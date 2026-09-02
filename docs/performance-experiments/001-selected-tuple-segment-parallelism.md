# Selected-tuple segment parallelism

## Hypothesis

Split the `RETURN DISTINCT` provenance tuple recheck inside each graph while the Cypher executor
keeps eight graph workers. The shared storage executor preserves the additive 8 graph + 8 storage
worker bound on the 16-CPU test host.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g`.
- Reference implementation: selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: 34/34 oracle records passed; zero failures and zero timeouts.
- Wrapped case-insensitive DISTINCT dense latency: 50.916 ms -> 48.788 ms (1.04x).
- Graph work: 153,786 -> 155,010 units.
- Observed workers: graph peak 8, storage peak 8.

## Decision

Reverted. The worker budget was correct, but the 4% wall-time change was noise-sized and total work
increased. The next milestone must remove work from the provenance path rather than subdivide the
same work further.
