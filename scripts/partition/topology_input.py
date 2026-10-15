"""Read physical board schema c/1 without editing the source document.

Native METIS weights are hop-dominant integer routing preferences with a
quantized inverse-lane tie breaker, not measured latency or routed bandwidth.
"""
import csv
import hashlib
import json
import math
from pathlib import Path


def _integer(value, name, minimum=0):
    if type(value) is not int or value < minimum:
        raise ValueError(f"{name} must be an integer >= {minimum}")
    return value


def _text(value, name):
    if not isinstance(value, str) or not value or value != value.strip() or any(c in value for c in "\t\r\n"):
        raise ValueError(f"{name} must be a nonempty single-line string")
    return value


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate topology JSON key: " + key)
        result[key] = value
    return result


def _invalid_number(value):
    raise ValueError("Nonfinite topology JSON number: " + value)


def _finite_number(value):
    result = float(value)
    if not math.isfinite(result):
        _invalid_number(value)
    return result


def _hash(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def _costs(k, lanes):
    # Quantization bounds all secondary penalties, even for unusual lane ratios.
    # Removing their common divisor gives the smallest equivalent supplied
    # penalty units: 8/16 lanes become 1/0 rather than 512/0.
    scale, minimum = 1024, min(lanes, default=1)
    inverse = {lane: (scale * minimum + lane - 1) // lane for lane in lanes}
    best = min(inverse.values(), default=0)
    raw = {lane: value - best for lane, value in inverse.items()}
    divisor = math.gcd(*raw.values()) or 1
    penalties = {lane: value // divisor for lane, value in raw.items()}
    base = (k - 1) * max(penalties.values(), default=0) + 1
    return {lane: base + penalty for lane, penalty in penalties.items()}, {
        "kind": "hop_dominant_then_quantized_inverse_lanes",
        "hop_base": base, "rapidwright_cost_scale": base,
        "inverse_scale": scale, "penalty_common_divisor": divisor,
        "cost_by_lanes": {str(lane): base + penalties[lane] for lane in sorted(penalties)},
        "route_guarantee": "One extra link outweighs secondary penalties on any simple route or Steiner tree with at most k-1 links",
        "scope": "Scalar native objective across all nets; not a global lexicographic guarantee, measured latency, or hard routed link capacity",
    }


def load_board_topology(source, output, label_for_index, fpga_count=None, selected_ids=None):
    """Return (k, METIS path, complete-or-empty canonical CLB cap dictionary).

    The complete source is validated before selecting an induced subgraph.
    fpga_count chooses the first sorted IDs; selected_ids chooses explicit
    IDs. Physical IDs remain unchanged and sorted native order is recorded.
    """
    source, output = Path(source).resolve(strict=True), Path(output).resolve()
    original = source.read_bytes()
    source_hash = hashlib.sha256(original).hexdigest()
    data = json.loads(original.decode("utf-8-sig"), object_pairs_hook=_unique_object,
                      parse_constant=_invalid_number, parse_float=_finite_number)
    if not isinstance(data, dict) or data.get("schema") != "c/1":
        raise ValueError("Expected physical board topology schema c/1")
    devices, edges = data.get("fpgas"), data.get("edges")
    if not isinstance(devices, list) or not 2 <= len(devices) <= 64 or not isinstance(edges, list):
        raise ValueError("c/1 requires 2..64 FPGA devices and an edges list")
    k = len(devices)
    by_id, by_path = {}, {}
    for device in devices:
        if not isinstance(device, dict):
            raise ValueError("Every FPGA device must be an object")
        identity = _integer(device.get("id"), "FPGA id")
        path = _text(device.get("path"), "FPGA path")
        if identity in by_id or path in by_path:
            raise ValueError("Duplicate physical FPGA ID/path")
        for key in ("chassis", "slot", "position"):
            if key in device:
                _text(device[key], key)
        if all(key in device for key in ("chassis", "slot", "position")):
            components = [_text(device[key], key) for key in ("chassis", "slot", "position")]
            if path != "/".join(components):
                raise ValueError("Physical FPGA path differs from chassis/slot/position")
        by_id[identity], by_path[path] = device, identity
    ordered_ids = sorted(by_id)
    source_caps = {identity: _integer(device["clb_luts"], "clb_luts")
                   for identity, device in by_id.items() if "clb_luts" in device}
    if source_caps and len(source_caps) != k:
        raise ValueError("Specify clb_luts for every FPGA or none")
    wires_per_lane = data.get("wires_per_lane_at_min_compression")
    if "wires_per_lane_at_min_compression" in data:
        _integer(wires_per_lane, "wires_per_lane_at_min_compression", 1)
    adjacency = {identity: {} for identity in ordered_ids}
    pairs = set()
    for edge in edges:
        if not isinstance(edge, dict):
            raise ValueError("Every physical edge must be an object")
        a, b = (_integer(edge.get(key), key) for key in ("a_id", "b_id"))
        if a not in by_id or b not in by_id or edge.get("a") != by_id[a]["path"] or edge.get("b") != by_id[b]["path"]:
            raise ValueError("Edge ID/path endpoints do not identify the same physical FPGA")
        pair = tuple(sorted((a, b)))
        if a == b or pair in pairs:
            raise ValueError("Physical edges must be unique non-self undirected pairs")
        pairs.add(pair)
        lanes = _integer(edge.get("lanes"), "lanes", 1)
        if "wires_at_min_compression" in edge:
            wires = _integer(edge["wires_at_min_compression"], "wires_at_min_compression", 1)
            if wires_per_lane is not None and wires != lanes * wires_per_lane:
                raise ValueError("Edge wire count differs from declared lanes times wires_per_lane")
        if "cross_slot" in edge:
            if type(edge["cross_slot"]) is not bool:
                raise ValueError("cross_slot must be boolean")
            if all(key in by_id[identity] for identity in (a, b) for key in ("chassis", "slot")):
                crosses = any(by_id[a][key] != by_id[b][key] for key in ("chassis", "slot"))
                if edge["cross_slot"] != crosses:
                    raise ValueError("cross_slot differs from physical chassis/slot identity")
        adjacency[a][b] = adjacency[b][a] = lanes
    reached, stack = set(), [ordered_ids[0]]
    while stack:
        identity = stack.pop()
        if identity not in reached:
            reached.add(identity)
            stack.extend(adjacency[identity])
    if len(reached) != k:
        raise ValueError("Physical FPGA topology must be connected")
    for identity, device in by_id.items():
        if "degree" in device and _integer(device["degree"], "degree") != len(adjacency[identity]):
            raise ValueError("FPGA degree differs from physical edges")
    if "adjacency" in data:
        declared = data["adjacency"]
        if not isinstance(declared, dict) or set(declared) != set(by_path):
            raise ValueError("Declared adjacency must cover exactly the physical FPGA paths")
        for path, row in declared.items():
            expected = {by_id[neighbor]["path"]: lanes for neighbor, lanes in adjacency[by_path[path]].items()}
            if not isinstance(row, dict) or set(row) != set(expected):
                raise ValueError("Declared adjacency differs from physical edge endpoints")
            if any(_integer(value, "adjacency lanes", 1) != expected[neighbor] for neighbor, value in row.items()):
                raise ValueError("Declared adjacency lane counts differ from physical edges")
    possible, lane_values = k * (k - 1) // 2, {edge["lanes"] for edge in edges}
    for key, expected in (("fpga_count", k), ("possible_pairs", possible), ("connected_pairs", len(edges)),
                          ("lane_min", min(lane_values)), ("lane_max", max(lane_values))):
        if key in data and _integer(data[key], key) != expected:
            raise ValueError("Topology summary does not match edges: " + key)
    for key, expected in (("complete_graph", len(edges) == possible), ("lane_uniform", len(lane_values) == 1)):
        if key in data and (type(data[key]) is not bool or data[key] != expected):
            raise ValueError("Topology summary does not match edges: " + key)
    if "connectivity_fraction" in data:
        fraction = data["connectivity_fraction"]
        if (type(fraction) not in (int, float) or not math.isfinite(fraction)
                or not math.isclose(fraction, len(edges) / possible, rel_tol=0, abs_tol=0.000050000001)):
            raise ValueError("connectivity_fraction differs from edges, allowing four-decimal rounding")
    source_k, source_edge_count, source_adjacency = k, len(edges), adjacency
    if fpga_count is not None:
        _integer(fpga_count, "fpga_count selection", 1)
        if fpga_count > source_k:
            raise ValueError("fpga_count selection exceeds the source board")
    if selected_ids is None:
        ordered_ids = ordered_ids[:fpga_count] if fpga_count is not None else ordered_ids
    else:
        if not isinstance(selected_ids, (list, tuple, set)) or not selected_ids:
            raise ValueError("selected_ids must be a nonempty collection of physical IDs")
        selected_ids = [_integer(identity, "selected physical ID") for identity in selected_ids]
        if len(set(selected_ids)) != len(selected_ids) or any(identity not in by_id for identity in selected_ids):
            raise ValueError("Selected physical IDs must be unique and present in the source")
        if fpga_count is not None and fpga_count != len(selected_ids):
            raise ValueError("fpga_count and selected_ids disagree")
        ordered_ids = sorted(selected_ids)
    k, selected = len(ordered_ids), set(ordered_ids)
    edges = [edge for edge in edges if edge["a_id"] in selected and edge["b_id"] in selected]
    adjacency = {identity: {neighbor: lanes for neighbor, lanes in source_adjacency[identity].items() if neighbor in selected}
                 for identity in ordered_ids}
    reached, stack = set(), [ordered_ids[0]]
    while stack:
        identity = stack.pop()
        if identity not in reached:
            reached.add(identity)
            stack.extend(adjacency[identity])
    if len(reached) != k:
        raise ValueError("Selected induced FPGA topology must be connected")
    indices = {identity: index for index, identity in enumerate(ordered_ids)}
    labels = [_text(label_for_index(index), "FPGA partition label") for index in range(k)]
    if len(set(labels)) != k:
        raise ValueError("Canonical FPGA labels must be unique")
    caps = {labels[indices[identity]]: source_caps[identity] for identity in ordered_ids} if source_caps else {}
    possible, lane_values = k * (k - 1) // 2, {edge["lanes"] for edge in edges}
    costs, cost_policy = _costs(k, lane_values)
    graph, device_table, manifest_path = (output / name for name in ("system.graph", "fpga_devices.tsv", "topology_manifest.json"))
    if any(path == source or path.exists() for path in (graph, device_table, manifest_path)):
        raise ValueError("Topology output must not overwrite existing files or the source")
    if _hash(source) != source_hash:
        raise ValueError("Physical topology source changed while reading")
    output.mkdir(parents=True, exist_ok=True)
    graph_lines = [f"% rapidwright_cost_scale={cost_policy['hop_base']}", f"{k} {len(edges)} 1"]
    for identity in ordered_ids:
        graph_lines.append(" ".join(f"{indices[neighbor] + 1} {costs[lanes]}"
                                    for neighbor, lanes in sorted(adjacency[identity].items())))
    graph.write_text("\n".join(graph_lines) + "\n", encoding="utf-8")
    exported_devices = [dict(by_id[identity], partition=labels[index], partition_index=index, metis_vertex=index + 1,
                             source_degree=len(source_adjacency[identity]), selected_degree=len(adjacency[identity]))
                        for index, identity in enumerate(ordered_ids)]
    with device_table.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(("partition", "partition_index", "metis_vertex", "physical_id", "path", "chassis", "slot", "position", "source_degree", "selected_degree", "clb_luts"))
        for device in exported_devices:
            writer.writerow((device["partition"], device["partition_index"], device["metis_vertex"], device["id"], device["path"],
                             device.get("chassis", ""), device.get("slot", ""), device.get("position", ""),
                             device["source_degree"], device["selected_degree"], device.get("clb_luts", "")))
    exported_edges = [dict(edge, partition_a=labels[indices[edge["a_id"]]], partition_b=labels[indices[edge["b_id"]]],
                           native_cost=costs[edge["lanes"]]) for edge in edges]
    manifest = {"schema": "c/1", "board": data.get("board"), "source": str(source), "source_sha256": source_hash,
                "fpga_count": k, "connected_pairs": len(edges), "missing_pairs": possible - len(edges),
                "source_fpga_count": source_k, "source_connected_pairs": source_edge_count, "selected_ids": ordered_ids,
                "rapidwright_cost_scale": cost_policy["hop_base"],
                "native_order": "sorted nonnegative physical ID", "cost_policy": cost_policy,
                "fpgas": exported_devices, "edges": exported_edges, "source_document": data,
                "graph_sha256": _hash(graph), "devices_sha256": _hash(device_table),
                "wires_semantics": "Declared minimum-compression metadata preserved and arithmetic checked; not assumed to be raw pins, achievable per-cycle capacity, a TDM ratio or measured latency",
                "hard_routed_bandwidth_enforced": False, "measured_link_delay_ns": None}
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=True, allow_nan=False) + "\n", encoding="utf-8")
    if _hash(source) != source_hash:
        raise ValueError("Physical topology source changed during export")
    return k, graph, caps
