/* Copyright (c) 2026, Advanced Micro Devices, Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

public class TestConstraintFrontier {
    @TempDir
    Path directory;

    private static EDIFNetlist hierarchy() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("test", false);
        EDIFCell ff = new EDIFCell(netlist.getHDIPrimitivesLibrary(), "FDRE");
        ff.createPort("D", EDIFDirection.INPUT, 1);
        ff.createPort("Q", EDIFDirection.OUTPUT, 1);
        EDIFCell group = new EDIFCell(netlist.getWorkLibrary(), "group_type");
        EDIFCell nested = new EDIFCell(netlist.getWorkLibrary(), "nested_type");
        group.createChildCellInst("a", ff);
        group.createChildCellInst("b", ff);
        group.createChildCellInst("nested", nested);
        nested.createChildCellInst("c", ff);
        nested.createChildCellInst("d", ff);
        netlist.getTopCell().createChildCellInst("group", group);
        return netlist;
    }

    @Test
    void deepConstraintSplitsAncestorsWithoutFixingUnrelatedSiblings() throws IOException {
        EDIFNetlist netlist = hierarchy();
        Map<EDIFHierCellInst, Integer> coarse = new LinkedHashMap<>();
        coarse.put(netlist.getHierCellInstFromName("group"), 7);
        Path constraints = directory.resolve("constraints.txt");
        Files.write(constraints, Arrays.asList("# partition target", "", "test/group/nested/c 2"));
        Map<EDIFHierCellInst, Integer> aligned =
                PartitionTools.alignFrontierToConstraints(netlist, coarse, constraints);
        Assertions.assertArrayEquals(new String[] {"group/a", "group/b", "group/nested/c", "group/nested/d"},
                aligned.keySet().stream().map(Object::toString).toArray(String[]::new));
        Assertions.assertEquals(Arrays.asList(1, 2, 3, 4), new ArrayList<>(aligned.values()));
        Assertions.assertFalse(aligned.containsKey(netlist.getHierCellInstFromName("group")));
        Assertions.assertFalse(aligned.containsKey(netlist.getHierCellInstFromName("group/nested")));
        // Independent coverage check: every real primitive has exactly one
        // frontier ancestor; no coarse and fine owners overlap after splitting.
        for (EDIFHierCellInst primitive : netlist.getAllLeafHierCellInstances(true)) {
            int owners = 0;
            for (EDIFHierCellInst node = primitive; node != null; node = node.getParent()) {
                if (aligned.containsKey(node)) owners++;
            }
            Assertions.assertEquals(1, owners, primitive.toString());
        }
    }

    @Test
    void wholeModuleConstraintKeepsItsExistingAtomicOwner() throws IOException {
        EDIFNetlist netlist = hierarchy();
        Map<EDIFHierCellInst, Integer> coarse = new LinkedHashMap<>();
        EDIFHierCellInst group = netlist.getHierCellInstFromName("group");
        coarse.put(group, 1);
        Path constraints = directory.resolve("constraints.txt");
        Files.write(constraints, Arrays.asList("test/group 1"));
        Map<EDIFHierCellInst, Integer> aligned =
                PartitionTools.alignFrontierToConstraints(netlist, coarse, constraints);
        Assertions.assertEquals(1, aligned.size());
        Assertions.assertEquals(1, aligned.get(group));
    }

    @Test
    void unknownHierarchyConstraintFailsBeforeNativePartitioning() throws IOException {
        EDIFNetlist netlist = hierarchy();
        Map<EDIFHierCellInst, Integer> coarse = new LinkedHashMap<>();
        coarse.put(netlist.getHierCellInstFromName("group"), 1);
        Path constraints = directory.resolve("constraints.txt");
        Files.write(constraints, Arrays.asList("test/group/missing 1"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> PartitionTools.alignFrontierToConstraints(netlist, coarse, constraints));
    }

    @Test
    void canonicalIdsDoNotDependOnInputMapOrderAndTopPrefixIsExact() {
        EDIFNetlist netlist = hierarchy();
        Map<EDIFHierCellInst, Integer> frontier = new LinkedHashMap<>();
        frontier.put(netlist.getHierCellInstFromName("group/nested"), 99);
        frontier.put(netlist.getHierCellInstFromName("group/b"), 98);
        frontier.put(netlist.getHierCellInstFromName("group/a"), 97);
        Map<EDIFHierCellInst, Integer> aligned = PartitionTools.alignFrontierToConstraints(netlist, frontier, null);
        Assertions.assertArrayEquals(new String[] {"group/a", "group/b", "group/nested"},
                aligned.keySet().stream().map(Object::toString).toArray(String[]::new));
        Assertions.assertEquals(Arrays.asList(1, 2, 3), new ArrayList<>(aligned.values()));
        Assertions.assertEquals("", PartitionTools.normalizeConstraintPath(netlist, "test"));
        Assertions.assertEquals("group/a", PartitionTools.normalizeConstraintPath(netlist, "test/group/a"));
        Assertions.assertEquals("testing/group/a", PartitionTools.normalizeConstraintPath(netlist, "testing/group/a"));
    }

    @Test
    void commandLineTopDefaultAllowsImmediateChildOverrideBeforeBackendLaunch() throws IOException {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("test", false);
        EDIFCell lut = new EDIFCell(netlist.getHDIPrimitivesLibrary(), "LUT1");
        lut.createPort("I0", EDIFDirection.INPUT, 1);
        lut.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCell group = new EDIFCell(netlist.getWorkLibrary(), "group_type");
        group.createChildCellInst("a", lut);
        netlist.getTopCell().createChildCellInst("group", group);
        netlist.getTopCell().createChildCellInst("b", lut);
        Path input = directory.resolve("design.edf");
        netlist.exportEDIF(input);
        Path constraints = directory.resolve("constraints.txt");
        Files.write(constraints, Arrays.asList("test FPGA_A", "test/group FPGA_B"));
        // No alternate partitioner is run: an intentionally missing executable
        // stops at backend launch, after the real CLI generated its fixed file.
        Assertions.assertThrows(UncheckedIOException.class, () -> Partitioner.main(new String[] {
                input.toString(), "2", "1", "--part_dir", directory.toString(),
                "--mapping_constraints", constraints.toString(), "--mtkahypar_bin",
                directory.resolve("intentionally_missing_backend.exe").toString()}));
        Assertions.assertEquals(Arrays.asList("0", "1"), Files.readAllLines(directory.resolve("design.edf.fix")));
    }

    @Test
    void zeroLutFfHierarchyDescendsToMovableResourceBearingPrimitives() throws IOException {
        EDIFNetlist netlist = hierarchy();
        Map<EDIFHierCellInst, Integer> lutCounts = new HashMap<>();
        Map<EDIFHierCellInst, Integer> frontier = PartitionTools.alignFrontierToConstraints(netlist,
                PartitionTools.identifyLeafInstances(netlist, 1000, lutCounts), null);
        Assertions.assertEquals(0, lutCounts.get(netlist.getTopHierCellInst()));
        Assertions.assertEquals(4, frontier.size());
        for (EDIFHierCellInst owner : frontier.keySet()) Assertions.assertTrue(owner.getCellType().isLeafCellOrBlackBox());
        HardwarePartitionGraph graph = HardwarePartitionGraph.build(netlist, frontier);
        long ff = 0;
        long usefulLogic = 0;
        for (int v = 1; v < graph.resources.length; v++) {
            Assertions.assertEquals(0L, graph.resources[v][0]);
            Assertions.assertEquals(1L, graph.resources[v][1]);
            ff += graph.resources[v][1];
            usefulLogic += graph.realLogic[v];
        }
        Assertions.assertEquals(4L, ff);
        Assertions.assertEquals(4L, usefulLogic);
        for (EDIFHierCellInst primitive : netlist.getAllLeafHierCellInstances(true)) {
            int owners = 0;
            for (EDIFHierCellInst node = primitive; node != null; node = node.getParent()) {
                if (frontier.containsKey(node)) owners++;
            }
            Assertions.assertEquals(1, owners);
        }
        Path hgr = directory.resolve("zero_lut.hgr");
        PartitionTools.writeHMetisFile(hgr, Collections.emptyMap(), frontier, false, lutCounts);
        Assertions.assertEquals(Arrays.asList("0 4 10", "1", "1", "1", "1"), Files.readAllLines(hgr));
    }

    @Test
    void mixedLutDesignKeepsTrueZeroFfWeightsInsteadOfApplyingUnitFallback() throws IOException {
        EDIFNetlist netlist = hierarchy();
        EDIFCell lut = new EDIFCell(netlist.getHDIPrimitivesLibrary(), "LUT1");
        lut.createPort("I0", EDIFDirection.INPUT, 1);
        lut.createPort("O", EDIFDirection.OUTPUT, 1);
        netlist.getTopCell().createChildCellInst("lut_a", lut);
        netlist.getTopCell().createChildCellInst("lut_b", lut);
        Map<EDIFHierCellInst, Integer> lutCounts = new HashMap<>();
        Map<EDIFHierCellInst, Integer> frontier = PartitionTools.alignFrontierToConstraints(netlist,
                PartitionTools.identifyLeafInstances(netlist, 1, lutCounts), null);
        Assertions.assertEquals(2, lutCounts.get(netlist.getTopHierCellInst()));
        Assertions.assertEquals(6, frontier.size());
        Path hgr = directory.resolve("mixed_lut.hgr");
        PartitionTools.writeHMetisFile(hgr, Collections.emptyMap(), frontier, false, lutCounts);
        Assertions.assertEquals(Arrays.asList("0 6 10", "0", "0", "0", "0", "1", "1"), Files.readAllLines(hgr));
    }

    @Test
    void commandLineNoLutDesignReachesBackendWithPositiveFallbackWeights() throws IOException {
        EDIFNetlist netlist = hierarchy();
        Path input = directory.resolve("ff_only.edf");
        netlist.exportEDIF(input);
        Assertions.assertThrows(UncheckedIOException.class, () -> Partitioner.main(new String[] {
                input.toString(), "2", "1", "--part_dir", directory.toString(), "--mtkahypar_bin",
                directory.resolve("intentionally_missing_backend.exe").toString()}));
        Assertions.assertEquals(Arrays.asList("0 4 10", "1", "1", "1", "1"),
                Files.readAllLines(directory.resolve("ff_only.edf.hgr")));
    }
}
