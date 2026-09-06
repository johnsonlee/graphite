# Node encounter-order investigation

This is a correctness investigation against main `4e328b0109e13c896b74004823fb049fcb19251a`, not a performance experiment or a production fix. Native observations use `f85418822fbc01e902e08514b4e25aea2a517ef2`. Attempt 5 and its worktree are unchanged.

## Sources and fixtures

`GenerateEncounter.java` creates nine nodes with sparse IDs using the actual main GraphStore writer. A Graph proxy supplies an explicit interleaved `nodes(Node.class)` sequence: `[90,2,17,41,4,77,22,6,103]`. Types repeat IntConstant, StringConstant, CallSiteNode. This yields serialized record and legacy nodeindex order equal to that sequence, with separate main-written type-index sequences. `all-kinds` is a copy of the existing 16-kind `store/testdata/jvm-v3` correctness fixture, whose IDs are `[0,2,...,30]`.

`EncounterOracle.java` records each Graph.nodes source, Class identity hashes, 14 full queries in scoped and cross modes, and the real Explorer subgraph builder result. Queries cover untyped/Node/Constant/concrete labels, WHERE, LIMIT 5, UNWIND/MATCH, OPTIONAL MATCH, collect/UNWIND, explicit ORDER BY, and direct string predicates. Cross queries use sources `[b,a]` and preserve full provenance. Each fixture runs in EAGER and MAPPED modes, under three Class identity-hash initialization orders, for 12 separate JVM launches and 336 complete query observations. No LIMIT 0 query is included.

The original oracle uses Java 17.0.18+0 Homebrew ARM64, UTF-8 default charset. Jar SHA-256: `91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`. Regenerate with `python3 regenerate.py /path/to/graphite-explore.jar`. HashMap identity-key ordering is not promised to regenerate identically. The explicit EAGER type order, concrete-type sequences, ORDER BY outputs, and graph membership are deterministic.

## Findings

| Source | Main behavior | Native baseline |
| --- | --- | --- |
| EAGER Node or supertype | First encountered concrete-type order, then persisted order within each type | Raw serialized order, which can interleave types |
| MAPPED concrete type | Main-written type-index order within that type | Filtering NodeIDs preserves matching order on these fixtures |
| MAPPED Node or supertype | HashMap<Class,Range> bucket order, then each type-index range | Raw nodeindex order |
| Method | Metadata descriptor iteration; not part of node source | Existing MethodList path, unaffected |
| Cross graph | Source selection order first, then each graph's local candidate order | Same source order; inherits local mismatch |
| C4 source | `graph.nodes(CallSiteNode.class)` | `NodesOfKind("CallSiteNode")`; matching concrete-type order |
| Explorer node | `/node/{id}` performs direct ID lookup | Direct Store.Node lookup |
| Explorer subgraph `nodes` | DFS: center, then outgoing targets, then incoming sources, subject to visited/depth/limits | Adjacency traversal; no all-node enumeration |

Pinned main has no general REST `/nodes` enumeration route. Neither direct node lookup nor the subgraph builder sorts or traverses the all-node type map. The captured subgraph outputs are equal across load/prehash modes, including the connected all-kinds fixture. C4 may subsequently sort its derived results, but its raw callsite source is a concrete-type sequence.

EAGER mixed-sparse traversal is `[90,41,22,2,4,6,17,77,103]`: type groups Int, String, CallSite. The three JVM variants agree. Native returns `[90,2,17,41,4,77,22,6,103]`, yielding 16 of 28 differing scoped/cross query outputs.

MAPPED all-kinds traversal is not uniquely determined by persisted bytes. With the same fixture and JVM, allocating Class identity hashes in forward versus reverse order changes the all-node result to:

- Forward: `[20,4,12,22,16,28,18,0,30,6,2,26,10,8,14,24]`
- Reverse: `[10,18,26,8,14,2,12,30,0,24,28,4,20,22,16,6]`

Default initialization gives a third sequence. These differences reach complete MATCH, LIMIT, UNWIND, OPTIONAL, collect, and cross-graph query outputs. They are not a reason to hardcode any observed type sequence in Go. Exact MAPPED supertype order cannot be reconstructed from a graph directory alone without the original JVM's Class identity state.

The investigation also found a separate membership defect: `ResourceValueNode` implements main `ConstantNode`. Native's Constant label test excludes it. The all-kinds EAGER difference is exactly its missing ID 28 in scoped and cross Constant queries. That requires a separate explicit label fix; it must not be disguised as ordering work.

## Minimal deterministic correction proposed

Add a query-specific Store encounter accessor without changing NodeIDs or NodesOfKind. For EAGER, construct the sequence by preserving first concrete-type encounter and concatenating existing per-kind IDs. Have query.walkNodeCandidates use it. Existing bound variables, Methods, property evaluation, provenance, and materialization stay on their existing paths. This corrects the demonstrable EAGER source-order defect without changing REST/C4/index-reader consumers.

Keep the MAPPED supertype identity-order limitation explicit. No fixed Java Class-hash sequence, node-ID sorting, or offset sorting can honestly claim exact reproduction. Concrete type sources retain their persisted order. A broader policy for cross-runtime queries without ORDER BY needs an explicit decision, not an invented ordering approximation.

## Native comparison and reproducibility

`native-probe_test.go.txt` is the exact temporary native probe. To regenerate native observations, copy it to `internal/query/encounter_probe_test.go`, run `go test ./internal/query -run TestEncounterProbe -count=1`, then remove that temporary test. It records all 112 complete native query outputs; `native-differences.json` retains full main/native values, not just counts. Default JVM comparisons have 2/28 differences for all-kinds EAGER, 16/28 for all-kinds MAPPED, and 16/28 each for mixed-sparse EAGER/MAPPED. No production file was modified in this investigation.
