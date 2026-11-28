# Dependency Bodies and Completeness Evidence

A proof over a graph needs two things the graph did not use to say: whether a callee's body is
in it, and, when it is not, why. `LoaderConfig.dependencyBodies` decides which library bodies a
build analyses beyond the application's own; `Graph.analysisCompleteness()` says what the build
did with every class it loaded and every method it did not analyse completely. Both exist for
one consumer shape (GraphFold, issue #192): a change between two builds must be shown to remain
unobservable after folding, and a path that crosses into a helper jar or `kotlin-stdlib` cannot
be judged by a graph that stops at the call.

## The scope

`includePackages` says what the application is. It used to say, at the same time, which bodies
are built: a build that named its application packages built no body of any library, and a
Spring Boot or WAR build did not even load a nested jar that held no application package.
`DependencyBodyScope` separates the two.

| Scope | Library jar loaded | Library bodies analysed |
|---|---|---|
| `None` (default) | as before: when it holds an application package | none; a library class resolves types and overrides only |
| `Declared(artifacts, packages)` | as before, or when its file name matches an artifact pattern, or it holds a class of one of the packages | every class of a matching artifact, every class under one of the packages |
| `Reachable(maxClasses, roots)` | every library jar | the classes the application's calls reach (from the root classes, through the application methods they reach, when roots are named), and the classes those bodies' calls reach, up to the budget |

```kotlin
val graph = JavaProjectLoader(
    LoaderConfig(
        includePackages = listOf("com.acme.app"),
        includeLibraries = true,
        dependencyBodies = DependencyBodyScope.Declared(
            artifacts = listOf("acme-helpers-*.jar"),
            packages = listOf("kotlin.text.")
        ),
        interproceduralDataflow = true
    )
).load(Path.of("app.jar"))
```

```bash
graphite build app.jar -o /data/app --include com.acme.app --include-libs \
  --dependency-artifacts 'acme-helpers-*.jar' --dependency-packages kotlin.text. --interprocedural
```

```bash
graphite build app.jar -o /data/app --include com.acme.app --include-libs --dependency-reachable 20000 \
  --dependency-roots com.acme.app.web,com.acme.app.jobs
```

A library class the scope covers is processed as an application class is: its methods, their
bodies, their annotations and its fields. It does not become application code. It registers no
endpoint and runs no `GraphiteExtension`, its artifact keeps the `LIBRARY` role, and the
evidence tells it apart from a class the scope left out. The artifact patterns are the glob
dialect of `--lib-filter` (`*` matches any text) and match the jar's file name, whatever
directory a directory input keeps it in (`lib/acme-helpers-1.0.jar` matches `acme-helpers-*.jar`).
A declared scope needs `includeLibraries`; `LoaderConfig` refuses the combination otherwise.

`Reachable` is the selective expansion: after the application's bodies are read, the build
reads the class of every call's declared callee, every override the view knows for a virtual
callee (class hierarchy analysis), the class every `new` names, including the function-object
classes the application allocates, and the class that declares the field every static field
access resolves to (the JVM's field resolution: the named class, its superinterfaces, then its
superclass; `getstatic Child.value` on a field `Base` declares initialises `Base`, not `Child`),
since `getstatic` runs that class's initialiser without any call; and, before each class it
reads, the classes the JVM initialises first (JVMS 5.5): its superclass and every superinterface,
direct or indirect, that declares a default method, so an interface initialiser that runs
without a call in the class's bytecode is read too (one without a default method is not
initialised and not read); the walk goes through an application class in that hierarchy to
the library class it extends, since the application class is already read but what the JVM
initialises before it may not be; then the classes those bodies reach, and so on. A root's own
prerequisites are reached as well. Each class is
read once, so the expansion terminates on any call graph, cycles included. Reading stops at
`maxClasses` (50,000 by default); a class reached past the budget is `NOT_EXPANDED`, which a
proof must treat as unread, never as a class with no effect, and a library class nothing reaches
stays `EXCLUDED_BY_SCOPE`. Expansion follows calls, so a body reached only through a function
value resolved in the dispatch fixpoint is read when the class that holds it was reached by
other means (its allocation is a call to its constructor), and a library body reached only
through a function value the dispatch fixpoint resolved is read after the fixpoint, so its own
calls and fields are in the graph while a function value it passes on is not resolved further
and reads as a gap; an entry a framework discovers by reflection or configuration is not
reached, as the issue's contract expects. Only library classes are reached: an application
class `excludePackages` names stays excluded. `roots` names the
application classes (by prefix) whose calls seed the expansion; the other application classes
are still read, being the application, and a root's calls are followed through them: an
application method a followed call reaches (directly, through other application methods,
through an override the view knows, through a function value the dispatch fixpoint resolved,
or as the initialiser of a class a followed access initialises) has its own calls followed,
on the folded body, so a call the fold removed leads nowhere; what no root reaches is not
followed into the libraries. A call on a function value resolved after the classes were read is
attributed to its own caller. The roots are part of the fold provenance (`dependency_roots`).

With `interproceduralDataflow`, an argument of an application call reaches the parameter node
of the helper's body and the helper's return reaches the call's result, so a query can follow a
bundle name or a key from the application into `ResourceBundle.getBundle` inside the helper.
Those edges are context-insensitive: a helper called from two places passes either caller's
arguments to either caller's result (`docs/constant-folding.md`, Limits). A consumer that needs
exact pairings must keep the call-site context itself; the graph does not claim it.

The scope is part of a fold selection's provenance (`dependency_artifacts` and
`dependency_packages` in the plan's `analysis` identity), since a build with more bodies holds
more calls and a key selected on one scope may name another call on another. The keys are left
out of the document when the scope is empty, so a plan written before the scope existed reads
the same.

## The evidence

```kotlin
val evidence = graph.analysisCompleteness()        // null for a graph built before this existed
graph.analysisStatus(callSite.callee)               // AnalysisStatus, or null without evidence
```

`AnalysisCompleteness` holds:

- `scope`: the `AnalysisIdentity` of the build (package filters, libraries, library filters, the
  dependency scope: its artifact patterns and packages, or its reachable class budget). The evidence is relative to it and claims nothing outside it. The Android
  platform jar an APK build reads is named by its SDK root here; its content digest is the fold
  provenance's, which the CLI computes.
- `artifacts`: every source the build read classes from, in class-path order, with its
  `ArtifactRole` (`APPLICATION`, `LIBRARY`, `PLATFORM`) and whether it carries
  `META-INF/versions/` class entries. This frontend does not select versioned copies: every
  class is read at its base version, as SootUp reads it, so a consumer whose runtime is a newer
  JDK knows the bodies may differ from the ones that run.
- `classes`: every class of the view, with the artifact it was read from (an APK build's
  platform classes name the platform jar, `android-21/android.jar`, not the APK) and `ANALYZED`,
  `EXCLUDED_BY_SCOPE` (loaded for its types only) or `RESOLUTION_FAILED` (its methods could not be
  resolved at all).
- `methods`: every method whose status is not `ANALYZED`, keyed by `MethodDescriptor.identity`
  (class, name and complete JVM descriptor, so a bridge and its covariant override are two
  entries). A method of an analysed class that the graph holds and this map does not is
  analysed; that keeps the evidence proportional to the exceptions, not to the corpus.
- `duplicateClasses`: a class name defined by more than one archive of the input, with every
  archive, loaded or not, in the order the runtime loads them where the archive declares it
  (a Spring Boot jar's `BOOT-INF/classpath.idx`) and in the input's listing order otherwise;
  the first is the definition that runs. A Spring Boot jar's nested libraries are also read by
  the view in that order, so the class's own `origin`, the definition the graph holds, is the
  one the launcher loads; for other layouts it is the first the input lists.
- `callSiteGaps`: the node id of every call on a function value (`fn.apply(x)`,
  `supplier.get()`, Kotlin's `fn(x)`) that the dispatch fixpoint resolved to no body, with why:
  `DISPATCH_UNRESOLVED` (no function value is known to reach the receiver: it comes from
  outside the analysed scope, or along a flow the adapter does not track) or
  `DISPATCH_SATURATED` (more than 64 function values reach it, so the adapter resolved none) or
  `DISPATCH_PARTIAL` (bodies were resolved and are in the graph, and a value with no known
  origin in the scope reaches the receiver as well: a parameter no known caller passes a value
  to, the result of a method outside the scope, a field nothing in the scope assigns, so an
  alternative the scope cannot name may run too, through the override boundaries as well, so an
  interface parameter one caller fills with such a value opens the parameter of every
  implementation; or a holder more than 64 function values reached flows into the receiver, so
  the values it lost may run too, and a call that resolved nothing on such a receiver is
  `DISPATCH_SATURATED`). With cross-method tracking off the same question is answered within
  the method: a receiver assigned from a parameter, a field, a call's result, an element of an
  array such a value was stored into, or a copy of such a local is open. The call site itself is in the graph with its
  interface callee; a call on a function value absent from this map resolved to every body it
  runs that the scope holds. A value is "known" relative to the scope: a caller outside it that
  passes a value to a parameter known callers also pass values to is not represented.

| `AnalysisStatus` | Meaning | Body complete |
|---|---|---|
| `ANALYZED` | the body was read and everything it produces is in the graph | yes |
| `ANALYZED_WITHOUT_CONTROL_FLOW` | dataflow and calls are in the graph; branch scopes were left out for the body's size (more than 2,000 statements) | yes |
| `ABSTRACT` | an abstract or interface method; dispatch reaches its overrides | no |
| `NATIVE` | a native method; its effect needs a model | no |
| `NO_BODY` | neither abstract nor native, yet without a body | no |
| `INCOMPLETE` | processing stopped part way (out of memory); the nodes created before are in the graph, the rest is not | no |
| `RESOLUTION_FAILED` | the frontend could not read the body, or the class's methods; nothing of it is in the graph | no |
| `EXCLUDED_BY_SCOPE` | the class was loaded for type resolution only | no |
| `NOT_EXPANDED` | the class was reached under a `Reachable` scope and left unread because the class budget ran out | no |
| `EXTERNAL` | the class was never loaded: the JDK, a jar the build did not read, a class no input holds | no |
| `UNKNOWN_MEMBER` | the class was analysed and declares no such method | no |

`Graph.analysisStatus(method)` answers from the method's own record, else `ANALYZED` when the
graph holds the method (`Graph.hasMethod`, an index lookup on every graph implementation), else
the class's status, or `UNKNOWN_MEMBER` for an analysed class without that member. A call site's
`callee` is the declaring method as the adapter resolves it, not the receiver class the bytecode
spells: a Kotlin facade call (`StringsKt.trim`) names the part that declares the body
(`StringsKt__StringsKt.trim`), and the facade itself answers `UNKNOWN_MEMBER`.

Node existence never stands in for a status. A method's `ReturnNode` is created before its body
is read, so a body the build gave up on has nodes and is `INCOMPLETE`; a body the frontend
could not resolve has none and is `RESOLUTION_FAILED`; a method with no outgoing call and a
method whose calls were never read are told apart only here. A graph built by a frontend before
this evidence existed has none, and every answer is `null`: unknown, not analysed.

## Persistence

`GraphStore.save` writes the evidence as `graph.completeness` beside the graph and removes the
file an earlier save left when the graph carries none. The file names classes and methods, not
node ids, so it needs no node layout to read; it is bound to the save all the same: it carries
the SHA-256 of `graph.nodedata` that `forward.properties` records (`graphite.nodeLayout.sha256`)
and ends with the SHA-256 of its own bytes, which the save records in `forward.properties` too
(`graphite.completeness.sha256`). The node layout alone would not do: two saves of the same
nodes under different scopes write the same layout and different evidence, and the evidence of
the one must not read as the other's. The payload between header and trailer is gzip-compressed,
since it is mostly class names (uncompressed it added 2.5% to a persisted Tika graph). A file
whose trailer is not the one the directory recorded, or that carries another node layout, is
another save's and reads as absent. A bound file that no longer hashes, is cut short, has bytes
after its last entry, or names a status this reader does not know is corruption after binding
and throws rather than answers. A loaded graph that has not read its evidence yet reads it before
a save into its own directory replaces the directory.
Both JVM loads read it on the first call for it and never before, and so does the Rust reader
(`graphite_storage::completeness`), which answers `analysisStatus(x)` and `dispatchGap(c)` in the
Rust engine as the Kotlin engine answers them from `Graph.analysisStatus`. Status and role codes index a name table the file carries, so a value added later still reads
as itself.

## The JavaScript frontend

A JavaScript build records the same evidence in the same file, so one reader answers for both
frontends. The frontend lists what it did in the `completeness` section of its JSON interchange
(`classes`, `methods`, `callSiteGaps`) and the Rust importer writes `graph.completeness` from it,
bound to that save's node data, before it seals the directory. An interchange without the
section writes no file, and the graph answers `null`.

What the statuses mean for compiled JavaScript:

| Case | Recorded as | A caller reads |
|------|-------------|----------------|
| A module, a class or an object literal whose function bodies were read | class `ANALYZED` | `analysisStatus` of its methods: `ANALYZED` |
| An artifact that does not parse (the build warns and goes on) | class `RESOLUTION_FAILED`, named by its module path | a call that would have reached it is a dispatch gap; the class is on record as failed, not as calling nothing |
| A callee that is a JavaScript library binding (`JSON.stringify`, `Array.prototype.map`) | nothing: its owner is not a class of the build | `EXTERNAL` |
| A callee outside the artifacts (`node_modules`, an unresolved import) or a value nothing binds (a callback parameter, a computed member) | call site `DISPATCH_UNRESOLVED` | `dispatchGap`: `DISPATCH_UNRESOLVED`; `analysisStatus`: `EXTERNAL` |

The gap set is exactly the calls whose `CALL` edge is `isDynamic`. A call the TypeScript checker
resolves to a body is not a gap, and that resolution is static: JavaScript can rebind a function
at run time, and the frontend follows only the bindings it can see. `ABSTRACT`, `NATIVE`,
`NO_BODY`, `INCOMPLETE`, `EXCLUDED_BY_SCOPE` and `NOT_EXPANDED` do not arise for compiled
JavaScript (it has no bodiless declarations, and the build has no scope or budget); the
interchange accepts them for a frontend that needs them.

## The Apple frontend

An Apple build (Swift, Objective-C) records the evidence in the trailer of its Graph IR
(`Completeness` in `ir/graphite_ir.proto`), and the JVM importer persists it as
`graph.completeness` with the graph. A reader that predates the field ignores it; a stream
without it leaves the graph answering `null`. The frontend's facts come from the compiler's
index, so the statuses say what the index can and cannot tell:

| Case | Recorded as | A caller reads |
|------|-------------|----------------|
| A type declared in the indexed sources, or a module that owns free functions or top-level code | class `ANALYZED`, origin the module (none for C and Objective-C, which Clang indexes without one) | `analysisStatus` of its methods: `ANALYZED` |
| A protocol requirement with no default implementation | method `ABSTRACT` | `ABSTRACT` |
| An Objective-C method or C function a header declares with no definition in the indexed sources (accessors a property names and the compiler synthesizes included) | method `NO_BODY` | `NO_BODY` |
| A callee on a class of the build that the sources never declare: a member the compiler synthesized (a memberwise initializer, a derived conformance) | method `NO_BODY` | `NO_BODY`, where the class alone would answer `UNKNOWN_MEMBER` |
| A callee from the SDK, the standard library or another package | nothing | `EXTERNAL` |
| A dynamically dispatched call whose named method has a body in the build, when another member of the build overrides or implements it | call site `DISPATCH_PARTIAL` | the named body is in the graph; the other may run and is not linked |
| A dynamically dispatched call whose named method has no body in the build (a requirement, an SDK method), when a member of the build implements or overrides it | call site `DISPATCH_UNRESOLVED` | no body is linked to the call |

A call site names the method the compiler resolved statically, and an Apple graph links no
override to it. A gap is recorded exactly when the index marks the call as dynamically
dispatched and the build holds another body the receiver could select: a member with a body
that overrides or implements the named method under a descriptor of its own. A requirement
and its default implementation in an extension of the protocol are one method to the graph
(one descriptor), so the default is the named method and not "another body". A call bound
statically (`super.f()`, a `final` or struct method) leaves no gap, and neither does a dynamic
call nothing in the build overrides: an override outside the build is outside the scope, as
for the JVM frontend, and the callee's own status says what the build knows of it.

What the evidence does not cover:

- A call on a closure or another function value has no call site in an Apple graph (the index
  names no callee for it), so no gap can point at it.
- A source file under the roots that the build did not compile has no index record and no
  entry: its types are simply not classes of the build, and read as `EXTERNAL`.
- `ANALYZED` means the calls the index resolves in the body are in the graph. Dataflow in an
  Apple graph is limited to what the IR header's `source_dataflow` option names.
- The gap is as coarse as the index's dynamic role: it does not ask whether the receiver's
  static type could hold the overriding class.

Committed checks: `CompletenessTests` and `ObjectiveCTests` (the frontend, on a package SwiftPM
builds and on a Clang-indexed class), `IrCompletenessTest` (the importer: the evidence becomes
the graph's, and evidence it cannot take at its word is refused), and
`backend/bench/apple-completeness-e2e.py` in `apple.yml` (a real build of
`frontend/apple/Fixtures/Dispatch`, the Rust CLI and the jar answering the same table).

## What this delivery does not do

- The roots of a `Reachable` expansion are application classes, by name prefix, not entry
  methods, and the expansion runs on the folded bodies of the build it is part of: a `select`
  fold rule's staging build expands on the unfolded bodies, the final build on the folded ones.
- The expansion follows the view's class hierarchy for virtual calls (every override the view
  knows), not a points-to analysis: it reads more classes than run, never fewer, within the
  budget.
- A gap is recorded for calls on function values only, and openness is relative to the scope:
  a method's parameter that known callers pass function values to is treated as closed even
  where a caller outside the scope could pass another. A virtual call whose receiver class is
  unknown has every override the view knows linked by `interproceduralDataflow`; one whose
  implementation lives outside the view is `EXTERNAL` by its class.
- No multi-release selection: the archive is reported as multi-release, every class is read at
  its base version. Class-path precedence outside a Spring Boot jar is the input's listing
  order, reported and applied alike.
- No reading of the evidence by the Rust server; `graphite query` and `serve` ignore the file.
