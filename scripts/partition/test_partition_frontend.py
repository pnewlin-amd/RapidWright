"""Frontend acceptance regressions, with an optional recorded-native integration.

Run from the checkout: python -m unittest discover -s scripts/partition -p test_partition_frontend.py
Set PARTITION_FRONTEND_NATIVE_FIXTURE to a completed SHA4 candidate directory to
replay its native measurements through the real Java provenance/cap gate. The
replay substitutes only the expensive solver and native reporting stages.
Its input_reference.json (or PARTITION_FRONTEND_REFERENCE manifest) must identify
input_edif and native_primitives; no sibling design repository is required.
"""
import importlib.util
import csv
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("partition_frontend", Path(__file__).with_name("partitioner.py"))
FRONTEND = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FRONTEND)
WORK = Path(__file__).resolve().parents[2] / "build/partition-python-tests"


class CommandHelp(unittest.TestCase):
    def test_high_level_interface_help_renders_without_formatting_error(self):
        help_text = FRONTEND.parser().format_help()
        self.assertIn("--input", help_text)
        self.assertIn("--system-topology", help_text)
        self.assertIn("10%", help_text)


class TopologyContract(unittest.TestCase):
    def test_twenty_eight_fpga_labels_and_nonuniform_caps(self):
        WORK.joinpath("test_tmp").mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=WORK / "test_tmp") as temporary:
            root = Path(temporary)
            # These labels intentionally exercise the existing Java mapping
            # contract beyond Z, where Excel labels would break the native gate.
            labels = ["FPGA_" + chr(65 + i) for i in range(26)] + ["FPGA_AZ", "FPGA_BZ"]
            source = root / "system.json"
            data = {"fpgas": [{"id": label, "clb_luts": 1000 + i} for i, label in enumerate(labels)],
                    "links": [{"u": labels[i], "v": labels[(i + 1) % len(labels)], "cost": i + 1}
                              for i in range(len(labels))]}
            source.write_text(json.dumps(data))
            k, graph, caps = FRONTEND.topology(source, root)
            self.assertEqual(k, 28)
            self.assertEqual(caps["FPGA_AZ"], 1026)
            self.assertEqual(caps["FPGA_BZ"], 1027)
            self.assertEqual(graph.read_text().splitlines()[0], "28 28 1")
            self.assertEqual(FRONTEND.fpga_label(27), "FPGA_BZ")
            del data["fpgas"][-1]["clb_luts"]
            source.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, "every FPGA"):
                FRONTEND.topology(source, root)


class QualityAcceptanceContract(unittest.TestCase):
    def test_native_balance_gain_cannot_exceed_the_cut_budget(self):
        # Recorded Earl Grey k3 tradeoff: native imbalance improved from3.98%
        # to1.11%, but transfers grew3.48%. This must not qualify as an early
        # success or displace the cut-feasible candidate under a3% budget.
        baseline = {"transfers": 2930, "topology_distance": 3638.0,
                    "max_crossings": 7, "endpoint_excess": 58268, "transit_primitives": 6596}
        better_balance = {"transfers": 3032, "topology_distance": 3587.0,
                          "max_crossings": 6, "endpoint_excess": 46694, "transit_primitives": 4945}
        self.assertFalse(FRONTEND.within_guard(better_balance, baseline, 0.03))
        better_balance["transfers"] = 3018  # Exact inclusive integer growth limit.
        self.assertTrue(FRONTEND.within_guard(better_balance, baseline, 0.03))

    def test_endpoint_improvement_cannot_hide_more_transit_logic(self):
        baseline = {"transfers": 1000, "topology_distance": 1500.0,
                    "max_crossings": 5, "endpoint_excess": 2000, "transit_primitives": 400}
        candidate = {"transfers": 990, "topology_distance": 1490.0,
                     "max_crossings": 4, "endpoint_excess": 1000, "transit_primitives": 401}
        self.assertFalse(FRONTEND.within_guard(candidate, baseline, 0.03))

    def test_feedback_cannot_compound_the_seed_and_cleanup_growth_allowance(self):
        baseline = {"transfers": 110, "topology_distance": 220.0,
                    "guard_reference_transfers": 100, "guard_reference_topology": 200.0,
                    "max_crossings": 4, "endpoint_excess": 100, "transit_primitives": 50}
        candidate = {"transfers": 111, "topology_distance": 220.0,
                     "max_crossings": 3, "endpoint_excess": 80, "transit_primitives": 40}
        self.assertFalse(FRONTEND.within_guard(candidate, baseline, 0.10))
        candidate["transfers"] = 110
        self.assertTrue(FRONTEND.within_guard(candidate, baseline, 0.10))
        candidate["topology_distance"] = 221.0
        self.assertFalse(FRONTEND.within_guard(candidate, baseline, 0.10))

    def test_path_quality_precedes_soft_balance_within_the_growth_budget(self):
        balanced = {"path_analysis_complete": True, "transfers": 100, "topology_distance": 150.0,
                    "max_crossings": 7, "endpoint_excess": 200, "transit_primitives": 100, "target_imbalance": 0.01}
        fewer_snakes = dict(balanced, transfers=105, max_crossings=6, endpoint_excess=150,
                            transit_primitives=80, target_imbalance=0.04)
        self.assertTrue(FRONTEND.within_guard(fewer_snakes, balanced, 0.10))
        self.assertIs(min([balanced, fewer_snakes], key=FRONTEND.candidate_priority), fewer_snakes)

    def test_explicit_modeled_timing_precedes_structural_ties_and_cannot_regress(self):
        baseline = {"path_analysis_complete": True, "transfers": 100, "topology_distance": 150.0,
                    "max_crossings": 4, "endpoint_excess": 200, "transit_primitives": 100,
                    "target_imbalance": 0.03, "timing_cost": 10.0, "modeled_worst_slack_ns": -2.0}
        lower_crossings = dict(baseline, max_crossings=2, endpoint_excess=150,
                               transit_primitives=80, timing_cost=5.0, modeled_worst_slack_ns=-1.0)
        better_timing = dict(baseline, max_crossings=3, endpoint_excess=120,
                             transit_primitives=70, timing_cost=4.0, modeled_worst_slack_ns=0.0)
        self.assertTrue(FRONTEND.within_guard(lower_crossings, baseline, 0.10))
        self.assertTrue(FRONTEND.within_guard(better_timing, baseline, 0.10))
        self.assertIs(min([lower_crossings, better_timing], key=FRONTEND.candidate_priority), better_timing)
        self.assertFalse(FRONTEND.within_guard(dict(better_timing, timing_cost=11), baseline, 0.10))
        self.assertFalse(FRONTEND.within_guard(dict(better_timing, modeled_worst_slack_ns=-3), baseline, 0.10))


class FeedbackIntegerContract(unittest.TestCase):
    def feedback_fixture(self, root, primary, assignments, native):
        (root / "frontier.tsv").write_text("vertex\tpath\tlogical_luts\tprimary_weight\n" +
            "".join(f"{i+1}\tlogic_{i+1}\t{weight}\t{weight}\n" for i, weight in enumerate(primary)))
        (root / "final_assignment.part").write_text("".join(f"{part}\n" for part in assignments))
        target = root / "feedback.tsv"
        scale, total = FRONTEND.feedback(root, target, "a" * 64, native)
        with target.open(newline="") as handle:
            rows = list(csv.DictReader((line for line in handle if not line.startswith("#")), delimiter="\t"))
        return scale, total, [int(row["integer_weight"]) for row in rows], target

    def test_tight_heterogeneous_caps_are_not_made_impossible_by_vertex_rounding(self):
        WORK.joinpath("test_tmp").mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=WORK / "test_tmp") as temporary:
            root = Path(temporary)
            # Initial logical3/4 is legal, but native2/2 violates physical caps1/3.
            # Independent rounding used to emit62*3+54*4=402 units against400
            # units of total capacity, making every next native solve impossible.
            scale, total, weights, target = self.feedback_fixture(root, [1] * 7, [0] * 3 + [1] * 4,
                                                                  {"FPGA_A": 2, "FPGA_B": 2})
            self.assertEqual(scale, 100)
            self.assertEqual(total, 400)
            self.assertEqual(sum(weights), 400)
            self.assertLessEqual(sum(weights), scale * (1 + 3))
            first = target.read_bytes()
            self.feedback_fixture(root, [1] * 7, [0] * 3 + [1] * 4, {"FPGA_A": 2, "FPGA_B": 2})
            self.assertEqual(target.read_bytes(), first)  # Stable largest-remainder ties.

    def test_memory_only_zero_primary_partition_receives_native_area_feedback(self):
        WORK.joinpath("test_tmp").mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=WORK / "test_tmp") as temporary:
            scale, total, weights, _ = self.feedback_fixture(Path(temporary), [0, 0, 1, 1], [0, 0, 1, 1],
                                                            {"FPGA_A": 2, "FPGA_B": 2})
            self.assertGreater(sum(weights[:2]), 0)
            self.assertEqual(sum(weights), scale * 4)
            self.assertEqual(total, scale * 4)
            self.assertTrue(all(weight >= 0 for weight in weights))


@unittest.skipUnless(os.environ.get("PARTITION_FRONTEND_NATIVE_FIXTURE"),
                     "Recorded-native integration needs PARTITION_FRONTEND_NATIVE_FIXTURE")
class RecordedNativeAcceptance(unittest.TestCase):
    def run_replay(self, change_final_cap):
        fixture = Path(os.environ["PARTITION_FRONTEND_NATIVE_FIXTURE"]).resolve()
        native = fixture / "vivado_reference"
        metadata = dict(line.split("=", 1) for line in (native / "provenance.txt").read_text().splitlines()
                        if "=" in line)
        checkpoint = Path(metadata["checkpoint"])
        reference_path = Path(os.environ.get("PARTITION_FRONTEND_REFERENCE", fixture / "input_reference.json"))
        reference = json.loads(reference_path.read_text())
        def source_path(key):
            value = Path(reference[key])
            return value if value.is_absolute() else reference_path.parent / value
        inventory, netlist = source_path("native_primitives"), source_path("input_edif")
        self.assertTrue(inventory.is_file() and netlist.is_file(), "Recorded-native reference inputs must exist")
        real_run = FRONTEND.run
        WORK.joinpath("test_tmp").mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=WORK / "test_tmp") as temporary:
            root = Path(temporary)
            topology = root / "topology.json"
            labels = ["FPGA_A", "FPGA_B", "FPGA_C", "FPGA_D"]
            topology.write_text(json.dumps({"fpgas": [{"id": label, "clb_luts": 2200} for label in labels],
                                           "links": [{"u": labels[i], "v": labels[j]} for i in range(4) for j in range(i + 1, 4)]}))
            output = root / "accepted"
            args = FRONTEND.parser().parse_args(["--input", str(checkpoint), "--system-topology", str(topology),
                "--output", str(output), "--iterations", "1", "--starts", "1", "--refine-passes", "0",
                "--max-luts", "2000", "--target-part", "xcvp1902", "--timing", "off"])

            def replay(command, cwd, log):
                command = [str(value) for value in command]
                if any(value.endswith("prepare_partition_input.py") for value in command):
                    source_dir = Path(command[command.index("--output") + 1])
                    source_dir.mkdir(parents=True)
                    (source_dir / "metadata.json").write_text(json.dumps({"input_edif": str(netlist),
                        "checkpoint": str(checkpoint), "native_primitives": str(inventory)}))
                elif FRONTEND.MAIN + "Partitioner" in command:
                    candidate = Path(command[command.index("--part_dir") + 1])
                    for name in ("mapping.txt", "hardware_quality.txt", "frontier.tsv", "final_assignment.part",
                                 "lut_report.txt", "logical_resources.tsv", "primitive_counts.tsv", "run_provenance.json",
                                 *FRONTEND.mapping_contract_module().REQUIRED_ARTIFACTS):
                        if (fixture / name).exists():
                            shutil.copy2(fixture / name, candidate / name)
                elif any(value.endswith("prepare_native_resource_cells.py") for value in command):
                    target = Path(command[command.index("--output") + 1])
                    shutil.copytree(native, target)
                elif "-tclargs" in command:
                    pass  # The unchanged, completed native export is replayed.
                elif any(value.endswith("archive_native_request.py") for value in command):
                    # Exercise the real archive step between the two native
                    # gates; compression must not make either cap gate weaker.
                    real_run(command, cwd, log)
                else:
                    self.assertIn(FRONTEND.MAIN + "VivadoResourceOracle", command)
                    if log.name == "acceptance.log":
                        if change_final_cap:
                            (output / "native_lut_caps.tsv").write_text("partition\tclb_luts\n" +
                                "".join(f"{label}\t{0 if label == 'FPGA_A' else 2000}\n" for label in labels))
                        self.assertIn("--lut-caps", command)
                    extra_classes = os.environ.get("PARTITION_FRONTEND_TEST_CLASSES")
                    if extra_classes:
                        classpath = command.index("-cp") + 1
                        command[classpath] = extra_classes + os.pathsep + command[classpath]
                    real_run(command, cwd, log)

            with patch.object(FRONTEND, "run", replay):
                if change_final_cap:
                    with self.assertRaisesRegex(RuntimeError, "cap exceeded"):
                        FRONTEND.execute(args)
                else:
                    FRONTEND.execute(args)
            status = json.loads((output / "STATUS.json").read_text())
            self.assertEqual(status["status"], "FAIL" if change_final_cap else "CAP_VALIDATED_WITH_LIMITATIONS")
            self.assertEqual((output / "mapping.txt").exists(), not change_final_cap)
            if not change_final_cap:
                self.assertEqual(status["hard_native_clb_lut_caps"], {label: 2000 for label in labels})
                self.assertEqual(status["native_cap_check"], "PASS")
                self.assertFalse(status["native_balance_target_met"])
                self.assertEqual((output / "mapping.txt").read_bytes(), (fixture / "mapping.txt").read_bytes())

    def test_native_cap_success_publishes_only_validated_mapping(self):
        self.run_replay(False)

    def test_final_native_gate_rejects_changed_caps_before_mapping_publication(self):
        self.run_replay(True)


if __name__ == "__main__":
    unittest.main()
