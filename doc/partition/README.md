# Hardware-aware FPGA partition mapping

Give the partitioner a post-synthesis netlist and a description of the FPGA
system. It assigns the design's logic to the selected FPGAs and writes
`mapping.txt`, together with reports explaining that assignment.

The tool tries to keep communication low and avoid signals passing through
several FPGAs without being captured by a register. It considers the system's
links and available source timing information. It uses **Mt-KaHyPar v1.6.2
only** to find starting assignments, improves them with local moves, and asks
Vivado to check each FPGA's original-source LUT usage before accepting a result.

A normal run needs a netlist and topology. The topology must identify its
actual FPGA parts, directly or through a companion file; otherwise supply
`--target-part`. The default uses every FPGA in the topology. Use `--fpga-count`
or `--fpga-ids` to select a subset.

The output is a mapping. The tool does not wrap or regroup a netlist, insert
transport, replicate logic, retime registers, place or route the partitions.
It does not infer an achieved DUT frequency or promise a global optimum.

In these documents, **native LUT validation** means asking Vivado to report
the original checkpoint's cells assigned to each FPGA. **Source timing** means
static timing analysis of that original checkpoint, before adding any
prototyping transport. These are useful checks, with a different scope from
implementing the finished multi-FPGA system.

- [Build and requirements](#build-and-requirements)
- [Quick start](#quick-start)
- [Topology and target devices](#topology-and-target-devices)
- [Quality controls](#quality-controls)
- [What the numbers mean](#what-the-numbers-mean)
- [All high-level options](#all-high-level-options)
- [Outputs and acceptance](#outputs-and-acceptance)
- [Path, timing and resource model](#path-timing-and-resource-model)
- [Tests and troubleshooting](#tests-and-troubleshooting)
- [Validation report PDF](VALIDATION_REPORT.pdf) and [LaTeX source](VALIDATION_REPORT.tex)
- [J4 example mappings and HTML reports](examples/j4/README.md)
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

Install the shared Python requirements before running a design job. They
provide the required figures and process resource measurements; Tcl/Tk is
also needed for the source-netlist helpers.

From the source checkout or extracted runtime directory, use the same Python
interpreter that will run the partitioner:

```powershell
python -m pip install -r scripts/partition/requirements.txt
```

On Linux, use `python3` if that is the launcher's interpreter. The file pins
Matplotlib 3.10.8, Pillow 12.0.0 and psutil 7.2.0, with Windows and Linux
wheels for the supported Python versions. Install before a long partition job; accepting
a mapping requires its reports to be generated too.

| Requirement | Use |
|---|---|
| Python 3.11 or newer | Frontend and export/report helpers; `hashlib.file_digest` is required. |
| Python Tcl/Tk (`tkinter`) | Tcl-list decoding for source timing and independent endpoint audits. A display is not needed. |
| Pinned report dependencies | Matplotlib/Pillow generate the PNGs without a display; psutil measures the run's host processes. Install `scripts/partition/requirements.txt`. |
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
**`<output>/report.html`** after completion for the readable summary and plots.
`<output>/STATUS.json` contains the machine-readable acceptance result.

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

The normal command already uses topology, snake-path checks and available
source timing. You do not need separate switches to turn on those checks.
The default tries three feedback iterations, with three starting assignments
per iteration and three cleanup passes per phase, using four backend threads.
The name `--quality balanced` describes the amount of search effort; it does
not demand nearly equal LUT counts on all FPGAs.

For four FPGAs and three iterations, a typical command is:

```powershell
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --target-part VP1902 --fpga-count 4 --iterations 3 --output 'C:\results\four_fpgas'
```

Omit `--target-part` when the topology or its companion already declares the
actual parts. Omit `--fpga-count` to use all FPGAs in the topology.

### Can a longer run find a better partition?

Yes: more iterations give the tool more opportunities to find a useful
assignment and to improve its LUT estimates using Vivado's measurements.
They are a search budget, not a promise that every iteration improves every
number. The tool keeps the best accepted candidate under the priority below;
the final mapping can come from an earlier iteration.
Multi-FPGA runs use the full requested iteration budget by default, so a
candidate with no repeated crossings still has opportunities to reduce cuts.
Add `--early-stop` to permit an earlier finish after finding a complete,
cap-valid candidate inside the search balance range with no modeled repeated
FPGA/link crossing and no pass-through combinational logic. That optional
stopping rule does not establish the lowest possible I/O-cut count.
A one-FPGA run always makes one direct assignment; extra iterations cannot
change which FPGA owns its logic.

| Preset | Feedback iterations | Starting seeds per iteration | Cleanup passes |
|---|---:|---:|---:|
| `--quality fast` | 1 | 1 | 1 |
| `--quality balanced` (default) | 3 | 3 | 3 |
| `--quality thorough` | 5 | 7 | 6 |

These options change different parts of that budget:

- `--iterations N`: repeat the search with feedback from native Vivado LUT
  measurements. Each round can update the area estimates used for the next.
- `--starts N`: try this many backend seeds in each iteration. These are
  alternative starting assignments, not extra serial cleanup passes.
- `--refine-passes N`: bound the local cleanup work on an assignment. Both the
  single-move phase and the paired-exchange phase use this bound. Zero skips
  those cleanup moves.

More starts and passes generally cost more time; native checks and graph
construction also contribute, so doubling iterations need not double runtime.
Native feedback improves estimates; final acceptance still requires Vivado's
measured subset LUT counts.

There is measured evidence that this extra search can help. In the recorded
three-FPGA mesh comparison, a later candidate reduced the optimization's data
transfers from 6,988 to 6,452, the repeated-crossing endpoint score from 66 to
32, and pass-through logic from 813 to 720 primitives. Its worst register-free
path still crossed two boundaries. Other runs show tradeoffs or no improvement;
the [validation report](VALIDATION_REPORT.tex) includes those cases and the
source identities. This is an example of a better result found during a run,
not a guarantee about a particular iteration setting.

For a local cleanup example, NVDLA on four FPGAs improved its worst
register-free path from three FPGA crossings to two. Its repeated-crossing
endpoint score fell from 1,001 to 733 and pass-through logic fell from 266 to
230 primitives, for 17 more optimization transfers (0.21%). This is a measured
tradeoff, not an achieved-clock claim. The two-FPGA NVDLA case also shows why
the separate measures matter: its endpoint score improved while pass-through
logic increased substantially. Read the numbers together rather than treating
one smaller score as proof that every aspect improved.

### Balance, cuts and snake paths

Keeping every FPGA below its hard resource limit matters more than making
their bars equal. A 30% imbalance, or a larger difference, is acceptable if it
buys a better partition and every FPGA stays within its hard cap. A very small
imbalance is not a quality goal on its own. The default hard LUT ceiling remains **75% of each actual
target part's LUT capacity**, even if you allow much greater imbalance.

The balance controls are deliberately separate:

- `--balance 0.20` is a preferred 20% imbalance, not a hard native LUT limit.
- `--search-balance` lets you impose a tighter search range when you want one.
  The automatic range permits any distribution of estimated area; the actual
  Vivado LUT caps decide whether it fits. For equal devices this range is
  `number of FPGAs - 1` (for example, 3.0 for four FPGAs). If you supply a
  tighter range, then at equal path quality a candidate inside it is preferred
  to one outside it. Inside the range, fewer transfers and lower topology cost
  come before finer balance.
- `--logical-balance` is a separate hard guard on raw logical LUT counts used
  during search. Its automatic value, `max(0.5, number of FPGAs - 1)`, avoids
  forcing equal LUT counts. Supply a smaller value only if you want this
  extra restriction. Raw LUTs and Vivado's packed LUT usage are different counts.
- `--max-luts`, `--fpga-lut-caps` and the part's 75% utilization ceiling are
  hard acceptance limits. Search effort and balance settings cannot waive them.

For equal parts, imbalance means **how far the most loaded FPGA is above the
mean**, divided by the mean. Counts of 1 million and 3 million LUTs have a
mean of 2 million and a 50% imbalance. This is separate from utilization: both
can still fit comfortably on VP1902s. For different part sizes, selection
compares each FPGA's fraction of its physical capacity, rather than demanding
equal raw LUT counts. The reports also show the raw count imbalance.

The default **10% cut/topology growth allowance** is an engineering tradeoff,
not a universal research constant. For example, `--cut-growth 0.15` allows
more growth to improve higher-priority path quality. Budgets retain original
references across seed selection, refinement and feedback; stages do not each
receive another allowance. The optimizer counts internal data-net distinct
FPGA destinations and weighted topology distance. Clock/reset-only sinks and
top I/O are outside that optimization counter; complete cut reports are broader.

Without an explicit transport-delay model, the normal selection order is:

1. Prefer a candidate with complete analysis of the modeled paths.
2. Reduce the worst number of hardware links needed along a register-free
   path, then the worst number of logical FPGA boundary crossings.
3. Reduce the sampled source timing penalty, then the repeated-crossing
   endpoint score, then the number of pass-through combinational primitives.
4. Prefer the native balance search range, then fewer data transfers, lower
   topology cost and, finally, finer balance.

This order gives severe snake paths a high priority. `--cut-growth` still bounds
how many extra transfers and how much extra topology cost cleanup can spend.
Hard capacities, fixed assignments, useful logic on every FPGA, logical
resource limits and the original growth budgets remain mandatory checks.
`--quality-guard ranked` permits a lower-priority measure to worsen when a
higher-priority one improves. For example, fewer repeated crossings at
endpoints can be accepted even if more combinational primitives become
pass-through logic. Use `--quality-guard componentwise` if every reference
path and timing measure must stay as good or improve; this can reject useful
tradeoffs. The reported relay estimate is diagnostic rather than an extra
optimization guarantee.

`--snake-weight` changes the penalty used to guide cleanup (default 8).
Increasing it makes the local search focus harder on its sampled bad paths;
it does not change the selection order or hard budgets, and does not promise
that every snake-path measure falls. `--leaf-luts auto` chooses which groups
of logic move together. Smaller limits allow finer moves but make the graph
larger and can break up natural modules. Larger limits preserve bigger groups
but can prevent useful splits.

```powershell
# More effort and a larger explicit cut-growth tradeoff:
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --target-part VP1902 --quality thorough --cut-growth .15 --output 'C:\results\thorough'
# Optional: narrow the normally capacity-driven search to a 70% imbalance range:
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --target-part VP1902 --fpga-count 4 --iterations 12 --balance .70 --search-balance .70 --logical-balance .70 --output 'C:\results\wide_balance'
# A stricter search envelope, retaining the independent physical cap:
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --target-part VP1902 --balance .10 --search-balance .20 --output 'C:\results\tighter_balance'
```

The frontend retains the best native-validated assignment found in the same
run. Later candidates are checked against it with the same source, topology,
timing and capacity settings. A new seed does not reset the cut budget and
allow another 10% growth at every stage. A quality rejection uses up an
attempt; the run can continue from its retained assignment. Runtime errors
stop the run rather than being treated as ordinary unsuccessful searches.

### Source timing

`--timing auto` asks Vivado for up to **1,000 constrained setup paths** from the
original checkpoint. It uses their slack, requirements and assigned logic to
guide the initial partitioning and cleanup. If that timing is unavailable,
the tool can continue with connection-based path checks and records the reason.
Use `--timing required` to fail instead of accepting that fallback, or `off`
to compare a run without source timing guidance. `--timing-weight` defaults
to 8. A reused timing TSV must identify the exact source EDIF/DCP.

```powershell
.\partitioner.cmd --input 'C:\designs\synth.dcp' --system-topology 'C:\systems\machine.json' --target-part VP1902 --timing required --timing-max-paths 2000 --output 'C:\results\source_timing'
```

A sample can miss paths or clock domains, especially if they are unconstrained.
A zero timing penalty therefore does not mean zero physical delay or a proven
DUT clock. If the constraints change, export timing and validate the mapping
again against that changed source; do not reuse an old timing claim.

No link delay is invented. `--extra-delay-ns D` models additional delay per
**logical FPGA crossing** on sampled paths. In that mode, better modeled worst
sampled slack and lower timing cost come before worst link hops, FPGA
crossings and the other structural comparisons,
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
`max(0.5,k-1)` for every system, where `k` is the number of selected FPGAs.
This avoids an extra equality constraint; native hard caps and useful logic
on every selected FPGA remain mandatory. The automatic estimated-area search
range is `max(preferred balance, sum(device LUT capacities) / smallest device
LUT capacity - 1)`. It becomes `k-1` for equal devices unless the preferred
balance is even larger. Explicit balance settings, including zero where
allowed, are retained. `--weight-model` accepts primitive-type
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
| `--iterations N` | Search rounds with Vivado LUT feedback; positive; full budget by default for multi-FPGA runs. |
| `--early-stop` | Opt into stopping when repeated modeled crossings and pass-through logic disappear; cuts could still improve. |
| `--starts N` | Alternative starting assignments per round; positive. |
| `--refine-passes N` | Maximum passes in each local cleanup phase; zero disables those moves. |
| `--capture-cohorts off\|auto` | Experimental fanin cleanup; default off. |
| `--balance FRACTION` | Preferred imbalance in Vivado LUT utilization; default 0.20. |
| `--search-balance FRACTION` | Optional tighter estimated-area imbalance range; automatic default is capacity-driven, as defined above. |
| `--cut-growth FRACTION` | Extra transfer/topology cost allowed to improve paths; default 0.10 against the original references. |
| `--snake-weight WEIGHT` | Local bad-path penalty; default 8. |
| `--quality-guard ranked\|componentwise` | Tradeoff policy; default ranked. |
| `--timing auto\|required\|off` | Source STA availability policy; default auto. |
| `--timing-paths FILE` | Existing exact-source-bound timing TSV. |
| `--timing-max-paths N` | Maximum source paths, 1–100,000; default 1,000. |
| `--timing-weight WEIGHT` | Importance of sampled source timing in local guidance; default 8. |
| `--extra-delay-ns DELAY` | User-estimated delay per logical FPGA crossing; no default delay. |
| `--max-luts N` | Hard native CLB LUT cap on every selected FPGA. |
| `--fpga-lut-caps FILE` | JSON hard caps covering every selected label. |
| `--target-part PART` | Actual uniform target device, e.g. VP1902 or VU19P. |
| `--fpga-parts FILE` | SHA-bound assignment; otherwise `<topology>.targets.json`. |
| `--part-catalog FILE` | Relocate approved catalog and `.identity.json` sidecar. |
| `--lut-utilization FRACTION` | Hard device fraction; default 0.75, valid `(0,0.75]`. |
| `--source-checkpoint FILE` | Reused pair's DCP; requires native inventory. |
| `--native-primitives FILE` | Reused inventory; requires source checkpoint. |
| `--part PART` | EDIF source import part; distinct from target part. |
| `--leaf-luts auto\|N` | Logical-LUT coarsening threshold; default auto. |
| `--logical-balance FRACTION` | Separate hard raw-LUT imbalance guard; automatic policy above. |
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

## What the numbers mean

An **I/O cut** here is a logical signal connection that crosses between FPGA
owners. It represents communication the later prototyping flow must carry.
It is a useful measure of pin-multiplexing demand, but it is **not a measured
physical pin count or multiplexing ratio**. Those depend on the transport,
clock/reset handling, encoding, scheduling and actual link resources added
later. A bus contributes separate signal bits when those bits are separate
nets in the synthesized netlist.

The output contains several counts because they answer different questions:

| Name | Plain-English meaning |
|---|---|
| Vivado LUT total | Vivado's **CLB LUT Used** for the original-source logic assigned to an FPGA. Use this to check the hard LUT cap. |
| LUT utilization | That FPGA's Vivado LUT total divided by the actual target part's LUT capacity. The default maximum is 75%. |
| Deviation from mean | `(FPGA total - mean total) / mean total × 100%`. Negative means below the mean; positive means above it. If every total is zero, deviation is shown as zero. |
| Unique crossing nets | Number of distinct canonical nets that connect different FPGA owners, counted once per net. Hierarchical aliases of the same net do not create extra nets. |
| Directed signal transfers | One canonical net for each source-to-destination FPGA pair. A signal sent from FPGA_A to both FPGA_B and FPGA_C is **two transfers**, although it is only **one crossing net**. |
| Per-FPGA I/O cuts | Unique outgoing data/functional-control nets plus unique incoming nets on that FPGA. An outgoing net is counted once even if several FPGAs consume it. This is logical boundary signal demand; clock-only and reset-only traffic is reported separately. |
| Per-FPGA pair pressure | Incoming plus outgoing source-to-destination transfers touching that FPGA. This counts a multicast once for each receiving FPGA, so it can be higher than unique boundary signal demand. |
| Optimization transfers | The internal data-net counter used during search. It excludes clock/reset-only sinks and top-level I/O; it can differ from the complete final cut report. |
| Cut hyperedge | A solver net whose logic groups occupy more than one FPGA. This ignores direction. One hyperedge can represent several signal destinations. |
| Hyperedge partition pairs | The undirected FPGA pairs touched by each solver hyperedge. A hyperedge touching A, B and C contributes A–B, A–C and B–C. `io_cuts.txt` reports this different graph measure. |
| Worst register-free FPGA crossings | The largest number of FPGA owner changes on a modeled path before capture by a register or another modeled sequential endpoint. A→B→C without capture has two crossings. |
| Worst register-free hardware-link hops | For each such path, add the fewest topology links needed for each owner change, then take the worst path. This detects a costly crossing between distant FPGAs even when it counts as only one logical owner change. |
| Repeated-crossing endpoint score | For every capture or terminal endpoint, find its worst register-free crossing count, subtract one (minimum zero), then add those values. This measures how widely repeated crossings affect endpoints; it does not count all possible paths. Lower is better. |
| Pass-through combinational primitives | Number of combinational primitives in an FPGA that can be reached from a foreign FPGA and can reach another foreign FPGA without a register inside that FPGA. A→B→C is included; return to A is not required. This counts logic primitives, not paths. |
| Sampled source timing penalty | A ranking score based on the exported source timing paths and their FPGA crossings. Lower is better; it is not the achieved partitioned-system clock period. |
| Weighted topology cost | Communication cost using the supplied link weights and topology. It is a preference for cheaper communication, not measured bandwidth or delay. |

For example, one data bit driven on FPGA_A and consumed on FPGA_B and FPGA_C
has one crossing net and two directed transfers. The unique boundary signal
counts shown in the I/O bars are **1, 1 and 1**: A sends one unique net, and
B and C each receive it. The corresponding pair-pressure counts are **2, 1
and 1**, because A communicates with two destination FPGAs. The solver's
directionless three-way hyperedge can instead contribute three partition pairs.
These numbers are compatible; they count different things. The final reports
show both boundary signal demand and source-to-destination communication.

Older machine reports retain short names such as `max_minimum_link_hops`,
`endpoint_excess` and `transit_primitives` for compatibility. The user-facing
descriptions above give their meaning. A report of complete path analysis
means all paths in the modeled graph were covered; it does not prove every
path is electrically sensitizable or that later transport meets timing.

## Outputs and acceptance

### Start with the HTML report

Every completed frontend partition produces **`report.html`** alongside its
accepted `mapping.txt`. Open it in a browser; no report server is needed.
The page uses a restrained black-and-white document style and explains the
accepted partition, resource headroom, I/O demand, communication, path quality,
timing guidance and validation status. The plots use the requested orange and
black palette. Keep the whole output directory together so its linked images
and detailed files remain available.

The PNGs use only light orange `#F26522`, dark orange `#C4470C` and black
`#000000` as their drawing colors. Their bundled font is **Titillium Web
Regular**, an open-licensed approximation of the requested Klavika style.
It needs no local font installation. The font file and its SIL Open Font
License are included under `scripts/partition/assets/fonts/`; commercial
Klavika files are not redistributed. The HTML text itself remains black and
white, with the colored PNGs embedded as figures.

Three PNGs describe the **selected final mapping**, which can come from an
earlier iteration rather than the last attempt:

| Figure | What it shows |
|---|---|
| `lut_balance.png` | Two bar charts: Vivado LUT total per FPGA with a mean line, then each FPGA's percentage deviation from that mean. The HTML also shows the actual part capacity and hard cap. |
| `io_cut_balance.png` | Two bar charts: unique incoming plus outgoing data/functional-control boundary nets per FPGA with a mean line, then percentage deviation from that mean. Multicast transmit nets count once per source FPGA. Clock-only and reset-only counts appear separately in the HTML. |
| `fpga_connectivity.png` | Directed communication between FPGA pairs, showing how many data/functional-control signal transfers each source sends to each destination. This shows logical communication; it does not claim to be a routed transport schedule. |

The HTML distinguishes unique boundary signals from multicast pair pressure
and explains the cut definitions. If a run tried multiple iterations, its
history identifies the selected candidate and shows how the reported quality
changed. Unsuccessful proposals are not presented as accepted improvements.
`report_data.json` retains the plotted values and descriptions;
`report_manifest.json` records the source artifacts and report identities.
The plots summarize measured source-subset and logical-net evidence. They do
not replace the underlying native capacity or mapping-contract checks.

The runtime table reports elapsed wall time, observed CPU time, average CPU
cores used, and peak sampled memory for the partitioner and its visible child
processes, including Java and Vivado. It samples every 0.2 seconds and measures
through report generation and final source checks, just before publishing the
mapping. CPU time is a lower bound because very short-lived or inaccessible
processes can be missed. Memory is the largest observed sum of simultaneous
resident sets; shared pages can be counted more than once. Windows measurements
exclude CPU and memory inside a WSL guest. Older examples without measurements
say **unavailable**. Raw values and their measurement scope are retained in
`report_data.json` and `STATUS.json`.

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
| `report.html` | Readable summary of the selected mapping, its three plots, iteration history and validation limits. |
| `lut_balance.png`, `io_cut_balance.png`, `fpga_connectivity.png` | Selected mapping's LUT distribution, I/O distribution and directed communication. |
| `report_data.json`, `report_manifest.json` | Figure values and source/report identities. |
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
| `fpga_io_cuts.txt`, `fpga_net_crossings.tsv` | Unique crossing nets and directed source-to-destination signal transfers, including top-level I/O. |
| `fpga_control_categories.tsv` | Clock/reset-only/data-or-functional-control classification. |
| `io_cuts.txt` | Directionless solver hyperedge partition pairs; not physical I/O pins. |
| `hardware_quality.txt` | Before/after path crossings, repeated-crossing endpoint score, pass-through logic and sampled timing penalty. |
| `native_candidates.tsv` | Measured path/cut quality of the backend starting assignments. |
| `worst_combinational_path.tsv` | An actual modeled path attaining the worst logical FPGA-crossing count. A different path may have the worst hardware-link-hop count. |
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

A crossing net can have several source-to-destination FPGA transfers. The
clock-only, reset-only and data/functional-control categories divide those
transfers without overlap. These final counts have broader coverage than the
search's optimization-transfer counter. They also differ from directionless
solver pairs and physical package pins; use the definitions above when
comparing reports.

## Path, timing and resource model

The path graph follows connections between actual primitives. A path ends
when it reaches a modeled sequential capture; that capture's output starts a
new path. Crossing the boundary into the capture still counts. The analysis
checks all modeled acyclic paths without having to list every possible path.
Pass-through logic is identified by tracing both incoming and outgoing foreign
communication inside an FPGA; A→B→C is caught even if the signal never returns
to A. Combinational cycles and the logic they block are reported explicitly.
An incomplete graph is not treated as a successful complete path check.

Logical boundaries and hardware links answer different questions. Suppose a
four-device chain connects A–B–C–D and a modeled connection goes directly
from logic owned by A to logic owned by D. That is **one logical FPGA boundary
crossing**, but it needs **at least three hardware links** and two intermediate
relay traversals. A directly connected system needs one link for that crossing.
The analyzer adds these minimum link counts along each register-free path.
It does not choose a real transport route or predict congestion, cycles or
nanoseconds. The worst logical-crossing path and the worst link-hop path can
be different; the exported path file shows the former.

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
The [mapping test coverage record](evidence/mapping_contract_test_coverage.json)
ties the tests to the five supplied contract photos. It includes exhaustive
small assignments, independent ordered-move checks, literal hierarchy names,
constants, nested exceptions and a replay of all sixteen delivered J4 maps.
The validator also rejects a resealed ownership manifest whose wrong owners
preserve every FPGA's total but contradict the most-specific mapping record.
The [CI and runtime validation record](evidence/ci_runtime_validation.json)
records Windows/Linux test results and a fresh four-FPGA Vivado smoke run.
The [validation report](VALIDATION_REPORT.tex) records experiment scope,
metrics and limits. LSUT designs/output directories are not dependencies of
the standalone tool.

The [J4 review examples](examples/j4/README.md) contain all sixteen qualified
mapping files and their HTML/PNG reports in compact, version-controlled folders.
Their resource/cut measurements are historical results preserved by exact
ownership equivalence; their README explains the large proof files needed for
an independent re-audit.

To rebuild the version-controlled [PDF](VALIDATION_REPORT.pdf), first install
the pinned Python dependencies above and a full LaTeX distribution containing
`pdflatex` and the report's packages, including `fancyvrb`. From the checkout:

```powershell
python doc/partition/build_report.py
# If pdflatex is not on PATH:
python doc/partition/build_report.py --pdflatex 'C:\tools\texlive\bin\windows\pdflatex.exe'
```

The script regenerates the paper's source-bound figure, runs LaTeX twice, and
copies only a successfully completed PDF to `doc/partition/VALIDATION_REPORT.pdf`.
It does not install a compiler or download missing LaTeX packages automatically.

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
