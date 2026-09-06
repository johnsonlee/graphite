#!/usr/bin/env python3
"""Regenerate the offline main JVM correctness oracle; never uses HTTP.

Usage: python3 regenerate-traversal.py /path/to/main/graphite-explore.jar
The supplied jar must be built from revision recorded in the corpus.
"""
import json
import pathlib
import subprocess
import sys
import tempfile

root = pathlib.Path(__file__).resolve().parent
jar = pathlib.Path(sys.argv[1]).resolve()
corpus = root / "traversal-jvm-oracle.json"
data = json.loads(corpus.read_text())
with tempfile.TemporaryDirectory(prefix="graphite-traversal-oracle-") as classes:
    subprocess.run(["javac", "-cp", str(jar), "-d", classes, str(root / "TraversalOracle.java")], check=True)
    command = ["java", "-Dfile.encoding=UTF-8", "-cp", classes + ":" + str(jar), "TraversalOracle", str(root / "traversal")]
    specs = data["cases"]
    output = subprocess.run(command, input="".join(json.dumps(case) + "\n" for case in specs), text=True, capture_output=True, check=True)
    results = [json.loads(line) for line in output.stdout.splitlines() if line.startswith("{")]
    assert len(results) == len(specs), output.stderr
    for spec, result in zip(specs, results):
        result["name"] = spec["name"]
    data["cases"] = results
corpus.write_text(json.dumps(data, indent=2) + "\n")
