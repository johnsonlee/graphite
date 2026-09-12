# DISTINCT application work within existing phase traces

Offline extraction only; no new capture or production change. Each phase summary reproduces the original analysis exactly. Application threads are the request worker and graphite cypher/callsite scan/segment workers; JIT and all other threads remain separate. Inclusive stack counts are not additive or latency fractions.

| Recording/query/phase | Application CPU samples | JIT | Other | Within raw DISTINCT projection | Within mapped matching-string discovery |
|---|---:|---:|---:|---:|---:|
| 1 / targeted / initial | 76 | 66 | 9 | 75 | 1 |
| 1 / dense / initial | 8 | 13 | 1 | 4 | 2 |
| 1 / dense / provenance | 123 | 100 | 0 | 53 | 69 |
| 2 / targeted / initial | 79 | 69 | 3 | 74 | 3 |
| 2 / dense / initial | 9 | 17 | 0 | 6 | 2 |
| 2 / dense / provenance | 87 | 67 | 1 | 46 | 33 |
| 3 / targeted / initial | 66 | 58 | 4 | 60 | 2 |
| 3 / dense / initial | 8 | 14 | 0 | 4 | 3 |
| 3 / dense / provenance | 80 | 73 | 4 | 28 | 49 |

Targeted initial selection has 75/76, 74/79 and 60/66 application samples inside raw projection. Dense provenance has two material paths: raw projection (53/123,46/87,28/80) and mapped matching-string discovery (69/123,33/87,49/80). No validator stack appears in these application CPU samples. This supports examining both initial sparse projection and provenance, and does not support carrying a cold validator-only gain into the warm global gate.

Application sample counts are small and recordings include method tracing. No precise speedup, causal attribution of concurrent JIT work, or accepted optimization follows from these percentages. Full leaf/inclusive frames and exact counts are retained in application-summary.json; the JFR, old summary and new summary identity receipts remain beside it.
