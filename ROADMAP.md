# Roadmap

This document tracks the project's progress and planned work. Updated alongside code changes per the development workflow in [CLAUDE.md](CLAUDE.md).

**Current version:** `0.1.0-beta.17`

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
- [x] Single-pass class processing: reduced `buildGraph()` from 6 `view.classes` iterations to 2 (type hierarchy + enum values; methods + fields + endpoints + Jackson annotations)
- [x] `GraphiteExtension` SPI — pluggable extension mechanism via `ServiceLoader` for custom class processing
- [x] `ResourceAccessor` — archive resource access for JAR/WAR contents
- [x] Generic annotation extraction via `graph.memberAnnotations()`
- [x] `invokedynamic` / lambda / method reference support
- [x] Functional interface dispatch resolution (parameter callbacks, return values, fields, varargs)
- [x] Controller inheritance in endpoint discovery
- [x] Spring/Jackson processing extracted into pluggable extensions

### CLI: `find-args` (`cli/find-args`)

- [x] Find constant arguments passed to specific methods
- [x] Regex-based class/method matching
- [x] Multiple argument index tracking
- [x] Propagation path display
- [x] Enum value reference support

### CLI: `find-endpoints` (`cli/find-endpoints`)

- [x] HTTP endpoint discovery from Spring MVC annotations
- [x] Return type field discovery with `@JsonProperty` handling
- [x] OpenAPI 3.0 JSON output format
- [x] Type hierarchy display with tree formatting

### CLI: `find-dead-code` (`cli/find-dead-code`)

- [x] Three analysis modes: unreferenced detection, scan & export, assumption-based analysis
- [x] Source code editing via IntelliJ PSI (Java and Kotlin)
- [x] `SharedPsiEnvironment` singleton for PSI lifecycle management
- [x] `SourceCodeEditor` with delete/dry-run workflows
- [x] `SourceFileResolver` for mapping bytecode classes to source files
- [x] Kover coverage setup (98.2% line coverage)
- [x] Integration tests with runtime-compiled Java fixtures
- [x] `--format json` output with Gson — structured JSON for unreferenced methods, dead branches, dead methods, and summary
- [x] Kotlin source fixture integration tests (mixed Java+Kotlin source dir scenarios)
- [x] Unreferenced field detection — fields with no external `FIELD_LOAD`/`FIELD_STORE` references
- [x] Lombok-aware accessor linking — fields referenced via generated `getXxx`/`setXxx`/`isXxx` accessors
- [x] Spring API-aware field exclusions — fields in endpoint return/parameter types excluded from dead reports
- [x] Jackson annotation awareness — `@JsonProperty` keeps fields alive, `@JsonIgnore` does not
- [x] `DeleteField` action with PSI support for Java (`deleteField`) and Kotlin (`deleteProperty`)
- [x] Field output formatting in both text and JSON modes
- [x] Multi-round deletion — `--iterate` flag for iterative delete-recompile-reanalyze cycles until convergence
- [x] `--build-command` to invoke external build tool between rounds
- [x] `--max-rounds` to cap iteration rounds (default 10)
- [x] Per-round statistics and iteration summary reporting
- [x] Kover coverage at 99.0% line coverage

### CLI: `find-endpoints` (`cli/find-endpoints`) — Test Coverage

- [x] Unit tests for `formatDeclaredType`, `formatText`, `formatJsonSchema`, `buildFieldSchema`, `buildTypeSchema` (24 tests)
- [x] OpenAPI type mapping coverage: int, long, float, double, boolean, String, Date, DateTime, List, Map

### Infrastructure

- [x] GitHub Actions CI (`build.yml`) — runs `./gradlew check` on push/PR
- [x] CI build failure comments posted to PRs automatically
- [x] GitHub Actions publishing (`publish.yml`) — tag-triggered release to GitHub Packages
- [x] Automated release notes via `softprops/action-gh-release`
- [x] Version catalog for dependency management (`gradle/libs.versions.toml`)
- [x] Kover coverage expanded to all modules (graphite-core, graphite-sootup, cli/find-args, cli/find-endpoints, cli/find-dead-code)

### Test Coverage (>= 98% across all modules)

- [x] `graphite-core` — 98.2% line coverage (unit tests for Node, Edge, Graph, DataFlowAnalysis, BranchReachabilityAnalysis, QueryDsl, TypeHierarchyAnalysis, LoaderConfig)
- [x] `graphite-sootup` — 98.1% line coverage (SootUpAdapter, JavaProjectLoader, GenericSignatureParser, BytecodeSignatureReader tests; ASM visitEnd() bug fix; dead code removal for BooleanConstant/JFieldRef; isStatic fix for JFieldRef)
- [x] `graphite-cli-find-args` — 99.6% line coverage (unit + integration tests with runtime-compiled Java fixtures)
- [x] `graphite-cli-find-endpoints` — 100% line coverage (formatTypeStructure, formatFieldStructure, buildFieldSchema, formatJsonSchema tests)
- [x] `graphite-cli-find-dead-code` — 99.0% line coverage

## In Progress

(None)

## Planned

(None)
