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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

public class TestMappingContract {
    private static EDIFCell primitive(EDIFNetlist netlist, String name) {
        EDIFCell cell = netlist.getHDIPrimitivesLibrary().getCell(name);
        return cell == null ? new EDIFCell(netlist.getHDIPrimitivesLibrary(), name) : cell;
    }

    private static EDIFNetlist simple() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("hw_top");
        netlist.getTopCell().createChildCellInst("a", primitive(netlist, "LUT1"));
        netlist.getTopCell().createChildCellInst("b", primitive(netlist, "FDRE"));
        return netlist;
    }

    private static EDIFNetlist nested() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("hw_top");
        netlist.getTopCell().createChildCellInst("root_reg", primitive(netlist, "FDRE"));
        EDIFCell cluster = new EDIFCell(netlist.getWorkLibrary(), "cluster_type");
        cluster.createChildCellInst("local_helper", primitive(netlist, "FDRE"));
        cluster.createChildCellInst("compute", primitive(netlist, "LUT1"));
        cluster.createChildCellInst("remaining", primitive(netlist, "LUT1"));
        netlist.getTopCell().createChildCellInst("cluster_b", cluster);
        return netlist;
    }

    private static Map<Integer, Set<String>> parts(String[]... paths) {
        Map<Integer, Set<String>> result = new LinkedHashMap<>();
        for (int i = 0; i < paths.length; i++) {
            result.put(i, new LinkedHashSet<>(Arrays.asList(paths[i])));
        }
        return result;
    }

    private static Map<String, Set<String>> frontier(String[]... paths) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Map<Integer, Set<String>> indexed = parts(paths);
        for (Map.Entry<Integer, Set<String>> entry : indexed.entrySet()) {
            result.put(CellsWriter.generateName(entry.getKey()), entry.getValue());
        }
        return result;
    }

    @Test
    public void parentOnlyAndOneRemoteChildAreLegal() {
        EDIFNetlist netlist = simple();
        Assertions.assertDoesNotThrow(() -> MappingContract.validate(netlist,
                Arrays.asList("hw_top FPGA_A"), Arrays.asList("FPGA_A"), null));
        Assertions.assertDoesNotThrow(() -> MappingContract.validate(netlist,
                Arrays.asList("hw_top/b FPGA_B", "hw_top FPGA_A"),
                Arrays.asList("FPGA_A", "FPGA_B"), frontier(new String[] {"a"}, new String[] {"b"})));
    }

    @Test
    public void nestedReturnToRootUsesNearestMappedAncestor(@TempDir Path directory) throws IOException {
        EDIFNetlist netlist = nested();
        List<String> rules = Arrays.asList("hw_top/cluster_b/local_helper FPGA_A",
                "hw_top/cluster_b/compute FPGA_C", "hw_top/cluster_b FPGA_B", "hw_top FPGA_A");
        List<String> labels = Arrays.asList("FPGA_A", "FPGA_B", "FPGA_C");
        MappingContract.validate(netlist, rules, labels, frontier(
                new String[] {"root_reg", "cluster_b/local_helper"},
                new String[] {"cluster_b/remaining"}, new String[] {"cluster_b/compute"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(netlist,
                rules.subList(1, rules.size()), labels, frontier(
                        new String[] {"root_reg", "cluster_b/local_helper"},
                        new String[] {"cluster_b/remaining"}, new String[] {"cluster_b/compute"})));
        HierMappingWriter.writeMappingFile(rules, directory.resolve("mapping.txt").toString(), netlist, labels);
        Assertions.assertTrue(Files.readAllLines(directory.resolve("mapping_records.tsv")).contains(
                "hw_top/cluster_b/local_helper\tFPGA_A\thw_top/cluster_b"));
        String ownership = gunzip(directory.resolve("mapping_ownership.tsv.gz"));
        Assertions.assertTrue(ownership.contains("hw_top/cluster_b/local_helper\tFDRE\tFUNCTIONAL\tFPGA_A\thw_top/cluster_b/local_helper\t"));
        Assertions.assertTrue(ownership.contains("hw_top/cluster_b/remaining\tLUT1\tFUNCTIONAL\tFPGA_B\thw_top/cluster_b\t"));
    }

    @Test
    public void redundantNearestAncestorRecordIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(simple(),
                Arrays.asList("hw_top/a FPGA_A", "hw_top FPGA_A"), Arrays.asList("FPGA_A"), null));
    }

    @Test
    public void reverseOrderAndMissingRootAreRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(simple(),
                Arrays.asList("hw_top FPGA_A", "hw_top/b FPGA_B"), Arrays.asList("FPGA_A", "FPGA_B"), null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(nested(),
                Arrays.asList("hw_top/cluster_b FPGA_B", "hw_top/cluster_b/compute FPGA_C", "hw_top FPGA_A"),
                Arrays.asList("FPGA_A", "FPGA_B", "FPGA_C"), null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(simple(),
                Arrays.asList("hw_top/a FPGA_A", "hw_top/b FPGA_B"), Arrays.asList("FPGA_A", "FPGA_B"), null));
    }

    @Test
    public void identicalDuplicatesAndMalformedRecordsAreRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.parse(
                Arrays.asList("hw_top FPGA_A", "hw_top FPGA_A")));
        for (String invalid : Arrays.asList("hw_top", "hw_top FPGA_A # inline", "\"hw_top\" FPGA_A",
                "'hw_top' FPGA_A", "hw_top/* FPGA_A", "hw_top/q? FPGA_A", "hw_top FPGA_",
                "hw_top C01_S01_E", "hw_top FPGA-A", "hw_top/a\\[0\\] FPGA_A")) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> MappingContract.parse(Arrays.asList(invalid)), invalid);
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.parse(Collections.emptyList()));
        Assertions.assertEquals(Arrays.asList("hw_top/a[0].no_snake.logic", "hw_top"),
                new ArrayList<>(MappingContract.parse(Arrays.asList("", "# whole-line comment",
                        "hw_top/a[0].no_snake.logic\tFPGA_1", "hw_top FPGA_target_2")).keySet()));
    }

    @Test
    public void unicodeSpacesAndControlCharactersCannotBecomeInstanceTokens() {
        for (char forbidden : new char[] {'\u00a0', '\u2007', '\u202f', '\u0000', '\u0001', '\u007f'}) {
            String name = "unsafe" + forbidden + "name";
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> MappingContract.parse(Arrays.asList("hw_top/" + name + " FPGA_A")));
            EDIFNetlist netlist = simple();
            netlist.getTopCell().createChildCellInst(name, primitive(netlist, "LUT1"));
            Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(netlist,
                    Arrays.asList("hw_top FPGA_A"), Arrays.asList("FPGA_A"), null));
        }
        for (String incomplete : Arrays.asList(" FPGA_A", "hw_top ", "\tFPGA_A", "hw_top\t")) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> MappingContract.parse(Arrays.asList(incomplete)));
        }
    }

    @Test
    public void existingMappingAndCompanionsAreUntouchedWhenOutputIsReused(@TempDir Path directory) throws Exception {
        EDIFNetlist netlist = simple();
        Map<Integer, Set<String>> original = parts(new String[] {"a"}, new String[] {"b"});
        HierMappingWriter.write(directory, netlist, original, Collections.emptyMap(), true);
        List<String> artifacts = Arrays.asList("mapping.txt", "cells.txt", "mapping_contract.json",
                "mapping_records.tsv", "mapping_ownership.tsv.gz");
        Map<String, String> before = new LinkedHashMap<>();
        for (String artifact : artifacts) before.put(artifact, independentHash(directory.resolve(artifact)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> HierMappingWriter.write(directory, netlist,
                parts(new String[] {"b"}, new String[] {"a"}), Collections.emptyMap(), true));
        Assertions.assertThrows(IllegalArgumentException.class, () -> HierMappingWriter.writeMappingFile(
                Arrays.asList("hw_top FPGA_A"), directory.resolve("mapping.txt").toString(), netlist, Arrays.asList("FPGA_A")));
        for (String artifact : artifacts) {
            Assertions.assertEquals(before.get(artifact), independentHash(directory.resolve(artifact)), artifact);
        }
        Assertions.assertFalse(Files.exists(directory.resolve("mapping.txt.pending")));
    }

    @Test
    public void wrongGenerateBranchAndNoninstanceNamesAreRejected() {
        EDIFNetlist netlist = simple();
        netlist.getTopCell().createChildCellInst("nodes[0].no_snake.logic", primitive(netlist, "LUT1"));
        List<String> labels = Arrays.asList("FPGA_A", "FPGA_B");
        MappingContract.validate(netlist, Arrays.asList("hw_top/nodes[0].no_snake.logic FPGA_B", "hw_top FPGA_A"), labels, null);
        for (String nonexistent : Arrays.asList("hw_top/nodes[0].has_snake.logic", "hw_top/LUT1", "simulation/hw_top/a")) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(netlist,
                    Arrays.asList(nonexistent + " FPGA_B", "hw_top FPGA_A"), labels, null));
        }
    }

    @Test
    public void missingFunctionalFrontierAndChangedOwnershipAreRejected() {
        EDIFNetlist netlist = simple();
        netlist.getTopCell().createChildCellInst("forgotten", primitive(netlist, "FDRE"));
        List<String> rules = Arrays.asList("hw_top/b FPGA_B", "hw_top FPGA_A");
        List<String> labels = Arrays.asList("FPGA_A", "FPGA_B");
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(netlist, rules, labels,
                frontier(new String[] {"a"}, new String[] {"b"})));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(netlist, rules, labels,
                frontier(new String[] {"b", "forgotten"}, new String[] {"a"})));
    }

    @Test
    public void cellsMustBeUniqueAndExactlyThePopulatedDestinations() {
        List<String> rules = Arrays.asList("hw_top/b FPGA_B", "hw_top FPGA_A");
        for (List<String> labels : Arrays.asList(Arrays.asList("FPGA_A"), Arrays.asList("FPGA_A", "FPGA_B", "FPGA_C"),
                Arrays.asList("FPGA_A", "FPGA_B", "FPGA_A"), Arrays.asList("FPGA_A", "C01_S01_E"))) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(simple(), rules, labels, null));
        }
    }

    @Test
    public void constantOnlyDestinationsAndExplicitConstantsCannotPublish(@TempDir Path directory) {
        EDIFNetlist netlist = simple();
        netlist.getTopCell().createChildCellInst("tie_low", primitive(netlist, "GND"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(netlist,
                Arrays.asList("hw_top/tie_low FPGA_B", "hw_top FPGA_A"), Arrays.asList("FPGA_A", "FPGA_B"), null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> HierMappingWriter.write(directory, netlist,
                parts(new String[] {"a", "b"}, new String[] {"tie_low"}), Collections.emptyMap(), true));
        Assertions.assertFalse(Files.exists(directory.resolve("mapping.txt")));
    }

    @Test
    public void emptyHierarchyCannotPopulateDestination(@TempDir Path directory) throws IOException {
        EDIFNetlist netlist = simple();
        EDIFCell empty = new EDIFCell(netlist.getWorkLibrary(), "empty_type");
        netlist.getTopCell().createChildCellInst("empty", empty);
        Assertions.assertThrows(IllegalArgumentException.class, () -> HierMappingWriter.write(directory, netlist,
                parts(new String[] {"a", "b"}, new String[] {"empty"}), Collections.emptyMap(), true));
        HierMappingWriter.write(directory, netlist, parts(new String[] {"a", "b", "empty"}), Collections.emptyMap(), true);
        JSONObject receipt = new JSONObject(Files.readString(directory.resolve("mapping_contract.json")));
        Assertions.assertEquals(2, receipt.getLong("functional_leaf_count"));
        Assertions.assertEquals(1, receipt.getLong("empty_hierarchy_count"));
        Assertions.assertTrue(gunzip(directory.resolve("mapping_ownership.tsv.gz")).contains(
                "hw_top/empty\tempty_type\tEMPTY_HIERARCHY\tEMPTY_HIERARCHY\thw_top\t"));
    }

    @Test
    public void compactAndVerboseNeverEmitRedundantMoves(@TempDir Path directory) throws IOException {
        EDIFNetlist netlist = simple();
        netlist.getTopCell().createChildCellInst("other", primitive(netlist, "LUT1"));
        for (boolean compact : new boolean[] {false, true}) {
            Path output = directory.resolve(compact ? "compact" : "verbose");
            HierMappingWriter.write(output, netlist, parts(new String[] {"a", "other"}, new String[] {"b"}),
                    Collections.emptyMap(), compact);
            List<String> lines = Files.readAllLines(output.resolve("mapping.txt"));
            Assertions.assertEquals(Arrays.asList("hw_top/b FPGA_B", "hw_top FPGA_A"), lines);
            MappingContract.validate(netlist, lines, Files.readAllLines(output.resolve("cells.txt")), null);
        }
    }

    @Test
    public void uncheckedPublicEmissionApisFailClosed(@TempDir Path directory) {
        Assertions.assertThrows(IllegalArgumentException.class, () -> HierMappingWriter.writeMappingFile(
                Arrays.asList("hw_top FPGA_A"), directory.resolve("mapping.txt").toString()));
        Assertions.assertThrows(IllegalArgumentException.class, () -> HierMappingWriter.buildMappingFromPartitionFiles(
                directory.toString(), directory.resolve("cells.txt").toString(), null, true));
        Assertions.assertThrows(IllegalArgumentException.class, () -> HierMappingWriter.buildMappingFromPartitionFiles(
                directory.toString(), directory.resolve("cells.txt").toString(), null, true, null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(null,
                Arrays.asList("hw_top FPGA_A"), Arrays.asList("FPGA_A"), null));
        Assertions.assertFalse(Files.exists(directory.resolve("mapping.txt")));
    }

    @Test
    public void literalSlashNamesNeverCreateManufacturedParents(@TempDir Path directory) throws IOException {
        EDIFNetlist netlist = simple();
        netlist.getTopCell().createChildCellInst("flat/a", primitive(netlist, "LUT1"));
        netlist.getTopCell().createChildCellInst("flat/b", primitive(netlist, "LUT1"));
        netlist.getTopCell().createChildCellInst("other", primitive(netlist, "FDRE"));
        Assertions.assertNull(netlist.getHierCellInstFromName("flat"));
        HierMappingWriter.write(directory, netlist,
                parts(new String[] {"a", "b", "other"}, new String[] {"flat/a", "flat/b"}), Collections.emptyMap(), true);
        List<String> lines = Files.readAllLines(directory.resolve("mapping.txt"));
        Assertions.assertTrue(lines.contains("hw_top/flat/a FPGA_B"));
        Assertions.assertTrue(lines.contains("hw_top/flat/b FPGA_B"));
        Assertions.assertFalse(lines.stream().anyMatch(line -> line.startsWith("hw_top/flat ")));
    }

    @Test
    public void textPrefixAndActualParentOwnershipDisagreementIsRejected() {
        EDIFNetlist netlist = simple();
        EDIFCell module = new EDIFCell(netlist.getWorkLibrary(), "x_type");
        module.createChildCellInst("inside", primitive(netlist, "LUT1"));
        netlist.getTopCell().createChildCellInst("x", module);
        netlist.getTopCell().createChildCellInst("x/y", primitive(netlist, "FDRE"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(netlist,
                Arrays.asList("hw_top/x FPGA_B", "hw_top FPGA_A"), Arrays.asList("FPGA_A", "FPGA_B"), null));
    }

    @Test
    public void ownershipGzipCountsHashesAndTypeClassificationAreBound(@TempDir Path directory) throws Exception {
        EDIFNetlist netlist = simple();
        netlist.getTopCell().createChildCellInst("VCC", primitive(netlist, "LUT1"));
        netlist.getTopCell().createChildCellInst("real_constant", primitive(netlist, "VCC"));
        HierMappingWriter.write(directory, netlist,
                parts(new String[] {"a", "VCC", "real_constant"}, new String[] {"b"}), Collections.emptyMap(), true);
        Path mapping = directory.resolve("mapping.txt");
        List<String> lines = Files.readAllLines(mapping);
        Assertions.assertFalse(lines.stream().anyMatch(line -> line.contains("real_constant")));
        JSONObject receipt = new JSONObject(Files.readString(directory.resolve("mapping_contract.json")));
        Assertions.assertEquals("PASS", receipt.getString("status"));
        Assertions.assertEquals(3, receipt.getLong("functional_leaf_count"));
        Assertions.assertEquals(1, receipt.getLong("local_constant_leaf_count"));
        Assertions.assertTrue(receipt.getBoolean("frontier_ownership_compared"));
        Assertions.assertFalse(receipt.getBoolean("downstream_regroup_verified"));
        Assertions.assertEquals(independentHash(mapping), receipt.getString("mapping_sha256"));
        Assertions.assertEquals(independentHash(directory.resolve("mapping_records.tsv")), receipt.getString("records_manifest_sha256"));
        Path ownership = directory.resolve("mapping_ownership.tsv.gz");
        Assertions.assertEquals(independentHash(ownership), receipt.getString("ownership_manifest_sha256"));
        String data = gunzip(ownership);
        Assertions.assertTrue(data.startsWith("instance_path\tref_name\tclassification\tlogical_fpga\tgoverning_record\tfrontier_owner\n"));
        Assertions.assertEquals(5, data.lines().count());
        Assertions.assertTrue(data.contains("hw_top/VCC\tLUT1\tFUNCTIONAL\tFPGA_A\t"));
        Assertions.assertTrue(data.contains("hw_top/real_constant\tVCC\tLOCAL_CONSTANT\tLOCAL_CONSTANT\t"));
        Assertions.assertEquals(2, receipt.getJSONObject("functional_leaves_by_destination").getLong("FPGA_A"));
        Assertions.assertEquals(1, receipt.getJSONObject("functional_leaves_by_destination").getLong("FPGA_B"));
    }

    private static String gunzip(Path path) throws IOException {
        try (GZIPInputStream stream = new GZIPInputStream(Files.newInputStream(path))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String independentHash(Path path) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder text = new StringBuilder();
        for (byte value : hash) text.append(String.format("%02x", value & 255));
        return text.toString();
    }
}
