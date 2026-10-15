"""Export a bounded native Vivado setup-path sample from an unchanged checkpoint.

Usage: python tools/prepare_timing_paths.py SOURCE.dcp SOURCE.edf OUTPUT_DIR
No clocks, delays, exceptions, optimization or partition wrappers are created.
The TSV supplies native slack/requirement and ordered data-path primitive owners;
it is sampled source-design STA, not timing closure for a partitioned platform.
"""
import argparse
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tkinter
import shutil


def sha256(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def identity(checkpoint, edif):
    return {"checkpoint": str(checkpoint.resolve()), "checkpoint_sha256": sha256(checkpoint),
            "edif": str(edif.resolve()), "input_sha256": sha256(edif)}


def record_identity(checkpoint, edif, output):
    output.mkdir(parents=True, exist_ok=True)
    values = identity(checkpoint, edif)
    request = output / "timing_request_identity.json"
    if request.exists() and json.loads(request.read_text()) != values:
        raise ValueError("Timing output is already bound to a different source checkpoint/EDIF")
    request.write_text(json.dumps(values, indent=2) + "\n", encoding="utf-8")
    return values


def finite(value):
    try:
        number = Decimal(value)
        return number if number.is_finite() else None
    except InvalidOperation:
        return None


def primitive_owners(names):
    """Exact outermost native primitive boundary, preserving expanded macros."""
    result = {}
    outer = None
    for name in sorted(names):
        if outer is None or not name.startswith(outer + "/"):
            outer = name
        result[name] = outer
    return result


def path_cells(fields, tcl, owners):
    pin_names, pin_parents = tcl.splitlist(fields[12]), tcl.splitlist(fields[13])
    if len(pin_names) != len(pin_parents):
        raise ValueError("Native timing pin NAME/PARENT_CELL counts differ")
    cells = []

    def append(cell):
        if not cells or cells[-1] != cell:
            cells.append(cell)

    def ordinary(cell):
        if cell not in owners:
            raise ValueError("Unresolved native timing primitive owner: " + cell)
        return owners[cell]

    def endpoint(name, kind, parent):
        if kind == "port":
            return "@top_io:" + name
        if kind == "pin":
            return ordinary(parent)
        raise ValueError("Unknown native timing endpoint class: " + kind)

    append(endpoint(fields[6], fields[8], fields[10]))
    for pin, parent in zip(pin_names, pin_parents):
        # The owning cell comes from the actual PARENT_CELL property, rather
        # than guessing hierarchy from the spelling of the pin NAME.
        if not pin or not parent:
            raise ValueError("Missing native timing data pin metadata")
        append(ordinary(parent))
    append(endpoint(fields[7], fields[9], fields[11]))
    return cells, len(pin_names)


def write_lines(path, lines):
    pending = path.with_name(path.name + f".partial.{os.getpid()}")
    pending.write_text("\n".join(lines) + "\n", encoding="utf-8")
    os.replace(pending, path)


def finalize(checkpoint, edif, output, maximum):
    expected = json.loads((output / "timing_request_identity.json").read_text())
    if identity(checkpoint, edif) != expected:
        raise ValueError("Source checkpoint/EDIF changed during native timing export")
    metadata = dict(line.split("=", 1) for line in (output / "native_timing_complete.txt").read_text().splitlines() if "=" in line)
    if metadata.get("status") != "NATIVE_TIMING_PATH_EXPORT_PASS":
        raise ValueError("Native timing export is incomplete")
    if (Path(metadata["checkpoint"]).resolve() != checkpoint.resolve()
            or Path(metadata["edif"]).resolve() != edif.resolve()):
        raise ValueError("Native timing completion source paths differ from request")
    if int(metadata["requested_max_paths"]) != maximum:
        raise ValueError("Native timing sample limit differs from request")
    tcl = tkinter.Tcl()
    inventory = output / "native_primitive_names.tcllist"
    native_names = tcl.splitlist(inventory.read_text(encoding="utf-8-sig"))
    if len(native_names) != len(set(native_names)):
        raise ValueError("Duplicate native primitive name in timing inventory")
    owners = primitive_owners(native_names)
    raw = output / "native_timing_paths.tcllist"
    accepted, details, skipped, identifiers = [], [], [], set()
    unconstrained = nonfinite = 0
    for line_number, line in enumerate(raw.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        fields = tcl.splitlist(line)
        if len(fields) != 14:
            raise ValueError(f"Expected fourteen native timing fields at row {line_number}")
        path_id, slack, requirement, delay, launch, capture = fields[:6]
        if path_id in identifiers or int(path_id) < 0:
            raise ValueError("Duplicate/invalid native timing path ID")
        identifiers.add(path_id)
        if any("\t" in value or "\n" in value or "\r" in value for value in fields[:12]):
            raise ValueError("Native scalar/name cannot be represented in the timing TSV")
        reason = None
        if finite(requirement) is None:
            reason = "unconstrained_or_nonfinite_requirement"
            unconstrained += 1
        elif finite(slack) is None or finite(delay) is None:
            reason = "nonfinite_slack_or_datapath_delay"
            nonfinite += 1
        if reason:
            skipped.append("\t".join([path_id, slack, requirement, delay, reason]))
            continue
        cells, native_pin_count = path_cells(fields, tcl, owners)
        accepted.append("\t".join([path_id, slack, requirement, launch, capture,
                                   json.dumps(cells, ensure_ascii=True, separators=(",", ":"))]))
        details.append("\t".join([path_id, slack, requirement, delay, fields[6], fields[7],
                                  str(native_pin_count), str(len(cells))]))
    native_count = int(metadata["native_path_count"])
    if len(identifiers) != native_count or native_count > maximum:
        raise ValueError("Native timing path count/completion marker mismatch")
    saved_helper = output / "prepare_timing_paths.py"
    if saved_helper.resolve() != Path(__file__).resolve():
        shutil.copyfile(__file__, saved_helper)
    comments = ["#input_sha256=" + expected["input_sha256"], "#checkpoint_sha256=" + expected["checkpoint_sha256"],
                "#coverage=sampled_setup_paths", f"#requested_max_paths={maximum}", f"#native_sampled_paths={native_count}",
                f"#skipped_unconstrained={unconstrained}", f"#skipped_nonfinite={nonfinite}",
                "#design_unconstrained_path_count=unknown", "#data_cell_order=native_get_pins_of_timing_path",
                "#top_io_marker=@top_io:", "#clock_constraints=unchanged_source_checkpoint"]
    write_lines(output / "timing_paths.tsv", comments + ["path_id\tslack_ns\trequirement_ns\tlaunch_clock\tcapture_clock\tcells_json"] + accepted)
    write_lines(output / "timing_path_details.tsv", ["path_id\tslack_ns\trequirement_ns\tdatapath_delay_ns\tstartpoint_pin\tendpoint_pin\tnative_data_pins\tlogical_cells"] + details)
    write_lines(output / "timing_skipped_paths.tsv", ["path_id\tslack_ns\trequirement_ns\tdatapath_delay_ns\treason"] + skipped)
    result = dict(expected, status="PASS", available=bool(accepted), coverage="sampled_setup_paths",
                  reason="Finite native constrained setup paths exported" if accepted else "No finite constrained setup paths in native sample",
                  requested_max_paths=maximum, native_sampled_paths=native_count, exported_paths=len(accepted),
                  skipped_unconstrained=unconstrained, skipped_nonfinite=nonfinite,
                  native_clock_count=int(metadata["native_clock_count"]), design_unconstrained_path_count=None,
                  vivado_version=metadata.get("vivado_version"), native_top=metadata.get("top"), native_part=metadata.get("part"),
                  cells="Ordered native DATA pin owners, consecutive duplicates collapsed, logical macro boundaries retained",
                  timing_paths=str((output / "timing_paths.tsv").resolve()),
                  raw_paths_sha256=sha256(raw), native_primitive_inventory_sha256=sha256(inventory),
                  native_completion_sha256=sha256(output / "native_timing_complete.txt"),
                  python_exporter_sha256=sha256(Path(__file__)),
                  tcl_exporter_sha256=sha256(output / "export_partition_timing.tcl"),
                  timing_paths_sha256=sha256(output / "timing_paths.tsv"),
                  constraints_created=False, optimization_performed=False, partition_netlist_created=False,
                  scope="Native original synthesized/implemented checkpoint STA sample; no inter-FPGA transport delay or timing closure claim")
    (output / "timing_reference.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("edif", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--max-paths", type=int, default=1000)
    parser.add_argument("--vivado", default=os.environ.get("VIVADO", "vivado"))
    parser.add_argument("--record-identity", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--process-native", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not 1 <= args.max_paths <= 100000:
        raise ValueError("Timing max-paths must be in 1..100000")
    checkpoint, edif, output = args.checkpoint.resolve(), args.edif.resolve(), args.output.resolve()
    if args.record_identity:
        record_identity(checkpoint, edif, output)
        return
    if args.process_native:
        result = finalize(checkpoint, edif, output, args.max_paths)
        values = ("available", str(int(result["available"])), "exported_paths", str(result["exported_paths"]),
                  "timing_paths", result["timing_paths"], "reference", str(output / "timing_reference.json"), "reason", result["reason"])
        print(tkinter.Tcl().call("format", "%s", values))
        return
    record_identity(checkpoint, edif, output)
    script = Path(__file__).with_name("export_partition_timing.tcl").resolve()
    command = [args.vivado, "-mode", "batch", "-source", str(script), "-log", str(output / "vivado.log"),
               "-journal", str(output / "vivado.jou"), "-tclargs", "--partition-timing-standalone", str(checkpoint), str(edif), str(output), str(args.max_paths)]
    environment = os.environ.copy()
    environment["RW_TIMING_PYTHON"] = sys.executable
    with (output / "console.log").open("w", encoding="utf-8") as log:
        completed = subprocess.run(command, cwd=output, env=environment, stdout=log, stderr=subprocess.STDOUT)
    if completed.returncode or not (output / "timing_reference.json").is_file():
        raise RuntimeError(f"Native timing export failed (exit {completed.returncode}); see {output / 'console.log'}")
    print((output / "timing_reference.json").read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
