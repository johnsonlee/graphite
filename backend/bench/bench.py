#!/usr/bin/env python3
"""Measure per-request latency for both servers over the same scenario set.

Each scenario is run `--warmup` times to prime page cache and JIT, then `--iters`
times for measurement. P50/P95/max are computed over the measured requests.
"""
import argparse, json, statistics, sys, time, urllib.request, urllib.error

def call(base, method, path, body):
    data = body.encode() if body else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            payload = r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        payload = e.read()
        status = e.code
    return (time.perf_counter() - t) * 1000.0, status, payload

def rows_of(payload):
    try:
        return json.loads(payload).get("rowCount")
    except Exception:
        return None

def cypher(q):
    return ("POST", "/api/graphs/app/cypher", json.dumps({"query": q}))

TERM = "javalin"
SCENARIOS = [
    ("global-wide-four-properties", *cypher(
        f"MATCH (n) WHERE n.caller_class CONTAINS '{TERM}' OR n.caller_name CONTAINS '{TERM}' "
        f"OR n.callee_class CONTAINS '{TERM}' OR n.callee_name CONTAINS '{TERM}' "
        "RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200")),
    ("global-wide-class-pair", *cypher(
        f"MATCH (n) WHERE n.caller_class CONTAINS '{TERM}' OR n.caller_name CONTAINS '{TERM}' "
        f"OR n.callee_class CONTAINS '{TERM}' OR n.callee_name CONTAINS '{TERM}' "
        "RETURN n.caller_class, n.callee_class LIMIT 200")),
    ("global-wide-callee-class", *cypher(
        f"MATCH (n) WHERE n.caller_class CONTAINS '{TERM}' OR n.caller_name CONTAINS '{TERM}' "
        f"OR n.callee_class CONTAINS '{TERM}' OR n.callee_name CONTAINS '{TERM}' "
        "RETURN n.callee_class LIMIT 200")),
    ("wrapped-case-insensitive", *cypher(
        f"MATCH (n) WHERE toLower(coalesce(n.caller_class, '')) CONTAINS '{TERM}' "
        f"OR toLower(coalesce(n.caller_name, '')) CONTAINS '{TERM}' "
        f"OR toLower(coalesce(n.callee_class, '')) CONTAINS '{TERM}' "
        f"OR toLower(coalesce(n.callee_name, '')) CONTAINS '{TERM}' "
        "RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200")),
    ("zero-hit-term", *cypher(
        "MATCH (n) WHERE n.caller_class CONTAINS 'zzqqxx' OR n.callee_class CONTAINS 'zzqqxx' "
        "RETURN n.caller_class LIMIT 200")),
    ("dense-term-get", *cypher(
        "MATCH (n) WHERE n.callee_name CONTAINS 'get' RETURN n.callee_class, n.callee_name LIMIT 200")),
    ("prefix-package-scan", *cypher(
        "MATCH (n:CallSiteNode) WHERE n.callee_class STARTS WITH 'java.util' "
        "RETURN n.callee_class, n.callee_name LIMIT 200")),
    ("suffix-scan", *cypher(
        "MATCH (n:CallSiteNode) WHERE n.callee_class ENDS WITH 'Objects' RETURN n.callee_class LIMIT 200")),
    ("exact-name-match", *cypher(
        "MATCH (n:CallSiteNode) WHERE n.callee_name = 'toString' RETURN n.caller_class LIMIT 200")),
    ("count-star", *cypher("MATCH (n:CallSiteNode) RETURN count(*)")),
    ("ordered-distinct-limit", *cypher(
        "MATCH (n:StringConstant) RETURN DISTINCT n.value AS v ORDER BY v LIMIT 100")),
    ("group-by-callee-class", *cypher(
        "MATCH (n:CallSiteNode) RETURN n.callee_class, count(*) AS c ORDER BY c DESC LIMIT 20")),
    ("method-discovery", *cypher("MATCH (n:Method) RETURN n.signature LIMIT 200")),
    ("single-hop-relationship", *cypher(
        "MATCH (a:CallSiteNode)-[r]->(b) RETURN a.callee_name, type(r) LIMIT 200")),
    ("node-scan-limit", *cypher("MATCH (n:CallSiteNode) RETURN n LIMIT 100")),
    ("rest-graphs", "GET", "/api/graphs", None),
    ("rest-overview", "GET", "/api/overview?limit=200", None),
    ("rest-node", "GET", "/api/graphs/app/node/4090", None),
    ("rest-node-outgoing", "GET", "/api/graphs/app/node/4090/outgoing?limit=200", None),
    ("rest-subgraph-depth2", "GET",
     "/api/graphs/app/subgraph?center=4090&depth=2&direction=outgoing", None),
    ("rest-endpoints", "GET", "/api/graphs/app/endpoints?limit=200", None),
    ("rest-c4-context-json", "GET",
     "/api/graphs/app/architecture/c4?level=context&format=json", None),
    ("rest-c4-context-mermaid", "GET",
     "/api/graphs/app/architecture/c4?level=context&format=mermaid", None),
]

def percentile(values, fraction):
    s = sorted(values)
    return s[max(0, -(-len(s) * fraction // 1).__int__() - 1 + 1 - 1)] if False else s[max(0, int(-(-len(s) * fraction // 1)) - 1)]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kotlin", default="http://localhost:18081")
    ap.add_argument("--rust", default="http://localhost:18080")
    ap.add_argument("--warmup", type=int, default=3)
    ap.add_argument("--iters", type=int, default=15)
    ap.add_argument("--out", default="results.json")
    args = ap.parse_args()

    results = []
    for name, method, path, body in SCENARIOS:
        row = {"scenario": name}
        for label, base in (("kotlin", args.kotlin), ("rust", args.rust)):
            for _ in range(args.warmup):
                call(base, method, path, body)
            samples, status, rows = [], None, None
            for _ in range(args.iters):
                ms, status, payload = call(base, method, path, body)
                samples.append(ms)
                rows = rows_of(payload)
            row[label] = {
                "p50": round(percentile(samples, 0.50), 3),
                "p95": round(percentile(samples, 0.95), 3),
                "max": round(max(samples), 3),
                "status": status,
                "rowCount": rows,
            }
        k, r = row["kotlin"], row["rust"]
        row["p95_speedup"] = round(k["p95"] / r["p95"], 2) if r["p95"] > 0 else None
        row["p50_speedup"] = round(k["p50"] / r["p50"], 2) if r["p50"] > 0 else None
        row["rows_match"] = k["rowCount"] == r["rowCount"] and k["status"] == r["status"]
        results.append(row)
        print(f"{name:32s} kotlin p95={k['p95']:9.3f}ms  rust p95={r['p95']:8.3f}ms  "
              f"speedup={row['p95_speedup']}x  rows={k['rowCount']}/{r['rowCount']}"
              f"{'' if row['rows_match'] else '  MISMATCH'}")

    all_k = [r["kotlin"]["p95"] for r in results]
    all_r = [r["rust"]["p95"] for r in results]
    agg = {
        "kotlin_aggregate_p95": round(percentile(all_k, 0.95), 3),
        "rust_aggregate_p95": round(percentile(all_r, 0.95), 3),
        "kotlin_p95_sum": round(sum(all_k), 3),
        "rust_p95_sum": round(sum(all_r), 3),
    }
    agg["aggregate_p95_speedup"] = round(agg["kotlin_aggregate_p95"] / agg["rust_aggregate_p95"], 2)
    agg["total_p95_speedup"] = round(agg["kotlin_p95_sum"] / agg["rust_p95_sum"], 2)
    speedups = [r["p95_speedup"] for r in results if r["p95_speedup"]]
    agg["median_scenario_speedup"] = round(statistics.median(speedups), 2)
    agg["min_scenario_speedup"] = round(min(speedups), 2)
    mismatches = [r["scenario"] for r in results if not r["rows_match"]]
    agg["correctness_mismatches"] = mismatches

    print("\n" + json.dumps(agg, indent=2))
    json.dump({"scenarios": results, "aggregate": agg,
               "config": {"warmup": args.warmup, "iters": args.iters}},
              open(args.out, "w"), indent=2)
    print(f"\nwrote {args.out}")

main()
