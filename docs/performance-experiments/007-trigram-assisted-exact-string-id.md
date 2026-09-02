# Trigram-assisted exact string-ID lookup

## Hypothesis

For each selected projection value, probe its sparsest existing trigram posting range and verify the
candidate string exactly, avoiding a full-table front-coded binary search without adding a new
persisted structure.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g`.
- Reference implementation: selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: 34/34 oracle records passed; zero failures and zero timeouts; ASCII collision, case,
  missing-value, short-string/Unicode fallback, cancellation, and work-denial tests passed.
- Wrapped case-insensitive DISTINCT dense latency: 50.916 ms -> 49.683 ms (1.02x).
- Graph work: 153,786 -> 255,449 units (+66%).

## Decision

Reverted. Repeated trigram-range binary searches cost more work than the front-coded lookup they
replaced and did not produce a reliable latency gain.
