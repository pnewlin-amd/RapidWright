"""Logical default policy through real frontend control and fake tool commands.

The boundaries supply tiny completed source/native reports. These tests assert
constraint propagation and publication identity, never synthetic hardware quality.
"""
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "logical_balance_availability_fixture", Path(__file__).with_name("test_partition_timing_availability.py"))
AVAILABILITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AVAILABILITY)
FRONTEND = AVAILABILITY.FRONTEND


class LogicalBalanceAcceptance(unittest.TestCase):
    def setUp(self):
        self.fixture = AVAILABILITY.TimingAvailabilityAcceptance(methodName="runTest")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.swap_report = False
        self.force_feedback = False
        original = self.fixture.run_boundary

        def boundary(command, cwd, log):
            values = [str(value) for value in command]
            original(command, cwd, log)
            if self.force_feedback and FRONTEND.MAIN + "Partitioner" in values:
                candidate = Path(values[values.index("--part_dir") + 1])
                quality = candidate / "hardware_quality.txt"
                quality.write_text(quality.read_text().replace("after_max_register_free_crossings=0",
                                                              "after_max_register_free_crossings=2"))
            if self.swap_report and FRONTEND.MAIN + "Partitioner" in values:
                candidate = Path(values[values.index("--part_dir") + 1])
                (candidate / "swap_refinement_passes.tsv").write_text(
                    "pass\tswaps\twithin_guards\tretained\n0\t1\ttrue\ttrue\n")

        self.fixture.run_boundary = boundary

    def set_topology_caps(self, a=None, b=None):
        devices = [{"id": "FPGA_A", "part": "xcvp1902"}, {"id": "FPGA_B", "part": "xcvp1902"}]
        if a is not None:
            devices[0]["clb_luts"], devices[1]["clb_luts"] = a, b
        self.fixture.topology.write_text(json.dumps({"fpgas": devices,
            "links": [{"u": "FPGA_A", "v": "FPGA_B"}]}))

    def execute(self, *flags):
        return self.fixture.execute("--timing", "off", "--native-session", "off", *flags)

    def assert_policy(self, state, value, policy):
        self.assertEqual(state["effective_logical_balance"], value)
        self.assertEqual(state["logical_balance_policy"], policy)
        self.assertTrue((self.fixture.output / "mapping.txt").exists())
        for command in self.fixture.partition_commands:
            self.assertEqual(float(command[command.index("--logical_epsilon") + 1]), value)
        self.assertEqual(state["accepted_mapping_sha256"], FRONTEND.sha256(self.fixture.output / "mapping.txt"))

    def test_unequal_topology_caps_remove_implicit_uniform_ceiling_across_feedback(self):
        self.set_topology_caps(90, 10)
        # Both devices have the same physical size, so their balanced measured
        # LUTs alone need no further iteration. Retain a path-cleanup reason to
        # exercise propagation of the distinct hard ceilings through feedback.
        self.force_feedback = True
        state = self.execute("--iterations", "2")
        self.assert_policy(state, 1.0, "automatic_heterogeneous_native_caps")
        self.assertEqual(len(self.fixture.partition_commands), 2)
        self.assertIsNone(state["logical_balance_requested"])
        self.assertEqual(state["hard_native_clb_lut_caps"], {"FPGA_A": 90, "FPGA_B": 10})
        self.assertIn("--scalar_capacities", self.fixture.partition_commands[1])
        self.assertEqual(state["native_cap_check"], "PASS")

    def test_unequal_json_override_is_resolved_after_topology_caps(self):
        self.set_topology_caps(100, 100)
        file = self.fixture.root / "caps.json"
        file.write_text(json.dumps({"FPGA_A": 90, "FPGA_B": 10}))
        state = self.execute("--fpga-lut-caps", str(file))
        self.assert_policy(state, 1.0, "automatic_heterogeneous_native_caps")
        self.assertEqual(state["hard_native_clb_lut_caps"], {"FPGA_A": 90, "FPGA_B": 10})

    def test_global_maximum_can_make_unequal_resolved_caps_equal(self):
        self.set_topology_caps(90, 10)
        state = self.execute("--max-luts", "10")
        self.assert_policy(state, 0.5, "automatic_uniform_or_unspecified_native_caps")
        self.assertEqual(state["hard_native_clb_lut_caps"], {"FPGA_A": 10, "FPGA_B": 10})

    def test_equal_native_caps_preserve_existing_default(self):
        state = self.execute()
        self.assert_policy(state, 0.5, "automatic_uniform_or_unspecified_native_caps")
        self.assertIsNone(state["logical_balance_requested"])

    def test_absent_explicit_caps_keep_logical_default_and_physical_device_cap(self):
        self.set_topology_caps()
        state = self.execute()
        self.assert_policy(state, 0.5, "automatic_uniform_or_unspecified_native_caps")
        self.assertEqual(state["hard_native_clb_lut_caps"], {"FPGA_A": 6345216, "FPGA_B": 6345216})
        self.assertEqual(state["native_cap_check"], "PASS")

    def test_explicit_zero_logical_guard_is_preserved_with_uneven_caps(self):
        self.set_topology_caps(90, 10)
        state = self.execute("--logical-balance", "0")
        self.assert_policy(state, 0.0, "explicit_user")
        self.assertEqual(state["logical_balance_requested"], 0.0)

    def test_explicit_original_half_guard_is_not_mistaken_for_automatic(self):
        self.set_topology_caps(90, 10)
        state = self.execute("--logical-balance", "0.5")
        self.assert_policy(state, 0.5, "explicit_user")

    def test_explicit_six_resource_file_is_copied_unchanged_and_stays_bound(self):
        self.set_topology_caps(90, 10)
        caps = self.fixture.root / "logical_caps.txt"
        data = b"0 75 90 2 3 4 5\n1 75 10 2 3 4 5\n"
        caps.write_bytes(data)
        state = self.execute("--logical-resource-capacities", str(caps))
        self.assert_policy(state, 1.0, "automatic_heterogeneous_native_caps")
        self.assertTrue(state["logical_resource_capacities_explicit"])
        copied = self.fixture.output / "inputs/logical_resource_capacities.txt"
        self.assertEqual(copied.read_bytes(), data)
        self.assertEqual(caps.read_bytes(), data)
        relative = str(copied.relative_to(self.fixture.output))
        self.assertEqual(state["input_snapshot_sha256"][relative], FRONTEND.sha256(copied))
        for command in self.fixture.partition_commands:
            self.assertEqual(command[command.index("--resource_capacities") + 1], str(copied))

    def test_invalid_explicit_nonfinite_or_negative_guard_fails_before_commands(self):
        for value in ("nan", "inf", "-inf", "-0.1"):
            with self.subTest(value=value):
                self.setUp()
                with self.assertRaisesRegex(ValueError, "logical_balance.*finite.*nonnegative"):
                    self.execute("--logical-balance=" + value)
                self.assertEqual(self.fixture.export_commands, [])
                self.assertEqual(self.fixture.partition_commands, [])
                self.assertFalse((self.fixture.output / "mapping.txt").exists())
                self.assertEqual(json.loads((self.fixture.output / "STATUS.json").read_text())["status"], "FAIL")

    def test_optional_selected_swap_pass_report_is_published_verbatim(self):
        self.swap_report = True
        state = self.execute()
        selected = self.fixture.output / f"candidate_{state['selected_iteration']:02d}" / "swap_refinement_passes.tsv"
        self.assertEqual((self.fixture.output / "swap_refinement_passes.tsv").read_bytes(), selected.read_bytes())

    def test_missing_optional_swap_report_does_not_block_legacy_publication(self):
        self.execute()
        self.assertFalse((self.fixture.output / "swap_refinement_passes.tsv").exists())

    def test_automatic_heterogeneous_default_scales_to_four_and_sixty_four_parts(self):
        self.assertEqual(FRONTEND.resolve_logical_balance(None, 4, {"A": 90, "B": 10, "C": 10, "D": 10}),
                         (3.0, "automatic_heterogeneous_native_caps"))
        caps = {"P" + str(index): int(index != 0) for index in range(64)}
        self.assertEqual(FRONTEND.resolve_logical_balance(None, 64, caps),
                         (63.0, "automatic_heterogeneous_native_caps"))
        self.assertEqual(FRONTEND.resolve_logical_balance(0.125, 4, {"A": 90, "B": 10}),
                         (0.125, "explicit_user"))


if __name__ == "__main__":
    unittest.main()
