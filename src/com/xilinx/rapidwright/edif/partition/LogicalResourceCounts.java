/* Copyright (c) 2026, Advanced Micro Devices, Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0. */
package com.xilinx.rapidwright.edif.partition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Exact logical primitive census, cached by cell type. The primitive histogram
 * can be compared directly with REF_NAME counts on the identical Vivado logical
 * netlist. Scalar totals count primitives, except BRAM18 which uses 18Kb units.
 * They do not predict packed LUT sites, timing, or package I/O utilization.
 * Expanded device macros and unfamiliar black boxes remain visible by type.
 */
public final class LogicalResourceCounts {
    // Exact HDI primitive names present in the bundled 2025.1 UNISIM library.
    private static final Set<String> EXPANDED_LUT_RAM = Set.of(
            "RAMD32", "RAMD32M64", "RAMD64E", "RAMD64E5",
            "RAMS32", "RAMS64E", "RAMS64E1", "RAMS64E5");

    public static final class Counts {
        public final long logicLuts, flipFlops, latches, lutRamPrimitives;
        public final long shiftRegisterPrimitives, bram18Equivalent, dsp, uram;
        private final Map<String, Long> histogram;
        private final Map<String, Long> primitiveResources;

        private Counts(Map<String, Long> histogram, Map<String, Long> primitiveResources) {
            this.histogram = Collections.unmodifiableMap(new TreeMap<>(histogram));
            this.primitiveResources = Collections.unmodifiableMap(new TreeMap<>(primitiveResources));
            long l = 0, f = 0, a = 0, r = 0, s = 0, b = 0, d = 0, u = 0;
            for (Map.Entry<String, Long> entry : primitiveResources.entrySet()) {
                String type = entry.getKey();
                long n = entry.getValue();
                if (logicDiscoveryPolicy.isLogicLUT(type)) l = Math.addExact(l, n);
                if (isFlipFlop(type)) f = Math.addExact(f, n);
                if (isLatch(type)) a = Math.addExact(a, n);
                if (isLutRam(type)) r = Math.addExact(r, n);
                if (isShiftRegister(type)) s = Math.addExact(s, n);
                if (type.matches("(RAMB|FIFO)18.*")) b = Math.addExact(b, n);
                if (type.matches("(RAMB|FIFO)36.*")) b = Math.addExact(b, Math.multiplyExact(2, n));
                if (type.matches("DSP(48(A1?|E[125]?)?|58C?|CPLX|FP32)")) d = Math.addExact(d, n);
                if (type.matches("URAM288(_BASE)?")) u = Math.addExact(u, n);
            }
            logicLuts = l; flipFlops = f; latches = a; lutRamPrimitives = r;
            shiftRegisterPrimitives = s; bram18Equivalent = b; dsp = d; uram = u;
        }

        public Map<String, Long> primitiveHistogram() { return histogram; }
        public long primitiveCount() {
            long total = 0;
            for (long n : histogram.values()) total = Math.addExact(total, n);
            return total;
        }
    }

    private final Map<EDIFCell, Counts> cache = new IdentityHashMap<>();
    private final Set<EDIFCell> active = Collections.newSetFromMap(new IdentityHashMap<EDIFCell, Boolean>());

    public Counts count(EDIFNetlist netlist) { return count(netlist.getTopCell()); }

    public Counts count(EDIFCell cell) {
        Counts old = cache.get(cell);
        if (old != null) return old;
        if (!active.add(cell)) throw new IllegalArgumentException("Recursive EDIF hierarchy at " + cell.getName());
        Map<String, Long> histogram = new TreeMap<>();
        Map<String, Long> resources = new TreeMap<>();
        if (cell.isLeafCellOrBlackBox()) {
            histogram.put(cell.getName(), 1L);
            // A user black box may share a UNISIM name; preserve its histogram
            // entry without asserting an implementation/resource classification.
            if (cell.isPrimitive()) resources.put(cell.getName(), 1L);
        } else {
            for (EDIFCellInst child : cell.getCellInsts()) {
                Counts childCounts = count(child.getCellType());
                for (Map.Entry<String, Long> entry : childCounts.histogram.entrySet()) {
                    histogram.put(entry.getKey(), Math.addExact(histogram.getOrDefault(entry.getKey(), 0L), entry.getValue()));
                }
                for (Map.Entry<String, Long> entry : childCounts.primitiveResources.entrySet()) {
                    resources.put(entry.getKey(), Math.addExact(resources.getOrDefault(entry.getKey(), 0L), entry.getValue()));
                }
            }
        }
        Counts value = new Counts(histogram, resources);
        active.remove(cell);
        cache.put(cell, value);
        return value;
    }

    public static boolean isFlipFlop(String type) {
        return type != null && type.matches("FD(C|CE|CP|CPE|E|P|PE|R|RE|RS|RSE|S|SE)?(_1)?");
    }

    public static boolean isLatch(String type) {
        return type != null && type.matches("LD(C|CE|CP|CPE|E|P|PE)?(_1)?");
    }

    public static boolean isLutRam(String type) {
        return type != null && (EXPANDED_LUT_RAM.contains(type)
                || type.matches("RAM(16|32|64|128|256|512)(X[0-9]+[SD](R[0-9]+|W)?|M[0-9]*)(_[1-8])?"));
    }

    public static boolean isShiftRegister(String type) {
        return type != null && type.matches("SRLC?(16|32)E?(_1)?");
    }

    public static boolean isClockPin(String type, String pin) {
        if (type == null || pin == null) return false;
        if (isFlipFlop(type)) return "C".equals(pin);
        if (isLatch(type)) return "G".equals(pin);
        if (isLutRam(type) || isShiftRegister(type)) return "CLK".equals(pin) || "WCLK".equals(pin);
        if (type.startsWith("RAMB") || type.startsWith("FIFO") || type.startsWith("URAM")) {
            return pin.startsWith("CLK") || "RDCLK".equals(pin) || "WRCLK".equals(pin);
        }
        if (type.startsWith("DSP")) return "CLK".equals(pin);
        if (type.startsWith("BUFG") || type.startsWith("BUFH") || type.startsWith("BUFR")
                || type.startsWith("BUFIO") || type.startsWith("BUFDIV")) {
            return "I".equals(pin) || "I0".equals(pin) || "I1".equals(pin);
        }
        return false;
    }

    public static boolean isResetPin(String type, String pin) {
        if (type == null || pin == null) return false;
        if (isFlipFlop(type) || isLatch(type)) {
            return "R".equals(pin) || "S".equals(pin) || "CLR".equals(pin) || "PRE".equals(pin);
        }
        return (type.startsWith("RAMB") || type.startsWith("FIFO") || type.startsWith("URAM")
                || type.startsWith("DSP")) && pin.startsWith("RST");
    }

    public static void writeHistogram(Path output, Counts counts) throws IOException {
        List<String> rows = new ArrayList<>();
        rows.add("REF_NAME\tCOUNT");
        for (Map.Entry<String, Long> entry : counts.histogram.entrySet()) {
            rows.add(entry.getKey() + "\t" + entry.getValue());
        }
        Files.write(output, rows, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: input.edf primitive_histogram.tsv");
        Counts counts = new LogicalResourceCounts().count(EDIFTools.readEdifFile(java.nio.file.Paths.get(args[0])));
        writeHistogram(java.nio.file.Paths.get(args[1]), counts);
        System.out.println("LOGICAL_RESOURCES LUT=" + counts.logicLuts + " FF=" + counts.flipFlops
                + " LATCH=" + counts.latches + " LUTRAM_PRIMITIVES=" + counts.lutRamPrimitives
                + " SRL_PRIMITIVES=" + counts.shiftRegisterPrimitives + " BRAM18=" + counts.bram18Equivalent
                + " DSP=" + counts.dsp + " URAM=" + counts.uram);
    }
}
