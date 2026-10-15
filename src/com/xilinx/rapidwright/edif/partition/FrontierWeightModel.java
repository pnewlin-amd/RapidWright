/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Strict source- and frontier-bound native-feedback scalar estimates. */
public final class FrontierWeightModel {
    private FrontierWeightModel() { }

    public static int[] read(Path path, String inputSha, String[] names) throws IOException {
        int[] weights = new int[names.length];
        boolean[] seen = new boolean[names.length];
        boolean bound = false, header = false;
        long sum = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            if (line.startsWith("#input_sha256=")) {
                if (bound || !line.substring(14).equalsIgnoreCase(inputSha)) {
                    throw new IllegalArgumentException("Frontier weight input SHA256 mismatch/duplicate");
                }
                bound = true;
                continue;
            }
            if (line.startsWith("#")) continue;
            if (!header) {
                if (!"vertex\tpath\tinteger_weight".equals(line)) {
                    throw new IllegalArgumentException("Expected vertex/path/integer_weight TSV");
                }
                header = true;
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) throw new IllegalArgumentException("Malformed frontier weight row");
            int vertex = Integer.parseInt(fields[0]), weight = Integer.parseInt(fields[2]);
            if (vertex < 1 || vertex >= names.length || seen[vertex] || weight < 0
                    || !fields[1].equals(names[vertex])) {
                throw new IllegalArgumentException("Frontier weight row differs from actual frontier");
            }
            seen[vertex] = true;
            weights[vertex] = weight;
            sum += weight;
        }
        if (!bound || !header || sum == 0) throw new IllegalArgumentException("Unbound/empty frontier weights");
        for (int v = 1; v < seen.length; v++) {
            if (!seen[v]) throw new IllegalArgumentException("Missing frontier weight vertex " + v);
        }
        return weights;
    }
}
