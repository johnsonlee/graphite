#!/usr/bin/env python3
"""Run the checks a change can affect, and only those, in parallel.

A full local round (every Cargo crate, every Gradle module with coverage, the
JavaScript frontend) takes 16-21 minutes, which is the wrong first feedback loop
for a change that touches one module. This script maps the files changed since a
base revision to the dependency closure that can observe them:

  backend/storage  -> graphite-storage and every crate that reads graphs
  backend/cypher   -> graphite-cypher, graphite-explore, graphite-cli, graphite-tck
  frontend/jvm/X   -> :X and every Gradle project that depends on :X
  frontend/web     -> npm test

and runs the Rust and JVM groups concurrently. It is a fast first pass, not the
acceptance gate: CI still runs everything, and `--all` runs every check here too.

Usage:
  python3 .github/scripts/verify-affected.py               # changes since origin/main
  python3 .github/scripts/verify-affected.py --base HEAD~3
  python3 .github/scripts/verify-affected.py --dry-run     # print the plan only
"""

import argparse
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# Who depends on whom (reverse edges), from each Cargo.toml and build.gradle.kts.
CARGO_DEPENDENTS = {
    "graphite-storage": ["graphite-cypher", "graphite-explore", "graphite-cli", "graphite-tck"],
    "graphite-cypher": ["graphite-explore", "graphite-cli", "graphite-tck"],
    "graphite-explore": ["graphite-cli"],
    "graphite-cli": [],
    "graphite-tck": [],
    "graphite-benchmark": [],
}
CARGO_DIRS = {
    "backend/storage/": "graphite-storage",
    "backend/cypher/": "graphite-cypher",
    "backend/explore/": "graphite-explore",
    "cli/": "graphite-cli",
    "tck/": "graphite-tck",
    "benchmark/src/": "graphite-benchmark",
}
GRADLE_DEPENDENTS = {
    "core": ["ir", "sootup", "cypher", "webgraph", "query", "explore"],
    "ir": ["query"],
    "sootup": ["webgraph", "query", "explore"],
    "cypher": ["webgraph", "query", "explore"],
    "webgraph": ["query", "explore"],
    "query": ["explore"],
    "explore": ["query"],
}
# Files every build reads: a change there needs everything.
EVERYTHING = ("Cargo.toml", "Cargo.lock", "build.gradle.kts", "settings.gradle.kts", "gradle/", "gradle.properties")


def closure(start, dependents):
    seen, todo = set(), list(start)
    while todo:
        item = todo.pop()
        if item not in seen:
            seen.add(item)
            todo.extend(dependents.get(item, []))
    return seen


def plan(paths, everything=False):
    crates, modules, web = set(), set(), False
    for path in paths:
        if everything or path.startswith(EVERYTHING):
            return closure(CARGO_DEPENDENTS, CARGO_DEPENDENTS), set(GRADLE_DEPENDENTS), True
        for prefix, crate in CARGO_DIRS.items():
            if path.startswith(prefix):
                crates.add(crate)
        if path.startswith("frontend/jvm/"):
            module = path.split("/")[2]
            if module in GRADLE_DEPENDENTS:
                modules.add(module)
        if path.startswith("frontend/web/"):
            web = True
        if path.startswith("benchmark/jvm/"):
            modules.add("webgraph")
    return closure(crates, CARGO_DEPENDENTS), closure(modules, GRADLE_DEPENDENTS), web


def commands(crates, modules, web):
    groups = []
    if crates:
        packages = [arg for crate in sorted(crates) for arg in ("-p", crate)]
        groups.append(("rust", [["cargo", "fmt", "--all", "--", "--check"], ["cargo", "test", "--locked", *packages]]))
    if modules:
        groups.append(("jvm", [["./gradlew", *[f":{m}:test" for m in sorted(modules)], "--continue"]]))
    if web:
        groups.append(("web", [["npm", "test", "--prefix", "frontend/web"]]))
    return groups


def run_group(name, steps):
    started = time.monotonic()
    for step in steps:
        result = subprocess.run(step, cwd=ROOT)
        if result.returncode != 0:
            return name, False, time.monotonic() - started, " ".join(step)
    return name, True, time.monotonic() - started, None


def changed_files(base):
    merge_base = subprocess.run(["git", "merge-base", base, "HEAD"], cwd=ROOT, capture_output=True, text=True)
    revision = merge_base.stdout.strip() if merge_base.returncode == 0 else base
    tracked = subprocess.run(["git", "diff", "--name-only", revision], cwd=ROOT, capture_output=True, text=True, check=True)
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, capture_output=True, text=True, check=True)
    return sorted(set(tracked.stdout.split()) | set(untracked.stdout.split()))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--all", action="store_true", help="run every check")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    paths = changed_files(args.base)
    crates, modules, web = plan(paths, args.all)
    groups = commands(crates, modules, web)
    print(f"{len(paths)} changed file(s); crates: {sorted(crates) or '-'}; gradle: {sorted(modules) or '-'}; web: {web}")
    for name, steps in groups:
        for step in steps:
            print(f"  [{name}] {' '.join(step)}")
    if args.dry_run or not groups:
        return 0
    with ThreadPoolExecutor(max_workers=len(groups)) as pool:
        results = list(pool.map(lambda group: run_group(*group), groups))
    failed = False
    for name, ok, seconds, step in results:
        print(f"{name}: {'ok' if ok else 'FAILED at ' + step} in {seconds:.0f}s")
        failed |= not ok
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
