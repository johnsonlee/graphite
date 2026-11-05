# Local PR #154 benchmark (2026-11-05, Asia/Seoul)

Base `7c59d7993be51f0807495890d068effcfaff155b` → head `190eba79a4428d3100c8fba480bd858a19ac893d`. **Correctness and coverage: PASS** (15 core checks; no core errors; Apple full-response oracle, all scoped requests and parity passed). **Formal performance acceptance: not established.**

This is a local comparison of the unmodified trusted main controller plus a separate real Apple/JVM three-graph supplement. Measurements below are observations, not latency/CPU/RSS regression acceptance. All signs and absolute changes are retained; no favorable-case selection.

**Observed query regression in this run:** the five highest-baseline-p95 cases all have higher head p50 and p95. Their case-level absolute p50 increases range from 20.409 to 209.211 ms; p95 increases range from 31.255 to 387.365 ms. Tables below retain each case and its percentage change. These are observed increases; this single ordered pair cannot isolate code effects from interactive-machine noise. Construction/loading observations remain separate conclusions.

## Real64 server pressure

64 distinct persisted class shards from four pinned real JAR corpora (Android, Tika, Hive, Kotlin compiler). Every request uses the global graph scope. Concurrency 4; 20 measured requests per case after two warmups and one first-use oracle. Nearest-rank p50/p95 are computed per case through full HTTP body consumption, never pooled across cases. Fixed batches are not a saturation test.

Five cases selected by highest absolute baseline p95:

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | N base/head |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shape-order-by-desc | 1,936.861 | 2,146.073 | 209.211 | +10.80% | 2,063.579 | 2,441.624 | 378.045 | +18.32% | 20/20 |
| shape-order-by | 1,929.906 | 2,136.016 | 206.110 | +10.68% | 2,057.484 | 2,444.850 | 387.365 | +18.83% | 20/20 |
| shape-collect | 1,897.477 | 2,072.484 | 175.007 | +9.22% | 2,007.798 | 2,384.493 | 376.695 | +18.76% | 20/20 |
| shape-with-aggregate | 475.085 | 495.495 | 20.409 | +4.30% | 520.984 | 576.977 | 55.993 | +10.75% | 20/20 |
| shape-group-count | 300.978 | 327.934 | 26.955 | +8.96% | 399.176 | 430.431 | 31.255 | +7.83% | 20/20 |

<details>
<summary>All 73 pressure cases</summary>

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | N base/head |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| broad-all-64/global-wide-four-properties | 0.983 | 1.075 | 0.092 | +9.38% | 1.388 | 1.520 | 0.132 | +9.51% | 20/20 |
| dense/global-wide-aliased | 1.055 | 1.101 | 0.046 | +4.39% | 1.434 | 1.603 | 0.169 | +11.78% | 20/20 |
| dense/global-wide-callee-class | 0.946 | 1.019 | 0.072 | +7.65% | 1.501 | 1.417 | -0.084 | -5.58% | 20/20 |
| dense/global-wide-caller-class | 0.984 | 1.063 | 0.078 | +7.97% | 1.265 | 1.575 | 0.309 | +24.46% | 20/20 |
| dense/global-wide-class-pair | 1.048 | 1.072 | 0.024 | +2.28% | 1.453 | 1.259 | -0.194 | -13.37% | 20/20 |
| dense/global-wide-four-properties | 1.046 | 1.085 | 0.040 | +3.78% | 1.565 | 1.629 | 0.064 | +4.07% | 20/20 |
| dense/global-wide-name-pair | 0.985 | 1.046 | 0.061 | +6.23% | 1.345 | 1.507 | 0.162 | +12.03% | 20/20 |
| dense/global-wide-parameterized-as-literal | 1.077 | 1.101 | 0.024 | +2.23% | 1.336 | 1.503 | 0.167 | +12.53% | 20/20 |
| dense/global-wide-provenance | 1.021 | 1.082 | 0.062 | +6.05% | 1.303 | 1.635 | 0.333 | +25.53% | 20/20 |
| dense/global-wide-wrapped-case-insensitive | 1.190 | 1.241 | 0.052 | +4.33% | 1.402 | 1.805 | 0.403 | +28.72% | 20/20 |
| dense/global-wide-wrapped-case-insensitive-distinct | 3.183 | 3.421 | 0.238 | +7.48% | 3.590 | 4.232 | 0.642 | +17.88% | 20/20 |
| localized-early/global-wide-four-properties | 0.440 | 0.422 | -0.018 | -4.09% | 0.727 | 0.687 | -0.040 | -5.53% | 20/20 |
| localized-late/global-wide-four-properties | 0.576 | 0.621 | 0.044 | +7.71% | 0.806 | 0.880 | 0.074 | +9.12% | 20/20 |
| localized-middle/global-wide-four-properties | 0.578 | 0.610 | 0.032 | +5.46% | 1.086 | 0.800 | -0.286 | -26.31% | 20/20 |
| schema-key-histogram | 0.915 | 1.037 | 0.122 | +13.33% | 1.102 | 1.466 | 0.364 | +33.03% | 20/20 |
| schema-label-histogram | 0.545 | 0.602 | 0.058 | +10.59% | 0.632 | 0.787 | 0.155 | +24.61% | 20/20 |
| schema-relationship-histogram | 15.238 | 16.759 | 1.521 | +9.98% | 18.077 | 19.644 | 1.567 | +8.67% | 20/20 |
| shape-all-nodes | 0.724 | 0.827 | 0.104 | +14.33% | 1.436 | 1.344 | -0.092 | -6.41% | 20/20 |
| shape-cartesian | 0.655 | 0.676 | 0.021 | +3.19% | 0.722 | 0.765 | 0.043 | +6.02% | 20/20 |
| shape-case-expr | 0.475 | 0.494 | 0.019 | +4.07% | 0.522 | 0.541 | 0.019 | +3.59% | 20/20 |
| shape-coalesce | 0.484 | 0.486 | 0.002 | +0.46% | 0.550 | 0.605 | 0.055 | +9.92% | 20/20 |
| shape-collect | 1,897.477 | 2,072.484 | 175.007 | +9.22% | 2,007.798 | 2,384.493 | 376.695 | +18.76% | 20/20 |
| shape-count-all | 0.210 | 0.252 | 0.042 | +20.19% | 0.299 | 0.386 | 0.087 | +29.26% | 20/20 |
| shape-count-label | 0.194 | 0.226 | 0.032 | +16.48% | 0.324 | 0.395 | 0.071 | +21.92% | 20/20 |
| shape-count-traversal | 15.400 | 16.715 | 1.316 | +8.54% | 20.671 | 18.077 | -2.595 | -12.55% | 20/20 |
| shape-distinct | 272.278 | 287.873 | 15.594 | +5.73% | 315.101 | 361.279 | 46.177 | +14.65% | 20/20 |
| shape-equality | 0.559 | 0.595 | 0.036 | +6.43% | 0.776 | 0.741 | -0.035 | -4.53% | 20/20 |
| shape-function-calls | 0.577 | 0.584 | 0.007 | +1.23% | 0.670 | 0.679 | 0.009 | +1.34% | 20/20 |
| shape-group-count | 300.978 | 327.934 | 26.955 | +8.96% | 399.176 | 430.431 | 31.255 | +7.83% | 20/20 |
| shape-id-lookup | 0.481 | 0.542 | 0.061 | +12.75% | 0.693 | 0.722 | 0.029 | +4.19% | 20/20 |
| shape-in-list | 0.289 | 0.357 | 0.068 | +23.42% | 0.452 | 0.477 | 0.025 | +5.54% | 20/20 |
| shape-incoming | 0.429 | 0.511 | 0.082 | +19.00% | 0.831 | 0.679 | -0.152 | -18.28% | 20/20 |
| shape-inequality | 0.379 | 0.447 | 0.068 | +17.81% | 0.503 | 0.647 | 0.144 | +28.61% | 20/20 |
| shape-label-scan-callsite | 0.420 | 0.489 | 0.069 | +16.30% | 0.681 | 0.837 | 0.156 | +22.88% | 20/20 |
| shape-label-scan-constant | 0.389 | 0.412 | 0.024 | +6.10% | 1.026 | 0.649 | -0.376 | -36.68% | 20/20 |
| shape-label-scan-field | 0.372 | 0.397 | 0.026 | +6.89% | 0.953 | 0.640 | -0.313 | -32.80% | 20/20 |
| shape-label-scan-method | 0.398 | 0.444 | 0.046 | +11.44% | 0.710 | 0.738 | 0.028 | +3.94% | 20/20 |
| shape-labels-fn | 0.474 | 0.491 | 0.017 | +3.68% | 0.540 | 0.585 | 0.045 | +8.31% | 20/20 |
| shape-null-check | 0.362 | 0.444 | 0.082 | +22.64% | 0.651 | 0.725 | 0.074 | +11.44% | 20/20 |
| shape-one-hop | 0.450 | 0.545 | 0.095 | +21.19% | 0.667 | 0.821 | 0.155 | +23.18% | 20/20 |
| shape-one-hop-typed | 0.422 | 0.491 | 0.069 | +16.43% | 1.033 | 0.623 | -0.409 | -39.66% | 20/20 |
| shape-order-by | 1,929.906 | 2,136.016 | 206.110 | +10.68% | 2,057.484 | 2,444.850 | 387.365 | +18.83% | 20/20 |
| shape-order-by-desc | 1,936.861 | 2,146.073 | 209.211 | +10.80% | 2,063.579 | 2,441.624 | 378.045 | +18.32% | 20/20 |
| shape-path | 0.494 | 0.607 | 0.113 | +22.98% | 0.630 | 0.836 | 0.206 | +32.66% | 20/20 |
| shape-regex | 0.403 | 0.446 | 0.043 | +10.76% | 0.658 | 0.751 | 0.093 | +14.07% | 20/20 |
| shape-skip | 1.206 | 1.330 | 0.124 | +10.30% | 1.285 | 1.561 | 0.276 | +21.44% | 20/20 |
| shape-traverse-filtered | 0.651 | 0.799 | 0.148 | +22.74% | 0.835 | 1.027 | 0.193 | +23.06% | 20/20 |
| shape-two-hop | 0.413 | 0.508 | 0.095 | +23.09% | 0.862 | 0.677 | -0.185 | -21.43% | 20/20 |
| shape-union | 0.459 | 0.511 | 0.052 | +11.42% | 0.510 | 0.599 | 0.089 | +17.40% | 20/20 |
| shape-var-length | 0.368 | 0.446 | 0.078 | +21.09% | 0.540 | 0.606 | 0.066 | +12.15% | 20/20 |
| shape-wide-contains | 0.593 | 0.665 | 0.072 | +12.23% | 0.798 | 0.906 | 0.108 | +13.50% | 20/20 |
| shape-with-aggregate | 475.085 | 495.495 | 20.409 | +4.30% | 520.984 | 576.977 | 55.993 | +10.75% | 20/20 |
| shape-with-filter | 0.431 | 0.453 | 0.022 | +5.03% | 0.489 | 0.546 | 0.057 | +11.70% | 20/20 |
| targeted/global-wide-aliased | 0.405 | 0.434 | 0.029 | +7.23% | 0.526 | 0.599 | 0.073 | +13.91% | 20/20 |
| targeted/global-wide-callee-class | 0.401 | 0.444 | 0.042 | +10.56% | 0.558 | 0.613 | 0.055 | +9.93% | 20/20 |
| targeted/global-wide-caller-class | 0.416 | 0.433 | 0.017 | +3.99% | 0.521 | 0.606 | 0.085 | +16.40% | 20/20 |
| targeted/global-wide-class-pair | 0.436 | 0.469 | 0.034 | +7.75% | 0.574 | 0.652 | 0.078 | +13.61% | 20/20 |
| targeted/global-wide-four-properties | 0.572 | 0.585 | 0.013 | +2.22% | 0.696 | 0.694 | -0.002 | -0.30% | 20/20 |
| targeted/global-wide-name-pair | 0.424 | 0.461 | 0.037 | +8.63% | 0.581 | 0.618 | 0.037 | +6.38% | 20/20 |
| targeted/global-wide-parameterized-as-literal | 0.398 | 0.418 | 0.019 | +4.80% | 0.568 | 0.577 | 0.009 | +1.61% | 20/20 |
| targeted/global-wide-provenance | 0.417 | 0.406 | -0.010 | -2.50% | 0.521 | 0.632 | 0.111 | +21.37% | 20/20 |
| targeted/global-wide-wrapped-case-insensitive | 0.407 | 0.415 | 0.007 | +1.77% | 0.480 | 0.611 | 0.131 | +27.32% | 20/20 |
| targeted/global-wide-wrapped-case-insensitive-distinct | 0.461 | 0.463 | 0.002 | +0.43% | 0.545 | 0.699 | 0.154 | +28.16% | 20/20 |
| zero/global-wide-aliased | 0.263 | 0.293 | 0.030 | +11.44% | 0.413 | 0.523 | 0.110 | +26.52% | 20/20 |
| zero/global-wide-callee-class | 0.278 | 0.280 | 0.002 | +0.78% | 0.359 | 0.399 | 0.040 | +11.06% | 20/20 |
| zero/global-wide-caller-class | 0.290 | 0.303 | 0.013 | +4.52% | 0.361 | 0.547 | 0.186 | +51.56% | 20/20 |
| zero/global-wide-class-pair | 0.311 | 0.344 | 0.033 | +10.53% | 0.421 | 0.503 | 0.082 | +19.43% | 20/20 |
| zero/global-wide-four-properties | 0.362 | 0.381 | 0.020 | +5.41% | 0.451 | 0.512 | 0.061 | +13.51% | 20/20 |
| zero/global-wide-name-pair | 0.274 | 0.299 | 0.026 | +9.43% | 0.385 | 0.428 | 0.043 | +11.09% | 20/20 |
| zero/global-wide-parameterized-as-literal | 0.239 | 0.286 | 0.047 | +19.47% | 0.359 | 0.411 | 0.052 | +14.46% | 20/20 |
| zero/global-wide-provenance | 0.252 | 0.298 | 0.046 | +18.20% | 0.344 | 0.415 | 0.071 | +20.75% | 20/20 |
| zero/global-wide-wrapped-case-insensitive | 0.238 | 0.278 | 0.040 | +16.98% | 0.336 | 0.460 | 0.124 | +36.88% | 20/20 |
| zero/global-wide-wrapped-case-insensitive-distinct | 0.291 | 0.304 | 0.013 | +4.55% | 0.378 | 0.461 | 0.083 | +22.03% | 20/20 |

</details>

Head observations are higher in 71/73 case p50 values and 60/73 case p95 values; these counts do not establish statistical significance or recovery.

<details>
<summary>Separate isolated observations (not replacements for pressure)</summary>

Each arm independently selects cases whose pressure p50 is below 100 ms, then takes 40 requests at concurrency 1. In this run, 67 cases were selected in both arms and all scheduled samples are present. The six slow cases were not scheduled by the predeclared rule; their isolated UNAVAILABLE values mean not scheduled. UNAVAILABLE is not zero. These short-case observations are secondary and do not select a performance candidate.

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | N base/head |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| broad-all-64/global-wide-four-properties | 0.954 | 0.997 | 0.043 | +4.47% | 1.048 | 1.134 | 0.085 | +8.13% | 40/40 |
| dense/global-wide-aliased | 1.049 | 0.970 | -0.079 | -7.56% | 1.323 | 1.129 | -0.194 | -14.65% | 40/40 |
| dense/global-wide-callee-class | 0.895 | 0.964 | 0.069 | +7.66% | 1.099 | 1.089 | -0.009 | -0.83% | 40/40 |
| dense/global-wide-caller-class | 0.999 | 0.917 | -0.081 | -8.14% | 1.192 | 1.096 | -0.096 | -8.03% | 40/40 |
| dense/global-wide-class-pair | 1.181 | 0.982 | -0.199 | -16.84% | 1.631 | 1.125 | -0.506 | -31.05% | 40/40 |
| dense/global-wide-four-properties | 1.298 | 1.026 | -0.272 | -20.99% | 2.710 | 1.258 | -1.452 | -53.57% | 40/40 |
| dense/global-wide-name-pair | 1.074 | 0.994 | -0.080 | -7.46% | 1.263 | 1.122 | -0.141 | -11.15% | 40/40 |
| dense/global-wide-parameterized-as-literal | 0.948 | 0.957 | 0.009 | +0.98% | 1.194 | 1.116 | -0.078 | -6.57% | 40/40 |
| dense/global-wide-provenance | 0.982 | 0.978 | -0.003 | -0.31% | 1.179 | 1.101 | -0.077 | -6.57% | 40/40 |
| dense/global-wide-wrapped-case-insensitive | 1.107 | 1.107 | -0.000 | -0.00% | 1.245 | 1.290 | 0.045 | +3.61% | 40/40 |
| dense/global-wide-wrapped-case-insensitive-distinct | 2.824 | 2.839 | 0.015 | +0.52% | 3.208 | 3.115 | -0.093 | -2.89% | 40/40 |
| localized-early/global-wide-four-properties | 0.311 | 0.325 | 0.014 | +4.62% | 0.353 | 0.417 | 0.064 | +18.12% | 40/40 |
| localized-late/global-wide-four-properties | 0.565 | 0.548 | -0.017 | -2.97% | 0.683 | 0.663 | -0.020 | -2.98% | 40/40 |
| localized-middle/global-wide-four-properties | 0.523 | 0.571 | 0.048 | +9.16% | 0.603 | 0.654 | 0.050 | +8.36% | 40/40 |
| schema-key-histogram | 0.874 | 0.934 | 0.061 | +6.95% | 0.927 | 1.036 | 0.108 | +11.68% | 40/40 |
| schema-label-histogram | 0.470 | 0.474 | 0.005 | +0.98% | 0.510 | 0.605 | 0.095 | +18.68% | 40/40 |
| schema-relationship-histogram | 13.439 | 19.345 | 5.906 | +43.95% | 14.662 | 22.792 | 8.130 | +55.45% | 40/40 |
| shape-all-nodes | 0.649 | 0.644 | -0.005 | -0.83% | 0.804 | 0.792 | -0.012 | -1.47% | 40/40 |
| shape-cartesian | 0.208 | 0.206 | -0.002 | -0.80% | 0.226 | 0.300 | 0.075 | +33.23% | 40/40 |
| shape-case-expr | 0.309 | 0.347 | 0.038 | +12.28% | 0.398 | 0.423 | 0.025 | +6.18% | 40/40 |
| shape-coalesce | 0.315 | 0.326 | 0.011 | +3.46% | 0.345 | 0.432 | 0.086 | +24.96% | 40/40 |
| shape-collect | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | 0/0 |
| shape-count-all | 0.190 | 0.175 | -0.015 | -7.71% | 0.266 | 0.211 | -0.055 | -20.59% | 40/40 |
| shape-count-label | 0.176 | 0.171 | -0.005 | -2.63% | 0.195 | 0.201 | 0.006 | +3.29% | 40/40 |
| shape-count-traversal | 13.300 | 15.603 | 2.303 | +17.32% | 14.531 | 20.926 | 6.395 | +44.01% | 40/40 |
| shape-distinct | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | 0/0 |
| shape-equality | 0.485 | 0.452 | -0.034 | -6.93% | 0.566 | 0.522 | -0.044 | -7.76% | 40/40 |
| shape-function-calls | 0.369 | 0.418 | 0.049 | +13.34% | 0.409 | 0.564 | 0.156 | +38.12% | 40/40 |
| shape-group-count | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | 0/0 |
| shape-id-lookup | 0.226 | 0.208 | -0.018 | -8.10% | 0.349 | 0.225 | -0.124 | -35.64% | 40/40 |
| shape-in-list | 0.251 | 0.235 | -0.016 | -6.34% | 0.398 | 0.310 | -0.088 | -22.18% | 40/40 |
| shape-incoming | 0.407 | 0.513 | 0.106 | +26.14% | 0.455 | 0.683 | 0.228 | +50.00% | 40/40 |
| shape-inequality | 0.347 | 0.336 | -0.011 | -3.06% | 0.434 | 0.448 | 0.014 | +3.22% | 40/40 |
| shape-label-scan-callsite | 0.380 | 0.418 | 0.038 | +10.10% | 0.451 | 0.516 | 0.065 | +14.41% | 40/40 |
| shape-label-scan-constant | 0.324 | 0.312 | -0.012 | -3.76% | 0.401 | 0.330 | -0.071 | -17.59% | 40/40 |
| shape-label-scan-field | 0.318 | 0.308 | -0.010 | -3.19% | 0.386 | 0.335 | -0.051 | -13.20% | 40/40 |
| shape-label-scan-method | 0.346 | 0.360 | 0.014 | +4.01% | 0.437 | 0.463 | 0.025 | +5.80% | 40/40 |
| shape-labels-fn | 0.324 | 0.323 | -0.001 | -0.31% | 0.345 | 0.350 | 0.005 | +1.36% | 40/40 |
| shape-null-check | 0.420 | 0.331 | -0.090 | -21.34% | 0.473 | 0.363 | -0.110 | -23.26% | 40/40 |
| shape-one-hop | 0.426 | 0.412 | -0.014 | -3.31% | 0.503 | 0.434 | -0.070 | -13.84% | 40/40 |
| shape-one-hop-typed | 0.372 | 0.385 | 0.012 | +3.26% | 0.393 | 0.502 | 0.109 | +27.85% | 40/40 |
| shape-order-by | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | 0/0 |
| shape-order-by-desc | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | 0/0 |
| shape-path | 0.449 | 0.460 | 0.011 | +2.35% | 0.488 | 0.513 | 0.026 | +5.28% | 40/40 |
| shape-regex | 0.293 | 0.290 | -0.003 | -1.05% | 0.356 | 0.354 | -0.002 | -0.52% | 40/40 |
| shape-skip | 0.759 | 1.098 | 0.339 | +44.71% | 0.917 | 1.463 | 0.546 | +59.56% | 40/40 |
| shape-traverse-filtered | 0.611 | 0.614 | 0.003 | +0.53% | 0.669 | 0.709 | 0.041 | +6.10% | 40/40 |
| shape-two-hop | 0.391 | 0.405 | 0.014 | +3.57% | 0.410 | 0.536 | 0.126 | +30.63% | 40/40 |
| shape-union | 0.287 | 0.282 | -0.004 | -1.54% | 0.301 | 0.360 | 0.059 | +19.62% | 40/40 |
| shape-var-length | 0.333 | 0.364 | 0.031 | +9.24% | 0.361 | 0.395 | 0.035 | +9.60% | 40/40 |
| shape-wide-contains | 0.339 | 0.449 | 0.111 | +32.69% | 0.458 | 0.642 | 0.184 | +40.26% | 40/40 |
| shape-with-aggregate | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | 0/0 |
| shape-with-filter | 0.334 | 0.308 | -0.026 | -7.79% | 0.423 | 0.359 | -0.064 | -15.12% | 40/40 |
| targeted/global-wide-aliased | 0.574 | 0.585 | 0.011 | +1.87% | 0.909 | 0.656 | -0.254 | -27.91% | 40/40 |
| targeted/global-wide-callee-class | 0.600 | 0.657 | 0.057 | +9.45% | 0.990 | 0.874 | -0.115 | -11.66% | 40/40 |
| targeted/global-wide-caller-class | 0.604 | 0.563 | -0.041 | -6.76% | 0.901 | 1.022 | 0.120 | +13.37% | 40/40 |
| targeted/global-wide-class-pair | 0.480 | 0.584 | 0.104 | +21.58% | 1.919 | 0.882 | -1.038 | -54.06% | 40/40 |
| targeted/global-wide-four-properties | 0.522 | 0.548 | 0.026 | +4.97% | 0.861 | 0.931 | 0.070 | +8.10% | 40/40 |
| targeted/global-wide-name-pair | 0.491 | 0.689 | 0.198 | +40.34% | 0.932 | 1.250 | 0.319 | +34.19% | 40/40 |
| targeted/global-wide-parameterized-as-literal | 0.580 | 0.467 | -0.113 | -19.47% | 1.155 | 0.519 | -0.637 | -55.11% | 40/40 |
| targeted/global-wide-provenance | 0.564 | 0.602 | 0.038 | +6.81% | 0.889 | 0.829 | -0.060 | -6.73% | 40/40 |
| targeted/global-wide-wrapped-case-insensitive | 0.612 | 0.459 | -0.153 | -25.05% | 0.839 | 0.572 | -0.267 | -31.84% | 40/40 |
| targeted/global-wide-wrapped-case-insensitive-distinct | 0.561 | 0.521 | -0.040 | -7.12% | 1.059 | 0.620 | -0.440 | -41.52% | 40/40 |
| zero/global-wide-aliased | 0.457 | 0.311 | -0.145 | -31.80% | 0.809 | 0.553 | -0.255 | -31.58% | 40/40 |
| zero/global-wide-callee-class | 0.190 | 0.212 | 0.022 | +11.35% | 0.246 | 0.648 | 0.402 | +163.80% | 40/40 |
| zero/global-wide-caller-class | 0.178 | 0.305 | 0.128 | +71.71% | 0.241 | 0.787 | 0.546 | +226.98% | 40/40 |
| zero/global-wide-class-pair | 0.186 | 0.269 | 0.083 | +44.52% | 0.219 | 0.687 | 0.468 | +213.81% | 40/40 |
| zero/global-wide-four-properties | 0.214 | 0.350 | 0.136 | +63.30% | 0.339 | 0.825 | 0.486 | +143.31% | 40/40 |
| zero/global-wide-name-pair | 0.179 | 0.287 | 0.108 | +60.58% | 0.206 | 0.353 | 0.147 | +71.28% | 40/40 |
| zero/global-wide-parameterized-as-literal | 0.502 | 0.222 | -0.280 | -55.71% | 0.871 | 0.459 | -0.413 | -47.35% | 40/40 |
| zero/global-wide-provenance | 0.286 | 0.183 | -0.104 | -36.24% | 0.326 | 0.634 | 0.308 | +94.66% | 40/40 |
| zero/global-wide-wrapped-case-insensitive | 0.333 | 0.301 | -0.032 | -9.53% | 0.545 | 0.335 | -0.210 | -38.55% | 40/40 |
| zero/global-wide-wrapped-case-insensitive-distinct | 0.331 | 0.383 | 0.053 | +15.98% | 0.754 | 0.593 | -0.161 | -21.35% | 40/40 |

</details>

## Core construction and loading

| Boundary | Base ms | Head ms | Δ ms | N base/head |
| --- | --- | --- | --- | --- |
| real64-build-save / frontend-build-save | 280,728.161 | 266,061.404 | -14,666.757 | 1/1 |
| real64-loading / registry-ready | 20,095.892 | 19,637.295 | -458.597 | 1/1 |
| real64-loading / startup-through-first-use-oracle | 22,414.732 | 22,378.459 | -36.272 | 1/1 |

Construction runs through usable saved graphs. Loading is shown separately through registry readiness and through the complete first-use oracle, exposing deferred work. These N=1 boundaries are report-only. Core process CPU and peak RSS were not collected by this protocol; no resource recovery claim is possible.

## Apple/JVM three-graph supplement

Each request explicitly spans Signal iOS, SwiftPM and Signal Server. Four cases cover calls, literal-to-call provenance, field types and same wire literals on different graphs. The independent oracle saved full responses twice per case/arm and proved at least two represented graphs, exact typed-result parity and cross-graph wire pairs. All 4800 measured and 120 warmup requests succeeded. Each table arm has 300 measured requests per case/concurrency, across three paired AB/BA/AB rounds; five warmups per case precede measured batches. Percentiles use nearest rank; ranges are the three round p95 values.

| Case | C | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 range | Head round p95 range |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| network-message-call-scan | 1 | 6.451 | 6.808 | 0.357 | +5.54% | 7.470 | 9.548 | 2.079 | +27.83% | 7.113–7.625 | 7.587–10.669 |
| network-message-call-scan | 4 | 7.247 | 7.193 | -0.054 | -0.74% | 9.031 | 8.781 | -0.249 | -2.76% | 8.688–9.096 | 8.396–8.798 |
| literal-call-provenance | 1 | 21.013 | 21.087 | 0.074 | +0.35% | 28.429 | 24.799 | -3.630 | -12.77% | 22.894–30.447 | 23.853–25.082 |
| literal-call-provenance | 4 | 22.734 | 23.000 | 0.266 | +1.17% | 25.294 | 25.854 | 0.560 | +2.21% | 24.762–26.206 | 24.871–26.306 |
| field-type-inventory | 1 | 18.239 | 18.919 | 0.680 | +3.73% | 19.798 | 22.462 | 2.664 | +13.46% | 19.116–19.884 | 20.168–23.839 |
| field-type-inventory | 4 | 20.289 | 20.284 | -0.006 | -0.03% | 21.560 | 21.574 | 0.014 | +0.07% | 21.329–21.891 | 21.413–21.661 |
| shared-wire-literals-prefiltered | 1 | 602.872 | 606.902 | 4.030 | +0.67% | 700.651 | 708.557 | 7.906 | +1.13% | 684.066–711.257 | 695.388–715.654 |
| shared-wire-literals-prefiltered | 4 | 614.890 | 615.979 | 1.089 | +0.18% | 693.455 | 697.630 | 4.176 | +0.60% | 682.265–710.026 | 684.053–711.059 |

Server CPU per completed request (batch user+system delta; separate from response latency):

| Case | C | Base CPU ms/req | Head CPU ms/req | Δ CPU ms/req | Δ CPU % | N base/head |
| --- | --- | --- | --- | --- | --- | --- |
| network-message-call-scan | 1 | 6.419 | 7.019 | 0.600 | +9.34% | 300/300 |
| network-message-call-scan | 4 | 7.268 | 7.161 | -0.107 | -1.47% | 300/300 |
| literal-call-provenance | 1 | 21.528 | 21.160 | -0.368 | -1.71% | 300/300 |
| literal-call-provenance | 4 | 21.704 | 21.884 | 0.180 | +0.83% | 300/300 |
| field-type-inventory | 1 | 17.982 | 18.970 | 0.988 | +5.49% | 300/300 |
| field-type-inventory | 4 | 19.176 | 19.146 | -0.030 | -0.15% | 300/300 |
| shared-wire-literals-prefiltered | 1 | 610.932 | 615.364 | 4.432 | +0.73% | 300/300 |
| shared-wire-literals-prefiltered | 4 | 619.182 | 619.847 | 0.666 | +0.11% | 300/300 |

Apple load ends after readiness and consumption of all four cases, including deferred work:

| Arm | Rounds | Load wall range ms | Median wall ms | Boundary CPU range ms |
| --- | --- | --- | --- | --- |
| baseline | 3 | 828.516–894.175 | 840.941 | 786.908–805.347 |
| candidate | 3 | 825.616–828.464 | 826.198 | 795.001–810.425 |

Process lifetime resource accounting is separate:

| Arm | Operation | Processes | Peak RSS range MiB | Lifetime CPU range ms |
| --- | --- | --- | --- | --- |
| baseline | load | 3 | 139.969–140.062 | 787.222–805.674 |
| baseline | query | 3 | 238.906–277.672 | 135,334.063–136,424.894 |
| candidate | load | 3 | 140.016–140.062 | 795.222–810.742 |
| candidate | query | 3 | 252.625–255.219 | 136,137.142–137,213.633 |

Query CPU ms/request is the sum of live server user+system deltas over the three 100-request batches divided by 300. macOS Mach ticks are converted using mach_timebase_info; Linux uses /proc clock ticks. wait4 peak process RSS includes startup and warmup, requests and shutdown: it is not query-only RSS. Periodic current-RSS samples are only lower bounds. Load boundary CPU differs deliberately from full process-lifetime CPU.

Connection/readiness retries retained: load 18, query 18. These are startup polling attempts, separate from the zero failures in measured, warmup and load-consumption requests. No failed journal operation or incomplete server lifecycle was accepted.

## Reproduction and limitations

Hardware: Apple M3 Max, 16 logical CPUs, 64 GiB RAM; macOS-14.3-arm64-arm-64bit-Mach-O. Java: openjdk version "17.0.20.1" 2026-08-18. Sanitized details: [environment.json](environment.json).

Trusted controller source is base 7c59d799; product source revisions are the exact base/head above. The main pipeline uses locked release native builds and fresh real64 construction. JVM producers/forks use G1 with a 4 GiB maximum heap and ExitOnOutOfMemoryError (below the 8 GiB ceiling). macOS lacks Linux cgroup OOM counters; process survival and JVM receipts are checked, but no Linux host-OOM claim is made. Input hashes and product identities are retained in the core report.

The initial Git-worktree preparation failed in Gradle/JGit before measurement; original run/ evidence is retained. The successful run-standalone/ uses standalone exact-commit source checkouts. No failed preparation sample is treated as a measurement.

The supplementary harness is frozen in apple/apple-lifecycle.py; config, PROTOCOL.md, provenance.json and oracle.py describe the exact commands. Oracle → load → query must execute after the core pipeline is idle. No cache eviction was performed; filesystem cache is warm/uncontrolled and ambient workstation load is not assumed absent.

Both supplementary arms use the same final Signal persisted graph (packed SHA 31788b41dc78ae25c46f2614125254bc5dfeb934d794aa51964b277ed0a2eeaf), produced from the unchanged Swift IR and repaired fc88002a JVM consumer, with full 560895-node/199849-edge/enum-metadata roundtrip verification. The older 2881-consumer graph is excluded. Signal includes five explicitly accepted dictionary-valued plist omissions. SwiftPM and Signal Server are pinned real-source legacy graphs, not rebuilt by the current head; SwiftPM legacy importer SHA was not recorded in its import log. Their actual persisted file hashes are retained. This isolates native consumers and does not establish Apple construction performance or current producer completeness.

All raw results, errors, complete response oracles and commands are retained in the downloadable evidence archive. Public environment output intentionally excludes the process list and private application names. No thresholds were relaxed and no formal performance acceptance is claimed.

Evidence: [download and integrity instructions](DOWNLOAD.md), [core report](core-report.json), [independent core sample verification](core-sample-verification.json), [Apple full-response oracle](apple-oracle.json), [Apple query summary](apple-query-summary.json), [Apple load summary](apple-load-summary.json).

The trusted [benchmark protocol](https://github.com/johnsonlee/graphite/blob/7c59d7993be51f0807495890d068effcfaff155b/benchmark/README.md), [native request catalogue](https://github.com/johnsonlee/graphite/blob/7c59d7993be51f0807495890d068effcfaff155b/benchmark/src/native_queries.json), and [fixture preparation](https://github.com/johnsonlee/graphite/blob/7c59d7993be51f0807495890d068effcfaff155b/benchmark/src/prepare.rs) are pinned to the exact base commit. Source JAR hashes are retained in core-report.json; Apple source commits, graph producers and query text are in the archive’s apple/provenance.json and apple/config.json.

Reproduction uses Java 17 and independent standalone clones (not Git worktrees). Choose a fresh working directory and fresh output directories. On macOS, set JAVA_HOME to the installed JDK 17 before running:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$JAVA_HOME/bin:$PATH"
for role in base-standalone head-standalone trusted; do
  git clone https://github.com/johnsonlee/graphite.git "$role"
done
git -C base-standalone checkout --detach 7c59d7993be51f0807495890d068effcfaff155b
git -C head-standalone checkout --detach 190eba79a4428d3100c8fba480bd858a19ac893d
git -C trusted checkout --detach 7c59d7993be51f0807495890d068effcfaff155b
cargo build --release --locked --manifest-path trusted/Cargo.toml \
  --target-dir controller-target -p graphite-benchmark
controller-target/release/graphite-benchmark run \
  --base-source base-standalone --head-source head-standalone \
  --harness-source trusted --output run-standalone
# After the core pipeline stops, use the archived Apple scripts/config.
# Rebind graph and prepared-binary paths to the reproduced local artifacts.
cd apple
python3 oracle.py config.json --out oracle
python3 apple-lifecycle.py load config.json --out load
python3 apple-lifecycle.py query config.json --out query
cd ..
python3 render-report.py
```

Java 17 is required for these recorded builds. The evidence archive supplies exact Apple config paths and source identities, not the large graph and binary payloads that remain local; recreate or provide the same verified graph inputs before replay. Rendering does not execute any benchmark. Percent changes are UNAVAILABLE for a zero or missing baseline; absolute changes remain primary.
