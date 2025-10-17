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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * writes cells.txt (one partition name per line) into the output directory.
 * 
 * Simply displays the name of the partitions:
 *   Line 1: FPGA_A
 *   Line 2: FPGA_B
 *   Line 3: FPGA_C
 *   Line 4: FPGA_D 
 *   etc.....
 * 
 * TODO: likely not needed for final version of regroup instances, might be redudant artifact
 */
public final class CellsWriter {

    private CellsWriter() {
        // no instances.
    }

    /**
     * writes cells.txt using the provided list of names (one per line, in order).
     */
    public static void write(Path outDir, List<String> partitionNames) {
        if (partitionNames == null) {
            throw new IllegalArgumentException("partitionNames is null");
        }
        try {
            if (outDir != null) {
                Files.createDirectories(outDir);
            }
            Path cellsFile = outDir.resolve("cells.txt");
            Files.write(cellsFile, partitionNames, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * writes cells.txt for a given number of partitions, generating names using partitionlabel.
     */
    public static void write(Path outDir, int numPartitions) {
        write(outDir, generateNames(numPartitions));
    }

    /**
     * writes cells.txt from an index->name map and fills any missing indices with generated labels.
     */
    public static void write(Path outDir, Map<Integer, String> indexToName) {
        if (indexToName == null || indexToName.isEmpty()) {
            write(outDir, Collections.emptyList());
            return;
        }
        int maxIdx = -1;
        for (Integer idx : indexToName.keySet()) {
            if (idx != null && idx > maxIdx) maxIdx = idx;
        }
        if (maxIdx < 0) {
            write(outDir, Collections.emptyList());
            return;
        }
        List<String> names = new ArrayList<>(maxIdx + 1);
        for (int i = 0; i <= maxIdx; i++) {
            String name = indexToName.get(i);
            if (name == null || name.trim().isEmpty()) {
                name = generateName(i);
            }
            names.add(name);
        }
        write(outDir, names);
    }

    /**
     * generates a list of canonical partition names ["fpga_a","fpga_b",...] using partitionlabel.
     */
    public static List<String> generateNames(int count) {
        if (count <= 0) return Collections.emptyList();
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add(PartitionLabel.indexToFpgaLabel(i));
        }
        return names;
    }

    /**
     * generates a single canonical partition name "fpga_*" via partitionlabel.
     */
    public static String generateName(int index) {
        return PartitionLabel.indexToFpgaLabel(index);
    }

}
