# Optional end-to-end runtime smoke

This small registered pipeline tests installation and command plumbing. It is
separate from the NVDLA, OpenTitan, mesh and AES experiments in the validation
report. It needs Vivado 2025.1, Java 17+, Python 3.11+/Tcl and Mt-KaHyPar v1.6.2.
Configure their PATH/environment or explicit options as in the main manual.

From a source checkout or unpacked runtime root, synthesize the fixture once:

```powershell
vivado -mode batch -source test/resources/partition/smoke/synthesize.tcl -tclargs build/smoke_source
```

The synthesis script requires a fresh output directory, targets an Artix-7
source device, preserves lane hierarchy and adds a 10 ns clock for this test
source. Its resulting DCP is the unpartitioned input. The supplied example
topology declares four VP1902 target devices; the source device is never used
to infer those target capacities.

```powershell
.\partitioner.cmd --check-tools
.\partitioner.cmd --input build/smoke_source/synth.dcp --system-topology test/resources/partition/smoke/topology.json --fpga-count 1 --output build/smoke_k1
.\partitioner.cmd --input build/smoke_source/synth.dcp --system-topology test/resources/partition/smoke/topology.json --fpga-count 2 --output build/smoke_k2
.\partitioner.cmd --input build/smoke_source/synth.dcp --system-topology test/resources/partition/smoke/topology.json --fpga-count 3 --output build/smoke_k3
# No count: use all four topology devices.
.\partitioner.cmd --input build/smoke_source/synth.dcp --system-topology test/resources/partition/smoke/topology.json --output build/smoke_all4
# Deliberately impossible cap: expect nonzero exit and no accepted mapping.txt.
.\partitioner.cmd --input build/smoke_source/synth.dcp --system-topology test/resources/partition/smoke/topology.json --fpga-count 2 --max-luts 1 --quality fast --output build/smoke_rejected
```

On Linux replace `.\partitioner.cmd` with `./partitioner`. Every output must
be new or empty. Successful cases record `PASS` in `STATUS.json`; inspect its
Native LUT counts/caps, selected candidate and timing-guidance field. The
one-FPGA case intentionally skips Mt-KaHyPar and partition-boundary STA.
The committed validation report/evidence records actual execution results;
these commands are also available for a new installation to repeat.
