# Graphite benchmark controller

`graphite-benchmark` is a development-only Cargo workspace binary. It runs the same
base/head workload on one machine and emits one result model as JSON, Markdown and HTML.
Correctness, complete coverage and OOM failures block the PR. Timing differences never do.
There are no CLI/environment overrides for fixture identities, heap ceilings, sample counts,
concurrency or acceptance rules; change the reviewed Rust/JMH source to change the protocol.

## Pipeline

1. `prepare` builds both exact revisions with the same three frontend benchmark source files.
   Gradle resolves the four pinned real JARs; the controller verifies their SHA256 and sizes.
   Compiler/build-tool time is preparation overhead, not graph construction performance.
2. `jvm` runs `Fixture64BuildBenchmark` through real JMH: one fork, zero warmups, one
   single-shot invocation per revision. The invocation partitions the four JARs into 16
   shards each, builds and saves 64 distinct graphs, and retains the original resource,
   semantic, persisted-index and readback assertions. It never restores a saved-graph cache.
3. `native` starts each revision's production Rust server with that revision's saved graphs.
   It checks identical source/shard identities and query text, completely consumes and checks
   responses, then runs the fixed real64 pressure plan. JVM query benchmarks are not used
   as a proxy for the Rust server.
4. `report` requires exactly one frontend and one native result for the same base/head pair.
   Missing, cancelled or failed work is not PASS. Timing rows are always `REPORT_ONLY`.

The JMH launcher and forks use 4 GiB for JAR fixtures. The Java agent checks actual
`Runtime.maxMemory()`, not just command-line text. The fixed APK policy is 8 GiB; the current
real64 workload consists of Android-all, Tika, Hive and Kotlin compiler **JARs**, not APKs.
APK fixture publication and APK workload integration are not part of this change.
Linux cgroup OOM counters and process exit status supplement the JVM heap checks. A host OOM
is a failed run, not evidence that the configured Java heap was exceeded. There is no retry
with a larger heap.

## Running

Use two dedicated source checkouts: `prepare` installs the shared benchmark source into them.
The source implementation remains revision-specific; the three installed benchmark file hashes
and source revisions are recorded in `prepared/preparation.json`.

```sh
cargo build --release --locked -p graphite-benchmark
BENCH=target/release/graphite-benchmark
$BENCH prepare --base-source /work/base --head-source /work/head --output prepared
$BENCH jvm --suite frontend-build-save \
  --base-jar prepared/base/webgraph-jmh.jar --head-jar prepared/head/webgraph-jmh.jar \
  --fixtures prepared/fixtures --output results/frontend \
  --base-sha "$BASE_SHA" --head-sha "$HEAD_SHA"
$BENCH native --base prepared/base/graphite --head prepared/head/graphite \
  --base-manifest results/frontend/base/graphs/graphs.tsv \
  --head-manifest results/frontend/head/graphs/graphs.tsv \
  --output results/native --base-sha "$BASE_SHA" --head-sha "$HEAD_SHA"
$BENCH report --input results --output report
```

The hosted acceptance environment is Linux with cgroup v2, JDK 17 and Rust. Diagnostic local
runs on other platforms do not provide Linux host-OOM evidence. Outputs are retained on failure;
use a fresh preparation/output directory for another run. No signing key, service or separate
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

## GitHub integration

`benchmark.yml` builds/tests this controller and runs the paired pipeline. The existing required
check name, `benchmark-regression-gate`, is retained for branch protection, but its decision is
correctness/OOM only. Existing JVM unit tests and native tests/differential checks remain in
`build.yml` and `rust.yml`.

`benchmark-comment.yml` runs the comment publisher from the trusted default-branch workflow.
It reads artifacts as data and updates one bot comment identified by a hidden marker. It checks
the current PR head/base and run/attempt identity before updating, so a superseded run cannot
replace the latest report. No PR checkout is executed with its write token. The PR comment uses
`summary.md`; `report.md`, `report.json`, `report.html`, raw requests and logs remain artifacts.

The old scheduled, main-branch and release-tag performance workflows and their comparator
scripts are retired. Historical measurements in `backend/bench/` and optimization logs remain
unchanged; their original scripts are available from Git history. Recent-release comparisons,
local attestation and private fixture distribution are deferred.
