package com.xilinx.rapidwright.edif.partition;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Discriminating9-node DAG: an interleaved exchange loses a good greedy pass
 * while remaining inside the original native guard. Separate v2 must keep it. */
public class TestRetainedGreedySwapPhase {
    @TempDir Path directory;
    @Test void exactSharedNetSwapDeltaRespectsNormalizedWeightedTopology() throws Exception {
        Path topologyFile=directory.resolve("weighted.graph");
        Files.write(topologyFile,Arrays.asList("% rapidwright_cost_scale=24","3 2 1","2 24","1 24 3 25","2 25"));
        PartitionTopology topology=PartitionTopology.read(topologyFile,3);
        long[][] resources=new long[5][6];for(int v=1;v<=4;v++)resources[v][0]=1;
        HardwarePartitionGraph graph=new HardwarePartitionGraph(new int[]{1,2,3,4},new boolean[]{true,true,true,true},
                new int[][]{{},{},{},{}},new String[]{"a","b","c","d"},Arrays.asList(
                    new HardwarePartitionGraph.Net(2,new int[]{2,3,4}),new HardwarePartitionGraph.Net(3,new int[]{1,2,3}),
                    new HardwarePartitionGraph.Net(2,new int[]{1,3})),Collections.emptyMap(),resources,new long[]{0,1,1,1,1},0,0);
        int[] fixed={-1,-1,-1,-1,-1},assignment={-1,0,1,2,0};
        HardwareMappingRefiner refiner=new HardwareMappingRefiner(graph,topology,3,0,fixed,0,0,null);
        java.lang.reflect.Method initialize=HardwareMappingRefiner.class.getDeclaredMethod("initializeNetCache",int[].class);
        initialize.setAccessible(true);initialize.invoke(refiner,(Object)assignment);
        java.lang.reflect.Method probe=HardwareMappingRefiner.class.getDeclaredMethod("probeSwap",int.class,int.class,int[].class);
        probe.setAccessible(true);Object delta=probe.invoke(refiner,2,3,assignment);
        java.lang.reflect.Field cost=delta.getClass().getDeclaredField("cost"),distance=delta.getClass().getDeclaredField("topology"),transfers=delta.getClass().getDeclaredField("transfers");
        cost.setAccessible(true);distance.setAccessible(true);transfers.setAccessible(true);
        Assertions.assertEquals(25.0/24,cost.getDouble(delta),1e-9);
        Assertions.assertEquals(25.0/24,distance.getDouble(delta),1e-9);
        Assertions.assertEquals(0,transfers.getLong(delta));
        Assertions.assertArrayEquals(new int[]{-1,0,1,2,0},assignment);
    }
    @Test void nativeGuardAloneCannotAuthorizeLosingTheRetainedGreedyBest() throws Exception {
        int n=9,k=3;int[] owners={1,2,3,4,5,6,7,8,9};
        boolean[] seq={true,true,false,false,false,false,true,false,true};
        int[][] successors={{4,5,6,7},{2,5,7},{4,5,6,8},{6},{6,8},{8},{},{},{}};
        int[] initial={-1,1,2,2,0,0,1,2,0,1};
        int[] observedBadInterleaved={-1,1,0,0,2,2,0,2,0,2};
        long[][] resources=new long[n+1][6];long[] logic=new long[n+1];String[] names=new String[n];
        int[] fixed=new int[n+1];Arrays.fill(fixed,-1);List<HardwarePartitionGraph.Net> nets=new ArrayList<>();
        for(int u=0;u<n;u++){
            names[u]="p"+(u+1);resources[u+1][0]=1;logic[u+1]=1;
            if(successors[u].length>0)nets.add(new HardwarePartitionGraph.Net(u+1,Arrays.stream(successors[u]).map(v->v+1).toArray()));
        }
        HardwarePartitionGraph graph=new HardwarePartitionGraph(owners,seq,successors,names,nets,Collections.emptyMap(),resources,logic,0,0);
        CombinationalPathAnalyzer analyzer=new CombinationalPathAnalyzer(owners,seq,successors);
        HardwareMappingRefiner paired=new HardwareMappingRefiner(graph,PartitionTopology.complete(k),k,0.5,fixed,8,0.5,null);
        // Captured from the frozen pre-exchange implementation. Preserve this
        // counterexample as fixture data without maintaining a mirror solver.
        int[] baseline={-1,0,2,2,2,1,1,1,0,1};
        int[] actual=paired.refine(initial,3,Files.createDirectory(directory.resolve("paired")));
        CombinationalPathAnalyzer.Analysis base=analyzer.analyze(baseline),bad=analyzer.analyze(observedBadInterleaved),nativeAudit=analyzer.analyze(initial),result=analyzer.analyze(actual);
        Assertions.assertEquals(1,base.maxCrossings);Assertions.assertEquals(0,base.excessCrossings);
        Assertions.assertEquals(0,base.transitCount);Assertions.assertEquals(5,paired.transfers(baseline));
        Assertions.assertEquals(2,bad.maxCrossings);
        Assertions.assertTrue(bad.maxCrossings<=nativeAudit.maxCrossings);
        Assertions.assertTrue(bad.excessCrossings<=nativeAudit.excessCrossings);
        Assertions.assertTrue(bad.transitCount<=nativeAudit.transitCount);
        Assertions.assertTrue(result.maxCrossings<=base.maxCrossings);
        Assertions.assertTrue(result.excessCrossings<=base.excessCrossings);
        Assertions.assertTrue(result.transitCount<=base.transitCount);
        paired.validate(actual);
        Assertions.assertTrue(Files.isRegularFile(directory.resolve("paired/swap_refinement_passes.tsv")));
    }
}
