# Complete native cut census with bounded consumer memory

These new tools preserve the existing plain native oracle and all its receipts.
They do not optimize, rewrite or wrap a netlist. A complete native census is
mapping-independent: one census of the unchanged source can check its final
1/2/3/4-FPGA mappings together, or later replacement mappings.

The producer, streaming comparators and shared fingerprint policy are included
in this repository. They do not require an adjacent design checkout. A separate
small reference parser is included under `test/resources/partition/` for bounded
regressions; it is not a full-design export tool. The relevant files are:

- `export_partition_native_cuts.tcl`: the same typed 512-net batches,
  pin/port anchor, native `get_nets -segments` alias closure, lexicographically
  smallest alias name, bulk pin properties, and top-port queries. It writes a
  gzip stream instead of raw plus expanded endpoint TSV.
- `native_cut_gzip_pipe.py`: bounded stdin-to-gzip fallback when native Tcl
  lacks `zlib`. Native Tcl gzip is preferred. Both use compression level 1.
- `compare_native_cuts_stream.py` and its functional-control-aware v3 variant:
  validate one canonical net at a time and
  all supplied mappings in one pass. It keeps logical primitive names, mapping
  rules, expected crossing fingerprints, matched fingerprints and a bounded
  250,000-owner lookup cache. It retains neither every net nor a global endpoint
  ownership dictionary.

The cut unit matches the existing comparator exactly: one canonical logical net
per distinct directed FPGA pair. Native logical macro boundaries filter out
expanded physical children. VCC/GND-driven nets are excluded; clock/reset and
top I/O are included. Top ports use the mapping's named root assignment. Endpoint
fingerprints are SHA256 of sorted endpoint/direction tuples, independent of net
alias spelling. These are not physical package-pin counts or routed capacity.

## Completion and source binding

The producer enumerates every native hierarchical net and checks that every
enumerated segment was covered by its alias dictionary. A completion record is
published only after gzip closes successfully and the final stream is renamed.
Interrupted, over-budget or incomplete exports retain a partial file without a
PASS marker. The comparator requires the actual native exit code, completion
count, gzip CRC/trailer, contiguous record IDs, unchanged original DCP/EDIF/
logical-census hashes, frozen mapping/report hashes, and the producer source
snapshots. It publishes a new hash-bound JSON receipt without replacing an older
receipt. Legacy streams additionally require their protected native source
receipt. Native-only dangling macro groups are allowed; total native and EDIF
canonical-net counts are not asserted equal.

The Python comparison does not repeat the old global endpoint-ownership check.
Its complete-census cut claim relies on native electrical alias closure and the
producer's coverage check. Local conflicting duplicates and duplicate crossing
fingerprints fail. A successful comparison checks all native canonical groups,
including groups that do not appear in the reported crossing list; it does not
query only preselected cut boundaries.

## Advanced comparison interface

Run from RapidWright using fresh output directories. Schedule memory-loaded
native jobs to fit available resources and preserve the actual owned process
exit, source/export identities and completion records. The tools do not provide
a global job scheduler. No local experiment controller or workspace lease is a
standalone dependency.

The commands below assume a completed source export and its association receipt.
`--source-export-receipt` requires `status: PASS`, equal
`checkpoint_sha256_before`/`checkpoint_sha256_after`, and `exports_sha256` entries
for `post_synth.edf` and `logical_leaf_primitives.tsv`. Those exact bytes must
match the supplied checkpoint, EDIF and logical inventory. The caller must retain
how that association was actually exported and its native completion/exit proof;
writing matching hash fields alone does not establish electrical provenance.
The standard frontend's `metadata.json`/`source_pair_provenance.json` are a
separate interface, not interchangeable comparator receipts.

```powershell
vivado -mode batch `
  -source tools/export_partition_native_cuts.tcl `
  -log FRESH_NATIVE_DIR/vivado.log -journal FRESH_NATIVE_DIR/vivado.jou `
  -tclargs --export-native-cuts SOURCE.dcp FRESH_NATIVE_DIR auto 2147483648 28800

python tools/compare_native_cuts_stream_v3.py `
  --bulk FRESH_NATIVE_DIR/native_net_endpoint_lists.tcl.gz `
  --native-log FRESH_NATIVE_DIR/native_endpoint_complete.txt --native-exit-code 0 `
  --checkpoint SOURCE.dcp --edif REFERENCE/post_synth.edf `
  --logical-primitives REFERENCE/logical_leaf_primitives.tsv `
  --source-export-receipt REFERENCE/export_receipt.json `
  --case k1 CASE1/mapping.txt CASE1/fpga_net_crossings.tsv CASE1/candidate_NN/primitive_counts.tsv `
  --case k2 CASE2/mapping.txt CASE2/fpga_net_crossings.tsv CASE2/candidate_NN/primitive_counts.tsv `
  --case k3 CASE3/mapping.txt CASE3/fpga_net_crossings.tsv CASE3/candidate_NN/primitive_counts.tsv `
  --case k4 CASE4/mapping.txt CASE4/fpga_net_crossings.tsv CASE4/candidate_NN/primitive_counts.tsv `
  --output NEW_COMPARISON.json
```

The native command and comparison are separate jobs. Set `--native-exit-code`
to the captured actual exporter exit; zero in the example is a successful-run
placeholder, not permission to substitute a success value after a failed run.
A native process that is interrupted or exceeds its limits provides no complete
proof. The listed comparison checks endpoints, destinations and primitive
histograms; it does not claim functional-control category parity without the
sidecar described below.

### Functional synchronous-control categories

For the current `functional_synchronous_controls_v2_UNISIM_2025_1` scope,
source `export_control_scope_metadata.tcl` in the already-open unchanged original
Vivado design and call `rw_control_scope_metadata OUTPUT_TSV LOGICAL_INVENTORY`.
This is a source-only procedure, not an independent checkpoint-launch command.
The caller must preserve a completed, actual-exit-bound metadata contract for
`compare_native_cuts_stream_v3.py --control-metadata-receipt RECEIPT`, plus one
`--control-categories CASE RW_CONTROL_REPORT` pair per comparison case.

The contract binds checkpoint, EDIF, logical inventory, endpoint bulk, metadata
and producer SHA256 identities, the metadata path/count, producer interface 3,
Vivado 2025.1, actual native exit zero, control scope and default authority.
The comparator validates those fields and exact report coverage. Its automatic
source reader does not generate this caller contract. Missing/stale contracts
cannot acquire a category-PASS claim from an endpoint-only comparison.

Select each histogram from its actual accepted candidate in `STATUS.json`.
Pass exit code 0 only when the owned native process actually returned 0.
Producer defaults bound compressed output to 2 GiB and export work to 8 hours;
the caller must also enforce a wall-clock limit during native API calls.
The consumer's default maximum physical record is 1 GiB, adjustable through
`--max-record-bytes`; exceeding it fails rather than skipping a large clock net.
Full mesh has 4,060,110 clock input pins, so a single record and its decoded
objects can occupy several GiB. Run the consumer after the native reader exits.
These are full-census tools; the final J4 NV/mesh proofs used a distinct
mapping-bound boundary/all-owner scope recorded in the
[validation report](../doc/partition/VALIDATION_REPORT.tex). Do not relabel one
proof kind as the other.

## Historical producer measurements and planning

The original eight host tests passed, including exact agreement with the old flatten/comparator
for constants, macro boundaries, top I/O, INOUT, aliases and 1/2/3/4 assignments;
gzip truncation, incomplete census, record bounds, changed source, duplicate
crossings, no-overwrite receipts, host-Tcl compression modes and mocked native
coverage/budget failure.

One actual Vivado 2025.1 SHA4 producer smoke also passed, using native Tcl
`zlib`: 17,228 canonical groups and all 18,844 enumerated hierarchical segments
in 20.275 seconds of export work. Its 505,372-byte gzip expands to bytes
identical to the preserved 4,647,552-byte complete native SHA4 census
(SHA256 `dfa65e239b3fa86896025a20c6b89a79d44dae1e63056f63c9415999c3bf38e5`).
Every canonical group and pin/reference/direction/top-port field matched. The
same fresh stream checked sparse k3 at 1,072 crossing nets / 1,102 directed
destinations and timing k4 at 1,244 / 1,348, with zero cut or primitive-histogram
mismatches. The owned native process and launcher actually exited 0; peak RSS
was 2.54 GiB and the minimum observed global free RAM was 48.21 GiB. All source,
map and script hashes remained unchanged. The detailed small-smoke receipt was a historical local artifact and is not
shipped with this source release.
This establishes the new producer on SHA4, not on either full large source.

The new consumer also passed the complete existing OpenTitan native census of
492,939 canonical groups against the frozen original J4 1/2/3/4 mappings in one
27-second scan: crossing nets 0/2,003/3,237/3,905 and directed destinations
0/2,003/3,435/4,045, with zero missing, extra, pair or primitive-histogram
mismatches. The detailed historical local receipts are not standalone dependencies. Replacement
v2/v3 mappings require another comparison; this proof does not cover them.

A read-only compression probe of the protected 536,200,925-byte OpenTitan raw
census produced 32,053,693 gzip bytes (5.98%) in 2.6 seconds, discarded in memory.
Source bytes/hash matched the old receipt. The following were **early planning
projections**, not measured full-source runtimes or current completion estimates:

| Input | Estimated raw + endpoint TSV | Estimated gzip raw only | Native traversal uncertainty |
| --- | ---: | ---: | --- |
| Full NVDLA | 8–9 GB | about 0.2 GB | roughly 1–4 hours |
| Full 3,072-CPU mesh | about 22 GB | about 0.45 GB | roughly 4–16 hours |

These are projections from measured mesh24 and OpenTitan endpoint density and
251–957 canonical groups/second, not measured large-case results. Native load,
global clock/constant fanouts and alias depth can change runtime substantially.
These historical resource observations do not define a universal admission rule.
A full native collection and its alias dictionary can grow linearly with the
whole design; gzip does not bound their resident memory. Choose explicit disk,
RAM and wall-clock budgets for the machine and monitor the actual owned job.
Final measured J4 evidence is in the [validation report](../doc/partition/VALIDATION_REPORT.tex),
which distinguishes complete source census from mapping-bound boundary coverage.
A stopped or partial traversal remains incomplete.

## Producer optimization scope

The proven implementation already queries pin properties in bulk per net; it
contains no per-endpoint `get_cells` or `get_property` loop. AMD's
[Vivado 2025.1 get_nets reference](https://docs.amd.com/r/2025.1-English/ug835-vivado-tcl-commands/get_nets)
documents `-top_net_of_hierarchical_group` for the highest segment and `-segments`
for all aliases. Enumerating native top groups could remove the large Tcl alias
dictionary. It must first prove complete disconnected/internal macro coverage
and identical endpoint groups on SHA4 and the full OpenTitan golden census.
Highest hierarchy and lexicographically smallest alias are different names;
all-segment endpoint gathering and fingerprint semantics must be retained.
This note does not qualify that alternative full-census producer. Later
mapping-bound exporter work has its own exact-map evidence and scope in the
validation report; it is not a universal equivalence or speedup claim for every
possible alias/port query.
