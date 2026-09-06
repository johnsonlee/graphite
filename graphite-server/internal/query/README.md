# Native Cypher execution

The server parses and executes Cypher entirely in Go. Java is used only to
regenerate the parser and independent correctness oracles; it is never a query
backend. Full main compatibility remains work in progress.

## Relationship and path execution

`MATCH` and `OPTIONAL MATCH` support single-hop and variable-length relationship
chains, incoming/outgoing/undirected traversal, alternative relationship types,
inline relationship constraints, relationship variables, and named paths.
Variable-length traversal follows main's DFS pre-order and prohibits repeated
relationships within a trail while permitting repeated nodes. Relationship
reservation/reuse is scoped to one MATCH clause. Single-hop undirected self-loops
appear twice; variable-length undirected self-loops appear once, matching main.

The matcher preserves existing bindings, evaluates inline constraints before
binding newly introduced variables, and null-fills only new OPTIONAL variables
when either the pattern or its WHERE predicate fails. Cross-graph joins execute
in one pipeline; each relationship remains within its own store. Node, edge and
path bindings retain graph provenance through projection and aggregation.

Relationship/path property access, `type`, `nodes`, `relationships`, `size`,
`length`, sorting, DISTINCT and aggregation keys are implemented. Scoped paths
materialize as alternating nodes and edges; cross-graph paths retain their graph
namespace and separate node/relationship lists. Scoped control-flow comparison
objects and call flags deliberately differ from qualified edge output, as in main.

The matcher uses an explicit traversal stack and checks the query context during
node enumeration, edge expansion, binding inspection and output materialization.
It currently materializes intermediate match rows eagerly and does not implement
main's lazy LIMIT pushdown or other planner optimizations. No traversal performance
claim has been made.

## Independent correctness evidence

`traversal_test.go` checks every column and every ordered row against 62 outputs
captured from unmodified remote main `4e328b0109e13c896b74004823fb049fcb19251a`.
The persisted fixture contains eight nodes and fifteen edges covering every edge
family. It is exclusively a correctness fixture, never a performance workload.
A separate test cancels inside traversal after parsing and node lookup.

To recapture using a jar built from that exact revision:

```sh
python3 internal/query/testdata/regenerate-traversal.py /path/to/graphite-explore.jar
go test -race ./internal/query ./internal/cypher/...
go vet ./internal/query ./internal/cypher/...
```

`TraversalOracle.java` also accepts a trailing `generate` argument to recreate the
fixture. The regeneration script invokes local JVM classes directly and never
sends HTTP requests or runs benchmarks.

## Functions, aggregation and regex

The complete main function dispatch table is implemented natively: 59 scalar
names (including aliases) and 10 aggregates. See [FUNCTIONS.md](FUNCTIONS.md) for
the matrix, 369 JVM query oracle cases, exact runtime quirks and limitations.
Object string rendering, property-map order, Java 17 Unicode behavior, UTF-16
boundaries and regex are included. The native mathematical and regex packages
have additional independent JVM corpora.

Procedure/planner shapes and the full server compatibility matrix remain separate
work. Intermediate matching is still eager. These correctness fixtures establish
neither complete server parity nor the required performance improvement.
