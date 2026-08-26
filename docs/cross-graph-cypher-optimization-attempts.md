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
