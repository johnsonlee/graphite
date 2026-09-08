# Real64 replay with raw projection cache

The Go candidate matches pinned main 4e328b0109e13c896b74004823fb049fcb19251a across all reachable phases of the original 1,267-case workload. All three candidate processes are terminal with exit code 1: each reproduces the original query error and original all-success gate failure. The serial controller exits zero only after complete capture and comparison, not as an all-success benchmark result.

| State and observed phase | Main and Go outcomes | Graph observations, all seven fields equal |
| --- | --- | --- |
| Cold replay | 1,266 identical successful results and one identical error | 162,304 |
| Warm warmup | 1,266 identical successful results and one identical error | 162,240 |
| Startup-prepared replay | 1,266 identical successful results and one identical error | 162,304 |

The seven fields are retained, mappedView, trigrams, loadedFromPersistence, mappedRangeCount, rawMatchCount, and rawProjectionCount. All 486,848 graph observations match; native reports no unavailable state counters. Main and Go raw-projection counts grow identically to four entries, and raw-match counts stay zero. Full original definitions, parameters, source selection and ordering match. Successful comparisons preserve every column, row, numeric type and canonical byte sequence. Both cold and startup manifests independently verify all 1,266 successful original digests, lengths and row counts.

The remaining error is original case index 821, four-or-graph-id-targeted, IllegalStateException / Unsafe expression reached parallel string projection. Main additionally emits its fully qualified Java exception class in an observer field; native has the same simple class/message but not that extra qualified field, explicitly reported in every comparison. Warm fails its original gate during setup after all warmup cases; it never reaches prepared, forced GC or formal replay. No case was removed or reclassified, and no gate was bypassed.

Each native process uses a separate writable clone of the frozen real64 dataset. All 1,152 original files match SHA-256 before and after each run, with no added graph files. Runtime binaries and all Go module source inputs remain unchanged. The three source snapshots are identical and still match the final candidate sources; cross-state-input-verification.json records the check. run-state.py records exact commands, Go environment, binary/source hashes, PID, timestamps, and terminal status. Original main captures are reused from ../native64-fullcase-replay/main-cold-complete and ../native64-index-states/main-{warm,startup-prepared}; their original artifacts and manifests remain intact.

Recheck from the repository root:

```sh
python3 docs/go-server-baseline/native64-raw-projection-cache/verify-state.py cold
python3 docs/go-server-baseline/native64-raw-projection-cache/verify-state.py warm
python3 docs/go-server-baseline/native64-raw-projection-cache/verify-state.py startup-prepared
```

archive.py stores byte-preserving gzip copies; capture-archives.json verifies their decompressed SHA-256. The first verifier failed while routing the repository workload path into the baseline directory; verify-cold-initial-path-error.log preserves that harness error. The path resolver was corrected and the existing completed capture verified without rerunning the runtime. run-all.py can resume from terminal receipts and refuses to restart any potentially live capture.

This is full-workload correctness and seven-field structural evidence, not timing evidence or complete server parity. Per-query bounded string matching, graph-work/context accounting, other remaining server functionality, formal warm replay and the requested per-case 10x P95 acceptance remain outstanding. Main's production finite-work option is ignored, as the source audit in ../native-raw-projection-cache/finite-work-next.md explains. No performance samples or speedup claims are produced here.
