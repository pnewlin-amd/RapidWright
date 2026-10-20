"""Check process accounting independently of the partitioning algorithm."""
from contextlib import nullcontext
import importlib.util
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("tested_runtime_metrics", Path(__file__).with_name("runtime_metrics.py"))
RUNTIME = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNTIME)


class ProcessError(Exception):
    pass


class Process:
    def __init__(self, pid, created=1, rss=100, cpu=0):
        self.pid, self.created, self.rss, self.cpu = pid, created, rss, cpu
        self.descendants = []
        self.alive = True
        self.denied = False
    def children(self, recursive=False):
        return self.descendants
    def create_time(self):
        return self.created
    def is_running(self):
        return self.alive
    def memory_info(self):
        if self.denied:
            raise ProcessError("denied")
        return SimpleNamespace(rss=self.rss)
    def cpu_times(self):
        # These aggregate child fields must never be counted a second time.
        return SimpleNamespace(user=self.cpu, system=0, children_user=999, children_system=999)
    def oneshot(self):
        return nullcontext()


class RuntimeAccounting(unittest.TestCase):
    def setUp(self):
        self.root = Process(1)
        self.provider = SimpleNamespace(Process=lambda pid: self.root, Error=ProcessError, __version__="fixture")
        self.modules = patch.dict(sys.modules, {"psutil": self.provider})
        self.modules.start()
        self.addCleanup(self.modules.stop)

    def monitor(self):
        return RUNTIME.RuntimeMonitor(started_perf=0, started_cpu=0)

    def test_cpu_totals_do_not_double_count_children_or_repeated_samples(self):
        monitor = self.monitor()
        self.root.descendants = [Process(2, cpu=1), Process(3, cpu=2)]
        with patch.object(RUNTIME.time, "perf_counter", return_value=2), patch.object(RUNTIME.time, "process_time", return_value=.5):
            first = monitor.snapshot()
            second = monitor.snapshot()
        self.assertEqual(first["cpu_time_seconds"], 3.5)
        self.assertEqual(second["cpu_time_seconds"], 3.5)
        self.assertEqual(second["average_cpu_cores"], 1.75)
        self.assertEqual(second["average_cpu_percent_one_core"], 175)

    def test_memory_is_simultaneous_tree_peak_not_sum_of_separate_peaks(self):
        monitor = self.monitor()
        old = Process(2, rss=1000, cpu=1)
        self.root.descendants = [old]
        monitor._sample()
        old.alive = False
        self.root.descendants = [Process(3, rss=1500, cpu=2)]
        result = monitor.snapshot()
        self.assertEqual(result["peak_sampled_process_tree_rss_bytes"], 1600)
        self.assertEqual(result["child_processes_observed"], 2)
        self.assertEqual(result["observed_child_cpu_time_seconds"], 3)
        self.assertEqual(len(monitor.processes), 1)

    def test_pid_reuse_is_a_different_process_identity(self):
        monitor = self.monitor()
        old = Process(42, created=1, cpu=1)
        self.root.descendants = [old]
        monitor._sample()
        old.alive = False
        self.root.descendants = [Process(42, created=2, cpu=2)]
        result = monitor.snapshot()
        self.assertEqual(result["child_processes_observed"], 2)
        self.assertEqual(result["observed_child_cpu_time_seconds"], 3)

    def test_preexisting_children_are_excluded_from_this_run(self):
        self.root.descendants = [Process(2, rss=1000000, cpu=999)]
        result = self.monitor().snapshot()
        self.assertEqual(result["child_processes_observed"], 0)
        self.assertEqual(result["peak_sampled_process_tree_rss_bytes"], 100)
        self.assertEqual(result["observed_child_cpu_time_seconds"], 0)

    def test_known_live_orphan_remains_observed(self):
        monitor = self.monitor()
        child = Process(2, rss=1000, cpu=1)
        self.root.descendants = [child]
        monitor._sample()
        self.root.descendants = []
        child.cpu = 2
        self.assertEqual(monitor.snapshot()["observed_child_cpu_time_seconds"], 2)

    def test_inaccessible_memory_is_unavailable_rather_than_zero(self):
        monitor = self.monitor()
        self.root.denied = True
        result = monitor.snapshot()
        self.assertIsNone(result["peak_sampled_process_tree_rss_bytes"])
        self.assertIsNone(result["frontend_peak_rss_bytes"])
        self.assertGreater(result["process_read_errors"], 0)

    def test_wsl_is_explicitly_outside_host_cpu_and_memory_scope(self):
        result = RUNTIME.RuntimeMonitor(wsl_guest_excluded=True).snapshot()
        self.assertTrue(result["wsl_guest_processes_excluded"])
        self.assertIn("WSL guest CPU and memory are excluded", result["scope"])

    def test_invalid_sampling_interval_is_rejected(self):
        for value in (0, -1, float("nan"), float("inf"), True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                RUNTIME.RuntimeMonitor(interval=value)


class ActualRuntimeProcess(unittest.TestCase):
    def test_busy_child_cpu_memory_and_thread_shutdown(self):
        monitor = RUNTIME.RuntimeMonitor(interval=.02).start()
        child = None
        try:
            child = subprocess.Popen([sys.executable, "-c",
                "import time; data=bytearray(12*1024*1024); print('ready',flush=True); "
                "start=time.perf_counter();\nwhile time.perf_counter()-start<.6: sum(range(10000))"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            self.assertEqual(child.stdout.readline().strip(), "ready")
            child.communicate(timeout=10)
            self.assertEqual(child.returncode, 0)
            result = monitor.snapshot("child_workload_complete")
            self.assertGreaterEqual(result["child_processes_observed"], 1)
            self.assertGreater(result["observed_child_cpu_time_seconds"], .01)
            self.assertGreaterEqual(result["peak_sampled_process_tree_rss_bytes"], result["frontend_peak_rss_bytes"])
            self.assertGreater(result["wall_clock_seconds"], .5)
        finally:
            if child is not None and child.poll() is None:
                child.kill()
                child.communicate()
            monitor.stop()
        self.assertFalse(monitor.thread.is_alive())


if __name__ == "__main__":
    unittest.main()
