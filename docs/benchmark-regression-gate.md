# Benchmark regression gate

Every pull request runs `.github/workflows/benchmark.yml` as a required status check named
`benchmark-regression-gate`. It compares the pull request with its exact base commit on the same
GitHub-hosted runner. A committed score from a different machine is not used as the comparison
baseline.

The workflow updates one `Benchmark Regression Gate` comment on the pull request. The comment
contains the base and candidate SHAs, runner architecture, every measured score, delta, threshold,
and gate decision. Raw JMH JSON and large-corpus logs are retained as workflow artifacts for 14
days.

## Wrapped case-insensitive latency gate

The `wrapped-query-latency` job protects the production
`toLower(coalesce(...)) CONTAINS` discovery shape on persisted mapped graphs.
The same `WrappedDiscoveryLatencyBenchmark` source is compiled at three
revisions:

1. fixed pre-PR-95 commit `44b57562f2b3d0c88882a9002bdc488e05e5d7a7`;
2. the pull request's current base SHA; and
3. the pull request candidate SHA.

It measures warm and cold queries with `graphCount=1` and `graphCount=17` on
deterministic persisted graphs. It also builds every repository benchmark
fixture (Android, Tika, Hive, and Kotlin Compiler) sequentially, opens the four
persisted graphs together, and measures the same query over the heterogeneous
19,091,048-node graph set. Source graphs are never retained together: each is
closed after persistence, before the next fixture is built.

The real-corpus suite treats target distribution as part of the fixture. Its
preflight pins per-corpus match counts, then benchmarks zero-hit, dense
four-corpus, first-graph-only, middle-graphs-only, last-graph-only,
four-corpus-distributed, first/last bimodal, and highly skewed class/method
cases. The queries vary caller/callee fields, class/method properties,
`CONTAINS`/`STARTS WITH`/`ENDS WITH`, and `LIMIT 1/50/250`.
Before timing, fixed, current-base, and candidate executors must produce the
same SHA-256 digest over complete columns, ordered rows, values, and graph
provenance for all eight queries. The comparator separately requires the exact
four synthetic and eight real-fixture benchmark keys, so a variant cannot
silently disappear from all three revisions.

Each source JAR is built in its own JVM and private `java.io.tmpdir`. After the
source graph is persisted and that JVM exits, the raw mmap work directory is
deleted before the next corpus starts. Only the four final persisted graph
directories remain for the shared query measurements.
Every row must remain at least 50% faster than the fixed baseline and may not
regress more than 15% against the current PR base. Missing graph-count or query
variants, incompatible units, invalid scores, and missing artifacts fail
closed. A suspected failure reruns candidate, base, and fixed baseline in
reverse order before it blocks. The fixed baseline prevents the original full-scan behavior from
becoming an accepted new base after a merge; the moving base comparison catches
new regressions in later optimizations.

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
```

The workflow deliberately keeps benchmark execution separate from unit-test coverage. Coverage
answers whether behavior was exercised; the benchmark gate answers whether the same behavior became
materially slower or more memory intensive.
