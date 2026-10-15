/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.util.FileTools;

/**
 * Facilitates calling the MtKaHyPar partitioning tool.
 */
public class MtKaHyParPartitioner implements AbstractPartitioner {

    private static final String name = "MtKaHyPar";

    private Integer k;

    private Path inputFile;

    private int numOfThreads = 2;

    private double epsilon = 0.03; // Imbalance for partition
    
    private String objective = "km1"; // One transfer per destination FPGA, not one per cut net.
    
    private String presetType = "default";
    
    private Path outputDir = null; // Use current working directory

    private int seed = 0;

    private Path fixedVerticesFile = null; // Manual partition constraints

    private String executable = "mtkahypar";
    private String wslDistribution;
    private String libraryPath;
    private Path targetGraph;
    private int vcycles = 1;
    private Path observedOutputFile;
    private long[] individualPartWeights;

    public void setIndividualPartWeights(long[] weights) {
        if (weights == null) { individualPartWeights = null; return; }
        if (k == null || weights.length != k) throw new IllegalArgumentException("Part weights must match k");
        for (long weight : weights) {
            if (weight < 0 || weight > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Scalar part capacities must fit nonnegative signed integers");
            }
        }
        individualPartWeights = weights.clone();
    }

    private String toolPath(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        if (wslDistribution != null && value.matches("^[A-Za-z]:.*")) {
            return "/mnt/" + Character.toLowerCase(value.charAt(0))
                    + value.substring(2).replace('\\', '/');
        }
        return value;
    }

    private List<String> commandPrefix() {
        List<String> command = new ArrayList<>();
        if (wslDistribution != null) {
            command.add("wsl.exe");
            command.add("-d");
            command.add(wslDistribution);
            command.add("--");
            if (libraryPath != null) {
                command.add("env");
                command.add("LD_LIBRARY_PATH=" + libraryPath);
            }
        }
        command.add(executable);
        return command;
    }

    private ProcessBuilder processBuilder(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (wslDistribution == null && libraryPath != null) {
            builder.environment().put("LD_LIBRARY_PATH", libraryPath);
        }
        return builder;
    }

    /** Reject an accidental upgrade: experiments are explicitly pinned to v1.6.2. */
    private void checkVersion() {
        List<String> command = commandPrefix();
        command.add("--version");
        try {
            Process process = processBuilder(command).start();
            StringBuilder version = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    version.append(line).append('\n');
                }
            }
            if (process.waitFor() != 0 || !version.toString().trim().equals("Mt-KaHyPar v1.6.2")) {
                throw new IllegalArgumentException("Only Mt-KaHyPar v1.6.2 is permitted; got: " + version);
            }
            System.out.println("Partitioner backend verified: " + version.toString().trim());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Executes the MtKaHyPar partitioning tool with configured parameters.
     * @return Exit code from the partitioner execution
     */
    @Override
    public Integer runPartitioner() {
        checkVersion();
        observedOutputFile = null;
        // If fixed vertices are present, override incompatible presets
        String effectivePreset = presetType;
        if (fixedVerticesFile != null) {
            if ("deterministic".equals(effectivePreset) || "large_k".equals(effectivePreset)) {
                System.out.println("partitioner debug: fixed vertices present; " +
                   "overriding preset to quality");
                effectivePreset = "quality";
            }
        }
        List<String> cmd = commandPrefix();
        java.util.Collections.addAll(cmd, "-h", toolPath(getInputFile()),
                "--preset-type=" + effectivePreset, "-t", Integer.toString(numOfThreads),
                "-k", k.toString(), "--seed", Integer.toString(seed), "--epsilon",
                Double.toString(epsilon), "--objective", objective,
                "--write-partition-file=true", "--verbose=true", "--show-detailed-timings=true",
                "--partition-output-folder", toolPath(getOutputDir()),
                "--num-vcycles", Integer.toString(vcycles));
        if (fixedVerticesFile != null) {
            cmd.add("-f");
            cmd.add(toolPath(fixedVerticesFile));
        }
        if (targetGraph != null) {
            cmd.add("-g");
            cmd.add(toolPath(targetGraph));
        }
        if (individualPartWeights != null) {
            cmd.add("--part-weights");
            for (long weight : individualPartWeights) cmd.add(Long.toString(weight));
        }
        try {
            Process process = processBuilder(cmd).directory(getOutputDir().toFile()).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    String trimmed = line.trim();
                    if (trimmed.startsWith("Partition File:")) {
                        String output = trimmed.substring("Partition File:".length()).trim().replace('\\', '/');
                        String fileName = output.substring(output.lastIndexOf('/') + 1);
                        String prefix = inputFile.getFileName() + ".part" + k + ".epsilon";
                        if (!fileName.startsWith(prefix) || !fileName.endsWith(".seed" + seed + ".KaHyPar")) {
                            throw new IllegalStateException("Unexpected native output filename: " + output);
                        }
                        observedOutputFile = getOutputDir().resolve(fileName);
                    }
                }
            }
            int status = process.waitFor();
            if (status != 0) {
                throw new IllegalStateException("Mt-KaHyPar failed with exit status " + status);
            }
            if (observedOutputFile == null || !java.nio.file.Files.isRegularFile(observedOutputFile)) {
                throw new IllegalStateException("Mt-KaHyPar did not announce and create its solution file");
            }
            return status;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the name of this partitioner.
     * @return The partitioner name "MtKaHyPar"
     */
    @Override
    public String Name() {
        return name;
    }

    /**
     * Sets the number of partitions to create.
     * @param k The number of partitions
     */
    @Override
    public void setKPartitions(int k) {
        this.k = k;
    }

    /**
     * Sets the input hypergraph file path.
     * @param filePath Path to the input .hgr file
     */
    @Override
    public void setInputFile(Path filePath) {
        this.inputFile = filePath;
    }

    /**
     * Gets the input hypergraph file path.
     * @return Path to the input file
     */
    @Override
    public Path getInputFile() {
        return inputFile;
    }

    /**
     * Gets the output directory for partition results.
     * @return Path to output directory
     */
    public Path getOutputDir() {
        return outputDir == null ? Paths.get(System.getProperty("user.dir")) : outputDir;
    }

    /**
     * Sets the output directory for partition results.
     * @param d Path to output directory
     */
    public void setOutputDir(Path d) {
        this.outputDir = d;
    }

    /**
     * Gets the expected output partition file path.
     * @return Path to the partition solution file
     */
    @Override
    public Path getOutputFile() {
        if (observedOutputFile != null) { return observedOutputFile; }
        Path outputFile = getOutputDir()
                .resolve(getInputFile().getFileName() + ".part" + getKPartitions() + ".epsilon" + epsilon
                + ".seed" + seed + ".KaHyPar");
        return outputFile;
    }

    /**
     * Gets the number of partitions configured.
     * @return The number of partitions
     */
    @Override
    public Integer getKPartitions() {
        return k;
    }

    /**
     * Sets the number of threads for partitioning.
     * @param t Number of threads to use
     */
    public void setNumThreads(int t) {
        if (t < 1) { throw new IllegalArgumentException("threads must be positive"); }
        this.numOfThreads = t;
    }

    /**
     * Sets the partition imbalance tolerance.
     * @param e Epsilon value for imbalance
     */
    public void setEpsilon(double e) {
        if (!Double.isFinite(e) || e < 0) {
            throw new IllegalArgumentException("epsilon must be finite and nonnegative");
        }
        this.epsilon = e;
    }

    public double getEpsilon() { return epsilon; }
    public void setExecutable(String value) { executable = value; }
    public void setWslDistribution(String value) { wslDistribution = value; }
    public void setLibraryPath(String value) { libraryPath = value; }
    public void setTargetGraph(Path value) { targetGraph = value; objective = "steiner_tree"; }
    public void setVcycles(int value) {
        if (value < 0) { throw new IllegalArgumentException("num_vcycles must be nonnegative"); }
        vcycles = value;
    }

    /**
     * Sets the random seed for partitioning.
     * @param s Seed value for reproducibility
     */
    public void setSeed(int s) {
        this.seed = s;
    }

    public int getSeed() { return seed; }

    /**
     * Sets the partitioner preset configuration type.
     * @param p Preset type like "default" or "deterministic"
     */
    public void setPresetType(String p) {
        this.presetType = p;
    }

    /**
     * Sets the partitioning objective function.
     * @param o Objective like "cut", "km1", or "soed"
     */
    public void setObjective(String o) {
        if (o != null && !o.isEmpty()) {
            if (!java.util.Arrays.asList("cut", "km1", "soed", "steiner_tree").contains(o)) {
                throw new IllegalArgumentException("Unsupported objective: " + o);
            }
            this.objective = o;
        }
    }

    /**
     * Gets the current partitioning objective function.
     * @return The objective function name
     */
    public String getObjective() {
        return this.objective;
    }

    /**
     * Sets the fixed vertices constraint file path.
     * @param p Path to the .fix file
     */
    public void setFixedVerticesFile(Path p) {
        this.fixedVerticesFile = p;
    }

    /**
     * Gets the fixed vertices constraint file path.
     * @return Path to the .fix file or null
     */
    public Path getFixedVerticesFile() {
        return this.fixedVerticesFile;
    }


}
