"""Fail-closed checks for ordered, source-bound partition mapping artifacts.

The Java producer checks actual EDIF instance identity and ancestry. Its hashed
record manifest supplies that ancestry here: splitting '/' alone is insufficient
because an instance name can contain a literal slash. Native census prefixes are
an independent name-presence check, not a replacement for that source validation.
No regrouping, attribute promotion, or transformed-netlist validation is claimed.
"""
import csv
import gzip
import hashlib
import json
from pathlib import Path
import re


DESTINATION = re.compile(r"FPGA_[A-Za-z0-9_]+\Z")
RECORD_HEADER = ("instance_path", "logical_fpga", "nearest_mapped_ancestor")
OWNERSHIP_HEADER = ("instance_path", "ref_name", "classification", "logical_fpga",
                    "governing_record", "frontier_owner")
CONNECTION_HEADER = ("RECORD_TYPE", "CANONICAL_NET", "SOURCE_INSTANCE", "PIN", "REF_NAME", "PORT_DIRECTION",
                     "DRIVER", "LOAD", "LOGICAL_OWNER", "BIT_WIDTH", "CONSTANT_CLASSIFICATION", "BOUNDARY_SCOPE")
SOURCE_SCOPE = "pre_regroup_source_hierarchy_and_ordered_ownership"
CONNECTION_SCOPE = "all canonical source nets, original hierarchy aliases and primitive/top-port endpoints"
SOURCE_PROOF_FLAGS = ("source_paths_exact", "descendants_before_ancestors", "nearest_ancestor_redundancy_absent",
                      "residual_root_explicit", "cells_agreement", "frontier_ownership_compared")
REQUIRED_ARTIFACTS = ("mapping_contract.json", "mapping_records.tsv",
                      "mapping_ownership.tsv.gz", "mapping_connections.tsv.gz",
                      "mapping_connections_manifest.json", "cells.txt")


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _records(path):
    records = {}
    with Path(path).open(encoding="utf-8-sig") as stream:
        for number, text in enumerate(stream, 1):
            text = text.strip()
            if not text or text.startswith("#"):
                continue
            tokens = text.split()
            if len(tokens) != 2:
                raise ValueError(f"Mapping contract: line {number} requires exactly two tokens")
            source, destination = tokens
            if (any(character in source for character in ('*', '?', '"', "'", '\\', '#'))
                    or source.startswith("/") or source.endswith("/") or "//" in source
                    or any(piece in (".", "..") for piece in source.split("/"))):
                raise ValueError(f"Mapping contract: invalid literal instance path on line {number}")
            if DESTINATION.fullmatch(destination) is None:
                raise ValueError(f"Mapping contract: invalid logical FPGA destination on line {number}")
            if source in records:
                raise ValueError(f"Mapping contract: duplicate source assignment: {source}")
            records[source] = destination
    if not records:
        raise ValueError("Mapping contract: no assignments")
    return records


def _cells(path):
    labels = set()
    with Path(path).open(encoding="utf-8-sig") as stream:
        for number, text in enumerate(stream, 1):
            tokens = text.split()
            if not tokens:
                continue
            if len(tokens) != 1 or DESTINATION.fullmatch(tokens[0]) is None:
                raise ValueError(f"Mapping contract: cells.txt line {number} requires one logical FPGA")
            if tokens[0] in labels:
                raise ValueError("Mapping contract: duplicate cells.txt destination: " + tokens[0])
            labels.add(tokens[0])
    return labels


def _prefix_ancestors(records, root):
    ancestors = {}
    for source in records:
        if source == root:
            ancestors[source] = ""
            continue
        if not source.startswith(root + "/"):
            raise ValueError("Mapping contract: source outside the explicit DUT root: " + source)
        ancestor = source.rsplit("/", 1)[0]
        while ancestor not in records and "/" in ancestor:
            ancestor = ancestor.rsplit("/", 1)[0]
        if ancestor not in records:
            raise ValueError("Mapping contract: no explicit residual root ownership for " + source)
        ancestors[source] = ancestor
    return ancestors


def validate_mapping(mapping, cells, native_inventory=None, root=None,
                     expected_destinations=None, record_manifest=None):
    """Validate records, ordered moves and native name presence.

    Without an EDIF-derived record manifest, ancestry is conservatively inferred
    from slash prefixes. Such a standalone check is not a source-netlist proof.
    Native primitive names are relative to the synthesized DUT instance.
    """
    records = _records(mapping)
    if root is None:
        roots = []
        for source in records:
            ancestor = source
            while "/" in ancestor:
                ancestor = ancestor.rsplit("/", 1)[0]
                if ancestor in records:
                    break
            else:
                roots.append(source)
        if len(roots) != 1:
            raise ValueError("Mapping contract: require one explicit residual DUT root assignment")
        root = roots[0]
        if "/" in root:
            raise ValueError("Mapping contract: explicit DUT root identity is required for a nested-only mapping")
    if root not in records or next(reversed(records)) != root:
        raise ValueError("Mapping contract: explicit residual DUT root must be the last assignment")
    if record_manifest is None:
        ancestors = _prefix_ancestors(records, root)
    else:
        ancestors = {}
        with Path(record_manifest).open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream, delimiter="\t")
            if tuple(reader.fieldnames or ()) != RECORD_HEADER:
                raise ValueError("Mapping contract: unexpected record ancestry manifest schema")
            for (source, destination), row in zip(records.items(), reader, strict=True):
                if (set(row) != set(RECORD_HEADER) or any(value is None for value in row.values())
                        or (row["instance_path"], row["logical_fpga"]) != (source, destination)):
                    raise ValueError("Mapping contract: record manifest differs from mapping file order")
                ancestors[source] = row["nearest_mapped_ancestor"]
    positions = {source: index for index, source in enumerate(records)}
    for source, destination in records.items():
        ancestor = ancestors[source]
        if source == root:
            if ancestor:
                raise ValueError("Mapping contract: DUT root has a mapped ancestor")
            continue
        if (not source.startswith(root + "/") or ancestor not in records
                or not source.startswith(ancestor + "/")):
            raise ValueError("Mapping contract: invalid mapped ancestor for " + source)
        if positions[source] >= positions[ancestor]:
            raise ValueError("Mapping contract: descendants must precede their mapped ancestors: " + source)
        if destination == records[ancestor]:
            raise ValueError("Mapping contract: redundant assignment matches nearest mapped ancestor: " + source)
    labels = set(records.values())
    if _cells(cells) != labels:
        raise ValueError("Mapping contract: cells.txt destinations differ from mapping")
    if expected_destinations is not None and labels != set(expected_destinations):
        raise ValueError("Mapping contract: destinations differ from selected topology FPGAs")
    result = {"status": "PASS", "root_instance": root, "mapping_sha256": sha256(mapping),
              "record_count": len(records), "logical_fpgas": sorted(labels),
              "ancestry_source": "source_bound_EDIF_manifest" if record_manifest else "slash_prefix_only"}
    if native_inventory is not None:
        # Retain only mapped prefixes; the native inventory can contain millions
        # of rows. A terminal stores the full mapping path, not a copied subtree.
        trie = {}
        for source in records:
            node = trie
            for piece in ([] if source == root else source[len(root) + 1:].split("/")):
                node = node.setdefault(piece, {})
            node[None] = source
        matched = set()
        count = constants = 0
        with Path(native_inventory).open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream, delimiter="\t")
            if not reader.fieldnames or not {"name", "ref_name"}.issubset(reader.fieldnames):
                raise ValueError("Mapping contract: native primitive inventory needs name/ref_name columns")
            for row in reader:
                name, reference = row.get("name"), row.get("ref_name")
                if not name or not reference:
                    raise ValueError("Mapping contract: malformed native primitive inventory row")
                count += 1
                constant = reference.upper() in ("GND", "VCC")
                constants += int(constant)
                node = trie
                matched.add(root)
                pieces = name.split("/")
                for index, piece in enumerate(pieces):
                    node = node.get(piece)
                    if node is None:
                        break
                    source = node.get(None)
                    if source is not None:
                        matched.add(source)
                        if constant and index == len(pieces) - 1:
                            raise ValueError("Mapping contract: explicit constant primitive assignment: " + source)
        missing = set(records) - matched
        if missing:
            raise ValueError("Mapping contract: mapped path absent from native census hierarchy: " + min(missing))
        result.update(native_inventory_rows=count, native_constant_rows=constants,
                      native_presence_scope="mapped strings and census prefixes; actual ancestry verified by EDIF producer")
    return result


def _unescape_connection_field(value):
    """Decode the producer's TSV escaping, independently of CSV quoting."""
    if "\\" not in value:
        return value
    result = []
    index = 0
    escapes = {"\\": "\\", "t": "\t", "r": "\r", "n": "\n"}
    while index < len(value):
        character = value[index]
        if character == "\\":
            index += 1
            if index == len(value) or value[index] not in escapes:
                raise ValueError("Mapping contract: invalid source connection TSV escape")
            character = escapes[value[index]]
        result.append(character)
        index += 1
    return "".join(result)


def validate_connections(path, receipt, root, labels):
    """Read to gzip EOF/CRC and validate rows using bounded per-net state."""
    counts = {"NET": 0, "ALIAS": 0, "ENDPOINT": 0}
    allowed_owners = labels | {"LOCAL_CONSTANT"}
    current = None
    drivers, loads = set(), set()

    def rooted(value):
        return value == root or value.startswith(root + "/")

    def finish_net():
        if current is None:
            return
        _, classification, boundary = current
        expected = ("LOCAL_CONSTANT" if classification == "VENDOR_CONSTANT_NET" else
                    "CROSSING" if any(driver != load for driver in drivers for load in loads) else "LOCAL")
        if boundary != expected:
            raise ValueError("Mapping contract: source connection boundary scope disagrees with endpoint owners")

    try:
        with gzip.open(path, "rt", encoding="utf-8", newline="") as stream:
            if tuple(stream.readline().rstrip("\r\n").split("\t")) != CONNECTION_HEADER:
                raise ValueError("Mapping contract: unexpected source connection manifest schema")
            for line in stream:
                fields = line.rstrip("\r\n").split("\t")
                if len(fields) != len(CONNECTION_HEADER):
                    raise ValueError("Mapping contract: malformed source connection field count")
                (kind, canonical, source, pin, reference, direction, driver, load,
                 owner, width, classification, boundary) = map(_unescape_connection_field, fields)
                if kind not in counts or not rooted(canonical) or width != "1":
                    raise ValueError("Mapping contract: invalid source connection record, canonical path, or bit width")
                if kind == "NET":
                    finish_net()
                    if any((source, pin, reference, direction, driver, load, owner)):
                        raise ValueError("Mapping contract: NET record contains endpoint fields")
                    if (classification not in ("VENDOR_CONSTANT_NET", "NON_CONSTANT")
                            or boundary not in ("LOCAL", "CROSSING", "LOCAL_CONSTANT")
                            or (classification == "VENDOR_CONSTANT_NET") != (boundary == "LOCAL_CONSTANT")):
                        raise ValueError("Mapping contract: invalid source connection NET classification")
                    current = canonical, classification, boundary
                    drivers.clear()
                    loads.clear()
                else:
                    if current is None or canonical != current[0] or boundary != current[2] or not rooted(source):
                        raise ValueError("Mapping contract: source connection row does not belong to its current NET")
                    if kind == "ALIAS":
                        if any((pin, reference, direction, driver, load, owner)) or classification != current[1]:
                            raise ValueError("Mapping contract: malformed source connection ALIAS")
                    else:
                        if (not pin or not reference or direction not in ("INPUT", "OUTPUT", "INOUT")
                                or driver not in ("true", "false") or load not in ("true", "false")
                                or owner not in allowed_owners):
                            raise ValueError("Mapping contract: invalid source endpoint direction, role, or owner")
                        top = source == root
                        expected_driver = direction == "INOUT" or direction == ("INPUT" if top else "OUTPUT")
                        expected_load = direction == "INOUT" or direction == ("OUTPUT" if top else "INPUT")
                        if ((reference == "TOP_PORT") != top or (driver == "true") != expected_driver
                                or (load == "true") != expected_load):
                            raise ValueError("Mapping contract: source endpoint roles disagree with direction/top-port scope")
                        local_constant = classification == "LOCAL_CONSTANT_SOURCE"
                        if (classification not in (current[1], "LOCAL_CONSTANT_SOURCE")
                                or (owner == "LOCAL_CONSTANT") != local_constant
                                or local_constant and (reference not in ("GND", "VCC")
                                                       or current[1] != "VENDOR_CONSTANT_NET" or driver != "true")):
                            raise ValueError("Mapping contract: source endpoint constant ownership is inconsistent")
                        if driver == "true":
                            drivers.add(owner)
                        if load == "true":
                            loads.add(owner)
                counts[kind] += 1
            finish_net()
    except (OSError, EOFError) as error:
        raise ValueError("Mapping contract: corrupt or truncated source connection gzip") from error
    for kind, key in (("NET", "canonical_nets"), ("ALIAS", "hierarchy_aliases"), ("ENDPOINT", "endpoints")):
        if type(receipt.get(key)) is not int or receipt[key] != counts[kind]:
            raise ValueError("Mapping contract: source connection receipt counts differ from complete gzip stream")
    return counts


def validate_bundle(directory, native_inventory, input_sha256, expected_destinations,
                    mapping_path=None):
    """Require exact source-bound producer evidence before any publication."""
    directory = Path(directory)
    mapping = Path(mapping_path) if mapping_path else directory / "mapping.txt"
    for name in REQUIRED_ARTIFACTS:
        if not (directory / name).is_file():
            raise ValueError("Mapping contract: required producer artifact missing: " + name)
    report_path = directory / "mapping_contract.json"
    if report_path.stat().st_size > 1024 * 1024:
        raise ValueError("Mapping contract: oversized contract report")
    report = json.loads(report_path.read_text(encoding="utf-8-sig"))
    if (not isinstance(report, dict) or report.get("schema_version") != 1 or report.get("status") != "PASS"
            or report.get("input_sha256") != input_sha256 or report.get("mapping_sha256") != sha256(mapping)):
        raise ValueError("Mapping contract: producer report does not bind this source and mapping")
    if (report.get("scope") != SOURCE_SCOPE or report.get("downstream_regroup_verified") is not False
            or any(report.get(flag) is not True for flag in SOURCE_PROOF_FLAGS)):
        raise ValueError("Mapping contract: source hierarchy/frontier proof is missing or has an unsupported scope")
    for name, key in (("mapping_records.tsv", "records_manifest_sha256"),
                      ("mapping_ownership.tsv.gz", "ownership_manifest_sha256"),
                      ("mapping_connections.tsv.gz", "connections_manifest_sha256"),
                      ("mapping_connections_manifest.json", "connections_receipt_sha256")):
        if report.get(key) != sha256(directory / name):
            raise ValueError("Mapping contract: producer manifest hash differs: " + name)
    root = report.get("root_instance")
    if not isinstance(root, str) or not root:
        raise ValueError("Mapping contract: producer report needs the exact DUT root instance")
    receipt_path = directory / "mapping_connections_manifest.json"
    if receipt_path.stat().st_size > 1024 * 1024:
        raise ValueError("Mapping contract: oversized connection receipt")
    receipt = json.loads(receipt_path.read_text(encoding="utf-8-sig"))
    if (not isinstance(receipt, dict) or receipt.get("schema_version") != 1
            or receipt.get("status") != "PRE_REGROUP_SOURCE_CONNECTIVITY_ONLY"
            or receipt.get("downstream_regroup_verification") != "NOT_RUN"
            or receipt.get("file") != "mapping_connections.tsv.gz"
            or receipt.get("sha256") != report["connections_manifest_sha256"]
            or receipt.get("source_root") != root or receipt.get("scope") != CONNECTION_SCOPE):
        raise ValueError("Mapping contract: connection receipt has incorrect source scope, identity, or status")
    connection_counts = validate_connections(directory / "mapping_connections.tsv.gz", receipt,
                                             root, set(expected_destinations))
    result = validate_mapping(mapping, directory / "cells.txt", native_inventory, root,
                              expected_destinations, directory / "mapping_records.tsv")
    records = _records(mapping)
    populations = {label: 0 for label in result["logical_fpgas"]}
    leaves = constants = empty_hierarchy = 0
    with gzip.open(directory / "mapping_ownership.tsv.gz", "rt", encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if tuple(reader.fieldnames or ()) != OWNERSHIP_HEADER:
            raise ValueError("Mapping contract: unexpected ownership manifest schema")
        for row in reader:
            if set(row) != set(OWNERSHIP_HEADER) or any(value is None for value in row.values()):
                raise ValueError("Mapping contract: malformed ownership manifest row")
            path, label, governing = row["instance_path"], row["logical_fpga"], row["governing_record"]
            if not path.startswith(root + "/") or governing not in records:
                raise ValueError("Mapping contract: ownership manifest references an unknown source or record")
            if not (path == governing or path.startswith(governing + "/")):
                raise ValueError("Mapping contract: ownership governing record is outside the leaf ancestry")
            constant = row["ref_name"].upper() in ("GND", "VCC")
            if row["classification"] == "EMPTY_HIERARCHY":
                if label != "EMPTY_HIERARCHY" or constant:
                    raise ValueError("Mapping contract: empty hierarchy requires separate nonfunctional classification")
                empty_hierarchy += 1
            elif constant:
                if row["classification"] != "LOCAL_CONSTANT" or label != "LOCAL_CONSTANT":
                    raise ValueError("Mapping contract: constant primitive requires separate local classification")
                constants += 1
            else:
                if (row["classification"] != "FUNCTIONAL" or label not in populations
                        or label != records[governing] or row["frontier_owner"] != label):
                    raise ValueError("Mapping contract: ownership manifest destination differs from governing record")
                populations[label] += 1
                leaves += 1
    if any(count == 0 for count in populations.values()):
        raise ValueError("Mapping contract: every selected FPGA must own nonconstant functional logic")
    if (report.get("record_count") != len(records) or report.get("functional_leaf_count") != leaves
            or report.get("local_constant_leaf_count") != constants
            or report.get("empty_hierarchy_count") != empty_hierarchy
            or report.get("functional_leaves_by_destination") != populations):
        raise ValueError("Mapping contract: producer counts differ from complete ownership manifest")
    result.update(functional_leaf_count=leaves, local_constant_count=constants,
                  empty_hierarchy_count=empty_hierarchy,
                  source_connection_rows=connection_counts,
                  functional_leaf_populations=populations, contract_report_sha256=sha256(report_path),
                  scope="pre_regroup_source_manifest; no transformed_netlist_or_wrapper_validation")
    return result
