# Attempt 142: shared bounded query scheduler — first candidate not accepted

This attempt replaces independent graph-source, Method, CallSite scan/segment and Guard
query worker pools with one structured shared scheduler. The resource owns at most NCPU
live workers, including idle workers. Nested task ownership, helper eligibility, queued
cancellation, token-only cancellation and actual descendant exit are explicit. Storage
submission scopes join before releasing reservations; normal cancellation callbacks and
no-consumer sorting are included. Original query plans, predicate algorithms and index
formats are retained.

The worker bound is for scheduler-owned computation workers. Existing caller execution,
such as topology queries under registry mutation callbacks, is not covered by a claim
that every JVM/query-calling thread is bounded. HTTP, timeout, persistence compression
and JVM management threads are not all this scheduler's workers.

## Validation

- 2,096 module tests pass: core450, cypher1235, webgraph190, explore221; all four detekt pass.
- Webgraph and Explore JMH packaging pass; Webgraph JMH test exclusion passes.
- 16 scheduler tests pass with ActiveProcessorCount=32, including live and idle worker counts.
  This is a correctness configuration, not measured performance on a physical 32-core machine.
- Exact webgraph JAR contains 560 unique byte-identical compiled core/cypher/webgraph classes;
  Explore JAR contains 835 unique byte-identical compiled main classes across four modules.
- Real supplemental36 full values/order/provenance control passes; input/JAR identity retained.
- Earlier build failures and subsequent repairs are retained in checks5–8 logs; no failed
  intermediate snapshot is described as passing.

## First performance evidence

Frozen main: `4e328b0109e13c896b74004823fb049fcb19251a`; candidate parent:
`599a3ece5e47cace9254144cdfaf4595090ea5a8`. Fixture is 64 real persisted class shards
from Android, Tika, Hive and Kotlin compiler, not 64 independent applications. Java17,
macOS, ActiveProcessorCount4, original JMH zero-warmup/single-iteration/single-fork protocol.
No concurrent build, Java analysis or profiling during these acceptance pairs.

| Pair/order | main → candidate P95 ms | main → candidate process CPU s |
|---|---:|---:|
| 1 C/B | 62.739458 → 51.100291 | 1.744503 → 1.602539 |
| 2 B/C | 41.604709 → 42.968791 | 1.495837 → 1.559311 |

Pair2 does not show strict P95 progress. Stop this snapshot's acceptance measurement,
retain both pairs and do not start candidate CI or supplemental performance pairs.
This is an unsuccessful first candidate in the current direction, not proof that shared
scheduling is wrong. Under the updated user objective, investigate the P95 query and
actual execution before revising the same direction; do not abandon after one failure.
No performance-neutral or failed candidate is promoted to the final PR. No 10x claim.

Webgraph candidate JAR SHA256:
`f0c865e4c63188a5f2ec7a09588b1d7ee047ea603e0eae8442631d8f351b7f1f`.
Explore candidate JAR SHA256:
`5ef38c66eafe13249fd7b3c4ed01f8b5a7e23b57effba217cc80ffc76e4cbc7b`.
Immutable binaries, complete commands, logs, source fingerprints, tests and raw comparisons:
`/private/tmp/graphite-attempt142.O53GyN`.
