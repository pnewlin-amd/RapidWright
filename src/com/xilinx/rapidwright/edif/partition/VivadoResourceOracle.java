/* Copyright (c) 2026, Advanced Micro Devices, Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.InputStream;

/** Imports authoritative Vivado Used-column values for one exact mapping. */
public final class VivadoResourceOracle {
    /** A hard physical-cap violation; callers must reject or repartition. */
    public static final class CapacityViolationException extends IllegalArgumentException {
        public final String partition;
        public final long actual, limit;
        private CapacityViolationException(String partition, long actual, long limit) {
            super("Native CLB LUT cap exceeded for " + partition + ": " + actual + " > " + limit);
            this.partition = partition; this.actual = actual; this.limit = limit;
        }
    }
    public static final class Resources {
        public final String partition;
        public final Map<String, BigDecimal> metrics;

        private Resources(String partition, Map<String, BigDecimal> metrics) {
            this.partition = partition;
            this.metrics = Collections.unmodifiableMap(metrics);
        }

        public long clbLuts() { return required("clb_luts").longValueExact(); }
        public long flipFlops() { return required("flip_flops").longValueExact(); }
        public BigDecimal required(String metric) {
            BigDecimal value = metrics.get(metric);
            if (value == null) throw new IllegalArgumentException("Native metric unavailable: " + partition + "/" + metric);
            return value;
        }
    }

    public final Map<String, Resources> partitions;
    public final Resources design;
    public final Path sourceTable;

    private VivadoResourceOracle(Path table, Map<String, Resources> rows) {
        sourceTable = table.toAbsolutePath();
        design = rows.remove("DESIGN");
        if (design == null) throw new IllegalArgumentException("Missing DESIGN row in native utilization table");
        partitions = Collections.unmodifiableMap(rows);
    }

    /** Rejects a table generated for a different mapping, even if labels match. */
    public static VivadoResourceOracle read(Path table, Path currentMapping) throws IOException {
        Path directory = table.toAbsolutePath().getParent();
        Map<String, String> complete = metadata(directory.resolve("native_complete.txt"));
        Path provenancePath = directory.resolve("vivado_provenance.txt");
        if (!Files.isRegularFile(provenancePath)) provenancePath = directory.resolve("provenance.txt");
        Map<String, String> provenance = metadata(provenancePath);
        if (!"VIVADO_PARTITION_RESOURCE_MAPPING_PASS".equals(complete.get("status"))) {
            throw new IllegalArgumentException("Native resource export is not complete");
        }
        String mappingHash = sha256(Files.readAllBytes(currentMapping));
        if (!mappingHash.equals(complete.get("mapping_sha256"))
                || !mappingHash.equals(provenance.get("mapping_sha256"))) {
            throw new IllegalArgumentException("Native resource mapping fingerprint mismatch");
        }
        String checkpointHash = complete.get("checkpoint_sha256");
        if (checkpointHash == null || !checkpointHash.matches("[0-9a-f]{64}")
                || !checkpointHash.equals(provenance.get("checkpoint_sha256"))
                || provenance.get("checkpoint") == null || provenance.get("checkpoint").isEmpty()) {
            throw new IllegalArgumentException("Missing/inconsistent native checkpoint provenance");
        }
        Path checkpoint = java.nio.file.Paths.get(provenance.get("checkpoint"));
        if (!checkpoint.isAbsolute() || !Files.isRegularFile(checkpoint)
                || !checkpointHash.equals(sha256(checkpoint))) {
            throw new IllegalArgumentException("Native source checkpoint fingerprint mismatch: " + checkpoint);
        }
        Path sourceMapping = table.toAbsolutePath().getParent().resolve("source_mapping.txt");
        if (!Files.isRegularFile(sourceMapping)
                || !Arrays.equals(Files.readAllBytes(sourceMapping), Files.readAllBytes(currentMapping))) {
            throw new IllegalArgumentException("Native resource reference does not match mapping bytes: " + sourceMapping);
        }
        List<String> lines = Files.readAllLines(table, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IllegalArgumentException("Empty native utilization table");
        String[] columns = lines.get(0).split("\t", -1);
        if (!"partition".equals(columns[0])) throw new IllegalArgumentException("Invalid native utilization table header");
        Map<String, Resources> rows = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            String[] fields = lines.get(i).split("\t", -1);
            if (fields.length != columns.length) throw new IllegalArgumentException("Invalid native utilization row " + (i + 1));
            Map<String, BigDecimal> metrics = new LinkedHashMap<>();
            for (int c = 1; c < columns.length; c++) {
                if ("NA".equals(fields[c])) continue;
                BigDecimal value = new BigDecimal(fields[c]);
                if (value.signum() < 0) throw new IllegalArgumentException("Negative native utilization value");
                metrics.put(columns[c], value);
            }
            Resources resource = new Resources(fields[0], metrics);
            resource.clbLuts(); resource.flipFlops();
            if (rows.put(fields[0], resource) != null) throw new IllegalArgumentException("Duplicate native utilization partition " + fields[0]);
        }
        Set<String> expected = new TreeSet<>();
        for (String line : Files.readAllLines(currentMapping, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\\s+");
            if (fields.length != 2) throw new IllegalArgumentException("Invalid source mapping rule " + line);
            expected.add(fields[1]);
        }
        Set<String> actual = new TreeSet<>(rows.keySet());
        actual.remove("DESIGN");
        if (!actual.equals(expected)) throw new IllegalArgumentException("Native utilization partition labels mismatch: expected " + expected + " found " + actual);
        return new VivadoResourceOracle(table, rows);
    }

    private static Map<String, String> metadata(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Missing native export metadata " + path);
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int equals = line.indexOf('=');
            if (equals > 0) result.put(line.substring(0, equals), line.substring(equals + 1));
        }
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder();
            for (byte b : digest) text.append(String.format("%02x", b & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = Files.newInputStream(file)) {
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder text = new StringBuilder();
            for (byte value : digest.digest()) text.append(String.format("%02x", value & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Checks authoritative native counts without writing any success artifact. */
    public void validateMaxClbLuts(long maximum) {
        if (maximum < 0) throw new IllegalArgumentException("Native LUT cap must be nonnegative");
        for (Resources resource : partitions.values()) {
            if (resource.clbLuts() > maximum) throw new CapacityViolationException(resource.partition, resource.clbLuts(), maximum);
        }
    }

    /** Every FPGA must have an explicit cap; unknown/missing labels fail. */
    public void validateClbLutCaps(Map<String, Long> caps) {
        if (caps == null || !caps.keySet().equals(partitions.keySet())) {
            throw new IllegalArgumentException("Native LUT cap labels must exactly match mapped FPGAs");
        }
        for (Resources resource : partitions.values()) {
            Long maximum = caps.get(resource.partition);
            if (maximum == null || maximum < 0) throw new IllegalArgumentException("Invalid native LUT cap for " + resource.partition);
            if (resource.clbLuts() > maximum) throw new CapacityViolationException(resource.partition, resource.clbLuts(), maximum);
        }
    }

    public static Map<String, Long> readClbLutCaps(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Map<String, Long> result = new LinkedHashMap<>();
        boolean header = false;
        for (String line : lines) {
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
            if (!header) {
                if (!"partition\tclb_luts".equals(line.trim())) {
                    throw new IllegalArgumentException("Expected LUT cap header partition<TAB>clb_luts");
                }
                header = true;
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 2 || !fields[0].matches("FPGA_[A-Z]+")) {
                throw new IllegalArgumentException("Malformed native LUT cap row: " + line);
            }
            long limit = Long.parseLong(fields[1].trim());
            if (limit < 0 || result.put(fields[0], limit) != null) {
                throw new IllegalArgumentException("Negative or duplicate native LUT cap: " + line);
            }
        }
        if (!header || result.isEmpty()) throw new IllegalArgumentException("Empty native LUT cap table");
        return result;
    }

    /** Applies reports after native evaluation without rerunning a random solver. */
    public void writeReports(Path output) throws IOException {
        Files.createDirectories(output);
        Path lutReport = output.resolve("lut_report.txt");
        Path logicalReport = output.resolve("logical_lut_report.txt");
        if (Files.isRegularFile(lutReport) && !Files.exists(logicalReport)) {
            Files.copy(lutReport, logicalReport);
        }
        List<String> luts = new ArrayList<>(), summary = new ArrayList<>();
        long sum = 0, maximum = 0;
        for (Resources resource : partitions.values()) {
            long value = resource.clbLuts();
            luts.add(value + "LUTS " + resource.partition);
            sum = Math.addExact(sum, value);
            maximum = Math.max(maximum, value);
        }
        if (partitions.isEmpty()) throw new IllegalArgumentException("No partitions in native resource table");
        double mean = sum / (double) partitions.size();
        luts.add((long) Math.ceil(mean) + "LUTS mean");
        Files.write(lutReport, luts, StandardCharsets.UTF_8);
        Path target = output.resolve("vivado_resources.tsv").toAbsolutePath();
        if (!target.equals(sourceTable)) Files.copy(sourceTable, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path nativeMapping = sourceTable.getParent().resolve("source_mapping.txt");
        Path targetMapping = output.resolve("source_mapping.txt").toAbsolutePath();
        if (!nativeMapping.equals(targetMapping)) Files.copy(nativeMapping, targetMapping,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path provenance = sourceTable.getParent().resolve("vivado_provenance.txt");
        if (!Files.isRegularFile(provenance)) provenance = sourceTable.getParent().resolve("provenance.txt");
        if (Files.isRegularFile(provenance)) Files.copy(provenance, output.resolve("vivado_provenance.txt"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path complete = sourceTable.getParent().resolve("native_complete.txt");
        Path targetComplete = output.resolve("native_complete.txt").toAbsolutePath();
        if (!complete.equals(targetComplete)) Files.copy(complete, targetComplete, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        summary.add("source=Vivado report_utilization -cells on original assigned leaf primitive collections");
        summary.add("design_clb_luts=" + design.clbLuts());
        summary.add("partition_clb_lut_sum=" + sum);
        summary.add("subset_sum_minus_whole_design=" + (sum - design.clbLuts()));
        summary.add("partition_clb_lut_mean=" + mean);
        summary.add("partition_clb_lut_max=" + maximum);
        summary.add("physical_clb_lut_imbalance=" + (mean == 0 ? 0 : maximum / mean - 1));
        summary.add("logical_lut_balance_does_not_guarantee_native_clb_lut_balance=true");
        summary.add("subset_sums_need_not_equal_whole_design=Native LUT combining can span mapping boundaries");
        summary.add("reported_values_are_original_netlist_subset_utilization_not_future_wrapped_netlist_placement=true");
        Files.write(output.resolve("vivado_resource_summary.txt"), summary, StandardCharsets.UTF_8);
        Path definitions = output.resolve("metric_definitions.txt");
        List<String> updatedDefinitions = new ArrayList<>();
        updatedDefinitions.add("lut_report.txt and vivado_resources.tsv contain exact native Vivado CLB LUT Used values for original mapped primitive subsets.");
        updatedDefinitions.add("logical_lut_report.txt and logical_resources.tsv count logical primitive instances; LUT6_2 is one logical LUT primitive.");
        updatedDefinitions.add("Native subset CLB LUT sums may differ from whole-design utilization because LUT combining can cross mapping boundaries.");
        if (Files.isRegularFile(definitions)) {
            for (String line : Files.readAllLines(definitions, StandardCharsets.UTF_8)) {
                if (line.startsWith("lut_report.txt ") || line.startsWith("logical_lut_report.txt ")
                        || line.startsWith("Native subset CLB LUT sums ")
                        || line.startsWith("They are NOT packed CLB LUT utilization:")) continue;
                updatedDefinitions.add(line);
            }
        }
        Files.write(definitions, updatedDefinitions, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: vivado_resources.tsv mapping.txt output_directory [--max-luts N] [--lut-caps caps.tsv]");
        }
        Long globalCap = null;
        Path capsFile = null;
        for (int i = 3; i < args.length; i++) {
            if ("--max-luts".equals(args[i]) && globalCap == null && i + 1 < args.length) {
                globalCap = Long.valueOf(args[++i]);
            } else if ("--lut-caps".equals(args[i]) && capsFile == null && i + 1 < args.length) {
                capsFile = java.nio.file.Paths.get(args[++i]);
            } else throw new IllegalArgumentException("Invalid/repeated native cap argument: " + args[i]);
        }
        VivadoResourceOracle oracle = read(java.nio.file.Paths.get(args[0]), java.nio.file.Paths.get(args[1]));
        if (globalCap != null) oracle.validateMaxClbLuts(globalCap);
        if (capsFile != null) oracle.validateClbLutCaps(readClbLutCaps(capsFile));
        Path output = java.nio.file.Paths.get(args[2]);
        oracle.writeReports(output);
        if (globalCap != null || capsFile != null) {
            List<String> status = new ArrayList<>();
            status.add("native_lut_cap_status=PASS");
            if (globalCap != null) status.add("global_native_lut_cap=" + globalCap);
            if (capsFile != null) {
                status.add("per_fpga_native_lut_caps=" + capsFile.toAbsolutePath());
                Path savedCaps = output.resolve("native_lut_caps.tsv");
                if (!capsFile.toAbsolutePath().normalize().equals(savedCaps.toAbsolutePath().normalize())) {
                    Files.copy(capsFile, savedCaps, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.write(output.resolve("vivado_resource_summary.txt"), status, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        }
        System.out.println("VIVADO_RESOURCE_REPORT_PASS design_clb_luts=" + oracle.design.clbLuts()
                + " partitions=" + oracle.partitions.size());
    }
}
