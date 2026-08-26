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
