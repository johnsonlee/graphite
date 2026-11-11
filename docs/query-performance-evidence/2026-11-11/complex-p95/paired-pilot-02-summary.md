# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,530.371 | 3,785.510 | 2,476.142–4,168.595 |
| bi-global-target-support | 20 / 20 | 0 | 3,134.109 | 4,146.769 | 2,756.966–5,170.152 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,053.845 | 4,148.833 | 4,000.444–4,565.071 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 6,356.386 | 9,363.787 | 5,543.673–9,682.704 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 11,832.964 | 15,986.252 | 10,319.423–16,698.153 |
| bi-dependency-co-use | 20 / 20 | 0 | 29,116.145 | 37,186.954 | 26,726.530–42,730.429 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.894 |
| Load-only kernel peak RSS, MiB | 3,796.625 |
| Complete mixed pressure CPU user+system, seconds | 1,111.713 |
| Query-process lifetime CPU, seconds (wait4) | 1,285.302 |
| Query-process lifetime kernel peak RSS, MiB | 32,680.109 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,507.778 | 2,537.866 | 2,460.811–2,545.974 |
| bi-global-target-support | 20 / 20 | 0 | 2,746.166 | 2,787.224 | 2,715.728–2,833.705 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,040.398 | 4,061.434 | 4,018.035–4,081.443 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 5,553.650 | 5,660.043 | 5,471.475–5,701.196 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,367.351 | 10,619.568 | 10,195.621–10,629.234 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,227.055 | 2,268.983 | 2,172.676–2,283.186 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.671 |
| Load-only kernel peak RSS, MiB | 3,797.672 |
| Complete mixed pressure CPU user+system, seconds | 546.922 |
| Query-process lifetime CPU, seconds (wait4) | 646.986 |
| Query-process lifetime kernel peak RSS, MiB | 25,998.078 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 3,785.510 | 2,537.866 | -1,247.644 | -32.958 |
| bi-global-target-support | 4,146.769 | 2,787.224 | -1,359.545 | -32.786 |
| bi-two-hop-dataflow | 4,148.833 | 4,061.434 | -87.399 | -2.107 |
| bi-dataflow-degree-histogram | 9,363.787 | 5,660.043 | -3,703.744 | -39.554 |
| bi-dataflow-optional-sinks | 15,986.252 | 10,619.568 | -5,366.684 | -33.571 |
| bi-dependency-co-use | 37,186.954 | 2,268.983 | -34,917.972 | -93.898 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.
