"""Real frontend feedback/publication control with fake expensive tool boundaries.

These tests do not assert hardware quality from synthetic netlists. Native and
Java commands provide small completed reports; the real frontend must enforce
same-run identity, accepted-anchor control, memory policy and publication.
"""
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "quality_feedback_availability_fixture", Path(__file__).with_name("test_partition_timing_availability.py"))
AVAILABILITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AVAILABILITY)
FRONTEND = AVAILABILITY.FRONTEND


class AutomaticQualityFeedbackAcceptance(unittest.TestCase):
    def setUp(self):
        self.make_fixture()

    def make_fixture(self):
        fixture = AVAILABILITY.TimingAvailabilityAcceptance(methodName="runTest")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.fixture = fixture
        fixture.cache = "available"
        (fixture.repo / "bin/com/xilinx/rapidwright/edif/partition/AcceptedQualityAnchor.class").write_bytes(
            b"accepted-anchor-support-fixture")
        self.reject_second = False
        self.rejection_message = "No refinement candidate satisfies the accepted quality anchor"
        self.first_cap_failure = False
        self.first_incomplete = False
        self.mutate_native_mapping = False
        self.zero_area_hops = None
        self.after_feedback = None
        self.partition_commands = []
        self.native_commands = []
        self.seen_anchors = []
        original_boundary = fixture.run_boundary

        def boundary(command, cwd, log):
            command = [str(value) for value in command]
            is_partitioner = FRONTEND.MAIN + "Partitioner" in command
            if is_partitioner:
                self.partition_commands.append(command)
                candidate = Path(command[command.index("--part_dir") + 1])
                iteration = int(candidate.name.rsplit("_", 1)[1])
                if "--quality_anchor" in command:
                    anchor = Path(command[command.index("--quality_anchor") + 1])
                    self.seen_anchors.append((anchor, anchor.read_text()))
                if iteration == 1 and self.reject_second:
                    raise RuntimeError(self.rejection_message)
            original_boundary(command, cwd, log)
            if is_partitioner:
                if iteration >= 1:
                    (candidate / "mapping.txt").write_text("top/a FPGA_B\ntop FPGA_A\n")
                    (candidate / "final_assignment.part").write_text("1\n0\n")
                mapping = candidate / "mapping.txt"
                (candidate / "run_provenance.json").write_text(json.dumps({
                    "mapping_sha256": FRONTEND.sha256(mapping),
                    "input_sha256": FRONTEND.sha256(fixture.netlist), "partitions": 2}))
                quality = {
                    "complete": "false" if iteration == 0 and self.first_incomplete else "true",
                    "resource_coverage_complete": "true", "transfer_growth_reference": "10",
                    "topology_growth_reference": "20", "before_transfers": "10",
                    "after_transfers": "10" if iteration == 0 else "11",
                    "before_topology_distance": "20", "after_topology_distance": "20" if iteration == 0 else "22",
                    "after_max_register_free_crossings": "3" if iteration == 0 else "2",
                    "after_endpoint_excess_crossings": "20" if iteration == 0 else "10",
                    "after_transit_primitives": "4" if iteration == 0 else "3",
                    "timing_cost_after": "3" if iteration == 0 else "2", "timing_paths_scored": "1",
                }
                if self.zero_area_hops is not None:
                    destination = "FPGA_C" if self.zero_area_hops == 2 else "FPGA_B"
                    other = "FPGA_B" if destination == "FPGA_C" else "FPGA_C"
                    mapping.write_text(f"top/a FPGA_A\ntop/b {destination}\ntop/c {other}\ntop FPGA_A\n")
                    (candidate / "frontier.tsv").write_text(
                        "vertex\tpath\tlogical_luts\tprimary_weight\n1\ta\t0\t1\n2\tb\t0\t1\n3\tc\t0\t1\n")
                    (candidate / "final_assignment.part").write_text("0\n2\n1\n" if self.zero_area_hops == 2 else "0\n1\n2\n")
                    (candidate / "run_provenance.json").write_text(json.dumps({
                        "mapping_sha256": FRONTEND.sha256(mapping), "input_sha256": FRONTEND.sha256(fixture.netlist),
                        "partitions": 3, "minimum_link_hop_analysis_version": 1,
                        "minimum_link_hop_analysis_available": True}))
                    quality.update(after_transfers="10", after_topology_distance="20",
                                   after_max_register_free_crossings="1", after_endpoint_excess_crossings="0",
                                   after_transit_primitives="0", timing_cost_after="2",
                                   minimum_link_hop_analysis_available="true",
                                   after_max_minimum_link_hops=str(self.zero_area_hops),
                                   after_max_minimum_relay_traversals=str(self.zero_area_hops - 1))
                (candidate / "hardware_quality.txt").write_text("".join(f"{key}={value}\n" for key, value in quality.items()))
            elif any(value.endswith("prepare_native_resource_cells.py") for value in command):
                native = Path(command[command.index("--output") + 1])
                if self.first_cap_failure and native.parent.name == "candidate_00":
                    (native / "vivado_resources.tsv").write_text(
                        "partition\tclb_luts\nDESIGN\t8\nFPGA_A\t12\nFPGA_B\t4\n")
                if self.zero_area_hops is not None:
                    (native / "vivado_resources.tsv").write_text(
                        "partition\tclb_luts\nDESIGN\t0\nFPGA_A\t0\nFPGA_B\t0\nFPGA_C\t0\n")
            elif "-tclargs" in command:
                self.native_commands.append(command)
                if self.mutate_native_mapping:
                    index = command.index("-tclargs")
                    Path(command[index + 2]).write_text("changed after native snapshot\n")
            if self.zero_area_hops is not None and any(value.endswith("prepare_partition_input.py") for value in command):
                fixture.inventory.write_text("name\tref_name\na\tFDRE\nb\tFDRE\nc\tFDRE\n")

        fixture.run_boundary = boundary

    def execute(self, *flags):
        real_feedback = FRONTEND.feedback

        def feedback(*args, **kwargs):
            result = real_feedback(*args, **kwargs)
            if self.after_feedback:
                self.after_feedback(Path(args[0]))
            return result

        with patch.object(FRONTEND, "feedback", side_effect=feedback):
            return self.fixture.execute("--iterations", "2", "--native-session", "off", *flags)

    def assert_no_publication(self):
        self.assertFalse((self.fixture.output / "mapping.txt").exists())
        self.assertEqual(json.loads((self.fixture.output / "STATUS.json").read_text())["status"], "FAIL")

    def test_two_iterations_anchor_only_native_accepted_source_frontier_and_hashes(self):
        status = self.execute()
        self.assertEqual(len(self.partition_commands), 2)
        self.assertNotIn("--quality_anchor", self.partition_commands[0])
        self.assertIn("--quality_anchor", self.partition_commands[1])
        anchor, text = self.seen_anchors[0]
        tags = dict(line[1:].split("=", 1) for line in text.splitlines() if line.startswith("#"))
        first = self.fixture.output / "candidate_00"
        expected = {
            "input_sha256": FRONTEND.sha256(self.fixture.netlist),
            "transfer_growth_reference": "10", "topology_growth_reference": "20.0",
            "incumbent_mapping_sha256": FRONTEND.sha256(first / "mapping.txt"),
            "incumbent_native_completion_sha256": FRONTEND.sha256(first / "vivado_reference/native_complete.txt"),
            "checkpoint_sha256": FRONTEND.sha256(self.fixture.checkpoint),
            "engine_manifest_sha256": status["engine_manifest_sha256"],
            "topology_sha256": FRONTEND.sha256(self.fixture.output / "system.graph"),
            "timing_paths_sha256": FRONTEND.sha256(self.fixture.output / "inputs/timing_paths.tsv"),
            "hard_lut_caps_sha256": FRONTEND.sha256(self.fixture.output / "native_lut_caps.tsv"),
        }
        for key, value in expected.items():
            self.assertEqual(tags.get(key), value, key)
        self.assertEqual(text.splitlines()[-3:], ["vertex\tpath\tpartition", "1\ta\t0", "2\tb\t1"])
        self.assertEqual(status["input_snapshot_sha256"][anchor.name], FRONTEND.sha256(anchor))
        self.assertEqual(status["selected_iteration"], 1)
        self.assertEqual(status["native_feedback_quality_guard"], "accepted_mapping_anchor")
        self.assertEqual(status["native_cap_check"], "PASS")
        self.assertEqual(status["accepted_mapping_sha256"], FRONTEND.sha256(self.fixture.output / "mapping.txt"))
        self.assertEqual((self.fixture.output / "mapping.txt").read_bytes(),
                         (self.fixture.output / "candidate_01/vivado_reference/source_mapping.txt").read_bytes())

    def test_second_iteration_quality_failure_keeps_native_accepted_incumbent(self):
        self.reject_second = True
        status = self.execute()
        self.assertEqual(status["status"], "PASS")
        self.assertEqual(status["selected_iteration"], 0)
        self.assertEqual(status["candidates"][1]["status"], "REJECTED")
        self.assertIn("quality anchor", status["candidates"][1]["error"])
        self.assertEqual(len(self.native_commands), 1)
        self.assertEqual(self.fixture.final_gate_calls, 1)
        self.assertEqual((self.fixture.output / "mapping.txt").read_bytes(),
                         (self.fixture.output / "candidate_00/mapping.txt").read_bytes())

    def test_recognized_anchor_rejection_retries_remaining_seed_budget(self):
        self.reject_second = True
        status = self.execute("--iterations", "3")
        self.assertEqual(status["status"], "PASS")
        self.assertEqual(len(self.partition_commands), 3)
        self.assertEqual(status["candidates"][1]["status"], "REJECTED")
        self.assertEqual(status["selected_iteration"], 2)
        self.assertEqual(len(self.native_commands), 2)
        first_retry, second_retry = self.partition_commands[1:]
        self.assertNotEqual(first_retry[first_retry.index("--seed") + 1],
                            second_retry[second_retry.index("--seed") + 1])
        # An unsuccessful solver pass supplies no calibrated native result.
        # The next attempt must retain the last valid feedback and global guard.
        for option in ("--vertex_weight_file", "--scalar_capacities"):
            self.assertEqual(first_retry[first_retry.index(option) + 1],
                             second_retry[second_retry.index(option) + 1])
        self.assertEqual(len(self.seen_anchors), 2)
        for _, text in self.seen_anchors:
            tags = dict(line[1:].split("=", 1) for line in text.splitlines() if line.startswith("#"))
            self.assertEqual(tags["transfer_growth_reference"], "10")
            self.assertEqual(tags["topology_growth_reference"], "20.0")
            self.assertEqual(tags["incumbent_mapping_sha256"],
                             FRONTEND.sha256(self.fixture.output / "candidate_00/mapping.txt"))
        self.assertEqual((self.fixture.output / "mapping.txt").read_bytes(),
                         (self.fixture.output / "candidate_02/vivado_reference/source_mapping.txt").read_bytes())

    def test_unrecognized_runtime_failure_stops_retries_and_retains_incumbent(self):
        self.reject_second = True
        self.rejection_message = "Native partition backend crashed unexpectedly"
        status = self.execute("--iterations", "3")
        self.assertEqual(status["status"], "PASS")
        self.assertEqual(status["selected_iteration"], 0)
        self.assertEqual(len(self.partition_commands), 2)
        self.assertEqual(len(self.native_commands), 1)
        self.assertEqual(status["candidates"][1]["error"], self.rejection_message)
        self.assertEqual((self.fixture.output / "mapping.txt").read_bytes(),
                         (self.fixture.output / "candidate_00/vivado_reference/source_mapping.txt").read_bytes())

    def test_zero_area_sparse_relay_paths_consume_budget_without_invalid_feedback(self):
        for hops, expected_attempts in ((2, 3), (1, 1)):
            with self.subTest(minimum_link_hops=hops):
                self.make_fixture()
                self.zero_area_hops = hops
                self.fixture.topology.write_text(json.dumps({
                    "fpgas": [{"id": name, "clb_luts": 10, "part": "xcvp1902"} for name in ("FPGA_A", "FPGA_B", "FPGA_C")],
                    "links": [{"u": "FPGA_A", "v": "FPGA_B"}, {"u": "FPGA_B", "v": "FPGA_C"}]}))
                with patch.object(FRONTEND, "feedback", side_effect=AssertionError("Zero area cannot calibrate LUT feedback")):
                    status = self.execute("--iterations", "3")
                self.assertEqual(status["status"], "PASS")
                self.assertEqual(len(self.partition_commands), expected_attempts)
                self.assertEqual(len(self.native_commands), expected_attempts)
                self.assertFalse(list(self.fixture.output.glob("feedback_*.tsv")))
                self.assertFalse(list(self.fixture.output.glob("feedback_caps_*.txt")))
                self.assertTrue(all("--vertex_weight_file" not in command for command in self.partition_commands))
                if hops > 1:
                    self.assertTrue(status["native_feedback_skipped_zero_area"])
                    self.assertEqual(len(self.seen_anchors), 2)
                self.assertTrue(all(candidate["max_minimum_link_hops"] == hops for candidate in status["candidates"]))

    def test_cap_ineligible_or_incomplete_candidate_is_never_an_anchor(self):
        for reason in ("first_cap_failure", "first_incomplete"):
            with self.subTest(reason=reason):
                self.make_fixture()
                setattr(self, reason, True)
                status = self.execute()
                self.assertEqual(len(self.partition_commands), 2)
                self.assertNotIn("--quality_anchor", self.partition_commands[1])
                self.assertEqual(self.seen_anchors, [])
                self.assertEqual(status["selected_iteration"], 1)
                if reason == "first_cap_failure":
                    self.assertTrue(status["candidates"][0]["cap_violations"])

    def test_changed_incumbent_mapping_frontier_assignment_or_native_receipt_fails(self):
        for name in ("mapping.txt", "frontier.tsv", "final_assignment.part", "vivado_reference/native_complete.txt"):
            with self.subTest(name=name):
                self.make_fixture()
                self.after_feedback = lambda candidate, name=name: (candidate / name).write_text("changed incumbent\n")
                with self.assertRaisesRegex(ValueError, "incumbent changed"):
                    self.execute()
                self.assertEqual(len(self.partition_commands), 1)
                self.assertEqual(self.fixture.final_gate_calls, 0)
                self.assert_no_publication()

    def test_mapping_changed_during_native_reporting_cannot_be_accepted(self):
        self.mutate_native_mapping = True
        with self.assertRaisesRegex(ValueError, "mapping changed|Java result"):
            self.execute()
        self.assertEqual(self.fixture.final_gate_calls, 0)
        self.assert_no_publication()

    def test_report_copy_failure_leaves_no_accepted_mapping(self):
        real_copy = FRONTEND.shutil.copy2

        def failing_copy(source, target, *args, **kwargs):
            if Path(target) == self.fixture.output / "hardware_quality.txt":
                raise OSError("injected accepted-report copy failure")
            return real_copy(source, target, *args, **kwargs)

        with patch.object(FRONTEND.shutil, "copy2", side_effect=failing_copy):
            with self.assertRaisesRegex(OSError, "report copy failure"):
                self.execute()
        self.assertEqual(self.fixture.final_gate_calls, 1)
        self.assert_no_publication()

    def test_final_status_write_failure_leaves_no_accepted_mapping(self):
        real_write = Path.write_text
        failed = False

        def failing_write(path, data, *args, **kwargs):
            nonlocal failed
            if path == self.fixture.output / "STATUS.json" and not failed:
                payload = json.loads(data)
                if "accepted_mapping_sha256" in payload and payload["status"] != "FAIL":
                    failed = True
                    raise OSError("injected final STATUS write failure")
            return real_write(path, data, *args, **kwargs)

        with patch.object(Path, "write_text", new=failing_write):
            with self.assertRaisesRegex(OSError, "STATUS write failure"):
                self.execute()
        self.assertTrue(failed)
        self.assertEqual(self.fixture.final_gate_calls, 1)
        self.assert_no_publication()

    def test_unknown_or_insufficient_runtime_memory_selects_batch_without_loading_session(self):
        for available in (None, 1024 ** 3):
            with self.subTest(available=available):
                self.make_fixture()
                (self.fixture.repo / "tools/native_resource_session.py").write_text(
                    "raise AssertionError('Unsafe persistent session module was loaded')\n")
                (self.fixture.repo / "tools/resource_session.tcl").write_text("fixture session protocol\n")
                with patch.object(FRONTEND, "available_memory_bytes", return_value=available):
                    status = self.execute("--native-session", "auto", "--heap", "16G")
                self.assertEqual(status["native_oracle_mode"], "batch_probe_memory_guard")
                self.assertEqual(status["native_residency_decisions"], [{
                    "iteration": 0, "stage": "after_java", "available_bytes": available, "decision": "use_batch"}])
                expected_scripts = {str(self.fixture.repo / "tools" / name) for name in
                                    ("native_resource_session.py", "resource_session.tcl", "report_partition_resources.tcl")}
                self.assertEqual(set(status["native_oracle_scripts_sha256"]), expected_scripts)
                self.assertEqual(len(self.native_commands), 2)
                self.assertFalse((self.fixture.output / "native_session").exists())

    def test_runtime_memory_guard_reserves_heap_native_design_and_headroom(self):
        # A16GiB Java heap plus a1GiB EDIF's10GiB native estimate and4GiB
        # headroom requires30GiB. The inclusive boundary is deliberate.
        gib = 1024 ** 3
        self.assertFalse(FRONTEND.persistent_memory_fits("16G", gib, 30 * gib - 1))
        self.assertTrue(FRONTEND.persistent_memory_fits("16G", gib, 30 * gib))
        self.assertFalse(FRONTEND.persistent_memory_fits("16G", gib, None))
        self.assertFalse(FRONTEND.persistent_memory_fits("16GiB", gib, 100 * gib))


if __name__ == "__main__":
    unittest.main()
