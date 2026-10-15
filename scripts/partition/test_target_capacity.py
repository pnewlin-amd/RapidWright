"""Physical target caps use the portable native catalog; no vendor process runs."""
import copy
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


CAPACITY, BOARD = module("target_capacity"), module("topology_input")
REPO = Path(__file__).resolve().parents[2]
SCRATCH = REPO / "build/partition-python-tests"


class TargetCapacityContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog, _, _ = CAPACITY.load_catalog()

    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "board.json"
        self.board = {"schema": "c/1", "fpgas": [{"id": 7, "path": "C/S/A"},
                                                 {"id": 90, "path": "C/S/B"}],
                      "edges": [{"a_id": 7, "b_id": 90, "a": "C/S/A", "b": "C/S/B", "lanes": 8}]}

    def prepare(self, *, target=None, existing_caps=None, utilization=.75, parts=None, board=None, count=None):
        self.source.write_text(json.dumps(board or self.board), encoding="utf-8")
        output = self.root / "output"
        k, _, topology_caps = BOARD.load_board_topology(
            self.source, output, lambda i: "FPGA_" + chr(65 + i), fpga_count=count)
        if parts is not None:
            binding = {"schema": "fpga_target_parts_v1", "topology_sha256": CAPACITY.sha(self.source),
                       "parts_by_physical_id": parts}
            self.source.with_name(self.source.name + ".targets.json").write_text(json.dumps(binding))
        return CAPACITY.prepare(self.source, output, ["FPGA_" + chr(65 + i) for i in range(k)],
                                topology_caps if existing_caps is None else existing_caps,
                                target_part=target, utilization=utilization)

    def test_vp1902_vu19p_aliases_and_exact_part_variants_agree(self):
        for aliases, expected in ((("VP1902", "vp1902", "xcvp1902"), 8460288),
                                  (("VU19P", "vu19p", "xcvu19p"), 4085760)):
            for alias in aliases:
                resolved = CAPACITY.resolve_part(alias, self.catalog)
                self.assertEqual(resolved["lut_elements"], expected)
                exact = CAPACITY.resolve_part(resolved["canonical_part_examples"][0], self.catalog)
                self.assertEqual((exact["device"], exact["lut_elements"]),
                                 (resolved["device"], expected))
        with self.assertRaisesRegex(ValueError, "Unknown"):
            CAPACITY.resolve_part("unknown_part_123", self.catalog)

    def test_explicit_caps_can_only_tighten_the_native_75_percent_limit(self):
        caps, sizes, result, pins = self.prepare(target="VP1902",
            existing_caps={"FPGA_A": 5000000, "FPGA_B": 9000000})
        self.assertEqual(sizes, {"FPGA_A": 8460288, "FPGA_B": 8460288})
        self.assertEqual(caps, {"FPGA_A": 5000000, "FPGA_B": 6345216})
        self.assertFalse(result["source_checkpoint_part_inferred_as_target"])
        self.assertTrue(result["default_policy_is_user_engineering_bound_not_route_proof"])
        self.assertTrue(all(CAPACITY.sha(path) == digest for path, digest in pins.items()))

    def test_utilization_tightening_uses_integer_floor(self):
        caps, _, result, _ = self.prepare(target="VU19P", utilization=.333333)
        self.assertEqual(caps, {"FPGA_A": 1361918, "FPGA_B": 1361918})
        self.assertEqual(result["devices"][0]["physical_lut_capacity"], 4085760)

    def test_utilization_above_75_percent_or_nonfinite_is_never_accepted(self):
        for value in (.7500001, 1, 0, -.1, True, float("nan"), float("inf")):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "75|0.75"):
                CAPACITY.prepare(self.source, self.root / "never_created", ["FPGA_A"], {},
                                 target_part="VP1902", utilization=value)
        self.assertFalse((self.root / "never_created").exists())

    def test_partless_topology_fails_without_assuming_the_source_part(self):
        with self.assertRaisesRegex(ValueError, "Missing target part"):
            self.prepare()
        self.assertFalse((self.root / "output/target_capacity.json").exists())

    def test_bound_companion_keeps_noncontiguous_ids_and_heterogeneous_sizes(self):
        caps, sizes, result, _ = self.prepare(parts={"7": "VP1902", "90": "VU19P"})
        self.assertEqual(caps, {"FPGA_A": 6345216, "FPGA_B": 3064320})
        self.assertEqual(sizes, {"FPGA_A": 8460288, "FPGA_B": 4085760})
        self.assertEqual([(d["physical_id"], d["device"]) for d in result["devices"]],
                         [("7", "xcvp1902"), ("90", "xcvu19p")])
        CAPACITY.verify_originals(result)
        self.source.write_text(self.source.read_text() + "\n")
        with self.assertRaisesRegex(ValueError, "changed"):
            CAPACITY.verify_originals(result)

    def test_subset_companion_still_requires_all_original_physical_ids(self):
        caps, _, result, _ = self.prepare(parts={"7": "VP1902", "90": "VU19P"}, count=1)
        self.assertEqual(caps, {"FPGA_A": 6345216})
        self.assertEqual(len(result["devices"]), 1)

    def test_incomplete_companion_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "exactly cover every original"):
            self.prepare(parts={"7": "VP1902"}, count=1)

    def test_wrong_topology_hash_in_companion_is_rejected(self):
        self.source.write_text(json.dumps(self.board))
        output = self.root / "output"
        BOARD.load_board_topology(self.source, output, lambda i: "FPGA_" + chr(65 + i))
        self.source.with_name(self.source.name + ".targets.json").write_text(json.dumps({
            "schema": "fpga_target_parts_v1", "topology_sha256": "0" * 64,
            "parts_by_physical_id": {"7": "VP1902", "90": "VU19P"}}))
        with self.assertRaisesRegex(ValueError, "exact original topology bytes"):
            CAPACITY.prepare(self.source, output, ["FPGA_A", "FPGA_B"], {})

    def test_uniform_target_conflicting_with_companion_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Conflicting"):
            self.prepare(target="VP1902", parts={"7": "VU19P", "90": "VP1902"})

    def test_uniform_target_conflicting_with_embedded_part_is_rejected(self):
        board = copy.deepcopy(self.board)
        board["fpgas"][0]["part"] = "VU19P"
        with self.assertRaisesRegex(ValueError, "Conflicting"):
            self.prepare(target="VP1902", board=board)

    def test_catalog_replacement_rejects_altered_data_even_with_self_consistent_identity(self):
        catalog = self.root / "catalog.tsv"
        raw = CAPACITY.DEFAULT_CATALOG.read_bytes().replace(b"8460288", b"8460289")
        self.assertNotEqual(raw, CAPACITY.DEFAULT_CATALOG.read_bytes())
        catalog.write_bytes(raw)
        identity = json.loads(Path(str(CAPACITY.DEFAULT_CATALOG) + ".identity.json").read_text())
        identity["catalog_sha256"] = CAPACITY.sha(catalog)
        Path(str(catalog) + ".identity.json").write_text(json.dumps(identity))
        with self.assertRaisesRegex(ValueError, "Unapproved"):
            CAPACITY.load_catalog(catalog)


if __name__ == "__main__":
    unittest.main()
