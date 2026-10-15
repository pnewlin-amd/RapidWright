/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

public class TestVertexWeightModel {
    @TempDir Path directory;
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private Path model(String... rows) throws Exception {
        Path file = directory.resolve("weights.tsv");
        Files.write(file, Arrays.asList(rows));
        return file;
    }

    private static EDIFCell primitive(EDIFNetlist netlist, String name) {
        return new EDIFCell(netlist.getHDIPrimitivesLibrary(), name);
    }

    @Test
    void repeatedHierarchyUsesExplicitLutWeightsAndUnlistedOtherTypesCostZero() throws Exception {
        VertexWeightModel model = VertexWeightModel.read(model("#input_sha256=" + SHA,
                "REF_NAME\tinteger_weight", "LUT2\t76", "LUT6\t200"), SHA.toUpperCase());
        EDIFNetlist netlist = EDIFTools.createNewNetlist("weights");
        EDIFCell block = new EDIFCell(netlist.getWorkLibrary(), "block");
        block.createChildCellInst("small", primitive(netlist, "LUT2"));
        block.createChildCellInst("large", primitive(netlist, "LUT6"));
        block.createChildCellInst("ff", primitive(netlist, "FDRE"));
        block.createChildCellInst("unknown", primitive(netlist, "UNFAMILIAR_MACRO"));
        netlist.getTopCell().createChildCellInst("a", block);
        netlist.getTopCell().createChildCellInst("b", block);
        Assertions.assertEquals(276, model.weight(block));
        Assertions.assertEquals(552, model.weight(netlist.getTopCell()));
        Assertions.assertEquals(552, model.weight(netlist.getTopCell()));
    }

    @Test
    void inputBindingIsOptionalButEnforcedWhenPresent() throws Exception {
        Path bound = model("#input_sha256=" + SHA, "REF_NAME\tinteger_weight", "LUT1\t1");
        Assertions.assertThrows(IllegalArgumentException.class, () -> VertexWeightModel.read(bound, null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> VertexWeightModel.read(bound, "0".repeat(64)));
        VertexWeightModel.read(model("REF_NAME\tinteger_weight", "LUT1\t1"), null);
        Path invalid = model("#input_sha256=short", "REF_NAME\tinteger_weight", "LUT1\t1");
        Assertions.assertThrows(IllegalArgumentException.class, () -> VertexWeightModel.read(invalid, SHA));
    }

    @Test
    void missingKnownLutAndCheckedHierarchyOverflowFailExplicitly() throws Exception {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("weights");
        EDIFCell lut = primitive(netlist, "LUT6_2");
        VertexWeightModel missing = VertexWeightModel.read(model("REF_NAME\tinteger_weight", "LUT6\t1"), null);
        Assertions.assertThrows(IllegalArgumentException.class, () -> missing.weight(lut));
        VertexWeightModel overflow = VertexWeightModel.read(model("REF_NAME\tinteger_weight",
                "LUT6_2\t2147483647"), null);
        netlist.getTopCell().createChildCellInst("a", lut);
        netlist.getTopCell().createChildCellInst("b", lut);
        Assertions.assertThrows(IllegalArgumentException.class, () -> overflow.weight(netlist.getTopCell()));
    }

    @Test
    void invalidTableAndNegativeDuplicateOrNonIntegerWeightsAreRejected() throws Exception {
        for (String[] rows : new String[][] {
                {"LUT1\t2"}, {"REF_NAME\tinteger_weight"},
                {"REF_NAME\tinteger_weight", "LUT1\t-1"},
                {"REF_NAME\tinteger_weight", "LUT1\t1", "LUT1\t2"},
                {"REF_NAME\tinteger_weight", "LUT1\t1.5"},
                {"REF_NAME\tinteger_weight", "LUT1\t2147483648"},
                {"REF_NAME\tinteger_weight", "LUT1\t1\textra"}}) {
            Path path = model(rows);
            Assertions.assertThrows(IllegalArgumentException.class, () -> VertexWeightModel.read(path, null));
        }
    }
}
