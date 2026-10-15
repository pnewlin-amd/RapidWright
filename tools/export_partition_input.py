#!/usr/bin/env python3
"""Zip native Vivado property columns and bind export provenance to SHA-256.

Called by export_partition_input.tcl. Physical CLB LUT counts are exclusively
reported by native report_utilization; this helper never estimates LUT packing.
"""
from __future__ import annotations

from collections import Counter
import csv
from datetime import datetime, timezone
import hashlib
from itertools import zip_longest
import json
from pathlib import Path
import re
import sys

PROPERTIES = ("NAME", "REF_NAME", "PRIMITIVE_TYPE", "PRIMITIVE_GROUP",
              "PRIMITIVE_SUBGROUP", "IS_SEQUENTIAL")
FIELDS = ("name", "ref_name", "primitive_type", "primitive_group",
          "primitive_subgroup", "is_sequential")


def sha256(path: Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def declared_edif_part(path: Path) -> str:
    pattern = re.compile(r'\(property\s+part\s+\(string\s+"([^"\r\n]+)"\)\s*\)', re.I)
    part, tail = "", ""
    with path.open(encoding="utf-8") as handle:
        while chunk := handle.read(1024 * 1024):
            text = tail + chunk
            for match in pattern.finditer(text):
                part = match.group(1)
            tail = text[-1024:]
    return part


def begin(source: Path, output: Path, requested_part: str) -> None:
    if source.suffix.lower() not in {".dcp", ".edf", ".edif"}:
        raise ValueError("Input must be a .dcp, .edf, or .edif file")
    output.mkdir(parents=True, exist_ok=True)
    source_part = declared_edif_part(source) if source.suffix.lower() != ".dcp" else ""
    metadata = {"status": "PREPARING", "ready": False, "source_input": str(source),
                "source_sha256": sha256(source), "declared_edif_part": source_part,
                "requested_part": requested_part,
                "started_utc": datetime.now(timezone.utc).isoformat()}
    write_json(output / "metadata.json", metadata)
    print(requested_part or source_part)


def finish(source: Path, output: Path, checkpoint: Path, edif: Path,
           part: str, top: str, version: str, expected_count: int) -> None:
    metadata = json.loads((output / "metadata.json").read_text(encoding="utf-8"))
    if metadata["source_input"] != str(source) or metadata["source_sha256"] != sha256(source):
        raise ValueError("Source changed during native export")
    names, counts = [], Counter()
    native = output / "leaf_primitives.tsv"
    handles = [(output / "native_columns" / (prop + ".txt")).open(encoding="utf-8")
               for prop in PROPERTIES]
    sentinel = object()
    try:
        with native.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
            writer.writerow(FIELDS)
            for row in zip_longest(*handles, fillvalue=sentinel):
                if any(value is sentinel for value in row):
                    raise ValueError("Native property columns have different lengths")
                values = [value.rstrip("\n") for value in row]
                if any("\t" in value or "\r" in value or "\n" in value for value in values):
                    raise ValueError("Native properties contain unsupported TSV control characters")
                if not values[0] or not values[1]:
                    raise ValueError("Native primitive NAME and REF_NAME must be nonempty")
                writer.writerow(values)
                names.append(values[0])
                counts[values[1]] += 1
    finally:
        for handle in handles:
            handle.close()
    if len(names) != expected_count or len(set(names)) != len(names):
        raise ValueError(f"Native census count/uniqueness mismatch: {len(names)} vs {expected_count}")
    # Stop at outermost native primitive macros, matching logical EDIF leaves.
    logical_names, prefix = set(), ""
    for name in sorted(names):
        if not prefix or not name.startswith(prefix):
            logical_names.add(name)
            prefix = name + "/"
    logical_counts = Counter()
    with native.open(encoding="utf-8", newline="") as handle, (output / "logical_leaf_primitives.tsv").open("w", encoding="utf-8", newline="") as target:
        reader = csv.DictReader(handle, delimiter="\t")
        writer = csv.DictWriter(target, fieldnames=FIELDS, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        for row in reader:
            if row["name"] in logical_names:
                writer.writerow(row)
                logical_counts[row["ref_name"]] += 1
    for filename, histogram in (("primitive_counts.tsv", counts), ("logical_primitive_counts.tsv", logical_counts)):
        with (output / filename).open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
            writer.writerow(("ref_name", "count"))
            writer.writerows(sorted(histogram.items()))
    reports = {name: sha256(output / name) for name in ("utilization.rpt", "utilization_hierarchical.rpt")}
    metadata.update({"status": "PASS", "ready": True, "input_edif": str(edif),
                     "checkpoint": str(checkpoint), "native_primitives": str(native),
                     "logical_native_primitives": str(output / "logical_leaf_primitives.tsv"),
                     "part": part, "top": top, "vivado_version": version,
                     "checkpoint_sha256": sha256(checkpoint), "edif_sha256": sha256(edif),
                     "native_primitives_sha256": sha256(native),
                     "expanded_primitive_cells": expected_count,
                     "logical_primitive_cells": len(logical_names),
                     "report_sha256": reports,
                     "exporter_sha256": sha256(Path(__file__).with_suffix(".tcl")),
                     "helper_sha256": sha256(Path(__file__)),
                     "finished_utc": datetime.now(timezone.utc).isoformat(),
                     "transformations": "DCP opened unchanged; EDIF read/link only; no synthesis, optimization, placement, or partition wrapping"})
    write_json(output / "metadata.json", metadata)
    print(f"PARTITION_INPUT_METADATA_PASS primitives={expected_count} logical={len(logical_names)}")


def attach_timing(source: Path, output: Path, error: str) -> None:
    """Add source-STA availability without invalidating a valid base export."""
    target = output / "metadata.json"
    metadata = json.loads(target.read_text(encoding="utf-8"))
    if metadata.get("source_input") != str(source) or metadata.get("status") != "PASS":
        raise ValueError("Cannot attach timing to an unverified base export")
    reference = output / "timing" / "timing_reference.json"
    timing, available, reason = {}, False, error or "Native timing reference unavailable"
    if not error and reference.is_file():
        try:
            timing = json.loads(reference.read_text(encoding="utf-8"))
            if timing.get("status") != "PASS":
                raise ValueError("Native timing reference did not pass")
            if timing.get("checkpoint_sha256") != metadata["checkpoint_sha256"]:
                raise ValueError("Timing checkpoint identity mismatch")
            if timing.get("input_sha256") != metadata["edif_sha256"]:
                raise ValueError("Timing EDIF identity mismatch")
            available = timing.get("available") is True
            reason = timing.get("reason", "")
            if available:
                paths = Path(timing["timing_paths"])
                if not paths.is_file():
                    raise ValueError("Native timing path file is missing")
                if sha256(paths) != timing.get("timing_paths_sha256"):
                    raise ValueError("Native timing path content identity mismatch")
        except (OSError, ValueError, KeyError) as exc:
            available, reason = False, str(exc)
    metadata.update({"timing_available": available, "timing_reason": reason,
                     "timing_reference": str(reference) if reference.is_file() else None,
                     "timing_paths": timing.get("timing_paths") if available else None,
                     "timing_paths_sha256": timing.get("timing_paths_sha256") if available else None,
                     "timing_exported_paths": timing.get("exported_paths", 0) if available else 0,
                     "timing_scope": "Sampled finite setup paths under existing source constraints; no post-partition STA"})
    write_json(target, metadata)
    print(f"PARTITION_INPUT_TIMING_METADATA_PASS available={available}")


def disable_timing(source: Path, output: Path) -> None:
    metadata = json.loads((output / "metadata.json").read_text(encoding="utf-8"))
    if metadata.get("source_input") != str(source) or metadata.get("status") != "PASS":
        raise ValueError("Cannot disable timing on an unverified base export")
    directory = output / "timing"
    directory.mkdir(exist_ok=True)
    write_json(directory / "timing_reference.json", {
        "status": "PASS", "available": False,
        "reason": "Disabled by --timing-max-paths=0; no STA queried",
        "timing_paths": None, "exported_paths": 0, "native_sampled_paths": 0,
        "requested_max_paths": 0, "skipped_unconstrained": 0,
        "skipped_nonfinite": 0, "native_clock_count": None,
        "input_sha256": metadata["edif_sha256"],
        "checkpoint_sha256": metadata["checkpoint_sha256"],
        "scope": "Source STA disabled; structural partition analysis only"})
    print("PARTITION_INPUT_TIMING_DISABLED")


if __name__ == "__main__":
    action, source_arg, output_arg, *rest = sys.argv[1:]
    source, output = Path(source_arg).resolve(), Path(output_arg).resolve()
    if action == "begin":
        begin(source, output, rest[0] if rest else "")
    elif action == "finish":
        cp, edf, selected_part, top_name, tool_version, count = rest
        finish(source, output, Path(cp).resolve(), Path(edf).resolve(), selected_part,
               top_name, tool_version, int(count))
    elif action == "attach-timing":
        attach_timing(source, output, rest[0] if rest else "")
    elif action == "disable-timing":
        disable_timing(source, output)
    else:
        raise ValueError(f"Unknown action: {action}")
