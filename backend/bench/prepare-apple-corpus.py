#!/usr/bin/env python3
"""Prepare or verify a pinned real Apple source corpus; compilation is never timed here.

Both the regression gate and release calibration use this exact recipe. Performance
acceptance belongs to the comparator, not to a successful preparation or calibration.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys

# graphite-apple-real-source-v1: hash-pinned preparation control for the gate transition.


def checked(command, *, cwd=None, log=None):
    if log is None:
        return subprocess.run(command, cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()
    print("Preparing corpus: " + " ".join(map(str, command)), flush=True)
    with log.open("a") as stream:
        stream.write("\n$ " + " ".join(map(str, command)) + "\n")
        stream.flush()
        result = subprocess.run(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        tail = "\n".join(log.read_text(errors="replace").splitlines()[-40:])
        raise ValueError(f"corpus preparation failed (exit {result.returncode}); see {log}\n{tail}")


def relative_path(value):
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise ValueError(f"manifest path must stay inside its root: {value}")
    return path


def manifest_at(path):
    manifest = json.loads(path.read_text())
    if manifest.get("schema") != "graphite-apple-frontend-corpus-v2" or manifest.get("kind") != "real-source" or "generator" in manifest:
        raise ValueError("only a v2 real-source corpus can be prepared for performance measurement")
    if not str(manifest.get("repository", "")).startswith("https://") or not re.fullmatch(r"[0-9a-f]{40}", manifest.get("commit", "")):
        raise ValueError("corpus must pin an HTTPS repository and full commit SHA")
    if manifest.get("input") not in ("package", "xcodeproj"):
        raise ValueError("corpus input must be package or xcodeproj")
    for location, commit in manifest.get("submodules", {}).items():
        relative_path(location)
        if not re.fullmatch(r"[0-9a-f]{40}", commit):
            raise ValueError(f"submodule {location} needs a full commit SHA")
    if manifest["input"] == "xcodeproj":
        project = relative_path(manifest["project"])
        if project.suffix not in (".xcodeproj", ".xcworkspace") or not manifest.get("scheme"):
            raise ValueError("Xcode corpus must name a project/workspace and scheme")
    lock = manifest.get("dependencyLock")
    if lock:
        lock_path = path.parent / relative_path(lock["path"])
        if not lock_path.is_file() or hashlib.sha256(lock_path.read_bytes()).hexdigest() != lock["sha256"]:
            raise ValueError("the preparation lockfile does not match its manifest digest")
    return manifest


def verify_sources(manifest, root):
    def git(*args):
        return checked(["git", "-C", str(root), *args])
    if git("rev-parse", "HEAD") != manifest["commit"]:
        raise ValueError("corpus checkout differs from its commit pin")
    if git("status", "--porcelain", "--untracked-files=no", "--ignore-submodules=untracked"):
        raise ValueError("corpus checkout contains tracked changes")
    for location, commit in manifest.get("submodules", {}).items():
        if git("-C", location, "rev-parse", "HEAD") != commit:
            raise ValueError(f"submodule {location} differs from its commit pin")
        if git("-C", location, "status", "--porcelain", "--untracked-files=no"):
            raise ValueError(f"submodule {location} contains tracked changes")
    lock = manifest.get("dependencyLock")
    if lock:
        installed = root / "Package.resolved"
        if not installed.is_file() or hashlib.sha256(installed.read_bytes()).hexdigest() != lock["sha256"]:
            raise ValueError("corpus Package.resolved differs from its dependency pin")


def build_command(manifest, root, derived_data, jobs):
    if manifest["input"] == "package":
        command = ["swift", "build", "--package-path", str(root), "--jobs", str(jobs), "-c", "debug", "--enable-index-store"]
        if manifest.get("dependencyLock"):
            command.append("--force-resolved-versions")
        return command
    if derived_data is None:
        raise ValueError("the workspace corpus requires --derived-data")
    project = root / relative_path(manifest["project"])
    return ["xcodebuild", "-workspace" if project.suffix == ".xcworkspace" else "-project", str(project),
            "-scheme", manifest["scheme"], "-configuration", "Debug", "-derivedDataPath", str(derived_data),
            "-destination", "generic/platform=iOS Simulator", "-jobs", str(jobs), "build",
            "COMPILER_INDEX_STORE_ENABLE=YES", "CODE_SIGNING_ALLOWED=NO", "CODE_SIGNING_REQUIRED=NO", "CODE_SIGN_IDENTITY="]


def prepare(manifest_path, root, derived_data, log, jobs=4, verify_only=False):
    manifest = manifest_at(manifest_path)
    if jobs < 1:
        raise ValueError("jobs must be positive")
    command = build_command(manifest, root, derived_data, jobs)
    if not verify_only:
        log.parent.mkdir(parents=True, exist_ok=True)
        if not root.exists():
            root.parent.mkdir(parents=True, exist_ok=True)
            checked(["git", "init", "--quiet", str(root)], log=log)
            checked(["git", "-C", str(root), "remote", "add", "origin", manifest["repository"]], log=log)
            checked(["git", "-C", str(root), "fetch", "--quiet", "--depth", "1", "origin", manifest["commit"]], log=log)
            checked(["git", "-C", str(root), "checkout", "--quiet", manifest["commit"]], log=log)
        if manifest.get("submodules"):
            checked(["git", "-C", str(root), "submodule", "update", "--init", "--depth", "1", *manifest["submodules"]], log=log)
        if manifest.get("dependencyLock"):
            shutil.copyfile(manifest_path.parent / manifest["dependencyLock"]["path"], root / "Package.resolved")
        verify_sources(manifest, root)
        if manifest["label"] == "signal-ios":
            # This upstream script validates the downloaded RingRTC archive against its
            # checksum in the pinned Pods checkout before unpacking it.
            checked([str(root / "Pods/SignalRingRTC/bin/set-up-for-cocoapods")], cwd=root, log=log)
        versions = {"platform": platform.platform(), "machine": platform.machine(), "swift": checked(["swift", "--version"])}
        if manifest["input"] == "xcodeproj":
            versions["xcode"] = checked(["xcodebuild", "-version"])
        log.with_suffix(".toolchain.json").write_text(json.dumps(versions, indent=2) + "\n")
        checked(command, log=log)
    verify_sources(manifest, root)
    if verify_only:
        log.parent.mkdir(parents=True, exist_ok=True)
        versions = {"platform": platform.platform(), "machine": platform.machine(), "swift": checked(["swift", "--version"])}
        if manifest["input"] == "xcodeproj":
            versions["xcode"] = checked(["xcodebuild", "-version"])
        log.with_suffix(".toolchain.json").write_text(json.dumps(versions, indent=2) + "\n")
    store = root / ".build/debug/index/store" if manifest["input"] == "package" else derived_data / "Index.noindex/DataStore"
    if not store.is_dir():
        raise ValueError(f"the prepared compiler index store is missing: {store}")
    print(f"Prepared pinned real-source corpus {manifest['label']}: {store}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--derived-data", type=Path)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    try:
        prepare(args.corpus.resolve(), args.out.resolve(), args.derived_data.resolve() if args.derived_data else None,
                args.log.resolve(), args.jobs, args.verify_only)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
