/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;

/** Directed primitive connectivity plus movable hierarchy vertices. No netlist rewriting.
 * Primitive arcs conservatively connect non-clock inputs to outputs, except known
 * LUTRAM/SRL writes terminate at separate capture nodes. Read addresses retain
 * combinational arcs. DSP outputs stop paths only for proven PREG configurations;
 * latches and unknown macros remain conservative combinational dependencies.
 */
public final class HardwarePartitionGraph {
    public static final class Net {
        public final int driver;
        public final int[] sinks;
        public Net(int driver, int[] sinks) {
            this.driver = driver;
            this.sinks = Arrays.stream(sinks).distinct().sorted().toArray();
        }
    }

    public final int[] ownerVertex;
    public final boolean[] sequential;
    public final int[][] successors;
    public final String[] primitiveNames;
    public final List<Net> nets;
    public final Map<EDIFHierNet, Set<EDIFHierCellInst>> hyperedges;
    public final long[][] resources;
    public final long[] realLogic;
    public final int opaquePrimitives;
    public final int omittedTopInputNets;

    HardwarePartitionGraph(int[] ownerVertex, boolean[] sequential, int[][] successors,
            String[] names, List<Net> nets, Map<EDIFHierNet, Set<EDIFHierCellInst>> hyperedges,
            long[][] resources, long[] realLogic, int opaque, int omittedTopInputNets) {
        this.ownerVertex = ownerVertex;
        this.sequential = sequential;
        this.successors = successors;
        this.primitiveNames = names;
        this.nets = nets;
        this.hyperedges = hyperedges;
        this.resources = resources;
        this.realLogic = realLogic;
        this.opaquePrimitives = opaque;
        this.omittedTopInputNets = omittedTopInputNets;
    }

    private static boolean trustedConstant(EDIFHierCellInst leaf) {
        String type = leaf.getCellType().getName();
        return ("GND".equals(type) || "VCC".equals(type))
                && leaf.getCellType().getLibrary().isHDIPrimitivesLibrary();
    }

    public static HardwarePartitionGraph build(EDIFNetlist netlist,
            Map<EDIFHierCellInst, Integer> vertices) {
        List<EDIFHierCellInst> leaves = netlist.getAllLeafHierCellInstances(true);
        Map<EDIFHierCellInst, Integer> primitiveIndex = new HashMap<>();
        EDIFHierCellInst[] byVertex = new EDIFHierCellInst[vertices.size() + 1];
        for (Map.Entry<EDIFHierCellInst, Integer> e : vertices.entrySet()) {
            byVertex[e.getValue()] = e.getKey();
        }
        long[][] resources = new long[vertices.size() + 1][6];
        long[] realLogic = new long[vertices.size() + 1];
        LogicalResourceCounts counter = new LogicalResourceCounts();
        for (int v = 1; v < byVertex.length; v++) {
            LogicalResourceCounts.Counts c = counter.count(byVertex[v].getCellType());
            resources[v] = new long[] {c.logicLuts, c.flipFlops + c.latches,
                    c.bram18Equivalent, c.dsp, c.uram,
                    c.lutRamPrimitives + c.shiftRegisterPrimitives};
            realLogic[v] = c.primitiveCount();
        }
        int n = leaves.size();
        int captureCount = 0;
        int[] writeCapture = new int[n];
        Arrays.fill(writeCapture, -1);
        int[] resetCapture = new int[n];
        Arrays.fill(resetCapture, -1);
        // Names such as GND/<const0> are legal user net names. Only actual
        // output drivers of trusted constant primitives establish constancy.
        Set<EDIFHierNet> trustedConstantNets = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (PrimitiveTimingPolicy.hasWriteCapture(leaves.get(i))) writeCapture[i] = n + captureCount++;
            if (PrimitiveTimingPolicy.hasResetCapture(leaves.get(i))) resetCapture[i] = n + captureCount++;
            if (trustedConstant(leaves.get(i))) {
                for (EDIFHierPortInst pin : leaves.get(i).getHierPortInsts()) {
                    if (!pin.isOutput() || pin.getHierarchicalNet() == null) continue;
                    EDIFHierNet net = netlist.getParentNet(pin.getHierarchicalNet());
                    trustedConstantNets.add(net == null ? pin.getHierarchicalNet() : net);
                }
            }
        }
        int nodeCount = n + captureCount;
        int[] owners = new int[nodeCount];
        boolean[] seq = new boolean[nodeCount];
        String[] names = new String[nodeCount];
        List<List<Integer>> succ = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) succ.add(new ArrayList<>());
        int opaque = 0;
        for (int i = 0; i < n; i++) {
            EDIFHierCellInst leaf = leaves.get(i);
            primitiveIndex.put(leaf, i);
            names[i] = leaf.toString();
            EDIFHierCellInst ancestor = leaf;
            while (ancestor != null && !vertices.containsKey(ancestor)) {
                ancestor = ancestor.getParent();
            }
            if (ancestor == null) {
                throw new IllegalArgumentException("Uncovered primitive: " + leaf);
            }
            owners[i] = vertices.get(ancestor);
            String type = leaf.getCellType().getName();
            boolean trustedPrimitive = leaf.getCellType().getLibrary().isHDIPrimitivesLibrary();
            if (trustedPrimitive && (trustedConstant(leaf) || type.startsWith("BUF")
                    || type.startsWith("IBUF") || type.startsWith("OBUF")
                    || type.startsWith("IOBUF") || type.startsWith("MMCME") || type.startsWith("PLLE"))) {
                realLogic[owners[i]]--;
            }
            seq[i] = trustedPrimitive && (LogicalResourceCounts.isFlipFlop(type)
                    || type.startsWith("RAMB") || type.startsWith("URAM")
                    || PrimitiveTimingPolicy.registeredDspOutputs(leaf));
            if (writeCapture[i] >= 0) {
                int capture = writeCapture[i];
                owners[capture] = owners[i];
                names[capture] = names[i] + "@write_capture";
                seq[capture] = true;
            }
            if (resetCapture[i] >= 0) {
                int capture = resetCapture[i];
                owners[capture] = owners[i]; names[capture] = names[i] + "@sync_control_capture"; seq[capture] = true;
            }
            if (PrimitiveTimingPolicy.unresolvedResetControls(leaf)) {
                // Invalid/unsupported present control attributes do not gain a
                // guessed register barrier; existing opaque coverage reports it.
                opaque++; seq[i] = false;
            }
            for (EDIFHierPortInst pin : leaf.getHierPortInsts()) {
                if (!pin.isInput() && !pin.isOutput()) {
                    throw new IllegalArgumentException("Inout primitive requires an explicit model: " + pin);
                }
            }
            // Cascades and unknown dynamic memory controls can bypass storage.
            // Known captured input pins retain separate capture endpoints.
            if (type.startsWith("RAMB") || type.startsWith("URAM")) {
                for (EDIFHierPortInst pin : leaf.getHierPortInsts()) {
                    String pinName = pin.getPortInst().getPort().getBusName();
                    if (pin.isInput() && !PrimitiveTimingPolicy.clockPin(leaf.getInst(), pinName)
                            && !LogicalResourceCounts.isResetPin(type, pinName)
                            && (PrimitiveTimingPolicy.inputRoles(leaf, pinName)
                                    & PrimitiveTimingPolicy.COMBINATIONAL) != 0
                            && pin.getHierarchicalNet() != null) {
                        EDIFHierNet net = netlist.getParentNet(pin.getHierarchicalNet());
                        if (net == null) { net = pin.getHierarchicalNet(); }
                        if (!trustedConstantNets.contains(net)) { seq[i] = false; }
                    }
                }
            }
            if (!leaf.getCellType().getLibrary().isHDIPrimitivesLibrary()) {
                opaque++;
            }
        }
        List<Net> nets = new ArrayList<>();
        Map<EDIFHierNet, Set<EDIFHierCellInst>> hyperedges = new LinkedHashMap<>();
        Set<EDIFHierNet> visited = new HashSet<>();
        int omittedTopInputNets = 0;
        for (int i = 0; i < n; i++) {
            for (EDIFHierPortInst port : leaves.get(i).getHierPortInsts()) {
                EDIFHierNet alias = port.getHierarchicalNet();
                if (alias == null) {
                    continue;
                }
                EDIFHierNet parent = netlist.getParentNet(alias);
                EDIFHierNet canonical = parent == null ? alias : parent;
                if (!visited.add(canonical)) {
                    continue;
                }
                List<EDIFHierPortInst> pins = canonical.getLeafHierPortInsts();
                int driver = -1;
                boolean constant = false;
                List<Integer> sinks = new ArrayList<>();
                for (EDIFHierPortInst pin : pins) {
                    Integer node = primitiveIndex.get(pin.getFullHierarchicalInst());
                    if (node == null) {
                        throw new IllegalArgumentException("Unresolved primitive pin: " + pin);
                    }
                    String type = pin.getCellType().getName();
                    if (pin.isOutput()) {
                        if (driver != -1) {
                            throw new IllegalArgumentException("Multiple-driver net: " + canonical);
                        }
                        driver = node;
                        constant = trustedConstant(pin.getFullHierarchicalInst());
                    } else if (pin.isInput()) {
                        String pinName = pin.getPortInst().getPort().getBusName();
                        if (LogicalResourceCounts.isResetPin(type, pinName)) {
                            int roles = PrimitiveTimingPolicy.resetRoles(pin.getPortInst().getCellInst(), pinName);
                            if ((roles & PrimitiveTimingPolicy.COMBINATIONAL) != 0) sinks.add(node);
                            if ((roles & PrimitiveTimingPolicy.CAPTURE) != 0) sinks.add(resetCapture[node] < 0 ? node : resetCapture[node]);
                        } else if (!PrimitiveTimingPolicy.clockPin(pin.getPortInst().getCellInst(), pinName)) {
                            int roles = writeCapture[node] < 0 ? PrimitiveTimingPolicy.COMBINATIONAL
                                    : PrimitiveTimingPolicy.inputRoles(pin.getFullHierarchicalInst(), pinName);
                            if ((roles & PrimitiveTimingPolicy.COMBINATIONAL) != 0) sinks.add(node);
                            if ((roles & PrimitiveTimingPolicy.CAPTURE) != 0) sinks.add(writeCapture[node]);
                        }
                    } else {
                        throw new IllegalArgumentException("Inout primitive requires an explicit model: " + pin);
                    }
                }
                if (driver == -1) {
                    omittedTopInputNets++;
                    continue; // Primary inputs have no FPGA home unless the platform specifies one.
                }
                if (constant || sinks.isEmpty()) {
                    continue;
                }
                TreeSet<Integer> sinkVertices = new TreeSet<>();
                Set<EDIFHierCellInst> incident = new HashSet<>();
                incident.add(byVertex[owners[driver]]);
                for (int sink : sinks) {
                    succ.get(driver).add(sink);
                    sinkVertices.add(owners[sink]);
                    incident.add(byVertex[owners[sink]]);
                }
                if (incident.size() > 1) {
                    hyperedges.put(canonical, incident);
                    nets.add(new Net(owners[driver], sinkVertices.stream().mapToInt(x -> x).toArray()));
                }
            }
        }
        int[][] successors = new int[nodeCount][];
        for (int i = 0; i < nodeCount; i++) {
            successors[i] = succ.get(i).stream().mapToInt(x -> x).distinct().sorted().toArray();
        }
        return new HardwarePartitionGraph(owners, seq, successors, names, nets, hyperedges,
                resources, realLogic, opaque, omittedTopInputNets);
    }
}
