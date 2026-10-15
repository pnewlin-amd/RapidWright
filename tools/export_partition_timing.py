"""Native source-design STA sample; no netlist rewriting or new constraints."""
import argparse
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--edif", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--vivado", default=os.environ.get("VIVADO", "vivado"))
    parser.add_argument("--max-paths", type=int, default=1000)
    args = parser.parse_args()
    helper = Path(__file__).with_name("prepare_timing_paths.py")
    subprocess.run([sys.executable, str(helper), str(args.checkpoint), str(args.edif), str(args.output),
                    "--vivado", args.vivado, "--max-paths", str(args.max_paths)], check=True)


if __name__ == "__main__":
    main()
