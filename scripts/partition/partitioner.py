"""Topology-aware mapping with Mt-KaHyPar 1.6.2 and native Vivado acceptance.

Only reads/serializes the source netlist and writes assignment/report files.
No wrapped, regrouped, placed or routed partition netlist is produced.
"""
import argparse
import csv
import hashlib
import importlib.util
import json
import math
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import datetime, timezone

REPO = Path(__file__).resolve().parents[2]
MAIN = "com.xilinx.rapidwright.edif.partition."
SOFT_NATIVE_BALANCE_ABSOLUTE_TOLERANCE = 1e-12


def soft_native_balance_met(value, bound, tolerance=SOFT_NATIVE_BALANCE_ABSOLUTE_TOLERANCE):
    """Absorb ratio rounding only; integer resource/cut/topology gates stay exact."""
    return value <= bound or math.isclose(value, bound, rel_tol=0, abs_tol=tolerance)


def sha256(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def validate_timing_source(path, input_hash, checkpoint_hash, expected_hash=None):
    """A timing sample belongs to both the EDIF and its native checkpoint."""
    path = Path(path)
    if expected_hash is not None and sha256(path) != expected_hash:
        raise ValueError("Native timing TSV SHA256 hash does not match its export receipt")
    tags = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.startswith("#") and "=" in line:
            key, value = line[1:].split("=", 1)
            if key in tags and tags[key] != value:
                raise ValueError("Conflicting timing source identity: " + key)
            tags[key] = value
    if tags.get("input_sha256") != input_hash or tags.get("checkpoint_sha256") != checkpoint_hash:
        raise ValueError("Timing paths are not bound to the supplied source EDIF and checkpoint")


def reusable_timing_reference(metadata, input_hash, checkpoint_hash, max_paths):
    if (not metadata or metadata.get("status") != "PASS"
            or metadata.get("input_sha256") != input_hash
            or metadata.get("checkpoint_sha256") != checkpoint_hash
            or metadata.get("requested_max_paths", 0) < max_paths):
        return False
    if metadata.get("available"):
        try:
            digest = metadata.get("timing_paths_sha256")
            if not isinstance(digest, str) or len(digest) != 64:
                return False
            validate_timing_source(metadata["timing_paths"], input_hash, checkpoint_hash, digest)
        except (OSError, KeyError, ValueError):
            return False
    return True


def verify_snapshots(hashes, excluded=()):
    for path, digest in hashes.items():
        if path not in excluded and (not path.is_file() or sha256(path) != digest):
            raise ValueError("Partition input snapshot changed during partitioning: " + str(path))


def snapshot_source_pair_receipt(receipt_file, snapshot, source, checkpoint, inventory,
                                 source_hash, checkpoint_hash, inventory_hash):
    """Pin one parsed byte snapshot; old receipts assert an association only."""
    receipt_file, snapshot = Path(receipt_file).resolve(), Path(snapshot).resolve()
    if not receipt_file.is_file():
        raise ValueError("External source pair needs export_receipt.json beside EDIF; use native import otherwise")
    payload = receipt_file.read_bytes()
    receipt_hash = hashlib.sha256(payload).hexdigest()
    receipt = json.loads(payload.decode("utf-8-sig"))
    if not isinstance(receipt, dict):
        raise ValueError("Reference receipt must be a JSON object")
    exports = receipt.get("exports_sha256", {})
    hashes = [receipt.get(key) for key in
              ("checkpoint_sha256", "checkpoint_sha256_before", "checkpoint_sha256_after")]
    if (receipt.get("status") != "PASS" or not isinstance(exports, dict)
            or exports.get(source.name) != source_hash
            or exports.get(inventory.name) != inventory_hash
            or checkpoint_hash not in hashes or any(value and value != checkpoint_hash for value in hashes)):
        raise ValueError("Reference receipt does not bind the supplied EDIF and checkpoint")
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_bytes(payload)
    pins = {receipt_file: receipt_hash, snapshot: receipt_hash}
    verify_snapshots(pins)
    association = {"version": 1, "mode": "supplied_manifest_legacy",
                   "original_receipt": str(receipt_file), "receipt_snapshot": str(snapshot),
                   "original_receipt_sha256": receipt_hash, "receipt_snapshot_sha256": receipt_hash,
                   "input_edif": str(source), "input_edif_sha256": source_hash,
                   "checkpoint": str(checkpoint), "checkpoint_sha256": checkpoint_hash,
                   "native_primitives": str(inventory), "native_primitives_sha256": inventory_hash,
                   "producer_evidence_verified": False,
                   "export_run_association": "Supplied PASS manifest asserts one export association; producer command, actual native exit0 and completion are not verified",
                   "scope": "Exact payload and receipt-byte consistency; no arbitrary EDIF/checkpoint electrical-equivalence or cryptographic-authenticity proof"}
    return association, pins


def available_memory_bytes():
    try:
        if os.name == "nt":
            import ctypes
            class MemoryStatus(ctypes.Structure):
                _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
                    (name, ctypes.c_ulonglong) for name in ("total", "available", "page_total", "page_available",
                                                           "virtual_total", "virtual_available", "extended")]
            memory = MemoryStatus()
            memory.length = ctypes.sizeof(memory)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(memory)):
                return memory.available
        else:
            return os.sysconf("SC_AVPHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")
    except (AttributeError, OSError, ValueError):
        pass
    return None


def native_leaf_count(inventory):
    """Count the expanded native census without retaining millions of names."""
    with Path(inventory).open("rb") as handle:
        header_line = handle.readline().rstrip(b"\r\n")
        if header_line.startswith(b"\xef\xbb\xbf"):
            header_line = header_line[3:]
        header = header_line.split(b"\t")
        if header[:2] != [b"name", b"ref_name"]:
            return None
        rows, last = 0, b""
        while block := handle.read(4 * 1024 * 1024):
            rows += block.count(b"\n")
            last = block[-1:]
        return rows + int(bool(last) and last != b"\n")


def automatic_java_heap(edif_size, native_leaves):
    # Runtime heuristics, independent of LUT counts and device capacity. A
    # compact EDIF may reuse one cell type millions of times in the hierarchy.
    estimated_gib = 8 + max(5 * edif_size, 4096 * (native_leaves or 0)) / (1024 ** 3)
    return str(max(8, int(math.ceil(estimated_gib / 8)) * 8)) + "G"


def persistent_memory_fits(heap, edif_size, available, native_leaves=0):
    match = re.fullmatch(r"(\d+)([kmg]?)", heap.lower())
    if match is None or available is None:
        return False
    heap_bytes = int(match[1]) * {"": 1, "k": 1024, "m": 1024 ** 2, "g": 1024 ** 3}[match[2]]
    # Conservative runtime estimate, not a resource-count or feasibility claim.
    native_estimate = 2 * 1024 ** 3 + max(8 * edif_size, 2048 * (native_leaves or 0))
    return available >= heap_bytes + native_estimate + 4 * 1024 ** 3


def native_session_probe_fits(edif_size, available, native_leaves=0):
    # The first load occurs after Java exits. Measure its actual residency
    # before deciding whether the following Java process can coexist with it.
    native_estimate = 2 * 1024 ** 3 + max(8 * edif_size, 2048 * (native_leaves or 0))
    return available is not None and available >= native_estimate + 4 * 1024 ** 3


def backend_memory_reserve(hgr_bytes, threads=4):
    if hgr_bytes is None:
        return None
    # Native C++ runs while Java retains the design. Bound its input scale
    # separately from JVM overhead; this is a conservative runtime heuristic.
    return 2 * 1024 ** 3 + 32 * hgr_bytes * max(1, math.ceil(threads / 4))


def resident_java_memory_fits(heap, available, solver_input_bytes=None, threads=4):
    match = re.fullmatch(r"(\d+)([kmg]?)", heap.lower())
    backend_reserve = backend_memory_reserve(solver_input_bytes, threads)
    if match is None or available is None or backend_reserve is None:
        return False
    heap_bytes = int(match[1]) * {"": 1, "k": 1024, "m": 1024 ** 2, "g": 1024 ** 3}[match[2]]
    # Available physical RAM already excludes the resident native process.
    # Reserve additional space for JVM overhead, small tools and native solver.
    return available >= heap_bytes + 8 * 1024 ** 3 + backend_reserve


def close_native_session(session):
    session.close()
    if session.process is not None and session.process.poll() is None:
        raise RuntimeError("Native resource session did not exit before the next Java process")


def candidate_mapping_hash(candidate, input_hash):
    provenance = json.loads((candidate / "run_provenance.json").read_text())
    expected = provenance.get("mapping_sha256")
    if (provenance.get("input_sha256") != input_hash or expected != sha256(candidate / "mapping.txt")):
        raise ValueError("Candidate mapping changed or differs from the source-bound Java result")
    return expected


ENDPOINT_DIAGNOSTIC_HEADER = (
    "schema_version", "endpoint_kind", "global_model_complete", "coverage",
    "modeled_endpoint_count", "evaluated_endpoint_count", "max_logical_crossings", "endpoint_excess_crossings",
    "minimum_link_hop_analysis_available", "max_minimum_link_hops", "input_sha256", "mapping_sha256",
    "final_assignment_sha256", "scope")
ENDPOINT_DIAGNOSTIC_SCOPE = "modeled_graph_endpoint_ids_not_STA_R2R_top_outputs_or_useful_occupancy"
SELECTED_REPORT_NAMES = (
    "hardware_quality.txt", "logical_resources.tsv", "logical_lut_report.txt", "primitive_counts.tsv", "native_candidates.tsv",
    "fpga_io_cuts.txt", "fpga_net_crossings.tsv", "io_cuts.txt", "metric_definitions.txt",
    "run_provenance.json", "worst_combinational_path.tsv", "refinement_passes.tsv", "swap_refinement_passes.tsv",
    "final_assignment.part", "endpoint_kind_diagnostic.tsv", "fpga_control_categories.tsv", "cohort_refinement_passes.tsv",
    "mapping_contract.json", "mapping_records.tsv", "mapping_ownership.tsv.gz",
    "mapping_connections.tsv.gz", "mapping_connections_manifest.json", "cells.txt")


def mapping_contract_module():
    specification = importlib.util.spec_from_file_location(
        "partition_mapping_contract", Path(__file__).with_name("mapping_contract.py"))
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def prepare_reporting(output):
    """Load the portable renderer and fail before starting expensive tools."""
    source = Path(__file__).with_name("reporting.py")
    font = source.parent / "assets/fonts/TitilliumWeb-Regular.ttf"
    # Matplotlib may create a font cache on import; keep it in this run.
    os.environ["MPLCONFIGDIR"] = str(Path(output) / ".report_cache")
    specification = importlib.util.spec_from_file_location("partition_reporting", source)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    pins = {path: sha256(path) for path in (source, font,
            source.parent / "assets/fonts/TitilliumWeb-OFL.txt")}
    proof = module.preflight(font)
    verify_snapshots(pins)
    return module, font, proof, pins


def publish_partition_reports(output, state, staged_mapping, renderer, font):
    """Stage and verify every presentation artifact before mapping publication."""
    stage = Path(output) / "report.pending"
    manifest = renderer.generate_report(stage, state, mapping_path=staged_mapping,
                                        font_path=font, metrics_directory=output)
    if manifest["mapping_sha256"] != state["accepted_mapping_sha256"]:
        raise ValueError("Presentation report describes a different accepted mapping")
    expected_names = set(renderer.REPORT_FILES) - {"report_manifest.json"}
    if set(manifest["files_sha256"]) != expected_names:
        raise ValueError("Presentation report is missing required artifacts")
    for name, expected in manifest["files_sha256"].items():
        if sha256(stage / name) != expected:
            raise ValueError("Staged presentation report changed: " + name)
    pins = {}
    for name in renderer.REPORT_FILES:
        destination = Path(output) / name
        if destination.exists():
            raise ValueError("Refusing to overwrite presentation report: " + name)
        expected = sha256(stage / name)
        os.replace(stage / name, destination)
        pins[destination] = expected
    stage.rmdir()
    verify_snapshots(pins)
    return pins


def freeze_selected_reports(selected):
    """Bind every published selected report, including optional-file absence."""
    hashes = {}
    for name in SELECTED_REPORT_NAMES:
        path = selected / name
        if path.exists() and not path.is_file(): raise ValueError("Selected report must be a regular file: " + name)
        hashes[name] = sha256(path) if path.exists() else None
    for name in ("hardware_quality.txt", "run_provenance.json", *mapping_contract_module().REQUIRED_ARTIFACTS):
        if hashes[name] is None: raise ValueError("Required selected report missing: " + name)
    return hashes


def copy_selected_reports(selected, output, hashes):
    for name in SELECTED_REPORT_NAMES:
        if name == "endpoint_kind_diagnostic.tsv": continue  # Existing schema/identity validator copies this report.
        if hashes[name] is None: (output / name).unlink(missing_ok=True)
        else: shutil.copy2(selected / name, output / name)


def verify_selected_reports(selected, output, hashes):
    for name, expected in hashes.items():
        for path in ((selected / name,) if output is None else (selected / name, output / name)):
            if expected is None:
                if path.exists(): raise ValueError("Previously absent selected report appeared: " + name)
            elif not path.is_file() or sha256(path) != expected:
                raise ValueError("Selected report changed before mapping publication: " + name)


def post_native_selected_reports(candidate, java_hashes):
    """Only the known Oracle rewrite/create outputs gain post-native hashes."""
    immutable = {name: digest for name, digest in java_hashes.items()
                 if name not in ("metric_definitions.txt", "logical_lut_report.txt", "lut_report.txt")}
    verify_selected_reports(candidate, None, immutable)
    logical = candidate / "logical_lut_report.txt"
    logical_hash = sha256(logical) if logical.exists() else None
    expected_logical = java_hashes["logical_lut_report.txt"]
    if logical_hash is not None and logical_hash != (expected_logical or java_hashes["lut_report.txt"]):
        raise ValueError("Oracle logical LUT report differs from captured original Java bytes")
    if expected_logical is not None and logical_hash != expected_logical:
        raise ValueError("Captured Java logical LUT report changed during Oracle")
    retained = {name: java_hashes[name] for name in SELECTED_REPORT_NAMES}
    retained["logical_lut_report.txt"] = logical_hash
    definitions = candidate / "metric_definitions.txt"
    retained["metric_definitions.txt"] = sha256(definitions) if definitions.exists() else None
    return retained


def copy_endpoint_kind_diagnostic(selected, output, input_hash, mapping_hash):
    """Copy only the selected retained-assignment diagnostic; it never selects a case."""
    source = selected / "endpoint_kind_diagnostic.tsv"
    target = output / source.name
    if source.resolve() == target.resolve():
        raise ValueError("Selected endpoint source and publication target must differ")
    target.unlink(missing_ok=True)
    if not source.exists():
        return  # Legacy Java engines do not emit this additive report.
    if not source.is_file() or source.stat().st_size > 65536:
        raise ValueError("Endpoint diagnostic must be a bounded regular TSV")
    if candidate_mapping_hash(selected, input_hash) != mapping_hash:
        raise ValueError("Selected mapping identity differs from the endpoint diagnostic copy")
    report_hash = sha256(source)
    assignment_hash = sha256(selected / "final_assignment.part")
    quality_path = selected / "hardware_quality.txt"
    quality_hash = sha256(quality_path)
    quality = dict(line.split("=", 1) for line in quality_path.read_text().splitlines())
    with source.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if tuple(reader.fieldnames or ()) != ENDPOINT_DIAGNOSTIC_HEADER:
            raise ValueError("Unexpected endpoint diagnostic schema")
        rows = list(reader)
    if len(rows) != 2 or [row["endpoint_kind"] for row in rows] != ["modeled_capture", "modeled_terminal"]:
        raise ValueError("Exactly two ordered modeled endpoint rows required")
    for row in rows:
        if set(row) != set(ENDPOINT_DIAGNOSTIC_HEADER) or any(value is None for value in row.values()):
            raise ValueError("Malformed endpoint diagnostic row")
        if row["schema_version"] != "1" or row["scope"] != ENDPOINT_DIAGNOSTIC_SCOPE:
            raise ValueError("Unknown endpoint diagnostic version or scope")
        if row["global_model_complete"] not in ("true", "false") or row["minimum_link_hop_analysis_available"] not in ("true", "false"):
            raise ValueError("Endpoint completeness/availability must be explicit booleans")
        expected_coverage = "complete_supplied_graph" if row["global_model_complete"] == "true" else "incomplete_evaluated_subset"
        if row["coverage"] != expected_coverage or row["global_model_complete"] != quality["complete"]:
            raise ValueError("Endpoint coverage differs from selected retained analysis")
        if row["minimum_link_hop_analysis_available"] != quality["minimum_link_hop_analysis_available"]:
            raise ValueError("Endpoint minimum-link availability differs from selected analysis")
        for key in ("modeled_endpoint_count", "evaluated_endpoint_count", "max_logical_crossings", "endpoint_excess_crossings"):
            if re.fullmatch(r"0|[1-9][0-9]*", row[key]) is None:
                raise ValueError("Endpoint counters must be nonnegative integers")
        if int(row["evaluated_endpoint_count"]) > int(row["modeled_endpoint_count"]):
            raise ValueError("Evaluated endpoint count exceeds modeled count")
        if row["minimum_link_hop_analysis_available"] == "true":
            if re.fullmatch(r"0|[1-9][0-9]*", row["max_minimum_link_hops"]) is None:
                raise ValueError("Available endpoint H must be nonnegative")
        elif row["max_minimum_link_hops"] != "-1":
            raise ValueError("Unavailable endpoint H must be -1")
        if row["global_model_complete"] == "true" and row["modeled_endpoint_count"] != row["evaluated_endpoint_count"]:
            raise ValueError("Complete supplied graph has unequal endpoint coverage counts")
        if int(row["evaluated_endpoint_count"]) == 0 and (int(row["max_logical_crossings"]) != 0
                or int(row["endpoint_excess_crossings"]) != 0
                or row["minimum_link_hop_analysis_available"] == "true" and int(row["max_minimum_link_hops"]) != 0):
            raise ValueError("Empty evaluated endpoint class has nonzero extrema")
        for key, expected in (("input_sha256", input_hash), ("mapping_sha256", mapping_hash),
                              ("final_assignment_sha256", assignment_hash)):
            if re.fullmatch(r"[0-9a-f]{64}", row[key]) is None or row[key] != expected:
                raise ValueError("Endpoint diagnostic differs from selected source/mapping/assignment")
    if (max(int(row["max_logical_crossings"]) for row in rows) != int(quality["after_max_register_free_crossings"])
            or sum(int(row["endpoint_excess_crossings"]) for row in rows) != int(quality["after_endpoint_excess_crossings"])
            or (rows[0]["minimum_link_hop_analysis_available"] == "true"
                and max(int(row["max_minimum_link_hops"]) for row in rows) != int(quality["after_max_minimum_link_hops"]))):
        raise ValueError("Endpoint summaries do not decompose selected global analysis")
    try:
        shutil.copy2(source, target)
        if (sha256(source) != report_hash or sha256(target) != report_hash
                or sha256(quality_path) != quality_hash or sha256(selected / "final_assignment.part") != assignment_hash
                or candidate_mapping_hash(selected, input_hash) != mapping_hash):
            target.unlink(missing_ok=True)
            raise ValueError("Endpoint diagnostic or retained-assignment evidence changed during copy")


    except BaseException:
        target.unlink(missing_ok=True)
        raise


def fpga_label(index):
    # Preserve the branch's mapping consumer contract: A..Z, AZ..ZZ, AZZ..
    if index < 0:
        raise ValueError("Negative FPGA index")
    return "FPGA_" + chr(65 + index % 26) + "Z" * (index // 26)


def natural(value, name):
    if type(value) is not int or value < 0:
        raise ValueError(f"{name} must be a nonnegative integer")
    return value


def topology(source, output, fpga_count=None, selected_ids=None):
    """Return (k, native METIS path, optional exact-label capacity dictionary)."""
    if source.suffix.lower() != ".json":
        lines = [line for line in source.read_text().splitlines()
                 if line.strip() and not line.lstrip().startswith("%")]
        k = int(lines[0].split()[0])
        if not 1 <= k <= 64:
            raise ValueError("Topology requires 1..64 FPGAs")
        if selected_ids is not None or (fpga_count is not None and fpga_count != k):
            raise ValueError("Device subset selection requires a physical board c/1 JSON")
        target = output / "system.graph"
        if source.resolve() != target.resolve():
            shutil.copy2(source, target)
        return k, target.resolve(), {}
    data = json.loads(source.read_text())
    if data.get("schema") == "c/1":
        spec = importlib.util.spec_from_file_location("rw_board_topology", REPO / "scripts/partition/topology_input.py")
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        return module.load_board_topology(source, output, fpga_label, fpga_count=fpga_count, selected_ids=selected_ids)
    devices = data["fpgas"]
    k = len(devices)
    if not 1 <= k <= 64:
        raise ValueError("Topology requires 1..64 FPGAs")
    if selected_ids is not None or (fpga_count is not None and fpga_count != k):
        raise ValueError("Device subset selection requires a physical board c/1 JSON")
    labels = [fpga_label(i) for i in range(k)]
    caps = {}
    for i, device in enumerate(devices):
        if device.get("id", labels[i]) != labels[i]:
            raise ValueError("Topology IDs must follow RapidWright labels: FPGA_A..FPGA_Z, FPGA_AZ, FPGA_BZ, ...")
        if "clb_luts" in device:
            caps[labels[i]] = natural(device["clb_luts"], "clb_luts")
    if caps and len(caps) != k:
        raise ValueError("Specify topology clb_luts for every FPGA or none")
    adjacency = [dict() for _ in devices]
    for link in data["links"]:
        u, v = labels.index(link["u"]), labels.index(link["v"])
        cost = natural(link.get("cost", 1), "link cost")
        if cost == 0 or u == v or v in adjacency[u]:
            raise ValueError("Links must be unique, positive-cost, non-self undirected edges")
        # Capacity/TDM is deliberately not conflated with latency/routing cost.
        if "bandwidth" in link or "capacity" in link:
            raise ValueError("This backend models link cost; bandwidth constraints require a separate routing model")
        adjacency[u][v] = adjacency[v][u] = cost
    reached, stack = set(), [0]
    while stack:
        u = stack.pop()
        if u not in reached:
            reached.add(u)
            stack.extend(adjacency[u])
    if len(reached) != k:
        raise ValueError("FPGA topology must be connected")
    target = output / "system.graph"
    lines = [f"{k} {sum(map(len, adjacency)) // 2} 1"]
    lines += [" ".join(f"{v+1} {cost}" for v, cost in sorted(row.items())) for row in adjacency]
    target.write_text("\n".join(lines) + "\n")
    return k, target, caps


def run(command, cwd, log):
    print(f"Running {log.name}", flush=True)
    with log.open("w", encoding="utf-8") as handle:
        result = subprocess.run([str(x) for x in command], cwd=cwd,
                                stdout=handle, stderr=subprocess.STDOUT)
    if result.returncode:
        tail = log.read_text(errors="replace").splitlines()[-15:]
        raise RuntimeError(f"Command failed ({result.returncode}); {log}\n" + "\n".join(tail))


def find_java(explicit):
    return toolchain().find_java(explicit)


def toolchain():
    specification = importlib.util.spec_from_file_location("partition_toolchain", Path(__file__).with_name("toolchain.py"))
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def find_vivado(explicit=None):
    return toolchain().find_vivado(explicit)


def resolve_backend(explicit=None, wsl=None, library=None):
    return toolchain().resolve_backend(explicit, wsl, library)


def native_values(table):
    with table.open(newline="", encoding="utf-8-sig") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    values = {row["partition"]: int(row["clb_luts"]) for row in rows if row["partition"] != "DESIGN"}
    if not values:
        raise ValueError("Native resource table contains no FPGAs")
    return values


def route_hop_metrics(report, logical_max, required=False):
    availability = report.get("minimum_link_hop_analysis_available")
    if availability not in (None, "true", "false"):
        raise ValueError("Invalid minimum-link hop availability")
    if required and availability != "true":
        raise ValueError("New engine must report available minimum-link hops for the declared topology")
    result = {"minimum_link_hop_analysis_available": availability == "true"}
    if availability == "true":
        values = []
        for key in ("after_max_minimum_link_hops", "after_max_minimum_relay_traversals"):
            raw = report.get(key, "")
            if not isinstance(raw, str) or not re.fullmatch(r"[0-9]+", raw):
                raise ValueError("Invalid or missing minimum-link hop metric: " + key)
            values.append(int(raw))
        links, relays = values
        if links < logical_max or relays > links:
            raise ValueError("Inconsistent minimum-link hop metrics")
        result.update(max_minimum_link_hops=links, max_minimum_relay_traversals=relays)
    return result


def quality_guard_priority(row):
    """The declared structural/STA priority, excluding area and cut tie-breakers."""
    modeled = row.get("modeled_worst_slack_ns")
    hops = row["max_minimum_link_hops"] if row.get("minimum_link_hop_analysis_available") else row["max_crossings"]
    structural = (hops, row["max_crossings"], row["endpoint_excess"], row["transit_primitives"])
    if modeled is not None:
        return (not row["path_analysis_complete"], -modeled, row.get("timing_cost") or 0) + structural
    return (not row["path_analysis_complete"], hops, row["max_crossings"], row.get("timing_cost") or 0) + structural[2:]


def within_guard(row, baseline, growth, policy="componentwise"):
    """Keep original cut budgets hard; apply the explicitly selected path guard."""
    if policy not in ("ranked", "componentwise"):
        raise ValueError("Unknown quality guard policy")
    transfers = baseline.get("guard_reference_transfers", baseline["transfers"])
    topology_distance = baseline.get("guard_reference_topology", baseline["topology_distance"])
    if (row["transfers"] > transfers + math.ceil(transfers * growth)
            or row["topology_distance"] > topology_distance + math.ceil(topology_distance * growth)):
        return False
    if policy == "ranked":
        # Missing metrics cannot turn into an apparently better zero. Frozen
        # runs have one topology/timing mode; legacy direct helper calls retain
        # their componentwise default for compatibility.
        if bool(row.get("minimum_link_hop_analysis_available")) != bool(baseline.get("minimum_link_hop_analysis_available")):
            return False
        for key in ("timing_cost", "modeled_worst_slack_ns"):
            if (row.get(key) is None) != (baseline.get(key) is None):
                return False
        return quality_guard_priority(row) <= quality_guard_priority(baseline)
    timing_ok = True
    if baseline.get("timing_cost") is not None:
        timing_ok = (row.get("timing_cost") is not None
                     and row["timing_cost"] <= baseline["timing_cost"] + 1e-9)
    if baseline.get("modeled_worst_slack_ns") is not None:
        timing_ok = (timing_ok and row.get("modeled_worst_slack_ns") is not None
                     and row["modeled_worst_slack_ns"] >= baseline["modeled_worst_slack_ns"] - 1e-9)
    hops_ok = (not baseline.get("minimum_link_hop_analysis_available")
               or (row.get("minimum_link_hop_analysis_available")
                   and row["max_minimum_link_hops"] <= baseline["max_minimum_link_hops"]))
    return (timing_ok and hops_ok and row["max_crossings"] <= baseline["max_crossings"]
            and row["endpoint_excess"] <= baseline["endpoint_excess"]
            and row["transit_primitives"] <= baseline["transit_primitives"])


def candidate_priority(row, balance_envelope=None):
    # Native balance remains soft. Within the declared search envelope, equal
    # path-quality candidates prefer fewer cuts/topology cost before fine balance.
    version = row.get("selection_policy_version")
    if version is None and balance_envelope is None:
        return quality_guard_priority(row) + (row["target_imbalance"], row["transfers"], row["topology_distance"])
    if version is not None:
        if type(version) is not int or version not in (2, 3): raise ValueError("Unsupported recorded candidate selection policy")
        recorded = row.get("native_balance_search_envelope")
        if balance_envelope is not None and balance_envelope != recorded: raise ValueError("Explicit candidate envelope differs from recorded policy")
        balance_envelope = recorded
        if version == 3 and (type(row.get("native_balance_absolute_tolerance")) is not float
                            or row["native_balance_absolute_tolerance"] != SOFT_NATIVE_BALANCE_ABSOLUTE_TOLERANCE):
            raise ValueError("Recorded candidate soft balance tolerance differs from policy")
    if type(balance_envelope) not in (int, float) or not math.isfinite(balance_envelope) or balance_envelope < 0:
        raise ValueError("Recorded/explicit finite candidate search envelope required")
    tolerance = 0 if version == 2 else SOFT_NATIVE_BALANCE_ABSOLUTE_TOLERANCE
    excess = 0.0 if soft_native_balance_met(row["target_imbalance"], balance_envelope, tolerance) else row["target_imbalance"] - balance_envelope
    return quality_guard_priority(row) + (excess > 0, excess, row["transfers"], row["topology_distance"], row["target_imbalance"])

def validate_quality_guard_report(quality_report, provenance, requested):
    version = provenance.get("quality_guard_policy_version")
    if version is not None and (type(version) is not int or version != 1):
        raise ValueError("Unsupported engine quality guard policy version")
    report_version = quality_report.get("quality_guard_policy_version")
    if report_version is not None and (type(report_version) is not str or report_version != "1"):
        raise ValueError("Unsupported report quality guard policy version")
    if (version is None) != (report_version is None):
        raise ValueError("Incomplete quality guard policy version confirmation")
    for document in (provenance, quality_report):
        reported = document.get("quality_guard_policy")
        if (version == 1 or reported is not None) and reported != requested:
            raise ValueError("Engine quality guard policy differs from requested policy")
    return version == 1


def write_quality_anchor(incumbent, baseline, target, input_hash, identities):
    """Use only a prior native-cap-accepted candidate from this frozen run."""
    if incumbent["cap_violations"] or not incumbent["path_analysis_complete"]:
        raise ValueError("Quality anchor requires a complete, native-cap-accepted incumbent")
    candidate = Path(incumbent["directory"])
    for name, key in (("mapping.txt", "mapping_sha256"), ("frontier.tsv", "frontier_sha256"),
                      ("final_assignment.part", "assignment_sha256"),
                      ("vivado_reference/native_complete.txt", "native_completion_sha256")):
        if sha256(candidate / name) != incumbent[key]:
            raise ValueError("Accepted incumbent changed before quality anchoring: " + name)
    with (candidate / "frontier.tsv").open(newline="", encoding="utf-8-sig") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    assignments = [int(value) for value in (candidate / "final_assignment.part").read_text().splitlines()]
    if len(rows) != len(assignments):
        raise ValueError("Accepted quality-anchor frontier/assignment length mismatch")
    lines = [f"#input_sha256={input_hash}",
             f"#transfer_growth_reference={baseline.get('guard_reference_transfers', baseline['transfers'])}",
             f"#topology_growth_reference={baseline.get('guard_reference_topology', baseline['topology_distance'])}",
             "#incumbent_mapping_sha256=" + incumbent["mapping_sha256"],
             "#incumbent_native_completion_sha256=" + incumbent["native_completion_sha256"]]
    lines += [f"#{key}={value}" for key, value in sorted(identities.items())]
    lines.append("vertex\tpath\tpartition")
    lines += [f"{row['vertex']}\t{row['path']}\t{part}" for row, part in zip(rows, assignments)]
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")


def feedback(candidate, next_path, input_hash, native):
    """Damped spatial calibration. These are estimates; final native check is mandatory."""
    with (candidate / "frontier.tsv").open(newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    assignments = [int(line) for line in (candidate / "final_assignment.part").read_text().splitlines()]
    if len(rows) != len(assignments):
        raise ValueError("Frontier/assignment length mismatch")
    totals = [0] * len(native)
    bases = [int(row["primary_weight"]) for row in rows]
    for base, p in zip(bases, assignments):
        totals[p] += base
    # A memory-only block can have real CLB usage but zero raw logic-LUT
    # weight. Give its frontier a nonzero starting estimate for feedback.
    zero_parts = {p for p, total in enumerate(totals) if total == 0}
    for i, p in enumerate(assignments):
        if p in zero_parts:
            bases[i] = 1
            totals[p] += 1
    scale = max(1, min(100, 1_000_000_000 // max(sum(native.values()), 1)))
    estimates = []
    for old, p in zip(bases, assignments):
        # Half damping avoids overreacting to one partition's packing mixture.
        ratio = native[fpga_label(p)] / max(totals[p], 1)
        average = sum(native.values()) / max(sum(totals), 1)
        estimates.append(scale * old * (0.5 * ratio + 0.5 * average))
    # Hamilton apportionment preserves the total exactly. Independent rounding
    # can otherwise make feasible tight capacities impossible by construction.
    target = scale * sum(native.values())
    estimate_sum = sum(estimates)
    if target <= 0 or estimate_sum <= 0:
        raise ValueError("Native area feedback requires positive total area")
    estimates = [value * target / estimate_sum for value in estimates]
    weights = [int(math.floor(value)) for value in estimates]
    remaining = target - sum(weights)
    if not 0 <= remaining <= len(weights):
        raise ValueError("Feedback apportionment arithmetic failed")
    order = sorted(range(len(weights)), key=lambda i: (-(estimates[i] - weights[i]), i))
    for i in order[:remaining]:
        weights[i] += 1
    if any(value > 2_147_483_647 for value in weights):
        raise ValueError("Native feedback scalar exceeds integer range")
    lines = [f"#input_sha256={input_hash}", "vertex\tpath\tinteger_weight"]
    lines += [f"{row['vertex']}\t{row['path']}\t{weight}" for row, weight in zip(rows, weights)]
    next_path.write_text("\n".join(lines) + "\n")
    return scale, sum(weights)


def resolve_logical_balance(requested, k, caps):
    """Keep explicit raw constraints; avoid an implicit homogeneous ceiling."""
    if requested is not None:
        if not math.isfinite(requested) or requested < 0:
            raise ValueError("logical_balance must be finite and nonnegative")
        return requested, "explicit_user"
    # k*ceil(total/k) >= total: automatic raw ceilings do not force area
    # equality. Useful logic on every selected FPGA and native hard caps
    # remain mandatory. Explicit resource-capacity files are unchanged.
    return max(0.5, float(k - 1)), "automatic_capacity_first"


def resolve_search_balance(preferred, requested, capacities):
    if requested is not None:
        if requested < preferred:
            raise ValueError("Search balance must be at least the preferred soft balance target")
        return requested
    # The solver uses capacity-proportional scalar targets. This upper bound
    # permits the whole scalar load on even the smallest target; actual native
    # capacity and nonempty useful-logic checks still decide acceptance.
    return max(preferred, sum(capacities.values()) / min(capacities.values()) - 1)


def capture_cohort_configuration(requested, k, passes, engine):
    if requested not in ("auto", "off"):
        raise ValueError("Capture cohorts must be auto or off")
    supported = (engine / "com/xilinx/rapidwright/edif/partition/CohortCleanupPolicy.class").is_file()
    reason = ("requested_off" if requested == "off" else "zero_pass_incumbent_audit" if passes == 0
              else "single_fpga" if k == 1 else "legacy_engine_unsupported" if not supported else "eligible_auto")
    effective = "auto" if reason == "eligible_auto" else "off"
    return {"requested": requested, "effective": effective, "backend_supported": supported, "reason": reason}


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--input", type=Path, help="Post-synthesis DCP or EDIF (required except with --check-tools)")
    result.add_argument("--system-topology", "--system_topology", type=Path, help="System topology JSON or graph (required except with --check-tools)")
    result.add_argument("--check-tools", action="store_true", help="Check Python/Tcl, Java, Vivado, Mt-KaHyPar and compiled runtime; no input or solver job")
    subset = result.add_mutually_exclusive_group()
    subset.add_argument("--fpga-count", type=int, help="Use this many physical board FPGAs (first sorted IDs); default: all")
    subset.add_argument("--fpga-ids", nargs="+", type=int, help="Explicit physical board FPGA IDs instead of all devices")
    result.add_argument("--output", type=Path, help="New result directory; default: partition_results/<design>_<UTC timestamp>")
    result.add_argument("--quality", choices=("fast", "balanced", "thorough"), default="balanced")
    result.add_argument("--partition-preset", choices=("default", "deterministic"), default="default",
                        help="Mt-KaHyPar 1.6.2 preset; deterministic opts in and requires no mapping constraints")
    result.add_argument("--iterations", type=int, help="Native feedback iterations (quality default: 1/3/5); multi-FPGA runs use the full budget unless --early-stop")
    result.add_argument("--early-stop", action="store_true", help="Allow stopping once repeated modeled crossings and pass-through logic disappear; remaining cuts may still improve")
    result.add_argument("--starts", type=int, help="Independent native seeds (quality default: 1/3/7)")
    result.add_argument("--refine-passes", type=int, help="Directed snake cleanup passes (default: 1/3/6)")
    result.add_argument("--capture-cohorts", choices=("auto", "off"), default="off",
                        help="Bounded whole-frontier capture cleanup; off (default) disables, explicit auto opts in; pass0 audits do not move cohorts")
    result.add_argument("--balance", type=float, default=0.20, help="Preferred soft native utilization imbalance (default 20%%); cuts/path quality can justify larger imbalance")
    result.add_argument("--search-balance", type=float, help="Optional tighter estimated solver/refiner imbalance envelope; default allows any capacity-feasible area distribution")
    result.add_argument("--cut-growth", type=float, default=0.10, help="Allowed transfer/topology growth for snake cleanup (default 10%%; user tradeoff)")
    result.add_argument("--snake-weight", type=float, default=8)
    result.add_argument("--timing", choices=("auto", "required", "off"), default="auto",
                        help="Use native constrained source STA paths when available")
    result.add_argument("--timing-paths", type=Path, help="Existing source-bound sampled Vivado STA TSV")
    result.add_argument("--timing-max-paths", type=int, default=1000)
    result.add_argument("--timing-weight", type=float, default=8)
    result.add_argument("--extra-delay-ns", type=float, help="Explicit modeled extra delay per FPGA crossing; no default delay is invented")
    result.add_argument("--max-luts", type=int, help="Hard native CLB LUT cap on every FPGA")
    result.add_argument("--fpga-lut-caps", type=Path, help="JSON {FPGA_A: N, ...}; requires every FPGA")
    result.add_argument("--target-part", help="Uniform actual target device/part; never defaults to the source checkpoint part")
    result.add_argument("--fpga-parts", type=Path, help="Topology-SHA-bound per-physical-ID target assignment; otherwise auto-read <topology>.targets.json")
    result.add_argument("--part-catalog", type=Path, help="Optional identity-bound replacement LUT_ELEMENTS catalog; default bundled installed Vivado 2025.1 catalog")
    result.add_argument("--lut-utilization", type=float, default=0.75, help="Hard fraction of target LUT_ELEMENTS; may tighten to (0,75%%], never waive the user75%% bound")
    result.add_argument("--source-checkpoint", type=Path, help="Original DCP corresponding to EDIF input; supplied export receipt is pinned as a manifest association")
    result.add_argument("--native-primitives", type=Path, help="Native primitive inventory from that DCP")
    result.add_argument("--part", help="FPGA part for importing EDIF if no embedded part property")
    result.add_argument("--leaf-luts", default="auto")
    result.add_argument("--logical-balance", type=float, help="Optional tighter raw LUT imbalance guard; default max(0.5,k-1), leaving the native LUT caps in control")
    result.add_argument("--quality-guard", choices=("ranked", "componentwise"), default="ranked",
                        help="Ranked follows the objective priority; componentwise preserves each reference path metric")
    result.add_argument("--mapping-constraints", type=Path)
    result.add_argument("--logical-resource-capacities", type=Path)
    result.add_argument("--weight-model", type=Path, help="Optional initial calibrated primitive-type estimate")
    result.add_argument("--verbose-mapping", action="store_true")
    result.add_argument("--seed", type=int, default=1)
    result.add_argument("--threads", type=int, default=4)
    result.add_argument("--java")
    result.add_argument("--heap", help="Java maximum heap, e.g. 48G; default scales with EDIF size and expanded native census")
    result.add_argument("--vivado", help="Vivado executable; otherwise VIVADO, XILINX_VIVADO or PATH")
    result.add_argument("--native-session", choices=("auto", "off"), default="auto",
                        help="Reuse one unchanged Vivado checkpoint across feedback when memory permits; off uses batch")
    result.add_argument("--native-timeout-seconds", type=float, default=7200.0,
                        help="Maximum wait for persistent checkpoint startup and each resource response (seconds; default 7200); batch is unaffected")
    result.add_argument("--native-artifacts", choices=("auto", "compressed", "plain"), default="auto",
                        help="Losslessly compress large sealed native reports/cell lists; tables and mapping anchors stay plain")
    result.add_argument("--mtkahypar-bin", default=os.environ.get("MTKAHYPAR_BIN"))
    result.add_argument("--mtkahypar-lib", default=os.environ.get("MTKAHYPAR_LIB"))
    result.add_argument("--wsl", default=os.environ.get("MTKAHYPAR_WSL_DISTRO"), help="Optional WSL distribution for Mt-KaHyPar; otherwise run directly from PATH")
    return result


def execute(args):
    out = (args.output if args.output else Path.cwd() / "partition_results" /
           (args.input.stem + "_" + datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S_%f"))).resolve()
    if out.exists() and any(out.iterdir()):
        raise ValueError("Output must be a new or empty directory to prevent stale result reuse")
    out.mkdir(parents=True, exist_ok=True)
    if (out / "mapping.txt").exists():
        raise ValueError("Output already contains an accepted mapping; choose a new output directory")
    status = out / "STATUS.json"
    state = {"status": "RUNNING", "backend_version": "1.6.2", "candidates": [],
             "partition_preset_requested": args.partition_preset,
             "frontend_sha256": sha256(Path(__file__))}
    status.write_text(json.dumps(state, indent=2))
    native_session = None
    published_mapping_hash = None
    presentation_names = ()
    source_pair_snapshots = {}
    source_pair_association = None

    def run_with_source_pair(command, cwd, log):
        # Covers every command boundary, including Java, native preparation,
        # timing export, resource evaluation and final native acceptance.
        verify_snapshots(source_pair_snapshots)
        run(command, cwd, log)
        verify_snapshots(source_pair_snapshots)

    try:
        if args.partition_preset == "deterministic" and args.mapping_constraints:
            raise ValueError("--partition-preset deterministic cannot use --mapping-constraints: the Java backend changes fixed-vertex deterministic to quality")
        if not math.isfinite(args.native_timeout_seconds) or args.native_timeout_seconds <= 0:
            raise ValueError("--native-timeout-seconds must be finite and positive")
        state["native_timeout_seconds"] = args.native_timeout_seconds
        state["native_timeout_scope"] = "persistent checkpoint startup and each resource request; batch commands unaffected"
        for name in ("balance", "search_balance", "cut_growth", "snake_weight", "logical_balance", "timing_weight"):
            if name in ("logical_balance", "search_balance") and getattr(args, name) is None:
                continue
            if not math.isfinite(getattr(args, name)) or getattr(args, name) < 0:
                raise ValueError(f"{name} must be finite and nonnegative")
        if args.extra_delay_ns is not None and (not math.isfinite(args.extra_delay_ns) or args.extra_delay_ns < 0):
            raise ValueError("--extra-delay-ns must be finite and nonnegative")
        if not 1 <= args.timing_max_paths <= 100000:
            raise ValueError("--timing-max-paths must be in 1..100000")
        if (args.timing == "off" or args.timing_weight == 0) and (args.timing_paths or args.extra_delay_ns is not None):
            raise ValueError("Timing paths/transport delay require enabled timing guidance")
        quality = ("fast", "balanced", "thorough").index(args.quality)
        iterations = args.iterations if args.iterations is not None else (1, 3, 5)[quality]
        requested_iterations = iterations
        starts = args.starts if args.starts is not None else (1, 3, 7)[quality]
        passes = args.refine_passes if args.refine_passes is not None else (1, 3, 6)[quality]
        if min(iterations, starts, args.threads) < 1 or passes < 0:
            raise ValueError("Iterations, starts and threads must be positive; passes nonnegative")
        source = args.input.resolve()
        source_hash = sha256(source)
        topology_source = args.system_topology.resolve()
        topology_source_hash = sha256(topology_source)
        k, graph, caps = topology(topology_source, out, args.fpga_count, args.fpga_ids)
        state["topology_sha256"] = sha256(graph)
        state["topology_source_sha256"] = topology_source_hash
        state["fpga_count"] = k
        state["native_backend_invoked"] = k > 1
        if k == 1:
            state["backend_version"] = None
            iterations, starts, passes = 1, 1, 0
            state["mapping_strategy"] = "single_fpga_assignment_no_external_partition_solver"
        timing_enabled = (args.timing != "off" and args.timing_weight > 0
                          and (k > 1 or args.timing == "required" or args.timing_paths
                               or args.extra_delay_ns is not None))
        labels = {fpga_label(i) for i in range(k)}
        if args.fpga_lut_caps:
            extra = json.loads(args.fpga_lut_caps.read_text())
            if set(extra) != labels:
                raise ValueError("Per-FPGA cap JSON must exactly cover all topology FPGAs")
            for label, value in extra.items():
                value = natural(value, label)
                caps[label] = min(caps.get(label, value), value)
        if args.max_luts is not None:
            maximum = natural(args.max_luts, "--max-luts")
            caps = {label: min(caps.get(label, maximum), maximum) for label in labels}
        capacity_source = Path(__file__).with_name("target_capacity.py")
        capacity_source_sha = sha256(capacity_source)
        capacity_spec = importlib.util.spec_from_file_location("rw_selected_target_capacity", capacity_source)
        capacity = importlib.util.module_from_spec(capacity_spec); sys.modules[capacity_spec.name] = capacity
        capacity_spec.loader.exec_module(capacity)
        caps, balance_capacities, target_capacity, target_snapshots = capacity.prepare(
            topology_source, out, [fpga_label(i) for i in range(k)], caps, args.target_part, args.fpga_parts,
            args.part_catalog, args.lut_utilization)
        search_balance = resolve_search_balance(args.balance, args.search_balance, balance_capacities)
        if sha256(capacity_source) != capacity_source_sha or target_capacity["original_inputs_sha256"][str(topology_source)] != topology_source_hash:
            raise ValueError("Target helper or original topology changed during target preparation")
        state.update(target_capacity=target_capacity, preferred_native_balance=args.balance,
                     balance_policy="capacity_first" if args.search_balance is None else "explicit_search_envelope",
                     iterations_requested=requested_iterations, iterations_effective=iterations, starts_per_iteration=starts,
                     refine_passes=passes, early_stop_requested=args.early_stop,
                     selection_policy_version=3, native_balance_absolute_tolerance=SOFT_NATIVE_BALANCE_ABSOLUTE_TOLERANCE,
                     effective_solver_epsilon=search_balance, effective_refiner_scalar_balance=search_balance,
                     native_balance_search_envelope=search_balance)
        dependency_manifest = out / "target_capacity_dependency_manifest.json"
        dependencies = {str(capacity_source): capacity_source_sha, **target_capacity["original_inputs_sha256"]}
        dependency_raw = (json.dumps({"schema": "target_capacity_dependency_freeze_v1", "files_sha256": dependencies,
                                     "approved_catalog_sha256": capacity.APPROVED_CATALOG_SHA256}, indent=2) + "\n").encode()
        dependency_sha = hashlib.sha256(dependency_raw).hexdigest()
        with dependency_manifest.open("xb") as stream: stream.write(dependency_raw)
        if sha256(dependency_manifest) != dependency_sha: raise ValueError("Expected target dependency freeze bytes changed")
        target_snapshots[dependency_manifest] = dependency_sha
        state["target_capacity_dependency_manifest_sha256"] = dependency_sha
        state["target_capacity_dependencies_sha256"] = dependencies
        logical_balance, logical_balance_policy = resolve_logical_balance(args.logical_balance, k, caps)
        state.update(effective_logical_balance=logical_balance, logical_balance_policy=logical_balance_policy,
                     logical_balance_requested=args.logical_balance,
                     logical_resource_capacities_explicit=bool(args.logical_resource_capacities))
        renderer, report_font, report_runtime, report_tool_pins = prepare_reporting(out)
        presentation_names = renderer.REPORT_FILES
        state["report_runtime"] = report_runtime
        status.write_text(json.dumps(state, indent=2))
        cap_file = out / "native_lut_caps.tsv"
        if caps:
            cap_file.write_text("partition\tclb_luts\n" + "".join(f"{label}\t{caps[label]}\n" for label in sorted(caps)))
        inputs = out / "inputs"
        for name in ("mapping_constraints", "logical_resource_capacities", "weight_model"):
            original = getattr(args, name)
            if original:
                inputs.mkdir(exist_ok=True)
                target = inputs / (name + ".txt")
                shutil.copy2(original.resolve(), target)
                setattr(args, name, target)
        java = find_java(args.java)
        args.vivado = find_vivado(args.vivado)
        binary, library = resolve_backend(args.mtkahypar_bin, args.wsl, args.mtkahypar_lib) if k > 1 else (None, None)
        state["toolchain"] = {"java": java, "vivado": args.vivado, "mtkahypar": binary,
                              "mtkahypar_library": library, "wsl_distribution": args.wsl,
                              "discovery_sha256": sha256(Path(__file__).with_name("toolchain.py"))}
        # A feedback run must use one immutable engine even if the checkout is
        # rebuilt while Vivado evaluates a candidate.
        engine = out / "engine_classes"
        shutil.copytree(REPO / "bin", engine)
        engine_hashes = {str(path.relative_to(engine)).replace("\\", "/"): sha256(path)
                         for path in sorted(engine.rglob("*.class"))}
        (out / "engine_manifest.json").write_text(json.dumps(engine_hashes, indent=2))
        state["engine_manifest_sha256"] = sha256(out / "engine_manifest.json")
        java_base = [java, "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED", f"-Xmx{args.heap}",
                     "-cp", str(engine) + os.pathsep + str(REPO / "jars/*")]
        if args.source_checkpoint and args.native_primitives:
            if source.suffix.lower() == ".dcp":
                raise ValueError("External reference shortcut expects EDIF input")
            checkpoint = args.source_checkpoint.resolve()
            inventory = args.native_primitives.resolve()
            netlist = source
            # A supplied receipt asserts the EDIF/checkpoint export association.
            # Parse and preserve one exact byte snapshot, then keep it immutable.
            source_pair_association, receipt_pins = snapshot_source_pair_receipt(
                source.parent / "export_receipt.json", inputs / "source_pair_receipt.json",
                source, checkpoint, inventory, source_hash, sha256(checkpoint), sha256(inventory))
            source_pair_snapshots.update(receipt_pins)
        else:
            if args.source_checkpoint or args.native_primitives:
                raise ValueError("Pass both --source-checkpoint and --native-primitives")
            native_input = out / "source"
            command = [sys.executable, REPO / "tools/prepare_partition_input.py", "--input", source,
                       "--output", native_input, "--vivado", args.vivado, "--timing-max-paths",
                       str(args.timing_max_paths if timing_enabled else 0)]
            if args.part:
                command += ["--part", args.part]
            run_with_source_pair(command, out, out / "source_export.log")
            metadata = json.loads((native_input / "metadata.json").read_text())
            netlist, checkpoint, inventory = (Path(metadata[key]) for key in ("input_edif", "checkpoint", "native_primitives"))
        netlist_hash, checkpoint_hash, inventory_hash = sha256(netlist), sha256(checkpoint), sha256(inventory)
        if source_pair_association is None:
            source_pair_association = {"version": 1, "mode": "automatic_native_export",
                "source_input": str(source), "source_input_sha256": source_hash,
                "input_edif": str(netlist), "input_edif_sha256": netlist_hash,
                "checkpoint": str(checkpoint), "checkpoint_sha256": checkpoint_hash,
                "native_primitives": str(inventory), "native_primitives_sha256": inventory_hash,
                "prepare_process_exit_code": 0,
                "prepare_command": [str(value) for value in command],
                "export_run_association": "Successful automatic prepare_partition_input invocation; that helper checks actual native exit0 and export completion",
                "scope": "Native import/export association; no arbitrary supplied-pair electrical comparison"}
        elif (source_pair_association["input_edif_sha256"], source_pair_association["checkpoint_sha256"],
              source_pair_association["native_primitives_sha256"]) != (netlist_hash, checkpoint_hash, inventory_hash):
            raise ValueError("Source payload changed while its export receipt was captured")
        association_file = out / "source_pair_provenance.json"
        association_payload = (json.dumps(source_pair_association, indent=2) + "\n").encode("utf-8")
        source_pair_snapshots[association_file] = hashlib.sha256(association_payload).hexdigest()
        association_file.write_bytes(association_payload)
        verify_snapshots(source_pair_snapshots)
        state["source_pair_association"] = source_pair_association
        state["source_pair_provenance_sha256"] = source_pair_snapshots[association_file]
        status.write_text(json.dumps(state, indent=2))
        expanded_native_leaves = native_leaf_count(inventory)
        if args.heap:
            heap = args.heap
        else:
            heap = automatic_java_heap(netlist.stat().st_size, expanded_native_leaves)
        java_base[2] = "-Xmx" + heap
        state["java_heap"] = heap
        state["expanded_native_leaf_count"] = expanded_native_leaves
        timing_file = None
        state["timing_guidance"] = "single_fpga_no_boundaries" if k == 1 and args.timing == "auto" and not timing_enabled else "disabled"
        if timing_enabled:
            supported = (engine / "com/xilinx/rapidwright/edif/partition/TimingPathModel.class").is_file()
            if not supported:
                if args.timing == "required" or args.timing_paths or args.extra_delay_ns is not None:
                    raise ValueError("Rebuild the Java partitioner to enable timing guidance")
                state["timing_guidance"] = "structural_fallback"
                state["timing_unavailable_reason"] = "Compiled engine has no timing-path support"
            elif args.timing_paths:
                timing_file = args.timing_paths.resolve()
                validate_timing_source(timing_file, netlist_hash, checkpoint_hash)
            else:
                references = [netlist.parent / "timing_reference.json", netlist.parent / "timing/timing_reference.json"]
                reference = next((path for path in references if path.is_file()), references[0])
                metadata = json.loads(reference.read_text()) if reference.is_file() else None
                if not reusable_timing_reference(metadata, netlist_hash, checkpoint_hash, args.timing_max_paths):
                    timing_output = out / "source_timing"
                    try:
                        run_with_source_pair([sys.executable, REPO / "tools/export_partition_timing.py", "--checkpoint", checkpoint,
                             "--edif", netlist, "--output", timing_output, "--vivado", args.vivado,
                             "--max-paths", args.timing_max_paths], out, out / "timing_export.log")
                        metadata = json.loads((timing_output / "timing_reference.json").read_text())
                    except RuntimeError as error:
                        if args.timing == "required" or args.extra_delay_ns is not None:
                            raise
                        metadata = {"status": "PASS", "available": False,
                                    "reason": "Native timing export failed; see timing_export.log: " + str(error),
                                    "input_sha256": netlist_hash, "checkpoint_sha256": checkpoint_hash}
                if (metadata.get("status") != "PASS" or metadata.get("input_sha256") != netlist_hash
                        or metadata.get("checkpoint_sha256") != checkpoint_hash):
                    raise ValueError("Native timing export is incomplete or bound to a different source")
                state["timing_reference"] = metadata
                if metadata.get("available"):
                    timing_file = Path(metadata["timing_paths"])
                    digest = metadata.get("timing_paths_sha256")
                    if not isinstance(digest, str) or len(digest) != 64:
                        raise ValueError("Native timing export is missing the timing TSV SHA256 hash")
                    validate_timing_source(timing_file, netlist_hash, checkpoint_hash, digest)
                else:
                    state["timing_guidance"] = "structural_fallback"
                    state["timing_unavailable_reason"] = metadata.get("reason", "No finite constrained native paths")
                    if args.timing == "required" or args.extra_delay_ns is not None:
                        raise ValueError("Source timing guidance is unavailable: " + state["timing_unavailable_reason"])
        if timing_file:
            inputs.mkdir(exist_ok=True)
            target = inputs / "timing_paths.tsv"
            if args.timing_paths:
                shutil.copy2(timing_file, target)
            else:
                kept, count = [], 0
                for line in timing_file.read_text(encoding="utf-8-sig").splitlines():
                    if line.startswith("#") or line.startswith("path_id\t") or not line.strip():
                        kept.append(line)
                    elif count < args.timing_max_paths:
                        kept.append(line)
                        count += 1
                target.write_text("\n".join(kept) + "\n", encoding="utf-8")
                state["timing_consumed_paths"] = count
            timing_file = target
            state["timing_guidance"] = "sampled_source_STA"
            state["timing_paths_sha256"] = sha256(timing_file)
            state["extra_delay_ns_per_crossing"] = args.extra_delay_ns
        snapshots = {graph: sha256(graph), association_file: source_pair_snapshots[association_file], **target_snapshots}
        for path in (out / "fpga_devices.tsv", out / "topology_manifest.json"):
            if path.is_file():
                snapshots[path] = sha256(path)
        if caps:
            snapshots[cap_file] = sha256(cap_file)
        for path in inputs.iterdir() if inputs.is_dir() else ():
            if path.is_file():
                snapshots[path] = sha256(path)
        state["input_snapshot_sha256"] = {str(path.relative_to(out)): digest for path, digest in snapshots.items()}
        session_files = [REPO / "tools" / name for name in
                         ("native_resource_session.py", "resource_session.tcl", "report_partition_resources.tcl")]
        use_session = (args.native_session == "auto" and iterations > 1
                       and all(path.is_file() for path in session_files))
        tool_snapshots = {path: sha256(path) for path in session_files} if use_session else {}
        tool_snapshots.update(report_tool_pins)
        tool_snapshots[capacity_source] = capacity_source_sha
        contract_source = Path(__file__).with_name("mapping_contract.py")
        tool_snapshots[contract_source] = sha256(contract_source)
        state["mapping_contract_validator_sha256"] = tool_snapshots[contract_source]
        contract = mapping_contract_module()
        archive_tool = REPO / "tools/archive_native_request.py"
        archive_workspace = REPO.parent.resolve()
        archive_available = (archive_tool.is_file() and out.is_relative_to(archive_workspace)
                             and checkpoint.is_relative_to(archive_workspace))
        if args.native_artifacts == "compressed" and not archive_available:
            raise ValueError("Compressed native artifacts require the archive helper and source/output inside the repository workspace")
        use_archive = args.native_artifacts != "plain" and archive_available
        if use_archive:
            tool_snapshots[archive_tool] = sha256(archive_tool)
        state["native_artifact_storage"] = "verified_lossless_compression" if use_archive else "plain"
        state["native_oracle_mode"] = "adaptive_persistent_pending" if use_session else "batch"
        state["native_residency_policy"] = "Probe after Java; retain only when measured available RAM covers the next heap, 8 GiB overhead and a prior-HGR native solver reserve"
        state["native_residency_decisions"] = []
        state["native_oracle_scripts_sha256"] = {str(path): digest for path, digest in tool_snapshots.items()
                                                 if path not in (capacity_source, contract_source)}
        weight_file = None
        scalar_caps_file = None
        accepted = []
        anchor_supported = (engine / "com/xilinx/rapidwright/edif/partition/AcceptedQualityAnchor.class").is_file()
        cohort_configuration = capture_cohort_configuration(args.capture_cohorts,k,passes,engine)
        state["capture_cohort_cleanup"] = cohort_configuration
        state["native_feedback_quality_guard"] = "accepted_mapping_anchor" if anchor_supported else "current_native_baseline"
        state["quality_guard_policy"] = args.quality_guard
        for iteration in range(iterations):
            candidate = out / f"candidate_{iteration:02d}"
            candidate.mkdir(exist_ok=True)
            command = java_base + [MAIN + "Partitioner", str(netlist), str(k), args.leaf_luts,
                "--target_graph", str(graph), "--part_dir", str(candidate), "--skip_detailed_reports",
                "--threads", str(args.threads), "--starts", str(starts), "--refine_passes", str(passes),
                "--epsilon", str(search_balance), "--logical_epsilon", str(logical_balance),
                "--cut_budget", str(args.cut_growth), "--snake_weight", str(args.snake_weight),
                "--quality_guard", args.quality_guard,
                "--seed", str(args.seed + iteration * starts)]
            if binary:
                command += ["--mtkahypar_bin", binary]
            if args.partition_preset == "deterministic":
                command += ["--partition_config", "deterministic"]
            if cohort_configuration["backend_supported"]:
                command += ["--capture_cohorts", cohort_configuration["requested"]]
            if args.wsl:
                command += ["--wsl", args.wsl]
            if library:
                command += ["--mtkahypar_lib", library]
            for flag, value in (("mapping_constraints", args.mapping_constraints),
                                ("resource_capacities", args.logical_resource_capacities)):
                if value:
                    command += ["--" + flag, str(value.resolve())]
            if weight_file:
                command += ["--vertex_weight_file", str(weight_file)]
            elif args.weight_model:
                command += ["--weight_model", str(args.weight_model.resolve())]
            if args.verbose_mapping:
                command += ["--verbose_mapping"]
            if scalar_caps_file:
                command += ["--scalar_capacities", str(scalar_caps_file)]
            if anchor_supported and accepted and accepted[0]["path_analysis_complete"]:
                eligible_prior = [row for row in accepted if within_guard(row, accepted[0], args.cut_growth, args.quality_guard)]
                incumbent = min(eligible_prior, key=candidate_priority)
                anchor_file = out / f"quality_anchor_{iteration:02d}.tsv"
                verify_snapshots(snapshots)
                write_quality_anchor(incumbent, accepted[0], anchor_file, netlist_hash,
                                     {"quality_guard_policy": args.quality_guard,
                                      "topology_sha256": state["topology_sha256"],
                                      "timing_paths_sha256": state.get("timing_paths_sha256", "none"),
                                      "engine_manifest_sha256": state["engine_manifest_sha256"],
                                      "checkpoint_sha256": checkpoint_hash,
                                      "hard_lut_caps_sha256": snapshots.get(cap_file, "none")})
                verify_selected_reports(Path(incumbent["directory"]), None, incumbent["java_immutable_report_sha256"])
                verify_selected_reports(Path(incumbent["directory"]), None, incumbent["selected_report_sha256"])
                snapshots[anchor_file] = sha256(anchor_file)
                command += ["--quality_anchor", str(anchor_file)]
            if timing_file:
                command += ["--timing_paths", str(timing_file), "--timing_weight", str(args.timing_weight)]
                if args.extra_delay_ns is not None:
                    command += ["--extra_delay_ns", str(args.extra_delay_ns)]
            if native_session is not None:
                available = available_memory_bytes()
                prior_hgr_sizes = [path.stat().st_size for path in out.glob("candidate_*/*.hgr") if path.is_file()]
                solver_input_bytes = max(prior_hgr_sizes, default=None)
                solver_reserve = backend_memory_reserve(solver_input_bytes, args.threads)
                retain = resident_java_memory_fits(heap, available, solver_input_bytes, args.threads)
                state["native_residency_decisions"].append({"iteration": iteration,
                    "stage": "before_java", "available_bytes": available,
                    "prior_hgr_bytes": solver_input_bytes, "native_solver_reserve_bytes": solver_reserve,
                    "decision": "retain" if retain else "close_and_use_batch"})
                if not retain:
                    close_native_session(native_session)
                    native_session = None
                    use_session = False
                    state["native_oracle_mode"] = "batch_after_memory_guard"
                status.write_text(json.dumps(state, indent=2))
            try:
                run_with_source_pair(command, REPO, candidate / "partition.log")
            except RuntimeError as error:
                if not accepted:
                    raise
                state["candidates"].append({"iteration": iteration, "status": "REJECTED", "error": str(error)})
                status.write_text(json.dumps(state, indent=2))
                if ("--quality_anchor" in command
                        and "No refinement candidate satisfies the accepted quality anchor" in str(error)):
                    print(f"Candidate {iteration}: quality anchor retained; trying remaining seed budget", flush=True)
                    continue
                break
            native = candidate / "vivado_reference"
            java_report_hashes = freeze_selected_reports(candidate)
            for name in ("frontier.tsv", "mapping.txt", "lut_report.txt"):
                path = candidate / name
                java_report_hashes[name] = sha256(path) if path.exists() else None
            expected_mapping_hash = candidate_mapping_hash(candidate, netlist_hash)
            verify_selected_reports(candidate, None, java_report_hashes)
            contract_audit = contract.validate_bundle(candidate, inventory, netlist_hash, labels)
            state["mapping_contract_last_candidate"] = contract_audit
            run_with_source_pair([sys.executable, REPO / "tools/prepare_native_resource_cells.py", "--mapping", candidate / "mapping.txt",
                 "--checkpoint", checkpoint, "--native-primitives", inventory, "--output", native], out,
                candidate / "prepare_native.log")
            if use_session and native_session is None:
                available = available_memory_bytes()
                probe = native_session_probe_fits(netlist.stat().st_size, available, expanded_native_leaves)
                state["native_residency_decisions"].append({"iteration": iteration,
                    "stage": "after_java", "available_bytes": available,
                    "decision": "load_native_probe" if probe else "use_batch"})
                if not probe:
                    use_session = False
                    state["native_oracle_mode"] = "batch_probe_memory_guard"
            verify_snapshots(source_pair_snapshots)
            if use_session:
                if native_session is None:
                    spec = importlib.util.spec_from_file_location("rw_native_resource_session", session_files[0])
                    module = importlib.util.module_from_spec(spec)
                    sys.modules[spec.name] = module
                    spec.loader.exec_module(module)
                    native_session = module.NativeResourceSession(checkpoint, session_files[2],
                                                                  out / "native_session", args.vivado,
                                                                  timeout=args.native_timeout_seconds)
                    native_session.__enter__()
                    state["native_oracle_mode"] = "persistent_original_checkpoint"
                verify_snapshots(source_pair_snapshots)
                print("Running persistent native resource check", flush=True)
                native_session.evaluate(candidate / "mapping.txt", native)
                verify_snapshots(source_pair_snapshots)
                (candidate / "native.log").write_text("Persistent unchanged checkpoint session: " + str(out / "native_session") + "\n")
            else:
                run_with_source_pair([args.vivado, "-mode", "batch", "-source", REPO / "tools/report_partition_resources.tcl",
                     "-log", native / "vivado.log", "-journal", native / "vivado.jou", "-tclargs", checkpoint,
                     candidate / "mapping.txt", native], out, candidate / "native.log")
            if candidate_mapping_hash(candidate, netlist_hash) != expected_mapping_hash:
                raise ValueError("Candidate mapping changed during native resource reporting")
            # Validate provenance and publish exact native metrics to the
            # candidate. No candidate becomes the accepted mapping until caps pass.
            verify_selected_reports(candidate, None, java_report_hashes)
            run_with_source_pair(java_base + [MAIN + "VivadoResourceOracle", str(native / "vivado_resources.tsv"),
                            str(candidate / "mapping.txt"), str(candidate)], REPO, candidate / "oracle.log")
            retained_report_hashes = post_native_selected_reports(candidate, java_report_hashes)
            java_immutable_hashes = {name: digest for name, digest in java_report_hashes.items()
                                     if name not in ("metric_definitions.txt", "logical_lut_report.txt", "lut_report.txt")}
            native_report_hashes = {name: sha256(candidate / name) for name in
                                    ("lut_report.txt", "vivado_resources.tsv", "source_mapping.txt", "native_complete.txt", "vivado_resource_summary.txt")
                                    if (candidate / name).is_file()}
            if use_archive:
                verify_snapshots(tool_snapshots)
                run_with_source_pair([sys.executable, archive_tool, "--directory", native, "--mapping", candidate / "mapping.txt",
                     "--workspace-root", archive_workspace, "--oracle-exit-code", "0", "--apply"], out,
                    candidate / "archive_native.log")
            values = native_values(native / "vivado_resources.tsv")
            if set(values) != labels:
                raise ValueError("Native partition labels differ from topology")
            imbalance = max(values.values()) / (sum(values.values()) / k) - 1 if sum(values.values()) else 0
            positive_utilization = [values[label] / balance_capacities[label] for label in labels]
            target_imbalance = (max(positive_utilization) / (sum(positive_utilization) / len(positive_utilization)) - 1
                                if positive_utilization and sum(positive_utilization) else imbalance)
            violations = {label: {"actual": value, "cap": caps[label]} for label, value in values.items()
                          if label in caps and value > caps[label]}
            quality_report = dict(line.split("=", 1) for line in (candidate / "hardware_quality.txt").read_text().splitlines())
            if quality_report.get("resource_coverage_complete") != "true":
                raise RuntimeError("Cannot certify native capacity with unresolved/opaque primitive resource coverage")
            row = {"iteration": iteration, "directory": str(candidate), "native_clb_luts": values,
                   "selection_policy_version": 3, "native_balance_search_envelope": search_balance,
                   "native_balance_absolute_tolerance": SOFT_NATIVE_BALANCE_ABSOLUTE_TOLERANCE,
                   "preferred_native_balance": args.balance,
                   "native_imbalance": imbalance, "target_imbalance": target_imbalance, "cap_violations": violations,
                   "target_device_utilizations": {label: values[label] / balance_capacities[label] for label in labels},
                   "preferred_balance_target_met": soft_native_balance_met(target_imbalance, args.balance),
                   "within_native_search_envelope": soft_native_balance_met(target_imbalance, search_balance),
                   "transfers": int(quality_report["after_transfers"]),
                   "topology_distance": float(quality_report["after_topology_distance"]),
                   "guard_reference_transfers": int(quality_report.get("transfer_growth_reference", quality_report["before_transfers"])),
                   "guard_reference_topology": float(quality_report.get("topology_growth_reference", quality_report["before_topology_distance"])),
                   "max_crossings": int(quality_report["after_max_register_free_crossings"]),
                   "endpoint_excess": int(quality_report["after_endpoint_excess_crossings"]),
                   "transit_primitives": int(quality_report["after_transit_primitives"]),
                   "path_analysis_complete": quality_report["complete"] == "true"}
            candidate_provenance = json.loads((candidate / "run_provenance.json").read_text())
            row["quality_guard_policy"] = args.quality_guard
            row["quality_guard_policy_confirmed"] = validate_quality_guard_report(
                quality_report, candidate_provenance, args.quality_guard)
            hop_version = candidate_provenance.get("minimum_link_hop_analysis_version")
            if hop_version is not None and (type(hop_version) is not int or hop_version != 1):
                raise ValueError("Unsupported engine minimum-link hop analysis version")
            if hop_version == 1 and candidate_provenance.get("minimum_link_hop_analysis_available") is not True:
                raise ValueError("New engine did not analyze the declared topology adjacency")
            row.update(route_hop_metrics(quality_report, row["max_crossings"], required=hop_version == 1))
            hop_available = row["minimum_link_hop_analysis_available"]
            if ("minimum_link_hop_analysis_available" in state
                    and state["minimum_link_hop_analysis_available"] != hop_available):
                raise ValueError("Minimum-link hop availability changed within one frozen run")
            state["minimum_link_hop_analysis_available"] = hop_available
            if "timing_cost_after" in quality_report:
                row["timing_cost"] = float(quality_report["timing_cost_after"])
                row["timing_path_count"] = int(quality_report["timing_paths_scored"])
                if "modeled_worst_slack_after_ns" in quality_report:
                    row["modeled_worst_slack_ns"] = float(quality_report["modeled_worst_slack_after_ns"])
                if not math.isfinite(row["timing_cost"]) or (row.get("modeled_worst_slack_ns") is not None
                        and not math.isfinite(row["modeled_worst_slack_ns"])):
                    raise ValueError("Nonfinite sampled timing result")
            if anchor_supported:
                row.update(mapping_sha256=expected_mapping_hash,
                           frontier_sha256=sha256(candidate / "frontier.tsv"),
                           assignment_sha256=sha256(candidate / "final_assignment.part"),
                           native_completion_sha256=sha256(native / "native_complete.txt"))
            verify_selected_reports(candidate, None, retained_report_hashes)
            verify_selected_reports(candidate, None, java_immutable_hashes)
            verify_selected_reports(candidate, None, native_report_hashes)
            row["selected_report_sha256"] = retained_report_hashes
            row["java_immutable_report_sha256"] = java_immutable_hashes
            row["native_regenerated_report_sha256"] = native_report_hashes
            state["candidates"].append(row)
            if not violations:
                accepted.append(row)
            status.write_text(json.dumps(state, indent=2))
            print(f"Candidate {iteration}: native LUT imbalance {imbalance:.2%}; cap violations {len(violations)}", flush=True)
            if (args.early_stop and not violations and within_guard(row, accepted[0], args.cut_growth, args.quality_guard)
                    and soft_native_balance_met(target_imbalance, search_balance) and row["path_analysis_complete"]
                    and row["max_crossings"] <= 1 and row["transit_primitives"] == 0
                    and (not hop_available or row["max_minimum_link_hops"] <= 1)):
                state["early_stop_reason"] = "No repeated modeled boundary crossings or pass-through logic; I/O cuts may still be improvable"
                break
            if iteration + 1 == iterations:
                continue
            if sum(values.values()) == 0:
                # FF/DSP-only designs still benefit from new path-aware seeds,
                # but zero CLB area cannot calibrate a physical LUT proxy.
                state["native_feedback_skipped_zero_area"] = True
                continue
            weight_file = out / f"feedback_{iteration+1:02d}.tsv"
            scale, weight_sum = feedback(candidate, weight_file, netlist_hash, values)
            snapshots[weight_file] = sha256(weight_file)
            if caps:
                scalar_caps_file = out / f"feedback_caps_{iteration+1:02d}.txt"
                total_caps = sum(balance_capacities.values())
                limits = [min(caps[fpga_label(p)] * scale,
                              math.floor(weight_sum * balance_capacities[fpga_label(p)] / max(total_caps, 1) * (1 + search_balance)))
                          for p in range(k)]
                scalar_caps_file.write_text("\n".join(str(value) for value in limits) + "\n")
                snapshots[scalar_caps_file] = sha256(scalar_caps_file)
        if native_session is not None:
            close_native_session(native_session)
            native_session = None
        if not accepted:
            raise RuntimeError("No candidate satisfies every hard native LUT cap. No accepted mapping was published; this is not a proof of infeasibility.")
        # Native feedback changes the solver's balance weights. Guard cuts and
        # topology against the first cap-feasible candidate across iterations.
        baseline = accepted[0]
        for candidate_row in accepted:
            verify_selected_reports(Path(candidate_row["directory"]), None, candidate_row["java_immutable_report_sha256"])
            verify_selected_reports(Path(candidate_row["directory"]), None, candidate_row["selected_report_sha256"])
            verify_selected_reports(Path(candidate_row["directory"]), None, candidate_row["native_regenerated_report_sha256"])
        eligible = [row for row in accepted if within_guard(row, baseline, args.cut_growth, args.quality_guard)]
        best = min(eligible, key=candidate_priority)
        selected = Path(best["directory"])
        # Strong final native cap gate, with source hash validation, runs before
        # the accepted mapping is copied to the output root.
        final_check = java_base + [MAIN + "VivadoResourceOracle", str(selected / "vivado_reference/vivado_resources.tsv"),
                                  str(selected / "mapping.txt"), str(out)]
        if caps:
            final_check += ["--lut-caps", str(cap_file)]
        if (sha256(source), sha256(netlist), sha256(checkpoint), sha256(inventory)) != (source_hash, netlist_hash, checkpoint_hash, inventory_hash):
            raise ValueError("Input or native reference changed during partitioning")
        selected_mapping_hash = candidate_mapping_hash(selected, netlist_hash)
        verify_snapshots(snapshots, excluded=(cap_file,))
        verify_snapshots(tool_snapshots)
        capacity.verify_originals(target_capacity)
        if sha256(topology_source) != topology_source_hash:
            raise ValueError("Original topology source changed during partitioning")
        run_with_source_pair(final_check, REPO, out / "acceptance.log")
        # Recheck after the native gate as well: publication must use exactly
        # the topology, timing, constraints and cap manifest seen by the solve.
        verify_snapshots(snapshots)
        verify_snapshots(tool_snapshots)
        capacity.verify_originals(target_capacity)
        if sha256(topology_source) != topology_source_hash:
            raise ValueError("Original topology source changed during native acceptance")
        if candidate_mapping_hash(selected, netlist_hash) != selected_mapping_hash:
            raise ValueError("Selected mapping changed during final native acceptance")
        if (sha256(source), sha256(netlist), sha256(checkpoint), sha256(inventory)) != (source_hash, netlist_hash, checkpoint_hash, inventory_hash):
            raise ValueError("Input or native reference changed during native acceptance")
        selected_report_hashes = best["selected_report_sha256"]
        verify_selected_reports(selected, None, selected_report_hashes)
        verify_selected_reports(selected, None, best["java_immutable_report_sha256"])
        verify_selected_reports(selected, None, best["native_regenerated_report_sha256"])
        copy_endpoint_kind_diagnostic(selected, out, netlist_hash, selected_mapping_hash)
        copy_selected_reports(selected, out, selected_report_hashes)
        staged_mapping = out / "mapping.txt.pending"
        shutil.copy2(out / "source_mapping.txt", staged_mapping)
        if sha256(staged_mapping) != selected_mapping_hash:
            raise ValueError("Native-validated mapping snapshot differs from the selected Java mapping")
        contract_audit = contract.validate_bundle(out, inventory, netlist_hash, labels, staged_mapping)
        fully_qualified = soft_native_balance_met(best["target_imbalance"], search_balance) and best["path_analysis_complete"]
        state.update(status="PASS" if fully_qualified else "CAP_VALIDATED_WITH_LIMITATIONS",
                     selected_iteration=best["iteration"], native_cap_check="PASS" if caps else "NOT_REQUESTED",
                     native_balance_target_met=soft_native_balance_met(best["target_imbalance"], args.balance),
                     preferred_native_balance_target_met=soft_native_balance_met(best["target_imbalance"], args.balance),
                     native_search_envelope_met=soft_native_balance_met(best["target_imbalance"], search_balance),
                     new_policy_complete_within_search_envelope=fully_qualified,
                     source_input=str(source), source_sha256=source_hash, checkpoint_sha256=checkpoint_hash,
                     native_inventory_sha256=inventory_hash, hard_native_clb_lut_caps=caps,
                     selection_policy=("Hard capacities and one cut/topology budget; complete paths; "
                         + ("modeled sampled worst slack, timing cost, minimum-link hops and logical paths" if args.extra_delay_ns is not None else
                            (("worst minimum-link hop lower bound, " if best["minimum_link_hop_analysis_available"] else "")
                             + "logical register-free crossings, sampled source timing cost, endpoint excess, transit"))
                         + "; native search-envelope limitation tier, transfers, topology cost, then fine balance; preferred balance remains soft"),
                     minimum_link_hop_scope=("Declared selected adjacency shortest-link lower bounds; not actual transport routing, congestion or nanoseconds"
                         if best["minimum_link_hop_analysis_available"] else "UNAVAILABLE from this engine"),
                     scope="Original synthesized netlist subset utilization; no future wrapper/transport resources included")
        state["input_snapshot_sha256"] = {str(path.relative_to(out)): digest for path, digest in snapshots.items()}
        state["accepted_mapping_sha256"] = selected_mapping_hash
        state["selected_report_sha256"] = selected_report_hashes
        state["mapping_contract"] = contract_audit
        state["iterations_attempted"] = len(state["candidates"])
        report_pins = publish_partition_reports(out, state, staged_mapping, renderer, report_font)
        state["presentation_report_sha256"] = {path.name: digest for path, digest in report_pins.items()}
        report_manifest = json.loads((out / "report_manifest.json").read_text(encoding="utf-8"))
        report_source_pins = {out / name: digest for name, digest in report_manifest["source_files_sha256"].items()}
        # Recheck after report/staged-mapping copies, immediately before publication.
        verify_snapshots(source_pair_snapshots)
        verify_selected_reports(selected, out, selected_report_hashes)
        verify_selected_reports(selected, None, best["java_immutable_report_sha256"])
        verify_selected_reports(selected, None, best["native_regenerated_report_sha256"])
        if sha256(staged_mapping) != selected_mapping_hash:
            raise ValueError("Staged mapping changed before publication")
        verify_snapshots(tool_snapshots)
        verify_snapshots(report_pins)
        verify_snapshots(report_source_pins)
        verify_snapshots(snapshots)
        capacity.verify_originals(target_capacity)
        if sha256(topology_source) != topology_source_hash:
            raise ValueError("Original topology changed during report generation")
        if (sha256(source), sha256(netlist), sha256(checkpoint), sha256(inventory)) != (source_hash, netlist_hash, checkpoint_hash, inventory_hash):
            raise ValueError("Source or native reference changed during report generation")
        os.replace(staged_mapping, out / "mapping.txt")
        published_mapping_hash = selected_mapping_hash
        status.write_text(json.dumps(state, indent=2))
        try:
            print(f"Accepted mapping: {out / 'mapping.txt'}; native balance target met: {state['native_balance_target_met']}")
        except BrokenPipeError:
            pass
    except Exception as error:
        if published_mapping_hash and (out / "mapping.txt").is_file() and sha256(out / "mapping.txt") == published_mapping_hash:
            (out / "mapping.txt").unlink()
        # A failed run must not retain a PASS-looking report whose accepted
        # mapping was withheld. Only these newly generated artifacts are ours.
        for name in presentation_names:
            (out / name).unlink(missing_ok=True)
        state.update(status="FAIL", error=str(error))
        status.write_text(json.dumps(state, indent=2))
        raise
    finally:
        if native_session is not None:
            native_session.close()


if __name__ == "__main__":
    try:
        arguments = parser()
        options = arguments.parse_args()
        if options.check_tools:
            report = toolchain().check_tools(REPO, options.java, options.vivado, options.mtkahypar_bin,
                                             options.wsl, options.mtkahypar_lib)
            print(json.dumps(report, indent=2))
            sys.exit(0 if report["status"] == "PASS" else 1)
        if options.input is None or options.system_topology is None:
            arguments.error("--input and --system-topology are required unless --check-tools is used")
        execute(options)
    except Exception as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(1)
