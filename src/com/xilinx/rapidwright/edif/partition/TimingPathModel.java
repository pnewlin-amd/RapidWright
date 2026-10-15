/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;

/** Source-bound sampled native setup paths, not a full STA propagation engine.
 * Unmapped physical macro internals resolve to their longest frontier ancestor.
 * Declared @top_io: boundaries have no FPGA home and add no invented crossing.
 */
public final class TimingPathModel {
    public static final class TimingPath {
        public final String id, launchClock, captureClock;
        public final double sourceSlackNs, requirementNs;
        public final boolean scored;
        public final int unanchoredIoCells;
        private final int[] owners;
        private TimingPath(String id, String launch, String capture, double slack,
                double requirement, boolean scored, int[] owners, int io) {
            this.id = id; launchClock = launch; captureClock = capture;
            sourceSlackNs = slack; requirementNs = requirement; this.scored = scored;
            this.owners = owners; unanchoredIoCells = io;
        }
        public int[] owners() { return owners.clone(); }
    }

    public static final class Evaluation {
        public final double timingCost;
        /** Actual source-sample worst slack; NaN if no scored source paths. */
        public final double sourceWorstSlackNs;
        /** Null unless an explicit per-crossing delay was provided and paths scored. */
        public final Double modeledWorstSlackNs;
        private Evaluation(double cost, double sourceWorst, Double modeledWorst) {
            timingCost = cost; sourceWorstSlackNs = sourceWorst; modeledWorstSlackNs = modeledWorst;
        }
    }

    private final List<TimingPath> paths;
    private final Map<String, String> metadata;
    private final int ownerLimit, scoredCount, unanchoredCount;
    private final double maxSourceCriticality;

    private TimingPathModel(List<TimingPath> paths, Map<String, String> metadata, int ownerLimit) {
        this.paths = Collections.unmodifiableList(paths);
        this.metadata = Collections.unmodifiableMap(metadata);
        this.ownerLimit = ownerLimit;
        int scored = 0, unanchored = 0;
        double maximum = 0;
        for (TimingPath path : paths) {
            if (path.scored) { scored++; maximum = Math.max(maximum, criticality(path.sourceSlackNs, path.requirementNs)); }
            if (path.unanchoredIoCells > 0 || path.owners.length == 0) unanchored++;
        }
        scoredCount = scored; unanchoredCount = unanchored;
        maxSourceCriticality = maximum;
    }

    public int pathCount() { return paths.size(); }
    public int scoredPathCount() { return scoredCount; }
    public int unscoredPathCount() { return paths.size() - scoredCount; }
    public int unanchoredPathCount() { return unanchoredCount; }
    public int ownerLimit() { return ownerLimit; }
    public double maxSourceCriticality() { return maxSourceCriticality; }
    public TimingPath path(int index) { return paths.get(index); }
    public Map<String, String> metadata() { return metadata; }

    private static double number(String value, String field) {
        if ("NA".equals(value)) return Double.NaN;
        double result;
        try { result = Double.parseDouble(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid native timing " + field, e); }
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Nonfinite native timing " + field);
        return result;
    }

    private static Integer ancestorOwner(String cell, Map<String, Integer> owners) {
        while (true) {
            Integer owner = owners.get(cell);
            if (owner != null) return owner;
            int slash = cell.lastIndexOf('/');
            if (slash < 0) return owners.get("");
            cell = cell.substring(0, slash);
        }
    }

    public static TimingPathModel read(Path file, String expectedInputSha,
            String[] instLookup, String topName) throws IOException {
        if (expectedInputSha == null || !expectedInputSha.matches("(?i)[0-9a-f]{64}")
                || instLookup == null || instLookup.length < 2) {
            throw new IllegalArgumentException("Timing paths require source SHA256 and a one-based frontier");
        }
        Map<String, Integer> ownerMap = new HashMap<>();
        for (int v = 1; v < instLookup.length; v++) {
            if (instLookup[v] == null || ownerMap.put(instLookup[v], v) != null) {
                throw new IllegalArgumentException("Missing/duplicate frontier identity for timing paths");
            }
        }
        List<TimingPath> paths = new ArrayList<>();
        Map<String, String> metadata = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        boolean bound = false, header = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.trim().isEmpty()) continue;
            if (line.startsWith("#")) {
                int separator = line.indexOf('=');
                if (separator > 1) {
                    String key = line.substring(1, separator), value = line.substring(separator + 1);
                    if (metadata.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate timing metadata " + key);
                    if ("input_sha256".equals(key)) {
                        if (!value.equalsIgnoreCase(expectedInputSha)) throw new IllegalArgumentException("Timing-path input SHA256 mismatch");
                        bound = true;
                    }
                }
                continue;
            }
            if (!header) {
                if (!"path_id\tslack_ns\trequirement_ns\tlaunch_clock\tcapture_clock\tcells_json".equals(line)) {
                    throw new IllegalArgumentException("Invalid native timing TSV header");
                }
                header = true;
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 6 || fields[0].isEmpty() || !ids.add(fields[0])) {
                throw new IllegalArgumentException("Malformed/duplicate native timing path row");
            }
            double slack = number(fields[1], "slack"), requirement = number(fields[2], "requirement");
            boolean scored = Double.isFinite(slack) && Double.isFinite(requirement) && requirement > 0;
            if (scored) criticality(slack, requirement); // Validate numerical scale before use.
            JSONArray cells;
            try { cells = new JSONArray(fields[5]); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid timing cells_json", e); }
            List<Integer> owners = new ArrayList<>();
            int io = 0;
            for (int i = 0; i < cells.length(); i++) {
                Object value = cells.get(i);
                if (!(value instanceof String) || ((String) value).isEmpty()) {
                    throw new IllegalArgumentException("Timing path cells must be nonempty strings");
                }
                String cell = (String) value;
                if (cell.startsWith("@top_io:")) {
                    if (cell.length() == "@top_io:".length()) throw new IllegalArgumentException("Unnamed timing top IO");
                    io++;
                    continue;
                }
                Integer owner = ancestorOwner(cell, ownerMap);
                if (owner == null && topName != null && cell.startsWith(topName + "/")) {
                    owner = ancestorOwner(cell.substring(topName.length() + 1), ownerMap);
                }
                if (owner == null) throw new IllegalArgumentException("Unmapped native timing cell " + cell);
                if (owners.isEmpty() || owners.get(owners.size() - 1).intValue() != owner) owners.add(owner);
            }
            paths.add(new TimingPath(fields[0], fields[3], fields[4], slack, requirement, scored,
                    owners.stream().mapToInt(Integer::intValue).toArray(), io));
        }
        if (!bound || !header) throw new IllegalArgumentException("Unbound/incomplete native timing path export");
        return new TimingPathModel(paths, metadata, instLookup.length - 1);
    }

    private static double criticality(double slack, double requirement) {
        double ratio = Math.max(0, 1.0 - slack / requirement);
        double value = ratio * ratio;
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Native timing criticality overflows numerical range");
        return value;
    }

    /** Source criticality proxy for initial native hyperedge weighting. Parallel
     * driver/sink nets cannot be distinguished from a cell-only timing sample. */
    public Map<Long, Double> directedOwnerPairCriticalities() {
        Map<Long, Double> result = new HashMap<>();
        for (TimingPath path : paths) {
            if (!path.scored) continue;
            double criticality = criticality(path.sourceSlackNs, path.requirementNs);
            for (int i = 1; i < path.owners.length; i++) {
                long pair = ((long) path.owners[i - 1] << 32) | (path.owners[i] & 0xffffffffL);
                result.merge(pair, criticality, Math::max);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static int crossings(TimingPath path, int[] assignment) {
        int result = 0;
        for (int i = 1; i < path.owners.length; i++) if (assignment[path.owners[i - 1]] != assignment[path.owners[i]]) result++;
        return result;
    }

    public double pathCost(int index, int[] assignment, Double extraDelayNsPerCrossing) {
        return pathCost(index, assignment, extraDelayNsPerCrossing, null);
    }

    /** minimumHops must come from a validated PartitionTopology. The hot local
     * probe deliberately avoids revalidating the whole matrix on every move. */
    public double pathCost(int index, int[] assignment, Double extraDelayNsPerCrossing, int[][] minimumHops) {
        if (assignment == null || assignment.length <= ownerLimit
                || (extraDelayNsPerCrossing != null && (!Double.isFinite(extraDelayNsPerCrossing) || extraDelayNsPerCrossing < 0))) {
            throw new IllegalArgumentException("Invalid timing assignment/delay model");
        }
        TimingPath path = paths.get(index);
        if (!path.scored) return 0;
        int changes = crossings(path, assignment);
        double slack = path.sourceSlackNs - (extraDelayNsPerCrossing == null ? 0 : extraDelayNsPerCrossing * changes);
        if (!Double.isFinite(slack)) throw new IllegalArgumentException("Modeled timing slack overflows numerical range");
        Set<Integer> visited = new HashSet<>();
        int last = -1, reentries = 0;
        for (int owner : path.owners) {
            int part = assignment[owner];
            if (part != last) { if (!visited.add(part)) reentries++; last = part; }
        }
        long severity = changes;
        if (minimumHops != null) {
            severity = 0;
            for (int i = 1; i < path.owners.length; i++) {
                severity = Math.addExact(severity, minimumHops[assignment[path.owners[i - 1]]][assignment[path.owners[i]]]);
            }
        }
        long excess = Math.max(0, severity - 1);
        double normalized = criticality(slack, path.requirementNs)
                / (maxSourceCriticality > 0 ? maxSourceCriticality : 1);
        return normalized * (severity + excess * (double) excess + reentries);
    }

    public Evaluation evaluate(int[] assignment, Double extraDelayNsPerCrossing) {
        return evaluate(assignment, extraDelayNsPerCrossing, null);
    }

    public Evaluation evaluate(int[] assignment, Double extraDelayNsPerCrossing, int[][] minimumHops) {
        if (minimumHops != null) PartitionTopology.validateMinimumHopMatrix(minimumHops);
        if (assignment == null || assignment.length <= ownerLimit
                || (extraDelayNsPerCrossing != null && (!Double.isFinite(extraDelayNsPerCrossing) || extraDelayNsPerCrossing < 0))) {
            throw new IllegalArgumentException("Invalid timing assignment/delay model");
        }
        for (int v = 1; v <= ownerLimit; v++) if (assignment[v] < 0) throw new IllegalArgumentException("Unmapped timing owner");
        double cost = 0, worstSource = Double.POSITIVE_INFINITY, worstModeled = Double.POSITIVE_INFINITY;
        for (int i = 0; i < paths.size(); i++) {
            TimingPath path = paths.get(i);
            if (!path.scored) continue;
            cost += pathCost(i, assignment, extraDelayNsPerCrossing, minimumHops);
            worstSource = Math.min(worstSource, path.sourceSlackNs);
            if (extraDelayNsPerCrossing != null) worstModeled = Math.min(worstModeled,
                    path.sourceSlackNs - extraDelayNsPerCrossing * crossings(path, assignment));
        }
        if (!Double.isFinite(cost)) throw new IllegalArgumentException("Sampled timing cost overflows numerical range");
        return new Evaluation(cost, scoredCount == 0 ? Double.NaN : worstSource,
                extraDelayNsPerCrossing == null || scoredCount == 0 ? null : worstModeled);
    }
}
