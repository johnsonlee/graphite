# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,521.031 | 2,636.761 | 2,486.955–2,675.490 |
| bi-global-target-support | 20 / 20 | 0 | 3,004.618 | 4,041.096 | 2,743.524–4,140.406 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,052.924 | 4,125.299 | 4,023.629–4,184.807 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 6,379.972 | 7,466.682 | 5,631.354–7,715.207 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 12,104.584 | 13,816.374 | 10,511.638–14,666.500 |
| bi-dependency-co-use | 20 / 20 | 0 | 28,136.037 | 31,239.094 | 25,983.659–35,786.532 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.708 |
| Load-only kernel peak RSS, MiB | 3,797.281 |
| Complete mixed pressure CPU user+system, seconds | 1,081.894 |
| Query-process lifetime CPU, seconds (wait4) | 1,256.074 |
| Query-process lifetime kernel peak RSS, MiB | 32,301.609 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,497.753 | 2,522.399 | 2,447.453–2,525.085 |
| bi-global-target-support | 20 / 20 | 0 | 2,748.260 | 2,787.069 | 2,699.324–2,805.692 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,049.589 | 4,070.277 | 4,017.195–4,093.087 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 5,526.377 | 5,649.511 | 5,454.546–5,715.975 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 10,273.389 | 10,554.603 | 9,904.977–10,569.484 |
| bi-dependency-co-use | 20 / 20 | 0 | 2,217.805 | 2,274.597 | 2,169.449–2,294.836 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.185 |
| Load-only kernel peak RSS, MiB | 3,796.859 |
| Complete mixed pressure CPU user+system, seconds | 543.809 |
| Query-process lifetime CPU, seconds (wait4) | 644.105 |
| Query-process lifetime kernel peak RSS, MiB | 26,852.344 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 2,636.761 | 2,522.399 | -114.362 | -4.337 |
| bi-global-target-support | 4,041.096 | 2,787.069 | -1,254.026 | -31.032 |
| bi-two-hop-dataflow | 4,125.299 | 4,070.277 | -55.021 | -1.334 |
| bi-dataflow-degree-histogram | 7,466.682 | 5,649.511 | -1,817.172 | -24.337 |
| bi-dataflow-optional-sinks | 13,816.374 | 10,554.603 | -3,261.771 | -23.608 |
| bi-dependency-co-use | 31,239.094 | 2,274.597 | -28,964.497 | -92.719 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.
