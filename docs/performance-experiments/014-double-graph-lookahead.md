# Double graph lookahead

## Hypothesis

Keep the eight graph workers and eight storage workers selected from the 16 available processors,
but allow the source-ordered rolling scheduler to submit two graph-worker windows ahead. A larger
ready queue could hide a slow graph lookup without changing the active-worker budget or result
order.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: one paired `global-wide` run, 34 cases, cold indexes, `LIMIT 200`, `-Xmx8g`.
- Base revision: `3122931` with the retained one-window scheduler; observation
  `/tmp/pr113-exp014/control.tsv`.
- Candidate: the uncommitted two-window snapshot; observation
  `/tmp/pr113-exp014/lookahead2.tsv`.
- Correctness: both runs verified all 34 records against the same real-fixture oracle with zero
  timeout or failure.
- Aggregate P95: 75.130 ms -> 78.883 ms (5.0% slower).
- Wrapped case-insensitive DISTINCT dense: 26.683 ms -> 78.883 ms (2.96x slower), with identical
  177,117 work units. The extra queued provenance scans increased contention without reducing
  storage work.
- Four-property targeted: 75.130 ms -> 76.218 ms (1.4% slower), with identical 104,972 work units.
- Process CPU time fell 4.7%, peak used heap fell 2.5%, and peak RSS fell 7.7%, but those reductions
  do not compensate for the latency regression.

## Decision

Rejected. The production change is reverted. A larger speculative queue preserves the active
8 + 8 worker bound but competes with the leading DISTINCT work and does not reduce the next P95
bottleneck. The one-window scheduler remains in production.
