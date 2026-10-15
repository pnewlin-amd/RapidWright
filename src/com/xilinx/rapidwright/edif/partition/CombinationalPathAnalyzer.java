/* Copyright (c) 2026, Advanced Micro Devices, Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Mapping-dependent analysis of a directed primitive dependency graph. Primitive
 * IDs are zero based, frontier owner IDs are one based, and partition arrays have a dummy index zero and use
 * {@code partition[ownerId]}. The caller must not mutate constructor arrays.
 *
 * A sequential primitive is both a capture endpoint and an independent launch
 * point. An incoming crossing is counted before stopping at a capture; no path
 * continues through the sequential primitive. Combinational roots and terminals
 * stand in for unanchored input/output boundaries. Accuracy is conditional on
 * the caller's dependency arcs and sequential classification: this class does
 * not infer timing arcs or assume that a module containing a register is a
 * barrier. In particular, a cycle is reported as incomplete analysis.
 */
public final class CombinationalPathAnalyzer {
    private final int[] ownerVertex;
    private final boolean[] sequential;
    private final int[][] successors;
    private final int[][] predecessors;
    private final int[] topologicalOrder;
    private final int processedCount;
    private final int cycleNodeCount;
    private final int maxOwner;

    /**
     * Additive endpoint-kind diagnostic, not a register-to-register timing result.
     * Modeled capture endpoints include synthetic memory-write captures and may
     * be reached from unanchored combinational roots. A sequential terminal with
     * no incoming data path retains the existing zero-length endpoint fallback.
     * On an incomplete graph the evaluated subset is not a class-wide maximum.
     */
    public static final class EndpointSummary {
        public final int modeledEndpointCount;
        public final int evaluatedEndpointCount;
        public final int maxCrossings;
        /** Same endpoint-max excess definition as Analysis.excessCrossings. */
        public final long excessCrossings;
        public final boolean minimumLinkHopAnalysisAvailable;
        /** Zero for an empty evaluated set; counts must be consulted. */
        public final long maxMinimumLinkHops;

        private EndpointSummary(int modeledCount, int evaluatedCount, int maximum,
                long excess) {
            modeledEndpointCount = modeledCount; evaluatedEndpointCount = evaluatedCount;
            maxCrossings = maximum; excessCrossings = excess;
            minimumLinkHopAnalysisAvailable = false; maxMinimumLinkHops = -1;
        }

        private EndpointSummary(EndpointSummary logical, long maximumLinks) {
            modeledEndpointCount = logical.modeledEndpointCount;
            evaluatedEndpointCount = logical.evaluatedEndpointCount;
            maxCrossings = logical.maxCrossings; excessCrossings = logical.excessCrossings;
            minimumLinkHopAnalysisAvailable = true; maxMinimumLinkHops = maximumLinks;
        }
    }

    /** Results are exact on the supplied graph when {@link #complete} is true. */
    public static final class Analysis {
        /** Maximum boundary changes on one register-free path, including capture. */
        public final int maxCrossings;
        /**
         * Sum over capture/terminal endpoints of max(0, their maximum crossings
         * minus one). This is NOT a sum over the exponentially many paths.
         */
        public final long excessCrossings;
        /** Actual nodes in cyclic combinational strongly connected components. */
        public final int cycleNodeCount;
        /** Cyclic nodes plus descendants whose predecessor order is unavailable. */
        public final int unprocessedNodeCount;
        /**
         * Number of combinational primitives reachable from a cut ingress and
         * able to reach a cut egress within their own FPGA without a register.
         * Counts primitives, not paths; also detects A -> B -> C transit.
         */
        public final int transitCount;
        public final boolean complete;
        /** Primitive-ID witness for maxCrossings (empty for an empty graph). */
        public final int[] worstPath;
        public final boolean minimumLinkHopAnalysisAvailable;
        public final long maxMinimumLinkHops;
        public final long maxMinimumRelayTraversals;
        public final EndpointSummary modeledCaptureEndpoints;
        public final EndpointSummary combinationalTerminalEndpoints;

        private Analysis(int maxCrossings, long excessCrossings, int cycleNodeCount,
                int unprocessedNodeCount, int transitCount, int[] worstPath,
                EndpointSummary captures, EndpointSummary terminals) {
            this.maxCrossings = maxCrossings;
            this.excessCrossings = excessCrossings;
            this.cycleNodeCount = cycleNodeCount;
            this.unprocessedNodeCount = unprocessedNodeCount;
            this.transitCount = transitCount;
            this.complete = unprocessedNodeCount == 0;
            this.worstPath = worstPath;
            this.minimumLinkHopAnalysisAvailable = false;
            this.maxMinimumLinkHops = -1;
            this.maxMinimumRelayTraversals = -1;
            modeledCaptureEndpoints = captures;
            combinationalTerminalEndpoints = terminals;
        }

        private Analysis(Analysis logical, RoutePaths route) {
            maxCrossings = logical.maxCrossings; excessCrossings = logical.excessCrossings;
            cycleNodeCount = logical.cycleNodeCount; unprocessedNodeCount = logical.unprocessedNodeCount;
            transitCount = logical.transitCount; complete = logical.complete; worstPath = logical.worstPath;
            minimumLinkHopAnalysisAvailable = true;
            maxMinimumLinkHops = route.maximumLinks;
            maxMinimumRelayTraversals = route.maximumRelays;
            modeledCaptureEndpoints = new EndpointSummary(logical.modeledCaptureEndpoints, route.maximumCaptureLinks);
            combinationalTerminalEndpoints = new EndpointSummary(logical.combinationalTerminalEndpoints, route.maximumTerminalLinks);
        }

        public int getMaxCrossings() { return maxCrossings; }
        public long getExcessCrossings() { return excessCrossings; }
        public int getCycleNodeCount() { return cycleNodeCount; }
        public int getUnprocessedNodeCount() { return unprocessedNodeCount; }
        public int getTransitCount() { return transitCount; }
        public boolean isComplete() { return complete; }
        public int[] getWorstPath() { return worstPath.clone(); }
    }

    public CombinationalPathAnalyzer(int[] ownerVertex, boolean[] sequential, int[][] successors) {
        if (ownerVertex == null || sequential == null || successors == null
                || ownerVertex.length != sequential.length || ownerVertex.length != successors.length) {
            throw new IllegalArgumentException("Primitive graph arrays must have equal non-null lengths");
        }
        this.ownerVertex = ownerVertex;
        this.sequential = sequential;
        this.successors = successors;
        int n = ownerVertex.length;
        int ownerLimit = 0;
        int[] incomingCount = new int[n];
        int[] indegree = new int[n];
        for (int u = 0; u < n; u++) {
            if (ownerVertex[u] < 1) throw new IllegalArgumentException("Owner vertex IDs must be positive");
            ownerLimit = Math.max(ownerLimit, ownerVertex[u]);
            if (successors[u] == null) throw new IllegalArgumentException("Null successor list at primitive " + u);
            for (int v : successors[u]) {
                if (v < 0 || v >= n) throw new IllegalArgumentException("Invalid successor ID " + v);
                incomingCount[v]++;
                // Removing arcs into sequential cells splits launch and capture.
                if (!sequential[v]) indegree[v]++;
            }
        }
        maxOwner = ownerLimit;
        predecessors = new int[n][];
        for (int u = 0; u < n; u++) predecessors[u] = new int[incomingCount[u]];
        Arrays.fill(incomingCount, 0);
        for (int u = 0; u < n; u++) {
            for (int v : successors[u]) predecessors[v][incomingCount[v]++] = u;
        }
        topologicalOrder = new int[n];
        int head = 0;
        int tail = 0;
        for (int u = 0; u < n; u++) if (indegree[u] == 0) topologicalOrder[tail++] = u;
        while (head < tail) {
            int u = topologicalOrder[head++];
            for (int v : successors[u]) {
                if (!sequential[v] && --indegree[v] == 0) topologicalOrder[tail++] = v;
            }
        }
        processedCount = tail;
        cycleNodeCount = tail == n ? 0 : countCyclicNodes(indegree);
    }

    private static final class Paths {
        final int[] partition;
        final int[] distance;
        final int[] predecessor;
        final int[] endpointDistance;
        final int[] endpointPredecessor;
        Paths(int[] partition, int n) {
            this.partition = partition;
            distance = new int[n];
            predecessor = new int[n];
            endpointDistance = new int[n];
            endpointPredecessor = new int[n];
            Arrays.fill(predecessor, -1);
            Arrays.fill(endpointDistance, -1);
            Arrays.fill(endpointPredecessor, -1);
        }
    }

    private Paths paths(int[] vertexPartition) {
        if (vertexPartition == null || vertexPartition.length <= maxOwner) {
            throw new IllegalArgumentException("Partition array must cover every owner vertex");
        }
        for (int owner : ownerVertex) {
            if (vertexPartition[owner] < 0) {
                throw new IllegalArgumentException("Partition IDs must be nonnegative for mapped owners");
            }
        }
        Paths paths = new Paths(vertexPartition, ownerVertex.length);
        for (int i = 0; i < processedCount; i++) {
            int u = topologicalOrder[i];
            if (successors[u].length == 0) {
                // A sequential endpoint may already have captured a path before
                // its independent launch node is visited in the queue.
                paths.endpointDistance[u] = Math.max(paths.endpointDistance[u], paths.distance[u]);
            }
            int part = vertexPartition[ownerVertex[u]];
            for (int v : successors[u]) {
                int candidate = paths.distance[u]
                        + (part == vertexPartition[ownerVertex[v]] ? 0 : 1);
                if (sequential[v]) {
                    if (candidate > paths.endpointDistance[v]
                            || (candidate == paths.endpointDistance[v]
                                && u < paths.endpointPredecessor[v])) {
                        paths.endpointDistance[v] = candidate;
                        paths.endpointPredecessor[v] = u;
                    }
                } else if (candidate > paths.distance[v]
                        || (candidate == paths.distance[v]
                            && (paths.predecessor[v] < 0 || u < paths.predecessor[v]))) {
                    paths.distance[v] = candidate;
                    paths.predecessor[v] = u;
                }
            }
        }
        return paths;
    }

    public Analysis analyze(int[] vertexPartition) {
        Paths paths = paths(vertexPartition);
        int maximum = 0;
        int worstEndpoint = -1;
        long excess = 0;
        int modeledCaptures = 0, modeledTerminals = 0;
        int evaluatedCaptures = 0, evaluatedTerminals = 0;
        int captureMaximum = 0, terminalMaximum = 0;
        long captureExcess = 0, terminalExcess = 0;
        for (int u = 0; u < ownerVertex.length; u++) {
            if (sequential[u]) {
                if (predecessors[u].length > 0 || successors[u].length == 0) modeledCaptures++;
            } else if (successors[u].length == 0) modeledTerminals++;
            int value = paths.endpointDistance[u];
            if (value >= 0 && (worstEndpoint < 0 || value > maximum)) {
                maximum = value;
                worstEndpoint = u;
            }
            if (value > 1) excess += value - 1L;
            if (value >= 0) {
                if (sequential[u]) {
                    evaluatedCaptures++; captureMaximum = Math.max(captureMaximum, value);
                    if (value > 1) captureExcess += value - 1L;
                } else {
                    evaluatedTerminals++; terminalMaximum = Math.max(terminalMaximum, value);
                    if (value > 1) terminalExcess += value - 1L;
                }
            }
        }
        boolean[] ingress = new boolean[ownerVertex.length];
        boolean[] egress = new boolean[ownerVertex.length];
        for (int i = 0; i < processedCount; i++) {
            int u = topologicalOrder[i];
            if (sequential[u]) continue;
            int part = vertexPartition[ownerVertex[u]];
            for (int v : predecessors[u]) {
                if (part != vertexPartition[ownerVertex[v]] || (!sequential[v] && ingress[v])) {
                    ingress[u] = true;
                    break;
                }
            }
        }
        int transit = 0;
        for (int i = processedCount - 1; i >= 0; i--) {
            int u = topologicalOrder[i];
            if (sequential[u]) continue;
            int part = vertexPartition[ownerVertex[u]];
            for (int v : successors[u]) {
                if (part != vertexPartition[ownerVertex[v]] || (!sequential[v] && egress[v])) {
                    egress[u] = true;
                    break;
                }
            }
            if (ingress[u] && egress[u]) transit++;
        }
        return new Analysis(maximum, excess, cycleNodeCount, ownerVertex.length - processedCount,
                transit, worstEndpoint < 0 ? new int[0] : reconstruct(paths, worstEndpoint),
                new EndpointSummary(modeledCaptures, evaluatedCaptures, captureMaximum, captureExcess),
                new EndpointSummary(modeledTerminals, evaluatedTerminals, terminalMaximum, terminalExcess));
    }

    /** Optional physical-link lower bounds. Existing logical API is unchanged. */
    public Analysis analyze(int[] vertexPartition, int[][] minimumHops) {
        Analysis original = analyze(vertexPartition);
        return minimumHops == null ? original : new Analysis(original, routePaths(vertexPartition, minimumHops, false));
    }

    private static final class RoutePaths {
        final long[] links, relays, endpoint;
        final int[] predecessor, endpointPredecessor;
        long maximumLinks, maximumRelays;
        long maximumCaptureLinks, maximumTerminalLinks;
        RoutePaths(int n, boolean witnesses) {
            links = new long[n]; relays = new long[n];
            endpoint = witnesses ? new long[n] : null;
            predecessor = witnesses ? new int[n] : null;
            endpointPredecessor = witnesses ? new int[n] : null;
            if (witnesses) {
                Arrays.fill(endpoint, -1); Arrays.fill(predecessor, -1); Arrays.fill(endpointPredecessor, -1);
            }
        }
    }

    private RoutePaths routePaths(int[] assignment, int[][] hops, boolean witnesses) {
        PartitionTopology.validateMinimumHopMatrix(hops);
        if (assignment == null || assignment.length <= maxOwner || hops.length == 0) throw new IllegalArgumentException("Invalid physical-hop assignment");
        for (int u = 0; u < hops.length; u++) {
            if (hops[u] == null || hops[u].length != hops.length) throw new IllegalArgumentException("Square hop matrix required");
            for (int v = 0; v < hops.length; v++) if (hops[u][v] < 0 || (u == v ? hops[u][v] != 0 : hops[u][v] < 1)) throw new IllegalArgumentException("Connected physical-hop matrix required");
        }
        for (int owner : ownerVertex) if (assignment[owner] < 0 || assignment[owner] >= hops.length) throw new IllegalArgumentException("Partition outside physical topology");
        RoutePaths result = new RoutePaths(ownerVertex.length, witnesses);
        for (int i = 0; i < processedCount; i++) {
            int u = topologicalOrder[i];
            if (successors[u].length == 0) {
                result.maximumLinks = Math.max(result.maximumLinks, result.links[u]);
                result.maximumRelays = Math.max(result.maximumRelays, result.relays[u]);
                if (sequential[u]) result.maximumCaptureLinks = Math.max(result.maximumCaptureLinks, result.links[u]);
                else result.maximumTerminalLinks = Math.max(result.maximumTerminalLinks, result.links[u]);
                if (witnesses) result.endpoint[u] = Math.max(result.endpoint[u], result.links[u]);
            }
            for (int v : successors[u]) {
                int h = hops[assignment[ownerVertex[u]]][assignment[ownerVertex[v]]];
                long links = Math.addExact(result.links[u], h);
                long relays = Math.addExact(result.relays[u], Math.max(0, h - 1));
                if (sequential[v]) {
                    result.maximumLinks = Math.max(result.maximumLinks, links);
                    result.maximumRelays = Math.max(result.maximumRelays, relays);
                    result.maximumCaptureLinks = Math.max(result.maximumCaptureLinks, links);
                    if (witnesses && (links > result.endpoint[v] || (links == result.endpoint[v] && u < result.endpointPredecessor[v]))) {
                        result.endpoint[v] = links; result.endpointPredecessor[v] = u;
                    }
                } else {
                    if (links > result.links[v] || (links == result.links[v] && (result.predecessor != null && (result.predecessor[v] < 0 || u < result.predecessor[v])))) {
                        result.links[v] = links;
                        if (witnesses) result.predecessor[v] = u;
                    }
                    result.relays[v] = Math.max(result.relays[v], relays);
                }
            }
        }
        return result;
    }

    /** Endpoint-maximal H witnesses, same bounds/ties as the logical sampler. */
    public int[][] collectWorstPaths(int[] assignment, int limit, int maxWitnessNodes, int[][] hops) {
        if (hops == null) return collectWorstPaths(assignment, limit, maxWitnessNodes);
        if (limit < 0 || maxWitnessNodes < 1) throw new IllegalArgumentException("Invalid witness budget");
        RoutePaths route = routePaths(assignment, hops, true);
        if (limit == 0) return new int[0][];
        Comparator<Integer> worstFirst = (a, b) -> {
            int cmp = Long.compare(route.endpoint[a], route.endpoint[b]);
            return cmp != 0 ? cmp : Integer.compare(b, a);
        };
        PriorityQueue<Integer> selected = new PriorityQueue<>(Math.min(limit, 1024), worstFirst);
        for (int u = 0; u < ownerVertex.length; u++) {
            if (route.endpoint[u] < 0) continue;
            if (selected.size() < limit) selected.add(u);
            else if (worstFirst.compare(u, selected.peek()) > 0) { selected.poll(); selected.add(u); }
        }
        List<Integer> endpoints = new ArrayList<>(selected);
        endpoints.sort(worstFirst.reversed());
        List<int[]> result = new ArrayList<>();
        long used = 0;
        for (int end : endpoints) {
            int first = sequential[end] && route.endpointPredecessor[end] >= 0 ? route.endpointPredecessor[end] : end;
            boolean capture = first != end || (sequential[end] && route.endpointPredecessor[end] == end);
            int length = capture ? 1 : 0;
            for (int u = first; u >= 0; u = route.predecessor[u]) length++;
            if (!result.isEmpty() && used + length > maxWitnessNodes) break;
            int[] path = new int[length]; int cursor = length;
            if (capture) path[--cursor] = end;
            for (int u = first; u >= 0; u = route.predecessor[u]) path[--cursor] = u;
            result.add(path); used += length;
        }
        return result.toArray(new int[0][]);
    }

    /**
     * Returns up to limit endpoint-maximal witnesses, ordered by descending
     * crossings, then endpoint ID. One path per endpoint is retained; this is a
     * bounded refinement sample, not an enumeration of all timing paths. Cyclic
     * graph portions have no complete witnesses and must be checked with analyze.
     */
    public int[][] collectWorstPaths(int[] vertexPartition, int limit) {
        return collectWorstPaths(vertexPartition, limit, 1_000_000);
    }

    /**
     * Also bounds the total primitive IDs retained in the sample. Always keeps
     * the first complete worst witness, even when it alone exceeds the budget;
     * subsequent paths stop before exceeding the budget. No path is truncated.
     * Endpoint selection precedes reconstruction, so memory is O(V+E+budget),
     * including a possible first witness of at most V+1 primitive IDs.
     */
    public int[][] collectWorstPaths(int[] vertexPartition, int limit, int maxWitnessNodes) {
        if (limit < 0) throw new IllegalArgumentException("Path limit must be nonnegative");
        if (maxWitnessNodes < 1) throw new IllegalArgumentException("Witness-node budget must be positive");
        Paths paths = paths(vertexPartition);
        if (limit == 0) return new int[0][];
        Comparator<Integer> worstFirst = (a, b) -> {
            int compare = Integer.compare(paths.endpointDistance[a], paths.endpointDistance[b]);
            return compare != 0 ? compare : Integer.compare(b, a);
        };
        PriorityQueue<Integer> selected = new PriorityQueue<>(Math.min(limit, 1024), worstFirst);
        for (int u = 0; u < ownerVertex.length; u++) {
            if (paths.endpointDistance[u] < 0) continue;
            if (selected.size() < limit) selected.add(u);
            else if (worstFirst.compare(u, selected.peek()) > 0) {
                selected.poll();
                selected.add(u);
            }
        }
        List<Integer> endpoints = new ArrayList<>(selected);
        endpoints.sort(worstFirst.reversed());
        List<int[]> result = new ArrayList<>(endpoints.size());
        long usedNodes = 0;
        for (int endpoint : endpoints) {
            int length = pathLength(paths, endpoint);
            if (!result.isEmpty() && usedNodes + length > maxWitnessNodes) break;
            result.add(reconstruct(paths, endpoint, length));
            usedNodes += length;
        }
        return result.toArray(new int[0][]);
    }

    /** Held cohort hook: select only capture endpoints, never a terminal proxy.
     * Priority follows maximum selected-topology links, crossings, endpoint ID.
     * The original endpoint/path APIs keep their existing behavior. */
    public int[] worstCaptureEndpoints(int[] assignment, int[][] hops, int limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative capture endpoint limit");
        if (limit == 0) return new int[0];
        Paths logical = paths(assignment);
        RoutePaths route = hops == null ? null : routePaths(assignment, hops, true);
        Comparator<Integer> worstFirst = (a, b) -> {
            int comparison = route == null ? 0 : Long.compare(route.endpoint[a], route.endpoint[b]);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(logical.endpointDistance[a], logical.endpointDistance[b]);
            return comparison != 0 ? comparison : Integer.compare(b, a);
        };
        PriorityQueue<Integer> selected = new PriorityQueue<>(Math.min(limit, 1024), worstFirst);
        for (int node = 0; node < ownerVertex.length; node++) {
            if (!sequential[node] || logical.endpointDistance[node] < 0
                    || route != null && route.endpoint[node] < 0) continue;
            if (selected.size() < limit) selected.add(node);
            else if (worstFirst.compare(node, selected.peek()) > 0) { selected.poll(); selected.add(node); }
        }
        List<Integer> result = new ArrayList<>(selected); result.sort(worstFirst.reversed());
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Copy only a bounded requested fanin row; no second reverse index. */
    int[] cohortPredecessors(int node, int remainingEntries) {
        if (node < 0 || node >= predecessors.length || remainingEntries < 0) throw new IllegalArgumentException("Invalid bounded fanin request");
        return predecessors[node].length > remainingEntries ? null : predecessors[node].clone();
    }

    private int pathLength(Paths paths, int endpoint) {
        int first = sequential[endpoint] && paths.endpointPredecessor[endpoint] >= 0
                ? paths.endpointPredecessor[endpoint] : endpoint;
        int length = first == endpoint ? 0 : 1;
        // A self-loop on a sequential cell is a one-cycle path with two endpoints.
        if (sequential[endpoint] && paths.endpointPredecessor[endpoint] == endpoint) length = 1;
        for (int u = first; u >= 0; u = paths.predecessor[u]) length++;
        return length;
    }

    private int[] reconstruct(Paths paths, int endpoint) {
        return reconstruct(paths, endpoint, pathLength(paths, endpoint));
    }

    private int[] reconstruct(Paths paths, int endpoint, int length) {
        int first = sequential[endpoint] && paths.endpointPredecessor[endpoint] >= 0
                ? paths.endpointPredecessor[endpoint] : endpoint;
        int[] result = new int[length];
        int index = length;
        if (first != endpoint || (sequential[endpoint] && paths.endpointPredecessor[endpoint] == endpoint)) {
            result[--index] = endpoint;
        }
        for (int u = first; u >= 0; u = paths.predecessor[u]) result[--index] = u;
        return result;
    }

    /** Iterative Kosaraju on Kahn's residual, so long designs cannot overflow the stack. */
    private int countCyclicNodes(int[] residualIndegree) {
        int n = ownerVertex.length;
        boolean[] visited = new boolean[n];
        int residualCount = n - processedCount;
        int[] stack = new int[residualCount];
        int[] cursor = new int[residualCount];
        int[] finish = new int[residualCount];
        int finishCount = 0;
        for (int root = 0; root < n; root++) {
            if (residualIndegree[root] <= 0 || visited[root]) continue;
            int size = 1;
            stack[0] = root;
            cursor[0] = 0;
            visited[root] = true;
            while (size > 0) {
                int u = stack[size - 1];
                if (cursor[size - 1] == successors[u].length) {
                    finish[finishCount++] = u;
                    size--;
                    continue;
                }
                int v = successors[u][cursor[size - 1]++];
                if (residualIndegree[v] > 0 && !visited[v]) {
                    visited[v] = true;
                    stack[size] = v;
                    cursor[size++] = 0;
                }
            }
        }
        Arrays.fill(visited, false);
        int cyclic = 0;
        for (int i = finishCount - 1; i >= 0; i--) {
            int root = finish[i];
            if (visited[root]) continue;
            int size = 1;
            int componentSize = 0;
            boolean selfLoop = false;
            stack[0] = root;
            visited[root] = true;
            while (size > 0) {
                int u = stack[--size];
                componentSize++;
                for (int v : successors[u]) if (v == u) selfLoop = true;
                for (int v : predecessors[u]) {
                    if (residualIndegree[v] > 0 && !visited[v]) {
                        visited[v] = true;
                        stack[size++] = v;
                    }
                }
            }
            if (componentSize > 1 || selfLoop) cyclic += componentSize;
        }
        return cyclic;
    }
}
