# Graphite universal schema: Draft logical model

English | [简体中文](universal-schema-logical-model.zh.md)

Design review draft. The [product scope agreed in #231](universal-schema-scope.md) governs this proposal. The compatibility baseline is the [README at 70f908cf](https://github.com/johnsonlee/graphite/blob/70f908cf6acf4b80ce74d038cecba07f119a544b/README.md). The names below describe logical roles. Cypher labels, file formats, indexes and network APIs are separate design decisions.

## 1. What should users be able to trace?

Starting with an IPA, a user can find an extension, locate its executable image and ask which libraries that image declares it will load. Working backward from a refund API takes the user through service versions, routing configuration and client operations to individual call sites. Both paths require **identifiable objects, relationships with precise meanings, evidence for connections across boundaries, and a way to explain how much of the question the answer covers**.

Each graph should publish facts that can be interpreted independently. An analysis over several selected graphs can then establish conditional relationships while preserving each graph's identities and conclusions. The same rule applies when a user adds a dSYM or changes the service routing configuration. The explicit references and namespaced definitions from #169 could express this model. Its record layout and codec remain separate choices. [Earlier prototype #169](https://github.com/johnsonlee/graphite/pull/169)

## 2. Objects, scope and identity

| Logical object | Identity and relationship constraints | Question it helps answer |
| --- | --- | --- |
| Snapshot | Facts published by producer runs for a fixed set of inputs and options. An object reference is qualified by at least its snapshot and local identifier. A registry name can refer to different snapshots over time. | Do yesterday's and today's graphs named orders represent the same build? |
| Artifact content and member (Artifact, Member) | Content can be a file or a collection with an inventory. Each member occurrence has its own identity, one parent container, a member name and the content it refers to. Occurrence positions distinguish entries with the same name. Matching digests obtained with the same algorithm can establish content equality. When content identity is unavailable, retain only a local reference. | Which two applications package the same library? Can we reuse facts about the same ELF supplied directly and extracted from an APK? |
| Representation | An interpretation of content within a particular input scope and context. It can depend on several inputs, such as a compiler index, source and build target. One file can yield multiple representations, such as two Mach-O slices. Facts obtained by parsing content can be reused by content identity. Conclusions that depend on search paths or compilation targets also carry that context. | Why can declarations from the same Swift source file compiled into two modules have distinct identities? |
| Location | A representation, a coordinate system, an owner where needed, and a point or half-open interval. The coordinate system defines the unit and origin. Examples include bytecode offsets within a method, byte offsets within a section, file offsets within a slice, and link-time virtual addresses within an image. A mapping between coordinate systems is itself a fact. A location can have several counterparts or none, and a mapping can cover only part of its domain. | Which image owns this address? Can we locate it in a file or in source? |
| Program object | Declarations, linker symbols, code ranges, call sites, values, type uses, resources and operations can each exist independently. Each domain defines its declaration keys within the owning module and compilation context. An occurrence and its declaration can be referenced separately. A named symbol can lack a body, code can lack a name, and a declaration can have several code instances. | What should a query return for an unused enum declaration, an unresolved symbol reference or an inlined instance? |
| System view (SystemView) | An explicit service inventory, selected snapshots, service versions where known, and static environment inputs. Evidence-backed attribution connects services to artifact members or representations. One service can correspond to multiple graphs, and the same content can appear in multiple services. Unknown versions stay unknown. | To which set of services and which environment does the conclusion apply? |

**A direct identity join is valid only within the identity's scope.** Node 7 in one graph and node 7 in another are distinct references, as are address 0x1000 in two different images. Names, deobfuscated display text and URL paths provide clues for matching. A cross-snapshot association must state its kind. “Contains identical bytes” supports content reuse, “debug information describes this image” supports source association, and “can bind to this API under this configuration” supports impact analysis. None of these associations automatically merges nodes. Existing public identifiers keep their original query projection for legacy graphs.

## 3. What a fact can express

In the following notation, parentheses contain named roles and brackets denote ordered sequences. The notation is for reviewing the model and examples.

```text
Definition = (namespace, name, semantic version, type and cardinality of each role, equivalence and coverage conditions)
Fact       = (fact identifier, definition, scope, role values, basis)
Basis      = direct observation(extraction run, locator within input)
           | derivation(rule and version, references to premise facts, interpretation context)
```

- **Roles and values.** A role can be required, optional or repeated. Its definition specifies whether repetition and order are significant. Values include typed scalars, explicit nulls, object or fact references, and structured collections. The definition determines equality and precision. A missing field, an explicit null, an empty sequence and a fact that was never extracted each have distinct meanings. Two arguments of a call can refer to the same value. Facts with the same endpoints can differ by role, ordinal or occurrence.
- **Evidence and counting.** An input locator can identify a table entry, member path, source range or legacy graph record. A legacy graph is evidence that it recorded a fact. An unknown original source location remains unknown. A rule's conclusions must be traceable to input facts. Cycles in program relationships still need support from observations. Keep both pieces of evidence when two sources support one call site. Count call sites by distinct site identity and count evidence by its basis. Preserve the sources and contexts of conflicting observations and mark a query that depends on a unique conclusion as unresolved.
- **References and resolution.** A call site can carry an observed name or symbol reference before any target is known. Record each candidate target with the rule and conditions that support it. Publish a definite binding under stated conditions only when the evidence suffices. Finding a candidate leaves open whether all candidates have been enumerated.
- **Evolution.** A new format can publish definitions and declare evidence-backed projections onto common definitions. Within one definition version, role meanings, identity and result precision must remain stable. Changing “explicit argument” to “effective argument” requires a separate definition. The old projection keeps its semantics. A generic consumer can interpret structure and references. Specific type inference requires the corresponding capability. A reader must explicitly reject the operation or the graph when a fact it cannot interpret could affect the answer.

The following common relationships make claims that **can be tested independently**. Each domain retains its native predicates, such as ELF dynamic table entries or Mach-O load commands.

| Relationship (illustrative) | Meaning |
| --- | --- |
| `contains(parent, member, content)` | The content occurs in this parent container. Member identity preserves ownership. |
| `declaresDependency(image, requestedLibrary)` | The image's load metadata requests the library and can retain its original name and qualifiers. A binding relationship expresses whether the request resolves to a particular member. |
| `refersTo(site, reference)`, `candidate(reference, target, context)` | Distinguish a reference observed in the input from a possible target identified during resolution. |
| `argument(call, ordinal, value, role)`, `flowsTo(value, use, context)` | Argument order and origin have evidence. Dataflow preserves the analysis boundaries and does not directly connect a client value to a parameter in another service. |
| `provides(serviceRevision, operation, handler)`, `requests(site, operationRef)` | API declarations and client operations can be queried independently. Cross-service binding requires matching evidence such as the protocol, target and route. |
| `corresponds(left, right, kind, context)` | The kind of association determines which conclusion it supports. Source, indexes, images and debug records can correspond many to many. |

## 4. Empty results, partial results and query boundaries

A coverage claim applies to **(snapshot or system inventory, fact definition and version, selected scope, extraction conditions)**. A claim can cover a batch with exceptions for narrower scopes. Complete means enumeration finished for the particular question. Partial means the checked scope is known and gaps remain, even if the checked portion yielded zero matches. Unknown means no sufficient claim is available. The presence of a node kind cannot establish coverage across a graph. Record which run collected the facts, why extraction skipped a scope, or where data was damaged, as applicable. When data declared necessary is corrupt, affected queries return an integrity error.

For each query, determine the object domain and required facts, then combine coverage across the relevant scopes. A positive match can be reported with its evidence. Counts in the presence of gaps describe only the confirmed set. Negation, exact counts and unique bindings require coverage of every scope and resolution candidate that could change the answer. Check complete consumption of the query results separately. Cancellation or exhaustion of a work budget cannot establish zero matches. Existing Cypher contracts for rows, nulls and errors continue to apply. These conditions govern when a conclusion holds. How an API conveys them is a separate decision. [Existing method-body completeness semantics](analysis-completeness.md)

## 5. Constraints derived from baseline inputs

| Input or fact | Question and expected answer | Modeling error exposed |
| --- | --- | --- |
| JAR, fat JAR, WAR, class directory | Distinguish methods with the same name by their owning declaration and full JVM descriptor. Hive has two `radixSort` overloads taking one- and two-dimensional byte arrays respectively. Both declarations must survive. For nested JARs, retain the chain of package membership and the classpath selection. | Deduplicating by display signature loses overloads. Identical library content can belong to different packages. [Verified classfile inventory](declared-types-corpus-identity-audit.md) |
| APK, AAR | Attribute DEX declarations, resources and native libraries for each ABI to their respective APK members. Retain separate origins for an AAR’s classes.jar and resources. For one declaration, a recorded method signature, an analyzed body and available control flow represent distinct coverage claims. | Finding a native library cannot establish a JNI binding or call edge. Finding a declaration cannot establish that its body was analyzed. [Existing inputs and options](../README.md#inputs-by-language) |
| Emitted JavaScript files and directories | For `f(x=1)`, both `f()` and `f(1)` can yield a recorded effective value of 1. If the existing record preserves only the effective arguments, the explicit argument count is unknown. The two arguments in `g(x,x)` need distinct positions. Record single-file and directory scan scopes separately. | Reconstructing source arguments from effective values or deduplicating arguments by value. [Existing JavaScript behavior](typescript-frontend.md) |
| Swift package, Xcode project/workspace, index store with source, including Objective-C | Retain the indexing context of each compilation target when several targets include the same source. Declarations can exist independently of uses. In the Signal round trip, all 10,027 enum metadata entries must survive even without corresponding constant nodes. A declared empty parameter list remains `[]`. | Inferring the declaration inventory from use sites turns “known empty” into “never recorded.” [Signal round-trip check](apple-frontend-validation.md) |
| Graph IR import, graph directory, .graphite archive | Include separate method and enum batches from IR in the fact inventory. An old graph that projected class literals to integer zero retains that historical projection. New graphs supporting tag 16 can supply a `ClassConstant` descriptor. The IR v1 Node oneof has no corresponding variant. | Reading a header or matching all three trailer counts cannot establish that every fact family survived. An older reader encountering a new tag that affects an answer must answer correctly or explicitly refuse. [IR definition](../ir/graphite_ir.proto) · [Tag 16 behavior](webgraph-storage.md#class-literals-node-tag-16) |

Check compatibility for the public projections of the Kotlin library, CLI, both existing engines, REST, MCP and Explorer against the same inputs, build options and legacy graphs. Compare original fact kinds, identifiers, properties and values, order and multiplicity, errors and coverage gaps. Intentional changes to public results still require individual declaration and review under #231. The table gives counterexamples for testing the model. Full product compatibility is an implementation acceptance criterion.

## 6. Three boundaries illustrated by native samples

### IPA and Mach-O: membership, load requests and candidate bindings

We built an arm64 iOS executable named Demo and a dynamic library named Pay in a temporary directory. Demo’s C entry point references the external function `pay`, which the library defines. We put both binaries in a ZIP with an IPA member layout. The following observations come from independent tools. We checked package layout and static metadata only. We did not install or execute the sample. [Apple’s IPA package layout](https://developer.apple.com/library/archive/qa/qa1798/_index.html)

| Tool observation | Fact in the model |
| --- | --- |
| `unzip -Z1 demo.ipa` lists `Payload/Demo.app/Demo` and `Payload/Demo.app/Frameworks/Pay.framework/Pay`. | Two member occurrences, each with its own content and image representation. |
| `otool -l` on Demo shows `LC_LOAD_DYLIB @rpath/Pay.framework/Pay` and `LC_RPATH @executable_path/Frameworks`. | The main image declares a dependency and supplies its own search condition. Its separate load request for a system library can remain an external reference. |
| `otool -D` on Pay gives the same install name. Its `nm -gU` output includes a definition of `_pay`. Demo’s `nm -u` output includes an undefined `_pay`. | The search condition, member location, architecture and library identity support a static candidate. The symbol reference and export each stand on their own. The two symbol tables alone cannot locate a call site or establish execution. |

**Counterfactual checks.** If Pay is removed from the package, Demo still declares its load request and the packaged candidate disappears. Adding a framework that matches none of Demo’s load requests can add facts about the framework and its package membership. Demo’s candidates stay the same. In a multi-architecture Mach-O, arm64 and x86_64 each have their own address domain and build UUID. Matching a dSYM by architecture and UUID adds source associations only to the corresponding slice. A matching filename with a different UUID cannot establish a binding. The last two cases follow from the specification. [Apple’s build UUID and dSYM rules](https://developer.apple.com/documentation/technotes/tn3178-checking-for-and-resolving-build-uuid-problems)

### ELF: one number can denote two kinds of location

We compiled the same Demo source to an AArch64 ELF object. `readelf -h` reported ELF64 and type REL. `readelf -Ws` showed a defined main and an undefined pay. `readelf -rW` showed an R_AARCH64_CALL26 relocation referencing pay at offset 0x10 in .text. The answer is “this object has a relocation reference to pay at .text+0x10.” The logical location identifies the object representation, section and section-relative offset. This sample supplies neither a dependency library name nor a final load address. [ELF relocation specification](https://gabi.xinuos.com/elf/06-reloc.html)

Consider two further cases for a loadable ELF. If a PT_LOAD segment has file size 0x100 and memory size 0x180, segment-relative offset 0x120 falls in its zero-initialized region. It has an image location with no corresponding file byte. If the same shared library is supplied as a standalone file and as an APK member, its content should yield the same dynamic dependency requests. Which library a request resolves to still depends on the selected search context. Local function names can be unknown in a stripped input while fully read dynamic dependencies remain queryable. [ELF loading specification](https://gabi.xinuos.com/elf/07-pheader.html)

## 7. Cross-service query: who could a refund API change affect?

Suppose the staging inventory lists orders, billing, payments, sandbox-pay and legacy-worker. Both orders and billing include the same SDK. Call site C7 in orders sends a POST /refunds request to the alias pay. For billing, the only evidence is SDK membership. Both payments and sandbox-pay provide an operation with the same name. The staging configuration maps pay to payments, and the test configuration maps pay to sandbox-pay. The inventory includes legacy-worker, whose call facts were never collected. Each service name here denotes one version selected by the view.

Starting at the payments handler, the query joins back through the provided operation, the route for the selected environment and the client request. It returns the source of each call site. Run `python3 -m doctest docs/universal-schema-logical-model.md` from the repository root to check the relational example below. Tuples abbreviate named roles. The three groups of facts come from call-site extraction, configuration parsing and provider declarations. Two services with the same path and a second environment ensure that matching on path alone cannot pass the example.

```pycon
>>> requests = [('orders', 'C7', 'pay', 'POST', '/refunds')]
>>> routes = [('staging', 'pay', 'payments'), ('test', 'pay', 'sandbox-pay')]
>>> providers = [('payments', 'POST', '/refunds', 'H1'),
...              ('sandbox-pay', 'POST', '/refunds', 'H2')]
>>> def callers(environment, target, bindings):
...     return sorted({(caller, site, handler)
...                    for caller, site, alias, verb, path in requests
...                    for env, name, service in bindings
...                    if (env, name, service) == (environment, alias, target)
...                    for owner, method, route, handler in providers
...                    if (owner, method, route) == (service, verb, path)})
>>> callers('staging', 'payments', routes)
[('orders', 'C7', 'H1')]
>>> callers('test', 'payments', routes)
[]
>>> callers('staging', 'payments', [])
[]
>>> callers('test', 'sandbox-pay', routes)
[('orders', 'C7', 'H2')]
>>> ambiguous = routes + [('staging', 'pay', 'sandbox-pay')]
>>> [service for service in ('payments', 'sandbox-pay')
...  if callers('staging', service, ambiguous)]
['payments', 'sandbox-pay']
>>> inventory = {'orders', 'billing', 'payments', 'sandbox-pay', 'legacy-worker'}
>>> covered_requests = inventory - {'legacy-worker'}
>>> matches = callers('staging', 'payments', routes)
>>> (len({caller for caller, _, _ in matches}), sorted(inventory - covered_requests))
(1, ['legacy-worker'])
>>> def caller_count(environment, target, bindings, *, covered, routes_complete):
...     rows = callers(environment, target, bindings)
...     observed = len({caller for caller, site, handler in rows})
...     gaps = sorted(inventory - covered)
...     if not routes_complete:
...         gaps.append('route coverage')
...     return observed, (observed if not gaps else None), gaps
>>> caller_count('test', 'payments', routes, covered=inventory, routes_complete=True)
(0, 0, [])
>>> caller_count('staging', 'payments', [], covered=inventory, routes_complete=False)
(0, None, ['route coverage'])
>>> caller_count('staging', 'payments', routes, covered=covered_requests, routes_complete=True)
(1, None, ['legacy-worker'])

```

**Interpreting the results.** The first result identifies one call site that can be linked statically through C7, the staging route and the declaration of handler H1. Billing’s SDK membership does not enter this join. The second result finds no binding to payments under the test configuration. The third also returns an empty set, but in this case the configuration is missing and C7’s target remains unresolved. The fourth gives a candidate for the same call site under another configuration. Because coverage excludes legacy-worker, the first result establishes one confirmed caller and leaves the system-wide total unknown. If staging supplies two routes that could both apply, retain both candidates and their conditions. One route alone cannot establish a unique target.

The last three assertions compare counts assuming a complete provider inventory, deterministic routing and completed execution. Each returns the observed number of services linked to the target, the exact count (`None` when unknown), and the gaps. The first assumes that request facts were subsequently collected for all services with no additional calls found. The last retains the original inventory coverage. Coverage is an input to this example.

The existing topology runs rules over the configured graphIds, aggregates weights by (source, target, protocol) and deduplicates operation and evidence. The call-site results above can be projected onto service pairs while retaining the detailed evidence for inspection. Existing rules keep their original weight calculations. Each graph registered through the legacy workflow still maps to one service. C4, topology and endpoints grouped by graphId retain their existing contracts. Establishing field-level dataflow across services also requires evidence about serialized fields, protocol versions and the peer’s decoding map. An endpoint match alone supports only operation-level impact. [Topology query contract](topology-graph.md) · [Existing cross-graph rule example](https://github.com/johnsonlee/graphite/blob/70f908cf6acf4b80ce74d038cecba07f119a544b/README.md#L948-L965)

## 8. Review conclusions and open questions

The baseline cases require independent declarations, value roles and coverage by fact family. The native samples require a coordinate domain for each location and separate facts for membership, references and bindings. The multi-service query requires attribution, environment, evidence for the applied rules and inventory completeness within its scope. Each constraint can be tested by observing how the answer changes.

This review checked existing repository cases, inspected native samples with independent tools and ran the multi-service relational example above. These checks establish what the logical model can express. Implementation acceptance still needs real multi-bundle IPAs, paired extraction from universal Mach-O binaries and their dSYMs, stripped ELF shared libraries and end-to-end compatibility of production queries. The next review should identify which common predicates already have precise enough identity and coverage conditions, where domains still need separate predicates and what evidence can promote a candidate inferred across services or snapshots to a confirmed binding.
