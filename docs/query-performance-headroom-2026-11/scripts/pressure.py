#!/usr/bin/env python3
"""Local replica of the benchmark controller's real64 pressure protocol (diagnostic only).

Stages: first-use (1 request per case, sequential), warmup (2 per case, 4 workers),
pressure (20 per case, 4 workers, interleaved by round), isolated (N per case, 1 worker).
Latency ends after the complete HTTP body is read. Nearest-rank percentiles per case.
"""
import argparse, json, sys, time, threading, queue, statistics, urllib.request, urllib.error, re

def load_catalog(manifest, native_queries):
    graphs = []; dists = []
    for line in open(manifest):
        line = line.rstrip('\n')
        if not line.strip(): continue
        f = line.split('\t')
        if line.startswith('#'):
            if f[0] == '# global-wide-distribution-v1':
                dists.append((f[1], f[3]))
            continue
        graphs.append({'id': f[0], 'path': f[1], 'terms': f[2:5]})
    FOUR = "n.caller_class, n.caller_name, n.callee_class, n.callee_name"
    def quoted(v): return v.replace('\\', '\\\\').replace("'", "\\'")
    def predicate(term, wrapped):
        t = quoted(term.lower() if wrapped else term)
        return ' OR '.join((f"toLower(coalesce(n.{p}, '')) CONTAINS '{t}'" if wrapped else f"n.{p} CONTAINS '{t}'")
                           for p in ["caller_class","caller_name","callee_class","callee_name"])
    def wide(term, proj, wrapped, distinct):
        return f"MATCH (n)\nWHERE {predicate(term, wrapped)}\nRETURN {'DISTINCT ' if distinct else ''}{proj}\nLIMIT 200"
    shapes = [("four-properties", FOUR), ("class-pair", "n.caller_class, n.callee_class"),
              ("name-pair", "n.caller_name, n.callee_name"), ("caller-class", "n.caller_class"),
              ("callee-class", "n.callee_class"), ("provenance", "n.graphId, n.caller_class, n.caller_name, n.callee_class, n.callee_name"),
              ("aliased", "n.caller_class AS caller, n.caller_name AS callerMethod, n.callee_class AS callee, n.callee_name AS calleeMethod"),
              ("parameterized-as-literal", FOUR), ("wrapped-case-insensitive", FOUR), ("wrapped-case-insensitive-distinct", FOUR)]
    cases = []
    for sel, term in zip(["zero","targeted","dense"], graphs[0]['terms']):
        for name, proj in shapes:
            cases.append((f"{sel}/global-wide-{name}", wide(term, proj, name.startswith("wrapped"), name.endswith("distinct"))))
    for name, term in dists:
        cases.append((f"{name}/global-wide-four-properties", wide(term, FOUR, False, False)))
    for cid, q in json.load(open(native_queries)):
        cases.append((cid, q))
    assert len(cases) == 73, len(cases)
    return graphs, cases

def request(base, query, timeout_ms):
    body = json.dumps({"query": query, "timeoutMs": timeout_ms}).encode()
    req = urllib.request.Request(base + "/api/cypher", data=body, method="POST", headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout_ms/1000+5) as r:
            data = r.read(); status = r.status
    except urllib.error.HTTPError as e:
        data = e.read(); status = e.code
    ms = (time.perf_counter() - t0) * 1000.0
    return ms, status, len(data)

def nearest_rank(samples, p):
    s = sorted(samples); n = len(s)
    if n == 0: return None
    k = max(1, int(-(-p * n // 1)))  # ceil
    return s[k - 1]

def stage(base, cases, tasks, workers, timeout_ms, label):
    q = queue.Queue()
    for t in tasks: q.put(t)
    out = {cid: [] for cid, _ in cases}
    errors = []
    lock = threading.Lock()
    def worker():
        while True:
            try: i = q.get_nowait()
            except queue.Empty: return
            cid, query = cases[i]
            ms, status, nbytes = request(base, query, timeout_ms)
            with lock:
                out[cid].append((ms, status, nbytes))
                if status != 200: errors.append((label, cid, status))
    threads = [threading.Thread(target=worker) for _ in range(workers)]
    t0 = time.perf_counter()
    for t in threads: t.start()
    for t in threads: t.join()
    return out, errors, time.perf_counter() - t0

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default='http://127.0.0.1:18080')
    ap.add_argument('--manifest', required=True)
    ap.add_argument('--native-queries', required=True)
    ap.add_argument('--only', help='regex on case id')
    ap.add_argument('--pressure', type=int, default=20)
    ap.add_argument('--warmup', type=int, default=2)
    ap.add_argument('--isolated', type=int, default=40)
    ap.add_argument('--workers', type=int, default=4)
    ap.add_argument('--timeout-ms', type=int, default=300000)
    ap.add_argument('--skip-first-use', action='store_true')
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    graphs, cases = load_catalog(a.manifest, a.native_queries)
    if a.only:
        cases = [c for c in cases if re.search(a.only, c[0])]
    n = len(cases)
    result = {'cases': {cid: {'query': q} for cid, q in cases}, 'stages': {}}
    def record(name, out, errors, wall):
        result['stages'][name] = {'wall_s': wall, 'errors': errors}
        for cid, samples in out.items():
            ms = [m for m, s, b in samples if s == 200]
            result['cases'][cid][name] = {'n': len(ms), 'p50': nearest_rank(ms, .5), 'p95': nearest_rank(ms, .95),
                                          'min': min(ms) if ms else None, 'max': max(ms) if ms else None,
                                          'mean': statistics.fmean(ms) if ms else None,
                                          'bytes': samples[0][2] if samples else None, 'samples': ms}
        print(f"[{name}] wall {wall:.1f}s errors {len(errors)}", file=sys.stderr)
    if not a.skip_first_use:
        record('first_use', *stage(a.base, cases, list(range(n)), 1, a.timeout_ms, 'first_use'))
    if a.warmup:
        record('warmup', *stage(a.base, cases, [i for _ in range(a.warmup) for i in range(n)], a.workers, a.timeout_ms, 'warmup'))
    if a.pressure:
        record('pressure', *stage(a.base, cases, [i for _ in range(a.pressure) for i in range(n)], a.workers, a.timeout_ms, 'pressure'))
    if a.isolated:
        record('isolated', *stage(a.base, cases, [i for _ in range(a.isolated) for i in range(n)], 1, a.timeout_ms, 'isolated'))
    json.dump(result, open(a.out, 'w'), indent=1)
    # summary table sorted by pressure p50
    rows = []
    for cid, c in result['cases'].items():
        p = c.get('pressure', {}); i = c.get('isolated', {}); f = c.get('first_use', {})
        rows.append((p.get('p50') or 0, cid, p.get('p50'), p.get('p95'), i.get('p50'), i.get('p95'), f.get('p50'), p.get('bytes')))
    fmt = lambda v: '-' if v is None else f"{v:.3f}"
    print("case\tpress_p50\tpress_p95\tiso_p50\tiso_p95\tfirst_use\tbytes")
    for _, cid, pp50, pp95, ip50, ip95, fu, b in sorted(rows, reverse=True):
        print(f"{cid}\t{fmt(pp50)}\t{fmt(pp95)}\t{fmt(ip50)}\t{fmt(ip95)}\t{fmt(fu)}\t{b}")

if __name__ == '__main__':
    main()
