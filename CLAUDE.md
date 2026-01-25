# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## IMPORTANT: Review Requirements

**All git commits and version publishing MUST be reviewed and approved by the user before execution.**

- Do NOT run `git commit` without user approval
- Do NOT run `./gradlew publish*` without user approval
- Always show a summary of changes and wait for explicit user confirmation before committing or publishing

## Project Overview

Graphite is a graph-based static analysis framework for JVM bytecode. It provides a clean abstraction layer over [SootUp](https://github.com/soot-oss/SootUp) for building custom program analyses.

## Build Commands

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew :graphite-core:build

# Run tests
./gradlew check

# Run a specific test class
./gradlew :graphite-sootup:test --tests "io.johnsonlee.graphite.sootup.UseCaseValidationTest"
```

## Module Structure

```
graphite/
├── graphite-core/          # Core framework (zero external dependencies)
│   ├── core/               # Node, Edge, TypeDescriptor, MethodDescriptor
│   ├── graph/              # Graph interface, DefaultGraph
│   ├── analysis/           # DataFlowAnalysis
│   ├── query/              # QueryDsl - declarative query API
│   └── input/              # ProjectLoader interface, LoaderConfig
│
└── graphite-sootup/        # SootUp backend
    └── sootup/             # JavaProjectLoader, SootUpAdapter
```

## Key Abstractions

| Abstraction | Description |
|-------------|-------------|
| `Node` | Program element: constant, variable, parameter, return value, call site |
| `Edge` | Relationship: dataflow, call, type hierarchy |
| `Graph` | Unified program representation, supports traversal and queries |
| `ProjectLoader` | Interface for loading bytecode into Graph |
| `DataFlowAnalysis` | Backward/forward slice analysis |
| `GraphiteQuery` | Declarative query DSL |

## Usage

```kotlin
// Load bytecode
val graph = JavaProjectLoader(LoaderConfig(
    includePackages = listOf("com.example")
)).load(Path.of("/path/to/app.jar"))

// Query: find constants passed to specific methods
Graphite.from(graph).query {
    findArgumentConstants {
        method {
            declaringClass = "com.example.SomeClass"
            name = "someMethod"
            parameterTypes = listOf("java.lang.Integer")
        }
        argumentIndex = 0
    }
}

// Dataflow: backward slice from a node
val analysis = DataFlowAnalysis(graph)
val result = analysis.backwardSlice(nodeId)
result.constants()  // all constant values that flow to this node
```

## Dependency Management

Dependencies are managed via version catalog (`gradle/libs.versions.toml`).

## Publishing

This project publishes artifacts to GitHub Packages. Both manual and automated publishing are supported.

### Prerequisites

Configure GitHub credentials in `~/.gradle/gradle.properties`:

```properties
gpr.user=your-github-username
gpr.key=your-github-token
```

Token requires `write:packages` permission.

### Manual Publishing

Specify the version via command line property:

```bash
# Publish alpha version
./gradlew clean publishAllPublicationsToGitHubPackagesRepository -Pversion=0.0.1-alpha.2

# Publish release version
./gradlew clean publishAllPublicationsToGitHubPackagesRepository -Pversion=1.0.0
```

### Automated Publishing (GitHub Actions)

Publishing is triggered by creating a git tag. GitHub Actions automatically derives the version number from the tag name.

```bash
# Create and push a tag to trigger release
git tag v1.0.0
git push origin v1.0.0
```

### Using Published Artifacts

Add GitHub Packages repository to your project:

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

### Release Workflow

Follow this progression when releasing versions:

| Stage | Version Format | Purpose |
|-------|----------------|---------|
| 1. Alpha | `x.y.z-alpha.n` | Internal testing during active development |
| 2. Beta | `x.y.z-beta.n` | Limited testing with early adopters |
| 3. RC | `x.y.z-rc.n` | Public testing, feature-complete |
| 4. Release | `x.y.z` | Production-ready stable release |

Example version progression:
```
0.0.1-alpha.2 → 0.0.1-beta.1 → 0.0.1-rc.1 → 0.0.1
```
