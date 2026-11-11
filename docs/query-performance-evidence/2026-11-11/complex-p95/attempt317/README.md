# Attempt317 independent observations

Three separate real64 rounds compare Attempt316 with Attempt317 (`1c85b1cb`).
Do not pool normal pilot, normal confirmation or wide-distinct samples. All issued
responses, complete typed results, receipts and per-case percentiles independently
verify. `performanceAcceptance` remains false.

| Round | Order | Pressure per case | Complete responses | Audit | Summary |
| --- | --- | ---: | ---: | --- | --- |
| Normal pilot | AB | 20 | 276 | [JSON](normal-pilot-audit.json) | [P50/P95](normal-pilot-summary.md) |
| Normal confirmation | BA | 100 | 1236 | [JSON](normal-measure-audit.json) | [P50/P95](normal-measure-summary.md) |
| Wide DISTINCT pilot | AB | 20 | 276 | [JSON](wide-pilot-audit.json) | [P50/P95](wide-pilot-summary.md) |

The normal confirmation retains four P95 increases, including co-use
2285.934→2323.029ms (+37.095ms). Wide co-use improves51231.978→3891.426ms
(-47340.551ms), with mixed pressure CPU−49.816% and lifetime RSS−27.074%.
All matched loading and mixed-query CPU/RSS observations are within their separate
5% limits. This does not establish each-case resource limits, all-case latency
recovery or production acceptance. Wide has one20-sample pilot only.

[Cardinality proof](wide-cardinality-proof.json) binds all23 co-use bodies per arm:
maximum returned callers19397 in each response, actual graphCount64. With at most64
provenance values, >576 distinct callers proves sparse mask storage. It is a
logical dictionary, not a benchmark of more than64 physical graphs.

## Raw evidence

The separate [Attempt317 evidence release](https://github.com/johnsonlee/graphite/releases/tag/benchmark-evidence-pr212-attempt317-2026-11-11)
is published and immutable. Its [pinned assets](assets.json) cover three raw runs
and one identity archive. All nine assets (four gzip archives, the asset manifest
and four member manifests) were downloaded to a fresh directory and verified
byte-for-byte before publication. Archives remain outside Git. Repository audit
and member-manifest JSON may use compact formatting; the published JSON preserves
the original formatting and equivalent content.

Fetch and verify:

```sh
python3 docs/query-performance-evidence/2026-11-11/fetch_assets.py \
  --manifest docs/query-performance-evidence/2026-11-11/complex-p95/attempt317/assets.json \
  --cache-dir "$HOME/.cache/graphite/evidence/pr212-attempt317"
python3 docs/query-performance-evidence/2026-11-11/complex-p95/verify_archive.py \
  --manifest attempt317/normal-measure-manifest.json \
  --archive "$HOME/.cache/graphite/evidence/pr212-attempt317/normal-measure.tar.gz" \
  --extract /tmp/graphite-attempt317-independent-review
python3 docs/query-performance-evidence/2026-11-11/complex-p95/audit_local.py \
  /tmp/graphite-attempt317-independent-review/normal-measure \
  --candidate-run /tmp/graphite-attempt317-independent-review/normal-measure \
  --candidate-arm candidate --output /tmp/graphite-attempt317-audit.json
```

Each matching `*-manifest.json` checks every archive member. The three raw run
archives contain859/3739/859 original files, including logs. The five-file
[source/controller manifest](source-controller-identity-manifest.json) binds the
candidate source-file manifest, source diff, build/test logs and controller
identity. The source manifest was captured after build and before source commit;
it is session provenance, not an embedded commit identity or reproducible-build
proof. Runtime binary hashes remain in each unmodified report. Exact graph
payloads and executables are excluded; the input seals and identities are retained.
