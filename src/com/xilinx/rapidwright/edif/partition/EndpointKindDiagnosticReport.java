/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Additive diagnostics of the retained supplied graph, never STA or occupancy. */
public final class EndpointKindDiagnosticReport {
    public static final String FILE_NAME = "endpoint_kind_diagnostic.tsv";
    public static final String SCOPE = "modeled_graph_endpoint_ids_not_STA_R2R_top_outputs_or_useful_occupancy";
    public static final String HEADER = "schema_version\tendpoint_kind\tglobal_model_complete\tcoverage"
            + "\tmodeled_endpoint_count\tevaluated_endpoint_count\tmax_logical_crossings\tendpoint_excess_crossings"
            + "\tminimum_link_hop_analysis_available\tmax_minimum_link_hops\tinput_sha256\tmapping_sha256"
            + "\tfinal_assignment_sha256\tscope";

    private EndpointKindDiagnosticReport() { }

    /** Constant-memory identity for a frontier assignment, including its dummy index. */
    public static String assignmentDigest(int[] assignment) {
        if (assignment == null) throw new IllegalArgumentException("Missing retained assignment");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int value : assignment) {
                digest.update((byte) (value >>> 24)); digest.update((byte) (value >>> 16));
                digest.update((byte) (value >>> 8)); digest.update((byte) value);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static void identity(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact lowercase source/mapping/assignment SHA-256 required");
        }
    }

    private static void validate(CombinationalPathAnalyzer.EndpointSummary summary, boolean available, boolean complete) {
        if (summary == null || summary.modeledEndpointCount < 0 || summary.evaluatedEndpointCount < 0
                || summary.evaluatedEndpointCount > summary.modeledEndpointCount || summary.maxCrossings < 0
                || summary.excessCrossings < 0 || summary.minimumLinkHopAnalysisAvailable != available
                || (available ? summary.maxMinimumLinkHops < 0 : summary.maxMinimumLinkHops != -1)
                || (complete && summary.evaluatedEndpointCount != summary.modeledEndpointCount)
                || (summary.evaluatedEndpointCount == 0 && (summary.maxCrossings != 0
                    || summary.excessCrossings != 0 || available && summary.maxMinimumLinkHops != 0))) {
            throw new IllegalArgumentException("Invalid modeled endpoint diagnostic");
        }
    }

    private static String row(String kind, CombinationalPathAnalyzer.Analysis analysis,
            CombinationalPathAnalyzer.EndpointSummary summary, String input, String mapping, String assignment) {
        return "1\t" + kind + "\t" + analysis.complete + "\t"
                + (analysis.complete ? "complete_supplied_graph" : "incomplete_evaluated_subset") + "\t"
                + summary.modeledEndpointCount + "\t" + summary.evaluatedEndpointCount + "\t"
                + summary.maxCrossings + "\t" + summary.excessCrossings + "\t"
                + summary.minimumLinkHopAnalysisAvailable + "\t" + summary.maxMinimumLinkHops + "\t"
                + input + "\t" + mapping + "\t" + assignment + "\t" + SCOPE;
    }

    public static void write(Path directory, CombinationalPathAnalyzer.Analysis analysis,
            String inputHash, String mappingHash, String assignmentFileHash) throws IOException {
        identity(inputHash); identity(mappingHash); identity(assignmentFileHash);
        if (analysis == null) throw new IllegalArgumentException("Missing retained graph analysis");
        CombinationalPathAnalyzer.EndpointSummary capture = analysis.modeledCaptureEndpoints;
        CombinationalPathAnalyzer.EndpointSummary terminal = analysis.combinationalTerminalEndpoints;
        validate(capture, analysis.minimumLinkHopAnalysisAvailable, analysis.complete);
        validate(terminal, analysis.minimumLinkHopAnalysisAvailable, analysis.complete);
        if (Math.max(capture.maxCrossings, terminal.maxCrossings) != analysis.maxCrossings
                || Math.addExact(capture.excessCrossings, terminal.excessCrossings) != analysis.excessCrossings
                || (analysis.minimumLinkHopAnalysisAvailable
                    && Math.max(capture.maxMinimumLinkHops, terminal.maxMinimumLinkHops) != analysis.maxMinimumLinkHops)) {
            throw new IllegalArgumentException("Endpoint diagnostics do not decompose the retained global analysis");
        }
        String text = HEADER + "\n" + row("modeled_capture", analysis, capture, inputHash, mappingHash, assignmentFileHash)
                + "\n" + row("modeled_terminal", analysis, terminal, inputHash, mappingHash, assignmentFileHash) + "\n";
        Files.write(directory.resolve(FILE_NAME), text.getBytes(StandardCharsets.UTF_8));
    }
}
