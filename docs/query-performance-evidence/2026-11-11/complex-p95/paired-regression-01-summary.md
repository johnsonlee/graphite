# Independent complex-query evidence audit

Performance acceptance: **not established**. P95 values are per-case nearest ranks, not pooled query distributions.

## baseline

Audit valid: `True`; workload complete: `True`; issued requests: 4599.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| zero/global-wide-four-properties | 20 / 20 | 0 | 0.193 | 0.282 | 0.182–0.728 |
| zero/global-wide-class-pair | 20 / 20 | 0 | 0.187 | 0.211 | 0.159–0.760 |
| zero/global-wide-name-pair | 20 / 20 | 0 | 0.175 | 0.194 | 0.165–0.717 |
| zero/global-wide-caller-class | 20 / 20 | 0 | 0.186 | 0.208 | 0.154–0.755 |
| zero/global-wide-callee-class | 20 / 20 | 0 | 0.175 | 0.236 | 0.162–0.490 |
| zero/global-wide-provenance | 20 / 20 | 0 | 0.182 | 0.194 | 0.162–0.491 |
| zero/global-wide-aliased | 20 / 20 | 0 | 0.178 | 0.212 | 0.158–0.464 |
| zero/global-wide-parameterized-as-literal | 20 / 20 | 0 | 0.174 | 0.216 | 0.155–0.450 |
| zero/global-wide-wrapped-case-insensitive | 20 / 20 | 0 | 0.179 | 0.199 | 0.163–0.482 |
| zero/global-wide-wrapped-case-insensitive-distinct | 20 / 20 | 0 | 0.210 | 0.239 | 0.190–0.515 |
| targeted/global-wide-four-properties | 20 / 20 | 0 | 0.494 | 0.555 | 0.437–0.839 |
| targeted/global-wide-class-pair | 20 / 20 | 0 | 0.433 | 0.551 | 0.303–0.816 |
| targeted/global-wide-name-pair | 20 / 20 | 0 | 0.435 | 0.537 | 0.379–0.740 |
| targeted/global-wide-caller-class | 20 / 20 | 0 | 0.449 | 0.513 | 0.318–0.657 |
| targeted/global-wide-callee-class | 20 / 20 | 0 | 0.444 | 0.552 | 0.384–0.704 |
| targeted/global-wide-provenance | 20 / 20 | 0 | 0.446 | 0.593 | 0.359–0.649 |
| targeted/global-wide-aliased | 20 / 20 | 0 | 0.447 | 0.526 | 0.376–0.617 |
| targeted/global-wide-parameterized-as-literal | 20 / 20 | 0 | 0.423 | 0.511 | 0.305–0.555 |
| targeted/global-wide-wrapped-case-insensitive | 20 / 20 | 0 | 0.449 | 0.580 | 0.319–1.400 |
| targeted/global-wide-wrapped-case-insensitive-distinct | 20 / 20 | 0 | 0.466 | 0.559 | 0.368–1.176 |
| dense/global-wide-four-properties | 20 / 20 | 0 | 1.131 | 1.335 | 0.927–1.714 |
| dense/global-wide-class-pair | 20 / 20 | 0 | 1.010 | 1.223 | 0.758–1.723 |
| dense/global-wide-name-pair | 20 / 20 | 0 | 1.052 | 1.210 | 0.884–1.259 |
| dense/global-wide-caller-class | 20 / 20 | 0 | 0.864 | 1.117 | 0.717–1.164 |
| dense/global-wide-callee-class | 20 / 20 | 0 | 0.990 | 1.121 | 0.856–1.155 |
| dense/global-wide-provenance | 20 / 20 | 0 | 0.958 | 1.233 | 0.830–1.251 |
| dense/global-wide-aliased | 20 / 20 | 0 | 1.123 | 1.335 | 0.936–1.451 |
| dense/global-wide-parameterized-as-literal | 20 / 20 | 0 | 0.898 | 1.195 | 0.775–1.241 |
| dense/global-wide-wrapped-case-insensitive | 20 / 20 | 0 | 1.214 | 1.322 | 1.009–1.466 |
| dense/global-wide-wrapped-case-insensitive-distinct | 20 / 20 | 0 | 3.147 | 3.470 | 2.908–3.482 |
| localized-early/global-wide-four-properties | 20 / 20 | 0 | 0.368 | 0.397 | 0.338–0.513 |
| localized-middle/global-wide-four-properties | 20 / 20 | 0 | 0.574 | 0.669 | 0.509–0.704 |
| localized-late/global-wide-four-properties | 20 / 20 | 0 | 0.593 | 0.674 | 0.531–0.701 |
| broad-all-64/global-wide-four-properties | 20 / 20 | 0 | 0.985 | 1.224 | 0.916–1.230 |
| schema-label-histogram | 20 / 20 | 0 | 0.524 | 0.709 | 0.482–0.723 |
| schema-key-histogram | 20 / 20 | 0 | 1.992 | 3.217 | 0.950–3.474 |
| schema-relationship-histogram | 20 / 20 | 0 | 14.552 | 15.198 | 14.203–15.275 |
| shape-wide-contains | 20 / 20 | 0 | 1.535 | 2.431 | 0.521–2.491 |
| shape-label-scan-callsite | 20 / 20 | 0 | 1.303 | 2.768 | 0.773–3.061 |
| shape-label-scan-method | 20 / 20 | 0 | 1.434 | 2.317 | 0.825–2.517 |
| shape-label-scan-constant | 20 / 20 | 0 | 0.972 | 1.972 | 0.661–2.013 |
| shape-label-scan-field | 20 / 20 | 0 | 0.895 | 1.350 | 0.387–1.406 |
| shape-all-nodes | 20 / 20 | 0 | 0.901 | 1.607 | 0.680–1.795 |
| shape-equality | 20 / 20 | 0 | 0.530 | 1.344 | 0.417–6.655 |
| shape-inequality | 20 / 20 | 0 | 0.352 | 0.444 | 0.317–1.057 |
| shape-id-lookup | 20 / 20 | 0 | 0.469 | 0.579 | 0.422–1.257 |
| shape-in-list | 20 / 20 | 0 | 0.290 | 0.413 | 0.231–1.126 |
| shape-null-check | 20 / 20 | 0 | 0.334 | 0.411 | 0.307–1.328 |
| shape-regex | 20 / 20 | 0 | 0.370 | 0.475 | 0.332–0.821 |
| shape-one-hop | 20 / 20 | 0 | 0.414 | 0.465 | 0.383–0.985 |
| shape-one-hop-typed | 20 / 20 | 0 | 0.377 | 0.429 | 0.360–1.127 |
| shape-two-hop | 20 / 20 | 0 | 0.394 | 0.455 | 0.370–0.781 |
| shape-incoming | 20 / 20 | 0 | 0.392 | 0.444 | 0.359–0.511 |
| shape-var-length | 20 / 20 | 0 | 0.325 | 0.377 | 0.307–0.394 |
| shape-path | 20 / 20 | 0 | 0.479 | 0.549 | 0.429–0.600 |
| shape-traverse-filtered | 20 / 20 | 0 | 0.630 | 0.681 | 0.544–0.700 |
| shape-count-all | 20 / 20 | 0 | 0.225 | 0.275 | 0.168–0.292 |
| shape-count-label | 20 / 20 | 0 | 0.166 | 0.200 | 0.145–0.205 |
| shape-group-count | 20 / 20 | 0 | 42.884 | 46.011 | 38.503–47.412 |
| shape-distinct | 20 / 20 | 0 | 6.233 | 8.040 | 5.286–8.982 |
| shape-collect | 20 / 20 | 0 | 0.180 | 0.226 | 0.168–0.236 |
| shape-count-traversal | 20 / 20 | 0 | 14.162 | 14.653 | 13.294–14.658 |
| shape-order-by | 20 / 20 | 0 | 1.155 | 2.701 | 0.817–2.732 |
| shape-order-by-desc | 20 / 20 | 0 | 1.200 | 2.160 | 0.531–2.373 |
| shape-skip | 20 / 20 | 0 | 1.360 | 2.141 | 0.749–3.543 |
| shape-function-calls | 20 / 20 | 0 | 1.528 | 2.201 | 0.822–2.531 |
| shape-case-expr | 20 / 20 | 0 | 0.746 | 1.174 | 0.292–1.967 |
| shape-coalesce | 20 / 20 | 0 | 0.652 | 1.666 | 0.528–1.765 |
| shape-labels-fn | 20 / 20 | 0 | 0.622 | 1.177 | 0.554–1.722 |
| shape-with-filter | 20 / 20 | 0 | 0.358 | 0.611 | 0.297–0.640 |
| shape-with-aggregate | 20 / 20 | 0 | 42.526 | 48.694 | 32.448–50.497 |
| shape-cartesian | 20 / 20 | 0 | 0.197 | 0.311 | 0.177–0.424 |
| shape-union | 20 / 20 | 0 | 0.276 | 0.342 | 0.257–0.347 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.948 |
| Load-only kernel peak RSS, MiB | 3,796.969 |
| Complete mixed pressure CPU user+system, seconds | 10.290 |
| Query-process lifetime CPU, seconds (wait4) | 50.147 |
| Query-process lifetime kernel peak RSS, MiB | 4,998.859 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## candidate

Audit valid: `True`; workload complete: `True`; issued requests: 4599.

| Case | pressure samples / expected | failures | p50 ms | p95 ms | min–max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| zero/global-wide-four-properties | 20 / 20 | 0 | 0.206 | 0.232 | 0.186–0.734 |
| zero/global-wide-class-pair | 20 / 20 | 0 | 0.190 | 0.212 | 0.163–0.681 |
| zero/global-wide-name-pair | 20 / 20 | 0 | 0.183 | 0.206 | 0.170–0.709 |
| zero/global-wide-caller-class | 20 / 20 | 0 | 0.181 | 0.219 | 0.172–0.708 |
| zero/global-wide-callee-class | 20 / 20 | 0 | 0.177 | 0.231 | 0.164–0.425 |
| zero/global-wide-provenance | 20 / 20 | 0 | 0.180 | 0.217 | 0.159–0.405 |
| zero/global-wide-aliased | 20 / 20 | 0 | 0.176 | 0.205 | 0.163–0.449 |
| zero/global-wide-parameterized-as-literal | 20 / 20 | 0 | 0.180 | 0.214 | 0.166–0.430 |
| zero/global-wide-wrapped-case-insensitive | 20 / 20 | 0 | 0.182 | 0.205 | 0.164–0.490 |
| zero/global-wide-wrapped-case-insensitive-distinct | 20 / 20 | 0 | 0.218 | 0.251 | 0.190–0.455 |
| targeted/global-wide-four-properties | 20 / 20 | 0 | 0.505 | 0.862 | 0.431–1.330 |
| targeted/global-wide-class-pair | 20 / 20 | 0 | 0.455 | 0.834 | 0.307–1.203 |
| targeted/global-wide-name-pair | 20 / 20 | 0 | 0.453 | 0.568 | 0.327–0.684 |
| targeted/global-wide-caller-class | 20 / 20 | 0 | 0.403 | 0.535 | 0.298–0.658 |
| targeted/global-wide-callee-class | 20 / 20 | 0 | 0.425 | 0.720 | 0.354–0.737 |
| targeted/global-wide-provenance | 20 / 20 | 0 | 0.408 | 0.574 | 0.285–0.625 |
| targeted/global-wide-aliased | 20 / 20 | 0 | 0.428 | 0.565 | 0.348–0.574 |
| targeted/global-wide-parameterized-as-literal | 20 / 20 | 0 | 0.399 | 0.542 | 0.291–0.570 |
| targeted/global-wide-wrapped-case-insensitive | 20 / 20 | 0 | 0.417 | 0.721 | 0.372–1.833 |
| targeted/global-wide-wrapped-case-insensitive-distinct | 20 / 20 | 0 | 0.448 | 0.699 | 0.382–1.803 |
| dense/global-wide-four-properties | 20 / 20 | 0 | 1.132 | 1.241 | 1.002–1.405 |
| dense/global-wide-class-pair | 20 / 20 | 0 | 0.985 | 1.329 | 0.752–1.507 |
| dense/global-wide-name-pair | 20 / 20 | 0 | 1.091 | 1.393 | 0.879–1.831 |
| dense/global-wide-caller-class | 20 / 20 | 0 | 0.887 | 1.280 | 0.734–1.309 |
| dense/global-wide-callee-class | 20 / 20 | 0 | 1.044 | 1.189 | 0.901–1.299 |
| dense/global-wide-provenance | 20 / 20 | 0 | 0.976 | 1.121 | 0.861–1.138 |
| dense/global-wide-aliased | 20 / 20 | 0 | 1.121 | 1.306 | 0.796–1.363 |
| dense/global-wide-parameterized-as-literal | 20 / 20 | 0 | 0.967 | 1.217 | 0.771–1.267 |
| dense/global-wide-wrapped-case-insensitive | 20 / 20 | 0 | 1.248 | 1.464 | 0.996–1.610 |
| dense/global-wide-wrapped-case-insensitive-distinct | 20 / 20 | 0 | 3.189 | 3.512 | 2.831–3.580 |
| localized-early/global-wide-four-properties | 20 / 20 | 0 | 0.392 | 0.456 | 0.310–0.487 |
| localized-middle/global-wide-four-properties | 20 / 20 | 0 | 0.551 | 0.626 | 0.510–0.633 |
| localized-late/global-wide-four-properties | 20 / 20 | 0 | 0.569 | 0.668 | 0.520–0.783 |
| broad-all-64/global-wide-four-properties | 20 / 20 | 0 | 0.971 | 1.076 | 0.889–1.230 |
| schema-label-histogram | 20 / 20 | 0 | 0.576 | 0.735 | 0.537–0.800 |
| schema-key-histogram | 20 / 20 | 0 | 2.359 | 3.045 | 1.082–3.114 |
| schema-relationship-histogram | 20 / 20 | 0 | 14.891 | 15.073 | 14.361–15.554 |
| shape-wide-contains | 20 / 20 | 0 | 1.288 | 1.911 | 0.671–2.755 |
| shape-label-scan-callsite | 20 / 20 | 0 | 1.347 | 2.624 | 1.085–6.260 |
| shape-label-scan-method | 20 / 20 | 0 | 1.226 | 2.143 | 0.555–5.376 |
| shape-label-scan-constant | 20 / 20 | 0 | 1.137 | 1.722 | 0.435–2.209 |
| shape-label-scan-field | 20 / 20 | 0 | 0.929 | 1.557 | 0.427–1.703 |
| shape-all-nodes | 20 / 20 | 0 | 1.025 | 1.654 | 0.688–2.223 |
| shape-equality | 20 / 20 | 0 | 0.533 | 4.046 | 0.424–6.658 |
| shape-inequality | 20 / 20 | 0 | 0.388 | 0.712 | 0.350–0.896 |
| shape-id-lookup | 20 / 20 | 0 | 0.475 | 0.593 | 0.442–0.862 |
| shape-in-list | 20 / 20 | 0 | 0.290 | 0.343 | 0.259–0.729 |
| shape-null-check | 20 / 20 | 0 | 0.332 | 0.390 | 0.304–1.013 |
| shape-regex | 20 / 20 | 0 | 0.385 | 0.428 | 0.349–1.162 |
| shape-one-hop | 20 / 20 | 0 | 0.416 | 0.465 | 0.400–1.353 |
| shape-one-hop-typed | 20 / 20 | 0 | 0.391 | 0.417 | 0.363–1.104 |
| shape-two-hop | 20 / 20 | 0 | 0.407 | 0.459 | 0.382–0.523 |
| shape-incoming | 20 / 20 | 0 | 0.405 | 0.426 | 0.378–0.508 |
| shape-var-length | 20 / 20 | 0 | 0.337 | 0.387 | 0.317–0.449 |
| shape-path | 20 / 20 | 0 | 0.509 | 0.574 | 0.445–0.586 |
| shape-traverse-filtered | 20 / 20 | 0 | 0.617 | 0.711 | 0.575–0.779 |
| shape-count-all | 20 / 20 | 0 | 0.205 | 0.258 | 0.173–0.270 |
| shape-count-label | 20 / 20 | 0 | 0.174 | 0.203 | 0.160–0.216 |
| shape-group-count | 20 / 20 | 0 | 42.728 | 44.700 | 40.461–45.913 |
| shape-distinct | 20 / 20 | 0 | 6.014 | 7.646 | 5.472–10.841 |
| shape-collect | 20 / 20 | 0 | 0.191 | 0.218 | 0.169–0.267 |
| shape-count-traversal | 20 / 20 | 0 | 14.082 | 14.981 | 13.234–15.041 |
| shape-order-by | 20 / 20 | 0 | 1.193 | 1.933 | 0.661–2.579 |
| shape-order-by-desc | 20 / 20 | 0 | 1.009 | 1.904 | 0.526–2.877 |
| shape-skip | 20 / 20 | 0 | 1.814 | 3.330 | 0.796–3.824 |
| shape-function-calls | 20 / 20 | 0 | 1.610 | 1.971 | 0.851–2.379 |
| shape-case-expr | 20 / 20 | 0 | 0.612 | 1.510 | 0.305–1.583 |
| shape-coalesce | 20 / 20 | 0 | 0.672 | 2.075 | 0.541–2.809 |
| shape-labels-fn | 20 / 20 | 0 | 0.641 | 1.297 | 0.560–1.510 |
| shape-with-filter | 20 / 20 | 0 | 0.379 | 0.658 | 0.323–0.721 |
| shape-with-aggregate | 20 / 20 | 0 | 42.224 | 49.845 | 32.893–51.720 |
| shape-cartesian | 20 / 20 | 0 | 0.215 | 0.280 | 0.181–0.296 |
| shape-union | 20 / 20 | 0 | 0.288 | 0.316 | 0.260–0.331 |

| Resource observation | Value |
| --- | ---: |
| Load-only CPU user+system, seconds (wait4) | 17.923 |
| Load-only kernel peak RSS, MiB | 3,797.766 |
| Complete mixed pressure CPU user+system, seconds | 10.082 |
| Query-process lifetime CPU, seconds (wait4) | 49.361 |
| Query-process lifetime kernel peak RSS, MiB | 5,010.500 |

Query-process lifetime includes loading, first use, warmup, pressure and any isolated requests. It is not a resettable pressure-window peak. CPU observations cover the entire fixed mix, not each query case.

## Matched observations

| Case | base p95 ms | candidate p95 ms | absolute change ms | change % |
| --- | ---: | ---: | ---: | ---: |
| zero/global-wide-four-properties | 0.282 | 0.232 | -0.051 | -17.970 |
| zero/global-wide-class-pair | 0.211 | 0.212 | 0.001 | 0.433 |
| zero/global-wide-name-pair | 0.194 | 0.206 | 0.011 | 5.851 |
| zero/global-wide-caller-class | 0.208 | 0.219 | 0.011 | 5.199 |
| zero/global-wide-callee-class | 0.236 | 0.231 | -0.005 | -2.030 |
| zero/global-wide-provenance | 0.194 | 0.217 | 0.023 | 11.919 |
| zero/global-wide-aliased | 0.212 | 0.205 | -0.008 | -3.651 |
| zero/global-wide-parameterized-as-literal | 0.216 | 0.214 | -0.002 | -0.772 |
| zero/global-wide-wrapped-case-insensitive | 0.199 | 0.205 | 0.006 | 3.016 |
| zero/global-wide-wrapped-case-insensitive-distinct | 0.239 | 0.251 | 0.012 | 5.013 |
| targeted/global-wide-four-properties | 0.555 | 0.862 | 0.307 | 55.366 |
| targeted/global-wide-class-pair | 0.551 | 0.834 | 0.283 | 51.289 |
| targeted/global-wide-name-pair | 0.537 | 0.568 | 0.032 | 5.900 |
| targeted/global-wide-caller-class | 0.513 | 0.535 | 0.023 | 4.463 |
| targeted/global-wide-callee-class | 0.552 | 0.720 | 0.168 | 30.475 |
| targeted/global-wide-provenance | 0.593 | 0.574 | -0.019 | -3.165 |
| targeted/global-wide-aliased | 0.526 | 0.565 | 0.039 | 7.400 |
| targeted/global-wide-parameterized-as-literal | 0.511 | 0.542 | 0.031 | 6.055 |
| targeted/global-wide-wrapped-case-insensitive | 0.580 | 0.721 | 0.141 | 24.265 |
| targeted/global-wide-wrapped-case-insensitive-distinct | 0.559 | 0.699 | 0.140 | 25.110 |
| dense/global-wide-four-properties | 1.335 | 1.241 | -0.094 | -7.051 |
| dense/global-wide-class-pair | 1.223 | 1.329 | 0.106 | 8.665 |
| dense/global-wide-name-pair | 1.210 | 1.393 | 0.184 | 15.198 |
| dense/global-wide-caller-class | 1.117 | 1.280 | 0.163 | 14.560 |
| dense/global-wide-callee-class | 1.121 | 1.189 | 0.068 | 6.102 |
| dense/global-wide-provenance | 1.233 | 1.121 | -0.112 | -9.082 |
| dense/global-wide-aliased | 1.335 | 1.306 | -0.029 | -2.191 |
| dense/global-wide-parameterized-as-literal | 1.195 | 1.217 | 0.023 | 1.904 |
| dense/global-wide-wrapped-case-insensitive | 1.322 | 1.464 | 0.142 | 10.750 |
| dense/global-wide-wrapped-case-insensitive-distinct | 3.470 | 3.512 | 0.041 | 1.195 |
| localized-early/global-wide-four-properties | 0.397 | 0.456 | 0.059 | 14.762 |
| localized-middle/global-wide-four-properties | 0.669 | 0.626 | -0.043 | -6.474 |
| localized-late/global-wide-four-properties | 0.674 | 0.668 | -0.007 | -0.964 |
| broad-all-64/global-wide-four-properties | 1.224 | 1.076 | -0.149 | -12.136 |
| schema-label-histogram | 0.709 | 0.735 | 0.026 | 3.722 |
| schema-key-histogram | 3.217 | 3.045 | -0.172 | -5.352 |
| schema-relationship-histogram | 15.198 | 15.073 | -0.125 | -0.823 |
| shape-wide-contains | 2.431 | 1.911 | -0.520 | -21.394 |
| shape-label-scan-callsite | 2.768 | 2.624 | -0.144 | -5.217 |
| shape-label-scan-method | 2.317 | 2.143 | -0.174 | -7.517 |
| shape-label-scan-constant | 1.972 | 1.722 | -0.251 | -12.704 |
| shape-label-scan-field | 1.350 | 1.557 | 0.207 | 15.349 |
| shape-all-nodes | 1.607 | 1.654 | 0.047 | 2.901 |
| shape-equality | 1.344 | 4.046 | 2.702 | 201.076 |
| shape-inequality | 0.444 | 0.712 | 0.268 | 60.276 |
| shape-id-lookup | 0.579 | 0.593 | 0.014 | 2.360 |
| shape-in-list | 0.413 | 0.343 | -0.070 | -16.858 |
| shape-null-check | 0.411 | 0.390 | -0.022 | -5.250 |
| shape-regex | 0.475 | 0.428 | -0.047 | -9.896 |
| shape-one-hop | 0.465 | 0.465 | 0.000 | 0.063 |
| shape-one-hop-typed | 0.429 | 0.417 | -0.012 | -2.776 |
| shape-two-hop | 0.455 | 0.459 | 0.004 | 0.814 |
| shape-incoming | 0.444 | 0.426 | -0.018 | -4.043 |
| shape-var-length | 0.377 | 0.387 | 0.010 | 2.641 |
| shape-path | 0.549 | 0.574 | 0.025 | 4.580 |
| shape-traverse-filtered | 0.681 | 0.711 | 0.030 | 4.430 |
| shape-count-all | 0.275 | 0.258 | -0.018 | -6.503 |
| shape-count-label | 0.200 | 0.203 | 0.003 | 1.334 |
| shape-group-count | 46.011 | 44.700 | -1.311 | -2.849 |
| shape-distinct | 8.040 | 7.646 | -0.394 | -4.903 |
| shape-collect | 0.226 | 0.218 | -0.007 | -3.270 |
| shape-count-traversal | 14.653 | 14.981 | 0.328 | 2.239 |
| shape-order-by | 2.701 | 1.933 | -0.767 | -28.412 |
| shape-order-by-desc | 2.160 | 1.904 | -0.256 | -11.848 |
| shape-skip | 2.141 | 3.330 | 1.189 | 55.553 |
| shape-function-calls | 2.201 | 1.971 | -0.230 | -10.468 |
| shape-case-expr | 1.174 | 1.510 | 0.336 | 28.632 |
| shape-coalesce | 1.666 | 2.075 | 0.408 | 24.489 |
| shape-labels-fn | 1.177 | 1.297 | 0.119 | 10.129 |
| shape-with-filter | 0.611 | 0.658 | 0.047 | 7.736 |
| shape-with-aggregate | 48.694 | 49.845 | 1.151 | 2.363 |
| shape-cartesian | 0.311 | 0.280 | -0.031 | -10.011 |
| shape-union | 0.342 | 0.316 | -0.026 | -7.575 |

The resource constraint observations are retained separately in the audit JSON. A matched local pair is not final production acceptance or proof of each-case CPU/RSS limits.
