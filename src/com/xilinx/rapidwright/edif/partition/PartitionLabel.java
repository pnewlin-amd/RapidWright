/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * Licensed under the Apache License, Version 2.0
 * 
 * 
 * Author: Perry Newlin
 * 
 */

package com.xilinx.rapidwright.edif.partition;

/**
 *
 * naming scheme:
 *   0..25  -> A..Z
 *   26..51 -> AZ..ZZ
 *   52..77 -> AZZ..ZZZ
 *
 * close to "excel-style (bijective) base-26"
 */
final class PartitionLabel {

    private PartitionLabel() {}

    private static final char[] ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    //returns FPGA_<SUFFIX>
    static String indexToFpgaLabel(int idx) {
        if (idx < 0) throw new IllegalArgumentException("partition index must be non-negative");
        String suffix;
        if (idx < 26) {
            suffix = String.valueOf(ALPHA[idx]);
        } else {
            int t = idx - 26;
            int q = t / 26;   //how many trailing Z's to add
            int r = t % 26;   //leading letter
            StringBuilder sb = new StringBuilder();
            sb.append(ALPHA[r]);
            for (int i = 0; i < q + 1; i++) sb.append('Z');
            suffix = sb.toString();
        }
        return "FPGA_" + suffix;
    }
}
