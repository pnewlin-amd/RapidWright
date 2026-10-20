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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

public class TestMappingReencoder {
    private static final List<String> FRONTIER = Arrays.asList(
            "vertex\tpath\tlogical_luts\tprimary_weight",
            "1\tflat/a\t1\t1", "2\tflat/b\t1\t1", "3\ta\t1\t1", "4\tb\t1\t1");

    private static final class Fixture {
        final Path source, legacy, cells, frontier, assignment;
        final EDIFNetlist netlist;

        Fixture(Path directory) throws IOException {
            Files.createDirectories(directory);
            source = directory.resolve("source.edf");
            legacy = directory.resolve("legacy_mapping.txt");
            cells = directory.resolve("cells.txt");
            frontier = directory.resolve("frontier.tsv");
            assignment = directory.resolve("final_assignment.part");
            makeSource("flat/b").exportEDIF(source);
            netlist = EDIFTools.readEdifFile(source);
            Files.write(legacy, Arrays.asList("hw_top/flat FPGA_B", "hw_top FPGA_A"), StandardCharsets.UTF_8);
            Files.write(cells, Arrays.asList("FPGA_A", "FPGA_B"), StandardCharsets.UTF_8);
            Files.write(frontier, FRONTIER, StandardCharsets.UTF_8);
            Files.write(assignment, Arrays.asList("1", "1", "0", "0"), StandardCharsets.UTF_8);
        }

        JSONObject reencode(Path output) throws IOException {
            return MappingReencoder.reencode(netlist, source, legacy, cells, frontier, assignment, output);
        }

        Map<Path, String> inputHashes() throws Exception {
            Map<Path, String> hashes = new LinkedHashMap<>();
            for (Path path : Arrays.asList(source, legacy, cells, frontier, assignment)) hashes.put(path, hash(path));
            return hashes;
        }
    }

    private static EDIFNetlist makeSource(String secondFlatName) {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("hw_top");
        EDIFCell lut = new EDIFCell(netlist.getHDIPrimitivesLibrary(), "LUT1");
        for (String name : Arrays.asList("flat/a", secondFlatName, "a", "b")) {
            netlist.getTopCell().createChildCellInst(name, lut);
        }
        return netlist;
    }

    @Test
    public void imaginaryLegacyPrefixBecomesExactSourceRecordsWithIdenticalLeafOwners(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory.resolve("inputs"));
        Map<Path, String> before = fixture.inputHashes();
        Assertions.assertNull(fixture.netlist.getHierCellInstFromName("flat"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingContract.validate(fixture.netlist,
                Files.readAllLines(fixture.legacy), Files.readAllLines(fixture.cells), null));

        Path output = directory.resolve("legal");
        JSONObject proof = fixture.reencode(output);
        List<String> mapping = Files.readAllLines(output.resolve("mapping.txt"));
        Assertions.assertEquals(Arrays.asList("hw_top/flat/a FPGA_B", "hw_top/flat/b FPGA_B", "hw_top FPGA_A"), mapping);
        MappingContract.validate(fixture.netlist, mapping, Files.readAllLines(output.resolve("cells.txt")), null);
        Assertions.assertEquals("PASS", proof.getString("status"));
        Assertions.assertEquals(4, proof.getLong("functional_leaves_compared"));
        Assertions.assertEquals(0, proof.getLong("ownership_mismatches"));
        Assertions.assertEquals(before.get(fixture.source), proof.getString("input_sha256"));
        Assertions.assertEquals(before.get(fixture.legacy), proof.getString("legacy_mapping_sha256"));
        Assertions.assertEquals(before.get(fixture.frontier), proof.getString("frontier_sha256"));
        Assertions.assertEquals(before.get(fixture.assignment), proof.getString("final_assignment_sha256"));
        Assertions.assertEquals(hash(output.resolve("mapping.txt")), proof.getString("mapping_sha256"));
        Assertions.assertTrue(proof.getString("native_measurement_scope").startsWith("No new Vivado measurement:"));
        JSONObject saved = new JSONObject(Files.readString(output.resolve("reencoding_equivalence.json")));
        Assertions.assertEquals(proof.keySet(), saved.keySet());
        for (String key : proof.keySet()) Assertions.assertEquals(proof.get(key).toString(), saved.get(key).toString(), key);
        JSONObject contract = new JSONObject(Files.readString(output.resolve("mapping_contract.json")));
        Assertions.assertEquals(before.get(fixture.source), contract.getString("input_sha256"));
        Assertions.assertEquals(4, contract.getLong("functional_leaf_count"));
        Assertions.assertTrue(contract.getBoolean("frontier_ownership_compared"));
        Assertions.assertEquals(hash(output.resolve("mapping_ownership.tsv.gz")), contract.getString("ownership_manifest_sha256"));

        Map<String, String> leafOwners = ownership(output.resolve("mapping_ownership.tsv.gz"));
        Assertions.assertEquals(4, leafOwners.size());
        Assertions.assertEquals("FPGA_A", leafOwners.get("hw_top/a"));
        Assertions.assertEquals("FPGA_A", leafOwners.get("hw_top/b"));
        Assertions.assertEquals("FPGA_B", leafOwners.get("hw_top/flat/a"));
        Assertions.assertEquals("FPGA_B", leafOwners.get("hw_top/flat/b"));
        Assertions.assertEquals(before, fixture.inputHashes());
        Assertions.assertFalse(Files.exists(output.resolve("reencoding.pending")));
    }

    @Test
    public void changedSavedAssignmentCannotPublishAgainstHistoricalOwners(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory.resolve("inputs"));
        Files.write(fixture.assignment, Arrays.asList("0", "0", "1", "1"), StandardCharsets.UTF_8);
        Map<Path, String> before = fixture.inputHashes();
        Path output = directory.resolve("rejected");
        Assertions.assertThrows(IllegalArgumentException.class, () -> fixture.reencode(output));
        Assertions.assertFalse(Files.exists(output.resolve("mapping.txt")));
        Assertions.assertFalse(Files.exists(output.resolve("reencoding_equivalence.json")));
        Assertions.assertEquals(before, fixture.inputHashes());
    }

    @Test
    public void existingSuccessfulOutputIsNeverOverwritten(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory.resolve("inputs"));
        Path output = directory.resolve("legal");
        fixture.reencode(output);
        Map<String, String> before = directoryHashes(output);
        Assertions.assertThrows(IllegalArgumentException.class, () -> fixture.reencode(output));
        Assertions.assertEquals(before, directoryHashes(output));
    }

    @Test
    public void malformedOrNondenseFrontierFailsBeforeFinalPublication(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory.resolve("inputs"));
        List<List<String>> malformed = Arrays.asList(
                Arrays.asList("vertex\tpath\twrong\tprimary_weight", "1\tflat/a\t1\t1"),
                Arrays.asList(FRONTIER.get(0), "2\tflat/a\t1\t1", "3\tflat/b\t1\t1", "4\ta\t1\t1", "5\tb\t1\t1"),
                Arrays.asList(FRONTIER.get(0), "1\tflat/a\t1\t1", "3\tflat/b\t1\t1", "4\ta\t1\t1", "5\tb\t1\t1"),
                Arrays.asList(FRONTIER.get(0), "1\tflat/a\t1\t1", "1\tflat/b\t1\t1", "3\ta\t1\t1", "4\tb\t1\t1"),
                Arrays.asList(FRONTIER.get(0), "1\tflat/a\t1", "2\tflat/b\t1\t1", "3\ta\t1\t1", "4\tb\t1\t1"),
                Arrays.asList(FRONTIER.get(0), "1\tflat/a\t1\t1", "2\tflat/a\t1\t1", "3\ta\t1\t1", "4\tb\t1\t1"));
        for (int index = 0; index < malformed.size(); index++) {
            Files.write(fixture.frontier, malformed.get(index), StandardCharsets.UTF_8);
            Path output = directory.resolve("bad_frontier_" + index);
            Assertions.assertThrows(IllegalArgumentException.class, () -> fixture.reencode(output));
            Assertions.assertFalse(Files.exists(output.resolve("mapping.txt")));
        }
    }

    @Test
    public void malformedAssignmentsAndNoncanonicalCellOrderFail(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory.resolve("inputs"));
        List<List<String>> malformed = Arrays.asList(Arrays.asList("1", "1", "0"),
                Arrays.asList("1", "1", "0", "0", "0"), Arrays.asList("2", "1", "0", "0"),
                Arrays.asList("-1", "1", "0", "0"), Arrays.asList("bad", "1", "0", "0"));
        for (int index = 0; index < malformed.size(); index++) {
            Files.write(fixture.assignment, malformed.get(index), StandardCharsets.UTF_8);
            Path output = directory.resolve("bad_assignment_" + index);
            Assertions.assertThrows(IllegalArgumentException.class, () -> fixture.reencode(output));
            Assertions.assertFalse(Files.exists(output.resolve("mapping.txt")));
        }
        Files.write(fixture.assignment, Arrays.asList("1", "1", "0", "0"), StandardCharsets.UTF_8);
        Files.write(fixture.cells, Arrays.asList("FPGA_B", "FPGA_A"), StandardCharsets.UTF_8);
        Path output = directory.resolve("bad_cells");
        Assertions.assertThrows(IllegalArgumentException.class, () -> fixture.reencode(output));
        Assertions.assertFalse(Files.exists(output.resolve("mapping.txt")));
    }

    @Test
    public void savedFrontierFromWrongGenerateBranchIsRejected(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory.resolve("inputs"));
        Path differentSource = directory.resolve("different_source.edf");
        makeSource("flat/c").exportEDIF(differentSource);
        EDIFNetlist differentNetlist = EDIFTools.readEdifFile(differentSource);
        Path output = directory.resolve("wrong_source");
        Assertions.assertThrows(IllegalArgumentException.class, () -> MappingReencoder.reencode(differentNetlist,
                differentSource, fixture.legacy, fixture.cells, fixture.frontier, fixture.assignment, output));
        Assertions.assertFalse(Files.exists(output.resolve("mapping.txt")));
    }

    private static Map<String, String> ownership(Path path) throws IOException {
        String text;
        try (GZIPInputStream stream = new GZIPInputStream(Files.newInputStream(path))) {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> rows = new ArrayList<>(Arrays.asList(text.split("\n")));
        Assertions.assertEquals("instance_path\tref_name\tclassification\tlogical_fpga\tgoverning_record\tfrontier_owner", rows.remove(0));
        Map<String, String> result = new LinkedHashMap<>();
        for (String row : rows) {
            String[] fields = row.split("\t", -1);
            Assertions.assertEquals(6, fields.length);
            Assertions.assertEquals("FUNCTIONAL", fields[2]);
            Assertions.assertNull(result.put(fields[0], fields[3]));
        }
        return result;
    }

    private static Map<String, String> directoryHashes(Path directory) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) result.put(path.getFileName().toString(), hash(path));
        }
        return result;
    }

    private static String hash(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder value = new StringBuilder();
        for (byte octet : digest) value.append(String.format("%02x", octet & 255));
        return value.toString();
    }
}
