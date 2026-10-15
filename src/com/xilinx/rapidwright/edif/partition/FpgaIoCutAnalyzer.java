/* Copyright (c) 2026, Advanced Micro Devices, Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFCellInst;

/**
 * Counts unique logical nets crossing each directed partition pair. Hierarchy
 * aliases are traversed once and only primitive/top-port endpoints determine
 * direction. These counts are logical signal requirements before serialization
 * and routing; they are not Vivado report_io/package pin utilization.
 */
public final class FpgaIoCutAnalyzer {
    private FpgaIoCutAnalyzer() { }

    /** An actual leaf primitive pin or a top-level design port. */
    public static final class Endpoint {
        public final String path, pin, type;
        public final boolean driver, load, topPort;
        public final boolean trustedPrimitive;
        final EDIFCellInst instance;

        private Endpoint(String path, String pin, String type, EDIFDirection direction, boolean topPort, EDIFCellInst instance) {
            this.path = path; this.pin = pin; this.type = type; this.topPort = topPort;
            this.instance = instance; trustedPrimitive = PrimitiveTimingPolicy.trusted(instance);
            driver = direction == EDIFDirection.INOUT
                    || direction == (topPort ? EDIFDirection.INPUT : EDIFDirection.OUTPUT);
            load = direction == EDIFDirection.INOUT
                    || direction == (topPort ? EDIFDirection.OUTPUT : EDIFDirection.INPUT);
        }

        public String name() { return topPort ? "PORT:" + pin : path + "/" + pin; }
    }

    /** Complete connectivity of one electrical net, including hierarchy aliases. */
    public static final class NetEndpoints {
        public final Set<EDIFHierNet> aliases = new HashSet<>();
        public final List<Endpoint> endpoints = new ArrayList<>();
        public String name;
        public boolean constant;
    }

    /**
     * Traverses aliases without getLeafHierPortInsts' omission of INOUT pins.
     * A visited alias set makes traversal linear in the net's hierarchy/pins.
     */
    public static NetEndpoints collectEndpoints(EDIFHierNet initial) {
        NetEndpoints result = new NetEndpoints();
        Deque<EDIFHierNet> queue = new ArrayDeque<>();
        Set<String> seenEndpoints = new HashSet<>();
        queue.add(initial);
        while (!queue.isEmpty()) {
            EDIFHierNet net = queue.removeFirst();
            if (!result.aliases.add(net)) continue;
            String name = net.getHierarchicalNetName();
            if (result.name == null || name.compareTo(result.name) < 0) result.name = name;
            for (EDIFPortInst port : net.getNet().getPortInsts()) {
                EDIFHierPortInst hierPort = new EDIFHierPortInst(net.getHierarchicalInst(), port);
                Endpoint endpoint = null;
                if (port.getCellInst() == null) {
                    if (net.getHierarchicalInst().isTopLevelInst()) {
                        endpoint = new Endpoint("", port.getName(), "TOP_PORT", port.getDirection(), true, null);
                    } else {
                        EDIFHierPortInst parent = hierPort.getPortInParent();
                        if (parent != null && parent.getHierarchicalNet() != null) queue.add(parent.getHierarchicalNet());
                    }
                } else if (port.getCellInst().getCellType().isLeafCellOrBlackBox()) {
                    String type = port.getCellInst().getCellType().getName();
                    endpoint = new Endpoint(hierPort.getFullHierarchicalInstName(), port.getName(), type,
                            port.getDirection(), false, port.getCellInst());
                    // EDIFNet.isGND/isVCC also trusts reserved NET NAMES. A
                    // constant exclusion needs an actual vendor primitive driver.
                    result.constant |= port.getCellInst().getCellType().isPrimitive()
                            && port.getDirection() == EDIFDirection.OUTPUT
                            && ("GND".equals(type) || "VCC".equals(type));
                } else {
                    EDIFHierNet internal = hierPort.getInternalNet();
                    if (internal != null) queue.add(internal);
                }
                if (endpoint != null && seenEndpoints.add(endpoint.name())) result.endpoints.add(endpoint);
            }
        }
        result.endpoints.sort((a, b) -> a.name().compareTo(b.name()));
        return result;
    }

    public static class FpgaIoCutResult {
        public final Map<String, Integer> directionalCuts = new TreeMap<>();
        public final List<String> clockNets = new ArrayList<>();
        public final List<String> resetNets = new ArrayList<>();
        public final List<String> filteredConstantNets = new ArrayList<>();
        public final List<String> unanalyzedNets = new ArrayList<>();
        public final Map<String, Set<String>> netCrossings = new TreeMap<>();
        public final Map<String, String> endpointFingerprints = new TreeMap<>();
        public final Map<String, String> controlCategories = new TreeMap<>();
        public int totalDataSignals;
        public int directionalDataSignals;
        public int canonicalNets;
        public int unmappedTopPorts;
    }

    /** Strict longest mapped ancestor. Repeated simple cell names never alias. */
    public static Integer resolvePartition(String path, Map<String, Integer> mapping) {
        String candidate = path;
        while (true) {
            Integer partition = mapping.get(candidate);
            if (partition != null) return partition;
            int slash = candidate.lastIndexOf('/');
            if (slash < 0) return mapping.get("");
            candidate = candidate.substring(0, slash);
        }
    }

    public static FpgaIoCutResult analyze(EDIFNetlist netlist,
            Map<EDIFHierCellInst, Integer> leafInsts, Map<String, Integer> nameToPart) {
        return analyze(netlist, leafInsts, nameToPart, nameToPart.get(""));
    }

    /** topIoPartition must match the root assignment in the emitted mapping. */
    public static FpgaIoCutResult analyze(EDIFNetlist netlist,
            Map<EDIFHierCellInst, Integer> leafInsts, Map<String, Integer> nameToPart,
            Integer topIoPartition) {
        FpgaIoCutResult result = new FpgaIoCutResult();
        Set<EDIFHierNet> processed = new HashSet<>();
        List<EDIFHierNet> candidates = new ArrayList<>();
        for (EDIFHierCellInst leaf : leafInsts.keySet()) {
            for (EDIFHierPortInst port : leaf.getHierPortInsts()) {
                if (port.getHierarchicalNet() != null) candidates.add(port.getHierarchicalNet());
            }
        }
        for (EDIFNet net : netlist.getTopCell().getNets()) {
            candidates.add(new EDIFHierNet(netlist.getTopHierCellInst(), net));
        }
        Collections.sort(candidates);
        for (EDIFHierNet candidate : candidates) {
            if (processed.contains(candidate)) continue;
            NetEndpoints net = collectEndpoints(candidate);
            processed.addAll(net.aliases);
            result.canonicalNets++;
            if (net.constant) {
                result.filteredConstantNets.add(net.name);
                continue;
            }
            Set<Integer> drivers = new TreeSet<>(), loads = new TreeSet<>();
            boolean clock = true, reset = true;
            boolean topDriver = false, functionalSyncReset = false, unresolvedReset = false;
            int loadPins = 0, driverPins = 0;
            for (Endpoint endpoint : net.endpoints) {
                Integer partition = endpoint.topPort ? topIoPartition : resolvePartition(endpoint.path, nameToPart);
                if (partition == null) {
                    if (!endpoint.topPort) throw new IllegalArgumentException("Unmapped primitive endpoint: " + endpoint.name());
                    result.unmappedTopPorts++;
                    result.unanalyzedNets.add(net.name + ": top port " + endpoint.pin + " has no root FPGA assignment");
                } else if (partition < 0) {
                    throw new IllegalArgumentException("Negative partition for endpoint " + endpoint.name());
                }
                if (endpoint.driver) {
                    driverPins++;
                    topDriver |= endpoint.topPort;
                    if (partition != null) drivers.add(partition);
                }
                if (endpoint.load) {
                    loadPins++;
                    String pin = basePin(endpoint.pin);
                    clock &= endpoint.trustedPrimitive && PrimitiveTimingPolicy.clockPin(endpoint.instance, pin);
                    boolean resetPin = PrimitiveTimingPolicy.resetPin(endpoint.instance, pin);
                    reset &= resetPin;
                    if (resetPin) {
                        int roles = PrimitiveTimingPolicy.resetRoles(endpoint.instance, pin);
                        functionalSyncReset |= roles != 0;
                        unresolvedReset |= roles == PrimitiveTimingPolicy.COMBINATIONAL;
                    }
                    if (partition != null) loads.add(partition);
                }
            }
            if (driverPins != 1) result.unanalyzedNets.add(net.name + ": " + driverPins + " driver pins (direction may be ambiguous)");
            Set<String> crossings = new TreeSet<>();
            for (int driver : drivers) for (int load : loads) {
                if (driver != load) crossings.add(PartitionLabel.indexToFpgaLabel(driver)
                        + "->" + PartitionLabel.indexToFpgaLabel(load));
            }
            if (crossings.isEmpty()) continue;
            result.netCrossings.put(net.name, crossings);
            List<String> endpointNames = new ArrayList<>();
            for (Endpoint endpoint : net.endpoints) endpointNames.add(endpoint.name() + ":"
                    + (endpoint.driver ? "D" : "") + (endpoint.load ? "L" : ""));
            Collections.sort(endpointNames);
            result.endpointFingerprints.put(net.name, endpointFingerprint(endpointNames));
            if (loadPins > 0 && clock) { result.clockNets.add(net.name); result.controlCategories.put(net.name, "clock"); }
            else if (loadPins > 0 && reset && !unresolvedReset && (driverPins == 1 && topDriver || !functionalSyncReset)) {
                result.resetNets.add(net.name); result.controlCategories.put(net.name, "reset_only");
            }
            else {
                result.controlCategories.put(net.name, "data_or_functional_sync_control");
                result.totalDataSignals++;
                result.directionalDataSignals += crossings.size();
                for (String crossing : crossings) {
                    result.directionalCuts.put(crossing, result.directionalCuts.getOrDefault(crossing, 0) + 1);
                }
            }
        }
        Collections.sort(result.clockNets);
        Collections.sort(result.resetNets);
        Collections.sort(result.filteredConstantNets);
        Collections.sort(result.unanalyzedNets);
        return result;
    }

    private static String basePin(String pin) {
        int bracket = pin.indexOf('[');
        return bracket < 0 ? pin : pin.substring(0, bracket);
    }

    private static String endpointFingerprint(List<String> sortedEndpoints) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            boolean first = true;
            for (String endpoint : sortedEndpoints) {
                if (!first) digest.update((byte) ';');
                first = false;
                digest.update(endpoint.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder result = new StringBuilder("sha256:");
            for (byte value : digest.digest()) result.append(String.format("%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static void writeReport(Path outputDir, FpgaIoCutResult result) {
        List<String> rows = new ArrayList<>();
        rows.add("=== FPGA Logical Net Cut Report (Direction-Aware) ===");
        rows.add("Unit: one canonical net per source/destination FPGA pair; not physical package I/O pins.");
        rows.add("Control scope: " + PrimitiveTimingPolicy.CONTROL_SCOPE + "; defaults: " + PrimitiveTimingPolicy.DEFAULT_AUTHORITY);
        rows.add("Trusted clocks remain clock-only; top-driven reset-only and async/disabled resets remain separate; internally driven active synchronous controls and mixed-use nets are data/control.");
        rows.add("");
        rows.add("--- Data Signals by Direction ---");
        for (Map.Entry<String, Integer> entry : result.directionalCuts.entrySet()) rows.add("  " + entry.getKey() + ": " + entry.getValue() + " signal(s)");
        rows.add("");
        rows.add("--- Summary ---");
        rows.add("  Unique data nets crossing FPGA boundaries: " + result.totalDataSignals);
        rows.add("  Directed data net destinations crossing FPGA boundaries: " + result.directionalDataSignals);
        rows.add("  Clock nets crossing: " + result.clockNets.size());
        rows.add("  Reset nets crossing: " + result.resetNets.size());
        rows.add("  Constant nets filtered: " + result.filteredConstantNets.size());
        rows.add("  Canonical nets examined: " + result.canonicalNets);
        rows.add("  Unmapped top-level ports: " + result.unmappedTopPorts);
        rows.add("  Connectivity diagnostics: " + result.unanalyzedNets.size());
        appendSection(rows, "Clock Nets", result.clockNets);
        appendSection(rows, "Reset Nets", result.resetNets);
        appendSection(rows, "Connectivity Diagnostics", result.unanalyzedNets);
        try {
            Files.write(outputDir.resolve("fpga_io_cuts.txt"), rows, StandardCharsets.UTF_8);
            List<String> audit = new ArrayList<>();
            audit.add("CANONICAL_NET\tDIRECTED_PARTITION_PAIRS\tENDPOINT_FINGERPRINT");
            for (Map.Entry<String, Set<String>> entry : result.netCrossings.entrySet()) audit.add(entry.getKey() + "\t" + String.join(",", entry.getValue())
                    + "\t" + result.endpointFingerprints.get(entry.getKey()));
            Files.write(outputDir.resolve("fpga_net_crossings.tsv"), audit, StandardCharsets.UTF_8);
            List<String> categories = new ArrayList<>();
            categories.add("#control_scope=" + PrimitiveTimingPolicy.CONTROL_SCOPE);
            categories.add("#default_authority=" + PrimitiveTimingPolicy.DEFAULT_AUTHORITY);
            categories.add("CANONICAL_NET\tCATEGORY\tDIRECTED_PARTITION_PAIRS\tENDPOINT_FINGERPRINT");
            for (Map.Entry<String, String> entry : result.controlCategories.entrySet()) {
                categories.add(entry.getKey() + "\t" + entry.getValue() + "\t"
                        + String.join(",", result.netCrossings.get(entry.getKey())) + "\t" + result.endpointFingerprints.get(entry.getKey()));
            }
            Files.write(outputDir.resolve("fpga_control_categories.tsv"), categories, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static void appendSection(List<String> rows, String title, List<String> values) {
        if (values.isEmpty()) return;
        rows.add(""); rows.add("--- " + title + " ---");
        for (String value : values) rows.add("  " + value);
    }

    /** Independent audit of an existing final mapping against its original EDF. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("Usage: input.edf mapping.txt output_directory");
        EDIFNetlist netlist = EDIFTools.readEdifFile(java.nio.file.Paths.get(args[0]));
        String top = netlist.getTopCell().getName();
        Map<String, Integer> mapping = new TreeMap<>();
        for (String line : Files.readAllLines(java.nio.file.Paths.get(args[1]), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\\s+");
            if (fields.length != 2 || !fields[1].matches("FPGA_[A-Z]+")) throw new IllegalArgumentException("Invalid mapping rule " + line);
            String suffix = fields[1].substring(5);
            for (int i = 1; i < suffix.length(); i++) {
                if (suffix.charAt(i) != 'Z') throw new IllegalArgumentException("Unknown legacy FPGA label " + fields[1]);
            }
            int partition = suffix.charAt(0) - 'A';
            if (suffix.length() > 1) partition += 26 * (suffix.length() - 1);
            String path = fields[0];
            if (path.equals(top)) path = "";
            else if (path.startsWith(top + "/")) path = path.substring(top.length() + 1);
            Integer old = mapping.put(path, partition);
            if (old != null && old.intValue() != partition) throw new IllegalArgumentException("Conflicting mapping rule " + line);
        }
        if (!mapping.containsKey("")) throw new IllegalArgumentException("Missing top mapping for " + top);
        Map<EDIFHierCellInst, Integer> leaves = new java.util.LinkedHashMap<>();
        for (EDIFHierCellInst leaf : netlist.getAllLeafHierCellInstances(true)) leaves.put(leaf, leaves.size() + 1);
        Path output = java.nio.file.Paths.get(args[2]);
        Files.createDirectories(output);
        FpgaIoCutResult result = analyze(netlist, leaves, mapping, mapping.get(""));
        writeReport(output, result);
        System.out.println("LOGICAL_CUT_AUDIT nets=" + result.canonicalNets + " crossing_nets=" + result.netCrossings.size()
                + " data_destinations=" + result.directionalDataSignals + " diagnostics=" + result.unanalyzedNets.size());
    }
}
