#!/usr/bin/env python3
"""Run the same external diagnostic adapter against a selected, immutable JAR.

Each fork is a fresh JVM and retains all catalog observations. This supplements
the existing regression gates; its results never waive or replace those gates.
"""

import argparse
import csv
import hashlib
import json
import math
import pathlib
import re
import shutil
import subprocess
import sys

from verify_run import validate_catalog, verify_run

ROOT = pathlib.Path(__file__).resolve().parent
SCHEMA = "graphite-multi-keyword-profile-v2"


def sha256(path):
    with pathlib.Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def graph_identity(manifest):
    """Hash persisted data, not just the TSV paths pointing to it."""
    entries = []
    paths = set()
    for line in manifest.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 6:
            raise ValueError("Malformed fixture manifest")
        root = pathlib.Path(fields[1]).resolve(strict=True)
        if not root.is_dir() or root in paths:
            raise ValueError("Graph paths must be distinct directories")
        paths.add(root)
        files = []
        for path in sorted(root.rglob("*")):
            if path.is_symlink():
                raise ValueError("Persisted graph cannot contain a symlink")
            if path.is_file():
                files.append({"path": str(path.relative_to(root)), "size": path.stat().st_size,
                              "sha256": sha256(path)})
        if not files:
            raise ValueError("Empty persisted graph")
        entries.append({"id": fields[0], "files": files})
    if len(entries) != 64 or len({entry["id"] for entry in entries}) != 64:
        raise ValueError("Expected 64 unique persisted graph identities")
    return entries


def summarize(observations, expected_count=24):
    """Keep percentiles within a fixed query; never pool heterogeneous queries."""
    if not observations:
        raise ValueError("No completed, verified forks")
    ids = [row["id"] for row in observations[0]]
    if expected_count not in (24, 26, 36) or len(ids) != expected_count or len(set(ids)) != expected_count:
        raise ValueError(f"Exactly {expected_count} distinct query IDs are required")
    if any([row["id"] for row in fork] != ids for fork in observations):
        raise ValueError("Incomplete or reordered fork")
    result = []
    for index, query_id in enumerate(ids):
        samples = [int(fork[index]["latencyNanos"]) for fork in observations]
        if any(value <= 0 for value in samples):
            raise ValueError("Query latencies must be positive")
        ordered = sorted(samples)
        result.append({
            "id": query_id,
            "sampleCount": len(samples),
            "latencyNanosInForkOrder": samples,
            "minLatencyNanos": ordered[0],
            "maxLatencyNanos": ordered[-1],
            "medianLatencyNanos": (ordered[(len(ordered) - 1) // 2] + ordered[len(ordered) // 2]) / 2,
            # 20 is a reporting floor, not a confidence or stability guarantee.
            "empiricalP95LatencyNanos": ordered[math.ceil(0.95 * len(ordered)) - 1]
            if len(ordered) >= 20 else None,
        })
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=pathlib.Path, required=True)
    parser.add_argument("--trusted-jar", type=pathlib.Path, required=True,
                        help="Frozen-main JAR used to compile the adapter")
    parser.add_argument("--jar", type=pathlib.Path, required=True,
                        help="Runtime JAR to measure (may be the trusted JAR)")
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    parser.add_argument("--catalog-dir", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--forks", type=int, default=1)
    args = parser.parse_args()
    if args.forks < 1:
        parser.error("--forks must be positive")
    for name in ("java", "trusted_jar", "jar", "manifest", "catalog_dir", "output"):
        setattr(args, name, getattr(args, name).resolve())
    if args.output.exists():
        parser.error("Output already exists; use a new directory to preserve evidence")
    catalog_path = args.catalog_dir / "catalog.json"
    workload_path = args.catalog_dir / "workloads.tsv"
    catalog = json.loads(catalog_path.read_text())
    queries, _ = validate_catalog(catalog)
    query_count = len(queries)
    if catalog["manifestSha256"] != sha256(args.manifest):
        parser.error("Manifest differs from the independent reference")
    if catalog["jarSha256"] != sha256(args.trusted_jar):
        parser.error("Trusted JAR differs from the independent reference")
    inputs = {
        "trustedJar": args.trusted_jar,
        "runtimeJar": args.jar,
        "manifest": args.manifest,
        "catalog": catalog_path,
        "workloads": workload_path,
        "adapterSource": ROOT / "MultiKeywordProfileRunner.java",
        "runnerSource": pathlib.Path(__file__).resolve(),
        "verifierSource": ROOT / "verify_run.py",
    }
    identities = {name: {"path": str(path), "sha256": sha256(path)}
                  for name, path in inputs.items()}
    args.output.mkdir(parents=True)
    classes = args.output / "classes"
    classes.mkdir()
    shutil.copy2(catalog_path, args.output / "catalog.json")
    shutil.copy2(workload_path, args.output / "workloads.tsv")
    java_version = subprocess.run([str(args.java), "-version"], check=True,
                                  capture_output=True, text=True)
    version_text = java_version.stdout + java_version.stderr
    if not re.search(r'\b(?:openjdk|java)(?: version)? "?17(?:[.\s"])', version_text):
        parser.error("This measurement contract requires Java 17")
    receipt = {
        "schema": SCHEMA,
        "catalogSchema": catalog.get("schema", "graphite-wide-query-oracle-v1"),
        "queryCount": query_count,
        "inputs": identities,
        "javaVersion": version_text,
        "requestedForks": args.forks,
        "completedVerifiedForks": 0,
        "resetMode": "per-query-cold",
        "measurement": "One observation per query per fresh JVM; fixed catalog order; "
                       "indexes cleared before every query; JIT and OS page cache are not reset",
        "percentile": "Per-query nearest-rank empirical P95, reported only at >=20 forks; "
                      "no confidence/stability guarantee and no pooled workload P95",
        "performanceGate": False,
        "status": "running",
    }
    write_json(args.output / "run.json", receipt)
    observations = []
    try:
        # Authentication is outside timed JVMs. It reads the fixture and therefore
        # does not establish a cold OS page cache, as the reset contract states.
        graph_files = graph_identity(args.manifest)
        write_json(args.output / "graph-content-before.json", graph_files)
        receipt["graphContentSha256"] = sha256(args.output / "graph-content-before.json")
        compile_command = [str(args.java.with_name("javac")), "-cp", str(args.trusted_jar),
                           "-d", str(classes), str(inputs["adapterSource"])]
        write_json(args.output / "compile-command.json", compile_command)
        with (args.output / "compile.log").open("w") as log:
            subprocess.run(compile_command, stdout=log, stderr=subprocess.STDOUT, check=True)
        receipt["compiledClasses"] = {
            str(path.relative_to(classes)): sha256(path)
            for path in sorted(classes.rglob("*.class"))
        }
        for fork in range(1, args.forks + 1):
            for name, path in inputs.items():
                if sha256(path) != identities[name]["sha256"]:
                    raise ValueError(f"Input changed during run: {name}")
            prefix = args.output / f"fork-{fork:03d}"
            command = [str(args.java), "-Xmx8g", "-XX:ActiveProcessorCount=4",
                       "-cp", str(classes) + ":" + str(args.jar),
                       "MultiKeywordProfileRunner", str(args.manifest),
                       str(workload_path), str(prefix), "all", "per-query-cold"]
            write_json(pathlib.Path(str(prefix) + "-command.json"), command)
            print(f"Starting fork {fork}/{args.forks}", flush=True)
            with pathlib.Path(str(prefix) + ".log").open("w") as log:
                subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                               check=True, timeout=query_count * 330 + 120)
            check = verify_run(catalog_path, workload_path, prefix)
            write_json(pathlib.Path(str(prefix) + "-reference-check.json"), check)
            if not check["passed"]:
                raise ValueError(f"Fork {fork} fails independent correctness: {check['errors']}")
            with pathlib.Path(str(prefix) + ".tsv").open() as stream:
                observations.append(list(csv.DictReader(stream, delimiter="\t")))
            receipt["completedVerifiedForks"] = len(observations)
            receipt["queries"] = summarize(observations, query_count)
            write_json(args.output / "run.json", receipt)
            print(f"Verified fork {fork}: all {query_count} queries", flush=True)
        for name, path in inputs.items():
            if sha256(path) != identities[name]["sha256"]:
                raise ValueError(f"Input changed during run: {name}")
        final_graph_files = graph_identity(args.manifest)
        write_json(args.output / "graph-content-after.json", final_graph_files)
        if final_graph_files != graph_files:
            raise ValueError("Persisted graph files changed during run")
        receipt["status"] = "complete"
    except BaseException as error:
        receipt["status"] = "failed"
        receipt["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        write_json(args.output / "run.json", receipt)


if __name__ == "__main__":
    main()
