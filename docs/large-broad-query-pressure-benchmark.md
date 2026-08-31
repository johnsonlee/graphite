# 64-graph broad-query pressure benchmark

`LargeBroadQueryPressureBenchmark` measures the full replay distribution of broad node-property
searches across 64 mapped graph handles under an exact `-Xmx8g` cap. The number of queries is a
result of the coverage matrix, not a target: 32 non-routing shapes are crossed with zero-hit,
targeted, and dense selectivity, while three graphId spellings cover every graph in the manifest.
This produces 288 queries today.

The matrix borrows the useful taxonomy from the openCypher TCK, but not the TCK datasets or its
pass criteria. The TCK is a correctness suite over small scenario graphs. This pressure benchmark
selects only clause and expression boundaries that change search, retention, merge, or
materialization cost:

- literal and parameterized `CONTAINS`, `STARTS WITH`, `ENDS WITH`, regular expression, `=`, and `IN`;
- `AND`, `OR`, `AND NOT`, and mixed exact/substring predicates;
- raw and `toLower(coalesce(...))` property access;
- literal, function, and parameterized `graphId` routing combined with wrapped broad search;
- node, property, `keys`, graph provenance, `DISTINCT`, and aggregate projection;
- `ORDER BY`, `SKIP`, small and large `LIMIT`, and full-match aggregation;
- zero-hit, targeted, and dense terms for every shape.

Relationship traversal, variable-length paths, and write clauses are intentionally separate from
this node-property wide-search distribution. Mixing them into the same P50/P95 would make a change
in topology traversal look like a regression in broad search.

## Graph input

Performance runs require a tab-separated manifest with exactly 64 unique graph ids and 64 distinct
real persisted graph paths. Synthetic graphs and repeated fixture paths are rejected and may only
be used outside this harness for correctness verification. Each line is
`<graph-id><TAB><absolute-persisted-graph-path>`:

```text
app-000	/graphs/app-000
app-001	/graphs/app-001
```

Set it on the fork with
`-Dgraphite.broad.pressure.graphs=/absolute/path/to/graphs.tsv`. The harness rejects a manifest
whose entry count differs from `graphCount` or whose ids are duplicated.

## Correctness hard gate

Performance results are invalid unless correctness verification passes. The benchmark defaults to
`verify` mode and refuses to start without a correctness oracle. The oracle must contain every
query selected by `coverageFamily`, every record must have outcome `success`, and query ids must be
unique. During warmup and measurement the gate compares, per query:

- family, shape, selectivity, operator, clause boundary, projection, and limit;
- outcome, complete row count, and serialized response size;
- SHA-256 over complete columns, ordered rows, values, and graph provenance.

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
jar. Run revisions sequentially on the same idle machine. Run each family as a separate shard:

```bash
FAMILIES='contains boolean exact wrapped projection aggregation global regex graph-id'
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

## graphId 10x gate

The `graph-id` family contains 192 queries: every one of the 64 graph ids is exercised once through
each of the property, `graphId(n)`, and parameterized spellings. Zero-hit, targeted, and dense terms
are rotated across graph and spelling so every dimension is represented without making query count
the goal. The queries preserve the production non-`DISTINCT`, four-property wrapped `CONTAINS`, and
`LIMIT 200` shape.

Run `main` in record mode to capture its performance observations even if its graph-qualified
result is known to be semantically wrong, and run the candidate in verify mode against an
independently reviewed correctness oracle. Candidate timing is invalid unless oracle verification
passes. Then enforce the gate for cold and warm forks separately:

```bash
node .github/scripts/benchmark-gate.mjs compare-graph-id-pressure \
  --base results/main-graph-id-cold.json \
  --candidate results/candidate-graph-id-cold.json \
  --base-observations results/main-graph-id-cold.tsv \
  --candidate-observations results/candidate-graph-id-cold.tsv \
  --minimum-speedup 10 \
  --report results/graph-id-cold-report.md \
  --status results/graph-id-cold-status.json
```

The comparator fails unless both revisions report exactly 64 distinct graph paths, all 192 queries
complete without timeout/failure, every manifest graph id has all three spellings, and candidate
P50 and P95 are each at least 10x faster. Repeat with the warm result files; both statuses are
required evidence.

The in-process hard gate checks each revision against the same trusted oracle before its score is
accepted. As an additional audit artifact, retain the emitted base and candidate manifests and
require them to be byte-identical. The observation TSV contains one row per query with family,
shape, selectivity, operator, clause boundary, projection, limit, outcome, row count, response
bytes, and latency nanoseconds.

## Baseline metrics

JMH JSON exposes the suite and family P50/P95/max latency, timeouts, process CPU time and effective
core utilization, sampled peak process CPU load, heap before/peak/after, committed/max heap, RSS
before/peak/after, GC count/time, raw match-state bytes, and admitted/retained CallSite indexes.
Keep timeout samples in percentile calculation at the configured timeout. Report both overall and
selectivity/family percentiles, then diff every observation row by id so a fast family cannot hide
a regression in another shape.
