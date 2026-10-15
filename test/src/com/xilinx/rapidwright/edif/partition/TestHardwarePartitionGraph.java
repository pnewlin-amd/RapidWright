/* Copyright (c) 2026, Advanced Micro Devices, Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0. */
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

public class TestHardwarePartitionGraph {
    private static EDIFCell primitive(EDIFNetlist netlist, String type, String input, String output) {
        EDIFCell cell = netlist.getHDIPrimitivesLibrary().getCell(type);
        if (cell != null) return cell;
        cell = new EDIFCell(netlist.getHDIPrimitivesLibrary(), type);
        cell.createPort(input, EDIFDirection.INPUT, 1);
        cell.createPort(output, EDIFDirection.OUTPUT, 1);
        return cell;
    }

    private static void connect(EDIFCell top, String name, EDIFCellInst source, String out,
            EDIFCellInst sink, String in) {
        EDIFNet net = top.createNet(name);
        net.createPortInst(out, source);
        net.createPortInst(in, sink);
    }

    private static HardwarePartitionGraph through(String middleType, String middleInput, String middleOutput) {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("test", false);
        EDIFCell top = netlist.getTopCell();
        EDIFCell ff = primitive(netlist, "FDRE", "D", "Q");
        EDIFCell middle = primitive(netlist, middleType, middleInput, middleOutput);
        EDIFCellInst source = top.createChildCellInst("source", ff);
        EDIFCellInst comb = top.createChildCellInst("middle", middle);
        EDIFCellInst sink = top.createChildCellInst("sink", ff);
        connect(top, "ingress", source, "Q", comb, middleInput);
        connect(top, "egress", comb, middleOutput, sink, "D");
        Map<EDIFHierCellInst, Integer> vertices = new LinkedHashMap<>();
        vertices.put(netlist.getHierCellInstFromName("source"), 1);
        vertices.put(netlist.getHierCellInstFromName("middle"), 2);
        vertices.put(netlist.getHierCellInstFromName("sink"), 3);
        return HardwarePartitionGraph.build(netlist, vertices);
    }

    @Test
    void srlDynamicAddressPathIsNotFalseSequentialCapture() {
        HardwarePartitionGraph graph = through("SRL16E", "A0", "Q");
        CombinationalPathAnalyzer.Analysis analysis = new CombinationalPathAnalyzer(
                graph.ownerVertex, graph.sequential, graph.successors).analyze(new int[] {-1, 0, 1, 2});
        Assertions.assertTrue(analysis.complete);
        Assertions.assertEquals(2, analysis.maxCrossings);
        Assertions.assertEquals(1, analysis.transitCount);
    }

    @Test
    void bramCascadeCanPassIncomingDataWithoutCapturing() {
        HardwarePartitionGraph graph = through("RAMB36E2", "CASDINA", "CASDOUTA");
        CombinationalPathAnalyzer.Analysis analysis = new CombinationalPathAnalyzer(
                graph.ownerVertex, graph.sequential, graph.successors).analyze(new int[] {-1, 0, 1, 2});
        Assertions.assertEquals(2, analysis.maxCrossings);
        Assertions.assertEquals(1, analysis.transitCount);
    }

    @Test
    void uramOptionalCascadeRegisterIsNotAssumedPresent() {
        HardwarePartitionGraph graph = through("URAM288", "CAS_IN_DOUT_A", "CAS_OUT_DOUT_A");
        CombinationalPathAnalyzer.Analysis analysis = new CombinationalPathAnalyzer(
                graph.ownerVertex, graph.sequential, graph.successors).analyze(new int[] {-1, 0, 1, 2});
        Assertions.assertEquals(2, analysis.maxCrossings);
        Assertions.assertEquals(1, analysis.transitCount);
    }

    @Test
    void clockPinDoesNotInventDataDependency() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("test", false);
        EDIFCell top = netlist.getTopCell();
        EDIFCell lut = primitive(netlist, "LUT1", "I0", "O");
        EDIFCell ff = primitive(netlist, "FDRE", "D", "Q");
        ff.createPort("C", EDIFDirection.INPUT, 1);
        EDIFCellInst source = top.createChildCellInst("source", lut);
        EDIFCellInst sink = top.createChildCellInst("sink", ff);
        connect(top, "clock", source, "O", sink, "C");
        Map<EDIFHierCellInst, Integer> vertices = new LinkedHashMap<>();
        vertices.put(netlist.getHierCellInstFromName("source"), 1);
        vertices.put(netlist.getHierCellInstFromName("sink"), 2);
        HardwarePartitionGraph graph = HardwarePartitionGraph.build(netlist, vertices);
        Assertions.assertTrue(graph.nets.isEmpty());
        for (int[] successors : graph.successors) Assertions.assertEquals(0, successors.length);
    }

    @Test
    void distributionBuffersDoNotSatisfyUsefulLogicOccupancy() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("test", false);
        EDIFCell buffer = primitive(netlist, "BUFG", "I", "O");
        netlist.getTopCell().createChildCellInst("buffer", buffer);
        Map<EDIFHierCellInst, Integer> vertices = new LinkedHashMap<>();
        vertices.put(netlist.getHierCellInstFromName("buffer"), 1);
        HardwarePartitionGraph graph = HardwarePartitionGraph.build(netlist, vertices);
        Assertions.assertEquals(0L, graph.realLogic[1]);
    }

    @Test
    void opaqueDesignBlackBoxCountsAsLogicButRemainsReported() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("test", false);
        EDIFCell blackBox = new EDIFCell(netlist.getWorkLibrary(), "opaque_design_macro");
        blackBox.createPort("I", EDIFDirection.INPUT, 1);
        blackBox.createPort("O", EDIFDirection.OUTPUT, 1);
        netlist.getTopCell().createChildCellInst("macro", blackBox);
        Map<EDIFHierCellInst, Integer> vertices = new LinkedHashMap<>();
        vertices.put(netlist.getHierCellInstFromName("macro"), 1);
        HardwarePartitionGraph graph = HardwarePartitionGraph.build(netlist, vertices);
        Assertions.assertEquals(1L, graph.realLogic[1]);
        Assertions.assertEquals(1, graph.opaquePrimitives);
        Assertions.assertFalse(graph.sequential[0]);
    }

    @Test
    void opaqueWorkLibraryCellCannotBecomeBarrierByBorrowingVendorFfName() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("test", false);
        EDIFCell ff = primitive(netlist, "FDRE", "D", "Q");
        EDIFCell opaque = new EDIFCell(netlist.getWorkLibrary(), "FDRE");
        opaque.createPort("D", EDIFDirection.INPUT, 1);
        opaque.createPort("Q", EDIFDirection.OUTPUT, 1);
        EDIFCell top = netlist.getTopCell();
        EDIFCellInst source = top.createChildCellInst("source", ff);
        EDIFCellInst macro = top.createChildCellInst("opaque", opaque);
        EDIFCellInst sink = top.createChildCellInst("sink", ff);
        connect(top, "ingress", source, "Q", macro, "D");
        connect(top, "egress", macro, "Q", sink, "D");
        Map<EDIFHierCellInst, Integer> vertices = new LinkedHashMap<>();
        vertices.put(netlist.getHierCellInstFromName("source"), 1);
        vertices.put(netlist.getHierCellInstFromName("opaque"), 2);
        vertices.put(netlist.getHierCellInstFromName("sink"), 3);
        HardwarePartitionGraph graph = HardwarePartitionGraph.build(netlist, vertices);
        Assertions.assertEquals(1, graph.opaquePrimitives);
        CombinationalPathAnalyzer.Analysis audit = new CombinationalPathAnalyzer(
                graph.ownerVertex, graph.sequential, graph.successors).analyze(new int[] {-1, 0, 1, 2});
        Assertions.assertEquals(2, audit.maxCrossings);
        Assertions.assertEquals(1, audit.transitCount);
    }

    private static HardwarePartitionGraph constantIdentityFixture(String netName, boolean constant, boolean trusted) {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("constant_identity", false);
        EDIFCell top = netlist.getTopCell();
        EDIFCell ff = primitive(netlist, "FDRE", "D", "Q");
        EDIFCell driver = ff;
        if (constant) {
            driver = new EDIFCell(trusted ? netlist.getHDIPrimitivesLibrary() : netlist.getWorkLibrary(), "GND");
            driver.createPort("G", EDIFDirection.OUTPUT, 1);
        }
        EDIFCell ram = primitive(netlist, "RAMB36E2", "CASDINA", "DOADO");
        EDIFCellInst source = top.createChildCellInst("source", driver);
        EDIFCellInst middle = top.createChildCellInst("memory", ram);
        EDIFCellInst sink = top.createChildCellInst("sink", ff);
        connect(top, netName, source, constant ? "G" : "Q", middle, "CASDINA");
        connect(top, "result", middle, "DOADO", sink, "D");
        Map<EDIFHierCellInst, Integer> vertices = new LinkedHashMap<>();
        vertices.put(netlist.getHierCellInstFromName("source"), 1);
        vertices.put(netlist.getHierCellInstFromName("memory"), 2);
        vertices.put(netlist.getHierCellInstFromName("sink"), 3);
        return HardwarePartitionGraph.build(netlist, vertices);
    }

    @Test
    void reservedConstantNetNamesCannotHideDynamicCascadePaths() {
        for (String name : new String[] {"GND", "VCC", "<const0>", "<const1>"}) {
            HardwarePartitionGraph graph = constantIdentityFixture(name, false, true);
            CombinationalPathAnalyzer.Analysis audit = new CombinationalPathAnalyzer(
                    graph.ownerVertex, graph.sequential, graph.successors).analyze(new int[] {-1, 0, 1, 2});
            Assertions.assertEquals(2, graph.nets.size(), name);
            Assertions.assertEquals(2, audit.maxCrossings, name);
            Assertions.assertEquals(1, audit.transitCount, name);
        }
    }

    @Test
    void onlyTrustedConstantOutputDriversAreStaticAndExcludedFromUsefulLogic() {
        HardwarePartitionGraph opaque = constantIdentityFixture("ordinary", true, false);
        Assertions.assertEquals(1, opaque.opaquePrimitives);
        Assertions.assertEquals(1L, opaque.realLogic[1]);
        Assertions.assertEquals(2, opaque.nets.size());
        Assertions.assertEquals(2, new CombinationalPathAnalyzer(opaque.ownerVertex,
                opaque.sequential, opaque.successors).analyze(new int[] {-1, 0, 1, 2}).maxCrossings);
        HardwarePartitionGraph nativeConstant = constantIdentityFixture("arbitrary_name", true, true);
        Assertions.assertEquals(0L, nativeConstant.realLogic[1]);
        Assertions.assertEquals(1, nativeConstant.nets.size());
        Assertions.assertEquals(1, new CombinationalPathAnalyzer(nativeConstant.ownerVertex,
                nativeConstant.sequential, nativeConstant.successors).analyze(new int[] {-1, 0, 1, 2}).maxCrossings);
    }
}
