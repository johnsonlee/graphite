# Predicate-range preflight before provenance

## Hypothesis

Before resolving selected DISTINCT tuples in a graph, compute the predicate matching ranges and
skip tuple resolution when the predicate has no hits.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g`.
- Reference implementation: instrumented selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: the oracle result remained exact.
- Wrapped case-insensitive DISTINCT dense latency: 59.69 ms -> 83.31 ms (0.72x).
- Graph work: 153,786 -> 1,025,390 units.
- The preflight was non-empty in 64/64 graphs even though only two graphs contributed one of the
  selected projection tuples.

## Decision

Reverted. Predicate presence is too weak a filter for selected-tuple provenance. The experiment
increased work 6.7x and made latency 40% worse.
