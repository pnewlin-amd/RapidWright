package com.xilinx.rapidwright.edif.partition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Standalone assertions plus exhaustive independent path enumeration. */
public final class RouteHopProof {
    private static long exhaustiveCases;
    private static int fixtures;

    private static void equal(long expected, long actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static int[] owners(int n) {
        int[] result = new int[n];
        for (int i = 0; i < n; i++) result[i] = i + 1;
        return result;
    }
    private static int[] parts(int... values) {
        int[] result = new int[values.length + 1];
        result[0] = -1;
        System.arraycopy(values, 0, result, 1, values.length);
        return result;
    }
    private static int[][] chain(int n) {
        int[][] h = new int[n][n];
        for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) h[a][b] = Math.abs(a - b);
        return h;
    }
    private static MinimumPhysicalHopAnalyzer.Analysis analyze(boolean[] seq, int[][] graph, int[][] hops, int... assignment) {
        return new MinimumPhysicalHopAnalyzer(owners(seq.length), seq, graph, hops).analyze(parts(assignment));
    }
    private static void topologyFixtures(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path weighted = directory.resolve("weighted_chain.graph");
        Files.writeString(weighted, "% rapidwright_cost_scale=10\n3 2 1\n2 7\n1 7 3 11\n2 11\n");
        PartitionTopology topology = PartitionTopology.read(weighted, 3);
        equal(2, topology.minimumHops(0, 2), "BFS actual link count");
        check(Math.abs(topology.distance(0, 2) - 1.8) < 1e-12, "Weighted distance preserved");
        check((long) Math.floor(topology.distance(0, 2)) != topology.minimumHops(0, 2), "Never floor arbitrary cost units");
        int[][] copy = topology.minimumHopMatrix();
        copy[0][2] = 99;
        equal(2, topology.minimumHops(0, 2), "Matrix getter defensive copy");
        Path triangle = directory.resolve("weighted_triangle.graph");
        Files.writeString(triangle, "3 3 1\n2 1 3 99\n1 1 3 1\n1 99 2 1\n");
        topology = PartitionTopology.read(triangle, 3);
        equal(1, topology.minimumHops(0, 2), "Direct physical link exists despite expensive cost");
        check(topology.distance(0, 2) == 2, "Weighted minimum chooses cheaper two-link route");
        Path disconnected = directory.resolve("disconnected.graph");
        Files.writeString(disconnected, "3 1 1\n2 1\n1 1\n\n");
        boolean failed = false;
        try { PartitionTopology.read(disconnected, 3); } catch (IllegalArgumentException expected) { failed = true; }
        check(failed, "Disconnected physical topology fails");
        fixtures += 3;
    }
    private static void pathFixtures() {
        MinimumPhysicalHopAnalyzer.Analysis oneCut = analyze(new boolean[] {true, true}, new int[][] {{1}, {}}, chain(4), 0, 3);
        equal(1, oneCut.logical.maxCrossings, "One logical cut");
        equal(3, oneCut.maxMinimumLinkHops, "Three minimum physical links");
        equal(2, oneCut.maxMinimumRelayTraversals, "Two intermediate relay traversals");
        check(oneCut.complete, "Complete chain report");

        MinimumPhysicalHopAnalyzer.Analysis reset = analyze(new boolean[] {true, false, true, false, true},
                new int[][] {{1}, {2}, {3}, {4}, {}}, chain(4), 0, 0, 3, 3, 0);
        equal(3, reset.maxMinimumLinkHops, "Capture stops incoming path before independent launch");
        equal(2, reset.maxMinimumRelayTraversals, "Relay metric resets at capture");
        equal(1, reset.logical.maxCrossings, "Logical reset remains unchanged");

        MinimumPhysicalHopAnalyzer.Analysis reconvergent = analyze(new boolean[] {true, false, false, false, true},
                new int[][] {{1, 2}, {3}, {3}, {4}, {}}, chain(4), 0, 1, 3, 1, 0);
        equal(6, reconvergent.maxMinimumLinkHops, "Reconvergent longest route");
        equal(3, reconvergent.maxMinimumRelayTraversals, "Reconvergent relay lower bound");

        MinimumPhysicalHopAnalyzer.Analysis independent = analyze(new boolean[] {true, false, false, true, true},
                new int[][] {{1, 4}, {2}, {3}, {}, {}}, chain(4), 0, 1, 0, 2, 3);
        equal(4, independent.maxMinimumLinkHops, "Link maximum path");
        equal(2, independent.maxMinimumRelayTraversals, "Relay maximum can be a different path");
        check(independent.maxMinimumRelayTraversals != independent.maxMinimumLinkHops - independent.logical.maxCrossings,
                "Cannot subtract independent maxima to derive relay maximum");

        MinimumPhysicalHopAnalyzer.Analysis cycle = analyze(new boolean[] {false, false, false}, new int[][] {{1}, {0, 2}, {}}, chain(4), 0, 3, 1);
        check(!cycle.complete, "Cyclic graph must never claim clean physical paths");
        equal(2, cycle.logical.cycleNodeCount, "Actual SCC nodes");
        equal(3, cycle.logical.unprocessedNodeCount, "SCC and downstream blocked nodes");
        MinimumPhysicalHopAnalyzer.Analysis sequentialCycle = analyze(new boolean[] {true, true}, new int[][] {{1}, {0}}, chain(4), 0, 3);
        check(sequentialCycle.complete, "Feedback through capture remains fully analyzable");
        equal(3, sequentialCycle.maxMinimumLinkHops, "No accumulation across sequential feedback");

        MinimumPhysicalHopAnalyzer.Analysis unavailable = analyze(new boolean[] {true, true}, new int[][] {{1}, {}}, null, 0, 3);
        check(unavailable.logical.complete && !unavailable.complete && !unavailable.physicalHopAnalysisAvailable,
                "Missing topology does not invent complete physical graph");
        equal(-1, unavailable.maxMinimumLinkHops, "Unavailable rather than invented zero");
        MinimumPhysicalHopAnalyzer.Analysis empty = analyze(new boolean[0], new int[0][], chain(4));
        check(empty.complete, "Empty modeled graph is complete with provided topology");
        equal(0, empty.maxMinimumLinkHops, "Empty path link maximum");
        fixtures += 8;
    }

    private static void walk(int u, boolean[] seq, int[][] graph, int[] assignment, int[][] hops,
            long links, long relays, long crossings, long[] maxima) {
        if (graph[u].length == 0) update(maxima, links, relays, crossings);
        for (int v : graph[u]) {
            int h = hops[assignment[u + 1]][assignment[v + 1]];
            long nextLinks = links + h, nextRelays = relays + Math.max(0, h - 1);
            long nextCrossings = crossings + (assignment[u + 1] == assignment[v + 1] ? 0 : 1);
            if (seq[v]) update(maxima, nextLinks, nextRelays, nextCrossings);
            else walk(v, seq, graph, assignment, hops, nextLinks, nextRelays, nextCrossings, maxima);
        }
    }
    private static void update(long[] maxima, long links, long relays, long crossings) {
        maxima[0] = Math.max(maxima[0], links);
        maxima[1] = Math.max(maxima[1], relays);
        maxima[2] = Math.max(maxima[2], crossings);
    }
    private static void exhaustiveTinyDags() {
        int n = 4;
        List<int[]> possible = new ArrayList<>();
        for (int u = 0; u < n; u++) for (int v = u + 1; v < n; v++) possible.add(new int[] {u, v});
        for (int edgeMask = 0; edgeMask < (1 << possible.size()); edgeMask++) {
            int[][] graph = new int[n][];
            for (int u = 0; u < n; u++) {
                List<Integer> row = new ArrayList<>();
                for (int e = 0; e < possible.size(); e++) if ((edgeMask & (1 << e)) != 0 && possible.get(e)[0] == u) row.add(possible.get(e)[1]);
                graph[u] = row.stream().mapToInt(Integer::intValue).toArray();
            }
            for (int sequentialMask = 0; sequentialMask < (1 << n); sequentialMask++) {
                boolean[] seq = new boolean[n];
                for (int u = 0; u < n; u++) seq[u] = (sequentialMask & (1 << u)) != 0;
                for (int[][] physical : new int[][][] {chain(3), PartitionTopology.complete(3).minimumHopMatrix()}) {
                    MinimumPhysicalHopAnalyzer analyzer = new MinimumPhysicalHopAnalyzer(owners(n), seq, graph, physical);
                    CombinationalPathAnalyzer integrated = new CombinationalPathAnalyzer(owners(n), seq, graph);
                    for (int code = 0; code < 81; code++) {
                        int[] assignment = new int[n + 1];
                        int remaining = code;
                        for (int u = 1; u <= n; u++) { assignment[u] = remaining % 3; remaining /= 3; }
                        long[] oracle = new long[3];
                        // Independent enumeration includes all starts. With nonnegative
                        // edge weights, internal starts cannot exceed full-prefix maxima.
                        for (int u = 0; u < n; u++) walk(u, seq, graph, assignment, physical, 0, 0, 0, oracle);
                        MinimumPhysicalHopAnalyzer.Analysis actual = analyzer.analyze(assignment);
                        check(actual.complete, "Every ordered tiny DAG is complete");
                        equal(oracle[0], actual.maxMinimumLinkHops, "Exhaustive links case" + exhaustiveCases);
                        equal(oracle[1], actual.maxMinimumRelayTraversals, "Exhaustive relays case" + exhaustiveCases);
                        equal(oracle[2], actual.logical.maxCrossings, "Existing logical metric parity" + exhaustiveCases);
                        CombinationalPathAnalyzer.Analysis added = integrated.analyze(assignment, physical);
                        equal(oracle[0], added.maxMinimumLinkHops, "Integrated exact H" + exhaustiveCases);
                        equal(oracle[1], added.maxMinimumRelayTraversals, "Integrated independent R" + exhaustiveCases);
                        exhaustiveCases++;
                    }
                }
            }
        }
    }
    private static void completeJ4() {
        int[][] graph = {{1}, {2}, {3}, {4}, {}};
        boolean[] seq = {true, false, false, false, true};
        MinimumPhysicalHopAnalyzer analyzer = new MinimumPhysicalHopAnalyzer(owners(5), seq, graph,
                PartitionTopology.complete(4).minimumHopMatrix());
        for (int code = 0; code < 1024; code++) {
            int[] assignment = new int[6];
            int remaining = code;
            for (int u = 1; u <= 5; u++) { assignment[u] = remaining % 4; remaining /= 4; }
            MinimumPhysicalHopAnalyzer.Analysis value = analyzer.analyze(assignment);
            equal(value.logical.maxCrossings, value.maxMinimumLinkHops, "Complete J4 link/logical equality");
            equal(0, value.maxMinimumRelayTraversals, "Complete J4 needs no physical relay lower bound");
        }
        fixtures++;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Provide workspace-local fixture directory");
        topologyFixtures(Path.of(args[0]));
        pathFixtures();
        completeJ4();
        exhaustiveTinyDags();
        System.out.println("PASS fixtures=" + fixtures + " exhaustive_dag_mapping_cases=" + exhaustiveCases + " complete_j4_mappings=1024");
    }
}
