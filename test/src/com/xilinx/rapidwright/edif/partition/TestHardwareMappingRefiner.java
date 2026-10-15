/* Copyright (c) 2026, Advanced Micro Devices, Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestHardwareMappingRefiner {
    @TempDir
    Path directory;

    private static HardwarePartitionGraph fixture(boolean[] sequential, int[][] successors,
            long[][] resources, long[] logic, List<HardwarePartitionGraph.Net> extraNets) {
        int n = sequential.length;
        int[] owner = new int[n];
        String[] names = new String[n];
        List<HardwarePartitionGraph.Net> nets = new ArrayList<>();
        for (int u = 0; u < n; u++) {
            owner[u] = u + 1;
            names[u] = "primitive_" + u;
            if (successors[u].length > 0) {
                int[] sinks = Arrays.stream(successors[u]).map(v -> v + 1).toArray();
                nets.add(new HardwarePartitionGraph.Net(u + 1, sinks));
            }
        }
        nets.addAll(extraNets);
        return new HardwarePartitionGraph(owner, sequential, successors, names, nets,
                Collections.emptyMap(), resources, logic, 0, 0);
    }

    private static long[][] luts(int n) {
        long[][] resources = new long[n + 1][6];
        for (int v = 1; v <= n; v++) resources[v][0] = 1;
        return resources;
    }

    private static int[] unfixed(int n) {
        int[] fixed = new int[n + 1];
        Arrays.fill(fixed, -1);
        return fixed;
    }

    private HardwareMappingRefiner refiner(HardwarePartitionGraph graph, int k, int[] fixed,
            double snakeWeight, double budget, Path capacities) throws IOException {
        return new HardwareMappingRefiner(graph, PartitionTopology.complete(k), k,
                0.0, fixed, snakeWeight, budget, capacities);
    }

    @Test
    void repairsRealLogicOccupancyEvenWhenBackendBlockContainsVirtualVertex() throws IOException {
        long[][] resources = luts(3);
        resources[3][0] = 0;
        HardwarePartitionGraph graph = fixture(new boolean[] {true, true, false},
                new int[][] {{}, {}, {}}, resources, new long[] {0, 1, 1, 0}, Collections.emptyList());
        int[] fixed = unfixed(3);
        fixed[3] = 1;
        int[] actual = refiner(graph, 2, fixed, 100, 0, null)
                .refine(new int[] {-1, 0, 0, 1}, 0, directory);
        Assertions.assertNotEquals(actual[1], actual[2]);
        Assertions.assertEquals(1, actual[3]);
    }

    @Test
    void improvesUnregisteredTransitWhilePreservingFixedRegistersAndOccupancy() throws IOException {
        HardwarePartitionGraph graph = fixture(new boolean[] {true, false, true, true},
                new int[][] {{1}, {2}, {}, {}}, luts(4), new long[] {0, 1, 1, 1, 1},
                Collections.emptyList());
        int[] fixed = {-1, 0, -1, 2, 1};
        int[] initial = {-1, 0, 1, 2, 1};
        int[] actual = refiner(graph, 3, fixed, 100, 0, null).refine(initial, 2, directory);
        CombinationalPathAnalyzer analyzer = new CombinationalPathAnalyzer(
                graph.ownerVertex, graph.sequential, graph.successors);
        Assertions.assertEquals(2, analyzer.analyze(initial).maxCrossings);
        Assertions.assertEquals(1, analyzer.analyze(actual).maxCrossings);
        Assertions.assertEquals(0, analyzer.analyze(actual).transitCount);
        Assertions.assertEquals(0, actual[1]);
        Assertions.assertEquals(2, actual[3]);
        Assertions.assertEquals(1, actual[4]);
        Assertions.assertArrayEquals(new int[] {-1, 0, 1, 2, 1}, initial); // Input is not modified.
    }

    @Test
    void rejectsFfCapacityViolationDespiteBalancedLutWeights() throws IOException {
        long[][] resources = luts(2);
        resources[1][1] = 2;
        HardwarePartitionGraph graph = fixture(new boolean[] {true, true}, new int[][] {{}, {}},
                resources, new long[] {0, 1, 1}, Collections.emptyList());
        Path capacities = directory.resolve("capacities.csv");
        Files.write(capacities, Arrays.asList("0,1,1,0,0,0,0", "1,1,1,0,0,0,0"));
        HardwareMappingRefiner refiner = refiner(graph, 2, unfixed(2), 100, 0, capacities);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> refiner.refine(new int[] {-1, 0, 1}, 0, directory));
    }

    @Test
    void rejectsFixedAssignmentViolation() throws IOException {
        HardwarePartitionGraph graph = fixture(new boolean[] {true, true}, new int[][] {{}, {}},
                luts(2), new long[] {0, 1, 1}, Collections.emptyList());
        HardwareMappingRefiner refiner = refiner(graph, 2, new int[] {-1, 1, -1}, 100, 0, null);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> refiner.refine(new int[] {-1, 0, 1}, 0, directory));
    }

    @Test
    void transferBudgetRejectsSnakeImprovementThatCreatesTooManyDistinctTransfers() throws IOException {
        List<HardwarePartitionGraph.Net> extraNets = new ArrayList<>();
        for (int i = 0; i < 5; i++) extraNets.add(new HardwarePartitionGraph.Net(2, new int[] {4}));
        HardwarePartitionGraph graph = fixture(new boolean[] {true, false, true, true},
                new int[][] {{1}, {2}, {}, {}}, luts(4), new long[] {0, 1, 1, 1, 1}, extraNets);
        int[] initial = {-1, 0, 1, 2, 1};
        int[] actual = refiner(graph, 3, new int[] {-1, 0, -1, 2, 1}, 1000, 0, null)
                .refine(initial, 2, directory);
        Assertions.assertArrayEquals(initial, actual);
        List<String> report = Files.readAllLines(directory.resolve("hardware_quality.txt"));
        Assertions.assertTrue(report.contains("after_transfers=2"));
    }

    @Test
    void combinationalCyclesPreventCleanQualityClaimOrUnauditedRefinement() throws IOException {
        HardwarePartitionGraph graph = fixture(new boolean[] {false, false}, new int[][] {{1}, {0}},
                luts(2), new long[] {0, 1, 1}, Collections.emptyList());
        int[] initial = {-1, 0, 1};
        int[] actual = refiner(graph, 2, unfixed(2), 100, 0, null).refine(initial, 3, directory);
        Assertions.assertArrayEquals(initial, actual);
        List<String> report = Files.readAllLines(directory.resolve("hardware_quality.txt"));
        Assertions.assertTrue(report.contains("complete=false"));
        Assertions.assertTrue(report.contains("cycle_nodes=2"));
    }

    @Test
    void validatesExternalPartitionAndFixedFilesBeforeUse() throws IOException {
        Path solution = directory.resolve("solution.part");
        Files.write(solution, Arrays.asList("0", "2"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> HardwareMappingRefiner.readSolution(solution, 2, 2));
        Files.write(solution, Arrays.asList("0"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> HardwareMappingRefiner.readSolution(solution, 2, 2));
        Files.write(solution, Arrays.asList("0", "1"));
        Assertions.assertArrayEquals(new int[] {-1, 0, 1},
                HardwareMappingRefiner.readSolution(solution, 2, 2));
        Files.write(solution, Arrays.asList("-1", "2"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> HardwareMappingRefiner.readFixed(solution, 2, 2));
    }

    @Test
    void cachedCandidateMovesMatchIndependentNetOracleIncludingDriverAsSink() throws Exception {
        int n = 9;
        int k = 4;
        Random random = new Random(482109L);
        List<HardwarePartitionGraph.Net> nets = new ArrayList<>();
        nets.add(new HardwarePartitionGraph.Net(3, new int[] {6, 3, 1, 6, 9, 2}));
        Assertions.assertArrayEquals(new int[] {1, 2, 3, 6, 9}, nets.get(0).sinks);
        for (int e = 0; e < 25; e++) {
            int[] sinks = new int[1 + random.nextInt(n)];
            for (int i = 0; i < sinks.length; i++) sinks[i] = 1 + random.nextInt(n);
            nets.add(new HardwarePartitionGraph.Net(1 + random.nextInt(n), sinks));
        }
        boolean[] sequential = new boolean[n];
        Arrays.fill(sequential, true);
        int[][] successors = new int[n][];
        for (int u = 0; u < n; u++) successors[u] = new int[0];
        long[] logic = new long[n + 1];
        Arrays.fill(logic, 1);
        HardwarePartitionGraph graph = fixture(sequential, successors, luts(n), logic, nets);
        Path topologyFile = directory.resolve("weighted.graph");
        Files.write(topologyFile, Arrays.asList("4 3 1", "2 1", "1 1 3 2", "2 2 4 5", "3 5"));
        HardwareMappingRefiner refiner = new HardwareMappingRefiner(graph,
                PartitionTopology.read(topologyFile, k), k, 0.0, unfixed(n), 0, 0, null);
        Method initialize = HardwareMappingRefiner.class.getDeclaredMethod("initializeNetCache", int[].class);
        Method place = HardwareMappingRefiner.class.getDeclaredMethod("setPlacement", int.class, int.class, int[].class);
        initialize.setAccessible(true);
        place.setAccessible(true);
        Field costField = HardwareMappingRefiner.class.getDeclaredField("cachedNetCosts");
        Field transferField = HardwareMappingRefiner.class.getDeclaredField("cachedTransfers");
        Field countField = HardwareMappingRefiner.class.getDeclaredField("sinkCounts");
        costField.setAccessible(true);
        transferField.setAccessible(true);
        countField.setAccessible(true);
        double[][] distance = {{0, 1, 3, 8}, {1, 0, 2, 7}, {3, 2, 0, 5}, {8, 7, 5, 0}};
        for (int trial = 0; trial < 40; trial++) {
            int[] assignment = new int[n + 1];
            assignment[0] = -1;
            for (int v = 1; v <= n; v++) assignment[v] = random.nextInt(k);
            int[] initial = assignment.clone();
            initialize.invoke(refiner, (Object) assignment);
            for (int v = 1; v <= n; v++) {
                int source = assignment[v];
                // Consecutive candidate moves deliberately do not restore each
                // candidate: the production loop restores once after all k.
                for (int dest = 0; dest < k; dest++) {
                    place.invoke(refiner, v, dest, assignment);
                    assertNetCache(graph.nets, assignment, distance,
                            (double[]) costField.get(refiner), (int[]) transferField.get(refiner),
                            (int[][]) countField.get(refiner));
                }
                place.invoke(refiner, v, source, assignment);
                Assertions.assertArrayEquals(initial, assignment);
                assertNetCache(graph.nets, assignment, distance,
                        (double[]) costField.get(refiner), (int[]) transferField.get(refiner),
                        (int[][]) countField.get(refiner));
            }
        }
    }

    private static void assertNetCache(List<HardwarePartitionGraph.Net> nets, int[] assignment,
            double[][] distance, double[] actualCosts, int[] actualTransfers, int[][] actualCounts) {
        for (int e = 0; e < nets.size(); e++) {
            HardwarePartitionGraph.Net net = nets.get(e);
            int[] counts = new int[distance.length];
            for (int sink : net.sinks) counts[assignment[sink]]++;
            int transfers = 0;
            double cost = 0;
            int driver = assignment[net.driver];
            for (int p = 0; p < counts.length; p++) {
                if (p != driver && counts[p] > 0) {
                    transfers++;
                    cost += 1 + distance[driver][p];
                }
            }
            Assertions.assertArrayEquals(counts, actualCounts[e], "net " + e);
            Assertions.assertEquals(transfers, actualTransfers[e], "net " + e);
            Assertions.assertEquals(cost, actualCosts[e], 1e-9, "net " + e);
        }
    }

    @Test
    void ownerRunCompressionPreservesAllMappingCostsIncludingLaterReentry() throws Exception {
        int[] primitiveOwners = {2, 2, 3, 3, 2, 4, 4};
        int[] primitivePath = {0, 1, 2, 3, 4, 5, 6};
        int[] compressed = HardwareMappingRefiner.compressPathOwners(primitivePath, primitiveOwners);
        Assertions.assertArrayEquals(new int[] {2, 3, 2, 4}, compressed);
        Assertions.assertArrayEquals(new int[0],
                HardwareMappingRefiner.compressPathOwners(new int[0], primitiveOwners));
        HardwarePartitionGraph graph = fixture(new boolean[] {true, true, true, true},
                new int[][] {{}, {}, {}, {}}, luts(4), new long[] {0, 1, 1, 1, 1}, Collections.emptyList());
        HardwareMappingRefiner refiner = refiner(graph, 3, unfixed(4), 3.5, 0, null);
        Method cost = HardwareMappingRefiner.class.getDeclaredMethod("pathCost", int[].class, int[].class);
        cost.setAccessible(true);
        // Exhaust all 27 assignments of the three owners. The oracle counts
        // primitive transitions and derives reentries from run count minus
        // distinct partitions, independently of the compressed implementation.
        for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) for (int c = 0; c < 3; c++) {
            int[] assignment = {-1, 0, a, b, c};
            int transitions = 0;
            Set<Integer> partitions = new HashSet<>();
            for (int i = 0; i < primitivePath.length; i++) {
                int part = assignment[primitiveOwners[primitivePath[i]]];
                partitions.add(part);
                if (i > 0 && part != assignment[primitiveOwners[primitivePath[i - 1]]]) transitions++;
            }
            int reentries = transitions + 1 - partitions.size();
            int excess = Math.max(0, transitions - 1);
            double expected = 3.5 * (excess * (double) excess + reentries);
            Assertions.assertEquals(expected, (double) cost.invoke(refiner, compressed, assignment), 1e-9);
        }
    }

    @Test
    void candidateSelectionCannotSpendUnboundedTopologyCostToRemoveSnake() throws IOException {
        HardwarePartitionGraph graph = fixture(new boolean[] {true, false, true, true, true},
                new int[][] {{1}, {2}, {}, {}, {3}}, luts(5), new long[] {0, 1, 1, 1, 1, 1},
                Collections.emptyList());
        int[] cheapTopology = {-1, 0, 1, 0, 2, 2};
        int[] fewerSnakes = {-1, 0, 0, 2, 1, 2};
        Path file = directory.resolve("candidate_topology.graph");
        Files.write(file, Arrays.asList("3 2 1", "2 1", "1 1 3 100", "2 100"));
        HardwareMappingRefiner weighted = new HardwareMappingRefiner(graph,
                PartitionTopology.read(file, 3), 3, 0.0, unfixed(5), 100, 0.03, null);
        Assertions.assertEquals(2, weighted.transfers(cheapTopology));
        Assertions.assertEquals(2, weighted.transfers(fewerSnakes));
        Assertions.assertEquals(2.0, weighted.topologyCost(cheapTopology));
        Assertions.assertEquals(201.0, weighted.topologyCost(fewerSnakes));
        Assertions.assertSame(cheapTopology, weighted.selectCandidate(Arrays.asList(cheapTopology, fewerSnakes)));
        // When topology cost is equal, the improved directed path quality wins.
        HardwareMappingRefiner uniform = refiner(graph, 3, unfixed(5), 100, 0.03, null);
        Assertions.assertSame(fewerSnakes, uniform.selectCandidate(Arrays.asList(cheapTopology, fewerSnakes)));
    }

    @Test
    void tinyIntegerBalanceUsesNativeFloorAndRepairsOverflow() throws IOException {
        HardwarePartitionGraph graph = fixture(new boolean[] {true, true, true, true},
                new int[][] {{}, {}, {}, {}}, luts(4), new long[] {0, 1, 1, 1, 1}, Collections.emptyList());
        HardwareMappingRefiner refiner = new HardwareMappingRefiner(graph, PartitionTopology.complete(2),
                2, 0.03, unfixed(4), 0, 0, null);
        int[] initial = {-1, 0, 0, 0, 1};
        Assertions.assertThrows(IllegalArgumentException.class, () -> refiner.validate(initial));
        int[] actual = refiner.refine(initial, 0, directory);
        Assertions.assertEquals(2, Arrays.stream(actual).filter(p -> p == 0).count());
        refiner.validate(actual);
    }

    @Test
    void optionalEstimatedBalanceRepairsAndCandidateSelectionExcludesOverflow() throws IOException {
        HardwarePartitionGraph graph = fixture(new boolean[] {true, true, true, true},
                new int[][] {{}, {}, {}, {}}, luts(4), new long[] {0, 1, 1, 1, 1}, Collections.emptyList());
        HardwareMappingRefiner refiner = new HardwareMappingRefiner(graph, PartitionTopology.complete(2),
                2, 1.0, unfixed(4), 0, 0, null);
        int[] weights = {0, 3, 1, 1, 1};
        refiner.configureBalanceWeights(weights, 0.03); // floor(3 * 1.03) = 3, not 4.
        weights[1] = 0; // Caller mutation must not alter the configured model.
        int[] infeasible = {-1, 0, 0, 1, 1};
        int[] feasible = {-1, 0, 1, 1, 1};
        Assertions.assertThrows(IllegalArgumentException.class, () -> refiner.validate(infeasible));
        Assertions.assertSame(feasible, refiner.selectCandidate(Arrays.asList(infeasible, feasible)));
        int[] actual = refiner.refine(infeasible, 0, directory);
        refiner.validate(actual);
        Assertions.assertNotEquals(actual[1], actual[2]);
        Assertions.assertTrue(Files.readAllLines(directory.resolve("hardware_quality.txt"))
                .contains("configured_scalar_loads=[3, 3]"));
        refiner.configureBalanceWeights(null, 0);
        refiner.validate(infeasible); // Legacy logical-only behavior is preserved.
    }

    @Test
    void temporaryPlacementDoesNotAlterCommittedWeightsAndValidationRecomputesLoads() throws Exception {
        HardwarePartitionGraph graph = fixture(new boolean[] {true, true, true, true},
                new int[][] {{}, {}, {}, {}}, luts(4), new long[] {0, 1, 1, 1, 1}, Collections.emptyList());
        HardwareMappingRefiner refiner = new HardwareMappingRefiner(graph, PartitionTopology.complete(2),
                2, 1.0, unfixed(4), 0, 0, null);
        refiner.configureBalanceWeights(new int[] {0, 3, 1, 1, 1}, 0);
        int[] actual = refiner.refine(new int[] {-1, 0, 1, 1, 1}, 0, directory);
        Field committed = HardwareMappingRefiner.class.getDeclaredField("committedBalanceLoad");
        committed.setAccessible(true);
        Method place = HardwareMappingRefiner.class.getDeclaredMethod("setPlacement", int.class, int.class, int[].class);
        place.setAccessible(true);
        place.invoke(refiner, 2, 0, actual);
        Assertions.assertArrayEquals(new long[] {3, 3}, (long[]) committed.get(refiner));
        Assertions.assertThrows(IllegalArgumentException.class, () -> refiner.validate(actual));
        place.invoke(refiner, 2, 1, actual);
        refiner.validate(actual);
    }

    @Test
    void optionalBalancePreventsPathImprovementThatWouldOverfillEstimate() throws IOException {
        HardwarePartitionGraph graph = fixture(new boolean[] {true, false, true, true},
                new int[][] {{1}, {2}, {}, {}}, luts(4), new long[] {0, 1, 1, 1, 1}, Collections.emptyList());
        HardwareMappingRefiner refiner = refiner(graph, 3, new int[] {-1, 0, -1, 2, 1}, 100, 0, null);
        refiner.configureBalanceWeights(new int[] {0, 2, 2, 2, 1}, 0);
        int[] initial = {-1, 0, 1, 2, 1};
        Assertions.assertArrayEquals(initial, refiner.refine(initial, 3, directory));
        for (int[] invalid : new int[][] {{0, 1}, {1, 1, 1, 1, 1}, {0, 1, -1, 1, 1}}) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> refiner.configureBalanceWeights(invalid, 0));
        }
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> refiner.configureBalanceWeights(new int[] {0, 1, 1, 1, 1}, Double.NaN));
    }

    @Test
    void heterogeneousEstimatedCapsRepairTowardTheCorrectLargerFpga() throws IOException {
        HardwarePartitionGraph graph = fixture(new boolean[] {true, true, true, true},
                new int[][] {{}, {}, {}, {}}, luts(4), new long[] {0, 1, 1, 1, 1}, Collections.emptyList());
        HardwareMappingRefiner refiner = new HardwareMappingRefiner(graph, PartitionTopology.complete(2),
                2, 1.0, new int[] {-1, 0, -1, -1, -1}, 0, 0, null);
        long[] capacities = {2, 3};
        refiner.configureBalanceWeights(new int[] {0, 2, 1, 1, 1}, capacities);
        capacities[0] = 99; // Caller cannot change the configured hardware limits.
        int[] initial = {-1, 0, 0, 1, 1};
        int[] feasible = {-1, 0, 1, 1, 1};
        Assertions.assertThrows(IllegalArgumentException.class, () -> refiner.validate(initial));
        Assertions.assertSame(feasible, refiner.selectCandidate(Arrays.asList(initial, feasible)));
        int[] actual = refiner.refine(initial, 3, directory);
        Assertions.assertArrayEquals(feasible, actual);
        refiner.validate(actual);
        List<String> report = Files.readAllLines(directory.resolve("hardware_quality.txt"));
        Assertions.assertTrue(report.contains("configured_scalar_capacities=[2, 3]"));
        Assertions.assertTrue(report.contains("configured_scalar_loads=[2, 3]"));
    }

    @Test
    void zeroScalarCapacityCanContainUsefulUnweightedLogicAndImpossibleCapsFail() throws IOException {
        long[][] resources = luts(3); resources[1][0] = 0; resources[1][1] = 1;
        HardwarePartitionGraph graph = fixture(new boolean[] {true, true, true},
                new int[][] {{}, {}, {}}, resources, new long[] {0, 1, 1, 1}, Collections.emptyList());
        HardwareMappingRefiner refiner = new HardwareMappingRefiner(graph, PartitionTopology.complete(2),
                2, 1.0, new int[] {-1, 0, -1, -1}, 0, 0, null);
        refiner.configureBalanceWeights(new int[] {0, 0, 2, 1}, new long[] {0, 3});
        int[] assignment = {-1, 0, 1, 1};
        refiner.validate(assignment); // Scalar zero is not equivalent to empty useful logic.
        Assertions.assertArrayEquals(assignment, refiner.refine(assignment, 1, directory));
        refiner.configureBalanceWeights(new int[] {0, 0, 2, 1}, new long[] {0, 2});
        Assertions.assertThrows(IllegalArgumentException.class, () -> refiner.refine(assignment, 1, directory));
        for (long[] invalid : new long[][] {{2}, {2, -1}, {2, (long) Integer.MAX_VALUE + 1}}) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> refiner.configureBalanceWeights(new int[] {0, 0, 2, 1}, invalid));
        }
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> refiner.configureBalanceWeights(null, new long[] {1, 1}));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> refiner.configureBalanceWeights(new int[] {0, 0, 2, 1}, (long[]) null));
    }

    @Test
    void separateLogicalRelaxationDoesNotRelaxNativePrimaryScalarBalance() throws IOException {
        HardwarePartitionGraph graph = fixture(new boolean[] {true, false, true, true},
                new int[][] {{1}, {2}, {}, {}}, luts(4), new long[] {0, 1, 1, 1, 1}, Collections.emptyList());
        int[] initial = {-1, 0, 1, 0, 1};
        // The relaxed logical bound permits three LUTs on one FPGA. The
        // original native primary bound floor(1.03*ceil(4/2)) remains two.
        HardwareMappingRefiner refiner = new HardwareMappingRefiner(graph, PartitionTopology.complete(2),
                2, 0.5, new int[] {-1, 0, -1, 0, 1}, 100, 0, null);
        int[] nativePrimary = Partitioner.primaryBalanceWeights(graph, null);
        Assertions.assertArrayEquals(new int[] {0, 1, 1, 1, 1}, nativePrimary);
        refiner.configureBalanceWeights(nativePrimary, 0.03);
        int[] overPrimary = {-1, 0, 0, 0, 1}; // Removes both cuts but would create a 3/1 split.
        Assertions.assertThrows(IllegalArgumentException.class, () -> refiner.validate(overPrimary));
        Assertions.assertSame(initial, refiner.selectCandidate(Arrays.asList(overPrimary, initial)));
        Assertions.assertArrayEquals(initial, refiner.refine(initial, 3, directory));
        Assertions.assertTrue(Files.readAllLines(directory.resolve("hardware_quality.txt"))
                .contains("configured_scalar_capacities=[2, 2]"));
        int[] custom = {0, 2, 3, 4, 5};
        Assertions.assertArrayEquals(custom, Partitioner.primaryBalanceWeights(graph, custom));
    }

    @Test
    void seedSelectionAndCleanupShareOneGrowthBudgetInsteadOfCompounding() throws IOException {
        // Two combinational vertices lie between fixed launch/capture owners.
        // Additional independent signals make the best-cut seed cost10 and a
        // better-path seed cost11. Removing its remaining snake costs12, which
        // fits10% above11 after integer rounding but violates10% above10.
        List<HardwarePartitionGraph.Net> extra = new ArrayList<>();
        for (int i = 0; i < 3; i++) extra.add(new HardwarePartitionGraph.Net(2, new int[] {5}));
        for (int i = 0; i < 5; i++) extra.add(new HardwarePartitionGraph.Net(3, new int[] {5}));
        for (int i = 0; i < 4; i++) extra.add(new HardwarePartitionGraph.Net(1, new int[] {5}));
        HardwarePartitionGraph graph = fixture(new boolean[] {true, false, false, true, true, true},
                new int[][] {{2}, {3}, {1}, {}, {}, {}}, luts(6), new long[] {0, 1, 1, 1, 1, 1, 1}, extra);
        int[] fixed = {-1, 0, -1, -1, 0, 1, 2};
        int[] bestCuts = {-1, 0, 2, 1, 0, 1, 2};
        int[] betterPath = {-1, 0, 1, 0, 0, 1, 2};
        HardwareMappingRefiner global = new HardwareMappingRefiner(graph, PartitionTopology.complete(3),
                3, 1.0, fixed, 1000, 0.10, null);
        Assertions.assertEquals(10, global.transfers(bestCuts));
        Assertions.assertEquals(11, global.transfers(betterPath));
        Assertions.assertSame(betterPath, global.selectCandidate(Arrays.asList(bestCuts, betterPath)));
        int[] guarded = global.refine(betterPath, 3, directory);
        Assertions.assertTrue(global.transfers(guarded) <= 11);
        List<String> report = Files.readAllLines(directory.resolve("hardware_quality.txt"));
        Assertions.assertTrue(report.contains("transfer_growth_reference=10"));
        Assertions.assertTrue(report.contains("topology_growth_reference=10.0"));

        Path directOutput = directory.resolve("direct"); Files.createDirectories(directOutput);
        HardwareMappingRefiner direct = new HardwareMappingRefiner(graph, PartitionTopology.complete(3),
                3, 1.0, fixed, 1000, 0.10, null);
        int[] compoundedCounterexample = direct.refine(betterPath, 3, directOutput);
        Assertions.assertEquals(12, direct.transfers(compoundedCounterexample));
        Assertions.assertEquals(0, new CombinationalPathAnalyzer(graph.ownerVertex, graph.sequential,
                graph.successors).analyze(compoundedCounterexample).maxCrossings);
    }

    private TimingPathModel timingPaths(String[] frontier, String... rows) throws Exception {
        Path file = directory.resolve("native_timing.tsv");
        List<String> lines = new ArrayList<>(Arrays.asList("#input_sha256=" + TestTimingPathModel.SHA,
                TestTimingPathModel.HEADER));
        lines.addAll(Arrays.asList(rows)); Files.write(file, lines);
        return TimingPathModel.read(file, TestTimingPathModel.SHA, frontier, "top");
    }

    @Test
    void sampledCriticalPathBreaksEqualCutSeedTiesAndGuidesCleanupWithoutInventedDelay() throws Exception {
        HardwarePartitionGraph graph = fixture(new boolean[] {true, true, true, true},
                new int[][] {{1}, {}, {3}, {}}, luts(4), new long[] {0, 1, 1, 1, 1}, Collections.emptyList());
        HardwareMappingRefiner refiner = new HardwareMappingRefiner(graph, PartitionTopology.complete(2),
                2, 1.0, unfixed(4), 0, 0, null);
        int[] criticalCut = {-1, 0, 1, 0, 0}, uncriticalCut = {-1, 0, 0, 0, 1};
        Assertions.assertSame(criticalCut, refiner.selectCandidate(Arrays.asList(criticalCut, uncriticalCut)));
        TimingPathModel timing = timingPaths(new String[] {null, "a", "b", "c", "d"},
                "critical\t0\t10\tclock\tclock\t[\"a\",\"b\"]",
                "other\t9\t10\tclock\tclock\t[\"c\",\"d\"]");
        refiner.configureTimingPaths(timing, 8, null);
        Assertions.assertSame(uncriticalCut, refiner.selectCandidate(Arrays.asList(criticalCut, uncriticalCut)));
        int[] before = {-1, 0, 1, 0, 1};
        // Reset native seed growth references before an independent cleanup.
        refiner.selectCandidate(Collections.singletonList(before));
        int[] actual = refiner.refine(before, 2, directory);
        Assertions.assertTrue(refiner.timingEvaluation(actual).timingCost < refiner.timingEvaluation(before).timingCost);
        List<String> report = Files.readAllLines(directory.resolve("hardware_quality.txt"));
        Assertions.assertTrue(report.contains("timing_paths_count=2"));
        Assertions.assertTrue(report.stream().noneMatch(line -> line.startsWith("modeled_worst_slack")));
    }

    @Test
    void sourceOnlyTimingCannotPreferFourRegisterFreeCrossingsOverThree() throws Exception {
        // The native sample contains the direct launch-to-capture path but
        // misses the longer reconvergent chain. Its unchanged loose source
        // clock does not establish the transport cost of four FPGA changes.
        HardwarePartitionGraph graph = fixture(new boolean[] {true, false, false, false, true},
                new int[][] {{1, 4}, {2}, {3}, {4}, {}}, luts(5),
                new long[] {0, 1, 1, 1, 1, 1}, Collections.emptyList());
        int[] fourCrossings = {-1, 0, 1, 0, 1, 0};
        int[] threeCrossings = {-1, 0, 1, 0, 0, 1};
        CombinationalPathAnalyzer analyzer = new CombinationalPathAnalyzer(
                graph.ownerVertex, graph.sequential, graph.successors);
        Assertions.assertEquals(4, analyzer.analyze(fourCrossings).maxCrossings);
        Assertions.assertEquals(3, analyzer.analyze(threeCrossings).maxCrossings);
        TimingPathModel timing = timingPaths(new String[] {null, "a", "b", "c", "d", "e"},
                "sampled_direct\t95.7\t100\tplatform_clk\tplatform_clk\t[\"a\",\"e\"]");
        HardwareMappingRefiner refiner = new HardwareMappingRefiner(graph, PartitionTopology.complete(2),
                2, 1.0, unfixed(5), 1000, 0.10, null);
        refiner.configureTimingPaths(timing, 8, null);
        Assertions.assertEquals(0, refiner.timingEvaluation(fourCrossings).timingCost);
        Assertions.assertTrue(refiner.timingEvaluation(threeCrossings).timingCost > 0);
        Assertions.assertSame(threeCrossings,
                refiner.selectCandidate(Arrays.asList(fourCrossings, threeCrossings)));
        Assertions.assertSame(threeCrossings,
                refiner.selectCandidate(Arrays.asList(threeCrossings, fourCrossings)));

        // With an explicit delay, the sample has a defined modeled timing
        // comparison. Its better modeled slack may take priority within the
        // existing capacity and cut/topology bounds.
        refiner.configureTimingPaths(timing, 8, 3.0);
        Assertions.assertEquals(95.7, refiner.timingEvaluation(fourCrossings).modeledWorstSlackNs);
        Assertions.assertEquals(92.7, refiner.timingEvaluation(threeCrossings).modeledWorstSlackNs);
        Assertions.assertSame(fourCrossings,
                refiner.selectCandidate(Arrays.asList(threeCrossings, fourCrossings)));
        Assertions.assertSame(fourCrossings,
                refiner.selectCandidate(Arrays.asList(fourCrossings, threeCrossings)));
    }

    @Test
    void structuralImprovementCannotRegressSampledTimingCostEvenWithTinyGuideWeight() throws Exception {
        HardwarePartitionGraph graph = fixture(new boolean[] {true, false, false, true, true, true},
                new int[][] {{1}, {2, 5}, {3}, {}, {1}, {}}, luts(6), new long[] {0, 1, 1, 1, 1, 1, 1}, Collections.emptyList());
        int[] fixed = {-1, 0, -1, 2, 0, 1, 1};
        int[] initial = {-1, 0, 1, 2, 0, 1, 1};
        Path capacities = directory.resolve("timing_capacity.csv");
        Files.write(capacities, Arrays.asList("0,2,0,0,0,0,0", "1,4,0,0,0,0,0", "2,4,0,0,0,0,0"));
        HardwareMappingRefiner structural = new HardwareMappingRefiner(graph, PartitionTopology.complete(3),
                3, 1.0, fixed, 1000, 1.0, capacities);
        int[] lowerSnake = structural.refine(initial, 1, directory);
        CombinationalPathAnalyzer analyzer = new CombinationalPathAnalyzer(graph.ownerVertex, graph.sequential, graph.successors);
        Assertions.assertEquals(3, analyzer.analyze(initial).maxCrossings);
        Assertions.assertEquals(2, analyzer.analyze(lowerSnake).maxCrossings);
        TimingPathModel timing = timingPaths(new String[] {null, "a", "b", "c", "d", "e", "f"},
                "critical\t0\t10\tclock\tclock\t[\"e\",\"b\",\"f\"]");
        HardwareMappingRefiner guided = new HardwareMappingRefiner(graph, PartitionTopology.complete(3),
                3, 1.0, fixed, 1000, 1.0, capacities);
        guided.configureQualityGuard("componentwise"); // This fixture proves the conservative optional policy.
        guided.configureTimingPaths(timing, 0.001, null);
        int[] actual = guided.refine(initial, 1, directory);
        Assertions.assertArrayEquals(initial, actual);
        Assertions.assertEquals(0, timing.evaluate(initial, null).timingCost);
        Assertions.assertTrue(timing.evaluate(lowerSnake, null).timingCost > 0);
        TimingPathModel newlyCritical = timingPaths(new String[] {null, "a", "b", "c", "d", "e", "f"},
                "source_noncritical\t12\t10\tclock\tclock\t[\"e\",\"b\",\"f\"]");
        guided.configureTimingPaths(newlyCritical, 0.001, 3.0);
        Assertions.assertEquals(0, newlyCritical.evaluate(initial, null).timingCost);
        Assertions.assertEquals(0, newlyCritical.evaluate(lowerSnake, null).timingCost);
        Assertions.assertTrue(newlyCritical.evaluate(lowerSnake, 3.0).timingCost > 0);
        Assertions.assertArrayEquals(initial, guided.refine(initial, 1, directory));
        List<String> modeledReport = Files.readAllLines(directory.resolve("hardware_quality.txt"));
        Assertions.assertTrue(modeledReport.contains("modeled_worst_slack_before_ns=12.0"));
        Assertions.assertTrue(modeledReport.contains("modeled_worst_slack_after_ns=12.0"));
    }
}
