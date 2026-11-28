# Graphite universal schema: problems and logical model

English | [简体中文](universal-schema-logical-model.zh.md)

Design review draft under the [scope agreed in #231](universal-schema-scope.md). Graphite extracts and connects static facts about software artifacts and systems so users can investigate structure, dependencies, calls and dataflow. Preserving every currently supported input and public behavior is the first requirement. Whole-system analysis, including production cross-graph queries, is part of that baseline. Extending to additional input formats follows from it. The retained information is determined by the analysis questions Graphite undertakes to answer. Complete preservation of extracted facts does not mean recording every detail of an input, reconstructing its bytes, implementing a universal compiler IR or inferring runtime execution. Intentional corrections to existing behavior require explicit before/after review under #231.

The public compatibility baseline remains the [README at 70f908cf](https://github.com/johnsonlee/graphite/blob/70f908cf6acf4b80ce74d038cecba07f119a544b/README.md). The implementation diagnosis below is based on the code at `d51abb5c`. Historical failures are identified separately from current code behavior and risks inferred from it. Where the README and shipped behavior disagree, both must be recorded as a contract gap: this proposal neither assumes the documented behavior already works nor silently removes the promised capability. Resolving that gap requires an explicit behavior or documentation correction. This proposal selects logical contracts. File encodings, indexes, transport APIs and migration mechanics require a later design. [Prototype #169](https://github.com/johnsonlee/graphite/pull/169) and the [frontend/backend proposal](architecture-frontend-backend.md) are references, not constraints on the solution.

## 1. What exists today

There are three production graph-construction paths. Explicit IR import and reopening existing saved graphs are also supported entry points.

| Path | Implementation | Consequence for the shared contract |
| --- | --- | --- |
| JVM and Android | `JavaProjectLoader` constructs the Kotlin Graph, then `GraphStore` saves it. APK has a DEX input path; classfile and DEX declarations already feed shared declared-type processing. | These inputs do not pass through the Protobuf IR. Features added to the Kotlin graph do not automatically become available in that IR. |
| Swift and Objective-C | The Apple frontend reads compiler indexes, source and selected resources, emits Protobuf Graph IR, and invokes the JVM importer and writer. | The compiler's model, IR and Kotlin graph must each represent a fact for it to reach the saved graph. |
| JavaScript artifacts | The Web analyzer emits JSON interchange, which the Rust importer validates and saves. | This path has its own closed node vocabulary and field mappings. TypeScript/JSX must first be compiled to supported JavaScript artifacts. |

Sources: [JVM build](../frontend/jvm/query/src/main/kotlin/io/johnsonlee/graphite/cli/BuildCommand.kt), [input selection](../frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/JavaProjectLoader.kt), [Apple dispatch](../cli/src/build.rs), [Web dispatch](../cli/src/build_ts.rs), [Protobuf IR](../ir/graphite_ir.proto), [JSON importer](../backend/storage/src/interchange.rs).

The extension unit must account for input format and analysis capability. Java and Kotlin can share a classfile reader; Kotlin can also arrive as DEX. An APK can combine several formats, while an index store may need source and compilation context. A language name alone cannot select one universal interpretation.

The program model is larger than its node and edge sets. Kotlin exposes node occurrences and five edge families, plus separate methods, type hierarchies, enum declarations and constructor values, class origins, annotations, branch scopes, local definitions, declared types and analysis completeness. Persistence collects these through different accessors. Both JVM and Rust readers and query engines interpret the resulting records. Public Cypher properties and Explorer JSON have additional mappings. [Graph contracts](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/Graph.kt) · [Metadata collection](../frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/GraphMetadataCollector.kt)

Graphite already runs one Cypher query over a selected union of graphs, with joins, filters, aggregates and per-row graph attribution. Explicit fanout runs queries separately. Existing topology rules derive service dependencies from those graphs; Explorer's established workflow identifies a service by its registered graph. These are working capabilities to preserve. The open modeling question is how independently produced artifacts, service revisions and configuration contribute evidence to a system-wide answer. [Cross-graph API](../README.md#mcp-integration) · [Topology contract](topology-graph.md)

## 2. Problems and their causes

### P1. A fact has several independently maintained definitions

`ClassConstant` is an existing node in the Kotlin model, persisted records, Rust reader and JSON importer. Protobuf IR v1 has no corresponding variant. Call-site ordinals and origins also have no corresponding fields in its CallSite message. Thus the IR cannot serve as a complete interchange for the current JVM graph. This does not mean Apple currently emits those facts and loses them; the concrete defect is unequal representational capacity across paths. [Node model](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Node.kt) · [Record codec](../frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeRecordCodec.kt) · [Rust node decoder](../backend/storage/src/node.rs) · [IR](../ir/graphite_ir.proto)

Adding a fact requires reconciling producer types, interchange variants, persistence tags and payloads, mapped columns, property accessors and public projections. Sharing a name or using the same bytes at the end does not make those definitions agree. This is a direct schema problem: there is no single contract that determines what each participating boundary must carry and expose. A replacement with another hand-maintained enum at each boundary would reproduce it.

### P2. The complete fact inventory is not the node inventory

A historical Signal graph had 10,027 enum declarations in IR but lost them during saving because they had no EnumConstant nodes. The collector inferred declaration inventory from value occurrences. The current implementation fixes this with an independent enum index while retaining a fallback for older Graph implementations. The failure is repaired; its architectural lesson remains: each new metadata family needs its own enumeration and preservation path unless the contract covers all fact families. A checksum and correct answers to selected node queries cannot establish that a declaration-only fact survived. [Failure and repair](apple-frontend-validation.md) · [Current collector](../frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/GraphMetadataCollector.kt)

The required distinction is declaration versus use, and enumeration versus lookup. An enum case with an empty constructor list is a known declaration. An absent case is different. A method can exist without a caller or an analyzed body. This is a schema-level inventory problem that also requires adapters and writers to honor the inventory.

### P3. Identity, display text and resolution are entangled

A JVM method can use its declaring class, name and full descriptor as a declaration key within its input scope. That convention does not transfer unchanged to every format. Apple keeps compiler USRs in its internal model but lowers a method reference to owner, name, parameter types and return type. Web adds `#declaration@<position>` to the owner when otherwise equal method keys would collide. Apple also lowers module-level calls through a synthetic `<top-level>` caller. These are concrete adaptations to the common descriptor's shape. They are not evidence that a particular pair of declarations has already been merged. [Apple descriptors and calls](../frontend/apple/Sources/GraphiteFrontend/Emitter.swift) · [Apple symbol model](../frontend/apple/Sources/GraphiteFrontend/IndexModel.swift) · [Web declaration registration](../frontend/web/src/analyzer.ts)

The same narrowing occurs with locations. Apple distinguishes call occurrences using module, caller and callee USRs, file, line and column; the emitted call site retains descriptors, line and argument references. Separate node IDs can still distinguish calls on the same line, but the saved representation cannot recover the missing compiler identity or column from those fields.

For another input format, an exported symbol may have no known source declaration, a function may have no class owner, and an address needs an image and coordinate system. Making all of them look like a JVM method would confuse observed names with identities or resolved targets. The schema must let these concepts coexist without forcing adapters to manufacture semantic information. Existing display strings remain compatibility obligations.

### P4. A shared field does not establish shared semantics

Web records effective arguments after default-parameter handling for resolved calls. Apple records arguments obtained from matched source syntax and emits an empty list when that syntax is unavailable. The IR stores only an ordered list of node references. From that list alone, a reader cannot always distinguish a known zero-argument call from unextracted arguments, or explicit arguments from effective values. [Web analysis](../frontend/web/src/analyzer.ts) · [Documented Web semantics](typescript-frontend.md) · [Apple call emission](../frontend/apple/Sources/GraphiteFrontend/Emitter.swift)

Other examples expose the same issue. Apple emits resource key/value nodes, while the JVM resource contract can retain a configuration file without making its contents ResourceValue nodes. Apple projects protocol relationships to existing EXTENDS/IMPLEMENTS kinds; that does not preserve every Swift conformance condition. IR value decoding maps both explicit null and an unset value variant to Kotlin null. The latter is a decoder behavior, not proof that a producer emitted an unset value. [JVM resource contract](../frontend/jvm/sootup/src/test/kotlin/io/johnsonlee/graphite/sootup/ResourceConfigLinkingTest.kt) · [Apple resources](../frontend/apple/Sources/GraphiteFrontend/AppleResources.swift) · [IR value decoding](../frontend/jvm/ir/src/main/kotlin/io/johnsonlee/graphite/ir/IrReader.kt)

The README also describes a RESOURCE_CONTAINS relationship for resources, but the current IR and relationship matchers have no corresponding kind. This is a documentation/implementation contract gap to resolve explicitly; normalizing labels cannot settle it. [README resource queries](../README.md#query-resources-with-cypher) · [Rust matching](../backend/cypher/src/engine/matching.rs)

The schema needs precise meanings for observations, derived facts, field presence and collection completeness. Implementing more extraction remains frontend work. A uniform label cannot supply evidence the input or analysis never provided.

### P5. Provenance, semantic coverage and integrity have separate, incomplete contracts

These mechanisms already exist in parts of Graphite:

- The IR header carries producer name/version, source information and options. Apple writes its source-dataflow scope there. The import command prints the header and passes only the Graph to saving, so that path does not propagate the header as a persistent provenance contract. [Producer](../frontend/apple/Sources/GraphiteFrontend/Frontend.swift) · [Importer](../frontend/jvm/query/src/main/kotlin/io/johnsonlee/graphite/cli/ImportCommand.kt)
- The `.graphite` container has a file manifest with sizes and hashes, required entries and verification. This describes physical contents. It does not declare which facts were extracted, with which meanings, or what an unknown extension changes. Node format version 3 covers graphs both before and after ClassConstant was added. Legacy integer zeros cannot be reinterpreted as recovered class literals. [Container](../backend/storage/src/container.rs) · [Class-literal compatibility](webgraph-storage.md#class-literals-node-tag-16)
- Method-body completeness records scope and reasons for gaps. `/api/schema` inventories observed labels, properties and relationships. Neither is a general declaration of coverage across all fact families. [Completeness](analysis-completeness.md) · [Schema endpoint](../backend/explore/src/routes.rs)
- Error handling varies by data family. Bound declared-type data rejects missing or corrupt content. Completeness data returns no evidence for a missing file or mismatched binding/layout, but rejects a payload digest or parsing failure after the binding matches. Branch-definition data can warn and yield empty definitions. Rust `Graph::node` converts decoding errors to `None`. These distinctions cannot all be treated as a legitimate empty result. [Declared types](../frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/DeclaredTypeStore.kt) · [Completeness reader](../frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/AnalysisCompletenessStore.kt) · [Sidecar behavior](webgraph-storage.md) · [Rust node access](../backend/storage/src/graph.rs)

The schema must define what is promised and what missing, unsupported or invalid data means for that promise. Persisting provenance, validating bytes and propagating errors still require implementation changes. A graph's physical integrity, extraction coverage and query completion are three different questions.

### P6. Graph composition lacks a common artifact and system attribution model

The same SDK can appear in multiple services. One service can comprise several artifacts or graphs. A graph name can later refer to a different build. A package can contain several formats, and one file can contain several architecture slices. Class origins, weighted artifact dependencies and graph attribution already provide useful pieces; they do not together specify content identity, package occurrence, compilation context, service revision and environment.

Cross-graph joins already work without this richer model. The missing contract concerns the meaning of joins across these boundaries. Equal names or equal bytes cannot alone establish equal service identity or a call to a particular handler. A graph-derived topology edge also needs to remain distinguishable from a binding established using routing and provider evidence. This is a logical-model gap, with separate extraction and resolution work needed to populate it. [Existing topology](topology-graph.md) · [Existing origin and dependency metadata](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/Graph.kt)

### P7. Extensions are tied to producers and fixed graph vocabulary

There is already a SootUp `GraphiteExtension` SPI discovered through ServiceLoader. Its visitor accepts a SootClass; it is coupled to that frontend. It does not establish a portable definition, exchange, preservation and query contract for a new framework fact. Adding arbitrary properties also would not define a new relation's identity, multiplicity, scope or evidence. [Existing SPI](../frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/GraphiteExtension.kt)

This extension boundary was a reasonable product tradeoff. Supporting additional formats and system semantics makes it necessary to decide which extensions can travel through existing consumers and which require new analysis code. This is a schema responsibility. Discovering framework routes or understanding a new binary instruction remains domain work.

### Related engineering problems remain in scope, with different owners

The original problem inventory includes all of the following. A logical schema should expose its dependencies on them without claiming to repair them by definition.

| Problem | Current evidence and distinction | Schema's responsibility and the remaining engineering work |
| --- | --- | --- |
| Lossy modeling; absence confused with non-collection | P2–P5 include repaired losses, current semantic narrowing and existing partial completeness mechanisms. | Specify retained facts, meaning and coverage. Extraction accuracy and faithful adapters still need implementation. Lossless input reconstruction is a non-goal. |
| Adding facts touches many places; file inventory and producer history | P1 and P5. A physical archive manifest and IR producer header already exist; a shared semantic inventory and end-to-end provenance contract remain incomplete. | Define one semantic contract for participating boundaries and their evolution. Do not replace the existing manifest merely to rename it. |
| Saving over a source graph; damaged data treated as empty | The directory writer deletes sidecars before collecting some lazy metadata and writes files in place. This exposes loss/mixed-generation risks by code inspection; this review does not claim a new failure reproduction. Packed output has separate publication protections. | Define snapshot consistency and error meanings. Safe directory publication, reader lifetimes and error propagation are implementation fixes. [Writer](../frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/GraphStoreWriter.kt) |
| JVM/Rust query and JSON drift | Some differences are documented parity defects, such as internal single-graph provenance; others are intentional additions, such as Rust's `total`. Parameter-error envelopes also differ. | Shared property and identity definitions reduce duplicated interpretation. HTTP error envelopes, dialect behavior and result serialization require their own shared contracts. [Parity record](../backend/bench/parity.py) |
| Pagination, broad-query timeouts and capability discovery | SKIP/LIMIT, truncation information, cancellation, timeouts and schema discovery exist. They do not provide a stable snapshot cursor or general extraction-coverage discovery. | Supply capability and snapshot meaning. Pagination protocols, query planning and execution controls remain server work. [Parser](../backend/cypher/src/parser.rs) · [Routes](../backend/explore/src/routes.rs) |
| Process-wide IDs and allocation by maximum ID | Kotlin NodeId uses a process counter; builder offsets and saved adjacency use maximum ID plus one. The resource impact is unquantified here. | Scope identity independently of physical numbering. Allocation and index layout need separate design and representative measurement. [IDs](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Node.kt) · [Builder](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/MmapGraphBuilder.kt) |
| Timing-sensitive tests and a long verification cycle | These are engineering issues in the original inventory, not new timing measurements in this review. | Shared semantic cases can reduce duplicated expectations. Scheduler control and faster verification infrastructure do not follow from schema unification. |
| No general framework-semantic extension layer | P7 distinguishes the existing producer SPI from a portable fact contract. | Define extension semantics and preservation. Framework analysis and specialized presentation still need code. |

## 3. Design decisions derived from those problems

### D1. Make definitions and the complete fact inventory the shared boundary

A dataset declares the definitions it uses and enumerates all its semantic records, including declarations with no use nodes. A definition is identified by (namespace, name, semantic version), with named typed roles, presence and cardinality rules, identity/equality rules, and exact dependency definitions. Repeated roles declare whether order and duplicates matter. Versions identify meanings; physical format versions and producer versions remain separate. Definitions and published projection revisions are each immutable under their respective identities. Two graphs presenting different definitions under the same identity have a contract conflict. They may remain separately inspectable, but a composition depending on that identity must reject the conflict or use an explicit, versioned conversion; equal spelling never authorizes a semantic merge.

The logical value system covers typed scalars, scoped references, ordered collections and named structured values. Existing numeric distinctions, nulls, annotation maps and enum references must survive. Missing, explicit null and an empty collection are distinct. An optional field does not by itself explain whether it was inapplicable or unobserved; when that distinction affects the question, the definition supplies an observation status or a separate coverage claim. Native descriptors can remain typed opaque values with a named grammar. A consumer that does not understand that grammar can preserve the value and compare its exact representation, but cannot infer type equivalence from it.

Every participating boundary must be able to enumerate and carry these definitions and records. The producer supplies the inventory, including declaration-only families and any claimed empty enumerations. A preserving boundary accounts for that inventory, its record identities, ordered values and references in its output. A writer cannot establish preservation by rediscovering only the kinds it already knows or counting only nodes and edges. How to check that accounting efficiently belongs to implementation design, not every query. Adding a fact expressible with supported value types and relationships changes its definition and producer, without requiring a new kind-specific branch in every reader, writer and property accessor. Consumers provide generic selection by definition, role projection, reference joins and counting. A new fact can therefore be inspected before it has a specialized analysis or UI. Specialized operators and optimized access remain explicit capabilities. This is a logical access contract, not a choice of tables, serialization or Cypher syntax.

A definition contains no executable plugin. It specifies structure and semantics; domain algorithms produce facts using it. A new primitive value encoding, a new query operator or a new resolution algorithm can still require a consumer release. An arbitrary new semantic analysis cannot be delivered by merely naming a predicate.

**Example.** A class-literal occurrence is a typed value with a referenced type descriptor. Its declaration specifies how its value is exposed. All participating paths carry that definition and value. A string containing the same descriptor stays a string. An imported legacy zero stays an integer of unknown historical origin. The schema change eliminates repeated transport/property vocabulary work; recognizing class literals in bytecode remains frontend work. This addresses P1 and P2 without claiming to recover discarded information.

#### A routine extension through the whole chain

Consider a new framework profile defining `acme.http:request@1` with a call-site reference, verb, path and ordered argument bindings. Each binding retains its slot and value reference; two slots can refer to the same value. The extractor supplies the definition, scoped occurrence identity, records and extraction coverage. Assume all of its value types and structural operations are already supported by the generic contract.

| Boundary | Required behavior without a kind-specific consumer release |
| --- | --- |
| Import | Read the supplied definition, validate typed roles and reference scope, and retain the request records. No new closed-enum variant is required. |
| Save and reopen | Enumerate the submitted family independently of legacy node kinds and preserve its identity, values, references and coverage. |
| Generic query | Select the definition, filter by verb/path, join the call-site reference and return ordered bindings using the existing structural operations. A human can inspect the new fact without a framework-aware engine. |
| Export or copy | Carry the unfamiliar definition and records, preserving references. A tool unable to do so refuses the preservation operation. |

This promise applies to consumers implementing the proposed generic contract, not today's IR v1 or JSON importer. Once that contract exists, these consumers need no rebuild to carry this example. It still does not establish the destination service or handler. A specialized resolver must supply that conclusion. Publishing an additional common projection requires a declared projection revision and supported operations; an unsupported projection is unavailable, and queries depending on it are rejected. Existing `MATCH (n)`, labels and properties do not silently acquire these records.

### D2. Separate objects, occurrences and claims about them

Objects have scoped identity. Facts describe their properties or relationships and can refer to other facts. A definition decides whether a fact is identified by its participating objects and values or needs an occurrence key. Multiple bases can support one claim; multiple call occurrences remain separate even when they have equal properties.

The common program vocabulary covers declarations of types and callable/stored members, call occurrences, values, locals, parameters, returns, annotations, resources and relationships. It is a set of roles that a frontend supplies when supported. There is no requirement that every input expose types, class owners, bodies or dataflow. Independent metadata inventories participate in the same contract as node records, without being forced into the legacy node enumeration.

| Distinction | Rule | Problem addressed |
| --- | --- | --- |
| Declaration / occurrence | A method declaration survives with no callers; an enum case survives with no constant occurrence. A declaration can have several observed locations or code instances. | P2 |
| Identity / display | Use a scoped native key when available, otherwise an explicit local identity. Keep names, signatures and descriptors as attributes with their grammar. Never infer identity from an incomplete display signature. | P3 |
| Reference / target | Preserve what a site references even when no declaration is available. A resolved or candidate target is a separate fact with its conditions. | P3, P6 |
| Explicit argument / effective value | Record their roles separately, preserving argument positions and repeated references. A missing argument inventory cannot claim arity zero. Existing effective-argument projections remain available. | P4 |
| Native fact / common meaning | Preserve the native observation. Publish a common interpretation only when the observation supports it, retaining its premise. A Swift conformance projection need not claim Java inheritance semantics. | P4, P7 |

For existing JVM graphs, a method declaration key includes its owner, name and full descriptor, including return type. Existing call sites, constants and other nodes keep their occurrence IDs within the snapshot. Annotation lookup by class and member name remains a legacy lookup; it cannot acquire overload precision that its source did not have. A native symbol can exist without an identified function body. A top-level callable need not be owned by a fabricated class.

### D3. Keep content, interpretation, graph and service identities separate

| Object | Identity and relationships |
| --- | --- |
| Artifact content | An input file or a collection with an inventory. Equal digests under the same algorithm and content domain/canonicalization can establish equal content; a member digest cannot establish equality of its entire container. Without that evidence, use a local identity. |
| Artifact entry | One occurrence of content inside a container, with parent and member position/name. Two packages can contain the same content through different entries. |
| Representation | An interpretation of content in an input/compilation context, potentially using several inputs. Mach-O slices and a Swift source compiled in different targets have different interpretation scopes. |
| Location | Representation, coordinate system, required owner and position/range. The coordinate system defines unit and origin. Source locations, method bytecode offsets, section offsets, file offsets and image addresses remain distinct. Mappings are explicit facts and may be partial. |
| Snapshot | One concrete graph output with its identifier assignment. A local node ID identifies an occurrence only within that snapshot. Equal inputs/options do not prove equal output IDs. Existing registry graph names remain public handles. |
| Service revision and system view | A service identity with revision when known; a view selects snapshots, service/artifact attribution, service inventory and static environment inputs. Unknown versions stay unknown. One service can have several graphs and several services can reuse one content object. |

These contexts are required only for questions that need them. Importing an old graph does not require inventing its original build run or artifact hierarchy. A graph's current registration can continue to supply the established one-graph/one-service mapping.

Content equality allows reuse of content-derived observations; contextual conclusions also depend on compilation, linking or routing inputs. Associations across snapshots state their meaning, such as identical content, debug correspondence or conditional API binding. They do not merge node occurrences. Re-numbering for an internal representation must preserve references and the existing public identity projection. This addresses P3 and P6 while leaving the physical ID allocator open.

### D4. Carry provenance and coverage with the claims they qualify

An extraction run records producer/version, inputs and effective options when available. A direct observation names its run and optionally a locator. A derived claim names the rule/version, premise facts and interpretation context. Shared defaults can apply to a batch or scope, so this contract does not require a separate provenance object for every node. Independent observations can support the same fact; conflicting claims retain their bases.

Coverage qualifies a fact definition, a selected domain and extraction conditions within a snapshot or system view. It states complete enumeration, partial enumeration with known gaps, or unknown coverage. A narrow exception can qualify a broader claim. Existing method-body completeness becomes one contributor to this contract; it is not discarded or extrapolated to unrelated facts.

Three questions stay separate:

1. **What does the saved graph contain?** Ordinary Cypher still queries the recorded graph. Zero ResourceValue rows can be an exact answer about that graph.
2. **What does that establish about the input or system?** The absence of a configuration key needs coverage of that file's keys; the absence of other callers needs coverage of the relevant services, sites and resolution domain.
3. **Did this query finish?** Timeout, cancellation or truncated consumption cannot establish an exhaustive result, even over complete input facts.

A future reader can carry a new definition structurally without knowing a specialized interpretation. It may answer operations whose dependencies it supports. If unsupported semantics could change the selected answer, it must reject that operation, or the graph when it cannot isolate the dependency. Save/export must preserve the semantic inventory and the identities and references of unfamiliar records, including any consistent ID remapping, or explicitly refuse a preservation operation; silently dropping unknown facts is not a successful round trip. A deliberately selected lossy projection must identify its omissions and cannot inherit completeness claims for them.

Corruption of data required by a claimed capability is an integrity failure, not unknown extraction or a valid empty set. A damaged reconstructible index can fall back to intact underlying facts. Historical missing evidence remains unknown. Applying stricter errors to legacy paths is an explicit compatibility change to review, not an incidental consequence of introducing the model. This addresses P5; checksums, safe publication and error propagation remain required implementation work.

### D5. Define public projections separately from the underlying inventory

The current public graph is a compatibility projection. Its definitions specify labels and aliases, properties, edge direction/kinds, multiplicity and missing-value behavior. Existing Kotlin accessors, Cypher, REST, MCP and Explorer must preserve their supported behavior. A new declaration or provenance fact does not automatically add a row to `MATCH (n)`, a key to `keys(n)` or a relationship to an existing traversal.

Common cross-format views expose only genuinely shared meanings. Format/framework profiles own more specific definitions and declare any evidence-backed projection to a common view. Shared definitions and projection rules are the semantic authority across implementations. The implementation design must make routine mappings consumable or generated from that authority; copying another list of handwritten mappings into each engine would leave P1 unsolved. Domain inference stays in producers or analyzers and emits its conclusions as facts.

Ordinary queries must not require resolution, a service inventory or traversal of provenance merely because those facts exist. Richer views opt into those dependencies. New public projections can add functionality without reinterpreting an existing query. A custom visualization still needs presentation work, and schema unification does not make two query engines' execution or HTTP protocols identical. This addresses P1, P4 and P7 while preserving production behavior.

## 4. Walk the model through existing inputs first

These cases examine whether the decisions resolve the problems. They are semantic arguments, not evidence that an implementation of this model already works.

| Existing input or path | Facts and answers that must remain | What the proposed distinction contributes |
| --- | --- | --- |
| JAR, fat JAR, WAR, class directory | Preserve declarations, calls, all recorded dataflow/control-flow/type/resource edges, annotations, constants, class origins and analysis options. `radixSort([B)V` and `radixSort([[B)V` remain different declarations; the display signature remains unchanged. Nested libraries retain their selection and attribution. | D1 carries each fact through every supported boundary. D2 separates declaration keys from display. D3 separates nested membership from content reuse. |
| Android APK/AAR workflows | Preserve supported input behavior, DEX declaration/type facts, calls, resources and existing scan boundaries. An APK can physically contain native libraries; packaging alone does not establish that Graphite recorded those members or analyzed their code. | D3 allows a package to combine DEX, resources and future ELF representations. D4 prevents a package-level support claim from implying native call coverage. No existing workflow is silently dropped. |
| JavaScript artifacts | Preserve effective arguments, receiver, ordered repeated values, existing method keys and emitted dataflow. | D2 can add explicit-argument observations without changing the effective-value view. A query about source arity uses only an explicit inventory with adequate coverage. |
| Swift/Objective-C projects and compiler index plus source | Preserve independently declared methods and enum cases, existing descriptor strings, recorded calls, type relations, annotations, selected resources and partial source dataflow. | D2 retains native symbol identity and source/compilation context when supplied. D4 distinguishes unavailable syntax from a known empty argument list. A reader cannot recover discarded USRs from the legacy graph alone. |
| IR/import and saved directories/archives | Preserve the full recorded inventory, including declarations with no node, empty enum-value lists and existing unknowns. Existing graphs remain readable. | D1 makes metadata enumerable. D4 preserves provenance and refuses silent losses on preservation operations. Legacy class-literal zeros remain zeros. |
| JVM and Rust queries and product interfaces | Preserve the supported node families, five edge families, declaration queries, aliases, fields, direction and endpoint identity; preserve rows, multiplicities, JSON value meanings and graph attribution. | D5 keeps the current public projection. Adding a Member fact does not retarget a historical CALL edge or inflate node counts. |

The five existing edge families are DATAFLOW, CALL, TYPE, CONTROL_FLOW and RESOURCE, including their existing aliases, subkinds, flags and payloads. Branch comparisons, branch membership and ordered local definitions are additional facts to retain. Methods, enum values, declared types, class origins, synthetic identities, weighted artifact dependencies and resources remain independently available. `CallSite` and `CallSiteNode` search aliases keep their existing behavior; exposed labels and `keys(n)` do not silently expand. [Nodes](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Node.kt) · [Edges](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Edge.kt) · [Graph APIs](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/Graph.kt) · [Public queries](../README.md)

Consider a saved resource file containing `feature.mode`, with no ResourceValue nodes. The legacy key query still returns zero rows. The common view cannot conclude that the file has no such key unless key extraction covered it. Adding a key extractor creates new observations and coverage, not a retrospective claim that the previous graph was complete. This directly separates P4's interpretation problem from the work of implementing that extractor.

## 5. Whole-system analysis using existing cross-graph execution

Keep the current union and fanout modes, `graphId` and per-row `$metadata.graphIds`. Keep topology rules, C4 and endpoint grouping. A rule that extracts a target service from an RPC adapter class name remains usable and retains its stated basis. The model adds attribution and qualified claims where users need stronger answers.

Suppose orders and billing package the same payments SDK. Orders contains two call sites, C7 and C8, requesting HTTP `POST /refunds` through alias `pay`. Billing has a site B2 with the operation known but its destination unresolved. A selected staging view includes these services and legacy-worker, whose requests were not collected. The question is which sites can be statically bound to payments handler H1 in that view.

The logical facts are distinct and independently inspectable:

| Fact | What it establishes |
| --- | --- |
| Package membership and service attribution | Which selected service revision contains each occurrence. SDK membership alone establishes no refund call. |
| Request at C7/C8 | A call occurrence with protocol, operation and observed destination reference. |
| Staging routing facts | Applicable destinations for alias `pay` under the declared routing rules. |
| Provider facts | Operations and handlers exposed by the selected destination revisions. |
| Candidate/binding fact | A resolver's conclusion, with request, routes, providers, rule/version, context and relevant coverage as its basis. |

For this example, the resolver follows the alias and matches protocol, verb and path. Its scope excludes runtime overrides, fallback and rewriting. A unique binding requires complete applicable routes and provider enumeration, evaluated precedence rules and exactly one viable handler. The definition is conditional on that model of routing. A lone observed candidate in an open domain is not sufficient.

If C7 and C8 bind to H1, report two confirmed sites from orders. B2 and legacy-worker leave the system-wide total unknown. If the candidate set contains H1 and H2, retain both; report possible impact on H1 without declaring a unique binding. A missing route table leaves the result unresolved, whereas a complete applicable route table with no destination excludes that site under this rule. An exact total also requires a closed service inventory and adequate request coverage in every relevant service.

A different environment can change the applicable routing facts and the resulting binding; the observed call sites remain the same. Two environments can still resolve to the same handler. Reusing one library across services reuses content-derived facts but preserves each service's occurrence and contextual conclusions. A cross-service operation binding supports operation-level impact. Field-level dataflow additionally needs serialization and decoding correspondence; endpoint equality cannot manufacture it.

This supplies the missing meaning behind some cross-graph joins in P6. Writing protocol/framework extractors and resolvers is still necessary. The model does not claim to discover an entire runtime system from static configuration.

## 6. Additional input formats challenge the same model

### IPA and Mach-O

An IPA's app, extensions and frameworks are container entries. Their executable contents can yield Mach-O representations; architecture slices have separate location domains. A package membership fact answers what ships. A load command yields a library request. A symbol table yields definitions and unresolved references. Each is useful even if no call analysis is available.

For a request such as `@rpath/Pay.framework/Pay`, candidate resolution depends on the requesting image, architecture, search context and packaged or external candidates. Matching an exported symbol does not by itself locate a call site or prove execution. Removing Pay leaves the load request intact and removes that packaged candidate. Adding an unrelated framework changes inventory without creating a call or dependency from the app to it.

A matching dSYM can supplement a representation when architecture and build identity establish correspondence. A same-named file alone does not. A stripped image can still answer covered load-dependency questions while function names or source locations remain unknown. D2–D4 express these distinctions without requiring fabricated methods or complete dataflow. [Apple build UUID guidance](https://developer.apple.com/documentation/technotes/tn3178-checking-for-and-resolving-build-uuid-problems)

### ELF, standalone or packaged

An ELF relocation can identify a reference to `pay` at `.text+0x10`. If `.text` starts at file offset `0x40`, that is file offset `0x50`; file offset `0x10` names a different byte. The location needs its representation and coordinate system. A relocatable object need not provide a final image address or the name of the library that will satisfy the reference.

A loadable image can also contain a zero-initialized memory region without corresponding file bytes. Therefore location correspondence must be partial. The same ELF supplied directly and as an APK member can reuse content-derived dynamic dependencies, while package occurrence and library resolution context remain separate. [ELF relocations](https://gabi.xinuos.com/elf/06-reloc.html) · [ELF program loading](https://gabi.xinuos.com/elf/07-pheader.html)

A useful ELF or Mach-O frontend may initially publish inventory, load dependencies and symbols only. Its coverage declares that scope. It must not fabricate locals, parameters or flows to satisfy a universal node checklist. New format-native facts travel under D1; extraction, disassembly and semantic recovery remain separate capabilities.

## 7. What this proposal resolves, and what it costs

| Problem | Decision and intended result | Remaining work or limit |
| --- | --- | --- |
| P1: repeated definitions | D1/D5 make structural facts and projections a shared contract, so a routine new fact can travel and be inspected without coordinated kind-specific changes everywhere. | Build generic boundary support once; domain operators and specialized presentation still need code. |
| P2: facts outside node inventory | D1/D2 make declaration and metadata inventories independent of uses and traversals. | Existing adapters must enumerate them faithfully; old missing information needs new input evidence. |
| P3: identity and representation narrowing | D2/D3 retain native identity, references, contexts and locations without assigning them invented JVM meanings. | Producers must emit available context; equality across independent outputs requires evidence. |
| P4: overloaded meanings | D2/D4/D5 distinguish observations, interpretations and coverage while preserving legacy projections. | A common view can expose less than a native profile. Additional extraction is not automatic. |
| P5: unsupported, missing, damaged and incomplete confused | D1/D4 separate semantic capability, provenance, integrity dependencies and query completion. | Readers, writers and service APIs must enforce those distinctions; safe publication is independent engineering. |
| P6: system attribution | D3/D4 preserve artifact occurrences, service revisions, environment and binding premises in cross-graph analysis. | Service inventories, protocol knowledge and configuration have to be supplied or extracted. |
| P7: producer-specific extensions | D1/D5 let profiles introduce typed facts and declare common projections. | Defining a fact does not implement its extractor, resolver or UI. |

The costs are real. Generic structural access needs an implementation alongside existing optimized paths. Definitions and projections need semantic ownership and version discipline. More precise identities and evidence increase the information to manage, even though they can be shared by scope. Coverage claims require producers to account for gaps. A view can only be as precise as its evidence, so some cross-format questions will correctly remain unresolved.

Three tempting shortcuts do not address the causes. Renaming current JVM types preserves the descriptor restrictions in P3. Adding open property bags leaves fact identity, independent inventories and coverage undefined. Replacing the physical format alone leaves every producer and query implementation free to assign different meanings to the same record. Conversely, turning every input into a complete compiler IR would exceed Graphite's role and make useful partial binary support unnecessarily difficult.

Review should first decide whether P1–P7 explain the actual extension and correctness problems, and whether D1–D5 remove those causes without weakening the existing product. In particular, reject this model if a declaration with no uses still has no preservation path, if an unknown fact can silently disappear, if an old query acquires new rows or meanings, or if a service binding can be asserted without the premises that distinguish its target.

This draft does not claim a new implementation, benchmark improvement or full input reconstruction. The subsequent physical and implementation design must preserve existing correctness, availability and resource constraints, using the repository's representative multi-graph acceptance requirements. That work cannot substitute for resolving the logical questions here. [Scope and non-goals](universal-schema-scope.md) · [Engineering constraints](../CONVENTIONS.md)
