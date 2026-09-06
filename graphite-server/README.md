# Native Go Graphite server

Work in progress toward a complete native Go replacement for `graphite serve`,
including the Cypher engine. The JVM is an independent correctness and performance
oracle, never a runtime query backend.

```sh
cd graphite-server
go build -o /tmp/graphite-server ./cmd/graphite-server
/tmp/graphite-server serve --data /data/graphs --graph orders:orders-graph --port 8080
go test -race ./...
go vet ./...
```

The parser is generated from the existing repository ANTLR grammars. See
`internal/cypher/generate.sh` for pinned reproducible generation. Generation uses
Java; the compiled server, lexer, parser and executor run natively in Go.

Implemented foundations include native persisted graph decoding; MAPPED, EAGER
and AUTO node loading; transactional graph replacement with reference-counted
readers; request concurrency, timeout and socket cancellation; scoped node and
edge access, subgraphs, annotations, resources, class overview and endpoint discovery; embedded byte-identical Explorer frontend; and native single/cross-graph
Cypher pipelines with an explicit fanout API; relationship/path traversal; transactional topology rebuilding; full C4 inference and JSON/DSL/Mermaid/PlantUML output; OpenAPI aliases; and optional query/HTTP/Go runtime metrics. These components are still subject
to the complete compatibility matrix in [go-server-parity.md](../docs/go-server-parity.md).

## Required acceptance

- Complete the full server and native Cypher behavior matrix, including error,
  ordering, cancellation, wire-format and persisted-storage semantics.
- Benchmark the complete **64 real persisted graphs** against pinned remote main
  `4e328b0109e13c896b74004823fb049fcb19251a` on the same machine, queries and concurrency.
- Verify complete query responses before and during timing; a failed or timed-out
  query cannot count as a faster result. A single-graph test is correctness
  diagnostics only, never the performance acceptance workload.
- Prove candidate P95 is at most one tenth of main P95. Preserve raw samples,
  fixture/artifact identities, CPU, RSS, allocation and GC evidence, and report
  cold/warm and concurrency strata separately.

The goal is **not achieved**. All 59 scalar function names and 10 aggregates are
implemented, with 369 main query oracles; native Java-compatible regex and the
Java17 ARM64 math target have separate 4,172- and 30,320-case suites. Finite
coverage does not prove full equivalence. Remaining work includes planner/cache
behavior, broader malformed input and persisted-storage variants, and additional
JVM string/value/error combinations. See the
[query](internal/query/README.md) and [C4](internal/analysis/c4/README.md) scope
records for precise limitations. Graph strings/metadata/adjacency are currently
heap-backed even in MAPPED mode. Node data and lazily opened optional CallSite
string indexes are memory mapped. The index reader passes complete CRC/CSR
validation on all 64 real graphs, but query candidate integration is still
pending. There is no validated 10× speedup.

`--topology` accepts a query file or a directory of `.cypher` files. Derived
relations rebuild transactionally on graph replacement/unload; invalid rules or
references roll the catalog and topology back together. Snapshots are immutable
Go objects; main's private memory-mapped topology persistence is not replicated.

`--metrics` enables `/metrics`. Application `graphite_cypher_*` series preserve
main's outcome tags and SLO bucket identities. Native HTTP durations use
`graphite_http_request_duration_seconds` with bounded method/route/status labels.
Go allocation/GC/goroutine series use `go_*` names; JVM/Jetty-specific metrics
are replaced by native measurements. This runtime-specific naming is an explicit
observability difference. GC pause counts use cumulative bucket counters because
Go does not provide their exact sum. Release artifacts may set their version with
`-ldflags '-X main.version=...'`; unversioned binaries report `unknown`.

## Evidence and reproduction

- [Store format and JVM parity](internal/store/README.md)
- [Relationship/path differential oracle](internal/query/README.md)
- [C4 inference and renderer goldens](internal/analysis/c4/README.md)
- [Main HTTP oracle](../docs/go-server-baseline/README.md)
- [HTTP differential and mandatory 64-graph benchmark scripts](scripts/README.md)
- [Chronological rewrite/performance record](../docs/go-server-optimization-attempts.md)

The `internal/store/testdata` JVM-generated tiny fixtures are used for correctness
tests only. Performance scripts require the complete real corpus and provenance.
