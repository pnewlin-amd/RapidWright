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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Generates mapping.txt containing a hierarchy aware 
 * mapping of instances to partitions post-MtKaHyPar partitioning.
 *
 * Example,
 * If fft_top/stage_16 is wholly contained into partition 1 'FPGA_B' 
 * then only one line should be written which references stage_16, "fft_top/stage_16 FPGA_B"
 * 
 * If stage_16 is not wholly contained we go one level deeper until wholly contained 
 * or we reach leaf instances.
 * "fft_top/stage_16/butterfly_0 FPGA_A"
 * "fft_top/stage_16/butterfly_1 FPGA_B"
 */
public final class HierMappingWriter {

    /**
     * Trie node representing a hierarchical instance path segment.
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
        String assignedPartition;
    }

    /**
     * Recursively assigns the majority partition to each node in the trie.
     * @param node The current node.
     */
    private static void assignPartitions(Node node) {
        for (Node child : node.children.values()) {
            assignPartitions(child);
        }
        node.assignedPartition = chooseHomePartition(node.counts);
    }

    /** Differential rules, including verbose mode, always obey the same move contract. */
    private static void emitContractMappings(Node node, String inherited, String rootName,
            String relative, List<String> out, boolean compact) {
        String owner = relative.isEmpty() || compact || !node.leafCounts.isEmpty()
                ? node.assignedPartition : inherited;
        if (owner == null) throw new IllegalArgumentException("No functional source logic to map");
        for (Node child : node.children.values()) {
            String path = relative.isEmpty() ? child.name : relative + "/" + child.name;
            emitContractMappings(child, owner, rootName, path, out, compact);
        }
        if (!owner.equals(inherited)) out.add(MappingContract.rooted(rootName, relative) + " " + owner);
    }

    /**
     * Builds mapping lines from input partition sets (read from files in partitionDir) and
     * returns a list of "<path> <partition>" lines.
     *
     * @param partitionDir The directory containing partition files.
     * @param cellsFile The path to the cells.txt file.
     * @param defaultPartitionName The name of the default partition (optional).
     * @param optimize Whether to use the optimized differential mapping generation.
     * @return A list of mapping lines.
     */
    public static List<String> buildMappingFromPartitionFiles(String partitionDir
            , String cellsFile
            , String defaultPartitionName
            , boolean optimize) {
        throw new IllegalArgumentException("Exact synthesized EDIF netlist is required; use the netlist-aware overload or --netlist");
    }

    /** Netlist-aware overload omits actual static-source primitives by type. */
    public static List<String> buildMappingFromPartitionFiles(String partitionDir,
            String cellsFile, String defaultPartitionName, boolean optimize,
            EDIFNetlist netlist) {
        if (netlist == null) throw new IllegalArgumentException("Exact synthesized EDIF netlist is required");
        List<String> partitions = readPartitionNames(Paths.get(cellsFile));
        Map<String, Set<String>> partToPaths = readPartitionInstanceSets(Paths.get(partitionDir),
                new LinkedHashSet<>(partitions));
        if (netlist != null) partToPaths = withoutConstants(partToPaths, netlist);
        Node root = buildTrie(partToPaths, netlist);
        computeCounts(root);
        List<String> out = new ArrayList<>();
        if (defaultPartitionName != null && !partitions.contains(defaultPartitionName)) {
            throw new IllegalArgumentException("Default partition is absent from cells.txt: " + defaultPartitionName);
        }
        assignPartitions(root);
        if (defaultPartitionName != null) root.assignedPartition = defaultPartitionName;
        emitContractMappings(root, null, netlist.getTopCell().getName(), "", out, optimize);
        MappingContract.validate(netlist, out, partitions, partToPaths);
        return out;
    }

    /**
     * Writes mapping lines to a file.
     *
     * Ensure the parent directories exist so the file emits cleanly even on fresh runs.
     *
     * @param lines The list of mapping lines to write.
     * @param outputPath The path to the output file.
     */
    public static void writeMappingFile(List<String> lines, String outputPath) {
        throw new IllegalArgumentException("Unchecked mapping writes are forbidden; supply the exact synthesized netlist");
    }

    /** Every emission path requires the exact source and emits ownership evidence. */
    public static void writeMappingFile(List<String> lines, String outputPath,
            EDIFNetlist netlist, List<String> destinations) {
        publish(lines, Paths.get(outputPath), netlist, destinations, null);
    }

    private static void publish(List<String> lines, Path out, EDIFNetlist netlist,
            List<String> destinations, Map<String, Set<String>> frontier) {
        if (Files.exists(out, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Refusing to overwrite an existing mapping; use a fresh output directory: " + out);
        }
        Path staged = out.resolveSibling(out.getFileName() + ".pending");
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(staged, lines, StandardCharsets.UTF_8);
            MappingContract.certify(netlist, lines, destinations, frontier, staged, parent);
            Files.write(parent.resolve("cells.txt"), destinations, StandardCharsets.UTF_8);
            Files.move(staged, out);
        } catch (RuntimeException | IOException e) {
            try { Files.deleteIfExists(staged); } catch (IOException ignored) { }
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    /**
     * RapidWright partitioner api: writes mapping.txt in outDir.
     *
     * @param outDir The output directory.
     * @param netlist The EDIF netlist.
     * @param partitions A map of partition IDs to sets of instance names.
     * @param instLutCountMap A map of instance LUT counts (unused but kept for API consistency).
     */
    public static void write(Path outDir
            , EDIFNetlist netlist
            , Map<Integer, Set<String>> partitions
            , Map<EDIFHierCellInst, Integer> instLutCountMap
            , boolean optimize) {
        // ============================================================================
        // STEP 1
        // Ensure cells.txt has fpga_* names for all partitions based on indices;
        // will generate or extend labels when needed.
        // ============================================================================

        int maxIdx = -1;
        for (Integer idx : partitions.keySet()) {
            if (idx == null || idx < 0) throw new IllegalArgumentException("Invalid partition index");
            if (idx != null && idx > maxIdx) {
                maxIdx = idx;
            }
        }
        int numParts = Math.max(0, maxIdx + 1);
        if (partitions.size() != numParts) throw new IllegalArgumentException("Partition indices must be dense");

        // A reused output directory must not retain labels from an older run.
        List<String> names = CellsWriter.generateNames(numParts);

        // ============================================================================
        // STEP 2
        // Build part-to-paths keyed by partition names (canonical labels); missing indices
        // get generated names so the map is complete.
        // ============================================================================
        Map<String, Set<String>> partToPaths = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<String>> entry : partitions.entrySet()) {
            int idx = entry.getKey();
            String name = (idx >= 0 && idx < names.size())
                    ? names.get(idx) : CellsWriter.generateName(idx);
            partToPaths.put(name, entry.getValue() != null
                    ? entry.getValue() : new LinkedHashSet<>());
        }
        partToPaths = withoutConstants(partToPaths, netlist);

        // ============================================================================
        // STEP 3
        // Build the trie and compute per-subtree counts, then select lines using the
        // containment + home rules.
        // ============================================================================
        Node root = buildTrie(partToPaths, netlist);
        computeCounts(root);
        assignPartitions(root);

        // ============================================================================
        // STEP 4
        // Prefix every path with the top cell name (prefer the top cell’s type name) and
        // append a single top 'home' line at the end for readability.
        // ============================================================================
        String topName = netlist.getTopCell().getName();
        List<String> prefixed = new ArrayList<>();
        emitContractMappings(root, null, topName, "", prefixed, optimize);

        Path mappingFile = outDir.resolve("mapping.txt");
        publish(prefixed, mappingFile, netlist, names, partToPaths);
    }

    /**
     * Constants have no placement ownership: regrouping recreates local sources.
     * Ignore them before home selection, uniform-subtree compression and
     * roundtrip checks. Classification uses primitive TYPE, never path spelling.
     * A mixed coarse vertex is retained and its constants inherit implicitly.
     */
    private static Map<String, Set<String>> withoutConstants(
            Map<String, Set<String>> source, EDIFNetlist netlist) {
        Map<EDIFCell, Boolean> nonconstant = new java.util.IdentityHashMap<>();
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            Set<String> paths = new LinkedHashSet<>();
            for (String path : entry.getValue()) {
                EDIFHierCellInst instance = path.isEmpty() ? netlist.getTopHierCellInst()
                        : netlist.getHierCellInstFromName(path);
                if (instance == null) throw new IllegalArgumentException("Unknown mapping frontier path " + path);
                if (MappingContract.hasFunctionalLeaf(instance.getCellType(), nonconstant)) paths.add(path);
            }
            result.put(entry.getKey(), paths);
        }
        return result;
    }

    /**
     * Convenience main to emit mapping lines from a directory of partition files and a cells.txt.
     *
     * @param args Command line arguments: partitionDir, cells.txt, output_mapping.txt,
     *             [defaultPartitionName].
     */
    public static void main(String[] args) {
        if (args.length < 5) throw new IllegalArgumentException(
                "usage: partitionDir cells.txt output_mapping.txt [defaultPartitionName] [--optimized] --netlist source.edf");
        String partitionDir = args[0];
        String cellsFile = args[1];
        String outputFile = args[2];
        String defaultPartitionName = null;
        Path source = null;
        boolean optimize = false;
        for (int i = 3; i < args.length; i++) {
            if ("--optimized".equals(args[i])) optimize = true;
            else if ("--netlist".equals(args[i]) && i + 1 < args.length) source = Paths.get(args[++i]);
            else if (defaultPartitionName == null && !args[i].startsWith("--")) defaultPartitionName = args[i];
            else throw new IllegalArgumentException("Unknown mapping argument: " + args[i]);
        }
        if (source == null) throw new IllegalArgumentException("Exact synthesized EDIF --netlist is required");
        EDIFNetlist netlist = com.xilinx.rapidwright.edif.EDIFTools.readEdifFile(source);
        List<String> mapping = buildMappingFromPartitionFiles(partitionDir,
                cellsFile,
                defaultPartitionName,
                optimize, netlist);
        writeMappingFile(mapping, outputFile, netlist, readPartitionNames(Paths.get(cellsFile)));
    }

    /**
     * Reads partition labels from cells.txt and returns them in order.
     *
     * We ignore comments and blanks and keep order deterministic.
     *
     * @param cellsFile The path to the cells.txt file.
     * @return A list of partition names.
     */
    private static List<String> readPartitionNames(Path cellsFile) {
        List<String> partitions = new ArrayList<>();
        if (cellsFile != null && Files.isRegularFile(cellsFile)) {
            for (String line : FileTools.getLinesFromTextFile(cellsFile.toString())) {
                String trimmed = trimComment(line);
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!trimmed.matches("FPGA_[A-Za-z0-9_]+") || partitions.contains(trimmed)) {
                    throw new IllegalArgumentException("Invalid or duplicate cells.txt destination: " + trimmed);
                }
                partitions.add(trimmed);
            }
        }
        if (partitions.isEmpty()) throw new IllegalArgumentException("Empty or missing cells.txt");
        return partitions;
    }

    /**
     * Reads instance paths for each partition label from the given directory.
     *
     * @param partitionDir The directory containing partition files.
     * @param partitions A set of partition names to look for.
     * @return A map of partition names to sets of instance paths.
     */
    private static Map<String, Set<String>> readPartitionInstanceSets(Path partitionDir
            , Set<String> partitions) {
        Map<String, Set<String>> partToPaths = new LinkedHashMap<>();

        for (String p : partitions) {
            Set<String> paths = new LinkedHashSet<>();

            Path file1 = partitionDir.resolve(p);
            Path file2 = partitionDir.resolve(p + ".txt");

            boolean readOk = false;
            if (Files.isRegularFile(file1)) {
                readOk = true;
                for (String line : safeReadLines(file1)) {
                    String trimmedLine = trimComment(line);
                    if (trimmedLine.isEmpty()) {
                        continue;
                    }
                    paths.add(trimmedLine);
                }
            } else if (Files.isRegularFile(file2)) {
                readOk = true;
                for (String line : safeReadLines(file2)) {
                    String trimmedLine = trimComment(line);
                    if (trimmedLine.isEmpty()) {
                        continue;
                    }
                    paths.add(trimmedLine);
                }
            } else {
                Path matched = findFileCaseInsensitive(partitionDir, p);
                if (matched != null && Files.isRegularFile(matched)) {
                    readOk = true;
                    for (String line : safeReadLines(matched)) {
                        String trimmedLine = trimComment(line);
                        if (trimmedLine.isEmpty()) {
                            continue;
                        }
                        paths.add(trimmedLine);
                    }
                }
            }

            if (!readOk) {
                throw new IllegalArgumentException("Missing partition file for " + p);
            }

            partToPaths.put(p, paths);
        }

        return partToPaths;
    }

    /**
     * Safe read of file lines with utf-8.
     *
     * @param file The file to read.
     * @return A list of lines from the file.
     */
    private static List<String> safeReadLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Case-insensitive search for a file that matches either "<name>" or "<name>.txt"
     * in a directory.
     *
     * @param dir The directory to search in.
     * @param targetName The name to search for.
     * @return The path to the matching file, or null if not found.
     */
    private static Path findFileCaseInsensitive(Path dir, String targetName) {
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(dir)) {
            for (Path path : directoryStream) {
                String name = path.getFileName().toString();
                if (name.equalsIgnoreCase(targetName)
                        || name.equalsIgnoreCase(targetName + ".txt")) {
                    if (Files.isRegularFile(path)) {
                        return path;
                    }
                }
            }
        } catch (IOException e) {
            // ignore, something to fill in this spot? error print?
        }
        return null;
    }

    /**
     * Trims comments and whitespace; treats lines starting with '#' or '//' as comments.
     *
     * @param s The string to trim.
     * @return The trimmed string, or an empty string if it was a comment.
     */
    private static String trimComment(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("#")) {
            return "";
        }
        if (trimmed.startsWith("//")) {
            return "";
        }
        return trimmed;
    }

    /** Build only genuine EDIF ancestors, never prefixes invented by splitting '/' names. */
    private static Node buildTrie(Map<String, Set<String>> partToPaths, EDIFNetlist netlist) {
        Node root = new Node();
        root.name = "";
        for (Map.Entry<String, Set<String>> entry : partToPaths.entrySet()) {
            for (String path : entry.getValue()) {
                EDIFHierCellInst instance = netlist.getHierCellInstFromName(path);
                if (instance == null || !instance.getFullHierarchicalInstName().equals(path)) {
                    throw new IllegalArgumentException("Unknown exact synthesized frontier path: " + path);
                }
                List<EDIFHierCellInst> chain = new ArrayList<>();
                for (EDIFHierCellInst part = instance; !part.isTopLevelInst(); part = part.getParent()) chain.add(part);
                Collections.reverse(chain);
                Node cursor = root;
                for (EDIFHierCellInst part : chain) {
                    if (!cursor.leafCounts.isEmpty()) throw new IllegalArgumentException("Overlapping frontier: " + path);
                    String name = part.getInst().getName();
                    Node child = cursor.children.get(name);
                    if (child == null) { child = new Node(); child.name = name; cursor.children.put(name, child); }
                    cursor = child;
                }
                if (!cursor.children.isEmpty() || !cursor.leafCounts.isEmpty()) {
                    throw new IllegalArgumentException("Duplicate or overlapping frontier: " + path);
                }
                cursor.leafCounts.put(entry.getKey(), 1);
            }
        }
        return root;
    }

    /**
     * Computes per-subtree aggregated counts for every node in the trie.
     *
     * @param node The root node of the subtree.
     * @return A map of aggregated counts per partition.
     */
    private static Map<String, Integer> computeCounts(Node node) {
        Map<String, Integer> aggregatedCounts = new LinkedHashMap<>();
        mergeCounts(aggregatedCounts, node.leafCounts);
        for (Node child : node.children.values()) {
            Map<String, Integer> childCounts = computeCounts(child);
            mergeCounts(aggregatedCounts, childCounts);
        }
        node.counts = aggregatedCounts;
        return aggregatedCounts;
    }

    /**
     * Adds a delta to a counts map for a given partition key.
     *
     * @param map The map to update.
     * @param key The partition key.
     * @param delta The value to add.
     */
    private static void addCount(Map<String, Integer> map, String key, int delta) {
        if (key == null) {
            return;
        }
        map.put(key, map.getOrDefault(key, 0) + delta);
    }

    /**
     * Merges entries from mapB into mapA by adding counts per partition.
     *
     * @param mapA The destination map.
     * @param mapB The source map.
     */
    private static void mergeCounts(Map<String, Integer> mapA, Map<String, Integer> mapB) {
        for (Map.Entry<String, Integer> entry : mapB.entrySet()) {
            addCount(mapA, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Picks the “home” partition for a node using
     * the largest count (ties broken lexicographically).
     *
     * @param counts The counts map.
     * @return The home partition label, or null.
     */
    private static String chooseHomePartition(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        int max = -1;
        String best = null;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int value = entry.getValue() == null ? 0 : entry.getValue();
            if (value > max || (value == max
                    && (best == null || entry.getKey().compareTo(best) < 0))) {
                max = value;
                best = entry.getKey();
            }
        }
        return best;
    }

    /**
     * Returns lines sorted by path depth, then lexicographically. Useful when post-processing
     * is needed for readability.
     *
     * @param lines The list of lines to sort.
     * @return A new sorted list of lines.
     */
    public static List<String> sortByPathDepth(List<String> lines) {
        List<String> out = new ArrayList<>(lines);
        Collections.sort(out, (a, b) -> {
            int depthA = depthOfPath(a);
            int depthB = depthOfPath(b);
            if (depthA != depthB) {
                return Integer.compare(depthB, depthA);
            }
            return a.compareTo(b);
        });
        return out;
    }

    /**
     * Computes a simple depth metric for "<path> <partition>" lines by counting '/' in the path.
     *
     * @param mappingLine The mapping line string.
     * @return The depth of the path.
     */
    private static int depthOfPath(String mappingLine) {
        int spaceIndex = mappingLine.indexOf(' ');
        String path = spaceIndex >= 0 ? mappingLine.substring(0, spaceIndex) : mappingLine;
        if (path.isEmpty()) {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth + 1;
    }
}
