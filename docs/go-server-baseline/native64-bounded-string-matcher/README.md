# Real64 replay after bounded matcher port

The Go candidate ports main's bounded matcher into raw projection and finite serial raw CallSite scanning. The exact candidate Go inputs are frozen by SHA-256 in each capture receipt; all three source snapshots are equal and still match the final candidate sources. Original main remains pinned to 4e328b0109e13c896b74004823fb049fcb19251a.

| State / observed phase | Outcomes equal to original main | Graph observations, all seven fields equal |
| --- | --- | --- |
| Cold / replay | 1,266 successful full results and one original error | 162,304 |
| Warm / warmup | 1,266 successful full results and one original error | 162,240 |
| Startup-prepared / replay | 1,266 successful full results and one original error | 162,304 |

All 1,267 original testcase definitions, parameters, source selections and order remain intact. Comparison covers columns, every row, canonical numeric types, simple exception class/message, and retained, mappedView, trigrams, loadedFromPersistence, mappedRangeCount, rawMatchCount and rawProjectionCount for every graph at each observed boundary. All 486,848 graph observations match. Cold and startup-prepared also verify each of the original main manifests' 1,266 successful digests, response lengths and row counts.

The original error is still case index 821, four-or-graph-id-targeted, IllegalStateException / Unsafe expression reached parallel string projection. All three runtimes exit 1 after the original all-success gate rejects it. Warm stops after its complete warmup and before prepared, forced GC or formal replay; it is not a completed formal warm benchmark. Main includes an additional fully qualified Java error class in its observer output that native does not expose; every comparison explicitly records that observer difference. No testcase or gate is removed.

Every native capture uses a fresh writable clone of the immutable real64 dataset. All 1,152 original files match before and after execution with no graph-local additions. Every source input and runtime binary remains unchanged, and all recorded runtime processes are terminal. The controller's zero exit means complete captures and successful comparison, not passage of the original all-success benchmark gate. Main captures are reused from ../native64-fullcase-replay/main-cold-complete and ../native64-index-states/main-{warm,startup-prepared}.

From the repository root, run verify-state.py in this directory with cold, warm or startup-prepared to recheck. run-state.py records exact commands, Go environment, source/binary hashes, PID, timestamps and fixture audits. archive.py preserves JSONL bytes in gzip files and verifies decompressed SHA-256 in capture-archives.json. run-all.py refuses to restart a receipt that is not terminal.

This is correctness evidence, not latency or P95 evidence. Synthetic matcher, corruption, public-query, policy and negative-control evidence is in ../native-bounded-string-matcher. Remaining work includes other raw fallback match-state behavior, graph-work/context accounting and other server functionality. Formal warm measurement remains unavailable under the unchanged gate. The concrete next measurement protocol is ../native-bounded-string-matcher/p95-next.md: use an observer-free main launcher and matching Go timing boundary for a full ordered cold/startup pilot, then repeated complete workloads for per-case distributions. The 10x P95 objective has not been verified.
