/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestMtKaHyParScalarCapacities {
    @Test
    void nativeLibraryPathIsAppliedToBothVersionAndSolverProcessBuilder() throws Exception {
        MtKaHyParPartitioner backend = new MtKaHyParPartitioner();
        backend.setLibraryPath("/custom libraries/mtkahypar");
        Method method = MtKaHyParPartitioner.class.getDeclaredMethod("processBuilder", java.util.List.class);
        method.setAccessible(true);
        ProcessBuilder direct = (ProcessBuilder) method.invoke(backend, Arrays.asList("mtkahypar", "--version"));
        Assertions.assertEquals("/custom libraries/mtkahypar", direct.environment().get("LD_LIBRARY_PATH"));
        Assertions.assertEquals(Arrays.asList("mtkahypar", "--version"), direct.command());
        backend.setWslDistribution("ExplicitDistro");
        ProcessBuilder wsl = (ProcessBuilder) method.invoke(backend, Arrays.asList("wsl.exe", "-d", "ExplicitDistro"));
        Assertions.assertEquals(System.getenv("LD_LIBRARY_PATH"), wsl.environment().get("LD_LIBRARY_PATH"));
    }

    @Test
    void explicitScalarCapacitiesRequireConfiguredKAndExactNativeIntegerRange() throws Exception {
        MtKaHyParPartitioner backend = new MtKaHyParPartitioner();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> backend.setIndividualPartWeights(new long[] {2, 3}));
        backend.setKPartitions(2);
        for (long[] invalid : new long[][] {{1}, {1, -1}, {1, (long) Integer.MAX_VALUE + 1}}) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> backend.setIndividualPartWeights(invalid));
        }
        long[] capacities = {0, Integer.MAX_VALUE};
        backend.setIndividualPartWeights(capacities);
        capacities[1] = 1;
        Field field = MtKaHyParPartitioner.class.getDeclaredField("individualPartWeights");
        field.setAccessible(true);
        Assertions.assertArrayEquals(new long[] {0, Integer.MAX_VALUE}, (long[]) field.get(backend));
        backend.setIndividualPartWeights(null);
        Assertions.assertNull(field.get(backend));
    }
}
