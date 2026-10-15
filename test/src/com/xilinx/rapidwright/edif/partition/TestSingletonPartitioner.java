/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestSingletonPartitioner {
    @TempDir Path directory;

    @Test
    void allLogicMapsToOneFpgaWithoutLaunchingAnyExternalPartitioner() throws IOException {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("single", false);
        EDIFCell lut = new EDIFCell(netlist.getHDIPrimitivesLibrary(), "LUT1");
        lut.createPort("I0", EDIFDirection.INPUT, 1);
        lut.createPort("O", EDIFDirection.OUTPUT, 1);
        netlist.getTopCell().createChildCellInst("a", lut);
        netlist.getTopCell().createChildCellInst("b", lut);
        Path input = directory.resolve("source.edf"), target = directory.resolve("single.graph");
        netlist.exportEDIF(input);
        Files.write(target, Arrays.asList("% rapidwright_cost_scale=1", "1 0 1", ""));
        Partitioner.main(new String[] {input.toString(), "1", "auto", "--part_dir", directory.toString(),
                "--target_graph", target.toString(), "--skip_detailed_reports", "--mtkahypar_bin",
                directory.resolve("must_never_launch.exe").toString()});
        Assertions.assertEquals(Arrays.asList("single FPGA_A"), Files.readAllLines(directory.resolve("mapping.txt")));
        Assertions.assertEquals("PASS", Files.readAllLines(directory.resolve("RUN_STATUS.txt")).get(0));
        java.util.List<String> quality = Files.readAllLines(directory.resolve("hardware_quality.txt"));
        Assertions.assertTrue(quality.contains("after_transfers=0"));
        Assertions.assertTrue(quality.contains("after_max_register_free_crossings=0"));
        Assertions.assertTrue(quality.contains("minimum_link_hop_analysis_available=true"));
        Assertions.assertTrue(quality.contains("after_max_minimum_link_hops=0"));
        Assertions.assertTrue(quality.contains("after_max_minimum_relay_traversals=0"));
        Assertions.assertTrue(quality.contains("backend=trivial_single_fpga_assignment"));
        org.json.JSONObject provenance = new org.json.JSONObject(new String(Files.readAllBytes(directory.resolve("run_provenance.json")),
                java.nio.charset.StandardCharsets.UTF_8));
        Assertions.assertFalse(provenance.getBoolean("native_backend_invoked"));
        Assertions.assertEquals(1, provenance.getInt("minimum_link_hop_analysis_version"));
        Assertions.assertTrue(provenance.getBoolean("minimum_link_hop_analysis_available"));
    }
}
