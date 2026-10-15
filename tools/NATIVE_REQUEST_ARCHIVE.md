# Lossless native request archives

`archive_native_request.py` preserves disk between large partition iterations. Invoke it only after the per-request native Vivado process has finished and `VivadoResourceOracle` has returned exit code zero for that mapping. The helper independently checks `native_complete.txt`, the resource table, checkpoint and mapping hashes, source mapping bytes and partition labels before archiving.

From the RapidWright repository:

```powershell
python tools/archive_native_request.py --directory PATH_TO_NATIVE_REQUEST --mapping PATH_TO_MAPPING --oracle-exit-code 0 --apply
```

Without `--apply`, the helper prints a validated plan. The default minimum size is 1 MiB; `--minimum-bytes 0` includes small eligible files too. `--workspace-root` defaults to the parent of this RapidWright checkout and constrains all request directories. Archives stay beside their original files.

Only nonrecursive `*.rpt` and `native_cells_FPGA_*.txt` files are eligible. Tables, completion/provenance, `source_mapping.txt`, preassigned manifests/hashes, logs, `frontier.tsv`, final assignments and mapping files stay plain. Java resource imports consume the sealed table/provenance and original checkpoint/mapping identities, so these imports remain usable after reports and cell-name lists are compressed. Restore cell-name lists before rerunning native Vivado Tcl in that same request directory.

Each `.gz` has a plain `.archive.json` receipt containing original/compressed SHA256 and byte counts, the stream-decompression verification and native request seal identity. The exact original is removed only after round-trip/source stability checks and durable receipt publication. A directory kernel lock prevents concurrent archive/restore calls. Existing different archives and changed restored originals are rejected; identical verified archives can be reused.

Restore exact original bytes with:

```powershell
python tools/archive_native_request.py --directory PATH_TO_NATIVE_REQUEST --restore --apply
```

Restoration retains compressed archives and receipts and checks their hashes before writing. Source paths must remain inside the request directory. The restore command also supports the historical completed-case human-report receipt format; no design-repository coordinator is required.

Run the six bounded regressions with:

```powershell
python -m unittest discover -s tools -p test_archive_native_request.py -v
```

A historical native NVDLA archive integration (local receipt not shipped) established this narrower observation: every human report and native cell-name list was absent, while the frozen Java oracle reimported the original NVDLA table/provenance and reproduced 1,703,727 design CLB LUTs and all four FPGA resource/cap checks. Protected plain metadata/manifests/assignment anchors remained unchanged.

See the [validation report](../doc/partition/VALIDATION_REPORT.tex) for current
large-design results and portable evidence. An archive receipt proves retained
bytes and the recorded request identity; it does not establish new partition
quality or routing/timing closure.
