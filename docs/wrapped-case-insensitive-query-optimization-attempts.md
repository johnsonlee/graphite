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

`WrappedDiscoveryLatencyBenchmark` persists deterministic mixed-case data and
runs warm and cold forms with `graphCount=1,17`. CI overlays that exact harness
onto the fixed pre-PR-95 commit and the pull request base before compiling all
three revisions. `compare-latency-baseline` fails closed for missing parameters
and requires both the fixed-baseline speedup and the current-base regression
limit. Suspected failures repeat in candidate/base/fixed reverse order. The
fixed SHA is explicit in `.github/workflows/benchmark.yml`.

`AndroidWrappedDiscoveryBenchmark` is the manual real-corpus counterpart. It
uses the exact production query and the existing validated Android corpus.

### 2026-08-29 - Attempt 005: Heterogeneous all-fixture baseline

The repeated-Android benchmark isolates graph-count scaling but reuses one
mapped graph and therefore does not represent heterogeneous string tables,
hit distributions, or page-cache behavior. The permanent gate now adds
`AllFixtureWrappedDiscoveryLatencyBenchmark`: Android, Tika, Hive, and Kotlin
Compiler are built and persisted one at a time, then all four mapped graphs
(`19,091,048` nodes total) are queried together. CI prepares the persisted
graphs once and supplies the identical files to pre-PR-95, current-base, and
candidate JMH forks. This is additive to, not a replacement for, the stable
1/17-graph scaling benchmark.

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
