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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestAcceptedQualityAnchor {
    @TempDir Path temporary;
    static final String SHA = "a".repeat(64);

    private Path anchorFile(String... lines) throws Exception {
        Path file = temporary.resolve("anchor.tsv");
        Files.write(file, Arrays.asList(lines));
        return file;
    }

    @Test void strictSourceFrontierCoverageAndReferencesAreImmutable() throws Exception {
        String[] names = {null, "", "a/b"};
        AcceptedQualityAnchor anchor = AcceptedQualityAnchor.read(anchorFile(
                "#input_sha256=" + SHA, "#transfer_growth_reference=100", "#topology_growth_reference=200.5",
                "#native_receipt_sha256=" + "b".repeat(64), "vertex\tpath\tpartition", "2\ta/b\t1", "1\t\t0"), SHA, names, 2);
        Assertions.assertArrayEquals(new int[] {-1, 0, 1}, anchor.assignment());
        Assertions.assertEquals(100, anchor.transferGrowthReference);
        Assertions.assertEquals(200.5, anchor.topologyGrowthReference);
        int[] copy = anchor.assignment(); copy[1] = 1;
        Assertions.assertEquals(0, anchor.assignment()[1]);
        Assertions.assertThrows(UnsupportedOperationException.class, () -> anchor.metadata().put("x", "y"));
        Assertions.assertEquals("b".repeat(64), anchor.metadata().get("native_receipt_sha256"));
    }

    @Test void malformedBindingsDuplicatesCoverageAndNumbersFail() throws Exception {
        String[] valid = {"#input_sha256=" + SHA, "#transfer_growth_reference=100", "#topology_growth_reference=200",
                "vertex\tpath\tpartition", "1\ta\t0", "2\tb\t1"};
        List<List<String>> invalid = new ArrayList<>();
        invalid.add(Arrays.asList(Arrays.copyOfRange(valid, 1, valid.length)));
        for (String replacement : new String[] {"#input_sha256=" + "b".repeat(64),
                "#transfer_growth_reference=-1", "#transfer_growth_reference=1.2",
                "#topology_growth_reference=NaN", "#topology_growth_reference=-2"}) {
            List<String> rows = new ArrayList<>(Arrays.asList(valid));
            rows.set(replacement.startsWith("#input") ? 0 : replacement.startsWith("#transfer") ? 1 : 2, replacement);
            invalid.add(rows);
        }
        for (String replacement : new String[] {"1\twrong\t0", "1\ta\t-1", "1\ta\t2", "0\ta\t0"}) {
            List<String> rows = new ArrayList<>(Arrays.asList(valid)); rows.set(4, replacement); invalid.add(rows);
        }
        List<String> duplicate = new ArrayList<>(Arrays.asList(valid)); duplicate.add(valid[4]); invalid.add(duplicate);
        duplicate = new ArrayList<>(Arrays.asList(valid)); duplicate.add(valid[0]); invalid.add(duplicate);
        invalid.add(Arrays.asList(Arrays.copyOf(valid, valid.length - 1)));
        for (List<String> rows : invalid) {
            Path file = anchorFile(rows.toArray(new String[0]));
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> AcceptedQualityAnchor.read(file, SHA, new String[] {null, "a", "b"}, 2), rows.toString());
        }
    }

    private HardwarePartitionGraph fixture() {
        // Primary chain initially has seven crossings. A separate fixed chain
        // keeps both prior/proposed maps at max5. B and C parallel branches
        // permit initial<proposal<prior transit, reproducing the real conflict.
        int[][] successors = {{1,16,18},{2},{3},{4,8},{5},{6},{7,8},{},{},{},
                {11},{12},{13},{14},{15},{},{17,8},{7,8},{19},{7}};
        boolean[] sequential = new boolean[20];
        for (int node : new int[] {0,7,8,9,10,15}) sequential[node] = true;
        int[] owners = new int[20]; String[] names = new String[20];
        long[][] resources = new long[21][6]; long[] logic = new long[21];
        List<HardwarePartitionGraph.Net> nets = new ArrayList<>();
        for (int n = 0; n < 20; n++) {
            owners[n] = n + 1; names[n] = "p" + n; resources[n+1][0] = 1; logic[n+1] = 1;
            if (successors[n].length > 0) nets.add(new HardwarePartitionGraph.Net(n+1,
                    Arrays.stream(successors[n]).map(v -> v+1).toArray()));
        }
        resources[5][0] = resources[6][0] = 2;
        // Parallel signals strengthen the local incentive to gather A/B on1.
        for (int owner : new int[] {4,7,17,18}) for (int j=0;j<10;j++)
            nets.add(new HardwarePartitionGraph.Net(owner, new int[] {9}));
        return new HardwarePartitionGraph(owners,sequential,successors,names,nets,
                Collections.emptyMap(),resources,logic,0,0);
    }

    private int[] initial() { return new int[] {-1,0,1,2,0,1,2,0,0,1,2,0,1,2,0,1,2,0,0,0,0}; }
    private int[] prior() {
        int[] result = initial();
        for (int owner : new int[] {4,5,6,7}) result[owner] = 1;
        for (int owner : new int[] {17,18,19,20}) result[owner] = 2;
        return result;
    }
    private int[] fixed() {
        int[] result = initial();
        for (int owner : new int[] {4,5,6,7,17,18,19,20}) result[owner] = -1;
        return result;
    }
    private HardwareMappingRefiner refiner(HardwarePartitionGraph graph) throws Exception {
        Path caps = temporary.resolve("capacities.txt");
        Files.write(caps, Arrays.asList("0 10 0 0 0 0 0", "1 30 0 0 0 0 0", "2 30 0 0 0 0 0"));
        HardwareMappingRefiner refiner = new HardwareMappingRefiner(graph, PartitionTopology.complete(3),3,1,fixed(),1000,0.1,caps);
        refiner.configureQualityGuard("componentwise"); // Preserve this conservative-anchor fixture.
        return refiner;
    }

    @Test void anchorAdmitsGlobalImprovementRejectedByFreshNativeTransitGuard() throws Exception {
        HardwarePartitionGraph graph = fixture();
        CombinationalPathAnalyzer analyzer = new CombinationalPathAnalyzer(graph.ownerVertex,graph.sequential,graph.successors);
        int[] initial = initial(), prior = prior();
        CombinationalPathAnalyzer.Analysis nativeAudit = analyzer.analyze(initial), priorAudit = analyzer.analyze(prior);
        Assertions.assertEquals(7,nativeAudit.maxCrossings); Assertions.assertEquals(5,priorAudit.maxCrossings);
        HardwareMappingRefiner legacy = refiner(graph);
        Path legacyOutput = temporary.resolve("legacy"); Files.createDirectories(legacyOutput);
        int[] conservative = legacy.refine(initial,1,legacyOutput);
        Assertions.assertArrayEquals(initial,conservative);
        HardwareMappingRefiner anchored = refiner(graph);
        int[] weights = new int[21]; Arrays.fill(weights,1); weights[0]=0; weights[19]=weights[20]=10;
        anchored.configureBalanceWeights(weights,new long[] {100,100,10});
        Assertions.assertThrows(IllegalArgumentException.class, () -> anchored.validate(prior));
        anchored.configureQualityAnchor(prior,100,100);
        Path output = temporary.resolve("anchored"); Files.createDirectories(output);
        int[] result;
        try { result = anchored.refine(initial,1,output); }
        catch (IllegalArgumentException error) {
            throw new AssertionError("prior=" + priorAudit.maxCrossings + "/" + priorAudit.excessCrossings + "/" + priorAudit.transitCount
                    + " initial=" + nativeAudit.maxCrossings + "/" + nativeAudit.excessCrossings + "/" + nativeAudit.transitCount
                    + " passes=" + Files.readAllLines(output.resolve("refinement_passes.tsv")), error);
        }
        CombinationalPathAnalyzer.Analysis proposal = analyzer.analyze(result);
        Assertions.assertEquals(5,proposal.maxCrossings);
        Assertions.assertTrue(proposal.transitCount > nativeAudit.transitCount);
        Assertions.assertTrue(proposal.transitCount <= priorAudit.transitCount);
        Assertions.assertTrue(proposal.excessCrossings <= priorAudit.excessCrossings);
        anchored.validate(result);
        List<String> report = Files.readAllLines(output.resolve("hardware_quality.txt"));
        Assertions.assertTrue(report.contains("quality_guard_reference=prior_native_accepted_assignment"));
        Assertions.assertTrue(report.contains("transfer_growth_reference=100"));
        Assertions.assertTrue(report.contains("topology_growth_reference=100.0"));
    }

    @Test void infeasibleOrQualityWorseFinalCannotFallBackToFreshBaseline() throws Exception {
        HardwarePartitionGraph graph = fixture(); HardwareMappingRefiner anchored = refiner(graph);
        anchored.configureQualityAnchor(prior(),100,100);
        Path output = temporary.resolve("no_passes"); Files.createDirectories(output);
        IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
                () -> anchored.refine(initial(),0,output));
        Assertions.assertTrue(error.getMessage().contains("accepted quality anchor"));
        int[] wrongFixed = prior(); wrongFixed[1]=1;
        Assertions.assertThrows(IllegalArgumentException.class, () -> anchored.configureQualityAnchor(wrongFixed,100,100));
        Assertions.assertThrows(IllegalArgumentException.class, () -> anchored.configureQualityAnchor(prior(),-1,100));
        // A globally tiny original cut budget must not reset to the native seed.
        anchored.configureQualityAnchor(prior(),0,0);
        Path budgetOutput = temporary.resolve("no_budget"); Files.createDirectories(budgetOutput);
        Assertions.assertThrows(IllegalArgumentException.class, () -> anchored.refine(initial(),1,budgetOutput));
    }

    @Test void currentCapacityAndActualCycleChecksStillReject() throws Exception {
        HardwarePartitionGraph graph = fixture(); HardwareMappingRefiner anchored = refiner(graph);
        anchored.configureQualityAnchor(prior(),100,100);
        int[] weights = new int[21]; Arrays.fill(weights,1); weights[0]=0;
        anchored.configureBalanceWeights(weights,new long[] {1,1,1}); // Total capacity3 cannot hold20.
        Path output = temporary.resolve("capacity_fail"); Files.createDirectories(output);
        Assertions.assertThrows(IllegalArgumentException.class, () -> anchored.refine(initial(),1,output));
        graph.successors[19] = new int[] {18,7}; // Real combinational SCC on C1/C2.
        HardwareMappingRefiner cyclic = refiner(graph); cyclic.configureQualityAnchor(prior(),100,100);
        Path cycleOutput = temporary.resolve("cycle_fail"); Files.createDirectories(cycleOutput);
        IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
                () -> cyclic.refine(initial(),1,cycleOutput));
        Assertions.assertTrue(error.getMessage().contains("incomplete current path model"));
    }

    @Test void sourceTimingAndExplicitModeledSlackAreReevaluatedAgainstAnchor() throws Exception {
        HardwarePartitionGraph graph = fixture(); String[] names = new String[21];
        for (int v=1;v<names.length;v++) names[v]="p"+(v-1);
        Path timingFile=temporary.resolve("timing.tsv");
        for (Double delay : new Double[] {null,3.0}) {
            String slack = delay == null ? "0" : "12";
            Files.write(timingFile,Arrays.asList("#input_sha256="+SHA,
                    "path_id\tslack_ns\trequirement_ns\tlaunch_clock\tcapture_clock\tcells_json",
                    "critical\t"+slack+"\t10\tclk\tclk\t[\"p16\",\"p18\"]"));
            TimingPathModel model=TimingPathModel.read(timingFile,SHA,names,"top");
            HardwareMappingRefiner anchored=refiner(graph);
            anchored.configureTimingPaths(model,0.001,delay);
            anchored.configureQualityAnchor(prior(),100,100);
            Path output=temporary.resolve(delay==null?"source_timing_fail":"modeled_timing_fail");
            Files.createDirectories(output);
            Assertions.assertEquals(0,anchored.timingEvaluation(prior()).timingCost);
            Assertions.assertThrows(IllegalArgumentException.class,()->anchored.refine(initial(),1,output));
        }
    }
}
