"""Generate reproducible illustrative weighted FPGA mesh topologies, METIS fmt=1.

Link weights are positive relative routing/latency cost, NOT link bandwidth.
These examples are experiments, not descriptions of the user's platform.
"""
import argparse
from pathlib import Path


def generate(rows, cols, output):
    adjacency = [dict() for _ in range(rows * cols)]
    for r in range(rows):
        for c in range(cols):
            u = r * cols + c
            for rr, cc in ((r + 1, c), (r, c + 1)):
                if rr < rows and cc < cols:
                    v = rr * cols + cc
                    # Model uneven links by making inter-row hops twice as expensive.
                    weight = 2 if rr != r else 1
                    adjacency[u][v] = weight
                    adjacency[v][u] = weight
    output.parent.mkdir(parents=True, exist_ok=True)
    edges = sum(map(len, adjacency)) // 2
    lines = [f"{rows * cols} {edges} 1"]
    lines += [" ".join(f"{v + 1} {weight}" for v, weight in sorted(row.items())) for row in adjacency]
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("rows", type=int)
    parser.add_argument("cols", type=int)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.rows < 1 or args.cols < 1 or args.rows * args.cols < 2:
        parser.error("topology needs at least two FPGA nodes")
    generate(args.rows, args.cols, args.output)
