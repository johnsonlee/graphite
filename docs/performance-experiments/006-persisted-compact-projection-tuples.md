# Persisted compact projection-tuple index

## Hypothesis

Persist one `(64-bit tuple hash, earliest node ID)` entry per unique four-property CallSite tuple.
Binary-search selected DISTINCT tuples and use exact four-string comparison for collisions, avoiding
front-coded string-ID resolution during provenance.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Corpus shape: 5,046,935 CallSites and 3,419,019 unique projection tuples.
- Added retained arrays: about 39.13 MiB; total retained index estimate increased 11.09%.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g`.
- Reference implementation: selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: 34/34 oracle records passed; zero failures and zero timeouts; collision, case,
  missing-value, earliest-node, v2 fallback, v3 restore, budget, and cancellation tests passed.
- Admission: 64/64 graphs.
- Wrapped case-insensitive DISTINCT dense latency: 46.059 ms -> 44.711 ms (1.03x).
- Graph work: 153,786 -> 350,214 units.

## Decision

Reverted. The primitive index removed string decoding, but binary probing charged about 13 steps per
selected tuple and increased total work 2.3x for only a 3% latency change. Phase profiling showed the
larger remaining target is the first pass that constructs the initial 200 DISTINCT rows.
