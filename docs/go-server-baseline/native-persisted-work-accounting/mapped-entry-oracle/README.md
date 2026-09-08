# Actual storage entry interruption controls

Eight actual-main controls agree across both original-configuration captures: 16 probe phases and 23 warmup phases. This is the public **storage** method `nodesByStringPropertyDisjunction`, not public Cypher execution or a benchmark. It runs on one actual-main-written hit64 graph per case. The factory's sourceCount40 selects the actual preferred mapped Split work-consumer class; this is not a claim of executing 40 graphs or exercising dispatcher scheduling.

The actual `QueryPipelineKt.directStringStorageWorkConsumer` factory constructs the consumer. Its callback records each batch and delegates to the actual ExecutionContext.workTracker. Callback controls set the thread interrupt flag or cancel the actual signal after the first successful tracker callback. They never replace the storage algorithm, artificially consume work, or synthesize budget exceptions. Warmup uses the same public storage method; retained warmup uses the original factory's forceSerial preferred-persisted option. Probe contexts are new; the Store is preserved.

| Probe | Observed result |
| --- | --- |
| Cold view, entry interrupted | Identity callback `[1]`, then sequence construction throws CancellationException; no view or range is published. |
| Warm mapped view, absentzz, entry interrupted | Construction and empty iteration succeed while interrupt remains set; callback `[1]`, work1. |
| Warm mapped view, hit, entry interrupted | Construction throws after callbacks `[2,7]`, work9, range cache0. |
| Warm mapped view, hit, first callback sets interrupt | Same construction failure, callbacks `[2,7]`, work9, range cache0. |
| Warm mapped view, hit, first callback cancels signal | First callback consumes2; next callback `[7]` throws the same actual signal exception instance without consuming more. Thread flag is false. |
| Warm mapped view, hit, budget1 | Attempted callback `[2]` throws the actual tracker budget exception; remaining0, reported work1; same callback exception instance propagates. |
| Existing retained, cached absentzz, entry interrupted | Construction and empty iteration succeed, zero callbacks/work, existing retained caches preserved. |
| Existing retained, cached hit, entry interrupted | Construction, next(node74), EOF succeed while interrupt remains set; callback `[1]`, work1. |

`posting-locations.json` interprets the immutable writer sidecar: term hit's trigram is at absolute posting147, matching SID1. Caller-name property1 row0 occupies node-posting range `[0,1)` and yields node74. Node ordinal64 must not be confused with posting position0. Thus the warm-view hit controls stop at the first range-validation poll during construction; they do not prove the already-validated-range first-iteration boundary. Any additional warm-range controls must be a separate supplement preserving these eight captures.

Each ordered step records before/after actual diagnostics (all eight fields), remaining tracker work, signal state/reason, thread flag, cache counts and lookup counters. Callback records preserve phase, units, before/after state and original exceptions. Sequence construction, iterator construction, hasNext, next and EOF are separate phases. Thread.interrupted() is cleared only after all phases/outcomes have been recorded, for serial isolation; no cleanup occurs between construction and iteration. Full nodes, original exception classes/messages/stacks, callback-cause identity, before/after close state and fixture hashes are retained.

Both runs use `-Xmx512m` and the pinned original main JAR; no diagnostic override or dispatcher forcing is added. V1 capture PID34204 and V2 PID34219 are terminal0. All136 case fixture files per capture remained unchanged, with17 unique fixture files archived. Full parsed outputs, including stacks, match exactly. `verify.py` checks raw external/archive bytes, fixture archive, input-source archive, and repeat equality. No failures were discarded, and no latency/P95 claim is made.
