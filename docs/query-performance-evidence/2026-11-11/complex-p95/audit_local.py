#!/usr/bin/env python3
"""Offline verification only. Never starts servers, hashes graph payloads, or runs a benchmark."""
import argparse
import copy
import hashlib
import json
import math
from collections import Counter, defaultdict
from decimal import Decimal
from pathlib import Path


def reject_constant(value):
    raise ValueError(f"nonfinite JSON constant: {value}")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def parse(data):
    return json.loads(data, parse_float=Decimal, parse_constant=reject_constant,
                      object_pairs_hook=unique_object)


def typed(value):
    # Unlike Python ==, this preserves true vs 1, integer vs float, absent vs null,
    # ordered arrays, duplicates, and provenance. Object key order is irrelevant.
    if value is None:
        return ["null"]
    if isinstance(value, bool):
        return ["bool", value]
    if isinstance(value, int):
        return ["integer", str(value)]
    if isinstance(value, Decimal):
        return ["float", str(value.normalize())]
    if isinstance(value, str):
        return ["string", value]
    if isinstance(value, list):
        return ["array", [typed(item) for item in value]]
    if isinstance(value, dict):
        return ["object", [[key, typed(value[key])] for key in sorted(value)]]
    raise TypeError(type(value))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def typed_digest(value):
    return digest(json.dumps(typed(value), ensure_ascii=False, separators=(",", ":")).encode())


def native(value):
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, list):
        return [native(v) for v in value]
    if isinstance(value, dict):
        return {k: native(v) for k, v in value.items()}
    return value


def read(path):
    return parse(path.read_bytes())


def quantile(values, fraction):
    return sorted(values)[math.ceil(len(values) * fraction) - 1]


def cpu(snapshot):
    return snapshot["userSeconds"] + snapshot["systemSeconds"]


def close(actual, expected):
    return abs(float(actual) - float(expected)) <= 1e-7 * max(1, abs(float(expected)))


def correct_darwin_resources(resource, proof, environment):
    """Derive corrected evidence; retain original fields and never rewrite input reports."""
    original = copy.deepcopy(resource)
    annotations = []
    snapshots = [("queryReady", resource.get("queryReady")),
                 ("afterFirstUse", resource.get("afterFirstUse")),
                 ("queryEnd", resource.get("queryEnd"))]
    if resource.get("loading"):
        snapshots.append(("loading.ready", resource["loading"].get("ready")))
    if resource.get("pressure"):
        snapshots.extend((f"pressure.{key}", resource["pressure"][key]) for key in ("before", "after"))
    verified = True
    for path, snapshot in snapshots:
        if snapshot is None or snapshot.get("source") != "proc_pid_rusage v2":
            continue
        has_ticks = "rawUserTicks" in snapshot and "timebaseNumer" in snapshot and "timebaseDenom" in snapshot
        if not has_ticks and proof is None:
            verified = False
            annotations.append({"path": path, "status": "UNAVAILABLE", "reason": "legacy CPU divided Mach ticks by 1e9 without timebase; explicit same-host correction proof required"})
            continue
        if has_ticks:
            numer, denom = snapshot["timebaseNumer"], snapshot["timebaseDenom"]
            assert numer > 0 and denom > 0
        else:
            assert proof["bracketPassed"] is True and environment["os"] == "macos"
            assert environment["arch"] == "aarch64" and proof["machine"] == "arm64"
            numer, denom = proof["timebaseNumer"], proof["timebaseDenom"]
        record = {"path": path, "status": "RECORDED_RAW_TICKS" if has_ticks else "CORRECTED_LEGACY_DERIVATION",
                  "timebaseNumer": numer, "timebaseDenom": denom}
        for field, raw in (("userSeconds", "rawUserTicks"), ("systemSeconds", "rawSystemTicks")):
            if has_ticks:
                ticks = snapshot[raw]
                computed = Decimal(ticks) * Decimal(numer) / Decimal(denom) / Decimal(10**9)
                assert close(snapshot[field], computed), f"recorded timebase conversion mismatch: {path}.{field}"
            else:
                scaled = Decimal(snapshot[field]) * Decimal(10**9)
                ticks = int(scaled.to_integral_value())
                assert abs(scaled - ticks) < Decimal("0.01"), f"cannot reconstruct integer ticks: {path}.{field}"
                computed = Decimal(ticks) * Decimal(numer) / Decimal(denom) / Decimal(10**9)
                record[f"original_{field}"] = snapshot[field]
                record[f"reconstructionResidualTicks_{field}"] = scaled - ticks
                snapshot[field] = computed
                snapshot[raw] = ticks
            record[raw] = ticks
            record[field] = computed
        snapshot["timebaseNumer"], snapshot["timebaseDenom"] = numer, denom
        snapshot["cpuTimeUnit"] = "Mach absolute-time ticks"
        annotations.append(record)
    if verified and resource.get("pressure"):
        resource["pressure"]["cpuSeconds"] = cpu(resource["pressure"]["after"]) - cpu(resource["pressure"]["before"])
    corroboration = []
    for name, snapshot, usage in (("queryLifecycle", resource.get("queryEnd"), resource.get("queryLifecycle")),
                                 ("loading", (resource.get("loading") or {}).get("ready"),
                                  (resource.get("loading") or {}).get("lifetime"))):
        if verified and snapshot and usage:
            gap = cpu(usage) - cpu(snapshot)
            tolerance = max(Decimal("0.25"), cpu(usage) * Decimal("0.005"))
            passed = Decimal("-0.01") <= gap <= tolerance
            corroboration.append({"boundary": name, "snapshotCpuSeconds": cpu(snapshot),
                                  "independentWait4CpuSeconds": cpu(usage), "gapSeconds": gap,
                                  "maximumAllowedPostSnapshotCpuSeconds": tolerance, "passed": passed})
            assert passed, f"converted CPU does not corroborate independent wait4: {name}"
    resource["cpuUnitVerified"] = verified
    resource["timebaseAudit"] = {"snapshots": annotations, "wait4Corroboration": corroboration,
                                "proof": proof, "originalResourceEvidence": original,
                                "note": "Derived corrected output only. Original report, raw request evidence, wait4 CPU/RSS and latency are unchanged."}
    return resource


def audit_run(directory, arm_name, timebase_proof=None):
    root = Path(directory).resolve()
    report_path = root / "report.json"
    report = read(report_path)
    result = {"run": str(root), "arm": arm_name, "reportSha256": digest(report_path.read_bytes()),
              "performanceAcceptance": False, "errors": [], "warnings": [], "cases": []}
    errors = result["errors"]
    warnings = result["warnings"]
    if report.get("performanceAcceptance") is not False:
        errors.append("controller report unexpectedly claims performance acceptance")
    arm = report.get("arms", {}).get(arm_name)
    if arm is None:
        errors.append("requested arm has no retained report; run may still be active")
        result["auditValid"] = False
        result["workloadComplete"] = False
        return result
    result["controllerComplete"] = report.get("complete") is True and arm.get("complete") is True
    result["controllerErrors"] = report.get("errors", []) + arm.get("errors", [])
    result["protocol"] = report["protocol"]
    result["environment"] = report.get("environment")
    result["binarySha256"] = arm.get("binarySha256")
    result["catalogSha256"] = report.get("catalogSha256")
    result["manifestSha256"] = report.get("manifestSha256")
    result["sourceInputsDigest"] = typed_digest(report.get("sourceInputs"))
    expected_graphs = {row["graphId"] for row in report.get("sourceInputs", [])}
    if len(expected_graphs) != 64:
        errors.append("source provenance does not name 64 unique graphs")
    registry_path = root / arm_name / "query" / "registry.json"
    if registry_path.exists():
        registry = read(registry_path)
        result["registryTotals"] = registry.get("totals")
        if registry.get("count") != 64 or {g["id"] for g in registry["graphs"]} != expected_graphs:
            errors.append("registered graph scope differs from source provenance")
        if any(g["nodes"] <= 0 for g in registry["graphs"]) or sum(g["nodes"] for g in registry["graphs"]) != registry["totals"]["nodes"]:
            errors.append("registered node totals invalid")
    else:
        errors.append("missing query-process registry evidence")
    seal_path = root / "fixture-seal.json"
    if seal_path.exists():
        seal = read(seal_path)
        # File paths/mtime may differ across relocated copies; content identity does not.
        seal_content = sorted((r["graph"], str(Path(r["path"]).name), r["bytes"], r["sha256"]) for r in seal)
        result["fixtureContentDigest"] = typed_digest([list(row) for row in seal_content])
        result["fixtureFiles"] = len(seal)
        result["fixtureBytes"] = sum(r["bytes"] for r in seal)
    else:
        errors.append("missing input fixture seal")
    queries = read(root / "queries.json")
    query_by_id = {q["id"]: q for q in queries}
    if len(query_by_id) != report["protocol"]["cases"]:
        errors.append("query catalog size/uniqueness differs from protocol")
    result["queryDigests"] = {q["id"]: q["sha256"] for q in queries}
    for query in queries:
        if digest(query["query"].encode()) != query["sha256"]:
            errors.append(f"query digest mismatch: {query['id']}")
    samples = arm.get("samples", [])
    result["issuedRequests"] = len(samples)
    result["stageCounts"] = dict(Counter(row["stage"] for row in samples))
    grouped = defaultdict(list)
    oracle = {}
    seen = set()
    complete_bytes = 0
    for record in samples:
        case = record["case"]
        stage = record["stage"]
        grouped[(case, stage)].append(record)
        identity = (stage, record["sequence"])
        prefix = f"{stage}/{record['sequence']:06} {case}"
        try:
            assert identity not in seen, "duplicate request sequence"
            seen.add(identity)
            assert case in query_by_id, "unknown query case"
            assert record["querySha256"] == query_by_id[case]["sha256"], "query identity drift"
            assert len(record["graphIds"]) == len(set(record["graphIds"])) == 64, "non-real64 scope"
            assert set(record["graphIds"]) == expected_graphs, "request source identities differ"
            assert record["startNs"] <= record["endNs"], "negative request interval"
            path = root / arm_name / "query" / stage / f"{record['sequence']:06}.json"
            assert typed(read(path.with_suffix(".record.json"))) == typed(record), "journal/report differ"
            receipt = read(path.with_suffix(".receipt.json"))
            body_bytes = path.read_bytes()
            assert receipt["bodyBytes"] == len(body_bytes), "body length differs"
            assert receipt["bodySha256"] == digest(body_bytes), "body hash differs"
            if record.get("passed") is not True:
                # All bytes/receipts remain evidence, including non-JSON and truncated bodies.
                assert record.get("error"), "failure lacks reason"
                continue
            assert receipt["passed"] is True and receipt["complete"] is True, "successful record has incomplete receipt"
            assert receipt["status"] == 200 and type(receipt["status"]) is int, "wrong HTTP status"
            assert close(receipt["requestMs"], record["requestMs"]), "request timing differs"
            body = parse(body_bytes)
            assert "error" not in body, "successful response contains error"
            assert body["graphCount"] == 64 and type(body["graphCount"]) is int, "response graph scope differs"
            assert type(body["rowCount"]) is int and body["rowCount"] == len(body["rows"]), "row count differs"
            assert len(body["columns"]) == len(set(body["columns"])), "duplicate columns"
            for row in body["rows"]:
                assert set(row).issubset(set(body["columns"]) | {"$metadata"}), "undeclared projected field"
                ids = row["$metadata"]["graphIds"]
                assert len(ids) == len(set(ids)) and set(ids).issubset(record["graphIds"]), "invalid provenance"
            body_digest = typed_digest(body)
            assert oracle.setdefault(case, body_digest) == body_digest, "complete typed responses differ"
            complete_bytes += len(body_bytes)
        except Exception as exc:
            errors.append(f"{prefix}: {exc}")
    result["verifiedCompleteBodyBytes"] = complete_bytes
    result["oracleTypedDigests"] = oracle
    reported_journals = {root / arm_name / "query" / r["stage"] / f"{r['sequence']:06}.record.json" for r in samples}
    actual_journals = set((root / arm_name / "query").glob("*/*.record.json"))
    if actual_journals != reported_journals:
        errors.append(f"journal/report file set differs: {len(actual_journals-reported_journals)} unreported, {len(reported_journals-actual_journals)} missing")
    for stage, issued_count in result["stageCounts"].items():
        stage_path = root / arm_name / "query" / stage / "stage.json"
        if not stage_path.exists():
            errors.append(f"missing completion/error journal for {stage}")
        else:
            stage_result = read(stage_path)
            if stage_result["completedRequests"] != issued_count:
                errors.append(f"stage completion count differs: {stage}")
            if result["controllerComplete"] and (stage_result["errors"] or stage_result["expectedRequests"] != issued_count):
                errors.append(f"completed controller includes incomplete/failed stage: {stage}")
    stored_oracle = arm.get("oracle", [])
    if stored_oracle:
        if len(stored_oracle) != len(queries):
            errors.append("stored oracle has wrong case count")
        else:
            for query, body in zip(queries, stored_oracle):
                if oracle.get(query["id"]) != typed_digest(body):
                    errors.append(f"stored/raw typed oracle differs: {query['id']}")
    pressure_n = report["protocol"]["pressurePerCase"]
    warmup_n = report["protocol"]["warmupPerCase"]
    for query in queries:
        case = query["id"]
        pressure_records = grouped[(case, "pressure")]
        pressure_values = [r["requestMs"] for r in pressure_records if r.get("passed") is True and r.get("requestMs") is not None]
        isolated_n = 0
        if report["protocol"].get("isolatedPerCase", 0) and pressure_n and len(pressure_values) == pressure_n:
            if quantile(pressure_values, .5) < report["protocol"]["isolatedThresholdMs"]:
                isolated_n = report["protocol"]["isolatedPerCase"]
        for stage, expected in (("first-use", 1), ("warmup", warmup_n), ("pressure", pressure_n), ("isolated", isolated_n)):
            records = grouped[(case, stage)]
            failed = sum(r.get("passed") is not True for r in records)
            values = [r["requestMs"] for r in records if r.get("passed") is True and r.get("requestMs") is not None]
            available = expected > 0 and len(records) == expected and len(values) == expected and failed == 0
            row = {"case": case, "stage": stage, "issued": len(records), "expected": expected,
                   "failedCount": failed, "available": available,
                   "p50Ms": quantile(values, .5) if available and stage != "first-use" else None,
                   "p95Ms": quantile(values, .95) if available and stage != "first-use" else None,
                   "firstUseMs": values[0] if available and stage == "first-use" else None,
                   "minMs": min(values) if available else None,
                   "maxMs": max(values) if available else None,
                   "rawRequestMs": [r.get("requestMs") for r in records],
                   "failures": [r.get("error") for r in records if r.get("passed") is not True]}
            result["cases"].append(row)
            if stage in ("pressure", "isolated") and available:
                timing = next((t for t in report.get("timings", []) if t["case"] == case and t["stage"] == stage), None)
                if timing is None:
                    errors.append(f"missing controller timing for {case}")
                else:
                    for metric in ("p50Ms", "p95Ms"):
                        if timing[arm_name].get(metric) is None or not close(timing[arm_name][metric], row[metric]):
                            errors.append(f"controller/raw {metric} mismatch: {case}")
            elif stage in ("pressure", "isolated") and expected and not available:
                warnings.append(f"{case}: {stage} incomplete/failed; percentiles unavailable")
    resource = {"loading": arm.get("loading"), "pressure": arm.get("pressure"),
                "queryLifecycle": arm.get("queryLifecycle"), "queryLifecycleError": arm.get("queryLifecycleError"),
                "queryReady": arm.get("queryReady"), "afterFirstUse": arm.get("afterFirstUse"), "queryEnd": arm.get("queryEnd")}
    try:
        resource = correct_darwin_resources(resource, timebase_proof, report.get("environment", {}))
    except Exception as exc:
        errors.append(f"CPU unit validation/correction failed: {exc}")
        resource["cpuUnitVerified"] = False
    if resource["pressure"] is not None:
        pressure = resource["pressure"]
        recomputed_cpu = cpu(pressure["after"]) - cpu(pressure["before"])
        if recomputed_cpu < 0 or not close(pressure["cpuSeconds"], recomputed_cpu):
            errors.append("pressure CPU delta disagrees with snapshots")
        resource["recomputedPressureCpuSeconds"] = recomputed_cpu
    for name, value in (("loading", resource["loading"] and resource["loading"].get("lifetime")),
                        ("queryLifecycle", resource["queryLifecycle"])):
        if value is not None:
            if not close(value["cpuSeconds"], cpu(value)) or value["kernelPeakRssBytes"] <= 0:
                errors.append(f"invalid {name} lifetime resource record")
    resource["limitations"] = ["CPU covers fixed mixed workload, not each case",
        "query kernel peak RSS includes loading, first use, warmup, pressure and any isolated samples",
        "kernel lifetime peak cannot be subtracted to derive query-window peak",
        "load-only server is a separate process; first use does not mean disk-cold files",
        "resource snapshots do not prove environment isolation or independent result semantics"]
    result["resources"] = resource
    result["auditValid"] = not errors
    result["workloadComplete"] = result["controllerComplete"] and not errors
    return result


def delta(a, b):
    if a is None or b is None:
        return {"base": a, "candidate": b, "delta": None, "deltaPercent": None}
    return {"base": a, "candidate": b, "delta": b - a,
            "deltaPercent": (b - a) / a * 100 if a != 0 else None}


def compare(base, candidate):
    reasons = []
    for name in ("catalogSha256", "fixtureContentDigest", "sourceInputsDigest", "queryDigests", "oracleTypedDigests"):
        if base.get(name) != candidate.get(name):
            reasons.append(f"{name} differs")
    if base.get("protocol") != candidate.get("protocol"):
        reasons.append("protocol differs")
    if not base["workloadComplete"] or not candidate["workloadComplete"]:
        reasons.append("one or both workloads incomplete or audit-invalid")
    def matched_delta(a, b):
        if reasons:
            return {"base": a, "candidate": b, "delta": None, "deltaPercent": None,
                    "unavailableReasons": reasons}
        return delta(a, b)
    rows = []
    for a in base["cases"]:
        if a["stage"] != "pressure":
            continue
        b = next((r for r in candidate["cases"] if (r["case"], r["stage"]) == (a["case"], a["stage"])), {})
        rows.append({"case": a["case"], "p50Ms": matched_delta(a["p50Ms"], b.get("p50Ms")),
                     "p95Ms": matched_delta(a["p95Ms"], b.get("p95Ms")), "comparable": not reasons})
    resource_rows = []
    paths = (("loadingCpuSeconds", ("loading", "lifetime", "cpuSeconds")),
             ("loadingPeakRssBytes", ("loading", "lifetime", "kernelPeakRssBytes")),
             ("pressureMixedCpuSeconds", ("pressure", "cpuSeconds")),
             ("queryLifecyclePeakRssBytes", ("queryLifecycle", "kernelPeakRssBytes")))
    for name, path in paths:
        values = []
        for arm in (base, candidate):
            value = arm["resources"]
            if name == "pressureMixedCpuSeconds" and not value.get("cpuUnitVerified"):
                values.append(None)
                continue
            for key in path:
                value = value.get(key) if isinstance(value, dict) else None
            values.append(value)
        difference = matched_delta(*values)
        bound = None if reasons or any(v is None for v in values) else values[1] <= values[0] * Decimal("1.05")
        resource_rows.append({"metric": name, **difference, "observedWithinFivePercent": bound})
    return {"comparable": not reasons, "unavailableReasons": reasons, "cases": rows,
            "resourceObservations": resource_rows, "performanceAcceptance": False,
            "limitations": "One local matched mix observation; no each-case resource proof, uncertainty or full production acceptance."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline_run")
    parser.add_argument("--baseline-arm", default="base", choices=("base", "candidate"))
    parser.add_argument("--candidate-run")
    parser.add_argument("--candidate-arm", default="candidate", choices=("base", "candidate"))
    parser.add_argument("--darwin-timebase-proof", help="Explicit same-host proof for deriving corrected legacy Mach-tick CPU; never changes original reports")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    proof = read(Path(args.darwin_timebase_proof)) if args.darwin_timebase_proof else None
    baseline = audit_run(args.baseline_run, args.baseline_arm, proof)
    result = {"baseline": baseline, "performanceAcceptance": False}
    if args.candidate_run:
        candidate = audit_run(args.candidate_run, args.candidate_arm, proof)
        result["candidate"] = candidate
        if "resources" in baseline and "resources" in candidate:
            result["comparison"] = compare(baseline, candidate)
    Path(args.output).write_text(json.dumps(native(result), indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"output": args.output, "baselineAuditValid": baseline["auditValid"],
                      "baselineComplete": baseline["workloadComplete"],
                      "candidateAuditValid": result.get("candidate", {}).get("auditValid"),
                      "comparable": result.get("comparison", {}).get("comparable"),
                      "performanceAcceptance": False}))


if __name__ == "__main__":
    main()
