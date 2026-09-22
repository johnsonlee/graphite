# Graphite

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**Structured codebase context for LLMs.** Graphite turns JVM bytecode into a queryable program graph — so AI agents can understand your codebase without reading every file.

## The Problem

LLMs working with code face a fundamental constraint: **context windows are finite, but codebases are not.**

Dumping source files into a prompt is wasteful. Most tokens describe boilerplate, imports, and formatting — not the relationships that matter. An LLM trying to understand "what calls this method?" or "what constants flow into this API?" must read hundreds of files to answer questions that a graph can answer in milliseconds.

## The Solution

Graphite builds a **program graph** from compiled bytecode — nodes are program elements (methods, fields, constants, call sites), edges are relationships (dataflow, calls, type hierarchy). LLMs query the graph instead of reading source code.

**Before Graphite:** Feed 500 source files (~2M tokens) to find AB test IDs.
**With Graphite:** Query `graph.callSites(pattern)` → get 23 constants in 12 tokens.

### What the Graph Captures

| Relationship | Example | LLM Use Case |
|-------------|---------|---------------|
| **Dataflow** | `x = 42; foo(x)` → constant 42 flows to `foo` | Track config values, feature flags, API keys |
| **Call graph** | `UserService.save()` calls `Repository.insert()` | Understand execution paths without reading source |
| **Type hierarchy** | `AdminUser extends User implements Auditable` | Resolve polymorphism, find implementations |
| **Annotations** | `@GetMapping("/api/users")` on `listUsers()` | Discover endpoints, serialization rules, DI config |
| **Lambda/method ref** | `items.stream().map(User::getName)` | Trace functional pipelines |
| **Resources** | `config/application.yml` inside a fat JAR | Cross-reference code with config files |

### Token Efficiency

| Task | Raw Source | Graphite Query | Reduction |
|------|-----------|----------------|-----------|
| Find all AB test IDs | ~500 files, 2M tokens | `callSites` + `backwardSlice` → 23 results | **99.99%** |
| Map REST endpoints | ~200 controllers, 800K tokens | `memberAnnotations` scan → structured list | **99.9%** |
| Find dead code | Entire codebase, 5M tokens | `branchScopes` + `callSites` → dead paths | **99.99%** |
| Resolve type hierarchy | ~100 files per type chain | `supertypes` / `subtypes` → direct answer | **99%** |

Graphite uses **Cypher** (the industry-standard graph query language) for querying. The `graphite` binary runs the Rust engine in `backend/cypher` for `query`, `serve` and `mcp`; the Kotlin API's `graph.query(...)` runs the ANTLR-based engine in `frontend/jvm/cypher`. Both speak the same read-oriented dialect and are checked against each other by a differential harness (`backend/bench`).

## Why Not Tree-sitter?

Tools like [GitNexus](https://github.com/nicobailon/gitnexus), Aider, and most LLM code assistants use [Tree-sitter](https://tree-sitter.github.io/) for codebase understanding. Tree-sitter parses syntax — it sees **text structure**, not **program semantics**.

| Capability | Tree-sitter | Graphite |
|-----------|-------------|----------|
| "What type is this variable?" | No — sees `var x = foo()`, can't resolve `foo`'s return type | Yes — full type resolution from bytecode |
| "What values flow into this parameter?" | No — can't cross method boundaries | Yes — inter-procedural backward slice |
| "Does this interface have implementations?" | Heuristic grep for class names | Yes — complete type hierarchy from class metadata |
| "What does this lambda actually call?" | No — `invokedynamic` is invisible in source | Yes — MethodHandle extraction from bootstrap args |
| "Is this field used via reflection/DI?" | No — annotation semantics are opaque | Yes — annotation values are queryable data |
| "What's the real type of `Object` fields?" | No — requires dataflow across methods | Yes — cross-method field assignment tracking |
| Controller inheritance | No — can't resolve inherited annotations | Yes — walks type hierarchy for endpoint discovery |

**The fundamental issue:** Tree-sitter operates on **syntax** (one file at a time, no type resolution, no cross-file dataflow). Graphite operates on **semantics** (compiled bytecode with full type information, inter-procedural analysis, resolved generics).

For LLMs, this difference is critical. A syntax tree tells you what code *looks like*. A program graph tells you what code *does*.

## Quick Start

```bash
# Install via Homebrew
brew tap johnsonlee/tap
brew install johnsonlee/tap/graphite
graphite --version

# Build a graph from your JAR
graphite build app.jar -o /data/app-graph --include com.example

# Build a graph from an Android APK
graphite build app.apk \
  -o /data/apk-graph \
  --include com.example

# Query with Cypher
graphite query /data/app-graph \
  "MATCH (c:IntConstant)-[:DATAFLOW*]->(cs:CallSiteNode)
   WHERE cs.callee_class =~ 'com.example.*'
   RETURN c.value, cs.callee_name"

# JSON output (for LLM consumption)
graphite query --format json /data/app-graph \
  "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 10"

# Launch the web UI
graphite serve --id app /data/app-graph --port 8080

# Serve every .graphite file in a directory, each under its file name
# (/data/graphs/orders.graphite is served as `orders`).
graphite serve --data /data/graphs --port 8080

# Serve multiple graphs by id. Relative graph paths resolve under --data, and any
# .graphite file directly under --data is served too.
graphite serve --data /data/graphs \
  --graph orders:orders-graph \
  --graph billing:/data/billing-graph \
  --topology /rules/company-topology.cypher \
  --max-concurrent-cypher 4 \
  --cypher-max-timeout-ms 60000 \
  --port 8080

# Hot-load or replace a graph without restarting the server
curl -X PUT http://localhost:8080/api/graphs/orders \
  -H 'Content-Type: application/json' \
  -d '{"path":"/data/graphs/orders-graph-v2"}'
```

### What the formula installs

`graphite` is a native binary (Rust). It carries `query` and `serve` itself and runs
`build` through the JVM frontend, `graphite.jar`, which the formula installs next to it
together with `openjdk@17`. Every command line written for the jar-based formula works
unchanged, `--profile` and the `JAVA_OPTS`/`JAVA_TOOL_OPTIONS` heap settings included.

```bash
graphite frontend list           # which frontend `build` will run, and where it came from
graphite frontend describe jvm   # JSON: version, accepted inputs
graphite frontend install jvm    # fetch the jar for this CLI's version into ~/.graphite/frontends
```

Outside Homebrew, `graphite build` finds the frontend through, in order:
`GRAPHITE_FRONTEND_JVM` (a jar or launcher), a `graphite.jar` next to the binary or in a
sibling `libexec/`, `graphite-frontend-jvm` on `PATH`, then `~/.graphite/frontends/jvm/`.
It finds `java` through `GRAPHITE_JAVA`, `JAVA_HOME`, then `PATH`. The release also ships
`graphite.jar` on its own; `java -jar graphite.jar build|query|serve` still works.

### Upgrading a legacy installation

An older installer may have placed `~/.graphite/bin/graphite` before Homebrew in `PATH`. In that case, installing or
upgrading the formula does not change the executable invoked by `graphite`. Move the legacy installation aside,
refresh command lookup, and rebuild saved graphs so they contain the current resource store:

```bash
type -a graphite
mv ~/.graphite ~/.graphite.legacy
hash -r
brew upgrade johnsonlee/tap/graphite
graphite --version
graphite build app.jar -o /data/app-graph --include com.example
```

For APK inputs, Graphite uses Android platform jars to resolve the APK's target
API level. Pass `--android-sdk` with the Android SDK root. If omitted,
Graphite searches in this order:

1. `ANDROID_HOME`, then `ANDROID_SDK_ROOT`.
2. Default SDK roots for the current OS:
   - macOS: `~/Library/Android/sdk`,
     `/opt/homebrew/share/android-commandlinetools`,
     `/usr/local/share/android-commandlinetools`
   - Linux: `~/Android/Sdk`, `~/android-sdk`, `/opt/android-sdk`,
     `/usr/local/android-sdk`, `/usr/lib/android-sdk`
   - Windows: `%USERPROFILE%\AppData\Local\Android\Sdk`
3. SDK roots inferred from `adb`, `emulator`, or `sdkmanager` on `PATH`.

## Kotlin API

### Build & Query

```kotlin
// Build graph from bytecode
val graph = JavaProjectLoader(LoaderConfig(
    includePackages = listOf("com.example")
)).load(Path.of("/path/to/app.jar"))

// Cypher query
val result = graph.query("""
    MATCH (c:IntConstant)-[:DATAFLOW*]->(cs:CallSiteNode)
    WHERE cs.callee_class =~ 'com.example.*'
    RETURN c.value, cs.callee_name
""")
result.rows.forEach { row ->
    println("${row["c.value"]} -> ${row["cs.callee_name"]}")
}

// Bind values without interpolating them into the query text
val selected = graph.query(
    "MATCH (c:IntConstant) WHERE c.value = \$value RETURN c",
    mapOf("value" to 42)
)

// Programmatic query DSL
val results = Graphite.from(graph).query {
    findArgumentConstants {
        method {
            declaringClass = "com.example.ab.AbClient"
            name = "getOption"
        }
        argumentIndex = 0
    }
}

// Annotations, dataflow analysis
val annotations = graph.memberAnnotations("com.example.User", "name")
val slice = DataFlowAnalysis(graph).backwardSlice(nodeId)
slice.constants()  // all constant values that reach this node
```

### Persist & Load

```kotlin
// Save to disk (WebGraph compressed format)
GraphStore.save(graph, Path.of("/data/app-graph"))

// Load — auto-adaptive based on graph size:
//   < 1M nodes → eager (all in heap, fastest queries)
//   >= 1M nodes → mmap (nodes off heap, 75% less memory)
val graph = GraphStore.load(Path.of("/data/app-graph"))

// Or force a specific strategy
val graph = GraphStore.load(dir, GraphStore.LoadMode.EAGER)   // always in-heap
val graph = GraphStore.load(dir, GraphStore.LoadMode.MAPPED)  // always mmap
```

### Access Resources

```kotlin
graph.resources.list("**/*.xml").forEach { entry ->
    println(entry.path)  // e.g., "config/application.yml"
}
```

### Query Resources With Cypher

Resources are also indexed into the graph, so you can query them with Cypher and
cross-reference them with call sites:

```cypher
// Structured resource values
MATCH (r:ResourceValue {key: "feature.mode"})
RETURN r.path, r.value

// Nested JSON / XML values
MATCH (r:ResourceValue)
WHERE r.key IN ["feature.enabled", "service.endpoint", "service.@enabled"]
RETURN r.path, r.key, r.value

// Which call sites read a specific key
MATCH (r:ResourceValue {key: "feature.mode"})-[:RESOURCE_LOOKUP]->(cs:CallSiteNode)
RETURN cs.caller_signature, cs.callee_signature

// Resource files opened by code
MATCH (f:ResourceFile)-[e:RESOURCE_OPEN|RESOURCE_LOAD|RESOURCE_BUNDLE_CANDIDATE]->(cs:CallSiteNode)
RETURN f.path, e.kind, cs.caller_signature, cs.callee_signature
```

Resource relationships are exposed as dedicated edge types:

| Type | Meaning |
|------|---------|
| `RESOURCE_CONTAINS` | `ResourceFile -> ResourceValue` |
| `RESOURCE_OPEN` | Resource file opened directly by code |
| `RESOURCE_LOAD` | Resource content loaded by parsers/bundles |
| `RESOURCE_BUNDLE_CANDIDATE` | `ResourceBundle.getBundle(...)` candidate resolution |
| `RESOURCE_LOOKUP` | Concrete key/value lookup (`getProperty`, `getString`, `getObject`) |
| `RESOURCE_KEYS` | Key enumeration (`getKeys`) |

Resource path indexing currently covers:
- `.properties`
- `.yml` / `.yaml`
- Java properties XML (`Properties.loadFromXML`)
- `.json`
- generic `.xml`
- `ListResourceBundle` / provider-backed class bundles via path-level class indexing

Generic JDK resource linking currently covers:
- `ClassLoader.getResource*`
- `Properties.load(...)`
- `Properties.loadFromXML(...)`
- `PropertyResourceBundle(...)`
- `ResourceBundle.getString/getObject/getKeys`
- `ResourceBundle.getBundle(...)` with locale-aware candidate resolution
- common `ResourceBundle.Control` cases including `FORMAT_*`, no-fallback controls, and simple custom `getFormats/getCandidateLocales` overrides

### Explore Resource APIs

`graphite serve` exposes resource-aware HTTP APIs for agents and tooling:

| Endpoint | Description |
|----------|-------------|
| `/api/graphs` | List loaded webgraphs with cached per-graph statistics and aggregate totals |
| `/api/graphs/{graphId}` | Get, load, replace, or unload a webgraph by id |
| `/api/graphs/{graphId}/...` | Query one explicit webgraph with the direct single-graph response shape |
| `/api/topology` | Get the graph-to-graph call topology derived at startup from the `--topology` rules |
| `/api/cypher` | Run one Cypher query over the union of every loaded graph |
| `/api/cypher/graphs` | Run one query over an explicit graph set, or explicitly fan out per graph |
| `/api/resources` | List indexed resources in every graph, grouped by `graphId` |
| `/api/resources/{path}` | Read every matching resource without path collisions, grouped by `graphId` |
| `/api/endpoints` | Extract framework HTTP endpoints from every graph, grouped by `graphId` |
| `/metrics` | Prometheus performance metrics when the server starts with `--metrics` |
| `/openapi.json` | Machine-readable OpenAPI document for the explore server |
| `/swagger.json` | Swagger-compatible alias of the same API document |

Graph-local node IDs are accepted only by graph-scoped routes such as
`/api/graphs/{graphId}/node/{id}` and
`/api/graphs/{graphId}/subgraph?center={id}`. The corresponding root routes do
not exist because the same local ID can identify unrelated nodes in different
graphs.

There is no default graph and no automatic graph selection. Root graph APIs
always mean all loaded graphs; `/api/graphs/{graphId}/...` always means exactly
one graph. Every root non-Cypher result is grouped by `graphId`, while every
cross-graph Cypher row includes `$metadata.graphIds` and returned graph elements include
qualified identities such as `elementId = "orders:42"`.

The legacy `/api/nodes`, `/api/call-sites`, and `/api/methods` search routes and
the MCP `methods` tool are gone (removed during 2.x). Use Cypher for
agent-driven node, call-site, and method discovery. `/openapi.json` describes
the complete supported surface.

Declared method metadata, including indexed methods without graph nodes, is
available through the virtual `Method` source:

```cypher
MATCH (method:Method)
RETURN method.signature, method.class, method.name,
       method.parameter_types, method.return_type
LIMIT 50
```

`Method` values are virtual metadata records rather than stored graph nodes.
Their stable string identity is available through `elementId(method)`;
`id(method)` returns `null` because no numeric graph-node id exists.

Every Cypher response says whether the rows it returned are all there are. Next
to `rowCount` (the rows in the response) there is `total`, in the shape
Elasticsearch gives `hits.total`:

```json
{ "columns": ["n.callee_name"], "rows": [ … ], "rowCount": 1000,
  "total": { "value": 1001, "relation": "gte" } }
```

`relation` is `eq` when `value` is the exact number of rows the query has, and
`gte` when at least `value` rows exist and the response was cut by a `LIMIT`, the
`limit` parameter (default 1000, at most 5000), or a fan-out `perGraphLimit`. The
engine learns this by matching one row past the limit, so nothing is counted;
`LIMIT 0` therefore answers "does any row exist". On `/api/cypher/graphs` every
per-graph entry carries its own `total` as well.

A global discovery query belongs on `/api/cypher`. Enumerating `/api/graphs`
and then calling `/api/graphs/{graphId}/cypher` for each entry performs
client-side fan-out and repeats HTTP and Cypher parsing overhead.

Cypher endpoints admit at most four executing queries by default and enforce a
60-second maximum request timeout. Configure these bounds with
`--max-concurrent-cypher` and `--cypher-max-timeout-ms`. Clients may request a
shorter positive `timeoutMs` in the query string or JSON body; the effective
timeout is the smaller of the client value and the server maximum. A timeout
automatically cancels and interrupts the corresponding query, returning HTTP
504 with `code` set to `cypher_query_timeout`. Concurrency rejection returns
HTTP 429 with `code` set to `cypher_concurrency_limit`.

`--cypher-work-budget` is deprecated and ignored. It remains accepted for
command-line compatibility but no longer constrains server requests. Core
library callers may still use `CypherExecutionBudget` directly.
The `=~` operator in the `graphite` binary accepts the syntax of Rust's `regex` crate:
linear-time matching, no backreferences, look-around or possessive quantifiers. A pattern
that uses such a construct fails the query with `Unsupported regex construct in pattern`
rather than matching nothing. The Kotlin engine (`graph.query(...)` and the legacy
`graphite.jar serve`) keeps Java `Pattern` syntax in full.
A query that stops for any reason other than its timeout returns HTTP 503 with `code`
set to `cypher_query_cancelled`; it is never reported as an empty HTTP 200 response. The
legacy `graphite.jar serve` additionally cancels a query when it observes a connection
close, TCP reset or socket error, and suspends Jetty's idle clock while Cypher executes;
see [docs/cypher-client-cancellation-attempts.md](docs/cypher-client-cancellation-attempts.md).

Start the server with `--metrics` to expose Prometheus output at `/metrics`.
Metrics are opt-in, so the default request path carries no instrumentation cost.
The `graphite` binary exports what a native process knows about itself: `process_*`
(CPU seconds, resident and virtual memory, threads, open and maximum file
descriptors, start time, uptime), `system_load_average_1m` and `system_cpu_count`;
`http_server_requests_seconds`, a latency histogram by HTTP method, route template,
status and outcome, plus `http_server_requests_active`; and the graphs it serves,
`graphite_graphs_loaded`, `graphite_graph_nodes`, `graphite_graph_edges` and
`graphite_graph_mapped_bytes`. Two `_info` gauges carry identity for a fleet:
`graphite_build_info{version,commit}` (the commit when the release build set it,
`unknown` otherwise) and `graphite_graph_info{graph,fingerprint}`, one per served
graph, where the fingerprint is the SHA-256 of the graph's manifest, the same for a
directory and for the `.graphite` file packed from it, so a rollout can check that
every instance serves the same build and the same graphs. Which instance a scrape
came from is the scraper's `instance` label, as usual; no series carries a host
name. Cypher metrics cover active queries, concurrency
limit, rejections and duration by fixed outcome. MCP over `POST /mcp` is covered
too: `graphite_mcp_requests_total` counts JSON-RPC requests by method
(`initialize`, `ping`, `tools/list`, `tools/call`, anything else as `other`) and
`graphite_mcp_tool_duration_seconds` is a latency histogram by `tool` (the names
`tools/list` returns) and `outcome` (`ok`, or `error` when the tool answered
`isError`), with the same buckets as the Cypher histogram. A tool call is one
`/mcp` request in `http_server_requests_seconds`; the API hop it makes inside the
process is not counted again. `graphite mcp` over stdio has no `/metrics` and
records nothing. (The legacy `graphite.jar serve`
exports JVM heap, GC and thread metrics and Jetty's request timer instead.)
Graph ids, query text, keywords, classes and methods are never used as metric
labels. HTTP URI labels are route templates and are capped at 64 distinct values;
a request that would create a 65th template is not recorded.

For label discovery, use the metadata-backed histogram shape below. Graphite
answers it from node type counts without visiting graph nodes:

```cypher
MATCH (n)
UNWIND labels(n) AS label
RETURN label, count(*) AS count
ORDER BY count DESC
LIMIT 50
```

For multi-graph startup, `--topology` accepts one Cypher file (or a directory
of `.cypher` files). The configured `--graph` entries are the catalog: Graphite
loads them once, runs the topology query over those loaded graph instances,
and aggregates the returned rows into an in-process topology graph that is
rebuilt whenever a graph is loaded, replaced or unloaded. Nothing is written
beside the service graphs. The query must return `source` and `target`; it may also return `protocol`,
`operation`, `weight`, and `evidence`. For example, a generated RPC adapter can
encode its provider in a package segment:

```cypher
MATCH (call:CallSiteNode)
WHERE call.callee_class =~ 'com\\.company\\.rpc\\..*\\.Adapter'
RETURN graphId(call) AS source,
       split(call.callee_class, '.')[3] AS target,
       'company-rpc' AS protocol,
       call.callee_name AS operation,
       call.callee_class AS evidence
```

The Explorer homepage displays this topology by default when more than one
graph is loaded. Isolated graphs remain visible, and double-clicking a graph
drills down to its class overview.

## Scale

Measured on a production deployment of `graphite serve` (Rust backend, graphs
memory-mapped):

| | |
|---|---|
| Graphs served by one process | 40+ |
| Nodes | 100M+ |
| Edges | 100M+ |
| Methods | 10M+ |
| Call sites | 20M+ |
| Cypher latency, P50 | ~500 ms |
| Cypher latency, P95 | ~15 s |

Latency is over the mixed production query stream, most of it cross-graph. Narrow
queries (a class, a method, a constant) answer from the string indexes in
milliseconds; the P95 is the broad shapes that search every property of every
node. `--metrics` exposes the same figures for your own deployment as
`http_server_requests_seconds` and the Cypher families.

## Architecture

Graphite is split into per-language *frontends*, which turn compiled artifacts into a
graph, one Rust *backend*, which stores, serves, and queries those graphs, and one Rust
*CLI* (`graphite`) that drives both. See
[docs/architecture-frontend-backend.md](docs/architecture-frontend-backend.md).

```
graphite/
├── frontend/
│   └── jvm/                # JVM frontend (Kotlin, Gradle projects keep their short names)
│       ├── core/           # Graph interface, nodes, edges, analysis
│       ├── cypher/         # Cypher query engine (ANTLR parser + executor)
│       ├── sootup/         # SootUp bytecode → graph builder
│       ├── webgraph/       # WebGraph disk persistence (BVGraph + LAW tools)
│       ├── query/          # `graphite.jar`: the build frontend, plus legacy query/serve
│       └── explore/        # Legacy Kotlin Explorer server
├── backend/                # Rust backend
│   ├── storage/            # mmap reader of the persisted graph, indexes, columns
│   ├── cypher/             # Cypher parser, planner, executor
│   ├── explore/            # HTTP server, UI, C4, topology
│   └── bench/              # Kotlin-vs-Rust differential harness and benchmarks
├── cli/                    # `graphite` CLI (Rust): build, query, serve, mcp, frontend
├── Cargo.toml              # Cargo workspace: backend/* and cli
└── docs/
```

### Storage Format

Graphs are persisted using the [WebGraph](https://webgraph.di.unimi.it/) ecosystem:

| Data | Format |
|------|--------|
| Adjacency | BVGraph (2-4 bits/edge) |
| Edge labels | Byte array in BVGraph order |
| Strings | FrontCodedStringList (prefix compression) |
| Node data | Compact binary with string table indices |
| Metadata | Compact binary with string table indices |

A saved graph is a directory of these files, or the same files packed into **one
`.graphite` file**: `graphite build app.jar -o app.graphite` writes the file, and
`graphite query`, `serve` and `mcp` open either form. The file is a plain uncompressed
(STORED) zip, so `unzip -l` and `jar tf` list it, with every entry page-aligned so the
server serves it from one memory map exactly as it serves a directory, and a
`META-INF/graphite.manifest` entry carrying each file's size and SHA-256; `pack` refuses a
set of files that is not a whole graph, and `verify` reports a missing required entry. Because the
central directory is written last, a truncated file does not open at all, and packing is
deterministic: the manifest's SHA-256 is the graph's fingerprint. A `.sha256` file in
`sha256sum -c` format is written next to the container, so a copy or download is checked
with standard tools; the fingerprint says what the graph is, the file digest whether these
are the bytes that were built.

```bash
graphite verify app.graphite              # CRC-32 per entry, SHA-256 against the manifest and app.graphite.sha256
sha256sum -c app.graphite.sha256          # the same file check without graphite
graphite info app.graphite                # entries, sizes, fingerprint, file digest as JSON
graphite pack saved-graph/ -o app.graphite
graphite unpack app.graphite saved-graph/ # for graphite.jar or the Kotlin API (default: current directory)
```

Replacing a served graph is then one atomic `rename` of a new file over the old: the
server's mapping stays bound to the old inode until the last in-flight query finishes.

## Analysis Capabilities

| Capability | Description |
|-----------|-------------|
| Constant tracking | Direct, local variable, field, cross-class, enum |
| Auto-boxing | `Integer.valueOf()` transparent handling |
| Lambda / method ref | `invokedynamic` → actual target resolution |
| Functional dispatch | Callbacks, return values, fields, varargs, conditionals |
| Controller inheritance | Endpoint discovery follows class hierarchy |
| Generic type analysis | `ApiResponse<PageData<User>>` nested structure |
| Branch reachability | Dead code via condition constant analysis |
| Annotations | Generic `memberAnnotations()` for any framework |
| Cypher queries | `graph.query("MATCH ...")` -- read-oriented Cypher subset |
| Resource access | Files inside JAR/WAR/fat JAR (nested JARs) |

## Extension Mechanism

Pluggable via `GraphiteExtension` SPI (ServiceLoader):

```kotlin
class MyExtension : GraphiteExtension {
    override fun visit(sootClass: SootClass, context: GraphiteContext) {
        // Extract domain-specific metadata during graph building
        context.addMemberAnnotation(className, memberName, annotationFqn, values)
    }
}
```

Register in `META-INF/services/io.johnsonlee.graphite.sootup.GraphiteExtension`.

## Kotlin Dependencies

The JVM modules are published to Maven Central under `io.johnsonlee.graphite` with
prefix-free artifact ids (`core`, `sootup`, `cypher`, `webgraph`), unchanged since 2.x.
Pin an explicit version: the `3.0.0-alpha*` pre-releases of this layout are still on
Maven Central and sort above `2.5.0`, so a dynamic version such as `+` resolves to one
of them instead of the current release.

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.johnsonlee.graphite:core:2.5.0")
    implementation("io.johnsonlee.graphite:sootup:2.5.0")
    // Optional: Cypher query support (graph.query("MATCH ..."))
    implementation("io.johnsonlee.graphite:cypher:2.5.0")
    // Optional: disk persistence (WebGraph format)
    implementation("io.johnsonlee.graphite:webgraph:2.5.0")
}
```

## MCP Integration

The `graphite` binary is an [Model Context Protocol](https://modelcontextprotocol.io)
server: the thirteen tools the `graphite-mcp` npm package used to expose (`graphs`,
`cypher`, `node`, `outgoing`, `incoming`, `annotations`, `endpoints`, `resources`,
`resource`, `subgraph`, `overview`, `c4`, `openapi`) plus `schema`, served in-process by
the same code as the REST API. `schema` (`GET /api/schema`, `GET /api/graphs/{id}/schema`)
describes what a graph holds -- every label set with its node count and property keys,
every relationship type with its count, the most frequent `(labels)-[type]->(labels)`
patterns -- in milliseconds, so an agent reads it before writing Cypher instead of
discovering the graph with `MATCH (n) RETURN labels(n), keys(n), count(*)` probes. Those
probes are answered per type as well (see the partitioned evaluation in
`backend/cypher/src/engine/partition.rs`), but one call is cheaper than a conversation.
Two ways to connect:

- **stdio**, for local clients (Claude Code, Claude Desktop, Cursor): `graphite mcp`
  opens the graphs itself; no server to start first.
- **HTTP**, for remote or shared setups: every `graphite serve` also answers MCP at
  `POST /mcp` (Streamable HTTP).

Configure in Claude Code (`~/.claude/settings.json`):

```json
{
  "mcpServers": {
    "graphite": {
      "command": "graphite",
      "args": ["mcp", "--graph", "app:/data/app-graph", "--graph", "billing:/data/billing-graph"]
    }
  }
}
```

or point an HTTP-capable client at a running server: `{"url": "http://localhost:8080/mcp"}`.
`/mcp` validates the `Origin` header (DNS-rebinding protection): requests without one are
accepted, loopback origins are accepted, any other origin is refused with 403 unless listed
with `graphite serve --mcp-allowed-origin https://tools.example.com` (repeatable; `*` allows
all). The REST API is unaffected.

Migrating from `npx graphite-mcp`: the tools, their arguments and their outputs are
unchanged, and every protocol revision the npm package negotiated (`2024-11-05` through
`2025-11-25`) is still accepted; replace the `command`/`args` with `graphite mcp` and the
graphs it should open, and drop `GRAPHITE_URL`. The one argument change is that `node`,
`outgoing` and `incoming` require `graph_id` (the package advertised it as optional and
answered a 404 without it). The npm package is not published from v2.5.0 on; its last
version, 2.4.8, keeps working against a 2.5.0 server because it only calls the REST
routes above.

Start the Explorer first, then LLMs can query the graph:

```bash
# Start Explorer
graphite serve --id app /path/to/saved-graph

# The serve command defaults to --load-mode MAPPED for multi-graph heap stability.
```

You can also start with no initial graph and hot-load services later (a `.graphite`
file placed in `--data` before the next start is picked up by itself):

```bash
graphite serve --data /data/graphs
curl -X PUT http://localhost:8080/api/graphs/orders \
  -H 'Content-Type: application/json' \
  -d '{"path":"orders-graph"}'
```

Graph replacement is atomic for readers. Requests that already acquired the
previous graph finish against that snapshot, requests acquired after the swap
use the replacement, and the previous graph is closed only after its last
request releases it. A replacement that fails to load leaves the current graph
unchanged.

To run one query across an explicit graph set:

```bash
curl -X POST http://localhost:8080/api/cypher/graphs \
  -H 'Content-Type: application/json' \
  -d '{"query":"MATCH (n:IntConstant) RETURN n.value","graphs":["orders","billing"],"limit":100}'
```

The default mode is `cross-graph`: patterns, joins, filters, and aggregations
operate once over the selected graph union. Every row reports all contributing
graphs in `$metadata.graphIds`. To preserve independent per-graph execution, explicitly
send `"mode":"fanout"`; only this mode accepts `perGraphLimit` and
`includeGraphRows`. In both modes, `limit` caps the total response row count.

The MCP tools follow the same rule: omitting `graph_id` queries all graphs;
providing `graph_id` selects exactly one graph. The exceptions are `node`,
`outgoing` and `incoming`, whose node IDs are local to a graph: they require
`graph_id`. The `cypher` tool can also use
`graphs: ["orders", "billing"]` for an explicit subset or `all_graphs: true`
with `mode: "cross-graph"` or `mode: "fanout"`.

LLMs can use tools such as openapi, graphs, cypher, resources, resource,
endpoints, c4, and annotations. Node, call-site, and method discovery goes
through the `cypher` tool.

The explore server also exposes a single C4 architecture endpoint:

```text
GET /api/architecture/c4?level=context|container|component|all
GET /api/architecture/c4?level=context|container|component|all&format=dsl
GET /api/architecture/c4?level=context|container|component|all&format=mermaid
GET /api/architecture/c4?level=context|container|component|all&format=plantuml
```

Agents can use it to retrieve code graph-derived C4 architecture views without
guessing multiple endpoints. The default response is a Structurizr workspace
JSON document. For text rendering, use `format=dsl`, `format=mermaid`, or
`format=plantuml`.

## License

```
Copyright 2026 Johnson Lee

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
