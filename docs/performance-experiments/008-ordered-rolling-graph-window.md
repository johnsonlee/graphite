# Ordered rolling graph window

## Hypothesis

Keep source-order semantics while replacing whole-wave barriers with a bounded rolling completion
window. Probe the leading graph synchronously, schedule the next graph as soon as the ordered prefix
advances, pass the pruned source count into storage planning, and stop/cancel once `LIMIT` is known.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: three alternating paired `global-wide` runs, 34 cases per run, cold indexes, `-Xmx8g`.
- Base revision: `78ce46b` (`main` / `v2.4.7`).
- Candidate lineage: PR head `882fb90` plus the rolling-window working-tree change.
- Correctness: every candidate run passed all 34 oracle records with zero timeout/failure; graphId
  K=64 separately passed all 1,137 records byte-for-byte against base.
- Aggregate P50 speedup: 151x to 154x across the three pairs.
- Aggregate P95 speedup: 7.76x to 8.77x across the three pairs.
- K=64 graphId-set P50/P95: 10.511/24.888 ms -> 0.812/6.163 ms.
- CPU, heap, and RSS stayed within the paired 15% regression limits; observed peak workers were the
  planned 8 graph + 8 storage workers on the 16-CPU host.

## Decision

Kept as the first incremental milestone. It is materially faster and preserves correctness, source
order, cancellation, and the additive CPU bound. It does not claim the cumulative 10x P95 goal:
wrapped case-insensitive DISTINCT remains the next measured bottleneck.
