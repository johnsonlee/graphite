# Independent candidateSlot correctness audit

This audit read Attempt 4 in `/tmp/graphite-go-candidate-slot-4124bfc4` and executed tests only in external module copies. It did not modify the implementation agent's worktree or measure performance.

Two complementary checks were used:

- **4,318 expression comparisons:** 127 expressions × 17 entities (all 16 persisted node kinds and the fixture's virtual Method) × scoped/qualified modes. Each expression was evaluated with the ordinary boxed entity and the borrowed candidate slot. The comparison checks complete values and error class/message. It then mutates the slot and verifies returned containers still hold the original values, and checks retained `rowOrders` for candidate pointers. Whole-entity functions, bad casts, properties, equality, ordering, null/Boolean behavior, CASE/coalesce, lists/maps, list addition, comprehensions and predicates are included.
- **198 complete query comparisons:** 99 queries × scoped/two-graph modes. The control uses the same immutable source snapshot with just the scanner's `walkNodeCandidates(..., slot, ...)` argument changed to `nil`, restoring the existing boxed enumeration path. Complete result columns/rows and error class/message are compared against the candidate. Cases include anonymous/bound/optional matching, earlier bindings, Method, rejected prefixes and interleaving rejections, whole-row collect/string conversion, comprehensions and multi-graph provenance.

The original `delete(bound, variable)` cleanup had **six concrete differences** after normalizing only the graph instance's `MappedWebGraphBackedGraph@hex` string. Rejected candidates removed the variable's insertion position, so a later accepted row could stringify its provenance before the variable. For example, cross-graph `MATCH (n) WHERE n.id>0 WITH collect() AS rows RETURN toString(rows) AS text` changed whole-row member order. Full original/control responses are saved in the JSONL logs and `initial-normalized-differences.json`.

The implementation agent independently identified that defect and changed cleanup to retain the key with a nil value. This audit took a second immutable snapshot; the only production-file difference from its first snapshot was `internal/query/scan.go`. The final snapshot passes **198/198 complete-query comparisons** and **4,318/4,318 expression comparisons**, with the Go race detector enabled. No further concrete transparency counterexample was found within this bounded audit.

The full-query comparison normalizes only `io.johnsonlee.graphite.webgraph.MappedWebGraphBackedGraph@<hex>` instance identity embedded in strings: the independent module copies load fixtures at different paths and therefore have different native graph identity text. It preserves graph IDs, all member order, every other string, values, columns, nulls, errors and row order. Both raw and normalized differences are retained; raw final differences are exactly this expected instance-identity text.

`slot_audit_test.go`, `query_audit_test.go` and `full-query-cases.json` are the standalone test sources and workload. `compare.py` reproduces the full-query comparison from saved Go JSON test output. `snapshot.json` and `final-snapshot.json` contain exact production hashes and external copy paths. `boxed-control.json` identifies the sole control mutation. The final command was:

```sh
SLOT_AUDIT_CASES=<absolute-full-query-cases.json> go test -race -json ./internal/query -run TestExternal -count=1
```

This is independent slot-vs-boxed correctness evidence, not an additional JVM oracle and not proof of complete Cypher parity. The implementation agent separately owns its 40-case pinned-main oracle. Real 64-graph correctness/profile work remains with the root task. These tiny fixtures must not be used for a performance claim.
