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
| `Reachable(maxClasses, roots)` | every library jar | the classes the application's calls reach (from the root classes only, when roots are named), and the classes those bodies' calls reach, up to the budget |

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
graphite build app.jar -o /data/app --include com.acme.app --include-libs --dependency-reachable=20000 \
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
callee (class hierarchy analysis), and the class every `new` names, including the function-object
classes the application allocates; then the classes those bodies reach, and so on. Each class is
read once, so the expansion terminates on any call graph, cycles included. Reading stops at
`maxClasses` (50,000 by default); a class reached past the budget is `NOT_EXPANDED`, which a
proof must treat as unread, never as a class with no effect, and a library class nothing reaches
stays `EXCLUDED_BY_SCOPE`. Expansion follows calls, so a body reached only through a function
value resolved in the dispatch fixpoint is read when the class that holds it was reached by
other means (its allocation is a call to its constructor); an entry a framework discovers by
reflection or configuration is not reached, as the issue's contract expects. `roots` names the
application classes (by prefix) whose calls seed the expansion; the other application classes
are still read, being the application, but what only they call is not followed into the
libraries. The roots are part of the fold provenance (`dependency_roots`).

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
- `classes`: every class of the view, with the artifact it was read from and `ANALYZED`,
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
  `DISPATCH_SATURATED` (more than 64 function values reach it, so the adapter resolved none).
  The call site itself is in the graph with its interface callee; a call on a function value
  absent from this map resolved to every body it runs that the scope holds.

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
node ids, so it needs no node layout to read; it is bound to the save all the same, by the
SHA-256 of `graph.nodedata` that `forward.properties` records (`graphite.nodeLayout.sha256`),
and ends with the SHA-256 of its own bytes; the payload between is gzip-compressed, since it is
mostly class names (uncompressed it added 2.5% to a persisted Tika graph). A file bound to another save reads as absent. A
bound file that no longer hashes, is cut short, has bytes after its last entry, or names a
status this reader does not know is corruption after binding and throws rather than answers.
Both JVM loads read it on the first call for it and never before; the Rust reader does not read
it. Status and role codes index a name table the file carries, so a value added later still reads
as itself.

## What this delivery does not do

- The roots of a `Reachable` expansion are application classes, by name prefix, not entry
  methods, and the expansion runs on the folded bodies of the build it is part of: a `select`
  fold rule's staging build expands on the unfolded bodies, the final build on the folded ones.
- The expansion follows the view's class hierarchy for virtual calls (every override the view
  knows), not a points-to analysis: it reads more classes than run, never fewer, within the
  budget.
- A gap is recorded for calls on function values only. A virtual call whose receiver class is
  unknown has every override the view knows linked by `interproceduralDataflow`; one whose
  implementation lives outside the view is `EXTERNAL` by its class.
- No multi-release selection: the archive is reported as multi-release, every class is read at
  its base version. Class-path precedence outside a Spring Boot jar is the input's listing
  order, reported and applied alike.
- No reading of the evidence by the Rust server; `graphite query` and `serve` ignore the file.
