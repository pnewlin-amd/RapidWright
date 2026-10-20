"""Timing-source acceptance control, without invoking Vivado or the partitioner.

The command boundary supplies small completed source/native measurements; the
real frontend decides whether timing is trustworthy and whether to publish a
mapping. Recorded-native acceptance tests separately exercise the Java oracle.
"""
import importlib.util
import csv
import gzip
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("partition_timing_frontend", Path(__file__).with_name("partitioner.py"))
FRONTEND = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FRONTEND)
WORK = Path(__file__).resolve().parents[2] / "build/partition-python-tests"


class TimingAvailabilityAcceptance(unittest.TestCase):
    def setUp(self):
        WORK.joinpath("test_tmp").mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=WORK / "test_tmp")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "repo"
        engine = self.repo / "bin/com/xilinx/rapidwright/edif/partition"
        engine.mkdir(parents=True)
        (engine / "TimingPathModel.class").write_bytes(b"timing-support-fixture")
        (self.repo / "jars").mkdir()
        (self.repo / "tools").mkdir()
        # The frontend can fingerprint these command dependencies even though
        # their native execution is replaced at the run() boundary.
        for name in ("prepare_partition_input.py", "prepare_native_resource_cells.py",
                     "report_partition_resources.tcl", "export_partition_timing.py"):
            (self.repo / "tools" / name).write_text("fixture command boundary\n")
        self.source = self.root / "input.dcp"
        self.source.write_bytes(b"unchanged native checkpoint fixture")
        self.topology = self.root / "topology.json"
        self.topology.write_text(json.dumps({"fpgas": [{"id": "FPGA_A", "clb_luts": 10, "part": "xcvp1902"},
                                                       {"id": "FPGA_B", "clb_luts": 10, "part": "xcvp1902"}],
                                             "links": [{"u": "FPGA_A", "v": "FPGA_B"}]}))
        self.output = self.root / "accepted"
        self.partition_commands = []
        self.export_commands = []
        self.native_timing_calls = 0
        self.final_gate_calls = 0
        self.cache = "unavailable"
        self.export_result = "unavailable"
        self.explicit = None
        self.mutate_at_acceptance = None
        self.real_reporting = False
        self.report_action = None

    def report_boundary(self, output, state, staged_mapping, renderer, font):
        """These timing tests replace rendering; separate tests render real PNGs."""
        self.assertFalse((output / "mapping.txt").exists())
        if self.report_action:
            result = self.report_action(output, state, staged_mapping, renderer, font)
            if result is not None:
                return result
        manifest = output / "report_manifest.json"
        manifest.write_text(json.dumps({"source_files_sha256": {}}))
        return {manifest: FRONTEND.sha256(manifest)}

    def timing_reference(self, output, available, bad_hash=False):
        output.mkdir(parents=True, exist_ok=True)
        paths = output / "timing_paths.tsv"
        lines = ["#input_sha256=" + FRONTEND.sha256(self.netlist),
                 "#checkpoint_sha256=" + FRONTEND.sha256(self.checkpoint),
                 "#coverage=sampled_setup_paths",
                 "path_id\tslack_ns\trequirement_ns\tlaunch_clock\tcapture_clock\tcells_json"]
        if available:
            lines.append('1\t2.5\t10.0\tclk\tclk\t["a","b"]')
        paths.write_text("\n".join(lines) + "\n")
        metadata = {"status": "PASS", "available": available,
                    "input_sha256": FRONTEND.sha256(self.netlist),
                    "checkpoint_sha256": FRONTEND.sha256(self.checkpoint),
                    "requested_max_paths": 1000, "exported_paths": int(available),
                    "timing_paths": str(paths), "timing_paths_sha256": "0" * 64 if bad_hash else FRONTEND.sha256(paths),
                    "reason": "" if available else "No finite constrained native paths"}
        (output / "timing_reference.json").write_text(json.dumps(metadata))
        return paths

    def write_mapping_contract_fixture(self, candidate):
        """Synthetic producer artifacts for frontend control-flow tests only.

        This does not stand in for Java's source-netlist verification: those
        checks have dedicated Java tests. Here command boundaries already
        substitute every expensive producer and native execution.
        """
        contract = FRONTEND.mapping_contract_module()
        records = contract._records(candidate / "mapping.txt")
        root = "top"
        populations = {label: 0 for label in set(records.values())}
        constants = 0
        ancestors = contract._prefix_ancestors(records, root)
        (candidate / "cells.txt").write_text("".join(label + "\n" for label in sorted(set(records.values()))))
        with (candidate / "mapping_records.tsv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
            writer.writerow(contract.RECORD_HEADER)
            writer.writerows((source, destination, ancestors[source]) for source, destination in records.items())
        with gzip.open(candidate / "mapping_ownership.tsv.gz", "wt", newline="", encoding="utf-8") as stream:
            writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
            writer.writerow(contract.OWNERSHIP_HEADER)
            with self.inventory.open(newline="", encoding="utf-8") as inventory:
                for row in csv.DictReader(inventory, delimiter="\t"):
                    source = root + "/" + row["name"]
                    governing = source
                    while governing not in records:
                        governing = governing.rsplit("/", 1)[0]
                    constant = row["ref_name"] in ("GND", "VCC")
                    if constant:
                        constants += 1
                    else:
                        populations[records[governing]] += 1
                    writer.writerow((source, row["ref_name"], "LOCAL_CONSTANT" if constant else "FUNCTIONAL",
                                     "LOCAL_CONSTANT" if constant else records[governing], governing, records[governing]))
        # Connectivity generation is separately covered by the Java source
        # producer. Empty connection fixture data makes no hardware claim.
        with gzip.open(candidate / "mapping_connections.tsv.gz", "wt", encoding="utf-8") as stream:
            stream.write("\t".join(contract.CONNECTION_HEADER) + "\n")
        (candidate / "mapping_connections_manifest.json").write_text(json.dumps({
            "schema_version": 1, "status": "PRE_REGROUP_SOURCE_CONNECTIVITY_ONLY",
            "downstream_regroup_verification": "NOT_RUN", "source_root": root,
            "file": "mapping_connections.tsv.gz", "sha256": FRONTEND.sha256(candidate / "mapping_connections.tsv.gz"),
            "scope": contract.CONNECTION_SCOPE, "canonical_nets": 0, "hierarchy_aliases": 0,
            "endpoints": 0, "fixture": True}))
        report = {
            "schema_version": 1, "status": "PASS", "root_instance": root,
            "scope": contract.SOURCE_SCOPE, "downstream_regroup_verified": False,
            "record_count": len(records), "functional_leaf_count": sum(populations.values()),
            "local_constant_leaf_count": constants, "empty_hierarchy_count": 0,
            "functional_leaves_by_destination": populations,
            "input_sha256": FRONTEND.sha256(self.netlist), "mapping_sha256": FRONTEND.sha256(candidate / "mapping.txt"),
            "records_manifest_sha256": FRONTEND.sha256(candidate / "mapping_records.tsv"),
            "ownership_manifest_sha256": FRONTEND.sha256(candidate / "mapping_ownership.tsv.gz"),
            "connections_manifest_sha256": FRONTEND.sha256(candidate / "mapping_connections.tsv.gz"),
            "connections_receipt_sha256": FRONTEND.sha256(candidate / "mapping_connections_manifest.json")}
        report.update((flag, True) for flag in contract.SOURCE_PROOF_FLAGS)
        (candidate / "mapping_contract.json").write_text(json.dumps(report))

    def run_boundary(self, command, cwd, log):
        command = [str(value) for value in command]
        if any(value.endswith("prepare_partition_input.py") for value in command):
            self.export_commands.append(command)
            output = Path(command[command.index("--output") + 1])
            output.mkdir(parents=True)
            self.netlist, self.checkpoint, self.inventory = (output / name for name in
                ("source.edf", "source.dcp", "native_primitives.tsv"))
            self.netlist.write_bytes(b"native source EDIF fixture")
            self.checkpoint.write_bytes(self.source.read_bytes())
            self.inventory.write_text("name\tref_name\na\tLUT1\nb\tFDRE\n")
            (output / "metadata.json").write_text(json.dumps({"input_edif": str(self.netlist),
                "checkpoint": str(self.checkpoint), "native_primitives": str(self.inventory)}))
            if self.cache != "missing":
                self.timing_reference(output / "timing", self.cache != "unavailable", self.cache == "bad_hash")
            if self.explicit:
                text = ("#input_sha256=" + FRONTEND.sha256(self.netlist) + "\n#checkpoint_sha256="
                        + ("f" * 64 if self.explicit == "wrong_checkpoint" else FRONTEND.sha256(self.checkpoint))
                        + '\npath_id\tslack_ns\trequirement_ns\tlaunch_clock\tcapture_clock\tcells_json\n'
                          '1\t2.5\t10.0\tclk\tclk\t["a","b"]\n')
                self.explicit_file.write_text(text)
        elif any(value.endswith("export_partition_timing.py") for value in command):
            self.native_timing_calls += 1
            if self.export_result == "failure":
                raise RuntimeError("Native timing command fixture failed")
            output = Path(command[command.index("--output") + 1])
            self.timing_reference(output, self.export_result != "unavailable", self.export_result == "bad_hash")
        elif FRONTEND.MAIN + "Partitioner" in command:
            self.partition_commands.append(command)
            candidate = Path(command[command.index("--part_dir") + 1])
            mapping = candidate / "mapping.txt"
            mapping.write_text("top/a FPGA_A\ntop FPGA_B\n")
            (candidate / "run_provenance.json").write_text(json.dumps({
                "mapping_sha256": FRONTEND.sha256(mapping),
                "input_sha256": FRONTEND.sha256(self.netlist), "partitions": 2}))
            (candidate / "frontier.tsv").write_text("vertex\tpath\tlogical_luts\tprimary_weight\n1\ta\t1\t1\n2\tb\t1\t1\n")
            (candidate / "final_assignment.part").write_text("0\n1\n")
            quality = {"complete": "true", "resource_coverage_complete": "true",
                       "before_transfers": "0", "after_transfers": "0",
                       "before_topology_distance": "0", "after_topology_distance": "0",
                       "after_max_register_free_crossings": "0", "after_endpoint_excess_crossings": "0",
                       "after_transit_primitives": "0"}
            if "--timing_paths" in command:
                quality.update(timing_cost_after="1.0", timing_paths_scored="1")
            (candidate / "hardware_quality.txt").write_text("".join(f"{key}={value}\n" for key, value in quality.items()))
            self.write_mapping_contract_fixture(candidate)
        elif any(value.endswith("prepare_native_resource_cells.py") for value in command):
            output = Path(command[command.index("--output") + 1])
            output.mkdir(parents=True)
            (output / "vivado_resources.tsv").write_text("partition\tclb_luts\nDESIGN\t8\nFPGA_A\t4\nFPGA_B\t4\n")
        elif "-tclargs" in command:
            index = command.index("-tclargs")
            checkpoint, mapping, output = map(Path, command[index + 1:index + 4])
            completion = {"status": "VIVADO_PARTITION_RESOURCE_MAPPING_PASS",
                          "checkpoint_sha256": FRONTEND.sha256(checkpoint),
                          "mapping_sha256": FRONTEND.sha256(mapping)}
            (output / "native_complete.txt").write_text("".join(f"{key}={value}\n" for key, value in completion.items()))
            (output / "provenance.txt").write_text("".join(f"{key}={value}\n" for key, value in completion.items()))
            (output / "source_mapping.txt").write_bytes(mapping.read_bytes())
        else:
            self.assertIn(FRONTEND.MAIN + "VivadoResourceOracle", command)
            index = command.index(FRONTEND.MAIN + "VivadoResourceOracle")
            table, mapping, output = map(Path, command[index + 1:index + 4])
            # The real Oracle copies the native snapshot used for atomic final
            # publication; it does not invent fresh assignments at this stage.
            (output / "source_mapping.txt").write_bytes((table.parent / "source_mapping.txt").read_bytes())
            if log.name == "acceptance.log":
                self.final_gate_calls += 1
                self.assertFalse((self.output / "mapping.txt").exists())
                self.assertIn("--lut-caps", command)
                if self.mutate_at_acceptance:
                    self.mutate_at_acceptance()

    def execute(self, *flags):
        arguments = ["--input", str(self.source), "--system-topology", str(self.topology),
                     "--output", str(self.output), "--quality", "fast", "--iterations", "1", "--starts", "1"]
        arguments += list(flags)
        if self.explicit:
            self.explicit_file = self.root / "explicit_paths.tsv"
            arguments += ["--timing-paths", str(self.explicit_file)]
        args = FRONTEND.parser().parse_args(arguments)
        from contextlib import ExitStack
        with ExitStack() as stack:
            if not self.real_reporting:
                stack.enter_context(patch.object(FRONTEND, "prepare_reporting", return_value=(
                    SimpleNamespace(REPORT_FILES=("lut_balance.png", "io_cut_balance.png", "fpga_connectivity.png",
                                                   "report.html", "report_data.json", "report_manifest.json")), None, {}, {})))
                stack.enter_context(patch.object(FRONTEND, "publish_partition_reports", self.report_boundary))
            stack.enter_context(patch.object(FRONTEND, "REPO", self.repo))
            stack.enter_context(patch.object(FRONTEND, "find_java", return_value="java"))
            stack.enter_context(patch.object(FRONTEND, "find_vivado", return_value="vivado", create=True))
            stack.enter_context(patch.object(FRONTEND, "resolve_backend", return_value=("mtkahypar", None), create=True))
            stack.enter_context(patch.object(FRONTEND, "run", self.run_boundary))
            FRONTEND.execute(args)
        return json.loads((self.output / "STATUS.json").read_text())

    def assert_rejected_before_partition(self, *flags, error="unavailable"):
        with self.assertRaisesRegex((ValueError, RuntimeError), error):
            self.execute(*flags)
        self.assertEqual(self.partition_commands, [])
        self.assertEqual(self.final_gate_calls, 0)
        self.assertFalse((self.output / "mapping.txt").exists())
        self.assertEqual(json.loads((self.output / "STATUS.json").read_text())["status"], "FAIL")

    def test_auto_unavailable_native_sta_publishes_only_structurally_guided_mapping(self):
        status = self.execute()
        self.assertEqual(status["status"], "PASS")
        self.assertEqual(status["timing_guidance"], "structural_fallback")
        self.assertIn("No finite constrained", status["timing_unavailable_reason"])
        self.assertEqual(self.native_timing_calls, 0)  # Reuse the completed unavailable sample.
        self.assertNotIn("--timing_paths", self.partition_commands[0])
        self.assertEqual(self.final_gate_calls, 1)
        self.assertTrue((self.output / "mapping.txt").is_file())

    def test_required_unavailable_sta_fails_before_partition_and_publication(self):
        self.assert_rejected_before_partition("--timing", "required")

    def test_explicit_transport_delay_requires_native_paths(self):
        self.assert_rejected_before_partition("--extra-delay-ns", "1.25")

    def test_auto_native_export_failure_falls_back_with_reason(self):
        self.cache, self.export_result = "missing", "failure"
        status = self.execute()
        self.assertEqual(status["timing_guidance"], "structural_fallback")
        self.assertIn("Native timing export failed", status["timing_unavailable_reason"])
        self.assertEqual(self.native_timing_calls, 1)
        self.assertNotIn("--timing_paths", self.partition_commands[0])

    def test_required_native_export_failure_is_not_structural_success(self):
        self.cache, self.export_result = "missing", "failure"
        self.assert_rejected_before_partition("--timing", "required", error="Native timing command")

    def test_zero_timing_weight_disables_native_sampling_even_with_required_mode(self):
        self.cache, self.export_result = "missing", "failure"
        status = self.execute("--timing", "required", "--timing-weight", "0")
        self.assertEqual(status["timing_guidance"], "disabled")
        self.assertEqual(self.native_timing_calls, 0)
        export = self.export_commands[0]
        self.assertEqual(export[export.index("--timing-max-paths") + 1], "0")
        self.assertNotIn("--timing_paths", self.partition_commands[0])

    def test_out_of_range_sample_limit_fails_before_native_source_preparation(self):
        self.assert_rejected_before_partition("--timing-max-paths", "100001", error="timing-max-paths|100000")
        self.assertEqual(self.export_commands, [])

    def test_timing_metadata_cannot_claim_a_changed_tsv_as_native_sta(self):
        self.cache, self.export_result = "missing", "bad_hash"
        self.assert_rejected_before_partition(error="hash|SHA|timing.*bound|timing.*invalid")

    def test_bad_cached_timing_hash_triggers_native_refresh_before_use(self):
        self.cache, self.export_result = "bad_hash", "unavailable"
        status = self.execute()
        self.assertEqual(self.native_timing_calls, 1)
        self.assertEqual(status["timing_guidance"], "structural_fallback")
        self.assertNotIn("--timing_paths", self.partition_commands[0])

    def test_explicit_paths_from_different_checkpoint_are_rejected(self):
        self.explicit = "wrong_checkpoint"
        self.assert_rejected_before_partition(error="checkpoint|source")

    def test_valid_native_sample_is_copied_and_hash_bound(self):
        self.cache = "available"
        status = self.execute()
        paths = self.output / "inputs/timing_paths.tsv"
        self.assertEqual(status["timing_guidance"], "sampled_source_STA")
        self.assertEqual(status["timing_consumed_paths"], 1)
        self.assertEqual(status["timing_paths_sha256"], FRONTEND.sha256(paths))
        command = self.partition_commands[0]
        self.assertEqual(command[command.index("--timing_paths") + 1], str(paths))
        self.assertEqual(self.final_gate_calls, 1)

    def test_timing_snapshot_mutation_after_solver_cannot_publish_mapping(self):
        self.cache = "available"
        self.mutate_at_acceptance = lambda: (self.output / "inputs/timing_paths.tsv").write_text("changed after solver\n")
        with self.assertRaisesRegex(ValueError, "changed|snapshot|hash|SHA"):
            self.execute()
        self.assertEqual(self.final_gate_calls, 1)
        self.assertFalse((self.output / "mapping.txt").exists())
        self.assertEqual(json.loads((self.output / "STATUS.json").read_text())["status"], "FAIL")

    def test_topology_snapshot_mutation_after_solver_cannot_publish_mapping(self):
        self.mutate_at_acceptance = lambda: (self.output / "system.graph").write_text("changed topology\n")
        with self.assertRaisesRegex(ValueError, "changed|snapshot|hash|SHA"):
            self.execute()
        self.assertEqual(self.final_gate_calls, 1)
        self.assertFalse((self.output / "mapping.txt").exists())

    def test_increasing_caps_during_final_gate_cannot_relax_accepted_request(self):
        self.mutate_at_acceptance = lambda: (self.output / "native_lut_caps.tsv").write_text(
            "partition\tclb_luts\nFPGA_A\t100\nFPGA_B\t100\n")
        with self.assertRaisesRegex(ValueError, "changed|snapshot|hash|SHA"):
            self.execute()
        self.assertEqual(self.final_gate_calls, 1)
        self.assertFalse((self.output / "mapping.txt").exists())
        self.assertEqual(json.loads((self.output / "STATUS.json").read_text())["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
