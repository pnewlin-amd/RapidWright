# Mapping metrics and exact Vivado parity

Run all commands from the RapidWright checkout. The mapping pipeline uses
**Mt-KaHyPar v1.6.2 only**. The following audit steps evaluate the original
post-synthesis design and its final mapping; they do not produce a wrapped or
partitioned netlist.

The frontend already performs native utilization acceptance. Independent cut
and functional-control parity is a separate advanced audit; its source, actual
process exit and exact-map receipts must pass independently. Historical SHA
measurements below describe their original revisions. For the final J4 matrix
and portable evidence, see the [validation report](../doc/partition/VALIDATION_REPORT.tex).

The production mapping writer omits explicit GND/VCC primitive rules and ignores
constant-only subtrees when choosing hierarchy defaults. It still verifies every
nonconstant assignment by longest-prefix lookup. Uniform nonconstant children
collapse to their parent even if constant cells were assigned elsewhere. Native
resource manifests include the original constant cells under the final implicit
root/ancestor rule, so omission does not remove cells from the native census.

## What each number means

`primitive_counts.tsv` is an exact leaf `REF_NAME` histogram. `logical_resources.tsv`
counts logical LUT primitives, FF/latch primitives, RAM/DSP primitives and BRAM18
equivalents. `LUT6_2` contributes one logical primitive; distributed RAM, SRLs,
carry and mux primitives are separate categories. An arbitrary macro containing
the letters `LUT` is not a LUT. Unknown leaf types remain visible in the histogram.

Vivado can expand a logical RAM32M16/RAM32M macro into RAMD32/RAMS32 children.
`IS_PRIMITIVE` alone can include both the wrapper and its internal primitives.
For EDF parity, use `logical_leaf_primitives.tsv` and `logical_net_endpoints.tsv`
at original logical macro boundaries. The in-repository
`prepare_partition_input.py` produces `logical_leaf_primitives.tsv`; the
compressed census workflow below avoids requiring a separate flattened endpoint
TSV or an adjacent design repository.
Preserve the expanded physical inventory separately. The resource exporter
selects outer primitive boundaries, then lets native Vivado evaluate their
expanded hardware; internal macro nodes do not become mapping variables.

These counts cannot reproduce native packed CLB LUT utilization in general.
Vivado accounts for LUT combining, memory usage and device semantics in its
[utilization report](https://docs.amd.com/r/2025.1-English/ug906-vivado-design-analysis/Report-Utilization).
The [`report_utilization -cells` command](https://docs.amd.com/r/2025.1-English/ug835-vivado-tcl-commands/report_utilization)
can evaluate the original leaf collection assigned to each FPGA. The exporter
retains each complete native report and copies its **Used** column verbatim into
`vivado_resources.tsv`. It does not infer native utilization from cell-name
patterns or LUTNM/HLUTNM attributes.

## Evaluate one final mapping with native Vivado

First generate the mapping with `Partitioner` on the exported EDF. Use absolute
paths for input/output placeholders below. Prepare cell lists from the native
primitive inventory; this avoids slow iteration over Vivado's native collection
objects on large designs. The preparation binds both mapping and checkpoint
hashes, and native Vivado checks the inventory size before reporting.

```powershell
python tools/prepare_native_resource_cells.py `
  --mapping FINAL_MAPPING_TXT --checkpoint INPUT_DCP `
  --native-primitives REFERENCE_DIR/leaf_primitives.tsv `
  --output OUTPUT_NATIVE_REFERENCE_DIR

# Use your configured native Vivado command/path.
vivado -mode batch -source tools/report_partition_resources.tcl `
  -log OUTPUT_NATIVE_REFERENCE_DIR/vivado.log `
  -journal OUTPUT_NATIVE_REFERENCE_DIR/vivado.jou `
  -tclargs INPUT_DCP FINAL_MAPPING_TXT OUTPUT_NATIVE_REFERENCE_DIR
if ($LASTEXITCODE -ne 0) { throw 'Native utilization export failed' }

java --add-exports=java.base/sun.nio.ch=ALL-UNNAMED `
  -cp 'bin;jars/*' com.xilinx.rapidwright.edif.partition.VivadoResourceOracle `
  OUTPUT_NATIVE_REFERENCE_DIR/vivado_resources.tsv FINAL_MAPPING_TXT OUTPUT_PARTITION_DIR
```

Multiple mappings of the same checkpoint can be evaluated in one native process
by appending more `MAPPING_TXT OUTPUT_NATIVE_REFERENCE_DIR` argument pairs. Prepare
each output's cell manifest first. Native reports remain sequential.
When feedback generates the next mapping only after the previous measurement,
the [persistent resource session](NATIVE_RESOURCE_SESSION.md) can reuse the
loaded checkpoint. It preserves the report body and final Java acceptance gate;
the batch invocation above remains available.

The Java report step does not rerun the partitioner. It checks the mapping bytes
against the native export's `source_mapping.txt`, requires a completed export
with every mapped FPGA label, streams the original DCP's SHA256 to reject a
changed checkpoint, and rejects stale mapping fingerprints. It
preserves the previous `lut_report.txt` as `logical_lut_report.txt`, and writes
the actual native CLB LUT values to `lut_report.txt`. It also writes
`vivado_resource_summary.txt`, including physical LUT imbalance and the difference
between the sum of native partition subsets and the whole design. Native
categories unavailable for a device are `NA`, not fabricated zeroes.

The DCP must be the checkpoint from which the input EDF was exported. Keep the
native reference directory/provenance with the mapping. A changed mapping needs
a fresh native export. Values describe subsets of the original synthesized
netlist; future wrappers, transport logic and placement can change utilization.

For a hard native CLB LUT cap, append `--max-luts 5000000` to the Java report
command. For heterogeneous limits, append `--lut-caps CAPS_TSV`, with the exact
header `partition<TAB>clb_luts` and one nonnegative integer limit for every mapped
FPGA label. Both flags may be supplied; both constraints must pass. Equality is
allowed. Missing/extra/duplicate labels and native utilization above a cap fail
before reports are written. The frontend applies this native gate before
publishing its accepted `mapping.txt`; a failed search is not an infeasibility
proof. Native balance is a reported target, separate from the hard capacity cap.

Do **not** require the physical partition sum to equal whole-design LUT usage.
LUT combining can cross mapping boundaries. In the recorded
``sha4_timing_final_k4` result` (historical local artifact, not shipped),
the original design contains 9,131 logical LUT primitives but native Vivado
reports 6,492 CLB LUTs. The final FPGA A/B/C/D subsets report
1,831 / 1,638 / 1,629 / 1,458 CLB LUTs, totalling 6,556. Their physical imbalance
is 11.7145%, despite satisfying the native solver's 3% logical-LUT balance bound.
Every subset passes the requested 2,000-CLB-LUT hard cap. The separate 3% native
physical balance target is unmet, so the accepted status is
`CAP_VALIDATED_WITH_LIMITATIONS`. The refiner's logical balance is therefore
not a guarantee of native physical resource balance. The
`native summary` (historical local artifact, not shipped)
retains the 64-LUT subset-sum difference and measured imbalance.

## Audit directed cuts against independently exported Vivado endpoints

`fpga_io_cuts.txt` counts one canonical logical net for each distinct directed
source/destination FPGA pair. A broadcast to two FPGAs contributes two directed
destinations. `io_cuts.txt` instead counts undirected pairs of partitions touched
by a hyperedge. Neither metric is a Vivado package-pin count or a measured
physical link/serialization requirement. Clock/reset-only nets are classified
by actual primitive sink pins, not net names; mixed data/control use remains data.

Use the in-repository [compressed native census workflow](NATIVE_CUT_STREAM.md)
for an independent original-source endpoint export and streaming comparison.
Its CLI needs a completed source-export association receipt and actual native
exit evidence; the frontend source metadata alone is not that audit receipt.
For functional synchronous-control category parity, the v3 comparator also
requires its explicitly bound metadata sidecar and per-case control reports.

The older TSV comparison remains available for an independently exported
`net_endpoints.tsv` with the documented canonical logical-boundary semantics.
Supplying such a TSV is an advanced caller responsibility; it is not produced
automatically by the frontend. For that interface:

```powershell
java --add-exports=java.base/sun.nio.ch=ALL-UNNAMED -Xmx8g `
  -cp 'bin;jars/*' com.xilinx.rapidwright.edif.partition.FpgaIoCutAnalyzer `
  REFERENCE_DIR/post_synth.edf FINAL_MAPPING_TXT OUTPUT_AUDIT_DIR

python tools/compare_partition_reference.py `
  --mapping FINAL_MAPPING_TXT `
  --vivado-endpoints REFERENCE_DIR/net_endpoints.tsv `
  --vivado-primitives REFERENCE_DIR/leaf_primitives.tsv `
  --rw-crossings OUTPUT_AUDIT_DIR/fpga_net_crossings.tsv `
  --rw-primitives OUTPUT_PARTITION_DIR/primitive_counts.tsv `
  --output OUTPUT_AUDIT_DIR/vivado_parity.json
```

For frontend results, `OUTPUT_PARTITION_DIR` in the histogram argument is the
selected `candidate_NN` directory identified by `STATUS.json`; the accepted root
retains its cut report, while the candidate retains `primitive_counts.tsv` and
the full frontier/backend artifacts.

The comparison includes top I/O at the mapping's root FPGA and all clock/reset
crossings. It matches SHA256 fingerprints of sorted endpoint tuples, so hierarchy alias names
cannot hide a discrepancy. Missing/extra nets, wrong partition pairs, or a
different primitive histogram cause a nonzero exit. The accepted
`sha4_timing_final_k4` mapping passed with exactly **1,244 crossing nets and
1,348 directed net destinations** in both tools, with zero endpoint, pair or
primitive-histogram mismatches. Its
`parity report` (historical local artifact, not shipped)
and `identity receipt` (historical local artifact, not shipped)
bind the comparison to the unchanged mapping and completed native source export.

The same mapping's `hardware_quality.txt` reports **1,149 internal data
transfers**. That optimization metric excludes clock/reset sinks and does not
assign platform homes to top-level I/O. The complete endpoint audit includes
those connections using the emitted root assignment. These are different
scopes; neither number is a physical package-pin count or a TDM requirement.

## Regression checks

Native timing guidance is available separately:

```powershell
python tools/export_partition_timing.py --checkpoint SOURCE_DCP --edif SOURCE_EDF `
  --output TIMING_OUTPUT_DIR --max-paths 1000
```

`timing_paths.tsv` binds both the exact EDF and source checkpoint SHA256 and contains native slack,
requirement, launch/capture clocks and ordered data-path primitive owners.
`timing_path_details.tsv` retains native datapath delay. Vivado 2025.1 exposes
these scalars on [TIMING_PATH objects](https://docs.amd.com/r/2025.1-English/ug912-vivado-properties/TIMING_PATH).
The exporter uses native `get_pins -of_objects` timing-path data pins, with
consecutive owners collapsed and nonconsecutive hierarchy reentries preserved.
The sampled SHA worst-path order was checked against native `report_timing`.
The checkpoint's clock and timing constraints remain unchanged.

`timing_reference.json` reports sample counts, skipped nonfinite/unconstrained
rows, source hashes and availability. This bounded setup-path sample does not
measure all design paths or future inter-FPGA transport delay. A source lacking
finite constrained paths reports `available=false`; no clock is invented.
Vivado may omit unconstrained paths when constrained paths exist, as documented
in [UG906 timing report options](https://docs.amd.com/r/2025.1-English/ug906-vivado-design-analysis/Reports),
so the total number of unconstrained design paths is explicitly unknown.

```powershell
.\gradlew.bat testJava --tests 'com.xilinx.rapidwright.edif.partition.TestPartitionMetrics' --no-daemon
```

Tests cover repeated hierarchy types/names, canonical directionality, top ports,
clock/data mixed use, INOUT visibility, weighted hMETIS sections, primitive-level
mapping overrides, duplicate/overlapping frontiers, stale native references and
non-additive native packing counts. Mapping compression verifies every explicit
frontier assignment by longest-prefix lookup before writing the artifact.

Frontend publication regressions are available with:

```powershell
python -m unittest discover -s scripts/partition -p 'test_partition*.py' -v
```

Set `PARTITION_FRONTEND_NATIVE_FIXTURE` to a compatible immutable completed
candidate to opt into recorded-native integration checks. The default portable
suite skips that integration when its external fixture is absent. They replay solver/native outputs and execute the real Java provenance
and cap gate. They verify successful capacity acceptance with an explicitly unmet
balance target and final-cap rejection before mapping publication. Topology
coverage includes 28 FPGAs with the branch's legacy labels after `FPGA_Z`:
`FPGA_AZ`, `FPGA_BZ`.

The timing availability suite checks auto-only structural fallback, required
timing and explicit-delay failures, zero-weight disabling, sample-size bounds,
source/checkpoint identity, metadata/TSV hash agreement, cache refresh, and
immutable topology/timing/cap snapshots before accepted mapping publication.
