"""`apple-frontend.py` accepts a sample only for valid, equivalent work.

Run with `python3 -m unittest discover -s backend/bench -p 'test_*.py'`; no frontend is
needed: the IR streams are built here with the framing of `ir/graphite_ir.proto`, and the
end-to-end cases drive the harness with a stub frontend that writes them.
"""

import importlib.util
import io
import json
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

HERE = pathlib.Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


harness = load("apple-frontend")


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def field(number, payload):
    """A length-delimited field."""
    return varint((number << 3) | 2) + varint(len(payload)) + payload


def scalar(number, value):
    return varint(number << 3) + varint(value)


def chunk(number, payload):
    body = field(number, payload)
    return varint(len(body)) + body


def stream(nodes=3, edges=2, strings=4, trailer=None, header=True, tail=b""):
    """An IR stream: header, strings, nodes, edges, trailer; `trailer` overrides the counts."""
    parts = []
    if header:
        parts.append(chunk(harness.CHUNK_HEADER, scalar(1, 1) + field(2, b"swift")))
    parts.append(chunk(harness.CHUNK_STRINGS, b"".join(field(1, b"s%d" % i) for i in range(strings))))
    parts.append(chunk(harness.CHUNK_NODES, b"".join(field(1, scalar(1, i) + field(9, scalar(1, 7))) for i in range(nodes))))
    parts.append(chunk(harness.CHUNK_EDGES, b"".join(field(1, scalar(1, i) + scalar(2, i + 1)) for i in range(edges))))
    declared = trailer or (nodes, edges, strings)
    parts.append(chunk(harness.CHUNK_TRAILER, scalar(1, declared[0]) + scalar(2, declared[1]) + scalar(3, declared[2])))
    return b"".join(parts) + tail


def shape(nodes=3, edges=2, strings=4, files=4):
    return {"files": files, "types": 1, "methods": 2, "fields": 1, "callSites": 2, "constants": 1, "annotations": 0,
            "nodes": nodes, "edges": edges, "strings": strings}


SUMMARY = ("graphite-frontend-apple 0.1.0: {files} files, {types} types, {methods} methods, {fields} fields, "
           "{callSites} call sites, {constants} constants, {annotations} annotations; {nodes} nodes, {edges} edges, "
           "{strings} strings -> out\n")


class ValidateIR(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = pathlib.Path(self.directory.name) / "x.graphite-ir"

    def tearDown(self):
        self.directory.cleanup()

    def check(self, data, shape_=None):
        self.path.write_bytes(data)
        return harness.validate_ir(self.path, shape_ or shape())

    def test_a_well_formed_stream_counts_its_batches(self):
        self.assertEqual(self.check(stream()), (3, 2, 4))
        self.assertEqual(self.check(stream(nodes=1, edges=0, strings=0), shape(1, 0, 0)), (1, 0, 0))

    def test_corrupt_streams_are_refused(self):
        cases = [
            (b"", "the IR is empty"),
            (stream()[:-3], "truncated inside a chunk"),
            (stream(tail=b"\x7f\x01\x02"), "continues after its trailer"),
            (stream(tail=stream()), "continues after its trailer"),
            (stream(header=False), "does not start with a header"),
            (stream(trailer=(3, 2, 9)), "trailer declares nodes/edges/strings (3, 2, 9), the stream carries (3, 2, 4)"),
            (stream(nodes=0, edges=0, strings=0), "no nodes"),
            (b"\x05" + b"\xff" * 5, "varint runs past the end"),
        ]
        for data, message in cases:
            with self.subTest(message=message):
                with self.assertRaises(harness.InvalidIR) as raised:
                    self.check(data, shape(0, 0, 0) if message == "no nodes" else None)
                self.assertIn(message, str(raised.exception))
        # No trailer at all: drop the last chunk.
        body = stream()
        without = body[:len(body) - len(chunk(harness.CHUNK_TRAILER, scalar(1, 3) + scalar(2, 2) + scalar(3, 4)))]
        with self.assertRaises(harness.InvalidIR) as raised:
            self.check(without)
        self.assertIn("no trailer", str(raised.exception))
        # Two payloads in one chunk.
        double = chunk(harness.CHUNK_HEADER, scalar(1, 1))[:0] + varint(len(field(1, b"") + field(2, b""))) + field(1, b"") + field(2, b"")
        with self.assertRaises(harness.InvalidIR) as raised:
            self.check(double)
        self.assertIn("carries 2 payloads", str(raised.exception))

    def test_the_summary_must_agree_with_the_stream(self):
        with self.assertRaises(harness.InvalidIR) as raised:
            self.check(stream(), shape(nodes=30))
        self.assertIn("reported nodes/edges/strings (30, 2, 4), the IR carries (3, 2, 4)", str(raised.exception))

    def test_summary_parsing(self):
        log = pathlib.Path(self.directory.name) / "run.log"
        log.write_text('{"phase": "index", "done": 1, "total": 1}\n' + SUMMARY.format(**shape()))
        self.assertEqual(harness.parse_summary(log), shape())
        log.write_text("error: boom\n")
        with self.assertRaises(harness.InvalidIR):
            harness.parse_summary(log)


class EndToEnd(unittest.TestCase):
    """The harness driven by a stub frontend: the stub writes an IR and prints a summary."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def stub(self, data, shape_, name="frontend"):
        payload = self.root / f"{name}.ir"
        payload.write_bytes(data)
        script = self.root / name
        script.write_text(
            "#!/usr/bin/env python3\n"
            "import shutil, sys\n"
            "args = sys.argv[1:]\n"
            f"shutil.copyfile({str(payload)!r}, args[args.index('--out') + 1])\n"
            f"sys.stderr.write({SUMMARY.format(**shape_)!r})\n"
        )
        script.chmod(script.stat().st_mode | stat.S_IEXEC)
        return script

    def manifest(self, **extra):
        path = self.root / "corpus.json"
        path.write_text(json.dumps({"schema": "graphite-apple-frontend-corpus-v1", "label": "stub", "repository": "r", "commit": "c",
                                    "files": 4, "ceilings": {"wallMs": 5000, "rssMiB": 512}, **extra}))
        return path

    def run_harness(self, frontend, corpus, repetitions=2, corpus_arguments=None, verify=None):
        out = self.root / "rows.json"
        result = subprocess.run(
            [sys.executable, str(HERE / "apple-frontend.py"), "--frontend", str(frontend),
             *(corpus_arguments or ["--package", str(self.root)]),
             "--corpus", str(corpus), "--repetitions", str(repetitions), "--warmup", "1",
             *([] if verify is None else ["--verify", verify]),
             "--log", str(self.root / "run.log"), "--out", str(out)],
            capture_output=True, text=True,
        )
        return result, out

    def recording_stub(self):
        """A stub that also records the arguments it was called with."""
        script = self.stub(stream(), shape(), name="recording")
        script.write_text(script.read_text().replace("import shutil, sys\n", f"import shutil, sys\nopen({str(self.root / 'args')!r}, 'a').write(' '.join(sys.argv[1:]) + chr(10))\n"))
        return script

    def test_valid_runs_record_the_shape_and_determinism(self):
        result, out = self.run_harness(self.stub(stream(), shape()), self.manifest(shape=shape()))
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = json.loads(out.read_text())
        self.assertEqual([row["benchmark"] for row in rows], ["apple.frontend.wall", "apple.frontend.rss"])
        self.assertEqual(rows[0]["shape"], shape())
        self.assertEqual(rows[0]["determinism"]["identical"], True)
        self.assertEqual(rows[0]["primaryMetric"]["scoreUnit"], "ms/op")
        self.assertEqual(rows[1]["primaryMetric"]["scoreUnit"], "MiB")
        self.assertEqual(len(rows[0]["primaryMetric"]["rawData"][0]), 2)
        self.assertIn("stub: 4 files, 3 nodes, 2 edges", result.stdout)

    def test_a_truncated_ir_is_refused_whatever_the_summary_says(self):
        # The frontend prints its real summary but leaves an empty file behind.
        result, _ = self.run_harness(self.stub(b"", shape()), self.manifest(shape=shape()))
        self.assertEqual(result.returncode, 2)
        self.assertIn("not valid Graphite IR: the IR is empty", result.stderr)

    def test_a_shape_other_than_the_pin_is_refused(self):
        # Fewer nodes than pinned: faster, but not the same work.
        result, _ = self.run_harness(self.stub(stream(nodes=2), shape(nodes=2)), self.manifest(shape=shape()))
        self.assertEqual(result.returncode, 2)
        self.assertIn("differs from the corpus pin (nodes 3 -> 2)", result.stderr)
        # An unpinned manifest accepts any consistent shape.
        result, out = self.run_harness(self.stub(stream(nodes=2), shape(nodes=2)), self.manifest())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(out.read_text())[0]["shape"]["nodes"], 2)
        # The pinned file count is checked too.
        result, _ = self.run_harness(self.stub(stream(), shape(files=5)), self.manifest())
        self.assertEqual(result.returncode, 2)
        self.assertIn("has 5 Swift files, the manifest pins 4", result.stderr)

    def test_a_project_corpus_is_measured_through_its_derived_data(self):
        manifest = self.manifest(input="xcodeproj")
        project = ["--project", str(self.root / "App.xcodeproj"), "--derived-data", str(self.root / "derived")]
        result, out = self.run_harness(self.recording_stub(), manifest, corpus_arguments=project)
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = (self.root / "args").read_text().splitlines()
        self.assertEqual(len(calls), 3)
        self.assertTrue(calls[0].startswith(f"build --project {self.root / 'App.xcodeproj'} --derived-data {self.root / 'derived'} --skip-build --out "), calls[0])
        rows = json.loads(out.read_text())
        self.assertEqual(rows[0]["input"], "xcodeproj")
        self.assertEqual(rows[0]["corpus"], {"repository": "r", "commit": "c"})
        # The package path and a project manifest never mix, in either direction.
        result, _ = self.run_harness(self.recording_stub(), manifest)
        self.assertEqual(result.returncode, 2)
        self.assertIn("the manifest is a xcodeproj corpus, the harness was given a package", result.stderr)
        result, _ = self.run_harness(self.recording_stub(), self.manifest(), corpus_arguments=project)
        self.assertEqual(result.returncode, 2)
        self.assertIn("the manifest is a package corpus, the harness was given a xcodeproj", result.stderr)
        result, _ = self.run_harness(self.recording_stub(), manifest, corpus_arguments=project[:2])
        self.assertEqual(result.returncode, 2)
        self.assertIn("--project needs --derived-data", result.stderr)

    def test_a_generated_corpus_records_its_generator(self):
        generator = {"script": "generate-apple-corpus.py", "seed": 1, "files": 4, "modules": 1, "layout": "package", "name": "Demo"}
        manifest = self.manifest(generator=generator)
        result, out = self.run_harness(self.stub(stream(), shape()), manifest)
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = json.loads(out.read_text())
        self.assertEqual(rows[0]["input"], "package")
        self.assertEqual(rows[0]["corpus"], {"generator": generator})

    def test_the_production_reader_verifies_the_last_measured_ir(self):
        # The verify command sees the IR the last run wrote, at the path in {ir}, and an {out}.
        reader = self.root / "reader.py"
        reader.write_text(
            "import pathlib, sys\n"
            "ir, out = sys.argv[1], sys.argv[2]\n"
            f"assert pathlib.Path(ir).read_bytes() == {stream()!r}, ir\n"
            "assert out.endswith('verified-graph'), out\n"
            f"pathlib.Path({str(self.root / 'seen')!r}).write_text(ir)\n"
        )
        command = f"{sys.executable} {reader} {{ir}} -o {{out}}".replace(" -o {out}", " {out}")
        result, out = self.run_harness(self.stub(stream(), shape()), self.manifest(), verify=command)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / "seen").exists())
        rows = json.loads(out.read_text())
        self.assertEqual(rows[0]["verified"], {"command": command, "passed": True})
        self.assertIn("verified by the production reader", result.stdout)
        # Without --verify the rows say so, and the comparator will not accept them.
        result, out = self.run_harness(self.stub(stream(), shape()), self.manifest())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNone(json.loads(out.read_text())[0]["verified"])
        self.assertIn("not verified by the production reader", result.stdout)
        # A refusal by the reader refuses the snapshot, with the reader's output.
        refusing = f"{sys.executable} -c \"import sys; print('Error: IR node 0 has no kind', file=sys.stderr); sys.exit(1)\" {{ir}} {{out}}"
        result, _ = self.run_harness(self.stub(stream(), shape()), self.manifest(), verify=refusing)
        self.assertEqual(result.returncode, 2)
        self.assertIn("the production reader refused the measured IR (exit 1)", result.stderr)
        self.assertIn("IR node 0 has no kind", result.stderr)

    def test_a_failing_frontend_is_refused(self):
        script = self.root / "failing"
        script.write_text("#!/bin/sh\necho 'error: no' >&2\nexit 3\n")
        script.chmod(script.stat().st_mode | stat.S_IEXEC)
        result, _ = self.run_harness(script, self.manifest())
        self.assertEqual(result.returncode, 2)
        self.assertIn("frontend exited with status 3", result.stderr)


if __name__ == "__main__":
    unittest.main()
