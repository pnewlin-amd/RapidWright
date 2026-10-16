/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.edif.partition;

import java.util.Arrays;

/** Report-only prototype. Lower bounds on declared physical link traversals,
 * never actual multicast routes, transport capture, congestion, nanoseconds or
 * DUT clock. Constructor graph arrays follow CombinationalPathAnalyzer's API.
 */
public final class MinimumPhysicalHopAnalyzer {
    private final int[] owners;
    private final boolean[] sequential;
    private final int[][] successors;
    private final int[][] hops;
    private final CombinationalPathAnalyzer logical;
    private final int[] order;
    private final int processed;

    public static final class Analysis {
        public final CombinationalPathAnalyzer.Analysis logical;
        public final boolean physicalHopAnalysisAvailable;
        public final boolean complete;
        /** Max over register-free paths of sum of per-owner-pair minimum links. */
        public final long maxMinimumLinkHops;
        /** Independent max over paths of sum(max(0, minimumLinks - 1)). */
        public final long maxMinimumRelayTraversals;

        private Analysis(CombinationalPathAnalyzer.Analysis logical, boolean available, long links, long relays) {
            this.logical = logical;
            this.physicalHopAnalysisAvailable = available;
            this.complete = logical.complete && available;
            this.maxMinimumLinkHops = links;
            this.maxMinimumRelayTraversals = relays;
        }
    }

    public MinimumPhysicalHopAnalyzer(int[] owners, boolean[] sequential, int[][] successors, int[][] minimumHops) {
        logical = new CombinationalPathAnalyzer(owners, sequential, successors);
        this.owners = owners;
        this.sequential = sequential;
        this.successors = successors;
        hops = checkedCopy(minimumHops);
        int[] degree = new int[owners.length];
        for (int[] row : successors) for (int v : row) if (!sequential[v]) degree[v]++;
        order = new int[owners.length];
        int head = 0, tail = 0;
        for (int u = 0; u < degree.length; u++) if (degree[u] == 0) order[tail++] = u;
        while (head < tail) {
            int u = order[head++];
            for (int v : successors[u]) if (!sequential[v] && --degree[v] == 0) order[tail++] = v;
        }
        processed = tail;
    }

    private static int[][] checkedCopy(int[][] matrix) {
        if (matrix == null) return null; // Optional report; never invent a complete physical graph.
        if (matrix.length == 0) throw new IllegalArgumentException("Physical topology must have nodes");
        int[][] copy = new int[matrix.length][];
        for (int u = 0; u < matrix.length; u++) {
            if (matrix[u] == null || matrix[u].length != matrix.length) throw new IllegalArgumentException("Square hop matrix required");
            copy[u] = matrix[u].clone();
            for (int v = 0; v < matrix.length; v++) {
                if (copy[u][v] < 0 || (u == v ? copy[u][v] != 0 : copy[u][v] < 1)) {
                    throw new IllegalArgumentException("Connected nonnegative physical hop matrix required");
                }
            }
        }
        for (int u = 0; u < copy.length; u++) for (int v = 0; v < copy.length; v++) {
            if (copy[u][v] != copy[v][u]) throw new IllegalArgumentException("Undirected physical hop matrix required");
        }
        PartitionTopology.validateMinimumHopMatrix(copy);
        return copy;
    }

    public Analysis analyze(int[] vertexPartition) {
        CombinationalPathAnalyzer.Analysis original = logical.analyze(vertexPartition);
        if (original.unprocessedNodeCount != owners.length - processed) throw new IllegalStateException("Graph coverage mismatch");
        if (hops == null) return new Analysis(original, false, -1, -1);
        for (int owner : owners) if (vertexPartition[owner] >= hops.length) throw new IllegalArgumentException("Partition exceeds physical topology");
        long[] links = new long[owners.length], relays = new long[owners.length];
        long maxLinks = 0, maxRelays = 0;
        for (int i = 0; i < processed; i++) {
            int u = order[i];
            if (successors[u].length == 0) {
                maxLinks = Math.max(maxLinks, links[u]);
                maxRelays = Math.max(maxRelays, relays[u]);
            }
            int source = vertexPartition[owners[u]];
            for (int v : successors[u]) {
                int h = hops[source][vertexPartition[owners[v]]];
                long candidateLinks = Math.addExact(links[u], h);
                long candidateRelays = Math.addExact(relays[u], Math.max(0, h - 1));
                if (sequential[v]) {
                    // Incoming links count before capture; this node's launch remains zero.
                    maxLinks = Math.max(maxLinks, candidateLinks);
                    maxRelays = Math.max(maxRelays, candidateRelays);
                } else {
                    links[v] = Math.max(links[v], candidateLinks);
                    relays[v] = Math.max(relays[v], candidateRelays);
                }
            }
        }
        return new Analysis(original, true, maxLinks, maxRelays);
    }
}
