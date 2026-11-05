#!/usr/bin/env python3
"""Compare compiler/index/IR correctness in the two historical SwiftPM checkout roots."""
import argparse
from collections import Counter
import csv
from functools import cache
import hashlib
import importlib.util
import itertools
import json
from pathlib import Path
import subprocess
import sys
import traceback

HERE = Path(__file__).resolve().parent
BASE = "0818c68ecd084e14e80a978f7940c6d9d03e1a9a"
FRONTEND_TREE = "1deb1cade3b2a13bef8af3cd6d9c57a42d6fece4"
SOURCE = "587a4fdaebc5d7f977ac6b0a01f0e0b644b50f54"
LOCK = "152f6bf2595de521b7293890504639652ff53f52084d9daea4e45a7660eb313b"


def load(filename):
    spec = importlib.util.spec_from_file_location(filename, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


prepare = load("prepare-apple-corpus.py")
wire = load("apple-frontend.py")  # Only framing, counts and summary parsing are used.


def save(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def digest(path):
    with path.open("rb") as stream:
        hasher = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            hasher.update(chunk)
        return hasher.hexdigest()


def git(path, *args):
    return subprocess.check_output(["git", "-C", path, *args], text=True).strip()


def run(args, directory, label, *, stdout=None):
    command = list(map(str, args))
    save(directory / (label + "-command.json"), command)
    with (directory / (label + ".log")).open("wb") as log:
        if stdout:
            with stdout.open("wb") as output:
                subprocess.run(command, stdout=output, stderr=log, check=True)
        else:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)


def source_summary(directory, root):
    selected = json.loads((directory / "selected-paths.json").read_text())
    root = root.resolve()

    @cache
    def relative_location(name):
        location = Path(name).resolve()
        return str(location.relative_to(root)) if location.is_relative_to(root) else name

    files = {str(Path(name).resolve().relative_to(root)): {"sha256": digest(Path(name)), "occurrences": 0}
             for name in selected}
    if len(files) != len(selected):
        raise ValueError("duplicate selected source paths")
    usrs = set()
    with (directory / "occurrences.jsonl").open() as stream:
        rows = (json.loads(line) for line in stream)
        for filename, group in itertools.groupby(rows, key=lambda row: row["file"]):
            relative = relative_location(filename)
            file = files[relative]
            if file["occurrences"]:
                raise ValueError("sidecar returned noncontiguous groups for one source")
            facts = []
            for row in group:
                # Strip only absolute location prefixes from comparison copies. Enum
                # properties, USRs and symbol spellings remain byte-for-byte intact.
                del row["file"]
                row["path"] = relative_location(row["path"])
                row["relations"].sort(key=lambda relation: json.dumps(relation, sort_keys=True))
                usrs.add(row["symbol"]["usr"])
                usrs.update(relation["symbol"]["usr"] for relation in row["relations"])
                facts.append(json.dumps(row, sort_keys=True, separators=(",", ":")))
            file.update(occurrences=len(facts), factsSha256=hashlib.sha256("\n".join(sorted(facts)).encode()).hexdigest())
    save(directory / "selected-files.json", files)
    save(directory / "index-usrs.json", sorted(usrs))
    return len(files), len(usrs)


def strings_in_ir(path):
    data = path.read_bytes()
    at = 0
    while at < len(data):
        length, at = wire.read_varint(data, at)
        chunk = data[at:at + length]
        at += length
        for number, kind, payload in wire.fields(chunk):
            if (number, kind) == (wire.CHUNK_STRINGS, 2):
                yield from (value.decode("utf8") for field, wire_type, value in wire.fields(payload)
                            if (field, wire_type) == (1, 2))


def progress_counts(log):
    phases = [json.loads(line) for line in log.read_text().splitlines() if line.startswith('{"phase"')]
    return {name: next(phase["total"] for phase in phases if phase["phase"] == name)
            for name in ("index", "demangle")}


def emit_and_import(args, root, directory):
    ir = directory / "baseline.graphite-ir"
    # These independent readers use separate temporary IndexStoreDB databases.
    # Keep the production result even if instrumentation or its checks fail.
    run([args.frontend, "build", "--package", root, "--skip-build", "--out", ir], directory, "frontend")
    shape = wire.parse_summary(directory / "frontend.log")
    wire.validate_ir(ir, shape)
    save(directory / "ir-shape.json", shape)
    save(directory / "ir-strings.json", list(strings_in_ir(ir)))
    graph = directory / "saved.graphite"
    java = ["java", "-Xmx2g", "-jar", args.jar]
    run([*java, "import", ir, "-o", graph], directory, "import")
    query = "MATCH (m:Method) RETURN count(m) AS methods"
    result = directory / "saved-graph-count.csv"
    run([*java, "query", graph, query, "-f", "csv"], directory, "query", stdout=result)
    with result.open() as stream:
        rows = list(csv.reader(stream))
    if len(rows) != 2 or rows[0] != ["methods"] or len(rows[1]) != 1 or int(rows[1][0]) <= 0:
        raise ValueError(f"saved graph method count did not verify: {rows}")
    return shape, int(rows[1][0])


def collect(args, root, directory):
    if root.exists():
        raise ValueError(f"expected a fresh checkout root: {root}")
    prepare.prepare(args.manifest, root, None, directory / "prepare.log")
    revisions = {p.name.lower(): git(p, "rev-parse", "HEAD") for p in (root / ".build/checkouts").iterdir()}
    lock = root / "Package.resolved"
    pins = {p["identity"]: p["state"]["revision"] for p in json.loads(lock.read_text())["pins"]}
    save(directory / "source.json", {"root": str(root), "commit": git(root, "rev-parse", "HEAD"),
         "tree": git(root, "rev-parse", "HEAD^{tree}"), "dependencyLockSha256": digest(lock),
         "dependencyCheckouts": revisions})
    if revisions != pins:
        raise ValueError("actual dependency checkout revisions differ from the frozen lock")
    try:
        shape, methods = emit_and_import(args, root, directory)
    finally:
        # A failed importer or emitter must not suppress the independent compiler
        # evidence. Python preserves both errors if the sidecar also fails.
        run([args.probe, root, root / ".build/debug/index/store", directory], directory, "dump")
        files, usrs = source_summary(directory, root)
    counts = progress_counts(directory / "frontend.log")
    save(directory / "observed.json", {"shape": shape, "irSha256": digest(directory / "baseline.graphite-ir"),
         "savedGraphMethods": methods, "librarySha256": digest(Path((directory / "index-library.txt").read_text())),
         "productionFiles": counts["index"], "productionUSRs": counts["demangle"],
         "dumpFiles": files, "dumpUSRs": usrs})
    if files != counts["index"] or files != shape["files"] or usrs != counts["demangle"]:
        raise ValueError("sidecar and baseline reported different selected-file or distinct-USR counts")


def compare(left, right):
    def pair(name):
        return tuple(json.loads((directory / name).read_text()) for directory in (left, right))
    result = {}
    a, b = pair("selected-files.json")
    result["changedFiles"] = {key: [a.get(key), b.get(key)] for key in sorted(a.keys() | b.keys())
                              if a.get(key) != b.get(key)}
    for filename, key in [("index-usrs.json", "usrs"), ("ir-strings.json", "irStrings")]:
        a, b = map(Counter, pair(filename))
        result[key] = {"onlyTemp": dict(sorted((a - b).items())), "onlyWorkspace": dict(sorted((b - a).items()))}
    result["observations"] = dict(zip(("temp", "workspace"), pair("observed.json")))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("baseline", "manifest", "frontend", "probe", "jar", "out", "temp-root", "workspace-root"):
        parser.add_argument("--" + name, type=lambda path: Path(path).resolve(), required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if args.temp_root == args.workspace_root:
        raise ValueError("the two fresh checkout roots must differ")
    base = git(args.baseline, "rev-parse", "HEAD")
    tree = git(args.baseline, "rev-parse", "HEAD:frontend/apple")
    manifest = prepare.manifest_at(args.manifest)
    if (base, tree, manifest["commit"], manifest["dependencyLock"]["sha256"]) != (BASE, FRONTEND_TREE, SOURCE, LOCK):
        raise ValueError("unexpected baseline or corpus provenance")
    identity = {name: digest(getattr(args, name)) for name in ("frontend", "probe", "jar", "manifest")}
    save(args.out / "identity.json", {"base": base, "tree": git(args.baseline, "rev-parse", "HEAD^{tree}"),
         "appleTree": tree, "sha256": identity})
    failed = False
    for label, root in (("temp", args.temp_root), ("workspace", args.workspace_root)):
        directory = args.out / label
        directory.mkdir()
        try:
            collect(args, root, directory)
        except Exception:
            (directory / "failure.log").write_text(traceback.format_exc())
            traceback.print_exc()
            failed = True
    if identity != {name: digest(getattr(args, name)) for name in identity}:
        raise ValueError("input controls changed during the diagnostic")
    if not failed:
        save(args.out / "differences.json", compare(args.out / "temp", args.out / "workspace"))
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
