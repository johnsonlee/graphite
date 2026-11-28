#!/usr/bin/env python3
"""Completeness evidence of an Apple build, end to end.

    apple-completeness-e2e.py <graphite> <graphite.jar> <package>

Builds the Swift package with the real CLI (Apple frontend, Graph IR, JVM importer), then asks
both query engines, the Rust CLI on the archive and the jar on the unpacked directory, what
every call of drive() reaches: the callee's analysis status and the call's dispatch gap. Both
must give the table below, which is the contract of docs/analysis-completeness.md ("The Apple
frontend") spelled out for frontend/apple/Fixtures/Dispatch.

Needs GRAPHITE_FRONTEND_APPLE and GRAPHITE_FRONTEND_JVM (or installed frontends) and a Swift
toolchain, as any Apple build does.
"""
import csv
import io
import os
import subprocess
import sys
import tempfile

QUERY = (
    "MATCH (c:CallSite) WHERE c.caller_name STARTS WITH 'drive' "
    "RETURN c.callee_class AS class, c.callee_name AS callee, analysisStatus(c) AS status, dispatchGap(c) AS gap "
    "ORDER BY c.line"
)
EXPECTED = [
    # A requirement with no default, which Base and Custom implement: nothing is linked to the call.
    ("Dispatch.Performing", "perform()", "ABSTRACT", "DISPATCH_UNRESOLVED"),
    # A default implementation (the named method) that Custom replaces: the other may run.
    ("Dispatch.Performing", "idle()", "ANALYZED", "DISPATCH_PARTIAL"),
    # A default implementation nothing replaces.
    ("Dispatch.Performing", "rest()", "ANALYZED", ""),
    # A body Leaf overrides.
    ("Dispatch.Base", "perform()", "ANALYZED", "DISPATCH_PARTIAL"),
    # Dynamically dispatched, overridden by nothing in the build.
    ("Dispatch.Base", "alone()", "ANALYZED", ""),
    # The override itself, a final method and a struct's method: bound statically.
    ("Dispatch.Leaf", "perform()", "ANALYZED", ""),
    ("Dispatch.Base", "sealed()", "ANALYZED", ""),
    ("Dispatch.Value", "read()", "ANALYZED", ""),
    # Synthesized by the compiler: the build holds no body for it.
    ("Dispatch.Value", "init()", "NO_BODY", ""),
    # The standard library is outside the build.
    ("Swift", "print(_:separator:terminator:)", "EXTERNAL", ""),
]
STATUSES = "MATCH (m:Method) WHERE analysisStatus(m) <> 'ANALYZED' RETURN m.class + '.' + m.name AS method, analysisStatus(m) AS status ORDER BY method"
EXPECTED_STATUSES = [("Dispatch.Performing.perform()", "ABSTRACT")]


def run(argv):
    result = subprocess.run(argv, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit("FAIL: %s exited %d\n%s%s" % (" ".join(argv[:3]), result.returncode, result.stdout, result.stderr))
    return result.stdout


def rows(text):
    return [tuple(row) for row in list(csv.reader(io.StringIO(text)))[1:]]


def main(argv):
    if len(argv) != 4:
        sys.exit(__doc__)
    graphite, jar, package = (os.path.abspath(path) for path in argv[1:])
    failures = []
    with tempfile.TemporaryDirectory(prefix="graphite-apple-completeness-") as work:
        archive, directory = os.path.join(work, "dispatch.graphite"), os.path.join(work, "dispatch")
        run([graphite, "build", package, "-o", archive])
        run([graphite, "unpack", archive, directory])
        if not os.path.exists(os.path.join(directory, "graph.completeness")):
            sys.exit("FAIL: the Apple build wrote no graph.completeness")
        engines = {
            "rust": lambda query: run([graphite, "query", archive, query, "-f", "csv"]),
            "jvm": lambda query: run(["java", "-jar", jar, "query", directory, query, "-f", "csv"]),
        }
        for name, ask in engines.items():
            for label, query, expected in (("calls of drive()", QUERY, EXPECTED), ("methods", STATUSES, EXPECTED_STATUSES)):
                actual = rows(ask(query))
                if actual != expected:
                    failures.append("%s, %s:\n  expected %s\n  actual   %s" % (name, label, expected, actual))
                else:
                    print("ok: %s answers %d rows for %s" % (name, len(actual), label))
    for failure in failures:
        print("FAIL: " + failure)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
