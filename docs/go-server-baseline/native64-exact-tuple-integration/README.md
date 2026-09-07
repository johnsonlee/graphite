# Exact projection tuple functional integration

This integrates main's retained four-field tuple table onto `06321518`. It is a
functional completion, with an explicit first-construction cost. It does not
replace the overall compatibility or main-relative P95 acceptance criteria.

The source delta is the independently reviewed91-file patch
`1cec41089f4e897c110822ff7c11bfb44bb2d3cfca7ea6cc398c3eefda98de59`,
from ordinary treee348726c. Seven production files implement real hash/node
arrays, full four-SID construction in first-CSR order, Java UTF16 hash and
linear-probe behavior, SID-based deduplication, projection admission/probing,
publication and cache lifetime. Construction occurs before checking the exact
four-property projection shape. It may therefore consume a malformed later field
even when projection ultimately falls back. A fake byte reservation or cached
result would not reproduce that behavior.

Construction is private until successful completion. Cancellation/failure remains
retryable; query-cache clear preserves the table, while Close clears it under the
existing lifetime lock. Actual structural state contributes to ordinary planner
byte accounting. No callback executes under the mapping lock. The table is not
persisted into the sidecar, and existing global-budget/generation policy is not
silently replaced by this change.

## Source and correctness proof

The provider's final full-module race/vet pass,16 complete main query cases,
7 Java hash vectors,9 fresh-JVM consumed-SID errors and the ordinary threshold
history956970→1055434→180296 are retained under `provider/`. Independent review
adds concurrent build/clear/probe, canceled lock acquisition, Close/reopen,
race×20 and repeat query/store checks under `independent/`. The review manifest
is complete; its duplicate module can be reconstructed from the pinned source
and patch. Compiled Java classes remain external with verified hashes in
`copy-receipt.json`; source and commands are retained.

Root separately tested the ordinary/Attempt12/Attempt13 combination, then the
combination with synchronous generic DISTINCT. Both complete module race/vet
runs pass. The first preserves all34 prior full response artifacts, with23
speculative40-source mappedView diagnostic leaves differing. The combined run
preserves all35 artifacts' complete responses, including generic432;52
speculative state leaves differ. The exact state paths are retained. These are
the already-demonstrated concurrent initialization observations, not row/error
normalization. All48 rolling responses match in both comparisons.

The final06321518 candidate's2271 module source/fixture files exactly equal the
tested combined snapshot. The original1048 corpus remains968 complete matches
and80 existing differences. Earlier integration setup errors are retained: a
manifest-list collector assumption and an apply attempted before worktree
checkout finished; neither altered production or test expectations. The latter
was retried only after the original checkout handle returned exit0 and clean
state was verified.

HotSpot's warmed throw-site message elision, global JVM reservation/custom
configuration, broader generation release flags and exact racing callback
timing remain explicit gaps. The nine fresh-JVM error captures use normal JVM
defaults and do not claim to emulate JIT history. See the source testdata README
for precise supported contracts and raw failed-before observations.

## Real64 path discovery and measurement

All runs use the same64 real persisted graph dataset:1152 graph files,
10,338,207,518 bytes, with complete catalog verification. Separate main and Go
copy-on-write clones preserve the original files; `/bin/cp -cRp` preserves file
metadata. Actual Close/persistence remains enabled. Pinned main is
`4e328b0109e13c896b74004823fb049fcb19251a`, its explore JAR SHA256 is
`91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
Main uses the established `-Xmx8g`, default request timeout/capacity4, actual
16 CPUs and no injected JVM options. No scheduler/parallelism override is used.

The first fresh main HTTP process ran the fixed five-query history and global
LIMIT255/256/256-repeat. All eight complete bodies pass in both Go revisions.
However, the actual Go state observer showed zero tuple tables:64 participating
sources select the mapped-view/ParallelRaw path. Raising LIMIT alone does not
exercise retained tuple construction. `global64-path-observation.json` retains
this discovery, and `global64-comparison.json` is only correctness/control
evidence. These observations are not relabelled as a successful tuple benchmark.

The added workload keeps all64 graphs loaded and uses explicit graphId predicates
for two disjoint32-source groups, collectively covering every graph. Each group
runs255,256,256-repeat. A second fresh main HTTP process captured all six full
bodies, with both repeats equal. The Go pair starts from fresh processes and the
same ordered routed workload. All complete outputs match main.

A diagnostic helper reads the actual retained pointer and hash/node array lengths
under the existing read lock, outside request counters. It never creates tables,
scans their contents, or updates cache state. The exact same helper compiles in
both revisions, using reflection only to recognize the newly added field. The
255 control leaves zero tables; the first256 query creates31, and the second
group's256 query brings the total to62. Source zero in each group already knows
all selected tuples and does not require a provenance probe. The baseline has
zero tables throughout.

| Routed query | Execute + marshal seconds, base → candidate | Allocation bytes, base → candidate | Process CPU seconds |
|---|---:|---:|---:|
| first32-tuple-255 | 2.707375 → 2.765019 | 2,254,876,408 → 2,254,871,680 | 12.747557 → 13.104999 |
| first32-tuple-256 | 0.121201 → 0.649740 | 76,895,128 → 263,266,016 | 0.577600 → 3.084234 |
| first32-tuple-256-repeat | 0.120413 → 0.112753 | 76,887,056 → 48,153,288 | 0.607283 → 0.535754 |
| last32-tuple-255 | 1.736963 → 1.736216 | 3,449,741,496 → 3,449,737,648 | 8.371592 → 8.330630 |
| last32-tuple-256 | 0.086002 → 0.417642 | 81,912,728 → 185,396,976 | 0.417305 → 1.948133 |
| last32-tuple-256-repeat | 0.083148 → 0.073162 | 81,915,720 → 47,795,600 | 0.422562 → 0.353549 |

The first construction increases latency, CPU and allocation; repeated queries
use fewer allocations and modestly less CPU/wall time. Keep the functional
correction with this cost visible. This is not a general performance win.
Global64 controls do not enter the new table path.

Measurements include ExecuteCross and actual server response encoding. CPU
sampling is off; getrusage and request allocation deltas are used. Forced GC,
heap snapshots and state observation occur outside request counters. Go1.22.0,
darwin/arm64,16 CPUs,64GB; background correctness/build/evidence I/O was present.
No real64 execution overlaps another. All original, main-clone and native-clone graph hashes remain unchanged after
the main/profile processes, with no extra graph files. Individual observations
are not HTTP P95,
peak RSS, or a main-relative10× result. Complete commands, source/binary identities,
raw bodies, counters and before/after state are preserved for both pairs.

## Shipping verification

All56 HTTP requests pass in three fresh sequential shipping processes: global64
8 queries, routed6 queries and the original42-request regression sequence.
Each phase preserves its own frozen main request order. Independent verification
compares every typed JSON value, preserving arrays, nulls and numeric/boolean
identity, plus protocol headers and all64 catalog entries. No dynamic masks are
used. The actual shipping binary excludes all three diagnostic helpers; SHA256:
`42b489a6a495aa4e96af3ed110638d177fc62588a2980c0804cfcac870e88392`.
All140 current-platform compiler/embed/module inputs and all2271 source/fixture
files match final root. Default60-second timeout/capacity4, profiling environment
unset. PIDs14254,15790 and16767 exited143 after SIGTERM without forced kill;
ports18865–18867 were independently bound after exit. Full original and HTTP
clone graph hashes are checked after all three processes, with no extra files.

An initial source collector wrongly expected the older all-source inventory
count; that failure is retained and was corrected to distinguish actual
compilation inputs from tests and inactive-platform source. No source change
was needed. The existing ignored root build output is left untouched; the
independently built and hashed executable above is the one actually exercised.

Full feature parity, repeated main-relative10× P95 and the required
benchmark-regression-gate remain unestablished.
