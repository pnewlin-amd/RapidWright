# Hardware-aware FPGA partition mapping

The partitioner reads an original post-synthesis netlist and a system topology,
then writes an FPGA assignment and reports. It uses **Mt-KaHyPar v1.6.2 only**
for initial partitioning, followed by topology, register-free path and sampled
source-timing analysis, refinement and native Vivado resource validation.

A normal run needs a netlist and topology. The topology must identify its
actual FPGA parts, directly or through a companion file; otherwise supply
`--target-part`. The default uses every FPGA in the topology. Use `--fpga-count`
or `--fpga-ids` to select a subset.

The output is a mapping. The tool does not wrap or regroup a netlist, insert
transport, replicate logic, retime registers, place or route the partitions.
It does not infer an achieved DUT frequency or promise a global optimum.

- [Build and requirements](#build-and-requirements)
- [Quick start](#quick-start)
- [Topology and target devices](#topology-and-target-devices)
- [Quality controls](#quality-controls)
- [All high-level options](#all-high-level-options)
- [Outputs and acceptance](#outputs-and-acceptance)
- [Path, timing and resource model](#path-timing-and-resource-model)
- [Tests and troubleshooting](#tests-and-troubleshooting)
- [Validation report](VALIDATION_REPORT.tex) (standalone LaTeX source)
- [Research and engineering rationale](RESEARCH.md)

## Build and requirements

Choose a source checkout or the prebuilt runtime distribution. Both require
the external tools listed below; neither includes Vivado or Mt-KaHyPar.

**Runtime ZIP:** extract `rapidwright-partitioner-extreme-overhaul.zip`, open
the extracted directory, and run `partitioner.cmd --check-tools` on Windows or
`./partitioner --check-tools` on POSIX. It includes compiled `bin/` classes,
dependency `jars/`, Python/Tcl tools, catalog and documentation. Users do not
need Gradle to run this bundle. Configure dependencies before a design job.

**Source checkout:** the Python launcher uses compiled classes in `bin/` and
dependencies in `jars/`. Those generated directories are not committed. Build
a clean checkout first:

```powershell
# Windows, from the RapidWright checkout:
.\gradlew.bat compileJava --no-daemon
.\partitioner.cmd --help
```

```sh
# Linux, from the RapidWright checkout:
./gradlew compileJava --no-daemon
./partitioner --help
```

The Gradle wrapper downloads its build distribution and Maven dependencies on
the first build; an offline build needs those caches already populated. A
Python `rapidwright` pip installation alone does not provide these modified
Java classes or this command-line frontend.

To create the runtime ZIP from source, run `./gradlew partitionerDistZip`
(`.\gradlew.bat partitionerDistZip` on Windows). The archive is written to
`build/distributions/rapidwright-partitioner-extreme-overhaul.zip`; source DCPs,
large benchmark results, Vivado and Mt-KaHyPar are excluded.

The Python helpers use the standard library, including Tcl/Tk; no additional
pip package is required for the frontend.

| Requirement | Use |
|---|---|
| Python 3.11 or newer | Frontend and export/report helpers; `hashlib.file_digest` is required. |
| Python Tcl/Tk (`tkinter`) | Tcl-list decoding for source timing and independent endpoint audits. A display is not needed. |
| Java 17 or newer | Run the runtime bundle; use a JDK for a source build. Java 21 was used for the recorded large-design experiments. |
| Vivado 2025.1 | Original DCP/EDIF preparation, source STA sampling and authoritative original-source subset utilization. The shipped part catalog and primitive defaults are from 2025.1. |
| Mt-KaHyPar **1.6.2** | Initial multi-FPGA partitioning. Other versions are rejected. A one-FPGA assignment does not invoke it. |
| WSL, when explicitly selected on Windows | Run a Linux backend with `--wsl DISTRO`; not required for a directly executable backend. |

Install/build Mt-KaHyPar from the [v1.6.2 release instructions](https://github.com/kahypar/mt-kahypar/tree/v1.6.2).
Its executable and shared-library dependencies must be available in the selected
native or WSL environment. Building RapidWright does not install Vivado or
Mt-KaHyPar.

### Tool discovery

| Tool | Configuration |
|---|---|
| Python | `PYTHON` can name an interpreter. Otherwise `partitioner.cmd` uses `python` and the POSIX launcher uses `python3`. Direct `python scripts/partition/partitioner.py ...` is also supported. |
| Java | `--java`, then `JAVA_HOME`, then `java` on PATH. |
| Vivado | `--vivado`, then `VIVADO`, then `XILINX_VIVADO`, then `vivado` on PATH. |
| Mt-KaHyPar | `--mtkahypar-bin`, then `MTKAHYPAR_BIN`, then native PATH (`mtkahypar`, `MtKaHyPar`). WSL defaults to the command `mtkahypar`; supply its actual path/name when different. |
| Backend libraries | `--mtkahypar-lib` or `MTKAHYPAR_LIB` supplies a library directory when needed. |
| WSL distribution | `--wsl DISTRO` or `MTKAHYPAR_WSL_DISTRO` explicitly opts into WSL. No distribution is selected automatically. |

`VIVADO` names an executable or command; `XILINX_VIVADO` names the installation
directory. WSL backend executable/library paths refer to that Linux environment;
keep Python, Java and Vivado configured for the host running the frontend.

```powershell
# Optional configuration; replace paths with your installations.
$env:JAVA_HOME = 'C:\tools\jdk-21'
$env:XILINX_VIVADO = 'C:\Xilinx\2025.1\Vivado'
$env:MTKAHYPAR_BIN = '/opt/mt-kahypar-1.6.2/bin/MtKaHyPar'
$env:MTKAHYPAR_WSL_DISTRO = 'Ubuntu'
.\partitioner.cmd --check-tools
```

`--help` and `--check-tools` do not require a design or topology. Preflight
checks Python/Tcl, Java 17+, Vivado 2025.1, Mt-KaHyPar 1.6.2 and the compiled
classes/JARs. It prints JSON with overall `PASS`/`FAIL` and each check's result;
exit zero means all preflight checks passed, otherwise exit one. It does not
open a design, run the solver or create a result directory, and is not a fresh
build, capacity or timing proof. Run it before a long job. Do not select WSL
for a native backend executable.

## Quick start

From the checkout, with actual devices declared in the topology or its companion:

```powershell
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --output 'C:\results\run_01'
```

If the topology has no part declarations, add the actual target device:

```powershell
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --target-part VP1902 --output 'C:\results\run_02'
```

The equivalent POSIX command is:

```sh
./partitioner --input /designs/synth.dcp --system-topology /systems/machine.json --target-part VP1902 --output /results/run_02
```

No `--fpga-count` means **all devices in the topology**. For physical `c/1`
board JSON, select a smaller connected subset when needed:

```powershell
# First three sorted physical IDs:
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --target-part VP1902 --fpga-count 3 --output 'C:\results\three_fpgas'
# Explicit physical IDs:
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --target-part VP1902 --fpga-ids 0 2 --output 'C:\results\selected_pair'
```

The selected induced topology must be connected. Selection does not invent a
link through an unselected device. `--fpga-count` and `--fpga-ids` are mutually
exclusive. Subset selection is specific to physical board JSON; supply a graph
with the intended device count for simple JSON or METIS. Each selected FPGA
must contain useful nonconstant design logic;
clock, constant and I/O infrastructure alone cannot satisfy occupancy. A
one-FPGA run assigns the design directly and still checks native resources;
it uses one iteration, no external partition solver and no automatic source
timing sample.

Use a new or empty output directory. The accepted assignment is
**`<output>/mapping.txt`**. Without `--output`, a timestamped directory is
created under `partition_results/` in the current working directory. Inspect
`<output>/STATUS.json` and the selected reports after completion.

### Input netlists

- **DCP:** recommended. Vivado opens the original checkpoint unchanged,
  exports its logical EDIF/inventory and can sample existing source constraints.
- **EDIF (`.edf` or `.edif`):** imported with Vivado
  `link_design -mode out_of_context`, without optimization or I/O buffer
  insertion. Supply `--part` if the EDIF has no embedded source part. This
  import part is separate from the target devices.
- **Existing EDIF/DCP export pair:** pass both `--source-checkpoint` and
  `--native-primitives`. The EDIF directory must contain `export_receipt.json`
  binding those exact files. This advanced shortcut trusts that export
  association; it does not prove arbitrary EDIF/DCP electrical equivalence.
  Receipt bytes and source identities are snapshotted and rechecked.

An EDIF alone generally lacks the original checkpoint's clock constraints.
Prefer the DCP for timing guidance, or provide a compatible, source-bound
export with `--timing-paths`.

## Topology and target devices

The frontend accepts physical board JSON with `schema: "c/1"`, weighted METIS
graphs (`fmt=1`), and the simple graph JSON below.
[Physical board input](BOARD_TOPOLOGY_INPUT.md) defines ID/path preservation,
lane-cost conversion, connected subsets and sparse-link behavior. Input files
are read without modification.

This example declares three VP1902 targets and unequal link costs. `clb_luts`
is an optional additional hard policy ceiling, not device capacity:

```json
{
  "fpgas": [
    {"id": "FPGA_A", "part": "VP1902", "clb_luts": 5000000},
    {"id": "FPGA_B", "part": "VP1902", "clb_luts": 5000000},
    {"id": "FPGA_C", "part": "VP1902", "clb_luts": 5000000}
  ],
  "links": [
    {"u": "FPGA_A", "v": "FPGA_B", "cost": 1},
    {"u": "FPGA_B", "v": "FPGA_C", "cost": 2},
    {"u": "FPGA_A", "v": "FPGA_C", "cost": 3}
  ]
}
```

Actual target parts come from `--target-part`, per-device `part`, or a SHA-bound
assignment supplied as `--fpga-parts`. The frontend also automatically reads
**`<topology filename>.targets.json`**, for example
`machine.json.targets.json`. Overlapping declarations must agree. The source
checkpoint's part is never inferred as the target.

A companion can assign target parts to existing devices while leaving the
original board JSON unchanged:

```json
{
  "schema": "fpga_target_parts_v1",
  "topology_sha256": "SHA256_OF_EXACT_TOPOLOGY_FILE_BYTES",
  "parts_by_physical_id": {"0": "VP1902", "1": "VU19P"}
}
```

For a physical board, the assignment covers every original physical ID,
including devices outside a selected subset. METIS/simple inputs use their
canonical labels as IDs. Missing, conflicting, unknown or ambiguous targets
fail. The bundled Vivado 2025.1 catalog contains 5,566 part variants;
`--part-catalog` may relocate the exact approved catalog and identity sidecar,
not introduce arbitrary capacities.

Each effective per-FPGA limit is the minimum of all explicit uniform,
per-FPGA and topology caps, and
`floor(--lut-utilization * selected-part LUT_ELEMENTS)`.
The default utilization fraction is **0.75**; it can be tightened, never raised
above 0.75. VP1902 has 8,460,288 LUT elements, giving a 6,345,216 limit; VU19P
has 4,085,760, giving 3,064,320. Limits apply to Vivado original-source subset
**CLB LUT Used**. They exclude future wrapper/transport resources and do not
prove routing fit. See [target parts and balance](TARGET_PARTS_AND_BALANCE.md).

```powershell
# A stricter hard limit on every selected FPGA:
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --target-part VP1902 --fpga-count 4 --max-luts 5000000 --output 'C:\results\capped'
```

`--fpga-lut-caps caps.json` must cover every selected canonical label, for
example `{"FPGA_A": 200000, "FPGA_B": 250000}`. If combined with `--max-luts`,
the smaller bound applies. Native validation rechecks all caps before an
accepted mapping is published. Failure to find a candidate within a hard cap
is not proof that the requested problem is infeasible.

Generate illustrative board files without overwriting existing files:

```sh
python scripts/partition/generate_board_topology.py chain 3 examples/chain_3.json
python scripts/partition/generate_board_topology.py grid 4 7 examples/grid_28.json
```

These are synthetic systems, not verified hardware; supply actual parts
separately. Weighted target graphs require symmetric positive integer costs
and connected communication. Native Steiner mapping in v1.6.2 supports at most
64 target nodes. Lane counts supply a relative preference, not usable
bandwidth, directional capacity, TDM schedules, physical pins or link delay.

## Quality controls

Defaults aim for a useful first run: balanced effort, three feedback
iterations, three starting seeds, three cleanup passes, four backend threads, compact
mapping, sampled source timing when available, and topology-aware path ranking.
More effort or finer hierarchy does not guarantee better results; preserve
alternatives and compare their reports.

| Preset | Feedback iterations | Starting seeds per iteration | Cleanup passes |
|---|---:|---:|---:|
| `--quality fast` | 1 | 1 | 1 |
| `--quality balanced` (default) | 3 | 3 | 3 |
| `--quality thorough` | 5 | 7 | 6 |

`--iterations`, `--starts` and `--refine-passes` override individual values.
Zero cleanup passes skips cleanup moves. Greedy and bounded paired-exchange
phases each use that pass bound. Native feedback calibrates estimates; those
estimates never replace native capacity checks.

### Balance, cuts and snake paths

`--balance 0.20` is a **soft preference**. `--search-balance` is the separate
solver/refiner envelope, default `max(0.30, --balance)` and never below the
preferred target. For equal devices, imbalance is `max(L)/mean(L)-1`. For
heterogeneous devices, selection uses `max(L/C)/mean(L/C)-1`, with physical
device capacity C. A lower engineering cap does not change physical device
size. Raw subset imbalance is also reported.

The default **10% cut/topology growth allowance** is an engineering tradeoff,
not a universal research constant. For example, `--cut-growth 0.15` allows
more growth to improve higher-priority path quality. Budgets retain original
references across seed selection, refinement and feedback; stages do not each
receive another allowance. The optimizer counts internal data-net distinct
FPGA destinations and weighted topology distance. Clock/reset-only sinks and
top I/O are outside that optimization counter; complete cut reports are broader.

Without an explicit transport-delay model, default ranked selection considers:

1. Complete modeled path analysis.
2. Worst minimum-link-hop lower bound H, then worst logical register-free
   FPGA crossings C.
3. Sampled source timing cost, endpoint excess E, then transit primitives V.
4. Native search-envelope limitation tier, structural transfers T, weighted
   topology cost, then fine balance.

Hard capacities, fixed assignments, useful occupancy, logical resource/scalar
limits and original growth budgets remain independent gates. Within an
envelope, equal path quality therefore favors cuts before small balance gains.
`--quality-guard ranked` permits a lower-priority regression when a
higher-priority measure improves. `componentwise` preserves each reference
path/timing measure separately. Relay bound R is reported but has no
optimization/nonregression promise.

`--snake-weight` changes the witness penalty (default 8), not the priority or
hard budgets. Increasing it does not promise that every snake metric falls.
`--leaf-luts auto` chooses a hierarchy frontier. Smaller limits allow finer
moves but enlarge the graph and can fragment natural groups; larger limits
preserve modules but may prevent useful splits. Both affect packing and cuts.

```powershell
# More effort and a larger explicit cut-growth tradeoff:
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --target-part VP1902 --quality thorough --cut-growth .15 --output 'C:\results\thorough'
# A stricter search envelope, retaining the independent physical cap:
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --target-part VP1902 --balance .10 --search-balance .20 --output 'C:\results\tighter_balance'
```

The frontend retains a native-validated incumbent as a same-run anchor and
reevaluates it under current graph, timing, topology and capacity identities.
A new seed does not redefine its cut budget or quality promises. An explicit
anchor-quality rejection consumes an attempt and can continue with the
incumbent/feedback. Runtime errors stop retries. The anchor file alone is not
proof of native acceptance.

### Source timing

`--timing auto` attempts up to **1,000 constrained source setup paths**. Native
slack, requirement and primitive owners guide hyperedge weights and refinement.
If unavailable, auto can use structural guidance and records the fallback.
`--timing required` rejects unavailable timing; `off` provides a structural
comparison. `--timing-weight` defaults to 8. Existing TSVs must bind to the exact
source EDIF/DCP.

```powershell
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --target-part VP1902 --timing required --timing-max-paths 2000 --output 'C:\results\source_timing'
```

A fully scored sample does not establish design-wide constraint coverage;
unconstrained domains can be absent. Zero timing cost does not mean zero
physical delay. New constraints require a new source lineage, timing export
and actual mapping validation, not relabeling old reports.

No link delay is invented. `--extra-delay-ns D` models additional delay per
**logical FPGA crossing** on sampled paths. In that mode, better modeled worst
sampled slack and lower timing cost precede H/C and structural tie breakers,
within the same hard caps and growth gates. This is a user estimate, not
measured platform latency, full timing-graph propagation, post-partition STA
or achieved DUT frequency. Sampled guidance does not reproduce TritonPart's
full slack-propagation engine.

### Constraints and logical resources

`--mapping-constraints FILE` fixes hierarchy owners with mapping-style rules:

```text
hw_top/control FPGA_A
hw_top/datapath FPGA_B
hw_top/datapath/status FPGA_A
```

Longest ancestor wins. A deeper exception splits a containing coarse vertex;
resource/scalar/nonempty checks still apply. `--partition-preset deterministic`
rejects explicit mapping constraints because v1.6.2 would otherwise fall back
to another preset.

`--logical-resource-capacities FILE` supplies every zero-based FPGA index and
six logical resource dimensions:

```text
# fpga logical_LUT FF_plus_latch BRAM18_equivalent DSP URAM LUTRAM_plus_SRL_primitives
0 450000 600000 1200 2500 0 50000
1 450000 600000 1200 2500 0 50000
```

These counts are separate from packed CLB area and transport overhead. Unknown
macro resources prevent certification of explicit hard capacities.
`--logical-balance` separately constrains raw LUTs. Its automatic default is
0.5 for equal/absent native caps and `max(0.5,k-1)` for uneven caps, avoiding an
implicit uniform raw-LUT ceiling on capacity-proportional splits. Explicit
values, including zero, are retained. `--weight-model` accepts primitive-type
integer estimates with header `REF_NAME<TAB>integer_weight`, optionally preceded
by `#input_sha256=...`. Estimates do not certify packed area.

### Reproducibility

The multithreaded default can produce different assignments with the same
seed. `--partition-preset deterministic` opts into v1.6.2's deterministic
preset. Record preset, threads, seeds, effort, frontier and alternatives.
Relaxing balance enlarges the feasible set, but a finite heuristic may still
find a worse mapping. Different anchors/effort do not isolate one option's
causal effect.

`--capture-cohorts off` is default. Explicit `auto` enables bounded atomic
fanin-group moves toward captures under the existing guards. It is
experimental; OFF/AUTO runs with different initial assignments do not establish
a cohort benefit.

## All high-level options

Use `partitioner --help` or `partitioner.cmd --help` for the installed parser.
These are frontend flags; Java's underscore flags are not interchangeable.

| Option | Meaning / default |
|---|---|
| `--help` | Usage without a design or installed tools. |
| `--check-tools` | Toolchain preflight without a design/topology run. |
| `--input FILE` | Original `.dcp`, `.edf` or `.edif`; required for mapping. |
| `--system-topology FILE` (`--system_topology`) | Board JSON, simple JSON or weighted METIS; required for mapping. |
| `--fpga-count N` | First N sorted physical IDs; default all topology devices. |
| `--fpga-ids ID ...` | Explicit physical IDs; alternative to count. |
| `--output DIR` | New/empty output; default `partition_results/<input-stem>_<UTC timestamp>`. |
| `--quality fast\|balanced\|thorough` | Effort presets above; default balanced. |
| `--partition-preset default\|deterministic` | Mt-KaHyPar preset; default preserves multithreaded behavior. Deterministic rejects mapping constraints. |
| `--iterations N` | Feedback iterations; positive. |
| `--starts N` | Starting seeds per iteration; positive. |
| `--refine-passes N` | Bound each cleanup phase; nonnegative. |
| `--capture-cohorts off\|auto` | Experimental fanin cleanup; default off. |
| `--balance E` | Preferred native utilization imbalance; default 0.20. |
| `--search-balance E` | Envelope; default `max(0.30,balance)`, at least balance. |
| `--cut-growth E` | Transfer/topology growth; default 0.10, original references. |
| `--snake-weight W` | Structural witness weight; default 8. |
| `--quality-guard ranked\|componentwise` | Tradeoff policy; default ranked. |
| `--timing auto\|required\|off` | Source STA availability policy; default auto. |
| `--timing-paths FILE` | Existing exact-source-bound timing TSV. |
| `--timing-max-paths N` | Maximum source paths, 1–100,000; default 1,000. |
| `--timing-weight W` | Sampled timing contribution; default 8. |
| `--extra-delay-ns D` | Explicit delay per logical crossing; no default delay. |
| `--max-luts N` | Hard native CLB LUT cap on every selected FPGA. |
| `--fpga-lut-caps FILE` | JSON hard caps covering every selected label. |
| `--target-part PART` | Actual uniform target device, e.g. VP1902 or VU19P. |
| `--fpga-parts FILE` | SHA-bound assignment; otherwise `<topology>.targets.json`. |
| `--part-catalog FILE` | Relocate approved catalog and `.identity.json` sidecar. |
| `--lut-utilization F` | Hard device fraction; default 0.75, valid `(0,0.75]`. |
| `--source-checkpoint FILE` | Reused pair's DCP; requires native inventory. |
| `--native-primitives FILE` | Reused inventory; requires source checkpoint. |
| `--part PART` | EDIF source import part; distinct from target part. |
| `--leaf-luts auto\|N` | Logical-LUT coarsening threshold; default auto. |
| `--logical-balance E` | Separate raw-LUT ceiling; automatic policy above. |
| `--mapping-constraints FILE` | Longest-prefix fixed hierarchy rules. |
| `--logical-resource-capacities FILE` | Six-dimensional logical ceilings. |
| `--weight-model FILE` | Initial primitive-type integer estimate. |
| `--verbose-mapping` | Request frontier-level exceptions; explicit root and nonredundant ordered rules still apply. |
| `--seed N` | Initial seed; default 1. |
| `--threads N` | Backend worker threads; default 4. |
| `--java EXECUTABLE` | Explicit Java; otherwise environment/PATH. |
| `--heap SIZE` | Java maximum heap, e.g. `48G`; automatic size by default. |
| `--vivado EXECUTABLE` | Explicit Vivado; otherwise environment/PATH. |
| `--native-session auto\|off` | Adaptive unchanged-checkpoint session or batch. |
| `--native-timeout-seconds S` | Persistent startup/response waits; default 7,200; batch unaffected. |
| `--native-artifacts auto\|compressed\|plain` | Lossless report/cell-list storage; default auto. |
| `--mtkahypar-bin EXECUTABLE` | Backend; otherwise environment/PATH. |
| `--mtkahypar-lib DIR` | Optional backend library directory. |
| `--wsl DISTRO` | Explicit WSL backend distribution. |

Low-level Java development still uses
`com.xilinx.rapidwright.edif.partition.Partitioner`. Use the frontend for native
preparation, feedback, part-aware caps, source timing and final acceptance;
the Java solver alone does not perform that complete workflow.

## Outputs and acceptance

### Required mapping contract

`mapping.txt` is an **ordered move plan for the exact synthesized hierarchy**.
Each assignment has exactly two whitespace-separated tokens: an instance path
rooted at the DUT, followed by a logical destination matching
`FPGA_[A-Za-z0-9_]+`. A path names an instance in that particular synthesized
netlist, not a module type, signal, simulation prefix or guessed generate branch.
Preserve literal generate indices, brackets and dots. No wildcards, quotes,
shell escaping, inline comments, one-token shortcuts or duplicate source paths
are permitted, including duplicate assignments to the same destination.
Blank lines and whole-line `#` comments are accepted when reading a mapping;
generated mappings contain assignments only, to support simpler consumers.

**Every mapped descendant precedes its mapped ancestors. The residual DUT
root is explicit and last.** The downstream consumer extracts children first,
then moves only the parent's remaining hierarchy and local logic. It does not
pull already extracted children back into a later parent. Consequently,
sorting records alphabetically or moving the root first changes the plan.
Independent siblings may retain a stable order.

For a remote subtree and a nested exception returning to the root's FPGA:

```text
hw_top/cool_module/status FPGA_A
hw_top/cool_module FPGA_B
hw_top FPGA_A
```

The `status` exception is necessary: its nearest mapped ancestor is
`cool_module FPGA_B`, even though the more distant root targets `FPGA_A`.
Every functional leaf inherits its **most-specific mapped ancestor in the
original hierarchy**. There is no implicit destination for unlisted logic;
the explicit root owns the remainder.

Omit a child record whenever its destination equals its **nearest mapped
ancestor's** destination. For example, adding `hw_top/other FPGA_A` before
`hw_top FPGA_A` is redundant and is prohibited: same-FPGA parent/child moves
have caused downstream hierarchy and attribute-promotion problems. Comparing
only with the root would incorrectly remove the necessary `status` exception.
Uniform modules collapse while genuine deeper exceptions retain their
assignments. `--verbose-mapping` cannot bypass these rules; detailed ownership
belongs in a separate manifest rather than redundant move records.

Mapping generation validates actual source instances, complete functional-leaf
ownership, ordered records, nearest-ancestor redundancy and destination
agreement with `cells.txt` before publication. Compression must preserve the
partition assignment; a successful syntax parse or equal cell counts alone
does not establish this. Source-free partition lists cannot establish exact
hierarchy validity: standalone generation requires the synthesized EDIF too.
The original source/checkpoint association and mapping hashes remain part of
the acceptance identity. Compression follows real instance-parent relationships,
including names that contain `/`; it does not manufacture hierarchy from
textual prefixes. If a name is ambiguous under the downstream textual ownership
lookup, generation fails rather than publishing an unsafe interpretation.

For low-level development, the standalone writer requires `--netlist` alongside
its existing positional arguments. For example, from a built Windows checkout:

```powershell
java -cp 'bin;jars/*' com.xilinx.rapidwright.edif.partition.HierMappingWriter 'C:\solver\partitions' 'C:\solver\cells.txt' 'C:\results\mapping.txt' --netlist 'C:\designs\post_synth.edf' --optimized
```

The optional positional default destination must also be a valid populated
logical destination. Omitting `--netlist` fails closed. This writer validates
the hierarchy/mapping contract; use the normal frontend for topology, timing,
native resource acceptance and the complete published package.
Every writer requires a fresh mapping output: an existing final mapping is
rejected before any companions are modified. Use a separate output directory
for each alternative; do not reuse a previous accepted package in place.

Explicit GND/VCC primitive rules are omitted by primitive **type**, not by
instance-name spelling. Constants are accounted for separately; a real logic
instance named `VCC` still requires correct ownership. Constants can inherit
residual ownership for native accounting and are excluded from data
optimization. Empty hierarchy is also distinct from useful functional logic.
All requested FPGAs must receive useful design logic.

Generated labels are `FPGA_A` through `FPGA_Z`, then `FPGA_AZ`, `FPGA_BZ`, etc.
`cells.txt` contains each populated logical destination exactly once and agrees
with the mapping. Physical board names such as `C01_S01_E` are not destinations
in this file: `fpga_devices.tsv` records the separate logical-to-physical
selection. A logical label is not automatically a particular socket.

The ownership and boundary evidence describes the input netlist and intended
placement. This tool does not execute WrapAndRegroup or prove the transformed
netlist, promoted attributes, wrapper clock/reset hookup, transport or hardware
behavior. Those downstream checks remain necessary in the consuming flow.

| Artifact | Meaning |
|---|---|
| `mapping.txt`, `source_mapping.txt` | Accepted mapping and native-validated snapshot. |
| `cells.txt` | One unique populated logical destination per line, consistent with the accepted mapping. |
| `mapping_contract.json` | Source-bound contract validation, coverage and artifact identities. |
| `mapping_records.tsv` | Ordered records with their actual nearest mapped ancestors. |
| `mapping_ownership.tsv.gz` | Original instance path/type, functional/constant classification, intended owner, governing record and frontier owner. |
| `mapping_connections.tsv.gz`, `mapping_connections_manifest.json` | Input-netlist boundary/connection evidence and its scope/identity. |
| `STATUS.json` | Candidate, status, caps, balance, completeness, timing and identities. |
| `target_capacity.json`, `native_lut_caps.tsv` | Devices, policy and explicit native bounds. |
| `fpga_devices.tsv`, `topology_manifest.json` | Physical identity/topology provenance when applicable. |
| `lut_report.txt`, `vivado_resources.tsv` | Original-source subset Vivado CLB LUT Used. |
| `logical_lut_report.txt`, `logical_resources.tsv` | Logical resources, separate from packed area. |
| `primitive_counts.tsv` | Logical boundary primitive histogram. |
| `fpga_io_cuts.txt`, `fpga_net_crossings.tsv` | Canonical directed destinations/crossings, including top I/O. |
| `fpga_control_categories.tsv` | Clock/reset-only/data-or-functional-control classification. |
| `io_cuts.txt` | Undirected hyperedge partition pairs, a different metric. |
| `hardware_quality.txt` | Initial/repaired/final structural and sampled timing quality. |
| `native_candidates.tsv` | Starting seeds' audited quality. |
| `worst_combinational_path.tsv` | Witness attaining C; may not attain H on sparse systems. |
| `endpoint_kind_diagnostic.tsv` | Modeled capture/terminal types and coverage. |
| `run_provenance.json`, `source_pair_provenance.json` | Source/assignment/configuration/export identities. |
| `candidate_NN/` | Solver frontier/assignments, native validation and detailed reports. |

`frontier.tsv`, `final_assignment.part`, `initial_assignment.part`
and original per-seed `*.KaHyPar` files remain in the selected candidate,
identified by `STATUS.json`. New provenance exposes useful-logic counts and
their definition. Those exclude trusted constant/clock/I/O infrastructure and
synthetic capture nodes. Useful FF-only logic can occupy an FPGA with zero LUTs.

A completed frontend process returns zero with either:

- **`PASS`:** native caps met, modeled paths complete and balance inside the
  search envelope. The preferred soft balance target may still be missed.
- **`CAP_VALIDATED_WITH_LIMITATIONS`:** native caps met, but path completeness
  or the native search envelope has a recorded limitation.

Tool/input errors return nonzero and record `FAIL`. Read `STATUS.json` rather
than treating exit zero as an unrestricted quality certificate. Publication
stages/hashes the exact native-validated snapshot; a failed final copy/status
step removes the accepted mapping. Input replacement during a run is detected.
Candidate diagnostics can remain after failure.

### Re-encoding a historical assignment

Older compressed mappings can contain a textual prefix that is not a real
synthesized instance. For example, a design can have two direct children named
literally `flat/a` and `flat/b` without a parent instance named `flat`. A legacy
rule for `hw_top/flat` may give meaningful ownership to a longest-prefix audit,
but cannot be used as an actual move record. A legal successor must name the
real children or another genuine source ancestor.

The advanced `MappingReencoder` utility rebuilds legal records from the saved
solver frontier and final assignment. It does not invoke Mt-KaHyPar or optimize
the partition again. Supply the exact synthesized EDIF used for that solution,
the historical mapping, its original ordered canonical `cells.txt`, the selected
candidate's `frontier.tsv`, and its matching `final_assignment.part`. Do not mix
these files between candidates. The frontier needs its original four-column
header `vertex`, `path`, `logical_luts`, `primary_weight`, with dense vertex IDs
starting at one. Assignment rows are zero-based partition indices in the
original canonical label order, one per frontier vertex.

Create a UTF-8 jobs TSV with **no header**, one job per line and exactly five
tab-separated fields in this order:

| Field | Value |
|---|---|
| 1 | Historical `mapping.txt` path. |
| 2 | Original ordered `cells.txt` path. |
| 3 | Saved selected-candidate `frontier.tsv` path. |
| 4 | Matching `final_assignment.part` path. |
| 5 | Fresh successor output directory. |

Use literal tabs between fields; file-system paths in this jobs file need no
quotes. Every job in one invocation uses the same source EDIF, which is loaded
once. From a built Windows checkout:

```powershell
java -cp 'bin;jars/*' com.xilinx.rapidwright.edif.partition.MappingReencoder 'C:\designs\post_synth.edf' 'C:\saved\reencoding_jobs.tsv'
```

The utility checks the new mapping against actual source hierarchy and the
saved frontier, then compares **every functional source leaf's owner** with
the historical mapping's serialized longest-prefix interpretation. It also
requires the explicit DUT root owner to remain unchanged, preserving top-port
ownership. Any mismatch rejects final publication. It emits `mapping.txt`,
`cells.txt`, the ownership/record/contract manifests and
`reencoding_equivalence.json`, which binds the source, old/new mappings,
frontier and final assignment by SHA-256. Failed staging directories may
remain for diagnosis; they are not accepted successor mappings.

When the source is the same source associated with the historical native
evidence, unchanged functional-leaf and top-port ownership preserves its
original-source resource subsets and nonconstant cut endpoints. This is a
source-bound equivalence proof, **not a fresh Vivado measurement**. Keep the
original mapping, native reports, source-pair receipt and their hash-bound
qualification unchanged alongside the new proof. Do not rewrite an old native
receipt or `source_mapping.txt` to make it appear to validate new mapping
bytes. The re-encoder does not rerun independent native cut/category parity,
emit the full frontend's acceptance status, or establish transformed-netlist,
attribute-promotion, transport or hardware behavior. For a new solution and
fresh native acceptance, use the normal partitioner frontend.

### Native resources and exact cut parity are separate

The frontend automatically accepts native utilization on original-source cell
collections. Native LUTs are not a primitive-name formula: `LUT6_2` is one
logical primitive, while CLB usage follows Vivado semantics. Separately
reported subsets can sum differently from whole-design utilization because
LUT combining crosses boundaries. Do not force those totals to equal.

Frontend PASS/native caps do **not** establish independent endpoint fingerprints,
primitive-histogram or cut-category equality. Use the
[exact Vivado parity workflow](../../tools/PARTITION_METRIC_PARITY.md) for its
additional unchanged-source, exact-map-bound export/comparison. A full source
census and a mapping-bound crossing-incidence query have different scopes;
retain the actual proof's scope. Independent parity is PENDING/NOT RUN until
its own receipts pass.

A canonical crossing net can have several distinct directed net-to-FPGA
destinations. Clock, reset-only and data/functional-control categories partition
those destinations. These differ from structural T, undirected pairs and
physical package pins.

## Path, timing and resource model

The graph follows primitive connectivity. A crossing into a modeled sequential
capture is counted; its output begins a fresh path. Dynamic programming covers
all modeled acyclic paths without enumerating every path. Transit V intersects
foreign-ingress/foreign-egress reachability inside a partition, detecting
A-to-B-to-C even without reentering A. Cycles and blocked descendants are
reported; incomplete analysis cannot invent capture barriers or certify a DAG.

For physical adjacency, h(A,B) is the fewest links between owners. H is the
maximum path sum of h across owner changes; C counts logical owner changes.
R independently sums `max(0,h-1)`. An end-to-end crossing on a four-device
chain has C=1, H=3, R=2; a directly connected topology has H=C and R=0.
Weighted costs are not rounded into hops. H/R are lower bounds, not actual
routes, congestion, cycles or nanoseconds. Their maxima can come from different
paths; the exported worst witness attains C.

Current scope is `functional_synchronous_controls_v2_UNISIM_2025_1`.
Functional synchronous controls can capture, including FDRE reset, FDSE set
and enabled BRAM/URAM/DSP reset stages. Asynchronous reset/preset and disabled
stages do not acquire synchronous roles. Trusted HDI/object properties and
parameters determine the model; names alone do not create barriers. Unknown
or opaque logic remains conservative. Pinned Vivado 2025.1 defaults apply to
missing recognized parameters; invalid explicit values do not fall back.

LUTRAM/SRL asynchronous address/read dependencies remain combinational. Memory
data/write/control capture and optional cascade paths are modeled separately.
DSP outputs are barriers only where actual registration supports it. This is
conservative connectivity, not Boolean sensitization or pin-accurate full STA.

Refinement samples at most 4,096 endpoint-maximal witnesses with a one-million
primitive-ID aggregate budget, retaining whole sampled paths. Final analysis
still covers all modeled acyclic paths. Bounded paired exchanges escape some
full-partition cases where single moves cannot fit: each sweep considers up
to 64 relevant vertices, 16 partners per vertex, 4,096 probes and four committed
exchanges, with scan limits. Every retained move meets independent legality,
resource, occupancy, quality and original growth checks.

Timing weights use normalized sampled criticality and conservative owner-pair
guidance; parallel nets between matched owners may all receive guidance. Raw
cuts remain unweighted. Paths outside the STA sample receive structural
analysis, not invented timing values.

### Runtime and large designs

Automatic Java heap sizing takes the larger of five times EDIF bytes and
4 KiB per expanded native leaf, adds 8 GiB and rounds up to 8 GiB. `--heap`
overrides it. Repeated hierarchy can have a small definition and large expanded
census; file size alone is not a sufficient RAM estimate.

`--native-session auto` can retain an unchanged Vivado checkpoint when measured
free RAM covers the next Java heap, headroom and overlapping solver reserve.
Unknown/insufficient RAM closes the owned session, confirms exit and switches
to batch. `off` selects batch directly. Reuse does not relax identities or caps.
The persistent timeout separately bounds startup and each response, not total
runtime; batch is unaffected. Large exports/graphs/native reports can require
substantial RAM, disk space and time before refinement starts.

`--native-artifacts auto` can compress sealed reports/cell lists of at least
1 MiB when source/output meet the archive workspace conditions. It verifies
decompression hashes and retains receipts. Tables, mappings, assignments and
anchor inputs stay plain. `plain` keeps reports plain; `compressed` requires
the helper and workspace conditions. See
[native archive details](../../tools/NATIVE_REQUEST_ARCHIVE.md) for restoration.

## Tests and troubleshooting

From a built checkout, run focused Java and Python suites:

```powershell
.\gradlew.bat testJava --tests 'com.xilinx.rapidwright.edif.partition.*' --no-daemon --max-workers=4
python -m unittest discover -s scripts/partition -p 'test_*.py'
python -m unittest discover -s tools -p 'test_*.py'
```

Use `./gradlew` on POSIX. These test code/bounded fixtures, not large-design
synthesis. Some integrations require explicitly supplied recorded-native
evidence and may skip when absent; a skip is not new native validation.
The [validation report](VALIDATION_REPORT.tex) records experiment scope,
metrics and limits. LSUT designs/output directories are not dependencies of
the standalone tool.

| Symptom | Action |
|---|---|
| Missing classes/JARs | Build this checkout with `gradlew compileJava`; another installation's classes are not a substitute. |
| Missing tools/wrong version | Use `--check-tools`, configure actual paths/environment; only Mt-KaHyPar 1.6.2 is accepted. |
| Missing `tkinter` | Install Tcl/Tk for the launcher's Python, then preflight. |
| Missing/conflicting target | Supply actual metadata, a correctly bound companion or `--target-part`. |
| Disconnected subset | Select connected devices or correct the topology; missing links are not inserted. |
| No cap-feasible candidate | Inspect native resources/frontier; consider finer moves, more effort/devices. Failure is not infeasibility proof. |
| `CAP_VALIDATED_WITH_LIMITATIONS` | Inspect completeness and search-envelope fields; it is not a hard-cap violation. |
| Timing fallback | Inspect its reason; supply constraints/bound paths or require timing. |
| RAM/disk pressure/response timeout | Inspect stage logs and adjust heap, session, archive or timeout to resources; these do not change FPGA caps. |
| Worse tuning result | Compare all metrics, source, frontier, anchors, budgets and seeds. More effort or balance tolerance does not ensure improvement. |

The [research note](RESEARCH.md) separates public algorithm/vendor references
from engineering policy. This is a measured heuristic, not a claim to
proprietary vendor algorithms or an ideal mathematical solution.
