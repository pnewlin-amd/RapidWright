package com.xilinx.rapidwright.edif.partition;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent small-graph proofs. No physical/native utilization assertion. */
public class TestBoundedBalancedSwaps {
    @TempDir Path directory;

    private static long[][] resources(int n) {
        long[][] resources = new long[n + 1][6];
        for (int v = 1; v <= n; v++) resources[v][0] = 1;
        return resources;
    }
    private static int[] unfixed(int n) {
        int[] fixed = new int[n + 1]; Arrays.fill(fixed, -1); return fixed;
    }
    private static HardwarePartitionGraph chain(long[][] resources, long[] logic,
            List<HardwarePartitionGraph.Net> extra) {
        int n = resources.length - 1;
        int[] owners = new int[n]; String[] names = new String[n];
        boolean[] sequential = new boolean[n]; sequential[0] = sequential[n-1] = true;
        int[][] successors = new int[n][]; List<HardwarePartitionGraph.Net> nets = new ArrayList<>();
        for (int u = 0; u < n; u++) {
            owners[u] = u + 1; names[u] = "p" + (u + 1);
            successors[u] = u + 1 < n ? new int[] {u + 1} : new int[0];
            if (u + 1 < n) nets.add(new HardwarePartitionGraph.Net(u + 1, new int[] {u + 2}));
        }
        nets.addAll(extra);
        return new HardwarePartitionGraph(owners, sequential, successors, names, nets,
                Collections.emptyMap(), resources, logic, 0, 0);
    }
    private HardwareMappingRefiner refiner(HardwarePartitionGraph graph, int[] fixed,
            double snake, Path capacities) throws Exception {
        return new HardwareMappingRefiner(graph, PartitionTopology.complete(2), 2, 0.0,
                fixed, snake, 0.0, capacities);
    }
    private static Method method(String name, Class<?>... arguments) throws Exception {
        Method method = HardwareMappingRefiner.class.getDeclaredMethod(name, arguments);
        method.setAccessible(true); return method;
    }
    private static Field field(String name) throws Exception {
        Field field = HardwareMappingRefiner.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static boolean fitsSwap(HardwareMappingRefiner refiner, int a, int b, int[] assignment) throws Exception {
        Object loads = method("loads", int[].class).invoke(refiner, (Object) assignment);
        Object logic = method("logicLoads", int[].class).invoke(refiner, (Object) assignment);
        field("committedBalanceLoad").set(refiner, method("balanceLoads", int[].class).invoke(refiner, (Object) assignment));
        return (boolean) method("swapFits", int.class, int.class, int[].class, long[][].class, long[].class)
                .invoke(refiner, a, b, assignment, loads, logic);
    }
    private static int[] baseline() { return new int[] {-1, 0, 1, 0, 1}; }
    private static int[] fixedEndpoints() { return new int[] {-1, 0, -1, -1, 1}; }
    private static HardwarePartitionGraph fourChain() {
        return chain(resources(4), new long[] {0, 1, 1, 1, 1}, Collections.emptyList());
    }
    private long value(Path output, String key) throws Exception {
        return Long.parseLong(Files.readAllLines(output.resolve("hardware_quality.txt")).stream()
                .filter(line -> line.startsWith(key + "=")).findFirst().orElseThrow().split("=", 2)[1]);
    }

    @Test void tightlyFullFourNodeCounterexampleEscapesThreeCrossingsWithOneSwap() throws Exception {
        HardwarePartitionGraph graph = fourChain();
        int[] initial = baseline();
        // Independently certify the single-move obstruction: both partitions
        // are full, and the only movable vertices each carry one logical LUT.
        long[] loads = new long[2];
        for (int v = 1; v < initial.length; v++) loads[initial[v]] += graph.resources[v][0];
        Assertions.assertArrayEquals(new long[] {2, 2}, loads);
        for (int vertex : new int[] {2, 3}) {
            Assertions.assertTrue(loads[1 - initial[vertex]] + graph.resources[vertex][0] > 2);
        }
        HardwareMappingRefiner paired = refiner(graph, fixedEndpoints(), 8, null);
        paired.configureBalanceWeights(new int[] {0, 1, 1, 1, 1}, new long[] {2, 2});
        Path output = Files.createDirectory(directory.resolve("paired"));
        int[] actual = paired.refine(initial, 2, output);
        Assertions.assertArrayEquals(new int[] {-1, 0, 0, 1, 1}, actual);
        Assertions.assertArrayEquals(baseline(), initial);
        Assertions.assertEquals(3, paired.transfers(initial));
        Assertions.assertEquals(1, paired.transfers(actual));
        Assertions.assertEquals(3, new CombinationalPathAnalyzer(graph.ownerVertex, graph.sequential,
                graph.successors).analyze(initial).maxCrossings);
        Assertions.assertEquals(1, value(output, "after_max_register_free_crossings"));
        Assertions.assertEquals(0, value(output, "after_transit_primitives"));
        Assertions.assertEquals(1, value(output, "proposed_swaps"));
        paired.validate(actual);
    }

    @Test void rejectsEachOfSixFinalResourceOverflowDimensions() throws Exception {
        for (int resource = 0; resource < 6; resource++) {
            long[][] resources = resources(4); resources[2][resource]++;
            HardwarePartitionGraph graph = chain(resources, new long[] {0,1,1,1,1}, Collections.emptyList());
            Path output = Files.createDirectory(directory.resolve("resource" + resource));
            Path caps = output.resolve("caps.txt");
            StringBuilder a = new StringBuilder("0"), b = new StringBuilder("1");
            for (int r = 0; r < 6; r++) {
                a.append(" ").append(resources[1][r] + resources[3][r]);
                b.append(" ").append(resources[2][r] + resources[4][r]);
            }
            Files.write(caps, Arrays.asList(a.toString(), b.toString()));
            HardwareMappingRefiner paired = refiner(graph, fixedEndpoints(), 8, caps);
            Assertions.assertFalse(fitsSwap(paired, 2, 3, baseline()), "resource " + resource);
            Assertions.assertArrayEquals(baseline(), paired.refine(baseline(), 1, output));
            Assertions.assertEquals(0, value(output, "proposed_swaps"));
        }
    }

    @Test void scalarProxyFixedAssignmentsAndUsefulOccupancyRemainHard() throws Exception {
        HardwareMappingRefiner scalar = refiner(fourChain(), fixedEndpoints(), 8, null);
        scalar.configureBalanceWeights(new int[] {0,1,2,1,1}, new long[] {2,3});
        Assertions.assertFalse(fitsSwap(scalar, 2, 3, baseline()));
        Path output = Files.createDirectory(directory.resolve("scalar"));
        Assertions.assertArrayEquals(baseline(), scalar.refine(baseline(), 1, output));
        int[] fixed = fixedEndpoints(); fixed[2] = 1;
        Assertions.assertFalse(fitsSwap(refiner(fourChain(), fixed, 8, null), 2, 3, baseline()));
        HardwarePartitionGraph virtual = chain(resources(4), new long[] {0,1,0,0,1}, Collections.emptyList());
        Assertions.assertFalse(fitsSwap(refiner(virtual, unfixed(4), 8, null), 1, 2, baseline()));
    }

    @Test void originalTransferBudgetBlocksProfitableSnakeSwap() throws Exception {
        List<HardwarePartitionGraph.Net> extra = new ArrayList<>();
        for (int i = 0; i < 5; i++) extra.add(new HardwarePartitionGraph.Net(2, new int[] {4}));
        HardwarePartitionGraph graph = chain(resources(4), new long[] {0,1,1,1,1}, extra);
        HardwareMappingRefiner paired = refiner(graph, fixedEndpoints(), 1000, null);
        Assertions.assertTrue(fitsSwap(paired, 2, 3, baseline()));
        Path output = Files.createDirectory(directory.resolve("cutguard"));
        Assertions.assertArrayEquals(baseline(), paired.refine(baseline(), 2, output));
        Assertions.assertEquals(0, value(output, "proposed_swaps"));
        Assertions.assertEquals(3, value(output, "after_transfers"));
    }

    private TimingPathModel timing(String[] frontier, String... rows) throws Exception {
        Path file = directory.resolve("timing_" + Files.list(directory).count() + ".tsv");
        List<String> lines = new ArrayList<>(Arrays.asList("#input_sha256=" + "a".repeat(64),
                "path_id\tslack_ns\trequirement_ns\tlaunch_clock\tcapture_clock\tcells_json"));
        lines.addAll(Arrays.asList(rows)); Files.write(file, lines);
        return TimingPathModel.read(file, "a".repeat(64), frontier, "top");
    }
    @Test void fullSampledTimingGuardRejectsLocalStructuralImprovement() throws Exception {
        HardwareMappingRefiner paired = refiner(fourChain(), fixedEndpoints(), 8, null);
        TimingPathModel timing = timing(new String[] {null,"p1","p2","p3","p4"},
                "critical\t1\t10\tL\tC\t[\"p2\",\"p4\"]");
        paired.configureTimingPaths(timing, 0, 0.5);
        Path output = Files.createDirectory(directory.resolve("timingguard"));
        Assertions.assertArrayEquals(baseline(), paired.refine(baseline(), 1, output));
        Assertions.assertEquals(1, value(output, "proposed_swaps"));
        Assertions.assertEquals(3, value(output, "after_max_register_free_crossings"));
        Assertions.assertTrue(Files.readAllLines(output.resolve("hardware_quality.txt")).contains("refinement_accepted=false"));
    }

    private static double independentCost(HardwarePartitionGraph graph, int[][] paths, int[] assignment,
            TimingPathModel timing, double snake, double timingWeight, Double delay) {
        double result = 0;
        for (HardwarePartitionGraph.Net net : graph.nets) {
            Set<Integer> targets = new HashSet<>();
            for (int sink : net.sinks) if (assignment[sink] != assignment[net.driver]) targets.add(assignment[sink]);
            result += 2 * targets.size(); // Complete uniform topology costs one hop.
        }
        for (int[] path : paths) {
            Set<Integer> visited = new HashSet<>(); int last = -1, changes = 0, reentries = 0;
            for (int v : path) {
                int p = assignment[v];
                if (p != last) { if (last >= 0) changes++; if (!visited.add(p)) reentries++; last = p; }
            }
            int excess = Math.max(0, changes - 1); result += snake * (excess * excess + reentries);
        }
        return result + timingWeight * timing.evaluate(assignment, delay).timingCost;
    }
    @Test void deduplicatedUnionDeltaMatchesIndependentWholeObjectiveAndRestoresCaches() throws Exception {
        int n = 9; Random random = new Random(291172L);
        List<HardwarePartitionGraph.Net> extra = new ArrayList<>();
        extra.add(new HardwarePartitionGraph.Net(2, new int[] {2,3,3,7,9}));
        for (int e = 0; e < 25; e++) {
            int[] sinks = new int[1 + random.nextInt(n)];
            for (int i = 0; i < sinks.length; i++) sinks[i] = 1 + random.nextInt(n);
            extra.add(new HardwarePartitionGraph.Net(1 + random.nextInt(n), sinks));
        }
        long[] logic = new long[n + 1]; Arrays.fill(logic, 1);
        HardwarePartitionGraph graph = chain(resources(n), logic, extra);
        String[] frontier = new String[n+1]; for (int v = 1; v <= n; v++) frontier[v] = "p" + v;
        TimingPathModel timing = timing(frontier,
                "shared\t1\t10\tL\tC\t[\"p1\",\"p4\",\"p2\",\"p4\",\"p7\",\"p9\"]",
                "other\t3\t10\tL\tC\t[\"p8\",\"p4\",\"p2\",\"p6\"]");
        HardwareMappingRefiner paired = new HardwareMappingRefiner(graph, PartitionTopology.complete(3),
                3, 0, unfixed(n), 2.5, 0, null);
        paired.configureTimingPaths(timing, 3.25, 0.7);
        CombinationalPathAnalyzer analyzer = new CombinationalPathAnalyzer(graph.ownerVertex, graph.sequential, graph.successors);
        for (int trial = 0; trial < 30; trial++) {
            int[] assignment = new int[n+1]; assignment[0] = -1;
            for (int v = 1; v <= n; v++) assignment[v] = random.nextInt(3);
            method("initializeNetCache", int[].class).invoke(paired, (Object) assignment);
            method("refreshPaths", CombinationalPathAnalyzer.class, int[].class).invoke(paired, analyzer, assignment);
            int[][] paths = (int[][]) field("paths").get(paired);
            for (int test = 0; test < 30; test++) {
                int a = 1 + random.nextInt(n), b = 1 + random.nextInt(n);
                if (a == b || assignment[a] == assignment[b]) continue;
                int[] beforeAssignment = assignment.clone();
                double[] beforeCosts = ((double[]) field("cachedNetCosts").get(paired)).clone();
                int[] beforeTransfers = ((int[]) field("cachedTransfers").get(paired)).clone();
                double before = independentCost(graph, paths, assignment, timing, 2.5, 3.25, 0.7);
                long transfersBefore = paired.transfers(assignment);
                double topologyBefore = paired.topologyCost(assignment);
                Object delta = method("probeSwap", int.class, int.class, int[].class).invoke(paired, a, b, assignment);
                Assertions.assertArrayEquals(beforeAssignment, assignment);
                Assertions.assertArrayEquals(beforeCosts, (double[]) field("cachedNetCosts").get(paired), 1e-9);
                Assertions.assertArrayEquals(beforeTransfers, (int[]) field("cachedTransfers").get(paired));
                int[] swapped = assignment.clone(); swapped[a] = assignment[b]; swapped[b] = assignment[a];
                Field cost = delta.getClass().getDeclaredField("cost"); cost.setAccessible(true);
                Field transfers = delta.getClass().getDeclaredField("transfers"); transfers.setAccessible(true);
                Field topology = delta.getClass().getDeclaredField("topology"); topology.setAccessible(true);
                Assertions.assertEquals(independentCost(graph, paths, swapped, timing, 2.5, 3.25, 0.7) - before,
                        cost.getDouble(delta), 1e-8);
                Assertions.assertEquals(paired.transfers(swapped) - transfersBefore, transfers.getLong(delta));
                Assertions.assertEquals(paired.topologyCost(swapped) - topologyBefore, topology.getDouble(delta), 1e-9);
            }
        }
    }

    @Test void perPassProbeAndCommittedSwapBudgetsRemainBounded() throws Exception {
        int n = 64; long[] logic = new long[n+1]; Arrays.fill(logic, 1);
        long[][] resources = resources(n); int[] owners = new int[n], fixed = unfixed(n), assignment = new int[n+1];
        boolean[] sequential = new boolean[n]; int[][] successors = new int[n][]; String[] names = new String[n];
        List<HardwarePartitionGraph.Net> nets = new ArrayList<>(); assignment[0] = -1;
        for (int u = 0; u < n; u++) {
            owners[u] = u + 1; names[u] = "p" + (u+1); assignment[u+1] = u % 2;
            sequential[u] = u % 4 == 0 || u % 4 == 3;
            if (sequential[u]) fixed[u+1] = assignment[u+1];
            successors[u] = u % 4 == 3 ? new int[0] : new int[] {u+1};
            if (successors[u].length > 0) nets.add(new HardwarePartitionGraph.Net(u+1, new int[] {u+2}));
        }
        HardwarePartitionGraph graph = new HardwarePartitionGraph(owners, sequential, successors, names, nets,
                Collections.emptyMap(), resources, logic, 0, 0);
        HardwareMappingRefiner paired = refiner(graph, fixed, 8, null);
        Path output = Files.createDirectory(directory.resolve("bounded"));
        int[] actual = paired.refine(assignment, 1, output);
        paired.validate(actual);
        Assertions.assertEquals(4, value(output, "proposed_swaps"));
        Assertions.assertTrue(value(output, "swap_cost_probes") <= 4096);
        Assertions.assertTrue(paired.transfers(actual) < paired.transfers(assignment));
    }
}
