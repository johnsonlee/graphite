# JVM Migration to the Universal Graph Schema

English | [简体中文](jvm-generic-types.zh.md)

Status: design draft, not implemented. Date: October 6, 2026.

The core design is in the [Graphite Universal Graph Schema Proposal](graph-schema.md). JVM is one profile of the universal schema. This document defines migration of the existing JVM graph, storage, and query behavior; it does not define a separate format for generics. Adding a language must not require changes to the common wire format or codec.

## Agreed component boundaries

Kotlin owns JVM analysis and IR output, without implementing a reader or writer for the new persisted format. NodeSerializer, GraphStore, and MappedWebGraphBackedGraph remain within their existing v1–v3 compatibility scope. Keep v3 writing during the transition for differential checks, then retire Kotlin query and service modules on the agreed schedule after the Rust cutover. MmapGraphBuilder may remain frontend-internal working storage, preserving the complete types that will be written to IR.

Rust owns persistence, indexing, queries, and Explorer for the new format. Kotlin changes focus on the model, extraction, IR output, and temporary graph construction; they do not port mapped readers, index recovery, property scans, or the Cypher engine to the new format. The Rust JVM profile provides compatibility properties for new graphs.

## Current gaps

| Stage | Current behavior | Change location |
| --- | --- | --- |
| Type model | className and typeArguments cannot fully express scopes, wildcards, and bounds | [Node.kt](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Node.kt) |
| Bytecode parsing | Field, method, and class Signatures are read, but some structure is discarded | [GenericSignatureParser.kt](../frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/GenericSignatureParser.kt), [BytecodeSignatureReader.kt](../frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/BytecodeSignatureReader.kt) |
| Graph construction | Fields retain some generic information; parameters and return values primarily use Soot's erased types | [SootUpAdapter.kt](../frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SootUpAdapter.kt) |
| Mmap | The type pool deduplicates by className and discards type arguments | [MmapGraphBuilder.kt](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/MmapGraphBuilder.kt) |
| Persistence | Types in nodes and embedded methods are stored as raw class-name string IDs | [NodeSerializer.kt](../frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeSerializer.kt) |
| Queries | Explicit properties, full node output, and fast scans all depend on raw class names | JVM/Rust property accessors, columns, scan, materialize, and Explorer |

The main proposal replaces fixed node tags, edge-label bit fields, and query allowlists with generic mechanisms. The JVM profile must not keep extending those enums.

## Mapping JVM information to the common model

| JVM information | Common records |
| --- | --- |
| Class, field, and method identity | Module and Symbol; descriptors are JVM profile data |
| Class and method formal parameters | Scope and Parameter, bound by declaration position |
| Types such as List<User> | Expression using common apply, named, and parameter-reference structures |
| Wildcard, bound, owner, and array | Corresponding operators, operands, and Constraints, preserving direction, order, and owner |
| Field, parameter, and return types | Declared TypeUse for the corresponding node |
| Existing actualType | Separate resolved or inferred TypeUse, without overwriting the declared type |
| Generic superclasses and interfaces | Declaration references and constraints, preserving the original inheritance topology semantics |
| callee/caller | Symbol references and available declared types; these do not imply that call-site generic substitution has been performed |

JVM erased types come from descriptors; the JVM adapter maps existing className access. A common Expression does not require erasedType, and other languages do not need to invent JVM fields. Method matching and existing public signature strings retain their semantics; do not append List<User> to the raw className.

The key for reading method Signatures and distinguishing generic scopes uses the owner class, method name, and full JVM descriptor, including the return type. The existing public MethodDescriptor.signature omits the return type and cannot serve as the unique key for all new declarations. Reference type variables by scope and ordinal, and store bounds as constraints to avoid infinitely embedding T extends Comparable<T>.

Variable names are not identities. Preserve identical names in different scopes, method variables shadowing class variables, and inner classes referring to outer variables. Use complete structural equality for type deduplication while retaining erased identity for JVM method matching. Audit uses of existing data class equality, method sets, and caches.

## Extraction and graph construction

Read class, method, and field generics from the [Signature attribute](https://docs.oracle.com/en/java/javase/26/docs/specs/jvms/jvms-4.html#jvms-4.7.9). BytecodeSignatureReader accepts both the descriptor and optional Signature, and produces parameters, return types, scopes, and constraints. Where resolvable, check that Signature erasure agrees with the descriptor; report diagnostics and fall back on a conflict. Mark missing external bounds as unresolved rather than guessing a replacement for the descriptor.

SootUpAdapter integrates declaration information at the shared method-descriptor entry point. ParameterNode reuses its parameter types, and fields retain their existing entry point. Implicit constructor parameters, bridge methods, and synthetic methods require specific checks of position and identity. Preserve erased types when reliable alignment is impossible instead of binding the wrong generic parameter.

The initial JVM profile provides declared types for fields, parameters, returns, callers, and callees, plus generic inheritance information. LocalVariable initially preserves known complete types. Reading the optional [LocalVariableTypeTable](https://docs.oracle.com/en/java/javase/26/docs/specs/jvms/jvms-4.html#jvms-4.7.14) by slot and bytecode live range, and inferring T → User at call sites, are later capabilities.

Current TypeHierarchyAnalysis caches contain only raw class names and methods; field-assignment caches store only class names, and constructor inference guesses T by parameter position. Migration must identify which paths consume complete types and fix caches that conflate different instantiations. Completing persistence does not establish correct generic inference. Validate semantic binding and inference separately.

## Storage migration and legacy graphs

The Kotlin IR writer emits common records and definitions; the Rust indexer writes the generic tables and layouts from the main proposal. Do not add a JVM-specific permanent type file or private payload. Existing embedded MethodDescriptor records do not imply an existing shared methodId. Measure the cost of moving to common Symbols alongside type records; the earlier zero-growth claim for a local field replacement does not apply.

Change the Mmap construction path to deduplicate complete, scoped types. nodeTypes is currently an on-heap table, so heap use will increase even if reference widths in temporary files stay unchanged. Measure it separately.

| Situation | Behavior |
| --- | --- |
| New Rust engine reads v1–v3 | The legacy adapter preserves the original logical graph view without requiring new schema tables |
| New Rust engine reads the universal format | Decode using definitions shipped with the graph; the JVM profile provides compatibility properties |
| Kotlin legacy readers | Retain existing v1–v3 capabilities and explicitly reject the new persisted format |
| Old engine reads the universal format | Explicitly reject an unsupported physical version |
| Legacy graph migrates to the universal format | Preserve existing information; previously lost generics cannot be recovered |
| Rebuild from the original JAR | Extract generic and declaration information still present in the source bytecode |

Allocate the new wire version together with the [architecture proposal](architecture-frontend-backend.md); do not reserve a separate v4 for JVM generics first. Keep Kotlin NodeSerializer's FORMAT_VERSION at 3 rather than introducing the new format by incrementing that constant. This migration therefore adds no Kotlin version branches or new edge decoding. Rust's legacy and new readers handle their capability thresholds separately, continuing to read v1–v3 labels and metadata correctly.

The first release of the new format must support pure Rust `graphite build --from <v3-directory|v3.graphite> -o <target>`, requiring no JAR, JDK, or Kotlin. Fully migrate branchdefs, GRX/GRS, synthetic identities, resource states, and all other v3 facts, then rebuild every derived index. Merely exporting the current Rust query Graph is insufficient because it does not read some metadata trailers. See the main proposal's corpus upgrade process.

Preserve corpus graphId, public NodeId, and registration order. Physical and string IDs may be reordered; recompute content fingerprints and record source lineage. Missing generics do not make a format migration fail: a graph can be upgraded without its source JAR, but information never saved cannot be recovered.

Validate the new directory or container before switching, and retain the old graph for rollback instead of patching files in place online. The common registry, data tables, and unknown extensions must survive .graphite packaging, validation, copying, and extraction intact.

## Query and display compatibility

Existing type, actual_type, method, callee_signature, and caller_signature properties retain their JVM compatibility views. On some nodes, type currently means the node kind and cannot simply be changed to the declared type. fieldType, paramType, and varType retain their meanings. Expose the new schema's structure through generic query access; complete declared types are projections of TypeUse, without duplicating their strings on every node.

The JVM profile may offer generic_type, actual_generic_type, and complete caller/callee parameter and return types as convenience properties; these are not core fields of the universal schema. Adding properties to full node results and properties(n) is an observable API extension and requires updating client tests that compare exact property sets. Explorer labels use short names such as List<User>, while details use qualified names and correctly escape angle brackets.

One explicit behavior correction concerns the existing field parser, which writes T directly into className. Newly built graphs should return the descriptor's erased type for type and T for the complete declared type. Queries relying on f.type = 'T' need migration. When a legacy graph contains only the string T, keep that value: it cannot reveal whether T was a variable or a class with that name.

Rust columns.rs, scan.rs, and partition.rs must not interpret new type references as StrId. Fast paths must match a validated layout and JVM profile, falling back to generic reading otherwise. Kotlin mapped properties and CallSite indexes handle only legacy formats and are not ported to the new layout. Check consistency between optimized Rust WHERE evaluation, per-node property access, and full result output so that a display fix does not leave missed query matches.

## Acceptance criteria

The JVM profile is a consumer of the generic codec. First prove the main proposal's lossless round trip for an unknown language, then validate JVM compatibility and new information:

- Cover nested generics, upper and lower wildcard bounds, generic arrays, owners, recursive bounds, identical names in different scopes, variable shadowing, bridges, and implicit constructor parameters.
- Run the same compiled fixture through Kotlin in-memory/Mmap graph construction, IR output, the Rust indexer, loading and querying, and a container round trip. Complete types and original method identities must agree and survive Rust reserialization; Kotlin need not load the new format.
- Compare Kotlin legacy, Rust legacy, and post-migration Rust queries on the same v3 fixture. Separately assert GRX/GRS, branchdefs, and missing versus empty resources so that fields unused by both query engines are still checked for loss.
- Run --from without a JDK or JARs; validate corpus resumption, source changes, individual graph failures, ID continuity, index rebuilding, cutover, and rollback.
- Fixed v1, v2, and v3 fixtures retain their legacy properties and relationships; invalid references, missing tables, and schema mismatches fail explicitly.
- Legacy and new query paths, properties(n), full node results, cross-graph references, and Explorer displays agree. Declared and inferred types remain distinct.
- Measure the complete original JAR → build → save → load → query path, reporting storage and performance effects of universal-format migration separately from JVM generic extraction.

Follow [CONVENTIONS.md](../CONVENTIONS.md) for relevant module tests, lint, and real-data benchmarks; benchmark-regression-gate must pass. Synthetic fixtures prove correctness only and cannot serve as measured evidence for performance or storage savings at 100M nodes.

## Main affected areas

| Module | Main paths |
| --- | --- |
| core | Node, TypeStructure, the Mmap type pool, TypeHierarchyAnalysis, and identity/cache keys |
| sootup | GenericSignatureParser, BytecodeSignatureReader, SootUpAdapter |
| Kotlin IR writer | Common schema, complete type/declaration/constraint output, and provenance status |
| webgraph and JVM query/display | Freeze legacy paths and retain comparison tests; add no reading or querying of the new format |
| Rust storage/build | node, graph, complete legacy metadata importer, columns, container, source, IR indexer, --from, and corpus migration |
| Rust query/display | engine/props, engine/scan, engine/partition, materialize, Explorer helpers |

Swift, TS, and other languages each provide profiles and extractors without adding language branches to the common writer or reader. Language-specific analysis capabilities are implemented separately and do not change this storage contract.
