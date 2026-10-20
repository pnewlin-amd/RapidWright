"""Sample this run's host processes without attributing system-wide load to it.

CPU is a lower bound: a short-lived or inaccessible descendant may be missed.
RSS is the sum of simultaneously observed resident sets, not unique memory.
"""
import math
import os
import threading
import time
from datetime import datetime, timezone


def preflight():
    try:
        import psutil
    except ImportError as error:
        raise RuntimeError("Runtime statistics need psutil; install scripts/partition/requirements.txt") from error
    return {"psutil_version": psutil.__version__, "measurement": "sampled_host_process_tree"}


class RuntimeMonitor:
    def __init__(self, interval=0.2, started_perf=None, started_cpu=None,
                 started_utc=None, wsl_guest_excluded=False):
        if isinstance(interval, bool) or not math.isfinite(interval) or interval <= 0:
            raise ValueError("Runtime sampling interval must be finite and positive")
        preflight()
        import psutil
        self.psutil = psutil
        self.interval = interval
        self.started_perf = time.perf_counter() if started_perf is None else started_perf
        self.started_cpu = time.process_time() if started_cpu is None else started_cpu
        self.started_utc = started_utc or datetime.now(timezone.utc).isoformat()
        self.root = psutil.Process(os.getpid())
        self.wsl_guest_excluded = wsl_guest_excluded
        self.lock = threading.RLock()
        self.done = threading.Event()
        self.processes = {}
        self.seen = set()
        self.cpu = {}
        self.peak_tree_rss = None
        self.peak_frontend_rss = None
        self.samples = 0
        self.read_errors = 0
        self.thread = None
        # A caller embedding the frontend may already have unrelated children.
        # Ignore these; the standalone CLI normally starts with none.
        self.existing = set()
        try:
            for process in self.root.children(recursive=True):
                try:
                    self.existing.add((process.pid, process.create_time()))
                except self.psutil.Error:
                    self.read_errors += 1
        except self.psutil.Error:
            self.read_errors += 1

    def _sample(self):
        with self.lock:
            try:
                children = self.root.children(recursive=True)
            except self.psutil.Error:
                self.read_errors += 1
                children = []
            for process in children:
                try:
                    key = (process.pid, process.create_time())
                    if key not in self.existing:
                        self.processes.setdefault(key, process)
                        self.seen.add(key)
                except self.psutil.Error:
                    self.read_errors += 1
            total_rss = 0
            rss_available = False
            try:
                total_rss = self.root.memory_info().rss
                rss_available = True
                self.peak_frontend_rss = max(self.peak_frontend_rss or 0, total_rss)
            except self.psutil.Error:
                self.read_errors += 1
            for key, process in list(self.processes.items()):
                try:
                    # Guard against PID reuse, including retained orphan handles.
                    if process.create_time() != key[1] or not process.is_running():
                        self.processes.pop(key, None)
                        continue
                    with process.oneshot():
                        times = process.cpu_times()
                        cpu = times.user + times.system
                        self.cpu[key] = max(self.cpu.get(key, 0.0), cpu)
                        total_rss += process.memory_info().rss
                        rss_available = True
                except self.psutil.Error:
                    self.read_errors += 1
                    self.processes.pop(key, None)
            if rss_available:
                self.peak_tree_rss = max(self.peak_tree_rss or 0, total_rss)
            self.samples += 1

    def start(self):
        if self.thread is not None or self.done.is_set():
            raise ValueError("Runtime monitor can only be started once")
        self._sample()
        def observe():
            while not self.done.wait(self.interval):
                self._sample()
        self.thread = threading.Thread(target=observe, name="partition-runtime-monitor", daemon=True)
        self.thread.start()
        return self

    def snapshot(self, measured_through="partitioning"):
        self._sample()
        with self.lock:
            wall = max(0.0, time.perf_counter() - self.started_perf)
            frontend_cpu = max(0.0, time.process_time() - self.started_cpu)
            child_cpu = sum(self.cpu.values())
            cpu = frontend_cpu + child_cpu
            cores = cpu / wall if wall else 0.0
            return {"schema_version": 1, "started_utc": self.started_utc,
                    "measured_utc": datetime.now(timezone.utc).isoformat(),
                    "measured_through": measured_through, "wall_clock_seconds": wall,
                    "cpu_time_seconds": cpu, "frontend_cpu_time_seconds": frontend_cpu,
                    "observed_child_cpu_time_seconds": child_cpu,
                    "average_cpu_cores": cores, "average_cpu_percent_one_core": 100 * cores,
                    "peak_sampled_process_tree_rss_bytes": self.peak_tree_rss,
                    "frontend_peak_rss_bytes": self.peak_frontend_rss,
                    "child_processes_observed": len(self.seen), "sample_count": self.samples,
                    "sample_interval_seconds": self.interval, "process_read_errors": self.read_errors,
                    "cpu_measurement": "frontend process CPU plus sampled descendant CPU; lower bound",
                    "memory_measurement": "peak sampled sum of simultaneous resident sets; shared pages may be counted more than once",
                    "wsl_guest_processes_excluded": self.wsl_guest_excluded,
                    "scope": "This run's visible host process tree, including Java and Vivado. Short-lived/inaccessible processes may be missed."
                             + (" WSL guest CPU and memory are excluded." if self.wsl_guest_excluded else "")}

    def stop(self):
        self.done.set()
        if self.thread is not None:
            self.thread.join(timeout=5)
