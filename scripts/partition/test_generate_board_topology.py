import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result

GENERATOR, BOARD = module("generate_board_topology"), module("topology_input")
SCRATCH = Path(__file__).resolve().parents[2] / "build/partition-python-tests"


class ExperimentalTopologyContract(unittest.TestCase):
    def test_requested_chain_and_grid_validate_with_exact_lanes_and_edges(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as temporary:
            output = Path(temporary)
            for rows, columns, edges in ((1, 3, 2), (4, 7, 45)):
                source = GENERATOR.generate(output / f"{rows}x{columns}.json", rows, columns)
                data = json.loads(source.read_text())
                self.assertTrue(data["experimental"])
                self.assertFalse(data["physical_system_verified"])
                self.assertEqual(data["connected_pairs"], edges)
                self.assertEqual({edge["lanes"] for edge in data["edges"]}, {16} if rows == 1 else {8, 16})
                self.assertTrue(all(edge["lanes"] == (8 if edge["cross_slot"] else 16) for edge in data["edges"]))
                k, _, _ = BOARD.load_board_topology(source, output / f"validated_{rows}x{columns}",
                    lambda index: "FPGA_" + chr(65 + index % 26) + "Z" * (index // 26))
                self.assertEqual(k, rows * columns)
                before = source.read_bytes()
                with self.assertRaises(FileExistsError):
                    GENERATOR.generate(source, rows, columns)
                self.assertEqual(source.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
