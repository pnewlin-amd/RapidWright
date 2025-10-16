/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Perry Newlin
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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;

/**
 * writes io_cuts.txt (direction-agnostic summary with both permutations) and io_cuts_directional.txt (driver-resolved), 
 * and centralizes generation of the detailed nets.txt report.
 */
public final class IoCutWriter {

    private IoCutWriter() {
        // no instances; this utility is a static facade that writes summary artifacts derived from the partition solution
    }

    /**
     * writes both io_cuts.txt (in outDir) and the detailed nets.txt (following existing behavior).
     */
    public static void write(Path outDir,
                             Path inputEDIF,
                             EDIFNetlist netlist,
                             String[] instLookup,
                             Map<Integer, Set<String>> partitions,
                             boolean generate_edif_nets) {
        // build a deterministic name -> partition id lookup so we can translate each hypergraph member to its block id quickly.
        Map<String, Integer> nameToPart = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<String>> pe : partitions.entrySet()) {
            for (String nm : pe.getValue()) {
                nameToPart.put(nm, pe.getKey());
            }
        }

        // derive all artifact paths alongside the input edif; these are produced earlier in the flow:
        // - .hgr lists each edge as a space-separated list of vertex ids
        // - .eidmap is a 1-based list of hierarchical net names aligned with the edges in .hgr
        // - .nets.txt is the verbose connectivity report we keep centralized here for convenience
        String base = inputEDIF.getFileName().toString();
        Path hgr = outDir.resolve(base + ".hgr");
        Path eidmap = outDir.resolve(base + ".eidmap");
        Path netsOut = outDir.resolve(base + ".nets.txt");

        //=================================^IO CUT WRITER^===================================
        //=================================*-*-*-*-*-*-*-*===================================
        //===============================vDETAILED .NETS.TXTv================================

        // always write the new, direction-agnostic io cuts into io_cuts.txt.
        // this uses .hgr membership and ignores driver direction, emitting counts for both permutations.
        writeIoCutsUndirected(outDir, hgr, instLookup, nameToPart);

        // write directional io cuts into io_cuts_directional.txt.
        if (generate_edif_nets) {
            writeIoCuts(outDir, hgr, eidmap, instLookup, nameToPart, netlist);
        } else {
            writeDirectionalIoCutsNoEidmap(outDir, netlist, instLookup, nameToPart);
        }

        // generate the detailed per-net report
        if (generate_edif_nets) {
            writeDetailedNetsReport(hgr, eidmap, instLookup, nameToPart, netsOut);
        }
    }

    /**
     * writes io_cuts_directional.txt into outdir.
     */
    private static void writeIoCuts(Path outDir,
                                    Path hgrFile,
                                    Path eidmapFile,
                                    String[] instLookup,
                                    Map<String, Integer> nameToPart,
                                    EDIFNetlist netlist) {
        // step 1: read .eidmap so edge index -> net name lookups are fast and aligned with .hgr.
        // this is crucial because .eidmap encodes the parent-level hierarchical name, which is the same
        // string representation we will reconstruct when we query the netlist at the parent scope.
        List<String> netNames = new ArrayList<>();
        try (BufferedReader eid = new BufferedReader(new FileReader(eidmapFile.toFile()))) {
            String line;
            while ((line = eid.readLine()) != null) {
                netNames.add(line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // this map accumulates "srcLabel-->dstLabel" -> count. we keep it ordered for stable output,
        // which makes diffs cleaner and pattern recognition easier during review.
        Map<String, Integer> pairCounts = new LinkedHashMap<>();

        // step 2: scan the hypergraph (.hgr). for each edge, build the list of participating hierarchical
        // instance names by translating vertex ids through instLookup. if there are no participants, we skip.
        // otherwise, we attempt to resolve a single driving participant by comparing hierarchical port instances
        // against the parent-level hierarchical net name and its source-side endpoints.
        try (BufferedReader hgr = new BufferedReader(new FileReader(hgrFile.toFile()))) {
            String header = hgr.readLine(); // ignore header; the following lines are the edges
            String line;
            int edgeIdx = 0;
            while ((line = hgr.readLine()) != null) {
                edgeIdx++;
                String netName = edgeIdx <= netNames.size() ? netNames.get(edgeIdx - 1)
                        : ("<edge_" + edgeIdx + ">");

                String[] toks = line.trim().isEmpty() ? new String[0] : line.trim().split("\\s+");

                // gather all participating hierarchical instance names for this edge (signal).
                // these names let us locate each instance in the netlist and ultimately identify the driver.
                List<String> instNames = new ArrayList<>(toks.length);
                for (String t : toks) {
                    if (t.isEmpty()) continue;
                    int vid;
                    try {
                        vid = Integer.parseInt(t);
                    } catch (NumberFormatException nfe) {
                        continue;
                    }
                    String instName = (vid >= 0 && vid < instLookup.length) ? instLookup[vid] : null;
                    if (instName != null) instNames.add(instName);
                }
                if (instNames.isEmpty()) continue;

                // step 2a: identify the driving participant. we do this by inspecting each participant’s
                // hierarchical port instances and retrieving its hierarchical net at the parent scope.
                // using the parent-level net ensures the string name (toString) matches the .eidmap entry.
                // if this participant’s hierarchical port instance is listed as a source on that net, we
                // designate it as the driver and record its partition as the source partition for this signal.
                String driverName = null;
                Integer driverPart = null;
                for (String nm : instNames) {
                    EDIFHierCellInst inst = netlist.getHierCellInstFromName(nm);
                    if (inst == null) continue;
                    for (EDIFHierPortInst pi : inst.getHierPortInsts()) {
                    EDIFHierNet hnet = pi.getHierarchicalNet();
                    if (hnet == null) continue;
                    EDIFHierNet parent = null; //skip ambiguous nets, TODO: is there a better way to handle this!!!
                    try { parent = netlist.getParentNet(hnet); } catch (RuntimeException ex) { parent = null; } //skip ambiguous nets, TODO: is there a better way to handle this!!!
                    String hn = (parent != null) ? parent.toString() : hnet.toString();
                    if (!netName.equals(hn)) continue;

                        // now compare against the net’s source endpoints at the same scope we used for naming.
                        // if this port instance is one of the sources, we treat this participant as the driver.
                    List<EDIFHierPortInst> sources = ((parent != null) ? parent : hnet).getSourcePortInsts(true);
                    if (sources == null || sources.size() != 1) continue; //skip ambiguous nets
                    for (EDIFHierPortInst spi : sources) {
                            if (spi.equals(pi)) {
                                driverName = nm;
                                driverPart = nameToPart.get(driverName);
                                break;
                            }
                        }
                        if (driverName != null) break;
                    }
                    if (driverName != null) break;
                }

                // if no driver can be resolved (for example, tie-offs that are not represented as a source),
                // we skip this edge for the directional summary. the detailed nets.txt will still list it normally.
                if (driverName == null || driverPart == null) continue;

                String driverLabel = PartitionLabel.indexToFpgaLabel(driverPart);

                // step 2b: accumulate a single count per destination partition for this signal.
                java.util.Set<Integer> destParts = new java.util.HashSet<>();
                for (String nm : instNames) {
                    if (nm.equals(driverName)) continue;
                    Integer dst = nameToPart.get(nm);
                    if (dst == null) continue;
                    if (dst.intValue() == driverPart.intValue()) continue;
                    destParts.add(dst);
                }
                for (Integer dp : destParts) {
                    String sinkLabel = PartitionLabel.indexToFpgaLabel(dp);
                    String key = driverLabel + "-->" + sinkLabel;
                    pairCounts.put(key, pairCounts.getOrDefault(key, 0) + 1);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // step 3: write io_cuts.txt in a stable, sorted order. presenting entries deterministically makes it
        // much easier to review diffs between runs and to visually scan where the heaviest traffic resides.
        List<String> lines = new ArrayList<>(pairCounts.size());
        java.util.List<String> keys = new java.util.ArrayList<>(pairCounts.keySet());
        java.util.Collections.sort(keys);
        for (String key : keys) {
            int cnt = pairCounts.get(key);
            // each key is "fpga_x-->fpga_y"; we rewrite it as "fpga_x--N--fpga_y" where n is the net count between them.
            String formatted = key.replace("-->", "--" + cnt + "--");
            lines.add(formatted);
        }

        Path ioCuts = outDir.resolve("io_cuts_directional.txt");
        try {
            Files.write(ioCuts, lines, StandardCharsets.UTF_8);
            System.out.println("Partitioner artifact written: " + ioCuts);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * writes io_cuts.txt (direction-agnostic) into outdir by scanning .hgr membership and
     * counting every pair of distinct partitions that share a net. for each unordered pair,
     * both permutations are written so downstream tools can consume either ordering.
     */
    private static void writeIoCutsUndirected(Path outDir,
                                              Path hgrFile,
                                              String[] instLookup,
                                              Map<String, Integer> nameToPart) {
        Map<String, Integer> pairCounts = new LinkedHashMap<>();

        try (BufferedReader hgr = new BufferedReader(new FileReader(hgrFile.toFile()))) {
            String header = hgr.readLine(); // ignore header
            String line;
            while ((line = hgr.readLine()) != null) {
                String[] toks = line.trim().isEmpty() ? new String[0] : line.trim().split("\\s+");
                java.util.Set<Integer> parts = new java.util.HashSet<>();
                for (String t : toks) {
                    if (t.isEmpty()) continue;
                    int vid;
                    try {
                        vid = Integer.parseInt(t);
                    } catch (NumberFormatException nfe) {
                        continue;
                    }
                    String instName = (vid >= 0 && vid < instLookup.length) ? instLookup[vid] : null;
                    Integer part = (instName == null) ? null : nameToPart.get(instName);
                    if (part != null) parts.add(part.intValue());
                }
                if (parts.size() < 2) continue;

                java.util.List<Integer> plist = new java.util.ArrayList<>(parts);
                for (int i = 0; i < plist.size(); i++) {
                    for (int j = i + 1; j < plist.size(); j++) {
                        String a = PartitionLabel.indexToFpgaLabel(plist.get(i));
                        String b = PartitionLabel.indexToFpgaLabel(plist.get(j));
                        String k1 = a + "," + b;
                        String k2 = b + "," + a;
                        pairCounts.put(k1, pairCounts.getOrDefault(k1, 0) + 1);
                        pairCounts.put(k2, pairCounts.getOrDefault(k2, 0) + 1);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        java.util.List<String> keys = new java.util.ArrayList<>(pairCounts.keySet());
        java.util.Collections.sort(keys);
        java.util.List<String> lines = new java.util.ArrayList<>(keys.size());
        for (String key : keys) {
            int sep = key.indexOf(',');
            String a = key.substring(0, sep);
            String b = key.substring(sep + 1);
            int cnt = pairCounts.get(key);
            lines.add(a + "--" + cnt + "--" + b);
        }

        Path ioCuts = outDir.resolve("io_cuts.txt");
        try {
            Files.write(ioCuts, lines, StandardCharsets.UTF_8);
            System.out.println("Partitioner artifact written: " + ioCuts);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * writes io_cuts_directional.txt by recomputing edges from the netlist when .eidmap
     * is not available. this avoids relying on large eidmap files while still producing
     * the directional summary.
     */
    private static void writeDirectionalIoCutsNoEidmap(Path outDir,
                                                       EDIFNetlist netlist,
                                                       String[] instLookup,
                                                       Map<String, Integer> nameToPart) {
        // reconstruct leaf set from instlookup
        java.util.Set<EDIFHierCellInst> leafSet = new java.util.HashSet<>();
        for (int i = 1; i < instLookup.length; i++) {
            String nm = instLookup[i];
            if (nm == null) continue;
            EDIFHierCellInst inst = netlist.getHierCellInstFromName(nm);
            if (inst != null) leafSet.add(inst);
        }

        // rebuild edges: parent-level net -> participating leaf instances
        Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap = new java.util.HashMap<>();
        for (EDIFHierCellInst ci : leafSet) {
            for (EDIFHierPortInst pi : ci.getHierPortInsts()) {
                EDIFHierNet hnet = pi.getHierarchicalNet();
                if (hnet == null) continue;
                EDIFHierNet parent = null; //skip ambiguous nets
                try { parent = netlist.getParentNet(hnet); } catch (RuntimeException ex) { parent = null; } //skip ambiguous nets
                EDIFHierNet key = (parent != null) ? parent : hnet; //skip ambiguous nets
                if (edgesMap.containsKey(key)) continue;
                edgesMap.put(key, hnet.getConnectedInsts(leafSet));
            }
        }

        Map<String, Integer> pairCounts = new LinkedHashMap<>();

        for (Map.Entry<EDIFHierNet, Set<EDIFHierCellInst>> e : edgesMap.entrySet()) {
            java.util.List<String> instNames = new java.util.ArrayList<>(e.getValue().size());
            for (EDIFHierCellInst ci : e.getValue()) {
                instNames.add(ci.toString());
            }
            if (instNames.isEmpty()) continue;

            // find driver by matching source endpoints on the same parent net
            String driverName = null;
            Integer driverPart = null;
            for (String nm : instNames) {
                EDIFHierCellInst inst = netlist.getHierCellInstFromName(nm);
                if (inst == null) continue;
                for (EDIFHierPortInst pi : inst.getHierPortInsts()) {
                    EDIFHierNet hnet = pi.getHierarchicalNet();
                    if (hnet == null) continue;
                    EDIFHierNet parent = null; //skip ambiguous nets
                    try { parent = netlist.getParentNet(hnet); } catch (RuntimeException ex) { parent = null; } //skip ambiguous nets
                    EDIFHierNet netRef = (parent != null) ? parent : hnet;
                    if (netRef != e.getKey()) continue;

                    java.util.List<EDIFHierPortInst> sources = netRef.getSourcePortInsts(true);
                    if (sources == null || sources.size() != 1) continue; //skip ambiguous nets
                    for (EDIFHierPortInst spi : sources) {
                        if (spi.equals(pi)) {
                            driverName = nm;
                            driverPart = nameToPart.get(driverName);
                            break;
                        }
                    }
                    if (driverName != null) break;
                }
                if (driverName != null) break;
            }
            if (driverName == null || driverPart == null) continue;

            String driverLabel = PartitionLabel.indexToFpgaLabel(driverPart);

            java.util.Set<Integer> destParts = new java.util.HashSet<>();
            for (String nm : instNames) {
                if (nm.equals(driverName)) continue;
                Integer dst = nameToPart.get(nm);
                if (dst == null) continue;
                if (dst.intValue() == driverPart.intValue()) continue;
                destParts.add(dst);
            }
            for (Integer dp : destParts) {
                String sinkLabel = PartitionLabel.indexToFpgaLabel(dp);
                String key = driverLabel + "-->" + sinkLabel;
                pairCounts.put(key, pairCounts.getOrDefault(key, 0) + 1);
            }
        }

        java.util.List<String> keys = new java.util.ArrayList<>(pairCounts.keySet());
        java.util.Collections.sort(keys);
        java.util.List<String> lines = new java.util.ArrayList<>(keys.size());
        for (String key : keys) {
            int cnt = pairCounts.get(key);
            String formatted = key.replace("-->", "--" + cnt + "--");
            lines.add(formatted);
        }

        Path ioCuts = outDir.resolve("io_cuts_directional.txt");
        try {
            Files.write(ioCuts, lines, StandardCharsets.UTF_8);
            System.out.println("Partitioner artifact written: " + ioCuts);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * writes detailed nets.txt using existing partitiontools logic.
     */
    private static void writeDetailedNetsReport(Path hgrFile,
                                                Path eidmapFile,
                                                String[] instLookup,
                                                Map<String, Integer> nameToPart,
                                                Path netsOut) {
        try {
            PartitionTools.writeConnectivityReportWithNames(hgrFile, eidmapFile, instLookup, nameToPart, netsOut);
            System.out.println("Partitioner artifact written: " + netsOut);
        } catch (UncheckedIOException ex) {
            System.err.println("WARNING: failed to write nets report: " + ex.getMessage());
        }
    }
}
