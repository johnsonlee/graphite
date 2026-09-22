#!/usr/bin/env python3
"""Absolute latency snapshot of the Rust engine over the fixture64 corpus.

This is what the benchmark observatory (`.github/workflows/benchmark-pages.yml`) records for
every main commit: `graphite serve` opens the 64 fixture graphs, and the cross-graph plan of
`fixture64.py` (ten `global-wide` shapes at three selectivities, then the distribution cases)
runs against `/api/cypher`, single-threaded, `--repetitions` times. Each row is written in
the JMH result shape the page renderer already understands:

    benchmark      rust.fixture64.<shape>          (or rust.fixture64.aggregate)
    params         {"selectivity": ...} / {"case": ...} / {"statistic": "p50"|"p95"}
    mode           sequential-pass
    primaryMetric  score = median over repetitions, scoreUnit = ms/op,
                   scoreConfidence = [min, max] over repetitions, rawData = every sample

The aggregate rows are the statistic the README and the gate quote: the P50 and P95 taken
across the queries of one pass, each query counted once. The first pass runs cold; later
passes run on a warm page cache, so the interval is the spread between them.

Any response that is not HTTP 200, or any row whose digest changes between passes, fails
the snapshot (exit 2): a page that silently plots errors or unstable answers is worse than
no page.
"""
import argparse, json, os, pathlib, signal, subprocess, sys, time, urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import fixture64  # noqa: E402

BENCHMARK_PREFIX = "rust.fixture64."
MODE = "sequential-pass"
UNIT = "ms/op"


def plan(graphs, distributions):
    """The fixture64 plan: (params, shape, query) triples in the harness's own order."""
    request_id = distributions[0]["requestGraph"] if distributions else graphs[0]["id"]
    request = next((g for g in graphs if g["id"] == request_id), graphs[0])
    rows = []
    for level in ("zero", "targeted", "dense"):
        for name, build in fixture64.SHAPES:
            rows.append(({"selectivity": level}, name, build(request[level])))
    for d in distributions:
        rows.append(({"case": d["case"]}, "global-wide-four-properties",
                     fixture64.SHAPES[0][1](d["term"])))
    for name, query in SCHEMA_SHAPES:
        rows.append(({"selectivity": "schema"}, name, query))
    return rows


# The schema-exploration shapes an agent runs first against a fleet, answered per type
# rather than per node on both revisions; measured over the whole corpus, so a fallback
# to the row pipeline shows as a regression against the base.
SCHEMA_SHAPES = [
    ("schema-label-histogram",
     "MATCH (n) RETURN labels(n) AS labels, count(*) AS c ORDER BY c DESC LIMIT 40"),
    ("schema-key-histogram",
     "MATCH (n) UNWIND keys(n) AS k RETURN k, count(*) AS c ORDER BY c DESC LIMIT 50"),
    ("schema-relationship-histogram",
     "MATCH (a)-[r]->(b) RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c ORDER BY c DESC LIMIT 40"),
]


def median(values):
    s = sorted(values)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2


def metric(samples):
    return {
        "score": round(median(samples), 3),
        "scoreUnit": UNIT,
        "scoreConfidence": [round(min(samples), 3), round(max(samples), 3)],
        "rawData": [[round(v, 3) for v in samples]],
    }


def measure(base, work, repetitions, timeout, call=fixture64.call, log=print):
    """Run the plan `repetitions` times; return JMH-shaped rows or raise RuntimeError."""
    samples = [[] for _ in work]
    digests = [None] * len(work)
    row_counts = [None] * len(work)
    per_pass = []
    for pass_index in range(repetitions):
        pass_ms = []
        for i, (params, shape, query) in enumerate(work):
            ms, status, payload = call(base, query, timeout)
            d, n = fixture64.digest(payload)
            if status != 200 or n is None:
                raise RuntimeError(f"{shape} {params}: HTTP {status} {d}")
            if digests[i] is None:
                digests[i], row_counts[i] = d, n
            elif digests[i] != d:
                raise RuntimeError(f"{shape} {params}: answer changed between passes")
            samples[i].append(ms)
            pass_ms.append(ms)
        per_pass.append(pass_ms)
        log(f"pass {pass_index + 1}/{repetitions}: p50={fixture64.percentile(pass_ms, 0.5):.1f}ms "
            f"p95={fixture64.percentile(pass_ms, 0.95):.1f}ms")
    rows = []
    for i, (params, shape, _) in enumerate(work):
        rows.append({
            "benchmark": BENCHMARK_PREFIX + shape,
            "mode": MODE,
            "params": params,
            "primaryMetric": metric(samples[i]),
            "rowCount": row_counts[i],
            "digest": digests[i][:16],
        })
    for statistic, fraction in (("p50", 0.5), ("p95", 0.95)):
        rows.append({
            "benchmark": BENCHMARK_PREFIX + "aggregate",
            "mode": MODE,
            "params": {"statistic": statistic},
            "primaryMetric": metric([fixture64.percentile(p, fraction) for p in per_pass]),
        })
    return rows


def graph_count(base):
    try:
        with urllib.request.urlopen(base + "/api/graphs", timeout=5) as r:
            d = json.load(r)
    except Exception:
        return None
    return d.get("count", len(d)) if isinstance(d, dict) else len(d)


def serve(binary, graphs, port, log_path, timeout_ms):
    args = [binary, "serve", "--port", str(port), "--cypher-max-timeout-ms", str(timeout_ms)]
    for g in graphs:
        args += ["--graph", f"{g['id']}:{g['path']}"]
    log = open(log_path, "w")
    return subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT,
                            stdin=subprocess.DEVNULL, start_new_session=True)


def stop(process):
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True, help="fixture64 graphs.tsv")
    ap.add_argument("--binary", help="graphite binary to serve the corpus with")
    ap.add_argument("--base", help="measure an already running server instead")
    ap.add_argument("--port", type=int, default=18080)
    ap.add_argument("--repetitions", type=int, default=3)
    ap.add_argument("--timeout", type=int, default=300, help="per-request timeout, seconds")
    ap.add_argument("--startup-timeout", type=int, default=600, help="seconds to wait for the graphs")
    ap.add_argument("--server-log", default="graphite-serve.log")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    if bool(args.binary) == bool(args.base):
        ap.error("exactly one of --binary and --base is required")

    graphs, distributions = fixture64.read_manifest(args.manifest)
    if not graphs:
        print("manifest carried no graphs", file=sys.stderr)
        return 2
    work = plan(graphs, distributions)
    print(f"{len(graphs)} graphs, {len(work)} queries, {args.repetitions} passes")

    process = None
    base = args.base
    try:
        if args.binary:
            base = f"http://localhost:{args.port}"
            if graph_count(base) is not None:
                print(f"port {args.port} is already served; refusing to measure it", file=sys.stderr)
                return 2
            process = serve(args.binary, graphs, args.port, args.server_log, args.timeout * 1000)
            deadline = time.monotonic() + args.startup_timeout
            while graph_count(base) != len(graphs):
                if process.poll() is not None:
                    print(f"server exited before serving; see {args.server_log}", file=sys.stderr)
                    return 2
                if time.monotonic() > deadline:
                    print(f"server did not open {len(graphs)} graphs in time", file=sys.stderr)
                    return 2
                time.sleep(1)
            print(f"server up with {len(graphs)} graphs")
        try:
            rows = measure(base, work, args.repetitions, args.timeout)
        except RuntimeError as e:
            print(f"snapshot failed: {e}", file=sys.stderr)
            return 2
    finally:
        if process is not None:
            stop(process)
    json.dump(rows, open(args.out, "w"), indent=2)
    print(f"wrote {len(rows)} rows to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
