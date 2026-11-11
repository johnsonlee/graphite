# Attempt318 review validation

The dense representation now has a real64 matched observation. The six-case
AB pilot compares frozen316 (streaming fallback above64 distinct values) with
318 (317 masks plus the first-element cancellation polling fix). Every request
selects all64 persisted graphs. Four workers consume complete responses;
20 pressure samples per case use nearest-rank P95 (rank19). All276 responses
and the separate six-response correctness smoke independently verify.

Co-use P95 is **41,313.780→2,692.884ms**, an absolute reduction of
**38,620.897ms**. Mixed query CPU is1140.544925→550.634461s; query-process
lifetime peak RSS is36,414,455,808→28,023,980,032bytes. Separate loading CPU
is17.434059→18.162500s (+4.178%), and loading peak RSS
3,978,756,096→3,987,079,168bytes (+0.209%). All four resource comparisons
are within their independent5% bounds. These are workload/process resources,
not per-query CPU/RSS. The20-sample pilot has no repeated-round confidence
estimate and is not formal acceptance or a100-sample confirmation.

[Every-case summary](dense-pilot-summary.md), [independent audit](dense-pilot-audit.json),
[cardinality/source proof](dense-tier-proof.json), and
[smoke correctness audit](dense-smoke-correctness-audit.json) retain the full
results and limits. P50, min/max and raw sample variability remain in the audit.
All46 co-use responses return maximum distinct256. The query bounds the global
dictionary to64 graphs × four caller-length buckets, with physical provenance
at most64; candidate source admission therefore selects three dense high words.
This uses24 extra logical mask bytes per group, before Vec capacity/allocator
overhead; it does not eliminate per-group storage growth. The general dense tier
can use up to64 extra logical bytes per group. This is source-and-result inference,
not runtime instrumentation. The original controller field `tier: Dense` in both
arms means cardinality eligibility;316 actually falls back to streaming. The
final controller clarifies the field without rewriting original reports.

There are still only64 physical graphs. This run does not establish65–320-graph
performance or measure the318 high-provenance cancellation change. That change
has444 module tests, focused1154-provenance-value coverage and lint validation;
budgeted execution retains its generic fallback. Historical normal co-use
+37.095ms in317 remains visible and is not superseded by this different workload.

## Retained evidence

The [immutable evidence release](https://github.com/johnsonlee/graphite/releases/tag/benchmark-evidence-pr212-attempt318-2026-11-11)
contains three archives, three member manifests and the [asset catalog](assets.json).
All seven assets were freshly downloaded and byte/SHA-verified before publication;
the unconditional verifier passed under `python -O` for all896 archive members.
Archives stay outside Git. Member-manifest JSON is compact in Git with equivalent
content. Candidate/source snapshots and controller session validation are explicit
session provenance, not embedded binary commit identities or reproducible builds.

```sh
python3 docs/query-performance-evidence/2026-11-11/fetch_assets.py \
  --manifest docs/query-performance-evidence/2026-11-11/complex-p95/attempt318/assets.json \
  --cache-dir "$HOME/.cache/graphite/evidence/pr212-attempt318"
python3 -O docs/query-performance-evidence/2026-11-11/complex-p95/verify_archive.py \
  --manifest attempt318/dense-pilot-manifest.json \
  --archive "$HOME/.cache/graphite/evidence/pr212-attempt318/dense-pilot.tar.gz" \
  --extract /tmp/graphite-attempt318-independent-review
python3 docs/query-performance-evidence/2026-11-11/complex-p95/audit_local.py \
  /tmp/graphite-attempt318-independent-review/dense-pilot \
  --candidate-run /tmp/graphite-attempt318-independent-review/dense-pilot \
  --candidate-arm candidate --output /tmp/graphite-attempt318-audit.json
```
