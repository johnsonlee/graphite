# Existing dense DISTINCT: selected tuple feasibility

This is an independent reference probe on the frozen real fixture64 export,
not an optimization or performance benchmark. It targets the old workload's
`global-wide-wrapped-case-insensitive-distinct-dense` query (`get` across the four
lowercased caller/callee properties, DISTINCT original values, LIMIT 200).

| Graphs | Tuples whose values independently exist in all corresponding properties | Tuples actually present |
| --- | ---: | ---: |
| fixture-android-00 | 200 | 200 |
| fixture-tika-00 | 11 | 11 |
| Other 62 graphs | 0 | 0 |

The complete predicate matches 1,489,740 CallSites across all 64 graphs. It is
only the provenance of the first 200 selected tuples that can exclude 62 graphs.
Those excluded graphs contain 1,448,486 full-predicate matches, so confusing the
full predicate hit set with the selected-result provenance would be incorrect.

All 200 selected tuples first occur in android-00. Reconstructed response bytes
(85,721) and digest
`1e662cf0935ee94c21b8eb0c4f6e3b4257d74ab515ccb1e7e31419b17919a9e7`
match all three existing CPU-profile TSVs. The independent export contains
5,046,935 real CallSites; hashes and all 64 graph counts are retained here.
Separate property membership is only a necessary condition: a tuple whose four
values exist separately must still be checked on the same node. This input has
no such false positives, but an implementation must not assume that generally.

The current storage path computes `exactMatchingStringIds` before doing the
selected-tuple feasibility checks inside `parallelRawDistinctCallSiteStringProjection`.
Moving a proved-empty selected-tuple check ahead of predicate candidate discovery
is therefore a concrete hypothesis to measure. The existing index validation,
work accounting, null/projection semantics and full source provenance must remain.
This does not introduce a tuple index, choose a shortest posting, or repeat the
rejected Attempt 133 selected-tuple lookup implementation.

In the three existing dense-query CPU recordings, application thread samples are
105 / 136 / 178. Mutually exclusive stack sets for predicate string candidate
discovery are 45 / 60 / 82, and raw DISTINCT projection are 54 / 69 / 90. Combined,
these account for 94.3% / 94.9% / 96.6% of application samples. Compiler threads
also have 90 / 113 / 148 samples; this is concurrent activity, not measured query
blocking. The data does not identify thread scheduling as the dominant cost.

No implementation or speedup is claimed. `probe.py` and `check-harness-digest.py`
are archived exact evidence scripts with their original machine paths. The
receipt records source/input hashes, and `per-graph.tsv` is the readable complete
census. This probe is not a CI acceptance check.
