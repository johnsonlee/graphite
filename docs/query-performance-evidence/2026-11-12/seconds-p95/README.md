# Seconds-scale P95 experiment evidence

Baseline: merged PR212 `69ba5f3072c4984d0e6cdb98eeeb107cd365bccd`.
Product candidate: `868158af5723a931e936c7bcb6212edc78f0b805` (Attempt324).
All requests select64 real persisted graphs with four concurrent workers and
complete response consumption. See [protocol](protocol.json), [host](host.txt),
[baseline source equivalence](baseline-source-equivalence.json), and
[dataset observations](dataset-observations.json).

| Increment | Summary | Independent full-body audit |
| --- | --- | --- |
|319|[P50/P95, CPU/RSS](pilot319/summary.md)|[JSON](pilot319/audit.json)|
|320|[P50/P95, CPU/RSS](pilot320/summary.md)|[JSON](pilot320/audit.json)|
|321|[P50/P95, CPU/RSS](pilot321/summary.md)|[JSON](pilot321/audit.json)|
|322|[P50/P95, CPU/RSS](pilot322/summary.md)|[JSON](pilot322/audit.json)|
|323|[P50/P95, CPU/RSS](pilot323/summary.md)|[JSON](pilot323/audit.json)|
|324|[P50/P95, CPU/RSS](pilot324/summary.md)|[JSON](pilot324/audit.json)|

Each AB pilot has20 pressure samples/case/arm and276 complete responses.
Six independent pairs total1656 responses; never pool their percentiles.
LoadingCPU exceeds5% in320 and322, and query peakRSS exceeds5% in324 relative
to their immediate parents. These are retained violations, not overall acceptance.
No production recovery or universal latency-pass claim follows from pilot gains.

The [pilot evidence release](https://github.com/johnsonlee/graphite/releases/tag/benchmark-evidence-seconds-p95-pilots-2026-11-12)
hosts eight raw archives with [SHA256 pins](assets.json) and member manifests.
Archives remain outside Git. Build/test/source manifests are session provenance,
not independent reproducible-build or embedded-commit proof.

```sh
python3 docs/query-performance-evidence/2026-11-11/fetch_assets.py \
  --manifest docs/query-performance-evidence/2026-11-12/seconds-p95/assets.json \
  --cache-dir "$HOME/.cache/graphite/evidence/seconds-p95-pilots"
python3 -O docs/query-performance-evidence/2026-11-11/complex-p95/verify_archive.py \
  --manifest "$PWD/docs/query-performance-evidence/2026-11-12/seconds-p95/pilot324-manifest.json" \
  --archive "$HOME/.cache/graphite/evidence/seconds-p95-pilots/pilot324.tar.gz" \
  --extract /tmp/graphite-seconds-p95-independent
python3 docs/query-performance-evidence/2026-11-11/complex-p95/audit_local.py \
  /tmp/graphite-seconds-p95-independent/pilot324 \
  --candidate-run /tmp/graphite-seconds-p95-independent/pilot324 \
  --candidate-arm candidate --output /tmp/graphite-seconds-p95-independent-audit.json
```

## Cumulative evaluation appendix

The separately published [final assessment](https://github.com/johnsonlee/graphite/releases/download/benchmark-evidence-seconds-p95-final-2026-11-12/final-assessment.md)
and [immutable final release](https://github.com/johnsonlee/graphite/releases/tag/benchmark-evidence-seconds-p95-final-2026-11-12)
contain base/cumulative BA100,73-case regression and any explicitly declared
loading diagnosis. Its external assets.json and per-archive member manifests
bind all final samples and conclusions. Keeping the appendix external lets
product CI and independent local validation run concurrently on frozen code;
publication and review readiness require both to finish. Final PR text records
that exact product/CI identity and appendix checksum. Pilot results above remain
historical, including adverse observations, irrespective of final results.
