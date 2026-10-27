#!/usr/bin/env python3
"""Source-pinned OSS correctness checks for the TypeScript build/save/query path.

Does not install dependencies or run scripts from the analyzed repositories.
Every command and its complete output is preserved under --output, including failures.
This is correctness evidence, not a performance benchmark.
"""

import argparse
import hashlib
import io
import json
import os
import shutil
from pathlib import Path
import subprocess
import tarfile
import urllib.request


PROJECTS = (
    {
        "name": "mitt",
        "repo": "developit/mitt",
        "revision": "b240473b5707857ba2c6a8e6d707c28d1e39da49",  # 3.0.1
        "sha256": "5bfb7ae1c8674759d31684141a37e64914ac024b441dccda76775fc236039ced",
        "input": "src",
        "calls": [("on", "get", [67]), ("on", "set", [71]), ("off", "splice", [86]), ("off", "indexOf", [86]), ("emit", "get", [104, 113])],
        "literal": "*",
    },
    {
        "name": "zod",
        "repo": "colinhacks/zod",
        "revision": "e30870369d5b8f31ff4d0130d4439fd997deb523",  # v3.24.2
        "sha256": "34d6943168210fba885b7206d47e7fc032d595e21a26ea56c8b7e8df9fa53465",
        "input": "tsconfig.json",
        "calls": [("addIssueToContext", "getErrorMap", [76]), ("addIssueToContext", "makeIssue", [77]), ("mergeObjectAsync", "mergeObjectSync", [131]), ("mergeArray", "dirty", [111])],
        "literal": "aborted",
    },
)


class Harness:
    def __init__(self, cli, output):
        self.cli = str(cli.resolve())
        self.output = output.resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        self.commands = []
        self.results = []

    def run(self, *args):
        command = [self.cli, *map(str, args)]
        index = len(self.commands)
        result = subprocess.run(command, capture_output=True, text=True, timeout=300)
        prefix = self.output / f"command-{index:03d}"
        prefix.with_suffix(".stdout").write_text(result.stdout)
        prefix.with_suffix(".stderr").write_text(result.stderr)
        self.commands.append({"command": command, "exitCode": result.returncode,
                              "stdout": str(prefix.with_suffix(".stdout")),
                              "stderr": str(prefix.with_suffix(".stderr"))})
        (self.output / "commands.json").write_text(json.dumps(self.commands, indent=2) + "\n")
        if result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}): {command}\n{result.stderr}")
        return result.stdout

    def query(self, graph, query):
        result = json.loads(self.run("query", graph, query, "--format", "json"))
        assert result["rowCount"] == len(result["rows"]), result
        return result["rows"]

    def download(self, project):
        name, revision = project["name"], project["revision"]
        archive = self.output / f"{name}-{revision}.tar.gz"
        if not archive.exists():
            url = f"https://codeload.github.com/{project['repo']}/tar.gz/{revision}"
            with urllib.request.urlopen(url, timeout=60) as response:
                archive.write_bytes(response.read())
        data = archive.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        assert digest == project["sha256"], f"{name}: archive hash mismatch: {digest}"
        source = self.output / "sources"
        source.mkdir(exist_ok=True)
        root = source / f"{name}-{revision}"
        # Never trust a cached extracted tree: re-extract the verified source archive.
        if root.exists():
            shutil.rmtree(root)
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as contents:
            for member in contents.getmembers():
                destination = (source / member.name).resolve()
                if not destination.is_relative_to(root) or not (member.isfile() or member.isdir()):
                    raise RuntimeError(f"Unexpected archive member: {member.name}")
            contents.extractall(source, filter="data")
        return root / project["input"]

    def check_project(self, project):
        name = project["name"]
        print(f"Checking {name} at {project['revision']}", flush=True)
        source = self.download(project)
        graph = self.output / f"{name}-graph"
        packed = self.output / f"{name}.graphite"
        unpacked = self.output / f"{name}-unpacked"
        direct = self.output / f"{name}-direct.graphite"
        self.run("build", "--lang", "ts", source, "-o", graph)
        checks = []
        for caller, callee, lines in project["calls"]:
            query = (f"MATCH (cs:CallSite {{caller_name: '{caller}', callee_name: '{callee}'}}) "
                     "RETURN cs.caller_class AS caller_class, cs.caller_name AS caller, "
                     "cs.callee_class AS callee_class, cs.callee_name AS callee, "
                     "cs.line AS line "
                     "ORDER BY caller_class, caller, callee_class, callee, line")
            rows = self.query(graph, query)
            assert rows, f"{name}: missing concrete call {caller} -> {callee}"
            assert all(row["caller"] == caller and row["callee"] == callee and
                       row["line"] > 0 for row in rows), rows
            assert sorted(row["line"] for row in rows) == lines, rows
            if name == "mitt":
                assert all(row["caller_class"] == "index.ts#object@54:9#mitt@46:1" for row in rows), rows
            if name == "zod":
                expected_owner = "src/helpers/parseUtil.ts" + (".ParseStatus" if caller.startswith("merge") else "")
                assert any(row["caller_class"] == expected_owner for row in rows), rows
                if callee == "getErrorMap":
                    assert any(row["callee_class"] == "src/errors.ts" for row in rows), rows
            checks.append({"query": query, "rows": rows})
        query = f"MATCH (s:StringConstant {{value: '{project['literal']}'}}) RETURN s.value AS value"
        rows = self.query(graph, query)
        assert rows and all(row == {"value": project["literal"]} for row in rows), rows
        checks.append({"query": query, "rows": rows})
        if name == "mitt":
            query = ("MATCH (s:StringConstant {value: '*'})-->(cs:CallSite {caller_name: 'emit', callee_name: 'get'}) "
                     "RETURN s.value AS value, cs.caller_name AS caller, cs.callee_name AS callee")
            rows = self.query(graph, query)
            assert rows == [{"value": "*", "caller": "emit", "callee": "get"}], rows
            checks.append({"query": query, "rows": rows})
        # Complete nodes and edges, not sampled rows or counts: loss during save/load fails.
        for query in ("MATCH (n) RETURN n ORDER BY n.id", "MATCH (a)-->(b) RETURN a.id AS source, b.id AS target ORDER BY source, target"):
            rows = self.query(graph, query)
            assert rows, f"{name}: empty saved graph: {query}"
            checks.append({"query": query, "rows": rows})
        self.run("pack", graph, "-o", packed)
        self.run("verify", "--verbose", packed)
        self.run("unpack", packed, unpacked)
        self.run("build", "--lang", "ts", source, "-o", direct)
        self.run("verify", "--verbose", direct)
        for restored in (packed, unpacked, direct):
            for check in checks:
                actual = self.query(restored, check["query"])
                assert actual == check["rows"], f"{name}: roundtrip differs at {restored}: {check['query']}"
        record = {**project, "passed": True, "checks": checks}
        (self.output / f"{name}-results.json").write_text(json.dumps(record, indent=2) + "\n")
        self.results.append({"project": name, "revision": project["revision"], "passed": True,
                             "checks": len(checks), "nodes": len(checks[-2]["rows"]),
                             "edges": len(checks[-1]["rows"]), "forms": ["directory", "packed", "unpacked", "direct-container"]})
        print(f"PASS {name}: {len(checks)} checks across four saved graph forms", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", type=Path, default=Path("target/release/graphite"))
    parser.add_argument("--frontend", type=Path, default=Path("frontend/web/dist/cli.js"))
    parser.add_argument("--output", required=True, type=Path, help="Evidence and downloaded source directory")
    args = parser.parse_args()
    os.environ["GRAPHITE_FRONTEND_TS"] = str(args.frontend.resolve())
    harness = Harness(args.cli, args.output)
    error = None
    try:
        for project in PROJECTS:
            harness.check_project(project)
    except Exception as failure:
        error = f"{type(failure).__name__}: {failure}"
        raise
    finally:
        (harness.output / "summary.json").write_text(json.dumps({"passed": error is None, "error": error, "projects": harness.results,
            "scope": "Correctness only; no performance claim. Target project dependencies and scripts are not executed."}, indent=2) + "\n")


if __name__ == "__main__":
    main()
