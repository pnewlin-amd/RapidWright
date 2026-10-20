/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.xilinx.rapidwright.edif.partition;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.json.JSONObject;

import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Re-encode an existing solution without invoking a partition solver. Legacy
 * compressed rules may name nonexistent prefixes; they are used only as the
 * historical serialized ownership oracle. Every new rule must pass the exact
 * source contract, and every functional leaf must keep its historical owner.
 */
public final class MappingReencoder {
    private MappingReencoder() { }

    // Internal batch API: main loads this exact source once for all jobs.
    static JSONObject reencode(EDIFNetlist netlist, Path source, Path legacyMapping,
            Path cells, Path frontier, Path assignment, Path output) throws IOException {
        if (Files.exists(output.resolve("mapping.txt"))) {
            throw new IllegalArgumentException("Re-encoding needs a fresh output directory");
        }
        Map<String, String> legacy = MappingContract.parse(Files.readAllLines(legacyMapping, StandardCharsets.UTF_8));
        String sourceHash = MappingContract.sha256(source);
        String legacyHash = MappingContract.sha256(legacyMapping);
        String frontierHash = MappingContract.sha256(frontier);
        String assignmentHash = MappingContract.sha256(assignment);
        List<String> labels = Files.readAllLines(cells, StandardCharsets.UTF_8);
        if (!labels.equals(CellsWriter.generateNames(labels.size()))) {
            throw new IllegalArgumentException("Saved solver assignment requires its original canonical FPGA labels");
        }
        Map<Integer, Set<String>> partitions = new LinkedHashMap<>();
        for (int p = 0; p < labels.size(); p++) partitions.put(p, new LinkedHashSet<>());
        List<String> assignments = Files.readAllLines(assignment, StandardCharsets.UTF_8);
        try (BufferedReader reader = Files.newBufferedReader(frontier, StandardCharsets.UTF_8)) {
            if (!"vertex\tpath\tlogical_luts\tprimary_weight".equals(reader.readLine())) {
                throw new IllegalArgumentException("Unexpected saved frontier schema");
            }
            String line;
            int vertex = 0;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 4 || Integer.parseInt(fields[0]) != ++vertex || vertex > assignments.size()) {
                    throw new IllegalArgumentException("Saved frontier must be dense and match assignment length");
                }
                int owner = Integer.parseInt(assignments.get(vertex - 1).trim());
                if (!partitions.containsKey(owner) || !partitions.get(owner).add(fields[1])) {
                    throw new IllegalArgumentException("Invalid or duplicate saved frontier assignment");
                }
            }
            if (vertex != assignments.size()) throw new IllegalArgumentException("Assignment/frontier row mismatch");
        }
        Files.createDirectories(output);
        Path staged = output.resolve("reencoding.pending");
        if (Files.exists(staged)) throw new IllegalArgumentException("Previous re-encoding staging directory exists");
        HierMappingWriter.write(staged, netlist, partitions, Collections.emptyMap(), true);
        Map<String, String> rewritten = MappingContract.parse(Files.readAllLines(staged.resolve("mapping.txt"), StandardCharsets.UTF_8));
        String root = netlist.getTopCell().getName();
        if (!rewritten.get(root).equals(legacy.get(root))) {
            throw new IllegalArgumentException("Re-encoding changes top-port residual owner");
        }
        long leaves = compareLegacyOwnership(legacy, staged.resolve("mapping_ownership.tsv.gz"));
        if (!sourceHash.equals(MappingContract.sha256(source))
                || !legacyHash.equals(MappingContract.sha256(legacyMapping))
                || !frontierHash.equals(MappingContract.sha256(frontier))
                || !assignmentHash.equals(MappingContract.sha256(assignment))) {
            throw new IllegalStateException("Re-encoding input changed");
        }
        JSONObject contract = new JSONObject(new String(Files.readAllBytes(staged.resolve("mapping_contract.json")), StandardCharsets.UTF_8));
        contract.put("input_sha256", sourceHash);
        Files.write(staged.resolve("mapping_contract.json"), Collections.singletonList(contract.toString(2)), StandardCharsets.UTF_8);
        JSONObject proof = new JSONObject();
        proof.put("schema_version", 1);
        proof.put("status", "PASS");
        proof.put("scope", "exact_functional_leaf_ownership_equivalence; source netlist unchanged; no regroup execution");
        proof.put("input_sha256", sourceHash);
        proof.put("legacy_mapping_sha256", legacyHash);
        proof.put("mapping_sha256", MappingContract.sha256(staged.resolve("mapping.txt")));
        proof.put("frontier_sha256", frontierHash);
        proof.put("final_assignment_sha256", assignmentHash);
        proof.put("functional_leaves_compared", leaves);
        proof.put("ownership_mismatches", 0);
        proof.put("top_port_owner_unchanged", true);
        proof.put("constant_policy", "GND/VCC separately local; no explicit move records");
        proof.put("native_measurement_scope", "No new Vivado measurement: identical source leaf ownership preserves prior resource subsets and cut endpoints; original hash-bound evidence stays historical");
        Files.write(staged.resolve("reencoding_equivalence.json"), Collections.singletonList(proof.toString(2)), StandardCharsets.UTF_8);
        // Publish companions first, legal mapping last, only after equality passes.
        List<Path> companions = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(staged)) {
            for (Path file : files) if (!file.getFileName().toString().equals("mapping.txt")) companions.add(file);
        }
        for (Path file : companions) Files.move(file, output.resolve(file.getFileName()));
        Files.move(staged.resolve("mapping.txt"), output.resolve("mapping.txt"));
        Files.delete(staged);
        return proof;
    }

    /** Independent historical longest-serialized-prefix resolution over every source leaf. */
    static long compareLegacyOwnership(Map<String, String> legacy, Path ownership) throws IOException {
        long leaves = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(ownership)), StandardCharsets.UTF_8))) {
            if (!"instance_path\tref_name\tclassification\tlogical_fpga\tgoverning_record\tfrontier_owner".equals(reader.readLine())) {
                throw new IllegalArgumentException("Unexpected ownership manifest schema");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 6) throw new IllegalArgumentException("Malformed source ownership manifest");
                if (!fields[2].equals("FUNCTIONAL")) continue;
                String ancestor = fields[0];
                String owner = null;
                while (true) {
                    if (legacy.containsKey(ancestor)) { owner = legacy.get(ancestor); break; }
                    int slash = ancestor.lastIndexOf('/');
                    if (slash < 0) break;
                    ancestor = ancestor.substring(0, slash);
                }
                if (!fields[3].equals(owner)) {
                    throw new IllegalArgumentException("Re-encoding changes legacy functional ownership: " + fields[0]);
                }
                leaves++;
            }
        }
        return leaves;
    }

    /** Batch jobs: legacy mapping, cells, frontier, final assignment, new output directory. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: source.edf reencoding_jobs.tsv (five paths, no header)");
        Path source = Paths.get(args[0]);
        String sourceHash = MappingContract.sha256(source);
        EDIFNetlist netlist = EDIFTools.readEdifFile(source);
        for (String job : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            if (job.trim().isEmpty()) continue;
            String[] fields = job.split("\t", -1);
            if (fields.length != 5) throw new IllegalArgumentException("Re-encoding job needs five paths");
            JSONObject proof = reencode(netlist, source, Paths.get(fields[0]), Paths.get(fields[1]),
                    Paths.get(fields[2]), Paths.get(fields[3]), Paths.get(fields[4]));
            System.out.println("MAPPING_REENCODING_PASS " + fields[4] + " leaves=" + proof.getLong("functional_leaves_compared"));
        }
        if (!sourceHash.equals(MappingContract.sha256(source))) throw new IllegalStateException("Source changed during batch");
    }
}
