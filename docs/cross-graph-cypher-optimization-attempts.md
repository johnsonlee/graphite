# Cross-Graph Cypher Optimization Attempts

This log records each benchmark and implementation attempt for cross-graph
Cypher performance. Product changes are accepted only when existing query
semantics and performance guardrails remain intact.

## Goal

- Improve the representative cross-graph agent workflow by at least `10x`.
- Preserve complete cross-graph identity, provenance, aggregation, and traversal
  semantics.
- Avoid regressions in the existing `CypherBenchmark` and end-to-end graph
  benchmark.

### 2026-08-26 - Attempt 000: Representative cross-graph JMH baseline

**Problem:** the existing Cypher benchmark uses one graph with roughly one
thousand nodes. Its variable-length path fixture contains only one-hop edges,
and it does not cover graph-qualified identity or an agent's search-then-expand
workflow. It therefore cannot validate cross-graph performance work.

**Benchmark design:** add `CrossGraphCypherBenchmark` with 16 graphs and 5,000
nodes per graph. Local node IDs intentionally collide across graphs. The only
keyword hit and the eight-edge call chain are in the final graph, ensuring that
the baseline cannot stop after scanning an early graph.

The benchmark covers:

- a keyword miss that must inspect all 80,000 candidate nodes
- a late keyword hit in the final graph
- a two-request agent workflow that discovers an `elementId`, then uses it as
  the seed for a variable-length call-chain query

This attempt changes benchmark and documentation code only. Baseline results
were measured with:

```shell
./gradlew :cypher:jmh \
  -Pjmh.filter='.*CrossGraphCypherBenchmark.*' \
  --no-daemon
```

**Environment:** Apple M3 Max, 64 GiB RAM, macOS 14.3 arm64, OpenJDK
17.0.18, JMH 1.37, one benchmark thread, one fork, three 1-second warmups,
and five 1-second measurements.

| Benchmark | `main` baseline |
|-----------|----------------:|
| `keywordMissAcrossAllGraphs` | `5.018 ms/op` |
| `keywordLateHitAcrossAllGraphs` | `4.974 ms/op` |
| `keywordThenCallChain` | `13.875 ms/op` |

**Conclusion:** baseline established. A miss and a late hit cost the same,
confirming that the filtered fast path still scans every qualified candidate
when fewer than the requested 20 rows match. The two-stage workflow adds a
second full candidate scan plus eager `WITH` materialization and path traversal.

### 2026-08-26 - Attempt 001: Defer filtered-row provenance

**Hypothesis:** the filtered-node fast path adds a provenance set to every
candidate binding before evaluating `WHERE`. Misses discard that binding
immediately, so broad low-selectivity searches allocate provenance for all
80,000 candidates while only zero or one row reaches the result. Moving
provenance creation after a successful predicate should preserve visible and
metadata semantics while reducing allocation and CPU cost.

**Build/save impact:** none. This only changes query execution order for
internal result metadata that is not visible to `WHERE` expressions.

**Validation:**

```shell
./gradlew :cypher:test :cypher:jmh \
  -Pjmh.filter='.*CrossGraphCypherBenchmark.*' \
  --no-daemon

java -jar graphite-cypher/build/libs/cypher-1.0.0-SNAPSHOT-jmh.jar \
  '.*CrossGraphCypherBenchmark.(keywordLateHitAcrossAllGraphs|keywordThenCallChain)' \
  -wi 2 -i 3 -w 1s -r 1s -f 1 -prof gc
```

| Benchmark | Baseline | Attempt 001 | Speedup |
|-----------|---------:|------------:|--------:|
| `keywordMissAcrossAllGraphs` | `5.018 ms/op` | `2.809 ms/op` | `1.79x` |
| `keywordLateHitAcrossAllGraphs` | `4.974 ms/op` | `2.794 ms/op` | `1.78x` |
| `keywordThenCallChain` | `13.875 ms/op` | `10.882 ms/op` | `1.28x` |

| Allocation | Baseline | Attempt 001 | Change |
|------------|---------:|------------:|-------:|
| `keywordLateHitAcrossAllGraphs` | `35.250 MB/op` | `15.410 MB/op` | `-56.3%` |
| `keywordThenCallChain` | `83.335 MB/op` | `63.495 MB/op` | `-23.8%` |

**Conclusion:** effective and retained, but short of the `10x` target. Deferring
provenance removes most allocation from the filtered fast path. The remaining
keyword cost still creates a qualified node and binding map per candidate, and
the second workflow query still uses the eager generic `WITH` pipeline.

### 2026-08-26 - Attempt 002: Reuse filtered predicate bindings

**Hypothesis:** after Attempt 001, every candidate still creates a new mutable
binding map solely so the expression evaluator can read one variable. A single
map can be reused while scanning because predicate evaluation does not retain
the input binding. Only successful candidates need a durable binding for
projection and provenance.

**Semantic boundary:** expressions that create nested bindings remain safe:
the evaluator copies the input map before adding list or predicate variables.
The reusable map is never returned or stored in a result row.

**Build/save impact:** none. Results are recorded after running the same tests,
JMH benchmark, and GC profiler as Attempt 001.

| Benchmark | Baseline | Attempt 001 | Attempt 002 | Speedup vs baseline |
|-----------|---------:|------------:|------------:|--------------------:|
| `keywordMissAcrossAllGraphs` | `5.018 ms/op` | `2.809 ms/op` | `2.073 ms/op` | `2.42x` |
| `keywordLateHitAcrossAllGraphs` | `4.974 ms/op` | `2.794 ms/op` | `2.225 ms/op` | `2.24x` |
| `keywordThenCallChain` | `13.875 ms/op` | `10.882 ms/op` | `9.599 ms/op` | `1.45x` |

| Allocation | Baseline | Attempt 001 | Attempt 002 | Change vs baseline |
|------------|---------:|------------:|------------:|-------------------:|
| `keywordLateHitAcrossAllGraphs` | `35.250 MB/op` | `15.410 MB/op` | `1.970 MB/op` | `-94.4%` |
| `keywordThenCallChain` | `83.335 MB/op` | `63.495 MB/op` | `48.774 MB/op` | `-41.5%` |

**Conclusion:** effective and retained, but still short of the `10x` target.
The filtered scan now allocates very little per candidate. Its remaining cost
is qualified-node construction plus generic expression dispatch. The workflow
remains dominated by the eager `MATCH -> WHERE -> WITH` seed lookup.

### 2026-08-26 - Attempt 003: Compile direct string filters

**Hypothesis:** common keyword discovery uses a node property with a literal
`STARTS WITH`, `ENDS WITH`, or `CONTAINS` predicate. The AST is constant for the
query, so resolving that shape and dispatching through the generic expression
evaluator for every candidate is unnecessary. A compiled predicate can inspect
the raw node and create a graph-qualified value only for matches.

**Semantic boundary:** the direct path is limited to a single node label, no
inline node properties, one literal string predicate, a non-aggregate return,
and a literal limit. All other expressions continue through the existing
evaluator. Tests assert result values, qualified identity, result order, and
provenance for all three string operators.

**Build/save impact:** none. The graph representation and indexes are unchanged.
Results are recorded after tests, JMH, and allocation profiling.

| Benchmark | Baseline | Attempt 002 | Attempt 003 | Speedup vs baseline |
|-----------|---------:|------------:|------------:|--------------------:|
| `keywordMissAcrossAllGraphs` | `5.018 ms/op` | `2.073 ms/op` | `0.437 ms/op` | `11.48x` |
| `keywordLateHitAcrossAllGraphs` | `4.974 ms/op` | `2.225 ms/op` | `0.437 ms/op` | `11.38x` |
| `keywordThenCallChain` | `13.875 ms/op` | `9.599 ms/op` | `8.145 ms/op` | `1.70x` |

`keywordLateHitAcrossAllGraphs` allocation falls from `35.250 MB/op` at
baseline to `0.048 MB/op`, a `99.86%` reduction, with no measured collections
during the profiler run.

**Conclusion:** effective and retained. Attempt 003 crosses the `10x` target
for both worst-case keyword query shapes. It does not yet cross the target for
the complete agent workflow because the graph-qualified seed query still scans
and materializes all 80,000 candidates before `WITH`.

### 2026-08-26 - Attempt 004: Seek graph-qualified element IDs

**Hypothesis:** after keyword discovery, the agent already has a globally unique
`elementId`, but `MATCH (n) WHERE elementId(n) = 'graph:id' WITH n ...` still
scans every node. Pushing this equality into `MATCH` can select the owning graph
and call `Graph.node(NodeId)` directly before the rest of the pipeline runs.

**Semantic boundary:** the seek applies only to a non-optional, single-node
`MATCH` immediately followed by equality between a literal string and either
`elementId(variable)`, `variable.elementId`, or `variable.qualifiedId`. The
resolved candidate is still checked against labels, inline properties, and the
original complete `WHERE` expression. Missing graphs, malformed IDs, missing
nodes, and label mismatches produce no rows. Every other shape falls back to
the existing matcher.

**Build/save impact:** none. The optimization uses the existing graph ID list
and `Graph.node` lookup. Results are recorded after tests and benchmarks.

| Benchmark | Baseline | Attempt 003 | Attempt 004 | Speedup vs baseline |
|-----------|---------:|------------:|------------:|--------------------:|
| `keywordMissAcrossAllGraphs` | `5.018 ms/op` | `0.437 ms/op` | `0.436 ms/op` | `11.51x` |
| `keywordLateHitAcrossAllGraphs` | `4.974 ms/op` | `0.437 ms/op` | `0.441 ms/op` | `11.28x` |
| `keywordThenCallChain` | `13.875 ms/op` | `8.145 ms/op` | `0.466 ms/op` | `29.77x` |

Workflow allocation falls from `83.335 MB/op` at baseline to `0.126 MB/op`, a
`99.85%` reduction.

**Conclusion:** effective and retained. Attempt 004 crosses the `10x` target
for the complete search-then-expand workflow while preserving the keyword gains.
The result confirms that direct graph-qualified lookup, rather than parallel
fan-out, removes the dominant second-stage CPU and allocation cost.

### 2026-08-26 - Attempt 005: Mapped cross-graph benchmark guardrail

**Problem:** the method-level benchmark uses `DefaultGraph`, whose nodes are
already materialized. Production explorer sessions use mapped WebGraph storage,
where a scan must decode each node from mmap. An optimization that only removes
heap-object overhead could overstate the real improvement.

**Benchmark design:** add `CrossGraphMappedQueryBenchmark` in the WebGraph JMH
module. It persists and maps the same 16 graphs, 80,000 colliding local node IDs,
late keyword hit, and eight-edge call chain as the in-memory benchmark. Setup
asserts the keyword result and complete call-chain row count before measurement.

The exact same benchmark commit is run on this branch and on an `origin/main`
worktree so both implementations use an identical fixture and harness. This
attempt changes benchmark and documentation code only.

| Mapped benchmark | `main` | Attempts 001-004 | Speedup |
|------------------|-------:|-----------------:|--------:|
| `keywordMissAcrossAllMappedGraphs` | `14.399 ms/op` | `6.628 ms/op` | `2.17x` |
| `keywordLateHitAcrossAllMappedGraphs` | `14.719 ms/op` | `6.279 ms/op` | `2.34x` |
| `keywordThenMappedCallChain` | `32.087 ms/op` | `6.510 ms/op` | `4.93x` |

**Conclusion:** the mapped guardrail disproves completion at Attempt 004. The
in-memory target is met, but mapped node decoding keeps the production workflow
below `10x`. Further work must avoid deserializing every mapped node during
keyword discovery.

### 2026-08-26 - Attempt 006: Lazy mapped string-property index

**Hypothesis:** mapped storage already has node offsets, type IDs, and a shared
string table. A bounded lazy index of `(nodeId, stringTableId)` can be built from
raw mmap fields without deserializing nodes. A trigram dictionary over distinct
property strings can then answer arbitrary `CONTAINS` terms without rescanning
all strings; only matched nodes are materialized.

**Design:** add an optional `Graph.nodesByStringProperty` capability. Unsupported
graphs and properties retain the direct scan from Attempt 003. Mapped graphs
support common string fields on constants, call sites, fields, locals,
parameters, enum constants, and resource files. They retain at most four
property indexes and 32 small predicate results. Trigram construction is
disabled above 500,000 distinct property strings, and large match sets are not
cached, bounding retained memory.

The mapped benchmark adds a unique keyword miss on every invocation so a fixed
literal result cache cannot manufacture the reported gain. Results are recorded
after unit, mapped integration, performance, and memory regression checks.

**Cold-start policy:** the first access to a supported `(type, property)` uses
the existing direct scan. The second access builds the index. This avoids making
a one-off query pay the index construction cost. The benchmark therefore covers
the first query, the first two queries together, repeated fixed queries, and
repeated queries with a different keyword on every invocation.

An implementation variant using a primitive integer set reduced one-time index
allocation by 12%, but increased the two-query time from `21.9` to `23.2 ms/op`.
It was rejected because reducing CPU time is the primary objective.

**Validation commands:**

```shell
./gradlew :core:test :cypher:test :webgraph:test --no-daemon

./gradlew :webgraph:jmh \
  -Pjmh.filter='CrossGraphMappedQueryBenchmark' \
  --no-daemon

java -jar graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
  '.*CrossGraphMappedQueryBenchmark.(uncachedKeywordMissAcrossAllMappedGraphs|coldTwoKeywordSearchesAcrossAllMappedGraphs)' \
  -wi 2 -i 3 -w 1s -r 1s -f 1 -prof gc
```

The baseline was measured from `main` commit `e4d1c6a` in a separate worktree
with the same benchmark fixture. For the two new controls, the benchmark source
was copied to the baseline worktree; the index-reset call was omitted because
`main` has no index. All other fixture and JMH settings were identical.

| Mapped benchmark | `main` | Attempt 006 | Speedup |
|------------------|-------:|------------:|--------:|
| `coldKeywordLateHitAcrossAllMappedGraphs` | `14.719 ms/op` | `6.850 ms/op` | `2.15x` |
| `coldTwoKeywordSearchesAcrossAllMappedGraphs` | `28.463 ms/op` | `21.890 ms/op` | `1.30x` |
| `keywordMissAcrossAllMappedGraphs` | `14.399 ms/op` | `0.019 ms/op` | `757.84x` |
| `keywordLateHitAcrossAllMappedGraphs` | `14.719 ms/op` | `0.060 ms/op` | `245.32x` |
| `uncachedKeywordMissAcrossAllMappedGraphs` | `15.080 ms/op` | `0.020 ms/op` | `754.00x` |
| `keywordThenMappedCallChain` | `32.087 ms/op` | `0.091 ms/op` | `352.60x` |

The unique-keyword benchmark proves that the steady-state improvement does not
come from caching complete query results. It uses the already-built trigram
dictionary to resolve a new predicate each time. The first query is `2.15x`
faster due to Attempts 001-003; the first two queries, including full index
construction, are cumulatively `1.30x` faster than `main`. The target is reached
for the long-lived mapped graph and agent workflow, not claimed for cold start.

| Allocation benchmark | `main` | Attempt 006 | Change |
|----------------------|-------:|------------:|-------:|
| `uncachedKeywordMissAcrossAllMappedGraphs` | `47.333 MB/op` | `0.052 MB/op` | `-99.89%` |
| `coldTwoKeywordSearchesAcrossAllMappedGraphs` | `94.666 MB/op` | `51.321 MB/op` | `-45.78%` |

**Conclusion:** retained. Mapped steady-state keyword search and the complete
search-then-expand workflow exceed the `10x` target while cold and amortized
costs remain below `main`. The index is lazy, bounded to four properties per
mapped graph, and leaves unsupported query shapes on the existing execution
path. Save format, build behavior, eager graphs, and public Cypher results are
unchanged.

### 2026-08-26 - Attempt 007: Preserve qualified-property semantics

**Review finding:** the direct string-filter compiler accepted virtual
cross-graph properties (`graphId`, `elementId`, and `qualifiedId`) but evaluated
them against a raw node. It also rejected an existing empty graph namespace
when seeking an element ID such as `:1`.

**Fix:** virtual qualified properties now stay on the generic evaluator, where
the binding is a `QualifiedNode`. Element-ID parsing accepts a separator at
offset zero while continuing to reject missing separators and missing local
IDs. Regression tests assert concrete results for string operations on all
three virtual properties and for an empty graph namespace.

**Conclusion:** retained. This restores behavior present on `main`; it does not
change the indexed raw-property path or its benchmark results.

### 2026-08-27 - Attempt 008: Demand-aware admission and byte budgets

**Review findings:** the second access built a complete property and trigram
index even when both queries found their `LIMIT 1` result at the first node. A
100,000-node reproduction took `21.922 ms` and allocated about `39.9 MB`, versus
`0.029 ms` and `79 KB` on `main`. The 500,000-distinct-string guard also bounded
dictionary cardinality rather than trigram postings or retained bytes.

**Design:** Cypher now passes its remaining result limit to the storage-aware
lookup. Before an index exists, a finite query lazily scans raw mmap string IDs
and materializes matching nodes only. If the consumer satisfies its limit in
the first 256 nodes, no index access is recorded. A scan that crosses that
threshold marks the property as worth indexing on its next access. This keeps
early hits at scan cost while making a one-off late hit or miss cheaper than
deserializing every node.

Index retention has independent conservative budgets:

| Retained structure | Per-property limit |
|--------------------|-------------------:|
| node ID, string ID, and unique-string arrays | `8 MiB` |
| trigram builders/postings | `16 MiB`, `1,000,000` postings |
| predicate-result cache | `2 MiB`, 32 entries |

The predicate-result estimate includes both matching ID arrays and the retained
query string, so long agent-generated literals consume the same byte budget.
The existing four-property LRU therefore has an estimated upper bound of
`104 MiB` per mapped graph for these structures, rather than an unbounded size
hidden behind an entry count. Trigram construction still rejects more than
500,000 unique strings. Crossing either trigram limit discards the partial
builder and scans the unique-string dictionary; crossing the base-array budget
keeps finite Cypher queries on the raw-field scan and unlimited callers on the
existing fallback. Neither condition throws or changes query results.

The early-hit and cold-query controls use the same 16 persisted graphs and
80,000 nodes on `main` and this branch:

```shell
java -jar graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
  '.*CrossGraphMappedQueryBenchmark.(coldTwoEarlyHitKeywordSearchesAcrossAllMappedGraphs|coldKeywordLateHitAcrossAllMappedGraphs|coldTwoKeywordSearchesAcrossAllMappedGraphs)' \
  -wi 2 -i 5 -w 1s -r 1s -f 1 -prof gc
```

| Mapped benchmark | `main` | Attempt 006 | Attempt 008 |
|------------------|-------:|------------:|------------:|
| two early `LIMIT 1` searches | `0.038 ms` | not measured on this fixture | `0.038 ms` |
| cold late hit | `14.719 ms` | `6.850 ms` | `4.853 ms` |
| two cold late/miss searches | `29.292 ms` | `21.890 ms` | `18.438 ms` |

Early-hit allocation is `96.860 KB/op` on Attempt 008 versus `96.723 KB/op` on
the identical `main` fixture. Two cold searches allocate `41.310 MB/op` versus
`97.225 MB/op` on `main`, a 57.5% reduction. Steady-state unique misses remain
`0.020 ms/op`, and keyword-then-call-chain remains `0.093 ms/op`.

**Retained-memory control:** setup builds one `StringConstant.value` index in
each of the 16 mapped graphs, then the measurement method only holds the
fixture. With Native Memory Tracking enabled, `jcmd GC.run` is followed by
`GC.heap_info`, `VM.native_memory summary`, and `ps` while the fork remains idle.

| Full-GC footprint | `main` | Attempt 008 | Difference |
|-------------------|-------:|------------:|-----------:|
| live Java heap | `11,918 KiB` | `16,949 KiB` | `+5,031 KiB` |
| RSS | `178,592 KiB` | `233,056 KiB` | `+54,464 KiB` |
| committed Java heap | `69,632 KiB` | `131,072 KiB` | `+61,440 KiB` |

The live-object increase is about `314 KiB` per graph for this fixture. The
larger RSS delta tracks G1's committed heap, not live index objects; both raw
numbers are retained here rather than presenting RSS as heap usage.

Regression tests assert that repeated early limited queries leave the index
count at zero, a later admitted access builds exactly one index, and forced
trigram budget exhaustion returns the same matches through dictionary scan.

**Conclusion:** retained. Attempt 008 removes the early-hit regression, improves
cold and amortized performance beyond Attempt 006, and replaces cardinality-only
guards with explicit posting and byte budgets plus result-preserving fallback.

## PR verification summary

**Environment:** Apple M3 Max, 64 GiB RAM, macOS 14.3 arm64, OpenJDK
17.0.18, JMH 1.37, one benchmark thread, one fork. Baseline and branch runs
used the same machine and dependency caches.

### Method-level Cypher regression

```shell
./gradlew :cypher:jmh \
  -Pjmh.filter='io.johnsonlee.graphite.cypher.CypherBenchmark.*' \
  --no-daemon
```

| `CypherBenchmark` | `main` | Branch | Change |
|-------------------|-------:|-------:|-------:|
| `aggregationCountGroupBy` | `35.208 us/op` | `34.609 us/op` | `-1.7%` |
| `countStar` | `2.238 us/op` | `2.268 us/op` | `+1.3%` |
| `functionCalls` | `24.702 us/op` | `24.269 us/op` | `-1.8%` |
| `nodeMatchWithWhere` | `57.774 us/op` | `57.605 us/op` | `-0.3%` |
| `regexFilter` | `23.097 us/op` | `22.926 us/op` | `-0.7%` |
| `returnDistinct` | `100.328 us/op` | `92.856 us/op` | `-7.4%` |
| `simpleNodeMatch` | `19.969 us/op` | `19.645 us/op` | `-1.6%` |
| `singleHopRelationship` | `30.402 us/op` | `29.201 us/op` | `-4.0%` |
| `variableLengthPath` | `26.319 us/op` | `25.500 us/op` | `-3.1%` |
| `withPipeline` | `75.915 us/op` | `76.749 us/op` | `+1.1%` |

The two small increases have overlapping confidence intervals. There is no
measured method-level regression.

### Large mapped graph regression

```shell
./gradlew :webgraph:jmh \
  -Pjmh.filter='EsQueryBenchmark.mapped_.*' \
  --no-daemon
```

This uses the repository's Elasticsearch corpus with approximately 968,000
nodes. Values are `ms/op`.

| `EsQueryBenchmark` | `main` | Branch |
|--------------------|-------:|-------:|
| `mapped_countStar` | `0.002` | `0.002` |
| `mapped_intConstantFilter` | `0.300` | `0.295` |
| `mapped_regexFilter` | `0.076` | `0.078` |
| `mapped_returnDistinct` | `0.095` | `0.093` |
| `mapped_simpleNodeMatch` | `0.067` | `0.068` |
| `mapped_singleHopRelationship` | `0.151` | `0.152` |

`mapped_returnDistinct` was repeated with five warmups and ten measurements
after an initial noisy result; the repeated branch result is `0.093 ms/op`
versus `0.095 ms/op` on `main`. The remaining differences are at most
`0.002 ms/op`; there is no measured large-corpus query regression.

### End-to-end regression

```shell
./gradlew :webgraph:jmh \
  -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' \
  --no-daemon
```

`GraphEndToEndBenchmark` covers Android JAR analysis, graph build, save, mapped
load, and Cypher count query. The single-shot result was `30,182.415 ms/op` on
`main` and `24,435.361 ms/op` on the branch. This benchmark is intentionally
coarse and noisy, but it shows no end-to-end regression. The optimization does
not change graph building or the persisted format.

### Tests and lint

The CI-equivalent gate passes:

```shell
./gradlew check -S --no-daemon
```

This covers every module's tests, baseline-aware detekt task, and Kover
verification. Direct `detektMain` currently fails on both `main` and this branch
with the same pre-existing totals: 17 Cypher findings and 11 WebGraph findings;
that task does not apply the repository baselines used by `check`. No new
finding remains in a changed code path. New complexity and return-count
findings were resolved or narrowly suppressed following existing project
practice.

The first PR workflow run exposed coverage below the repository's separate 98%
per-module threshold even though `check` passed locally before coverage was
printed. Follow-up behavior tests cover unlabeled element-ID seeks, empty direct
string-filter results, every supported mapped raw string field, mapped metadata
access, and the unsupported `DataInput.readLine` contract. Final application
line coverage is `98.0043%` for Cypher and `98.1162%` for WebGraph; the complete
CI-equivalent `check` gate passes after these tests.
