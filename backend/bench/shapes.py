#!/usr/bin/env python3
"""Timeout rate across query *shapes*, not one shape at many selectivities.

`fixture64.py` measures ten variants of a single family — a disjunction of CONTAINS over
four CallSite string properties — which is exactly the family the Rust scan pushdown
covers. A suite that only asks questions the fast path answers cannot discover that
everything else falls to the generic evaluator.

This runs a deliberately broad set at a real timeout and reports how many queries never
come back, which is the number that matters to someone actually using the thing.

Measured on fixture64 (64 graphs, 19.4M nodes), serially, 60s timeout:

    rust    0 of 36 timed out      slowest: order-by 46.0s, order-by-desc 24.4s,
                                   with-filter 19.4s, group-count 16.8s, id-lookup 11.7s
    kotlin  3 of 36 timed out      count-all, group-count, count-traversal; eight more
                                   returned 400 "Java heap space" after 30-58s

Neither side is healthy here. The Rust numbers are the ones this port owns: a point
lookup (`WHERE id(n) = 4101`) taking 11.7 seconds is a full scan of 19.4M nodes
evaluating a constant comparison, and `ORDER BY ... LIMIT 200` materialises and sorts
everything rather than keeping a bounded top-N. Both are shapes the `global-wide`
benchmark family never asks for, which is why they went unnoticed.
"""
import argparse, json, sys, time, urllib.request, urllib.error

SHAPES = [
    # --- the family fixture64 already covers, kept as a control ---
    ("wide-contains", "MATCH (n) WHERE n.caller_class CONTAINS 'Activity' RETURN n.caller_class LIMIT 200"),

    # --- plain label scans with no string predicate ---
    ("label-scan-callsite", "MATCH (n:CallSite) RETURN n.caller_class, n.callee_name LIMIT 200"),
    ("label-scan-method", "MATCH (n:Method) RETURN n.signature LIMIT 200"),
    ("label-scan-constant", "MATCH (n:Constant) RETURN n.value LIMIT 200"),
    ("label-scan-field", "MATCH (n:FieldNode) RETURN n.name LIMIT 200"),
    ("all-nodes", "MATCH (n) RETURN n LIMIT 200"),

    # --- equality and numeric predicates, not CONTAINS ---
    ("equality", "MATCH (n:CallSite) WHERE n.callee_name = 'valueOf' RETURN n.caller_class LIMIT 200"),
    ("inequality", "MATCH (n:CallSite) WHERE n.callee_name <> 'valueOf' RETURN n.caller_class LIMIT 200"),
    ("id-lookup", "MATCH (n) WHERE id(n) = 4101 RETURN n"),
    ("in-list", "MATCH (n:CallSite) WHERE n.callee_name IN ['valueOf', 'toString'] RETURN n.caller_class LIMIT 200"),
    ("null-check", "MATCH (n:CallSite) WHERE n.line IS NULL RETURN n.caller_class LIMIT 200"),
    ("regex", "MATCH (n:CallSite) WHERE n.callee_name =~ '.*alue.*' RETURN n.callee_name LIMIT 200"),

    # --- traversal, which no scan pushdown touches ---
    ("one-hop", "MATCH (a:CallSite)-[r]->(b) RETURN a.caller_class, b.id LIMIT 200"),
    ("one-hop-typed", "MATCH (a)-[r:DATAFLOW]->(b) RETURN a.id, b.id LIMIT 200"),
    ("two-hop", "MATCH (a)-[]->(b)-[]->(c) RETURN a.id, c.id LIMIT 200"),
    ("incoming", "MATCH (a)<-[r]-(b) RETURN a.id, b.id LIMIT 200"),
    ("var-length", "MATCH (a)-[*1..2]->(b) RETURN a.id, b.id LIMIT 200"),
    ("path", "MATCH p = (a)-[r]->(b) RETURN p LIMIT 50"),
    ("traverse-filtered", "MATCH (a:CallSite)-[r]->(b) WHERE a.callee_name = 'valueOf' RETURN b.id LIMIT 200"),

    # --- aggregation and grouping ---
    ("count-all", "MATCH (n) RETURN count(*)"),
    ("count-label", "MATCH (n:CallSite) RETURN count(*)"),
    ("group-count", "MATCH (n:CallSite) RETURN n.callee_class, count(*) AS c ORDER BY c DESC LIMIT 20"),
    ("distinct", "MATCH (n:CallSite) RETURN DISTINCT n.callee_class LIMIT 200"),
    ("collect", "MATCH (n:CallSite) RETURN collect(n.callee_name)[0..10] AS names"),
    ("count-traversal", "MATCH (a)-[r]->(b) RETURN count(*)"),

    # --- ordering, which forces a full materialisation ---
    ("order-by", "MATCH (n:CallSite) RETURN n.caller_class ORDER BY n.caller_class LIMIT 200"),
    ("order-by-desc", "MATCH (n:CallSite) RETURN n.callee_name ORDER BY n.callee_name DESC LIMIT 200"),
    ("skip", "MATCH (n:CallSite) RETURN n.caller_class SKIP 1000 LIMIT 200"),

    # --- expressions and functions ---
    ("function-calls", "MATCH (n:CallSite) RETURN toUpper(n.callee_name), size(n.caller_class) LIMIT 200"),
    ("case-expr", "MATCH (n:CallSite) RETURN CASE WHEN n.callee_name = 'valueOf' THEN 1 ELSE 0 END AS f LIMIT 200"),
    ("coalesce", "MATCH (n:CallSite) RETURN coalesce(n.line, -1) AS l LIMIT 200"),
    ("labels-fn", "MATCH (n) RETURN labels(n) LIMIT 200"),

    # --- WITH pipelines ---
    ("with-filter", "MATCH (n:CallSite) WITH n.callee_class AS c WHERE c IS NOT NULL RETURN c LIMIT 200"),
    ("with-aggregate", "MATCH (n:CallSite) WITH n.callee_class AS c, count(*) AS k WHERE k > 5 RETURN c, k LIMIT 50"),

    # --- multi-pattern ---
    ("cartesian", "MATCH (a:Method), (b:Method) RETURN a.id, b.id LIMIT 50"),
    ("union", "MATCH (n:CallSite) RETURN n.id LIMIT 100 UNION MATCH (n:Method) RETURN n.id LIMIT 100"),
]


def call(base, query, timeout):
    body = json.dumps({"query": query}).encode()
    req = urllib.request.Request(base + "/api/cypher", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            r.read()
            return (time.perf_counter() - started) * 1000.0, r.status
    except urllib.error.HTTPError as e:
        e.read()
        return (time.perf_counter() - started) * 1000.0, e.code
    except Exception:
        return (time.perf_counter() - started) * 1000.0, "TIMEOUT"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--label", required=True)
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--out")
    args = ap.parse_args()

    rows, timeouts = [], 0
    for name, query in SHAPES:
        ms, status = call(args.base, query, args.timeout)
        if status == "TIMEOUT":
            timeouts += 1
        rows.append({"shape": name, "ms": round(ms, 1), "status": status})
        print(f"{args.label:7s} {name:22s} {ms:9.1f}ms  {status}", flush=True)

    print(f"\n{args.label}: {timeouts} of {len(SHAPES)} timed out at {args.timeout:.0f}s")
    if args.out:
        json.dump({"label": args.label, "timeouts": timeouts, "queries": rows},
                  open(args.out, "w"), indent=2)
    return 0


sys.exit(main())
