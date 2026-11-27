#!/usr/bin/env python3
"""Single-bit flips over graph files, one line per flip.

    fuzz.py <graphite-binary> <graph-dir> <work-dir> <file>...

For each named file: 30 flips. Flip k uses random.Random(f"{file}:{k}") to choose a byte
offset in [0, size) and a bit in [0, 8), so every flip is reproducible on its own. The
damaged copy of the graph is asked QUERIES in order until one does not exit 0. Each line
prints the file, flip, offset, bit, the outcome and the raw exit code and first stderr line
per query asked. A summary per file follows. GRAPHITE_VERIFY_GRAPHS in the environment is
passed on.

Outcomes (`classify`): ok, every query exited 0; error, a query exited 1, the CLI's
controlled refusal; PANIC, a query exited 101, a Rust panic; CRASH, a query was killed by a
signal (a negative return code: SIGABRT, SIGSEGV, a kill); UNEXPECTED, any other code. Only
`error` counts as a clean rejection, and the script exits 3 if a run of the candidate (a
binary passed with --expect-clean) saw any PANIC, CRASH or UNEXPECTED.
"""
import collections, os, random, shutil, subprocess, sys

QUERIES = [
    "MATCH (c:CallSite) RETURN c.callee_class AS k, count(*) AS n ORDER BY n DESC, k LIMIT 3",
    "MATCH (a)-[r]->(b) RETURN type(r) AS t, count(*) AS n ORDER BY t",
    "MATCH (n) RETURN labels(n)[0] AS l, count(*) AS c ORDER BY l",
    "MATCH (n:LocalVariable) RETURN n.name, n.type ORDER BY n.id LIMIT 50",
]
FLIPS = 30


def classify(returncode):
    """The outcome of one query's exit status, as subprocess reports it."""
    if returncode == 0:
        return "ok"
    if returncode == 1:
        return "error"
    if returncode == 101:
        return "PANIC"
    if returncode < 0:
        return "CRASH"
    return "UNEXPECTED"


def main():
    args = sys.argv[1:]
    expect_clean = "--expect-clean" in args
    args = [a for a in args if a != "--expect-clean"]
    binary, src, work, *files = args
    unclean = 0
    for i, q in enumerate(QUERIES):
        print(f"# query {i}: {q}")
    for name in files:
        size = os.path.getsize(os.path.join(src, name))
        tally = collections.Counter()
        for k in range(FLIPS):
            rng = random.Random(f"{name}:{k}")
            offset, bit = rng.randrange(size), rng.randrange(8)
            shutil.rmtree(work, ignore_errors=True)
            shutil.copytree(src, work)
            path = os.path.join(work, name)
            with open(path, "r+b") as f:
                f.seek(offset)
                byte = f.read(1)[0]
                f.seek(offset)
                f.write(bytes([byte ^ (1 << bit)]))
            outcome, asked = "ok", []
            for i, q in enumerate(QUERIES):
                r = subprocess.run([binary, "query", work, q], capture_output=True, text=True,
                                   env=dict(os.environ, RUST_BACKTRACE="0"))
                first = next((l for l in r.stderr.splitlines() if l.strip()), "")
                asked.append(f"q{i}={r.returncode}" + (f" {first[:160]!r}" if r.returncode else ""))
                outcome = classify(r.returncode)
                if outcome != "ok":
                    break
            tally[outcome] += 1
            unclean += outcome in ("PANIC", "CRASH", "UNEXPECTED")
            print(f"{name} flip {k:2d} offset {offset} bit {bit}: {outcome}; " + "; ".join(asked))
        print(f"== {name}: {dict(sorted(tally.items()))}")
    shutil.rmtree(work, ignore_errors=True)
    if expect_clean and unclean:
        print(f"fuzz: {unclean} flips ended in a panic, crash or unexpected exit", file=sys.stderr)
        sys.exit(3)


if __name__ == "__main__":
    main()
