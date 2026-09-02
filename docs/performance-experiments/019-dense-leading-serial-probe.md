# Dense leading serial probe

## Hypothesis

For a bounded unscoped row query whose `CONTAINS` alternatives all use a term of at most three
characters, the ordered leading graph is likely dense enough to satisfy `LIMIT` directly. Probe
that graph serially and retain the balanced NCPU split for later graphs. Longer targeted and sparse
terms continue to use the persisted sidecar on the leading graph.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the four pinned fixture JARs.
- Workload: the complete 34-case global-wide workload, cold indexes, `LIMIT 200`, and `-Xmx8g`.
- Base revision: `b004f2c`, using split storage for every unscoped leading probe.
- Candidate: the dense-leading serial snapshot initially created in this commit.
- Correctness: all 34 candidate records matched the base-generated real-fixture oracle; the focused
  Cypher test and detekt passed.
- Aggregate P50 / P95: 1.698 / 28.123 ms -> 1.841 / 24.333 ms. The 1.16x P95 observation is below
  the 2x milestone and is not large enough to separate from single-shot variance.
- Total graph work was identical at 58,014,194 units. The first zero-result query had already
  retained the persisted sidecars, so changing the later leading consumer to serial did not select
  the raw path.
- Process CPU changed from 3.883 s to 4.166 s (+7.3%). Peak used heap fell 4.9% and peak RSS fell
  1.9%; there was no compensating work reduction.
- Raw observations and JMH JSON are under `/tmp/pr113-exp019-run.zOr8ii/`; the paired base is under
  `/tmp/pr113-exp017-pair.igK5Ew/`.

## Decision

Reverted. The consumer choice cannot recover the dense raw path after a sidecar is resident, and
the observation did not meet the 2x incremental keep threshold. This docs-only commit leaves the
production and test behavior unchanged.
