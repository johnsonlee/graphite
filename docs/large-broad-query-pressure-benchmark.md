# 64-graph broad-query pressure benchmark

`LargeBroadQueryPressureBenchmark` measures the full replay distribution of broad node-property
searches across 64 mapped graph handles under an exact `-Xmx8g` cap. The number of queries is a
result of the coverage matrix, not a target: 32 non-routing shapes are crossed with zero-hit,
targeted, and dense selectivity, while three graphId spellings plus a request-selected single-source
reference path cover every graph and every selectivity in the manifest.

The matrix borrows the useful taxonomy from the openCypher TCK, but not the TCK datasets or its
pass criteria. The TCK is a correctness suite over small scenario graphs. This pressure benchmark
selects only clause and expression boundaries that change search, retention, merge, or
materialization cost:

- literal and parameterized `CONTAINS`, `STARTS WITH`, `ENDS WITH`, regular expression, `=`, and `IN`;
- `AND`, `OR`, `AND NOT`, and mixed exact/substring predicates;
- raw and `toLower(coalesce(...))` property access;
- literal, function, and parameterized `graphId` routing combined with wrapped broad search;
- request-selected single-source execution, equivalent to the source set produced by
  `/api/cypher/graphs` with its `graph` parameter, with the same wrapped broad search;
- node, property, `keys`, graph provenance, `DISTINCT`, and aggregate projection;
- `ORDER BY`, `SKIP`, small and large `LIMIT`, and full-match aggregation;
- zero-hit, targeted, and dense terms for every shape.

Relationship traversal, variable-length paths, and write clauses are intentionally separate from
this node-property wide-search distribution. Mixing them into the same P50/P95 would make a change
in topology traversal look like a regression in broad search.

## Graph input

Performance runs require a tab-separated manifest with exactly 64 unique graph ids and 64 distinct
fixture-derived persisted graph paths. Synthetic graphs and repeated paths are rejected and may
only be used outside this harness for correctness verification. Each graph also supplies reviewed terms
whose observed result counts define the three non-overlapping selectivity bands. Each line is
`<graph-id><TAB><absolute-path><TAB><zero-term><TAB><targeted-term><TAB><dense-term>`:

```text
app-000	/graphs/app-000	GraphiteAbsentApp000	com.acme.rare.Feature	java
app-001	/graphs/app-001	GraphiteAbsentApp001	org.example.checkout	get
```

Set it on the fork with
`-Dgraphite.broad.pressure.graphs=/absolute/path/to/graphs.tsv`. The harness rejects a manifest
whose entry count differs from `graphCount`, whose ids or paths are duplicated, or whose three terms
are blank or equal. The request-selected reference query must prove `zero=0`, `targeted=1..199`, and
`dense=200` rows under `LIMIT 200`; otherwise the fork fails before its timing can be accepted.

Generate the repository baseline from the four pinned real-bytecode fixtures rather than from
synthetic nodes:

```bash
./gradlew :webgraph:jmhJar :webgraph:prepareBenchmarkFixtures --no-daemon
.github/scripts/prepare-fixture64-graphs.sh \
  graphite-webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar \
  graphite-webgraph/build/benchmark-fixtures \
  /absolute/path/to/fixture64
```

The preparation deterministically partitions Android, Tika, Hive, and Kotlin compiler into 16
non-overlapping class-entry shards each, then executes `JAR -> build -> save -> mapped load` for all
64 shards. It calibrates and verifies the three selectivity terms per shard and writes
`graphs.tsv` plus `fixture-provenance.tsv`. The provenance pins source-JAR and persisted-graph
identity: the whole-JAR SHA-256, an order-independent bytecode-shard SHA-256, and an
order-independent query-semantic SHA-256 over node count plus the complete CallSite string tuple
multiset. Generated timestamps and physical node order are intentionally excluded. Class/node/
CallSite counts, corpus id, and shard id are also recorded, and all 64 semantic fingerprints must
differ. Preparation immediately reloads and re-verifies all 64 graphs against the same identities.
For a release-candidate audit, `.github/scripts/test-fixture64-reproducibility.sh` performs two
independent real-JAR preparations, requires identical identity/term fields, and proves that a
tampered semantic fingerprint is rejected.

This is honestly a **64-shard routing and wide-search baseline over four real code corpora**, not
64 independent production applications. It proves graph-count routing coverage and exercises real
bytecode-derived graph layouts. Production-distribution claims still require the separately held
production graph set; the fixture result must not be relabeled as that evidence.

## Correctness hard gate

Performance results are invalid unless correctness verification passes. The benchmark defaults to
`verify` mode and refuses to start without a correctness oracle. The oracle must contain every
query selected by `coverageFamily`, every record must have outcome `success`, and query ids must be
unique. During warmup and measurement the gate compares, per query:

- family, shape, selectivity, operator, clause boundary, projection, and limit;
- outcome, complete row count, and serialized response size;
- SHA-256 over complete columns, ordered rows, and values; graph-routing query ids bind each
  signature to its target manifest slot.

Missing, duplicate, or unexpected cases; timeout/failure results; and any signature difference
fail the JMH fork. A faster run that fails this gate is not a performance improvement.

For a general `coverageFamily=all` run, create the oracle in a separate run from a trusted semantic
implementation, with a timeout high enough for every query to finish:

```bash
java -jar trusted-webgraph-jmh.jar \
  'io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries' \
  -p graphCount=64 -p coverageFamily=all -p indexState=cold -p timeoutMillis=300000 \
  -wi 0 -i 1 -f 1 -foe true \
  -jvmArgsAppend "-Dgraphite.broad.pressure.graphs=/absolute/path/to/graphs.tsv \
    -Dgraphite.broad.pressure.correctness.mode=record \
    -Dgraphite.broad.pressure.output=/absolute/path/results/correctness-oracle.manifest"
```

`record` mode also fails unless every selected query succeeds. Its timing is oracle-generation
overhead, not comparable performance evidence. Review and retain the oracle as an immutable test
artifact; do not regenerate it from the candidate being measured.

The fixture64 routing driver uses a stricter differential protocol tailored to this change. It
records 192 request-selected single-source results from `main`, plus 123 request-selected K-source
results over deterministic disjoint K=2/8/64 groups. Those groups cover every one of the 64 real
fixture graphs exactly once at each width and selectivity. The driver derives a 1,137-record oracle
for the equality, `IN` literal, `IN` parameter, and reference identities. The oracle is independent
of the candidate graphId path; candidate self-recording is explicitly not accepted.

## Comparable base/candidate run

Copy the same benchmark source into the base and candidate checkouts before building either JMH
jar. Run revisions sequentially on the same idle machine. Run each family as a separate shard. The
example below is the candidate command; the repository driver records main separately and verifies
the candidate against the independently derived request-selected oracle:

```bash
FAMILIES='contains boolean exact wrapped projection aggregation global regex graph-routing'
for FAMILY in ${FAMILIES}; do
  java -jar webgraph-jmh.jar \
    'io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries' \
    -p graphCount=64 -p coverageFamily="${FAMILY}" -p indexState=cold -p timeoutMillis=60000 \
    -wi 0 -i 1 -f 1 -foe true -prof gc -rf json \
    -rff "results/${REVISION}-${FAMILY}.json" \
    -jvmArgsAppend "-Dgraphite.broad.pressure.graphs=/absolute/path/to/graphs.tsv \
      -Dgraphite.broad.pressure.correctness.oracle=/absolute/path/results/correctness-oracle.manifest \
      -Dgraphite.broad.pressure.output=/absolute/path/results/${REVISION}-${FAMILY}.correctness \
      -Dgraphite.broad.pressure.observations.output=/absolute/path/results/${REVISION}-${FAMILY}.tsv"
done
```

Run cold and warm in independent forks. Do not run base and candidate families concurrently: their
8 GiB heaps and mapped page-cache working sets would contaminate CPU and memory comparisons.

## graphId and request-selected routing gate

The `graph-routing` family contains 1,137 queries. Every one of the 64 graph ids is exercised through
property, `graphId(n)`, parameterized graphId, and request-selected single-source execution at zero, targeted,
and dense selectivity. For a given target and selectivity, all four forms use the same reviewed term,
so the request-selected single-graph result is a direct semantic reference for the three query-level
graphId forms. The queries preserve the production non-`DISTINCT`, four-property wrapped
`CONTAINS`, and `LIMIT 200` shape.

The same real fixtures also cover selected-set widths 2, 8, and 64 through `n.graphId IN [...]`,
`n.graphId IN $graphIds`, and a request-selected K-source reference. Together with the existing
single-graph equality matrix, this covers widths 1/2/8/64. The candidate must receive 64 input
sources for every Cypher graphId form, touch no source outside the selected set, and report `64-K`
sources pruned. Zero and targeted queries must consume all K selected sources. Dense queries are
expected to stop after the first selected graph fills the global `LIMIT 200`, so they must access
exactly one selected source even though the planner selected K. K=64 therefore requires zero
planner pruning and one dense execution access, not fabricated pruning or 64 forced lookups. The
comparator reports P50/P95 separately by set width; these rows do not enter the single-graph 10x P95 gate.

For this finite-limit shape, the query layer passes the remaining `LIMIT` into the selected mapped
graph. The production CLI and fixture64 builder persist the combined CallSite CSR/trigram index;
`loadMapped` restores it lazily on the first relevant query, so unrelated Method queries retain no
CallSite-index heap. Legacy or invalid graph files rebuild it once and atomically persist it when
the complete index is released or the mapped graph closes. If index admission is denied, the graph retains the
correct raw-scan fallback; that fallback partitions the mapped CallSite type index onto
`graphite-callsite-scan-N` workers. The default fallback background-worker count is the segment
half of the additive NCPU plan (`NCPU - floor(NCPU / 2)`), overridable with
`-Dgraphite.webgraph.callSiteScanParallelism=N`. `graphite-cypher-scan-N` names the separate
cross-graph source pool and is not evidence that a query restricted to one graph used intra-graph
parallelism.

During a real run, verify actual use in JFR/VisualVM by filtering for
`graphite-callsite-scan-` and checking that multiple workers are simultaneously runnable/on-CPU
during one selected-graph query. The benchmark's process-CPU-time / wall-time effective-core
ratio is the numeric utilization baseline; thread count alone is not sufficient.
The harness also records the number of bounded intra-graph scans and the peak number of workers
simultaneously inside one scan. The fixture64 comparator fails closed unless the candidate executes at
exactly one such scan on each of the 64 graphs and observes at least two active workers; merely
creating eight threads cannot satisfy the gate. It also counts retained-index lookups per graph.
Before loading graphs, the harness sets
`graphite.webgraph.prepareCallSiteStringIndexOnLoad=lazy` for cold and warm forks. This disables
load-time preparation without disabling production's lazy persisted-sidecar restore. The property
is restored after the trial. `startup-prepared` explicitly uses the `true` load-time preparation path.

The candidate cold fork must report 64 scans followed by exactly 1,979 index lookups, distributed
29..38 per graph because dense queries stop at the global limit. After warmup metrics are reset, the
warm fork must report zero scans and exactly 2,043 index lookups, distributed 30..39 per graph, with
all 64 graphs represented in both cases. The `startup-prepared` fork
performs no query warmup: main must retain its one lazy-build scan per graph, while the candidate
must restore all 64 persisted indexes and execute 2,043 indexed lookups with zero scans. Main has no
set routing in the comparison revision, so startup-prepared exposes 64 lazy-build scans plus 14,139
lookups, distributed 181..266 per graph. Aggregate totals alone
cannot hide a distribution such as `[641, 1, ..., 1]`. This rejects an implementation that happens
to meet the percentile target while routing only part of the workload or silently falling back to
raw scans.

When a bounded scan reaches the end of the selected graph, the candidate reuses the already-read
node and string ids to publish the combined CallSite CSR index instead of discarding them and later
performing two more mapped-file passes. Subsequent routing queries for that graph use the retained
index. The global CallSite-index budget defaults to half of `-Xmx` (4 GiB under this gate's 8 GiB
heap) and can be overridden with `-Dgraphite.webgraph.callSiteStringIndexBudgetBytes=N`.

The Cypher boundary also reuses immutable materialized rows for repeated bounded projections. This
is a process-wide LRU keyed by the storage projection generation, column layout, and graph id, so
releasing a mapped string index cannot return stale rows. It retains at most 32 entries and at most
`min(2 MiB, maxHeap / 2048)` estimated bytes; override the latter with
`-Dgraphite.cypher.directProjectionCacheBytes=N`. Storage lookup and work-budget accounting still
run on every request before this result cache is consulted.

For the request-selected reference cases, the harness performs the same id-to-single-lease selection produced by
`POST /api/cypher/graphs` with `graph=<id>` before invoking the executor. Route tests separately
verify singular JSON and query-string `graph` parsing; the pressure timing deliberately excludes
fixed HTTP and JSON serialization overhead so it measures the multi-graph routing and search cost.

Run `main` in record mode to capture its performance observations, and run the candidate in verify mode against the
base-single-source-derived correctness oracle. Candidate timing is invalid unless oracle
verification passes. Then enforce the gate for cold, warm, and `startup-prepared` forks separately:

```bash
node .github/scripts/benchmark-gate.mjs compare-graph-id-pressure \
  --base results/main-graph-routing-cold.json \
  --candidate results/candidate-graph-routing-cold.json \
  --base-observations results/main-graph-routing-cold.tsv \
  --candidate-observations results/candidate-graph-routing-cold.tsv \
  --minimum-speedup 10 \
  --report results/graph-id-cold-report.md \
  --status results/graph-id-cold-status.json
```

The comparator fails unless both revisions report exactly 64 distinct graph paths, all 1,137 queries
complete without timeout/failure, every manifest graph id has all four routing forms at all three
selectivities, and the already-correct request-selected P50/P95 do not regress by more than 15% for
material latency; microsecond-scale request-selected and graph-set paths have a 0.25ms absolute
jitter allowance. Set-width P95 must still avoid regression and is reported independently.
Cold/warm compatibility gates require query-level graphId P50 and P95 to improve by 10x. The
production-relevant `startup-prepared` gate requires graphId P95 to improve by 10x and P50 not to
regress by more than 15%, because its P50 is already an indexed microsecond-scale request. A warm result is rejected unless all 64
graphs retain the combined CallSite index and all
64 initialize the lowercase trigram postings; proving the indexed path on only one graph is not
coverage. It independently
rejects any request-selected reference outside `zero=0`, `targeted=1..199`, and `dense=200`, so an all-zero
workload cannot pass. This prevents accepting a graphId speedup when the already-selected
single-graph request path regresses. The request-selected path is the correctness reference and a non-regression
guardrail; the 10x target applies to query-level graphId routing.

The candidate in-process hard gate checks all routing results against the base request-selected oracle.
The comparator additionally requires every candidate graphId result to match its request-selected
K-source reference in selectivity, row count, response size, and SHA-256 digest, and requires
the request-selected reference itself to match between main and candidate. Main's graphId output is
retained only as the latency baseline and is never accepted as a correctness oracle. The observation
TSV contains one row per query with family, shape, selectivity, operator, clause boundary,
projection, limit, target graph set, selected width, input/accessed source counts, planner pruning,
outcome, row count, response bytes, digest, and latency nanoseconds.
Repeat with warm and `startup-prepared` forks; all three statuses are required evidence.

## Required fixture64 evidence check

The full fixture64 replay is intentionally run on the benchmark host rather than synthesized in a
small hosted-runner test. The normal `benchmark-regression-gate` depends on
`graph-routing-pressure-evidence`, which fails closed until the exact candidate commit has a trusted
`graph-routing-pressure-evidence`. The required PR job checks out the exact base and candidate SHAs,
regenerates all 64 persisted graphs from the pinned fixture JARs, executes the cold, warm, and
startup-prepared states, verifies correctness, and reruns the comparator on the same trusted runner.
No external URL, Gist, or author-published commit status is accepted as execution evidence.

Unscoped 64-graph wide queries have a separate required component,
`global-wide-pressure-evidence`. It requires
three paired base/candidate JVM forks in alternating order (`candidate/base`, `base/candidate`,
`candidate/base`) and a P95 speedup of at least 10x in every independent fork. The nine targeted
query shapes are placed across graph ordinals 1, 8, 16, 24, 32, 40, 48, 56, and 64, and each result is
bound to that graph's fixture-derived workload identity. Every zero-hit observation must prove that
all 64 distinct graph ids were accessed. This prevents first-graph-only coverage, empty-result
shortcuts, and a base-first page-cache bias from satisfying the gate.

Each fork uses the `cold` index state: graph handles are loaded, but retained query indexes are
cleared before the measured replay. This matches the unprepared/legacy graph state behind the
production multi-second first wide queries. The candidate may restore a verified persisted sidecar
inside the request; that I/O and validation remains part of the measured latency.

The `global-wide` family uses the production-shaped unlabeled `MATCH (n)` with eight raw
four-property `CONTAINS` projection/boundary variants plus the original case-insensitive
non-`DISTINCT` `toLower(coalesce(...)) CONTAINS ... OR ...` form. All nine shapes run at zero, targeted, and dense
selectivity for 27 correctness and latency rows. In addition to the aggregate P95 requirement, the
wrapped case-insensitive subgroup must independently reach 10x P95 in every paired fork, so faster
raw cases cannot hide a regression in the motivating query shape.

Run the repository-owned driver with the generated fixture64 manifest; it builds both revisions and
derives the correctness oracle itself:

```bash
.github/scripts/run-real64-graph-routing.sh \
  /absolute/path/to/fixture64/graphs.tsv \
  graphite-webgraph/build/benchmark-fixtures \
  "$BASE_SHA" "$CANDIDATE_SHA"
```

Run the unscoped global-wide gate against the same verified manifest and fixture JARs:

```bash
.github/scripts/run-real64-global-wide.sh \
  /absolute/path/to/fixture64/graphs.tsv \
  graphite-webgraph/build/benchmark-fixtures \
  "$BASE_SHA" "$CANDIDATE_SHA"
```

The driver verifies both SHAs against GitHub, creates independent clones at those exact commits,
copies the candidate-reviewed pressure harness byte-for-byte into the base worktree, and builds both
JMH JARs itself. It records the two commit SHAs plus SHA-256 for the harness, comparator, driver,
both built JARs, graph manifest, fixture provenance, the `base-single-source` oracle origin, and the
derived correctness-oracle SHA-256 in `provenance.json`;
caller-supplied JARs are not accepted. Before running, it requires exactly 64 provenance rows, four
source corpora, and 64 distinct query-semantic graph fingerprints. The candidate-built verifier
re-hashes the four supplied fixture JARs and every bytecode shard, binds every manifest path and
term to its provenance row, reloads all 64 actual graph directories, and recomputes counts, terms,
and semantic identities before timing. It then runs base and candidate sequentially for independent
cold, warm, and `startup-prepared` forks,
verifies the candidate against the base-derived oracle, invokes `compare-graph-id-pressure` for all
states, publishes immutable downloadable evidence, and only then publishes the trusted commit
status. Set
`GRAPHITE_PRESSURE_TIMEOUT_MILLIS` to override the default five-minute per-query timeout. Retain
all three comparator reports plus the JMH JSON, observation TSV, correctness manifests,
`provenance.json`, `fixture-provenance.tsv`, JVM/host details, and raw CPU/heap/RSS/GC counters.

## Baseline metrics

JMH JSON exposes the suite and family P50/P95/max latency, timeouts, process CPU time and effective
core utilization, sampled peak process CPU load, heap before/peak/after, committed/max heap, RSS
before/peak/after, GC count/time, raw match-state bytes, and admitted/retained CallSite indexes.
The graph-routing comparison report prints base-to-candidate effective CPU cores, peak heap, peak
RSS, GC count/time, retained-index memory, parallel-scan count, and peak active workers alongside
latency. These are measured diffs rather than a requirement to consume the full 8 GiB heap: `-Xmx8g`
is a ceiling, not a utilization target.
Keep timeout samples in percentile calculation at the configured timeout. Report both overall and
selectivity/family percentiles, then diff every observation row by id so a fast family cannot hide
a regression in another shape.
