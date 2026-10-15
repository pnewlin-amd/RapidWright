/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

public class TestPrimitiveTimingPolicy {
    private static EDIFCell cell(EDIFNetlist netlist, String type, boolean trusted) {
        return new EDIFCell(trusted ? netlist.getHDIPrimitivesLibrary() : netlist.getWorkLibrary(), type);
    }

    private static void connect(EDIFCell top, String name, EDIFCellInst source, String output,
            EDIFCellInst sink, String input) {
        EDIFNet net = top.createNet(name);
        net.createPortInst(output, source);
        if (sink.getCellType().getPort(input).isBus()) net.createPortInst(input, 0, sink);
        else net.createPortInst(input, sink);
    }

    private static HardwarePartitionGraph build(EDIFNetlist netlist, String... instances) {
        Map<EDIFHierCellInst, Integer> vertices = new LinkedHashMap<>();
        for (int i = 0; i < instances.length; i++) vertices.put(netlist.getHierCellInstFromName(instances[i]), i + 1);
        return HardwarePartitionGraph.build(netlist, vertices);
    }

    private static CombinationalPathAnalyzer.Analysis analyze(HardwarePartitionGraph graph, int... assignment) {
        return new CombinationalPathAnalyzer(graph.ownerVertex, graph.sequential, graph.successors).analyze(assignment);
    }

    private static HardwarePartitionGraph through(String type, String input, Integer preg, boolean extraCascade) {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("timing", false);
        EDIFCell top = netlist.getTopCell();
        EDIFCell ff = cell(netlist, "FDRE", true);
        ff.createPort("D", EDIFDirection.INPUT, 1); ff.createPort("Q", EDIFDirection.OUTPUT, 1);
        EDIFCell middle = cell(netlist, type, true);
        middle.createPort(input, EDIFDirection.INPUT, 1);
        String output = type.startsWith("DSP") ? "P" : "Q";
        middle.createPort(output, EDIFDirection.OUTPUT, 1);
        EDIFCellInst source = top.createChildCellInst("source", ff);
        EDIFCellInst memory = top.createChildCellInst("middle", middle);
        EDIFCellInst sink = top.createChildCellInst("sink", ff);
        if (preg != null) memory.addProperty("PREG", preg);
        connect(top, "ingress", source, "Q", memory, input);
        connect(top, "egress", memory, output, sink, "D");
        if (extraCascade) {
            middle.createPort("ACOUT", EDIFDirection.OUTPUT, 1);
            EDIFNet extra = top.createNet("independent_cascade"); extra.createPortInst("ACOUT", memory);
        }
        return build(netlist, "source", "middle", "sink");
    }

    @Test
    void srlDataAndEnableCaptureButDynamicAddressStillHasCombinationalTransit() {
        for (String input : new String[] {"D", "CE"}) {
            HardwarePartitionGraph graph = through("SRL16E", input, null, false);
            Assertions.assertEquals(4, graph.ownerVertex.length);
            Assertions.assertEquals(1, analyze(graph, -1, 0, 1, 2).maxCrossings);
            Assertions.assertEquals(0, analyze(graph, -1, 0, 1, 2).transitCount);
            Assertions.assertEquals(2, graph.ownerVertex[3]);
            Assertions.assertEquals("middle@write_capture", graph.primitiveNames[3]);
            Assertions.assertTrue(graph.sequential[3]);
            Assertions.assertEquals(2, graph.nets.size()); // Capture model does not change FPGA net incidence.
        }
        Assertions.assertEquals(2, analyze(through("SRL16E", "A0", null, false), -1, 0, 1, 2).maxCrossings);
    }

    private static HardwarePartitionGraph memoryFeedback(String input, boolean trusted) {
        return memoryFeedback(input, trusted, input.equals("ADDRH"));
    }

    private static HardwarePartitionGraph memoryFeedback(String input, boolean trusted, boolean sharedRead) {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("memory_feedback", false);
        EDIFCell top = netlist.getTopCell();
        EDIFCell ram = cell(netlist, "RAM32M16", trusted);
        ram.createPort("DIA[1:0]", EDIFDirection.INPUT, 2);
        ram.createPort("ADDRH[4:0]", EDIFDirection.INPUT, 5);
        ram.createPort("DOA", EDIFDirection.OUTPUT, 1);
        ram.createPort("DOH", EDIFDirection.OUTPUT, 1);
        EDIFCell lut = cell(netlist, "LUT1", true);
        lut.createPort("I0", EDIFDirection.INPUT, 1); lut.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCellInst memory = top.createChildCellInst("memory", ram);
        EDIFCellInst comb = top.createChildCellInst("comb", lut);
        connect(top, "read", memory, sharedRead ? "DOH" : "DOA", comb, "I0");
        connect(top, "feedback", comb, "O", memory, input);
        return build(netlist, "memory", "comb");
    }

    @Test
    void lutramWritebackFeedbackIsCapturedIncludingVectorPortNames() {
        HardwarePartitionGraph graph = memoryFeedback("DIA", true);
        CombinationalPathAnalyzer.Analysis result = analyze(graph, -1, 0, 1);
        Assertions.assertTrue(result.complete);
        Assertions.assertEquals(0, result.cycleNodeCount);
        Assertions.assertEquals(2, result.maxCrossings); // RAM output -> comb -> RAM write capture.
        Assertions.assertEquals(3, graph.ownerVertex.length);
        Assertions.assertEquals(2, graph.nets.size());
        HardwarePartitionGraph.Net writeNet = graph.nets.stream().filter(net -> net.driver == 2).findFirst().orElseThrow();
        Assertions.assertArrayEquals(new int[] {1}, writeNet.sinks);
    }

    @Test
    void sharedReadWriteAddressRetainsRealCombinationalFeedbackAndCaptureEndpoint() {
        HardwarePartitionGraph graph = memoryFeedback("ADDRH", true);
        CombinationalPathAnalyzer.Analysis result = analyze(graph, -1, 0, 1);
        Assertions.assertFalse(result.complete);
        Assertions.assertEquals(2, result.cycleNodeCount);
        int comb = graph.primitiveNames[0].equals("comb") ? 0 : 1;
        Assertions.assertEquals(2, graph.successors[comb].length); // Read dependency plus write endpoint.
        HardwarePartitionGraph unusedRead = memoryFeedback("ADDRH", true, false);
        Assertions.assertTrue(analyze(unusedRead, -1, 0, 1).complete);
        Assertions.assertEquals(2, analyze(unusedRead, -1, 0, 1).maxCrossings);
    }

    @Test
    void untrustedMemoryNameCannotHideAnOpaqueMacroCycle() {
        HardwarePartitionGraph graph = memoryFeedback("DIA", false);
        Assertions.assertEquals(2, graph.ownerVertex.length);
        Assertions.assertEquals(1, graph.opaquePrimitives);
        Assertions.assertFalse(analyze(graph, -1, 0, 1).complete);
    }

    @Test
    void memoryPinRolesDistinguishReadWriteAndSharedAddresses() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("roles", false);
        for (String type : new String[] {"RAM32M", "RAM32M16", "RAM64M8", "RAM64X1D", "RAMD32M64"}) {
            EDIFCell primitive = cell(netlist, type, true);
            EDIFCellInst instance = netlist.getTopCell().createChildCellInst(type, primitive);
            for (String output : new String[] {"DOA", "DOD", "DOH", "SPO", "DPO", "O"}) {
                primitive.createPort(output, EDIFDirection.OUTPUT, 1);
                netlist.getTopCell().createNet(type + "_" + output).createPortInst(output, instance);
            }
            EDIFHierCellInst memory = netlist.getHierCellInstFromName(type);
            Assertions.assertEquals(PrimitiveTimingPolicy.CAPTURE, PrimitiveTimingPolicy.inputRoles(memory, "WE"));
            Assertions.assertEquals(PrimitiveTimingPolicy.CAPTURE, PrimitiveTimingPolicy.inputRoles(memory, "DIA"));
            Assertions.assertEquals(PrimitiveTimingPolicy.COMBINATIONAL, PrimitiveTimingPolicy.inputRoles(memory, "DPRA0"));
            Assertions.assertEquals(PrimitiveTimingPolicy.COMBINATIONAL, PrimitiveTimingPolicy.inputRoles(memory, "RADR0"));
            Assertions.assertEquals(PrimitiveTimingPolicy.CAPTURE, PrimitiveTimingPolicy.inputRoles(memory, "WADR0"));
            Assertions.assertEquals(0, PrimitiveTimingPolicy.inputRoles(memory, "WCLK"));
            String shared = type.equals("RAM32M") ? "ADDRD" : type.equals("RAM32M16") || type.equals("RAM64M8") ? "ADDRH" : "A0";
            if (!type.equals("RAMD32M64")) Assertions.assertEquals(3, PrimitiveTimingPolicy.inputRoles(memory, shared));
        }
    }

    @Test
    void dspOnlyStopsPathWhenPregAndEveryConnectedOutputProveRegistration() {
        for (String type : new String[] {"DSP48E1", "DSP48E2"}) {
            Assertions.assertEquals(1, analyze(through(type, "A", 1, false), -1, 0, 1, 2).maxCrossings);
            Assertions.assertEquals(2, analyze(through(type, "A", 0, false), -1, 0, 1, 2).maxCrossings);
            // Missing PREG uses the pinned 2025.1 UNISIM top-module default 1.
            Assertions.assertEquals(1, analyze(through(type, "A", null, false), -1, 0, 1, 2).maxCrossings);
            // Invalid present values never receive that missing-parameter default.
            for (int invalidPreg : new int[] {-1, 2}) {
                HardwarePartitionGraph invalid = through(type, "A", invalidPreg, false);
                Assertions.assertEquals(2, analyze(invalid, -1, 0, 1, 2).maxCrossings);
                Assertions.assertEquals(1, invalid.opaquePrimitives);
            }
            Assertions.assertEquals(2, analyze(through(type, "A", 1, true), -1, 0, 1, 2).maxCrossings);
        }
    }

    @Test
    void cascadedBramDoesNotInventOrdinaryDataOrAddressFeedthrough() {
        for (String input : new String[] {"DIADI", "DINADIN", "DINBDIN", "DINPADINP", "DINPBDINP",
                "ADDRARDADDR", "ADDRENA", "ADDRENB", "ENARDEN", "WEA", "CASDOMUXA",
                "CASDOMUXB", "CASDOMUXEN_A", "CASOREGIMUXA", "CASOREGIMUXEN_B"}) {
            HardwarePartitionGraph graph = through("RAMB36E2", input, null, false);
            // Add a dynamic cascade in a separate fixture below; here the
            // synchronous input reaches the independent write-capture node.
            Assertions.assertEquals(1, analyze(graph, -1, 0, 1, 2).maxCrossings);
        }
        Assertions.assertEquals(2, analyze(through("RAMB36E2", "CASDINA", null, false), -1, 0, 1, 2).maxCrossings);
        Assertions.assertEquals(2, analyze(through("RAMB36E2", "CASINDBITERR", null, false), -1, 0, 1, 2).maxCrossings);
        Assertions.assertEquals(2, analyze(through("RAMB36E2", "SLEEP", null, false), -1, 0, 1, 2).maxCrossings);
    }

    @Test
    void cascadedBramWritebackFeedbackTerminatesAtClockedCapture() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("bram_feedback", false);
        EDIFCell top = netlist.getTopCell();
        EDIFCell ram = cell(netlist, "RAMB36E2", true);
        ram.createPort("DINADIN", EDIFDirection.INPUT, 1);
        ram.createPort("CASDINA", EDIFDirection.INPUT, 1);
        ram.createPort("DOADO", EDIFDirection.OUTPUT, 1);
        EDIFCell lut = cell(netlist, "LUT1", true);
        lut.createPort("I0", EDIFDirection.INPUT, 1); lut.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCell ff = cell(netlist, "FDRE", true);
        ff.createPort("D", EDIFDirection.INPUT, 1); ff.createPort("Q", EDIFDirection.OUTPUT, 1);
        EDIFCellInst memory = top.createChildCellInst("memory", ram);
        EDIFCellInst comb = top.createChildCellInst("comb", lut);
        EDIFCellInst cascade = top.createChildCellInst("cascade", ff);
        connect(top, "read", memory, "DOADO", comb, "I0");
        connect(top, "writeback", comb, "O", memory, "DINADIN");
        connect(top, "active_cascade", cascade, "Q", memory, "CASDINA");
        HardwarePartitionGraph graph = build(netlist, "memory", "comb", "cascade");
        CombinationalPathAnalyzer.Analysis result = analyze(graph, -1, 0, 1, 2);
        Assertions.assertTrue(result.complete);
        Assertions.assertEquals(3, result.maxCrossings);
        Assertions.assertEquals(0, result.cycleNodeCount);
    }
}
