from pathlib import Path
import tempfile
import tkinter
import unittest

from prepare_timing_paths import finalize, path_cells, primitive_owners, record_identity


class TimingPreparationTests(unittest.TestCase):
    def setUp(self):
        self.tcl = tkinter.Tcl()
        parent = Path(__file__).resolve().parents[1] / "build/partition-python-tests"
        parent.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def fields(self):
        return ("0", "-1.25", "10.000", "11.0", "clock_a", "clock_b", "a/C", "b/D", "pin", "pin", "a", "b",
                self.encode(("a/Q", "ram/physical/I", "a/I", "b/D")), self.encode(("a", "ram/physical", "a", "b")))

    def encode(self, values):
        return self.tcl.call("format", "%s", tuple(values))

    def test_native_data_order_preserves_reentry_and_collapses_physical_macro(self):
        owners = primitive_owners(("a", "b", "ram", "ram/physical"))
        cells, count = path_cells(self.fields(), self.tcl, owners)
        self.assertEqual(cells, ["a", "ram", "a", "b"])
        self.assertEqual(count, 4)
        top = list(self.fields())
        top[6], top[8], top[10] = "input[3]", "port", ""
        self.assertEqual(path_cells(top, self.tcl, owners)[0][0], "@top_io:input[3]")
        with self.assertRaisesRegex(ValueError, "Unresolved"):
            path_cells(self.fields(), self.tcl, {"a": "a", "b": "b"})

    def test_finite_sample_skips_unconstrained_and_rejects_stale_source(self):
        checkpoint, edif = self.root / "source.dcp", self.root / "source.edf"
        checkpoint.write_text("checkpoint fixture")
        edif.write_text("edif fixture")
        record_identity(checkpoint, edif, self.root)
        (self.root / "native_primitive_names.tcllist").write_text(self.encode(("a", "b", "ram", "ram/physical")))
        skipped = list(self.fields())
        skipped[0], skipped[1], skipped[2] = "1", "inf", ""
        (self.root / "native_timing_paths.tcllist").write_text(self.encode(self.fields()) + "\n" + self.encode(skipped) + "\n")
        (self.root / "native_timing_complete.txt").write_text("status=NATIVE_TIMING_PATH_EXPORT_PASS\nrequested_max_paths=10\nnative_path_count=2\nnative_clock_count=2\nvivado_version=2025.1\n"
                + f"checkpoint={checkpoint}\nedif={edif}\n")
        (self.root / "export_partition_timing.tcl").write_text("fixture exporter")
        result = finalize(checkpoint, edif, self.root, 10)
        self.assertTrue(result["available"])
        self.assertEqual(result["exported_paths"], 1)
        self.assertEqual(result["skipped_unconstrained"], 1)
        text = (self.root / "timing_paths.tsv").read_text()
        self.assertIn("#input_sha256=", text)
        self.assertIn('0\t-1.25\t10.000\tclock_a\tclock_b\t["a","ram","a","b"]', text)
        self.assertNotIn('"[""a""', text)  # Java consumes raw JSON, not CSV-quoted JSON.
        checkpoint.write_text("changed checkpoint")
        with self.assertRaisesRegex(ValueError, "changed during"):
            finalize(checkpoint, edif, self.root, 10)

    def test_unconstrained_source_reports_unavailable_without_invented_clocks(self):
        checkpoint, edif = self.root / "source.dcp", self.root / "source.edf"
        checkpoint.write_text("unconstrained checkpoint fixture")
        edif.write_text("source fixture")
        record_identity(checkpoint, edif, self.root)
        (self.root / "native_primitive_names.tcllist").write_text(self.encode(("a", "b", "ram", "ram/physical")))
        fields = list(self.fields())
        fields[1], fields[2] = "inf", ""
        (self.root / "native_timing_paths.tcllist").write_text(self.encode(fields) + "\n")
        (self.root / "native_timing_complete.txt").write_text("status=NATIVE_TIMING_PATH_EXPORT_PASS\nrequested_max_paths=1\nnative_path_count=1\nnative_clock_count=0\n"
                + f"checkpoint={checkpoint}\nedif={edif}\n")
        (self.root / "export_partition_timing.tcl").write_text("fixture")
        result = finalize(checkpoint, edif, self.root, 1)
        self.assertFalse(result["available"])
        self.assertEqual(result["exported_paths"], 0)
        self.assertEqual(result["skipped_unconstrained"], 1)
        self.assertEqual(result["native_clock_count"], 0)
        self.assertFalse(result["constraints_created"])


if __name__ == "__main__":
    unittest.main()
