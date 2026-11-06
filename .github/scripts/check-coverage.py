#!/usr/bin/env python3
"""Enforce the seven JVM modules' 98% line coverage from Kover XML, never console logs."""

import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

MODULES = ("core", "cypher", "explore", "ir", "query", "sootup", "webgraph")
THRESHOLD = 98
REPORT_PREFIX = "Kover Gradle Plugin XML report for :"


class CoverageError(ValueError):
    pass


def read_report(path: Path) -> dict:
    try:
        report = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        raise CoverageError(f"Cannot read Kover XML {path}: {error}") from error
    name = report.get("name", "")
    if report.tag != "report" or not name.startswith(REPORT_PREFIX):
        raise CoverageError(f"Unrecognized Kover report identity in {path}")
    module = name.removeprefix(REPORT_PREFIX)
    if module not in MODULES:
        raise CoverageError(f"Unknown coverage module {module!r} in {path}")
    # Only the report-wide aggregate counts. Package/class/method counters overlap.
    counters = [counter for counter in report.findall("counter") if counter.get("type") == "LINE"]
    if len(counters) != 1:
        raise CoverageError(f"{module}: expected exactly one report-wide LINE counter")
    counts = {}
    for field in ("covered", "missed"):
        value = counters[0].get(field, "")
        if not re.fullmatch(r"[0-9]+", value):
            raise CoverageError(f"{module}: invalid LINE {field} count {value!r}")
        counts[field] = int(value)
    total = counts["covered"] + counts["missed"]
    if total == 0:
        raise CoverageError(f"{module}: LINE counter has zero total lines")
    return {
        "module": module,
        **counts,
        "total": total,
        "percent": counts["covered"] * 100 / total,
        "passed": counts["covered"] * 100 >= THRESHOLD * total,
    }


def read_reports(paths: list[Path]) -> list[dict]:
    modules = {}
    for path in paths:
        row = read_report(path)
        module = row["module"]
        if module in modules:
            raise CoverageError(f"Duplicate coverage module {module!r}")
        modules[module] = row
        # A copied or misplaced report cannot stand in for another module's evidence.
        if path.parent.parent.parent.parent.name != module:
            raise CoverageError(f"{module}: report is in the wrong module directory: {path}")
    missing = set(MODULES) - modules.keys()
    if missing:
        raise CoverageError(f"Missing coverage modules: {', '.join(sorted(missing))}")
    return [modules[module] for module in MODULES]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--summary", type=Path, help="Write validated module coverage for the PR comment")
    args = parser.parse_args()
    if args.summary:
        args.summary.unlink(missing_ok=True)
    try:
        paths = sorted((args.root / "frontend/jvm").glob("*/build/reports/kover/report.xml"))
        rows = read_reports(paths)
    except CoverageError as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1
    if args.summary:
        args.summary.write_text(json.dumps({"threshold": THRESHOLD, "modules": rows}, indent=2) + "\n")
    for row in rows:
        message = f"{row['module']}: {row['covered']}/{row['total']} lines ({row['percent']:.4f}%), required {THRESHOLD}%"
        print(message if row["passed"] else f"::error::{message}")
    return 0 if all(row["passed"] for row in rows) else 1


if __name__ == "__main__":
    sys.exit(main())
