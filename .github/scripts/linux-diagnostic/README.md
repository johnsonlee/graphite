# Attempt 146 Linux diagnostic

This independent push-only branch diagnoses the failed attempt 146; it does not publish a performance acceptance gate. Production checkouts remain frozen at main `4e328b0109e13c896b74004823fb049fcb19251a` and candidate `23dafb3dc82b31ea78a5d399d3ed68f70de5340f`.

The workflow authenticates the original CI shared 64-graph artifact and global oracle, rebuilds canonical original JARs, then adds benchmark-only query JFR events. Every non-harness JAR payload must remain identical. It runs two original controls followed by four dual native/async CPU recordings in B1/C1/C2/B2 order. All 34 queries and their original prefix execute; full oracle signatures must match. Every non-time observation difference is retained. All recordings finish before offline analysis starts.

The ephemeral Linux runner records and sets perf permissions; async recording metadata must identify the perf_events CPU engine at 1 ms. Failure stops the protocol and preserves available evidence. It does not fall back to a timer or change production JVM CPU limits. Actual CPU count and Java version are recorded. Builds use Kotlin in-process compilation; before the first control, a bounded Linux process inventory must confirm that no Java process remains. The marker identifies each fixed case and workload, but does not independently record parameter values.

Local macOS validation compiled this harness and ran the complete 34-query replay: 68 markers matched observations, oracle and all non-time fields matched, graph files stayed unchanged, and all non-harness payloads matched. That validates instrumentation locally; it is not Linux reproduction or evidence of a speedup.

The original PR remains unaccepted. Diagnostic timings are not acceptance results, and neither this commit nor its green diagnostic status establishes the requested 10× improvement. See workflow receipt, raw JFRs/TSVs, per-query analysis and hashes in the uploaded artifact. The Method4 cache is scoped to the original PR and is not silently rebuilt or substituted here.
