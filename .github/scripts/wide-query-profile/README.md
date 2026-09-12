# Multi-keyword wide-query diagnostics

This versioned adapter measures unmodified engine JARs against an independently
derived real-data oracle. It supplements the existing 34-query global-wide
comparison. It does not change that comparison, its frozen reference, any CI
threshold, or the requirement to pass all regression checks before accepting an
optimization. A completed diagnostic run is not an accepted performance change.

## Workload and statistical contract

The reference is starting main `4e328b0109e13c896b74004823fb049fcb19251a` with the
existing fixture64 corpus: Android 14, Tika 2.9.2, Hive 4.0.0 and Kotlin compiler
2.0.21, each split into 16 persisted graphs. These are 64 different shards, not
64 independent production applications. Never substitute synthetic graphs for
these measurements.

The v3 oracle contains 18 predicates expanded into 36 queries, each with ordinary and DISTINCT
four-property projection and `LIMIT 200`. Queries use unlabeled `MATCH (n)`;
every keyword searches the caller/callee class/name properties with
`toLower(coalesce(..., '')) CONTAINS`. AND applies to the same node. No graphId
filter preselects sources.

| Condition | Complete hit distribution before LIMIT |
| --- | --- |
| Two-keyword OR and AND | One graph, separately at positions 0, 31, 63 |
| Two-keyword OR | Two graphs, positions 0+63 and 0+31 |
| Two-keyword OR and AND | All 64 graphs |
| Four-keyword `(A AND B) OR (C AND D)` | Two graphs, positions 0+63 |
| Same four keywords as pure `A OR B OR C OR D` | 55 graphs in the frozen corpus |
| Four-keyword pure OR, separately selected terms | One graph at positions 0, 31, 63; two graphs at 0+63; all 64 graphs |
| Two-keyword AND | Zero; operands independently occur in different graphs |

The oracle records all 64 pre-LIMIT counts independently of returned rows.
It checks full values, order and source provenance, including every contributing
graph for selected DISTINCT tuples. A query that matches all 64 graphs may fill
its ordinary 200-row result from the first graph. That does not make it a
single-hit-graph predicate.

`run.py` starts a fresh JVM for each fork. Each fork runs all 36 queries in fixed
catalog order, clearing engine string indexes before every query. JIT and OS
page caches are not reset. The timer includes executor submission and waiting;
result serialization is outside it. Every raw observation and exact command is
retained. The original v1 catalog remains a strict 12-predicate/24-query contract;
v2 preserves those cases in order and appends the two pure four-keyword OR cases.
v3 preserves all 26 v2 queries and appends ten queries covering four-term OR
with single-graph early/middle/late, two-graph and all-graph hits. Every v3
four-term OR predicate records four positive exclusive-match counts: each
keyword contributes nodes that match none of the other three keywords.
Unknown versions and truncated catalogs are rejected. The adapter is compiled against the trusted reference JAR and the same
classes are used for all forks of a run.

Percentiles are calculated separately for each query, across forks. With fewer
than 20 observations, `empiricalP95LatencyNanos` is null. At 20 or more, it uses
nearest rank `ceil(0.95 * N)`. This is an empirical quantile, not a confidence or
stability guarantee. No combined percentile across unrelated queries is emitted.
The original suite's percentile across 34 different query cases remains a
separate historical comparison; increasing its JMH iterations alone overwrites
its raw TSV and does not provide the repeated-query evidence collected here.

## Prepare the reference

Use Java 17 and Python 3.11 or later. Build the WebGraph JMH JAR from a clean
checkout of the frozen revision. Record that checkout and build command with the
artifacts: these helpers check declared JAR hashes, not the Git origin of arbitrary
JAR bytes. Use absolute paths in the variables below. `PROFILE_DIR` is a fresh
artifact directory; `TOOLS` is this source directory.

First authenticate an existing fixture64 with its original four source JARs:

```bash
"$JAVA_BIN" -Xmx8g -XX:ActiveProcessorCount=4 \
  -Dandroid.jar.path="$ANDROID_JAR" -Dtika.jar.path="$TIKA_JAR" \
  -Dhive.jar.path="$HIVE_JAR" -Dkotlin.compiler.jar.path="$KOTLIN_JAR" \
  -cp "$REFERENCE_JAR" \
  io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation \
  --verify "$MANIFEST" "$PROVENANCE"
```

Compile the two independent exporters. The expected reference SHA256 must come
from the recorded trusted build. Exporting and deriving are setup work and must
finish before timed runs start.

```bash
mkdir -p "$PROFILE_DIR/classes"
"${JAVA_BIN%/*}/javac" -cp "$REFERENCE_JAR" -d "$PROFILE_DIR/classes" \
  "$TOOLS/ExportCallSites.java" "$TOOLS/VerifyNonCallSiteProperties.java"

"$JAVA_BIN" -Xmx3g -XX:ActiveProcessorCount=4 \
  -cp "$PROFILE_DIR/classes:$REFERENCE_JAR" ExportCallSites \
  --manifest "$MANIFEST" --provenance "$PROVENANCE" \
  --expected-jar-sha256 "$REFERENCE_SHA256" \
  --output "$PROFILE_DIR/callsites.tsv.gz" --receipt "$PROFILE_DIR/export.json"

"$JAVA_BIN" -Xmx2g -XX:ActiveProcessorCount=4 \
  -cp "$PROFILE_DIR/classes:$REFERENCE_JAR" VerifyNonCallSiteProperties \
  --manifest "$MANIFEST" --provenance "$PROVENANCE" \
  --expected-jar-sha256 "$REFERENCE_SHA256" \
  --output "$PROFILE_DIR/non-callsite-census.tsv" --receipt "$PROFILE_DIR/census.json"
```

The exporters verify pinned corpus identities, unique graph paths, persisted
CallSite index hashes and per-graph counts. The census rejects AnnotationNodes
that expose queried keys: they require a broader oracle. Other frozen-main node
property branches cannot match these four fields. Failed exports do not produce
a passing receipt; use fresh output paths after investigating any failure.

Take `EXPORT_SHA256` and `CENSUS_SHA256` from the successful export/census
receipts. For an already completed and independently verified export, its
recorded hashes can be used without another scan.

```bash
python3 "$TOOLS/derive.py" \
  --manifest "$MANIFEST" --provenance "$PROVENANCE" --jar "$REFERENCE_JAR" \
  --export "$PROFILE_DIR/callsites.tsv.gz" --census "$PROFILE_DIR/non-callsite-census.tsv" \
  --expected-jar-sha256 "$REFERENCE_SHA256" \
  --expected-export-sha256 "$EXPORT_SHA256" --expected-census-sha256 "$CENSUS_SHA256" \
  --output-dir "$PROFILE_DIR/oracle"
```

Derivation streams the actual exported CallSites. It chooses separate keywords
from the data, asserts the advertised hit distributions and rejects redundant
keywords. It writes `catalog.json`, full query text in `catalog.md`, and
`workloads.tsv`. The reference result never comes from executing Cypher.

## Run and inspect

Run without concurrent builds, exports, other benchmarks or profilers. Start
with one fork to validate the complete path. Use a new directory for each run;
existing artifacts are never overwritten or silently resumed.

```bash
python3 "$TOOLS/run.py" --java "$JAVA_BIN" \
  --trusted-jar "$REFERENCE_JAR" --jar "$RUNTIME_JAR" \
  --manifest "$MANIFEST" --catalog-dir "$PROFILE_DIR/oracle" \
  --output "$PROFILE_DIR/repeated-run" --forks 20
```

`run.json` contains input hashes, Java version, compiled adapter class hashes,
completed fork count, all per-query latency samples and empirical quantiles.
The launcher requires Java 17 and hashes every persisted graph file before and
after the run; it rejects changes even if `graphs.tsv` itself is unchanged.
This endpoint identity check supplements the prior corpus authentication; it
does not prove that files were never temporarily modified between checks.
Only `status=complete` means all requested forks passed independent correctness.
Failures preserve logs and partial observations with `status=failed`; they cannot
produce a successful diagnostic result. These observations do not independently
prove CPU/memory non-regression, thread-pool removal, or final 10x acceptance.
Keep the existing required method-level, end-to-end, resource and correctness
checks. Do not compare separately timed machines or use profiling overhead as an
optimization result.

The exact retained command for one fork can also be run with async-profiler
startup arguments and a new output prefix. Keep CPU and wall captures separate
on platforms that do not support simultaneous collection, align native method
traces to query IDs, and keep profiled captures separate from these unprofiled
repetitions. Inclusive samples overlap; sampled allocation weights and sums of
idle-thread wall time are not exact allocations or request latency.

Recheck a retained fork with:

```bash
python3 "$TOOLS/verify_run.py" --catalog "$PROFILE_DIR/oracle/catalog.json" \
  --workloads "$PROFILE_DIR/oracle/workloads.tsv" \
  --prefix "$PROFILE_DIR/repeated-run/fork-001"
python3 -m unittest discover -s "$TOOLS" -p 'test_*.py'
```
