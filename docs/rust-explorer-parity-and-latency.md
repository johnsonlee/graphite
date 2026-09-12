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

## What the parity suite covers, and what it does not

The suite reports a single number ("116 passed"), and a number like that invites being
read as "everything checked out". It does not mean that. This section is the boundary,
so the count is never load-bearing for something it never touched.

### Covered

`rust/bench/parity.py` issues each request to both servers and compares **HTTP status and
response body**. JSON bodies are parsed and compared structurally after normalising
`loadedAt`, `builtAt` and `version`; everything else is compared as text.

**The OpenAPI document is the checklist, and the suite enforces it.** Every method and
path `/openapi.json` advertises must be exercised; a declared endpoint the suite never
calls fails the run. That check was added after comparing the two by hand revealed that
13 of the 30 declared method/path pairs had never been requested — the entire
cross-graph family, both `GET` spellings of the Cypher routes, resource body serving,
and all of registry mutation. A byte-identical OpenAPI document had made the surface
look covered; it only ever proved the document itself matched.

| | |
|---|---|
| Graph metadata | `/api/graphs`, `/api/graphs/{id}` — including a missing id and a malformed one |
| Topology | `/api/topology` |
| Overview | `/api/graphs/{id}/overview`, `/api/overview` |
| Nodes | `/api/graphs/{id}/node/{id}` — valid, out-of-range, non-numeric — plus `outgoing` and `incoming` |
| Subgraph | `/api/graphs/{id}/subgraph` — valid, missing `center`, invalid `direction` |
| Endpoints, resources, annotations | the `/api/graphs/{id}/…` forms, annotations both with and without `member` |
| C4 | `/api/graphs/{id}/architecture/c4` at **`level=context` only**, in all four formats, plus an invalid level and an invalid format |
| OpenAPI | `/openapi.json`, `/swagger.json` |
| Cypher | 37 queries, each sent to both `/api/graphs/{id}/cypher` and `/api/cypher` — 74 cases — plus the `GET ?query=` spelling of both, and `/api/cypher/graphs` in both methods |
| `/metrics` | The Prometheus exposition, compared structurally — see below |
| Cross-graph routes | `/api/annotations`, `/api/endpoints`, `/api/resources`, `/api/architecture/c4` |
| Resource bodies | `/api/resources/{path}` and `/api/graphs/{id}/resources/{path}` |
| Registry mutation | `PUT` and `POST /api/graphs/{id}` each walked through a full lifecycle — load, describe, list, reload with a bad path, reload with no path, a malformed id, unload, unload again, describe the absent graph |
| Web UI | `/`, `/index.html`, `/app.js`, `/ui-state.js`, `/style.css` — bytes, `Content-Type`, and `If-None-Match` → 304 |

### `/metrics` is compared structurally, not byte for byte

Both servers are started with `--metrics` and the exposition is parsed rather than
diffed as text, because two things about it can never match:

* Most of what the Kotlin server exposes describes a **JVM** — garbage collection, class
  loading, JIT compilation, Jetty's thread pool, `jvm_info`. Those families have no
  counterpart in a Rust binary. Only the `graphite_`-prefixed families are this port's
  to reproduce; `process_start_time_seconds` and `process_uptime_seconds` are also
  emitted, since they describe a process rather than a virtual machine.
* Every **value** is wall-clock or workload dependent. A count, a sum, an uptime differ
  between two processes by construction.

What is compared is everything a dashboard actually binds to: the set of `graphite_*`
families, each one's `TYPE` and `HELP` line, the full set of series including their
label sets, the histogram bucket boundaries, and whether each series renders as an
integer or a double. That last one is not pedantry — Micrometer writes `_bucket` and
`_count` through `writeLong` and everything else through `Double.toString`, so a correct
scrape mixes `2` and `0.0` in a way that looks like a bug and is not.

Finding this is what the check was for. The Rust server had been emitting the Cypher
timer as a `summary` with only `_count` and `_sum`, so every latency panel bound to
`_bucket` was empty against it, and the `_max` gauge family was missing outright. It now
emits the same histogram with the same service level objectives (10ms, 50ms, 100ms,
500ms, 1s, 5s, 30s, 2m) the Kotlin server configures through Micrometer.

One divergence is deliberate. Javalin answers `HEAD /metrics` with a bare `text/plain`,
dropping the `version=0.0.4; charset=utf-8` its own `GET` sends; the Rust server sends
the full type on both. The content type is therefore compared on the `GET`, which is
what a scraper issues.

### Not covered

Nothing below has been differentially tested. Some of it is not ported at all, some is
ported but unverified — the table says which. Neither is evidence of equivalence.

| | |
|---|---|
| **`graphite build`** | The `build` subcommand is **not ported at all** — it runs the SootUp bytecode analysis, which this port does not implement. `graphite serve` is not ported either; the Explorer ships as its own `graphite-explore` binary. |
| **The Explorer's own CLI** | Flags are one-for-one with the Kotlin binary and were exercised by hand, but no automated check compares them. `--help`, `--version` and error text are known to differ: picocli and clap format differently. |
| **C4 container and component levels** | Only `level=context` is compared. The other two levels produce diagram layout, which was never brought to parity. |
| **API response headers** | For every route except the five UI assets, only status and body are compared. Content types, cache headers and error-response headers on the JSON API are unchecked. |
| **`TopologyStore`'s binary snapshot** | The persisted format is not read by the Rust server. |
| **Corpus breadth** | Everything runs against one graph built from the `graphite-explore` shadow jar. A second corpus could surface encoding paths this one never exercises. |
| **Concurrency** | Every request is issued serially. Behaviour under concurrent load, and the request guard's queuing and timeout behaviour, are not compared. |

### `graphite query` has its own byte-level suite

`rust/bench/parity-cli.py` runs both CLIs and compares **raw stdout, raw stderr and the
exit code**, byte for byte — not parsed output. That is the only way to check what a
command-line tool actually promises: column widths and padding, Gson's escaping, and the
exact error text a script might match on. 148 checks: 28 queries across all five format
spellings (`text`, `json`, `csv`, `CSV`, and an unrecognised one, which falls through to
the table), plus the flag forms, the verbose lines, and the two directory failures.

It reaches things the HTTP suite structurally cannot:

- **Gson's HTML escaping.** `<init>` serialises as `"\u003cinit\u003e"`. The HTTP suite
  parses JSON before comparing, so it can never see this; a byte comparison can.
- **`toString()` rather than JSON.** A node prints `line=null` in the table and has no
  `line` key in the JSON of the same query, because Gson omits nulls and `toString()`
  does not. A relationship is not a map at all: it prints as the Kotlin data class,
  `DataFlowEdge(from=node#4101, to=node#4102, kind=PARAMETER_PASS)`, and serialises to
  that class's declared fields — no `type` key, unlike the HTTP API's relationship shape.
- **Table geometry.** Column widths are `max(header, widest value, 4)`, measured in
  UTF-16 code units, and every column including the last is padded, so trailing spaces
  are part of the output.

Two divergences are accepted rather than reproduced, both artefacts of the baseline:

- **`CREATE` leaks a stack trace.** Kotlin's `NotImplementedError` is an `Error`, so it
  escapes the command's `catch (e: Exception)`; the JVM prints a stack trace and mangles
  the message's em-dash through the platform encoding. Only the exit code is compared.
- **Unconstrained relationship traversal is not deterministic.** `MATCH (a)-[r]->(b)
  RETURN r LIMIT 2` returns different edges on consecutive runs *of the baseline itself*,
  so it is no oracle for a byte comparison. The suite pins the source node and orders the
  result.

### The 64-graph run is a separate, narrower check

`fixture64.py` compares a SHA-256 over ordered columns and rows for its 34 benchmark
queries, across 64 graphs, on `/api/cypher` only. It is evidence about cross-graph query
results and nothing else — not about any other route, and not about response shape
beyond columns and rows.

### Known accepted deviations

These are differences that exist and are not treated as failures:

- On the single-graph route the Kotlin server tags some rows with
  `{"$metadata": {"graphIds": ["single"]}}`. `"single"` is an internal placeholder, not
  the id of any loaded graph, and it appears only for query shapes that take an
  index-assisted path. The suite strips that exact value and reports how often it fired.
- Three response headers on the UI assets: Kotlin also sends `Accept-Ranges: bytes` and
  `Last-Modified`, and chunks the response where the Rust server sends `Content-Length`.
  None affects what the browser renders.
- The benchmark's parameterized query shape is run in its literal form, because the HTTP
  API takes no query parameters.
- **The HTTP API's JSON is not byte-identical.** The Kotlin server serialises through
  `GsonBuilder().setPrettyPrinting().create()`, so it HTML-escapes `<`, `>`, `&`, `=` and
  `'`; the Rust server emits those characters raw. Parsed responses are identical, which
  is why 116 structural checks never saw it — it was found while porting the CLI, whose
  output *is* compared byte for byte. `graphite_cypher::gson` now implements the Gson
  form and the CLI uses it; the server does not yet.

## Method

Both servers were started on the same graph (built from the `graphite-explore` shadow
jar with the current `graphite build`) and driven by `rust/bench/bench.py`. Each
scenario is warmed up 5 times, then measured over 25 requests.

The web UI is compiled into the binary with `include_str!` from `rust/graphite-explore-rs/web/`,
which holds byte-identical copies of `graphite-explore/src/main/resources/web/`. The
served bytes, the `Content-Type`, and the `If-None-Match` → 304 revalidation are all
compared. Three header deviations remain, all from Ktor's static-file serving and none
affecting what the browser renders: Kotlin also sends `Accept-Ranges: bytes` and
`Last-Modified`, and chunks the response where the Rust server sends `Content-Length`.

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

## Parity results

All **150** checks pass. What they do and do not reach is set out under "What the parity
suite covers, and what it does not" above; this section records only the findings.

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
reduction and layer splitting are not ported. **Those two levels are not requested by
the suite at all** — not even for status — so nothing here is evidence about them.

**Persisted topology snapshots.** `TopologyStore`'s binary snapshot format is not
ported. `/api/topology` is served from live registry state instead.

## Reproducing

```bash
cd rust && cargo build --release

java -Xmx6g -jar ../graphite-explore/build/libs/graphite-explore.jar \
    --id app /path/to/graph --port 18081
./target/release/graphite-explore --id app /path/to/graph --port 18080 --metrics

cd bench
python3 parity.py                       # 150 differential checks
python3 bench.py --warmup 5 --iters 25  # single-graph latency comparison
```
