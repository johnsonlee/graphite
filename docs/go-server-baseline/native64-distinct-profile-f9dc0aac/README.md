# Real64 diagnostic after indexed DISTINCT integration

Revision `f9dc0aac1c1026797cc749f6ba8901ca6a0e0267` uses the unchanged five-query
Attempt8 diagnostic harness, including the production HTTP response serializer.
All 64 graph catalogs and 1,152 file hashes were freshly verified. All five
complete outputs match pinned main. Source, binary, commands, raw profiles,
request counters and complete responses are retained here.

Compared with the historical Attempt8 candidate, the prefix path remains near
11.14 GB first / 8.55 GB repeat because `n.graph_id` makes the raw planner decline.
The eligible dense DISTINCT path allocates less but takes substantially longer.

| Query | Attempt8 allocation (bytes) | Current allocation (bytes) | Attempt8 execute + marshal (s) | Current (s) |
|---|---:|---:|---:|---:|
| Prefix first | 11,136,246,944 | 11,136,252,984 | 22.931 | 20.279 |
| Prefix repeat | 8,549,794,336 | 8,549,768,928 | 11.946 | 11.451 |
| Dense DISTINCT first | 7,807,821,560 | 5,770,613,992 | 13.424 | 53.824 |
| Dense DISTINCT repeat | 6,406,013,408 | 334,507,096 | 8.461 | 57.004 |
| Ordinary dense, after DISTINCT | 4,238,998,704 | 5,640,685,688 | 5.985 | 11.310 |

The ordinary query sees a different prior Store/index history; this is not an
isolated ordinary-projection code comparison. These are single instrumented
native-to-native observations with host co-tenancy, not HTTP, P95, peak RSS or
main-relative acceptance. This was a diagnostic of a compatibility feature,
not a successful optimization experiment.

The dense CPU profiles are quantitatively suspect: the first claims 3,280.95
sample seconds in 53.94 wall seconds on 16 CPUs, versus 259.43 CPU seconds from
the request's `getrusage` counters. The repeat has a similar inconsistency.
`cpu-sample-anomaly.json` retains the numerical check. Do not interpret the large
`runtime.usleep` percentage as a measured CPU cause. The profiles are preserved
without normalization. One completed profile was inspected while the repeat
query was running; `early-cpu-inspection.json` records that small additional work.

The follow-up in `../native64-distinct-no-cpu-profile/` disables CPU sampling and
repeats the same five queries on immediately preceding revision `bb961cd8` and
this revision. It independently reproduces the dense slowdown, so sampling alone
does not explain it. Repeated target-string lookup and cancellation checks are
next hypotheses to test, not established causes from this profile.

Reproduce with a fresh detached checkout and a fresh output directory:

```sh
python3 docs/go-server-baseline/native64-ascii-lower-attempt8/run-profile.py \
  --worktree /tmp/fresh-f9dc0aac --out /tmp/fresh-distinct-profile
```
