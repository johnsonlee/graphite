#!/usr/bin/env python3
import json, time, urllib.request, urllib.error, sys
BASE='http://127.0.0.1:18080'
def req(q, timeout=60000):
    body=json.dumps({"query":q,"timeoutMs":timeout}).encode()
    r=urllib.request.Request(BASE+"/api/cypher",data=body,method="POST",headers={"Content-Type":"application/json"})
    t0=time.perf_counter()
    try:
        with urllib.request.urlopen(r,timeout=timeout/1000+5) as resp: data=resp.read(); st=resp.status
    except urllib.error.HTTPError as e: data=e.read(); st=e.code
    return (time.perf_counter()-t0)*1000, st, data[:200]
labels=sys.argv[1:]
print("label\tms\tstatus\tbody")
for l in labels:
    q=f"MATCH (n:{l}) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'Spooler') RETURN count(*)"
    ms,st,b=req(q)
    print(f"{l}\t{ms:.1f}\t{st}\t{b.decode(errors='replace')[:120]}", flush=True)
