#!/usr/bin/env python3
"""Verify both shared-source contexts in the candidate compiler model, IR and saved graph."""
import argparse
from collections import Counter
import csv
import importlib.util
import json
from pathlib import Path
import sys
import traceback

HERE = Path(__file__).resolve().parent


def module(filename):
    spec = importlib.util.spec_from_file_location(filename, HERE / filename)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


diag = module("diagnose-apple-index.py")
oracle = module("apple-index-oracle.py")
SHARED = {
    "Sources/PackageLoading/ContextModel.swift": ("PackageDescription", "PackageLoading"),
    "Sources/PackageDescription/PackageDescriptionSerialization.swift": ("PackageDescription", "PackageLoading"),
    "Sources/PackagePlugin/PluginMessages.swift": ("PackagePlugin", "SPMBuildCore"),
}


def read(path):
    return json.loads(path.read_text())


def check_model(expected, model, shared_models, directory):
    differences = {}
    def compare_usrs(name, wanted, actual):
        a, b = set(wanted), set(actual)
        if a != b:
            differences[name] = {"missingFromModel": sorted(a - b), "extraInModel": sorted(b - a)}
    compare_usrs("allSelectedFiles", expected["usrs"], model["usrs"])
    for file, modules in SHARED.items():
        contexts = expected["files"][file]["contexts"]
        if set(contexts) != set(modules):
            differences[file + ":compilerModules"] = sorted(contexts)
        compare_usrs(file, expected["sharedUSRs"][file], shared_models[file]["usrs"])
        wanted_types = {tuple(row) for row in expected["sharedTypes"][file]}
        actual_types = {tuple(row[:2]) for row in shared_models[file]["types"]}
        wanted_calls = {tuple(row) for row in expected["sharedCalls"][file]}
        actual_calls = {tuple(row[:3] + row[4:]) for row in shared_models[file]["calls"]}
        for label, wanted, actual in [("typeDefinitions", wanted_types, actual_types), ("calls", wanted_calls, actual_calls)]:
            if wanted != actual:
                differences[file + ":" + label] = {"missing": sorted(wanted - actual), "extra": sorted(actual - wanted)}
        # Check declarations in addition to symbol presence (a reference could
        # otherwise cause a missing definition's USR to survive in the model).
        for kind in ("types", "members"):
            owners = {row[0] for row in shared_models[file][kind]}
            if owners != set(modules):
                differences[file + ":" + kind] = sorted(owners)
    diag.save(directory / "oracle-model-differences.json", differences)
    if differences:
        raise ValueError("production model disagrees with the independent record union")


def field_queries():
    for owner, member, name in [
        ("Serialization.BuildConfiguration", "config", "serialization"),
        ("HostToPluginMessage.InputContext.URL", "subpath", "plugin"),
    ]:
        modules = ("PackageDescription", "PackageLoading") if name == "serialization" else ("PackagePlugin", "SPMBuildCore")
        for prefix in modules:
            yield f"{name}-{prefix}", f"MATCH (f:Field) WHERE f.class = '{prefix}.{owner}' AND f.name = '{member}' RETURN count(f) AS facts"
    for prefix in ("PackageDescription", "PackageLoading"):
        yield "context-" + prefix, (
            f"MATCH (c:CallSite) WHERE c.caller_class = '{prefix}.ContextModel' AND c.caller_name = 'encode()' "
            "AND c.callee_class = 'FoundationEssentials.JSONEncoder' AND c.callee_name = 'init()' RETURN count(c) AS facts"
        )


def query_owners(jar, output):
    counts = {}
    for name, query in field_queries():
        result = output / (name + ".csv")
        diag.run(["java", "-Xmx2g", "-jar", jar, "query", output / "saved.graphite", query, "-f", "csv"],
                 output, name, stdout=result)
        with result.open() as stream:
            rows = list(csv.reader(stream))
        if rows != [["facts"], ["1"]]:
            raise ValueError(f"{name} must preserve exactly one compiler-backed graph fact, got {rows}")
        counts[name] = 1
    return counts


def checks(args, root, out):
    if root.exists():
        raise ValueError(f"fresh checkout required: {root}")
    diag.prepare.prepare(args.manifest, root, None, out / "prepare.log")
    lock = root / "Package.resolved"
    revisions = {p.name.lower(): diag.git(p, "rev-parse", "HEAD") for p in (root / ".build/checkouts").iterdir()}
    expected_revisions = {p["identity"]: p["state"]["revision"] for p in read(lock)["pins"]}
    source = {"root": str(root), "commit": diag.git(root, "rev-parse", "HEAD"), "tree": diag.git(root, "rev-parse", "HEAD^{tree}"),
              "dependencyRevisions": revisions, "lockSha256": diag.digest(lock)}
    diag.save(out / "source.json", source)
    if revisions != expected_revisions:
        raise ValueError("dependency checkouts differ from the fixed lock")
    diag.run(["swift", "package", "--package-path", root, "describe", "--type", "json"], out, "describe", stdout=out / "describe.json")
    diag.run(["swift", "package", "--package-path", root, "dump-package"], out, "dump-package", stdout=out / "dump-package.json")
    diag.run([args.model, root, out, *SHARED], out, "model-dump")
    selected = read(out / "selected-paths.json")
    described_swift = {(root / t["path"] / name).resolve() for t in read(out / "describe.json")["targets"]
                       if t["type"] in {"library", "executable", "macro"} for name in t["sources"] if name.endswith(".swift")}
    if described_swift != {Path(p).resolve() for p in selected if p.endswith(".swift")}:
        raise ValueError("selected Swift files differ from the directly described target sources")
    library = Path((out / "index-library.txt").read_text())
    result = oracle.audit(root, selected, library, out, SHARED)
    diag.save(out / "oracle.json", result)
    # Always attempt production import and queries if the compiler/model
    # comparison fails. The workflow uploads both independent results.
    try:
        check_model(result, read(out / "model.json"), read(out / "shared-models.json"), out)
    finally:
        shape, methods = diag.emit_and_import(args, root, out)
        # The historical helper uses baseline.graphite-ir as its working path.
        # Archive the emitted bytes under their actual candidate identity.
        ir = (out / "baseline.graphite-ir").rename(out / "candidate.graphite-ir")
        counts = query_owners(args.jar, out)
        diag.save(out / "observation.json", {"shape": shape, "methods": methods, "ownerQueries": counts,
                  "librarySha256": diag.digest(library), "irSha256": diag.digest(ir)})
    counts = diag.progress_counts(out / "frontend.log")
    if counts != {"index": len(selected), "demangle": len(result["usrs"])}:
        raise ValueError("pristine frontend and independent oracle disagree on files or distinct USRs")


def cross_check(left, right, out):
    differences = {}
    for name in ("model", "shared-models", "oracle", "observation", "source"):
        a, b = (read(directory / (name + ".json")) for directory in (left, right))
        if name == "observation":
            # The wire header embeds the physical checkout path.
            a.pop("irSha256")
            b.pop("irSha256")
        if name == "source":
            a.pop("root")
            b.pop("root")
        for key in a.keys() | b.keys():
            if a.get(key) != b.get(key):
                differences.setdefault(name, []).append(key)
    # String table IDs can differ between runs. Preserve exact spellings and
    # multiplicity, and retain both complete streams for entity-level review.
    a, b = (Counter(read(directory / "ir-strings.json")) for directory in (left, right))
    if a != b:
        differences["strings"] = {"tempOnly": dict(a - b), "workspaceOnly": dict(b - a)}
    diag.save(out / "root-differences.json", differences)
    if differences:
        raise ValueError("normalized graph or compiler facts differ across checkout roots")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("manifest", "frontend", "model", "jar", "out", "temp-root", "workspace-root"):
        parser.add_argument("--" + name, type=lambda name: Path(name).resolve(), required=True)
    args = parser.parse_args()
    manifest = diag.prepare.manifest_at(args.manifest)
    if (manifest["commit"], manifest["dependencyLock"]["sha256"]) != (diag.SOURCE, diag.LOCK):
        raise ValueError("unexpected SwiftPM corpus revision or dependency lock")
    identity = {key: diag.digest(getattr(args, key)) for key in ("manifest", "frontend", "model", "jar")}
    diag.save(args.out / "inputs.json", identity)
    failures = []
    for label, root in (("temp", args.temp_root), ("workspace", args.workspace_root)):
        out = args.out / label
        out.mkdir()
        try:
            checks(args, root, out)
        except Exception:
            (out / "failure.log").write_text(traceback.format_exc())
            traceback.print_exc()
            failures.append(label)
    if identity != {key: diag.digest(getattr(args, key)) for key in identity}:
        raise ValueError("diagnostic inputs changed during the run")
    if failures:
        raise ValueError("failed roots: " + ", ".join(failures))
    cross_check(args.out / "temp", args.out / "workspace", args.out)


if __name__ == "__main__":
    main()
