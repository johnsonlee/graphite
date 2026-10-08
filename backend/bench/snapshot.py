#!/usr/bin/env python3
"""Absolute latency snapshot of the Rust engine over the fixture64 corpus.

`graphite serve` opens the real multi-graph fixture, and all requests use `/api/cypher`.
The fixture64 suite retains the historical fast and schema cases; fast34 selects only
the original fast cases. broad adds schema queries to shapes.py's 36 ordering,
aggregation, traversal and expression cases; all combines both catalogs (73 cases
with the standard four distribution entries). Each runs sequentially, --repetitions
times, and emits the JMH result shape understood by the benchmark observatory:

    benchmark      rust.fixture64.<shape>          (or rust.fixture64.aggregate)
    params         {"selectivity": ...} / {"case": ...} / {"statistic": "p50"|"p95"}
    mode           sequential-pass
    primaryMetric  score = median over repetitions, scoreUnit = ms/op,
                   scoreConfidence = [min, max] over repetitions, rawData = every sample

Aggregate percentiles describe the distribution across different cases in one pass,
not repeated-request p50/p95. Sequential passes also mix first-use and warm state;
their median and min/max are diagnostics, not a server latency acceptance distribution.
The shared shapes.py catalog uses WITH collect(...) before slicing to preserve the
full aggregation while avoiding the unsupported aggregate-inside-slice expression.

Any response that is not HTTP 200, or any row whose digest changes between passes, fails
the snapshot (exit 2): a page that silently plots errors or unstable answers is worse than
no page.
Failed attempts are retained, including complete HTTP error bodies. If transport fails
inside fixture64.call before it returns, partial bytes are unavailable; the attempt is
marked as lacking a body, never presented as a completely consumed response.
"""
import argparse, hashlib, json, os, pathlib, signal, subprocess, sys, time, urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import fixture64  # noqa: E402
import shapes  # noqa: E402

BENCHMARK_PREFIX = "rust.fixture64."
MODE = "sequential-pass"
UNIT = "ms/op"
MULTIGRAPH_QUERY_SUITE_V2 = "MULTIGRAPH_QUERY_SUITE_V2"
SUITES = ("fixture64", "fast34", "broad", "all")


def plan(graphs, distributions, suite="fixture64"):
    """The fixture64 plan: (params, shape, query) triples in the harness's own order."""
    if suite not in SUITES:
        raise ValueError(f"Unknown suite: {suite}")
    request_id = distributions[0]["requestGraph"] if distributions else graphs[0]["id"]
    request = next((g for g in graphs if g["id"] == request_id), graphs[0])
    rows = []
    for level in ("zero", "targeted", "dense"):
        for name, build in fixture64.SHAPES:
            rows.append(({"selectivity": level}, name, build(request[level])))
    for d in distributions:
        rows.append(({"case": d["case"]}, "global-wide-four-properties",
                     fixture64.SHAPES[0][1](d["term"])))
    if suite == "broad":
        rows = []
    if suite != "fast34":
        for name, query in SCHEMA_SHAPES:
            rows.append(({"selectivity": "schema"}, name, query))
    if suite in ("broad", "all"):
        rows.extend(({"selectivity": "broad"}, "shape-" + name, query)
                    for name, query in shapes.SHAPES)
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


def canonical_json(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def response_digest(payload):
    """Hash the complete typed JSON body, retaining order, metadata and every field."""
    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON key: {key}")
            result[key] = value
        return result

    def invalid_constant(value):
        raise ValueError(f"non-finite JSON value: {value}")

    body = json.loads(payload, object_pairs_hook=object_pairs, parse_constant=invalid_constant)
    if not isinstance(body, dict) or not isinstance(body.get("columns"), list):
        raise ValueError("response must contain columns")
    rows = body.get("rows")
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise ValueError("response must contain object rows")
    if any(not isinstance(column, str) for column in body["columns"]):
        raise ValueError("column names must be strings")
    if "rowCount" in body and (type(body["rowCount"]) is not int or body["rowCount"] != len(rows)):
        raise ValueError("rowCount does not match complete rows")
    return hashlib.sha256(canonical_json(body)).hexdigest(), len(rows)


def case_catalog(work):
    return [{"benchmark": BENCHMARK_PREFIX + shape, "params": params,
             "querySha256": hashlib.sha256(query.encode("utf-8")).hexdigest()}
            for params, shape, query in work]


def measure(base, work, repetitions, timeout, call=fixture64.call, log=print, *,
            suite="fixture64", responses_dir=None, overall_timeout=None,
            clock=time.monotonic, input_graph_count=None):
    """Complete-body sequential diagnostics; fail closed and retain every attempted body.

    The first successful body is a repeatability reference, not a correctness oracle.
    Paired revision comparison must require equal full responseDigest for every case.
    """
    if not work or repetitions < 1 or timeout <= 0 or (overall_timeout is not None and overall_timeout <= 0):
        raise ValueError("nonempty plan and positive repetitions/timeouts required")
    catalog = case_catalog(work)
    identity = hashlib.sha256(canonical_json(catalog)).hexdigest()
    common = {"protocol": MULTIGRAPH_QUERY_SUITE_V2, "suite": suite,
              "caseCount": len(work), "caseListSha256": identity,
              "inputGraphCount": input_graph_count, "requestScope": "global-cross-graph",
              "sampling": {"repetitions": repetitions, "firstPass": "first-use",
                           "laterPasses": "warm", "requestPercentileEstimate": False}}
    samples = [[] for _ in work]
    digests = [None] * len(work)
    legacy_digests = [None] * len(work)
    row_counts = [None] * len(work)
    per_pass = []
    directory = pathlib.Path(responses_dir) if responses_dir is not None else None
    manifest = {**common, "status": "RUNNING", "cases": catalog,
                "repetitions": repetitions, "perRequestTimeoutSeconds": timeout,
                "overallTimeoutSeconds": overall_timeout, "attempted": 0,
                "expectedAttempts": len(work) * repetitions,
                "statisticScope": "sequential-pass-diagnostic"}
    if directory is not None:
        directory.mkdir(parents=True, exist_ok=False)

    def checkpoint():
        if directory is not None:
            (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    def retain(record, payload):
        manifest["attempted"] += 1
        record["bodyCompleteness"] = "complete-read" if payload is not None else "unavailable"
        if directory is not None:
            if payload is not None:
                filename = f"pass-{record['passIndex']:03d}-case-{record['caseIndex']:03d}.body"
                (directory / filename).write_bytes(payload)
                record.update(bodyFile=filename, bodyBytes=len(payload),
                              bodySha256=hashlib.sha256(payload).hexdigest())
            with (directory / "attempts.jsonl").open("a") as stream:
                stream.write(json.dumps(record) + "\n")
            checkpoint()

    deadline = clock() + overall_timeout if overall_timeout is not None else None
    checkpoint()
    try:
        for pass_index in range(repetitions):
            pass_ms = []
            for i, (params, shape, query) in enumerate(work):
                remaining = deadline - clock() if deadline is not None else timeout
                if remaining <= 0:
                    raise RuntimeError("overall measurement budget exhausted; remaining cases unmeasured")
                effective_timeout = min(timeout, remaining)
                record = {**catalog[i], "passIndex": pass_index, "caseIndex": i,
                          "query": query, "timeoutSeconds": effective_timeout}
                payload = None
                try:
                    ms, status, payload = call(base, query, effective_timeout)
                    record.update(elapsedMs=ms, httpStatus=status)
                    if status != 200:
                        raise RuntimeError(f"HTTP {status}")
                    d, n = response_digest(payload)
                    record.update(responseDigest=d, rowCount=n)
                    if deadline is not None and clock() > deadline:
                        raise RuntimeError("overall measurement budget exhausted after complete response")
                    if digests[i] is None:
                        digests[i], row_counts[i] = d, n
                        legacy_digests[i] = fixture64.digest(payload)[0]
                    elif digests[i] != d:
                        raise RuntimeError("answer changed between passes")
                    samples[i].append(ms)
                    pass_ms.append(ms)
                    record["status"] = "PASS"
                except Exception as error:
                    record.update(status="FAIL", error=str(error))
                    retain(record, payload)
                    raise RuntimeError(f"{shape} {params}: {error}") from error
                retain(record, payload)
            per_pass.append(pass_ms)
            log(f"pass {pass_index + 1}/{repetitions}: cross-case p50="
                f"{fixture64.percentile(pass_ms, 0.5):.1f}ms "
                f"cross-case p95={fixture64.percentile(pass_ms, 0.95):.1f}ms (not request percentiles)")
        rows = []
        for i, (params, shape, _) in enumerate(work):
            rows.append({
                **common, **catalog[i], "mode": MODE,
                "primaryMetric": metric(samples[i]), "rowCount": row_counts[i],
                "digest": legacy_digests[i][:16], "responseDigest": digests[i],
                "statisticScope": "same-case-sequential-pass-median",
            })
        for statistic, fraction in (("p50", 0.5), ("p95", 0.95)):
            rows.append({
                **common, "benchmark": BENCHMARK_PREFIX + "aggregate", "mode": MODE,
                "params": {"statistic": statistic}, "statisticScope": "cross-case-per-pass",
                "primaryMetric": metric([fixture64.percentile(p, fraction) for p in per_pass]),
            })
        manifest["status"] = "PASS"
        return rows
    except BaseException as error:
        manifest.update(status="FAIL", error=str(error))
        raise
    finally:
        checkpoint()


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
    ap.add_argument("--suite", choices=SUITES, default="fixture64",
                    help="fixture64: historical 37; fast34: historical fast cases; broad: 39; all: 73 (four distribution cases)")
    ap.add_argument("--port", type=int, default=18080)
    ap.add_argument("--repetitions", type=int,
                    help="sequential passes; default 3 for historical suites, 1 diagnostic pass for broad/all")
    ap.add_argument("--timeout", type=int,
                    help="per-request seconds; default 300 historical, 60 broad/all")
    ap.add_argument("--overall-timeout", type=int, default=600,
                    help="hard wall-clock budget for query measurement, seconds; expiry fails, never drops cases")
    ap.add_argument("--startup-timeout", type=int, default=600, help="seconds to wait for the graphs")
    ap.add_argument("--server-log", default="graphite-serve.log")
    ap.add_argument("--responses-dir", help="new directory retaining every full body and failed attempt; default OUT.responses")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    if bool(args.binary) == bool(args.base):
        ap.error("exactly one of --binary and --base is required")
    broad = args.suite in ("broad", "all")
    args.repetitions = args.repetitions if args.repetitions is not None else (1 if broad else 3)
    args.timeout = args.timeout if args.timeout is not None else (60 if broad else 300)
    if min(args.repetitions, args.timeout, args.overall_timeout, args.startup_timeout) <= 0:
        ap.error("repetitions and all timeouts must be positive")

    graphs, distributions = fixture64.read_manifest(args.manifest)
    if len(graphs) < 2 or len({g["id"] for g in graphs}) != len(graphs):
        print("multi-graph benchmark requires at least two distinct graph ids", file=sys.stderr)
        return 2
    work = plan(graphs, distributions, args.suite)
    print(f"{len(graphs)} graphs, suite={args.suite}, {len(work)} queries, {args.repetitions} passes; "
          f"{args.overall_timeout}s overall budget (sequential diagnostic, not request p95)")

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
        elif graph_count(base) != len(graphs):
            print("server graph count differs from multi-graph manifest", file=sys.stderr)
            return 2
        try:
            # Socket timeouts alone do not bound a trickling body or all cases together.
            # This command runs in the Unix main thread; the alarm interrupts even read().
            def budget_expired(_signal, _frame):
                raise RuntimeError("overall measurement budget exhausted")

            previous_handler = signal.signal(signal.SIGALRM, budget_expired)
            signal.setitimer(signal.ITIMER_REAL, args.overall_timeout)
            try:
                rows = measure(base, work, args.repetitions, args.timeout, suite=args.suite,
                               responses_dir=args.responses_dir or args.out + ".responses",
                               overall_timeout=args.overall_timeout, input_graph_count=len(graphs))
            finally:
                signal.setitimer(signal.ITIMER_REAL, 0)
                signal.signal(signal.SIGALRM, previous_handler)
        except RuntimeError as e:
            print(f"snapshot failed: {e}", file=sys.stderr)
            return 2
    finally:
        if process is not None:
            stop(process)
    for row in rows:
        row["graphCount"] = len(graphs)
    with open(args.out, "w") as stream:
        json.dump(rows, stream, indent=2)
    print(f"wrote {len(rows)} rows to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
