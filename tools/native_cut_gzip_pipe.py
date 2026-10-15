"""Bounded stdin-to-gzip fallback for Vivado builds without Tcl zlib.

The native producer owns publication: this helper writes only its fresh partial
path. EOF, gzip trailer completion, flush and fsync must all succeed first.
"""
import argparse
import gzip
import os
import sys
from pathlib import Path


def compress_stream(source, target, level=1):
    with target.open("xb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=level, mtime=0) as packed:
            while block := source.read(1024 * 1024):
                packed.write(block)
        raw.flush()
        os.fsync(raw.fileno())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    compress_stream(sys.stdin.buffer, args.output)


if __name__ == "__main__":
    main()
