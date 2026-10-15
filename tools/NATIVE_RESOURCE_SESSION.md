# Reuse a native resource oracle

For feedback iterations whose next mapping is not yet known, keep one Vivado
process open and submit prepared mappings sequentially:

```python
from native_resource_session import NativeResourceSession

with NativeResourceSession(checkpoint, report_script, session_output, vivado) as native:
    # Run prepare_native_resource_cells.py for this mapping/output first.
    table = native.evaluate(mapping, native_output)
    # Apply the real VivadoResourceOracle and its hard-cap gate before acceptance.
```

The first request opens the checkpoint. Later requests source the unchanged
`report_partition_resources.tcl` report body and reuse that design. The loaded
checkpoint hash and report-script hash remain fixed. Each response binds its
request ID, mapping bytes, native provenance and completion record. Changed
source bytes or incomplete requests fail; table existence alone is insufficient.
The frontend option `--native-timeout-seconds` sets a finite positive wall-clock
wait for persistent checkpoint startup and separately for each resource request.
Its default is 7200 seconds; the direct Python session API has the same default
and accepts `timeout=...`. Unrelated protocol output does not restart the timer.
This is a runtime guard, chosen to accommodate an observed full3072 checkpoint
load and reports that exceeded the previous 1800-second wait. It is not a
partition-quality constant or a speed guarantee. A timeout still fails the
request and closes the owned session; existing native provenance and cap
acceptance remain mandatory. Batch reporting is unaffected.

The context closes the owned process with `STOP`. Output streams to disk in
bounded chunks, so expanded cell-list headers do not become unbounded Python
strings.

Persistence retains the native design in memory during the next Java solve.
The high-level frontend first probes a session after Java exits, provided its
conservative native-only estimate plus 4 GiB fits available physical RAM.
Before each later Java launch it measures available RAM again, which already
excludes the resident Vivado process. Reuse requires that measurement to cover
the Java maximum heap plus 8 GiB and a separate native solver reserve. The
solver reserve is 2 GiB plus 32 times the largest completed candidate HGR size,
multiplied by ceil(threads/4); unknown HGR size selects batch. This heuristic
budgets the C++/WSL backend that can overlap Java, and is recorded in each
residency decision. Insufficient or unknown RAM closes the session
and confirms process exit before Java starts; subsequent checks use batch mode.
The estimates govern runtime residency and do not describe FPGA capacity.
Concurrent large jobs must still serialize their memory-loaded phases. The
batch reporter remains available for one iteration or `--native-session off`;
the final Java Oracle is mandatory in either mode.

A historical source-bound SHA proof (local receipt not shipped) opened one checkpoint for two
successive mappings. Tables were byte-identical to recorded batch results and
both real Java cap checks passed. Evaluation took 45.815 seconds initially and
2.323 seconds on reuse. Clean shutdown and changed-checkpoint rejection passed.
These timings describe that small benchmark, not a general speed guarantee.

Current large-design validation and portable evidence are described in the
[validation report](../doc/partition/VALIDATION_REPORT.tex). The historical
small-proof timings above do not establish a general speedup or fresh proof
for another source/mapping. No local experiment folder is a runtime dependency.
