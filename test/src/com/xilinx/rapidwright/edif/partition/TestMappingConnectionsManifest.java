/*
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

public class TestMappingConnectionsManifest {
    @TempDir Path directory;

    private static EDIFCell primitive(EDIFNetlist netlist, String name, String[] inputs, String[] outputs) {
        EDIFCell cell = new EDIFCell(netlist.getHDIPrimitivesLibrary(), name);
        for (String input : inputs) cell.createPort(input, EDIFDirection.INPUT, 1);
        for (String output : outputs) cell.createPort(output, EDIFDirection.OUTPUT, 1);
        return cell;
    }

    private static Map<EDIFHierCellInst, Integer> leaves(EDIFNetlist netlist) {
        Map<EDIFHierCellInst, Integer> result = new LinkedHashMap<>();
        for (EDIFHierCellInst leaf : netlist.getAllLeafHierCellInstances(true)) result.put(leaf, result.size() + 1);
        return result;
    }

    private static List<String[]> rows(Path file) throws Exception {
        List<String[]> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.add(line.split("\t", -1));
        }
        return result;
    }

    private static String[] endpoint(List<String[]> rows, String net, String instance, String pin) {
        return rows.stream().filter(row -> row[0].equals("ENDPOINT") && row[1].equals(net)
                && row[2].equals(instance) && row[3].equals(pin)).findFirst().orElseThrow();
    }

    @Test void localCrossingConstantTopAndInoutConnectionsArePreservedWithoutChangingMetrics() throws Exception {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("dut", false);
        EDIFCell top = netlist.getTopCell();
        EDIFCell logic = primitive(netlist, "BUF", new String[] {"I", "J"}, new String[] {"O", "P"});
        EDIFCellInst a = top.createChildCellInst("a", logic);
        EDIFCellInst b = top.createChildCellInst("b", logic);
        EDIFCellInst c = top.createChildCellInst("c", logic);
        EDIFNet cross = top.createNet("cross");
        cross.createPortInst("O", a); cross.createPortInst("I", b);
        EDIFNet local = top.createNet("local");
        local.createPortInst("P", a); local.createPortInst("I", c);
        EDIFCell gnd = primitive(netlist, "GND", new String[0], new String[] {"G"});
        EDIFCellInst constant = top.createChildCellInst("constant", gnd);
        EDIFNet zero = top.createNet("not_named_constant");
        zero.createPortInst("G", constant); zero.createPortInst("J", b);
        EDIFCell boxType = primitive(netlist, "IOBUF", new String[0], new String[0]);
        boxType.createPort("IO", EDIFDirection.INOUT, 1);
        EDIFCellInst box = top.createChildCellInst("box", boxType);
        top.createPort("io", EDIFDirection.INOUT, 1);
        EDIFNet io = top.createNet("io");
        io.createPortInst("IO", box); io.createPortInst(top.getPort("io"));
        com.xilinx.rapidwright.edif.EDIFPort bus = top.createPort("bus[3:0]", EDIFDirection.INPUT, 4);
        EDIFNet bit = top.createNet("bus_bit");
        bit.createPortInst(bus, 2); bit.createPortInst("J", c);
        Map<String, Integer> mapping = new LinkedHashMap<>();
        mapping.put("", 0); mapping.put("b", 1); mapping.put("box", 1);
        FpgaIoCutAnalyzer.FpgaIoCutResult baseline = FpgaIoCutAnalyzer.analyze(netlist, leaves(netlist), mapping, 0);
        Path file = directory.resolve("mapping_connections.tsv.gz");
        FpgaIoCutAnalyzer.FpgaIoCutResult actual = FpgaIoCutAnalyzer.analyze(netlist, leaves(netlist), mapping, 0, file);
        Assertions.assertEquals(baseline.canonicalNets, actual.canonicalNets);
        Assertions.assertEquals(baseline.netCrossings, actual.netCrossings);
        Assertions.assertEquals(baseline.directionalCuts, actual.directionalCuts);
        Assertions.assertEquals(baseline.controlCategories, actual.controlCategories);
        Assertions.assertEquals(baseline.filteredConstantNets, actual.filteredConstantNets);
        List<String[]> records = rows(file);
        Assertions.assertEquals("FPGA_A", endpoint(records, "dut/cross", "dut/a", "O")[8]);
        Assertions.assertEquals("FPGA_B", endpoint(records, "dut/cross", "dut/b", "I")[8]);
        Assertions.assertEquals("CROSSING", endpoint(records, "dut/cross", "dut/a", "O")[11]);
        Assertions.assertEquals("LOCAL", endpoint(records, "dut/local", "dut/c", "I")[11]);
        String[] source = endpoint(records, "dut/not_named_constant", "dut/constant", "G");
        Assertions.assertEquals("LOCAL_CONSTANT", source[8]);
        Assertions.assertEquals("LOCAL_CONSTANT_SOURCE", source[10]);
        Assertions.assertEquals("FPGA_B", endpoint(records, "dut/not_named_constant", "dut/b", "J")[8]);
        String[] inout = endpoint(records, "dut/io", "dut/box", "IO");
        Assertions.assertEquals(Arrays.asList("INOUT", "true", "true"), Arrays.asList(inout[5], inout[6], inout[7]));
        String[] topIo = endpoint(records, "dut/io", "dut", "io");
        Assertions.assertEquals("TOP_PORT", topIo[4]);
        Assertions.assertEquals("FPGA_A", topIo[8]);
        String[] busBit = records.stream().filter(row -> row[0].equals("ENDPOINT") && row[1].equals("dut/bus_bit")
                && row[4].equals("TOP_PORT")).findFirst().orElseThrow();
        Assertions.assertTrue(busBit[3].startsWith("bus["));
        Assertions.assertEquals(Arrays.asList("INPUT", "true", "false", "FPGA_A", "1"),
                Arrays.asList(busBit[5], busBit[6], busBit[7], busBit[8], busBit[9]));
        Assertions.assertEquals(5, actual.connectionManifestNets);
        Assertions.assertEquals(10, actual.connectionManifestEndpoints);
        FpgaIoCutAnalyzer.writeReport(directory, actual);
        JSONObject receipt = new JSONObject(Files.readString(directory.resolve("mapping_connections_manifest.json")));
        Assertions.assertEquals("NOT_RUN", receipt.getString("downstream_regroup_verification"));
        Assertions.assertEquals("dut", receipt.getString("source_root"));
        Assertions.assertEquals(actual.connectionsManifestSha256, receipt.getString("sha256"));
        StringBuilder hash = new StringBuilder();
        for (byte octet : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))) {
            hash.append(String.format("%02x", octet & 255));
        }
        Assertions.assertEquals(hash.toString(), actual.connectionsManifestSha256);
    }

    @Test void hierarchyAliasesAndDisconnectedNetsRemainInManifestWithExactGenerateNames() throws Exception {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("dut", false);
        EDIFCell module = new EDIFCell(netlist.getWorkLibrary(), "module");
        module.createPort("P", EDIFDirection.OUTPUT, 1);
        EDIFCell source = primitive(netlist, "BUF", new String[0], new String[] {"O"});
        EDIFCellInst primitive = module.createChildCellInst("source", source);
        EDIFNet inside = module.createNet("internal");
        inside.createPortInst(module.getPort("P")); inside.createPortInst("O", primitive);
        module.createNet("disconnected");
        EDIFCellInst instance = netlist.getTopCell().createChildCellInst("gen[0].no_snake", module);
        netlist.getTopCell().createPort("out", EDIFDirection.OUTPUT, 1);
        EDIFNet outside = netlist.getTopCell().createNet("external");
        outside.createPortInst("P", instance); outside.createPortInst(netlist.getTopCell().getPort("out"));
        Map<String, Integer> mapping = new LinkedHashMap<>();
        mapping.put("", 0); mapping.put("gen[0].no_snake", 1);
        Path file = directory.resolve("mapping_connections.tsv.gz");
        FpgaIoCutAnalyzer.FpgaIoCutResult actual = FpgaIoCutAnalyzer.analyze(netlist, leaves(netlist), mapping, 0, file);
        List<String[]> records = rows(file);
        Assertions.assertEquals(1, actual.canonicalNets);
        Assertions.assertEquals(2, actual.connectionManifestNets);
        Assertions.assertTrue(records.stream().anyMatch(row -> row[0].equals("NET")
                && row[1].equals("dut/gen[0].no_snake/disconnected")));
        Assertions.assertTrue(records.stream().anyMatch(row -> row[0].equals("ALIAS")
                && row[2].equals("dut/gen[0].no_snake/internal")));
        Assertions.assertEquals("FPGA_B", endpoint(records, "dut/external", "dut/gen[0].no_snake/source", "O")[8]);
    }

    @Test void internalAliasesBelowCoarseFrontierAreEmittedOnceWithoutChangingCutCounters() throws Exception {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("dut", false);
        EDIFCell register = primitive(netlist, "FDRE", new String[] {"D"}, new String[] {"Q"});
        EDIFCell inner = new EDIFCell(netlist.getWorkLibrary(), "inner_type");
        inner.createPort("entry", EDIFDirection.INPUT, 1);
        EDIFCellInst sink = inner.createChildCellInst("sink", register);
        EDIFNet entry = inner.createNet("entry_wire");
        entry.createPortInst(inner.getPort("entry"));
        entry.createPortInst("D", sink);
        EDIFCell outer = new EDIFCell(netlist.getWorkLibrary(), "outer_type");
        EDIFCellInst source = outer.createChildCellInst("source", register);
        EDIFCellInst nested = outer.createChildCellInst("inner", inner);
        EDIFNet wire = outer.createNet("wire");
        wire.createPortInst("Q", source);
        wire.createPortInst("entry", nested);
        EDIFCellInst instance = netlist.getTopCell().createChildCellInst("outer", outer);
        Map<EDIFHierCellInst, Integer> frontier = new LinkedHashMap<>();
        frontier.put(netlist.getTopHierCellInst().getChild(instance), 1);
        Map<String, Integer> mapping = new LinkedHashMap<>();
        mapping.put("", 0);
        Path file = directory.resolve("mapping_connections.tsv.gz");
        FpgaIoCutAnalyzer.FpgaIoCutResult actual = FpgaIoCutAnalyzer.analyze(netlist, frontier, mapping, 0, file);
        // The coarse frontier has no ports and the top has no nets. Both
        // aliases must therefore be found only by the remaining-source pass.
        Assertions.assertEquals(0, actual.canonicalNets);
        Assertions.assertEquals(1, actual.connectionManifestNets);
        Assertions.assertEquals(2, actual.connectionManifestAliases);
        Assertions.assertEquals(2, actual.connectionManifestEndpoints);
        List<String[]> records = rows(file);
        Assertions.assertEquals(1, records.stream().filter(row -> row[0].equals("NET")).count());
        Assertions.assertEquals(2, records.stream().filter(row -> row[0].equals("ALIAS")).count());
        Assertions.assertEquals(2, records.stream().filter(row -> row[0].equals("ENDPOINT")).count());
        Assertions.assertEquals(5, records.stream().skip(1).map(row -> String.join("\t", row)).distinct().count());
        String canonical = records.stream().filter(row -> row[0].equals("NET")).findFirst().orElseThrow()[1];
        Assertions.assertEquals("FPGA_A", endpoint(records, canonical, "dut/outer/source", "Q")[8]);
        Assertions.assertEquals("FPGA_A", endpoint(records, canonical, "dut/outer/inner/sink", "D")[8]);
        FpgaIoCutAnalyzer.writeReport(directory, actual);
        JSONObject receipt = new JSONObject(Files.readString(directory.resolve("mapping_connections_manifest.json")));
        Assertions.assertEquals(1, receipt.getLong("canonical_nets"));
        Assertions.assertEquals(2, receipt.getLong("hierarchy_aliases"));
        Assertions.assertEquals(2, receipt.getLong("endpoints"));
    }

    @Test void failedOwnershipDoesNotPublishOrLeavePendingFilesAndReceiptRejectsTampering() throws Exception {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("dut", false);
        EDIFCell source = primitive(netlist, "BUF", new String[0], new String[] {"O"});
        EDIFCellInst instance = netlist.getTopCell().createChildCellInst("unmapped", source);
        netlist.getTopCell().createNet("data").createPortInst("O", instance);
        Path file = directory.resolve("mapping_connections.tsv.gz");
        Map<String, Integer> mapping = new LinkedHashMap<>();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FpgaIoCutAnalyzer.analyze(netlist, leaves(netlist), mapping, 0, file));
        Assertions.assertFalse(Files.exists(file));
        try (java.util.stream.Stream<Path> files = Files.list(directory)) { Assertions.assertEquals(0, files.count()); }
        mapping.put("", 0);
        FpgaIoCutAnalyzer.FpgaIoCutResult actual = FpgaIoCutAnalyzer.analyze(netlist, leaves(netlist), mapping, 0, file);
        Files.writeString(file, "tampered");
        Assertions.assertThrows(IllegalStateException.class, () -> FpgaIoCutAnalyzer.writeReport(directory, actual));
        Assertions.assertFalse(Files.exists(directory.resolve("mapping_connections_manifest.json")));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FpgaIoCutAnalyzer.analyze(netlist, leaves(netlist), mapping, null, file));
    }
}
