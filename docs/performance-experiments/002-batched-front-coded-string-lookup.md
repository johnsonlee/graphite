# Batched front-coded string lookup

## Hypothesis

Resolve the selected projection strings in batches, sharing bounds while searching the persisted,
sorted front-coded string table. Resolve properties progressively so a missing earlier property
still eliminates a tuple before later lookups.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g`.
- Reference implementation: selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: 34/34 oracle records passed; zero failures and zero timeouts.
- Wrapped case-insensitive DISTINCT dense latency: 50.916 ms -> 46.522 ms (1.09x).
- Graph work remained 153,786 units.

## Decision

Reverted. Shared binary-search bounds helped, but not enough to justify another lookup path. Later
profiling confirmed that repeated tuple-to-string-ID resolution is important, but this batching
strategy removes too little of its random front-coded decoding cost.
