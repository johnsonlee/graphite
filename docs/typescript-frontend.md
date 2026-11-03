# JavaScript artifact frontend

The JavaScript frontend analyzes compiled output from JavaScript and TypeScript
projects. It accepts emitted `.js`, `.mjs` and `.cjs` files, or an artifact
directory such as `.next`, `dist` or `build`. Node.js 20 or newer runs the
frontend; reading a saved graph requires only the Rust binary.

Compile the target project first using its own build tool. Graphite neither
runs project build scripts nor installs target dependencies. Emitted modules in directories named `cache` are included;
only Next’s `.next/cache` compiler output is skipped alongside dependencies. Source `.ts`,
`.tsx`, `.mts`, `.cts`, `.jsx` files and `tsconfig.json` are rejected with a
compile-first message. The `ts` selector is an alias for artifact analysis,
not a request to parse TypeScript source.

## Build and query

Prepare Graphite's frontend and CLI from a source checkout:

```bash
npm ci --prefix frontend/web
npm test --prefix frontend/web
cargo build --release --locked -p graphite-cli
export GRAPHITE_FRONTEND_JS="$PWD/frontend/web/dist/cli.js"

./target/release/graphite frontend describe js
./target/release/graphite build --lang js /path/to/project/dist -o /tmp/project.graphite
./target/release/graphite verify /tmp/project.graphite
./target/release/graphite query /tmp/project.graphite \
  "MATCH (cs:CallSite) RETURN cs.caller_class, cs.caller_name, cs.callee_name, cs.line LIMIT 20" \
  --format json
./target/release/graphite serve --graph project:/tmp/project.graphite
```

Place `--lang` immediately after `build`. `js`, `javascript`, `ts` and
`typescript` select the same frontend. With no selector, the existing JVM build
path is preserved. Discovery uses `GRAPHITE_FRONTEND_JS`, then its compatibility
alias `GRAPHITE_FRONTEND_TS`, then `graphite-frontend-js` or
`graphite-frontend-ts` on `PATH`. `GRAPHITE_NODE` selects the Node executable for
a JavaScript entry point. The npm package provides both launcher names.
`frontend install jvm` and the JVM Homebrew installation do not install this
frontend; use the source-checkout setup above.

An output without `.graphite` is a saved directory. You can subsequently use
`graphite pack <directory> -o <file.graphite>` and `graphite unpack` as for JVM
graphs. Existing outputs are refused. Analysis and import occur in a temporary
sibling directory; frontend failures and invalid graph output leave no saved
graph. Packed graphs use the existing entry manifest and SHA-256 verification,
including the adjacent `.graphite.sha256` file. A packed JS build needs a
filesystem that supports hard links. It publishes the completed digest first,
then the container, refusing either name if another writer has claimed it.
If a process is killed between those steps, the digest can remain by itself.

For an already-built Next.js application, select emitted server and browser
artifacts separately when useful:

```bash
graphite build --lang js /path/to/app/.next/server -o /tmp/app-server.graphite
graphite build --lang ts /path/to/app/.next/static/chunks -o /tmp/app-client.graphite
graphite serve --graph server:/tmp/app-server.graphite --graph client:/tmp/app-client.graphite
```

Artifact discovery includes hidden `.next` directories and does not skip output
because it lives under `dist` or `build`. Supplying an artifact root keeps the
analysis boundary explicit; unrelated source and configuration files are not
compiled by Graphite. The server's `/api/cypher` endpoint queries both loaded
graphs; per-graph endpoints select just one.

## Artifact graph semantics

Call sites retain their containing function, target and emitted-file line.
Module paths are relative to the selected artifact root. Class methods include
the class owner; nested functions and object methods retain lexical ownership.
Changing the selected root changes path prefixes. Import aliases resolve through
the TypeScript checker where the emitted code retains resolvable imports.
Literal arguments appear as constant nodes and flow into call sites.

For example, an emitted `all.get('*')` call can be inspected with:

```cypher
MATCH (s:StringConstant {value: '*'})-->(cs:CallSite {callee_name: 'get'})
RETURN s.value AS argument, cs.caller_class AS artifact, cs.line AS line
```

Default parameters are normalized into explicit argument flow when the call
can be resolved. Missing arguments and known `undefined` values (including
`void` expressions and expanded literal-array spreads) select the default;
`null` remains an explicit argument. Earlier parameter references and writes by
later defaults are reflected in this normalization. Unknown values retain both
possible supplied-value and default flows. In particular, an initial `undefined`
type on a mutable binding cannot erase a later supplied value. For example,
`let x = void 0; x = 5; f(x)` must keep the flow from `5` to the argument.
The graph does not claim a precise execution order for all writes. Nested
defaults use the actual binding for that call; if a default might write an
earlier parameter, both its previous value and the possible write are retained.
An opaque `void` return type alone does not prove a runtime value is `undefined`.
The analyzer also recognizes the
exact leading default-parameter guard emitted by TypeScript for ES5; arbitrary
conditional code is not assumed to be an equivalent default.
Default expressions not reached by a resolved call remain visible as possible
argument flow for external entry points and callbacks; no source invocation is
invented for them.

For resolved calls, `CallSite.arguments` records effective values after the
parameter prologue, rather than the original JavaScript argument list. This is
dataflow normalization, not an executable source rewrite: `f()` and `f(1)` can
enter `function f(x = 1)` with the same parameter value but different
`arguments.length`. Original argument arity and the special `arguments` object's
runtime behavior are not modeled.

A reassigned formal parameter has a separate mutable binding, so writes do not
replace its incoming argument identity. Property locations and values read from
those locations are separate nodes. These distinctions preserve dataflow without
changing the existing persisted graph schema; they do not establish branch
reachability or eliminate unresolved dynamic call targets.

JavaScript strings can contain unpaired UTF-16 surrogates, which the existing
UTF-8 string table cannot directly represent. Such strings are stored in
textual fields, including `StringConstant.value` and inferred literal type names,
as the reserved prefix `\0js:utf16:` (a NUL character,
then `js:utf16:`), followed by four lowercase hexadecimal digits per UTF-16 code
unit. Original strings beginning with that prefix are encoded in the same way
to avoid collisions. All other valid Unicode strings remain unchanged. For
example, the one-unit JavaScript string `"\uD800"` is stored as
`"\u0000js:utf16:d800"` in JSON. This encoding is reversible, but Cypher returns
the stored representation: it does not transparently decode it. Clients must
encode these exceptional originals in value predicates and explicitly decode
returned values when needed; `frontend/web/src/strings.ts` defines both
operations. The graph interchange also preserves numeric negative zero (`-0`).

Analysis is flow-insensitive and context-insensitive: values from different
assignments or calls can share graph paths. It does not prove branch reachability
or produce a complete runtime call graph. Dynamic property lookup, callback
registration, runtime module resolution and monkey patching can remain unresolved.
Compiled output may rename or inline functions, remove code, introduce helpers,
and lower JSX to runtime calls; the graph describes that emitted code. It does
not reconstruct original TS types, original source locations, React component
lifecycles or Next.js route semantics. Source-map reconstruction and framework
adapters are separate features.

The TypeScript compiler API is used as a parser/checker, not as a project build
step. Syntax and compiler-option errors fail analysis. The frontend requests
symbols and types as it builds the graph, without type-checking the complete program. The artifact frontend does not provide
the JVM frontend's bytecode folding, Android behavior or resource extraction.

## Validation

Frontend unit tests verify graph identities, concrete call arguments and dataflow.
The end-to-end harness builds target projects explicitly, then runs Graphite
over their emitted client/server JavaScript. Build inputs, dependency locks and
artifact hashes are retained with command/query results so a saved graph can be
traced to the exact compiled output. Directory and `.graphite` round trips must
preserve complete queried graph contents.

The primary open-source fixture is the MIT-licensed
[cornflourblue/next-js-10-crud-example](https://github.com/cornflourblue/next-js-10-crud-example/tree/bf0046d35e80bbede589cd6f5435be16d70b6348)
at commit `bf0046d35e80bbede589cd6f5435be16d70b6348`. It includes React user
forms and Next API routes that persist users to a file. The harness checks its
license and archive hashes, and keeps its upstream configuration and dependency
lock unchanged. This historical Next 10 build requires the official Node.js
14.17.0 executable, passed with `--legacy-build-node`; that executable is used
only to prepare the fixture. Graphite analysis still uses Node.js 20 or newer.

The supplementary modern fixture is the public
[nextjs/async-react-demo Taskboard](https://github.com/nextjs/async-react-demo/tree/ec5b60f6362e956fea5a91fddfc0527c48d2a36f)
at commit `ec5b60f6362e956fea5a91fddfc0527c48d2a36f`, with React client actions
and a SQLite backend. That revision has no license and is not presented as an
open-source fixture. Its pinned dependency lock is preserved; the recorded
`experimental.cpus = 1` configuration override serializes SQLite initialization
during its Next 16 build.

Both fixtures use only `.next/server` and `.next/static` as graph inputs. The
harness records preparation and Graphite commands separately; analysis itself
still runs no target build scripts. Select `--project crud` (the default) or
`--project taskboard`, using separate output directories:

```bash
python3 .github/scripts/check-typescript-e2e.py \
  --project crud --legacy-build-node /path/to/node-v14.17.0/bin/node \
  --cli ./target/release/graphite \
  --frontend ./frontend/web/dist/cli.js \
  --output /tmp/graphite-js-crud-e2e

python3 .github/scripts/check-typescript-e2e.py \
  --project taskboard \
  --cli ./target/release/graphite \
  --frontend ./frontend/web/dist/cli.js \
  --output /tmp/graphite-js-taskboard-e2e
```

Use `--prepare-only` to prepare the compiled application before the Graphite
binary is ready; `--prepared-app <path>` reuses that application for subsequent
checks. Select the matching `--project` when reusing a prepared application.
Each run validates the prepared revision and artifact checksums. Pass/fail
claims belong to the generated report for the exact revisions under test; the
older source-prototype results below are not artifact validation.

These checks establish correctness. Performance acceptance requires matched,
representative multi-graph load tests whose requests actually target multiple
graphs, following [CONVENTIONS.md](../CONVENTIONS.md) and the
[benchmark protocol](../benchmark/README.md). Single-graph correctness runs and
hosted timing observations do not establish performance acceptance.

### Local artifact validation, 2026-11-03

The frontend at `7f5b40a6` passed 39 frontend tests. For each pinned application,
the harness verified the client and server bundle hashes, then built, saved and
queried fresh graphs with the revised producer:

| Application | Side | Nodes | Edges |
|---|---|---:|---:|
| MIT Next 10 CRUD example | Client | 46,701 | 69,225 |
| MIT Next 10 CRUD example | Server | 12,333 | 16,156 |
| Supplementary Taskboard | Client | 85,006 | 131,957 |
| Supplementary Taskboard | Server | 125,781 | 184,134 |

Complete node and relationship query results matched across saved directories,
packed containers, unpacked directories and directly built containers. Twenty
selected application call occurrences matched the independent emitted-code
oracle in all four forms. The harness requires exact occurrence
counts, including multiple calls on one minified line. HTTP queries loaded both
client and server graphs and matched the per-graph results. Saved default-value
fixtures preserved the effective arguments `shared`, `shared` and `null`.

The earlier producer (`3a127f70`, 30 frontend tests and 589 Rust workspace
passes) produced 46,676/69,136 and 12,343/16,140 nodes/edges for the two CRUD
sides, and 84,984/131,731 and 125,758/183,841 for Taskboard. The revised
graphs differ in binding and value dependencies. No visible call site was
added or lost. In each Taskboard graph, for example, a recursive call now
retains paths from both the reassigned binding and its default object.

At `42031d8d`, all 31 frontend tests and six independent harness tests passed.
Both applications' verified `.next` outputs were analyzed and saved again with
the revised frontend. Every complete node, edge and selected application query
matched the earlier graph results, including HTTP responses for the two loaded
sides. For `function send(x = -1_000, y = +0xAB_CD)`, calling `send()`
produced `IntConstant` values `-1000` and `43981`. Directory and packed queries
both found the `PARAMETER_PASS` edges to the call and the corresponding formals.

A separate native comparison ran main `a5c96f71` and candidate `3a127f70` over
the same 64 historical persisted JAR graphs. The trusted main controller's 73
query cases produced identical complete responses for all 8,638 requests across
the two versions; the 1,408 input files remained unchanged. This establishes
loading/query compatibility, not current JVM build/save coverage.

That earlier comparison did not establish performance acceptance. At concurrency four with 20 pressure
samples per case, the filtered query's p95 increased from 3426.559 ms to
3494.756 ms (+68.196 ms), while other major slow queries improved in that run.
CPU/RSS samples were within the resource allowances, but sampling cannot prove
true peak RSS or complete CPU bounds, and equal filesystem-cache residency was
not established. Earlier runs using different historical graph layouts, and a
failed resource monitor, remain diagnostic evidence only. These observations
are not a no-regression claim or a substitute for the required Linux CI gate.

Candidate `d27e054c` subsequently adds a narrowly guarded scalar WITH/filter/LIMIT
optimization. All 595 Rust workspace tests passed (one existing ignored test),
along with strict Clippy and formatting. Fifty-two query replay checks on the
same real client/server graphs passed, including complete saved node/edge
oracles and independent generic-query comparisons for the changed execution path.

Two predeclared real64 comparisons used the exact same files with their pages
held resident throughout all four arms. All 17,356 complete responses matched.
Against main, the filter query's p95 fell from 3381.610 ms to 0.580 ms, but sort,
descending-sort and collect p95 increased by 275.638, 246.900 and 137.564 ms.
Primary server lifetime CPU/RSS decreased; loading resource bounds remain
unresolved, including an observed pre-query RSS increase. This is a retained
positive intermediate result, not no-regression acceptance. The full protocol,
both comparisons, resource limitations and keep decision are recorded in
[Attempt 047](cross-graph-cypher-optimization-attempts.md#2026-11-03---attempt-047-stop-scalar-with-filters-after-enough-accepted-rows).

The subsequent [Linux comparison](cross-graph-cypher-optimization-attempts.md#2026-11-03---attempt-048-specialize-filtered-and-ordinary-row-consumers)
also built, saved and read back 64 real JAR shards and three whole JARs under
4 GiB heaps. All 8,518 native responses matched, with no OOMs. A separate
comparison on identical resident files satisfied the CPU and RSS bounds for
loading and the fixed mixed-query workload in three pairs. It also identified
repeatable traversal-count and relationship-histogram latency increases;
the resource result does not offset them.

## Historical source-prototype evidence

The previous source-analysis prototype used mitt 3.0.1 at
`b240473b5707857ba2c6a8e6d707c28d1e39da49` and Zod v3.24.2 at
`e30870369d5b8f31ff4d0130d4439fd997deb523`. It accepted TS source inputs,
which the artifact frontend now rejects. The record below is historical
correctness evidence only; it does not validate the current artifact analyzer.

On 2026-10-08, those pinned sources passed on macOS ARM64 with Node.js
20.19.6, TypeScript 5.9.3 and Python 3.14.7, using the debug CLI:

```bash
python3 .github/scripts/check-typescript-e2e.py \
  --cli target/aarch64-apple-darwin/debug/graphite \
  --frontend frontend/web/dist/cli.js \
  --output /tmp/graphite-typescript-e2e-4
```

| Project | Saved nodes | Saved edges | Query assertions in each of four forms | Result |
|---|---:|---:|---:|---|
| mitt 3.0.1 | 45 | 45 | 9 | Pass |
| Zod v3.24.2 | 19,245 | 30,167 | 7 | Pass |

The source assertions include exact call lines, Zod's cross-file `getErrorMap`
target, and mitt's returned-object owner `index.ts#object@54:9#mitt@46:1`;
that object starts on source line 54, column 9, inside `mitt` at line 46.
Complete node and edge query results also match after each save/load path. In
that historical source-frontend run, Zod reported 19 semantic diagnostics because target dependencies were not installed;
those warnings are retained in the build stderr. This record reports correctness
and graph contents, not performance measurements.

The existing JVM path was also checked locally with Guava 33.6.0-jre
(SHA-256 `dc573e1fca4fd5454f4a5fd3d7da2df03002876a4175bafc14a95980dd7713b3`)
using the candidate Rust CLI and the unchanged, freshly built JVM frontend at
base revision `4f2ccf33`, with `-Xmx4g`. The JVM directory reader, Rust directory
reader and Rust packed reader returned identical complete JSON for four queries:
node count (214,212), `Preconditions.checkNotNull` calls (1,299), concrete caller
class/name rows, and `DATAFLOW` edge count (195,710). This is compatibility
evidence; method-level and end-to-end performance acceptance still belongs to
the applicable benchmark acceptance protocol.
