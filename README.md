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
brew install graphite graphite-explore

# Build a graph from your JAR
graphite build app.jar -o /data/app-graph --include com.example

# Query with Cypher
graphite query /data/app-graph \
  "MATCH (c:IntConstant)-[:DATAFLOW*]->(cs:CallSiteNode)
   WHERE cs.callee_class =~ 'com.example.*'
   RETURN c.value, cs.callee_name"

# JSON output (for LLM consumption)
graphite query --format json /data/app-graph \
  "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 10"

# Launch the web UI
graphite-explore /data/app-graph --port 8080
```

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

## Architecture

```
graphite/
├── graphite-core/          # Graph interface, nodes, edges, analysis
├── graphite-cypher/        # Cypher query engine (ANTLR parser + executor)
├── graphite-sootup/        # SootUp bytecode → graph builder
├── graphite-webgraph/      # WebGraph disk persistence (BVGraph + LAW tools)
├── graphite-query/         # CLI: build, query, Cypher
└── graphite-explore/       # CLI: web visualization
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
| Cypher queries | `graph.query("MATCH ...")` -- full openCypher read grammar |
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
    implementation("io.johnsonlee.graphite:core:1.1.0")
    implementation("io.johnsonlee.graphite:sootup:1.1.0")
    // Optional: Cypher query support (graph.query("MATCH ..."))
    implementation("io.johnsonlee.graphite:cypher:1.1.0")
    // Optional: disk persistence (WebGraph format)
    implementation("io.johnsonlee.graphite:webgraph:1.1.0")
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
graphite-explore /path/to/saved-graph

# LLM can now use tools: cypher, nodes, methods, call_sites, annotations, etc.
```

## License

```
Copyright 2026 Johnson Lee

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
