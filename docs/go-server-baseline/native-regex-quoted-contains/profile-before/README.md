# Frozen baseline diagnostic profile

This is a profiling run, not a performance sample or P95 acceptance run. It uses
the frozen `ast-cache-61828229-build-v1/source` in a new external module copy.
Only the benchmark command was instrumented: one call-site wrapper, diagnostic
header fields, and a new helper. Query/store/parser sources, prepared inputs,
persistent worker, `WorkTrackingEnabled=true`, original measure body and original
setup GC remain unchanged. The command and all build inputs are hashed.

One fresh audited clone contains all 64 real graphs. All 1,152 original files
match before and after the full cold workload. The original 1,267 cases execute
in order, retaining history: 1,266 successes and original case821 failure match
the full canonical main reference. There are no new errors or timeouts. The
child exits1 for the original all-success gate; controller completion is exit0.

Only `regex-or-zero`, `regex-or-targeted` and `regex-or-dense` enable process-wide
CPU profiling around the original measure call. No forced GC or extra case was
added. CPU covers all goroutines, including allocation, GC and profiler work.
Profile serialization is outside the measure interval, but changes later history;
**all run latency observations are profile-contaminated diagnostics**. Other
host correctness work may run concurrently. Host inventories are retained.

| Case | CPU samples | decodeJavaString inclusive | matcher methods inclusive union | GC stacks inclusive | MemStats TotalAlloc delta | GC cycles |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| regex-or-zero | 2,463 | 83 (3.37%) | 924 (37.52%) | 601 (24.40%) | 21,033,952,776 B | 2 |
| regex-or-targeted | 1,014 | 33 (3.25%) | 378 (37.28%) | 294 (28.99%) | 6,058,040,768 B | 1 |
| regex-or-dense | 0 | unavailable | unavailable | unavailable | 1,879,528 B | 0 |

These are inclusive stack groups and overlap; their percentages cannot be added.
The parent `MatchesContext` frame covers 41.45% and 41.62% of sampled CPU for
zero and targeted respectively. The matcher and allocation/GC paths occupy much
more sampled CPU than decoding alone in this run. The dense query produced no
CPU samples, so no CPU attribution can be made for it.

`profiles/*/counters.json` preserves before/after MemStats and process Rusage.
MemStats differences measure process-wide counters over the diagnostic interval
and include runtime/profiler allocations. The default 524,288-byte sampled
alloc/heap profiles were captured without additional GC. Their snapshots can lag
GC: the targeted alloc-profile difference is about 9.43 GB while its MemStats
interval increase is about 6.06 GB. The sampled difference can include allocation
from preceding cases becoming visible after GC, and omit recent allocation.
It must not be interpreted as exact per-query allocation attribution. Dense has
a zero sampled difference despite a nonzero MemStats increase.

`profile-summary.json` retains raw counts and fractions; `pprof-commands.json`
records all CPU flat/cumulative, allocation snapshot-difference and heap commands.
Original profile files and decoded raw pprof output remain available. The first
analysis-only text parser failed on pprof's `[dflt]` header marker; its log is
retained, the parser was corrected, and final analysis passed. No query rerun was
performed for that correction.

The external module and graph clone remain at the paths in `build.json` and
`process.json`. `build-source.tar.gz` contains command build inputs from the module
(Go sources, module files and embedded inputs), not unrelated test fixtures.
`graphite-benchmark-profile.gz` preserves the exact binary. Full original module
file hashes and transitive compiler/dependency input hashes are retained alongside
the command overlay. See `artifact-manifest.json` and `final-verification.json`
for the final file inventory and independent hash checks.
