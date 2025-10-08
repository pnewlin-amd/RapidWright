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
import java.nio.file.Path;
import java.nio.file.Paths;

import com.xilinx.rapidwright.util.FileTools;

/**
 * Facilitates calling the MtKaHyPar partitioning tool.
 */
public class MtKaHyParPartitioner implements AbstractPartitioner {

    private static final String name = "MtKaHyPar";

    private Integer k;

    private Path inputFile;

    // user should specify >16 for very large designs
    private int numOfThreads = 2;

    // controls allowed partition imbalance; smaller is more balanced
    private double epsilon = 0.03;
    
    private String objective = "cut";
    
    private String presetType = "default";
    
    // Use current working directory
    private Path outputDir = null;

    private int seed = 0;

    // fixed vertices file passed to mtkahypar via -f (optional)
    private Path fixed_vertices_file = null;


    @Override
    public Integer runPartitioner() {
        //--verbose=true                 -> displays detailed information on the partitioning process
        //--show-detailed-timings=true   -> shows detailed sub-timings of each phase of the algorithm at the end of partitioning
        // compute effective preset; if fixed vertices are present, override incompatible presets
        String effectivePreset = presetType;
        if (fixed_vertices_file != null) {
            if ("deterministic".equals(effectivePreset) || "large_k".equals(effectivePreset)) {
                System.out.println("partitioner debug: fixed vertices present; overriding preset to 'quality'");
                effectivePreset = "quality";
            }
        }
        String cmd = name 
                + " -h " + getInputFile() 
                + " --preset-type=" + effectivePreset + " "
                + " -t " + numOfThreads
                + " -k " + getKPartitions() 
                + " --seed " + seed
                + " --epsilon " + epsilon
                + " --objective "+ objective
                + " --write-partition-file=true"
                + " --verbose=true"
                + " --show-detailed-timings=true";
        // append fixed vertices file if provided
        if (fixed_vertices_file != null) {
            cmd += " -f " + fixed_vertices_file;
        }

        
        String[] environ = null; // inherit env
        File runDir = getOutputDir().toFile();

        return FileTools.runCommand(cmd, true, environ, runDir);
    }

    @Override
    public String Name() {
        return name;
    }

    @Override
    public void setKPartitions(int k) {
        this.k = k;
    }

    @Override
    public void setInputFile(Path filePath) {
        this.inputFile = filePath;
    }

    @Override
    public Path getInputFile() {
        return inputFile;
    }

    public Path getOutputDir() {
        return outputDir == null ? Paths.get(System.getProperty("user.dir")) : outputDir;
    }

    // allow caller to override where external tool runs and writes outputs
    public void setOutputDir(Path d) {
        this.outputDir = d;
    }

    @Override
    public Path getOutputFile() {
        Path outputFile = getOutputDir()
                .resolve(getInputFile() + ".part" + getKPartitions() + ".epsilon" + epsilon
                + ".seed" + seed + ".KaHyPar");
        return outputFile;
    }

    @Override
    public Integer getKPartitions() {
        return k;
    }

    // set threads
    public void setNumThreads(int t) {
        this.numOfThreads = t;
    }

    // set epsilon
    public void setEpsilon(double e) {
        this.epsilon = e;
    }

    // set seed
    public void setSeed(int s) {
        this.seed = s;
    }

    public void setPresetType(String p) {
        this.presetType = p;
    }

    // set objective (defaults to "cut" if not set by user)
    public void setObjective(String o) {
        if (o != null && !o.isEmpty()) {
            this.objective = o;
        }
    }

    public String getObjective() {
        return this.objective;
    }

    // setter for fixed vertices file
    public void setFixedVerticesFile(Path p) {
        this.fixed_vertices_file = p;
    }

    // getter for fixed vertices file
    public Path getFixedVerticesFile() {
        return this.fixed_vertices_file;
    }


}
