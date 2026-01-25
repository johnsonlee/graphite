# Graphite

[![Maven Central](https://img.shields.io/maven-central/v/io.johnsonlee.graphite/graphite-core.svg)](https://search.maven.org/search?q=g:io.johnsonlee.graphite)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A graph-based static analysis framework for JVM bytecode. Graphite provides a clean abstraction layer for building custom program analyses with an intuitive Query DSL.

## Use Cases

- **AB Test ID Detection**: Find all integer/enum/string constants passed to AB SDK methods
- **Feature Flag Analysis**: Discover all feature flags used in your codebase
- **API Return Type Analysis**: Find actual return types when methods declare `Object` or generics
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
    implementation("io.johnsonlee.graphite:graphite-core:0.0.1-alpha.2")
    implementation("io.johnsonlee.graphite:graphite-sootup:0.0.1-alpha.2")
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
    implementation 'io.johnsonlee.graphite:graphite-core:0.0.1-alpha.2'
    implementation 'io.johnsonlee.graphite:graphite-sootup:0.0.1-alpha.2'
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
```

### CLI Options

| Option | Description |
|--------|-------------|
| `-c, --class` | Target class name (required) |
| `-m, --method` | Target method name (required) |
| `-p, --param-types` | Parameter types (comma-separated) |
| `-i, --arg-index` | Argument index (0-based, default: 0) |
| `--include` | Package prefixes to include |
| `--exclude` | Package prefixes to exclude |
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
