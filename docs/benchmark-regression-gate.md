# Benchmark regression gate

Every pull request runs `.github/workflows/benchmark.yml` as a required status check named
`benchmark-regression-gate`. It compares the pull request with its exact base commit on the same
GitHub-hosted runner. A committed score from a different machine is not used as the comparison
baseline.

The workflow updates one `Benchmark Regression Gate` comment on the pull request. The comment
contains the base and candidate SHAs, runner architecture, every measured score, delta, threshold,
and gate decision. Raw JMH JSON and large-corpus logs are retained as workflow artifacts for 14
days.

## Current wide-query acceptance policy

The required gate focuses on query diversity, correct results, and warmed latency under an
**8 GiB maximum heap**. Process CPU, peak used heap, and peak RSS remain recorded diagnostics;
their percentage increases do not reject a change. Method CPU/RSS and capacity CPU/RSS are
advisory too; wrapped retained/peak heap increases are advisory. Actual heap caps, invalid
measurements, correctness, cancellation, and capacity behavior remain checked. Allocation/GC
checks and Explorer's existing memory-stability assertions are separate, unchanged checks.

The reviewed [query catalog](../.github/scripts/wide-query-catalog.json) has **72 cases**: the
original 34 plus 38 multi-keyword cases. It includes AND, OR, mixed Boolean trees, four-term OR,
zero hits, single-graph hits at early/middle/late positions, multiple-graph hits, and ordinary and
DISTINCT projections. The corpus consists of 64 persisted shards of four real source corpora;
it does not represent 64 independent applications or concurrent client requests.

Each `wide-query-latency-<query-id>` CI check independently accepts or rejects one query. All
checks consume the same paired experiment artifact; they do not rerun the workload 72 times.
Within each JVM, **each query** runs contiguously: warmup continues until both **10 seconds
and five calls** have completed, then measurement continues until both **10 seconds and 40
calls** have completed. The JVM clears retained indexes once before warmup and preserves warmed
indexes and caches throughout the experiment. These are minimums: every measured call is
retained, so fast queries can produce more than 40 samples. Warmup observations are retained
for correctness and protocol validation but excluded from latency quantiles. Each raw TSV row
records its phase, round, and cumulative phase elapsed nanoseconds. Each completed invocation
flushes its full row before result verification and the next call, so cancellation during a slow
following call retains prior samples, including a mismatching result. Flush is outside the query
latency timer, but contributes to subsequent phase wall time; it is not fsync and does not promise
durability after host storage failure.

There are three independent base/candidate JVM pairs in candidate/base, base/candidate,
candidate/base order. All six JVMs for any one query run sequentially on the same host. The
effective JVM maximum heap is checked in addition to passing `-Xmx8g`. This is workload pressure
over all 64 sources, with queries submitted sequentially; it is not a multi-client throughput test.

`build-wide-latency-bundle` builds both exact production revisions with the same reviewed
harness, records the full 72-query oracle from base, and binds their artifacts to the verified
shared fixtures. Two fixed measurement shards consume this bundle: `standard` contains 71
queries and `full-scan` contains only `mixed-four-few-distinct`. This separates the long scan
without changing its query or reducing its samples. `global-wide-pressure-evidence` verifies
artifact hashes, revisions, runner identities, phase durations, and the complete disjoint shard
union before producing the 72 verdicts. Missing or malformed shards fail the gate.

For **each query**, compute nearest-rank P50 and P95 from **all** of its measured calls in each
fork. The report includes actual warmup and measurement counts and durations:

- Each paired candidate P50 and P95 must be **less than 105%** of the matching main value.
- For each quantile and each revision, cross-fork fluctuation is `(maximum - minimum) / minimum`
  across the three forks. This is **diagnostic only**, with no pass/fail threshold. Even large
  same-revision variation does not itself fail acceptance. The 5% hard limit applies only to
  paired candidate/base P50 and P95 increases; exactly 5% fails. No absolute-latency allowance
  hides a paired exceedance. A measured exceedance blocks acceptance but does not by itself
  establish that the candidate code caused it; order, JIT and environment effects need diagnosis.
  No uncalibrated confidence-interval or spread threshold is substituted for this policy.
- Every warmup and measured result must match the base-generated full 14-field correctness
  oracle, including the canonical result digest that binds field values, row order, and graph
  provenance. Missing queries/samples, timeouts, exceptions, duplicate samples, and altered query
  identities fail closed. A query's result error cannot be offset by another query's speedup.

The mixed-query percentile over the original 34 cases is diagnostic only for this warmed
acceptance policy. Cold-start latency, the historical 10x target, and strict improvement over the
last accepted iteration are not current PR acceptance criteria. Only the current PR main base
runs the expanded repeated experiment. A separate legacy-diagnostics job retains original
34-query comparisons against current main and historical references; their numerical latency
results are advisory, while correctness and evidence-integrity failures remain blocking. Raw sample TSVs, per-query
verdicts, all paired quantiles, and fluctuation calculations are retained in the CI artifact.

Other existing benchmark families do not all share this warmup protocol: some use normal JMH
warmup/measurement iterations, while startup/session and cold-replay probes use SingleShot runs.
Those measurements must not be described as this timed per-query P50/P95 evidence. The nine-key
wrapped-query latency harness retains its separate five warmup / 40 measured invocations / three
fork protocol; the historical wrapped comparison retains one warmup / three measurements / one
fork. Neither protocol changes with this 72-query rollout.

The timed protocol addresses insufficient per-query JIT warmup exposed by earlier CI runs that
interleaved different queries with fixed invocation counts. Defining this protocol does not establish
that all 72 queries pass; acceptance still requires their actual complete CI measurements.

## Failure handling and fixture preparation

Server lifecycle and CPU accounting contracts run before the expensive benchmark jobs. The
64-graph fixture also waits for the real CPU-accounting smoke to succeed. Expensive matrices
cancel their sibling jobs on failure. Isolated monitors cancel independent work after a blocking
job finishes with a failure, including its diagnostic uploads; they inspect only the current run
attempt. The monitors do not check out or execute benchmark code with their cancellation token. Aggregate and reporting jobs use `!cancelled()` so ordinary failures still produce diagnostics, while workflow cancellation stops further artifact downloads and processing; step-level diagnostic uploads retain `always()`. They run only for
same-repository pull requests, where GitHub grants the cancellation token. Fork pull requests
retain the prerequisite barriers, matrix cancellation, and per-pair fail-fast checks; cancellation
of unrelated jobs is unavailable with the fork's read-only token. Only this explicit fork policy
allows both monitors to be skipped. Failed or cancelled monitors never satisfy acceptance.
The early monitor hands off only when the late monitor is running. Hosted jobs have a 360-minute
limit; the late monitor fails visibly at 359 minutes if work has not finished, rather than silently
leaving the remaining run unmonitored.

Each timed measurement shard validates the completed base/candidate pair before starting the
next pair. Runtime errors stop the current invocation immediately; checkpoint integrity errors
stop before the next pair. First-pair numerical exceedances are retained, but the reverse-order
second pair must complete before a terminal latency decision. After that pair, any cumulative
paired P50/P95 increase of at least 5% stops the shard. Cross-fork spread never stops a shard. A passing second
pair cannot erase a first-pair failure. All partial raw samples and checkpoint reasons remain
available; partial evidence can never pass the final gate. Successful acceptance still requires
all three pairs for all 72 queries.

The fixture cache stores one prepared 64-graph corpus and its completed reproducibility receipt,
bound to the exact generator, verifier and pinned input JAR cache key. On an exact cache hit,
the workflow checks that binding, relocates only path columns, and verifies the actual graph
contents against the original JARs. It does not repeat the independent second build and tamper
self-tests. On a miss, both independent builds and the full self-tests must pass before a new
receipt can be cached. On a miss, `prepare-fixture64-inputs` uploads one generator JAR and
four pinned fixture JARs. Two independent `generate-fixture64` jobs (`primary` and `repeat`)
build one 64-graph corpus each, with at most two jobs running concurrently. Both consume the
same SHA-verified inputs and exact resolved Temurin version; actual `java.runtime.version`
is also checked before generation and verification. The resolved JDK version and sealing
helper are included in the existing v2 cache key alongside every original dependency.

The final `prepare-fixture64` job downloads both corpora and performs the unchanged content,
reproducibility and tamper checks before writing the shared receipt and cache. Missing or
failed producers cannot trigger a replacement build in the aggregate job. On a cache hit,
the inputs job verifies and publishes the existing corpus; both generators are skipped and
the final job confirms that successful publication without downloading/uploading it again.
Downstream builders, measurements and the late watchdog use explicit `!cancelled()` conditions
and require their direct prerequisites to succeed. GitHub's default success condition would
otherwise propagate the intentionally skipped generator through the dependency chain, even
after `prepare-fixture64` succeeds. Failed prerequisites and workflow cancellation still prevent
those jobs from starting; only the expected cache-hit generator skip is bypassed.
Intermediate artifact names bind the workflow run and attempt. Downstream artifact names and
measurement scheduling are unchanged: base/candidate queries still run serially in all three
pairs on the same machine, under the existing 8GiB measurement cap. Fixture generation retains
its separate 4GiB cap.

This removes the second graph generation from the serial preparation path; it does not remove
validation or reduce total graph generation work. Uploading common inputs, transferring both
corpora, and scheduling additional runners add overhead. Net wall-clock savings require CI
measurement and are not assumed from the previous roughly six-minute repeat-build duration.

## Report coverage taxonomy

The aggregate comment separates a component's run result from its coverage scope. `PASS`/`FAIL`
comes only from the blocking component reports. Coverage labels follow the model introduced in
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
measurements but explicitly marks the baseline and deltas unavailable. The resolver fingerprints
the shared `CypherBenchmark.kt` fixture/setup, all six selected method bodies, and the fixed
execution protocol, together with the effective JMH runtime, Gradle plugin, dependencies, and
module JMH options. Unrelated benchmark additions and non-JMH Gradle tasks are ignored. When that
fingerprint changes between tags, both raw score sets remain visible but signed deltas are disabled
as incomparable workload drift.

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
workflow always selects the base-owned comparator; the final aggregate remains
base-owned in either case.

The five-sample large-corpus rollout has an equally bounded one-time transition. If the exact base
harness and comparator do not expose the new sample protocol, the workflow requires the reviewed
legacy harness SHA-256, the reviewed candidate harness and comparator SHA-256 values, and a passing
candidate comparator test job. It then installs that pinned harness into both revisions and uses the
pinned comparator. Any other pre-protocol base fails closed. Once `main` contains the protocol, the
workflow automatically returns to base-owned controls and never selects candidate controls.

The coverage-taxonomy rollout changes presentation only. The base-owned aggregator always writes
the authoritative verdict and is the only status enforced by the required check. If that exact base
does not yet render the coverage summary, a candidate renderer is allowed only when its reviewed
SHA-256 matches and candidate gate tests pass. The workflow enriches the base status with the exact
base SHA, candidate SHA, runner, and run URL, then requires the rendered status to match its verdict,
errors, and provenance. The required check reads only that authoritative status; the published
status remains paired byte-for-byte with the rendered report so Pages can validate the evidence.
The artifact retains both reports and statuses for audit. Once the base renderer contains the
taxonomy, the transition path is skipped.

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
The same real-fixture `AllFixtureWrappedDiscoveryLatencyBenchmark` sources are compiled once at each
of three revisions:

1. pinned known-good commit `0b421f8a25800193fd86a7e4aebf72aa9e9d6cc6`;
2. the pull request's current base SHA; and
3. the pull request candidate SHA.

The three validated JMH JARs are uploaded once and reused by all latency shards and the resource
gate. Explorer, Method compatibility, and Cypher capacity similarly reuse one base and one candidate
Explorer JMH build. This removes repeated Gradle compilation from consumer jobs while checksums and
JAR inspection fail closed on missing or corrupt build artifacts.

Every benchmark JMH fat JAR explicitly disables the plugin's default test-output inclusion. Every
comparable build runs a packaging invariant that compiles the project test output, intersects all of
its relative entries with the finished archive, and fails if any test class or resource leaked into
the JMH artifact. This keeps unrelated tests from perturbing latency/resource forks while leaving
candidate production classes intact for the actual regression comparison.

The rollout also covers comparison revisions that predate this Gradle configuration. During that
one-time transition, the pull-request workflow applies a reviewed init script and verifier from the
candidate checkout only after both files match their pinned SHA-256 values. Once the exact base SHA
contains those controls, all comparable revisions automatically use the base-owned copies. The
scheduled historical workflow always uses the controls from the current default-branch checkout.

The nine latency keys are split across five parallel matrix shards: four for pairs of real-fixture
query cases and one for the real 36-graph case. Within
each shard, known-good anchor, current base, and candidate still run sequentially
on the same runner, so parallelism does not turn cross-runner variance into a
performance comparison. A prerequisite job restores or builds the persisted
fixture graphs once with a 4 GiB heap, using a content-addressed cache key over
the graph-building/serialization sources, fixture harness, dependency catalog,
and Gradle build files. Real shards restore that immutable cache instead of
rebuilding 19 million nodes independently. The final `wrapped-query-latency`
job fails closed unless all five shard reports arrive and their union contains
every expected key exactly once.

It builds every repository benchmark fixture (Android, Tika, Hive, and Kotlin Compiler)
sequentially on a cache miss,
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
coverage query. The comparator separately requires the exact eight four-fixture
and one 36-graph benchmark keys, so a variant cannot silently
disappear from all three revisions.

Each source JAR is built in its own JVM and private `java.io.tmpdir`. After the
source graph is persisted and that JVM exits, the raw mmap work directory is
deleted before the next corpus starts. Only the four final persisted graph
directories remain for the shared query measurements. The 36-real-graph setup validates the
positive exact-identity coverage query but no longer executes a redundant untimed zero-hit scan;
Each query runs five warmup iterations followed by 40 measured iterations in each of
three independent JVM forks, with an 8 GiB maximum heap. Warmup builds retained
indexes; measured invocations preserve them. The reference, current base, and
candidate use the same harness. When the base still carries the known legacy
cold-index harness, the workflow installs the reviewed warm harness into all
three revisions after checking both source hashes and the gate-tool tests.
An already updated base remains authoritative; an unknown cold harness fails closed.

No row may regress more than 50% against the pinned known-good anchor or more than 15% against the
current PR base. Synthetic graphs are excluded from all performance comparisons. Missing query
variants, incompatible units, invalid scores, and missing artifacts fail closed. A suspected failure
reruns candidate, base, and known-good anchor in reverse order before it blocks. The anchor prevents
a gradual regression from being normalized across moving bases, while the current-base comparison
remains sensitive to a new PR-local slowdown.

The expensive proof against the known-bad pre-PR-95 commit
`44b57562f2b3d0c88882a9002bdc488e05e5d7a7` runs in
`.github/workflows/benchmark-historical-latency.yml` on a daily schedule and on manual dispatch.
That historical comparison explicitly keeps its existing one warmup, three
measurements and one fork for both revisions in both execution orders. The known-bad
36-graph query takes roughly four minutes per invocation; applying the PR sample
budget would exceed the runner job limit. It uses retained warm indexes but is a
bounded historical check, not evidence for the PR’s per-query P50/P95 gates.
That workflow preserves the previous exact correctness digest and retained-speedup contract across
all five real-graph shards, including the 36-real-graph scan, but does not extend pull-request critical-path
latency.

## Wrapped-query resource gate

Resource probes run in a separate SingleShot JMH class, so their forced full-GC
fixtures never enter the latency score. Only the real persisted 36-graph AllFixture probe is used,
with exactly `-Xmx8g`. The gate checks both the fork argument and the effective maximum heap reported
from inside the fork.

Each resource result must contain finite `gc.alloc.rate.norm`, `gc.count`, and
`gc.time` profiler metrics plus loaded, peak, post-GC retained, retained-delta,
and query-only GC counters. JMH sums `AuxCounters(EVENTS)` scores, so the gate
reads and validates every per-invocation `rawData` sample for heap caps and
relationships, then compares their means. The profiler GC values remain
diagnostic because they include forced GC outside the query; regressions are
decided by the query-only counters. Missing metrics or raw samples, incompatible
units, duplicate results, a wrong heap cap, or impossible
`loaded <= peak <= max` / `retained <= peak` relationships fail closed.
Allocation and query GC regressions use a 15% relative threshold plus an absolute noise floor
and must repeat in a candidate-first confirmation run before blocking. Retained-delta and peak
heap growth are advisory; missing or invalid measurements and the effective 8 GiB cap still fail.

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

The measured Method server disables Javalin's startup watcher. Javalin otherwise creates an
unnamed thread which sleeps for five seconds and exits even after successful startup; a long
query can span that exit, invalidating the strict Java-thread CPU snapshots. This removes a
startup diagnostic thread at its source without excluding any request worker from accounting.
The fixture-free `MethodBenchmarkServerLifecycleContract` uses the same server factory and
measures through the watcher's lifetime; the existing transient-worker and vanished-thread
negative CPU contracts remain unchanged.

While main still contains the known legacy Explorer harness, CI verifies its SHA and installs
the reviewed, SHA-pinned fixed Explorer harness into both production revisions after candidate
gate tests pass. CPU accounting and capacity harnesses remain base-owned. The transition and
its failure paths are exercised by running the actual installation shell in contract tests.

Method discovery covers the exact 11-scenario matrix at 4, 17, and 36 graphs: 33
semantic/performance cases. The workflow partitions each graph count into four scenario groups
(`position`, `string`, `scan`, and `aggregate`) for 12 independently scheduled shards. Each shard
runs its assigned scenarios for base and candidate from the shared Explorer JMH artifacts and emits
both JMH metrics and canonical result records.

The aggregator requires all 12 artifacts, the exact 33 unique `(graphCount, scenario)` pairs, and
identical result records. Wall time remains a blocking 15% comparison; process CPU, post-run RSS, and RSS delta are
advisory. Their measurements and validity checks remain required. Sharding changes scheduling only—the scenario manifest and final
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
| Mapped load (median of 5) | 30% | 50 ms |
| Query | 25% | 250 ms |
| Full pipeline | 20% | 1,000 ms |
| Sampled peak heap | Report only | 4 GiB process cap |

Both the relative limit and the minimum increase must be exceeded to trigger a reverse-order
confirmation run. The same corpus and phase must exceed both limits again to block. This keeps a
single GC, CPU, or filesystem stall from becoming a required-check failure while preserving a gate
for repeatable phase regressions. Sampled peak heap is informational because a single high-water
sample varies with GC timing; the isolated 4 GiB process cap is the hard memory gate. Missing corpus
output or a benchmark process failure blocks the gate.

Mapped loading is measured five times against the same persisted graph and compared by median. The
marker also records the sample minimum, maximum, and count; the comparator fails closed unless both
revisions use the exact five-sample protocol and the median lies inside a valid positive range. This
removes single filesystem/mmap startup outliers without weakening the original 30% plus 50 ms gate.

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
./gradlew :webgraph:jmh -Pjmh.filter='AllFixtureWrappedDiscoveryLatencyBenchmark.*' --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='RealThirtySixGraphWrappedDiscoveryLatencyBenchmark.*' --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='.*WrappedDiscoveryResourceBenchmark.*' --no-daemon
```

The workflow deliberately keeps benchmark execution separate from unit-test coverage. Coverage
answers whether behavior was exercised; the benchmark gate answers whether the same behavior became
materially slower or more memory intensive.


## Cold diagnostics prerequisite

The original 34-query global replay measures cold queries. CI uses the iteration driver's
`--legacy-diagnostics-only` mode: historical speedup targets, aligned cold latency
regressions, and CPU/peak-heap/RSS growth remain reported but do not block this prerequisite.
Errors are classified where they are detected; correctness, complete paired evidence, graph
coverage, worker accounting, and the effective 8 GiB heap requirement still fail the gate.
Direct driver invocations retain the strict default. Diagnostic mode cannot publish the legacy
strict-target external success status. Failed comparisons retain their provenance and observations.

Graph-routing `cold` and `startup-prepared` numerical latency is likewise advisory.
`startup-prepared` prepares indexes at load time but performs no query warmup; it does not measure
steady-state query latency. Only `warm` numerical latency remains blocking under its existing limits. Every state's result and measurement-integrity checks remain
required. This prerequisite does not implement or weaken the separate 72-query warmed P50/P95
protocol, whose paired regression limit remains strictly below 5%; cross-fork variation is diagnostic only.


The resource-growth policy applies consistently to Method initial comparisons and confirmation
selection, capacity CPU/RSS comparisons, and wrapped retained/peak heap comparisons. Method
wall latency and capacity tail latency retain their existing thresholds. Wrapped allocation/GC
and Explorer memory-stability checks are unchanged. CPU accounting remains strict: withdrawing
a CPU growth threshold does not authorize missing, negative, or invalid CPU measurements.
The wrapped resource job uses the reviewed SHA-pinned candidate comparator for both initial
and reverse-order confirmation comparisons; the paired execution harness remains base-owned.


This state classification follows `setupInvocation()`: `warm` performs one untimed replay,
whereas `startup-prepared` performs none. It does not establish that the legacy warm protocol
(one replay and one measured sample per query, with mixed-query percentiles) meets the separate
per-query timed warmup and repeated P50/P95 protocol. That protocol remains independent.

PR publication uses a bounded, exact-head summary linked to the unchanged full report artifacts.
After fail-fast cancellation, only the three-minute publication job may continue: its cancellation
branch reads GitHub job and artifact metadata, publishes an explicitly incomplete cancellation
summary, and fails visibly. It performs no checkout, artifact download, aggregation or benchmark
execution. Heavy jobs remain cancellation-aware. Superseded heads and older attempts cannot replace
newer evidence. Force cancellation or runner failure can prevent even this cleanup; a same-run
comment is not guaranteed in those cases, and the workflow/check state remains the authority.


## Slow-query family gate inherited from main

The separate `slow-query-shapes` job covers 12 hit/miss queries in COLD and WARM states on
real persisted Android graphs. It retains three single-shot forks and the existing 15% mean
latency threshold with a reverse-order confirmation for WARM. Its one priming invocation is
not the 72-query timed warmup or P50/P95 protocol. COLD latency is diagnostic only under the
current CI policy and does not trigger numerical confirmation. Every COLD and WARM result,
private fixture, sample set and ordered digest remains mandatory and authenticated.

The new component remains required by aggregation and by the late failure monitor. CI selects
SHA-pinned candidate controls when the base has the known pre-diagnostic controls manifest;
other supported base controls retain ownership. Standalone invocations keep their original
strict default unless `--cold-diagnostics-only` is explicitly selected. This transition changes
acceptance and reporting, not the inherited harness, production implementation or measurements.
