# Split leading sidecar lookup

## Hypothesis

The leading graph should keep its synchronous, source-ordered `LIMIT` probe, but use the storage
half of the NCPU budget instead of a serial storage consumer. On a persisted graph, the split
consumer can restore and query the CallSite sidecar; the serial consumer cannot restore it and
falls back to a full raw scan.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: three alternating paired `global-wide` runs, 34 cases per run, cold indexes,
  `LIMIT 200`, `-Xmx8g`.
- Base revision: `3122931`, retaining the serial leading storage probe.
- Candidate: the split leading-probe change in this commit.
- Correctness: all six paired executions verified all 34 records against the same real-fixture
  oracle, with zero timeout or failure. The focused cross-graph suite also passed all 66 tests and
  verifies that the leading probe receives the planned segment-worker count.
- Aggregate P50 speedup: 3.27x, 3.48x, and 3.94x.
- Aggregate P95 speedup: 2.29x, 2.85x, and 3.09x.
- Aggregate P95 ranges: 60.093-74.861 ms -> 21.924-26.253 ms.
- Four-property targeted work: 104,972 -> 4,746 units. The result remains 11 rows with the same
  digest, while access/index lookup returns from 63 to all 64 graphs. The eliminated 100,226-unit
  delta is the leading graph's raw scan.
- Process CPU time fell 28.6-40.7% in every pair.
- Peak used heap ranged from -3.6% to +5.4%; peak RSS ranged from -9.2% to +4.1%. Both stay within
  the paired 15% resource limits.
- Against the three local `main` / `v2.4.7` real-64 observations (381.017-395.924 ms P95), the
  candidate's 21.924-26.253 ms range is a cumulative 14.5x-18.1x improvement. Exact-head CI remains
  authoritative for the 10x gate.
- Raw observations and JMH JSON are under `/tmp/pr113-exp016/`; the candidate and control JMH JAR
  SHA-256 values are `2934ce86234e287018b4653a208caa424c8f8b91cb546f577af5405ad71a7b33`
  and `a0aa2564e3090a0687d3bfb4889a06dec9eccbf9121473e7e878db7fb4df39ad`.

## Decision

Kept. The query still probes graph zero before scheduling later graphs and retains deterministic
source order and early `LIMIT` termination. Only the storage strategy inside that probe changes:
on the 16-CPU host it uses the planned eight segment workers, while later work remains bounded by
the separate eight graph workers.
