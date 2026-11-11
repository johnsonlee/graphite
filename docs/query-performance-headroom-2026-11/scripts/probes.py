#!/usr/bin/env python3
"""Production-shaped probes outside the 73-case catalog: first-use then warm medians (single client)."""
import json, time, urllib.request, urllib.error, sys
import os; BASE=os.environ.get('GRAPHITE_BASE','http://127.0.0.1:18080')
def req(q, timeout=120000):
    body=json.dumps({"query":q,"timeoutMs":timeout}).encode()
    r=urllib.request.Request(BASE+"/api/cypher",data=body,method="POST",headers={"Content-Type":"application/json"})
    t0=time.perf_counter()
    try:
        with urllib.request.urlopen(r,timeout=timeout/1000+5) as resp: data=resp.read(); st=resp.status
    except urllib.error.HTTPError as e: data=e.read(); st=e.code
    ms=(time.perf_counter()-t0)*1000
    try:
        j=json.loads(data); rows=len(j.get('rows',j.get('data',[])) or []) if isinstance(j,dict) else None
    except Exception: rows=None
    return ms, st, len(data), rows
PROBES=[
 ("http-floor","RETURN 1"),
 ("value-contains-node","MATCH (n) WHERE n.value CONTAINS 'Spooler' RETURN n LIMIT 25"),
 ("value-contains-http","MATCH (n) WHERE n.value CONTAINS 'http' RETURN n LIMIT 25"),
 ("value-contains-absent","MATCH (n) WHERE n.value CONTAINS 'zzqqxxvv' RETURN n LIMIT 25"),
 ("value-contains-count","MATCH (n) WHERE n.value CONTAINS 'http' RETURN count(*)"),
 ("class-pair-node","MATCH (n) WHERE n.caller_class CONTAINS 'Spooler' OR n.callee_class CONTAINS 'Spooler' RETURN n LIMIT 25"),
 ("all-props-limit","MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'Spooler') RETURN n LIMIT 25"),
 ("all-props-absent","MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'zzqqxxvv') RETURN n LIMIT 25"),
 ("all-props-count","MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'Spooler') RETURN count(*)"),
 ("numeric-eq-limit","MATCH (n) WHERE n.value = 105873 RETURN n LIMIT 25"),
 ("numeric-eq-count","MATCH (n) WHERE n.value = 105873 RETURN count(*)"),
 ("qualifiedId-contains","MATCH (n) WHERE n.qualifiedId CONTAINS '1234' RETURN n LIMIT 25"),
 ("hop-callee-contains","MATCH (c)-[r:DATAFLOW]->(n) WHERE n.callee_class CONTAINS 'Spooler' RETURN c, n LIMIT 25"),
 ("hop-callee-count","MATCH (c)-[r:DATAFLOW]->(n) WHERE n.callee_class CONTAINS 'Spooler' RETURN count(*)"),
 ("string-seed-paths","MATCH (s:StringConstant)-[*1..3]-(n) WHERE s.value CONTAINS 'Spooler' RETURN n LIMIT 25"),
 ("explorer-expand","MATCH (n {graphId: 'fixture-android-00', id: 4101})-[r]-(m) RETURN m LIMIT 50"),
 ("method-signature-contains","MATCH (m:Method) WHERE m.signature CONTAINS 'parse' RETURN m LIMIT 25"),
 ("method-count","MATCH (m:Method) RETURN count(*)"),
 ("local-type-count","MATCH (n:LocalVariable) WHERE n.type CONTAINS 'String' RETURN count(*)"),
 ("filtered-group-count","MATCH (n:CallSite) WHERE n.callee_name CONTAINS 'valueOf' RETURN n.callee_class, count(*) AS c ORDER BY c DESC LIMIT 20"),
 ("eq-count","MATCH (n:CallSite) WHERE n.callee_class = 'java.lang.String' RETURN count(*)"),
 ("typed-hop-count","MATCH (a:CallSite)-[r]->(b) RETURN count(*)"),
 ("dataflow-count","MATCH (a)-[r:DATAFLOW]->(b) RETURN count(*)"),
 ("caller-name-group","MATCH (n:CallSite) RETURN n.caller_name, count(*) AS c ORDER BY c DESC LIMIT 20"),
 ("group-all-rows","MATCH (n:CallSite) RETURN n.callee_class, count(*) AS c ORDER BY c DESC LIMIT 65536"),
 ("group-no-order","MATCH (n:CallSite) RETURN n.callee_class, count(*) AS c"),
 ("distinct-5000","MATCH (n:CallSite) RETURN DISTINCT n.callee_class LIMIT 5000"),
 ("label-histogram-with","MATCH (n) WITH labels(n) AS l, count(*) AS c RETURN l, c ORDER BY c DESC"),
 ("two-hop-count","MATCH (a:CallSite)-[r]->(b)-[s]->(c) RETURN count(*)"),
 ("var-length-count","MATCH (a:StringConstant)-[*1..2]->(b) RETURN count(*)"),
 ("order-by-line","MATCH (n:CallSite) RETURN n.caller_class, n.line ORDER BY n.line DESC LIMIT 200"),
 ("order-by-two-keys","MATCH (n:CallSite) RETURN n.caller_class, n.callee_name ORDER BY n.caller_class, n.callee_name LIMIT 200"),
 ("with-filter-size","MATCH (n:CallSite) WITH n.callee_class AS c WHERE size(c) > 40 RETURN c LIMIT 200"),
 ("collect-distinct","MATCH (n:CallSite) WITH collect(DISTINCT n.callee_name) AS names RETURN size(names)"),
 ("count-distinct","MATCH (n:CallSite) RETURN count(DISTINCT n.callee_class)"),
 ("min-max-line","MATCH (n:CallSite) RETURN min(n.line), max(n.line)"),
 ("sum-int-constants","MATCH (n:IntConstant) RETURN count(*), sum(n.value)"),
]
only=sys.argv[1:]
print("probe\tfirst_ms\twarm_p50_ms(5)\tstatus\tbytes\trows")
for name,q in PROBES:
    if only and name not in only: continue
    f=req(q, 60000)
    n = 5 if f[0] < 3000 else (2 if f[0] < 20000 else 1)
    warm=sorted(req(q, 60000)[0] for _ in range(n))
    print(f"{name}\t{f[0]:.1f}\t{warm[len(warm)//2]:.1f}\t{f[1]}\t{f[2]}\t{f[3]}\t(warm n={n})", flush=True)
