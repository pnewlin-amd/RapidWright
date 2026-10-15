"""Persistent timeout control, with no native process or large netlist load."""
import importlib.util
import json
from pathlib import Path
import queue
import tempfile
import unittest
from unittest.mock import Mock, patch
import test_partition_native_residency as RESIDENCY

FRONTEND = RESIDENCY.FRONTEND
BUNDLE = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("review_native_timeout_session", BUNDLE / "tools/native_resource_session.py")
SESSION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SESSION)


class NativeTimeoutControl(unittest.TestCase):
    def setUp(self):
        work = BUNDLE / "build/partition-python-tests"
        work.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=work)
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.checkpoint, self.reporter = self.directory / "source.dcp", self.directory / "report.tcl"
        self.checkpoint.write_bytes(b"source fixture")
        self.reporter.write_bytes(b"report fixture")

    def session(self, timeout=None):
        kwargs = {} if timeout is None else {"timeout": timeout}
        return SESSION.NativeResourceSession(self.checkpoint, self.reporter, self.directory / "session", "unused", **kwargs)

    def test_parser_and_direct_session_default_agree_and_custom_positive_is_retained(self):
        args = FRONTEND.parser().parse_args(["--input", "source.dcp", "--system-topology", "board.json"])
        self.assertEqual(args.native_timeout_seconds, 7200)
        self.assertEqual(self.session().timeout, 7200)
        self.assertEqual(self.session(117.5).timeout, 117.5)
        self.assertIn("7200", FRONTEND.parser().format_help())

    def test_session_rejects_invalid_timeout_before_source_or_process_access(self):
        for timeout in (0, -1, float("nan"), float("inf"), -float("inf"), True, "10"):
            with self.subTest(timeout=timeout):
                with patch.object(SESSION.subprocess, "Popen") as process:
                    with self.assertRaisesRegex(ValueError, "finite and positive"):
                        SESSION.NativeResourceSession("missing-source", "missing-report", self.directory, "unused", timeout)
                    process.assert_not_called()

    def test_frontend_invalid_timeout_fails_before_any_expensive_command_or_publication(self):
        for index, value in enumerate(("0", "-1", "nan", "inf", "-inf")):
            output = self.directory / ("invalid_" + str(index))
            args = FRONTEND.parser().parse_args(["--input", "missing.dcp", "--system-topology", "missing.json",
                                                "--output", str(output), "--native-timeout-seconds=" + value])
            with patch.object(FRONTEND, "run") as run:
                with self.assertRaisesRegex(ValueError, "finite and positive"):
                    FRONTEND.execute(args)
                run.assert_not_called()
            self.assertEqual(json.loads((output / "STATUS.json").read_text())["status"], "FAIL")
            self.assertFalse((output / "mapping.txt").exists())

    def test_real_controller_propagates_custom_timeout_and_records_scope(self):
        runner = RESIDENCY.AdaptiveNativeResidency(methodName="runTest")
        self.addCleanup(runner.doCleanups)
        _, status, _, sessions = runner.execute([12 * RESIDENCY.GIB, 30 * RESIDENCY.GIB, 30 * RESIDENCY.GIB], native_timeout=4321.5)
        self.assertEqual(len(sessions), 1)
        self.assertEqual(sessions[0].timeout, 4321.5)
        self.assertEqual(status["native_timeout_seconds"], 4321.5)
        self.assertIn("startup and each resource request", status["native_timeout_scope"])
        self.assertEqual(status["native_cap_check"], "PASS")
        self.assertEqual(status["native_oracle_mode"], "persistent_original_checkpoint")

    def test_batch_mode_records_timeout_but_never_constructs_session(self):
        runner = RESIDENCY.AdaptiveNativeResidency(methodName="runTest")
        self.addCleanup(runner.doCleanups)
        _, status, events, sessions = runner.execute([], off=True, native_timeout=1234)
        self.assertEqual(sessions, [])
        self.assertFalse(any(kind in ("import", "open", "persistent") for kind, _ in events))
        self.assertEqual(status["native_timeout_seconds"], 1234)
        self.assertIn("batch commands unaffected", status["native_timeout_scope"])
        self.assertEqual(status["native_cap_check"], "PASS")

    def test_unrelated_protocol_output_cannot_extend_the_request_deadline(self):
        session = self.session(10)
        session.responses = Mock()
        session.responses.get.side_effect = ["RW_NATIVE_UNRELATED", queue.Empty()]
        with patch.object(SESSION.time, "monotonic", side_effect=[100, 102, 108]):
            with self.assertRaisesRegex(TimeoutError, "10.0 seconds"):
                session.wait_for("RW_NATIVE_RESPONSE\trequested\t")
        self.assertEqual([call.kwargs["timeout"] for call in session.responses.get.call_args_list], [8, 2])

    def test_exhausted_deadline_does_not_accept_a_queued_stale_response(self):
        session = self.session(10)
        session.responses = Mock()
        session.responses.get.return_value = "RW_NATIVE_READY"
        with patch.object(SESSION.time, "monotonic", side_effect=[100, 110]):
            with self.assertRaises(TimeoutError): session.wait_for("RW_NATIVE_READY")
        session.responses.get.assert_not_called()

    def test_startup_and_each_request_receive_separate_wait_budget(self):
        session = self.session(10)
        session.responses = Mock()
        session.responses.get.side_effect = ["RW_NATIVE_READY", "RW_NATIVE_RESPONSE\tx\tPASS"]
        with patch.object(SESSION.time, "monotonic", side_effect=[100, 101, 200, 201]):
            self.assertEqual(session.wait_for("RW_NATIVE_READY"), "RW_NATIVE_READY")
            self.assertEqual(session.wait_for("RW_NATIVE_RESPONSE\tx\t"), "RW_NATIVE_RESPONSE\tx\tPASS")
        self.assertEqual([call.kwargs["timeout"] for call in session.responses.get.call_args_list], [9, 9])

    def test_owned_eof_is_failure_without_waiting_or_accepting_output(self):
        session = self.session(10)
        session.responses.put("RW_NATIVE_EOF")
        with self.assertRaisesRegex(RuntimeError, "ended without response"):
            session.wait_for("RW_NATIVE_READY")


if __name__ == "__main__": unittest.main()
