"""Dependency discovery and rejection tests without running vendor tools."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("partition_toolchain_test", Path(__file__).with_name("toolchain.py"))
TOOLS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOLS)


class ToolchainContract(unittest.TestCase):
    def setUp(self):
        parent = Path(__file__).resolve().parents[2] / "build/partition-python-tests"
        parent.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def file(self, name):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("fixture")
        return target.resolve()

    def test_explicit_vivado_overrides_environment_and_path(self):
        path = self.file("vivado-explicit")
        with patch.dict(os.environ, {"VIVADO": "bad-env"}), patch.object(TOOLS.shutil, "which", return_value=None):
            self.assertEqual(TOOLS.find_vivado(str(path)), str(path))

    def test_vivado_environment_and_install_root_are_supported(self):
        path = self.file("vivado-env")
        with patch.dict(os.environ, {"VIVADO": str(path)}, clear=True), patch.object(TOOLS.shutil, "which", return_value=None):
            self.assertEqual(TOOLS.find_vivado(), str(path))
        bin_name = "vivado.bat" if os.name == "nt" else "vivado"
        path = self.file("installation/bin/" + bin_name)
        with patch.dict(os.environ, {"XILINX_VIVADO": str(self.root / "installation")}, clear=True), patch.object(TOOLS.shutil, "which", return_value=None):
            self.assertEqual(TOOLS.find_vivado(), str(path))

    def test_missing_explicit_vivado_does_not_silently_pick_another(self):
        with patch.object(TOOLS.shutil, "which", return_value=None), self.assertRaisesRegex(ValueError, "Cannot find"):
            TOOLS.find_vivado(str(self.root / "missing"))

    def test_java_explicit_and_home_precedence(self):
        explicit = self.file("explicit-java")
        name = "java.exe" if os.name == "nt" else "java"
        home = self.file("jdk/bin/" + name)
        with patch.dict(os.environ, {"JAVA_HOME": str(self.root / "jdk")}, clear=True), patch.object(TOOLS.shutil, "which", return_value=None), patch.object(TOOLS, "java_version", return_value="17"):
            self.assertEqual(TOOLS.find_java(str(explicit)), str(explicit))
            self.assertEqual(TOOLS.find_java(), str(home))

    def test_java_rejects_8_and_unrecognized_version(self):
        for text in ('java version "1.8.0_999"', "unrecognized output"):
            with self.subTest(text=text), patch.object(TOOLS, "output", return_value=text), self.assertRaisesRegex(ValueError, "Java 17"):
                TOOLS.java_version("java")

    def test_java_accepts_current_supported_version_forms(self):
        for text in ('openjdk version "17.0.1"', 'java version "21.0.1"', 'openjdk 21.0.1 2023-10-17'):
            with self.subTest(text=text), patch.object(TOOLS, "output", return_value=text):
                self.assertEqual(TOOLS.java_version("java"), text)

    def test_native_backend_discovers_lowercase_and_legacy_binary_names(self):
        for name in ("mtkahypar", "MtKaHyPar"):
            path = self.file(name)
            with self.subTest(name=name), patch.dict(os.environ, {}, clear=True), patch.object(TOOLS.shutil, "which", side_effect=lambda requested: str(path) if requested == name else None), patch.object(TOOLS, "backend_version") as version:
                self.assertEqual(TOOLS.resolve_backend(), (str(path), None))
                version.assert_called_once_with(str(path), None, None)

    def test_missing_backend_has_actionable_error(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(TOOLS.shutil, "which", return_value=None), self.assertRaisesRegex(ValueError, "v1.6.2 was not found"):
            TOOLS.resolve_backend()

    def test_wsl_path_is_not_interpreted_as_host_file(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(TOOLS, "backend_version") as version:
            self.assertEqual(TOOLS.resolve_backend("/opt/mtkahypar", "SelectedDistro", "/opt/lib"), ("/opt/mtkahypar", "/opt/lib"))
            version.assert_called_once_with("/opt/mtkahypar", "SelectedDistro", "/opt/lib")

    def test_wsl_command_keeps_argument_boundaries(self):
        with patch.object(TOOLS, "executable", return_value="wsl.exe"):
            self.assertEqual(TOOLS.backend_command("/path with spaces/mtkahypar", "Distro Name", "/lib with spaces"), ["wsl.exe", "-d", "Distro Name", "--", "env", "LD_LIBRARY_PATH=/lib with spaces", "/path with spaces/mtkahypar"])

    def test_only_exact_backend_version_is_permitted(self):
        for text in ("Mt-KaHyPar v1.6.1", "Mt-KaHyPar v1.6.3", "Mt-KaHyPar v1.6.2\nextra output"):
            with self.subTest(text=text), patch.object(TOOLS, "output", return_value=text), self.assertRaisesRegex(ValueError, "Only Mt-KaHyPar v1.6.2"):
                TOOLS.backend_version("mtkahypar")
        with patch.object(TOOLS, "output", return_value="Mt-KaHyPar v1.6.2"):
            self.assertEqual(TOOLS.backend_version("mtkahypar"), "Mt-KaHyPar v1.6.2")

    def test_native_library_directory_is_applied_without_wsl(self):
        result = subprocess.CompletedProcess([], 0, "Mt-KaHyPar v1.6.2\n")
        with patch.object(TOOLS.subprocess, "run", return_value=result) as run:
            TOOLS.backend_version("mtkahypar", library="/opt/custom libs")
            self.assertEqual(run.call_args.kwargs["env"]["LD_LIBRARY_PATH"], "/opt/custom libs")


if __name__ == "__main__":
    unittest.main()
