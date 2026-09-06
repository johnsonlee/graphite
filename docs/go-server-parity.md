# Go server parity contract and verification inventory

This is a requirements inventory, not a completion claim. The goal is a native Go
replacement for the Graphite server with 100% observable functionality preserved
and HTTP P95 latency at most one tenth of `main` on representative real persisted
graphs. A JVM proxy, finite query template recognizer, synthetic performance
fixture, or passing subset of the following matrix does not meet that goal.

## Revision identity

Inspected on 2026-09-07:

| Reference | Exact revision | Meaning |
| --- | --- | --- |
| Local `main` | `44b57562f2b3d0c88882a9002bdc488e05e5d7a7` | Stale local branch; differs substantially from present server |
| Local `origin/main` | `4e328b0109e13c896b74004823fb049fcb19251a` | Remote-tracking baseline; independently confirmed by `git ls-remote origin refs/heads/main` during this task |
| Starting worktree HEAD | `599a3ece` | Production sources under explore, cypher, and webgraph match the inspected `origin/main`; two test files differ |

Pin the resolved remote `main` commit, fixture manifests, candidate commit, runtime,
and invocation in benchmark receipts. Do not quietly benchmark the stale local
`main` branch. The concrete source and test references below describe the inspected
`origin/main` server. Exact status bodies and serialization are defined by source
and must be checked with a running pinned baseline, including framework-generated
errors. The inventory must expand if further reachable behavior is discovered.

## HTTP routes and response contract

`S` below means `/api/graphs/{graphId}`. Production CLI startup uses a registry even
with one initial graph. Internal standalone registration also serves root
graph-bound routes, uses ID `standalone`, and has no graph mutations or selected
graph query route. Root node-ID routes are deliberately absent in both modes.
Success is 200 unless stated otherwise. JSON envelopes, array order, scalar types,
omitted null fields, escaping, and error text require differential checking.

| Method and path | Request and successful response | Error / boundary cases | Source and test pointers |
| --- | --- | --- | --- |
| GET `/api/graphs` | Registry `{data,loadMode,count,totals,graphs}`; descriptors `{id,path,loadMode,loadedAt,nodes,edges,methods,callSites}`; totals use same four counts; ID-sorted catalog. Standalone omits registry fields/path/time/mode. | Empty catalog valid; cached counts must reflect replacement | `GraphRegistry.kt`; `ExploreCommandTest`: cached stats, empty startup, hot update |
| GET `S` | `{graph: descriptor}` | 400 invalid ID; 404 `{error:"Graph not loaded: <id>"}` | `ExploreRoutes.registerRegistryRoutes`, static variant |
| PUT / POST `S` | Body `{path,loadMode?}`, or query fields with blank body; default configured mode; `{graph: descriptor}` | 400 load/parse/topology errors; relative path resolved against data directory; replacement supported | `ExploreCommandTest`: loading, replacement rollback, acquire gap |
| DELETE `S` | 204, unload from registry and rebuild topology | 404 missing ID; 400 invalid ID/rebuild error; rollback on rebuild failure | `ExploreCommandTest`: registry unload rollback, retired leases |
| GET `/api/topology` | `{nodes,edges,graphCount,relationCount,matchedRows,builtAt,rules,stale}`; topology node `{id,graphId,type:"Graph",label,...stats}`; edge `{from,to,type:"TopologyCall",protocol,weight,operations,evidence}` | Snapshot remains valid through reload/unload; mapped stream content length and lease lifetime | `TopologyGraph.kt`, `TopologyStore.kt`; `TopologyGraphTest`, `TopologyStoreTest` |
| GET `S/node/{id}` | `Helpers.nodeToMap` node type-specific fields | 400 plain `Invalid node ID`; 404 plain `Node not found`; graph validation/404 precedes node lookup | `ExploreCommandTest`: node routes; `HelpersTest` |
| GET `S/node/{id}/outgoing`, `/incoming` | Array of `Helpers.edgeToMap`; limit default 200, max 2000 | Invalid ID 400; absent/isolated node yields empty edges; limits clamp | `ExploreCommandTest`: incoming/outgoing |
| GET `S/subgraph` | Required integer `center`; depth default 2, maximum 4; direction `both` default, `outgoing`, `incoming`; `{nodes,edges}` | 400 missing/invalid center or invalid direction; absent center empty; max 2000 nodes/5000 edges; preserve traversal order, duplicate-edge behavior, and negative-depth baseline behavior | `ExploreRoutes.buildSubgraph`; `ExploreCommandTest`: subgraph and traversal caps |
| GET `/api/annotations`, `S/annotations` | Required `class`, `member`; scoped annotations map; registry root grouped envelope | Missing field 400 plain `Missing '<name>' parameter`; unknown member empty | `ExploreCommandTest`: annotation cases |
| GET `/api/resources`, `S/resources` | `pattern` glob default `**`; limit default 100, max 1000; scoped `{pattern,limit,count,resources:[{path,source,derived:false}]}`; root grouped | 409 JSON if legacy resource store unavailable; empty present store succeeds; root fails if any selected store unavailable | `PersistedResourceAccessor.kt`; `ExploreCommandTest`: real JAR resources, missing legacy store |
| GET `/api/resources/<path>`, `S/resources/<path>` | Nested path; scoped `{path,source,derived:false,size,content}` with UTF-8 replacement semantics; root groups matching resources | 404 plain missing path/resource; 413 plain oversized resource; 1,048,576 bytes total across root graphs; 409 unavailable store | `ExploreCommandTest`: resource nested path, payload cap |
| GET `/api/endpoints`, `S/endpoints` | Spring annotations; exact optional `class` filter; default limit 200/max 2000; scoped `{framework:"spring-web",count,endpoints}`; root grouped | Annotation arrays/iterables, class/method paths, verbs/defaults, endpoint fields must match extractor | `EndpointExtractor.kt`; `ExploreCommandTest`: all HTTP mapping branches |
| GET `/api/overview`, `S/overview` | Default limit 200/max 1000; scoped `{nodes,edges}`; class node stable class-name ID, `type:"Class"`, `label`, `fullName`, `callSites`; weighted `Call` edges; root grouped | Indexed and fallback paths agree; fallback caps 100000 call sites, 20000 classes, 50000 dependency keys | `ExploreRoutes.buildClassOverview`; `ExploreCommandTest`: overview |
| GET `/api/architecture/c4`, `S/architecture/c4` | `level=all|context|container|component`; formats `json|mermaid|plantuml|dsl`; structured workspace and rendered diagrams; root grouped JSON or graph-comment-separated text | 400 JSON `{error,allowed}` invalid level/format; recognized Accept takes precedence over query format | `cli/c4/*`; `C4InferenceTest`; extensive `ExploreCommandTest` C4 cases |
| GET / POST `S/cypher` | GET query field; POST JSON `query` with URL fallback; URL `limit` default 1000/max 5000; `{columns,rows,rowCount}` | Missing query 400 plain; graph missing 404 JSON; Cypher errors below | `ExploreRoutes.executeSingleGraphCypher`; `ExploreCommandTest` Cypher cases |
| GET / POST `/api/cypher` | Same query resolution, trim nonblank query, URL limit; one global cross-graph execution; `{columns,rows,rowCount,graphCount}` | Empty catalog still executes graph-independent expressions; global aggregation, provenance, order and limit | `CrossGraphCypherExecutorTest`; `ExploreCommandTest`: root surface |
| GET / POST `/api/cypher/graphs` | Exactly one of nonempty `graphs`/`graph` or `allGraphs=true`; JSON array/string, repeated URL fields and comma splitting; default `mode=cross-graph`; body limit supported | 400 duplicate IDs, invalid selection/mode; 404 selected unloaded graph; source scope checked | `ExploreRoutes.parseMultiGraphCypherRequest`; `ExploreCommandTest`: fans out, caps rows |
| GET `/openapi.json`, `/swagger.json` | Same OpenAPI document, schemas, examples, paths, server version | Preserve alias; compare specification as data, not just route presence | `OpenApiSpecBuilder.kt`, `GraphiteVersionProvider.kt`; `ExploreCommandTest`: specs |
| GET `/`, `/index.html`, `/app.js`, `/ui-state.js`, `/style.css` | Existing embedded web UI and referenced assets | Browser UI behavior must stay functional, including graph selection, Cypher cancellation and C4/topology | `src/main/resources/web/*`; `src/test/js/ui-state.test.js` |
| GET `/metrics` | Only enabled with `--metrics`; Prometheus text 0.0.4 | Disabled route absent; bounded route labels, active/limit/rejected gauges/counters, query outcome timers, HTTP timings and process/runtime metrics | `ServerPerformanceMetrics.kt`; `ServerPerformanceMetricsTest` |
| Legacy discovery and root node-ID paths | `/api/nodes`, `/api/call-sites`, `/api/methods` and their scoped variants, plus `/api/node/...` and `/api/subgraph`, remain unavailable | Preserve 404 behavior; do not recreate removed discovery APIs | `ExploreCommandTest`: legacy discovery unavailable; graph-local ID routes |

Registry root grouped envelope is
`{graphCount,resultGraphCount,results:[{graphId,data}]}`. Root listing limits divide
the total by graph count and give remainder slots to the first graphs; they are
not independent full limits or adaptive spillover limits. Selected graph order
follows request order; all-graph order follows sorted IDs. Integer route limits
default on parse failure and clamp into `[0,max]`.

Selected cross-graph response is
`{mode,graphs:[ids],graphCount,columns,rows,rowCount,limit}`. In cross-graph mode,
`perGraphLimit` and `includeGraphRows=true` are invalid. Fanout response additionally
uses `queriedGraphCount`, `perGraphLimit`, `truncated`, and graph descriptors with
per-graph columns/counts and optional rows. Fanout defaults per-graph limit to
`ceil(totalLimit / graphCount)` and spends the remaining total limit in order;
it injects/overwrites `graphId` in every row. Mode aliases are `cross_graph`,
`crossgraph`, `fan-out`, `fan_out`; true values are `true`, `1`, `yes`, `on`.

C4 content types: scoped JSON `application/vnd.structurizr+json`, root JSON
`application/json`, `text/vnd.mermaid`, `text/vnd.plantuml`, and
`text/vnd.structurizr.dsl`, with UTF-8. Full artifact/manifest/annotation-based
inference, external-system classification, component/container clustering,
relationship evidence, stable IDs, diagram cropping and all renderer escaping
are required. A generic empty architecture model is not parity.

## CLI and lifecycle

| Surface | Required behavior | Authoritative checks |
| --- | --- | --- |
| Entry point | `graphite-explore [graphDir]`, standard `-h/--help`, `-V/--version`; underlying command class called `serve` | `ExploreMain.kt`, `ExploreCommand.kt`, command tests |
| Startup mapping | Optional positional graph requires `--id`; repeatable `--graph=id:path`, split first colon; duplicate initial IDs fail; `--data` required for empty startup | Command validation tests |
| Defaults | `--port/-p=8080`, `--load-mode=MAPPED`, `--max-concurrent-cypher=4`, `--cypher-max-timeout-ms=60000`, metrics disabled | Command tests; guard constants |
| Compatibility option | `--cypher-work-budget` accepted, deprecated, ignored, including nonpositive values; concurrency and maximum timeout must be positive | `serve rejects non-positive active cypher limits and ignores deprecated work budget` |
| Paths and IDs | Create normalized absolute data root; relative graph path resolved against it; ID trim then `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`; absolute paths supported; no invented data-root confinement | `GraphRegistry.validateGraphId`, `resolveGraphPath` |
| Startup failure | Invalid load or topology fails before serving, error on stderr and exit 1; close already-open resources | Command tests |
| Hot replacement | Atomic catalog replacement, topology rebuild in stable catalog transaction, rollback on failure, no acquire gap | Registry concurrency/rollback tests |
| Leases | Retired graphs stay readable until final checked-out lease closes; partial acquireAll failure releases prior leases; idempotent close; streaming and serialization retain lease | Registry and cancellation contract tests |
| Timeout | Request `timeoutMs` positive integer from JSON before URL; effective deadline bounded by server maximum; client timeout cannot increase maximum | `CypherQueryGuardTest`, `CypherCancellationContractTest` |
| Concurrency | Shared bounded admission; capacity returned on all terminal paths; no permit leaks on rejected execution, cancellation, serialization failure, or shutdown | `CypherQueryGuardTest`, `CypherClientCancellationTest` |
| Shutdown/disconnect | Cancel running work, stop admission, stop work during graph scans, regex, aggregation and response serialization; do not write normal success after cancellation; close topology/registry/metrics/server | Cancellation tests and `CypherResponseSerializer.kt` |
| Topology queries | File or sorted immediate `.cypher` directory; no empty queries; query rows require valid loaded source/target; optional protocol/operation/weight/evidence; aggregate relations, capped details, persist snapshots and reuse valid snapshots | `TopologyGraphTest`, `TopologyStoreTest` |

Cypher errors have JSON `{error,code}`. Codes and status: `cypher_query_failed` 400,
`cypher_concurrency_limit` 429 plus `Retry-After: 1`,
`cypher_work_budget_exceeded` 429, `cypher_query_timeout` 504 plus effective
`timeoutMs`, `cypher_query_cancelled` 503. Missing graph is 404 `{error}`.
Cross-graph result rows contain `$metadata:{graphIds:[...]}` provenance, including
an empty array for graph-independent expressions. Gson omits null-valued object
fields while preserving null elements in arrays. Client disconnect does not require an error to be written to the disconnected
socket. Successful response requires numeric `rowCount`; cancellation covers JSON
serialization as well as execution. Parse failures and unsupported operations
must match actual baseline responses, not merely produce an empty result.

## Cypher language and value semantics

The contract is the supported language of the pinned implementation, not all
Neo4j functionality. Port the complete grammar/AST and execution behavior; do not
infer the language from benchmark strings or only `CypherCompatibilityTest`.

| Area | Required coverage | Existing test classes under `graphite-cypher/src/test/kotlin/io/johnsonlee/graphite/cypher` |
| --- | --- | --- |
| Lexer/parser | Quoted/backtick identifiers, escaped strings, comments, case-insensitive keywords, keyword properties, decimal/hex/octal/exponent literals, parameters, precedence, nested AST, scripts/semicolons | `CypherDslAdapterTest`, `ParsedQueryTest`, `CypherCompatibilityTest`, `CypherParametersTest` |
| Patterns | Untyped/typed nodes, intersecting labels and aliases, property maps, repeated bindings, directed/reverse/undirected edges, multiple edge types, edge property filters, multi-pattern joins, variable-length bounds including zero/unbounded, named paths and relationship uniqueness | `CypherExecutorTest`, `CypherLabelSemanticsTest`, `PropertyFilterTest`, `PathFinderTest`, `CypherReviewRegressionTest` |
| Clauses | MATCH, OPTIONAL MATCH with attached WHERE and null filling, standalone WHERE, RETURN/WITH/star/aliases/DISTINCT, WITH WHERE, UNWIND, ORDER BY/multi-key/direction, SKIP, LIMIT, UNION/UNION ALL and script behavior | `QueryPipelineTest`, `CypherCompatibilityTest`, `CypherExecutorTest` |
| Expressions | Arithmetic/unary/power, string/list operations, comparison and null truth tables, short circuit, regex including Java-compatible constructs, IN, CASE, maps/lists, indexing/slicing, comprehensions, ANY/ALL/NONE/SINGLE predicates | `ExpressionEvaluatorTest`, `CypherValueSemanticsTest`, `CypherReviewRegressionTest` |
| Aggregation | count/star/distinct, sum, avg, min, max, collect, percentileCont, percentileDisc, stDev, stDevP; grouping/nested expressions, nulls, empty groups, ordering after projection | `CypherFunctionsTest`, `QueryPipelineTest`, `CypherExecutorTest` |
| Scalar functions | id, elementId/qualifiedId, graphId, coalesce, timestamp, toInteger/toInt, toFloat, toBoolean, toString, properties, keys, labels, type; toLower/toLowerCase, toUpper/toUpperCase, trim/ltrim/rtrim, replace, substring, split, size/length, left/right/reverse; head/tail/last/range, nodes/relationships; abs/ceil/floor/round/sign/rand/sqrt/exp/log/log10/e/sin/cos/tan/asin/acos/atan/atan2/cot/pi/degrees/radians; exists | `CypherFunctionsTest`, `ExpressionEvaluatorTest`; `CypherFunctions.evaluate` authoritative |
| Native values | Every Node subtype, graph properties, relationship properties, paths, constants, annotations, resources, field/parameter/local/return data, numeric coercion, null serialization, maps/lists and equality/order/dedup | `NodePropertyAccessorTest`, `CypherValueSemanticsTest`, `CypherFunctionsTest` |
| Method virtual source | `Method` source across indexed method metadata, signatures, classes, names, return/parameter types; identity/provenance, empty graphs, node-vs-method restrictions, global order/count | `MethodQueryExecutorTest`, `CrossGraphCypherExecutorTest` |
| Cross-graph | Qualified node/edge/path identities; same local IDs in different graphs distinct; independent pattern joins across graphs; graph_id/element IDs and provenance contributors; source filtering conflicts/unsafe disjunctions; global DISTINCT/aggregation/order/limit; explicit source scope | `CrossGraphCypherExecutorTest` (including 4/17/36-graph cases) |
| Unsupported and errors | CREATE, DELETE/DETACH DELETE, SET, REMOVE, MERGE and other parsed but nonexecuted forms reproduce rejection; invalid function/argument/regex/query errors; missing parameter and invalid type behavior | `CypherCompatibilityTest`, `CypherParametersTest`, `CypherDslAdapterTest` |
| Cancellation and caching | Immutable parsed queries, safe concurrent reuse, result-cache identity/generation invalidation, cancelled computation never cached as success; graph-index and evaluator loops stop promptly | `ParsedQueryTest`, `DirectProjectionResultCacheTest`, `FilteredRelationshipMemoryTest`, query budget/cancellation tests |

Source inventory: `src/main/antlr/CypherLexer.g4`, `CypherParser.g4`, and all
production Kotlin files in `graphite-cypher`. Tests assert concrete AST shape and
meaningful values per `CONVENTIONS.md`. Additional differential cases should
exercise feature combinations, boundary values, malformed input, Unicode, regex
backreferences/lookaround, numeric precision/overflow and different graph orders;
validating only row counts cannot establish parity. Nondeterministic functions
require domain/range/time checks rather than raw equality. HTTP currently does
not pass a JSON parameters map into the executor, so do not silently claim such
an API without checking the baseline.

## Persisted storage and graph capabilities

| Surface | Required compatibility | Source/tests |
| --- | --- | --- |
| Existing graph directory | Native consumption of current/legacy GraphStore output; no mandatory JVM conversion or query fallback | `GraphStore.kt`, `NodeSerializer.kt`; `GraphStoreTest` and serializer tests |
| Core data | `graph.nodedata`, `graph.metadata`, string references, IDs and every node tag; metadata versions 1/2/3 with current format 3; method/type/artifact/annotation information | `NodeSerializer.kt`, `MappedNodeData.kt`; serialization/metadata tests |
| Adjacency | WebGraph/BVGraph forward compression and properties/offsets; backward index/transpose fallback; edge labels, comparisons, multiplicity, sorted targets and node ID gaps | `GraphStore.kt`, `WebGraphGraph.kt`; persisted outgoing/incoming and comparison tests |
| Load modes | EAGER, MAPPED and AUTO; AUTO eager below 1,000,000 nodes, mapped otherwise; same results and advertised mode semantics | `GraphStore.LoadMode`; eager/mapped parity tests |
| Indexes | `graph.nodeindex`, `graph.nodeoffsets`, `graph.typeindex`, `graph.labelprefix`; supported legacy reconstruction; missing/corrupt optional acceleration sidecars fall back without corrupting results | GraphStore/index/CallSite string-index tests |
| Resources | Persisted `graph.resources`, missing legacy-vs-empty distinction, original path/source/content, glob behavior, embedded/nested archive resources and artifact provenance | `PersistedResourceAccessor.kt`; persisted resources and real JAR tests |
| Sidecars/lifetimes | Read-only directory support/fallbacks where baseline supports them; cache/content identity; stale/corrupt index rejection; safe mapped memory lifetime through lease retirement | GraphStore/index tests; explore registry lease tests |
| Graph capabilities | Methods, nodes by label/property, node counts, edges, annotations, class overview, resources, type hierarchy/branch comparisons used by server/Cypher/C4 must remain observable | `graphite-core/graph/Graph.kt`, `GraphCapabilityTest`, webgraph tests |

Tests and source, rather than the table's sample filenames, define all binary
fields and supported fallback rules. Byte-for-byte private content need not be
committed; fixture identity and provenance must be recorded outside response logs
where appropriate.

## Local real fixtures and baseline runtime discovery

The following paths were checked for existence and file sizes on 2026-09-07.
They are discovery evidence, not a completed load/benchmark receipt.

| Path | Observed bytes/files | Use |
| --- | --- | --- |
| `/tmp/pr113-exp037-fixture.nXn4fg` | 10,338,280,922 bytes; 1154 files; 64 `graph.metadata` files | Existing 64-shard fixture, Android/Tika/Hive/Kotlin compiler provenance documented in `wrapped-case-insensitive-query-optimization-attempts.md`; complete forward files observed |
| `/tmp/pr113-exp037-fixture.nXn4fg/fixture-tika-00` | 99,200,727 bytes; 18 files | Small real persisted graph for initial server differential checks; `forward.graph` exists |
| `/tmp/pr113-exp037-fixture.nXn4fg/fixture-android-00` | 39,685,960 bytes; 18 files | Small real persisted graph; `forward.graph` exists |
| `/tmp/graphite-real4-routing.Zd0s1O/graphs` | 1,210,746,286 bytes; 20 files; four metadata files | Incomplete routing fixture: no forward graph in the four corpus directories; unsuitable for GraphStore server loading as found |
| `/tmp/android-persisted-copy.dQKWj5` | No regular files | Empty; unusable |
| `/Users/johnsonlee/workspace/github/johnsonlee/graphite/graphite-explore/build/libs/graphite-explore.jar` | 26,177,949 bytes | `java -jar ... --help` succeeds, but stale CLI advertises work budget and lacks timeout; do not label this artifact as pinned main |

Java 17.0.18 arm64 is installed at
`/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home`.
Build a fresh runtime from the resolved main revision with
`./gradlew :explore:shadowJar`; launch the generated
`graphite-explore/build/libs/graphite-explore.jar` with a copied real fixture,
explicit `--data`, `--graph`, `--port`, `--load-mode`, and matching concurrency/
timeout flags. Graph loading can create indexes/sidecars, so preserve a pristine
manifest and give each measured revision equivalently prepared copies.

## Acceptance evidence still required

1. Native Go executable builds and runs without JVM runtime dependency. All routes,
   CLI options, binary inputs, value semantics, UI assets, topology/C4 behavior,
   concurrency and lifecycle rows above have positive and negative checks against
   the freshly built pinned main server. Record gaps explicitly.
2. Existing Kotlin module correctness and lint gates remain green. Run meaningful
   Go semantic/concurrency tests and differential HTTP checks, including real
   fixture full response comparisons and adversarial cancellation/reload cases.
3. Before measurement, freeze representative real persisted dataset manifests and
   workload: scoped/root/selected graphs, cold/warm state, graph counts, result
   cardinalities, query mix and concurrency. Apply equivalent timeouts and limits.
   Do not use response caches that change hot-load semantics or omit slow cases.
4. Measure external HTTP end-to-end latency including complete response reading.
   Preserve individual samples, success/error/result checks, sufficient repeated
   paired runs, P50/P95/P99, CPU/RSS/allocations and throughput. Report each workload
   and aggregate mix without hiding regressions, timeouts, incorrect results or
   rejected requests. Required ratio is `main P95 / Go P95 >= 10` for the agreed
   representative server workload, with no functional regression.
5. Follow `CONVENTIONS.md`: no synthetic performance evidence; chronological
   optimization attempt log with fixture and exact revisions, one experiment per
   commit, keep/revert rationale; relevant `CypherBenchmark`, real load/query and
   `LargeCorpusPerformanceGateTest` coverage; required PR
   `benchmark-regression-gate` check. JVM method-level results do not substitute
   for Go-vs-main HTTP P95 evidence.

At inventory creation, complete native parity and the 10x P95 target remain
unproven. This document must not be used as evidence that either is complete.
