/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Command-line tool to partition an EDIFNetlist
 */
public class Partitioner {

    private static final class RunContext {
        Path statusFile;
    }

    private static void writeStatus(RunContext context, String status) {
        if (context.statusFile == null) { return; }
        try {
            Files.write(context.statusFile, java.util.Collections.singletonList(status));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) {
        RunContext context = new RunContext();
        try {
            run(args, context);
        } catch (RuntimeException | Error failure) {
            try { writeStatus(context, "FAIL"); } catch (RuntimeException ignored) {
                System.err.println("Unable to write failure status: " + ignored.getMessage());
            }
            throw failure;
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) { digest.update(buffer, 0, count); }
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) { result.append(String.format("%02x", value & 255)); }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static int[] primaryBalanceWeights(HardwarePartitionGraph graph, int[] configured) {
        if (configured != null) return configured;
        int[] result = new int[graph.resources.length];
        for (int v = 1; v < result.length; v++) result[v] = Math.toIntExact(graph.resources[v][0]);
        return result;
    }

    private static void validateOptions(String[] args) {
        Set<String> switches = new java.util.HashSet<>(java.util.Arrays.asList(
                "constraints_debug", "skip_detailed_reports", "optimized", "verbose_mapping"));
        Set<String> valued = new java.util.HashSet<>(java.util.Arrays.asList(
                "seed", "epsilon", "threads", "partition_config", "objective", "part_dir",
                "mapping_constraints", "target_graph", "resource_capacities", "refine_passes",
                "starts", "snake_weight", "cut_budget", "mtkahypar_bin", "wsl", "mtkahypar_lib",
                "num_vcycles", "weight_model", "vertex_weight_file", "scalar_capacities", "logical_epsilon",
                "timing_paths", "timing_weight", "extra_delay_ns", "quality_anchor", "quality_guard", "capture_cohorts"));
        for (int i = 3; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) { throw new IllegalArgumentException("Unexpected argument: " + arg); }
            String[] fields = arg.substring(2).split("=", 2);
            if (switches.contains(fields[0])) {
                if (fields.length == 2 && !("true".equalsIgnoreCase(fields[1])
                        || "false".equalsIgnoreCase(fields[1]) || "1".equals(fields[1])
                        || "0".equals(fields[1]) || "yes".equalsIgnoreCase(fields[1])
                        || "no".equalsIgnoreCase(fields[1]))) {
                    throw new IllegalArgumentException("Invalid boolean option: " + arg);
                }
                continue;
            }
            if (!valued.contains(fields[0])) { throw new IllegalArgumentException("Unknown option: " + arg); }
            if (fields.length == 2) {
                if (fields[1].isEmpty()) { throw new IllegalArgumentException("Missing value: " + arg); }
            } else if (++i >= args.length || args[i].startsWith("--")) {
                throw new IllegalArgumentException("Missing value: " + arg);
            }
        }
    }

    /**
     * Logs current memory usage for debugging.
     * @param label Description label for the audit point
     */
    private static void memAudit(String label) {
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - free;
        PartitionTools.logPartitionerDebug(
                System.out,
                "%s -> used=%d MB total=%d MB free=%d MB",
                label, used / (1024 * 1024), total / (1024 * 1024), free / (1024 * 1024));
    }

    /**
     * Creates and returns the default partitioner instance.
     * @return A new MtKaHyParPartitioner instance
     */
    public static AbstractPartitioner getDefaultPartitioner() {
        MtKaHyParPartitioner p = new MtKaHyParPartitioner();
        return p;
    }

    /**
     * Prints command-line usage information to console.
     */
    private static void printUsage() {
        System.out.println("===================================================================");
        System.out.println("                       RAPIDWRIGHT PARTITIONER");
        System.out.println("===================================================================");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  <input.edf|input.dcp>");
        System.out.println("  <# of partitions>");
        System.out.println("  <leafLUTCountLimit|auto>");
        System.out.println();
        System.out.println("Optional Flags:");
        System.out.println("  --seed N                    Set seed");
        System.out.println("  --epsilon E                 Set partition imbalance");
        System.out.println("  --threads T                 Set number of threads");
        System.out.println("  --partition_config TYPE     Set preset (default/deterministic)");
        System.out.println("  --objective OBJ             Set objective (cut/km1/soed)");
        System.out.println("  --target_graph FILE         Weighted METIS FPGA topology; enables Steiner objective");
        System.out.println("  --resource_capacities FILE  Hard per-FPGA logical-resource capacities");
        System.out.println("  --refine_passes N           Directed path refinement passes (default 3; 0 audits baseline)");
        System.out.println("  --capture_cohorts MODE      Bounded whole-frontier capture cleanup: off (default), or explicit auto; pass0/k1 disable");
        System.out.println("  --starts N                  Native candidate seeds to audit (default 3)");
        System.out.println("  --weight_model FILE         Optional calibrated scalar REF_NAME weights; estimates only");
        System.out.println("  --vertex_weight_file FILE   Source/frontier-bound scalar weights from native feedback");
        System.out.println("  --quality_guard POLICY    ranked (default) or conservative componentwise structural/STA guard");
        System.out.println("  --quality_anchor FILE      Prior accepted assignment; preserve its quality and original cut budget");
        System.out.println("  --timing_paths FILE         Source-bound sampled Vivado STA paths for timing guidance");
        System.out.println("  --timing_weight W           Timing guidance weight (default 8; zero disables)");
        System.out.println("  --extra_delay_ns D          Explicit modeled transport delay per FPGA crossing");
        System.out.println("  --verbose_mapping          Emit individual frontier rules (default: compact hierarchy)");
        System.out.println("  --logical_epsilon E         Separate raw LUT imbalance when scalar weights are calibrated");
        System.out.println("  --snake_weight W            Excess crossing/reentry penalty (default 8)");
        System.out.println("  --cut_budget E              Allowed transfer growth fraction (default 0.10)");
        System.out.println("  --mtkahypar_bin PATH        v1.6.2 executable; version is checked");
        System.out.println("  --wsl DISTRO                Run the v1.6.2 backend in this WSL distribution");
        System.out.println("  --mtkahypar_lib PATH        WSL shared-library directory");
        System.out.println("  --num_vcycles N             Mt-KaHyPar improvement cycles (default 1)");
        System.out.println("  --part_dir DIR              Set output directory");
        System.out.println("  --mapping_constraints PATH  Specify constraints file");
        System.out.println("  --constraints_debug         Enable constraint debugging");
        System.out.println("  --skip_detailed_reports     Skip detailed report generation,");
        System.out.println("                              HIGHLY advised for large designs.");
        System.out.println();
        System.out.println("===================================================================");
    }
    
    /**
     * Main entry point for the partitioner tool.
     * @param args Command-line arguments for partitioning
     */
    private static void run(String[] args, RunContext context) {
        if (args.length < 3) {
            printUsage();
            return;
        }
        validateOptions(args);
        Path inputPath = Paths.get(args[0]);
        int k = Integer.parseInt(args[1]);
        boolean autoFrontier = "auto".equalsIgnoreCase(args[2]);
        int leafLUTCountLimit = autoFrontier ? 1 : Integer.parseInt(args[2]);
        if (k < 1 || leafLUTCountLimit < 1) {
            throw new IllegalArgumentException("k must be >=1 and leafLUTCountLimit must be >=1");
        }
        CodePerfTracker t = new CodePerfTracker("Partitioner");
        
        boolean generateEdifNets = false; // Human readable hypergraph artifacts
        Path constraintsFile = null;
        boolean constraintsDebug = false;
        boolean skipDetailedReports = false;
        boolean optimizeMapping = true;
        Path targetGraph = null;
        Path resourceCapacities = null;
        int refinementPasses = 3;
        String captureCohortMode = "off"; // Opt-in until actual source pilots demonstrate accepted cohort gains.
        int starts = 3;
        Path weightModelFile = null;
        Path vertexWeightFile = null;
        Path scalarCapacityFile = null;
        long[] scalarCapacities = null;
        Path timingPathFile = null;
        Path qualityAnchorFile = null;
        String qualityGuardPolicy = "ranked";
        double timingWeight = 8.0;
        Double extraDelayNs = null;
        Double logicalEpsilon = null;
        double snakeWeight = 8.0;
        double cutBudget = 0.10;



        // ============================================================================
        // ARGUMENT PARSING & INITIALIZATION
        // Parses command-line flags and sets up initial configuration variables.
        // Establishes the output directory where all partition artifacts will be written.
        // Determines whether to generate detailed reports based on the skip_detailed_reports flag.
        // ============================================================================
        Path outDir = (inputPath.getParent() == null)
                ? Paths.get(System.getProperty("user.dir"))
                : inputPath.getParent();
        // parse early flags that affect artifact emission and locations
        for (int i = 3; i < args.length; i++) {
            String arg = args[i];
            if ("--part_dir".equals(arg) && i + 1 < args.length) {
                outDir = Paths.get(args[++i]);
            } else if (arg.startsWith("--part_dir=")) {
                outDir = Paths.get(arg.substring("--part_dir=".length()));
            } else if ("--mapping_constraints".equals(arg) && i + 1 < args.length) {
                constraintsFile = Paths.get(args[++i]);
            } else if (arg.startsWith("--mapping_constraints=")) {
                constraintsFile = Paths.get(arg.substring("--mapping_constraints=".length()));
            } else if ("--constraints_debug".equals(arg)) {
                constraintsDebug = true;
            } else if (arg.startsWith("--constraints_debug=")) {
                //used for debugging constrained partition mode.
                String val = arg.substring("--constraints_debug=".length()).trim();
                constraintsDebug = "1".equals(val) || "true".equalsIgnoreCase(val) ||
                        "yes".equalsIgnoreCase(val);
            } else if ("--skip_detailed_reports".equals(arg)) { //human-readable partition outputs
                skipDetailedReports = true;
            } else if (arg.startsWith("--skip_detailed_reports=")) {
                String val = arg.substring("--skip_detailed_reports=".length()).trim();
                skipDetailedReports = "1".equals(val) || "true".equalsIgnoreCase(val) ||
                        "yes".equalsIgnoreCase(val);
            } else if ("--optimized".equals(arg)) {
                optimizeMapping = true;
            } else if ("--verbose_mapping".equals(arg)) {
                optimizeMapping = false;
            } else if (arg.startsWith("--verbose_mapping=")) {
                String value = arg.substring("--verbose_mapping=".length());
                optimizeMapping = !("true".equalsIgnoreCase(value) || "1".equals(value)
                        || "yes".equalsIgnoreCase(value));
            } else if (arg.startsWith("--optimized=")) {
                String value = arg.substring("--optimized=".length());
                optimizeMapping = "true".equalsIgnoreCase(value) || "1".equals(value)
                        || "yes".equalsIgnoreCase(value);
            } else if ("--target_graph".equals(arg) && i + 1 < args.length) {
                targetGraph = Paths.get(args[++i]);
            } else if (arg.startsWith("--target_graph=")) {
                targetGraph = Paths.get(arg.substring("--target_graph=".length()));
            } else if ("--resource_capacities".equals(arg) && i + 1 < args.length) {
                resourceCapacities = Paths.get(args[++i]);
            } else if (arg.startsWith("--resource_capacities=")) {
                resourceCapacities = Paths.get(arg.substring("--resource_capacities=".length()));
            } else if ("--capture_cohorts".equals(arg) && i + 1 < args.length) {
                captureCohortMode = CohortCleanupPolicy.validateMode(args[++i]);
            } else if (arg.startsWith("--capture_cohorts=")) {
                captureCohortMode = CohortCleanupPolicy.validateMode(arg.substring("--capture_cohorts=".length()));
            } else if ("--refine_passes".equals(arg) && i + 1 < args.length) {
                refinementPasses = Integer.parseInt(args[++i]);
            } else if (arg.startsWith("--refine_passes=")) {
                refinementPasses = Integer.parseInt(arg.substring("--refine_passes=".length()));
            } else if ("--starts".equals(arg) && i + 1 < args.length) {
                starts = Integer.parseInt(args[++i]);
            } else if (arg.startsWith("--starts=")) {
                starts = Integer.parseInt(arg.substring("--starts=".length()));
            } else if ("--weight_model".equals(arg) && i + 1 < args.length) {
                weightModelFile = Paths.get(args[++i]);
            } else if (arg.startsWith("--weight_model=")) {
                weightModelFile = Paths.get(arg.substring("--weight_model=".length()));
            } else if ("--vertex_weight_file".equals(arg) && i + 1 < args.length) {
                vertexWeightFile = Paths.get(args[++i]);
            } else if (arg.startsWith("--vertex_weight_file=")) {
                vertexWeightFile = Paths.get(arg.substring("--vertex_weight_file=".length()));
            } else if ("--scalar_capacities".equals(arg) && i + 1 < args.length) {
                scalarCapacityFile = Paths.get(args[++i]);
            } else if (arg.startsWith("--scalar_capacities=")) {
                scalarCapacityFile = Paths.get(arg.substring("--scalar_capacities=".length()));
            } else if ("--quality_guard".equals(arg) && i + 1 < args.length) {
                qualityGuardPolicy = HardwareMappingRefiner.validateQualityGuardPolicy(args[++i]);
            } else if (arg.startsWith("--quality_guard=")) {
                qualityGuardPolicy = HardwareMappingRefiner.validateQualityGuardPolicy(arg.substring("--quality_guard=".length()));
            } else if ("--quality_anchor".equals(arg) && i + 1 < args.length) {
                qualityAnchorFile = Paths.get(args[++i]);
            } else if (arg.startsWith("--quality_anchor=")) {
                qualityAnchorFile = Paths.get(arg.substring("--quality_anchor=".length()));
            } else if ("--timing_paths".equals(arg) && i + 1 < args.length) {
                timingPathFile = Paths.get(args[++i]);
            } else if (arg.startsWith("--timing_paths=")) {
                timingPathFile = Paths.get(arg.substring("--timing_paths=".length()));
            } else if ("--timing_weight".equals(arg) && i + 1 < args.length) {
                timingWeight = Double.parseDouble(args[++i]);
            } else if (arg.startsWith("--timing_weight=")) {
                timingWeight = Double.parseDouble(arg.substring("--timing_weight=".length()));
            } else if ("--extra_delay_ns".equals(arg) && i + 1 < args.length) {
                extraDelayNs = Double.parseDouble(args[++i]);
            } else if (arg.startsWith("--extra_delay_ns=")) {
                extraDelayNs = Double.parseDouble(arg.substring("--extra_delay_ns=".length()));
            } else if ("--logical_epsilon".equals(arg) && i + 1 < args.length) {
                logicalEpsilon = Double.parseDouble(args[++i]);
            } else if (arg.startsWith("--logical_epsilon=")) {
                logicalEpsilon = Double.parseDouble(arg.substring("--logical_epsilon=".length()));
            } else if ("--snake_weight".equals(arg) && i + 1 < args.length) {
                snakeWeight = Double.parseDouble(args[++i]);
            } else if (arg.startsWith("--snake_weight=")) {
                snakeWeight = Double.parseDouble(arg.substring("--snake_weight=".length()));
            } else if ("--cut_budget".equals(arg) && i + 1 < args.length) {
                cutBudget = Double.parseDouble(args[++i]);
            } else if (arg.startsWith("--cut_budget=")) {
                cutBudget = Double.parseDouble(arg.substring("--cut_budget=".length()));
            }
        }
        if (refinementPasses < 0 || starts < 1 || !Double.isFinite(snakeWeight) || snakeWeight < 0
                || !Double.isFinite(cutBudget) || cutBudget < 0
                || !Double.isFinite(timingWeight) || timingWeight < 0
                || (extraDelayNs != null && (!Double.isFinite(extraDelayNs) || extraDelayNs < 0))
                || (logicalEpsilon != null && (!Double.isFinite(logicalEpsilon) || logicalEpsilon < 0))) {
            throw new IllegalArgumentException("Refinement options must be finite and nonnegative");
        }
        if (weightModelFile != null && vertexWeightFile != null) {
            throw new IllegalArgumentException("Choose one scalar weight model");
        }
        if (extraDelayNs != null && (timingPathFile == null || timingWeight == 0)) {
            throw new IllegalArgumentException("Explicit transport delay requires enabled source timing paths");
        }
        Map<Path, String> dependencyHashes = new java.util.LinkedHashMap<>();
        for (Path dependency : new Path[] {targetGraph, resourceCapacities, constraintsFile,
                weightModelFile, vertexWeightFile, scalarCapacityFile, timingPathFile, qualityAnchorFile}) {
            if (dependency != null) {
                try { dependencyHashes.put(dependency, sha256(dependency)); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
        }
        if (scalarCapacityFile != null) {
            try {
                List<String> capacities = Files.readAllLines(scalarCapacityFile);
                List<Long> values = new ArrayList<>();
                for (String line : capacities) {
                    if (!line.trim().isEmpty() && !line.trim().startsWith("#")) values.add(Long.parseLong(line.trim()));
                }
                if (values.size() != k) throw new IllegalArgumentException("Scalar capacities must define all FPGAs");
                scalarCapacities = new long[k];
                for (int p = 0; p < k; p++) scalarCapacities[p] = values.get(p);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        generateEdifNets = !skipDetailedReports;
        try {
            Files.createDirectories(outDir);
            context.statusFile = outDir.resolve("RUN_STATUS.txt");
            writeStatus(context, "RUNNING");
            Files.deleteIfExists(outDir.resolve("cohort_refinement_passes.tsv"));
            Files.deleteIfExists(outDir.resolve(EndpointKindDiagnosticReport.FILE_NAME));
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }



        // ============================================================================
        // INPUT READING & VALIDATION
        // Reads the input design from either a DCP or EDIF file.
        // Validates that the design is not encrypted, which is unsupported.
        // Extracts the netlist for partitioning and tracks memory usage.
        // ============================================================================
        memAudit("rapidwright mem usg input before");
        t.start("Read Input");
        String loadedInputHash;
        try { loadedInputHash = sha256(inputPath); } catch (IOException e) { throw new UncheckedIOException(e); }
        EDIFNetlist netlist;
        String inLower = inputPath.toString().toLowerCase();
        if (inLower.endsWith(".dcp")) {
            Design design = Design.readCheckpoint(inputPath.toString());
            netlist = design.getNetlist();
            int encryptedCount = netlist.getEncryptedCells().size();
            if (encryptedCount > 0) {
                throw new RuntimeException("ERROR: Encrypted DCP detected (encryptedCells=" + 
                        encryptedCount + "). Encrypted DCPs are unsupported.");
            }
        } else {
            netlist = EDIFTools.readEdifFile(inputPath);
        }
        t.stop();
        memAudit("rapidwright mem usg input after");



        // ============================================================================
        // NETLIST COARSENING
        // Identifies leaf instances in the netlist hierarchy based on the LUT count threshold.
        // For DCP inputs, backfills missing LUT counts for hierarchical instances.
        // Validates that the leaf LUT count limit is within acceptable bounds.
        // ============================================================================
        t.start("Coarsen Netlist");
        if (autoFrontier) {
            long logicalLuts = new LogicalResourceCounts().count(netlist).logicLuts;
            leafLUTCountLimit = (int) Math.max(1, Math.min(Math.max(1, logicalLuts - 1),
                    Math.min(10000, Math.max(100, logicalLuts / (k * 64L)))));
            System.out.println("Automatic hierarchy frontier LUT limit: " + leafLUTCountLimit);
        }
        Map<EDIFHierCellInst, Integer> instLutCountMap = new HashMap<>();
        Map<EDIFHierCellInst, Integer> leafInsts = PartitionTools.identifyLeafInstances(netlist,
                leafLUTCountLimit, instLutCountMap);
        leafInsts = PartitionTools.alignFrontierToConstraints(netlist, leafInsts, constraintsFile);
        CohortCleanupPolicy.Prepared cohortCaller = CohortCleanupPolicy.prepare(captureCohortMode,k,refinementPasses,leafInsts);
        System.out.println("Identified " + leafInsts.size() + " leaves");
        if (k > leafInsts.size()) {
            throw new IllegalArgumentException("More FPGAs than movable vertices; lower leafLUTCountLimit");
        }
        {
            final int SAMPLE_TARGET = 100_000;
            final int STEP = Math.max(1, leafInsts.size() / SAMPLE_TARGET);
            long sampledHier = 0;
            long missingHier = 0;
            int idx = 0;
            for (EDIFHierCellInst ci : leafInsts.keySet()) {
                if ((idx++ % STEP) != 0) continue;
                if (!ci.getCellType().isLeafCellOrBlackBox()) {
                    sampledHier++;
                    if (!instLutCountMap.containsKey(ci)) {
                        missingHier++;
                    }
                }
            }
            double pct = sampledHier == 0 ? 0.0 : 100.0 * missingHier / sampledHier;
            /* 
             * Reports sampled coverage of missing lut counts among hierarchical 
             * leaves to confirm incomplete instance coverage
             */
            PartitionTools.logPartitionerDebug(
                    System.out,
                    "leafInsts coverage -> sampledHier=%d missingHier=%d pct=%.4f step=%d",
                    sampledHier, missingHier, pct, STEP); 
        }
        if (inLower.endsWith(".dcp")) {
            PartitionTools.logPartitionerDebug(
                    System.err,
                    "DCP backfill start -> leafCount=%d",
                    leafInsts.size());
            int backfillAttempts = 0;
            int backfillSuccess = 0;
            int backfillSkipped = 0;
            java.util.Map<com.xilinx.rapidwright.edif.EDIFCell, java.lang.Integer> lutCountCache;
            // Caches LUT counts to avoid expensive re-calculation during backfill process.
            lutCountCache = new java.util.HashMap<>();
            for (EDIFHierCellInst leaf : leafInsts.keySet()) {
                if (!leaf.getCellType().isLeafCellOrBlackBox()) {
                    if (instLutCountMap.get(leaf) == null) {
                        backfillAttempts++;
                        if (backfillAttempts <= 10) {
                            PartitionTools.logPartitionerDebug(
                                    System.err,
                                    "DCP backfill attempt -> instPath=%s cellType=%s",
                                    leaf.toString(), leaf.getCellType().getName());
                        }
                        PartitionTools.getLUTCount(leaf, lutCountCache, instLutCountMap);
                        if (instLutCountMap.get(leaf) != null) {
                            backfillSuccess++;
                            if (backfillSuccess <= 10) {
                                PartitionTools.logPartitionerDebug(
                                        System.err,
                                        "DCP backfill success -> instPath=%s lutCount=%d",
                                        leaf.toString(), instLutCountMap.get(leaf));
                            }
                        }
                    } else {
                        backfillSkipped++;
                    }
                }
            }
            PartitionTools.logPartitionerDebug(
                    System.err,
                    "DCP backfill complete -> attempts=%d success=%d skipped=%d",
                    backfillAttempts, backfillSuccess, backfillSkipped);
        }
        int totalLUTs = instLutCountMap.get(netlist.getTopHierCellInst());
        if ((totalLUTs > 1 && leafLUTCountLimit >= totalLUTs) || leafLUTCountLimit < 1) {
            throw new RuntimeException("ERROR: Invalid leafLUTCountLimit '" + leafLUTCountLimit
                    + "', must be less than total LUT count in netlist or 1 or greater.");
        }
        t.stop();



        // ============================================================================
        // EDGE DISCOVERY
        // Builds a map of nets (edges) to the instances (vertices) they connect.
        // Uses parent-level nets to avoid duplication and handle hierarchical connectivity.
        // Creates the hypergraph structure that will be partitioned.
        // ============================================================================
        t.start("Find Edges");
        HardwarePartitionGraph hardwareGraph = HardwarePartitionGraph.build(netlist, leafInsts);
        Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap = hardwareGraph.hyperedges;
        String[] instLookup = PartitionTools.createInstLookupArray(leafInsts);
        int[] calibratedWeights = null;
        if (vertexWeightFile != null) {
            try { calibratedWeights = FrontierWeightModel.read(vertexWeightFile, loadedInputHash, instLookup); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        } else if (weightModelFile != null) {
            try {
                VertexWeightModel weightModel = VertexWeightModel.read(weightModelFile, loadedInputHash);
                calibratedWeights = new int[leafInsts.size() + 1];
                for (Entry<EDIFHierCellInst, Integer> entry : leafInsts.entrySet()) {
                    calibratedWeights[entry.getValue()] = weightModel.weight(entry.getKey().getCellType());
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        } else if (totalLUTs == 0) {
            calibratedWeights = new int[leafInsts.size() + 1];
            java.util.Arrays.fill(calibratedWeights, 1);
            calibratedWeights[0] = 0;
        }
        if (scalarCapacities != null && calibratedWeights == null) {
            calibratedWeights = new int[leafInsts.size() + 1];
            for (int v = 1; v < calibratedWeights.length; v++) calibratedWeights[v] = Math.toIntExact(hardwareGraph.resources[v][0]);
        }
        int[] enforcedPrimaryWeights = primaryBalanceWeights(hardwareGraph, calibratedWeights);
        TimingPathModel timingModel = null;
        int[] timingEdgeWeights = null;
        if (timingPathFile != null && timingWeight > 0) {
            try { timingModel = TimingPathModel.read(timingPathFile, loadedInputHash, instLookup,
                    netlist.getTopCell().getName()); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            if (timingModel.scoredPathCount() == 0) throw new IllegalArgumentException("Timing guidance has no finite constrained source paths");
            Map<Long, Double> criticalPairs = timingModel.directedOwnerPairCriticalities();
            double criticalityScale = timingModel.maxSourceCriticality();
            if (hardwareGraph.nets.size() != edgesMap.size()) throw new IllegalStateException("Directed/hyperedge order mismatch");
            timingEdgeWeights = new int[edgesMap.size()];
            int edge = 0;
            for (HardwarePartitionGraph.Net net : hardwareGraph.nets) {
                double importance = 0;
                for (int sink : net.sinks) {
                    long pair = ((long) net.driver << 32) | (sink & 0xffffffffL);
                    importance = Math.max(importance, criticalPairs.getOrDefault(pair, 0.0));
                }
                double normalized = criticalityScale == 0 ? 0 : importance / criticalityScale;
                timingEdgeWeights[edge++] = 1 + (int) Math.min(1000000, Math.round(timingWeight * normalized));
            }
        }
        List<String> frontier = new ArrayList<>();
        frontier.add("vertex\tpath\tlogical_luts\tprimary_weight");
        for (int v = 1; v < instLookup.length; v++) {
            frontier.add(v + "\t" + instLookup[v] + "\t" + hardwareGraph.resources[v][0]
                    + "\t" + (calibratedWeights == null ? hardwareGraph.resources[v][0] : calibratedWeights[v]));
        }
        try { Files.write(outDir.resolve("frontier.tsv"), frontier); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        t.stop();



        // ============================================================================
        // HYPERGRAPH FILE GENERATION
        // Writes the hypergraph to a .hgr file in hMETIS format for the partitioner.
        // Optionally generates a .eidmap file mapping edge IDs to net names.
        // Frees the edge map memory to reduce memory footprint before partitioning.
        // ============================================================================
        t.start("Write hMETIS File");
        Path hMetisFile = outDir.resolve(inputPath.getFileName().toString() + ".hgr");
        PartitionTools.writeHMetisFile(hMetisFile, edgesMap, leafInsts, generateEdifNets,
                instLutCountMap, calibratedWeights, timingEdgeWeights);
        t.stop();

        //free edgesMap memory after .hgr file is written
        edgesMap.clear();
        edgesMap = null;
        System.gc();



        // ============================================================================
        // FIXED VERTICES COMPUTATION
        // Processes user-provided mapping constraints to fix certain instances to
        // specific partitions. Matches constraint paths against leaf instances and
        // assigns partition IDs. Generates a .fix file that tells the partitioner
        // which vertices are constrained.
        // ============================================================================
        Path fixedVertexFile = null; // generate fixed vertices file if constraints were provided
        if (constraintsFile != null) {
            // build label->index map
            java.util.Map<String, Integer> labelToIndex = new java.util.HashMap<>();
            for (int idx = 0; idx < k; idx++) {
                String lbl = PartitionLabel.indexToFpgaLabel(idx);
                labelToIndex.put(lbl, Integer.valueOf(idx));
            }
            // init fix array (1-based vertex ids)
            int numVertices = leafInsts.size();
            int[] vertexPartitionAssignments = new int[numVertices + 1];
            for (int i = 0; i <= numVertices; i++) vertexPartitionAssignments[i] = -1;
            int numFixedVertices = 0;

            // Parse hierarchy constraints before expanding them to vertices.  This
            // allows a broad module assignment to define the default placement
            // while a deeper child path overrides it.  For example:
            //   soc FPGA_C
            //   soc/simpleuart FPGA_B
            // puts the entire SoC on FPGA_C except the UART subtree.
            //
            // This is essential for hierarchical partitioning: a parent default
            // and a child exception are not contradictory.  Only equally-specific
            // matching constraints with different destinations are an error.
            Map<String, Integer> constraintByPath = new HashMap<>();
            try (BufferedReader cr = new BufferedReader(new FileReader(constraintsFile.toFile()))) {
                String cline;
                while ((cline = cr.readLine()) != null) {
                    String orig = cline.trim();
                    if (orig.isEmpty() || orig.startsWith("#")) continue; //comments and blankline
                    String[] toks = orig.split("\\s+");
                    if (toks.length != 2) {
                        throw new RuntimeException(
                                "constraint '" + orig + "' was not found valid in design");
                    }
                    String path = PartitionTools.normalizeConstraintPath(netlist, toks[0]);
                    String label = toks[1];
                    Integer blockIndex = labelToIndex.get(label);
                    if (blockIndex == null) {
                        throw new RuntimeException(
                                "constraint '" + orig + "' was not found valid in design");
                    }
                    Integer prior = constraintByPath.put(path, blockIndex);
                    if (prior != null && prior.intValue() != blockIndex.intValue()) {
                        throw new RuntimeException(
                                "conflicting constraints for hierarchy path '" + path + "'");
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            HierarchyConstraintIndex constraintIndex =
                    HierarchyConstraintIndex.assignments(constraintByPath);
            for (Entry<EDIFHierCellInst, Integer> e : leafInsts.entrySet()) {
                String name = e.getKey().toString();
                int vertexId = e.getValue().intValue();
                HierarchyConstraintIndex.Choice choice = constraintIndex.selectRelatedAndCount(name);
                if (choice.partition != null) {
                    vertexPartitionAssignments[vertexId] = choice.partition.intValue();
                    numFixedVertices++;
                }
            }
            Map<String, Integer> matchedByConstraint = constraintIndex.matchedCounts();

            for (Entry<String, Integer> match : matchedByConstraint.entrySet()) {
                if (constraintsDebug) {
                    System.out.printf(
                            "partitioner debug: constraint '%s' matched %d vertices%n",
                            match.getKey(), match.getValue());
                }
                if (match.getValue().intValue() == 0) {
                    throw new RuntimeException(
                            "constraint hierarchy path was not found valid in design: '" +
                            match.getKey() + "'");
                }
            }

            // derive fix file path from .hgr
            // will show constrained modules to MtKahyPar,
            // -1 values for unconstrained vertices
            String hypergraphBasePath = hMetisFile.toString();
            if (hypergraphBasePath.endsWith(".hgr")) {
                fixedVertexFile = Paths.get(
                        hypergraphBasePath.substring(0, hypergraphBasePath.length() - 4) + ".fix");
            } else {
                fixedVertexFile = Paths.get(hypergraphBasePath + ".fix");
            }
            try (BufferedWriter fw = new BufferedWriter(new FileWriter(fixedVertexFile.toFile()))) {
                for (int vertex_id = 1; vertex_id <= numVertices; vertex_id++) {
                    fw.write(Integer.toString(vertexPartitionAssignments[vertex_id]));
                    fw.write("\n");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            System.out.printf("partitioner debug: fix-file=%s vertices=%d fixed=%d%n",
                    fixedVertexFile, leafInsts.size(), numFixedVertices);
        }
        



        // ============================================================================
        // PARTITIONER EXECUTION
        // Configures the external MtKaHyPar partitioning tool with user-specified parameters.
        // Sets options like seed, epsilon, threads, preset type, and objective function.
        // Executes the partitioner and tracks memory usage before and after.
        // ============================================================================
        t.start("Run Partitioner");
        AbstractPartitioner partitioner = getDefaultPartitioner();
        partitioner.setInputFile(hMetisFile);
        partitioner.setKPartitions(k);
        // optional args
        if (partitioner instanceof MtKaHyParPartitioner) {
            MtKaHyParPartitioner mtPartitioner = (MtKaHyParPartitioner) partitioner;
            // ensure external tool runs and writes outputs into outDir
            mtPartitioner.setOutputDir(outDir);
            // pass fixed vertices file if detected
            if (fixedVertexFile != null) {
                mtPartitioner.setFixedVerticesFile(fixedVertexFile);
            }
            for (int i = 3; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--seed") && i + 1 < args.length) {
                    mtPartitioner.setSeed(Integer.parseInt(args[++i]));
                } else if (arg.startsWith("--seed=")) {
                    mtPartitioner.setSeed(Integer.parseInt(arg.substring("--seed=".length())));
                } else if (arg.equals("--epsilon") && i + 1 < args.length) {
                    mtPartitioner.setEpsilon(Double.parseDouble(args[++i]));
                } else if (arg.startsWith("--epsilon=")) {
                    mtPartitioner.setEpsilon(Double.parseDouble(
                            arg.substring("--epsilon=".length())));
                } else if (arg.equals("--threads") && i + 1 < args.length) {
                    mtPartitioner.setNumThreads(Integer.parseInt(args[++i]));
                } else if (arg.startsWith("--threads=")) {
                    mtPartitioner.setNumThreads(Integer.parseInt(
                            arg.substring("--threads=".length())));
                } else if (arg.equals("--partition_config") && i + 1 < args.length) {
                    mtPartitioner.setPresetType(args[++i]);
                } else if (arg.startsWith("--partition_config=")) {
                    mtPartitioner.setPresetType(arg.substring("--partition_config=".length()));
                } else if (arg.equals("--objective") && i + 1 < args.length) {
                    mtPartitioner.setObjective(args[++i]);
                } else if (arg.startsWith("--objective=")) {
                    mtPartitioner.setObjective(arg.substring("--objective=".length()));
                } else if (arg.equals("--mtkahypar_bin") && i + 1 < args.length) {
                    mtPartitioner.setExecutable(args[++i]);
                } else if (arg.startsWith("--mtkahypar_bin=")) {
                    mtPartitioner.setExecutable(arg.substring("--mtkahypar_bin=".length()));
                } else if (arg.equals("--wsl") && i + 1 < args.length) {
                    mtPartitioner.setWslDistribution(args[++i]);
                } else if (arg.startsWith("--wsl=")) {
                    mtPartitioner.setWslDistribution(arg.substring("--wsl=".length()));
                } else if (arg.equals("--mtkahypar_lib") && i + 1 < args.length) {
                    mtPartitioner.setLibraryPath(args[++i]);
                } else if (arg.startsWith("--mtkahypar_lib=")) {
                    mtPartitioner.setLibraryPath(arg.substring("--mtkahypar_lib=".length()));
                } else if (arg.equals("--num_vcycles") && i + 1 < args.length) {
                    mtPartitioner.setVcycles(Integer.parseInt(args[++i]));
                } else if (arg.startsWith("--num_vcycles=")) {
                    mtPartitioner.setVcycles(Integer.parseInt(arg.substring("--num_vcycles=".length())));
                }
            }
            if (targetGraph != null) {
                if (k > 64) {
                    throw new IllegalArgumentException("Mt-KaHyPar v1.6.2 Steiner mapping supports <=64 FPGAs");
                }
                mtPartitioner.setTargetGraph(targetGraph);
            }
            // print user flags summary
            System.out.printf("Partitioner flag: objective=%s%n", mtPartitioner.getObjective());
            System.out.printf("Partitioner flag: part_dir=%s%n", outDir);
            System.out.printf("Partitioner flag: mapping_constraints=%s%n",
                  constraintsFile != null ? constraintsFile.toString() : "none");
            System.out.printf("Partitioner flag: skip_detailed_reports=%s%n",
                    skipDetailedReports ? "on" : "off");
        }
        memAudit("rapidwright mem usg before partitioner run");
        ((MtKaHyParPartitioner) partitioner).setIndividualPartWeights(scalarCapacities);
        if (k > 1) partitioner.runPartitioner();
        else System.out.println("Single FPGA: assigning all logic directly; native partition solver is unnecessary");
        memAudit("rapidwright mem usg after partitioner run");
        t.stop();
        



        // ============================================================================
        // SOLUTION PROCESSING - BARE BONES MODE
        // Reads the partition solution and computes per-partition LUT counts
        // efficiently. Generates minimal output artifacts: io_cuts.txt,
        // lut_report.txt, cells.txt, and mapping.txt. Skips detailed reports like
        // .eidmap and .nets.txt to reduce artifact size.
        // ============================================================================
        t.start("Read Partition Solution");
        Path outputFile = k == 1 ? null : partitioner.getOutputFile();
        try {
            int[] nativeAssignment;
            if (k == 1) {
                nativeAssignment = new int[leafInsts.size() + 1];
                nativeAssignment[0] = -1;
            } else nativeAssignment = HardwareMappingRefiner.readSolution(outputFile, leafInsts.size(), k);
            int[] fixed = HardwareMappingRefiner.readFixed(fixedVertexFile, leafInsts.size(), k);
            double epsilon = ((MtKaHyParPartitioner) partitioner).getEpsilon();
            PartitionTopology topology = targetGraph == null ? PartitionTopology.complete(k)
                    : PartitionTopology.read(targetGraph, k);
            HardwareMappingRefiner refiner = new HardwareMappingRefiner(hardwareGraph,
                    topology, k, logicalEpsilon == null ? epsilon : logicalEpsilon,
                    fixed, snakeWeight, cutBudget, resourceCapacities);
            refiner.configureQualityGuard(qualityGuardPolicy);
            if (scalarCapacities != null) refiner.configureBalanceWeights(enforcedPrimaryWeights, scalarCapacities);
            else refiner.configureBalanceWeights(enforcedPrimaryWeights, epsilon);
            if (timingModel != null) refiner.configureTimingPaths(timingModel, timingWeight, extraDelayNs);
            if (cohortCaller.enabled) refiner.configureCaptureCohorts(CohortCleanupPolicy.limits(hardwareGraph,refinementPasses),cohortCaller.protectedOwners(fixed));
            if (qualityAnchorFile != null) {
                AcceptedQualityAnchor anchor = AcceptedQualityAnchor.read(qualityAnchorFile,
                        loadedInputHash, instLookup, k);
                anchor.validateQualityGuardPolicy(qualityGuardPolicy);
                refiner.configureQualityAnchor(anchor.assignment(), anchor.transferGrowthReference,
                        anchor.topologyGrowthReference, anchor.metadata().get("quality_guard_policy"));
            }
            List<int[]> candidates = new ArrayList<>();
            candidates.add(nativeAssignment);
            MtKaHyParPartitioner mt = (MtKaHyParPartitioner) partitioner;
            int firstSeed = mt.getSeed();
            for (int attempt = 1; k > 1 && attempt < starts; attempt++) {
                mt.setSeed(firstSeed + attempt);
                mt.runPartitioner();
                candidates.add(HardwareMappingRefiner.readSolution(mt.getOutputFile(), leafInsts.size(), k));
            }
            nativeAssignment = refiner.selectCandidate(candidates);
            List<String> candidateReport = new ArrayList<>();
            candidateReport.add("seed\ttransfers\ttopology_distance\tmax_crossings\tendpoint_excess\ttransit_primitives\tcomplete\tselected\ttiming_cost\tmodeled_worst_slack_ns\tminimum_link_hop_analysis_available\tmax_minimum_link_hops\tmax_minimum_relay_traversals");
            CombinationalPathAnalyzer candidateAnalyzer = new CombinationalPathAnalyzer(
                    hardwareGraph.ownerVertex, hardwareGraph.sequential, hardwareGraph.successors);
            int[][] candidateHopMatrix = topology.hasDeclaredPhysicalAdjacency() ? topology.minimumHopMatrix() : null;
            for (int attempt = 0; attempt < candidates.size(); attempt++) {
                CombinationalPathAnalyzer.Analysis audit = candidateAnalyzer.analyze(candidates.get(attempt), candidateHopMatrix);
                TimingPathModel.Evaluation timing = refiner.timingEvaluation(candidates.get(attempt));
                candidateReport.add((firstSeed + attempt) + "\t" + refiner.transfers(candidates.get(attempt))
                        + "\t" + refiner.topologyCost(candidates.get(attempt)) + "\t" + audit.maxCrossings
                        + "\t" + audit.excessCrossings + "\t" + audit.transitCount + "\t" + audit.complete
                        + "\t" + (candidates.get(attempt) == nativeAssignment)
                        + "\t" + (timing == null ? "NA" : Double.toString(timing.timingCost))
                        + "\t" + (timing == null || timing.modeledWorstSlackNs == null ? "NA" : timing.modeledWorstSlackNs.toString())
                        + "\t" + audit.minimumLinkHopAnalysisAvailable + "\t" + audit.maxMinimumLinkHops
                        + "\t" + audit.maxMinimumRelayTraversals);
            }
            Files.write(outDir.resolve("native_candidates.tsv"), candidateReport);
            List<String> selectedNative = new ArrayList<>();
            for (int v = 1; v < nativeAssignment.length; v++) {
                selectedNative.add(Integer.toString(nativeAssignment[v]));
            }
            Files.write(outDir.resolve("initial_assignment.part"), selectedNative);
            int[] finalAssignment = refiner.refine(nativeAssignment, refinementPasses, outDir);
            List<String> finalLines = new ArrayList<>();
            Map<Integer, Set<String>> partitions = new java.util.TreeMap<>();
            Map<String, Integer> nameToPartMap = new java.util.LinkedHashMap<>();
            for (int p = 0; p < k; p++) { partitions.put(p, new java.util.TreeSet<>()); }
            for (int v = 1; v < finalAssignment.length; v++) {
                finalLines.add(Integer.toString(finalAssignment[v]));
                partitions.get(finalAssignment[v]).add(instLookup[v]);
                nameToPartMap.put(instLookup[v], finalAssignment[v]);
            }
            Files.write(outDir.resolve("final_assignment.part"), finalLines);
            long[][] load = new long[k][6];
            long[] usefulLogicLoad = new long[k];
            for (int v = 1; v < finalAssignment.length; v++) {
                for (int r = 0; r < 6; r++) {
                    load[finalAssignment[v]][r] += hardwareGraph.resources[v][r];
                }
                usefulLogicLoad[finalAssignment[v]] = Math.addExact(
                        usefulLogicLoad[finalAssignment[v]], hardwareGraph.realLogic[v]);
            }
            for (int p = 0; p < k; p++) {
                if (usefulLogicLoad[p] <= 0) {
                    throw new IllegalStateException("Final partition has no useful design logic: "
                            + PartitionLabel.indexToFpgaLabel(p));
                }
            }
            List<String> lutReport = new ArrayList<>();
            List<String> resourceReport = new ArrayList<>();
            resourceReport.add("partition\tlogical_lut_primitives\tff_and_latches\tbram18_equivalent\tdsp\turam\tlutram_srl_primitives");
            long sum = 0;
            for (int p = 0; p < k; p++) {
                String label = PartitionLabel.indexToFpgaLabel(p);
                lutReport.add(load[p][0] + "LUTS " + label);
                resourceReport.add(label + "\t" + load[p][0] + "\t" + load[p][1] + "\t"
                        + load[p][2] + "\t" + load[p][3] + "\t" + load[p][4] + "\t" + load[p][5]);
                sum += load[p][0];
                if (!skipDetailedReports) {
                    Files.write(outDir.resolve(inputPath.getFileName() + "." + label), partitions.get(p));
                }
            }
            if (sum != totalLUTs) { throw new IllegalStateException("Logical LUT accounting mismatch"); }
            lutReport.add((long) Math.ceil(sum / (double) k) + "LUTS mean");
            Files.write(outDir.resolve("lut_report.txt"), lutReport);
            Files.write(outDir.resolve("logical_lut_report.txt"), lutReport);
            Files.write(outDir.resolve("logical_resources.tsv"), resourceReport);
            LogicalResourceCounts.Counts inventory = new LogicalResourceCounts().count(netlist);
            List<String> histogram = new ArrayList<>();
            histogram.add("REF_NAME\tCOUNT");
            for (Map.Entry<String, Long> entry : inventory.primitiveHistogram().entrySet()) {
                histogram.add(entry.getKey() + "\t" + entry.getValue());
            }
            Files.write(outDir.resolve("primitive_counts.tsv"), histogram);
            HierMappingWriter.write(outDir, netlist, partitions, instLutCountMap, optimizeMapping);
            Integer topIoPartition = null;
            String topName = netlist.getTopCell().getName();
            for (String line : Files.readAllLines(outDir.resolve("mapping.txt"))) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length == 2 && fields[0].equals(topName)) {
                    for (int p = 0; p < k; p++) {
                        if (fields[1].equals(PartitionLabel.indexToFpgaLabel(p))) { topIoPartition = p; }
                    }
                }
            }
            FpgaIoCutAnalyzer.writeReport(outDir,
                    FpgaIoCutAnalyzer.analyze(netlist, leafInsts, nameToPartMap, topIoPartition));
            PartitionPairCounter.writePairCountsToFile(outDir.resolve("io_cuts.txt"),
                    PartitionPairCounter.countPartitionPairs(hMetisFile, instLookup, nameToPartMap));
            Files.write(outDir.resolve("metric_definitions.txt"), java.util.Arrays.asList(
                    "lut_report.txt and logical_resources.tsv count exact logical primitive instances.",
                    "They are NOT packed CLB LUT utilization: use tools/report_partition_resources.tcl on the original checkpoint and FINAL mapping.",
                    "fpga_io_cuts.txt counts canonical directed logical net destinations; clock/reset-only nets are classified separately.",
                    "io_cuts.txt is undirected hyperedge partition pairs, NOT package ports or physical links.",
                    "No wrapping, replication, transport insertion or physical link compression is modeled.",
                    "Topology costs are weighted shortest paths/Steiner routing costs, not measured link bandwidth or achieved DUT clock.",
                    "Path analysis is conservative primitive connectivity, not Vivado timing slack or a proof of Boolean sensitization."));
            org.json.JSONObject provenance = new org.json.JSONObject();
            provenance.put("backend_version", k == 1 ? org.json.JSONObject.NULL : "1.6.2");
            provenance.put("native_backend_invoked", k > 1);
            provenance.put("source_input", inputPath.toAbsolutePath().normalize().toString());
            if (!loadedInputHash.equals(sha256(inputPath))) {
                throw new IllegalStateException("Input changed during partitioning; discard this run and use a stable checkpoint/export");
            }
            org.json.JSONObject dependencies = new org.json.JSONObject();
            for (Entry<Path, String> dependency : dependencyHashes.entrySet()) {
                if (!dependency.getValue().equals(sha256(dependency.getKey()))) {
                    throw new IllegalStateException("Partition dependency changed during run: " + dependency.getKey());
                }
                dependencies.put(dependency.getKey().toAbsolutePath().normalize().toString(), dependency.getValue());
            }
            provenance.put("dependency_sha256", dependencies);
            provenance.put("input_sha256", loadedInputHash);
            provenance.put("hypergraph_sha256", sha256(hMetisFile));
            provenance.put("mapping_sha256", sha256(outDir.resolve("mapping.txt")));
            provenance.put("partitions", k);
            org.json.JSONObject usefulLogicByPartition = new org.json.JSONObject();
            for (int p = 0; p < k; p++) {
                usefulLogicByPartition.put(PartitionLabel.indexToFpgaLabel(p), usefulLogicLoad[p]);
            }
            provenance.put("useful_logic_occupancy_version", 1);
            provenance.put("useful_logic_by_partition", usefulLogicByPartition);
            provenance.put("useful_logic_occupancy_pass", true);
            provenance.put("useful_logic_occupancy_status", "PASS");
            provenance.put("useful_logic_definition", "Sum of HardwarePartitionGraph.realLogic over final frontier assignments; "
                    + "excludes trusted constant/clock/I/O infrastructure and synthetic capture endpoints; "
                    + "not LUT count or packed area");
            provenance.put("leaf_lut_limit", leafLUTCountLimit);
            provenance.put("starts", starts);
            provenance.put("quality_guard_policy_version", 1);
            provenance.put("quality_guard_policy", refiner.qualityGuardPolicy());
            provenance.put("capture_cohort_mode_requested",captureCohortMode);
            provenance.put("capture_cohort_caller_enabled",cohortCaller.enabled);
            provenance.put("capture_cohort_caller_reason",cohortCaller.reason);
            provenance.put("capture_cohort_protection_scope",CohortCleanupPolicy.SCOPE);
            provenance.put("capture_cohort_source_protected_owners",cohortCaller.sourceProtectedCount);
            provenance.put("quality_guard_anchor_policy_metadata", refiner.qualityGuardAnchorPolicyMetadata());
            provenance.put("quality_guard_reference", qualityAnchorFile == null
                    ? "current_native_baseline" : "prior_native_accepted_assignment");
            provenance.put("minimum_link_hop_analysis_version", 1);
            provenance.put("minimum_link_hop_analysis_available", topology.hasDeclaredPhysicalAdjacency());
            provenance.put("minimum_link_hop_scope", "Lower bounds on declared selected adjacency; not actual transport routing, congestion or nanoseconds");
            if (qualityAnchorFile != null) provenance.put("quality_anchor_sha256", sha256(qualityAnchorFile));
            provenance.put("timing_guidance", timingModel == null ? "structural_only" : "sampled_source_STA_paths");
            if (timingModel != null) {
                provenance.put("timing_paths_sha256", sha256(timingPathFile));
                provenance.put("timing_weight", timingWeight);
                provenance.put("extra_delay_ns_per_crossing", extraDelayNs == null ? org.json.JSONObject.NULL : extraDelayNs);
                provenance.put("timing_edge_weights", "1+round(weight*max source owner-pair criticality/max sampled source criticality), increment capped at 1000000");
                provenance.put("timing_net_identity_scope", "Cell-pair guidance applies to all parallel matching hyperedges; not exact pin-arc identification");
            }
            provenance.put("args", new org.json.JSONArray(args));
            provenance.put("known_logical_resource_constraints_pass", true);
            provenance.put("resource_coverage_complete", hardwareGraph.opaquePrimitives == 0);
            provenance.put("primary_weight_scheme", vertexWeightFile != null ? "native_feedback_proxy" : weightModelFile != null ? "calibrated_proxy"
                    : (totalLUTs == 0 ? "unit_fallback" : "logical_lut_primitives"));
            if (weightModelFile != null) {
                provenance.put("scalar_weight_model", weightModelFile.toAbsolutePath().toString());
                provenance.put("scalar_weight_model_sha256", sha256(weightModelFile));
                provenance.put("scalar_weight_model_is_physical_authority", false);
            }
            if (vertexWeightFile != null) {
                provenance.put("vertex_weight_file", vertexWeightFile.toAbsolutePath().toString());
                provenance.put("vertex_weight_sha256", sha256(vertexWeightFile));
                provenance.put("scalar_weight_model_is_physical_authority", false);
            }
            provenance.put("physical_utilization_authority", "Native Vivado subset report required separately");
            EndpointKindDiagnosticReport.write(outDir, refiner.retainedEndpointAnalysis(finalAssignment),
                    loadedInputHash, provenance.getString("mapping_sha256"),
                    sha256(outDir.resolve("final_assignment.part")));
            Files.write(outDir.resolve("run_provenance.json"),
                    java.util.Collections.singletonList(provenance.toString(2)));
            writeStatus(context, "PASS");
            System.out.println("Partitioner final mapping: " + outDir.resolve("mapping.txt"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        t.stop();
        t.printSummary();
    }
}
