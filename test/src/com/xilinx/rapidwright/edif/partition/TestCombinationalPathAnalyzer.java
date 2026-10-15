/* Copyright (c) 2026, Advanced Micro Devices, Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestCombinationalPathAnalyzer {
    private static CombinationalPathAnalyzer graph(boolean[] sequential, int[][] successors) {
        int[] owners = new int[sequential.length];
        for (int i = 0; i < owners.length; i++) owners[i] = i + 1;
        return new CombinationalPathAnalyzer(owners, sequential, successors);
    }

    @Test
    void detectsTransitWithoutSameBlockReentry() {
        CombinationalPathAnalyzer analyzer = graph(new boolean[] {true, false, true},
                new int[][] {{1}, {2}, {}});
        CombinationalPathAnalyzer.Analysis result = analyzer.analyze(new int[] {-1, 0, 1, 2});
        Assertions.assertTrue(result.complete);
        Assertions.assertEquals(2, result.maxCrossings);
        Assertions.assertEquals(1L, result.excessCrossings);
        Assertions.assertEquals(1, result.transitCount);
        Assertions.assertArrayEquals(new int[] {0, 1, 2}, result.worstPath);
    }

    @Test
    void captureStopsPathBeforeNextIndependentLaunch() {
        CombinationalPathAnalyzer analyzer = graph(new boolean[] {true, true, true},
                new int[][] {{1}, {2}, {}});
        CombinationalPathAnalyzer.Analysis result = analyzer.analyze(new int[] {0, 0, 1, 2});
        Assertions.assertEquals(1, result.maxCrossings);
        Assertions.assertEquals(0L, result.excessCrossings);
        Assertions.assertEquals(0, result.transitCount);
        Assertions.assertArrayEquals(new int[] {0, 1}, result.worstPath);
    }

    @Test
    void detectsReentryAndWholeCombinationalTransitRegion() {
        CombinationalPathAnalyzer analyzer = graph(new boolean[] {true, false, false, true},
                new int[][] {{1}, {2}, {3}, {}});
        CombinationalPathAnalyzer.Analysis result = analyzer.analyze(new int[] {0, 0, 1, 1, 0});
        Assertions.assertEquals(2, result.maxCrossings);
        Assertions.assertEquals(2, result.transitCount);
        Assertions.assertArrayEquals(new int[] {0, 1, 2, 3}, result.worstPath);
    }

    @Test
    void endpointExcessIsNotSumOverEveryReconvergentPath() {
        CombinationalPathAnalyzer analyzer = graph(new boolean[] {true, false, false, true, true},
                new int[][] {{1, 2}, {3, 4}, {3}, {}, {}});
        int[] partition = {0, 0, 1, 0, 2, 2};
        CombinationalPathAnalyzer.Analysis result = analyzer.analyze(partition);
        Assertions.assertEquals(2, result.maxCrossings);
        Assertions.assertEquals(2L, result.excessCrossings); // Two endpoint maxima.
        int[][] witnesses = analyzer.collectWorstPaths(partition, 1);
        Assertions.assertEquals(1, witnesses.length);
        Assertions.assertArrayEquals(new int[] {0, 1, 3}, witnesses[0]);
        Assertions.assertEquals(0, analyzer.collectWorstPaths(partition, 0).length);
    }

    @Test
    void terminalCombinationalNodesAreUnanchoredOutputEndpoints() {
        CombinationalPathAnalyzer analyzer = graph(new boolean[] {false, false, false},
                new int[][] {{1}, {2}, {}});
        CombinationalPathAnalyzer.Analysis result = analyzer.analyze(new int[] {0, 0, 1, 2});
        Assertions.assertEquals(2, result.maxCrossings);
        Assertions.assertArrayEquals(new int[] {0, 1, 2}, result.worstPath);
    }

    @Test
    void witnessBudgetKeepsCompleteWorstPathWithoutMaterializingEveryEndpointPath() {
        CombinationalPathAnalyzer analyzer = graph(new boolean[] {true, false, false, true, true},
                new int[][] {{1}, {2, 4}, {3}, {}, {}});
        int[] partition = {-1, 0, 1, 2, 3, 3};
        int[][] bounded = analyzer.collectWorstPaths(partition, 10, 4);
        Assertions.assertEquals(1, bounded.length);
        Assertions.assertArrayEquals(new int[] {0, 1, 2, 3}, bounded[0]);
        Assertions.assertEquals(1, analyzer.collectWorstPaths(partition, 10, 1).length);
        Assertions.assertEquals(2, analyzer.collectWorstPaths(partition, 10, 7).length);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> analyzer.collectWorstPaths(partition, 10, 0));
    }

    @Test
    void cyclesRemainUnknownAndSccCountExcludesBlockedDescendants() {
        CombinationalPathAnalyzer analyzer = graph(new boolean[] {true, false, false, false, true, true},
                new int[][] {{1}, {2}, {1, 3}, {}, {5}, {}});
        CombinationalPathAnalyzer.Analysis result = analyzer.analyze(new int[] {0, 0, 1, 2, 3, 0, 1});
        Assertions.assertFalse(result.complete);
        Assertions.assertEquals(2, result.cycleNodeCount);
        Assertions.assertEquals(3, result.unprocessedNodeCount);
        Assertions.assertEquals(1, result.maxCrossings); // A lower bound, not a clean certificate.
        Assertions.assertArrayEquals(new int[] {4, 5}, result.worstPath);
    }

    @Test
    void distinguishesCombinationalAndRegisteredSelfFeedback() {
        CombinationalPathAnalyzer.Analysis comb = graph(new boolean[] {false},
                new int[][] {{0}}).analyze(new int[] {0, 0});
        Assertions.assertFalse(comb.complete);
        Assertions.assertEquals(1, comb.cycleNodeCount);
        CombinationalPathAnalyzer.Analysis registered = graph(new boolean[] {true},
                new int[][] {{0}}).analyze(new int[] {0, 0});
        Assertions.assertTrue(registered.complete);
        Assertions.assertEquals(0, registered.maxCrossings);
        Assertions.assertArrayEquals(new int[] {0, 0}, registered.worstPath);
    }

    @Test
    void ownersUsePaddedOneBasedMappingAndMayShareAFrontierVertex() {
        CombinationalPathAnalyzer analyzer = new CombinationalPathAnalyzer(new int[] {2, 2, 5},
                new boolean[] {true, false, true}, new int[][] {{1}, {2}, {}});
        Assertions.assertEquals(1, analyzer.analyze(new int[] {-1, -1, 0, -1, -1, 1}).maxCrossings);
        Assertions.assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(new int[] {0, 0}));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze(new int[] {0, 0, -1, 0, 0, 1}));
    }

    @Test
    void exactMetricsMatchExhaustiveSmallDagEnumeration() {
        Random random = new Random(719083L);
        for (int trial = 0; trial < 200; trial++) {
            int n = 8;
            boolean[] sequential = new boolean[n];
            int[][] successors = new int[n][];
            int[] partition = new int[n + 1];
            for (int u = 0; u < n; u++) {
                sequential[u] = random.nextInt(4) == 0;
                partition[u + 1] = random.nextInt(3);
                List<Integer> next = new ArrayList<>();
                for (int v = u + 1; v < n; v++) if (random.nextInt(4) == 0) next.add(v);
                successors[u] = next.stream().mapToInt(Integer::intValue).toArray();
            }
            CombinationalPathAnalyzer analyzer = graph(sequential, successors);
            CombinationalPathAnalyzer.Analysis actual = analyzer.analyze(partition);
            int[] endpointMax = new int[n];
            Arrays.fill(endpointMax, -1);
            boolean[] transit = new boolean[n];
            int[] indegree = new int[n];
            for (int[] next : successors) for (int v : next) indegree[v]++;
            for (int u = 0; u < n; u++) {
                if (sequential[u] || indegree[u] == 0) {
                    enumerate(u, new ArrayList<>(), sequential, successors, partition, endpointMax, transit);
                }
            }
            int max = 0;
            long excess = 0;
            int transitCount = 0;
            for (int u = 0; u < n; u++) {
                max = Math.max(max, endpointMax[u]);
                excess += Math.max(0, endpointMax[u] - 1);
                if (transit[u]) transitCount++;
            }
            Assertions.assertTrue(actual.complete);
            Assertions.assertEquals(max, actual.maxCrossings, "trial " + trial);
            Assertions.assertEquals(excess, actual.excessCrossings, "trial " + trial);
            Assertions.assertEquals(transitCount, actual.transitCount, "trial " + trial);
            Assertions.assertEquals(max, crossings(actual.worstPath, partition));
        }
    }

    private static void enumerate(int u, List<Integer> prefix, boolean[] sequential, int[][] successors,
            int[] partition, int[] endpointMax, boolean[] transit) {
        prefix.add(u);
        if ((sequential[u] && prefix.size() > 1) || successors[u].length == 0) {
            int[] path = prefix.stream().mapToInt(Integer::intValue).toArray();
            endpointMax[u] = Math.max(endpointMax[u], crossings(path, partition));
            for (int i = 0; i < path.length; i++) {
                if (sequential[path[i]]) continue;
                int first = i;
                int last = i;
                while (first > 0 && partition[path[first - 1] + 1] == partition[path[i] + 1]) first--;
                while (last + 1 < path.length && partition[path[last + 1] + 1] == partition[path[i] + 1]) last++;
                if (first > 0 && last + 1 < path.length) transit[path[i]] = true;
            }
        } else {
            for (int v : successors[u]) enumerate(v, prefix, sequential, successors, partition, endpointMax, transit);
        }
        prefix.remove(prefix.size() - 1);
    }

    private static int crossings(int[] path, int[] partition) {
        int result = 0;
        for (int i = 1; i < path.length; i++) if (partition[path[i - 1] + 1] != partition[path[i] + 1]) result++;
        return result;
    }
}
