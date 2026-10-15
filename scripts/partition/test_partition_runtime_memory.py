"""Runtime estimates must account for expanded hierarchy independently of EDIF bytes."""
import importlib.util
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("runtime_memory_frontend", Path(__file__).with_name("partitioner.py"))
FRONTEND = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FRONTEND)
GIB = 1024 ** 3


class ExpandedHierarchyMemory(unittest.TestCase):
    def test_reused_cell_type_requires_more_heap_than_small_serialization(self):
        self.assertEqual(FRONTEND.automatic_java_heap(650 * 1024 ** 2, 11_462_000), "56G")
        self.assertEqual(FRONTEND.automatic_java_heap(650 * 1024 ** 2, 955_135), "16G")

    def test_large_serialization_remains_an_independent_lower_bound(self):
        self.assertEqual(FRONTEND.automatic_java_heap(8 * GIB, 10), "48G")
        self.assertEqual(FRONTEND.automatic_java_heap(0, None), "8G")

    def test_native_residency_also_accounts_for_expanded_hierarchy(self):
        self.assertTrue(FRONTEND.persistent_memory_fits("56G", 650 * 1024 ** 2, 70 * GIB))
        self.assertFalse(FRONTEND.persistent_memory_fits("56G", 650 * 1024 ** 2, 70 * GIB, 11_462_000))
        self.assertTrue(FRONTEND.persistent_memory_fits("56G", 650 * 1024 ** 2, 90 * GIB, 11_462_000))

    def test_native_census_line_count_with_crlf_and_unterminated_last_row(self):
        temporary_root = Path(__file__).resolve().parents[2] / "build/partition-python-tests"
        temporary_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary_root) as temporary:
            inventory = Path(temporary) / "leaf_primitives.tsv"
            inventory.write_bytes(b"name\tref_name\r\na\tLUT6\r\nb\tFDRE")
            self.assertEqual(FRONTEND.native_leaf_count(inventory), 2)
            inventory.write_bytes(b"\xef\xbb\xbfname\tref_name\r\na\tLUT6\r\nb\tFDRE")
            self.assertEqual(FRONTEND.native_leaf_count(inventory), 2)
            inventory.write_bytes(b"name\tref_name\n")
            self.assertEqual(FRONTEND.native_leaf_count(inventory), 0)
            inventory.write_bytes(b"unrecognized census\n")
            self.assertIsNone(FRONTEND.native_leaf_count(inventory))


if __name__ == "__main__":
    unittest.main()
