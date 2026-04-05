# Graphite

[![Maven Central](https://img.shields.io/maven-central/v/io.johnsonlee.graphite/graphite-core.svg)](https://search.maven.org/search?q=g:io.johnsonlee.graphite)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A graph-based static analysis framework for JVM bytecode. Graphite provides a clean abstraction layer over [SootUp](https://github.com/soot-oss/SootUp) for building custom program analyses with an intuitive Query DSL.

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
    implementation("io.johnsonlee.graphite:graphite-core:0.1.0-rc.1")
    implementation("io.johnsonlee.graphite:graphite-sootup:0.1.0-rc.1")
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
    implementation 'io.johnsonlee.graphite:graphite-core:0.1.0-rc.1'
    implementation 'io.johnsonlee.graphite:graphite-sootup:0.1.0-rc.1'
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

### Query Member Annotations

```kotlin
// Get annotations on a class member (field or method)
val annotations = graph.memberAnnotations("com.example.User", "name")
// Returns: {"com.fasterxml.jackson.annotation.JsonProperty": {"value": "user_name"}}

annotations.forEach { (annotationFqn, values) ->
    println("@$annotationFqn $values")
}
```

### Access Archive Resources

```kotlin
// Access resource files from the analyzed archive (JAR, WAR, directory)
val resources = graph.resources

// List resources matching a glob pattern
resources.list("**/*.xml").forEach { entry ->
    println(entry.path)
}

// Read a specific resource
resources.open("META-INF/spring.factories")?.use { stream ->
    println(stream.bufferedReader().readText())
}
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

## CLI Tools

Graphite includes three command-line tools for quick analysis without writing code.

### Build CLI

```bash
# Build all CLI shadow JARs
./gradlew :cli:find-args:shadowJar
./gradlew :cli:find-endpoints:shadowJar
./gradlew :cli:find-dead-code:shadowJar
```

This produces standalone JARs under each CLI module's `build/libs/` directory:
- `cli/find-args/build/libs/find-args-<version>.jar`
- `cli/find-endpoints/build/libs/find-endpoints-<version>.jar`
- `cli/find-dead-code/build/libs/find-dead-code-<version>.jar`

### find-args

Find constant values passed as arguments to specified methods.

```bash
# Find integer AB IDs
java -jar find-args-<version>.jar app.jar \
  -c com.example.AbClient \
  -m getOption \
  -p java.lang.Integer \
  --include com.example

# Find feature flags with JSON output
java -jar find-args-<version>.jar app.jar \
  -c org.ff4j.FF4j \
  -m check \
  --include com.example \
  -f json

# Find enum AB IDs
java -jar find-args-<version>.jar app.jar \
  -c com.example.AbClient \
  -m getOption \
  -p com.example.ExperimentId \
  --include com.example

# Track multiple arguments at once (comma-separated indices)
java -jar find-args-<version>.jar app.jar \
  -c com.example.AbClient \
  -m getOption \
  -i 0,1 \
  --include com.example
```

#### Options (find-args)

| Option | Description |
|--------|-------------|
| `-c, --class` | Target class name (required) |
| `-m, --method` | Target method name (required) |
| `-r, --regex` | Treat class and method names as regex patterns |
| `-p, --param-types` | Method parameter signature (comma-separated, e.g., `-p int,java.lang.String`) |
| `-i, --arg-index` | Argument indices (0-based, comma-separated, e.g., `0,1,2`; default: 0) |
| `--include` | Package prefixes to include |
| `--exclude` | Package prefixes to exclude |
| `-f, --format` | Output format: `text` or `json` |
| `-v, --verbose` | Enable verbose output |

### find-endpoints

Find HTTP endpoints from Spring MVC annotations and analyze return type hierarchy.

Endpoint discovery works by querying `graph.memberAnnotations()` and `graph.methods()` to find methods annotated with Spring mapping annotations (`@GetMapping`, `@PostMapping`, etc.), then resolving class-level `@RequestMapping` path prefixes.

```bash
# Find all endpoints (includes type hierarchy analysis by default)
java -jar find-endpoints-<version>.jar app.jar

# Find endpoints matching a pattern
java -jar find-endpoints-<version>.jar app.jar -e "/api/users/*"

# Find all GET endpoints under /api
java -jar find-endpoints-<version>.jar app.jar -e "/api/**" -m GET

# JSON output with full type hierarchy
java -jar find-endpoints-<version>.jar app.jar -f json
```

**Sample Output:**

```
Found 2 endpoint(s):

/api/users
  GET     /api/users/{id}
          -> UserController.getUser()
          Declared: ResponseEntity
          Actual:   ApiResponse<User>
                    ├── data: Object -> User
                    │   ├── id: Long
                    │   └── name: String
                    └── message: String

Summary: 2 endpoint(s)
```

#### Endpoint Pattern Syntax

The `-e, --endpoint` option supports wildcard patterns for matching endpoint paths:

| Pattern | Description | Example Match |
|---------|-------------|---------------|
| `*` | Matches a single path segment | `/api/users/*` matches `/api/users/123` |
| `**` | Matches multiple path segments | `/api/**` matches `/api/users/123/orders` |
| `{param}` | Path parameters are treated as wildcards | `/api/users/{id}` matches `/api/users/*` |

#### Options (find-endpoints)

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

### find-dead-code

Find unreferenced code and optionally remove it with multi-round iterative deletion.

```bash
# Find dead code
java -jar find-dead-code-<version>.jar app.jar --include com.example

# JSON output
java -jar find-dead-code-<version>.jar app.jar --include com.example -f json
```

### Supported Input Types

All CLI tools accept:

- Class directories
- JAR files
- Spring Boot executable JARs (auto-extracts `BOOT-INF/classes`)
- WAR files (auto-extracts `WEB-INF/classes`)

## Module Structure

```
graphite/
├── graphite-core/          # Core framework (zero external dependencies except fastutil)
│   ├── core/               # Node, Edge, TypeDescriptor
│   ├── graph/              # Graph interface
│   ├── analysis/           # DataFlowAnalysis
│   ├── query/              # Query DSL
│   └── input/              # ProjectLoader, ResourceAccessor
│
├── graphite-sootup/        # SootUp backend + GraphiteExtension SPI
│   └── sootup/             # JavaProjectLoader, SootUpAdapter
│
└── cli/
    ├── find-args/          # Find argument constants CLI
    ├── find-endpoints/     # Find HTTP endpoints CLI
    └── find-dead-code/     # Find dead code CLI
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
| Lambda / method ref | `invokedynamic` with lambda and method reference support |
| Functional dispatch | Parameter callbacks, return values, fields, varargs |
| Controller inheritance | Endpoint discovery follows controller class hierarchy |

## Extension Mechanism

Graphite supports pluggable extensions via the `GraphiteExtension` SPI. Custom extensions are discovered automatically through `java.util.ServiceLoader`.

```kotlin
import io.johnsonlee.graphite.sootup.GraphiteExtension
import io.johnsonlee.graphite.sootup.GraphiteContext
import sootup.core.model.SootClass

class MyExtension : GraphiteExtension {
    override fun visit(sootClass: SootClass, context: GraphiteContext) {
        // Access SootUp class model to extract domain-specific metadata.
        // Use context.toMethodDescriptor() to convert SootUp methods.
        // Use context.resources to access archive resources.
        // Use context.log() for verbose logging.
    }
}
```

Register your extension in `META-INF/services/io.johnsonlee.graphite.sootup.GraphiteExtension`.

### Key Extension Points

| Feature | Description |
|---------|-------------|
| `GraphiteExtension` SPI | ServiceLoader-based plugin mechanism for custom class processing |
| `GraphiteContext` | Provides method descriptor conversion, resource access, and logging |
| `graph.memberAnnotations()` | Query annotations on class members (fields and methods) |
| `graph.resources` | Access resource files inside JAR/WAR archives |

## Building from Source

```bash
# Clone repository
git clone https://github.com/johnsonlee/graphite.git
cd graphite

# Build all modules
./gradlew build

# Run tests
./gradlew check

# Build CLI shadow JARs
./gradlew :cli:find-args:shadowJar :cli:find-endpoints:shadowJar :cli:find-dead-code:shadowJar
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
