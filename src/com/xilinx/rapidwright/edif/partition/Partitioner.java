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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Command-line tool to partition an EDIFNetlist
 */
public class Partitioner {

    private static void memAudit(String label) {
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - free;
        System.out.printf("PARTITIONER DEBUG: %s -> used=%d MB total=%d MB free=%d MB%n",
                label, used / (1024 * 1024), total / (1024 * 1024), free / (1024 * 1024));
    }

    public static AbstractPartitioner getDefaultPartitioner() {
        MtKaHyParPartitioner p = new MtKaHyParPartitioner();
        return p;
    }
    
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("<input.edf> <# of partitions> <leafLUTCountLimit> [--seed N] [--epsilon E] [--threads T] [--partition_config default/deterministic] [--objective cut/km1/soed]");
            return;
        }
        Path inputEDIF = Paths.get(args[0]);
        int k = Integer.parseInt(args[1]);
        int leafLUTCountLimit = Integer.parseInt(args[2]);
        CodePerfTracker t = new CodePerfTracker("Partitioner");

        memAudit("rapidwright mem usg edif before");
        t.start("Read EDIF");
        EDIFNetlist n = EDIFTools.readEdifFile(inputEDIF);
        t.stop();
        memAudit("rapidwright mem usg edif after");

        t.start("Coarsen Netlist");
        Map<EDIFHierCellInst, Integer> instLutCountMap = new HashMap<>();
        Map<EDIFHierCellInst, Integer> leafInsts = PartitionTools.identifyLeafInstances(n,
                leafLUTCountLimit, instLutCountMap);
        System.out.println("Identified " + leafInsts.size() + " leaves");
        {
            final int sampleTarget = 100_000;
            final int step = Math.max(1, leafInsts.size() / sampleTarget);
            long sampledHier = 0, missingHier = 0;
            int idx = 0;
            for (EDIFHierCellInst ci : leafInsts.keySet()) {
                if ((idx++ % step) != 0) continue;
                if (!ci.getCellType().isLeafCellOrBlackBox()) {
                    sampledHier++;
                    if (!instLutCountMap.containsKey(ci)) {
                        missingHier++;
                    }
                }
            }
            double pct = sampledHier == 0 ? 0.0 : 100.0 * missingHier / sampledHier;
            System.out.printf("PARTITIONER DEBUG: leafInsts coverage -> sampledHier=%d missingHier=%d pct=%.4f step=%d%n",
                    sampledHier, missingHier, pct, step); // reports sampled coverage of missing lut counts among hierarchical leaves to confirm incomplete instance coverage
        }
        int totalLUTs = instLutCountMap.get(n.getTopHierCellInst());
        if (leafLUTCountLimit >= totalLUTs || leafLUTCountLimit < 1) {
            throw new RuntimeException("ERROR: Invalid leafLUTCountLimit '" + leafLUTCountLimit
                    + "', must be less than total LUT count in netlist or 1 or greater.");
        }
        t.stop();

        t.start("Find Edges");
        Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap = new HashMap<>();
        for (Entry<EDIFHierCellInst, Integer> e : leafInsts.entrySet()) {
            for (EDIFHierPortInst pi : e.getKey().getHierPortInsts()) {
                EDIFHierNet connectedNet = pi.getHierarchicalNet();
                EDIFHierNet parentNet = n.getParentNet(connectedNet);
                if (edgesMap.containsKey(parentNet))
                    continue;
                edgesMap.put(parentNet, connectedNet.getConnectedInsts(leafInsts.keySet()));
            }
        }
        t.stop();

        t.start("Write hMETIS File");
        Path hMetisFile = Paths.get(inputEDIF.toString() + ".hgr");
        PartitionTools.writeHMetisFile(hMetisFile, edgesMap, leafInsts);
        t.stop();
        
        t.start("Run Partitioner");
        AbstractPartitioner p = getDefaultPartitioner();
        p.setInputFile(hMetisFile);
        p.setKPartitions(k);
        // optional args
        if (p instanceof MtKaHyParPartitioner) {
            MtKaHyParPartitioner mp = (MtKaHyParPartitioner) p;
            for (int i = 3; i < args.length; i++) {
                String a = args[i];
                if (a.equals("--seed") && i + 1 < args.length) {
                    mp.setSeed(Integer.parseInt(args[++i]));
                } else if (a.startsWith("--seed=")) {
                    mp.setSeed(Integer.parseInt(a.substring("--seed=".length())));
                } else if (a.equals("--epsilon") && i + 1 < args.length) {
                    mp.setEpsilon(Double.parseDouble(args[++i]));
                } else if (a.startsWith("--epsilon=")) {
                    mp.setEpsilon(Double.parseDouble(a.substring("--epsilon=".length())));
                } else if (a.equals("--threads") && i + 1 < args.length) {
                    mp.setNumThreads(Integer.parseInt(args[++i]));
                } else if (a.startsWith("--threads=")) {
                    mp.setNumThreads(Integer.parseInt(a.substring("--threads=".length())));
                } else if (a.equals("--partition_config") && i + 1 < args.length) {
                    mp.setPresetType(args[++i]);
                } else if (a.startsWith("--partition_config=")) {
                    mp.setPresetType(a.substring("--partition_config=".length()));
                } else if (a.equals("--objective") && i + 1 < args.length) {
                    mp.setObjective(args[++i]);
                } else if (a.startsWith("--objective=")) {
                    mp.setObjective(a.substring("--objective=".length()));
                }
            }
            // print selected user flags summary including objective
            System.out.printf("Partitioner flag: objective=%s%n", mp.getObjective());
        }
        memAudit("rapidwright mem usg before partitioner run");
        p.runPartitioner();
        memAudit("rapidwright mem usg after partitioner run (java)");
        t.stop();
        
        t.start("Read Partition Solution");
        Path outputFile = p.getOutputFile();
        String[] instLookup = PartitionTools.createInstLookupArray(leafInsts);
        Map<Integer, Set<String>> partitions = PartitionTools.readSolutionFile(outputFile, instLookup);
        // add a name-keyed cache to avoid identity/key churn on large netlists
        java.util.Map<String, Integer> lutByName = new java.util.HashMap<>();

        {
            int mismatches = 0;
            int maxCheck = Math.min(1000, instLookup.length - 1);
            for (int j = 1; j <= maxCheck; j++) {
                String nm = instLookup[j];
                if (nm == null) continue;
                if (n.getHierCellInstFromName(nm) == null) {
                    mismatches++;
                }
            }
            if (mismatches > 0) {
                System.err.printf("PARTITIONER DEBUG: name roundtrip -> mismatches=%d checked=%d%n", mismatches, maxCheck); // verifies name to instance roundtrip integrity is not causing errors
            } else {
                System.out.println("PARTITIONER DEBUG: name roundtrip -> ok"); // verifies name to instance roundtrip integrity is not causing errors
            }
        }
        MessageGenerator.printHeader("Partition Solution Report");
        final int DBG_MAX_MISS_LOGS = 100; //after 100 missed LUT counts stop printing to the terminal..
        int dbgMissingLUTCountTotal = 0;   //track the amount of times we never had a valid count of LUTs
        int dbgMissingLUTCountPrinted = 0; //track the amount of times we print missed luts.
        for (int i = 0; i < partitions.size(); i++) {
            int lutCount = 0;
            Set<String> names = partitions.get(i);
            Path partitionFile = Paths.get(inputEDIF.toString() + ".part" + i);
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(partitionFile.toFile()))) {
                for (String name : names) {
                    bw.write(name + "\n");
                    EDIFHierCellInst inst = n.getHierCellInstFromName(name);
                    if (inst.getCellType().isLeafCellOrBlackBox()) {
                        lutCount += inst.getCellName().contains("LUT") ? 1 : 0;
                    } else {
                        Integer cnt = instLutCountMap.get(inst);
                        if (cnt == null) {
                            // try name-based cache first
                            Integer cached = lutByName.get(name);
                            if (cached != null) {
                                cnt = cached;
                            }
                        }
                        if (cnt == null) {
                            dbgMissingLUTCountTotal++;
                            if (dbgMissingLUTCountPrinted < DBG_MAX_MISS_LOGS) {
                                System.err.printf(
                                        "PARTITIONER DEBUG: missing lut count -> name=%s instPath=%s type=%s depth=%d isLeafOrBB=%s%n",
                                        name, inst.toString(), inst.getCellType().getName(), inst.getDepth(),
                                        inst.getCellType().isLeafCellOrBlackBox()); // includes hierarchical instance path to localize where the missing lut count occurs
                                dbgMissingLUTCountPrinted++;
                            }
                            // recompute lut count on-demand for this hierarchical instance and fill caches
                            Integer recomputed = PartitionTools.getLUTCount(inst, new java.util.HashMap<>(), instLutCountMap);
                            if (recomputed != null) {
                                cnt = recomputed;
                                lutByName.put(name, cnt);
                            }
                        }
                        if (cnt == null) {
                            // last resort to avoid crash on extremely large netlists
                            // don't keep a null value in final report cause a crash will happen
                            // just set as zero.. partition should still be valid but report will be off.
                            cnt = 0;
                        }
                        lutCount += cnt.intValue();
                    }
                }
                System.out.printf("  Partition %3d %10d LUTs %s\n", i, lutCount, partitionFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        System.out.printf("PARTITIONER DEBUG: missing lut count summary -> total=%d printed=%d limit=%d%n",
                dbgMissingLUTCountTotal, dbgMissingLUTCountPrinted, DBG_MAX_MISS_LOGS); // reports total missing lut count lookups to quantify impact
        System.out.println("-----------------------------------------------------------");
        System.out.printf("        Total : %10d LUTs\n\n\n", totalLUTs);
        t.stop();
        t.printSummary();
    }
}
