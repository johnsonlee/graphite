# Next: parallel captured-index publication

**Status: source review and proposed correctness cases only; not executed.** This document is not an oracle result, a native passing test, a real64 testcase replication claim, or a P95 measurement. It was written while the existing full check was running. No JVM, Go build/test, or benchmark was started; no frozen input, output, manifest, or production file was changed.

The references below use pinned main `4e328b0109e13c896b74004823fb049fcb19251a`. The relevant files are `graphite-webgraph/.../MappedWebGraphBackedGraph.kt`, `MappedCallSiteStringIndex.kt`, `StringTable.kt`, `GraphStore.kt`, and `graphite-cypher/.../QueryPipeline.kt` / `CypherExecutionBudget.kt`. Native observations refer to the source read during this audit: `internal/store/ordinary_projection.go:82` and `internal/query/ordinary_parallel.go`.

## Public route and admission order

The proposed primary query is:

```cypher
MATCH (n:CallSite)
WHERE n.caller_name CONTAINS 'absentzz'
RETURN n.caller_name AS name
LIMIT 1
```

Use one actual mapped source with at least 4096 CallSites, no `graph.callsite-string-index` file, an explicit request context, and default dispatcher settings on a machine with more than one available processor. `QueryPipeline.kt:211–241` chooses `ParallelGraphWorkBatchConsumer` for one source after the higher-priority prefer-raw and force-serial checks. This consumer is not Split. A one-source public executor plus a missing sidecar is a candidate route, not proof that an arbitrary rewritten query reaches it: the eventual capture must preserve the chosen path's counters and stacks without forcing a dispatcher. The existing 40-source large oracle uses a Split-derived consumer and does not validate this publication path.

`lookupStringPropertyDisjunction` first considers preferred/raw/retained/mapped options, then invokes `parallelRawCallSiteStringDisjunction` before generic preflight or the ordinary retained builder (`MappedWebGraphBackedGraph.kt:898–985`). The latter admits a scan only when:

1. The consumer implements Parallel; type is CallSite; LIMIT is finite rather than `Int.MAX_VALUE`.
2. Local scan parallelism exceeds one unless a Split consumer provides the alternate execution route.
3. CallSite count is at least 4096, at most `Int.MAX_VALUE`, and greater than LIMIT.
4. Worker/range selection and per-predicate match-state arrays are established. Default local parallelism comes from `graphite.webgraph.callSiteScanParallelism`, bounded by actual processors; this is different from the Cypher graph-dispatcher property.
5. **Only when Split is absent and no retained index exists**, main attempts the initial count-array reservation (`:1142–1147`). Estimate overflow or reservation denial leaves it null. The raw query still runs; workers simply do not allocate capture arrays.

The initial reservation covers the estimate for four count arrays, not a blanket estimate of every worker capture allocation. Do not turn reservation denial into a query rejection, nor allocate/publish as if reservation always succeeds.

Each admitted worker consumes raw work before reading the four SIDs, fills its optional node/SID capture arrays, increments inspected count, and stops its own range after LIMIT matches. It finally flushes that worker's buffered work. Scan failures are collected and thrown **before** optional publication, with reservation cleanup; they are not swallowed as cache failures. Interrupted collection waits for submitted results, restores interruption, and throws cancellation. The actual no-hit query is useful because every range should be fully inspected; a hit can stop a range before its capture is complete.

## Completion gate, two passes, identity and publication

At `:1296–1317`, main attempts the optional build only if a reservation exists, every scheduled range was executed and returned a result, and every result satisfies `capturedCompleteIndex`: both capture arrays exist and `scannedCount == expectedCount` (`:97–107`). A last-position hit can still complete a range; "query hit" is not by itself a rejection rule. Incomplete capture closes the reservation without building. Results remain in worker/range order, not completion order.

`buildAndPublishCallSiteStringIndex` (`:1320–1435`) performs the following steps:

- Allocate four count arrays and derive unique counts from the captured SIDs. It dispatches work per property through `forEachCallSiteStringProperty` (`:1562–1622`). Each property has a buffered consumer, but **only property index 0 receives the request consumer**. A complete count pass therefore charges N units, not 4N. The charge is made while iterating the captured records; finally flush is part of failure behavior.
- Compute the complete retained-byte estimate from N, S and all four unique counts. Estimate failure or `reservation.tryGrowTo` denial closes the reservation and returns. This admission occurs after the count pass and before the posting pass.
- Build used-string directories, posting ends and node postings from the same captured records. The posting pass again charges N units only on property 0, with its own buffered finally flush. Directory loops, compact-end copying and the other three properties do real work without another request-unit charge.
- Shrink the reservation to retained size, compute content identity, construct the index, and only then synchronize on the retained-index lock. Publish if no other index has won; otherwise close the newly built object. A prior winner can therefore cause already incurred work to be discarded. Publication sets `loadedFromPersistence=false`.
- Any Throwable in the builder closes its reservation and is rethrown to the caller. Property workers use abort/polling and completion collection; a native implementation must not publish before sibling property work has completed.

The original raw scan is another N units when all N records are inspected. Thus the complete successful raw-scan-plus-publication path has three distinct N-unit traversals. The existing Go `PublishProjectionScan` builds entries directly under its lock and currently lacks the count reservation, retained-growth admission, these two work-consumer passes and captured identity construction. Charging an unconditional `2*N` at its entry would not preserve which pass or admission failed, the buffered failure point, or the resulting publication state.

**Captured identity is untracked here.** The helper at `MappedWebGraphBackedGraph.kt:1438–1458` calls `stringTable.contentIdentity()` with no consumer, hashes the CallSite count, then each captured node ID, its current node offset and four captured SIDs. It does not call the loader's `persistedCallSiteStringIndexContentIdentity(workConsumer)` and does not read `graph.callsite-string-content.identity`. With `graph.strings.identity` absent, `StringTable.contentIdentity` (`StringTable.kt:70–81,129–149`) genuinely decodes the ordered table and can publish its semantic-identity cache, but its default consumer is null: **do not add S request units or N identity units to this captured path**. File presence, semantic-cache state, hash bytes, decoding failures and the point of publication still matter. Identity failure after the two charged passes closes the reservation; identity computation must not be moved after retained publication or skipped merely because it is uncharged.

The captured constructor does not eagerly prepare trigram metadata/postings. Their state must be observed separately from a structural retained index. A subsequent query can create additional matching/trigram work; the recently captured build-trigram cases concern a different builder and cannot supply that follow-up result by substitution.

## Optional budget failures and request state

The caller rethrows `CancellationException` and swallows other `Exception` values from the optional build. `CypherBudgetExceededException` is a RuntimeException, not a CancellationException (`CypherExecutionBudget.kt:84–95`), so it is eligible to be swallowed **after the scan itself succeeded**. JVM Errors are not caught by this optional Exception catch.

`CypherWorkTracker.consume` first checks cancellation; on an oversized batch it atomically sets remaining work to zero and throws (`:113–126`). Swallowing the optional error does not refund work, cancel the signal, clear diagnostics, or replace the tracker. A query can therefore retain an already completed empty result while its context has no remaining work. Source inspection alone does not establish the final public result for every surrounding pipeline: the proposed oracle must capture rows/errors, all eight diagnostics, remaining units, cancellation and retained state before/after each operation. A second execute sharing that context and a fresh-context retry are distinct controls. Cancellation/timeouts must continue to escape; native recovery must not indiscriminately swallow all panics/errors as optional cache failures.

The count/posting finally flushes and cleanup must run before native error classification. The existing native caller ignores returned non-cancellation/non-close publication errors. Adding a callback that panics through Store can bypass that return boundary or strand owner state; use the established typed work-error bridge and cleanup discipline, and preserve the consumed work when the query layer intentionally ignores an optional failure.

## Persistence and memory controls

`GraphStore.kt:65–71,882–899` interprets `graphite.webgraph.prepareCallSiteStringIndexOnLoad=false` as disabling persisted restore while retaining lazy in-memory building. **Neither the capture reservation nor `buildAndPublishCallSiteStringIndex` is gated on persistence being enabled.** A persistence-disabled control should therefore observe in-memory behavior directly, not assume no index can be published.

At Close, main persists an in-memory index only when persistence is enabled and trigram postings are ready (`MappedWebGraphBackedGraph.kt:2010–2055`). A newly captured structural index alone does not meet that readiness condition. A later query can change readiness and therefore the final filesystem behavior. Keep ordinary query snapshots, explicit cache release if separately tested, and after-Close file manifests distinct. Do not infer unconditional persistence from the existence of a captured index, or assume disabling disk persistence clears it after every ordinary query.

The memory cap is the process-wide lazy `graphite.webgraph.callSiteStringIndexBudgetBytes`, default half max heap (`MappedCallSiteStringIndex.kt:2157–2206`). Count reservation size is `64 + 16*S`. Retained size is `464 + 16*N + 8*sum(U) + 8*S` for the current constants (`:2209–2245`, primitive-array header 16). These formulas select explicit admission experiments, not runtime work charges.

For the immutable large writer source N=262145, S=2, U=(1,1,1,1), the count estimate is 96 bytes and complete retained estimate is 4194832 bytes. Proposed isolated-JVM memory controls are cap 0 or 95 (initial capture denial), cap 96 (count admitted, retained growth denied), and a sufficient cap. Record actual global reserved bytes before/after and ensure unrelated stores/indexes do not occupy the cap. Since the cap is lazy/process-wide, changing a property between cases in one already initialized JVM is not a valid isolated control. These explicit memory configurations belong to a separate diagnostic/admission matrix; do not replace the default-configuration repeat with them.

## Proposed public matrix and evidence contract — not run

Create a new fixture variant by copying the existing actual-main large writer bytes from `mapped-oracle/large-writer-capture/fixtures.tar.gz` and removing **only** `graph.callsite-string-index`, recording its removed SHA. Preserve the original archive and all current cases. The normal variant keeps both identity files; a second removes `graph.strings.identity`, and a third can remove the independent graph content-identity file to establish that this helper does not consult it. Actual single-source selection and the parallel non-Split path need original source metadata/stack confirmation.

A bounded initial matrix should include:

| Control | Purpose |
| --- | --- |
| N-1, N, N+1 | Separate a failing raw scan from a successful scan whose optional count pass exhausts the budget. |
| 2N-1, 2N, 3N-1, 3N, ample budget | Locate count completion, retained growth and posting completion without assuming failed attempts are uncharged. |
| Same-context second query, then new-context ample-budget retry | Preserve exhausted/remaining request state while distinguishing absent, captured and further-prepared indexes. |
| Missing string identity at 3N and ample budget | Observe untracked semantic identity publication and exact hash; do not assume an added S-unit charge. |
| Missing independent graph identity | Distinguish captured identity construction from the persisted-loader helper. |
| Persistence disabled, with later query and Close | Separate in-memory publication, trigram readiness and disk writes. |
| Initial count denial and later retained-growth denial | Prove no capture vs charged count pass without publication; keep process-wide admission isolated. |
| An early per-range hit and a complete no-hit scan | Verify actual `capturedCompleteIndex` gating rather than equating LIMIT with partial capture. |
| A multi-source Split no-hit control | Confirm the same raw work does not opportunistically reserve/publish a captured index under Split. |

Use actual main executors, original writer bytes, two independent default-configuration captures, exact ordered operations, full public rows/errors/stacks and all eight diagnostics. Add true private/public observations for count reservation/global retained bytes where possible, retained presence and loaded-from-persistence, string identity cache/digest, trigram readiness, query caches and source state; observation must not prepare or consume work. Record original and changed fixture hashes through Close. Do not reuse the old harness's unconditional `prepareCallSiteStringIndexOnLoad=lazy` assignment for a persistence-disabled experiment: preserve the old harness and create an explicit new configuration-aware input/runner whose effective value is captured.

First report whatever the actual dispatcher and pipeline choose. If the query routes differently, retain that failed design attempt and introduce a separately named correction. Do not force the storage provider, privately consume budget, change budgets after seeing results, omit successful-query work, or present these source-based predictions as executed observations. Native tests and real64/P95 checks remain separate work after this oracle exists.
