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
 * logicDiscoveryPolicy is a centralized, single source-of-truth utility for
 * discovering logic-related properties in the EDIF/DCP domain.
 * 
 * There are many points in the code where we want to determine how many
 * LUTs there are. lutcount CLI and partitioner notably - this keeps 
 * that LUT count discovery centralized and easy to modify in one spot.
 *
 */
public final class logicDiscoveryPolicy {

    private logicDiscoveryPolicy() {
        // no instances
    }




    /**
     * Returns true if a cell type name represents a LUT used as logic.
     * 
     * Treat any cell type whose name contains "LUT" as a logic LUT.
     * substring match to be consistent with legacy policy.
     * 
     * TODO : probably a better implementation
     */
    public static boolean is_logic_lut_type_name(String type_name) {
        if (type_name == null) return false;
        return type_name.contains("LUT");
    }

    /**
     * Convenience helper: returns 1 if the given instance is a leaf and its type
     * is a LUT.
     */
    public static int lut_count_for_leaf(EDIFHierCellInst inst) {
        if (inst == null) return 0;
        if (!inst.getCellType().isLeafCellOrBlackBox()) return 0;
        boolean is_lut = is_logic_lut_type_name(inst.getCellType().getName());
        return is_lut ? 1 : 0;
    }
}
