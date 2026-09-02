# Persisted property fingerprint index

## Hypothesis

Persist a primitive `(stable hash, string ID)` index for each CallSite string property. Exact string
comparison resolves hash collisions, while provenance avoids repeated binary searches and random
decoding in `FrontCodedStringList`.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs; all 64 v3 sidecars
  were rebuilt before measurement.
- Workload: complete 34-case `global-wide`, cold index state, `-Xmx8g` with a 6 GiB explicit index
  budget for the admission check.
- Reference implementation: selected-tuple anchor baseline based on PR head `882fb90`.
- Correctness: 34/34 oracle records passed; zero failures and zero timeouts; hash-collision, case,
  missing-value, work-denial, and cancellation tests passed.
- Admission: 64/64 graphs.
- Wrapped case-insensitive DISTINCT dense latency: 50.916 ms -> 40.712 ms (1.25x).
- Peak heap: 4.443 GiB -> 4.677 GiB (+5.3%).

## Decision

Reverted. This was a measurable incremental latency gain and stayed inside the memory envelope, but
the retained index and sidecar-format complexity were disproportionate to 1.25x. The experiment
also showed that lookup acceleration alone cannot remove the first-pass cost.
