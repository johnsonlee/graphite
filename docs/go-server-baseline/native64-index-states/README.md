# Real64 warm and startup-prepared behavior

These four serial captures use Go source revision `5451d576` and original main
`4e328b0109e13c896b74004823fb049fcb19251a`. All native module source hashes equal
the earlier verified cold replay, and all main classpath hashes equal the
complete-classpath cold capture. Each process has a fresh writable real64 clone;
all 1,152 original files match before and after execution, with no added graph
files. Both native binaries and all runtime/compiler inputs remain unchanged.

| State / observed phase | Main and Go results | Five structural fields | Original success gate |
| --- | --- | --- | --- |
| Warm / warmup | 1,266 equal successful results; one equal error | All 162,240 graph observations equal | Fails during invocation setup |
| Startup-prepared / replay | 1,266 equal successful results; one equal error | All 162,304 graph observations equal | Fails after full replay |

The five fields are retained, mappedView, trigrams, loadedFromPersistence, and
mappedRangeCount. Query definitions, parameters, source order, request selection,
execution order, columns, all rows, numeric types in original canonical framing,
and simple error class/message match. Main additionally records its fully
qualified exception class; the native observer does not expose that extra field,
and each comparison reports that difference explicitly.

Warm must not be reported as a completed warm benchmark. Original main's
`setupInvocation` clears indexes, replays all 1,267 warmup cases, then calls its
all-success gate. `four-or-graph-id-targeted` throws `IllegalStateException` with
`Unsafe expression reached parallel string projection`, so invocation setup
fails before the prepared marker, forced-GC boundary, or formal replay. Native
follows the same failure boundary. No warm correctness/observation TSV is emitted
by original main, and no formal warm replay or P95 is established.

Startup preparation is observed directly: all 64 graphs load with retained and
trigram indexes marked loaded from persistence, while mapped-view counts start
at zero. Both runtimes reach the full replay. Main writes its original
correctness and observation manifests, then rejects the same query error.
The verifier checks all 1,266 successful original digests, byte lengths, and row
counts against captured canonical data in both manifests. Their timing columns
include observer overhead and are not performance samples.

The remaining raw-cache observation gap is preserved. Main's raw-match counter
is zero throughout both captures. Its raw-projection cache grows from zero to
four entries on `fixture-android-00`, at cases 9, 15, 849, and 873 in each phase.
`main-raw-projection-transitions.json` records the exact transitions;
`raw-cache-source-audit.json` records the original cache's key, lifecycle, and
16-entry limit. The native raw projection cache and both corresponding counters
are still unimplemented. These observations are not normalized away.

Recheck captures from the repository root:

```sh
python3 docs/go-server-baseline/native64-index-states/verify-state.py warm
python3 docs/go-server-baseline/native64-index-states/verify-state.py startup-prepared
```

Committed gzip archives preserve the original JSONL bytes and their hashes.
`run-state.py` records each original command, source/classpath hashes, PID, exit
code, and fixture audit. `run-all.py` runs all four processes serially and accepts
completion of their full captures; its successful exit does not imply that any
child's original all-success gate passed. Every child exits 1, and every process
is terminal. This turn changes evidence and capture tooling, not Go runtime code.

Full warm replay, raw-cache behavior/counters, finite work accounting, remaining
server functionality, and per-case P95 acceptance remain outstanding. There is
no 10x speedup claim or complete server-parity claim in this evidence.
