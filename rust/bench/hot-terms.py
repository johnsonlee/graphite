#!/usr/bin/env python3
"""Wide `CONTAINS` searches for the corpus's most frequent terms.

Pruning cannot help here by construction. A term that appears in nearly every graph
survives every filter, so what is left is the cost of actually scanning — which is the
case worth measuring once the cheap exits are in place.

Frequency is measured, not assumed: fragments are pulled out of a large sample of the
four CallSite string properties, ranked by how often they occur, and the top ones are
then queried. Each term is run in three shapes, because a `LIMIT` that fills early hides
exactly the cost this is trying to expose:

    limited    `... LIMIT 200`  — stops as soon as 200 rows exist, so a frequent term is
                                  answered from the first graph or two
    distinct   `RETURN DISTINCT ... LIMIT 200` — must keep going until 200 *distinct*
                                  rows exist
    counted    `RETURN count(*)` — no limit at all: every graph, every record
"""
import argparse, collections, hashlib, http.client, json, math, re, statistics, sys, time, urllib.parse

FOUR = "n.caller_class, n.caller_name, n.callee_class, n.callee_name"


class Client:
    def __init__(self, base, timeout):
        u = urllib.parse.urlparse(base)
        self.host, self.port, self.timeout = u.hostname, u.port or 80, timeout
        self.conn = None

    def post(self, query):
        body = json.dumps({"query": query}).encode()
        headers = {"Content-Type": "application/json", "Connection": "keep-alive"}
        for attempt in (0, 1):
            if self.conn is None:
                self.conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
            started = time.perf_counter()
            try:
                self.conn.request("POST", "/api/cypher", body=body, headers=headers)
                r = self.conn.getresponse()
                data = r.read()
                return (time.perf_counter() - started) * 1000.0, r.status, data
            except Exception as exc:
                try:
                    self.conn.close()
                except Exception:
                    pass
                self.conn = None
                if attempt == 1 or isinstance(exc, TimeoutError):
                    return ((time.perf_counter() - started) * 1000.0,
                            f"FAILED:{type(exc).__name__}", b"")
        return 0.0, "FAILED", b""


def esc(t):
    return t.replace("\\", "\\\\").replace('"', '\\"')


def predicate(t):
    t = esc(t)
    return (f'n.caller_class CONTAINS "{t}" OR n.callee_class CONTAINS "{t}" '
            f'OR n.caller_name CONTAINS "{t}" OR n.callee_name CONTAINS "{t}"')


def shapes(t):
    p = predicate(t)
    return [
        ("limited", f"MATCH (n) WHERE {p} RETURN n.graphId, {FOUR} LIMIT 200"),
        ("distinct", f"MATCH (n) WHERE {p} RETURN DISTINCT {FOUR} LIMIT 200"),
        ("counted", f"MATCH (n) WHERE {p} RETURN count(*)"),
    ]


def digest(payload):
    try:
        d = json.loads(payload)
    except Exception:
        return "unparseable", None
    rows = d.get("rows")
    if rows is None:
        return "error:" + str(d.get("error"))[:40], None
    h = hashlib.sha256()
    h.update(json.dumps(d.get("columns", []), sort_keys=True).encode())
    for row in rows:
        h.update(json.dumps(row, sort_keys=True).encode())
    return h.hexdigest()[:16], len(rows)


def percentile(values, fraction):
    s = sorted(values)
    return s[max(0, math.ceil(len(s) * fraction) - 1)]


def hot_terms(client, wanted):
    """The most frequent identifier fragments, counted over a sample of the properties."""
    counts = collections.Counter()
    for prop in ("caller_class", "callee_class", "caller_name", "callee_name"):
        _, _, payload = client.post(
            f"MATCH (n:CallSite) RETURN n.{prop} AS v LIMIT 20000"
        )
        try:
            rows = json.loads(payload).get("rows", [])
        except Exception:
            rows = []
        for row in rows:
            v = row.get("v")
            if not isinstance(v, str):
                continue
            for part in re.split(r"[.$/]", v):
                for w in re.findall(r"[A-Z]?[a-z]{3,}|[A-Z]{3,}", part):
                    counts[w] += 1
    return [w for w, _ in counts.most_common(wanted)], counts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--label", required=True)
    ap.add_argument("--terms", type=int, default=20)
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--out")
    args = ap.parse_args()

    client = Client(args.base, args.timeout)
    terms, counts = hot_terms(client, args.terms)
    if not terms:
        print("no terms sampled; is the server loaded?", file=sys.stderr)
        return 2
    print(f"{args.label}: top {len(terms)} terms — "
          + ", ".join(f"{t}({counts[t]})" for t in terms[:8]) + " ...", flush=True)

    # Warm page cache and JIT on terms that are not the ones measured.
    for t in terms[-3:]:
        for _, q in shapes(t):
            client.post(q)

    rows = []
    for t in terms:
        for shape, q in shapes(t):
            ms, status, payload = client.post(q)
            d, n = digest(payload)
            rows.append({"term": t, "shape": shape, "ms": round(ms, 1),
                         "status": status, "rows": n, "digest": d})
            print(f"  {t:20s} {shape:9s} {ms:9.1f}ms  {status}  rows={n}", flush=True)

    by_shape = collections.defaultdict(list)
    for r in rows:
        by_shape[r["shape"]].append(r["ms"])
    summary = {"label": args.label, "terms": len(terms)}
    for shape, lat in by_shape.items():
        summary[f"{shape}_p50_ms"] = round(statistics.median(lat), 2)
        summary[f"{shape}_p95_ms"] = round(percentile(lat, 0.95), 2)
    lat = [r["ms"] for r in rows]
    summary["p50_ms"] = round(statistics.median(lat), 2)
    summary["p95_ms"] = round(percentile(lat, 0.95), 2)
    summary["failures"] = sum(1 for r in rows if not isinstance(r["status"], int))
    print(json.dumps(summary, indent=2))
    if args.out:
        json.dump({"summary": summary, "queries": rows}, open(args.out, "w"), indent=2)
    return 0


sys.exit(main())
