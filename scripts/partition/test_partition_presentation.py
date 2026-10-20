"""Presentation is a required, selected-mapping-bound publication stage."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "presentation_availability_fixture", Path(__file__).with_name("test_partition_timing_availability.py"))
AVAILABILITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AVAILABILITY)
FRONTEND = AVAILABILITY.FRONTEND
REPORT_NAMES = ("lut_balance.png", "io_cut_balance.png", "fpga_connectivity.png",
                "report.html", "report_data.json", "report_manifest.json")


class RequiredPresentation(unittest.TestCase):
    def setUp(self):
        self.fixture = AVAILABILITY.TimingAvailabilityAcceptance(methodName="runTest")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def test_requested_rounds_continue_after_path_cleanup(self):
        state = self.fixture.execute("--iterations", "3")
        self.assertEqual(len(self.fixture.partition_commands), 3)
        self.assertEqual(state["iterations_attempted"], 3)
        self.assertEqual(state["iterations_requested"], 3)
        self.assertNotIn("early_stop_reason", state)
        self.assertEqual(state["selected_iteration"], 0)

    def test_optional_early_stop_is_explicit_and_reported(self):
        state = self.fixture.execute("--iterations", "3", "--early-stop")
        self.assertEqual(len(self.fixture.partition_commands), 1)
        self.assertIn("I/O cuts may still be improvable", state["early_stop_reason"])

    def test_capacity_first_default_and_explicit_tighter_search(self):
        self.assertEqual(FRONTEND.resolve_search_balance(.2, None, {"A": 100, "B": 100, "C": 100, "D": 100}), 3)
        self.assertEqual(FRONTEND.resolve_search_balance(.2, None, {"A": 100, "B": 50}), 2)
        self.assertEqual(FRONTEND.resolve_logical_balance(None, 4, {}), (3, "automatic_capacity_first"))
        self.assertEqual(FRONTEND.resolve_logical_balance(.3, 4, {}), (.3, "explicit_user"))
        self.assertEqual(FRONTEND.resolve_search_balance(.2, .3, {"A": 100, "B": 100}), .3)
        with self.assertRaisesRegex(ValueError, "at least"):
            FRONTEND.resolve_search_balance(.2, .1, {"A": 100})

    def test_renderer_failure_does_not_publish_accepted_mapping(self):
        def fail(*unused):
            raise RuntimeError("Rendering failed before publication")
        self.fixture.report_action = fail
        with self.assertRaisesRegex(RuntimeError, "Rendering failed"):
            self.fixture.execute()
        self.assertFalse((self.fixture.output / "mapping.txt").exists())
        self.assertEqual(json.loads((self.fixture.output / "STATUS.json").read_text())["status"], "FAIL")

    def test_missing_renderer_fails_before_native_export(self):
        self.fixture.real_reporting = True
        with patch.object(FRONTEND, "prepare_reporting", side_effect=RuntimeError("Install rendering requirements")):
            with self.assertRaisesRegex(RuntimeError, "rendering requirements"):
                self.fixture.execute()
        self.assertEqual(self.fixture.export_commands, [])
        self.assertEqual(self.fixture.partition_commands, [])
        self.assertFalse((self.fixture.output / "mapping.txt").exists())

    def test_missing_runtime_dependency_fails_before_native_export(self):
        with patch.object(FRONTEND, "start_runtime_monitor", side_effect=RuntimeError("Runtime statistics need psutil")):
            with self.assertRaisesRegex(RuntimeError, "psutil"):
                self.fixture.execute()
        self.assertEqual(self.fixture.export_commands, [])
        self.assertEqual(self.fixture.partition_commands, [])
        self.assertFalse((self.fixture.output / "mapping.txt").exists())

    def test_final_runtime_refresh_failure_cannot_publish_a_pass(self):
        self.fixture.real_reporting = True
        def completed_reports(output, *unused):
            # Supply a completed presentation boundary; the independent real
            # renderer test below checks real PNGs and measured source tables.
            for name in REPORT_NAMES:
                (output / name).write_text(json.dumps({"source_files_sha256": {}})
                                           if name == "report_manifest.json" else "published report fixture")
            return {output / name: FRONTEND.sha256(output / name) for name in REPORT_NAMES}
        with patch.object(FRONTEND, "publish_partition_reports", completed_reports), \
             patch.object(FRONTEND, "finalize_runtime_report", side_effect=OSError("Final runtime refresh failed")):
            with self.assertRaisesRegex(OSError, "runtime refresh"):
                self.fixture.execute()
        self.assertEqual(self.fixture.final_gate_calls, 1)
        for name in ("mapping.txt", *REPORT_NAMES):
            self.assertFalse((self.fixture.output / name).exists(), name)
        status = json.loads((self.fixture.output / "STATUS.json").read_text())
        self.assertEqual(status["status"], "FAIL")

    def test_late_report_mapping_mutation_is_rejected(self):
        def corrupt(output, state, mapping, renderer, font):
            (output / "report.html").write_text("PASS-looking report must be removed")
            mapping.write_text("top FPGA_OTHER\n")
        self.fixture.report_action = corrupt
        with self.assertRaisesRegex(ValueError, "Staged mapping changed"):
            self.fixture.execute()
        self.assertFalse((self.fixture.output / "mapping.txt").exists())
        self.assertFalse((self.fixture.output / "report.html").exists())
        self.assertFalse((self.fixture.output / "report_manifest.json").exists())

    def test_status_write_failure_removes_mapping_and_pass_report(self):
        original = Path.write_text
        def fail_pass_status(path, data, *args, **kwargs):
            if path == self.fixture.output / "STATUS.json" and json.loads(data).get("status") == "PASS":
                raise OSError("Simulated final status write failure")
            return original(path, data, *args, **kwargs)
        def report(output, *unused):
            (output / "report.html").write_text("PASS-looking report must be removed")
        self.fixture.report_action = report
        with patch.object(Path, "write_text", fail_pass_status):
            with self.assertRaisesRegex(OSError, "final status write"):
                self.fixture.execute()
        self.assertFalse((self.fixture.output / "mapping.txt").exists())
        self.assertFalse((self.fixture.output / "report.html").exists())
        self.assertEqual(json.loads((self.fixture.output / "STATUS.json").read_text())["status"], "FAIL")

    def test_incomplete_report_manifest_cannot_publish(self):
        state = {"accepted_mapping_sha256": "a" * 64}
        renderer = SimpleNamespace(REPORT_FILES=REPORT_NAMES, generate_report=lambda *args, **kwargs:
                                   {"mapping_sha256": "a" * 64, "files_sha256": {}})
        with self.assertRaisesRegex(ValueError, "missing required"):
            FRONTEND.publish_partition_reports(self.fixture.root, state, self.fixture.source, renderer, None)
        self.assertFalse((self.fixture.root / "mapping.txt").exists())

    def test_real_renderer_and_frontend_publish_consistent_selected_artifacts(self):
        self.fixture.real_reporting = True
        boundary = self.fixture.run_boundary
        def measurements(command, cwd, log):
            boundary(command, cwd, log)
            values = [str(value) for value in command]
            if FRONTEND.MAIN + "Partitioner" in values:
                candidate = Path(values[values.index("--part_dir") + 1])
                (candidate / "fpga_control_categories.tsv").write_text(
                    "CANONICAL_NET\tCATEGORY\tDIRECTED_PARTITION_PAIRS\tENDPOINT_FINGERPRINT\n"
                    "data[0]\tdata_or_functional_sync_control\tFPGA_A->FPGA_B\tx\n")
            elif FRONTEND.MAIN + "VivadoResourceOracle" in values:
                index = values.index(FRONTEND.MAIN + "VivadoResourceOracle")
                table, output = Path(values[index + 1]), Path(values[index + 3])
                (output / "vivado_resources.tsv").write_bytes(table.read_bytes())
        self.fixture.run_boundary = measurements
        state = self.fixture.execute("--iterations", "2")
        output = self.fixture.output
        self.assertTrue((output / "mapping.txt").is_file())
        self.assertEqual(set(state["presentation_report_sha256"]), set(REPORT_NAMES))
        for name, digest in state["presentation_report_sha256"].items():
            self.assertEqual(FRONTEND.sha256(output / name), digest)
        data = json.loads((output / "report_data.json").read_text(encoding="utf-8"))
        self.assertEqual(data["mapping_sha256"], FRONTEND.sha256(output / "mapping.txt"))
        self.assertEqual(data["selected_iteration"], state["selected_iteration"])
        self.assertEqual(data["runtime_statistics"], state["runtime_statistics"])
        self.assertEqual(data["runtime_statistics"]["measured_through"], "before_mapping_publication")
        self.assertGreater(data["runtime_statistics"]["wall_clock_seconds"], 0)
        self.assertGreater(data["runtime_statistics"]["peak_sampled_process_tree_rss_bytes"], 0)
        self.assertEqual(data["resources"]["FPGA_A"]["clb_luts"], 4)
        self.assertEqual(data["io_cuts"]["directed_matrices"]["data_or_functional_sync_control"]["FPGA_A"]["FPGA_B"], 1)
        self.assertEqual(len(list(output.glob("*.png"))), 3)
        self.assertFalse((output / "report.pending").exists())


if __name__ == "__main__":
    unittest.main()
