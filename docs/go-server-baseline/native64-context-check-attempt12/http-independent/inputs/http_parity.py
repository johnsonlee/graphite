#!/usr/bin/env python3
"""Compare HTTP correctness against a pinned JVM baseline; never measure latency."""
import argparse
import copy
from decimal import Decimal
import hashlib
import json
import pathlib
import urllib.error
import urllib.request


def fetch(base, case, graph_id):
    path = case["path"].replace("{graphId}", graph_id)
    body = case.get("body")
    request = urllib.request.Request(
        base.rstrip("/") + path,
        data=None if body is None else json.dumps(body).encode(),
        method=case.get("method", "GET"),
        headers=case.get("headers", {"Accept": "application/json", "Content-Type": "application/json"}))
    try:
        response = urllib.request.urlopen(request, timeout=120)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode("utf-8")
        result = {"status": response.status, "headers": dict(response.headers), "body": raw}
        try:
            result["json"] = json.loads(raw)
        except json.JSONDecodeError:
            pass
        return result


def normalized(response, pointers):
    value = copy.deepcopy(response.get("json", response["body"]))
    for pointer in pointers:
        components = pointer.split("/")[1:]
        obj = value
        for component in components[:-1]:
            obj = obj[int(component)] if isinstance(obj, list) else obj[component]
        key = components[-1]
        if key not in obj:
            raise ValueError(f"Normalization pointer is missing: {pointer}")
        obj[key] = "<dynamic>"
    # JSON whitespace/key order is not semantic; list order and nulls are preserved.
    return {"status": response["status"], "value": value}


def signature(response, case):
    # Compare JSON numbers as exact decimal tokens. Parsing 9.223372036854776E18
    # as a binary float but 9223372036854776000 as an arbitrary-precision int
    # would otherwise report a false mismatch for numerically identical JSON.
    exact = dict(response)
    if "json" in response:
        exact["json"] = json.loads(response["body"], parse_float=Decimal, parse_int=Decimal)
    value = normalized(exact, case.get("dynamicPointers", []))
    def canonical(item):
        if isinstance(item, Decimal):
            sign, digits, exponent = item.as_tuple()
            digits = list(digits)
            while len(digits) > 1 and digits[-1] == 0:
                digits.pop()
                exponent += 1
            if not any(digits):
                return ("number", 0, [0], 0)
            return ("number", sign, digits, exponent)
        if isinstance(item, dict):
            return ("object", [(key, canonical(item[key])) for key in sorted(item)])
        if isinstance(item, list):
            return ("array", [canonical(child) for child in item])
        return (type(item).__name__, item)
    return json.dumps(canonical(value), separators=(",", ":"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-url", required=True)
    parser.add_argument("--candidate-url")
    parser.add_argument("--manifest", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--exclude-phase", action="append", default=[])
    parser.add_argument("--stop-on-mismatch", action="store_true", help="Stop a preflight after the first mismatch; remaining manifest cases stay explicitly unverified")
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    args.output.mkdir(parents=True, exist_ok=True)
    observations = []
    mismatches = []
    header_differences = []
    for case in manifest["cases"]:
        if case.get("phase") in args.exclude_phase:
            continue
        baseline = fetch(args.baseline_url, case, manifest["graphId"])
        record = {"case": case, "baseline": baseline}
        if args.candidate_url:
            candidate = fetch(args.candidate_url, case, manifest["graphId"])
            record["candidate"] = candidate
            expected = normalized(baseline, case.get("dynamicPointers", []))
            actual = normalized(candidate, case.get("dynamicPointers", []))
            record["equal"] = signature(baseline, case) == signature(candidate, case)
            if not record["equal"]:
                mismatches.append({"name": case["name"], "baseline": expected, "candidate": actual})
            for name in ("Content-Type", "Retry-After"):
                if baseline["headers"].get(name) != candidate["headers"].get(name):
                    header_differences.append({"case": case["name"], "header": name,
                                              "baseline": baseline["headers"].get(name),
                                              "candidate": candidate["headers"].get(name)})
        observations.append(record)
        # Preserve completed responses even if a later request blocks or the run is interrupted.
        (args.output / "observations.partial.json").write_text(json.dumps(observations, indent=2) + "\n")
        print(case["name"], baseline["status"], record.get("equal", "baseline-only"), flush=True)
        if args.stop_on_mismatch and record.get("equal") is False:
            break
    args.output.mkdir(parents=True, exist_ok=True)
    result = {"purpose": "Correctness-only HTTP differential; no performance evidence",
              "manifest": str(args.manifest), "manifestSha256": hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
              "baselineUrl": args.baseline_url, "candidateUrl": args.candidate_url,
              "cases": len(observations), "manifestCases": len(manifest["cases"]),
              "completeManifestCompared": len(observations) == len(manifest["cases"]) and bool(args.candidate_url),
              "mismatches": mismatches, "headerDifferences": header_differences}
    (args.output / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    (args.output / "observations.json").write_text(json.dumps(observations, indent=2) + "\n")
    print(f"Cases: {len(observations)}, body/status differences: {len(mismatches)}, header differences: {len(header_differences)}")
    return 1 if mismatches or header_differences else 0


if __name__ == "__main__":
    raise SystemExit(main())
