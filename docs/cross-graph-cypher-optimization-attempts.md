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
