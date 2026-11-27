#!/usr/bin/env python3
"""Single-bit flips over graph files, one line per flip.

    fuzz.py <graphite-binary> <graph-dir> <work-dir> <file>...

For each named file: 30 flips. Flip k uses random.Random(f"{file}:{k}") to choose a byte
offset in [0, size) and a bit in [0, 8), so every flip is reproducible on its own. The
damaged copy of the graph is asked QUERIES in order until one does not exit 0. Each line
prints the file, flip, offset, bit, the outcome (ok: every query exit 0; error: a query
exited 1; PANIC: a query exited 101) and the exit code and first stderr line per query
asked. A summary per file follows. GRAPHITE_VERIFY_GRAPHS in the environment is passed on.
"""
import collections, os, random, shutil, subprocess, sys

QUERIES = [
    "MATCH (c:CallSite) RETURN c.callee_class AS k, count(*) AS n ORDER BY n DESC, k LIMIT 3",
    "MATCH (a)-[r]->(b) RETURN type(r) AS t, count(*) AS n ORDER BY t",
    "MATCH (n) RETURN labels(n)[0] AS l, count(*) AS c ORDER BY l",
    "MATCH (n:LocalVariable) RETURN n.name, n.type ORDER BY n.id LIMIT 50",
]
FLIPS = 30


def main():
    binary, src, work, *files = sys.argv[1:]
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
                if r.returncode == 101:
                    outcome = "PANIC"
                    break
                if r.returncode != 0:
                    outcome = "error"
                    break
            tally[outcome] += 1
            print(f"{name} flip {k:2d} offset {offset} bit {bit}: {outcome}; " + "; ".join(asked))
        print(f"== {name}: {dict(sorted(tally.items()))}")
    shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
