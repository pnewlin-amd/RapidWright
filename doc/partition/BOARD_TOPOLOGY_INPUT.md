# Physical board topology input

`scripts/partition/topology_input.py` reads physical `schema: "c/1"` board
documents without editing them. Exact J4/P9 regression fixtures are included
under `test/resources/partition/topologies/`; they are not device-part
declarations. Supply actual targets as described in
[target parts and balance](TARGET_PARTS_AND_BALANCE.md). Its API is
`load_board_topology(source, output, label_for_index, fpga_count=None, selected_ids=None)`.
Output must not overwrite
an existing graph, manifest, device table or source file.

Sorted nonnegative physical IDs determine native vertex order. IDs may be
noncontiguous. `fpga_devices.tsv` records each original ID/path against its
canonical mapping label and one-based METIS vertex. `topology_manifest.json`
retains the complete original document, source SHA256, physical devices,
edges, lane/wire counts, cross-slot metadata and the derived cost policy.
Missing physical edges remain absent. Endpoint ID/path mismatches, duplicate
edges/devices, disconnected graphs and inconsistent declared summaries,
degrees, adjacency or wire arithmetic fail validation.

The default selects every source FPGA. `fpga_count=N` selects the first N
sorted physical IDs; `selected_ids=[...]` selects explicit IDs in sorted native
order. If both are supplied, their sizes must agree. The entire source board is
validated first, including unselected nodes and links. The emitted graph is the
exact connected induced subgraph; selection never inserts a missing link.
Manifests retain `source_fpga_count`, `source_connected_pairs`, `selected_ids`
and the full original `source_document`. Device rows distinguish
`source_degree` from `selected_degree`.

For the original four-FPGA j4 board, selecting 1/2/3/4 FPGAs yields exactly
0/1/3/6 physical edges. A singleton has METIS header `1 0 1`, one blank adjacency
row, scale 1 and no selected link capacities. Its original physical ID/path
remains visible in the device table. The four-device input file is unchanged.

Separate illustrative systems can be created with
`scripts/partition/generate_board_topology.py chain 3 NEW_OUTPUT.json` or
`scripts/partition/generate_board_topology.py grid 4 7 NEW_OUTPUT.json`.
Exclusive file creation rejects an existing target. The generated documents
are explicitly experimental, with synthetic physical paths and unverified
hardware identity. A chain has 16-lane links; a grid has 16-lane horizontal
links and 8-lane vertical links. Choose a new output path for each example;
they do not replace a supplied board file or establish validation on that
hardware. The [validation report](VALIDATION_REPORT.tex) describes the actual
J4 subset experiments; larger synthetic topologies are separate experiments.

The native graph is weighted METIS. Inverse lane counts supply a bounded,
quantized secondary penalty; a larger lane count never receives a larger
penalty. Common penalty factors are removed. The hop base is
`(k - 1) * maximum_penalty + 1`, so an extra link outweighs every secondary
penalty on an individual simple route or Steiner tree with at most `k - 1`
links. The supplied j4 graph has six uniform links of cost 1. The supplied
p9 graph retains exactly 84 links: 16-lane links cost 24 and 8-lane links cost
25. This scalar preference over all nets is not a global lexicographic
optimization or hard link-congestion constraint.

`% rapidwright_cost_scale=24` on p9 tells RapidWright to normalize internal
distances back to roughly hop units, while Mt-KaHyPar receives the original
integer costs. Uniform j4 uses scale 1. Relative cost is not nanoseconds.
Cross-slot links retain their flag but receive no fabricated measured delay.

The path objective additionally uses **minimum link hops**, computed by
breadth-first search on the selected physical adjacency before weighted
cost closure. It does not floor normalized METIS costs. For an original
modeled register-free path, H sums h(A,B) over consecutive FPGA owners;
R independently sums max(0,h(A,B)−1). Thus one logical crossing between
the ends of a four-device chain has H=3 and R=2. The supplied complete J4
subsets have H equal to logical crossings and R=0. Sparse systems can have
larger H even when the mapping has only one logical boundary change.

Default source-only ranking uses worst H, then worst logical crossings,
then sampled timing cost and other structural measures. Default
`--quality-guard ranked` applies that priority to retained passes and
accepted anchors: a higher-priority improvement may permit a secondary
regression. `componentwise` requires each reference quality measure,
including H, to remain nonregressing. With an explicit delay model,
modeled slack and sampled timing cost precede H in ranked mode. Resource,
fixed, occupancy, requested native capacity and original cut/topology
budgets remain independent checks in both modes.
R is a report-only lower bound; it counts
intermediate routing-FPGA traversals, not combinational transit primitives,
and its independent maximum has no nonregression guarantee. These metrics
are relative to the declared selected graph and the original modeled
launch/capture boundaries. Actual routes can be longer; transport registers
can segment those paths. Neither is inferred here.

Explicit `--extra-delay-ns` still means nanoseconds per **logical** crossing.
Modeled slack and sampled timing cost retain priority in that mode, followed
by H and logical metrics. No per-link delay is invented. The low-level Java
CLI's implicit complete-graph fallback marks physical-hop analysis
unavailable; it does not assert that every real device pair is connected.

The files declare `wires_per_lane_at_min_compression: 64` and per-edge wire
counts equal to `lanes * 64`. That consistency is checked; the values remain
declared metadata. The loader does not infer raw package pins, usable wires
per DUT cycle, a serialization ratio, directional capacity or physical latency.
The document's relay-cycle note is preserved as source metadata, rather than
converted into an unverified timing measurement.
In particular, H/R do not certify that the source document's relay-cycle
description matches a particular wrapper, clock relationship or TDM schedule.

Public vendor descriptions explain why this distinction matters: Synopsys
describes [high-speed time-domain multiplexing and its interconnect](https://news.synopsys.com/home?item=123433),
while Cadence describes [routing many virtual signals over limited physical wires](https://www.cadence.com/en_US/home/explore/enterprise-fpga-prototyping.html).
Neither inspected reference defines this input field's 64-unit convention.
Synopsys also describes [link characterization using FPGA, PCB and cable delay](https://www.synopsys.com/blogs/chip-design/ensuring-reliability-haps-physical-prototyping-systems.html).
Lane count alone supplies neither that characterization nor a transport
schedule. A future hard bandwidth model needs the platform's documented
directionality, usable capacity, TDM clock/compression policy and routing rules.
