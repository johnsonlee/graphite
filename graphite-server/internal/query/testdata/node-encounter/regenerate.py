#!/usr/bin/env python3
"""Regenerate correctness-only source/query observations using pinned main jar.
Usage: python3 regenerate.py /path/to/graphite-explore.jar
Mapped supertype order is deliberately recorded, never declared canonical.
"""
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

root = pathlib.Path(__file__).resolve().parent
jar = pathlib.Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix="graphite-encounter-classes-") as classes:
    subprocess.run(["javac", "-cp", str(jar), "-d", classes, str(root / "GenerateEncounter.java"), str(root / "EncounterOracle.java")], check=True)
    cp = classes + ":" + str(jar)
    subprocess.run(["java", "-cp", cp, "GenerateEncounter", str(root / "mixed-sparse")], check=True)
    shutil.copytree(root.parent.parent.parent / "store/testdata/jvm-v3", root / "all-kinds", dirs_exist_ok=True)
    for fixture, center in [("mixed-sparse", 90), ("all-kinds", 0)]:
        for mode in ["MAPPED", "EAGER"]:
            for prehash in ["none", "forward", "reverse"]:
                output = root / (fixture + "-" + mode.lower() + "-" + prehash + ".json")
                subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", cp, "EncounterOracle", str(root / fixture), mode, prehash, str(center), str(output)], check=True)
    print("main jar SHA256", hashlib.sha256(jar.read_bytes()).hexdigest())
