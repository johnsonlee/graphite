# 64-graph broad-query pressure benchmark

`LargeBroadQueryPressureBenchmark` measures the full replay distribution of broad node-property
searches across 64 mapped graph handles under an exact `-Xmx8g` cap. The number of queries is a
result of the coverage matrix, not a target: 32 non-routing shapes are crossed with zero-hit,
targeted, and dense selectivity, while three graphId spellings plus the `/api/cypher/graphs`
`graph`-parameter reference path cover every graph and every selectivity in the manifest. This
produces 864 queries
today.

The matrix borrows the useful taxonomy from the openCypher TCK, but not the TCK datasets or its
pass criteria. The TCK is a correctness suite over small scenario graphs. This pressure benchmark
selects only clause and expression boundaries that change search, retention, merge, or
materialization cost:

- literal and parameterized `CONTAINS`, `STARTS WITH`, `ENDS WITH`, regular expression, `=`, and `IN`;
- `AND`, `OR`, `AND NOT`, and mixed exact/substring predicates;
- raw and `toLower(coalesce(...))` property access;
- literal, function, and parameterized `graphId` routing combined with wrapped broad search;
- `/api/cypher/graphs` single-`graph` source selection with the same wrapped broad search;
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
are blank or equal. The API-selected reference query must prove `zero=0`, `targeted=1..199`, and
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
SHA-256 values, class/node/CallSite counts, corpus id, and shard id; all 64 persisted fingerprints
must differ.

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

Create the oracle in a separate run from a trusted semantic implementation, with a timeout high
enough for every query to finish:

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

## Comparable base/candidate run

Copy the same benchmark source into the base and candidate checkouts before building either JMH
jar. Run revisions sequentially on the same idle machine. Run each family as a separate shard. The
example below is the candidate command; for main's `graph-routing` shard, replace the oracle option
with `-Dgraphite.broad.pressure.correctness.mode=record` because main's graphId result is the known
correctness defect being fixed:

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

## graphId and API graph-parameter routing gate

The `graph-routing` family contains 768 queries. Every one of the 64 graph ids is exercised through
property, `graphId(n)`, parameterized graphId, and API `graph`-parameter routing at zero, targeted,
and dense selectivity. For a given target and selectivity, all four forms use the same reviewed term,
so the API-selected single-graph result is a direct semantic reference for the three query-level
graphId forms. The queries preserve the production non-`DISTINCT`, four-property wrapped
`CONTAINS`, and `LIMIT 200` shape.

For this finite-limit shape, the query layer passes the remaining `LIMIT` into the selected mapped
graph. If the combined CallSite index is not already resident, that graph partitions its mapped
CallSite type index into ordered ordinal ranges and scans them on `graphite-callsite-scan-N`
workers. The default worker count is `min(8, Runtime.availableProcessors())`; override it with
`-Dgraphite.webgraph.callSiteScanParallelism=N`. Results are concatenated in persisted node order,
all workers share the request work/cancellation budget, and a worker failure stops and joins the
remaining tasks before the query returns. `graphite-cypher-scan-N` names the separate cross-graph source pool and is not evidence that
a query already restricted to one graph used intra-graph parallelism.

During a real run, verify actual use in JFR/VisualVM by filtering for
`graphite-callsite-scan-` and checking that multiple workers are simultaneously runnable/on-CPU
during one selected-graph query. The benchmark's process-CPU-time / wall-time effective-core
ratio is the numeric utilization baseline; thread count alone is not sufficient.
The harness also records the number of bounded intra-graph scans and the peak number of workers
simultaneously inside one scan. The fixture64 comparator fails closed unless the candidate executes at
exactly one such scan on each of the 64 graphs and observes at least two active workers; merely
creating eight threads cannot satisfy the gate. It also counts retained-index lookups per graph.
The cold fork must report 64 scans followed by exactly 704 index lookups (11 remaining queries per
graph). After warmup metrics are reset, the warm fork must report zero scans and exactly 768 index
lookups (12 per graph), with all 64 graphs represented in both cases. The comparator requires the
per-graph lookup minimum and maximum to both be 11 cold and both be 12 warm; aggregate totals alone
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

For the API reference cases, the harness performs the same id-to-single-lease selection produced by
`POST /api/cypher/graphs` with `graph=<id>` before invoking the executor. Route tests separately
verify singular JSON and query-string `graph` parsing; the pressure timing deliberately excludes
fixed HTTP and JSON serialization overhead so it measures the multi-graph routing and search cost.

Run `main` in record mode to capture its performance observations even if its graph-qualified
result is known to be semantically wrong, and run the candidate in verify mode against an
independently reviewed correctness oracle. Candidate timing is invalid unless oracle verification
passes. Then enforce the gate for cold and warm forks separately:

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

The comparator fails unless both revisions report exactly 64 distinct graph paths, all 768 queries
complete without timeout/failure, every manifest graph id has all four routing forms at all three
selectivities, query-level graphId P50/P95 are each at least 10x faster, and the already-correct API
graph-parameter P50/P95 do not regress by more than 15%. A warm result is rejected unless all 64
graphs retain the combined CallSite index and all
64 initialize the lowercase trigram postings; proving the indexed path on only one graph is not
coverage. It independently
rejects any API reference outside `zero=0`, `targeted=1..199`, and `dense=200`, so an all-zero
workload cannot pass. This prevents accepting a graphId speedup when the already-selected
single-graph API path regresses. The API path is the correctness reference and a non-regression
guardrail; the 10x target applies to query-level graphId routing.

The candidate in-process hard gate checks all routing results against the trusted external oracle.
The comparator additionally requires every candidate graphId result to match its API-selected
single-graph reference in selectivity, row count, response size, and SHA-256 digest, and requires
the API reference itself to match between main and candidate. Main's known-wrong graphId output is
retained only as the latency baseline and is never accepted as a correctness oracle. The observation
TSV contains one row per query with family, shape, selectivity, operator, clause boundary,
projection, limit, target graph, outcome, row count, response bytes, digest, and latency nanoseconds.
Repeat with warm forks; both cold and warm statuses are required evidence.

## Required fixture64 evidence check

The full fixture64 replay is intentionally run on the benchmark host rather than synthesized in a
small hosted-runner test. The normal `benchmark-regression-gate` depends on
`graph-routing-pressure-evidence`, which fails closed until the exact candidate commit has a trusted
`graphite/fixture64-graph-routing` commit status. The status is accepted only when it was published
by `johnsonlee`, names the current base SHA, reports correctness pass, and records cold and warm
P50/P95 speedups of at least 10x. A status attached to an older candidate or base cannot satisfy the
gate. An arbitrary HTTPS URL is neither required nor treated as proof.

After building base and candidate JMH jars with the identical benchmark harness and preparing the
reviewed oracle, run the repository-owned driver with the generated fixture64 manifest:

```bash
.github/scripts/run-fixture64-graph-routing.sh \
  /absolute/path/to/fixture64/graphs.tsv \
  /absolute/path/to/reviewed-oracle.manifest \
  "$BASE_SHA" "$CANDIDATE_SHA"
```

The driver verifies both SHAs against GitHub, creates independent clones at those exact commits,
copies the candidate-reviewed pressure harness byte-for-byte into the base worktree, and builds both
JMH JARs itself. It records the two commit SHAs plus SHA-256 for the harness, comparator, driver,
both built JARs, graph manifest, fixture provenance, and correctness oracle in `provenance.json`;
caller-supplied JARs are not accepted. Before running, it requires exactly 64 provenance rows, four
source corpora, and 64 distinct persisted graph fingerprints. It then runs base and candidate
sequentially for independent cold and warm forks,
verifies the candidate against the reviewed oracle, invokes `compare-graph-id-pressure` for both
states, and publishes the trusted commit status only after both comparisons pass. Set
`GRAPHITE_PRESSURE_TIMEOUT_MILLIS` to override the default five-minute per-query timeout. Retain
both comparator reports plus the JMH JSON, observation TSV, correctness manifests,
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
