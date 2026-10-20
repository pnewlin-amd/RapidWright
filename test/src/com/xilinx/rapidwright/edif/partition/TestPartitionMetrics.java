/* Copyright (c) 2026, Advanced Micro Devices, Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

public class TestPartitionMetrics {
    private static EDIFCell primitive(EDIFNetlist netlist, String name) {
        return new EDIFCell(netlist.getHDIPrimitivesLibrary(), name);
    }

    @Test
    public void logicalCensusCountsRepeatedCellTypesAndSeparatesMemory() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("metrics");
        EDIFCell block = new EDIFCell(netlist.getWorkLibrary(), "block");
        block.createChildCellInst("lut", primitive(netlist, "LUT6_2"));
        block.createChildCellInst("ff", primitive(netlist, "FDRE"));
        block.createChildCellInst("ram", primitive(netlist, "RAM64X1S"));
        block.createChildCellInst("srl", primitive(netlist, "SRLC32E"));
        block.createChildCellInst("bram", primitive(netlist, "RAMB36E2"));
        netlist.getTopCell().createChildCellInst("a", block);
        netlist.getTopCell().createChildCellInst("b", block);
        netlist.getTopCell().createChildCellInst("unknown", primitive(netlist, "MY_LUT_MACRO"));
        LogicalResourceCounts.Counts counts = new LogicalResourceCounts().count(netlist);
        Assertions.assertEquals(2, counts.logicLuts);
        Assertions.assertEquals(2, counts.flipFlops);
        Assertions.assertEquals(2, counts.lutRamPrimitives);
        Assertions.assertEquals(2, counts.shiftRegisterPrimitives);
        Assertions.assertEquals(4, counts.bram18Equivalent);
        Assertions.assertEquals(11, counts.primitiveCount());
        Assertions.assertEquals(1L, counts.primitiveHistogram().get("MY_LUT_MACRO").longValue());
        Assertions.assertFalse(logicDiscoveryPolicy.isLogicLUT("MY_LUT_MACRO"));
    }

    @Test
    public void expandedLutRamUsesExactUnisimTypesAndPrimitiveMetadata() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("expanded_memory");
        for (String type : Arrays.asList("RAMD32", "RAMD32M64", "RAMD64E", "RAMD64E5",
                                        "RAMS32", "RAMS64E", "RAMS64E1", "RAMS64E5")) {
            netlist.getTopCell().createChildCellInst(type.toLowerCase(), primitive(netlist, type));
            Assertions.assertTrue(LogicalResourceCounts.isLutRam(type));
            Assertions.assertTrue(LogicalResourceCounts.isClockPin(type, "CLK"));
        }
        netlist.getTopCell().createChildCellInst("custom", new EDIFCell(netlist.getWorkLibrary(), "RAMD32"));
        LogicalResourceCounts.Counts counts = new LogicalResourceCounts().count(netlist);
        Assertions.assertEquals(8, counts.lutRamPrimitives);
        Assertions.assertEquals(9, counts.primitiveCount());
        Assertions.assertEquals(2L, counts.primitiveHistogram().get("RAMD32").longValue());
        Assertions.assertEquals(0, counts.logicLuts);
        Assertions.assertFalse(LogicalResourceCounts.isLutRam("MY_RAMD32_MACRO"));
        Assertions.assertFalse(LogicalResourceCounts.isLutRam("RAMD64"));
        Assertions.assertFalse(LogicalResourceCounts.isLutRam("RAMS64E2"));
    }

    @Test
    public void legacyLutCountingRequiresHdiPrimitiveMetadata() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("trusted_luts");
        netlist.getTopCell().createChildCellInst("real", primitive(netlist, "LUT1"));
        netlist.getTopCell().createChildCellInst("custom", new EDIFCell(netlist.getWorkLibrary(), "LUT1"));
        Assertions.assertEquals(1, logicDiscoveryPolicy.leafLutCount(netlist.getHierCellInstFromName("real")));
        Assertions.assertEquals(0, logicDiscoveryPolicy.leafLutCount(netlist.getHierCellInstFromName("custom")));
        Assertions.assertEquals(1, LUTCountingUtils.computeLUTCount(netlist.getTopHierCellInst(),
                new java.util.IdentityHashMap<>(), null).intValue());
        Assertions.assertEquals(1, new LogicalResourceCounts().count(netlist).logicLuts);
    }

    private static EDIFCell module(EDIFNetlist netlist, String name, EDIFDirection direction) {
        EDIFCell module = new EDIFCell(netlist.getWorkLibrary(), name);
        module.createPort("P", direction, 1);
        EDIFCell lut = netlist.getHDIPrimitivesLibrary().getCell("LUT1");
        if (lut == null) {
            lut = primitive(netlist, "LUT1");
            lut.createPort("I0", EDIFDirection.INPUT, 1);
            lut.createPort("O", EDIFDirection.OUTPUT, 1);
        }
        EDIFCellInst inst = module.createChildCellInst("logic_i_1", lut);
        EDIFNet net = module.createNet("local");
        net.createPortInst(module.getPort("P"));
        net.createPortInst(direction == EDIFDirection.OUTPUT ? "O" : "I0", inst);
        return module;
    }

    @Test
    public void hierarchyAliasesAndRepeatedNamesHaveExactDirectedCuts() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("cuts");
        EDIFCell top = netlist.getTopCell();
        EDIFCellInst a = top.createChildCellInst("a", module(netlist, "source", EDIFDirection.OUTPUT));
        EDIFCell sink = module(netlist, "sink", EDIFDirection.INPUT);
        EDIFCellInst b = top.createChildCellInst("b", sink);
        EDIFCellInst c = top.createChildCellInst("c", sink);
        EDIFNet net = top.createNet("misleading_clk_data");
        net.createPortInst("P", a); net.createPortInst("P", b); net.createPortInst("P", c);
        Map<EDIFHierCellInst, Integer> frontier = new LinkedHashMap<>();
        frontier.put(netlist.getHierCellInstFromName("a"), 1);
        frontier.put(netlist.getHierCellInstFromName("b"), 2);
        frontier.put(netlist.getHierCellInstFromName("c"), 3);
        Map<String, Integer> mapping = new LinkedHashMap<>();
        mapping.put("a", 0); mapping.put("b", 1); mapping.put("c", 2);
        FpgaIoCutAnalyzer.FpgaIoCutResult result = FpgaIoCutAnalyzer.analyze(netlist, frontier, mapping, 0);
        Assertions.assertEquals(1, result.canonicalNets);
        Assertions.assertEquals(1, result.totalDataSignals);
        Assertions.assertEquals(2, result.directionalDataSignals);
        Assertions.assertEquals(1, result.directionalCuts.get("FPGA_A->FPGA_B").intValue());
        Assertions.assertEquals(1, result.directionalCuts.get("FPGA_A->FPGA_C").intValue());
        Assertions.assertTrue(result.clockNets.isEmpty());
        Assertions.assertTrue(result.unanalyzedNets.isEmpty());
        mapping.remove("c");
        Assertions.assertThrows(IllegalArgumentException.class, () -> FpgaIoCutAnalyzer.analyze(netlist, frontier, mapping, 0));
    }

    @Test
    public void strictMappingResolutionUsesLongestAncestorOnly() {
        Map<String, Integer> mapping = new LinkedHashMap<>();
        mapping.put("same_reg", 3); mapping.put("a", 0); mapping.put("a/sub", 1);
        Assertions.assertEquals(1, FpgaIoCutAnalyzer.resolvePartition("a/sub/same_reg", mapping).intValue());
        Assertions.assertNull(FpgaIoCutAnalyzer.resolvePartition("b/same_reg", mapping));
    }

    @Test
    public void compressionPreservesPrimitiveOverrides(@TempDir Path dir) throws Exception {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("soc");
        EDIFCell lut = primitive(netlist, "LUT1");
        netlist.getTopCell().createChildCellInst("a_reg[0]", lut);
        netlist.getTopCell().createChildCellInst("b_i_1", lut);
        EDIFCell namedGnd = new EDIFCell(netlist.getWorkLibrary(), "ordinary_logic_type");
        namedGnd.createChildCellInst("real_logic", lut);
        netlist.getTopCell().createChildCellInst("GND", namedGnd);
        Files.write(dir.resolve("cells.txt"), Arrays.asList("FPGA_A", "FPGA_B"), StandardCharsets.UTF_8);
        Files.write(dir.resolve("FPGA_A"), Arrays.asList("a_reg[0]", "GND/real_logic"), StandardCharsets.UTF_8);
        Files.write(dir.resolve("FPGA_B"), Arrays.asList("b_i_1"), StandardCharsets.UTF_8);
        for (boolean optimize : new boolean[] {false, true}) {
            List<String> lines = HierMappingWriter.buildMappingFromPartitionFiles(dir.toString(), dir.resolve("cells.txt").toString(), null, optimize, netlist);
            Assertions.assertTrue(lines.stream().anyMatch(s -> s.equals("soc/b_i_1 FPGA_B")));
            Assertions.assertFalse(lines.isEmpty());
        }
        Files.write(dir.resolve("FPGA_B"), Arrays.asList("a_reg[0]"), StandardCharsets.UTF_8);
        Assertions.assertThrows(IllegalArgumentException.class, () -> HierMappingWriter.buildMappingFromPartitionFiles(dir.toString(), dir.resolve("cells.txt").toString(), null, true, netlist));
    }

    @Test
    public void pairCounterHonorsEdgeWeightsAndEdgeSection(@TempDir Path dir) throws Exception {
        Path hgr = dir.resolve("weighted.hgr");
        Files.write(hgr, Arrays.asList("1 3 11 2", "99 1 2", "1 2", "1 2", "1 2"), StandardCharsets.UTF_8);
        Map<String, Integer> mapping = new LinkedHashMap<>();
        mapping.put("a", 0); mapping.put("b", 1); mapping.put("c", 2);
        Map<String, Integer> counts = PartitionPairCounter.countPartitionPairs(hgr, new String[] {null, "a", "b", "c"}, mapping);
        Assertions.assertEquals(1, counts.get("FPGA_A,FPGA_B").intValue());
        Assertions.assertEquals(2, counts.size());
    }

    @Test
    public void connectivityReportSkipsFmt11WeightsAndVertexSection(@TempDir Path dir) throws Exception {
        Path hgr = dir.resolve("weighted.hgr"), names = dir.resolve("weighted.eidmap"), output = dir.resolve("nets.txt");
        Files.write(hgr, Arrays.asList("% named weighted fixture", "1 3 11 2", "99 1 2", "1 2", "5 6", "7 8"));
        Files.write(names, Arrays.asList("actual_data_net"));
        Map<String, Integer> mapping = new LinkedHashMap<>();
        mapping.put("a", 0); mapping.put("b", 1); mapping.put("c", 1);
        PartitionTools.writeConnectivityReportWithNames(hgr, names, new String[] {null, "a", "b", "c"}, mapping, output);
        String report = Files.readString(output);
        Assertions.assertTrue(report.contains("Net 1: actual_data_net"));
        Assertions.assertTrue(report.contains("edge_weight=99"));
        Assertions.assertTrue(report.contains("cut=true blocks: FPGA_A=1 pin, FPGA_B=1 pin"));
        Assertions.assertFalse(report.contains("Net 2:"));
        Assertions.assertFalse(report.contains("99:"));
        Assertions.assertFalse(report.contains("UNASSIGNED"));
        mapping.remove("b");
        Assertions.assertThrows(IllegalArgumentException.class, () -> PartitionTools.writeConnectivityReportWithNames(
                hgr, names, new String[] {null, "a", "b", "c"}, mapping, output));
    }

    @Test
    public void clockClassificationUsesPinMetadataAndMixedUseIsData() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("control");
        EDIFCell top = netlist.getTopCell();
        top.createPort("unnamed_control", EDIFDirection.INPUT, 1);
        EDIFCell ff = primitive(netlist, "FDRE");
        ff.createPort("C", EDIFDirection.INPUT, 1);
        EDIFCellInst inst = top.createChildCellInst("ff", ff);
        EDIFNet clock = top.createNet("nothing_like_a_clock_name");
        clock.createPortInst(top.getPort("unnamed_control"));
        clock.createPortInst("C", inst);
        Map<EDIFHierCellInst, Integer> frontier = new LinkedHashMap<>();
        frontier.put(netlist.getHierCellInstFromName("ff"), 1);
        Map<String, Integer> mapping = new LinkedHashMap<>();
        mapping.put("ff", 1);
        FpgaIoCutAnalyzer.FpgaIoCutResult result = FpgaIoCutAnalyzer.analyze(netlist, frontier, mapping, 0);
        Assertions.assertEquals(1, result.clockNets.size());
        Assertions.assertEquals(0, result.totalDataSignals);
        EDIFCell lut = primitive(netlist, "LUT1");
        lut.createPort("I0", EDIFDirection.INPUT, 1);
        EDIFCellInst data = top.createChildCellInst("data", lut);
        clock.createPortInst("I0", data);
        frontier.put(netlist.getHierCellInstFromName("data"), 2);
        mapping.put("data", 1);
        result = FpgaIoCutAnalyzer.analyze(netlist, frontier, mapping, 0);
        Assertions.assertTrue(result.clockNets.isEmpty());
        Assertions.assertEquals(1, result.totalDataSignals);
    }

    @Test
    public void inoutEndpointsRemainVisible() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("bidirectional");
        EDIFCell box = primitive(netlist, "opaque");
        box.createPort("IO", EDIFDirection.INOUT, 1);
        EDIFCellInst inst = netlist.getTopCell().createChildCellInst("box", box);
        EDIFNet net = netlist.getTopCell().createNet("io");
        net.createPortInst("IO", inst);
        FpgaIoCutAnalyzer.NetEndpoints endpoints = FpgaIoCutAnalyzer.collectEndpoints(netlist.getHierNetFromName("io"));
        Assertions.assertEquals(1, endpoints.endpoints.size());
        Assertions.assertTrue(endpoints.endpoints.get(0).driver);
        Assertions.assertTrue(endpoints.endpoints.get(0).load);
    }

    @Test
    public void constantExclusionRequiresVendorPrimitiveDriver() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("misleading_constants");
        EDIFCell custom = new EDIFCell(netlist.getWorkLibrary(), "GND");
        custom.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCell lut = primitive(netlist, "LUT1");
        lut.createPort("I0", EDIFDirection.INPUT, 1);
        EDIFCellInst source = netlist.getTopCell().createChildCellInst("source", custom);
        EDIFCellInst sink = netlist.getTopCell().createChildCellInst("sink", lut);
        EDIFNet net = netlist.getTopCell().createNet("<const0>");
        net.createPortInst("O", source); net.createPortInst("I0", sink);
        Assertions.assertFalse(FpgaIoCutAnalyzer.collectEndpoints(netlist.getHierNetFromName("<const0>")).constant);
        EDIFCell gnd = primitive(netlist, "GND");
        gnd.createPort("G", EDIFDirection.OUTPUT, 1);
        EDIFCellInst actual = netlist.getTopCell().createChildCellInst("actual", gnd);
        EDIFNet real = netlist.getTopCell().createNet("ordinary_name");
        real.createPortInst("G", actual);
        Assertions.assertTrue(FpgaIoCutAnalyzer.collectEndpoints(netlist.getHierNetFromName("ordinary_name")).constant);
    }

    @Test
    public void legacyGraphExportRetainsMisleadingConstantNames(@TempDir Path dir) throws Exception {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("legacy_constant_metadata");
        EDIFCell custom = new EDIFCell(netlist.getWorkLibrary(), "GND");
        custom.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCell lut = primitive(netlist, "LUT1");
        lut.createPort("I0", EDIFDirection.INPUT, 1);
        EDIFCell gnd = primitive(netlist, "GND");
        gnd.createPort("G", EDIFDirection.OUTPUT, 1);
        EDIFCellInst source = netlist.getTopCell().createChildCellInst("source", custom);
        EDIFCellInst sink = netlist.getTopCell().createChildCellInst("sink", lut);
        EDIFCellInst actual = netlist.getTopCell().createChildCellInst("actual", gnd);
        EDIFNet net = netlist.getTopCell().createNet("<const0>");
        net.createPortInst("O", source); net.createPortInst("I0", sink);
        EDIFNet real = netlist.getTopCell().createNet("ordinary_name");
        real.createPortInst("G", actual);
        Path edif = dir.resolve("input.edf");
        netlist.exportEDIF(edif);
        Path output = dir.resolve("export");
        new PartitionGraphExporter().export(edif, output, 1, java.util.Collections.emptySet());
        org.json.JSONObject manifest = new org.json.JSONObject(Files.readString(output.resolve("manifest.json")));
        Assertions.assertEquals(2, manifest.getJSONArray("vertices").length());
        Assertions.assertEquals(1, manifest.getInt("edge_count"));
        Assertions.assertEquals(1, manifest.getJSONObject("total_resources").getInt("UNKNOWN"));
    }

    @Test
    public void nativeOracleRejectsStaleMappingAndKeepsPhysicalPackingOverhead(@TempDir Path dir) throws Exception {
        Path mapping = dir.resolve("mapping.txt");
        Files.write(mapping, Arrays.asList("top FPGA_A", "top/b FPGA_B"), StandardCharsets.UTF_8);
        Path reference = dir.resolve("reference");
        Files.createDirectories(reference);
        Files.copy(mapping, reference.resolve("source_mapping.txt"));
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest(Files.readAllBytes(mapping))) hash.append(String.format("%02x", value & 255));
        Path checkpoint = dir.resolve("fixture.dcp");
        Files.write(checkpoint, Arrays.asList("original checkpoint fixture"), StandardCharsets.UTF_8);
        StringBuilder checkpointHash = new StringBuilder();
        for (byte value : digest.digest(Files.readAllBytes(checkpoint))) checkpointHash.append(String.format("%02x", value & 255));
        Files.write(reference.resolve("provenance.txt"), Arrays.asList("checkpoint=" + checkpoint.toAbsolutePath(),
                "checkpoint_sha256=" + checkpointHash, "mapping_sha256=" + hash), StandardCharsets.UTF_8);
        Files.write(reference.resolve("native_complete.txt"), Arrays.asList("status=VIVADO_PARTITION_RESOURCE_MAPPING_PASS",
                "checkpoint_sha256=" + checkpointHash, "mapping_sha256=" + hash), StandardCharsets.UTF_8);
        Path table = reference.resolve("vivado_resources.tsv");
        Files.write(table, Arrays.asList("partition\tclb_luts\tflip_flops\tblock_ram_tiles",
                "DESIGN\t6\t2\t0.5", "FPGA_A\t3\t1\t0", "FPGA_B\t4\t1\t0.5"), StandardCharsets.UTF_8);
        VivadoResourceOracle oracle = VivadoResourceOracle.read(table, mapping);
        oracle.validateMaxClbLuts(4);
        Assertions.assertThrows(VivadoResourceOracle.CapacityViolationException.class, () -> oracle.validateMaxClbLuts(3));
        Map<String, Long> caps = new LinkedHashMap<>();
        caps.put("FPGA_A", 3L); caps.put("FPGA_B", 4L);
        oracle.validateClbLutCaps(caps);
        caps.put("FPGA_B", 3L);
        Assertions.assertThrows(VivadoResourceOracle.CapacityViolationException.class, () -> oracle.validateClbLutCaps(caps));
        Path rejected = dir.resolve("rejected_cap_output");
        Assertions.assertThrows(VivadoResourceOracle.CapacityViolationException.class,
                () -> VivadoResourceOracle.main(new String[] {table.toString(), mapping.toString(), rejected.toString(), "--max-luts", "3"}));
        Assertions.assertFalse(Files.exists(rejected));
        Path capsFile = dir.resolve("caps.tsv");
        Files.write(capsFile, Arrays.asList("partition\tclb_luts", "FPGA_A\t3"), StandardCharsets.UTF_8);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> VivadoResourceOracle.main(new String[] {table.toString(), mapping.toString(), rejected.toString(), "--lut-caps", capsFile.toString()}));
        Assertions.assertFalse(Files.exists(rejected));
        Files.write(capsFile, Arrays.asList("partition\tclb_luts", "FPGA_A\t3", "FPGA_A\t4"), StandardCharsets.UTF_8);
        Assertions.assertThrows(IllegalArgumentException.class, () -> VivadoResourceOracle.readClbLutCaps(capsFile));
        Files.write(capsFile, Arrays.asList("partition\twrong_header", "FPGA_A\t3"), StandardCharsets.UTF_8);
        Assertions.assertThrows(IllegalArgumentException.class, () -> VivadoResourceOracle.readClbLutCaps(capsFile));
        Assertions.assertEquals(6, oracle.design.clbLuts());
        Assertions.assertEquals(new java.math.BigDecimal("0.5"), oracle.design.required("block_ram_tiles"));
        Files.write(dir.resolve("lut_report.txt"), Arrays.asList("9LUTS FPGA_A"), StandardCharsets.UTF_8);
        Files.write(dir.resolve("metric_definitions.txt"), Arrays.asList(
                "lut_report.txt and logical_resources.tsv count exact logical primitive instances.",
                "They are NOT packed CLB LUT utilization: use the native exporter.",
                "fpga_io_cuts.txt counts canonical directed logical net destinations."), StandardCharsets.UTF_8);
        oracle.writeReports(dir);
        Assertions.assertEquals("9LUTS FPGA_A", Files.readAllLines(dir.resolve("logical_lut_report.txt")).get(0));
        Assertions.assertEquals("3LUTS FPGA_A", Files.readAllLines(dir.resolve("lut_report.txt")).get(0));
        Assertions.assertTrue(Files.readAllLines(dir.resolve("vivado_resource_summary.txt")).contains("subset_sum_minus_whole_design=1"));
        List<String> definitions = Files.readAllLines(dir.resolve("metric_definitions.txt"));
        Assertions.assertTrue(definitions.get(0).contains("exact native Vivado CLB LUT Used"));
        Assertions.assertTrue(definitions.get(1).startsWith("logical_lut_report.txt and logical_resources.tsv"));
        Assertions.assertTrue(definitions.contains("fpga_io_cuts.txt counts canonical directed logical net destinations."));
        Assertions.assertFalse(definitions.stream().anyMatch(s -> s.startsWith("They are NOT packed")
                || s.equals("lut_report.txt and logical_resources.tsv count exact logical primitive instances.")));
        Files.write(checkpoint, Arrays.asList("changed checkpoint with same path"), StandardCharsets.UTF_8);
        IllegalArgumentException changedSource = Assertions.assertThrows(IllegalArgumentException.class, () -> VivadoResourceOracle.read(table, mapping));
        Assertions.assertTrue(changedSource.getMessage().contains("source checkpoint fingerprint"));
        Files.write(checkpoint, Arrays.asList("original checkpoint fixture"), StandardCharsets.UTF_8);
        Files.write(mapping, Arrays.asList("top FPGA_B"), StandardCharsets.UTF_8);
        IllegalArgumentException stale = Assertions.assertThrows(IllegalArgumentException.class, () -> VivadoResourceOracle.read(table, mapping));
        Assertions.assertTrue(stale.getMessage().contains("fingerprint"));
        Files.write(mapping, Arrays.asList("top FPGA_A", "top/b FPGA_B"), StandardCharsets.UTF_8);
        Files.write(table, Arrays.asList("partition\tclb_luts\tflip_flops", "DESIGN\t6\t2", "FPGA_A\t3\t1"), StandardCharsets.UTF_8);
        Assertions.assertThrows(IllegalArgumentException.class, () -> VivadoResourceOracle.read(table, mapping));
        Files.delete(reference.resolve("native_complete.txt"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> VivadoResourceOracle.read(table, mapping));
    }

    @Test
    public void constantOutliersDoNotSplitUniformParentOrChooseHome(@TempDir Path dir) throws Exception {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("compact");
        EDIFCell lut = primitive(netlist, "LUT1");
        EDIFCell gnd = primitive(netlist, "GND");
        EDIFCell vcc = primitive(netlist, "VCC");
        EDIFCell parent = new EDIFCell(netlist.getWorkLibrary(), "parent_type");
        Set<String> a = new LinkedHashSet<>(), b = new LinkedHashSet<>();
        for (String name : Arrays.asList("A", "B", "C", "D")) {
            parent.createChildCellInst(name, lut);
            a.add("parent/" + name);
        }
        parent.createChildCellInst("tie_low", gnd);
        parent.createChildCellInst("tie_high", vcc);
        b.add("parent/tie_low"); b.add("parent/tie_high");
        netlist.getTopCell().createChildCellInst("parent", parent);
        EDIFCell constants = new EDIFCell(netlist.getWorkLibrary(), "constants_only");
        constants.createChildCellInst("low", gnd);
        constants.createChildCellInst("high", vcc);
        netlist.getTopCell().createChildCellInst("constant_box", constants);
        b.add("constant_box");
        for (int i = 0; i < 6; i++) {
            String name = "other" + i;
            netlist.getTopCell().createChildCellInst(name, lut);
            b.add(name);
        }
        Map<Integer, Set<String>> parts = new LinkedHashMap<>();
        parts.put(0, a); parts.put(1, b);
        HierMappingWriter.write(dir, netlist, parts, java.util.Collections.emptyMap(), true);
        Assertions.assertEquals(Arrays.asList("compact/parent FPGA_A", "compact FPGA_B"),
                Files.readAllLines(dir.resolve("mapping.txt")));
        parts.put(0, new LinkedHashSet<>(Arrays.asList("parent")));
        b.remove("parent/tie_low"); b.remove("parent/tie_high");
        Path second = dir.resolve("coarse_frontier");
        HierMappingWriter.write(second, netlist, parts, java.util.Collections.emptyMap(), true);
        Assertions.assertEquals(Arrays.asList("compact/parent FPGA_A", "compact FPGA_B"),
                Files.readAllLines(second.resolve("mapping.txt")));
    }

    @Test
    public void aNonconstantInstanceNamedVccRemainsMapped(@TempDir Path dir) throws Exception {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("typed");
        EDIFCell lut = primitive(netlist, "LUT1");
        netlist.getTopCell().createChildCellInst("VCC", lut);
        netlist.getTopCell().createChildCellInst("other0", lut);
        netlist.getTopCell().createChildCellInst("other1", lut);
        netlist.getTopCell().createChildCellInst("real_constant", primitive(netlist, "GND"));
        Map<Integer, Set<String>> parts = new LinkedHashMap<>();
        parts.put(0, new LinkedHashSet<>(Arrays.asList("VCC")));
        parts.put(1, new LinkedHashSet<>(Arrays.asList("other0", "other1", "real_constant")));
        HierMappingWriter.write(dir, netlist, parts, java.util.Collections.emptyMap(), true);
        Assertions.assertTrue(Files.readAllLines(dir.resolve("mapping.txt")).contains("typed/VCC FPGA_A"));
        Assertions.assertTrue(Files.readAllLines(dir.resolve("mapping.txt")).stream().noneMatch(s -> s.contains("real_constant")));
    }
}
