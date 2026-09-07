# Indexed DISTINCT string projection: functional parity evidence

This is a functional change from native base `bbdfae2a07b629c40d1ef0db6d156bff500989c9`, compared with main `4e328b0109e13c896b74004823fb049fcb19251a`. It implements main's indexed **DISTINCT** string projection. It does not enable ordinary indexed projection, claim complete Cypher parity, or provide performance evidence.

The main oracle uses Java 17.0.18 and the pinned `graphite-explore.jar` (SHA256 `91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`). Commands, raw complete results, and Java source are included. The 4096-node fixture exists solely because main's storage branch has an exact 4096-node eligibility boundary. The 9- and 40-source tests repeat tiny fixtures solely to exercise source scheduling and provenance. No timings, throughput, allocation, or speedup are inferred from these fixtures.

## Result denominator

`verification-summary.json` enumerates all **1048** pairs. **580 eligible-path cases match completely**, including columns, row order, nulls, provenance, error class, and nullable error message. Of the 468 declined cases, 159 match and 309 preserve outstanding behavior differences: 180 both-error class/message differences, 122 main-success/native-error differences, and 7 both-success differences. Overall 739/1048 pairs match. The 309 declined differences are not removed, reclassified as successes, or claimed fixed.

| Corpus | Pairs | Purpose |
| --- | ---: | --- |
| Prior frozen raw-projection audit | 512 | Original 432 primary, 64 supplementary, and 16 source-pair cases; every result is replayed, with AST eligibility recorded separately |
| Required reads | 336 | Four queries in scoped/cross form across 14 node-tag/ID/count/SID/offset mutations, with valid index, missing index, and missing identity sidecar |
| Annotation/mixed | 144 | 24 queries on mixed, Annotation-before, and Annotation-after fixtures; 16 explicitly eligible AST shapes and 8 decline boundaries per fixture |
| Split storage | 50 | 4096 records, 9/40 sources, five query shapes, clean/count/tag/callee-SID/missing-index cases |
| Late-source exclusion | 6 | graphId excludes a late source with malformed required core and missing identity, while an unqualified tuple must still consume and report its failure |

`audit-*` contains a copy of the earlier read-only audit, not rewritten evidence. `verification-native` is the final native replay. Earlier `native-*` directories preserve intermediate observations and are not the acceptance result. `PlannerOracle.java` preserves UTF-16 code units by escaping surrogate code units in its file output. For additional fixtures, separately named `*-wire-main.json` applies Java's final UTF-8 scalar-value encoding to compare against the public Go `Result.Rows` API, which already performs that conversion. Valid U+FFFD and surrogate pairs remain distinct from isolated surrogates. The original JVM results remain in `*-main.json`.

## AST and graph eligibility

The compiler examines AST nodes, never query text. It accepts one mandatory MATCH containing one named node, an attached complete WHERE disjunction, and a DISTINCT RETURN of direct properties followed by a positive literal LIMIT. The node may be unlabelled, Node, CallSite, or CallSiteNode. It rejects multiple labels, inline node properties, relationship/path patterns, OPTIONAL, bound/preexisting rows, WITH, star, aggregates, ORDER BY, projection expressions, and nonliteral LIMIT. SKIP uses the same literal planner conversion as main; the retained count includes SKIP and the emitted rows drop it only after provenance collection.

Projection properties are the four CallSite strings plus graphId/class/name. Aliases, duplicate aliases, `$metadata`, and final row-order materialization are preserved. WHERE supports main's direct property, exact one-argument toLower/toLowercase(property), and exact toLower(coalesce(property,'')) forms; literal/string-parameter operands; equality/CONTAINS/STARTS WITH/ENDS WITH; literal-list IN; and a matching exists(property) guard. Every OR term must compile and at least one term must concern a CallSite string. Extra eager function arguments, toString wrappers, residual AND expressions, and empty coalesced needles decline. The generic executor continues to own those cases.

For the no-SKIP fast shape, pure graphId constraints are removed only through main's graph-route AST rules and select sources before the raw path. The SKIP streaming shape does not acquire this extra route qualification. Selected sources must all be MAPPED; EAGER uses the existing executor. No per-source LIMIT substitutes for global DISTINCT selection: later sources still contribute provenance for every retained visible tuple. Source graphId is `single` for the unqualified main executor, and raw rows receive its metadata just as main does.

## Representation and execution

* `ProjectionStringID` reads precisely the requested mapped field. It does not validate a node tag/ID or unrelated return type. Callee addressing preserves Java int32 overflow and negative-count behavior. `ProjectionStringIDs` instead mirrors `withRawCallSiteStringIds`: it reads the caller count/address and all four integers before evaluating any SID consumer.
* Existing persisted-index CRC/layout checks remain. Projection validates posting encounter order using actual `graph.nodeoffsets`, not the general node-index offsets. The shared missing-identity derivation now reads only main's four raw SIDs and offset in MAPPED mode. A6's independent full-node certificate remains intact; strict core decoding is still performed when its node candidates are consumed.
* A present index file selects the main one-source prepared/serial mode even when index validation later rejects the file. Without a usable retained/persisted index, serial raw scans validate only actual predicate and projected SIDs, while a parallel index build consumes all four SIDs. The 336 required-read cases distinguish these branches.
* At 40+ sources, a graph with at least 4096 CallSites and a retained count below its node count uses split raw projection, including when a mapped index provides exact matching SID sets. Selected-value membership is checked before projected string materialization in this path. All started storage segments are joined; failures are not suppressed because an earlier segment found LIMIT.
* Source execution follows main's default scheduling: legacy fixed waves below 40, a leading graph probe and a source-ordered bounded window at 40+. The latter stops when the contiguous prefix fills LIMIT and cancels/joins the speculative suffix. Provenance tasks still consume all relevant remaining sources. A graphId-incompatible selected tuple does not initialize that source's raw index, but its generic candidates are still considered.
* Annotation/Field/LocalVariable/Enum candidates supplement relevant generic properties. Generic rows and raw CallSite rows merge by actual mapped encounter position. The Annotation fixtures explicitly put its physical record before and after the CallSites; no arbitrary type sorting is introduced.

## Retention and cancellation contract

Successful persisted loading or complete index construction publishes a DISTINCT retained-index marker. Failed/canceled initialization does not publish it. Serial raw fallback does not publish it. A preferred mapped view used for eligible split projection does not itself publish the retained-index marker. A zero-hit multi-source merge releases the marker; active immutable handles remain usable until Store.Close. This marker is deliberately not used to enable ordinary projection in this change.

Close clears DISTINCT-owned state under the existing index lifetime lock. Reads and initialization observe context cancellation and the closed flag; no mapped data is accessed after unmap. Concurrent-close and deterministic canceled-initialization tests verify these boundaries and retry behavior. This change does not reproduce main's unsafe reservation-close race or add a workaround baseline configuration.

Required mapped reads can throw Java IndexOutOfBoundsException with a null message. `store.ProjectionReadError` and `query.Error.NullMessage` preserve that distinction. `JavaMessage()` returns nil for oracle comparison; `Error()` supplies main's HTTP fallback `Query execution failed`. Existing string-valued errors retain their behavior. Unknown-node-tag propagation remains the earlier typed-tag implementation.

## Main source evidence

All paths below are relative to pinned main:

* `graphite-cypher/.../QueryPipeline.kt`: `tryFastFilteredNodeLimit` (1292), `executeIndexedDistinctStringProjection` (2146), raw/generic projection materialization (2339/2371), complete-task and ordered-prefix runners (2409/2479), `DirectStringDisjunction` (3090), streaming filtered limit (3312), and storage work-consumer selection (211).
* `graphite-webgraph/.../MappedWebGraphBackedGraph.kt`: DISTINCT dispatch (368), split raw projection (481), serial raw projection (676), preflight (1619), prepared/retained capabilities (1707), retained split policy (2057), identity hashing (216), raw four-SID reads (2448), and selective property SID reads (2523).
* `graphite-webgraph/.../MappedCallSiteStringIndex.kt`: `distinctProjection` (765), prefix and selected-value paths, raw SID tuple identity, string materialization, and encounter order.

## Reproduction

From `graphite-server`:

```sh
INDEXED_DISTINCT_OUTPUT=testdata/indexed-distinct/verification-native go test -race ./...
go vet ./...
python3 internal/query/testdata/indexed-distinct/summarize.py
```

The `capture-required.py` flags `--missing-index` and `--missing-identity`, `capture-additional.py`, `capture-split.py`, and `capture-late.py` reproduce the new main observations against the pinned jar. They use temporary fixture copies and do not run an HTTP/performance server. Java and jar paths are recorded in each `*-command*.json`. Regeneration is an explicit new evidence run; do not overwrite the frozen verification receipt.

Remaining functional work includes ordinary indexed projection and its retained-history behavior, the declined error-class differences, unlabelled empty-needle/generic encounter order, and broader 100% parity. Default runtime scheduling is covered here; no equivalence claim is made for an unimplemented mapping of custom JVM system-property overrides. Real64 performance verification belongs to a separate parent-run experiment after independent integration review.
