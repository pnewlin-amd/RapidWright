#!/usr/bin/env python3
"""Prepare a native Vivado EDIF/checkpoint pair and resource census for partitioning."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--vivado", default=os.environ.get("VIVADO", "vivado"))
    parser.add_argument("--part", default="")
    parser.add_argument("--timing-max-paths", type=int, default=1000,
                        help="Finite source setup paths to sample; 0 disables STA sampling")
    args = parser.parse_args()
    if not 0 <= args.timing_max_paths <= 100000:
        parser.error("--timing-max-paths must be in 0..100000")
    source = args.input.resolve(strict=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    executable = shutil.which(args.vivado) or args.vivado
    script = Path(__file__).with_name("export_partition_input.tcl").resolve()
    command = [executable, "-mode", "batch", "-source", str(script),
               "-log", str(output / "vivado.log"), "-journal", str(output / "vivado.jou"),
               "-tclargs", str(source), str(output), args.part,
               str(args.timing_max_paths)]
    env = os.environ.copy()
    env["RW_PARTITION_PYTHON"] = sys.executable
    (output / "command.json").write_text(json.dumps(command, indent=2) + "\n", encoding="utf-8")
    with (output / "console.log").open("w", encoding="utf-8") as handle:
        completed = subprocess.run(command, cwd=output, env=env, stdout=handle,
                                   stderr=subprocess.STDOUT, check=False)
    log = (output / "console.log").read_text(encoding="utf-8", errors="replace")
    if completed.returncode != 0 or "\nPARTITION_INPUT_EXPORT_PASS\n" not in log:
        raise RuntimeError(f"Native input export failed (exit {completed.returncode}); see {output / 'console.log'}")
    metadata = json.loads((output / "metadata.json").read_text(encoding="utf-8"))
    if metadata.get("status") != "PASS" or metadata.get("ready") is not True:
        raise RuntimeError("Native export did not produce PASS metadata")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
