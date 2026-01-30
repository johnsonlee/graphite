# Graphite

[![Maven Central](https://img.shields.io/maven-central/v/io.johnsonlee.graphite/graphite-core.svg)](https://search.maven.org/search?q=g:io.johnsonlee.graphite)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A graph-based static analysis framework for JVM bytecode. Graphite provides a clean abstraction layer for building custom program analyses with an intuitive Query DSL.

## Use Cases

- **AB Test ID Detection**: Find all integer/enum/string constants passed to AB SDK methods
- **Feature Flag Analysis**: Discover all feature flags used in your codebase
- **API Return Type Analysis**: Find actual return types when methods declare `Object` or generics
- **Type Hierarchy Analysis**: Discover nested generic types like `ApiResponse<PageData<User>>` and Object field assignments
- **HTTP Endpoint Discovery**: Extract and analyze REST API endpoints from Spring MVC annotations
- **Dead Code Detection**: Identify unreachable code paths
- **Security Auditing**: Track sensitive data flow through your application

## Installation

Artifacts are published to GitHub Packages.

### Gradle (Kotlin DSL)

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
    implementation("io.johnsonlee.graphite:graphite-core:0.1.0-beta.8")
    implementation("io.johnsonlee.graphite:graphite-sootup:0.1.0-beta.8")
}
```

### Gradle (Groovy DSL)

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/johnsonlee/graphite")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation 'io.johnsonlee.graphite:graphite-core:0.1.0-beta.8'
    implementation 'io.johnsonlee.graphite:graphite-sootup:0.1.0-beta.8'
}
```

Configure credentials in `~/.gradle/gradle.properties`:

```properties
gpr.user=your-github-username
gpr.key=your-github-token
```

## Quick Start

### Find AB Test IDs (Integer Constants)

```kotlin
import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader

// Load bytecode from JAR or directory
val loader = JavaProjectLoader(LoaderConfig(
    includePackages = listOf("com.example")
))
val graph = loader.load(Path.of("/path/to/app.jar"))

// Find all integer constants passed to AbClient.getOption()
val results = Graphite.from(graph).query {
    findArgumentConstants {
        method {
            declaringClass = "com.example.ab.AbClient"
            name = "getOption"
            parameterTypes = listOf("java.lang.Integer")
        }
        argumentIndex = 0
    }
}

// Print found AB IDs
results.forEach { result ->
    println("Found AB ID: ${result.value} at ${result.location}")
}
```

### Track Multiple Method Arguments

```kotlin
// Track constants passed to multiple parameters at once
val results = Graphite.from(graph).query {
    findArgumentConstants {
        method {
            declaringClass = "com.example.ab.AbClient"
            name = "getOption"
        }
        argumentIndices = listOf(0, 1)  // analyze both arg 0 and arg 1
    }
}

// Results include argumentIndex so you can distinguish which parameter each constant belongs to
results.forEach { result ->
    println("arg[${result.argumentIndex}] = ${result.value} at ${result.location}")
}
```

### Find Feature Flags (String Constants)

```kotlin
// Find all feature flags passed to FF4j.check()
val featureFlags = Graphite.from(graph).query {
    findArgumentConstants {
        method {
            declaringClass = "org.ff4j.FF4j"
            name = "check"
        }
        argumentIndex = 0
    }
}

featureFlags.forEach { result ->
    println("Feature flag: ${result.value}")
}
```

### Find Actual Return Types

```kotlin
// Find actual return types for REST controllers returning Object
val returnTypes = Graphite.from(graph).query {
    findActualReturnTypes {
        method {
            annotations = listOf("org.springframework.web.bind.annotation.GetMapping")
        }
    }
}

returnTypes.forEach { result ->
    println("${result.method.name}: declared=${result.declaredType}, actual=${result.actualTypes}")
}
```

### Analyze Type Hierarchy (Nested Generics)

Discover the complete type structure of returned objects, including nested generic types and Object field assignments:

```kotlin
// Analyze nested type hierarchy like ApiResponse<PageData<User>>
val results = Graphite.from(graph).query {
    findTypeHierarchy {
        method {
            declaringClass = "com.example.UserService"
            name = "getUserResponse"
        }
        // Optional: increase maxDepth for deeply nested types (default: 10)
        // config { copy(maxDepth = 25) }
    }
}

results.forEach { result ->
    println(result.toTreeString())
    // Output:
    // ApiResponse<PageData<User>>
    //   ├── data: PageData<User>
    //   │   ├── items: List<User>
    //   │   └── extra: Object → PageMetadata
    //   └── metadata: Object → RequestMetadata
}
```

The type hierarchy analysis discovers:
- **Generic type arguments**: `List<User>`, `Map<String, Order>`
- **Object field assignments**: Actual types assigned to `Object` fields via setters
- **Cross-method tracking**: Fields assigned in one method, returned in another
- **Nested structures**: Up to 10 levels deep (configurable)

### Find HTTP Endpoints

```kotlin
// Load a Spring Boot application
val loader = JavaProjectLoader(LoaderConfig(
    includePackages = listOf("com.example"),
    includeLibraries = true
))
val graph = loader.load(Path.of("/path/to/app.jar"))

// Find all endpoints
val endpoints = graph.endpoints().toList()
endpoints.forEach { endpoint ->
    println("${endpoint.httpMethod} ${endpoint.path} -> ${endpoint.method.name}")
}

// Filter by pattern and HTTP method
val userEndpoints = graph.endpoints(
    pattern = "/api/users/*",
    httpMethod = HttpMethod.GET
).toList()
```

### Low-Level Dataflow Analysis

```kotlin
import io.johnsonlee.graphite.analysis.DataFlowAnalysis

// Backward slice: find all values that can flow to a node
val analysis = DataFlowAnalysis(graph)
val result = analysis.backwardSlice(nodeId)

// Get all constants
result.constants()      // Direct constants only
result.allConstants()   // Including enum constants from field access
result.intConstants()   // Integer values only
```

## CLI Tool

Graphite includes a command-line tool for quick analysis without writing code.

### Build CLI

```bash
./gradlew :graphite-cli:fatJar
```

### Usage

```bash
java -jar graphite-cli-1.0.0-SNAPSHOT-all.jar find-args <input> [options]
```

### Examples

```bash
# Find integer AB IDs
java -jar graphite-cli.jar find-args app.jar \
  -c com.example.AbClient \
  -m getOption \
  -p java.lang.Integer \
  --include com.example

# Find feature flags with JSON output
java -jar graphite-cli.jar find-args app.jar \
  -c org.ff4j.FF4j \
  -m check \
  --include com.example \
  -f json

# Find enum AB IDs
java -jar graphite-cli.jar find-args app.jar \
  -c com.example.AbClient \
  -m getOption \
  -p com.example.ExperimentId \
  --include com.example

# Track multiple arguments at once (comma-separated indices)
java -jar graphite-cli.jar find-args app.jar \
  -c com.example.AbClient \
  -m getOption \
  -i 0,1 \
  --include com.example
```

### Find HTTP Endpoints

```bash
# Find all endpoints (includes type hierarchy analysis by default)
java -jar graphite-cli.jar find-endpoints app.jar

# Find endpoints matching a pattern
java -jar graphite-cli.jar find-endpoints app.jar -e "/api/users/*"

# Find all GET endpoints under /api
java -jar graphite-cli.jar find-endpoints app.jar -e "/api/**" -m GET

# JSON output with full type hierarchy
java -jar graphite-cli.jar find-endpoints app.jar -f json
```

**Sample Output:**

```
Found 2 endpoint(s):

/api/users
  GET     /api/users/{id}
          -> UserController.getUser()
          Declared: ResponseEntity
          Actual:   ApiResponse<User>
                    ├── data: Object → User
                    │   ├── id: Long
                    │   └── name: String
                    └── message: String

Summary: 2 endpoint(s)
```

### Endpoint Pattern Syntax

The `-e, --endpoint` option supports wildcard patterns for matching endpoint paths:

| Pattern | Description | Example Match |
|---------|-------------|---------------|
| `*` | Matches a single path segment | `/api/users/*` matches `/api/users/123` |
| `**` | Matches multiple path segments | `/api/**` matches `/api/users/123/orders` |
| `{param}` | Path parameters are treated as wildcards | `/api/users/{id}` matches `/api/users/*` |

**Examples:**

- `/api/users/*` - Matches `/api/users/{id}`, `/api/users/123`
- `/api/**/orders` - Matches `/api/v1/orders`, `/api/users/123/orders`
- `/api/**` - Matches all paths under `/api/`
- `/users/{userId}/posts/{postId}` - Matches `/users/*/posts/*`

### CLI Options (find-args)

| Option | Description |
|--------|-------------|
| `-c, --class` | Target class name (required) |
| `-m, --method` | Target method name (required) |
| `-r, --regex` | Treat class and method names as regex patterns |
| `-p, --param-types` | Method parameter signature (comma-separated for multi-param methods, e.g., `-p int,java.lang.String` matches `method(int, String)`) |
| `-i, --arg-index` | Argument indices (0-based, comma-separated, e.g., `0,1,2`; default: 0) |
| `--include` | Package prefixes to include |
| `--exclude` | Package prefixes to exclude |
| `-f, --format` | Output format: `text` or `json` |
| `-v, --verbose` | Enable verbose output |

### CLI Options (find-endpoints)

| Option | Description |
|--------|-------------|
| `-e, --endpoint` | Endpoint path pattern to match (supports `*`, `**` wildcards) |
| `-m, --method` | HTTP method filter: `GET`, `POST`, `PUT`, `DELETE`, `PATCH` |
| `--include` | Package prefixes to include |
| `--exclude` | Package prefixes to exclude |
| `--include-libs` | Include library JARs from `WEB-INF/lib` or `BOOT-INF/lib` |
| `--lib-filter` | Only load JARs matching these patterns (comma-separated) |
| `-f, --format` | Output format: `text` or `json` |
| `-v, --verbose` | Enable verbose output |

### Supported Input Types

- Class directories
- JAR files
- Spring Boot executable JARs (auto-extracts `BOOT-INF/classes`)
- WAR files (auto-extracts `WEB-INF/classes`)

## Module Structure

```
graphite/
├── graphite-core/     # Core framework (zero external dependencies)
│   ├── core/          # Node, Edge, TypeDescriptor
│   ├── graph/         # Graph interface
│   ├── analysis/      # DataFlowAnalysis
│   ├── query/         # Query DSL
│   └── input/         # ProjectLoader interface
│
├── graphite-sootup/   # SootUp bytecode backend
│   └── sootup/        # JavaProjectLoader, SootUpAdapter
│
└── graphite-cli/      # Command-line tool
```

## Supported Analysis Patterns

| Pattern | Description |
|---------|-------------|
| Direct constants | `getOption(1001)` |
| Local variable | `int id = 1001; getOption(id)` |
| Field constants | `getOption(CHECKOUT_ID)` |
| Cross-class constants | `getOption(AbTestIds.HOMEPAGE)` |
| Enum constants | `getOption(ExperimentId.CHECKOUT)` |
| Conditional branches | Both branches in `if/else` are detected |
| Auto-boxing | `Integer.valueOf()` is handled transparently |

## Building from Source

```bash
# Clone repository
git clone https://github.com/johnsonlee/graphite.git
cd graphite

# Build all modules
./gradlew build

# Run tests
./gradlew test

# Build CLI fat jar
./gradlew :graphite-cli:fatJar
```

## License

```
Copyright 2026 Johnson Lee

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
