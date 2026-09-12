# Independent application-phase audit

**Pass.** Independently recomputed the reported statistics from all serialized per-thread full stacks. No Java process, JFR re-decoding, new capture, workspace change, or candidate was started. The reproducible Python verifier is `audit-phase-application.py`; exact thread/leaf/category weights are in `phase-application-audit.json`.

After normalizing the class rename, `DistinctPhaseDetails.java` differs from the original analyzer on exactly one serialization line: each original metric is wrapped as `summary` and `threadStacks` is added. Removing that wrapper and added field reproduces all **102 original query summaries** exactly, including phase windows, calls, event metadata, allocation weights and original per-thread summaries. Independently checked all three JFR/TSV/output hashes against `receipt.json`.

Across **186 query-phase-metric partitions and 722 per-thread partitions**, full-stack sums equal the preserved summary weights; missing and truncated stack counts are zero. For all **26 rows** in `application-summary.json`, independently recomputed thread classes, inclusive category counts, reported top leaf and inclusive frame weights, and top-list cutoffs. They match. Inclusive frames are counted at most once per stack, including recursive frames. Leaf weights sum to the application denominator.

## Denominator and category definitions

Application threads are exactly `broad-query-pressure-worker` plus `graphite-cypher-scan-*`, `graphite-callsite-scan-*`, and `graphite-callsite-segment-*`. Full observed thread names/IDs and class weights are retained in the JSON. `Java: C1 CompilerThread*` and `Java: C2 CompilerThread*` are JIT; every other thread is other, including GC, JVM/JFR service threads, the resource sampler and unnamed native threads. JIT samples are **not** in the application denominator.

Raw projection means a frame begins with the exact owner/method prefix `io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection`, including generated lambdas. Discovery means a frame begins with `io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView.exactMatchingStringIds`, including its overloads. Count a full stack once per category; compute the intersection explicitly rather than adding inclusive totals blindly.

| Recording / query / phase | Application | JIT | Other | Raw | Discovery | Raw ∩ discovery | Application outside both |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 / targeted / initial | 76 | 66 | 9 | 75 | 1 | 0 | 0 |
| 2 / targeted / initial | 79 | 69 | 3 | 74 | 3 | 0 | 2 |
| 3 / targeted / initial | 66 | 58 | 4 | 60 | 2 | 0 | 4 |
| 1 / dense / initial | 8 | 13 | 1 | 4 | 2 | 0 | 2 |
| 2 / dense / initial | 9 | 17 | 0 | 6 | 2 | 0 | 1 |
| 3 / dense / initial | 8 | 14 | 0 | 4 | 3 | 0 | 1 |
| 1 / dense / provenance | 123 | 100 | 0 | 53 | 69 | 0 | 1 |
| 2 / dense / provenance | 87 | 67 | 1 | 46 | 33 | 0 | 8 |
| 3 / dense / provenance | 80 | 73 | 4 | 28 | 49 | 0 | 3 |

Thus targeted raw is exactly **75/76, 74/79, 60/66** application samples. Dense provenance raw is **53/123, 46/87, 28/80** and discovery is **69/123, 33/87, 49/80**. Their intersections happen to be zero in these recordings. This fact is computed from the same full-stack observations, not inferred from call-tree appearance. All 26 selected CPU/allocation rows also have zero raw/discovery intersection and no `PersistentIndexViewValidator` frame among application samples. That scoped absence does not say validation never runs elsewhere in the replay.

## Leaf evidence and interpretation limits

The raw per-node predicate lambda is the sampled leaf **32, 36, 30** times for targeted initial selection. Other raw-subtree leaves include physical type iteration and primitive-set membership. Dense provenance raw-subtree per-node lambda leaves are **21, 17, 5**; raw parent-method leaves are **7, 4, 3**. Its discovery-subtree leaves include front-coded string extraction, MutableString operations and case conversion. Full leaves, rather than truncated top lists, are in the independent JSON.

The inclusive raw category includes setup/selection code and descendant calls inside the raw method; **it is not an exact count of time spent only in the node scan loop**. Leaf location in a generated predicate lambda cannot by itself distinguish its individual inline operations. The application definition is a thread partition, so all work on those threads is counted, not just Graphite-package frames. Concurrent JIT and other activity are explicitly retained but not causally assigned to these query phases.

Allocation weights are sampled TLAB/outside-TLAB bytes, not exact allocation-object counts or physical memory. Sample counts are small and recordings contain method tracing. These checks support the reported warm initial/provenance work attribution, but do not establish a latency fraction, optimization speedup, or candidate acceptance. The independent check verifies source serialization and full-stack arithmetic; it does not independently decode JFR events again.
