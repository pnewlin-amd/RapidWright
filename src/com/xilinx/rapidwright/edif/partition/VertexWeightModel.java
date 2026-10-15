/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import com.xilinx.rapidwright.edif.EDIFCell;

/**
 * Optional additive, integer, primitive-type weight estimate for native balance.
 * These weights are a calibrated proxy, never a certificate of packed LUT area.
 * A hierarchy weight is the checked sum of its cached primitive census. Known
 * logic LUT types must be listed explicitly; other unlisted types cost zero.
 */
public final class VertexWeightModel {
    private final Map<String, Integer> primitiveWeights;
    private final LogicalResourceCounts counts = new LogicalResourceCounts();
    private final Map<EDIFCell, Integer> cache = new IdentityHashMap<>();

    private VertexWeightModel(Map<String, Integer> primitiveWeights) {
        this.primitiveWeights = primitiveWeights;
    }

    /** Reads REF_NAME/integer_weight TSV, optionally bound by #input_sha256=. */
    public static VertexWeightModel read(Path path, String expectedInputSha) throws IOException {
        Map<String, Integer> weights = new HashMap<>();
        String binding = null;
        boolean header = false;
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            String text = line.trim();
            if (text.isEmpty()) continue;
            if (text.startsWith("#input_sha256=")) {
                if (binding != null) throw new IllegalArgumentException("Duplicate weight-model input SHA binding");
                binding = text.substring("#input_sha256=".length()).toLowerCase(Locale.ROOT);
                if (!binding.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("Invalid weight-model input SHA256");
                }
                continue;
            }
            if (text.startsWith("#")) continue;
            String[] fields = text.split("\\t", -1);
            if (!header) {
                if (fields.length != 2 || !"REF_NAME".equals(fields[0])
                        || !"integer_weight".equals(fields[1])) {
                    throw new IllegalArgumentException("Weight model requires REF_NAME/integer_weight TSV header");
                }
                header = true;
                continue;
            }
            if (fields.length != 2 || fields[0].isEmpty() || !fields[0].equals(fields[0].trim())) {
                throw new IllegalArgumentException("Malformed weight-model row " + lineNumber);
            }
            int weight;
            try { weight = Integer.parseInt(fields[1]); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid integer weight at row " + lineNumber, e);
            }
            if (weight < 0 || weights.putIfAbsent(fields[0], weight) != null) {
                throw new IllegalArgumentException("Negative or duplicate weight at row " + lineNumber);
            }
        }
        if (!header || weights.isEmpty()) throw new IllegalArgumentException("Empty weight model");
        if (binding != null && (expectedInputSha == null
                || !binding.equals(expectedInputSha.toLowerCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("Weight-model input SHA256 differs from input netlist");
        }
        return new VertexWeightModel(weights);
    }

    /** Returns the checked additive weight of a primitive or hierarchy cell. */
    public int weight(EDIFCell cell) {
        Integer old = cache.get(cell);
        if (old != null) return old;
        long total = 0;
        try {
            for (Map.Entry<String, Long> entry : counts.count(cell).primitiveHistogram().entrySet()) {
                Integer unit = primitiveWeights.get(entry.getKey());
                if (unit == null && logicDiscoveryPolicy.isLogicLUT(entry.getKey())) {
                    throw new IllegalArgumentException("Weight model omits logic LUT type " + entry.getKey());
                }
                if (unit != null) total = Math.addExact(total,
                        Math.multiplyExact(unit.longValue(), entry.getValue()));
            }
            int weight = Math.toIntExact(total);
            cache.put(cell, weight);
            return weight;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Weight-model hierarchy sum exceeds integer range at "
                    + cell.getName(), e);
        }
    }
}
