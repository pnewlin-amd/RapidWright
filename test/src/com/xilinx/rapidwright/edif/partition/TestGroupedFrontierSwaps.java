/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** A movable hierarchy owns several primitives, including a real FF capture. */
public class TestGroupedFrontierSwaps {
    @TempDir Path directory;
    private static HardwarePartitionGraph grouped() {
        // seq1 -> comb2 -> seq2 -> comb2 -> comb3 -> comb3 -> seq4.
        int[] owners={1,2,2,2,3,3,4};
        boolean[] seq={true,false,true,false,false,false,true};
        int[][] successors={{1},{2},{3},{4},{5},{6},{}};
        String[] names={"launch/ff","left/c0","left/ff","left/c1","right/c0","right/c1","capture/ff"};
        long[][] resources=new long[5][6];
        resources[1][1]=1;resources[2][0]=2;resources[2][1]=1;resources[3][0]=2;resources[4][1]=1;
        List<HardwarePartitionGraph.Net> nets=Arrays.asList(
                new HardwarePartitionGraph.Net(1,new int[]{2}),new HardwarePartitionGraph.Net(2,new int[]{2}),
                new HardwarePartitionGraph.Net(2,new int[]{3}),new HardwarePartitionGraph.Net(3,new int[]{3}),
                new HardwarePartitionGraph.Net(3,new int[]{4}));
        return new HardwarePartitionGraph(owners,seq,successors,names,nets,Collections.emptyMap(),resources,new long[]{0,1,3,2,1},0,0);
    }
    private HardwareMappingRefiner refiner(boolean forbidExtraFfOnA,Path output) throws Exception {
        Path capacities=output.resolve("capacities.txt");
        Files.write(capacities,Arrays.asList("0 2 "+(forbidExtraFfOnA?1:2)+" 0 0 0 0","1 2 2 0 0 0 0"));
        HardwareMappingRefiner refiner=new HardwareMappingRefiner(grouped(),PartitionTopology.complete(2),2,0,
                new int[]{-1,0,-1,-1,1},8,0,capacities);
        refiner.configureBalanceWeights(new int[]{0,1,2,2,1},new long[]{3,3});return refiner;
    }
    @Test void groupedExchangePreservesInternalRegisterCaptureAndTightLutScalarLoads() throws Exception {
        Path output=Files.createDirectory(directory.resolve("positive"));
        HardwarePartitionGraph graph=grouped();HardwareMappingRefiner refiner=refiner(false,output);
        int[] initial={-1,0,1,0,1};int[] actual=refiner.refine(initial,2,output);
        Assertions.assertArrayEquals(new int[]{-1,0,0,1,1},actual);
        CombinationalPathAnalyzer analyzer=new CombinationalPathAnalyzer(graph.ownerVertex,graph.sequential,graph.successors);
        Assertions.assertEquals(2,analyzer.analyze(initial).maxCrossings);
        Assertions.assertEquals(1,analyzer.analyze(actual).maxCrossings);
        Assertions.assertEquals(0,analyzer.analyze(actual).transitCount);
        refiner.validate(actual);
    }
    @Test void equalLutGroupedExchangeCannotOverflowDestinationFfCapacity() throws Exception {
        Path output=Files.createDirectory(directory.resolve("ff_negative"));
        int[] initial={-1,0,1,0,1};HardwareMappingRefiner refiner=refiner(true,output);
        Assertions.assertArrayEquals(initial,refiner.refine(initial,2,output));refiner.validate(initial);
    }
}
