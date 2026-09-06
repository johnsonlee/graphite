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
Cypher pipelines with an explicit fanout API. These components are still subject
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

The goal is **not achieved**. Unsupported work still includes relationship/path
execution in Cypher, Java Pattern semantics, numerous functions/planner shapes,
topology derivation, C4 inference/renderers, endpoint discovery, overview, OpenAPI and opt-in metrics. Some edge-case value semantics remain incomplete.
Graph strings/metadata/adjacency are currently heap-backed even in MAPPED mode;
only node data is memory mapped. There is no validated 10× speedup.

## Evidence and reproduction

- [Store format and JVM parity](internal/store/README.md)
- [Main HTTP oracle](../docs/go-server-baseline/README.md)
- [HTTP differential and mandatory 64-graph benchmark scripts](scripts/README.md)
- [Chronological rewrite/performance record](../docs/go-server-optimization-attempts.md)

The `internal/store/testdata` JVM-generated tiny fixtures are used for correctness
tests only. Performance scripts require the complete real corpus and provenance.
