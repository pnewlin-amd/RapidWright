"""Compare complete native endpoint streams with several final mappings.

Reads one native canonical net at a time from plain/gzip seven-field Tcl lists.
No full endpoint TSV, all-net dictionary or global endpoint-ownership map is
created. Logical primitive names and mapping rules remain resident. The exact
cut/fingerprint policy is shared with compare_partition_reference.py.
"""
import argparse
from collections import Counter
import csv
from functools import lru_cache
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import time
import tkinter

from compare_partition_reference import mapping_rules, rapidwright_crossings, resolve


def digest(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def open_bulk(path):
    with path.open("rb") as handle:
        magic = handle.read(2)
    return gzip.open(path, "rb") if magic == b"\x1f\x8b" else path.open("rb")


def completion(path):
    text = path.read_text(encoding="utf-8-sig")
    pairs = [line.split("=", 1) for line in text.splitlines() if "=" in line]
    if len({key for key, value in pairs}) != len(pairs):
        raise ValueError("Duplicate native completion metadata key")
    data = dict(pairs)
    if data.get("status") == "COMPRESSED_NATIVE_ENDPOINT_EXPORT_PASS":
        if data.get("all_enumerated_segments_covered") != "1":
            raise ValueError("Native producer did not cover all enumerated segments")
        count = int(data["canonical_nets"])
        if int(data["covered_alias_segments"]) < int(data["hierarchical_segments"]):
            raise ValueError("Native alias coverage count is incomplete")
        data["format"] = "compressed_complete_census"
    else:
        markers = re.findall(r"^BULK_NATIVE_ENDPOINT_EXPORT_PASS nets=(\d+)\b", text, re.MULTILINE)
        if len(markers) != 1:
            raise ValueError("Expected one unambiguous native completion marker")
        count = int(markers[0])
        data["format"] = "legacy_complete_census"
    if count < 0:
        raise ValueError("Negative native canonical-net count")
    return count, data


def load_primitives(path):
    types, histogram = {}, Counter()
    with path.open(encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            name, ref = row["name"], row["ref_name"]
            if not name or not ref or name in types:
                raise ValueError("Empty/duplicate logical primitive name or reference")
            types[name] = ref
            histogram[ref] += 1
    return types, histogram


def prepare_case(name, mapping, crossings, primitives=None):
    return {"name": name, "mapping": Path(mapping), "rw_crossings": Path(crossings),
            "rw_primitives": Path(primitives) if primitives else None,
            "rules": mapping_rules(Path(mapping)),
            "expected": rapidwright_crossings(Path(crossings)), "seen": set(),
            "native_crossing_nets": 0, "native_directed_destinations": 0,
            "extra_count": 0, "extra_sample": [], "pair_mismatch_count": 0,
            "pair_mismatch_sample": []}


def compare_stream(bulk, primitives, cases, expected_nets, max_record_bytes=1024**3,
                   owner_cache_entries=250000, progress=None):
    types, histogram = load_primitives(primitives)
    if len({case["name"] for case in cases}) != len(cases) or not cases:
        raise ValueError("Expected distinct named comparison cases")
    tcl = tkinter.Tcl()  # Tcl list decoding only; never eval source data.
    raw_hash, raw_bytes = hashlib.sha256(), 0
    count = constants = endpoints_compared = 0
    started = time.monotonic()

    @lru_cache(maxsize=owner_cache_entries)
    def assignments(owner):
        return tuple(resolve(owner, case["rules"]) for case in cases)

    with open_bulk(bulk) as source:
        while line := source.readline(max_record_bytes + 1):
            raw_hash.update(line)
            raw_bytes += len(line)
            if len(line) > max_record_bytes:
                raise ValueError("Native net record exceeds configured memory bound")
            if not line.endswith(b"\n"):
                raise ValueError("Incomplete native physical record")
            if not line.strip() or line.lstrip().startswith(b"#"):
                continue
            try:
                fields = tcl.splitlist(line.decode("utf-8-sig" if count == 0 else "utf-8"))
                if len(fields) != 7:
                    raise ValueError("Expected seven native Tcl-list fields")
                net_id, canonical = fields[:2]
                if int(net_id) != count:
                    raise ValueError("Native net IDs must be contiguous from zero")
                pin_names, pin_refs, pin_dirs, ports, port_dirs = [tcl.splitlist(v) for v in fields[2:]]
                if not (len(pin_names) == len(pin_refs) == len(pin_dirs)) or len(ports) != len(port_dirs):
                    raise ValueError("Native property-list lengths differ")
                constant = False
                for full_pin in pin_names:
                    owner, separator, unused = full_pin.rpartition("/")
                    if not separator:
                        raise ValueError("Native pin has no owning cell")
                    if types.get(owner) in ("VCC", "GND"):
                        constant = True
                        break
                count += 1
                if constant:
                    constants += 1
                    continue
                emitted = {}
                drivers = [set() for unused in cases]
                loads = [set() for unused in cases]

                def emit(kind, direction, owner, pin, full_pin):
                    if direction not in ("IN", "OUT", "INOUT"):
                        raise ValueError("Unknown native pin/port direction")
                    key, metadata = (kind, full_pin), (direction, owner, pin)
                    if key in emitted:
                        if emitted[key] != metadata:
                            raise ValueError("Conflicting duplicate native endpoint metadata")
                        return
                    emitted[key] = metadata
                    top = kind == "port"
                    driver = direction == "INOUT" or direction == ("IN" if top else "OUT")
                    load = direction == "INOUT" or direction == ("OUT" if top else "IN")
                    for index, part in enumerate(assignments("" if top else owner)):
                        if driver:
                            drivers[index].add(part)
                        if load:
                            loads[index].add(part)

                for full_pin, ref, direction in zip(pin_names, pin_refs, pin_dirs):
                    owner = full_pin.rpartition("/")[0]
                    if owner in types:
                        emit("pin", direction, owner, ref, full_pin)
                for name, direction in zip(ports, port_dirs):
                    emit("port", direction, "", name, name)
                endpoints_compared += len(emitted)
                crossings = [{a + "->" + b for a in d for b in l if a != b}
                             for d, l in zip(drivers, loads)]
                if any(crossings):
                    fingerprint = set()
                    for (kind, unused_full), (direction, owner, pin) in emitted.items():
                        top = kind == "port"
                        driver = direction == "INOUT" or direction == ("IN" if top else "OUT")
                        load = direction == "INOUT" or direction == ("OUT" if top else "IN")
                        name = "PORT:" + pin if top else owner + "/" + pin
                        fingerprint.add(name + ":" + ("D" if driver else "") + ("L" if load else ""))
                    key = "sha256:" + hashlib.sha256(";".join(sorted(fingerprint)).encode("utf-8")).hexdigest()
                    for case, pairs in zip(cases, crossings):
                        if not pairs:
                            continue
                        case["native_crossing_nets"] += 1
                        case["native_directed_destinations"] += len(pairs)
                        if key not in case["expected"]:
                            case["extra_count"] += 1
                            if len(case["extra_sample"]) < 10:
                                case["extra_sample"].append({"name": canonical, "pairs": sorted(pairs)})
                            continue
                        if key in case["seen"]:
                            raise ValueError("Duplicate native crossing endpoint fingerprint")
                        case["seen"].add(key)
                        expected = case["expected"][key]
                        if sorted(pairs) != expected["pairs"]:
                            case["pair_mismatch_count"] += 1
                            if len(case["pair_mismatch_sample"]) < 10:
                                case["pair_mismatch_sample"].append({"native": sorted(pairs), "rapidwright": expected})
                if progress and count % 10000 == 0:
                    progress(count, time.monotonic() - started)
            except (ValueError, tkinter.TclError) as error:
                raise ValueError(f"Native record {count}: {error}") from error
    if count != expected_nets:
        raise ValueError(f"Native completion count differs: {count} != {expected_nets}")
    results = []
    for case in cases:
        missing = case["expected"].keys() - case["seen"]
        mismatch = []
        if case["rw_primitives"]:
            with case["rw_primitives"].open(encoding="utf-8-sig", newline="") as handle:
                rw_histogram = {row["REF_NAME"]: int(row["COUNT"]) for row in csv.DictReader(handle, delimiter="\t")}
            mismatch = [{"ref_name": ref, "vivado": histogram.get(ref, 0), "rapidwright": rw_histogram.get(ref, 0)}
                        for ref in sorted(histogram.keys() | rw_histogram.keys())
                        if histogram.get(ref, 0) != rw_histogram.get(ref, 0)]
        results.append({"name": case["name"],
            "passed": not (missing or case["extra_count"] or case["pair_mismatch_count"] or mismatch),
            "vivado_crossing_nets": case["native_crossing_nets"],
            "rapidwright_crossing_nets": len(case["expected"]),
            "vivado_directed_net_destinations": case["native_directed_destinations"],
            "rapidwright_directed_net_destinations": sum(len(v["pairs"]) for v in case["expected"].values()),
            "missing_count": len(missing), "extra_count": case["extra_count"],
            "pair_mismatch_count": case["pair_mismatch_count"],
            "missing_sample": [case["expected"][key] for key in sorted(missing)[:10]],
            "extra_sample": case["extra_sample"], "pair_mismatch_sample": case["pair_mismatch_sample"],
            "primitive_histogram_mismatch": mismatch})
    return {"passed": all(case["passed"] for case in results), "coverage": "complete_native_canonical_census",
            "native_canonical_nets": count, "constant_groups_excluded": constants,
            "logical_primitive_boundaries": len(types), "nonconstant_logical_endpoints_compared": endpoints_compared,
            "raw_stream_sha256": raw_hash.hexdigest(), "raw_stream_bytes": raw_bytes,
            "owner_cache_entries_limit": owner_cache_entries, "owner_cache_entries_peak": assignments.cache_info().currsize,
            "elapsed_seconds": time.monotonic() - started, "cases": results,
            "unit": "canonical logical net per distinct directed FPGA pair, clocks/resets and root top I/O included; not physical package pins"}


def atomic_json(path, result):
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + f".partial.{os.getpid()}")
    partial.resolve().relative_to(path.parent)
    with partial.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(result, handle, indent=2)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    try:
        os.link(partial, path)  # Never replace an older receipt.
    finally:
        partial.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bulk", type=Path, required=True)
    parser.add_argument("--logical-primitives", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--edif", type=Path, required=True)
    parser.add_argument("--source-export-receipt", type=Path, required=True)
    parser.add_argument("--native-log", type=Path, required=True)
    parser.add_argument("--native-receipt", type=Path, help="Required for legacy native streams without checkpoint identity")
    parser.add_argument("--native-exit-code", type=int, required=True)
    parser.add_argument("--case", action="append", nargs=4, metavar=("NAME", "MAPPING", "RW_CROSSINGS", "RW_PRIMITIVES"), required=True)
    parser.add_argument("--max-record-bytes", type=int, default=1024**3)
    parser.add_argument("--owner-cache-entries", type=int, default=250000)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.native_exit_code != 0:
        raise ValueError("Native exporter did not exit successfully")
    if args.max_record_bytes <= 0 or args.owner_cache_entries < 0:
        raise ValueError("Invalid memory bounds")
    source = json.loads(args.source_export_receipt.read_text())
    if source.get("status") != "PASS" or source.get("checkpoint_sha256_before") != source.get("checkpoint_sha256_after"):
        raise ValueError("Source export receipt is incomplete or checkpoint changed")
    expected_nets, metadata = completion(args.native_log)
    policy = Path(__file__).with_name("compare_partition_reference.py")
    snapshots = {path.resolve(): digest(path) for path in
                 (args.bulk, args.logical_primitives, args.checkpoint, args.edif, args.source_export_receipt,
                  args.native_log, Path(__file__), policy)}
    if snapshots[args.checkpoint.resolve()] != source["checkpoint_sha256_after"]:
        raise ValueError("Checkpoint differs from original source export")
    if snapshots[args.edif.resolve()] != source["exports_sha256"]["post_synth.edf"]:
        raise ValueError("EDIF differs from original source export")
    if snapshots[args.logical_primitives.resolve()] != source["exports_sha256"]["logical_leaf_primitives.tsv"]:
        raise ValueError("Logical census differs from original source export")
    if metadata["format"] == "compressed_complete_census":
        if Path(metadata["checkpoint"]).resolve() != args.checkpoint.resolve():
            raise ValueError("Native producer opened a different checkpoint")
        if int(metadata["gzip_bytes"]) != args.bulk.stat().st_size:
            raise ValueError("Native compressed byte count differs")
        for name in ("native_cut_export_script.tcl", "native_cut_gzip_pipe.py"):
            snapshot = args.native_log.parent / name
            if not snapshot.is_file():
                raise ValueError("Native producer source snapshot is missing: " + name)
            snapshots[snapshot.resolve()] = digest(snapshot)
    else:
        if not args.native_receipt:
            raise ValueError("Legacy native stream requires its protected source identity receipt")
        native = json.loads(args.native_receipt.read_text())
        snapshots[args.native_receipt.resolve()] = digest(args.native_receipt)
        if native.get("status") != "PASS" or native["checkpoint_sha256"] != snapshots[args.checkpoint.resolve()] or native["edif_sha256"] != snapshots[args.edif.resolve()]:
            raise ValueError("Legacy native source identity differs")
        if native["exports_sha256"].get(args.bulk.name) != snapshots[args.bulk.resolve()]:
            raise ValueError("Legacy native stream differs from protected receipt")
    cases = []
    for name, mapping, crossings, primitives in args.case:
        if primitives == "-":
            raise ValueError("CLI production comparisons require the primitive histogram")
        case = prepare_case(name, mapping, crossings, primitives)
        cases.append(case)
        for path in (case["mapping"], case["rw_crossings"], case["rw_primitives"]):
            snapshots[path.resolve()] = digest(path)
    result = compare_stream(args.bulk, args.logical_primitives, cases, expected_nets,
                            args.max_record_bytes, args.owner_cache_entries,
                            lambda n, sec: print(f"NATIVE_STREAM_COMPARE_PROGRESS nets={n} elapsed_seconds={sec:.1f}", flush=True))
    for path, before in snapshots.items():
        if digest(path) != before:
            raise ValueError("Input changed during comparison: " + str(path))
    result.update(status="PASS" if result["passed"] else "FAIL", native_completion=metadata,
                  native_exit_code=0, source_files_sha256={str(p): h for p, h in snapshots.items()},
                  comparator_sha256=digest(Path(__file__)),
                  shared_cut_policy_sha256=digest(Path(__file__).with_name("compare_partition_reference.py")))
    atomic_json(args.output, result)
    print(json.dumps({"status": result["status"], "native_canonical_nets": result["native_canonical_nets"], "cases": result["cases"]}, indent=2))
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
