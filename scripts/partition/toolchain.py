"""Portable dependency discovery for the mapping-only partitioner.

Explicit options take precedence over environment configuration and PATH.
No experiment folders or machine-specific installation paths are searched.
"""
import importlib
import importlib.util
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def executable(value, name):
    found = shutil.which(str(value))
    if found:
        return str(Path(found).resolve())
    path = Path(value).expanduser()
    if path.is_file():
        return str(path.resolve())
    raise ValueError(f"Cannot find {name}: {value}. Install it on PATH or supply its explicit option.")


def output(command, name, timeout=30):
    try:
        result = subprocess.run([str(v) for v in command], stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, errors="replace", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError(f"Cannot run {name}: {error}") from error
    if result.returncode:
        raise ValueError(f"{name} exited {result.returncode}: {result.stdout.strip()[:1500]}")
    return result.stdout.strip()


def java_version(path):
    value = output([path, "-version"], "Java")
    match = re.search(r'(?:openjdk|java) version "(\d+)(?:\.(\d+))?', value)
    if match is None:
        match = re.search(r'\b(?:openjdk|java)\s+(\d+)(?:\.(\d+))?', value)
    major = int(match[2] or 0) if match and match[1] == "1" else int(match[1]) if match else 0
    if major < 17:
        raise ValueError(f"Java 17+ is required; got {value.splitlines()[0] if value else 'no version'}")
    return value


def find_java(explicit=None):
    if explicit:
        path = executable(explicit, "Java (--java)")
        java_version(path)
        return path
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    if shutil.which("java"):
        candidates.append(shutil.which("java"))
    failures = []
    for candidate in candidates:
        try:
            path = executable(candidate, "Java")
            java_version(path)
            return path
        except ValueError as error:
            failures.append(str(error))
    raise ValueError("Java 17+ was not found. Set JAVA_HOME, update PATH, or pass --java. " + " ".join(failures))


def find_vivado(explicit=None):
    configured = explicit or os.environ.get("VIVADO")
    if configured:
        return executable(configured, "Vivado (--vivado or VIVADO)")
    if os.environ.get("XILINX_VIVADO"):
        path = Path(os.environ["XILINX_VIVADO"]) / "bin" / ("vivado.bat" if os.name == "nt" else "vivado")
        return executable(path, "Vivado (XILINX_VIVADO)")
    return executable("vivado", "Vivado")


def backend_command(binary, wsl=None, library=None):
    command = []
    if wsl:
        command = [executable("wsl.exe", "WSL"), "-d", wsl, "--"]
        if library:
            command += ["env", "LD_LIBRARY_PATH=" + library]
    return command + [binary]


def backend_version(binary, wsl=None, library=None):
    command = backend_command(binary, wsl, library) + ["--version"]
    if library and not wsl:
        # The Java backend applies the same environment to its own processes.
        environment = os.environ.copy()
        environment["LD_LIBRARY_PATH"] = library
        try:
            result = subprocess.run(command, env=environment, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True, errors="replace", timeout=30)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ValueError(f"Cannot run Mt-KaHyPar: {error}") from error
        if result.returncode:
            raise ValueError(f"Mt-KaHyPar exited {result.returncode}: {result.stdout.strip()[:1500]}")
        value = result.stdout.strip()
    else:
        value = output(command, "Mt-KaHyPar")
    if value != "Mt-KaHyPar v1.6.2":
        raise ValueError("Only Mt-KaHyPar v1.6.2 is supported; got: " + value)
    return value


def resolve_backend(explicit=None, wsl=None, library=None):
    binary = explicit or os.environ.get("MTKAHYPAR_BIN")
    library = library or os.environ.get("MTKAHYPAR_LIB")
    if wsl:
        # Paths here belong to the selected distribution, not the host Python.
        binary = binary or "mtkahypar"
    elif binary:
        binary = executable(binary, "Mt-KaHyPar")
    else:
        binary = next((shutil.which(name) for name in ("mtkahypar", "MtKaHyPar") if shutil.which(name)), None)
        if not binary:
            raise ValueError("Mt-KaHyPar v1.6.2 was not found on PATH. Pass --mtkahypar-bin, set MTKAHYPAR_BIN, or select --wsl DISTRO.")
        binary = str(Path(binary).resolve())
    backend_version(binary, wsl, library)
    return binary, library


def _reporting_module():
    spec = importlib.util.spec_from_file_location("partition_reporting_toolchain", Path(__file__).with_name("reporting.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check_reporting(repo, cache_directory=None):
    """Check the bundled font and renderer before starting an expensive solve.

    Matplotlib's font cache belongs to the distribution build directory, or to
    the caller's explicit output cache, rather than an implicit home directory.
    This check may write that cache; it never writes a mapping or runs a solver.
    """
    repo = Path(repo).resolve()
    cache = Path(cache_directory).resolve() if cache_directory is not None else repo / "build/partition-report-cache"
    cache.mkdir(parents=True, exist_ok=True)
    previous = os.environ.get("MPLCONFIGDIR")
    os.environ["MPLCONFIGDIR"] = str(cache)
    try:
        font = repo / "scripts/partition/assets/fonts/TitilliumWeb-Regular.ttf"
        return {**_reporting_module().preflight(font), "cache_directory": str(cache),
                "scope": "Bundled open font and local PNG renderer; no design or partition generated"}
    finally:
        if previous is None:
            os.environ.pop("MPLCONFIGDIR", None)
        else:
            os.environ["MPLCONFIGDIR"] = previous


def check_tools(repo, java=None, vivado=None, binary=None, wsl=None, library=None):
    """No netlist export or solve; renderer may write its local build cache."""
    checks = {}
    def check(name, action):
        try:
            checks[name] = {"status": "PASS", **action()}
        except Exception as error:
            checks[name] = {"status": "FAIL", "error": str(error)}
    check("python", lambda: {"version": sys.version.split()[0]} if sys.version_info >= (3, 11)
          else (_ for _ in ()).throw(ValueError("Python 3.11+ is required")))
    def tcl():
        interpreter = importlib.import_module("tkinter").Tcl()
        return {"version": interpreter.call("info", "patchlevel"), "scope": "Tcl parsing; no GUI window"}
    check("tcl", tcl)
    check("reporting", lambda: check_reporting(repo))
    def jvm():
        path = find_java(java)
        return {"executable": path, "version": java_version(path)}
    check("java", jvm)
    def native():
        path = find_vivado(vivado)
        # Some Vivado launchers return 1 for -version even after printing the
        # version. Use an explicit, no-design batch query with a real exit0.
        probe = Path(repo) / "tools/check_vivado_version.tcl"
        version = output([path, "-mode", "batch", "-nolog", "-nojournal", "-notrace", "-source", str(probe)], "Vivado version query")
        match = re.search(r"^PARTITION_TOOLCHAIN_VIVADO_VERSION=(\S+)$", version, re.MULTILINE)
        if match is None or match[1] != "2025.1":
            raise ValueError("This release requires Vivado 2025.1; explicit version query returned: " + (match[1] if match else "no version marker"))
        return {"executable": path, "version": match[1], "query_exit_code": 0, "source_design_loaded": False}
    check("vivado", native)
    def solver():
        path, libs = resolve_backend(binary, wsl, library)
        return {"executable": path, "wsl_distribution": wsl, "library_path": libs, "version": "1.6.2"}
    check("mtkahypar", solver)
    def build():
        repo_path = Path(repo)
        required = [repo_path / "bin/com/xilinx/rapidwright/edif/partition/Partitioner.class",
                    repo_path / "bin/com/xilinx/rapidwright/edif/partition/VivadoResourceOracle.class"]
        if not all(path.is_file() for path in required) or not list((repo_path / "jars").glob("*.jar")):
            raise ValueError("Java runtime is not built. Run ./gradlew compileJava (Windows: .\\gradlew.bat compileJava).")
        return {"scope": "compiled partitioner classes and dependency jars present; not a fresh build certificate"}
    check("build", build)
    return {"status": "PASS" if all(c["status"] == "PASS" for c in checks.values()) else "FAIL", "checks": checks}
