#!/usr/bin/env python3
"""Capture correctness-only HTTP observations from a freshly built JVM server."""
import argparse
import hashlib
import json
import pathlib
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True, type=pathlib.Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    jar = args.jar.resolve()
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    cases = [
        ("graphs-empty", "GET", "/api/graphs", None),
        ("topology-empty", "GET", "/api/topology", None),
        ("missing-graph", "GET", "/api/graphs/missing", None),
        ("invalid-graph-id", "GET", "/api/graphs/bad!", None),
        ("missing-scoped-node", "GET", "/api/graphs/missing/node/1", None),
        ("missing-root-node", "GET", "/api/node/1", None),
        ("missing-root-subgraph", "GET", "/api/subgraph?center=1", None),
        ("removed-nodes", "GET", "/api/nodes", None),
        ("removed-methods", "GET", "/api/methods", None),
        ("removed-call-sites", "GET", "/api/call-sites", None),
        ("missing-annotations-params", "GET", "/api/annotations", None),
        ("annotations-empty", "GET", "/api/annotations?class=C&member=m", None),
        ("resources-empty", "GET", "/api/resources", None),
        ("resource-missing", "GET", "/api/resources/absent.txt", None),
        ("endpoints-empty", "GET", "/api/endpoints", None),
        ("overview-empty", "GET", "/api/overview", None),
        ("c4-empty", "GET", "/api/architecture/c4", None),
        ("c4-invalid-level", "GET", "/api/architecture/c4?level=invalid", None),
        ("c4-accept-overrides-invalid-format", "GET", "/api/architecture/c4?format=invalid", None),
        ("c4-invalid-format", "GET", "/api/architecture/c4?format=invalid", None),
        ("metrics-disabled", "GET", "/metrics", None),
        ("cypher-missing-query", "GET", "/api/cypher", None),
        ("cypher-return", "POST", "/api/cypher", {"query": "RETURN 1 AS ok"}),
        ("cypher-get-return", "GET", "/api/cypher?query=RETURN%201%20AS%20ok", None),
        ("cypher-match-empty", "POST", "/api/cypher", {"query": "MATCH (n) RETURN n"}),
        ("cypher-count-empty", "POST", "/api/cypher", {"query": "MATCH (n) RETURN count(n) AS total"}),
        ("cypher-invalid", "POST", "/api/cypher", {"query": "NOT A VALID QUERY"}),
        ("cypher-write", "POST", "/api/cypher", {"query": "CREATE (n) RETURN n"}),
        ("cypher-unknown-function", "POST", "/api/cypher", {"query": "RETURN unknown(1)"}),
        ("cypher-null-row", "POST", "/api/cypher", {"query": "RETURN null AS nil, [1,null] AS values"}),
        ("cypher-unwind", "POST", "/api/cypher", {"query": "UNWIND [3,1,2] AS n RETURN n ORDER BY n DESC LIMIT 2"}),
        ("cypher-unknown-timeout-field-ignored", "POST", "/api/cypher", {"query": "RETURN 1", "timeoutMillis": 0}),
        ("cypher-invalid-timeout", "POST", "/api/cypher", {"query": "RETURN 1", "timeoutMs": 0}),
        ("cypher-selected-empty", "POST", "/api/cypher/graphs", {"query": "RETURN 1 AS ok", "allGraphs": True}),
        ("cypher-fanout-empty", "POST", "/api/cypher/graphs", {"query": "RETURN 1 AS ok", "allGraphs": True, "mode": "fanout"}),
        ("cypher-selection-missing", "POST", "/api/cypher/graphs", {"query": "RETURN 1"}),
        ("cypher-selection-duplicates", "POST", "/api/cypher/graphs", {"query": "RETURN 1", "graphs": ["a", "a"]}),
        ("cypher-scoped-missing", "POST", "/api/graphs/missing/cypher", {"query": "RETURN 1"}),
        ("graph-load-missing-path", "PUT", "/api/graphs/test", {}),
        ("graph-delete-missing", "DELETE", "/api/graphs/missing", None),
    ]
    observations = []
    with tempfile.TemporaryDirectory(prefix="graphite-go-empty-baseline-") as data:
        command = ["java", "-jar", str(jar), "--data", data, "--port", str(port)]
        with (args.output / "server.log").open("wb") as log:
            server = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            try:
                for _ in range(200):
                    if server.poll() is not None:
                        raise RuntimeError("Baseline server exited during startup; see server.log")
                    try:
                        urllib.request.urlopen(f"http://localhost:{port}/api/graphs", timeout=1).close()
                        break
                    except (OSError, urllib.error.URLError):
                        time.sleep(0.1)
                else:
                    raise RuntimeError("Baseline server did not become ready")
                for name, method, path, body in cases:
                    payload = None if body is None else json.dumps(body).encode()
                    request = urllib.request.Request(
                        f"http://localhost:{port}{path}", data=payload, method=method,
                        headers={"Content-Type": "application/json",
                                 "Accept": "*/*" if name == "c4-invalid-format" else "application/json"})
                    try:
                        response = urllib.request.urlopen(request, timeout=10)
                    except urllib.error.HTTPError as error:
                        response = error
                    with response:
                        raw = response.read().decode("utf-8")
                        record = {"name": name, "method": method, "path": path, "request": body,
                                  "requestHeaders": dict(request.header_items()),
                                  "status": response.status, "headers": dict(response.headers), "body": raw}
                        try:
                            record["json"] = json.loads(raw)
                        except json.JSONDecodeError:
                            pass
                        observations.append(record)
                (args.output / "observations.json").write_text(json.dumps(observations, indent=2) + "\n")
                metadata = {"revision": args.revision, "jar": str(jar),
                            "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                            "javaVersion": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr,
                            "command": command, "cases": len(cases),
                            "purpose": "Correctness-only empty-catalog HTTP baseline; no performance claims"}
                (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
            finally:
                server.terminate()
                try:
                    server.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait()
    for record in observations:
        print(record["name"], record["status"], record["body"][:140].replace("\n", " "))


if __name__ == "__main__":
    main()
