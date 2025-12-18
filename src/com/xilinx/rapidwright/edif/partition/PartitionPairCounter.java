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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counts partition pairs from hypergraph files.
 */
public final class PartitionPairCounter {

    private PartitionPairCounter() {
    }

    /**
     * Counts how many nets connect between each pair of partitions.
     * 
     * @param hgrFile Hypergraph file path
     * @param instLookup Vertex ID to instance name array
     * @param nameToPart Instance name to partition ID map
     * @return Partition pair counts map
     */
    public static Map<String, Integer> countPartitionPairs(Path hgrFile,
            String[] instLookup, Map<String, Integer> nameToPart) {
        Map<String, Integer> pairCounts = new LinkedHashMap<>();
        try (BufferedReader hgrReader = new BufferedReader(
                new FileReader(hgrFile.toFile()))) {
            String header = hgrReader.readLine();
            String line;
            while ((line = hgrReader.readLine()) != null) {
                String[] tokens = line.trim().isEmpty() ?
                        new String[0] : line.trim().split("\\s+");
                Set<Integer> partitionIds = new HashSet<>();
                for (String t : tokens) {
                    if (t.isEmpty()) {
                        continue;
                    }
                    int vertexId;
                    try {
                        vertexId = Integer.parseInt(t);
                    } catch (NumberFormatException nfe) {
                        continue;
                    }
                    String instanceName = (vertexId >= 0 && vertexId < instLookup.length)
                            ? instLookup[vertexId] : null;
                    Integer part = (instanceName == null) ?
                            null : nameToPart.get(instanceName);
                    if (part != null) {
                        partitionIds.add(part.intValue());
                    }
                }
                if (partitionIds.size() < 2) {
                    continue;
                }
                List<Integer> partitionIdList = new ArrayList<>(partitionIds);
                for (int i = 0; i < partitionIdList.size(); i++) {
                    for (int j = i + 1; j < partitionIdList.size(); j++) {
                        String partitionLabelA =
                                PartitionLabel.indexToFpgaLabel(partitionIdList.get(i));
                        String partitionLabelB =
                                PartitionLabel.indexToFpgaLabel(partitionIdList.get(j));
                        String pairKeyForward = partitionLabelA + "," + partitionLabelB;
                        String pairKeyBackward = partitionLabelB + "," + partitionLabelA;
                        pairCounts.put(pairKeyForward,
                                pairCounts.getOrDefault(pairKeyForward, 0) + 1);
                        pairCounts.put(pairKeyBackward,
                                pairCounts.getOrDefault(pairKeyBackward, 0) + 1);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return pairCounts;
    }

    /**
     * Writes partition pair counts to file.
     * 
     * @param outputFile Output file path
     * @param pairCounts Partition pair counts map
     */
    public static void writePairCountsToFile(Path outputFile,
            Map<String, Integer> pairCounts) {
        List<String> keys = new ArrayList<>(pairCounts.keySet());
        Collections.sort(keys);
        List<String> lines = new ArrayList<>(keys.size());
        for (String pairKey : keys) {
            int separatorIndex = pairKey.indexOf(',');
            String partitionLabelA = pairKey.substring(0, separatorIndex);
            String partitionLabelB = pairKey.substring(separatorIndex + 1);
            int pairCount = pairCounts.get(pairKey);
            lines.add(partitionLabelA + "--" + pairCount + "--" + partitionLabelB);
        }
        try {
            Files.write(outputFile, lines, StandardCharsets.UTF_8);
            PartitionTools.logPartitionerDebug(null,
                    "Partitioner artifact written: %s", outputFile.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
