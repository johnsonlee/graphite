# Benchmark regression gate

Every pull request runs `.github/workflows/benchmark.yml` as a required status check named
`benchmark-regression-gate`. It compares the pull request with its exact base commit on the same
GitHub-hosted runner. A committed score from a different machine is not used as the comparison
baseline.

The workflow updates one `Benchmark Regression Gate` comment on the pull request. The comment
contains the base and candidate SHAs, runner architecture, every measured score, delta, threshold,
and gate decision. Raw JMH JSON and large-corpus logs are retained as workflow artifacts for 14
days.

## Trust boundary

The pull-request revision supplies production code only. Comparator commands, expected benchmark
keys, workload harnesses, fixture-preparation harnesses, shard combination, and final aggregation
are all loaded from the pull request's exact base SHA. Fixed baselines and the candidate are built
with that base-owned workload source. Candidate copies of comparator tests run as ordinary test
coverage in a separate runner that contains only the candidate checkout. They cannot access the
base comparator, measurements, or authoritative workspace and are not part of the gate decision.

Benchmark execution jobs have only `contents: read`, every checkout disables credential
persistence, and result artifacts are downloaded by exact name into separate directories before
the trusted aggregator stages the expected report/status pair. The only job with
`pull-requests: write` downloads the already-aggregated report and updates the PR comment; it does
not check out or execute candidate code.

`.github/CODEOWNERS` assigns the workflow, comparator, JMH workloads, and large-corpus gate harness
to the repository owner. The active `main` ruleset requires code-owner review so changes to this
trusted control plane cannot approve themselves; the repository owner is the sole pull-request
bypass actor for solo maintenance. This repository-local boundary prevents direct comparator or
workload substitution; defending against a deliberately hostile candidate build script additionally
requires an external required workflow or GitHub App on an isolated runner.

## Wrapped case-insensitive latency gate

The `wrapped-query-latency` job protects the production
`toLower(coalesce(...)) CONTAINS` discovery shape on persisted mapped graphs.
The same `WrappedDiscoveryLatencyBenchmark` source is compiled at three
revisions:

1. fixed pre-PR-95 commit `44b57562f2b3d0c88882a9002bdc488e05e5d7a7`;
2. the pull request's current base SHA; and
3. the pull request candidate SHA.

The 17 latency keys are split across nine parallel matrix shards: four for the
synthetic 1/4/16/64-graph scales, four for pairs of real-fixture query cases,
and one for the real 36-graph case. Within
each shard, fixed baseline, current base, and candidate still run sequentially
on the same runner, so parallelism does not turn cross-runner variance into a
performance comparison. A prerequisite job restores or builds the persisted
fixture graphs once with a 4 GiB heap, using a content-addressed cache key over
the graph-building/serialization sources, fixture harness, dependency catalog,
and Gradle build files. Real shards restore that immutable cache instead of
rebuilding 19 million nodes independently. The final `wrapped-query-latency`
job fails closed unless all nine shard reports arrive and their union contains
every expected key exactly once.

It measures warm and cold queries on a geometric `graphCount=1/4/16/64` scale on
deterministic persisted graphs. It also builds every repository benchmark
fixture (Android, Tika, Hive, and Kotlin Compiler) sequentially on a cache miss,
then each real shard opens the four
persisted graphs together, and measures the same query over the heterogeneous
19,091,048-node graph set. A separate 36-graph benchmark opens those four
persisted fixtures round-robin under an 8 GiB cap, assigns every mapping an
independent graph identity, and forces a zero-hit query to visit the complete
real graph list. A positive preflight query must also return the exact ordered
set of 36 graph identities. Source graphs are never retained together: each is
closed after persistence, before the next fixture is built.

The real-corpus suite treats target distribution as part of the fixture. Its
preflight pins per-corpus match counts, then benchmarks zero-hit, dense
four-corpus, first-graph-only, middle-graphs-only, last-graph-only,
four-corpus-distributed, first/last bimodal, and highly skewed class/method
cases. The queries vary caller/callee fields, class/method properties,
`CONTAINS`/`STARTS WITH`/`ENDS WITH`, and `LIMIT 1/50/250`.
Before timing, fixed, current-base, and candidate executors must produce the
same SHA-256 digest over complete columns, ordered rows, values, and graph
provenance for all eight distribution queries plus the 36-graph identity
coverage query. The comparator separately requires the exact eight synthetic,
eight four-fixture, and one 36-graph benchmark keys, so a variant cannot silently
disappear from all three revisions.

Each source JAR is built in its own JVM and private `java.io.tmpdir`. After the
source graph is persisted and that JVM exits, the raw mmap work directory is
deleted before the next corpus starts. Only the four final persisted graph
directories remain for the shared query measurements.
Every real-fixture row must remain at least 10x faster than the fixed baseline;
the lightweight synthetic scaling rows retain the 50% fixed-baseline floor.
No row may regress more than 15% against the current PR base; synthetic changes
below the 0.5 ms absolute noise floor remain informational. Missing graph-count
or query variants, incompatible units, invalid scores, and missing artifacts fail
closed. A suspected failure reruns candidate, base, and fixed baseline in
reverse order before it blocks. The fixed baseline prevents the original full-scan behavior from
becoming an accepted new base after a merge; the moving base comparison catches
new regressions in later optimizations.

## Wrapped-query resource gate

Resource probes run in separate SingleShot JMH classes, so their forced full-GC
fixtures never enter the latency score. The single synthetic graph is required
to run with exactly `-Xmx4g`; the 36-graph AllFixture probe runs with exactly
`-Xmx8g`. The gate checks both the fork argument and the effective maximum heap
reported from inside the fork.

Each resource result must contain finite `gc.alloc.rate.norm`, `gc.count`, and
`gc.time` profiler metrics plus loaded, peak, post-GC retained, retained-delta,
and query-only GC counters. JMH sums `AuxCounters(EVENTS)` scores, so the gate
reads and validates every per-invocation `rawData` sample for heap caps and
relationships, then compares their means. The profiler GC values remain
diagnostic because they include forced GC outside the query; regressions are
decided by the query-only counters. Missing metrics or raw samples, incompatible
units, duplicate results, a wrong heap cap, or impossible
`loaded <= peak <= max` / `retained <= peak` relationships fail closed.
Allocation, query GC, retained-delta, and peak regressions use a 15% relative
threshold plus an absolute noise floor and must repeat in a candidate-first
confirmation run before blocking.

## Method-level gate

The method-level job runs every `CypherBenchmark` method from both revisions with its normal JMH
warmup and measurement protocol. Lower latency is better. A row blocks the pull request only when:

1. candidate latency is more than 15% above the base latency; and
2. the two JMH 99.9% confidence intervals do not overlap; and
3. the same benchmark fails both the initial base-first run and a PR-first confirmation run.

The reverse-order confirmation only runs after a suspected regression. It prevents CPU frequency,
host contention, or execution order from turning a one-round process-level drift into a required
check failure. If JMH cannot produce finite confidence intervals, the 15% threshold is enforced
directly. Missing benchmarks, invalid scores, changed units, and execution errors fail closed.

## Real-corpus end-to-end gate

The end-to-end job uses the large-corpus harness introduced in PR #92. Tika, Hive, and the Kotlin
compiler each run in an isolated 4 GiB JVM through:

```text
JAR -> graph build -> save -> mapped load -> Cypher queries
```

The candidate runs before the base so it does not receive a systematic filesystem-cache advantage.
The semantic and fixture assertions still run in record mode; record mode only disables the
machine-specific absolute timing ceiling. Each corpus process is capped at a 4 GiB heap, so an OOM
or failure to finish still blocks the pull request.

The comparator requires exactly one result for each of Tika, Hive, and the Kotlin compiler. Node,
source-edge, persisted-edge, method, and call-site counts must match exactly between the PR base and
candidate. Persisted graph size may differ by at most 4 KiB to accommodate observed filesystem
serialization noise; larger size changes fail closed and require an explicit gate-contract update.

| Metric | Relative limit | Minimum absolute increase |
|---|---:|---:|
| Build | 20% | 500 ms |
| Save | 25% | 250 ms |
| Mapped load | 30% | 50 ms |
| Query | 25% | 250 ms |
| Full pipeline | 20% | 1,000 ms |
| Sampled peak heap | Report only | 4 GiB process cap |

Both the relative limit and the minimum increase must be exceeded to trigger a reverse-order
confirmation run. The same corpus and phase must exceed both limits again to block. This keeps a
single GC, CPU, or filesystem stall from becoming a required-check failure while preserving a gate
for repeatable phase regressions. Sampled peak heap is informational because a single high-water
sample varies with GC timing; the isolated 4 GiB process cap is the hard memory gate. Missing corpus
output or a benchmark process failure blocks the gate.

## Gradle caching

`gradle/actions/setup-gradle` caches the wrapper distribution, downloaded dependencies, compiled
build scripts, artifact transforms, and other reusable Gradle User Home state. The unit-test
workflow writes this cache on the default branch. Pull-request benchmark jobs use it read-only, so
they can reuse trusted main-branch state without creating a cache entry for every PR.

Generated graphs and project `build/` directories are deliberately excluded. The end-to-end gate
must measure graph construction and persistence rather than restore those outputs from a cache.

## Budgeted mapped-string latency gate

The `budgeted-mapped-string-latency` job protects the budget-aware transformed mapped-string scan
that regressed after the original latency fix. It compiles the identical
`MappedStringAdmissionBenchmark.budgetedTransformedZeroHit` harness at fixed commit
`87c74c2cae0685e40e32fb2eb46b33987ec1a7a0` and at the pull request candidate, then runs both on
the same runner. Each side uses three independent forks with five warmup and twenty measured
SingleShot invocations per fork. Any candidate score more than 15% slower is rerun in reverse order
even when the SingleShot confidence intervals overlap, and it blocks when the reverse-order score
is also more than 15% slower. This fixed baseline keeps a later change from normalizing a 2x
full-scan regression into the moving base.

## Local verification

Test the comparison and report generation logic:

```bash
node --test .github/scripts/benchmark-gate.test.mjs
actionlint .github/workflows/benchmark.yml
```

Run the two benchmark sources directly:

```bash
./gradlew :cypher:jmhJar --no-daemon
./gradlew :webgraph:largeCorpusTest -Dlarge.corpus.record=true --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='WrappedDiscoveryLatencyBenchmark.*' --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='AllFixtureWrappedDiscoveryLatencyBenchmark.*' --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='RealThirtySixGraphWrappedDiscoveryLatencyBenchmark.*' --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='.*WrappedDiscoveryResourceBenchmark.*' --no-daemon
```

The workflow deliberately keeps benchmark execution separate from unit-test coverage. Coverage
answers whether behavior was exercised; the benchmark gate answers whether the same behavior became
materially slower or more memory intensive.
