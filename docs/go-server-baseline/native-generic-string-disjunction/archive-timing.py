"""Archive a completed generic string disjunction timing smoke; never run while timing is live.

A completed output is verified on rerun without overwriting it. An incomplete
output is preserved and requires a fresh --output directory.
"""
import argparse
import csv
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import statistics
import subprocess
import sys

BASE = Path(__file__).resolve().parent
SAMPLING = BASE.parent / "native64-p95-sampling"
DEFAULT_ROOT = Path("/Users/johnsonlee/.codex/benchmarks/graphite")


def read(path):
    return json.loads(path.read_text())


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x") as stream:
        json.dump(value, stream, indent=2, allow_nan=False)
        stream.write("\n")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def ratios(values):
    values = sorted(values)
    n = len(values)
    return {"count": n, "min": values[0], "median": statistics.median(values),
            "max": values[-1], "nearestRankQuantiles": {
                str(p / 100): values[(p * n + 99) // 100 - 1]
                for p in (5, 25, 75, 95, 99)},
            "interpretation": "Distribution across distinct case point ratios; not latency P95."}


def verify_archive(output):
    """Separate post-write reads verify packed bytes, decoded bytes and sources."""
    manifest = read(output / "archive-manifest.json")
    for entry in manifest["entries"]:
        packed = (output / entry["archive"]).read_bytes()
        require(sha(packed) == entry["archiveSHA256"], "archive hash changed")
        plain = gzip.decompress(packed) if entry["encoding"] == "gzip" else packed
        require(sha(plain) == entry["sourceSHA256"], "decoded hash changed")
        require(plain == Path(entry["source"]).read_bytes(), "source/copy bytes differ")
    for alias in manifest["deduplication"]:
        plain = gzip.decompress((output / alias["archive"]).read_bytes())
        require(plain == Path(alias["source"]).read_bytes(), "deduplicated source differs")
        require(sha(plain) == alias["sha256"], "deduplicated hash differs")
    for identity in manifest["identityOnly"]:
        require(sha(Path(identity["source"]).read_bytes()) == identity["sha256"], "identity changed")
    for name, expected in manifest["generatedArtifactSHA256"].items():
        require(sha((output / name).read_bytes()) == expected, "generated artifact changed: " + name)
    return {"passed": True, "copiedFilesVerified": len(manifest["entries"]),
            "deduplicatedSourcesVerified": len(manifest["deduplication"]),
            "identityOnlyFilesVerified": len(manifest["identityOnly"]),
            "generatedArtifactsVerified": len(manifest["generatedArtifactSHA256"]),
            "archiveManifestSHA256": sha((output / "archive-manifest.json").read_bytes()),
            "verification": "Independent reread of each source and archive; exact decoded bytes and SHA256.",
            "graphClonesReopened": False}


def compare_baseline(controller, documents, baseline_path):
    baseline = read(baseline_path)
    states = []
    for old_state in baseline["states"]:
        state = old_state["state"]
        old_path = Path(old_state["source"]["path"])
        packed = old_path.read_bytes()
        raw = gzip.decompress(packed)
        require(sha(packed) == old_state["source"]["archiveSHA256"], "baseline archive hash")
        require(sha(raw) == old_state["source"]["uncompressedSHA256"], "baseline source hash")
        old = json.loads(raw)
        new = documents[state]
        require(old["workloadSHA256"] == new["workloadSHA256"] == baseline["workload"]["sha256"], "comparison workload differs")
        require(old["graphManifestSHA256"] == new["graphManifestSHA256"], "comparison graph manifest differs")
        rows, errors = [], []
        for a, b in zip(old["records"], new["records"]):
            require(all(a[k] == b[k] for k in ("index", "id", "querySHA256")), "comparison case identity")
            for runtime in ("main", "go"):
                require(all(a[runtime][k] == b[runtime][k] for k in
                            ("outcome", "digest", "rowCount", "responseBytes", "error", "message")), "comparison public result differs")
            row = {"index": a["index"], "id": a["id"], "querySHA256": a["querySHA256"],
                   "oldMainLatencyNanos": a["main"]["latencyNanos"], "newMainLatencyNanos": b["main"]["latencyNanos"],
                   "oldGoLatencyNanos": a["go"]["latencyNanos"], "newGoLatencyNanos": b["go"]["latencyNanos"],
                   "oldGoOverNewGoPointRatio": a["go"]["latencyNanos"] / b["go"]["latencyNanos"],
                   "newMainOverNewGoPointRatio": b["main"]["latencyNanos"] / b["go"]["latencyNanos"]}
            if b["go"]["outcome"] == "success":
                rows.append(row)
            else:
                errors.append({**row, "measurementKind": "time-to-failure", "old": a, "new": b})
        require(len(rows) == 1266 and len(errors) == 1 and errors[0]["index"] == 821, "comparison success/error coverage")
        states.append({"state": state, "baselineSource": old_state["source"], "successCount": len(rows),
                       "samplesPerCasePerRuntimePerRevision": 1,
                       "successfulLatencySumNanos": {key: sum(r[key] for r in rows) for key in
                           ("oldMainLatencyNanos", "newMainLatencyNanos", "oldGoLatencyNanos", "newGoLatencyNanos")},
                       "oldGoOverNewGoPointRatioDistribution": ratios([r["oldGoOverNewGoPointRatio"] for r in rows]),
                       "newMainOverNewGoPointRatioDistribution": ratios([r["newMainOverNewGoPointRatio"] for r in rows]),
                       "oldGoSlowerThanOldMainCount": sum(r["oldGoLatencyNanos"] > r["oldMainLatencyNanos"] for r in rows),
                       "newGoSlowerThanNewMainCount": sum(r["newGoLatencyNanos"] > r["newMainLatencyNanos"] for r in rows),
                       "newGoFasterThanOldGoCount": sum(r["newGoLatencyNanos"] < r["oldGoLatencyNanos"] for r in rows),
                       "newMainOverNewGoAtLeastTenCount": sum(r["newMainLatencyNanos"] >= 10 * r["newGoLatencyNanos"] for r in rows),
                       "top20ByNewGoLatencyNanos": sorted(rows, key=lambda r: (-r["newGoLatencyNanos"], r["index"]))[:20],
                       "originalFailure": errors[0], "records": rows})
    return {"schemaVersion": 1, "baselineSummary": {"path": str(baseline_path), "sha256": sha(baseline_path.read_bytes())},
            "baselineEngineRevision": baseline["baselineEngineRevision"], "candidateBuild": controller["build"],
            "samplesPerCasePerRuntimePerRevision": 1, "perCaseP95Available": False,
            "tenfoldP95AcceptanceProven": False, "measurementAcceptanceEligible": False, "states": states,
            "limitations": ["Point observations from separate n=1 campaigns, not a causal performance estimate or per-case latency P95.",
                            "Ratio quantiles compare different cases; no latency percentile is pooled across cases.",
                            "Sums cover successful query timers only, not process wall time; original failure retained separately.",
                            "Warm state is diagnostic continuation after failed original prewarm, not formal original warm."]}


def archive(args):
    output, campaign, build = args.output.resolve(), args.campaign.resolve(), args.build.resolve()
    if output.exists():
        require((output / "archive-copy-verification.json").exists(), "Incomplete archive preserved; choose a new --output.")
        print(json.dumps(verify_archive(output), indent=2))
        return
    controller = read(campaign / "controller.json")
    require(controller.get("status") == "complete" and controller.get("currentTrial") is None, "Campaign is still active or incomplete.")
    require(controller["plannedPairsPerState"] == 1 and len(controller["trials"]) == 3, "Expected one completed pair per state.")
    require(Path(controller["build"]).resolve() == build, "Wrong build directory.")
    for name, field in (("inputs.json", "buildInputsSHA256"), ("build.json", "buildSHA256")):
        require(sha((build / name).read_bytes()) == controller[field], "Build metadata changed.")
    metadata = read(build / "build.json")
    require(sha(Path(metadata["nativeBinary"]).read_bytes()) == metadata["nativeBinarySHA256"], "Native binary changed.")
    workload_path = Path(controller["workload"]["path"])
    workload = read(workload_path)
    require(sha(workload_path.read_bytes()) == controller["workload"]["sha256"], "Workload changed.")
    output.mkdir(parents=True)
    summary_path = campaign / "archive-smoke-summary.json"
    require(not summary_path.exists(), "Summary source already exists; preserve it and choose fresh campaign/output paths.")
    command = [sys.executable, str(SAMPLING / "summarize.py"), "--controller", str(campaign / "controller.json"), "--output", str(summary_path)]
    with (output / "summary.log").open("x") as log:
        process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    require(process.returncode == 1, "Expected n=1 ineligible summary exit 1.")
    summary = read(summary_path)
    require(len(summary["records"]) == 3801 and summary["tenfoldP95AcceptanceProven"] is False, "Incomplete summary.")
    require(all(r["sampleStatus"] == "insufficient" for r in summary["records"]), "Unexpected sample counts.")
    require(len(summary["issues"]) == 6 and all(r["code"] == "query-failed" and r["index"] == 821 for r in summary["issues"]), "New smoke issues.")
    spec = importlib.util.spec_from_file_location("archive_pair_verifier", SAMPLING / "verify_pair.py")
    verifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verifier)
    reference = verifier.references(workload)
    documents, intervals, entries, identities, aliases = {}, [], [], [], []

    def copy(source, relative, compressed=False):
        raw = source.read_bytes()
        payload = gzip.compress(raw, mtime=0) if compressed else raw
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("xb") as stream:
            stream.write(payload)
        entries.append({"source": str(source), "archive": relative, "encoding": "gzip" if compressed else "identity",
                        "sourceBytes": len(raw), "sourceSHA256": sha(raw), "archiveBytes": len(payload), "archiveSHA256": sha(payload)})

    for trial in controller["trials"]:
        directory = campaign / trial["id"]
        require(all(trial[f] is True for f in ("complete", "verificationPassed", "serialExecutionVerified")), "Unverified pair.")
        document = read(directory / "records.json")
        require(sha((directory / "records.json").read_bytes()) == trial["recordsSHA256"], "Normalized record hash.")
        require(len(document["records"]) == 1267 and document["state"] == trial["state"], "Pair identity/coverage.")
        require(trial["state"] not in documents, "Duplicate state.")
        documents[trial["state"]] = document
        with (directory / "main/capture/main-observations.tsv").open() as stream:
            main = list(csv.DictReader(stream, delimiter="\t"))
        native = [json.loads(line) for line in (directory / "go/capture/observations.jsonl").read_text().splitlines()]
        verifier.original.compare_cases(workload, reference, main, native[1:-1])
        for index, row in enumerate(document["records"]):
            require(row["index"] == index and row["id"] == workload["cases"][index]["id"], "Normalized case order.")
            for runtime, raw in (("main", main[index]), ("go", native[index + 1])):
                obs = row[runtime]
                require(obs["latencyNanos"] == int(raw["latencyNanos"]) and obs["outcome"] == raw["outcome"].lower(), "Normalized clocks/outcome differ.")
                require(all(obs[f] == raw[f] for f in ("digest",)), "Normalized digest differs.")
                require(all(obs[f] == int(raw[f]) for f in ("rowCount", "responseBytes")), "Normalized result counts differ.")
                require(obs["error"] == reference[index].get("error") and obs["message"] == reference[index].get("message"), "Normalized error differs.")
                require(obs["messageSource"] == ("reference-derived" if runtime == "main" and index == 821 else "observed"), "Message provenance differs.")
        warm = trial["state"] == "warm-after-failed-prewarm"
        if warm:
            with (directory / "main/capture/warmup-observations.tsv").open() as stream:
                wm = list(csv.DictReader(stream, delimiter="\t"))
            wn = [{**json.loads(line), "phase": "replay"} for line in (directory / "go/capture/warmup-observations.jsonl").read_text().splitlines()]
            verifier.original.compare_cases(workload, reference, wm, wn)
        for runtime in ("main", "go"):
            receipt = read(directory / runtime / "process.json")
            require(receipt["status"] == "exited" and receipt["exitCode"] == 1, "Runtime still active or wrong exit.")
            require(receipt["inputsUnchanged"] is True and receipt["fixtureAuditPassed"] is True, "Runtime audit failed.")
            require(sha((directory / runtime / "graphs-relocated.tsv").read_bytes()) == receipt["manifestSHA256"], "Relocated manifest changed.")
            intervals.append((receipt["startedAtNanos"], receipt["finishedAtNanos"], trial["id"], runtime))
            for filename in ("clone-preflight.json", "postrun-fixtures.json"):
                audit = read(directory / runtime / filename)
                require(audit["originalFiles"] == audit["matched"] == 1152 and not any(audit[f] for f in ("changed", "missing", "added")), "Fixture audit incomplete.")
        write(output / trial["id"] / "archive-reverification.json", {
            "trialId": trial["id"], "state": trial["state"], "passed": True, "orderedCasesPerRuntime": 1267,
            "successesPerRuntime": 1266, "originalErrorsPerRuntime": 1, "censoredTimeouts": 0,
            "canonicalSignaturesReverified": True, "normalizedClocksMatchRawLedgers": True,
            "warmLedgersReverified": warm, "graphClonesReopened": False,
            "graphAuditScope": "Original controller receipts retained; already cleaned clones are not reopened."})
        for source in sorted(directory.rglob("*")):
            if not source.is_file():
                continue
            relative = str(source.relative_to(campaign))
            if source.name == "actual-cases.json" and trial != controller["trials"][0]:
                first = controller["trials"][0]["id"] + "/main/capture/actual-cases.json.gz"
                aliases.append({"source": str(source), "archive": first, "sha256": sha(source.read_bytes())})
                continue
            compressed = source.stat().st_size > 16384 or source.name == "records.json"
            copy(source, relative + (".gz" if compressed else ""), compressed)
    intervals.sort()
    require(all(a < b for a, b, *_ in intervals) and all(intervals[i][1] <= intervals[i + 1][0] for i in range(5)), "Runtime intervals overlap.")
    copy(campaign / "controller.json", "controller.json")
    copy(campaign / "reference-preflight.json", "reference-preflight.json")
    bundle_heads = subprocess.check_output(["git", "bundle", "list-heads", str(args.source_bundle.resolve())], text=True)
    require(any(line.split()[0] == metadata["engineRevision"] for line in bundle_heads.splitlines()), "Measurement commit absent from source bundle.")
    copy(args.source_bundle.resolve(), "measurement-source.bundle")
    for name in ("build.json", "build.log", "inputs.json", "module-source.json", "dependencies.jsonstream"):
        compressed = (build / name).stat().st_size > 65536
        copy(build / name, "build/" + name + (".gz" if compressed else ""), compressed)
    # Keep the exact generated summary source and a compact archive of it.
    copy(summary_path, "smoke-summary.json.gz", True)
    comparison = compare_baseline(controller, documents, args.baseline.resolve())
    write(output / "baseline-comparison.json", comparison)
    identity_paths = [Path(__file__).resolve(), SAMPLING / "summarize.py", SAMPLING / "verify_pair.py",
                      SAMPLING / "prepare.py", SAMPLING / "run.py", SAMPLING / "main-tooling/build-verification.json",
                      SAMPLING / "main-tooling/tooling-manifest.json", Path(metadata["nativeBinary"]), workload_path,
                      verifier.FIXTURES, args.baseline.resolve()]
    for source in identity_paths:
        identities.append({"source": str(source), "bytes": source.stat().st_size, "sha256": sha(source.read_bytes())})
    (output / "README.md").write_text(
        "# generic string disjunction timing smoke\n\n"
        "Three completed real64 main/Go pairs; one sample per case/runtime/state. Each pair retains all 1,267 cases, "
        "with 1,266 successes and original case821 failure. The 3,801 summary rows are insufficient for P95; "
        "summary exit 1 and measurementAcceptanceEligible=false are expected. No 10× acceptance is established.\n\n"
        "`baseline-comparison.json` contains every successful old/new Go point ratio, sums and 20 largest new Go "
        "timings per state. These are single observations from separate campaigns, not a causal speedup estimate "
        "or latency P95. Original failures remain separate. Warm is diagnostic continuation after failed prewarm, "
        "not formal original warm. Main failed-query messages are reference-derived, not timed observations.\n\n"
        "The archive retains controller, normalized and raw timing/warm ledgers, process/fixture/cleanup receipts "
        "and build metadata. Repeated actual-cases bytes are stored once. Gzip decoding reproduces original bytes; "
        "controller paths and hashes have not been rewritten. The native binary and full build snapshot remain "
        "at the recorded external build path. `measurement-source.bundle` preserves the measured commit before "
        "any later amend. Graph clones were already cleaned by the controller; archival "
        "reverification checks retained ledgers and original audit receipts, without reopening deleted clones.\n\n"
        "See `archive-manifest.json` for source hashes and `archive-copy-verification.json` for independent post-copy "
        "byte verification. Re-running archive-timing.py verifies this completed archive without overwriting it.\n")
    copied = {e["archive"] for e in entries}
    generated = {str(p.relative_to(output)): sha(p.read_bytes()) for p in sorted(output.rglob("*"))
                 if p.is_file() and str(p.relative_to(output)) not in copied}
    write(output / "archive-manifest.json", {"schemaVersion": 1, "sourceCampaign": str(campaign), "sourceBuild": str(build),
          "engineRevision": metadata["engineRevision"], "mainRevision": workload["mainRevision"],
          "summaryCommand": command, "summaryExitCode": process.returncode, "samplesPerCasePerRuntimePerState": 1,
          "perCaseP95Available": False, "tenfoldP95AcceptanceProven": False, "runtimeIntervals": intervals,
          "measurementSourceBundleHeads": bundle_heads.splitlines(),
          "allSixRuntimeIntervalsNonoverlapping": True, "entries": entries, "deduplication": aliases,
          "identityOnly": identities, "generatedArtifactSHA256": generated})
    receipt = verify_archive(output)
    write(output / "archive-copy-verification.json", receipt)
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--campaign", type=Path, default=DEFAULT_ROOT / "generic-string-acb06574-timing-v1")
    parser.add_argument("--build", type=Path, default=DEFAULT_ROOT / "generic-string-acb06574-build-v1")
    parser.add_argument("--output", type=Path, default=BASE / "timing")
    parser.add_argument("--baseline", type=Path, default=BASE / "baseline-latency-summary.json")
    parser.add_argument("--source-bundle", type=Path, default=DEFAULT_ROOT / "generic-string-acb06574-source.bundle")
    archive(parser.parse_args())
