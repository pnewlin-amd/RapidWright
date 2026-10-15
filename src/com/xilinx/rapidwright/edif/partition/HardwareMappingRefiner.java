/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded deterministic local refinement of assignments, preserving fixed vertices.
 * Net cost counts unique source-to-destination FPGA transfers, with weighted topology
 * distances. Worst-path witnesses contribute excess crossings and block reentries.
 * A full primitive DAG audit guards the final result against path regression.
 */
public final class HardwareMappingRefiner {
    private final HardwarePartitionGraph graph;
    private final PartitionTopology topology;
    private final int[][] minimumLinkHops;
    private final int k;
    private final long[][] caps;
    private final int[] fixed;
    private final double snakeWeight;
    private final double cutBudget;
    private final List<List<Integer>> incidentNets;
    private List<List<Integer>> incidentPaths;
    // One-based frontier-owner runs; consecutive primitives with the same
    // owner always share a partition under every candidate assignment.
    private int[][] paths;
    private int[][] sinkCounts;
    private double[] cachedNetCosts;
    private int[] cachedTransfers;
    private int[] balanceWeights;
    private long[] balanceCaps;
    // Updated only on committed moves, never during temporary cost probes.
    private long[] committedBalanceLoad;
    private TimingPathModel timingModel;
    private double timingWeight;
    private Double timingDelayNsPerCrossing;
    private List<List<Integer>> incidentTimingPaths;
    private Long selectionTransferReference;
    private Double selectionTopologyReference;
    // Bounded paired exchange: at most 64 relevant vertices, 16 feasible partners
    // per vertex/sweep, 4096 exact cost probes and 4 committed swaps per pass.
    private static final int SWAP_VERTICES = 64, SWAP_PARTNERS = 16;
    private static final int SWAP_PROBES = 4096, SWAPS_PER_PASS = 4;
    private static final long SWAP_WORK_PER_PASS = 8_000_000;
    private long swapProbes, swapFeasibilityRejects, swapCommits, swapWork, swapWorkSkips;
    private int[] timingOwnerLengths;
    private boolean swapPhaseAccepted;
    private String qualityGuardPolicy = "ranked";
    private String qualityAnchorDeclaredPolicy;
    private String qualityGuardAnchorPolicyMetadata = "not_used";
    private int retainedSwapPass = -1;
    private static final long SWAP_MAX_INDEX_ENTRIES = 65536;
    // Held proposal: explicitly configured, default off. No production CLI gate.
    private CaptureFaninCohort.Limits cohortLimits;
    private boolean[] cohortProtectedOwners;
    private long cohortWork;
    private int cohortProposals, cohortAccepted, cohortSkipped;

    public void configureCaptureCohorts(CaptureFaninCohort.Limits limits, boolean[] protectedOwners) {
        if (limits == null) { cohortLimits=null;cohortProtectedOwners=null;return; }
        if (protectedOwners == null || protectedOwners.length != graph.resources.length || protectedOwners[0])
            throw new IllegalArgumentException("Explicit one-based protected frontier-owner mask required");
        cohortLimits=limits;cohortProtectedOwners=protectedOwners.clone();
    }

    private static final class CohortResult {
        int[] assignment;
        CombinationalPathAnalyzer.Analysis audit;
        TimingPathModel.Evaluation timing;
        long transfers; double topology;
        CohortResult(int[] assignment,CombinationalPathAnalyzer.Analysis audit,TimingPathModel.Evaluation timing,long transfers,double topology) {
            this.assignment=assignment;this.audit=audit;this.timing=timing;this.transfers=transfers;this.topology=topology;
        }
    }

    // Instance-local diagnostics are available only after a successful return.
    // The digest binds the returned frontier assignment without another graph pass.
    private CombinationalPathAnalyzer.Analysis retainedEndpointAnalysis;
    private String retainedEndpointAssignmentDigest;

    public CombinationalPathAnalyzer.Analysis retainedEndpointAnalysis(int[] assignment) {
        if (retainedEndpointAnalysis == null || retainedEndpointAssignmentDigest == null
                || !retainedEndpointAssignmentDigest.equals(EndpointKindDiagnosticReport.assignmentDigest(assignment))) {
            throw new IllegalStateException("No endpoint diagnostic for this successfully retained assignment");
        }
        return retainedEndpointAnalysis;
    }

    private static final class SwapNeighborhood {
        final int[] nets, structural, timing;
        SwapNeighborhood(int[] nets, int[] structural, int[] timing) {
            this.nets = nets; this.structural = structural; this.timing = timing;
        }
    }
    private static final class SwapDelta {
        final double cost, topology;
        final long transfers;
        SwapDelta(double cost, long transfers, double topology) {
            this.cost = cost; this.transfers = transfers; this.topology = topology;
        }
    }
    private static final class SwapPass {
        int swaps;
        long transfers;
        double topology;
        SwapPass(long transfers, double topology) { this.transfers = transfers; this.topology = topology; }
    }

    private int[] qualityAnchorAssignment;
    private long qualityAnchorTransfers;
    private double qualityAnchorTopology;

    /** Caller supplies a previously native-cap-accepted mapping.
     * Its current scalar proxy load may differ; only returned proposals must
     * satisfy the current scalar/resource limits. Reevaluate quality in refine. */
    public static String validateQualityGuardPolicy(String policy) {
        if (!"ranked".equals(policy) && !"componentwise".equals(policy)) {
            throw new IllegalArgumentException("Quality guard must be ranked or componentwise");
        }
        return policy;
    }

    /** Ranked uses the exact existing structural/STA priority, independently of
     * hard capacities, completeness and original transfer/topology budgets. */
    public void configureQualityGuard(String policy) {
        validateQualityGuardPolicy(policy);
        if (qualityAnchorDeclaredPolicy != null && !qualityAnchorDeclaredPolicy.equals(policy)) {
            throw new IllegalArgumentException("Quality-anchor policy differs from requested quality guard");
        }
        qualityGuardPolicy = policy;
    }

    public String qualityGuardPolicy() { return qualityGuardPolicy; }
    public String qualityGuardAnchorPolicyMetadata() { return qualityGuardAnchorPolicyMetadata; }

    public void configureQualityAnchor(int[] assignment, long transferReference,
            double topologyReference) {
        configureQualityAnchor(assignment, transferReference, topologyReference, null);
    }

    public void configureQualityAnchor(int[] assignment, long transferReference,
            double topologyReference, String declaredGuardPolicy) {
        if (declaredGuardPolicy != null && !qualityGuardPolicy.equals(validateQualityGuardPolicy(declaredGuardPolicy))) {
            throw new IllegalArgumentException("Quality-anchor policy differs from requested quality guard");
        }
        qualityAnchorDeclaredPolicy = declaredGuardPolicy;
        qualityGuardAnchorPolicyMetadata = declaredGuardPolicy == null ? "legacy_absent_reanalyzed" : declaredGuardPolicy;
        if (assignment == null || assignment.length != graph.resources.length
                || transferReference < 0 || !Double.isFinite(topologyReference) || topologyReference < 0) {
            throw new IllegalArgumentException("Invalid quality-anchor assignment/budget references");
        }
        for (int v = 1; v < assignment.length; v++) {
            if (assignment[v] < 0 || assignment[v] >= k || (fixed[v] >= 0 && fixed[v] != assignment[v])) {
                throw new IllegalArgumentException("Quality anchor violates current fixed assignments/FPGA IDs");
            }
        }
        for (long useful : logicLoads(assignment)) {
            if (useful <= 0) throw new IllegalArgumentException("Quality anchor has empty useful logic");
        }
        qualityAnchorAssignment = assignment.clone();
        qualityAnchorTransfers = transferReference;
        qualityAnchorTopology = topologyReference;
    }

    public HardwareMappingRefiner(HardwarePartitionGraph graph, PartitionTopology topology,
            int k, double epsilon, int[] fixed, double snakeWeight, double cutBudget,
            Path capacityFile) throws IOException {
        this.graph = graph;
        this.topology = topology;
        this.minimumLinkHops = topology.hasDeclaredPhysicalAdjacency() ? topology.minimumHopMatrix() : null;
        this.k = k;
        this.fixed = fixed;
        this.snakeWeight = snakeWeight;
        this.cutBudget = cutBudget;
        if (capacityFile != null && graph.opaquePrimitives > 0) {
            throw new IllegalArgumentException("Cannot certify hard resource capacities with opaque primitives;") ;
        }
        caps = new long[k][6];
        long total = 0;
        for (long[] resource : graph.resources) { total += resource[0]; }
        long lutBound = (long) Math.floor((1.0 + epsilon) * Math.ceil(total / (double) k));
        for (long[] row : caps) {
            Arrays.fill(row, Long.MAX_VALUE);
            row[0] = lutBound;
        }
        if (capacityFile != null) {
            // FPGA index (zero based), logical LUT, FF/latch, BRAM18, DSP, URAM, LUTRAM/SRL primitive.
            boolean[] seen = new boolean[k];
            for (String line : Files.readAllLines(capacityFile)) {
                if (line.trim().isEmpty() || line.trim().startsWith("#")) { continue; }
                String[] fields = line.trim().split("[,\\s]+");
                if (fields.length != 7) {
                    throw new IllegalArgumentException("Capacity row requires FPGA index and six capacities");
                }
                int p = Integer.parseInt(fields[0]);
                if (p < 0 || p >= k || seen[p]) {
                    throw new IllegalArgumentException("Duplicate/invalid capacity FPGA index");
                }
                seen[p] = true;
                for (int r = 0; r < 6; r++) {
                    caps[p][r] = Long.parseLong(fields[r + 1]);
                    if (caps[p][r] < 0) { throw new IllegalArgumentException("Negative resource capacity"); }
                }
            }
            for (boolean s : seen) {
                if (!s) { throw new IllegalArgumentException("Capacity file must define every FPGA"); }
            }
        }
        incidentNets = new ArrayList<>();
        for (int v = 0; v < graph.resources.length; v++) { incidentNets.add(new ArrayList<>()); }
        for (int i = 0; i < graph.nets.size(); i++) {
            HardwarePartitionGraph.Net net = graph.nets.get(i);
            incidentNets.get(net.driver).add(i);
            for (int sink : net.sinks) {
                if (sink != net.driver) { incidentNets.get(sink).add(i); }
            }
        }
    }

    /** Adds an estimated scalar balance bound, independently of logical resources.
     * Weight array is one based and copied. This is never an exact physical-area claim.
     */
    public void configureBalanceWeights(int[] weights, double epsilon) {
        if (weights == null) {
            balanceWeights = null;
            balanceCaps = null;
            committedBalanceLoad = null;
            return;
        }
        if (weights.length != graph.resources.length || weights[0] != 0
                || !Double.isFinite(epsilon) || epsilon < 0) {
            throw new IllegalArgumentException("Invalid one-based estimated balance weights/epsilon");
        }
        long total = 0;
        for (int weight : weights) {
            if (weight < 0) throw new IllegalArgumentException("Negative estimated balance weight");
            total = Math.addExact(total, weight);
        }
        double bound = Math.floor((1.0 + epsilon) * Math.ceil(total / (double) k));
        if (!Double.isFinite(bound) || bound > Long.MAX_VALUE) {
            throw new IllegalArgumentException("Estimated balance capacity exceeds long range");
        }
        balanceWeights = weights.clone();
        balanceCaps = new long[k];
        Arrays.fill(balanceCaps, (long) bound);
        committedBalanceLoad = null;
    }

    /** Explicit scalar capacities match the backend's --part-weights exactly. */
    public void configureBalanceWeights(int[] weights, long[] capacities) {
        if (weights == null || capacities == null || capacities.length != k) {
            throw new IllegalArgumentException("Explicit scalar capacities require weights and every FPGA");
        }
        configureBalanceWeights(weights, 0.0);
        for (long capacity : capacities) {
            if (capacity < 0 || capacity > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid scalar capacity");
            }
        }
        balanceCaps = capacities.clone();
    }

    /** Adds sampled native criticality guidance. An optional explicitly supplied
     * delay updates sampled slack after every trial move; no delay is invented.
     */
    public void configureTimingPaths(TimingPathModel model, double weight, Double delayNsPerCrossing) {
        if (!Double.isFinite(weight) || weight < 0
                || (delayNsPerCrossing != null && (!Double.isFinite(delayNsPerCrossing) || delayNsPerCrossing < 0))) {
            throw new IllegalArgumentException("Timing weight/delay must be finite and nonnegative");
        }
        if (model != null && model.ownerLimit() != graph.resources.length - 1) {
            throw new IllegalArgumentException("Timing model and graph frontier differ");
        }
        timingModel = model; timingWeight = weight; timingDelayNsPerCrossing = delayNsPerCrossing;
        incidentTimingPaths = null; timingOwnerLengths = null;
        if (model == null) return;
        timingOwnerLengths = new int[model.pathCount()];
        incidentTimingPaths = new ArrayList<>();
        for (int v = 0; v < graph.resources.length; v++) incidentTimingPaths.add(new ArrayList<>());
        for (int p = 0; p < model.pathCount(); p++) {
            if (!model.path(p).scored) continue;
            Set<Integer> owners = new HashSet<>();
            int[] orderedOwners = model.path(p).owners(); timingOwnerLengths[p] = orderedOwners.length;
            for (int owner : orderedOwners) owners.add(owner);
            for (int owner : owners) incidentTimingPaths.get(owner).add(p);
        }
    }

    public TimingPathModel.Evaluation timingEvaluation(int[] assignment) {
        return timingModel == null ? null : timingModel.evaluate(assignment, timingDelayNsPerCrossing, minimumLinkHops);
    }

    private static int compareTiming(TimingPathModel.Evaluation left, TimingPathModel.Evaluation right) {
        if (left == null || right == null) return 0;
        if (left.modeledWorstSlackNs != null && right.modeledWorstSlackNs != null) {
            int slack = Double.compare(right.modeledWorstSlackNs, left.modeledWorstSlackNs);
            if (slack != 0) return slack;
        }
        return Double.compare(left.timingCost, right.timingCost);
    }

    private static boolean timingNonregression(TimingPathModel.Evaluation candidate,
            TimingPathModel.Evaluation baseline) {
        return candidate == null || baseline == null || (candidate.timingCost <= baseline.timingCost + 1e-9
                && (candidate.modeledWorstSlackNs == null || baseline.modeledWorstSlackNs == null
                    || candidate.modeledWorstSlackNs + 1e-9 >= baseline.modeledWorstSlackNs));
    }

    public static int[] readSolution(Path path, int vertices, int k) throws IOException {
        List<String> lines = Files.readAllLines(path);
        if (lines.size() != vertices) {
            throw new IllegalArgumentException("Partition solution length " + lines.size()
                    + " differs from vertex count " + vertices);
        }
        int[] result = new int[vertices + 1];
        result[0] = -1;
        for (int v = 1; v <= vertices; v++) {
            int p = Integer.parseInt(lines.get(v - 1).trim());
            if (p < 0 || p >= k) { throw new IllegalArgumentException("Invalid partition ID " + p); }
            result[v] = p;
        }
        return result;
    }

    public static int[] readFixed(Path file, int vertices, int k) throws IOException {
        int[] result = new int[vertices + 1];
        Arrays.fill(result, -1);
        if (file == null) { return result; }
        List<String> lines = Files.readAllLines(file);
        if (lines.size() != vertices) { throw new IllegalArgumentException("Fixed file length mismatch"); }
        for (int v = 1; v <= vertices; v++) {
            result[v] = Integer.parseInt(lines.get(v - 1).trim());
            if (result[v] < -1 || result[v] >= k) {
                throw new IllegalArgumentException("Invalid fixed partition ID");
            }
        }
        return result;
    }

    private double netCost(HardwarePartitionGraph.Net net, int[] assignment) {
        int driver = assignment[net.driver];
        Set<Integer> dest = new HashSet<>();
        double cost = 0;
        for (int sink : net.sinks) {
            int p = assignment[sink];
            if (p != driver && dest.add(p)) { cost += 1 + topology.distance(driver, p); }
        }
        return cost;
    }

    public double topologyCost(int[] assignment) {
        double total = 0;
        for (HardwarePartitionGraph.Net net : graph.nets) {
            total += netCost(net, assignment);
        }
        return total - transfers(assignment);
    }

    private void initializeNetCache(int[] assignment) {
        sinkCounts = new int[graph.nets.size()][k];
        cachedNetCosts = new double[graph.nets.size()];
        cachedTransfers = new int[graph.nets.size()];
        for (int e = 0; e < graph.nets.size(); e++) {
            HardwarePartitionGraph.Net net = graph.nets.get(e);
            for (int v : net.sinks) { sinkCounts[e][assignment[v]]++; }
            for (int p = 0; p < k; p++) {
                if (sinkCounts[e][p] > 0 && p != assignment[net.driver]) {
                    cachedNetCosts[e] += 1 + topology.distance(assignment[net.driver], p);
                    cachedTransfers[e]++;
                }
            }
        }
    }

    private double contribution(int driver, int destination, int count) {
        return count > 0 && driver != destination ? 1 + topology.distance(driver, destination) : 0;
    }

    /** Update only incident nets. A sink move costs O(1) per net even for huge fanout. */
    private void setPlacement(int vertex, int dest, int[] assignment) {
        int source = assignment[vertex];
        if (source == dest) { return; }
        for (int e : incidentNets.get(vertex)) {
            HardwarePartitionGraph.Net net = graph.nets.get(e);
            int oldDriver = assignment[net.driver];
            boolean sink = Arrays.binarySearch(net.sinks, vertex) >= 0;
            if (net.driver != vertex && sink) {
                double before = contribution(oldDriver, source, sinkCounts[e][source])
                        + contribution(oldDriver, dest, sinkCounts[e][dest]);
                int beforeTransfers = (sinkCounts[e][source] > 0 && source != oldDriver ? 1 : 0)
                        + (sinkCounts[e][dest] > 0 && dest != oldDriver ? 1 : 0);
                sinkCounts[e][source]--;
                sinkCounts[e][dest]++;
                cachedNetCosts[e] += contribution(oldDriver, source, sinkCounts[e][source])
                        + contribution(oldDriver, dest, sinkCounts[e][dest]) - before;
                cachedTransfers[e] += (sinkCounts[e][source] > 0 && source != oldDriver ? 1 : 0)
                        + (sinkCounts[e][dest] > 0 && dest != oldDriver ? 1 : 0) - beforeTransfers;
            } else if (net.driver == vertex) {
                if (sink) { sinkCounts[e][source]--; sinkCounts[e][dest]++; }
                cachedNetCosts[e] = 0;
                cachedTransfers[e] = 0;
                for (int p = 0; p < k; p++) {
                    cachedNetCosts[e] += contribution(dest, p, sinkCounts[e][p]);
                    if (p != dest && sinkCounts[e][p] > 0) { cachedTransfers[e]++; }
                }
            }
        }
        assignment[vertex] = dest;
    }

    public long transfers(int[] assignment) {
        long total = 0;
        for (HardwarePartitionGraph.Net net : graph.nets) {
            Set<Integer> dest = new HashSet<>();
            for (int sink : net.sinks) {
                if (assignment[sink] != assignment[net.driver]) { dest.add(assignment[sink]); }
            }
            total += dest.size();
        }
        return total;
    }

    private int localTransfers(int vertex, int[] assignment) {
        int total = 0;
        for (int edge : incidentNets.get(vertex)) {
            total += cachedTransfers[edge];
        }
        return total;
    }

    /** Select the best complete path audit within the best native transfer count's budget. */
    public int[] selectCandidate(List<int[]> candidates) {
        selectionTransferReference = null;
        selectionTopologyReference = null;
        List<int[]> feasible = new ArrayList<>();
        long minimumTransfers = Long.MAX_VALUE;
        for (int[] candidate : candidates) {
            try { validate(candidate); } catch (IllegalArgumentException e) { continue; }
            feasible.add(candidate);
            minimumTransfers = Math.min(minimumTransfers, transfers(candidate));
        }
        if (feasible.isEmpty()) { return candidates.get(0); } // refine() attempts feasibility repair.
        long limit = minimumTransfers + (long) Math.ceil(minimumTransfers * cutBudget);
        double minimumTopology = Double.POSITIVE_INFINITY;
        for (int[] candidate : feasible) {
            if (transfers(candidate) <= limit) {
                minimumTopology = Math.min(minimumTopology, topologyCost(candidate));
            }
        }
        selectionTransferReference = minimumTransfers;
        selectionTopologyReference = minimumTopology;
        CombinationalPathAnalyzer analyzer = new CombinationalPathAnalyzer(
                graph.ownerVertex, graph.sequential, graph.successors);
        int[] best = null;
        CombinationalPathAnalyzer.Analysis bestAudit = null;
        TimingPathModel.Evaluation bestTiming = null;
        long bestTransfers = Long.MAX_VALUE;
        double bestTopology = Double.POSITIVE_INFINITY;
        for (int[] candidate : feasible) {
            long transfer = transfers(candidate);
            double topology = topologyCost(candidate);
            if (transfer > limit || topology > minimumTopology
                    + Math.ceil(minimumTopology * cutBudget) + 1e-9) {
                continue;
            }
            CombinationalPathAnalyzer.Analysis audit = analyzer.analyze(candidate, minimumLinkHops);
            TimingPathModel.Evaluation timing = timingEvaluation(candidate);
            if (best == null || compareQuality(audit, timing, transfer, topology,
                    bestAudit, bestTiming, bestTransfers, bestTopology) < 0) {
                best = candidate; bestAudit = audit; bestTiming = timing;
                bestTransfers = transfer; bestTopology = topology;
            }
        }
        return best;
    }

    private double pathCost(int[] path, int[] assignment) {
        Set<Integer> visited = new HashSet<>();
        int last = -1;
        long minimumLinks = 0;
        int reentries = 0;
        for (int owner : path) {
            int p = assignment[owner];
            if (p != last) {
                if (last >= 0) { minimumLinks += minimumLinkHops == null ? 1 : minimumLinkHops[last][p]; }
                if (!visited.add(p)) { reentries++; }
                last = p;
            }
        }
        long excess = Math.max(0, minimumLinks - 1);
        return snakeWeight * (excess * (double) excess + reentries);
    }

    /** Compresses only consecutive identical owners, preserving later reentries. */
    static int[] compressPathOwners(int[] primitivePath, int[] ownerVertex) {
        int[] owners = new int[primitivePath.length];
        int length = 0;
        for (int primitive : primitivePath) {
            int owner = ownerVertex[primitive];
            if (length == 0 || owners[length - 1] != owner) owners[length++] = owner;
        }
        return length == owners.length ? owners : Arrays.copyOf(owners, length);
    }

    private double localCost(int vertex, int[] assignment) {
        double cost = localNetCost(vertex);
        if (incidentPaths != null) {
            for (int path : incidentPaths.get(vertex)) { cost += pathCost(paths[path], assignment); }
        }
        if (incidentTimingPaths != null) {
            for (int path : incidentTimingPaths.get(vertex)) {
                cost += timingWeight * timingModel.pathCost(path, assignment, timingDelayNsPerCrossing, minimumLinkHops);
            }
        }
        return cost;
    }

    private double localNetCost(int vertex) {
        double cost = 0;
        for (int edge : incidentNets.get(vertex)) { cost += cachedNetCosts[edge]; }
        return cost;
    }

    private void refreshPaths(CombinationalPathAnalyzer analyzer, int[] assignment) {
        paths = analyzer.collectWorstPaths(assignment, 4096, 1_000_000, minimumLinkHops);
        for (int p = 0; p < paths.length; p++) {
            paths[p] = compressPathOwners(paths[p], graph.ownerVertex);
        }
        incidentPaths = new ArrayList<>();
        for (int v = 0; v < assignment.length; v++) { incidentPaths.add(new ArrayList<>()); }
        for (int p = 0; p < paths.length; p++) {
            Set<Integer> vertices = new HashSet<>();
            for (int owner : paths[p]) { vertices.add(owner); }
            for (int v : vertices) { incidentPaths.get(v).add(p); }
        }
    }

    /** Source STA alone supplies criticality, not a transport-delay model.
     * Preserve the all-modeled-path worst crossing priority until an explicit
     * delay permits sampled modeled-slack comparisons. Negative prefers left. */
    int compareGuardQuality(CombinationalPathAnalyzer.Analysis left,
            TimingPathModel.Evaluation leftTiming,
            CombinationalPathAnalyzer.Analysis right,
            TimingPathModel.Evaluation rightTiming) {
        if (left.complete != right.complete) return left.complete ? -1 : 1;
        boolean modeled = timingModel != null && timingDelayNsPerCrossing != null;
        int comparison;
        if (!modeled) {
            comparison = Long.compare(left.maxMinimumLinkHops, right.maxMinimumLinkHops);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(left.maxCrossings, right.maxCrossings);
            if (comparison != 0) return comparison;
        }
        comparison = compareTiming(leftTiming, rightTiming);
        if (comparison != 0) return comparison;
        comparison = Long.compare(left.maxMinimumLinkHops, right.maxMinimumLinkHops);
        if (comparison != 0) return comparison;
        comparison = Integer.compare(left.maxCrossings, right.maxCrossings);
        if (comparison != 0) return comparison;
        comparison = Long.compare(left.excessCrossings, right.excessCrossings);
        if (comparison != 0) return comparison;
        comparison = Integer.compare(left.transitCount, right.transitCount);
        if (comparison != 0) return comparison;
        return comparison;
    }

    /** Only structural/STA metrics participate in the nonregression guard.
     * Transfer/topology bounds and resource/occupancy checks stay independent. */
    boolean qualityNonregression(CombinationalPathAnalyzer.Analysis candidate,
            TimingPathModel.Evaluation candidateTiming, CombinationalPathAnalyzer.Analysis reference,
            TimingPathModel.Evaluation referenceTiming) {
        if (!candidate.complete) return false;
        if ("ranked".equals(qualityGuardPolicy)) {
            return compareGuardQuality(candidate, candidateTiming, reference, referenceTiming) <= 0;
        }
        return timingNonregression(candidateTiming, referenceTiming)
                && candidate.maxMinimumLinkHops <= reference.maxMinimumLinkHops
                && candidate.maxCrossings <= reference.maxCrossings
                && candidate.excessCrossings <= reference.excessCrossings
                && candidate.transitCount <= reference.transitCount;
    }

    private int compareQuality(CombinationalPathAnalyzer.Analysis left,
            TimingPathModel.Evaluation leftTiming, long leftTransfers, double leftTopology,
            CombinationalPathAnalyzer.Analysis right,
            TimingPathModel.Evaluation rightTiming, long rightTransfers, double rightTopology) {
        int comparison = compareGuardQuality(left, leftTiming, right, rightTiming);
        if (comparison != 0) return comparison;
        comparison = Long.compare(leftTransfers, rightTransfers);
        return comparison != 0 ? comparison : Double.compare(leftTopology, rightTopology);
    }

    private boolean fits(int vertex, int destination, long[][] load) {
        if (balanceWeights != null && balanceWeights[vertex]
                > balanceCaps[destination] - committedBalanceLoad[destination]) return false;
        for (int r = 0; r < 6; r++) {
            if (graph.resources[vertex][r] > caps[destination][r] - load[destination][r]) {
                return false;
            }
        }
        return true;
    }

    private boolean overloaded(int part, long[][] load) {
        if (balanceWeights != null && committedBalanceLoad[part] > balanceCaps[part]) return true;
        for (int r = 0; r < 6; r++) {
            if (load[part][r] > caps[part][r]) { return true; }
        }
        return false;
    }

    private boolean reducesOverflow(int vertex, int source, long[][] load) {
        if (balanceWeights != null && committedBalanceLoad[source] > balanceCaps[source]
                && balanceWeights[vertex] > 0) return true;
        for (int r = 0; r < 6; r++) {
            if (load[source][r] > caps[source][r] && graph.resources[vertex][r] > 0) {
                return true;
            }
        }
        return false;
    }

    private void move(int vertex, int dest, int[] assignment, long[][] load, long[] logic) {
        int source = assignment[vertex];
        for (int r = 0; r < 6; r++) {
            load[source][r] -= graph.resources[vertex][r];
            load[dest][r] += graph.resources[vertex][r];
        }
        logic[source] -= graph.realLogic[vertex];
        logic[dest] += graph.realLogic[vertex];
        if (balanceWeights != null) {
            committedBalanceLoad[source] -= balanceWeights[vertex];
            committedBalanceLoad[dest] += balanceWeights[vertex];
        }
        setPlacement(vertex, dest, assignment);
    }

    private long[][] loads(int[] assignment) {
        long[][] load = new long[k][6];
        for (int v = 1; v < assignment.length; v++) {
            for (int r = 0; r < 6; r++) { load[assignment[v]][r] += graph.resources[v][r]; }
        }
        return load;
    }

    private long[] logicLoads(int[] assignment) {
        long[] result = new long[k];
        for (int v = 1; v < assignment.length; v++) {
            result[assignment[v]] += graph.realLogic[v];
        }
        return result;
    }

    private long[] balanceLoads(int[] assignment) {
        if (balanceWeights == null) return null;
        long[] result = new long[k];
        for (int v = 1; v < assignment.length; v++) {
            result[assignment[v]] = Math.addExact(result[assignment[v]], balanceWeights[v]);
        }
        return result;
    }

    public void validate(int[] assignment) {
        long[][] load = loads(assignment);
        long[] logic = logicLoads(assignment);
        long[] weighted = balanceLoads(assignment);
        for (int v = 1; v < assignment.length; v++) {
            if (fixed[v] >= 0 && fixed[v] != assignment[v]) {
                throw new IllegalArgumentException("Fixed assignment violated for vertex " + v);
            }
        }
        for (int p = 0; p < k; p++) {
            boolean resourceOverflow = false;
            for (int r = 0; r < 6; r++) resourceOverflow |= load[p][r] > caps[p][r];
            if (logic[p] == 0 || resourceOverflow || (weighted != null && weighted[p] > balanceCaps[p])) {
                throw new IllegalArgumentException("Infeasible partition " + p
                        + ": empty real logic or resource capacity exceeded; reduce leafLUTCountLimit"
                        + " or relax capacity/constraints");
            }
        }
    }

    private static int[] uniqueUnion(List<Integer> left, List<Integer> right) {
        Set<Integer> unique = new java.util.LinkedHashSet<>();
        unique.addAll(left); unique.addAll(right);
        return unique.stream().mapToInt(Integer::intValue).toArray();
    }

    private SwapNeighborhood swapNeighborhood(int a, int b) {
        return new SwapNeighborhood(uniqueUnion(incidentNets.get(a), incidentNets.get(b)),
                incidentPaths == null ? new int[0] : uniqueUnion(incidentPaths.get(a), incidentPaths.get(b)),
                incidentTimingPaths == null ? new int[0] : uniqueUnion(incidentTimingPaths.get(a), incidentTimingPaths.get(b)));
    }

    private double swapLocalCost(SwapNeighborhood neighborhood, int[] assignment) {
        double result = 0;
        for (int net : neighborhood.nets) result += cachedNetCosts[net];
        for (int path : neighborhood.structural) result += pathCost(paths[path], assignment);
        for (int path : neighborhood.timing) result += timingWeight * timingModel.pathCost(path, assignment, timingDelayNsPerCrossing, minimumLinkHops);
        return result;
    }

    private double swapLocalNetCost(SwapNeighborhood neighborhood) {
        double result = 0;
        for (int net : neighborhood.nets) result += cachedNetCosts[net];
        return result;
    }

    private long swapLocalTransfers(SwapNeighborhood neighborhood) {
        long result = 0;
        for (int net : neighborhood.nets) result += cachedTransfers[net];
        return result;
    }

    /** Evaluate the simultaneous final exchange. Intermediate cache placements
     * are temporary, not capacity-certified proposals. Shared neighborhoods are
     * deduplicated exactly once, including driver/sink and later path reentries. */
    private SwapDelta probeSwap(int a, int b, int[] assignment) {
        return probeSwap(a, b, assignment, swapNeighborhood(a, b));
    }

    private SwapDelta probeSwap(int a, int b, int[] assignment, SwapNeighborhood neighborhood) {
        int sourceA = assignment[a], sourceB = assignment[b];
        double before = swapLocalCost(neighborhood, assignment);
        double beforeNet = swapLocalNetCost(neighborhood);
        long beforeTransfers = swapLocalTransfers(neighborhood);
        try {
            setPlacement(a, sourceB, assignment);
            setPlacement(b, sourceA, assignment);
            long transferDelta = swapLocalTransfers(neighborhood) - beforeTransfers;
            return new SwapDelta(swapLocalCost(neighborhood, assignment) - before,
                    transferDelta, swapLocalNetCost(neighborhood) - beforeNet - transferDelta);
        } finally {
            setPlacement(b, sourceB, assignment);
            setPlacement(a, sourceA, assignment);
        }
    }

    private long swapIndexWork(int a, int b) {
        long work = (long) incidentNets.get(a).size() + incidentNets.get(b).size();
        if (incidentPaths != null) work += (long) incidentPaths.get(a).size() + incidentPaths.get(b).size();
        if (incidentTimingPaths != null) work += (long) incidentTimingPaths.get(a).size() + incidentTimingPaths.get(b).size();
        return work;
    }

    private long swapNeighborhoodWork(SwapNeighborhood neighborhood) {
        // Conservative scan-unit budget, in addition to the probe-count bound.
        // Exact expensive neighborhoods are skipped, never approximately scored.
        long work = 8L * neighborhood.nets.length * (k + 1L);
        for (int path : neighborhood.structural) work += 2L * paths[path].length;
        for (int path : neighborhood.timing) work += 4L * timingOwnerLengths[path];
        return work;
    }

    /** The final paired loads must satisfy every resource, scalar, fixed and
     * nonempty-useful-logic constraint even if neither individual move fits. */
    private boolean swapFits(int a, int b, int[] assignment, long[][] load, long[] logic) {
        int p = assignment[a], q = assignment[b];
        if (a == b || p == q || fixed[a] >= 0 || fixed[b] >= 0) return false;
        if (logic[p] - graph.realLogic[a] + graph.realLogic[b] <= 0
                || logic[q] - graph.realLogic[b] + graph.realLogic[a] <= 0) return false;
        for (int r = 0; r < 6; r++) {
            if (load[p][r] - graph.resources[a][r] + graph.resources[b][r] > caps[p][r]
                    || load[q][r] - graph.resources[b][r] + graph.resources[a][r] > caps[q][r]) return false;
        }
        return balanceWeights == null || (committedBalanceLoad[p] - balanceWeights[a] + balanceWeights[b] <= balanceCaps[p]
                && committedBalanceLoad[q] - balanceWeights[b] + balanceWeights[a] <= balanceCaps[q]);
    }

    private void addSwapCandidate(Set<Integer> candidates, int vertex) {
        if (candidates.size() < SWAP_VERTICES && fixed[vertex] < 0) candidates.add(vertex);
    }

    private int[] swapCandidates(int[] assignment) {
        Set<Integer> candidates = new java.util.LinkedHashSet<>();
        // Witness order comes from the existing bounded worst-path collector.
        for (int[] path : paths) {
            boolean crossing = false;
            for (int i = 1; i < path.length; i++) crossing |= assignment[path[i-1]] != assignment[path[i]];
            if (crossing) for (int vertex : path) addSwapCandidate(candidates, vertex);
            if (candidates.size() == SWAP_VERTICES) break;
        }
        for (HardwarePartitionGraph.Net net : graph.nets) {
            if (candidates.size() == SWAP_VERTICES) break;
            for (int sink : net.sinks) {
                if (assignment[net.driver] != assignment[sink]) {
                    addSwapCandidate(candidates, net.driver); addSwapCandidate(candidates, sink);
                    if (candidates.size() == SWAP_VERTICES) break;
                }
            }
        }
        return candidates.stream().mapToInt(Integer::intValue).toArray();
    }

    private SwapPass swapPass(int[] assignment, long[][] load, long[] logic,
            long transfers, double topology, long maxTransfers, double maxTopology) {
        SwapPass result = new SwapPass(transfers, topology);
        int[] vertices = swapCandidates(assignment);
        int probes = 0;
        long work = 0;
        while (result.swaps < SWAPS_PER_PASS && probes < SWAP_PROBES) {
            int bestA = -1, bestB = -1;
            SwapDelta best = new SwapDelta(-1e-9, 0, 0);
            for (int i = 0; i < vertices.length && probes < SWAP_PROBES; i++) {
                int partners = 0;
                for (int j = i + 1; j < vertices.length && probes < SWAP_PROBES && partners < SWAP_PARTNERS; j++) {
                    int a = vertices[i], b = vertices[j];
                    if (assignment[a] == assignment[b]) continue;
                    if (!swapFits(a, b, assignment, load, logic)) { swapFeasibilityRejects++; continue; }
                    partners++;
                    long indices = swapIndexWork(a, b);
                    if (indices > SWAP_MAX_INDEX_ENTRIES || indices > SWAP_WORK_PER_PASS - work) { swapWorkSkips++; continue; }
                    work += indices; swapWork += indices;
                    SwapNeighborhood neighborhood = swapNeighborhood(a, b);
                    long estimate = swapNeighborhoodWork(neighborhood);
                    if (estimate > SWAP_WORK_PER_PASS - work) { swapWorkSkips++; continue; }
                    probes++; swapProbes++; work += estimate; swapWork += estimate;
                    SwapDelta delta = probeSwap(a, b, assignment, neighborhood);
                    if (delta.cost < best.cost && result.transfers + delta.transfers <= maxTransfers
                            && result.topology + delta.topology <= maxTopology + 1e-9) {
                        bestA = a; bestB = b; best = delta;
                    }
                }
            }
            if (bestA < 0) break;
            int sourceA = assignment[bestA], sourceB = assignment[bestB];
            move(bestA, sourceB, assignment, load, logic);
            move(bestB, sourceA, assignment, load, logic);
            result.transfers += best.transfers; result.topology += best.topology;
            result.swaps++; swapCommits++;
        }
        return result;
    }

    private boolean cohortStructuralAndTimingNonregression(CombinationalPathAnalyzer.Analysis candidate,
            TimingPathModel.Evaluation candidateTiming,CombinationalPathAnalyzer.Analysis reference,TimingPathModel.Evaluation referenceTiming) {
        return qualityNonregression(candidate,candidateTiming,reference,referenceTiming)
                && candidate.maxMinimumLinkHops<=reference.maxMinimumLinkHops && candidate.maxCrossings<=reference.maxCrossings
                && candidate.excessCrossings<=reference.excessCrossings && candidate.transitCount<=reference.transitCount
                && timingNonregression(candidateTiming,referenceTiming);
    }

    /** Validate only a complete transactional assignment, including overflow-safe
     * aggregate arithmetic, before using the existing fixed/resource/scalar gate. */
    private boolean cohortConstraintsSatisfied(int[] assignment) {
        try {
            long[][] totals=new long[k][6];long[] useful=new long[k];
            for(int owner=1;owner<assignment.length;owner++) {
                int part=assignment[owner];if(part<0||part>=k)return false;
                for(int r=0;r<6;r++) {if(graph.resources[owner][r]<0)return false;totals[part][r]=Math.addExact(totals[part][r],graph.resources[owner][r]);}
                if(graph.realLogic[owner]<0)return false;useful[part]=Math.addExact(useful[part],graph.realLogic[owner]);
            }
            validate(assignment);return true;
        } catch(IllegalArgumentException|ArithmeticException failure) {return false;}
    }

    private boolean reserveCohortWork(long units) {
        if(units<0||units>cohortLimits.maxWork-cohortWork){cohortSkipped++;return false;}
        cohortWork+=units;return true;
    }

    private CohortResult cohortPhase(CombinationalPathAnalyzer analyzer,CohortResult best,
            CombinationalPathAnalyzer.Analysis guardAudit,TimingPathModel.Evaluation guardTiming,
            long maxTransfers,double maxTopology,List<String> rows) {
        if(graph.opaquePrimitives>0||!best.audit.complete){cohortSkipped++;rows.add("-1\t0\t0\t-1\t0\t0\t0\t-1\t0\topaque_or_incomplete_graph");return best;}
        // Charge source-index lengths before scanning, then reserve conservative
        // full global audit/resource/net/STA work before each exact proposal.
        if(!reserveCohortWork(graph.ownerVertex.length))return best;
        long edges=0;for(int[] next:graph.successors)edges=Math.addExact(edges,next.length);
        if(!reserveCohortWork(graph.nets.size()))return best;
        long sinkEntries=0;for(HardwarePartitionGraph.Net net:graph.nets)sinkEntries=Math.addExact(sinkEntries,net.sinks.length);
        long timingEntries=0;if(timingOwnerLengths!=null) {
            if(!reserveCohortWork(timingOwnerLengths.length))return best;
            for(int length:timingOwnerLengths)timingEntries=Math.addExact(timingEntries,length);
        }
        long auditWork=16L*(graph.ownerVertex.length+edges)+32L*graph.resources.length+4L*timingEntries+8L*graph.nets.size()+4L*sinkEntries+12L*k;
        if(!reserveCohortWork(auditWork))return best;
        int[] endpoints=analyzer.worstCaptureEndpoints(best.assignment,minimumLinkHops,cohortLimits.maxCaptures);
        Set<String> proposalIdentities=new HashSet<>();
        for(int endpoint:endpoints) {
            CaptureFaninCohort.Cone cone=CaptureFaninCohort.collect(graph,analyzer,endpoint,best.assignment,cohortProtectedOwners,cohortLimits,(cohortLimits.maxWork-cohortWork)/4);
            cohortWork+=4L*(cone.nodes+cone.edges);
            if(cone.skip!=null){cohortSkipped++;rows.add(endpoint+"\t"+cone.nodes+"\t"+cone.owners.length+"\t-1\t0\t0\t0\t-1\t0\t"+cone.skip);continue;}
            java.util.LinkedHashSet<Integer> homes=new java.util.LinkedHashSet<>();homes.add(best.assignment[cone.captureOwner]);
            for(int home:cone.boundaryHomes)if(homes.size()<cohortLimits.maxTargets)homes.add(home);
            for(int target:homes)for(boolean includeCapture:new boolean[]{true,false}) {
                if(cohortProposals>=cohortLimits.maxProposals)return best;
                java.util.TreeSet<Integer> owners=new java.util.TreeSet<>();for(int owner:cone.owners)owners.add(owner);
                if(includeCapture)owners.add(cone.captureOwner);
                if(owners.size()>cohortLimits.maxVertices){cohortSkipped++;continue;}
                boolean fixedConflict=false;int changed=0;StringBuilder changedOwners=new StringBuilder();
                for(int owner:owners){if(fixed[owner]>=0&&fixed[owner]!=target)fixedConflict=true;if(best.assignment[owner]!=target){changed++;changedOwners.append(owner).append(',');}}
                if(fixedConflict||changed==0){cohortSkipped++;continue;}
                if(!proposalIdentities.add(changedOwners.toString()+"@"+target)){cohortSkipped++;continue;}
                if(!reserveCohortWork(auditWork))return best;
                cohortProposals++;
                int[] candidate=best.assignment.clone();for(int owner:owners)candidate[owner]=target;
                boolean constraints=cohortConstraintsSatisfied(candidate);
                long transfers=constraints?transfers(candidate):0;double topologyCost=constraints?topologyCost(candidate):0;
                CombinationalPathAnalyzer.Analysis audit=null;TimingPathModel.Evaluation timing=null;
                String disposition="fixed_occupancy_resource_or_scalar_reject";
                if(constraints&&transfers<=maxTransfers&&topologyCost<=maxTopology+1e-9) {
                    audit=analyzer.analyze(candidate,minimumLinkHops);timing=timingEvaluation(candidate);
                    if(!cohortStructuralAndTimingNonregression(audit,timing,guardAudit,guardTiming)
                            ||!cohortStructuralAndTimingNonregression(audit,timing,best.audit,best.timing))disposition="global_structural_or_sampled_sta_reject";
                    else if(compareQuality(audit,timing,transfers,topologyCost,best.audit,best.timing,best.transfers,best.topology)>=0)disposition="no_ranked_improvement";
                    else {best=new CohortResult(candidate,audit,timing,transfers,topologyCost);cohortAccepted++;disposition="accepted";}
                } else if(constraints)disposition="original_transfer_or_topology_budget_reject";
                rows.add(endpoint+"\t"+cone.nodes+"\t"+owners.size()+"\t"+target+"\t"+changed+"\t"+transfers+"\t"+topologyCost
                        +"\t"+(audit==null?-1:audit.maxMinimumLinkHops)+"\t"+(audit==null?0:audit.maxCrossings)+"\t"+disposition);
            }
        }
        return best;
    }

    /** Returns an audited assignment; never silently returns an infeasible mapping. */
    public int[] refine(int[] initial, int passes, Path outDir) throws IOException {
        retainedEndpointAnalysis = null; retainedEndpointAssignmentDigest = null;
        cohortWork=0;cohortProposals=cohortAccepted=cohortSkipped=0;
        Files.deleteIfExists(outDir.resolve("cohort_refinement_passes.tsv"));
        swapProbes = swapFeasibilityRejects = swapCommits = swapWork = swapWorkSkips = 0;
        swapPhaseAccepted = false; retainedSwapPass = -1;
        int[] assignment = initial.clone();
        incidentPaths = null;
        CombinationalPathAnalyzer analyzer = new CombinationalPathAnalyzer(
                graph.ownerVertex, graph.sequential, graph.successors);
        CombinationalPathAnalyzer.Analysis nativeAudit = analyzer.analyze(initial, minimumLinkHops);
        initializeNetCache(assignment);
        long[][] load = loads(assignment);
        long[] logic = logicLoads(assignment);
        committedBalanceLoad = balanceLoads(assignment);
        // First repair scalar-weight rounding differences and empty real-logic blocks.
        for (int iteration = 0; iteration < assignment.length + k; iteration++) {
            int needy = -1;
            int over = -1;
            for (int p = 0; p < k; p++) {
                if (logic[p] == 0) { needy = p; break; }
                if (overloaded(p, load)) { over = p; }
            }
            if (needy < 0 && over < 0) { break; }
            int bestV = -1;
            int bestDest = -1;
            double bestCost = Double.POSITIVE_INFINITY;
            for (int v = 1; v < assignment.length; v++) {
                int source = assignment[v];
                if (fixed[v] >= 0 || logic[source] <= graph.realLogic[v]
                        || (needy < 0 && (source != over || !reducesOverflow(v, source, load)))) { continue; }
                for (int dest = 0; dest < k; dest++) {
                    if (dest == source || (needy >= 0 && dest != needy) || !fits(v, dest, load)
                            || (needy >= 0 && graph.realLogic[v] == 0)) { continue; }
                    double before = localCost(v, assignment);
                    setPlacement(v, dest, assignment);
                    double delta = localCost(v, assignment) - before;
                    setPlacement(v, source, assignment);
                    if (delta < bestCost) { bestCost = delta; bestV = v; bestDest = dest; }
                }
            }
            if (bestV < 0) { break; }
            move(bestV, bestDest, assignment, load, logic);
        }
        validate(assignment);
        int[] baseline = assignment.clone();
        CombinationalPathAnalyzer.Analysis before = analyzer.analyze(baseline, minimumLinkHops);
        TimingPathModel.Evaluation beforeTiming = timingEvaluation(baseline);
        CombinationalPathAnalyzer.Analysis guardAudit = qualityAnchorAssignment == null
                ? before : analyzer.analyze(qualityAnchorAssignment, minimumLinkHops);
        TimingPathModel.Evaluation guardTiming = qualityAnchorAssignment == null
                ? beforeTiming : timingEvaluation(qualityAnchorAssignment);
        if (qualityAnchorAssignment != null && !guardAudit.complete) {
            throw new IllegalArgumentException("Quality anchor cannot certify an incomplete current path model");
        }
        long baseTransfers = transfers(baseline);
        double baseTopologyCost = topologyCost(baseline);
        long transferReference = qualityAnchorAssignment != null ? qualityAnchorTransfers
                : selectionTransferReference == null ? baseTransfers : selectionTransferReference;
        double topologyReference = qualityAnchorAssignment != null ? qualityAnchorTopology
                : selectionTopologyReference == null ? baseTopologyCost : selectionTopologyReference;
        long maxTransfers = Math.addExact(transferReference, (long) Math.ceil(transferReference * cutBudget));
        double maxTopology = topologyReference + Math.ceil(topologyReference * cutBudget);
        if (!Double.isFinite(maxTopology)) throw new IllegalArgumentException("Quality budget overflows numerical range");
        refreshPaths(analyzer, baseline);
        int moves = 0;
        long currentTransfers = baseTransfers;
        double currentTopology = baseTopologyCost;
        boolean baselineGuarded = qualityAnchorAssignment == null || (qualityNonregression(before, beforeTiming, guardAudit, guardTiming)
                && baseTransfers <= maxTransfers && baseTopologyCost <= maxTopology + 1e-9);
        int[] bestAssignment = baselineGuarded ? baseline : null;
        CombinationalPathAnalyzer.Analysis bestAudit = before;
        TimingPathModel.Evaluation bestTiming = beforeTiming;
        long bestTransfers = baseTransfers;
        double bestTopology = baseTopologyCost;
        int retainedPass = -1;
        List<String> passReport = new ArrayList<>();
        passReport.add("pass\tmoves\ttransfers\ttopology\tmax_crossings\tendpoint_excess\ttransit\twithin_guards\tretained");
        if (before.complete) {
            for (int pass = 0; pass < passes; pass++) {
                if (pass > 0) { refreshPaths(analyzer, assignment); }
                int passMoves = 0;
                for (int v = 1; v < assignment.length; v++) {
                    int source = assignment[v];
                    if (fixed[v] >= 0 || logic[source] <= graph.realLogic[v]) { continue; }
                    double beforeCost = localCost(v, assignment);
                    double beforeNetCost = localNetCost(v);
                    int beforeTransfers = localTransfers(v, assignment);
                    double bestDelta = -1e-9;
                    int best = source;
                    int bestTransferDelta = 0;
                    double bestTopologyDelta = 0;
                    for (int dest = 0; dest < k; dest++) {
                        if (dest == source || !fits(v, dest, load)) { continue; }
                        setPlacement(v, dest, assignment);
                        double delta = localCost(v, assignment) - beforeCost;
                        int transferDelta = localTransfers(v, assignment) - beforeTransfers;
                        double topologyDelta = localNetCost(v) - beforeNetCost - transferDelta;
                        if (delta < bestDelta && currentTransfers + transferDelta <= maxTransfers
                                && currentTopology + topologyDelta <= maxTopology + 1e-9) {
                            bestDelta = delta; best = dest; bestTransferDelta = transferDelta;
                            bestTopologyDelta = topologyDelta;
                        }
                    }
                    setPlacement(v, source, assignment);
                    if (best != source) {
                        move(v, best, assignment, load, logic);
                        currentTransfers += bestTransferDelta;
                        currentTopology += bestTopologyDelta;
                        passMoves++;
                    }
                }
                moves += passMoves;
                CombinationalPathAnalyzer.Analysis audit = analyzer.analyze(assignment, minimumLinkHops);
                TimingPathModel.Evaluation timing = timingEvaluation(assignment);
                boolean constraintsSatisfied = true;
                try { validate(assignment); } catch (IllegalArgumentException e) { constraintsSatisfied = false; }
                boolean guarded = constraintsSatisfied && qualityNonregression(audit, timing, guardAudit, guardTiming)
                        && currentTransfers <= maxTransfers && currentTopology <= maxTopology + 1e-9;
                boolean retain = guarded && (bestAssignment == null
                        || compareQuality(audit, timing, currentTransfers, currentTopology,
                            bestAudit, bestTiming, bestTransfers, bestTopology) < 0);
                if (retain) {
                    bestAssignment = assignment.clone(); bestAudit = audit;
                    bestTiming = timing;
                    bestTransfers = currentTransfers; bestTopology = currentTopology; retainedPass = pass;
                }
                passReport.add(pass + "\t" + passMoves + "\t" + currentTransfers + "\t"
                        + currentTopology + "\t" + audit.maxCrossings + "\t" + audit.excessCrossings
                        + "\t" + audit.transitCount + "\t" + guarded + "\t" + retain);
                if (passMoves == 0) { break; }
            }
        }
        // Preserve legacy candidate/report semantics before the separate phase.
        CombinationalPathAnalyzer.Analysis greedyCandidateAudit = analyzer.analyze(assignment, minimumLinkHops);
        long greedyCandidateTransfers = transfers(assignment);
        double greedyCandidateTopology = topologyCost(assignment);
        List<String> swapReport = new ArrayList<>();
        swapReport.add("pass\tswaps\ttransfers\ttopology\tmax_minimum_link_hops\tmax_crossings\tendpoint_excess\ttransit\twithin_guards\tretained");
        if (bestAssignment != null && before.complete && passes > 0) {
            // The retained greedy result is an immutable fallback. Rebuild every
            // committed cache/load from it before probing paired exchanges.
            assignment = bestAssignment.clone();
            initializeNetCache(assignment); load = loads(assignment); logic = logicLoads(assignment);
            committedBalanceLoad = balanceLoads(assignment);
            currentTransfers = bestTransfers; currentTopology = bestTopology;
            for (int pass = 0; pass < passes; pass++) {
                refreshPaths(analyzer, assignment);
                SwapPass swapped = swapPass(assignment, load, logic, currentTransfers, currentTopology, maxTransfers, maxTopology);
                currentTransfers = swapped.transfers; currentTopology = swapped.topology;
                if (swapped.swaps == 0) break;
                CombinationalPathAnalyzer.Analysis audit = analyzer.analyze(assignment, minimumLinkHops);
                TimingPathModel.Evaluation timing = timingEvaluation(assignment);
                boolean constraintsSatisfied = true;
                try { validate(assignment); } catch (IllegalArgumentException e) { constraintsSatisfied = false; }
                boolean guarded = constraintsSatisfied && qualityNonregression(audit, timing, guardAudit, guardTiming)
                        && currentTransfers <= maxTransfers && currentTopology <= maxTopology + 1e-9;
                boolean retainedBestNonregression = qualityNonregression(audit, timing, bestAudit, bestTiming);
                boolean retain = guarded && retainedBestNonregression && compareQuality(audit, timing, currentTransfers, currentTopology,
                        bestAudit, bestTiming, bestTransfers, bestTopology) < 0;
                swapReport.add(pass + "\t" + swapped.swaps + "\t" + currentTransfers + "\t"
                        + currentTopology + "\t" + audit.maxMinimumLinkHops + "\t" + audit.maxCrossings + "\t" + audit.excessCrossings
                        + "\t" + audit.transitCount + "\t" + guarded + "\t" + retain);
                if (!retain) {
                    // A rejected swap trajectory cannot hide a good greedy pass.
                    assignment = bestAssignment.clone();
                    initializeNetCache(assignment); load = loads(assignment); logic = logicLoads(assignment);
                    committedBalanceLoad = balanceLoads(assignment);
                    currentTransfers = bestTransfers; currentTopology = bestTopology;
                    break;
                }
                bestAssignment = assignment.clone(); bestAudit = audit; bestTiming = timing;
                bestTransfers = currentTransfers; bestTopology = currentTopology;
                swapPhaseAccepted = true; retainedSwapPass = pass;
            }
        }
        if(cohortLimits!=null&&bestAssignment!=null&&passes>0) {
            List<String> cohortRows=new ArrayList<>();cohortRows.add("capture_node\tcone_nodes\tfrontier_owners\ttarget_home\tchanged_owners\ttransfers\ttopology\tH\tC\tdisposition");
            CohortResult cohort=cohortPhase(analyzer,new CohortResult(bestAssignment,bestAudit,bestTiming,bestTransfers,bestTopology),guardAudit,guardTiming,maxTransfers,maxTopology,cohortRows);
            bestAssignment=cohort.assignment;bestAudit=cohort.audit;bestTiming=cohort.timing;bestTransfers=cohort.transfers;bestTopology=cohort.topology;
            Files.write(outDir.resolve("cohort_refinement_passes.tsv"),cohortRows);
        }
        Files.write(outDir.resolve("swap_refinement_passes.tsv"), swapReport);
        CombinationalPathAnalyzer.Analysis after = analyzer.analyze(assignment, minimumLinkHops);
        CombinationalPathAnalyzer.Analysis candidate = greedyCandidateAudit;
        long candidateTransfers = greedyCandidateTransfers;
        double candidateTopologyCost = greedyCandidateTopology;
        if (bestAssignment == null) {
            Files.write(outDir.resolve("refinement_passes.tsv"), passReport);
            throw new IllegalArgumentException("No refinement candidate satisfies the accepted quality anchor; retain earlier native-accepted mapping");
        }
        boolean accepted = retainedPass >= 0 || swapPhaseAccepted || cohortAccepted>0;
        assignment = bestAssignment;
        after = bestAudit;
        validate(assignment);
        Files.write(outDir.resolve("refinement_passes.tsv"), passReport);
        List<String> report = new ArrayList<>();
        report.add("backend=" + (k == 1 ? "trivial_single_fpga_assignment" : "Mt-KaHyPar v1.6.2"));
        report.add("transfer_growth_reference=" + transferReference);
        report.add("topology_growth_reference=" + topologyReference);
        report.add("quality_guard_policy_version=1");
        if(cohortLimits!=null) {
            report.add("capture_fanin_cohort_scope=held_whole_frontier_atomic_coassignment_v1");
            report.add("cohort_proposals="+cohortProposals);report.add("cohort_accepted="+cohortAccepted);report.add("cohort_skips="+cohortSkipped);report.add("cohort_work_units="+cohortWork);
            report.add("cohort_limits="+cohortLimits.maxNodes+" nodes,"+cohortLimits.maxVertices+" owners,"+cohortLimits.maxEdges+" fanin entries,"+cohortLimits.maxCaptures+" captures,"+cohortLimits.maxTargets+" existing homes,"+cohortLimits.maxProposals+" proposals,"+cohortLimits.maxWork+" scan units");
            report.add("cohort_guard=aggregate_fixed_real_occupancy_6D_scalar_original_budgets_full_global_H_C_excess_transit_and_sampled_STA_componentwise_against_anchor_and_retained_best");
        }
        report.add("quality_guard_policy=" + qualityGuardPolicy);
        report.add("quality_guard_anchor_policy_metadata=" + qualityGuardAnchorPolicyMetadata);
        report.add("quality_guard_reference=" + (qualityAnchorAssignment == null ? "current_native_baseline" : "prior_native_accepted_assignment"));
        report.add("minimum_link_hop_analysis_available=" + (minimumLinkHops != null));
        report.add("minimum_link_hop_scope=" + (minimumLinkHops == null ? "unavailable_no_declared_adjacency"
                : "declared selected topology shortest links; lower bound, not actual routed transport"));
        report.add("quality_guard_max_minimum_link_hops=" + guardAudit.maxMinimumLinkHops);
        report.add("before_max_minimum_link_hops=" + before.maxMinimumLinkHops);
        report.add("after_max_minimum_link_hops=" + after.maxMinimumLinkHops);
        report.add("before_max_minimum_relay_traversals=" + before.maxMinimumRelayTraversals);
        report.add("after_max_minimum_relay_traversals=" + after.maxMinimumRelayTraversals);
        report.add("quality_guard_max_crossings=" + guardAudit.maxCrossings);
        report.add("quality_guard_endpoint_excess=" + guardAudit.excessCrossings);
        report.add("quality_guard_transit=" + guardAudit.transitCount);
        report.add("baseline_satisfies_quality_guard=" + baselineGuarded);
        if (guardTiming != null) {
            report.add("quality_guard_timing_cost=" + guardTiming.timingCost);
            if (guardTiming.modeledWorstSlackNs != null) {
                report.add("quality_guard_modeled_worst_slack_ns=" + guardTiming.modeledWorstSlackNs);
            }
        }
        report.add("path_model=primitive conservative data arcs; top IO has no FPGA home");
        report.add("path_model_scope=" + PrimitiveTimingPolicy.CONTROL_SCOPE);
        report.add("path_model_default_authority=" + PrimitiveTimingPolicy.DEFAULT_AUTHORITY);
        report.add("complete=" + after.complete);
        report.add("opaque_primitives=" + graph.opaquePrimitives);
        report.add("resource_coverage_complete=" + (graph.opaquePrimitives == 0));
        report.add("native_transfers=" + transfers(initial));
        report.add("native_max_crossings=" + nativeAudit.maxCrossings);
        report.add("native_endpoint_excess=" + nativeAudit.excessCrossings);
        report.add("native_transit_primitives=" + nativeAudit.transitCount);
        report.add("unanchored_input_nets=" + graph.omittedTopInputNets);
        report.add("cycle_nodes=" + after.cycleNodeCount);
        report.add("sampled_path_witnesses=" + paths.length);
        report.add("proposed_moves=" + moves);
        report.add("swap_refinement=bounded_post_greedy_exchange");
        report.add("swap_phase_accepted=" + swapPhaseAccepted);
        report.add("retained_swap_pass=" + retainedSwapPass);
        report.add("swap_cost_probes=" + swapProbes);
        report.add("swap_feasibility_rejects=" + swapFeasibilityRejects);
        report.add("proposed_swaps=" + swapCommits);
        report.add("swap_scan_units=" + swapWork);
        report.add("swap_work_budget_skips=" + swapWorkSkips);
        report.add("swap_limits=64 vertices,16 feasible partners,4096 probes,8000000 total scan units,65536 indices per neighborhood,4 swaps per pass");
        report.add("refinement_accepted=" + accepted);
        report.add("retained_pass=" + retainedPass);
        report.add("candidate_transfers=" + candidateTransfers);
        report.add("candidate_topology_distance=" + candidateTopologyCost);
        report.add("candidate_max_crossings=" + candidate.maxCrossings);
        report.add("candidate_endpoint_excess=" + candidate.excessCrossings);
        report.add("candidate_transit_primitives=" + candidate.transitCount);
        report.add("before_transfers=" + baseTransfers);
        report.add("after_transfers=" + transfers(assignment));
        report.add("before_topology_distance=" + baseTopologyCost);
        report.add("after_topology_distance=" + topologyCost(assignment));
        report.add("before_max_register_free_crossings=" + before.maxCrossings);
        report.add("after_max_register_free_crossings=" + after.maxCrossings);
        report.add("before_endpoint_excess_crossings=" + before.excessCrossings);
        report.add("after_endpoint_excess_crossings=" + after.excessCrossings);
        report.add("before_transit_primitives=" + before.transitCount);
        report.add("after_transit_primitives=" + after.transitCount);
        report.add("known_logical_capacities_and_fixed_constraints=PASS");
        if (timingModel != null) {
            TimingPathModel.Evaluation nativeTiming = timingEvaluation(initial);
            TimingPathModel.Evaluation afterTiming = timingEvaluation(assignment);
            report.add("timing_paths_count=" + timingModel.pathCount());
            report.add("timing_paths_scored=" + timingModel.scoredPathCount());
            report.add("timing_paths_unscored=" + timingModel.unscoredPathCount());
            report.add("timing_paths_unanchored=" + timingModel.unanchoredPathCount());
            report.add("timing_sample_coverage=exported sampled native setup paths only; not all-path STA");
            report.add("timing_scope=internal owner crossings only; no FPGA homes invented for top IO");
            report.add("timing_weight=" + timingWeight);
            report.add("timing_source_max_criticality=" + timingModel.maxSourceCriticality());
            report.add("timing_comparison_priority=" + (timingDelayNsPerCrossing == null
                    ? (minimumLinkHops == null ? "maximum_register_free_crossings_then_sampled_timing_cost"
                            : "maximum_minimum_link_hops_then_logical_crossings_then_sampled_timing_cost")
                    : "modeled_worst_sampled_slack_then_sampled_timing_cost"));
            report.add("timing_cost_normalization=fixed maximum source criticality; scale1 if all source criticalities zero");
            report.add("timing_cost_native=" + nativeTiming.timingCost);
            report.add("timing_cost_before=" + beforeTiming.timingCost);
            report.add("timing_cost_after=" + afterTiming.timingCost);
            if (Double.isFinite(afterTiming.sourceWorstSlackNs)) report.add("timing_source_worst_slack_ns=" + afterTiming.sourceWorstSlackNs);
            if (timingDelayNsPerCrossing != null) {
                report.add("timing_extra_delay_ns_per_crossing=" + timingDelayNsPerCrossing);
                report.add("modeled_worst_slack_before_ns=" + (beforeTiming.modeledWorstSlackNs == null ? "NA" : beforeTiming.modeledWorstSlackNs));
                report.add("modeled_worst_slack_after_ns=" + (afterTiming.modeledWorstSlackNs == null ? "NA" : afterTiming.modeledWorstSlackNs));
            }
            for (java.util.Map.Entry<String, String> entry : timingModel.metadata().entrySet()) {
                report.add("timing_export_metadata_" + entry.getKey() + "=" + entry.getValue());
            }
        }
        if (balanceWeights != null) {
            report.add("configured_scalar_balance=PASS");
            report.add("configured_scalar_capacities=" + Arrays.toString(balanceCaps));
            report.add("configured_scalar_loads=" + Arrays.toString(balanceLoads(assignment)));
        }
        Files.write(outDir.resolve("hardware_quality.txt"), report);
        List<String> witness = new ArrayList<>();
        witness.add("primitive\tpartition\tsequential");
        for (int node : after.worstPath) {
            witness.add(graph.primitiveNames[node] + "\t" + assignment[graph.ownerVertex[node]]
                    + "\t" + graph.sequential[node]);
        }
        Files.write(outDir.resolve("worst_combinational_path.tsv"), witness);
        retainedEndpointAssignmentDigest = EndpointKindDiagnosticReport.assignmentDigest(assignment);
        retainedEndpointAnalysis = after;
        return assignment;
    }
}
