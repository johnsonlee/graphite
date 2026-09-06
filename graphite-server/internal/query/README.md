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
Single-node WHERE clauses filter candidates before retaining rows; relationship
and path matches still materialize intermediate rows eagerly. Main's lazy LIMIT
pushdown and other planner optimizations are not implemented. The isolated
64-graph allocation experiment is recorded in `docs/go-server-optimization-attempts.md`;
it is not a P95 or traversal performance acceptance result.

## Independent correctness evidence

`traversal_test.go` checks every column and every ordered row against 62 outputs
captured from unmodified remote main `4e328b0109e13c896b74004823fb049fcb19251a`.
The persisted fixture contains eight nodes and fifteen edges covering every edge
family. It is exclusively a correctness fixture, never a performance workload.
A separate test cancels inside traversal after parsing and node lookup.

Inline node and relationship properties evaluate in source insertion order and
stop at the first mismatch. Duplicate keys retain their first position and use
the final expression, as do ordinary map literals. `property-order-jvm-oracle.json`
captures 34 scoped/cross-graph cases, each repeated 20 times in the independent
main JVM (680 observations), including mismatch/error order, duplicate keys,
OPTIONAL, bound nodes, variable-length relationships and map error order. The
Go tests compare complete columns/rows or exception class/message. Recapture it
with `testdata/regenerate-functions.py`; this is correctness evidence only.

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


## Recent semantic boundaries

EAGER query enumeration groups concrete node kinds in first persisted encounter
order, matching main; existing raw and typed Store accessors remain unchanged.
The [node-order audit](testdata/node-encounter/README.md) includes sparse mixed
fixtures and demonstrates that MAPPED supertype order depends on JVM Class
identity hashes. No single captured MAPPED sequence is hardcoded in Go.
`ResourceValueNode` now participates in `Constant`/`ConstantNode` label matches,
with a separate [26-query main oracle](testdata/constant-membership.md).

The [literal zero-limit guard](testdata/limit-zero/README.md) reproduces main's
specific initial filtered-MATCH branch. It can skip even throwing WHERE, inline
property and projection expressions, as verified against main. It is not general
lazy LIMIT execution. [General SKIP/LIMIT conversion](testdata/count-conversion/README.md)
now follows main value conversion and evaluates each count against the correct
first projected row. The new corpus matches 312/320 main results, and all 180
prior zero-limit results now match. The eight remaining discrepancies concern
early LIMIT evaluation and lazy MATCH; positive-limit early stopping and
relationship materialization remain separate work.

Valid UTF-8 string predicates avoid temporary UTF-16 arrays while malformed or
isolated-surrogate strings use the original Java-compatible path. The frozen
Attempt 5 binary passes complete ordered responses for all 42 HTTP workload
queries on all 64 real graphs. See the chronological optimization record for
source identities and allocation diagnostics; this does not establish a P95 gain.

The optional persistent CallSite string-index reader is implemented separately
in Store. It is not yet used to select query candidates. Its availability does
not certify all core node payloads or permit skipping Annotation matches.
