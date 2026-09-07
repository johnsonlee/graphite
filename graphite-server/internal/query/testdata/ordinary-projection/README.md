# Ordinary indexed projection compatibility

This functional delta follows main `4e328b0109e13c896b74004823fb049fcb19251a` on Java 17.0.18. It is based on effective base `53f3e7561d504b6773fec3f2a4f3b02a421a0e23`: root `bbdfae2a07b629c40d1ef0db6d156bff500989c9` plus the unchanged frozen indexed DISTINCT patch. The root's later DISTINCT nil-graph integration correction is a separate prerequisite at integration, not silently included here. Ordinary execution itself declines a nil unqualified graph.

The main jar is `/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar`, SHA256 `91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`. Java and jar commands, complete rows/errors, state observations, and failed intermediate native captures are retained beside this document. Nothing here is a performance measurement. All graphs used by this delta are small synthetic correctness fixtures; 4096 records and 40 sources are necessary to enter main's specific execution branches. The cache-threshold fixture has only two nodes and long strings to exercise the 1 MiB strategy transition.

## Execution contract

The ordinary hook recognizes a real AST: one mandatory, unbound single-node MATCH followed by non-DISTINCT RETURN with a positive literal LIMIT. It accepts the existing indexed WHERE compiler's four CallSite string properties, nonempty string matchers, pure OR, supported wrappers, literal/parameter operands, and graph-route constraints. Arbitrary aliases are preserved. RETURN keeps its actual expression AST; literal/property projections can enter the parallel scanner, while the serial/leading executor evaluates ordinary expressions. OPTIONAL, inline properties, residual predicates, ORDER BY, SKIP, WITH, aggregate and unbounded shapes remain on the existing executor. Graph selection precedes source consumption. Unlabelled/Node queries also merge Annotation values in mapped encounter order; an existing sidecar does not prove the absence of annotations.

The raw ordinary projection capability is based on an already retained index, not on an index file or an A6-certified reader. One selected source, or a graph-scoped set of retained sources, can read only projected SIDs and skip an unrelated bad core field. Other ordinary candidates must consume full nodes. A successful index initialization can survive a subsequently failing full-node read, so the same query may fail first and succeed through raw projection on its next execution. Raw rows include main's `single` or source graph metadata, including when that changes between cold and warm requests.

The Store distinguishes three states: an A6 reader view, an explicitly initialized projection mapped view, and a retained string index. A preferred mapped view can survive a zero-hit DISTINCT release without making the retained capability true. These are observable in later ordinary requests. A6's full-node certificate is unchanged.

Single-source cold bounded scans with at least 4096 CallSites and LIMIT below node count split the raw four-SID candidate scan into main's ranges. Every started range is joined, matching IDs merge in range order, and full-node decoding occurs only as IDs are consumed. A complete scan can publish an optional in-memory index. Failure to build that optional cache does not replace the completed query result; partial scans never publish it. Required field errors in an executed range are not suppressed because another range filled LIMIT. At 40 sources, mapped CONTAINS views, exact SID membership, split raw scans and bounded leading projections follow separate capabilities and consumption rules.

Below 40 sources, parallel-safe work uses complete waves; arbitrary projections use the serial executor. At 40 sources, the leading source is evaluated before the bounded source-ordered rolling window. The contiguous prefix determines completion and suffix cancellation. Full state captures show that speculative suffix initialization can vary between schedules, even though all 144 repeated rolling-query responses were identical. Tests compare complete responses and retain the original diagnostic states; they do not demand one incidental speculative schedule.

Main's parallel scanner has a deliberate compatibility edge: after an empty leading source, an unsafe RETURN expression such as `substring(...)` can reach its Literal/Property-only projection switch and throw `IllegalStateException: Unsafe expression reached parallel string projection`. The same expression can succeed on the serial or leading path. A valid but incorrect core tag also differs by storage path: serial raw candidates can preserve the node, retained lookups filter it out, and mapped/parallel class casts can throw. The new tests assert those differences rather than applying one global cast policy.

## Candidate decoding and errors

`ProjectionCandidateNode` reads a consumed mapped CallSite with Java's element-by-element collection semantics. Negative counts produce empty lists; oversized positive counts reach the first failing SID/read instead of being rejected by a preliminary size check. It does not change `Store.Node`, REST validation, or the storage cursor implementation. Other runtime node kinds continue through the existing decoder. Generic merging looks up the decoded payload ID's mapped position, matching main's bad-ID error timing.

`StringTableReferenceError{Index, Size}` preserves the old Store diagnostic string exactly. Query consumption renders Java's List index class/message. Serial dense string matchers and parallel raw matchers instead preserve the preceding Java array lookup's exception. Null-message bounds and EOF exceptions remain distinct. The 336 required-history requests cover tags, payload IDs, negative/overflowing/outside parameter counts, required and unrelated SIDs, absent/outside/overflowing/aliased offsets, and present/missing index files.

## Caches and graph close

The three retained caches use main's independent 32-entry, 2 MiB access-order LRUs. String keys omit the property; node keys include ordered predicates and LIMIT; projection keys add ordered storage properties but exclude aliases. Duplicate properties and their decoded string occurrences count individually. Node IDs publish only when their sequence completes (or immediately for an empty result), and projected rows publish only after successful complete materialization. Cached values are copied at the Store boundary, and graph IDs/aliases are applied when returning a row. Failures do not cause a blanket rollback of earlier cache publications or recency.

`ProjectionPlannerBytes` is the Java reservation estimate used for execution selection, not a measurement of Go memory. It includes structural arrays, prepared trigram postings and all three cache estimates. The 38-step Java oracle proves a concrete transition: 1006 bytes per graph after warmup; 1,121,950 bytes in the first graph after a large ordinary projection; then 89,926 bytes after LRU eviction. The same cross-graph query succeeds, fails on the later corrupt graph after crossing the threshold, and succeeds again after eviction. Native responses and every recorded byte estimate agree.

A built index becomes persistable only after real trigram preparation. `Store.Close` performs a best-effort atomic v2 sidecar handoff before unmapping. A partial/canceled preparation does not produce a sidecar; failed replacement does not change Close's existing result. This is actual interoperable data, not a presence marker. Native-generated clean and unrelated-core-corrupt indexes are read by pinned Java with `loadedFromPersistence=true`; all four complete follow-up responses agree. The clean 66,148-byte output is byte-for-byte equal to the original main sidecar. Tests also cover rejected replacement, cleanup, concurrent Close, immutable cache results, and retry/cancellation boundaries.

## Evidence and remaining scope

`verification/original-summary.json` preserves the original 1048-pair denominator. The current delta has 966 complete matches versus 739 at the DISTINCT freeze: 227 additional matches. All original 580 DISTINCT-eligible cases still match. The remaining 82 are retained explicitly: 58 main-success/native-error, 20 different errors, and 4 different successful bodies. They principally involve other DISTINCT projections, ORDER/SKIP/residual predicates, unbounded projection timing and empty-needle generic traversal.

The new Annotation corpus has 144 requests: all 84 ordinary-eligible cases match, as do 48 declined cases; 12 declined generic differences remain. Required-history, dense/history/rolling/cache and valid-tag suites assert complete responses. Histories with one source or deterministic warmup also assert the retained/view states; speculative multi-source state is evidence, not an asserted universal schedule.

This is not a claim of 100% parity. Known remaining boundaries include:

* The prior DISTINCT implementation lacks main's separately built exact projection tuple representation. Persisted indexes can build it for sufficiently large selected-value work, retaining `160 + 12 * nextPowerOfTwo(2N)` bytes after temporary storage is released. Ordinary execution must not fabricate that state without the corresponding DISTINCT reads and errors.
* JVM-global memory-budget admission/eviction and custom JVM system-property overrides have no native-equivalent configuration here. LRU per-cache admission is modeled; global JVM heap-budget denial is not. The old mapped reservation close race is not reproduced or hidden.
* Native context cancellation has no public analogue of a Java GraphWork callback that merely sets Thread.interrupt and returns. Main can publish a cache after that callback or refresh LRU before a callback throws. The delta does not roll back already-applied effects, but the Java direct-callback oracle is not misrepresented as an end-to-end native cancellation test.
* General non-CallSite decoding and the explicit declined corpus retain earlier semantic gaps. This delta does not broaden raw projection eligibility to conceal them.

The independent cache lifecycle audit supplied by the query agent is `/tmp/graphite-main-cache-lifecycle-audit`; its manifest SHA256 is `e24ad3754d7fe6acb60309fb5ba4e718fe96766141b4a07d537711bf52252442`.

## Reproduction

From the `graphite-server` directory, choose new output directories when regenerating evidence:

```sh
ORDINARY_HISTORY_OUTPUT=/tmp/ordinary-native-new INDEXED_DISTINCT_OUTPUT=/tmp/ordinary-original-new go test -race ./...
go vet ./...
python3 internal/query/testdata/ordinary-projection/capture-writer.py --output /tmp/ordinary-writer-new
```

The Java sources and `capture-*.py` files recreate tiny main observations. Their checked-in default destinations are evidence files: copy the directory or redirect outputs before regeneration. `CacheThresholdOracle.java` also generates its complete two-node fixtures; `ValidTagHistoryOracle.java` uses the documented byte mutation from `valid-tag-command.json`. The final freeze manifest records source, tests, raw evidence and validation logs. No root checkout, A6/A7 experiment, frozen DISTINCT receipt, real persisted graph or 64-graph runtime was modified by this task.
