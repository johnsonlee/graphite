# openCypher TCK integration

Graphite runs the official openCypher Technology Compatibility Kit (TCK) against the
production Rust Cypher executor. This is a correctness suite, not a performance
benchmark. The full pinned scenario inventory is reported, including failures and
scenarios for which the adapter does not yet implement the required contract.

## Query compatibility mode

The TCK probe explicitly selects the public `opencypher-m23` dialect on the same
production executor used by the CLI and server. Reports identify that dialect;
passing these scenarios describes M23 mode. Graphite's default `graphite` mode
preserves existing query extensions, including `toString()` formatting of lists,
maps and graph entities, UNWIND variable rebinding, and hidden input variables in
nonaggregating DISTINCT ORDER BY. M23 mode rejects those nonstandard uses with
native diagnostics. Scalar, temporal and null `toString()` inputs work in both modes.

Choose the mode with `graphite query graph.graphite 'RETURN toString(42)' --dialect
opencypher-m23`, or `Executor::with_dialect(CypherDialect::OpenCypherM23)` in Rust.
All three Cypher HTTP routes accept `dialect=opencypher-m23` as a URL parameter or
`"dialect": "opencypher-m23"` in a JSON request; the JSON field takes precedence.
This includes both cross-graph and fanout queries at `/api/cypher/graphs`. The MCP
`cypher` tool exposes the same optional `dialect` argument. Omission selects
`graphite`; invalid dialects are rejected before execution (HTTP 400).

## Reproduce

```sh
cargo build --release --locked -p graphite-tck --bins
target/release/graphite-tck --probe target/release/tck_probe --output /tmp/graphite-tck-results
```

If Cargo is configured for an explicit target, use `target/<target-triple>/release/`
instead. `cargo test --locked -p graphite-tck` runs the acquisition, expansion,
comparison, infrastructure-failure and production-engine adapter tests.
All TCK-specific implementation lives in the independent top-level `tck/` Cargo
workspace member; no Python environment or Python packages are required.

The runner downloads the official M23 corpus at commit
`007895aff5f33097d67b2e48a0a2babd6bd18590` when the cache is absent.
`tck/upstream.json` pins the archive SHA-256 and every required file digest.
All bytes are verified before atomically publishing the ignored `tck/upstream/`
cache, including LICENSE and NOTICE. Each run verifies an existing cache and fails
on corruption rather than silently repairing it. `--prepare-only` explicitly
prepares this cache without executing scenarios. Upstream source files are not
checked into the repository.

The Rust `gherkin` parser handles the official feature syntax. The runner expands
backgrounds, rules and every Scenario Outline Examples row in source order.
`--inventory-output <path>` exports the complete expanded inventory for auditing.
The pinned initial inventory and outcome hashes were retained during migration;
all expanded scenarios and complete outcomes are compared against the prior
implementation, rather than blessing changed results through a baseline refresh.

## Initial measured inventory

The first integration run over M23 has **3,897 expanded scenarios**:

| Outcome | Scenarios |
|---|---:|
| Passed | 1,143 |
| Failed against official expectations | 1,045 |
| Adapter contract not implemented | 1,709 |

All 2,188 currently executable scenarios were run, including the failures; none
were filtered to produce the passing count. Independent debug and release runs
reproduced the exact checked-in baseline. These counts describe this initial
adapter and product revision; CI reports are authoritative after later changes.

## Meaning of the results

- **passed**: the official expected columns and complete values match. Ordered
  tables preserve row order; unordered tables compare multisets, preserving
  duplicate rows. Nulls, booleans, numbers, lists and maps retain their types.
- **failed**: an executable scenario returns an engine error or differs from the
  official expected result. The report retains the query, parameters, expected
  result and actual result.
- **unsupported**: the adapter cannot faithfully establish the official scenario's
  graph, procedure, error-code or result-value contract. These are unexecuted
  scenarios, never passing scenarios. This does not prove that every such query
  construct is unsupported by the engine itself.

The initial adapter executes empty-graph and graph-independent read scenarios.
Graphite's fixed program-graph schema cannot stand in for arbitrary property graphs
created by TCK setup statements. Named graphs, arbitrary graph construction,
procedures, graph-reference/temporal/spatial values and official error-phase/code
assertions need additional adapters. Native errors are not guessed into TCK codes.

Each request uses a fresh empty immutable executor, with parameters and a bounded
execution deadline. These scalar scenarios do not verify service routing, actual
program call-chain expansion, multi-graph performance or the reported two-level
service-exit problem. Those need their own real-data correctness and stability
reproductions.

## CI and baseline review

The Rust `cypher-tck` job executes the full inventory and uploads `report.json` and
`report.md`, including per-feature counts and every scenario outcome.
`baseline.json` records exact scenario hashes, statuses and outcome hashes.
Scenario additions/removals, passing-case regressions, changed known failures,
changed unsupported contracts and newly passing cases all require review.
Upstream corpus changes also require updating the explicit file inventory.

A green CI gate means the reviewed inventory was reproduced. It does **not** mean
that known failures disappeared, that unsupported scenarios passed, or that
Graphite is fully Cypher conformant. The reports retain all categories separately.

To intentionally update the inventory after inspecting the complete report:

```sh
target/release/graphite-tck --probe target/release/tck_probe --output <fresh-output> --record-baseline
```

CI never uses `--record-baseline`. Review the exact changes to `baseline.json`
alongside the implementation and actual official expected results; do not use
baseline refreshes to hide regressions. Do not rewrite upstream scenarios to fit
Graphite behavior.
