# Wrapped Case-Insensitive Query Optimization Attempts

This log records the investigation and every retained optimization attempt for
the production `toLower(coalesce(...)) CONTAINS` discovery query. The fixed
comparison baseline is commit `44b57562f2b3d0c88882a9002bdc488e05e5d7a7`,
the commit immediately before PR #95.

## Query and acceptance criteria

The benchmark uses the same four wrapped properties, `DISTINCT`, projection,
and `LIMIT 250` as the reported query. Both one graph and 17 graphs are required
because the executor consumes graph sources serially and qualified `DISTINCT`
must keep scanning to merge complete provenance.

The change is accepted only if it:

- preserves Kotlin `String.lowercase()` semantics exactly, including mixed-case
  and Unicode values;
- preserves null/missing-property behavior, row order, `DISTINCT`, `LIMIT`, and
  cross-graph provenance;
- is at least 50% faster than the fixed pre-PR-95 benchmark for both one and 17
  persisted graphs;
- does not regress more than 15% against the pull request base; and
- leaves unsupported graphs, properties, and expression shapes on the generic
  evaluator.

### 2026-08-29 - Attempt 000: Reproduce and isolate the regression

The exact real Android mmap graph contains 5,938,826 nodes. An identical JMH
harness was compiled at the fixed pre-PR-95 commit and current `main`.

| Revision | 1 graph | 17 graphs | 1 graph allocation | 17 graph allocation |
|---|---:|---:|---:|---:|
| pre-PR-95 `44b5756` | `5,335.201 ms/op` | `92,396 ms/op` | `12.113 GB/op` | `205.29 GB/op` |
| post-PR-95 `e2a2b1a` | `5,371.547 ms/op` | `89,635 ms/op` | `12.071 GB/op` | `194.44 GB/op` |

PR #95 changes the one-graph runtime by only `+0.68%`; the roughly 90-second
latency comes from executing the same generic full scan once for each of 17
graphs. The PR #95 work-budget check rejects the query in about 220 ms when the
default budget is enabled and is not the source of the 9-10x execution time.

### 2026-08-29 - Attempt 001: Query-only rewrites

On the same Android graph, adding a `CallSiteNode` label reduces the wrapped
query to `1,681.695 ms/op`, while rewriting it to direct case-sensitive
properties reaches `614.051 ms/op`.

**Conclusion:** rejected as a product fix. The label excludes dynamic
`AnnotationNode` properties and the direct query changes case semantics. The
result establishes that storage-aware property lookup has enough headroom, but
the planner must recognize the wrapped AST without rewriting user semantics.

### 2026-08-29 - Attempt 002: Exact transformed lookup capability

Added an optional transformed-string lookup capability instead of extending
the existing `StringMatchMode` enum or adding a virtual method to `Graph`. The
planner recognizes only these exact operands:

```text
toLower(n.property)
toLower(coalesce(n.property, ''))
toLowercase(...)
```

The right-hand literal is not normalized. An empty literal with `coalesce` is
sent to the generic evaluator because missing properties must match it. Mapped
storage evaluates lowercase once per distinct raw string ID when an index is
admitted, and raw and transformed predicate caches use separate keys.

The first real-graph run reduced latency to `0.750 s/op` for one graph and
`12.918 s/op` for 17 graphs, with allocation reduced to about `1.60 GB/op` per
graph.

**Conclusion:** retained. Results and provenance are unchanged, but large
properties that exceed the retained-index budget still repeat lowercase work
while scanning raw `(nodeId, stringId)` pairs.

### 2026-08-29 - Attempt 003: Remove boxed index iteration

Replaced `indices.asSequence().filter().map()` in the admitted property index
with one primitive loop. The Android result moved from `0.750` to `0.721 s/op`
for one graph and allocation remained about `1.60 GB/op`.

**Conclusion:** retained as a low-risk allocation fix for indexed hits, but it
does not address the measured query because the Android call-site properties
exceed the 8 MiB retained-index limit and use the raw scan.

### 2026-08-29 - Attempt 004: Per-query raw string-ID match states

After the first 256 inspected nodes, a raw scan now allocates one bounded byte
per global string-table ID and records unknown/miss/match state. Repeated class
and method strings are decoded and lowercased once per property query instead
of once per node. The state array is transient, starts only after a long scan,
and is disabled when it would exceed 16 MiB, so early `LIMIT` hits and very large
string tables keep the previous memory behavior.

Exact single-shot protocol:

```shell
java -jar graphite-webgraph/build/libs/*-jmh.jar \
  'io.johnsonlee.graphite.webgraph.RealCompositeQueryBenchmark.compositeDistinct$' \
  -p graphCount=1,17 -f 1 -foe true -prof gc \
  -jvmArgsAppend '-Dandroid.graph.path=<persisted-android-graph>' \
  -rf json -rff candidate-real-composite-final.json
```

Environment: Apple M3 Max, 64 GiB RAM, macOS arm64, OpenJDK 17.0.18, JMH
1.37, one thread, one fork, one single-shot warmup, three single-shot
measurements, `-Xmx12g`. Baseline and candidate use the same 5,938,826-node
persisted graph and exact benchmark source.

| Graphs | Pre-PR-95 baseline | Final | Speedup | Allocation before | Allocation final | Reduction |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | `5,335.201 ms/op` | `180.862 ms/op` | `29.50x` | `12.113 GB/op` | `0.290 GB/op` | `97.6%` |
| 17 | `92,396 ms/op` | `2,733.259 ms/op` | `33.80x` | `205.29 GB/op` | `4.927 GB/op` | `97.6%` |

**Conclusion:** retained. The same 17-source query is below three seconds on
the benchmark machine, substantially better than both the roughly 90-second
fixed baseline and the reported former 10-second behavior.

## Permanent regression gate

Current policy excludes synthetic graphs from performance evidence. The former synthetic
`WrappedDiscoveryLatencyBenchmark` and its 1/4/16/64 graph shards have been removed. CI now compares
only the persisted real-fixture workloads in `AllFixtureWrappedDiscoveryLatencyBenchmark` and the
real 36-graph workload. Synthetic fixtures remain valid only for correctness and deterministic path
coverage.

`AndroidWrappedDiscoveryBenchmark` is the manual real-corpus counterpart. It
uses the exact production query and the existing validated Android corpus.

### 2026-08-29 - Attempt 005: Heterogeneous all-fixture baseline

The repeated-Android benchmark isolated graph-count scaling but reused one
mapped graph and therefore is no longer accepted as performance evidence. The permanent gate uses
`AllFixtureWrappedDiscoveryLatencyBenchmark`: Android, Tika, Hive, and Kotlin
Compiler are built and persisted one at a time, then all four mapped graphs
(`19,091,048` nodes total) are queried together. CI prepares the persisted
graphs once and supplies the identical files to pre-PR-95, current-base, and
candidate JMH forks.

The suite does not merely substitute constants. A preflight records and pins
the exact per-corpus match counts, covering zero hits; dense matches in all
four graphs; matches isolated to the first, middle, or last graph; broadly
distributed matches; first/last bimodal matches; and a highly skewed case with
a dense Android head and sparse tail. Query shapes vary class versus method,
caller versus callee, `CONTAINS`/`STARTS WITH`/`ENDS WITH`, mixed operators,
multi-target OR, and `LIMIT 1/50/250`.

Same-machine SingleShot results over the shared four-corpus persisted fixture:

| Distribution / shape | Pre-PR-95 | Final | Speedup |
|---|---:|---:|---:|
| zero-hit broad `CONTAINS`, four fields | `15.209 s/op` | `0.520 s/op` | `29.25x` |
| dense method `CONTAINS`, all graphs | `14.019 s/op` | `1.836 s/op` | `7.64x` |
| first-graph-only class prefix | `11.506 s/op` | `1.481 s/op` | `7.77x` |
| middle-graphs-only class prefixes | `14.314 s/op` | `1.349 s/op` | `10.61x` |
| last-graph-only class prefix | `12.695 s/op` | `1.438 s/op` | `8.83x` |
| broadly distributed class prefix | `13.927 s/op` | `1.715 s/op` | `8.12x` |
| first/last bimodal class prefixes | `16.289 s/op` | `2.842 s/op` | `5.73x` |
| skewed mixed class/method operators | `15.977 s/op` | `1.811 s/op` | `8.82x` |
| arithmetic mean | `14.242 s/op` | `1.624 s/op` | `8.77x` |

These are the final order-preserving measurements with one unmeasured warmup
and three measured single-shot invocations per case. All eight complete result
digests match the pre-PR-95 executor, including ordered rows and provenance.
The 50% fixed-baseline speedup threshold remains comfortably satisfied for
every distribution.

The final gate also closes three false-pass/resource gaps found during review:
it compares complete result/provenance digests across fixed, base, and
candidate; requires an explicit manifest of all 12 benchmark keys; and builds
each source corpus in a separate JVM/private temporary directory before
removing the raw mmap files. Thus an empty or semantically wrong fast result,
a deleted benchmark variant, or accumulated source mmap files cannot masquerade
as a successful latency optimization.

Local full-protocol gate validation on the same machine as the real-corpus run:

| Benchmark | Pre-PR-95 | `main` | Final |
|---|---:|---:|---:|
| cold, 1 graph | `3.994 ms/op` | `4.207 ms/op` | `0.566 ms/op` |
| cold, 17 graphs | `68.841 ms/op` | `83.809 ms/op` | `7.917 ms/op` |
| warm, 1 graph | `5.660 ms/op` | `5.108 ms/op` | `0.126 ms/op` |
| warm, 17 graphs | `97.534 ms/op` | `71.127 ms/op` | `0.216 ms/op` |

All four rows pass `compare-latency-baseline`. Existing method-level
`CypherBenchmark` rows also pass the 15% gate. Persisted-storage checks show no
adjacent regression: Android mapped load is `149.418 -> 139.761 ms/op`, the
five mapped query benchmarks range from `-12.6%` to `+6.7%`, and
`GraphEndToEndBenchmark.android_build_save_load_query` is
`25,294.562 -> 24,900.711 ms/op` (`-1.6%`).

### 2026-08-29 - Attempt 006: Fuse same-node string-property disjunctions

The mapped backend previously evaluated the four caller/callee predicates as
four independent storage lookups (or fell back to deserializing a complete
node stream when a cold unbounded lookup was not admitted). It now exposes an
optional exact disjunction capability. The Cypher fast path uses it only when
the backend supports every property, preserving the existing ordered fallback
for dynamic and unsupported properties. One fused scan inspects each candidate
node ID once, emits each matching node once in canonical order, and charges one
work unit per inspected candidate. Predicates with the same transform, operator,
and literal share their string-ID match state even when they target different
properties.

Preliminary same-machine, cold single-shot checks on the four real fixtures:

| Distribution | `main` | Fused scan | Speedup | Allocation reduction |
|---|---:|---:|---:|---:|
| dense method `CONTAINS` | `2.050 s` | `1.703 s` | `1.20x` | `10.8%` |
| first/last bimodal prefix | `3.352 s` | `2.279 s` | `1.47x` | `29.1%` |
| zero-hit broad `CONTAINS` | `0.831 s` | `0.537 s` | `1.55x` | `21.6%` |

**Conclusion:** retained. The capability removes redundant storage work and
reduces allocation without changing query semantics, but it is not sufficient
for the 10x multi-graph objective. The next attempt adds bounded cross-graph
execution around this fused primitive while retaining ordered global merge,
budget, cancellation, and provenance semantics.

### 2026-08-29 - Attempt 007: Bounded ordered cross-graph scans

The direct disjunction path now scans independent graph instances on a private
bounded executor, while the caller merges rows strictly by source ordinal. A
worker pauses after at most `LIMIT` local distinct rows. After the global rows
are known, it drains its remaining candidates only to add provenance for those
selected visible values. This preserves first-seen row order and complete
cross-graph provenance without retaining every matching row. Unsupported or
non-deterministic projections stay serial; the same graph instance is locked
across workers; work accounting uses one atomic CAS counter; and the first
failure interrupts and joins every submitted scan.

The real 36-graph zero-hit case established why logical CPU count is not a safe
default on this mmap/allocation-bound workload:

| Parallelism | Latency | Relative to serial | Allocation |
|---:|---:|---:|---:|
| 1 | `3.038 s` | `1.00x` | `11.24 GB/op` |
| 2 | `2.708 s` | `1.12x` | `11.51 GB/op` |
| 4 | `4.988 s` | `0.61x` | `11.52 GB/op` |
| 8 | `10.207 s` | `0.30x` | `11.44 GB/op` |
| 16 (`NCPU`) | `19.697 s` | `0.15x` | `12.16 GB/op` |

The default is therefore two workers, still bounded by available processors;
`graphite.cypher.directStringParallelism` remains available for controlled
deployment and benchmark experiments. On the four real fixtures, two workers
produce `0.535–2.081 s/op`. Five of eight distributions clear the fixed-baseline
10x target; first-only (`9.26x`), last-only (`8.84x`), and first/last bimodal
(`7.83x`) show that concurrency alone cannot satisfy the gate.

**Conclusion:** retained at parallelism two. It improves the dense case from
`1.707` to `1.169 s/op` and reduces its allocation from `5.23` to `4.36 GB/op`,
while higher fan-out is decisively rejected. The remaining work targets
per-graph string decode/lowercase allocation rather than adding threads.

### 2026-08-29 - Attempt 008: Reusable ASCII lowercase comparison buffer

Tested decoding front-coded strings into one reusable `MutableString` and
performing exact allocation-free ASCII lowercase `STARTS WITH`/`ENDS WITH`/
`CONTAINS` comparisons, with the existing Kotlin `String.lowercase()` path for
non-ASCII values. A new persisted-graph test pins mixed ASCII, Unicode `İ`, and
the rule that the expected literal itself is not normalized.

Against Attempt 007 at parallelism two, zero-hit improved from `0.535` to
`0.489 s/op` and allocation fell from `0.980` to `0.902 GB/op`. Dense improved
only from `1.169` to `1.120 s/op`; early, first/last bimodal, broad, and skewed
cases regressed to `1.392`, `2.450`, `1.399`, and `1.554 s/op` respectively.
Their multi-gigabyte allocation barely changed because it is dominated by
materializing and projecting matching call-site nodes, not lowercase strings.

**Conclusion:** rejected and implementation removed. The Unicode/exactness test
is retained. The next attempt avoids materializing matches whose projected
visible values cannot contribute to the already selected global LIMIT rows.

### 2026-08-29 - Attempt 009: Selected-row projection matcher

Once ordered merging establishes the global `DISTINCT ... LIMIT` rows, later
graph scans no longer build a projected map and provenance set for every match.
They compute the selected projection's map hash directly, then perform exact
column equality only within the matching hash bucket. Each scanner reuses its
projection-value array, and duplicate return aliases retain the normal
last-write projection semantics. Hash collisions therefore affect lookup cost,
not correctness.

At parallelism two, allocation fell by `15–24%` on every real hit-bearing
distribution: dense from `4.36` to `3.36 GB/op`, early from `3.68` to
`2.80 GB/op`, broad from `4.63` to `3.50 GB/op`, and bimodal from `5.99` to
`4.97 GB/op`; the zero-hit case remained `0.98 GB/op`. A follow-up cursor that
computes each projection value once per match reduced bimodal further to
`4.62 GB/op`. One-fork spot checks measured early, bimodal, and late at
`1.214`, `1.798`, and `1.175 s/op`, respectively. Late now clears the fixed
10x baseline, while early and bimodal remain just below it.

**Conclusion:** retained. It removes allocation proportional to the number of
non-selected matches without changing row order, distinctness, provenance, or
fallback behavior. The remaining work must avoid matched-node materialization
or use distribution-aware scheduling; a larger default worker pool is not
supported by the 36-graph data.

### 2026-08-29 - Attempt 010: Raw storage projection

Tested a mapped-store capability that returned only the raw string properties
needed by `RETURN`, bypassing complete `CallSiteNode` deserialization. The
parallel scanner consumed these projected arrays directly and retained the
materialized-node fallback for every other graph implementation and expression.

This did not remove the dominant work. At parallelism two, early regressed from
`1.214` to `1.785 s/op`, bimodal moved from `1.798` to `1.857 s/op`, and late
from `1.175` to `1.202 s/op`. Allocation was still `2.84`, `4.58`, and
`2.90 GB/op`: phase-two provenance completion continued creating and decoding
a four-column array for every filter match, even though nearly every match was
not one of the globally selected rows.

**Conclusion:** rejected and implementation removed. The next storage
primitive should test selected projections during the raw scan and return only
which selected rows occurred, rather than projecting every filter match.

### 2026-08-29 - Attempt 011: Selected-projection storage pushdown

Tested the narrower primitive proposed by Attempt 010. It resumed after the
last yielded canonical node ID, compared raw string IDs against only the global
selected rows, and returned a boolean hit vector. Focused tests covered resume
position, exact selected/missing values, source order, and provenance.

The real fixture result showed that phase-two projection was still not the
dominant cold cost. Early, bimodal, and late measured `1.242`, `1.885`, and
`1.332 s/op`, with `2.87`, `5.06`, and `2.90 GB/op` allocated. Every remaining
node still had to evaluate the cold lowercase filter, which decodes and
lowercases each previously unseen string-table value. Avoiding matched-node
projection therefore could not materially change the total.

**Conclusion:** rejected and implementation removed. The next attempt targets
the cold lowercase predicate itself while retaining exact Unicode behavior.

### 2026-08-29 - Attempt 012: Direct ASCII lowercase matching

Tested matching decoded all-ASCII strings by lowercasing each character during
comparison, avoiding the second `String` allocated by `lowercase()`. Any
non-ASCII input retained the existing Kotlin lowercase path, including Unicode
expansion behavior and the rule that the expected literal is not normalized.

The allocation reduction was small because front-coded string decoding still
allocates the source string. Early remained `1.213 s/op`, bimodal measured
`1.822 s/op`, and broad regressed to `1.430 s/op`; zero-hit remained
`0.529 s/op`. This repeated Attempt 008's conclusion without its mutable-buffer
decode overhead: lowercase allocation alone is not the remaining bottleneck.

**Conclusion:** rejected and implementation removed. Phase-two scheduling has
a clearer avoidable stall: fixed-size waves leave a worker idle while waiting
for the slowest graph in the current wave.

### 2026-08-29 - Attempt 013: Work-conserving provenance join

Phase one remains source-ordered and wave-bounded because later graph work can
become unnecessary once the global limit is known. Phase two is different:
every graph must finish provenance discovery. It now submits all remaining
graph tasks to the same fixed two-worker executor at once. Completion of any
task immediately admits the next graph, eliminating the barrier between fixed
`[0,1]`, `[2,3]` waves without increasing concurrency. A deterministic test
holds one provenance task open and proves that a third graph starts as soon as
the other worker becomes free.

One-fork cold real-fixture checks now clear the fixed pre-PR-95 10x baseline in
all eight distributions:

| Distribution | Candidate | Fixed-baseline speedup |
|---|---:|---:|
| broad | `1.062 s` | `13.11x` |
| dense | `0.919 s` | `15.25x` |
| early | `1.102 s` | `10.44x` |
| bimodal | `1.139 s` | `14.30x` |
| late | `1.181 s` | `10.75x` |
| middle | `1.190 s` | `12.03x` |
| skewed | `1.072 s` | `14.90x` |
| zero hit | `0.536 s` | `28.37x` |

**Conclusion:** retained. This is a bounded fork/join schedule with parallelism
two, not an `NCPU` pool. It improves load balance while preserving exact work
accounting, cancellation joins, source-selected row order, and complete
provenance.

### 2026-08-29 - Attempt 014: Fair and reentrant work-conserving join

Attempt 013 removed the phase-two wave barrier, but its first implementation
queued every graph in the shared two-worker executor. One large request could
therefore place all of its graph tasks ahead of a later request. A graph lookup
callback that recursively executed another qualifying cross-graph query could
also make both workers wait for nested work in the same pool.

The retained scheduler now keeps at most two tasks from each request in flight
and admits one replacement whenever a task completes. Nested queries detected
on a scan worker use the existing serial path. Per-graph exclusion uses a weak,
identity-stable interruptible lock instead of the graph monitor, so sibling
failure can cancel and join a task waiting behind another request. Raw fallback
node scans also poll interruption before filtering zero-hit candidates. Tests
cover nested parallel callbacks, rolling admission beyond two graphs, and
failure while another thread holds the same graph. The shared budget CAS loop
now retries a raced exhaustion update before reporting the limit exceeded.

Formal one-warmup/three-measurement real-fixture results stayed comfortably
above the fixed pre-PR-95 10x floor:

| Distribution | Candidate | Fixed-baseline speedup |
|---|---:|---:|
| broad | `0.659 s/op` | `21.12x` |
| dense | `0.742 s/op` | `18.90x` |
| early | `0.900 s/op` | `12.78x` |
| bimodal | `0.939 s/op` | `17.34x` |
| late | `0.888 s/op` | `14.30x` |
| middle | `0.787 s/op` | `18.18x` |
| skewed | `0.892 s/op` | `17.91x` |
| zero hit | `0.296 s/op` | `51.33x` |

The independent 36-real-graph zero-hit gate measured `2.401 s/op`. Its setup
also runs a positive query that returns the exact 36 ordered graph identities,
preventing an empty-result shortcut from masquerading as a latency win.

**Conclusion:** retained and supersedes Attempt 013's submit-all queue. It keeps
two-way scan parallelism without cross-request FIFO monopolization or nested
pool deadlock.

### 2026-08-29 - Attempt 015: Lookup-state-driven parallel admission

The synthetic timings below are retained only as a historical diagnostic record. Under the current
benchmark policy they cannot establish or gate performance.

The first GitHub Actions run showed that executor overhead is material on tiny
warm graphs: the four-graph synthetic cache-hit case moved from `0.194 ms/op`
on `main` to `0.753 ms/op`, even though the cold path improved. Those fixtures
contain only 2,000 relevant call-site nodes per graph and are not representative
of the production scans that motivated parallel execution.

Mapped graphs now expose whether the relevant string state is warm and the
property index fits within its retained-memory budget. If every relevant graph
prefers that indexed path, the pipeline stays serial and avoids worker setup.
Cold, oversized, unknown, and unordered lookups remain eligible for fused
storage scans and bounded two-worker parallelism. The decision follows actual
lookup state instead of a graph-count or CPU-count heuristic.

That synthetic gate previously sampled the geometric `1/4/16/64` curve. It has been retired; only
heterogeneous persisted real-fixture measurements and independently supplied real production graph
manifests may support latency, CPU, or memory conclusions.

**Conclusion:** retained. Small cached queries avoid fused and fork-join setup,
while real multi-graph searches keep bounded parallel scans.

### 2026-09-02 - Attempt 016: Additive NCPU graph/segment plan

The current wide-query plan reads `Runtime.availableProcessors()`; it does not hardcode 16. For
`NCPU > 1`, the default divides that budget additively into
`floor(NCPU / 2)` graph workers plus the remaining segment workers. Thus a 16-CPU process plans
`8 + 8`, not `8 × 8`; a one-CPU process plans `1 + 0`. The number of graph workers is also capped
by the number of selected sources.

The intra-graph half is enabled by default for wide searches with at least 40 input graphs. Smaller
existing real-fixture cases retain the legacy graph-level budget of `min(NCPU, 8, sourceCount)` and
keep each storage lookup serial. This distinction fixed a regression where the 36-real-graph gate
had accidentally been reduced from the main branch's available graph workers to half of NCPU: its
cold zero-hit latency returned from about `1.26 s` to `0.583 s`, matching the `0.579 s` main result
on the same host. A separate NCPU=4 reproduction reduced the candidate from `2.026 s` in CI to
`0.919 s` after restoring all four graph workers.

`graphite.cypher.directStringParallelism=N` means “allocate N graph workers from the process-wide
NCPU budget”; the remaining `NCPU-N` workers are available for segment scans. For example, an
override of 16 on a 16-CPU process produces `16 + 0`, never 24 runnable scan workers. This planner
does not infer concurrency from unused heap. Heap governs persisted-index admission separately;
the scan plan is CPU- and source-count-based.

The 64-real-graph gate uses graph shards regenerated from four pinned fixture JARs, three
alternating base/candidate fork pairs whose individual P95 speedups must each clear 10x, exact
result digests, targeted hits distributed from the first through the sixty-fourth graph, and a
zero-hit proof that all 64 graphs were searched. Synthetic graphs remain correctness-only and
cannot establish performance. The gate uses raw case-sensitive `CONTAINS` predicates matching the
production query shape; wrapped lowercase predicates remain separate persisted-index coverage.

### 2026-09-02 - Attempt 017: Selected-tuple segment parallelism

**Hypothesis:**

Split the `RETURN DISTINCT` provenance tuple recheck inside each graph while the Cypher executor
keeps eight graph workers. The shared storage executor preserves the additive 8 graph + 8 storage
worker bound on the 16-CPU test host.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g`.
- Reference implementation: selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: 34/34 oracle records passed; zero failures and zero timeouts.
- Wrapped case-insensitive DISTINCT dense latency: 50.916 ms -> 48.788 ms (1.04x).
- Graph work: 153,786 -> 155,010 units.
- Observed workers: graph peak 8, storage peak 8.

**Conclusion:**

Reverted. The worker budget was correct, but the 4% wall-time change was noise-sized and total work
increased. The next milestone must remove work from the provenance path rather than subdivide the
same work further.

### 2026-09-02 - Attempt 018: Batched front-coded string lookup

**Hypothesis:**

Resolve the selected projection strings in batches, sharing bounds while searching the persisted,
sorted front-coded string table. Resolve properties progressively so a missing earlier property
still eliminates a tuple before later lookups.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g`.
- Reference implementation: selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: 34/34 oracle records passed; zero failures and zero timeouts.
- Wrapped case-insensitive DISTINCT dense latency: 50.916 ms -> 46.522 ms (1.09x).
- Graph work remained 153,786 units.

**Conclusion:**

Reverted. Shared binary-search bounds helped, but not enough to justify another lookup path. Later
profiling confirmed that repeated tuple-to-string-ID resolution is important, but this batching
strategy removes too little of its random front-coded decoding cost.

### 2026-09-02 - Attempt 019: Persisted property fingerprint index

**Hypothesis:**

Persist a primitive `(stable hash, string ID)` index for each CallSite string property. Exact string
comparison resolves hash collisions, while provenance avoids repeated binary searches and random
decoding in `FrontCodedStringList`.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs; all 64 v3 sidecars
  were rebuilt before measurement.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g` with a 6 GiB explicit index
  budget for the admission check.
- Reference implementation: selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: 34/34 oracle records passed; zero failures and zero timeouts; hash-collision, case,
  missing-value, work-denial, and cancellation tests passed.
- Admission: 64/64 graphs.
- Wrapped case-insensitive DISTINCT dense latency: 50.916 ms -> 40.712 ms (1.25x).
- Peak heap: 4.443 GiB -> 4.677 GiB (+5.3%).

**Conclusion:**

Reverted. This was a measurable incremental latency gain and stayed inside the memory envelope, but
the retained index and sidecar-format complexity were disproportionate to 1.25x. The experiment
also showed that lookup acceleration alone cannot remove the first-pass cost.

### 2026-09-02 - Attempt 020: Allocation-free anchor probe

**Hypothesis:**

Remove the per-posting `IntArray` and key-object allocations from selected-tuple anchor scans. Use a
primitive projection hash to select a bucket and the existing four-property equality check to
resolve collisions.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g`.
- Reference implementation: selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: 34/34 oracle records passed; zero failures and zero timeouts; deliberate projection
  hash collisions and repeated anchors passed exact-result tests.
- Wrapped case-insensitive DISTINCT dense latency: 50.916 ms -> 43.739 ms (1.16x).
- Graph work remained 153,786 units.

**Conclusion:**

Reverted. The allocation reduction was real but did not address the dominant work. Follow-up phase
profiling measured only about 1.15 ms in anchor posting scans, confirming that this loop was not the
primary tail-latency source.

### 2026-09-02 - Attempt 021: Predicate-range preflight before provenance

**Hypothesis:**

Before resolving selected DISTINCT tuples in a graph, compute the predicate matching ranges and
skip tuple resolution when the predicate has no hits.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g`.
- Reference implementation: instrumented selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: the oracle result remained exact.
- Wrapped case-insensitive DISTINCT dense latency: 59.69 ms -> 83.31 ms (0.72x).
- Graph work: 153,786 -> 1,025,390 units.
- The preflight was non-empty in 64/64 graphs even though only two graphs contributed one of the
  selected projection tuples.

**Conclusion:**

Reverted. Predicate presence is too weak a filter for selected-tuple provenance. The experiment
increased work 6.7x and made latency 40% worse.

### 2026-09-02 - Attempt 022: Persisted compact projection-tuple index

**Hypothesis:**

Persist one `(64-bit tuple hash, earliest node ID)` entry per unique four-property CallSite tuple.
Binary-search selected DISTINCT tuples and use exact four-string comparison for collisions, avoiding
front-coded string-ID resolution during provenance.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Corpus shape: 5,046,935 CallSites and 3,419,019 unique projection tuples.
- Added retained arrays: about 39.13 MiB; total retained index estimate increased 11.09%.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g`.
- Reference implementation: selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: 34/34 oracle records passed; zero failures and zero timeouts; collision, case,
  missing-value, earliest-node, v2 fallback, v3 restore, budget, and cancellation tests passed.
- Admission: 64/64 graphs.
- Wrapped case-insensitive DISTINCT dense latency: 46.059 ms -> 44.711 ms (1.03x).
- Graph work: 153,786 -> 350,214 units.

**Conclusion:**

Reverted. The primitive index removed string decoding, but binary probing charged about 13 steps per
selected tuple and increased total work 2.3x for only a 3% latency change. Phase profiling showed the
larger remaining target is the first pass that constructs the initial 200 DISTINCT rows.

### 2026-09-02 - Attempt 023: Trigram-assisted exact string-ID lookup

**Hypothesis:**

For each selected projection value, probe its sparsest existing trigram posting range and verify the
candidate string exactly, avoiding a full-table front-coded binary search without adding a new
persisted structure.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g`.
- Reference implementation: selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: 34/34 oracle records passed; zero failures and zero timeouts; ASCII collision, case,
  missing-value, short-string/Unicode fallback, cancellation, and work-denial tests passed.
- Wrapped case-insensitive DISTINCT dense latency: 50.916 ms -> 49.683 ms (1.02x).
- Graph work: 153,786 -> 255,449 units (+66%).

**Conclusion:**

Reverted. Repeated trigram-range binary searches cost more work than the front-coded lookup they
replaced and did not produce a reliable latency gain.

### 2026-09-02 - Attempt 024: Ordered rolling graph window

**Hypothesis:**

Keep source-order semantics while replacing whole-wave barriers with a bounded rolling completion
window. Probe the leading graph synchronously, schedule the next graph as soon as the ordered prefix
advances, pass the pruned source count into storage planning, and stop/cancel once `LIMIT` is known.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: three alternating paired `global-wide` runs, 34 cases per run, cold indexes, `-Xmx8g`.
- Base revision: `78ce46b` (`main` / `v2.4.7`).
- Candidate lineage: PR head `882fb90` plus the rolling-window working-tree change.
- Correctness: every candidate run passed all 34 oracle records with zero timeout/failure; graphId
  K=64 separately passed all 1,137 records byte-for-byte against base.
- Aggregate P50 speedup: 151x to 154x across the three pairs.
- Aggregate P95 speedup: 7.76x to 8.77x across the three pairs.
- K=64 graphId-set P50/P95: 10.511/24.888 ms -> 0.812/6.163 ms.
- CPU, heap, and RSS stayed within the paired 15% regression limits; observed peak workers were the
  planned 8 graph + 8 storage workers on the 16-CPU host.

**Conclusion:**

Kept as the first incremental milestone. It is materially faster and preserves correctness, source
order, cancellation, and the additive CPU bound. It does not claim the cumulative 10x P95 goal:
wrapped case-insensitive DISTINCT remains the next measured bottleneck.

### 2026-09-02 - Attempt 025: Budgeted CallSite sidecar restore

**Hypothesis:**

Restoring a persisted CallSite string index must charge the request's graph-work budget while bytes
are read, checksummed, and structurally validated. Validation should happen in the streamed read so
it does not require a second traversal, and cancellation or a consumer failure must release the
retained-memory reservation without publishing a partial index. Split-worker accounting must also
reach zero before a completed future is observable so the worker bound can be measured reliably.

**Evidence:**

- Dataset: the 64 persisted graph shards regenerated from the pinned fixture JARs, containing
  5,046,935 CallSites. All 64 v2 sidecars remained admissible after the change.
- Base revision: `e48a532befc3cf83d20501b7459f400e22c53fc1`.
- Candidate: this experiment commit.
- Correctness: targeted tests verify that restore work is charged, consumer failure at the final EOF
  check is propagated, interrupted restore preserves the interrupt/cancellation outcome, memory is
  released, and no partial index is published. A repeated split-task test verifies that active-worker
  accounting is zero as soon as execution returns.
- Validation: targeted `GraphStoreTest` cases and `:webgraph:detekt` passed; `git diff --check` was
  clean.
- Latency: not separately benchmarked because this change closes hidden budget and cancellation work;
  it does not claim a query-latency improvement.
- CPU, heap, and RSS: no standalone delta was collected. Streamed validation replaces the former
  post-read validation traversal and retains the same persisted arrays; the tests independently
  verify reservation cleanup on failure.

**Conclusion:**

Kept as a correctness and observability prerequisite. Persisted restore can no longer perform
unbudgeted validation work or swallow cancellation, and worker-peak diagnostics are deterministic.
Selected-tuple anchor grouping and adaptive raw-prefix probing are independent experiments and are
not part of this commit.

### 2026-09-02 - Attempt 026: Selected-tuple anchor grouping

**Hypothesis:**

Resolve repeated selected values and posting ranges once per graph, group selected four-property
tuples by their smallest exact posting, and scan each shared anchor only once. This should reduce
duplicate string-table lookups and posting traversal during cross-graph `RETURN DISTINCT`
provenance checks.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: `global-wide-wrapped-case-insensitive-distinct-dense`, cold indexes, `LIMIT 200`,
  `-Xmx8g`; oracle `/tmp/pr113-quick-candidate/oracle.correctness`.
- Base lineage: PR head `882fb90`; reference observation
  `/tmp/pr113-quick-candidate/anchor.tsv`.
- Candidate: the uncommitted anchor-grouping snapshot recorded in
  `/tmp/pr113-quick-candidate/candidate-final1.tsv` through `candidate-final4.tsv`.
- Correctness: candidate outputs matched the real-data oracle; focused null, encounter-order,
  predicate, limit, failure, and shared-anchor tests passed.
- Latency: the 50.916 ms reference became 39.012-50.893 ms across four quick observations. The
  variance was too large to establish a repeatable material improvement, and the slow observation
  was effectively unchanged.
- Graph work: 153,786 -> 154,058 units.
- CPU, heap, and RSS: no retained-memory change was expected, but no stable CPU or memory reduction
  was observed in the real-data run.

**Conclusion:**

Reverted. Grouping preserved correctness but did not produce a dependable latency reduction and
slightly increased measured work. The production change and its synthetic behavior tests are absent
from this docs-only commit.

### 2026-09-02 - Attempt 027: Adaptive raw prefix probe

**Hypothesis:**

For a serial CallSite query with a small `LIMIT` and a three-character lowercase `CONTAINS` term,
probe at most four times the requested row count directly from storage. Commit the raw result only
when the probe fills the limit; otherwise discard it and continue through the existing persisted
index. Dense leading matches should avoid index startup while sparse and late matches retain the
indexed path.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: the complete 34-case `global-wide` quick run with cold indexes, `LIMIT 200`, `-Xmx8g`;
  the regression was clearest in `global-wide-four-properties-dense`.
- Base lineage: PR head `882fb90`; observation
  `/private/tmp/pr113-global-wide-raw-base/observations.tsv`.
- Candidate: the uncommitted adaptive raw-prefix snapshot recorded in
  `/private/tmp/pr113-global-wide-raw-candidate/observations.tsv`; later cold/startup variants were
  recorded under `/private/tmp/pr113-global-wide-raw-cold-candidate-*` and
  `/private/tmp/pr113-global-wide-raw-startup-candidate-6da49f1`.
- Correctness: the candidate verified against the base oracle, and focused tests covered serial vs
  parallel/split selection, property choice, case sensitivity, cache reuse, sparse fallback, bounded
  work, and cancellation.
- Four-properties dense latency/work: 9.250 ms and 31,044 units in the paired base observation vs
  55.458 ms and 258,940 units in the candidate. Cold variants remained slower at 47-114 ms vs
  4.10-4.34 ms for the cold base.
- Aggregate single-shot runtime: 0.193 s -> 0.278 s in the paired base/candidate quick runs.
- CPU, heap, and RSS: no compensating reduction was observed; no standalone retained-memory change
  was expected because the probe only buffered at most `LIMIT` rows.

**Conclusion:**

Reverted. The bounded probe duplicated storage and index work on the measured workload, regressed
the dense four-property shape, and did not meet the keep threshold. The production change and its
synthetic path test are absent from this docs-only commit.

### 2026-09-02 - Attempt 028: Ordered DISTINCT leading window

**Hypothesis:**

The indexed DISTINCT path should use the same ordered leading-window policy as the retained
non-DISTINCT path. When the first graph already supplies `LIMIT 200`, launching and joining an
entire eight-graph prefix wave performs seven unnecessary projection scans before the required
all-graph provenance pass. Probe only the first graph with the storage half of the NCPU budget, then
roll graph tasks forward in source order only when more distinct rows are required.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold indexes, `LIMIT 200`, `-Xmx8g`.
- Base revision: `f87e90a`.
- Candidate: this experiment commit.
- Protocol: three local paired JVM forks in alternating candidate/base order. Files are under
  `/tmp/pr113-exp012`; the first pair ran candidate then base, the second base then candidate, and
  the third candidate then base.
- Correctness: all 34 outcomes, row counts, response sizes, and result digests matched in every
  pair; the new deterministic test proves only graph zero performs the initial dense projection,
  all 64 graphs still perform selected-tuple provenance, and the leading lookup receives the
  eight-worker storage half on a 16-CPU host.
- Aggregate P95: `210.727 -> 187.808 ms` (1.12x), `212.588 -> 147.400 ms` (1.44x), and
  `211.089 -> 183.831 ms` (1.15x).
- Wrapped case-insensitive DISTINCT dense work: `5,201,615 -> 5,070,631` units in every pair.
- Process CPU delta: `+5.1%`, `+3.2%`, and `-9.1%`.
- Peak used heap delta: `+0.6%`, `-2.6%`, and `-0.2%`; peak RSS delta: `+0.4%`, `-2.3%`, and
  `-0.4%`.
- A serial-storage leading probe was separately rejected: it raised the DISTINCT-dense latency to
  `434.704 ms`. The retained form keeps the planned eight segment workers.
- Validation: full `:cypher:test`, focused execution-path test, `:cypher:detekt`, and
  `git diff --check` passed in an isolated clone.

**Conclusion:**

Kept as an incremental optimization. It improves P95 in all three paired comparisons without
changing correctness, retained memory, or the additive 8 graph + 8 segment worker contract. It does
not by itself satisfy the cumulative 10x objective; the remaining cold selected-tuple index build
is the next measured bottleneck.

### 2026-09-02 - Attempt 029: Small selected-tuple anchor lookup

**Hypothesis:**

The DISTINCT provenance phase should not build a graph-wide exact projection-tuple hash table to
answer at most 200 selected tuples. On a cold 64-graph query, that policy scans and hashes millions
of CallSites before the first lookup. Reuse an already-built exact index, but for a cold index use
the existing exact property-posting anchor until the selected set reaches 256 tuples; only larger
sets amortize the graph-wide build.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold indexes, `LIMIT 200`, `-Xmx8g`.
- Base revision: `c64c3be` (ordered DISTINCT leading window).
- Candidate: this experiment commit.
- Protocol: three paired JVM forks in alternating candidate/base order under
  `/tmp/pr113-exp013`.
- Correctness: all 34 outcomes, row counts, response sizes, and result digests matched in every
  pair. Tests cover the small-set anchor path, the >=256 tuple exact-index path, exact predicate
  rechecks, encounter order, collision handling, and index budget/cancellation behavior.
- Aggregate P95: `189.997 -> 73.104 ms` (2.60x), `183.585 -> 58.024 ms` (3.16x), and
  `183.360 -> 70.556 ms` (2.60x).
- Wrapped case-insensitive DISTINCT dense latency: `189.997 -> 27.766 ms` (6.84x),
  `183.585 -> 26.073 ms` (7.04x), and `183.360 -> 26.508 ms` (6.92x).
- Wrapped DISTINCT dense work: `5,070,631 -> 177,117` units in every pair (-96.5%).
- Total process CPU delta: `-21.5%`, `-16.3%`, and `-24.5%`.
- Peak used heap delta: `-10.4%`, `-6.6%`, and `-11.7%`; peak RSS delta: `-18.9%`,
  `-15.5%`, and `-20.3%`.
- Validation: focused exact-index tests, full `:webgraph:test`, and `:webgraph:detekt` passed in an
  isolated clone; `git diff --check` was clean.

**Conclusion:**

Kept. This removes a cold-query index build whose cost cannot be amortized by the production
`LIMIT 200` provenance set, while retaining the exact index for larger sets and for subsequent
lookups once already built. The aggregate P95 bottleneck moves to the non-DISTINCT four-property
targeted case, which remains the next independent experiment toward cumulative 10x.

### 2026-09-03 - Attempt 030: Double graph lookahead

**Hypothesis:**

Keep the eight graph workers and eight storage workers selected from the 16 available processors,
but allow the source-ordered rolling scheduler to submit two graph-worker windows ahead. A larger
ready queue could hide a slow graph lookup without changing the active-worker budget or result
order.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: one paired `global-wide` run, 34 cases, cold indexes, `LIMIT 200`, `-Xmx8g`.
- Base revision: `3122931` with the retained one-window scheduler; observation
  `/tmp/pr113-exp014/control.tsv`.
- Candidate: the uncommitted two-window snapshot; observation
  `/tmp/pr113-exp014/lookahead2.tsv`.
- Correctness: both runs verified all 34 records against the same real-fixture oracle with zero
  timeout or failure.
- Aggregate P95: 75.130 ms -> 78.883 ms (5.0% slower).
- Wrapped case-insensitive DISTINCT dense: 26.683 ms -> 78.883 ms (2.96x slower), with identical
  177,117 work units. The extra queued provenance scans increased contention without reducing
  storage work.
- Four-property targeted: 75.130 ms -> 76.218 ms (1.4% slower), with identical 104,972 work units.
- Process CPU time fell 4.7%, peak used heap fell 2.5%, and peak RSS fell 7.7%, but those reductions
  do not compensate for the latency regression.

**Conclusion:**

Rejected. The production change is reverted. A larger speculative queue preserves the active
8 + 8 worker bound but competes with the leading DISTINCT work and does not reduce the next P95
bottleneck. The one-window scheduler remains in production.

### 2026-09-03 - Attempt 031: Rare trigram direct verification

**Hypothesis:**

When the rarest trigram leaves at most 256 candidate strings, verify those complete strings
directly instead of intersecting the candidate set against every remaining trigram. This could
reduce binary searches for long targeted terms without changing the final predicate check.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: one paired `global-wide` run, 34 cases, cold indexes, `LIMIT 200`, `-Xmx8g`.
- Base revision: `3122931`; observation `/tmp/pr113-exp014/control.tsv`.
- Candidate: the uncommitted rare-anchor snapshot; observation
  `/tmp/pr113-exp015/rare-anchor.tsv`.
- Correctness: both runs verified all 34 records against the same real-fixture oracle with zero
  timeout or failure. Focused Unicode/collision and match-cache tests also passed.
- Aggregate P95: 75.130 ms -> 75.493 ms (0.5% slower).
- Aggregate P50: 6.478 ms -> 7.213 ms (11.3% slower).
- Total work: 59,720,551 -> 59,716,915 units, only 3,636 units saved.
- Process CPU time fell 2.8%, peak heap fell 2.2%, and peak RSS fell 8.4%, but latency did not
  improve.

**Conclusion:**

Rejected. The production change is reverted. Investigation showed that the current P95 query's
104,972 units are dominated by a 100,606-unit raw scan of the serial leading graph; the remaining
indexed graphs leave too little trigram-intersection work for this optimization to matter.

### 2026-09-03 - Attempt 032: Split leading sidecar lookup

**Hypothesis:**

The leading graph should keep its synchronous, source-ordered `LIMIT` probe, but use the storage
half of the NCPU budget instead of a serial storage consumer. On a persisted graph, the split
consumer can restore and query the CallSite sidecar; the serial consumer cannot restore it and
falls back to a full raw scan.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: three alternating paired `global-wide` runs, 34 cases per run, cold indexes,
  `LIMIT 200`, `-Xmx8g`.
- Base revision: `3122931`, retaining the serial leading storage probe.
- Candidate: the split leading-probe change in this commit.
- Correctness: all six paired executions verified all 34 records against the same real-fixture
  oracle, with zero timeout or failure. The focused cross-graph suite also passed all 66 tests and
  verifies that the leading probe receives the planned segment-worker count.
- Aggregate P50 speedup: 3.27x, 3.48x, and 3.94x.
- Aggregate P95 speedup: 2.29x, 2.85x, and 3.09x.
- Aggregate P95 ranges: 60.093-74.861 ms -> 21.924-26.253 ms.
- Four-property targeted work: 104,972 -> 4,746 units. The result remains 11 rows with the same
  digest, while access/index lookup returns from 63 to all 64 graphs. The eliminated 100,226-unit
  delta is the leading graph's raw scan.
- Process CPU time fell 28.6-40.7% in every pair.
- Peak used heap ranged from -3.6% to +5.4%; peak RSS ranged from -9.2% to +4.1%. Both stay within
  the paired 15% resource limits.
- Against the three local `main` / `v2.4.7` real-64 observations (381.017-395.924 ms P95), the
  candidate's 21.924-26.253 ms range is a cumulative 14.5x-18.1x improvement. Exact-head CI remains
  authoritative for the 10x gate.
- Raw observations and JMH JSON are under `/tmp/pr113-exp016/`; the candidate and control JMH JAR
  SHA-256 values are `2934ce86234e287018b4653a208caa424c8f8b91cb546f577af5405ad71a7b33`
  and `a0aa2564e3090a0687d3bfb4889a06dec9eccbf9121473e7e878db7fb4df39ad`.

**Conclusion:**

Kept. The query still probes graph zero before scheduling later graphs and retains deterministic
source order and early `LIMIT` termination. Only the storage strategy inside that probe changes:
on the 16-CPU host it uses the planned eight segment workers, while later work remains bounded by
the separate eight graph workers.

### 2026-09-03 - Attempt 033: Preflight before sidecar restore

**Hypothesis:**

Run the existing long-`CONTAINS` string-table preflight before restoring a persisted CallSite
sidecar. An impossible term should be rejected from the much smaller string dictionary without
loading and traversing every graph's complete CallSite index.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the four pinned fixture JARs.
- Workload: the complete 34-case `global-wide` run, including the four-property zero-result case,
  cold indexes, `LIMIT 200`, and `-Xmx8g`.
- Base revision: `21feea1`, with the original sidecar-first ordering.
- Candidate: the preflight-first snapshot initially created in this commit.
- Correctness: all 34 candidate records matched the base-generated real-fixture oracle; the focused
  persisted-index test and WebGraph detekt passed.
- Base aggregate P50 / P95: 1.698 ms / 28.123 ms.
- Candidate aggregate P50 / P95: 102.966 ms / 210.746 ms.
- The zero-result P95 improved from 332.754 ms to 210.746 ms, but long targeted terms also paid a
  string-table scan on every graph before their sidecars could be restored; targeted P95 regressed
  from 22.220 ms to 216.395 ms.
- Process CPU time regressed from 3.883 s to 11.963 s. Peak used heap was effectively unchanged
  (4.11 GiB to 4.10 GiB), as was peak RSS (4.38 GiB to 4.38 GiB).
- Raw measurements are under `/tmp/pr113-exp017-pair.igK5Ew/`; the regenerated fixture is under
  `/tmp/pr113-exp017-fixture.t1A36b/`.

**Conclusion:**

Reverted. A dictionary preflight is useful only after a cheap signal establishes that the term is
unlikely to exist. Applying it unconditionally before sidecar restoration trades one zero-result
tail regression for a much larger regression across the normal targeted workload. This docs-only
commit leaves the original production and test behavior intact.

### 2026-09-03 - Attempt 034: Scoped leading serial probe

**Hypothesis:**

An explicit `graphId` set already supplies the graph-level routing decision. Keep its first graph's
bounded `LIMIT` probe serial, while retaining the NCPU-balanced graph and segment workers for later
graphs. This avoids paying segment dispatch and persisted-sidecar retention overhead before the
ordered leading source has been tested, without changing unscoped global-wide execution.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the four pinned fixture JARs.
- Workload: all `graphId(n)` and `/api/cypher/graphs` routing shapes, including the explicit K64
  graph set, with cold, warm, and startup-prepared index states.
- Base revision: `a2cf5a4`, retaining split storage for every K64 leading probe.
- Candidate: this commit's scoped-leading serial probe.
- Correctness: all 1,137 candidate records in each state matched the base-generated real-fixture
  oracle; the focused Cypher test and detekt passed.
- Explicit K64 P50 / P95:
  - cold: 0.658 / 8.699 ms -> 0.691 / 1.853 ms (4.69x P95);
  - warm: 0.436 / 0.497 ms -> 0.257 / 0.406 ms (1.22x P95);
  - startup-prepared: 0.786 / 1.828 ms -> 0.758 / 1.696 ms (1.08x P95).
- The nine K64 records consumed exactly 4,153, 1,011, and 4,153 work units in both revisions for
  cold, warm, and startup-prepared respectively.
- Whole-suite cold CPU changed from 9.969 s to 11.058 s (+10.9%); warm CPU changed from 2.181 s to
  2.290 s (+5.0%); startup-prepared CPU fell from 5.029 s to 4.839 s (-3.8%).
- Worst peak-heap change was +1.6% and worst peak-RSS change was +1.0%, within the paired 15%
  resource limits. The whole-suite P95 changed by +2.0%, from 25.585 ms to 26.088 ms.
- Raw observations and JMH JSON are under `/tmp/pr113-exp018-pair.kASeHc/`; the regenerated fixture
  is under `/tmp/pr113-exp017-fixture.t1A36b/`.

**Conclusion:**

Kept. Explicit graph scoping now avoids nested work in the ordered leading probe and retains the
balanced 8+8 plan for later K64 graphs. Unscoped global-wide queries continue to split the leading
graph, so the retained global-wide speedup is unaffected.

### 2026-09-03 - Attempt 035: Dense leading serial probe

**Hypothesis:**

For a bounded unscoped row query whose `CONTAINS` alternatives all use a term of at most three
characters, the ordered leading graph is likely dense enough to satisfy `LIMIT` directly. Probe
that graph serially and retain the balanced NCPU split for later graphs. Longer targeted and sparse
terms continue to use the persisted sidecar on the leading graph.

**Evidence:**

- Dataset: 64 persisted graph shards regenerated from the four pinned fixture JARs.
- Workload: the complete 34-case global-wide workload, cold indexes, `LIMIT 200`, and `-Xmx8g`.
- Base revision: `b004f2c`, using split storage for every unscoped leading probe.
- Candidate: the dense-leading serial snapshot initially created in this commit.
- Correctness: all 34 candidate records matched the base-generated real-fixture oracle; the focused
  Cypher test and detekt passed.
- Aggregate P50 / P95: 1.698 / 28.123 ms -> 1.841 / 24.333 ms. The 1.16x P95 observation is below
  the 2x milestone and is not large enough to separate from single-shot variance.
- Total graph work was identical at 58,014,194 units. The first zero-result query had already
  retained the persisted sidecars, so changing the later leading consumer to serial did not select
  the raw path.
- Process CPU changed from 3.883 s to 4.166 s (+7.3%). Peak used heap fell 4.9% and peak RSS fell
  1.9%; there was no compensating work reduction.
- Raw observations and JMH JSON are under `/tmp/pr113-exp019-run.zOr8ii/`; the paired base is under
  `/tmp/pr113-exp017-pair.igK5Ew/`.

**Conclusion:**

Reverted. The consumer choice cannot recover the dense raw path after a sidecar is resident, and
the observation did not meet the 2x incremental keep threshold. This docs-only commit leaves the
production and test behavior unchanged.

### 2026-09-03 - Attempt 036: Persisted trigram miss summary

**Hypothesis:** persist a small content-identity-bound Bloom summary of each graph's CallSite
trigrams beside the complete string index. A split global-wide lookup could then prove that a long
`CONTAINS` term is absent without restoring the complete sidecar, while every possible hit and
every missing, incompatible, or corrupt summary would fail open to the existing exact path.

**Evidence:**

- Dataset: 64 distinct persisted graph shards regenerated from the four pinned Android, Tika,
  Hive, and Kotlin compiler fixture JARs; all 64 summaries were present and occupied 8.3 MiB in
  total. The generated fixture is under `/tmp/pr113-exp020-fixture.saTJZd/`.
- Workload: the complete 34-case `global-wide` run, cold indexes, `LIMIT 200`, and `-Xmx8g`.
- Base revision: `e54afc8ce3eabd8bde44ffece5680a22727deef4`.
- Candidate: the production-and-test snapshot initially created in this commit, based on `e54afc8`,
  patch SHA-256 `f3949521ece3911af5fd862e3b6c62f5cb0795105e880031c3ddd60b4425f206`.
- Correctness: all 34 candidate records matched the base-generated real-fixture oracle. Focused
  tests also proved a definite miss leaves the complete index unloaded, a real hit is preserved,
  and a truncated summary falls back to the complete persisted index; WebGraph detekt passed.
- Aggregate P50 / P95 regressed from 1.656 / 61.724 ms to 1.977 / 88.945 ms. Wall time regressed
  from 571.906 to 665.246 ms.
- The first zero-result query improved from 341.870 to 62.106 ms (5.50x), but targeted P95
  regressed from 19.744 to 199.195 ms (10.09x slower). The summary avoided the first complete-index
  load, then paid those cold loads during later targeted cases instead.
- Complete-index lookups fell from 1,266 to 858, but all 64 indexes were eventually admitted in
  both runs. Work increased from 58,014,194 to 59,057,962 units and process CPU increased from
  3.506 to 3.773 s.
- Peak used heap changed from 4,412,662,064 to 4,462,808,560 bytes (+1.1%); peak RSS changed from
  4,702,355,456 to 4,739,874,816 bytes (+0.8%). The candidate therefore did not produce a memory
  benefit for this workload.
- Raw measurements are under `/tmp/pr113-exp020-quick/`. One directional real-data pair was enough
  to reject the candidate because the targeted regression was an order of magnitude and aggregate
  latency, CPU, work, heap, and RSS all moved in the wrong direction; no claim is based on this
  single pair.

**Conclusion:** reverted. The summary creates a useful 5.50x zero-result micro-improvement, but it
shifts rather than removes cold-index work across the representative query sequence and misses the
incremental keep criterion. This commit leaves production and test behavior unchanged. A follow-up
should reduce or amortize the cold hit cost, not merely defer it.

### 2026-09-03 - Attempt 037: Persisted projection-tuple miss filter

**Hypothesis:** persist a content-identity-bound Bloom filter for the exact four-property CallSite
projection tuple. During cross-graph `DISTINCT` provenance rechecks, graphs that definitely contain
none of the selected tuples could return empty without restoring or probing the complete string
index. False positives, missing files, incompatible files, and corrupt files would fail open to the
existing exact path.

**Evidence:**

- Dataset: the same 64 distinct persisted graph shards regenerated from the four pinned Android,
  Tika, Hive, and Kotlin compiler fixture JARs. All 64 candidate tuple filters were present and
  occupied 17 MiB in total. The fixture is under `/tmp/pr113-exp037-fixture.nXn4fg/`.
- Workload: the complete 34-case `global-wide` run, cold indexes, `LIMIT 200`, and `-Xmx8g`.
- Base production revision: `e54afc8ce3eabd8bde44ffece5680a22727deef4`; later revision
  `b911b6a` only reorganizes experiment documentation and does not change the benchmark or product
  code.
- Candidate: the production-and-test snapshot based on `b911b6a`, patch SHA-256
  `e3089b632cbd4cf582f8895fc1b8544472fabd141bb2794caade0ce7eca1bf97`.
- Correctness: all 34 candidate observations matched the base-generated real-fixture oracle with
  zero failures and zero timeouts. A focused synthetic correctness test additionally covered a
  definite miss, an exact hit, and corrupt-filter fallback; WebGraph detekt passed.
- Aggregate P50 changed from 1.730 to 1.754 ms (+1.4%), while aggregate P95 regressed from 41.375
  to 116.799 ms (2.82x slower). Wall time changed from 607.311 to 669.003 ms (+10.2%).
- The intended `global-wide-wrapped-case-insensitive-distinct-dense` case was itself the P95 in
  both runs and regressed from 41.375 to 116.799 ms. The filter reduced its accessed graphs from
  64 to 2, but reading and hashing every 256 KiB filter increased that case's charged work from
  172,919 to 2,124,177 units.
- Total graph work increased from 58,014,194 to 59,965,452 units (+3.4%). Process CPU changed from
  4.168 to 4.099 s (-1.6%), which is not a material improvement relative to the latency regression.
- Peak used heap fell from 4,727,621,240 to 4,411,202,272 bytes (-6.7%) and peak RSS fell from
  5,144,788,992 to 4,543,152,128 bytes (-11.7%). Those memory reductions do not compensate for the
  2.82x P95 regression.
- Paired raw observations and JMH JSON are under `/tmp/pr113-exp037-quick/`.

**Conclusion:** reverted. A 256 KiB per-graph filter replaces cheap exact rechecks with 16 MiB of
cold filter reads across 64 graphs and misses the 2x incremental latency criterion in the wrong
direction. This docs-only commit leaves production and test behavior unchanged. The next work
focuses on making the already measured 5x milestone pass its aligned-shape and compatibility gates
instead of adding another persisted summary.

### 2026-09-03 - Attempt 038: Bounded raw leading probe for dense terms

**Hypothesis:** the non-`DISTINCT` dense regressions come from using the retained CallSite index for
the ordered leading graph even though a short `CONTAINS` term fills `LIMIT 200` after only a few
hundred raw nodes. Mark only an unscoped, bounded, short-term leading probe as raw-preferred, while
leaving graph-scoped queries, longer terms, later graph waves, and `DISTINCT` execution unchanged.

**Evidence:**

- Dataset: 64 distinct persisted graph shards regenerated from the four pinned Android, Tika,
  Hive, and Kotlin compiler fixture JARs. The fixture is under
  `/tmp/pr113-exp037-fixture.nXn4fg/`; this attempt does not change its storage format.
- Workload: three paired complete 34-case `global-wide` runs, cold indexes, `LIMIT 200`, `-Xmx8g`,
  and alternating candidate/base order. Raw observations and JMH JSON are under
  `/tmp/pr113-exp038-pairs/`.
- Base revision: `15cf81b`, whose production path is byte-equivalent to `e54afc8`.
- Candidate: the production, telemetry, and correctness-test snapshot initially created in this
  commit, patch SHA-256 `46c16518e34ce6bcb590a0783de5f62d0435ada53de964f634a2119039feadbb`.
- Correctness: every candidate pair completed all 34 cases with zero failures and zero timeouts and
  matched the real-fixture oracle. Full core, Cypher, and WebGraph tests plus all three detekt tasks
  passed with `--rerun-tasks`. Synthetic tests were used only to verify consumer selection,
  persisted-sidecar bypass, bounded results, and access telemetry.
- All ten non-`DISTINCT` dense rows reduced leading-graph work from 11,496-17,783 units to 665-681
  units, except `four-properties`, which reduced 15,694 to 681. Every candidate observation
  reported exactly one accessed graph and zero index lookups for those leading raw probes.
- Wrapped case-insensitive dense latency improved from 2.184 to 1.033 ms, 2.028 to 0.959 ms, and
  2.082 to 0.891 ms: 2.11x, 2.11x, and 2.34x. The other nine affected dense rows improved or were
  effectively flat in every pair, ranging from 1.02x to 1.58x.
- Aggregate P50 changed by +1.08x, -1.02x, and +1.13x. Aggregate P95 changed from
  24.477-27.742 ms to 29.115-31.101 ms, a 12-19% regression, because the unchanged later
  `DISTINCT` row no longer inherits the leading graph's short-term index cache. This attempt is not
  an aggregate-P95 improvement and does not complete the 5x milestone by itself.
- Total graph work fell deterministically from 58,014,194 to 57,897,636 units. Process CPU changed
  by -4.1%, +0.8%, and +3.2%. Peak used heap changed by +0.6%, -0.9%, and -1.9%; peak RSS changed by
  +0.9%, -1.0%, and -2.1%. No systematic CPU or memory regression was observed.

**Conclusion:** kept as a narrow aligned-regression fix. It reaches an independently repeatable 2x
goal for wrapped dense and removes the unnecessary indexed work behind the other dense blockers,
while retaining the additive eight graph plus eight segment plan for later graphs. The remaining
5x blocker is the cold zero-result query's 64-sidecar restore; the aggregate P95 tradeoff must be
rechecked against `main` in the authoritative exact-head gate rather than claimed as a gain here.

### 2026-09-03 - Attempt 039: Batched persisted-index checksum verification

**Hypothesis:** restoring 64 persisted CallSite indexes spends avoidable CPU invoking `CRC32` once
per primitive value. Preserve the version-2 file format and all structural validation, but feed the
same little-endian checksum bytes to `CRC32` in 8 KiB blocks after each array has been read. This
should reduce the cold first-query cost without changing query planning, graph/segment parallelism,
or resident-index semantics.

**Evidence:**

- Dataset: the same 64 distinct persisted graph shards generated from the four pinned Android,
  Tika, Hive, and Kotlin compiler fixture JARs. The fixture is under
  `/tmp/pr113-exp037-fixture.nXn4fg/`; the experiment reads its existing version-2 sidecars rather
  than regenerating or substituting data.
- Workload: three paired complete 34-case `global-wide` runs, cold indexes, `LIMIT 200`, `-Xmx8g`,
  and alternating candidate/base order. Raw observations and JMH JSON are under
  `/tmp/pr113-exp039-pairs/`.
- Base revision: `0fdba6cf796fc7a2e66ce2eaad454bee77eb2be9`.
- Candidate: an isolated snapshot based on that revision, patch SHA-256
  `38ae528435b0a66ceb652e692161db4b084847c918aa13eca9f88db083d8a69a`.
- Correctness: every candidate pair completed all 34 cases successfully and matched the
  base-generated real-fixture oracle. WebGraph tests, detekt, and JMH compilation passed with
  `--rerun-tasks`.
- Aggregate P95 improved `42.669 -> 32.078 ms` (1.33x), `44.155 -> 36.393 ms` (1.21x), and
  `43.552 -> 43.464 ms` (1.00x). The effect therefore disappeared in the third independent pair
  and did not reach the 2x incremental milestone.
- The cold four-property zero row improved `337.725 -> 296.759 ms` (1.14x),
  `319.257 -> 277.838 ms` (1.15x), and `319.937 -> 285.885 ms` (1.12x). The deterministic work
  remained `57,897,636` units in both revisions because checksum batching does not eliminate any
  sidecar data or validation.
- Aggregate P50 changed `1.620 -> 1.863 ms`, `1.675 -> 1.923 ms`, and
  `1.691 -> 1.668 ms`. Process CPU changed `4.057 -> 3.610 s`, `3.698 -> 3.729 s`, and
  `3.760 -> 3.562 s`; neither metric shows a repeatable material win.
- Peak used heap changed by +0.7%, +1.3%, and +6.0%; peak RSS changed by -1.6%, +4.4%, and +6.6%.
  All remain inside the 15% resource guardrail, but there is no memory benefit.

**Conclusion:** reverted. Batching CRC updates is semantically safe and sometimes faster, but its
1.00-1.33x P95 result is too small and inconsistent to retain as a 5x milestone step. This
docs-only commit leaves the production version-2 reader unchanged. The next experiment must remove
or lazily access cold sidecar data instead of only reducing checksum call overhead.

### 2026-09-03 - Attempt 040: Persisted six-gram graph miss summary

**Hypothesis:** persist a content-identity-bound 128 KiB bitset containing one fingerprint for each
lowercased six-character CallSite property window. Before a split long-`CONTAINS` lookup restores
the complete string index, a missing fingerprint can prove that the graph cannot match. Hash
collisions, short terms, missing summaries, incompatible summaries, and corrupt summaries all fail
open to the existing exact path, so the summary cannot introduce a false negative.

**Evidence:**

- Dataset: 64 distinct persisted graph shards regenerated from the four pinned Android, Tika,
  Hive, and Kotlin compiler fixture JARs. All 64 graph directories had a 131,128-byte summary; the
  candidate fixture and provenance are under `/tmp/graphite-exp040.eHR3kO/repo/--help/`.
- Workload: three paired complete 34-case `global-wide` runs, cold indexes, `LIMIT 200`, `-Xmx8g`,
  and alternating candidate/base order. Base revision: `7198b28` production-equivalent
  `0fdba6c`; candidate: the isolated Attempt 040 snapshot. Raw observations and JMH JSON are under
  `/tmp/pr113-exp040-pairs/`.
- Correctness: fixture verification passed and every candidate run matched all 34 records in the
  base-generated real-fixture oracle. Focused tests also covered a definite miss without complete
  index admission, a real hit, and corrupt-summary fallback; WebGraph compilation and detekt
  passed.
- The first four-property zero-result query improved from `371.695 -> 55.329 ms` (6.72x),
  `369.243 -> 57.192 ms` (6.46x), and `311.770 -> 54.481 ms` (5.72x). Its charged work fell from
  `57,642,320` to `1,048,576` units.
- The optimization deferred rather than removed complete-index restoration. Subsequent targeted
  rows became the tail while their possible-hit graphs loaded complete sidecars: aggregate P95
  regressed `32.652 -> 106.739 ms`, `29.390 -> 97.803 ms`, and `32.085 -> 121.815 ms`. Aggregate
  P50 also regressed in every pair.
- Process CPU changed `3.916 -> 3.994 s`, `4.178 -> 3.662 s`, and `3.709 -> 3.843 s`, with no
  repeatable material win. The third pair's peak RSS grew from 4.41 GiB to 5.09 GiB, exceeding the
  15% resource guardrail.
- The zero-result `DISTINCT` path's early proof also exposed that access telemetry counted only 29
  graphs even though all 64 summaries were consulted. The strict gate correctly rejected this as
  insufficient exact-coverage evidence; changing telemetry alone would not repair the measured
  latency regressions.

**Conclusion:** reverted. A six-gram summary can eliminate almost all cold miss work and clears the
5x micro-goal for the initial zero-result row, but it moves sidecar loads into normal targeted
queries and makes aggregate P95 3.27-3.80x slower than the paired base. This docs-only commit leaves
production, storage, fixture, and test code unchanged. A retained follow-up must either amortize
hit-sidecar loading across the complete workload or avoid materializing the full sidecar on hits.

### 2026-09-03 - Attempt 041: Raw split scan after a positive miss-summary probe

**Hypothesis:** keep Attempt 040's six-gram negative proof, but route summary-positive graphs to the
existing segmented raw scan instead of restoring their complete persisted indexes. This should
preserve the fast cold miss while preventing sidecar restoration from moving into later targeted
queries.

**Evidence:**

- Dataset: the same 64 distinct persisted fixture-JAR graph shards used by Attempt 040; no synthetic
  graph supplied performance evidence. Base revision: production-equivalent `0fdba6c`; candidate:
  an isolated Attempt 041 snapshot. The complete 34-case screening output is under
  `/tmp/pr113-exp041-quick/`.
- Correctness: all 34 real-fixture cases matched the existing oracle.
- The four-property zero row remained fast at `59.924 ms`, but aggregate P95 was `66.986 ms` and
  process CPU was `6.848 s`, both worse than the prior current-production screening range.
- The deferred cost moved again: `global-wide-wrapped-case-insensitive-distinct-dense` took
  `399.996 ms`, and total charged work rose to `62,464,364` units. Peak used heap was `4.13 GiB`
  and peak RSS was `4.79 GiB`.

**Conclusion:** reverted after the complete screening run. Replacing a positive sidecar lookup with
a full raw scan preserves the negative proof but makes dense `DISTINCT` and CPU materially worse.
This docs-only commit leaves the Attempt 041 production-code prototype out of the branch.

### 2026-09-03 - Attempt 042: Force split raw execution for every DISTINCT shape

**Hypothesis:** the remaining Attempt 041 tail might come from mixing raw and indexed execution.
Force every eligible `DISTINCT` projection through segmented raw execution so all shapes share the
same bounded parallel path and avoid cold sidecar restoration.

**Evidence:**

- Dataset: the same 64 distinct persisted Android, Tika, Hive, and Kotlin compiler fixture-JAR
  shards. Base revision: production-equivalent `0fdba6c`; candidate: an isolated Attempt 042
  snapshot. The complete 34-case screening output is under `/tmp/pr113-exp042-quick/`.
- Correctness: all 34 cases matched the real-fixture oracle.
- Aggregate P50/P95 were `1.418/65.981 ms`; process CPU was `6.569 s`. Total charged work fell to
  `10,197,727` units, but lower accounting did not translate into a latency or CPU win.
- The four-property zero row took `62.966 ms`, while
  `global-wide-wrapped-case-insensitive-distinct-dense` still took `270.360 ms`. Peak used heap was
  `4.62 GiB` and peak RSS was `5.71 GiB`, materially above the current-production screening runs.

**Conclusion:** reverted. Forcing every DISTINCT query through raw segmentation reduces charged
work but retains a large dense tail and increases memory/CPU. This docs-only commit records the
failed hypothesis without retaining its production-code change.

### 2026-09-03 - Attempt 043: Memory-map the persisted CallSite sidecar

**Hypothesis:** the cold zero-result tail may be dominated by `DataInputStream` copies. Read the
existing persisted index through a mapped `ByteBuffer` while preserving its format, checksum,
structural validation, work accounting, cancellation, and fallback behavior.

**Evidence:**

- Dataset: 64 distinct persisted graph shards generated from the four pinned fixture JARs; no
  synthetic performance data. Base revision: `7198b28`, production-equivalent `0fdba6c`;
  candidate: an isolated Attempt 043 snapshot. The complete 34-case screening output is under
  `/tmp/pr113-exp043-quick/`.
- Correctness: all 34 cases matched the real-fixture oracle.
- Aggregate P50/P95 were `1.660/97.777 ms`; the cold four-property zero row regressed to
  `470.854 ms`. Current-production screening on the same machine had aggregate P95 around
  `29-33 ms` and the zero row around `312-372 ms`.
- Process CPU was `3.434 s`, peak used heap `4.26 GiB`, peak RSS `4.60 GiB`, and charged work
  `57,897,636` units. Avoiding stream copies did not remove the validation work.

**Conclusion:** reverted after screening. Memory mapping changes how bytes arrive but leaves the
dominant per-value validation and checksum costs intact, and latency regressed. This commit records
only the experiment; the mapped-reader prototype is not retained.

### 2026-09-03 - Attempt 044: Bulk-read the persisted sidecar before parsing

**Hypothesis:** a single `Files.readAllBytes` followed by `ByteBuffer` parsing may reduce cold-read
overhead while retaining the version-2 checksum, structural validation, exact results, and corrupt
sidecar fallback.

**Evidence:**

- Dataset: the same 64 distinct persisted fixture-JAR graph shards. Base revision: `7198b28`,
  production-equivalent `0fdba6c`; candidate: the isolated Attempt 044 snapshot. The complete
  34-case screening output is under `/tmp/pr113-exp044-quick/`.
- Correctness: all 34 cases matched the real-fixture oracle.
- Aggregate P50/P95 were `1.839/31.140 ms`, and the cold four-property zero row was `353.135 ms`.
  Both are within the observed current-production range rather than a repeatable 2x improvement.
- Process CPU was `3.935 s`, peak used heap `4.16 GiB`, peak RSS `4.53 GiB`, and charged work
  remained `57,897,636` units. The full byte arrays add transient memory without eliminating the
  per-value validation pass.

**Conclusion:** reverted. Bulk input alone is effectively flat and adds a large transient
allocation path, so it is not a useful 2x/5x milestone step. The production reader remains
streaming; this docs-only commit preserves the negative result.

### 2026-09-03 - Attempt 045: Profile writer-trust without integrity validation

**Hypothesis:** temporarily remove sidecar integrity and posting-order validation in an isolated
profiling build to measure their upper-bound cost. This is deliberately unsafe and can only locate
the next optimization target; it can never pass the correctness hard gate or be retained.

**Evidence:**

- Dataset: the same 64 distinct persisted Android, Tika, Hive, and Kotlin compiler fixture-JAR
  shards. Base revision: `7198b28`, production-equivalent `0fdba6c`; candidate: an isolated,
  explicitly unsafe Attempt 045 profiling snapshot. Output is under `/tmp/pr113-exp045b-quick/`.
- The uncorrupted workload happened to match all 34 oracle records, but corruption detection was
  absent by construction. Therefore this is profiling evidence, not correctness evidence.
- Aggregate P50/P95 were `1.859/31.671 ms`; the cold four-property zero row fell to `178.642 ms`,
  compared with the current-production screening range of `312-372 ms`. Process CPU fell to
  `2.742 s`; peak used heap was `4.51 GiB`, peak RSS `4.56 GiB`, and charged work `57,897,636`.
- A query-phase JFR sample attributed 36 of 121 samples to posting `nodeOrder` validation, with
  further samples in per-value checksum updates and stream reads. The unsafe upper bound therefore
  identifies validation, rather than query matching or graph scheduling, as the cold-tail target.

**Conclusion:** reverted unconditionally because it cannot detect corrupted sidecars. The result
justifies testing an authenticated/checksummed writer-trust format that validates the complete byte
stream before publishing the index while avoiding redundant random `nodeOrder` checks.

### 2026-09-03 - Attempt 046: SHA-256-authenticated writer-trust sidecar

**Hypothesis:** version the persisted index with a SHA-256 trailer over every preceding byte. Once
the complete stream and graph content identity match, trust writer-produced posting order and skip
redundant random `nodeOrder` validation. Keep streaming reads, memory reservation, work accounting,
cancellation, corrupt fallback, and version-2 compatibility.

**Evidence:**

- Dataset: 64 distinct persisted shards freshly regenerated from the four pinned fixture JARs at
  `/tmp/pr113-exp046-fixture64/`. Base revision: `7198b28`, production-equivalent `0fdba6c`;
  candidate: the isolated Attempt 046 snapshot. Complete 34-case output is under
  `/tmp/pr113-exp046-quick/`.
- Correctness: all 34 real-fixture cases matched the existing oracle. Focused tests passed for
  SHA-corruption fallback, lazy-restore work accounting, cancellation without publication, and
  legacy version-2 CRC restoration. Compilation and detekt also passed.
- Aggregate P50/P95 were `1.702/66.947 ms`, and the cold four-property zero row took `392.370 ms`.
  This is worse than the current-production screening ranges of about `1.6-1.9/29-44 ms` and
  `312-372 ms`, respectively.
- Process CPU was `4.345 s`, peak used heap `4.09 GiB`, peak RSS `4.23 GiB`, and charged work
  `57,897,572` units. SHA-256 replaced enough of the removed validation cost to erase the expected
  latency gain.

**Conclusion:** reverted after the complete screening run. Strong stream authentication preserves
the hard correctness gate but does not produce a net speedup on this workload. This docs-only
commit leaves the production version-2 format and reader unchanged.

### 2026-09-03 - Attempt 047: Bulk stream CRC writer-trust sidecar

**Hypothesis:** replace Attempt 046's SHA-256 trailer with a raw-byte CRC consumed below the buffered
reader. Preserve complete-stream corruption detection, content identity, bounds checks, work and
cancellation behavior, legacy version-2 support, and publication only after checksum success, while
removing the SHA cost and redundant writer-order validation.

**Evidence:**

- Dataset: 64 distinct persisted shards freshly regenerated from the four pinned fixture JARs at
  `/tmp/pr113-exp047-fixture64/`. Base revision: `7198b28`, production-equivalent `0fdba6c`;
  candidate: the isolated Attempt 047 snapshot. The complete 34-case output is under
  `/tmp/pr113-exp047-quick/`.
- Correctness: all 34 real-fixture cases matched the oracle. Focused corrupt-sidecar, work-budget,
  cancellation, and legacy version-2 tests passed, as did compilation and detekt.
- Aggregate P50/P95 were `1.591/37.134 ms`; the cold four-property zero row took `283.645 ms`.
  These do not establish a repeatable 2x improvement over current-production screening ranges of
  about `1.6-1.9/29-44 ms` and `312-372 ms`.
- Process CPU was `3.367 s`, peak used heap `4.08 GiB`, peak RSS `4.21 GiB`, and charged work
  `57,897,572` units. The resource figures are acceptable but do not compensate for the missing
  latency milestone.

**Conclusion:** reverted. Bulk CRC is cheaper than SHA-256 but still does not deliver a stable 2x
step or independently remove the exact-head aligned latency blocker. This docs-only commit retains
neither the new persistence version nor the trusted-order reader.

### 2026-09-03 - Attempt 048: Re-evaluate miss summary against main with exact coverage telemetry

**Hypothesis:** Attempt 040 was rejected against the already-optimized candidate, but its very fast
zero-result proof may still improve the cumulative `main` comparison and remove the exact-head zero
regression. Reapply only the false-positive six-gram summary, count every summary consultation as a
graph access, and compare it directly with remote `main` rather than retaining the positive raw-scan
experiments.

**Evidence:**

- Dataset: the same 64 distinct persisted graph shards generated from the four pinned Android,
  Tika, Hive, and Kotlin compiler fixture JARs. Base revision: remote `main` at `78ce46b`;
  candidate: an isolated Attempt 048 snapshot based on `305a181`. The 16-CPU outputs are under
  `/tmp/pr113-exp048-v-main/` and `/tmp/pr113-exp048-quick/`; the CI-shaped four-CPU pair is under
  `/tmp/pr113-exp048-cpu4/`.
- Correctness: both candidate runs completed all 34 cases and matched the real-fixture oracle.
  The previously missing zero-query coverage now reports all 64 graph IDs. Focused tests passed for
  a definite miss without full-index admission, a real hit, corrupt-summary fallback, and persisted
  summary creation; compilation, JMH compilation, and detekt passed.
- On 16 available CPUs, `main -> candidate` P50/P95 was `242.846/417.798 -> 2.424/119.601 ms`:
  `100.17x` P50 but only `3.49x` P95. The first four-property zero row improved
  `1660.332 -> 70.195 ms`, but later targeted and dense sidecar restores set the tail.
- With `-XX:ActiveProcessorCount=4`, which produces the same additive two graph plus two segment
  plan as the hosted runner, P50/P95 was `247.876/449.377 -> 2.689/194.371 ms`: `92.19x` P50 but
  only `2.31x` P95. Candidate process CPU was `3.068 s` versus main's `11.126 s`; peak heap/RSS were
  `4.71/4.98 GiB` versus `4.64/5.73 GiB`, so resources improved while the latency gate still failed.

**Conclusion:** reverted. Exact access evidence is repaired and the summary is highly effective for
zero misses, but deferring full sidecar loads makes cumulative P95 miss the 5x goal on both CPU
plans. This docs-only commit does not retain the summary format or reader path.

### 2026-09-03 - Attempt 049: Split persisted posting-order validation

**Hypothesis:** JFR attributed the largest cold sidecar restore sample group to random `nodeOrder`
checks. Preserve the version-2 checksum and every structural/order invariant, but read bounded node
IDs first and validate their posting order in two pieces: one on each graph worker and one on the
shared segment pool. With concurrent graph scans this continues to use the additive NCPU allocation,
not graph-by-segment multiplication.

**Evidence:**

- Dataset: the same 64 distinct persisted graph shards generated from the four pinned Android,
  Tika, Hive, and Kotlin compiler fixture JARs. Base revision: `9cf52fc`, production-equivalent
  `0fdba6c`; candidate: an isolated Attempt 049 snapshot. The complete 34-case screening output is
  under `/tmp/pr113-exp049-quick/`.
- Correctness: all 34 cases matched the real-fixture oracle. Focused tests for corrupt-sidecar
  fallback, lazy restore work accounting, and interruption without publication passed; compilation,
  JMH compilation, and detekt passed.
- The observed worker peaks remained the required additive `8 graph + 8 segment` on 16 CPUs.
  Aggregate P50/P95 were `1.755/32.334 ms`, the cold four-property zero row was `389.198 ms`, and
  process CPU was `4.105 s`. These are flat or worse than the current-production screening ranges
  of about `1.6-1.9/29-44 ms`, `312-372 ms`, and `3.7-4.2 s` respectively.
- Peak used heap was `4.37 GiB`, peak RSS `4.73 GiB`, and charged work remained `57,897,636` units.
  Moving validation to the shared pool does not remove work and adds queue/join overhead.

**Conclusion:** reverted after the complete screening run. Parallel posting-order validation does
not provide a repeatable 2x step and makes the cold zero row slower. This docs-only commit leaves
the serial version-2 validator unchanged.

### 2026-09-03 - Attempt 050: Bulk sidecar decode with parallel property validation

**Hypothesis:** remove `DataInputStream`'s per-primitive decode overhead by bulk-reading the existing
version-2 sidecar, copying each primitive array through `IntBuffer` / `LongBuffer`, and validating
the four property CSR sections concurrently with the shared segment half of the NCPU plan. Preserve
the existing content identity, CRC, bounds, posting-order, work-budget, cancellation, and corrupt
sidecar fallback checks.

**Evidence:**

- Dataset: the same 64 distinct persisted graph shards generated from the four pinned Android,
  Tika, Hive, and Kotlin compiler fixture JARs. Base revision: `74f479d`, production-equivalent
  `0fdba6c`; candidate: an isolated Attempt 050 snapshot under
  `/tmp/graphite-exp050.X8t1Y0/repo`. The complete 34-case screening output is under
  `/tmp/pr113-exp050-quick/`.
- Correctness: all 34 result records matched the preceding real-fixture reference on outcome, row
  count, response bytes, digest, and hit graph IDs. Focused tests passed for lazy persisted restore,
  corrupt/trailing sidecar fallback and atomic rebuild, graph-identity rejection, exact work
  charging, and interruption without publication. Compilation and detekt also passed.
- The observed worker peaks remained the required additive `8 graph + 8 segment` on 16 CPUs.
  Aggregate P50/P95 were `1.755/38.855 ms`, compared with the immediately preceding production
  screening's `1.755/32.334 ms`. P95 therefore regressed by 20.2% instead of reaching the 2x keep
  threshold.
- The cold four-property zero row improved from `389.198` to `308.903 ms` (1.26x), while process
  CPU was effectively flat at `4.093 s` versus `4.105 s`. Charged work remained exactly
  `57,897,636` units.
- Peak used heap was `4.56 GiB` and peak RSS was `4.86 GiB`, versus `4.37/4.73 GiB` in the preceding
  production screening. Bulk byte arrays add transient allocation without producing a material
  CPU or tail-latency benefit.

**Conclusion:** reverted after the complete screening run. Bulk primitive decode plus parallel
structural validation provides only a 1.26x cold-row micro-improvement and regresses aggregate P95.
This docs-only commit leaves the streaming version-2 reader unchanged.

### 2026-09-03 - Attempt 051: Larger persisted six-gram miss summary

**Hypothesis:** Attempt 040's 128 KiB-per-graph miss summary may admit too many false positives and
defer full sidecar restoration into later rows. Increase the false-positive-only summary to 2 MiB
per graph, retaining the same six-character windows, content identity, checksum, fail-open
semantics, and exact fallback. The 128 MiB fixture-wide footprint remains small relative to the
required 8 GiB max heap and should trade available memory for lower cold latency.

**Evidence:**

- Dataset: 64 distinct persisted graph shards freshly regenerated from the four pinned Android,
  Tika, Hive, and Kotlin compiler fixture JARs. All 64 summary files were present and occupied
  129 MiB in total. The candidate fixture is under `/tmp/pr113-exp051-fixture64/`; no synthetic
  graph supplied performance evidence.
- Base revision: `d9cea9c`, production-equivalent `0fdba6c`; candidate: an isolated snapshot under
  `/tmp/graphite-exp051.MPD8ia/repo`. The complete 34-case screening output is under
  `/tmp/pr113-exp051-quick/`.
- Correctness: the candidate's 34-record manifest exactly matched the latest trusted real-fixture
  base oracle from CI run `33671076750`. Focused tests covered a definite miss without complete
  index admission, a real hit, corrupt-summary fallback, and persisted summary creation;
  compilation, JMH compilation, and detekt passed.
- The first four-property zero row improved from `389.198` to `120.739 ms` (3.22x), but aggregate
  P50/P95 regressed from `1.755/32.334` to `2.255/133.659 ms`. The intended blocker moved below the
  tail while later targeted and dense rows became the new P95.
- Complete-index lookups fell from `1,256` to `361`, proving that the larger summary reduced false
  positives. Nevertheless, the representative workload's real terms are distributed across the
  complete graph set, so all 64 full indexes were eventually admitted in both runs. Charged work
  increased from `57,897,636` to `74,633,951` units (+28.9%).
- Process CPU increased from `4.105` to `4.429 s`; peak used heap changed from `4.37` to `4.60 GiB`
  and peak RSS from `4.73` to `5.17 GiB`. The additive `8 graph + 8 segment` worker peaks and all
  result digests remained intact.

**Conclusion:** reverted. Spending 128 MiB on a more selective summary fixes the isolated cold miss
but cannot remove the real sidecar loads required by the coverage workload, and aggregate P95
regresses by more than 4x. This docs-only commit leaves the production storage format unchanged;
the remaining aligned blocker requires lazy access to positive sidecar data, not a larger miss
filter.

### 2026-09-03 - Attempt 052: Persist an exact string-level trigram prefilter

**Hypothesis:** persist, in a separate raw-CRC memory-mapped file, the sorted string IDs used by
each CallSite property and sorted trigram-to-string postings. A query can then prove an exact
case-insensitive `CONTAINS` miss, or identify a possible matching string, before restoring the much
larger node-posting sidecar. This should reduce cold positive and negative query cost while retaining
exact fallback, content-identity checks, cancellation, work accounting, and the additive NCPU plan.

**Evidence:**

- Dataset: 64 distinct persisted graph shards freshly regenerated from the four pinned Android,
  Tika, Hive, and Kotlin compiler fixture JARs at `/tmp/pr113-exp052-fixture64/`; no synthetic graph
  supplied performance evidence. The 64 prefilter files occupied `246.86 MiB` in total.
- Base revision: `369ace4`, production-equivalent `0fdba6c`; candidate: an isolated Attempt 052
  snapshot under `/tmp/graphite-exp052.HeDoN8/repo`. Complete final output is under
  `/tmp/pr113-exp052-direct-verify/`.
- Correctness: the final candidate completed all 34 cases and matched the trusted real-fixture CI
  oracle on outcome, row count, response bytes, digest, and hit graph IDs. The focused mapped
  persistence test, JMH compilation, and detekt passed.
- Three reader variants all missed the keep threshold. Full posting-order validation produced
  aggregate P50/P95 `1.719/119.310 ms`; trusting the versioned writer after raw-byte CRC produced
  `1.583/122.367 ms`; decoding and exactly checking each rarest-trigram candidate directly produced
  `1.580/126.126 ms`. The current-production screening reference was `1.755/32.334 ms`, so the final
  candidate regressed P95 by `3.90x` instead of improving it by 2x.
- In the final variant the four-property zero row was `45.729 ms`, but positive targeted and dense
  rows replaced it in the tail: four-properties-targeted was `126.126 ms` and wrapped dense
  DISTINCT was `208.743 ms`. All 64 complete indexes were still eventually admitted because real
  coverage terms occur throughout the graph set.
- The final run observed the required additive `8 graph + 8 segment` worker peaks on 16 available
  CPUs and averaged about `6.00` CPU cores. Process CPU was `4.796 s`, peak used heap `3.75 GiB`,
  peak RSS `4.98 GiB`, and charged work `90,443,307` units, versus `57,897,636` units in the
  production screening reference.

**Conclusion:** reverted. The exact prefilter makes isolated misses cheap, but its 247 MiB mapped
footprint, checksum/read cost, property membership checks, and exact candidate decoding add more
work than they remove for positive real-data queries. It also cannot prevent all 64 full indexes
from being needed by this coverage workload. This docs-only commit retains neither the new file
format nor the reader/writer prototype.

### 2026-09-03 - Attempt 053: Scan raw CallSites by exact prefilter string IDs

**Hypothesis:** retain Attempt 052's CRC-protected mapped trigram dictionary, but use its verified
positive result instead of treating it only as a preflight. Resolve each predicate to exact
property-specific string IDs, then let the existing shared segment pool compare those integer IDs
while scanning raw CallSite records. This should eliminate complete node-posting sidecar restores
for both misses and hits while preserving exact string verification, deterministic row order,
content identity, corruption fallback, work accounting, cancellation, and the additive NCPU split.

**Evidence:**

- Dataset: 64 distinct persisted graph shards freshly regenerated from the four pinned Android,
  Tika, Hive, and Kotlin compiler fixture JARs at `/tmp/pr113-exp053-fixture64-v1/`; no synthetic
  graph supplied performance evidence. The 64 CRC-protected dictionaries occupied `247 MiB`.
  Fixture provenance now records each dictionary's byte size and SHA-256, and the candidate verifier
  independently accepted all 64 21-field provenance records.
- Base revision: remote `main` at `78ce46b`; candidate: this Attempt 053 snapshot based on
  `db90513`. The four-CPU main reference is under `/tmp/pr113-exp048-cpu4/`; three candidate runs
  are under `/tmp/pr113-exp053-paired-candidate-{1,2,3}/`.
- Correctness: every candidate run completed all 34 real-fixture cases and matched the trusted CI
  oracle on outcome, row count, response bytes, digest, and hit graph IDs, with zero failures and
  zero timeouts. `GraphStoreTest` passed all 160 tests, including exact hit, exact DISTINCT,
  property isolation, lifecycle reset, and corrupt-dictionary full-index fallback; WebGraph detekt
  and all 86 benchmark-gate tests passed.
- With `-XX:ActiveProcessorCount=4`, candidate P95 was `49.766`, `62.949`, and `53.119 ms` versus
  main's `449.377 ms`: `9.03x`, `7.14x`, and `8.46x`, so every run clears the 5x milestone. Median
  candidate P50 was `2.402 ms` versus `247.876 ms` (`103.18x`). Median maximum latency was
  `384.112 ms` versus `481.268 ms`; the slowest individual query therefore also improved, although
  it did not improve by 5x.
- Median four-CPU process CPU was `2.842 s` versus main's `11.126 s`; peak used heap was
  `4.03 GiB` versus `4.64 GiB`, peak RSS was `5.33 GiB` versus `5.73 GiB`, and charged work was
  `40,259,720` versus `109,198,717` units. No complete CallSite index was admitted or queried.
- A production-fixture run with 16 available CPUs observed exactly `8 graph + 8 segment` worker
  peaks. It completed 34/34 cases with P50/P95 `1.918/49.448 ms`, maximum `242.776 ms`, process
  CPU `5.624 s`, peak used heap `4.41 GiB`, peak RSS `5.74 GiB`, and zero failures/timeouts. The
  graph caller can process one segment inline, so the logical CallSite scan peak is nine while the
  two physical executor budgets remain eight plus eight.

**Conclusion:** keep. Returning exact matching string IDs and consuming them in the segmented raw
scan removes the positive-query sidecar restores that defeated Attempt 052. All three real-data
four-CPU runs exceed the current 5x P95 milestone, CPU/work/heap improve, 16 CPUs realize the
required additive `8 + 8` plan, and correctness remains a hard gate. Missing, incompatible, or
corrupt dictionaries continue to fail open to the complete exact index path.

### 2026-09-03 - Attempt 054: Replace duplicated postings with an on-demand range directory

**Hypothesis:** Attempt 053 pays a cold whole-file CRC over a 247 MiB duplicate of the trigram
postings and fails the persisted-footprint gate. Keep the exact string-ID raw-scan path, but store
only a compact checksummed `(trigram, end offset, range CRC32)` directory. Memory-map the postings
already present in `graph.callsite-string-index`, validate the 5.85 MiB directory in bounded chunks,
and validate only the posting range selected by each query. This should remove both the duplicate
storage and the first-query full scan without weakening corruption fallback, work accounting, or
cancellation.

**Evidence:**

- Dataset: the same 64 distinct persisted shards generated from the four pinned Android, Tika,
  Hive, and Kotlin compiler fixture JARs. For local screening, version-2 directories were
  deterministically regenerated from the production exact indexes in
  `/tmp/pr113-exp054-fixture64-screen/`; no synthetic graph supplied performance evidence. Base
  revision: `00d9681`; candidate: the isolated Attempt 054 snapshot under
  `/tmp/graphite-exp054.PcRFQe/repo`. Complete output is under
  `/tmp/pr113-exp054-screen-run1/`.
- Correctness: all 34 records exactly matched the trusted Attempt 053 real-fixture result on
  outcome, row count, response bytes, digest, and hit graph IDs. Focused tests also passed for an
  exact hit, property isolation, DISTINCT, whole-directory corruption fallback, queried-range
  corruption fallback, deterministic work rejection, interruption, and no publication after a
  rejected directory load.
- The 64 directories occupy `5.85 MiB`, down from `247 MiB` (`42.2x` smaller); postings remain only
  in the existing 352 MiB exact indexes. The first four-property zero row was `46.475 ms` with
  `1,277,757` charged units, versus the exact remote Attempt 053 cold result of `619.492 ms` and
  `32,356,657` units (`13.33x` latency and `25.32x` work improvement).
- The complete four-CPU screening run produced P50/P95 `1.998/57.225 ms`, process CPU `3.172 s`,
  peak used heap `4.09 GiB`, peak RSS `5.13 GiB`, and `9,052,840` charged units. The comparable
  warm-cache Attempt 053 local run was `2.402/49.766 ms`, `2.794 s`, `3.97/5.26 GiB`, and
  `40,259,720` units. Directory/range validation therefore removes the cold and footprint blockers,
  while small-directory validation adds some CPU and does not improve the warm-cache P95.
- Wrapped dense DISTINCT remains the aligned blocker at `877.708 ms`; it performs complete
  selected-tuple provenance scans over the remaining graphs. Consequently the overall comparison
  against main is above 5x, but the every-shape 5x gate is not yet established.

**Conclusion:** keep as an independently useful step toward 5x. It removes 241 MiB of duplicated
persisted data, bounds validation/cancellation work, and eliminates the exact remote cold outlier
without changing results. The next attempt targets the separate dense-DISTINCT provenance scan;
this attempt does not claim that the complete 5x gate has passed.

### 2026-09-03 - Attempt 055: Prune DISTINCT provenance by exact property string membership

**Hypothesis:** after the first graph supplies 200 DISTINCT tuples, the provenance pass scans every
remaining graph even when one or more tuple strings do not occur in that graph's projected CallSite
property. Convert selected values to each graph's local string IDs before starting a raw scan. Use
the compact directory's checksums to validate and mmap the existing four sorted property-ID
sections, then reject impossible tuples by exact property membership. Compare integer tuples during
the remaining scans and decode strings only for actual selected hits.

**Evidence:**

- Dataset: the same 64 distinct persisted shards generated from the four pinned Android, Tika,
  Hive, and Kotlin compiler fixture JARs, under `/tmp/pr113-exp054-fixture64-screen/`. The compact
  directories remain `5.85 MiB` total and contain only four additional checksums, not copied IDs.
  Base revision: `b094c45`; candidate: the isolated Attempt 055 snapshot under
  `/tmp/graphite-exp054.PcRFQe/repo`. The three complete four-CPU runs are under
  `/tmp/pr113-exp055-property-run{1,2,3}/`.
- Correctness: every run completed all 34 real-fixture cases and exactly matched the trusted oracle
  on outcome, row count, response bytes, digest, and hit graph IDs. The focused test proves that a
  selected value present elsewhere in the graph dictionary but absent from the requested CallSite
  property returns no rows without starting a segment scan. Corrupt directory/range fallback,
  work rejection, interruption, compilation, and detekt also pass.
- Candidate P50/P95 across the three JVMs was `2.197/56.048`, `2.267/58.401`, and
  `2.284/58.857 ms`. Against the three exact remote-main pairs, P95 speedup is `15.41x`, `12.84x`,
  and `15.67x`; the wrapped DISTINCT speedup is `7.45x`, `7.13x`, and `9.18x`. This establishes the
  5x latency milestone for the aggregate and both required wrapped shapes in every measured pair.
- Dense DISTINCT fell from Attempt 054's `877.708 ms` to `149.546`, `148.633`, and `140.667 ms`.
  Only three per-query raw scans remain, and complete-run scanned graph count fell from 64 in
  Attempt 054 to 20. Charged work fell from `9,052,840` to `5,697,204` units.
- Process CPU was `1.777-1.855 s`, peak used heap `3.67-3.67 GiB`, and peak RSS
  `4.10-4.13 GiB`, all below Attempt 054's `3.172 s`, `4.09 GiB`, and `5.13 GiB`.
- The complete comparison is not yet clean: the `localized-early` dense row repeats a >15% and
  >1 ms aligned regression in two pairs. Its four equal-term predicates currently decode the same
  property-independent trigram range four times.

**Conclusion:** keep. Exact selected-tuple pruning delivers a stable greater-than-5x milestone,
materially reduces CPU/work/heap, and preserves exact provenance. A separate follow-up attempt will
deduplicate same-term prefilter lookup to remove the remaining aligned small-query regression; this
record does not claim that every regression-gate condition passes.

### 2026-09-03 - Attempt 056: Reuse property-independent exact-term candidates

**Hypothesis:** a four-property OR repeats the same transform, match mode, and term, while the
compact prefilter's candidate string IDs are intentionally property-independent. Resolve that
predicate key once per graph query and reuse the immutable result for all four properties. Keep
property membership exact at the selected-tuple pruning boundary, and record each DISTINCT storage
entry as a graph access even when the prefilter proves a miss without starting a segment scan.

**Evidence:**

- Dataset: the same 64 distinct persisted shards generated from the four pinned Android, Tika,
  Hive, and Kotlin compiler fixture JARs under `/tmp/pr113-exp054-fixture64-screen/`; no synthetic
  graph supplied performance evidence. Base revision: `3ffe4d2`; candidate: the isolated
  Attempt 056 snapshot under `/tmp/graphite-exp054.PcRFQe/repo`. Three complete candidate runs are
  under `/tmp/pr113-exp056-final-run{1,2,3}/`; the exact comparator output is
  `/tmp/pr113-exp056-final-status.json` and `/tmp/pr113-exp056-final-report.md`.
- Correctness: all three runs completed 34/34 cases and exactly matched the trusted real-fixture
  oracle on outcome, row count, response bytes, digest, and hit graph IDs. A focused work-accounting
  assertion proves one predicate and four property variants of the same absent predicate consume
  identical prefilter work. GraphStore tests, compilation, and detekt pass.
- The unmodified repository comparator passed with zero errors when pairing the three exact remote
  main results with the three candidate runs. Aggregate P95 speedup was `13.43x`, `11.77x`, and
  `15.84x`; the worst required wrapped-shape speedup was `14.47x`, `11.61x`, and `21.18x`.
- Candidate P50/P95 was `2.202/64.341`, `2.352/63.670`, and `2.669/58.227 ms`. Dense DISTINCT fell
  again from Attempt 055's `140.667-149.546 ms` to `60.968-91.289 ms`. `localized-early` measured
  `7.793`, `9.337`, and `9.511 ms`; only one pair crossed the aligned-regression threshold, so the
  repeated-regression gate is clear.
- Zero-result and dense DISTINCT rows both report all 64 exact graph accesses. The zero row consumes
  only 99 units once directories are mapped, dense DISTINCT consumes `354,654`, and complete-run
  work falls from `5,697,204` to `4,915,713` units.
- Process CPU was `1.544-1.619 s`, peak used heap `3.46-3.57 GiB`, and peak RSS
  `3.93-4.00 GiB`, improving again over Attempt 055 without increasing persisted size.

**Conclusion:** keep. Same-term candidate reuse removes the remaining repeated aligned regression,
preserves complete 64-graph access evidence, and passes the exact three-pair 5x comparator with no
errors. This is local screening against the exact remote-main artifacts; hosted CI must still
regenerate all 64 graphs from the pinned JARs and independently confirm the result.

### 2026-09-03 - Attempt 057: Fixed-size posting chunks

**Hypothesis:** replace Attempt 056's per-trigram range directory with fixed 16,384-posting chunks.
Each record stores the chunk's minimum/maximum trigram, end offset, and CRC32. The bounded record
count should fit the large-corpus 4 KiB persisted-footprint tolerance while retaining selective
corruption detection.

**Evidence:**

- Dataset: the same 64 distinct persisted shards generated from the four pinned Android, Tika,
  Hive, and Kotlin compiler fixture JARs under `/tmp/pr113-exp054-fixture64-screen/`; no synthetic
  graph supplied performance evidence. Base revision: `06a4e5d`; candidate: the isolated prototype
  under `/tmp/graphite-exp057.YxAwwD/repo`. Output is under `/tmp/pr113-exp057-run1/`.
- Correctness: 34/34 real-fixture records matched the Attempt 056 oracle exactly. The focused
  queried-chunk corruption fallback test also passed.
- The 64 directories occupied 37,520 logical bytes total, with a 752-byte maximum shard, so the
  layout solved the footprint bound.
- Performance regressed: P50/P95 was `10.875/199.503 ms`, process CPU was `3.307 s`, peak used heap
  was `4.19 GiB`, peak RSS was `4.64 GiB`, and charged work rose from Attempt 056's `4,915,713` to
  `45,093,694` units. Absent trigrams that fall between a chunk's endpoints require reading and
  checksumming the complete 16,384-posting chunk.

**Conclusion:** reject. Fixed posting chunks satisfy the storage bound but spend too much work
proving sparse and absent terms. The production tree does not retain this layout.

### 2026-09-03 - Attempt 058: Fixed trigram-hash buckets

**Hypothesis:** use 496 fixed buckets over the complete three-character hash domain and persist
only `(end offset, CRC32)` per bucket. The query computes its bucket in O(1), while the complete
directory is a constant 4,064 bytes regardless of graph size.

**Evidence:**

- Dataset: the same 64 pinned-fixture-JAR shards under
  `/tmp/pr113-exp054-fixture64-screen/`. Base revision: `789ea0e` with Attempt 056 production code;
  candidate: the isolated prototype under `/tmp/graphite-exp057b.pY2dWb/repo`. Output is under
  `/tmp/pr113-exp057b-run1/`.
- Correctness: 34/34 real-fixture records matched the oracle exactly, and each sidecar was exactly
  4,064 bytes.
- Performance was substantially worse: P50/P95 was `307.878/367.759 ms`, process CPU was
  `15.650 s`, peak used heap was `4.70 GiB`, peak RSS was `4.95 GiB`, and charged work was
  `715,922,159` units.
- Root cause: the theoretical UTF-16 trigram hash domain is about 65 million values, but real Java
  class and method names occupy a narrow ASCII-heavy region. Equal-width hash buckets are therefore
  extremely skewed and make common buckets almost equivalent to a whole-posting scan.

**Conclusion:** reject. Constant-size equal-width buckets meet the footprint limit but do not
balance real data. The production tree does not retain this layout.

### 2026-09-03 - Attempt 059: Bounded balanced posting chunks

**Hypothesis:** cap the directory at 333 records but divide each graph's sorted trigram postings
into equal-count chunks. Persist `(maximum trigram, end offset, CRC32)` per chunk. Binary-search the
maximum keys and validate only the one or adjacent chunks that may contain the selected trigram.
This keeps the directory within 4 KiB without the skew of Attempt 058 or the coarse fixed chunk
size of Attempt 057.

**Evidence:**

- Dataset: the same 64 distinct persisted shards generated from the four pinned Android, Tika,
  Hive, and Kotlin compiler fixture JARs under `/tmp/pr113-exp054-fixture64-screen/`; no synthetic
  graph supplied performance evidence. Base revision: `c7579df` with Attempt 056 production code;
  candidate: the isolated Attempt 059 snapshot under `/tmp/graphite-exp057c.zBNfP3/repo`. Three
  complete four-CPU runs are under `/tmp/pr113-exp057c-run{1,2,3}/`; exact comparison output is
  `/tmp/pr113-exp057c-status.json` and `/tmp/pr113-exp057c-report.md`.
- Correctness: all three runs completed 34/34 real-fixture cases and matched the remote-main oracle
  exactly on outcome, row count, response bytes, digest, and hit graph IDs. Focused tests cover
  exact hits, property isolation, same-term OR reuse, directory corruption, selected-chunk
  corruption, deterministic work rejection, and interruption.
- Each 64-shard directory is 4,092 bytes and the binary format cannot exceed 4,092 bytes. Posting
  values and property IDs remain solely in `graph.callsite-string-index`. Hosted large-corpus CI
  must still confirm the complete persisted graph delta against the 4,096-byte gate.
- Candidate P50/P95 was `4.504/51.432`, `4.581/55.199`, and `4.732/55.713 ms`. The unmodified
  repository comparator passed with zero errors against the three exact remote-main pairs. Overall
  P95 speedup was `17.77x`, `15.07x`, and `15.48x`; worst required wrapped-shape speedup was
  `15.22x`, `12.86x`, and `20.67x`.
- Dense wrapped DISTINCT was `78.721`, `86.770`, and `70.950 ms`. The prior remote Attempt 056
  outlier was `246.557 ms`, so the bounded directory also removes enough cold metadata work to
  leave margin above the 5x milestone rather than merely clearing it by rounding.
- Process CPU was `1.820-1.954 s`, peak used heap `3.60-3.61 GiB`, peak RSS `4.03-4.10 GiB`, and
  charged work was `5,866,095` units. The physical worker plan remained `2 graph + 2 segment` with
  four active CPU cores on the four-CPU screening JVMs.

**Conclusion:** keep. Balanced chunks cap persisted overhead at 4 KiB, preserve selective
corruption fallback, and pass the exact three-pair 5x comparator with substantial latency margin.
The claim remains local screening until hosted pinned-JAR global-wide and large-corpus gates pass
on this exact commit.

### 2026-09-03 - Attempt 060: Lazily construct trailing graph scanners

**Hypothesis:** the balanced row path constructs one `DirectStringSourceScanner` and its local
state for every one of the 64 sources before probing graph zero. Dense `LIMIT 200` cases finish in
that leading graph, so construct only its scanner first and allocate the remaining 63 scanners
only when the query must continue. This should remove fixed orchestration cost without changing
storage selection, graph/segment parallelism, source ordering, or results.

**Evidence:**

- The exact hosted Attempt 059 run `33689424481` established the scoped milestone before this
  experiment: all three real-64 paired forks exceeded 5x overall P95 (`6.31x`, `6.25x`, `7.01x`),
  P50 speedup was `27.49-30.76x`, and correctness matched. The gate remained red only because five
  dense shapes repeated small absolute regressions. The same run passed the large-corpus gate;
  Tika and Hive added exactly 4,092 persisted bytes and Kotlin compiler added 4,094 bytes against
  a base result with two bytes of generation variation.
- Dataset: the same 64 distinct persisted shards generated from the four pinned Android, Tika,
  Hive, and Kotlin compiler fixture JARs under `/tmp/pr113-exp054-fixture64-screen/`; no synthetic
  graph supplied performance evidence. Three four-CPU candidate runs are under
  `/tmp/pr113-exp060-lazy-scanners.ZBjRm2/`.
- Correctness: all three runs completed all 34 cases and matched the exact hosted remote-main
  oracle on outcome, row count, response bytes, digest, and hit graph IDs. Charged work remained
  exactly `5,866,095` units in every run.
- Candidate P50/P95 was `4.527/52.391`, `4.475/53.058`, and `4.904/51.126 ms`, overlapping Attempt
  059's `4.504-4.732/51.432-55.713 ms` range. The five previously blocked dense shapes likewise
  had no consistent improvement: class-pair `2.272-2.363 ms`, name-pair `1.681-2.169 ms`,
  caller-class `1.184-1.274 ms`, callee-class `0.787-0.982 ms`, and wrapped case-insensitive
  `0.961-1.143 ms`.
- Process CPU was `1.918-1.974 s`, peak used heap `3.60-3.61 GiB`, and peak RSS `4.03-4.06 GiB`;
  these also overlap Attempt 059.

**Conclusion:** reject. Avoiding 63 small scanner wrappers is not the source of the hosted dense
latency regressions. The production tree retains the Attempt 059 path unchanged.

### 2026-09-03 - Attempt 061: Reuse the legacy projection loop for the dense leading graph

**Hypothesis:** the balanced path's `DirectStringSourceScanner` projection differs from the
remote-main work-tracked serial loop even when both select the same bounded raw storage scan. Use
the legacy binding/projection loop for the short-term leading probe, then retain balanced parallel
continuation only when graph zero does not fill the limit.

**Evidence:**

- Dataset and oracle: the same 64 pinned-JAR persisted graphs and exact hosted remote-main oracle
  used by Attempts 059 and 060. Three four-CPU runs are under
  `/tmp/pr113-exp061-serial-leading.eby6Ow/`; all completed 34/34 cases with exact result parity and
  unchanged `5,866,095` charged work units.
- P50/P95 was `4.657/57.110`, `4.681/51.247`, and `4.834/57.030 ms`, no improvement over Attempt
  059. Every targeted dense row became slower locally: class-pair `2.717-2.928 ms`, name-pair
  `2.190-2.250 ms`, caller-class `1.641-1.902 ms`, callee-class `1.151-1.181 ms`, and wrapped
  case-insensitive `1.428-1.500 ms`.
- Process CPU remained `1.931-1.957 s`; heap and RSS remained inside the Attempt 059 ranges.

**Conclusion:** reject. The specialized scanner projection is already cheaper than rebuilding the
legacy binding map for each hit. The production tree retains the scanner projection.

### 2026-09-03 - Attempt 062: Cache provenance and bypass qualified-node projection wrappers

**Hypothesis:** dense graph-zero cases project 200 rows. Cache the graph's singleton provenance
set per scanner and read ordinary node properties directly, avoiding a `QualifiedNode` plus a new
singleton set for every returned row.

**Evidence:**

- Dataset and oracle: the same 64 pinned-JAR persisted graphs and hosted remote-main oracle. Three
  four-CPU runs are under `/tmp/pr113-exp062-direct-projection.zOwWrC/`; all completed 34/34 cases
  with exact result parity and unchanged `5,866,095` work units.
- P50/P95 was `4.624/55.634`, `4.363/55.110`, and `4.430/55.394 ms`. Caller-class dense improved
  from Attempt 059's `1.216-1.435 ms` to `0.972-1.191 ms`, but class-pair, name-pair, callee-class,
  and wrapped dense overlapped their prior ranges and did not show a consistent reduction.
- Process CPU was `1.803-1.983 s`; heap and RSS stayed inside the prior run-to-run range.

**Conclusion:** reject. One dense shape improved, but the change did not address the repeated
multi-shape hosted blocker and adds special qualified-property handling. The production tree keeps
the simpler common projection path.

### 2026-09-03 - Attempt 063: Fuse the bounded leading raw scan with property projection

**Hypothesis:** dense `LIMIT 200` cases inspect only 665-681 CallSites in graph zero, but the
existing raw-leading path materializes each full `CallSiteNode` and then reads its projected fields
again in the Cypher layer. Reuse the existing duplicate-preserving storage projection capability
for this bounded leading probe so mmap decoding performs matching and selected-field projection in
one pass. If graph zero produces fewer than the limit, continue with the unchanged balanced graph
and segment path from graph one.

**Evidence:**

- Dataset and oracle: the same 64 distinct pinned-JAR persisted graphs and exact hosted remote-main
  oracle used by Attempts 059-062. Three four-CPU runs are under
  `/tmp/pr113-exp063-raw-projection.CJPTRf/`; synthetic tests are used only to verify path selection,
  duplicate preservation, and fallback behavior.
- Correctness: all three real-fixture runs completed 34/34 cases and matched outcome, row count,
  response bytes, digest, and hit graph IDs. Charged work remained exactly `5,866,095` units, so
  the improvement does not skip graph coverage or budget accounting.
- Class-pair dense fell from Attempt 059's `2.148-2.304 ms` to `1.297-1.345 ms` (about 40%);
  name-pair fell from `1.705-1.849 ms` to `1.178-1.202 ms` (about 32%); caller-class fell from
  `1.216-1.435 ms` to `0.954-0.983 ms` (about 25%). Four-property projection was `2.527-2.694 ms`.
  Callee-class and wrapped results overlap their earlier ranges, so the hosted aligned-shape gate
  remains authoritative for whether the entire blocker is cleared.
- Overall P50/P95 was `4.731/53.954`, `4.486/51.683`, and `4.577/52.692 ms`; process CPU was
  `1.693-1.862 s`, versus Attempt 059's `1.820-1.954 s`. Peak heap was `3.60-3.61 GiB` and peak RSS
  was `4.03-4.06 GiB`.
- Focused `CrossGraphCypherExecutorTest` and `GraphStoreTest` suites plus cypher/webgraph detekt pass.
  Tests require a bounded dense global query to use only graph zero's raw projection without node
  materialization, and require mapped raw projection to preserve duplicate rows without loading
  the persisted index.

The exact hosted run `33692920895` confirmed the main milestone in all three paired orders: P95
speedup was `7.60x`, `6.20x`, and `5.49x`, the worst required wrapped speedup was `5.01x`, P50
speedup was `33.49-35.17x`, and all candidate runs retained exact result parity. CPU fell from
`25.41-26.66 s` to `4.76-5.12 s`, with `3.53-3.60` effective cores, while peak heap and RSS also
fell. It also exposed two flaws in this first fused version. The raw return path omitted the graph
access counter, and repeated raw scans displaced reusable projection/index work: callee-class,
provenance, wrapped, and broad-all dense rows repeated aligned regressions, while the existing
request-selected K=64 dense row changed from one retained-index lookup and `0.802 ms` to no lookup,
`665` raw work units, and `2.398 ms`.

**Conclusion:** reject this first selection policy while retaining the fused storage primitive for
the next bounded/cache-aware attempt. Term length is not a density signal, repeated projections
must reuse the first bounded match set, and an already-loaded retained index must keep priority.

### 2026-09-03 - Attempt 064: Bound and reuse the raw leading match set

**Hypothesis:** Attempt 063 pays the same 665-681-node graph-zero scan for each projection shape
and bypasses a retained index even when earlier graph-routing queries have already made that path
hot. Limit the raw path to a density probe of at most `max(64, LIMIT * 4)` and 1,024 CallSites,
cache at most 16 immutable node-id prefixes by exact predicate and limit, and keep an already-loaded
CallSite index ahead of the raw probe. An inconclusive probe returns to the existing persisted-index
and balanced graph/segment path instead of finishing an unbounded serial scan.

**Evidence:**

- Dataset and oracle: 64 distinct persisted shards regenerated from the four pinned Android, Tika,
  Hive, and Kotlin compiler fixture JARs under `/tmp/pr113-exp064-fixture64.7PSN8d/`; the exact
  hosted remote-main base and oracle from run `33692920895` remain the comparison and correctness
  sources. The candidate is this Attempt 064 experiment commit; three four-CPU candidate runs are
  under `/tmp/pr113-exp064-bounded-cache-short/`. Synthetic tests cover only cache selection, the
  800-node miss bound, and retained-index priority.
- Correctness: all three global-wide runs completed 34/34 rows with exact outcome, row count,
  response bytes, digest, and hit-graph parity. P50/P95 was `4.359/51.412`, `4.223/54.948`, and
  `4.817/58.153 ms`; process CPU was `1.952-1.966 s`, peak heap `3.60-3.61 GiB`, and peak RSS
  `4.04-4.05 GiB`. The runtime still reported four processors, `2` graph workers, and `2` segment
  workers.
- The repeated dense projections now reuse the exact first 200 matching node ids: class-pair was
  `0.768-0.868 ms`, name-pair `0.660-0.711 ms`, caller-class `0.403-0.553 ms`, callee-class
  `0.409-0.457 ms`, aliased `0.710-0.729 ms`, and parameterized `0.689-0.731 ms`. Their charged
  work falls from 681 to 200 after the first projection. Wrapped non-distinct dense was
  `1.323-1.537 ms`; broad-all-64 was `0.752-0.860 ms`. Every accessed graph is now recorded.
- Total global-wide charged work fell from Attempt 063's `5,866,095` to `5,862,744`; the bounded
  fallback therefore does not move cost into the long zero/targeted terms. An exploratory version
  that probed every term raised work to `7,719,673` and P50 to roughly 10 ms, so it was rejected
  before this commit and the existing four-character eligibility bound was retained around the
  actual bounded density probe.
- A separate real-64 cold graph-routing replay under `/tmp/pr113-exp064-routing/` completed all
  1,137 rows correctly. The request-selected K=64 dense row restored the retained-index path:
  one lookup, 200 work units, and `0.367 ms`, versus Attempt 063's zero lookups, 665 work units,
  and hosted `2.398 ms`. Aggregate retained-index evidence returned to exactly 1,979 lookups over
  all 64 graphs with the required `29..38` per-graph range.

Hosted exact-candidate `c9c9efd49ac8` run `33696452403` confirmed 34/34 correct rows in every
pair, complete source-access evidence, the configured/observed `2+2` split, and aggregate P95
speedups of `6.15x`, `7.19x`, and `7.96x`; wrapped P95 was `5.43x`, `7.67x`, and `6.69x`.
Candidate P50 was `9.712-12.541 ms`, process CPU fell from `19.84-20.66 s` to `3.12-3.43 s`,
peak heap from `4.67-4.80 GiB` to `3.66-3.67 GiB`, and peak RSS from `5.97-6.08 GiB` to
`4.35-4.38 GiB`. The exact 5x milestone therefore passes with lower resources.

The full gate remains red. `global-wide-provenance/dense` repeatedly regresses from roughly
`1.7-1.9 ms` to `6.4-8.4 ms`, and non-distinct wrapped dense repeats above the aligned gate in two
pairs at `1.5-1.7 ms` to `3.3-4.0 ms`. Graph-routing restores all 1,979 retained-index lookups,
the `29..38` per-graph range, and the K=64 dense row's one lookup/200 work contract, but K=64
zero/targeted P50/P95 remains red because graph-scoped 64-source index work is still forced through
the scan-oriented two-graph/two-segment split. Existing method compatibility also has confirmed
runner-sensitive failures outside the changed >=40-source path.

**Conclusion:** keep as an intermediate 5x win, not as a merge-ready result. The raw path is now
bounded and its repeated dense projections are cheaper without displacing a loaded index. The next
attempt must return the segment half to graph fanout for explicitly graph-scoped index work; the
following projection-path attempt can remove the two remaining aligned dense regressions without
changing the established all-graph scan split.

### 2026-09-03 - Attempt 065: Return segment capacity to graph-scoped index fanout

**Hypothesis:** the additive `2+2` plan is appropriate for an unscoped 64-graph storage scan, but a
request already scoped by `graphId`, a graph-id set, or the `/api/cypher/graphs` graph parameter has
no segment work when its retained indexes are ready. Attempt 064 nevertheless limits those K=64
lookups to two graph workers. Keep the source-ordered leading probe, then use up to NCPU graph
workers with serial per-graph storage for the remaining explicitly selected sources. This stays
additive and cannot create graph-worker x segment-worker oversubscription.

**Evidence:**

- Base: Attempt 064 exact production head `c9c9efd49ac8`. Candidate: this experiment commit. Dataset:
  the same 64 persisted shards regenerated from the pinned Android, Tika, Hive, and Kotlin compiler
  fixture JARs in `/tmp/pr113-exp064-fixture64.7PSN8d/graphs/`; no synthetic timing evidence.
- The hosted base run `33696452403` had K=64 cold P50/P95 `2.513/13.951 ms` and failed the graph-set
  latency gate despite 1,979 correct retained-index lookups. The same-machine local Attempt 064
  control under `/tmp/pr113-exp064-routing/` measured `0.744/5.535 ms` and a two-worker graph peak.
- Three four-CPU candidate runs under `/tmp/pr113-exp065-leading-routing.04Ev71/` and
  `/tmp/pr113-exp065-leading-routing-more.kXCdsr/` measured K=64 P50 `0.969`, `0.974`, and
  `0.742 ms`; P95 was `3.060`, `3.143`, and `2.374 ms`. All runs completed 1,137/1,137 rows with
  exact oracle parity, exactly 1,979 index lookups, and unchanged `18,550,794` charged work.
- The source-ordered dense contract remains intact in every run: each of the literal, parameterized,
  and request-selected K=64 dense rows accesses one graph, performs one index lookup and 200 work
  units, and returns the same digest. Runtime graph concurrency rises from two to four while
  segment concurrency is zero for the scoped path.
- Candidate process CPU was `8.96-9.15 s` versus the local Attempt 064 control's `9.81 s`; wall time
  was `5.077-5.087 s` versus `5.252 s`. Peak heap was `5.28-5.31 GiB` versus `5.50 GiB`, and peak
  RSS `6.72-6.96 GiB` versus `6.62 GiB`; the upper RSS observation is +5.1%, below the 15% gate.

The subsequent exact hosted run `33699712360` kept all 1,979 index lookups and exact result parity,
but rejected the per-graph task shape. K=64 P50/P95 regressed from main's `1.714/5.572 ms` to
`4.287/8.130 ms`; all six zero/targeted rows paid roughly `4-8 ms` although they performed only
`64-3,125` work units. The four graph workers were active, but submitting one tiny retained-index
lookup per task made scheduler overhead larger than the useful work.

**Conclusion:** superseded by Attempt 069. Returning the segment half to graph fanout is correct,
but one task per selected graph is the wrong granularity for hot, low-work K=64 lookups. Unscoped
>=40-source scans retain the NCPU-half graph/segment split and the independently established 5x
global-wide path.

### 2026-09-03 - Attempt 066: Synthesize graphId around the raw projection

**Hypothesis:** `global-wide-provenance/dense` has the same predicate and matching CallSite ids as
the leading four-property shape, but requesting `n.graphId` makes `projectRawLeadingRows` reject
the storage projection and repeat a 681-node object-materializing scan. `graphId` is source metadata,
not a stored CallSite string. Remove it from the storage projection, reuse the bounded cached match
ids for the remaining properties, and synthesize the selected source id into the result row.

**Evidence:**

- Base: Attempt 065 parent, whose raw projection behavior is the exact hosted Attempt 064 head
  `c9c9efd49ac8`. Candidate: this experiment commit. Dataset: the same 64 persisted JAR-derived
  shards in `/tmp/pr113-exp064-fixture64.7PSN8d/graphs/`; synthetic coverage verifies only the
  storage/request projection mapping and returned graph id.
- Hosted Attempt 064 measured provenance dense at `6.357`, `7.012`, and `8.381 ms`, versus base
  `1.696`, `1.907`, and `1.777 ms`, failing the aligned latency gate in all three pairs. It charged
  681 work units because the unsupported storage projection forced a second leading scan.
- Three four-CPU candidate real64 runs in `/tmp/pr113-exp066-global.EeKOvG/` completed 34/34 rows
  with exact oracle parity. Provenance dense fell to `0.825`, `0.825`, and `0.791 ms`, and reused
  exactly 200 cached matches/work units. Total work fell from `5,862,744` to `5,862,263`.
- Aggregate candidate P50 was `4.730`, `4.678`, and `4.776 ms`; P95 was `52.912`, `52.326`, and
  `45.505 ms`. Process CPU was `1.78-1.85 s`, peak heap `3.60-3.61 GiB`, and peak RSS
  `4.04-4.07 GiB`. The observed execution plan remains two graph plus two segment workers.

**Conclusion:** keep pending exact hosted paired confirmation. This removes one of Attempt 064's
two deterministic aligned-shape regressions without changing predicate matching, source order,
the 5x graph/segment plan, or the explicitly graph-scoped dispatch corrected by Attempt 065.

### 2026-09-03 - Attempt 067: Send transformed dense terms back to the index

**Hypothesis:** Attempt 064's remaining non-distinct wrapped dense regression comes from lowercasing
four raw strings across the first 665 CallSites. Restrict the bounded raw leading path to predicates
without a transform so wrapped `toLower(... ) CONTAINS` queries use the existing persisted
trigram/index path and its segment workers.

**Evidence:**

- Base: Attempt 066. Candidate: an isolated one-line eligibility snapshot based on Attempt 066;
  the rejected production change is not retained. Dataset: the same 64 persisted JAR-derived shards
  in `/tmp/pr113-exp064-fixture64.7PSN8d/graphs/`. Three four-CPU candidate runs are under
  `/tmp/pr113-exp067-global.8mmmD6/` and all complete 34/34 rows with exact oracle parity.
- Non-distinct wrapped dense worsened from Attempt 066's raw `1.498-1.609 ms` and 665 work units to
  `3.992`, `4.076`, and `6.184 ms` with 9,767 work units. This remains above hosted main's
  `1.518-2.130 ms` range and therefore does not clear the aligned-shape gate.
- Aggregate P50/P95 remained `4.161-4.756/50.598-52.665 ms`, but total charged work rose from
  `5,862,263` to `5,880,932`. Process CPU was `1.71-1.94 s`, peak heap `3.46-3.60 GiB`, and peak
  RSS `3.96-4.05 GiB`; the resource movement does not justify the deterministic wrapped regression.

**Conclusion:** reject and revert. A transformed dense term cannot simply be sent to the existing
index path: its fixed lookup/range overhead exceeds the bounded 665-node raw scan on this fixture.
Keep Attempt 066's raw behavior and optimize the transformed raw comparison itself, or admit a
measured hot representation that does not add this 9,102-work penalty.

### 2026-09-03 - Attempt 068: Compare lowercase ASCII without mutating the decode buffer

**Hypothesis:** the transformed raw path first scans the complete decoded string for non-ASCII,
then lowercases the mutable buffer, then searches it. The dense one-character term should be able
to stop at its first ASCII case-insensitive match. Replace those three passes with one bounded
comparison loop while preserving the exact Kotlin lowercase fallback for any non-ASCII input.

**Evidence:**

- Base: Attempt 066. Candidate: an isolated comparator snapshot based on Attempt 066; the rejected
  production change is not retained. Dataset: the same 64 persisted JAR-derived shards in
  `/tmp/pr113-exp064-fixture64.7PSN8d/graphs/`. Three four-CPU runs are under
  `/tmp/pr113-exp068-global.oNF3h5/`; all complete 34/34 rows with exact oracle parity.
- Non-distinct wrapped dense measured `1.619`, `1.595`, and `1.829 ms`, versus Attempt 066's
  `1.609`, `1.609`, and `1.498 ms`. Work stayed at 665, so the alternative comparator provides no
  latency reduction and adds downside in the third fork.
- Aggregate P50/P95 was `4.630-4.715/50.818-55.462 ms`, total work remained `5,862,263`, process
  CPU was `1.92-1.93 s`, peak heap `3.58-3.60 GiB`, and peak RSS `4.04-4.08 GiB`. None is a
  material improvement over Attempt 066.

**Conclusion:** reject and revert. The existing mutable-buffer lowercase scan is not the measured
bottleneck on the local real64 JVM. Keep the smaller Attempt 066 production tree and use exact
hosted paired evidence before investing further in this millisecond-scale cold-path variance.

### 2026-09-03 - Attempt 069: Batch graph-scoped retained-index fanout

**Hypothesis:** Attempt 065 assigns one executor task to each of the 63 sources after the ordered
leading probe. The exact hosted K=64 zero and targeted rows perform only `64-3,125` storage work
units, so their `4-8 ms` latency is dominated by submitting, completing, and replenishing 63 tiny
tasks. Partition the remaining sources into at most NCPU contiguous batches. Each batch remains one
graph worker and performs serial per-graph storage work, preserving the additive `NCPU + 0` bound;
completed batches are merged in source order and later batches are cancelled once LIMIT is full.

**Evidence:**

- Base: exact Attempt 066 head `4f61526d3e3978a60ba6d0194545e845540c66a9`, hosted run
  `33699712360`. Candidate: this experiment commit. Dataset: the same 64 distinct persisted shards
  regenerated from the pinned Android, Tika, Hive, and Kotlin compiler fixture JARs in
  `/tmp/pr113-exp064-fixture64.7PSN8d/graphs/`; synthetic graphs supply concurrency and ordering
  assertions only.
- Three four-CPU candidate runs under `/tmp/pr113-exp069-batched-routing/` completed all
  `1,137/1,137` rows with exact oracle parity. K64 P50 was `0.728`, `0.844`, and `0.822 ms`; P95 was
  `2.809`, `3.038`, and `3.006 ms`. K64 work was exactly `4,146` units in every run.
- The three dense K64 variants still stop at the leading graph and each records one accessed graph,
  one retained-index lookup, and 200 work units. Zero and targeted cases preserve complete selected
  source coverage. Peak graph concurrency remains four; graph-scoped per-graph storage stays serial.
- Relative to the exact hosted Attempt 066 result, median K64 P50 improves from `4.287 ms` to
  `0.822 ms` (`5.21x`) and P95 from `8.130 ms` to `3.006 ms` (`2.70x`). Complete-run wall time was
  `4.948-5.010 s`, process CPU `8.88-9.14 s`, peak heap `5.29-5.53 GiB`, and peak RSS
  `6.72-6.99 GiB`; all overlap the Attempt 065 local resource envelope.
- Focused cross-graph tests and Cypher detekt pass in the isolated verification checkout. The
  concurrency regression requires the four graph workers to enter different contiguous batches
  concurrently and requires every storage consumer to remain serial.

**Conclusion:** keep pending exact hosted confirmation. Coarsening the task unit removes most of
the K64 scheduler tax without changing result order, correctness, graph selection, work accounting,
or the additive CPU budget. It does not alter the unscoped 64-graph `2 + 2` global-wide path.

### 2026-09-03 - Attempt 070: Bulk-validate selected trigram chunks

**Hypothesis:** the compact prefilter validates every selected posting chunk by feeding each stored
long to `CRC32` through eight Kotlin-level byte updates. Keep the same persisted checksum and exact
posting checks, but validate the mapped byte slice with the JDK's bulk `CRC32.update(ByteBuffer)`
path before scanning its postings for the requested trigram. This trades a second sequential read
of a small selected chunk for far fewer interpreted checksum calls.

**Evidence:**

- Base: Attempt 069 commit `645237a`; candidate: this experiment commit. Dataset: the same 64
  distinct pinned-JAR persisted graphs and remote-main correctness oracle. Three candidate runs are
  under `/tmp/pr113-exp070-bulk-crc/`; three same-time-window base runs are under
  `/tmp/pr113-exp070-control/`. Synthetic tests are used only for corruption/fallback behavior.
- All six runs completed 34/34 global-wide cases with exact oracle parity. Candidate and base both
  charge exactly `462,762` work units for wrapped DISTINCT dense and `80,171` for localized-early;
  bulk CRC therefore does not obtain its result by weakening budget accounting.
- Base P50 was `4.620`, `4.635`, and `4.855 ms`; candidate P50 was `2.959`, `3.261`, and
  `3.166 ms` (a 31.7% median reduction). Base P95 was `54.306`, `51.801`, and `55.866 ms`;
  candidate P95 was `55.357`, `53.024`, and `51.755 ms`, an overlapping range rather than a tail
  claim.
- Wrapped DISTINCT dense was `91.866/75.419/71.318 ms` on base and
  `79.263/70.414/74.240 ms` on candidate: two wins and one small regression. Process CPU fell from
  `1.890-1.950 s` to `1.815-1.880 s`. Candidate peak heap was `3.60-3.61 GiB` and peak RSS
  `4.04-4.07 GiB`, unchanged in practice.
- Full WebGraph tests and detekt pass. Existing tests verify exact hits and misses, directory and
  selected-chunk corruption fallback, work-budget rejection, and interruption.

**Conclusion:** keep as a measurable P50/CPU improvement, not as proof that the remaining wrapped
DISTINCT tail gate is solved. The binary format and checksum are unchanged, every selected chunk is
still verified before its matches are accepted, and exact string comparison remains authoritative.

### 2026-09-03 - Attempt 071: Reuse the allocation-bounded lowercase matcher in the prefilter

**Hypothesis:** after a selected trigram chunk is verified, the compact prefilter converts every
candidate `MutableString` to a new `String` and then allocates another lowercase `String` before the
exact `contains` check. The mapped raw scanner already has a correctness-tested reusable matcher:
ASCII values lowercase in the mutable decode buffer, while any non-ASCII value falls back to
Kotlin's locale-independent `String.lowercase()` semantics. Reuse that implementation at the
prefilter boundary instead of maintaining a second allocating comparison path.

**Evidence:**

- Base: Attempt 070. Candidate: this experiment commit. Dataset and correctness oracle: the same
  64 distinct persisted pinned-JAR graphs used by Attempts 069-070. Three candidate runs are under
  `/tmp/pr113-exp071-reuse-matcher/`; the immediate Attempt 070 controls are under
  `/tmp/pr113-exp070-bulk-crc/`.
- All candidate runs completed 34/34 cases with exact oracle parity. Wrapped DISTINCT dense remains
  exactly `462,762` work units, wrapped non-DISTINCT dense 665, and localized-early 80,171 in every
  run.
- Wrapped DISTINCT dense improved from Attempt 070's `79.263/70.414/74.240 ms` to
  `73.525/69.479/66.517 ms`; all three observations are no worse and the worst observation falls
  7.2%. Complete-run process CPU fell again from `1.815-1.880 s` to `1.704-1.800 s`.
- Wrapped non-DISTINCT dense stabilized at `1.325/1.286/1.368 ms`, versus Attempt 070's
  `1.552/1.656/1.581 ms`. Localized-early stabilized at `8.594/8.583/8.952 ms`, versus
  `13.165/8.683/10.267 ms`. Overall P50 was `2.987/2.986/3.150 ms`; P95 was
  `50.820/54.123/51.576 ms`.
- Candidate peak heap was `3.46-3.56 GiB` and peak RSS `3.94-4.00 GiB`, with no retained cache or
  persisted-format addition. Focused exact/corrupt/Unicode trigram tests and WebGraph detekt pass.
- The repository's unmodified `compare-global-wide-pressure` command paired these three candidate
  results with the three exact hosted-main results from run `33699712360`. It passed with zero
  correctness or aligned-regression errors: overall P95 speedup was `17.43x`, `16.23x`, and
  `16.40x`; the worst required wrapped-shape speedup was `15.05x`. This cross-environment screening
  has ample 5x margin, while the next exact hosted run remains the authoritative same-runner gate.

**Conclusion:** keep pending exact hosted paired confirmation. One shared exact matcher removes a
duplicate allocation-heavy path and improves CPU plus both previously aligned dense blockers while
preserving Unicode semantics, checksums, work accounting, and exact result digests.

### 2026-09-03 - Attempt 072: Keep sidecar persistence out of request cache release

**Hypothesis:** a zero-hit source calls `releaseStringPropertyDisjunctionCache()` on the request
thread. When that query built a missing CallSite index, release synchronously wrote the complete
exact index and trigram directory before freeing the reservation. Request cleanup should only
release request-owned memory; the existing graph-close and explicit preparation lifecycles can
retain responsibility for best-effort sidecar migration.

**Evidence:**

- The release path now closes a query-built index without persisting it. An exact 4,096-CallSite
  regression test interrupts the request thread before release and verifies prompt
  `CancellationException` propagation, preservation of the interrupt flag, removal of the retained
  reservation, and absence of both sidecar targets. This synthetic graph establishes lifecycle and
  cancellation correctness only; it is not performance evidence.
- Explicit writer loops poll interruption every 1,024 integers/longs, both persistence wrappers
  rethrow cancellation instead of converting it to a best-effort `false`, and target replacement
  is preceded by an interruption check.
- The normal graph-close migration test still persists and restores a query-built index in the
  next mapped process. The persistence-failure fallback and the 4,097-posting directory boundary
  tests also pass.
- The complete WebGraph test suite and WebGraph detekt pass in the isolated verification checkout.

**Conclusion:** keep. This removes unbounded sidecar I/O from request cleanup without disabling
startup/graph-close persistence, and makes the remaining non-request writer lifecycle explicitly
cancellable. Exact hosted real64 evidence remains responsible for any latency claim on the full
query pipeline.

### 2026-09-03 - Attempt 073: Bound and charge mapped posting checksum reads

**Hypothesis:** Attempt 070's bulk `CRC32.update(ByteBuffer)` lowered CPU, but validating an entire
selected posting chunk before the first work callback made cancellation and rejection proportional
to the full index. Preserve the JDK bulk checksum path while dividing each chunk into fixed 8 KiB
slices; poll interruption and synchronously charge each slice before touching its mapped bytes.

**Evidence:**

- The prefilter now flushes one work charge before each checksum slice. A regression test first
  initializes the mapped directory, then rejects the first selected-posting checksum charge and
  verifies the exact exception escapes after one callback without loading the full CallSite index.
  Existing directory/selected-chunk corruption and interruption tests remain authoritative for
  fallback and cancellation semantics.
- The slice size is independent of graph/index size, so the uncharged interval is capped at one
  fixed bulk CRC call rather than one complete posting chunk. Posting iteration remains separately
  charged exactly as before.
- This synthetic test is correctness and resource-contract evidence only. The exact hosted real64
  rerun must establish whether the extra bounded callback affects the retained 5x latency gate.

**Conclusion:** keep pending exact hosted confirmation. This closes the cancellation/work-accounting
hole without reverting the allocation-free bulk checksum implementation.

### 2026-09-03 - Attempt 074: Validate property membership only when queried

**Hypothesis:** loading the compact trigram prefilter eagerly scans and checksums every property's
sorted string-id membership array, even when a zero-hit term is absent from the small trigram
directory and no membership lookup can occur. Preserve eager validation of the directory, exact
index header, and every selected posting chunk, but defer each optional property-membership
checksum until `selectedValues` actually asks whether that property contains a string id.

**Evidence:**

- Base: exact head `3596c58d49b34296cc0260271a3f6e44e5bac49f`; candidate: this isolated
  experiment plus the checksum-test proof from `0a30a34`. Dataset: the same authenticated
  64 distinct pinned-JAR graph artifact from hosted run `33707092760`, downloaded under
  `/tmp/pr113-shared-fixture64-3596c58/`; no synthetic timing evidence.
- Three four-CPU, 8 GiB cold-index pairs under `/tmp/pr113-exp074-real-a/` alternated order as
  base/candidate, candidate/base, and base/candidate. All six runs completed 34/34 queries, and all
  three candidate runs verified every result against the base-recorded correctness oracle.
- Zero-query P95 fell in every pair: `64.867 -> 35.784 ms`, `54.346 -> 39.934 ms`, and
  `51.582 -> 31.110 ms`. Overall P95 also fell in every pair: `89.624 -> 42.882 ms`,
  `54.346 -> 50.468 ms`, and `54.559 -> 52.942 ms`. Overall P50 was mixed at
  `2.960 -> 3.249 ms`, `3.150 -> 2.990 ms`, and `3.276 -> 3.514 ms`.
- The first four-property zero-hit case drops from `1,686,453` to `151,595` charged work units.
  Complete-run charged work is deterministic at `5,865,407 -> 4,449,703` (-24.1%). Process CPU
  is `1.971 -> 1.750 s`, `1.740 -> 1.838 s`, and `1.914 -> 1.744 s`; two pairs improve and the
  reverse-order pair is +5.6%. Peak heap is effectively unchanged; peak RSS ranges overlap.
- A corrupt deferred membership array cannot create a false negative. The new regression corrupts
  its persisted checksum, performs an exact selected-value DISTINCT projection, and still returns
  the authoritative raw-projection row without loading the full index. Work rejection and
  interruption still propagate while the first actual membership validation remains charged.
  The focused regression, complete WebGraph test suite, and WebGraph detekt pass.

**Conclusion:** keep pending exact hosted paired confirmation. Deferring unused optional membership
validation removes 1.42 million cold zero-hit work units and improves all observed real64 zero-hit
tails without changing the persisted format or trusting unchecked data to exclude a result.

### 2026-09-03 - Attempt 075: Retain bounded legacy repair state until graph close

**Hypothesis:** Attempt 072 correctly removed complete sidecar writes from request cleanup, but
closing the query-built index there also discards the only state that graph close can persist. A
legacy graph with missing sidecars can therefore rebuild the same complete index on every zero-hit
request. Retain only the globally budgeted structural index, clear request-result caches at release,
and leave the existing atomic best-effort writer on the non-request graph-close lifecycle.

**Evidence:**

- A deterministic production-path regression saves 4,096 CallSites without sidecars and executes
  the actual `CrossGraphCypherExecutor` zero-hit DISTINCT query twice. Both results are empty, the
  second request reuses the same reservation instead of rebuilding, and neither request writes a
  sidecar. Closing the graph then creates both the exact index and compact prefilter; a new mapped
  graph executes the same query from the repaired persisted index with the same result.
- Retained structural memory remains governed by `MappedCallSiteStringIndexMemoryBudget`; release
  clears predicate, node-id, and projected-row caches immediately. Graph close releases the
  reservation after persistence, and the regression verifies the process-wide retained byte count
  returns exactly to its pre-test value.
- The already-interrupted release regression still throws `CancellationException`, leaves both
  targets absent, clears the in-memory index, and returns the global retained byte count to its
  original value. This synthetic fixture is lifecycle/correctness evidence only, not a performance
  benchmark.
- Both focused lifecycle tests and WebGraph detekt pass. The complete WebGraph suite and exact
  hosted performance/resource gates remain required before resolving the review thread.

**Conclusion:** keep pending full verification. This restores legacy sidecar self-repair and
repeat-query reuse without reintroducing request-thread persistence or unbounded cache retention.

### 2026-09-03 - Attempt 076: Bypass raw projection for transformed leading rows

**Hypothesis:** the remaining hosted `global-wide-wrapped-case-insensitive/dense` regression comes
from converting 200 storage-projected rows into Cypher result maps. Bypass the raw projection for
transformed predicates so the existing bounded serial node path handles the leading graph, while
retaining the same source ordering, LIMIT, work accounting, and later-graph fanout.

**Evidence:**

- Base: exact PR head `efa9fb477718b3e711a9a695c0a30ebfb6439c43`. Candidate: an isolated
  one-line routing snapshot based on that head; the rejected production change is not retained.
  Dataset: the same 64 distinct persisted shards generated from the pinned Android, Tika, Hive,
  and Kotlin compiler fixture JARs in `/tmp/pr113-exp074-real-a/graphs.tsv`. Synthetic timing data
  was not used.
- Three four-CPU, 8 GiB cold-index base runs are under
  `/tmp/pr113-attempt076-control.YmW6XG/results/`; three candidate runs are under
  `/tmp/pr113-attempt076-node-leading/`. All six runs completed 34/34 queries, and every candidate
  row count, ordered digest, response size, and provenance matched the base-recorded oracle.
- The targeted hosted blocker regressed in every local pair. Wrapped non-DISTINCT dense moved from
  `1.416/1.342/1.334 ms` to `2.619/2.620/2.579 ms` while retaining exactly 665 charged work units.
  Node materialization and property projection are therefore more expensive than the existing raw
  storage projection; the proposed bypass does not remove the measured cost.
- Overall P50 was `3.010/2.896/3.075 ms` on base and `3.010/3.180/2.914 ms` on candidate; P95 was
  `48.603/48.377/45.782 ms` and `49.284/48.392/46.444 ms`. Process CPU was
  `1.664-1.803 s` on base and `1.680-1.893 s` on candidate. Peak heap stayed near 3.82 GB and peak
  RSS near 4.29-4.35 GB, providing no resource justification for the deterministic dense
  regression.

**Conclusion:** reject and revert. Keep the allocation-bounded raw projection for transformed
leading rows. The next experiment must target work before or inside the storage scan rather than
replacing the projection with full node materialization.

### 2026-09-03 - Attempt 077: Stop later segments when the first ordered range fills LIMIT

**Hypothesis:** a split raw scan currently waits for every segment even when segment zero already
contains the first 200 source-ordered matches. Signal later segments to stop as soon as that leading
range fills LIMIT, preserving the exact ordered prefix while avoiding work whose rows cannot be
returned.

**Evidence:**

- Base: exact PR head `efa9fb477718b3e711a9a695c0a30ebfb6439c43`. Candidate: an isolated
  early-stop snapshot; the rejected production and test changes are not retained. Dataset and
  oracle: the same 64 pinned-JAR persisted graphs used by Attempt 076. Three four-CPU, 8 GiB
  candidate runs are under `/tmp/pr113-attempt077-ordered-segment-stop/` and the paired base runs
  are under `/tmp/pr113-attempt076-control.YmW6XG/results/`.
- All three candidate runs completed 34/34 queries with exact row-count, ordered-digest, response,
  and provenance parity. A deterministic storage test also proved that a match at the beginning of
  the first ordered range stops later work and leaves no active worker.
- The change does not reach the hosted blocker. `localized-early` still charged exactly 80,173 work
  units because its first equal-sized segment contains fewer than 200 matches; latency was
  `8.935/8.611/9.970 ms` versus base `9.286/9.471/18.289 ms`, an overlapping range rather than a
  deterministic fix. `localized-middle` did trigger the optimization and fell from 112,089 to
  73,670 work units, measuring `7.690/4.754/8.123 ms` versus `17.754/13.007/13.411 ms`.
- Overall P50 was `3.010/2.896/3.075 ms` on base and `3.046/3.165/2.925 ms` on candidate; P95 was
  `48.603/48.377/45.782 ms` and `49.454/48.313/49.455 ms`. Process CPU was
  `1.664-1.803 s` on base and `1.700-1.782 s` on candidate. Peak heap remained near 3.82 GB and
  peak RSS near 4.26-4.33 GB. The same-head comparator therefore found no stable aggregate gain.

**Conclusion:** reject and revert. Ordered early cancellation is correct and helps one distribution,
but the equal segment boundary prevents it from reducing the failing localized-early case and the
aggregate evidence is neutral. The next experiment routes only long selective transformed terms to
the persisted exact index while leaving short dense terms on the bounded raw path.

### 2026-09-03 - Attempt 078: Route long transformed terms to the persisted exact index

**Hypothesis:** Attempt 067 showed that loading the exact index is too expensive for the short dense
term `get`, but a 32-or-more-character transformed term has a far narrower candidate set. After the
compact trigram prefilter confirms a possible hit, load the persisted exact index before raw segment
scanning only for those long terms; keep short dense terms on the existing bounded raw path.

**Evidence:**

- Base: exact PR head `efa9fb477718b3e711a9a695c0a30ebfb6439c43`. Candidate: an isolated
  selective-index snapshot; the rejected production change is not retained. Dataset, oracle, JVM,
  and base runs are the same as Attempts 076-077. Three candidate runs are under
  `/tmp/pr113-attempt078-selective-index/`; all complete 34/34 cases with exact correctness parity.
- Cold index restoration overwhelms the selective lookup. `localized-early` work rose from 80,173
  to 1,116,134 and latency from base `9.286/9.471/18.289 ms` to
  `24.731/26.653/24.922 ms`. `localized-middle` charged 888,346 work units and `localized-late`
  799,176; all three distributions regressed in all three aligned pairs.
- The ordinary wrapped targeted case was worse still: work rose from 221,480 to 1,605,059 and
  latency from `17.898/16.874/17.287 ms` to `107.703/130.464/109.534 ms`. Short dense and zero-hit
  cases correctly retained their original raw/prefilter routes, but that isolation is insufficient
  to justify the long-term regression.
- Overall P50 stayed near `2.86-2.94 ms`, while P95 regressed from `45.782-48.603 ms` to
  `79.957-87.521 ms`. Candidate process CPU was `2.167-2.354 s` versus base `1.664-1.803 s`, above
  the 15% resource gate in every pair. Peak heap and RSS moved only slightly.

**Conclusion:** reject and revert. A long literal does not amortize full exact-index restoration;
its checksum, structure, and posting load costs more than the raw scan it replaces. Retain the
compact trigram prefilter and test a source-ordered raw scan over its exact string-id matches without
loading the complete index.

### 2026-09-03 - Attempt 079: Serial raw scan over prefiltered long-term string ids

**Hypothesis:** after the compact trigram prefilter has produced exact matching string ids for a
long transformed term, a source-ordered raw scan can compare integer ids and stop at LIMIT without
the complete persisted-index load from Attempt 078. Restrict this route to terms of at least 32
characters and leave short dense terms unchanged.

**Evidence:**

- Base: exact PR head `efa9fb477718b3e711a9a695c0a30ebfb6439c43`. Candidate: an isolated
  selective-serial snapshot; the rejected production change is not retained. The paired three-run
  real64 setup, 8 GiB heap, four-CPU limit, and exact correctness oracle are unchanged. Candidate
  files are under `/tmp/pr113-attempt079-selective-serial/`.
- All 34 cases pass exact row, order, digest, response-size, and provenance verification in every
  run. `localized-early` falls from 80,173 to 46,639 work units and from base
  `9.286/9.471/18.289 ms` to `6.497/3.541/4.414 ms`. `localized-middle` similarly falls to 59,334
  work units and `3.973/3.647/4.023 ms`. This validates that segment over-scan is the hosted
  localized-early regression's dominant mechanism.
- Static term length does not distinguish dense from sparse. The ordinary wrapped targeted query
  keeps the same 221,480 charged work but loses segment parallelism, regressing in every run from
  `17.898/16.874/17.287 ms` to `34.619/36.665/35.348 ms`. The comparator rejects this aligned
  regression even though the localized distributions improve.
- Overall P50 remains near 3.02-3.06 ms and P95 `46.328-49.243 ms`; process CPU is
  `1.747-1.787 s`, peak heap `3.71-3.81 GB`, and peak RSS `4.21-4.34 GB`. Resources are bounded,
  but they cannot justify doubling a successful targeted shape.

**Conclusion:** reject and revert. Prefiltered serial raw scanning is the right primitive for the
dense localized case, but term length is not a sufficient planner signal. The next experiment uses
a bounded source-prefix sample to select serial continuation only when observed match density can
fill LIMIT, otherwise restoring the existing segment path.

### 2026-09-03 - Attempt 080: Select serial continuation from a bounded prefix sample

**Hypothesis:** sample a bounded source-ordered prefix using the prefilter's exact string ids. If
its observed density projects enough matches to fill LIMIT, continue the same iterator serially;
otherwise discard the sample and use the existing split scan. This should preserve Attempt 079's
dense win without serializing a sparse targeted graph.

**Evidence:**

- Base: exact PR head `efa9fb477718b3e711a9a695c0a30ebfb6439c43`. Candidate: isolated 8 Ki
  and 32 Ki prefix-sampling snapshots; neither rejected production change is retained. Dataset,
  oracle, four-CPU/8 GiB JVM, and three base runs are the same as Attempts 076-079. Final 32 Ki
  candidate files are under `/tmp/pr113-attempt080-adaptive-serial-32k/`.
- All candidate runs complete 34/34 cases with exact correctness parity. An 8,192-node sample
  selects serial execution for `localized-middle` but finds zero early matches for
  `localized-early`; that case falls back to parallel work after paying the sample and rises from
  80,173 to 88,365 work units. Raising the prefix to 32,768 still misses its clustered match region
  and raises work to 112,941.
- The 32 Ki sample also raises wrapped targeted work from 221,480 to 287,016 and latency from base
  `17.898/16.874/17.287 ms` to `30.535/27.375/28.514 ms`, producing a deterministic aligned
  regression. `localized-middle` remains fast at 59,334 work units and `4.246/4.202/4.064 ms`, but
  the planner cannot infer the later cluster from a source prefix.
- Final overall P50 is `3.120-3.232 ms` and P95 `48.617-49.544 ms`, versus base
  `2.896-3.075/45.782-48.603 ms`. Process CPU is `1.788-1.843 s`, peak heap about 3.82 GB, and peak
  RSS 4.27-4.30 GB. Increasing the sample spends more CPU/work without solving the target blocker.

**Conclusion:** reject and revert. Prefix density is not representative for class-clustered call
sites. Replace prediction with a distribution-independent ordered small-block scheduler that keeps
only the bounded in-flight lookahead allowed by the segment-worker budget.

### 2026-09-03 - Attempt 081: Scan long transformed terms with ordered small chunks

**Hypothesis:** replace the unrepresentative prefix sample with fixed 16,384-node chunks claimed by
the caller and the existing segment-worker budget. Merge completed chunks in source order and stop
all later chunks once that ordered prefix contains LIMIT rows. This should reach a match cluster
regardless of its position while bounding speculative work to the additive segment concurrency.

**Evidence:**

- Base, dataset, correctness oracle, four-CPU/8 GiB JVM, and three paired cold runs are unchanged
  from Attempts 076-080. The final candidate evidence is under
  `/tmp/pr113-attempt081-ordered-chunks-16k/`; earlier 4,096-node variants are retained under
  `/tmp/pr113-attempt081-ordered-chunks/` and
  `/tmp/pr113-attempt081-ordered-chunks-fair/`. No synthetic timing evidence was used.
- All three final runs completed 34/34 real64 cases with exact row count, order, digest, response
  size, and provenance parity. `localized-early` fell from 80,173 charged work units to
  `49,711-51,759` and from base `9.286/9.471/18.289 ms` to `4.504/4.611/4.297 ms`.
  `localized-middle` also fell to `73,000-76,000` work units and `6.99-10.00 ms`, versus base
  112,089 work units and `13.007-17.754 ms`.
- Distribution-independent early stopping is not sufficient planner selectivity. Wrapped targeted
  queries retain exactly 221,480 charged work units but regress in all three runs from base
  `17.898/16.874/17.287 ms` to `33.385/35.203/26.833 ms`; scheduling several small tasks per graph
  roughly doubles latency when the sparse scan must still reach the end.
- Overall P50 is `2.956/2.948/2.788 ms` versus base `3.010/2.896/3.075 ms`, while P95 is
  `48.844/43.753/49.395 ms` versus `48.603/48.377/45.782 ms`. Process CPU increases in two pairs,
  including `1.718 -> 1.917 s`; heap and RSS remain bounded but provide no justification for the
  deterministic sparse-query regression.

**Conclusion:** reject and revert. Ordered chunks solve the localized cluster but must not be
scheduled solely from term length. The persisted exact index already stores each string id's
posting end; the next experiment will map and validate those compact arrays and enable ordered
chunks only when the exact matching ids can supply at least LIMIT occurrences.

### 2026-09-03 - Attempt 082: Gate ordered chunks with mapped posting counts

**Hypothesis:** map the posting-end arrays already stored beside each property's sorted string ids
and sum the exact matching ids' occurrence counts. Enable Attempt 081's ordered chunks only when
that upper bound can fill LIMIT; otherwise retain the existing equal-segment scan. This should
isolate chunk scheduling to dense localized terms without changing the persisted format.

**Evidence:**

- Base, fixture-derived 64 graphs, oracle, four-CPU/8 GiB JVM, and three paired cold runs are the
  same as Attempts 076-081. Final candidate evidence is under
  `/tmp/pr113-attempt082-occurrence-gated-b/`; every run completed 34/34 cases with exact ordered
  result, response, digest, and provenance parity.
- The mapped count distinguishes the two previously conflicting shapes. Wrapped targeted retains
  the equal-segment path and essentially its old work (`221,480 -> 221,567` including bounded
  planner reads), with `16.891/17.591/17.997 ms` versus base `17.898/16.874/17.287 ms`. Localized
  early takes the chunk path and reduces work from 80,173 to `52,836-57,956`.
- Reduced work does not translate into stable latency: localized early measures
  `17.559/11.975/11.907 ms` versus base `9.286/9.471/18.289 ms`, and the comparator reports the
  aligned regression in two pairs. Overall P50 is `3.124/3.040/3.014 ms` versus
  `3.010/2.896/3.075 ms`; P95 is `50.138/46.507/47.230 ms` versus
  `48.603/48.377/45.782 ms`.
- Process CPU is `1.849/1.723/1.718 s` versus base `1.718/1.664/1.803 s`; peak heap and RSS remain
  bounded. The remaining cost is therefore the small-task scheduler itself, not excess scan work
  or memory pressure.

**Conclusion:** reject the ordered-chunk route. Keep the occurrence count as a planner signal for
the next isolated experiment, but pair it with Attempt 079's already faster source-ordered serial
integer-id scan. Sparse terms must continue using equal segments.

### 2026-09-03 - Attempt 083: Gate serial integer-id scanning with mapped posting counts

**Hypothesis:** when the mapped posting counts prove that the exact matching string ids can fill
LIMIT, use Attempt 079's source-ordered serial integer-id scan; otherwise retain equal segments.
This removes small-task coordination while preventing sparse targeted terms from losing parallelism.

**Evidence:**

- Base, real64 fixture, oracle, and runtime protocol remain unchanged. Three candidate runs are
  under `/tmp/pr113-attempt083-occurrence-serial/`; all 34 cases pass exact row, order, digest,
  response-size, and provenance verification in every run.
- The gate routes as intended. Localized early uses no segment worker and deterministically falls
  from 80,173 to 46,692 work units; localized middle falls from 112,089 to 59,383. Wrapped targeted
  remains parallel and stays at 221,567 work units including the bounded count lookup, versus
  221,480 on base.
- Work reduction is still insufficient under the required latency gate. Localized early measures
  `13.641/13.672/13.355 ms` versus base `9.286/9.471/18.289 ms`, producing an aligned regression in
  two pairs. Overall P50 is `2.949/2.902/2.895 ms`, but P95 is
  `48.830/49.861/48.867 ms` versus base `48.603/48.377/45.782 ms`; wrapped DISTINCT dense also
  regresses in two noisy aligned pairs.
- Process CPU is `1.745/1.773/1.700 s` versus `1.718/1.664/1.803 s`; peak heap and RSS remain
  unchanged. The dense serial route saves work but gives up enough segment concurrency to miss the
  latency gate.

**Conclusion:** reject and revert the occurrence-count planner and serial route. Extend the
existing equal-segment executor instead: cancel later segments once any completed contiguous
source-order prefix, not only segment zero, has accumulated LIMIT matches. Sparse scans cannot
trigger that cancellation and therefore retain their full parallelism.

### 2026-09-03 - Attempt 084: Cancel fixed segments from any complete ordered prefix

**Hypothesis:** as equal-segment results arrive, accumulate every contiguous source-order prefix
and signal later workers once that prefix contains LIMIT rows. Unlike Attempt 077, segment zero
need not satisfy LIMIT by itself; sparse scans never satisfy the condition and remain unchanged.

**Evidence:**

- Base, real64 data, oracle, and runtime protocol are unchanged. Three candidate runs under
  `/tmp/pr113-attempt084-prefix-cancel/` complete 34/34 cases with exact correctness parity.
- Localized middle consistently stops the third segment and falls from 112,089 to 73,670 work
  units, measuring `8.656/8.192/3.734 ms` versus base `17.754/13.007/13.411 ms`. Wrapped targeted
  remains exactly 221,480 work units and retains peak segment concurrency of three including the
  inline graph worker.
- Localized early does not receive the prefix result soon enough to save material work: it charges
  `80,173/80,173/79,407` units versus 80,173 on base. Its latency is no longer worse at
  `9.586/10.777/9.431 ms`, but this is not a deterministic work reduction for the hosted blocker.
- Overall P50 is `3.163/2.945/2.927 ms` and P95 `49.521/50.054/41.504 ms`; the required same-head
  comparator still fails two pairs and observes wrapped DISTINCT dense noise. Process CPU improves
  only in the third run and regresses in the first two; heap and RSS remain unchanged.

**Conclusion:** reject and revert. Correct prefix cancellation arrives after all three equal ranges
are already running. The next experiment uses the mapped dense-count signal to divide only dense
long terms into one extra queued range, preserving the two-worker budget while allowing an
unneeded fourth range to remain unstarted.

### 2026-09-03 - Attempt 085: Queue one extra dense ordered range

**Hypothesis:** for long terms whose mapped posting count can fill LIMIT, divide the graph into four
ranges but retain the existing two background segment workers plus the inline graph worker. The
fourth range should remain queued until the first two ordered ranges fill LIMIT, avoiding both the
three-range boundary miss and Attempt 081's many-task overhead.

**Evidence:**

- Base, real64 fixture, oracle, and runtime protocol are unchanged. Three candidate runs under
  `/tmp/pr113-attempt085-queued-range/` complete all 34 cases with exact correctness parity.
- The executor does not leave the fourth range reliably queued. A segment worker immediately takes
  it when an earlier range completes, before the coordinator records the contiguous prefix.
  Localized-early work becomes `83,556/96,994/77,988` versus 80,173 on base, so the proposed bound
  is neither lower nor deterministic. Latency is `8.548/11.515/8.876 ms`.
- Wrapped targeted correctly remains on three equal ranges and charges 221,567 units including the
  bounded posting-count lookup, versus 221,480 on base. Its latency range overlaps base, but the
  comparator still finds a separate wrapped DISTINCT targeted regression in two pairs.
- Overall P50 is `2.664/3.122/2.921 ms`, P95 `49.254/48.474/51.308 ms`, and the same-head gate fails
  all three pairs. CPU, heap, and RSS provide no compensating resource improvement.

**Conclusion:** reject and revert. Executor dequeue races make a queued range an ineffective work
bound. Stop tuning partition counts; the next attempt must be based on measured call-stack or
allocation evidence for the two exact hosted blockers.

### 2026-09-03 - Attempt 086: Specialize small exact string-id sets

**Hypothesis:** JFR execution samples from ten real64 replays identify `IntOpenHashSet.contains` as
the most frequent top frame in mapped raw scans. Exact long-term matches commonly contain one id
per predicate, so compare arrays of at most eight ids directly and retain the hash set for larger
candidate collections.

**Evidence:**

- The 13-second recording `/tmp/pr113-hotspot-candidate.jfr` contains 589 execution samples. After
  filtering to benchmark and Graphite workers, `IntOpenHashSet.contains` is the top frame in 24
  samples, ahead of the raw CallSite scan lambda at 21. This is profiling evidence only; acceptance
  still uses real64 paired latency and exact correctness.
- Three initial candidate runs under `/tmp/pr113-attempt086-exact-small-set/` complete 34/34 cases
  with exact parity but fail the same-head comparator. Because their base batch was older, a second
  six-fork experiment under `/tmp/pr113-attempt086-paired/` alternates candidate/base,
  base/candidate, candidate/base on the same machine and time window.
- All six paired runs pass correctness. The specialized lookup does not reduce charged scan work,
  and localized early regresses in two aligned pairs, including `9.863 -> 11.861 ms`. Overall P95
  speedup is `0.95x/1.08x/0.88x`; wrapped DISTINCT P95 is also below 1x in two pairs. CPU moves in
  both directions and heap/RSS are unchanged.

**Conclusion:** reject and revert. Frequent sampling inside hash lookup does not establish that a
short linear search is cheaper in the optimized loop. The next profile must retain inclusive stack,
allocation, and blocking context before selecting another production change.

### 2026-09-03 - Attempt 087: Scan dense exact matches in ordered prefix waves

**Hypothesis:** the fixed three-range scan makes all three physical scan slots traverse equal
thirds even when LIMIT is satisfied by an early source-order prefix. Use the posting ends already
stored in the exact sidecar only as a conservative density signal. When the matching string ids can
provide at least LIMIT occurrences, split the graph into six ordered ranges but submit only one
three-slot wave at a time. If the first half supplies LIMIT rows, never submit the second half;
sparse terms retain the existing single three-range wave.

**Evidence:**

- A 200-replay isolated JFR under `/tmp/pr113-profile-isolated.reRNGH/results/` attributes the
  localized-early CPU samples to the mapped raw scan on all three active scan slots, rather than a
  lock, GC, or inactive executor. The extra work is speculative range scanning after the ordered
  prefix can already determine the result.
- Base is exact PR head `efa9fb477718b3e711a9a695c0a30ebfb6439c43`. Candidate evidence is the
  final isolated checkout under `/tmp/pr113-attempt087.qSvzUz/`; the six same-window forks under
  `/tmp/pr113-attempt087-final-paired/` alternate candidate/base, base/candidate, candidate/base.
  Every fork executes the complete 34-case, four-pinned-JAR real64 workload with 8 GiB heap and
  four active CPUs. All candidate outcomes, row counts, ordered digests, response sizes, and hit
  graph ids match the exact base oracle.
- Localized-early charged work falls deterministically from `80,173` to `46,650`. Latency moves
  from `9.513/28.355/9.546 ms` on base to `5.234/3.902/7.034 ms` on candidate. Wrapped targeted
  remains on the original one-wave path, while wrapped non-DISTINCT dense remains at 665 work
  units and `1.379/1.300/1.275 ms` versus base `1.303/1.325/1.369 ms`; there is no repeated
  changed-path regression.
- The complete same-head comparator remains red because one reverse-order fork has unrelated
  wrapped DISTINCT and aggregate P95 noise and two pairs flag the untouched provenance-targeted
  row. This attempt is therefore not represented as a complete PR gate result. Process CPU is
  lower in all three pairs; heap and RSS ranges overlap.
- Posting-end buffers are mapped lazily, only for this planner decision. Missing, truncated, or
  malformed summaries fall back to the original one-wave scan and cannot exclude a row. The new
  deterministic regression forces three physical scan slots to overlap, proves the prefix path
  returns the exact ordered result, corrupts every caller posting end, and proves fallback returns
  the identical result while doing more work. The complete WebGraph test suite and detekt pass.

**Conclusion:** keep as a measured localized-early improvement pending exact hosted confirmation.
It preserves the additive `2 graph + 2 segment` plan and does not create queued-task races: at most
the existing two background segment tasks plus the inline graph worker run at once. The remaining
global wrapped-dense and graph-routing blockers stay open and must pass on the same pushed head
before this optimization is mergeable.

### 2026-09-03 - Attempt 088: Split graph-scoped work into two batches per worker

**Hypothesis:** the 64-graph scoped path assigns one contiguous batch to each graph worker. The
four fixture families differ enough in lookup cost that these large batches can leave a straggler
at the ordered merge boundary. Split the remaining graphs into eight rolling batches while keeping
the same four physical graph workers. This should improve load balance without restoring the
63-task scheduling overhead rejected in Attempt 065.

**Evidence:**

- Base is exact PR head `e53a747a833a5930e761293577be530c61e7fb3f`. Candidate evidence is under
  `/tmp/pr113-attempt088-routing/`. Both revisions use the same fixture-JAR-derived 64 persisted
  graphs, 1,137-row routing oracle, 8 GiB heap, and four active CPUs. Five paired runs cover cold
  and startup-prepared states with alternating candidate/base and base/candidate order.
- Every base and candidate fork completes 1,137/1,137 queries successfully with exact oracle
  parity. Candidate index and access evidence is unchanged: all 64 graphs are admitted and
  observed, retained-index lookup counts remain complete, and peak graph concurrency remains four.
- Cold K=64 median P50 across the five pairs is `0.738 -> 0.728 ms` (`1.014x`) and median P95 is
  `2.448 -> 2.444 ms` (`1.002x`). Individual P50 speedups range from `0.844x` to `1.091x`, so the
  apparent improvement does not repeat independently.
- Startup-prepared median P50 is `0.882 -> 0.763 ms` (`1.156x`), but median P95 regresses from
  `2.941` to `3.025 ms` (`0.972x`). One pair regresses both P50 and P95 to `0.708x/0.752x`.
  CPU, heap, and RSS stay within the existing graph-routing limits but do not compensate for the
  unstable latency direction.

**Conclusion:** reject and revert. Finer graph batches do not provide a stable cold-path benefit
and move startup tail latency in the wrong direction. The K=64 regression is fixed execution-path
overhead rather than fixture-family load imbalance; the next attempt must avoid imposing graph and
segment scheduling on retained-index micro-workloads.

### 2026-09-03 - Attempt 089: Serialize scoped graph lookups while retaining one cold intra-graph build

**Hypothesis:** graphId-routed and request-selected graph sets pay outer graph-task scheduling even
after each mapped graph has a retained string index and only microseconds of lookup work remains.
Walk explicitly scoped sources in deterministic order, but pass a storage marker that permits the
selected graph's first raw scan to use all segment workers and retains the resulting index. The
unscoped global path keeps the existing additive graph/segment plan.

**Evidence:**

- Base is remote main `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`; candidate is Attempt 087 plus
  this isolated scoped-path change. Three paired runs under
  `/tmp/pr113-attempt089g-main-window/` use the fixture-JAR-derived 64 persisted graphs, the complete
  1,137-query routing oracle, 8 GiB heap, and four active CPUs. Every cold, warm, and
  startup-prepared fork passes the comparator with exact row, order, digest, graph identity, and
  response-size parity.
- Exact head `e53a747a833a5930e761293577be530c61e7fb3f` previously failed the routing gate: cold K=64
  P50 was `0.807 -> 3.027 ms`, warm P50 `0.280 -> 0.584 ms`, and startup-prepared P95
  `4.975 -> 8.426 ms`. With this change, the three paired K=64 observations are cold P50
  `0.320/0.313/0.312 -> 0.288/0.268/0.394 ms`, warm P50
  `0.164/0.199/0.175 -> 0.186/0.111/0.122 ms`, and startup-prepared P95
  `2.146/2.131/2.444 -> 1.389/1.469/1.396 ms`; all remain inside the required regression bound.
- The rebuilt final JAR was independently replayed under
  `/tmp/pr113-attempt089-final-routing-cpu4/` with `-XX:ActiveProcessorCount=4`. All three states
  pass again. Cold K=64 P50/P95 is `0.320/2.356 -> 0.292/1.767 ms`; warm is
  `0.164/0.241 -> 0.166/0.253 ms`; startup-prepared is
  `0.332/2.146 -> 0.304/1.486 ms`.
- Lifecycle evidence is exact: cold performs 64 parallel raw scans, then 1,979 retained-index
  lookups across all 64 graphs with peak scan concurrency four; warm and startup-prepared perform
  zero raw scans and 2,043 retained-index lookups across all 64 graphs. Request-selected cases from
  `/api/cypher/graphs` carry an explicit scope bit, so a query without a textual graphId predicate
  receives the same execution policy.
- Deterministic synthetic tests are used only for correctness and path validation: they assert
  that textual graphId and externally selected graph sets serialize outer graph access, that the
  consumer permits intra-graph parallelism, and that a mapped graph builds once then reuses the
  retained index. The complete core, Cypher, WebGraph, and Explore tests, all four detekt tasks,
  and JMH assembly pass.

**Conclusion:** keep. This removes the graph-routing regression without changing the unscoped
global-wide execution path. Exact pushed-head graph-routing, method-compatibility, and global-wide
CI remain mandatory before resolving their review threads or merging.

### 2026-09-03 - Attempt 090: Match the first Method slice while building its compact index

**Hypothesis:** the first exact Method query builds the complete compact metadata index and then
scans its primitive arrays for the requested slice. Evaluate the slice predicate while decoding
the metadata so the first query does not repeat the prefix scan, while still publishing the full
index for later queries.

**Evidence:**

- The experiment used the four real persisted graphs generated from the pinned Android, Tika,
  Hive, and Kotlin compiler fixture JARs, 8 GiB heap, and four active CPUs. It ran the complete
  `4-position/middle` compatibility operation, including scoped and root requests, in three fresh
  forks. Response schemas, ordered digests, graph ids, and byte counts remained exact.
- The old candidate's three observations were `162.102/129.127/129.688 ms`; the fused build-and-
  slice observations were `194.653/193.726/194.625 ms`. Median latency therefore regressed from
  `129.688` to `194.625 ms` (`+50.1%`), and summed process CPU rose from `1.155` to `1.828 s`.
- Matching inside the ByteBuffer decode loop makes index construction branchier and performs
  string-pattern work before the compact primitive arrays are available. Avoiding one later array
  scan does not recover that cost.

**Conclusion:** reject and revert. The method-index construction loop remains unchanged; cold-path
specialization must stay outside it.

### 2026-09-03 - Attempt 091: Scan exact cold Method signatures without materializing a full index

**Hypothesis:** an exact `m.signature` predicate already resolves class, name, and parameter strings
to sorted string-table ids. Before the reusable Method index exists, scan the mapped metadata once
using those ids and materialize only matches. Non-exact queries and all warm queries retain the
existing compact-index path.

**Evidence:**

- Three fresh forks for each `4-position` exact case used the same four fixture-JAR-derived persisted
  graphs, 8 GiB heap, and four active CPUs. The old/new mean latency in milliseconds was
  `152.192 -> 146.217` for early, `152.337 -> 143.970` for middle, and `153.449 -> 148.396` for late.
  Middle improves `5.5%`; its summed process CPU falls from `1.416` to `1.091 s` (`22.9%`). The
  zero-hit control remains flat at `131.538 -> 128.305 ms` with equivalent CPU.
- The complete old and new compatibility manifests are byte-identical. They cover every scoped
  service plus every root grouped result across Android, Tika, Hive, and Kotlin compiler graphs.
- Deterministic tests use a small persisted graph only as a correctness/path gate. They prove exact
  class/name/parameter and optional return-type matching, missing string-table ids, scan counts,
  cancellation without partial index publication, and fallback to the reusable index for non-exact
  patterns. Synthetic data is not used as performance evidence.
- The focused WebGraph suite and detekt pass. The exact pushed-head method-compatibility gate remains
  authoritative for the prior hosted `4-position/middle` regression.

**Conclusion:** keep pending exact hosted confirmation. The change removes cold full-index
allocation from exact signature discovery without altering result order, limits, cancellation, or
the general Method query path.

### 2026-09-03 - Attempt 092: Parallelize cold externally selected multi-graph scans

**Hypothesis:** explicit scope identifies which graphs are eligible; it does not imply that a set of
64 eligible graphs should execute serially. Keep the preferred persisted-index serial policy only
for a single selected graph. Multi-graph selected sets should use the same bounded additive graph
and segment plan as an equivalent `graphId IN` query.

**Evidence:**

- The existing 1,137-case fixture64 workload now moves its K=64 zero-hit request-selected case to
  the first record without adding synthetic or duplicate queries. This runs before all width-one
  queries and index construction, so the cold label represents the production first-request state.
- Three independent fresh JVMs used the fixture-JAR-derived 64 persisted graphs, 8 GiB heap, and
  four active CPUs. The first record completed successfully in `35.740`, `26.305`, and `36.180 ms`,
  accessed exactly all 64 selected graphs, returned the expected empty result, and charged the same
  `151,595` work units each time. All three complete 315-record request-selected manifests have the
  same SHA-256 `fee54d81e6c7a19341ba8a1c236b779772bcd06a6eb63f5e2dfc4798106e81d4`.
- The blocking reproduction on exact parent `5eb7f30` measured the same cold K64 request at
  `1,979-2,194 ms` when scoped serialization was enabled and `137-176 ms` under the parent parallel
  policy. The retained change removes that source-count-independent serial flag only when more than
  one source remains.
- Deterministic execution tests force the first background graph wave to overlap for both textual
  64-id routing and externally selected 64-graph execution. They assert the computed graph-worker
  ceiling, split storage consumers, complete source coverage, and no residual active worker. The
  existing eight-graph compatibility test remains serial, and single-graph scope continues to use
  the preferred persisted-index marker.

**Conclusion:** keep pending exact hosted confirmation. This addresses the cold-first K64 blocker
without changing one-graph graphId latency or exceeding the additive CPU plan.

### 2026-09-03 - Attempt 093: Replace canonical Method contains regexes with literal matching

**Hypothesis:** Cypher `m.name CONTAINS <literal>` is pushed into the mapped Method index as the
canonical regex `.*Pattern.quote(literal).*`. Recognize that exact shape and evaluate it with
`String.contains`, while retaining Java-regex line-terminator behavior and the general regex path.
This should remove regex-engine CPU from the hosted `17-scan/contains` regression.

**Evidence:**

- The experiment used the four complete persisted graphs generated from the pinned Android, Tika,
  Hive, and Kotlin compiler fixture JARs, 8 GiB heap, and four active CPUs. Exact head `4d3732f`
  and the candidate each ran `17-scan/contains` in three fresh JVM forks.
- Exact head latency was `973.660/960.765/975.624 ms`; the candidate measured
  `973.079/978.752/994.907 ms`. Mean wall time therefore regressed from `970.016` to `982.246 ms`
  (`+1.3%`). Summed process CPU changed only from `4.483` to `4.463 s` (`-0.4%`), well below a
  useful or repeatable improvement. RSS-after changed by `-0.7%`.
- All three old and new forks produced the same complete normalized manifest. It covers each of
  the 17 scoped graph responses plus the four root grouped responses with identical schemas,
  ordered digests, graph ids, and response sizes.
- A deterministic persisted-graph test covered quoted literal metacharacters and preserved the
  original regex behavior around line terminators; the focused WebGraph test and detekt passed.

**Conclusion:** reject and revert. Java's compiled regex path is not the material CPU cost in this
workload. The next Method experiment must reduce metadata records visited or descriptors
materialized instead of replacing the per-distinct-string matcher.

### 2026-09-03 - Attempt 094: Restrict exact Method class scans to contiguous metadata ranges

**Hypothesis:** persisted fixture metadata writes each declaring class as one contiguous Method
run. Record those ranges while constructing the existing compact index, then restrict exact-class
queries to the matching run. If a producer writes the same class in multiple runs, mark it
non-contiguous and retain the full scan. This reduces records visited and descriptors materialized
without making ordering a storage-format requirement.

**Evidence:**

- A read-only inspection of the four complete pinned fixture-JAR-derived graphs found exactly one
  run per declaring class: Android has 408,510 methods in 44,287 class runs; Tika 312,788/30,617;
  Hive 404,016/38,217; and Kotlin compiler 249,669/24,255. Class ids are not sorted, so the
  implementation records primitive id-to-range entries rather than assuming binary-search order.
- Three fresh JVM forks per case compared exact head `4d3732f` with this candidate using the same
  four persisted graphs, 8 GiB heap, and four active CPUs. For `17-scan/contains`, mean latency
  improves from `959.974` to `920.337 ms` (`4.1%`) and summed process CPU from `4.349` to `3.666 s`
  (`15.7%`). RSS-after changes by `+1.1%`.
- For the hosted `4-aggregate/count` blocker, mean latency improves from `204.538` to `173.539 ms`
  (`15.2%`) and summed process CPU from `1.439` to `0.839 s` (`41.7%`). RSS-after improves `3.4%`.
- Every old and new fork produces the same complete normalized manifest, covering all scoped
  services and root grouped responses with exact schema, order, digest, graph id, and response-size
  parity. Deterministic persisted-graph tests additionally prove one-record exact ranges, zero-scan
  missing classes, and the full-scan correctness fallback for a deliberately non-contiguous class.
  The focused WebGraph suite and detekt pass.

**Conclusion:** keep pending exact hosted confirmation. The range table is built together with the
existing compact Method index, preserves source order, and adds no new cold metadata pass. It
directly addresses both current Method CPU blockers while retaining a conservative fallback for
graphs that do not group methods by declaring class.

### 2026-09-03 - Attempt 095: Separate scoped graph fanout from persisted-index policy

**Hypothesis:** Attempt 092 used one flag for two independent decisions: whether selected graphs
run concurrently and whether each graph should build or restore its retained CallSite index. The
cold K=64 request consequently used a bounded raw probe across 64 graphs without retaining those
results, then rebuilt all 64 indexes later. Select cold graph sets concurrently while giving every
graph the preferred-persisted storage consumer; serialize loaded or startup-prepared micro-lookups
using a read-only planning capability that does not change storage fallback behavior.

**Evidence:**

- Three paired runs compared remote main `78ce46b57b2` with the candidate on the same machine. Each
  fresh JVM used 64 persisted graphs generated from the four pinned Android, Tika, Hive, and Kotlin
  compiler fixture JARs, an 8 GiB heap, and four active CPUs. All nine cold, warm, and
  startup-prepared comparisons pass the complete graph-routing comparator with no errors and exact
  1,137-query oracle parity.
- Cold now performs exactly 64 index-building scans with peak scan concurrency four, followed by
  exactly 1,979 retained-index lookups distributed `29..38` per graph. Warm and startup-prepared
  perform zero raw scans and exactly 2,043 lookups distributed `30..39` per graph. This restores
  the missing 64 lookups from the exact hosted Attempt 092 failure and removes its duplicate
  bounded-raw work.
- The three paired K=64 P50/P95 observations in milliseconds are cold
  `0.254/1.311 -> 0.357/1.478`, `0.345/2.465 -> 0.355/1.507`, and
  `0.411/2.519 -> 0.342/1.533`; warm `0.100/0.245 -> 0.250/0.364`,
  `0.448/0.803 -> 0.174/0.327`, and `0.156/0.244 -> 0.268/0.340`; and
  startup-prepared `0.190/1.390 -> 0.368/1.539`, `0.255/1.294 -> 0.339/1.481`,
  and `0.263/1.287 -> 0.329/1.478`. Every observation remains inside the comparator's relative-or-
  absolute jitter gate.
- Candidate peak RSS stays within the resource gate in all nine comparisons. The largest increase
  is the first cold pair, `5.94 -> 6.73 GiB` (`13.3%`); the other cold pairs change by `+0.8%` and
  `-0.4%`, and warm/startup-prepared remain within `2%` or improve except for normal measurement
  jitter. The cold build uses the intended additive CPU ceiling rather than graph-worker times
  segment-worker multiplication.
- Deterministic tests prove that textual `graphId IN` and externally supplied graph sets overlap
  their cold graph workers while every storage call receives the preferred-persisted consumer.
  A persisted-sidecar test proves startup readiness before index loading. The full WebGraph test
  class also preserves the memory-budget-denied raw fallback; an initially attempted reuse of the
  storage strategy interface failed that correctness test and was removed before this commit.
  Focused Cypher and WebGraph suites plus all detekt tasks pass.

**Conclusion:** keep pending exact hosted confirmation. Graph concurrency and segment concurrency
remain additive, but graph selection no longer disables per-graph index retention. Ready indexes
avoid K=64 task scheduling, while cold explicitly selected graph sets build each reusable index
once in parallel without changing result order, work accounting, or cancellation fallback.

### 2026-09-03 - Attempt 096: Restore a startup-prepared index on the first preferred lookup

**Hypothesis:** the preferred-persisted storage branch incorrectly requires a raw scan in the
current process before it will load an existing sidecar. A freshly reopened startup-prepared graph
has a zero scan count, so its first node lookup falls through to raw storage despite the planner's
prepared-index decision. Attempt sidecar loading immediately; a missing, invalid, or memory-budget-
denied sidecar still returns `null` and preserves the existing parallel raw-build fallback.

**Evidence:**

- A deterministic persist-close-reopen test now invokes the production
  `PreferredPersistedStringIndexGraphWorkBatchConsumer` on the first lookup. It asserts the exact
  row, one persisted-index lookup, zero raw scans, and `loadedFromPersistence=true`. The prior
  aggregate-only test did not exercise this branch. The complete `GraphStoreTest` and WebGraph
  detekt pass in a clean clone.
- A fresh JVM then replayed all 1,137 startup-prepared routing queries over the 64 persisted graphs
  generated from the four pinned fixture JARs, with an 8 GiB heap and four active CPUs. All queries
  match the existing correctness oracle, access no non-target graph, perform zero raw scans, and
  record exactly 2,043 persisted-index lookups over all 64 graphs (`30..39` per graph).
- The repository comparator passes with no errors. Against remote main, startup-prepared P50/P95
  changes from `0.286/3.318 ms` to `0.082/1.100 ms`; graph-parameter P50/P95 improves by
  `4.22x/3.61x`. Peak RSS changes from `6.44` to `6.17 GB`, while peak used heap is within `0.7%`.

**Conclusion:** keep pending exact hosted confirmation. Startup readiness and storage execution now
agree: an existing valid sidecar is restored on the first preferred lookup, while cold graphs with
no usable sidecar still enter the additive parallel raw-scan and index-build path.

### 2026-09-03 - Attempt 097: Reject impossible exact Method patterns before index construction

**Hypothesis:** a Method predicate containing an exact string that is absent from the persisted
string table cannot match any metadata record. Detect that condition before constructing or
scanning the compact Method index. Prefix and general regex patterns remain on the existing path;
the shortcut applies only when the current `MethodPattern` semantics prove the value is exact.

**Evidence:**

- This directly targets the exact-head hosted failure. Run `33737451433` passed wall time and both
  RSS checks for `17-position`, but its zero-result CPU row repeated `1.42 -> 2.20 CPU-s` and
  `1.49 -> 2.09 CPU-s`, exceeding the 15% gate in both run orders.
- Three locally paired fresh JVM forks compared remote main with the candidate on 17 mapped graphs
  backed by the complete Android, Tika, Hive, and Kotlin compiler fixture-JAR outputs, an 8 GiB heap,
  and four active CPUs. Zero-result wall time is `319.302 -> 190.551`,
  `331.224 -> 191.377`, and `324.766 -> 208.863 ms`; mean wall time improves `39.4%`.
  Process CPU is `674.856 -> 444.620`, `803.325 -> 435.031`, and
  `721.501 -> 541.225 ms`; mean CPU improves `35.4%`. RSS-after improves on average.
- All six complete compatibility manifests have the same SHA-256
  `8dceefad34b7ab32d99c5895c0ae4d354c278c49488a8e79b255514ffccaf9dc`, covering the 17
  scoped HTTP responses and four root grouped responses. A deterministic persisted-graph test
  additionally proves both cold and retained-index calls return the correct empty result with zero
  metadata inspections, and that the cold call does not initialize the index. The focused test and
  WebGraph detekt pass.

**Conclusion:** keep pending exact hosted confirmation. This removes provably useless Method-index
work from the failing zero-result shape without changing fuzzy-pattern behavior, ordering, limits,
materialization, or cancellation for patterns that may match.

### 2026-09-03 - Attempt 098: Bulk-checksum persisted prefilter property ids

**Hypothesis:** deferred validation of persisted prefilter property-id arrays computes CRC one byte
at a time in Kotlin. Retain the full per-id range and ordering validation plus identical work
accounting, but compute CRC from bounded read-only `ByteBuffer` slices using the JDK bulk path.
This may reduce the cold dense DISTINCT cost without weakening sidecar validation.

**Evidence:**

- Three paired fresh JVM forks compared the Attempt 097 parent with this isolated experiment over
  all 34 global-wide cases on the fixture-JAR-derived 64 persisted graphs, an 8 GiB heap, and four
  active CPUs. Every one of the 204 executions succeeded; all six correctness manifests have the
  same SHA-256 `a331a139c575120eb47bec21e2cbafb766f1f68dee2f892939edfa608e105219`.
- Dense wrapped DISTINCT latency does not materially change:
  `3218.110 -> 3287.629`, `3193.078 -> 3297.907`, and
  `3259.313 -> 3135.776 ms`. Charged work is exactly `69,501,268` in every old and new fork, so the
  checksum implementation is not the dominant cost.
- Aggregate P95 changes from `107.465/96.638/97.761 ms` to
  `134.116/107.385/105.843 ms`; two pairs regress. The first wrapped zero row also changes from
  `2.259` to `6.528 ms` in pair one and `6.703` to `6.839 ms` in pair two. Total CPU improves only
  in the third pair and has no stable direction; RSS remains equivalent.
- The complete `GraphStoreTest`, WebGraph detekt, and WebGraph JMH assembly passed in the
  experimental clone, confirming semantic safety but not performance value.

**Conclusion:** reject and revert. The byte-wise CRC is not the dense DISTINCT bottleneck, and the
bulk mapped-buffer pass adds cold-page cost to smaller zero-result queries. No production or test
code from this experiment is retained.

### 2026-09-03 - Attempt 099: Preserve lazy Method streams around exact-pattern rejection

**Hypothesis:** Attempt 097's impossible-pattern check is safe only when it preserves the public
`StreamingMethodLookup` contract. Wrapping both the check and `methodIndex()` access in the returned
sequence should keep the fast empty result while deferring all index work until sequence consumption.

**Evidence:**

- On exact parent `0530cd6b565cb261721a2eb616acff62707a92c1`, a persisted-graph regression test first
  captured the review report: constructing `graph.methods(MethodPattern())` immediately initialized
  the retained Method index and failed before consuming the sequence.
- With the lazy wrapper, the same test proves the Method index remains uninitialized after sequence
  construction and initializes only when `take(1)` consumes it. The existing result, count,
  DISTINCT ordering, and UNWIND assertions continue to pass without initializing full graph metadata.
- The complete WebGraph test suite and WebGraph detekt pass in a clean regular clone. This test is a
  correctness and execution-path fixture only; no synthetic timing result is used as performance
  evidence. The consumed Method lookup still delegates to the same index and matcher, so this step
  makes no new latency claim.

**Conclusion:** keep. The exact impossible-pattern shortcut remains, while abandoned or deferred
Method sequences no longer pay eager CPU or heap cost. The exact pushed-head CI and Method
compatibility gate remain mandatory before resolving the review thread.

### 2026-09-03 - Attempt 100: Hoist indexed DISTINCT projection ahead of scheduling policy

**Hypothesis:** the prepared-index scheduling branch serializes small retained lookups before the
indexed DISTINCT projection is considered. Evaluate that projection first so DISTINCT wide queries
can merge bounded per-graph index rows directly, independent of whether ordinary node lookup would
use graph fanout or serial execution.

**Evidence:**

- Three paired fresh-JVM runs compared remote main `78ce46b57b2` with this candidate on the same
  fixture-JAR-derived 64 persisted graphs, an 8 GiB heap, and four active CPUs. All 204 executions
  per revision succeeded in every pair and all six complete correctness manifests have the same
  SHA-256 `a331a139c575120eb47bec21e2cbafb766f1f68dee2f892939edfa608e105219`.
- Aggregate P50 improves by `84.01x`, `107.02x`, and `78.09x`; P95 improves by `9.61x`, `7.92x`,
  and `7.71x`. Candidate P50 is `2.58..3.05 ms`, candidate P95 is `49.3..50.6 ms`, and the worst
  wrapped-query speedup is `6.19x`. Total process CPU falls from `10.7..11.3 s` to `1.63..1.78 s`,
  while peak RSS falls from `5.73..6.10 GB` to `3.93..3.94 GB`.
- An incremental comparison with the Attempt 099 parent confirms the targeted dense DISTINCT rows
  fall from roughly `3.1 s` to `50..63 ms`. A focused regression test proves graphs reported as
  prepared still invoke `StringPropertyDisjunctionDistinctProjection` rather than the ordinary
  serial lookup branch.
- The first screening run also exposed five repeated dense non-DISTINCT micro-regressions. They are
  retained as explicit guardrail failures rather than hidden by the aggregate gain; the following
  attempt must repair them before this series can be pushed or its review comment resolved.

**Conclusion:** keep as the 5x-path primitive, pending the next isolated guardrail fix and exact
hosted confirmation. The change only reorders an existing correctness-preserving projection path;
it does not alter result merging, graph order, limits, cancellation, or storage data.

### 2026-09-03 - Attempt 101: Serialize prepared projection sources after the leading graph

**Hypothesis:** once the leading graph satisfies part of a bounded DISTINCT projection, scheduling
the remaining prepared sources serially may avoid graph-task overhead without changing the indexed
projection or selected-value verification.

**Evidence:**

- A same-machine screen compared Attempt 100 with this isolated scheduling change on the pinned
  fixture-JAR-derived 64 persisted graphs. Complete correctness manifests remained identical.
- The dense DISTINCT rows did not improve consistently: three observations changed from
  `63/53/56 ms` to `71/77/72 ms`. Total process CPU and peak RSS were flat or worse, so the added
  policy branch supplied no measurable benefit to offset its complexity.

**Conclusion:** reject and revert. Prepared projection sources retain the existing execution policy;
no production or test code from this experiment is retained.

### 2026-09-03 - Attempt 102: Preserve bounded raw-leading probes for prepared non-DISTINCT queries

**Hypothesis:** Attempt 100 exposes indexed DISTINCT before scheduling, but the prepared-index
serial branch also captures dense bounded non-DISTINCT queries that should satisfy their limit from
a raw projected leading segment. Make that eligibility explicit before the serial decision; retain
the existing additive graph-plus-segment parallel continuation when the leading segment is short.

**Evidence:**

- Three paired fresh-JVM runs against remote main used the same pinned fixture-JAR-derived 64
  persisted graphs, 8 GiB heap, and four active CPUs. All 204 executions per revision succeeded and
  all six complete correctness manifests match exactly.
- This repairs all five repeated micro-regressions found by Attempt 100. Aggregate P50 improves by
  roughly `77..80x`, aggregate P95 by `7.07..7.84x`, and the worst wrapped-query speedup is `5.35x`
  in the first paired screen. No synthetic timing is used as evidence.
- A second screen of the simplified condition preserves `76.6..83.7x` P50 and `7.33..7.61x` P95.
  Two worst-row observations fall to `4.50..4.81x` while the same localized early row varies from
  `5.3..7.1 ms`, so the exact hosted gate remains required before claiming a stable 5x floor.
- The regression test now marks the dense bounded graph as startup-prepared and still requires the
  raw projection path, proving readiness cannot accidentally force node materialization or the
  ordinary serial lookup branch.

**Conclusion:** keep pending exact hosted confirmation. The change restores the already bounded
raw-leading fast path for non-DISTINCT queries without broadening eligible query shapes, changing
results, or multiplying graph and segment worker budgets.

### 2026-09-03 - Attempt 103: Broaden raw-leading probes to larger disjunctions

**Hypothesis:** increasing the bounded raw-leading term ceiling from four to 128 may let more wide
queries avoid retained-index setup while still satisfying `LIMIT` from the leading graph.

**Evidence:**

- A paired screen used the same fixture-JAR-derived 64 persisted graphs and complete correctness
  comparison. Aggregate P95 speedup ranged from `5.81x` to `7.54x`, but the worst wrapped-query
  speedup fell to `4.65x` and the localized early row remained unstable at `6.25..6.92 ms`.
- The broader rule therefore does not establish the requested 5x floor and risks shifting sparse
  large-disjunction queries away from their reusable index without a compensating stable gain.

**Conclusion:** reject and revert. Keep the existing four-term eligibility bound; no production or
test code from this experiment is retained.

### 2026-09-03 - Attempt 104: Defer cold sidecar readiness and split the first K64 request

**Hypothesis:** treating the mere presence of a sidecar file as an unconditional serial-scheduling
signal serializes the first explicitly selected K=64 query while each graph restores and validates
its index. Keep sidecar readiness semantics, but consult the existing storage strategy: unopened
mapped indexes explicitly prefer parallel work, while loaded small indexes prefer serial lookup.
Give a balanced K=64 cold set the existing segment-split consumer so worker budgets remain additive.

**Evidence:**

- A fresh JVM replayed all 1,137 routing queries over the 64 persisted graphs generated from the
  four pinned fixture JARs, with an 8 GiB heap and four active CPUs. Every query matches the
  correctness oracle; the manifest SHA-256 is
  `35fc69539c3080dbb801cca4ec7f1e7541f3ccd190d8774861f6109f7c58b6dd`.
- The final implementation's fresh confirmation lowers the first cold K=64 request from the exact
  hosted candidate's `4,519.689 ms` to `39.552 ms`.
  It accesses exactly the selected 64 graphs, no non-target graph, performs no full raw scan, and
  charges `151,595` prefilter work units. The remaining workload restores all 64 valid sidecars and
  records exactly 1,979 retained-index lookups distributed `29..38` per graph.
- Aggregate candidate P50/P95 is `0.077/14.056 ms`; total process CPU is `5.941 s` and peak RSS is
  `6.319 GB`. These numbers are retained for audit, but the first-request row—not aggregate
  percentiles—is the direct regression evidence for this attempt.
- Deterministic tests preserve startup sidecar readiness and first-lookup restoration with zero raw
  scans. Textual and externally supplied 64-graph selections prove graph fanout uses the configured
  split worker count, while loaded prepared-index mocks retain serial scheduling. The pressure gate
  now accepts either a genuine 64-graph parallel build or successful restoration of all 64
  sidecars, and independently fails a hidden first-request latency regression above 15% or 250 ms.
- The combined WebGraph suite exposed two single-source lifecycle regressions from the preceding
  DISTINCT hoist. A zero-hit restored sidecar was immediately released, while a fresh exact query
  could be forced away from building its reusable index. The fix prefers persisted storage only
  when the one source actually reports a prepared sidecar and limits zero-hit release to
  multi-source provenance work; both existing lifecycle tests now pass.

**Conclusion:** keep pending exact hosted confirmation. This removes the observed 4.5-second cold
serialization without changing selected-graph isolation, result order, lookup accounting, memory
admission, or the additive graph-plus-segment parallelism budget.

### 2026-09-03 - Attempt 105: Parallelize prepared wide selected sets

**Hypothesis:** a retained index makes one graph lookup cheap, but does not make 64 independent
graph lookups free. For a balanced wide selected set, keep the existing NCPU split even after every
index is prepared: half of the budget schedules graphs and half remains available to storage
segments. Smaller selected sets retain the serial prepared-index policy so task overhead does not
regress their micro lookups.

**Evidence:**

- Exact hosted head `5aa60556fba5d99cc6ce5aec754cb5ff7bb021a7` established the failure before this
  change. All six routing correctness manifests have SHA-256
  `35fc69539c3080dbb801cca4ec7f1e7541f3ccd190d8774861f6109f7c58b6dd`, but prepared K=64
  graph-set P50/P95 was `4.293/17.659 ms` cold, `1.663/1.917 ms` warm, and
  `5.315/14.860 ms` startup-prepared. Instrumentation reported zero graph workers for candidate
  prepared lookups, directly confirming serialization.
- Local fixture-JAR screens initially looked promising, but exact hosted head
  `717de5c3904c676624edb4ea48fed89bd378367c` rejects the result. Instrumentation proves the
  intended plan really ran (`availableProcessors=4`, graph workers and peak `2`, segment workers
  and peak `2`), while K=64 still regresses in all three states: cold P50/P95
  `0.843/3.047 -> 5.504/13.808 ms`, warm `0.561/0.919 -> 2.541/4.220 ms`, and
  startup-prepared `1.493/3.595 -> 4.080/13.443 ms`.
- The exact hosted run completes all 1,137 cases in every state with no timeout or failure; all six
  correctness files retain the same SHA-256
  `35fc69539c3080dbb801cca4ec7f1e7541f3ccd190d8774861f6109f7c58b6dd`. Selected-source and
  non-target checks also pass. The failure is therefore scheduling cost, not a semantic or routing
  error: roughly 63 tiny per-graph futures cost more than the prepared index lookups they wrap.
- Global-wide still clears the 5x aggregate and wrapped-shape floors in all three pairs, but the
  dense non-DISTINCT micro row repeats a `3.019 -> 4.642 ms` regression in all three independent
  pairs. That row accesses only the leading graph, so this attempt also fails its existing
  regression guard despite lower aggregate CPU, heap, and RSS.

**Conclusion:** reject and revert. Per-graph fanout is the wrong granularity for prepared indexes;
the next attempt must batch contiguous graphs per worker instead of submitting one future per graph.
No production or test code from this experiment is retained.

### 2026-09-03 - Attempt 106: Reuse fixed workers for prepared global-wide lookups

**Hypothesis:** prepared global-wide lookups still need graph-level concurrency, but one submitted
future per graph costs more than the retained index lookup itself. Keep the NCPU-balanced graph and
segment budgets, but submit only the fixed graph-worker count and feed those workers from a bounded
source-ordered queue. Retained graph-scoped queries stay serial; cold graph-scoped queries retain
parallel index restoration.

**Evidence:**

- Three fresh-JVM screens compare remote main `78ce46b57b2d` with this attempt over the same 64
  persisted graphs generated from the four pinned fixture JARs, an 8 GiB heap, and four active
  CPUs. All 204 candidate queries complete successfully and match the complete correctness oracle.
  Source-order and bounded-access checks pass; the candidate accesses the expected 1,452 graphs.
- Aggregate global-wide P50 improves by `153.96x`, `168.21x`, and `147.86x`; P95 improves by
  `20.71x`, `21.15x`, and `20.43x`. The wrapped-shape P95 floor improves by `16.71x`, `15.82x`,
  and `15.63x`, clearing the 5x target in every pair. Candidate process CPU is `1.65..1.87 s`,
  peak used heap is `3.71 GB`, and peak RSS is `4.23..4.35 GB`; each is below the paired main
  observation.
- Instrumentation on four active CPUs reports the intended additive split: configured and observed
  graph workers are `2`, while configured and observed segment workers are `2`. Deterministic tests
  prove that the fixed workers execute concurrently, merge in graph order, stop at a bounded
  speculative suffix, propagate failures, and leave graph-scoped retained micro-lookups serial.
- The local routing screen keeps the first cold selected K=64 query at `40.208 ms` versus main's
  `991.347 ms`, restores exactly 1,979 post-build retained-index lookups across all 64 graphs, and
  preserves all 1,137 oracle-valid results. Its six-sample cold K=64 P50/P95 is nevertheless
  `1.071/4.664 ms` versus `0.843/3.047 ms`; the single `4.664 ms` literal-zero observation exceeds
  the existing absolute jitter guard. This result is recorded as a blocker, not hidden by the
  global-wide gain.
- Exact hosted head `64f2caf07901e4bf2ea40dfe6f6597783f87047b` confirms the aggregate gain on
  the same 64 persisted fixture-JAR graphs: global-wide P50 improves by `40.55x`, `40.34x`, and
  `52.99x`, while P95 improves by `6.92x`, `5.43x`, and `7.22x`. Correctness, bounded access, the
  additive `2 graph + 2 segment` worker split on four active CPUs, and all resource ceilings pass.
- The exact hosted result still rejects the attempt as a merge candidate. One DISTINCT shape has
  only `4.71x` P95 in the first pair, and the dense/localized one-source rows repeatedly regress
  because the candidate probes all 64 prepared-sidecar capabilities before discovering that the
  leading graph already satisfies `LIMIT`. The graph-routing companion also rejects selected
  K=64 in cold, warm, and startup-prepared states; instrumentation shows candidate segment scans
  fell from 64 to zero because the selected-set serial route incorrectly forced storage itself to
  stay serial.

**Conclusion:** keep the fixed-worker primitive as the measured global-wide foundation, but reject
this head as mergeable. Follow with isolated guardrail attempts for deferred capability probing,
selected K=64 segment execution, and the remaining DISTINCT provenance overhead. Do not resolve
either benchmark review until one exact head passes both hosted gates.

### 2026-09-03 - Attempt 107: Defer prepared capability probes past the leading LIMIT

**Hypothesis:** dense and distribution-localized global-wide queries pay an avoidable fixed cost by
checking whether all 64 graphs have prepared string sidecars before reading the leading graph. Treat
prepared batching as a possibility initially; probe the leading source first, return immediately if
it fills `LIMIT`, and perform the all-source capability check only when later graphs are required.
This changes neither the NCPU-balanced worker allocation nor graph-scoped execution.

**Evidence:**

- Base revision is exact hosted head `64f2caf07901e4bf2ea40dfe6f6597783f87047b`; candidate exact
  hosted evidence is pending. Both revisions use the same 64 persisted graphs generated from the
  four pinned fixture JARs, an 8 GiB heap, and four active CPUs.
- A deterministic execution test supplies 64 prepared-capable graphs with a leading match and
  `LIMIT 1`. It verifies the returned value and source access, and additionally proves that the
  leading fast path performs zero prepared-capability checks instead of probing all 64 sidecars.
- The complete `CrossGraphCypherExecutorTest` class passes locally after the change. Full module
  correctness, coverage, real-data latency, CPU, and memory evidence remain pending before this
  attempt can be kept.

**Conclusion:** pending exact real-64 validation. Keep only if the repeated dense/localized
regressions disappear without weakening correctness, resource, worker-budget, or graph-routing
guards.

### 2026-09-03 - Attempt 108: Restore segmented storage for selected K=64

**Hypothesis:** a graph-scoped query over 64 selected graphs should avoid graph-level scheduling
overhead once its persisted indexes are prepared, but serial graphs do not require serial storage.
Keep the selected graphs source-ordered and execute one graph lookup at a time, while restoring the
balanced segment consumer inside each lookup. K=1 and K=8 retain their serial persisted-index path;
the unscoped global-wide graph-worker policy is unchanged.

**Evidence:**

- Base revision is Attempt 107; candidate exact hosted evidence is pending. Both use the same 64
  persisted graphs generated from the four pinned fixture JARs, an 8 GiB heap, and four active CPUs.
- Exact hosted Attempt 106 identifies the mechanism directly in all three graph-routing states:
  main records 64 cold parallel segment scans, candidate records zero, and selected K=64 P95
  regresses from `3.880` to `12.710 ms` cold, `0.787` to `2.246 ms` warm, and `2.822` to
  `9.873 ms` startup-prepared.
- The deterministic prepared-selected-set test continues to prove one active graph worker,
  source-complete access, no redundant prepared-capability probes, and no leaked work. It now also
  requires every K=64 storage lookup to receive the balanced split consumer and its configured
  segment-worker budget.
- A same-machine real-64 cold comparison rejects the change. K=64 candidate literal/parameter
  zero-hit remains `5.064/0.950 ms` versus main's `0.789/0.183 ms`, while targeted remains
  `3.284/1.241 ms` versus `2.200/0.220 ms`. The split consumer also makes four-predicate retained
  index lookups schedule tiny range tasks; this is the wrong concurrency level for prepared data.

**Conclusion:** reject and revert. Prepared selected sets need graph concurrency, not segment
concurrency inside each already-small retained lookup. Attempt 111 replaces this with fixed graph
workers and restores serial storage for the scoped path.

### 2026-09-03 - Attempt 109: Skip unused DISTINCT prepared probes

**Hypothesis:** the DISTINCT projection path checks prepared-storage capability once for the leading
graph and again for every provenance graph, although that result only controls the single-source
consumer. A 64-graph global-wide query therefore performs 65 irrelevant sidecar checks. Short-circuit
the capability lookup unless the query has exactly one candidate source; retain the existing
balanced graph and segment execution for all multi-graph DISTINCT work.

**Evidence:**

- Base revision is Attempt 108; candidate exact hosted evidence is pending. Both use the same 64
  persisted graphs generated from the four pinned fixture JARs, an 8 GiB heap, and four active CPUs.
- Exact hosted Attempt 106 misses the required DISTINCT P95 floor in one pair by a narrow margin:
  `1,345.983 -> 285.674 ms`, or `4.71x`, while the other two pairs reach `6.62x` and `6.77x`.
- The existing 64-source DISTINCT provenance test now equips every source with the prepared
  capability and asserts zero capability probes. It still proves leading-only initial projection,
  all-source provenance lookup, exact merged graph IDs, and the balanced segment allocation.

**Conclusion:** pending exact real-64 validation. Keep only if the wrapped DISTINCT P95 floor clears
5x in every pair without changing the correctness oracle or graph-scoped single-source policy.

### 2026-09-03 - Attempt 110: Use retained CallSite indexes before trigram prefiltering

**Hypothesis:** selected K=64 queries already have a retained `graph.callsite-string-index` for every
graph, but the candidate probes `graph.callsite-trigram-prefilter` before consulting that in-memory
index. Restore the constant-time retained-index check ahead of prefilter loading; keep the prefilter
and raw segmented paths unchanged when no index is retained.

**Evidence:**

- Base revision is Attempt 109; candidate exact hosted evidence is pending. Both use the same 64
  persisted graphs generated from the four pinned fixture JARs, an 8 GiB heap, and four active CPUs.
- A same-machine real-64 cold screen confirms the remaining selected-set overhead before this
  change: candidate K=64 literal/parameter zero-hit rows take `5.064/0.950 ms` versus main's
  `0.789/0.183 ms`; targeted rows take `3.284/1.241 ms` versus `2.200/0.220 ms`. Access counts,
  index lookup counts, row digests, and the correctness oracle match, isolating per-lookup planner
  overhead rather than extra graph scanning.
- A persisted-graph test first restores the CallSite index, then queries it with a split consumer.
  It requires the exact node ID, one retained-index lookup, and an uninitialized trigram prefilter,
  proving the fast path does not touch the additional sidecar.
- The same-machine real-64 cold rerun rejects the hypothesis. Selected K=64 zero-hit remains
  `4.706/1.016 ms` for literal/parameter forms versus main's `0.789/0.183 ms`; targeted remains
  `3.252/1.039 ms` versus `2.200/0.220 ms`. The change is within run noise relative to Attempt 109
  and does not remove the blocker.

**Conclusion:** reject and revert. The retained index was already reached cheaply enough after
prefilter state was prepared; moving this check does not address the per-graph scheduling cost. No
production or test code from this experiment is retained.

### 2026-09-03 - Attempt 111: Batch prepared selected sets with fixed graph workers

**Hypothesis:** selected K=64 retained-index lookups are individually too small for segment tasks or
one Future per graph, but their aggregate work can use the CPU efficiently. Reuse the fixed bounded
graph-worker queue from Attempt 106, allocate up to all available CPUs to graph workers for this
scoped prepared path, and keep every graph's storage lookup serial. The leading graph remains
synchronous for source-order `LIMIT` early return. Cold selected sets and unscoped global-wide
queries retain their existing additive NCPU split.

**Evidence:**

- Base revision is Attempt 110; candidate exact hosted evidence is pending. Both use the same 64
  persisted graphs generated from the four pinned fixture JARs, an 8 GiB heap, and four active CPUs.
- Attempt 105 proved that submitting one Future per prepared graph is too expensive; Attempt 106
  proved that a fixed worker queue removes that allocation/scheduling pattern for global-wide work.
  This attempt reuses the same tested primitive instead of adding another executor or persisted
  format.
- The prepared selected-set test requires exactly the graph-scoped worker budget to run
  concurrently, all 64 sources to be visited for a zero-hit query, graph-order completion with no
  leaked workers, zero redundant capability probes, and a serial persisted-storage consumer on
  every graph. Existing cold selected-set tests continue to require the additive split consumer.
- A same-machine real-64 cold run confirms the scheduling hypothesis for full-set work. Relative
  to Attempt 109, K=64 literal zero-hit falls from `5.064` to `0.774 ms` and literal targeted from
  `3.284` to `1.429 ms`; main is `0.789/2.200 ms`. All 1,137 queries match the oracle, all expected
  graphs are accessed, and the process completes in `2.205 s` versus main's `5.349 s`.
- The attempt is not independently mergeable yet: leading-hit rows still allocate all 64 scanner
  objects before returning. K=64 candidate P50 is therefore about `0.681 ms` versus main's
  `0.255 ms`, exceeding the absolute regression guard even though P95 improves. The next isolated
  attempt must defer suffix scanner construction until the leading graph misses `LIMIT`.

**Conclusion:** keep the fixed scoped-worker primitive as the measured K=64 zero/targeted fix, but
do not merge this head. Attempt 112 must remove leading-hit setup overhead before exact hosted
validation.

### 2026-09-03 - Attempt 112: Defer suffix scanner construction past leading LIMIT

**Hypothesis:** every balanced query constructs 64 `DirectStringSourceScanner` instances before
probing graph zero. Dense selected-set queries then return from graph zero, so the other 63 scanner
objects and their captured state are pure latency/allocation overhead. Construct only the leading
scanner synchronously; create suffix scanners inside fixed-worker tasks after the leading source
proves more graphs are required. Non-balanced execution and source-order merging remain unchanged.

**Evidence:**

- Base revision is Attempt 111; candidate exact hosted evidence is pending. Both use the same 64
  persisted graphs generated from the four pinned fixture JARs, an 8 GiB heap, and four active CPUs.
- Attempt 111's same-machine real-64 run already proves the fixed scoped workers repair zero and
  targeted K=64 latency, but its six-row K=64 P50 remains about `0.681 ms` versus main's `0.255 ms`.
  The three leading-hit observations access only graph zero, isolating setup before graph access as
  the remaining median blocker.
- Existing leading-LIMIT tests require exact result values, graph-zero-only access, and no
  all-sidecar capability probe for global-wide execution. The prepared selected-set test separately
  proves fixed-worker concurrency, complete zero-hit access, and clean worker shutdown.
- The same-machine real-64 cold rerun does not support the allocation hypothesis. K=64 candidate
  P50 remains about `0.690 ms` versus main's `0.255 ms`; dense literal/parameter rows are
  `0.757/0.622 ms` versus `0.266/0.255 ms`. The change is within run noise relative to Attempt 111.

**Conclusion:** reject and revert. Scanner allocation is not the material leading-hit overhead. No
production code from this experiment is retained; the next attempt must defer the scoped fanout
decision itself until after the leading result, removing its capability check from dense queries.

### 2026-09-03 - Attempt 113: Defer scoped fanout planning past the leading LIMIT

**Hypothesis:** prepared K=64 selected-set queries call the prepared-capability probe before reading
graph zero. Move both that probe and suffix scanner construction after the leading lookup, so a
dense `LIMIT 200` result can return without planning the remaining 63 graphs. A leading miss still
selects the fixed all-CPU graph-worker queue from Attempt 111; cold and unscoped paths retain their
additive graph/segment split. This attempt introduces no storage format or sidecar change.

**Evidence:**

- Base revision is Attempt 112; the candidate was validated from an isolated worktree over
  `efcb078`. Both use the same 64 persisted graphs generated from the four pinned fixture JARs, an
  8 GiB heap, and four active CPUs.
- The complete 80-test `CrossGraphCypherExecutorTest` class passes. It covers the selected and
  unscoped leading-return paths, cold `2 + 2` allocation, prepared fixed-worker fanout, source-order
  LIMIT behavior, cancellation, and exact result/provenance semantics.
- The 1,137-case real-64 routing replay matches its independent correctness oracle with zero
  failures and zero timeouts. The change does not improve the K=64 median: candidate P50/P95 are
  `0.771/1.657 ms` versus main's `0.255/2.200 ms`. Dense literal/parameter rows remain
  `0.761/0.612 ms`, effectively unchanged from Attempt 112's `0.757/0.622 ms`. The leading split
  lookup also increases retained-index lookups from the expected `1,979` to `2,042`.
- The separate 34-case unscoped real-64 replay remains healthy but does not justify this scoped
  change: all results match the oracle, P50/P95 are `3.215/52.010 ms` versus main's
  `238.520/408.163 ms` (`74.20x/7.85x`), wrapped-case-insensitive P95 is `7.39x`, and the runtime
  records exactly `2 graph + 2 segment` workers. Candidate process CPU is `1.704 s` versus
  `10.567 s`, peak used heap is `3.55 GiB` versus `4.65 GiB`, and peak RSS is `3.97 GiB` versus
  `5.73 GiB`.

**Conclusion:** reject and revert. The capability probe and eager scanner construction are not the
material K=64 median overhead, and changing the leading consumer adds index work. No production or
test code from this experiment is retained. The unscoped 5x result and NCPU split remain established
by the earlier kept attempts; the next isolated change must target the selected-set planner or
fixed-worker overhead measured before graph-zero lookup.

### 2026-09-04 - Attempt 114: Prefer the retained index with a split fallback

**Hypothesis:** Attempt 113 changed the leading prepared graph to a split consumer while removing
the capability probe, so it no longer exercised the same retained-index path as Attempt 111. Add a
dual-purpose work consumer: prefer the already-retained index when present, but retain the balanced
segment budget as a correctness-preserving fallback when it is absent. Defer scoped fanout planning
until graph zero misses LIMIT. This isolates the capability probe without sacrificing the cold
selected-set `2 + 2` contract and introduces no storage-format change.

**Evidence:**

- Base revision is `e2d6123`; the candidate was built and measured in an isolated worktree using
  the same 64 persisted graphs from the four pinned fixture JARs, an 8 GiB heap, and four active
  CPUs. The complete 80-test `CrossGraphCypherExecutorTest` class and Cypher detekt both pass.
- The 1,137-case real-64 routing replay matches its independent correctness oracle with zero
  failures and zero timeouts. K=64 P50/P95 remain `0.685/1.187 ms` versus main's
  `0.255/2.200 ms`; dense literal/parameter latency is `0.777/0.548 ms`, still within the same
  range as Attempts 111 through 113. The candidate therefore still violates the K64 absolute
  median regression guard.
- The hybrid leading lookup increases retained-index lookups to `2,043`, versus the gate's expected
  `1,979` post-build accesses, while zero/targeted K64 rows improve only in the already-repaired
  tail. The full replay takes `2.142 s`, process CPU is `6.285 s`, and it records up to four graph
  workers; none of those measurements identifies a benefit for the leading dense path.

**Conclusion:** reject and revert. Preserving the retained-index preference while removing the
capability probe still does not reduce K64 median latency and adds one leading index lookup per
graph over the replay. No production or test code from this experiment is retained. The next
attempt must bypass the balanced scanner machinery for the synchronous leading result itself,
matching main's direct source loop before any suffix fanout is considered.

### 2026-09-04 - Attempt 115: Execute the leading selected source directly

**Hypothesis:** main's graph-scoped fast path projects a synchronous source directly, whereas the
balanced candidate routes even a graph-zero dense hit through `DirectStringSourceScanner` and its
iterator/batch machinery. Keep the existing fanout decision and suffix workers, but execute the
leading graph with the original direct candidate/projection loop. This should remove the remaining
sub-millisecond K64 median regression without affecting unscoped `2 + 2` execution or storage.

**Evidence:**

- Base revision is `37439a1`; the candidate was tested in an isolated worktree with the same 64
  persisted fixture-JAR graphs, an 8 GiB heap, and four active CPUs. Cypher detekt and all 80
  `CrossGraphCypherExecutorTest` cases pass.
- All 1,137 real-64 routing cases match the independent oracle with zero failures and zero timeouts.
  The direct loop materially reduces leading dense K64 latency: literal/parameter rows fall from
  Attempt 111's `0.681/0.506 ms` to `0.382/0.335 ms`. K64 P50 falls to `0.507 ms`, only about
  `2 us` beyond the comparator's allowed `0.25 ms` absolute delta over main's `0.255 ms`.
- The attempt does not repair the cold lifecycle contract. Because the existing planner still
  treats a persisted sidecar as equivalent to an already-retained index, the first cold K64 lookup
  restores all 64 indexes. The replay records `2,043` index lookups distributed `30..39` per graph,
  rather than the required `1,979` post-build lookups distributed `29..38`; K64 targeted tail also
  remains noisy at `1.837/0.725 ms` for literal/parameter forms.

**Conclusion:** reject as an independently mergeable attempt and revert. The direct leading loop
does remove a measured median cost, but retaining it alone leaves the cold lifecycle gate red. The
next attempt must combine this direct path with an in-memory retained-index hint: a disk sidecar's
existence must not force cold selected sources away from the balanced raw/prefilter path.

### 2026-09-04 - Attempt 116: Separate retained lookup cost from cold split work

**Hypothesis:** a persisted CallSite index file is not proof that its index is already retained in
memory. Expose that distinction as an in-process graph capability: a cold selected set keeps the
balanced graph/segment path, while a retained selected set uses the cheaper source-ordered serial
lookup loop. Preserve Attempt 115's direct leading projection, reuse stateless no-op work consumers,
and precompute each query's storage predicates once instead of allocating them for every graph.
This changes no persisted format and leaves unscoped global-wide fanout on the NCPU split.

**Evidence:**

- Base revision is `8fb0a2a`; candidate is this Attempt 116 commit, validated from the matching
  clean snapshot at `/tmp/pr113-attempt116b-test.XDfCRZ/repo`. All measurements use the same 64
  persisted graphs generated from the four pinned Android, Tika, Hive, and Kotlin compiler fixture
  JARs at `/tmp/pr113-attempt095-fixture64/`, an 8 GiB heap, and four active CPUs. No synthetic graph
  supplies performance evidence.
- All 80 `CrossGraphCypherExecutorTest` cases, the mapped `GraphStoreTest`, and core, Cypher, and
  WebGraph detekt pass. The selected-set test now proves retained lookups visit all 64 graphs in
  source order with one active caller and the persisted-storage consumer; the adjacent cold test
  continues to require concurrent graph work and the balanced split consumer.
- Two independent 1,137-case cold routing replays both match the trusted oracle with zero failures
  and zero timeouts. Both record exactly `1,979` retained-index lookups distributed `29..38` per
  graph, rather than the rejected `2,043` eager-load lifecycle. K=64 candidate P50/P95 are
  `0.300/1.584 ms` and `0.422/1.405 ms`, both below the comparator limits derived from main's
  `0.255/2.200 ms`; the complete routing gate passes in both runs.
- The separate 34-case unscoped real-64 replay matches every result digest with zero failures and
  zero timeouts. Candidate P50/P95 are `3.354/47.361 ms` versus main's `238.520/408.163 ms`, or
  `71.10x/8.62x`. Wrapped case-insensitive DISTINCT P95 is `75.542 ms` versus `393.313 ms`
  (`5.21x`). The runtime reports four available CPUs, exactly `2 graph + 2 segment` workers, and
  observed peaks of two on each side. Process CPU is `1.770 s`, peak used heap is `3.56 GiB`, and
  peak RSS is `4.01 GiB`.

**Conclusion:** keep. The in-memory hint preserves the cold split lifecycle, the retained serial
path removes the sub-millisecond scheduling regression with repeatable gate margin, and the actual
unscoped target remains above 5x P95 with the required additive NCPU allocation. Exact hosted
three-pair validation remains required before merge.

### 2026-09-04 - Attempt 117: Reuse projected provenance and reject absent tuples earlier

**Hypothesis:** the remaining wrapped DISTINCT tail repeats work after the leading projection has
already filled `LIMIT 200`. Its provenance phase rechecks the leading graph and searches each
selected four-property tuple in output-column order on every suffix graph. Preserve provenance
already established by the initial projection, and probe each graph's highest-cardinality indexed
property first so absent tuples short-circuit before the other string-table lookups. This changes no
persisted format, route, result ordering, or NCPU graph/segment allocation.

**Evidence:**

- Base is exact Attempt 116 head `d9497e1`; candidate is this attempt. Three alternating fresh-JVM
  pairs use the same 64 persisted graphs generated from the four pinned fixture JARs, an 8 GiB
  heap, and four active CPUs. All 204 candidate observations match the complete oracle; the target
  row returns the same 200 rows, digest, and graph provenance in every pair.
- `global-wide-wrapped-case-insensitive-distinct-dense` falls from `79.167` to `55.184 ms`,
  `85.345` to `49.353 ms`, and `93.491` to `54.873 ms`, or `1.43x`, `1.73x`, and `1.70x` over
  Attempt 116. Its charged index lookups fall deterministically from `582,366` to `476,367` in
  every run because graph zero is no longer searched twice and missing suffix tuples reject on the
  most selective available property.
- The deterministic 64-source DISTINCT test proves graph zero supplies the initial rows, only
  graphs 1 through 63 receive the selected-tuple provenance lookup, and the final row still merges
  provenance from graphs zero and 63. The mapped selected-tuple test and Cypher detekt pass.
- Candidate process CPU is `1.716/1.618/1.748 s` versus `1.818/1.822/1.837 s`; peak used heap is
  `3.71..3.81 GB` and peak RSS is `4.24..4.33 GB`, with no material resource increase. Aggregate
  P95 versus Attempt 116 is noisy (`1.21x`, `0.97x`, `0.77x`) because unchanged millisecond-scale
  distribution rows exchange the 33rd order-statistic position. The target DISTINCT improvement is
  stable, but it does not by itself prove the hosted 5x gate.

**Conclusion:** keep as a measured incremental improvement. The previous hosted result missed the
wrapped DISTINCT 5x floor narrowly at `4.97x` and `4.74x`; this attempt removes a repeatable
`1.43x..1.73x` portion of that target row without weakening correctness or resource bounds. Exact
hosted three-pair evidence remains the hard gate, and this PR must not be merged or tagged without
an explicit user instruction.

### 2026-09-04 - Attempt 118: Remove the independent trigram prefilter format

**Hypothesis:** `graph.callsite-trigram-prefilter` is an unjustified persisted-format expansion.
The 64 fixture graphs already contain only `0.34 GiB` of verified `graph.callsite-string-index`
data, while the additional prefilter files total about `256 KiB`; with the required 8 GiB heap,
loading the existing indexes is affordable. Restore and validate that existing format, reuse its
exact trigram candidates and property membership for segmented raw scans, and let a bounded dense
leading projection return before suffix planning. This should retain the 5x milestone without a
second CallSite lookup protocol.

**Evidence:**

- Base is exact head `7161b54`; candidate is this attempt. The paired fresh-JVM screen uses the
  same 64 persisted graphs generated from the four pinned fixture JARs, the same independent
  correctness oracle, an 8 GiB heap, and four active CPUs. The candidate code does not open or
  require the pre-existing `graph.callsite-trigram-prefilter` files.
- All 34 base and candidate observations succeed with zero failures and zero timeouts. Candidate
  rows, digests, and graph provenance match the oracle. The complete 84-case
  `CrossGraphCypherExecutorTest`, 164-case mapped `GraphStoreTest`, core/Cypher/WebGraph detekt,
  JMH compilation, 87 benchmark-gate contract tests, and shell syntax checks pass in an isolated
  clean checkout.
- Aggregate P50/P95 fall from `236.630/482.382 ms` on main to `2.170/62.536 ms`, or
  `109.05x/7.71x`. `global-wide-wrapped-case-insensitive-distinct-dense` falls from `482.382` to
  `62.536 ms` (`7.71x`) while preserving its 200 rows and digest. The runtime reports the required
  additive `2 graph + 2 segment` split.
- Candidate process CPU falls from `10.938` to `3.130 s`; peak used heap falls from `5.003` to
  `4.610 GB`; peak RSS falls from `6.180` to `5.016 GB`; normalized allocation falls from `5.230`
  to `4.901 GB/op`. Retained CallSite indexes account for `369 MB`, well inside the heap-derived
  shared budget.
- The dense ordinary wrapped row keeps identical charged work (`665`) but measures
  `0.876 -> 1.741 ms` in this single local pair. The bounded leading return removes suffix planning,
  but exact hosted reverse-order evidence remains necessary to decide whether this micro-row is a
  real regression or runner noise.

**Conclusion:** keep for exact hosted validation. This attempt deletes the `GRTP` reader, writer,
file constant, fixture provenance fields, corruption tests, driver requirements, and storage-format
documentation rather than renaming the sidecar. The 5x target and resource bounds pass locally;
the remaining aligned micro-row and all review/CI gates remain hard blockers. Do not merge or tag
without an explicit user instruction.

### 2026-09-04 - Attempt 119: Preflight cold misses before restoring the retained index

**Hypothesis:** after removing the independent trigram-prefilter format, the first long zero-hit
query restores all 64 complete `graph.callsite-string-index` files before it can prove the term is
absent. Run the existing exact string-table preflight first, and reject selected DISTINCT tuples
whose required strings do not occur in a graph's dictionary. Both checks are deterministic and
conservative: an uncertain graph still takes the authoritative retained-index path.

**Evidence:**

- Base is exact head `dde3b35`; candidate is this experiment, subsequently reverted. Three paired
  fresh-JVM screens use the same 64 persisted graphs generated from the four pinned fixture JARs,
  the same independent correctness oracle, an 8 GiB heap, and `ActiveProcessorCount=4`. Synthetic
  coverage checks only path selection and positive/negative correctness.
- All six real-fixture runs complete 34/34 observations with exact oracle parity. The first
  `global-wide-four-properties/zero` row improves from `797.609/799.022/873.722 ms` and
  `57,642,320` work units to `312.257/314.457/310.386 ms` and `2,793,940` units. Wrapped
  non-DISTINCT dense also falls from `1.714..2.030 ms` to `1.215..1.350 ms` with the same 665 units.
- The improvement moves too much exact preflight work into the rest of the matrix. Aggregate
  P50 regresses from `2.347/2.429/2.529 ms` to `231.151/231.732/230.402 ms`; P95 regresses from
  `79.801/64.932/91.639 ms` to `312.257/391.661/333.828 ms`. Total charged work rises from
  `59,541,143` to `88,305,370`, and process CPU rises from `3.11..3.80 s` to `11.01..11.74 s`.
  Peak heap and RSS do not compensate for that regression.

**Conclusion:** reject and revert. Exact per-graph string-table preflight fixes the isolated cold
zero row, but repeating it for each long shape destroys the 5x workload objective. No production
or test code from this experiment remains. The next attempt must obtain a reusable, integrity-
checked view from the existing `graph.callsite-string-index` rather than add another persisted
format or rescan every graph dictionary per query.

### 2026-09-04 - Attempt 120: Map the existing persisted string index for cold broad scans

**Hypothesis:** a cold broad query needs the property directories, posting ends, and trigram
postings already stored in `graph.callsite-string-index`, but does not need to materialize the
complete CSR index into heap arrays. Validate that existing file once, retain a read-only mmap view,
and feed its exact string ids into the additive graph/segment raw scan. This should remove repeated
dictionary preflight and full-index restoration without adding another persisted format. Already-
retained and single-graph scoped queries keep their existing index path.

**Evidence:**

- Base PR revision is `155fd20`; the production baseline is exact `origin/main` revision
  `78ce46b`. Candidate is this Attempt 120 commit. All performance measurements use the same 64
  persisted graphs generated from the four pinned Android, Tika, Hive, and Kotlin compiler fixture
  JARs at `/tmp/pr113-attempt119-real64/`, an 8 GiB heap, and four active CPUs. The 34 workload
  identities match main case-for-case. Synthetic graphs are used only for deterministic path,
  corruption-fallback, cache-lifecycle, and result-correctness tests.
- The view reads only `graph.callsite-string-index`: it checks the existing magic, version, content
  identity, dimensions, CRC32, sorted property directories, posting ends, and trigram postings.
  Missing, stale, truncated, malformed, or checksum-invalid files fall back to the authoritative raw
  scan. No new file, magic, version, writer, or persistence setting is introduced. A forced cache
  release drops the view; an ordinary between-query release retains it.
- Three final candidate fresh-JVM global-wide screens complete 34/34 cases with exact oracle row,
  order, digest, response-size, and provenance parity. Main P50/P95 is
  `239.001/412.240`, `236.533/423.533`, and `255.016/428.226 ms`; candidate is
  `1.730/39.968`, `2.036/43.341`, and `1.882/98.395 ms`. P50 improves
  `138.13x/116.17x/135.49x`; P95 improves `10.31x/9.77x/4.35x`. The third DISTINCT-tail outlier
  means the required 5x floor is not yet stable and is recorded rather than discarded.
- Candidate process CPU is `1.720/1.771/2.060 s` versus main's
  `10.378/10.518/11.136 s`. Candidate peak used heap is `4.397/4.711/4.713 GB` versus
  `4.984/5.003/5.005 GB`; peak RSS is `5.313/5.535/5.698 GB` versus
  `6.143/6.164/6.130 GB`. Full retained CallSite-index bytes remain zero. Every run reports four
  available CPUs, the required `2 graph + 2 segment` plan, and observed peaks of two graph and two
  segment workers.
- The cold graph-routing replay exposed and then closed a K=64 lifecycle regression. Applying the
  same mapped view only to cold balanced selected sets reduced the first explicit 64-graph request
  from repeat candidate failures of `805/940 ms` to `256 ms` against main's `449 ms` and preserved
  the expected `1,979` retained-index lookups. The current cold, warm, and startup-prepared
  comparators all pass, and all 1,137 cases match the independent main-derived oracle. Single
  `graphId` queries remain below the balanced threshold and retain their original path.
- The complete core tests, `CrossGraphCypherExecutorTest`, mapped `GraphStoreTest`, core/Cypher/
  WebGraph detekt, and JMH compilation pass in the isolated candidate checkout. Tests assert the
  mapped consumer selection for unscoped and balanced selected sets, retained selection for a
  single graph, positive/zero exact matches, selected-tuple membership, non-force reuse, force
  release, corrupt-index rejection, and raw correctness fallback.

**Conclusion:** keep as the measured cold-broad-scan foundation, but do not claim the 5x PR target
yet. It removes full heap-index restoration, materially lowers CPU and memory, and makes cold K=64
selected queries pass without a second persisted format. The remaining work is isolated: replace
the scheduler overhead for exact dense ordered prefixes and remove the wrapped DISTINCT tail
variance in a separate attempt. This commit is not authorization to merge or tag.

### 2026-09-04 - Attempt 121: Serialize mapped exact dense prefixes

**Hypothesis:** once the mapped posting counts prove that exact matching string ids contain at
least `LIMIT` occurrences, scanning those ids in source order on the caller thread should avoid the
two segment-task submissions responsible for the repeated `localized-early` aligned regression.
Restrict the route to predicates sharing the same transform, match mode, and expected string so a
multi-term upper-bound sum cannot serialize an unrelated sparse query.

**Evidence:**

- Base is exact Attempt 120 head `969bb1c`; candidate variants were built in the same isolated
  checkout and replayed against the same 64 pinned-fixture-JAR graphs, independent correctness
  oracle, 8 GiB heap, and four active CPUs. Every screen completed 34/34 global-wide cases with
  exact row, order, digest, response-size, and provenance parity. Focused mapped-valid and corrupt-
  fallback tests plus WebGraph detekt passed before the production changes were reverted.
- The unrestricted first variant exposed an unsafe performance signal rather than a correctness
  error: summed occurrences across different predicates can exceed `LIMIT` even when the result
  remains sparse. `global-wide-four-properties-targeted` rose to `108.360 ms`. Requiring one shared
  matcher restored that row to `42.356 ms`.
- The protected final variant still did not reduce the actual blocker. `localized-early` measured
  `13.187 ms` with `44,900` charged units, versus Attempt 120's `4.926-12.196 ms` and main's stable
  `4.091-4.142 ms`. Direct comparison for up to eight exact string ids did not change that result.
  `localized-middle` and `localized-late` measured `2.938/7.642 ms`, but those isolated wins do not
  compensate for the repeated early-row regression.
- The final screen's aggregate P50/P95 was `1.785/53.122 ms`, process CPU was `1.610 s`, and the
  required aggregate `2 graph + 2 segment` peaks remained observable on other query shapes. One
  screen cannot establish a stable tail improvement, and the intended aligned row remained red.

**Conclusion:** reject and revert. Removing segment scheduling after exact string matching does
not remove the cost that distinguishes candidate from main; the redundant work is resolving exact
strings and then scanning raw nodes again. No production or test change from this experiment is
retained. The next attempt should consume the already-persisted property node postings directly,
with integrity validation and raw fallback, instead of tuning the second scan.

### 2026-09-04 - Attempt 122: Merge existing mapped property postings directly

**Hypothesis:** `graph.callsite-string-index` already stores each property's matching node ids in
encounter order. After the mmap view resolves exact string ids, merge those existing posting ranges
and de-duplicate node ids instead of scanning every raw CallSite again. Restrict the direct route to
disjunctions sharing one transform/mode/expected matcher. Keep selected-value DISTINCT provenance
on the prior segmented raw path because most suffix graphs reject those tuples before scanning.

**Evidence:**

- Base PR revision is exact Attempt 121 head `ede2432`; production comparison is exact
  `origin/main` revision `78ce46b`. Candidate is this Attempt 122 commit. Three fresh-JVM pairs ran
  in the actual order candidate/base, base/candidate, candidate/base on the same 64 persisted graphs
  generated from the four pinned fixture JARs, the same independent oracle, an 8 GiB heap, and four
  active CPUs. No synthetic performance evidence was used.
- The unmodified `compare-global-wide-pressure --minimum-speedup 5` gate passes with no errors.
  Main P50/P95 is `233.827/389.235`, `242.054/399.137`, and `239.299/411.352 ms`; candidate is
  `1.916/62.536`, `2.080/44.484`, and `1.926/52.608 ms`. P50 improves
  `122.06x/116.38x/124.26x`; P95 improves `6.22x/8.97x/7.82x`, so every independent fork clears
  the 5x milestone.
- All 204 paired observations and all three candidate runs match the 34-case oracle exactly for
  outcome, row count, order, digest, response size, and graph provenance. The formerly aligned
  `localized-early` row improves from main's `4.031/3.968/5.678 ms` and `44,824` work units to
  `2.690/2.646/2.580 ms` and `330` units. Wrapped case-insensitive DISTINCT dense remains on its
  selected-value segmented path and measures `62.536/44.484/52.608 ms`, with the unchanged
  deterministic `283,544` units.
- Candidate process CPU is `1.891/1.772/1.787 s` versus main's
  `10.246/10.950/10.606 s`. Candidate peak used heap is `5.012/5.034/5.003 GB` versus
  `4.992/4.998/4.996 GB`, within the comparator's resource tolerance; peak RSS falls to
  `5.715/5.766/5.726 GB` from `6.164/6.169/6.166 GB`. Total charged work falls from
  `109,198,717` to `58,067,854`. Full retained CallSite-index bytes remain zero.
- Every candidate fork reads `availableProcessors=4`, plans `2 graph + 2 segment`, and observes
  peaks of two graph and two segment workers. Direct posting lookup avoids segmentation only when
  it is cheaper; the remaining DISTINCT/raw paths still exercise the additive segment budget.
- The current 1,137-case cold, warm, and startup-prepared graph-routing replays all pass their
  comparators and match the independent main-derived oracle. Candidate P50/P95 is
  `0.074/13.783`, `0.031/0.226`, and `0.077/1.072 ms`; retained-index lookup counts remain exactly
  `1,979/2,043/2,043`, preserving the required cold and prepared lifecycle.
- The view now validates property posting node-id bounds and strict encounter order in addition to
  the existing header, identity, dimensions, checksum, directories, ends, and trigram checks.
  Missing or invalid files still fall back to authoritative raw scanning. The deterministic test
  proves source-order merge and de-duplication across two properties, no heap-retained index, and
  corrupt-file raw fallback. The complete mapped `GraphStoreTest` and WebGraph detekt pass.

**Conclusion:** keep. Directly consuming the existing mmap postings removes the repeated exact-id
plus raw-node scan, closes the localized aligned regression, and clears the 5x P95 milestone in all
three genuinely paired forks while preserving correctness, NCPU planning, resource bounds, and
graph-routing lifecycle. This changes no persisted filename, magic, version, or writer. Exact-head
full CI and hosted review gates remain required, and this commit is not authorization to merge or
tag.

### 2026-09-04 - Attempt 123: Keep mapped posting validation sequential

**Hypothesis:** Attempt 122 validates encounter order by resolving `nodeOffsets.offset(nodeId)` for
every posting while each view is opened. The checksum pass over the sidecar is sequential, but those
extra random reads fault unrelated node-offset pages for every graph before a query consumes any
posting. Keep the eager integrity pass sequential: validate the existing header, content identity,
dimensions, checksum, directories, posting ends, node-id bounds, and trigram ordering. The existing
writer is still the authority for encounter-ordered posting ranges, and matching cursors continue to
resolve `nodeOrder` lazily while query-selected posting ranges are merged.

**Evidence:**

- Exact Attempt 122 head `985c4ae` passes 31 hosted benchmark components, including wrapped-query
  latency/resources, capacity, large corpora, Explorer, and the 4/17 graph plus three of four 36
  graph Method shards. Its 36-graph aggregate shard isolates a repeated RSS-after regression in the
  `order` case: `3.943 -> 4.631 GB` (`+17.4%`) and reverse confirmation
  `3.909 -> 4.709 GB` (`+20.5%`). Wall, CPU, RSS delta, response bytes, request success, and the
  sibling `count` case pass. This is consistent with eagerly faulting node-offset pages rather than
  retained heap or query-result growth.
- After removing only that eager random node-order walk, three fresh-JVM candidate confirmations use
  the same four active CPUs, 8 GiB heap, 64 persisted graphs generated from the four pinned fixture
  JARs, and Attempt 122's independent main-derived oracle. All 102 observations (34 per fork) pass
  exact correctness verification with no failed query. No synthetic performance evidence is used.
- Candidate P50 is `2.014/1.715/1.986 ms` and P95 is `52.506/59.707/49.540 ms`. Against the frozen
  paired main samples from Attempt 122 this is `116.12x/141.12x/120.50x` at P50 and
  `7.41x/6.68x/8.30x` at P95, preserving the 5x milestone in every fork. Charged work remains
  exactly `58,067,854` in all three forks and failures remain zero.
- Peak RSS falls to `5.257/5.259/5.003 GB` from Attempt 122's
  `5.715/5.766/5.726 GB`; process CPU is `1.582/1.644/1.527 s`. Peak used heap is
  `4.705/4.703/4.470 GB`, so the change removes page residency rather than trading it for heap.
- Focused tests now cover multi-element mapped posting cursors, cross-property source-order merge
  and de-duplication, and both the `exactMatchesCanFillLimit` true and false segmented fallback.
  Cypher also executes the split consumer's work-forwarding branch. Full measured coverage returns
  to WebGraph `98.1081%` and Cypher `98.0324%` without changing thresholds or exclusions; the
  explicit qualified-work-tracker contract keeps Cypher clear of the rounding boundary.

**Conclusion:** keep. Eager validation remains integrity-checked but sequential and no longer walks
the entire node-offset mapping merely to open a CallSite sidecar. The 64-real correctness/latency
milestone remains green while RSS falls materially. Exact-head hosted 36-graph RSS, global-wide,
graph-routing, unit coverage, and aggregate gates must still pass before either open review thread is
resolved. This changes no persisted filename, magic, version, or writer, and it is not authorization
to merge or tag.

### 2026-09-04 - Attempt 124: Validate only selected posting ranges before merge

**Hypothesis:** Attempt 123 correctly avoids faulting every node-offset page when a mapped view is
opened, but its merge still assumes that each checksum-authenticated posting range is in encounter
order. A checksum proves byte integrity, not that semantic invariant: a reordered range with a
recomputed checksum can make an early `LIMIT` return the wrong node. Validate encounter order for
only the posting ranges selected by the current query, and do so before yielding any result. If a
selected range is invalid, decline the mapped merge so the authoritative raw path supplies the
result. This retains Attempt 123's avoidance of an eager four-property node-offset walk.

**Evidence:**

- A focused regression persists three matching CallSites, changes the caller-class posting range
  from `[0, 1, 2]` to `[1, 0, 2]`, recomputes the sidecar CRC, and issues `LIMIT 1`. The mapped view
  initializes, rejects the selected out-of-order range before emitting a node, and the fallback
  returns the correct encounter-order result `[0]`; the test and WebGraph detekt pass.
- Three fresh-JVM candidate confirmations under `/tmp/pr113-attempt124-paired` use the same four
  active CPUs, 8 GiB heap, 64 persisted graphs generated from the four pinned fixture JARs, and the
  same independent main-derived oracle as Attempts 122-123. All 102 observations pass exact
  correctness verification for outcome, row count, order, digest, response size, and graph
  provenance. No synthetic performance evidence is used.
- Candidate P50 is `1.921/1.925/1.933 ms` and P95 is `47.861/41.936/46.428 ms`. Against the frozen
  paired main samples this is `121.72x/125.72x/123.82x` at P50 and
  `8.13x/9.52x/8.86x` at P95. The unmodified `compare-global-wide-pressure --minimum-speedup 5`
  gate passes with no errors; worst wrapped-query P95 speedup is `8.28x`.
- Candidate process CPU is `1.521/1.488/1.496 s`; peak used heap is
  `4.325/4.686/4.241 GB`, and peak RSS is `4.882/5.237/4.781 GB`. Charged work is
  `58,071,626` in every fork, only `3,772` (`0.0065%`) above Attempt 123 because the selected-range
  semantic check is budgeted. Each fork reads four available processors, plans `2 graph + 2
  segment`, and observes both peaks at two.

**Conclusion:** keep. Query-selected semantic validation closes the checksum-valid corruption hole
without restoring the eager all-property page faults or changing the persisted format, but reject
this uncached form as a terminal implementation. Exact-head hosted run `33814266997` kept the
aggregate global-wide gate above 5x, yet repeated a `global-wide-wrapped-case-insensitive/dense`
regression (`2.024 -> 5.613 ms`) in all three aligned pairs and failed cold K=64 graph routing
(`0.696/5.522 -> 1.247/3.724 ms` at P50/P95). The same run also reported red real-a zero-hit
latency and Method36 regex RSS checks, so the exact head did not satisfy the all-gates hard gate.
The next attempt must retain this semantic check while removing repeated range validation and its
duplicate node-order reads. This commit is not authorization to merge or tag.

### 2026-09-04 - Attempt 125: Validate all posting ranges once at mapped-view load

**Hypothesis:** replace per-query posting-order validation with a single load-time pass. Build a
temporary CallSite node-id-to-encounter-rank table from the existing mapped node-type index, then
validate every posting range while the existing CRC/property-posting pass is already sequential.
Discard the rank table after load, retain no new index, and leave the persisted format unchanged.

**Evidence:**

- The checksum-valid reordered-range regression passes: the mapped view rejects the malformed
  sidecar and the authoritative raw scan returns encounter-order result `[0]`. Focused correctness,
  WebGraph detekt, and JMH compilation pass. No synthetic performance evidence is used.
- Three fresh-JVM real-64 runs on the four pinned fixture JAR families preserve all 102 oracle
  observations. P50 is `1.892/1.842/1.873 ms` (`123.58x/131.41x/127.74x` versus frozen main), but
  P95 is `80.501/63.316/52.273 ms`; the first pair reaches only `4.84x`, below the 5x hard gate.
- Eager encounter-rank construction raises deterministic charged work from `58,071,626` to
  `63,114,789` units. Replacing the temporary hash table with a compact zero-filled `IntArray`
  removes hashing overhead but cannot remove the full CallSite walk.
- A same-machine cold graph-routing replay against Attempt 124 confirms the regression: first K=64
  request rises from `295.235` to `373.519 ms`, and K=64 P95 rises from `1.377` to `2.766 ms`.
  The full-load pass touches millions of CallSites that the selected query never needs.

**Conclusion:** reject and revert. Validating every range once is semantically sound but violates
the pressure objective by doing broad work before the selected query identifies any relevant
posting. No production or test code from this experiment remains. The next attempt should cache
validation per selected `(property, posting row)` and reuse the first validation's node orders,
without adding a file, magic, version, writer, or persisted field.

### 2026-09-04 - Attempt 126: Cache selected posting-range validation

**Hypothesis:** retain Attempt 124's query-selected semantic validation, but validate each immutable
`(property, posting row)` at most once per mapped view. On the first lookup, resolve and verify the
entire range before yielding any node, retain only its valid/invalid result, and give that query's
cursor the already-resolved node orders so it does not read them twice. Later lookups reuse the
validation result and resolve only nodes actually consumed by the merge. This avoids Attempt 125's
full CallSite walk and keeps checksum-valid semantic corruption on the authoritative raw fallback.

**Evidence:**

- The checksum-valid `[0, 1, 2] -> [1, 0, 2]` regression still returns `[0]`: the first selected
  range is fully checked before `LIMIT 1`, its invalid result is retained by the view, and the raw
  path supplies encounter order. Focused correctness, WebGraph detekt, and JMH compilation pass.
- Three fresh-JVM real-64 runs use the same four pinned fixture JAR families, 8 GiB heap, four
  active CPUs, and frozen main-derived 34-case oracle as Attempts 122-125. All 102 observations
  match outcome, row order, digest, response size, and provenance. No synthetic performance
  evidence is used.
- Candidate P50 is `1.819/1.727/1.673 ms` versus main's
  `233.827/242.054/239.299 ms`, or `128.56x/140.12x/143.04x`. Candidate P95 is
  `50.849/68.260/57.374 ms`, or `7.65x/5.85x/7.17x`; every independent fork clears 5x and the
  worst wrapped case-insensitive P95 speedup is `7.61x`.
- Process CPU is `1.421/1.572/1.447 s`; peak used heap is `4.38/4.38/4.38 GiB`, and peak RSS is
  `4.89/4.87/4.89 GiB`. Charged work returns to exactly `58,071,626` in every fork instead of
  Attempt 125's `63,114,789`; no unselected CallSite is walked for encounter-rank construction.
- A same-machine cold graph-routing replay against exact Attempt 124 completes all 1,137 oracle
  cases. Query-level graphId P50/P95 is `1.06x/1.01x`, request-selected P50/P95 is
  `0.99x/0.99x`, K=64 aggregate is `0.341/1.377 -> 0.322/1.494 ms`, and the first cold K=64
  request improves from `295.235` to `260.761 ms`. Peak RSS falls from `6.60` to `6.17 GiB`.

**Conclusion:** keep as the correctness-preserving mapped-posting foundation, but supersede its
terminal query plan with Attempt 127. Exact-head hosted run `33818543224` passed unit, corpus,
resource, Method, Explorer, and the first complete global-wide gate; its rerun also restored the
fixture64 cache without regeneration. The rerun nevertheless reproduced graph-routing overhead:
K=8 cold P50/P95 was `0.442/1.475 -> 0.722/1.586 ms`, while K=64 was
`0.799/2.503 -> 0.960/3.605 ms`. It also repeated an aligned wrapped-dense regression
(`2.094 -> 3.684 ms` in the first pair) despite aggregate P50/P95 speedups of at least
`75.78x/7.68x`. Correctness, graph access, lookup counts, and work were unchanged, isolating the
remaining problem to fixed query-layer planning, node materialization, and projection overhead.
This commit is not authorization to merge or tag.

### 2026-09-04 - Attempt 127: Project retained selected graph sets directly

**Hypothesis:** once every graph in an explicitly selected set already retains the existing
`graph.callsite-string-index`, the query layer should not probe the generic graph/segment plan and
then materialize matching `CallSiteNode` objects only to read the same four stored strings. Extend
the existing bounded direct projection from one source to an ordered retained source set. Check
that every source is retained before consuming any projection, append source-local rows in catalog
order, and stop as soon as the global `LIMIT` is full. If any source is cold, lacks the capability,
contains an applicable `AnnotationNode`, or has a residual node predicate, decline the fast path
before emitting a row and preserve the authoritative existing execution path.

**Evidence:**

- Base is exact Attempt 126/cache-boundary head `5204cf8`. Candidate uses the same 64 persisted
  graphs generated from the four pinned fixture JARs, the same independent main-derived oracle,
  an 8 GiB heap, and `ActiveProcessorCount=4`. No synthetic timing evidence is used; synthetic
  coverage verifies only source order, remaining-limit propagation, per-row graph provenance, and
  early global-limit termination.
- Three fresh-JVM cold graph-routing runs complete all `1,137/1,137` rows with exact oracle parity.
  K=8 P50 is `0.033/0.036/0.034 ms` and P95 is `0.440/0.473/0.456 ms`; K=64 P50 is
  `0.099/0.096/0.103 ms` and P95 is `1.281/1.277/1.248 ms`. Against the same-machine Attempt 126
  control (`0.452/1.724 ms` at K=8 and `1.387/6.053 ms` at K=64), median latency improves about
  `13.5x/13.9x`, while tail latency improves about `3.8x/4.7x`.
- Every run retains exactly 1,979 indexed lookups over all 64 graphs, distributed 29..38 per graph.
  Dense K=64 literal, parameter, and request-selected forms still access only the leading graph,
  perform one lookup and 200 work units, and return identical ordered digests. The intentionally
  cold first K=64 zero query still takes the original scan/build fallback and initializes all 64
  graphs; it is not converted into an eager 64-sidecar restore.
- The unchanged global-wide engine was replayed in three fresh JVMs against the 34-case real64
  oracle. All `102/102` observations are exact, P50 is `2.033/1.953/1.959 ms`, P95 is
  `56.740/53.220/52.660 ms`, and charged work remains exactly `58,071,626`. Relative to the frozen
  same-machine main samples, the conservative P50/P95 speedups remain above `115x/6.8x`.
- A separate attempt to send the wrapped dense row through object-materializing serial raw scan
  regressed it from `1.504` to `3.414 ms` and was reverted. Rewriting the fused projection loop did
  not provide a stable material gain and was also reverted. The retained change is therefore only
  the measured multi-source projection path; it adds no file, format, magic, version, writer, or
  startup work.

**Conclusion:** keep for exact hosted validation. This removes the repeated fixed scheduler and
node-projection tax from graph-id sets and `/api/cypher/graphs` selected sets without changing the
cold fallback, source order, correctness oracle, work accounting, or persisted representation.
Exact-head full CI, hosted global-wide and graph-routing gates, and all review threads remain hard
gates. This commit is not authorization to merge or tag.

### 2026-09-04 - Attempt 128: Bound selected posting-range validation state

**Hypothesis:** Attempt 126's per-selected-range validation avoids the rejected eager full-graph
walk, but its boxed concurrent map can retain one entry for every queried `(property, posting row)`
for the lifetime of each mapped graph. Replace that unbounded state with a fixed 1,024-slot
direct-mapped primitive cache. Charge a conservative 16 KiB reservation to the existing shared
CallSite index budget before allocating it, allow collisions to repeat deterministic validation,
and retain no validation state when the reservation is unavailable. Closing or clearing the mapped
view must release the reservation.

**Evidence:**

- A deterministic correctness/memory test denies the complete retained index while leaving exactly
  16 KiB for the mapped-view cache, queries 1,088 disjoint posting rows twice, and verifies every
  ordered result. Retained entries stay within 1,024, retained bytes stay exactly 16 KiB, and
  clearing the view returns those bytes to the shared budget. A second phase occupies the entire
  budget, verifies 64 further disjoint rows through the uncached mapped fallback, observes zero
  retained validation entries/bytes, and confirms graph close does not leak budget.
- The cache uses one `LongArray` and one `ByteArray`; it has no boxed `Long -> Boolean` entries and
  cannot grow. A collision replaces one slot and affects only future validation work, never query
  results. The first validation still resolves the complete selected range before yielding any
  node, so checksum-valid posting-order corruption continues to take the authoritative raw fallback.
- One fresh-JVM real fixture64 global-wide replay, using the four pinned fixture JAR families, the
  existing independent 34-case oracle, an 8 GiB heap, and four active CPUs, completes `34/34`
  observations with P50 `1.720 ms`, P95 `46.751 ms`, zero timeout, peak used heap `4.04 GiB`, and
  peak RSS `4.54 GiB`. This is a no-regression check only; synthetic data is not performance
  evidence.
- One corresponding real fixture64 cold graph-routing replay completes `1,137/1,137` cases. K=8
  P50/P95 is `0.040/0.465 ms` and K=64 is `0.098/1.211 ms`, versus Attempt 127's same-machine
  representative `0.035/0.473 ms` and `0.096/1.277 ms`. The fixed reservation therefore shows no
  material routing-latency regression while making heap retention independent of query diversity.

**Conclusion:** keep for exact-head hosted validation. This is a lifecycle and accounting boundary
around the already-measured Attempt 126 optimization; it adds no persisted file, format, magic,
version, writer, or startup-wide validation pass. Full CI, both hosted real64 pressure gates, and
all review threads remain hard gates. This commit is not authorization to merge or tag.

### 2026-09-04 - Attempt 129: Keep unscoped dense projection on the raw-leading path

**Hypothesis:** Attempt 127's retained multi-source projection is intended for explicitly selected
graph sets, but the same entry point also admitted an unscoped global query whenever all 64 sources
already retained their string indexes. That changes the established low-work dense `LIMIT 200`
plan from one raw-leading projection to a 64-source retained preflight and adds fixed query-layer
overhead without reducing its 665 charged work units. Keep direct retained projection for a single
source and for explicitly scoped graphId, graphId-set, and request-selected graph sets, but decline
it for an unscoped multi-source query so the measured raw-leading path remains authoritative.

**Evidence:**

- Exact-head hosted run `33825074274`, attempt 2, preserved result rows, digest, graph access, and
  exactly 665 work units for `global-wide-wrapped-case-insensitive/dense`, but candidate latency was
  `3.247/4.161/4.818 ms` versus aligned base `2.165/2.704/2.381 ms`. This isolated the failed gate
  to fixed planning/projection overhead rather than scan work or correctness.
- A focused regression gives every source both retained-index and projection capabilities. The
  unscoped 40-source dense query must nevertheless use the existing `PreferredRawGraphWorkBatchConsumer`,
  project only source zero, return the same ordered 200 rows and graph provenance, and never enter
  node materialization. The complete `CrossGraphCypherExecutorTest`, Cypher detekt, and JMH build pass.
- One fresh-JVM real fixture64 global-wide replay uses the four pinned fixture JAR families, the
  independent 34-case correctness oracle, an 8 GiB heap, and four active CPUs. All `34/34`
  observations pass with zero timeout, P50 `1.746 ms`, and P95 `128.923 ms`. The isolated dense row
  falls to `1.420 ms` while retaining 200 rows, the exact digest, source-zero provenance, and 665
  work units. The high one-fork aggregate P95 comes from the distinct/global and zero-hit cases and
  is not used as proof that the hosted 5x gate has passed; exact-head paired hosted evidence remains
  required.
- A separate real fixture64 cold graph-routing replay verifies all `1,137/1,137` observations
  against the independent oracle. It retains exactly 1,979 indexed lookups over all 64 graphs,
  distributed 29..38 per graph. Graph-id-set K=8 P50/P95 is `0.048/0.549 ms` and K=64 is
  `0.098/1.321 ms`, confirming that explicit graphId sets and `/api/cypher/graphs` request-selected
  sets retain the scoped path and exact source pruning.

**Conclusion:** keep for exact-head hosted validation. This restores the previously measured
unscoped dense raw-leading plan without weakening Attempt 127's explicitly selected multi-source
projection. It changes no persisted file or format and uses no synthetic performance evidence.
Full CI, both hosted real64 pressure gates, and all review threads remain hard gates. This commit
is not authorization to merge or tag.

### 2026-09-04 - Attempt 130: Keep the bounded raw projection loop primitive

**Hypothesis:** the remaining exact-head aligned regression is fixed overhead in the bounded
raw-leading projection rather than missing graph/segment concurrency: the failing dense query
visits only the first real graph and exactly 665 CallSites. Remove boxed iterator dispatch and the
per-node `indices.any` lambda from that loop, while preserving its bounded matcher, source order,
LIMIT, cancellation polling, work accounting, and projection semantics.

**Evidence:**

- Exact hosted Attempt 129 run `33827837795` preserved all results and exceeded the aggregate 5x
  milestone, with P95 speedups of `8.68x`, `7.72x`, and `6.28x`. It nevertheless repeated the
  `global-wide-wrapped-case-insensitive/dense` aligned regression in all three pairs: base
  `2.496/2.655/2.083 ms`, candidate `4.017/5.722/3.539 ms`. The exact-head gate therefore remains
  failed even though the query still returned the same 200 rows, digest, first-graph provenance,
  and 665 work units.
- Three fresh-JVM candidate replays use the existing 64 distinct persisted graphs generated from
  the four pinned fixture JAR families, the independent 34-case oracle, an 8 GiB heap, and four
  active CPUs. All `102/102` observations pass with zero timeout and unchanged aggregate work of
  `58,071,626` units per replay. The benchmark reports the intended additive split on every run:
  two graph workers plus two segment workers.
- The isolated wrapped dense row is `1.349`, `1.340`, and `1.383 ms`, versus the unchanged-loop
  screens at roughly `1.37..1.52 ms`; row count, ordered digest, accessed graph, and 665 work units
  are identical. Aggregate P50 is `1.707/1.869/1.701 ms`; aggregate P95 is
  `58.801/56.637/39.907 ms`. These unpaired local numbers are diagnostic only; the exact pushed
  base/candidate hosted gate remains authoritative for the 5x claim and the aligned regression.
- Isolated alternatives were rejected before this retained change. Restoring the fixed 64K
  matcher code shape produced `1.435/1.516/1.370 ms`; special-casing a shared predicate matcher
  produced `1.490/1.369/1.495 ms`. Routing only transformed predicates through the raw-node
  coroutine regressed to `3.475/3.352/3.270 ms`. Routing every dense shape through that node path
  appeared fast only after earlier cases had warmed the coroutine and also regressed ordinary
  dense shapes by up to roughly 4x, so neither ordering-dependent result is retained.

**Conclusion:** keep as a small, semantics-preserving hot-loop improvement and validate on the
exact pushed head. It does not change the NCPU allocation, persisted graph format, index policy,
or query routing. Full CI, both hosted real64 pressure gates, and all review threads remain hard
gates. This commit is not authorization to merge or tag.

### 2026-09-04 - Attempt 131: Project the initialized mapped leading graph without nodes

**Hypothesis:** the remaining `global-wide-distribution-localized-early/dense` regression is not
missing graph or segment concurrency: its ordered `LIMIT 200` is satisfied by source zero after
only 1,053 charged work units. That source already initialized the existing
`graph.callsite-string-index` mapped view during the earlier query sequence, but the query path
still materializes complete `CallSiteNode` objects and then projects four strings. Reuse only that
already-initialized mapped view to produce projection rows directly. Never initialize the sidecar
from this projection probe, keep short dense terms on Attempt 130's raw-leading path, and decline
to the authoritative retained-index/raw/node fallback on an unsupported matcher or invalid view.

**Evidence:**

- Base is exact Attempt 130 correctness-regression head
  `f07ce836f3cc61fc0051fa2d08986f0cad0bc9fa`; candidate is this experiment commit. Three paired
  fresh-JVM runs alternate base/candidate order on the same 64 distinct persisted graphs generated
  from the four pinned Android, Tika, Hive, and Kotlin fixture JARs. Each process uses an 8 GiB heap
  and `ActiveProcessorCount=4`. Synthetic graphs are used only by focused correctness/path tests,
  never as performance evidence.
- All `204/204` observations across the six processes match the independent oracle for outcome,
  ordered rows, digest, response bytes, and graph provenance, with zero timeout or failure. Focused
  WebGraph and Cypher tests plus both detekt gates pass. The mapped projection test proves a cold
  projection probe cannot initialize the view, an initialized view preserves its existing posting
  encounter order, and a checksum-valid invalid posting takes the correct retained-index fallback.
  Query-layer regressions prove both a full leading result and a partial leading result preserve
  source order, provenance, global `LIMIT`, and continuation from source one without rescanning
  source zero or materializing nodes. A 40-source regression also proves that `RETURN
  x.caller_class` retains 200 `null` values when the matched variable is `n`; removing the new
  property-owner check makes that test fail by entering the projection fast path.
  An adjacent selected-set regression covers the all-source retained preflight: when one projection
  source lacks retained-index capability, no source projects before the normal ordered fallback.
  Final Cypher application line coverage is `98.0132%`, above the 98% CI threshold.
- `global-wide-distribution-localized-early/dense` improves from
  `2.626/2.702/2.708 ms` to `1.183/1.203/1.188 ms`, or `2.22x/2.25x/2.28x`. Every run returns the
  same 200 rows and digest from source zero with exactly 1,053 work units. The short wrapped-dense
  raw path remains effectively unchanged at `1.529/1.389/1.511 ms` versus
  `1.371/1.366/1.416 ms`, with the same 200 rows and 665 work units.
- Aggregate base P50 is `1.800/1.918/2.079 ms` and candidate P50 is
  `1.716/1.745/1.892 ms`. Base P95 is `49.896/45.804/65.826 ms` and candidate P95 is
  `42.031/43.380/80.179 ms`; the tail remains the wrapped `DISTINCT` dense case. Pair three is
  `21.8%` slower, but that greater-than-15%-and-1-ms movement occurs in only one of three pairs and
  therefore does not meet the existing repeated-regression rule. Aggregate charged work is exactly
  `58,071,626` in every process.
- Base/candidate process CPU is `1.646/1.545/1.691 s` versus `1.409/1.477/1.614 s`; peak used heap
  is `4.381/4.380/4.386 GiB` versus `4.040/4.386/4.388 GiB`; peak RSS is
  `4.897/4.917/4.892 GiB` versus `4.625/4.905/4.885 GiB`. Every process reads four available
  processors, plans `2 graph + 2 segment`, and observes both worker peaks at two.
- Exact hosted Attempt 130 run `33831665334` is the current main comparison: all three aggregate
  P95 speedups already clear 5x (`5.55x/6.79x/7.75x`) with exact `102/102` correctness, but its
  aligned localized-early regression repeats in two pairs and wrapped-dense repeats in all three.
  Attempt 131's local paired evidence addresses the localized-early component only; the pushed
  exact-head CI and its unresolved review threads remain mandatory gates.

**Conclusion:** keep for exact-head hosted validation. The change removes node materialization only
when the existing mapped view is already live, produces no startup-wide work, and adds no persisted
file, format, magic, version, writer, or configuration. It deliberately does not claim to solve the
separate wrapped-dense aligned regression. Full CI, hosted real64 gates, and every review thread must
pass before completion. This commit is not authorization to merge or tag; either action requires a
new explicit user instruction.

### 2026-09-05 - Attempt 132: Scan selected provenance tuples with primitive loops

**Hypothesis:** the v2.4.7 selected-provenance path spends its tail in generic collection and
sequence machinery. Replace only that tuple scan with primitive loops while preserving the exact
raw CallSite predicate, source order, projection, and limit behavior.

**Evidence:**

- Base is exact v2.4.7 `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`. The candidate was an
  isolated prototype over that revision and was rejected before a production commit was minted;
  this record commit is retrospective and contains no candidate code. The measurement identity is
  bound to JSON/TSV SHA-256
  `6b8a532fb65faafbe25afabaf5ab3c8a6a723630bd485c94d7a83975866f010f` /
  `1ca420eeea78dadca710d291d7f905501155ed65892caf9420e05790ffa1362d`;
  base artifacts are
  `b40f1265508baa5146b6f433f4aaf49f9ac6c89ce70f8cbb3269b703d6164862` /
  `4eb359cc26d4f3cbee0284b50138fa8f9b468ff5eccb5a8a549ce5caf296ad56`.
- The cold global-wide replay used 64 distinct persisted graphs generated from the four pinned
  Android, Tika, Hive, and Kotlin fixture JARs, an 8 GiB heap, four active CPUs, and the 34-case
  oracle. Manifest SHA-256 is
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`;
  provenance SHA-256 is
  `86b13a58b2837fbaa317d70fe486fc552b38bfcdd4f615b8bf125c9992b01a1d`.
  No synthetic performance data was used.
- All `34/34` queries succeeded with zero failure or timeout. Base P50/P95/max was
  `238.758/404.579/1727.752 ms`; candidate was
  `241.523/345.298/399.726 ms`. P95 improved only `1.17x`, while P50 regressed.
  Process CPU was `10.275 -> 9.595 s`, peak used heap
  `5.005 -> 3.852 GB`, peak RSS `6.167 -> 5.028 GB`, and charged work
  `109,198,717 -> 107,052,406`.

**Conclusion:** reject and remove. Primitive tuple iteration reduces some tail and memory cost but
does not approach the required 10x P95 and slightly worsens the median. No production code from
this prototype remains.

### 2026-09-05 - Attempt 133: Prebuild a full retained heap string index

**Hypothesis:** paying once to materialize the complete CallSite string index on heap may eliminate
the repeated wide raw scan and reduce P95 enough to justify its admission cost. Measure both normal
admission and a forced-index control.

**Evidence:**

- Base, fixture, JVM, and oracle are identical to Attempt 132: exact v2.4.7
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`, manifest
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`, 64 real persisted
  graphs, 8 GiB, and four CPUs. Both isolated prototype variants completed `34/34` queries with
  zero failure or timeout.
- Normal admission measured P50/P95/max
  `240.112/413.342/550.110 ms`, process CPU `10.634 s`, peak used heap/RSS
  `3.993/5.358 GB`, and `111,243,848` work units. Its JSON/TSV SHA-256 is
  `d8cab48a6d98ba5c5cf2ba0485b34b55031d02a9b944f202ba285dcf205de3e8` /
  `67643d8fd0ef674ff3b2bef73457fe03f731e25645fe41c57e9a5f166aeb9253`.
- Forced admission lowered P50 to `3.144 ms` but P95/max was
  `461.308/1564.316 ms`; CPU was `6.019 s`, heap/RSS `5.024/5.921 GB`, and work
  `42,644,875`. Its JSON/TSV SHA-256 is
  `d0312ed722cd958cf8e4eb5bcea305d987617fec87e355c157b4f84502bbf6dc` /
  `4d9af467749633a872a5412585b85a9563fb1d6ad0c5d008a6b3c0dd47444b33`.
  The paired v2.4.7 control was `238.758/404.579/1727.752 ms`, `10.275 s` CPU,
  `5.005/6.167 GB` heap/RSS, and `109,198,717` work units.
- These were rejected isolated patches over v2.4.7, not Git revisions. Their exact result artifacts
  above identify the measurements; this record commit retains no experimental production code.

**Conclusion:** reject and remove. Normal admission makes P95 worse, while forced admission merely
moves the full-index build into the cold tail, increases heap pressure, and still regresses P95.
A graph-lifetime read-only view of the existing persisted index is the next direction.

### 2026-09-05 - Attempt 134: Read the persisted CallSite string index through mmap

**Hypothesis:** `graph.callsite-string-index` already contains the string table and node postings
needed by the query. A checksum- and structure-validated read-only mmap view can avoid both a full
heap rebuild and most raw string decoding without changing the persisted format or writer.

**Evidence:**

- The candidate was an isolated mmap prototype over exact v2.4.7
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`; rejected code was removed before this
  record-only commit. Its JSON/TSV SHA-256 is
  `09d8037229f97a5358fa7626bbc1777f54a20edd932a0fbebb31dabb1f81056f` /
  `79620c1308c50a485006465fcd66e62612fcabfb88fa8c1745de4a81efd0d702`.
- The same real fixture64 cold replay and oracle as Attempt 132 completed `34/34` queries with
  zero failure or timeout. Base P50/P95/max was `238.758/404.579/1727.752 ms`; mmap candidate
  was `2.478/260.414/415.707 ms`. P50 improved `96.36x`, but P95 improved only `1.55x`.
- Process CPU fell `10.275 -> 4.259 s`; peak used heap was
  `5.005 -> 5.080 GB`, peak RSS `6.167 -> 6.154 GB`, and charged work
  `109,198,717 -> 80,421,998`. The fixture manifest/provenance SHA-256 remained
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b` /
  `86b13a58b2837fbaa317d70fe486fc552b38bfcdd4f615b8bf125c9992b01a1d`;
  no synthetic performance data was used.

**Conclusion:** reject this initial implementation, retain the mmap direction. It proves the
persisted sidecar can collapse the median and CPU, but broad tuple/provenance work still dominates
P95 and the candidate misses 10x by a wide margin.

### 2026-09-05 - Attempt 135: Stream mapped validation in persisted order

**Hypothesis:** keep the mmap view, but make its validation and traversal sequential in persisted
order so opening 64 sidecars faults fewer unrelated pages and does less temporary work before the
selected tuple is known.

**Evidence:**

- Base is exact v2.4.7 `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`; candidate was an
  isolated uncommitted variant over that SHA. The real fixture64 manifest and provenance are
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b` and
  `86b13a58b2837fbaa317d70fe486fc552b38bfcdd4f615b8bf125c9992b01a1d`.
  The 8 GiB/four-CPU cold replay used all 64 persisted graphs and no synthetic performance data.
- All `34/34` cases matched the oracle with no failure or timeout. Candidate P50/P95/max was
  `2.443/261.187/418.976 ms`, versus `238.758/404.579/1727.752 ms` for v2.4.7.
  CPU improved to `2.783 s`, but P95 stayed at only `1.55x` speedup.
  Peak used heap/RSS was `5.006/6.115 GB`, and work fell to `74,857,303`.
- Result identity is JSON/TSV SHA-256
  `cfc9fa4d683af5702882c10c08260f3993b52b678b30aec1c5c3571c172ce774` /
  `4c20b380065fa324bf6e871fa35fb979de60f74e62acd213efc64c2b3a310283`.
  The prototype code was removed; only this record is committed.

**Conclusion:** reject as a terminal plan. Sequential mapping reduces CPU and charged work, but it
does not change the tail-driving lookup shape. The next attempt must anchor provenance on a
selective posting instead of walking broad mapped state.

### 2026-09-05 - Attempt 136: Anchor selected tuples on the shortest posting

**Hypothesis:** a selected provenance tuple requires four exact properties. Resolve its four string
IDs, choose the shortest property posting as the anchor, and verify the remaining values only for
those candidate nodes. This should replace the broad mapped walk with selective intersection while
preserving the earliest encounter-order tuple.

**Evidence:**

- Exact base is v2.4.7 `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`. The isolated candidate
  used the same 64 persisted real graphs, fixture manifest
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`, 8 GiB heap,
  four CPUs, and 34-case oracle. All `34/34` cases succeeded with zero timeout or failure.
- Candidate P50/P95/max was `2.525/94.351/419.564 ms`, versus base
  `238.758/404.579/1727.752 ms`. P95 improved `4.29x`; CPU fell
  `10.275 -> 1.802 s`, heap/RSS was `4.712/5.248 GB`, and charged work fell
  `109,198,717 -> 61,302,962`.
- The candidate JSON/TSV SHA-256 is
  `99ac5fbeaf6409dabde0cc62c2aae7eb1bdd3c9c9212d1c5dfd02fd6a9a745e9` /
  `5f2b25b9a37be4f5fbb5b45f19fcfbfd85378e3460337f2afe75ba6a3ca5389f`.
  This rejected prototype was not assigned a Git revision and no production change from it remains.

**Conclusion:** reject this form but retain shortest-posting anchoring as a building block.
Selective intersection materially improves P95 and resource use, yet still misses the 10x target;
the remaining duplicate raw/mapped provenance pass must be removed.

### 2026-09-05 - Attempt 137: Choose a hybrid raw-first provenance route

**Hypothesis:** use the raw leading graph when it can fill the limit cheaply and enter the mmap tuple
path only for broad or sparse provenance. A hybrid selector may avoid mmap startup for dense rows
without giving back the shortest-posting tail reduction.

**Evidence:**

- Three fresh candidate JVM screens ran over exact v2.4.7
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b` with the same real fixture64 manifest
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`,
  8 GiB heap, four CPUs, and independent 34-case oracle. All `102/102` cases were correct with
  zero failure or timeout; no synthetic timing data was used.
- The three candidate P50/P95/max samples were
  `2.583/73.450/417.527`, `5.584/1260.470/6325.956`, and
  `2.673/67.265/600.937 ms`. CPU was `1.713/2.411/1.491 s`;
  peak heap was `4.697/4.433/4.702 GB`, peak RSS
  `5.225/4.846/5.217 GB`, and work was a stable `60,315,205`.
  The second fresh JVM exposes an unacceptable multi-second cold-tail outlier.
- Run 1/2/3 JSON SHA-256 is
  `9c164412bc7dd3052197a2681c519cf112e2db9cc2bbdd2712a5e26ba7265de7`,
  `aae9ecd1a2080b2f23d725fa7373c9c7988e0bafc8fda21569ddf3d484c7e98b`, and
  `b87007a7d548fb7a1999dcb286665c7838e5bb34045f074da0bd3111741fadc2`;
  TSV SHA-256 is
  `1c7c23c0dbb1cca8d67c77630a3ff31bc76a77e67269a0d1df7c7089ba463ea8`,
  `cd1f7bd470fbd8e6b8d094647d9d60f38e1436025cc90bf980fe8a29ec507d61`, and
  `312d2347730fb42ead51ddb27b781ac2b6ebd46000c6d3e82302998c91716a1e`.
  The candidate was an uncommitted isolated patch and is absent from the tree.

**Conclusion:** reject and remove. The selector preserves results and average work but introduces
severe cold-state instability; a 10x claim cannot rely on two favorable screens around a
`6.326 s` max. The mapped path must make one deterministic choice before emitting any row.

### 2026-09-05 - Attempt 138: Remove mapped validation caching

**Hypothesis:** the graph-lifetime posting-validation cache may be adding synchronization and lookup
cost to every selected tuple. Revalidate the chosen immutable posting directly per query and retain
no cache state.

**Evidence:**

- The candidate was an isolated patch over exact v2.4.7
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`. The same 64 real persisted graphs, manifest
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`,
  8 GiB/four-CPU cold setup, and 34-case oracle were used. All `34/34` queries passed with no
  timeout or failure.
- Candidate P50/P95/max was `2.839/59.796/458.863 ms`, a `6.77x` P95 improvement over the
  v2.4.7 `404.579 ms` control but still below 10x. CPU was `1.325 s`, peak heap/RSS
  `4.272/4.749 GB`, and charged work `60,315,958`.
- JSON/TSV SHA-256 is
  `5cb2b198ab15194fbdd72c868bbd231c9ffe30966812efd9a017fe3e3b449d50` /
  `766852742b7de2821081fa2f478c3d5fb82f8c42b7d322feadef94205ce6bcd3`.
  The experiment had no candidate Git commit; this record retains its result identity and no
  rejected production code.

**Conclusion:** reject. Removing cache state is not enough to reach 10x and repeats complete posting
validation on recurring terms. Keep full pre-yield validation, but eliminate the second provenance
scan rather than the integrity check.

### 2026-09-05 - Attempt 139: Replace tuple object reads with primitive mapped accessors

**Hypothesis:** selected tuple verification still constructs or crosses high-level node/property
accessors. Read tuple fields and node offsets directly from primitive mmap accessors to remove that
allocation and dispatch from the tail.

**Evidence:**

- Two isolated variants were measured over exact v2.4.7
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b` using the same real fixture64 manifest
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`,
  8 GiB heap, four CPUs, and 34-case oracle. Both completed `34/34` cases with zero failure or
  timeout.
- The direct tuple-read variant measured P50/P95/max
  `2.802/171.565/976.852 ms`, CPU `2.297 s`, heap/RSS
  `4.687/5.240 GB`, and `60,315,958` work units. Its JSON/TSV SHA-256 is
  `ef58a71227c88f732213696cc44cd44c6a25240cc96eb4b79aa62a83979fe9ae` /
  `e27378a57cc9d020452aeeecbfdd08f64118cae5a0b117acbf3a348dd76ad2f3`.
- The primitive accessor variant improved that to
  `2.867/77.392/463.901 ms`, CPU `1.836 s`, heap/RSS
  `4.239/5.474 GB`, with the same work. Its JSON/TSV SHA-256 is
  `e39d0009594f8a5a957ef4ae726334f7fc4fea7c7fffb85b3ce297cf25cd97ae` /
  `4ac02802d615782be599b78e89be26092a8265db83648748391a501c0f95432e`.
  No synthetic timing data was used; neither prototype received a Git revision.

**Conclusion:** reject both exact variants and remove their code. Primitive access is the better
building block, but even its P95 is only `5.23x` faster than v2.4.7. Reusing the tuple match already
found for provenance is still necessary.

### 2026-09-05 - Attempt 140: Batch selected tuple lookup

**Hypothesis:** resolve all selected provenance tuples in one mapped pass so repeated string-table
searches and posting setup are amortized across the request.

**Evidence:**

- Exact base is v2.4.7 `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`; the candidate was an
  isolated, later-removed prototype. The cold 34-case run used the same 64 persisted graphs from
  four pinned fixture JARs, manifest
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`,
  8 GiB heap, and four CPUs. Correctness was `34/34`, with zero failure or timeout.
- Candidate P50/P95/max was `2.456/107.255/443.040 ms`, versus base
  `238.758/404.579/1727.752 ms`. CPU was `1.857 s`, peak heap/RSS
  `4.679/5.403 GB`, and work `60,315,307`. Batching therefore achieved only
  `3.77x` at P95.
- JSON/TSV SHA-256 is
  `e40bcabb842013a687c6e9947675f9a0c3595ea7641d10fb1b939693874565a9` /
  `e2d60cd5f6aee6ec4d83fc93eebba48e4e1430392c6e25535701d21473a36c0b`.
  No synthetic performance data was used and no rejected production code remains.

**Conclusion:** reject. Batching lookup setup does not remove the duplicate selected-provenance
verification pass and is materially slower at P95 than the best primitive single-tuple path.

### 2026-09-05 - Attempt 141: Reuse selected provenance without rescanning

**Hypothesis:** once the shortest posting finds and fully verifies the earliest exact four-property
tuple, return that selected provenance directly instead of rescanning the same graph through the
generic raw path. Preserve encounter order and validate the complete chosen posting before exposing
any row so corruption can still fall back atomically.

**Evidence:**

- The isolated candidate was based on exact v2.4.7
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`. The same real fixture64 manifest
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`,
  provenance `86b13a58b2837fbaa317d70fe486fc552b38bfcdd4f615b8bf125c9992b01a1d`,
  8 GiB heap, four CPUs, and 34-case oracle were used. Both screens were `34/34` correct with no
  timeout or failure.
- The first candidate screen measured P50/P95/max
  `2.775/15.990/622.367 ms`, or `86.03x/25.30x` at P50/P95 versus v2.4.7.
  CPU was `1.259 s`, peak heap/RSS `4.249/4.761 GB`, and work `60,092,871`.
  JSON/TSV SHA-256 is
  `e992b13a35efbf672d8225425059a54b3cf6735b5681b6d1cd7aa61559c09067` /
  `90489640b6cc9ec7db8758a8f5777f4ecc006a05ff35157ce5d2d9494cf6f3db`.
- A clean reconstruction over the same base measured
  `2.478/27.730/392.140 ms`, still `14.59x` at P95, with `1.308 s` CPU,
  `4.245/4.721 GB` heap/RSS, and identical `60,092,871` work. Its JSON/TSV SHA-256 is
  `17c63e53479d9b3471d516eb93a8b1902b0f5f739ef3672812931cab090eab71` /
  `97e4474446b33cf60875a78e7c31ca163e7415d2893acf7bd2793e4eae0c7bc3`.
  These were uncommitted prototypes; the exact artifacts, not a nonexistent Git SHA, identify them.

**Conclusion:** retain the no-rescan design as the candidate foundation, but do not accept either
single screen as final proof. It is the first variant to exceed 10x P95 twice while reducing CPU,
RSS, and work; independent alternating pairs and full correctness/access auditing are required next.

### 2026-09-05 - Attempt 142: Audit the no-rescan mmap plan in alternating pairs

**Hypothesis:** Attempt 141's no-rescan plan should sustain at least 10x P95 across independent JVMs
when paired with byte-identical-harness v2.4.7 controls and alternating process order.

**Evidence:**

- Base is exact v2.4.7 `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`; the candidate is the
  isolated Attempt 141 prototype, identified by the recorded result set because it predated its Git
  reconstruction. Three pairs ran base/candidate, candidate/base, base/candidate on the same 64
  distinct real persisted graphs, 8 GiB heap, four CPUs, manifest
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`, and a
  base-derived 34-case oracle. All six processes completed `34/34` cases; all 204 observations
  matched outcome, rows, digest, response size, and hit provenance.
- Base to candidate P50/P95/max was
  `268.170/414.735/1007.724 -> 2.416/23.420/405.892 ms`,
  `238.934/417.521/503.826 -> 2.453/26.849/413.690 ms`, and
  `241.674/412.879/444.807 -> 2.461/19.187/384.211 ms`.
  P95 speedup was `17.71x/15.55x/21.52x`.
- CPU fell `10.867/10.781/10.632 -> 1.347/1.375/1.287 s`; peak used heap fell
  `4.999/4.994/4.984 -> 4.676/4.677/4.359 GB`; peak RSS fell
  `6.167/6.169/6.158 -> 5.212/5.176/4.922 GB`. Work was
  `109,198,717 -> 60,092,871` in every pair.
- The comparator nevertheless failed. Candidate wrapped-DISTINCT rows reported no exact accessed
  graph set, the then-current gate prescribed a `2+2` worker topology while this implementation
  was serial (`0+0`), and ten dense cases repeated aligned regressions. The exact status/report
  SHA-256 is
  `aea1415bd402a72c8a16318a24cd1588dd902408293df214edcea1141e777af1` /
  `4d0bac50d10d4bae6190ba0ec654eeb568b237a39b3f2c01bfa59b4d22894dd3`.
  Pair candidate JSON/TSV SHA-256 is
  `bfc3948a64bcc4000cdca059da4f93f5b4c176bcec21c6c4c6c765b84e433b61` /
  `d5800ffb4e9c0e5f528bf8e29b7b0e8a1f189c2e96ed9ba18ca5c4a90f4fcbc9`,
  `3f80c6b5660310f29613c31848ad5c6020a98ce82a129d9bfe5d8fc5d13eb0ec` /
  `69afa785a243b19944a27d0ac931785bfd95a91c6275b92f36631ffae8801675`, and
  `b30e7e571198eb729e8327f76dd69d16d52949b2bc172f7263d092e709858f2a` /
  `d1c9d8316d33a2721d03a92310d398b06277a80771cc2bca9a9561873f0603e5`.

**Conclusion:** reject this candidate as terminal despite its aggregate 10x result. Correctness
values are intact, but access proof is incomplete, dense regressions repeat, and evidence cannot
pass the repository gate. Retain the mmap/no-rescan direction; add exact access accounting and
protect v2.4.7's short dense path before measuring again.

### 2026-09-05 - Attempt 143: Preserve short dense raw scans and prove mapped access

**Hypothesis:** the remaining broad benefit comes from zero/targeted selected provenance, while
short dense terms already fill `LIMIT 200` cheaply in the first graph. Statically keep exact
three-character bounded predicates on the unmodified v2.4.7 raw path, use mmap only for supported
broad shapes, and report the exact graph IDs inspected by mapped DISTINCT provenance.

**Evidence:**

- Candidate production was reconstructed over exact v2.4.7
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`. Stable source identities after rejected
  subvariants were removed are: view
  `d3c585f69bc3f3aba1ace0a572fc45547c397581efaccfd296fb9191de32feab`,
  graph integration
  `cd059f8ef3f0353ec3ab5e23b7d178fb0c26b35e91524918c50ccc19dd4601a2`, and focused test
  `35eb48fbe36a275b904dc0f1f0d43ba1d86e93b79e77a651563981c4bb7a78b4`.
- Three alternating pairs used the same candidate-reviewed harness, real fixture64 manifest
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`,
  8 GiB heap, four CPUs, and exact 34-case oracle. All 204 observations passed correctness and exact
  candidate access proof. Base to candidate P50/P95/max was
  `258.457/470.105/470.881 -> 2.119/15.626/409.911 ms`,
  `254.802/415.132/444.233 -> 2.390/19.546/435.125 ms`, and
  `245.078/409.344/438.022 -> 2.277/17.258/440.823 ms`.
  P95 speedup was `30.08x/21.24x/23.72x`.
- CPU fell `12.008/10.870/10.724 -> 1.212/1.224/1.285 s`; candidate peak heap/RSS was
  `4.698/5.210`, `4.695/5.205`, and `4.359/4.866 GB`, all within paired bounds.
  Work fell `109,198,717 -> 57,710,024`.
- The then-current comparator still failed for two reasons: it prescribed a `2+2` worker split
  even though the candidate intentionally uses a serial consumer, and the identical raw
  `global-wide-four-properties/dense` case measured
  `4.917 -> 7.066`, `5.326 -> 7.836`, and `4.515 -> 7.317 ms`.
  Both sides did exactly 681 work units, exposing cross-case JIT state: v2.4.7 had warmed this raw
  scanner in two earlier cases while the candidate's mapped path had skipped them.
- Comparator status/report SHA-256 is
  `646570434a6086ad6ab470a6a8098bdfe818aedef670f1c48cbd16eaa73e358a` /
  `d6f46429539105d3418ecc128d7085ceacdb113d6c338c20d512e9cdedb9b6ea`.
  Candidate pair JSON/TSV SHA-256 is
  `01f85994bd76a69d2b8d05655c73d0be3fe4e9c922012bfec7a1d69ea7953912` /
  `5d595a6e3b9c8af37f23b3b4ead946615e8d35be832d7e4731d502b2f372a913`,
  `840556021d79b32a0dd98ace71d8c3bdf8aebf0c2f8a5819d6eee2ea0dec10ce` /
  `61963a9a47247ff5c024eef09de56ffaedc2bfe14cb9a7ed343c50d6fcd8fefd`, and
  `11fdfc001808f5a9e75c94c6d0b7d22d4820c0a34bd2ede67a084cd6b301093c` /
  `01f182a4aa3b201f6a867f91214dc13e31f505ec8a9c374360f3b08a84bcdd22`.

**Conclusion:** retain the statically guarded implementation and access/correctness hardening, but
reject this execution order as final evidence. The production path exceeds 10x P95 with lower CPU,
RSS, and work; the benchmark must put the identical raw control in the same cold/JIT position for
both revisions, and the gate must validate bounded worker telemetry without prescribing topology.

### 2026-09-05 - Attempt 144: Replace sequences with an explicit iterator and shared mask

**Hypothesis:** coroutine/sequence state and repeated property-matcher dispatch may explain the
remaining cold raw control difference. Replace the mapped result sequence internals with an
explicit iterator, then share a primitive property mask across that iterator.

**Evidence:**

- Both isolated variants ran over the stable Attempt 143 production state and exact v2.4.7 base
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`, using the same 64 real persisted graphs,
  8 GiB/four-CPU cold JVM, and 34-case oracle. Both were `34/34` correct in the replay, with no
  timeout or failure; no synthetic performance data was used.
- Explicit iterator P50/P95/max was `2.200/14.732/419.999 ms`, CPU `1.266 s`,
  heap/RSS `4.695/5.205 GB`, and work `57,710,024`. The raw control remained
  `6.865 ms` at 681 work units. JSON/TSV SHA-256 is
  `8666bae3aef84ddf8e0d119d8839073bd5ac5ea557df34d1321a934efa4ec155` /
  `41c75fcbd21e62ddb8fa192facf98d53132eaba4c5017f5b2904993e9c9bd69d`.
- Shared mask P50/P95/max was `1.990/15.562/416.522 ms`, CPU `1.263 s`,
  heap/RSS `4.693/5.207 GB`, and unchanged work. The same raw control was
  `6.535 ms`. JSON/TSV SHA-256 is
  `6554ae1a45198efa0f7fd6670cbbd0fcf58c7988e3b9083b803b6ba7a4e3554e` /
  `1be54e662cf016420084d1b6048edb25f844e94ebe5899953ff74a2654413a6d`.
- The explicit iterator also complicated terminal exception state: preserving the exact consumer
  exception object and preventing post-failure continuation required extra state absent from the
  repeatable sequence design. Focused exception behavior was therefore treated as a semantic
  blocker even though the aggregate screen was fast.

**Conclusion:** reject both variants and restore the sequence implementation. Neither removes the
identical 681-work raw-control delta, and the iterator adds avoidable exception-state risk. The
candidate must preserve repeatable/concurrent iteration and exact callback exception identity.

### 2026-09-05 - Attempt 145: Validate every posting at load and route every dense case through mmap

**Hypothesis:** moving posting-order validation to one graph-load pass could remove query-time
validation overhead. As a companion probe, route every dense case through the already validated
mmap view to avoid the raw control path entirely.

**Evidence:**

- The isolated variants used exact v2.4.7
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`, the same 64 real persisted graphs and
  manifest `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`,
  an 8 GiB/four-CPU cold JVM, and the exact 34-case oracle. Both completed `34/34` cases without
  failure or timeout.
- Global load-time posting validation measured P50/P95/max
  `2.197/16.860/514.249 ms`, CPU `1.383 s`, heap/RSS
  `4.698/5.374 GB`, and `57,705,255` work units. It did not improve P95 over the selected-range
  design and raised candidate max materially. JSON/TSV SHA-256 is
  `5ec1a4b80ca5687b2d67ade05c21c9a69af1f2942fda1874dfe54799bc7bb5ad` /
  `58054424bcb71f0e0965ad630048a0969c458a3e6f2587bb41699c758a7412f6`.
- Routing all dense cases through mmap measured
  `2.539/26.604/514.391 ms`, CPU `1.517 s`, heap/RSS
  `4.711/5.377 GB`, and `59,809,076` work units. It made P95 and work worse than the guarded
  candidate. JSON/TSV SHA-256 is
  `0c6cf4a9b63270def3be7652e41f9e0bdccb424ec99f186c29338e43350fec28` /
  `fe7e34c4b821357c09f523ae7afd6db0c607cc12923cdf92b1213336240d25a5`.
  No prototype code was committed or retained.

**Conclusion:** reject and remove both changes. Whole-index semantic validation faults data that
the selected query never uses, while all-mapped dense routing gives back the proven cheap
v2.4.7 raw-leading behavior. Retain complete validation only for the selected posting before any
row is returned.

### 2026-09-05 - Attempt 146: Dispatch raw earlier and feed mapped string IDs into raw scan

**Hypothesis:** move the static short-dense decision to the top-level dispatcher to avoid mmap probe
overhead. Separately, resolve exact string IDs through mmap and pass them to the raw scanner so it
can avoid repeated string comparisons while retaining raw encounter order.

**Evidence:**

- Both variants were isolated patches over the stable Attempt 143 candidate and exact v2.4.7 base
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`. They used the same 64 real persisted graphs,
  8 GiB/four-CPU cold JVM, fixture manifest
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`, and
  34-case oracle. Both were `34/34` correct with zero failure or timeout.
- Top-level dispatch measured `2.064/15.591/421.772 ms` at P50/P95/max, `1.267 s` CPU,
  `4.697/5.226 GB` heap/RSS, and `57,710,024` work units. Its identical raw control remained
  `6.832 ms` for 681 units. JSON/TSV SHA-256 is
  `e3f09fa2f247488ffd63408cb6aa81e312c059d549b4562d5e85c45ac5dad59a` /
  `a61a7e60db5aa45c0348ba4f6a5c11446ebcaabf9474595cdd734ee9f519913e`.
- Mapped-ID-assisted raw scan measured `2.171/16.281/445.010 ms`, `1.286 s` CPU,
  `4.698/5.302 GB` heap/RSS, and `57,751,994` work units. The raw control regressed to
  `9.462 ms` and `4,878` units. JSON/TSV SHA-256 is
  `a3ff1e00a323b3d38b5f1094edc24e396f87dda88b5354b1fe7b95d1a4b173dd` /
  `672566b3de5ab4996c108ef19f54ce5da6ecc790f5f684d8e15dffe374c349c1`.
  Neither uncommitted candidate is present in the production tree.

**Conclusion:** reject both and restore Attempt 143's production state. Earlier dispatch does not
remove cross-case JIT asymmetry, and mapped IDs add work to an already cheap raw scan. The honest
fix is benchmark symmetry: keep the raw control, but put it first for both byte-identical harnesses
in their fresh JVMs.

### 2026-09-05 - Attempt 147: Map the persisted index and reuse selected provenance

**Hypothesis:** open the existing v2 `graph.callsite-string-index` as a validated read-only mapped
view for serial global-wide queries, resolve selected tuples through their shortest postings, and
return the already verified earliest tuple instead of rescanning raw CallSites. Keep bounded exact
three-character queries on v2.4.7's raw path, and place that identical raw control first in both
fresh benchmark JVMs so the comparison has symmetric cold/JIT state.

**Evidence:**

- Base is exact v2.4.7 `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`; the candidate revision is
  the Attempt 147 commit containing this record. Its stable content identities are mapped view
  `d3c585f69bc3f3aba1ace0a572fc45547c397581efaccfd296fb9191de32feab`, graph integration
  `cd059f8ef3f0353ec3ab5e23b7d178fb0c26b35e91524918c50ccc19dd4601a2`, shared constants
  `9765aae0be20169724f4846fd8da703afe5553ff09f1a1dfe710bae0fc0308b8`, focused tests
  `3ea9731b9fb5dfc79f8ff068a631c1f8176d4ed0b4ed8241080bea58626ee760`, and JMH harness
  `b07de7fca27c786b305dda77cd1fc08c328bbf5ad17e254d2082af29e0e36bf8`. The base and
  candidate benchmark JAR raw SHA-256 is
  `5297473aa8c38353c2aa21cc61a86c7c7fac3fab427b31c2cd0065f66865a2dd` /
  `0b361ad01168c36a5fc7f8c57d8693e205dc70ea018b09c1872b747643055613`; their canonical ZIP
  content hashes are `12f060ddf4c886acf7774bcc1e13cf9761ddf2109ef83d9f6acee09ba55e96a8` /
  `7d5092fe02e176b5582802ef308ae22867d2a980aeb59b58f2b97a564859362c`.
- Three independent cold pairs ran candidate/base, base/candidate, candidate/base under JDK
  17.0.18, `-Xmx8g`, `-XX:ActiveProcessorCount=4`, zero warmup, one measurement, and one fork.
  Both revisions used the byte-identical candidate-reviewed harness and the same 64 distinct real
  persisted graphs from four pinned fixture JARs. Manifest SHA-256 is
  `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`; fixture provenance
  SHA-256 is `86b13a58b2837fbaa317d70fe486fc552b38bfcdd4f615b8bf125c9992b01a1d`.
- All six measured processes completed `34/34` cases with zero failure or timeout and `2,925`
  rows. All 204 observations matched the base-generated outcome, row count, response bytes, and
  digest oracle; fixture distribution plus hit/access provenance passed their independent checks.
  Oracle SHA-256 is
  `ca62e20e7b043e7a89af44c60dd06d5bd01261bd0eda1630775b68921d449f51`; work fell from
  `109,198,717` to `57,710,024` units in every pair.
- Base to candidate P50/P95/max was
  `238.065/403.804/507.673 -> 1.931/27.139/407.101 ms`,
  `235.851/472.811/493.800 -> 1.805/26.692/411.352 ms`, and
  `242.379/400.292/503.915 -> 1.904/26.903/416.873 ms`. Every pair clears the `10x` P95
  requirement at `14.88x/17.71x/14.88x`; the worst required wrapped-shape speedup is `24.42x`.
- The first-position v2.4.7 raw control stayed neutral at identical `681` work units:
  `27.340 -> 27.139 ms`, `26.462 -> 26.692 ms`, and `27.278 -> 26.903 ms`. No case repeated
  a greater-than-15%-and-1-ms regression. Candidate max stayed below its paired base max.
- CPU fell `10.438/10.839/10.294 -> 1.039/1.137/1.065 s`. Peak heap was
  `4.39/4.30/4.65 -> 3.92/4.38/4.37 GiB`, and peak RSS was
  `5.45/5.37/5.74 -> 4.40/4.85/5.02 GiB`; every resource measurement passed the paired
  non-regression bound. Pair 3's advisory committed-heap and post-run used-heap gauges rose once,
  but did not repeat; its peak used heap, RSS, and normalized allocation all fell. The local
  fail-closed data comparator returned PASS with no errors. Comparator/status/report SHA-256 is
  `023b00cc6506465107e9e4bf4f545763c55dc5b147b3849564d1086125d3500e` /
  `35560cd4c9b020766726c07bc3c16320824ae9710ceb9ccc24d171b266ed5b62` /
  `b7d9696e1c6d54f6d49fa703b52b300307a88f334678506238230d6a3cae3a7d`.
- Pair base JSON/TSV SHA-256 is
  `8a65dedce728694676c321edb8637abaa3424f9f2452939bd2bbe106573bc554` /
  `19b57dc96df3fc242faedcc630147f92ac79fe1a309d70b3deb8b1d9acf065de`,
  `d7008a87868198daf02b266eab0f45128f301651c86911e3c4b2dfe4852c0e8b` /
  `4d8417b50ce643d499e6e4bdaa5f97403bb36be6c5c6472b8986345140ccbcbc`, and
  `71c71d0a0debfec6f137104f32f2cc5432fbb3391179d2b581edd942aae1a026` /
  `64701c36e6eda6c590c72b69690fac88f22d2a70229ab676b488f7ac03a87462`.
  Candidate JSON/TSV SHA-256 is
  `d4207e6eb2be6aa1a296465eeb7ed3a000de0d1937caa0bb3ef157c51f281092` /
  `a6c80cb4c16dd99e519f5b55c610341ea0af9db1d361eebd0ff954c60e7eae37`,
  `44d04154d145a053b6dc217db5ff18977d9c766c7f8f889cb77178767f55cca3` /
  `bd8b88762564c0a511ee8775e574d1a1fb3a77e91d1d31934dc3d877dc8b3f58`, and
  `ad27fd10e4f721b76fb87e8ba33883da47b3d6b2fecf990952fb1e2178ac53c5` /
  `cfe39e96c3d1bc47ed800f81111829fcf8c66051aa718dc17cb97ef0ec3e5658`.
- `:webgraph:test`, `:webgraph:detekt`, and `:webgraph:jmhJar` passed together. The suite ran 178
  tests with zero failure/error/skip, including all 12 focused mapped-view tests. Missing, stale, or
  corrupt sidecars fall back without rewriting; heap-index priority, interruption, exact consumer
  exception identity, encounter order, limits, null projections, repeatability, concurrency, and
  clear/reopen lifecycle are covered. The reader changes no magic, format version, or writer bytes.

**Conclusion:** keep. This is the first fully gated candidate to exceed `10x` P95 in every
independent pairing without a repeated per-case or resource regression. Use the read-only mapped
view only for eligible serial queries; preserve every unsupported, short-dense, unavailable, and
invalid-index path as the v2.4.7 fallback. The source and local artifacts are content-addressed
above; the hosted dual-baseline run must still bind the committed candidate SHA and remains the
authoritative merge gate.

### 2026-09-05 - Attempt 148: Project request-scoped retained graph sets directly

**Hypothesis:** the persisted heap indexes are already retained for request-selected K-source
queries, but the rollback pipeline still materializes every selected `CallSiteNode` before applying
`RETURN`. Preflight every selected graph for the retained lookup capability, then project safe
non-`DISTINCT` string properties directly in catalog order with one global remaining `LIMIT` and
the existing provenance cache. Keep unscoped global-wide queries, unsupported predicates,
annotations, residual node predicates, and any incomplete preflight on the authoritative v2.4.7
fallback.

**Evidence:**

- Base production is exact v2.4.7
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`; candidate production is the Attempt 148 commit
  containing this record. The candidate is based on predecessor
  `6c6ebee907441b19efb3b33a9f5a90112edd41e7`. Base/candidate JMH JAR raw SHA-256 is
  `20672f264688d0d7c2ed2ed775c4a6441aac8f67b83bafeff9fc83845a3fc829` /
  `7d3ff6ec3d423833f72675b8fcf7c71b90ae7e8b2bbdf2a0981336e635cf5a85`; canonical ZIP content
  SHA-256 is `12f060ddf4c886acf7774bcc1e13cf9761ddf2109ef83d9f6acee09ba55e96a8` /
  `68333b517e24a9d6c125b88500df6e5e0bf1f82c8f9e1a6cd792b2e19f273ecf`.
- The exact graph-routing JMH command was
  `java -jar <jar> io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries
  -p graphCount=64 -p coverageFamily=graph-routing -p indexState=<state> -p timeoutMillis=300000
  -wi 0 -i 1 -f 1 -to 30m -foe true -prof gc -rf json -rff <result.json> -jvmArgs
  "-Xmx8g -XX:ActiveProcessorCount=4 -Dgraphite.broad.pressure.graphs=<manifest>
  -Dgraphite.broad.pressure.correctness.<record-or-verify> ..."`. The runs used JDK 17.0.18 on
  Apple M3 Max/macOS 14.3, one fork, one single-shot measurement, no warmup, cold base/candidate
  order and warm/startup candidate/base order. Both revisions used the byte-identical
  candidate-reviewed harness.
- All runs used the same 64 distinct real persisted graphs derived from the four pinned Android,
  Tika, Hive, and Kotlin compiler JARs. Original manifest/provenance SHA-256 is
  `3c019438680a5e95e8ccc335e001c6b6e54b4e5b904871750b5742e3c17037d5` /
  `4a87e194346a32d2b348b79780bfd9a35136365cb5fc7388edc67a957ea0ece9`; the local manifest differs
  only by absolute graph paths. The independent single-source-derived oracle SHA-256 is
  `bc8edcf39a58c19d2f8bb4e269402308e916e3cb5df5ef9740d33e34f4f7836d`.
- Every state completed `1137/1137` queries with zero failure or timeout and `78,824` rows. Every
  candidate observation matched the oracle, and source-access, source-order, graph identity,
  retained-index lifecycle, and fixture distribution checks passed. The identical correctness
  output SHA-256 is
  `35fc69539c3080dbb801cca4ec7f1e7541f3ccd190d8774861f6109f7c58b6dd`.
- The exact paired graph-routing comparator passed cold, warm, and startup-prepared. Selected-set
  latency was:

  | State | K | Base P50 | Candidate P50 | Base P95 | Candidate P95 |
  | --- | ---: | ---: | ---: | ---: | ---: |
  | cold | 2 | 0.151 ms | 0.045 ms | 0.490 ms | 0.262 ms |
  | cold | 8 | 0.167 ms | 0.064 ms | 0.791 ms | 0.789 ms |
  | cold | 64 | 0.254 ms | 0.087 ms | 1.706 ms | 1.611 ms |
  | warm | 2 | 0.032 ms | 0.023 ms | 0.268 ms | 0.068 ms |
  | warm | 8 | 0.045 ms | 0.025 ms | 0.258 ms | 0.061 ms |
  | warm | 64 | 0.117 ms | 0.050 ms | 0.261 ms | 0.075 ms |
  | startup-prepared | 2 | 0.139 ms | 0.050 ms | 0.538 ms | 0.169 ms |
  | startup-prepared | 8 | 0.159 ms | 0.052 ms | 0.535 ms | 0.490 ms |
  | startup-prepared | 64 | 0.235 ms | 0.104 ms | 1.371 ms | 1.264 ms |

  The first cold K64 request improved `485.003 -> 398.560 ms`; the initial reverse-order
  diagnostic was discarded because the candidate alone paid the empty OS page-cache cost.
- Effective CPU cores were `1.70 -> 1.60`, `1.55 -> 1.60`, and `2.84 -> 2.39` for cold, warm,
  and startup-prepared. Peak used heap was `4.77 -> 5.46`, `5.48 -> 5.52`, and
  `5.44 -> 5.52 GiB`; peak RSS was `5.99 -> 7.46`, `6.72 -> 7.55`, and `5.88 -> 5.81 GiB`.
  All stayed inside the graph-routing resource gate. The candidate performed zero intra-graph
  scans after preparation and retained all 64 indexes; no mmap or index lifecycle rule changed.
- Cold/warm/startup candidate JSON/TSV SHA-256 is
  `144fba19bc62c65af7aba9350b227a1dcf9aa71f05579e8472ab305b52af76b7` /
  `2d036e30cdc228ce9643a8df52ff14421386bddc51eca0d97f6340572752d63e`,
  `1a219cb124e5ff752d7151019741cdd5b2397edd79be3789e0ff596111097045` /
  `a46bd5066b91bb8eeaa1c25f6977be9979b907fc170e91c6e136e8e8cec75818`, and
  `ad5af4b853a0c04cba7d4bd70de0170971be2e478b207a25f645c2922e9b62df` /
  `31f4a06f1ae441f159581703b5e79b425beed0dfb7c2d46317606dc5fe807f90`.
  Comparator status/report SHA-256 is
  `080243bb5f86dfb77efc3304ae91d52e73e0ee16a086b567fb6890e279946886` /
  `45e5414efacc98cbe639f50df70da58a69e7e477eca7561802e8afe55a47fc9e`,
  `38427e9f3713559b573b5b10f2317a8daec608fa7f4434b53b10e6b7f8b799b1` /
  `73ea1f9e6b893001f28f3a7c19eb77bdecdf951952faa94e00f57383057a675a`, and
  `d5471278fb917b8ce3a339f11bfe9e9cc68d42879854082184a954c53808abe7` /
  `94ab734d6f186593aaa810e526534c707c55b9e6f5a278f8cb5d2a0193208258`.
- Focused Cypher execution and mapped-index lifecycle tests passed, including literal and parameter
  graph sets, complete all-source preflight before the first projection, source order, remaining
  limit, provenance, authoritative fallback, explicit request scope, and clear/reload/eager
  lifecycle. `:core:detekt :cypher:detekt :webgraph:detekt :explore:detekt`,
  `:webgraph:jmhJar :cypher:jmhJar :explore:test`, the full `CrossGraphCypherExecutorTest`, and the
  affected `GraphStoreTest` passed.

**Conclusion:** keep. A graph set may use direct projection only after every selected source proves
that its retained index already supports every predicate; otherwise no source projects and the
whole request follows the authoritative fallback. This closes the hosted graph-routing regression
without widening unscoped global-wide behavior. The final committed candidate still requires the
hosted dual-baseline gate together with the remaining global-wide dense and zero/max fixes.

### 2026-09-05 - Attempt 149: Project the leading dense source from primitive storage

**Hypothesis:** current main's accepted primitive projection and bounded node-prefix reuse can be
restored without restoring v2.4.8 wholesale. For an unscoped, non-`DISTINCT` CallSite query with
only short `CONTAINS` disjuncts and `LIMIT <= 200`, probe only catalog source zero through primitive
string IDs, synthesize graph provenance in the query layer, and cache at most 16 immutable matching
node-ID prefixes. If the bounded probe cannot prove a complete prefix, retry source zero through
the authoritative v2.4.7 path before visiting later sources.

**Evidence:**

- Goal base is exact v2.4.7 `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`; non-regression base is
  current main/v2.4.8 `4e328b0109e13c896b74004823fb049fcb19251a`; candidate production is the
  Attempt 149 commit containing this record on predecessor
  `8ce816eb07a70efa30072fe5f7d55bcc8611a054`. The exact pre-document production/test diff SHA-256
  is `133e0eba0e1f77ec6408e9c37e9ed942d762a60b07b94a457a3e4d92a4c1dd9b`. Stable content
  identities are graph capability
  `9e072938dd03023867000e299f7dcc86ed38bf309093fa7c76d006267babf7ba`, query integration
  `31c0647e0f29efdc53c5e50c0e2f021a36df0c02cc6c03b50a7f55b513311b18`, storage implementation
  `c0b6eb30271911d7a938d9de267c70bd3f3606d742bf6fe04aa5267e5ecd4ca7`, query tests
  `1a69896435bbb69e2bd6523c5b7465dd211368c10e77b77cb18a991fdf5c0fcc`, and storage tests
  `feb379ea429c7649b5a5dfa91a2e33e9a891e29c499eb06a5376f4a6d0e0e271`.
- Goal/current/candidate JMH JAR raw SHA-256 is
  `20672f264688d0d7c2ed2ed775c4a6441aac8f67b83bafeff9fc83845a3fc829` /
  `a00874b3fee87193d7902c6c5fc32aa32caf2be37eb6b8d58dee67767500608b` /
  `d7b45a2d68f2e37269c1743e785088b17edf1a14eb57b3bfea0be4b9f9d06593`; canonical ZIP content
  SHA-256 is `12f060ddf4c886acf7774bcc1e13cf9761ddf2109ef83d9f6acee09ba55e96a8` /
  `830bee69d86335dfdb1e1635205522e2262909d1dae92a9f81959db0a46b3461` /
  `e94b987bff0a436fe1ebdadf58089ab547c3a0d0c1f7d3ac17388776869c6365`. All three used the
  byte-identical candidate-reviewed JMH harness
  `b07de7fca27c786b305dda77cd1fc08c328bbf5ad17e254d2082af29e0e36bf8`.
- The exact command shape was
  `java -jar <jar> io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries
  -p graphCount=64 -p coverageFamily=global-wide -p indexState=cold -p timeoutMillis=300000
  -wi 0 -i 1 -f 1 -to 30m -foe true -prof gc -rf json -rff <result.json> -jvmArgs
  "-Xmx8g -XX:ActiveProcessorCount=4 -Dgraphite.broad.pressure.graphs=<manifest>
  -Dgraphite.broad.pressure.correctness.<record-or-verify> ..."`. Three fresh paired forks ran
  goal/current bases then candidate, candidate then goal/current bases, and goal/current bases then
  candidate. The host was Apple M3 Max/macOS 14.3 with JDK 17.0.18, four exposed CPUs, one
  single-shot measurement, and no warmup.
- Every run used the same 64 distinct real persisted graphs derived from pinned Android, Tika,
  Hive, and Kotlin compiler JARs. Original manifest/provenance SHA-256 is
  `3c019438680a5e95e8ccc335e001c6b6e54b4e5b904871750b5742e3c17037d5` /
  `4a87e194346a32d2b348b79780bfd9a35136365cb5fc7388edc67a957ea0ece9`; the local-path-only
  manifest SHA-256 is `24876ee54a7c5cded5a68b476613ac26e357f3973bb2d54a2ac56f5ea54bbd81`.
  The base-recorded oracle and every correctness output have SHA-256
  `ca62e20e7b043e7a89af44c60dd06d5bd01261bd0eda1630775b68921d449f51`.
- All nine measured processes completed `34/34` cases with zero failure or timeout and `2,925`
  rows. All 306 observations matched outcome, row count, response bytes, digest, fixture
  distribution, hit provenance, accessed graph IDs, and catalog ordering. Goal-base work fell from
  `109,198,717` to `57,706,192` units in every pair; current main measured `58,071,626` units.
- The exact v2.4.7 goal comparator passed every pair and every resource check:

  | Pair | Order | Base P50/P95/max | Candidate P50/P95/max | P95 speedup |
  | ---: | :--- | :--- | :--- | ---: |
  | 1 | base-candidate | 236.987/376.099/453.467 ms | 1.509/20.956/380.040 ms | 17.95x |
  | 2 | candidate-base | 235.087/443.578/1468.366 ms | 1.478/20.431/392.894 ms | 21.71x |
  | 3 | base-candidate | 238.797/381.866/412.874 ms | 1.869/23.445/402.104 ms | 16.29x |

  Worst required wrapped-shape speedup was `17.61x`; worst order-median speedup was `17.12x`.
- The isolated dense hypothesis passed against current main. First four-property dense latency/work
  was `19.170/18.731/18.883 ms` and `681` units on main versus
  `20.956/20.431/19.206 ms` and `681` units on the candidate. Repeated class/name/caller/callee
  projections were `0.860/0.791/0.549/0.442 ms` in pair one and exactly `200` units, matching
  main's `0.855/0.845/0.559/0.412 ms` and `200` units. Wrapped dense retained `665` units, while
  broad-all reused the prefix at `200` units. Every eligible dense case accessed only source zero;
  explicit storage access telemetry reported one lookup even on cache hits.
- The current-main aggregate P95 improved in all pairs
  (`47.968/53.476/101.526 -> 20.956/20.431/23.445 ms`), but the exact non-regression gate still
  failed. Cold four-property zero/max regressed
  `245.407/246.422/247.600 -> 380.040/392.894/402.104 ms`; first targeted regressed
  `7.902/7.817/7.751 -> 11.098/9.069/9.847 ms`; DISTINCT zero repeated a material regression in
  two pairs (`3.099/3.195/3.202 -> 4.261/4.257/4.028 ms`); localized-early regressed
  `1.327/1.191/1.228 -> 2.802/2.316/2.324 ms`. These paths are outside the new raw-leading
  projection and remain separate hypotheses.
- Goal-base CPU fell `10.258/10.313/10.379 -> 1.229/1.227/1.275 s`; current-main CPU was
  `1.546/1.564/1.679 s`. Goal peak heap/RSS fell from
  `4.65/4.64/4.65 GiB` and `5.73/5.72/5.75 GiB` to
  `4.16/4.38/4.38 GiB` and `4.64/4.87/4.87 GiB`. Against current main, candidate peak heap and
  RSS also stayed below every paired base. Normalized allocation was approximately `4.81 GB` on
  the candidate, below both baselines. All correctness, access, CPU, heap, RSS, allocation, and
  worker-policy checks passed; only the listed aligned latency checks failed current-main policy.
- Goal report/status SHA-256 is
  `0f655e79d1ab85982b45260b1f7f794612adb3bd8a140e1edc9bfe615fefb884` /
  `81e1228f315c377d1a1e2feaf4aa83198bb1cbff41429f1d6b17949170ef7ef8`; current-main
  report/status SHA-256 is
  `0619b8cf331cecccb463bad083505a59fbba4823b9249d8367e4b439cd908df9` /
  `c0ec40abdb6145145ac9d54717a50db4e5173013e638ba91a2904effac23fca8`.
  Goal-base JSON/TSV pairs are
  `a026a058c18fc9c4e8d24fe61022406daeaa8e37f48f79e3942738f0c3c903d1` /
  `dff258913dd45150ae987ef3c01ac88c8c253b89758ee4a07ba679ee02f06b86`,
  `185832e0f627f4a503a4e25908f9a52df25417d09ba01c8c1969a1d73f481baa` /
  `7cbfd6a0ce7940f82d42148faa46b276195182c2e514db4095278eeb1dd37d61`, and
  `2fc2d922c5f16d2a398b442d982a6c45c6a150eeb60d001b8ac3537d500bf4b8` /
  `c444c33087b9f024334e8a0a59ab89dff9b599d2e7632a0630d62fce73c93075`. Current-main JSON/TSV
  pairs are `1bef10b47c7daeaedfb57c75b139554bf46a9aa21882e6ee7d80fe18447d3609` /
  `fdd0b0b07f3aee334e5252df34b22203c9d0c98be8deb8c0cb1cb2dfeb3437e2`,
  `8c08663879348083ad992e6bb3b11a2e97d9aed58f332ddd357922c94f974829` /
  `0ee4180718b1e51de7a1543a214061f20e2eeec0d9d9060fd4c761073600c2e5`, and
  `0a013694e06f33f61449a7a07300a280249fa1c01a27029ce38e6c8e59d52e4c` /
  `8d6ac1726195deeda5cd629c884b90befa5b3faf179af6f7ec76ad17aa9394e3`. Candidate JSON/TSV pairs
  are `a8d103c7cf8f87454a1a75cd47f38abc7416ce1cbaab2cdaea9e97d644632807` /
  `f3df01e39bfcfd5cc7beef6f696b64790a6535bc92c4e2daaecd7a3f230af924`,
  `53ce40bd567b571647496589e0bf20f57ae065479d385e5e8fe339d097aaa43f` /
  `e1658ef665f869250d0704c5817f9c993355819bf65f286902be15603e8e0733`, and
  `6ecfdf48df673606844401dc6af78533241e3493d923ea52cf874e8eb1685dea` /
  `ed01b1969a6147f38753b491986037e20f7ad1712dca239a519e642773234ec6`.
- Focused execution and storage tests passed `63/63` and `151/151`; core, Cypher, and webgraph
  compilation plus detekt passed, as did `:webgraph:jmhJar` and `git diff --check`. Tests cover
  scope and feature eligibility, authoritative partial fallback, result/source order, aliases,
  duplicate projections, null values, provenance, consumer-error identity, cache key/capacity/
  immutability/clear lifecycle, heap-index priority, explicit access telemetry, and proof that the
  primitive path does not initialize the mapped index. An independent read-only review found no
  correctness blocker.

**Conclusion:** keep as the dense-path building block, but do not call the overall candidate
mergeable yet. This attempt independently restores current main's primitive leading projection and
bounded reuse while preserving Attempt 147's global-wide reductions and Attempt 148's scoped path;
it passes the exact v2.4.7 `10x` gate. The next commits must separately remove the measured cold
mmap zero/max, first-targeted, DISTINCT-empty, and localized-early regressions, then rerun the full
three-pair dual-baseline gate.

### 2026-09-05 - Attempt 150: Parallelize cold mapped graph suffixes with two workers

**Hypothesis:** Attempt 149's remaining cold four-property zero/max regression comes from opening
and scanning 63 mapped graph suffixes serially after the primitive source-zero probe. For eligible
unscoped, non-`DISTINCT` CallSite queries, finish source zero synchronously and process only the
cold mapped suffix with two long-lived graph workers. Bound claimed-but-unmerged work to two
sources and merge strictly in catalog order so global `LIMIT`, duplicate rows, provenance,
cancellation, and exception identity stay unchanged. Keep each graph's storage work serial to
avoid nesting graph and segment worker pools.

**Evidence:**

- Goal base is exact v2.4.7 `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`; non-regression base is
  current main/v2.4.8 `4e328b0109e13c896b74004823fb049fcb19251a`; the measured temporary
  candidate is `2f88ddf6ce5df2ab4a31dbf4df82ef2f514e85dd` on predecessor
  `fb5ac7fc6a2e609bf2807303425430bf4748fe2c`. Its complete production, test, and harness diff
  SHA-256 is `d3c96e4c950a4b14f6a92b478d7a5a8e4d26981cca55760b911ded17ac76d73c`.
  This record's final commit removes that rejected diff, as required by the experiment convention.
- Goal/current/candidate JMH JAR raw SHA-256 is
  `6ef3810c5fd149a0626969cb030c948f735d86a31600f704e53f17b94e9ec620` /
  `285adc87fba44fc271194d164020ec7c1e62eba746c232071dd4848209190322` /
  `3ebe7c304f90c30ab90bb3a33a9aa240002dda0547c8f2ebc4ec1f2f48de0baf`; canonical ZIP content
  SHA-256 is `6662b668d61e887671a5b2f6db85df23900f15cb875a5f73e0095b3c11aa7b25` /
  `16b68822c8296c3a164d8e7a52b5c1b83ee4604586ca832cf4ebd3690461544a` /
  `92921d9ed6fae82bd428419029c4906367e01455b9201b53a3c9569ba9470e9e`. All three used the
  byte-identical candidate-reviewed harness
  `3d372b591857da9c05e6cb9b046ed2003e3bff80da51fdb2e3c234f40309ad8f`.
- The exact command shape was
  `java -jar <jar> io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries
  -p graphCount=64 -p coverageFamily=global-wide -p indexState=cold -p timeoutMillis=300000
  -wi 0 -i 1 -f 1 -to 30m -foe true -prof gc -rf json -rff <result.json> -jvmArgs
  "-Xmx8g -XX:ActiveProcessorCount=4 -Dgraphite.broad.pressure.graphs=<manifest>
  -Dgraphite.broad.pressure.correctness.<record-or-verify> ..."`. The host was Apple M3 Max/macOS
  14.3 with JDK 17.0.18, four exposed CPUs, one single-shot measurement, and no warmup. The first
  diagnostic pair ran candidate, then goal and current bases against a freshly recorded oracle.
- The run used the same 64 distinct real persisted graphs derived from pinned Android, Tika, Hive,
  and Kotlin compiler JARs. Original manifest/provenance SHA-256 is
  `3c019438680a5e95e8ccc335e001c6b6e54b4e5b904871750b5742e3c17037d5` /
  `4a87e194346a32d2b348b79780bfd9a35136365cb5fc7388edc67a957ea0ece9`; the local-path-only
  manifest SHA-256 is `24876ee54a7c5cded5a68b476613ac26e357f3973bb2d54a2ac56f5ea54bbd81`.
  The independently recorded oracle and all three correctness outputs have SHA-256
  `ca62e20e7b043e7a89af44c60dd06d5bd01261bd0eda1630775b68921d449f51`.
- All three measured processes completed `34/34` cases with zero failure or timeout and `2,925`
  rows. All 102 observations matched outcome, row count, response bytes, digest, fixture
  distribution, hit provenance, accessed graph IDs, and catalog order. Goal/current/candidate work
  was `109,198,717 / 58,071,626 / 57,706,192` units.
- Aggregate goal-base to candidate P50/P95/max was
  `240.881/354.750/488.445 -> 1.874/19.639/370.634 ms`, so this diagnostic pair retained an
  `18.06x` v2.4.7 P95 speedup and stayed below the goal-base max. Against current main, aggregate
  P50/P95 improved `1.776/48.070 -> 1.874/19.639 ms`, but the exact cold four-property zero/max
  case regressed `252.836 -> 370.634 ms` (`46.6%`, `117.799 ms`) at identical `57,642,093` work
  units and 64 accessed graphs. That is far outside the current-main `15%` and `1 ms` aligned-case
  limit. First targeted also regressed `7.805 -> 10.137 ms`; dense stayed neutral at
  `18.664 -> 19.639 ms` and identical `681` work units. DISTINCT zero was
  `3.027 -> 3.249 ms`, while localized-early remained materially slower at
  `1.350 -> 2.638 ms`.
- Goal/current/candidate CPU was `10.565/1.595/1.327 s`; peak used heap was
  `4.620/4.745/4.503 GB`; peak RSS was `5.759/5.303/4.997 GB`; normalized allocation was
  `5.220/4.841/4.810 GB`. The candidate used exactly two configured graph workers, zero segment
  workers, and observed a peak of two active graph workers on four exposed CPUs. Correctness,
  work, CPU, heap, RSS, allocation, and worker-policy evidence passed; latency against current main
  did not.
- Goal/current/candidate JSON/TSV SHA-256 is
  `b90325e381c9e7fa6e20f2def97fbead3d35796ed9b7059d0ade43b6e7973ee9` /
  `ec37b984029fa23e5684215192dad50adfe408711e25be54b6a69f6d0290402d`,
  `9f25be8dfac33293d72a9360eb5ac4887d161a399b4415800cb6ebee56c672af` /
  `06979432332d4acbf86987552ff2906c925f85d46dd0c81a0303c09ff79dd675`, and
  `e52e6195c717d5a8deed5d2177c5fbdfad1ff7cf9c54db4372e9362f6e2550e7` /
  `16bcb01d9ed01f0ad0a191afc5e0b23b835524472d23b028b8b3288c57cd0392`.
  Focused execution/storage tests passed `70/70` and `152/152`; core, Cypher, and webgraph
  compilation and detekt passed, as did the rebuilt JMH JAR and `git diff --check`. Tests cover
  eligibility and fallback, bounded speculation, strict source order, limits, provenance,
  heap/mmap/raw priority, cancellation, interruption, and exact error identity.

**Conclusion:** reject and revert. Two outer graph workers reduce Attempt 149's roughly `393 ms`
cold zero/max result to `370.634 ms`, but leave half the four-CPU budget idle and remain much slower
than current main's `252.836 ms`. The first diagnostic pair already violates the hard aligned-case
gate by `117.799 ms`; two more repetitions cannot justify retaining this implementation, so they
were intentionally not run. Test a four-worker outer-only suffix as a separate hypothesis; do not
carry this attempt's production code forward implicitly.

### 2026-09-05 - Attempt 151: Use the full four-CPU budget for cold mapped graph suffixes

**Hypothesis:** Attempt 150 reduced cold suffix latency with two outer workers but left half of the
four-CPU benchmark budget idle. Keep the same source-zero-first, serial-per-graph, catalog-ordered
algorithm, but permit up to four suffix workers, bounded by available processors and the existing
`graphite.cypher.directStringParallelism` setting. With no nested segment work, four independent
mapped graph views should remove the cold zero/max regression without changing logical work.

**Evidence:**

- Goal base is exact v2.4.7 `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`; non-regression base is
  current main/v2.4.8 `4e328b0109e13c896b74004823fb049fcb19251a`; the measured candidate
  production tree is temporary commit `f76e53de330bdc36604579ba4a42686c9214d3bb` on Attempt 150
  record commit `9e4f80e3`. The final commit containing this record has the same production tree.
  Its full-index production/test/harness diff SHA-256 is
  `634027f56df844e0871cb07926c44aa82645cb91916af99706ec0fd2db1ef187`; the regular verification
  clone produced the identical diff hash.
- Goal/current/candidate JMH JAR raw SHA-256 is
  `0175abd230a14b278221d4ad5a818f383000e9a8a651e052c262e74880c5cc3a` /
  `4fff6a1bfa004a915d54c93b907530796285b865077f65080abf8d7c177dc259` /
  `65056a3e224a71592cb2ee05d3e146965c717c85095f34af72e074a66160a965`; canonical ZIP content
  SHA-256 is `f1a9d6712240adbbb31821ad63fab3fd46ee5f67f50213876119bf505560cfb9` /
  `4c6362804163fe5ba6dda30c2c3af523349028c1c769cb5f22d39ed99ee52c18` /
  `60b687057c5238eb2886bf8c588c3a12c7ecfdf412162dccf296353de9f6fef7`. All three used the
  byte-identical candidate-reviewed harness
  `ccc8aac6c6b286bc9420bd190b515c7791ce74077987a2d8bb4ef79a6a51c780`. Review found that its
  initial fallback telemetry ignored an explicit worker property; the measured JARs contain the
  corrected reflection call and report the actual configured count.
- The exact command shape was
  `java -jar <jar> io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries
  -p graphCount=64 -p coverageFamily=global-wide -p indexState=cold -p timeoutMillis=300000
  -wi 0 -i 1 -f 1 -to 30m -foe true -prof gc -v SILENT -rf json -rff <result.json> -jvmArgs
  "-Xmx8g -XX:ActiveProcessorCount=4 -Dgraphite.broad.pressure.graphs=<manifest>
  -Dgraphite.broad.pressure.correctness.mode=verify
  -Dgraphite.broad.pressure.correctness.oracle=<oracle>
  -Dgraphite.broad.pressure.output=<correctness>
  -Dgraphite.broad.pressure.observations.output=<observations.tsv>"`. Three fresh paired forks ran
  candidate/goal/current, goal/current/candidate, and candidate/goal/current. The host was Apple M3
  Max/macOS 14.3 with JDK 17.0.18, four exposed CPUs, one fork, one single-shot measurement, and no
  warmup.
- All runs used the same 64 distinct real persisted graphs derived from pinned Android, Tika, Hive,
  and Kotlin compiler JARs. Original manifest/provenance SHA-256 is
  `3c019438680a5e95e8ccc335e001c6b6e54b4e5b904871750b5742e3c17037d5` /
  `4a87e194346a32d2b348b79780bfd9a35136365cb5fc7388edc67a957ea0ece9`; the local-path-only
  manifest SHA-256 is `24876ee54a7c5cded5a68b476613ac26e357f3973bb2d54a2ac56f5ea54bbd81`.
  The independent v2.4.7 oracle and all nine correctness outputs have SHA-256
  `ca62e20e7b043e7a89af44c60dd06d5bd01261bd0eda1630775b68921d449f51`.
- All nine measured processes completed `34/34` cases with zero failure or timeout and `2,925`
  rows. All 306 observations matched outcome, row count, response bytes, digest, fixture
  distribution, hit provenance, accessed graph IDs, and catalog order. Goal/current/candidate work
  was exactly `109,198,717 / 58,071,626 / 57,706,192` units in every pair.
- The unmodified v2.4.7 goal comparator passed with no error:

  | Pair | Order | Goal P50/P95/max | Candidate P50/P95/max | P95 speedup | Wrapped minimum |
  | ---: | :--- | :--- | :--- | ---: | ---: |
  | 1 | candidate-base | 239.093/410.427/432.035 ms | 1.591/20.535/185.691 ms | 19.99x | 26.25x |
  | 2 | base-candidate | 253.288/398.009/409.987 ms | 1.808/19.191/158.488 ms | 20.74x | 22.97x |
  | 3 | candidate-base | 235.681/402.065/431.833 ms | 1.471/21.613/167.085 ms | 18.60x | 19.98x |

  Every independent pair exceeds the required `10x` P95 target, every wrapped family exceeds it,
  and candidate max stays below its paired goal max.
- The isolated zero/max hypothesis passed against current main. Cold four-property zero latency was
  `235.154/258.897/244.472 -> 185.691/158.488/167.085 ms`, with identical `57,642,093` work units
  and all 64 graphs accessed on both revisions. Candidate aggregate P95 also improved
  `39.145/50.746/40.821 -> 20.535/19.191/21.613 ms`; candidate max stayed below current max in all
  three pairs. Dense stayed neutral or improved at identical `681` work units, and DISTINCT zero
  stayed neutral at identical `99` work units.
- The full current-main comparator remains red only on three separate paths that this attempt did
  not accelerate. Four-property targeted was
  `7.933/8.061/8.238 -> 10.130/10.106/10.125 ms`; class-pair targeted was
  `3.914/3.916/4.239 -> 5.302/6.002/5.326 ms`; localized-early was
  `1.196/1.246/1.185 -> 2.224/2.442/2.261 ms`. Each crosses both the 15% and 1 ms threshold in all
  three pairs. Their work and accessed-graph counts are identical to current main, isolating fixed
  projection/path overhead rather than extra logical scanning.
- Goal/current/candidate process CPU was
  `10.570/1.467/1.427`, `10.464/1.640/1.371`, and `10.439/1.504/1.371 s`. Candidate peak used heap
  was `4.715/4.714/4.710 GB` and peak RSS was `5.402/5.236/5.249 GB`; every paired goal and current
  CPU/heap/RSS resource check passed. Candidate normalized allocation was
  `4.810/4.810/4.810 GB/op`, below both baselines in every pair. Run-level telemetry reported
  `graphWorkerCount=4`, `graphScanPeakActiveWorkers=4`, `segmentWorkerCount=0`, and
  `segmentScanPeakActiveWorkers=0` in every candidate fork.
- Goal/current comparator report/status SHA-256 is
  `80c83565a75d5f48e82f0f0e6d139dfdb6a43003846d93c7532dbb2541f3fd24` /
  `b2ea528af8ed4450281a68dc6d902b722926a56c908e30b89fb0ba1428c8178c` and
  `8537faa92d24dd38d9f43fe2c16e981ee91416fc88e95e54924ccd37dd316fb1` /
  `6cca2c08cd4c3111270b446360b453403ef71a99c676f8c0fea9aa899e915357`. Combined dual-baseline
  report/status SHA-256 is
  `9f805b66baba38a814765b95eca53e45c036804096d5560b37d437e08d50e9f3` /
  `11ccd9443f7ac9f58d4615dd34646b4e437f40543f66ebde01c3be3e980657ac`.
- Goal JSON/TSV SHA-256 is
  `5933720949fde3bcd4df07364db5767ed80cdcae5777bd033bfc554d0dddd28f` /
  `09b2b9eb4e44a4453765d5cbfa48b28bd0d5804a8f55d6a50efda700fe2722e1`,
  `a5588956d6737b435222499692fe3b562193a3943dc850d1c925d2a5a2eb5aae` /
  `b1b4b729919de0f45e73bf5784db14e526b1139430f058673ad6e0b550516053`, and
  `3a6234e9a55dafe976ab8009728147e6cd5334fab2b08a64e14ae5e1c197c5c6` /
  `de92637a957bfa89a4ac88a3e6e861daf76c12dfbf4f30f130a24e5ff45437ce`.
  Current JSON/TSV SHA-256 is
  `7f132eb02c376a4735cf55fb95d353d9c03f860176942801529473b769a50f58` /
  `2d515823013e949c135b7f87f33dd7276168588bf51910a459526dc0f9b9d8e3`,
  `c241257f6b1292e123a26f9f65a6b3147346f01fb589126cfd5a4bbb69361b45` /
  `39ec334f88514858c7f821c2ce384a0f4a9ea768027265426af20aa405a997de`, and
  `2fc9025b3fd7c864ec565fb722e304359a5ebd29bc1c43f8ed003bf869339c1e` /
  `b321d075b9ba67deb16effc82808f8de80464721a624c9eda975cd0841e37e0f`.
  Candidate JSON/TSV SHA-256 is
  `30819939790cab2b60704c2702ac6694052430102e93b1e028aaa6c08404328c` /
  `081028232e10abd2e98ea814920bd2507b67e5645e7f2b8694dabad8a12d8fa7`,
  `81c785cd9b7953bdd8e6a593de46b2c9119dd1285766ec4de7316bb34b4c3457` /
  `d8afb5e49c771b31fb05013e83bb7cadb13da58a1a045a09701ccecc54770706`, and
  `13497f6717b6c48136a9e327821b48b0023b80bf586ab5829174940f7f0d3565` /
  `f579180b966f17dc2f2594efc7972422caee0da08a4e4fa61b5828f3c8376c62`.
- Focused execution/storage tests passed `70/70` and `152/152`; core, Cypher, and webgraph
  compilation and detekt passed, as did the rebuilt JMH JAR and `git diff --check`. Tests cover
  one-to-four CPU/config caps, invalid/oversized settings, exact four-worker bounded speculation,
  serial storage, order, limit, fallback, cancellation, interruption, and exact error identity. An
  independent read-only review found no production blocker and confirmed that the worker
  `ThreadLocal` prevents nested fanout.

**Conclusion:** keep as the cold zero/max building block. Four outer workers close Attempt 149's
largest current-main regression while preserving the v2.4.7 rollback and an `18.60x` worst-pair
P95 speedup. Do not call the overall branch mergeable yet: independently remove the three repeated
targeted/localized aligned regressions, then rerun the complete three-pair dual-baseline gate.

### 2026-09-05 - Attempt 152: Reuse the mapped suffix workers after cold initialization

**Hypothesis:** Attempt 151 initializes every suffix graph's mapped string-index view during the
cold zero case, but later targeted cases return to the legacy wave barrier and short raw scans.
Expose a read-only warm-view capability and reuse the same four-worker, serial-per-graph,
catalog-ordered suffix runner only when every suffix source proves that its mapped view is already
warm and supports all predicates. Mixed warm/cold, heap-index, unsupported predicate, scoped,
`DISTINCT`, and general-expression requests must keep their existing authoritative paths.

**Evidence:**

- Goal base is exact v2.4.7 `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`; non-regression base is
  current main/v2.4.8 `4e328b0109e13c896b74004823fb049fcb19251a`; the measured candidate is
  temporary commit `9df94f2c8953cd7ae2a4771eb58195b286011f0a` on retained Attempt 151
  `33121e71`. The final attempt commit has the same production and harness bytes plus the
  review-requested hit/order test. Its full-index production/test/harness diff SHA-256 is
  `3309b3c027d4e12847e5f0ab66d5d2a84be5c5543de46cb015ee0184d1232d66`. Stable content
  identities are graph capability
  `391b51e072c217b697921493ee117237d53087a104b645b87763ad55a0377afd`, query integration
  `ce722f2322f691f358fa527ce2042e20870d54cf9161538fc464231ed93838f4`, storage implementation
  `93fae93718ccfb5973b6410b12a9c67ad678dc084617e4409e592fc0b53f2887`, query tests
  `c2410d734607a910b7d66f7a513678f3dea48f5081273b40f8aacbb4e10c8de9`, and storage tests
  `e83658ed846ba46a44a0ae18fe72daa241b152806e037e2a422ed8651356f32d`.
- Goal/current/candidate JMH JAR raw SHA-256 is
  `0175abd230a14b278221d4ad5a818f383000e9a8a651e052c262e74880c5cc3a` /
  `4fff6a1bfa004a915d54c93b907530796285b865077f65080abf8d7c177dc259` /
  `eabade7b79e63d8d9ff8c424e17875d7869caffffd259cccea8a91624ba7ed4d`; canonical ZIP content
  SHA-256 is `f1a9d6712240adbbb31821ad63fab3fd46ee5f67f50213876119bf505560cfb9` /
  `4c6362804163fe5ba6dda30c2c3af523349028c1c769cb5f22d39ed99ee52c18` /
  `d311723ad86f87102dffd21291ecf1c2331da6237080e536e053155ae7d22ab9`. All three used the
  byte-identical candidate-reviewed harness
  `ccc8aac6c6b286bc9420bd190b515c7791ce74077987a2d8bb4ef79a6a51c780`.
- The exact command shape remained
  `java -jar <jar> io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries
  -p graphCount=64 -p coverageFamily=global-wide -p indexState=cold -p timeoutMillis=300000
  -wi 0 -i 1 -f 1 -to 30m -foe true -prof gc -v SILENT -rf json -rff <result.json> -jvmArgs
  "-Xmx8g -XX:ActiveProcessorCount=4 -Dgraphite.broad.pressure.graphs=<manifest>
  -Dgraphite.broad.pressure.correctness.mode=verify
  -Dgraphite.broad.pressure.correctness.oracle=<oracle>
  -Dgraphite.broad.pressure.output=<correctness>
  -Dgraphite.broad.pressure.observations.output=<observations.tsv>"`. Three fresh paired forks ran
  candidate/goal/current, goal/current/candidate, and candidate/goal/current. The host was Apple M3
  Max/macOS 14.3 with JDK 17.0.18, four exposed CPUs, one fork, one single-shot measurement, and no
  warmup.
- All runs used the same 64 distinct real persisted graphs derived from pinned Android, Tika, Hive,
  and Kotlin compiler JARs. Original manifest/provenance SHA-256 is
  `3c019438680a5e95e8ccc335e001c6b6e54b4e5b904871750b5742e3c17037d5` /
  `4a87e194346a32d2b348b79780bfd9a35136365cb5fc7388edc67a957ea0ece9`; the local-path-only
  manifest SHA-256 is `24876ee54a7c5cded5a68b476613ac26e357f3973bb2d54a2ac56f5ea54bbd81`.
  The independent v2.4.7 oracle and all nine correctness outputs have SHA-256
  `ca62e20e7b043e7a89af44c60dd06d5bd01261bd0eda1630775b68921d449f51`.
- All nine measured processes completed `34/34` cases with zero failure or timeout and `2,925`
  rows. All 306 observations matched outcome, row count, response bytes, digest, fixture
  distribution, hit provenance, accessed graph IDs, and catalog order. Goal/current/candidate work
  was exactly `109,198,717 / 58,071,626 / 57,706,213` units in every pair.
- The unmodified v2.4.7 goal comparator passed every correctness, work, latency, and resource rule:

  | Pair | Order | Goal P50/P95/max | Candidate P50/P95/max | P95 speedup | Wrapped minimum |
  | ---: | :--- | :--- | :--- | ---: | ---: |
  | 1 | candidate-base | 237.393/405.961/414.652 ms | 1.613/20.193/160.130 ms | 20.10x | 21.14x |
  | 2 | base-candidate | 237.830/372.342/386.897 ms | 1.577/19.499/162.044 ms | 19.10x | 22.84x |
  | 3 | candidate-base | 236.806/342.467/381.925 ms | 1.776/20.167/163.234 ms | 16.98x | 18.94x |

  Worst individual P95 speedup is `16.98x`, worst wrapped-family speedup is `18.94x`, and worst
  order-median speedup is `18.54x`; all are above the required `10x` threshold.
- The complete current-main non-regression comparator also passed. Aggregate P95 improved
  `37.496/49.058/44.536 -> 20.193/19.499/20.167 ms`; cold four-property zero/max improved
  `235.041/244.786/236.864 -> 160.130/162.044/163.234 ms`. The Attempt 151 targeted failures are
  closed: four-property targeted improved
  `8.365/8.036/8.061 -> 6.347/6.948/7.669 ms`, and class-pair targeted changed
  `4.687/4.332/3.763 -> 3.118/3.100/4.185 ms` with no material sample. Localized-early changed
  `1.184/1.235/1.209 -> 2.232/1.988/2.053 ms`; only pair one exceeded the 1 ms noise floor, by
  `0.048 ms`, so no regression repeated in two forks. All other aligned cases, including dense and
  DISTINCT zero, remained within the gate's 15% plus 1 ms materiality rule.
- Goal/current/candidate process CPU was
  `10.240/1.456/1.418`, `10.115/1.557/1.418`, and `9.837/1.463/1.476 s`. Candidate peak used heap
  was `4.716/4.712/4.716 GB`, peak RSS was `5.250/5.264/5.255 GB`, and normalized allocation was
  `4.812/4.812/4.812 GB/op`; every paired goal and current resource rule passed. Candidate
  telemetry reported `graphWorkerCount=4`, `graphScanPeakActiveWorkers=4`,
  `segmentWorkerCount=0`, and `segmentScanPeakActiveWorkers=0` in every fork.
- Goal report/status SHA-256 is
  `f63adde8eb8acf74956d4a0913d124a0cb71ecd20a750461577f60feb1f506b7` /
  `50096d1504f0e84f88494db07f0c743d91c36e2cb571321fe7697f2e4a6839be`; current report/status is
  `5a92809bec89a29a93e3e6bf6644c4c8c257c37775659298c30c3b67e6241b7e` /
  `a72cbdd6cdba1d12afecc73f6a70d4c4055c3d548fd2c25a1f558c31d7a71499`; combined dual-baseline
  report/status is `697146744fa0af27ec56cc9162f677d3fbf1694032d9a8ed963fa7ea516c2066` /
  `b6eee9f6d687f54dfaaeb15dfa1eda00c5250a4607f3ceddc213641bed4e8c6d`.
- Goal JSON/TSV SHA-256 is
  `12686f1f285f2aa0554c687f347035c87d685b3a874cd6cd9e25d1d20662b84e` /
  `bebe9cd5059450e6da251899326ed49aa2092a6c642e45b1b2cfef98764713c2`,
  `88b7393bb1cd9d5abcc503be7aeea1f2c31a5bedeb49ebe510bf45492b6d9679` /
  `583b9cfadb24025b7b7f9257ea53b05f330666318370beb6d40dee773de6cad7`, and
  `fd64fa4fae6d70726b4aee1a46c3880bf0c055babae255970c96bfcc83cee347` /
  `1d44424923e54a5aa98037f7f71a4ff517075d7cc572f662538246e4f0fea197`. Current JSON/TSV is
  `b06f74c453d3ec79cc932c9d987264d6ece659adc6d5a18dd4c3db840150c98f` /
  `4715aea839d86addc6b33669c0991bd4ad34b8174b7667fdc5443610c03a367a`,
  `826cdcdfd8b492dcf3de2e4e6c72172fc9e9442bd50bd8fefd60b5e6122514f8` /
  `4af4cb5daf4dfe1125b10c2ee81061ef2dd4d05c5218673763837a47724920d9`, and
  `814e54abd0f44817a877475acbfd8a82573807ab5c02669385c3723b8c185241` /
  `bcea1398fa170a5f372fd47994f62a11ca4f03585b83a47baa28c1b8e7480cd7`. Candidate JSON/TSV is
  `e6ca87449437470a05e6358b2b4e663c4e8a6476382c495cd932e5dd1d64bdae` /
  `d0e271b245075d86f5dd9229d017e3904702131c3246f18e8711a72f1e41604f`,
  `222c2576ffadbb0d9f5e3f5d10b5c340b4d1f56a13b263a76e1e01530467aab6` /
  `3ce1163d108857798d3cb575b3f5fcffda384086651e8b28fa5b9a91f1cb70a2`, and
  `c14d24baf29731a50f48fdce11588b6fd7905d34273d04d4e52cce4d2e45a7b7` /
  `85ab0203ad63c7593a66c03da8a2c0905ab0101f460a2845c6401101152f3df8`.
- Focused execution/storage tests passed `72/72` and `152/152`; core, Cypher, and webgraph
  compilation and detekt passed, as did the rebuilt JMH JAR and `git diff --check`. Tests cover
  cold, fully warm, and mixed suffixes; supported and unsupported predicates; heap-index priority;
  strict order, limit, provenance, bounded workers, serial storage, cancellation, interruption,
  budget and failure identity; and mapped-view clear/reload/eager lifecycle. The final added test
  forces a later warm source to enter before the earlier hit and still proves the earlier source's
  row and provenance win. An independent read-only review found no production blocker.

**Conclusion:** keep. This closes the remaining targeted regressions by retaining the mapped views
that the cold scan already paid to initialize, while preserving the four-worker cold-zero fix and
all authoritative fallbacks. The exact three-pair dual-baseline gate is fully green: every v2.4.7
pair exceeds `10x`, and no correctness, access, latency, CPU, heap, RSS, allocation, or worker-policy
regression is material against current main/v2.4.8. This attempt completes the requested rollback
and global-wide P95 target; the branch is ready for final repository verification and PR review.

## 2026-09-05 - Final integration verification: preserve the v2.4.8 core ABI

The post-attempt review found that v2.4.8 had published five additional scheduling contracts even
though the replacement no longer uses its 2+2 runtime. The final integration therefore restores
`SplitGraphWorkBatchConsumer`, `PreferredMappedStringIndexViewGraphWorkBatchConsumer`,
`PreferredPersistedStringIndexGraphWorkBatchConsumer`, `PreparedStringPropertyDisjunctionLookup`,
and `GraphScanParallelismPlan` as compatibility-only declarations. `javap -public` output for those
types, `RetainedStringPropertyDisjunctionLookup`, and `PreferredRawGraphWorkBatchConsumer` is
byte-for-byte identical in public JVM signatures to v2.4.8, and the final core JAR is missing no
class entry present in the v2.4.8 core JAR. A source-level capability test also exercises the plan
factories, type hierarchy, callbacks, and prepared hint. No replacement query or storage code
references the compatibility-only types.

Restoring `GraphScanParallelismPlan` exposed an evidence-only ambiguity: the real64 harness first
found that legacy class and reported the candidate as a 2+2 plan even though measured peaks were
four graph and zero segment workers. The harness now probes the active v2.4.7-based replacement
resolver first and uses the legacy plan only as fallback. This preserves truthful telemetry for all
three revisions: v2.4.7 reports 0+0, v2.4.8 reports 2+2, and the candidate reports 4+0. The corrected
harness SHA-256 is `b2f811b88bd0f92621eb9382b792df288ebedbfc9b25a6c19c709fbecf7bbf34`.

The first post-ABI batch with the superseded harness is retained for audit. Its v2.4.7 goal side
passed, but current-main comparison failed because one candidate cold-zero max was `309.759 ms`
against `241.290 ms`; the other two candidate maxima were `156.086/152.070 ms`. A complete repeat
did not reproduce the outlier and passed both baselines, but that batch still misidentified the
worker plan and is not the final evidence. The authoritative run below rebuilt all three JARs from
the corrected byte-identical harness and reran the complete nine-process protocol rather than
selecting individual forks.

- The exact measured production/test/harness tree is integration commit
  `a8f61c06c76f1007b214c61060dfa0fc226fd0e7` on Attempt 152 `6d5792b3`. The final record-only
  amendment has the same production, tests, and JMH bytes. Full-index integration diff SHA-256 is
  `9f120d87a874c504ce4feead72778952d10ee3cffa7b4ad6ce1349946cc20fd8`; core source/test content
  SHA-256 is `04c5e1534d02b7d8bd960622c64440a0130f0ff1adce5568da248288779b89fd` /
  `5d0d3cea910624bfb761aa15ca2939bbf4371b3cea54b0abd55f1111cd91efa6`.
- Goal/current/candidate JMH JAR raw SHA-256 is
  `414cc0a9bc07ace800ba78ab809d2ce87ae240d126c238ab8a62543a5a0a5877` /
  `08df2003e262dea4fdf8e221f985bf60dca5dfd933661af2fd46eef5a0a49a9d` /
  `e46153fbf2488524170122585dadb4b9de4b82c4237dd6c62a8cfc7b472b497f`; canonical ZIP content
  SHA-256 is `85e94a36da9bd038c5116ec76c8cbe3187de429557af78949d7390c777c3ecde` /
  `35ae857bfe50c92fa44dfe1242245f2dd75017955d3e70e9e9c490e9a6883787` /
  `d7edc3177e80205323c08582a74de934523ecc41cbafc8e0f247a0d45a94fcd1`.
- The command, host, four-CPU/8-GiB limits, 64 persisted graphs, manifest/provenance, oracle, 34
  cases, and alternating pair orders are identical to Attempt 152. All nine processes completed
  with zero failure or timeout and `2,925` rows. All 306 observations and all nine correctness
  outputs matched the same oracle. Goal/current/candidate work was exactly
  `109,198,717 / 58,071,626 / 57,706,213` units in every pair.

  | Pair | Order | Goal P50/P95/max | Candidate P50/P95/max | P95 speedup | Wrapped minimum |
  | ---: | :--- | :--- | :--- | ---: | ---: |
  | 1 | candidate-base | 236.289/411.533/488.447 ms | 1.639/24.440/170.892 ms | 16.84x | 19.99x |
  | 2 | base-candidate | 248.397/386.471/414.972 ms | 1.568/24.926/172.007 ms | 15.50x | 15.50x |
  | 3 | candidate-base | 239.572/436.651/451.233 ms | 1.553/26.245/155.112 ms | 16.64x | 17.19x |

  Worst individual, wrapped-family, and order-median P95 speedup is `15.50x`; every independent
  requirement remains above `10x`.

  | Pair | Current P50/P95/max | Candidate P50/P95/max | P95 change |
  | ---: | :--- | :--- | ---: |
  | 1 | 1.622/40.929/248.750 ms | 1.639/24.440/170.892 ms | -40.29% |
  | 2 | 1.683/40.887/229.202 ms | 1.568/24.926/172.007 ms | -39.04% |
  | 3 | 1.692/43.545/241.242 ms | 1.553/26.245/155.112 ms | -39.73% |

  The current-main comparator passed every aligned correctness, access, latency, work, CPU, heap,
  RSS, allocation, and max-latency rule. Candidate CPU was `1.508/1.486/1.436 s`, peak used heap
  `4.718/4.710/4.710 GB`, peak RSS `5.257/5.241/5.241 GB`, and normalized allocation
  `4.812/4.812/4.812 GB/op`. Every candidate fork reports four configured graph workers, peak four
  active graph workers, zero segment workers, and zero peak segment workers.
- Goal report/status SHA-256 is
  `decffe49ee23d8155053d5b8c6796dd83b2d45e4a68bf8d61a3ea3fe094909d1` /
  `8c359e9123313eaa6df49848dc9e8bf47546c098c7efaf1513345d5141ff387e`; current report/status is
  `2ba563ca394623735cce5c5da3d7cdeb073bdee9149c45c0d20aa4e38c283305` /
  `efb2fa40f37e45bcd4b7fd61dcfef7f13e67154c19dc6f07a4ae9c127f147c06`; combined report/status is
  `bc759275788c39e33f7a5b363c3c489790b6537b8b0241c497910d44162ed7d9` /
  `f3dc8761ae7e993c1f334acbe61145f91a38fdadc5cb25cca71c71bc7d44e427`.
- Goal JSON/TSV SHA-256 is
  `3a8fd887a2fd02457848b69155acce3f13619c33f8d33aeaf253014a0991d55d` /
  `3f478495ba653786a8dc59a81b9dd2f18f49b5e3dec31a97453895aeb025f2fb`,
  `f79927cbdab779f79c52e70dc07b24b33dc2b4cfaf17f4f0ee2425551b3902ce` /
  `06194e77909daf1204ca2dc1173c9c39935686e74f5aa08ab7ec46932b382e6e`, and
  `23c461cd978e37c37b055754157ae17b311915ada0b6763390be7b57523b815b` /
  `7b254a766449bc24e03cf69de2199dfb48d7a69fa358e02bbf353d29c9e284bb`. Current JSON/TSV is
  `baa6c608b4d92c68a95ba75b0ea831edce7f8576a73f862d9c8921f978da205d` /
  `4786fc91aa7f385c31a48a5330fdf1117d9a06980ed35c569e4ca7e040be2d5d`,
  `1cd4aa1b4acd533035f49bdb3bca208f88c75329aec9facdd2fcc05b1f7e6241` /
  `4de0234b71771345ca755cfd744f55ed16d97d19014919c762408b1c9da50ac0`, and
  `f42842559604969e9d1c7381371459fd8f75f0e33e0985103a22f9c9ff953193` /
  `122acc84426b4c3c164f65d10775aafe920d3534d3eca4245662f7d1e47b5852`. Candidate JSON/TSV is
  `693d290002cc7b06f1fc7cfd448ed22548c56cc6f92fea6cdc5208053b76f86a` /
  `7929ad44049522de7bd6c2eccfe11df0472e94b8db850021c0d485191521eb61`,
  `a016267db22e54e86e196eca7fab3e00cbb8b2e66885c881804f6c3dbd77a9de` /
  `9280840c957c00842731f395a0f284bac758c71ce9b2f2570aaba5966b86c582`, and
  `294243b0c4fdf2b38256bd593da095a921048317cfeb1952eabe44e7251a13cc` /
  `89b916e76cf939c7cba59124df02665d0ad00fc20b789d2c5aa891ca5ba372d4`.
- The exact integration tree passed full `test detekt`, focused compatibility tests, both Cypher and
  webgraph JMH builds, the public-signature/class-entry comparison, JavaScript gate tests `115/115`,
  and `git diff --check`.

**Integration conclusion:** keep the compatibility declarations and corrected telemetry. They
remove the published-ABI blocker without reactivating v2.4.8 scheduling. The final real-data run
proves the requested runtime remains above `10x` versus v2.4.7 and non-regressing versus v2.4.8.

### 2026-09-05 - Attempt 153: Restore persisted indexes for single-graph routes

**Hypothesis:** The v2.4.7-based replacement sends a `ParallelGraphWorkBatchConsumer` to each
first graph-scoped lookup. A cold mapped graph therefore rejects the persisted-index path and
rebuilds one raw index per graph. Reuse v2.4.8's compatibility-only
`PreferredPersistedStringIndexGraphWorkBatchConsumer` only when routing has selected exactly one
source; load an existing valid sidecar without invoking the builder, and preserve the existing raw
fallback for a missing, corrupt, or budget-denied sidecar. K2/K8/K64 and unscoped global queries
remain unchanged in this attempt.

**Evidence:**

- Attempt base is exact retained integration `1d47778441986378a5c08fe2ab950f91483e80d3`;
  current-main reference is v2.4.8 `4e328b0109e13c896b74004823fb049fcb19251a`; measured candidate is
  temporary commit `18420a6014cb7ac2dcfba3a3b1d1bae69cee3c37`. The final record amendment has
  identical production, test, gate, workflow, and JMH bytes. The non-document attempt diff SHA-256
  is `62fde863ff24668efdada500beb96913dd3285ad9df1d5a6075f41606d34621d`.
- The fixture is the same 64 distinct real persisted Android/Tika/Hive/Kotlin compiler graph shards
  used by the exact gate. Local manifest/provenance SHA-256 is
  `809c8b8ceed25428af65350edfa2c391f449051bf069b12555df245099f26ae2` /
  `3b10a4a619498122df1bc0718130be25b55bd063b7c2bd79a4af079137c891cc`.
  The command was `LargeBroadQueryPressureBenchmark.replayBroadQueries -p graphCount=64
  -p coverageFamily=graph-routing -p indexState=cold -wi 0 -i 1 -f 1 -prof gc`, with
  `-Xmx8g -XX:ActiveProcessorCount=4`, JDK 17.0.18 on Apple M3 Max/macOS 14.3. Base and candidate
  used the byte-identical current harness and fresh JVMs; the reported pair ran base then candidate.
- Base/candidate JMH JAR raw SHA-256 is
  `c4489df64fdd43a740c36ef2bec59c86386e2baf4bd5f537312948cd024a1e0e` /
  `e383ce20fecb7a9e22dc5a9c51410db69ecfb18426c4fe22a1eb60a24b5c4c72`; canonical ZIP content
  SHA-256 is `d7edc3177e80205323c08582a74de934523ecc41cbafc8e0f247a0d45a94fcd1` /
  `bfc35b2f0b5339896ad4c3ec7c8454c741eb87c8cf8583890d0fd09f894fbbad`.
- Both revisions completed all `1,137/1,137` queries with zero failure/timeout and byte-identical
  correctness manifests (SHA-256
  `35fc69539c3080dbb801cca4ec7f1e7541f3ccd190d8774861f6109f7c58b6dd`). Access and catalog
  order matched the oracle. Candidate lifecycle changed from 64 parallel raw index builds and
  1,979 retained lookups to zero raw builds and 2,043 persisted-index lookups over all 64 graphs.

  | Revision | Graph-id P50/P95 | Zero P95 | Targeted P95 | Dense P95 | First K64 |
  | :--- | :--- | ---: | ---: | ---: | ---: |
  | Attempt base | 0.093/36.939 ms | 37.536 ms | 42.133 ms | 2.336 ms | 406.149 ms |
  | Candidate | 0.089/24.051 ms | 30.548 ms | 0.405 ms | 2.296 ms | 446.834 ms |

  The candidate cuts graph-id P95 by `34.9%` and removes the 64 raw builds. K2/K8/K64 P95 is
  `0.201/0.642/1.497 ms`, versus `0.275/1.137/2.063 ms` for the attempt base.
- Base/candidate wall time was `6.073/3.623 s`, process CPU `10.199/6.106 s`, peak used heap
  `5.911/5.783 GB`, peak RSS `7.487/6.919 GB`, and allocation `15.639/15.043 GB/op`. Focused
  Cypher execution tests passed, all 153 `GraphStoreTest` cases passed, JavaScript gate tests passed
  `90/90`, the JMH JAR rebuilt, and `git diff --check` passed.
- A same-host comparison against current main still exposes an order-stable residual: two main
  runs have graph-id P95 `17.990/17.410 ms`, while two candidate runs have `28.607/24.982 ms`.
  Current main restores all sidecars during the leading wide request-selected K64 zero case; this
  single-source attempt instead pays 64 first-lookup restore costs inside the later width-one set.

**Conclusion:** keep as a verified narrow building block, but not as the final graph-routing fix.
It eliminates the much larger raw-index rebuild and improves latency, CPU, heap, RSS, and
allocation without changing correctness. A separate attempt must restore sidecars during only the
wide scoped path, with bounded outer concurrency and serial storage fallback, while proving K2/K8
and unscoped global behavior unchanged.

### 2026-09-05 - Attempt 154: Restore sidecars during wide scoped routing

**Hypothesis:** Attempt 153 still pays 64 first-lookup restores after the leading request-selected
K64 zero case. For scoped source sets at the existing wide-query threshold (`>=40`), restore valid
sidecars on the existing four-worker ordered runner. Combine the persisted and serial-mapped marker
protocols so corrupt, missing, or budget-denied sidecars cannot create nested storage workers.
K2/K8 and unscoped global paths remain outside the new branch.

**Evidence:**

- Attempt base is `87a7be02ce617864d179493f74292f50f27bc484`; measured candidate is temporary
  commit `013a0043` with non-document diff SHA-256
  `b8871d77a25487260a6b06cd16afe5c7852b517e5ded936be85fcaf23e182b3e`. The same real64
  fixture, oracle, JDK 17, four-CPU/8-GiB cold graph-routing command, and byte-identical harness from
  Attempt 153 were used. Candidate JMH JAR raw/canonical SHA-256 is
  `ae913dd68cb353c4864339aa37df4b3bf5c39356ec6ed9a8ef077fda504e6eda` /
  `e0376db411be689932bced261e684d40c555f76e9ddf2c107b6cfc3c82f9264d`.
- The candidate completed `1,137/1,137` queries with zero failure/timeout and the exact oracle
  SHA-256 `35fc69539c3080dbb801cca4ec7f1e7541f3ccd190d8774861f6109f7c58b6dd`.
  It restored all 64 sidecars with zero raw scan, 2,043 lookups, four peak graph workers, and zero
  storage scan workers. Focused Cypher tests covered wide zero/dense ordering and bounded
  speculation plus K2/K8 exclusion; WebGraph tests covered valid/corrupt/missing sidecars and
  serialized fallback.

  | Revision | Graph-id P50/P95 | First K64 | K2/K8/K64 P95 |
  | :--- | :--- | ---: | :--- |
  | Attempt 153 | 0.089/24.051 ms | 446.834 ms | 0.201/0.642/1.497 ms |
  | Candidate | 0.085/1.588 ms | 596.973 ms | 0.176/0.516/1.189 ms |
  | Current main | 0.086/17.864 ms | 273.308 ms | 0.224/0.892/1.747 ms |

  Width-one P95 improves `15.1x` versus Attempt 153 and `11.25x` versus the paired current-main
  sample. However the current-main cold gate limits the first K64 sample to `523.308 ms`
  (`base + 250 ms`), and the candidate takes `596.973 ms`, so the comparator correctly fails.
- Candidate wall/CPU was `2.214/6.378 s`, peak used heap `5.914 GB`, peak RSS `6.280 GB`, and
  allocation `13.805 GB/op`; none is worse than the paired current-main resource sample
  (`3.095/6.124 s`, `5.788/7.009 GB`, `15.383 GB/op`) by a material gate margin. Resource safety
  does not override the blocking first-query latency failure.

**Conclusion:** reject and revert. Eagerly retaining all 64 full indexes makes later width-one
queries extremely fast, but moves too much work into the first K64 request and violates the exact
current-main cold guard. Keep only this record; do not retain the production or test change. The
next attempt should reuse the already-open mapped views rather than materializing every full index.

### 2026-09-05 - Attempt 155: Reuse warm mapped views for single-graph routes

**Hypothesis:** Attempt 154 proved that restoring every full index before returning the leading K64
request moves too much latency into that request. The same request has already opened and validated
the persisted mapped view for every graph. For a later route that selects exactly one graph, prefer
that warm view and skip full-index restoration; retain Attempt 153's persisted-only fallback when
the view is not already open. This should remove both raw rebuilds and per-graph restore spikes
without adding another executor or reintroducing v2.4.8's segment pool.

**Evidence:**

- Attempt base is rejected-record commit `203324541fce9bb3a766afaa8967e2f36e1e8b86`; current-main
  reference is v2.4.8 `4e328b0109e13c896b74004823fb049fcb19251a`; measured candidate is exact
  temporary commit `80d6117fec6ef51d1b231cb74fca16aa65811232`. The final record amendment has
  identical production, test, gate, workflow, and JMH bytes. The non-document diff SHA-256 is
  `0101103c3f5d29c9f0bda2be5694b04e8a825ef4b054294297ef1346c0b47d00`.
- The production change is confined to the already graph-scoped single-source branch. It asks a
  `WarmMappedStringPropertyDisjunctionLookup` whether all current CallSite predicates are supported
  and already open, then passes the serial mapped-view marker instead of the persisted-index marker.
  Unsupported predicates, cold views, multiple selected graphs, unscoped queries, and graphs
  without the capability retain their prior paths. The benchmark adds an explicit open-mapped-view
  counter, and the comparator rejects partial or mixed mapped lifecycles.
- The real fixture is the same 64 distinct persisted Android, Tika, Hive, and Kotlin compiler
  shards used by the exact gate; manifest SHA-256 is
  `809c8b8ceed25428af65350edfa2c391f449051bf069b12555df245099f26ae2`. The command was
  `LargeBroadQueryPressureBenchmark.replayBroadQueries -p graphCount=64
  -p coverageFamily=graph-routing -p indexState=<cold|warm|startup-prepared> -wi 0 -i 1 -f 1
  -prof gc`, with `-Xmx8g -XX:ActiveProcessorCount=4`, JDK 17.0.18 on Apple M3 Max/macOS 14.3.
  Current main and candidate used the byte-identical candidate harness, whose source SHA-256 is
  `30d755738d74e91826cee07ec064029bdbc9723c33fd8d189458fa563e991efc`.
- Current-main/candidate JMH JAR raw SHA-256 is
  `e44332f002b754291b259875b2eac7dc11cc58d9aa1b3bfe5cd02239580e9b7a` /
  `af20f8d2505ec3f988695a342f3ed313bace3ad3663233ce8ba34d99fa1bb8ad`; canonical ZIP content
  SHA-256 is `a37a2e3ce17a6cb93a1871cfe703d1d6f99347faf4a396eb6f41ed63bb0f4f76` /
  `8f6798bf3f6a814d54ddf7ad655f6d519a132ded5ced3bdf1e38f062ccc29f65`.
- Every state completed all `1,137/1,137` queries with zero failure or timeout. All six independent
  correctness manifests are byte-identical at SHA-256
  `35fc69539c3080dbb801cca4ec7f1e7541f3ccd190d8774861f6109f7c58b6dd`, and access, source
  pruning, row order, provenance, response size, and digest match the oracle. Cold and warm end with
  exactly 64 open mapped views, zero retained/admitted/trigram indexes, zero raw scans, and 1,920
  mapped lookups distributed exactly 30 per graph. The 1,920 total is 576 graph-id, 192 graph
  parameter, 768 graph-id-set, and 384 request-selected-set lookups; dense set projections do not
  add an index lookup. Startup-prepared remains the unchanged 64-retained-index lifecycle with
  2,043 lookups distributed 30..39.

  | State | Current main overall P50/P95 | Candidate overall P50/P95 | Main/candidate graph-id P95 | Result |
  | :--- | :--- | :--- | :--- | :--- |
  | cold | 0.060/13.479 ms | 0.165/1.979 ms | 18.172/2.156 ms | fail |
  | warm | 0.024/0.077 ms | 0.120/1.910 ms | 0.083/2.010 ms | fail |
  | startup-prepared | 0.063/1.025 ms | 0.061/1.202 ms | 1.203/1.382 ms | pass |

  The exact cold first K64 request is `255.501 -> 419.290 ms`, within the comparator's
  `base + 250 ms` cap and far below Attempt 154's `596.973 ms`. Three preceding candidate-only
  probes measured `716.0/412.7/424.4 ms`; the first was a non-repeating page-cache outlier, but it
  is retained here rather than discarded.
- The mapped lifecycle is not yet a current-main non-regression. Cold K8 graph-set P50/P95 changes
  `0.071/0.618 -> 0.366/0.722 ms`, and K64 changes
  `0.100/1.471 -> 0.391/1.853 ms`; both fail the material guard. Warm K64 changes
  `0.053/0.082 -> 0.299/1.546 ms` and fails. Warm overall process CPU changes
  `2.065 -> 2.770 s`, although candidate peak heap/RSS is lower at `4.853/5.657 GiB` versus
  `5.581/6.502 GiB`. Cold candidate wall/CPU is `2.480/5.307 s`, peak heap/RSS
  `4.812/5.547 GiB`, and allocation `13.758 GiB/op`; startup candidate wall/CPU is
  `1.727/4.112 s`, peak heap/RSS `5.429/5.844 GiB`, and allocation `12.857 GiB/op`.
- The complete `CrossGraphCypherExecutorTest` passed `77/77`, all benchmark JavaScript tests passed
  `117/117`, the JMH JAR rebuilt, and `git diff --check` passed. The focused Cypher test proves the
  cold persisted marker and already-warm mapped marker are mutually exclusive for the real
  unlabeled query shape.

**Conclusion:** retain only as the next experiment's narrow base, not as a mergeable performance
result. It closes the single-graph restore spike without new threads and preserves exact results,
but repeated exact-string resolution in the mapped view creates material cold set-width and warm
latency/CPU regressions. The next independent attempt must bound and reuse mapped-view query state;
if that does not close all three graph-routing states, revert this production change rather than
weakening the comparator.

### 2026-09-05 - Attempt 156: Cache exact string IDs in mapped views

**Hypothesis:** Attempt 155 repeats trigram lookup and exact string comparison for the same
`(transform, mode, expected)` predicates during the graph-routing replay. Add a lazy, view-lifetime
LRU of exact matching string IDs, bounded to 32 entries and 256 KiB per mapped view, and publish an
entry only after the lookup and buffered work accounting complete successfully. Reusing these IDs
should remove enough repeated mapped work to close the cold set-width and warm latency guards
without creating a new executor or using the legacy CallSite scan pool.

**Evidence:**

- Attempt base is retained building-block commit
  `85b2b4f49f6e307ad34b8dcf781ebaca1da2fd98`; current-main reference is v2.4.8
  `4e328b0109e13c896b74004823fb049fcb19251a`; the exact measured candidate is temporary commit
  `b75d9203b0694196b987e45bc05baf8c5fe0315a`. The measured production/test diff SHA-256 is
  `68cf024c6c2bef8162e48f678d3ce6b3d045351960a28c334b0e7fc90916076b`. The final Attempt 156
  commit reverts that diff and retains only this record.
- The authoritative candidate used the same real 64-graph fixture, oracle, byte-identical harness,
  JDK 17, four-CPU/8-GiB limit, and fresh-process `cold`, `warm`, and `startup-prepared` commands as
  Attempt 155. Candidate JMH JAR SHA-256 is
  `567420c903037348fcc58a0b44493c4854b2745ea168075012e0cc73601bac71`. Every state completed
  `1,137/1,137` queries with zero failure or timeout; all candidate and current-main correctness
  manifests are byte-identical at SHA-256
  `35fc69539c3080dbb801cca4ec7f1e7541f3ccd190d8774861f6109f7c58b6dd`.
- Cold and warm retain exactly 64 mapped views, zero full-index admissions, and 1,920 mapped
  lookups distributed exactly 30 per graph. Both report zero intra-graph scans, zero scanned
  graphs, and zero peak `graphite-callsite-scan-*` workers. Startup remains the unchanged
  full-index lifecycle with 64 retained indexes, 2,043 lookups, and no mapped view. This isolates
  the result from either removed `graphite-callsite-segment-*` scheduling or legacy scan fallback.

  | State | Current main overall P50/P95 | Candidate overall P50/P95 | Candidate graph-id / parameter P95 | Result |
  | :--- | :--- | :--- | :--- | :--- |
  | cold | 0.060/13.479 ms | 0.114/1.725 ms | 2.359/1.329 ms | fail |
  | warm | 0.024/0.077 ms | 0.043/1.380 ms | 1.510/1.390 ms | fail |
  | startup-prepared | 0.063/1.025 ms | 0.057/1.175 ms | 1.408/0.080 ms | pass |

  The cold first K64 request improves from Attempt 155's `419.290 ms` to `366.557 ms` and stays
  below the current-main `255.501 + 250 ms` limit. The blocking cold residual is instead K64
  graph-set P50/P95: current main `0.100/1.471 ms`, candidate `0.527/1.725 ms`. K2 changes
  `0.054/0.208 -> 0.130/0.497 ms`, and K8 changes
  `0.071/0.618 -> 0.190/0.595 ms`; those two remain inside the material absolute allowances.
  Warm K2/K8/K64 is respectively `0.039/0.398`, `0.057/0.399`, and `0.173/0.341 ms`, but the
  stricter overall graph-id and graph-parameter guards still fail: their P95 changes from roughly
  `0.083/0.064 ms` to `1.510/1.390 ms`.
- Versus Attempt 155, exact-ID reuse reduces cold work only
  `109,278,906 -> 108,544,147` and warm work only
  `51,636,912 -> 50,660,958` units. Candidate cold wall/CPU is `2.304/5.524 s`, peak used
  heap/RSS `4.828/5.549 GiB`, and allocation `13.768 GiB/op`; warm is `1.582/2.488 s`,
  `4.855/5.683 GiB`, and `23.275 GiB/op`. The warm CPU reduction does not compensate for the
  latency regression. The remaining dominant work is posting-range validation, encounter-order
  merge, node materialization, and projection after exact IDs have already been resolved.
- The focused mapped-view test passed and proved successful, empty, eviction, and consumer-failure
  behavior before the performance run. A subsequent review found two additional reasons not to
  ship the isolated cache: a hit, especially an empty hit, bypasses interruption and minimum work
  charging; and its per-view byte cap is not reserved from the shared mapped-index memory budget,
  while `close()` has no generation guard against a concurrent late publication. No full suite was
  claimed after the authoritative performance gate failed.
- Candidate cold/warm/startup JSON SHA-256 is
  `e06916a4c2e45a3d3442377c273c33e89062d36778f4b48dccc418f9a6644686` /
  `c4328f3285c6fe8c82baac39d7bb86cb7521bb267483c2588637f9efe845af39` /
  `8a85e44d8f79439eec25e9e5aa65d644afbbd2134ac5fcd1f6a576c05ccb2df`; comparator status
  SHA-256 is `81527cccea5bb4e87a6f78feca4c6dde7bdfe4841a18ec064ad3ad62db15ebc6` /
  `77de7c3baee5da8c79a0a9736898d7f647b8195818657db1de7570efab99df90` /
  `7b82b352eeef89bf80ac23935f8e8f4157454a9c8a357cb9aa6778f6a11ce543` in the same state order.

**Conclusion:** reject and revert. Exact string-ID reuse helps a narrow repeated-term component but
does not remove the mapped posting validation and node projection that dominate warm latency, and
it introduces cancellation and memory-accounting semantics that are not justified by the failed
gate. Keep only this record. The next independent attempt should cache a fully validated,
encounter-ordered, limit-aware mapped result prefix under the shared memory budget, with cancellation
and close-generation safety; if the strict warm guard still fails, direct bounded projection must be
measured as a separate hypothesis.

### 2026-09-05 - Attempt 157: Cache complete bounded mapped node prefixes

**Hypothesis:** Attempt 156 showed that exact string-id lookup is not the dominant repeated work.
Cache the complete, encounter-ordered node-id prefix for the exact `(ordered predicates, limit)`
request instead, but only after every posting range and every returned node has been validated.
This should remove repeated posting validation and merge work from graph-scoped routes without
adding a thread pool, weakening work/cancellation semantics, or retaining projected object rows.

**Evidence:**

- Attempt base is rejected-record commit
  `de5228d1704faee71a1f4d105523ab516f96b9cb`; current-main reference is v2.4.8
  `4e328b0109e13c896b74004823fb049fcb19251a`; frozen goal reference is v2.4.7
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b`. The preliminary measured candidate is temporary
  commit `c7ce19432abd0af3a4192753ed8b872141d85502`; the measured production/test/harness diff SHA-256
  is `0bc8a9e3ce8f152bb782291db7783b232c6b574600af5617a3ee220e29ca388c`.
  The exact fully built and measured checkout is
  `1bda8a0123bc662038c77aa419340e21796247f5`; the final record-only amendment leaves its
  production, tests, gate, workflow, and harness bytes unchanged. Its WebGraph JMH JAR
  raw/canonical SHA-256 is
  `b256157c0484947a06d42cdc49e54e659fbed4e9706371fe213f9a9c565aa171` /
  `0cd87dda023685c695f3dd64876c7f1d33ddea61a7b85ef1e0f5f2da2ecf61c3`.
- Each mapped view owns an access-ordered LRU capped at 16 entries and 256 KiB. Only limits up to
  200 are eligible. Keys retain the full ordered predicates and exact limit; values are private
  primitive node-id arrays. A hit checks interruption and charges at least one work unit. A miss
  admits only a complete result: a short result after exhaustion, or an exact-limit prefix before
  yielding its last item so an outer `LIMIT` cannot prevent publication. Partial iteration below
  the limit never admits. Entries reserve the shared mapped-index memory budget, evict by LRU,
  shrink their reservation, clear on close, and cannot publish after a concurrent close.
- The implementation was refined within this one hypothesis rather than selecting a favorable
  run. Caching only the preferred single-source branch produced 128 entries and did not close the
  set-width gate. Applying the same safe lookup to the general mapped branch produced 400 entries
  but still did not cache exact-limit results because the outer pipeline never requested the final
  `hasNext`. Publishing immediately before the verified limit-th yield produced the expected 464
  entries and closed the gate while the partial-consumption test remained non-admitting.
- The routing protocol used the same 64 distinct real Android/Tika/Hive/Kotlin compiler shards,
  correctness oracle, fresh JVMs, JDK 17.0.18, four active processors, and 8 GiB heap as the hosted
  gate. The harness is byte-identical across current main and candidate at SHA-256
  `a4844ba66446ebfc117066fc347985d8494cb08bebc3772b586ce56da3fbc65b`. All six measured
  correctness outputs contain `1,137/1,137` successful queries and have SHA-256
  `35fc69539c3080dbb801cca4ec7f1e7541f3ccd190d8774861f6109f7c58b6dd`.

  | State | Current main overall P50/P95 | Candidate overall P50/P95 | Main/candidate graph-id P95 | Main/candidate parameter P95 | Result |
  | :--- | :--- | :--- | :--- | :--- | :--- |
  | cold | 0.058/16.025 ms | 0.115/1.677 ms | 22.528/2.167 ms | 0.090/0.309 ms | pass |
  | warm | 0.024/0.077 ms | 0.041/0.363 ms | 0.086/0.294 ms | 0.060/0.276 ms | pass |
  | startup-prepared | 0.059/0.993 ms | 0.057/1.182 ms | 1.191/1.419 ms | 0.100/0.081 ms | pass |

  Cold and warm end with 64 mapped views, exactly 1,920 lookups distributed 30..30, zero raw
  scans, zero scan workers, and exactly 464 cache entries / 643,296 bytes. Startup remains the
  unchanged 64-retained-index lifecycle with 2,043 lookups distributed 30..39 and zero cache
  entries/bytes. The first cold K64 request improves `518.295 -> 440.170 ms`; K2/K8/K64 P95 is
  `0.522/0.733/1.487 ms` cold and `0.416/0.446/0.369 ms` warm. All three strict state comparators
  passed this preliminary candidate run without changing their latency or resource tolerances.
- A fresh base/candidate rerun from the exact final Attempt 157 checkout reproduced the cold and
  warm passes, including the exact 464-entry / 643,296-byte mapped-cache lifecycle, but exposed a
  blocking startup-prepared tail result. Graph-id P95 changed `1.109083 -> 1.391917 ms`; the
  comparator permits 15% or 0.25 ms of absolute jitter, so this is 0.033 ms beyond the absolute
  allowance. Startup still had 64 retained indexes, zero mapped views/cache entries, zero scans,
  and otherwise matching correctness and resource behavior. This proves that the mapped-prefix
  change is a valid cold/warm building block but does not close the final merge gate. Exact-final
  cold/warm/startup status SHA-256 is
  `b0e3b902911ad15bacbd2a04e17f8368e86bd3b33d10849c3147518267038a97` /
  `c7f0fb37db3e3aead195afaa63e4b7a7c069a87b950edba5b8708f271b56605a` /
  `d8689633b65be2dcf056f4d59bacf8fe2cfa91d994fc921ccd29859aaa16d50b`.
- Candidate cold/warm/startup wall time is `2.282/1.369/1.694 s`, process CPU
  `5.171/2.341/3.989 s`, peak used heap `5.68/4.86/5.52 GiB`, peak RSS
  `6.41/5.56/5.85 GiB`, and normalized allocation `13.59/22.79/12.97 GiB/op`. The cache itself is
  only `0.61 MiB`; every lifecycle remains inside the existing CPU, heap, RSS, allocation, and
  lookup-distribution rules. Routing report/status SHA-256 is
  `65a9ae982d1eb4ff8f469bcd5087d0add3111d35ae1240ee4a8612ff0cf22896` /
  `7106ad6e0f5407c78af193afeaccebbee591ecd02372e290a40b3296f7f4c5bb` cold,
  `2b1e5430f9d2f0783b62a2fcede6c737dcad02d36c7566d785776f2f637db050` /
  `56cff48aabeba9c3856e29b77e58985f63b485cb307c5eaa8b9ce83b1d7224e8` warm, and
  `508cab0df4369e027b474862ef770a79e32b2ed75a97a4e9df7ef36f3489998a` /
  `14cf07af50245a440d687555762a6aed4450ec18a42290c5d8a3aadbc47e6a3d`
  startup-prepared.
- Because the general mapped path changed, the complete nine-process global-wide protocol was
  rerun rather than reusing Attempt 152 timings. Goal/current/candidate used byte-identical harness
  bytes and the same 34-case oracle SHA-256
  `ca62e20e7b043e7a89af44c60dd06d5bd01261bd0eda1630775b68921d449f51`.

  | Pair | Order | v2.4.7 P50/P95 | Candidate P50/P95 | P95 speedup | Current-main P95 -> candidate P95 |
  | ---: | :--- | :--- | :--- | ---: | :--- |
  | 1 | candidate-base | 239.826/439.283 ms | 1.636/21.248 ms | 20.67x | 53.172 -> 21.248 ms |
  | 2 | base-candidate | 242.052/359.510 ms | 1.536/19.811 ms | 18.15x | 55.494 -> 19.811 ms |
  | 3 | candidate-base | 240.144/383.477 ms | 1.610/19.140 ms | 20.03x | 43.324 -> 19.140 ms |

  Worst individual and order-median P95 speedup is `18.15x`, and worst wrapped-family speedup is
  `20.98x`. Both the frozen v2.4.7 10x gate and every current-main aligned correctness, access,
  latency, work, CPU, heap, RSS, allocation, and worker-policy rule pass. The candidate reports
  four graph workers, zero segment workers, zero CallSite scans, and zero active CallSite scan
  workers. Goal report/status SHA-256 is
  `bc6014ddd9fc3ce31f365256c88d547b35bfd718dd63902c76001bcd95be1e27` /
  `2f864db03da74d8b3b125eece9de7ff2b36e047f2894fe7925a15bcbe63f8c23`; current report/status is
  `55d29b4073f85459750348e91e8ac5cd331568db5ca9e9442510576233e42248` /
  `96fdb2d92fc12bbb24f3b28ae2d29e4cdff1e73e330affe44fa78829623a53ca`.
- The exact Method-17 string shard was also rerun against current main. Its previously hosted
  suffix CPU failure did not reproduce: late/prefix/suffix changed by `+18.1/+7.1/+5.5%` in the
  initial base-first run, and the required candidate-first confirmation reduced the only initial
  failure (late) to `+5.3%`. Correctness text is identical and the final CPU comparator passes;
  report/status SHA-256 is
  `872cb6d9b628240ecba9dadc59e685b18b5d48433460dc8c0c56ce04b0e0a568` /
  `8be1696241296dc0f93e42b62bb5b978a9dcdca0305e1b6ad068984425d7904c`.
- Tests cover full/empty/exact-limit/partial prefixes, ordered predicate and limit isolation,
  consumer exceptions, interruption, budget denial, LRU eviction, clear/release, and a forced
  close-versus-late-publication race over 4,096 real mapped nodes. The comparator now fails with an
  explicit graph-id/request-selected percentile error instead of the former empty error array,
  validates the exact mapped-prefix lifecycle, and reports its entries and bytes.

**Conclusion:** retain as a building block, not as a mergeable final result. The bounded primitive
prefix is the first mapped-view cache that removes the dominant repeated validation/merge work while
preserving correctness, cancellation, accounting, memory, and close semantics. It closes cold and
warm graph routing and improves the already-qualified global-wide result from the prior `15.50x`
floor to `18.15x`, without adding `graphite-callsite-segment-*` or activating the
`graphite-callsite-scan-*` fallback. The exact final checkout still fails startup-prepared graph-id
P95, so the next independent attempt must address the retained heap index's exact-limit admission
gap and re-run every gate before the PR can be marked ready.

### 2026-09-05 - Attempt 158: Admit retained-index exact-limit node prefixes

**Hypothesis:** the retained heap index publishes its bounded node-id cache only after its source
sequence is exhausted. An outer `LIMIT 200` can stop immediately after the 200th element and prevent
that final continuation. Publish a fully verified 200-element prefix immediately before yielding its
last element, matching the safe mapped-view behavior from Attempt 157. Repeated startup-prepared
graph-id queries should then avoid rebuilding the same posting merge and move graph-id P95 safely
inside the current-main guard.

**Evidence:**

- Attempt base is the corrected Attempt 157 record commit
  `0197e53e9df4b5a5ddc2df94438a585610d60207`; current-main reference is v2.4.8
  `4e328b0109e13c896b74004823fb049fcb19251a`; exact measured candidate is
  `ad8ab28468ca0d9c828a91ef71a0754f58f2199f`. The candidate used a fixed primitive array for
  eligible limits, admitted before the exact-limit final yield, preserved no-admission for partial
  consumption, moved cache-hit work callbacks outside the cache monitor, and restored interruption
  checking. Temporary metrics exposed retained node-prefix entry/byte counts.
- The focused `GraphStoreTest` passed. It proved that consuming only one of 200 results did not
  admit a node prefix, consuming the exact 200 through an outer `take(200)` did admit it, the next
  request reused the ordered IDs with the expected work charge, interruption still cancelled a
  cache hit, and closing the graph returned the shared reservation to its starting value.
- The exact startup-prepared protocol used the same 64 distinct real persisted graphs and oracle as
  Attempt 157 (fixture manifest SHA-256
  `809c8b8ceed25428af65350edfa2c391f449051bf069b12555df245099f26ae2`), JDK 17.0.18,
  `-XX:ActiveProcessorCount=4`, and `-Xmx8g`. The command remained
  `LargeBroadQueryPressureBenchmark.replayBroadQueries -p graphCount=64
  -p coverageFamily=graph-routing -p indexState=startup-prepared -wi 0 -i 1 -f 1 -prof gc`.
  Main and candidate used byte-identical temporary harness source at SHA-256
  `ffc5515a12d4bc5b4f32ff4a512b16a1efcf98b26d2eead6832856a1ae24f9c8`.
- Main and both candidate runs completed `1,137/1,137` queries with no failure or timeout. Their
  correctness manifests are byte-identical at SHA-256
  `35fc69539c3080dbb801cca4ec7f1e7541f3ccd190d8774861f6109f7c58b6dd`. Candidate runs retained
  464 node-prefix entries / 476,256 bytes across 64 heap indexes and still reported zero mapped
  views, raw scans, scan workers, or segment workers.

  | Run | Overall P50/P95 | Graph-id P95 | Parameter P95 | Process CPU | Gate margin |
  | :--- | :--- | :--- | :--- | :--- | :--- |
  | current main | 0.058/0.988 ms | 1.147 ms | 0.087 ms | 3.858 s | reference |
  | candidate, candidate-first | 0.058/1.159 ms | 1.358 ms | 0.086 ms | 4.132 s | pass by 0.039 ms |
  | candidate, base-first confirmation | 0.059/1.156 ms | 1.375 ms | 0.081 ms | 4.191 s | pass by 0.022 ms |

  Candidate JMH JSON SHA-256 is
  `4395b5da053fde175695edf62614ffa38cb609dde1785d10b5e0dd44c2612d88` /
  `fb8cf054cf6f6af18dcf0940bbc99769e0e80ae5f170d838d721ab4e5d86132b`; current-main JSON is
  `0a0ca4bdcef158c93c3d2c327a9624469f6351df76e3f1029b871342afa0c1f8`.
- Expanding the percentile identifies the flaw in the hypothesis: rank 548 of 576 is always the
  first `graph-id-property-wrapped-contains-*-dense` lookup for a graph. That query uses the direct
  projection path, whose `projectRows` operation already consumes the complete bounded node
  sequence and admits the cache even before this attempt. Later graph-id/function/parameter forms
  reuse the projection-row cache, so publishing a node prefix one continuation earlier cannot
  remove the first-miss work that determines P95. Relative to Attempt 157's exact candidate sample,
  graph-id P95 changes only `1.392 -> 1.358/1.375 ms` while process CPU changes
  `3.998 -> 4.132/4.191 s`. Passing the absolute guard by only 0.022--0.039 ms is consistent with
  run noise, not a durable causal improvement.

**Conclusion:** reject and revert. Exact-limit admission is valid for node-only consumers, but it
does not affect the projection-first samples that block this PR and does not justify extra retained
state in the goal path. The final Attempt 158 commit retains only this record; production, test, and
JMH changes are removed. The next attempt must reduce the serial retained-index first-miss range
collection or projection cost rather than rely on a later cache hit.

### 2026-09-05 - Attempt 159: Merge broad retained matches by ordered join

**Hypothesis:** Attempt 158 identified the first projection miss, rather than later cache admission,
as the startup-prepared P95 sample. The retained index currently resolves every already-verified
trigram candidate with a separate binary search over the property's sorted string ids. For broad
candidate sets that repeats the same logarithmic traversal hundreds of times. Since trigram
postings and their intersections preserve ascending string-id order, merge the candidate ids with
the property's ascending used ids in one linear pass whenever its conservative work bound is no
worse than binary lookup. Keep binary lookup for narrow sets. This should reduce the first-miss work
without adding threads, scans, retained state, or a cache-only benefit.

**Evidence:**

- Attempt base is rejected-record commit `b4cfa7aa` on top of Attempt 157. The first measured
  production/test candidate was `706d78bc110f6c46536b03cb72e43100523bce4a`; the final
  cancellation refinement polls the existing 1,024-operation interruption cadence inside the new
  merge loop. The exact fully built and measured checkout is
  `7029d848a14e5829a4f722225bbf3d5d216dbe7c`; the final evidence-only amendment leaves its
  production, tests, gates, and harness bytes unchanged. The measured production/test diff
  SHA-256 is `08cdf86de7c7ad47e74bd780042521fbd1e3f6e037cfb0e085c7a2599fa27558`.
  The merge branch is selected only when
  `candidateCount + usedStringCount <= candidateCount * (binaryComparisonBound + 1)`; otherwise the
  existing per-candidate binary lookup remains unchanged. Both arrays are strictly ordered by
  existing construction/invariants, so a two-pointer join preserves posting-range encounter order.
- The focused `GraphStoreTest` constructs 128 sorted persisted values with every second value as a
  known candidate. It verifies the exact ordered node ids, bounds charged graph work by the sum of
  both input widths, and proves an interrupted merge still raises `CancellationException`. The
  focused test passes in the clean build clone.
- The routing protocol is unchanged from Attempt 157: 64 distinct real persisted graphs, the same
  correctness oracle, byte-identical current-main/candidate harness at SHA-256
  `a4844ba66446ebfc117066fc347985d8494cb08bebc3772b586ce56da3fbc65b`, JDK 17.0.18,
  `-XX:ActiveProcessorCount=4`, `-Xmx8g`, fresh JVMs, and 1,137 graph-routing queries. Every selected
  base/candidate correctness manifest is byte-identical at SHA-256
  `35fc69539c3080dbb801cca4ec7f1e7541f3ccd190d8774861f6109f7c58b6dd`; every run has zero failure,
  timeout, raw CallSite scan, or active CallSite scan worker.

  | State / order | Current main overall P50/P95 | Candidate overall P50/P95 | Main/candidate graph-id P95 | Main/candidate parameter P95 | Result |
  | :--- | :--- | :--- | :--- | :--- | :--- |
  | cold, candidate-base | 0.056/13.305 ms | 0.109/1.533 ms | 17.886/2.067 ms | 0.088/0.284 ms | pass |
  | warm, candidate-base | 0.024/0.076 ms | 0.041/0.358 ms | 0.091/0.292 ms | 0.060/0.273 ms | pass |
  | startup, base-candidate | 0.058/1.002 ms | 0.058/0.971 ms | 1.158/1.119 ms | 0.082/0.081 ms | pass |

  Startup's candidate graph work falls from Attempt 157's `12,006,216` to `2,470,864` units
  (`-79.4%`) while process CPU is `3.927 s` versus current main's `4.090 s`; a second candidate run
  reproduces graph-id P95 at `1.124 ms`. The previous exact-final blocker was `1.392 ms`, so the new
  first-miss reduction has a causal margin rather than Attempt 158's cache-neutral noise. The
  startup lifecycle remains 64 retained indexes, 2,043 lookups distributed 30..39, and zero mapped
  views/cache entries. Cold and warm retain zero heap indexes, use 64 mapped views, exactly 1,920
  lookups distributed 30..30, and end at exactly 464 mapped-prefix entries / 643,296 bytes.
- The reversed warm pair confirms a real but sub-jitter absolute tradeoff against v2.4.8's 359 MiB
  retained heap index: graph-id P95 is `0.091 -> 0.292 ms` and parameter P95 is
  `0.060 -> 0.273 ms`, while peak RSS is `6.64 -> 5.63 GiB`. Both are within the frozen 0.25 ms
  absolute regression allowance and the comparator passes; this is recorded explicitly rather than
  presenting the large relative ratios at sub-millisecond scale as neutral. Against the requested
  v2.4.7 goal, the global-wide gate remains the controlling no-regression/speedup comparison.
- Cold/warm/startup report/status SHA-256 is
  `9c6ad89361efd008834fea411953900caa467bbea6b21b007ded5780d8c7c79b` /
  `e9f76c7ace7ce4bc753be4ba8cefdec1d26aba501bb387e85a8ee4abd637f36a`,
  `7728c2b59a0e570dd4923de717f3abcc52d9466522c8483a6cee68ce9bcc61d2` /
  `1f6203d97444e68a1411c4686341a06a8be2d66edcce666dac628e0566a9c12a`, and
  `46de060d089966a0326f279370ce1d8a1ccfdb4e58bc81ed7bf3edf1ada4e60c` /
  `ddf1dfc6b22131bb0a23fcf71eafe753bd6f7955506a56d6666efc2ad7a2f63c`.
- The exact global-wide protocol was rerun against both frozen v2.4.7
  `78ce46b57b2d88ae0f1823432ffefc5c7685bc1b` and current main
  `4e328b0109e13c896b74004823fb049fcb19251a`, with three fresh pairs in
  candidate-base/base-candidate/candidate-base order. All revisions used the same 64 real persisted
  graphs (manifest SHA-256
  `809c8b8ceed25428af65350edfa2c391f449051bf069b12555df245099f26ae2`), byte-identical harness,
  and 34-record oracle SHA-256
  `ca62e20e7b043e7a89af44c60dd06d5bd01261bd0eda1630775b68921d449f51`.

  | Pair | v2.4.7 P50/P95 | Current-main P50/P95 | Candidate P50/P95 | Goal P95 speedup | Current P95 speedup |
  | ---: | :--- | :--- | :--- | ---: | ---: |
  | 1 | 238.118/397.065 ms | 1.669/51.442 ms | 1.707/20.062 ms | 19.79x | 2.56x |
  | 2 | 236.333/430.580 ms | 1.754/44.490 ms | 1.718/24.560 ms | 17.53x | 1.81x |
  | 3 | 239.798/407.036 ms | 1.731/54.241 ms | 1.720/21.259 ms | 19.15x | 2.55x |

  Worst global P95 speedup is `17.53x` and worst wrapped-family P95 speedup is `17.63x`. Candidate
  max latency is `159.311/174.115/158.483 ms`, below current main's
  `244.913/243.679/244.930 ms` in every pair. Candidate process CPU is
  `1.439/1.514/1.442 s`, also below current main's `1.642/1.556/1.566 s`. Both dual-baseline
  comparators pass every correctness, aligned latency, work, CPU, heap, RSS, allocation, and worker
  rule; candidate telemetry remains four graph workers, zero segment workers, and zero CallSite
  scans. Goal report/status SHA-256 is
  `90fa033df3b6e0fc75423abf49b11071b0aab768a7a2e9d510c3fcea03ae6cd0` /
  `aaea46224dd3a07d4bb2c4b38a22c97d064c8176cb6b131fcf82ef083ad19e19`; current-main
  report/status SHA-256 is
  `3a269b0076c6cbc9eadb229933e3238edcc62938129adf34b1ef7ebab26d3cc7` /
  `560b8db0a46f754ca4089116593cf9b27666a29ba0da9c279f01549c39d2a5ec`.
- An earlier manual global-wide sequence omitted the hosted fixture-verifier precondition. Its
  first candidate cold zero-match request faulted persisted pages at `307.771 ms` versus the
  following current-main process at `230.641 ms`, so the current-main comparator correctly failed
  that incomplete protocol. Running the exact verifier used by the workflow before the paired
  forks produced `159.311 ms` versus current main's `244.913 ms` and the complete protocol above.
  The invalid run is retained as a diagnostic, not selected as evidence and not addressed by
  weakening any threshold.
- The exact Method-17 string shard (`late,prefix,suffix`) also passes. Initial wall deltas are
  `+0.5/+2.5/+3.6%`. Initial CPU deltas for late/suffix are `+21.2/+18.3%`, so the workflow-required
  candidate-first confirmation was run; those same keys reproduce at `-15.9/-3.8%`. Initial RSS
  keys above 15% reproduce at `+6.9/-1.8%`. All four correctness outputs are byte-identical at
  SHA-256 `db4504a0ce8ec10fdd576d6d62fa858a4a407e34e3a301ad8b61f06ff0ba60ab`.
  Final shard report/status SHA-256 is
  `b66eafd5d1355a19e74110471da065cfdffe836bc8daa17efc013d81516e8745` /
  `995fedb00fb417a972cfc56c33d1141bb2ac9e496e76343a4062cda7439a9f45`.
- The exact measured checkout passed `./gradlew test detekt :webgraph:jmhJar :explore:jmhJar
  --no-daemon`, all 91 benchmark-gate Node tests, and `git diff --check`. Its WebGraph JMH JAR
  raw/canonical SHA-256 is
  `e6a49f70f8cef6bd79b1fd8fc44c509326cc71829db7da4b021bc989b3ebf4ad` /
  `4f4154c8855a76f626162d4fdf6dbc125b9bb8c800f24e626e30a455c1c271c2`; Explore JMH is
  `460c70946c816b384d3b8fd15cccece6bfb0636192c066b1931a29ae6e0e1a34` /
  `753020c7edd0eb91b8fbe3d6ecd5f5a11f95fd7663839094048345610ec3fd5c`.

**Conclusion:** retain. The startup-prepared blocker is removed twice with a measured 79.4%
reduction in the specific work that caused it, the global-wide P95 floor is `17.53x` against
v2.4.7, and every local dual-baseline, compatibility, correctness, resource, and repository gate
passes. This attempt adds no executor or worker pool: the candidate remains four graph workers,
zero segment workers, and an unused `graphite-callsite-scan-*` fallback. The production bytes are
ready for hosted reproduction; the PR itself must not be marked ready to merge until the final
remote SHA passes required checks and outstanding review threads are resolved.

### 2026-09-05 - Attempt 160: Cache projected rows in mapped CallSite index views

**Hypothesis:** the hosted graph-routing failure is specific to the cold/warm mapped-index
lifecycle. Attempt 157 cached only the matching node-id prefix, so every repeated graph-scoped
projection still reconstructs `CallSiteNode` objects, resolves each requested string property, and
rebuilds the public rows. Let the mapped storage view return and retain the final immutable string
rows for the same bounded projection. This should remove that repeated materialization without
changing the unscoped global-wide executor, introducing another worker pool, or re-enabling raw
CallSite scans.

**Evidence:**

- Attempt base is the retained Attempt 159 commit
  `cd7b13914782e1027ab21f55e22b0f5b0cf86499`; current-main reference is v2.4.8
  `4e328b0109e13c896b74004823fb049fcb19251a`. The exact measured production/test candidate is
  `bb8ba9dfc28696583b95c72055fc06c316b5fe9b`; the following evidence-only amendment leaves its
  production, test, gate, and harness bytes unchanged. Their combined measured diff SHA-256 is
  `0d71b11cdd31a10a85d39d95cf022672250a15a252851981dd1722b722917681`.
- `MappedCallSiteStringIndexView` now resolves requested persisted string ids directly into final
  projected rows and keeps completed results in an access-ordered LRU. The cache is bounded to 16
  entries and 512 KiB per mapped view, reserves bytes from the existing shared mapped-index memory
  budget, charges graph work on hits, polls interruption, never publishes partially consumed or
  failed results, and releases every reservation on clear/close. The cache key includes the query
  and projected property list. `QueryPipeline` selects this storage projection only for a preferred
  graph-scoped mapped source; the multi-source unscoped global-wide branch is unchanged.
- A sizing experiment used the same complete warm workload. Eight entries / 512 KiB retained only
  443 of 464 workload keys and produced `0.143 ms` graph-id P95. Sixteen entries / 512 KiB retained
  all 464 keys and produced `0.095 ms` graph-id P95; 32 entries / 2 MiB retained the same 464 keys
  and produced `0.081 ms` overall P95, within run noise but with four times the theoretical byte allowance. The selected
  16-entry limit is the smallest complete policy. Across the 64 real views its actual footprint is
  464 entries / 7,663,418 bytes, versus a 32 MiB aggregate ceiling; the independent node-prefix
  cache remains 464 entries / 643,296 bytes under its existing 16 MiB ceiling.
- The exact fixture contains 64 distinct real persisted graph shards derived from Android, Tika,
  Hive, and Kotlin compiler graphs. Its manifest SHA-256 is
  `05df7237fb4a8876d4be83f3454135d559c866789d252679b702d76332090d41`; the independent
  single-source oracle SHA-256 is
  `2432752fbf81477508980bb04966b282330d3a2b705690f034ca3c6317b808d6`. Current main and
  candidate ran in fresh JDK 17.0.18 JVMs on the same local Apple host with 16 available processors,
  `-Xmx8g`, and
  `LargeBroadQueryPressureBenchmark.replayBroadQueries -p graphCount=64
  -p coverageFamily=graph-routing -p indexState=<cold|warm|startup-prepared> -wi 0 -i 1 -f 1
  -prof gc`. This is local preflight evidence only; the GitHub workflow remains unchanged on its
  existing Linux x64 runner.
- Every run completed `1,137/1,137` queries, 78,824 rows, with no failure or timeout. All six
  selected base/candidate correctness outputs are byte-identical at SHA-256
  `7a1b9b41bd8220ef823f7a2012958aaec4499d9457e4aeb2286d104de43d9ea7`.

  | State | Current-main overall P50/P95 | Candidate overall P50/P95 | Main/candidate graph-id P95 | Main/candidate parameter P95 | Result |
  | :--- | :--- | :--- | :--- | :--- | :--- |
  | cold | 0.063/13.537 ms | 0.070/1.439 ms | 18.031/1.987 ms | 0.090/0.094 ms | pass |
  | warm | 0.025/0.080 ms | 0.029/0.087 ms | 0.092/0.095 ms | 0.064/0.066 ms | pass |
  | startup-prepared | 0.067/0.966 ms | 0.067/1.050 ms | 1.149/1.290 ms | 0.100/0.101 ms | pass |

  Cold overall P95 improves `9.41x` and the blocking graph-id P95 improves `9.08x`. Warm and
  startup-prepared stay inside the existing 15%/0.25 ms absolute regression policy. K=64 P95 is
  `1.214 -> 1.450 ms` cold, `0.101 -> 0.230 ms` warm, and `1.364 -> 1.410 ms`
  startup-prepared; the cold first K=64 request is `158.164 -> 406.204 ms`, still below its explicit
  408.164 ms page-fault allowance.
- Cold/warm candidate process CPU is `4.912/1.963 s` versus current main's `6.593/2.161 s`; peak
  RSS is `5.56/5.62 GiB` versus `6.51/6.77 GiB`, and normalized allocation is
  `13.48/22.54 GiB/op` versus `14.34/23.81 GiB/op`. Startup candidate/current-main process CPU is
  `4.469/4.637 s`, with `5.88/5.80 GiB` peak RSS. Cold/warm use 64 mapped views, zero retained heap indexes, zero raw scans,
  and exactly 2,043 indexed lookups distributed 30..39 per graph. Startup remains 64 retained
  indexes, zero mapped views/caches/scans, and the same exact lookup distribution. All states report
  four graph workers and zero segment or active CallSite scan workers.
- Cold/warm/startup report/status SHA-256 is
  `3cdebc0ba47df8f7857b9a688af1c4e8c31788ea8b60b31d5d39624fde06f01d` /
  `f7b114e3ecdefc63e14294ddc7dd5cb9dc5bd919d419ef4d757e65aab827c14d`,
  `56a30107b1f894179b081b61436323c21d940df6a16a6a878e3dc6088036ab65` /
  `d2f6ffd1445afb4b9be1d94185d56998831ed6d45e1cc37f767bb96e84797137`, and
  `c81ee0a4f9d638b0b69c14009d93e5beb4fa03443dbd9cd1f59dc127083ce4f3` /
  `66f6e7d136005cd38237af0f5fb1d3f428715769ab731b5f79e672c93821cf9e`.
- Tests verify exact projected values and encounter order, source provenance and global limits,
  cache identity on a hit, work accounting, interruption, consumer exception identity, budget
  denial, LRU bounds, and shared reservation release. The exact code passed `:webgraph:test`,
  `:cypher:test`, all 91 benchmark-gate Node tests, and `git diff --check`. The gate now requires
  the exact mapped projection lifecycle and fails closed on a missing/oversized cache; the four
  comparator integrity hashes were updated to the comparator's exact SHA-256
  `7d0327a123ab5b54b55a25a1804184ed6cdbff912b62fdd1a3c1417fed971b20`.

**Conclusion:** retain for hosted reproduction. This causally removes the old remote
graph-routing mapped-view regression while preserving correctness, bounded memory, cancellation,
work accounting, existing worker topology, and the retained startup path. It does not claim to fix
the independently failing global-wide shapes, method-compatibility CPU sample, or Cypher-capacity
tail sample; those require separate attempts and commits before the PR can be ready to merge.

### 2026-09-05 - Attempt 161: Cap cold scoped graph-set waves on four CPUs

**Hypothesis:** the exact Attempt 160 GitHub runner exposed a cold graph-routing failure that the
local Apple preflight did not reproduce. On the hosted four-vCPU Linux runner the candidate used a
four-source outer wave while current main used two graph workers. The candidate raised effective
CPU use from `2.05` to `2.52` cores but made the first cold K=64 request `277.633 ms` slower. For an
already graph-scoped request only, cap the outer wave to half the exposed processors, up to the
existing four-worker ceiling. On the hosted runner that means two concurrent sources; unscoped
global-wide execution remains at four. If the next exact-head CI run does not remove the first-K=64
failure, reject this scheduling hypothesis rather than using a local timing result to retain it.

**Evidence:**

- Attempt base is exact pushed Attempt 160 `8bee36de3474744d22c931033b219fa62dd3c0a1`;
  current-main reference is v2.4.8 `4e328b0109e13c896b74004823fb049fcb19251a`; the exact
  pre-record candidate is `4214999d961a41ffc5bc36409e0bfc3d061c15b8`. The final record
  amendment changes documentation only; production and test bytes are identical to that candidate.
- The root evidence is GitHub Actions run `33946868294`, job `101255455956`, using 64 real
  persisted Android/Tika/Hive/Kotlin compiler shards on Linux x64 with four exposed processors and
  an 8-GiB heap. Fixture manifest/provenance SHA-256 is
  `3c019438680a5e95e8ccc335e001c6b6e54b4e5b904871750b5742e3c17037d5` /
  `4a87e194346a32d2b348b79780bfd9a35136365cb5fc7388edc67a957ea0ece9`.
  Base and candidate completed all `1,137/1,137` queries with byte-identical correctness SHA-256
  `35fc69539c3080dbb801cca4ec7f1e7541f3ccd190d8774861f6109f7c58b6dd`.
- The hosted first cold request was `505.094 -> 782.728 ms`, beyond the `755.094 ms` hard cap by
  `27.633 ms`. Cold K=64 graph-set P50/P95 was `0.316/2.565 -> 0.707/3.211 ms`.
  Candidate/base peak heap was `4.86/4.96 GiB`, RSS was `5.78/5.98 GiB`, and GC was
  `8/44 ms` versus `11/78 ms`; memory pressure and GC therefore do not explain the latency miss.
  Candidate graph-routing wall time and process CPU still improved overall
  (`7.043 -> 5.136 s`, `14.260 -> 12.690 s`), isolating the failure to the cold scoped latency
  boundary rather than aggregate throughput.
- This attempt changes only the wave-width resolver used after a graphId/request scope has already
  selected multiple cold sources. It preserves the existing executor, serial-per-graph storage,
  source-ordered merge, limit behavior, cancellation, configured worker override, and the separate
  four-worker unscoped cold suffix. It adds no thread or pool. Resolver checks cover one through 64
  exposed processors plus configured caps and invalid values.
- Local verification is deliberately non-performance-only: the exact production/test bytes passed
  `:cypher:test --tests io.johnsonlee.graphite.cypher.CrossGraphCypherExecutorTest`,
  `:cypher:detekt`, and `git diff --check` in a clean normal clone. Candidate latency, CPU, and
  memory evidence is unavailable until the exact pushed SHA runs on GitHub; no local timing is
  substituted for it.

**Conclusion:** submit only for hosted reproduction. This attempt targets the first cold K=64
graph-routing error and makes no claim about the separate cold K=64 steady-state, startup K=8,
global-wide, or Method-17 failures. The production change is retained or reverted solely from the
next exact-head CI result, and the PR remains not ready to merge.

### 2026-09-05 - Attempt 162: Reuse decoded strings within the bounded raw projection

**Hypothesis:** the exact Attempt 161 hosted result proves that the remaining
`global-wide-wrapped-case-insensitive/dense` failure is inside the single leading-graph projection,
not graph fanout. All three candidate forks return the same 200 rows and digest from the first
graph with exactly 665 charged work units, but each projection decodes four front-coded values for
every row even when method/class string ids repeat. Reuse decoded values only within that one
bounded projection invocation. This should remove duplicate decode/allocation cost without changing
the selected node ids, work accounting, query plan, thread topology, or graph-lifetime retained
state.

**Evidence:**

- Attempt base is exact pushed Attempt 161
  `7150c9b494399fed37279146b29c4c49ced90e99`; current-main remains v2.4.8
  `4e328b0109e13c896b74004823fb049fcb19251a`. The production/test candidate before this record is
  `fc73da3972005bf579031c45ba9d424190776c2f`; the final record amendment is documentation only.
  Its production/test commit patch SHA-256 is
  `3fc9823514b0099f0cdfc5e1e1d50cf47c86c9a9a207c8d1dfd903166a999db4`.
- GitHub Actions run `33948326870` is the sole performance input for this attempt. It confirms
  Attempt 161's scoped scheduling hypothesis: graph-routing job `101259278743` passed every
  cold/warm/startup rule. The first cold K=64 request changed from current main's `424.437 ms` to
  `535.691 ms`, inside its `674.437 ms` cap; cold K=64 graph-set P95 was
  `2.800 -> 2.234 ms`, and startup K=8 P95 was `0.877 -> 0.952 ms`. The report/status SHA-256 is
  `1a365ae0a20a8d19e6168017896354d517578896c0d799dc0c767bc878167b31` /
  `2bc2417edc5e6130d8225f2b489ddddc9b0bb2a89f949e3ff90fe2b77dc6d753`; Attempt 161 is therefore
  retained rather than inferred from a local timing result.
- The same hosted run still fails the global-wide job `101259278726`. The frozen-v2.4.7 aggregate
  goal has ample tail margin—worst individual P95 speedup `13.74x`, worst wrapped P95 speedup
  `17.39x`, and worst order-median P95 speedup `14.12x`—but its per-shape non-regression rule rejects
  wrapped dense in all three pairs. Candidate/base aligned latency is
  `4.741/3.348 ms`, `4.220/2.550 ms`, and `3.442/1.982 ms`. Against current main the same row is
  `6.049/3.769 ms`, `7.047/3.190 ms`, and `4.991/4.076 ms`, repeating the material regression in
  two pairs. Every candidate observation has identical row count, digest, first-source provenance,
  and 665 work units, which isolates fixed decode/projection cost rather than missing pruning or
  excess graph work. Global report/status SHA-256 is
  `94c4d73f513f8192675e85efcadf3cb8515b46d06282bf869b8c82a3573cc10f` /
  `9e6aa2ee17837a9c6d08ded1b327f8e844f5e1121915acd069c60979a8c317e7`.
- The new 512-slot direct-mapped decoder is allocated per bounded raw projection. A hit reuses the
  already decoded immutable `String`; a collision merely decodes that id again. Nothing is shared
  across requests or graphs, and the cache cannot affect predicate matching, row order, duplicate
  preservation, cancellation, or memory-budget admission. Both an initial raw scan and a cached
  node-id projection use the same decoder.
- The focused storage regression now projects a repeated `callee_name` across two matched rows and
  proves the decoded value is reused while exact rows, work units, raw match-cache lifecycle, and
  retained-index precedence remain unchanged. In a clean normal clone, the exact production/test
  bytes passed the focused `GraphStoreTest`, full `:webgraph:test`, `:webgraph:detekt`, and
  `git diff --check`. These are correctness/static preflights only; no local latency number is used
  as evidence.
- Method-4 `contains` CPU and Method-17 `or` RSS remain separate hosted failures. This attempt does
  not modify Method execution and deliberately does not combine either follow-up with the
  global-wide root cause.

**Conclusion:** submit this single raw-projection hypothesis to the same hosted dual-baseline gate.
Retain it only if the repeated wrapped-dense error disappears without creating another hard
regression. PR #114 remains not ready to merge, and no merge is authorized.
