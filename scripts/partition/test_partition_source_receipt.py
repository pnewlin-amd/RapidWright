"""Receipt consistency in the real frontend, with expensive command fixtures."""
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import test_partition_timing_availability as AVAILABILITY

FRONTEND = AVAILABILITY.FRONTEND


class SourcePairReceiptAcceptance(unittest.TestCase):
    def setUp(self):
        fixture = AVAILABILITY.TimingAvailabilityAcceptance(methodName="runTest")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.fixture = fixture
        self.auto_source = fixture.source
        source = fixture.root / "external"
        source.mkdir()
        fixture.source = fixture.netlist = source / "post_synth.edf"
        fixture.netlist.write_bytes(b"exported EDIF fixture")
        fixture.checkpoint = self.auto_source
        fixture.inventory = source / "leaf_primitives.tsv"
        fixture.inventory.write_text("name\tref_name\na\tLUT1\nb\tFDRE\n")
        self.receipt = source / "export_receipt.json"
        self.receipt_payload = {
            "status": "PASS", "checkpoint_sha256_before": FRONTEND.sha256(fixture.checkpoint),
            "checkpoint_sha256_after": FRONTEND.sha256(fixture.checkpoint),
            "exports_sha256": {fixture.netlist.name: FRONTEND.sha256(fixture.netlist),
                               fixture.inventory.name: FRONTEND.sha256(fixture.inventory)}}
        self.receipt.write_text(json.dumps(self.receipt_payload))
        self.events = []
        self.after_command = None
        self.unclean = False
        original = fixture.run_boundary

        def boundary(command, cwd, log):
            command = [str(value) for value in command]
            self.events.append(log.name)
            original(command, cwd, log)
            if self.unclean and FRONTEND.MAIN + "Partitioner" in command:
                candidate = Path(command[command.index("--part_dir") + 1])
                values = dict(line.split("=", 1) for line in
                              (candidate / "hardware_quality.txt").read_text().splitlines())
                values.update(after_max_register_free_crossings="2", after_endpoint_excess_crossings="1",
                              after_transit_primitives="1")
                (candidate / "hardware_quality.txt").write_text(
                    "".join(key + "=" + value + "\n" for key, value in values.items()))
            if self.after_command:
                self.after_command(command, log)

        fixture.run_boundary = boundary

    def execute(self, *extra):
        return self.fixture.execute("--source-checkpoint", str(self.fixture.checkpoint),
            "--native-primitives", str(self.fixture.inventory), "--native-session", "off", "--timing", "off", *extra)

    def assert_fail_closed(self):
        self.assertFalse((self.fixture.output / "mapping.txt").exists())
        self.assertEqual(json.loads((self.fixture.output / "STATUS.json").read_text())["status"], "FAIL")

    def mutate(self, path=None):
        target = path or self.receipt
        # Even harmless semantic reformatting must violate the captured bytes.
        target.write_bytes(target.read_bytes() + b"\n ")

    def test_legacy_receipt_is_pinned_and_explicitly_not_producer_verified(self):
        original = self.receipt.read_bytes()
        status = self.execute()
        association = status["source_pair_association"]
        self.assertEqual(association["mode"], "supplied_manifest_legacy")
        self.assertIs(association["producer_evidence_verified"], False)
        self.assertNotIn("actual_native_exit_code", association)
        snapshot = self.fixture.output / "inputs/source_pair_receipt.json"
        self.assertEqual(snapshot.read_bytes(), original)
        self.assertEqual(association["original_receipt_sha256"], FRONTEND.sha256(self.receipt))
        self.assertEqual(association["receipt_snapshot_sha256"], FRONTEND.sha256(snapshot))
        provenance = self.fixture.output / "source_pair_provenance.json"
        self.assertEqual(json.loads(provenance.read_text()), association)
        self.assertEqual(status["source_pair_provenance_sha256"], FRONTEND.sha256(provenance))
        self.assertEqual(status["input_snapshot_sha256"][str(snapshot.relative_to(self.fixture.output))], FRONTEND.sha256(snapshot))
        self.assertEqual(status["input_snapshot_sha256"]["source_pair_provenance.json"], FRONTEND.sha256(provenance))
        self.assertEqual(self.fixture.export_commands, [])
        self.assertEqual(self.fixture.native_timing_calls, 0)
        self.assertEqual((self.fixture.output / "run_provenance.json").read_bytes(),
                         (self.fixture.output / "candidate_00/run_provenance.json").read_bytes())

    def test_one_read_byte_snapshot_is_both_parsed_and_preserved_including_bom(self):
        self.receipt.write_bytes(b"\xef\xbb\xbf" + self.receipt.read_bytes())
        original = self.receipt.read_bytes()
        reads = []
        real_read = Path.read_bytes

        def read(path):
            if path == self.receipt:
                reads.append(path)
            return real_read(path)

        with patch.object(Path, "read_bytes", read):
            self.execute()
        self.assertEqual(len(reads), 1)
        self.assertEqual((self.fixture.output / "inputs/source_pair_receipt.json").read_bytes(), original)

    def test_unrecognized_producer_claim_does_not_upgrade_legacy_manifest_trust(self):
        self.receipt_payload.update(producer_verified=True, actual_native_exit_code=0, producer_schema="unknown")
        self.receipt.write_text(json.dumps(self.receipt_payload))
        association = self.execute()["source_pair_association"]
        self.assertEqual(association["mode"], "supplied_manifest_legacy")
        self.assertIs(association["producer_evidence_verified"], False)

    def test_older_single_checkpoint_digest_schema_remains_legacy_and_accepted(self):
        self.receipt_payload.pop("checkpoint_sha256_before")
        self.receipt_payload.pop("checkpoint_sha256_after")
        self.receipt_payload["checkpoint_sha256"] = FRONTEND.sha256(self.fixture.checkpoint)
        self.receipt.write_text(json.dumps(self.receipt_payload))
        association = self.execute()["source_pair_association"]
        self.assertEqual(association["mode"], "supplied_manifest_legacy")
        self.assertIs(association["producer_evidence_verified"], False)

    def test_payload_digest_mismatch_still_rejects_before_java_or_native(self):
        self.fixture.netlist.write_bytes(b"same named cells with changed wiring fixture")
        with self.assertRaisesRegex(ValueError, "receipt.*bind"):
            self.execute()
        self.assertEqual(self.events, [])
        self.assert_fail_closed()

    def test_receipt_change_during_capture_is_rejected_before_java(self):
        real_write = Path.write_bytes

        def write(path, data):
            result = real_write(path, data)
            if path.name == "source_pair_receipt.json":
                self.mutate()
            return result

        with patch.object(Path, "write_bytes", write):
            with self.assertRaisesRegex(ValueError, "snapshot changed"):
                self.execute()
        self.assertEqual(self.events, [])
        self.assert_fail_closed()

    def test_receipt_change_after_capture_fails_before_first_java(self):
        real_heap = FRONTEND.automatic_java_heap

        def heap(*args):
            self.mutate()
            return real_heap(*args)

        with patch.object(FRONTEND, "automatic_java_heap", side_effect=heap):
            with self.assertRaisesRegex(ValueError, "snapshot changed"):
                self.execute()
        self.assertEqual(self.events, [])
        self.assert_fail_closed()

    def test_receipt_change_after_java_fails_before_native_preparation(self):
        self.after_command = lambda command, log: self.mutate() if log.name == "partition.log" else None
        with self.assertRaisesRegex(ValueError, "snapshot changed"):
            self.execute()
        self.assertEqual(self.events, ["partition.log"])
        self.assert_fail_closed()

    def test_snapshot_change_after_native_preparation_fails_before_vivado(self):
        self.after_command = lambda command, log: self.mutate(
            self.fixture.output / "inputs/source_pair_receipt.json") if log.name == "prepare_native.log" else None
        with self.assertRaisesRegex(ValueError, "snapshot changed"):
            self.execute()
        self.assertEqual(self.events, ["partition.log", "prepare_native.log"])
        self.assert_fail_closed()

    def test_receipt_change_during_native_evaluation_fails_before_oracle(self):
        self.after_command = lambda command, log: self.mutate() if log.name == "native.log" else None
        with self.assertRaisesRegex(ValueError, "snapshot changed"):
            self.execute()
        self.assertNotIn("oracle.log", self.events)
        self.assertNotIn("acceptance.log", self.events)
        self.assert_fail_closed()

    def test_receipt_change_before_second_java_cannot_publish_first_incumbent(self):
        self.unclean = True
        real_feedback = FRONTEND.feedback

        def feedback(*args, **kwargs):
            result = real_feedback(*args, **kwargs)
            self.mutate()
            return result

        with patch.object(FRONTEND, "feedback", side_effect=feedback):
            with self.assertRaisesRegex(ValueError, "snapshot changed"):
                self.execute("--iterations", "2")
        self.assertEqual(self.events.count("partition.log"), 1)
        self.assertEqual(self.events.count("native.log"), 1)
        self.assertNotIn("acceptance.log", self.events)
        self.assert_fail_closed()

    def test_receipt_change_during_final_native_gate_cannot_publish(self):
        self.fixture.mutate_at_acceptance = self.mutate
        with self.assertRaisesRegex(ValueError, "snapshot changed"):
            self.execute()
        self.assertIn("acceptance.log", self.events)
        self.assert_fail_closed()

    def test_receipt_change_during_final_report_copy_cannot_publish(self):
        real_copy = FRONTEND.shutil.copy2

        def copy(source, target, *args, **kwargs):
            result = real_copy(source, target, *args, **kwargs)
            if Path(target) == self.fixture.output / "hardware_quality.txt":
                self.mutate()
            return result

        with patch.object(FRONTEND.shutil, "copy2", side_effect=copy):
            with self.assertRaisesRegex(ValueError, "snapshot changed"):
                self.execute()
        self.assertIn("acceptance.log", self.events)
        self.assert_fail_closed()

    def test_source_pair_provenance_mutation_cannot_change_trust_at_publication(self):
        self.fixture.mutate_at_acceptance = lambda: self.mutate(self.fixture.output / "source_pair_provenance.json")
        with self.assertRaisesRegex(ValueError, "snapshot changed"):
            self.execute()
        self.assert_fail_closed()

    def test_write_time_provenance_mutation_is_not_blessed_by_initial_capture(self):
        real_write = Path.write_bytes
        real_text = Path.write_text

        def corrupt(path, payload):
            if path.name == "source_pair_provenance.json":
                forged = json.loads(payload.decode("utf-8"))
                forged.update(mode="automatic_native_export", producer_evidence_verified=True)
                real_write(path, json.dumps(forged).encode("utf-8"))

        def write(path, payload):
            result = real_write(path, payload)
            corrupt(path, payload)
            return result

        def text(path, payload, *args, **kwargs):
            result = real_text(path, payload, *args, **kwargs)
            corrupt(path, payload.encode("utf-8"))
            return result

        with patch.object(Path, "write_bytes", write), patch.object(Path, "write_text", text):
            with self.assertRaisesRegex(ValueError, "snapshot changed"):
                self.execute()
        self.assertEqual(self.events, [])
        self.assert_fail_closed()

    def test_automatic_dcp_flow_records_observed_prepare_process_without_external_receipt(self):
        self.fixture.source = self.auto_source
        status = self.fixture.execute("--native-session", "off", "--timing", "off")
        association = status["source_pair_association"]
        self.assertEqual(association["mode"], "automatic_native_export")
        self.assertEqual(association["prepare_process_exit_code"], 0)
        self.assertIn(str(self.auto_source), association["prepare_command"])
        self.assertTrue(any(value.endswith("prepare_partition_input.py") for value in association["prepare_command"]))
        self.assertNotIn("original_receipt_sha256", association)
        self.assertNotIn("actual_native_exit_code", association)
        self.assertEqual(len(self.fixture.export_commands), 1)
        self.assertEqual(json.loads((self.fixture.output / "source_pair_provenance.json").read_text()), association)


if __name__ == "__main__":
    unittest.main()
