# Ordinary raw projection: functional integration

This integrates main's ordinary direct-string projection behavior onto
`2e2d8b52`, retaining the cancellation checkpoint and mapped Node cursor changes.
It adds the ordinary planner/executor, source dispatch and consumption boundaries,
main-compatible string/node/row cache lifecycle, typed consumed string-table
errors and real prepared-index persistence on Store.Close. It is a functional
completion; the performance diagnostic below is not a separate optimizer or
proof of the overall latency goal.

The original 1,048-case corpus improves from 739 to **966 complete main matches**.
All 580 original indexed DISTINCT-eligible cases and 122 ordinary-eligible cases
match. The remaining 82 are 58 main-success/native-error, 20 differing errors and
4 differing successful results; they are retained without denominator changes.
The separate 144-case Annotation corpus has 84 eligible and 48 declined matches,
with 12 declined generic differences. No universal compatibility claim is made.

Important behavior includes retained-index versus mapped-view state, serial and
rolling parallel source consumption, first required failure and task joins,
negative/oversized CallSite count consumption, branch-specific valid wrong tags,
scoped source selection, repeated-field byte accounting and cache eviction.
Three cache types preserve main's key ordering, UTF16 accounting and publication
boundaries. Close writes an actual compatible sidecar from a completed prepared
index under the existing lifetime lock. Ordinary projection's special full-node
decoder does not globally relax Node/REST decoding.

Independent review found and fixed one cache-key bug: canonical UTF8 and paired
WTF8 representations of the same Java UTF16 term produced different keys. The
local correction preserves lone surrogates, original values/outputs, alias reuse
and repeated projection fields. Eight actual-main history steps, including exact
retained byte counts, pass; the failing-before output is preserved. Independently
generated native sidecars were read by pinned main, with two files and four full
subsequent responses validated. Sources, exact patch identities, all commands,
known remaining boundaries and raw observations are under `provider/` and
`independent/`. Compiled Java classes remain external with verified hashes in
`copy-receipt.json`; their source and generation commands are retained.

The combined ordinary/Attempt12/Attempt13 worktree passed full-module race/vet.
All 2,095 module files remained unchanged through testing and match root after
integration. Compared with independent ordinary output, 33 of 34 whole artifacts
are identical. The rolling artifact has all 48 complete responses equal; only
24 speculative mapped-view booleans in 40-source diagnostic before/after state
differ. Raw states and every changed path are retained. The provider's three JVM
runs already demonstrated speculative state variability while all responses
remained equal. This is not a claim of identical concurrent initialization.

The earlier root integration also remains archived: its collector first missed
the two preserved Attempt12 test constructor changes, then incorrectly demanded
byte-identical speculative diagnostic state. Those assertions and exact diffs
are preserved, not relabelled as production failures. The final source differences
are exactly those test constructors and the Attempt13 mapped-cursor hunks.

## Real64 correctness and diagnostic

Independent actual shipping verification passes **42/42 HTTP 200**, complete
type-sensitive main bodies, protocol headers and all 64 catalog entries/statistics.
The clean binary includes ordinary plus Attempt12/13, with no generic synchronous
candidate, exact-tuple implementation or profiling helper. Binary SHA256:
`1cdc0bbfb7e59e93d12fface1e801450ef75f9c866c83acdc7946c392c3614b9`.
All 232 source/embed hashes equal the final root files. Default 60-second timeout
and capacity4; profiling environment unset. PID 71214 exited normally on SIGTERM
with status143, no forced kill, and port18862 was released.

Both profile and HTTP runs used isolated copy-on-write copies of the complete
real64 fixture. Close/persistence was not disabled. All 1,152 original graph-file
hashes and catalog counts were checked before each profile; original and cloned
graph files remained unchanged after both processes. HTTP separately checked
1,152 graph files plus two root TSVs, before and after, with no changes on either
tree. The original frozen graphs were never referenced by the candidate server
for writes. Initial GNU cp rejection of the macOS clone flag is recorded; explicit
`/bin/cp` succeeded before any runtime was started.

The five-query sequence is two prefix queries, two dense DISTINCT queries, then
ordinary projection. All five full bodies equal pinned main and each other.

| Query | Execute + marshal seconds, base → candidate | Allocation bytes, base → candidate | Process CPU seconds |
|---|---:|---:|---:|
| Prefix first | 18.688 → 18.567 | 9,102,927,064 → 9,102,938,920 | 24.853 → 24.822 |
| Prefix repeat | 12.227 → 12.114 | 8,320,011,320 → 8,319,987,200 | 18.817 → 18.943 |
| Dense DISTINCT first | 4.467 → 4.105 | 5,804,045,408 → 5,804,039,896 | 23.659 → 22.679 |
| Dense DISTINCT repeat | 4.434 → 4.257 | 367,935,816 → 367,941,448 | 20.621 → 19.710 |
| Ordinary after that history | 11.974313 → 0.001842 | 5,474,297,528 → 1,632,032 | 11.514676 → 0.001873 |

The ordinary improvement is for this specific prewarmed history. It is not a cold
or isolated ordinary query, an HTTP P95 result, a universal speedup, or a latency
comparison against main. Other path differences are single-run observations.
Exact raw values are authoritative in `comparison.json`. CPU sampling is off;
getrusage supplies CPU and allocation counters are request deltas. Forced GC and
heap profiles occur outside request intervals. Background correctness/build and
HTTP fixture-hash I/O were present; no 64-graph execution overlapped another.
Go1.22.0, darwin/arm64, 16 CPUs, 64GB host. No peak-RSS claim is made.

Reproduce sequentially with new output directories:

```sh
python3 docs/go-server-baseline/native64-ordinary-projection-integration/run-profile.py --worktree /tmp/graphite-go-ordinary-base-2e2d8b52 --out /tmp/fresh-ordinary-base
python3 docs/go-server-baseline/native64-ordinary-projection-integration/run-profile.py --worktree /tmp/graphite-go-ordinary-root-2e2d8b52 --out /tmp/fresh-ordinary-candidate
```

Keep this functional correction with the explicit remaining differences.
Generic streaming/pagination, exact-tuple construction and lifecycle, global
budget/custom configuration, broader malformed decoding and runtime-Class order
remain separate work. Full parity, repeated main-relative 10× P95 and the required
benchmark-regression-gate have not been established.
