# Real64 ordinary mapped-range verification

The final cold replay matches all 1,267 original main case outcomes: 1,266 full
canonical results, including numeric types and row order, and the original
`four-or-graph-id-targeted` exception. All five available structural state fields
match at graph load, after invocation preparation, and before/after every case:
retained, mappedView, trigrams, loadedFromPersistence, and mappedRangeCount.
There are 162,304 graph-state observations, each with five fields and graph ID.

The first candidate repaired ordinary selected-range consumption but retained
19,960 mapped-range differences. The first remaining difference appeared after
case index 4: DISTINCT preparation replaced an existing mapped view and discarded
its earlier range certificates. The final candidate preserves that view, including
its use by later ordinary queries, and honors an empty lookup already proven by
the initialized view. Original candidate data and failures remain preserved.

`run-native.py` and `run-native-final.py` each build a separate binary and use a
fresh writable clone of the frozen 64 real graphs. All 1,152 graph files were
authenticated before each run and unchanged afterward, with no graph-local
files added. Source and binary hashes remained unchanged during both runs.
All recorded processes are terminal. The pinned main comparison is the complete
classpath replay in `../native64-fullcase-replay/`, whose original correctness
manifest digests were independently verified.

Run `python3 verify-final.py` to compare the complete public results and all
available structural observations. Captures are archived as byte-preserving
gzip files; `capture-archives.json` stores original and compressed hashes.
The initial candidate remains in `native-cold/`; the verified candidate is in
`native-cold-final/`. Synthetic corruption/lifecycle/annotation controls and full
module race/vet logs are in `../native-ordinary-mapped-ranges/`.

This is correctness evidence, not latency evidence. The original main all-success
gate still fails on its query error. Native raw-match and raw-projection counters
remain unavailable; the original main values are preserved. Full warm and
startup-prepared validation, finite work accounting, and per-case P95 acceptance
are outstanding. No 10x speedup or complete server parity is claimed here.
