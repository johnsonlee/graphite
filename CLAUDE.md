# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## IMPORTANT: Review Requirements

**All git commits and version publishing MUST be reviewed and approved by the user before execution.**

- Do NOT run `git commit` without user approval
- Do NOT run `./gradlew publish*` without user approval
- Always show a summary of changes and wait for explicit user confirmation before committing or publishing

**Before publishing a new version:**
1. ALWAYS check existing versions first: `gh api /users/johnsonlee/packages/maven/io.johnsonlee.graphite.graphite-cli/versions --jq '.[].name' | head -5`
2. Determine the next version number based on existing versions
3. Show the user the current latest version and proposed new version for confirmation

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

## Implementation Learnings

### Type Hierarchy Analysis & Generic Type Tracking

When implementing nested generic type analysis (e.g., `ApiResponse<PageData<User>>`), several key insights emerged:

#### 1. JVM Type Erasure Requires Constructor Analysis
- Generic type information is erased at runtime in JVM bytecode
- To discover actual type arguments, analyze **constructor calls** and trace argument types
- Example: `new L1<>(l2)` where `l2` is type `L2` → infer `L1<L2>`

#### 2. Depth Budget Management
- Multiple analysis phases (field analysis, generic analysis) consume depth budget
- Each level of nesting consumes ~2x depth (once for fields, once for generics)
- **Rule of thumb**: Set `maxDepth ≈ 2.5 × desired_nesting_levels`
- Default `maxDepth=10` supports ~5 levels; use `maxDepth=25` for 10 levels

#### 3. Caching Considerations
- Type structure caching uses `"${className}:${methodSignature}"` as key
- Cache doesn't include depth, so early analysis at higher depth can affect later queries
- Be careful with analysis order when multiple methods analyze the same types

#### 4. Cross-Method Field Tracking
- Fields assigned in one method may be returned in another
- Build a global field assignment map at initialization: `Map<"class#field", Set<assignedTypes>>`
- Scan all `CallSiteNode` for setter patterns and `FieldNode` for direct assignments

#### 5. Inheritance Hierarchy for Object Fields
- Child classes may assign to parent class Object fields
- Use heuristics when type hierarchy edges aren't available:
  - Same package suggests possible inheritance
  - Check for setter calls from child to parent's methods

#### 6. Test Depth Verification
- When testing N-level nesting, create wrapper classes L1 through LN
- Measure actual depth reached vs expected to catch depth budget issues early
- Example test pattern:
  ```kotlin
  val maxDepthReached = measureTypeDepth(result.returnStructures.first())
  assertTrue(maxDepthReached >= 10, "Should reach 10 levels")
  ```
