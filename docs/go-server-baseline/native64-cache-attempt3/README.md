# Attempt 3: bounded deterministic HTTP query response cache — rejected candidate

The isolated base is `8fccf511`; the exact commit and complete file hashes are in
`source-manifest.json`. `candidate.patch`, `query_cache.go` and
`query_cache_test.go` preserve the experimental source; none of this cache code
was integrated into production. `run/identity.json` pins the binary and command.

The hypothesis was to reuse successful deterministic responses, after normal
admission and acquisition, keyed by raw query bytes, endpoint/mode/limits, graph
selection order and immutable graph generations. Unknown functions, timestamp,
rand, parameters, failed execution and cancelled work bypass publication. The
LRU had a 32 MiB / 512 entry bound and held only encoded bytes, not mapped graphs.
Correctness tests covered reload/unload/failed replacement, empty catalog,
selection/limit separation, admission 429, cancellation 503 and raw WTF-8 keys.
Server race tests and vet passed.

An independent review found that the existing evaluator iterated inline
properties through a Go map. Repeated execution of an identical query could
short-circuit before division by zero or evaluate the error first. A cache can
freeze the first successful evaluation and hide those unstable errors. The
query's original source order must be preserved and verified against main before
assuming this candidate preserves all successful/error outcomes.

`audit/audit_test.go` is an external read-only overlay, using a tiny fixture only
for correctness. The independent rerun in `audit/output.log` observed 181 success
and 19 division errors for 200 identical uncached executions. The cache then
reused a first success after one build. These are correctness outcome counts,
not performance samples. The first overlay invocation failed because Go1.22 vet
could not open an added overlay-only file; that failure is preserved, and the
rerun disables vet only for this audit (production vet already passed).

The real-data runtime loaded and checked all 64 graph IDs and full catalog counts.
All 1,152 files in the frozen 10.338 GB fixture manifest were freshly hashed and
matched before launch. It completed 11/42 first-replay full responses, all matching
the frozen main oracle. It was deliberately interrupted on the review finding;
`run/aborted.json` records the reason and incomplete denominator. No warm samples,
P95, throughput or speedup result was collected. No remaining cases count as
passed. The owned native process was stopped by the runner's cleanup.

First-replay observations below include full HTTP reading and JSON decoding.
They are one observation per listed query, with GODEBUG gctrace and concurrent
agent correctness/compilation work; they are not controlled paired latency data.

| Completed first-replay query | Milliseconds | Complete response |
|---|---:|---|
| wrapped-zeroHitBroadContains | 38200.373 | Match |
| wrapped-denseDistributedMethodContains | 39204.239 | Match |
| wrapped-earlyGraphClassPrefix | 33726.603 | Match |
| wrapped-middleGraphsClassPrefix | 43261.308 | Match |
| wrapped-lateGraphClassPrefix | 34581.603 | Match |
| wrapped-broadlyDistributedClassPrefix | 43248.929 | Match |
| wrapped-firstLastGraphBimodalClassPrefix | 52960.474 | Match |
| wrapped-skewedMixedClassMethodOperator | 44827.744 | Match |
| global-wide-four-properties-zero | 33698.861 | Match |
| global-wide-four-properties-targeted | 31669.890 | Match |
| global-wide-four-properties-dense | 28982.545 | Match |

CPU/heap: the raw server GC log is retained. No request-scoped allocation or
paired CPU/peak-RSS evidence was completed, and no quantitative improvement is
claimed. The cached main responses are a correctness oracle only, not a current
main performance run. Decision: reject this frozen candidate and keep its source,
audit and partial observations as the experiment record. Repair ordered property
evaluation independently, then use a new numbered experiment for a fresh cache
candidate. Do not overwrite this rejected run with later results.
