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

Graphite uses **Cypher** (the industry-standard graph query language) for querying. The Cypher engine is in the `graphite-cypher` module, powered by an ANTLR-based openCypher parser.

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
brew install graphite

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

# Serve multiple graphs by id. Relative graph paths resolve under --data.
graphite serve --data /data/graphs \
  --graph orders:orders-graph \
  --graph billing:/data/billing-graph \
  --topology /rules/company-topology.cypher \
  --max-concurrent-cypher 2 \
  --cypher-work-budget 250000 \
  --port 8080

# Hot-load or replace a graph without restarting the server
curl -X PUT http://localhost:8080/api/graphs/orders \
  -H 'Content-Type: application/json' \
  -d '{"path":"/data/graphs/orders-graph-v2"}'
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
| `/api/topology` | Get the graph-to-graph call topology built at startup and mapped from temporary storage |
| `/api/cypher` | Run one Cypher query over the union of every loaded graph |
| `/api/cypher/graphs` | Run one query over an explicit graph set, or explicitly fan out per graph |
| `/api/methods` | List declared methods, including declared return types and indexed methods without graph nodes |
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

Use Cypher for agent-driven node and call-site discovery. The legacy
`/api/nodes` and `/api/call-sites` search routes are not available. The
`/api/methods` route remains because its structured result preserves declared
return types and indexed methods that do not have corresponding graph nodes.
`/openapi.json` describes the complete supported surface.

A global discovery query belongs on `/api/cypher`. Enumerating `/api/graphs`
and then calling `/api/graphs/{graphId}/cypher` for each entry performs
client-side fan-out and repeats HTTP and Cypher parsing overhead.

Cypher endpoints admit at most two executing queries by default and stop a
query after 250,000 graph work units. Candidate inspections and materialized
path elements consume work units. Configure these bounds with
`--max-concurrent-cypher` and `--cypher-work-budget`. A rejected request returns
HTTP 429 with `code` set to `cypher_concurrency_limit` or
`cypher_work_budget_exceeded`. Result `LIMIT` controls returned rows; it does not
replace this execution budget for aggregations that must scan before limiting.
The `=~` operator preserves Java `Pattern` syntax, including backreferences,
look-around, possessive quantifiers, character-class intersections, and Java's
default line-terminator behavior. Budgeted execution polls cancellation through
the matcher's input without changing the accepted pattern language.
An executing query is cancelled only when the server observes an actual connection close,
TCP reset, or socket error. Jetty's connection idle clock is suspended while Cypher is
executing, because a query can legitimately perform no socket I/O for longer than the
connector's default 30-second idle timeout. A clean input FIN is not treated as cancellation:
TCP exposes both a full client `close()` and a valid request-side `SHUT_WR` as the same input
half-close until the server attempts to write the response. A server-side cancellation on a
still-connected client returns HTTP 503 with `code` set to `cypher_query_cancelled`; it is
never reported as an empty HTTP 200 response.

Start the server with `--metrics` to expose Prometheus output at `/metrics`.
Metrics are opt-in, so the default request path has no Micrometer instrumentation cost.
Runtime and HTTP performance metrics are the primary surface: JVM heap, GC and
threads; process CPU, uptime and file descriptors; and Jetty connections,
thread-pool load and route-template HTTP latency. Graphite-specific metrics are
secondary and currently cover Cypher active queries, concurrency limit,
rejections and duration by fixed outcome. Graph ids, query text, keywords,
classes and methods are never used as metric labels. HTTP URI labels are route
templates and are capped at 64 distinct values.

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
and aggregates the returned rows into an internal topology graph stored under
`${java.io.tmpdir}/graphite/<UUID>/` and mapped read-only. This internal format
is independent of the public WebGraph/`GraphStore` format. The query
must return `source` and `target`; it may also return `protocol`,
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

## Architecture

```
graphite/
├── graphite-core/          # Graph interface, nodes, edges, analysis
├── graphite-cypher/        # Cypher query engine (ANTLR parser + executor)
├── graphite-sootup/        # SootUp bytecode → graph builder
├── graphite-webgraph/      # WebGraph disk persistence (BVGraph + LAW tools)
├── graphite-query/         # CLI: build, query, serve
└── graphite-explore/       # Explore HTTP routes and legacy standalone launcher
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

## Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.johnsonlee.graphite:core:2.1.0")
    implementation("io.johnsonlee.graphite:sootup:2.1.0")
    // Optional: Cypher query support (graph.query("MATCH ..."))
    implementation("io.johnsonlee.graphite:cypher:2.1.0")
    // Optional: disk persistence (WebGraph format)
    implementation("io.johnsonlee.graphite:webgraph:2.1.0")
}
```

## MCP Integration

Connect LLMs to Graphite via [Model Context Protocol](https://modelcontextprotocol.io):

```bash
npx graphite-mcp
```

Configure in Claude Code (`~/.claude/settings.json`):

```json
{
  "mcpServers": {
    "graphite": {
      "command": "npx",
      "args": ["graphite-mcp"],
      "env": { "GRAPHITE_URL": "http://localhost:8080" }
    }
  }
}
```

Start the Explorer first, then LLMs can query the graph:

```bash
# Start Explorer
graphite serve --id app /path/to/saved-graph

# The serve command defaults to --load-mode MAPPED for multi-graph heap stability.
```

You can also start with no initial graph and hot-load services later:

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
providing `graph_id` selects exactly one graph. The `cypher` tool can also use
`graphs: ["orders", "billing"]` for an explicit subset or `all_graphs: true`
with `mode: "cross-graph"` or `mode: "fanout"`.

LLMs can use tools such as openapi, graphs, cypher, methods, resources,
resource, endpoints, c4, and annotations. Node and call-site discovery goes
through the `cypher` tool; declared method metadata remains available through
the dedicated `methods` tool.

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
