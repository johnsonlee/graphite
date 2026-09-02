# Selected-tuple anchor grouping

## Hypothesis

Resolve repeated selected values and posting ranges once per graph, group selected four-property
tuples by their smallest exact posting, and scan each shared anchor only once. This should reduce
duplicate string-table lookups and posting traversal during cross-graph `RETURN DISTINCT`
provenance checks.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: `global-wide-wrapped-case-insensitive-distinct-dense`, cold indexes, `LIMIT 200`,
  `-Xmx8g`; oracle `/tmp/pr113-quick-candidate/oracle.correctness`.
- Base lineage: PR head `882fb90`; reference observation
  `/tmp/pr113-quick-candidate/anchor.tsv`.
- Candidate: the uncommitted anchor-grouping snapshot recorded in
  `/tmp/pr113-quick-candidate/candidate-final1.tsv` through `candidate-final4.tsv`.
- Correctness: candidate outputs matched the real-data oracle; focused null, encounter-order,
  predicate, limit, failure, and shared-anchor tests passed.
- Latency: the 50.916 ms reference became 39.012-50.893 ms across four quick observations. The
  variance was too large to establish a repeatable material improvement, and the slow observation
  was effectively unchanged.
- Graph work: 153,786 -> 154,058 units.
- CPU, heap, and RSS: no retained-memory change was expected, but no stable CPU or memory reduction
  was observed in the real-data run.

## Decision

Reverted. Grouping preserved correctness but did not produce a dependable latency reduction and
slightly increased measured work. The production change and its synthetic behavior tests are absent
from this docs-only commit.
