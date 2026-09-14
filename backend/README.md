# Graphite Explorer (Rust)

A Rust reimplementation of `graphite-explore`. It reads the same on-disk graph format
and serves the same HTTP API, so it is a drop-in replacement for the Kotlin server.

`graphite` (the `query` subcommand) matches the Kotlin CLI on all 148 byte-level checks
of stdout, stderr and exit code; `build` and `serve` are not ported.

The server matches the Kotlin server on all 150 differential checks — every method and path the OpenAPI document declares, which the suite enforces as a checklist, and on the 64-graph gate
corpus all 34 query results are byte-identical. Both counts have a boundary — the
`graphite` CLI is not ported, and several routes and levels are untested — set out in
[What the parity suite covers, and what it does not](../docs/rust-explorer-parity-and-latency.md#what-the-parity-suite-covers-and-what-it-does-not).

Against the repository's real baseline — 64 graphs, 19.4M nodes, cold, P95 taken across
queries — **P95 is 16.7x lower than the Kotlin server** (10.9x pairing Kotlin's best
repetition against Rust's worst), and P50 is 5.3x lower.

That number rests almost entirely on one query shape: Kotlin's P95 *is*
`global-wide-wrapped-case-insensitive-distinct`, the only shape where it exceeds 30 ms.
Neutralise it and the gap is about 3x, of which roughly 1–2x is language-level. Rust's
edge there is a hand-written fast path, not a property of the language. See
[docs/rust-explorer-fixture64-p95.md](../docs/rust-explorer-fixture64-p95.md) for the
method, the per-query numbers and an honest attribution, and
[docs/rust-explorer-parity-and-latency.md](../docs/rust-explorer-parity-and-latency.md)
for the separate single-graph comparison.

## Crates

| Crate | Directory | Purpose |
|-------|-----------|---------|
| `graphite-storage` | `storage/` | Reader for the persisted format: BVGraph adjacency, front-coded string dictionary, node records, metadata, class overview, resources |
| `graphite-cypher` | `cypher/` | Cypher parser, evaluator, and query pipeline over that storage |
| `graphite-explore` | `explore/` | HTTP server exposing the Explorer API |

The `graphite` CLI lives outside this directory, in `cli/` at the repository root; the Cargo
workspace (`Cargo.toml` at the root) spans `backend/*` and `cli`. `graphite serve` and the
`graphite-explore` binary run the same `serve` module of this crate; `explore/Dockerfile`
packages the binary for the `graphite-explore` container image.

## Build and run

```bash
# from the repository root
cargo build --release
./target/release/graphite-explore --id app /path/to/graph --port 8080
```

`--release` is not optional for any measurement. A plain `cargo build` produces an
unoptimized binary under `target/debug/` that runs the 64-graph backtest at P50 6.7 ms
and P95 42 ms, against 1.0 ms and 3.9 ms for the release build — no faster than the
Kotlin server. The debug binary prints a warning at startup for that reason.

The command-line interface mirrors `graphite serve`: `--data`, `--graph id:path`,
`--id`, `--port`, `--load-mode`, `--topology`, `--max-concurrent-cypher`,
`--cypher-max-timeout-ms` and `--metrics`.

## Testing

```bash
cargo test                              # unit tests
cd bench && python3 parity.py           # 150 differential tests against the Kotlin server
cd bench && python3 parity-cli.py       # 148 byte-level tests of `graphite query`
cd bench && python3 bench.py            # latency comparison
```

`parity.py` and `bench.py` both expect a Kotlin server on port 18081 and a Rust server
on port 18080, serving the same graph under the id `app`.
