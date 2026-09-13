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

java -jar frontend/jvm/cypher/build/libs/cypher-1.0.0-SNAPSHOT-jmh.jar \
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

java -jar frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
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
java -jar frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
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

### 2026-08-27 - Attempt 009: Production broad-discovery query

**Production evidence:** an agent using Graphite 2.2.2 began feature discovery
with this query shape over the aggregate server:

```cypher
MATCH (n)
WHERE (exists(n.class) AND n.class CONTAINS 'ThankYou')
   OR (exists(n.name) AND n.name CONTAINS 'ThankYou')
   OR (exists(n.caller_class) AND n.caller_class CONTAINS 'ThankYou')
   OR (exists(n.caller_name) AND n.caller_name CONTAINS 'ThankYou')
   OR (exists(n.callee_class) AND n.callee_class CONTAINS 'ThankYou')
   OR (exists(n.callee_name) AND n.callee_name CONTAINS 'ThankYou')
RETURN DISTINCT n.class AS class, n.name AS name,
    n.caller_class AS caller, n.caller_name AS callerMethod,
    n.callee_class AS callee, n.callee_name AS calleeMethod
LIMIT 120
```

The agent also enumerated `/api/graphs` and called each scoped Cypher route,
which is client-side fan-out. The aggregate server already exposes
`/api/cypher` for one cross-graph query. `/api/graphs` itself reads cached graph
descriptors, so its timeout while these searches were running is consistent
with server saturation rather than graph-list computation.

**Root cause:** `RETURN DISTINCT` excluded the filtered-node fast path, while
the guarded six-way `OR` could not compile as a direct string filter. The
generic pipeline therefore materialized every unlabeled node and binding,
interpreted the full expression per node, projected every match, deduplicated
the projected rows, and only then applied `LIMIT 120`.

**Design:** filtered single-node `DISTINCT ... LIMIT` queries now stream rows
and retain at most the requested distinct results. Qualified cross-graph
execution continues scanning after the limit so later duplicate rows still
contribute complete graph provenance.

For a disjunction of direct `STARTS WITH`, `ENDS WITH`, or `CONTAINS`
predicates, with an optional matching `exists(property)` guard, the planner
narrows an unlabeled scan to node types that can expose those properties. It
uses each graph's storage-aware string lookup, unions matching node IDs, and
materializes matching nodes only. Annotation nodes retain generic evaluation
because their dynamic value map can expose the same property names. Any
unsupported property, mismatched guard, aggregation, ordering, or more complex
expression stays on the generic evaluator.

**Benchmark fixture:** 16 persisted mapped graphs, each with 5,000 string
constants and 2,000 call sites (112,000 nodes total). Every tenth call site has
a unique `ThankYou` caller class. The benchmark uses the production query
above and checks all 120 returned rows. The `main` comparison uses an identical
fixture in a separate clone at `e4d1c6a`.

```shell
./gradlew :webgraph:jmh \
  -Pjmh.filter='BroadDiscoveryMappedQueryBenchmark.*' \
  --no-daemon

java -jar frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
  '.*BroadDiscoveryMappedQueryBenchmark.coldBroadDiscoveryAcrossAllMappedGraphs' \
  -wi 2 -i 3 -w 1s -r 1s -f 1 -prof gc
```

| Broad discovery benchmark | `main` | Attempt 009 | Speedup |
|---------------------------|-------:|------------:|--------:|
| repeated query | `59.394 ms/op` | `2.884 ms/op` | `20.59x` |
| cold hit after index reset | `59.394 ms/op` | `11.208 ms/op` | `5.30x` |
| cold miss after index reset | not measured | `8.368 ms/op` | n/a |

The profiler run allocates `137.930 MB/op` on `main` and `25.203 MB/op` on the
cold branch path, an 81.7% reduction. The corresponding profiler times are
`60.139 ms/op` and `11.881 ms/op`.

**Limit:** this is query planning and allocation control, not a hard CPU
budget. Arbitrary Cypher outside the recognized shape can still perform a
large scan. Request deadlines, cooperative cancellation, and a Cypher
concurrency bulkhead remain separate availability work.

**Conclusion:** retained. This attempt covers the reported 2.2.2 query shape
and removes the main deserialization and intermediate-row costs without
changing `DISTINCT`, `LIMIT`, or provenance semantics.

### 2026-08-27 - Attempt 010: Predicate-specific index admission

**Review evidence:** property-level admission created two latency traps. A full
miss made the next finite lookup build the complete index before learning that
its different predicate matched the first node. Evicting an index from the
four-entry LRU left the same property-level admission hot, so the next early
lookup rebuilt the evicted index. Review reproductions measured `25.074 ms/op`
after an admitted miss and `9.810 ms/op` after LRU eviction.

The new storage method on `Graph` also compiled as an abstract JVM interface
method under the project's Kotlin settings. A graph implementation compiled
against 2.2.2 could therefore fail with `AbstractMethodError` when the new
Cypher fast path called it.

**Design:** finite admission is now keyed by node type, property, match mode,
expected string, and lookup limit. A costly scan admits only that exact
predicate and query budget. A different early-hit predicate stays on the lazy
raw mmap scan, and a large-limit scan cannot force a later `LIMIT 1` request to
build the complete index. Admission keeps at most 32 entries and 64 KiB of
estimated retained state. LRU index eviction removes every admission for the
evicted property. Rejected indexes remain on raw scan without repeatedly
attempting construction.

Storage-aware string lookup moved from the `Graph` interface to the optional
`StringPropertyLookup` capability. A `Graph.nodesByStringProperty` extension
performs a safe capability check, so existing implementations retain their old
JVM interface and fall back to `Graph.nodes`. A regression test asserts that
`Graph.class` has no `nodesByStringProperty` method and verifies the fallback.

**Benchmark fixture:** one persisted mapped graph with 50,000 resource nodes
and 50,000 field nodes. Invocation setup performs either a full miss, a
large-limit scan of the same predicate, or an admit/build workload over five
property keys against the four-entry LRU before a first-node `LIMIT 1` query.
Setup time is excluded from the single-shot measurement.

```shell
java -jar frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
  '.*MappedStringAdmissionBenchmark.*' -f 1
```

| Admission control | `main` | Attempt 010 |
|-------------------|-------:|------------:|
| early hit after full miss | `0.555 ms/op` | `0.390 ms/op` |
| early hit after same-predicate large-limit scan | `0.518 ms/op` | `0.463 ms/op` |
| early hit after LRU workload | `0.189 ms/op` | `0.263 ms/op` |

All controls are at `main` latency and eliminate the review reproductions'
index-build spikes. Predicate-and-limit-specific admission intentionally gives
uncached, always-changing misses the raw scan instead of a property-level index:
`5.155 ms/op` versus `15.080 ms/op` on `main`, rather than the unsafe previous
branch result of `0.020 ms/op`.

The other cross-graph controls remain improved: cold late hit is
`4.886 ms/op` versus `14.719 ms/op`, two different cold searches are
`10.176 ms/op` versus `29.292 ms/op`, repeated miss is `0.019 ms/op` versus
`14.399 ms/op`, and search-then-call-chain is `0.073 ms/op` versus
`32.087 ms/op`. The cold early-hit pair remains at parity (`0.038 ms/op`).

**Conclusion:** retained. Admission now follows observed cost for the exact
predicate and query limit, eviction resets its decision state, and the
optimization no longer changes the binary contract of `Graph`.

### 2026-08-27 - Attempt 011: Android-scale broad discovery

**Review finding:** the ES regression corpus is too small for this availability
problem. Its limited queries finish in less than one millisecond, so they are
useful smoke tests but do not reproduce CPU pressure from a broad discovery
query.

**Benchmark design:** `AndroidBroadDiscoveryBenchmark` loads only the mapped
graph built from the Android fixture JAR: 5.9 million nodes and about 6.5
million edges. It runs the production six-property guarded disjunction from
Attempt 009, using the common term `android`, and asserts all 120 requested
distinct rows. The benchmark name describes the query workload rather than its
original caller; the agent session is evidence for the query shape, not part of
its execution semantics.

The cold method clears optional string indexes before every invocation. The
repeated method measures the same query in a long-lived mapped graph. The exact
benchmark source was also compiled at `main` commit `e4d1c6a`; its reflective
clear hook is a no-op there because `main` has no mapped string index.

```shell
./gradlew :webgraph:jmh \
  -Pjmh.filter='AndroidBroadDiscoveryBenchmark.*' \
  --no-daemon
```

| Android broad discovery | `main` | Branch | Speedup |
|-------------------------|-------:|-------:|--------:|
| cold query | `7,426.015 ms/op` | `202.359 ms/op` | `36.70x` |
| repeated query | `7,433.167 ms/op` | `199.474 ms/op` | `37.26x` |

**Conclusion:** retained. The production query shape improves by 36-37x on
the real Android-scale corpus. This replaces the ES query table as the primary
large-graph performance evidence. The remaining `199-202 ms` cost is a real
raw mapped-field scan across 5.9 million nodes, so request-level CPU budgets and
concurrency isolation remain valid follow-up work.

### 2026-08-27 - Attempt 012: Lazy ordered candidate union

**Review finding:** the storage-aware disjunction still consumed every matching
candidate into a `LinkedHashMap` and sorted all unique nodes before yielding the
first result. A `DISTINCT ... LIMIT 1` reproduction with 1,000 matching nodes
therefore consumed all 1,000 candidates. This retained `O(matches)` nodes and
paid `O(matches log matches)` sorting cost before `LIMIT` could stop execution.

**Initial design:** assume each storage lookup yields nodes in ascending node-ID
order, then perform a lazy k-way merge across the property streams, retaining
one head per stream and deduplicating equal node IDs as it advances.

A regression test using hand-sorted streams reduced a `LIMIT 1` reproduction
from 1,000 consumed candidates to two. The Android run also improved, but the
test did not represent persisted mapped ordering.

**Conclusion:** rejected and replaced by Attempt 013. Existing mapped type
indexes preserve source hash iteration order, including graphs already written
by 2.2.2. The k-way merge could therefore emit the same node more than once.

### 2026-08-27 - Attempt 013: Unordered mapped candidate deduplication

**Review findings:** a real `save -> loadMapped` fixture returned type IDs in
hash order rather than numeric order. Interleaved property streams could make
the k-way merge emit IDs such as `[4, 6, 4]`; with `RETURN DISTINCT n.id,
rand()`, the duplicate node remains a distinct projected row and displaces a
different match. The large-limit admission JMH also used `path-` in setup and
`path-0` in measurement, so it changed both predicate and limit and could not
prove limit-specific admission.

**Design:** candidate streams are now consumed lazily in storage order and
deduplicated with a primitive node-ID set. No ordering contract is imposed on
existing persisted graphs. The path retains only IDs actually consumed before
the downstream distinct limit is met; it never retains matched `Node` objects
or sorts the complete match set. `LIMIT 1` now consumes one candidate.

A mapped integration test persists an intentionally hash-ordered call-site
fixture, verifies that ID 90 precedes ID 4 in the stored type index, and runs
the `DISTINCT n.id, rand()` reproduction. The result is exactly IDs `[4, 90]`
with no duplicate. A separate unit test supplies explicitly unordered streams
and verifies lazy cross-stream ID deduplication.

The corrected admission benchmark uses `CONTAINS 'path-0'` for both setup and
measurement, changing only `LIMIT 50000` to `LIMIT 1`; it asserts the index is
absent after both stages. It measures `0.518 +/- 0.392 ms/op` on `main` and
`0.463 +/- 0.356 ms/op` on the branch. The latest broad-discovery results are
`2.884 ms/op` repeated on the 16-graph fixture and `202.359/199.474 ms/op`
cold/repeated on Android.

**Conclusion:** rejected and replaced by Attempt 014. The primitive set is lazy
for an unqualified query that stops at its limit, but qualified cross-graph
execution drains every source to collect complete provenance. In that path the
set grows with every unique match and can again consume tens of megabytes on
the Android corpus.

### 2026-08-27 - Attempt 014: Filter-owned candidate streams

**Review finding:** cross-graph `DISTINCT ... LIMIT 1` cannot stop after the
first visible row because later graphs may contribute to that row's provenance.
Attempt 013 therefore retained every unique matching node ID while draining the
remaining candidate streams. At 5.9 million IDs, the primitive hash table alone
would require roughly a 32 MiB backing array and would keep growing on larger
corpora.

**Design:** each candidate stream is owned by its corresponding direct string
filter. The first filter emits all its matches. A later stream emits a node only
when none of the earlier filters matches that node. This works with arbitrary
storage order and retains no node IDs: deduplication memory is bounded by the
number of query filters, which is six for the broad-discovery query. The CPU
tradeoff is at most one property check per earlier filter for each candidate in
a later stream.

The unordered-stream regression now uses contract-correct lookup results: one
node matches both filters, while the other two match only the second filter. It
still produces `[1, 2, 0]` exactly once each. A qualified regression builds two
graphs with 5,000 nodes apiece, where every node matches both filters. It proves
that execution consumes all 20,000 indexed candidates, returns one distinct
row, and merges provenance from both graphs without match-sized deduplication
state. The single-graph `LIMIT 1` guard still consumes only one candidate.

The final Android JAR benchmark measures `207.392 ms/op` cold and `204.300
ms/op` repeated, versus `7,426.015` and `7,433.167 ms/op` on `main`: `35.81x`
and `36.38x` speedups. The 16-graph cross-graph benchmark measures `2.861
ms/op` repeated, `11.661 ms/op` cold hit, and `7.949 ms/op` cold miss.

**Conclusion:** retained. Candidate deduplication is correct for unordered
persisted graphs, remains lazy for single-graph limits, uses memory independent
of match count for qualified execution, and preserves the Android-scale
speedup.

### 2026-08-28 - Attempt 015: Android schema-discovery baseline

**Observed query shape:** an agent first samples labels and property keys with
`MATCH (n) RETURN labels(n), keys(n) LIMIT 20`, then requests a label histogram
with `MATCH (n) UNWIND labels(n) AS label RETURN label, count(*) AS c ORDER BY c
DESC LIMIT 50`. The first query inspects only 20 nodes because the generic
pipeline can push its limit into the match. The second query cannot push its
limit through `UNWIND`, aggregation, and ordering, so it materializes and
expands every matched node before retaining the top 50 result rows.

**Benchmark:** `AndroidSchemaDiscoveryBenchmark` runs both unmodified queries
against the persisted 5,938,826-node Android graph. The benchmark name describes
the workload rather than the client that generated it, so it remains usable for
CLI, HTTP, and agent callers.

```shell
./gradlew :webgraph:jmhJar --no-daemon
java -jar frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
  'AndroidSchemaDiscoveryBenchmark.*' \
  -wi 1 -i 3 -w 1s -r 1s -f 1 -prof gc -foe true
```

| Query | Time | Allocation per operation | GC time |
|-------|-----:|-------------------------:|--------:|
| `labels/keys LIMIT 20` | `0.018 ms/op` | `56,480 B/op` | `17 ms` total |
| `UNWIND labels + count` | `8,733.299 ms/op` | `11,211,740,267 B/op` | `12,313 ms` total |

**Conclusion:** retained as the baseline. Calling `labels()` or `keys()` is not
itself the pressure source when an early limit applies. The full-node scan,
row expansion, aggregation, and sort in the histogram query allocate roughly
11.2 GB per execution even at 5.9 million nodes. At the reported 80-million-node
deployment scale, this execution model is not viable; the histogram must use
existing type metadata instead of visiting nodes.

### 2026-08-28 - Attempt 016: Type-index label histogram

**Design:** recognize the exact schema-discovery shape from Attempt 015 and
derive its counts from `Graph.nodeCount(concreteType)`. Graphite labels are a
fixed projection of concrete node types, including aggregate labels such as
`Constant`, `Resource`, and `Annotation`. The executor sums each type count into
those labels, sorts the small metadata result, and retains cross-graph
provenance. It does not load or materialize a node.

The optimization is deliberately narrow. It requires an unlabeled single-node
`MATCH`, `UNWIND labels()` of that node, a label plus `count(*)` projection,
ordering by the count alias, and a literal limit. Unsupported shapes use the
generic pipeline. A graph that cannot provide an indexed `nodeCount` also falls
back to the generic implementation.

Tests prove that the optimized query never calls `Graph.nodes`, returns concrete
and aggregate label counts, supports aliases and limits, sums counts across
graphs, and preserves every contributing graph ID. A separate fallback test
uses a graph with no count metadata and verifies the original scan result.

The same Android benchmark and JVM settings from Attempt 015 produce:

| Query | Baseline | Attempt 016 | Change |
|-------|---------:|------------:|-------:|
| `labels/keys LIMIT 20` | `0.018 ms/op`, `56,480 B/op` | `0.014 ms/op`, `52,944 B/op` | no regression |
| `UNWIND labels + count` | `8,733.299 ms/op`, `11,211,740,267 B/op` | `0.010 ms/op`, `35,768 B/op` | `873,330x` faster, `313,456x` less allocation |

`./gradlew :cypher:check --no-daemon` passes, including tests, detekt, and Kover
verification. Explicit application line coverage is `98.1245%`.

**Conclusion:** retained. The production query is now bounded by the number of
Graphite node types and selected graphs rather than the number of nodes. The
5.9-million-node Android result exceeds the 100x target by more than four orders
of magnitude, and the already-limited labels/keys sample does not regress.

### 2026-08-28 - Attempt 017: Cypher admission and work budgets

**Remaining risk:** the label histogram fast path cannot cover every query an
agent can generate. For example, replacing `labels(n)` with `keys(n)` requires
per-node property inspection. A result `LIMIT` still applies after `UNWIND`,
aggregation, and ordering, so it does not bound the preceding scan or retained
match rows.

**Design:** HTTP Cypher endpoints now share a non-queuing semaphore with a
default of two active queries. Each admitted request also receives a 250,000
work-unit budget. The intended accounting covers generic node scans, direct
string-filter candidates, relationship scans, path reconstruction, UNION
segments, and cross-graph sources. Metadata fast paths consume no units. This
attempt divides the request budget evenly across fanout graphs.

The limits are configurable with `--max-concurrent-cypher` and
`--cypher-work-budget`. Concurrency and work-budget rejections return HTTP 429
with machine-readable codes `cypher_concurrency_limit` and
`cypher_work_budget_exceeded`; only concurrency rejection includes
`Retry-After`. Syntax and semantic query errors remain HTTP 400. The OpenAPI
document and README describe both responses and options.

An initial implementation consulted a `ThreadLocal` even when no budget was
configured. Its first JMH run moved `singleHopRelationship` from `29.230` to
`30.278 us/op`, with separated confidence intervals. The retained design makes
work tracking a construction-time pipeline capability: the default library
executor never sets or reads the tracker, while HTTP creates a budget-enabled
pipeline.

**Android budget benchmark:** the new SingleShot benchmark runs the unoptimized
`MATCH (n) UNWIND keys(n) ... count(*) ... LIMIT 50` shape on the persisted
5,938,826-node Android graph. The original run reported `109.710 ms/op` and
`259,665,267 B/op`, but did not retain enough fixture evidence to support its
node-count and GC claims. An independent harness run measured `108.846 ms/op`,
`298,504,171 B/op`, and one or two collections per measured invocation. Attempt
018 reruns the final implementation against the fixture-validated corpus and
replaces these resource conclusions.

```shell
java -jar frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
  'AndroidSchemaDiscoveryBenchmark.boundedPropertyKeyHistogram' \
  -i 3 -r 1s -f 1 -prof gc -foe true
```

**Method-level regression:** latest `main` (`44b5756`) and the branch were run
from separate local checkouts on the same machine with the standard JMH task.
Values are `us/op`; every confidence interval overlaps and every score change
is below 4%.

| `CypherBenchmark` | `main` | Attempt 017 | Change |
|-------------------|-------:|------------:|-------:|
| `aggregationCountGroupBy` | `34.278` | `34.885` | `+1.8%` |
| `countStar` | `2.340` | `2.425` | `+3.6%` |
| `functionCalls` | `24.710` | `24.736` | `+0.1%` |
| `nodeMatchWithWhere` | `57.667` | `57.034` | `-1.1%` |
| `regexFilter` | `23.387` | `23.885` | `+2.1%` |
| `returnDistinct` | `101.791` | `103.822` | `+2.0%` |
| `simpleNodeMatch` | `20.256` | `19.594` | `-3.3%` |
| `singleHopRelationship` | `29.230` | `29.582` | `+1.2%` |
| `variableLengthPath` | `26.138` | `25.583` | `-2.1%` |
| `withPipeline` | `76.788` | `76.558` | `-0.3%` |

Tests verify node, relationship, UNION, cross-graph, reset, and metadata budget
semantics; HTTP tests verify 429 work rejection, immediate concurrent rejection,
permit release, and recovery. `:cypher:check` and `:explore:check` pass. Explicit
application line coverage is `98.108%` for Cypher and `98.0857%` for Explore.

**Conclusion:** superseded by Attempt 018. Review found uncharged variable-length
path traversal and storage-internal string scans, fanout could exceed or reject
before the request-level budget, and the changed cross-graph constructor removed
the released JVM one-argument descriptor. The default non-budgeted benchmark
evidence remains valid, but it does not measure the HTTP budget-enabled path.

### 2026-08-28 - Attempt 018: Complete request-level work accounting

**Review findings reproduced:** a two-unit budget completed a four-hop variable
path because `PathFinder` did not receive the tracker. Mapped string misses and
index construction inspected hundreds of values while charging only returned
matches. Fanout created one tracker per graph, so a one-unit request could read
two one-node graphs, while fixed shares also rejected a first graph needing six
of a ten-unit request. `javap` confirmed that the released
`CrossGraphCypherExecutor(List)` constructor was absent.

A valid integer `LIMIT` above `Int.MAX_VALUE` was also narrowed with `toInt()`.
That wrapped `2147483648` to a negative value, causing the label-histogram fast
path to return an empty result and preventing the HTTP row cap from replacing
the oversized literal.

The first path-budget fix still copied each state's complete node and edge lists
and collected all paths before the caller could apply `LIMIT`. A long explicit
`*..N` chain therefore retained O(N^2) path references even though traversal
candidates were counted.

**Retained design:** `CypherExecutionContext` owns the mutable tracker for one
HTTP request. Single-graph, cross-graph, UNION, and fanout execution all use that
same context; the existing `CypherExecutor(graph, budget)` API still creates a
fresh tracker per `execute` call. Fanout no longer divides the budget and stops
opening graphs once the global row limit is full.

`PathFinder` now stores one parent link per BFS state and exposes an internal
lazy sequence. The pattern pipeline stays eager for ordinary unbounded queries
but streams relationship patterns when a safe early `LIMIT` is available, so
the limit stops sequence consumption without discarding a later branch that can
complete a longer pattern. Source-node reads, every edge candidate before type
filtering, and every target-node read are charged; when a relationship variable
requires a concrete path, its full node-and-edge materialization cost is charged
before allocating the path containers. Mapped string
lookup implements a new optional `WorkAwareStringPropertyLookup` without
changing the existing `StringPropertyLookup` ABI. It reports raw node scans,
first index construction,
unique-string/posting inspection, and indexed node-ID scans. Budgeted execution
falls back to a tracked generic node scan for graph implementations that only
support the legacy lookup. A custom tracking sequence charges before reading
the next candidate and avoids the allocation and dispatch cost of `onEach`.

Single-hop traversal uses untyped edge sequences while a tracker is active, so
every edge is charged before relationship-type filtering; unbudgeted execution
retains the graph's typed overload. Fast and generic single-hop paths also charge
the direct target-node load. Direct `elementId` seeks charge their node load in
both single-graph and qualified execution. Nested execution saves and restores
the previous thread-local tracker instead of clearing the outer request state.

LIMIT evaluation now saturates out-of-range integer values at the JVM `Int`
bounds. A large positive library LIMIT therefore preserves all available rows,
while `execute(query, maxRows)` safely replaces it with the server row cap.

The explicit `CrossGraphCypherExecutor(List)` and `QueryPipeline(List)`
constructors are restored, as is
`MappedStringPropertyIndex.matchingNodeIds(StringMatchMode, String)`. JVM
reflection regressions resolve and invoke those exact descriptors. Other
tests prove variable-path rejection, mapped miss/build/existing-index charging,
one-match success without double charging, fanout total-budget enforcement, and
no fixed-share false rejection. They also cover a label histogram and a
server-capped query with `LIMIT 2147483648`, path-materialization rejection, a
1,000-hop graph queried with `*..100000 LIMIT 1` that stops after the first
outgoing expansion, a multi-stage pattern whose first branch is a dead end,
fast/generic target-node reads, 100 rejected relationship candidates before a
typed match, reentrant execution, and unqualified/qualified `elementId` UNION
seeks.
The full
`./gradlew check -S --no-daemon` gate, including all three large-corpus
end-to-end tests, passes. Application line
coverage is `98.2569%` for Core, `98.1846%` for Cypher, `98.0213%` for WebGraph,
and `98.1046%` for Explore.

**Budget-enabled executor cost used by HTTP:** `BudgetedCypherBenchmark`
isolates successful unbudgeted and budgeted executor calls against the same 500
two-hop chains. It does not include Jetty or network time. This is separate from
`CypherBenchmark`, which remains the base/PR regression gate for the default
library API.

```shell
./gradlew :cypher:jmhJar --no-daemon
for pair in NodeScan Relationship VariableLengthPath; do
  java -jar frontend/jvm/cypher/build/libs/cypher-1.0.0-SNAPSHOT-jmh.jar \
    "BudgetedCypherBenchmark.(budgeted${pair}|unbudgeted${pair})$" \
    -wi 3 -i 5 -w 1s -r 1s -f 5 -foe true
done
```

Apple M3 Max, 64 GiB RAM, macOS arm64, OpenJDK 17.0.18, JMH 1.37, one
benchmark thread, five forks, and 25 measurement iterations per benchmark.
Values and 99.9% confidence errors are `us/op`.

| Successful query | Unbudgeted | Budgeted | Budget-check cost |
|------------------|-----------:|---------:|------------------:|
| 500-node scan | `48.613 +/- 0.240` | `49.312 +/- 0.666` | `+1.4%` |
| 500 single-hop relationships | `148.846 +/- 0.731` | `157.375 +/- 4.496` | `+5.7%` |
| 500 materialized two-hop paths | `231.380 +/- 2.436` | `233.829 +/- 4.377` | `+1.1%` |

The budget check has a measurable cost; this is not described as a free change.
The relationship result is the highest at 5.7%, and its 99.9% confidence
interval does not overlap the unbudgeted result. Its unbudgeted path now relies
on the graph's typed traversal without applying a duplicate relationship-type
filter. The budgeted path still reads the untyped edge sequence so it can charge
every rejected candidate before filtering; the benchmark includes source-node,
edge-candidate, and target-node accounting. The variable-path query binds and
returns its relationship variable, so that row also covers concrete path
materialization and its budget charge.

**Corrected Android rejection evidence:** the benchmark setup now asserts the
harness identity for Maven fixture `org.robolectric:android-all:14-robolectric-10818077`,
which persists exactly 5,938,826 nodes. The exact command from Attempt 017 on
the final implementation, with its declared `-Xmx16g` fork, measured
`116.334 ms/op`, `363,221,955 B/op`, and `gc.count ~= 0` over three invocations.
The independent review run measured lower allocation and one or two collections,
so allocation and GC count are explicitly environment-sensitive observations,
not a no-GC guarantee. The invariant is that every invocation rejects at the
250,000-unit boundary before scanning the complete corpus.

**Conclusion:** retained. All known graph traversal and mapped string lookup
paths now consume the same request counter, fanout enforces exactly one global
budget, and the released JVM constructor remains linkable. The safety bound is
complete for the reviewed paths, including path materialization rather than only
candidate reads, while the measured successful-query cost is reported instead
of being hidden behind unbudgeted benchmark results.

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
| `aggregationCountGroupBy` | `34.390 us/op` | `36.163 us/op` | `+5.2%` |
| `countStar` | `2.257 us/op` | `2.447 us/op` | `+8.4%` |
| `functionCalls` | `24.502 us/op` | `24.137 us/op` | `-1.5%` |
| `nodeMatchWithWhere` | `57.823 us/op` | `57.595 us/op` | `-0.4%` |
| `regexFilter` | `24.107 us/op` | `23.643 us/op` | `-1.9%` |
| `returnDistinct` | `102.087 us/op` | `96.102 us/op` | `-5.9%` |
| `simpleNodeMatch` | `19.335 us/op` | `19.243 us/op` | `-0.5%` |
| `singleHopRelationship` | `30.458 us/op` | `29.534 us/op` | `-3.0%` |
| `variableLengthPath` | `26.878 us/op` | `25.733 us/op` | `-4.3%` |
| `withPipeline` | `84.302 us/op` | `76.995 us/op` | `-8.7%` |

The one-fork table's slower rows are `aggregationCountGroupBy` (`+5.2%`) and
`countStar` (`+8.4%`), both below the workflow's configured 15% regression
threshold. These are local measurements, separate from the same-runner CI
artifact. A
focused five-fork base/head confirmation measured aggregation at
`34.605 +/- 0.526` versus `35.523 +/- 0.569 us/op` (`+2.7%`) and count at
`2.344 +/- 0.017` versus `2.363 +/- 0.084 us/op` (`+0.8%`); both confidence
interval pairs overlap. The separate budget-enabled comparison above reports
the production-path cost.

### Android mapped graph regression

```shell
./gradlew :webgraph:jmh \
  -Pjmh.filter='AndroidQueryBenchmark.mapped_.*' \
  --no-daemon
```

This uses the 5.9-million-node Android graph. These limited and metadata-backed
queries are secondary regression guards, not the CPU pressure benchmark.
Values are `ms/op`.

| `AndroidQueryBenchmark` | `main` | Branch |
|-------------------------|-------:|-------:|
| `mapped_countStar` | `0.003` | `0.002` |
| `mapped_intConstantFilter` | `0.172` | `0.182` |
| `mapped_returnDistinct` | `0.171` | `0.209` |
| `mapped_simpleNodeMatch` | `0.070` | `0.087` |
| `mapped_singleHopRelationship` | `0.651` | `0.653` |

The branch and main confidence intervals overlap for every row. The wide
intervals also show why these sub-millisecond limited queries are not used as
the primary pressure result.

### End-to-end regression

```shell
./gradlew :webgraph:jmh \
  -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' \
  --no-daemon
```

`GraphEndToEndBenchmark` covers Android JAR analysis, graph build, save, mapped
load, and Cypher count query. The single-shot result was `30,182.415 ms/op` on
`main` and `24,165.015 ms/op` on the final branch. This benchmark is intentionally
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
finding remains in a changed code path. New behavior tests cover the exact
six-property broad-discovery query, cross-graph provenance merging, dynamic annotation
properties, generic fallback, and streaming `DISTINCT ... LIMIT` execution.

The first PR workflow run exposed coverage below the repository's separate 98%
per-module threshold even though `check` passed locally before coverage was
printed. Follow-up behavior tests cover unlabeled element-ID seeks, empty direct
string-filter results, every supported mapped raw string field, mapped metadata
access, ABI fallback, predicate admission bounds, and admission reset after
cache clearing or LRU eviction. Final application line coverage is `98.2569%`
for Core, `98.1846%` for Cypher, `98.0213%` for WebGraph, and `98.1046%` for
Explore; the complete CI-equivalent `check` gate passes after these tests.

### 2026-09-13 - Attempt 019: Baseline the reported slow query shapes on current main

**Hypothesis:** untyped constant search, dynamic property search, wrapped caller-class
search, and filtered DATAFLOW expansion bypass the existing selective access paths.
Measure identical queries on actual persisted graphs before accepting an optimization.

**Baseline:** `144d98efa2bcb1f183d4f962b833c234d839d2a9` (latest remote main when
requested). The earlier `96522d96` diagnostic runs are superseded. Candidate for this
attempt is this harness-only commit; no production optimization is included.

**Fixture and protocol:** the pinned Android corpus has 5,938,826 nodes and is loaded
from `/tmp/graphite-cpu-vanished-diagnostic.5_7ygubx/fixtures-complete/android`.
The same directory contains the pinned Tika, Hive, and Kotlin compiler graphs for the
`corpus=all` matrix. Local environment: Apple M3 Max, 64 GiB RAM, OpenJDK 17.0.18;
query processes use `-Xmx8g -XX:ActiveProcessorCount=4`. `SlowQueryShapesBenchmark`
fixes the query text, LIMIT, projections, and exact ordered result digest. It includes
nonempty/absent cases, source/target DATAFLOW filtering, cold mappings and warmed
queries. Setup, result validation, and digest serialization are outside the timed
method. COLD means query-index state, not cold OS pages.

```shell
./gradlew :webgraph:jmhJar :webgraph:detekt --max-workers=2
java -Xmx8g -XX:ActiveProcessorCount=4 \
  -Dandroid.graph.path=/tmp/graphite-cpu-vanished-diagnostic.5_7ygubx/fixtures-complete/android \
  -cp frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
  io.johnsonlee.graphite.webgraph.SlowQueryShapesCorrectness android valueHit
```

**Correctness evidence:** baseline build and lint passed. Nine query cases each passed
both cold and warm executions (18 observations). `valueHit` returns one row,
`wrappedCallerHit` 50, `dataflowSourceHit` five, and `dataflowTargetHit` 50. Missing-term
queries return no rows. Full ordered digests, exact commands, and fixture SHA-256
manifest are retained locally in `/tmp/graphite-slow-shapes-evidence/base-144d98ef/`.

The tenth case, `dynamicHit`, exposed a functional defect: `n[k]` accepts only numeric
subscripts on main, so the dynamic search misses a keyword that `n.value` finds.
Do not relax that oracle to accept zero rows. Any performance comparison for the
repaired dynamic query must identify a semantically correct reference, separately
from unmodified main. This is not a successful speedup observation.

**Performance/resource evidence:** the diagnostic runner records per-query wall and
process CPU time, but these first single runs are not acceptance evidence. Formal
paired latency/allocation measurements and existing method/end-to-end regression
checks remain pending. No speedup, memory reduction, or non-regression is claimed.

**Conclusion:** keep the real-data harness and strict result checks. All four requested
shapes, including both DATAFLOW endpoints, remain in scope for the 10x objective.

### 2026-09-13 - Attempt 020: Repair the dynamic-property correctness baseline

**Hypothesis:** the dynamic search's empty result is caused by treating every
subscript as a numeric list/string index. A string subscript must resolve the same
property as a static property expression before an optimized search can be evaluated.

**Base/candidate:** main `144d98efa2bcb1f183d4f962b833c234d839d2a9`; candidate is this
correctness-fix commit. `ExpressionEvaluator` evaluates both operands once, dispatches
string keys through existing property resolution, and retains numeric indexing,
negative indices, out-of-range nulls, and null/invalid-key behavior.

**Correctness:** the focused Cypher run passed all 28 tests, including six new dynamic
property tests for node fields, numeric values converted to strings, qualified graph
identity, Method metadata, maps, and unchanged list/string indexing. Cypher detekt
passed. Command: `./gradlew :cypher:test --tests '*ToStringLookupTest' --tests
'*ValueStringLookupTest' --tests '*SourcePredicatePushdownTest' --tests
'*DynamicPropertyAccessTest' :cypher:detekt :webgraph:jmhJar --max-workers=2`.
The broader focused run contains separately uncommitted optimization experiments;
this commit includes only the subscript fix and its own tests.

**Performance/resources:** no optimization claim. Real Android fixture and environment
are unchanged from Attempt 019. A separate main-plus-this-fix reference build will
measure correct dynamic searches; unmodified main still supplies the baseline for
all other shapes. Latency, CPU, allocation, and full regression evidence remain pending.

**Conclusion:** keep the functional repair. It is a prerequisite for valid dynamic
search comparison, not evidence of progress toward a 10x speedup by itself.

### 2026-09-13 - Attempt 021: Route polymorphic value strings through typed lookup

**Hypothesis:** string constants already have raw persisted lookup support; untyped
`value` predicates should use it, while also retaining string-valued enum, resource,
and annotation attributes. Backends without canonical lookup ordering retain their
original scan for these polymorphic predicates.

**Base/candidate:** main `144d98efa2bcb1f183d4f962b833c234d839d2a9`; candidate is this
experiment commit. The paired measurement snapshot also contains the independent
wrapper and source-expansion attempts recorded below. Its exact patch and frozen JAR
identities are in `/tmp/graphite-slow-shapes-evidence/paired-first/manifest.json`.

**Evidence:** same real Android fixture and JVM protocol as Attempt 019, three alternating
main/candidate pairs, one JMH fork per pair, zero warmup iterations, one measurement,
COLD and WARM mappings. Across all eight non-dynamic cases, 96 ordered digests and
row counts agree. Median valueHit acceleration is 26.0x COLD / 60.0x WARM; valueMiss
27.4x / 76.2x. First diagnostic CPU reductions are 16–17x COLD and 40–43x WARM.
The raw JMH GC profiler observations are whole-trial allocation (including setup/primer),
not isolated query allocations; see `paired-first/summary.md` and the six raw JSON files.

Focused value tests verify resource/enum strings, storage access, encounter order,
numeric equality, numeric toString, and nulls. The second snapshot adds annotation
coverage; the full Cypher test task and detekt passed. Full repository, real-corpus
regression, and final combined-head benchmarks are still pending.

**Conclusion:** keep; value cases exceed 10x on this fixture. This does not prove the
remaining shapes or the complete no-regression requirement.

### 2026-09-13 - Attempt 022: Preserve toString while reusing string-field lookup

**Hypothesis:** CallSite caller/callee class/name values are already strings, so the
wrapper can reuse their direct lookup. Annotation attributes with the same names may
be numbers or lists: retain explicit coercion, bypass string-only lookup/aggregation
for those attributes, and preserve canonical mixed-node order.

**Base/candidate:** main `144d98ef`; candidate is this experiment commit. Measurement
identities, real Android fixture, JVM protocol, and combined-snapshot qualification
are the same as Attempt 021. Three-pair medians: wrappedCallerHit 26.6x COLD / 86.5x
WARM; wrappedCallerMiss 7.18x / 20.7x. Exact ordered result parity passed in all pairs.
Diagnostic CPU improvement for the missing case is only about 4.5–4.9x, so wall-time
speedup must not be misrepresented as CPU speedup.

**Correctness/resources:** seven wrapper tests and five annotation regressions cover
native/numeric/list values, nulls, parameters, distinct/order/skip, overlapping graph IDs,
canonical order, unknown annotation counts, and unsupported AST fallback. Full Cypher
tests and detekt passed in the second snapshot. Whole-trial GC evidence is retained
under `paired-first/`; isolated query allocation and final broader regressions remain
unproven. The annotation corrections do not change the annotation-free Android fixture.

**Conclusion:** keep the semantic repair and fast path. The COLD missing-term case has
not reached 10x and remains an explicit shortfall.

### 2026-09-13 - Attempt 023: Filter and select sources before single-hop expansion

**Hypothesis:** pure source-only conjuncts can reject a seed before adjacency access.
When storage exposes canonical candidate ordering, reuse typed lookup without applying
the result LIMIT to seeds: early candidates can have no surviving relationships.
Retain the full original WHERE and normal edge/provenance binding.

**Base/candidate:** main `144d98ef`; candidate is this experiment commit, depending on
the preceding typed-value support. Frozen first measurement snapshot and real-data/JVM
protocol are recorded in Attempt 021. Three-pair medians for sourceHit are 80.1x COLD /
247.6x WARM; sourceMiss 92.4x / 268.2x. Exact five-row hit results, absent results,
ordering, and provenance match main in every observation. Diagnostic CPU reductions
are about 44–47x COLD and 71–128x WARM. Whole-trial allocations are in the frozen report.

**Correctness:** source-access tests prove rejected adjacency is not visited, ordered
lookup does not scan all nodes or truncate dead seeds, and fallback preserves mixed OR,
right-only conditions, inline-property errors, volatility, non-Boolean NOT errors,
work budgets, LIMIT/SKIP/DISTINCT/order, and graph identity. Full Cypher tests and lint
passed in the second snapshot. Other repository and final-head regression gates remain
pending.

**Conclusion:** keep. Source-filtered cases exceed 10x on this real fixture; this does
not improve target-only predicates, which remain a separate experiment.

### 2026-09-13 - Attempt 024: Reject eager target-existence preflight

**Hypothesis:** a necessary target string predicate can prove a graph has no matching
relationship, eliminating all source and edge work without initializing a reverse graph.
Probe for one target, then retain ordinary traversal if any candidate exists.

**Base/candidate:** main `144d98ef` versus the frozen second snapshot at
`/tmp/graphite-slow-shapes-evidence/second/`; candidate JAR SHA-256 is
`4ba17379a02be9e1681119c1369dea2f5bc491cfe4fd9913c47d184cfd81a33c`.
Same real Android fixture/JVM settings as Attempt 019. Exact corresponding results
match, but the historical target-hit digest differs between the first and second
batches. Do not pool them; sidecar state was not fully frozen in the first protocol.
The core six graph files rehash identically; a persisted CallSite index was generated.

**Evidence:** the diagnostic targetMiss improves about 19.17x COLD / 61.09x WARM.
TargetHit regresses from 220 to 280 ms COLD and 108 to 153 ms WARM (27% / 41%).
Process CPU rises 36% / 86% on that hit. Full Cypher tests and detekt pass; result
correctness alone is insufficient. This single paired diagnostic is sufficient to
reject the eager hypothesis, not to accept latency or resource guarantees.

**Conclusion:** reject the eager production change; this commit retains only the record.
A baseline source-access probe shows the hit fills LIMIT 50 inside the first source,
which has 80,960 outgoing edges. A later attempt will defer preflight until the normal
stream actually requests a second source. The probe source and output are retained in
`/tmp/graphite-target-prefix-probe/`. Candidate regressions must be removed, not hidden
by aggregate speedups elsewhere.

### 2026-09-13 - Attempt 025: Compile exact dynamic-property ANY predicates

**Hypothesis:** eliminate per-property binding copies, function argument lists, repeated
AST evaluation, and per-node static property maps for the exact ANY/keys/toString/
CONTAINS shape. Retain eager property access, three-valued results, cancellation,
annotation key/accessor differences, parameter shadowing, and unsupported-shape fallback.
The per-evaluator plan cache is bounded to 256 entries; static key caches are bounded by
the sealed node schema, and dynamic annotations do not use the static cache.

**Base/candidate:** the semantic reference is main `144d98ef` plus only Attempt 020's
string-subscript repair; its exact patch identity is in
`/tmp/graphite-slow-shapes-evidence/dynamic-reference-144d98ef/`. Unmodified main cannot
correctly execute dynamicHit. Candidate is this experiment commit; the measured second
snapshot is frozen under `evidence/second/` as recorded in Attempt 024.

**Evidence:** real Android hit and miss queries retain exact results and improve about
2.0–2.16x, including a one-row hit. Hit is 10.949 to 5.453 seconds COLD, 11.149 to
5.160 seconds WARM. Process CPU observations are in the same report; query allocation
and broad non-regression are not yet established. Full Cypher tests and detekt pass,
including compiled-versus-general equivalence, all node kinds, dynamic annotations,
method/graph metadata keys, literal/parameter terms, cache eviction, and eager errors.

**Conclusion:** keep as an intermediate implementation. It is explicitly short of 10x;
loading every node remains a likely bottleneck and requires a separate storage experiment.


### 2026-09-13 - Attempt 026: Isolate persisted fixture state between query runs

**Hypothesis:** measurements that open the shared fixture directly can inherit a
call-site index written by an earlier graph close. Exact results matched within each
paired batch, but historical batches produced different ordered target-prefix digests
with the same main JAR. The cause of that ordering difference is not established;
those batches must not be pooled.

**Base/candidate:** main remains `144d98efa2bcb1f183d4f962b833c234d839d2a9`.
Install this identical harness in main, the dynamic-property semantic reference, and
candidate builds. Each workload copies the real Android/Tika/Hive/Kotlin persisted
fixture to a private directory, excludes `graph.callsite-string-index`, and opens only
that copy. COLD starts with no persisted call-site index; WARM primes the same private
mapping. This is protocol `private-copy-no-callsite-index-v2`.

**Evidence:** all 64 immutable fixture files are fingerprinted in
`/tmp/graphite-slow-shapes-evidence/fixture-protocol-v2/fixtures.json`; shared sizes and
mtimes, including the excluded sidecar, are recorded separately. Main and semantic
reference JMH builds pass with the identical revised harness. A failed-constructor
check verifies temporary-directory cleanup. The verifier also checks source hashes,
shared-file state, and removal of every logged private copy after a run. Query text,
ordered result digest, and timed execute boundaries are unchanged. No speedup is
claimed by this protocol change. Query CPU is measured around execute; first-trial
JMH GC statistics include setup, priming, and cleanup and are not query-only allocation.

**Conclusion:** keep the isolated protocol and repeat paired measurements before any
final performance claim. Earlier records remain historical diagnostics, not pooled
samples for the final comparison.


### 2026-09-13 - Attempt 027: Preserve heterogeneous aggregate and DISTINCT semantics

**Hypothesis:** new value and wrapped-field candidates can expose heterogeneous
Annotation values to pre-existing string-only aggregate/projection shortcuts. Keep
candidate selection, but decline those shortcuts when they cannot preserve Cypher
numeric equality, null counts, or arbitrary annotation values.

**Base/candidate:** current main remains `144d98ef`; this is a correctness follow-up
to Attempts 021–022, applied to the isolated V2 candidate before fresh timing.
Property-count storage aggregation admits `value` only for StringConstant; Annotation
property aggregation admits only declared nonnull string class/name. Possible
Annotation DISTINCT projections use the normalized streaming path and retain merged
graph provenance. A conjunction chooses a supported candidate property and leaves an
unsupported custom property to residual evaluation, or falls back if neither is supported.

**Evidence:** full Core and Cypher tests, focused mapped candidate correctness tests,
three module detekt gates, and the JMH build pass in
`/tmp/graphite-slow-shapes-v2-tests.log`. New regressions cover missing/numeric values,
count and count-distinct, both conjunction orders, Int/Long and nested-list equality,
SKIP/LIMIT, and cross-graph provenance. This commit claims semantic preservation;
separate isolated real-data timing is pending and no synthetic performance evidence
is used.

**Conclusion:** keep. A string candidate cannot justify string-only aggregation or
raw JVM equality over a heterogeneous projected property.


### 2026-09-13 - Attempt 028: Prefilter dynamic properties using raw string references

**Hypothesis:** avoid decoding every node for ANY/keys/toString/CONTAINS. Select a
necessary ASCII identifier fragment and inspect raw string references in supported
persisted node records. Preserve source order and fully evaluate the original predicate
on every survivor. Numeric/generated-token-only needles, external graph metadata hits,
unknown schemas, enums, resource values, and dynamic annotations retain safe fallbacks.
The matcher is query-local and bounded; no new persisted index is introduced.

**Base/candidate:** semantic reference is main `144d98ef` plus only the string-subscript
repair. Frozen V2 candidate JAR SHA-256 is
`760e4cfa924a10f8a22b19bdb268650544676212f3501a290c395280e83f2c18`.
Exact source files, manifest, protocol, commands, and environment are in
`/tmp/graphite-slow-shapes-evidence/third-isolated/`. This snapshot also includes the
independent deferred target probe, which is not entered by these node queries.

**Evidence:** one isolated real Android paired diagnostic (5,938,826 nodes) preserves
all dynamic hit/miss ordered rows and digests. Hit improves 11,109 to 1,276 ms COLD
(8.70x), 10,846 to 1,129 ms WARM (9.61x). Miss improves 11,031 to 1,060 ms COLD
(10.41x), 10,953 to 928 ms WARM (11.80x). Query-window aggregate process CPU improves
6.04x/8.18x for hit and 10.11x/11.58x for miss. No query-allocation claim is available.
All shared fixture hashes/mtimes remain unchanged and all private copies are removed.
Core 440 tests, Cypher 1,294 tests, six mapped-candidate tests, and three module lint
gates pass. Synthetic fixtures verify candidate supersets, residual semantics, binary
field layouts, encounter order, fallback, work accounting, and cancellation only.

A separate JFR diagnostic attributes 36 of 64 execution samples to raw prefiltering,
18 to surviving-node materialization, and four to ANY evaluation. String decompression
is the largest sampled raw leaf; these sparse samples are diagnostic, not percentages
of wall latency or precise candidate counts.

**Conclusion:** keep as an intermediate optimization. Dynamic hits remain below 10x;
further work should reduce repeated string decoding and false-positive materialization.
Final repeated paired measurements and broad regression gates remain outstanding.


### 2026-09-13 - Attempt 029: Defer target absence detection until a second source is needed

**Hypothesis:** the eager target probe rejected in Attempt 024 delays a high-degree
first source that can satisfy LIMIT immediately. Stream that source normally and only
probe target existence when another source is requested. A provably absent necessary
string target permits skipping the remaining relationship expansion. Retain full WHERE,
source order, lazy later graphs, and fallback for unsafe expressions/patterns/capabilities.

**Base/candidate:** main `144d98ef` versus the frozen isolated V2 snapshot documented
in Attempt 028. Real Android fixture and commands are in
`/tmp/graphite-slow-shapes-evidence/third-isolated/`.

**Evidence:** target miss improves 9,585 to 713 ms COLD (13.44x) and 9,242 to 292 ms
WARM (31.60x), with exact ordered results and CPU speedups of 6.85x/8.08x. Dense
target hit is 227.68 to 228.58 ms COLD and 118.03 to 125.16 ms WARM: the previous
27–41% regression is removed, but a single pair's 6% warm difference needs repeated
validation. No allocation conclusion is available. Full Cypher tests and lint pass,
including first-source LIMIT, graph laziness, complete positive continuation, unsupported
and unsafe fallbacks, and work-budget/cancellation tests.

The extra probe is real charged work. On a tiny graph with two unconnected nodes it
can consume four units where ordinary traversal consumes two; an exactly two-unit
budget therefore fails with the probe. Tests explicitly preserve this accounting and
propagate the original budget/cancellation exceptions. This optimization does not claim
identical work counts or identical success boundaries at every artificial budget limit.

**Conclusion:** retain provisionally for repeated broad real-data regression validation.
Dense-hit latency and extra preflight work remain explicit checks; no timing or budget
exception is hidden or reclassified as a successful query.


### 2026-09-13 - Attempt 030: Keep wrapped string discovery on bounded raw scans

**Hypothesis:** the newly recognized toString caller/callee predicates inherit a cold
parallel lookup that captures a complete index. Request the existing bounded raw scan
only when every direct string predicate is coerced. Leave plain and mixed predicates'
storage policy unchanged, and preserve the Annotation conversion fallback.

**Base/candidate:** main `144d98ef`, identical V2 harness, real Android persisted graph.
Frozen build-clone sources, full candidate JAR SHA, commands and environment are in
`/tmp/graphite-slow-shapes-evidence/raw-wrapper/`. This experiment predates the separate
two-fragment dynamic-property change; root and build clone were deliberately frozen
independently while that other change was developed.

**Evidence:** all eight observations preserve ordered rows/digests. Wrapped hit is
186.3 ms COLD (19.53x main) and 142.4 ms WARM (23.98x main). Wrapped miss is 190.5 ms
COLD (18.98x) and 79.5 ms WARM (42.27x). Query-window process CPU improves roughly
12–22x; no query-allocation measurement is claimed. Source files remain immutable and
all private snapshots are removed. Full Cypher tests and lint pass, including explicit
raw-consumer selection, ordinary/mixed policy parity, Annotation values/order, and
charged work.

**Tradeoff:** warm hit is slower than the earlier experimental retained-index path
(roughly 40 ms versus 142 ms), because this query no longer builds that index first.
Both remain much faster than the actual main baseline, which does not recognize the
wrapped predicate. This experiment favors bounded discovery cost and removes the
cold-miss shortfall without changing the existing plain-string query policy.

**Conclusion:** keep for repeated comparisons against main and broad regression gates.
Do not claim that every intermediate experimental score improved; the indexed warm-hit
tradeoff is explicit.


### 2026-09-13 - Attempt 031: Intersect two necessary dynamic-property fragments

**Hypothesis:** the single longest fragment admits nodes containing permission-related
method metadata even when their property text cannot contain the complete needle.
Intersect up to two distinct longest eligible fragments, retaining stable ties and
bounded selection state. Fragments may occur in different stored signature components;
the complete ANY predicate remains authoritative. Legacy capability implementations
can safely use only the first fragment and return a wider candidate superset.

**Base/candidate:** main `144d98ef` plus the subscript correctness repair, identical V2
harness and real Android fixture. Exact candidate JAR/source identities and commands
are in `/tmp/graphite-slow-shapes-evidence/dual-fragment/`.

**Evidence:** one isolated pair preserves all eight dynamic observations. Hit improves
10,895 to 1,044 ms COLD (10.43x) and 10,663 to 944 ms WARM (11.30x); miss improves
10.63x/11.28x. Query-window aggregate CPU improves 10.05–11.10x. All source hashes
and mtimes remain unchanged and all four private snapshots are removed. No allocation
claim is available. Core/Cypher tests, nine focused mapped tests, all three lint gates,
and JMH build pass in `/tmp/graphite-slow-shapes-dual-fragment-tests.log`. Tests verify
split signature components, stronger candidate filtering, residual false positives,
fallback kinds, order, legacy capability behavior, and the two-fragment bound.

**Conclusion:** keep. The cold 4–6% margin above 10x is narrow, so repeated formal
measurements are still required. String decompression remains an evidence-backed target
for a separate bounded-cache experiment; no change to query truth or result order is
needed to investigate it.


### 2026-09-13 - Attempt 032: Bound dense matcher state to avoid repeated decompression

**Hypothesis:** JFR identified front-coded string decompression as the largest raw-scan
cost. For dynamic-property dictionaries with at most 1,048,576 strings, use the existing
matcher byte-state mode so each string is decoded once per fragment. Larger dictionaries
retain the existing 64K collision cache. This changes no predicate, scan order, work
accounting, persisted file, or index lifecycle.

**Base/candidate:** main `144d98ef` plus the string-subscript repair, identical V2
harness and real Android fixture. `/tmp/graphite-slow-shapes-evidence/dense-cache/`
freezes the build-clone JAR/source identities and the exact diff versus Attempt 031;
only `MappedPropertyTextCandidates.kt` differs in this experiment.

**Evidence:** all eight dynamic observations match ordered rows/digests. Hit is 589 ms
COLD (18.49x semantic reference) and 501 ms WARM (21.78x); miss is 637 ms COLD
(17.15x) and 536 ms WARM (20.29x). Query-window CPU improves approximately 15–20x.
Attempt 031 hit was about 1,044/944 ms, so the separately measured cache change removes
much of the remaining decompression cost. All shared source hashes/mtimes remain
unchanged and all private snapshots are removed. Nine mapped correctness tests, WebGraph
lint, and JMH build pass in `/tmp/graphite-slow-shapes-dense-matcher-tests.log`.

The state-array payload is bounded to at most 1 MiB per fragment, 2 MiB for both,
plus array/object headers and the existing reusable decode buffers. Android's two
arrays total 1,065,574 bytes versus 655,360 bytes of previous key/state payload. This
is an explicit small transient-space tradeoff, not a measured heap/peak-memory claim;
query allocation and broader lifecycle evidence will be collected separately.

**Conclusion:** keep for final repeated paired comparisons and broad regression gates.
The dynamic-query speedup now has substantially more margin than the prior 10.4x cold
observation, while retained index policy and unrelated query paths remain unchanged.


### 2026-09-13 - Attempt 033: Measure allocation around the query execution window

**Hypothesis:** first-trial JMH GC profiling includes private fixture setup, priming,
and cleanup. Add a separate oracle counter around execute so query-window allocation
can be compared without mislabeling lifecycle allocation as query-only cost.

**Base/candidate:** the same revised harness is installed in main `144d98ef`, the
subscript-only semantic reference, and candidate. A fresh `git fetch origin main`
confirms the latest remote baseline remains full SHA
`144d98efa2bcb1f183d4f962b833c234d839d2a9`. Fixture isolation remains V2. This new
instrumented series will not be pooled with earlier harness measurements.

**Method:** Java 17's supported ThreadMXBean total-thread allocation counter is enabled
outside timing. Its difference brackets execute, while the existing wall and process-CPU
boundaries stay unchanged. The counter includes all Java/background threads active in
the query window; it measures allocated bytes, not retained heap or peak memory.
Unsupported, failed, or nonmonotonic counters emit -1 rather than fabricated zero.
JMH's primary execute method and query text are unchanged.

**Evidence:** local Java 17 API inspection confirms the counter interface. Identical
harness SHA-256 is `39008c47663e97278b8aafc21633acb576c504f265479d589e9b28ebc06bcd0a`;
sequential build logs and final frozen identities are recorded under
`/tmp/graphite-slow-shapes-evidence/final-build/`. This instrumentation change claims
no speedup; final paired latency, correctness, CPU, and allocation evidence follows
only after full checks.

**Conclusion:** keep explicit resource scope and unavailable-counter handling. Final
reports must continue distinguishing query-window allocated bytes, bounded cache payload,
and lifecycle/peak-memory observations.


### 2026-09-13 - Attempt 034: Preserve existing concrete-label value index admission

**Hypothesis:** adding value to typed candidate discovery unintentionally redirects an
already labeled StringConstant query from its established single-property lookup to
the disjunction capability. Preserve the original route for concrete value labels;
only polymorphic Node/Constant discovery needs the new merge of typed streams.

**Base/candidate:** latest main remains `144d98ef`. This is a compatibility correction
to Attempt 021; final candidate measurements must use the rebuilt corrected head.
The eight main-baseline target benchmark query shapes are untyped, so their intended
candidate selection is unchanged, but earlier candidate JARs are not the final artifact.

**Evidence:** full check exposed the existing
`MappedCypherBudgetTest.mapped existing string index charges internal candidate scans`:
two labeled queries admitted zero indexes instead of the established one. Restoring
single-property lookup preserves admission, cached late-match values, and internal
scan-budget failures. The unchanged three-test mapped budget suite, all Cypher tests,
and Cypher lint now pass in `/tmp/graphite-slow-shapes-typed-value-tests.log`.
This is a concrete regression found by the broad gate, not a changed test expectation.
No new performance speedup is claimed. Full checks and final comparisons remain pending.

**Conclusion:** keep the compatibility correction. An optimization of untyped discovery
must not silently change existing labeled-query index policy or work accounting.

### 2026-09-13 - Attempt 035: Keep source discovery lazy for dense DATAFLOW matches

**Hypothesis:** source pushdown must select raw serial storage explicitly. Its unlimited
seed count otherwise rejects the bounded parallel scan and admits an eager CallSite
index before yielding the first seed, even when that seed supplies all 50 output rows.

**Base/candidate:** main `144d98efa2bcb1f183d4f962b833c234d839d2a9` versus
`522a81f9` plus the raw-source policy correction and correctness tests. The frozen
candidate JAR SHA-256 is
`5cc950355a2a91024a3af468c3f57f54b362ff9b292ec1e0a6dc48ba4d89b940`;
exact source identities and patch are in
`/tmp/graphite-slow-shapes-evidence/dense-source-corrected/`.

**Fixture/method:** two diagnostic pairs in reversed revision order, each process
opening an independent private copy of the real persisted Android graph (5,938,826
nodes). Query: `MATCH (c:CallSiteNode)-[r:DATAFLOW]->(n) WHERE c.caller_class
CONTAINS 'android' RETURN c.id,n.id,type(r) LIMIT 50`. Cold means a fresh mapping,
not flushed operating-system pages. These observations are not pooled with final JMH.

| Observation | Main | Corrected candidate |
| --- | ---: | ---: |
| Cold wall, two runs | 133.41 / 131.02 ms | 70.34 / 69.02 ms |
| Warm wall, two runs | 36.96 / 36.83 ms | 9.20 / 8.89 ms |
| Cold process CPU, two runs | 359.97 / 358.69 ms | 133.96 / 131.31 ms |

The preceding candidate had regressed cold wall to 452.70 ms versus main 133.79 ms.
The correction removes that regression. All eight corrected observations have the
same ordered 50-row digest. Neither revision initializes or persists the CallSite
index; all four private copies were removed and shared fixture verification passed.
No measured allocation or peak-memory claim is made for this diagnostic probe.

**Verification:** the source-access test requires serial raw storage, unrestricted
seed discovery, and consumption/expansion of only the first seed when it supplies
the requested 50 rows. Full Cypher tests, lint, and coverage passed after this change;
Cypher line coverage is 98.004%. Full repository checks and final paired timings follow.

**Conclusion:** keep. The planner can push source predicates down while preserving
lazy output-limit behavior and avoiding eager index construction for dense matches.

### 2026-09-13 - Attempt 036: Final isolated acceptance against the updated main

**Hypothesis:** the retained changes satisfy the 10x target for the measured slow
cases without a material regression in existing mapped queries. Freeze the final
candidate and repeat complete comparisons, including resource scope and result order.

**Base/candidate:** main `144d98efa2bcb1f183d4f962b833c234d839d2a9`, candidate source
`b8fc966ee6614806babc79f21fbef625b5f0b0f3`. Dynamic queries use main plus only the
string-subscript correctness repair; incorrect empty main results are not a speedup
baseline. Candidate JAR SHA-256:
`5cc950355a2a91024a3af468c3f57f54b362ff9b292ec1e0a6dc48ba4d89b940`.
All three revisions use identical harness
`39008c47663e97278b8aafc21633acb576c504f265479d589e9b28ebc06bcd0a`.

**Fixture/method:** real persisted Android 14 graph, 5,938,826 nodes; independent
private copies omit the writable CallSite index. Java 17.0.18, M3 Max, 8 GiB heap,
four active processors. Three alternating pairs of fresh-JVM SingleShot measurements
yield 120 observations; 40 separate oracle observations measure query-window process
CPU and all-Java-thread allocation. Cold does not mean flushed OS pages. Exact commands,
inputs, identities, every paired latency, ordered digest, and scope qualifications are in
[the report](slow-query-shapes-optimization.md) and
[machine-readable evidence](slow-query-shapes-results.json).

| Slow cases | Cold wall speedup | Warm wall speedup |
| --- | ---: | ---: |
| value hit/miss | 25.08–27.53x | 61.07–81.00x |
| dynamic hit/miss, repaired reference | 18.18–18.50x | 20.79–21.10x |
| wrapped caller hit/miss | 23.28–25.17x | 26.93–67.19x |
| DATAFLOW source hit/miss | 82.76–89.71x | 136.92–272.59x |
| DATAFLOW target miss | 15.29x | 34.43x |

The already-fast target-hit guard improves from 260.10 to 236.55 ms cold and 157.93
to 127.92 ms warm; it is not a 10x claim. Exact full ordered results agree across all
160 observations. Main/semantic-reference query allocation for the four primary slow
cases is 7.1–41.8 GB versus candidate 2.1–43.6 MB, with CPU improvements 12.28–126.82x.
The additional target-miss case has CPU improvement 7.51x/9.14x and allocation
327.8/359.9 MB; fast target-hit CPU/allocation are effectively level. Allocation is
allocated bytes during the query window, not live heap or peak memory.

**Broader evidence:** existing AndroidQueryBenchmark and LargeCorpusQueryBenchmark
mapped simpleNodeMatch, intConstantFilter, countStar, singleHopRelationship and
returnDistinct on Android, Tika, Hive and Kotlin compiler: 20 cases, 120 scores,
three alternating pairs with two 500 ms warmups and three 500 ms measurements.
Median latency changes range from -5.8% to +5.2%; no case exceeds a 10% increase.
First-pair Android simpleNodeMatch +16.85% and Tika intConstantFilter +13.12% were
retained and examined; reversed pairs improve, and final medians are -1.0%/+5.2%.
There are small allocation increases: countStar +80 B/op (~5.1%), and Tika/Kotlin
simpleNodeMatch +3,280 B/op (~1.3–1.4%). These are reported, not called zero-cost.
All 260 private copies across both series were removed and shared input verification
passed. Existing broad methods do not assert result digests; correctness evidence is
separate. Synthetic CypherBenchmark timings are excluded by the real-fixture rule.

**Verification:** `./gradlew check koverLog --max-workers=2` passes: 2,498 regular
tests, seven memory-contract tests, and three independent real-corpus 4 GiB end-to-end
gates. Existing lint and coverage gates pass, including Cypher 98.004%. Frontend
ui-state tests pass. End-to-end gates pass their existing ceilings; they are not a
paired main/candidate end-to-end speedup claim. No PR or hosted benchmark-regression-gate
has been run, and local evidence does not substitute for that required PR check.

**Conclusion:** keep the frozen implementation. The measured slow cases exceed 10x,
ordered results agree, and the existing-query series shows no >10% median regression.
Unsupported/numeric search-text fallbacks and every possible query of the same shape
are outside this empirical 10x claim. Complete evidence is preserved rather than
pooling earlier prototypes or fixture protocols into the final result.

### 2026-09-13 - Attempt 037: Prune qualified identity candidates before node decoding

**Hypothesis:** qualifiedId is generated from the external graph namespace and local
node ID. Testing that exact string while iterating primitive IDs can reject nodes
without decoding their payload, preserving full residual predicates and encounter order.
Use the same candidate binding for LIMIT, no-LIMIT, ordering and initial aggregate paths.

**Base/candidate:** unmodified main `144d98efa2bcb1f183d4f962b833c234d839d2a9`,
re-fetched after implementation. Candidate is parent `7351f9696558e2341c90c246a2c1450a636ef105`
plus the source files frozen in `qualifiedIdExtension.artifacts` of
[the machine-readable evidence](slow-query-shapes-results.json). Candidate JAR SHA-256
`3f46433e7d4cd0279a39525001c4aa3dceb07aa4aff0da16ba6674b326e7b6f7`;
main JAR `59094b5cf634ca2f5dcbc1f1c0fae6794e6494ba7f0d77ee4f6162596846d5ce`.
Both use the twelve-case harness
`6b112a1bb6f12184fa27fa8b72f87f35a2071c2d354c8236f10e4e51662ca8fb`.
Previous four-family measurements retain their original source/harness identities.

**Fixture/method:** the same real persisted Android 14 graph (5,938,826 nodes), private
copies without a CallSite index, Java 17.0.18, M3 Max, 8 GiB heap and four active CPUs.
`SlowQueryShapesBenchmark.execute`, queryName `qualifiedIdHit,qualifiedIdMiss`,
`-f 1 -wi 0 -i 1 -prof gc`, COLD/WARM, three alternating revision pairs. Search
texts are `938826` (six hits, including the last persisted node) and `93882699`
(no hits). Cold does not flush OS pages. Separate correctness-oracle processes
measure query-window CPU and all-Java-thread allocated bytes; allocation is not peak heap.

| Case/state | Main wall | Candidate wall | Speedup | CPU speedup | Main/candidate query allocation |
| --- | ---: | ---: | ---: | ---: | ---: |
| Hit cold | 3,440.852 ms | 153.040 ms | 22.48x | 14.88x | 8,582.790 / 8.871 MB |
| Miss cold | 3,577.282 ms | 138.692 ms | 25.79x | 19.27x | 8,581.685 / 8.119 MB |
| Hit warm | 3,340.300 ms | 77.246 ms | 43.24x | 35.60x | 8,563.272 / 0.024 MB |
| Miss warm | 3,347.210 ms | 78.405 ms | 42.69x | 33.49x | 8,563.260 / 0.003 MB |

**Verification:** all 24 JMH and eight resource-oracle observations preserve exact
full ordered result digests; all 28 private copies were removed and shared input
hashes/mtimes remain unchanged. Ten Cypher tests verify namespaces, colon boundaries,
sparse/negative IDs, plain Annotation metadata, parameters, errors, budgets,
order/provenance, no-LIMIT/order/count/group paths, and safe fallback. Five mapped tests
verify ordered primitive traversal, one work charge per tested ID, lazy consumption,
interrupts, and zero rejected-node decoding using poisoned payloads with a decode control.
Full repository checks passed with the initial extension; complete Cypher tests,
lint, coverage and JMH compilation passed again after adding the general paths.
Combined current suite: 2,513 regular, seven memory-contract and three real-corpus tests.
Cypher coverage is 98.0033%, WebGraph 98.1051%. Independent code review found no blocker.

**Conclusion:** keep. The measured qualifiedId cases exceed 10x without loading
rejected nodes. Generated identity semantics apply only to qualified bindings;
plain graph properties and unsupported expressions retain normal evaluation.
Namespace-only matches retain the ordinary lazy scan. No-LIMIT/order/aggregate
paths have source-access correctness evidence, not separate latency claims.
The subsequent required-gate integration is recorded separately from this experiment.
