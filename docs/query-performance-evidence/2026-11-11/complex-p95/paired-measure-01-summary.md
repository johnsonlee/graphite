# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 618.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 100 / 100 | 0 | 2,725.548 | 5,768.295 | 2,469.291–8,049.547 |
| bi-global-target-support | 100 / 100 | 0 | 3,020.626 | 4,889.072 | 2,782.264–9,821.617 |
| bi-two-hop-dataflow | 100 / 100 | 0 | 4,137.004 | 4,595.136 | 4,047.098–6,702.786 |
| bi-dataflow-degree-histogram | 100 / 100 | 0 | 7,356.320 | 12,708.706 | 5,550.314–15,236.849 |
| bi-dataflow-optional-sinks | 100 / 100 | 0 | 12,812.713 | 21,838.016 | 10,301.414–29,430.513 |
| bi-dependency-co-use | 100 / 100 | 0 | 32,087.115 | 46,058.471 | 26,125.935–58,105.553 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.028 |
| Load-only kernel peak RSS, MiB | 3,795.984 |
| Complete mixed pressure CPU user+system, seconds | 5,655.693 |
| Query-process lifetime CPU, seconds (wait4) | 5,835.004 |
| Query-process lifetime kernel peak RSS, MiB | 34,390.297 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 618.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 100 / 100 | 0 | 2,502.956 | 2,528.259 | 2,444.972–2,542.993 |
| bi-global-target-support | 100 / 100 | 0 | 2,755.066 | 2,801.873 | 2,696.124–2,847.161 |
| bi-two-hop-dataflow | 100 / 100 | 0 | 4,050.062 | 4,081.245 | 4,023.638–4,116.942 |
| bi-dataflow-degree-histogram | 100 / 100 | 0 | 5,650.043 | 5,808.666 | 5,491.829–5,900.805 |
| bi-dataflow-optional-sinks | 100 / 100 | 0 | 10,900.323 | 11,352.328 | 10,063.703–11,512.395 |
| bi-dependency-co-use | 100 / 100 | 0 | 2,239.870 | 2,292.147 | 2,158.840–2,343.123 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.590 |
| Load-only kernel peak RSS, MiB | 3,798.109 |
| Complete mixed pressure CPU user+system, seconds | 2,766.472 |
| Query-process lifetime CPU, seconds (wait4) | 2,866.427 |
| Query-process lifetime kernel peak RSS, MiB | 27,372.078 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 5,768.295 | 2,528.259 | -3,240.036 | -56.170 |
| bi-global-target-support | 4,889.072 | 2,801.873 | -2,087.198 | -42.691 |
| bi-two-hop-dataflow | 4,595.136 | 4,081.245 | -513.891 | -11.183 |
| bi-dataflow-degree-histogram | 12,708.706 | 5,808.666 | -6,900.039 | -54.294 |
| bi-dataflow-optional-sinks | 21,838.016 | 11,352.328 | -10,485.687 | -48.016 |
| bi-dependency-co-use | 46,058.471 | 2,292.147 | -43,766.323 | -95.023 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.
