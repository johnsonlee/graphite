# Full original real64 testcase replay

The Go replay command now consumes all 1,267 cases exported from pinned main
`4e328b0109e13c896b74004823fb049fcb19251a`. Definitions, parameters, request source
selection, source order, row bounds, and execution order are preserved. This
directory contains correctness observations, not P95 measurements.

| Evidence | Result |
| --- | --- |
| Original testcase definitions against Go input | 1,267 exactly equal |
| Real graph inputs per runtime | 64 distinct shards; all 1,152 original files hash-verified |
| Successful outputs, before and after release correction | 1,266 exact main canonical results |
| Query errors | One matching `IllegalStateException` and message |
| Main repeat with complete classpath | All captured records, including index/cache states, exactly repeat |
| Original main correctness/observations files | All 1,266 successful canonical digests and lengths independently verified |
| Original main all-success gate | Failed on `four-or-graph-id-targeted` |
| Post-run fixture verification | All original files unchanged; no graph-local files added in any replay |
| P95 and main-relative speedup | Not measured |

The failing main query returns `graphId(n)` from a parallel string projection
path and throws `Unsafe expression reached parallel string projection`.
The Go implementation returns the same simple exception class and message.
Main additionally records its fully qualified exception class; the Go observer
does not expose that extra field. The comparison reports this difference.
The failing case remains in every replay and prevents the original all-success
gate from passing. No case is dropped or reclassified as successful.

The first main capture completed all queries but could not write its benchmark
manifest because the capture classpath lacked `QueryCorrectnessManifest`.
The corrected repeat supplies frozen compiled main classes after the original
Explore JAR. Query runtime classes were checked byte-for-byte against the JAR;
the complete classpath was hashed before and after execution. This repeat
writes both original manifests and reaches the original all-success gate,
which rejects the real query error. Its observation timings include capture
overhead and must not be treated as benchmark samples. An earlier clone setup
attempt rejected the TSV comment header before launching Java; that failure is
also preserved.

The original Go replay at `8bbd8835` retained all public results but differed in
index state. Fix `f22f8650` preserves preferred persisted indexes across zero-hit
DISTINCT release. The complete fresh replay after that fix has zero differences
in retained, mapped-view, trigram, and loaded-from-persistence state. The remaining
mapped-range counter differences total 22,745 graph/boundary observations;
these are repeated observations, not 22,745 distinct cases. The first appears
after case index 2, `single-contains-unlabeled-targeted`, on `fixture-android-00`
(main 1,024, Go zero). The Go observer also lacks the raw-match and raw-projection
cache counters. Their original main values remain in the captured records.

`MainReplayCapture.java` calls the original benchmark setup, invocation setup,
query submission/replay, and teardown. Its executor wrapper observes the
original Future without replacing its query callable or timeout join. Full
columns and rows accompany the original main canonical bytes. The native
canonical implementation preserves row order and numeric classes; it sorts
map entries only as the original canonical function does. Tests include five
complete actual-main response fixtures and an explicit row-order negative
control. The replay command validates graph identities, physical path aliases,
and the exact pinned workload digest; it uses a synchronous joined query
boundary and emits no latency values.

Verify from the repository root:

```sh
python3 docs/go-server-baseline/native64-fullcase-replay/verify-main-repeat.py
python3 docs/go-server-baseline/native64-fullcase-replay/verify-cold.py
python3 docs/go-server-baseline/native64-fullcase-replay/verify-cold.py --main main-cold-complete --native native-cold-release --output cold-release-comparison.json
```

The committed gzip files decompress byte-for-byte to the original response
streams; `capture-archives.json` records both raw and archive hashes. Verifiers
read raw captures when present or the gzip archives otherwise. Source/classpath
input manifests and process receipts retain the exact local commands and
unchanged-input checks. Compiled Java classes and raw uncompressed duplicates
are local build artifacts. Compile the observer with the original benchmark
classes and `ExportBenchmarkCases` helper from the earlier testcase audit;
the absolute compile receipts describe the classpath used for these runs.

The native driver passed its targeted race tests and vet. The subsequent full
module race and vet checks, including this driver and the release correction,
are in `../native-index-release/full-module-race.log` and `vet.log`.

Full warm and startup-prepared behavior, the remaining cache/state differences,
finite work accounting, and per-case P95 acceptance remain outstanding. P95
acceptance requires separate cold/warm/startup-prepared samples per original
case on identical real graphs and equivalent measurement boundaries, with
`main P95 / Go P95 >= 10`; a percentile pooled across different cases does not
establish that requirement.
