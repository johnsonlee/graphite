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
