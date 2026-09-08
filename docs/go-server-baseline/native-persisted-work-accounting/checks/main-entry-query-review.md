# Main query entry alignment: independent source review

Read-only review of the proposed query entry changes, against pinned main
`4e328b0109e13c896b74004823fb049fcb19251a`. No Go/JVM execution, benchmark,
production edit, or oracle rewrite was performed. The new Store entry APIs were
not yet present when reviewed; their implementation and cleanup behavior require
their own review. This document reviews the proposed contract, not a completed
implementation.

Source abbreviations below refer to the pinned checkout
`/tmp/graphite-go-main-baseline-clone-4e328b0`:

- **Graph**: `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt`.
- **Index**: `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndex.kt`.
- **View**: `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt`.
- **Pipeline**: `graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/QueryPipeline.kt`.

## Required distinctions

1. **Retained split lookups must perform exact matching before the node cache.**
   Graph 923–970 obtains retained storage, then computes exact matching for the
   Split consumer. All-empty exact matches return an empty CallSite sequence
   before `Index.matchingNodeIds`; nonempty matches attempt parallel raw scanning
   before falling back to the retained node iterator. With retained storage,
   `mappedView` is null, so ordered-prefix waves are false. Apply this to a newly
   loaded retained index as well as one present at function entry. A full split
   scan still performs the exact stage even though its node-count limit makes
   the bounded parallel shortcut ineligible.

2. **Retained exact eligibility differs from mapped eligibility.**
   Index 364–378 first checks every predicate, then maps predicates through
   `matchingStringIds`. It allows supported STARTS_WITH and ENDS_WITH as well as
   CONTAINS; equality, terms shorter than three UTF-16 units, and unsupported raw
   non-ASCII terms decline before any matching-cache access. View 69–80/516–518
   has its narrower CONTAINS capability. Reusing the current mapped-only gate
   would omit real retained entry behavior and alter cache/budget outcomes when
   a later predicate is ineligible.

3. **Retained exact matching has no request-local deduplication map.**
   Index 364–378 processes predicates in their original order. Each call may
   reuse the global matching-string cache (Index 339–360), but cache admission
   failure or eviction can require repeated work. View 69–80 explicitly has a
   local `matchesByPredicate`, so its shared map must not be generalized to the
   retained exact entry. Conversely, Index `matchingRanges` 265–296 explicitly
   shares both null and non-null matching results and runtime states locally;
   the existing `mainIndexMatchingRanges` sharing must remain intact.

4. **All-empty applies to the CallSite child, not unrelated generic candidates.**
   Graph 939 returns an empty sequence for its concrete CallSite lookup.
   `mainCandidateIterator` can additionally merge Annotation nodes when
   `plan.generic` is true. Preserve that child when skipping retained node-cache
   access; returning an empty iterator from the entire generic helper would
   incorrectly discard Annotation matches.

## Cancellation and publication boundaries

- A matching-string cache hit returns immediately without polling or work
  (Index 348–351). A node-cache hit first charges `max(cachedCount, 1)` and returns
  `cached.nodeIds.asSequence()` (Index 125–137), without another context check on
  every element or EOF. A main-only lifetime-safe getter and removing the
  cached iterator's extra `ctx.Err()` are justified. The work callback must
  still propagate its original request cancellation/budget error.
- Cache publication is also part of the contract. Index 139–160 publishes an
  empty result immediately or caches the consumed IDs only after complete
  iteration. These synchronized cache puts do not poll worker interruption.
  Changing getters alone leaves extra cancellation checks in Go
  `mainCacheNodes`, `CacheProjectionIDs`, and projected-row cache publication.
  Keep publication after completed work/EOF and after successful projection;
  do not move it earlier merely to preserve a cache count.
- Noncached retained iteration is a separate unresolved path. Index 1301–1339
  builds/sifts its heap before entering the try/finally loop, then polls at
  `inspected++ & 1023 == 0`. It charges only distinct IDs, flushes before yield,
  and resumes after yield before advancing or stopping at the limit. Current Go
  `mainIndexNodeIDs` checks at every Next, including completed EOF, and again
  for each heap candidate. Removing only the candidate decoder's outer check
  does not reproduce this path's cancellation behavior.
- Graph 943–946 and 966–970 map IDs through `node(NodeId(...))` without a new
  worker poll. A lifetime-only decoder is appropriate, while retaining their
  distinct cast semantics: mapped/parallel concrete casts can throw; retained
  `as? CallSiteNode` filters a wrong concrete type. Missing-node filtering and
  decoder errors must keep their original order relative to charged yields.
- Generic merging must be checked separately. Go `mainMergeNodeSequences`
  reads `ProjectionNodeOrder(ctx, ...)` for each child head. This can reintroduce
  polling after a lifetime-only CallSite decoder has been selected. Main's
  canonical-order merge (Pipeline 2924–2964) uses the graph order accessor
  without such a context-bearing wrapper. Raw and generic Annotation work
  tracking must not be globally disabled to remove the indexed-path polls.
- Preserve actual pipeline polling. In particular, `DirectStringSourceScanner`
  projects its row before `pollInterrupted()` (Pipeline 2734–2750), and the poll
  is periodic (2785–2790). Eliminating storage-only checks does not justify
  suppressing those source-scanner checks or request materialization checks.

## Scope and follow-up requirements

`lazyMain` is set both by ordinary projection's `mainSourceSpec()` and by generic
and streaming CallSite candidates in `main_string_candidates.go`. Its scope is
wider than ordinary projection. Preserve `forcePersisted` and `fullSplitScan`
consumer selection: PreferredPersisted is Serial, so a source count of 40 or
more does not by itself authorize the retained exact/split branch. The default
source-count policy does not establish support for custom JVM parallelism
properties.

`stringIndexMatches` is shared by indexed DISTINCT, ordinary retained matching,
and filtered-count paths; `mainIndexMatchingRanges` is also used by
`filteredCountStorage`. Its current signature does not carry `lazyMain`.
Consequently, an unconditional switch to no-poll cache or string accessors would
also change those callers. Make the intended entry policy explicit, or retain
the existing helper for other callers and add a narrowly scoped main entry.
Tests that directly construct `mainStringSourceSpec` without `lazyMain` are
another boundary that must not be silently reinterpreted.

The immediate implementation can align retained exact-first entry and cached
matching/node behavior, provided it keeps the distinctions above. It cannot
claim complete entry parity while noncached range matching, heap polling,
canonical-order merging, cache-publication checks, and split worker scheduling
remain unverified. Public actual-main cases should include mixed eligible and
ineligible predicates, all-empty exact results with a preexisting node cache,
freshly loaded retained storage, repeated predicates under cache admission
pressure, shared-context cancellation, and generic Annotation matches.
