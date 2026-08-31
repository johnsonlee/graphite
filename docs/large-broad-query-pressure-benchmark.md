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
real persisted graph paths. Synthetic graphs and repeated fixture paths are rejected and may only
be used outside this harness for correctness verification. Each graph also supplies reviewed terms
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
all workers share the request work/cancellation budget, and a worker failure cancels the remaining
tasks. `graphite-cypher-scan-N` names the separate cross-graph source pool and is not evidence that
a query already restricted to one graph used intra-graph parallelism.

During a real run, verify actual use in JFR/VisualVM by filtering for
`graphite-callsite-scan-` and checking that multiple workers are simultaneously runnable/on-CPU
during one selected-graph query. The benchmark's process-CPU-time / wall-time effective-core
ratio is the numeric utilization baseline; thread count alone is not sufficient.

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
selectivities, and both query-level graphId and API graph-parameter P50/P95 are each at least 10x
faster. It independently
rejects any API reference outside `zero=0`, `targeted=1..199`, and `dense=200`, so an all-zero
workload cannot pass. This prevents source pruning alone from satisfying the goal while the
already-selected single-graph API path remains slow.

The candidate in-process hard gate checks all routing results against the trusted external oracle.
The comparator additionally requires every candidate graphId result to match its API-selected
single-graph reference in selectivity, row count, response size, and SHA-256 digest, and requires
the API reference itself to match between main and candidate. Main's known-wrong graphId output is
retained only as the latency baseline and is never accepted as a correctness oracle. The observation
TSV contains one row per query with family, shape, selectivity, operator, clause boundary,
projection, limit, target graph, outcome, row count, response bytes, digest, and latency nanoseconds.
Repeat with warm forks; both cold and warm statuses are required evidence.

## Required external evidence check

The hosted GitHub runner does not contain the private 64-graph corpus. The normal
`benchmark-regression-gate` therefore depends on `graph-routing-pressure-evidence`, which fails
closed until the exact candidate commit has a trusted `graphite/real64-graph-routing` commit status.
The status is accepted only when it was published by `johnsonlee`, names the current base SHA,
links to an HTTPS evidence report, reports correctness pass, and records cold and warm P50/P95
speedups of at least 10x. A status attached to an older candidate or base cannot satisfy the gate.

After building base and candidate JMH jars with the identical benchmark harness and preparing the
reviewed oracle, run the repository-owned driver on the machine that has the 64 real graphs:

```bash
.github/scripts/run-real64-graph-routing.sh \
  /absolute/path/to/graphs.tsv \
  /absolute/path/to/reviewed-oracle.manifest \
  "$BASE_SHA" "$CANDIDATE_SHA" \
  https://github.com/johnsonlee/graphite/pull/109#issuecomment-EXAMPLE
```

The driver verifies both SHAs against GitHub, checks out detached worktrees for those exact commits,
copies the candidate-reviewed pressure harness byte-for-byte into the base worktree, and builds both
JMH JARs itself. It records the two commit SHAs plus SHA-256 for the harness, comparator, driver,
both built JARs, graph manifest, and correctness oracle in `provenance.json`; caller-supplied JARs
are not accepted. It then runs base and candidate sequentially for independent cold and warm forks,
verifies the candidate against the reviewed oracle, invokes `compare-graph-id-pressure` for both
states, and publishes the trusted commit status only after both comparisons pass. Set
`GRAPHITE_PRESSURE_TIMEOUT_MILLIS` to override the default five-minute per-query timeout. The linked
evidence report should retain both comparator reports plus the JMH JSON, observation TSV,
correctness manifests, `provenance.json`, JVM/host details, and raw CPU/heap/RSS/GC counters.

## Baseline metrics

JMH JSON exposes the suite and family P50/P95/max latency, timeouts, process CPU time and effective
core utilization, sampled peak process CPU load, heap before/peak/after, committed/max heap, RSS
before/peak/after, GC count/time, raw match-state bytes, and admitted/retained CallSite indexes.
Keep timeout samples in percentile calculation at the configured timeout. Report both overall and
selectivity/family percentiles, then diff every observation row by id so a fast family cannot hide
a regression in another shape.
