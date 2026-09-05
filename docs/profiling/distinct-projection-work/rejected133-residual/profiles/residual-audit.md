# Independent rejected Attempt 133 residual-work audit

**Pass.** Independently checked both existing recordings without running Java, capturing again, changing production code or judging acceptance. The rejected Attempt 133 remains rejected. Reproducer: `audit-residual.py`; full CPU/allocation category intersections and leaves: `residual-audit.json`.

## Conservation and provenance checks

All **68 original oracle signatures** match the TSVs exactly, including query identity, row count, response size and digest. Across **132 query-phase metrics and 457 per-thread metrics**, phase full-stack sums equal their original summaries and equal the independent outer analyzer's collapsed stacks **stack for stack**, not only by grand total. Query/phase durations conserve, and query identity/order, missing/truncated stacks, JFR/TSV hashes and recorded JAR hashes check out. All missing/truncated counts are zero. Current hashes and sizes of all **1,088 graph files** equal the pre-capture receipt; `completed.json` separately records root's completed pre/post verification.

Application threads use the same request worker and graphite cypher/callsite scan/segment prefixes as the earlier audit. JIT compiler threads and all remaining threads are separate; application includes all work on those application threads, not only Graphite-package frames.

## Dense provenance: residual CPU and sampled allocation

| Metric | Frozen main | Rejected 133 |
|---|---:|---:|
| Application CPU samples | 103 | 64 |
| Separate JIT CPU samples | 83 | 68 |
| Separate other CPU samples | 1 | 2 |
| Application samples inside selectedProjectionHits | 0 | 59 |
| Application samples inside selectedTupleStringIds | 0 | 56 |
| Application samples inside StringTable.findId | **2** | **56** |
| All application sampled allocation bytes | 10,747,904 | 39,321,600 |
| Application sampled allocation inside selectedProjectionHits | 0 | 39,059,456 |
| Application sampled allocation inside selectedTupleStringIds | 0 | 38,535,168 |
| Application sampled allocation inside StringTable.findId | **2,621,440** | **38,273,024** |

The exact JVM lookup frame is `StringTable.findId$webgraph(Ljava/lang/String:)I`; the internal Kotlin method is module-mangled. The independent script matches this actual frame.

The **56** rejected-133 CPU samples are the same stack observations inside all three methods. They must not be added as 59 + 56 + 56. More precisely, selectedProjectionHits contains 56 samples also under selectedTupleStringIds/findId, plus three others; five application samples are outside that selectedProjectionHits subtree. Frozen-main findId's two samples are contained in its raw projection subtree (42 application samples); mapped predicate discovery separately has 57 samples, with four application samples outside those two main paths.

Allocation has the same nesting. Rejected-133 findId accounts for 38,273,024 sampled bytes nested inside both selectedTupleStringIds and selectedProjectionHits. Another 262,144 is under selectedTupleStringIds without findId; 524,288 is elsewhere under selectedProjectionHits; 262,144 is elsewhere on application threads. An additional 262,144 sampled bytes is on a non-application thread and excluded from the application denominator.

FindId's rejected-133 allocation leaves independently sum to its inclusive weight:

| Allocation leaf | Sampled byte weight |
|---|---:|
| CharArrayFrontCodedList.getArray | 17,563,648 |
| StringUTF16.compress | 10,747,904 |
| MutableString.toString | 5,767,168 |
| MutableString.wrap | 4,194,304 |

CPU leaves beneath findId include front-coded extraction (8), the inner StringUTF16 compression routine (7), findId itself (5), front-coded length (4), and the outer compression routine (4). These are sampled leaves, not operation counts. Full leaves and intersection patterns for dense and targeted phases are retained in JSON.

## Why small posting counts did not remove the work

In rejected source `MappedCallSiteStringIndexView.kt:96–98`, each of the selected tuples calls `selectedTupleStringIds`. At `:142–156`, every non-null projected value directly calls `stringTable.findId`. This loop has no value-to-ID map shared across tuples. Repeated strings, including repeated columns, can therefore be resolved repeatedly; a missing ID short-circuits only the current tuple. Property posting feasibility is tested later by `selectedTupleAnchor`.

Frozen main's existing raw path already has two storage-call-local maps in `MappedWebGraphBackedGraph.kt:508–535`: `selectedStringIds.getOrPut(value)` caches ID lookup, including a returned -1, and `selectedPropertyMembership` caches corresponding-property membership by property/string ID. The rejected shortcut bypasses these maps. This difference is real source behavior; it does not establish a measured number of findId calls.

Both revisions use identical `StringTable.kt`. Loaded tables have `indexMap = null` at `:115`; `findId` (`:40–54`) performs a binary search whose comparison repeatedly executes `list.get(middle).toString().compareTo(s)`. Those operations correspond to the front-coded extraction, conversion and allocation observed above. The selected-tuple field's work-accounting unit does not count every internal binary-search comparison or temporary allocation. Thus the separate reference census's **53 summed candidate posting lengths** omits a material source of real residual work, and the earlier lowered work counter cannot be used as a CPU or latency predictor.

This evidence diagnoses a cost in the rejected implementation. It does not accept that implementation, prescribe a new candidate, remove initial sparse-query work, or show that adding a cache would meet the global target. Only one existing replay was recorded per revision, with method tracing and sampled allocation. Differences between these two recordings are not a stable speedup, production P95, causal attribution of concurrent JIT, or permission to override the historical CI rejection.
