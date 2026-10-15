"""Independent materialized-TSV endpoint oracle for tiny regression fixtures.

Preserves the original native logical-boundary flattening algorithm independently
of the streaming comparator under test. This is a test helper, not a runtime export.
"""
import csv
import hashlib
import json
import os
from pathlib import Path
import tkinter


def digest(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def flatten(bulk, primitives, output, receipt, expected_nets=None, native_log=None):
    with primitives.open(encoding="utf-8-sig", newline="") as handle:
        names = [row["name"] for row in csv.DictReader(handle, delimiter="\t")]
    if len(names) != len(set(names)):
        raise ValueError("Duplicate logical primitive name")
    logical = set(names)
    tcl = tkinter.Tcl()  # Tcl interpreter only; no GUI/window is created.
    output.parent.mkdir(parents=True, exist_ok=True)
    pending = output.with_name(output.name + f".partial.{os.getpid()}")
    net_ids, net_names, endpoints_seen = set(), set(), {}
    rows = pin_rows = port_rows = 0
    with bulk.open(encoding="utf-8-sig") as source, pending.open("w", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, delimiter="\t", lineterminator="\n")
        writer.writerow(["net_id", "net_name", "kind", "direction", "cell", "pin", "full_pin"])
        for line_number, line in enumerate(source, 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            try:
                fields = tcl.splitlist(line)
                if len(fields) != 7:
                    raise ValueError("Expected seven Tcl-list fields")
                net_id, canonical = fields[:2]
                if int(net_id) < 0 or net_id in net_ids or canonical in net_names:
                    raise ValueError("Negative/duplicate native net ID or canonical name")
                net_ids.add(net_id)
                net_names.add(canonical)
                pin_names, pin_refs, pin_dirs, ports, port_dirs = [tcl.splitlist(value) for value in fields[2:]]
                if not (len(pin_names) == len(pin_refs) == len(pin_dirs)) or len(ports) != len(port_dirs):
                    raise ValueError("Native property-list lengths differ")
                emitted = {}

                def emit(kind, direction, cell, pin, full_pin):
                    nonlocal rows, pin_rows, port_rows
                    if direction not in ("IN", "OUT", "INOUT"):
                        raise ValueError("Unknown native pin/port direction: " + direction)
                    key = (kind, full_pin)
                    metadata = (direction, cell, pin)
                    if key in emitted:
                        if emitted[key] != metadata:
                            raise ValueError("Conflicting duplicate native endpoint metadata")
                        return
                    emitted[key] = metadata
                    if key in endpoints_seen and endpoints_seen[key] != net_id:
                        raise ValueError("Native endpoint belongs to multiple canonical nets: " + full_pin)
                    endpoints_seen[key] = net_id
                    writer.writerow([net_id, canonical, kind, direction, cell, pin, full_pin])
                    rows += 1
                    pin_rows += kind == "pin"
                    port_rows += kind == "port"

                for full_pin, ref, direction in zip(pin_names, pin_refs, pin_dirs):
                    cell, separator, unused_pin = full_pin.rpartition("/")
                    if not separator:
                        raise ValueError("Native pin NAME has no owning cell: " + full_pin)
                    if cell in logical:
                        emit("pin", direction, cell, ref, full_pin)
                for name, direction in zip(ports, port_dirs):
                    emit("port", direction, "", name, name)
            except (ValueError, tkinter.TclError) as error:
                raise ValueError(f"Invalid native bulk endpoint row {line_number}: {error}") from error
    if expected_nets is not None and len(net_ids) != expected_nets:
        raise ValueError(f"Native completion marker net count differs: {len(net_ids)} != {expected_nets}")
    os.replace(pending, output)
    result = {"status": "NATIVE_BULK_ENDPOINT_FLATTEN_PASS", "method": "Native queried connectivity; exact logical-boundary filtering",
              "source_bulk": str(bulk.resolve()), "source_bulk_sha256": digest(bulk),
              "logical_primitives": str(primitives.resolve()), "logical_primitives_sha256": digest(primitives),
              "script_sha256": digest(Path(__file__)), "endpoint_tsv": str(output.resolve()), "endpoint_tsv_sha256": digest(output),
              "native_canonical_nets": len(net_ids), "logical_primitive_boundaries": len(logical),
              "endpoint_rows": rows, "logical_pin_rows": pin_rows, "top_port_rows": port_rows}
    if native_log is not None:
        result.update(native_log=str(native_log.resolve()), native_log_sha256=digest(native_log))
    receipt.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result
