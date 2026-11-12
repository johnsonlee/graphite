# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,744.806 | 2,797.013 | 2,699.605–2,816.073 |
| bi-global-target-support | 20 / 20 | 0 | 3,035.218 | 3,087.691 | 2,965.678–3,106.537 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,092.351 | 4,123.576 | 4,031.780–4,127.599 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 3,725.046 | 3,790.128 | 3,559.825–3,806.827 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,762.910 | 11,194.728 | 10,118.317–11,302.881 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,576.636 | 2,678.702 | 2,456.243–2,689.568 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.116 |
| Load-only kernel peak RSS, MiB | 3,795.859 |
| Complete mixed pressure CPU user+system, seconds | 535.457 |
| Query-process lifetime CPU, seconds (wait4) | 635.182 |
| Query-process lifetime kernel peak RSS, MiB | 27,461.391 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,707.255 | 2,769.021 | 2,664.671–2,813.057 |
| bi-global-target-support | 20 / 20 | 0 | 2,965.993 | 3,030.721 | 2,904.745–3,038.276 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,117.266 | 4,156.256 | 4,112.709–4,156.353 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 3,664.673 | 3,830.142 | 3,578.384–3,853.448 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,595.636 | 11,083.788 | 10,212.605–11,163.075 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,432.754 | 2,522.120 | 2,383.295–2,588.496 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.408 |
| Load-only kernel peak RSS, MiB | 3,802.953 |
| Complete mixed pressure CPU user+system, seconds | 528.799 |
| Query-process lifetime CPU, seconds (wait4) | 627.168 |
| Query-process lifetime kernel peak RSS, MiB | 26,945.797 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 2,797.013 | 2,769.021 | -27.992 | -1.001 |
| bi-global-target-support | 3,087.691 | 3,030.721 | -56.970 | -1.845 |
| bi-two-hop-dataflow | 4,123.576 | 4,156.256 | 32.680 | 0.793 |
| bi-dataflow-degree-histogram | 3,790.128 | 3,830.142 | 40.014 | 1.056 |
| bi-dataflow-optional-sinks | 11,194.728 | 11,083.788 | -110.940 | -0.991 |
| bi-dependency-co-use | 2,678.702 | 2,522.120 | -156.582 | -5.845 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.

## Independent resource constraints

| Operation / metric | Baseline | Candidate | Absolute change | Change % | Within +5% |
| --- | ---: | ---: | ---: | ---: | --- |
| loadingCpuSeconds | 18.115614 | 18.408029 | 0.292415 | 1.614160 | yes |
| loadingPeakRssBytes | 3980247040 | 3987685376 | 7438336 | 0.186881 | yes |
| pressureMixedCpuSeconds | 535.456693 | 528.799460 | -6.657234 | -1.243281 | yes |
| queryLifecyclePeakRssBytes | 28795355136 | 28254715904 | -540639232 | -1.877522 | yes |

CPU is user+system seconds; RSS is bytes. Query CPU covers the complete fixed mixed pressure stage. Query RSS is kernel lifetime peak, including loading/first-use/warmup. No per-case resource acceptance or pooled latency is inferred.
