# Roadmap

This document tracks the project's progress and planned work. Updated alongside code changes per the development workflow in [CLAUDE.md](CLAUDE.md).

**Current version:** `0.1.0-beta.13`

## Completed

### Core Framework (`graphite-core`)

- [x] Node/Edge/Graph abstractions with typed nodes (constants, variables, parameters, call sites)
- [x] `ProjectLoader` interface and `LoaderConfig` for backend-agnostic bytecode loading
- [x] `DataFlowAnalysis` — backward/forward slice with cached results
- [x] `GraphiteQuery` — declarative query DSL (`findArgumentConstants`)
- [x] Type hierarchy analysis with generic type tracking (nested generics via constructor analysis)
- [x] Collection parameter constant tracking (`List<Integer>`, `List<Enum>`)
- [x] Enum constant value extraction from bytecode (including boxed primitives, cached `getId()` patterns)
- [x] Method call tracing in backward dataflow analysis
- [x] Propagation path tracking and depth analysis
- [x] `BranchReachabilityAnalysis` — assumption-based dead branch detection
- [x] Memory optimization: `NodeId` String→Int, HashMap→Fastutil `Int2ObjectOpenHashMap` (63% reduction)
- [x] Performance: cached backward slice results, lazy `BranchScope` construction, `IntOpenHashSet` for branch node IDs

### SootUp Backend (`graphite-sootup`)

- [x] `JavaProjectLoader` — loads JAR/WAR/directory bytecode via SootUp
- [x] `SootUpAdapter` — converts SootUp IR to Graphite graph representation
- [x] WAR/Spring Boot JAR support (`WEB-INF/lib`, `BOOT-INF/lib`)
- [x] Smart JAR filtering to reduce memory usage
- [x] Complete constant type support (int, long, float, double, string, boolean, null, enum)
- [x] Branch scope and control flow edge support

### CLI: `find-args` (`graphite-cli-find-args`)

- [x] Find constant arguments passed to specific methods
- [x] Regex-based class/method matching
- [x] Multiple argument index tracking
- [x] Propagation path display
- [x] Enum value reference support

### CLI: `find-endpoints` (`graphite-cli-find-endpoints`)

- [x] HTTP endpoint discovery from Spring MVC annotations
- [x] Return type field discovery with `@JsonProperty` handling
- [x] OpenAPI 3.0 JSON output format
- [x] Type hierarchy display with tree formatting

### CLI: `find-dead-code` (`graphite-cli-find-dead-code`)

- [x] Three analysis modes: unreferenced detection, scan & export, assumption-based analysis
- [x] Source code editing via IntelliJ PSI (Java and Kotlin)
- [x] `SharedPsiEnvironment` singleton for PSI lifecycle management
- [x] `SourceCodeEditor` with delete/dry-run workflows
- [x] `SourceFileResolver` for mapping bytecode classes to source files
- [x] Kover coverage setup (98.2% line coverage)
- [x] Integration tests with runtime-compiled Java fixtures
- [x] `--format json` output with Gson — structured JSON for unreferenced methods, dead branches, dead methods, and summary
- [x] Kotlin source fixture integration tests (mixed Java+Kotlin source dir scenarios)

### CLI: `find-endpoints` (`graphite-cli-find-endpoints`) — Test Coverage

- [x] Unit tests for `formatDeclaredType`, `formatText`, `formatJsonSchema`, `buildFieldSchema`, `buildTypeSchema` (24 tests)
- [x] OpenAPI type mapping coverage: int, long, float, double, boolean, String, Date, DateTime, List, Map

### Infrastructure

- [x] GitHub Actions CI (`build.yml`) — runs `./gradlew check` on push/PR
- [x] CI build failure comments posted to PRs automatically
- [x] GitHub Actions publishing (`publish.yml`) — tag-triggered release to GitHub Packages
- [x] Automated release notes via `softprops/action-gh-release`
- [x] Version catalog for dependency management (`gradle/libs.versions.toml`)
- [x] Kover coverage expanded to all modules (graphite-core, graphite-sootup, graphite-cli-find-args, graphite-cli-find-endpoints)

### Test Coverage (>= 98% across all modules)

- [x] `graphite-core` — 98.2% line coverage (unit tests for Node, Edge, Graph, DataFlowAnalysis, BranchReachabilityAnalysis, QueryDsl, TypeHierarchyAnalysis, LoaderConfig)
- [x] `graphite-sootup` — 98.1% line coverage (SootUpAdapter, JavaProjectLoader, GenericSignatureParser, BytecodeSignatureReader tests; ASM visitEnd() bug fix; dead code removal for BooleanConstant/JFieldRef)
- [x] `graphite-cli-find-args` — 99.6% line coverage (unit + integration tests with runtime-compiled Java fixtures)
- [x] `graphite-cli-find-endpoints` — 100% line coverage (formatTypeStructure, formatFieldStructure, buildFieldSchema, formatJsonSchema tests)
- [x] `graphite-cli-find-dead-code` — 98.3% line coverage (previously completed)

## In Progress

- [ ] PR #20: docs — bump version to 0.1.0-beta.13

## Planned

### Unused Field Detection (`find-dead-code`)

Detect fields that are never read or written outside their own class.

**Spring API-aware analysis**: Fields and getters in Spring `@RestController`/`@Controller` response types are implicitly used by Jackson serialization at runtime, even if they have no static references in bytecode. These must be excluded from dead field reports.

Exclusion heuristics:
- Fields in classes reachable as **actual** return types of `@GetMapping`/`@PostMapping`/`@RequestMapping` etc. endpoint methods — not just the declared return type, but resolved through generic type arguments (`ResponseEntity<User>` → `User`), `Object` field assignments, and type hierarchy analysis (reusing `find-endpoints` infrastructure)
- Fields in classes annotated with `@JsonInclude`, `@JsonProperty`, `@JsonSerialize`, or other Jackson annotations
- Fields in classes used as `@RequestBody` parameter types (deserialization targets)
- Getters matching JavaBean convention (`getX()`/`isX()`) in the above classes

**Lombok-aware analysis**: Lombok annotations (`@Data`, `@Getter`, `@Setter`, `@Value`, `@Builder`, etc.) generate accessors at compile time that exist in bytecode but not in source. A field must be considered referenced if its generated getter/setter is called from elsewhere. Since SootUp operates on compiled bytecode, the generated methods are visible — `findUnreferencedFields()` must link fields to their Lombok-generated accessors (by JavaBean naming convention) and check whether those accessors are referenced.

Scope:
- `BranchReachabilityAnalysis` — add `findUnreferencedFields()` alongside existing `findUnreferencedMethods()`
- `SootUpAdapter` — track field read/write references from bytecode (`JFieldRef`)
- `SourceCodeEditor` — add `DeleteField` action with PSI support for Java and Kotlin
- `FindDeadCodeCommand` — report unused fields in text/JSON output, support `--delete`

### Multi-Round Deletion (`find-dead-code`)

Current implementation deletes dead code in a single pass. After cleanup branch or method deletion, new unreferenced methods/fields may emerge that weren't detectable in the first round. Add iterative deletion: delete → recompile → reload bytecode → reanalyze → delete again, until convergence (no new dead code found).

The assumption YAML is idempotent across rounds — deleted call sites won't match, and already-cleaned branches produce no new findings. The same YAML can be safely reused without side effects.

Scope:
- `FindDeadCodeCommand` — add `--iterate` flag to enable multi-round deletion
- Invoke external build tool (Gradle/Maven) between rounds to recompile, or operate on source-level analysis to avoid recompilation
- Report per-round statistics (e.g., "Round 1: deleted 5 methods, Round 2: deleted 2 methods, Round 3: converged")
