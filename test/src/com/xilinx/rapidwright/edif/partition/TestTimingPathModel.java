/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestTimingPathModel {
    @TempDir Path directory;
    static final String SHA = "a".repeat(64);
    static final String HEADER = "path_id\tslack_ns\trequirement_ns\tlaunch_clock\tcapture_clock\tcells_json";
    static final String[] FRONTIER = {null, "soc/a", "soc/b", "soc/c"};

    private TimingPathModel read(String... paths) throws Exception {
        Path file = directory.resolve("timing.tsv");
        List<String> lines = new java.util.ArrayList<>(Arrays.asList("#input_sha256=" + SHA,
                "#requested_max_paths=1000", "#skipped_unconstrained=2", HEADER));
        lines.addAll(Arrays.asList(paths)); Files.write(file, lines);
        return TimingPathModel.read(file, SHA, FRONTIER, "top");
    }

    @Test
    void sourceBoundMacroAncestorsPreserveClocksAndNonconsecutiveOwnerReentry() throws Exception {
        TimingPathModel model = read("p0\t0.1\t10\tlaunch\tcapture\t[\"@top_io:in\",\"top/soc/a/physical/ff\",\"soc/a/more\",\"soc/b\",\"soc/a/again\"]");
        Assertions.assertArrayEquals(new int[] {1, 2, 1}, model.path(0).owners());
        Assertions.assertEquals("launch", model.path(0).launchClock);
        Assertions.assertEquals("capture", model.path(0).captureClock);
        Assertions.assertEquals(1, model.unanchoredPathCount());
        Assertions.assertEquals("1000", model.metadata().get("requested_max_paths"));
        int[] returned = model.path(0).owners(); returned[0] = 3;
        Assertions.assertArrayEquals(new int[] {1, 2, 1}, model.path(0).owners());
    }

    @Test
    void invalidBindingsUnknownCellsDuplicateIdsAndNonfiniteNumbersFail() throws Exception {
        Path file = directory.resolve("invalid.tsv");
        for (List<String> rows : Arrays.asList(
                Arrays.asList(HEADER), Arrays.asList("#input_sha256=" + "b".repeat(64), HEADER),
                Arrays.asList("#input_sha256=" + SHA, HEADER, "x\t0\t10\ta\tb\t[\"unknown\"]"),
                Arrays.asList("#input_sha256=" + SHA, HEADER, "x\tNaN\t10\ta\tb\t[\"soc/a\"]"),
                Arrays.asList("#input_sha256=" + SHA, HEADER, "x\t0\tInfinity\ta\tb\t[\"soc/a\"]"),
                Arrays.asList("#input_sha256=" + SHA, HEADER, "x\t0\t10\ta\tb\t[1]"),
                Arrays.asList("#input_sha256=" + SHA, HEADER, "x\t0\t10\ta\tb\t[\"soc/a\"]", "x\t1\t10\ta\tb\t[\"soc/b\"]"))) {
            Files.write(file, rows);
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> TimingPathModel.read(file, SHA, FRONTIER, "top"));
        }
    }

    @Test
    void missingOrNonpositiveRequirementsAreReportedUnscoredWithoutInventedDelay() throws Exception {
        TimingPathModel model = read("valid\t2\t10\tL\tC\t[\"soc/a\",\"soc/b\"]",
                "unconstrained\tNA\tNA\t\t\t[\"soc/a\"]",
                "unsupported\t-1\t0\tL\tC\t[\"soc/b\"]");
        Assertions.assertEquals(3, model.pathCount());
        Assertions.assertEquals(1, model.scoredPathCount());
        Assertions.assertEquals(2, model.unscoredPathCount());
        TimingPathModel.Evaluation audit = model.evaluate(new int[] {-1, 0, 1, 0}, null);
        Assertions.assertEquals(1.0, audit.timingCost, 1e-12); // Normalized source criticality.
        Assertions.assertEquals(2.0, audit.sourceWorstSlackNs, 1e-12);
        Assertions.assertNull(audit.modeledWorstSlackNs);
    }

    @Test
    void explicitCutDelayRecomputesNewCriticalityAndModeledSlackForEveryAssignment() throws Exception {
        TimingPathModel model = read("newly_critical\t12\t10\tL\tC\t[\"soc/a\",\"soc/b\",\"soc/c\"]");
        int[] cuts = {-1, 0, 1, 2}, together = {-1, 0, 0, 0};
        Assertions.assertEquals(0, model.maxSourceCriticality());
        Assertions.assertEquals(0, model.evaluate(cuts, null).timingCost);
        TimingPathModel.Evaluation delayed = model.evaluate(cuts, 3.0);
        Assertions.assertEquals(6.0, delayed.modeledWorstSlackNs, 1e-12);
        Assertions.assertEquals(0.48, delayed.timingCost, 1e-12);
        Assertions.assertEquals(12.0, model.evaluate(together, 3.0).modeledWorstSlackNs, 1e-12);
        Assertions.assertEquals(0, model.evaluate(together, 3.0).timingCost);
        Assertions.assertEquals(0.48, model.evaluate(cuts, 3.0).timingCost, 1e-12);
        Assertions.assertThrows(IllegalArgumentException.class, () -> model.evaluate(cuts, -1.0));
    }

    @Test
    void sourceCriticalitiesForNativeEdgesRemainRawWhileCostsUseFixedRelativeNormalization() throws Exception {
        TimingPathModel high = read("critical\t0\t10\tL\tC\t[\"soc/a\",\"soc/b\",\"soc/a\"]",
                "other\t5\t10\tL\tC\t[\"soc/b\",\"soc/c\"]");
        TimingPathModel low = read("critical\t9\t10\tL\tC\t[\"soc/a\",\"soc/b\",\"soc/a\"]",
                "other\t9.5\t10\tL\tC\t[\"soc/b\",\"soc/c\"]");
        long ab = (1L << 32) | 2, ba = (2L << 32) | 1;
        Map<Long, Double> pairs = low.directedOwnerPairCriticalities();
        Assertions.assertEquals(0.01, low.maxSourceCriticality(), 1e-12);
        Assertions.assertEquals(0.01, pairs.get(ab), 1e-12);
        Assertions.assertEquals(0.01, pairs.get(ba), 1e-12);
        int[] placement = {-1, 0, 1, 2};
        Assertions.assertEquals(high.evaluate(placement, null).timingCost,
                low.evaluate(placement, null).timingCost, 1e-12);
        Assertions.assertEquals(4.25, low.evaluate(placement, null).timingCost, 1e-12);
    }
}
