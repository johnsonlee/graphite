#!/usr/bin/env python3
"""Measure a cheap query's latency alone, then while N workers loop a heavy query."""
import argparse, json, threading, time, urllib.request, statistics
def req(base, q, timeout=120):
    body=json.dumps({"query":q,"timeoutMs":timeout*1000}).encode()
    r=urllib.request.Request(base+"/api/cypher",data=body,method="POST",headers={"Content-Type":"application/json"})
    t0=time.perf_counter()
    with urllib.request.urlopen(r,timeout=timeout) as resp: resp.read(); st=resp.status
    return (time.perf_counter()-t0)*1000, st
def nr(s,p):
    s=sorted(s); return s[max(1,int(-(-p*len(s)//1)))-1]
def measure(base,q,n):
    xs=[req(base,q)[0] for _ in range(n)]
    return nr(xs,.5), nr(xs,.95), max(xs)
ap=argparse.ArgumentParser(); ap.add_argument('--base',default='http://127.0.0.1:18080')
ap.add_argument('--cheap',required=True); ap.add_argument('--heavy',required=True)
ap.add_argument('--n',type=int,default=40); ap.add_argument('--heavy-workers',type=int,default=1)
a=ap.parse_args()
for _ in range(3): req(a.base,a.cheap); req(a.base,a.heavy)
alone=measure(a.base,a.cheap,a.n)
h_alone=measure(a.base,a.heavy,5)
stop=threading.Event(); heavy_lat=[]
def loop():
    while not stop.is_set(): heavy_lat.append(req(a.base,a.heavy)[0])
ts=[threading.Thread(target=loop) for _ in range(a.heavy_workers)]
for t in ts: t.start()
time.sleep(0.5)
under=measure(a.base,a.cheap,a.n)
stop.set()
for t in ts: t.join()
print(json.dumps({"cheap_alone_p50_p95_max":alone,"heavy_alone_p50_p95_max":h_alone,
 "cheap_under_load_p50_p95_max":under,"heavy_under_load_p50":nr(heavy_lat,.5) if heavy_lat else None,
 "heavy_requests":len(heavy_lat)}))
