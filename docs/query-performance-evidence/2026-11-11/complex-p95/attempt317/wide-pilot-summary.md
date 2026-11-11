# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,623.063 | 6,244.053 | 2,497.008–6,955.026 |
| bi-global-target-support | 20 / 20 | 0 | 2,940.352 | 7,155.821 | 2,777.205–9,481.659 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,112.650 | 4,691.521 | 4,045.263–4,857.328 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 6,875.072 | 11,137.442 | 5,530.421–14,880.906 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 12,281.727 | 16,972.665 | 10,427.669–26,229.113 |
| bi-dependency-co-use-wide-distinct | 20 / 20 | 0 | 31,053.709 | 51,231.978 | 27,541.381–56,187.020 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.688 |
| Load-only kernel peak RSS, MiB | 3,798.797 |
| Complete mixed pressure CPU user+system, seconds | 1,160.296 |
| Query-process lifetime CPU, seconds (wait4) | 1,337.769 |
| Query-process lifetime kernel peak RSS, MiB | 35,253.672 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,524.461 | 2,551.590 | 2,466.022–2,554.142 |
| bi-global-target-support | 20 / 20 | 0 | 2,767.711 | 2,808.682 | 2,716.239–2,819.596 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,091.588 | 4,110.402 | 4,079.045–4,112.245 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 5,606.745 | 5,658.224 | 5,532.732–5,670.484 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,468.531 | 10,630.145 | 10,283.229–10,705.795 |
| bi-dependency-co-use-wide-distinct | 20 / 20 | 0 | 3,824.924 | 3,891.426 | 3,680.425–3,906.702 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.301 |
| Load-only kernel peak RSS, MiB | 3,796.375 |
| Complete mixed pressure CPU user+system, seconds | 582.279 |
| Query-process lifetime CPU, seconds (wait4) | 688.279 |
| Query-process lifetime kernel peak RSS, MiB | 25,709.031 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 6,244.053 | 2,551.590 | -3,692.463 | -59.136 |
| bi-global-target-support | 7,155.821 | 2,808.682 | -4,347.139 | -60.750 |
| bi-two-hop-dataflow | 4,691.521 | 4,110.402 | -581.119 | -12.387 |
| bi-dataflow-degree-histogram | 11,137.442 | 5,658.224 | -5,479.217 | -49.196 |
| bi-dataflow-optional-sinks | 16,972.665 | 10,630.145 | -6,342.520 | -37.369 |
| bi-dependency-co-use-wide-distinct | 51,231.978 | 3,891.426 | -47,340.551 | -92.404 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.
