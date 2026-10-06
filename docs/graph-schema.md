# Graphite Universal Graph Schema Proposal

English | [简体中文](graph-schema.zh.md)

Status: design draft; not yet implemented. Date: October 6, 2026.

The goal is a stable, universal graph schema in which new languages, node categories, type constructs, and constraints are introduced through data definitions, without changing the persistent format or the generic codec. Generics are one application of this schema; JVM, Swift, and TypeScript provide mapping examples.

This proposal supersedes the extension design centered on JVM type fields. The [JVM integration guide](jvm-generic-types.md) covers only migration of existing graphs and APIs. Node enums, type expressions, and IR field layouts in the [frontend/backend architecture proposal](architecture-frontend-backend.md) must follow the extensibility contract defined here. This documentation work does not implement the new storage format.

## Required Contracts

1. Adding a language does not allocate new binary value tags or add `switch(language)` branches or exhaustive node/type kind decoding branches to the core.
2. New entities, type operations, constraints, relations, and properties use namespaced definitions. The registry travels with the file as graph data.
3. A reader unfamiliar with a language's semantics can still read its records, validate references, display fields, query, copy, and save them again without information loss.
4. Every local, intra-graph reference is explicit and has a declared target type, so generic tools can remap IDs. References must not be hidden in strings or language-private blobs.
5. Declaration identity, type expressions, language compatibility, and runtime representation are modeled separately. The core does not require every language to have a className or an erased type.
6. Different languages can express equivalent information through common concepts. Language-specific information can also be preserved completely through the same physical mechanisms, without first expanding a closed TypeExpr enum.

“No format change” means that adding languages and semantic constructs does not change the wire format. A new language still needs a frontend adapter; understanding its particular type rules may require an analysis module. Being able to store an unknown relation does not mean the system can prove that relation holds.

## Why the Current Implementation Is Insufficient

The current [JVM NodeSerializer](../frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeSerializer.kt) and [Rust node.rs](../backend/storage/src/node.rs) have 16 fixed node tags, and edge labels use fixed family/subkind bit fields. Rust's [columns.rs](../backend/storage/src/columns.rs) also reads properties directly through node tags and fixed byte offsets. JVM node category indexes and property accessors depend on known Node classes.

Adding a generic type table alone cannot remove these constraints: a new node category or relation from a new language could still require codec and query engine changes. This proposal therefore covers a common representation of nodes, edges, declarations, types, constraints, and properties, while allowing existing high-performance paths to remain as optional optimizations over the generic format.

## Three Layers

| Layer | Fixed elements | Elements extensible by a language |
| --- | --- | --- |
| Physical format | Primitive value encoding, reference encoding, schema descriptors, table directory, indexes, and record boundaries | New table instances, record layouts, and dictionary entries, all represented as data |
| Common vocabulary | Conventions with defined meanings for entity, symbol, scope, type, constraint, relation, etc. | New namespaced kinds, operators, predicates, fields, and roles |
| Language profile | How to declare identity, map the common vocabulary, and describe provenance and capabilities | New language mappings, language-specific constraints, type solvers, and renderers |

The physical format understands only facts such as “this is a list of references” or “this is a string.” The common vocabulary gives some fields meanings such as “generic arguments” or “call target.” A language profile then defines semantics such as protocol conformance, covariance, and lifetimes.

## Implementation Ownership and the Kotlin Boundary

Decision: the new persistent format has a single Rust writer/indexer/reader implementation. Query, serve, and explore for the new format all run in Rust. Kotlin gains no ability to read or write the new persistent format; it extracts JVM semantics and emits generic IR.

| Component | Responsibility after migration |
| --- | --- |
| Kotlin core and sootup | JVM analysis and complete type/declaration extraction; supply IR data |
| Kotlin IR writer | Write IR, registered definitions, and explicit references according to the common schema; do not write BVGraph or new persistent tables |
| MmapGraphBuilder/MmapGraph | May remain temporary frontend graph-building storage; preserve complete types, but do not serve as a new-format reader |
| NodeSerializer, GraphStore, MappedWebGraphBackedGraph | Freeze within the existing v1–v3 legacy compatibility range; retain existing v3 writing for comparison during the transition, without new version branches |
| Kotlin cypher/query/explore | Serve legacy graphs only during the transition and provide a differential baseline; leave new releases after the Rust cutover passes acceptance, with deletion following the retirement phases |
| Rust generic IR reader and indexer | Validate IR and generate the new format and all derived indexes |
| Rust storage/cypher/explore | Read old and new graphs and execute queries and services |
| Rust legacy importer | Import all facts from the old format and call the same indexer to migrate the corpus |

Kotlin must maintain generic structural encoding for IR, but does not need a second new-format mmap reader, property-offset reader, index recovery implementation, or query engine. Updating the shared Kotlin FORMAT_VERSION must not incidentally make the legacy NodeSerializer emit a partially implemented new format. Temporary graph-building files and published persistent graphs must be clearly distinguished.

Old Kotlin programs explicitly reject the new format and direct users to the Rust CLI, without starting an implicit conversion. v1–v3 reading retains its existing compatibility range. Completing the import of old semantics for strict migration is the Rust importer's responsibility; the Kotlin legacy reader can serve as an acceptance baseline. A dual implementation of the new format is unnecessary, but no “half the work” estimate is promised.

```text
Language frontend
  → Registered definitions + symbols + scopes + type expressions + constraints + nodes and edges
  → The same generic writer / indexer
  → The same persistent format
  → The same generic reader / query engine
  → Optional language-specific semantic analysis modules
```

## Stable Primitive Values and Records

### Primitive Value Algebra

The physical layer provides only a finite set of primitive encodings; language concepts are not binary tags:

```text
Value = Null | Bool | Int64 | UInt64 | Float64 | StringRef | Bytes
      | Ref(table, row)
      | List(elementDescriptor, values)
      | Record(layoutId, fields)
```

Any finite language syntax or analysis result is represented by composing these primitive values and references. Values such as exact arbitrary-precision integers can use records with semantic names and strings/bytes, without new primitive tags. Layouts describe arbitrarily nested structures, and readers need not execute language code.

Field definitions distinguish absence from explicit Null; lists preserve order and duplicates. A map is expressed as a list of key/value records that declares key uniqueness and whether it is sorted, rather than depending on a programming language container's iteration order. Existing Record wrappers can represent polymorphic values, without adding a Value union branch for every extension.

`Bytes` is reserved for raw content with no local, intra-graph references, such as source excerpts. Language structures and all references that must survive migration must be explicit. A frontend cannot hide type, scope, or symbol IDs in a JSON string or blob and then claim to support generic round trips.

### Self-Describing Registry

Record instances use compact file-local IDs; the registry stores complete definitions:

```text
Definition {
  name: QualifiedName,
  revision: String,
  category: QualifiedName,
  fields: [FieldDefinition]
}

FieldDefinition {
  name: QualifiedName,
  valueDescriptor: Scalar | Ref(targetTable) | List(descriptor) | Record,
  required: Bool,
  nullable: Bool,
  role: QualifiedName?
}

Record {
  layoutId: LayoutId,
  values: [Value]   // Encoded in layout field order
}
```

A QualifiedName consists of a namespace and a local name; the namespace may be a URI controlled by the publisher. Examples include `core.type.apply`, `swift.type.opaque`, and `ts.type.conditional`. These names are dictionary data, not a closed enum in code.

The category identifies a structural purpose, such as a type expression or graph node; it does not automatically declare semantic inheritance. A role can use a common definition such as `core.type.argument`, allowing generic analysis to recognize the purpose of a reference list. An unknown predicate must not acquire known semantics merely because its name looks similar.

The same name and revision must correspond to the same definition and digest; conflicting content is rejected when combining graphs. Adding fields or changing a layout creates a new definition version rather than overwriting the old definition. Merge tools preserve unknown definitions and fields, and remap every nested ID according to the reference descriptors.

The registry describes structure and checkable constraints, such as reference targets, required fields, and list order. It contains no executable scripts and does not require the backend to download and execute schema code. Renderers and semantic modules are separate, explicitly installed capabilities.

## Common Domain Records

These form the initial common vocabulary, defined through the mechanisms above. They are not an inextensible set of record tags in the physical format.

| Record | Main information | Semantic boundary |
| --- | --- | --- |
| Module | Origin, version, language profile, build configuration, artifact identity | Distinguishes modules with the same name but different origins |
| Symbol | Owning module, frontend-supplied declaration identity, display name, optional declaration-node reference | The backend does not parse language signatures to construct identity |
| Scope | Owner symbol/parent scope, binding location, parameter list | Covers classes, functions, type aliases, anonymous generics, and local bindings |
| Parameter | Scope, ordinal, parameter category, optional default value | Categories may be type, value, lifetime, effect, or a named extension |
| Expression | Operator, named/ordered operands, properties | Represents types, constant expressions, type operations, etc.; not restricted to classes |
| Constraint | Predicate, operands, scope, declaration provenance | Expresses requirements such as subtype, conforms, and same type; does not assert proof |
| Assertion | Predicate, operands, context, status, evidence | Distinguishes frontend-resolved facts, inferred results, and unknowns |
| Node | Kind, symbol, location, properties, and named references | A new node category uses a new definition without changing the reader |
| Edge | Source, target, predicate, role, ordinal, properties | Supports multiple distinct relations between the same endpoints |
| TypeUse | Owner, role, expression, context, provenance, status | Distinguishes declared, instantiated, flow-sensitive, and runtime-observed types |

Generics simply declare parameters in a Scope, then reference or apply them in Expressions. Constraints are separate relation records, rather than being flattened into `extends`. Parameter and argument lists preserve order; nested scopes and explicit parameter references handle name shadowing.

Type expressions can be shared without becoming topology nodes; a Symbol can be associated with a separate type declaration node. TypeUse is a logical relation: a common single declared-type reference can be inlined in a node layout, with additional contextual types stored in sparse records. Every node need not gain a fixed set of fields.

Type expressions and scopes may contain reference cycles. Recursive types and recursive bounds, for example, close a cycle through ID references instead of infinitely nesting values. The profile determines the precise cycle semantics of an unknown operator. Physical reading permits cycles among a finite set of records, with visited sets and work budgets for traversal. Structural deduplication does not require expanding every reference into a tree.

### Identity and Equality

An intra-graph ID is an addressing mechanism, not semantic identity across graphs. Symbol identity includes the language/profile, module identity, and frontend declaration identifier; scope identity includes its owning symbol or a stable binding location. A shared display name does not merge different `User` declarations or the `T` parameters of different declarations.

Structural type deduplication merges only records equivalent in their definition, context, identity references, and operand order. Structures whose equivalence cannot be established may remain separate. Different TypeIds do not automatically imply unequal language types, and identical structure does not imply assignability. A canonical hash must not include temporary IDs that change on reordering. Cross-file merging first resolves target identities, then performs bounded deduplication or conservatively retains separate records.

Separate TypeUse/Assertion records express declared, resolved, inferred, and observed information. Information that was not extracted is distinct from language types such as `unknown`, `any`, and `never`. Storage preserves this distinction, and analyzers must not treat absence as proof of falsehood.

## Type and Language Mapping Examples

The common vocabulary defines common operators such as named, apply, parameter reference, projection, function, tuple, record, union, and intersection. A new operator is still an ordinary Expression; generic storage does not change with the operator list.

| Example | Common structure or extension record |
| --- | --- |
| JVM `List<User>` | `core.type.apply(base=SymbolRef(List), arguments=[TypeRef(User)])` |
| Swift `Array<User>` | The same apply structure, with the base referring to the Swift Array declaration |
| TS `Array<User>` | The same apply structure, with the base referring to the declaration resolved in that TS project |
| Swift `S.Element == User` | A projection expression and a same type constraint; S references its scope parameter |
| TS `Box<User \| null>` | The apply argument references a union expression |
| JVM `? extends User` | A wildcard/bound expression or JVM-namespaced operator, preserving direction |
| Swift `some P` and `any P` | Distinct namespaced operators; opaque declaration identity and constraints are stored separately |
| TS conditional type | The check, extends, true, and false operands of `ts.type.conditional`, plus a local scope |

JVM erasure and descriptor are profile fields, accessible to existing queries through a JVM compatibility adapter. Other languages need not invent erased types. For Swift associated types and same-type constraints, see the [language specification](https://github.com/swiftlang/swift-book/blob/main/TSPL.docc/ReferenceManual/GenericParametersAndArguments.md); for TS structural compatibility and type operations, see [type compatibility](https://www.typescriptlang.org/docs/handbook/type-compatibility.html) and [types from types](https://www.typescriptlang.org/docs/handbook/2/types-from-types.html). The frontend or corresponding analysis module handles these language semantics.

### A Concrete Unknown-Language Example

A future frontend can register the following definition. This is a readable representation, not a requirement to store JSON on disk:

```json
{
  "name": "example.type.region-qualified",
  "revision": "1",
  "category": "core.expression",
  "fields": [
    {"name": "base", "valueDescriptor": {"ref": "types"}, "required": true, "nullable": false, "role": "core.type.operand"},
    {"name": "region", "valueDescriptor": {"ref": "parameters"}, "required": true, "nullable": false},
    {"name": "mode", "valueDescriptor": "string", "required": true, "nullable": false}
  ]
}
```

An old generic reader does not understand the semantics of region-qualified, but can read base, region, and mode, follow both references, query the operator, remap TypeId and ParameterId, and save the result again. Only specialized analysis such as “are these two regions compatible?” needs a new semantic module. The schema itself carries no language code that must be executed.

## Persistence and Large-Graph Costs

### Table Directory and Layout-Driven Encoding

The container manifest includes the wire format version, registry digest, language profiles, table directory, table schemas, data segment locations/sizes/checksums, and optional indexes. A new language creates new dictionary entries, record layouts, or table instances. The container continues to use the same directory and field-description mechanisms, without hardcoded language filenames.

Each table is stored in groups by layout. A group header carries the layoutId; fixed-width fields use columnar or fixed-size arrangements, optional fields use presence bitmaps, and variable-length fields use offsets and data regions. Strings and definition names are globally dictionary-encoded. The physical encoding rules are fixed here, while schema data describes the concrete field layouts. Rows do not repeat field names, JSON, or complete type strings.

For homogeneous reference fields, the layout specifies the target table and each instance stores only a 4-byte row ID. Heterogeneous references explicitly store both the target table identifier and row ID, so they cannot all be budgeted at 4 bytes. The initial ID range must be consistent across all readers and writers, with explicit errors for overflow. A later capacity-encoding upgrade is physical format evolution, not a consequence of adding a language.

Generic indexes map global node and type IDs to group/row locations; these indexes are part of the capacity budget. Type expressions, scopes, symbols, and constraints use shared storage. Fast paths may cache compiled field offsets for common layouts, but must validate them against layout digests and provide a correct generic fallback.

Edge predicates use registry IDs rather than restricting semantics to fixed 8-bit family/subkind fields. Adjacency can remain compressed, with edge properties and predicates associated through edge IDs/ordinals. Parallel edges and their ordering semantics must be preserved; multiple relations between the same endpoints must not be silently merged. Old 8-bit labels belong to the legacy decoder.

Type and property indexes are optional derived data. A new kind can initially use generic queries and scans; later acceleration does not change the semantic record format. Layout and index optimizations must not assume support for only the languages currently known.

### A Budget for 100 Million Nodes

The earlier claim that “type references remain 4 bytes, so the node size increment is zero” applies only to locally replacing JVM type slots. It is not a total-cost commitment for migration to this universal graph format.

```text
Total increase = Node layout changes + edge layout changes + shared types/declarations/constraints
               + Registry and dictionary changes + ID addressing and query index changes
               - Replaced legacy data and indexes
```

Across 100M nodes, each additional 4-byte field costs 400 MB, and each additional 1-byte field costs 100 MB, using decimal units. Consequently, kind/layout overhead is amortized per group, type and parameter definitions are deduplicated, and additional type observations use sparse storage. Schema or generic metadata is not repeated per node.

If U is the number of all distinct type expressions and B is the average record cost including the type-location index, the type portion costs U × B. Assuming B=64 bytes only, 100 thousand, 1 million, and 10 million types cost 6.4 MB, 64 MB, and 640 MB respectively. These figures exclude symbols, scopes, constraints, references, the registry, and other node/edge migration overhead, and are not measurements.

Adding a language does not change the format, but can change U, structural complexity, and the number of references. Real-data measurements must separately report file size, peak build heap memory, load RSS, query caches, and latency. Wire extensibility does not imply unchanged performance.

## Reading and Querying Unknown Extensions

The minimum generic reader capability is to parse the known physical value encodings and self-describing layouts, validate fields and references, return all raw fields and relations, and support lossless round trips. Unknown kinds must not cause records to be deleted, replaced with empty objects, or reduced to display text.

The query engine must expose at least record definition names, fields, operators/predicates, and traversable references, and support filtering by unknown kind and retrieving field values. Indexes and result projections discover fields from the registry, rather than relying solely on a precompiled node-property allowlist. New language labels can be registered as profile aliases of kinds, but must not masquerade as old node labels with incompatible semantics.

Type dependencies with known common roles can be traversed directly. Explicit references in unknown fields establish only that a reference exists, not a call, inheritance, subtype, or assignability relation. Profiles must describe extraction completeness and the boundaries where dependency analysis returns unknown/partial. Failure to extract a reference must not be interpreted as absence of a dependency.

Optional semantic modules can provide normalization, formatting, or language analysis. Generic structural display remains available without a module. If an analysis depends on an unavailable semantic capability, only that analysis is rejected with an unsupported status; reading the entire graph and generic querying remain available.

## Versioning and Compatibility

Versions are managed separately:

| Version | When it changes | Behavior of an old generic reader |
| --- | --- | --- |
| Wire format | Incompatible changes to primitive encoding, record boundaries, or index addressing | Explicitly rejects physical formats it cannot decode |
| Definition/vocabulary | New operators, fields, relations, or semantic versions | Reads according to the accompanying definitions, without requiring semantic knowledge |
| Language profile | Changes to frontend mappings, language capabilities, or toolchains | Preserves the profile and data; generic capabilities remain available |
| Analysis capability | New semantic solving and interpretation capabilities | Returns an explicit status for unsupported analyses |

Published definition content and revisions are immutable; incompatible semantics require a new definition version. Storage, copy, and merge tools must not discard fields because a profile is not installed. An unchanged wire version is a hard acceptance condition for adding a language.

Reference reordering need not produce identical file bytes, but must preserve record meaning, unknown fields, reference targets, order, multiplicity, and original non-reference bytes. Checksums are recomputed on rewriting; the manifest binds the registry, all tables, and the string dictionary. Self-description does not permit unbounded allocation: validators limit counts, nesting, reference ranges, and traversal budgets.

Rust retains a legacy adapter for current v1–v3 graphs, preserving existing JVM property values and node identities; existing files need no in-place modification. Kotlin retains legacy support only, and old writers cannot write the new format. The new format version is allocated jointly with the existing architecture proposal, without first assigning a separate, incompatible v4 to JVM generics.

Switch over after migration to a new directory/container and consistency validation, retaining old graphs for rollback. The schema cannot automatically recover generics already lost in old graphs; recovering them requires re-extraction from source inputs. Future IR uses the same logical records and registry. Its transport can be chosen independently, but the indexer must not require new fixed-field branches for every language.

## Direct Upgrade of an Existing Corpus

### Required Entry Point and Frontend-Free Migration

Decision: the first release of the new format must provide `graphite build --from <v3 graph> -o <target>`, accepting both directories and `.graphite` containers. This follows the plan in [section 5 of the architecture proposal](architecture-frontend-backend.md). The current CLI build command still delegates to the JVM frontend; this command is not yet implemented and is a release gate, not a later optimization.

```text
v3 directory or container
  → Strict Rust legacy importer
  → Logical record stream in the common IR
  → The same Rust indexer
  → New-format tables, adjacency, and indexes
  → Validation report and new directory/container
```

The entire path requires no original JAR, source code, JDK, or Kotlin process. IR can stream to the indexer in-process, without requiring another complete corpus copy on disk. Conversion processes graphs in batches and streams within resource budgets; workspaces needed for operations such as sorting can spill to disk.

Format upgrades and semantic enrichment must be distinguished: `--from` converts existing persistent facts and rebuilds all derived indexes. Re-extraction from source inputs is needed only to add generics, local-variable types, or other analysis information absent from the old graph. Missing generics neither block a format upgrade nor justify inventing missing information.

### Data Preservation and Strict Import

The migrator must not simply save the current Rust query Graph object again. After its fixed sections, the existing Rust metadata reader does not import the GRX trailer, graph.branchdefs, or GRS synthetic identities that the Kotlin writer can emit. Before the first migration release, every supported v3 semantic section must be imported, with its references represented through common records.

| Source data | Migration behavior |
| --- | --- |
| Nodes, existing edges, call and parameter references, property values, and list order | Preserve completely; do not run new semantic analysis that rewrites the source graph |
| Methods, inheritance, enum values, annotations, class origins, artifact dependencies | Convert into common declarations/properties/relations, preserving existing query values |
| Branch scopes, comparisons, valid graph.branchdefs, and its binding trailer | Validate and import, explicitly converting NodeId and statement-position references |
| GRS synthetic identities | Preserve member keys and fingerprints, without re-derivation |
| graph.resources and other raw resources | Preserve content, paths, and provenance; keep absence distinct from a valid empty table |
| Offsets, type index, string columns, CallSite/trigram indexes, overview, forward/reverse adjacency | Rebuild from source facts in the target format and regenerate binding digests; do not copy old offsets or caches |
| Information already lost in the source format, such as generics or duplicate arcs | Preserve its absence; do not claim migration can recover it |

The source graph is read-only. The importer checks boundaries, counts, labels, references, and available digests in all known sections. Tolerant or ignoring behavior in a query reader does not establish migration correctness. Damaged known derived caches are rebuilt from authoritative data. If a section containing unique semantics is damaged, mismatched, or uninterpretable, migration of that graph fails and the source remains available.

Input files and trailing sections require a complete inventory: known derived data can be discarded and rebuilt; source attachments confirmed to contain no intra-graph references can be preserved; unknown extensions that may contain semantics or references must explicitly report unsupported. Putting unknown legacy sections into Bytes does not constitute lossless conversion to the generic model. There is no silent-degradation mode by default. A migration failure does not require rebuilding from JARs: first complete the relevant legacy decoder or repair the input.

### Graph Identity and Query Continuity

Direct migration preserves public NodeIds, corpus graphIds, registration order, and existing query aliases, without generating additional topology nodes that would change old MATCH counts. New Symbols, Scopes, and similar records are stored as shared metadata. Physical rows and internal string/type table IDs may be reordered, but all references must be remapped, with an explicit index from old NodeIds to physical locations.

A graphId is the corpus registration identity and must not be replaced by a target file's content digest. The new file fingerprint must be recomputed; source fingerprint, source format version, migrator version, and conversion parameters are recorded as lineage. For directory inputs without a container fingerprint, compute content identity over the read-only source file inventory. Discard the result if source content changes during migration.

Old- and new-format graphs may coexist in the Rust service. All indexes and service caches are rebound to target content identities. The registry generation advances on cutover; source graph content caches must not be reused. Stable public graphIds and NodeIds do not imply stable file digests or cache generations.

### Batch Processing for Hundreds of Graphs

The corpus migrator invokes the same `--from` path from a manifest. The manifest records graphId, order, source path and fingerprint, target path, target wire version, importer/indexer build identities, target schema/profile definition digests, migration parameters, and task status. Concrete batch CLI arguments will be frozen when the command interface is implemented.

Each graph is pending, running, verifying, ready, or failed; publication status is recorded separately. A task can be reused idempotently only when source fingerprint, migration parameters, target wire version, importer/indexer build identities, and target schema/profile digests all match. The target digest of ready output must also be verified. Any change requires remigration, preventing an importer fix from reusing an earlier incorrect artifact. Temporary results interrupted by a crash restart from that graph; recovery from arbitrary byte positions is not required.

Targets are written to separate staging locations and, after validation, atomically published on the same filesystem at separate versioned paths, without overwriting source graphs. An existing output with the same name but mismatched content causes failure rather than replacement of another migration result. Concurrency is bounded by memory/disk budgets. A failed graph records its reason while other graphs continue; the overall batch returns a non-success status.

Capacity planning includes the retained old corpus, completed new corpus, outputs of graphs currently being migrated, and external-sort temporary files. Check the space budget before starting and scheduling work. Insufficient space causes task failure or pauses scheduling; source graphs are not automatically deleted to free space.

By default, a new registry manifest is generated and atomically activated only after every graph in the selected manifest is ready. A failed batch does not automatically publish a partial corpus. Existing requests retain their old registry snapshot; new requests use the new generation. Old manifests and files remain available for rollback. Operators may select a verified subset for a new batch, without presenting partial success as completion of the original batch.

### Validation and Release Gates

Each graph produces a migration report containing source/target identities, node and relation counts by kind, normalized record/reference digests, metadata and resource digests, missing capabilities, index build results, and elapsed time. Compare source facts against the target legacy-compatible projection through streaming; counts alone are insufficient. Verify that references still point to the same logical objects after internal IDs are reordered.

Run three-way differential checks: compare Kotlin legacy query results with Rust v3, then with Rust's new format. Add structural completeness assertions for branchdefs/synthetic identities that Rust does not currently expose. A v3 fixture with missing generics must upgrade and remain queryable without accessing JARs. Cover containers and directories, absent/empty resources, trailing sections, multiple graphs with the same NodeId, failure recovery, and rollback.

The new-format release gate requires both “build new graphs from JVM IR” and “directly upgrade an existing v3 corpus.” Validate `--from` in an environment with JDK/JAR access isolated, and use a real corpus to measure migration time, peak disk space, and new-index query costs. If real data is unavailable, record the evidence gap rather than drawing throughput or capacity conclusions from synthetic graphs.

## Implementation Sequence and Acceptance

| Phase | Deliverables | Acceptance criteria |
| --- | --- | --- |
| 1 Freeze the contract | Primitive values, references, definitions/layouts, common vocabulary, versions, and unknown-value behavior | JVM, Swift, TS, and an invented unknown profile can all be expressed through the same contract |
| 2 Generic codec | Rust generic writer, reader, validator, reference remapping, and container round trips; frontend IR writer | Unknown definitions are handled losslessly without linking any language analysis module; Kotlin does not implement a new persistent reader |
| 3 Generic queries | Schema field access, relation traversal, index fallback, structural display | Unknown kinds and predicates are queryable; specialized analyses explicitly report unsupported |
| 4 Legacy and JVM profile | Kotlin IR output, strict Rust legacy importer, build --from, corpus batch upgrades, query aliases | Migrate v3 without JARs/JDK; preserve old behavior and all metadata, and retain newly extracted JVM types throughout the pipeline |
| 5 Real-data validation | Capacity, build, load, and query benchmarks and regression gates | Separately report universal-format migration costs and method-level/end-to-end changes |
| Subsequent frontends | Swift, TS, or other language mappings and analysis modules | Adding a language changes neither the wire format nor generic codec source code |

The critical test registers a completely new profile after freezing the reader/writer, rather than merely supporting three language branches written in advance:

1. Add unknown node kinds, type operators, parameter categories, constraint predicates, and edge relations.
2. Use lists/records with nested references, identical names in different scopes, cycles, and parallel edges; supply only schema and data.
3. Validate, query, and display with the unchanged reader; reorder IDs during copying/merging, then save and read again.
4. Verify every known and unknown field, reference target, ordering, and multiplicity. Missing semantic modules affect only specialized analyses.
5. Confirm that the wire version remains unchanged throughout, with no new language branches in the generic codec or query field-access code.

Fixtures must include more than 16 node categories and more than 256 relation definitions, preventing residual limits from old tag counts and 8-bit labels. ID-reordering tests should give the two merge inputs overlapping local IDs, rather than only validating a simple copy that needs no remapping.

Also cover schema conflicts, invalid references, missing tables, container validation, legacy compatibility, and equivalence between the existing JVM fast path and the generic path. JVM scoped T, bounds, wildcards, arrays, owners, and bridges; Swift associated types and opaque identities; and TS records, unions, and unevaluated type operations all serve as structural acceptance examples. Structural fixtures do not imply the corresponding language frontends have been implemented.

Follow [CONVENTIONS.md](../CONVENTIONS.md): synthetic data is for correctness and coverage only; performance evidence must come from real persistent graphs, with missing data explicitly identified. Implementation PRs must pass `benchmark-regression-gate`, including relevant module tests/lint, `CypherBenchmark`, appropriate load/query benchmarks, and `LargeCorpusPerformanceGateTest`. An unchanged wire format does not imply the absence of performance regressions.

## Tradeoffs

A small, stable physical encoding and open vocabulary avoid enumerating every language's type system. The common vocabulary gives common analyses consistent access points, while generic access to unknown definitions lets new languages operate without a simultaneous backend release. The cost is a complete adaptation of generic storage and querying, plus metadata and indirect-access overhead that must be validated with real data.

Per-node JSON/arbitrary maps are not the primary on-disk format, and opaque blobs are not the language integration protocol. Closed binary TypeExpr or NodeKind enums extended through language-by-language branches are also excluded. The normal path for a new language is to add profiles, definitions, and records, plus semantic analysis capabilities when needed.
