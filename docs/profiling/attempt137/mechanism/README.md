# Attempt 137: callback mechanism profile

One new CPU/allocation recording per revision replays the same two pure four-OR
queries 40 times each within one JVM, resetting indexes per query. Full returned
values, order and provenance match the independent oracle for all 160 queries.
The exact unchanged JARs and real graph files match the unprofiled experiment;
pre/post hashes are checked. These are diagnostic recordings, not 40 independent
forks, per-query P95, or acceptance reruns. Exact-head CI subsequently rejected the candidate for repeated Method4 CPU regressions; the production change is reverted.

| Across 40 DISTINCT windows | Frozen main | Candidate |
|---|---:|---:|
| All CPU samples | 13,830 | 9,169 |
| Inclusive validator CPU samples | 11,002 | 6,673 |
| All allocation sample weight, bytes | 49,397,143,680 | 2,952,305,632 |
| Boxed leaf allocation weight within validator stacks | 46,526,889,984 | 2,097,152 |
| HeapByteBuffer leaf allocation weight within validator stacks | 2,558,564,480 | 2,615,188,448 |

The remaining 2,097,152 boxed sample weight occurs in
`consumeGraphWork → PreferredMappedStringIndexViewGraphWorkConsumer.consume → Long.valueOf`,
not the modified per-element callback. The residual stacks are preserved beside
this report. This agrees with the independently verified primitive callback
bytecode; it does not assert all validator allocations are gone.

Allocation sample weights are estimated TLAB weights, not exact bytes or object
counts. CPU samples are stack observations; percentage changes are not measured
latency speedups. Query-window sample totals conserve collapsed/thread weights;
no CPU or allocation stack is missing or truncated. The trace starts after a
small positive TSV latency gap (up to about 8 ms on the first query); no query
IDs exist inside JFR, so binding uses chronological exact-signature traces and
the verified sequential workload order.

Existing buffer allocation and validator CPU remain visible. They are recorded
as observations only; no additional optimization is included in Attempt 137.
See [summary](summary.json), [alignment/conservation](alignment-and-conservation.json)
and the exact capture/analyzer commands alongside this file. Raw JFR/rows/collapsed
files remain at `/private/tmp/graphite-attempt137.dcywsuq7/mechanism`.
