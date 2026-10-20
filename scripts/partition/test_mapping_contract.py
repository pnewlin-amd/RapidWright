"""Ordered extraction and fail-closed publication contract regressions."""
import csv
import gzip
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("mapping_contract_under_test", Path(__file__).with_name("mapping_contract.py"))
CONTRACT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTRACT)
WORK = Path(__file__).resolve().parents[2] / "build/partition-python-tests/test_tmp"


class OrderedMappingContract(unittest.TestCase):
    def setUp(self):
        WORK.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=WORK)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.mapping = self.root / "mapping.txt"
        self.cells = self.root / "cells.txt"
        self.inventory = self.root / "native_primitives.tsv"
        self.inventory.write_text("name\tref_name\ncluster/helper\tFDRE\ncluster/compute\tLUT2\ncluster/local\tFDRE\nlocal\tFDRE\nGND\tGND\n")

    def write(self, text, cells=None):
        self.mapping.write_text(text)
        if cells is None:
            cells = sorted({line.split()[1] for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")})
        self.cells.write_text("".join(label + "\n" for label in cells))

    def validate(self, **kwargs):
        return CONTRACT.validate_mapping(self.mapping, self.cells, self.inventory, **kwargs)

    def test_parent_only_and_local_constants_are_inherited(self):
        self.write("top FPGA_1\n")
        audit = self.validate(expected_destinations={"FPGA_1"})
        self.assertEqual(audit["record_count"], 1)
        self.assertEqual(audit["native_constant_rows"], 1)

    def test_one_remote_child_and_explicit_residual_root(self):
        self.write("top/cluster FPGA_2\ntop FPGA_1\n")
        self.assertEqual(self.validate()["record_count"], 2)

    def test_nested_return_to_root_is_preserved(self):
        self.write("top/cluster/helper FPGA_1\ntop/cluster/compute FPGA_3\ntop/cluster FPGA_2\ntop FPGA_1\n")
        self.assertEqual(self.validate()["record_count"], 4)

    def test_root_first_is_rejected_even_with_same_longest_prefix_ownership(self):
        self.write("top FPGA_1\ntop/cluster FPGA_2\n")
        with self.assertRaisesRegex(ValueError, "root.*last"):
            self.validate()

    def test_internal_parent_before_child_is_rejected(self):
        self.write("top/cluster FPGA_2\ntop/cluster/helper FPGA_1\ntop FPGA_1\n")
        with self.assertRaisesRegex(ValueError, "descendants.*precede"):
            self.validate()

    def test_same_nearest_ancestor_destination_is_rejected(self):
        self.write("top/cluster/helper FPGA_2\ntop/cluster FPGA_2\ntop FPGA_1\n")
        with self.assertRaisesRegex(ValueError, "redundant.*nearest"):
            self.validate()

    def test_matching_distant_root_does_not_make_nested_exception_redundant(self):
        self.write("top/cluster/helper FPGA_1\ntop/cluster FPGA_2\ntop FPGA_1\n")
        self.validate()

    def test_source_path_must_exist_in_native_census_hierarchy(self):
        self.write("top/missing_generate[3].has_snake FPGA_2\ntop FPGA_1\n")
        with self.assertRaisesRegex(ValueError, "absent.*native"):
            self.validate()

    def test_square_brackets_and_generate_dots_are_literal(self):
        self.inventory.write_text("name\tref_name\ncounters[0].no_snake/reg[2]\tFDRE\nlocal\tFDRE\n")
        self.write("top/counters[0].no_snake FPGA_2\ntop FPGA_1\n")
        self.validate()
        self.write("top/counters[0].has_snake FPGA_2\ntop FPGA_1\n")
        with self.assertRaisesRegex(ValueError, "absent"):
            self.validate()

    def test_gnd_and_vcc_explicit_records_are_rejected_by_primitive_type(self):
        for reference in ("GND", "VCC"):
            with self.subTest(reference=reference):
                self.inventory.write_text("name\tref_name\nunrelated_constant_name\t" + reference + "\nlocal\tFDRE\n")
                self.write("top/unrelated_constant_name FPGA_2\ntop FPGA_1\n")
                with self.assertRaisesRegex(ValueError, "explicit constant"):
                    self.validate()

    def test_comments_and_blank_lines_are_parsed_without_affecting_order(self):
        self.write("# Whole line comment\n\n top/cluster FPGA_2\n\n# Root residual\ntop FPGA_1\n")
        self.validate()

    def test_forbidden_record_forms(self):
        cases = {
            "one token": "top/cluster\ntop FPGA_1\n",
            "inline comment": "top/cluster FPGA_2 # comment\ntop FPGA_1\n",
            "physical board": "top/cluster C01_S01_E\ntop FPGA_1\n",
            "quotes": '\"top/cluster\" FPGA_2\ntop FPGA_1\n',
            "shell escaping": "top/cluster\\[0\\] FPGA_2\ntop FPGA_1\n",
            "star": "top/* FPGA_2\ntop FPGA_1\n",
            "question wildcard": "top/cluste? FPGA_2\ntop FPGA_1\n",
            "same duplicate": "top/cluster FPGA_2\ntop/cluster FPGA_2\ntop FPGA_1\n",
            "different duplicate": "top/cluster FPGA_2\ntop/cluster FPGA_3\ntop FPGA_1\n",
            "missing root": "top/cluster FPGA_2\ntop/local FPGA_1\n",
            "single nested source without root": "top/cluster FPGA_2\n",
            "mixed root": "other/cluster FPGA_2\ntop FPGA_1\n",
        }
        for name, text in cases.items():
            with self.subTest(name=name):
                self.mapping.write_text(text)
                self.cells.write_text("FPGA_1\nFPGA_2\n")
                with self.assertRaises(ValueError):
                    self.validate()

    def test_cells_must_agree_uniquely_with_exact_selected_destination_set(self):
        self.write("top/cluster FPGA_2\ntop FPGA_1\n")
        for text in ("FPGA_1\n", "FPGA_1\nFPGA_2\nFPGA_2\n", "C01_S01_E\nFPGA_2\n", "FPGA_1 FPGA_2\n"):
            with self.subTest(cells=text):
                self.cells.write_text(text)
                with self.assertRaises(ValueError):
                    self.validate()
        self.cells.write_text("FPGA_1\nFPGA_2\n")
        with self.assertRaisesRegex(ValueError, "selected topology"):
            self.validate(expected_destinations={"FPGA_1", "FPGA_2", "FPGA_3"})


class SourceBoundContractBundle(unittest.TestCase):
    setUp = OrderedMappingContract.setUp
    write = OrderedMappingContract.write
    validate = OrderedMappingContract.validate

    def bundle(self):
        self.write("top/cluster/helper FPGA_1\ntop/cluster/compute FPGA_3\ntop/cluster FPGA_2\ntop FPGA_1\n")
        records = CONTRACT._records(self.mapping)
        with (self.root / "mapping_records.tsv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
            writer.writerow(CONTRACT.RECORD_HEADER)
            ancestors = CONTRACT._prefix_ancestors(records, "top")
            writer.writerows((source, target, ancestors[source]) for source, target in records.items())
        with gzip.open(self.root / "mapping_ownership.tsv.gz", "wt", newline="", encoding="utf-8") as stream:
            writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
            writer.writerow(CONTRACT.OWNERSHIP_HEADER)
            for name, reference, owner, governing in (
                    ("cluster/helper", "FDRE", "FPGA_1", "top/cluster/helper"),
                    ("cluster/compute", "LUT2", "FPGA_3", "top/cluster/compute"),
                    ("cluster/local", "FDRE", "FPGA_2", "top/cluster"),
                    ("local", "FDRE", "FPGA_1", "top"), ("GND", "GND", "LOCAL_CONSTANT", "top")):
                writer.writerow(("top/" + name, reference, "LOCAL_CONSTANT" if owner == "LOCAL_CONSTANT" else "FUNCTIONAL",
                                 owner, governing, owner))
        with gzip.open(self.root / "mapping_connections.tsv.gz", "wt", encoding="utf-8") as stream:
            stream.write("\t".join(CONTRACT.CONNECTION_HEADER) + "\n")
        (self.root / "mapping_connections_manifest.json").write_text(json.dumps({
            "schema_version": 1, "status": "PRE_REGROUP_SOURCE_CONNECTIVITY_ONLY",
            "downstream_regroup_verification": "NOT_RUN", "file": "mapping_connections.tsv.gz",
            "sha256": CONTRACT.sha256(self.root / "mapping_connections.tsv.gz"),
            "source_root": "top", "scope": CONTRACT.CONNECTION_SCOPE,
            "canonical_nets": 0, "hierarchy_aliases": 0, "endpoints": 0}))
        report = {"schema_version": 1, "status": "PASS", "root_instance": "top", "input_sha256": "a" * 64,
                  "scope": CONTRACT.SOURCE_SCOPE, "downstream_regroup_verified": False,
                  "record_count": len(records), "functional_leaf_count": 4, "local_constant_leaf_count": 1,
                  "empty_hierarchy_count": 0,
                  "functional_leaves_by_destination": {"FPGA_1": 2, "FPGA_2": 1, "FPGA_3": 1},
                  "mapping_sha256": CONTRACT.sha256(self.mapping),
                  "records_manifest_sha256": CONTRACT.sha256(self.root / "mapping_records.tsv"),
                  "ownership_manifest_sha256": CONTRACT.sha256(self.root / "mapping_ownership.tsv.gz"),
                  "connections_manifest_sha256": CONTRACT.sha256(self.root / "mapping_connections.tsv.gz"),
                  "connections_receipt_sha256": CONTRACT.sha256(self.root / "mapping_connections_manifest.json")}
        report.update((flag, True) for flag in CONTRACT.SOURCE_PROOF_FLAGS)
        (self.root / "mapping_contract.json").write_text(json.dumps(report))
        return report

    def validate_bundle(self):
        return CONTRACT.validate_bundle(self.root, self.inventory, "a" * 64, {"FPGA_1", "FPGA_2", "FPGA_3"})

    def test_exact_source_report_and_manifest_populations_are_required(self):
        self.bundle()
        audit = self.validate_bundle()
        self.assertEqual(audit["functional_leaf_populations"], {"FPGA_1": 2, "FPGA_2": 1, "FPGA_3": 1})
        self.assertEqual(audit["local_constant_count"], 1)
        self.assertEqual(audit["ancestry_source"], "source_bound_EDIF_manifest")

    def test_all_producer_artifacts_are_required(self):
        for name in CONTRACT.REQUIRED_ARTIFACTS:
            with self.subTest(missing=name):
                self.bundle()
                (self.root / name).unlink()
                with self.assertRaisesRegex(ValueError, "required producer artifact"):
                    self.validate_bundle()

    def test_source_mapping_or_manifest_mutations_cannot_be_blessed(self):
        for name in ("mapping.txt", "mapping_records.tsv", "mapping_ownership.tsv.gz"):
            with self.subTest(mutation=name):
                self.bundle()
                path = self.root / name
                path.write_bytes(path.read_bytes() + b"\n")
                with self.assertRaisesRegex(ValueError, "bind|manifest hash"):
                    self.validate_bundle()
        report = self.bundle()
        report["input_sha256"] = "b" * 64
        (self.root / "mapping_contract.json").write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, "bind this source"):
            self.validate_bundle()

    def test_empty_functional_partition_is_rejected_even_if_listed(self):
        report = self.bundle()
        path = self.root / "mapping_ownership.tsv.gz"
        with gzip.open(path, "rt", encoding="utf-8") as stream:
            lines = stream.readlines()
        with gzip.open(path, "wt", encoding="utf-8") as stream:
            stream.writelines(line for line in lines if not line.startswith("top/cluster/compute\t"))
        report["ownership_manifest_sha256"] = CONTRACT.sha256(path)
        (self.root / "mapping_contract.json").write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, "every selected FPGA"):
            self.validate_bundle()

    def test_record_manifest_order_must_match_mapping_verbatim(self):
        report = self.bundle()
        path = self.root / "mapping_records.tsv"
        rows = path.read_text().splitlines()
        rows[1], rows[2] = rows[2], rows[1]
        path.write_text("\n".join(rows) + "\n")
        report["records_manifest_sha256"] = CONTRACT.sha256(path)
        (self.root / "mapping_contract.json").write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, "manifest differs.*order"):
            self.validate_bundle()

    def test_source_proof_flags_and_complete_ownership_counts_are_enforced(self):
        for key, value in (("scope", "hardware_validated"), ("frontier_ownership_compared", False),
                           ("source_paths_exact", False), ("downstream_regroup_verified", True),
                           ("functional_leaf_count", 5), ("functional_leaves_by_destination", {"FPGA_1": 4})):
            with self.subTest(key=key):
                report = self.bundle()
                report[key] = value
                (self.root / "mapping_contract.json").write_text(json.dumps(report))
                with self.assertRaisesRegex(ValueError, "scope|counts differ"):
                    self.validate_bundle()

    def test_connection_artifacts_are_hash_bound_and_have_only_pre_regroup_scope(self):
        for key, value in (("status", "PASS"), ("source_root", "wrong_top"),
                           ("downstream_regroup_verification", "PASS"), ("sha256", "0" * 64)):
            with self.subTest(key=key):
                report = self.bundle()
                path = self.root / "mapping_connections_manifest.json"
                receipt = json.loads(path.read_text())
                receipt[key] = value
                path.write_text(json.dumps(receipt))
                report["connections_receipt_sha256"] = CONTRACT.sha256(path)
                (self.root / "mapping_contract.json").write_text(json.dumps(report))
                with self.assertRaisesRegex(ValueError, "connection receipt"):
                    self.validate_bundle()
        for name in ("mapping_connections_manifest.json", "mapping_connections.tsv.gz"):
            with self.subTest(mutation=name):
                self.bundle()
                path = self.root / name
                path.write_bytes(path.read_bytes() + b"\n")
                with self.assertRaisesRegex(ValueError, "manifest hash"):
                    self.validate_bundle()

    def test_frontier_ownership_cannot_disagree_with_emitted_assignment(self):
        report = self.bundle()
        path = self.root / "mapping_ownership.tsv.gz"
        with gzip.open(path, "rt", encoding="utf-8") as stream:
            text = stream.read()
        text = text.replace("top/cluster/helper\tFDRE\tFUNCTIONAL\tFPGA_1\ttop/cluster/helper\tFPGA_1",
                            "top/cluster/helper\tFDRE\tFUNCTIONAL\tFPGA_1\ttop/cluster/helper\tFPGA_2")
        with gzip.open(path, "wt", encoding="utf-8") as stream:
            stream.write(text)
        report["ownership_manifest_sha256"] = CONTRACT.sha256(path)
        (self.root / "mapping_contract.json").write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, "destination differs"):
            self.validate_bundle()

    def test_empty_hierarchy_is_separate_and_cannot_populate_a_partition(self):
        report = self.bundle()
        path = self.root / "mapping_ownership.tsv.gz"
        with gzip.open(path, "rt", encoding="utf-8") as stream:
            original = stream.read()
        with gzip.open(path, "wt", encoding="utf-8") as stream:
            stream.write(original + "top/empty\tempty_module\tEMPTY_HIERARCHY\tEMPTY_HIERARCHY\ttop\t\n")
        report["empty_hierarchy_count"] = 1
        report["ownership_manifest_sha256"] = CONTRACT.sha256(path)
        (self.root / "mapping_contract.json").write_text(json.dumps(report))
        self.assertEqual(self.validate_bundle()["empty_hierarchy_count"], 1)
        text = original.replace("top/cluster/compute\tLUT2\tFUNCTIONAL\tFPGA_3\ttop/cluster/compute\tFPGA_3",
                                "top/cluster/compute\tempty_module\tEMPTY_HIERARCHY\tEMPTY_HIERARCHY\ttop/cluster/compute\t")
        with gzip.open(path, "wt", encoding="utf-8") as stream:
            stream.write(text)
        report["functional_leaf_count"] = 3
        report["functional_leaves_by_destination"]["FPGA_3"] = 0
        report["ownership_manifest_sha256"] = CONTRACT.sha256(path)
        (self.root / "mapping_contract.json").write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, "every selected FPGA"):
            self.validate_bundle()

    def test_actual_instance_ancestry_can_differ_from_naive_slash_prefixes(self):
        # EDIF may contain sibling instance names 'cluster/helper' and 'cluster'.
        # Both are children of the top; neither is the other's ancestor.
        self.write("top/cluster/helper FPGA_2\ntop/cluster FPGA_2\ntop FPGA_1\n")
        manifest = self.root / "mapping_records.tsv"
        manifest.write_text("\t".join(CONTRACT.RECORD_HEADER) + "\n"
                            "top/cluster/helper\tFPGA_2\ttop\n"
                            "top/cluster\tFPGA_2\ttop\n"
                            "top\tFPGA_1\t\n")
        self.validate(record_manifest=manifest)
        with self.assertRaisesRegex(ValueError, "redundant"):
            self.validate()


class CompleteConnectionStream(unittest.TestCase):
    def setUp(self):
        WORK.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=WORK)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.path = self.root / "connections.tsv.gz"
        self.receipt = {"canonical_nets": 1, "hierarchy_aliases": 1, "endpoints": 2}
        self.rows = [
            ["NET", "top/data[0]", "", "", "", "", "", "", "", "1", "NON_CONSTANT", "CROSSING"],
            ["ALIAS", "top/data[0]", "top/a/data[0]", "", "", "", "", "", "", "1", "NON_CONSTANT", "CROSSING"],
            ["ENDPOINT", "top/data[0]", "top", "data[0]", "TOP_PORT", "INPUT", "true", "false", "FPGA_A", "1", "NON_CONSTANT", "CROSSING"],
            ["ENDPOINT", "top/data[0]", "top/a", "I0", "LUT2", "INPUT", "false", "true", "FPGA_B", "1", "NON_CONSTANT", "CROSSING"],
        ]

    def write(self, rows=None):
        rows = self.rows if rows is None else rows
        def escape(value):
            return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
        with gzip.open(self.path, "wt", encoding="utf-8") as stream:
            stream.write("\t".join(CONTRACT.CONNECTION_HEADER) + "\n")
            for row in rows:
                stream.write("\t".join(map(escape, row)) + "\n")

    def validate(self):
        return CONTRACT.validate_connections(self.path, self.receipt, "top", {"FPGA_A", "FPGA_B"})

    def test_full_bit_endpoint_stream_and_producer_escaping(self):
        self.rows[-1][3] = 'pin\\with\ttab\rand\nnewline"literal'
        self.write()
        self.assertEqual(self.validate(), {"NET": 1, "ALIAS": 1, "ENDPOINT": 2})

    def test_gzip_truncated_footer_and_bad_crc_are_rejected_after_valid_rows(self):
        for damage in ("truncated", "CRC"):
            with self.subTest(damage=damage):
                self.write()
                data = bytearray(self.path.read_bytes())
                if damage == "truncated":
                    data = data[:-8]
                else:
                    data[-8] ^= 1
                self.path.write_bytes(data)
                with self.assertRaisesRegex(ValueError, "corrupt or truncated"):
                    self.validate()

    def test_unsupported_records_wrong_field_counts_and_paths_are_rejected(self):
        for row_number, column, value in ((0, 0, "UNKNOWN"), (3, 1, "other/data"),
                                           (3, 2, "other/a"), (3, 9, "2")):
            with self.subTest(column=column, value=value):
                rows = [row.copy() for row in self.rows]
                rows[row_number][column] = value
                self.write(rows)
                with self.assertRaises(ValueError):
                    self.validate()
        self.write([self.rows[0], self.rows[-1][:-1]])
        with self.assertRaisesRegex(ValueError, "field count"):
            self.validate()

    def test_endpoint_direction_role_owner_and_constant_policy_are_enforced(self):
        for column, value in ((5, "IN"), (6, "True"), (6, "true"), (7, "false"),
                              (8, "C01_S01_E"), (8, "FPGA_C"), (8, "LOCAL_CONSTANT"),
                              (10, "LOCAL_CONSTANT_SOURCE"), (4, "TOP_PORT")):
            with self.subTest(column=column, value=value):
                rows = [row.copy() for row in self.rows]
                rows[-1][column] = value
                self.write(rows)
                with self.assertRaises(ValueError):
                    self.validate()

    def test_net_group_and_boundary_scope_must_match_endpoint_owners(self):
        for rows in ([self.rows[-1]], [self.rows[0], [*self.rows[-1][:1], "top/other", *self.rows[-1][2:]]]):
            self.write(rows)
            with self.assertRaisesRegex(ValueError, "current NET"):
                self.validate()
        rows = [row.copy() for row in self.rows]
        rows[-1][8] = "FPGA_A"
        self.write(rows)
        with self.assertRaisesRegex(ValueError, "boundary scope"):
            self.validate()

    def test_all_receipt_counts_match_complete_stream(self):
        self.write()
        for key in ("canonical_nets", "hierarchy_aliases", "endpoints"):
            with self.subTest(key=key):
                value = self.receipt[key]
                self.receipt[key] += 1
                with self.assertRaisesRegex(ValueError, "counts differ"):
                    self.validate()
                self.receipt[key] = value


class FrontendPublicationContract(unittest.TestCase):
    def setUp(self):
        import test_partition_timing_availability as availability
        self.frontend = availability.FRONTEND
        self.fixture = availability.TimingAvailabilityAcceptance(methodName="runTest")
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def test_noncompliant_candidate_fails_before_native_work(self):
        original = self.fixture.run_boundary
        native_commands = []

        def boundary(command, cwd, log):
            values = [str(value) for value in command]
            if any(value.endswith("prepare_native_resource_cells.py") for value in values):
                native_commands.append(values)
            original(command, cwd, log)
            if self.frontend.MAIN + "Partitioner" in values:
                candidate = Path(values[values.index("--part_dir") + 1])
                (candidate / "cells.txt").write_text("FPGA_A\n")

        self.fixture.run_boundary = boundary
        with self.assertRaisesRegex(ValueError, "cells.txt destinations differ"):
            self.fixture.execute("--timing", "off")
        self.assertEqual(native_commands, [])
        self.assertFalse((self.fixture.output / "mapping.txt").exists())

    def test_native_validated_mapping_staged_mutation_is_rejected(self):
        original = self.frontend.shutil.copy2

        def copy(source, target, *args, **kwargs):
            result = original(source, target, *args, **kwargs)
            if Path(target) == self.fixture.output / "mapping.txt.pending":
                Path(target).write_bytes(Path(target).read_bytes() + b"\n")
            return result

        with patch.object(self.frontend.shutil, "copy2", side_effect=copy):
            with self.assertRaisesRegex(ValueError, "Native-validated mapping snapshot differs"):
                self.fixture.execute("--timing", "off")
        self.assertFalse((self.fixture.output / "mapping.txt").exists())

    def test_selected_contract_artifact_copy_mutation_is_rejected(self):
        original = self.frontend.shutil.copy2

        def copy(source, target, *args, **kwargs):
            result = original(source, target, *args, **kwargs)
            if Path(target) == self.fixture.output / "mapping_records.tsv":
                Path(target).write_bytes(Path(target).read_bytes() + b"\n")
            return result

        with patch.object(self.frontend.shutil, "copy2", side_effect=copy):
            with self.assertRaises(ValueError):
                self.fixture.execute("--timing", "off")
        self.assertFalse((self.fixture.output / "mapping.txt").exists())


if __name__ == "__main__":
    unittest.main()
