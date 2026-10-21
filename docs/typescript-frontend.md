# TypeScript source frontend

The TypeScript frontend analyzes source using the TypeScript compiler API and
writes a graph that the Rust CLI can save, query and serve. It accepts a
`tsconfig.json`, a source directory, or a TypeScript/JavaScript source file.
Node.js 20 or newer is required for building the graph. Reading a saved graph
requires only the Rust binary.

## Build and query

From a Graphite source checkout:

```bash
npm ci --prefix frontend/web
npm test --prefix frontend/web
cargo build --release --locked -p graphite-cli
export GRAPHITE_FRONTEND_TS="$PWD/frontend/web/dist/cli.js"

./target/release/graphite frontend describe ts
./target/release/graphite build --lang ts /path/to/project/tsconfig.json -o /tmp/project-graph
./target/release/graphite query /tmp/project-graph \
  "MATCH (cs:CallSite) RETURN cs.caller_class, cs.caller_name, cs.callee_name, cs.line LIMIT 20" \
  --format json

# A single-file container has the same query interface.
./target/release/graphite pack /tmp/project-graph -o /tmp/project.graphite
./target/release/graphite verify /tmp/project.graphite
./target/release/graphite query /tmp/project.graphite \
  "MATCH (cs:CallSite) RETURN count(cs) AS calls" --format json
./target/release/graphite serve --graph project:/tmp/project.graphite
```

You can also build directly to `-o /tmp/project.graphite`. The frontend is found
through `GRAPHITE_FRONTEND_TS` or a `graphite-frontend-ts` launcher on `PATH`.
`GRAPHITE_NODE` selects the Node.js executable when a JavaScript entry point is
used. The JVM frontend and its existing default remain available; use `--lang ts`
to select source analysis explicitly. The TypeScript frontend is built from this
checkout; the JVM Homebrew installation and `frontend install jvm` do not install
it.

## What the graph represents

Call sites retain their containing function, target and source line.
Top-level functions use the source path as their `caller_class`/`callee_class`;
class methods append the class name, and nested functions/object methods include
the containing function scope. Names are relative to the selected source
root, so selecting `src` and selecting its parent project produce different path
prefixes. Cross-file import aliases resolve through TypeScript symbols. Literal
arguments flow into their call sites and are available as `StringConstant`,
numeric and boolean nodes.

For example, a call to `all.get('*')` inside an `emit` method can be found with:

```cypher
MATCH (s:StringConstant {value: '*'})-->(cs:CallSite {caller_name: 'emit', callee_name: 'get'})
RETURN s.value AS event, cs.caller_class AS source, cs.line AS line
```

A project config controls file inclusion and compiler resolution. A directory
uses its own `tsconfig.json` when present; otherwise its source files are scanned.
Analyzing a project does not invoke its build scripts or install its dependencies.
Missing dependency types and other semantic diagnostics are reported, and analysis
continues with the information available. Install dependencies yourself before
analysis when their declarations are needed for target resolution. Invalid config
and syntax errors fail the build. Project-reference configs are rejected explicitly;
build each referenced project separately.

This is conservative, flow-insensitive and context-insensitive static source
analysis: values from different assignments and calls can share graph paths. It
does not model control-flow graphs or branch reachability. Call-site ordinals are
not available; use source lines to locate calls. Dynamic property lookup, runtime monkey patching,
and calls through values whose implementation cannot be resolved are not a proof
of a complete runtime call graph. TypeScript source analysis does not provide the
JVM frontend's bytecode optimization, folding or Android/resource behavior.

## Reproducible open-source correctness checks

The verification harness downloads immutable source archives, verifies their
SHA-256 digests, and analyzes the actual source without executing project scripts
or installing project dependencies:

| Project | Pinned release and commit | Input | Concrete checks |
|---|---|---|---|
| [mitt](https://github.com/developit/mitt/tree/b240473b5707857ba2c6a8e6d707c28d1e39da49) | 3.0.1 · `b240473b5707857ba2c6a8e6d707c28d1e39da49` | `src/` | `on → get/set`, `off → splice/indexOf`, `emit → get`, wildcard `'*'` argument flow |
| [Zod](https://github.com/colinhacks/zod/tree/e30870369d5b8f31ff4d0130d4439fd997deb523) | v3.24.2 · `e30870369d5b8f31ff4d0130d4439fd997deb523` | Original `tsconfig.json`, including its inherited file selection | `addIssueToContext → getErrorMap/makeIssue`, `ParseStatus.mergeObjectAsync → mergeObjectSync`, `mergeArray → dirty`, `'aborted'` string |

```bash
python3 .github/scripts/check-typescript-e2e.py \
  --cli ./target/release/graphite \
  --frontend ./frontend/web/dist/cli.js \
  --output /tmp/graphite-typescript-e2e
```

Use Python 3.12 or newer and an empty output directory for a fresh run. The
harness preserves every command's stdout, stderr and exit status, and writes
per-project query results and `summary.json`. Downloaded archives and extracted
sources remain available for inspection. Each project is checked as a saved
directory, a packed container, an unpacked directory and a directly built
container. Complete ordered node and edge query results must match across all
four forms, as must the concrete source assertions. This verifies actual graph
content through fresh CLI processes, including source locations and resolved
cross-file targets, rather than relying only on nonzero counts.

The `typescript-build-save-query` CI job runs the same checks after frontend tests
and a release CLI build and uploads command/query evidence even on failure.
Existing Rust, JVM, differential and benchmark regression checks remain separate
requirements. These single-project checks establish correctness only; they make
no latency, throughput, CPU, RSS or performance regression claim.

### Verified source snapshot

On 2026-10-08, the pinned sources above passed on macOS ARM64 with Node.js
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
Complete node and edge query results also match after each save/load path. Zod
reported 19 semantic diagnostics because target dependencies were not installed;
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
the required PR benchmark regression gate.
