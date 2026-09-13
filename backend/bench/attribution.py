#!/usr/bin/env python3
"""Separate the runtime's contribution from the query strategy's.

Three servers run the same requests: the Kotlin baseline, a Rust server with index
fast paths and scan pushdown disabled, and the full Rust server. The middle one runs
the same row-by-row algorithm as Kotlin, so Kotlin -> Rust-plain isolates the runtime,
and Rust-plain -> Rust-full isolates the query strategy.
"""
import json, statistics, sys, time, urllib.request, urllib.error

KOTLIN, PLAIN, FULL = "http://localhost:18081", "http://localhost:18082", "http://localhost:18080"

def call(base, method, path, body):
    data = body.encode() if body else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            payload, status = r.read(), r.status
    except urllib.error.HTTPError as e:
        payload, status = e.read(), e.code
    return (time.perf_counter() - t) * 1000.0, status, payload

def p95(values):
    s = sorted(values)
    return s[max(0, int(-(-len(s) * 0.95)) - 1)]

sys.path.insert(0, ".")
from bench import SCENARIOS  # noqa: E402

WARMUP, ITERS = 3, 12
rows = []
for name, method, path, body in SCENARIOS:
    got = {}
    for label, base in (("kotlin", KOTLIN), ("plain", PLAIN), ("full", FULL)):
        for _ in range(WARMUP):
            call(base, method, path, body)
        samples = [call(base, method, path, body)[0] for _ in range(ITERS)]
        got[label] = p95(samples)
    runtime = got["kotlin"] / got["plain"] if got["plain"] else None
    strategy = got["plain"] / got["full"] if got["full"] else None
    total = got["kotlin"] / got["full"] if got["full"] else None
    rows.append({"scenario": name, **{k: round(v, 3) for k, v in got.items()},
                 "runtime_x": round(runtime, 2), "strategy_x": round(strategy, 2),
                 "total_x": round(total, 2)})
    print(f"{name:28s} kotlin={got['kotlin']:9.2f}  rust-plain={got['plain']:9.2f}  "
          f"rust-full={got['full']:8.2f}   runtime={runtime:6.2f}x  strategy={strategy:7.2f}x")

runtime_all = [r["runtime_x"] for r in rows]
strategy_all = [r["strategy_x"] for r in rows]
summary = {
    "median_runtime_speedup": round(statistics.median(runtime_all), 2),
    "median_strategy_speedup": round(statistics.median(strategy_all), 2),
    "kotlin_p95_sum": round(sum(r["kotlin"] for r in rows), 1),
    "rust_plain_p95_sum": round(sum(r["plain"] for r in rows), 1),
    "rust_full_p95_sum": round(sum(r["full"] for r in rows), 1),
}
summary["total_runtime_speedup"] = round(summary["kotlin_p95_sum"] / summary["rust_plain_p95_sum"], 2)
summary["total_strategy_speedup"] = round(summary["rust_plain_p95_sum"] / summary["rust_full_p95_sum"], 2)
print("\n" + json.dumps(summary, indent=2))
json.dump({"scenarios": rows, "summary": summary}, open("attribution.json", "w"), indent=2)
