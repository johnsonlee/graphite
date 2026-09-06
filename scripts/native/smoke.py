#!/usr/bin/env python3
"""Exercise installed JAR/distribution entry points without a binary override.
Uses the checked-in, JVM-written four-CallSite correctness fixture; no benchmark.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time
import urllib.request
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--build", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repo, build = args.repo.resolve(), args.build.resolve()
    results = []
    args.output.parent.mkdir(parents=True, exist_ok=True)
    resources = build / "graphite-explore/build/generated/native-resources/graphite-native"
    manifest = (resources / "SHA256SUMS").read_text()
    version = (resources / "VERSION").read_text().strip()
    env = {k: v for k, v in os.environ.items() if k not in (
        "GRAPHITE_SERVER_BINARY", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS",
        "GRAPHITE_NATIVE_CPU_PROFILE", "GRAPHITE_PROFILE")}
    fixture = repo / "graphite-server/internal/store/testdata/callsite-index/store"
    ui_bytes = (repo / "graphite-server/internal/web/assets/index.html").read_bytes()
    java = ["java", "-Dfile.encoding=UTF-8", "-Xmx128m", "-XX:ActiveProcessorCount=2"]
    commands = []
    for project, jar_name, script_name, prefix in [
        ("query", "graphite.jar", "graphite", ["serve"]),
        ("explore", "graphite-explore.jar", "explore", []),
    ]:
        jar = build / f"graphite-{project}/build/libs/{jar_name}"
        with zipfile.ZipFile(jar) as archive:
            for line in manifest.splitlines():
                digest, path = line.split("  ")
                assert hashlib.sha256(archive.read("graphite-native/" + path)).hexdigest() == digest
            assert archive.read("graphite-native/SHA256SUMS").decode() == manifest
            assert archive.read("graphite-native/VERSION").decode().strip() == version
        commands.append((project + "-jar", java + ["-jar", str(jar)] + prefix))
        # Gradle's application distribution name follows the project name.
        script = build / f"graphite-{project}/build/install/{script_name}/bin/{script_name}"
        commands.append((project + "-installDist", [str(script)] + prefix))
    try:
        for name, command in commands:
            with tempfile.TemporaryDirectory(prefix="graphite installed package ") as tmp:
                folder = Path(tmp)
                process_env = dict(env, JAVA_OPTS="-Xmx128m " + shlex.quote("-Djava.io.tmpdir=" + tmp))
                # JAVA_OPTS applies to generated scripts; JAR invocations get the
                # same explicit temp property so owned extraction is observable.
                invocation = list(command)
                if invocation[0] == "java": invocation.insert(1, "-Djava.io.tmpdir=" + tmp)
                invocation += ["--id", "tiny", str(fixture), "--port", "0"]
                error_path = folder / "stderr.txt"
                with error_path.open("w") as stderr, (folder / "stdout.txt").open("w") as stdout:
                    process = subprocess.Popen(invocation, cwd=folder, env=process_env,
                                               stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr)
                    try:
                        deadline = time.monotonic() + 30
                        while True:
                            text = error_path.read_text()
                            match = re.search(r"http://localhost:(\d+)", text)
                            if match: break
                            if process.poll() is not None or time.monotonic() >= deadline:
                                raise AssertionError((name, process.poll(), text))
                            time.sleep(.05)
                        extracted = list(folder.glob("graphite-native-*/graphite-server"))
                        assert len(extracted) == 1, (name, extracted)
                        assert extracted[0].stat().st_mode & 0o777 == 0o700
                        port = int(match[1])
                        with urllib.request.urlopen(f"http://localhost:{port}/api/graphs", timeout=10) as response:
                            catalog = json.load(response)
                        with urllib.request.urlopen(f"http://localhost:{port}/", timeout=10) as response:
                            homepage = response.read()
                        assert catalog["graphs"][0]["id"] == "tiny"
                        assert catalog["graphs"][0]["nodes"] == 4
                        assert homepage == ui_bytes
                        process.terminate()
                        process.wait(timeout=15)
                        assert process.returncode == 143, (name, process.returncode)
                        assert not extracted[0].exists() and not extracted[0].parent.exists()
                        results.append(dict(name=name, command=invocation, catalog=catalog,
                                            uiSHA256=hashlib.sha256(homepage).hexdigest(),
                                            exitCode=process.returncode, extractionRemoved=True))
                        args.output.write_text(json.dumps(dict(passed=False, checks=results), indent=2)+"\n")
                    finally:
                        if process.poll() is None: process.kill(); process.wait()
        args.output.write_text(json.dumps(dict(passed=True, version=version, checks=results), indent=2)+"\n")
        print(json.dumps(dict(passed=True, checks=len(results))))
    except BaseException as error:
        args.output.write_text(json.dumps(dict(passed=False, error=repr(error), checks=results), indent=2)+"\n")
        raise


if __name__ == "__main__":
    main()
