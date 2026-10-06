# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Workflow

Follow these steps in order when working on a task:

1. **Design** — Explore the codebase, understand the problem, and plan the implementation approach. Use plan mode for non-trivial tasks.
2. **Implement** — Write the code changes. Keep changes focused and avoid over-engineering.
3. **Run tests** — Run `./gradlew check` to verify all tests pass. *(Optional when running as Claude Code on web, since CI will catch failures.)*
4. **Commit** — Always show a summary of changes and wait for explicit user confirmation before committing.
5. **Squash commits and submit PR** — Squash all commits on the branch into a single commit, push, and create a PR via `gh pr create`.
6. **Watch CI build result** — CI posts build results as PR comments on failure. If the build fails, read the failure comment, fix the issue, and repeat from step 3.
7. **Ask user to approve and merge** — Once CI passes, ask the user to review, approve, and merge the PR.
8. **Publish** — NEVER publish via local `./gradlew publish*` commands. ALWAYS publish by creating and pushing a git tag, which triggers GitHub Actions to publish automatically: `git tag vX.Y.Z && git push origin vX.Y.Z`. Before publishing:
   1. Check existing versions: `gh api /users/johnsonlee/packages/maven/io.johnsonlee.graphite.graphite-cli/versions --jq '.[].name' | head -5`
   2. Determine the next version number based on existing versions
   3. Show the user the current latest version and proposed new version for confirmation
   4. Rehearse with a dry run first: `gh workflow run publish.yml --ref <branch> -f tag=vX.Y.Z` runs the release jobs from that branch and publishes nothing to any remote: Maven artifacts go to the runner's local repository (signed when the GPG secrets are available), the four binaries, `graphite.jar` and the rendered Homebrew formula are checked and kept as the `release-vX.Y.Z` artifact, the formula is installed from those assets on macOS, and both image architectures are built. It does not exercise the Sonatype upload, the GitHub Release, the tap push or the registry push; a tag with a pre-release suffix (`vX.Y.Z-rc.1`) runs those for real without touching the default channels (GitHub pre-release, no tap update, no `latest` image tag). The dispatch trigger exists once the workflow is on `main`.
9. **Update docs** — After tagging a release, update documentation (README version references, etc.) to reflect the new version. This is a docs-only change — commit and push directly to `main` without a PR.

## Project Overview

Graphite is a graph-based static analysis framework for JVM bytecode. It provides a clean abstraction layer over [SootUp](https://github.com/soot-oss/SootUp) for building custom program analyses.

## Test Coverage Requirements

**Unit test line coverage for the entire project MUST be >= 98%.** When adding or modifying code, ensure sufficient tests are written to maintain this threshold.

## Build Commands

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew :core:build

# Run tests
./gradlew check

# Run a specific test class
./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.UseCaseValidationTest"
```

## Module Structure

```
graphite/
├── frontend/
│   └── jvm/                # JVM frontend: Kotlin Gradle projects (project names have no prefix)
│       ├── core/           # Core framework (zero external dependencies except fastutil)
│       │   ├── core/       # Node, Edge, TypeDescriptor, MethodDescriptor
│       │   ├── graph/      # Graph interface, DefaultGraph
│       │   ├── analysis/   # DataFlowAnalysis
│       │   ├── query/      # QueryDsl - declarative query API
│       │   └── input/      # ProjectLoader interface, LoaderConfig
│       ├── sootup/         # SootUp backend + GraphiteExtension SPI
│       ├── cypher/         # Kotlin Cypher engine (legacy server)
│       ├── webgraph/       # Persisted graph writer/reader (WebGraph)
│       ├── query/          # `graphite.jar`: build, query, serve
│       └── explore/        # Legacy Kotlin Explorer server
│
├── backend/                # Rust backend: serves and queries persisted graphs
│   ├── storage/            # mmap reader of the persisted graph, indexes, columns
│   ├── cypher/             # Cypher parser, planner, executor
│   ├── explore/            # HTTP server, UI, C4, topology
│   └── bench/              # Kotlin-vs-Rust differential harness and benchmarks
│
├── cli/                    # `graphite` CLI (Rust): build (runs the JVM frontend), query, serve, frontend
└── Cargo.toml              # Cargo workspace root: backend/storage, backend/cypher, backend/explore, cli
```

Gradle project paths are `:core`, `:sootup`, `:cypher`, `:webgraph`, `:query`, `:explore`
(mapped to `frontend/jvm/<name>` in `settings.gradle.kts`); Cargo package names keep the
`graphite-` prefix (`graphite-storage`, ...) while their directories do not. Rust commands
(`cargo build`, `cargo test`) run from the repository root. See
`docs/architecture-frontend-backend.md` for the frontend/backend split, the Graph IR, and
the migration plan.

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
./gradlew clean publishAllPublicationsToGitHubPackagesRepository -Pversion=1.1.1-alpha.1

# Publish release version
./gradlew clean publishAllPublicationsToGitHubPackagesRepository -Pversion=1.2.0
```

### Automated Publishing (GitHub Actions)

Publishing is triggered by creating a git tag. GitHub Actions automatically derives the version number from the tag name.

```bash
# Create and push a tag to trigger release
git tag v1.2.0
git push origin v1.2.0
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
    implementation("io.johnsonlee.graphite:graphite-core:1.2.0")
    implementation("io.johnsonlee.graphite:graphite-sootup:1.2.0")
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

### Function Values: Every Lambda Shape Must Dispatch

A call on a function value (`fn.apply(x)`, `fn(x)` → `Function1.invoke`) only reaches the lambda
body if `SootUpAdapter` knows what `fn` holds. The shapes differ by compiler and version, and
Kotlin 2.x's `invokedynamic` default does **not** cover all of them:

| Shape | Bytecode | Dispatch target |
|-------|----------|-----------------|
| Java lambda / method ref, Kotlin 2.x lambda / SAM conversion | `invokedynamic` (LambdaMetafactory) | `DispatchTarget.Handle` |
| Kotlin 1.x lambda, `@JvmSerializableLambda`, `-Xlambdas=class` | `new Foo$bar$1(captures)` / `getstatic INSTANCE` | `DispatchTarget.FunctionObject` |
| Kotlin callable / property references, suspend lambdas (even in 2.x) | `FunctionReferenceImpl` / `PropertyReference1Impl` / `SuspendLambda` subclass | `FunctionObject` (property refs map `invoke` → `get`) |
| Anonymous classes, Kotlin `object :`, `$sam$` wrappers | `new Outer$1` | `FunctionObject` |
| D8/R8 desugared lambdas (Android, `minSdk < 26`), retrolambda | `new Outer$$ExternalSyntheticLambda0(captures)` (synthetic flag, so R8 renaming does not matter), `Outer$$Lambda$1` | `FunctionObject` |
| `fn::apply` (reference to a function value's own method) | `invokedynamic` with an instance handle | `DispatchTarget.Adapted` |

Lessons:
- A handle's resolved call must line up with the implementation's parameters: static handles take
  `captures ++ args`; instance handles take the first of those as the receiver.
- Function values cross methods through parameters, returns, fields, arrays and captures, in any
  processing order. They are recorded as flows between `DispatchSlot`s and resolved to a fixpoint
  in `resolveFunctionalDispatch()`, not by per-phase special cases. Override boundaries are flows
  too, derived lazily from the view's type hierarchy: a value passed to `Invoker.invoke` reaches
  the parameter of every implementation, including one inherited from a superclass that does not
  itself implement the interface, and a value returned by an implementation is what a call on the
  interface returns. `fn::apply` on a parameter is a `SlotAdapter`, applied in the fixpoint; a
  site never re-binds its own result twice, which keeps `fn = fn::apply` loops finite while
  distinct sites nest freely.
- Resolving a call is itself a flow: once `invoker.apply(seed())` resolves to `run(fn)`, the
  argument reaches `Parameter(run, 0)` and `Return(run)` reaches the result, so propagation and
  resolution alternate until nothing new resolves.
- Resolution is context-insensitive: a helper called with several function values dispatches to
  all of them.
- A function-object class is recognized by JVM-level facts (anonymous binary name `Outer$<digits>`,
  synthetic flag, Kotlin function interfaces and base classes), not by tool-specific name markers.
  Allocating one creates a call site only to the methods it implements for a supertype, so the
  helper methods of `new Object() { ... }` or `new Runnable() { ... }` get no phantom caller. A
  supertype outside the view (JDK, Kotlin stdlib) is inspected through the analysis JVM's own copy
  of the class, loaded without initialization.
- Resolution must stay bounded on inputs it cannot be precise about. A dex body (APK input) keeps
  the compiler's registers as untyped locals, each reused for hundreds of values, with no
  `LocalSplitter`; the per-local target sets are therefore shared, immutable and merged by subset
  test (a copy that brings nothing allocates nothing), the fixpoint shares one set between a slot
  and everything it fans out to (an interface parameter reaching every lambda's), and a holder
  that more than `MAX_TARGETS` (64) function values reach saturates: it resolves nothing and
  passes nothing on. Slots are numbered on first use and the fixpoint sweeps a compressed int
  flow graph in reverse postorder (`SlotPropagation`), not a map keyed by `DispatchSlot`: a data
  class hash of a `MethodDescriptor` per step was half the build. Before this, one
  6,250-statement Android method doubled its array targets on every register round trip, and
  the fixpoint copied a 27k-target set into millions of slots; 2.10.0 needed more than 40 GB for
  an APK that 2.8.0 built in 9 GB and 85 s (now 11 GB and ~100 s, with 930k resolved dispatch
  call sites more).
- Kotlin fixtures in `frontend/jvm/sootup/src/kotlinLambdaFixtures` compile twice (`indy` and
  `class`), and D8 desugars the `indy` output (`desugarKotlinLambdaFixtures`);
  `KotlinLambdaDispatchTest` runs every shape against all three outputs. Desugaring adds a
  `$r8$lambda$` trampoline per lambda, so reachability checks need a deeper hop budget.

### SootUp 3.x: What the Upgrade From 2.0.0 Changed

- API renames, all mechanical: `StmtGraph`/`MutableStmtGraph` are `ControlFlowGraph`/
  `MutableControlFlowGraph` (`builder.controlFlowGraph`), `Local`/`Value`/`Immediate` live in
  `sootup.core.jimple.common`, `BodyInterceptor` in `sootup.core.interceptor`, `Stmt.getUses()`
  returns a `List`, `AsmUtil.asmIdToSignatures` is plural, and `ApkAnalysisInputLocation`
  takes an `AndroidVersionInfo`. `SootMethod`, `SootClass` and `SootClassSource` are interfaces;
  test fakes extend `JavaSootMethod`, `JavaSootClass` and `JavaSootClassSource`.
- The bytecode frontend no longer hands out an `AsmClassSource` with a `ClassNode`: it converts
  each class into an `OverridingJavaClassSource` that already holds one `JavaSootMethod` per
  method (its ASM `MethodNode` is the body source) and releases the `ClassNode`. The adapter's
  streaming path reads those methods instead of reflecting on `classNode`; without that it
  silently fell back to `sootClass.methods`.
- `JavaSootMethod` memoizes its body. Resolving graphs through the methods the view holds keeps
  every body of the corpus alive and ran the Android SDK out of a 4 GB heap; the adapter
  processes a detached copy of each method (same body source, own cache) and drops it.
  `AsmMethodSource` keeps per-instruction scratch maps after resolving a body; the adapter
  nulls them after every path that resolves one (the method pass, the enum initialiser, bridge
  bodies, and the view's own methods once a call-graph algorithm has run), not only in the
  streaming sequence, whose `finally` runs before a `toList()` consumer resolves anything and
  never runs for an abandoned `firstOrNull`.
- `AsmClassSource.resolveMethods()` collects into a `HashSet` keyed by identity, so the order
  changes from load to load; the adapter sorts by signature. Unsorted, synthetic fingerprints
  were non-deterministic across two loads of the same classes.
- The type assigner gives every local a type: the parity baseline moved 7904 `type=unknown`
  facts to concrete types, nothing else changed. The richer `type` values also exposed a
  Kotlin engine bug the Rust parity harness had never hit: `RETURN n.type, count(*) ORDER BY
  n.type` was not sorted, because the grouped path never evaluated the sort key on the
  group's source row the way the Rust engine (and the non-grouped Kotlin path) does.
- The frontend semantic gate (`frontend-correctness/expectations.tsv`) names locals by type, so
  its oracle must say what the source holds, not what a frontend happened to infer: 2.0.0 left
  loaded locals `unknown`, which let a `forbid FIELD_LOAD ... local:int` pass in a method that
  really does read the field back (`return stored + values[0]`). Under 3.0.1 such rules are
  rewritten to the source types, and a forbid names an edge no source can produce (a
  reversed field store, an array store into an `int`). DEX registers stay untyped; 3.0.1 fixed `sput`/`aput`/`iput` read back as loads, so
  those entries left `known-deviations.tsv`.
- 3.0.1 built a large corpus 10-20% slower than 2.0.0 (kotlin-compiler: +22% on the CI gate,
  whose limit is 20%; +9.6% on an M3 Max). JFR alone could not say where: the extra wall
  time was main-thread CPU in the graph pass that the sampler under-reported, and the only
  measurement that resolved it was per-phase main-thread CPU time
  (`ThreadMXBean.getCurrentThreadCpuTime()` at each phase log) next to process CPU and GC time.
  The cause is SootUp 3's eager class source: `AsmJavaClassProvider.createClassSource` reads
  the file to check its name, reads it again, and wraps it in an `OverridingJavaClassSource`
  that converts every method's descriptor and annotations and every field of every class as
  the input is enumerated, before the graph pass, on code the JIT has not compiled, and
  under the identifier factory's lock (`cache.asMap().computeIfAbsent`, which locks on a hit
  too) when the enumeration runs in parallel: parsing 25k classes on four cores cost 17 s of
  CPU for 5.7 s of wall, against 4.5 s serial. It then drops the `ClassNode`, so the field
  generic signatures the adapter read off the node in 2.0.0 had to be read again from the
  class file, one zip entry per class (0.45 s on Tika). `ParsedClassLocation` parses each
  class once into SootUp's own node (`GraphiteClassNode`, in SootUp's package because
  `AsmMethodSource`'s constructor is package-private) and hands the view the lazy
  `AsmClassSource` that 2.0.0 handed it, so members are resolved when the graph pass reaches
  the class, on hot code and one thread; the field signatures are kept from that parse
  (`fieldSignatures`). The other costs of this frontend's that the phase CPU found, each a
  few hundred milliseconds: `resolveMethods()` called two or three times per class (each
  call converts every descriptor again; `bytecodeMethods` reads the class's memoised methods
  and caches them while the class is processed), the method sort rendering a signature per
  method (sorted by the method node's name and descriptor), and `getDeclaredField` per
  method for the scratch-field release (looked up once per class). Resolving bodies ahead on worker threads
  was measured and rejected: 4 s slower, from the same lock. A shared `AsmJavaClassProvider`
  across threads produced a `ClassNode` whose methods were plain `MethodNode`s (a
  `ClassCastException` in `resolveMethods`); one node per class does not.


### Why `buildGraph()` Is Not Parallelized

After reducing `SootUpAdapter.buildGraph()` from 6 passes to 2, parallel processing of classes within each pass was evaluated and rejected.

#### Where time is spent

| Phase | Per-class cost | Notes |
|-------|---------------|-------|
| `processTypeHierarchyForClass` | Trivial | A few map insertions per class |
| `extractEnumValues` | Moderate | `<clinit>` parsing, enum classes only |
| `processMethod` | **Heavy** | Statement graph traversal, node/edge creation — the bottleneck |
| `processClassFieldsForClass` | Light | Iterate declared fields |
| `processEndpointsForClass` | Light | Annotation reflection |
| `processJacksonAnnotationsForClass` | Light | Annotation reflection |

#### Why not parallel

1. **The bottleneck is upstream.** SootUp's `JavaView` construction (class resolution, body interception) dominates total load time. `buildGraph()` itself is a small fraction — parallelizing it yields marginal wall-clock improvement.

2. **Memory regression.** `DefaultGraph.Builder` uses fastutil `Int2ObjectOpenHashMap` for nodes and edges (51% memory savings over `HashMap`). There is no concurrent fastutil equivalent — parallelism would require falling back to `ConcurrentHashMap<Int, *>`, undoing the memory optimization that matters most for large applications.

3. **Shared mutable state.** Six adapter-level maps (`localNodes`, `fieldNodes`, `parameterNodes`, `constantNodes`, `methodReturnNodes`, `allocationNodes`) are written during method processing. `constantNodes` deduplication (`getOrPut` + `graphBuilder.addNode` side-effect) is particularly hard to make atomic without locking.

4. **Complexity vs. gain.** Thread-safe constant deduplication, concurrent edge list building, and race condition testing add significant complexity for a phase that processes ~200 filtered classes in under a second on typical workloads.

#### If revisited

If profiling reveals `buildGraph()` as a bottleneck on very large codebases, the preferred approach would be **per-class builders merged at end** (each class gets an independent builder, results merged sequentially after all classes are processed) rather than concurrent shared state. This avoids thread-safety issues but requires handling cross-class constant deduplication at merge time.

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
