# openCypher TCK integration

The independent top-level `tck/` Cargo module runs the official openCypher
Technology Compatibility Kit against Graphite's production Rust parser, validator,
matcher and executor. Acquisition, Gherkin expansion, execution and comparison are
implemented in Rust. No Python environment is required.

## Query compatibility

The TCK selects the public `opencypher-m23` dialect. The default `graphite` mode
retains established dynamic binding, runtime errors, canonical expression headers,
formatting and aggregate contracts. In particular, numeric-coercion min/max,
floating-point sums, historical aggregate-expression recognition and percentile
behavior remain available in Graphite mode. M23 mode uses static validation,
typed extrema, integer sums where applicable, aggregate expressions and explicit
percentile parameters. Both modes share the production engine.

## Reproduce

```sh
cargo build --release --locked -p graphite-tck --bins
target/release/graphite-tck --probe target/release/tck_probe --output /tmp/graphite-tck-results
cargo test --locked -p graphite-tck
```

When Cargo specifies a target, use `target/<target-triple>/release/` instead.
The probe explicitly selects the public `opencypher-m23` dialect. Reports identify
that mode; they do not imply that Graphite's default compatibility extensions have
been removed.

The runner downloads M23 at commit
`007895aff5f33097d67b2e48a0a2babd6bd18590` when its cache is absent.
`tck/upstream.json` pins the archive SHA-256 and every required file digest,
including LICENSE, NOTICE, 220 feature files and two named-graph setup scripts.
Bytes are verified before atomically publishing the ignored `tck/upstream/` cache.
Every run verifies an existing cache and rejects corruption. `--prepare-only`
prepares it without executing scenarios. Upstream sources are not checked in.

The Rust `gherkin` parser expands backgrounds, rules and every Scenario Outline
Examples row in source order. `--inventory-output <path>` exports the expanded
inventory for independent auditing.

## Verified coverage

The pinned M23 inventory contains **3,897 expanded scenarios**. The complete
2026-11-07 native replay passed all **3,897**, with zero failures, unsupported cases,
unexecuted cases or infrastructure errors. An independent Rust audit checked the
fixed inventory, complete primary responses and all 33 control responses. All
1,143 initially passing scenarios remained passing.

The first integration measured 1,143 passed, 1,045 failed and 1,709 unsupported.
Those are historical counts, not the current acceptance baseline. Later work
implemented native temporal values, property graph setup and updates, typed
procedures, correlated graph expressions and semantic validation, then reran every
official scenario without editing upstream queries or expectations.

Passing this pinned suite establishes coverage of its cases, not support for every
possible Cypher query, another TCK release, or Neo4j-specific extensions. This is a
correctness suite. It does not measure multi-graph performance or reproduce the
separate reported service-exit problem.

## Execution and comparison

Each scenario uses a fresh native `PropertyGraph` session. Official setup queries,
named graph scripts, the primary query and control queries run through the same
production executor. Procedure tables register typed callbacks in the production
`ProcedureRegistry`; the adapter does not interpret queries. Persisted program
graphs retain their immutable storage contract.

Native lexer, parser, validator and runtime errors expose their own structured
error type, phase and detail. The adapter does not infer codes from error messages.
Expected error triples are checked precisely, including the official `any time`
and `*` contracts where explicitly requested by a scenario.

Complete results preserve column order, value types and graph structure. Ordered
tables preserve row order; unordered tables compare multisets and retain duplicate
rows. Official unordered-list assertions affect Cypher lists, not path structure.
Floating positive and negative zero compare equal under official float semantics;
raw results and outcome hashes retain their original signs. Integers, floats and
booleans remain distinct comparison types.

Side effects come from actual before/after graph snapshots. Failed native write
transactions roll back. Reports retain queries, parameters, official expectations,
actual complete responses, side effects and control-query contracts and responses.
Each native query has a bounded execution deadline; a broken child process or
protocol is an infrastructure failure, never an expected query error or a pass.

## Public dialect selection

The default `graphite` dialect preserves historical expression, conversion,
aggregation, projection and error behavior, including container formatting,
UNWIND rebinding and permissive dynamic indexing. Standard fixes use the explicit
M23 mode so existing callers can retain their results and error contracts.
The same production executor offers `opencypher-m23` for standard contracts:

- Rust: `Executor::with_dialect(CypherDialect::OpenCypherM23)`.
- CLI: `graphite query <graph> '<query>' --dialect opencypher-m23`.
- HTTP: `dialect=opencypher-m23` or JSON `"dialect":"opencypher-m23"` on single,
  global and selected-graph Cypher endpoints, including fanout mode.
- MCP: optional `dialect` argument on the `cypher` tool.

Unknown dialects are rejected. Nested comprehensions and existential subqueries
inherit the selected dialect. Mode tests and existing persisted-graph tests cover
both standard behavior and retained default extensions.

Rust clients supplying parsed clauses should select the same dialect with
`parser::parse_with_dialect` or `parse_cached_with_dialect`. The existing `parse`
and `parse_cached` utilities retain Graphite grammar, including right-associative
powers and left-folded comparison chains. M23 grammar requires explicit selection
at parser and executor entry points. Cache keys include the dialect to prevent
cross-mode reuse.

## CI and exact baseline

The `cypher-tck` job downloads and verifies the pinned corpus, tests the Rust
module, executes the full suite and uploads `report.json` and `report.md`.
A green gate requires **every scenario to execute and pass**, no infrastructure
errors, and exact equality with the reviewed scenario hashes and outcome hashes in
`baseline.json`. The pin also records the full scenario count and identity digest;
missing, duplicate, added or changed scenarios and changed outcomes fail.

Failure and unsupported categories remain visible in reports, but cannot be
accepted by recording a new baseline. `--record-baseline` refuses to write unless
all scenarios actually passed and infrastructure checks succeeded. After inspecting
and independently auditing a complete passing replay, record exact outcomes with:

```sh
target/release/graphite-tck --probe target/release/tck_probe --output <fresh-output> --record-baseline
```

CI never records baselines. Review the exact baseline diff with the implementation
and official expectations. Do not edit upstream scenarios or weaken comparisons to
fit Graphite behavior.
