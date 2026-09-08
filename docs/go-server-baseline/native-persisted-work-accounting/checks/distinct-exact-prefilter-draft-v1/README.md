# DISTINCT exact prefilter review draft — not applied or tested

The candidate patch replaces the supported Split string-directory prefilter with actual existing retained/mapped matcher helpers. It is a bounded matching bridge, NOT a claim that the entire existing DISTINCT storage protocol already matches main. No workspace file is changed. No Go/JVM/build/test/profile/replay is run. Input hashes bind the current individual files; no whole module was copied.

## Patch scope

`candidate-matching.patch` changes indexed_distinct.go and adds distinct_split_exact.go. It propagates a query-local exact map from preparation into both first-prefix and selected-tuple raw projection calls. A nil map means unsupported; a supported empty map means a proved CallSite miss. Generic candidates still run in the callers. Predicates are evaluated by the existing helpers in original order, then their returned sets are OR-unioned separately for each property. The predicate list itself is not collapsed by term or property.

For an existing preferred mapped view, a successful nonempty preflight is forwarded directly into the qualifying parallel raw projection (source count >=40, CallSites >=4096, LIMIT<N), rather than dropping its result and loading a retained index. A mapped miss still avoids unrelated retained/node-order reads. For a retained index, mainRetainedExactMatches checks all predicates before the first lookup and uses the real retained LRU. For a mapped index, mainExactMatches uses its own invocation-local matching reuse. Their capability checks, matching work and errors are not replaced by a cheaper predicate.

The patch retains the existing fallback for unsupported exact predicates, generic supplementation, selected-target filtering, tuple projection, source ordering, LIMIT, and provenance. It does not change the raw projection worker/merge algorithm. It still uses the existing old directory fallback when exact is unsupported; do not claim that fallback has been independently re-proven here. Small graphs that reject parallel raw projection can still use the existing directory-based retained projection after a nonempty exact preflight; replacing that projection is a different scope from this prefilter.

## Why the complete reader bridge is NOT a one-line option change

Main MappedWebGraphBackedGraph.kt:387-431 has this order:

1. Non-preferred Split marks retained-persistence retention before reading the cached index field.
2. Use a current retained index. Otherwise only Serial/non-preferred Split attempts the optional retained load; PreferredMapped attempts the mapped view, with its file-presence gate even when the mapped field is already populated.
3. With the actual retained or mapped representation, do exact-string matching. All-empty returns before raw projection or property/node-offset lookup. Nonempty exact results are supplied to parallelRawDistinctCallSiteStringProjection.
4. If parallel raw declines and a retained index exists, call retained distinctProjection. If no retained index exists, only then perform the nonserial cannot-match preflight; if needed try parallel raw without exact; finally load/build the retained index and do distinctProjection.

The current `prepareDistinctSourceIndex` uses old `PrepareDistinctStringIndex` with neither MainSource nor ConsumeWork. Its caller cannot infer the main representation from PreferredMapped alone: `InitializeMappedView` selects the real mapped loader; `MainSource=true` without that still selects the retained loader. `PreferMappedView` alone currently mostly changes publication. The old !MainSource branch also validates every property posting/node order, while main's mapped loader deliberately does not perform those reads.

Therefore the matching patch deliberately does not sprinkle MainSource/ConsumeWork into that old call. Keeping existing reader validation for this bounded draft avoids silently deleting validation, but leaves a known loader-routing/accounting mismatch on cold/fallback paths. A full main-semantic integration must introduce a distinct Split **load-only preparation** boundary before selecting fallback, not call the old combined load/build API indiscriminately.

Proposed Store interface for that separate patch:

    PrepareMainDistinctSplitIndex(ctx, preferMapped, consumeWork)
      -> (index, representation{none,retained,mapped}, error)

It should set the non-preferred retention flag before a lifetime-only cached-index read; for a cache miss, invoke the existing main retained/mapped reader without opportunistic raw/build fallback. It must publish the resulting DistinctStringIndex/cache owner consistently with ordinary entry. It must carry the real worker context into actual reader checkpoints and the real work callback into identity/read/CRC/EOF. It must not replace them by context.Background or globally disable cancellation. The current mainOrdinaryEntryKey only changes metadata polling for that route; extending its policy to DISTINCT is a deliberate API/semantic change requiring the same entry-phase tests, not a harmless rename.

After a none result, the query layer must follow step4 above. In particular, do not invoke the retained reader a second time before an eligible raw fallback: that creates extra identity/reader work after an earlier optional-load rejection. Conversely main's final callSiteStringIndex can genuinely retry a rejected optional reader before building; do not eliminate that later read. Missing-sidecar and malformed-sidecar admission are distinct. Preserve sticky mapped-unavailable, retryable retained failure, retention/cache release, and Store-close behavior.

Existing reader implementation to reuse: main_callsite_index.go:29; main_persistent_identity.go:14; main_persistent_reader.go:71,175 (retained primitive accounting with final flush and separate EOF); main_mapped_work_reader.go:18,116 (mapped validate-complete-chunk then charge, no finally flush). A cached successfully published representation does not repeat cold CRC/identity; an absent/failed representation cannot bypass those checks merely because a matching string is absent.

## Further existing protocol differences to validate, not hide

- distinctCannotMatch currently loops by property atom and has no buffered consume; main:1619 deduplicates transform/mode/expected, polls at its defined boundaries and flushes actual work. A full reader migration exposes this fallback and must use its real protocol.
- distinctRawRange currently has an every-node e.check and no consume(1), whereas main parallel raw uses an inspected counter checkpoint plus BufferedGraphWorkConsumer in finally (main:560-612). Do not label the matching draft as complete work-budget/cancellation equivalence for positive raw scans.
- Existing retained exact helper still contains Go matching/intersection checkpoints and serial implementation. Main permits parallel candidate selection/matching above its thresholds. Existing passing oracles cover bounded controls, not every large candidate interleaving.
- The existing outer DISTINCT source e.check, cache accessors, and release paths are not converted by this patch. E01-E10 ordinary method evidence cannot automatically prove DISTINCT entry interruption equivalence.
- Query-local exact results must not become a new persistent cache. Retained LRU refusal/eviction can cause repeated matching; only mapped's documented invocation-local dictionary gives unconditional within-call sharing.

## Verification inventory — evidence exists, this patch has no passes

Reuse the actual main outputs/fixtures in native-distinct-capability-boundary (46 primary plus8 supplement) for count0,1/2/39/40 sources, preferred/nonpreferred, missing/invalid sidecar and unavailable-vs-empty. Reuse native-generic-provenance-prefix, including its initial missing-CallSite controls, for generic continuation and stop-after-consumption. Reuse native-persisted-work-accounting original52/187, mapped small/large and build-trigram oracles for loader/order/identity/CRC/EOF/work/cache protocols; their current queries are not automatically DISTINCT witnesses. Reuse its mapped-entry/mapped-cursor oracles for exact helper/storage checkpoint controls, with new DISTINCT public adapters required rather than rebranding their results. Reuse native-work-context's budget/cancel/error protocols without weakening diagnostics. All original failures and exceptions remain evidence.

Necessary new actual-main DISTINCT public controls before calling the full bridge equivalent:

- 39/40 sources; CallSite N=0,1,4095,4096; LIMIT=N-1/N; no SKIP vs SKIP0 (changes preference); already retained, already mapped, fresh prepared, missing/malformed sidecars.
- Exact miss, property-specific hit, two predicates on one property, one term across different properties, mixed transforms/operators and an unsupported predicate after a supported one. Verify all-capability admission precedes work.
- Empty/1/2/3 UTF16-unit needles, wrapped coalesce empty fallback, raw non-ASCII versus LOWERCASE Unicode (including surrogate pair/lone surrogate and context-sensitive case mapping), CONTAINS vs prefix/suffix/EQUAL.
- Supported exact miss plus corrupt raw SID/node order: assert main skips only reads it actually skips. Corrupt magic/layout/identity/CSR/CRC/trailing bytes before first load: preserve real optional failure and fallback; repeat with a new context on the same Store.
- Successful/failed retained cache fill, repeated predicates when LRU admits/refuses, mapped local reuse, low-budget follow-up, preinterrupt warm entry, callback cancellation, exact exception cause and all diagnostics before/after.
- Positive exact raw projection with budget exhaustion and decode failure at checkpoints; LIMIT cancellation, worker join and publication; generic Annotation match/error after a CallSite miss; selected tuples/provenance across later sources, including rawTargets empty.

Only after correctness should the real64 campaign be rerun with fresh candidate identity. The earlier n=3 latency diagnostics identify a priority; no speedup, runtime path frequency, final P95, or acceptance pass is asserted by this draft.
