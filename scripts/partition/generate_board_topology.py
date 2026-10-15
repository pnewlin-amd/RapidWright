"""Write a NEW illustrative c/1 chain/grid; never overwrite a topology file."""
import argparse
import json
from pathlib import Path


def generate(output, rows, columns, board=None):
    if type(rows) is not int or type(columns) is not int or rows < 1 or columns < 1 or not 2 <= rows * columns <= 64:
        raise ValueError("Experimental board needs positive dimensions and 2..64 FPGAs")
    k = rows * columns
    devices = [{"id": r * columns + c, "path": f"EXP/R{r+1:02d}/P{c+1:02d}",
                "chassis": "EXP", "slot": f"R{r+1:02d}", "position": f"P{c+1:02d}"}
               for r in range(rows) for c in range(columns)]
    edges, adjacency = [], {device["path"]: {} for device in devices}
    for r in range(rows):
        for c in range(columns):
            a = devices[r * columns + c]
            for rr, cc in ((r, c + 1), (r + 1, c)):
                if rr >= rows or cc >= columns:
                    continue
                b, lanes = devices[rr * columns + cc], 16 if rr == r else 8
                edges.append({"a": a["path"], "b": b["path"], "a_id": a["id"], "b_id": b["id"],
                              "lanes": lanes, "wires_at_min_compression": lanes * 64, "cross_slot": rr != r})
                adjacency[a["path"]][b["path"]] = lanes
                adjacency[b["path"]][a["path"]] = lanes
    for device in devices:
        device["degree"] = len(adjacency[device["path"]])
    possible = k * (k - 1) // 2
    lanes = {edge["lanes"] for edge in edges}
    data = {"schema": "c/1", "board": board or f"experimental_{rows}x{columns}",
            "experimental": True, "physical_system_verified": False,
            "note": "Illustrative connected chain/grid, not the supplied physical board. Horizontal links declare 16 lanes; vertical links declare 8. The 64 units per lane convention is experimental metadata, not a verified pin capacity, TDM schedule or latency.",
            "fpga_count": k, "possible_pairs": possible, "connected_pairs": len(edges),
            "connectivity_fraction": round(len(edges) / possible, 4), "lane_min": min(lanes), "lane_max": max(lanes),
            "lane_uniform": len(lanes) == 1, "complete_graph": len(edges) == possible,
            "wires_per_lane_at_min_compression": 64, "fpgas": devices, "edges": edges, "adjacency": adjacency}
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive create closes the existence-check/write race; existing user
    # JSONs, including a requested target that already exists, are untouched.
    with output.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, indent=2)
        handle.write("\n")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="shape", required=True)
    chain = subparsers.add_parser("chain")
    chain.add_argument("count", type=int)
    chain.add_argument("output", type=Path)
    grid = subparsers.add_parser("grid")
    grid.add_argument("rows", type=int)
    grid.add_argument("columns", type=int)
    grid.add_argument("output", type=Path)
    args = parser.parse_args()
    generate(args.output, 1 if args.shape == "chain" else args.rows,
             args.count if args.shape == "chain" else args.columns)


if __name__ == "__main__":
    main()
