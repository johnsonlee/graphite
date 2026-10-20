#!/usr/bin/env python3
"""A/B the Kotlin query server over the fixture64 corpus: one `graphite.jar` at a time, all 64 graphs at -Xmx8g.

Each labelled jar is served alone (`io.johnsonlee.graphite.cli.MainKt serve`, MAPPED load mode, all 64
graphs registered, Cypher concurrency 4), every case of `jvm_pressure_cases.json` is sent through
`/api/cypher/graphs` in cross-graph mode exactly as the former fixture64 harness sent it, and the wall
time of each complete response is recorded: the first request of a case is the cold row, the following
`--repetitions` requests are the warm rows (nearest-rank p50 over them, and the fastest). Requests run
one at a time, so a row is one request's own latency, not a quantile under contention; the servers never
run together. Row counts and a SHA-256 of each response body are kept so two jars can be checked for
identical answers before their timings are compared.

    python3 backend/bench/jvm_query_ab.py --graphs /path/to/fixture64 \\
        --jar base=/path/to/base/graphite.jar --jar head=/path/to/head/graphite.jar \\
        --repetitions 3 --out results.json

Build the corpus once with the repository's own preparation (the four pinned fixture JARs resolve from
the Gradle cache after `./gradlew :webgraph:prepareBenchmarkFixtures`):

    ./gradlew :webgraph:prepareBenchmarkFixtures :webgraph:jmhJar
    java -Xmx4g -cp frontend/jvm/webgraph/build/libs/webgraph-*-jmh.jar \\
        io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation /path/to/fixture64

and a server jar per revision with `./gradlew :query:shadowJar` (frontend/jvm/query/build/libs/graphite.jar).
The markdown table at the end compares every jar to the first one given; `--dump-answers DIR` keeps each
case's cold response so `jvm_query_ab_answers.py DIR` can prove that the cases whose bodies differ differ
only by the added declared-type properties.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent


def nearest_rank(values, quantile):
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(round(quantile * len(ordered) + 0.5)) - 1))
    return ordered[index]


def wait_ready(port, proc, timeout_s):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if proc.poll() is not None:
            raise SystemExit(f"server exited with {proc.returncode} before readiness")
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/graphs", timeout=5) as response:
                body = json.load(response)
                if response.status == 200 and body.get("count", 0) >= 64:
                    return body
        except (urllib.error.URLError, OSError, ValueError):
            pass
        time.sleep(1)
    raise SystemExit("server did not become ready in time")


def request(port, endpoint, body, timeout_s):
    data = json.dumps(body).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}{endpoint}", data=data, headers={"content-type": "application/json"})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as response:
            payload = response.read()
            status = response.status
    except urllib.error.HTTPError as error:
        payload = error.read()
        status = error.code
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    rows = None
    if status == 200:
        try:
            rows = len(json.loads(payload).get("rows", []))
        except ValueError:
            rows = -1
    return status, elapsed_ms, rows, hashlib.sha256(payload).hexdigest(), payload


def measure(label, jar, graphs, catalog, args):
    graph_dirs = [graphs / graph_id for graph_id in catalog["graphIds"]]
    missing = [path.name for path in graph_dirs if not (path / "graph.metadata").is_file()]
    if missing:
        raise SystemExit(f"{graphs} lacks graphs: {missing[:5]}")
    argv = ["java", f"-Xmx{args.heap}", f"-XX:ActiveProcessorCount={args.cpus}", *args.jvm_arg, "-cp", str(jar),
            "io.johnsonlee.graphite.cli.MainKt", "serve", "--data", str(graphs), "--port", str(args.port),
            "--max-concurrent-cypher", "4", "--cypher-max-timeout-ms", str(args.timeout_ms)]
    for path in graph_dirs:
        argv += ["--graph", f"{path.name}:{path}"]
    log = open(args.out.with_name(f"{args.out.stem}-{label}-serve.log"), "w")
    started = time.time()
    proc = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT)
    result = {"label": label, "jar": str(jar), "cases": {}}
    try:
        totals = wait_ready(args.port, proc, args.ready_timeout_s)
        result["readyMs"] = (time.time() - started) * 1000.0
        result["totals"] = totals.get("totals")
        print(f"[{label}] ready in {result['readyMs']:.0f} ms, {totals.get('count')} graphs", flush=True)
        selected = [case for case in catalog["cases"] if not args.case or case["id"] in args.case]
        for case in selected:
            body = {"graphs": catalog["graphIds"], "query": case["query"], "mode": case["mode"],
                    "limit": case["limit"], "timeoutMs": args.timeout_ms}
            samples = []
            for repetition in range(1 + args.repetitions):
                status, ms, rows, digest, payload = request(args.port, catalog["endpoint"], body, args.timeout_ms / 1000.0 + 30)
                samples.append({"status": status, "ms": ms, "rows": rows, "sha256": digest})
                if repetition == 0 and args.dump_answers:
                    (args.dump_answers / f"{label}-{case['id']}.json").write_bytes(payload)
                print(f"[{label}] {case['id']} {'cold' if repetition == 0 else 'warm'} {ms:.0f} ms status={status} rows={rows}", flush=True)
            warm = [s["ms"] for s in samples[1:] if s["status"] == 200]
            result["cases"][case["id"]] = {
                "family": case["family"], "samples": samples, "coldMs": samples[0]["ms"] if samples[0]["status"] == 200 else None,
                "warmP50Ms": nearest_rank(warm, 0.5) if warm else None, "warmMinMs": min(warm) if warm else None,
                "rows": samples[0]["rows"], "sha256": samples[0]["sha256"],
            }
    finally:
        proc.terminate()
        try:
            proc.wait(30)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.close()
    return result


def render(results):
    base = results[0]
    lines = [f"| Case | Family | Rows | {base['label']} cold ms | " + " | ".join(f"{r['label']} cold ms" for r in results[1:]) +
             f" | {base['label']} warm p50 ms | " + " | ".join(f"{r['label']} warm p50 ms (Δ)" for r in results[1:]) + " | Same answer |",
             "|---|---|---:|---:|" + "---:|" * (len(results) - 1) + "---:|" + "---:|" * (len(results) - 1) + ":---:|"]
    for case_id, b in base["cases"].items():
        cells = [case_id, b["family"], str(b["rows"]), fmt(b["coldMs"])]
        cells += [fmt(r["cases"].get(case_id, {}).get("coldMs")) for r in results[1:]]
        cells.append(fmt(b["warmP50Ms"]))
        for r in results[1:]:
            other = r["cases"].get(case_id, {})
            delta = ""
            if other.get("warmP50Ms") is not None and b["warmP50Ms"]:
                delta = f" ({(other['warmP50Ms'] - b['warmP50Ms']) / b['warmP50Ms'] * 100:+.0f}%)"
            cells.append(fmt(other.get("warmP50Ms")) + delta)
        same = all(r["cases"].get(case_id, {}).get("sha256") == b["sha256"] for r in results[1:])
        cells.append("yes" if same else "NO")
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines)


def fmt(value):
    return "n/a" if value is None else f"{value:.0f}"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--graphs", type=pathlib.Path, required=True, help="fixture64 directory holding the 64 fixture-* graphs")
    parser.add_argument("--jar", action="append", required=True, metavar="LABEL=PATH", help="a graphite.jar to measure; the first is the reference")
    parser.add_argument("--cases", type=pathlib.Path, default=HERE / "jvm_pressure_cases.json")
    parser.add_argument("--case", action="append", default=[], help="restrict to these case ids (repeatable)")
    parser.add_argument("--repetitions", type=int, default=3, help="warm requests per case after the cold one")
    parser.add_argument("--heap", default="8g")
    parser.add_argument("--cpus", type=int, default=4, help="-XX:ActiveProcessorCount for the server")
    parser.add_argument("--jvm-arg", action="append", default=[], help="extra JVM argument for the server (repeatable)")
    parser.add_argument("--port", type=int, default=18190)
    parser.add_argument("--timeout-ms", type=int, default=240000)
    parser.add_argument("--ready-timeout-s", type=int, default=600)
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("jvm-query-ab.json"))
    parser.add_argument("--dump-answers", type=pathlib.Path, metavar="DIR",
                        help="also keep each case's cold response body as DIR/<label>-<case>.json (jvm_query_ab_answers.py reads them)")
    args = parser.parse_args()
    if args.dump_answers:
        args.dump_answers.mkdir(parents=True, exist_ok=True)
    catalog = json.load(open(args.cases))
    results = []
    for spec in args.jar:
        label, _, path = spec.partition("=")
        if not path:
            parser.error(f"--jar expects LABEL=PATH, got {spec!r}")
        results.append(measure(label, pathlib.Path(path).resolve(), args.graphs.resolve(), catalog, args))
        json.dump({"schema": "graphite-jvm-query-ab-v1", "graphs": str(args.graphs.resolve()), "repetitions": args.repetitions,
                   "heap": args.heap, "results": results}, open(args.out, "w"), indent=1)
    table = render(results)
    print(table)
    args.out.with_suffix(".md").write_text(table + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
