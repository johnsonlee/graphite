# Graphite Universal Schema: Product Scope, Goals and Non-goals

English | [简体中文](universal-schema-scope.zh.md)

Discussion draft, November 28, 2026. This document defines the problems a universal schema should address and the criteria for reviewing future proposals. IPA, ELF and Mach-O are prospective inputs used to test the scope. The compatibility baseline is what Graphite supports on main today.

## 1. Graphite's role

Graphite is a static analysis tool for software artifacts and systems. It extracts facts from programs, build outputs, resources, configuration and compiler metadata. Users can ask what a system contains, how its components depend on each other, and where calls and data might flow. They should be able to trace each answer to its evidence.

**A single artifact and an entire microservice system are both first-class use cases.** For an artifact, the supplied input usually defines the boundary. For a system, the boundary also depends on which services, versions and environments the analysis covers. Several artifacts can describe one service, and many services can ship the same library. Artifact, graph and service counts therefore measure different things.

| Scope | User's question | What the answer should establish |
| --- | --- | --- |
| Single artifact | Which frameworks ship in this IPA? Does the main executable or an extension declare a dependency on the payment framework? | Identify the bundle members, their owners and the dependencies each image declares. Explain which dependencies can be matched to packaged members. Finding a file in the package establishes only that it ships there. |
| Whole system | If the payments service changes its refund API, which call sites in which services could be affected? | Identify the service and version at each end, the location in code or in an artifact, and the protocol or configuration evidence connecting them. Distinguish services that merely include the payments SDK from those with evidence of a refund call. |

The universal schema should preserve the meaning of the facts behind these answers across inputs, build stages and query implementations. It should also let users connect independently produced facts when the evidence supports the connection.

## 2. Problems to solve

### 2.1 Languages, formats and input boundaries overlap

Java and Kotlin can both produce JVM class files. A Kotlin program can appear as JVM bytecode or Android DEX. An APK can contain DEX, ELF shared libraries and resources together. An IPA can contain an app and extensions with their own executables. A standalone Mach-O file can contain multiple architectures. Project directories and compiler indexes need build context to interpret. Assigning one graph vocabulary to each language loses these distinctions and duplicates work when the same format arrives through different entry points.

### 2.2 Facts can disappear or change meaning between extraction and query

Recognizing a fact in a frontend is only the first step. Interchange, persistence, reopening and querying must preserve it. Older JVM graphs stored class literals as integer zero, so a newer reader cannot tell which zeros once denoted types. In the Apple workflow, a save once dropped 10,027 enum declarations because they had no corresponding constant nodes. The existing query checks passed even though the inventory was incomplete. [Class literal compatibility](webgraph-storage.md#class-literals-node-tag-16) · [Apple enum round-trip evidence](apple-frontend-validation.md)

### 2.3 Similar observations support different conclusions

A library can ship in an artifact, an image can declare a dependency on it, code can reference one of its symbols, and a particular implementation can execute at runtime. Each claim requires different evidence. The distinction matters at higher levels too. In JavaScript, a default parameter can receive a value even when the caller omitted the argument. A cross-format query must preserve distinctions that change its answer. [JavaScript artifact semantics](typescript-frontend.md)

### 2.4 Connections across artifacts and services need evidence

Different versions can export symbols with the same name. Images for different architectures can contain the same numeric address. Several services can expose the same HTTP path. Users need to know why Graphite connects two observations and which alternative matches remain possible. For a microservice call, the request in application code, the SDK wrapper, the service configuration and the remote API can all live in different artifacts. A dependency edge carrying only service names cannot explain which code a change might affect.

### 2.5 An empty result can have several causes

A stripped input can lack a symbol name. A missing call edge can reflect a method body skipped during analysis. A consumer can be absent because its service was outside the system inventory. A query can also stop early through cancellation or a work budget. Users need to locate the gap in the input, graph construction, system coverage or query execution. Graphite already records method-body analysis scope and reasons for skipping bodies. [Analysis completeness](analysis-completeness.md)

## 3. Goals

1. **Preserve 100% of the existing supported scope.** The baseline includes JARs, WARs, class directories, APKs, AARs, emitted JavaScript files and artifact directories, Swift packages, Xcode projects and workspaces, and existing compiler index stores supplied with source. Preserve each entry point's options, scan boundaries, facts and public query behavior. Existing graphs must remain readable. Facts must retain their meaning through import, save and reopen, with verifiable compatibility for public identifiers, APIs, Cypher results and missing values. Recovering information already lost in an old graph requires fresh evidence. [Current inputs](../README.md#inputs-by-language)
2. **Allow new input formats to be added independently.** Each format should be able to introduce native concepts and reuse shared ones. Most of the work should go into understanding the format and extracting its facts. Routine extensions should avoid requiring coordinated releases of every storage, query and presentation implementation. A claim of ELF or Mach-O support should identify the questions it covers, such as member inventory, load dependencies, symbols, source correspondence or call analysis.
3. **Give equivalent questions consistent answers.** When different formats expose equivalent facts, users should be able to ask the same question and interpret the answers the same way. Link dependencies in ELF and Mach-O are one example. The underlying format-specific evidence should remain accessible, and facts that depend on language semantics should retain that precision.
4. **Support system-wide analysis while preserving attribution.** Users should be able to trace a symbol or call site to its artifact, service and version, or start from an API change and find potentially affected locations across the system. An incomplete service inventory, a different deployment environment or an ambiguous route must qualify the conclusion.
5. **Make completeness claims testable.** Distinguish direct observations, analysis inferences and unknowns. State the input scope, the kinds of facts covered and whether execution completed. An absence claim requires a complete check of the stated scope. Confirmed findings within a covered subset should remain useful when other parts are unknown.

## 4. Non-goals

- **Lossless archiving and byte-for-byte input reconstruction.** Graphite can record an input's identity and the location of evidence. Reconstructing an identical JAR, IPA or ELF is outside its remit. Details such as ZIP compression parameters enter scope only when they affect an analysis question Graphite undertakes to answer.
- **Exhaustive coverage of every format or uniform depth across formats.** Reading load dependencies from a stripped ELF can be a complete capability in its own right, even when local function names are unavailable. Format support alone makes no promise to model every field, recover every instruction's semantics or provide source-level dataflow.
- **Full recovery of source code, author intent or a unique call graph.** Optimization, inlining, erasure, obfuscation and stripping change what survives in an artifact. Missing debug information, symbols or resolution context can leave multiple candidates or an unknown result.
- **A complete compiler IR for all languages.** The program questions Graphite undertakes to answer determine the common semantics. Language-specific concepts can retain their own meanings without requiring a counterpart in the JVM, Swift, JavaScript and machine code.
- **Reconstructing the full runtime system from static inputs alone.** Packaged components, potential calls and service connections supported by configuration are in scope. Identifying the instances deployed at a particular time, the route a request actually took or the implementation that ran requires deployment or runtime evidence.

## 5. Reviewing future proposals

For each proposed capability, state the user's question, the evidence needed to answer it and the conditions under which an absence claim would be justified. Three questions can guide review.

- **Would an existing user lose an answer?** Compare the full fact inventory and public query results for the same supported inputs, options and legacy graphs. Include metadata with no corresponding nodes and the behavior when a fact was never collected.
- **Is a new input useful on its own, and can it supplement an existing analysis?** A standalone Mach-O should yield findings from its own contents. Adding a matching dSYM should make the new source associations identifiable. The same ELF supplied directly or inside an APK should yield consistent facts about its contents while retaining its package membership where applicable.
- **Can a system-wide query guide action within the limits of the evidence?** For a refund API change, identify callers backed by evidence and flag services, versions or communication paths with insufficient coverage. Moving from “two callers found in 18 analyzed services” to “the system has only two callers” requires evidence that the system boundary and relevant call paths were fully covered.

Safe saves, corruption detection, query cancellation and budgets, pagination, and performance under representative multi-graph load retain their own engineering acceptance criteria. This document defines the semantics those efforts must preserve. The earlier prototype offers experience to draw on. Physical formats, index layouts, APIs and migration steps belong to the subsequent design review. [Earlier universal schema proposal #169](https://github.com/johnsonlee/graphite/pull/169) · [Repository acceptance criteria](../CONVENTIONS.md)
