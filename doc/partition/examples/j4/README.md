# J4 mapping examples and readable reports

These are compact review copies of 16 qualified J4 results: four designs,
each assigned across one, two, three or four VP1902 FPGAs. Each folder contains
the ordered `mapping.txt`, its `cells.txt`, the three PNGs and an offline HTML
report. Open a report below, or download the folder and open `report.html`.

| Design | One FPGA | Two FPGAs | Three FPGAs | Four FPGAs |
|---|---|---|---|---|
| OpenTitan Earl Grey | [Report](opentitan_earl_grey/k1/report.html) / [mapping](opentitan_earl_grey/k1/mapping.txt) | [Report](opentitan_earl_grey/k2/report.html) / [mapping](opentitan_earl_grey/k2/mapping.txt) | [Report](opentitan_earl_grey/k3/report.html) / [mapping](opentitan_earl_grey/k3/mapping.txt) | [Report](opentitan_earl_grey/k4/report.html) / [mapping](opentitan_earl_grey/k4/mapping.txt) |
| NVIDIA NVDLA | [Report](nvdla/k1/report.html) / [mapping](nvdla/k1/mapping.txt) | [Report](nvdla/k2/report.html) / [mapping](nvdla/k2/mapping.txt) | [Report](nvdla/k3/report.html) / [mapping](nvdla/k3/mapping.txt) | [Report](nvdla/k4/report.html) / [mapping](nvdla/k4/mapping.txt) |
| PicoRV32 mesh, 3,072 cores | [Report](riscv_mesh_3072/k1/report.html) / [mapping](riscv_mesh_3072/k1/mapping.txt) | [Report](riscv_mesh_3072/k2/report.html) / [mapping](riscv_mesh_3072/k2/mapping.txt) | [Report](riscv_mesh_3072/k3/report.html) / [mapping](riscv_mesh_3072/k3/mapping.txt) | [Report](riscv_mesh_3072/k4/report.html) / [mapping](riscv_mesh_3072/k4/mapping.txt) |
| AES farm | [Report](aes_farm/k1/report.html) / [mapping](aes_farm/k1/mapping.txt) | [Report](aes_farm/k2/report.html) / [mapping](aes_farm/k2/mapping.txt) | [Report](aes_farm/k3/report.html) / [mapping](aes_farm/k3/mapping.txt) | [Report](aes_farm/k4/report.html) / [mapping](aes_farm/k4/mapping.txt) |

These files preserve the bytes of the preferred outputs from
`RESULTS FOR J4/CONTRACT_COMPLIANT_20261020` in the original workspace. The
[archive manifest](archive_manifest.json) records every copied file's SHA-256
and the source backfill receipt, whose SHA-256 is
`42b42f9864e42e752cf4a93b363e5b00f219cf9dde24db25645ff695abf5fb16`.
All relative links inside each HTML report point to files in that same folder.

## What the evidence means

The LUT and cut measurements are **historical Vivado results preserved by an
exact ownership comparison**, not fresh measurements of a new run. The legal
successor mappings were checked against the exact synthesized hierarchy and
saved assignment. Every functional leaf and the residual root/top-port owner
kept its original FPGA owner. Their reports explicitly say
`HISTORICAL_METRICS_PRESERVED_BY_EXACT_OWNERSHIP_EQUIVALENCE`.

The adjacent `mapping_contract.json` records the mapping check;
`reencoding_equivalence.json` records the unchanged ownership argument; and
`prior_native_evidence.json` identifies the original native measurements and
their hashes. The original native proof remains tied to its historical mapping
bytes. These small files are evidence pointers, not substitutes for the
complete source and proof data.

For an independent re-audit, obtain the exact original EDIF/checkpoint and
the complete original ownership, connection and native evidence inventories.
Those large files, source netlists and Vivado outputs are deliberately absent
from this compact archive. Some hashes in the copied JSON files refer to those
omitted originals; copying the JSON does not rerun their checks.

## Using a mapping

Use a mapping only with its corresponding exact source netlist, identified in
the adjacent evidence files. Keep `cells.txt` with it, preserve record order,
and follow the [mapping contract](../../README.md#required-mapping-contract).
The records extract children before ancestors and end with explicit residual
root ownership. Do not sort the mapping or apply it to a different synthesis
result by assuming similar hierarchy names are equivalent.

These examples were found using the recorded historical search settings.
They do not demonstrate that the current automatic balance defaults reproduce
the same assignment or always improve it. No new partition search, Vivado
measurement, wrapping, regrouping, transport insertion, routed timing or
hardware test was performed to create these review copies.
