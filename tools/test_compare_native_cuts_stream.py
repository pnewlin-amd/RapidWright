import csv
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import tkinter
import unittest

from compare_native_cuts_stream import compare_stream, prepare_case
from compare_partition_reference import mapping_rules, native_crossings

REPO = Path(__file__).resolve().parents[1]
SCRATCH = REPO / "build/partition-python-tests"
sys.path.insert(0, str(REPO / "test/resources/partition"))
from native_endpoint_reference import flatten


class NativeCutStreamTests(unittest.TestCase):
    def setUp(self):
        parent = SCRATCH
        parent.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=parent)
        self.root = Path(self.temporary.name).resolve()
        self.addCleanup(self.clean)
        self.tcl = tkinter.Tcl()
        self.primitives = self.root / "logical.tsv"
        self.primitives.write_text("name\tref_name\nsrc\tLUT1\nsink\tFDRE\nblock[0]/ram\tRAM32M16\nlogic {quoted}\tLUT1\nio_pad\tIOBUF\ng\tGND\n")
        self.records = [
            ("0", "constant", ("g/G", "sink/CE"), ("G", "CE"), ("OUT", "IN"), (), ()),
            ("1", "data [0]", ("src/O", "sink/D", "block[0]/ram/ADDRA[0]", "logic {quoted}/I0", "block[0]/ram/RAMD32/I"),
             ("O", "D", "ADDRA[0]", "I0", "I"), ("OUT", "IN", "IN", "IN", "IN"), (), ()),
            ("2", "clock", ("sink/C", "block[0]/ram/WCLK"), ("C", "WCLK"), ("IN", "IN"), ("clk_i",), ("IN",)),
            ("3", "bidirectional", ("io_pad/IO", "block[0]/ram/DOA[1]"), ("IO", "DOA[1]"), ("INOUT", "OUT"), ("top_io[3]",), ("INOUT",)),
            ("4", "input alias", ("src/I0", "src/I0"), ("I0", "I0"), ("IN", "IN"), ("data_in",), ("IN",)),
            ("5", "macro internal", ("block[0]/ram/RAMD32/O", "block[0]/ram/RAMS32/I"), ("O", "I"), ("OUT", "IN"), (), ())]
        self.bulk = self.root / "bulk.tcl"
        self.bulk.write_text(self.encode(self.records), encoding="utf-8")
        self.endpoints = self.root / "endpoints.tsv"
        flatten(self.bulk, self.primitives, self.endpoints, self.root / "flatten.json", expected_nets=6)
        self.histogram = self.root / "histogram.tsv"
        self.histogram.write_text("REF_NAME\tCOUNT\nLUT1\t2\nFDRE\t1\nRAM32M16\t1\nIOBUF\t1\nGND\t1\n")
        self.inputs = []
        mappings = ["top A\n", "top A\ntop/sink B\ntop/block[0] B\n",
                    "top A\ntop/sink B\ntop/block[0] C\n",
                    "top D\ntop/src A\ntop/sink B\ntop/block[0] C\n"]
        for index, text in enumerate(mappings, 1):
            mapping = self.root / f"mapping{index}.txt"
            mapping.write_text(text)
            expected = native_crossings(self.endpoints, self.primitives, mapping_rules(mapping))
            crossings = self.root / f"crossings{index}.tsv"
            with crossings.open("w", newline="") as handle:
                writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
                writer.writerow(["ENDPOINT_FINGERPRINT", "CANONICAL_NET", "DIRECTED_PARTITION_PAIRS"])
                for key, row in expected.items():
                    writer.writerow([key, row["name"], ",".join(row["pairs"])])
            self.inputs.append((f"k{index}", mapping, crossings, self.histogram))

    def clean(self):
        self.root.relative_to(SCRATCH.resolve())
        self.assertNotEqual(self.root, SCRATCH.resolve())
        self.temporary.cleanup()

    def encode(self, records):
        return "".join(self.tcl.call("format", "%s", tuple(record)) + "\n" for record in records)

    def cases(self):
        return [prepare_case(*row) for row in self.inputs]

    def test_four_maps_match_existing_independent_flatten_and_comparator(self):
        result = compare_stream(self.bulk, self.primitives, self.cases(), 6, owner_cache_entries=2)
        self.assertTrue(result["passed"])
        self.assertEqual(result["native_canonical_nets"], 6)
        self.assertEqual(result["constant_groups_excluded"], 1)
        self.assertLessEqual(result["owner_cache_entries_peak"], 2)
        self.assertEqual(result["cases"][0]["vivado_crossing_nets"], 0)
        for case in result["cases"]:
            self.assertEqual(case["missing_count"] + case["extra_count"] + case["pair_mismatch_count"], 0)

    def test_gzip_is_identical_to_plain_and_crc_truncation_rejected(self):
        packed = self.root / "bulk.gz"
        packed.write_bytes(gzip.compress(self.bulk.read_bytes(), mtime=0))
        result = compare_stream(packed, self.primitives, self.cases(), 6)
        self.assertTrue(result["passed"])
        self.assertEqual(result["raw_stream_sha256"], hashlib.sha256(self.bulk.read_bytes()).hexdigest())
        packed.write_bytes(packed.read_bytes()[:-8])
        with self.assertRaises((EOFError, OSError)):
            compare_stream(packed, self.primitives, self.cases(), 6)

    def test_incomplete_census_noncontiguous_ids_and_record_bound_fail(self):
        with self.assertRaisesRegex(ValueError, "completion count"):
            compare_stream(self.bulk, self.primitives, self.cases(), 7)
        bad = self.root / "bad.tcl"
        bad.write_text(self.encode([self.records[1]]))
        with self.assertRaisesRegex(ValueError, "contiguous"):
            compare_stream(bad, self.primitives, self.cases(), 1)
        with self.assertRaisesRegex(ValueError, "memory bound"):
            compare_stream(self.bulk, self.primitives, self.cases(), 6, max_record_bytes=8)

    def test_bad_properties_and_duplicate_crossing_fingerprint_fail(self):
        bad = self.root / "bad.tcl"
        bad.write_text(self.encode([("0", "n", ("src/O",), (), ("OUT",), (), ())]))
        with self.assertRaisesRegex(ValueError, "lengths differ"):
            compare_stream(bad, self.primitives, self.cases(), 1)
        duplicate = list(self.records[1]);duplicate[0] = "6"
        bad.write_text(self.encode(self.records + [tuple(duplicate)]))
        with self.assertRaisesRegex(ValueError, "Duplicate native crossing"):
            compare_stream(bad, self.primitives, self.cases(), 7)

    def test_missing_extra_pairs_and_histogram_fail_honestly(self):
        cases = self.cases()
        key = next(iter(cases[-1]["expected"]))
        cases[-1]["expected"][key]["pairs"] = ["Z->A"]
        cases[-2]["expected"].pop(next(iter(cases[-2]["expected"])))
        self.histogram.write_text("REF_NAME\tCOUNT\nLUT1\t99\n")
        result = compare_stream(self.bulk, self.primitives, cases, 6)
        self.assertFalse(result["passed"])
        self.assertGreater(result["cases"][-1]["pair_mismatch_count"], 0)
        self.assertGreater(result["cases"][-2]["extra_count"], 0)
        self.assertTrue(result["cases"][0]["primitive_histogram_mismatch"])

    def test_host_tcl_zlib_and_pipe_channels_roundtrip_unicode_lists(self):
        # Host Tcl proof only; native Vivado capability remains a separate gate.
        self.tcl.call("set", "argv", ())
        self.tcl.call("source", str(Path(__file__).with_name("export_partition_native_cuts.tcl")))
        self.tcl.call("set", "env(BIGFPGA_PYTHON)", sys.executable)
        text = self.encode([("0", "unicode ü [0]", ("src/O",), ("O",), ("OUT",), (), ())])
        for mode in ("zlib", "pipe"):
            target = self.root / (mode + ".gz.partial")
            response = self.tcl.call("rw_cut_open_gzip", str(target), mode)
            channel = self.tcl.call("dict", "get", response, "channel")
            try:
                self.tcl.call("puts", "-nonewline", channel, text)
            finally:
                self.tcl.call("close", channel)
            self.assertEqual(gzip.decompress(target.read_bytes()), text.encode("utf-8"))
            with self.assertRaises(tkinter.TclError):
                self.tcl.call("rw_cut_open_gzip", str(target), mode)

    def test_mock_native_producer_completes_alias_census_and_refuses_byte_overrun(self):
        # Pure host Tcl with fake design APIs: no Vivado/native process starts.
        self.tcl.call("set", "argv", ())
        self.tcl.call("source", str(Path(__file__).with_name("export_partition_native_cuts.tcl")))
        self.tcl.eval(r'''
            proc set_param {args} {}
            proc open_checkpoint {path} {set ::opened_fixture $path}
            proc current_design {} {return fixture_design}
            proc version {args} {return mock_only}
            proc get_nets {args} {
                if {[lsearch -exact $args -hierarchical] >= 0} {return {n0 n0_alias n1}}
                set anchor [lindex $args end]
                if {$anchor eq "src/O"} {return {n0 n0_alias}}
                return {n1}
            }
            proc get_pins {args} {
                set nets [lindex $args end]
                if {[lindex $nets 0] in {n0 n0_alias}} {return {src/O sink/D}}
                return {sink/C}
            }
            proc get_ports {args} {
                if {[lindex [lindex $args end] 0] eq "n1"} {return {clk_i}}
                return {}
            }
            proc get_property {property objects} {
                if {$objects eq "fixture_design"} {
                    if {$property eq "TOP"} {return fixture_top}
                    if {$property eq "PART"} {return fixture_part}
                }
                if {$property eq "NAME"} {return $objects}
                set result {}
                foreach object $objects {
                    if {$property eq "REF_PIN_NAME"} {lappend result [lindex [split $object /] end]}
                    if {$property eq "DIRECTION"} {lappend result [expr {$object eq "src/O" ? "OUT" : "IN"}]}
                }
                return $result
            }
        ''')
        checkpoint = self.root / "mock_source.dcp"
        checkpoint.write_bytes(b"mock only")
        out = self.root / "mock_export"
        self.tcl.call("rw_export_complete_native_cuts", str(checkpoint), str(out), "zlib", 2147483648, 60)
        rows = gzip.decompress((out / "native_net_endpoint_lists.tcl.gz").read_bytes()).decode().splitlines()
        self.assertEqual(len(rows), 2)
        self.assertEqual([self.tcl.splitlist(row)[0] for row in rows], ["0", "1"])
        text = (out / "native_endpoint_complete.txt").read_text()
        self.assertIn("canonical_nets=2", text)
        self.assertIn("hierarchical_segments=3", text)
        self.assertIn("all_enumerated_segments_covered=1", text)
        failed = self.root / "mock_budget_failure"
        with self.assertRaisesRegex(tkinter.TclError, "byte budget"):
            self.tcl.call("rw_export_complete_native_cuts", str(checkpoint), str(failed), "zlib", 1, 60)
        self.assertFalse((failed / "native_endpoint_complete.txt").exists())

    def test_cli_source_seals_and_nonzero_native_exit(self):
        checkpoint, edif = self.root / "source.dcp", self.root / "source.edf"
        checkpoint.write_bytes(b"fixture-checkpoint")
        edif.write_bytes(b"fixture-edif")
        packed = self.root / "bulk.gz"
        packed.write_bytes(gzip.compress(self.bulk.read_bytes(), mtime=0))
        sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
        receipt = self.root / "source_receipt.json"
        receipt.write_text(json.dumps({"status": "PASS", "checkpoint_sha256_before": sha(checkpoint), "checkpoint_sha256_after": sha(checkpoint),
            "exports_sha256": {"post_synth.edf": sha(edif), "logical_leaf_primitives.tsv": sha(self.primitives)}}))
        native = self.root / "native_complete.txt"
        native.write_text(f"status=COMPRESSED_NATIVE_ENDPOINT_EXPORT_PASS\ncheckpoint={checkpoint}\ncanonical_nets=6\nhierarchical_segments=6\ncovered_alias_segments=6\nall_enumerated_segments_covered=1\ngzip_bytes={packed.stat().st_size}\n")
        (self.root / "native_cut_export_script.tcl").write_bytes(Path(__file__).with_name("export_partition_native_cuts.tcl").read_bytes())
        (self.root / "native_cut_gzip_pipe.py").write_bytes(Path(__file__).with_name("native_cut_gzip_pipe.py").read_bytes())
        output = self.root / "result.json"
        command = [sys.executable, str(Path(__file__).with_name("compare_native_cuts_stream.py")),
            "--bulk", str(packed), "--logical-primitives", str(self.primitives), "--checkpoint", str(checkpoint),
            "--edif", str(edif), "--source-export-receipt", str(receipt), "--native-log", str(native),
            "--output", str(output), "--native-exit-code", "1"]
        for row in self.inputs:
            command += ["--case", *map(str, row)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(output.exists())
        command[command.index("--native-exit-code") + 1] = "0"
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(output.read_text())["status"], "PASS")
        # Protected receipt must not be overwritten by a repeat invocation.
        self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
        command[command.index("--output") + 1] = str(self.root / "changed_source.json")
        checkpoint.write_bytes(b"changed-checkpoint")
        changed = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(changed.returncode, 0)
        self.assertIn("Checkpoint differs", changed.stderr)
        self.assertFalse((self.root / "changed_source.json").exists())


if __name__ == "__main__":
    unittest.main()
