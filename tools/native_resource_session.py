"""Reuse one read-only native checkpoint for sequential resource requests.

Call prepare_native_resource_cells.py normally before evaluate(). The existing
VivadoResourceOracle remains the mandatory final importer/cap acceptance gate.
"""
import hashlib
import math
import codecs
import os
from pathlib import Path
import queue
import subprocess
import threading
import time
import uuid


def sha256(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def metadata(path):
    return dict(line.split("=", 1) for line in Path(path).read_text().splitlines() if "=" in line)


class NativeResourceSession:
    def __init__(self, checkpoint, report_script, output, vivado, timeout=7200):
        if (isinstance(timeout, bool) or not isinstance(timeout, (int, float))
                or not math.isfinite(timeout) or timeout <= 0):
            raise ValueError("Native session timeout must be finite and positive")
        self.checkpoint = Path(checkpoint).resolve(strict=True)
        self.report_script = Path(report_script).resolve(strict=True)
        self.output = Path(output).resolve()
        self.vivado = str(vivado)
        self.timeout = float(timeout)
        self.responses = queue.Queue()
        self.process = None
        self.reader = None
        self.console = None
        self.closed = False
        self.checkpoint_sha256 = None

    def __enter__(self):
        self.output.mkdir(parents=True, exist_ok=True)
        command = [self.vivado, "-mode", "batch", "-source", str(Path(__file__).with_name("resource_session.tcl")),
                   "-log", str(self.output / "vivado.log"), "-journal", str(self.output / "vivado.jou"),
                   "-tclargs", str(self.checkpoint), str(self.report_script), sha256(self.report_script)]
        self.console = (self.output / "console.log").open("wb")
        try:
            self.process = subprocess.Popen(command, cwd=self.output, stdin=subprocess.PIPE,
                                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                            bufsize=65536)
        except BaseException:
            self.closed = True
            self.console.close()
            raise
        def drain():
            decoder = codecs.getincrementaldecoder("utf-8")("replace")
            carry, discard = "", False
            try:
                while True:
                    chunk = self.process.stdout.read1(65536)
                    if not chunk:
                        break
                    self.console.write(chunk)
                    self.console.flush()
                    parts = decoder.decode(chunk).split("\n")
                    for index, part in enumerate(parts):
                        ended = index < len(parts) - 1
                        if discard:
                            if ended:
                                discard = False
                            continue
                        carry += part
                        if ended:
                            if carry.startswith("RW_NATIVE_"):
                                self.responses.put(carry.strip())
                            carry = ""
                        elif len(carry) > 4096:
                            # Huge report command headers are copied to disk,
                            # never accumulated as a Python line in memory.
                            carry, discard = "", True
            finally:
                self.responses.put("RW_NATIVE_EOF")
        self.reader = threading.Thread(target=drain, daemon=True)
        self.reader.start()
        try:
            self.wait_for("RW_NATIVE_READY")
        except Exception:
            self.close()
            raise
        return self

    def wait_for(self, prefix):
        # One deadline per startup/request, never extended by unrelated output.
        deadline = time.monotonic() + self.timeout
        error = ("Native session response timed out after " + str(self.timeout)
                 + " seconds; see " + str(self.output / "console.log"))
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(error)
            try:
                line = self.responses.get(timeout=remaining)
            except queue.Empty:
                raise TimeoutError(error) from None
            if line == "RW_NATIVE_EOF":
                raise RuntimeError("Native session ended without response; see " + str(self.output / "console.log"))
            if line.startswith(prefix):
                return line

    def evaluate(self, mapping, output):
        if self.closed or not self.process or self.process.poll() is not None:
            raise RuntimeError("Native session is not running")
        mapping, output = Path(mapping).resolve(strict=True), Path(output).resolve()
        if not (output / "preassigned_cells.tsv").is_file():
            raise ValueError("Run the existing native cell preparer before evaluate()")
        values = (str(mapping), str(output))
        if any(any(character in value for character in "\t\r\n") for value in values):
            raise ValueError("Protocol paths must not contain tabs or newlines")
        request = uuid.uuid4().hex
        mapping_hash = sha256(mapping)
        self.process.stdin.write((request + "\t" + "\t".join(values) + "\n").encode("utf-8"))
        self.process.stdin.flush()
        response = self.wait_for("RW_NATIVE_RESPONSE\t" + request + "\t").split("\t", 3)
        if len(response) != 4 or response[2] != "PASS":
            raise RuntimeError("Native session request failed: " + response[-1])
        completion = metadata(output / "native_complete.txt")
        provenance = metadata(output / "provenance.txt")
        if self.checkpoint_sha256 is None:
            self.checkpoint_sha256 = response[3]
        if (response[3] != self.checkpoint_sha256
                or completion.get("status") != "VIVADO_PARTITION_RESOURCE_MAPPING_PASS"
                or completion.get("mapping_sha256") != mapping_hash
                or completion.get("checkpoint_sha256") != self.checkpoint_sha256
                or provenance.get("mapping_sha256") != mapping_hash
                or provenance.get("checkpoint_sha256") != self.checkpoint_sha256
                or sha256(mapping) != mapping_hash):
            raise ValueError("Native session completion/provenance identity mismatch")
        return output / "vivado_resources.tsv"

    def close(self):
        if self.closed:
            return
        self.closed = True
        if self.process:
            if self.process.poll() is None:
                try:
                    self.process.stdin.write(b"STOP\n")
                    self.process.stdin.flush()
                    self.process.wait(timeout=30)
                except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
                    if os.name == "nt":
                        subprocess.run(["taskkill", "/PID", str(self.process.pid), "/T", "/F"],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
                    else:
                        self.process.terminate()
                    self.process.wait(timeout=10)
            if self.reader:
                self.reader.join(timeout=10)
            self.console.close()

    def __exit__(self, *unused):
        self.close()
