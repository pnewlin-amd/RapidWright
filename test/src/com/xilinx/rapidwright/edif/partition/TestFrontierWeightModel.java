/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestFrontierWeightModel {
    @TempDir Path directory;
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String[] NAMES = {null, "top/a", "top/b[3]", "top/c"};
    private static final String HEADER = "vertex\tpath\tinteger_weight";

    private Path file(String... rows) throws Exception {
        Path path = directory.resolve("frontier.tsv");
        Files.write(path, Arrays.asList(rows));
        return path;
    }

    @Test
    void sourceAndActualVertexPathsBindAnUnorderedCompleteWeightTable() throws Exception {
        Path path = file("# native feedback estimates", "#input_sha256=" + SHA.toUpperCase(), HEADER,
                "3\ttop/c\t0", "1\ttop/a\t2147483647", "2\ttop/b[3]\t5");
        Assertions.assertArrayEquals(new int[] {0, Integer.MAX_VALUE, 5, 0},
                FrontierWeightModel.read(path, SHA, NAMES));
    }

    @Test
    void sourceBindingIsRequiredAndCannotBeChangedOrDuplicated() throws Exception {
        for (String[] rows : new String[][] {
                {HEADER, "1\ttop/a\t1", "2\ttop/b[3]\t0", "3\ttop/c\t0"},
                {"#input_sha256=" + "0".repeat(64), HEADER, "1\ttop/a\t1"},
                {"#input_sha256=" + SHA, "#input_sha256=" + SHA, HEADER, "1\ttop/a\t1"}}) {
            Path path = file(rows);
            Assertions.assertThrows(IllegalArgumentException.class, () -> FrontierWeightModel.read(path, SHA, NAMES));
        }
    }

    @Test
    void wrongPathMissingDuplicateOrOutOfRangeVerticesFailBeforeUse() throws Exception {
        for (String row : new String[] {"1\ttop/other\t1", "0\ttop/a\t1", "4\ttop/a\t1",
                "-1\ttop/a\t1", "1\ttop/a\t-1", "1\ttop/a\t1.5", "1\ttop/a\t2147483648"}) {
            Path path = file("#input_sha256=" + SHA, HEADER, row, "2\ttop/b[3]\t0", "3\ttop/c\t0");
            Assertions.assertThrows(IllegalArgumentException.class, () -> FrontierWeightModel.read(path, SHA, NAMES));
        }
        for (String[] rows : new String[][] {
                {"#input_sha256=" + SHA, HEADER, "1\ttop/a\t1", "2\ttop/b[3]\t1"},
                {"#input_sha256=" + SHA, HEADER, "1\ttop/a\t1", "1\ttop/a\t1", "2\ttop/b[3]\t1", "3\ttop/c\t1"},
                {"#input_sha256=" + SHA, HEADER, "1\ttop/a\t0", "2\ttop/b[3]\t0", "3\ttop/c\t0"},
                {"#input_sha256=" + SHA, "wrong_header", "1\ttop/a\t1"},
                {"#input_sha256=" + SHA, HEADER, "1\ttop/a\t1\textra"}}) {
            Path path = file(rows);
            Assertions.assertThrows(IllegalArgumentException.class, () -> FrontierWeightModel.read(path, SHA, NAMES));
        }
    }
}
