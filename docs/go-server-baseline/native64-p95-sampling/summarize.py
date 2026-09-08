"""Summarize independent real64 trial pairs; never pool latency across cases."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics

STATES = ("cold", "startup-prepared", "warm-after-failed-prewarm")
WORKLOAD_SHA256 = "378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023"
MIN_SAMPLES = 200


def digest(data):
    return hashlib.sha256(data).hexdigest()


def is_sha(value):
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def distribution(values):
    """Empirical nearest-rank P95, with the acceptance sample floor explicit."""
    ordered = sorted(values)
    n = len(ordered)
    return {
        "sampleCount": n,
        "sampleStatus": "sufficient" if n >= MIN_SAMPLES else "insufficient",
        "minNanos": ordered[0] if n else None,
        "medianNanos": statistics.median(ordered) if n else None,
        "p95Nanos": ordered[(95 * n + 99) // 100 - 1] if n else None,
        "maxNanos": ordered[-1] if n else None,
        "p95Rank": (95 * n + 99) // 100 if n else None,
    }


def accumulator():
    return {"success": [], "failed": [], "sampleCount": 0, "failureCount": 0,
            "censoredTimeoutCount": 0, "invalidCount": 0, "messageSources": {}}


def runtime_summary(samples):
    success, failed = samples["success"], samples["failed"]
    kind = "mixed-success-and-failure" if success and failed else (
        "time-to-failure" if failed else "successful-latency")
    chosen = [] if success and failed else (failed if failed else success)
    return {**{k: v for k, v in samples.items() if k not in ("success", "failed")},
            "measurementKind": kind, "exactSampleCount": len(success) + len(failed),
            "distribution": distribution(chosen),
            "successfulLatency": distribution(success), "timeToFailure": distribution(failed)}


def summarize(controller, workload, documents):
    """Pure aggregation. documents maps trial IDs to decoded, hash-checked input.

    The CLI validates the pinned workload and file hashes. Small fabricated inputs
    used by unit tests exercise statistics only and are not benchmark evidence.
    """
    cases = workload["cases"]
    problems = []

    def problem(code, trial=None, index=None, runtime=None):
        problems.append({"code": code, "trialId": trial, "index": index, "runtime": runtime})

    if controller.get("schemaVersion") != 1:
        problem("controller-schema")
    if controller.get("states") != list(STATES):
        problem("state-inventory")
    if type(controller.get("measurementAcceptanceEligible")) is not bool:
        problem("measurement-eligibility-missing")
    limitations = controller.get("limitations")
    if not isinstance(limitations, list) or not all(isinstance(x, str) for x in limitations):
        problem("limitations-missing")
    if not is_sha(controller.get("graphManifestSHA256")):
        problem("graph-manifest-hash-invalid")
    if len({c["id"] for c in cases}) != len(cases):
        problem("duplicate-workload-case")
    accum = {(state, i): {r: accumulator() for r in ("main", "go")}
             for state in STATES for i in range(len(cases))}
    seen, seen_paths = set(), set()
    trial_counts = {state: 0 for state in STATES}
    trials = controller.get("trials", [])
    if not isinstance(trials, list):
        problem("trial-list-invalid")
        trials = []
    for trial in trials:
        if not isinstance(trial, dict):
            problem("invalid-trial-entry")
            continue
        tid, state = trial.get("id"), trial.get("state")
        if not isinstance(tid, str) or not tid or tid in seen:
            problem("duplicate-or-invalid-trial", tid)
            continue
        seen.add(tid)
        path = trial.get("recordsPath")
        if not isinstance(path, str) or not path or path in seen_paths:
            problem("duplicate-or-invalid-records-path", tid)
            continue
        seen_paths.add(path)
        if state not in STATES:
            problem("unknown-state", tid)
            continue
        trial_counts[state] += 1
        for flag in ("verificationPassed", "complete", "serialExecutionVerified"):
            if trial.get(flag) is not True:
                problem("trial-" + flag, tid)
        doc = documents.get(tid)
        if not isinstance(doc, dict):
            problem("missing-records-document", tid)
            continue
        expected = {"schemaVersion": 1, "trialId": tid, "state": state,
                    "workloadSHA256": controller.get("workload", {}).get("sha256"),
                    "graphManifestSHA256": controller.get("graphManifestSHA256")}
        if any(doc.get(k) != v for k, v in expected.items()):
            problem("records-identity-or-manifest", tid)
            continue
        records = doc.get("records")
        if not isinstance(records, list) or len(records) != len(cases):
            problem("case-inventory", tid)
            continue
        if any(not isinstance(row, dict) or type(row.get("index")) is not int
               or row.get("index") != i or row.get("id") != case["id"]
               or row.get("querySHA256") != digest(case["query"].encode("utf-8"))
               for i, (row, case) in enumerate(zip(records, cases))):
            problem("case-order-id-or-query", tid)
            continue
        for i, row in enumerate(records):
            for runtime in ("main", "go"):
                acc = accum[state, i][runtime]
                acc["sampleCount"] += 1
                observation = row.get(runtime)
                if not isinstance(observation, dict):
                    acc["invalidCount"] += 1
                    problem("missing-runtime-observation", tid, i, runtime)
                    continue
                outcome = observation.get("outcome")
                if outcome != "success":
                    acc["failureCount"] += 1
                source = observation.get("messageSource")
                if source in ("observed", "reference-derived"):
                    acc["messageSources"][source] = acc["messageSources"].get(source, 0) + 1
                else:
                    problem("message-source-invalid", tid, i, runtime)
                if observation.get("validationError"):
                    problem("validation-error", tid, i, runtime)
                censored = observation.get("censoredTimeout")
                if censored is True or outcome == "timeout":
                    acc["censoredTimeoutCount"] += 1
                    problem("censored-timeout", tid, i, runtime)
                    continue
                latency = observation.get("latencyNanos")
                if censored is not False or outcome not in ("success", "failed") or type(latency) is not int or latency <= 0:
                    acc["invalidCount"] += 1
                    problem("invalid-exact-observation", tid, i, runtime)
                    continue
                for field in ("rowCount", "responseBytes"):
                    if type(observation.get(field)) is not int or observation[field] < 0:
                        problem("invalid-" + field, tid, i, runtime)
                if not isinstance(observation.get("digest"), str) or not observation["digest"]:
                    problem("invalid-digest", tid, i, runtime)
                if outcome == "failed":
                    problem("query-failed", tid, i, runtime)
                    if not isinstance(observation.get("error"), str) or not observation["error"]:
                        problem("failure-error-missing", tid, i, runtime)
                elif observation.get("error") or observation.get("message") is not None:
                    problem("success-has-error", tid, i, runtime)
                acc[outcome].append(latency)
            m, g = row.get("main"), row.get("go")
            if isinstance(m, dict) and isinstance(g, dict):
                if any(m.get(field) != g.get(field) for field in
                       ("outcome", "digest", "rowCount", "responseBytes", "error", "message")):
                    problem("main-go-result-mismatch", tid, i)
    trial_states = {trial["id"]: trial.get("state") for trial in trials
                    if isinstance(trial, dict) and isinstance(trial.get("id"), str)}
    case_issues = {}
    for issue in problems:
        if issue["index"] is not None:
            key = (trial_states.get(issue["trialId"]), issue["index"])
            case_issues[key] = case_issues.get(key, 0) + 1
    rows = []
    for state in STATES:
        for i, case in enumerate(cases):
            runtimes = {r: runtime_summary(accum[state, i][r]) for r in ("main", "go")}
            m, g = runtimes["main"], runtimes["go"]
            mp, gp = m["distribution"]["p95Nanos"], g["distribution"]["p95Nanos"]
            sufficient = all(r["distribution"]["sampleStatus"] == "sufficient" for r in (m, g))
            clean = all(not r["failureCount"] and not r["invalidCount"] and not r["censoredTimeoutCount"] for r in (m, g))
            ratio = mp / gp if mp is not None and gp is not None else None
            rows.append({"state": state, "index": i, "id": case["id"],
                         "querySHA256": digest(case["query"].encode("utf-8")), **runtimes,
                         "sampleStatus": "sufficient" if sufficient else "insufficient",
                         "issueCount": case_issues.get((state, i), 0),
                         "mainP95OverGoP95": ratio,
                         "thresholdMet": mp >= 10 * gp if mp is not None and gp is not None else None,
                         "successfulCaseP95ThresholdEligible": sufficient and clean and not case_issues.get((state, i), 0)})
    eligible = controller.get("measurementAcceptanceEligible") is True
    integrity = not problems
    accepted = eligible and integrity and all(r["successfulCaseP95ThresholdEligible"] and r["thresholdMet"] for r in rows)
    return {"schemaVersion": 1, "caseCountPerState": len(cases), "states": list(STATES),
            "workloadSHA256": controller.get("workload", {}).get("sha256"),
            "graphManifestSHA256": controller.get("graphManifestSHA256"),
            "minimumSamplesPerCasePerRuntime": MIN_SAMPLES,
            "quantileMethod": "nearest-rank ceil(0.95*n); independent per state/case/runtime",
            "trialCountByState": trial_counts, "measurementAcceptanceEligible": eligible,
            "limitations": limitations, "integrityPassed": integrity, "issues": problems,
            "tenfoldP95AcceptanceProven": bool(accepted), "records": rows,
            "notes": ["No average or pooled P95 across cases is calculated.",
                      "P95 below 200 exact samples is descriptive only and insufficient for acceptance.",
                      "Timeouts are censored and excluded from exact latency distributions.",
                      "Failure timings remain separate time-to-failure distributions; failures block acceptance.",
                      "Reference-derived messages are not claimed to be observed during the timed trial."]}


def summarize_file(controller_path):
    """Validate immutable input hashes before aggregating any samples."""
    controller_path = Path(controller_path).resolve()
    controller = json.loads(controller_path.read_text())
    root = controller_path.parent
    path = root / controller["workload"]["path"]
    raw = path.read_bytes()
    if digest(raw) != controller["workload"]["sha256"] or digest(raw) != WORKLOAD_SHA256:
        raise ValueError("pinned workload hash mismatch")
    workload = json.loads(raw)
    if len(workload["cases"]) != 1267 or len(workload["sourceOrder"]) != 64:
        raise ValueError("workload must retain all 1267 original cases and 64 sources")
    file_issues, evidence = [], {str(controller_path): digest(controller_path.read_bytes()), str(path.resolve()): digest(raw)}
    physical_paths, trial_by_id = set(), {}
    for trial in controller.get("trials", []):
        if isinstance(trial, dict) and isinstance(trial.get("id"), str):
            trial_by_id.setdefault(trial["id"], trial)

    class Documents:
        # Retain clocks, not hundreds of complete decoded result documents.
        def get(self, trial_id):
            trial = trial_by_id[trial_id]
            try:
                record_path = (root / trial["recordsPath"]).resolve()
                if record_path in physical_paths:
                    raise ValueError("reused physical records file")
                physical_paths.add(record_path)
                raw = record_path.read_bytes()
                if digest(raw) != trial["recordsSHA256"]:
                    raise ValueError("records file hash mismatch")
                document = json.loads(raw)
                evidence[str(record_path)] = digest(raw)
                return document
            except (OSError, ValueError, KeyError, TypeError) as error:
                file_issues.append({"code": "trial-file-integrity", "trialId": trial_id, "detail": str(error)})
                return None

    report = summarize(controller, workload, Documents())
    report["issues"] = file_issues + report["issues"]
    if file_issues:
        report["integrityPassed"] = report["tenfoldP95AcceptanceProven"] = False
    evidence[str(Path(__file__).resolve())] = digest(Path(__file__).read_bytes())
    report["evidenceSHA256"] = evidence
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--controller", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = summarize_file(args.controller)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = {"integrityPassed": False, "tenfoldP95AcceptanceProven": False,
                  "issues": [{"code": "controller-or-workload-invalid", "detail": str(error)}]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    print(json.dumps({key: value for key, value in result.items() if key not in ("records", "evidenceSHA256", "issues")}, indent=2))
    raise SystemExit(0 if result["tenfoldP95AcceptanceProven"] else 1)
