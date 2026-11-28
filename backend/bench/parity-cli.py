#!/usr/bin/env python3
"""Differential test for `graphite query`: run both CLIs and diff bytes.

Unlike the HTTP suite, which parses JSON before comparing, this compares raw stdout and
stderr byte for byte along with the exit code. That is the only way to check the things
a CLI actually promises: column widths, padding, Gson's escaping, and the exact error
text a script might match on.

Cases run a few at a time, since each starts a JVM. Every worker queries its own copy of the
graph directory: the JVM CLI writes derived files (the transpose) beside a graph it queries,
and two JVMs doing that to one directory at once would make a case's answer depend on its
neighbours. A case's two CLIs always read the same copy, so they are compared like for like.
"""
import concurrent.futures, queue, shutil, subprocess, sys, os, tempfile

GRAPH = sys.argv[1] if len(sys.argv) > 1 else "/home/user/fixtures/explore-graph"
JAR = sys.argv[2] if len(sys.argv) > 2 else "frontend/jvm/query/build/libs/graphite.jar"
RUST = sys.argv[3] if len(sys.argv) > 3 else "target/release/graphite"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# The JVM prints these to stderr from the environment, not the program.
NOISE = (b"Picked up JAVA_TOOL_OPTIONS:", b"Picked up _JAVA_OPTIONS:")

def strip_noise(b):
    return b"".join(l + b"\n" for l in b.split(b"\n") if not l.startswith(NOISE) and l != b"") or b""

def run(argv):
    p = subprocess.run(argv, cwd=ROOT, capture_output=True)
    return p.returncode, p.stdout, strip_noise(p.stderr)

QUERIES = [
    # Shape of the table itself: widths, padding, the minimum column width.
    "RETURN 1 AS x",
    "RETURN 1.5 AS f, 'a\"b,c' AS s, true AS b, null AS n",
    "MATCH (n:CallSite) RETURN n.caller_class, n.callee_name LIMIT 3",
    "MATCH (n:CallSite) RETURN n.caller_class LIMIT 1",
    # Values whose toString() is not obvious: nodes carry null-valued keys that JSON drops.
    "MATCH (n:CallSite) RETURN n LIMIT 1",
    "MATCH (n:CallSite) RETURN n LIMIT 3",
    "MATCH (n:Method) RETURN n LIMIT 2",
    # Gson's HTML escaping, which a structural comparison cannot see.
    "MATCH (n:CallSite) RETURN n.callee_name LIMIT 5",
    "RETURN 'x&y=z' AS s",
    "RETURN \"it's\" AS s",
    "RETURN '<init>' AS s",
    # Numbers.
    "MATCH (n) RETURN count(*)",
    "MATCH (n:CallSite) RETURN count(*) AS c, count(*) * 2 AS d",
    "RETURN 2.0 AS whole, 0.1 AS tenth, -0.0 AS negzero",
    # Nulls and booleans in every format.
    "MATCH (n:CallSite) RETURN n.nosuch, n.caller_class IS NOT NULL LIMIT 2",
    # Empty result with columns, and ordering.
    "MATCH (n:CallSite) WHERE n.caller_class = 'nope' RETURN n.caller_class",
    "MATCH (n:CallSite) RETURN DISTINCT n.caller_class ORDER BY n.caller_class LIMIT 5",
    # Aggregation and grouping.
    "MATCH (n:CallSite) RETURN n.caller_class, count(*) AS c ORDER BY c DESC LIMIT 5",
    # Lists.
    "MATCH (n:CallSite) RETURN collect(n.callee_name)[0..3] AS names LIMIT 1",
    "MATCH (n:Method) RETURN n.parameter_types LIMIT 3",
    # Relationships and paths. The source node is pinned and the result ordered:
    # an unconstrained `MATCH (a)-[r]->(b)` returns different edges on consecutive runs
    # of the baseline itself, so it is no oracle for a byte comparison.
    "MATCH (a)-[r]->(b) WHERE id(a) = 4101 RETURN r ORDER BY id(b)",
    "MATCH p = (a)-[r]->(b) WHERE id(a) = 4101 RETURN p ORDER BY id(b)",
    "MATCH (a)-[r]->(b) WHERE id(a) = 4101 RETURN type(r), id(a), id(b) ORDER BY id(b)",
    # Nodes whose own `type` property (a declared type) shares the kind's key: the map
    # keeps one `type`, holding the property. Selected by label, not by an id, so every
    # fixture exercises them.
    "MATCH (n:LocalVariable) RETURN n ORDER BY id(n) LIMIT 3",
    "MATCH (n:Field) RETURN n ORDER BY id(n) LIMIT 3",
    "MATCH p = (a:LocalVariable)-[r]->(b) RETURN p ORDER BY id(a), id(b) LIMIT 3",
    # Aggregates nested in a larger expression, which the baseline refuses.
    "MATCH (n:CallSite) RETURN count(*) AS c, count(*) * 2 AS d",
    "MATCH (n:CallSite) RETURN count(*) + 1 AS c",
    "MATCH (n:CallSite) RETURN max(n.caller_class) AS m",
    # Error paths.
    "MATCH (n RETURN n",
    "RETURN nosuchfunction(1)",
    # Arithmetic both engines follow openCypher on: integer division, float powers, and an
    # integer overflow that fails rather than wrapping or widening.
    "RETURN 7 / 2 AS a, -7 / 2 AS b, 7 % -3 AS c, 7.0 / 2 AS d, 2 ^ 3 AS e",
    "RETURN 9223372036854775807 + 1 AS x",
    "RETURN 9223372036854775807 * 2 AS x",
    # Non-finite floats, which JSON cannot spell.
    "RETURN sqrt(-1) AS nan, exp(1000) AS inf, [-exp(1000)] AS l",
    # One-line errors for what the engines refuse.
    "RETURN 'x' =~ '[' AS x",
    "RETURN substring('abc', 5) AS x",
    "RETURN substring('abc', 2, -1) AS x",
    "RETURN substring('abc', 1, 9223372036854775807) AS x, substring('abc', 3, 9223372036854775807) AS y",
    "MATCH (n) DELETE n",
    "MATCH (n) REMOVE n.x RETURN 1",
    # Completeness: an absent fact told from an unrecorded one.
    "MATCH (c:CallSite) RETURN analysisStatus(c) AS s, count(*) AS n ORDER BY n DESC, s",
    "MATCH (m:Method) RETURN analysisStatus(m) AS s, count(*) AS n ORDER BY s",
    "MATCH (c:CallSite) RETURN dispatchGap(c) AS g, count(*) AS n ORDER BY g",
]

FORMATS = ["text", "json", "csv", "CSV", "bogus"]

WORKERS = max(1, min(4, os.cpu_count() or 1))
cases = []

def check(label, argv_tail):
    cases.append((label, argv_tail))

def compare(copies, argv_tail):
    """Both CLIs on one private copy of the graph; GRAPH in the arguments names that copy."""
    copy = copies.get()
    try:
        tail = [copy if arg is GRAPH else arg for arg in argv_tail]
        return run(["java", "-jar", JAR] + tail), run([RUST] + tail)
    finally:
        copies.put(copy)

for q in QUERIES:
    for fmt in FORMATS:
        check(f"query -f {fmt}: {q[:60]}", ["query", GRAPH, q, "-f", fmt])

# Flags, defaults and failure modes that have nothing to do with a query result.
check("default format", ["query", GRAPH, "RETURN 1 AS x"])
check("verbose", ["query", GRAPH, "RETURN 1 AS x", "-v"])
check("verbose long", ["query", GRAPH, "RETURN 1 AS x", "--verbose"])
check("verbose json", ["query", GRAPH, "MATCH (n:CallSite) RETURN n LIMIT 1", "-v", "-f", "json"])
check("format long", ["query", GRAPH, "RETURN 1 AS x", "--format", "csv"])
check("no such graph", ["query", "/nonexistent", "RETURN 1"])
check("CREATE", ["query", GRAPH, "CREATE (n:Foo)"])

passed = failed = 0
failures = []
with tempfile.TemporaryDirectory(prefix="graphite-parity-cli-") as scratch:
    copies = queue.Queue()
    for worker in range(WORKERS):
        copies.put(shutil.copytree(GRAPH, os.path.join(scratch, f"graph-{worker}")))
    with concurrent.futures.ThreadPoolExecutor(WORKERS) as pool:
        # Results in case order, whatever order the cases finished in.
        results = list(pool.map(lambda case: compare(copies, case[1]), cases))
for (label, _), (kot, rust) in zip(cases, results):
    if kot == rust:
        passed += 1
    else:
        failed += 1
        failures.append((label, kot, rust))

# A file is a container to the Rust CLI and not a graph to the jar, which reads directories
# only: the two explain the refusal differently by design, so only the exit code is compared.
kot_code = run(["java", "-jar", JAR, "query", JAR, "RETURN 1"])[0]
rust_code = run([RUST, "query", JAR, "RETURN 1"])[0]
if kot_code == rust_code:
    passed += 1
else:
    failed += 1
    failures.append(("graph is a file: exit code", (kot_code, b"", b""), (rust_code, b"", b"")))
print(f"cli parity: {passed} passed, {failed} failed of {passed + failed}")
for label, kot, rust in failures:
    print(f"\n--- {label}")
    for name, (code, out, err) in (("kotlin", kot), ("rust", rust)):
        print(f"  {name}: exit={code}")
        if out:
            print(f"    stdout: {out[:400]!r}")
        if err:
            print(f"    stderr: {err[:400]!r}")
sys.exit(1 if failed else 0)
