/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Source/frontier-bound incumbent identity. This file does not
 * itself prove native capacity acceptance: the frontend must emit it only
 * after the incumbent's native oracle succeeds. Metrics are never trusted
 * from this file; the current graph/timing model reevaluates its assignment. */
public final class AcceptedQualityAnchor {
    private final int[] assignment;
    public final long transferGrowthReference;
    public final double topologyGrowthReference;
    private final Map<String, String> metadata;

    private AcceptedQualityAnchor(int[] assignment, long transfers, double topology,
            Map<String, String> metadata) {
        this.assignment = assignment.clone();
        transferGrowthReference = transfers;
        topologyGrowthReference = topology;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public int[] assignment() { return assignment.clone(); }
    public Map<String, String> metadata() { return metadata; }

    /** Legacy anchors only identify an assignment/budget. All metrics are
     * reanalyzed using the requested current policy; present policy must match. */
    public String validateQualityGuardPolicy(String requested) {
        HardwareMappingRefiner.validateQualityGuardPolicy(requested);
        String declared = metadata.get("quality_guard_policy");
        if (declared != null && !requested.equals(declared)) {
            throw new IllegalArgumentException("Quality-anchor policy differs from requested quality guard");
        }
        return declared == null ? "legacy_absent_reanalyzed" : declared;
    }

    public static AcceptedQualityAnchor read(Path path, String inputSha,
            String[] names, int k) throws IOException {
        if (inputSha == null || !inputSha.matches("(?i)[0-9a-f]{64}")
                || names == null || names.length < 2 || k < 2) {
            throw new IllegalArgumentException("Quality anchor requires source SHA256, frontier and FPGA count");
        }
        int[] assignment = new int[names.length];
        assignment[0] = -1;
        boolean[] seen = new boolean[names.length];
        java.util.Set<String> identities = new java.util.HashSet<>();
        for (int v = 1; v < names.length; v++) {
            if (names[v] == null || !identities.add(names[v])) {
                throw new IllegalArgumentException("Missing/duplicate actual quality-anchor frontier path");
            }
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        boolean header = false;
        for (String original : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = original.startsWith("\uFEFF") ? original.substring(1) : original;
            if (line.trim().isEmpty()) continue;
            if (line.startsWith("#")) {
                int equal = line.indexOf('=');
                if (equal > 1) {
                    String key = line.substring(1, equal), value = line.substring(equal + 1);
                    if (metadata.putIfAbsent(key, value) != null) {
                        throw new IllegalArgumentException("Duplicate quality-anchor metadata " + key);
                    }
                }
                continue;
            }
            if (!header) {
                if (!"vertex\tpath\tpartition".equals(line)) {
                    throw new IllegalArgumentException("Expected quality-anchor vertex/path/partition TSV");
                }
                header = true;
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) throw new IllegalArgumentException("Malformed quality-anchor row");
            int vertex = Integer.parseInt(fields[0]), part = Integer.parseInt(fields[2]);
            if (vertex < 1 || vertex >= names.length || seen[vertex]
                    || part < 0 || part >= k || !fields[1].equals(names[vertex])) {
                throw new IllegalArgumentException("Quality-anchor row differs from actual frontier/FPGA count");
            }
            assignment[vertex] = part;
            seen[vertex] = true;
        }
        if (!header || !inputSha.equalsIgnoreCase(metadata.get("input_sha256"))) {
            throw new IllegalArgumentException("Unbound/mismatched quality-anchor source SHA256");
        }
        for (int v = 1; v < seen.length; v++) {
            if (!seen[v]) throw new IllegalArgumentException("Missing quality-anchor vertex " + v);
        }
        long transfers;
        double topology;
        try {
            transfers = Long.parseLong(metadata.get("transfer_growth_reference"));
            topology = Double.parseDouble(metadata.get("topology_growth_reference"));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Missing/invalid original quality-anchor budget references", error);
        }
        if (transfers < 0 || !Double.isFinite(topology) || topology < 0) {
            throw new IllegalArgumentException("Negative/nonfinite quality-anchor budget reference");
        }
        return new AcceptedQualityAnchor(assignment, transfers, topology, metadata);
    }
}
