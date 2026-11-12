# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,719.453 | 2,814.430 | 2,683.683–2,851.400 |
| bi-global-target-support | 20 / 20 | 0 | 2,986.866 | 3,065.649 | 2,948.564–3,074.735 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,140.191 | 4,174.681 | 4,098.892–4,207.681 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 3,680.538 | 3,839.991 | 3,603.862–3,857.298 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,793.247 | 11,150.032 | 10,390.559–11,175.152 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,457.881 | 2,616.942 | 2,397.883–2,658.604 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.961 |
| Load-only kernel peak RSS, MiB | 3,800.375 |
| Complete mixed pressure CPU user+system, seconds | 533.352 |
| Query-process lifetime CPU, seconds (wait4) | 632.085 |
| Query-process lifetime kernel peak RSS, MiB | 26,949.094 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,522.194 | 2,650.357 | 2,404.512–2,817.466 |
| bi-global-target-support | 20 / 20 | 0 | 2,826.606 | 2,916.908 | 2,716.180–2,992.571 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,279.441 | 4,336.742 | 4,156.446–4,339.911 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 3,898.751 | 4,112.231 | 3,700.736–4,126.607 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 11,136.107 | 11,860.862 | 10,560.533–11,920.862 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,440.099 | 2,632.981 | 2,231.511–2,648.549 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 19.232 |
| Load-only kernel peak RSS, MiB | 3,800.125 |
| Complete mixed pressure CPU user+system, seconds | 535.599 |
| Query-process lifetime CPU, seconds (wait4) | 635.132 |
| Query-process lifetime kernel peak RSS, MiB | 26,937.328 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 2,814.430 | 2,650.357 | -164.073 | -5.830 |
| bi-global-target-support | 3,065.649 | 2,916.908 | -148.742 | -4.852 |
| bi-two-hop-dataflow | 4,174.681 | 4,336.742 | 162.061 | 3.882 |
| bi-dataflow-degree-histogram | 3,839.991 | 4,112.231 | 272.240 | 7.090 |
| bi-dataflow-optional-sinks | 11,150.032 | 11,860.862 | 710.830 | 6.375 |
| bi-dependency-co-use | 2,616.942 | 2,632.981 | 16.039 | 0.613 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.

## Independent resource constraints

| Operation / metric | Baseline | Candidate | Absolute change | Change % | Within +5% |
| --- | ---: | ---: | ---: | ---: | --- |
| loadingCpuSeconds | 17.961482 | 19.231650 | 1.270168 | 7.071621 | no |
| loadingPeakRssBytes | 3984982016 | 3984719872 | -262144 | -0.006578 | yes |
| pressureMixedCpuSeconds | 533.352116 | 535.599181 | 2.247065 | 0.421310 | yes |
| queryLifecyclePeakRssBytes | 28258172928 | 28245835776 | -12337152 | -0.043659 | yes |

CPU is user+system seconds; RSS is bytes. Query CPU covers the complete fixed mixed pressure stage. Query RSS is kernel lifetime peak, including loading/first-use/warmup. No per-case resource acceptance or pooled latency is inferred.
