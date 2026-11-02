#!/usr/bin/env python3
"""Build a pinned OSS Next/React app, then verify compiled-JS build/save/query.

The MIT CRUD app has React forms and file-backed Next API routes. The additional
public Taskboard fixture has a React client and SQLite server actions. Only .next JS
artifacts are graph inputs. Complete command output and artifact hashes are kept.
This is correctness evidence, never a performance benchmark.
"""

import argparse
import hashlib
import io
import json
import os
import platform
from pathlib import Path
import shutil
import socket
import time
import subprocess
import tarfile
import urllib.request


TASKBOARD = {
    "name": "async-react-demo",
    "repo": "nextjs/async-react-demo",
    "revision": "ec5b60f6362e956fea5a91fddfc0527c48d2a36f",
    "sha256": "ff523936418b1fceeb92018fbb693a2c7d11baf935c311f7ae68fd5b15c5a2c4",
    "packageManager": "pnpm@10.33.0",
    "configOverride": "experimental.cpus = 1 (serialize upstream SQLite initialization)",
}
CRUD = {
    "name": "next-js-10-crud-example",
    "repo": "cornflourblue/next-js-10-crud-example",
    "revision": "bf0046d35e80bbede589cd6f5435be16d70b6348",
    "sha256": "0abd21953ef1c811b2baac81041c17428ed3ff41c60b200ff1b9abf4c8eadb00",
    "license": "MIT",
    "licenseSha256": "3bf88392f4d4b9fc61e317906a884bdfc63b598305f7adedee55dd2183c23ef3",
    "configOverride": "none; unmodified upstream config and dependency lock",
    "buildNode": "v14.17.0",
}
PROJECTS = {"crud": CRUD, "taskboard": TASKBOARD}
# These operations implement app features, not framework/vendor-only behavior.
TASKBOARD_EXPECTED = {
    "client": [("setData", "text/plain"), ("getData", "text/plain"), ("get", "title")],
    "server": [("prepare", "UPDATE tasks SET status = ? WHERE id = ?"),
               ("prepare", "DELETE FROM comments WHERE id = ? AND user_name = ?"),
               ("prepare", "SELECT * FROM tasks ORDER BY created_at DESC, id ASC")],
}
EXPECTED = {"taskboard": TASKBOARD_EXPECTED, "crud": {
    "client": [("success", "User added"), ("success", "User updated"), ("required", "Title is required")],
    "server": [("writeFileSync", "data/users.json"), ("status", 200), ("status", 405)],
}}
FULL_QUERIES = (
    "MATCH (n) RETURN n ORDER BY n.id",
    "MATCH (a)-[r]->(b) RETURN a.id AS source, b.id AS target, type(r) AS type, "
    "r.kind AS kind, r.virtual AS virtual, r.dynamic AS dynamic "
    "ORDER BY source, target, type, kind, virtual, dynamic",
)
# Independent direct-call oracle: read emitted JS syntax, without the frontend's
# analysis, symbol resolution, normalization, graph or persisted query results.
ORACLE = r"""
const fs = require('node:fs');
const path = require('node:path');
const ts = require(process.argv[1]);
const root = process.argv[2];
const expected = JSON.parse(process.argv[3]);
const result = expected.map(([callee,value]) => ({callee,value,locations:[]}));
function walk(dir) {
  for (const ent of fs.readdirSync(dir,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name))) {
    const file = path.join(dir,ent.name);
    if(ent.isDirectory()) walk(file);
    else if(ent.isFile() && /\.[cm]?js$/.test(file)) {
      const text = fs.readFileSync(file,'utf8');
      const ast = ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.JS);
      if(ast.parseDiagnostics.length) throw new Error(`Invalid emitted JS: ${file}`);
      function visit(n) {
        if(ts.isCallExpression(n) && ts.isPropertyAccessExpression(n.expression)) {
          const callee = n.expression.name.text;
          for(const arg of n.arguments) if(ts.isStringLiteralLike(arg) || ts.isNumericLiteral(arg)) {
            const value = ts.isNumericLiteral(arg) ? Number(arg.text) : arg.text;
            const check = result.find(c=>c.callee===callee && c.value===value);
            if(check) check.locations.push({file:path.relative(root,file).split(path.sep).join('/'),
              line:ast.getLineAndCharacterOfPosition(n.getStart(ast)).line+1});
          }
        }
        ts.forEachChild(n,visit);
      }
      visit(ast);
    }
  }
}
walk(root);
for(const check of result) if(!check.locations.length) throw new Error(`Missing compiled app operation: ${JSON.stringify(check)}`);
console.log(JSON.stringify(result));
"""


def digest(data):
    return hashlib.sha256(data).hexdigest()


def js_inventory(app):
    result = {}
    for side, relative in (("server", "server"), ("client", "static")):
        root = app / ".next" / relative
        files = {str(p.relative_to(root)): digest(p.read_bytes()) for p in sorted(root.rglob("*"))
                 if p.is_file() and p.suffix in (".js", ".mjs", ".cjs")}
        if not files:
            raise RuntimeError(f"Missing compiled {side} JavaScript: {root}")
        result[side] = files
    return result


class Harness:
    def __init__(self, cli, frontend, output, project="crud", legacy_build_node=None):
        self.project_key = project
        self.project = PROJECTS[project]
        self.legacy_build_node = legacy_build_node
        self.cli = str(cli.resolve())
        self.frontend = frontend.resolve()
        self.output = output.resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        if (self.output / "commands.json").exists():
            raise RuntimeError("Use a fresh output directory; prior evidence must not be overwritten")
        original_frontend = self.frontend
        original_cli = Path(self.cli)
        inputs = [*sorted(original_frontend.parent.glob("*.js")),
                  *sorted((original_frontend.parent.parent / "src").glob("*.ts")),
                  original_frontend.parent.parent / "package.json",
                  original_frontend.parent.parent / "package-lock.json"]
        provenance = {"files": {str(p): digest(p.read_bytes()) for p in inputs if p.is_file()},
                      "cli": self.cli, "cliSha256": digest(original_cli.read_bytes()) if original_cli.is_file() else None,
                      "harnessSha256": digest(Path(__file__).read_bytes()),
                      "node": subprocess.check_output(["node", "--version"], text=True).strip(),
                      "python": platform.python_version(), "platform": platform.platform()}
        frozen = self.output / "producer"
        frozen.mkdir(exist_ok=True)
        if original_frontend.is_file():
            package = frozen / "frontend"
            package.mkdir()
            shutil.copytree(original_frontend.parent, package / "dist")
            for name in ("package.json", "package-lock.json"):
                shutil.copy2(original_frontend.parent.parent / name, package / name)
            shutil.copytree(original_frontend.parent.parent / "src", package / "src")
            (package / "node_modules").symlink_to(original_frontend.parent.parent / "node_modules", target_is_directory=True)
            self.frontend = package / "dist" / original_frontend.name
            for file in package.rglob("*"):
                if file.is_file() and "node_modules" not in file.parts:
                    file.chmod(0o444)
            os.environ["GRAPHITE_FRONTEND_JS"] = str(self.frontend)
        if original_cli.is_file():
            shutil.copy2(original_cli, frozen / "graphite")
            self.cli = str(frozen / "graphite")
            Path(self.cli).chmod(0o555)
        provenance["snapshotFrontend"] = str(self.frontend)
        provenance["snapshotCli"] = self.cli
        (self.output / "producer.json").write_text(json.dumps(provenance, indent=2) + "\n")
        self.commands = []
        self.results = []

    def command(self, args, cwd=None, env=None, timeout=900):
        command = list(map(str, args))
        index = len(self.commands)
        prefix = self.output / f"command-{index:03d}"
        record = {"command": command, "cwd": str(cwd) if cwd else None,
                  "environmentOverrides": env or {}, "timeoutSeconds": timeout,
                  "stdout": str(prefix.with_suffix(".stdout")),
                  "stderr": str(prefix.with_suffix(".stderr"))}
        self.commands.append(record)
        self.save_commands()
        with prefix.with_suffix(".stdout").open("w") as stdout, prefix.with_suffix(".stderr").open("w") as stderr:
            try:
                result = subprocess.run(command, cwd=cwd, env={**os.environ, **(env or {})},
                                        stdout=stdout, stderr=stderr, text=True, timeout=timeout)
                record["exitCode"] = result.returncode
            except Exception as failure:
                record["error"] = repr(failure)
                raise
            finally:
                self.save_commands()
        if result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}): {command}; see {prefix}.stderr")
        return prefix.with_suffix(".stdout").read_text()

    def save_commands(self):
        (self.output / "commands.json").write_text(json.dumps(self.commands, indent=2) + "\n")

    def run(self, *args):
        return self.command([self.cli, *args])

    def query(self, graph, query):
        result = json.loads(self.run("query", graph, query, "--format", "json"))
        assert result["rowCount"] == len(result["rows"]), result
        return result["rows"]

    def prepare(self):
        revision = self.project["revision"]
        archive = self.output / f"taskboard-{revision}.tar.gz"
        if not archive.exists():
            url = f"https://codeload.github.com/{self.project['repo']}/tar.gz/{revision}"
            with urllib.request.urlopen(url, timeout=60) as response:
                archive.write_bytes(response.read())
        data = archive.read_bytes()
        assert digest(data) == self.project["sha256"], "Pinned upstream archive hash mismatch"
        source = self.output / "sources"
        source.mkdir(exist_ok=True)
        root = source / f"{self.project['name']}-{revision}"
        if root.exists():
            shutil.rmtree(root)
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as contents:
            for member in contents.getmembers():
                destination = (source / member.name).resolve()
                if not destination.is_relative_to(root) or not (member.isfile() or member.isdir()):
                    raise RuntimeError(f"Unexpected archive member: {member.name}")
            contents.extractall(source, filter="data")
        package = json.loads((root / "package.json").read_text())
        assert package["scripts"]["build"] == "next build", "Review changed upstream build script"
        if self.project_key == "taskboard":
            assert package["packageManager"] == self.project["packageManager"]
            # Only build concurrency changes; application logic is untouched.
            config = root / "next.config.ts"
            original = config.read_text()
            assert original.count("  experimental: {\n") == 1
            config.write_text(original.replace("  experimental: {\n", "  experimental: {\n    cpus: 1,\n", 1))
            self.command(["npx", "--yes", self.project["packageManager"], "install", "--frozen-lockfile", "--ignore-scripts"], cwd=root)
            self.command(["npx", "--yes", self.project["packageManager"], "rebuild", "better-sqlite3"], cwd=root)
            build = ["npm", "run", "build"]
            lock = "pnpm-lock.yaml"
        else:
            assert digest((root / "LICENSE").read_bytes()) == self.project["licenseSha256"]
            config = root / "next.config.js"
            original = config.read_text()
            node = str(self.legacy_build_node or "node")
            version = self.command([node, "--version"]).strip()
            assert version == self.project["buildNode"], "CRUD fixture requires --legacy-build-node pointing to Node 14.17.0"
            self.command(["npm", "ci", "--ignore-scripts"], cwd=root)
            build = [node, "node_modules/next/dist/bin/next", "build"]
            lock = "package-lock.json"
        runtime = self.output / "sqlite-runtime"
        runtime.mkdir(exist_ok=True)
        self.command(build, cwd=root,
                     env={"TMPDIR": str(runtime), "TMP": str(runtime), "TEMP": str(runtime),
                          "NEXT_TELEMETRY_DISABLED": "1", "NODE_OPTIONS": "--max-old-space-size=4096"})
        manifest = {**self.project, "lockFile": lock, "lockSha256": digest((root / lock).read_bytes()),
                    "nextConfigBefore": original, "nextConfigAfter": config.read_text(),
                    "artifacts": js_inventory(root)}
        (root / ".graphite-fixture.json").write_text(json.dumps(manifest, indent=2) + "\n")
        (self.output / "fixture.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return root

    def check_side(self, app, side):
        artifact = app / ".next" / ("static" if side == "client" else "server")
        # Parse compiled JS independently before running the graph producer.
        typescript = self.frontend.parent.parent / "node_modules" / "typescript"
        expected = json.loads(self.command(["node", "-e", ORACLE, typescript, artifact, json.dumps(EXPECTED[self.project_key][side])]))
        (self.output / f"{side}-compiled-oracle.json").write_text(json.dumps(expected, indent=2) + "\n")
        graph = self.output / f"{side}-graph"
        packed = self.output / f"{side}.graphite"
        unpacked = self.output / f"{side}-unpacked"
        direct = self.output / f"{side}-direct.graphite"
        language = "js" if side == "client" else "ts"
        self.run("build", "--lang", language, artifact, "-o", graph)
        checks = []
        for operation in expected:
            # JSON strings also escape quotes/backslashes correctly for Cypher.
            value = json.dumps(operation["value"])
            callee = json.dumps(operation["callee"])
            kind = "StringConstant" if isinstance(operation["value"], str) else "IntConstant"
            query = (f"MATCH (s:{kind} {{value: {value}}})-[r:DATAFLOW]->"
                     f"(cs:CallSiteNode {{callee_name: {callee}}}) WHERE r.kind = 'PARAMETER_PASS' "
                     "RETURN s.value AS value, cs.caller_class AS caller_class, cs.callee_name AS callee, cs.line AS line "
                     "ORDER BY caller_class, callee, line")
            rows = self.query(graph, query)
            assert rows, f"{side}: missing compiled operation {operation}"
            for location in operation["locations"]:
                assert any(row["value"] == operation["value"] and row["callee"] == operation["callee"]
                           and row["line"] == location["line"]
                           and (row["caller_class"] == location["file"]
                                or row["caller_class"].startswith(location["file"] + "#")
                                or row["caller_class"].startswith(location["file"] + "."))
                           for row in rows), (side, operation, location, rows)
            checks.append({"query": query, "rows": rows})
        for query in FULL_QUERIES:
            rows = self.query(graph, query)
            assert rows, f"{side}: empty persisted graph"
            checks.append({"query": query, "rows": rows})
        self.run("pack", graph, "-o", packed)
        self.run("verify", "--verbose", packed)
        self.run("unpack", packed, unpacked)
        self.run("build", "--lang", language, artifact, "-o", direct)
        self.run("verify", "--verbose", direct)
        for restored in (packed, unpacked, direct):
            for check in checks:
                actual = self.query(restored, check["query"])
                assert actual == check["rows"], f"{side}: roundtrip differs: {restored}: {check['query']}"
        evidence = [{"query": check["query"], "rowCount": len(check["rows"]),
                     "sha256": digest(json.dumps(check["rows"], sort_keys=True).encode())} for check in checks]
        result = {"side": side, "passed": True, "artifact": str(artifact), "checks": evidence,
                  "nodes": len(checks[-2]["rows"]), "edges": len(checks[-1]["rows"]),
                  "forms": ["directory", "packed", "unpacked", "direct-container"]}
        (self.output / f"{side}-results.json").write_text(json.dumps(result, indent=2) + "\n")
        self.results.append(result)
        print(f"PASS {self.project_key} {side}: {result['nodes']} nodes, {result['edges']} edges, all four saved forms", flush=True)

    def check_server(self):
        # Every measured request below is a correctness check over BOTH real
        # graphs. No durations, latency distributions or performance assertions.
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        command = [self.cli, "serve", "--port", str(port), "--load-mode", "MAPPED",
                   "--graph", f"client:{self.output / 'client-graph'}",
                   "--graph", f"server:{self.output / 'server-graph'}"]
        report = {"command": command, "graphScope": ["client", "server"], "checks": [],
                  "scope": "Bounded multi-graph HTTP correctness only; no performance evidence"}
        def request(query):
            payload = json.dumps({"query": query, "timeoutMs": 60000}).encode()
            req = urllib.request.Request(f"http://127.0.0.1:{port}/api/cypher", data=payload,
                                         headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=65) as response:
                result = json.load(response)
            report["checks"].append({"query": query, "response": result})
            return [{key: value for key, value in row.items() if key != "$metadata"} for row in result["rows"]]
        with (self.output / "server.stdout").open("w") as stdout, (self.output / "server.stderr").open("w") as stderr:
            process = subprocess.Popen(command, stdout=stdout, stderr=stderr)
            report["pid"] = process.pid
            try:
                deadline = time.monotonic() + 60
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f"Graph server exited before readiness: {process.returncode}")
                    try:
                        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/graphs", timeout=1) as response:
                            json.load(response)
                        break
                    except (OSError, ValueError):
                        if time.monotonic() >= deadline:
                            raise RuntimeError("Graph server readiness exceeded 60 seconds")
                        time.sleep(0.1)
                counts = request("MATCH (n) RETURN n.graphId AS graph, count(*) AS count ORDER BY graph")
                assert counts == [{"graph": result["side"], "count": result["nodes"]}
                                  for result in self.results], counts
                values = [EXPECTED[self.project_key][side][0][1] for side in ("client", "server")]
                pattern = ("MATCH (s:StringConstant)-[r:DATAFLOW]->(cs:CallSiteNode) "
                           f"WHERE s.value IN {json.dumps(values)} AND r.kind = 'PARAMETER_PASS' ")
                projection = ("s.value AS value, cs.caller_class AS caller_class, "
                              "cs.callee_name AS callee, cs.line AS line")
                expected = []
                for side in ("client", "server"):
                    rows = self.query(self.output / f"{side}-graph", pattern + "RETURN " + projection)
                    assert rows, f"No app operation in {side} for multi-graph HTTP verification"
                    expected.extend({**row, "graph": side} for row in rows)
                rows = request(pattern + "RETURN cs.graphId AS graph, " + projection)
                canonical = lambda values: sorted(json.dumps(value, sort_keys=True) for value in values)
                assert canonical(rows) == canonical(expected), (rows, expected)
                assert {row["graph"] for row in rows} == {"client", "server"}, rows
                report["passed"] = True
                print("PASS production HTTP server: both real client/server graphs, counts and concrete parameter flows", flush=True)
            except Exception as failure:
                report["error"] = repr(failure)
                raise
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
                report["exitCodeAfterShutdown"] = process.returncode
                (self.output / "http-results.json").write_text(json.dumps(report, indent=2) + "\n")

    def check_defaults(self):
        # Synthetic correctness fixture only; real applications remain required.
        artifact = self.output / "default-arguments.js"
        artifact.write_text('function identity(value = "shared") { return value; }\n'
                            'identity();\nidentity(undefined);\nidentity(null);\n')
        interchange = self.output / "default-interchange.json"
        self.command(["node", self.frontend, "build", artifact, "--output", interchange])
        raw = json.loads(interchange.read_text())
        calls = sorted((node for node in raw["nodes"] if node["kind"] == "CallSite"
                        and node["callee"]["name"] == "identity"), key=lambda node: node["line"])
        assert len(calls) == 3 and all(len(call["arguments"]) == 1 for call in calls), calls
        actuals = [raw["nodes"][call["arguments"][0]] for call in calls]
        assert [(node["kind"], node.get("value")) for node in actuals] == [
            ("StringConstant", "shared"), ("StringConstant", "shared"), ("NullConstant", None)], actuals
        graph = self.output / "defaults-graph"
        packed = self.output / "defaults.graphite"
        unpacked = self.output / "defaults-unpacked"
        direct = self.output / "defaults-direct.graphite"
        self.run("build", "--lang", "js", artifact, "-o", graph)
        queries = [
            ("MATCH (cs:CallSiteNode {callee_name: 'identity'}) RETURN cs.line AS line ORDER BY line",
             [{"line": 2}, {"line": 3}, {"line": 4}]),
            ("MATCH (s:Constant)-[r:DATAFLOW]->(cs:CallSiteNode {callee_name: 'identity'}) "
             "RETURN cs.line AS line, labels(s) AS labels, s.value AS value, r.kind AS kind ORDER BY line",
             [{"line": 2, "labels": ["StringConstant", "Constant"], "value": "shared", "kind": "PARAMETER_PASS"},
              {"line": 3, "labels": ["StringConstant", "Constant"], "value": "shared", "kind": "PARAMETER_PASS"},
              {"line": 4, "labels": ["NullConstant", "Constant"], "kind": "PARAMETER_PASS"}]),
            ("MATCH (s:StringConstant {value: 'shared'})-[r]->(p:ParameterNode) "
             "RETURN p.index AS index, type(r) AS type, r.kind AS kind",
             [{"index": 0, "type": "DATAFLOW", "kind": "PARAMETER_PASS"}]),
        ]
        checks = []
        for query, expected in queries:
            rows = self.query(graph, query)
            assert rows == expected, (query, rows, expected)
            checks.append({"query": query, "rows": rows})
        for query in FULL_QUERIES:
            checks.append({"query": query, "rows": self.query(graph, query)})
        self.run("pack", graph, "-o", packed)
        self.run("verify", "--verbose", packed)
        self.run("unpack", packed, unpacked)
        self.run("build", "--lang", "ts", artifact, "-o", direct)
        self.run("verify", "--verbose", direct)
        for restored in (packed, unpacked, direct):
            for check in checks:
                assert self.query(restored, check["query"]) == check["rows"], (restored, check["query"])
        result = {"fixture": "default-normalization", "passed": True, "syntheticCorrectnessOnly": True,
                  "frontendArgumentSlots": "Raw interchange checked separately; persisted effective values verified via edges",
                  "checks": checks, "forms": ["directory", "packed", "unpacked", "direct-container"]}
        (self.output / "default-results.json").write_text(json.dumps(result, indent=2) + "\n")
        print("PASS default argument normalization: exact effective values and relations in all four saved forms", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", type=Path, default=Path("target/release/graphite"))
    parser.add_argument("--frontend", type=Path, default=Path("frontend/web/dist/cli.js"))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--prepare-only", action="store_true", help="Build pinned upstream app and inventory generated JS")
    parser.add_argument("--prepared-app", type=Path, help="Reuse a previously prepared app after verifying its artifact hashes")
    parser.add_argument("--project", choices=PROJECTS, default="crud")
    parser.add_argument("--legacy-build-node", type=Path, help="Node 14.17.0 executable used only for the pinned Next 10 CRUD build")
    args = parser.parse_args()
    os.environ["GRAPHITE_FRONTEND_JS"] = str(args.frontend.resolve())
    harness = Harness(args.cli, args.frontend, args.output, args.project, args.legacy_build_node)
    error = None
    try:
        app = args.prepared_app.resolve() if args.prepared_app else harness.prepare()
        manifest = json.loads((app / ".graphite-fixture.json").read_text())
        assert all(manifest[key] == value for key, value in harness.project.items()), "Wrong prepared fixture"
        assert manifest["artifacts"] == js_inventory(app), "Prepared artifacts changed since next build"
        assert manifest["lockSha256"] == digest((app / manifest.get("lockFile", "pnpm-lock.yaml")).read_bytes()), "Prepared lock changed"
        (harness.output / "fixture.json").write_text(json.dumps(manifest, indent=2) + "\n")
        if args.prepare_only:
            print(app, flush=True)
        else:
            harness.check_defaults()
            for side in ("client", "server"):
                harness.check_side(app, side)
            harness.check_server()
    except Exception as failure:
        error = f"{type(failure).__name__}: {failure}"
        raise
    finally:
        (harness.output / "summary.json").write_text(json.dumps({"passed": error is None and not args.prepare_only,
            "preparationOnly": args.prepare_only, "error": error, "project": harness.project, "graphs": harness.results,
            "scope": "Correctness only. Real upstream next build; graph inputs are generated client/server JS. "
                     "Complete node/edge queries compare all saved forms. No performance claim or source graph substitution."}, indent=2) + "\n")


if __name__ == "__main__":
    main()
