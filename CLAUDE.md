# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Workflow

Follow these steps in order when working on a task:

1. **Design** — Explore the codebase, understand the problem, and plan the implementation approach. Use plan mode for non-trivial tasks. After the plan is finalized, write it to `ROADMAP.md` and commit.
2. **Implement** — Write the code changes. Keep changes focused and avoid over-engineering.
3. **Run tests** — Run `./gradlew check` to verify all tests pass. *(Optional when running as Claude Code on web, since CI will catch failures.)*
4. **Update ROADMAP.md** — Before committing, update `ROADMAP.md` to reflect what was actually implemented, ensuring the roadmap stays in sync with the code. Mark completed items, adjust plans if the implementation diverged, and remove items that are no longer relevant.
5. **Commit** — Always show a summary of changes and wait for explicit user confirmation before committing.
6. **Squash commits and submit PR** — Squash all commits on the branch into a single commit, push, and create a PR via `gh pr create`.
7. **Watch CI build result** — CI posts build results as PR comments on failure. If the build fails, read the failure comment, fix the issue, and repeat from step 3.
8. **Ask user to approve and merge** — Once CI passes, ask the user to review, approve, and merge the PR.
9. **Publish** — NEVER publish via local `./gradlew publish*` commands. ALWAYS publish by creating and pushing a git tag, which triggers GitHub Actions to publish automatically: `git tag vX.Y.Z && git push origin vX.Y.Z`. Before publishing:
   1. Check existing versions: `gh api /users/johnsonlee/packages/maven/io.johnsonlee.graphite.graphite-cli/versions --jq '.[].name' | head -5`
   2. Determine the next version number based on existing versions
   3. Show the user the current latest version and proposed new version for confirmation
10. **Update docs** — After tagging a release, update documentation (README version references, ROADMAP.md version number, etc.) to reflect the new version.

## Project Overview

Graphite is a graph-based static analysis framework for JVM bytecode. It provides a clean abstraction layer over [SootUp](https://github.com/soot-oss/SootUp) for building custom program analyses.

## Test Coverage Requirements

**Unit test line coverage for the entire project MUST be >= 98%.** When adding or modifying code, ensure sufficient tests are written to maintain this threshold.

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

### Memory Optimization with Primitive Collections

When optimizing for memory efficiency in graph-based data structures:

#### 1. NodeId: String → Int
- String-based IDs use ~40 bytes per node (object header + char array + length)
- Int-based IDs use 4 bytes per node
- **Savings: 90% reduction in NodeId storage**

#### 2. Graph Maps: HashMap → Fastutil Int2ObjectOpenHashMap
- HashMap<Integer, V> uses ~64 bytes per entry (Entry object + boxing)
- Int2ObjectOpenHashMap uses ~24-32 bytes per entry (no boxing, open addressing)
- **Savings: 50-60% reduction in map overhead**

#### 3. Benchmark Results (500K nodes)
```
NodeId:     40 bytes → 20 bytes per node (50% savings)
Graph maps: 64 bytes → 31 bytes per entry (51% savings)
Total:      63% memory reduction for large applications
```

### Feature Flags & Special Case Handling Anti-Patterns

When adding special handling for specific cases (like collection factory methods), several pitfalls emerged:

#### 1. Testing "Bug as Feature" Trap
- A test that validates "feature X disabled should NOT find results" may actually be testing broken behavior
- **Correct approach**: Regression tests should verify "scenarios that worked before still work after changes"
- Example: Testing `expandCollections=false` shouldn't find constants was actually validating a bug

#### 2. Early Return Breaks Generic Traversal
```kotlin
// BAD: Special case with early return breaks default behavior
if (isSpecialCase) {
    if (config.enableSpecialHandling) {
        // do special handling
    }
    return  // <- This breaks normal traversal when flag is false!
}

// GOOD: Special case only adds behavior, doesn't remove default
if (isSpecialCase && config.enableSpecialHandling) {
    // do special handling
    return  // Return only when special handling is active
}
// Default traversal continues for all other cases
```

#### 3. Config Flags Add Complexity Without Value
- Before adding a config flag, verify the default behavior actually needs changing
- The original backward slice already traversed all incoming edges correctly
- Adding `expandCollections` flag created complexity and introduced bugs
- **Rule**: If the default behavior is correct, don't add flags to disable it

#### 4. Test Coverage Blind Spots
- Original `FeatureFlagAnalysisTest` tested direct constant passing: `getOption(1001)`
- Real-world usage included collection patterns: `getOption(List.of(AbKey.KEY))`
- Bug only surfaced in production use, not in tests
- **Rule**: Test coverage should mirror real-world usage patterns

## Productivity Insights

### Claude vs Staff Engineer: Type Hierarchy Analysis Feature

The Type Hierarchy Analysis feature (commit `7869f98`) provides a real-world comparison:

| Metric | Staff Engineer | Claude |
|--------|----------------|--------|
| **Scope** | +4,267 lines, 23 files, 46 tests | Same |
| **Calendar time** | ~2 weeks | ~3-4 hours |
| **Pure coding time** | ~4-6 days | ~2-3 hours |
| **Speedup** | baseline | **10-20x** |

#### Staff Engineer Breakdown (8-14 days)
| Phase | Effort |
|-------|--------|
| Design & Planning | 0.5-1 day |
| Research (JVM signatures, ASM) | 0.5-1 day |
| Core Implementation | 2-3 days |
| Signature Parsing | 1-2 days |
| Test Fixtures | 0.5-1 day |
| Test Cases | 1-2 days |
| Debugging & Edge Cases | 1-2 days |
| Code Review & Refinement | 0.5-1 day |

#### Why Claude is Faster
1. **No context switching** - Uninterrupted focus on the task
2. **No meetings** - 100% of time spent coding
3. **Instant knowledge access** - No need to look up APIs or documentation
4. **Parallel exploration** - Can explore multiple approaches simultaneously
5. **No code review cycles** - Immediate iteration on feedback

#### When Staff Engineers Excel
1. **Ambiguous requirements** - Better at clarifying with stakeholders
2. **System design** - Broader architectural context
3. **Team coordination** - Cross-team dependencies
4. **Production incidents** - On-call and debugging live systems
5. **Long-term ownership** - Maintenance and evolution over years
