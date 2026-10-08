#!/usr/bin/env python3
"""Verify a Swift client / JVM route join on independently built, served graphs."""

import argparse
import json
from pathlib import Path
import urllib.request


JOIN = """
MATCH (clientPath:Constant)-[:DATAFLOW]->(client:CallSite)
WHERE client.caller_class = 'AcmeShop.PaymentClient'
  AND client.caller_name = 'charge(amount:currency:method:)'
  AND client.callee_name = 'send(path:retries:)'
  AND clientPath.value = '/v1/charge'
MATCH (serverPath:Constant)-[:DATAFLOW]->(server:CallSite)
WHERE server.caller_class = 'com.acme.payments.PaymentService'
  AND server.caller_name = 'registerRoutes'
  AND server.callee_class = 'com.sun.net.httpserver.HttpServer'
  AND server.callee_name = 'createContext'
  AND serverPath.value = clientPath.value
RETURN DISTINCT clientPath.value AS path,
  client.caller_class AS client_class, client.caller_name AS client_method,
  client.callee_name AS client_call, client.graphId AS client_graph,
  server.caller_class AS server_class, server.caller_name AS server_method,
  server.callee_name AS server_call, server.graphId AS server_graph
""".strip()

EXPECTED = {
    "path": "/v1/charge",
    "client_class": "AcmeShop.PaymentClient",
    "client_method": "charge(amount:currency:method:)",
    "client_call": "send(path:retries:)",
    "client_graph": "swift",
    "server_class": "com.acme.payments.PaymentService",
    "server_method": "registerRoutes",
    "server_call": "createContext",
    "server_graph": "backend",
    "$metadata": {"graphIds": ["backend", "swift"]},
}


def request(base, path, query=None):
    data = None if query is None else json.dumps({"query": query}).encode()
    req = urllib.request.Request(
        base.rstrip("/") + path,
        data=data,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def verify(base, evidence):
    records = []

    def record(path, query=None):
        result = request(base, path, query)
        records.append({"endpoint": path, "query": query, "response": result})
        evidence.write_text(json.dumps(records, indent=2) + "\n")
        return result

    graphs = record("/api/graphs")
    assert sorted(graph["id"] for graph in graphs["graphs"]) == ["backend", "swift"], graphs
    joined = record("/api/cypher", JOIN)
    assert joined.get("graphCount") == 2, joined
    assert joined.get("rowCount") == 1, joined
    assert joined.get("columns") == list(EXPECTED)[:-1], joined
    assert joined.get("rows") == [EXPECTED], joined

    # The join needs declarations from both languages, not two independent queries
    # that happen to run on a server with multiple graphs loaded.
    for graph in ("swift", "backend"):
        isolated = record(f"/api/graphs/{graph}/cypher", JOIN)
        assert isolated.get("rowCount") == 0 and isolated.get("rows") == [], isolated
    print("Verified Swift charge -> /v1/charge -> JVM route with backend + swift provenance")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url")
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    verify(args.base_url, args.evidence)
