"""Adaptive native residency exercises the real frontend with cheap tool boundaries.

No native or Java process is launched. The fixtures preserve real source/mapping
identity, feedback, quality-anchor and final-publication control.
"""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "native_residency_quality_fixture", Path(__file__).with_name("test_partition_quality_feedback.py"))
QUALITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUALITY)
FRONTEND = QUALITY.FRONTEND
GIB = 1024 ** 3


class AdaptiveNativeResidency(unittest.TestCase):
    def fixture(self):
        quality = QUALITY.AutomaticQualityFeedbackAcceptance(methodName="runTest")
        quality.setUp()
        self.addCleanup(quality.doCleanups)
        for name in ("native_resource_session.py", "resource_session.tcl"):
            (quality.fixture.repo / "tools" / name).write_text("mock native residency boundary\n")
        return quality

    def execute(self, memory, *, off=False, fail_close=False, reject_second=False, solver_input_bytes=4096, native_timeout=7200):
        quality = self.fixture()
        quality.reject_second = reject_second
        events, sessions = [], []
        boundary = quality.fixture.run_boundary
        memory_values = iter(memory)
        hgr_sizes = {}
        original_stat = Path.stat

        def measured_stat(path, *args, **kwargs):
            result = original_stat(path, *args, **kwargs)
            if path in hgr_sizes:
                values = list(result)
                values[6] = hgr_sizes[path]
                return os.stat_result(values)
            return result

        def memory_available():
            available = next(memory_values)
            events.append(("memory", available))
            return available

        class Process:
            alive = True
            def poll(self):
                value = None if self.alive else 0
                events.append(("poll", value))
                return value

        class Session:
            def __init__(self, checkpoint, reporter, output, vivado, timeout):
                self.timeout = timeout
                self.checkpoint, self.output = Path(checkpoint), Path(output)
                self.process = Process()
                sessions.append(self)
                events.append(("construct", len(sessions)))
            def __enter__(self):
                self.output.mkdir()
                events.append(("open", None))
                return self
            def evaluate(self, mapping, output):
                mapping, output = Path(mapping), Path(output)
                events.append(("persistent", mapping.parent.name))
                # Use the fixture's exact native completion/snapshot writer,
                # bypassing the outer batch-command event recorder.
                boundary(["mock_native", "-tclargs", self.checkpoint, mapping, output],
                         quality.fixture.root, output / "mock_native.log")
            def close(self):
                events.append(("close", None))
                if not fail_close:
                    self.process.alive = False

        class Loader:
            def create_module(self, spec): return None
            def exec_module(self, module): module.NativeResourceSession = Session

        real_spec = importlib.util.spec_from_file_location
        def module_spec(name, path, *args, **kwargs):
            if name == "rw_native_resource_session":
                events.append(("import", None))
                return importlib.machinery.ModuleSpec(name, Loader())
            return real_spec(name, path, *args, **kwargs)

        def command_boundary(command, cwd, log):
            command = [str(value) for value in command]
            if FRONTEND.MAIN + "Partitioner" in command:
                candidate = Path(command[command.index("--part_dir") + 1]).name
                events.append(("java_start", candidate))
                boundary(command, cwd, log)
                if solver_input_bytes is not None:
                    size = solver_input_bytes[int(candidate.rsplit("_", 1)[1])] if isinstance(solver_input_bytes, tuple) else solver_input_bytes
                    hgr = quality.fixture.output / candidate / (quality.fixture.netlist.name + ".hgr")
                    hgr.write_bytes(b"small completed native-solver input fixture\n")
                    hgr_sizes[hgr] = size
                events.append(("java_exit", candidate))
            else:
                if "-tclargs" in command:
                    index = command.index("-tclargs")
                    events.append(("batch", Path(command[index + 2]).parent.name))
                boundary(command, cwd, log)

        quality.fixture.run_boundary = command_boundary
        original_module = sys.modules.get("rw_native_resource_session")
        try:
            with patch.object(FRONTEND, "available_memory_bytes", side_effect=memory_available), \
                    patch.object(Path, "stat", measured_stat), \
                    patch.object(importlib.util, "spec_from_file_location", side_effect=module_spec):
                try:
                    status = quality.execute("--iterations", "3", "--heap", "16G", "--threads", "4", "--native-session", "off" if off else "auto", "--native-timeout-seconds", str(native_timeout))
                except RuntimeError:
                    if not fail_close:
                        raise
                    status = json.loads((quality.fixture.output / "STATUS.json").read_text())
        finally:
            if original_module is None:
                sys.modules.pop("rw_native_resource_session", None)
            else:
                sys.modules["rw_native_resource_session"] = original_module
        return quality, status, events, sessions

    def test_native_probe_is_allowed_after_java_even_when_old_combined_estimate_fails(self):
        quality, status, events, sessions = self.execute([12 * GIB, 30 * GIB, 30 * GIB])
        self.assertFalse(FRONTEND.persistent_memory_fits("16G", quality.fixture.netlist.stat().st_size, 12 * GIB, 2))
        self.assertEqual(len(sessions), 1)
        self.assertLess(events.index(("java_exit", "candidate_00")), events.index(("memory", 12 * GIB)))
        self.assertLess(events.index(("memory", 12 * GIB)), events.index(("open", None)))
        self.assertEqual([value for kind, value in events if kind == "persistent"], ["candidate_00", "candidate_01", "candidate_02"])
        self.assertEqual([value for kind, value in events if kind == "batch"], [])
        self.assertEqual(status["native_oracle_mode"], "persistent_original_checkpoint")
        self.assertEqual([row["decision"] for row in status["native_residency_decisions"]], ["load_native_probe", "retain", "retain"])
        self.assertEqual([(row["prior_hgr_bytes"], row["native_solver_reserve_bytes"])
                          for row in status["native_residency_decisions"][1:]],
                         [(4096, 2 * GIB + 32 * 4096)] * 2)
        self.assertEqual(status["native_cap_check"], "PASS")
        self.assertEqual(quality.fixture.final_gate_calls, 1)
        self.assertEqual((quality.fixture.output / "mapping.txt").read_bytes(),
                         (quality.fixture.output / "candidate_01/vivado_reference/source_mapping.txt").read_bytes())

    def test_insufficient_or_unknown_measured_residency_closes_before_java_and_stays_batch(self):
        for available in (24 * GIB - 1, None):
            with self.subTest(available=available):
                quality, status, events, sessions = self.execute([12 * GIB, available])
                self.assertEqual(len(sessions), 1)
                self.assertEqual([value for kind, value in events if kind == "persistent"], ["candidate_00"])
                self.assertEqual([value for kind, value in events if kind == "batch"], ["candidate_01", "candidate_02"])
                closed, exited, java = events.index(("close", None)), events.index(("poll", 0)), events.index(("java_start", "candidate_01"))
                self.assertLess(closed, exited)
                self.assertLess(exited, java)
                self.assertEqual([value for kind, value in events if kind == "memory"], [12 * GIB, available])
                self.assertEqual(status["native_oracle_mode"], "batch_after_memory_guard")
                self.assertEqual(status["native_residency_decisions"][-1]["decision"], "close_and_use_batch")
                self.assertEqual(status["native_cap_check"], "PASS")
                self.assertEqual(quality.fixture.final_gate_calls, 1)

    def test_insufficient_or_unknown_initial_probe_uses_batch_without_loading_helper(self):
        for available in (GIB, None):
            with self.subTest(available=available):
                _, status, events, sessions = self.execute([available])
                self.assertEqual(sessions, [])
                self.assertFalse(any(kind in ("import", "persistent") for kind, _ in events))
                self.assertEqual([value for kind, value in events if kind == "batch"], ["candidate_00", "candidate_01", "candidate_02"])
                self.assertEqual(status["native_oracle_mode"], "batch_probe_memory_guard")
                self.assertEqual(status["native_residency_decisions"][0]["decision"], "use_batch")

    def test_explicit_off_never_probes_or_loads_resident_native_session(self):
        _, status, events, sessions = self.execute([], off=True)
        self.assertEqual(sessions, [])
        self.assertFalse(any(kind in ("memory", "import", "persistent", "close") for kind, _ in events))
        self.assertEqual([value for kind, value in events if kind == "batch"], ["candidate_00", "candidate_01", "candidate_02"])
        self.assertEqual(status["native_oracle_mode"], "batch")
        self.assertEqual(status["native_residency_decisions"], [])

    def test_failed_native_shutdown_cannot_start_next_java_or_publish_mapping(self):
        quality, status, events, sessions = self.execute([12 * GIB, None], fail_close=True)
        self.assertEqual(len(sessions), 1)
        self.assertEqual([value for kind, value in events if kind == "java_start"], ["candidate_00"])
        self.assertIn(("poll", None), events)
        self.assertIn("did not exit", status["error"])
        self.assertEqual(status["status"], "FAIL")
        self.assertEqual(quality.fixture.final_gate_calls, 0)
        self.assertFalse((quality.fixture.output / "mapping.txt").exists())

    def test_memory_is_rechecked_after_a_rejected_anchor_seed_before_retry_java(self):
        _, status, events, _ = self.execute([12 * GIB, 30 * GIB, None], reject_second=True)
        self.assertEqual(status["candidates"][1]["status"], "REJECTED")
        self.assertEqual([value for kind, value in events if kind == "persistent"], ["candidate_00"])
        self.assertEqual([value for kind, value in events if kind == "batch"], ["candidate_02"])
        self.assertLess(events.index(("poll", 0)), events.index(("java_start", "candidate_02")))
        self.assertEqual([row["decision"] for row in status["native_residency_decisions"]],
                         ["load_native_probe", "retain", "close_and_use_batch"])
        self.assertEqual(status["selected_iteration"], 2)

    def test_large_solver_input_closes_native_even_when_heap_and_jvm_overhead_fit(self):
        _, status, events, _ = self.execute([12 * GIB, 30 * GIB], solver_input_bytes=256 * 1024 ** 2)
        self.assertGreater(30 * GIB, (16 + 8) * GIB)
        decision = status["native_residency_decisions"][-1]
        self.assertEqual(decision["prior_hgr_bytes"], 256 * 1024 ** 2)
        self.assertEqual(decision["native_solver_reserve_bytes"], 10 * GIB)
        self.assertEqual(decision["decision"], "close_and_use_batch")
        self.assertLess(events.index(("poll", 0)), events.index(("java_start", "candidate_01")))
        self.assertEqual([value for kind, value in events if kind == "batch"], ["candidate_01", "candidate_02"])

    def test_missing_prior_solver_input_is_unknown_and_uses_batch_before_next_java(self):
        _, status, events, _ = self.execute([12 * GIB, 30 * GIB], solver_input_bytes=None)
        decision = status["native_residency_decisions"][-1]
        self.assertIsNone(decision["prior_hgr_bytes"])
        self.assertIsNone(decision["native_solver_reserve_bytes"])
        self.assertEqual(decision["decision"], "close_and_use_batch")
        self.assertLess(events.index(("poll", 0)), events.index(("java_start", "candidate_01")))

    def test_prior_solver_maximum_is_rechecked_and_exact_memory_threshold_can_retain(self):
        # The128MiB solver input exactly fits16G heap+8G overhead+6G solver.
        # A later256MiB input forces a new decision instead of reusing the fit.
        _, status, events, _ = self.execute([12 * GIB, 30 * GIB, 30 * GIB],
                                          solver_input_bytes=(128 * 1024 ** 2, 256 * 1024 ** 2, 4096))
        decisions = status["native_residency_decisions"][1:]
        self.assertEqual([(row["prior_hgr_bytes"], row["native_solver_reserve_bytes"], row["decision"]) for row in decisions],
                         [(128 * 1024 ** 2, 6 * GIB, "retain"), (256 * 1024 ** 2, 10 * GIB, "close_and_use_batch")])
        self.assertEqual([value for kind, value in events if kind == "persistent"], ["candidate_00", "candidate_01"])
        self.assertEqual([value for kind, value in events if kind == "batch"], ["candidate_02"])


if __name__ == "__main__": unittest.main()
