"""Selected target-part LUT_ELEMENTS policy; never infers targets from source part."""
import csv
from decimal import Decimal
import hashlib
import io
import json
import math
from pathlib import Path
import re
import shutil

BASE = Path(__file__).resolve().parent
DEFAULT_CATALOG = BASE / "part_catalog/vivado_2025_1_parts.tsv"
APPROVED_CATALOG_SHA256 = "a402de944fbd660743f6a7ff12403060e886627439826186600da78618e6a1fe"


def sha(path):
    with Path(path).open("rb") as stream: return hashlib.file_digest(stream, "sha256").hexdigest()


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result: raise ValueError("Duplicate target/catalog JSON key: " + key)
        result[key] = value
    return result


def document(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"), object_pairs_hook=unique)


def text(value, name):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.+-]{1,256}", value):
        raise ValueError("Invalid single part/device identifier: " + name)
    return value


def load_catalog(path=None):
    path = Path(path or DEFAULT_CATALOG).resolve()
    identity_path = Path(str(path) + ".identity.json")
    raw = path.read_bytes(); identity_raw = identity_path.read_bytes()
    catalog_digest = hashlib.sha256(raw).hexdigest(); identity_digest = hashlib.sha256(identity_raw).hexdigest()
    if catalog_digest != APPROVED_CATALOG_SHA256:
        raise ValueError("Unapproved part-capacity data; catalog replacement may only relocate the exact approved native export")
    identity = json.loads(identity_raw.decode("utf-8-sig"), object_pairs_hook=unique)
    if (identity.get("schema") != "vivado_lut_elements_catalog_v1" or identity.get("quantity") != "LUT_ELEMENTS"
            or identity.get("catalog_sha256") != catalog_digest or identity.get("native_query_version") != "2025.1"
            or identity.get("native_query_completed") is not True):
        raise ValueError("Exact completed native part catalog identity absent/mismatched")
    rows = []
    seen = set()
    with io.StringIO(raw.decode("utf-8-sig"), newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if not {"part", "device", "lut_elements"}.issubset(reader.fieldnames or []): raise ValueError("Missing LUT_ELEMENTS catalog columns")
        for row in reader:
            name, device = text(row["part"], "part"), text(row["device"], "device")
            if name.lower() in seen or not re.fullmatch(r"[0-9]+", row["lut_elements"]) or int(row["lut_elements"]) <= 0:
                raise ValueError("Duplicate/invalid native part catalog row")
            seen.add(name.lower()); rows.append({"part": name, "device": device, "lut_elements": int(row["lut_elements"])})
    if type(identity.get("part_variants")) is not int or identity["part_variants"] != len(rows) or not rows:
        raise ValueError("Complete native part catalog row count differs")
    return rows, {str(path): catalog_digest, str(identity_path): identity_digest}, identity


def resolve_part(request, rows):
    request = text(request, "selected target part")
    query = request.lower()
    matches = [row for row in rows if row["part"].lower() == query]
    if not matches:
        aliases = {query, "xc" + query} if not query.startswith(("xc", "xa", "xq")) else {query}
        matches = [row for row in rows if row["device"].lower() in aliases]
    if not matches: raise ValueError("Unknown locally supported target part/device: " + request)
    capacities = {row["lut_elements"] for row in matches}
    devices = {row["device"].lower() for row in matches}
    if len(capacities) != 1 or len(devices) != 1: raise ValueError("Ambiguous/inconsistent target device capacity: " + request)
    return {"requested": request, "device": matches[0]["device"], "lut_elements": next(iter(capacities)),
            "matching_part_variants": len(matches), "canonical_part_examples": [row["part"] for row in matches[:3]]}


def selected_devices(topology_source, output, labels, topology_data=None, source_hash=None):
    source = Path(topology_source)
    if source.suffix.lower() != ".json":
        return [{"partition": label, "physical_id": label} for label in labels], set(labels)
    topology = topology_data if topology_data is not None else document(source)
    if topology.get("schema") == "c/1":
        manifest = document(Path(output) / "topology_manifest.json")
        if manifest.get("source_sha256") != (source_hash or sha(source)): raise ValueError("Selected physical topology manifest source differs")
        original_devices = {str(row["id"]): row for row in topology["fpgas"]}
        if len(original_devices) != len(topology["fpgas"]): raise ValueError("Duplicate original physical FPGA IDs")
        manifest_rows = manifest.get("fpgas", [])
        manifest_ids = [str(row.get("id")) for row in manifest_rows]
        expected_ids = [str(identity) for identity in manifest.get("selected_ids", [])]
        if (len(set(manifest_ids)) != len(manifest_ids) or manifest_ids != expected_ids
                or any(identity not in original_devices for identity in manifest_ids)
                or len(manifest_rows) != len(labels)):
            raise ValueError("Selected manifest physical IDs/count/order differ from original topology")
        selected = []
        for label, row in zip(labels, manifest_rows):
            original = original_devices[str(row["id"])]
            if row.get("partition") != label or any(row.get(key) != value for key, value in original.items()):
                raise ValueError("Selected manifest physical FPGA metadata differs from captured original topology")
            selected.append({"partition": label, "physical_id": str(original["id"]), "part": original.get("part")})
        all_ids = set(original_devices)
    else:
        selected = [{"partition": label, "physical_id": str(row.get("id", label)), "part": row.get("part")}
                    for label, row in zip(labels, topology["fpgas"])]
        all_ids = {row["physical_id"] for row in selected}
    if {row["partition"] for row in selected} != set(labels) or len(selected) != len(labels):
        raise ValueError("Selected physical part identities differ from native FPGA labels")
    return selected, all_ids


def prepare(topology_source, output, labels, existing_caps, target_part=None, fpga_parts=None,
            catalog=None, utilization=0.75):
    if isinstance(utilization, bool) or not isinstance(utilization, (int, float)) or not math.isfinite(utilization) or not 0 < utilization <= 0.75:
        raise ValueError("LUT utilization must be finite in (0,0.75]; user75% bound cannot be waived")
    output = Path(output); source = Path(topology_source).resolve(); labels = list(labels)
    source_raw = source.read_bytes(); source_digest = hashlib.sha256(source_raw).hexdigest()
    topology_data = json.loads(source_raw.decode("utf-8-sig"), object_pairs_hook=unique) if source.suffix.lower() == ".json" else None
    selected, all_ids = selected_devices(source, output, labels, topology_data, source_digest)
    supplied = Path(fpga_parts).resolve() if fpga_parts else None
    companion = source.with_name(source.name + ".targets.json")
    if supplied is None and companion.is_file(): supplied = companion
    binding = None; original_pins = {str(source): source_digest}
    if supplied is not None:
        binding_raw = supplied.read_bytes(); binding = json.loads(binding_raw.decode("utf-8-sig"), object_pairs_hook=unique)
        if binding.get("schema") != "fpga_target_parts_v1" or binding.get("topology_sha256") != source_digest:
            raise ValueError("Target companion/assignment is not bound to exact original topology bytes")
        parts = binding.get("parts_by_physical_id")
        if not isinstance(parts, dict) or set(parts) != all_ids:
            raise ValueError("Target part assignment must exactly cover every original physical FPGA ID")
        original_pins[str(supplied)] = hashlib.sha256(binding_raw).hexdigest()
    rows, catalog_pins, catalog_identity = load_catalog(catalog)
    original_pins.update(catalog_pins)
    fraction = Decimal(str(utilization)); caps = {}; capacities = {}; devices = []
    for device in selected:
        label = device["partition"]
        requests = [value for value in (target_part, binding["parts_by_physical_id"][device["physical_id"]] if binding else None, device.get("part")) if value is not None]
        if not requests: raise ValueError("Missing target part: provide --target-part, exact topology companion, per-ID assignment, or new c/1 part metadata")
        resolved = [resolve_part(value, rows) for value in requests]
        if len({(row["device"].lower(), row["lut_elements"]) for row in resolved}) != 1:
            raise ValueError("Conflicting selected target part declarations: " + label)
        part = resolved[0]; physical_capacity = part["lut_elements"]; policy_cap = int(Decimal(physical_capacity) * fraction)
        if label in existing_caps and (type(existing_caps[label]) is not int or existing_caps[label] < 0): raise ValueError("Invalid existing hard LUT cap")
        caps[label] = min(policy_cap, existing_caps.get(label, policy_cap)); capacities[label] = physical_capacity
        devices.append({**device, **part, "physical_lut_capacity": physical_capacity, "utilization_fraction": utilization,
                        "utilization_policy_cap": policy_cap, "existing_explicit_hard_cap": existing_caps.get(label), "effective_hard_cap": caps[label]})
    frozen = output / "inputs/target_capacity"; frozen.mkdir(parents=True)
    snapshot_pins = {}
    for index, (name, digest) in enumerate(original_pins.items()):
        original = Path(name); target = frozen / (str(index) + "_" + original.name)
        shutil.copyfile(original, target)
        if sha(target) != digest or sha(original) != digest: raise ValueError("Target part/catalog/topology changed while snapshotting")
        snapshot_pins[target] = digest
    result = {"schema": "selected_target_lut_capacity_v1", "hard_capacity_policy": "min(all explicit hard caps, floor(utilization * verified selected-device LUT_ELEMENTS))",
              "default_policy_is_user_engineering_bound_not_route_proof": True, "devices": devices, "capacities": capacities, "hard_caps": caps,
              "catalog_identity": catalog_identity, "original_inputs_sha256": original_pins,
              "source_checkpoint_part_inferred_as_target": False, "scope": "Original subset LUT capacity only; excludes transport/wrapper resources and route closure"}
    path = output / "target_capacity.json"
    serialized = (json.dumps(result, indent=2, allow_nan=False) + "\n").encode()
    expected = hashlib.sha256(serialized).hexdigest()
    with path.open("xb") as stream: stream.write(serialized)
    if sha(path) != expected: raise ValueError("Expected serialized target capacity proof changed")
    snapshot_pins[path] = expected
    return caps, capacities, result, snapshot_pins


def verify_originals(result):
    if any(sha(name) != digest for name, digest in result["original_inputs_sha256"].items()):
        raise ValueError("Original target part/catalog/topology binding changed during execution")


def search_balance(preferred, requested=None):
    for name, value in (("preferred balance", preferred), ("search balance", requested)):
        if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0):
            raise ValueError("Finite nonnegative " + name + " required")
    result = max(0.30, preferred) if requested is None else requested
    if result < preferred: raise ValueError("Search balance must be at least the preferred soft balance target")
    return result
