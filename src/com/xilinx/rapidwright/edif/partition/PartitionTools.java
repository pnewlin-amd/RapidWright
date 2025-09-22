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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;

/**
 * Collection of methods used to export, analyze and parse hMETIS-based
 * partitioning problems and solutions from RapidWright Netlists.
 */
public class PartitionTools {

    // debug counters for lut count behavior
    private static long dbgCacheHits = 0;
    private static long dbgRecursiveCalls = 0;
    private static long dbgInstMapWrites = 0;
    private static int dbgCacheHitLogBudget = 10;

    /**
     * Calculates the number of LUTs in a given hierarchical cell instance.
     * 
     * @param inst    The hierarchical cell instance to calculate a LUT count.
     * @param map     A caching map to avoid recalculating LUTs for each cell type.
     * @param instMap A map that keeps track of all hierarchical instances and their
     *                LUT counts.
     * @return The number of LUTs in the hierarchical cell instance.
     */
    public static Integer getLUTCount(EDIFHierCellInst inst, Map<EDIFCell, Integer> map,
            Map<EDIFHierCellInst, Integer> instMap) {
        Integer totalLUTs = 0;
        for (EDIFCellInst i : inst.getCellType().getCellInsts()) {
            if (i.getCellType().isLeafCellOrBlackBox()) {
                totalLUTs += i.getCellType().getName().contains("LUT") ? 1 : 0;
            } else if (map.containsKey(i.getCellType())) {
                dbgCacheHits++; // count cell-type cache usage
                if (dbgCacheHitLogBudget > 0) {
                    System.err.printf("PARTITIONER DEBUG: cache hit -> parentPath=%s childInst=%s cellType=%s childPath=%s%n",
                            inst.toString(), i.getName(), i.getCellType().getName(), inst.getChild(i).toString()); // shows where cache prevents dfs so child instance path may not be recorded
                    dbgCacheHitLogBudget--;
                }
                totalLUTs += map.get(i.getCellType());
            } else {
                dbgRecursiveCalls++; // count recursion when cache miss
                totalLUTs += getLUTCount(inst.getChild(i), map, instMap);
            }
        }
        Integer prevCount = map.put(inst.getCellType(), totalLUTs);
        if (prevCount != null && !prevCount.equals(totalLUTs)) {
            throw new RuntimeException("ERROR: Inconsistent netlist");
        }
        instMap.put(inst, totalLUTs);
        dbgInstMapWrites++; // count inst map writes
    
        return totalLUTs;
    }

    /**
     * A method to select coarser-grained leaves for the netlist being presented to
     * the partitioner by identifying hierarchical cells that are of a certain LUT
     * count.
     * 
     * @param netlist  The current netlist considered for partitioning.
     * @param lutCount The LUT count threshold under which hierarchical cells should
     *                 be considered as leaves in the partitioner-presented graph.
     * @return A map of all coarsened leaf hierarchical instances mapped to a unique
     *         index.
     */
    public static Map<EDIFHierCellInst, Integer> identifyLeafInstances(EDIFNetlist netlist, int lutCount, 
            Map<EDIFHierCellInst, Integer> instMap) {
        EDIFHierCellInst topInst = netlist.getTopHierCellInst();
        Map<EDIFCell, Integer> lutCountMap = new HashMap<>();
        // reset counters
        dbgCacheHits = 0; dbgRecursiveCalls = 0; dbgInstMapWrites = 0; dbgCacheHitLogBudget = 10;
        getLUTCount(topInst, lutCountMap, instMap);
        // count null lut size decisions
        int dbgNullLUTCountDecisions = 0;

        Map<EDIFHierCellInst, Integer> leafInsts = new HashMap<>();
        Queue<EDIFHierCellInst> q = new LinkedList<>();
        q.add(topInst);
        while (!q.isEmpty()) {
            EDIFHierCellInst curr = q.poll();
            Integer lutSize = instMap.get(curr);
            // track null lut size
            if (lutSize == null) {
                dbgNullLUTCountDecisions++;
            }
            if (lutSize == null || lutSize <= lutCount) {
                leafInsts.put(curr, leafInsts.size()+1);
                continue;
            }
            assert (lutSize > lutCount);
            for (EDIFCellInst child : curr.getCellType().getCellInsts()) {
                q.add(curr.getChild(child));
            }
        }
    
        System.out.printf("PARTITIONER DEBUG: lutCount stats -> cacheHits=%d recursiveCalls=%d instMapWrites=%d uniqueCells=%d instMapSize=%d%n",
                dbgCacheHits, dbgRecursiveCalls, dbgInstMapWrites, lutCountMap.size(), instMap.size()); // reports cache vs recursion usage and instance map size to explain instmap coverage
        System.out.printf("PARTITIONER DEBUG: identifyLeafInstances -> leaves=%d nullLUTCountDecisions=%d%n",
                leafInsts.size(), dbgNullLUTCountDecisions); // reports leaf count and frequency of null lut size during bfs to validate null-as-leaf misclassification
        return leafInsts;
    }
    
    /**
     * Create an array of cell instance string names such that the strings are
     * stored at their respective index.
     * 
     * @param leafInsts The map of cell instances to their assigned index.
     * @return The string array of cell instance names stored at their assigned
     *         index.
     */
    public static String[] createInstLookupArray(Map<EDIFHierCellInst, Integer> leafInsts) {
        String[] lookup = new String[leafInsts.size() + 1];
        lookup[0] = null;
        for (Entry<EDIFHierCellInst, Integer> e : leafInsts.entrySet()) {
            lookup[e.getValue()] = e.getKey().toString();
        }
        return lookup;
    }

    /**
     * Writes out instance names to integers to store the enumerated mapping.
     * 
     * @param mappingFile A file that maps index to instance name (one per line) to
     *                    the partitioned solution can be decoded.
     * @param leafInsts   Current enumeration map for the instances used.
     */
    public static void writeInstMappingFile(Path mappingFile, String[] instLookup) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(mappingFile.toFile()))) {
            bw.write(instLookup.length + "\n");
            for (int i = 1; i < instLookup.length; i++) {
                bw.write(instLookup[i] + "\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }    
    }

    /**
     * Creates the hMETIS formatted problem file for the partitioner. This follows
     * conventional hMETIS file format.
     * 
     * @param filePath  Path to the output file.
     * @param edgesMap  Map of edges to be included in the output file.
     * @param leafInsts Map of nodes or leaves to be included in the output file.
     */
    public static void writeHMetisFile(Path filePath, Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap, 
            Map<EDIFHierCellInst, Integer> leafInsts, boolean writeEidmap) { 
        // Derive .eidmap path alongside .hgr (replace .hgr suffix if present)
        Path eidmapPath;
        String base = filePath.toString();
        if (base.endsWith(".hgr")) {
            eidmapPath = Paths.get(base.substring(0, base.length() - 4) + ".eidmap");
        } else {
            eidmapPath = Paths.get(base + ".eidmap");
        }
        if (writeEidmap) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(filePath.toFile()));
                 BufferedWriter emw = new BufferedWriter(new FileWriter(eidmapPath.toFile()))) {
                // <total edge count> <total node count>
                bw.write(edgesMap.size() + " " + leafInsts.size() + "\n");
                int unnamedCounter = 0; // assign unique ids for unnamed nets
                for (Entry<EDIFHierNet, Set<EDIFHierCellInst>> e : edgesMap.entrySet()) {
                    // write net name safely even if key is null
                    String netName = (e.getKey() == null) ? "<unnamed_net_" + (++unnamedCounter) + ">" : e.getKey().toString();
                    emw.write(netName);
                    emw.write("\n");
                    for (EDIFHierCellInst i : e.getValue()) {
                        Integer nodeIdx = leafInsts.get(i);
                        if (nodeIdx == null) {
                            throw new RuntimeException(
                                    "ERROR: Inconsistent netlist, cannot export .hgr.");
                        }
                        bw.write(nodeIdx + " ");
                    }
                    bw.write("\n");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(filePath.toFile()))) {
                // <total edge count> <total node count>
                bw.write(edgesMap.size() + " " + leafInsts.size() + "\n");
                for (Entry<EDIFHierNet, Set<EDIFHierCellInst>> e : edgesMap.entrySet()) {
                    for (EDIFHierCellInst i : e.getValue()) {
                        Integer nodeIdx = leafInsts.get(i);
                        if (nodeIdx == null) {
                            throw new RuntimeException(
                                    "ERROR: Inconsistent netlist, cannot export .hgr.");
                        }
                        bw.write(nodeIdx + " ");
                    }
                    bw.write("\n");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Reads the output of the partitioner and creates a map of the partitions using
     * cell instance names.
     * 
     * @param fileName     The file generated from the partitioner to read in.
     * @param nameMappings The cell instances names stored by their assigned index.
     * @return A map keyed by partition index to the respective set of cell instance
     *         names to be included in that partition.
     */
    public static Map<Integer, Set<String>> readSolutionFile(Path solutionFile, String[] instLookup) {
        Map<Integer, Set<String>> partitions = new HashMap<>();
    
        try (BufferedReader br = new BufferedReader(new FileReader(solutionFile.toFile()))) {
            String line = null;
            int i = 1;
            while ((line = br.readLine()) != null) {
                int part = Integer.parseInt(line.trim());
                Set<String> partition = partitions.computeIfAbsent(part, s -> new HashSet<>());
                String name = instLookup[i];
                if (!partition.add(name)) {
                    System.err.println("Found duplicate entry in partition file: " + line);
                }
                i++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    
        return partitions;
    }

    /**
     * Writes a human-readable connectivity report with net names using:
     *  - .hgr         (edge -> list of vertex IDs)
     *  - .eidmap      (edge ID -> hierarchical net name)
     *  - instLookup   (vertex ID -> hierarchical instance name)
     *  - nameToPart   (instance name -> partition index)
     *
     * Output format (for each net/edge):
     *   Net <id>: <net_name>
     *     cut=<true|false> partitions={p0:cnt0, p1:cnt1, ...}
     *     <vid>: <instance_name> p=<partition>
     */
    public static void writeConnectivityReportWithNames(Path hgrFile,
                                                       Path eidmapFile,
                                                       String[] instLookup,
                                                       Map<String, Integer> nameToPart,
                                                       Path netsOut) {
        try (BufferedReader hgr = new BufferedReader(new FileReader(hgrFile.toFile()));
             BufferedReader eid = new BufferedReader(new FileReader(eidmapFile.toFile()));
             BufferedWriter out = new BufferedWriter(new FileWriter(netsOut.toFile()))) {

            // Load eidmap: 1-based edge IDs
            ArrayList<String> netNames = new ArrayList<>();
            String line;
            while ((line = eid.readLine()) != null) {
                netNames.add(line);
            }

            // Read header line from .hgr
            String header = hgr.readLine(); // may be "E V" or "E V fmt"
            int edgeIdx = 0;

            while ((line = hgr.readLine()) != null) {
                edgeIdx++;
                String netName = edgeIdx <= netNames.size() ? netNames.get(edgeIdx - 1)
                        : ("<edge_" + edgeIdx + ">");

                String[] toks = line.trim().isEmpty() ? new String[0] : line.trim().split("\\s+");
                Map<Integer, Integer> partCounts = new HashMap<>();
                ArrayList<String> members = new ArrayList<>();

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
                    int p = (part == null) ? -1 : part.intValue();
                    partCounts.put(p, partCounts.getOrDefault(p, 0) + 1);
                    String plabel = (p >= 0) ? PartitionLabel.indexToFpgaLabel(p) : "UNASSIGNED";
                    members.add(String.format("  %d: %s part=%s", vid, instName, plabel));
                }
                boolean cut = partCounts.size() > 1;

                out.write(String.format("Net %d: %s%n", edgeIdx, netName));
                //print blocks with pin counts
                StringBuilder desc = new StringBuilder();
                desc.append("  cut=").append(cut ? "true" : "false").append(" blocks: ");
                if (partCounts.isEmpty()) {
                    desc.append("none");
                } else {
                    java.util.List<java.util.Map.Entry<Integer,Integer>> es = new java.util.ArrayList<>(partCounts.entrySet());
                    es.sort((a,b) -> Integer.compare(a.getKey(), b.getKey())); // sort by block id
                    for (int i = 0; i < es.size(); i++) {
                        int bid = es.get(i).getKey();
                        int cnt = es.get(i).getValue();
                        String blabel = (bid >= 0) ? PartitionLabel.indexToFpgaLabel(bid) : "UNASSIGNED";
                        desc.append(blabel).append("=").append(cnt).append(" ").append(cnt == 1 ? "pin" : "pins");
                        if (i + 1 < es.size()) desc.append(", ");
                    }
                }
                //note: 'blocks' lists how many pins of this net are inside each partition
                //note: pn is the partition id (for example p0 means partition 0)
                //note: e.g. 'p0=1 pin, p1=1 pin' means one pin in p0 and one pin in p1; 'p6=2 pins' means two pins in p6
                out.write(desc.toString());
                out.write("\n");
                for (String m : members) {
                    out.write(m);
                    out.write("\n");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
