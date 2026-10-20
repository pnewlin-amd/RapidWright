"""Dependency discovery and rejection tests without running vendor tools."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
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

    def test_report_renderer_preflight_uses_bundled_font_and_local_cache(self):
        observed = {}
        def preflight(font):
            observed["font"] = font
            observed["cache"] = os.environ["MPLCONFIGDIR"]
            return {"font_family": "Titillium Web", "font_sha256": "f" * 64}
        with patch.dict(os.environ, {"MPLCONFIGDIR": "old-home-cache"}), patch.object(TOOLS, "_reporting_module", return_value=SimpleNamespace(preflight=preflight)):
            result = TOOLS.check_reporting(self.root)
            self.assertEqual(os.environ["MPLCONFIGDIR"], "old-home-cache")
        self.assertEqual(observed["font"], self.root / "scripts/partition/assets/fonts/TitilliumWeb-Regular.ttf")
        self.assertEqual(Path(observed["cache"]), self.root / "build/partition-report-cache")
        self.assertEqual(result["font_family"], "Titillium Web")

    def test_renderer_preflight_preserves_environment_on_dependency_failure(self):
        def fail(font):
            raise RuntimeError("Matplotlib is missing")
        with patch.dict(os.environ, {}, clear=True), patch.object(TOOLS, "_reporting_module", return_value=SimpleNamespace(preflight=fail)):
            with self.assertRaisesRegex(RuntimeError, "Matplotlib"):
                TOOLS.check_reporting(self.root, self.root / "explicit-output-cache")
            self.assertNotIn("MPLCONFIGDIR", os.environ)

    def test_check_tools_contains_report_failure_without_running_a_design(self):
        for name in ("bin/com/xilinx/rapidwright/edif/partition/Partitioner.class",
                     "bin/com/xilinx/rapidwright/edif/partition/VivadoResourceOracle.class", "jars/runtime.jar"):
            self.file(name)
        fake_tcl = SimpleNamespace(Tcl=lambda: SimpleNamespace(call=lambda *args: "8.6"))
        with patch.object(TOOLS.importlib, "import_module", return_value=fake_tcl), \
             patch.object(TOOLS, "find_java", return_value="java"), \
             patch.object(TOOLS, "java_version", return_value="17"), \
             patch.object(TOOLS, "find_vivado", return_value="vivado"), \
             patch.object(TOOLS, "output", return_value="PARTITION_TOOLCHAIN_VIVADO_VERSION=2025.1"), \
             patch.object(TOOLS, "resolve_backend", return_value=("mtkahypar", None)), \
             patch.object(TOOLS, "check_reporting", side_effect=RuntimeError("missing report font")):
            result = TOOLS.check_tools(self.root)
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["checks"]["reporting"], {"status": "FAIL", "error": "missing report font"})
        self.assertEqual(result["checks"]["vivado"]["source_design_loaded"], False)


if __name__ == "__main__":
    unittest.main()
