"""Compare mapping cut records against independently exported Vivado endpoints.

No synthesis, wrapping or netlist changes. The compared unit is one canonical
logical net per directed FPGA pair, including clock/reset nets and top I/O.
Endpoint fingerprints make the check independent of hierarchical net aliases.
"""
import argparse
import csv
import json
import hashlib
from collections import defaultdict
from pathlib import Path

csv.field_size_limit(128 * 1024 * 1024)


def mapping_rules(path):
    rules = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        source, dest = line.split()
        if source in rules and rules[source] != dest:
            raise ValueError(f"Conflicting mapping: {source}")
        rules[source] = dest
    roots = []
    for path in rules:
        ancestor = path
        while "/" in ancestor:
            ancestor = ancestor.rsplit("/", 1)[0]
            if ancestor in rules:
                break
        else:
            roots.append(path)
    if len(roots) != 1:
        raise ValueError(f"Expected one named root mapping, got {roots}")
    top = roots[0]
    normalized = {"" if p == top else p[len(top) + 1:]: d for p, d in rules.items()}
    return normalized


def resolve(path, rules):
    while True:
        if path in rules:
            return rules[path]
        if "/" not in path:
            return rules[""]
        path = path.rsplit("/", 1)[0]


def native_crossings(endpoint_tsv, primitive_tsv, rules):
    with primitive_tsv.open(encoding="utf-8-sig", newline="") as handle:
        types = {row["name"]: row["ref_name"] for row in csv.DictReader(handle, delimiter="\t")}
    nets = defaultdict(list)
    with endpoint_tsv.open(encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            nets[row["net_id"]].append(row)
    output = {}
    for endpoints in nets.values():
        if any(row["kind"] == "pin" and types.get(row["cell"]) in ("VCC", "GND") for row in endpoints):
            continue
        drivers, loads, fingerprint = set(), set(), set()
        for row in endpoints:
            top = row["kind"] == "port"
            direction = row["direction"]
            driver = direction == "INOUT" or direction == ("IN" if top else "OUT")
            load = direction == "INOUT" or direction == ("OUT" if top else "IN")
            part = resolve("" if top else row["cell"], rules)
            name = "PORT:" + row["pin"] if top else row["cell"] + "/" + row["pin"]
            fingerprint.add(name + ":" + ("D" if driver else "") + ("L" if load else ""))
            if driver:
                drivers.add(part)
            if load:
                loads.add(part)
        crossings = {a + "->" + b for a in drivers for b in loads if a != b}
        if crossings:
            key = "sha256:" + hashlib.sha256(";".join(sorted(fingerprint)).encode("utf-8")).hexdigest()
            if key in output:
                raise ValueError("Duplicate native endpoint fingerprint")
            output[key] = {"name": endpoints[0]["net_name"], "pairs": sorted(crossings)}
    return output


def rapidwright_crossings(path):
    output = {}
    with path.open(encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            key = row["ENDPOINT_FINGERPRINT"]
            if not key.startswith("sha256:"):
                key = "sha256:" + hashlib.sha256(key.encode("utf-8")).hexdigest()
            if key in output:
                raise ValueError("Duplicate RapidWright endpoint fingerprint")
            output[key] = {"name": row["CANONICAL_NET"], "pairs": sorted(row["DIRECTED_PARTITION_PAIRS"].split(","))}
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--vivado-endpoints", type=Path, required=True)
    parser.add_argument("--vivado-primitives", type=Path, required=True)
    parser.add_argument("--rw-crossings", type=Path, required=True)
    parser.add_argument("--rw-primitives", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    native = native_crossings(args.vivado_endpoints, args.vivado_primitives, mapping_rules(args.mapping))
    rw = rapidwright_crossings(args.rw_crossings)
    missing, extra = sorted(native.keys() - rw.keys()), sorted(rw.keys() - native.keys())
    mismatch = [key for key in native.keys() & rw.keys() if native[key]["pairs"] != rw[key]["pairs"]]
    hist_mismatch = []
    if args.rw_primitives:
        native_hist = defaultdict(int)
        with args.vivado_primitives.open(encoding="utf-8-sig", newline="") as handle:
            for row in csv.DictReader(handle, delimiter="\t"):
                native_hist[row["ref_name"]] += 1
        with args.rw_primitives.open(encoding="utf-8-sig", newline="") as handle:
            rw_hist = {row["REF_NAME"]: int(row["COUNT"]) for row in csv.DictReader(handle, delimiter="\t")}
        hist_mismatch = [{"ref_name": ref, "vivado": native_hist.get(ref, 0), "rapidwright": rw_hist.get(ref, 0)}
                         for ref in sorted(native_hist.keys() | rw_hist.keys())
                         if native_hist.get(ref, 0) != rw_hist.get(ref, 0)]
    result = {"passed": not (missing or extra or mismatch or hist_mismatch),
              "vivado_crossing_nets": len(native), "rapidwright_crossing_nets": len(rw),
              "vivado_directed_net_destinations": sum(len(v["pairs"]) for v in native.values()),
              "rapidwright_directed_net_destinations": sum(len(v["pairs"]) for v in rw.values()),
              "missing_count": len(missing), "extra_count": len(extra), "pair_mismatch_count": len(mismatch),
              "missing_sample": [native[k] for k in missing[:10]],
              "extra_sample": [rw[k] for k in extra[:10]],
              "pair_mismatch_sample": [{"vivado": native[k], "rapidwright": rw[k]} for k in mismatch[:10]],
              "primitive_histogram_mismatch": hist_mismatch,
              "unit": "canonical logical net per directed FPGA pair, clocks/resets included; not physical I/O pins"}
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
