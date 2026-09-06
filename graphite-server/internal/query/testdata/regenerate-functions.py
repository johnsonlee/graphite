#!/usr/bin/env python3
"""Recapture complete local JVM function corpora using the pinned main jar.

Usage: python3 regenerate-functions.py /path/to/graphite-explore.jar
No HTTP, benchmark, or runtime backend is involved.
"""
import json
import pathlib
import subprocess
import sys
import tempfile

root = pathlib.Path(__file__).resolve().parent
jar = pathlib.Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix="graphite-functions-oracle-") as classes:
    subprocess.run(["javac", "-cp", str(jar), "-d", classes, str(root / "FunctionsOracle.java")], check=True)
    for corpus in [root / "functions-jvm-oracle.json", root / "functions-node-jvm-oracle.json", root / "property-order-jvm-oracle.json", root / "enum-key-jvm-oracle.json", root / "candidate-slot-jvm-oracle.json"]:
        data = json.loads(corpus.read_text())
        fixture = root.parent / data.get("fixture", "testdata/traversal")
        command = ["java", "-Dfile.encoding=UTF-8", "-cp", classes + ":" + str(jar), "FunctionsOracle", str(fixture)]
        repetitions = data.get("repetitionsPerCase", 1)
        specs = data["cases"] * repetitions
        output = subprocess.run(command, input="".join(json.dumps(case) + "\n" for case in specs), text=True, capture_output=True, check=True)
        results = [json.loads(line) for line in output.stdout.split("\n") if line.startswith("{")]
        assert len(results) == len(specs), output.stderr
        for i in range(len(data["cases"])):
            assert all(results[j] == results[i] for j in range(i, len(results), len(data["cases"]))), data["cases"][i]
        results = results[:len(data["cases"])]
        for spec, result in zip(data["cases"], results):
            result["name"] = spec["name"]
        data["cases"] = results
        corpus.write_text(json.dumps(data, indent=2) + "\n")
