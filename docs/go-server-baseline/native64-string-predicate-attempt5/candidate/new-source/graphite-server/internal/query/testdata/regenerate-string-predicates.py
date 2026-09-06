#!/usr/bin/env python3
"""Capture tiny-fixture string predicate correctness cases from the pinned main JVM.
Usage: python3 regenerate-string-predicates.py /path/to/graphite-explore.jar
No HTTP or performance workload is involved.
"""
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile

root = pathlib.Path(__file__).resolve().parent
jar = pathlib.Path(sys.argv[1]).resolve()
pairs = [
    ("empty-empty", "", ""), ("empty-needle", "abc", ""),
    ("empty-haystack", "", "a"), ("equal", "abc", "abc"),
    ("prefix", "abc", "ab"), ("suffix", "abc", "bc"),
    ("middle", "abcd", "bc"), ("absent", "abc", "x"),
    ("longer", "abc", "abcd"), ("case-sensitive", "ABC", "abc"),
    ("chinese", "甲中文乙", "中文"), ("japanese", "日本語", "語"),
    ("korean", "한국어", "한국"), ("supplementary", "😀中😀", "😀"),
    ("supplementary-middle", "a😀b", "😀"),
    ("different-supplementary", "😀", "😁"),
    ("combining", "e\u0301", "\u0301"),
    ("no-normalization", "é", "e\u0301"),
    ("nul", "a\u0000b", "\u0000"), ("replacement", "�", "�"),
    ("high-half", "😀", "\ud83d"), ("low-half", "😀", "\ude00"),
    ("isolated-high", "\ud83dx", "\ud83d"),
    ("isolated-low", "x\ude00", "\ude00"),
    ("reverse-halves", "\ude00\ud83d", "\ud83d\ude00"),
    ("high-is-not-replacement", "\ud83d", "�"),
    ("low-is-not-replacement", "�", "\ude00"),
    ("paired-surrogates", "\ud83d\ude00", "😀"),
    ("across-pair-boundary", "😀😁", "\ude00\ud83d"),
    ("high-empty", "\ud83d", ""), ("empty-low", "", "\ude00"),
]
# JSON's double-quoted literals and Unicode escapes are also Cypher literals.
def literal(value):
    return json.dumps(value, ensure_ascii=True)

cases = []
for op in ["STARTS WITH", "ENDS WITH", "CONTAINS", "NOT STARTS WITH", "NOT ENDS WITH", "NOT CONTAINS"]:
    for name, left, right in pairs:
        cases.append({"name": op.lower().replace(" ", "-") + "-" + name,
                      "query": f"RETURN {literal(left)} {op} {literal(right)} AS value"})
    for name, left, right in [
        ("null-left-skips-error", "null", "1/0"),
        ("number-left-skips-error", "123", "1/0"),
        ("list-left-skips-error", "[]", "1/0"),
        ("map-left-skips-error", "{}", "1/0"),
        ("null-right", "'abc'", "null"),
        ("number-right", "'abc'", "123"),
        ("list-right", "'abc'", "[]"),
        ("right-error", "'abc'", "1/0"),
        ("left-error", "1/0", "substring('abc','wrong')"),
        ("right-function-null", "'abc'", "sqrt('wrong')"),
        ("right-class-error", "'abc'", "substring('abc','wrong')"),
    ]:
        cases.append({"name": op.lower().replace(" ", "-") + "-" + name,
                      "query": f"RETURN ({left}) {op} ({right}) AS value"})

with tempfile.TemporaryDirectory(prefix="graphite-string-predicates-") as classes:
    subprocess.run(["javac", "-cp", str(jar), "-d", classes, str(root / "FunctionsOracle.java")], check=True)
    command = ["java", "-Dfile.encoding=UTF-8", "-cp", classes + ":" + str(jar), "FunctionsOracle", str(root.parent.parent / "store/testdata/jvm-v3")]
    # Repeat all queries to ensure that these complete outputs are stable.
    output = subprocess.run(command, input="".join(json.dumps(case) + "\n" for case in cases * 3), text=True, capture_output=True, check=True)
    results = [json.loads(line) for line in output.stdout.splitlines() if line.startswith("{")]
    assert len(results) == len(cases) * 3, output.stderr
    for i, case in enumerate(cases):
        assert results[i] == results[i + len(cases)] == results[i + 2 * len(cases)], case
        results[i]["name"] = case["name"]
    data = {"mainCommit": "4e328b0109e13c896b74004823fb049fcb19251a",
            "java": subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.strip(),
            "jarSHA256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "fixture": "../store/testdata/jvm-v3", "repetitionsPerCase": 3,
            "cases": results[:len(cases)]}
    (root / "string-predicate-jvm-oracle.json").write_text(json.dumps(data, indent=2) + "\n")
