"""parity-cli.py reads the graph it was given, wherever it is started from.

    python3 -m unittest discover -s backend/bench -p test_parity_cli.py

The script runs both CLIs with the repository root as their working directory, so a relative
graph path names a directory of the repository. It used to copy the graph relative to the
caller's directory instead: started from anywhere else it copied another graph, or failed.
The CLIs here are stubs, so the test needs no build: the "jar" side prints the marker file of
the graph copy it is handed and the "native" side prints what the repository's graph holds,
and the two agree only when the copy was taken from the repository's graph.
"""
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "parity-cli.py")

# `java -jar <jar> query <graph> ...`: the marker of the graph it was handed.
JAVA = """#!/bin/sh
cat "$4/marker" 2>/dev/null || echo repository
"""
# `<native> query <graph> ...`: what the repository's graph holds, whatever it was handed.
NATIVE = """#!/bin/sh
echo repository
"""


def executable(path, text):
    with open(path, "w") as f:
        f.write(text)
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
    return path


class RelativeGraphTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.mkdtemp(prefix="graphite-parity-cli-test-")
        self.addCleanup(shutil.rmtree, self.scratch)
        # A repository root holding the script (it finds the root from its own location),
        # a graph and the native stub; `java` is found on PATH.
        self.root = os.path.join(self.scratch, "repo")
        os.makedirs(os.path.join(self.root, "backend", "bench"))
        self.script = shutil.copy(SCRIPT, os.path.join(self.root, "backend", "bench"))
        self.graph(self.root, "repository")
        executable(os.path.join(self.root, "native"), NATIVE)
        self.bin = os.path.join(self.scratch, "bin")
        os.makedirs(self.bin)
        executable(os.path.join(self.bin, "java"), JAVA)

    def graph(self, parent, marker):
        os.makedirs(os.path.join(parent, "graphs", "fixture"))
        with open(os.path.join(parent, "graphs", "fixture", "marker"), "w") as f:
            f.write(marker + "\n")

    def run_script(self, cwd, graph):
        env = dict(os.environ, PATH=self.bin + os.pathsep + os.environ["PATH"])
        return subprocess.run(
            [sys.executable, self.script, graph, "unused.jar", "./native"],
            cwd=cwd, env=env, capture_output=True, text=True)

    def assert_all_pass(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertRegex(result.stdout, r"cli parity: \d+ passed, 0 failed")

    def test_a_relative_graph_is_the_repositorys_from_outside_it(self):
        outside = os.path.join(self.scratch, "elsewhere")
        os.makedirs(outside)
        # Failed before: the copy was taken relative to `outside`, which has no such graph.
        self.assert_all_pass(self.run_script(outside, "graphs/fixture"))

    def test_a_graph_of_the_same_relative_name_in_the_callers_directory_is_not_read(self):
        outside = os.path.join(self.scratch, "decoy")
        self.graph(outside, "the caller's directory")
        # Failed before: every case compared the decoy's marker with the repository's.
        self.assert_all_pass(self.run_script(outside, "graphs/fixture"))

    def test_from_the_repository_root_and_with_an_absolute_path(self):
        self.assert_all_pass(self.run_script(self.root, "graphs/fixture"))
        self.assert_all_pass(
            self.run_script(self.scratch, os.path.join(self.root, "graphs", "fixture")))


if __name__ == "__main__":
    unittest.main()
