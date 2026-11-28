"""The replay harness reports what happened, and fails when the replay did not run.

    python3 -m unittest discover -s docs/pr-229-evidence/replay -p 'test_*.py'
"""
import importlib.util
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = importlib.util.spec_from_file_location("fuzz", os.path.join(HERE, "fuzz.py"))
fuzz = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fuzz)


def stub(directory, name, body):
    """An executable shell script `name` in `directory` running `body`."""
    path = os.path.join(directory, name)
    with open(path, "w") as f:
        f.write("#!/bin/sh\n" + body + "\n")
    os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)
    return path


class ClassifyTest(unittest.TestCase):
    def test_each_exit_status_has_its_own_outcome(self):
        self.assertEqual(fuzz.classify(0), "ok")
        self.assertEqual(fuzz.classify(1), "error")
        self.assertEqual(fuzz.classify(101), "PANIC")
        # subprocess reports a signal as the negated signal number.
        self.assertEqual(fuzz.classify(-6), "CRASH")   # SIGABRT
        self.assertEqual(fuzz.classify(-11), "CRASH")  # SIGSEGV
        self.assertEqual(fuzz.classify(-9), "CRASH")   # SIGKILL
        self.assertEqual(fuzz.classify(2), "UNEXPECTED")
        self.assertEqual(fuzz.classify(134), "UNEXPECTED")


class FuzzTest(unittest.TestCase):
    def run_fuzz(self, body, *flags):
        with tempfile.TemporaryDirectory() as tmp:
            graph = os.path.join(tmp, "graph")
            os.makedirs(graph)
            with open(os.path.join(graph, "graph.labels"), "wb") as f:
                f.write(bytes(64))
            binary = stub(tmp, "graphite", body)
            return subprocess.run(
                [sys.executable, os.path.join(HERE, "fuzz.py"), *flags, binary, graph,
                 os.path.join(tmp, "work"), "graph.labels"],
                capture_output=True, text=True)

    def test_a_crash_is_counted_as_a_crash_and_fails_a_clean_run(self):
        crashed = self.run_fuzz("kill -ABRT $$", "--expect-clean")
        self.assertEqual(crashed.returncode, 3, crashed.stderr)
        self.assertIn("== graph.labels: {'CRASH': 30}", crashed.stdout)
        self.assertIn("q0=-6", crashed.stdout, "the raw code is kept")

    def test_a_controlled_refusal_is_clean_and_a_panic_is_not(self):
        refused = self.run_fuzz("echo 'Error: corrupt graph' >&2; exit 1", "--expect-clean")
        self.assertEqual(refused.returncode, 0, refused.stderr)
        self.assertIn("== graph.labels: {'error': 30}", refused.stdout)
        panicked = self.run_fuzz("exit 101", "--expect-clean")
        self.assertEqual(panicked.returncode, 3)
        # Without --expect-clean (the baseline) a panic is evidence, not a failure.
        self.assertEqual(self.run_fuzz("exit 101").returncode, 0)


class SectionPropagationTest(unittest.TestCase):
    """The real sections of replay.sh, sourced and driven with stub commands.

    Each stub prints its name and takes its exit code from a queue, one code per call, so a
    case can fail a required candidate command and pass the one after it: the case the
    review of #229 found a `{ ...; } || handler` block reporting as success.
    """

    def run_section(self, section, codes):
        with tempfile.TemporaryDirectory() as tmp:
            bin_dir = os.path.join(tmp, "bin")
            os.makedirs(bin_dir)
            queue = os.path.join(tmp, "codes")
            with open(queue, "w") as f:
                f.write("\n".join(str(c) for c in codes) + "\n")
            # One queue for every stubbed command, consumed in call order.
            body = ('code=$(head -1 "%s"); sed -i 1d "%s"; echo "stub $(basename "$0") -> ${code:-0}"; '
                    'exit "${code:-0}"' % (queue, queue))
            stub(bin_dir, "python3", body)
            graphite = stub(bin_dir, "graphite", body)
            out = os.path.join(tmp, "out")
            os.makedirs(os.path.join(out, "replay-logs"))
            script = (
                "REPLAY_SOURCE_ONLY=1 source %s\n"
                'OUT=%s WORK=%s LOGS=%s/replay-logs HERE=%s REPO=%s\n'
                'BASE_REV=b CAND_REV=c BASE_BIN=%s CAND_BIN=%s BASE_JAR=x CAND_JAR=x\n'
                "%s\n"
                "echo section-completed\n"
            ) % (os.path.join(HERE, "replay.sh"), out, tmp, out, HERE, tmp, graphite, graphite, section)
            env = dict(os.environ, PATH=bin_dir + os.pathsep + os.environ["PATH"])
            return subprocess.run(["bash", "-c", script], capture_output=True, text=True, env=env)

    def test_a_failing_candidate_bit_flip_run_fails_even_when_the_next_run_passes(self):
        # baseline 0, candidate 3 (a crash or panic), candidate under verification 0.
        failed = self.run_section("bitflip_section", [0, 3, 0])
        self.assertEqual(failed.returncode, 1, failed.stderr)
        self.assertIn("the candidate bit-flip run", failed.stderr)
        self.assertNotIn("section-completed", failed.stdout)
        passed = self.run_section("bitflip_section", [0, 0, 0])
        self.assertEqual(passed.returncode, 0, passed.stderr)
        self.assertIn("section-completed", passed.stdout)

    def test_a_failing_completeness_check_fails_even_when_the_next_query_passes(self):
        # baseline query 1 (its refusal is evidence), completeness-e2e 1, candidate query 0.
        failed = self.run_section("completeness_section", [1, 1, 0])
        self.assertEqual(failed.returncode, 1, failed.stderr)
        self.assertIn("the candidate completeness check", failed.stderr)
        self.assertNotIn("section-completed", failed.stdout)
        passed = self.run_section("completeness_section", [1, 0, 0])
        self.assertEqual(passed.returncode, 0, passed.stderr)

    def test_a_failing_candidate_differential_fails_and_the_baselines_is_evidence(self):
        self.assertEqual(self.run_section("differential_section", [1, 0]).returncode, 0)
        failed = self.run_section("differential_section", [0, 1])
        self.assertEqual(failed.returncode, 1, failed.stderr)
        self.assertIn("the candidate CLI differential", failed.stderr)


class HeapTest(unittest.TestCase):
    """Every JVM of a replay runs within the 8 GiB heap ceiling, and its log proves it."""

    def heap(self, logs):
        with tempfile.TemporaryDirectory() as tmp:
            for name, text in logs.items():
                with open(os.path.join(tmp, name), "w") as f:
                    f.write(text)
            return subprocess.run([sys.executable, os.path.join(HERE, "heap.py"), tmp],
                                  capture_output=True, text=True)

    def test_each_jvm_must_log_a_capacity_within_the_ceiling(self):
        line = "[0.032s][info][gc,init] Heap Max Capacity: %s\n"
        ok = self.heap({"jvm-1.log": line % "4G", "jvm-2.log": line % "3G", "jvm-3.log": line % "8G"})
        self.assertEqual(ok.returncode, 0, ok.stdout)
        self.assertIn("== 3 JVMs: 1 x 3221225472 bytes, 1 x 4294967296 bytes, 1 x 8589934592 bytes",
                      ok.stdout)
        over = self.heap({"jvm-1.log": line % "4G", "jvm-2.log": line % "8193M"})
        self.assertEqual(over.returncode, 1)
        self.assertIn("FAIL: jvm-2.log ran with 8590983168 bytes", over.stdout)
        silent = self.heap({"jvm-1.log": "[0.01s][info][gc,init] Version: 21\n"})
        self.assertEqual(silent.returncode, 1)
        self.assertIn("FAIL: jvm-1.log logged no Heap Max Capacity", silent.stdout)
        self.assertEqual(self.heap({}).returncode, 1, "no JVM logged at all")

    def bound(self, heap, then=""):
        script = ('REPLAY_SOURCE_ONLY=1 source %s\nHEAP_LOGS=%s\nREPLAY_HEAP=\"%s\" bound_jvm_heap\n'
                  'echo "opts=$JAVA_TOOL_OPTIONS"\n%s\n') % (
                      os.path.join(HERE, "replay.sh"), self.logs, heap, then)
        return subprocess.run(["bash", "-c", script], capture_output=True, text=True)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.logs = os.path.join(self.tmp.name, "jvm-heap")

    def tearDown(self):
        self.tmp.cleanup()

    def test_the_replay_heap_is_refused_above_the_ceiling(self):
        for heap in ("9g", "8193m", "8 g", "lots"):
            refused = self.bound(heap)
            self.assertEqual(refused.returncode, 1, heap)
            self.assertIn("REPLAY_HEAP=%s" % heap, refused.stderr)
        accepted = self.bound("8g")
        self.assertEqual(accepted.returncode, 0, accepted.stderr)
        self.assertIn("opts=-Xmx8g -Xlog:gc+init=info:file=%s/jvm-%%p-%%t.log" % self.logs,
                      accepted.stdout)

    @unittest.skipUnless(shutil.which("java"), "needs a JVM")
    def test_a_real_jvm_logs_the_heap_it_got_and_a_later_xmx_wins(self):
        ran = self.bound("2g", 'java -version 2>/dev/null; java -Xmx1g -version 2>/dev/null; '
                               'python3 %s "$HEAP_LOGS"' % os.path.join(HERE, "heap.py"))
        self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
        self.assertIn("== 2 JVMs: 1 x 1073741824 bytes, 1 x 2147483648 bytes", ran.stdout)


class ReplayTest(unittest.TestCase):
    def test_a_failed_graph_build_fails_the_replay_and_keeps_its_log(self):
        with tempfile.TemporaryDirectory() as tmp:
            binary = stub(tmp, "graphite", 'echo "build exploded" >&2; exit 7')
            jar = os.path.join(tmp, "graphite.jar")
            open(jar, "wb").close()
            out = os.path.join(tmp, "out")
            replay = subprocess.run(
                ["bash", os.path.join(HERE, "replay.sh"),
                 "base", binary, jar, "cand", binary, jar, jar, out],
                capture_output=True, text=True)
            self.assertEqual(replay.returncode, 1, replay.stderr)
            self.assertIn("step 'build-base-fixture-graph' failed", replay.stderr)
            self.assertNotIn("replayed into", replay.stdout)
            log = os.path.join(out, "replay-logs", "build-base-fixture-graph.log")
            with open(log) as f:
                self.assertIn("build exploded", f.read())


if __name__ == "__main__":
    unittest.main()
