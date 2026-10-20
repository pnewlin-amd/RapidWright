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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import org.json.JSONObject;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;

/** Pre-regroup mapping contract. Never calls or models the regroup implementation. */
public final class MappingContract {
    public static final String SCOPE = "pre_regroup_source_hierarchy_and_ordered_ownership";

    private MappingContract() { }

    /** Strict grammar; retain order and reject even identical duplicate assignments. */
    public static LinkedHashMap<String, String> parse(List<String> lines) {
        LinkedHashMap<String, String> rules = new LinkedHashMap<>();
        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) continue;
            String[] fields = text.split("\\s+");
            if (fields.length != 2 || !fields[1].matches("FPGA_[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("Mapping requires exactly <instance_path> <FPGA_name>: " + line);
            }
            checkPath(fields[0]);
            if (rules.putIfAbsent(fields[0], fields[1]) != null) {
                throw new IllegalArgumentException("Duplicate mapping source instance: " + fields[0]);
            }
        }
        if (rules.isEmpty()) throw new IllegalArgumentException("Empty mapping");
        return rules;
    }

    private static void checkPath(String path) {
        if (path.isEmpty() || path.matches(".*[\\s*?\\\\\"'#].*") || path.startsWith("/")
                || path.endsWith("/") || path.contains("//")
                || path.chars().anyMatch(character -> Character.isWhitespace(character)
                        || Character.isSpaceChar(character) || Character.isISOControl(character))) {
            throw new IllegalArgumentException("Unrepresentable mapping instance path: " + path);
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Nonliteral mapping path segment: " + path);
            }
        }
    }

    /** Type-based classification: a real LUT named VCC is functional. */
    static boolean constant(EDIFCell cell) {
        return cell.isPrimitive() && ("GND".equals(cell.getName()) || "VCC".equals(cell.getName()));
    }

    /** A zero-interface nonprimitive with no child instances cannot populate an FPGA. */
    static boolean emptyHierarchy(EDIFCell cell) {
        return !cell.isPrimitive() && cell.getCellInsts().isEmpty() && cell.getPorts().isEmpty();
    }

    static boolean hasFunctionalLeaf(EDIFCell cell, Map<EDIFCell, Boolean> cache) {
        Boolean old = cache.get(cell);
        if (old != null) return old;
        boolean result = false;
        if (emptyHierarchy(cell)) result = false;
        else if (cell.isLeafCellOrBlackBox()) result = !constant(cell);
        else for (EDIFCellInst child : cell.getCellInsts()) {
            if (hasFunctionalLeaf(child.getCellType(), cache)) { result = true; break; }
        }
        cache.put(cell, result);
        return result;
    }

    static String rooted(String root, String relative) {
        return relative.isEmpty() ? root : root + "/" + relative;
    }

    static String nearestTextRule(String path, Map<String, String> rules) {
        while (true) {
            if (rules.containsKey(path)) return path;
            int slash = path.lastIndexOf('/');
            if (slash < 0) return null;
            path = path.substring(0, slash);
        }
    }

    /** Source-aware validation, including order, exact ancestry and complete frontier coverage. */
    public static void validate(EDIFNetlist netlist, List<String> lines,
            List<String> destinations, Map<String, Set<String>> frontier) {
        try { certify(netlist, lines, destinations, frontier, null, null); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    /** Writes evidence only after validating the source hierarchy, streaming expanded leaves. */
    static JSONObject certify(EDIFNetlist netlist, List<String> lines, List<String> destinations,
            Map<String, Set<String>> frontier, Path mapping, Path output) throws IOException {
        if (netlist == null) throw new IllegalArgumentException("Exact synthesized EDIF netlist is required");
        String root = netlist.getTopCell().getName();
        checkPath(root);
        if (root.contains("/")) throw new IllegalArgumentException("DUT root must be one exact instance token: " + root);
        LinkedHashMap<String, String> rules = parse(lines);
        if (!rules.containsKey(root) || !new ArrayList<>(rules.keySet()).get(rules.size() - 1).equals(root)) {
            throw new IllegalArgumentException("Explicit residual DUT root must be the final mapping record: " + root);
        }
        Set<String> labels = new LinkedHashSet<>();
        for (String label : destinations) {
            if (!label.matches("FPGA_[A-Za-z0-9_]+") || !labels.add(label)) {
                throw new IllegalArgumentException("Invalid or duplicate cells.txt destination: " + label);
            }
        }
        if (!labels.equals(new LinkedHashSet<>(rules.values()))) {
            throw new IllegalArgumentException("cells.txt must equal the unique logical destinations in mapping.txt");
        }
        Map<String, Integer> order = new HashMap<>();
        int index = 0;
        for (String path : rules.keySet()) order.put(path, index++);
        Map<EDIFCell, Boolean> functional = new java.util.IdentityHashMap<>();
        List<String> records = new ArrayList<>();
        records.add("instance_path\tlogical_fpga\tnearest_mapped_ancestor");
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            String path = rule.getKey();
            if (!path.equals(root) && !path.startsWith(root + "/")) {
                throw new IllegalArgumentException("Mapping path outside synthesized DUT root: " + path);
            }
            String relative = path.equals(root) ? "" : path.substring(root.length() + 1);
            EDIFHierCellInst instance = netlist.getHierCellInstFromName(relative);
            if (instance == null || !instance.getFullHierarchicalInstName().equals(relative)) {
                throw new IllegalArgumentException("Nonexistent or wrong synthesized instance path: " + path);
            }
            if (!hasFunctionalLeaf(instance.getCellType(), functional)) {
                throw new IllegalArgumentException("Explicit constant-only mapping record is forbidden: " + path);
            }
            String nearest = "";
            for (EDIFHierCellInst parent = instance.getParent(); parent != null; parent = parent.getParent()) {
                String parentPath = rooted(root, parent.getFullHierarchicalInstName());
                if (!rules.containsKey(parentPath)) continue;
                if (order.get(path) >= order.get(parentPath)) {
                    throw new IllegalArgumentException("Mapped child must precede ancestor: " + path + " before " + parentPath);
                }
                if (nearest.isEmpty()) nearest = parentPath;
            }
            if (!nearest.isEmpty() && rules.get(nearest).equals(rule.getValue())) {
                throw new IllegalArgumentException("Redundant mapping equals nearest mapped ancestor: " + path + " / " + nearest);
            }
            records.add(path + "\t" + rule.getValue() + "\t" + nearest);
        }
        Map<String, String> expected = null;
        if (frontier != null) {
            expected = new HashMap<>();
            for (Map.Entry<String, Set<String>> entry : frontier.entrySet()) {
                if (!labels.contains(entry.getKey())) throw new IllegalArgumentException("Unknown frontier destination");
                for (String path : entry.getValue()) {
                    EDIFHierCellInst inst = netlist.getHierCellInstFromName(path);
                    if (inst == null) throw new IllegalArgumentException("Nonexistent frontier path: " + path);
                    if (expected.putIfAbsent(path, entry.getKey()) != null) {
                        throw new IllegalArgumentException("Duplicate frontier assignment: " + path);
                    }
                }
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String label : labels) counts.put(label, 0L);
        long[] totals = new long[3];
        Path ownership = output == null ? null : output.resolve("mapping_ownership.tsv.gz.pending");
        try (BufferedWriter writer = ownership == null ? null : new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(ownership)), StandardCharsets.UTF_8))) {
            if (writer != null) writer.write("instance_path\tref_name\tclassification\tlogical_fpga\tgoverning_record\tfrontier_owner\n");
            visit(netlist, netlist.getTopHierCellInst(), root, rules, expected, null, null, null, counts, totals, writer);
        } catch (RuntimeException | IOException failure) {
            if (ownership != null) Files.deleteIfExists(ownership);
            throw failure;
        }
        for (Map.Entry<String, Long> count : counts.entrySet()) {
            if (count.getValue() == 0) {
                if (ownership != null) Files.deleteIfExists(ownership);
                throw new IllegalArgumentException("Destination contains no functional source leaves: " + count.getKey());
            }
        }
        JSONObject report = new JSONObject();
        report.put("schema_version", 1);
        report.put("status", "PASS");
        report.put("scope", SCOPE);
        report.put("root_instance", root);
        report.put("root_convention", "Synthesized top-cell name used for the DUT instance by the consumer; not RapidWright's synthetic top instance name");
        report.put("record_count", rules.size());
        report.put("functional_leaf_count", totals[0]);
        report.put("local_constant_leaf_count", totals[1]);
        report.put("empty_hierarchy_count", totals[2]);
        report.put("functional_leaves_by_destination", new JSONObject(counts));
        report.put("frontier_ownership_compared", frontier != null);
        report.put("source_paths_exact", true);
        report.put("descendants_before_ancestors", true);
        report.put("nearest_ancestor_redundancy_absent", true);
        report.put("residual_root_explicit", true);
        report.put("cells_agreement", true);
        report.put("downstream_regroup_verified", false);
        if (output != null) {
            Path finalOwnership = output.resolve("mapping_ownership.tsv.gz");
            Files.move(ownership, finalOwnership, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Path recordPath = output.resolve("mapping_records.tsv");
            Files.write(recordPath, records, StandardCharsets.UTF_8);
            report.put("mapping_sha256", sha256(mapping));
            report.put("ownership_manifest_sha256", sha256(finalOwnership));
            report.put("records_manifest_sha256", sha256(recordPath));
            Files.write(output.resolve("mapping_contract.json"),
                    java.util.Collections.singletonList(report.toString(2)), StandardCharsets.UTF_8);
        }
        return report;
    }

    private static void visit(EDIFNetlist netlist, EDIFHierCellInst instance, String root,
            Map<String, String> rules, Map<String, String> expected, String inherited,
            String governing, String expectedOwner, Map<String, Long> counts, long[] totals,
            BufferedWriter writer) throws IOException {
        String relative = instance.getFullHierarchicalInstName();
        String path = rooted(root, relative);
        if (rules.containsKey(path)) { inherited = rules.get(path); governing = path; }
        if (expected != null && expected.containsKey(relative)) {
            if (expectedOwner != null) throw new IllegalArgumentException("Overlapping frontier at " + path);
            expectedOwner = expected.get(relative);
        }
        EDIFCell type = instance.getCellType();
        boolean isEmpty = emptyHierarchy(type);
        if (!isEmpty && !type.isLeafCellOrBlackBox()) {
            for (EDIFCellInst child : type.getCellInsts()) {
                visit(netlist, instance.getChild(child), root, rules, expected, inherited,
                        governing, expectedOwner, counts, totals, writer);
            }
            return;
        }
        checkPath(path);
        boolean isConstant = constant(type);
        if (!isConstant && !isEmpty) {
            if (inherited == null || (expected != null && expectedOwner == null)) {
                throw new IllegalArgumentException("Unaccounted functional source leaf: " + path);
            }
            if (expected != null && !inherited.equals(expectedOwner)) {
                throw new IllegalArgumentException("Mapping changes frontier ownership of " + path);
            }
            // The native oracle and current consumer also use serialized slash paths.
            // Refuse ambiguous flattened names instead of certifying incompatible ownership.
            String textRule = nearestTextRule(path, rules);
            if (textRule == null || !inherited.equals(rules.get(textRule))) {
                throw new IllegalArgumentException("Ambiguous slash-name mapping ownership: " + path);
            }
            if (!instance.equals(netlist.getHierCellInstFromName(relative))) {
                throw new IllegalArgumentException("Ambiguous serialized source instance: " + path);
            }
            counts.put(inherited, counts.get(inherited) + 1);
            totals[0]++;
        } else totals[isConstant ? 1 : 2]++;
        if (writer != null) {
            String classification = isConstant ? "LOCAL_CONSTANT" : isEmpty ? "EMPTY_HIERARCHY" : "FUNCTIONAL";
            writer.write(path + "\t" + type.getName() + "\t" + classification
                    + "\t" + (isConstant || isEmpty ? classification : inherited) + "\t" + governing + "\t"
                    + (expectedOwner == null ? "" : expectedOwner) + "\n");
        }
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int length;
                while ((length = input.read(buffer)) >= 0) digest.update(buffer, 0, length);
            }
            StringBuilder value = new StringBuilder();
            for (byte b : digest.digest()) value.append(String.format("%02x", b & 255));
            return value.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Audit several existing maps against one in-memory source, without changing maps. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException(
                "Usage: synthesized.edf audit_jobs.tsv (mapping_path, cells_path, output_directory; no header)");
        Path source = java.nio.file.Paths.get(args[0]);
        String sourceHash = sha256(source);
        EDIFNetlist netlist = com.xilinx.rapidwright.edif.EDIFTools.readEdifFile(source);
        for (String line : Files.readAllLines(java.nio.file.Paths.get(args[1]), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) throw new IllegalArgumentException("Audit job needs mapping/cells/output paths");
            Path mapping = java.nio.file.Paths.get(fields[0]);
            Path output = java.nio.file.Paths.get(fields[2]);
            Files.createDirectories(output);
            List<String> labels = Files.readAllLines(java.nio.file.Paths.get(fields[1]), StandardCharsets.UTF_8);
            JSONObject report = certify(netlist, Files.readAllLines(mapping, StandardCharsets.UTF_8),
                    labels, null, mapping, output);
            report.put("input_sha256", sourceHash);
            report.put("audit_mode", "existing_mapping_bytes_unchanged; intended owners derived from source-valid ordered records");
            Files.write(output.resolve("mapping_contract.json"),
                    java.util.Collections.singletonList(report.toString(2)), StandardCharsets.UTF_8);
            System.out.println("MAPPING_CONTRACT_PASS " + mapping + " functional_leaves=" + report.getLong("functional_leaf_count"));
        }
        if (!sourceHash.equals(sha256(source))) throw new IllegalStateException("Audit source changed");
    }
}
