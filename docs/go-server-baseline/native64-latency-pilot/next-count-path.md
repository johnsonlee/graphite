# Next experiment: filtered string aggregation

Read-only diagnosis, 2026-09-08. Main reference remains
`4e328b0109e13c896b74004823fb049fcb19251a`. No Go source change,
benchmark run, or CPU profile was made for this diagnosis.

## Measured observations

The complete ordered cold pilot contains these single observations. These are
not per-case P95 estimates and do not establish performance acceptance.

| Index (zero based) | Case | Main ms | Go ms |
|---|---|---:|---:|
| 838 | filtered-count-zero | 1.564000 | 11055.763375 |
| 839 | filtered-count-targeted | 24.882250 | 1958.953041 |
| 840 | filtered-count-dense | 18.264583 | 885.804584 |
| 841 | filtered-distinct-count-zero | 0.672917 | 2.191333 |
| 842 | filtered-distinct-count-targeted | 30.581125 | 3567.049083 |
| 843 | filtered-distinct-count-dense | 44.055833 | 3362.055875 |

Sources: [main cold observations](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/docs/go-server-baseline/native64-latency-pilot/main-cold-run2/capture/main-observations.tsv) and
[Go cold observations](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/docs/go-server-baseline/native64-latency-pilot/native-cold-run1/capture/observations.jsonl). The six pairs were independently checked for
identical normalized outcome, row count, response bytes and digest. The separate
whole-run verifier owns the complete 1,267-case audit.

The frozen [workload](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server/internal/benchmarkcase/testdata/main64.json)
places the first `count(...)` query at index 838:

```cypher
MATCH (n)
WHERE n.caller_class CONTAINS 'GraphitePressureAbsent27zeroX'
   OR n.caller_name CONTAINS 'GraphitePressureAbsent27zeroX'
   OR n.callee_class CONTAINS 'GraphitePressureAbsent27zeroX'
   OR n.callee_name CONTAINS 'GraphitePressureAbsent27zeroX'
RETURN count(*) AS matches
```

There is no request graph selection or timeout override: all 64 sources enter
in original order, with a 60,000 ms timeout. Index 841 is a different absent term
with `count(DISTINCT n.caller_class)`, not a repeated observation of 838. Its short
Go duration is consistent with one-time preparation at 838; this timing pattern
alone does not prove the cause.

## Source-backed path difference

Main's [QueryPipeline.kt](/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/QueryPipeline.kt:1060) admits `tryFastFilteredStringCount`. It recognizes COUNT
and COUNT(DISTINCT), compiles candidate predicates and takes a raw-property path
when the exact predicate and counted expression permit it. The dispatcher at
[QueryPipeline.kt](/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/QueryPipeline.kt:1096) can execute graph partials concurrently.
`exactStringCountPartial` at [QueryPipeline.kt](/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/QueryPipeline.kt:1203) iterates supported concrete types,
delegates supported properties to storage aggregation and preserves candidate
fallback for other types. A present work tracker selects the work-aware overload.

[MappedWebGraphBackedGraph.kt](/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt:344) implements CallSite aggregation. Without a retained index it may
first apply the original dictionary preflight, subject to its explicit node-count,
term-length and operator conditions. Otherwise it obtains the index and calls
`aggregate`. [MappedCallSiteStringIndex.kt](/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndex.kt:235) returns zero when matching posting ranges are empty,
without decoding every graph node. Nonempty ranges aggregate counts or distinct
property values at storage level. This pilot does not dynamically identify whether
dictionary preflight or retained-index range matching supplied its zero result.

Go's [query/engine.go](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server/internal/query/engine.go:160) has no corresponding filtered-string aggregation
dispatcher. [query/ordinary_projection.go](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server/internal/query/ordinary_projection.go:28) and
[query/lazy_filtered.go](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server/internal/query/lazy_filtered.go:55) reject aggregates. The ordinary MATCH path calls
[query/scan.go](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server/internal/query/scan.go:11) and its indexed candidate walker.
[query/string_candidates.go](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server/internal/query/string_candidates.go:163) prepares selected sources sequentially,
calling candidate certification at line 181 and trigram certification at line 206
before publishing candidates.

The first candidate certificate at [store/candidate_certificate.go](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server/internal/store/candidate_certificate.go:97)
decodes **every node**, checks encounter order and verifies all four property
posting memberships against raw CallSite records. Its result is cached on the
Store. [store/trigram_certificate.go](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server/internal/store/trigram_certificate.go:76) additionally validates trigram
completeness for property-directory string IDs and caches that result.
Matching candidates pass through [query/string_candidates.go](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server/internal/query/string_candidates.go:323) for node
decoding and [query/scan.go](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server/internal/query/scan.go:31) for binding, WHERE evaluation and matching-row
materialization before ordinary aggregation. This differs from main's storage
aggregation even after certificate caches are populated.

## Hypothesis and limits

The leading hypothesis is that case 838 pays substantial first-use candidate and
trigram certification work which main's specialized aggregation avoids. The source
proves those eager operations exist on Go's admitted path, and the timing sequence
supports investigating them first. It does **not** prove which certificates were
uncached on all 64 sources, whether any source fell back, or the fraction of the
11.056 seconds spent in each operation. **No CPU attribution or allocation profile
has been collected.** Later targeted/dense count gaps are consistent with matching
row materialization and sequential preparation; their cost split remains unknown.

## Next bounded experiment

1. After the frozen paired pilot is terminal, collect a separately labelled
   diagnostic CPU profile around case 838 during a fresh real64 **complete ordered
   workload** replay. Execute every preceding case unchanged to recreate cache
   history, then continue through the remaining cases. If needed, record certificate
   invocations and cache outcomes to distinguish preparation from fallback. Keep
   instrumentation outside acceptance evidence; do not move certification into
   untimed setup or repeatedly execute only one case in place.
2. If attribution confirms the mismatch, make one independent optimization attempt
   that ports main's actual filtered-string aggregation admission, graph-partial
   execution and storage aggregation. Preserve COUNT null behavior, distinct-value
   equivalence, Long result type, source provenance, annotation and other concrete
   type fallbacks, cancellation and work-aware dispatch.
3. Independently establish actual main behavior on malformed stores/sidecars,
   zero/nonzero predicates, missing counted properties and cancellation. Preserve
   lazy/deferred reads and errors at their original point of consumption. **Do not
   simply delete or bypass certificates in the generic scanner**: they protect that
   scanner's separate full-node decoding and candidate-skipping semantics. The
   specialized path may avoid unrelated reads only where main's admission and
   storage behavior authorize it.
4. Run complete ordered real64 correctness/state replay and paired timings on the
   frozen candidate. Retain all 1,267 cases and the original known failure. Record
   the optimization in the existing chronological attempts log. Single observations
   remain diagnostic; final per-case P95 requires the frozen repetition protocol.

## Evidence hashes

- `main-observations.tsv`: `9008b30e94214097922dedb4812f4b83911a9712ae73877d3da2dfe81607e2b4`
- `observations.jsonl`: `c94ac92b70fd315c14409adc777bc20eac095cf7a485fd1e8efc2858b2076ac4`
- `main64.json`: `378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023`
- `QueryPipeline.kt`: `9522dff099e2843f32115ae52f01adfb29c14d147dd3b9919daf907181c4f4ae`
- `MappedWebGraphBackedGraph.kt`: `9133289c4382b59f4bc880f9de3d8aee0930d449a32f9a487893dabb56255cb7`
- `MappedCallSiteStringIndex.kt`: `c1c4f07d818a4a25f712ad25094b30723fa25fa7531f424b2391bba792938a77`
- `candidate_certificate.go`: `49bf7397cca779b3cb576fa3f03dcbe329f373945fc4f3582e98a9e5582e7290`
- `trigram_certificate.go`: `5868a1a37ad31a9b3f8f6441fc12fdd91327f85960f0307bbfa28d33330b3bac`
- `string_candidates.go`: `63344549fe967273de844afa738c312809e422e8ed653bae7fc29f8fd8deaf8a`
