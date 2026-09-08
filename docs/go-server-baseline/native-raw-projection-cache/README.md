# Native raw projection cache parity

This change reproduces the pinned main raw projection match cache. It stores graph-owned copies of matching node IDs under ordered predicate and limit keys, excludes projected columns, reprojects on hits, keeps at most 16 entries in access order, and does not replace or promote duplicate puts. Only complete probes publish, including exhausted empty results. Retained-index release preserves raw matches; explicit index clear and graph close discard them. Store reads and publication honor cancellation and closed ownership.

The replay observer now exposes rawProjectionCount and rawMatchCount alongside the five existing structural fields. The raw-match counter remains zero because the original persistent RawStringMatchStates.stateFor has no production caller; the source inventory and unchanged mapped-graph bytecode are audited under main-oracle. This does not claim the separate per-query bounded matcher has been implemented.

The independent actual-JVM oracle passes 20 primitive assertions and 18 provider assertions. Its seven successful/incomplete provider observations are copied without alteration into internal/query/testdata/raw-projection-cache/main.json. The Go provider test verifies complete row values, projection changes, exact cached node IDs, empty publication, incomplete probe refusal, cancellation without publication, retained release and explicit clear. Store tests add input/output ownership, duplicate-put eviction order, capacity, empty-versus-missing, close, cancellation, and concurrent close. Key tests cover property, predicate ordering, transformation, operator, expected string, limit, projection exclusion, and Java UTF16 identity. Existing lifecycle, release, mapped-range and annotation-history tests now compare all seven main state fields without discarding raw counters.

Validation from graphite-server:

- go test ./internal/query ./internal/store ./cmd/graphite-benchmark-replay: exit 0.
- go test ./internal/query -run TestRawProjection -count=1: exit 0.
- go test -race ./...: exit 0.
- go vet ./...: exit 0.

An isolated negative control skips only raw cache publication while retaining query matching and projection. The actual-main provider test rejects its first full-hit state (zero entries instead of one); negative-control.json records the expected failure and verifies root source was unchanged.

Commands and terminal receipts are in test-receipts.json; complete module output is retained. All provider fixtures are synthetic correctness controls, never performance datasets. The initial targeted run exposed an old annotation comparator that removed main raw fields; the comparator was updated to compare those fields instead, and the full race suite passed afterward.

The source-backed follow-up audit in finite-work-next.md confirms the production main server ignores its deprecated finite-budget option and uses Long.MAX_VALUE; a new finite production default would be incompatible. Core budget/context APIs and diagnostics still need their original semantics.

Known remaining differences include graph-work accounting, cached-hit work charging, the per-query 4096-slot shared BoundedStringMatcher, and full-node-ID list copying. Main's work values in the provider oracle are preserved but are not asserted as implemented in Go. Full server fidelity, formal warm benchmark completion, per-case latency measurements, and the 10x P95 objective remain unproven. No timing or allocation improvement is claimed by this correctness change. Real64 routing and state evidence is recorded separately in ../native64-raw-projection-cache.
