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

A library class the scope covers is processed as an application class is: its methods, their
bodies, their annotations and its fields. It does not become application code. It registers no
endpoint and runs no `GraphiteExtension`, its artifact keeps the `LIBRARY` role, and the
evidence tells it apart from a class the scope left out. The artifact patterns are the glob
dialect of `--lib-filter` (`*` matches any text) and match the jar's file name, whatever
directory a directory input keeps it in (`lib/acme-helpers-1.0.jar` matches `acme-helpers-*.jar`).
A declared scope needs `includeLibraries`; `LoaderConfig` refuses the combination otherwise.

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
  dependency scope). The evidence is relative to it and claims nothing outside it. The Android
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
  archive in the order the input lists them, loaded or not. The graph holds the first
  definition. A Spring Boot jar's runtime order (`BOOT-INF/classpath.idx`) is not consulted.

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

- No selective expansion from roots. A declared scope is processed eagerly, every body of every
  class it covers. Processing only the bodies a surviving call reaches is the next step the
  issue names; the evidence this delivery records (`EXCLUDED_BY_SCOPE` at the frontier) is what
  such an expansion would turn into `ANALYZED`, and the scope it would need is already
  separate from the application identity.
- No per-call-site gap record. A virtual call's unresolved targets, a function value whose
  holder saturated (`MAX_TARGETS`), and an `invokedynamic` the adapter did not resolve are not
  recorded against the call site; the method-level statuses and `Graph.hasMethod` are what a
  consumer has.
- No class-path precedence beyond listing order, and no multi-release selection: both are
  reported, not resolved.
- No reading of the evidence by the Rust server; `graphite query` and `serve` ignore the file.
