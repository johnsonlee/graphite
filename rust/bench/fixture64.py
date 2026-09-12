#!/usr/bin/env python3
"""Cross-graph P95 over the repository's own 64-graph fixture.

This mirrors the shape of `LargeBroadQueryPressureBenchmark`'s `global-wide` family and
the way `.github/scripts/benchmark-gate.mjs` derives P95: the percentile is taken across
*queries*, each executed once, cold, single-threaded, with no warmup. That is a
different statistic from a per-endpoint request percentile, and it is the one this
repository gates on.

Both servers are queried over `/api/cypher`, which spans every loaded graph.
"""
import argparse, hashlib, json, sys, time, urllib.request, urllib.error

MANIFEST_COMMENT = "#"

def read_manifest(path):
    """`graphs.tsv`: id, path, zero-term, targeted-term, dense-term, fingerprint.

    Comment lines carry the `global-wide-distribution-v1` cases, each naming a request
    graph, a search term, and the graphs that term is expected to reach.
    """
    graphs, distributions = [], []
    for line in open(path):
        line = line.rstrip("\n")
        if not line.strip():
            continue
        if line.startswith(MANIFEST_COMMENT):
            parts = line.lstrip("#").strip().split("\t")
            if len(parts) >= 5 and parts[0] == "global-wide-distribution-v1":
                distributions.append({
                    "case": parts[1],
                    "requestGraph": parts[2],
                    "term": parts[3],
                    "expectedGraphs": parts[4].split(","),
                })
            continue
        parts = line.split("\t")
        if len(parts) >= 5:
            graphs.append({
                "id": parts[0], "path": parts[1],
                "zero": parts[2], "targeted": parts[3], "dense": parts[4],
                "fingerprint": parts[5] if len(parts) > 5 else None,
            })
    return graphs, distributions

def cypher_string(v):
    return v.replace("\\", "\\\\").replace("'", "\\'")

def wide_predicate(term):
    t = cypher_string(term)
    return (f"n.caller_class CONTAINS '{t}' OR n.caller_name CONTAINS '{t}' "
            f"OR n.callee_class CONTAINS '{t}' OR n.callee_name CONTAINS '{t}'")

def wide(term, projection):
    return f"MATCH (n)\nWHERE {wide_predicate(term)}\nRETURN {projection}\nLIMIT 200"

def wrapped(term):
    t = cypher_string(term.lower())
    return ("MATCH (n)\n"
            f"WHERE toLower(coalesce(n.caller_class, '')) CONTAINS '{t}'\n"
            f"   OR toLower(coalesce(n.caller_name, '')) CONTAINS '{t}'\n"
            f"   OR toLower(coalesce(n.callee_class, '')) CONTAINS '{t}'\n"
            f"   OR toLower(coalesce(n.callee_name, '')) CONTAINS '{t}'\n"
            "RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name\nLIMIT 200")

def wrapped_distinct(term):
    t = cypher_string(term.lower())
    return ("MATCH (n)\n"
            f"WHERE toLower(coalesce(n.caller_class, '')) CONTAINS '{t}'\n"
            f"   OR toLower(coalesce(n.caller_name, '')) CONTAINS '{t}'\n"
            f"   OR toLower(coalesce(n.callee_class, '')) CONTAINS '{t}'\n"
            f"   OR toLower(coalesce(n.callee_name, '')) CONTAINS '{t}'\n"
            "RETURN DISTINCT n.caller_class, n.caller_name, n.callee_class, n.callee_name\nLIMIT 200")

FOUR = "n.caller_class, n.caller_name, n.callee_class, n.callee_name"
ALIASED = ("n.caller_class AS caller, n.caller_name AS callerMethod, "
           "n.callee_class AS callee, n.callee_name AS calleeMethod")

# The `global-wide` shapes, in the benchmark's own order.
SHAPES = [
    ("global-wide-four-properties", lambda t: wide(t, FOUR)),
    ("global-wide-class-pair", lambda t: wide(t, "n.caller_class, n.callee_class")),
    ("global-wide-name-pair", lambda t: wide(t, "n.caller_name, n.callee_name")),
    ("global-wide-caller-class", lambda t: wide(t, "n.caller_class")),
    ("global-wide-callee-class", lambda t: wide(t, "n.callee_class")),
    ("global-wide-provenance", lambda t: wide(t, "n.graphId, " + FOUR)),
    ("global-wide-aliased", lambda t: wide(t, ALIASED)),
    # The benchmark's parameterized shape binds $term; the HTTP API takes no query
    # parameters, so it is run here in its literal form. Noted as a deviation.
    ("global-wide-parameterized-as-literal", lambda t: wide(t, FOUR)),
    ("global-wide-wrapped-case-insensitive", wrapped),
    ("global-wide-wrapped-case-insensitive-distinct", wrapped_distinct),
]

def call(base, query, timeout):
    body = json.dumps({"query": query}).encode()
    req = urllib.request.Request(base + "/api/cypher", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            payload, status = r.read(), r.status
    except urllib.error.HTTPError as e:
        payload, status = e.read(), e.code
    return (time.perf_counter() - t) * 1000.0, status, payload

def digest(payload):
    """SHA-256 over the ordered rows, so correctness is compared not just counted."""
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
        h.update(json.dumps({k: v for k, v in row.items() if k != "$metadata"},
                            sort_keys=True).encode())
    return h.hexdigest(), len(rows)

def percentile(values, fraction):
    """`pressurePercentile` from benchmark-gate.mjs."""
    s = sorted(values)
    import math
    return s[max(0, math.ceil(len(s) * fraction) - 1)]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--kotlin", default="http://localhost:18081")
    ap.add_argument("--rust", default="http://localhost:18080")
    ap.add_argument("--timeout", type=int, default=600)
    ap.add_argument("--out", default="fixture64-results.json")
    # Both servers together do not fit in memory on this host, and contention would
    # taint whichever one is measured. `--only` runs the plan against a single server
    # so each is measured alone; digests are recorded so the runs can still be diffed.
    ap.add_argument("--only", choices=("kotlin", "rust"))
    args = ap.parse_args()

    graphs, distributions = read_manifest(args.manifest)
    if not graphs:
        print("manifest carried no graphs", file=sys.stderr)
        return 1
    # Selectivity terms come from the request graph the distribution cases name.
    request_id = distributions[0]["requestGraph"] if distributions else graphs[0]["id"]
    request = next((g for g in graphs if g["id"] == request_id), graphs[0])
    work = [(level, request[level], f"selectivity:{level}")
            for level in ("zero", "targeted", "dense")]
    print(f"{len(graphs)} graphs; request graph {request['id']}")
    for level, term, _ in work:
        print(f"  {level:9s} term = {term[:70]}")
    print(f"  {len(distributions)} distribution cases\n")

    rows, mismatches = [], []
    # Ten shapes at three selectivities, then the distribution cases.
    plan = [(level, term, name, build)
            for level, term, _ in work for name, build in SHAPES]
    plan += [(d["case"], d["term"], "global-wide-four-properties",
              SHAPES[0][1]) for d in distributions]
    for level, term, name, build in plan:
        query = build(term)
        if args.only == "rust":
            kms, kstatus, kpayload = float("nan"), None, b"{}"
        else:
            kms, kstatus, kpayload = call(args.kotlin, query, args.timeout)
        if args.only == "kotlin":
            rms, rstatus, rpayload = float("nan"), None, b"{}"
        else:
            rms, rstatus, rpayload = call(args.rust, query, args.timeout)
        kd, kn = digest(kpayload)
        rd, rn = digest(rpayload)
        same = True if args.only else (kd == rd and kstatus == rstatus)
        if not same:
            mismatches.append({"shape": name, "selectivity": level,
                               "kotlin": kd[:24], "rust": rd[:24],
                               "kotlinRows": kn, "rustRows": rn,
                               "kotlinStatus": kstatus, "rustStatus": rstatus})
        rows.append({"shape": name, "selectivity": level,
                     "kotlin_ms": round(kms, 3), "rust_ms": round(rms, 3),
                     "speedup": round(kms / rms, 2) if rms and not args.only else None,
                     "rows": kn if args.only != "rust" else rn,
                     "digest": (rd if args.only == "rust" else kd),
                     "identical": same})
        print(f"{level:9s} {name:44s} kotlin={kms:9.1f}ms rust={rms:8.1f}ms "
              f"{'ok' if same else 'DIFFERS'}")

    summary = {"queries": len(rows), "graphs": len(graphs), "only": args.only,
               "mismatches": mismatches}
    for label in ("kotlin", "rust"):
        if args.only and args.only != label:
            continue
        v = [x[f"{label}_ms"] for x in rows]
        summary[f"{label}_p50_ms"] = round(percentile(v, 0.50), 3)
        summary[f"{label}_p95_ms"] = round(percentile(v, 0.95), 3)
    if not args.only:
        summary["identical_results"] = sum(1 for x in rows if x["identical"])
        summary["p95_speedup"] = round(summary["kotlin_p95_ms"] / summary["rust_p95_ms"], 2)
        summary["p50_speedup"] = round(summary["kotlin_p50_ms"] / summary["rust_p50_ms"], 2)
    print("\n" + json.dumps(summary, indent=2))
    json.dump({"queries": rows, "summary": summary}, open(args.out, "w"), indent=2)
    return 0

sys.exit(main())
