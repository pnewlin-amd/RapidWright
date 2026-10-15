The partition frontend prepares an input with native Vivado before generating a
mapping:

```powershell
python tools/prepare_partition_input.py --input design.dcp --output build/input_export --vivado vivado
python tools/prepare_partition_input.py --input design.edf --output build/input_export --vivado vivado --part xcu250-figd2104-2-e
```

Use fresh output paths and configure your actual Vivado executable on PATH or
with `--vivado`; the examples use the command `vivado`. The high-level frontend
discovers the tool for you.

For EDIF, `--part` is optional when the input declares its part. The tool links
with `-mode out_of_context`, which prevents Vivado from inserting I/O buffers
into an OOC netlist. It saves `source.dcp` for subsequent native per-partition
resource reports. A DCP is opened unchanged and exported to `post_synth.edf`;
the original checkpoint remains the resource oracle. Neither path runs synthesis,
optimization, implementation, or partition wrapping.

`metadata.json` becomes `ready: true`, `status: PASS` only after native reports,
inventory generation, and source SHA-256 verification succeed. It identifies the
EDIF, checkpoint, native inventory, part, tool version, and hashes. The wrapper
also requires the native `PARTITION_INPUT_EXPORT_PASS` marker.

The already-open source design is sampled for up to 1,000 finite setup paths
under its existing constraints. `--timing-max-paths N` selects the bound;
`--timing-max-paths 0` skips STA. The `timing` subdirectory and metadata describe
availability and source identities. Unconstrained imports or timing-export
errors retain the valid base input and report structural fallback. This sample
does not represent timing after FPGA partitioning or inserted transport.

`leaf_primitives.tsv` contains the exact expanded native primitive collection,
including macro wrappers and their physical children. Its columns are `name`,
`ref_name`, `primitive_type`, `primitive_group`, `primitive_subgroup`, and
`is_sequential`. `logical_leaf_primitives.tsv` selects the outermost primitive
boundaries used by EDIF; the two inventories have separate histograms. The
expanded inventory is compatible with `prepare_native_resource_cells.py`.
`utilization.rpt` and `utilization_hierarchical.rpt` supply authoritative physical
resource counts. Primitive histograms do not infer physical CLB LUT packing.

Native property access is batched on typed Vivado objects; six plain column
files are zipped in Python to avoid slow per-cell Tcl/native-object iteration.
The Tcl exporter can also be called directly with positional arguments
`INPUT_DCP_OR_EDIF OUTPUT_DIR ?PART? ?TIMING_MAX_PATHS?`. Set `RW_PARTITION_PYTHON` to select Python
when calling Tcl directly.
