# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,632.148 | 3,949.706 | 2,471.223–4,093.261 |
| bi-global-target-support | 20 / 20 | 0 | 2,941.392 | 4,388.318 | 2,747.686–5,646.054 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,103.661 | 4,265.109 | 4,026.284–4,531.904 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 6,298.155 | 8,959.953 | 5,645.684–11,642.904 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 11,687.409 | 16,655.825 | 10,568.286–19,074.627 |
| bi-dependency-co-use | 20 / 20 | 0 | 29,604.843 | 38,919.123 | 26,255.552–41,012.689 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.560 |
| Load-only kernel peak RSS, MiB | 3,804.578 |
| Complete mixed pressure CPU user+system, seconds | 1,130.778 |
| Query-process lifetime CPU, seconds (wait4) | 1,305.937 |
| Query-process lifetime kernel peak RSS, MiB | 32,915.859 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,495.373 | 2,536.270 | 2,459.812–2,536.658 |
| bi-global-target-support | 20 / 20 | 0 | 2,738.615 | 2,776.441 | 2,700.692–2,799.703 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,040.253 | 4,061.495 | 4,020.417–4,067.676 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 5,536.671 | 5,650.064 | 5,434.182–5,779.786 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,341.124 | 10,696.064 | 10,061.647–10,861.333 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,221.474 | 2,264.064 | 2,160.681–2,264.405 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.262 |
| Load-only kernel peak RSS, MiB | 3,799.484 |
| Complete mixed pressure CPU user+system, seconds | 545.562 |
| Query-process lifetime CPU, seconds (wait4) | 645.642 |
| Query-process lifetime kernel peak RSS, MiB | 26,668.109 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 3,949.706 | 2,536.270 | -1,413.436 | -35.786 |
| bi-global-target-support | 4,388.318 | 2,776.441 | -1,611.877 | -36.731 |
| bi-two-hop-dataflow | 4,265.109 | 4,061.495 | -203.614 | -4.774 |
| bi-dataflow-degree-histogram | 8,959.953 | 5,650.064 | -3,309.889 | -36.941 |
| bi-dataflow-optional-sinks | 16,655.825 | 10,696.064 | -5,959.761 | -35.782 |
| bi-dependency-co-use | 38,919.123 | 2,264.064 | -36,655.059 | -94.183 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.
