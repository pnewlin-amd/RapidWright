/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

public class TestTimingHmetisWeights {
    @TempDir Path directory;

    @Test
    void weightedNativeFmtElevenPreservesVertexWeightsCensusAndUnweightedCutCounts() throws Exception {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("timing_hgr", false);
        netlist.getTopCell().createChildCellInst("a", new EDIFCell(netlist.getHDIPrimitivesLibrary(), "LUT6"));
        netlist.getTopCell().createChildCellInst("b", new EDIFCell(netlist.getHDIPrimitivesLibrary(), "FDRE"));
        netlist.getTopCell().createChildCellInst("c", new EDIFCell(netlist.getHDIPrimitivesLibrary(), "RAM64X1S"));
        EDIFHierCellInst a = netlist.getHierCellInstFromName("a"), b = netlist.getHierCellInstFromName("b"), c = netlist.getHierCellInstFromName("c");
        Map<EDIFHierCellInst, Integer> vertices = new LinkedHashMap<>();
        vertices.put(a, 1); vertices.put(b, 2); vertices.put(c, 3);
        Map<EDIFHierNet, Set<EDIFHierCellInst>> edges = new LinkedHashMap<>();
        edges.put(new EDIFHierNet(netlist.getTopHierCellInst(), netlist.getTopCell().createNet("critical")),
                new LinkedHashSet<>(Arrays.asList(a, b)));
        edges.put(new EDIFHierNet(netlist.getTopHierCellInst(), netlist.getTopCell().createNet("other")),
                new LinkedHashSet<>(Arrays.asList(b, c)));
        int[] primary = {0, 7, 0, 3};
        Path plain = directory.resolve("plain.hgr"), weighted = directory.resolve("timing.hgr");
        PartitionTools.writeHMetisFile(plain, edges, vertices, false, null, primary, null);
        PartitionTools.writeHMetisFile(weighted, edges, vertices, false, null, primary, new int[] {9, 2});
        Assertions.assertEquals(Arrays.asList("2 3 11", "9 1 2 ", "2 2 3 ", "7", "0", "3"), Files.readAllLines(weighted));
        Map<String, Integer> assignment = new LinkedHashMap<>();
        assignment.put("a", 0); assignment.put("b", 1); assignment.put("c", 0);
        String[] lookup = {null, "a", "b", "c"};
        Assertions.assertEquals(PartitionPairCounter.countPartitionPairs(plain, lookup, assignment),
                PartitionPairCounter.countPartitionPairs(weighted, lookup, assignment));
        Assertions.assertEquals(2, PartitionPairCounter.countPartitionPairs(weighted, lookup, assignment)
                .get("FPGA_A,FPGA_B").intValue());
        LogicalResourceCounts.Counts census = new LogicalResourceCounts().count(netlist);
        Assertions.assertEquals(1, census.logicLuts);
        Assertions.assertEquals(1, census.flipFlops);
        Assertions.assertEquals(1, census.lutRamPrimitives);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> PartitionTools.writeHMetisFile(weighted, edges, vertices, false, null, primary, new int[] {9}));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> PartitionTools.writeHMetisFile(weighted, edges, vertices, false, null, primary, new int[] {9, 0}));
    }
}
