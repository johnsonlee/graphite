# Local complex-query P95 protocol

Declared before new performance samples, 2026-11-11 (Asia/Seoul).

## Scope and baseline

The objective is lower P95 for slow and complex aggregate queries. P50 is retained
as diagnostic data, not used to offset P95 regressions. Correctness, availability,
stability and the independent CPU/RSS limits remain mandatory.

Baseline product main is `58215b986f8c1be68b0480bc209e00311bc80dc1`.
The baseline native build checkout is `a911529a` (only candidate workload/tests,
documentation and retained evidence added; no native implementation change).
Its physical Cargo target is private to `/tmp/graphite-complex-p95/base`.
Candidate builds use the task checkout's separate physical target. Record actual
source commits, tree IDs, compiler versions, binary SHA256 and fixture seals before
comparison. Never rebuild one arm into another arm's binary path.

Use the same retained real64 head graph manifest for both arms:
`/private/tmp/graphite-pr211-final-benchmark/fresh-run/results/frontend/head/graphs/graphs.tsv`.
Its SHA256 is `ce217bcec57cb59d50a24f68baa0e88ce0275ae6d5172b07830b8cfef58f21bf`.
These are 16 shards from each of Android-all, Tika, Hive and Kotlin compiler,
with 19,438,099 nodes and 21,081,153 edges in the retained evidence. Verify actual
manifest and persisted inputs at the handoff. Every request targets all64 graphs;
complete responses must report the scope and valid provenance.

This is a native query investigation using retained producer outputs, not new
build/save acceptance. The corpus is not claimed equivalent to50+ independent
production projects; production slow-query incident recovery remains unproven.
No synthetic or single-graph performance samples are permitted.

## Feedback path

Baseline native build took approximately24 seconds of preparation. Reuse persisted
graphs and perform one integrity seal before runs; do not rebuild JVM graphs for
each native experiment. First execute a smoke pass to validate loading, all six
query texts and complete output. First-use observations estimate the next stage's
critical path; they are not a P95 distribution. If any query fails, retain its
failure and do not automatically expand to a pressure run.

The new local Rust controller consumes the fixed catalog
`benchmark/complex_aggregation_queries.json`. It is separate from the unchanged
trusted73-case CI protocol. Code review and correctness tests precede measurement.

## Sampling and boundaries

- Each arm uses a fresh production Rust server with the same graphs and settings.
- Fixed maximum query concurrency4. Complex local query deadline60 seconds and
  client timeout70 seconds; preserve timeouts, cancellations and errors. Existing
  official CI timeout policy is unchanged.
- Smoke: one first-use request per query, four workers; no warmup/pressure.
- Pilot: one first-use and two warmups per query, then20 requests per query at
  four workers interleaved by round.
- Final complex measurement: one first-use and two warmups per query, then100
  requests per query at four workers. No P50 filter on case eligibility.
- First incremental pilot is baseline then candidate. Cumulative final comparison
  uses three independent paired rounds in AB/BA/AB order. Report every round;
  do not discard an unfavorable run. A means baseline, B means candidate.
- Calculate nearest-rank P95 per query per arm/round (rank19 of20 in pilot,
  rank95 of100 in final). Retain P50 and full raw samples; show min/max and
  between-round variability. Report baseline/candidate milliseconds and absolute
  changes before percentages. Do not pool query classes or select a new statistic
  after observing results. Pilot and final samples remain separate.
- Latency covers request through complete HTTP body consumption. Client validation
  and artifact writes are outside that latency but between worker requests. This
  is closed-loop pressure with validation gaps, not saturated capacity or a fixed
  arrival-rate experiment. No maximum-QPS claim follows.
- First-use is a sequence after registry-ready, not an independently cold process
  for each query. OS page cache is uncontrolled; do not label any sample disk-cold.
- Freeze full expected responses per query and compare all repeats and arms using
  strict typed JSON, ordering, missing/null fields and provenance. The first-use
  result is a reference to compare implementations, not an independent semantic
  oracle. Deterministic fixture tests and changed-path generic parity provide the
  complementary semantic proof.

## Resources and acceptance

Measure server user+system CPU at readiness and stage boundaries. Query-window
CPU is the complete mixed pressure-stage delta; simultaneous per-query CPU is not
inferred from it. Record wall time for the full stage separately. Loading and
first-use CPU remain visible so work migration cannot hide a regression.

On macOS, distinguish sampled resident memory from exact kernel lifetime peak RSS.
The lifetime peak includes loading; do not subtract two high-water marks or call
physical footprint RSS. Use a separate fresh load-only process for loading peak
when supported, and a fresh query process for the declared query-scenario lifetime
peak. Each declared operation/workload CPU and RSS comparison has its own5% limit;
no resource or workload offsets another. Missing resource evidence is unavailable,
not PASS. JVMs, if needed by correctness/CI, use the existing4 GiB JAR setting and
never exceed8 GiB. Native RSS is a separate constraint.

Local machine: Apple M3 Max,16 physical/logical CPUs reported,64 GiB RAM,
macOS14.3. A background mediaanalysisd process was using approximately one core
at preparation. Do not stop unrelated user/system work. Record environment and
interference; do not run compiles or CPU-heavy tests concurrently with latency
measurement. Hosted CI timing remains report-only.

Final cumulative validation includes the existing73 multi-graph query regression
workload, relevant module and workspace correctness/lint checks, and the required
PR benchmark correctness/OOM gate. Keep per-case P95/resource outcomes explicit.
Each optimization attempt is a separate commit and chronological history entry;
retain independently beneficial increments while reporting unresolved constraints.
No claimed production recovery or formal performance pass can be inferred merely
from a clean PR or a controller's successful exit status.

## Amendment before Attempt316 measurement

The original main baseline failed during the unchanged60s-server/70s-client
warmup, before any pressure sample. Preserve that failure; do not repeatedly run
it to manufacture a percentile. It cannot provide a numerical P95 recovery or a
matched complete mixed-pressure CPU comparison. Attempt315 provides an incremental
reference only if its full pilot completes; it is not the accepted original main.

Observed Attempt315 pilot throughput (80/120 completed at approximately304s,
including startup and warmup) puts three100-request paired rounds on a roughly
multi-hour critical path. To keep feedback short without presenting reduced
sampling as equivalent precision, use this amended, predeclared sequence:

- Three paired20-request-per-case rounds in AB/BA/AB order for incremental
  screening and variability, with per-round rank19 P95 and no pooling.
- One separate paired100-request-per-case confirmation in BA order for the final
  retained cumulative candidate against the retained working incremental reference.
  It includes all six cases and the same concurrency, full results and resource
  boundaries. This confirmation is required independently of whether the20-sample
  result looks favorable; it is not conditional on selecting a favorable statistic.
- Retain each round's complete evidence and all prior failed arms. The100-sample
  confirmation is not three independent100-sample rounds; disclose that uncertainty.
  Neither incremental reference nor sample-count amendment permits a claim that
  original-main pressure P95/CPU recovery was measured when that baseline failed.

This changes statistical precision and execution cost, not query scope, semantic
coverage, request completeness, timeout/capacity behavior or resource allowances.
If a candidate cannot complete the declared workload or satisfy available matching
resource checks, report that outcome rather than relaxing the protocol again.

## Predeclared Attempt317 follow-up

Compare retained Attempt316 (A) with Attempt317 (B), using the same sealed real64
inputs and complete result-consumption boundary. Before measurement, declare:

- Original `complex`: AB pilot with20 pressure samples per case, followed by a
  separate BA measure confirmation with100 per case. Keep the two rounds separate.
- `complex-wide-distinct`: AB pilot with20 pressure samples per case. This fixed
  variant changes only co-use to `count(DISTINCT caller) AS callers`; the other
  five cases remain unchanged. It targets all64 real graphs, not65/130 graphs.
  Preserve full typed responses proving a caller dictionary beyond64 values
  (and beyond512 if observed), or an independent untimed cardinality check.
- The server/client deadlines remain60/70seconds. If a baseline arm fails, retain
  every issued request and failure; no success-only P95 or matched full resource
  comparison is available. A subsequent BA or candidate-only run may establish
  completion and preserve observations, but cannot repair the missing baseline
  distribution or establish comparative performance acceptance.

All existing loading/query CPU and lifetime RSS boundaries, fixed warmups,
correctness requirements and5% independent resource limits remain in force.
The new wider-dictionary workload must not be pooled with the original six-case
measurements. Physical65/130-graph tests remain separate correctness coverage
unless representative real persisted inputs and a performance protocol exist.
