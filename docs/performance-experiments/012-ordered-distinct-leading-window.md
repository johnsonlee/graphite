# Ordered DISTINCT leading window

## Hypothesis

The indexed DISTINCT path should use the same ordered leading-window policy as the retained
non-DISTINCT path. When the first graph already supplies `LIMIT 200`, launching and joining an
entire eight-graph prefix wave performs seven unnecessary projection scans before the required
all-graph provenance pass. Probe only the first graph with the storage half of the NCPU budget, then
roll graph tasks forward in source order only when more distinct rows are required.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold indexes, `LIMIT 200`, `-Xmx8g`.
- Base revision: `f87e90a`.
- Candidate: this experiment commit.
- Protocol: three local paired JVM forks in alternating candidate/base order. Files are under
  `/tmp/pr113-exp012`; the first pair ran candidate then base, the second base then candidate, and
  the third candidate then base.
- Correctness: all 34 outcomes, row counts, response sizes, and result digests matched in every
  pair; the new deterministic test proves only graph zero performs the initial dense projection,
  all 64 graphs still perform selected-tuple provenance, and the leading lookup receives the
  eight-worker storage half on a 16-CPU host.
- Aggregate P95: `210.727 -> 187.808 ms` (1.12x), `212.588 -> 147.400 ms` (1.44x), and
  `211.089 -> 183.831 ms` (1.15x).
- Wrapped case-insensitive DISTINCT dense work: `5,201,615 -> 5,070,631` units in every pair.
- Process CPU delta: `+5.1%`, `+3.2%`, and `-9.1%`.
- Peak used heap delta: `+0.6%`, `-2.6%`, and `-0.2%`; peak RSS delta: `+0.4%`, `-2.3%`, and
  `-0.4%`.
- A serial-storage leading probe was separately rejected: it raised the DISTINCT-dense latency to
  `434.704 ms`. The retained form keeps the planned eight segment workers.
- Validation: full `:cypher:test`, focused execution-path test, `:cypher:detekt`, and
  `git diff --check` passed in an isolated clone.

## Decision

Kept as an incremental optimization. It improves P95 in all three paired comparisons without
changing correctness, retained memory, or the additive 8 graph + 8 segment worker contract. It does
not by itself satisfy the cumulative 10x objective; the remaining cold selected-tuple index build
is the next measured bottleneck.
