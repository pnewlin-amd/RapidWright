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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.util.FileTools;

/**
 * generates mapping.txt for the wrap/add-cells/regroup flow (wrap the top cell, add new cells per partition, regroup instances).
 *
 * - The goal is to produce "<instance_path> <partition_label>" lines that tell regroupinstances where each hierarchical instance
 *   should be re-homed.
 * 
 *   The HierMappingWriter will only emit simple one liners when an entire hierarchy can be contained onto one partition.
 *   E.x: top/stage_16 fits entirely on FPGA_A then only write "top/stage_16 FPGA_A"
 * 
 *   The HierMappingWriter will emit low-level leaf instance names when a module is split up across partitions. The result is needing to specify 
 *   hierarchy on the partition destination: "top/stage_32/cell[0]_i FPGA_A/cell[0]_i"
 */
public final class HierMappingWriter {

    /**
     * trie node representing a hierarchical instance path segment.
     *
     * - name: the segment at this level (for example, "stage_16").
     * - children: sub-segments under this node.
     * - leafCounts: one count per partition per explicit path)
     * - counts: aggregated counts over this node’s subtree (leafCounts + all children)
     *           lets us easily decide if wholly contained or not
     */
    private static final class Node {
        String name;
        Map<String, Node> children = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Integer> leafCounts = new LinkedHashMap<>();
    }

    /**
     * builds mapping lines from input partition sets (read from files in partitionDir) and returns a list of "<path> <partition>" lines.
     */
    public static List<String> buildMappingFromPartitionFiles(String partitionDir, String cellsFile, String defaultPartitionName) {
        List<String> partitions = readPartitionNames(Paths.get(cellsFile));
        Map<String, Set<String>> partToPaths = readPartitionInstanceSets(Paths.get(partitionDir), new LinkedHashSet<>(partitions));
        Node root = buildTrie(partToPaths);
        computeCounts(root);
        List<String> out = new ArrayList<>();
        selectAndEmitMappings(root, "", out);

        if (defaultPartitionName != null && !partitions.contains(defaultPartitionName)) {
            System.err.println("warning: default partition '" + defaultPartitionName + "' not found in cells.txt");
        }

        return out;
    }

    /**
     * writes mapping lines to a file.
     *
     * - ensure the parent directories exist so the file emits cleanly even on fresh runs.
     */
    public static void writeMappingFile(List<String> lines, String outputPath) {
        try {
            Path out = Paths.get(outputPath);
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(out, lines, StandardCharsets.UTF_8);
        } catch (IOException e) { //more guards the better.
            throw new RuntimeException(e);
        }
    }

    /**
     * rapidwright partitioner api: writes mapping.txt in outDir using the provided partition membership, netlist, and labels.
     */
    public static void write(Path outDir, EDIFNetlist n, Map<Integer, Set<String>> partitions, Map<EDIFHierCellInst, Integer> instLutCountMap) {
        // ensure cells.txt has fpga_* names for all partitions based on indices; generate or extend labels when needed
        Path cellsFile = outDir.resolve("cells.txt");

        int maxIdx = -1;
        for (Integer idx : partitions.keySet()) {
            if (idx != null && idx > maxIdx) maxIdx = idx;
        }
        int numParts = Math.max(0, maxIdx + 1);

        List<String> names = readPartitionNames(cellsFile);
        if (names.size() < numParts) {
            List<String> gen = CellsWriter.generateNames(numParts);
            if (names.isEmpty()) {
                names = gen;
            } else {
                List<String> extended = new ArrayList<>(names);
                for (int i = names.size(); i < numParts; i++) {
                    extended.add(gen.get(i));
                }
                names = extended;
            }
            CellsWriter.write(outDir, names);
        }

        // build part-to-paths keyed by partition names (canonical labels); missing indices get generated names so the map is complete
        Map<String, Set<String>> partToPaths = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<String>> e : partitions.entrySet()) {
            int idx = e.getKey();
            String name = (idx >= 0 && idx < names.size()) ? names.get(idx) : CellsWriter.generateName(idx);
            partToPaths.put(name, e.getValue() != null ? e.getValue() : new LinkedHashSet<>());
        }

        // build the trie and compute per-subtree counts, then select lines using the containment + home rules
        Node root = buildTrie(partToPaths);
        computeCounts(root);
        List<String> lines = new ArrayList<>();
        selectAndEmitMappings(root, "", lines);

        // prefix every path with the top cell name (prefer the top cell’s type name) and append a single top 'home' line at the end for readability
        String topName = null;
        try {
            EDIFHierCellInst topH = n.getTopHierCellInst();
            topName = (topH != null) ? topH.getCellName() : null;
            if (topName == null || topName.isEmpty()) {
                topName = (topH != null) ? topH.getInst().getName() : null;
            }
        } catch (Exception ignored) {}
        if (topName == null || topName.isEmpty()) {
            topName = "top";
        }
        List<String> prefixed = new ArrayList<>(lines.size() + 1);
        for (String line : lines) {
            int sp = line.indexOf(' ');
            String path = sp >= 0 ? line.substring(0, sp) : line;
            String part = sp >= 0 ? line.substring(sp + 1) : "";
            prefixed.add(topName + "/" + path + " " + part);
        }
        // place top most home (hier) at the bottom of the file.
        String topHome = chooseHomePartition(root.counts);
        if (topHome != null) {
            prefixed.add(topName + " " + topHome);
        }

        Path mappingFile = outDir.resolve("mapping.txt");
        writeMappingFile(prefixed, mappingFile.toString());
    }

    /**
     * convenience main to emit mapping lines from a directory of partition files and a cells.txt.
     */
    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            System.err.println("usage: <partitionDir> <cells.txt> <output_mapping.txt> [defaultPartitionName]");
            return;
        }
        String partitionDir = args[0];
        String cellsFile = args[1];
        String outputFile = args[2];
        String defaultPartitionName = args.length == 4 ? args[3] : null;

        List<String> mapping = buildMappingFromPartitionFiles(partitionDir, cellsFile, defaultPartitionName);
        writeMappingFile(mapping, outputFile);
    }

    /**
     * reads partition labels from cells.txt and returns them in order.
     *
     * we ignore comments and blanks and keep order deterministic.
     */
    private static List<String> readPartitionNames(Path cellsFile) {
        List<String> partitions = new ArrayList<>();
        if (cellsFile != null && Files.isRegularFile(cellsFile)) {
            for (String line : FileTools.getLinesFromTextFile(cellsFile.toString())) {
                String trimmed = trimComment(line);
                if (trimmed.isEmpty()) continue;
                partitions.add(trimmed);
            }
        }
        return partitions;
    }

    /**
     * reads instance paths for each partition label from the given directory.
     *
     */
    private static Map<String, Set<String>> readPartitionInstanceSets(Path partitionDir, Set<String> partitions) {
        Map<String, Set<String>> partToPaths = new LinkedHashMap<>();

        for (String p : partitions) {
            Set<String> paths = new LinkedHashSet<>();

            Path f1 = partitionDir.resolve(p);
            Path f2 = partitionDir.resolve(p + ".txt");

            boolean readOk = false;
            if (Files.isRegularFile(f1)) {
                readOk = true;
                for (String line : safeReadLines(f1)) {
                    String t = trimComment(line);
                    if (t.isEmpty()) continue;
                    paths.add(t);
                }
            } else if (Files.isRegularFile(f2)) {
                readOk = true;
                for (String line : safeReadLines(f2)) {
                    String t = trimComment(line);
                    if (t.isEmpty()) continue;
                    paths.add(t);
                }
            } else {
                Path matched = findFileCaseInsensitive(partitionDir, p);
                if (matched != null && Files.isRegularFile(matched)) {
                    readOk = true;
                    for (String line : safeReadLines(matched)) {
                        String t = trimComment(line);
                        if (t.isEmpty()) continue;
                        paths.add(t);
                    }
                }
            }

            if (!readOk) {
                System.err.println("info: no file found for partition '" + p + "' in dir " + partitionDir);
            }

            partToPaths.put(p, paths);
        }

        return partToPaths;
    }

    /**
     * safe read of file lines with utf-8.
     */
    private static List<String> safeReadLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * case-insensitive search for a file that matches either "<name>" or "<name>.txt" in a directory.
     */
    private static Path findFileCaseInsensitive(Path dir, String targetName) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.equalsIgnoreCase(targetName) || name.equalsIgnoreCase(targetName + ".txt")) {
                    if (Files.isRegularFile(p)) return p;
                }
            }
        } catch (IOException e) {
            // ignore, something to fill in this spot? error print?
        }
        return null;
    }

    /**
     * trims comments and whitespace; treats lines starting with '#' or '//' as comments.
     */
    private static String trimComment(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        if (t.startsWith("#")) return "";
        if (t.startsWith("//")) return "";
        return t;
    }

    /**
     * builds a trie over all instance paths for all partitions.
     */
    private static Node buildTrie(Map<String, Set<String>> partToPaths) {
        Node root = new Node();
        root.name = "";

        for (Map.Entry<String, Set<String>> e : partToPaths.entrySet()) {
            String partition = e.getKey();
            for (String path : e.getValue()) {
                addPath(root, path, partition);
            }
        }
        return root;
    }

    /**
     * inserts an instance path into the trie and records a leaf contribution at the terminal node for the given partition.
     */
    private static void addPath(Node root, String fullPath, String partition) {
        List<String> segs = splitPath(fullPath);
        Node cur = root;
        for (String seg : segs) {
            Node nxt = cur.children.get(seg);
            if (nxt == null) {
                nxt = new Node();
                nxt.name = seg;
                cur.children.put(seg, nxt);
            }
            cur = nxt;
        }
        cur.leafCounts.put(partition, cur.leafCounts.getOrDefault(partition, 0) + 1);
    }

    /**
     * splits a hierarchical path on '/' while leaving each segment intact.
     */
    private static List<String> splitPath(String path) {
        if (path == null || path.isEmpty()) return Collections.emptyList();
        String[] parts = path.split("/");
        return Arrays.asList(parts);
    }

    /**
     * computes per-subtree aggregated counts for every node in the trie.
     */
    private static Map<String, Integer> computeCounts(Node n) {
        Map<String, Integer> agg = new LinkedHashMap<>();
        mergeCounts(agg, n.leafCounts);
        for (Node c : n.children.values()) {
            Map<String, Integer> childCounts = computeCounts(c);
            mergeCounts(agg, childCounts);
        }
        n.counts = agg;
        return agg;
    }

    /**
     * adds a delta to a counts map for a given partition key.
     */
    private static void addCount(Map<String, Integer> m, String key, int delta) {
        if (key == null) return;
        m.put(key, m.getOrDefault(key, 0) + delta);
    }

    /**
     * merges entries from b into a by adding counts per partition.
     */
    private static void mergeCounts(Map<String, Integer> a, Map<String, Integer> b) {
        for (Map.Entry<String, Integer> e : b.entrySet()) {
            addCount(a, e.getKey(), e.getValue());
        }
    }

    /**
     * subtracts entries of b from a on a copy and prunes non-positive results.
     *
     * useful to compute leftovers after child prints.
     */
    private static Map<String, Integer> subtractCounts(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            out.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Integer> e : b.entrySet()) {
            out.put(e.getKey(), out.getOrDefault(e.getKey(), 0) - e.getValue());
        }
        pruneNonPositive(out);
        return out;
    }

    /**
     * removes entries from a counts map where the value is null, zero, or negative.
     */
    private static void pruneNonPositive(Map<String, Integer> m) {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) {
                toRemove.add(e.getKey());
            }
        }
        for (String k : toRemove) {
            m.remove(k);
        }
    }

    /**
     * returns true if the counts map has exactly one partition present (wholly contained).
     */
    private static boolean isWhollyContained(Map<String, Integer> counts) {
        if (counts.isEmpty()) return false;
        return counts.size() == 1;
    }

    /**
     * returns the sole partition label if counts are wholly contained; otherwise null.
     */
    private static String singlePartition(Map<String, Integer> counts) {
        if (!isWhollyContained(counts)) return null;
        for (String k : counts.keySet()) return k;
        return null;
    }

    /**
     * picks the “home” partition for a node using the largest count (ties broken lexicographically).
     * 
     * TODO : May need revision based on requested changes to regroupinstances / mapping.txt needing to change based on that.
     */
    private static String chooseHomePartition(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) return null;
        int max = -1;
        String best = null;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int v = e.getValue() == null ? 0 : e.getValue();
            if (v > max || (v == max && (best == null || e.getKey().compareTo(best) < 0))) {
                max = v;
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * selects mapping lines by walking the trie and emits them into 'out'.
     */
    private static Map<String, Integer> selectAndEmitMappings(Node n, String prefixPath, List<String> out) {
        String thisPath = prefixPath;
        if (n.name != null && !n.name.isEmpty()) {
            if (thisPath.isEmpty()) thisPath = n.name;
            else thisPath = thisPath + "/" + n.name;
        }

        if (isWhollyContained(n.counts) && !thisPath.isEmpty()) {
            String p = singlePartition(n.counts);
            out.add(thisPath + " " + p);
            return new LinkedHashMap<>(n.counts);
        }

        Map<String, Integer> relocatedFromChildren = new LinkedHashMap<>();
        for (Node c : n.children.values()) {
            Map<String, Integer> childRelocated = selectAndEmitMappings(c, thisPath, out);
            mergeCounts(relocatedFromChildren, childRelocated);
        }

        Map<String, Integer> leftover = subtractCounts(n.counts, relocatedFromChildren);

        if (!thisPath.isEmpty()) {
            String home = chooseHomePartition(n.counts);
            if (home != null) {
                out.add(thisPath + " " + home);
                return new LinkedHashMap<>(n.counts);
            }
        }

        return relocatedFromChildren;
    }

    /**
     * returns lines sorted by path depth, then lexicographically. useful when post-processing is needed for readability.
     */
    public static List<String> sortByPathDepth(List<String> lines) {
        List<String> out = new ArrayList<>(lines);
        Collections.sort(out, (a, b) -> {
            int da = depthOfPath(a);
            int db = depthOfPath(b);
            if (da != db) return Integer.compare(da, db);
            return a.compareTo(b);
        });
        return out;
    }

    /**
     * computes a simple depth metric for "<path> <partition>" lines by counting '/' in the path.
     */
    private static int depthOfPath(String mappingLine) {
        int sp = mappingLine.indexOf(' ');
        String path = sp >= 0 ? mappingLine.substring(0, sp) : mappingLine;
        if (path.isEmpty()) return 0;
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') depth++;
        }
        return depth + 1;
    }
}
