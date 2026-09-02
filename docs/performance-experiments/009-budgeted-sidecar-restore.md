# Budgeted CallSite sidecar restore

## Hypothesis

Restoring a persisted CallSite string index must charge the request's graph-work budget while bytes
are read, checksummed, and structurally validated. Validation should happen in the streamed read so
it does not require a second traversal, and cancellation or a consumer failure must release the
retained-memory reservation without publishing a partial index. Split-worker accounting must also
reach zero before a completed future is observable so the worker bound can be measured reliably.

## Evidence

- Dataset: the 64 persisted graph shards regenerated from the pinned fixture JARs, containing
  5,046,935 CallSites. All 64 v2 sidecars remained admissible after the change.
- Base revision: `e48a532befc3cf83d20501b7459f400e22c53fc1`.
- Candidate: this experiment commit.
- Correctness: targeted tests verify that restore work is charged, consumer failure at the final EOF
  check is propagated, interrupted restore preserves the interrupt/cancellation outcome, memory is
  released, and no partial index is published. A repeated split-task test verifies that active-worker
  accounting is zero as soon as execution returns.
- Validation: targeted `GraphStoreTest` cases and `:webgraph:detekt` passed; `git diff --check` was
  clean.
- Latency: not separately benchmarked because this change closes hidden budget and cancellation work;
  it does not claim a query-latency improvement.
- CPU, heap, and RSS: no standalone delta was collected. Streamed validation replaces the former
  post-read validation traversal and retains the same persisted arrays; the tests independently
  verify reservation cleanup on failure.

## Decision

Kept as a correctness and observability prerequisite. Persisted restore can no longer perform
unbudgeted validation work or swallow cancellation, and worker-peak diagnostics are deterministic.
Selected-tuple anchor grouping and adaptive raw-prefix probing are independent experiments and are
not part of this commit.
