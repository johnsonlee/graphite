#!/usr/bin/env python3
"""Check real CLI/JVM default and override builds against a persisted reference.

The Java wrapper only records invocations and then execs the actual JVM unchanged.
This is correctness/launcher coverage, not a performance benchmark.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys


PROFILE = (
    "-Xmx8g -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions "
    "-XX:G1MaxNewSizePercent=30 -XX:MinHeapFreeRatio=20 -XX:GCTimeRatio=4"
)
OPTIONS = ("JAVA_TOOL_OPTIONS", "JAVA_OPTS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", type=Path, required=True)
    parser.add_argument("--frontend", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--include", default="com.acme")
    parser.add_argument("--android-sdk", type=Path)
    args = parser.parse_args()
    java = shutil.which("java")
    if java is None:
        parser.error("Java must be installed")
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    wrapper = root / "java-observer"
    wrapper.write_text(
        f"#!{sys.executable}\n"
        "import json, os, sys\n"
        "with open(os.environ['BUILD_JVM_LOG'], 'a') as log:\n"
        "    log.write(json.dumps({'args': sys.argv[1:], "
        "'toolOptions': os.environ.get('JAVA_TOOL_OPTIONS')}) + '\\n')\n"
        "os.execv(os.environ['BUILD_REAL_JAVA'], "
        "[os.environ['BUILD_REAL_JAVA'], *sys.argv[1:]])\n"
    )
    wrapper.chmod(0o700)
    env = {key: value for key, value in os.environ.items() if key not in OPTIONS}
    env.update(
        GRAPHITE_JAVA=str(wrapper),
        GRAPHITE_FRONTEND_JVM=str(args.frontend.resolve()),
        BUILD_REAL_JAVA=java,
    )
    for name, override in (("default", None), ("explicit-small-heap", "-Xmx2g")):
        case_env = env.copy()
        log = root / f"{name}.jsonl"
        case_env["BUILD_JVM_LOG"] = str(log)
        if override is not None:
            case_env["JAVA_OPTS"] = override
        graph = root / name
        build_args = ["build", str(args.input.resolve()), "-o", str(graph), "--include", args.include]
        if args.android_sdk is not None:
            build_args.extend(["--android-sdk", str(args.android_sdk.resolve())])
        with (root / f"{name}.stdout").open("wb") as stdout, (
            root / f"{name}.stderr"
        ).open("wb") as stderr:
            child = subprocess.Popen(
                [str(args.cli.resolve()), *build_args], env=case_env,
                stdout=stdout, stderr=stderr, start_new_session=True,
            )
            try:
                code = child.wait(timeout=120)
            except subprocess.TimeoutExpired:
                # The CLI waits for Java; killing only the CLI would orphan it.
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                child.wait()
                raise
            if code:
                raise subprocess.CalledProcessError(code, child.args)
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        expected = []
        if override is None:
            expected.append({
                "args": [*PROFILE.split(), "-XX:+PrintFlagsFinal", "-version"],
                "toolOptions": None,
            })
        expected.append({
            "args": ["-jar", str(args.frontend.resolve()), *build_args],
            "toolOptions": override or PROFILE,
        })
        if calls != expected:
            raise AssertionError(f"{name}: unexpected Java invocations: {calls!r}")
        # The established CLI/direct-JVM comparison ignores properties timestamps.
        subprocess.run(
            ["diff", "-r", "-I", "^#", str(args.reference.resolve()), str(graph)],
            check=True,
        )
        print(f"PASS {name}: exact JVM invocations and persisted graph match")


if __name__ == "__main__":
    main()
