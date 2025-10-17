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
            System.out.println("<input.edf|input.dcp> <# of partitions> <leafLUTCountLimit> [--seed N] [--epsilon E] [--threads T] [--partition_config default/deterministic] [--objective cut/km1/soed] [--part_dir DIR] [--mapping_constraints PATH] [--constraints_debug] [--bare_bones]");
            return;
        }
        Path inputPath = Paths.get(args[0]);
        int k = Integer.parseInt(args[1]);
        int leafLUTCountLimit = Integer.parseInt(args[2]);
        CodePerfTracker t = new CodePerfTracker("Partitioner");
        
        boolean generate_edif_nets = false; //verbose information

        Path constraints_file = null;
        // enable extra logs for constraints

        boolean constraints_debug = false;
        boolean bare_bones = false;
        
        // establish default output directory next to input EDIF (or cwd if none)
        Path outDir = (inputPath.getParent() == null)
                ? Paths.get(System.getProperty("user.dir"))
                : inputPath.getParent();
        // parse early flags that affect artifact emission and locations
        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if ("--part_dir".equals(a) && i + 1 < args.length) {
                outDir = Paths.get(args[++i]);
            } else if (a.startsWith("--part_dir=")) {
                outDir = Paths.get(a.substring("--part_dir=".length()));
            } else if ("--mapping_constraints".equals(a) && i + 1 < args.length) {
                constraints_file = Paths.get(args[++i]);
            } else if (a.startsWith("--mapping_constraints=")) {
                constraints_file = Paths.get(a.substring("--mapping_constraints=".length()));
            } else if ("--constraints_debug".equals(a)) {
                constraints_debug = true;
            } else if (a.startsWith("--constraints_debug=")) {
                String v = a.substring("--constraints_debug=".length()).trim();
                constraints_debug = "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
            } else if ("--bare_bones".equals(a)) {
                bare_bones = true;
            } else if (a.startsWith("--bare_bones=")) {
                String v = a.substring("--bare_bones=".length()).trim();
                bare_bones = "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
            }
        }
        generate_edif_nets = !bare_bones;
        try {
            Files.createDirectories(outDir);
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }

        memAudit("rapidwright mem usg input before");
        t.start("Read Input");
        EDIFNetlist n;
        String inLower = inputPath.toString().toLowerCase();
        if (inLower.endsWith(".dcp")) {
            Design d = Design.readCheckpoint(inputPath.toString());
            n = d.getNetlist();
            int encrypted_count = n.getEncryptedCells().size();
            if (encrypted_count > 0) {
                throw new RuntimeException("ERROR: Encrypted DCP detected (encryptedCells=" + encrypted_count + "). Encrypted DCPs are unsupported.");
            }
        } else {
            n = EDIFTools.readEdifFile(inputPath);
        }
        t.stop();
        memAudit("rapidwright mem usg input after");

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
        if (inLower.endsWith(".dcp")) {
            java.util.Map<com.xilinx.rapidwright.edif.EDIFCell, java.lang.Integer> __p_cache = new java.util.HashMap<>();
            for (EDIFHierCellInst leaf : leafInsts.keySet()) {
                if (!leaf.getCellType().isLeafCellOrBlackBox()) {
                    if (instLutCountMap.get(leaf) == null) {
                        PartitionTools.getLUTCount(leaf, __p_cache, instLutCountMap);
                    }
                }
            }
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
                EDIFHierNet parentNet = null; //skip ambiguous nets, TODO : is there a better way to handle this edge case..?
                try { parentNet = n.getParentNet(connectedNet); } catch (RuntimeException ex) { parentNet = null; } //skip ambiguous nets
                EDIFHierNet keyNet = (parentNet != null) ? parentNet : connectedNet;
                if (edgesMap.containsKey(keyNet))
                    continue;
                edgesMap.put(keyNet, connectedNet.getConnectedInsts(leafInsts.keySet())); 
            }
        }
        t.stop();

        t.start("Write hMETIS File");
        Path hMetisFile = outDir.resolve(inputPath.getFileName().toString() + ".hgr");
        PartitionTools.writeHMetisFile(hMetisFile, edgesMap, leafInsts, generate_edif_nets);
        t.stop();

        // generate fixed vertices file if constraints were provided
        Path fix_file = null;
        if (constraints_file != null) {
            // build label->index map
            java.util.Map<String, Integer> label_to_index = new java.util.HashMap<>();
            for (int idx = 0; idx < k; idx++) {
                String lbl = PartitionLabel.indexToFpgaLabel(idx);
                label_to_index.put(lbl, Integer.valueOf(idx));
            }
            // init fix array (1-based vertex ids)
            int num_vertices = leafInsts.size();
            int[] fix_arr = new int[num_vertices + 1];
            for (int i = 0; i <= num_vertices; i++) fix_arr[i] = -1;
            int fixed_count = 0;

            // parse mapping_constraints.txt and expand to vertices
            try (BufferedReader cr = new BufferedReader(new FileReader(constraints_file.toFile()))) {
                String cline;
                while ((cline = cr.readLine()) != null) {
                    String orig = cline.trim();
                    if (orig.isEmpty() || orig.startsWith("#")) continue;
                    String[] toks = orig.split("\\s+");
                    if (toks.length != 2) {
                        throw new RuntimeException("constraint '" + orig + "' was not found valid in design");
                    }
                    String path = toks[0];
                    String label = toks[1];
                    Integer block_idx = label_to_index.get(label);
                    if (block_idx == null) {
                        throw new RuntimeException("constraint '" + orig + "' was not found valid in design");
                    }
                    int matched = 0;
                    for (Entry<EDIFHierCellInst, Integer> e : leafInsts.entrySet()) {
                        String name = e.getKey().toString();
                        int vid = e.getValue().intValue();
                        boolean leaf_contains_path = path.equals(name) || path.startsWith(name + "/");
                        boolean path_contains_leaf = name.equals(path) || name.startsWith(path + "/");
                        if (leaf_contains_path || path_contains_leaf) {
                            if (fix_arr[vid] == -1) {
                                fix_arr[vid] = block_idx.intValue();
                                fixed_count++;
                            } else if (fix_arr[vid] != block_idx.intValue()) {
                                throw new RuntimeException("constraint '" + orig + "' was not found valid in design");
                            }
                            matched++;
                        }
                    }
                    if (constraints_debug) {
                        System.out.printf("partitioner debug: constraint '%s' matched %d vertices%n", orig, matched);
                    }
                    if (matched == 0) {
                        throw new RuntimeException("constraint '" + orig + "' was not found valid in design");
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            // derive fix file path from .hgr
            String hgr_base = hMetisFile.toString();
            if (hgr_base.endsWith(".hgr")) {
                fix_file = Paths.get(hgr_base.substring(0, hgr_base.length() - 4) + ".fix");
            } else {
                fix_file = Paths.get(hgr_base + ".fix");
            }
            try (BufferedWriter fw = new BufferedWriter(new FileWriter(fix_file.toFile()))) {
                for (int vid = 1; vid <= num_vertices; vid++) {
                    fw.write(Integer.toString(fix_arr[vid]));
                    fw.write("\n");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            System.out.printf("partitioner debug: fix-file=%s vertices=%d fixed=%d%n",
                    fix_file, leafInsts.size(), fixed_count);
        }
        
        t.start("Run Partitioner");
        AbstractPartitioner p = getDefaultPartitioner();
        p.setInputFile(hMetisFile);
        p.setKPartitions(k);
        // optional args
        if (p instanceof MtKaHyParPartitioner) {
            MtKaHyParPartitioner mp = (MtKaHyParPartitioner) p;
            // ensure external tool runs and writes outputs into outDir
            mp.setOutputDir(outDir);
            // pass fixed vertices file if detected
            if (fix_file != null) {
                mp.setFixedVerticesFile(fix_file);
            }
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
            // print user flags summary
            System.out.printf("Partitioner flag: objective=%s%n", mp.getObjective());
            System.out.printf("Partitioner flag: part_dir=%s%n", outDir);
            System.out.printf("Partitioner flag: mapping_constraints=%s%n", constraints_file != null ? constraints_file.toString() : "none");
            System.out.printf("Partitioner flag: bare_bones=%s%n", bare_bones ? "on" : "off");
        }
        memAudit("rapidwright mem usg before partitioner run");
        p.runPartitioner();
        memAudit("rapidwright mem usg after partitioner run (java)");
        t.stop();
        
        t.start("Read Partition Solution");
        Path outputFile = p.getOutputFile();
        String[] instLookup = PartitionTools.createInstLookupArray(leafInsts);
        Map<Integer, Set<String>> partitions = PartitionTools.readSolutionFile(outputFile, instLookup);
        if (bare_bones) {
            int num_vertices = instLookup.length - 1;
            int[] v2p = new int[num_vertices + 1];
            try (BufferedReader br = new BufferedReader(new FileReader(outputFile.toFile()))) {
                String line = null;
                int i = 1;
                while ((line = br.readLine()) != null) {
                    int part = Integer.parseInt(line.trim());
                    v2p[i++] = part;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            EDIFHierCellInst[] inst_by_vid = new EDIFHierCellInst[num_vertices + 1];
            for (Entry<EDIFHierCellInst, Integer> e : leafInsts.entrySet()) {
                int vid = e.getValue().intValue();
                inst_by_vid[vid] = e.getKey();
            }
            int[] lut_by_vid = new int[num_vertices + 1];
            for (int vid = 1; vid <= num_vertices; vid++) {
                EDIFHierCellInst inst = inst_by_vid[vid];
                if (inst == null) continue;
                if (inst.getCellType().isLeafCellOrBlackBox()) {
                    lut_by_vid[vid] = logicDiscoveryPolicy.lut_count_for_leaf(inst);
                } else {
                    Integer cnt = instLutCountMap.get(inst);
                    lut_by_vid[vid] = (cnt == null) ? 0 : cnt.intValue();
                }
            }
            Path hgr = outDir.resolve(inputPath.getFileName().toString() + ".hgr");
            Map<String, Integer> pair_counts = new java.util.LinkedHashMap<>();
            try (BufferedReader hgr_br = new BufferedReader(new FileReader(hgr.toFile()))) {
                String header = hgr_br.readLine();
                String line = null;
                while ((line = hgr_br.readLine()) != null) {
                    String[] toks = line.trim().isEmpty() ? new String[0] : line.trim().split("\\s+");
                    java.util.Set<Integer> parts_set = new java.util.HashSet<>();
                    for (String tkn : toks) {
                        if (tkn.isEmpty()) continue;
                        int vid;
                        try { vid = Integer.parseInt(tkn); } catch (NumberFormatException nfe) { continue; }
                        int part_idx = (vid >= 1 && vid <= num_vertices) ? v2p[vid] : -1;
                        if (part_idx >= 0) parts_set.add(part_idx);
                    }
                    if (parts_set.size() < 2) continue;
                    java.util.List<Integer> plist = new java.util.ArrayList<>(parts_set);
                    for (int i = 0; i < plist.size(); i++) {
                        for (int j = i + 1; j < plist.size(); j++) {
                            String a = PartitionLabel.indexToFpgaLabel(plist.get(i));
                            String b = PartitionLabel.indexToFpgaLabel(plist.get(j));
                            String k1 = a + "," + b;
                            String k2 = b + "," + a;
                            pair_counts.put(k1, pair_counts.getOrDefault(k1, 0) + 1);
                            pair_counts.put(k2, pair_counts.getOrDefault(k2, 0) + 1);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            java.util.List<String> keys = new java.util.ArrayList<>(pair_counts.keySet());
            java.util.Collections.sort(keys);
            java.util.List<String> lines = new java.util.ArrayList<>(keys.size());
            for (String key : keys) {
                int sep = key.indexOf(',');
                String a = key.substring(0, sep);
                String b = key.substring(sep + 1);
                int cnt = pair_counts.get(key);
                lines.add(a + "--" + cnt + "--" + b);
            }
            Path io_cuts = outDir.resolve("io_cuts.txt");
            try {
                Files.write(io_cuts, lines, java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("Partitioner artifact written: " + io_cuts);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            java.util.ArrayList<Integer> lut_counts = new java.util.ArrayList<>();
            int max_part = -1;
            for (int vid = 1; vid <= num_vertices; vid++) {
                int part_idx = v2p[vid];
                if (part_idx > max_part) max_part = part_idx;
            }
            for (int i = 0; i <= max_part; i++) lut_counts.add(0);
            for (int vid = 1; vid <= num_vertices; vid++) {
                int part_idx = v2p[vid];
                if (part_idx < 0) continue;
                lut_counts.set(part_idx, lut_counts.get(part_idx) + lut_by_vid[vid]);
            }
            long sum_luts = 0;
            for (int c : lut_counts) sum_luts += c;
            int mean_luts = (lut_counts.isEmpty()) ? 0 : (int) Math.ceil(sum_luts / (double) lut_counts.size());
            Path lut_report = outDir.resolve("lut_report.txt");
            try (BufferedWriter lw = new BufferedWriter(new FileWriter(lut_report.toFile()))) {
                for (int idx = 0; idx < lut_counts.size(); idx++) {
                    String label = PartitionLabel.indexToFpgaLabel(idx);
                    lw.write(lut_counts.get(idx) + "LUTS " + label);
                    lw.write("\n");
                }
                lw.write(mean_luts + "LUTS mean");
                lw.write("\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            System.out.println("Partitioner artifact written: " + lut_report);
            if (sum_luts != totalLUTs) {
                System.err.printf("ERROR: LUT count policy mismatch: per-partitions sum=%d, top-level total=%d%n", sum_luts, totalLUTs);
            }
            try {
                HierMappingWriter.write(outDir, n, partitions, instLutCountMap);
                System.out.println("Partitioner artifact written: " + outDir.resolve("cells.txt"));
                System.out.println("Partitioner artifact written: " + outDir.resolve("mapping.txt"));
            } catch (RuntimeException ex) {
                System.err.println("WARNING: failed to write hierarchical mapping artifacts: " + ex.getMessage());
            }
        } else {
            try {
                IoCutWriter.write(outDir, inputPath, n, instLookup, partitions, generate_edif_nets);
            } catch (RuntimeException ex) {
                System.err.println("WARNING: failed to write io cuts / nets artifacts: " + ex.getMessage());
            }
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
                    System.err.printf("PARTITIONER DEBUG: name roundtrip -> mismatches=%d checked=%d%n", mismatches, maxCheck);
                } else {
                    System.out.println("PARTITIONER DEBUG: name roundtrip -> ok");
                }
            }
            MessageGenerator.printHeader("Partition Solution Report");
            final int DBG_MAX_MISS_LOGS = 100;
            int dbgMissingLUTCountTotal = 0;
            int dbgMissingLUTCountPrinted = 0;
            java.util.ArrayList<Integer> lutCounts = new java.util.ArrayList<>();
            for (int i = 0; i < partitions.size(); i++) {
                int lutCount = 0;
                Set<String> names = partitions.get(i);
                String partLabel = PartitionLabel.indexToFpgaLabel(i);
                Path partitionFile = outDir.resolve(inputPath.getFileName().toString() + "." + partLabel);
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(partitionFile.toFile()))) {
                    for (String name : names) {
                        bw.write(name + "\n");
                        EDIFHierCellInst inst = n.getHierCellInstFromName(name);
                        if (inst.getCellType().isLeafCellOrBlackBox()) {
                            lutCount += logicDiscoveryPolicy.lut_count_for_leaf(inst);
                        } else {
                            Integer cnt = instLutCountMap.get(inst);
                            if (cnt == null) {
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
                                            inst.getCellType().isLeafCellOrBlackBox());
                                    dbgMissingLUTCountPrinted++;
                                }
                                Integer recomputed = PartitionTools.getLUTCount(inst, new java.util.HashMap<>(), instLutCountMap);
                                if (recomputed != null) {
                                    cnt = recomputed;
                                    lutByName.put(name, cnt);
                                }
                            }
                            if (cnt == null) {
                                cnt = 0;
                            }
                            lutCount += cnt.intValue();
                        }
                    }
                    System.out.printf("  Partition %3d %10d LUTs %s\n", i, lutCount, partitionFile);
                    lutCounts.add(Integer.valueOf(lutCount));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            long sumLuts = 0;
            for (int c : lutCounts) sumLuts += c;
            int meanLuts = (lutCounts.isEmpty()) ? 0 : (int) Math.ceil(sumLuts / (double) lutCounts.size());
            Path lutReport = outDir.resolve("lut_report.txt");
            try (BufferedWriter lw = new BufferedWriter(new FileWriter(lutReport.toFile()))) {
                for (int idx = 0; idx < lutCounts.size(); idx++) {
                    String label = PartitionLabel.indexToFpgaLabel(idx);
                    lw.write(lutCounts.get(idx) + "LUTS " + label);
                    lw.write("\n");
                }
                lw.write(meanLuts + "LUTS mean");
                lw.write("\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            System.out.println("Partitioner artifact written: " + lutReport);
            if (sumLuts != totalLUTs) {
                System.err.printf("ERROR: LUT count policy mismatch: per-partitions sum=%d, top-level total=%d%n", sumLuts, totalLUTs);
            }
            System.out.printf("PARTITIONER DEBUG: missing lut count summary -> total=%d printed=%d limit=%d%n",
                    dbgMissingLUTCountTotal, dbgMissingLUTCountPrinted, DBG_MAX_MISS_LOGS);
            System.out.println("-----------------------------------------------------------");
            System.out.printf("        Total : %10d LUTs\n\n\n", totalLUTs);

            try {
                HierMappingWriter.write(outDir, n, partitions, instLutCountMap);
                System.out.println("Partitioner artifact written: " + outDir.resolve("cells.txt"));
                System.out.println("Partitioner artifact written: " + outDir.resolve("mapping.txt"));
            } catch (RuntimeException ex) {
                System.err.println("WARNING: failed to write hierarchical mapping artifacts: " + ex.getMessage());
            }
        }

        t.stop();
        t.printSummary();
    }
}
