#!/usr/bin/env python3
"""Render the saved report and figures; requires an installed pdflatex.

Install scripts/partition/requirements.txt before running. No TeX packages are
downloaded. MiKTeX's automatic installer is explicitly disabled. Temporary TeX
output stays under this repository's build directory; only the completed PDF
is copied into doc/partition. Run the built-in editor compiler separately when
validating an edit in Codex.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdflatex", default="pdflatex", help="Installed pdflatex executable, otherwise discovered on PATH")
    arguments = parser.parse_args()
    executable = shutil.which(arguments.pdflatex)
    if executable is None:
        raise SystemExit("pdflatex was not found. Install/configure TeX first; this script does not download it.")
    version = subprocess.run([executable, "--version"], check=True, capture_output=True, text=True).stdout
    extra = ["--disable-installer"] if "miktex" in version.lower() else []
    subprocess.run([sys.executable, str(HERE / "build_report_figures.py")], cwd=HERE, check=True)
    build = (REPOSITORY / "build").resolve()
    build.mkdir(exist_ok=True)
    environment = os.environ.copy()
    environment["MIKTEX_ENABLE_INSTALLER"] = "0"
    with tempfile.TemporaryDirectory(prefix="partition-report-", dir=build) as temporary:
        temporary_path = Path(temporary).resolve()
        if temporary_path.parent != build:
            raise RuntimeError("Temporary TeX directory escaped the repository build directory")
        command = [executable, *extra, "-interaction=nonstopmode", "-halt-on-error", "-no-shell-escape",
                   f"-output-directory={temporary_path}", "VALIDATION_REPORT.tex"]
        for _ in range(2):
            subprocess.run(command, cwd=HERE, env=environment, check=True)
        source = temporary_path / "VALIDATION_REPORT.pdf"
        if not source.is_file() or source.stat().st_size == 0:
            raise RuntimeError("pdflatex did not produce a nonempty report PDF")
        destination = HERE / "VALIDATION_REPORT.pdf"
        pending = destination.with_suffix(".pdf.pending")
        try:
            shutil.copyfile(source, pending)
            os.replace(pending, destination)
        finally:
            pending.unlink(missing_ok=True)
    print(f"Wrote {destination}")


if __name__ == "__main__":
    main()
