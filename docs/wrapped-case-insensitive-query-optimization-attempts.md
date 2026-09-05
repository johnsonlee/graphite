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

### 2026-09-05 - Attempt 132: Execute CallSite work on the requesting thread

**Hypothesis:** CallSite graph/segment dispatch, Future allocation, and worker
coordination add overhead to bounded global queries. Execute the existing ordered
work on its caller to remove that overhead while retaining the current index and
projection algorithms. This is one scheduling experiment, based directly on main
`4e328b0109e13c896b74004823fb049fcb19251a`; the rollback and PR #114 experiments
are excluded. The updated user objective explicitly requires removing CallSite
thread pools and reaching a 10x global-query P95 improvement from main.

**Change:** remove the dedicated `graphite-callsite-scan-*` pool and per-size
`graphite-callsite-segment-*` pools. Raw scans, projection, index preparation, and
trigram work invoke their existing ordered tasks directly. CallSite and unlabeled
Cypher graph tasks also run inline. The generic graph pool remains available to
non-CallSite string queries. Public scheduling/capability types remain linkable;
legacy planning hints still select the same mapped/raw/retained storage paths.
Actual raw/index-build invocation counts remain recorded under their legacy metric
names, while background worker metrics are zero. No new index, cache, persisted
format, or query-specific result shortcut is introduced.

**Correctness and static evidence:**

- Cypher: 1,232 tests and detekt pass, including direct caller identity, exact rows,
  encounter order, DISTINCT provenance, graph selection, counts, ORDER BY,
  shared-budget exhaustion, real request cancellation, checked failures, and
  retained non-CallSite Annotation worker tests.
- WebGraph: 182 tests and detekt pass with caller identity, exact projection and
  lookup values, index admission, legacy raw-scan counts, cancellation, and zero
  background worker checks.
- All 115 benchmark logic tests pass. The topology contract now requires the
  caller capability, effective 1+0 plan, and observed zero graph/segment workers;
  routing retains exact raw/index lifecycle and lookup checks. The global-wide
  driver requires 10x rather than 5x. Result, source-access, per-query regression,
  CPU, heap, RSS, and fixture acceptance rules are preserved.
- The combined source snapshot passes `./gradlew check :webgraph:jmhJar --no-daemon`
  in a normal local clone using OpenJDK 17.0.18 on macOS arm64. This includes all
  module tests/lint and the Hive, Kotlin compiler, and Tika
  `LargeCorpusPerformanceGateTest` lifecycle checks. The combined build executes
  46 tasks and restores 29 from Gradle cache; focused changed-module suites also
  execute their test tasks. Restored benchmark logic and JMH compilation pass.

**Hosted outcome:** rejected. Candidate
`1882efe41756583d34ebabf3150b75e91f351ed2` was compared with main
`4e328b0109e13c896b74004823fb049fcb19251a` in benchmark run
[33964500201](https://github.com/johnsonlee/graphite/actions/runs/33964500201).
The workflow ran its pinned persisted real Android/Tika/Hive/Kotlin fixtures on
GitHub Ubuntu runners with Java 17, using the checked-in JMH commands and
base-owned harnesses. The standard benchmark artifacts retain the exact commands,
JVM options, fixture identities, and raw observations. Reverse-order confirmation
reproduced the following failures against main:

| Real-data benchmark | Main | Caller thread | Change |
| --- | ---: | ---: | ---: |
| `denseDistributedMethodContainsCaseInsensitiveDiscovery` | 0.9553 s/op | 2.0711 s/op | +116.79% latency |
| `zeroHitBroadContainsCaseInsensitiveDiscovery` | 0.2355 s/op | 0.4236 s/op | +79.88% latency |
| `broadlyDistributedClassPrefixCaseInsensitiveDiscovery` | 1.0090 s/op | 2.1041 s/op | +108.54% latency |
| `firstLastGraphBimodalClassPrefixCaseInsensitiveDiscovery` | 1.2164 s/op | 2.2271 s/op | +83.09% latency |
| `zeroHitBroadContainsAcrossThirtySixRealGraphs` | 1.8251 s/op | 3.5729 s/op | +95.77% latency |
| Method compatibility, 4 graphs, contains | 1.05 s CPU | 1.28 s CPU | +21.90% CPU |
| Method compatibility, 4 graphs, OR | 1.01 s CPU | 1.49 s CPU | +47.52% CPU |
| Method compatibility, 17 graphs, regex | 2.22 s CPU | 3.40 s CPU | +53.15% CPU |

The capacity gate also failed its 15% CPU bound: normalized process CPU rose from
4.9533 s to 6.3333 s (+27.86%). Wall time rose from 1667.89 ms to 1870.91 ms
(+12.17%), tail from 1.5896 s to 1.7997 s (+13.22%), and RSS after the run from
946,342,571 to 1,014,797,653 bytes (+7.23%). Its concurrency, budget-exhaustion,
cancellation, rejection, and recovery counters remained correct. This capacity
gate has no reverse-order confirmation; the independently confirmed latency and
Method failures already suffice to reject the attempt.

Method-level JMH and the large-corpus end-to-end job passed, but the Method
compatibility CPU checks and wrapped-query latency checks show regressions.
The unit workflow
[33964500204](https://github.com/johnsonlee/graphite/actions/runs/33964500204)
failed the Cypher coverage threshold: 96.902% versus the required 98%. Local
`check` success did not enforce that workflow threshold and must not be reported
as coverage acceptance. No assertion failure was reported by the executed unit
suites.

**Decision:** revert the entire scheduling experiment, including its tests and
benchmark contract changes, retaining this failure record only. Stop the remaining
benchmark work after the reproduced regressions establish rejection. Shared
fixture64 preparation was still running when cancellation was requested, so
64-graph global P95, routing, and 10x evidence are unavailable. No next optimization
is included in the revert. No merge or tag operation is authorized.

**Revert verification follow-up (not another optimization):** exact revert
`6c81f15d9d152abc455cd2ea48c0b705ae673e99` has the same production, tests, and
benchmark controls as starting main. Its five wrapped-query latency shards pass.
The unit build passes, but hosted Cypher coverage is 97.9975% (6215/6342).
An untouched main clone reproduces exactly that value at four available CPUs,
versus 98.0132% (6216/6342) at the local default CPU count. XML comparison isolates
one scheduling-dependent line: releasing the completion latch when a queued fixed
worker is canceled before its runnable starts.

A single deterministic correctness regression now saturates the shared pool,
interrupts another prepared request, and verifies that the request finishes before
the blocking query releases the pool. It checks the interruption cause, no queued
suffix scans, and the blocking query's exact final count and source provenance.
No production code or threshold changes accompany this test. Fresh four-CPU
validation passes 1,232 tests, detekt, and 98.0132% coverage; the adjusted fixture
also passes the focused test and detekt at 20 CPUs. Hosted acceptance is pending.

The revert's Method CPU checks also fail despite equivalent benchmark binaries:
both Explorer JARs contain the same 34,820 ordered entry payloads, including
resources and duplicates, with normalized payload SHA-256
`e6b7ef9fcfeae4eb2cac1b62fc6b8579df2f1a9a3c71e88e2b460eccdc3dd387`.
Fixtures, JVM settings, correctness manifests, and response sizes match. Existing
artifacts do not attribute the CPU difference to a specific runtime component;
these gate failures are neither waived nor evidence of retained production
changes. The separate global-wide rule still demands another 5x against the PR
base, including for a revert identical to main. Clarification of per-attempt
acceptance versus final 10x attainment is pending; the rule remains unchanged.

The unchanged-code hosted revert comparison finished with failure in
[run 33965223437](https://github.com/johnsonlee/graphite/actions/runs/33965223437).
Its three real64 global P95 pairs were:

| Paired fork | Starting main P95 | Revert P95 | Ratio |
| --- | ---: | ---: | ---: |
| 1, candidate then base | 135.968 ms | 127.100 ms | 1.070x |
| 2, base then candidate | 150.105 ms | 124.789 ms | 1.203x |
| 3, candidate then base | 124.562 ms | 215.274 ms | 0.579x |

All three fail the existing 5x requirement. The report additionally flags pair
three CPU (3.45 to 4.19 s) and repeated aligned-row latency differences for five
query shapes; those failures must not be described as target-only failures.
Routing warm and startup-prepared states pass, while cold k64 P95 differs from
2.561 to 5.484 ms and fails its latency guard. Correctness, source-access and
fixture validation report no error in either pressure comparator. These are
restored-main control measurements, not optimization gains. No threshold or
failure has been waived. The test-only coverage repair is held locally pending
resolution of the acceptance contract, rather than restarting a known-unmet
mandatory speedup gate with unchanged production code.


### 2026-09-05 - Attempt 133: Locate selected CallSite tuples through existing postings

**Acceptance clarification:** the user resolved the pending policy question after
Attempt 132. Each iteration must retain correctness and existing performance,
reduce global P95 against the last accepted iteration, and pass all exact-head CI
checks before another optimization starts. A non-improving iteration is rejected.
The final PR must directly reach 10x against starting main
`4e328b0109e13c896b74004823fb049fcb19251a`, remove the CallSite pools, and remain
mergeable; merging is not authorized. The accompanying acceptance plumbing is
verification work, not an optimization or evidence of progress. It retains all
existing numerical regression failures, distinguishes incremental progress from
final target attainment, and requires the final target for a ready-for-review PR.

**Hypothesis:** after selecting DISTINCT tuples, provenance completion need not
repeat predicate-wide discovery and a raw scan of each remaining graph. Resolve
each complete selected tuple through the shortest existing mapped property posting,
then compare all four physical CallSite string IDs to find its first occurrence.
This is one storage lookup change. Scheduling, thread pools, initial result
selection, indexes, persisted formats, and caches remain unchanged.

**Scope and correctness:** the specialization requires the existing preferred
mapped-view consumer and a complete four-property projection. It preserves
repeated/null columns, the original predicate, encounter ordering before LIMIT,
source provenance, budget accounting, and cancellation. Unsupported projections
and invalid posting order retain the original fallback. The complete selected
posting is validated before an early hit. Five persisted-graph test cases cover
these contracts, including recombined strings that must not count as a tuple and
checksum-valid late posting corruption. All 188 WebGraph tests and detekt pass.
Independent code review found no concrete correctness defect. The deterministic
queued-worker regression described above is also retained as verification only.

**Real-data experiment:** baseline is exact starting main `4e328b01`. The candidate
is this attempt's production source; the two file SHA-256 values are
`7674863b090811f24471e7d94a2a0d10cca9a32ebc39140aae7e1c75a0118347`
(`MappedCallSiteStringIndexView.kt`) and
`a2b1db0becebbb7f7c1696cbbe87f0c90fe9e4f7324e0120621c606e24a706c2`
(`MappedWebGraphBackedGraph.kt`). Candidate and baseline pressure harnesses are
byte-identical. Both execute the same independently reverified fixture64 manifest,
with 16 persisted sources each from Android 14, Tika 2.9.2, Hive 4.0.0, and Kotlin
compiler 2.0.21. No synthetic performance data is used.

Command for each fresh JVM, with baseline first recording a 34-row oracle and all
six timed runs verifying against it:

```bash
java -jar "$JMH_JAR" \
  io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries \
  -p graphCount=64 -p coverageFamily=global-wide -p indexState=cold \
  -p timeoutMillis=300000 -wi 0 -i 1 -f 1 -to 30m -foe true -prof gc \
  -rf json -rff "$PREFIX.json" \
  -jvmArgs "-Xmx8g -XX:ActiveProcessorCount=4 \
    -Dgraphite.broad.pressure.graphs=$MANIFEST \
    -Dgraphite.broad.pressure.correctness.mode=verify \
    -Dgraphite.broad.pressure.correctness.oracle=$ORACLE \
    -Dgraphite.broad.pressure.observations.output=$PREFIX.tsv"
```

Environment: macOS arm64, OpenJDK 17.0.18, four JVM-visible processors, 8 GiB heap.
No Gradle or test JVMs run during the six timed processes. Local artifacts, exact
commands, source hashes, JSON, TSV, and comparison reports are retained in
`/private/tmp/graphite-mapped-tuple-evidence.t2461mo1`; the verified manifest is
`/private/tmp/pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv`.

| Paired fork | Main P95 | Candidate P95 | Ratio | CPU main / candidate | Peak heap main / candidate | Peak RSS main / candidate |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1, candidate-base | 72.111 ms | 57.049 ms | 1.2640x | 1.515 / 1.378 s | 4.105 / 3.981 GiB | 4.602 / 4.473 GiB |
| 2, base-candidate | 61.736 ms | 61.360 ms | 1.0061x | 1.408 / 1.515 s | 4.031 / 4.411 GiB | 4.505 / 4.902 GiB |
| 3, candidate-base | 96.511 ms | 63.982 ms | 1.5084x | 1.609 / 1.456 s | 4.071 / 3.985 GiB | 4.543 / 4.464 GiB |

All 34 observations in each of the six forks match the baseline correctness
oracle, including results and source provenance. The global-wide comparator finds
no correctness, access, fixture, repeated aligned-latency, CPU, heap, or RSS
regression. Aggregate P95 decreases in all three pairs, but the second pair's
0.61% improvement is small and does not establish a stable gain on its own.
Wrapped DISTINCT dense source work falls from 283,544 to 30,652 units in each pair;
that counter is mechanism evidence, not a substitute for measured latency.
The non-DISTINCT wrapped ratios are 0.9733x, 0.9692x, and 1.0679x. The 10x target
is unmet, and CallSite pools remain present.

**Combined verification:** `./gradlew check koverLog --no-daemon` also passes
with Java 17 and `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4`. The real Hive,
Kotlin compiler, and Tika build/save/mapped-load/query lifecycle checks pass.
All six reported module line coverages exceed 98%, including Cypher 98.0132%
and WebGraph 98.1435%. These local lifecycle bounds do not replace the hosted
paired method-level and end-to-end regression comparisons. All 179 benchmark
script tests pass; workflow YAML parsing, shell syntax, and diff whitespace checks
pass. The iteration wrapper independently accepts the recorded local measurements
as progress while rejecting final target attainment.

**Decision:** retain only for exact-head hosted validation. Local results satisfy
the incremental global check, not final acceptance. Method-level, full end-to-end,
routing, resource, coverage, and all required CI checks remain mandatory. Do not
start another optimization until this commit is entirely green; revert if it fails.


**Hosted outcome:** rejected. Exact candidate
`2b98f6929893be2cd572a250d884ba1701efb1dc` fails benchmark run
[33969545333](https://github.com/johnsonlee/graphite/actions/runs/33969545333).
The Method compatibility 17-graph position shard reports a process-CPU regression
for `MethodDiscoveryCompatibilityBenchmark.methodScenarioGate[graphCount=17,scenario=zero]`:

| Measurement order | Main process CPU | Candidate process CPU | Change |
| --- | ---: | ---: | ---: |
| Initial baseline then candidate | 1.020 s | 1.450 s | +42.16% |
| Reverse-order confirmation | 0.930 s | 1.110 s | +19.35% |

Both exceed the existing 15% bound. All three scenario correctness manifests
match in initial and confirmation runs, and that shard's wall/RSS checks pass.
The other eleven Method shards, all five wrapped-query latency shards and their
aggregate, the capacity gate, method-level JMH, and large-corpus end-to-end paired
benchmarks pass. Thus the method-level JMH and end-to-end comparisons indicate no
regression, while the separate Method compatibility CPU check rejects the attempt.
Unit run [33969545329](https://github.com/johnsonlee/graphite/actions/runs/33969545329)
passes all checks including coverage. Neither local P95 progress nor these passing
checks override the reproduced CPU failure. No numerical failure is waived or
rerun until green.

**Revert decision:** remove the entire selected-tuple lookup specialization and
its tests. All production sources return to exact starting main. Retain the
user-authorized acceptance plumbing, deterministic queued-worker coverage test,
and chronological evidence as verification work; these are not an accepted
optimization iteration. No further optimization has started.


The remaining hosted global-wide and routing pressure jobs were canceled after
the confirmed CPU failure established rejection. At cancellation, both were still
executing their trusted benchmark steps without a result artifact. Hosted global
P95 and routing evidence for this candidate are therefore unavailable; the local
three-pair measurements above must not be described as hosted acceptance.

**Revert verification:** a fresh, uncached Java 17 / four-CPU run of
`:webgraph:test :webgraph:detekt :webgraph:koverLog --rerun-tasks --no-build-cache`
passes 183 tests with no failures or skips, detekt, and 98.1199% line coverage.
All three reverted files match frozen main byte-for-byte in both the workspace
and verification clone. The exact revert still requires hosted CI; no claim of
an accepted optimization or final target completion is made.

**Exact revert hosted outcome (2026-09-06):** unit run
[33970493936](https://github.com/johnsonlee/graphite/actions/runs/33970493936)
passes, but benchmark run
[33970493937](https://github.com/johnsonlee/graphite/actions/runs/33970493937)
fails at revert HEAD `21c236cf8ded154ffc41978d3ea64ffa172ca57c`.
All 103 production source files and 27 JMH source files match frozen main.
The hosted global-wide provenance also records equal base/candidate JAR content
hashes (`ddfed3136e1c443d1cbd1cc96285de133fb84ba2a6a252b43326f82020250275`).
The failed measurements therefore do not establish a regression caused by the
reverted production change; they also do not establish harmless measurement
noise or justify waiving the checks.

| Revert check | Initial base / candidate | Reverse-order base / candidate | Result |
| --- | ---: | ---: | --- |
| Method 4 / zero process CPU | 0.43 / 0.51 s (+18.60%) | 0.39 / 0.48 s (+23.08%) | Fail |
| Method 17 / count process CPU | 2.12 / 2.62 s (+23.58%) | 1.91 / 2.69 s (+40.84%) | Fail |

The three global-wide case-distribution P95 pairs are 293.377 / 145.108 ms,
153.705 / 146.345 ms, and 129.971 / 378.808 ms. Pair three also fails process
CPU (3.97 / 4.65 s, +17.13%). Wrapped non-DISTINCT targeted query latency fails
the aligned regression bound in all three pairs; four other aligned rows fail
in pairs one and two. All 34 result rows in each pair succeed and match the
reference values, digests, and provenance. Routing pressure passes. Method-level
JMH and large-corpus end-to-end comparisons pass; the separate Method CPU and
global-wide comparisons fail. No strict progress or final 10x result is accepted.
The benchmark run is terminal; no retry-until-green was performed.

**Profiling and coverage follow-up:** the previous 34 queries contain one keyword
per query, repeated across properties. Their percentile is over heterogeneous
query cases, not repeated observations of a fixed query. Independent frozen-main
profiling now includes 24 additional queries: two-keyword AND/OR on single hit
graphs at early/middle/late positions, two-graph and all-64-graph hits, four-keyword
mixed conditions, and a zero-result conjunction whose operands independently
occur in different graphs. Each condition has ordinary and DISTINCT projections.
Full pre-LIMIT hit distributions and returned row provenance are separately
checked against a direct scan of 5,046,935 real CallSites. This is additional
verification work, not a new production optimization or a replacement for the
original comparison contract.

The mixed four-keyword DISTINCT query returns 71 rows from two graphs. Its
unprofiled control latency is 38.375816 s; diagnostic CPU/wall captures take
42.642898 / 40.549959 s. In the CPU capture, 42,516 of 44,322 samples belong to
the request thread, with expression evaluation and string processing dominating.
Top-level GC pause interval unions are only 25.962416 / 54.765126 ms. The actual
diagnostics report one filtered-node-LIMIT execution and zero general fallbacks;
the sampled expression evaluation occurs within that path. These individual
captures neither prove a repeated-query P95 nor identify thread scheduling as
the bottleneck. The full report, raw JFRs, query catalog, independently verified
rows, exact commands, and flamegraphs are retained in
`/private/tmp/graphite-main-profiling-n50joikp/REPORT.md`.

**Pure four-keyword OR follow-up:** append ordinary and DISTINCT projections of
the same four terms combined as `A OR B OR C OR D`. The versioned v2 oracle now
contains 26 queries and preserves the first 24 verbatim. Independent reference
data shows 55 hit graphs, 50,461 matching nodes and 18,915 distinct tuples before
LIMIT. Both projections compile to the existing 16-predicate string disjunction;
the mixed-condition candidate-plan hypothesis does not apply to this pure OR.
The full 26-query control passes values, order, provenance and before/after
persisted graph-content hashes. Cold single-query observations are 34.421916 ms
for ordinary rows and 149.975958 ms for DISTINCT; these are not P95 measurements.

Forty repetitions per projection in each separate CPU/wall diagnostic capture
all match the independent oracle. For cold DISTINCT, 11,183 of 13,830 CPU-mode
samples (80.86%) include `PersistentIndexViewValidator`; 94.24% of sampled
allocation weights have boxed Integer/Long leaf frames. Recorded top-level GC
pauses account for approximately 0.03% of the traced DISTINCT time. The request
thread waits for real computation on the existing scan workers; those samples
do not establish thread-pool scheduling as the main overhead.

A separate index-warm control and CPU/wall captures retain indexes after the
first pair. Excluding that pair leaves 39 observations per projection in each
JVM, all independently verified. Unprofiled medians are 3.018250 / 8.163958 ms;
nearest-rank empirical P95 values are 5.871833 / 11.321750 ms. Index validation
samples disappear, while background JIT activity remains substantial. These
correlated diagnostic samples are not an independent-fork P95 gate or an
optimization result. Cold and warm costs must not be conflated.

The incomplete 24-query repeated baseline was explicitly stopped when the user
requested pure four-keyword OR coverage; four complete forks and a partial fifth
are retained with failed/interrupted status and are not used as a completed P95
baseline. A brief compiler-inspection overlap is also recorded in its limitation
receipt. The final 26-query workload is used for subsequent baseline collection.
The user-readable report, query catalog and standalone flamegraphs are now in
[`docs/profiling/main-wide-query-findings.md`](profiling/main-wide-query-findings.md),
and reproducible tooling is under `.github/scripts/wide-query-profile`.


**Repeated baseline and pure-OR distribution completion:** the v2 workload has
now completed 20 fresh JVM forks, all 520 queries independently verified against
the full-value/order/provenance oracle; persisted graph-content hashes match
before and after. Per-query cold-index empirical P95 is 38.741083 ms for the
55-graph pure four-term OR ordinary projection and 154.172875 ms for DISTINCT.
The mixed-condition DISTINCT P95 is 39,716.441166 ms. These are frozen-main
observations, not candidate improvement or CI acceptance. At 20 observations,
nearest rank selects the 19th value; no stability guarantee is inferred.

The user also requires pure four-term OR to cover both single and multiple hit
graphs. V3 preserves all 26 v2 query texts and expected results, appending ten
queries: ordinary/DISTINCT for single graphs at positions 0/31/63, two graphs at
0+63, and all 64 graphs. Independent full-hit node counts are 704 / 2,646 / 299 /
972 / 2,455,554 respectively. All six pure four-term OR predicates (including the
existing 55-graph predicate) record a positive exclusive contribution for each
keyword. No branch can be removed without losing matched nodes. The all-graph
terms are `get`, `set`, `read`, and `write` across the same four properties.

The complete v3 36-query frozen-main control passes full values, order,
provenance and before/after graph-content hashes. New cases have one observation
each and no P95 claim. V1/v2 contracts remain accepted by the diagnostic verifier;
v3 enforces 18 logical IDs / 36 queries, exact pure-OR query rendering, fixed new
hit distributions and positive exclusive counts. This expands measurement
coverage, not the production optimization or existing CI acceptance contract.
Durable summaries and the updated query list are in `docs/profiling/`; raw
receipts remain under `oracle-v3/` and `control-v3-36/` in the profiling directory.


### 2026-09-06 - Attempt 134: Use indexed candidate supersets for compound OR

**Hypothesis:** frozen main's `(A AND B) OR (C AND D)` cannot compile a direct
string candidate plan and evaluates the complete predicate over all 19,431,891
nodes for DISTINCT. Independent reference data identifies 962 candidates for
`A OR C`, of which 229 nodes satisfy the full predicate and project to 71 tuples.
Recursively union one safe candidate superset from each OR branch, then retain
the original residual predicate, canonical order, DISTINCT provenance, budget
and cancellation handling. If either branch has no safe superset, retain the
original execution. Pure string OR already compiles before this new branch;
this hypothesis does not directly affect the old 34-query workload or pure OR.

**Single direction and necessary compatibility repair:** the only changed
production file is `QueryPipeline.kt`. Enabling lookup on compound conditions
also exposes existing no-op parallel work-consumer lambda incompatibility for
storage implementations that call the original `consume()` method. A private
concrete consumer explicitly implements both individual and batched charges,
preserving its existing parallel capability marker and exact charged units.
This is needed for the newly enabled lookup path to preserve functionality; it
does not remove a pool or change the parallelism policy. An initial test helper
used the batch API, but review rejected bypassing the original callback contract.
The final helper calls `consume()` and the production adapter handles it.

**Base and candidate identity:** frozen comparison main is
`4e328b0109e13c896b74004823fb049fcb19251a`; candidate checkout starts at full revert
`21c236cf8ded154ffc41978d3ea64ffa172ca57c`, whose production and JMH sources match
frozen main before this patch. At measurement time the uncommitted candidate source SHA256 was
`885a36e7bf1c5046cd71b84357f24926ec5d81191d4680abd59f16e659bedbe9`;
its immutable measurement JAR SHA256 is
`8590788daa0e9bb9f4d567016982ffe91771389d8e22ee55184a3d80277433f4`.
The normal verification clone and immutable artifacts are under
`/var/folders/sy/_tdkyl2x0gx6z5kl2wbhd9tc0000gn/T/graphite-attempt134.lwussg_q`.
The compatible final candidate artifacts are in `compatible-v2/`; earlier
`candidate-jmh.jar` and paired results are preserved as superseded diagnostics,
not final-candidate evidence.

**Correctness:** Java 17 candidate `:cypher:test` (1,241 tests), `:cypher:detekt`,
`:cypher:filteredRelationshipMemoryTest`, and `:webgraph:jmhJar` pass. Nine added
tests verify both OR branches, false-positive residuals before LIMIT, overlap
counts/order, each pure-OR term, unsupported numeric/null fallback, Annotation
dynamic properties, cross-graph selected tuple provenance, budget/cancellation,
and individual/batched work callback compatibility. A separate clean frozen-main
clone executes the exact same tests: the six compound tests show missing lookup
coverage; pure OR and the explicit callback test expose the pre-existing no-op
consumer failure; numeric/null fallback passes. These results do not claim that
baseline compound result values or its general cancellation behavior are wrong.
The original baseline-with-batch-helper eight-test receipt is archived separately.

**Real-data verification in progress:** the unchanged persisted fixture64 contains
5,046,935 CallSites across Android 14, Tika 2.9.2, Hive 4.0.0 and Kotlin compiler
2.0.21, each split into 16 graphs. The independent v3 oracle preserves all prior
26 queries and expands to 36 ordinary/DISTINCT queries, including pure four-term
OR with single-graph early/middle/late, two-graph, 55-graph and all-graph hits.
Final-candidate runs are paired candidate-base / base-candidate / candidate-base
using fresh JVMs and identical immutable input files; no build/test/profile
process runs concurrently with timed queries. Per-query cold-index observations
are kept separate from the old 34-query cold-on-replay comparison. Three paired
observations are not reported as a per-query P95.

**Decision:** rejected by the unchanged local regression/progress checks below;
revert this candidate before proceeding to another optimization. No optimization
is accepted, no CI success is claimed, and CallSite pools remain. The old
regression/progress/final-target thresholds have not been changed.


**Final-candidate v3 paired observations:** all 216 executions (36 queries ×
2 revisions × 3 pairs) pass the independent full-row/order/provenance reference;
all six run receipts are complete with matching graph-content endpoint hashes.

| Paired fork | Main mixed rows | Candidate mixed rows | Main mixed DISTINCT | Candidate mixed DISTINCT |
| --- | ---: | ---: | ---: | ---: |
| 1, candidate-base | 548.662 ms | 96.127 ms | 38,856.822 ms | 211.031 ms |
| 2, base-candidate | 639.417 ms | 83.400 ms | 38,254.018 ms | 210.610 ms |
| 3, candidate-base | 648.624 ms | 79.296 ms | 39,249.166 ms | 214.955 ms |

This is approximately 181.6–184.1x on the selected mixed DISTINCT query and
5.7–8.2x on its ordinary projection. These are corresponding individual paired
latencies, not per-query P95 or overall 10x proof. Old 34-query regression/progress
and resource results remain required. The unchanged pure-OR path does not share
this candidate-plan coverage gap. Every exact command and observation is retained
under `compatible-v2/v3-pair-*`; the durable paired latency list is
[`docs/profiling/attempt134-v3-paired-latencies.json`](profiling/attempt134-v3-paired-latencies.json).


**Unchanged old-34 regression gate: rejected.** Six fresh JVMs execute the
original real fixture64 workload against its frozen-main correctness oracle.
The original command remains the Attempt 133 command above, with immutable
final Attempt 134 candidate JAR and new artifact prefixes. Exact commands and
complete JSON/TSV observations are retained in `compatible-v2/old34-pairs/`.
All 204 query results match the oracle; the statistical/resource comparison fails.

| Paired fork | Main old-34 P95 | Candidate old-34 P95 | Process CPU main / candidate | Peak heap main / candidate | Peak RSS main / candidate |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1, candidate-base | 52.132 ms | 101.659 ms | 1.643 / 1.817 s | 4.711 / 4.716 GB | 5.259 / 5.292 GB |
| 2, base-candidate | 73.699 ms | 45.519 ms | 1.654 / 1.502 s | 4.216 / 4.705 GB | 4.770 / 5.259 GB |
| 3, candidate-base | 47.265 ms | 70.090 ms | 1.463 / 1.721 s | 4.308 / 4.714 GB | 4.946 / 5.265 GB |

The aggregate and wrapped DISTINCT dense latencies exceed the unchanged 15% and
1 ms repeated-row bounds in two pairs. Pair three CPU is +17.64%, above the 15%
resource bound. Heap/RSS do not trigger their existing bounds. Strict P95 progress
in every pair and the frozen-main 10x target are both false. The source analysis
already showed that old-34 predicates bypass the new compound OR branch, so these
observations do not independently establish that branch as the cause of the old
query differences; neither do they justify waiving failed checks or accepting a
regression. No rerun-until-green is performed.

The failed local preflight is sufficient to reject the candidate. Candidate
method-level and end-to-end CI performance results are therefore unavailable;
no partial local correctness result is presented as full CI acceptance. The
standard `CypherBenchmark` source currently constructs synthetic data, so its
historical timing must not be used as real-data performance proof under the
repository conventions. The measured acceptance evidence here uses persisted
fixture64 exclusively. Candidate production/test sources and exact build receipts
remain archived with the immutable JAR; the experiment commit preserves the
attempt, followed by a production/test revert. Profiling tools, representative
query coverage, flamegraphs and the failure record remain available.


**Revert:** rejected experiment commit `610fcc6e8d8e57c952f4cbb5ff6485447c54bc13` is followed by an explicit
production/test revert. The complete production and JMH source trees again match
frozen main `4e328b0109e13c896b74004823fb049fcb19251a` byte for byte. The private
callback adapter is reverted together with the compound plan; it is not silently
retained as a second change. The nine candidate-only tests are retained in the
experiment history and archived receipts, not left failing against reverted main.
The 57 profiling/oracle Python tests are also wired into the existing candidate
gate test job; no numerical acceptance threshold is relaxed. Revert CI is pending
at the time of this record; this is not an accepted optimization.


**Revert CI terminal result (head `8be10a5913951d5f2275e287151492da42920b15`):**
unit workflow [33980691186](https://github.com/johnsonlee/graphite/actions/runs/33980691186)
passed; benchmark workflow [33980691202](https://github.com/johnsonlee/graphite/actions/runs/33980691202)
failed. Capacity CPU increased 22.19% and tail latency 15.25%; Method 17 aggregate
order CPU increased 40.8%, with reverse-order confirmation 20.4%. Global-wide
P95 main/candidate was 98.842/129.746, 113.220/137.226 and 110.489/113.691 ms;
all original 34 query results matched in all six runs. The candidate gate tests,
method-level, large-corpus end-to-end, routing and other resource/compatibility
shards passed, but the required aggregate remains failed.

Production and JMH source trees match frozen main. Independent comparison of the
downloaded base/candidate Explorer JARs found identical names and bytes for all
34,820 entries, including duplicate names. Both canonical content hashes are
`a78e19f19dc03d05aa800fa3b8395f002b4363ad2b21d9161a51951a8fa1d2a2`.
This excludes a difference in these JAR contents, but does not isolate runtime
causes or justify accepting the relative failures. No rerun or waiver was used;
no optimization has been accepted. Raw CI artifacts and comparison receipt are
retained at `/private/tmp/graphite-attempt134-revert-ci/`.


### 2026-09-06 - Attempt 135: Check selected tuple feasibility before predicate discovery

**Decision: reject and revert.** This attempt tests one ordering change in
`MappedWebGraphBackedGraph.distinctStringPropertyDisjunction`: defer predicate
string discovery until the existing selected-tuple necessary-condition check
inside the eligible parallel raw projection has succeeded. It adds no tuple
index, posting selection policy or pool change. Small-graph/unbounded-limit
fallbacks retain discovery and empty-candidate checks; a nullable deferred result
retains the original fallback. The lazy result is evaluated on the caller before
worker creation and is cached, including null. Index loading, identity and CRC
validation remain before the early return. Unsupported predicates may incur an
additional feasibility check before fallback; exact work counts are not claimed
identical for all inputs.

**Profiling basis:** three frozen-main dense-query CPU recordings put 94.3–96.6%
of application samples in predicate discovery plus raw DISTINCT projection.
The independently authenticated fixture64 export proves the first 200 selected
`get` tuples are impossible in 62 graphs using existing corresponding-property
membership checks. All four values independently present is only a necessary
condition; same-node checking remains mandatory. See
[the complete 64-graph feasibility evidence](profiling/dense-selected-feasibility/README.md).
This targets redundant candidate discovery, unlike rejected Attempt 133's direct
selected-tuple posting lookup.

**Source and build:** parent `8be10a5913951d5f2275e287151492da42920b15`, frozen
comparison main `4e328b0109e13c896b74004823fb049fcb19251a`. Only one production
file changes. Its SHA256 is
`c26895b75b4fd5abc3af9098380b05ae991568d1e34bcbc2ffe688212f704884`;
immutable candidate JAR SHA256 is
`b3d29878dd13c04578f1947951eed8da23b38862b633994e5c998b09783db496`.
The unchanged trusted main JAR SHA256 is
`a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`.
A fresh normal clone at `/private/tmp/graphite-attempt135.tkrgxwsr/repo` passed
`./gradlew :webgraph:test :webgraph:detekt :webgraph:verifyJmhJarExcludesTests --no-daemon`
with Java 17: all 192 tests, lint and JMH test-exclusion validation passed.
Two preliminary test runs exposed an incorrect builder-ID ordering assumption;
final expected values use independent mapped-node physical traversal, matching
the storage contract. Production code was not changed to accommodate that test.

Nine new correctness/mechanism tests cover corresponding-property rejection for
mapped and retained indexes, independently present but absent combined tuples,
null/duplicate projection columns, empty selection, budgeting/cancellation and
recovery, validation before early return, small/unbounded fallback, nullable
provider fallback and no-selection values/order. They use synthetic persisted
graphs only for correctness and deterministic work assertions, never performance.
An independently verified frozen-main clone passes five compatibility tests and
fails four new early-rejection expectations at eager predicate-discovery work.
The old budget/cancellation/CRC propagation assertions themselves pass; these
failures are not evidence of broken baseline cancellation. Final test SHA256:
`a968b6a98092a2afa6220798da93dd6421ac53d7dee4b7bbb33ebe3fda6e89f9`.
[Build receipt](profiling/attempt135-build-receipt.json) and
[baseline counterexamples](profiling/attempt135-baseline-counterexample.json)
retain exact commands and hashes.

**Real-data validation:** the same 64 persisted Android/Tika/Hive/Kotlin shards,
manifest SHA256 `fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`,
are used without concurrent local builds/tests/profilers. macOS arm64, Java 17,
four JVM-visible CPUs, 8 GiB heap, fresh JVMs. The v3 control completes all 36
queries, including pure four-term OR across single/multiple/all graphs; complete
values, order and source provenance match the independent oracle, and graph
content hashes match before/after. This is one observation per query, not P95.
[Control receipt](profiling/attempt135-v3-control-summary.json).

The prespecified original 34-query comparison uses three alternating pairs,
index cold-on-replay, the unchanged frozen correctness oracle and numeric gates.
All 204 results match. Exact commands are in
`/private/tmp/graphite-attempt135.tkrgxwsr/old34-pairs/*-command.json`;
the command template is the original Attempt 133 invocation above, with this
immutable candidate JAR and new output prefixes. `run-old34-pairs.py` and
`control-v3-command.json` preserve the full commands in the attempt directory.

| Pair | Main / candidate old-34 P95 | Main / candidate process CPU | Main / candidate peak heap | Main / candidate peak RSS |
| --- | ---: | ---: | ---: | ---: |
| 1 candidate-base | 43.854 / 33.787 ms | 1.486 / 1.413 s | 4.707 / 4.403 GB | 5.265 / 4.940 GB |
| 2 base-candidate | 47.670 / 31.796 ms | 1.481 / 1.444 s | 4.710 / 4.706 GB | 5.269 / 5.281 GB |
| 3 candidate-base | 60.723 / 29.107 ms | 1.659 / 1.396 s | 4.423 / 4.320 GB | 5.111 / 4.844 GB |

Dense DISTINCT itself improves 43.854→27.759, 47.670→29.935 and
60.723→29.107 ms; counted work falls 283,544→67,505 in every pair.
However targeted DISTINCT is 21.970→33.787, 20.937→31.796 and
41.593→23.679 ms, exceeding the unchanged 15% and 1 ms bound in two pairs.
Its counted work remains 106,706; equal work does not establish equal runtime
cost or identify the cause of the observed regression. Thus strict aggregate
P95 progress passes (1.30x, 1.50x, 2.09x), but non-regression fails and the final
10x target is unmet. CPU/heap/RSS remain within their existing bounds.
[Complete comparison](profiling/attempt135-old34-global-wide-report.md),
[status](profiling/attempt135-old34-global-wide-status.json) and
[progress decision](profiling/attempt135-old34-local-progress.json).

Per the prespecified plan, no extra v3 timing pairs or candidate hosted
method/end-to-end performance jobs are run after local rejection. Those candidate
comparisons are unavailable, not passing. Existing synthetic CypherBenchmark
timings are not real-data performance proof. The production change and its nine
candidate-only tests are recorded in one attempt commit, then explicitly reverted.
No failure is waived or retried until green. No optimization has been accepted;
CallSite pools remain and the 10x target remains active.


**Explicit revert:** rejected experiment `40e1d401e5d8086464a673cfaedc96cccac4029e` is followed by a production/test revert. Profiling/reference evidence and the failed comparison remain. Revert-head CI is pending; this does not constitute acceptance.


**Revert CI terminal result (`34b45bf798cbe26932e46ab42d6635b534e7fa3e`):**
unit workflow [33982902935](https://github.com/johnsonlee/graphite/actions/runs/33982902935)
passed; required benchmark workflow
[33982902934](https://github.com/johnsonlee/graphite/actions/runs/33982902934) failed.
Method 4-graph count process CPU was 0.560→0.750 s, confirmed in reverse order
0.550→0.780 s. Method 36-graph OR process CPU was 3.600→4.710 s, confirmed
3.580→4.740 s. Capacity, Method 17 aggregate, method-level, large-corpus and
routing checks passed. The global-wide gate failed repeated aligned row bounds;
P95 main/candidate was 121.156/130.316, 133.683/111.038 and 129.179/150.562 ms.
Its recorded base/candidate canonical JAR content hashes are both
`ddfed3136e1c443d1cbd1cc96285de133fb84ba2a6a252b43326f82020250275`.
All production/JMH source files had already been independently verified identical
to frozen main. This does not isolate runtime causes or waive any failures.
No optimization is accepted. Artifacts are retained at
`/private/tmp/graphite-attempt135-revert-ci/`; the full
[global-wide status](profiling/attempt135-revert-ci-global-wide-status.json) is retained.

Subsequent paired profile diagnostics of the rejected candidate found no new
exclusive callback hotspot and did not reproduce the original targeted DISTINCT
latency difference. The main/candidate raw per-node and worker instruction listings
are identical after normalizing constant-pool references and generated lambda
numbers. These diagnostics do not reverse the original rejection; see the
[bounded independent comparison](profiling/attempt135-targeted-diagnostic/independent-targeted-comparison.zh.md).
A separate [Method measurement boundary audit](profiling/method-gate-measurement-boundaries.md)
finds timed reference-result work and whole-JVM CPU accounting. Six subsequent
frozen-main local profiles place reference construction at 31–33% of CPU samples
for 4-count and 41–51% for 36-or; [independent sample audit](profiling/method-gate-samples/independent-method-sample-summary.zh.md).
Input graph files and JAR hashes match before/after capture. This does not
explain the hosted Linux failures or authorize changing the acceptance gate.


### 2026-09-06 - Attempt 136: Remove range and iterator construction from raw DISTINCT OR

**Hypothesis:** the existing `parallelRawDistinctCallSiteStringProjection` node
loop constructs `predicates.indices` and traverses its iterator for each inspected
CallSite. Replace only this traversal with a primitive while loop, retaining
predicate order, first-match short circuit, exact candidate membership and lazy
string match-state semantics. This is a different loop from Attempt 130's bounded
raw-leading projection; no historical speedup is transferred to this attempt.

Three frozen-main dense DISTINCT allocation profiles (`cpu-3/4/5`) contain
`getIndices` leaf weights of 1,572,864 / 1,572,864 / 1,310,720 bytes and
`IntProgression.iterator` leaf weights of 2,621,440 bytes each in this method.
These are sampled TLAB weights, not precise allocations or promised savings.
Existing bytecode uses primitive `nextInt` and inlines `any`; the candidate does
not eliminate index boxing or a predicate-lambda object. The selected tuple
`Integer.valueOf` path remains unchanged. Index preparation, projection,
parallelism, cancellation and work accounting remain at their original positions.

**Inputs and validation plan:** frozen main
`4e328b0109e13c896b74004823fb049fcb19251a` versus candidate parent
`34b45bf798cbe26932e46ab42d6635b534e7fa3e`, using the existing real 64 persisted
class shards of Android 14, Tika 2.9.2, Hive 4 and Kotlin compiler 2.0.21 (not
64 independent applications). Fixture manifest SHA256
`fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`.
The candidate JAR SHA256 is
`8bb7c9a0f507d37b23289209e4c846ad1b2335e508464449deba66ecbf18f433`.
Artifacts and exact commands are retained at
`/private/tmp/graphite-attempt136.zr21l653`.

All 187 webgraph tests, detekt and JMH test-exclusion checks passed. The four new
pure-OR correctness tests also pass frozen main and do not establish performance.
The prespecified sequence is one v3 36-query real correctness control, then three
alternating unchanged old34 pairs; only a nonregressing candidate with strict
P95 progress in every pair proceeds to three v3 pairs and required CI. No
performance acceptance is established yet.


**Measured outcome: reject for insufficient progress.** All 36 real v3 control
queries and all 204 old34 paired observations passed independent correctness
checks. The candidate's graph content hashes match before/after the v3 run.
Base/candidate JAR hashes match before/after all six old34 runs.

| Pair/order | Main P95 → candidate P95 | Process CPU | Peak heap | Peak RSS |
|---|---:|---:|---:|---:|
| 1 candidate/base | 47.074708 → 50.419250 ms | 1.499329 → 1.552886 s | 4.38 → 4.03 GiB | 4.89 → 4.53 GiB |
| 2 base/candidate | 65.733291 → 55.483833 ms | 1.659594 → 1.511979 s | 4.06 → 3.95 GiB | 4.68 → 4.44 GiB |
| 3 candidate/base | 47.527125 → 41.615750 ms | 1.590953 → 1.491688 s | 4.38 → 4.37 GiB | 4.91 → 4.90 GiB |

The unchanged regression-only comparison passes its bounds, but the first pair
has P95 speedup 0.934x. Strict progress in every pair is false, and the 10x target
is false. A non-regression PASS is not an accepted optimization. The aggregate
P95 is still the old34 mix of distinct queries, not a repeated per-query P95.
[Full generated comparison](profiling/attempt136/global-wide-report.md),
[status](profiling/attempt136/global-wide-status.json),
[explicit decision](profiling/attempt136/local-progress.json),
[build receipt](profiling/attempt136/build-receipt.json) and
[independent source review](profiling/attempt136/preimplementation-audit.md).

Per the prespecified plan, stop candidate measurement here; additional v3 timing
pairs and hosted method/end-to-end candidate comparisons are unavailable, not
passing. Record this one-direction attempt, then revert its production loop.
Keep the pure-OR correctness coverage, which independently passes frozen main,
and profiling evidence. No optimization is accepted; CallSite pools and the
unmet 10x objective remain.

Independent [result audit](profiling/attempt136/independent-results-audit.md)
recomputed rank-33 P95 from all six TSVs, matched all 204 ordered 14-field
correctness signatures to the unchanged oracle, and reconciled every resource
metric. Every paired query's work, hit/accessed graph IDs and parallel scan count
match; each replay charges 58,071,626 total work units. Dense DISTINCT is P95
in every run. Targeted DISTINCT also slows in pair 1 (24.229000→38.935916 ms),
then improves in pairs 2/3; it does not trigger the repeated-row regression rule.
The first-pair aggregate failure is sufficient to reject this attempt.

**Explicit production revert:** rejected attempt `bd942197c14d8febd8ea1118d85616444f4e3834` is followed by a
production-only revert. All 130 production/JMH source files now match frozen
main byte-for-byte; [verification](profiling/attempt136/revert-source-verification.json).
The pure-OR correctness tests and diagnostic artifacts remain. Revert-head CI is
pending; no acceptance or goal completion is claimed.

**Further frozen-main phase diagnosis:** three fresh-JVM original-34 recordings
trace the initial DISTINCT projectSource and selected-tuple provenance calls
inside the exact execute windows. All 102 correctness signatures match. Dense
queries have one initial call and 63 provenance calls; targeted queries have
64 initial calls and zero provenance calls. Phase interval unions, event counts
and all CPU/allocation thread partitions independently conserve original totals.
These instrumented times do not reopen Attempt 136 or change acceptance.
[Phase report](profiling/distinct-phase-boundaries/README.md). A separate authenticated
full CallSite-export census finds only 12 targeted hits among 104,566 CallSites
in the two hit graphs, explaining why scanning all their nodes deserves review.
The [complete CallSite executor map](profiling/callsite-executor-map.md) also shows
that a Serial consumer alone does not remove the large-trigram-sort pool entry.
No new production optimization has started while revert CI remains running.

**Revert 136 CI terminal result:** unit workflow
[33986202091](https://github.com/johnsonlee/graphite/actions/runs/33986202091)
passes, benchmark workflow
[33986202016](https://github.com/johnsonlee/graphite/actions/runs/33986202016)
fails. Method 17/order CPU is 1.740→2.130 s (+22.4%), confirmed in reverse order
1.940→3.230 s (+66.5%). Routing cold K64 P50 is 0.278150→1.524298 ms and P95
2.319441→3.077890 ms; the K64 P50 bound fails, while that P95 delta does not
independently trigger its bound. Global P95 pairs are
194.765887→100.719754, 103.967619→97.493250, and 126.274819→238.913798 ms.
The global comparator reports a repeated aligned DISTINCT-zero latency failure.
All checks are terminal; no retry or failure waiver is performed.
[Global status](profiling/attempt136/revert-ci/global-wide-status.json),
[routing status](profiling/attempt136/revert-ci/routing-status.json),
[Method CPU report](profiling/attempt136/revert-ci/method17-cpu-report.md).
The source-equal revert is still not accepted; no retained optimization or 10x
goal completion is claimed.

[Independent revert-CI audit](profiling/attempt136/revert-ci/independent-ci-audit.md)
checks 51 evidence-manifest file hashes, 204 paired global signatures plus the
34-row seed, and all 6,822 routing signatures with provenance. It confirms the
reported performance failures; source/JAR content equality does not identify
their runtime causes or waive them.


### 2026-09-06 - Attempt 137: Keep mapped-view validation callbacks primitive

**Hypothesis:** full persisted mapped-view validation invokes generic Kotlin
Function1 callbacks for every Int/Long element. Existing cold pure-four-OR
DISTINCT profiles place 80.86% of all CPU samples in validation; Integer/Long
boxing leaves account for 94.24% of sampled allocation weight. Change only the
callback boundary to Java IntConsumer/LongConsumer and accept, preserving the
validation closures, default no-op callbacks, loop order, buffer operations,
original checksum bytes, work accounting and interruption boundaries.
[Existing source and sampling evidence](profiling/cold-four-or-index-validation.md).
No posting planner, tuple strategy, scratch buffer, CRC policy, cache, or thread
pool change is included.

The private validator's callback signature is internal. Its full view identity,
dimensions, property IDs, posting bounds, trigram order and CRC checks must remain;
selected posting physical order must still be validated completely before any
LIMIT output. Primitive consumers must receive the same values at the same point
as before. This is not the rejected Attempt 098 CRC batching change.

Candidate parent is full production revert
`d14bc77f625c515c2e5416728e1b07e0554aa67a`; comparison remains frozen main
`4e328b0109e13c896b74004823fb049fcb19251a`. Real input is the unchanged fixture64
manifest SHA256
`fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`,
64 class shards from the four pinned corpus families, not synthetic graphs.
Artifacts and exact commands are at `/private/tmp/graphite-attempt137.dcywsuq7`.

The prespecified plan requires module tests, lint/JMH packaging and independent
source/bytecode verification, one real v3 36-query correctness control, then
three unchanged old34 alternating pairs. Only nonregression plus strict P95
progress in every pair proceeds to three additional v3 pairs and mandatory CI.
Old34 warm dense-query profiles have zero validator samples: no warm speedup is
asserted from cold profiling, nor is a cold/warm comparison an optimization gain.
The following results record execution of that plan; acceptance is not established.

**Correctness and mechanism verification:** all 187 webgraph tests, detekt and
JMH test-exclusion checks pass. The immutable candidate JAR SHA256 is
`b129029382bc6f0e8491c97c9057830c5993902b9cbeae7f21e6df10865a1fb7`;
production source SHA256 is
`8a803b81cfd2b9c455276c33640cafff9963a11673eed3a504b59fa5a63b6672`.
An independent mechanical reversal of the six added/four removed lines yields
byte-identical frozen-main source. Actual javap output shows primitive
`IntConsumer.accept(I)V` and `LongConsumer.accept(J)V`; the validator's prior
two Function1 calls and Integer.valueOf/Long.valueOf sites are absent. This
verifies the callback bytecode, not its machine-code optimization or measured
benefit. [Build/bytecode receipt](profiling/attempt137/build-receipt.json),
[independent source review](profiling/attempt137/preimplementation-audit.md).

**Original-34 local paired result:** all 204 signatures pass. The unchanged
regression-only gate passes, and all three P95 values improve. This permits the
prespecified v3 pairs and CI evaluation, not acceptance.

| Pair/order | Main → candidate P95 (ms) | Main → candidate CPU (s) | Peak heap (GiB) | Peak RSS (GiB) |
|---|---:|---:|---:|---:|
| 1 candidate-base | 137.409167 → 52.732459 | 1.687700 → 1.430862 | 4.386 → 3.571 | 4.890 → 4.073 |
| 2 base-candidate | 49.278375 → 48.396208 | 1.544348 → 1.415953 | 4.022 → 3.583 | 4.523 → 4.091 |
| 3 candidate-base | 49.659250 → 40.778083 | 1.702896 → 1.282096 | 4.390 → 3.583 | 4.907 → 4.058 |

Pair 2 P95 improves only 0.882167 ms (~1.02x). That pair's targeted
DISTINCT row slows from 32.371084 to 42.323250 ms; it is not a repeated
aligned-row failure under the unchanged rule. Do not describe every row in
every pair as nonregressing or infer stable warm-query gains from this screen.
All work/access/path counters are unchanged. Final 10x is false.
[Full comparison](profiling/attempt137/global-wide-report.md),
[status](profiling/attempt137/global-wide-status.json),
[local decision](profiling/attempt137/local-progress.json).


**Additional v3 paired diagnostic:** all 216 observations across three alternating
pairs pass full output/order/provenance correctness. Pure four-OR covers single
hit graphs at the first/middle/last positions, two hit graphs, 55 graphs and all
64 graphs, in both rows and DISTINCT forms. DISTINCT single/two graph cases
fall from 142.850–151.336 ms to 100.694–108.240 ms, and all-64 from
175.412–183.115 ms to 133.764–135.707 ms. These are individual paired observations,
not a per-query P95. No query exceeds >15% and >1 ms in two pairs; pair 2
two-term broad rows has a single 4.570417→5.663708 ms regression, and pair 3
pure four-OR all-graph rows has a smaller 11.771583→12.676041 ms slowdown.
No blanket claim of every-row improvement is made.
[All 36 paired results and commands](profiling/attempt137/v3-pairs/README.md),
[old34 independent audit](profiling/attempt137/independent-old34-audit.md).

**Decision:** local evidence permits submission to exact-head required CI only.
Method-level, end-to-end, routing and hosted global comparisons remain pending;
there is no accepted optimization, no 10x result, and no CallSite pool removal.
The original gates are unchanged. A failed candidate will be explicitly reverted.

The v3 audit also retains smaller repeated slowdowns in broad AND DISTINCT
and mixed-four DISTINCT. Unlike old34, four v3 work-counter pairs differ;
there is no claim of invariant v3 work or universal latency improvement.
See the complete paired table and independent v3 audit for the exact values.


**Post-submission mechanism diagnostic (not an acceptance rerun):** one new
CPU/allocation recording per revision, 40 repetitions each of the same pure
four-OR rows/DISTINCT queries per JVM, confirms the expected sampled change.
Within 40 DISTINCT windows, validator-boxed leaf allocation weight falls from
46,526,889,984 to 2,097,152 bytes; remaining samples are the separate graph-work
consumer, not the changed element callbacks. All allocation sample weight is
49,397,143,680→2,952,305,632 bytes. These are sampled TLAB weights, not exact
allocation or latency evidence. All 160 complete query outputs pass; JAR and
real graph input hashes remain unchanged. No additional production change is
included and exact-head CI still decides acceptance.
[Mechanism evidence](profiling/attempt137/mechanism/README.md).


**Final decision: rejected and production-only reverted.** Candidate commit
`536f585aab37da0888dd021cf9355d73dad1c545` fails required exact-head CI run
[33988242513](https://github.com/johnsonlee/graphite/actions/runs/33988242513).
Method 4 aggregate count process CPU is 0.67→0.89 s (+32.8358%), with reverse
confirmation 0.89→1.05 s (+17.9775%). Method 4 position middle CPU is
0.86→1.13 s (+31.3953%), confirmed 0.84→1.04 s (+23.8095%). Both exceed the
unchanged 15% bound twice. These are real compatibility workload process CPU
metrics, not callback-only CPU. No cause is inferred solely from the failure.
Method4 early/zero initial overruns do not repeat above threshold; they are not
reported as independently blocking failures. Wall and RSS-after comparisons
for these two shards do not block.
[Aggregate CPU report](profiling/attempt137/ci/method4-aggregate/method-compatibility-4-aggregate-cpu-report.md),
[position CPU report](profiling/attempt137/ci/method4-position/method-compatibility-4-position-cpu-report.md).

The production change is explicitly restored to parent d14bc; all 130 tracked
main/JMH files again match frozen main byte-for-byte. Correctness tests, local
paired results, mechanism evidence and this rejection log are retained.
There is no accepted improvement, thread pools remain and 10x is unmet. No gate
is relaxed or workflow rerun to reverse this outcome. Some candidate CI jobs
are still active at rejection; a superseding revert push may cancel them under
the existing concurrency policy. Incomplete checks are not passes. Full
method compatibility is a regression; end-to-end and hosted global/routing
conclusions remain unavailable until their own authoritative results exist.

[Independent CI failure audit](profiling/attempt137/ci/ci-audit.md) confirms the original JMH CPU scores, reverse confirmations and ten matching scenario signatures.


**Terminal CI follow-up:** Attempt 137 candidate benchmark `33988242513` ultimately
ended cancelled after the revert; its two completed, reverse-confirmed Method 4
CPU failures remain the rejection evidence. Cancelled large-corpus/routing/global
jobs are unavailable, not passes. Revert `e6c932c5` unit `33988848638` passes and
benchmark `33988848640` fails: Method 17 OR CPU is 1.95→3.17 s and 2.18→3.41 s in
confirmation; Method 36 contains is 5.00→5.88 s and 5.11→6.02 s. Global-wide P95
regresses in all three pairs; routing passes. Equal recorded binary hashes do not
waive the failures. [Candidate terminal receipt](profiling/attempt137/ci/terminal-receipt.json)
and [complete revert audit](profiling/attempt137/revert-ci/README.md) preserve the
original values and terminal snapshots.

**Profiling follow-up, not a new accepted attempt:** a diagnostic JAR rebuilt from
exact rejected Attempt 133 source retains its original rejection. One original-34
recording per revision verifies 68 oracle signatures and full phase/outer-stack
conservation. Rebuilt-133 dense provenance has 56/64 application CPU samples in
findId, nested inside selectedTupleStringIds/selectedProjectionHits; these counts
are not additive. FindId sampled allocation is 38,273,024 bytes versus main's
2,621,440 bytes. Source inspection shows repeated tuple string resolution bypassed
main raw's invocation-local ID/membership reuse. This identifies work omitted by
small posting-cardinality counts, not stable speedup or acceptance evidence.
[Rebuild provenance and residual audit](profiling/distinct-projection-work/rejected133-residual/README.md).


### 2026-09-06 - Attempt 138: Project DISTINCT candidates through validated mapped postings

**Hypothesis:** warm DISTINCT has two measured costs: initial selection still
raw-scans 104,566 CallSites for 12 matches; dense selected-tuple provenance
performs both predicate discovery and raw projection. Use the immutable mapped
index for candidate projection. Initial selection uses its existing physical-order
posting merge only when the exact occurrence upper bound cannot fill LIMIT;
selected complete-four-property tuples use their shortest fully validated
posting. Preserve main raw's invocation-local String→ID and property-membership
reuse in that selected-tuple path. The rejected-133 diagnostic above explains
why tiny candidate cardinality alone did not remove repeated dictionary work.
[Phase attribution, source audit and independent candidate census](profiling/distinct-projection-work/README.md).

This is one mapped candidate-projection direction; no StringTable implementation,
persisted format, global cache, validator, compiler, scheduler, thread pool,
benchmark or gate changes are included. Retained-heap, raw and unsupported
fallback paths remain. Posting order must be fully validated before LIMIT;
null/repeated/graphId columns, original predicates, physical order, complete
source provenance, budget and cancellation semantics remain required.

Candidate parent is explicit revert `e6c932c5e1d0fb7b583ceb9e14c8ef88ec9d9694`;
reference remains frozen main `4e328b0109e13c896b74004823fb049fcb19251a`.
The unchanged real fixture64 manifest SHA256 is
`fe66cc84f6d8ee95c49b49ad500f921b304f0160c2ae094621683bb4db94ea6b`:
64 persisted class shards across Android 14, Tika 2.9.2, Hive 4.0.0 and Kotlin
compiler 2.0.21, 5,046,935 CallSites, not synthetic performance data.
Exact commands/artifacts: `/private/tmp/graphite-attempt138.7ihszrob`.
The prespecified [measurement plan](profiling/attempt138/measurement-plan.json)
requires unchanged regression bounds plus strict global P95 progress in every
old34 pair before additional v3 pairs and exact-head full CI. There is no
accepted optimization at this point.

**Correctness:** 192 WebGraph tests in six suites pass, with zero failures,
errors or skips; detekt, JMH packaging and test-exclusion checks pass. All five
new behavior tests actually ran. Independent source review compares all 130
main/JMH files with frozen main; only the two intended production files differ.
Candidate JAR SHA256:
`2c419ac0b9d996af0890d1c857f81fa3479c170f59306fe35517a6e90cf7b5bf`.
[Build receipt](profiling/attempt138/build-receipt.json),
[independent build audit](profiling/attempt138/independent-build-audit.md),
[combined source review](profiling/attempt138/combined-review.md).
The separate real v3 control passes all 36 complete value/order/provenance
comparisons and before/after graph-content checks; it is not a P95 measurement.

**Original-34 local paired result:** unchanged regression-only comparison passes,
and global P95 strictly improves in all three pairs. Final 10x is false.

| Pair/order | Main → candidate P95 (ms) | Main → candidate CPU (s) | Peak heap (GiB) | Peak RSS (GiB) |
|---|---:|---:|---:|---:|
| 1 candidate-base | 56.735 → 19.399 | 1.573797 → 1.275374 | 4.38 → 4.38 | 4.92 → 4.86 |
| 2 base-candidate | 53.315 → 22.333 | 1.576525 → 1.305471 | 4.39 → 4.11 | 4.91 → 4.57 |
| 3 candidate-base | 37.082 → 20.844 | 1.445943 → 1.256500 | 4.39 → 4.27 | 4.91 → 4.75 |

This is the original percentile across 34 different query cases, not repeated
samples of one query. Pair 1 wrapped non-DISTINCT shape P95 is slightly slower
(0.94x); no universal improvement is claimed. Full per-row and resource evidence
is in the [original comparison](profiling/attempt138/old34-pairs/global-wide-report.md)
and [status](profiling/attempt138/old34-pairs/global-wide-status.json).
Additional pure-four-OR/multi-keyword v3 pairs and exact-head CI remain pending.
No CallSite pool has been removed; the final 10x goal remains unmet.


[Independent old34 audit](profiling/attempt138/independent-old34-audit.md) verifies
all 204 complete 14-field oracle signatures and recomputes all six rank-33 P95
values; DISTINCT dense remains the determining row in both revisions. It
retains 47 slower observations out of 102 comparisons, including three IDs
slower in every pair. None simultaneously exceeds +15% and +1 ms; smaller
slowdowns remain visible. Targeted work falls 106,706→2,370 and raw parallel
scans 2→0; dense work falls 283,544→22,365 and scans 2→1. All hit/source/access,
execution-path and index-lookup counters are unchanged. Work counts are not
CPU instructions or proof of speedup; the measured paired latency is separate.


**Final decision: rejected; explicit source/test revert follows the attempt record.**
All 216 v3 observations match full values/order/provenance (37,026 returned rows),
with six unchanged before/after graph inventories. However, the added workload
finds two repeated regressions under the same >15% and >1 ms per-query bounds:

| Query | Pair 1 main → candidate (ms) | Pair 3 main → candidate (ms) |
|---|---:|---:|
| Four-term mixed, two-hit-graph rows | 210.399375 → 271.733125 (+29.151%) | 312.862792 → 720.623958 (+130.332%) |
| Pure four-OR, 55-hit-graph DISTINCT | 149.365542 → 185.582709 (+24.247%) | 152.262625 → 192.266333 (+26.273%) |

The audit retains all 108 paired observations, including 86 slower values and
40 work-counter changes; all other observed fields match. Pure four-OR has 32
slower observations out of 36 pairs. Faster all-64 DISTINCT observations and the
old34 aggregate P95 pass do not override these repeated regressions. There are
only three observations per query; no repeated-query P95 is asserted.
[Full 36-query table](profiling/attempt138/v3-pairs/README.md),
[independent full-output audit](profiling/attempt138/independent-v3-audit.md),
[decision](profiling/attempt138/decision.json).

No candidate CI is submitted after this local rejection; method-level and
end-to-end CI performance are therefore unavailable for Attempt 138, not passes.
The original-34 local CPU/heap/RSS results above are separate evidence and do not
supply those missing conclusions. No failing measurement is rerun or waived.
Both production files and the two tests whose path assertions require the
candidate are restored to e6c in the explicit revert; their exact attempted
versions remain in the attempt commit. Existing pure-four-OR correctness tests,
all profiling evidence and this chronological record remain. No optimization
is accepted, CallSite pools remain, and the final 10x goal is unmet.
