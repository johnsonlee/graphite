# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,502.203 | 2,524.002 | 2,461.632–2,537.378 |
| bi-global-target-support | 20 / 20 | 0 | 2,745.367 | 2,769.479 | 2,720.854–2,791.442 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,029.269 | 4,064.870 | 4,001.598–4,069.535 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 5,509.292 | 5,644.167 | 5,429.543–5,671.724 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,280.407 | 10,367.474 | 9,943.970–10,446.670 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,218.805 | 2,254.600 | 2,161.971–2,269.217 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.259 |
| Load-only kernel peak RSS, MiB | 3,800.000 |
| Complete mixed pressure CPU user+system, seconds | 543.037 |
| Query-process lifetime CPU, seconds (wait4) | 642.744 |
| Query-process lifetime kernel peak RSS, MiB | 26,685.109 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,504.430 | 2,535.378 | 2,462.716–2,546.032 |
| bi-global-target-support | 20 / 20 | 0 | 2,748.269 | 2,795.812 | 2,688.714–2,795.839 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,030.806 | 4,046.487 | 4,009.056–4,049.398 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 5,558.505 | 5,618.247 | 5,480.887–5,648.275 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,321.879 | 10,457.890 | 10,111.092–10,494.564 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,235.476 | 2,320.536 | 2,180.573–2,335.768 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.094 |
| Load-only kernel peak RSS, MiB | 3,799.719 |
| Complete mixed pressure CPU user+system, seconds | 545.652 |
| Query-process lifetime CPU, seconds (wait4) | 646.512 |
| Query-process lifetime kernel peak RSS, MiB | 26,297.172 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 2,524.002 | 2,535.378 | 11.376 | 0.451 |
| bi-global-target-support | 2,769.479 | 2,795.812 | 26.333 | 0.951 |
| bi-two-hop-dataflow | 4,064.870 | 4,046.487 | -18.383 | -0.452 |
| bi-dataflow-degree-histogram | 5,644.167 | 5,618.247 | -25.920 | -0.459 |
| bi-dataflow-optional-sinks | 10,367.474 | 10,457.890 | 90.415 | 0.872 |
| bi-dependency-co-use | 2,254.600 | 2,320.536 | 65.936 | 2.925 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.
