#!/usr/bin/env python3
"""Where the first hit sits decides what a LIMIT query costs across many graphs.

Graphs are planned and swept in id order, in batches. A term whose only occurrence is
in the first graph is answered after one plan; one whose only occurrence is in the last
graph is answered after every graph was planned; a term present nowhere is the
worst case, as every graph must be ruled out. This measures all four positions with
the production's commonest shape, so a change to the batch schedule shows up here as
a number and not as an argument.

For each chosen position a `callee_class` is picked that occurs in that graph and, as
far as the cross-graph provenance query can tell, in no other served graph; the
absent term is one that occurs nowhere. Every request must succeed (HTTP 200 with
rows), or the script exits non-zero.

    hit-position.py --base http://localhost:18080 [--repeat 11]
"""

import argparse, json, statistics, sys, urllib.parse, http.client, time


class Client:
    def __init__(self, base, timeout):
        u = urllib.parse.urlparse(base)
        self.conn = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=timeout)

    def get_json(self, path):
        self.conn.request("GET", path)
        r = self.conn.getresponse()
        data = r.read()
        return r.status, json.loads(data) if r.status == 200 else None

    def cypher(self, query, path="/api/cypher"):
        body = json.dumps({"query": query}).encode()
        started = time.perf_counter()
        self.conn.request("POST", path, body=body,
                          headers={"Content-Type": "application/json", "Connection": "keep-alive"})
        r = self.conn.getresponse()
        data = r.read()
        ms = (time.perf_counter() - started) * 1000.0
        try:
            d = json.loads(data)
        except Exception:
            d = None
        ok = r.status == 200 and isinstance(d, dict) and isinstance(d.get("rows"), list)
        return ms, ok, d


def esc(t):
    return t.replace("\\", "\\\\").replace('"', '\\"')


def unique_term(client, graph_id, candidates):
    """The first candidate whose provenance is exactly this graph."""
    for c in candidates:
        q = (f'MATCH (n:CallSite) WHERE n.callee_class = "{esc(c)}" '
             f"RETURN DISTINCT n.graphId AS g")
        _, ok, d = client.cypher(q)
        if not ok:
            continue
        graphs = sorted(r.get("g") for r in d["rows"] if isinstance(r.get("g"), str))
        if graphs == [graph_id]:
            return c
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--repeat", type=int, default=11)
    ap.add_argument("--out")
    args = ap.parse_args()
    client = Client(args.base, args.timeout)

    status, catalog = client.get_json("/api/graphs")
    graphs = [g["id"] for g in (catalog or {}).get("graphs", [])] if status == 200 else []
    if len(graphs) < 3:
        print(f"need at least three served graphs, found {len(graphs)}", file=sys.stderr)
        return 2
    positions = {
        "early": graphs[0],
        "middle": graphs[len(graphs) // 2],
        "late": graphs[-1],
    }
    terms = {}
    for name, gid in positions.items():
        _, ok, d = client.cypher(
            "MATCH (n:CallSite) RETURN DISTINCT n.callee_class AS c LIMIT 3000",
            path=f"/api/graphs/{gid}/cypher",
        )
        if not ok:
            print(f"could not sample {gid}", file=sys.stderr)
            return 2
        candidates = [r["c"] for r in d["rows"] if isinstance(r.get("c"), str)]
        # Prefer names deep in the graph's own namespace: those are the ones a graph
        # is likely to hold alone.
        candidates.sort(key=lambda c: -c.count("."))
        term = unique_term(client, gid, candidates[:400])
        if term is None:
            print(f"no callee_class unique to {gid} among 400 candidates", file=sys.stderr)
            return 2
        terms[name] = term
    terms["absent"] = "__graphite_hit_position_absent__"

    def shape(t):
        return (f'MATCH (n) WHERE n.caller_class CONTAINS "{esc(t)}" '
                f'OR n.callee_class CONTAINS "{esc(t)}" RETURN n LIMIT 25')

    results = {}
    failures = 0
    for name, term in terms.items():
        q = shape(term)
        samples = []
        for _ in range(args.repeat):
            ms, ok, d = client.cypher(q)
            if not ok:
                failures += 1
                continue
            samples.append(ms)
        results[name] = {
            "graph": positions.get(name),
            "term": term,
            "rows": len(d["rows"]) if ok and d else None,
            "samples": len(samples),
            "median_ms": round(statistics.median(samples), 2) if samples else None,
            "max_ms": round(max(samples), 2) if samples else None,
        }
        print(f"  {name:7s} {results[name]['median_ms']!s:>8} ms median  "
              f"({results[name]['rows']} rows, graph {positions.get(name)})", flush=True)
    summary = {"graphs": len(graphs), "repeat": args.repeat, "failures": failures,
               "positions": results}
    print(json.dumps(summary, indent=2))
    if args.out:
        json.dump(summary, open(args.out, "w"), indent=2)
    return 3 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
