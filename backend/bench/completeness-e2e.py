#!/usr/bin/env python3
"""Completeness facts, end to end: a graph the JVM frontend built from real bytecode, saved,
then asked through both query engines.

The acme fixture is built with `--include com.acme`, so its calls into the JDK (and the
synthetic `invokedynamic` callee SootUp gives a lambda bootstrap) reach methods the build
never read: `analysisStatus` must say EXTERNAL for those and ANALYZED for the application's
own, and `dispatchGap` must name the calls on function values the build could not resolve.
A graph without the evidence would answer null for every one of them; equal counts in a
graph that recorded nothing would be indistinguishable from "everything analysed", which is
exactly the confusion the functions exist to remove.

    completeness-e2e.py <graph-dir> <graphite.jar> <graphite-binary>

Compares each engine's rows with `fixtures/acme/completeness.expected.json`.
"""
import json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
QUERIES = {
    "callSiteStatus": "MATCH (c:CallSite) RETURN analysisStatus(c) AS status, count(*) AS n ORDER BY status",
    "methodStatus": "MATCH (m:Method) RETURN analysisStatus(m) AS status, count(*) AS n ORDER BY status",
    "externalCallees": "MATCH (c:CallSite) WHERE analysisStatus(c) = 'EXTERNAL' "
                       "RETURN c.callee_class AS cls, count(*) AS n ORDER BY n DESC, cls",
    "dispatchGaps": "MATCH (c:CallSite) RETURN dispatchGap(c) AS gap, count(*) AS n ORDER BY gap",
    "applicationCalls": "MATCH (c:CallSite) WHERE c.callee_class STARTS WITH 'com.acme.' "
                        "RETURN analysisStatus(c) AS status, count(*) AS n ORDER BY status",
}


def rows(argv):
    out = subprocess.run(argv, capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit(f"{argv[0]} failed: {out.stderr}")
    return json.loads(out.stdout)["rows"]


def main():
    graph, jar, binary = sys.argv[1:4]
    expected = json.load(open(os.path.join(HERE, "fixtures/acme/completeness.expected.json")))
    failures = 0
    for name, query in QUERIES.items():
        for engine, argv in (("rust", [binary, "query", graph, query, "-f", "json"]),
                             ("kotlin", ["java", "-jar", jar, "query", graph, query, "-f", "json"])):
            actual = rows(argv)
            if actual != expected[name]:
                failures += 1
                print(f"--- {engine} {name}\n  expected {expected[name]}\n  actual   {actual}")
    print(f"completeness-e2e: {len(QUERIES) * 2 - failures} of {len(QUERIES) * 2} engine answers match")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
