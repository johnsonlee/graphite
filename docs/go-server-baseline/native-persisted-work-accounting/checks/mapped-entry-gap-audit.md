# Mapped entry work/cancellation attribution

This is a read-only source attribution of frozen actual-main storage-method captures E01–E10. No Go/JVM execution was performed for this audit, and no oracle or production file was changed. Main is pinned at `4e328b0109e13c896b74004823fb049fcb19251a`. The Go predictions below are **source inference**, not observed Go results or public Cypher failures.

## E01: the one unit is persisted graph identity retrieval

E01 records a single successful callback `[1]`, remaining999999, workUnitsConsumed1, signalCancelled=false and threadInterrupted=true. It then fails during sequence construction. The original stack is:

1. `MappedCallSiteStringIndexViewKt.checkViewInterrupted`, View.kt551.
2. `MappedCallSiteStringIndexView.Companion.load`, View.kt276.
3. `MappedWebGraphBackedGraph.mappedCallSiteStringIndexView`, Graph.kt1675.
4. `lookupStringPropertyDisjunction`, Graph.kt932.
5. Public `nodesByStringPropertyDisjunction`, Graph.kt319.

The source attribution is unambiguous: `mappedCallSiteStringIndexView` evaluates the arguments to `MappedCallSiteStringIndexView.load` before entering that method. Argument4 at Graph.kt1679 is `persistedCallSiteStringIndexContentIdentity(workConsumer)`. That helper, Graph.kt2414–2422, reads `graph.callsite-string-content.identity`; if it contains exactly32 bytes, it invokes `consumeGraphWork(workConsumer, 1L)` at2419 and returns those bytes. The immutable hit64 fixture has that32-byte file, SHA256 `10c7a837e495657a8e576a8d353882ee7aa9559a4b0f7c6d0fe589b6b4ac1a09`.

The1 is therefore **the persisted graph content identity retrieval charge**. It is not type-count work, node-offset inspection, StringTable semantic-identity calculation, mapped header validation, or the first trigram lookup. `nodeTypeIndex.count`, `stringTable.size` and `nodeOffsets.size` do not receive this work consumer. The separate `graph.strings.identity` fallback is not reached in E01. The actual callback delegates to CypherWorkTracker, whose cancellation check examines the request signal, not Thread.isInterrupted; the signal remains false, so this callback succeeds despite the thread flag.

## Exact cold order and first thread-interruption check

For this supported positive-limit CallSite lookup with the actual preferred-mapped Split consumer:

1. Validate nonempty supported predicates and positive limit, then increment lookupEntryCount. The prepared-fixture E01 count becomes1.
2. Examine the existing retained field. The preferred-mapped marker suppresses preferred retained loading and the split retained-preference mutation.
3. In `mappedCallSiteStringIndexView`, check marker/persistence/file-presence admission, existing view, unavailable flag, then repeat view/unavailable checks under its lock. None of these checks polls Thread.isInterrupted.
4. Read CallSite count and reject a nonpositive/too-large count. E01 count128 is admitted; this count read has no work callback.
5. Evaluate load arguments, including the persisted graph-identity read and callback1 described above.
6. Enter `View.load`. After its file-presence/identity-length guards, **View.kt276 calls `checkViewInterrupted()`**. View.kt550–552 tests `Thread.currentThread().isInterrupted` and throws `CancellationException("Mapped CallSite string index view interrupted")`.
7. Only after that checkpoint would main open the index FileChannel at278, check file size, map it, read the header and validate/charge data.

E01 stops at6. It never opens the sidecar through View.load, charges any header/CSR work, or publishes a view/range. The exception occurs before load's ordinary-invalid-file `try` block, so the graph's unavailable flag remains false. The single identity callback and lookup-entry increment survive.

A future cold integration must preserve this ordering. Do not add a synthetic unconditional consume1 before an early cancellation return: missing or invalid identity files take a different actual fallback path. Move/adapt the **actual identity retrieval** for this main-specific route, with its ordinary file/identity admission, then preserve the explicit loader checkpoint. Store lifetime/closing protection remains necessary throughout. This evidence does not authorize removing cancellation checks from generic Store APIs or from other request paths.

## Current Go ordering that prevents E01

`mainCandidateIterator` first calls `RetainedProjectionIndex(e.ctx)` (`main_string_source.go:31`); that getter checks `ctx.Err` before even returning an absent index (`ordinary_projection.go:19`). Thus an already-canceled worker context exits with zero work before the mapped route.

Bypassing only that getter is insufficient. `PrepareDistinctStringIndex` enters `prepareDistinctStringIndex`, which calls `prepareProjectionOffsets` before looking at cached views (`distinct_projection.go:289`). The offset helper polls ctx before its offsetsLoaded fast path. Preparation then checks ctx again before returning existing retained/mapped views. `tryMainCallSiteStringIndexWithWork` repeats offset preparation and ctx checks before its cached view and loader. Finally, `mainPersistedContentIdentity` itself starts with `indexCheck(ctx, closing)` **before reading the valid persisted identity file** (`main_persistent_identity.go:14`). Even if all outer entry checks were bypassed, this last check would still make E01 consume0. `loadMainMappedIndex` already has a checkpoint after the identity helper; that is the position corresponding to actual View.load's pre-open interruption check, once the preceding entry/identity checks are correctly scoped.

These are distinct cold and warm issues. A warm-only lifetime-checked getter can correctly preserve already-initialized capabilities without completing cold identity/checkpoint parity.

## E01–E10: likely current Go boundary, source inference only

For this comparison, main's **thread interrupt flag with a live cancellation signal** corresponds to an already-canceled worker `context.Context` with an independent, still-live Go ExecutionContext. It does not correspond to pre-canceling the request's signal. For callback controls, the comparison assumes the worker context or actual request signal is changed only after the specified successful work callback. No such Go adapter was executed in this audit. Scheduler admission can also prevent a worker from reaching storage, so this table does not predict a public fixed-worker execution result.

| Case | Observed actual main | Current Go inference / first likely difference |
| --- | --- | --- |
| E01 cold preinterrupt | Construction fails after identity1; no view, unavailable=false. | RetainedProjectionIndex returns context cancellation at0. After a warm getter change, remaining cold offset/preparation/identity entry checks still stop before identity1. |
| E02 warm mapped, absent preinterrupt | Construction + empty iteration succeed; work1, view retained. | Same initial retained getter rejects at0. Even bypassing it, PrepareDistinctStringIndex rejects before cached mapped view return. After a correctly scoped warm lookup, the absent trigram path has no anchor/poll and should flush1 then return empty. |
| E03 warm mapped, hit preinterrupt | Construction fails at range position0; callbacks[2,7], work9, range0. | Initial getter currently rejects at0. With correctly scoped warm lookup, existing pure mapped matcher/cursor source places the later checkpoint at the captured range0 boundary. |
| E04 warm mapped, callback sets interrupt | Construction fails at range0 after callbacks[2,7], work9. | Entry starts live, so getter/preparation checks are already passed. Pure matcher flush2, binary flush7 and range absolute0 poll appear to match. No separate entry mismatch is established by source; this needs an actual Go callback-control run. |
| E05 warm mapped, callback cancels signal | First callback consumes2; second attempted7 throws original signal instance; remaining999998. | Actual Go work tracker and WorkAbortedError cause unwrapping appear to preserve the same signal failure at binary finally. No new source-level failure identified; not claimed tested against E05. |
| E06 warm mapped, budget1 | Attempted callback2 throws original tracker budget exception; remaining0, work1. | Buffered matcher flush and tracker budget exhaustion appear to match. Not claimed executed against E06. |
| E07 existing retained, cached absent preinterrupt | Empty result succeeds at work0; exact-string cache short-circuits before node-match lookup. | Getter currently rejects at0. Merely bypassing it still does not reproduce the algorithm: Go's retained branch directly selects mainIndexNodeIDs; ProjectionCachedIDs polls ctx, and an empty node-cache hit would charge max(size,1)=1 after that check is bypassed. Main instead checks cached exact string matches first for the Split consumer and returns empty at0. |
| E08 existing retained, cached hit preinterrupt | Construction, node74 and EOF succeed; work1. | Getter currently rejects at0. Further obstacles remain in ProjectionCachedIDs, the cached-node iterator's explicit ctx check, outer mainCandidateIterator `e.check`, and ProjectionCandidateNode's offset/context polling. Warm getter alone cannot establish this end-to-end storage-method behavior. |
| E09 warm mapped **validated range**, preinterrupt | Construction/iterator succeed atwork9/cache1; first hasNext fails at visited0, no extra work. | Initial getter currently rejects at0; cached view preparation also rejects. After scoped warm lookup, mainSelectedMappedIDs/Store warm cursor source permits construction and polls visited0 when first called, matching the captured phase. Requires actual Go proof. |
| E10 warm mapped **validated range**, callback interrupt | Same construction success, first hasNext failure, work9/cache1. | Entry runs live. After callback2 cancels the worker, new main-only binary/cached cursor accessors do not poll ctx; first mapped iteration polls visited0. Source appears aligned, with no new entry mismatch identified. Not claimed executed against E10. |

## Why the mapped hit phase changes after warm range validation

The fixed writer's `hit` trigram anchor is at absolute trigram posting147, with SID1. Caller-name property1 row0 occupies node posting range `[0,1)`, yielding node74. The node's original ordinal64 is unrelated to that posting position. Mapped exact matching therefore does not poll at anchor147; its finally charges2. Directory binary search has no thread poll and finally charges7. Cold selected-range validation checks absolute node-posting0 and fails before range work, explaining E03/E04's9 units and cache0. A previously validated range bypasses that loop and constructs a cursor without a thread poll; the first merged-sequence iteration polls visited0 at View.kt144, explaining E09/E10's later failure and surviving cache1.

Raw E01–E08: `mapped-entry-oracle/main.json`, SHA256 `bee9be0ec1c1d6c5ec6b192769e056b56c3f57bc4a757fbfc04ae2d06e6e7635`. Raw E09–E10: `mapped-entry-oracle/warm-range-supplement/main.json`, SHA256 `f0616438b4b19bbbebd66514ae1f80d27aae170dfb06a3c481cce98d650e7c69`. Both pairs repeat exactly. This audit does not revise their expectations, test results, or any64-graph reference, and makes no performance/P95 claim.

## Reviewed Go source identities

These bind the source-inference statements to the files read during this audit.

- `internal/query/main_string_source.go`: `52db857b9889e460bf5b27c79dcf38c5a7be216de69445e79824761c0cd38185`
- `internal/query/main_string_postings.go`: `bd0b8bf1eb5d78b753d67e87d1370f4ac4702aaaa3b7b5082ca857e208ec535d`
- `internal/query/main_string_mapped.go`: `f10241eac34d57936662a289a0da21b222b68c5bc44523651b218900f4529004`
- `internal/query/ordinary_parallel.go`: `5b35f43f05f82142e13e645fb3245d20ad34f0d7bddab44f07deaa351458142c`
- `internal/store/ordinary_projection.go`: `a337a47ad844f18974bdc56a133ec3c27e7fb7a739c00393fefdb48e3830963f`
- `internal/store/distinct_projection.go`: `e18d68536ce2f076ccc2b958c7ecb6326542cbe2a359efb1140dc9d271ad38d5`
- `internal/store/main_callsite_index.go`: `b630063c4bec9baf428298da5daa99bae5c2fabfb6cb5f2656241b11c6dff26c`
- `internal/store/main_persistent_identity.go`: `f84c4a9e3bf3c4dc0e9ef8564196ba613705b22ba7c25406885fef2def364d9f`
- `internal/store/main_mapped_work_reader.go`: `003aced116310b28ac4be8154c5e5117cd2900dbebc37bc8c056f508116fa80d`
- `internal/store/projection_cache.go`: `5bb87b8979611beb5eac6e07c1c1d39d40fbd980b0b36d7e55837cb1bc3456d0`
- `internal/store/projection_node.go`: `fa1b8a55a6da9783823884f8f0418d722808cd9ef0fd2f650271e5bd0a1edc02`
