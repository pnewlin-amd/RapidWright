/*
 *
 * Copyright (c) 2018-2022, Xilinx, Inc.
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Perry Newlin
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

package com.xilinx.rapidwright.examples;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.*;

/**
 * SnakePathCLI - A tool to detect and report long combinatorial paths (snake paths) in FPGA designs.
 * 
 * This tool analyzes EDIF or DCP files to identify long combinatorial paths between sequential elements,
 * which can indicate problematic timing paths in both flat and partitioned designs.
 * 
 * Usage:
 *   rapidwright SnakePathCLI [options] <input_file>
 * 
 * Options:
 *   -mh, --min-hops <int>     Minimum number of combinatorial hops to report (default: 1)
 *   -n, --report-top-n <int>  Number of longest paths to display (default: 10)
 *   -o, --output <file>       Output file (default: stdout)
 *   <input_file>              Path to .dcp or .edif file (required)
 */
public class SnakePathCLI {
    
    // Set of sequential primitive types
    private static final Set<String> SEQUENTIAL_TYPES = new HashSet<>(Arrays.asList(
        "FDRE", "FDSE", "FDCE", "FDPE",           // Flip-flops
        "RAMB18E2", "RAMB36E2",                   // Block RAMs
        "DSP48E2", "DSP_ALU", "DSP_A_B_DATA",     // DSP blocks
        "DSP_C_DATA", "DSP_M_DATA", "DSP_MULTIPLIER",
        "DSP_OUTPUT", "DSP_PREADD", "DSP_PREADD_DATA"
    ));
    
    // Set of combinatorial primitive types
    private static final Set<String> COMBINATORIAL_TYPES = new HashSet<>(Arrays.asList(
        "LUT1", "LUT2", "LUT3", "LUT4", "LUT5", "LUT6",   // LUTs
        "MUXF7", "MUXF8", "MUXF9",                        // Muxes
        "CARRY8",                                         // Carry chains
        "AND2B1L", "OR2L"                                 // Other combinatorial primitives
    ));
    
    private EDIFNetlist netlist;
    private PrintStream out;
    private int minHops;
    private int reportTopN;
    
    /**
     * Class to represent a combinatorial path
     */
    private static class CombiPath implements Comparable<CombiPath> {
        String startPoint;
        String endPoint;
        int hopCount;
        List<String> pathElements;
        
        CombiPath(String start, String end, int hops, List<String> elements) {
            this.startPoint = start;
            this.endPoint = end;
            this.hopCount = hops;
            this.pathElements = new ArrayList<>(elements);
        }
        
        @Override
        public int compareTo(CombiPath other) {
            // Sort by hop count descending (longest paths first)
            return Integer.compare(other.hopCount, this.hopCount);
        }
        
        @Override
        public String toString() {
            return String.format("Path: %s -> %s (%d hops)", startPoint, endPoint, hopCount);
        }
    }
    
    public SnakePathCLI(EDIFNetlist netlist, PrintStream out, int minHops, int reportTopN) {
        this.netlist = netlist;
        this.out = out;
        this.minHops = minHops;
        this.reportTopN = reportTopN;
    }
    
    /**
     * Main analysis method - finds all combinatorial paths
     */
    public void analyze() {
        EDIFCell topCell = netlist.getTopCell();
        if (topCell == null) {
            out.println("ERROR: No top cell found in netlist");
            return;
        }
        
        out.println("Analyzing design: " + topCell.getName());
        out.println("Searching for paths with >= " + minHops + " combinatorial hops...\n");
        
        // Find all sequential startpoints (FF Q outputs, etc.)
        List<EDIFCellInst> startpoints = findStartpoints(topCell);
        out.println("Found " + startpoints.size() + " start points to analyze.");
        
        // For each startpoint, trace forward through combinatorial logic
        List<CombiPath> allPaths = new ArrayList<>();
        for (EDIFCellInst startFF : startpoints) {
            List<CombiPath> paths = traceFromStartpoint(startFF);
            allPaths.addAll(paths);
        }
        
        // Filter paths by minimum hops
        List<CombiPath> filteredPaths = new ArrayList<>();
        for (CombiPath path : allPaths) {
            if (path.hopCount >= minHops) {
                filteredPaths.add(path);
            }
        }
        
        out.println("Analysis complete. Found " + filteredPaths.size() + " paths meeting criteria.\n");
        
        // Sort and report top N paths
        Collections.sort(filteredPaths);
        int numToReport = Math.min(reportTopN, filteredPaths.size());
        
        out.println("================================================================================");
        out.println("                  Top " + numToReport + " Longest Combinatorial Paths");
        out.println("================================================================================\n");
        
        if (filteredPaths.isEmpty()) {
            out.println("No paths found meeting the criteria.");
        } else {
            for (int i = 0; i < numToReport; i++) {
                CombiPath path = filteredPaths.get(i);
                out.println("Path #" + (i + 1) + ": " + path.hopCount + " combinatorial hops");
                out.println("  Start: " + path.startPoint);
                out.println("  End:   " + path.endPoint);
                out.println("  Path trace:");
                for (String element : path.pathElements) {
                    out.println("    " + element);
                }
                out.println();
            }
        }
    }
    
    /**
     * Find all sequential startpoints in the design
     */
    private List<EDIFCellInst> findStartpoints(EDIFCell topCell) {
        List<EDIFCellInst> startpoints = new ArrayList<>();
        
        for (EDIFCellInst inst : topCell.getCellInsts()) {
            String cellType = inst.getCellType().getName();
            if (isSequential(cellType)) {
                startpoints.add(inst);
            }
        }
        
        return startpoints;
    }
    
    /**
     * Trace forward from a startpoint through combinatorial logic
     */
    private List<CombiPath> traceFromStartpoint(EDIFCellInst startFF) {
        List<CombiPath> paths = new ArrayList<>();
        
        // Get the Q output of the flip-flop
        EDIFPortInst qPort = startFF.getPortInst("Q");
        if (qPort == null) {
            return paths;
        }
        
        EDIFNet qNet = qPort.getNet();
        if (qNet == null) {
            return paths;
        }
        
        // Trace forward from this net
        List<String> pathTrace = new ArrayList<>();
        pathTrace.add(startFF.getName() + "/Q");
        
        traceForward(qNet, startFF.getName() + "/Q", 0, pathTrace, paths, new HashSet<>());
        
        return paths;
    }
    
    /**
     * Recursively trace forward through combinatorial logic
     */
    private void traceForward(EDIFNet currentNet, String startPoint, int hopCount, 
                             List<String> pathTrace, List<CombiPath> foundPaths, Set<String> visited) {
        String netKey = currentNet.getName();
        if (visited.contains(netKey)) {
            return;
        }
        visited.add(netKey);
        
        // Check all sinks of this net
        for (EDIFPortInst sink : currentNet.getPortInsts()) {
            if (sink.isOutput()) {
                continue; // Skip the driver
            }
            
            EDIFCellInst sinkCell = sink.getCellInst();
            if (sinkCell == null) {
                continue; // Top-level port
            }
            
            String cellType = sinkCell.getCellType().getName();
            String sinkPin = sinkCell.getName() + "/" + sink.getName();
            
            // Check if we've reached a sequential endpoint
            if (isSequential(cellType)) {
                // Found an endpoint - record this path
                List<String> completePath = new ArrayList<>(pathTrace);
                completePath.add(sinkPin);
                CombiPath path = new CombiPath(startPoint, sinkPin, hopCount, completePath);
                foundPaths.add(path);
                continue;
            }
            
            // Check if this is combinatorial logic
            if (isCombinatorial(cellType)) {
                // Continue tracing through this combinatorial cell
                for (EDIFPortInst output : sinkCell.getPortInsts()) {
                    if (output.isOutput()) {
                        EDIFNet outputNet = output.getNet();
                        if (outputNet != null) {
                            List<String> newTrace = new ArrayList<>(pathTrace);
                            newTrace.add(sinkPin);
                            newTrace.add(sinkCell.getName() + "/" + output.getName());
                            
                            traceForward(outputNet, startPoint, hopCount + 1, newTrace, foundPaths, visited);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Check if a cell type is sequential
     */
    private boolean isSequential(String cellType) {
        for (String seqType : SEQUENTIAL_TYPES) {
            if (cellType.startsWith(seqType)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if a cell type is combinatorial
     */
    private boolean isCombinatorial(String cellType) {
        for (String combType : COMBINATORIAL_TYPES) {
            if (cellType.startsWith(combType)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Print usage information
     */
    private static void usage() {
        System.out.println("SnakePathCLI - Detect and report long combinatorial paths in FPGA designs");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  rapidwright SnakePathCLI [options] <input_file>");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -mh, --min-hops <int>      Minimum number of combinatorial hops (default: 1)");
        System.out.println("  -n, --report-top-n <int>   Number of longest paths to display (default: 10)");
        System.out.println("  -o, --output <file>        Output file (default: stdout)");
        System.out.println("  <input_file>               Path to .dcp or .edif file (required)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  rapidwright SnakePathCLI -mh 5 -n 10 design.dcp");
        System.out.println("  rapidwright SnakePathCLI -mh 0 -n 5 -o report.txt design.edf");
    }
    
    /**
     * Main entry point
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        
        String inputFile = null;
        String outputFile = null;
        int minHops = 1;
        int reportTopN = 10;
        
        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-mh") || args[i].equals("--min-hops")) {
                if (i + 1 < args.length) {
                    try {
                        minHops = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.println("ERROR: Invalid value for -mh/--min-hops: " + args[i]);
                        usage();
                        return;
                    }
                } else {
                    System.err.println("ERROR: -mh/--min-hops requires an integer value");
                    usage();
                    return;
                }
            } else if (args[i].equals("-n") || args[i].equals("--report-top-n")) {
                if (i + 1 < args.length) {
                    try {
                        reportTopN = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.println("ERROR: Invalid value for -n/--report-top-n: " + args[i]);
                        usage();
                        return;
                    }
                } else {
                    System.err.println("ERROR: -n/--report-top-n requires an integer value");
                    usage();
                    return;
                }
            } else if (args[i].equals("-o") || args[i].equals("--output")) {
                if (i + 1 < args.length) {
                    outputFile = args[++i];
                } else {
                    System.err.println("ERROR: -o/--output requires a filename");
                    usage();
                    return;
                }
            } else if (args[i].equals("-h") || args[i].equals("--help")) {
                usage();
                return;
            } else if (!args[i].startsWith("-")) {
                inputFile = args[i];
            } else {
                System.err.println("ERROR: Unknown option: " + args[i]);
                usage();
                return;
            }
        }
        
        if (inputFile == null) {
            System.err.println("ERROR: Input file required");
            usage();
            return;
        }
        
        Path inputPath = Paths.get(inputFile);
        
        try {
            // Read netlist (EDIF or DCP)
            EDIFNetlist netlist;
            boolean isDcp = inputPath.toString().toLowerCase().endsWith(".dcp");
            
            if (isDcp) {
                Design design = Design.readCheckpoint(inputPath.toString());
                netlist = design.getNetlist();
                int encryptedCells = netlist.getEncryptedCells().size();
                if (encryptedCells > 0) {
                    System.err.println("ERROR: Encrypted DCP detected (encryptedCells=" + encryptedCells + 
                                     "). Encrypted DCPs are unsupported.");
                    System.exit(1);
                }
            } else {
                netlist = EDIFTools.readEdifFile(inputPath);
            }
            
            // Set up output stream
            PrintStream out = System.out;
            if (outputFile != null) {
                out = new PrintStream(new FileOutputStream(outputFile));
            }
            
            // Run analysis
            SnakePathCLI analyzer = new SnakePathCLI(netlist, out, minHops, reportTopN);
            analyzer.analyze();
            
            // Close output file if needed
            if (outputFile != null) {
                out.close();
                System.out.println("Report written to: " + outputFile);
            }
            
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
