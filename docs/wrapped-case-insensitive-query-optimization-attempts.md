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
