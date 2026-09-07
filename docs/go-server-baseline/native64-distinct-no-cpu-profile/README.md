# Indexed DISTINCT control with CPU sampling disabled

The exact comparison is `bb961cd8` before indexed DISTINCT versus `f9dc0aac`
after it. Each revision runs in its own fresh process on the same full 64 real
persisted graphs. The two processes ran sequentially. Every one of the 1,152
fixture hashes and all graph catalog totals was checked again for each process.
All five complete output bodies match each other and pinned main.

The only diagnostic change removes CPU profile start/stop and its output file.
`harness-control.patch` retains the exact change from the Attempt8 harness.
Heap sampling, explicit GC outside each query, response serialization, complete
result validation and request `getrusage`/MemStats counters remain. Query order
and all production source files are unchanged. The runner's historical name and
PROFILE log markers do not mean CPU sampling was enabled.

| Query | Before allocation (bytes) | After allocation (bytes) | Before execute + marshal (s) | After (s) | Before CPU (s) | After CPU (s) |
|---|---:|---:|---:|---:|---:|---:|
| Prefix first | 11,136,073,792 | 11,136,049,824 | 20.317 | 20.309 | 25.940 | 25.975 |
| Prefix repeat | 8,549,685,448 | 8,549,704,104 | 11.485 | 11.507 | 18.144 | 18.007 |
| Dense DISTINCT first | 7,807,915,504 | 5,770,519,952 | 13.585 | 59.740 | 19.674 | 296.275 |
| Dense DISTINCT repeat | 6,405,969,320 | 334,421,632 | 8.427 | 53.414 | 15.062 | 250.703 |
| Ordinary dense after DISTINCT | 4,238,973,832 | 5,640,814,984 | 6.354 | 11.118 | 6.416 | 11.096 |

CPU is user + system time from `getrusage`, not the suspect sample totals.
The dense slowdown remains without CPU sampling. Lower allocation does not
establish a faster query. The prefix is a useful unchanged-path observation;
the ordinary query is affected by the preceding index/Store history and cannot
be treated as an isolated ordinary implementation regression.

This is one paired diagnostic run under recorded background correctness/build
work, not a quiet-host repeated HTTP/P95 benchmark. No other 64-graph performance
process overlapped. No query failure or response difference was omitted. Raw
receipts expose GC counts, heap snapshots and all counters; no peak-RSS or
retained-heap reduction is claimed. The feature remains required for functional
compatibility, but its performance regression must be resolved before the goal
can be accepted. The 10x main-relative P95 goal remains unproven.

`preparation.json` records revisions and harness hashes; each run's identity and
source manifest records its actual binary and sources. `verification.json`
checks all final source hashes and full paired outputs. Reproduce sequentially
with fresh detached checkouts of those revisions:

```sh
python3 docs/go-server-baseline/native64-distinct-no-cpu-profile/run-profile.py \
  --worktree /tmp/fresh-bb961cd8 --out /tmp/fresh-distinct-control-base
python3 docs/go-server-baseline/native64-distinct-no-cpu-profile/run-profile.py \
  --worktree /tmp/fresh-f9dc0aac --out /tmp/fresh-distinct-control-candidate
```
