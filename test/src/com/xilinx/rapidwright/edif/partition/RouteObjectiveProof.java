package com.xilinx.rapidwright.edif.partition;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class RouteObjectiveProof {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static HardwarePartitionGraph fixture() {
        int[] owners = {1,2,3,4,5,6,7,8};
        boolean[] seq = {true,true,true,true,true,true,true,true};
        int[][] successors = {{},{},{},{},{5},{},{7},{}};
        String[] names = {"a","b","c","d","launch","capture","other_launch","other_capture"};
        long[][] resources = new long[9][6]; long[] logic = new long[9];
        for (int v = 1; v <= 8; v++) { resources[v][0] = 1; logic[v] = 1; }
        List<HardwarePartitionGraph.Net> nets = new ArrayList<>();
        nets.add(new HardwarePartitionGraph.Net(5,new int[]{6}));
        nets.add(new HardwarePartitionGraph.Net(7,new int[]{8}));
        return new HardwarePartitionGraph(owners,seq,successors,names,nets,Collections.emptyMap(),resources,logic,0,0);
    }
    private static HardwareMappingRefiner refiner(HardwarePartitionGraph graph, PartitionTopology topology) throws Exception {
        int[] fixed = {-1,0,1,2,3,0,-1,0,-1};
        return new HardwareMappingRefiner(graph,topology,4,1.0,fixed,8,1.0,null);
    }
    private static double structuralCost(HardwareMappingRefiner refiner, int[] path, int[] assignment) throws Exception {
        Method method = HardwareMappingRefiner.class.getDeclaredMethod("pathCost",int[].class,int[].class);
        method.setAccessible(true); return (Double)method.invoke(refiner,path,assignment);
    }
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]); Files.createDirectories(directory);
        Path file=directory.resolve("chain4.graph");
        Files.writeString(file,"4 3 1\n2 1\n1 1 3 1\n2 1 4 1\n3 1\n");
        Path completeFile=directory.resolve("complete4.graph");
        Files.writeString(completeFile,"4 6 1\n2 1 3 1 4 1\n1 1 3 1 4 1\n1 1 2 1 4 1\n1 1 2 1 3 1\n");
        PartitionTopology sparse=PartitionTopology.read(file,4), complete=PartitionTopology.read(completeFile,4);
        HardwarePartitionGraph graph=fixture();
        int[] far={-1,0,1,2,3,0,3,0,0}, nearer={-1,0,1,2,3,0,1,0,2};
        HardwareMappingRefiner refiner=refiner(graph,sparse);
        // Same worst logical crossing=1; far costs1transfer, nearer costs2.
        // Both have aggregate topology3, but worst link paths3 vs2.
        check(refiner.selectCandidate(Arrays.asList(far,nearer))==nearer,"Sparse H outranks smaller transfer count under declared budget");
        check(structuralCost(refiner,new int[]{5,6},far)==32,"Sparse structural H severity");
        check(structuralCost(refiner,new int[]{5,6},nearer)==0,"Direct structural crossing has no excess penalty");
        CombinationalPathAnalyzer analyzer=new CombinationalPathAnalyzer(graph.ownerVertex,graph.sequential,graph.successors);
        int[][] logicalWitness=analyzer.collectWorstPaths(nearer,1);
        int[][] physicalWitness=analyzer.collectWorstPaths(nearer,1,100,sparse.minimumHopMatrix());
        check(logicalWitness[0][logicalWitness[0].length-1]==5,"Logical sampler stable endpoint tie");
        check(physicalWitness[0][physicalWitness[0].length-1]==7,"Physical sampler selects hidden longer route");
        Files.createDirectories(directory.resolve("refined"));
        int[] refined=refiner.refine(far,3,directory.resolve("refined"));
        check(analyzer.analyze(refined,sparse.minimumHopMatrix()).maxMinimumLinkHops<=3,"Full refinement H guard");
        List<String> report=Files.readAllLines(directory.resolve("refined/hardware_quality.txt"));
        check(report.contains("minimum_link_hop_analysis_available=true"),"Explicit report availability");

        String inputSha="a".repeat(64);
        Path timing=directory.resolve("timing.tsv");
        Files.writeString(timing,"#input_sha256="+inputSha+"\npath_id\tslack_ns\trequirement_ns\tlaunch_clock\tcapture_clock\tcells_json\np0\t8\t10\tclk\tclk\t[\"top/launch\",\"top/capture\"]\n");
        String[] lookup={"","a","b","c","d","launch","capture","other_launch","other_capture"};
        TimingPathModel model=TimingPathModel.read(timing,inputSha,lookup,"top");
        check(model.pathCost(0,far,null)==1,"Legacy sampled cost preserved");
        check(model.pathCost(0,far,null,sparse.minimumHopMatrix())==7,"Sampled severity uses physical H");
        check(model.pathCost(0,nearer,null,sparse.minimumHopMatrix())==1,"Sampled direct severity");
        check(model.evaluate(far,2.0,sparse.minimumHopMatrix()).modeledWorstSlackNs==6.0,"Explicit ns model stays per logical crossing");
        HardwareMappingRefiner completeRefiner=refiner(graph,complete);
        check(completeRefiner.selectCandidate(Arrays.asList(far,nearer))==far,"Complete J4 retains old transfer tie break");
        check(!PartitionTopology.complete(4).hasDeclaredPhysicalAdjacency(),"Legacy complete fallback is explicitly assumed");
        check(complete.hasDeclaredPhysicalAdjacency(),"Complete J4 graph actually declared");
        check(sparse.hasDeclaredPhysicalAdjacency(),"Read physical graph is explicitly declared");
        for(int code=0;code<256;code++) {
            int[] assignment=far.clone(); int value=code;
            for(int v=5;v<=8;v++){assignment[v]=value%4;value/=4;}
            long changes=0,reentries=0; int last=-1; boolean[] visited=new boolean[4];
            for(int v=5;v<=8;v++) if(assignment[v]!=last){ if(last>=0) changes++; if(visited[assignment[v]]) reentries++; visited[assignment[v]]=true; last=assignment[v]; }
            double expected=8*(Math.pow(Math.max(0,changes-1),2)+reentries);
            check(structuralCost(completeRefiner,new int[]{5,6,7,8},assignment)==expected,"Complete structural-cost equivalence with reentries");
            check(model.pathCost(0,assignment,null)==model.pathCost(0,assignment,null,complete.minimumHopMatrix()),"Complete sampled cost equivalence");
            check(model.pathCost(0,assignment,2.0)==model.pathCost(0,assignment,2.0,complete.minimumHopMatrix()),"Complete explicit-delay cost equivalence");
            check(Arrays.deepEquals(analyzer.collectWorstPaths(assignment,4096),analyzer.collectWorstPaths(assignment,4096,1_000_000,complete.minimumHopMatrix())),"Complete witness equivalence");
        }
        for(int[][] invalid:new int[][][] {{{0,2},{2,0}},{{0,4,1},{4,0,1},{1,1,0}}}) {
            boolean rejected=false;
            try { analyzer.analyze(far,invalid); } catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"Non-BFS matrices rejected");
        }
        System.out.println("PASS objective_fixtures=sparse_seed_selection,structural_cost,physical_witness,full_guard,sampled_STA,logical_ns_preserved,complete_J4_equivalence,invalid_matrices equivalence_assignments=256");
    }
}
