# Target parts, hard capacity and soft balance

The basic netlist/topology command needs no extra part switch when its topology
or SHA-bound companion declares the actual targets. Otherwise use
`--target-part VP1902`, a full supported part name, per-device `part` fields,
or `--fpga-parts assignment.json`. The recorded J4/P9 experiments used
user-confirmed VP1902 targets; the standalone topology regression fixtures
do not themselves declare installed devices. Assignment schema:

```json
{
  "schema": "fpga_target_parts_v1",
  "topology_sha256": "SHA256_OF_EXACT_ORIGINAL_TOPOLOGY_BYTES",
  "parts_by_physical_id": {"0": "VP1902", "1": "VU19P"}
}
```

The assignment must cover all original physical IDs, including devices outside
a selected subset. It describes hardware independently of source synthesis.
The bundled Vivado 2025.1 catalog contains 5,566 part variants.
Unknown/conflicting targets fail before partitioning. Relocated catalogs must
match the approved native export; arbitrary self-written capacities are rejected.

Each partition's hard LUT cap is
`min(explicit uniform cap, explicit per-FPGA cap, topology cap, floor(0.75*LUT_ELEMENTS))`,
omitting unspecified explicit terms. The native final oracle enforces this on
Vivado's original-design subset **CLB LUT Used** before publishing a mapping.
`--lut-utilization 0.60` tightens the policy; values above0.75 are rejected.
The75% bound is the user's engineering policy. It does not prove routing fit
and does not include later platform wrapper or transport resource overhead.
AMD's [utilization guidance](https://docs.amd.com/r/en-US/ug949-vivado-design-methodology/Lower-Device-Utilization)
also warns that difficult congestion can require lower utilization.

For equal devices, reported imbalance is `max(L)/mean(L)-1`. For heterogeneous
devices, the selection measure is `max(L/C)/mean(L/C)-1`, whereC is each
physical device's native LUT capacity. Raw subset imbalance is also retained.
An explicit lower policy cap does not changeC.

`--balance 0.20` remains a preferred soft target. The automatic search now lets
hard device capacity decide how uneven the assignment can be; it does not
force 20% or 30% balance. It sets the estimated-area search range to
`max(preferred balance, sum(device LUT capacities) / smallest device LUT capacity - 1)`.
For equal parts this is `k-1`, where `k` is the number of selected FPGAs.
The separate raw-LUT guard automatically uses `max(0.5,k-1)`.

This permits a 30% imbalance, or a larger difference, when fewer cuts or better
snake paths justify it and every FPGA remains below its hard cap. Equal path
quality favors fewer transfers and lower topology cost before fine balance.
Use explicit `--search-balance` and `--logical-balance` values if you want
tighter restrictions. Outside an explicitly requested native search range, a
candidate receives a recorded limitation and may be kept if no preferable
candidate exists; this never waives a hard LUT cap. Every requested FPGA must
contain useful nonconstant logic. Constants and clock infrastructure cannot
satisfy that requirement.

The [Mt-KaHyPar1.6.2 README](https://raw.githubusercontent.com/kahypar/mt-kahypar/v1.6.2/README.md)
documents its relative imbalance and individual part-weight constraints.
[EasyPart's ICCAD2024 paper](https://numbda.cs.tsinghua.edu.cn/papers/iccad24.pdf)
uses a different balance definition, `(1/K ± epsilon)*W`; atK=4, epsilon5%
allows20% above the average. That arithmetic is an interpretation of its
definition, not evidence that20% is always best.
[TritonPart's timing-aware algorithm](https://openroad.readthedocs.io/en/latest/main/src/par/README.html#timing-aware-algorithm)
supports using path information beyond a cut-only objective. Here structural
uncaptured crossings and topology hops are audited globally, while source STA
is a bounded, explicitly identified sample. An optional user-provided crossing
delay models slack; the partitioner does not invent link timing or claim an
achieved DUT frequency.

Compare3%,10%,20% and30% with the same source, topology and original cut-growth
budget. Relaxing balance enlarges the mathematical feasible set; a finite
seeded heuristic can still find a worse result. Preserve alternatives and
compare native resources, complete boundary endpoints and structural/STA
quality before selecting the mapping.
