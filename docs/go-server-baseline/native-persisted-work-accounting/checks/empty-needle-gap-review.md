# Empty-needle generic projection: bounded review

Read-only review of `six-empty-1` and `six-empty-2` in the 201-case lazy-core matrix. No production, oracle, comparator, or previous failure record was changed; no Go/JVM program, build, test, or benchmark was launched. Pinned main remains `4e328b0109e13c896b74004823fb049fcb19251a`.

## Finding

**These two cases show unstable reference row order, not a coalesce/value/UTF-16 defect.** Both original main captures and current native return six distinct rows. The full `rowsUTF16` row multisets are identical across all three captures, including every null, value and nested provenance array. The ordered arrays differ between the two independent default JVM captures and between native and either main capture. Under strict array equality, a single native result cannot simultaneously equal both differing reference arrays.

The matrix records these two mismatches under the public `rowsUTF16` field. The other compared response fields for these cases match. Main/repeat differ only in `rows` and `rowsUTF16`; this review does not claim native diagnostics parity because that wrapper records its diagnostic comparison as unavailable. Both source stores remain unretained/unmapped before and after these requests in all three captures.

The query is identical in both cases:

```cypher
MATCH (n)
WHERE toLower(coalesce(n.class, '')) CONTAINS $term
   OR toLower(coalesce(n.name, '')) CONTAINS $term
   OR toLower(coalesce(n.caller_class, '')) CONTAINS $term
   OR toLower(coalesce(n.caller_name, '')) CONTAINS $term
   OR toLower(coalesce(n.callee_class, '')) CONTAINS $term
   OR toLower(coalesce(n.callee_name, '')) CONTAINS $term
RETURN DISTINCT n.class, n.name, n.caller_class, n.caller_name,
                n.callee_class, n.callee_name
LIMIT 200
```

`term` is the empty string. `six-empty-1` opens one clean fixture (`single`); `six-empty-2` opens two in source order `z-first`, `a-second`. Every two-source row has the same complete metadata, `graphIds: ["a-second", "z-first"]`.

For readability only, the following row labels stand for complete six-column tuples; the raw evidence retains all fields:

- N: six nulls.
- A: class `Example`, name `Annotation`, four null CallSite columns.
- F: class `Example`, name `field`, four null CallSite columns.
- C: class/name null, caller `Example.run`, callee `Example.callee`.
- E: name `RED`, all other columns null.
- L: name `x`, all other columns null.

| Capture | `six-empty-1` order | `six-empty-2` order |
| --- | --- | --- |
| Original main | N,A,F,C,E,L | N,A,F,C,E,L |
| Independent main repeat | N,L,E,A,F,C | N,L,E,A,F,C |
| Current native | N,E,L,F,C,A | N,E,L,F,C,A |

These labels do not normalize the evidence or turn a mismatch into a pass. LIMIT200 does not truncate these six-row results. No new LIMIT/SKIP control was run in this review.

## Source cause

1. `QueryPipeline.kt:3018` explicitly declines direct string extraction when an operand coalesces a missing property to empty and the expected string is empty. Current `compileDistinctAtom` (`indexed_distinct_plan.go:164`) and the conjunction compiler (`lazy_filtered_plan.go:144`) preserve that guard. Removing it would wrongly restrict candidate types: missing properties can satisfy the original expression after coalescing. These cases must retain the generic Node source and evaluate the original WHERE, not reuse the fused string provider or fold the entire condition to true.
2. Main still uses a **filtered-node-limit fast dispatcher**, with its generic branch at `QueryPipeline.kt:1472–1499`. The generic provider is a fallback within that dispatcher, not necessarily the general executor. `nodeCandidates` at4580–4590 enumerates each graph's `nodes(Node.class)` in source order. It evaluates constraints/WHERE, projects the row, then inserts first-seen distinct values into a LinkedHashMap; cross-source duplicates add provenance without moving the row.
3. `MappedWebGraphBackedGraph.kt:262–263` delegates `nodes(type)` to `nodeTypeIndex.ids(type)`. `NodeTypeIndex.kt:86–90` uses an exact range for an exact class but otherwise traverses `rangesByType.entries`. That map is populated as a Java `HashMap<Class<out Node>,NodeTypeRange>` at94–109. Its superclass order therefore depends on process-local Class identity hashes. The iterator at135–163 preserves IDs within each chosen class range.
4. Current native `lazyGenericIDs` (`lazy_filtered.go:210–234`) uses `Store.QueryNodeIDs()` for an unlabeled Node scan. `query_order.go:11–13` returns persisted record order in MAPPED mode and explicitly records the unresolved JVM Class-identity-order boundary. `lazyFiltered` then projects/deduplicates in that encounter order. This explains its N,E,L,F,C,A order without indicating any string transformation or deduplication error.

The existing independently frozen `/tmp/graphite-mapped-class-order-audit` traces the same main source mechanism with actual post-query class hashes and type ranges. It records multiple default-JVM orderings and a separate LIMIT1 selection variation. This review only reads that prior evidence; it does not repeat or enlarge that experiment. Its key boundary applies here: runtime Class identity hashes are absent from the graph files, so persisted bytes plus query do not determine which observed main permutation to reproduce.

## Smallest faithful next action

There is **no justified coalesce, projection, or sorting production correction for these two records**. Preserve both main arrays and the native array. Classify these two failures separately as **reference order unstable**, while retaining their existing strict failure status and the complete 201-case/16-difference records. Record ordered comparisons and the diagnostic full-row multiset characterization separately. Do not modify the comparator, suppress the field, sort output, or hardcode the first JVM's type permutation this turn.

A future source-iteration correction can address a narrower deterministic contract: superclass enumeration emits complete concrete-class ranges and preserves stored IDs within each range, whereas native persisted-record order may interleave classes. That would require a main-specific source-order abstraction and interleaved-type controls. It would not select the correct process-specific class order or resolve strict equality to both references, and this one-node-per-kind fixture cannot establish that change. Treat that as a separate bounded source contract, not as an invented fix for these two cases.

Exact instance-specific ordering would need the reference process's complete class-range encounter order (including effects of empty Class keys), or an explicitly stable ordering contract in a newly identified main baseline. Neither input nor contract change exists here. The user’s complete parity target is unchanged; these two observations are not a pass.

## Downstream paths to preserve if source iteration is later addressed

- `lazyGenericIDs` and `lazyGenericNodes`: no-label/Node and supertype scans; exact concrete-type scans must retain their direct range order and complete decoder/error timing.
- `generic_distinct.go` also calls `QueryNodeIDs`; a global replacement would affect unrelated DISTINCT/source consumption. EAGER encounter grouping is already separately verified and must not change.
- Generic projection preserves first-seen DISTINCT tuples, original WHERE evaluation, inline constraints, early LIMIT, and complete qualified provenance. Altering source order can change the selected prefix and first decoder/evaluator error, not merely presentation.
- Existing `query_order_test.go` tests concrete persisted/type accessors and EAGER main oracle results. Its MAPPED persisted-order expectation documents current native behavior, not proof of main superclass total-order parity. Relevant regression sets include `lazy_filtered_test.go`, `lazy_filtered_protocol_test.go`, `lazy_filtered_slot_test.go`, and generic DISTINCT ownership/consumption tests.
- Before any later source change, use an interleaved multi-node concrete-type fixture, exact-type controls, generic no-limit and bounded DISTINCT/provenance controls, and original malformed-node order tests. Preserve both unstable reference arrays; no newly derived expected order should be supplied by native.

This investigation does not explain or reclassify the other fourteen matrix differences or any scheduler/64-graph state difference.

## Evidence identities

- `docs/go-server-baseline/native-generic-string-disjunction/main.json` SHA256 `3c45fe10bb660fd11f96211a85c7188ac6b429ba7147def746368c39f38cf91f`.

- `docs/go-server-baseline/native-generic-string-disjunction/repeat-main.json.gz` SHA256 `891bf0c101e67ca5540eb327d2356b7051f0fd290a16abcb3ab71f59deca8f31`.

- `docs/go-server-baseline/native-generic-string-disjunction/cases.json` SHA256 `d7bea6c61759fe2d24ffa9d1222eddcd506913e189854900dad628f93418fbc1`.

- `docs/go-server-baseline/native-generic-string-disjunction/verification.json` SHA256 `a77c9454e8d288a18ae364668d3eebe2a11e8bd50f9d9ec4063e492abcca51a0`.

- `docs/go-server-baseline/native-generic-string-disjunction/GenericDisjunctionOracle.java` SHA256 `200c14b294cefded0dd79ba0c4ba1f766b414cc01275695e97a2cbc588fe273c`.

- `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-lazy-core-matrix201-v1/go.json` SHA256 `019d2686f30dc022e98f8d47dbb4c92b06d622962c9f8c7c26acaf3f6f8b6203`.

- `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-lazy-core-matrix201-v1/comparison.json` SHA256 `2fb4cdf2617384d4ee1fbf821a296ca061f0c3bcc1422fa8906dd500034f714d`.

- `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-lazy-core-matrix201-v1/source-before.json` SHA256 `62e5e8c0b105841d6cc348a719f3fff0e767269e37fdcb8a7a473f8aa5ee3a44`.

- `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-lazy-core-matrix201-v1/source-after.json` SHA256 `3b85dce77e912e478eaaa2f025ba5f69bb7c79c8989f043dfa2f516575948bf4`.

- `graphite-server/internal/store/query_order.go` SHA256 `3b315e521f7239f373354a8a7fc97ea862ac33555204c5669829ff0d936ac7c8`.

- `graphite-server/internal/query/lazy_filtered.go` SHA256 `aada43f9c32f41b21a37714b03eb41eea15cf1bbedea24116d91ae7b681ac19e`.

- `graphite-server/internal/query/lazy_filtered_plan.go` SHA256 `d7890f4753c04b6a5af8bd3efdfd57060a4ad6d5507bab51223ee5e0ff159f2b`.

- `graphite-server/internal/query/indexed_distinct_plan.go` SHA256 `09a9282489dffdda9f790f92590ba798825deceb158d4da97d766c7f41992c52`.

- `/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/QueryPipeline.kt` SHA256 `9522dff099e2843f32115ae52f01adfb29c14d147dd3b9919daf907181c4f4ae`.

- `/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeTypeIndex.kt` SHA256 `476ffd5884114dc18beb37e03cf1da9e2a5fcb64092a244564d09b688a46ab2e`.

- `/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt` SHA256 `9133289c4382b59f4bc880f9de3d8aee0930d449a32f9a487893dabb56255cb7`.

- `/tmp/graphite-mapped-class-order-audit/README.md` SHA256 `6d165169456d18261e57d241c8bf7fd54edbd0f12e120adbf6b50c5b98db02c0`.

- `/tmp/graphite-mapped-class-order-audit/manifest.json` SHA256 `75d2d49187d58f7483646700d0d4bfe501635b6484b9b423bd7745e239d8638f`.
