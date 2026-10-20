"""Report semantics, publication identity, readable plots and portable assets."""
import importlib.util
import gzip
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("partition_reporting_under_test", Path(__file__).with_name("reporting.py"))
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)
WORK = Path(__file__).resolve().parents[2] / "build/partition-python-tests/reporting"

try:
    from matplotlib import font_manager
    from PIL import Image
    TEST_FONT = Path(font_manager.findfont("DejaVu Sans"))
    DEPENDENCIES = True
except ImportError:
    DEPENDENCIES = False
    TEST_FONT = None


class ReportInputs(unittest.TestCase):
    def setUp(self):
        WORK.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=WORK)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.metrics = self.root / "metrics"
        self.metrics.mkdir()
        self.output = self.root / "report"
        self.mapping = self.metrics / "mapping.txt"
        self.labels = ["FPGA_A", "FPGA_B", "FPGA_C"]
        self.mapping.write_text("top/b FPGA_B\ntop/c FPGA_C\ntop FPGA_A\n")
        self.native = self.metrics / "vivado_resources.tsv"
        self.native.write_text("partition\tclb_luts\tregisters\tblock_ram_tiles\n"
                               "DESIGN\t600\t12\t1.5\nFPGA_A\t100\t4\t0.5\n"
                               "FPGA_B\t200\t4\t0.5\nFPGA_C\t300\t4\t0.5\n")
        self.categories = self.metrics / "fpga_control_categories.tsv"
        self.categories.write_text("#control_scope=fixture\nCANONICAL_NET\tCATEGORY\tDIRECTED_PARTITION_PAIRS\tENDPOINT_FINGERPRINT\n"
            "multicast[0]\tdata_or_functional_sync_control\tFPGA_A->FPGA_B,FPGA_A->FPGA_C\tfixture\n"
            "reply[0]\tdata_or_functional_sync_control\tFPGA_B->FPGA_A\tfixture\n"
            "clk\tclock\tFPGA_A->FPGA_B,FPGA_A->FPGA_C\tfixture\n"
            "reset\treset_only\tFPGA_A->FPGA_B\tfixture\n")
        self.quality = self.metrics / "hardware_quality.txt"
        self.quality.write_text("after_max_register_free_crossings=3\nafter_endpoint_excess_crossings=12\n"
                               "after_transit_primitives=5\nafter_transfers=8\ntiming_paths_scored=10\n"
                               "timing_cost_after=0.5\ntiming_sample_coverage=sampled setup paths only\n")
        self.state = {"status": "PASS", "accepted_mapping_sha256": REPORT._sha(self.mapping),
                      "selected_iteration": 0, "hard_native_clb_lut_caps": {label: 500 for label in self.labels},
                      "candidates": [{"iteration": 0, "max_crossings": 3, "endpoint_excess": 12,
                                      "transit_primitives": 5, "transfers": 8, "target_imbalance": 0.5},
                                     {"iteration": 1, "max_crossings": 4, "endpoint_excess": 15,
                                      "transit_primitives": 7, "transfers": 9, "target_imbalance": 0.2}]}

    def generate(self):
        return REPORT.generate_report(self.output, self.state, self.mapping, TEST_FONT, self.metrics)

    def historical_fixture(self):
        successor = self.root / "successor"
        successor.mkdir()
        (successor / "mapping.txt").write_text("top/c FPGA_C\ntop/b FPGA_B\ntop FPGA_A\n")
        (successor / "mapping_records.tsv").write_text("fixture ordered records\n")
        with gzip.open(successor / "mapping_ownership.tsv.gz", "wt") as handle:
            handle.write("fixture exact ownership\n")
        digest = REPORT._sha(successor / "mapping.txt")
        old_digest = REPORT._sha(self.mapping)
        source = "e" * 64
        self.state["checkpoint_sha256"] = "c" * 64
        (self.metrics / "STATUS.json").write_text(json.dumps(self.state))
        (self.metrics / "run_provenance.json").write_text(json.dumps({"input_sha256": source, "mapping_sha256": old_digest}))
        (self.metrics / "source_mapping.txt").write_bytes(self.mapping.read_bytes())
        (self.metrics / "final_assignment.part").write_text("0\n1\n2\n")
        contract = {"status": "PASS", "mapping_sha256": digest, "input_sha256": source, "functional_leaf_count": 3,
                    "records_manifest_sha256": REPORT._sha(successor / "mapping_records.tsv"),
                    "ownership_manifest_sha256": REPORT._sha(successor / "mapping_ownership.tsv.gz")}
        for key in ("descendants_before_ancestors", "residual_root_explicit", "cells_agreement", "source_paths_exact",
                    "nearest_ancestor_redundancy_absent", "frontier_ownership_compared"):
            contract[key] = True
        (successor / "mapping_contract.json").write_text(json.dumps(contract))
        equivalent = {"status": "PASS", "mapping_sha256": digest, "legacy_mapping_sha256": old_digest,
                      "ownership_mismatches": 0, "functional_leaves_compared": 3,
                      "top_port_owner_unchanged": True, "input_sha256": source,
                      "final_assignment_sha256": REPORT._sha(self.metrics / "final_assignment.part")}
        (successor / "reencoding_equivalence.json").write_text(json.dumps(equivalent))
        prior = {"status": "HISTORICAL_EVIDENCE_REFERENCED_BY_OWNERSHIP_EQUIVALENCE",
                 "historical_mapping_sha256": old_digest, "successor_mapping_sha256": digest,
                 "equivalence_sha256": REPORT._sha(successor / "reencoding_equivalence.json"),
                 "source_edif_sha256": source, "source_checkpoint_sha256": "c" * 64,
                 "native_luts": {"FPGA_A": 100, "FPGA_B": 200, "FPGA_C": 300},
                 "historical_artifact_sha256": {name: REPORT._sha(self.metrics / name) for name in
                     ("mapping.txt", "source_mapping.txt", "vivado_resources.tsv", "fpga_control_categories.tsv",
                      "STATUS.json", "run_provenance.json", "hardware_quality.txt", "final_assignment.part")}}
        (successor / "prior_native_evidence.json").write_text(json.dumps(prior))
        return successor, digest

    def test_multicast_transmitter_deduplicated_and_controls_separate(self):
        cuts = REPORT.read_cut_categories(self.categories, self.labels)
        data = cuts["by_category"]["data_or_functional_sync_control"]
        self.assertEqual(data["FPGA_A"], {"incoming": 1, "outgoing": 1, "total": 2})
        self.assertEqual(data["FPGA_B"], {"incoming": 1, "outgoing": 1, "total": 2})
        self.assertEqual(data["FPGA_C"], {"incoming": 1, "outgoing": 0, "total": 1})
        self.assertEqual(cuts["unique_crossing_nets"]["data_or_functional_sync_control"], 2)
        self.assertEqual(cuts["directed_net_destinations"]["data_or_functional_sync_control"], 3)
        matrix = cuts["directed_matrices"]["data_or_functional_sync_control"]
        self.assertEqual(matrix["FPGA_A"]["FPGA_B"], 1)
        self.assertEqual(matrix["FPGA_A"]["FPGA_C"], 1)
        self.assertEqual(matrix["FPGA_B"]["FPGA_C"], 0)
        self.assertEqual(cuts["by_category"]["clock"]["FPGA_A"]["outgoing"], 1)

    def test_zero_mean_and_signed_deviation(self):
        self.assertEqual(REPORT.deviations([0, 0, 0]), (0, [0, 0, 0]))
        self.assertEqual(REPORT.deviations([100, 200, 300]), (200, [-50, 0, 50]))

    def test_malformed_category_rejected(self):
        for bad in ("FPGA_A->FPGA_A", "FPGA_A->FPGA_MISSING", "FPGA_A", "", "FPGA_A->FPGA_B,FPGA_A->FPGA_B"):
            with self.subTest(pair=bad):
                self.categories.write_text("CANONICAL_NET\tCATEGORY\tDIRECTED_PARTITION_PAIRS\nn\tclock\t" + bad + "\n")
                with self.assertRaises(ValueError):
                    REPORT.read_cut_categories(self.categories, self.labels)

    def test_duplicate_canonical_net_rejected(self):
        with self.categories.open("a") as handle:
            handle.write("reply[0]\tclock\tFPGA_A->FPGA_C\tfixture\n")
        with self.assertRaisesRegex(ValueError, "duplicate canonical"):
            REPORT.read_cut_categories(self.categories, self.labels)

    def test_unknown_category_rejected_instead_of_false_data_zero(self):
        self.categories.write_text("CANONICAL_NET\tCATEGORY\tDIRECTED_PARTITION_PAIRS\nn\tunknown\tFPGA_A->FPGA_B\n")
        with self.assertRaisesRegex(ValueError, "Unknown cut category"):
            REPORT.read_cut_categories(self.categories, self.labels)

    def test_lut_summary_must_agree_with_native_resources(self):
        path = self.metrics / "lut_report.txt"
        resources = REPORT._native_resources(self.native, set(self.labels))
        path.write_text("100LUTS FPGA_A\n200LUTS FPGA_B\n300LUTS FPGA_C\n200LUTS mean\n")
        REPORT._check_lut_report(path, resources)
        path.write_text("101LUTS FPGA_A\n200LUTS FPGA_B\n300LUTS FPGA_C\n200LUTS mean\n")
        with self.assertRaisesRegex(ValueError, "differs"):
            REPORT._check_lut_report(path, resources)

    def test_native_counts_must_be_exact_integers_and_matching_labels(self):
        for value in ("1.5", "-1", "nan", "inf", "True"):
            self.native.write_text("partition\tclb_luts\nFPGA_A\t" + value + "\nFPGA_B\t200\nFPGA_C\t300\n")
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "integer"):
                REPORT._native_resources(self.native, set(self.labels))
        self.native.write_text("partition\tclb_luts\nFPGA_A\t1\nFPGA_A\t2\nFPGA_C\t3\n")
        with self.assertRaisesRegex(ValueError, "labels differ"):
            REPORT._native_resources(self.native, set(self.labels))

    def test_native_7series_unsupported_uram_is_null_while_luts_remain_exact(self):
        self.native.write_text("partition\tlogical_lut_primitives\tclb_luts\tlut_as_logic\tlut_as_memory\tregisters\tflip_flops\tlatches\tblock_ram_tiles\tdsps\turam\tprimitive_leaves\n"
                               "DESIGN\t304\t304\t304\t0\t256\t256\t0\t0\t0\tNA\t656\n"
                               "FPGA_A\t165\t165\t165\t0\t128\t128\t0\t0\t0\tNA\t341\n"
                               "FPGA_B\t1\t1\t1\t0\t0\t0\t0\t0\t0\tNA\t1\n"
                               "FPGA_C\t1\t1\t1\t0\t0\t0\t0\t0\t0\tNA\t1\n"
                               "FPGA_D\t137\t137\t137\t0\t128\t128\t0\t0\t0\tNA\t313\n")
        resources = REPORT._native_resources(self.native, {"FPGA_A", "FPGA_B", "FPGA_C", "FPGA_D"})
        self.assertEqual(sum(row["clb_luts"] for row in resources.values()), 304)
        self.assertTrue(all(row["uram"] is None for row in resources.values()))
        self.assertEqual(resources["FPGA_A"]["registers"], 128)
        self.assertEqual(resources["FPGA_A"]["block_ram_tiles"], 0)
        # The same explicit sentinel is accepted for every optional column,
        # while malformed text and the required LUT count still fail closed.
        optional = ["logical_lut_primitives", "lut_as_logic", "lut_as_memory", "registers",
                    "flip_flops", "latches", "block_ram_tiles", "dsps", "uram", "primitive_leaves"]
        self.native.write_text("partition\tclb_luts\t" + "\t".join(optional) + "\nFPGA_A\t7\t" + "\t".join(["NA"] * len(optional)) + "\n")
        row = REPORT._native_resources(self.native, {"FPGA_A"})["FPGA_A"]
        self.assertEqual(row["clb_luts"], 7)
        self.assertTrue(all(row[key] is None for key in optional))
        self.assertIn('"uram": null', json.dumps(row))
        self.native.write_text("partition\tclb_luts\turam\nFPGA_A\tNA\tNA\n")
        with self.assertRaisesRegex(ValueError, "integer"):
            REPORT._native_resources(self.native, {"FPGA_A"})
        self.native.write_text("partition\tclb_luts\turam\nFPGA_A\t7\tnan\n")
        with self.assertRaisesRegex(ValueError, "integer"):
            REPORT._native_resources(self.native, {"FPGA_A"})

    def test_nonfinite_optional_timing_is_rejected(self):
        self.quality.write_text("timing_cost_after=nan\n")
        with self.assertRaisesRegex(ValueError, "finite"):
            REPORT._quality(self.quality)

    def test_metadata_html_is_escaped_and_missing_metrics_are_unavailable(self):
        data = {"resources": {label: {"clb_luts": 0} for label in self.labels},
                "capacities": {label: {"physical_lut_capacity": None, "hard_lut_cap": None} for label in self.labels},
                "io_cuts": REPORT.read_cut_categories(self.categories, self.labels), "quality": {},
                "fpga_labels": self.labels, "selected_iteration": 0, "status": '<script>alert("x")</script>',
                "candidates": [], "mapping_sha256": "a" * 64, "source_files_sha256": {},
                "run_settings": {"filename": "<img src=x onerror=alert(1)>"}}
        rendered = REPORT._html(data)
        self.assertNotIn("<script>", rendered)
        self.assertNotIn("<img src=x", rendered)
        self.assertIn("&lt;script&gt;", rendered)
        self.assertIn("Unavailable", rendered)
        self.assertIn("Worst FPGA boundary crossings without a register", rendered)
        self.assertIn("Combinational primitives on transit paths", rendered)
        self.assertNotIn("<th>H</th>", rendered)

    def test_runtime_statistics_preserve_missing_values_and_reject_invalid_numbers(self):
        self.assertEqual(REPORT._runtime_statistics(None), {})
        self.assertEqual(REPORT._runtime_statistics({"frontend_peak_rss_bytes": None}), {"frontend_peak_rss_bytes": None})
        for value in (float("nan"), float("inf"), -1, True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                REPORT._runtime_statistics({"wall_clock_seconds": value})
        for value in (-1, 1.5, True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                REPORT._runtime_statistics({"peak_sampled_process_tree_rss_bytes": value})

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_statistics_first_html_runtime_and_rejected_iteration_reason(self):
        self.state.update(iterations_requested=3, iterations_attempted=3,
                          runtime_statistics={"wall_clock_seconds": 120.5, "cpu_time_seconds": 360.25,
                              "average_cpu_cores": 2.99, "average_cpu_percent_one_core": 299.0,
                              "peak_sampled_process_tree_rss_bytes": 2 * 1024 ** 3,
                              "frontend_peak_rss_bytes": None, "child_processes_observed": 7,
                              "sample_interval_seconds": 0.25, "measured_through": "before_report_generation",
                              "scope": "Observed frontend and child process samples"})
        self.state["candidates"].append({"iteration": 2, "status": "REJECTED",
                                         "error": "Native guard <script>alert(1)</script> " + "x" * 1000})
        self.generate()
        rendered = (self.output / "report.html").read_text(encoding="utf-8")
        data = json.loads((self.output / "report_data.json").read_text(encoding="utf-8"))
        self.assertEqual(data["runtime_statistics"]["child_processes_observed"], 7)
        self.assertIsNone(data["runtime_statistics"]["frontend_peak_rss_bytes"])
        self.assertIn("120.50 s", rendered)
        self.assertIn("Observed CPU time", rendered)
        self.assertIn("299.00%", rendered)
        self.assertIn("2.00 GiB", rendered)
        self.assertIn("Rejected: Native guard &lt;script&gt;", rendered)
        self.assertNotIn("<script>", rendered)
        self.assertNotIn("x" * 401, rendered)
        self.assertLess(rendered.index("Iterations requested"), rendered.index("<h2>Runtime"))
        self.assertIn("<details><summary>Metric definitions and counting rules", rendered)
        self.assertIn("<details><summary>Source identities, settings and validation scope", rendered)

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_runtime_refresh_updates_only_metadata_and_keeps_mapping_and_pngs(self):
        self.generate()
        png_hashes = {name: REPORT._sha(self.output / name) for name in REPORT.REPORT_FILES[:3]}
        self.assertFalse((self.output / "mapping.txt").exists())
        mapping_bytes = self.mapping.read_bytes()
        manifest = REPORT.refresh_runtime(self.output, {"wall_clock_seconds": 12.5, "cpu_time_seconds": 23.0,
            "average_cpu_cores": 1.84, "measured_through": "partitioning_and_initial_report_generation",
            "scope": "Partitioning and initial report generation; final publication excluded"})
        self.assertEqual(mapping_bytes, self.mapping.read_bytes())
        self.assertEqual(png_hashes, {name: REPORT._sha(self.output / name) for name in png_hashes})
        data = json.loads((self.output / "report_data.json").read_text(encoding="utf-8"))
        self.assertEqual(data["runtime_statistics"]["wall_clock_seconds"], 12.5)
        self.assertIn("12.50 s", (self.output / "report.html").read_text(encoding="utf-8"))
        self.assertIn("Partitioning and initial report generation", (self.output / "report.html").read_text(encoding="utf-8"))
        for name, digest in manifest["files_sha256"].items():
            self.assertEqual(REPORT._sha(self.output / name), digest)
        self.assertEqual(set(path.name for path in self.output.iterdir()), set(REPORT.REPORT_FILES))

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_runtime_refresh_rejects_changed_reports_and_rolls_back_replace_failure(self):
        self.generate()
        original = {name: (self.output / name).read_bytes() for name in REPORT.REPORT_FILES}
        actual_replace = REPORT.os.replace
        calls = []
        def fail_second(source, target):
            calls.append(str(target))
            if len(calls) == 2:
                raise OSError("controlled metadata replacement failure")
            return actual_replace(source, target)
        with patch.object(REPORT.os, "replace", side_effect=fail_second):
            with self.assertRaisesRegex(OSError, "controlled metadata"):
                REPORT.refresh_runtime(self.output, {"wall_clock_seconds": 1.5})
        self.assertEqual(original, {name: (self.output / name).read_bytes() for name in REPORT.REPORT_FILES})
        (self.output / "report.html").write_text("changed")
        with self.assertRaisesRegex(ValueError, "changed before runtime refresh"):
            REPORT.refresh_runtime(self.output, {"wall_clock_seconds": 1.5})

    def test_identity_mismatch_and_native_snapshot_rejected(self):
        self.state["accepted_mapping_sha256"] = "a" * 64
        with self.assertRaisesRegex(ValueError, "accepted mapping"):
            REPORT._verify_identity(self.state, self.metrics, self.mapping, REPORT._sha(self.mapping))
        self.state["accepted_mapping_sha256"] = REPORT._sha(self.mapping)
        (self.metrics / "source_mapping.txt").write_text("different\n")
        with self.assertRaisesRegex(ValueError, "native resource"):
            REPORT._verify_identity(self.state, self.metrics, self.mapping, REPORT._sha(self.mapping))

    def test_explicit_historical_mode_requires_all_unchanged_evidence(self):
        successor, digest = self.historical_fixture()
        evidence, state, provenance = REPORT._historical_evidence(successor, self.metrics, successor / "mapping.txt", digest)
        self.assertEqual(evidence["status"], REPORT.HISTORICAL_STATUS)
        self.assertFalse(evidence["fresh_native_measurement"])
        self.assertNotEqual(evidence["historical_mapping_sha256"], evidence["successor_mapping_sha256"])
        self.categories.write_text(self.categories.read_text() + "tampered\n")
        with self.assertRaisesRegex(ValueError, "Historical artifact changed"):
            REPORT._historical_evidence(successor, self.metrics, successor / "mapping.txt", digest)

    def test_historical_mode_rejects_changed_top_port_owner(self):
        successor, digest = self.historical_fixture()
        path = successor / "reencoding_equivalence.json"
        equivalent = json.loads(path.read_text())
        equivalent["top_port_owner_unchanged"] = False
        path.write_text(json.dumps(equivalent))
        prior_path = successor / "prior_native_evidence.json"
        prior = json.loads(prior_path.read_text())
        prior["equivalence_sha256"] = REPORT._sha(path)
        prior_path.write_text(json.dumps(prior))
        with self.assertRaisesRegex(ValueError, "root-owner equivalence"):
            REPORT._historical_evidence(successor, self.metrics, successor / "mapping.txt", digest)

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_historical_report_keeps_native_identity_and_conspicuous_scope(self):
        successor, digest = self.historical_fixture()
        with self.assertRaisesRegex(ValueError, "accepted mapping"):
            REPORT.generate_report(self.output, self.state, successor / "mapping.txt", TEST_FONT, self.metrics)
        REPORT.generate_historical_report(successor, self.metrics, TEST_FONT)
        data = json.loads((successor / "report_data.json").read_text(encoding="utf-8"))
        self.assertEqual(data["mapping_sha256"], digest)
        self.assertNotEqual(data["run_provenance"]["mapping_sha256"], digest)
        self.assertEqual(data["status"], REPORT.HISTORICAL_STATUS)
        self.assertIn("not a fresh Vivado measurement", (successor / "report.html").read_text(encoding="utf-8"))

    def test_selected_report_and_selected_candidate_hashes_verified(self):
        self.state["selected_report_sha256"] = {"hardware_quality.txt": "a" * 64}
        with self.assertRaisesRegex(ValueError, "Selected report hash"):
            REPORT._verify_identity(self.state, self.metrics, self.mapping, REPORT._sha(self.mapping))
        self.state.pop("selected_report_sha256")
        self.state["candidates"][0]["mapping_sha256"] = "a" * 64
        with self.assertRaisesRegex(ValueError, "Selected iteration"):
            REPORT._verify_identity(self.state, self.metrics, self.mapping, REPORT._sha(self.mapping))

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_preflight_records_real_font_and_rejects_invalid_font_bytes(self):
        checked = REPORT.preflight(TEST_FONT)
        self.assertEqual(checked["font_sha256"], REPORT._sha(TEST_FONT))
        self.assertTrue(checked["font_family"])
        path = self.root / "invalid.ttf"
        path.write_text("This is not a TrueType font")
        with self.assertRaisesRegex(ValueError, "cannot be loaded"):
            REPORT.preflight(path)

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_none_optional_history_metrics_allowed_but_nan_history_rejected(self):
        self.state["candidates"][0]["modeled_worst_slack_ns"] = None
        self.generate()
        data = json.loads((self.output / "report_data.json").read_text(encoding="utf-8"))
        self.assertIsNone(data["candidates"][0]["modeled_worst_slack_ns"])
        self.output = self.root / "nan-report"
        self.state["candidates"][1]["timing_cost"] = float("nan")
        with self.assertRaisesRegex(ValueError, "Out of range float"):
            self.generate()
        self.assertFalse(any(self.output.iterdir()))

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_topology_labels_and_plain_english_manifest_details(self):
        path = self.metrics / "topology_manifest.json"
        topology = {"board": "J4 <fixture>", "fpga_count": 3, "connected_pairs": 2,
                    "fpgas": [{"partition": label, "path": "slot/" + label, "selected_degree": 1}
                              for label in self.labels],
                    "edges": [{"partition_a": "FPGA_A", "partition_b": "FPGA_B", "lanes": 16,
                               "wires_at_min_compression": 1024}]}
        path.write_text(json.dumps(topology))
        self.generate()
        rendered = (self.output / "report.html").read_text(encoding="utf-8")
        self.assertIn("J4 &lt;fixture&gt;", rendered)
        self.assertIn("Before local cleanup", rendered)
        self.assertIn("slot/FPGA_A", rendered)
        self.output = self.root / "wrong-topology"
        topology["fpgas"][0]["partition"] = "FPGA_WRONG"
        path.write_text(json.dumps(topology))
        with self.assertRaisesRegex(ValueError, "topology manifest FPGA labels"):
            self.generate()

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_three_exact_palette_pngs_and_selected_best_html(self):
        manifest = self.generate()
        self.assertEqual(set(path.name for path in self.output.iterdir()), set(REPORT.REPORT_FILES))
        allowed = {tuple(int(value[i:i + 2], 16) for i in (1, 3, 5)) for value in REPORT.PALETTE}
        for name in REPORT.REPORT_FILES[:3]:
            with Image.open(self.output / name) as image:
                colors = {rgb for count, rgb in image.convert("RGB").getcolors(maxcolors=10000)}
                self.assertLessEqual(colors, allowed)
                self.assertEqual(len(colors), 3)
                self.assertGreaterEqual(image.width, 1600)
        data = json.loads((self.output / "report_data.json").read_text(encoding="utf-8"))
        self.assertEqual(data["font"]["sha256"], REPORT._sha(TEST_FONT))
        self.assertEqual(data["resources"]["FPGA_C"]["clb_luts"], 300)
        self.assertEqual(data["lut_deviation_percent"], {"FPGA_A": -50, "FPGA_B": 0, "FPGA_C": 50})
        self.assertEqual(data["selected_iteration"], 0)
        self.assertEqual(data["io_cuts"]["by_category"]["data_or_functional_sync_control"]["FPGA_A"]["total"], 2)
        self.assertEqual(manifest["mapping_sha256"], REPORT._sha(self.mapping))
        for name, digest in manifest["files_sha256"].items():
            self.assertEqual(REPORT._sha(self.output / name), digest)
        rendered = (self.output / "report.html").read_text(encoding="utf-8")
        self.assertIn("Selected best accepted mapping", rendered)
        self.assertIn("Not selected", rendered)

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_no_cut_categories_cannot_mislabel_directed_counts_as_unique_io(self):
        self.categories.unlink()
        (self.metrics / "fpga_io_cuts.txt").write_text("FPGA_A->FPGA_B: 42 signals\n")
        with self.assertRaises(FileNotFoundError):
            self.generate()
        self.assertFalse(any(self.output.glob("*.png")))

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_hard_cap_is_enforced_in_reporting_too(self):
        self.state["hard_native_clb_lut_caps"]["FPGA_C"] = 299
        with self.assertRaisesRegex(ValueError, "hard native LUT cap"):
            self.generate()
        self.assertFalse((self.output / "report.html").exists())

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_render_failure_cleans_partial_artifacts_and_does_not_touch_mapping(self):
        original = self.mapping.read_bytes()
        def fail(*args, **kwargs):
            (self.output / "lut_balance.png").write_bytes(b"partial")
            raise RuntimeError("controlled render failure")
        with patch.object(REPORT, "_balance_chart", side_effect=fail):
            with self.assertRaisesRegex(RuntimeError, "controlled render"):
                self.generate()
        self.assertEqual(self.mapping.read_bytes(), original)
        self.assertFalse(any(self.output.iterdir()))

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_mapping_change_during_render_cannot_leave_a_published_report(self):
        actual = REPORT._balance_chart
        def mutate(*args, **kwargs):
            actual(*args, **kwargs)
            self.mapping.write_text("top FPGA_A\n")
        with patch.object(REPORT, "_balance_chart", side_effect=mutate):
            with self.assertRaisesRegex(ValueError, "changed during rendering"):
                self.generate()
        self.assertFalse(any(self.output.iterdir()))

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_large_matrix_preserves_all_24_and_28_fpga_labels(self):
        for count in (24, 28):
            with self.subTest(count=count):
                labels = ["FPGA_" + str(index + 1) for index in range(count)]
                matrix = {source: {sink: int(source != sink) for sink in labels} for source in labels}
                path = self.root / (str(count) + ".png")
                captured = []
                save = REPORT._save_palette
                def capture(fig, *args):
                    axes = fig.axes[0]
                    captured.append([tick.get_text() for tick in axes.get_xticklabels()])
                    self.assertEqual(len(axes.texts), count * count)
                    return save(fig, *args)
                with patch.object(REPORT, "_save_palette", side_effect=capture):
                    REPORT._connectivity_chart(path, labels, matrix, TEST_FONT)
                self.assertEqual(captured[0], labels)
                with Image.open(path) as image:
                    self.assertEqual(len(image.convert("RGB").getcolors(maxcolors=10000)), 3)

    @unittest.skipUnless(DEPENDENCIES, "Install the report rendering requirements")
    def test_single_fpga_zero_area_and_zero_cuts_still_has_all_figures(self):
        self.mapping.write_text("top FPGA_A\n")
        self.native.write_text("partition\tclb_luts\nDESIGN\t0\nFPGA_A\t0\n")
        self.categories.write_text("CANONICAL_NET\tCATEGORY\tDIRECTED_PARTITION_PAIRS\n")
        self.state = {"status": "PASS", "accepted_mapping_sha256": REPORT._sha(self.mapping)}
        self.generate()
        data = json.loads((self.output / "report_data.json").read_text(encoding="utf-8"))
        self.assertEqual(data["lut_mean"], 0)
        self.assertEqual(data["io_mean"], 0)
        self.assertEqual(data["lut_deviation_percent"], {"FPGA_A": 0})


if __name__ == "__main__":
    unittest.main()
