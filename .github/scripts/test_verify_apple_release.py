"""The release smoke must reject broken builds and semantically incomplete graphs."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("verify-apple-release.sh")
STUB = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
with open(os.environ["CALLS"], "a") as stream:
    stream.write(json.dumps(args) + "\n")
if args[0] == os.environ.get("FAIL_COMMAND"):
    sys.exit(7)
if args[0] == "build":
    root = pathlib.Path(args[1])
    assert (root / "Package.swift").is_file()
    assert not (root / ".build").exists(), "must start with a fresh compiler index"
    assert "--skip-build" not in args
    pathlib.Path(args[args.index("-o") + 1]).touch()
elif args[0] == "verify":
    assert pathlib.Path(args[1]).is_file()
elif args[0] == "query":
    query = args[2]
    if "m:Method" in query:
        values = ['"checkout(order:method:)"', '"init(client:)"']
    elif "s:Constant" in query:
        values = ['"payments.charge"', 'true']
    else:
        values = ['"checkout(order:method:)"']
    for value in values:
        if value != os.environ.get("OMIT_VALUE"):
            print(value)
'''


class ReleaseSmokeTest(unittest.TestCase):
    def run_smoke(self, **overrides):
        with tempfile.TemporaryDirectory(prefix="apple release ") as directory:
            root = Path(directory)
            fixture = root / "fixture"
            fixture.mkdir()
            (fixture / "Package.swift").touch()
            (fixture / ".build").mkdir()
            (fixture / ".build" / "stale-index").touch()
            binary = root / "graphite"
            binary.write_text(STUB)
            binary.chmod(0o755)
            calls = root / "calls"
            result = subprocess.run(
                ["bash", str(SCRIPT), str(binary), str(fixture), str(root / "output")],
                env={**os.environ, "CALLS": str(calls), **overrides},
                capture_output=True, text=True,
            )
            self.assertTrue((fixture / ".build" / "stale-index").is_file())
            commands = [json.loads(line)[0] for line in calls.read_text().splitlines()]
            return result, commands

    def test_fresh_build_and_persisted_semantic_queries(self):
        result, commands = self.run_smoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(commands, ["frontend", "build", "verify", "query", "query", "query"])

    def test_failed_build_stops_before_verification(self):
        result, commands = self.run_smoke(FAIL_COMMAND="build")
        self.assertEqual(result.returncode, 7)
        self.assertEqual(commands, ["frontend", "build"])

    def test_invalid_graph_stops_before_queries(self):
        result, commands = self.run_smoke(FAIL_COMMAND="verify")
        self.assertEqual(result.returncode, 7)
        self.assertNotIn("query", commands)

    def test_missing_declaration_rejects_graph(self):
        result, _ = self.run_smoke(OMIT_VALUE='"init(client:)"')
        self.assertNotEqual(result.returncode, 0)

    def test_missing_literal_rejects_graph(self):
        result, _ = self.run_smoke(OMIT_VALUE='"payments.charge"')
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
