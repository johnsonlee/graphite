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

java -jar graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
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
java -jar graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
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
java -jar graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
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
java -jar graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
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
coverage is `98.2569%` for Core, `98.1832%` for Cypher, `98.0213%` for WebGraph,
and `98.1046%` for Explore.

**Budget-enabled executor cost used by HTTP:** `BudgetedCypherBenchmark`
isolates successful unbudgeted and budgeted executor calls against the same 500
two-hop chains. It does not include Jetty or network time. This is separate from
`CypherBenchmark`, which remains the base/PR regression gate for the default
library API.

```shell
./gradlew :cypher:jmhJar --no-daemon
java -jar graphite-cypher/build/libs/cypher-1.0.0-SNAPSHOT-jmh.jar \
  'BudgetedCypherBenchmark.*' \
  -wi 3 -i 5 -w 1s -r 1s -f 2 -foe true
```

Apple M3 Max, 64 GiB RAM, macOS arm64, OpenJDK 17.0.18, JMH 1.37, one
benchmark thread. Values and 99.9% confidence errors are `us/op`.

| Successful query | Unbudgeted | Budgeted | Budget-check cost |
|------------------|-----------:|---------:|------------------:|
| 500-node scan | `47.002 +/- 0.478` | `47.836 +/- 0.958` | `+1.8%` |
| 500 single-hop relationships | `142.833 +/- 4.256` | `143.634 +/- 1.466` | `+0.6%` |
| 500 two-hop variable paths | `243.109 +/- 3.029` | `244.341 +/- 0.970` | `+0.5%` |

The budget check has a measurable cost; this is not described as a free change.
The node result is the highest at 1.8%, and all three 99.9% confidence intervals
overlap. The relationship benchmark includes source-node, every untyped edge
candidate, and target-node accounting.

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
cache clearing or LRU eviction. Final application line coverage is `98.3471%`
for Core, `98.0897%` for Cypher, and `98.0072%` for WebGraph; the complete
CI-equivalent `check` gate passes after these tests.
