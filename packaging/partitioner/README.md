# RapidWright hardware-aware partitioner runtime

This ZIP is a prebuilt mapping-only runtime. Java classes, dependency JARs,
Python helpers, the verified part catalog and documentation are included.
Keep this directory together; no Gradle build is needed for this bundle.

Install Python 3.11+ with Tcl/Tk, Java 17+, Vivado 2025.1 and **Mt-KaHyPar
v1.6.2** separately. Dependency executables may be on PATH. The
[complete manual](doc/partition/README.md) explains environment variables,
explicit tool options and optional WSL backend configuration.

```powershell
python -m pip install -r scripts/partition/requirements.txt
.\partitioner.cmd --check-tools
.\partitioner.cmd --input design.dcp --system-topology system.json --fpga-count 4
```

On Linux use `./partitioner` in place of `partitioner.cmd`. Omit
`--fpga-count` to use all devices in the topology. Target parts must be in
the topology, a matching `.targets.json` companion, or `--target-part`.
The accepted output is `mapping.txt` in the printed result directory. Open
`report.html` beside it for the resource, I/O and connectivity figures and
the selected partition's quality summary. Matplotlib/Pillow versions are
pinned by the requirements file; the open-licensed plot font is bundled and
needs no system installation.

See [VALIDATION_REPORT.pdf](doc/partition/VALIDATION_REPORT.pdf), with its
[LaTeX source](doc/partition/VALIDATION_REPORT.tex), for the
research and measured evidence, including NVDLA and OpenTitan, and the
[included smoke source](test/resources/partition/smoke/registered_pipeline.v).
The bundle excludes Vivado, Mt-KaHyPar, large source designs and benchmark
netlists. JARs retain their embedded third-party license notices; the
RapidWright license is included alongside this file. Full source is in
the `Partitioner_Extreme_Overhaul` branch of the RapidWright fork.
