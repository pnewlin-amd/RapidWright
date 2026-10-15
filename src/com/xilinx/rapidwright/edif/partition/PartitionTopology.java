/* Copyright (c) 2026, Advanced Micro Devices, Inc. Licensed under Apache-2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Weighted, undirected FPGA topology in METIS format (fmt=1, positive link costs).
 * Costs represent latency/routing cost, not bandwidth. Disconnected systems fail closed.
 */
public final class PartitionTopology {
    private final double[][] distance;
    private final int[][] minimumHops;
    private final boolean declaredPhysicalAdjacency;

    private PartitionTopology(double[][] distance, int[][] minimumHops, boolean declaredPhysicalAdjacency) {
        this.distance = distance;
        this.minimumHops = minimumHops;
        this.declaredPhysicalAdjacency = declaredPhysicalAdjacency;
    }

    public static PartitionTopology complete(int k) {
        double[][] d = new double[k][k];
        int[][] h = new int[k][k];
        for (int i = 0; i < k; i++) {
            Arrays.fill(d[i], 1.0);
            Arrays.fill(h[i], 1);
            d[i][i] = 0;
            h[i][i] = 0;
        }
        return new PartitionTopology(d, h, false);
    }

    public static PartitionTopology read(Path file, int k) throws IOException {
        List<String> lines = new ArrayList<>();
        double costScale = 1.0;
        boolean scaleSeen = false;
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("% rapidwright_cost_scale=")) {
                if (scaleSeen) throw new IllegalArgumentException("Duplicate topology cost scale");
                scaleSeen = true;
                costScale = Double.parseDouble(trimmed.substring("% rapidwright_cost_scale=".length()));
                if (!Double.isFinite(costScale) || costScale <= 0) {
                    throw new IllegalArgumentException("Topology cost scale must be finite and positive");
                }
            } else if (!trimmed.startsWith("%")) {
                lines.add(trimmed);
            }
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Empty topology");
        }
        String[] header = lines.get(0).split("\\s+");
        if (header.length != 3 || Integer.parseInt(header[0]) != k
                || !"1".equals(header[2]) || lines.size() != k + 1) {
            throw new IllegalArgumentException("Topology must be a weighted METIS graph, fmt=1, with k nodes");
        }
        double[][] direct = new double[k][k];
        long arcs = 0;
        for (int i = 0; i < k; i++) {
            Arrays.fill(direct[i], Double.POSITIVE_INFINITY);
            direct[i][i] = 0;
            if (lines.get(i + 1).isEmpty()) {
                continue;
            }
            String[] fields = lines.get(i + 1).split("\\s+");
            if (fields.length % 2 != 0) {
                throw new IllegalArgumentException("Topology adjacency requires neighbor/cost pairs");
            }
            for (int j = 0; j < fields.length; j += 2) {
                int v = Integer.parseInt(fields[j]) - 1;
                int cost = Integer.parseInt(fields[j + 1]);
                if (v < 0 || v >= k || v == i || cost < 1 || Double.isFinite(direct[i][v])) {
                    throw new IllegalArgumentException("Invalid or duplicate topology link");
                }
                direct[i][v] = cost;
                arcs++;
            }
        }
        if (arcs != 2L * Integer.parseInt(header[1])) {
            throw new IllegalArgumentException("Topology edge count mismatch");
        }
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                if (direct[i][j] != direct[j][i]) {
                    throw new IllegalArgumentException("Topology links must be symmetric");
                }
            }
        }
        int[][] minimumHops = bfsHops(direct);
        for (int m = 0; m < k; m++) {
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) {
                    direct[i][j] = Math.min(direct[i][j], direct[i][m] + direct[m][j]);
                }
            }
        }
        for (double[] row : direct) {
            for (int j = 0; j < row.length; j++) {
                if (!Double.isFinite(row[j])) {
                    throw new IllegalArgumentException("Disconnected FPGA topology");
                }
                row[j] /= costScale;
                if (!Double.isFinite(row[j])) throw new IllegalArgumentException("Normalized topology cost exceeds finite range");
            }
        }
        return new PartitionTopology(direct, minimumHops, true);
    }

    public double distance(int source, int sink) {
        return distance[source][sink];
    }

    /** Physical-link lower bound, independent of arbitrary METIS cost units. */
    public int minimumHops(int source, int sink) { return minimumHops[source][sink]; }
    public boolean hasDeclaredPhysicalAdjacency() { return declaredPhysicalAdjacency; }

    public int[][] minimumHopMatrix() {
        int[][] copy = new int[minimumHops.length][];
        for (int i = 0; i < copy.length; i++) copy[i] = minimumHops[i].clone();
        return copy;
    }

    static void validateMinimumHopMatrix(int[][] hops) {
        if (hops == null || hops.length == 0) throw new IllegalArgumentException("Physical hop matrix required");
        for (int u = 0; u < hops.length; u++) {
            if (hops[u] == null || hops[u].length != hops.length) throw new IllegalArgumentException("Square physical hop matrix required");
            for (int v = 0; v < hops.length; v++) if (hops[u][v] < 0 || (u == v ? hops[u][v] != 0 : hops[u][v] < 1)) throw new IllegalArgumentException("Invalid physical hop value");
        }
        int[] queue = new int[hops.length], distance = new int[hops.length];
        for (int root = 0; root < hops.length; root++) {
            Arrays.fill(distance, -1); distance[root] = 0;
            int head = 0, tail = 0; queue[tail++] = root;
            while (head < tail) {
                int u = queue[head++];
                for (int v = 0; v < hops.length; v++) {
                    if (hops[u][v] != hops[v][u]) throw new IllegalArgumentException("Asymmetric physical hop matrix");
                    if (hops[u][v] == 1 && distance[v] < 0) { distance[v] = distance[u] + 1; queue[tail++] = v; }
                }
            }
            for (int v = 0; v < hops.length; v++) if (distance[v] != hops[root][v]) throw new IllegalArgumentException("Hop matrix differs from BFS adjacency distances");
        }
    }

    private static int[][] bfsHops(double[][] direct) {
        int k = direct.length;
        int[][] hops = new int[k][k];
        int[] queue = new int[k];
        for (int root = 0; root < k; root++) {
            Arrays.fill(hops[root], -1);
            int head = 0, tail = 0;
            queue[tail++] = root;
            hops[root][root] = 0;
            while (head < tail) {
                int u = queue[head++];
                for (int v = 0; v < k; v++) {
                    if (v != u && Double.isFinite(direct[u][v]) && hops[root][v] < 0) {
                        hops[root][v] = hops[root][u] + 1;
                        queue[tail++] = v;
                    }
                }
            }
            if (tail != k) throw new IllegalArgumentException("Disconnected FPGA topology");
        }
        return hops;
    }
}
