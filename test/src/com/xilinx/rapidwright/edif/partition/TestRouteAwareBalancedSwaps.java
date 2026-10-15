package com.xilinx.rapidwright.edif.partition;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sparse-route proofs; capacity estimates are never native-area claims. */
public class TestRouteAwareBalancedSwaps {
    @TempDir Path directory;
    private static Method method(String name, Class<?>... types) throws Exception {
        Method m = HardwareMappingRefiner.class.getDeclaredMethod(name, types); m.setAccessible(true); return m;
    }
    private static Field field(String name) throws Exception {
        Field f = HardwareMappingRefiner.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private PartitionTopology topology() throws Exception {
        Path path = directory.resolve("chain.graph");
        // Weighted cost is deliberately different from adjacency hop count.
        Files.write(path, Arrays.asList("% rapidwright_cost_scale=24", "4 3 1", "2 24", "1 24 3 25", "2 25 4 48", "3 48"));
        return PartitionTopology.read(path, 4);
    }
    private static long[][] resources(int n) {
        long[][] result = new long[n + 1][6]; for (int v = 1; v <= n; v++) result[v][0] = 1; return result;
    }
    private static int[] unfixed(int n) { int[] result = new int[n+1]; Arrays.fill(result,-1); return result; }
    private static String[] names(int n) { String[] result = new String[n]; for(int p=0;p<n;p++)result[p]="p"+(p+1);return result; }
    private static long[] logic(int n) { long[] result = new long[n+1]; Arrays.fill(result,1); result[0]=0; return result; }
    private static long report(Path out, String key) throws Exception {
        for(String line:Files.readAllLines(out.resolve("hardware_quality.txt"))) if(line.startsWith(key+"="))return Long.parseLong(line.substring(key.length()+1));
        throw new AssertionError("Missing "+key);
    }

    @Test void declaredCompleteJ4KeepsLegacyPairedSearchAssignmentAndCostsExactly() throws Exception {
        Path file=directory.resolve("j4.graph");
        Files.write(file,Arrays.asList("4 6 1","2 1 3 1 4 1","1 1 3 1 4 1","1 1 2 1 4 1","1 1 2 1 3 1"));
        PartitionTopology declared=PartitionTopology.read(file,4),legacy=PartitionTopology.complete(4);
        int n=8;int[] owners=new int[n];boolean[] seq=new boolean[n];seq[0]=seq[n-1]=true;
        int[][] successors=new int[n][];List<HardwarePartitionGraph.Net> nets=new ArrayList<>();
        for(int u=0;u<n;u++) {
            owners[u]=u+1;successors[u]=u+1<n?new int[]{u+1}:new int[0];
            if(u+1<n)nets.add(new HardwarePartitionGraph.Net(u+1,new int[]{u+2}));
        }
        HardwarePartitionGraph graph=new HardwarePartitionGraph(owners,seq,successors,names(n),nets,
                Collections.emptyMap(),resources(n),logic(n),0,0);
        int[] fixed=unfixed(n);fixed[1]=0;fixed[n]=3;
        int[] initial={-1,0,1,2,3,0,1,2,3},weights={0,1,1,1,1,1,1,1,1};
        HardwareMappingRefiner declaredRefiner=new HardwareMappingRefiner(graph,declared,4,0,fixed,8,0,null);
        HardwareMappingRefiner legacyRefiner=new HardwareMappingRefiner(graph,legacy,4,0,fixed,8,0,null);
        declaredRefiner.configureBalanceWeights(weights,new long[]{2,2,2,2});
        legacyRefiner.configureBalanceWeights(weights,new long[]{2,2,2,2});
        Path declaredOutput=Files.createDirectory(directory.resolve("j4_declared")),legacyOutput=Files.createDirectory(directory.resolve("j4_legacy"));
        int[] declaredResult=declaredRefiner.refine(initial,3,declaredOutput);
        int[] legacyResult=legacyRefiner.refine(initial,3,legacyOutput);
        Assertions.assertArrayEquals(legacyResult,declaredResult);
        for(String key:Arrays.asList("proposed_swaps","swap_cost_probes","after_transfers","after_max_register_free_crossings","after_endpoint_excess_crossings","after_transit_primitives")) {
            Assertions.assertEquals(report(legacyOutput,key),report(declaredOutput,key),key);
        }
        Assertions.assertEquals(report(declaredOutput,"after_max_register_free_crossings"),report(declaredOutput,"after_max_minimum_link_hops"));
        Assertions.assertEquals(-1,report(legacyOutput,"after_max_minimum_link_hops"));
    }

    @Test void profitableSwapCannotIncreaseFullMinimumLinkMaximumDespiteUnchangedLogicalMetrics() throws Exception {
        // Every primitive is a capture, so these are three separate register
        // paths, not a fabricated combinational concatenation through p2.
        int[] owners={1,2,3,4,5,6}; boolean[] seq={true,true,true,true,true,true};
        int[][] successors={{1},{3},{4},{},{},{}};
        List<HardwarePartitionGraph.Net> nets = new ArrayList<>();
        nets.add(new HardwarePartitionGraph.Net(1,new int[]{2}));
        for(int duplicate=0;duplicate<5;duplicate++) {
            nets.add(new HardwarePartitionGraph.Net(2,new int[]{4}));
            nets.add(new HardwarePartitionGraph.Net(3,new int[]{5}));
        }
        HardwarePartitionGraph graph=new HardwarePartitionGraph(owners,seq,successors,names(6),nets,
                Collections.emptyMap(),resources(6),logic(6),0,0);
        PartitionTopology topology=topology();
        int[] initial={-1,0,1,3,3,1,2}, swapped={-1,0,3,1,3,1,2};
        HardwareMappingRefiner refiner=new HardwareMappingRefiner(graph,topology,4,2,
                new int[]{-1,0,-1,-1,3,1,2},8,0,null);
        refiner.configureBalanceWeights(new int[]{0,1,1,1,1,1,1},new long[]{1,2,1,2});
        CombinationalPathAnalyzer analyzer=new CombinationalPathAnalyzer(owners,seq,successors);
        CombinationalPathAnalyzer.Analysis before=analyzer.analyze(initial,topology.minimumHopMatrix());
        CombinationalPathAnalyzer.Analysis after=analyzer.analyze(swapped,topology.minimumHopMatrix());
        Assertions.assertEquals(2,before.maxMinimumLinkHops); Assertions.assertEquals(3,after.maxMinimumLinkHops);
        Assertions.assertEquals(before.maxCrossings,after.maxCrossings);
        Assertions.assertEquals(before.excessCrossings,after.excessCrossings);
        Assertions.assertEquals(before.transitCount,after.transitCount);
        Assertions.assertTrue(refiner.transfers(swapped)<refiner.transfers(initial));
        Assertions.assertTrue(refiner.topologyCost(swapped)<refiner.topologyCost(initial));
        Path output=Files.createDirectory(directory.resolve("guard"));
        Assertions.assertArrayEquals(initial,refiner.refine(initial,2,output));
        Assertions.assertEquals(1,report(output,"proposed_swaps"));
        Assertions.assertEquals(2,report(output,"after_max_minimum_link_hops"));
        String rejected=Files.readAllLines(output.resolve("swap_refinement_passes.tsv")).get(1);
        Assertions.assertTrue(rejected.endsWith("\tfalse\tfalse"),rejected);
        refiner.validate(initial);
    }

    private TimingPathModel timing(int n) throws Exception {
        Path file=directory.resolve("timing.tsv"); String sha=String.join("",Collections.nCopies(64,"a"));
        Files.write(file,Arrays.asList("#input_sha256="+sha,
                "path_id\tslack_ns\trequirement_ns\tlaunch_clock\tcapture_clock\tcells_json",
                "reenter\t1\t10\tL\tC\t[\"p1\",\"p4\",\"p2\",\"p4\",\"p7\",\"p9\"]",
                "other\t3\t10\tL\tC\t[\"p8\",\"p4\",\"p2\",\"p6\"]"));
        String[] frontier=new String[n+1];for(int v=1;v<=n;v++)frontier[v]="p"+v;
        return TimingPathModel.read(file,sha,frontier,"top");
    }
    private static double wholeObjective(HardwarePartitionGraph graph, PartitionTopology topology,
            int[][] paths,int[] assignment,TimingPathModel timing,double snake,double timingWeight,Double delay) {
        double result=0;int[][] hops=topology.minimumHopMatrix();
        for(HardwarePartitionGraph.Net net:graph.nets) {
            Set<Integer> destinations=new HashSet<>();int source=assignment[net.driver];
            for(int sink:net.sinks)if(assignment[sink]!=source)destinations.add(assignment[sink]);
            for(int destination:destinations)result+=1+topology.distance(source,destination);
        }
        for(int[] path:paths) {
            long minimumLinks=0;int previous=-1,reentries=0;Set<Integer> visited=new HashSet<>();
            for(int owner:path) {
                int part=assignment[owner];if(part==previous)continue;
                if(previous>=0)minimumLinks+=hops[previous][part];
                if(!visited.add(part))reentries++;previous=part;
            }
            long excess=Math.max(0,minimumLinks-1);result+=snake*(excess*(double)excess+reentries);
        }
        return result+timingWeight*timing.evaluate(assignment,delay,hops).timingCost;
    }

    @Test void sparseUnionDeltaUsesPhysicalHopsAndLogicalExplicitDelayAndRestoresEveryCache() throws Exception {
        int n=9;PartitionTopology topology=topology();Random random=new Random(378321L);
        int[] owners=new int[n];boolean[] seq=new boolean[n];seq[0]=seq[n-1]=true;
        int[][] successors=new int[n][];List<HardwarePartitionGraph.Net> nets=new ArrayList<>();
        for(int u=0;u<n;u++) {
            owners[u]=u+1;successors[u]=u+1<n?new int[]{u+1}:new int[0];
            if(u+1<n)nets.add(new HardwarePartitionGraph.Net(u+1,new int[]{u+2}));
        }
        nets.add(new HardwarePartitionGraph.Net(2,new int[]{2,3,3,7,9}));
        for(int edge=0;edge<20;edge++) {
            int[] sinks=new int[1+random.nextInt(n)];for(int p=0;p<sinks.length;p++)sinks[p]=1+random.nextInt(n);
            nets.add(new HardwarePartitionGraph.Net(1+random.nextInt(n),sinks));
        }
        HardwarePartitionGraph graph=new HardwarePartitionGraph(owners,seq,successors,names(n),nets,
                Collections.emptyMap(),resources(n),logic(n),0,0);
        TimingPathModel timing=timing(n);HardwareMappingRefiner paired=new HardwareMappingRefiner(graph,topology,4,2,unfixed(n),2.5,0,null);
        paired.configureTimingPaths(timing,3.25,0.7);
        CombinationalPathAnalyzer analyzer=new CombinationalPathAnalyzer(owners,seq,successors);
        int tested=0;
        for(int trial=0;trial<24;trial++) {
            int[] assignment=new int[n+1];assignment[0]=-1;for(int v=1;v<=n;v++)assignment[v]=random.nextInt(4);
            method("initializeNetCache",int[].class).invoke(paired,(Object)assignment);
            method("refreshPaths",CombinationalPathAnalyzer.class,int[].class).invoke(paired,analyzer,assignment);
            int[][] paths=(int[][])field("paths").get(paired);
            for(int test=0;test<25;test++) {
                int a=1+random.nextInt(n),b=1+random.nextInt(n);if(a==b||assignment[a]==assignment[b])continue;
                int[] saved=assignment.clone();double[] costs=((double[])field("cachedNetCosts").get(paired)).clone();
                int[] transfers=((int[])field("cachedTransfers").get(paired)).clone();
                int[][] counts=(int[][])field("sinkCounts").get(paired);int[][] countCopy=new int[counts.length][];
                for(int e=0;e<counts.length;e++)countCopy[e]=counts[e].clone();
                double before=wholeObjective(graph,topology,paths,assignment,timing,2.5,3.25,0.7);
                Object delta=method("probeSwap",int.class,int.class,int[].class).invoke(paired,a,b,assignment);
                Assertions.assertArrayEquals(saved,assignment);Assertions.assertArrayEquals(costs,(double[])field("cachedNetCosts").get(paired),1e-8);
                Assertions.assertArrayEquals(transfers,(int[])field("cachedTransfers").get(paired));
                for(int e=0;e<counts.length;e++)Assertions.assertArrayEquals(countCopy[e],counts[e]);
                int[] swapped=saved.clone();swapped[a]=saved[b];swapped[b]=saved[a];
                Field cost=delta.getClass().getDeclaredField("cost"),transfer=delta.getClass().getDeclaredField("transfers"),distance=delta.getClass().getDeclaredField("topology");
                cost.setAccessible(true);transfer.setAccessible(true);distance.setAccessible(true);
                Assertions.assertEquals(wholeObjective(graph,topology,paths,swapped,timing,2.5,3.25,0.7)-before,cost.getDouble(delta),1e-7);
                Assertions.assertEquals(paired.transfers(swapped)-paired.transfers(saved),transfer.getLong(delta));
                Assertions.assertEquals(paired.topologyCost(swapped)-paired.topologyCost(saved),distance.getDouble(delta),1e-8);tested++;
            }
        }
        Assertions.assertTrue(tested>300);
        int[] oneCut={-1,0,3,0,0,0,0,0,0,0};
        Assertions.assertEquals(10.0,method("pathCost",int[].class,int[].class).invoke(paired,new int[]{1,2},oneCut));
        TimingPathModel onePath;
        Path file=directory.resolve("logical_delay.tsv");String sha=String.join("",Collections.nCopies(64,"a"));
        Files.write(file,Arrays.asList("#input_sha256="+sha,"path_id\tslack_ns\trequirement_ns\tlaunch_clock\tcapture_clock\tcells_json","p\t1\t10\tL\tC\t[\"p1\",\"p2\"]"));
        String[] frontier=new String[n+1];for(int v=1;v<=n;v++)frontier[v]="p"+v;
        onePath=TimingPathModel.read(file,sha,frontier,"top");
        Assertions.assertEquals(0.3,onePath.evaluate(oneCut,0.7,topology.minimumHopMatrix()).modeledWorstSlackNs,1e-12);
    }
}
