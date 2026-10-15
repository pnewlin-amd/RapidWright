/* Copyright (c) 2026, Advanced Micro Devices, Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import com.xilinx.rapidwright.edif.*;

/**
 * Extract once, solve many times. Exports the hierarchy frontier, scalar-LUT
 * hypergraph and provenance without retaining a JVM during native solver trials.
 * Resource totals are cached by cell TYPE, not materialized for every instance.
 * No timing connectivity is inferred from a module's mere existence.
 */
public final class PartitionGraphExporter {
    private static final String[] RESOURCES = {"LUT", "FF", "BRAM18", "DSP", "URAM", "UNKNOWN"};
    private final Map<EDIFCell, long[]> counts = new IdentityHashMap<>();
    private final Set<EDIFCell> counting = Collections.newSetFromMap(new IdentityHashMap<EDIFCell, Boolean>());
    private final Set<String> unknownTypes = new TreeSet<>();

    private long[] resources(EDIFCell cell) {
        long[] old = counts.get(cell);
        if (old != null) return old;
        if (!counting.add(cell)) throw new IllegalArgumentException("Recursive hierarchy: " + cell.getName());
        long[] value = new long[RESOURCES.length];
        if (cell.isLeafCellOrBlackBox()) {
            String type = cell.getName().toUpperCase(Locale.ROOT);
            if (!cell.isPrimitive()) {
                value[5] = 1;
                unknownTypes.add(type);
            } else if (logicDiscoveryPolicy.isLogicLUT(type)) value[0] = 1;
            else if (type.startsWith("FD") || type.startsWith("LD")) value[1] = 1;
            else if (type.startsWith("RAMB18")) value[2] = 1;
            else if (type.startsWith("RAMB36")) value[2] = 2;
            else if (type.startsWith("DSP")) value[3] = 1;
            else if (type.startsWith("URAM")) value[4] = 1;
            else if (!(type.equals("GND") || type.equals("VCC") || type.startsWith("BUFG")
                    || type.startsWith("IBUF") || type.startsWith("OBUF"))) {
                value[5] = 1;
                unknownTypes.add(type);
            }
        } else {
            for (EDIFCellInst inst : cell.getCellInsts()) {
                long[] child = resources(inst.getCellType());
                for (int i = 0; i < value.length; i++) value[i] = Math.addExact(value[i], child[i]);
            }
        }
        counting.remove(cell);
        counts.put(cell, value);
        return value;
    }

    private static JSONObject resourceJSON(long[] counts) {
        JSONObject json = new JSONObject();
        for (int i = 0; i < counts.length; i++) json.put(RESOURCES[i], counts[i]);
        return json;
    }

    public void export(Path input, Path output, int lutLimit, Set<String> splitPaths) throws IOException {
        export(input, output, lutLimit, splitPaths, 1);
    }

    public void export(Path input, Path output, int lutLimit, Set<String> splitPaths, int minFpgas) throws IOException {
        if (lutLimit < 1) throw new IllegalArgumentException("LUT threshold must be positive");
        if (minFpgas < 1) throw new IllegalArgumentException("Minimum FPGA count must be positive");
        Files.createDirectories(output);
        long start = System.nanoTime();
        EDIFNetlist netlist = EDIFTools.readEdifFile(input);
        long[] total = resources(netlist.getTopCell());
        if (total[0] > Integer.MAX_VALUE) throw new IllegalArgumentException("LUT total exceeds native int weights");
        if (total[0] < minFpgas) throw new IllegalArgumentException("Fewer LUTs than requested FPGAs");
        int requestedLutLimit = lutLimit;
        // A positive frontier vertex weighs at most floor(total/N), hence at
        // least N LUT-bearing vertices are available. Placement still enforces
        // actual occupancy and fixed/resource constraints in the smart wrapper.
        lutLimit = Math.min(lutLimit, (int)(total[0] / minFpgas));
        Map<String, EDIFHierCellInst> byName = new TreeMap<>();
        Deque<EDIFHierCellInst> queue = new ArrayDeque<>();
        queue.add(netlist.getTopHierCellInst());
        Set<String> seenSplits = new HashSet<>();
        while (!queue.isEmpty()) {
            EDIFHierCellInst current = queue.removeFirst();
            String name = current.toString();
            boolean force = false;
            for (String split : splitPaths) {
                if (name.equals(split)) seenSplits.add(split);
                if (name.isEmpty() || split.equals(name) || split.startsWith(name + "/")) force = true;
            }
            boolean leaf = current.getCellType().isLeafCellOrBlackBox();
            if (leaf && splitPaths.contains(name)) {
                throw new IllegalArgumentException("Cannot split primitive " + name);
            }
            if (!name.isEmpty() && (leaf || (!force && resources(current.getCellType())[0] <= lutLimit))) {
                byName.put(name, current);
            } else {
                for (EDIFCellInst child : current.getCellType().getCellInsts()) queue.add(current.getChild(child));
            }
        }
        if (!seenSplits.containsAll(splitPaths)) throw new IllegalArgumentException("Unknown split paths: " + splitPaths);
        Map<EDIFHierCellInst, Integer> vertices = new LinkedHashMap<>();
        JSONArray vertexJSON = new JSONArray();
        for (Map.Entry<String, EDIFHierCellInst> entry : byName.entrySet()) {
            EDIFHierCellInst inst = entry.getValue();
            // Static sources are regenerated per FPGA by regroup and are not
            // placement variables. Their nets cannot be snake dependencies.
            if (inst.getCellType().isStaticSource()) continue;
            int id = vertices.size() + 1;
            vertices.put(inst, id);
            JSONObject row = new JSONObject();
            row.put("id", id).put("path", entry.getKey()).put("type", inst.getCellType().getName());
            row.put("resources", resourceJSON(resources(inst.getCellType())));
            vertexJSON.put(row);
        }
        Map<EDIFHierNet, Set<EDIFHierCellInst>> edges = new TreeMap<>();
        Map<EDIFHierNet, Set<String>> aliases = new HashMap<>();
        // Only actual HDI constant OUTPUT drivers prove a static net. Net
        // names such as <const0> and work-library cells named GND are not proof.
        Set<EDIFHierNet> constantNets = new HashSet<>();
        for (EDIFHierCellInst leaf : netlist.getAllLeafHierCellInstances(true)) {
            if (!leaf.getCellType().isStaticSource()) continue;
            for (EDIFHierPortInst port : leaf.getHierPortInsts()) {
                if (!port.isOutput() || port.getHierarchicalNet() == null) continue;
                EDIFHierNet attached = port.getHierarchicalNet();
                EDIFHierNet canonical = netlist.getParentNet(attached);
                constantNets.add(canonical == null ? attached : canonical);
            }
        }
        // Top-level I/O is routed through the root mapping's FPGA. Preserve
        // these otherwise single-vertex nets using one fixed virtual terminal;
        // it is a placement constraint, never a new netlist instance.
        Map<EDIFHierNet, Set<String>> topPorts = new HashMap<>();
        for (EDIFNet net : netlist.getTopCell().getNets()) {
            EDIFHierNet attached = new EDIFHierNet(netlist.getTopHierCellInst(), net);
            EDIFHierNet canonical = netlist.getParentNet(attached);
            if (canonical == null) canonical = attached;
            for (EDIFPortInst port : net.getPortInsts()) {
                if (port.isTopLevelPort()) {
                    topPorts.computeIfAbsent(canonical, n -> new TreeSet<>()).add(port.getName());
                }
            }
        }
        Set<EDIFHierNet> visited = new HashSet<>();
        for (EDIFHierCellInst vertex : vertices.keySet()) {
            for (EDIFHierPortInst port : vertex.getHierPortInsts()) {
                EDIFHierNet attached = port.getHierarchicalNet();
                if (attached == null) continue;
                EDIFHierNet canonical = netlist.getParentNet(attached);
                if (canonical == null) canonical = attached;
                aliases.computeIfAbsent(canonical, n -> new TreeSet<>()).add(attached.toString());
                if (!visited.add(canonical) || constantNets.contains(canonical)) continue;
                Set<EDIFHierCellInst> connected = canonical.getConnectedInsts(vertices.keySet());
                if (connected.size() > 1 || (!connected.isEmpty() && topPorts.containsKey(canonical))) {
                    edges.put(canonical, connected);
                }
            }
        }
        int ioVertex = 0;
        if (edges.keySet().stream().anyMatch(topPorts::containsKey)) {
            String virtualPath = "__snake_top_io__";
            if (byName.containsKey(virtualPath)) throw new IllegalArgumentException("Reserved virtual path collision");
            ioVertex = vertices.size() + 1;
            vertexJSON.put(new JSONObject().put("id", ioVertex).put("path", virtualPath)
                    .put("type", "TOP_IO_TERMINAL").put("virtual", "top_io")
                    .put("resources", resourceJSON(new long[RESOURCES.length])));
        }
        long pinCount = 0;
        try (BufferedWriter hgr = Files.newBufferedWriter(output.resolve("design.hgr"), StandardCharsets.UTF_8);
             BufferedWriter names = Files.newBufferedWriter(output.resolve("nets.jsonl"), StandardCharsets.UTF_8)) {
            hgr.write(edges.size() + " " + vertexJSON.length() + " 10\n");
            int eid = 0;
            for (Map.Entry<EDIFHierNet, Set<EDIFHierCellInst>> edge : edges.entrySet()) {
                List<Integer> pins = new ArrayList<>();
                for (EDIFHierCellInst inst : edge.getValue()) pins.add(vertices.get(inst));
                if (topPorts.containsKey(edge.getKey())) pins.add(ioVertex);
                Collections.sort(pins);
                for (int i = 0; i < pins.size(); i++) {
                    if (i > 0) hgr.write(" ");
                    hgr.write(pins.get(i).toString());
                }
                hgr.write("\n");
                pinCount += pins.size();
                JSONObject row = new JSONObject();
                row.put("id", ++eid).put("name", edge.getKey().toString());
                row.put("aliases", new JSONArray(aliases.get(edge.getKey())));
                if (topPorts.containsKey(edge.getKey())) row.put("top_ports", new JSONArray(topPorts.get(edge.getKey())));
                names.write(row.toString());
                names.write("\n");
            }
            for (EDIFHierCellInst vertex : vertices.keySet()) {
                hgr.write(Long.toString(resources(vertex.getCellType())[0]));
                hgr.write("\n");
            }
            if (ioVertex != 0) hgr.write("0\n");
        }
        JSONObject manifest = new JSONObject();
        manifest.put("schema_version", 1).put("top", netlist.getTopCell().getName());
        manifest.put("input", input.toAbsolutePath().toString()).put("lutleaf", lutLimit);
        manifest.put("requested_lutleaf", requestedLutLimit).put("minimum_fpgas", minFpgas);
        manifest.put("split_paths", new JSONArray(new TreeSet<>(splitPaths)));
        manifest.put("vertices", vertexJSON).put("total_resources", resourceJSON(total));
        manifest.put("unknown_primitive_types", new JSONArray(unknownTypes));
        manifest.put("edge_count", edges.size()).put("pin_count", pinCount);
        manifest.put("top_io_model", "fixed_virtual_terminal_at_root_mapping");
        manifest.put("elapsed_seconds", (System.nanoTime() - start) / 1e9);
        Files.write(output.resolve("manifest.json"), Collections.singleton(manifest.toString(2)), StandardCharsets.UTF_8);
        System.out.println("GRAPH_EXPORT vertices=" + vertexJSON.length() + " edges=" + edges.size()
                + " pins=" + pinCount + " LUT=" + total[0] + " cached_cell_types=" + counts.size());
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: input.edf output_directory lutleaf [split_paths.txt] [--min-fpgas N]");
        }
        Set<String> splits = new TreeSet<>();
        int minFpgas = 1;
        boolean readSplits = false;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--min-fpgas") && i + 1 < args.length) {
                minFpgas = Integer.parseInt(args[++i]);
            } else if (!args[i].startsWith("--") && !readSplits) {
                readSplits = true;
                for (String line : Files.readAllLines(Paths.get(args[i]), StandardCharsets.UTF_8)) {
                    if (!line.trim().isEmpty() && !line.startsWith("#")) splits.add(line.trim());
                }
            } else throw new IllegalArgumentException("Unknown/incomplete export argument: " + args[i]);
        }
        new PartitionGraphExporter().export(Paths.get(args[0]), Paths.get(args[1]), Integer.parseInt(args[2]), splits, minFpgas);
    }
}
