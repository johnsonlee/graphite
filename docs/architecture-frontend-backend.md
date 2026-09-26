# Graphite architecture: language frontends, a graph IR, and one Rust backend

Status: proposal (design for review). Owner: johnsonlee. Baseline: PR #124 (the Rust
backend) on top of `main` at #128.

**Reading this document:** this is a design proposal anchored to the baseline
above, not a current feature inventory. The Rust CLI now runs `query`, `serve`,
and `mcp`, while `build` delegates to the JVM frontend. The proposed TypeScript
and Swift frontends and Graph IR pipeline below should not be read as released
capabilities. See the [README architecture](../README.md#architecture) and
[runnable demo](quickstart-demo.md) for the current user workflow.

Graphite today is one JVM program: SootUp builds the graph, the same jar persists it, serves
it and answers Cypher over it. PR #124 adds a Rust server and CLI that read the persisted
graph, and the differential harness shows the two servers answer identically. That leaves the
project with a JVM-only front half and two back halves. This document proposes the shape to
converge on:

```
                     per language                       one, in Rust
        ┌──────────────────────────┐   Graph IR    ┌───────────────────────────────┐
JAR/WAR │ graphite-frontend-jvm    │ ───────────▶  │ graphite build   (indexer)     │
APK/AAR │  (SootUp, Kotlin)        │               │   └─ persisted graph (v4)      │
        └──────────────────────────┘               │ graphite serve   (HTTP + UI)  │
        ┌──────────────────────────┐   Graph IR    │ graphite query   (CLI)        │
TS/JS   │ graphite-frontend-web    │ ───────────▶  │ graphite explore (C4/topology)│
        │  (TypeScript compiler)   │               └───────────────────────────────┘
        └──────────────────────────┘                        ▲
        ┌──────────────────────────┐   Graph IR             │ same on-disk format,
Swift   │ graphite-frontend-apple  │ ───────────▶           │ same Cypher, same API,
ObjC    │  (index-store-db)        │                        │ any number of graphs
        └──────────────────────────┘
```

Frontends know a language and produce a **Graph IR**. The backend knows the graph and
produces indexes, answers, diagrams. The contract between them is the IR, not the on-disk
format: a frontend never writes BVGraph, string tables or trigram indexes, and the format can
evolve without touching any frontend.

The two decisions already taken and reflected below: the IR carrier is chosen for the
long term (hundreds of graphs, 10^8 nodes in a corpus), and every Cypher property name in
use today stays valid; the language-neutral core gets new names.

## 1. Goals and non-goals

Goals

- One CLI, `graphite`, in Rust: `build`, `serve`, `query`, `explore`, plus IR tooling. A
  user installs one binary; the JVM is needed only when a JVM input is built.
- A language-neutral graph model with a stable core and per-language extensions, so a
  Cypher query written for a JVM graph reads naturally on a TypeScript or Swift graph, and
  a corpus can mix them.
- Frontends as independently released programs with a small, versioned contract.
- Full backward compatibility for persisted graphs, Cypher property names, labels, the HTTP
  API and the explorer UI. Every existing query keeps its meaning and its result.
- Scale target for the backend and the IR: a corpus of hundreds of graphs, the largest
  graphs at 10^7 nodes and 10^8 across the corpus, on one machine.

Non-goals

- Porting SootUp, the TypeScript compiler or the Swift frontend to Rust. Language analysis
  stays in the language's own ecosystem.
- A single universal IR for *program semantics* (SSA, types, effects). The IR carries the
  graph Graphite already exposes -- nodes, edges, properties, resources -- not a compiler IR.
- Replacing the persisted format wholesale. The webgraph layout stays; it gains a manifest
  and a few persisted indexes.

## 2. Component overview

| Layer | Component | Language | Owns |
|---|---|---|---|
| Frontend | `graphite-frontend-jvm` | Kotlin (existing `sootup`, `core` under `frontend/jvm/`) | JAR/WAR/APK/AAR/class dirs → IR |
| Frontend | `graphite-frontend-web` | TypeScript (new) | TS/JS projects → IR |
| Frontend | `graphite-frontend-apple` | Swift (new) | Swift/ObjC modules → IR |
| Contract | Graph IR | spec + Rust reader/writer, thin writers per language | Section 4 |
| Backend | `graphite-ir` | Rust (new crate) | IR schema, reader, writer, validation, JSONL bridge |
| Backend | `graphite-build` | Rust (new crate) | IR → persisted graph: ids, adjacency, strings, indexes, columns |
| Backend | `graphite-storage` | Rust (exists) | mmap reader of the persisted graph, indexes, columns |
| Backend | `graphite-cypher` | Rust (exists) | Cypher parser, planner, executor, functions |
| Backend | `graphite-explore` | Rust (exists) | HTTP API, registry, guard, metrics, UI, C4, topology |
| CLI | `graphite` | Rust (exists as `cli/`, grows) | `build`/`serve`/`query`/`explore`/`ir`/`frontend`; the one entry point, independent of any single frontend or backend crate |

The Kotlin modules `cypher`, `webgraph` (writer side excepted, see migration), `explore`
and `query` under `frontend/jvm/` are retired at the end of the migration;
`core` and `sootup` (under `frontend/jvm/`) become the JVM frontend.

### 2.1 Repository layout

The tree follows the split from PR #124 on. Directory names carry no `graphite-` prefix;
Gradle project paths (`:core`, `:sootup`, ...) and Cargo package names (`graphite-storage`,
...) are unchanged, so Maven and crate coordinates are stable.

```
graphite/
├── Cargo.toml                     Cargo workspace root: backend/* and cli
├── cli/                           `graphite` binary; depends on backend crates, owns no
│                                  analysis and no storage of its own
├── backend/                       Rust backend
│   ├── storage/                   persisted-graph reader (mmap), indexes, columns
│   ├── cypher/                    Cypher parser, planner, executor
│   ├── explore/                   HTTP server, UI, C4, topology
│   ├── ir/                        (phase 2) IR schema, reader/writer, check/diff
│   ├── build/                     (phase 2) IR → persisted graph indexer
│   └── bench/                     differential harness, backtests, fixtures
├── frontend/
│   ├── jvm/                       Kotlin: `core`, `sootup` (the frontend proper) plus, until
│   │   │                          phase 5, `cypher`, `webgraph`, `query`, `explore` (the
│   │   │                          legacy JVM server and `graphite.jar`)
│   │   ├── core/ sootup/ cypher/ webgraph/ query/ explore/
│   │   └── (phase 2) IR writer; builds `graphite-frontend-jvm.jar`
│   ├── web/                       (phase 6) TypeScript frontend, npm package
│   └── apple/                     (phase 7) Swift package
└── docs/
```

The benchmark gate compares a base revision with a candidate revision and installs
harness files into both trees by path, so every workflow step and script that touches a
base, gate, or tagged tree resolves a module directory through a small `jvm <tree> <module>`
shell helper: `<tree>/frontend/jvm/<module>` when present, else the pre-move
`<tree>/graphite-<module>`. Historical tags keep working without rewriting history.

## 3. The graph model

### 3.1 Core kinds

The core is what every frontend must be able to express and what the backend indexes
specially. It is the current JVM model with the JVM-specific parts moved behind
language-neutral names.

| Core kind | Meaning | Core properties | JVM | Web (TS/JS) | Apple (Swift/ObjC) |
|---|---|---|---|---|---|
| `Module` | Compilation/distribution unit | `name`, `path` | jar, apk, dex, source set | package, entry bundle, workspace project | module, framework, target |
| `Type` | Named type | `name`, `qualified_name`, `module`, `kind` (`class`/`interface`/`enum`/`struct`/`protocol`/`function-type`) | class, interface, enum, annotation type | class, interface, type alias, enum | class, struct, enum, protocol, actor |
| `Member` | Callable or stored member | `owner`, `name`, `signature`, `kind` (`method`/`ctor`/`field`/`property`/`function`) | method, constructor, field | function, method, property, arrow member | func, init, property, subscript |
| `CallSite` | A call expression | `caller`, `callee` (symbol refs), `line`, `receiver`, `arguments` | invoke*, `<init>` | call, new, tagged template, JSX element | call, message send, init |
| `Constant` | A literal | `value` (typed), `constant_type` | Int/Long/Float/Double/Boolean/String/Null/Enum | number/bigint/string/boolean/null/enum member | Int/Double/String/Bool/nil/enum case |
| `Local`, `Parameter`, `Return` | Data-flow endpoints inside a member | `owner`, `name`/`index`, `type`, `actual_type` | as today | as today | as today |
| `Annotation` | Metadata attached to a symbol | `name`, `owner`, `member`, `values` (open map) | annotation | decorator | attribute (`@objc`, `@available`, property wrapper) |
| `Resource`, `ResourceValue` | Non-code file and its parsed entries | `path`, `source`, `format`, `profile`; `key`, `value` | properties/yaml/xml/json | json/env/yaml, `package.json` | plist, xcconfig, strings, json |

`Type` and `Member` are new as first-class nodes. Today methods and classes exist only in
`graph.metadata` and as strings on call sites; making them nodes is what lets a Web graph
put a component or a route handler on the graph and lets the C4 inference read one model
instead of parsing signatures.

### 3.2 Symbol references

Today a call site embeds a `MethodDescriptor` (declaring class, name, parameter types,
return type), all as string ids. That descriptor is JVM syntax. The core replaces it with a
**symbol reference**: `(module, owner, name, signature)` as four string ids, where the
signature is an opaque string in the frontend's own grammar, plus an optional `symbol_id`
pointing at the `Member` node when the frontend resolved it. The backend indexes the four
strings (it already does for the JVM's `caller_class`/`callee_name`, and composes the
signature raw off the record); nothing in the backend parses a signature.

JVM keeps its exact strings, so `callee_signature` prints what it prints today.

### 3.3 Edge families

Unchanged in meaning; the 8-bit label encoding stays (family in bits 0–2, subkind in 3–6):

| Family | Subkinds | Notes |
|---|---|---|
| `DATAFLOW` | ASSIGN, PARAMETER_PASS, RETURN_VALUE, FIELD_STORE, FIELD_LOAD, ARRAY_STORE, ARRAY_LOAD, CAST, PHI | every frontend must emit at least ASSIGN, PARAMETER_PASS, RETURN_VALUE |
| `CALL` | flags (direct, virtual, interface, static, special) | from `CallSite` to `Member` once members are nodes |
| `TYPE` | EXTENDS, IMPLEMENTS | + `CONFORMS` (protocol) and `ALIAS` reserved |
| `CONTROLFLOW` | SEQUENTIAL, BRANCH_TRUE/FALSE, SWITCH_CASE/DEFAULT, EXCEPTION | optional for a frontend |
| `RESOURCE` | OPENS, LOADS, BUNDLE_CANDIDATE, LOOKUP, ENUMERATES | optional |
| `CONTAINS` (new) | Module→Type, Type→Member | structural, cheap, what C4 and the class overview walk today by string prefix |

Three subkind slots are still free per family; new families need a format version bump
(bit 7 is reserved for that).

### 3.4 Language extensions and naming

Every node carries `lang` (`jvm`, `web`, `apple`, ...). Extension properties are namespaced
by language: `jvm.access_flags`, `jvm.descriptor`, `web.exported`, `web.jsx`,
`apple.objc_selector`, `apple.availability`. Frontends may add any key under their
namespace without a schema change; the backend stores them as an open property map on the
node (the way annotation value pairs are stored today) and indexes them as string columns
when the manifest declares them indexable.

**Backward compatibility of names.** The JVM property names that queries use today are
kept as first-class aliases, resolved at the property-access layer of the query engine and
the HTTP node serialisation, for JVM graphs and for any graph whose frontend declares the
alias:

| Existing name | Core name |
|---|---|
| `callee_class`, `callee_name`, `callee_signature` | `callee.owner`, `callee.name`, `callee.signature` |
| `caller_class`, `caller_name`, `caller_signature` | `caller.owner`, `caller.name`, `caller.signature` |
| `class` (Field, Annotation) | `owner` |
| `enum_type` | `constant_type` |
| `method` (Local, Parameter, Return) | `owner.signature` |
| `static` | `jvm.static` |

Labels stay: `CallSite`, `CallSiteNode`, `Constant`, `StringConstant`, ... remain valid
labels on every graph, and the core adds `Type`, `Member`, `Module`. `keys(n)` keeps
listing the legacy names on JVM nodes so `any(k IN keys(n) ...)` keeps its result set;
core names are listed in addition only when they are not aliases of a listed key.

## 4. The Graph IR

### 4.1 Requirements

- Streams: a frontend writes it in one pass without holding the graph in memory; the
  indexer reads it in bounded memory.
- Columnar and dictionary-encoded: 10^7 nodes per graph, 10^8 across a corpus, without
  parsing text per field.
- Implementable from any language in a few hundred lines, with mature libraries where they
  exist.
- Self-describing and versioned; forward-compatible for extension properties.
- Inspectable with off-the-shelf tools.

### 4.2 Carrier: Apache Arrow IPC (stream format), zstd-compressed record batches

Arrow is the choice for the long term: it is columnar, dictionary encoding is built in,
batches stream, the schema travels with the data, zstd/lz4 buffer compression is part of
the IPC format, and DuckDB/Polars/pyarrow open it directly for inspection and ad-hoc
checks. Rust (`arrow-rs`), the JVM (`arrow-java`) and TypeScript (`apache-arrow`) have
first-party libraries; Swift has an Apache-maintained implementation whose writer covers
what the IR needs (primitive, utf8, list, struct, dictionary). A frontend on a platform
without a usable Arrow writer emits the **JSONL profile** (same schema, one object per
row) and the CLI converts: `graphite ir convert --to arrow`. Both carriers are equivalent
by definition; the JSONL one is also what debugging and tests use.

Sizing: at ~40 bytes per node row and ~12 per edge row before compression, a 10^7-node
graph with 5×10^7 edges is ~1 GB raw and ~250 MB as compressed IPC; batches of 65,536 rows
keep the indexer's working set to a few batches plus the growing string dictionary. The
persisted graph, not the IR, is what a corpus keeps; the IR is a build input and may be
deleted after `build` (or kept for rebuilds when the format changes).

### 4.3 Layout

An IR is a directory (or a single `.tar`/`.zip` of it):

```
<name>.graphite-ir/
  manifest.json          schema_version, lang, frontend {name, version}, source {kind, path,
                         digest}, options, node_count, edge_count, indexable extension keys
  strings.arrow          one column: utf8 dictionary; row i is string id i
  nodes.arrow            id:u32 | kind:u8 | lang:u8 | line:i32? | props: struct per kind
                         (string ids for strings, typed scalars for numbers/bools)
  edges.arrow            src:u32 | dst:u32 | family:u8 | subkind:u8 | props (optional)
  symbols.arrow          symbol_id:u32 | module:u32 | owner:u32 | name:u32 | signature:u32
  ext.arrow              node:u32 | key:u32 | value: union(utf8, i64, f64, bool, list)
  resources/             raw resource files (paths from `Resource` nodes)
```

Rules the indexer relies on: node ids are dense `0..n`, the frontend's iteration order is
the id order (the backend's node order today), edges reference existing ids, string ids
reference `strings.arrow`, and `manifest.json` declares which `ext` keys should get a
persisted column.

### 4.4 Validation

`graphite ir check` verifies referential integrity, dense ids, kind/property agreement,
and the manifest digests, and prints per-kind counts. `graphite build` runs the same
checks first and fails closed. `graphite ir diff a b` compares two IRs structurally (used
in migration, Section 7).

## 5. Persisted format (v4) and the indexer

The layout in `docs/webgraph-storage.md` stays; version 4 adds what the backend has been
building at load time and what the model above needs:

| File | Change |
|---|---|
| `graph.manifest` (new) | copy of the IR manifest plus build info: format version, `lang`, frontend, indexer version, graph identity |
| `graph.nodedata` | record kinds for `Module`, `Type`, `Member`; symbol references replace inline method descriptors on `CallSite`/`Local`/`Parameter`/`Return` (a v3 reader shim keeps decoding v3 records) |
| `graph.symbols` (new) | symbol table: the four string ids per symbol, CSR from symbol to members |
| `graph.columns` (new) | persisted per-type string columns (node ids + string ids + distinct + trigrams) that the Rust backend today builds on first use; includes the composed-signature column |
| `graph.ext` (new) | extension property store, CSR by node, plus columns for the keys the manifest marked indexable |
| `graph.callsite-string-index` | unchanged |
| `graph.labels`, `forward.*`, `backward.*` | unchanged; `CONTAINS` uses family 5 |

**Indexer (`graphite build`)** is a streaming pipeline in Rust: read `strings.arrow` →
build the sorted, front-coded string table and the id remap → stream `nodes.arrow` writing
`graph.nodedata` and the per-type columns → stream `edges.arrow` into an external-sortable
adjacency builder → BVGraph compression of forward (and backward, which today is built
lazily) → indexes (type, offsets, symbols, callsite trigram, columns) → manifest. Each stage
is bounded by the batch size except the adjacency sort, which spills to disk above a memory
budget. Target: a 10^7-node graph builds in minutes on a laptop, and building is
embarrassingly parallel across graphs of a corpus.

**Compatibility.** The Rust backend keeps reading v1–v3 graphs (it already does). A v3
graph reports `lang=jvm`, `frontend=legacy`, and the property aliases are the only names
it has. `graphite build --from <v3 graph>` re-indexes an existing graph into v4 without a
frontend, so a corpus can be upgraded without rebuilding from the jars.

## 6. The backend (as implemented in PR #124, and what changes)

### 6.1 Storage (`graphite-storage`)

Read-only, mmap-based. Every file is mapped and decoded lazily: BVGraph adjacency
(`bvgraph.rs`), the front-coded string table with binary search (`strings.rs`), node
records at fixed offsets (`node.rs`), type and offset indexes, metadata, resources,
comparisons, the CallSite trigram/CSR index (`callsite_index.rs`, built in memory when the
file is absent, byte-identical to the Kotlin writer), and per-type string columns
(`columns.rs`). Nothing is decoded that a query does not touch; a graph costs page cache,
not heap. v4 makes the columns and the signature column persisted (Section 5) and adds the
symbol and extension stores; the rest of the reader is unchanged.

Every loader reads through `source.rs`: a graph is a directory of files or one
`.graphite` container (`container.rs`), a STORED zip with page-aligned entries and a
`META-INF/graphite.manifest` of sizes and SHA-256 digests, mapped once and sliced per
entry. The frontends keep writing directories; `graphite build -o x.graphite` stages the
directory next to the output and the CLI packs it, so the single file exists for every
frontend without any of them knowing. `graphite verify|info|pack|unpack` operate on
the container. The v4 additions (manifest, symbol table, extension store) are further
entries of the same archive.

### 6.2 Query engine (`graphite-cypher`)

Lexer, parser and AST for the Cypher subset the explorer exposes; an evaluator with the
baseline's three-valued logic, value ordering and function set; and a planner whose whole
purpose is to avoid decoding records:

- **Per-type pruning.** Every WHERE leaf is settled per node type before any graph is
  touched: a property the type does not store, a number against a string property, a
  membership test against a scalar, a synthetic key folded per graph. Types that cannot
  match are never read.
- **Raw record reads.** The four CallSite strings go through the trigram/CSR index;
  other string properties through columns; signatures are composed from string ids and
  memoised per distinct method; integers, booleans, ids and per-node synthetic keys are
  read at fixed offsets. Rows that survive an exact plan skip the WHERE re-check.
- **Corpus scheduling.** Graphs are settled by their trigram bitmap first, then prepared in
  batches (one, then one per thread, then doubling) so a `LIMIT` met early pays for one
  plan, and full sweeps use every core; rows come out in source order.
- **Fast paths** for `count(*)` over bare patterns, grouped CallSite properties and
  `DISTINCT` string properties; an anchored single-hop plan for relationship patterns with a
  predicate.

With v4 the planner gains `Type`/`Member`/`Module` as ordinary node types with their own
columns, `CONTAINS` as an ordinary family, symbol lookups by `(owner, name)` and the
extension columns. Property aliases resolve in one place (`props.rs`), so a JVM query on a
v4 graph and on a v3 graph plan identically.

### 6.3 Server (`graphite-explore`)

Axum HTTP server exposing the same routes, bodies and `Content-Type`s as the Kotlin
server (checked by the 875-case differential): graph registry with load/unload and
provenance, request guard (concurrency admission, work budget, timeouts, cancellation),
Prometheus metrics, the static UI, the OpenAPI document, the C4 inference and its four
renderers, the topology graph rebuilt on load/unload. Multi-language corpora need one
addition: the registry exposes `lang` per graph and the UI shows it; C4 inference reads
`Module`/`Type`/`Member` and `CONTAINS` when present and falls back to the string-prefix
heuristics for v3 graphs.

The server also speaks the Model Context Protocol: the thirteen tools of the former
`graphite-mcp` npm package (`graphs`, `openapi`, `cypher`, `node`, `outgoing`, `incoming`,
`annotations`, `endpoints`, `resources`, `resource`, `subgraph`, `overview`, `c4`) plus
`schema` (`GET /api/schema`, `GET /api/graphs/{id}/schema`: label sets with node counts
and keys, relationship types with counts, the most frequent `(labels)-[type]->(labels)`
patterns) are built in, each dispatched in-process to the same route handler the REST API
runs. Two
transports: `graphite mcp` over stdio for local clients, and `POST /mcp` on `graphite
serve` (Streamable HTTP) for remote ones. The npm package is retired with v2.5.0.

### 6.4 CLI (`graphite`)

```
graphite build   <input> -o <graph> [--lang jvm|web|apple|auto] [--frontend <exe>] [frontend args...]
graphite build   --from-ir <dir.graphite-ir> -o <graph>      # indexer only
graphite build   --from <v3 graph> -o <graph>                 # re-index
graphite serve   [--graph id:path]... [--data dir] [--port] [--topology file]...
graphite query   <graph> '<cypher>' [--format json|table|csv]
graphite explore <graph> --level context|container|component|all --format json|mermaid|plantuml|dsl
graphite ir      check|convert|diff|stats
graphite mcp     [--graph id:path]... [--data dir] [--topology file]...   # MCP over stdio
graphite frontend list|install|describe
```

`build` picks the frontend by `--lang`, by input extension (`.jar/.war/.apk/.aar/.dex` →
jvm; `package.json`/`tsconfig.json` → web; `Package.swift`/`.xcodeproj` → apple), or by
an explicit executable. Everything after the frontend's own `--` is passed through, so
`--include`, `--android-sdk` and the other SootUp options keep working unchanged.

### 6.5 Frontend protocol

A frontend is an executable named `graphite-frontend-<lang>` found on `PATH`, in
`~/.graphite/frontends/`, or given with `--frontend`. The contract is three commands:

- `describe` → JSON on stdout: `{name, version, ir_schema: [versions], inputs: [...],
  aliases: {...}, indexable: [...]}`. The CLI refuses a frontend whose IR schema it does not
  read.
- `build --out <dir.graphite-ir> [args...]` → writes the IR; progress as JSON lines on
  stderr (`{"phase": "...", "done": n, "total": m}`); exit code 0 on success, 2 for
  unsupported input, 3 for a partial graph (the CLI then refuses to index unless
  `--allow-partial`).
- `version`.

The JVM frontend is a jar plus a tiny launcher script; the CLI locates `java`
(`GRAPHITE_JAVA`, `JAVA_HOME`, `PATH`) and runs it with the heap the Homebrew wrapper used
(`JAVA_TOOL_OPTIONS`, else `JAVA_OPTS`, else `-Xmx8g`). The Web frontend is an npm package
with a `bin`; the Apple frontend is a Swift package binary. Each has its own release
cadence and version; the CLI records the frontend name and version in the manifest.

**Phase 1 as shipped in PR #124.** The jar-era frontend has no `describe` or `--out` yet:
`graphite build <args>` passes every argument to `graphite.jar build`, which writes the
persisted graph directly, and the CLI answers `frontend describe jvm` itself (running the
jar's `--version` for the version, reporting an empty `ir_schema`). `graphite frontend
install jvm` fetches `graphite.jar` from the release of the CLI's own version and verifies
it against the published `graphite.jar.sha256`. The three-command protocol above replaces
this shell in phase 2 without changing the user-facing command.

### 6.6 Frontend distribution

The CLI is the only thing a user installs by hand. A frontend is a separately versioned
artifact in its language's own ecosystem, and the CLI fetches, verifies and runs it.

| Frontend | Artifact | Published to | Runtime it needs |
|---|---|---|---|
| jvm | `graphite-frontend-jvm-<ver>.jar` (fat jar) + `graphite-frontend-jvm` launcher | GitHub Release asset; Maven Central `io.johnsonlee.graphite:frontend-jvm` (for Gradle/Maven users who embed it); Homebrew `graphite-frontend-jvm` (depends on `openjdk@17`) | JDK 17+ |
| web | npm package `@johnsonlee/graphite-frontend-web` with a `bin` | npm; GitHub Release tarball | Node 20+ |
| apple | `graphite-frontend-apple` static binary (macOS x86_64/arm64) | GitHub Release asset; Homebrew | Xcode toolchain for index/SIL |

How the CLI finds one, in order: `--frontend <exe>`, `GRAPHITE_FRONTEND_<LANG>`, an
executable `graphite-frontend-<lang>` on `PATH`, then `~/.graphite/frontends/<lang>/<ver>/`.
If none is present, `graphite build` prints the exact `graphite frontend install <lang>`
command and exits 2; `graphite frontend install` downloads the newest release asset whose
`describe` reports an IR schema this CLI reads, checks its sha256 against the release
manifest, and records it. `graphite frontend list` shows what is installed and which IR
schema each speaks; `graphite frontend update` upgrades within the compatible range.

Compatibility is a single contract, the IR schema version (Section 4). The CLI declares
the range it reads; each frontend declares the versions it writes; either side can move
independently as long as the ranges overlap. A frontend release never requires a CLI
release, and a CLI release only requires new frontends when the IR schema itself changes.

Release mechanics: one tag on this repository builds and uploads the CLI binaries, the
`graphite-explore` server image, the JVM frontend jar (from `frontend/jvm`, published to
the Release and to Maven Central by the existing publish workflow);
the Web and Apple frontends live in `frontend/web` and `frontend/apple` and ship from the
same tag. For CI users a `graphite:jvm` container image bundles the CLI, a JRE and the JVM
frontend so that `graphite build app.jar` works with no other install.

## 7. Migration strategy

Every step keeps `main` releasable and is gated by the differential harness, so nothing
is taken on trust.

| Phase | Work | Exit criterion |
|---|---|---|
| 0 | Merge PR #124. Rust backend serves v3 graphs; Kotlin server still shipped. | 875/875 differential, gate green (done) |
| 1 | `graphite` CLI gains `build` as a shell over the existing jar (`java -jar graphite.jar build ...`), `serve` (the Explorer; `backend/explore` becomes a library and the standalone binary goes away) and `frontend list/describe/install`; the release ships the binary per platform, the Homebrew formula installs it with the jar as its frontend, the container image serves with it. One command line for users. **Done in PR #124.** | `graphite build` on the CI fixtures produces the same graph as the jar (byte-identical but for a properties timestamp), and the 875-check differential runs against `graphite serve` |
| 2 | Graph IR v1 spec, `graphite-ir` crate (reader, writer, JSONL bridge, `check`/`diff`), `graphite-build` indexer producing **v3** graphs from IR (no model change yet). JVM frontend emits IR alongside its direct save. | For every fixture graph (core jar, acme, the 64-graph corpus): `build` from the jar's IR is query-identical to the jar's own save (170-query digests + 875-case differential); `ir diff` between two runs is empty |
| 3 | Persisted v4: manifest, persisted columns, symbol table, extension store; `build --from` re-index; backend reads v3 and v4. | v3 and v4 of the same graph answer every differential case identically; column pre-warm cost disappears from first-query latency |
| 4 | Core model: `Module`/`Type`/`Member` nodes, `CONTAINS`, symbol references on the JVM frontend; aliases wired into property access, `keys()`, serialisation. Kotlin server frozen. | Old queries unchanged on v4 JVM graphs; C4 output byte-identical to today on the six reference graphs |
| 5 | Retire Kotlin `serve`/`query`/`explore`/`cypher`: `graphite.jar` becomes the frontend only; Docker image, Homebrew formula and docs switch to the Rust binary; Kotlin modules deleted. | Release pipeline publishes one CLI + frontends; parity harness retargeted to v-1 Rust vs current Rust |
| 6 | Web frontend (TypeScript). | The IR validates; the Explorer's own web UI (a TypeScript-free static app today, the first candidate) builds into a graph the explorer can browse; C4 on a multi-package workspace |
| 7 | Apple frontend (Swift, then ObjC). | A sample iOS app builds; cross-graph queries across a JVM backend graph and a Swift client graph |

Compatibility rules, enforced by the harness at every phase:

- Persisted v1–v3 graphs load and answer as before, forever (the reader shims are small).
- Every property name, label, function and route in use today keeps its meaning.
- A frontend's IR from version N builds with indexer N+1 (the IR schema is additive within a
  major version).
- Server and CLI are one binary and one version; frontends are versioned separately and
  recorded in the manifest.

Risks and how they are contained: the JVM frontend's IR emission is the riskiest step and is
gated by byte-level `ir diff` plus query digests; the model change (phase 4) is gated by the
C4 byte identity and the differential; the Web and Apple frontends are additive and cannot
regress JVM users.

## 8. Platform and language support

### 8.1 JVM (existing)

Inputs: JAR, WAR (`WEB-INF/lib`), Spring Boot fat jars (`BOOT-INF/lib`), APK/AAR/DEX
(Android platform classes via `--android-sdk`), class directories. Analysis: SootUp.
Becomes `graphite-frontend-jvm` with no analysis change; the only work is the IR writer
(`arrow-java`) and the `describe` command. Kotlin, Java, Scala and Groovy are all covered
at the bytecode level, which is why this frontend stays on the JVM.

### 8.2 Web: TypeScript and JavaScript

Frontend: TypeScript compiler API (or `ts-morph` over it), which gives a resolved program
for TS and for JS with `allowJs`/`checkJs`, including type information, symbol resolution,
decorators and JSX. Input: a `tsconfig.json`/`package.json` project or a workspace
(npm/pnpm/yarn workspaces, each package a `Module`). Mapping: modules → `Module`; classes,
interfaces, enums, type aliases → `Type`; functions, methods, properties, arrow-function
members → `Member`; calls, `new`, JSX elements and tagged templates → `CallSite` with the
resolved symbol (or the syntactic callee text when unresolved, as SootUp does for
reflection); decorators → `Annotation`; literals → `Constant`; `package.json`, `.env`,
JSON/YAML config → `Resource`/`ResourceValue`. Data flow: assignments, parameter passing,
returns and property stores from the checker's flow nodes; that is enough for the queries
Graphite serves today (`value CONTAINS`, argument constants, endpoints). Routes and
endpoints: framework adapters (Express/Koa/Nest routes, Next.js pages) as extension
properties under `web.`. Bundled/minified inputs are out of scope for the first version;
source maps can be a later input.

### 8.3 Apple: Swift and Objective-C

Frontend: the compiler's index store. `swiftc -index-store-path` (and `clang
-index-store-path` for ObjC) emits, during a normal build, every symbol, occurrence and
relation with USRs; `indexstore-db` reads it. That gives `Module`/`Type`/`Member`,
`CallSite` (call occurrences with the callee USR), conformance and inheritance (`TYPE`),
and attributes, with no parser of our own and correct handling of generics, extensions and
protocols. Data flow needs SIL: a second stage over `swiftc -emit-sil` is where
`DATAFLOW` edges come from, starting with parameter passing and returns. Input: a
`Package.swift`, an `.xcodeproj`/`.xcworkspace` with a scheme, or an existing index store
directory. Resources: `Info.plist`, `.xcconfig`, `.strings`, asset catalogs (names only).
Compiled binaries (Mach-O with symbols) are a possible later input at the symbol level
only.

### 8.4 Later candidates

Go (`go/packages` + `golang.org/x/tools/go/ssa` give types, calls and SSA data flow
directly), Kotlin/Native and Rust (`rustc` `-Z` unstable APIs or `rust-analyzer`'s HIR),
Python (typed via `pyright`'s or `jedi`'s symbol tables; data flow is weak without types).
Each is a frontend with the same three commands; none needs a backend change.

## 9. Delivery and packaging

- The first tag of this layout is `v2.5.0` (rehearsed as `v3.0.0-alpha1` to `-alpha6`).
  It stays in the 2.x line: the REST routes, the MCP tools and the Maven coordinates
  are those of 2.4.8; what changes is packaging, the installed `graphite` becomes a
  native binary with the jar as its frontend and the MCP server moves from npm into it.
- One release tag drives everything: the Rust CLI/server binaries (linux x86_64/aarch64
  musl, macOS x86_64/aarch64) as GitHub Release assets, the `graphite-explore` container
  image (a static binary on `distroless`), the Homebrew formula (`graphite`, no JDK
  dependency once the JVM frontend is optional), and each frontend to its ecosystem (the
  jar to the Release and Maven Central, the Web frontend to npm, the Apple frontend to the
  Release).
- `graphite frontend install jvm` fetches the matching frontend into `~/.graphite/frontends/`
  (Section 6.6 for the artifacts, resolution order and the compatibility contract).
- Until phase 5, `graphite.jar` keeps its `serve`/`query` for users who have not moved.

## 10. Open questions

1. Symbol identity across graphs: whether cross-graph topology rules should match on the
   core `(module, owner, name)` tuple rather than on strings, so a JVM server graph and a
   Web client graph can be related by API path without a Cypher join on text.
2. Whether `DATAFLOW` for the Web and Apple frontends should be produced by the frontend or
   by a shared, language-neutral pass in the backend over a small "statement IR". The
   frontend route is simpler and is what this document assumes; the shared pass would give
   identical dataflow semantics across languages at the cost of a second IR.
3. The CLI's Java discovery policy for the JVM frontend (bundle a JRE via the frontend's
   own installer, or require one on the machine as today).
