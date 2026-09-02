# Performance experiment records

Every performance hypothesis gets one record and one commit. Records use real persisted graph data;
synthetic graphs remain limited to correctness and deterministic execution-path tests.

Each record states:

1. the hypothesis and code path;
2. the exact base and candidate evidence;
3. correctness, latency, CPU, heap, and RSS outcomes when available;
4. whether the implementation was kept or reverted; and
5. the next incremental target.

A rejected experiment remains documented, but its rejected production code must be absent from the
same commit. This keeps the investigation auditable without accumulating speculative code.
