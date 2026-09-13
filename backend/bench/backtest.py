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
import argparse
import collections, hashlib, http.client, json, math, random, re, sys, time, urllib.parse

FOUR = "n.caller_class, n.caller_name, n.callee_class, n.callee_name"
ALIASED = ("n.caller_class AS callerClass, n.caller_name AS callerName, "
           "n.callee_class AS calleeClass, n.callee_name AS calleeName")


def esc(t):
    return t.replace("\\", "\\\\").replace('"', '\\"')


def re_esc(t):
    """Quote a term for use inside a single-quoted regex literal."""
    return "".join("\\\\" + c if c in "\\.^$|?*+()[]{}" else c for c in t).replace("'", "\\'")


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


def regex_pair_node(t):
    """The repo's own pressure benchmark shape: a regex-contains against the two
    class properties, the whole node returned."""
    t = re_esc(t)
    return (f"MATCH (n) WHERE n.caller_class =~ '.*{t}.*' OR n.callee_class =~ '.*{t}.*' "
            f"RETURN n LIMIT 25")


def regex_prefix(t):
    """A package prefix as a regex, the way the explorer UI writes one."""
    return f"MATCH (n) WHERE n.callee_class =~ '{re_esc(t)}.*' RETURN n.callee_class LIMIT 25"


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


def engine_seconds(client):
    """Cumulative engine time from `/metrics`, summed over outcomes.

    The server's histogram times only the engine, so two scrapes around a request
    split its wall time into the engine and everything around it: HTTP, JSON, the
    body on the wire. `None` when the server exposes no such series."""
    try:
        if client.conn is None:
            client._connect()
        client.conn.request("GET", "/metrics")
        r = client.conn.getresponse()
        text = r.read().decode("utf-8", "replace")
    except Exception:
        return None
    total, seen = 0.0, False
    for line in text.splitlines():
        if line.startswith("graphite_cypher_query_duration_seconds_sum"):
            try:
                total += float(line.rsplit(" ", 1)[1])
                seen = True
            except ValueError:
                pass
    return total if seen else None


def warm_up(client, queries):
    """Run the warmup set, holding every response to the measured loop's rule.

    A warmup that was rejected or failed did not warm anything, and a run measured
    behind it would include the cold work the warmup was meant to absorb. Returns the
    failures by status; the caller fails the run on any."""
    failures = collections.Counter()
    for _, q in queries:
        _, status, payload = call(client, q)
        _, n = digest(payload)
        if not (status == 200 and n is not None):
            failures[str(status)] += 1
    return failures


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
    # v2 with a third of the class-pair and value shapes written as `=~` patterns.
    "v3": [("class-pair-node", 30), ("regex-pair-node", 20), ("value-contains", 30),
           ("regex-prefix", 10), ("wide-or", 30), ("two-term-or", 15), ("and-of-or", 10),
           ("or-and-or", 10), ("and-two", 5), ("equality-or", 5), ("distinct-or", 3),
           ("keys-or", 2)],
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
        "regex-pair-node": lambda: regex_pair_node(rng.choice(pool)),
        "regex-prefix": lambda: regex_prefix(rng.choice(classes).rsplit(".", 1)[0] if classes else "x"),
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


def main(argv=None, client_factory=None):
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
    ap.add_argument("--split", action="store_true",
                    help="scrape /metrics around each query to split engine time from "
                         "the rest, and record the response body size")
    ap.add_argument("--out")
    args = ap.parse_args(argv)

    rng = random.Random(args.seed)
    client = (client_factory or Client)(args.base, args.timeout)
    classes, words = sample_terms(client, args.timeout)
    if len(words) < 20:
        print(f"only {len(words)} terms sampled; is the server up and loaded?", file=sys.stderr)
        return 2
    warmup = build(classes, words, random.Random(args.warmup_seed), args.mix)
    warmup_failures = warm_up(client, warmup)
    if warmup_failures:
        print(f"{args.label}: {sum(warmup_failures.values())} of {len(warmup)} warmup queries "
              f"failed ({dict(warmup_failures)}); the server is not warm, nothing measured",
              file=sys.stderr)
        return 2
    queries = build(classes, words, rng, args.mix)
    print(f"{args.label}: {len(queries)} queries from {len(words)} sampled terms "
          f"(after a {len(queries)}-query warmup on a different seed)", flush=True)

    # A query counts only when it succeeded: HTTP 200 and a parseable body with rows.
    # A rejected or failed request answers fast, and a fast failure inside the latency
    # distribution would flatter the server that failed; a slow one would smear its
    # timeouts over the percentiles. Failures are counted and reported separately, and
    # a run with any of them exits non-zero, so its numbers are never quoted unnoticed.
    rows, failures = [], collections.Counter()
    if args.split and engine_seconds(client) is None:
        print("--split needs a server exposing graphite_cypher_query_duration_seconds "
              "on /metrics (start it with --metrics)", file=sys.stderr)
        return 2
    for i, (shape, q) in enumerate(queries):
        before = engine_seconds(client) if args.split else None
        ms, status, payload = call(client, q)
        after = engine_seconds(client) if args.split else None
        d, n = digest(payload)
        ok = status == 200 and n is not None
        if not ok:
            failures[str(status)] += 1
        row = {"i": i, "shape": shape, "query": q, "ms": round(ms, 1),
               "status": status, "ok": ok, "rows": n, "digest": d}
        if args.split:
            engine = (after - before) * 1000.0 if before is not None and after is not None else None
            row["engine_ms"] = round(engine, 3) if engine is not None else None
            row["outside_ms"] = round(ms - engine, 3) if engine is not None else None
            row["bytes"] = len(payload)
        rows.append(row)
        if (i + 1) % 25 == 0:
            print(f"  {i + 1}/{len(queries)}", flush=True)

    lat = [r["ms"] for r in rows if r["ok"]]
    summary = {
        "label": args.label,
        "mix": args.mix,
        "queries": len(rows),
        "succeeded": len(lat),
        "failures": sum(failures.values()),
        "failure_statuses": dict(failures),
        "p50_ms": round(percentile(lat, 0.50), 2) if lat else None,
        "p95_ms": round(percentile(lat, 0.95), 2) if lat else None,
        "max_ms": round(max(lat), 2) if lat else None,
    }
    if args.split:
        ok_rows = [r for r in rows if r["ok"] and r.get("engine_ms") is not None]
        for key in ("engine_ms", "outside_ms", "bytes"):
            vals = [r[key] for r in ok_rows]
            summary[f"p50_{key}"] = round(percentile(vals, 0.50), 2) if vals else None
            summary[f"p95_{key}"] = round(percentile(vals, 0.95), 2) if vals else None
    print(json.dumps(summary, indent=2))
    if args.out:
        json.dump({"summary": summary, "queries": rows}, open(args.out, "w"), indent=2)
    if failures:
        print(f"{args.label}: {sum(failures.values())} of {len(rows)} queries failed "
              f"({dict(failures)}); the percentiles above cover the successes only",
              file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
