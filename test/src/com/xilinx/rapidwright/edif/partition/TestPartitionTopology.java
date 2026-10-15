/* Copyright (c) 2026, Advanced Micro Devices, Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestPartitionTopology {
    @TempDir
    Path directory;

    @Test
    void asymmetricLinkCostsUseShortestWeightedRoute() throws IOException {
        Path file = directory.resolve("topology.graph");
        Files.write(file, Arrays.asList("3 3 1", "2 2 3 9", "1 2 3 3", "1 9 2 3"));
        PartitionTopology topology = PartitionTopology.read(file, 3);
        Assertions.assertEquals(5.0, topology.distance(0, 2));
        Assertions.assertEquals(5.0, topology.distance(2, 0));
        Assertions.assertEquals(0.0, topology.distance(1, 1));
    }

    @Test
    void refusesDisconnectedOrAsymmetricTopologies() throws IOException {
        Path file = directory.resolve("topology.graph");
        Files.write(file, Arrays.asList("3 1 1", "2 1", "1 1", ""));
        Assertions.assertThrows(IllegalArgumentException.class, () -> PartitionTopology.read(file, 3));
        Files.write(file, Arrays.asList("2 1 1", "2 1", "1 2"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> PartitionTopology.read(file, 2));
    }

    @Test
    void internalIntegerScaleKeepsRefinementCostsInHopUnits() throws IOException {
        Path file = directory.resolve("physical.graph");
        Files.write(file, Arrays.asList("% rapidwright_cost_scale=24", "3 2 1", "2 24", "1 24 3 25", "2 25"));
        PartitionTopology topology = PartitionTopology.read(file, 3);
        Assertions.assertEquals(1.0, topology.distance(0, 1));
        Assertions.assertEquals(49.0 / 24, topology.distance(0, 2));
        Files.write(file, Arrays.asList("% rapidwright_cost_scale=0", "1 0 1", ""));
        Assertions.assertThrows(IllegalArgumentException.class, () -> PartitionTopology.read(file, 1));
    }

    @Test
    void singletonHasZeroSelfDistanceAndNoLinks() throws IOException {
        Path file = directory.resolve("single.graph");
        Files.write(file, Arrays.asList("% rapidwright_cost_scale=1", "1 0 1", ""));
        Assertions.assertEquals(0, PartitionTopology.read(file, 1).distance(0, 0));
    }
}
