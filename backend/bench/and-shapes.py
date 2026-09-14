#!/usr/bin/env python3
"""The conjunction shapes a production backtest actually runs.

Every query here mirrors one seen in a real backtest log, with the literals replaced by
terms that occur in the fixture corpus. What they share, and what neither `fixture64.py`
nor `shapes.py` had, is `AND`: two broad searches narrowed against each other, usually
with `DISTINCT` on the projection.

That combination is the whole point. The scan pushdown originally understood only `OR`,
so a single `AND` anywhere in the WHERE clause declined it and sent the query to the
generic evaluator — orders of magnitude slower, and past a 60s timeout on a real corpus.

Measured on fixture64 (64 graphs, 19.4M nodes), all ten digests identical:

    shape                     kotlin       rust
    or-baseline              4012.2ms     10.2ms
    and-of-or                 135.4ms     15.4ms     was 7218.7ms before the fix
    or-and-or                  33.1ms      1.3ms
    and-two-props              22.5ms      1.0ms
    and-empty-side             14.0ms      1.0ms
    equality-or-distinct      652.0ms      0.8ms
    or-distinct               393.0ms      7.4ms
    keys-projection            65.8ms      1.0ms
    and-three                3751.2ms      4.2ms     was 1966.7ms before the fix
    and-wrapped-lower          18.9ms      3.7ms
"""
import argparse, json, sys, time, urllib.request, urllib.error

FOUR = "n.caller_class, n.caller_name, n.callee_class, n.callee_name"
ALIASED = ("n.caller_class AS callerClass, n.caller_name AS callerName, "
           "n.callee_class AS calleeClass, n.callee_name AS calleeName")

SHAPES = [
    # The plain disjunction, as a control: this always had the pushdown.
    ("or-baseline",
     f"MATCH (n) WHERE n.caller_class CONTAINS 'Nonnull' OR n.callee_class CONTAINS 'Nonnull' "
     f"RETURN n.graphId, {FOUR} LIMIT 200"),

    # `X AND (a OR b OR c ...)` — one broad term narrowed by a set of alternatives.
    ("and-of-or",
     "MATCH (n) WHERE n.caller_class CONTAINS 'javax' AND (n.caller_class CONTAINS 'Nonnull' "
     "OR n.caller_class CONTAINS 'meta' OR n.caller_class CONTAINS 'When' "
     "OR n.caller_class CONTAINS 'Checker' OR n.caller_class CONTAINS 'Detector') "
     "RETURN DISTINCT n.caller_class AS class LIMIT 100"),

    # `(a OR b) AND (c OR d)` — the shape the intersection exists for.
    ("or-and-or",
     "MATCH (n) WHERE (n.caller_class CONTAINS 'Nonnull' OR n.callee_class CONTAINS 'Nonnull') "
     "AND (n.caller_class CONTAINS 'javax' OR n.callee_class CONTAINS 'javax') "
     f"RETURN DISTINCT {ALIASED} LIMIT 120"),

    # A two-term conjunction across two different properties, returning whole nodes.
    ("and-two-props",
     "MATCH (n) WHERE n.caller_class CONTAINS 'Nonnull' AND n.callee_class CONTAINS 'Object' "
     "RETURN n LIMIT 20"),

    # Conjunction where one side matches nothing: the intersection should collapse at once.
    ("and-empty-side",
     "MATCH (n) WHERE n.caller_class CONTAINS 'Nonnull' "
     "AND n.callee_class CONTAINS '__graphite_absent_term__' RETURN n LIMIT 20"),

    # Equality rather than CONTAINS, disjoined then made DISTINCT.
    ("equality-or-distinct",
     "MATCH (n) WHERE n.caller_class = 'javax.annotation.meta.When' "
     "OR n.callee_class = 'javax.annotation.meta.When' "
     f"RETURN DISTINCT {ALIASED} LIMIT 160"),

    # DISTINCT over a broad disjunction, no conjunction.
    ("or-distinct",
     "MATCH (n) WHERE n.caller_class CONTAINS 'javax' OR n.callee_class CONTAINS 'javax' "
     f"RETURN DISTINCT {ALIASED} LIMIT 200"),

    # `keys(n)` projection over a disjunction.
    ("keys-projection",
     "MATCH (n) WHERE n.caller_class CONTAINS 'Nonnull' OR n.caller_class CONTAINS 'When' "
     "RETURN keys(n) AS keys LIMIT 5"),

    # Three conjuncts.
    ("and-three",
     "MATCH (n) WHERE n.caller_class CONTAINS 'javax' AND n.caller_class CONTAINS 'annotation' "
     "AND n.callee_name CONTAINS 'init' RETURN DISTINCT n.caller_class AS c LIMIT 50"),

    # Case-insensitive wrapping inside a conjunction.
    ("and-wrapped-lower",
     "MATCH (n) WHERE toLower(coalesce(n.caller_class, '')) CONTAINS 'nonnull' "
     "AND toLower(coalesce(n.callee_class, '')) CONTAINS 'object' "
     f"RETURN DISTINCT {ALIASED} LIMIT 100"),
]


def call(base, query, timeout):
    body = json.dumps({"query": query}).encode()
    req = urllib.request.Request(base + "/api/cypher", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            payload = r.read()
            return (time.perf_counter() - started) * 1000.0, r.status, payload
    except urllib.error.HTTPError as e:
        return (time.perf_counter() - started) * 1000.0, e.code, e.read()
    except Exception:
        return (time.perf_counter() - started) * 1000.0, "TIMEOUT", b""


def digest(payload):
    """Hash the ordered rows, so a faster plan is also checked for the same answer."""
    import hashlib
    try:
        d = json.loads(payload)
    except Exception:
        return "unparseable", None
    rows = d.get("rows")
    if rows is None:
        return "error:" + str(d.get("error"))[:60], None
    h = hashlib.sha256()
    h.update(json.dumps(d.get("columns", []), sort_keys=True).encode())
    for row in rows:
        h.update(json.dumps(row, sort_keys=True).encode())
    return h.hexdigest()[:16], len(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--label", required=True)
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--out")
    args = ap.parse_args()

    rows, timeouts = [], 0
    for name, query in SHAPES:
        ms, status, payload = call(args.base, query, args.timeout)
        if status == "TIMEOUT":
            timeouts += 1
        d, n = digest(payload)
        rows.append({"shape": name, "ms": round(ms, 1), "status": status,
                     "rows": n, "digest": d})
        print(f"{args.label:7s} {name:22s} {ms:9.1f}ms  {status}  rows={n}  {d}", flush=True)

    print(f"\n{args.label}: {timeouts} of {len(SHAPES)} timed out at {args.timeout:.0f}s")
    if args.out:
        json.dump({"label": args.label, "timeouts": timeouts, "queries": rows},
                  open(args.out, "w"), indent=2)
    return 0


sys.exit(main())
