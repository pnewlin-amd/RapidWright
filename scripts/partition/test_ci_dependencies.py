"""Keep both source CI entry points supplied with the required report runtime.

The Gradle Python task invokes pytest from the checkout root, so its discovery
also reaches these partitioner tests. A dedicated workflow passing alone does
not establish that the broader Build workflow installed the same dependencies.
"""
from pathlib import Path
import re
import shlex
import unittest


REPO = Path(__file__).resolve().parents[2]


def commands(text):
    """Extract run commands without requiring a YAML package in the test runtime."""
    lines = text.splitlines()
    result = []
    for index, line in enumerate(lines):
        match = re.match(r"(\s*)run:\s*(.*)", line)
        if not match:
            continue
        value = match.group(2)
        if value == "|":
            block = []
            indentation = len(match.group(1))
            for candidate in lines[index + 1:]:
                if candidate.strip() and len(candidate) - len(candidate.lstrip()) <= indentation:
                    break
                block.append(candidate.strip())
            value = "\n".join(block)
        result.append(value)
    return result


@unittest.skipUnless((REPO / ".github/workflows/build.yml").is_file(),
                     "Source CI workflows are not included in an installed runtime bundle")
class ContinuousIntegrationDependencies(unittest.TestCase):
    def test_both_python_workflows_install_shared_runtime_requirements(self):
        for name in ("build.yml", "partitioner.yml"):
            with self.subTest(workflow=name):
                text = (REPO / ".github/workflows" / name).read_text(encoding="utf-8")
                installs = [shlex.split(command) for command in commands(text)
                            if command.startswith("python -m pip install ")]
                shared = [command for command in installs if "scripts/partition/requirements.txt" in command]
                self.assertTrue(shared, "This workflow must install the same rendering/process dependencies as the partitioner")
                self.assertTrue(any("-r" in command and command[command.index("-r") + 1] ==
                                    "scripts/partition/requirements.txt" for command in shared))
                if name == "build.yml":
                    self.assertTrue(any("pytest" in command and "python/" in command for command in shared),
                                    "Keep the upstream Gradle pytest bridge and Python wrapper dependencies")

    def test_broad_build_exercises_real_report_integration_before_java_work(self):
        text = (REPO / ".github/workflows/build.yml").read_text(encoding="utf-8")
        runs = commands(text)
        install = next(index for index, command in enumerate(runs)
                       if command.startswith("python -m pip install "))
        python_tests = next(index for index, command in enumerate(runs)
                            if shlex.split(command) == ["python", "-m", "unittest", "discover", "-s",
                                                       "scripts/partition", "-p", "test_*.py"])
        java = [index for index, command in enumerate(runs) if command.startswith("./gradlew ")]
        self.assertLess(install, python_tests)
        self.assertTrue(java)
        self.assertLess(python_tests, min(java), "Missing renderer dependencies should fail before a long Java run")

    def test_both_threading_matrix_results_survive_an_independent_failure(self):
        text = (REPO / ".github/workflows/build.yml").read_text(encoding="utf-8")
        self.assertRegex(text, r"(?m)^\s+fail-fast:\s+false\s*$")
        self.assertIn("single_threaded: [multi-threaded, single-threaded]", text)

    def test_gradle_resolves_the_python_junit_output_before_launching_pytest(self):
        text = (REPO / "build.gradle").read_text(encoding="utf-8")
        task = text[text.index("task testPython"):]
        self.assertNotIn("'$buildDir/test-results/", task,
                         "A Groovy single-quoted buildDir is passed literally to Python and misses CI artifact collection")
        self.assertRegex(task, r"['\"]--junitxml['\"]\s*,\s*layout\.buildDirectory\.file\(['\"]test-results/testPython/testPython\.xml['\"]\)\.get\(\)\.asFile\.absolutePath")


if __name__ == "__main__":
    unittest.main()
