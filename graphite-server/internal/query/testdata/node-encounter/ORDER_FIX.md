# Deterministic EAGER ordering correction

The independent audit is preserved in README.md, the 12 JVM observations, original native observations/differences, and provenance.json. This correction builds on native `f85418822fbc01e902e08514b4e25aea2a517ef2`.

Only `store/query_order.go` (new) and one accessor call in `query/traversal.go` change production behavior. QueryNodeIDs groups EAGER candidates by first persisted concrete-kind encounter, preserving each kind's existing record sequence. It returns a fresh slice. Existing NodeIDs and NodesOfKind contracts remain untouched. MAPPED returns the original NodeIDs sequence, with the JVM identity-order limitation explicitly documented. Method and already-bound-variable paths do not call the new accessor.

`query_order_test.go` verifies 54 complete independent main query results, including typed scans, untyped scans, WHERE, LIMIT, UNWIND, OPTIONAL, collect, ORDER BY, and cross-source order/provenance. It separately checks the concrete accessor sequences for both modes and confirms that mutating the returned query slice cannot mutate Store state. The sparse interleaved fixture makes the old source fail visibly rather than merely testing counts.

`derive-eager.py` selects these unmodified complete results from the formal audit. The all-kinds Constant query's two scoped/cross cases are excluded because their distinct ResourceValueNode membership defect is not an ordering issue. Their full original discrepancy remains recorded; they are handled in a separate change.

The full native module passes `go test -race ./...` and `go vet ./...`. No performance measurement was run. Integration with the independent callsite index reader touches no common file: store.go, index offsets, reader APIs, REST, and C4 source accessors are unchanged.
