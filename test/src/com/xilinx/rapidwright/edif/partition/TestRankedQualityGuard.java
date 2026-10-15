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

/** Real tiny DAG analyses prove the ordered guard; no fabricated metric fields. */
public class TestRankedQualityGuard {
    @TempDir Path temporary;
    private int fileId;
    private static final String SHA = "a".repeat(64);

    private HardwarePartitionGraph graph(int[] owners, boolean[] sequential, int[][] successors) {
        int max = Arrays.stream(owners).max().orElse(0);
        long[][] resources = new long[max+1][6]; long[] logic = new long[max+1];
        for (int v=1;v<=max;v++) { resources[v][0]=1; logic[v]=1; }
        String[] names=new String[owners.length]; List<HardwarePartitionGraph.Net> nets=new ArrayList<>();
        for(int p=0;p<owners.length;p++) {
            names[p]="primitive"+p;
            if(successors[p].length>0) nets.add(new HardwarePartitionGraph.Net(owners[p],
                    Arrays.stream(successors[p]).map(n->owners[n]).toArray()));
        }
        return new HardwarePartitionGraph(owners,sequential,successors,names,nets,
                Collections.emptyMap(),resources,logic,0,0);
    }

    private PartitionTopology complete(int k) throws Exception {
        Path file=temporary.resolve("complete"+(fileId++)+".graph"); List<String> rows=new ArrayList<>();
        rows.add(k+" "+(k*(k-1)/2)+" 1");
        for(int p=0;p<k;p++) { StringBuilder row=new StringBuilder();
            for(int q=0;q<k;q++)if(p!=q)row.append(q+1).append(" 1 "); rows.add(row.toString().trim()); }
        Files.write(file,rows);return PartitionTopology.read(file,k);
    }

    private HardwareMappingRefiner refiner(HardwarePartitionGraph graph,PartitionTopology topology,int k) throws Exception {
        int[] fixed=new int[graph.resources.length];Arrays.fill(fixed,-1);
        return new HardwareMappingRefiner(graph,topology,k,10,fixed,8,0,null);
    }

    private HardwarePartitionGraph oneTransitTradeoff() {
        // Main A,B,A,B,A,C becomes A,B,A,B,B,C: C5->4 and all
        // four internal primitives remain transit. A separate A,p5,A
        // data path acquires exactly one new transit primitive.
        return graph(new int[]{1,2,3,4,5,6,7,5,8},
                new boolean[]{true,false,false,false,false,true,true,false,true},
                new int[][]{{1},{2},{3},{4},{5},{},{7},{8},{}});
    }

    private CombinationalPathAnalyzer.Analysis analyze(HardwarePartitionGraph graph,int[] assignment,PartitionTopology topology) {
        return new CombinationalPathAnalyzer(graph.ownerVertex,graph.sequential,graph.successors)
                .analyze(assignment,topology.minimumHopMatrix());
    }

    @Test void lowerWorstSnakeRankWinsDespiteExactlyOneAdditionalTransitPrimitive() throws Exception {
        HardwarePartitionGraph graph=oneTransitTradeoff();PartitionTopology topology=complete(3);
        int[] before={-1,0,1,0,1,0,2,0,0},after=before.clone();after[5]=1;
        CombinationalPathAnalyzer.Analysis baseline=analyze(graph,before,topology);CombinationalPathAnalyzer.Analysis candidate=analyze(graph,after,topology);
        Assertions.assertEquals(5,baseline.maxCrossings);Assertions.assertEquals(4,candidate.maxCrossings);
        Assertions.assertEquals(5,baseline.maxMinimumLinkHops);Assertions.assertEquals(4,candidate.maxMinimumLinkHops);
        Assertions.assertEquals(baseline.excessCrossings,candidate.excessCrossings);
        Assertions.assertEquals(baseline.transitCount+1,candidate.transitCount);
        HardwareMappingRefiner refiner=refiner(graph,topology,3);refiner.validate(after);
        Assertions.assertTrue(refiner.qualityNonregression(candidate,null,baseline,null));
        Assertions.assertFalse(refiner.qualityNonregression(baseline,null,candidate,null),
                "Improved transit cannot excuse a regression in the primary snake rank");
        refiner.configureQualityGuard("componentwise");
        Assertions.assertFalse(refiner.qualityNonregression(candidate,null,baseline,null));
    }

    @Test void equalWorstRankUsesEndpointExcessBeforeTransit() throws Exception {
        // Fixed C5 main path; a secondary C4->2 path keeps three transit
        // primitives and one new A,p10,A path adds one. Excess improves7->6.
        HardwarePartitionGraph graph=graph(new int[]{1,2,3,4,5,6,7,9,10,11,8,12,10,13},
                new boolean[]{true,false,false,false,false,true,true,false,false,false,true,true,false,true},
                new int[][]{{1},{2},{3},{4},{5},{},{7},{8},{9},{10},{},{12},{13},{}});
        int[] before={-1,0,1,0,1,0,2,0,0,1,0,1,0,0},after=before.clone();after[10]=1;
        PartitionTopology topology=complete(3);HardwareMappingRefiner refiner=refiner(graph,topology,3);
        CombinationalPathAnalyzer.Analysis baseline=analyze(graph,before,topology);CombinationalPathAnalyzer.Analysis candidate=analyze(graph,after,topology);
        Assertions.assertEquals(baseline.maxMinimumLinkHops,candidate.maxMinimumLinkHops);
        Assertions.assertEquals(baseline.maxCrossings,candidate.maxCrossings);
        Assertions.assertTrue(candidate.excessCrossings<baseline.excessCrossings);
        Assertions.assertEquals(baseline.transitCount+1,candidate.transitCount);
        Assertions.assertTrue(refiner.qualityNonregression(candidate,null,baseline,null));
        Assertions.assertFalse(refiner.qualityNonregression(baseline,null,candidate,null));
        refiner.configureQualityGuard("componentwise");
        Assertions.assertFalse(refiner.qualityNonregression(candidate,null,baseline,null));
    }

    private TimingPathModel timing(int owners) throws Exception {
        Path file=temporary.resolve("timing"+(fileId++)+".tsv");
        Files.write(file,Arrays.asList("#input_sha256="+SHA,
                "path_id\tslack_ns\trequirement_ns\tlaunch_clock\tcapture_clock\tcells_json",
                "sample\t1\t10\tL\tC\t[\"p1\",\"p5\",\"p1\"]"));
        String[] names=new String[owners+1];for(int v=1;v<names.length;v++)names[v]="p"+v;
        return TimingPathModel.read(file,SHA,names,"top");
    }

    @Test void sourceOnlyStaAndExplicitDelayUseTheirExistingDifferentPriority() throws Exception {
        HardwarePartitionGraph graph=oneTransitTradeoff();PartitionTopology topology=complete(3);
        int[] before={-1,0,1,0,1,0,2,0,0},after=before.clone();after[5]=1;
        CombinationalPathAnalyzer.Analysis baseline=analyze(graph,before,topology);CombinationalPathAnalyzer.Analysis candidate=analyze(graph,after,topology);
        HardwareMappingRefiner refiner=refiner(graph,topology,3);TimingPathModel model=timing(8);
        refiner.configureTimingPaths(model,8,null);
        TimingPathModel.Evaluation oldTiming=refiner.timingEvaluation(before);TimingPathModel.Evaluation newTiming=refiner.timingEvaluation(after);
        Assertions.assertTrue(newTiming.timingCost>oldTiming.timingCost);
        Assertions.assertTrue(refiner.qualityNonregression(candidate,newTiming,baseline,oldTiming),
                "Source-only timing follows H/C, preserving the declared current priority");
        refiner.configureTimingPaths(model,8,0.7);
        oldTiming=refiner.timingEvaluation(before);newTiming=refiner.timingEvaluation(after);
        Assertions.assertTrue(newTiming.modeledWorstSlackNs<oldTiming.modeledWorstSlackNs);
        Assertions.assertFalse(refiner.qualityNonregression(candidate,newTiming,baseline,oldTiming));
        Assertions.assertTrue(refiner.qualityNonregression(baseline,oldTiming,candidate,newTiming),
                "In explicitly modeled-delay mode improved slack is the primary rank");
        refiner.configureQualityGuard("componentwise");
        Assertions.assertFalse(refiner.qualityNonregression(baseline,oldTiming,candidate,newTiming));
    }

    @Test void sparseTopologyPrimaryHopRegressionStillFailsDespiteEqualLogicalCrossings() throws Exception {
        Path file=temporary.resolve("chain.graph");Files.write(file,Arrays.asList("4 3 1","2 1","1 1 3 1","2 1 4 1","3 1"));
        PartitionTopology topology=PartitionTopology.read(file,4);
        HardwarePartitionGraph graph=graph(new int[]{1,2,3,4,5,6},
                new boolean[]{true,true,true,true,true,true},new int[][]{{1},{3},{4},{},{},{}});
        int[] before={-1,0,1,3,3,1,2},after={-1,0,3,1,3,1,2};
        CombinationalPathAnalyzer.Analysis baseline=analyze(graph,before,topology);CombinationalPathAnalyzer.Analysis candidate=analyze(graph,after,topology);
        Assertions.assertEquals(baseline.maxCrossings,candidate.maxCrossings);
        Assertions.assertEquals(2,baseline.maxMinimumLinkHops);Assertions.assertEquals(3,candidate.maxMinimumLinkHops);
        Assertions.assertFalse(refiner(graph,topology,4).qualityNonregression(candidate,null,baseline,null));
    }

    @Test void incompleteGraphCannotPassEitherGuard() throws Exception {
        HardwarePartitionGraph graph=graph(new int[]{1,2},new boolean[]{false,false},new int[][]{{1},{0}});
        PartitionTopology topology=complete(2);CombinationalPathAnalyzer.Analysis audit=analyze(graph,new int[]{-1,0,1},topology);
        Assertions.assertFalse(audit.complete);HardwareMappingRefiner refiner=refiner(graph,topology,2);
        Assertions.assertFalse(refiner.qualityNonregression(audit,null,audit,null));
        refiner.configureQualityGuard("componentwise");Assertions.assertFalse(refiner.qualityNonregression(audit,null,audit,null));
    }

    @Test void policyIdentityIsExplicitAndLegacyAssignmentIsReanalyzed() throws Exception {
        HardwarePartitionGraph graph=oneTransitTradeoff();HardwareMappingRefiner refiner=refiner(graph,complete(3),3);
        int[] assignment={-1,0,1,0,1,0,2,0,0};
        Assertions.assertEquals("ranked",refiner.qualityGuardPolicy());
        refiner.configureQualityAnchor(assignment,10,10);
        Assertions.assertEquals("legacy_absent_reanalyzed",refiner.qualityGuardAnchorPolicyMetadata());
        refiner.configureQualityAnchor(assignment,10,10,"ranked");
        Assertions.assertThrows(IllegalArgumentException.class,()->refiner.configureQualityGuard("componentwise"));
        Assertions.assertThrows(IllegalArgumentException.class,()->refiner.configureQualityGuard("other"));
        Path file=temporary.resolve("anchor.tsv");String[] rows={"#input_sha256="+SHA,
                "#transfer_growth_reference=10","#topology_growth_reference=10",
                "vertex\tpath\tpartition","1\ta\t0","2\tb\t1"};Files.write(file,Arrays.asList(rows));
        AcceptedQualityAnchor anchor=AcceptedQualityAnchor.read(file,SHA,new String[]{null,"a","b"},2);
        Assertions.assertEquals("legacy_absent_reanalyzed",anchor.validateQualityGuardPolicy("ranked"));
        List<String> declared=new ArrayList<>(Arrays.asList(rows));declared.add(0,"#quality_guard_policy=componentwise");Files.write(file,declared);
        anchor=AcceptedQualityAnchor.read(file,SHA,new String[]{null,"a","b"},2);
        Assertions.assertEquals("componentwise",anchor.validateQualityGuardPolicy("componentwise"));
        final AcceptedQualityAnchor bound=anchor;
        Assertions.assertThrows(IllegalArgumentException.class,()->bound.validateQualityGuardPolicy("ranked"));
    }

    @Test void cliRejectsInvalidPolicyBeforeSourceOrBackendAccess() {
        for(String[] flags:new String[][]{{"--quality_guard","unknown"},{"--quality_guard=unknown"}}) {
            List<String> args=new ArrayList<>(Arrays.asList("nonexistent.edf","3","auto"));args.addAll(Arrays.asList(flags));
            IllegalArgumentException error=Assertions.assertThrows(IllegalArgumentException.class,()->Partitioner.main(args.toArray(new String[0])));
            Assertions.assertTrue(error.getMessage().contains("Quality guard"),error.getMessage());
        }
    }
}
