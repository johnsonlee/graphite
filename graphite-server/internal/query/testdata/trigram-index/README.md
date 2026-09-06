# Bounded audit: persisted CallSite trigrams after Attempt6

This is source and tiny-fixture correctness evidence, not an implementation or a performance experiment. The isolated worktree is `/tmp/graphite-go-trigram-audit-after-attempt6`. Its effective base is commit `1c235916` plus the frozen Attempt6 patch SHA256 `22429f2b01531350bfd13c57eefb52c962a62e674353260d819a492dafb18e1e`. Those pre-existing production changes are not an audit delta. No production source was edited, no benchmark or HTTP process was run, and no commit was made.

## Main source and evidence

Pinned main is `4e328b0109e13c896b74004823fb049fcb19251a`. `TrigramOracle.java` calls the actual main private primitive functions through reflection, creates a 33-CallSite plus one-Int graph with main's real writer, asserts that main accepted its persisted CallSite index without rebuilding it, and captures complete scoped and two-source results. Java is 17.0.18+0 Homebrew ARM64, ROOT case with `-Dfile.encoding=UTF-8`. Jar and source hashes are recorded in `manifest.json`.

The oracle has 232 observations: 224 four-field OR queries (28 RHS vectors × raw/wrapped × typed/untyped × scoped/cross), plus eight Greek prefix/suffix observations. `verify.py` independently performs UTF-16 unit substring matching and candidate selection over main's captured numeric index. All 224 complete columns/rows/metadata match. It also checks all 92 required trigram pairs of the 37 used string IDs and both collision examples below. No claim is made that 232 finite cases prove general Unicode or malformed-file equivalence. The evidence is a semantic audit; there is no new native runtime to test with race/vet.

Inputs include empty/short strings, ASCII case differences, CJK, fullwidth digits, combining marks, Greek sigma context, U+0130 expansion, supplementary characters, isolated high/low surrogates, and a RHS beginning at the low surrogate of a valid pair. JSON stores unit arrays where raw strings would lose isolated surrogates during output encoding.

Reproduce:

```
python3 audit-trigram/run.py /path/to/pinned-main/graphite-explore/build/libs/graphite-explore.jar
python3 audit-trigram/verify.py
```

Relevant pinned Kotlin locations, relative to `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/`:

- `MappedCallSiteStringIndexView.kt:205`: compute all unique RHS hashes, reject a missing span, choose the shortest span, then exact-check every candidate against the ORIGINAL RHS. At 516 the mapped view requires CONTAINS, UTF-16 length at least three, and either LOWERCASE transform or raw ASCII RHS. At 520 the hash is `(u0 * 31 + u1) * 31 + u2` over UTF-16 code units.
- `MappedCallSiteStringIndex.kt:454`: heap candidate selection also supports STARTS/ENDS using first/last trigram, and intersects CONTAINS spans. It lowercases the RHS even for transformed predicates; the mapped view retains transformed RHS unchanged. Both finally exact-check the original RHS.
- `MappedCallSiteStringIndex.kt:2384–2405`: signature eligibility is ASCII CONTAINS with length at least three; the RHS signature helper lowercases each individual Char. It is not the Unicode metadata-building algorithm.
- `MappedCallSiteStringIndex.kt:2612,2635`: persisted signatures/postings are generated from each complete string's ROOT lowercase value, then its UTF-16 trigrams. Only SIDs used by the four CallSite property directories participate. Duplicate hashes per SID are removed.
- `MappedCallSiteStringIndex.kt:1228`: signatures are a rejection filter; exact string matching still follows.
- `MappedWebGraphBackedGraph.kt:3079,3127`: reusable LOWERCASE matching has an ASCII shortcut but delegates any non-ASCII actual value to full ROOT lowercase, retaining RHS. Its separate generic raw-string trigram index is not the persisted lowercase CallSite index and must not be confused with it.

## One proposed next experiment

Add a certified shortest-trigram candidate source for eligible CONTAINS atoms inside the existing pure-OR planner. Do not combine signature-only filtering, a lowercase cache, equality lookup, prefix/suffix acceleration, or a new result cache with this hypothesis.

For each eligible atom:

1. Keep Attempt6's existing parse/parameter/purity/null/type-inference and first-MATCH restrictions. Resolve each OR arm independently; different RHS values and case are never merged incorrectly.
2. Require UTF-16 RHS length >= 3. A raw atom additionally requires every RHS unit < 128. Lowercase raw ASCII RHS once for lookup; for a LOWERCASE atom use the ORIGINAL RHS units without lowercasing them.
3. Deduplicate all RHS trigram hashes. After proving index completeness, a missing posting is an available-empty result for that atom. Choose the smallest nonempty posting span as the anchor.
4. Decode only anchor candidate strings, run the existing exact Java-compatible transform and predicate with its original RHS, then request that atom's property CSR postings for matching SIDs. A candidate SID used only in another field yields empty postings for this field. There is no need to copy or traverse the whole property dictionary on this path.
5. Union all arms' node IDs, retain Attempt6's persisted-offset/source order, decode/publish through its existing candidate slot, and rerun the original WHERE/projection as today. Ineligible atoms use Attempt6 dictionary filtering; an OR must never silently lose one arm. If the whole expression fails Attempt6's admission proof, use its existing scan fallback.

The shortest-span rule is enough: a true match contains every necessary trigram, so it belongs to every span and therefore the chosen span. Hash collisions only add candidates. Intersecting all spans is not needed for correctness and is not part of this first proposal.

The current `TrigramStringIDs` copies a whole span. Calling it for every gram merely to discover sizes can copy many large spans. A minimal reader API could select the shortest span internally from a caller-provided hash set and copy only that span, returning explicit available-empty if any hash is absent. All binary searches and copying must hold the existing reader lifetime lock and poll cancellation. No mapped slice may escape. A separate private exact `(hash,SID)` binary-search helper can support the cold proof below without repeated copies.

Expected implementation surface, if authorized: `query/string_candidates.go` for per-atom selection; `store/callsite_index.go` or a new small reader extension for bounded anchor/exact-key access; a separate Store-lifetime trigram certificate and state; focused tests. Existing node enumeration, signature filtering, lower/case algorithms, early-limit, output, and HTTP need no change.

## Safe fallbacks and counterexamples

| Input | Rule and reason |
| --- | --- |
| Empty or fewer than 3 UTF-16 units | Dictionary fallback; no grams is not proof of no matches. Untyped `coalesce(property,'') CONTAINS ''` still requires full scan because non-CallSite kinds match. |
| Raw ASCII RHS, any actual string | Eligible. An ASCII substring survives ROOT lowercase; actual strings need not be ASCII. |
| Raw non-ASCII RHS | Dictionary fallback. Whole-string case depends on context. `XΟΣΑ CONTAINS XΟΣ` is true, but lower(actual)=`xοσα`, lower(RHS)=`xος`. `AΣ12 CONTAINS Σ12` is true, but the lower strings contain final/ordinary sigma respectively. Missing lower-RHS grams would incorrectly remove both matches. |
| Wrapped LOWER, non-ASCII RHS | Eligible at 3 units. Index and runtime both lower the full actual string, so its exact ORIGINAL RHS substring necessarily has all queried grams. Do not lowercase the RHS during final comparison. |
| Uppercase RHS under LOWER | Retain RHS as written. `toLower(actual) CONTAINS 'ABC'` does not become a search for `abc`. Querying original RHS grams may reject sooner and matches the mapped-view algorithm. |
| Supplementary and isolated surrogate units | Count/hash UTF-16, not bytes or Go runes. Emoji alone is 2 units and falls back; emoji plus `a` is 3 units. A low-surrogate-plus-`ab` RHS can match inside `😀ab`; index grams preserve that unit boundary. Use shared Java/WTF-8 conversion. Raw non-ASCII still falls back. |
| STARTS/ENDS, EQUALS, NOT, unsupported transforms | Existing Attempt6 path/fallback. Main has additional admissible prefix/suffix paths, but adding those is not necessary for this bounded CONTAINS hypothesis. |
| Annotation present or unsafe empty-null proof | Preserve Attempt6's scan fallback. Index source selection must not narrow the expression's kind membership. |

Full trigram collision: `aaz` and `ab[` have the same hash. Signature collision: `aaa` and `acc` have distinct hashes but the same two-bit signature. Neither artifact proves equality or substring membership. The signature formula is OR of bits `hash & 63` and `(hash xor (hash unsigned-shift-right 11) xor (hash shift-left 7)) & 63`. Per-character lower and whole ROOT lower differ for expansions/context; preserve the main signature eligibility if ever implemented separately.

## New cold proof is necessary

Attempt6 certifies full core decoding, candidate order, and CSR property association. Reader CRC/layout validation does not certify the semantic completeness of trigram or signature data. A legal CRC with a missing necessary `(hash,SID)` pair could pass those proofs and cause a false negative in this new path. Trusted content identity is not a proof of these additional derived fields.

A sufficient conservative proof is: after Attempt6 certification succeeds, collect the SIDs used by its four certified property directories; ROOT-lower each complete string with shared Java semantics; for every unique UTF-16 gram verify that the sorted persisted index contains `(hash,SID)`. Extra pairs only overselect and are harmless because of exact comparison. Unused SIDs do not need completeness. This proposal does not use signatures, so signature validation is unnecessary.

The proof is cold work inside the measured first query, not moved to unmeasured startup. Cache its valid/invalid result only for the same immutable Store lifetime. Invalid proof selects the already-certified Attempt6 dictionary path; it must not introduce a new early query error or silently accept incomplete candidates. Cancellation and Store.Close propagate, never publish a completed proof, and follow the same final-lock ordering and publication guard as Attempt6. Cached proof return also checks lifetime/context. Close must not race mapped reads. EAGER remains on its loaded-state scan path.

This is an additional cost/benefit hypothesis, not a guaranteed gain. The cold certificate must lower each used string and inspect its grams; common three-unit needles can have large anchors. Attempt6's known 61/80 corrupt-main differences remain unresolved and are not erased by this proof. Future differential tests must distinguish preserved native baseline behavior from actual main parity.

## Acceptance tests for a future implementation

Use the captured 224 full outputs and forced-A6 directory execution, including mixed eligible/ineligible OR arms with different RHS; available-empty vs unavailable; hash/signature collisions; RHS parameters (missing/null/non-string); empty-null untyped counterexamples; Annotation fallback; original node/source order and duplicate provenance; reused bindings/OPTIONAL and excluded operators; valid-CRC missing/extra trigram pairs; proof cancellation at its final publication boundary; close during proof and anchor copying; invalid-proof caching vs noncached cancellation; and a directory removed after EAGER load. Verify exact values/errors and no candidate publication on an interrupted preparation. Full module race/vet are required once code exists. Real64 performance and HTTP evidence remain the parent's separate work.
