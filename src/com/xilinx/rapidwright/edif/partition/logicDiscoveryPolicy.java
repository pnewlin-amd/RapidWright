/*
 *
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

package com.xilinx.rapidwright.edif.partition;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;

/**
 * logicDiscoveryPolicy is a source-of-truth utility for
 * discovering logic-related properties in the EDIF/DCP domain.
 * For example, determining if a cell is a look up table.
 */
public final class logicDiscoveryPolicy {

    private static final java.util.regex.Pattern LOGIC_LUT_NAME =
            java.util.regex.Pattern.compile("LUT[1-6](_[DL])?");

    // List of protected names that should be excluded from mapping generation. 
    // GND and VCC will be re-created locally in the partition / module as needed,
    // GND, VCC should not be results you see in mapping.txt
    private static final java.util.LinkedHashSet<String> EXCLUSION_NAMES =
            new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                    "VCC", "GND", "<const0>", "<const1>"
            ));

    private logicDiscoveryPolicy() {
        // no instances
    }


    /**
     * Returns true if a cell type name represents a LUT used as logic.
     * 
     * Counts logical LUT primitives in a post-synthesis netlist. This is not
     * Vivado's packed CLB/Slice LUT utilization: LUTRAM, SRLs and packing have
     * separate semantics. In particular LUT6_2 is one primitive, not two LUTs.
     * Exact primitive names avoid counting a black box such as MY_LUT_MACRO.
     * @param type_name The cell type name to check
     * @return True for an exact supported logic-LUT primitive type name
     */
    public static boolean isLogicLUT(String type_name) {
        if (type_name == null) return false;
        return LOGIC_LUT_NAME.matcher(type_name).matches()
                || "LUT6_2".equals(type_name) || "LUT6CY".equals(type_name)
                || "CFGLUT5".equals(type_name) || "LUTCY1".equals(type_name)
                || "LUTCY2".equals(type_name);
    }

    /**
     * Returns 1 if the given instance is a leaf and its type is a LUT.
     * @param inst The hierarchical cell instance to check
     * @return 1 if leaf LUT, 0 otherwise
     */
    public static int leafLutCount(EDIFHierCellInst inst) {
        if (inst == null) return 0;
        if (!inst.getCellType().isPrimitive()) return 0;
        boolean is_lut = isLogicLUT(inst.getCellType().getName());
        return is_lut ? 1 : 0;
    }

    /**
     * Checks if a token should be excluded from processing.
     * @param s The token string to check
     * @return True if token is VCC, GND, or similar
     */
    public static boolean isExcludedToken(String s) {
        if (s == null) {
            return false;
        }
        if (EXCLUSION_NAMES.contains(s)) {
            return true;
        }
        String t = s.toLowerCase(java.util.Locale.ROOT);
        return "vcc".equals(t) || "gnd".equals(t);
    }

    /**
     * Checks if a hierarchical path contains any excluded segments.
     * @param path The hierarchical path to check
     * @return True if path contains excluded segments
     */
    public static boolean pathHasExcludedSegment(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String[] segs = path.split("/");
        for (String seg : segs) {
            if (isExcludedToken(seg)) {
                return true;
            }
        }
        return false;
    }
}
