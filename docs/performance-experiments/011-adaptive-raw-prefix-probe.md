# Adaptive raw prefix probe

## Hypothesis

For a serial CallSite query with a small `LIMIT` and a three-character lowercase `CONTAINS` term,
probe at most four times the requested row count directly from storage. Commit the raw result only
when the probe fills the limit; otherwise discard it and continue through the existing persisted
index. Dense leading matches should avoid index startup while sparse and late matches retain the
indexed path.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the pinned fixture JARs.
- Workload: the complete 34-case `global-wide` quick run with cold indexes, `LIMIT 200`, `-Xmx8g`;
  the regression was clearest in `global-wide-four-properties-dense`.
- Base lineage: PR head `882fb90`; observation
  `/private/tmp/pr113-global-wide-raw-base/observations.tsv`.
- Candidate: the uncommitted adaptive raw-prefix snapshot recorded in
  `/private/tmp/pr113-global-wide-raw-candidate/observations.tsv`; later cold/startup variants were
  recorded under `/private/tmp/pr113-global-wide-raw-cold-candidate-*` and
  `/private/tmp/pr113-global-wide-raw-startup-candidate-6da49f1`.
- Correctness: the candidate verified against the base oracle, and focused tests covered serial vs
  parallel/split selection, property choice, case sensitivity, cache reuse, sparse fallback, bounded
  work, and cancellation.
- Four-properties dense latency/work: 9.250 ms and 31,044 units in the paired base observation vs
  55.458 ms and 258,940 units in the candidate. Cold variants remained slower at 47-114 ms vs
  4.10-4.34 ms for the cold base.
- Aggregate single-shot runtime: 0.193 s -> 0.278 s in the paired base/candidate quick runs.
- CPU, heap, and RSS: no compensating reduction was observed; no standalone retained-memory change
  was expected because the probe only buffered at most `LIMIT` rows.

## Decision

Reverted. The bounded probe duplicated storage and index work on the measured workload, regressed
the dense four-property shape, and did not meet the keep threshold. The production change and its
synthetic path test are absent from this docs-only commit.
