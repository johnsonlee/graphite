---
name: graphite
description: Investigate a codebase through a Graphite program graph (Cypher over a graph built from compiled artifacts, via the graphite MCP server or the graphite CLI). Use when asked to find entry points or system boundaries, trace call chains or control flow, trace where a value comes from or goes to, find where configuration keys, environment variables or resource files are read, find existing A/B test or feature flag touch points, or assess the blast radius of a change, and a Graphite graph (.graphite file, saved graph directory, or graphite MCP tools) is available or can be built.
---

# Graphite: answering code questions from the program graph

Graphite turns compiled artifacts into a graph of values, call sites, fields,
parameters, returns, annotations and packaged resources, and answers Cypher over it.
Each language has a frontend that builds the graph (`graphite frontend list`); the
storage, Cypher engine and MCP tools are shared.
Use it to get **evidence** (exact call sites, constants, signatures) quickly, then
read source only where the graph points.

## 0. Get access to a graph

Pick whichever is available, in this order:

1. **MCP tools** (`schema`, `cypher`, `endpoints`, `resources`, `resource`,
   `annotations`, `overview`, `c4`, `graphs`, `node`, `incoming`, `outgoing`,
   `subgraph`). Omitting `graph_id` queries every loaded graph; `node`, `incoming`
   and `outgoing` require it.
2. **CLI**: `graphite query --format json <graph> "<cypher>"`, where `<graph>` is a
   `.graphite` file or saved graph directory.
3. **Build one**: `graphite frontend list` shows the installed frontends and
   `graphite frontend describe <name>` the inputs each accepts. With the JVM
   frontend (JAR/WAR/APK/class directory, needs Java 17+):
   `graphite build app.jar -o app.graphite --include com.example`
   (`--include`/`--exclude` take package prefixes; `--include-libs` adds
   `WEB-INF/lib`/`BOOT-INF/lib` jars or Android platform classes; APKs need
   `--android-sdk` or `ANDROID_HOME`). Then either query with the CLI or
   `graphite serve --id app app.graphite --port 8080` for HTTP + MCP at `/mcp`.
   Large inputs (for example `android-all`) need a larger heap; set it through
   `JAVA_OPTS`, e.g. `JAVA_OPTS=-Xmx16g graphite build ...`.

A graph reflects the artifact it was built from, not your working tree. If the
question concerns uncommitted code, say so or rebuild.

## 1. Always start with the schema

Call the `schema` tool (or `GET /api/graphs/{id}/schema`) before writing Cypher.
It returns every label set with counts and property keys, every relationship type
with counts, and the most frequent `(labels)-[type]->(labels)` patterns, in
milliseconds. Graphs differ by builder version and input: for example
`ResourceValue` nodes and some `CONTROL_FLOW` kinds may be absent. Do not assume a
label exists because this skill mentions it.

## 2. What `schema` does not tell you

`schema` lists labels, property keys, relationship types and patterns. It omits:

- **`Method`**, a virtual label for every declared method (including uncalled
  ones): `signature`, `class`, `name`, `parameter_types`, `return_type`.
- **Relationship properties.** `r.kind` on `DATAFLOW` is one of `ASSIGN`,
  `PARAMETER_PASS`, `RETURN_VALUE`, `FIELD_STORE`, `FIELD_LOAD`, `ARRAY_STORE`,
  `ARRAY_LOAD`, `CAST`, `PHI`; on `CONTROL_FLOW` it is `SEQUENTIAL`,
  `BRANCH_TRUE`, `BRANCH_FALSE`, `SWITCH_CASE`, `SWITCH_DEFAULT`, `EXCEPTION` or
  `RETURN` (which of these appear depends on the frontend). `CALL` has
  `r.virtual` and `r.dynamic`.
- **Signature format**: `com.example.Foo.bar(java.lang.String,int[])`; nested
  classes use `$`, constructors are `<init>`, static initializers `<clinit>`.
  `Annotation.member` is `<class>` for a type-level annotation.

## 3. Facts that change how you write queries

Getting these wrong gives empty or misleading results. They were verified on
graphs from the JVM frontend. On a graph from another frontend, check the
`schema` patterns before relying on them (for example, whether `CALL` edges
connect distinct nodes or only loop on call sites).

- **There are no method-to-method edges.** The call graph is a *join*:
  a call site's `callee_signature` equals another call site's `caller_signature`.
  Walk it hop by hop (see `references/control-flow.md`).
- **Calls record the declared target.** A call through an interface or superclass
  has `callee_class` = that interface/superclass, not the implementation. Type
  hierarchy is not queryable in Cypher; find implementations with
  `MATCH (m:Method {name: 'send'}) RETURN m.signature` and confirm in source.
- **Dataflow stops at call sites.** Arguments flow into the `CallSiteNode`
  (`PARAMETER_PASS`), and the call site flows to the local receiving its result
  (`RETURN_VALUE`). The callee's `ParameterNode`/`ReturnNode` are not linked to
  callers; cross a method boundary with a join on
  `p.method = cs.callee_signature` or `r.method = cs.callee_signature`. The
  argument position is not exposed on the edge.
- **Receivers also flow into call sites** (`ASSIGN`), so `(x)-[:DATAFLOW*]->(cs)`
  includes the object the method was invoked on. Filter on the source label.
- **Keys are not always `Constant`s.** Enum values and static fields that are not
  compile-time constants arrive as a static `FieldNode` (`class` = the enum or
  holder type, `name` = the constant) through `FIELD_LOAD`. When searching for the
  key, flag or value reaching a call, run `(key:Constant)` and
  `(key:FieldNode) WHERE key.static` as two halves joined by `UNION`
  (`references/feature-flags.md` step 2). On the Kotlin compiler graph, every
  `LanguageFeature` flag arrives as a `FieldNode` and none as a `Constant`.
- **The argument position is not recorded.** Every constant reaching a call site
  comes back together: `DeviceConfig.getBoolean(namespace, key, default)` yields
  `['0', 'privacy', 'safety_protection_enabled']`. Group by call site
  (`id(cs)`) and interpret by the callee's parameter types. JVM booleans are
  ints, so `false`/`true` appear as `0`/`1`.
- **`RESOURCE_LOOKUP` edges can over-approximate.** In graphs built by
  Graphite 2.8.0, `System.getProperty` calls are linked to packaged
  `.properties` files. On the Kotlin compiler and Tika graphs every
  `RESOURCE_LOOKUP` edge was such a call (one Tika call to 352 files). Graphs
  rebuilt with a frontend that includes the fix
  ([#160](https://github.com/johnsonlee/graphite/pull/160)) have no such edges,
  but a graph persisted earlier keeps them until it is rebuilt, so excluding
  `java.lang.System` is safe on any graph. In every version, a `Properties`
  lookup whose key or file can't be bound is linked to all configuration
  resources, so check fan-out before reporting a file as read.

JVM frontend specifics (javac/kotlinc output):

- **`static final` compile-time constants are inlined by javac.** The graph sees
  the literal (`"checkout.new_flow"`, `1001`), not `Flags.NEW_CHECKOUT`. Search by
  value; map the value back to its constant name in source.
- **Branch polarity is JVM-level.** `if (flag) {A} else {B}` usually compiles to
  `if flag == 0 goto B`, so `BRANCH_TRUE` leads to **B**. Never report which
  branch is "on" from `kind` alone; confirm in source.
- **Only `BRANCH_TRUE`/`BRANCH_FALSE` control-flow edges are emitted.** There
  are no `SEQUENTIAL`, `SWITCH_*`, `EXCEPTION` or `RETURN` edges, so `switch`
  produces no `CONTROL_FLOW` edges; string switches appear as
  `String.hashCode`/`String.equals` call sites with the case literals flowing in.
- **`line` is often null.** Report `caller_signature` as the location.
- **Kotlin `object` and companion receivers** are static fields named `INSTANCE`
  or `Companion`; they reach call sites as receivers and are not keys.
- **Synthetic call sites**: `sootup.dummy.InvokeDynamic.makeConcatWithConstants`
  is string concatenation. Lambdas, method references, anonymous classes,
  Kotlin lambda and `suspend` lambda classes, callable references and D8/R8
  desugared lambdas all show up as extra call sites with a `CALL` self-loop
  where `dynamic = true`: one in the method that creates the function value,
  pointing at its body, and one at each call through it (`fn.apply(x)`,
  `fn(x)`), pointing at the implementation it resolves to.
- **Lambda bodies have callers, except in older graphs.** Upward chains pass
  through lambda, coroutine and anonymous-class bodies (`Outer$method$1.invoke`
  / `invokeSuspend`) like any other method. In graphs built by Graphite 2.8.0,
  before [#162](https://github.com/johnsonlee/graphite/pull/162), only
  `invokedynamic` lambdas were linked, so class-based bodies had no callers;
  there, hop to the enclosing method through the class's `<init>` call
  site or `INSTANCE` field read (`references/control-flow.md`, "Lambda and
  coroutine bodies"). Erased bridge methods (`invoke(Object)`) may still have no
  caller; their typed sibling does.

## 4. Query hygiene

- Anchor on the rare end with equality, `IN` or `STARTS WITH` on indexed string
  properties (`callee_class`, `callee_name`, `caller_signature`, `value`, ...).
  Avoid unanchored `MATCH (n) WHERE n.x CONTAINS ...` and `=~` on large graphs.
- **Bound every variable-length path**: `[:DATAFLOW*1..6]`, not `[:DATAFLOW*]`.
  Common constants (`0`, `1`, `""`, `null`) fan out enormously.
- **Never start a variable-length path at an unlabeled node.**
  `MATCH (x)-[:DATAFLOW*1..6]->(cs) WHERE ...` scans every node: it timed out
  after 200 s on a 300k-node Android graph, where the same question split into
  labeled halves with `UNION` took 0.4 s.
- Use `RETURN DISTINCT`, aggregate (`count`, `collect(DISTINCT ...)`), and `LIMIT`.
  MCP/HTTP responses carry `total`; `relation: gte` means results were cut
  (`graphite query` output has no `total`, so compare the row count to your `LIMIT`).
- Label predicates are not supported inside `WHERE` (`WHERE n:Constant` is a
  syntax error). Put the label in the pattern, or test `'Constant' IN labels(n)`.
- `=~` in the `graphite` binary uses Rust regex syntax (no look-around or
  backreferences). Escape dots: `'com\\.example\\..*'`.
- For multi-graph servers, omit `graph_id` for one union query;
  `graphId(n)` tells you which graph a row came from.

## 5. Task playbooks

Read the reference for the task at hand; each has verified query recipes.
The recipes name JVM APIs and frameworks (Spring, `System.getenv`,
`java.sql`, ...) as examples. The query shapes carry over to other frontends:
substitute that platform's APIs, found with the outbound grouping query in
`references/entrypoints-and-boundaries.md`.

| Task | Reference |
|---|---|
| Identify entry points and boundaries (HTTP, main, listeners, outbound I/O, service topology) | `references/entrypoints-and-boundaries.md` |
| Trace control flow (callers/callees, call chains, paths from an entry point, branch guards, lambdas) | `references/control-flow.md` |
| Trace data flow (where a value comes from, where it goes, across fields and methods) | `references/data-flow.md` |
| Trace configuration (resource files, property keys, env vars, system properties) | `references/configuration.md` |
| Find existing A/B test and feature flag touch points | `references/feature-flags.md` |
| Blast radius of a change, architecture overview, dependency/API usage audits, dead code, security sinks | `references/more-recipes.md` |

## 6. Reporting

- Separate **graph facts** (rows returned, with the query) from **inference**.
- Cite each finding as `caller_signature` → `callee_signature` (plus value/key),
  so it can be opened in source.
- State coverage limits when they matter: only the code the graph was built from
  (for the JVM frontend, the packages passed to `--include`) was analyzed; reflection, dependency injection wiring, dynamic class loading,
  remote configuration and code generated at runtime can leave relationships
  missing. An empty result means "not found in this graph", not "does not exist".
