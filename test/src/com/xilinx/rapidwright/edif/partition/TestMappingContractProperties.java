/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Independent source-tree ownership checks derived from the photographed
 * mapping contract. This is an ordered extraction oracle, not the unavailable
 * downstream regroup implementation, connectivity audit or hardware flow.
 */
public class TestMappingContractProperties {
    private static final class SourceNode {
        final SourceNode parent;
        final String path;
        final EDIFCell type;
        final List<SourceNode> children = new ArrayList<>();

        SourceNode(SourceNode parent, String name, EDIFCell type) {
            this.parent = parent;
            this.path = parent == null ? name : parent.path + "/" + name;
            this.type = type;
        }

        boolean functional() {
            return type.isPrimitive() && !type.getName().equals("GND") && !type.getName().equals("VCC");
        }

        boolean constant() {
            return type.isPrimitive() && (type.getName().equals("GND") || type.getName().equals("VCC"));
        }
    }

    private static EDIFCell primitive(EDIFNetlist netlist, String name) {
        EDIFCell previous = netlist.getHDIPrimitivesLibrary().getCell(name);
        return previous == null ? new EDIFCell(netlist.getHDIPrimitivesLibrary(), name) : previous;
    }

    private static SourceNode sourceTree(EDIFCell type, SourceNode parent, String name) {
        SourceNode node = new SourceNode(parent, name, type);
        for (EDIFCellInst child : type.getCellInsts()) {
            node.children.add(sourceTree(child.getCellType(), node, child.getName()));
        }
        return node;
    }

    private static List<SourceNode> all(SourceNode root) {
        List<SourceNode> result = new ArrayList<>();
        result.add(root);
        for (SourceNode child : root.children) result.addAll(all(child));
        return result;
    }

    private static boolean under(SourceNode node, SourceNode ancestor) {
        for (SourceNode current = node; current != null; current = current.parent) {
            if (current == ancestor) return true;
        }
        return false;
    }

    private static List<String> labels(int count) {
        List<String> result = new ArrayList<>();
        for (int p = 0; p < count; p++) result.add("FPGA_" + (char) ('A' + p));
        return result;
    }

    private static String relative(SourceNode node, SourceNode root) {
        return node == root ? "" : node.path.substring(root.path.length() + 1);
    }

    private static Map<Integer, Set<String>> leafFrontier(SourceNode root,
            Map<SourceNode, String> expected, int count) {
        Map<Integer, Set<String>> parts = new LinkedHashMap<>();
        List<String> names = labels(count);
        for (int p = 0; p < count; p++) parts.put(p, new LinkedHashSet<>());
        for (SourceNode node : all(root)) {
            if (node.functional()) parts.get(names.indexOf(expected.get(node))).add(relative(node, root));
            // Constants deliberately arrive in arbitrary parts; they must not
            // create explicit move records or satisfy useful occupancy.
            else if (node.constant()) parts.get(parts.size() - 1).add(relative(node, root));
        }
        return parts;
    }

    private static String uniformOwner(SourceNode node, Map<SourceNode, String> expected) {
        Set<String> owners = new LinkedHashSet<>();
        for (SourceNode leaf : all(node)) if (leaf.functional()) owners.add(expected.get(leaf));
        return owners.size() == 1 ? owners.iterator().next() : null;
    }

    private static void coarseFrontier(SourceNode node, SourceNode root,
            Map<SourceNode, String> expected, List<String> names, Map<Integer, Set<String>> result) {
        String owner = uniformOwner(node, expected);
        if (owner != null) {
            result.get(names.indexOf(owner)).add(relative(node, root));
            return;
        }
        for (SourceNode child : node.children) coarseFrontier(child, root, expected, names, result);
    }

    /**
     * Remove each selected source subtree from the still-present original
     * tree. Earlier extracted descendants never return when a parent moves.
     * No production parser, prefix lookup or ownership validator is used.
     */
    private static Map<SourceNode, String> orderedExtraction(SourceNode root, List<String> lines) {
        Map<String, SourceNode> exact = new LinkedHashMap<>();
        for (SourceNode node : all(root)) Assertions.assertNull(exact.put(node.path, node));
        Set<SourceNode> remaining = Collections.newSetFromMap(new IdentityHashMap<>());
        remaining.addAll(exact.values());
        Map<SourceNode, String> result = new LinkedHashMap<>();
        Map<SourceNode, String> records = new LinkedHashMap<>();
        Assertions.assertFalse(lines.isEmpty());
        for (String line : lines) {
            String[] tokens = line.split("\\s+");
            Assertions.assertEquals(2, tokens.length, "one comment-free two-token record");
            Assertions.assertTrue(tokens[1].matches("FPGA_[A-Za-z0-9_]+"));
            SourceNode source = exact.get(tokens[0]);
            Assertions.assertNotNull(source, "must name an actual synthesized instance: " + tokens[0]);
            Assertions.assertFalse(source.constant(), "no explicit constant primitive move");
            Assertions.assertTrue(remaining.contains(source), "a previously moved ancestor hid " + tokens[0]);
            Assertions.assertNull(records.put(source, tokens[1]), "duplicate source instance");
            for (SourceNode node : all(root)) {
                if (remaining.contains(node) && under(node, source) && node.functional()) result.put(node, tokens[1]);
            }
            remaining.removeIf(node -> under(node, source));
        }
        Assertions.assertEquals(root.path, lines.get(lines.size() - 1).split("\\s+")[0], "explicit residual root last");
        for (Map.Entry<SourceNode, String> record : records.entrySet()) {
            for (SourceNode parent = record.getKey().parent; parent != null; parent = parent.parent) {
                if (records.containsKey(parent)) {
                    Assertions.assertNotEquals(records.get(parent), record.getValue(),
                            "only the nearest actual mapped ancestor decides redundancy");
                    break;
                }
            }
        }
        return result;
    }

    private static void verifyExport(EDIFNetlist netlist, SourceNode source,
            Map<SourceNode, String> expected, Map<Integer, Set<String>> frontier,
            int count, Path output, boolean compact) throws IOException {
        HierMappingWriter.write(output, netlist, frontier, Collections.emptyMap(), compact);
        List<String> lines = Files.readAllLines(output.resolve("mapping.txt"));
        Map<SourceNode, String> actual = orderedExtraction(source, lines);
        Assertions.assertEquals(expected, actual, "ordered extraction must preserve every assigned functional leaf");
        Assertions.assertEquals(labels(count), Files.readAllLines(output.resolve("cells.txt")));
        Map<String, SourceNode> sourceByPath = new LinkedHashMap<>();
        for (SourceNode node : all(source)) sourceByPath.put(node.path, node);
        Set<String> moved = new LinkedHashSet<>();
        for (String line : lines) moved.add(line.split("\\s+")[0]);
        List<String> recordManifest = Files.readAllLines(output.resolve("mapping_records.tsv"));
        Assertions.assertEquals(lines.size() + 1, recordManifest.size());
        for (int i = 0; i < lines.size(); i++) {
            String[] rule = lines.get(i).split("\\s+");
            String ancestor = "";
            for (SourceNode parent = sourceByPath.get(rule[0]).parent; parent != null; parent = parent.parent) {
                if (moved.contains(parent.path)) { ancestor = parent.path; break; }
            }
            Assertions.assertEquals(rule[0] + "\t" + rule[1] + "\t" + ancestor, recordManifest.get(i + 1),
                    "record manifest must use actual EDIF parents, including literal slash names");
        }
        Map<String, String> expectedByPath = new LinkedHashMap<>();
        for (Map.Entry<SourceNode, String> entry : expected.entrySet()) expectedByPath.put(entry.getKey().path, entry.getValue());
        Map<String, String> manifest = new LinkedHashMap<>();
        int constants = 0, empty = 0;
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(output.resolve("mapping_ownership.tsv.gz")))) {
            String text = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            for (String row : text.split("\n")) {
                if (row.startsWith("instance_path\t")) continue;
                String[] fields = row.split("\t", -1);
                Assertions.assertEquals(6, fields.length);
                if (fields[2].equals("FUNCTIONAL")) {
                    Assertions.assertNull(manifest.put(fields[0], fields[3]));
                    Assertions.assertEquals(fields[3], fields[5], "frontier ownership retained");
                } else if (fields[2].equals("LOCAL_CONSTANT")) {
                    constants++;
                    Assertions.assertEquals("LOCAL_CONSTANT", fields[3]);
                } else {
                    Assertions.assertEquals("EMPTY_HIERARCHY", fields[2]);
                    empty++;
                }
            }
        }
        Assertions.assertEquals(expectedByPath, manifest);
        JSONObject receipt = new JSONObject(Files.readString(output.resolve("mapping_contract.json")));
        Assertions.assertEquals(expected.size(), receipt.getInt("functional_leaf_count"));
        Assertions.assertEquals(constants, receipt.getInt("local_constant_leaf_count"));
        Assertions.assertEquals(empty, receipt.getInt("empty_hierarchy_count"));
        Assertions.assertTrue(receipt.getBoolean("frontier_ownership_compared"));
        Assertions.assertFalse(receipt.getBoolean("downstream_regroup_verified"));
        for (String label : labels(count)) {
            long population = expected.values().stream().filter(label::equals).count();
            Assertions.assertTrue(population > 0, "each requested FPGA has useful source logic");
            Assertions.assertEquals(population, receipt.getJSONObject("functional_leaves_by_destination").getLong(label));
        }
    }

    private static EDIFNetlist fiveLeafTree() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("hw_top");
        netlist.getTopCell().createChildCellInst("root_reg", primitive(netlist, "FDRE"));
        EDIFCell cluster = new EDIFCell(netlist.getWorkLibrary(), "cluster_type");
        cluster.createChildCellInst("local_helper", primitive(netlist, "FDRE"));
        EDIFCell compute = new EDIFCell(netlist.getWorkLibrary(), "compute_type");
        compute.createChildCellInst("gate[0]", primitive(netlist, "LUT2"));
        compute.createChildCellInst("reg[0]", primitive(netlist, "FDRE"));
        cluster.createChildCellInst("compute", compute);
        cluster.createChildCellInst("remaining", primitive(netlist, "LUT1"));
        netlist.getTopCell().createChildCellInst("cluster_b", cluster);
        return netlist;
    }

    @Test
    public void everySmallLeafAssignmentRoundTripsThroughCompactAndVerbose(@TempDir Path directory) throws IOException {
        EDIFNetlist netlist = fiveLeafTree();
        SourceNode tree = sourceTree(netlist.getTopCell(), null, "hw_top");
        List<SourceNode> leaves = new ArrayList<>();
        for (SourceNode node : all(tree)) if (node.functional()) leaves.add(node);
        int exported = 0;
        for (int count = 1; count <= 3; count++) {
            int combinations = (int) Math.pow(count, leaves.size());
            for (int code = 0; code < combinations; code++) {
                int value = code;
                Map<SourceNode, String> expected = new LinkedHashMap<>();
                Set<String> occupied = new LinkedHashSet<>();
                for (SourceNode leaf : leaves) {
                    String owner = labels(count).get(value % count);
                    value /= count;
                    expected.put(leaf, owner);
                    occupied.add(owner);
                }
                if (occupied.size() != count) continue;
                for (boolean compact : new boolean[] {false, true}) {
                    verifyExport(netlist, tree, expected, leafFrontier(tree, expected, count), count,
                            directory.resolve("case_" + exported++), compact);
                }
            }
        }
        Assertions.assertEquals(362, exported);
    }

    private static void randomBranch(EDIFNetlist netlist, EDIFCell parent, Random random, int depth, int[] ids) {
        int id = ids[0]++;
        parent.createChildCellInst(depth == 0 ? "VCC" : "reg[" + id + "].no_snake", primitive(netlist, "FDRE"));
        if (depth < 3) {
            int children = 1 + random.nextInt(3);
            for (int i = 0; i < children; i++) {
                int childId = ids[0]++;
                if (random.nextBoolean()) {
                    EDIFCell module = new EDIFCell(netlist.getWorkLibrary(), "module_" + childId);
                    // This slash is a literal character in ONE local instance
                    // name, not a separately instantiated prefix module.
                    String name = i % 2 == 0 ? "scope[" + childId + "].no_snake/kept" : "module[" + childId + "]";
                    parent.createChildCellInst(name, module);
                    randomBranch(netlist, module, random, depth + 1, ids);
                } else {
                    parent.createChildCellInst("gate[" + childId + "].no_snake", primitive(netlist, "LUT2"));
                }
            }
        }
        parent.createChildCellInst("tie_low_" + id, primitive(netlist, "GND"));
    }

    @Test
    public void seededSourceTreesAndCoarseFrontiersPreserveOrderedOwnership(@TempDir Path directory) throws IOException {
        Random random = new Random(0x4d415050494e47L);
        for (int seed = 0; seed < 32; seed++) {
            EDIFNetlist netlist = EDIFTools.createNewNetlist("hw_top");
            randomBranch(netlist, netlist.getTopCell(), random, 0, new int[] {0});
            netlist.getTopCell().createChildCellInst("GND", primitive(netlist, "LUT1"));
            netlist.getTopCell().createChildCellInst("tie_high", primitive(netlist, "VCC"));
            netlist.getTopCell().createChildCellInst("empty", new EDIFCell(netlist.getWorkLibrary(), "empty_type"));
            SourceNode tree = sourceTree(netlist.getTopCell(), null, "hw_top");
            List<SourceNode> leaves = new ArrayList<>();
            for (SourceNode node : all(tree)) if (node.functional()) leaves.add(node);
            Collections.shuffle(leaves, random);
            int count = 1 + random.nextInt(Math.min(4, leaves.size()));
            Map<SourceNode, String> expected = new LinkedHashMap<>();
            for (int i = 0; i < leaves.size(); i++) expected.put(leaves.get(i), labels(count).get(i < count ? i : random.nextInt(count)));
            Map<Integer, Set<String>> coarse = new LinkedHashMap<>();
            for (int p = 0; p < count; p++) coarse.put(p, new LinkedHashSet<>());
            coarseFrontier(tree, tree, expected, labels(count), coarse);
            for (boolean compact : new boolean[] {false, true}) {
                verifyExport(netlist, tree, expected, leafFrontier(tree, expected, count), count,
                        directory.resolve("seed_" + seed + "_leaf_" + compact), compact);
                verifyExport(netlist, tree, expected, coarse, count,
                        directory.resolve("seed_" + seed + "_coarse_" + compact), compact);
            }
        }
    }

    @Test
    public void reusedModuleDefinitionsRetainDistinctSourceInstances(@TempDir Path directory) throws IOException {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("hw_top");
        EDIFCell shared = new EDIFCell(netlist.getWorkLibrary(), "shared_counter_type");
        shared.createChildCellInst("state[0]", primitive(netlist, "FDRE"));
        shared.createChildCellInst("value[0].no_snake", primitive(netlist, "LUT1"));
        for (int i = 0; i < 3; i++) netlist.getTopCell().createChildCellInst("counters[" + i + "].no_snake/kept", shared);
        SourceNode tree = sourceTree(netlist.getTopCell(), null, "hw_top");
        Map<SourceNode, String> expected = new LinkedHashMap<>();
        for (int i = 0; i < tree.children.size(); i++) {
            for (SourceNode leaf : tree.children.get(i).children) expected.put(leaf, labels(3).get(i));
        }
        for (boolean compact : new boolean[] {false, true}) {
            verifyExport(netlist, tree, expected, leafFrontier(tree, expected, 3), 3,
                    directory.resolve("shared_" + compact), compact);
        }
    }

    @Test
    public void literalSlashSiblingDoesNotBecomeItsTextualParentsChild(@TempDir Path directory) throws IOException {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("hw_top");
        EDIFCell cluster = new EDIFCell(netlist.getWorkLibrary(), "cluster_type");
        cluster.createChildCellInst("inside", primitive(netlist, "FDRE"));
        netlist.getTopCell().createChildCellInst("cluster", cluster);
        netlist.getTopCell().createChildCellInst("cluster/helper", primitive(netlist, "FDRE"));
        for (int i = 0; i < 4; i++) netlist.getTopCell().createChildCellInst("local_" + i, primitive(netlist, "FDRE"));
        SourceNode tree = sourceTree(netlist.getTopCell(), null, "hw_top");
        Map<SourceNode, String> expected = new LinkedHashMap<>();
        for (SourceNode node : all(tree)) {
            if (node.functional()) expected.put(node, node.path.contains("cluster") ? "FPGA_B" : "FPGA_A");
        }
        Map<Integer, Set<String>> coarse = new LinkedHashMap<>();
        coarse.put(0, new LinkedHashSet<>(Arrays.asList("local_0", "local_1", "local_2", "local_3")));
        coarse.put(1, new LinkedHashSet<>(Arrays.asList("cluster", "cluster/helper")));
        for (boolean compact : new boolean[] {false, true}) {
            Path output = directory.resolve("literal_sibling_" + compact);
            verifyExport(netlist, tree, expected, coarse, 2, output, compact);
            Assertions.assertTrue(Files.readAllLines(output.resolve("mapping_records.tsv")).contains(
                    "hw_top/cluster/helper\tFPGA_B\thw_top"));
        }
    }

    private static void permutations(List<String> prefix, List<String> remaining, List<List<String>> result) {
        if (remaining.isEmpty()) { result.add(new ArrayList<>(prefix)); return; }
        for (int i = 0; i < remaining.size(); i++) {
            List<String> rest = new ArrayList<>(remaining);
            String next = rest.remove(i);
            prefix.add(next);
            permutations(prefix, rest, result);
            prefix.remove(prefix.size() - 1);
        }
    }

    @Test
    public void everyAncestorOrderingOfTheGoldenNestedExampleIsChecked(@TempDir Path directory) throws IOException {
        EDIFNetlist netlist = fiveLeafTree();
        List<String> golden = Arrays.asList("hw_top/cluster_b/local_helper FPGA_A",
                "hw_top/cluster_b/compute FPGA_C", "hw_top/cluster_b FPGA_B", "hw_top FPGA_A");
        List<List<String>> alternatives = new ArrayList<>();
        permutations(new ArrayList<>(), golden, alternatives);
        int valid = 0, invalid = 0;
        for (int i = 0; i < alternatives.size(); i++) {
            List<String> rules = alternatives.get(i);
            // The independent relation checks every original source ancestor,
            // rather than assuming lexical sorting implies a correct order.
            boolean legal = rules.indexOf(golden.get(0)) < rules.indexOf(golden.get(2))
                    && rules.indexOf(golden.get(1)) < rules.indexOf(golden.get(2))
                    && rules.indexOf(golden.get(2)) < rules.indexOf(golden.get(3));
            Path target = directory.resolve("permutation_" + i).resolve("mapping.txt");
            if (legal) {
                HierMappingWriter.writeMappingFile(rules, target.toString(), netlist, labels(3));
                valid++;
            } else {
                Assertions.assertThrows(IllegalArgumentException.class,
                        () -> HierMappingWriter.writeMappingFile(rules, target.toString(), netlist, labels(3)));
                Assertions.assertFalse(Files.exists(target));
                Assertions.assertFalse(Files.exists(target.resolveSibling("mapping.txt.pending")));
                invalid++;
            }
        }
        Assertions.assertEquals(2, valid, "independent siblings may appear in either stable order");
        Assertions.assertEquals(22, invalid);
    }
}
