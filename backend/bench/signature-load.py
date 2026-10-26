#!/usr/bin/env python3
"""Sustained root-Cypher requests against one externally managed Rust server.

Each worker repeats its seeded shuffled case schedule without case/round barriers.
Only request through full HTTP-body consumption is timed. Raw bodies stay in memory
until the phase ends; JSON parsing, correctness hashes and disk writes follow it.
CPU and peak RSS must be captured externally for the server process. Current Rust
responses contain no volatile timing fields, so the full parsed response, including
row order, node properties, $metadata, graphCount and total, is compared unchanged.
"""
import argparse
import concurrent.futures
import hashlib
import json
import math
import pathlib
import random
import threading
import time
import urllib.error
import urllib.request


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def response_identity(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate JSON key: " + key)
            result[key] = value
        return result

    def invalid(value):
        raise ValueError("nonfinite JSON value: " + value)

    body = json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid)
    if not isinstance(body, dict) or not isinstance(body.get("columns"), list):
        raise ValueError("response must contain columns")
    if any(not isinstance(c, str) for c in body["columns"]):
        raise ValueError("column names must be strings")
    rows = body.get("rows")
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise ValueError("response must contain object rows")
    if type(body.get("rowCount")) is not int or body["rowCount"] != len(rows):
        raise ValueError("rowCount must match fully consumed rows")
    return hashlib.sha256(canonical(body)).hexdigest(), len(rows)


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def run_phase(url, cases, concurrency, rounds, seed, timeout):
    """All workers start together; no synchronization occurs between requests."""
    prepared = [json.dumps({"query": c["query"]}).encode() for c in cases]
    start = threading.Barrier(concurrency + 1)

    def worker(worker_id):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        rng = random.Random(seed + worker_id)
        schedule = []
        for round_index in range(rounds):
            order = list(range(len(cases)))
            rng.shuffle(order)
            schedule.extend((round_index, case_index) for case_index in order)
        records = []
        start.wait()
        for sequence, (round_index, case_index) in enumerate(schedule):
            request = urllib.request.Request(
                url.rstrip("/") + "/api/cypher", data=prepared[case_index],
                headers={"Content-Type": "application/json"}, method="POST")
            body, status, error, complete = b"", None, None, False
            began = time.perf_counter_ns()
            try:
                try:
                    response = opener.open(request, timeout=timeout)
                except urllib.error.HTTPError as exc:
                    response = exc
                with response:
                    status = response.status
                    body = response.read()
                    complete = True
            except Exception as exc:
                partial = getattr(exc, "partial", None)
                if isinstance(partial, bytes):
                    body = partial
                error = repr(exc)
            ended = time.perf_counter_ns()
            records.append(({
                "worker": worker_id, "sequence": sequence, "round": round_index,
                "case": cases[case_index]["name"], "startNs": began, "endNs": ended,
                "elapsedMs": (ended - began) / 1e6, "httpStatus": status,
                "completeBody": complete, "transportError": error,
            }, body))
        return records

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(worker, worker_id) for worker_id in range(concurrency)]
        began = time.perf_counter_ns()
        start.wait()
        records = [record for future in futures for record in future.result()]
        ended = time.perf_counter_ns()
    return records, (ended - began) / 1e9


def retain_phase(directory, name, records):
    """Validation and disk I/O happen after every request in this phase ended."""
    body_dir = directory / (name + "-bodies")
    body_dir.mkdir()
    samples = []
    for record, raw in sorted(records, key=lambda pair: pair[0]["startNs"]):
        sample = dict(record)
        body_name = f"w{sample['worker']:03d}-s{sample['sequence']:06d}.body"
        (body_dir / body_name).write_bytes(raw)
        sample.update(bodyFile=f"{name}-bodies/{body_name}", bodyBytes=len(raw),
                      bodySha256=hashlib.sha256(raw).hexdigest(), valid=False)
        try:
            if not sample["completeBody"] or sample["httpStatus"] != 200:
                raise ValueError("HTTP request did not complete successfully")
            digest, count = response_identity(raw)
            sample.update(responseDigest=digest, rowCount=count, valid=True)
        except Exception as exc:
            sample["validationError"] = repr(exc)
        samples.append(sample)
    with (directory / (name + ".jsonl")).open("w") as stream:
        for sample in samples:
            stream.write(json.dumps(sample, allow_nan=False) + "\n")
    return samples


def summarize(cases, samples, warmup, reference=None):
    results, failures = [], []
    reference_cases = {c["name"]: c for c in reference["cases"]} if reference else {}
    for case in cases:
        measured = [s for s in samples if s["case"] == case["name"]]
        initial = [s for s in warmup if s["case"] == case["name"]]
        all_samples = initial + measured
        valid = [s for s in all_samples if s["valid"]]
        identities = sorted({s["responseDigest"] for s in valid})
        for sample in all_samples:
            if not sample["valid"]:
                failures.append({"case": case["name"], "bodyFile": sample["bodyFile"],
                                 "reason": sample.get("validationError", "invalid response")})
        if len(identities) != 1:
            failures.append({"case": case["name"], "reason": "response identity changed or absent",
                             "digests": identities})
        if reference is not None:
            expected = reference_cases.get(case["name"])
            if expected is None or expected.get("responseDigests") != identities:
                failures.append({"case": case["name"], "reason": "reference response mismatch",
                                 "expected": expected, "digests": identities})
        values = [s["elapsedMs"] for s in measured]
        results.append({"name": case["name"], "samples": len(values),
                        "p50Ms": percentile(values, .50), "p95Ms": percentile(values, .95),
                        "minMs": min(values) if values else None,
                        "maxMs": max(values) if values else None,
                        "latenciesMs": values, "responseDigests": identities,
                        "rowCounts": sorted({s["rowCount"] for s in valid})})
    return results, failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--out", required=True, type=pathlib.Path)
    parser.add_argument("--queries", required=True, type=pathlib.Path)
    parser.add_argument("--concurrency", required=True, type=int)
    parser.add_argument("--rounds", required=True, type=int)
    parser.add_argument("--warmup-rounds", type=int, default=2)
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--seed", type=int, default=20261026)
    parser.add_argument("--reference", type=pathlib.Path)
    args = parser.parse_args()
    if min(args.concurrency, args.rounds) < 1 or args.warmup_rounds < 0 or args.timeout <= 0:
        parser.error("positive concurrency/rounds/timeout and nonnegative warmup required")
    cases = json.loads(args.queries.read_text())
    if (not isinstance(cases, list) or not cases or any(
            not isinstance(c, dict) or set(c) != {"name", "query"}
            or not isinstance(c["name"], str) or not c["name"]
            or not isinstance(c["query"], str) or not c["query"] for c in cases)
            or len({c["name"] for c in cases}) != len(cases)):
        parser.error("queries must be a nonempty list of unique {name,query} objects")
    config = {"queryCatalogSha256": hashlib.sha256(canonical(cases)).hexdigest(),
              "concurrency": args.concurrency, "roundsPerWorker": args.rounds,
              "warmupRoundsPerWorker": args.warmup_rounds, "seed": args.seed,
              "timeoutSeconds": args.timeout}
    reference = None
    if args.reference:
        reference = json.loads((args.reference / "summary.json").read_text())
        if reference.get("config") != config or reference.get("valid") is not True:
            parser.error("reference must be valid and have the identical measurement config/catalog")
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / "queries.json").write_text(json.dumps(cases, indent=2) + "\n")
    metadata = {"config": config, "url": args.url, "requestScope": "global /api/cypher",
                "model": "sustained closed-loop independent seeded workers; no case/round barriers",
                "boundary": "request through full HTTP-body consumption; JSON validation after phase",
                "transport": "urllib HTTP requests, no explicit persistent connection reuse",
                "resourceEvidence": "external server process CPU/peak RSS capture required",
                "percentile": "nearest rank within each case across repeated measured requests, including failures",
                "bodyRetention": "raw response bodies retained in client memory until phase ends",
                "coldState": "warmup declared separately; OS page cache uncontrolled",
                "excludedResponseFields": []}
    (args.out / "protocol.json").write_text(json.dumps(metadata, indent=2) + "\n")
    raw, warm_seconds = run_phase(args.url, cases, args.concurrency,
                                  args.warmup_rounds, args.seed, args.timeout)
    warmup = retain_phase(args.out, "warmup", raw)
    del raw
    raw, measured_seconds = run_phase(args.url, cases, args.concurrency,
                                      args.rounds, args.seed + 1000000, args.timeout)
    samples = retain_phase(args.out, "samples", raw)
    del raw
    results, failures = summarize(cases, samples, warmup, reference)
    summary = {**metadata, "warmupSeconds": warm_seconds, "measuredSeconds": measured_seconds,
               "attempts": len(samples), "expectedAttempts": len(cases) * args.rounds * args.concurrency,
               "cases": results, "failures": failures, "valid": not failures,
               "reference": str(args.reference) if args.reference else None,
               "correctnessScope": "full response repeatability and optional paired revision agreement; not an independent oracle"}
    (args.out / "summary.json").write_text(json.dumps(summary, indent=2, allow_nan=False) + "\n")
    print(json.dumps({"valid": summary["valid"], "attempts": len(samples),
                      "measuredSeconds": measured_seconds, "failures": len(failures)}))
    return 0 if summary["valid"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
