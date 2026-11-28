# Graphite universal schema: Draft logical model

English | [简体中文](universal-schema-logical-model.zh.md)

Design review draft. The [product scope agreed in #231](universal-schema-scope.md) governs this proposal. The compatibility baseline is the [README at 70f908cf](https://github.com/johnsonlee/graphite/blob/70f908cf6acf4b80ce74d038cecba07f119a544b/README.md). The names below describe logical roles. Cypher labels, file formats, indexes and network APIs are separate design decisions.

## 1. What should users be able to trace?

Starting with an IPA, a user can find an extension, locate its executable image and ask which libraries that image declares it will load. Working backward from a refund API takes the user through service versions, routing configuration and client operations to individual call sites. Both paths require **identifiable objects, relationships with precise meanings, evidence for connections across boundaries, and a way to explain how much of the question the answer covers**.

Each graph should publish facts that can be interpreted independently. An analysis over several selected graphs can then establish conditional relationships while preserving each graph's identities and conclusions. The same rule applies when a user adds a dSYM or changes the service routing configuration. The explicit references and namespaced definitions from #169 could express this model. Its record layout and codec remain separate choices. [Earlier prototype #169](https://github.com/johnsonlee/graphite/pull/169)

## 2. Objects, scope and identity

| Logical object | Identity and relationship constraints | Question it helps answer |
| --- | --- | --- |
| Snapshot | One concrete published output, including its assignment of local identifiers. A reference pairs the identity of that output with a local identifier. A registry name can point to a different snapshot tomorrow. | Does node 7 still refer to the same object after the orders graph is replaced? |
| Artifact content and member (Artifact, Member) | Content can be a file or a collection with an inventory. Each member occurrence has its own identity, one parent container, a member name and the content it refers to. Occurrence positions distinguish entries with the same name. Matching digests obtained with the same algorithm can establish content equality. When content identity is unavailable, retain only a local reference. | Which two applications package the same library? Can we reuse facts about the same ELF supplied directly and extracted from an APK? |
| Representation | An interpretation of content within a particular input scope and context. It can depend on several inputs, such as a compiler index, source and build target. One file can yield multiple representations, such as two Mach-O slices. Facts obtained by parsing content can be reused by content identity. Conclusions that depend on search paths or compilation targets also carry that context. | Why can declarations from the same Swift source file compiled into two modules have distinct identities? |
| Location | A representation, a coordinate system, an owner where needed, and a point or half-open interval. The coordinate system defines the unit and origin. Examples include bytecode offsets within a method, byte offsets within a section, file offsets within a slice, and link-time virtual addresses within an image. A mapping between coordinate systems is itself a fact. A location can have several counterparts or none, and a mapping can cover only part of its domain. | Which image owns this address? Can we locate it in a file or in source? |
| Program object | Declarations, linker symbols, code ranges, call sites, values, type uses, resources and operations can each exist independently. Each domain defines its declaration keys within the owning module and compilation context. An occurrence and its declaration can be referenced separately. A named symbol can lack a body, code can lack a name, and a declaration can have several code instances. | What should a query return for an unused enum declaration, an unresolved symbol reference or an inlined instance? |
| System view (SystemView) | An explicit service inventory, selected snapshots, service versions where known, and static environment inputs. Evidence-backed attribution connects services to artifact members or representations. One service can correspond to multiple graphs, and the same content can appear in multiple services. Unknown versions stay unknown. | To which set of services and which environment does the conclusion apply? |

**Snapshot identity and build provenance answer different questions.** For each producer run, record the input digests, effective options and producer name and version. Equal provenance permits comparison of build conditions. Even with identical inputs and options, a producer upgrade or a repeat run can assign node 7 to a different declaration. Reuse a local reference across copies only when their concrete output identity, including the identifier assignment, is established. Whether two outputs came from the same build execution requires a recorded run identity or equivalent evidence. An imported legacy graph can have its own snapshot identity while its original build or producer remains unknown.

**A direct identity join is valid only within the identity's scope.** Node 7 in one graph and node 7 in another are distinct references, as are address 0x1000 in two different images. Names, deobfuscated display text and URL paths provide clues for matching. A cross-snapshot association must state its kind. “Contains identical bytes” supports content reuse, “debug information describes this image” supports source association, and “can bind to this API under this configuration” supports impact analysis. None of these associations automatically merges nodes. Existing public identifiers keep their original query projection for legacy graphs.

## 3. What a fact can express

In the following notation, parentheses contain named roles and brackets denote ordered sequences. The notation is for reviewing the model and examples.

```text
Definition = (namespace, name, semantic version, type and cardinality of each role, equivalence and coverage conditions)
Fact       = (definition, scope, role values)
Evidence   = set of bases supporting the fact
Basis      = direct observation(extraction run, optional locator within input)
           | derivation(rule and version, references to premise facts, interpretation context)
```

- **Roles and values.** A role can be required, optional or repeated. Its definition specifies whether repetition and order are significant. Values include typed scalars, explicit nulls, object or fact references, and structured collections. The definition determines equality and precision. A missing field, an explicit null, an empty sequence and a fact that was never extracted each have distinct meanings. Two arguments of a call can refer to the same value. Facts with the same endpoints can differ by role, ordinal or occurrence.
- **Fact identity and evidence.** A definition specifies which scoped role values identify the claim, including occurrence or ordinal where significant. Two independent observations supporting the same claim contribute two bases to one fact. A fact reference can use this logical identity. A batch can declare a default set of bases for all its facts. A fact can add to that set or override it with its actual bases. A locator, when known, can identify a table entry, member path, source range or legacy graph record. A legacy record attests to what the graph recorded even when its original source location is unknown. These rules permit shared evidence without requiring a stored identifier, evidence record or locator per fact.
- **Counting and conflicts.** Count call sites by distinct site identity and evidence by distinct basis. Trace rule conclusions to premise facts and ultimately observations, including where program relationships form cycles. Preserve conflicting observations with their sources and contexts. A query requiring a unique conclusion remains unresolved while the conflict affects the result.
- **References and resolution.** Record the observed reference even when no target is known. Each candidate names the rule, context and evidence that make the target possible. Confirm a unique binding only when evidence establishes the complete candidate domain under that rule and context, the required matching and precedence conditions have been evaluated, and exactly one viable target remains. Retain the supporting facts and coverage claim so the result can be checked. A lone observed candidate in an open domain remains a candidate.
- **Evolution.** A new format can publish definitions and declare evidence-backed projections onto common definitions. Within one definition version, role meanings, identity and result precision must remain stable. Changing “explicit argument” to “effective argument” requires a separate definition. The old projection keeps its semantics. A generic consumer can interpret structure and references. Specific type inference requires the corresponding capability. A reader must explicitly reject the operation or the graph when a fact it cannot interpret could affect the answer.

The following common relationships make claims that **can be tested independently**. Each domain retains its native predicates, such as ELF dynamic table entries or Mach-O load commands.

| Relationship (illustrative) | Meaning |
| --- | --- |
| `contains(parent, member, content)` | The content occurs in this parent container. Member identity preserves ownership. |
| `declaresDependency(image, requestedLibrary)` | The image's load metadata requests the library and can retain its original name and qualifiers. A binding relationship expresses whether the request resolves to a particular member. |
| `refersTo(site, reference)`, `candidate(reference, target, context)` | Distinguish a reference observed in the input from a possible target identified during resolution. |
| `confirmedBinding(reference, target, context)` | The resolution rule selects exactly one viable target in its covered candidate domain. The conclusion applies under the recorded conditions. |
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
| Emitted JavaScript files and directories | For `f(x=1)`, both `f()` and `f(1)` can yield a recorded effective value of 1. For resolved calls, the current frontend records effective values in `CallSite.arguments` after defaults have been applied. The original argument count is unknown. The two arguments in `g(x,x)` need distinct positions. Record single-file and directory scan scopes separately. | Reconstructing source arguments from effective values or deduplicating arguments by value. [Existing JavaScript behavior](typescript-frontend.md) |
| Swift package, Xcode project/workspace, index store with source, including Objective-C | Retain the indexing context of each compilation target when several targets include the same source. Declarations can exist independently of uses. In the Signal round trip, all 10,027 enum metadata entries must survive even without corresponding constant nodes. Looking up a declared enum case with no associated values returns `[]`. Looking up an undeclared case returns `null`. | Inferring the declaration inventory from use sites turns “known empty” into “never recorded.” [Signal round-trip check](apple-frontend-validation.md) |
| Graph IR import, graph directory, .graphite archive | Include separate method and enum batches from IR in the fact inventory. An old graph that projected class literals to integer zero retains that historical projection. New graphs supporting tag 16 can supply a `ClassConstant` descriptor. The IR v1 Node oneof has no corresponding variant. | Reading a header or matching all three trailer counts cannot establish that every fact family survived. An older reader encountering a new tag that affects an answer must answer correctly or explicitly refuse. [IR definition](../ir/graphite_ir.proto) · [Tag 16 behavior](webgraph-storage.md#class-literals-node-tag-16) |

Check compatibility for the public projections of the Kotlin library, CLI, both existing engines, REST, MCP and Explorer against the same inputs, build options and legacy graphs. Compare original fact kinds, identifiers, properties and values, order and multiplicity, errors and coverage gaps. Intentional changes to public results still require individual declaration and review under #231. The table gives counterexamples for testing the model. Full product compatibility is an implementation acceptance criterion.

## 6. Three boundaries illustrated by native samples

### IPA and Mach-O: membership, load requests and candidate bindings

We built an arm64 iOS executable named Demo and a dynamic library named Pay in a temporary directory. Demo’s C entry point references the external function `pay`, which the library defines. We put both binaries in a ZIP with an IPA member layout. The following observations come from independent tools. We checked package layout and static metadata only. We did not install or execute the sample. [Apple QA1798, Checking Distribution Entitlements (IPA inspection example)](https://developer.apple.com/library/archive/qa/qa1798/_index.html)

| Tool observation | Fact in the model |
| --- | --- |
| `unzip -Z1 demo.ipa` lists `Payload/Demo.app/Demo` and `Payload/Demo.app/Frameworks/Pay.framework/Pay`. | Two member occurrences, each with its own content and image representation. |
| `otool -l` on Demo shows `LC_LOAD_DYLIB @rpath/Pay.framework/Pay` and `LC_RPATH @executable_path/Frameworks`. | The main image declares a dependency and supplies its own search condition. Its separate load request for a system library can remain an external reference. |
| `otool -D` on Pay gives the same install name. Its `nm -gU` output includes a definition of `_pay`. Demo’s `nm -u` output includes an undefined `_pay`. | The search condition, member location, architecture and library identity support a static candidate. The symbol reference and export each stand on their own. The two symbol tables alone cannot locate a call site or establish execution. |

**Counterfactual checks.** If Pay is removed from the package, Demo still declares its load request and the packaged candidate disappears. Adding a framework that matches none of Demo’s load requests can add facts about the framework and its package membership. Demo’s candidates stay the same. In a multi-architecture Mach-O, arm64 and x86_64 each have their own address domain and build UUID. Matching a dSYM by architecture and UUID adds source associations only to the corresponding slice. A matching filename with a different UUID cannot establish a binding. The last two cases follow from the specification. [Apple’s build UUID and dSYM rules](https://developer.apple.com/documentation/technotes/tn3178-checking-for-and-resolving-build-uuid-problems)

### ELF: one number can denote two kinds of location

We compiled the same Demo source to an AArch64 ELF object. `readelf -h` reported ELF64 and type REL. `readelf -Ws` showed a defined main and an undefined pay. `readelf -rW` showed an R_AARCH64_CALL26 relocation referencing pay at offset 0x10 in .text. The answer is “this object has a relocation reference to pay at .text+0x10.” Here `readelf -SW` places .text at file offset 0x40. Section offset 0x10 therefore identifies file byte 0x50, while file offset 0x10 identifies a byte in the ELF header. The number 0x10 needs a coordinate domain as well as an object identity. This sample supplies neither a dependency library name nor a final load address. [ELF relocation specification](https://gabi.xinuos.com/elf/06-reloc.html)

Consider two further cases for a loadable ELF. If a PT_LOAD segment has file size 0x100 and memory size 0x180, segment-relative offset 0x120 falls in its zero-initialized region. It has an image location with no corresponding file byte. If the same shared library is supplied as a standalone file and as an APK member, its content should yield the same dynamic dependency requests. Which library a request resolves to still depends on the selected search context. Local function names can be unknown in a stripped input while fully read dynamic dependencies remain queryable. [ELF loading specification](https://gabi.xinuos.com/elf/07-pheader.html)

## 7. Cross-service query: who could a refund API change affect?

The system view selects one revision of each service. Both orders and billing package the same SDK. Two call sites in orders request `POST /refunds` through the alias `pay`. At B2 in billing the operation is known, but the destination expression could not be resolved. The inventory includes legacy-worker, whose requests were never collected. The question is which services have a statically bound call to the payments handler H1 under staging.

The example fixes the protocol, verb and path to HTTP `POST /refunds`. Every listed request has independent call-site evidence. SDK membership supplies none. A route entry enumerates **all** applicable destinations for that environment and alias. A provider entry enumerates **all** handlers for that service revision and operation. An empty set certifies no match in that scope. An absent entry leaves the scope unknown. A partial extraction with known candidates must retain those candidates as evidence and leave enumeration open. The small fixture below uses complete entries or missing entries.

The resolver has one deterministic rule: follow the selected alias and match the operation at its destinations. Its declared scope excludes fallback routes, rewrites and runtime overrides. A definite binding requires a known request, complete applicable routing and provider evidence, and exactly one admissible handler after applying all rules. The basis for that binding identifies the site, matching route and provider entries, their coverage claims and the rule applied. Ambiguous candidates remain visible. A missing fact that could change the answer leaves the site unresolved. Production resolvers must establish these conditions from their own rules and coverage. Run `python3 -m doctest docs/universal-schema-logical-model.md` to check the example.

```pycon
>>> inventory = {'orders', 'billing', 'payments', 'sandbox-pay', 'legacy-worker'}
>>> requests = [('orders', 'C7', 'pay'), ('orders', 'C8', 'pay'), ('billing', 'B2', None)]
>>> routes = {('staging', 'pay'): {'payments'}, ('test', 'pay'): {'sandbox-pay'}}
>>> providers = {'payments': {'H1'}, 'sandbox-pay': {'H2'}}
>>> target = ('payments', 'H1')
>>> def resolve(environment, alias, routes, providers):
...     key = (environment, alias)
...     if alias is None or key not in routes:
...         return set(), None
...     destinations = routes[key]
...     candidates = {(service, handler) for service in destinations
...                   for handler in providers.get(service, set())}
...     return candidates, destinations - providers.keys()
>>> def callers(environment, target, requests, routes, providers):
...     confirmed, unresolved = set(), set()
...     for service, site, alias in requests:
...         candidates, gaps = resolve(environment, alias, routes, providers)
...         if gaps == set() and candidates == {target}:
...             confirmed.add((service, site))
...         elif gaps is None or target[0] in gaps or target in candidates:
...             unresolved.add((service, site))
...     return confirmed, unresolved
>>> known_sites = requests[:2]
>>> callers('test', target, known_sites, routes, providers)
(set(), set())
>>> callers('staging', target, known_sites[:1], {}, providers)
(set(), {('orders', 'C7')})
>>> callers('staging', target, known_sites[:1], {('staging', 'pay'): set()}, providers)
(set(), set())
>>> callers('staging', target, known_sites[:1], routes, {})
(set(), {('orders', 'C7')})
>>> ambiguous = {('staging', 'pay'): {'payments', 'sandbox-pay'}}
>>> callers('staging', target, known_sites[:1], ambiguous, {'payments': {'H1'}})
(set(), {('orders', 'C7')})
>>> sorted(resolve('staging', 'pay', ambiguous, providers)[0])
[('payments', 'H1'), ('sandbox-pay', 'H2')]
>>> callers('staging', target, known_sites[:1], ambiguous, providers)
(set(), {('orders', 'C7')})
>>> confirmed, unresolved = callers('staging', target, requests, routes, providers)
>>> sorted(confirmed), sorted(unresolved)
([('orders', 'C7'), ('orders', 'C8')], [('billing', 'B2')])
>>> def distinct_callers(confirmed, unresolved, missing_requests, inventory_complete):
...     known = {service for service, site in confirmed}
...     possible = missing_requests | {service for service, site in unresolved}
...     exact = inventory_complete and possible <= known
...     return len(known), len(known) if exact else None
>>> covered_requests = {'orders', 'billing', 'payments', 'sandbox-pay'}
>>> missing_requests = inventory - covered_requests
>>> distinct_callers(confirmed, unresolved, missing_requests, True)
(1, None)
>>> distinct_callers(confirmed, unresolved, set(), True)
(1, None)
>>> resolved_requests = known_sites + [('billing', 'B2', 'sandbox')]
>>> complete_routes = {**routes, ('staging', 'sandbox'): {'sandbox-pay'}}
>>> found, pending = callers('staging', target, resolved_requests, complete_routes, providers)
>>> distinct_callers(found, pending, set(), False)
(1, None)
>>> distinct_callers(found, pending, set(), True)
(1, 1)
>>> no_sites, open_sites = callers('test', target, known_sites, routes, providers)
>>> distinct_callers(no_sites, open_sites, set(), True)
(0, 0)
>>> distinct_callers(no_sites, open_sites, {'legacy-worker'}, True)
(0, None)

```

The two confirmed sites count as one caller, orders. Billing remains unresolved even when its request collection is complete. Collecting all legacy-worker requests would close that service’s gap, yet B2 could still add a caller. The checks using the resolved request list assume the completed request inventory added no sites and new evidence resolves B2 to sandbox-pay. An exact count of one then depends on the service inventory itself being complete. The final zero-count checks select orders alone in test, then extend that inventory with the unexamined legacy-worker. The count function returns the confirmed lower bound followed by the exact count when established.

The existing topology runs rules over the configured graphIds, aggregates weights by (source, target, protocol) and deduplicates operation and evidence. The call-site results above can be projected onto service pairs while retaining the detailed evidence for inspection. Existing rules keep their original weight calculations. Each graph registered through the legacy workflow still maps to one service. C4, topology and endpoints grouped by graphId retain their existing contracts. Establishing field-level dataflow across services also requires evidence about serialized fields, protocol versions and the peer’s decoding map. An endpoint match alone supports only operation-level impact. [Topology query contract](topology-graph.md) · [Existing cross-graph rule example](https://github.com/johnsonlee/graphite/blob/70f908cf6acf4b80ce74d038cecba07f119a544b/README.md#L948-L965)

## 8. Review conclusions and open questions

The baseline cases require independent declarations, value roles and coverage by fact family. The native samples require a coordinate domain for each location and separate facts for membership, references and bindings. The multi-service query requires attribution, environment, evidence for the applied rules and inventory completeness within its scope. Each constraint can be tested by observing how the answer changes.

This review checked existing repository cases, inspected native samples with independent tools and ran the multi-service relational example above. These checks establish what the logical model can express. Implementation acceptance still needs real multi-bundle IPAs, paired extraction from universal Mach-O binaries and their dSYMs, stripped ELF shared libraries and end-to-end compatibility of production queries. Reviewers should test whether the identity and coverage conditions for each common predicate are precise enough, and whether the evidence available in each domain can satisfy the confirmed-binding rule in §3. The refund case in §7 exercises that rule for one routing model. Other domains may still need their own predicates and resolution rules.
