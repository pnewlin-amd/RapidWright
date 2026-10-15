"""Opaque-byte and native-seal regression tests; no Vivado installation required."""
import gzip
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("archive_native_request", Path(__file__).with_name("archive_native_request.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ArchiveNativeRequestTests(unittest.TestCase):
    def setUp(self):
        test_work = Path(__file__).resolve().parents[1] / "build/partition-python-tests"
        test_work.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="archive_native_request_test_", dir=test_work)
        self.root = Path(self.temporary.name).resolve()
        self.directory = self.root / "native request [x]"
        self.directory.mkdir()
        self.checkpoint = self.root / "source.dcp"
        self.checkpoint.write_bytes(b"checkpoint fixture")
        self.mapping = self.root / "mapping.txt"
        self.mapping.write_bytes(b"top FPGA_A\n")
        (self.directory / "source_mapping.txt").write_bytes(self.mapping.read_bytes())
        cp, mapping = MODULE.sha256(self.checkpoint), MODULE.sha256(self.mapping)
        (self.directory / "native_complete.txt").write_text(f"status=VIVADO_PARTITION_RESOURCE_MAPPING_PASS\ncheckpoint_sha256={cp}\nmapping_sha256={mapping}\n")
        (self.directory / "provenance.txt").write_text(f"checkpoint={self.checkpoint}\ncheckpoint_sha256={cp}\nmapping_sha256={mapping}\n")
        (self.directory / "vivado_resources.tsv").write_text("partition\tclb_luts\tflip_flops\nDESIGN\t8\t3\nFPGA_A\t8\t3\n")
        self.report = self.directory / "FPGA_A_utilization.rpt"
        self.report_data = (b"opaque native report \x00\xff\r\n" * 3000)
        self.report.write_bytes(self.report_data)
        self.cells = self.directory / "native_cells_FPGA_A.txt"
        self.cells_data = b"top/a/b[3]\ntop/c d/e\n" * 3000
        self.cells.write_bytes(self.cells_data)
        for name in ("preassigned_cells.tsv", "preassigned_hashes.txt", "frontier.tsv", "final_assignment.tsv", "mapping.txt", "unrelated.txt"):
            (self.directory / name).write_bytes(b"must stay plain \x00\xff\n")
        self.protected = MODULE.protected_hashes(self.directory)

    def tearDown(self):
        intended = (Path(__file__).resolve().parents[1] / "build/partition-python-tests").resolve()
        self.assertTrue(Path(self.temporary.name).resolve().is_relative_to(intended))
        self.temporary.cleanup()

    def archive(self, **kwargs):
        return MODULE.process_request(self.directory, self.root, mapping=self.mapping,
                                      oracle_exit_code=0, minimum_bytes=0, apply=True, **kwargs)

    def test_exact_archive_restore_and_anchor_preservation(self):
        result = self.archive()
        self.assertEqual("SEALED_NATIVE_REQUEST_ARCHIVE_PASS", result["status"])
        self.assertEqual(2, len(result["files"]))
        self.assertTrue(all(item["original_bytes"] == item["source_bytes"] for item in result["files"]))
        self.assertFalse(self.report.exists())
        self.assertFalse(self.cells.exists())
        self.assertEqual(self.protected, MODULE.protected_hashes(self.directory))
        restored = MODULE.process_request(self.directory, self.root, restore=True, apply=True)
        self.assertEqual(2, len(restored["files"]))
        self.assertEqual(self.report_data, self.report.read_bytes())
        self.assertEqual(self.cells_data, self.cells.read_bytes())
        # Identical existing archives are reused, not overwritten.
        hashes = {path.name: MODULE.sha256(path) for path in self.directory.glob("*.gz")}
        again = self.archive()
        self.assertTrue(all(item["reuse_status"] == "REUSED_VERIFIED_ARCHIVE" for item in again["files"]))
        self.assertEqual(hashes, {path.name: MODULE.sha256(path) for path in self.directory.glob("*.gz")})

    def test_incomplete_or_wrong_oracle_exit_rejected_without_removal(self):
        with self.assertRaises(ValueError):
            MODULE.process_request(self.directory, self.root, oracle_exit_code=1, minimum_bytes=0, apply=True)
        (self.directory / "native_complete.txt").write_text("status=INCOMPLETE\n")
        with self.assertRaises(ValueError):
            self.archive()
        self.assertTrue(self.report.exists())
        self.assertTrue(self.cells.exists())

    def test_changed_checkpoint_or_mapping_rejected(self):
        self.checkpoint.write_bytes(b"changed checkpoint")
        with self.assertRaises(ValueError):
            self.archive()
        self.assertTrue(self.report.exists())
        self.checkpoint.write_bytes(b"checkpoint fixture")
        self.mapping.write_bytes(b"top FPGA_B\n")
        with self.assertRaises(ValueError):
            self.archive()
        self.assertTrue(self.cells.exists())

    def test_nonfinite_table_rejected(self):
        (self.directory / "vivado_resources.tsv").write_text("partition\tclb_luts\tflip_flops\nDESIGN\tNaN\t3\nFPGA_A\t8\t3\n")
        with self.assertRaises(ValueError):
            self.archive()
        self.assertTrue(self.cells.exists())

    def test_different_archive_never_overwritten(self):
        self.archive()
        MODULE.process_request(self.directory, self.root, restore=True, apply=True)
        archive = self.report.with_name(self.report.name + ".gz")
        archive.write_bytes(gzip.compress(b"different archive"))
        corrupt_hash = MODULE.sha256(archive)
        with self.assertRaises(ValueError):
            self.archive()
        self.assertEqual(corrupt_hash, MODULE.sha256(archive))
        self.assertEqual(self.report_data, self.report.read_bytes())

    def test_restore_escape_and_changed_original_rejected(self):
        self.archive()
        MODULE.process_request(self.directory, self.root, restore=True, apply=True)
        self.report.write_bytes(b"changed restored original")
        with self.assertRaises(ValueError):
            MODULE.process_request(self.directory, self.root, restore=True, apply=True)
        receipt = self.report.with_name(self.report.name + ".archive.json")
        record = json.loads(receipt.read_text())
        record["source_file"] = str(self.root / "outside.rpt")
        receipt.write_text(json.dumps(record))
        with self.assertRaises(ValueError):
            MODULE.process_request(self.directory, self.root, restore=True, apply=True)
        self.assertFalse((self.root / "outside.rpt").exists())


if __name__ == "__main__":
    unittest.main()
