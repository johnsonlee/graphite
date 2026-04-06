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

Graphite uses **Cypher** (the industry-standard graph query language) for querying. The Cypher engine is built into `graphite-core` with zero external dependencies.

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

### Build a Graph

```kotlin
val graph = JavaProjectLoader(LoaderConfig(
    includePackages = listOf("com.example")
)).load(Path.of("/path/to/app.jar"))
```

### Query It

```kotlin
// Cypher query — standard graph query language (built into graphite-core)
val result = graph.query("""
    MATCH (c:IntConstant)-[:DATAFLOW*]->(cs:CallSiteNode)
    WHERE cs.callee_class =~ 'com.example.*'
    RETURN c.value, cs.callee_name
""")
result.rows.forEach { row ->
    println("${row["c.value"]} -> ${row["cs.callee_name"]}")
}

// Find all constants passed to a method
val results = Graphite.from(graph).query {
    findArgumentConstants {
        method {
            declaringClass = "com.example.ab.AbClient"
            name = "getOption"
        }
        argumentIndex = 0
    }
}

// Query annotations on any class member
val annotations = graph.memberAnnotations("com.example.User", "name")
// → {"com.fasterxml.jackson.annotation.JsonProperty": {"value": "user_name"}}

// Backward dataflow analysis
val analysis = DataFlowAnalysis(graph)
val slice = analysis.backwardSlice(nodeId)
slice.constants()  // all constant values that reach this node
```

### Persist & Query from Disk

```bash
# Build graph and save (WebGraph compressed format)
graphite-query build app.jar -o /data/app-graph --include com.example

# CLI queries
graphite-query /data/app-graph info
graphite-query /data/app-graph call-sites -c "com.example.*"
graphite-query /data/app-graph methods -c "com.example.UserService"
graphite-query /data/app-graph annotations -c com.example.User -m name

# Cypher query from the command line
graphite-query /data/app-graph cypher "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 10"

# Interactive web visualization
graphite-query /data/app-graph serve -p 8080
```

### Access Resources

```kotlin
// Access files inside the analyzed archive
graph.resources.list("**/*.xml").forEach { entry ->
    println(entry.path)  // e.g., "config/application.yml"
}
```

## Architecture

```
graphite/
├── graphite-core/          # Graph interface, nodes, edges, analysis, Cypher engine
├── graphite-sootup/        # SootUp bytecode → graph builder
├── graphite-webgraph/      # WebGraph disk persistence (BVGraph + LAW tools)
└── cli/
    ├── find-args/          # Find argument constants
    ├── find-endpoints/     # Find HTTP endpoints
    ├── find-dead-code/     # Find dead code
    └── query/              # Query saved graphs + web visualization
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
    maven {
        url = uri("https://maven.pkg.github.com/johnsonlee/graphite")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("io.johnsonlee.graphite:graphite-core:0.1.0-rc.4")    // includes Cypher engine
    implementation("io.johnsonlee.graphite:graphite-sootup:0.1.0-rc.4")
    // Optional: disk persistence
    implementation("io.johnsonlee.graphite:graphite-webgraph:0.1.0-rc.4")
}
```

## License

```
Copyright 2026 Johnson Lee

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
