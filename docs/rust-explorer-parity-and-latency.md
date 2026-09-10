# Rust Explorer: parity, and a single-graph latency comparison

A Rust reimplementation of `graphite-explore`, compared against the Kotlin server on
`main`. Both read the same graph directory and serve the same HTTP API.

## What this does and does not measure

**This is not the repository's P95 baseline.** That baseline is
`LargeBroadQueryPressureBenchmark`: 64 graphs sharded from android-all, tika, hive and
kotlin-compiler (about 19M nodes), replayed single-threaded, cold, with no warmup, with
P95 taken *across queries* by `.github/scripts/benchmark-gate.mjs` outside the JVM.

What follows is a different measurement: **one** graph of 1,068,299 nodes, driven over
HTTP, with P95 taken across *repeated requests* to the same endpoint after warmup. It
therefore says nothing about the cross-graph execution path (`CrossGraphCypherExecutor`,
graph routing, provenance merging), which 64 graphs exercise and one graph does not.

Treat the numbers below as "how fast is a warm single-graph request", not as a result
against the project's gate. The 64-graph comparison has since been run separately: on
the real baseline the Rust port reaches a P95 16.7x lower than Kotlin's, with all
results byte-identical. See
[rust-explorer-fixture64-p95.md](rust-explorer-fixture64-p95.md) — that document, not
this one, is the result against the gate.

## Method

Both servers were started on the same graph (built from the `graphite-explore` shadow
jar with the current `graphite build`) and driven by `rust/bench/bench.py`. Each
scenario is warmed up 5 times, then measured over 25 requests.

Correctness is enforced before timing: every scenario compares status and row count
across the two servers, and a mismatch fails the run.

## Results

Hardware: 4 vCPU, 15 GiB. Kotlin on OpenJDK 21 with `-Xmx6g`; Rust built `--release`.

The honest summary is the middle of this distribution, not its tail:

| | |
|---|---:|
| **Median scenario speedup** | **2.71x** |
| Slowest scenario relative to Kotlin | 0.66x |
| Scenarios slower than Kotlin | 6 of 23 |
| Correctness mismatches | 0 |

| Scenario | Kotlin P95 | Rust P95 | Speedup |
|----------|-----------:|---------:|--------:|
| `exact-name-match` | 2.91 ms | 4.41 ms | 0.66x |
| `method-discovery` | 1.43 ms | 1.91 ms | 0.75x |
| `suffix-scan` | 2.63 ms | 3.37 ms | 0.78x |
| `rest-subgraph-depth2` | 0.63 ms | 0.77 ms | 0.82x |
| `prefix-package-scan` | 2.08 ms | 2.32 ms | 0.89x |
| `rest-graphs` | 0.65 ms | 0.68 ms | 0.96x |
| `rest-overview` | 12.11 ms | 11.20 ms | 1.08x |
| `node-scan-limit` | 1.91 ms | 1.60 ms | 1.19x |
| `single-hop-relationship` | 2.44 ms | 1.76 ms | 1.39x |
| `rest-node` | 0.90 ms | 0.56 ms | 1.60x |
| `zero-hit-term` | 38.43 ms | 22.20 ms | 1.73x |
| `rest-node-outgoing` | 1.95 ms | 0.72 ms | 2.71x |
| `ordered-distinct-limit` | 37.93 ms | 8.49 ms | 4.47x |
| `global-wide-class-pair` | 32.92 ms | 7.15 ms | 4.60x |
| `global-wide-four-properties` | 50.27 ms | 7.90 ms | 6.37x |
| `dense-term-get` | 36.87 ms | 4.54 ms | 8.12x |
| `global-wide-callee-class` | 141.59 ms | 9.10 ms | 15.55x |
| `wrapped-case-insensitive` | 214.20 ms | 8.88 ms | 24.13x |
| `group-by-callee-class` | 889.82 ms | 24.57 ms | 36.21x |
| `rest-endpoints` | 94.92 ms | 0.84 ms | 112.87x |
| `count-star` | 601.91 ms | 0.68 ms | 885.16x |
| `rest-c4-context-json` | 1241.54 ms | 0.99 ms | 1252.82x |
| `rest-c4-context-mermaid` | 1280.08 ms | 0.81 ms | 1590.16x |

### Why the aggregate figures are not quoted as the headline

An earlier version of this document led with "aggregate P95 speedup: 56x",
computed as the P95 across the per-scenario P95s. With 23 scenarios that statistic is
just the second-slowest scenario, so it moves with scenario selection rather than with
the code. During this work it read 38x, then 3.07x after two C4 scenarios were added,
then 56x after those scenarios were cached. A number that swings that far on
which rows are in the table should not be a headline, and quoting it as one overstated
the result.

The two C4 rows are also a caching win rather than a compute win: the model is cached
because a loaded graph is immutable, while the baseline recomputes it per request. The
underlying inference is 3.6x faster, at 329 ms cold against 1,171 ms.

### Where the time went

Four changes account for most of the gain on the scenarios that improved.

**Dictionary-first string search.** String properties are dictionary-encoded, so a
`CONTAINS` predicate has at most as many distinct answers as there are dictionary
entries: 85,188 here against 1,068,299 nodes. The predicate is evaluated against the
dictionary once to build a bitset of matching string ids; the node sweep then reads
four raw integers per record and tests bits, decoding nothing. Equality and prefix
predicates skip the scan entirely, because the dictionary is sorted and their matches
form a contiguous range reachable by binary search. The plan is only a pre-filter:
survivors are re-checked against the full `WHERE` clause, so an imprecise plan can
change performance but never results.

**Counting from the type index.** `count(*)` over a label is the size of a type-index
range. The server appends a `LIMIT` to every query, so the fast path has to recognise
the shape *with* that clause or it never fires.

**Grouping and DISTINCT on raw string ids.** Aggregation stays in integers, and because
the dictionary is sorted, ordering by the property is ordering by id.

**Skipping work that cannot match.** Endpoint discovery resolves the Spring annotation
names against the dictionary once; if none are present, no method can be an endpoint.

None of these depend on Rust. They are index and encoding choices that the Kotlin
implementation could adopt. `GRAPHITE_NO_FASTPATH=1` disables all of them, so the
runtime's contribution can be measured separately from the query strategy's; the
scenarios that hit no fast path at all (single node lookup, edge listing, one-hop
traversal, method listing) range from 0.75x to 2.7x, which is the scale of the
language-and-runtime difference on its own.

## Parity

`rust/bench/parity.py` issues 101 requests against both servers and compares status and
normalised body: registry and topology routes, node, edge and subgraph lookups,
overview, endpoints, resources, annotations, C4 in all four formats, the OpenAPI
document, and 37 Cypher queries spanning scans, filters, aggregation, ordering,
traversal, expressions, and error cases. All 101 match. Timestamps, absolute paths and
the build version are normalised, since they identify the build rather than its
behaviour.

Two baseline behaviours are reproduced deliberately rather than "fixed". `ORDER BY
n.value` after a `RETURN DISTINCT n.value` does not sort, because the sort key resolves
to null against the projected row. And `ORDER BY count(*)` is rejected as an inline
aggregation.

### Known divergences

**Internal `"single"` graph id.** On the single-graph route the Kotlin server tags some
rows with `{"$metadata": {"graphIds": ["single"]}}`. `single` is an internal
placeholder (`QueryPipeline.SINGLE_GRAPH_ID`), not the id of any loaded graph: the
graph in the harness is `app`. It also appears only for query shapes that take an
index-assisted path. The Rust server emits no provenance on a single-graph route. The
harness strips that exact placeholder and reports how many rows carried it.

**C4 container and component levels.** The context level matches byte-for-byte in JSON,
Mermaid, PlantUML and Structurizr DSL. The container and component levels produce the
same elements but not the same diagram layout: their edge-selection caps, fan-in
reduction and layer splitting are not ported. Those levels are checked for status only.

**Persisted topology snapshots.** `TopologyStore`'s binary snapshot format is not
ported. `/api/topology` is served from live registry state instead.

## Reproducing

```bash
cd rust && cargo build --release

java -Xmx6g -jar ../graphite-explore/build/libs/graphite-explore.jar \
    --id app /path/to/graph --port 18081
./target/release/graphite-explore --id app /path/to/graph --port 18080 --metrics

cd bench
python3 parity.py                       # 101 differential checks
python3 bench.py --warmup 5 --iters 25  # single-graph latency comparison
```
