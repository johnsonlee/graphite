# Rare trigram direct verification

## Hypothesis

When the rarest trigram leaves at most 256 candidate strings, verify those complete strings
directly instead of intersecting the candidate set against every remaining trigram. This could
reduce binary searches for long targeted terms without changing the final predicate check.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: one paired `global-wide` run, 34 cases, cold indexes, `LIMIT 200`, `-Xmx8g`.
- Base revision: `3122931`; observation `/tmp/pr113-exp014/control.tsv`.
- Candidate: the uncommitted rare-anchor snapshot; observation
  `/tmp/pr113-exp015/rare-anchor.tsv`.
- Correctness: both runs verified all 34 records against the same real-fixture oracle with zero
  timeout or failure. Focused Unicode/collision and match-cache tests also passed.
- Aggregate P95: 75.130 ms -> 75.493 ms (0.5% slower).
- Aggregate P50: 6.478 ms -> 7.213 ms (11.3% slower).
- Total work: 59,720,551 -> 59,716,915 units, only 3,636 units saved.
- Process CPU time fell 2.8%, peak heap fell 2.2%, and peak RSS fell 8.4%, but latency did not
  improve.

## Decision

Rejected. The production change is reverted. Investigation showed that the current P95 query's
104,972 units are dominated by a 100,606-unit raw scan of the serial leading graph; the remaining
indexed graphs leave too little trigram-intersection work for this optimization to matter.
