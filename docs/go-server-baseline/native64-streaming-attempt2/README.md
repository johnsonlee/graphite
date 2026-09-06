# Attempt 2: filter single-node MATCH candidates while scanning

This experiment changes only a single-node, unnamed-path MATCH with a WHERE
clause. It evaluates each candidate before retaining a fresh binding row.
Relationship/path clauses keep the original matcher. Projection, aggregation,
DISTINCT, ordering and LIMIT retain their existing pipeline positions. Optional
misses preserve existing variables and null-fill only introduced variables.

The isolated base is `a7de0bec67ae4dbd8b1dde621b6d9ce7cecef8d0`.
`final-identity.json`, `final-candidate.patch` and `final-scan.go` identify the
profiled candidate exactly. The original broader variant (which also changed
MATCH without WHERE) is preserved in `identity.json`, `candidate.patch`, `scan.go`
and `profiles/`. The final variant restricts the fast path to WHERE; the
supplemental no-WHERE method count consequently stays on the baseline path.
Do not overwrite or relabel the earlier variant's observations.

All 64 real persisted graphs are loaded before query profiling: 19,431,891 nodes,
20,448,885 edges, 1,374,983 methods and 5,046,935 call sites. Fixture byte identities,
query configuration and the identical profile harness are recorded in
`../native64-profile-a7de0bec/`. The main baseline is pinned to
`4e328b0109e13c896b74004823fb049fcb19251a`.

| Query | Baseline allocation bytes | Candidate allocation bytes | Baseline diagnostic seconds | Candidate diagnostic seconds | Baseline / candidate user+system CPU seconds |
|---|---:|---:|---:|---:|---:|
| wrapped-zeroHitBroadContains | 38,142,226,112 | 20,227,526,272 | 34.495 | 25.212 | 82.136 / 38.447 |
| global-wide-four-properties-zero | 32,796,831,280 | 14,854,469,648 | 21.158 | 14.818 | 57.972 / 23.587 |
| supplemental-method-count | 2,441,487,384 | 2,441,487,512 | 0.683 | 0.752 | 0.765 / 0.748 |

The first two allocations fall by about 47% and 55%. Request-end live heap falls
from 36.50/31.32 GB to 9.16/9.58 GB, while all requests return to approximately
6.27 GB after forced GC. These are separate points in GC cycles, not measured
peak RSS. Runtime delta counters include profile bookkeeping; sampled heap
profiles are subtracted with `-base heap-before.pprof`. CPU profiles include only
execution and marshaling, not loading or the forced GC before/after each request.

GC drain accounts for 66.22% of baseline wrapped-query CPU samples and 28.15% in
the final candidate. These are cumulative profile proportions, not additive CPU
categories. Struct copying and clearing remain large costs: `runtime.memmove`
alone accounts for 35.68% of candidate wrapped samples. Lower allocations do not
remove the remaining expensive node boxing, decoding or expression evaluation.
The no-WHERE control allocation differs by only 128 bytes of bookkeeping; its
single wall-time observation does not establish a regression or speedup.

Each profiled query has complete output equality, not just a row-count check.
`full42/` additionally contains every full HTTP response from the final isolated
candidate compared with previously frozen main responses: **42/42 pass**, including
Content-Type and Retry-After checks, with all 64 catalog IDs and counts verified.
`replay.py` records the exact candidate launch and comparison. Unit tests cover
retained row/provenance independence, optional misses, existing bindings, global
sort-before-limit and anonymous patterns. Query/server race tests and query vet
passed on the isolated candidate.

Decision: retain this narrower streaming hypothesis for integration. It has a
verified real-corpus allocation reduction and complete fixed-workload response
parity. This is not a P95 experiment or a 10x result. Profiling and concurrent tiny
correctness/compilation work affect durations; these are single observations,
not controlled paired repetitions. The complete server's newer function/Unicode
work is outside the isolated profiled source and needs its own later full-corpus
verification. Production integration must preserve its binding-order helpers.
