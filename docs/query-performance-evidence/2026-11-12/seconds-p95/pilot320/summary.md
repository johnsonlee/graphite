# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,540.497 | 2,592.183 | 2,489.072–2,622.848 |
| bi-global-target-support | 20 / 20 | 0 | 2,844.146 | 2,901.367 | 2,749.094–2,926.169 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,130.168 | 4,164.070 | 4,097.903–4,165.733 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 5,833.546 | 6,013.233 | 5,659.128–6,087.737 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,809.379 | 11,350.926 | 10,520.035–11,491.451 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,387.524 | 2,544.521 | 2,213.526–2,588.213 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.632 |
| Load-only kernel peak RSS, MiB | 3,801.344 |
| Complete mixed pressure CPU user+system, seconds | 569.240 |
| Query-process lifetime CPU, seconds (wait4) | 669.315 |
| Query-process lifetime kernel peak RSS, MiB | 26,976.812 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,767.048 | 2,824.908 | 2,683.358–2,848.219 |
| bi-global-target-support | 20 / 20 | 0 | 3,057.171 | 3,136.905 | 2,990.320–3,148.096 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,126.447 | 4,165.068 | 4,080.462–4,171.022 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 3,738.327 | 3,888.162 | 3,659.485–3,911.312 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,731.009 | 11,357.459 | 10,584.979–11,393.107 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,578.798 | 2,759.870 | 2,448.555–2,761.809 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.610 |
| Load-only kernel peak RSS, MiB | 3,793.750 |
| Complete mixed pressure CPU user+system, seconds | 539.267 |
| Query-process lifetime CPU, seconds (wait4) | 637.826 |
| Query-process lifetime kernel peak RSS, MiB | 27,028.406 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 2,592.183 | 2,824.908 | 232.725 | 8.978 |
| bi-global-target-support | 2,901.367 | 3,136.905 | 235.538 | 8.118 |
| bi-two-hop-dataflow | 4,164.070 | 4,165.068 | 0.999 | 0.024 |
| bi-dataflow-degree-histogram | 6,013.233 | 3,888.162 | -2,125.071 | -35.340 |
| bi-dataflow-optional-sinks | 11,350.926 | 11,357.459 | 6.533 | 0.058 |
| bi-dependency-co-use | 2,544.521 | 2,759.870 | 215.350 | 8.463 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.

## Independent resource constraints

| Operation / metric | Baseline | Candidate | Absolute change | Change % | Within +5% |
| --- | ---: | ---: | ---: | ---: | --- |
| loadingCpuSeconds | 17.632174 | 18.609709 | 0.977535 | 5.544041 | no |
| loadingPeakRssBytes | 3985997824 | 3978035200 | -7962624 | -0.199765 | yes |
| pressureMixedCpuSeconds | 569.240059 | 539.267181 | -29.972878 | -5.265420 | yes |
| queryLifecyclePeakRssBytes | 28287238144 | 28341338112 | 54099968 | 0.191252 | yes |

CPU is user+system seconds; RSS is bytes. Query CPU covers the complete fixed mixed pressure stage. Query RSS is kernel lifetime peak, including loading/first-use/warmup. No per-case resource acceptance or pooled latency is inferred.
