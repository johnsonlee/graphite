# Allocation-free anchor probe

## Hypothesis

Remove the per-posting `IntArray` and key-object allocations from selected-tuple anchor scans. Use a
primitive projection hash to select a bucket and the existing four-property equality check to
resolve collisions.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g`.
- Reference implementation: selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: 34/34 oracle records passed; zero failures and zero timeouts; deliberate projection
  hash collisions and repeated anchors passed exact-result tests.
- Wrapped case-insensitive DISTINCT dense latency: 50.916 ms -> 43.739 ms (1.16x).
- Graph work remained 153,786 units.

## Decision

Reverted. The allocation reduction was real but did not address the dominant work. Follow-up phase
profiling measured only about 1.15 ms in anchor posting scans, confirming that this loop was not the
primary tail-latency source.
