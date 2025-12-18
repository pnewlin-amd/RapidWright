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
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;

/**
 * Writes io_cuts.txt (direction-agnostic summary with both permutations)
 * and io_cuts_directional.txt (driver-resolved).
 */
public final class IoCutWriter {

    private IoCutWriter() {
        // no instances.
    }

    /**
     * Writes both io_cuts.txt (in outDir) and the detailed nets.txt.
     */
    public static void write(Path outDir
            , Path inputEdif
            , EDIFNetlist netlist
            , String[] instLookup
            , Map<Integer, Set<String>> partitions
            , boolean generateEdifNets) {
        // build a deterministic name -> partition id lookup so we can translate each
        // hypergraph member to its block id.
        Map<String, Integer> nameToPart = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<String>> partitionEntry : partitions.entrySet()) {
            for (String instanceName : partitionEntry.getValue()) {
                nameToPart.put(instanceName, partitionEntry.getKey());
            }
        }

        // derive artifact paths w/ the input edif; these are produced earlier in the flow:
        // - .hgr lists each edge as a space-separated list of vertex ids
        // - .eidmap is a 1-based list of hierarchical net names aligned with the edges in .hgr
        // - .nets.txt is the verbose connectivity report we keep centralized here for convenience
        String base = inputEdif.getFileName().toString();
        Path hgr = outDir.resolve(base + ".hgr");
        Path eidmap = outDir.resolve(base + ".eidmap");
        Path netsOut = outDir.resolve(base + ".nets.txt");

        // always write the new, direction-agnostic io cuts into io_cuts.txt.
        // this uses .hgr membership and ignores driver direction, emitting counts for both
        // permutations.
        writeIoCutsUndirected(outDir, hgr, instLookup, nameToPart);

        // write directional io cuts into io_cuts_directional.txt.
        if (generateEdifNets) {
            writeIoCuts(outDir, hgr, eidmap, instLookup, nameToPart, netlist);
        } else {
            writeDirectionalIoCutsNoEidmap(outDir, netlist, instLookup, nameToPart);
        }

        // generate the detailed per-net report
        if (generateEdifNets) {
            writeDetailedNetsReport(hgr, eidmap, instLookup, nameToPart, netsOut);
        }
    }

    /**
     * Writes io_cuts_directional.txt into outdir.
     */
    private static void writeIoCuts(Path outDir
            , Path hgrFile
            , Path eidmapFile
            , String[] instLookup
            , Map<String, Integer> nameToPart
            , EDIFNetlist netlist) {
        // ============================================================================
        // STEP 1
        // Read .eidmap so edge index -> net name lookups are fast and aligned with .hgr.
        // Because .eidmap encodes the parent-level hierarchical name, which is the same
        // string representation, we will then reconstruct when we query the netlist at the
        // parent scope.
        // ============================================================================
        List<String> netNames = new ArrayList<>();
        try (BufferedReader eidmapReader = new BufferedReader(
                new FileReader(eidmapFile.toFile()))) {
            String line;
            while ((line = eidmapReader.readLine()) != null) {
                netNames.add(line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // this map accumulates "srcLabel-->dstLabel" counts. we keep it ordered for stable output,
        Map<String, Integer> pairCounts = new LinkedHashMap<>();

        // ============================================================================
        // STEP 2
        // Scan the hypergraph (.hgr). For each edge, build the list of participating
        // hierarchical instance names by translating vertex ids through instLookup. If there are
        // no participants, we skip. Otherwise try to resolve a single driving participant by
        // comparing hierarchical port instances against the parent-level hierarchical net name
        // and its source-side endpoints.
        // ============================================================================
        try (BufferedReader hgrReader = new BufferedReader(new FileReader(hgrFile.toFile()))) {
            String header = hgrReader.readLine(); // ignore header; following lines are edges
            String line;
            int edgeIdx = 0;
            while ((line = hgrReader.readLine()) != null) {
                edgeIdx++;
                String netName = edgeIdx <= netNames.size() ? netNames.get(edgeIdx - 1)
                        : ("<edge_" + edgeIdx + ">");

                String[] tokens = line.trim().isEmpty() ? new String[0] : line.trim().split("\\s+");

                // gather all participating hierarchical instance names for this edge (signal).
                List<String> instNames = new ArrayList<>(tokens.length);
                for (String t : tokens) {
                    if (t.isEmpty()) {
                        continue;
                    }
                    int vertexId;
                    try {
                        vertexId = Integer.parseInt(t);
                    } catch (NumberFormatException nfe) {
                        continue;
                    }
                    String instanceName = (vertexId >= 0 && vertexId < instLookup.length)
                            ? instLookup[vertexId] : null;
                    if (instanceName != null) {
                        instNames.add(instanceName);
                    }
                }
                if (instNames.isEmpty()) {
                    continue;
                }

                // ============================================================================
                // STEP 2a
                // Identify the driving participant. We do this by inspecting each
                // participant’s hierarchical port instances and retrieving its hierarchical net
                // at the parent scope.
                // ============================================================================
                String driverName = null;
                Integer driverPart = null;
                for (String instanceName : instNames) {
                    EDIFHierCellInst inst = netlist.getHierCellInstFromName(instanceName);
                    if (inst == null) {
                        continue;
                    }
                    for (EDIFHierPortInst portInst : inst.getHierPortInsts()) {
                        EDIFHierNet hierNet = portInst.getHierarchicalNet();
                        if (hierNet == null) {
                            continue;
                        }
                        EDIFHierNet parentNet = null;
                        try {
                            parentNet = netlist.getParentNet(hierNet);
                        } catch (RuntimeException ex) {
                            parentNet = null;
                        }
                        // skip ambiguous nets, TODO: is there a better way to handle this?
                        String hierNetName = (parentNet != null)
                                ? parentNet.toString() : hierNet.toString();
                        if (!netName.equals(hierNetName)) {
                            continue;
                        }

                        // now compare against the net’s source endpoints at the same scope we used
                        // for naming. if this port instance is one of the sources, we treat this
                        // participant as the driver.
                        List<EDIFHierPortInst> sourcePortInsts =
                                ((parentNet != null) ? parentNet : hierNet)
                                        .getSourcePortInsts(true);
                        if (sourcePortInsts == null || sourcePortInsts.size() != 1) {
                            continue; // skip ambiguous nets
                        }
                        for (EDIFHierPortInst sourcePortInst : sourcePortInsts) {
                            if (sourcePortInst.equals(portInst)) {
                                driverName = instanceName;
                                driverPart = nameToPart.get(driverName);
                                break;
                            }
                        }
                        if (driverName != null) {
                            break;
                        }
                    }
                    if (driverName != null) {
                        break;
                    }
                }

                // if there is no driver that can be resolved (for example, tie-offs that are not
                // represented as a source), we skip this edge for the directional summary.
                // TODO: directional report may undercount? Silent drop of nets when driver 
                // resolution fails. Is this acceptable?
                if (driverName == null || driverPart == null) {
                    continue;
                }

                String driverLabel = PartitionLabel.indexToFpgaLabel(driverPart);

                // ============================================================================
                // STEP 2b
                // Accumulate a single count per destination partition for this signal.
                // ============================================================================
                Set<Integer> destinationPartitions = new HashSet<>();
                for (String instanceName : instNames) {
                    if (instanceName.equals(driverName)) {
                        continue;
                    }
                    Integer destinationPartition = nameToPart.get(instanceName);
                    if (destinationPartition == null) {
                        continue;
                    }
                    if (destinationPartition.intValue() == driverPart.intValue()) {
                        continue;
                    }
                    destinationPartitions.add(destinationPartition);
                }
                for (Integer destinationPartition : destinationPartitions) {
                    String sinkLabel = PartitionLabel.indexToFpgaLabel(destinationPartition);
                    String pairKey = driverLabel + "-->" + sinkLabel;
                    pairCounts.put(pairKey, pairCounts.getOrDefault(pairKey, 0) + 1);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // ============================================================================
        // STEP 3
        // Write io_cuts.txt in sorted order. Presenting entries deterministically makes it
        // much easier to review diffs between runs. Makes finding heavy traffic easier as well
        // in artifact.
        // ============================================================================
        List<String> lines = new ArrayList<>(pairCounts.size());
        List<String> keys = new ArrayList<>(pairCounts.keySet());
        Collections.sort(keys);
        for (String pairKey : keys) {
            int pairCount = pairCounts.get(pairKey);
            // each key is "fpga_x-->fpga_y"; we rewrite it as "fpga_x--N--fpga_y" where n is the
            // cut net count between them.
            String formatted = pairKey.replace("-->", "--" + pairCount + "--");
            lines.add(formatted);
        }

        Path ioCuts = outDir.resolve("io_cuts_directional.txt");
        try {
            Files.write(ioCuts, lines, StandardCharsets.UTF_8);
            PartitionTools.logPartitionerDebug(null, "Partitioner artifact written: %s",
                    ioCuts.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes io_cuts.txt (direction-agnostic) into outdir by scanning .hgr membership and
     * counting every pair of distinct partitions that share a net. for each unordered pair,
     * both permutations are written.
     *
     * ex:
     * fpga_b--20--fpga_a
     * fpga_a--20--fpga_b
     */
    private static void writeIoCutsUndirected(Path outDir
            , Path hgrFile
            , String[] instLookup
            , Map<String, Integer> nameToPart) {
        Map<String, Integer> pairCounts = new LinkedHashMap<>();

        // ============================================================================
        // STEP 1
        // Scan the hypergraph (.hgr) and count every pair of distinct partitions that share a net.
        // For each unordered pair, both permutations are written.
        // ============================================================================
        try (BufferedReader hgrReader = new BufferedReader(new FileReader(hgrFile.toFile()))) {
            String header = hgrReader.readLine(); // ignore header
            String line;
            while ((line = hgrReader.readLine()) != null) {
                String[] tokens = line.trim().isEmpty() ? new String[0] : line.trim().split("\\s+");
                Set<Integer> partitionIds = new HashSet<>();
                for (String t : tokens) {
                    if (t.isEmpty()) {
                        continue;
                    }
                    int vertexId;
                    try {
                        vertexId = Integer.parseInt(t);
                    } catch (NumberFormatException nfe) {
                        continue;
                    }
                    String instanceName = (vertexId >= 0 && vertexId < instLookup.length)
                            ? instLookup[vertexId] : null;
                    Integer part = (instanceName == null) ? null : nameToPart.get(instanceName);
                    if (part != null) {
                        partitionIds.add(part.intValue());
                    }
                }
                if (partitionIds.size() < 2) {
                    continue;
                }

                List<Integer> partitionIdList = new ArrayList<>(partitionIds);
                for (int i = 0; i < partitionIdList.size(); i++) {
                    for (int j = i + 1; j < partitionIdList.size(); j++) {
                        String partitionLabelA =
                                PartitionLabel.indexToFpgaLabel(partitionIdList.get(i));
                        String partitionLabelB =
                                PartitionLabel.indexToFpgaLabel(partitionIdList.get(j));
                        String pairKeyForward = partitionLabelA + "," + partitionLabelB;
                        String pairKeyBackward = partitionLabelB + "," + partitionLabelA;
                        pairCounts.put(pairKeyForward,
                                pairCounts.getOrDefault(pairKeyForward, 0) + 1);
                        pairCounts.put(pairKeyBackward,
                                pairCounts.getOrDefault(pairKeyBackward, 0) + 1);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // ============================================================================
        // STEP 2
        // Write io_cuts.txt in sorted order.
        // ============================================================================
        List<String> keys = new ArrayList<>(pairCounts.keySet());
        Collections.sort(keys);
        List<String> lines = new ArrayList<>(keys.size());
        for (String pairKey : keys) {
            int separatorIndex = pairKey.indexOf(',');
            String partitionLabelA = pairKey.substring(0, separatorIndex);
            String partitionLabelB = pairKey.substring(separatorIndex + 1);
            int pairCount = pairCounts.get(pairKey);
            lines.add(partitionLabelA + "--" + pairCount + "--" + partitionLabelB);
        }

        Path ioCuts = outDir.resolve("io_cuts.txt");
        try {
            Files.write(ioCuts, lines, StandardCharsets.UTF_8);
            PartitionTools.logPartitionerDebug(null, "Partitioner artifact written: %s",
                    ioCuts.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes io_cuts_directional.txt by recomputing edges from the netlist when .eidmap
     * is not available.
     *
     * Avoids relying on large eidmap files while still producing
     * the directional summary.
     */
    private static void writeDirectionalIoCutsNoEidmap(Path outDir
            , EDIFNetlist netlist
            , String[] instLookup
            , Map<String, Integer> nameToPart) {
        // ============================================================================
        // STEP 1
        // Reconstruct leaf set from instlookup.
        // ============================================================================
        Set<EDIFHierCellInst> leafSet = new HashSet<>();
        for (int i = 1; i < instLookup.length; i++) {
            String instanceName = instLookup[i];
            if (instanceName == null) {
                continue;
            }
            EDIFHierCellInst cellInst = netlist.getHierCellInstFromName(instanceName);
            if (cellInst != null) {
                leafSet.add(cellInst);
            }
        }

        // ============================================================================
        // STEP 2
        // Rebuild edges: parent-level net -> participating leaf instances.
        // ============================================================================
        Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap = new HashMap<>();
        for (EDIFHierCellInst cellInst : leafSet) {
            for (EDIFHierPortInst portInst : cellInst.getHierPortInsts()) {
                EDIFHierNet hierNet = portInst.getHierarchicalNet();
                if (hierNet == null) {
                    continue;
                }
                EDIFHierNet parentNet = null;
                try {
                    parentNet = netlist.getParentNet(hierNet);
                } catch (RuntimeException ex) {
                    parentNet = null;
                }
                // skip ambiguous nets
                EDIFHierNet netKey = (parentNet != null) ? parentNet : hierNet;
                if (edgesMap.containsKey(netKey)) {
                    continue;
                }
                edgesMap.put(netKey, hierNet.getConnectedInsts(leafSet));
            }
        }

        Map<String, Integer> pairCounts = new LinkedHashMap<>();

        // ============================================================================
        // STEP 3
        // Process edges to find drivers and accumulate counts.
        // ============================================================================
        for (Map.Entry<EDIFHierNet, Set<EDIFHierCellInst>> edgeEntry : edgesMap.entrySet()) {
            List<String> instNames = new ArrayList<>(edgeEntry.getValue().size());
            for (EDIFHierCellInst cellInst : edgeEntry.getValue()) {
                instNames.add(cellInst.toString());
            }
            if (instNames.isEmpty()) {
                continue;
            }

            // find driver by matching source endpoints on the same parent net
            String driverName = null;
            Integer driverPart = null;
            for (String instanceName : instNames) {
                EDIFHierCellInst inst = netlist.getHierCellInstFromName(instanceName);
                if (inst == null) {
                    continue;
                }
                for (EDIFHierPortInst portInst : inst.getHierPortInsts()) {
                    EDIFHierNet hierNet = portInst.getHierarchicalNet();
                    if (hierNet == null) {
                        continue;
                    }
                    EDIFHierNet parentNet = null;
                    try {
                        parentNet = netlist.getParentNet(hierNet);
                    } catch (RuntimeException ex) {
                        parentNet = null;
                    }
                    // skip ambiguous nets
                    EDIFHierNet netRef = (parentNet != null) ? parentNet : hierNet;
                    if (netRef != edgeEntry.getKey()) {
                        continue;
                    }

                    List<EDIFHierPortInst> sourcePortInsts = netRef.getSourcePortInsts(true);
                    if (sourcePortInsts == null || sourcePortInsts.size() != 1) {
                        continue; // skip ambiguous nets
                    }
                    for (EDIFHierPortInst sourcePortInst : sourcePortInsts) {
                        if (sourcePortInst.equals(portInst)) {
                            driverName = instanceName;
                            driverPart = nameToPart.get(driverName);
                            break;
                        }
                    }
                    if (driverName != null) {
                        break;
                    }
                }
                if (driverName != null) {
                    break;
                }
            }
            if (driverName == null || driverPart == null) {
                continue;
            }

            String driverLabel = PartitionLabel.indexToFpgaLabel(driverPart);

            Set<Integer> destinationPartitions = new HashSet<>();
            for (String instanceName : instNames) {
                if (instanceName.equals(driverName)) {
                    continue;
                }
                Integer destinationPartition = nameToPart.get(instanceName);
                if (destinationPartition == null) {
                    continue;
                }
                if (destinationPartition.intValue() == driverPart.intValue()) {
                    continue;
                }
                destinationPartitions.add(destinationPartition);
            }
            for (Integer destinationPartition : destinationPartitions) {
                String sinkLabel = PartitionLabel.indexToFpgaLabel(destinationPartition);
                String pairKey = driverLabel + "-->" + sinkLabel;
                pairCounts.put(pairKey, pairCounts.getOrDefault(pairKey, 0) + 1);
            }
        }

        // ============================================================================
        // STEP 4
        // Write io_cuts_directional.txt in sorted order.
        // ============================================================================
        List<String> keys = new ArrayList<>(pairCounts.keySet());
        Collections.sort(keys);
        List<String> lines = new ArrayList<>(keys.size());
        for (String pairKey : keys) {
            int pairCount = pairCounts.get(pairKey);
            String formatted = pairKey.replace("-->", "--" + pairCount + "--");
            lines.add(formatted);
        }

        Path ioCuts = outDir.resolve("io_cuts_directional.txt");
        try {
            Files.write(ioCuts, lines, StandardCharsets.UTF_8);
            PartitionTools.logPartitionerDebug(null, "Partitioner artifact written: %s",
                    ioCuts.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes detailed nets.txt using existing partitiontools logic.
     */
    private static void writeDetailedNetsReport(Path hgrFile
            , Path eidmapFile
            , String[] instLookup
            , Map<String, Integer> nameToPart
            , Path netsOut) {
        try {
            PartitionTools.writeConnectivityReportWithNames(hgrFile,
                    eidmapFile,
                    instLookup,
                    nameToPart,
                    netsOut);
            PartitionTools.logPartitionerDebug(null, "Partitioner artifact written: %s",
                    netsOut.toString());
        } catch (UncheckedIOException ex) {
            PartitionTools.logPartitionerDebug(null,
                    "WARNING: failed to write nets report: %s",
                    ex.getMessage());
        }
    }
}
