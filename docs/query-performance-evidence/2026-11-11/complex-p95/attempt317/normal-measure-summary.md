# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 618.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 100 / 100 | 0 | 2,503.189 | 2,528.601 | 2,465.121–2,541.853 |
| bi-global-target-support | 100 / 100 | 0 | 2,748.154 | 2,785.255 | 2,703.794–2,800.825 |
| bi-two-hop-dataflow | 100 / 100 | 0 | 4,032.403 | 4,066.375 | 4,007.108–4,088.906 |
| bi-dataflow-degree-histogram | 100 / 100 | 0 | 5,590.861 | 5,668.089 | 5,438.382–5,726.929 |
| bi-dataflow-optional-sinks | 100 / 100 | 0 | 10,838.854 | 11,037.184 | 10,110.769–11,096.773 |
| bi-dependency-co-use | 100 / 100 | 0 | 2,237.038 | 2,285.934 | 2,178.821–2,317.842 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.973 |
| Load-only kernel peak RSS, MiB | 3,798.047 |
| Complete mixed pressure CPU user+system, seconds | 2,757.365 |
| Query-process lifetime CPU, seconds (wait4) | 2,858.018 |
| Query-process lifetime kernel peak RSS, MiB | 26,936.219 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 618.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 100 / 100 | 0 | 2,518.010 | 2,546.549 | 2,483.970–2,562.742 |
| bi-global-target-support | 100 / 100 | 0 | 2,755.429 | 2,797.968 | 2,694.043–2,837.051 |
| bi-two-hop-dataflow | 100 / 100 | 0 | 4,039.192 | 4,076.518 | 4,017.293–4,089.554 |
| bi-dataflow-degree-histogram | 100 / 100 | 0 | 5,519.562 | 5,594.670 | 5,455.917–5,647.226 |
| bi-dataflow-optional-sinks | 100 / 100 | 0 | 10,348.080 | 10,516.431 | 10,033.747–10,610.345 |
| bi-dependency-co-use | 100 / 100 | 0 | 2,275.980 | 2,323.029 | 2,226.452–2,382.162 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.765 |
| Load-only kernel peak RSS, MiB | 3,796.281 |
| Complete mixed pressure CPU user+system, seconds | 2,732.097 |
| Query-process lifetime CPU, seconds (wait4) | 2,832.823 |
| Query-process lifetime kernel peak RSS, MiB | 27,343.719 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 2,528.601 | 2,546.549 | 17.948 | 0.710 |
| bi-global-target-support | 2,785.255 | 2,797.968 | 12.713 | 0.456 |
| bi-two-hop-dataflow | 4,066.375 | 4,076.518 | 10.143 | 0.249 |
| bi-dataflow-degree-histogram | 5,668.089 | 5,594.670 | -73.419 | -1.295 |
| bi-dataflow-optional-sinks | 11,037.184 | 10,516.431 | -520.753 | -4.718 |
| bi-dependency-co-use | 2,285.934 | 2,323.029 | 37.095 | 1.623 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.
