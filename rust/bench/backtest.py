#!/usr/bin/env python3
"""A backtest-shaped load: many terms across the query templates a real one uses.

The templates are taken from a production backtest log — broad `CONTAINS` searches over
the four CallSite string properties, combined with `OR` and `AND`, usually projected
`DISTINCT`. The terms are sampled from the corpus itself so the mix spans selectivities
the way a real run does: some match a great deal, some a little, some nothing.

This exists because the earlier benchmarks each measured one narrow thing. `fixture64.py`
runs ten variants of a single disjunction; `and-shapes.py` runs ten conjunctions. Neither
produces a P50 over a realistic population, which is the number a backtest reports.

Servers are measured one at a time: both together do not fit in memory, and contention
would taint whichever is being timed.
"""
import argparse, hashlib, json, math, random, re, sys, time, urllib.request, urllib.error

FOUR = "n.caller_class, n.caller_name, n.callee_class, n.callee_name"
ALIASED = ("n.caller_class AS callerClass, n.caller_name AS callerName, "
           "n.callee_class AS calleeClass, n.callee_name AS calleeName")


def esc(t):
    return t.replace("\\", "\\\\").replace('"', '\\"')


def wide_or(t):
    """The commonest shape: one term against all four properties."""
    t = esc(t)
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{t}" OR n.callee_class CONTAINS "{t}" '
            f'OR n.caller_name CONTAINS "{t}" OR n.callee_name CONTAINS "{t}" '
            f'RETURN n.graphId, {FOUR} LIMIT 200')


def two_term_or(a, b):
    a, b = esc(a), esc(b)
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{a}" OR n.callee_class CONTAINS "{a}" '
            f'OR n.caller_name CONTAINS "{b}" OR n.callee_name CONTAINS "{b}" '
            f'RETURN n.graphId, {FOUR} LIMIT 200')


def and_of_or(broad, alts):
    """`X AND (a OR b OR ...)` — a broad term narrowed by alternatives."""
    alt = " OR ".join(f'n.caller_class CONTAINS "{esc(a)}"' for a in alts)
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{esc(broad)}" AND ({alt}) '
            f'RETURN DISTINCT n.caller_class AS class LIMIT 100')


def or_and_or(a, b):
    a, b = esc(a), esc(b)
    return (f'MATCH (n) WHERE (n.caller_class CONTAINS "{a}" OR n.callee_class CONTAINS "{a}") '
            f'AND (n.caller_class CONTAINS "{b}" OR n.callee_class CONTAINS "{b}") '
            f'RETURN DISTINCT {ALIASED} LIMIT 120')


def and_two(a, b):
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{esc(a)}" '
            f'AND n.callee_class CONTAINS "{esc(b)}" RETURN n LIMIT 20')


def equality_or(cls):
    c = esc(cls)
    return (f'MATCH (n) WHERE n.caller_class = "{c}" OR n.callee_class = "{c}" '
            f'RETURN DISTINCT {ALIASED} LIMIT 160')


def distinct_or(t):
    t = esc(t)
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{t}" OR n.callee_class CONTAINS "{t}" '
            f'RETURN DISTINCT {ALIASED} LIMIT 200')


def keys_or(a, b):
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{esc(a)}" '
            f'OR n.caller_class CONTAINS "{esc(b)}" RETURN keys(n) AS keys LIMIT 5')


def call(base, query, timeout):
    body = json.dumps({"query": query}).encode()
    req = urllib.request.Request(base + "/api/cypher", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return (time.perf_counter() - started) * 1000.0, r.status, r.read()
    except urllib.error.HTTPError as e:
        return (time.perf_counter() - started) * 1000.0, e.code, e.read()
    except Exception as exc:
        return (time.perf_counter() - started) * 1000.0, f"FAILED:{type(exc).__name__}", b""


def digest(payload):
    try:
        d = json.loads(payload)
    except Exception:
        return "unparseable", None
    rows = d.get("rows")
    if rows is None:
        return "error:" + str(d.get("error"))[:40], None
    h = hashlib.sha256()
    h.update(json.dumps(d.get("columns", []), sort_keys=True).encode())
    for row in rows:
        h.update(json.dumps(row, sort_keys=True).encode())
    return h.hexdigest()[:16], len(rows)


def percentile(values, fraction):
    """`pressurePercentile` from benchmark-gate.mjs: the gate's own definition."""
    s = sorted(values)
    return s[max(0, math.ceil(len(s) * fraction) - 1)]


def sample_terms(base, timeout):
    """Identifier fragments drawn from the corpus, so selectivities are realistic."""
    _, _, payload = call(
        base,
        "MATCH (n:CallSite) RETURN DISTINCT n.caller_class AS c LIMIT 400",
        timeout,
    )
    classes = []
    try:
        for row in json.loads(payload).get("rows", []):
            v = row.get("c")
            if isinstance(v, str):
                classes.append(v)
    except Exception:
        pass
    words = set()
    for c in classes:
        for part in re.split(r"[.$]", c):
            for w in re.findall(r"[A-Z]?[a-z]{3,}|[A-Z]{3,}", part):
                words.add(w)
    return classes, sorted(words)


def build(classes, words, rng):
    """~170 queries, weighted the way the log is: mostly the broad single-term search."""
    queries = []
    absent = ["__graphite_absent_a__", "__graphite_absent_b__"]
    pool = words + absent
    for _ in range(60):
        queries.append(("wide-or", wide_or(rng.choice(pool))))
    for _ in range(25):
        queries.append(("two-term-or", two_term_or(rng.choice(pool), rng.choice(pool))))
    for _ in range(25):
        queries.append(("and-of-or", and_of_or(rng.choice(pool),
                                               [rng.choice(pool) for _ in range(5)])))
    for _ in range(20):
        queries.append(("or-and-or", or_and_or(rng.choice(pool), rng.choice(pool))))
    for _ in range(15):
        queries.append(("and-two", and_two(rng.choice(pool), rng.choice(pool))))
    for _ in range(10):
        queries.append(("equality-or", equality_or(rng.choice(classes) if classes else "x")))
    for _ in range(10):
        queries.append(("distinct-or", distinct_or(rng.choice(pool))))
    for _ in range(5):
        queries.append(("keys-or", keys_or(rng.choice(pool), rng.choice(pool))))
    rng.shuffle(queries)
    return queries


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--label", required=True)
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--seed", type=int, default=20260912)
    ap.add_argument("--out")
    args = ap.parse_args()

    rng = random.Random(args.seed)
    classes, words = sample_terms(args.base, args.timeout)
    if len(words) < 20:
        print(f"only {len(words)} terms sampled; is the server up and loaded?", file=sys.stderr)
        return 2
    queries = build(classes, words, rng)
    print(f"{args.label}: {len(queries)} queries from {len(words)} sampled terms", flush=True)

    rows, timeouts = [], 0
    for i, (shape, q) in enumerate(queries):
        ms, status, payload = call(args.base, q, args.timeout)
        if not isinstance(status, int):
            timeouts += 1
        d, n = digest(payload)
        rows.append({"i": i, "shape": shape, "query": q, "ms": round(ms, 1),
                     "status": status, "rows": n, "digest": d})
        if (i + 1) % 25 == 0:
            print(f"  {i + 1}/{len(queries)}", flush=True)

    lat = [r["ms"] for r in rows]
    summary = {
        "label": args.label,
        "queries": len(rows),
        "p50_ms": round(percentile(lat, 0.50), 2),
        "p95_ms": round(percentile(lat, 0.95), 2),
        "max_ms": round(max(lat), 2),
        "failures": timeouts,
    }
    print(json.dumps(summary, indent=2))
    if args.out:
        json.dump({"summary": summary, "queries": rows}, open(args.out, "w"), indent=2)
    return 0


sys.exit(main())
