# Next step: obtain real64 latency evidence

The next phase should produce a complete paired latency pilot immediately after
the current correctness replay finishes. More internal cache or work-accounting
porting is not a prerequisite for a diagnostic latency baseline. It remains
necessary for full feature parity, and diagnostics must not be represented as
the final correctness or 10× acceptance gate.

No P95 measurement was run for this audit. There are no existing 200-sample
per-case measurements asserted here.

## Exact original timing boundary

Pinned main is `4e328b0109e13c896b74004823fb049fcb19251a`.
[LargeBroadQueryPressureBenchmark.kt](/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.kt:233)
defines one case as follows:

1. Reset storage and graph worker metrics before the clock starts (234–235).
2. Start `System.nanoTime()` (236), then construct cancellation and unlimited
   work-budget context, classify execution path, and select request-scoped
   sources (237–242).
3. Submit to the persistent single-thread executor created at setupTrial
   (137–139). Inside that worker, construct CrossGraphCypherExecutor and call
   `execute(query, parameters)` (243–249). Parsing/execution and result
   materialization occur inside this interval.
4. Stop the successful-case clock immediately after `Future.get` returns
   (251–252). Canonical result encoding, digesting, row validation, provenance
   collection, and reflective graph metrics happen afterward (253–271).
5. An ExecutionException becomes a failed sample, with elapsed wall time sampled
   in its catch branch (288–298). A timeout sample records the configured timeout
   value, **not** elapsed cancellation/join time (273–287); cancellation joins the
   executor via a barrier (407–411). Such a timeout is a censored observation,
   not a successful latency sample.

The clock includes input context/source selection and executor dispatch, but
does not include HTTP, graph loading, setup GC, result serialization, or
post-query graph-state observation. JMH's primary SingleShotTime is the whole
replay (47–59, 173–189), not one query's latency. Existing `p95LatencyNanos` sorts
the 1,267 heterogeneous case samples (426–440); it is **not** the requested
repeated P95 for each case. The existing quantile is nearest rank
`ceil(0.95*n)-1` (1100–1102).

Cold means clear indexes once before the ordered replay (144–156). It does not
mean clear indexes before every case, nor does it mean cold operating-system
page cache. Cache history develops across the original ordered workload. Warm
means clear, execute the entire workload once with validation, enforce its
correctness, then reset metrics/force GC/start sampling. Startup-prepared means
prepare indexes during graph load (99–103, 129–135), then retain them at invocation
setup (152).

## Why previous capture timings cannot be reused

[MainReplayCapture.java](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/docs/go-server-baseline/native64-fullcase-replay/MainReplayCapture.java:50)
replaces the benchmark's queryExecutor. Its submit wrapper scans all graph states
before dispatch. Its Future.get wrapper canonicalizes the result, observes all
graph states, writes/flushed JSON, and prints progress before returning to main
(37–62). Those operations fall inside main's case timer. Its observations TSVs
are valid correctness evidence but unsuitable latency evidence.

The [Go replay command](/Users/johnsonlee/.codex/worktrees/112399f5-4ea0-42da-af34-5ab6ef682c3d/graphite/graphite-server/cmd/graphite-benchmark-replay/main.go:96)
explicitly reports `performanceMeasurement:false`. It observes graph state before
and after each query, performs canonicalization, emits all rows, and executes
directly on the caller goroutine. It currently records no latency. Adding a clock
only around ExecuteCrossWithOptions would omit work included by main's original
timer and would not establish the same benchmark boundary.

## What unchanged main can measure now

Use a thin launcher that instantiates the **original** benchmark, calls
setupTrial/setupInvocation/replayBroadQueries/tearDownTrial, and leaves
queryExecutor untouched. Reuse the verified complete main classpath and its
input-hash checks, but do not install MainReplayCapture.Observer. Set full
coverage/all, graphCount64, timeout60000, record correctness mode, distinct
correctness/observation output paths, and `-Xmx8g`. Runtime sampling and original
metrics remain part of the original harness; do not describe the result as
literally zero measurement overhead.

Cold and startup-prepared run all 1,267 cases. The original benchmark writes
correctness and observation TSVs before calling enforceCorrectness (185–188).
The observations contain case identity, outcome, row count, digest, and
`latencyNanos` (614–690). Therefore a launcher may archive complete, minimally
observed **diagnostic** latency data even when the original gate then throws.
Preserve the thrown error and original failing gate status. No engine or
benchmark gate change is necessary to collect this diagnostic data.

The known zero-based index821 (`four-or-graph-id-targeted`) produces
`IllegalStateException: Unsafe expression reached parallel string projection`,
independently present in the existing full main capture. It must remain in every
replay, result table, coverage count, and outcome ledger. Its latency is time to
failure, not successful-query throughput or proof of useful query acceleration.

For warm, [setupInvocation line150](/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.kt:150)
enforces correctness on the whole prewarm replay. The same case fails that gate
before forcePressureGc, sampler.start, and replayBroadQueries. The unchanged
runner cannot produce formal warm measurements. Its prewarm timings are not warm
samples and must not be relabeled. There is also no warm observations TSV from
that setup path.

[QueryCorrectnessManifest](/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/QueryCorrectnessManifest.kt:121)
requires all outcomes successful for record mode (121–131). Verify mode likewise
rejects failed oracle records when loading/selecting (104–118) and verifying
(134–168). Switching from record to verify does not solve the errorcase.

## Concrete paired pilot and repeated sampling

1. Freeze a native binary, exact main classpath/JAR, runtime versions/options,
   workload SHA, and real64 fixture manifest. Finish any current runtime first.
   Use fresh independently cloned graph directories from the immutable 1,152-file
   corpus and preserve all 1,267 cases, source order, parameters, request scope,
   work-tracking policy, timeouts, and current index-state definitions.
2. Add a separate Go timing mode/driver rather than deriving numbers from existing
   correctness logs. For each case: prepare the static case definition outside
   the interval; start a monotonic clock; create context and choose request-scoped
   sources; dispatch execution to one persistent worker goroutine; stop when the
   completed result/error reaches the caller. Keep canonicalization, oracle
   checks, full graph-state snapshots, and file writes outside the interval.
   Match original timeout outcome/cancellation-join behavior, preserving a
   separate censored-timeout flag. Do not pretend Go and JVM scheduler overhead
   are identical; this deliberately measures each runtime's corresponding
   dispatch-inclusive boundary.
3. Run one complete paired cold pilot and one complete paired startup-prepared
   pilot serially on the same machine. Produce per-case main/native latency,
   outcomes, correctness signature, ratio, coverage, and sample count. At n=1,
   report individual observations, not an accepted P95. Compare against all
   existing success signatures and retain the known matching failure; any new
   mismatch invalidates the affected run. Archive full logs and fixture/input
   hashes. This gives an actionable baseline before further optimization.
4. Freeze a repetition protocol, then collect at least200 observations **per
   runtime × state × case**, indexed by repetition. A repetition executes the
   whole ordered workload; do not run one case200 times in place, drop case821,
   filter a family, or average the mixed-case counter. Fresh identical clone/load
   per trial avoids persisted-index changes leaking across nominal cold trials.
   Record process/fork and discarded runtime warmup policy explicitly:200 fresh
   processes and200 iterations in one process are different experiments. Use
   balanced main/Go run order and no simultaneous benchmark runtimes.
5. Aggregate only after independently checking exact coverage, outcomes,
   signatures, fixture hashes, and state protocol for every repetition. Report
   n, failures/timeouts, median and nearest-rank P95 for each case/state, then
   `mainP95 / GoP95`. At n=200 nearest-rank P95 is the190th ordered observation.
   Ratios of mixed-case percentiles do not satisfy acceptance. Any measured
   errorcase remains explicit and cannot become a success pass by a fast error.

## Explicit policy decision needed for formal warm and final acceptance

There are two distinct possible benchmark changes; neither is equivalent to
passing unchanged pinned main:

- An **error-aware diagnostic protocol** can preserve the exact engine, testcase,
  order, and expected class/message at index821; permit its matching failure
  during prewarm; then collect post-prewarm timings. Version this harness policy,
  record originalGatePassed=false, keep all cases, and label results separately
  from original formal warm. A expected-error allowlist must be exact and reject
  new errors/timeouts or message changes. This enables investigation but does
  not meet an all-success gate.
- To pass the original all-success benchmark, fix the main engine bug (and its Go
  counterpart if necessary), pin a new explicit reference revision, regenerate
  the full oracle, and rerun all states under the revised baseline. That changes
  the reference from current pinned main. Never silently patch/remove the query
  or claim the fixed reference is the unchanged `4e328b0` baseline.

The immediate cold/startup diagnostic pilot can proceed before this acceptance
policy is resolved. Full formal warm and all-case10× acceptance remain unproven.
The broader server HTTP/end-to-end and required CI benchmark gate also remain
separate requirements; this workload measures direct Cypher execution.

## Audited source SHA-256

- LargeBroadQueryPressureBenchmark.kt:
  `154a6ff739caac2600b769072d6861d1d8d04c4692c7eff8bca5c8d7477d499f`
- QueryCorrectnessManifest.kt:
  `f6e8af6000ccd89e02ac932bf7e95c75cacce636ebcc73227e997f695f06e717`
- graphite-server/cmd/graphite-benchmark-replay/main.go:
  `c9eb693f5ace00963804baa3f9e4502b057edcc1d6427e6401f025ef0bec8d95`
- native64-fullcase-replay/MainReplayCapture.java:
  `e8c497950f83463dbec5833610864bd88429a95c76092067af95b910958ce44e`
- internal/benchmarkcase/testdata/main64.json:
  `378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023`

These hashes were read from current source files during this audit. Main fat JAR
remains the previously verified
`91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
