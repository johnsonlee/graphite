# Attempt318 dense pilot offline audit

Matched real64 AB pilot, 20 pressure requests per case. Full typed-body and resource audit is in `dense-pilot-audit.json`; all46 co-use body hashes and inferred dense-tier proof are in `dense-tier-proof.json`.

| Case | Baseline P95 ms | Candidate P95 ms | Delta ms |
| --- | ---: | ---: | ---: |
| bi-caller-dependency-rollup | 4353.696459 | 2505.795792 | -1847.900667 |
| bi-global-target-support | 4773.584500 | 2767.120958 | -2006.463542 |
| bi-two-hop-dataflow | 4226.244959 | 4075.309458 | -150.935501 |
| bi-dataflow-degree-histogram | 9861.021959 | 5670.554333 | -4190.467626 |
| bi-dataflow-optional-sinks | 16180.193667 | 10396.457958 | -5783.735709 |
| bi-dependency-co-use-dense-distinct | 41313.780250 | 2692.883500 | -38620.896750 |

| Resource | Base | Candidate | Delta | Within5% |
| --- | ---: | ---: | ---: | --- |
| loadingCpuSeconds | 17.434059 | 18.162500 | +0.728441 | True |
| loadingPeakRssBytes | 3978756096.000000 | 3987079168.000000 | +8323072.000000 | True |
| pressureMixedCpuSeconds | 1140.544925 | 550.634461 | -589.910464 | True |
| queryLifecyclePeakRssBytes | 36414455808.000000 | 28023980032.000000 | -8390475776.000000 | True |

Tier inferred from observed complete results plus fixed source admission; no runtime path instrumentation.
64 physical graphs with up to four logical buckets per graph; not more than 64 physical graphs.
Attempt318 skips the first high-provenance cancellation check; this real64 workload has no high provenance word and does not measure that changed loop.
One 20-request-per-case pilot is not a 100-request confirmation or universal performance acceptance.

The raw controller field `denseTierEvidence.tier: Dense` exists in both arms. It describes candidate representation eligibility from cardinality, not observed execution. Baseline316 falls back above64 values; only candidate318 has the inferred Dense path. Original raw evidence remains unchanged.

All276 pilot responses (12,381,360 body bytes) passed the independent full typed-body, receipt and journal audit. The separate six-response smoke also passed hash/receipt/journal checks and complete typed equality against both pilot arms (`dense-smoke-correctness-audit.json`); smoke timings are not performance evidence.
