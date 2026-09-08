# Quoted-contains candidate diagnostic profile

This is a full cold real64 diagnostic invocation, not an acceptance timing run.
All 1,267 original ordered cases execute on a fresh audited copy of the same
64 persisted graphs. Canonical comparison passes for all 1,266 successes and
the original case821 failure; no new error or timeout appears. The original
all-success gate still exits 1. All 1,152 original graph files and frozen build
inputs remain unchanged.

The candidate contains the independently verified functional quote correction
plus the strict ASCII quoted-contains matcher. The exact source and executable
are retained in `build-source.tar.gz` and `graphite-benchmark-profile.gz`, with
input/source hashes. All module-local build inputs are included, including
embedded Unicode data. `run-profile.py` records the original module, isolated
command overlay, transitive dependencies, Go environment and runtime inputs.

The benchmark command and profiler helper bytes exactly match `profile-before`.
Only the three original regex cases receive CPU/heap/allocation profiles and
MemStats/rusage brackets. There is no extra case or added forced GC, and the
complete original workload history is retained. Profiler setup/output changes
the measurement environment, so every query clock in this run is labelled
contaminated and excluded from acceptance evidence.

| Original case | Before TotalAlloc delta (bytes) | Candidate delta (bytes) | Before process CPU (s) | Candidate process CPU (s) |
| --- | ---: | ---: | ---: | ---: |
| regex-or-zero | 21,033,952,776 | 2,326,225,432 | 34.547250 | 11.178630 |
| regex-or-targeted | 6,058,040,768 | 743,596,368 | 12.404417 | 3.527174 |
| regex-or-dense | 1,879,528 | 1,636,208 | 0.001382 | 0.000937 |

The first two allocation deltas decrease by about 88.9% and 87.7%. CPU samples
contain no ordinary matcher/decodeJavaString frames for those two candidate
queries; the new matcher accounts for 41/872 and 13/251 samples respectively.
The dense query has zero CPU samples, so it supports no stack attribution.
Inclusive attribution groups overlap and must not be added together.

The remaining zero-result query CPU is dominated by candidate reads:
`ProjectionCandidateNode` is present in 52.64% of samples, including generic
node decoding and allocation. This is evidence for a later investigation, not
part of the current matcher change.

Exact process MemStats deltas include instrumentation and all goroutines inside
the bracket. Sampled heap/allocation snapshot differences may lag GC and include
earlier-query allocations; they are not exact per-query attribution. Before and
after are separate diagnostic invocations, and the earlier source precedes the
functional quote correction. The complete source archives retain that difference.
The runs are not statistical speedup estimates and establish no per-case P95.

Reproduction uses fresh output paths in `run-profile.py`, then
`python3 analyze-profile.py` after the runtime is terminal. Commands and all
pprof output are retained. The parent `profile-comparison.json` verifies equal
case identities/results and equal command/helper instrumentation bytes.
