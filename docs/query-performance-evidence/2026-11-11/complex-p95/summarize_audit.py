#!/usr/bin/env python3
"""Render independent local-query audit JSON without inventing missing comparisons."""
import argparse
import json
from pathlib import Path


def fmt(value, unit=1):
    return "UNAVAILABLE" if value is None else f"{value / unit:,.3f}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audit", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(args.audit.read_text())
    comparison = data.get("comparison", {})
    lines = ["# Independent complex-query evidence audit", "",
             "Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.", ""]
    if not comparison.get("comparable", False):
        lines.extend(["**No matched P95/CPU/RSS comparison is available.** " + "; ".join(comparison.get("unavailableReasons", ["No paired run supplied."])), "",
                      "An incomplete or failed baseline cannot supply a baseline P95 or mixed pressure CPU. Process lifetime resource observations from different completed work are not comparable.", ""])
    for label in ("baseline", "candidate"):
        if label not in data:
            continue
        arm = data[label]
        lines += [f"## {label}", "", f"Audit valid: `{arm['auditValid']}`; workload complete: `{arm['workloadComplete']}`; issued requests: {arm.get('issuedRequests', 0)}.", ""]
        if arm.get("controllerErrors"):
            lines += ["Retained errors:", ""] + [f"- {error}" for error in arm["controllerErrors"]] + [""]
        lines += ["| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |",
                  "| --- | ---: | ---: | ---: | ---: | ---: |"]
        for row in arm["cases"]:
            if row["stage"] != "pressure":
                continue
            lines.append(f"| {row['case']} | {row['issued']} / {row['expected']} | {row['failedCount']} | {fmt(row['p50Ms'])} | {fmt(row['p95Ms'])} | {fmt(row['minMs'])}–{fmt(row['maxMs'])} |")
        resources = arm.get("resources", {})
        loading = (resources.get("loading") or {}).get("lifetime", {})
        pressure = resources.get("pressure") or {}
        query = resources.get("queryLifecycle") or {}
        pressure_cpu = pressure.get("cpuSeconds") if resources.get("cpuUnitVerified") else None
        lines += ["", "| Resource observation | Value |", "| --- | ---: |",
                  f"| Load-only CPU user+system, seconds (wait4) | {fmt(loading.get('cpuSeconds'))} |",
                  f"| Load-only kernel peak RSS, MiB | {fmt(loading.get('kernelPeakRssBytes'), 1048576)} |",
                  f"| Complete mixed pressure CPU user+system, seconds | {fmt(pressure_cpu)} |",
                  f"| Query-process lifetime CPU, seconds (wait4) | {fmt(query.get('cpuSeconds'))} |",
                  f"| Query-process lifetime kernel peak RSS, MiB | {fmt(query.get('kernelPeakRssBytes'), 1048576)} |", "",
                  "Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.", ""]
        timebase = resources.get("timebaseAudit", {})
        corrected = any(s.get("status") == "CORRECTED_LEGACY_DERIVATION" for s in timebase.get("snapshots", []))
        if corrected:
            lines += ["Darwin CPU snapshots above are **derived corrections**, reconstructed from original tick/1e9 fields using independently verified same-host Mach timebase 125/3. Raw reports, request times, wait4 CPU and kernel RSS were not changed. Full reconstruction and wait4 corroboration are retained in the audit JSON.", ""]
    if comparison.get("comparable"):
        lines += ["## Matched observations", "", "| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |", "| --- | ---: | ---: | ---: | ---: |"]
        for row in comparison["cases"]:
            value = row["p95Ms"]
            lines.append(f"| {row['case']} | {fmt(value['base'])} | {fmt(value['candidate'])} | {fmt(value['delta'])} | {fmt(value['deltaPercent'])} |")
        lines += ["", "The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.", ""]
    args.output.write_text("\n".join(lines))


if __name__ == "__main__":
    main()
