# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

**No matched P95/CPU/RSS comparison is available.** one or both workloads incomplete or audit-invalid

An incomplete or failed baseline cannot supply a baseline P95 or mixed pressure CPU. Process lifetime resource observations from different completed work are not comparable.

## baseline

Audit valid: `True`; workload complete: `False`; issued requests: 18.

Retained errors:

- base incomplete: ["warmup: incomplete/failed pressure stage: [\"error sending request for url (http://127.0.0.1:18080/api/cypher): operation timed out\", \"error sending request for url (http://127.0.0.1:18080/api/cypher): client error (SendRequest): connection closed before message completed\"]"]
- warmup: incomplete/failed pressure stage: ["error sending request for url (http://127.0.0.1:18080/api/cypher): operation timed out", "error sending request for url (http://127.0.0.1:18080/api/cypher): client error (SendRequest): connection closed before message completed"]

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 0 / 20 | 0 | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE–UNAVAILABLE |
| bi-global-target-support | 0 / 20 | 0 | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE–UNAVAILABLE |
| bi-two-hop-dataflow | 0 / 20 | 0 | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE–UNAVAILABLE |
| bi-dataflow-degree-histogram | 0 / 20 | 0 | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE–UNAVAILABLE |
| bi-dataflow-optional-sinks | 0 / 20 | 0 | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE–UNAVAILABLE |
| bi-dependency-co-use | 0 / 20 | 0 | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE–UNAVAILABLE |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.311 |
| Load-only kernel peak RSS, MiB | 3,798.859 |
| Complete mixed pressure CPU user+system, seconds | UNAVAILABLE |
| Query-process lifetime CPU, seconds (wait4) | 250.302 |
| Query-process lifetime kernel peak RSS, MiB | 29,907.828 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

Darwin CPU snapshots above are **derived corrections**, reconstructed from original tick/1e9 fields using independently verified same-host Mach timebase 125/3. Raw reports, request times, wait4 CPU and kernel RSS were not changed. Full reconstruction and wait4 corroboration are retained in the audit JSON.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 138.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 20 / 20 | 0 | 2,753.068 | 5,835.735 | 2,495.155–6,288.885 |
| bi-global-target-support | 20 / 20 | 0 | 3,086.072 | 5,289.413 | 2,790.456–9,482.654 |
| bi-two-hop-dataflow | 20 / 20 | 0 | 4,131.888 | 4,688.115 | 4,037.084–4,794.966 |
| bi-dataflow-degree-histogram | 20 / 20 | 0 | 8,448.300 | 12,253.272 | 5,681.263–14,460.736 |
| bi-dataflow-optional-sinks | 20 / 20 | 0 | 14,237.488 | 20,374.777 | 10,662.687–23,483.253 |
| bi-dependency-co-use | 20 / 20 | 0 | 33,244.652 | 44,828.904 | 26,450.328–47,823.741 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 18.467 |
| Load-only kernel peak RSS, MiB | 3,794.156 |
| Complete mixed pressure CPU user+system, seconds | 1,185.129 |
| Query-process lifetime CPU, seconds (wait4) | 1,375.383 |
| Query-process lifetime kernel peak RSS, MiB | 32,085.484 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

Darwin CPU snapshots above are **derived corrections**, reconstructed from original tick/1e9 fields using independently verified same-host Mach timebase 125/3. Raw reports, request times, wait4 CPU and kernel RSS were not changed. Full reconstruction and wait4 corroboration are retained in the audit JSON.
