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
 * overview:
 * - the partitioner assigns vertices to k partitions by index (0..k-1). other artifacts (like mapping.txt)
 *   need stable, human-readable names for those partitions. cells.txt is that simple, canonical source of truth.
 * - this utility provides helpers to emit cells.txt either from a caller-provided list of names, a count (where
 *   we generate names using partitionlabel), or a sparse index->name map. it ensures names exist for all indices
 *   up to the highest observed and fills gaps with canonical fpga_* labels (fpga_a, fpga_b, ...), keeping output
 *   deterministic and easy to audit across runs.
 * - using partitionlabel means name generation is consistent with other tools (for example, partitiontools and
 *   partitioner), so downstream artifacts like mapping.txt and io_cuts.txt remain aligned with the same labels.
 */
public final class CellsWriter {

    private CellsWriter() {
        // no instances; this is a pure document writer that emits cells.txt using simple rules and shared naming helpers
    }

    /**
     * writes cells.txt using the provided list of names (one per line, in order).
     *
     * details:
     * - this function does no transformation of the provided names; it writes them as-is in the given order.
     * - callers that already have partition labels (for example, loaded from a previous run or a user-specified scheme)
     *   can use this to preserve exact naming across workflows. downstream tools will read these names verbatim.
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
     *
     * details:
     * - when the exact names are not provided, we generate a deterministic set of labels: fpga_a, fpga_b, ...,
     *   continuing beyond z with fpga_az, fpga_zz, etc. this ensures new runs produce consistent labels without
     *   external dependencies and that larger k values remain easy to read.
     */
    public static void write(Path outDir, int numPartitions) {
        write(outDir, generateNames(numPartitions));
    }

    /**
     * writes cells.txt from an index->name map and fills any missing indices with generated labels.
     *
     * details:
     * - this is useful when only some partitions have custom names or when tooling creates partial mappings.
     * - we compute the highest index observed and ensure every index from 0..max has a name. if a name is missing
     *   or blank, we generate a canonical fpga_* label via partitionlabel so the final file is complete.
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
     *
     * details:
     * - we rely on partitionlabel.indexToFpgaLabel(i) for deterministic naming. this ensures consistency across
     *   all artifacts and tools that display partition labels and makes diff review easier.
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
     *
     * details:
     * - callers should prefer generateNames(count) when emitting a sequential list, but this helper is convenient
     *   when filling sparse or partial mappings one index at a time.
     */
    public static String generateName(int index) {
        return PartitionLabel.indexToFpgaLabel(index);
    }

}
