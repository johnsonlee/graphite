# Small selected-tuple anchor lookup

## Hypothesis

The DISTINCT provenance phase should not build a graph-wide exact projection-tuple hash table to
answer at most 200 selected tuples. On a cold 64-graph query, that policy scans and hashes millions
of CallSites before the first lookup. Reuse an already-built exact index, but for a cold index use
the existing exact property-posting anchor until the selected set reaches 256 tuples; only larger
sets amortize the graph-wide build.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: complete 34-case `global-wide`, cold indexes, `LIMIT 200`, `-Xmx8g`.
- Base revision: `c64c3be` (ordered DISTINCT leading window).
- Candidate: this experiment commit.
- Protocol: three paired JVM forks in alternating candidate/base order under
  `/tmp/pr113-exp013`.
- Correctness: all 34 outcomes, row counts, response sizes, and result digests matched in every
  pair. Tests cover the small-set anchor path, the >=256 tuple exact-index path, exact predicate
  rechecks, encounter order, collision handling, and index budget/cancellation behavior.
- Aggregate P95: `189.997 -> 73.104 ms` (2.60x), `183.585 -> 58.024 ms` (3.16x), and
  `183.360 -> 70.556 ms` (2.60x).
- Wrapped case-insensitive DISTINCT dense latency: `189.997 -> 27.766 ms` (6.84x),
  `183.585 -> 26.073 ms` (7.04x), and `183.360 -> 26.508 ms` (6.92x).
- Wrapped DISTINCT dense work: `5,070,631 -> 177,117` units in every pair (-96.5%).
- Total process CPU delta: `-21.5%`, `-16.3%`, and `-24.5%`.
- Peak used heap delta: `-10.4%`, `-6.6%`, and `-11.7%`; peak RSS delta: `-18.9%`,
  `-15.5%`, and `-20.3%`.
- Validation: focused exact-index tests, full `:webgraph:test`, and `:webgraph:detekt` passed in an
  isolated clone; `git diff --check` was clean.

## Decision

Kept. This removes a cold-query index build whose cost cannot be amortized by the production
`LIMIT 200` provenance set, while retaining the exact index for larger sets and for subsequent
lookups once already built. The aggregate P95 bottleneck moves to the non-DISTINCT four-property
targeted case, which remains the next independent experiment toward cumulative 10x.
