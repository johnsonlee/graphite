#!/usr/bin/env python3
"""A backtest-shaped load: many terms across the query templates a real one uses.

The templates are taken from a production backtest log — broad `CONTAINS` searches over
the four CallSite string properties, combined with `OR` and `AND`, usually projected
`DISTINCT`. The terms are sampled from the corpus itself so the mix spans selectivities
the way a real run does: some match a great deal, some a little, some nothing.

This exists because the earlier benchmarks each measured one narrow thing. `fixture64.py`
runs ten variants of a single disjunction; `and-shapes.py` runs ten conjunctions. Neither
produces a P50 over a realistic population, which is the number a backtest reports.

Servers are measured one at a time: both together do not fit in memory, and contention
would taint whichever is being timed.
"""
import argparse, hashlib, http.client, json, math, random, re, sys, time, urllib.parse

FOUR = "n.caller_class, n.caller_name, n.callee_class, n.callee_name"
ALIASED = ("n.caller_class AS callerClass, n.caller_name AS callerName, "
           "n.callee_class AS calleeClass, n.callee_name AS calleeName")


def esc(t):
    return t.replace("\\", "\\\\").replace('"', '\\"')


def wide_or(t):
    """The commonest shape: one term against all four properties."""
    t = esc(t)
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{t}" OR n.callee_class CONTAINS "{t}" '
            f'OR n.caller_name CONTAINS "{t}" OR n.callee_name CONTAINS "{t}" '
            f'RETURN n.graphId, {FOUR} LIMIT 200')


def class_pair_node(t):
    """The production log's commonest shape: one term against the two class
    properties, the whole node returned, a small LIMIT."""
    t = esc(t)
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{t}" OR n.callee_class CONTAINS "{t}" '
            f'RETURN n LIMIT 25')


def value_contains(t):
    """The production log's other common shape: a constant's value, whole node."""
    return f'MATCH (n) WHERE n.value CONTAINS "{esc(t)}" RETURN n LIMIT 25'


def two_term_or(a, b):
    a, b = esc(a), esc(b)
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{a}" OR n.callee_class CONTAINS "{a}" '
            f'OR n.caller_name CONTAINS "{b}" OR n.callee_name CONTAINS "{b}" '
            f'RETURN n.graphId, {FOUR} LIMIT 200')


def and_of_or(broad, alts):
    """`X AND (a OR b OR ...)` — a broad term narrowed by alternatives."""
    alt = " OR ".join(f'n.caller_class CONTAINS "{esc(a)}"' for a in alts)
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{esc(broad)}" AND ({alt}) '
            f'RETURN DISTINCT n.caller_class AS class LIMIT 100')


def or_and_or(a, b):
    a, b = esc(a), esc(b)
    return (f'MATCH (n) WHERE (n.caller_class CONTAINS "{a}" OR n.callee_class CONTAINS "{a}") '
            f'AND (n.caller_class CONTAINS "{b}" OR n.callee_class CONTAINS "{b}") '
            f'RETURN DISTINCT {ALIASED} LIMIT 120')


def and_two(a, b):
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{esc(a)}" '
            f'AND n.callee_class CONTAINS "{esc(b)}" RETURN n LIMIT 20')


def equality_or(cls):
    c = esc(cls)
    return (f'MATCH (n) WHERE n.caller_class = "{c}" OR n.callee_class = "{c}" '
            f'RETURN DISTINCT {ALIASED} LIMIT 160')


def distinct_or(t):
    t = esc(t)
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{t}" OR n.callee_class CONTAINS "{t}" '
            f'RETURN DISTINCT {ALIASED} LIMIT 200')


def keys_or(a, b):
    return (f'MATCH (n) WHERE n.caller_class CONTAINS "{esc(a)}" '
            f'OR n.caller_class CONTAINS "{esc(b)}" RETURN keys(n) AS keys LIMIT 5')


class Client:
    """One kept-alive connection, as a backtest client would use.

    Opening a TCP connection per query adds a handshake to every measurement. Both
    servers pay it equally, so it does not flip a comparison, but it is latency no real
    client incurs and it compresses the ratio between them.
    """

    def __init__(self, base, timeout):
        u = urllib.parse.urlparse(base)
        self.host, self.port, self.timeout = u.hostname, u.port or 80, timeout
        self.conn = None

    def _connect(self):
        self.conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)

    def post(self, path, payload):
        body = json.dumps(payload).encode()
        headers = {"Content-Type": "application/json", "Connection": "keep-alive"}
        for attempt in (0, 1):
            if self.conn is None:
                self._connect()
            started = time.perf_counter()
            try:
                self.conn.request("POST", path, body=body, headers=headers)
                r = self.conn.getresponse()
                data = r.read()
                return (time.perf_counter() - started) * 1000.0, r.status, data
            except Exception as exc:
                # A dropped keep-alive is retried once; anything else is the result.
                try:
                    self.conn.close()
                except Exception:
                    pass
                self.conn = None
                if attempt == 1 or isinstance(exc, TimeoutError):
                    return ((time.perf_counter() - started) * 1000.0,
                            f"FAILED:{type(exc).__name__}", b"")
        return 0.0, "FAILED:unreachable", b""


def call(client, query, _timeout=None):
    return client.post("/api/cypher", {"query": query})


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
    """`pressurePercentile` from benchmark-gate.mjs: the gate's own definition."""
    s = sorted(values)
    return s[max(0, math.ceil(len(s) * fraction) - 1)]


def sample_terms(client, timeout):
    """Identifier fragments drawn from the corpus, so selectivities are realistic."""
    _, _, payload = call(
        client,
        "MATCH (n:CallSite) RETURN DISTINCT n.caller_class AS c LIMIT 400",
    )
    classes = []
    try:
        for row in json.loads(payload).get("rows", []):
            v = row.get("c")
            if isinstance(v, str):
                classes.append(v)
    except Exception:
        pass
    words = set()
    for c in classes:
        for part in re.split(r"[.$]", c):
            for w in re.findall(r"[A-Z]?[a-z]{3,}|[A-Z]{3,}", part):
                words.add(w)
    return classes, sorted(words)


# Shape weights per 170 queries. `v1` is the original mix, all CallSite properties;
# `v2` follows the production log, where the commonest queries are one term against the
# two class properties and a constant's value, each returning the whole node.
MIXES = {
    "v1": [("wide-or", 60), ("two-term-or", 25), ("and-of-or", 25), ("or-and-or", 20),
           ("and-two", 15), ("equality-or", 10), ("distinct-or", 10), ("keys-or", 5)],
    "v2": [("class-pair-node", 50), ("value-contains", 40), ("wide-or", 30),
           ("two-term-or", 15), ("and-of-or", 10), ("or-and-or", 10), ("and-two", 5),
           ("equality-or", 5), ("distinct-or", 3), ("keys-or", 2)],
}


def build(classes, words, rng, mix="v2"):
    """170 queries, weighted by `mix`."""
    queries = []
    absent = ["__graphite_absent_a__", "__graphite_absent_b__"]
    pool = words + absent
    makers = {
        "wide-or": lambda: wide_or(rng.choice(pool)),
        "class-pair-node": lambda: class_pair_node(rng.choice(pool)),
        "value-contains": lambda: value_contains(rng.choice(pool)),
        "two-term-or": lambda: two_term_or(rng.choice(pool), rng.choice(pool)),
        "and-of-or": lambda: and_of_or(rng.choice(pool), [rng.choice(pool) for _ in range(5)]),
        "or-and-or": lambda: or_and_or(rng.choice(pool), rng.choice(pool)),
        "and-two": lambda: and_two(rng.choice(pool), rng.choice(pool)),
        "equality-or": lambda: equality_or(rng.choice(classes) if classes else "x"),
        "distinct-or": lambda: distinct_or(rng.choice(pool)),
        "keys-or": lambda: keys_or(rng.choice(pool), rng.choice(pool)),
    }
    for shape, weight in MIXES[mix]:
        for _ in range(weight):
            queries.append((shape, makers[shape]()))
    rng.shuffle(queries)
    return queries


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--label", required=True)
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--seed", type=int, default=20260912)
    # A different query set is run first, to warm page cache and JIT without letting the
    # measured queries hit any per-predicate result cache. Repeating the *same* set is
    # what a backtest never does, and it hands a caching server a result it would not
    # otherwise have.
    ap.add_argument("--warmup-seed", type=int, default=777)
    ap.add_argument("--mix", choices=sorted(MIXES), default="v2")
    ap.add_argument("--out")
    args = ap.parse_args()

    rng = random.Random(args.seed)
    client = Client(args.base, args.timeout)
    classes, words = sample_terms(client, args.timeout)
    if len(words) < 20:
        print(f"only {len(words)} terms sampled; is the server up and loaded?", file=sys.stderr)
        return 2
    for shape, q in build(classes, words, random.Random(args.warmup_seed), args.mix):
        call(client, q)
    queries = build(classes, words, rng, args.mix)
    print(f"{args.label}: {len(queries)} queries from {len(words)} sampled terms "
          f"(after a {len(queries)}-query warmup on a different seed)", flush=True)

    rows, timeouts = [], 0
    for i, (shape, q) in enumerate(queries):
        ms, status, payload = call(client, q)
        if not isinstance(status, int):
            timeouts += 1
        d, n = digest(payload)
        rows.append({"i": i, "shape": shape, "query": q, "ms": round(ms, 1),
                     "status": status, "rows": n, "digest": d})
        if (i + 1) % 25 == 0:
            print(f"  {i + 1}/{len(queries)}", flush=True)

    lat = [r["ms"] for r in rows]
    summary = {
        "label": args.label,
        "queries": len(rows),
        "p50_ms": round(percentile(lat, 0.50), 2),
        "p95_ms": round(percentile(lat, 0.95), 2),
        "max_ms": round(max(lat), 2),
        "failures": timeouts,
    }
    print(json.dumps(summary, indent=2))
    if args.out:
        json.dump({"summary": summary, "queries": rows}, open(args.out, "w"), indent=2)
    return 0


sys.exit(main())
