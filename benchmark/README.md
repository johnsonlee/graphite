# Graphite benchmark controller

English · [简体中文](README.zh.md)

`graphite-benchmark` is a development-only Cargo workspace binary. It runs the same
base/head workload on one machine and emits one result model as JSON, Markdown and HTML.
Correctness, complete coverage and OOM failures block the PR. Timing differences never do.
There are no CLI/environment overrides for fixture identities, heap ceilings, sample counts,
concurrency or acceptance rules; change the reviewed Rust/JMH source to change the protocol.

## Pipeline

`run` owns the complete pipeline; the workflow does not assemble phase inputs or pass source
SHAs between commands. The controller reads both committed identities before installing the
trusted harness. Its internal phases are:

1. Preparation builds the product merge-base and exact PR head with one trusted frontend harness.
   Gradle resolves the four pinned real JARs; the controller verifies their SHA256 and sizes.
   Compiler/build-tool time is preparation overhead, not graph construction performance.
2. JVM execution runs `Fixture64BuildBenchmark` through real JMH: one fork, zero warmups, one
   single-shot invocation per revision. The invocation partitions the four JARs into 16
   shards each, builds and saves 64 distinct graphs, and retains the original resource,
   semantic, persisted-index and readback assertions. It never restores a saved-graph cache.
   Separate JMH forks also build and save the complete Tika, Hive and Kotlin compiler JARs,
   preserving the historical node/edge/method/call-site counts and mapped readback assertions.
   These whole-JAR cases gate correctness and OOM only; their single-graph durations are not
   performance evidence and do not appear in the comparison table.
3. Native execution starts each revision's production Rust server with that revision's saved graphs.
   It checks identical source/shard identities, completely consumes and checks responses,
   then runs the fixed real64 pressure plan. Queries and complete results must match unless
   an exact semantic transition was approved in the trusted controller beforehand. JVM query benchmarks are not used
   as a proxy for the Rust server. A separate real64 server checks four concurrent constant
   queries, complete results, metrics counters, invalid inputs and exact recovery results on
   both revisions. Required head-revision Rust route tests check rejection, timeout caps and
   permit release deterministically. These correctness checks never contribute timing samples.
4. Reporting requires exactly one frontend and one native result for the same base/head pair.
   Missing, cancelled or failed work is not PASS. Timing rows are always `REPORT_ONLY`.

The JMH launcher and forks explicitly use G1 and 4 GiB for JAR fixtures. The Java agent checks actual
`MemoryMXBean.getHeapMemoryUsage().getMax()`, not just command-line text. The fixed APK policy is 8 GiB; the current
real64 workload consists of Android-all, Tika, Hive and Kotlin compiler **JARs**, not APKs.
APK fixture publication and APK workload integration are not part of this change.
Linux cgroup OOM counters and process exit status supplement the JVM heap checks. A host OOM
is a failed run, not evidence that the configured Java heap was exceeded. There is no retry
with a larger heap. `-XX:+ExitOnOutOfMemoryError` deliberately makes the benchmark stricter
than production's SootUp recovery handlers: an OOM that production could catch and skip still
terminates the benchmark and fails the gate. Fixing G1 makes the effective heap check
consistent across the launcher and forks.

## Running

Use three dedicated checkouts: product base, product head and trusted controller/harness.
`run` overlays the trusted JMH sources and shared whole-JAR assertions into both product
checkouts. The product implementation remains revision-specific. Source commit/tree and product content identities,
trusted harness revision and installed file hashes are recorded in `<output>/prepared/preparation.json`.
Build the CLI from the trusted checkout; run preparation in its parent so outputs never
modify that trusted tree.

Each product checkout must have its own physical `target` directory. Preparation sets
`CARGO_TARGET_DIR` to that checkout's `target`, overriding an inherited value. Existing
private target caches are allowed; shared or symlinked target roots (including aliases
through parent directories) are rejected before any preparation commands or writes.
Cargo fingerprint reuse across revisions can silently copy the baseline executable as
the candidate. Share downloaded dependencies if needed, never the target directory.
Resolved target paths are recorded as `nativeTargetDirectories` in preparation evidence.

```sh
cd /work
cargo build --release --locked --manifest-path trusted/Cargo.toml -p graphite-benchmark
/work/trusted/target/release/graphite-benchmark run \
  --base-source /work/base --head-source /work/head --harness-source /work/trusted \
  --output /work/benchmark-run

# Re-render the same retained evidence, including after an interrupted run.
/work/trusted/target/release/graphite-benchmark report --run /work/benchmark-run
```

The hosted acceptance environment is Linux with cgroup v2, JDK 17 and Rust. Diagnostic local
runs on other platforms do not provide Linux host-OOM evidence. Outputs are retained on failure;
use a fresh output directory for another run. It contains `prepared/`, `results/`, and
`report/`; `execution.json` records completion and orchestration errors. Preparation failure,
missing completion evidence or a failed suite makes the final report fail. The workflow
always re-renders retained evidence in a separate step, so a killed controller cannot
accidentally produce a passing report. No signing key, service or separate
benchmark machine is required.

## Timing protocol

The current main-branch catalog contains 73 multi-graph cases. Each revision uses a fresh server,
one complete oracle pass, two warmups per case and twenty measured requests per case at four
workers. Requests are interleaved by round. Cases whose pressure p50 is below 100 ms receive
forty additional isolated requests; this is a fixed sampling rule, not a pass threshold.
Isolated and concurrent results remain separate. If only one revision qualifies for isolated
sampling, the report does not invent a paired delta.

Report each case's nearest-rank p50/p95 separately. Do not pool different query cases. Latency
ends after the complete HTTP body is read; response validation, hashing and artifact writes
are retained as client-cycle diagnostics outside that latency. Report registry-ready time and
startup through the first complete oracle pass separately so deferred initialization stays visible.
The JMH construction result includes its complete usable-save/readback boundary.

Every comparison shows base/head milliseconds, absolute difference, percentage, method and
sample count. A zero baseline has no defined percentage change. Missing or incomparable values
are `UNAVAILABLE`, never zero. Hosted-runner timings are observations affected by environment
noise; they do not establish performance regression acceptance or recovery.

## Local saved-graph query diagnostics

`local-query` is a separate development command for an existing trusted real64
manifest. It does not rebuild frontend graphs or change `run`, the required
73-case gate, or its policy. Start with `--mode smoke`: it executes one complete
first-use request per case at four workers and stops, with no warmup or pressure.
Failures stop the stage, retain every issued request/partial body, and leave
unissued work explicitly incomplete; they never become passing timing samples.

```sh
graphite-benchmark local-query --base /work/base/graphite \
  --manifest /work/graphs/graphs.tsv --output /work/discovery --mode smoke
graphite-benchmark local-query --base /work/base/graphite \
  --candidate /work/candidate/graphite --manifest /work/graphs/graphs.tsv \
  --output /work/paired-pilot --mode pilot --order ab
```

The default `--workload complex` uses the six fixed entries in
`complex_aggregation_queries.json`. Pilot has two warmups and twenty pressure
requests per case; measure has two warmups and one hundred pressure requests.
Both include the first-use pass. `--order ba` reverses the two fresh processes;
repeat into a new output directory to retain independent pairs. There is no
arbitrary query/sample-count override. `--workload regression` reuses the unchanged
73-case catalog, with twenty pressure samples in both pilot and measure modes
and forty isolated samples at concurrency one when pressure p50 is below 100 ms.
This local regression command is not the complete trusted CI pipeline: frontend,
capacity/recovery and dedicated scope probes remain in `run` and ordinary tests.
The local command fixes server timeout at 60 seconds and client timeout at
70 seconds, independently of the existing CI timeout. Every request targets all
64 registered graphs and consumes the complete response. Workers validate and
write artifacts before taking the next task; this is not a saturation test.

`--workload complex-wide-distinct` (`Workload::ComplexWideDistinct`) is a separate
fixed six-case workload. Five cases are unchanged; co-use becomes
`bi-dependency-co-use-wide-distinct`, replacing only
`count(DISTINCT graph) AS graphs` with `count(DISTINCT caller) AS callers`.
This exercises a wider distinct-value dictionary on the same real64 inputs;
it does **not** measure 65 or 130 physical graphs. Retain a complete response with
`callers > 64` (and, when observed, `> 512`), or an independent untimed dictionary
cardinality check, as evidence that the changed path was exercised. A workload
name alone does not prove dictionary width. Modes, sample counts, four workers,
and fixed 60/70-second timeouts are the same as `complex`. Do not combine samples
or compare latencies across the two workloads. Preserve a failed baseline and
mark its percentiles/resources unavailable for paired comparison; a completed
candidate-only run does not create a baseline.

Input manifest/source provenance checks are shared with `run`. All saved files
are hashed once before execution, paths/sizes/modification times are checked
after each arm, and binary/catalog hashes are retained. The same saved inputs
serve both binaries; a storage-format-changing candidate requires a different
reviewed protocol. Hashing warms filesystem caches: first-use means a fresh
server's first query sequence, **not disk-cold inputs or independent cold requests**.
Use a release-built controller for measurements and avoid concurrent compilation.

On macOS, `proc_pid_rusage` supplies server user/system CPU and instantaneous RSS;
Linux uses `/proc`. Darwin CPU raw Mach ticks are converted to seconds with
`mach_timebase_info`; raw ticks and the conversion ratio are retained. `wait4` supplies exact child lifetime CPU and kernel peak RSS
(Darwin bytes, Linux KiB converted to bytes). Pilot/measure run a separate
load-only server to obtain a loading-only kernel peak. The query process's peak
includes its own loading, first use, warmup and pressure (and regression isolated
samples); it is **not** a resettable pressure-window peak. Never subtract the
loading peak from this lifetime peak. Pressure CPU is the difference of server
CPU snapshots around the complete mixed stage; client/controller CPU is excluded.
Resources cover the fixed mix, not individual queries. Failure before a snapshot
leaves it unavailable, not zero.

`report.json` always has `performanceAcceptance: false`. Complete repeated typed
responses and matched arm oracles establish comparability, not an independent
semantic oracle or production representativeness. The command records per-case
nearest-rank p50/p95 and every failure; it cannot approve the CPU/RSS 5% bounds,
correctness/stability or production P95 recovery by itself. Smoke's one first-use
observation is not a percentile distribution. No synthetic or single-graph
performance execution is supported.

## GitHub integration

`benchmark.yml` builds/tests the controller from the PR target branch's recorded base tip,
including its Cargo manifests/lockfile, query catalog, fixture hashes and frontend harness.
The product comparison uses `git merge-base(targetBaseTip, head)` versus exact head, so unrelated
changes landed on main do not masquerade as regressions in an unrebased PR. These are distinct
identities: the publisher independently obtains merge-base through GitHub's compare API and
uses the target base tip only to discard stale runs.

PR #179 has one bounded bootstrap exception because its target base predates this controller:
while the target has no controller, the workflow accepts only this repository and PR number,
and requires the recorded original base to be an ancestor of the current target tip. It uses
a literal reviewed snapshot SHA, never the moving PR head. A descendant main commit does not
by itself require re-pinning; if the target contains the controller, that target version takes
precedence. The frozen snapshot must still compile against both product revisions. If an API
change makes it incompatible, a replacement snapshot needs independent review, compatibility
validation and an explicit pin update. The ancestry check does not supply compatibility.

After migration, normal PRs use their target base's controller. Harness changes are tested by
ordinary Rust/JVM CI on the candidate and take effect in the required comparison after landing
on the base. This prevents a feature PR from changing its own benchmark policy through
`benchmark/` or frontend harness edits. Workflow changes remain subject to owner review. It is
not a sandbox against hostile build scripts and does not claim cryptographic proof of execution.

The workflow runs the paired pipeline. The existing required
check name, `benchmark-regression-gate`, is retained for branch protection, but its decision is
correctness/OOM only. Existing JVM unit tests and native tests/differential checks remain in
`build.yml` and `rust.yml`.

`benchmark-comment.yml` runs `.github/scripts/benchmark/comment.ts` with the pinned Bun
runtime from the trusted default-branch workflow. CI helpers are organized by responsibility
under [`.github/scripts/`](../.github/scripts/README.md); benchmark execution remains in Rust.
It reads artifacts as data and updates one bot comment identified by a hidden marker. It checks
the current PR head/base and run/attempt identity before updating, so a superseded run cannot
replace the latest report. No PR checkout is executed with its write token. The PR comment uses
`summary.md`; `report.md`, `report.json`, `report.html`, raw requests and logs remain artifacts.

The old scheduled, main-branch and release-tag performance workflows and their comparator
scripts are retired. Historical measurements in `backend/bench/` and optimization logs remain
unchanged; their original scripts are available from Git history. Recent-release comparisons,
local attestation and private fixture distribution are deferred.

Shared JVM validation lives in `benchmark/jvm/`: `src/main/kotlin` contains the
whole-corpus assertions reused by frontend JUnit and JMH, and `src/main/java`
contains the forked JVM heap guard and the CLI default-configuration check. Both
read effective JVM configuration in process through MXBeans. The CLI check is attached to
a temporary frontend JAR via `Launcher-Agent-Class`, preserving its real main method and
the clean-environment default path; no generated Java wrapper or observer script is needed. `jmh-validation.init.gradle` wires the trusted
shared Kotlin source into the product revision being measured, including older
revisions. Frontend JMH adapters remain with the frontend implementation. These
validation sources are excluded from the application JAR.

## Harness API compatibility

The trusted Kotlin JMH adapters and shared assertions compile directly against each product
revision's APIs, including `GraphStore`. The overlay is source code, not a stable binary adapter
or an automatic API migration. A constructor, method, package or signature change can therefore
fail preparation before any graph is measured. Updating only the feature branch's harness
cannot fix the required comparison, because that harness is replaced by the target-tip version.

Stage such changes compatibly: first land the necessary product compatibility API while keeping
the interface used by the current trusted harness. Then migrate the harness on the target branch
and verify it compiles against both comparison revisions. Update the feature branch to that
compatible base before removing the old API; remove it only after the trusted harness no longer
uses it. This can require separate preparatory PRs and additional builds. Count or response
approvals do not authorize a compilation failure or adapt an incompatible harness.

## Intentional semantic changes

Default behavior is exact query and complete typed-response equality, including ordering,
duplicates, missing versus null fields and provenance. An intended node-count, schema or query
fix needs the following reviewed sequence:

1. Finish the feature changes, including their ordinary correctness-test expectations. Run the
   comparison to retain full bodies and `semantic-transitions.json`, and independently verify
   the new behavior. Record both product content identities, case IDs and both query/response
   SHA-256 values. A failed run may lack complete query evidence; independently establish and
   review changed whole-JAR counts before collecting any missing query evidence.
2. Land literal directed expectations with a rationale in `src/transitions.rs` on the trusted
   target branch first. For whole-JAR shape changes, add exact `(productIdentity, corpus)`
   count expectations in `LargeCorpusBaselines` in the trusted shared Kotlin harness. Keep the
   target branch's default counts valid for its unchanged product. Fixture hashes, class counts
   and readback/semantic assertions remain mandatory.
3. Update the feature branch to the latest target, as required by strict branch protection,
   resolve policy-file conflicts without losing either the approved entries or the feature's
   ordinary test expectations, and rerun the comparison. A merge/rebase that changes only the
   two excluded policy files preserves the product identities. Any other changed tree contents
   require fresh identities and independently reviewed expectations for affected differences.

Whole-JAR JUnit tests need separate attention. `:webgraph:largeCorpusTest`, also required by
Gradle `check`, reads the default `LargeCorpusBaselines.tika`, `hive` and `kotlinCompiler` values
directly. It does not apply the identity-keyed approval table; this is the same baseline behavior
as `productIdentity = null`. A feature that changes those counts must update its default
expectations as well as obtain trusted benchmark approval. These defaults and the approval
entries live in the same `LargeCorpusValidation.kt`, so merging the policy PR may conflict.
Keep the reviewed new defaults on the feature branch and the literal approved entries. The
required benchmark still installs the trusted target copy, so changing the feature copy alone
cannot approve it. Once new defaults land on the target, comparisons that still use an older
product base need exact approvals for that older base's counts where they differ.

This workflow costs a separate policy review, independent evidence collection, branch updates
and another full build/save/load/query run. Later edits outside the excluded files, including
documentation-only edits, can require repeating identity-specific approval. Finalize such edits
before collecting approval evidence where possible; do not resolve the extra work by weakening
counts, skipping cases or treating a failed build as an approved transition.

`prepare` computes product identities from committed Git objects before any overlay, using
SHA-256 over the domain `graphite-product-identity-v2\0` and sorted, NUL-terminated `git ls-tree`
records (path, file mode, object type and object ID). Exactly two policy-bearing paths are
excluded: `benchmark/src/transitions.rs` and
`benchmark/jvm/src/main/kotlin/io/johnsonlee/graphite/webgraph/LargeCorpusValidation.kt`.
For the required paired benchmark, these are supplied by the trusted controller/harness,
never the candidate product; the required JAR verifier also prevents the shared Kotlin assertions from entering the
application JAR. Their full Git identities and installed hashes remain in preparation evidence.
This is an all-tree identity, not a production-source-only hash: product source, build/lockfiles,
fixture identities, query catalog, READMEs, other documentation, workflows and every other
tracked path remain bound, including paths, modes and object IDs. Changing any of them changes
the identity even when application behavior is unchanged. Keeping approval-only changes within
the two excluded files avoids self-reference. Changes to default counts in the excluded Kotlin
file also leave this identity unchanged, but still require review and ordinary JUnit validation;
the exclusion is not permission to change expectations silently. After merging unrelated target
changes into the feature, recompute both identities and refresh any approval whose key changed.

Product identity v2 uses the relocated policy path above. Historical v1 reports
retain their original identities and must not be reinterpreted as v2 identities.
The old frontend policy path is now bound like every other non-policy file.

Both approved tables start empty. No wildcard, CLI/environment override or candidate-owned
expectation file can authorize a difference. Additional changed cases or changed expected
queries/results fail. Approved semantic changes can pass correctness, but differing queries
or responses make native timing deltas `UNAVAILABLE`; raw values and reasons remain visible.
Full base/head commit and tree identities are retained separately for provenance.

## Cancellation and work-budget correctness

The production Rust server enforces concurrency and deadlines; its deprecated work-budget
option is ignored, and client disconnect is not wired to Cypher cancellation. The native
network probes check concurrent successful queries, invalid inputs and recovery on both revisions.
They do not depend on a Cartesian query being slow enough to hold permits or hit a deadline.
Required head-revision `graphite-explore` route tests use test-only execution barriers and expired
cancellation tokens to check four occupied permits, fifth-request rejection, the timeout cap,
four timeout responses, permit release and successful recovery. These deterministic tests are
head correctness coverage, not evidence that both measured revisions exercised rejection or
timeouts over the network. Neither layer claims native work-budget or disconnect cancellation.
The existing JVM contracts remain covered by the required `build.yml` unit-test job:

- `CypherClientCancellationTest`: real TCP disconnect stops work and restores capacity.
- `CypherCancellationContractTest` and `CypherQueryGuardTest`: cancellation during execution
  and serialization, rejection under load, permit release and continuation ownership.
- `MappedCypherBudgetTest`: persisted mapped-graph scans and index work exhaust the budget.
- `ExploreCommandTest`: the real HTTP route returns `429/cypher_work_budget_exceeded`, then
  accepts a recovery request.

The deleted `cypher-capacity` JMH job was advisory. These behavioral tests stay required;
there is no JVM query timing substitute for the Rust multi-graph report.

## Complex aggregation candidates

The [complex aggregation candidate workload](complex-aggregation-workload.md) contains six
code-graph query shapes inspired by analytical graph benchmarks, with deterministic semantic
tests. It targets slow-query P95 on real multi-graph inputs. This separate candidate catalog is
not yet included in the trusted 73-case protocol; its two-graph execution smoke is correctness
only and supplies no performance evidence. The workload document defines the remaining oracle,
resource, production-representativeness and trusted-harness admission checks.
