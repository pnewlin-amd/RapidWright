"""Board schema validation and unchanged supplied-topology regression fixtures."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("board_topology", Path(__file__).with_name("topology_input.py"))
BOARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BOARD)
REPO = Path(__file__).resolve().parents[2]
TOPOLOGIES = REPO / "test/resources/partition/topologies"
SCRATCH = REPO / "build/partition-python-tests"
FIXTURES = {"j4_board_topology.json": (4, 6, "6f393e7eee76cbf20cd96e46f52c8c0a34be2365c7222aa6cf7abfe87c7e1b83"),
            "p9_board_topology.json": (24, 84, "076695c34574f63beade1aed1a4021bebb54dc881346d83dde02f330b70774f2")}


def label(index):
    return "FPGA_" + chr(65 + index % 26) + "Z" * (index // 26)


class BoardTopologyContract(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.addCleanup(self.temporary.cleanup)
        self.work = Path(self.temporary.name)

    def load(self, data):
        source = self.work / "input.json"
        source.write_text(json.dumps(data))
        return BOARD.load_board_topology(source, self.work / "result", label)

    def supplied(self, name):
        return json.loads((TOPOLOGIES / name).read_text())

    def test_exact_supplied_boards_preserve_hash_identity_missing_edges_and_lanes(self):
        for name, (expected_k, expected_edges, expected_hash) in FIXTURES.items():
            source = TOPOLOGIES / name
            before = source.read_bytes()
            self.assertEqual(hashlib.sha256(before).hexdigest(), expected_hash)
            output = self.work / name
            k, graph, caps = BOARD.load_board_topology(source, output, label)
            self.assertEqual(k, expected_k)
            self.assertEqual(caps, {})
            lines = [line for line in graph.read_text().splitlines() if not line.startswith("%")]
            self.assertEqual(lines[0], f"{k} {expected_edges} 1")
            manifest = json.loads((output / "topology_manifest.json").read_text())
            self.assertEqual(manifest["source_sha256"], expected_hash)
            original = json.loads(before)
            self.assertEqual(manifest["source_document"], original)
            self.assertEqual({frozenset((edge["a_id"], edge["b_id"])) for edge in manifest["edges"]},
                             {frozenset((edge["a_id"], edge["b_id"])) for edge in original["edges"]})
            self.assertEqual(sum(len(line.split()) // 2 for line in lines[1:]), expected_edges * 2)
            self.assertEqual([device["id"] for device in manifest["fpgas"]], list(range(k)))
            self.assertEqual(manifest["fpgas"][0]["path"], original["fpgas"][0]["path"])
            self.assertFalse(manifest["hard_routed_bandwidth_enforced"])
            self.assertIsNone(manifest["measured_link_delay_ns"])
            costs = manifest["cost_policy"]["cost_by_lanes"]
            self.assertEqual(costs, {"16": 1} if k == 4 else {"8": 25, "16": 24})
            self.assertEqual(manifest["rapidwright_cost_scale"], 1 if k == 4 else 24)
            self.assertIn(f"% rapidwright_cost_scale={manifest['rapidwright_cost_scale']}", graph.read_text())
            self.assertEqual(source.read_bytes(), before)

    def test_noncontiguous_ids_sorted_with_capacity_and_endpoint_correspondence(self):
        data = {"schema": "c/1", "fpga_count": 2,
                "fpgas": [{"id": 90, "path": "C/S/B", "clb_luts": 20},
                          {"id": 7, "path": "C/S/A", "clb_luts": 10}],
                "edges": [{"a_id": 90, "b_id": 7, "a": "C/S/B", "b": "C/S/A", "lanes": 4}]}
        k, graph, caps = self.load(data)
        self.assertEqual(caps, {"FPGA_A": 10, "FPGA_B": 20})
        manifest = json.loads((graph.parent / "topology_manifest.json").read_text())
        self.assertEqual([(row["id"], row["partition"]) for row in manifest["fpgas"]], [(7, "FPGA_A"), (90, "FPGA_B")])
        self.assertEqual(manifest["edges"][0]["partition_a"], "FPGA_B")

    def test_j4_one_through_four_are_exact_induced_subgraphs_without_source_edits(self):
        source = TOPOLOGIES / "j4_board_topology.json"
        before = source.read_bytes()
        for count, expected_edges in ((1, 0), (2, 1), (3, 3), (4, 6)):
            output = self.work / f"selected_{count}"
            k, graph, caps = BOARD.load_board_topology(source, output, label, fpga_count=count)
            lines = [line for line in graph.read_text().splitlines() if not line.startswith("%")]
            self.assertEqual(k, count)
            self.assertEqual(lines[0], f"{count} {expected_edges} 1")
            self.assertEqual(len(lines), count + 1)  # Singleton retains a blank adjacency row.
            self.assertEqual(sum(len(line.split()) // 2 for line in lines[1:]), expected_edges * 2)
            manifest = json.loads((output / "topology_manifest.json").read_text())
            self.assertEqual(manifest["source_fpga_count"], 4)
            self.assertEqual(manifest["source_connected_pairs"], 6)
            self.assertEqual(manifest["selected_ids"], list(range(count)))
            self.assertEqual(len(manifest["source_document"]["fpgas"]), 4)
            self.assertEqual(manifest["connected_pairs"], expected_edges)
            self.assertEqual({row["source_degree"] for row in manifest["fpgas"]}, {3})
            self.assertEqual({row["selected_degree"] for row in manifest["fpgas"]}, {count - 1})
            if count == 1:
                self.assertEqual(manifest["cost_policy"]["cost_by_lanes"], {})
                self.assertEqual(manifest["rapidwright_cost_scale"], 1)
            self.assertEqual(source.read_bytes(), before)
        self.assertEqual(hashlib.sha256(before).hexdigest(), FIXTURES[source.name][2])

    def test_explicit_selected_ids_keep_physical_identity_in_sorted_native_order(self):
        source = TOPOLOGIES / "j4_board_topology.json"
        k, graph, _ = BOARD.load_board_topology(source, self.work / "explicit", label, selected_ids=[3, 1])
        manifest = json.loads((graph.parent / "topology_manifest.json").read_text())
        self.assertEqual(k, 2)
        self.assertEqual([(row["id"], row["partition"], row["path"]) for row in manifest["fpgas"]],
                         [(1, "FPGA_A", "C01/S01/F"), (3, "FPGA_B", "C01/S01/H")])

    def test_subset_validation_rejects_disconnection_and_validates_unselected_source(self):
        source = TOPOLOGIES / "p9_board_topology.json"
        with self.assertRaisesRegex(ValueError, "Selected.*connected"):
            BOARD.load_board_topology(source, self.work / "disconnected", label, selected_ids=[0, 5])
        invalid = self.supplied("j4_board_topology.json")
        invalid["fpgas"][3]["degree"] = 99  # Unselected ID3 must still be validated.
        copied = self.work / "invalid_source.json"
        copied.write_text(json.dumps(invalid))
        with self.assertRaisesRegex(ValueError, "degree"):
            BOARD.load_board_topology(copied, self.work / "invalid", label, fpga_count=1)
        j4 = TOPOLOGIES / "j4_board_topology.json"
        for options in ({"fpga_count": 0}, {"fpga_count": 5}, {"fpga_count": True},
                        {"selected_ids": []}, {"selected_ids": [0, 0]}, {"selected_ids": [4]},
                        {"selected_ids": [True]}, {"fpga_count": 3, "selected_ids": [0, 1]}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                BOARD.load_board_topology(j4, self.work / "bad_selection", label, **options)

    def test_wider_links_are_secondary_to_hops_and_missing_pairs_stay_absent(self):
        _, graph, _ = self.load(self.supplied("p9_board_topology.json"))
        rows = [dict(zip(map(int, line.split()[::2]), map(int, line.split()[1::2])))
                for line in [line for line in graph.read_text().splitlines() if not line.startswith("%")][1:]]
        self.assertNotIn(6, rows[0])  # Physical ID0--5 is absent, not a default complete edge.
        self.assertEqual(rows[0][2], 24)  # ID0--1 has16 lanes.
        self.assertEqual(rows[0][5], 25)  # ID0--4 has8 lanes.
        self.assertLess(max(rows[0].values()), 2 * min(rows[0].values()))
        # Independent BFS/Dijkstra: every minimum-cost route has minimum hops.
        for start in range(len(rows)):
            hops, pending = {start: 0}, [start]
            for node in pending:
                for neighbor in rows[node]:
                    neighbor -= 1
                    if neighbor not in hops:
                        hops[neighbor] = hops[node] + 1
                        pending.append(neighbor)
            scores, unsettled = {start: (0, 0)}, set(range(len(rows)))
            while unsettled:
                node = min((node for node in unsettled if node in scores), key=lambda node: scores[node])
                unsettled.remove(node)
                for neighbor, cost in rows[node].items():
                    neighbor -= 1
                    candidate = (scores[node][0] + cost, scores[node][1] + 1)
                    if neighbor not in scores or candidate < scores[neighbor]:
                        scores[neighbor] = candidate
            self.assertEqual({node: result[1] for node, result in scores.items()}, hops)

    def test_bad_physical_identity_counts_and_metadata_are_rejected(self):
        original = self.supplied("j4_board_topology.json")
        mutations = [lambda d: d["fpgas"][0].update(id=True),
                     lambda d: d["fpgas"][1].update(id=0),
                     lambda d: d["edges"][0].update(a_id=3),
                     lambda d: d["edges"][0].update(lanes=0),
                     lambda d: d["edges"][0].update(lanes=True),
                     lambda d: d["edges"][0].update(wires_at_min_compression=1),
                     lambda d: d["edges"][0].update(cross_slot=True),
                     lambda d: d["fpgas"][0].update(degree=2),
                     lambda d: d.update(connected_pairs=5),
                     lambda d: d.update(connectivity_fraction=0.5),
                     lambda d: d["adjacency"]["C01/S01/E"].update({"C01/S01/F": 8}),
                     lambda d: d["edges"].append(copy.deepcopy(d["edges"][0])),
                     lambda d: d["fpgas"][0].update(clb_luts=10)]
        for mutate in mutations:
            data = copy.deepcopy(original)
            mutate(data)
            with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                self.load(data)

    def test_disconnected_graph_and_duplicate_json_keys_fail_without_outputs(self):
        data = self.supplied("j4_board_topology.json")
        data["edges"] = data["edges"][:1]
        with self.assertRaisesRegex(ValueError, "connected"):
            self.load(data)
        self.assertFalse((self.work / "result").exists())
        source = self.work / "duplicate.json"
        source.write_text('{"schema":"c/1","schema":"c/1"}')
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            BOARD.load_board_topology(source, self.work / "result", label)

    def test_existing_source_or_export_files_are_never_overwritten(self):
        source = TOPOLOGIES / "j4_board_topology.json"
        output = self.work / "result"
        output.mkdir()
        existing = output / "system.graph"
        existing.write_bytes(b"keep existing graph")
        before = source.read_bytes()
        with self.assertRaisesRegex(ValueError, "overwrite"):
            BOARD.load_board_topology(source, output, label)
        self.assertEqual(existing.read_bytes(), b"keep existing graph")
        self.assertEqual(source.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
