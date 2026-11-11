#!/usr/bin/env python3
"""Aggregate gdb 'thread apply all bt' samples: busy threads only, top frames and leaf frames."""
import sys, re, collections
text=open(sys.argv[1]).read()
samples=text.split("=== SAMPLE END ===")
leaf=collections.Counter(); inclusive=collections.Counter(); busy=0; total_threads=0
IDLE=re.compile(r'epoll_wait|futex|__GI___clock_nanosleep|park|pthread_cond|syscall|accept4|recvfrom|sched_yield|poll \(|__futex_abstimed')
for s in samples:
    for th in re.split(r'\nThread \d+ \(', s)[1:]:
        total_threads+=1
        frames=re.findall(r'#\d+\s+(?:0x[0-9a-f]+ in )?([^\n]+?) \(', th)
        frames=[f for f in frames]
        if not frames or IDLE.search(th.split('\n')[1] if len(th.split('\n'))>1 else th) or IDLE.search(' '.join(frames[:3])): continue
        busy+=1
        g=[f for f in frames if 'graphite' in f or 'cypher' in f or 'storage' in f or 'rayon' in f or 'std::' in f or 'core::' in f or 'alloc::' in f or 'hashbrown' in f][:40]
        fr=[f.split('::')[-3:] for f in g]
        leaf[frames[0][:110]]+=1
        seen=set()
        for f in g:
            k=f[:120]
            if k in seen: continue
            seen.add(k); inclusive[k]+=1
print(f"samples={len(samples)-1} busy_thread_samples={busy}")
print("\n--- leaf frames ---")
for f,c in leaf.most_common(25): print(f"{c:5d} {f}")
print("\n--- inclusive (graphite frames) ---")
for f,c in inclusive.most_common(45): print(f"{c:5d} {f}")
