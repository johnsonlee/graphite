#!/usr/bin/env python3
"""End-to-end check of the MCP server on both transports against the REST API.

    python3 mcp-smoke.py <graphite-binary> <http-base-url> --graph id:path [--graph id:path ...]

Starts `graphite mcp` (stdio) with the given graphs, walks the JSON-RPC lifecycle
(initialize, initialized, tools/list, tools/call) and checks each tool's text against
the same request made to the REST API of the running server at <http-base-url>, which
must serve the same graphs. Then sends the same tools/list and one tools/call to
`POST <http-base-url>/mcp` and checks the answers match the stdio ones. Exits non-zero on
the first mismatch. No production data: the graphs come from the CI fixtures.
"""

import json
import subprocess
import sys
import urllib.parse
import urllib.request


def fail(message):
    print(f"FAIL: {message}", file=sys.stderr)
    sys.exit(1)


def http(base, method, path, body=None, headers=None):
    req = urllib.request.Request(base + path, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data=data, timeout=60) as res:
            return res.status, res.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


class Stdio:
    def __init__(self, argv):
        self.proc = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        self.next_id = 1

    def notify(self, method, params=None):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()

    def request(self, method, params=None):
        msg = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        self.next_id += 1
        if params is not None:
            msg["params"] = params
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()
        line = self.proc.stdout.readline()
        if not line:
            fail(f"stdio server closed while answering {method}")
        res = json.loads(line)
        if res.get("id") != msg["id"]:
            fail(f"response id mismatch for {method}: {res}")
        return res

    def close(self):
        self.proc.stdin.close()
        self.proc.wait(timeout=60)


VOLATILE_KEYS = {"loadedAt"}


def stable(value):
    """Drop fields that differ between two processes serving the same graphs."""
    if isinstance(value, dict):
        return {k: stable(v) for k, v in value.items() if k not in VOLATILE_KEYS}
    if isinstance(value, list):
        return [stable(v) for v in value]
    return value


def tool_text(res):
    if "error" in res:
        fail(f"tools/call returned a JSON-RPC error: {res['error']}")
    return res["result"]


def main():
    if len(sys.argv) < 4:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    binary, base = sys.argv[1], sys.argv[2].rstrip("/")
    graph_args = sys.argv[3:]
    graph_ids = [a.split(":", 1)[0] for a in graph_args if ":" in a]

    stdio = Stdio([binary, "mcp"] + graph_args)
    init = stdio.request("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                                        "clientInfo": {"name": "mcp-smoke", "version": "0"}})
    if init["result"]["protocolVersion"] != "2025-03-26" or init["result"]["serverInfo"]["name"] != "graphite":
        fail(f"unexpected initialize result: {init}")
    stdio.notify("notifications/initialized")
    listed = stdio.request("tools/list")["result"]["tools"]
    names = [t["name"] for t in listed]
    expected = ["graphs", "openapi", "cypher", "node", "outgoing", "incoming", "annotations",
                "endpoints", "resources", "resource", "subgraph", "overview", "c4"]
    if names != expected:
        fail(f"tools/list: {names}")

    checks = 0

    def check(tool, arguments, method, path, body=None, text=False):
        nonlocal checks
        res = tool_text(stdio.request("tools/call", {"name": tool, "arguments": arguments}))
        status, http_body = http(base, method, path, body)
        if status != 200:
            fail(f"{method} {path} -> {status}: {http_body[:200]}")
        if res.get("isError"):
            fail(f"{tool} {arguments}: isError with {res['content'][0]['text'][:200]}")
        got = res["content"][0]["text"]
        want = http_body
        if not text:
            # The MCP text is `JSON.stringify(data, null, 2)` of the same response.
            got = json.dumps(stable(json.loads(got)), indent=2)
            want = json.dumps(stable(json.loads(http_body)), indent=2)
        if got != want:
            fail(f"{tool} {arguments}: MCP text differs from {method} {path}\n--- mcp\n{got[:400]}\n--- http\n{want[:400]}")
        checks += 1

    first = graph_ids[0]
    check("graphs", {}, "GET", "/api/graphs")
    check("graphs", {"graph_id": first}, "GET", f"/api/graphs/{urllib.parse.quote(first, safe='')}")
    check("openapi", {}, "GET", "/openapi.json")
    check("cypher", {"query": "MATCH (n) RETURN count(*) AS c", "graph_id": first},
          "POST", f"/api/graphs/{first}/cypher", {"query": "MATCH (n) RETURN count(*) AS c"})
    check("cypher", {"query": "MATCH (n:CallSite) RETURN count(*) AS c"},
          "POST", "/api/cypher", {"query": "MATCH (n:CallSite) RETURN count(*) AS c"})
    check("cypher", {"query": "MATCH (n:CallSite) RETURN n.callee_name AS m ORDER BY m LIMIT 3",
                     "all_graphs": True, "mode": "fanout", "per_graph_limit": 2},
          "POST", "/api/cypher/graphs?perGraphLimit=2",
          {"query": "MATCH (n:CallSite) RETURN n.callee_name AS m ORDER BY m LIMIT 3",
           "mode": "fanout", "allGraphs": True})
    check("node", {"id": 1, "graph_id": first}, "GET", f"/api/graphs/{first}/node/1")
    check("outgoing", {"id": 1, "graph_id": first}, "GET", f"/api/graphs/{first}/node/1/outgoing")
    check("incoming", {"id": 1, "graph_id": first}, "GET", f"/api/graphs/{first}/node/1/incoming")
    check("endpoints", {"graph_id": first}, "GET", f"/api/graphs/{first}/endpoints?limit=200")
    check("resources", {"pattern": "**", "limit": 5}, "GET", "/api/resources?pattern=**&limit=5")
    check("subgraph", {"center": 1, "depth": 1, "graph_id": first},
          "GET", f"/api/graphs/{first}/subgraph?center=1&depth=1")
    check("overview", {"limit": 10}, "GET", "/api/overview?limit=10")
    check("c4", {"graph_id": first, "level": "context"},
          "GET", f"/api/graphs/{first}/architecture/c4?level=context&format=json&limit=200")
    check("c4", {"graph_id": first, "level": "container", "format": "mermaid"},
          "GET", f"/api/graphs/{first}/architecture/c4?level=container&format=mermaid&limit=200",
          text=True)

    # Node routes exist per graph only, on the Kotlin server too; without graph_id the
    # npm package sent `/api/node/<id>` and got the same 404, which is kept as is.
    res = tool_text(stdio.request("tools/call", {"name": "outgoing", "arguments": {"id": 1}}))
    if not res.get("isError") or not res["content"][0]["text"].startswith("404 "):
        fail(f"all-graph node route should be a 404 tool error: {res}")
    checks += 1

    # An API error becomes a tool error, with the status line the npm package produced.
    res = tool_text(stdio.request("tools/call", {"name": "graphs", "arguments": {"graph_id": "no-such-graph"}}))
    if not res.get("isError") or not res["content"][0]["text"].startswith("404 Not Found: "):
        fail(f"missing graph should be a 404 tool error: {res}")
    res = tool_text(stdio.request("tools/call", {"name": "cypher", "arguments": {"query": "RETURN 1", "mode": "fanout"}}))
    if res["content"][0]["text"] != "fanout mode requires graphs or all_graphs=true":
        fail(f"validation message: {res}")
    checks += 2

    # The HTTP transport answers the same.
    status, body = http(base, "POST", "/mcp", {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    if status != 200 or [t["name"] for t in json.loads(body)["result"]["tools"]] != expected:
        fail(f"POST /mcp tools/list -> {status}: {body[:200]}")
    stdio_graphs = tool_text(stdio.request("tools/call", {"name": "graphs", "arguments": {}}))
    status, body = http(base, "POST", "/mcp",
                        {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "graphs", "arguments": {}}})
    if status != 200 or stable(json.loads(json.loads(body)["result"]["content"][0]["text"])) != \
            stable(json.loads(stdio_graphs["content"][0]["text"])):
        fail(f"POST /mcp tools/call differs from stdio: {body[:200]}")
    status, _ = http(base, "POST", "/mcp", {"jsonrpc": "2.0", "method": "notifications/initialized"})
    if status != 202:
        fail(f"notification over HTTP -> {status}, expected 202")
    status, _ = http(base, "GET", "/mcp")
    if status != 405:
        fail(f"GET /mcp -> {status}, expected 405")
    # DNS-rebinding protection: a foreign browser origin is refused, a loopback one is not.
    status, _ = http(base, "POST", "/mcp", {"jsonrpc": "2.0", "id": 3, "method": "ping"},
                     headers={"Origin": "http://evil.example"})
    if status != 403:
        fail(f"POST /mcp with a foreign Origin -> {status}, expected 403")
    status, _ = http(base, "POST", "/mcp", {"jsonrpc": "2.0", "id": 4, "method": "ping"},
                     headers={"Origin": "http://localhost:5173"})
    if status != 200:
        fail(f"POST /mcp with a loopback Origin -> {status}, expected 200")
    checks += 6

    stdio.close()
    print(f"mcp-smoke: {checks} checks passed on stdio and /mcp over {len(graph_ids)} graph(s)")


if __name__ == "__main__":
    main()
