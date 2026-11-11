# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,404.635 | 2,453.626 | 2,377.637–2,457.649 |
| bi-global-target-support | 20 / 20 | 0 | 2,722.524 | 2,778.076 | 2,682.222–2,785.086 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,208.849 | 4,241.492 | 4,181.606–4,248.672 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 3,858.133 | 4,101.210 | 3,737.331–4,123.550 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 7,497.121 | 7,861.910 | 7,243.431–8,049.549 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,262.480 | 2,338.773 | 2,089.456–2,347.151 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.882 |
| Load-only kernel peak RSS, MiB | 3,803.781 |
| Complete mixed pressure CPU user+system, seconds | 457.072 |
| Query-process lifetime CPU, seconds (wait4) | 542.885 |
| Query-process lifetime kernel peak RSS, MiB | 22,233.688 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,376.855 | 2,413.938 | 2,316.727–2,423.402 |
| bi-global-target-support | 20 / 20 | 0 | 2,686.851 | 2,722.968 | 2,612.915–2,878.145 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 3,253.546 | 3,286.695 | 3,232.547–3,309.507 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 3,816.098 | 4,043.423 | 3,687.082–4,080.216 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 7,621.871 | 8,278.760 | 7,289.674–8,518.941 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,172.693 | 2,234.886 | 2,062.956–2,253.470 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.079 |
| Load-only kernel peak RSS, MiB | 3,797.375 |
| Complete mixed pressure CPU user+system, seconds | 436.864 |
| Query-process lifetime CPU, seconds (wait4) | 519.591 |
| Query-process lifetime kernel peak RSS, MiB | 23,703.906 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 2,453.626 | 2,413.938 | -39.689 | -1.618 |
| bi-global-target-support | 2,778.076 | 2,722.968 | -55.108 | -1.984 |
| bi-two-hop-dataflow | 4,241.492 | 3,286.695 | -954.797 | -22.511 |
| bi-dataflow-degree-histogram | 4,101.210 | 4,043.423 | -57.788 | -1.409 |
| bi-dataflow-optional-sinks | 7,861.910 | 8,278.760 | 416.850 | 5.302 |
| bi-dependency-co-use | 2,338.773 | 2,234.886 | -103.887 | -4.442 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.

## Independent resource constraints

| Operation / metric | Baseline | Candidate | Absolute change | Change % | Within +5% |
| --- | ---: | ---: | ---: | ---: | --- |
| loadingCpuSeconds | 17.881995 | 18.079137 | 0.197142 | 1.102461 | yes |
| loadingPeakRssBytes | 3988553728 | 3981836288 | -6717440 | -0.168418 | yes |
| pressureMixedCpuSeconds | 457.071675 | 436.863665 | -20.208010 | -4.421191 | yes |
| queryLifecyclePeakRssBytes | 23313711104 | 24855347200 | 1541636096 | 6.612573 | no |

CPU is user+system seconds; RSS is bytes. Query CPU covers the complete fixed mixed pressure stage. Query RSS is kernel lifetime peak, including loading/first-use/warmup. No per-case resource acceptance or pooled latency is inferred.
