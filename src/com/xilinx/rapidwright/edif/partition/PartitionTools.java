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

    /** Normalize the optional emitted top-cell prefix to RapidWright's relative path. */
    public static String normalizeConstraintPath(EDIFNetlist netlist, String path) {
        String top = netlist.getTopCell().getName();
        if (path.equals(top)) { return ""; }
        return path.startsWith(top + "/") ? path.substring(top.length() + 1) : path;
    }

    /** Split any coarse vertex containing a deeper fixed constraint, then assign stable IDs.
     * A constraint on one child must never silently fix all its unconstrained siblings.
     */
    public static Map<EDIFHierCellInst, Integer> alignFrontierToConstraints(EDIFNetlist netlist,
            Map<EDIFHierCellInst, Integer> frontier, Path constraintsFile) {
        Set<String> paths = new HashSet<>();
        SourceConstraintPrefixes sourcePrefixes = new SourceConstraintPrefixes(netlist);
        if (constraintsFile != null) {
            try (BufferedReader reader = new BufferedReader(new FileReader(constraintsFile.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty() || line.trim().startsWith("#")) { continue; }
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length != 2) { throw new IllegalArgumentException("Invalid constraint: " + line); }
                    String path = normalizeConstraintPath(netlist, fields[0]);
                    if (!path.isEmpty() && !sourcePrefixes.exists(path)) {
                        throw new IllegalArgumentException("Unknown constraint hierarchy path: " + fields[0]);
                    }
                    paths.add(path);
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        HierarchyConstraintIndex constraintIndex = HierarchyConstraintIndex.paths(paths);
        Queue<EDIFHierCellInst> queue = new LinkedList<>(frontier.keySet());
        java.util.TreeMap<String, EDIFHierCellInst> ordered = new java.util.TreeMap<>();
        while (!queue.isEmpty()) {
            EDIFHierCellInst inst = queue.remove();
            String name = inst.toString();
            boolean split = constraintIndex.hasStrictDescendant(name);
            if (split && !inst.getCellType().getLibrary().isHDIPrimitivesLibrary()
                    && !inst.getCellType().isLeafCellOrBlackBox()) {
                for (EDIFCellInst child : inst.getCellType().getCellInsts()) { queue.add(inst.getChild(child)); }
            } else {
                EDIFHierCellInst prior = ordered.put(name, inst);
                if (prior != null && !prior.equals(inst)) {
                    throw new IllegalArgumentException("Ambiguous source hierarchy path: " + name);
                }
            }
        }
        Map<EDIFHierCellInst, Integer> result = new java.util.LinkedHashMap<>();
        for (EDIFHierCellInst inst : ordered.values()) { result.put(inst, result.size() + 1); }
        return result;
    }


    /**
     * Helper to print partitioner debug messages with a consistent prefix.
     * @param stream Output stream for debug messages
     * @param format Format string for the message
     * @param args Arguments for the format string
     */
    public static void logPartitionerDebug(java.io.PrintStream stream, String format, 
            Object... args) {
        if (stream == null) {
            stream = System.out;
        }
        stream.printf("PARTITIONER DEBUG: " + format + "%n", args);
    }

    /**
     * Calculates LUT count for hierarchical instance.
     * @param hierInst Hierarchical cell instance
     * @param cellTypeCache Cell type cache
     * @param instMap Instance map
     * @return LUT count
     */
    public static Integer getLUTCount(EDIFHierCellInst hierInst,
            Map<EDIFCell, Integer> cellTypeCache,
            Map<EDIFHierCellInst, Integer> instMap) {
        return LUTCountingUtils.computeLUTCount(hierInst, cellTypeCache, instMap);
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
    public static Map<EDIFHierCellInst, Integer> identifyLeafInstances(EDIFNetlist netlist,
            int lutCount, 
            Map<EDIFHierCellInst, Integer> instMap) {
        EDIFHierCellInst topInst = netlist.getTopHierCellInst();
        Map<EDIFCell, Integer> lutCountMap = new HashMap<>();
        LUTCountingUtils.dbgCacheHits = 0;
        LUTCountingUtils.dbgRecursiveCalls = 0;
        LUTCountingUtils.dbgInstMapWrites = 0;
        LUTCountingUtils.dbgCacheHitInstMapWrites = 0;
        LUTCountingUtils.dbgCacheHitLogBudget = 10;
        logPartitionerDebug(System.err,
                "identifyLeafInstances start -> topInst=%s lutCountThreshold=%d",
                topInst.toString(), lutCount);
        getLUTCount(topInst, lutCountMap, instMap);
        logPartitionerDebug(System.err,
                "getLUTCount complete -> topLUTs=%d instMapSize=%d lutCountMapSize=%d "
                + "cacheHitInstMapWrites=%d",
                instMap.get(topInst), instMap.size(), lutCountMap.size(),
                LUTCountingUtils.dbgCacheHitInstMapWrites);
        int dbgNullLUTCountDecisions = 0;
        int dbgNullButHier = 0;
        int dbgNullButLeaf = 0;

        Map<EDIFHierCellInst, Integer> leafInsts = new HashMap<>();
        Queue<EDIFHierCellInst> instQueue = new LinkedList<>();
        instQueue.add(topInst);
        while (!instQueue.isEmpty()) {
            EDIFHierCellInst currentInst = instQueue.poll();

            //A null here means LUTCounting did not populate instMap for this instance
            //we MUST still make a coarsening decision, log and treat as eligible 
            //leaf vertex.
            Integer lutSize = instMap.get(currentInst);
            if (lutSize == null) {
                //debug only, track case where LUT size is missing
                //but we had to decide if leaf or to descend
                dbgNullLUTCountDecisions++;
                if (currentInst.getCellType().isLeafCellOrBlackBox()) {
                    dbgNullButLeaf++;
                    if (dbgNullButLeaf <= 10) {
                        logPartitionerDebug(System.err, 
                            "null LUT count (leaf) -> instPath=%s cellType=%s isLeaf=%s",
                            currentInst.toString(), currentInst.getCellType().getName(), 
                            currentInst.getCellType().isLeafCellOrBlackBox());
                    }
                //split up debug logs so we can see if missing LUT counts are on primitive leaf cells 
                //or on hierarchical (Which is not a leaf) cell.
                } else {
                    dbgNullButHier++;
                    if (dbgNullButHier <= 10) {
                        logPartitionerDebug(System.err, 
                            "null LUT count (hier) -> instPath=%s cellType=%s depth=%d " 
                            + "childCount=%d",
                            currentInst.toString(), currentInst.getCellType().getName(), 
                            currentInst.getDepth(), 
                            currentInst.getCellType().getCellInsts().size());
                    }
                }
            }
            // Zero-LUT hierarchy may contain thousands of FFs or RAMs. Descend
            // instead of treating it as a free, indivisible resource bundle.
            if (currentInst.getCellType().isLeafCellOrBlackBox()
                    || (lutSize != null && lutSize > 0 && lutSize <= lutCount)) {
                leafInsts.put(currentInst, leafInsts.size() + 1);
                continue;
            }
            if (lutSize == null) {
                throw new IllegalStateException("Missing hierarchical LUT count: " + currentInst);
            }
            for (EDIFCellInst childInst : currentInst.getCellType().getCellInsts()) {
                instQueue.add(currentInst.getChild(childInst));
            }
        }
    
        logPartitionerDebug(System.out,
                "lutCount stats -> cacheHits=%d recursiveCalls=%d instMapWrites=%d "
                + "cacheHitInstMapWrites=%d uniqueCells=%d instMapSize=%d",
                LUTCountingUtils.dbgCacheHits, LUTCountingUtils.dbgRecursiveCalls,
                LUTCountingUtils.dbgInstMapWrites, LUTCountingUtils.dbgCacheHitInstMapWrites,
                lutCountMap.size(), instMap.size());
        logPartitionerDebug(System.out,
                "identifyLeafInstances -> leaves=%d nullLUTCountDecisions=%d nullButHier=%d "
                + "nullButLeaf=%d",
                leafInsts.size(), dbgNullLUTCountDecisions, dbgNullButHier, dbgNullButLeaf);
        logPartitionerDebug(System.err,
                "fix validation -> cacheHits=%d cacheHitInstMapWrites=%d shouldMatch=%s "
                + "subtreePopulations=%d subtreeInstances=%d",
                LUTCountingUtils.dbgCacheHits, LUTCountingUtils.dbgCacheHitInstMapWrites,
                (LUTCountingUtils.dbgCacheHits == LUTCountingUtils.dbgCacheHitInstMapWrites),
                LUTCountingUtils.dbgSubtreePopulations, LUTCountingUtils.dbgSubtreeInstances);
        return leafInsts;
    }
    
    /**
     * Create an array of cell instance string names such that the strings are
     * stored at their respective index.
     * @param leafInsts The map of cell instances to their assigned index
     * @return String array of instance names at assigned indices
     */
    public static String[] createInstLookupArray(Map<EDIFHierCellInst, Integer> leafInsts) {
        String[] lookup = new String[leafInsts.size() + 1];
        lookup[0] = null;
        for (Entry<EDIFHierCellInst, Integer> entry : leafInsts.entrySet()) {
            lookup[entry.getValue()] = entry.getKey().toString();
        }
        return lookup;
    }

    /**
     * Writes out instance names to integers to store the enumerated mapping.
     * @param mappingFile File that maps index to instance name
     * @param instLookup Current enumeration map for instances
     */
    public static void writeInstMappingFile(Path mappingFile, String[] instLookup) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(mappingFile.toFile()))) {
            writer.write(instLookup.length + "\n");
            for (int index = 1; index < instLookup.length; index++) {
                writer.write(instLookup[index] + "\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }    
    }

    /**
     * Creates the hMETIS formatted problem file for the partitioner. This follows
     * conventional hMETIS file format.
     * @param filePath Path to the output .hgr file
     * @param edgesMap Map of edges to include
     * @param leafInsts Map of nodes to include
     * @param writeEidmap Whether to write edge ID mapping file
     */
    public static void writeHMetisFile(Path filePath, 
            Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap, 
            Map<EDIFHierCellInst, Integer> leafInsts, boolean writeEidmap) {
        writeHMetisFile(filePath, edgesMap, leafInsts, writeEidmap, null);
    }

    /**
     * Creates the hMETIS formatted problem file for the partitioner. This follows
     * conventional hMETIS file format.
     * @param filePath Path to the output .hgr file
     * @param edgesMap Map of edges to include
     * @param leafInsts Map of nodes to include
     * @param writeEidmap Whether to write edge ID mapping file
     * @param instLutCountMap Map of instance LUT counts for vertex weights
     */
    public static void writeHMetisFile(Path filePath, 
            Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap, 
            Map<EDIFHierCellInst, Integer> leafInsts, boolean writeEidmap,
            Map<EDIFHierCellInst, Integer> instLutCountMap) {
        writeHMetisFile(filePath, edgesMap, leafInsts, writeEidmap, instLutCountMap, null);
    }

    /** Optional calibrated scalar weights do not change the logical resource census. */
    public static void writeHMetisFile(Path filePath,
            Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap,
            Map<EDIFHierCellInst, Integer> leafInsts, boolean writeEidmap,
            Map<EDIFHierCellInst, Integer> instLutCountMap, int[] vertexWeights) {
        writeHMetisFile(filePath, edgesMap, leafInsts, writeEidmap, instLutCountMap, vertexWeights, null);
    }

    /** Edge weights follow edgesMap iteration order and never change census/cut counts. */
    public static void writeHMetisFile(Path filePath,
            Map<EDIFHierNet, Set<EDIFHierCellInst>> edgesMap,
            Map<EDIFHierCellInst, Integer> leafInsts, boolean writeEidmap,
            Map<EDIFHierCellInst, Integer> instLutCountMap, int[] vertexWeights, int[] edgeWeights) {
        if (edgeWeights != null && edgeWeights.length != edgesMap.size()) {
            throw new IllegalArgumentException("Edge weight count differs from hypergraph");
        }
        if (edgeWeights != null) {
            for (int weight : edgeWeights) {
                if (weight <= 0) throw new IllegalArgumentException("Hyperedge weights must be positive");
            }
        }
        if (vertexWeights != null && vertexWeights.length != leafInsts.size() + 1) {
            throw new IllegalArgumentException("Vertex weight count differs from frontier");
        }
        // Derive .eidmap path alongside .hgr (replace .hgr suffix if present)
        Path eidmapPath;
        String base = filePath.toString();
        if (base.endsWith(".hgr")) {
            eidmapPath = Paths.get(base.substring(0, base.length() - 4) + ".eidmap");
        } else {
            eidmapPath = Paths.get(base + ".eidmap");
        }
        
        boolean useWeights = instLutCountMap != null || vertexWeights != null;
        long totalWeight = 0;
        if (useWeights) {
            for (Entry<EDIFHierCellInst, Integer> entry : leafInsts.entrySet()) {
                EDIFHierCellInst inst = entry.getKey();
                int weight = vertexWeights == null ? (inst.getCellType().isLeafCellOrBlackBox()
                        ? logicDiscoveryPolicy.leafLutCount(inst)
                        : instLutCountMap.getOrDefault(inst, 0)) : vertexWeights[entry.getValue()];
                if (weight < 0) { throw new IllegalArgumentException("Negative vertex weight"); }
                totalWeight += weight;
            }
        }
        if (vertexWeights != null && totalWeight == 0) {
            throw new IllegalArgumentException("Custom vertex weights have zero total");
        }
        boolean unitWeightFallback = useWeights && totalWeight == 0;
        
        try (BufferedWriter hgrWriter = new BufferedWriter(new FileWriter(filePath.toFile()));
             BufferedWriter eidmapWriter = writeEidmap ? new BufferedWriter(
                 new FileWriter(eidmapPath.toFile())) : null) {
            
            /**
             * <total edge count> <total node count> [fmt]
             * 
             * fmt = 10 is absolutely required in order to produce balanced designs
             * >10,000,000LUTs. when fmt is omitted, hMETIS standard tells us that each
             * vertex has a weight of 1. This is problematic with large designs because a 
             * single vertex could represent hundreds of thousands of LUTs leading to unbalanced
             * partitions at the scale which routing no longer becomes possible. Changing 
             * the header to include fmt=10 allows partitioning to factor in vertex weights.
             */
            if (useWeights || edgeWeights != null) {
                hgrWriter.write(edgesMap.size() + " " + leafInsts.size() + " "
                        + ((useWeights ? 10 : 0) + (edgeWeights == null ? 0 : 1)) + "\n");
            } else {
                hgrWriter.write(edgesMap.size() + " " + leafInsts.size() + "\n");
            }
            
            int unnamedCounter = 0; // assign unique ids for unnamed nets
            int edgeIndex = 0;
            for (Entry<EDIFHierNet, Set<EDIFHierCellInst>> entry : edgesMap.entrySet()) {
                if (edgeWeights != null) hgrWriter.write(edgeWeights[edgeIndex++] + " ");
                if (writeEidmap) {
                    // Write net name safely even if key is null
                    // Might be a better way to resolve this?
                    String netName = (entry.getKey() == null) ? 
                        "<unnamed_net_" + (++unnamedCounter) + ">" : entry.getKey().toString();
                    eidmapWriter.write(netName);
                    eidmapWriter.write("\n");
                }
                for (EDIFHierCellInst hierInst : entry.getValue()) {
                    Integer nodeIdx = leafInsts.get(hierInst);
                    if (nodeIdx == null) {
                        throw new RuntimeException(
                                "ERROR: Inconsistent netlist, cannot export .hgr.");
                    }
                    hgrWriter.write(nodeIdx + " ");
                }
                hgrWriter.write("\n");
            }
            
            // Write vertex weights if enabled
            if (useWeights) {
                // Create an array to order vertices by index (1-based)
                EDIFHierCellInst[] orderedInsts = new EDIFHierCellInst[leafInsts.size() + 1];
                for (Entry<EDIFHierCellInst, Integer> entry : leafInsts.entrySet()) {
                    orderedInsts[entry.getValue()] = entry.getKey();
                }
                
                for (int i = 1; i <= leafInsts.size(); i++) {
                    EDIFHierCellInst inst = orderedInsts[i];
                    Integer weight = null;
                    if (vertexWeights != null) {
                        weight = vertexWeights[i];
                    } else if (inst.getCellType().isLeafCellOrBlackBox()) {
                        weight = logicDiscoveryPolicy.leafLutCount(inst);
                    } else {
                        weight = instLutCountMap.get(inst);
                    }
                    
                    // Keep genuine zero LUT weights. Use units only when the entire
                    // design has no LUTs, avoiding a zero-total-weight backend problem.
                    int w = vertexWeights != null ? vertexWeights[i]
                            : (unitWeightFallback ? 1 : (weight == null ? 0 : weight.intValue()));
                    hgrWriter.write(w + "\n");
                }
            }
            
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads the output of the partitioner and creates a map of the partitions using
     * cell instance names.
     * @param solutionFile The partitioner output file to read
     * @param instLookup Cell instance names by assigned index
     * @return Map of partition index to instance names
     */
    public static Map<Integer, Set<String>> readSolutionFile(Path solutionFile, 
            String[] instLookup) {
        Map<Integer, Set<String>> partitions = new HashMap<>();
    
        try (BufferedReader reader = new BufferedReader(new FileReader(solutionFile.toFile()))) {
            String line = null;
            int lineIndex = 1;
            while ((line = reader.readLine()) != null) {
                int partitionId = Integer.parseInt(line.trim());
                Set<String> set = partitions.computeIfAbsent(partitionId, s -> new HashSet<>());
                String name = instLookup[lineIndex];
                if (!set.add(name)) {
                    System.err.println("Found duplicate entry in partition file: " + line);
                }
                lineIndex++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    
        return partitions;
    }

    /**
     * Writes a human-readable connectivity report with net names using:
     *  - .hgr         edge -> list of vertex IDs
     *  - .eidmap      edge ID -> net name
     *  - instLookup   vertex ID -> instance name
     *  - nameToPart   instance name -> partition index
     *
     * output for every net and edge
     *   <net_id> : <net_name>
     *     cut=<true|false> partitions={p0:cnt0, p1:cnt1, ...}
     *     <vid>: <instance_name> p=<partition>
     * @param hgrFile Path to hypergraph file
     * @param eidmapFile Path to edge ID mapping file
     * @param instLookup Vertex ID to instance name mapping
     * @param nameToPart Instance name to partition mapping
     * @param netsOut Output path for connectivity report
     */
    public static void writeConnectivityReportWithNames(Path hgrFile, Path eidmapFile, String[] 
            instLookup, Map<String, Integer> nameToPart, Path netsOut) {
        try (BufferedReader hgrReader = new BufferedReader(new FileReader(hgrFile.toFile()));
             BufferedReader eidmapReader = new BufferedReader(new FileReader(eidmapFile.toFile()));
             BufferedWriter reportWriter = new BufferedWriter(new FileWriter(netsOut.toFile()))) {

            // Load eidmap: 1-based edge IDs
            ArrayList<String> netNames = new ArrayList<>();
            String line;
            while ((line = eidmapReader.readLine()) != null) {
                netNames.add(line);
            }

            // Only the edge section describes nets. In fmt1/11 the first
            // integer is an edge weight; fmt10/11 vertex weights follow E edges.
            String header = readHypergraphRecord(hgrReader);
            if (header == null) throw new IllegalArgumentException("Empty hypergraph");
            String[] fields = header.trim().split("\\s+");
            if (fields.length < 2) throw new IllegalArgumentException("Invalid hMETIS header");
            int edgeCount = Integer.parseInt(fields[0]);
            int vertexCount = Integer.parseInt(fields[1]);
            int format = fields.length > 2 ? Integer.parseInt(fields[2]) : 0;
            if (edgeCount < 0 || vertexCount != instLookup.length - 1
                    || (format != 0 && format != 1 && format != 10 && format != 11)) {
                throw new IllegalArgumentException("Invalid/unsupported hypergraph dimensions or format");
            }
            for (int edgeIdx = 1; edgeIdx <= edgeCount; edgeIdx++) {
                line = readHypergraphRecord(hgrReader);
                if (line == null) throw new IllegalArgumentException("Truncated hypergraph edge section");
                String netName = edgeIdx <= netNames.size() ? netNames.get(edgeIdx - 1)
                        : ("<edge_" + edgeIdx + ">");

                String[] tokens = line.trim().isEmpty() ? 
                    new String[0] : line.trim().split("\\s+");
                Map<Integer, Integer> partCounts = new HashMap<>();
                ArrayList<String> members = new ArrayList<>();
                int firstVertex = format % 10 == 1 ? 1 : 0;
                int edgeWeight = firstVertex == 1 ? Integer.parseInt(tokens[0]) : 1;
                if (edgeWeight <= 0) throw new IllegalArgumentException("Nonpositive hyperedge weight");
                Set<Integer> seenVertices = new HashSet<>();
                for (int token = firstVertex; token < tokens.length; token++) {
                    int vertexId = Integer.parseInt(tokens[token]);
                    if (vertexId < 1 || vertexId > vertexCount || !seenVertices.add(vertexId)) {
                        throw new IllegalArgumentException("Invalid/duplicate hypergraph vertex " + vertexId);
                    }
                    String instName = instLookup[vertexId];
                    Integer partition = instName == null ? null : nameToPart.get(instName);
                    if (partition == null || partition < 0) {
                        throw new IllegalArgumentException("Unmapped hypergraph instance " + instName);
                    }
                    int partitionId = partition;
                    partCounts.put(partitionId, partCounts.getOrDefault(partitionId, 0) + 1);
                    String partitionLabel = (partitionId >= 0) ? 
                        PartitionLabel.indexToFpgaLabel(partitionId) : "UNASSIGNED";
                    members.add(String.format("  %d: %s part=%s", 
                        vertexId, instName, partitionLabel));
                }
                boolean cut = partCounts.size() > 1;

                reportWriter.write(String.format("Net %d: %s%n", edgeIdx, netName));
                if (firstVertex == 1) reportWriter.write("  edge_weight=" + edgeWeight + "\n");
                // Print blocks with pin counts
                StringBuilder description = new StringBuilder();
                description.append("  cut=").append(cut ? "true" : "false").append(" blocks: ");
                if (partCounts.isEmpty()) {
                    description.append("none");
                } else {
                    java.util.List<java.util.Map.Entry<Integer,Integer>> entries = 
                        new java.util.ArrayList<>(partCounts.entrySet());
                    // Sort by block id
                    entries.sort((a,b) -> Integer.compare(a.getKey(), b.getKey())); 
                    for (int i = 0; i < entries.size(); i++) {
                        int blockId = entries.get(i).getKey();
                        int pinCount = entries.get(i).getValue();
                        String blockLabel = (blockId >= 0) ? 
                            PartitionLabel.indexToFpgaLabel(blockId) : "UNASSIGNED";
                        description.append(blockLabel).append("=").append(pinCount).append(" ")
                            .append(pinCount == 1 ? "pin" : "pins");
                        if (i + 1 < entries.size()) description.append(", ");
                    }
                }
                //'blocks' lists how many pins of net are inside each partition
                //pn is the partition id p2 = partition 2, p0 = partition 0
                //'p0=1 pin, p1=1 pin' means one pin in p0 and one pin in p1; 
                //'p6=2 pins' means two pins in p6
                reportWriter.write(description.toString());
                reportWriter.write("\n");
                for (String member : members) {
                    reportWriter.write(member);
                    reportWriter.write("\n");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readHypergraphRecord(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.trim().isEmpty() && !line.trim().startsWith("%")) return line;
        }
        return null;
    }
}
