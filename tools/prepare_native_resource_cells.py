"""Prepare exact logical-boundary cell lists for the native Vivado oracle.

Uses a native exported primitive inventory and a final mapping. Resource values
are still obtained exclusively by report_utilization in the original checkpoint.
This avoids iterating large Vivado native collection objects in Tcl.
"""
import argparse
import csv
import re
import hashlib
from collections import defaultdict
from pathlib import Path

from compare_partition_reference import mapping_rules, resolve


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--native-primitives", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rules = mapping_rules(args.mapping)
    with args.native_primitives.open(encoding="utf-8-sig", newline="") as handle:
        cells = {row["name"]: row["ref_name"] for row in csv.DictReader(handle, delimiter="\t")}
    assigned = {label: [] for label in set(rules.values())}
    lut_counts = defaultdict(int)
    lut_name = re.compile(r"LUT[1-6](_[DL])?|LUT6_2|LUT6CY|CFGLUT5|LUTCY[12]")
    for name, ref in cells.items():
        ancestor = name
        while "/" in ancestor:
            ancestor = ancestor.rsplit("/", 1)[0]
            if ancestor in cells:
                if resolve(name, rules) != resolve(ancestor, rules):
                    raise ValueError(f"Mapping splits the internal implementation of primitive macro {ancestor}: {name}")
                break
        else:
            label = resolve(name, rules)
            assigned[label].append(name)
            if lut_name.fullmatch(ref):
                lut_counts[label] += 1
    args.output.mkdir(parents=True, exist_ok=True)
    rows = ["partition\tcell_file\tlogical_luts\tprimitive_count"]
    for label in sorted(assigned):
        cell_file = args.output / ("native_cells_" + label + ".txt")
        cells_for_partition = sorted(assigned[label])
        cell_file.write_text("\n".join(cells_for_partition) + ("\n" if cells_for_partition else ""), encoding="utf-8")
        rows.append(f"{label}\t{cell_file.resolve().as_posix()}\t{lut_counts[label]}\t{len(cells_for_partition)}")
    (args.output / "preassigned_cells.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    with args.checkpoint.open("rb") as handle:
        checkpoint_hash = hashlib.file_digest(handle, "sha256").hexdigest()
    metadata = {"mapping_sha256": hashlib.sha256(args.mapping.read_bytes()).hexdigest(),
                "checkpoint_sha256": checkpoint_hash,
                "native_inventory_sha256": hashlib.sha256(args.native_primitives.read_bytes()).hexdigest(),
                "native_inventory_cells": len(cells)}
    (args.output / "preassigned_hashes.txt").write_text("".join(f"{key}={value}\n" for key, value in metadata.items()), encoding="utf-8")
    print(f"NATIVE_CELL_LISTS_READY partitions={len(assigned)} logical_boundaries={sum(map(len, assigned.values()))}")


if __name__ == "__main__":
    main()
