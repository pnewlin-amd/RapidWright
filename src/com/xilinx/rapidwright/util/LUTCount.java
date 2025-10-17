/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Perry Newlin
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

package com.xilinx.rapidwright.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.partition.PartitionTools;

/**
 * CLI utility to count logic LUT usage from EDIF (.edf/.edif) or Vivado DCP (.dcp).
 */
public final class LUTCount {

    private LUTCount() {}

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  LUTCount <input.edf|input.dcp>");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  rapidwright LUTCount design.edf");
        System.out.println("  rapidwright LUTCount design.dcp");
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            usage();
            return;
        }

        Path input_path = Paths.get(args[0]);

        // read netlist (EDIF or DCP)
        EDIFNetlist netlist;
        boolean is_dcp = input_path.toString().toLowerCase().endsWith(".dcp");
        if (is_dcp) {
            Design d = Design.readCheckpoint(input_path.toString());
            netlist = d.getNetlist();
            int enc = netlist.getEncryptedCells().size();
            if (enc > 0) {
                System.err.println("ERROR: Encrypted DCP detected (encryptedCells=" + enc + "). Encrypted DCPs are unsupported.");
                System.exit(1);
            }
        } else {
            netlist = EDIFTools.readEdifFile(input_path);
        }

        // compute logic-only LUT count via PartitionTools aggregation
        EDIFHierCellInst top_inst = netlist.getTopHierCellInst();
        Map<EDIFCell, Integer> lut_cache = new HashMap<>();
        int logic_luts = PartitionTools.getLUTCount(top_inst, lut_cache, null);

        System.out.println("-----------------------------------------------------------");
        System.out.println("LUT Count Report");
        System.out.println("Input     : " + input_path);
        System.out.println("-----------------------------------------------------------");
        System.out.printf("Logic LUTs: %d%n", logic_luts);
    }



}
