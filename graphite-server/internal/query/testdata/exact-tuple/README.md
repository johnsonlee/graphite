# Exact DISTINCT projection tuple compatibility

This functional delta is based on the independently frozen ordinary integration tree `e348726c51bc291bf6978b3ca78aa12525754042` (root 87aaf0ad plus ordinary and its Java UTF16 cache-key correction). It ports pinned main `4e328b0109e13c896b74004823fb049fcb19251a`, MappedCallSiteStringIndex.kt:97–110,765–899,1702–1896. It does not include later root A12/A13 changes, alter global-budget settings, or run any performance experiment.

The actual retained table owns parallel uint64 hash and int32 node arrays. Persisted retained indexes can build it at first-CSR posting count>=4096 and selected-value count>=256; freshly built/A6/mapped-view/raw paths do not gain that capability. Construction deliberately occurs before four-column projection eligibility: single, duplicate, and known-null projections can consume four raw SIDs for every first-CSR posting and then fall back. All four raw integers are read before indexing the temporary string-hash array. Build order is persisted CSR order, with Java UTF16 hashes, signed32-to64 extension, main64-bit mixing/linear probing, raw SID deduplication, and least-offset representative selection. A table probe stops at one tuple-equal node, letting the query evaluate its ordered predicates before examining later collisions. No callback executes under the Store lifetime lock.

The table is built privately under the existing lifetime lock, published after successful completion and a context check, and remains retryable after cancellation or a consumed-field error. Successful structural bytes are `160+12*capacity`; the temporary string-hash buffer is not retained. Cache clearing preserves the table; Store.Close clears its current retained table before unmapping. Actual table length contributes to ordinary serial-scan selection. The tuple table is not written into the v2 sidecar. Existing index-generation release policy is preserved; this delta does not rewrite the ordinary provider's broader release flags or JVM global reservation policy.

## Evidence

The tiny fixtures are actual main GraphStore.save output copied from the frozen read-only audit `/tmp/graphite-main-exact-tuple-audit` (manifest c8b1529eb4908fcae68f4074836663879d330dfa47fb2417d2218c480082f2d1). Their4095/4096 sizes are necessary branch thresholds, never performance samples. Main writer encounter order can depend on JVM object identity; use these exact saved fixtures when reproducing their concrete row order, or capture new expectations if regenerating graphs. Copy fixtures to a new directory before running Java helpers: the built-index case intentionally deletes a sidecar and Close may write it again.

- The original 10-state main audit covers 4095/4096, 255/256, persisted/built, four/single/repeated/known-null columns,98,464-byte growth, clear, canceled build/retry and Close. Store tests assert concrete state and earliest raw tuple representative. Query tests validate 16 complete main result/error cases: the original 4 and 12 parameter/permuted/repeated/null/graphId cases. The definitive source control uses 9 logical source names over two tiny stores: Java availableProcessors=16 and Go runtime.NumCPU=16, equal legacy wave 8, so the last source performs selected-value provenance. All columns, rows and provenance are checked. The previous missing-tuple failure now agrees with main.
- Seven actual main hash/slot vectors cover signed hashes, Java hash-zero strings, Aa/BB collisions, supplementary and lone-surrogate units. Additional storage representation tests cover collision rejection, two different SIDs with equal contents, WTF8/canonical UTF16 identity, and duplicate tuple encounter order. These additional representation mutations are unit tests, not claimed persisted main-oracle fixtures.
- `TupleCacheOracle.java` executes an actual 200-row, 68-column ordinary projection. Native and main have identical complete results and logical states 956,970 bytes/serial=true -> actual tuple construction 1,055,434/false -> clear 180,296/true. This is an execution-strategy correctness check, not a memory benchmark.
- `TupleBadSIDOracle.java` covers negative and out-of-range SIDs in all four positions and one earlier bad raw address. The bad-address case proves the callee raw read can throw a null-message IndexOutOfBoundsException before hashing an already read negative caller-class SID. Nine independent fresh default-JVM captures provide exact expected class/message/state. Cooperative cancellation checks cover entry, partial work, pre-publication and retry; concurrent build/Close and owned probes are race-tested.

The independent delivery preserves full raw commands, main JAR/source hashes, the original missing-tuple failure, all frozen main rows/errors, and the full original 1,048 regression denominator. No existing oracle was narrowed or masked.

## Explicit boundaries

JVM global reservation admission/denial and custom heap-budget configuration remain the ordinary provider's pre-existing gap. This implementation allocates real tables and retains real structural state; it does not fake global admission through counters. Malformed sidecar validation outside the accepted reader contract, all precise GraphWork callback scheduling, release-policy flags beyond the current native generation model, and all possible concurrent external mutation histories are not newly claimed.

The captured shared-JVM bad-SID series also demonstrates HotSpot OmitStackTraceInFastThrow: later ArrayIndexOutOfBoundsExceptions may omit their messages as that throw site warms. Raw shared-JVM output and the initial native mismatch log are preserved in the independent delivery. Native does not emulate JIT exception-message elision with a counter. Fresh-process cases use normal JVM defaults (no -XX:-OmitStackTraceInFastThrow flag) to isolate the source's read/error order. Therefore passing those cases is not a claim of all warmed-JVM exception-message histories.

## Reproduction

From graphite-server, with new evidence output paths:

```sh
ORDINARY_HISTORY_OUTPUT=/tmp/tuple-ordinary-new INDEXED_DISTINCT_OUTPUT=/tmp/tuple-indexed-new go test -race ./... -count=1
go vet ./...
```

Compile Java helpers against the pinned main JAR with JDK17 and this directory's ExactTupleOracle.java. TupleProjectionOracle writes the same UTF8 JSON boundary as main HTTP: an unpaired surrogate is replaced by '?' while full rows/columns are retained. The original direct raw-UTF16 audit is retained separately. TupleBadSIDOracle accepts a final 0..8 case index; invoke one fresh JVM per case and concatenate those nine arrays. Global all 1,048 and ordinary-history comparisons must stay visible even though their earlier 82 explicit differences remain unrelated to this delta.
