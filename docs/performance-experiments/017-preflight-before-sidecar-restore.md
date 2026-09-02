# Preflight before sidecar restore

## Hypothesis

Run the existing long-`CONTAINS` string-table preflight before restoring a persisted CallSite
sidecar. An impossible term should be rejected from the much smaller string dictionary without
loading and traversing every graph's complete CallSite index.

## Evidence

- Dataset: 64 persisted graph shards regenerated from the four pinned fixture JARs.
- Workload: the complete 34-case `global-wide` run, including the four-property zero-result case,
  cold indexes, `LIMIT 200`, and `-Xmx8g`.
- Base revision: `21feea1`, with the original sidecar-first ordering.
- Candidate: the preflight-first snapshot initially created in this commit.
- Correctness: all 34 candidate records matched the base-generated real-fixture oracle; the focused
  persisted-index test and WebGraph detekt passed.
- Base aggregate P50 / P95: 1.698 ms / 28.123 ms.
- Candidate aggregate P50 / P95: 102.966 ms / 210.746 ms.
- The zero-result P95 improved from 332.754 ms to 210.746 ms, but long targeted terms also paid a
  string-table scan on every graph before their sidecars could be restored; targeted P95 regressed
  from 22.220 ms to 216.395 ms.
- Process CPU time regressed from 3.883 s to 11.963 s. Peak used heap was effectively unchanged
  (4.11 GiB to 4.10 GiB), as was peak RSS (4.38 GiB to 4.38 GiB).
- Raw measurements are under `/tmp/pr113-exp017-pair.igK5Ew/`; the regenerated fixture is under
  `/tmp/pr113-exp017-fixture.t1A36b/`.

## Decision

Reverted. A dictionary preflight is useful only after a cheap signal establishes that the term is
unlikely to exist. Applying it unconditionally before sidecar restoration trades one zero-result
tail regression for a much larger regression across the normal targeted workload. This docs-only
commit leaves the original production and test behavior intact.
