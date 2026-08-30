# Benchmark regression gate

Every pull request runs `.github/workflows/benchmark.yml` as a required status check named
`benchmark-regression-gate`. It compares the pull request with its exact base commit on the same
GitHub-hosted runner. A committed score from a different machine is not used as the comparison
baseline.

The workflow updates one `Benchmark Regression Gate` comment on the pull request. The comment
contains the base and candidate SHAs, runner architecture, every measured score, delta, threshold,
and gate decision. Raw JMH JSON and large-corpus logs are retained as workflow artifacts for 14
days.

## Report coverage taxonomy

The aggregate comment separates a component's run result from its coverage scope. `PASS`/`FAIL`
comes only from the nine blocking component reports. Coverage labels follow the model introduced in
PR #104: ✅ means an implemented gate has no identified gate-specific gap, while ⚠️ means the gate
is implemented but intentionally incomplete. A passing component does not claim to cover its listed
gap.

The report groups detailed evidence under the product-performance domains `Semantic correctness`,
`Latency regression`, `Throughput and capacity`, `Memory and resources`, `Scalability`, and `Build
and persistence lifecycle`. Every blocking component belongs to exactly one domain. Benchmark
families not exercised by the suite are listed as non-blocking uncovered scope instead of being
mixed into the run verdict. A separate `Gate system` table keeps evidence reliability,
control-plane integrity, and coverage-policy limitations visible without presenting them as product
measurements.

## Main benchmark observatory

Every push to `main` (and an optional manual dispatch) runs
`.github/workflows/benchmark-pages.yml`. This post-merge workflow does not join the pull-request
gate. It builds the current commit's Cypher JMH JAR once, records the method-level benchmark set as
an informational absolute snapshot, and retains the raw JSON artifact for 90 days.

The report renderer also locates the successful paired benchmark artifact from the pull request
associated with the main commit. When a direct push has no associated artifact, the page says so
explicitly rather than manufacturing a gate verdict. It recovers the previously published embedded
history, replaces same-SHA manual reruns, and retains up to 90 snapshots or 180 days. Hosted-runner
cross-run deltas are informational; only the paired PR report supplies blocking regression
decisions.

The generated `index.html` is self-contained: responsive coverage cards, searchable evidence
tables, attention-row filtering, current/previous snapshot deltas, inline CSS/JavaScript, a strict
content-security policy, and no CDN or runtime package dependency. It is uploaded through the
official GitHub Pages artifact path and deployed by a separate `github-pages` environment job with
only `pages: write` and `id-token: write`. The live destination is
`https://johnsonlee.io/graphite/`.

## Release-tag benchmark diff

Every pushed release tag matching `v*` independently runs
`.github/workflows/benchmark-tag-diff.yml`. The resolver peels annotated tags, validates the exact
event commit, and selects the highest valid semantic-version tag below the current version. Current
and previous JMH JARs are built from their exact commits, then six representative
`CypherBenchmark` methods run sequentially on one hosted runner with a bounded one-fork protocol.

The resulting self-contained HTML artifact records both full commit SHAs, scores, confidence
bounds, signed release-to-release deltas, and the same PR #104 coverage taxonomy and known gaps used
by the pull-request and Pages reports. It is retained with its manifest, tag-resolution metadata,
and raw JMH JSON for 90 days. If no earlier semantic tag exists, the report preserves the current
measurements but explicitly marks the baseline and deltas unavailable.

This comparison is informational: it produces no release pass/fail verdict and has no dependency
edge to `.github/workflows/publish.yml`, so a benchmark or rendering failure cannot block artifact
publishing. Only latency for the representative method set is observed; correctness, throughput,
resource usage, scalability, and build/persistence lifecycle coverage remain explicitly unmeasured.

## Integrity model and limitations

Comparator commands, expected benchmark keys, workload harnesses, fixture-preparation harnesses,
shard combination, and final aggregation are loaded from the pull request's exact base SHA. This
keeps ordinary pull requests from accidentally changing the experiment and its pass criteria in the
same revision. Candidate comparator tests run as non-authoritative test coverage in a separate job
whose runner contains only the candidate checkout.

The known-good-anchor rollout has one compatibility exception for a pull request whose base
predates the anchor comparator command: its latency shards and latency-shard combiner may use the
candidate comparator only when the base comparator matches the pinned reviewed SHA-256 and
`candidate-gate-tests` passes. Any other pre-anchor base fails closed. The CODEOWNERS boundary must
review that bootstrap change for actors without ruleset bypass. Repository owners and holders of
bypass credentials are part of the trusted boundary. When the base exposes the anchor command, the
workflow always selects the base-owned comparator; the final nine-component aggregate remains
base-owned in either case.

This workflow is a regression signal for non-malicious changes, not a sandbox or a tamper-resistant
security boundary. Component jobs still execute candidate Gradle scripts and candidate benchmark
JARs on the same GitHub-hosted runner and as the same operating-system user as sibling base
checkouts, base measurements, and the base comparator. A deliberately hostile candidate process
can therefore overwrite those files or forge a component report/status artifact. The fresh
base-only aggregation job cannot recover integrity after a component artifact has been forged, so
the required check must not be treated as proof against a hostile pull request.

Benchmark jobs have only `contents: read`, every checkout disables credential persistence, and the
only job with `pull-requests: write` downloads the aggregate report and updates the PR comment
without checking out or executing candidate code. These controls protect repository credentials
and limit write authority; they do not isolate files or processes on a mixed benchmark runner.

`.github/CODEOWNERS` assigns the workflow, comparator, JMH workloads, and large-corpus gate harness
to the repository owner, and the active `main` ruleset requires code-owner review. Code-owner review
protects these gate files from changes by actors who cannot bypass that ruleset. It does not protect
against a repository owner exercising approval authority or a holder using bypass credentials; those
actors and credentials are within the repository-local trusted boundary. Fully defending against
hostile build or runtime code requires base and candidate execution on separate runners, raw artifact
comparison in a fresh base-only job, or an external required workflow/GitHub App. That isolation is
not provided by this workflow.

## Wrapped case-insensitive latency gate

The `wrapped-query-latency` job protects the production
`toLower(coalesce(...)) CONTAINS` discovery shape on persisted mapped graphs.
The same `WrappedDiscoveryLatencyBenchmark` source is compiled once at each of three revisions:

1. pinned known-good commit `0b421f8a25800193fd86a7e4aebf72aa9e9d6cc6`;
2. the pull request's current base SHA; and
3. the pull request candidate SHA.

The three validated JMH JARs are uploaded once and reused by all latency shards and the resource
gate. Explorer, Method compatibility, and Cypher capacity similarly reuse one base and one candidate
Explorer JMH build. This removes repeated Gradle compilation from consumer jobs while checksums and
JAR inspection fail closed on missing or corrupt build artifacts.

The 17 latency keys are split across nine parallel matrix shards: four for the
synthetic 1/4/16/64-graph scales, four for pairs of real-fixture query cases,
and one for the real 36-graph case. Within
each shard, known-good anchor, current base, and candidate still run sequentially
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
Before timing, known-good, current-base, and candidate executors must produce the
same SHA-256 digest over complete columns, ordered rows, values, and graph
provenance for all eight distribution queries plus the 36-graph identity
coverage query. The comparator separately requires the exact eight synthetic,
eight four-fixture, and one 36-graph benchmark keys, so a variant cannot silently
disappear from all three revisions.

Each source JAR is built in its own JVM and private `java.io.tmpdir`. After the
source graph is persisted and that JVM exits, the raw mmap work directory is
deleted before the next corpus starts. Only the four final persisted graph
directories remain for the shared query measurements. The 36-real-graph setup validates the
positive exact-identity coverage query but no longer executes a redundant untimed zero-hit scan;
JMH warmup and measurement iterations remain unchanged.

No row may regress more than 50% against the pinned known-good anchor or more than 15% against the
current PR base. Synthetic changes below the 0.5 ms absolute noise floor remain informational.
Missing graph-count or query variants, incompatible units, invalid scores, and missing artifacts
fail closed. A suspected failure reruns candidate, base, and known-good anchor in reverse order
before it blocks. The anchor prevents a gradual regression from being normalized across moving
bases, while the current-base comparison remains sensitive to a new PR-local slowdown.

The expensive proof against the known-bad pre-PR-95 commit
`44b57562f2b3d0c88882a9002bdc488e05e5d7a7` runs in
`.github/workflows/benchmark-historical-latency.yml` on a daily schedule and on manual dispatch.
That workflow preserves the previous exact correctness digest and retained-speedup contract across
all nine shards, including the 36-real-graph scan, but does not extend pull-request critical-path
latency.

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

## Method compatibility gate

Method discovery covers the exact 11-scenario matrix at 4, 17, and 36 graphs: 33
semantic/performance cases. The workflow partitions each graph count into four scenario groups
(`position`, `string`, `scan`, and `aggregate`) for 12 independently scheduled shards. Each shard
runs its assigned scenarios for base and candidate from the shared Explorer JMH artifacts and emits
both JMH metrics and canonical result records.

The aggregator requires all 12 artifacts, the exact 33 unique `(graphCount, scenario)` pairs, and
identical result records. Wall time, process CPU, and post-run RSS are blocking 15% comparisons;
RSS delta remains advisory. Sharding changes scheduling only—the scenario manifest and final
fail-closed contract are unchanged.

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
they can reuse base-branch state without creating a cache entry for every PR.

Generated graphs and project `build/` directories are deliberately excluded. The end-to-end gate
must measure graph construction and persistence rather than restore those outputs from a cache.
Within one workflow run, dedicated build jobs publish checksum-protected JMH JAR artifacts so the
consumer matrix does not rebuild identical revisions. These short-lived artifacts are a run-local
fan-out mechanism, not a cross-run build cache.

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
node --test .github/scripts/benchmark-*.test.mjs
actionlint .github/workflows/benchmark.yml \
  .github/workflows/benchmark-historical-latency.yml \
  .github/workflows/benchmark-pages.yml \
  .github/workflows/benchmark-tag-diff.yml
```

`concurrency.queue: max` is supported by GitHub Actions but may require a current `actionlint`
release; older schemas can report that key as unknown even when the remaining workflow validates.

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
