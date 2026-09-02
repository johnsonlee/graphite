# Scoped leading serial probe

## Hypothesis

An explicit `graphId` set already supplies the graph-level routing decision. Keep its first graph's
bounded `LIMIT` probe serial, while retaining the NCPU-balanced graph and segment workers for later
graphs. This avoids paying segment dispatch and persisted-sidecar retention overhead before the
ordered leading source has been tested, without changing unscoped global-wide execution.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the four pinned fixture JARs.
- Workload: all `graphId(n)` and `/api/cypher/graphs` routing shapes, including the explicit K64
  graph set, with cold, warm, and startup-prepared index states.
- Base revision: `a2cf5a4`, retaining split storage for every K64 leading probe.
- Candidate: this commit's scoped-leading serial probe.
- Correctness: all 1,137 candidate records in each state matched the base-generated real-fixture
  oracle; the focused Cypher test and detekt passed.
- Explicit K64 P50 / P95:
  - cold: 0.658 / 8.699 ms -> 0.691 / 1.853 ms (4.69x P95);
  - warm: 0.436 / 0.497 ms -> 0.257 / 0.406 ms (1.22x P95);
  - startup-prepared: 0.786 / 1.828 ms -> 0.758 / 1.696 ms (1.08x P95).
- The nine K64 records consumed exactly 4,153, 1,011, and 4,153 work units in both revisions for
  cold, warm, and startup-prepared respectively.
- Whole-suite cold CPU changed from 9.969 s to 11.058 s (+10.9%); warm CPU changed from 2.181 s to
  2.290 s (+5.0%); startup-prepared CPU fell from 5.029 s to 4.839 s (-3.8%).
- Worst peak-heap change was +1.6% and worst peak-RSS change was +1.0%, within the paired 15%
  resource limits. The whole-suite P95 changed by +2.0%, from 25.585 ms to 26.088 ms.
- Raw observations and JMH JSON are under `/tmp/pr113-exp018-pair.kASeHc/`; the regenerated fixture
  is under `/tmp/pr113-exp017-fixture.t1A36b/`.

## Decision

Kept. Explicit graph scoping now avoids nested work in the ordered leading probe and retains the
balanced 8+8 plan for later K64 graphs. Unscoped global-wide queries continue to split the leading
graph, so the retained global-wide speedup is unaffected.
