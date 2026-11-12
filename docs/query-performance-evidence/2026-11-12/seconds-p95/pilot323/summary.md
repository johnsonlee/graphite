# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,448.304 | 2,566.544 | 2,406.704–2,597.988 |
| bi-global-target-support | 20 / 20 | 0 | 2,728.508 | 2,798.090 | 2,652.522–2,807.701 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,191.806 | 4,235.462 | 4,165.013–4,245.203 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 3,851.534 | 3,903.893 | 3,724.686–4,090.054 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,808.609 | 11,585.851 | 10,585.080–11,699.307 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,278.801 | 2,453.876 | 2,148.486–2,455.747 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.156 |
| Load-only kernel peak RSS, MiB | 3,799.844 |
| Complete mixed pressure CPU user+system, seconds | 524.429 |
| Query-process lifetime CPU, seconds (wait4) | 620.832 |
| Query-process lifetime kernel peak RSS, MiB | 27,432.234 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,391.808 | 2,454.536 | 2,341.461–2,520.849 |
| bi-global-target-support | 20 / 20 | 0 | 2,724.939 | 2,796.374 | 2,615.790–2,808.492 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,198.056 | 4,224.471 | 4,145.147–4,254.265 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 3,915.029 | 4,370.623 | 3,689.494–4,521.796 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 7,669.983 | 8,283.724 | 6,962.725–8,651.518 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,212.282 | 2,357.849 | 2,065.725–2,373.441 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.408 |
| Load-only kernel peak RSS, MiB | 3,795.203 |
| Complete mixed pressure CPU user+system, seconds | 458.261 |
| Query-process lifetime CPU, seconds (wait4) | 543.251 |
| Query-process lifetime kernel peak RSS, MiB | 22,505.125 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 2,566.544 | 2,454.536 | -112.007 | -4.364 |
| bi-global-target-support | 2,798.090 | 2,796.374 | -1.716 | -0.061 |
| bi-two-hop-dataflow | 4,235.462 | 4,224.471 | -10.991 | -0.260 |
| bi-dataflow-degree-histogram | 3,903.893 | 4,370.623 | 466.730 | 11.955 |
| bi-dataflow-optional-sinks | 11,585.851 | 8,283.724 | -3,302.127 | -28.501 |
| bi-dependency-co-use | 2,453.876 | 2,357.849 | -96.028 | -3.913 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.

## Independent resource constraints

| Operation / metric | Baseline | Candidate | Absolute change | Change % | Within +5% |
| --- | ---: | ---: | ---: | ---: | --- |
| loadingCpuSeconds | 18.156149 | 18.408176 | 0.252027 | 1.388108 | yes |
| loadingPeakRssBytes | 3984424960 | 3979558912 | -4866048 | -0.122127 | yes |
| pressureMixedCpuSeconds | 524.429388 | 458.260979 | -66.168409 | -12.617220 | yes |
| queryLifecyclePeakRssBytes | 28764782592 | 23598333952 | -5166448640 | -17.961021 | yes |

CPU is user+system seconds; RSS is bytes. Query CPU covers the complete fixed mixed pressure stage. Query RSS is kernel lifetime peak, including loading/first-use/warmup. No per-case resource acceptance or pooled latency is inferred.
