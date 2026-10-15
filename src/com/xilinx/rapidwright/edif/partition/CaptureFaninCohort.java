package com.xilinx.rapidwright.edif.partition;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** Held deterministic whole-frontier fanin proposals. Never rewrites a netlist. */
public final class CaptureFaninCohort {
    public static final class Limits {
        public final int maxNodes, maxVertices, maxEdges, maxCaptures, maxTargets, maxProposals;
        public final long maxWork;
        public Limits(int nodes, int vertices, int edges, int captures, int targets, int proposals, long work) {
            if (nodes < 1 || nodes > 131072 || vertices < 1 || vertices > 16384 || edges < 1 || edges > 2097152
                    || captures < 1 || captures > 16 || targets < 1 || targets > 32 || proposals < 1 || proposals > 64
                    || work < 1 || work > 4_000_000_000L) throw new IllegalArgumentException("Cohort limits exceed bounded envelope");
            maxNodes=nodes;maxVertices=vertices;maxEdges=edges;maxCaptures=captures;maxTargets=targets;maxProposals=proposals;maxWork=work;
        }
        public static Limits defaults() { return new Limits(65536,8192,1048576,4,8,16,512_000_000L); }
    }
    static final class Cone {
        final int endpoint, captureOwner;
        final int[] owners, boundaryHomes;
        final int nodes, edges;
        final String skip;
        Cone(int endpoint,int owner,TreeSet<Integer> owners,TreeSet<Integer> homes,int nodes,int edges,String skip) {
            this.endpoint=endpoint;captureOwner=owner;this.owners=owners.stream().mapToInt(Integer::intValue).toArray();
            boundaryHomes=homes.stream().mapToInt(Integer::intValue).toArray();this.nodes=nodes;this.edges=edges;this.skip=skip;
        }
    }
    static Cone collect(HardwarePartitionGraph graph,CombinationalPathAnalyzer analyzer,int endpoint,
            int[] assignment,boolean[] protectedOwners,Limits limits,long remainingWork) {
        TreeSet<Integer> owners=new TreeSet<>(),homes=new TreeSet<>();Set<Integer> visited=new HashSet<>();
        int captureOwner=graph.ownerVertex[endpoint],edges=0;
        if (!graph.sequential[endpoint] || protectedOwners[captureOwner]) return new Cone(endpoint,captureOwner,owners,homes,0,0,"protected_or_non_capture_target");
        ArrayDeque<Integer> pending=new ArrayDeque<>();pending.add(endpoint);
        while(!pending.isEmpty()) {
            int node=pending.removeFirst();if(visited.contains(node))continue;
            if(visited.size()+edges>=remainingWork)return new Cone(endpoint,captureOwner,owners,homes,visited.size(),edges,"work_limit");
            visited.add(node);
            if(visited.size()>limits.maxNodes)return new Cone(endpoint,captureOwner,owners,homes,visited.size(),edges,"node_limit");
            int owner=graph.ownerVertex[node];
            if(node!=endpoint&&(graph.sequential[node]||protectedOwners[owner])) {homes.add(assignment[owner]);continue;}
            if(node!=endpoint)owners.add(owner);
            if(owners.size()>limits.maxVertices)return new Cone(endpoint,captureOwner,owners,homes,visited.size(),edges,"vertex_limit");
            int remaining=(int)Math.min(limits.maxEdges-edges,remainingWork-visited.size()-edges);
            int[] predecessors=analyzer.cohortPredecessors(node,remaining);
            if(predecessors==null)return new Cone(endpoint,captureOwner,owners,homes,visited.size(),edges,remaining<limits.maxEdges-edges?"work_limit":"edge_limit");
            edges+=predecessors.length;
            for(int previous:predecessors) {
                if(!visited.contains(previous))pending.addLast(previous);
                if(pending.size()>limits.maxEdges)return new Cone(endpoint,captureOwner,owners,homes,visited.size(),edges,"pending_limit");
            }
        }
        return new Cone(endpoint,captureOwner,owners,homes,visited.size(),edges,owners.isEmpty()?"empty_fanin":null);
    }
    private CaptureFaninCohort() { }
}
