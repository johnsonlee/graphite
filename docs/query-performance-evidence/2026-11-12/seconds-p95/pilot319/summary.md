# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,556.572 | 2,603.045 | 2,514.354–2,609.517 |
| bi-global-target-support | 20 / 20 | 0 | 2,828.152 | 2,902.553 | 2,761.811–2,912.696 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,099.542 | 4,131.621 | 4,043.810–4,142.916 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 5,731.488 | 5,872.969 | 5,577.141–5,982.999 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,628.678 | 10,977.785 | 10,122.714–11,193.697 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,388.440 | 2,544.299 | 2,209.554–2,550.931 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.071 |
| Load-only kernel peak RSS, MiB | 3,796.703 |
| Complete mixed pressure CPU user+system, seconds | 560.447 |
| Query-process lifetime CPU, seconds (wait4) | 665.272 |
| Query-process lifetime kernel peak RSS, MiB | 26,718.344 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,479.079 | 2,524.862 | 2,449.289–2,534.085 |
| bi-global-target-support | 20 / 20 | 0 | 2,721.030 | 2,805.695 | 2,674.809–2,825.510 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,031.823 | 4,068.540 | 4,004.830–4,077.198 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 5,602.658 | 5,714.670 | 5,468.011–5,783.970 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,512.325 | 10,818.636 | 10,295.369–11,082.892 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,202.281 | 2,358.072 | 2,151.111–2,447.214 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.200 |
| Load-only kernel peak RSS, MiB | 3,799.156 |
| Complete mixed pressure CPU user+system, seconds | 549.866 |
| Query-process lifetime CPU, seconds (wait4) | 650.256 |
| Query-process lifetime kernel peak RSS, MiB | 26,052.812 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 2,603.045 | 2,524.862 | -78.183 | -3.004 |
| bi-global-target-support | 2,902.553 | 2,805.695 | -96.859 | -3.337 |
| bi-two-hop-dataflow | 4,131.621 | 4,068.540 | -63.081 | -1.527 |
| bi-dataflow-degree-histogram | 5,872.969 | 5,714.670 | -158.299 | -2.695 |
| bi-dataflow-optional-sinks | 10,977.785 | 10,818.636 | -159.150 | -1.450 |
| bi-dependency-co-use | 2,544.299 | 2,358.072 | -186.227 | -7.319 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.

## Independent resource constraints

| Operation / metric | Baseline | Candidate | Absolute change | Change % | Within +5% |
| --- | ---: | ---: | ---: | ---: | --- |
| loadingCpuSeconds | 18.070720 | 18.200222 | 0.129502 | 0.716640 | yes |
| loadingPeakRssBytes | 3981131776 | 3983704064 | 2572288 | 0.064612 | yes |
| pressureMixedCpuSeconds | 560.446564 | 549.865917 | -10.580647 | -1.887896 | yes |
| queryLifecyclePeakRssBytes | 28016214016 | 27318353920 | -697860096 | -2.490915 | yes |

CPU is user+system seconds; RSS is bytes. Query CPU covers the complete fixed mixed pressure stage. Query RSS is kernel lifetime peak, including loading/first-use/warmup. No per-case resource acceptance or pooled latency is inferred.
