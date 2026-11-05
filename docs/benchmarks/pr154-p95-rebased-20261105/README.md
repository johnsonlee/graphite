# PR #154: corrected multi-graph diagnosis and confirmation

Base `97488be2987cbcc172714e9cd8cab5d8bf74ce60` → head `877363f623754b2633baba9d3c221dfc9da1e237`. Fresh native binaries have separate physical Cargo targets and verified source identities. **Correctness: verified for all 16 declared native runs. Formal performance acceptance: not established.**

**Full performance acceptance: NOT MET.** No automatic acceptance is inferred; per-case latency and independent workload constraints remain explicit. Apple paired resource limits also remain exceeded: Apple load raw round0 CPU +6.453% (0.815 → 0.868s); Apple query network-message-call-scan c1 raw round1 CPU +7.649% (0.647 → 0.696s). Original sort p95 remains higher: shape-order-by-desc: 0.836 → 3.310 ms (Δ +2.474 ms); shape-order-by: 1.020 → 6.923 ms (Δ +5.903 ms). These increases remain visible; the candidate is not described as fixing every query. Remaining slow-query p95 increases: Apple shared-wire c1 p95 617.221 → 630.659ms (Δ +13.438ms); Apple shared-wire c4 p95 657.624 → 661.467ms (Δ +3.843ms).

The previous native report is superseded: its candidate reused the cached baseline executable (identical SHA and missing Apple import dispatch). The old core comparison therefore changed authentic JVM-produced data while keeping one baseline consumer; it was not a native code A/B. The old Apple pair reused that same consumer on identical graphs and is classified as A/A. Neither comparison establishes PR code performance. Earlier explicitly declared Apple A/A evidence remains historical; its observed contention does not prove what caused these runs.

The producer difference is genuine: head persisted 19,635 enum declarations/strings, shifting 1,952,248 existing string IDs, while other decoded metadata stayed equal. Correctness requires retaining these declarations. Matched focused data-only and code-only runs below isolate those variables under the declared five-case mix. Their positive and negative observations and round ranges do not confirm a stable universal 18% cause. Native storage/cypher/explore source was identical between base and the pre-optimization 89f2ddd7 control; compiled object archives were not all byte-identical. Latest main #199 execution optimizations are already in both sources. The retained 152 increment adds a certified bounded-collect prefix scalar path: only after validating the complete source certificate does it skip unnecessary per-row scalar evaluation and provenance work. The grouped fc428 increment composes compact certified grouped-count state and materializes only bounded winning rows/provenance. The subsequent DISTINCT increment is separately attributed with a one-pair all 73 diagnostic; only the new three-pair full73 load/query runs constitute primary repeated evidence. Neither optimization may weaken source certificates, query semantics, work budgets or cancellation. The global 64-graph CPU profile identified fused_rows/stream_match and merge_provenance as hot paths. The 89f2ddd7→152 same-head-data optimization-effect run attributes the first increment; the 152→fc428 grouping-effect run attributes grouping composition, and the fc428→final DISTINCT diagnostic uses one all 73 pair rather than the five-case focused mix. The 97488be2→cumulative full runs remain the primary comparison; no parent-relative result substitutes for that baseline. The preserved external Claude compile fix and artifact-identity test/guard repair remain separately credited.

## Final full 73 primary query workload

64 real persisted class shards from four pinned real JAR corpora; every request is global across all 64 graphs. Three AB/BA/AB pairs, concurrency 4, one first-use oracle, two warmups and 20 pressure requests per case/arm/round. Each reported per-case percentile has 60 samples per arm. Nearest-rank p50/p95 retain full typed response parity; no pooling across different cases. There are 10,074 full-query requests: 8,760 pressure, 876 warmup and 438 first-use. This protocol differs from the earlier one-round Rust controller; no cross-protocol speedup is claimed.

The original five cases requested by the user, retained even when their current latency rank changes:

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shape-order-by-desc | 0.643 | 1.682 | 1.039 | +161.48% | 0.836 | 3.310 | 2.474 | +295.78% | 0.712/0.848 | 2.941/3.358 |
| shape-order-by | 0.919 | 2.183 | 1.264 | +137.61% | 1.020 | 6.923 | 5.903 | +578.61% | 0.967/1.020 | 5.651/7.227 |
| shape-collect | 1,889.723 | 0.354 | -1,889.369 | -99.98% | 2,086.864 | 0.487 | -2,086.377 | -99.98% | 1,888.573/2,170.006 | 0.433/0.487 |
| shape-with-aggregate | 157.735 | 49.733 | -108.001 | -68.47% | 179.448 | 54.222 | -125.227 | -69.78% | 166.030/181.607 | 50.497/54.839 |
| shape-group-count | 185.702 | 47.739 | -137.962 | -74.29% | 205.924 | 52.782 | -153.142 | -74.37% | 195.157/217.628 | 49.637/57.272 |

Full 73 directions: p50 higher/lower/equal = {'higher': 65, 'lower': 8, 'equal': 0}; p95 higher/lower/equal = {'higher': 65, 'lower': 8, 'equal': 0}. Every increase remains visible; tiny-case percentages are not primary optimization evidence.

<details>
<summary>All73 pooled case distributions and three-round p95 ranges</summary>

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| broad-all-64/global-wide-four-properties | 1.152 | 1.347 | 0.194 | +16.87% | 1.393 | 1.744 | 0.350 | +25.15% | 1.345/1.393 | 1.421/1.770 |
| dense/global-wide-aliased | 1.163 | 1.499 | 0.336 | +28.84% | 1.620 | 2.035 | 0.415 | +25.58% | 1.562/1.620 | 1.642/2.209 |
| dense/global-wide-callee-class | 1.131 | 1.483 | 0.352 | +31.15% | 1.720 | 1.895 | 0.175 | +10.19% | 1.320/1.720 | 1.708/1.902 |
| dense/global-wide-caller-class | 1.153 | 1.339 | 0.186 | +16.17% | 1.463 | 1.648 | 0.185 | +12.64% | 1.265/1.559 | 1.477/1.900 |
| dense/global-wide-class-pair | 1.172 | 1.345 | 0.173 | +14.75% | 1.612 | 1.733 | 0.121 | +7.51% | 1.346/1.612 | 1.518/1.882 |
| dense/global-wide-four-properties | 1.195 | 1.496 | 0.301 | +25.18% | 1.609 | 1.888 | 0.279 | +17.35% | 1.525/1.632 | 1.814/1.888 |
| dense/global-wide-name-pair | 1.164 | 1.522 | 0.358 | +30.77% | 1.474 | 1.972 | 0.499 | +33.84% | 1.365/1.474 | 1.697/2.056 |
| dense/global-wide-parameterized-as-literal | 1.156 | 1.292 | 0.135 | +11.72% | 1.476 | 1.908 | 0.433 | +29.32% | 1.383/1.563 | 1.474/2.155 |
| dense/global-wide-provenance | 1.131 | 1.493 | 0.362 | +32.05% | 1.689 | 2.201 | 0.512 | +30.34% | 1.488/1.766 | 1.730/2.346 |
| dense/global-wide-wrapped-case-insensitive | 1.329 | 1.653 | 0.324 | +24.36% | 1.824 | 2.087 | 0.263 | +14.44% | 1.598/1.824 | 1.907/2.087 |
| dense/global-wide-wrapped-case-insensitive-distinct | 3.449 | 3.932 | 0.483 | +14.00% | 4.149 | 4.486 | 0.337 | +8.12% | 3.735/4.168 | 4.161/4.536 |
| localized-early/global-wide-four-properties | 0.631 | 0.602 | -0.029 | -4.52% | 0.936 | 0.840 | -0.096 | -10.27% | 0.846/0.936 | 0.815/0.863 |
| localized-late/global-wide-four-properties | 0.833 | 0.871 | 0.038 | +4.56% | 1.348 | 1.197 | -0.151 | -11.20% | 0.905/1.476 | 1.054/1.397 |
| localized-middle/global-wide-four-properties | 0.789 | 0.807 | 0.018 | +2.23% | 1.154 | 1.101 | -0.053 | -4.60% | 0.924/1.244 | 1.039/1.101 |
| schema-key-histogram | 0.969 | 1.777 | 0.808 | +83.35% | 1.197 | 4.556 | 3.359 | +280.61% | 1.093/1.709 | 3.549/4.788 |
| schema-label-histogram | 0.624 | 0.841 | 0.217 | +34.75% | 0.863 | 1.161 | 0.297 | +34.44% | 0.808/0.863 | 1.059/2.042 |
| schema-relationship-histogram | 17.438 | 17.876 | 0.438 | +2.51% | 19.421 | 21.592 | 2.171 | +11.18% | 18.334/31.333 | 18.259/21.641 |
| shape-all-nodes | 0.849 | 1.408 | 0.559 | +65.82% | 1.319 | 2.371 | 1.052 | +79.72% | 1.067/1.319 | 1.985/2.641 |
| shape-cartesian | 0.763 | 0.453 | -0.310 | -40.63% | 0.887 | 0.687 | -0.200 | -22.50% | 0.826/0.908 | 0.520/0.694 |
| shape-case-expr | 0.554 | 0.831 | 0.276 | +49.88% | 0.720 | 1.513 | 0.794 | +110.23% | 0.647/0.720 | 1.113/1.744 |
| shape-coalesce | 0.553 | 0.696 | 0.143 | +25.92% | 0.660 | 1.114 | 0.454 | +68.83% | 0.647/0.686 | 0.960/1.117 |
| shape-collect | 1,889.723 | 0.354 | -1,889.369 | -99.98% | 2,086.864 | 0.487 | -2,086.377 | -99.98% | 1,888.573/2,170.006 | 0.433/0.487 |
| shape-count-all | 0.354 | 0.436 | 0.082 | +23.29% | 0.562 | 0.624 | 0.061 | +10.87% | 0.455/0.679 | 0.563/0.707 |
| shape-count-label | 0.323 | 0.412 | 0.089 | +27.46% | 0.470 | 0.676 | 0.206 | +43.77% | 0.418/0.732 | 0.499/0.681 |
| shape-count-traversal | 17.672 | 18.415 | 0.743 | +4.20% | 20.855 | 21.793 | 0.938 | +4.50% | 18.289/22.644 | 18.434/23.522 |
| shape-distinct | 208.449 | 6.604 | -201.846 | -96.83% | 241.288 | 11.286 | -230.002 | -95.32% | 231.006/256.851 | 9.078/11.286 |
| shape-equality | 0.624 | 0.902 | 0.278 | +44.65% | 0.881 | 2.189 | 1.308 | +148.50% | 0.839/0.881 | 1.522/2.271 |
| shape-function-calls | 0.633 | 1.263 | 0.630 | +99.61% | 0.774 | 2.370 | 1.597 | +206.41% | 0.714/0.774 | 1.836/3.634 |
| shape-group-count | 185.702 | 47.739 | -137.962 | -74.29% | 205.924 | 52.782 | -153.142 | -74.37% | 195.157/217.628 | 49.637/57.272 |
| shape-id-lookup | 0.621 | 0.752 | 0.132 | +21.22% | 0.843 | 1.139 | 0.296 | +35.06% | 0.761/0.843 | 0.928/1.167 |
| shape-in-list | 0.415 | 0.515 | 0.100 | +24.06% | 0.607 | 0.884 | 0.276 | +45.51% | 0.541/0.617 | 0.605/0.931 |
| shape-incoming | 0.589 | 0.695 | 0.106 | +17.97% | 0.780 | 0.881 | 0.101 | +12.91% | 0.621/0.782 | 0.766/0.904 |
| shape-inequality | 0.514 | 0.681 | 0.167 | +32.45% | 0.810 | 1.131 | 0.321 | +39.61% | 0.626/0.810 | 0.907/1.491 |
| shape-label-scan-callsite | 0.529 | 1.607 | 1.078 | +203.89% | 1.041 | 3.625 | 2.584 | +248.20% | 0.904/2.119 | 2.438/4.141 |
| shape-label-scan-constant | 0.487 | 1.463 | 0.976 | +200.29% | 1.298 | 2.954 | 1.655 | +127.47% | 0.772/1.352 | 2.211/2.954 |
| shape-label-scan-field | 0.471 | 1.424 | 0.953 | +202.54% | 0.965 | 2.605 | 1.640 | +169.95% | 0.684/0.965 | 2.393/2.605 |
| shape-label-scan-method | 0.476 | 1.495 | 1.018 | +213.77% | 1.484 | 3.013 | 1.529 | +102.98% | 1.101/1.881 | 2.134/4.577 |
| shape-labels-fn | 0.555 | 0.681 | 0.126 | +22.77% | 0.719 | 0.976 | 0.257 | +35.69% | 0.663/0.719 | 0.925/0.976 |
| shape-null-check | 0.488 | 0.632 | 0.144 | +29.61% | 0.665 | 0.934 | 0.268 | +40.35% | 0.608/0.696 | 0.692/0.989 |
| shape-one-hop | 0.607 | 0.716 | 0.109 | +17.99% | 0.892 | 1.091 | 0.199 | +22.34% | 0.847/0.892 | 0.878/1.207 |
| shape-one-hop-typed | 0.567 | 0.668 | 0.101 | +17.79% | 0.766 | 0.835 | 0.068 | +8.92% | 0.717/0.840 | 0.785/0.835 |
| shape-order-by | 0.919 | 2.183 | 1.264 | +137.61% | 1.020 | 6.923 | 5.903 | +578.61% | 0.967/1.020 | 5.651/7.227 |
| shape-order-by-desc | 0.643 | 1.682 | 1.039 | +161.48% | 0.836 | 3.310 | 2.474 | +295.78% | 0.712/0.848 | 2.941/3.358 |
| shape-path | 0.650 | 0.765 | 0.116 | +17.82% | 0.817 | 0.969 | 0.152 | +18.64% | 0.694/0.831 | 0.846/1.036 |
| shape-regex | 0.544 | 0.651 | 0.107 | +19.70% | 0.683 | 0.804 | 0.121 | +17.68% | 0.626/0.721 | 0.731/0.888 |
| shape-skip | 1.089 | 2.240 | 1.151 | +105.69% | 1.329 | 3.492 | 2.163 | +162.73% | 1.309/1.329 | 3.451/3.902 |
| shape-traverse-filtered | 0.844 | 0.954 | 0.110 | +13.01% | 1.002 | 1.281 | 0.278 | +27.77% | 0.980/1.114 | 1.105/1.419 |
| shape-two-hop | 0.587 | 0.686 | 0.099 | +16.91% | 0.760 | 0.899 | 0.140 | +18.38% | 0.747/0.769 | 0.777/0.899 |
| shape-union | 0.613 | 0.585 | -0.029 | -4.65% | 0.722 | 0.745 | 0.023 | +3.13% | 0.684/0.722 | 0.628/0.833 |
| shape-var-length | 0.521 | 0.608 | 0.086 | +16.56% | 0.772 | 0.800 | 0.029 | +3.74% | 0.631/0.780 | 0.678/0.865 |
| shape-wide-contains | 0.766 | 1.646 | 0.880 | +114.94% | 2.103 | 5.028 | 2.924 | +139.03% | 0.919/2.123 | 3.534/6.179 |
| shape-with-aggregate | 157.735 | 49.733 | -108.001 | -68.47% | 179.448 | 54.222 | -125.227 | -69.78% | 166.030/181.607 | 50.497/54.839 |
| shape-with-filter | 0.556 | 0.643 | 0.088 | +15.82% | 0.746 | 0.875 | 0.128 | +17.19% | 0.630/0.746 | 0.796/0.875 |
| targeted/global-wide-aliased | 0.584 | 0.797 | 0.213 | +36.47% | 0.868 | 1.019 | 0.151 | +17.40% | 0.648/0.984 | 0.988/1.019 |
| targeted/global-wide-callee-class | 0.562 | 0.744 | 0.182 | +32.34% | 0.795 | 1.194 | 0.399 | +50.25% | 0.672/0.897 | 0.810/1.298 |
| targeted/global-wide-caller-class | 0.560 | 0.731 | 0.170 | +30.42% | 0.757 | 1.148 | 0.391 | +51.68% | 0.696/0.780 | 0.849/2.011 |
| targeted/global-wide-class-pair | 0.593 | 0.702 | 0.109 | +18.38% | 0.975 | 1.001 | 0.026 | +2.62% | 0.760/0.975 | 0.901/1.015 |
| targeted/global-wide-four-properties | 0.805 | 0.852 | 0.047 | +5.87% | 1.009 | 1.140 | 0.131 | +13.01% | 0.961/1.009 | 1.011/1.182 |
| targeted/global-wide-name-pair | 0.560 | 0.698 | 0.138 | +24.69% | 0.875 | 1.027 | 0.152 | +17.33% | 0.727/0.961 | 0.943/1.027 |
| targeted/global-wide-parameterized-as-literal | 0.572 | 0.790 | 0.218 | +38.04% | 0.757 | 1.486 | 0.730 | +96.45% | 0.689/0.757 | 1.141/1.486 |
| targeted/global-wide-provenance | 0.553 | 0.754 | 0.201 | +36.28% | 0.721 | 1.042 | 0.321 | +44.43% | 0.711/0.721 | 0.944/1.042 |
| targeted/global-wide-wrapped-case-insensitive | 0.560 | 0.783 | 0.223 | +39.83% | 0.802 | 1.162 | 0.361 | +45.02% | 0.726/0.802 | 0.936/1.182 |
| targeted/global-wide-wrapped-case-insensitive-distinct | 0.538 | 0.800 | 0.262 | +48.70% | 0.701 | 1.351 | 0.650 | +92.76% | 0.675/0.848 | 1.061/1.370 |
| zero/global-wide-aliased | 0.362 | 0.480 | 0.118 | +32.60% | 0.701 | 0.733 | 0.031 | +4.47% | 0.418/0.701 | 0.582/0.770 |
| zero/global-wide-callee-class | 0.358 | 0.444 | 0.086 | +24.08% | 0.576 | 0.739 | 0.163 | +28.29% | 0.426/0.576 | 0.486/0.743 |
| zero/global-wide-caller-class | 0.372 | 0.431 | 0.059 | +15.85% | 0.630 | 0.788 | 0.158 | +24.99% | 0.464/0.630 | 0.548/0.788 |
| zero/global-wide-class-pair | 0.404 | 0.423 | 0.019 | +4.69% | 0.587 | 0.791 | 0.204 | +34.75% | 0.475/0.587 | 0.602/0.791 |
| zero/global-wide-four-properties | 0.449 | 0.437 | -0.012 | -2.72% | 0.727 | 0.784 | 0.057 | +7.89% | 0.486/0.727 | 0.545/0.784 |
| zero/global-wide-name-pair | 0.395 | 0.451 | 0.056 | +14.21% | 0.559 | 0.780 | 0.221 | +39.53% | 0.489/0.559 | 0.628/0.780 |
| zero/global-wide-parameterized-as-literal | 0.349 | 0.502 | 0.153 | +43.97% | 0.573 | 0.728 | 0.155 | +27.09% | 0.468/0.573 | 0.625/0.728 |
| zero/global-wide-provenance | 0.347 | 0.466 | 0.119 | +34.18% | 0.571 | 0.760 | 0.189 | +33.19% | 0.452/0.571 | 0.573/0.760 |
| zero/global-wide-wrapped-case-insensitive | 0.345 | 0.471 | 0.126 | +36.51% | 0.593 | 0.669 | 0.077 | +12.96% | 0.462/0.593 | 0.628/0.694 |
| zero/global-wide-wrapped-case-insensitive-distinct | 0.369 | 0.527 | 0.158 | +42.72% | 0.548 | 0.719 | 0.171 | +31.23% | 0.501/0.548 | 0.628/0.747 |

</details>

<details>
<summary>All73 ×three paired rounds: p50/p95 and absolute/percentage changes (20 samples per arm/round)</summary>

| Case | Round | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| broad-all-64/global-wide-four-properties | 1 | 1.172 | 1.335 | 0.163 | +13.93% | 1.393 | 1.770 | 0.376 | +27.01% |
| broad-all-64/global-wide-four-properties | 2 | 1.160 | 1.395 | 0.235 | +20.27% | 1.345 | 1.744 | 0.398 | +29.60% |
| broad-all-64/global-wide-four-properties | 3 | 1.116 | 1.268 | 0.152 | +13.58% | 1.384 | 1.421 | 0.038 | +2.71% |
| dense/global-wide-aliased | 1 | 1.173 | 1.375 | 0.201 | +17.14% | 1.619 | 1.676 | 0.057 | +3.52% |
| dense/global-wide-aliased | 2 | 1.206 | 1.625 | 0.419 | +34.77% | 1.620 | 2.209 | 0.589 | +36.33% |
| dense/global-wide-aliased | 3 | 1.138 | 1.432 | 0.294 | +25.83% | 1.562 | 1.642 | 0.080 | +5.13% |
| dense/global-wide-callee-class | 1 | 1.161 | 1.442 | 0.281 | +24.17% | 1.498 | 1.794 | 0.296 | +19.77% |
| dense/global-wide-callee-class | 2 | 1.157 | 1.624 | 0.467 | +40.33% | 1.720 | 1.902 | 0.182 | +10.57% |
| dense/global-wide-callee-class | 3 | 1.030 | 1.416 | 0.385 | +37.41% | 1.320 | 1.708 | 0.388 | +29.37% |
| dense/global-wide-caller-class | 1 | 1.154 | 1.302 | 0.148 | +12.80% | 1.265 | 1.477 | 0.212 | +16.72% |
| dense/global-wide-caller-class | 2 | 1.153 | 1.455 | 0.302 | +26.23% | 1.559 | 1.900 | 0.342 | +21.93% |
| dense/global-wide-caller-class | 3 | 1.104 | 1.271 | 0.166 | +15.06% | 1.438 | 1.521 | 0.083 | +5.78% |
| dense/global-wide-class-pair | 1 | 1.138 | 1.310 | 0.172 | +15.15% | 1.346 | 1.558 | 0.213 | +15.80% |
| dense/global-wide-class-pair | 2 | 1.177 | 1.427 | 0.250 | +21.24% | 1.612 | 1.882 | 0.271 | +16.79% |
| dense/global-wide-class-pair | 3 | 1.170 | 1.315 | 0.145 | +12.37% | 1.604 | 1.518 | -0.086 | -5.35% |
| dense/global-wide-four-properties | 1 | 1.206 | 1.436 | 0.230 | +19.07% | 1.632 | 1.814 | 0.182 | +11.15% |
| dense/global-wide-four-properties | 2 | 1.227 | 1.622 | 0.395 | +32.23% | 1.550 | 1.888 | 0.338 | +21.79% |
| dense/global-wide-four-properties | 3 | 1.154 | 1.400 | 0.246 | +21.31% | 1.525 | 1.886 | 0.360 | +23.62% |
| dense/global-wide-name-pair | 1 | 1.165 | 1.498 | 0.333 | +28.54% | 1.365 | 1.736 | 0.371 | +27.18% |
| dense/global-wide-name-pair | 2 | 1.178 | 1.645 | 0.467 | +39.66% | 1.402 | 2.056 | 0.654 | +46.69% |
| dense/global-wide-name-pair | 3 | 1.120 | 1.436 | 0.316 | +28.22% | 1.474 | 1.697 | 0.224 | +15.17% |
| dense/global-wide-parameterized-as-literal | 1 | 1.175 | 1.305 | 0.129 | +11.01% | 1.463 | 1.682 | 0.219 | +14.97% |
| dense/global-wide-parameterized-as-literal | 2 | 1.145 | 1.357 | 0.213 | +18.58% | 1.563 | 2.155 | 0.591 | +37.82% |
| dense/global-wide-parameterized-as-literal | 3 | 1.139 | 1.106 | -0.033 | -2.90% | 1.383 | 1.474 | 0.091 | +6.59% |
| dense/global-wide-provenance | 1 | 1.170 | 1.492 | 0.322 | +27.51% | 1.488 | 1.734 | 0.246 | +16.54% |
| dense/global-wide-provenance | 2 | 1.121 | 1.629 | 0.508 | +45.28% | 1.689 | 2.346 | 0.657 | +38.92% |
| dense/global-wide-provenance | 3 | 1.103 | 1.444 | 0.341 | +30.94% | 1.766 | 1.730 | -0.036 | -2.06% |
| dense/global-wide-wrapped-case-insensitive | 1 | 1.390 | 1.678 | 0.288 | +20.76% | 1.824 | 2.087 | 0.263 | +14.44% |
| dense/global-wide-wrapped-case-insensitive | 2 | 1.335 | 1.685 | 0.350 | +26.22% | 1.598 | 1.907 | 0.309 | +19.36% |
| dense/global-wide-wrapped-case-insensitive | 3 | 1.248 | 1.529 | 0.280 | +22.47% | 1.706 | 2.061 | 0.354 | +20.76% |
| dense/global-wide-wrapped-case-insensitive-distinct | 1 | 3.637 | 3.983 | 0.346 | +9.51% | 4.149 | 4.373 | 0.224 | +5.39% |
| dense/global-wide-wrapped-case-insensitive-distinct | 2 | 3.398 | 3.989 | 0.591 | +17.38% | 4.168 | 4.536 | 0.369 | +8.84% |
| dense/global-wide-wrapped-case-insensitive-distinct | 3 | 3.348 | 3.788 | 0.440 | +13.14% | 3.735 | 4.161 | 0.426 | +11.40% |
| localized-early/global-wide-four-properties | 1 | 0.632 | 0.594 | -0.037 | -5.90% | 0.846 | 0.815 | -0.032 | -3.75% |
| localized-early/global-wide-four-properties | 2 | 0.618 | 0.612 | -0.006 | -1.04% | 0.936 | 0.818 | -0.118 | -12.60% |
| localized-early/global-wide-four-properties | 3 | 0.631 | 0.597 | -0.034 | -5.40% | 0.904 | 0.863 | -0.041 | -4.55% |
| localized-late/global-wide-four-properties | 1 | 0.846 | 0.885 | 0.039 | +4.62% | 1.348 | 1.129 | -0.219 | -16.26% |
| localized-late/global-wide-four-properties | 2 | 0.833 | 0.871 | 0.038 | +4.56% | 1.476 | 1.397 | -0.079 | -5.37% |
| localized-late/global-wide-four-properties | 3 | 0.804 | 0.813 | 0.009 | +1.15% | 0.905 | 1.054 | 0.149 | +16.46% |
| localized-middle/global-wide-four-properties | 1 | 0.792 | 0.781 | -0.011 | -1.44% | 1.154 | 1.101 | -0.053 | -4.60% |
| localized-middle/global-wide-four-properties | 2 | 0.793 | 0.865 | 0.072 | +9.07% | 1.244 | 1.039 | -0.205 | -16.47% |
| localized-middle/global-wide-four-properties | 3 | 0.776 | 0.700 | -0.076 | -9.81% | 0.924 | 1.045 | 0.121 | +13.08% |
| schema-key-histogram | 1 | 0.997 | 2.301 | 1.304 | +130.74% | 1.135 | 4.049 | 2.913 | +256.59% |
| schema-key-histogram | 2 | 0.969 | 1.591 | 0.621 | +64.12% | 1.709 | 4.788 | 3.080 | +180.26% |
| schema-key-histogram | 3 | 0.954 | 2.061 | 1.107 | +116.00% | 1.093 | 3.549 | 2.456 | +224.60% |
| schema-label-histogram | 1 | 0.642 | 0.896 | 0.254 | +39.63% | 0.853 | 1.140 | 0.287 | +33.72% |
| schema-label-histogram | 2 | 0.650 | 0.867 | 0.216 | +33.23% | 0.863 | 1.059 | 0.195 | +22.62% |
| schema-label-histogram | 3 | 0.596 | 0.804 | 0.208 | +34.84% | 0.808 | 2.042 | 1.234 | +152.78% |
| schema-relationship-histogram | 1 | 17.812 | 18.334 | 0.521 | +2.93% | 19.421 | 21.641 | 2.220 | +11.43% |
| schema-relationship-histogram | 2 | 17.105 | 18.799 | 1.694 | +9.90% | 31.333 | 21.592 | -9.741 | -31.09% |
| schema-relationship-histogram | 3 | 17.099 | 17.194 | 0.096 | +0.56% | 18.334 | 18.259 | -0.074 | -0.40% |
| shape-all-nodes | 1 | 0.869 | 1.343 | 0.474 | +54.58% | 1.147 | 2.365 | 1.217 | +106.11% |
| shape-all-nodes | 2 | 0.852 | 1.548 | 0.696 | +81.66% | 1.319 | 2.641 | 1.321 | +100.17% |
| shape-all-nodes | 3 | 0.843 | 1.380 | 0.537 | +63.74% | 1.067 | 1.985 | 0.918 | +86.00% |
| shape-cartesian | 1 | 0.777 | 0.480 | -0.298 | -38.29% | 0.874 | 0.646 | -0.228 | -26.10% |
| shape-cartesian | 2 | 0.777 | 0.503 | -0.274 | -35.27% | 0.908 | 0.694 | -0.214 | -23.55% |
| shape-cartesian | 3 | 0.724 | 0.381 | -0.343 | -47.38% | 0.826 | 0.520 | -0.306 | -37.01% |
| shape-case-expr | 1 | 0.565 | 0.849 | 0.284 | +50.28% | 0.652 | 1.113 | 0.461 | +70.71% |
| shape-case-expr | 2 | 0.554 | 0.842 | 0.288 | +51.99% | 0.647 | 1.744 | 1.096 | +169.40% |
| shape-case-expr | 3 | 0.520 | 0.708 | 0.188 | +36.06% | 0.720 | 1.237 | 0.517 | +71.82% |
| shape-coalesce | 1 | 0.531 | 0.675 | 0.144 | +27.08% | 0.686 | 0.960 | 0.274 | +39.92% |
| shape-coalesce | 2 | 0.559 | 0.734 | 0.175 | +31.20% | 0.650 | 1.117 | 0.466 | +71.69% |
| shape-coalesce | 3 | 0.537 | 0.636 | 0.100 | +18.54% | 0.647 | 1.105 | 0.458 | +70.90% |
| shape-collect | 1 | 1,969.861 | 0.363 | -1,969.497 | -99.98% | 2,170.006 | 0.487 | -2,169.518 | -99.98% |
| shape-collect | 2 | 1,897.037 | 0.338 | -1,896.699 | -99.98% | 1,943.980 | 0.476 | -1,943.505 | -99.98% |
| shape-collect | 3 | 1,871.623 | 0.333 | -1,871.290 | -99.98% | 1,888.573 | 0.433 | -1,888.139 | -99.98% |
| shape-count-all | 1 | 0.365 | 0.481 | 0.116 | +31.75% | 0.679 | 0.614 | -0.065 | -9.57% |
| shape-count-all | 2 | 0.359 | 0.436 | 0.077 | +21.60% | 0.455 | 0.707 | 0.252 | +55.43% |
| shape-count-all | 3 | 0.349 | 0.415 | 0.066 | +19.00% | 0.498 | 0.563 | 0.065 | +13.03% |
| shape-count-label | 1 | 0.325 | 0.421 | 0.095 | +29.30% | 0.732 | 0.617 | -0.115 | -15.66% |
| shape-count-label | 2 | 0.329 | 0.458 | 0.130 | +39.44% | 0.439 | 0.681 | 0.242 | +55.07% |
| shape-count-label | 3 | 0.301 | 0.368 | 0.067 | +22.26% | 0.418 | 0.499 | 0.080 | +19.25% |
| shape-count-traversal | 1 | 18.395 | 18.480 | 0.085 | +0.46% | 22.644 | 20.107 | -2.537 | -11.20% |
| shape-count-traversal | 2 | 17.478 | 19.343 | 1.865 | +10.67% | 20.855 | 23.522 | 2.667 | +12.79% |
| shape-count-traversal | 3 | 17.024 | 17.509 | 0.486 | +2.85% | 18.289 | 18.434 | 0.145 | +0.79% |
| shape-distinct | 1 | 208.511 | 6.801 | -201.710 | -96.74% | 256.851 | 10.677 | -246.174 | -95.84% |
| shape-distinct | 2 | 208.449 | 6.725 | -201.724 | -96.77% | 232.656 | 11.286 | -221.370 | -95.15% |
| shape-distinct | 3 | 204.997 | 6.272 | -198.725 | -96.94% | 231.006 | 9.078 | -221.928 | -96.07% |
| shape-equality | 1 | 0.633 | 0.890 | 0.257 | +40.62% | 0.839 | 1.533 | 0.694 | +82.69% |
| shape-equality | 2 | 0.627 | 0.945 | 0.318 | +50.77% | 0.873 | 2.271 | 1.397 | +160.00% |
| shape-equality | 3 | 0.576 | 0.838 | 0.262 | +45.47% | 0.881 | 1.522 | 0.641 | +72.81% |
| shape-function-calls | 1 | 0.643 | 1.099 | 0.455 | +70.72% | 0.774 | 1.836 | 1.063 | +137.36% |
| shape-function-calls | 2 | 0.618 | 1.489 | 0.871 | +140.90% | 0.714 | 3.634 | 2.920 | +408.75% |
| shape-function-calls | 3 | 0.637 | 1.255 | 0.617 | +96.90% | 0.753 | 1.949 | 1.196 | +158.77% |
| shape-group-count | 1 | 189.534 | 47.668 | -141.866 | -74.85% | 217.628 | 52.566 | -165.061 | -75.85% |
| shape-group-count | 2 | 181.305 | 50.054 | -131.252 | -72.39% | 198.379 | 57.272 | -141.107 | -71.13% |
| shape-group-count | 3 | 180.562 | 46.116 | -134.446 | -74.46% | 195.157 | 49.637 | -145.520 | -74.57% |
| shape-id-lookup | 1 | 0.651 | 0.718 | 0.068 | +10.44% | 0.843 | 0.928 | 0.085 | +10.08% |
| shape-id-lookup | 2 | 0.633 | 0.813 | 0.179 | +28.30% | 0.777 | 1.167 | 0.391 | +50.27% |
| shape-id-lookup | 3 | 0.580 | 0.680 | 0.100 | +17.19% | 0.761 | 0.948 | 0.187 | +24.52% |
| shape-in-list | 1 | 0.422 | 0.620 | 0.198 | +46.94% | 0.617 | 0.931 | 0.314 | +50.89% |
| shape-in-list | 2 | 0.379 | 0.600 | 0.221 | +58.23% | 0.607 | 0.824 | 0.217 | +35.67% |
| shape-in-list | 3 | 0.419 | 0.463 | 0.044 | +10.44% | 0.541 | 0.605 | 0.064 | +11.86% |
| shape-incoming | 1 | 0.617 | 0.706 | 0.089 | +14.38% | 0.782 | 0.881 | 0.099 | +12.61% |
| shape-incoming | 2 | 0.589 | 0.738 | 0.149 | +25.35% | 0.780 | 0.904 | 0.123 | +15.82% |
| shape-incoming | 3 | 0.572 | 0.674 | 0.102 | +17.86% | 0.621 | 0.766 | 0.145 | +23.28% |
| shape-inequality | 1 | 0.543 | 0.690 | 0.147 | +27.07% | 0.683 | 1.007 | 0.324 | +47.47% |
| shape-inequality | 2 | 0.492 | 0.716 | 0.225 | +45.72% | 0.810 | 1.491 | 0.681 | +84.02% |
| shape-inequality | 3 | 0.507 | 0.627 | 0.119 | +23.53% | 0.626 | 0.907 | 0.281 | +44.85% |
| shape-label-scan-callsite | 1 | 0.551 | 1.561 | 1.009 | +183.05% | 0.904 | 2.438 | 1.534 | +169.74% |
| shape-label-scan-callsite | 2 | 0.572 | 1.937 | 1.365 | +238.59% | 2.119 | 3.625 | 1.505 | +71.02% |
| shape-label-scan-callsite | 3 | 0.495 | 1.388 | 0.893 | +180.49% | 0.910 | 4.141 | 3.232 | +355.30% |
| shape-label-scan-constant | 1 | 0.539 | 1.463 | 0.924 | +171.60% | 1.132 | 2.954 | 1.822 | +160.93% |
| shape-label-scan-constant | 2 | 0.475 | 1.454 | 0.979 | +206.03% | 0.772 | 2.211 | 1.439 | +186.49% |
| shape-label-scan-constant | 3 | 0.461 | 1.418 | 0.958 | +207.93% | 1.352 | 2.651 | 1.299 | +96.07% |
| shape-label-scan-field | 1 | 0.498 | 1.424 | 0.926 | +186.15% | 0.929 | 2.393 | 1.464 | +157.49% |
| shape-label-scan-field | 2 | 0.483 | 1.404 | 0.921 | +190.73% | 0.684 | 2.565 | 1.881 | +275.08% |
| shape-label-scan-field | 3 | 0.455 | 1.253 | 0.798 | +175.55% | 0.965 | 2.605 | 1.640 | +169.95% |
| shape-label-scan-method | 1 | 0.473 | 1.353 | 0.880 | +185.97% | 1.881 | 4.577 | 2.696 | +143.37% |
| shape-label-scan-method | 2 | 0.521 | 1.723 | 1.201 | +230.40% | 1.101 | 2.999 | 1.898 | +172.45% |
| shape-label-scan-method | 3 | 0.471 | 1.459 | 0.988 | +209.61% | 1.484 | 2.134 | 0.649 | +43.74% |
| shape-labels-fn | 1 | 0.558 | 0.683 | 0.125 | +22.46% | 0.719 | 0.925 | 0.206 | +28.63% |
| shape-labels-fn | 2 | 0.555 | 0.722 | 0.167 | +30.15% | 0.663 | 0.946 | 0.283 | +42.70% |
| shape-labels-fn | 3 | 0.544 | 0.615 | 0.071 | +12.95% | 0.692 | 0.976 | 0.284 | +40.96% |
| shape-null-check | 1 | 0.518 | 0.697 | 0.180 | +34.70% | 0.665 | 0.989 | 0.323 | +48.58% |
| shape-null-check | 2 | 0.467 | 0.660 | 0.194 | +41.50% | 0.696 | 0.929 | 0.233 | +33.45% |
| shape-null-check | 3 | 0.465 | 0.581 | 0.116 | +24.88% | 0.608 | 0.692 | 0.084 | +13.87% |
| shape-one-hop | 1 | 0.629 | 0.737 | 0.108 | +17.15% | 0.884 | 0.904 | 0.020 | +2.26% |
| shape-one-hop | 2 | 0.575 | 0.779 | 0.204 | +35.40% | 0.847 | 1.207 | 0.360 | +42.47% |
| shape-one-hop | 3 | 0.580 | 0.661 | 0.081 | +13.98% | 0.892 | 0.878 | -0.014 | -1.59% |
| shape-one-hop-typed | 1 | 0.589 | 0.696 | 0.107 | +18.20% | 0.840 | 0.832 | -0.008 | -0.94% |
| shape-one-hop-typed | 2 | 0.578 | 0.664 | 0.086 | +14.92% | 0.717 | 0.835 | 0.117 | +16.35% |
| shape-one-hop-typed | 3 | 0.524 | 0.635 | 0.111 | +21.25% | 0.761 | 0.785 | 0.024 | +3.19% |
| shape-order-by | 1 | 0.930 | 2.187 | 1.257 | +135.18% | 0.998 | 7.227 | 6.229 | +624.41% |
| shape-order-by | 2 | 0.937 | 2.125 | 1.188 | +126.78% | 1.020 | 6.865 | 5.845 | +572.98% |
| shape-order-by | 3 | 0.904 | 1.816 | 0.912 | +100.87% | 0.967 | 5.651 | 4.684 | +484.54% |
| shape-order-by-desc | 1 | 0.644 | 1.557 | 0.913 | +141.88% | 0.712 | 2.941 | 2.229 | +313.32% |
| shape-order-by-desc | 2 | 0.645 | 1.655 | 1.010 | +156.45% | 0.790 | 3.216 | 2.426 | +306.88% |
| shape-order-by-desc | 3 | 0.615 | 1.714 | 1.100 | +178.97% | 0.848 | 3.358 | 2.510 | +296.06% |
| shape-path | 1 | 0.686 | 0.801 | 0.114 | +16.67% | 0.831 | 0.883 | 0.052 | +6.21% |
| shape-path | 2 | 0.654 | 0.819 | 0.165 | +25.15% | 0.800 | 1.036 | 0.236 | +29.47% |
| shape-path | 3 | 0.631 | 0.711 | 0.080 | +12.64% | 0.694 | 0.846 | 0.152 | +21.93% |
| shape-regex | 1 | 0.598 | 0.661 | 0.064 | +10.70% | 0.721 | 0.804 | 0.083 | +11.44% |
| shape-regex | 2 | 0.538 | 0.660 | 0.122 | +22.64% | 0.682 | 0.888 | 0.205 | +30.09% |
| shape-regex | 3 | 0.515 | 0.603 | 0.088 | +17.11% | 0.626 | 0.731 | 0.105 | +16.73% |
| shape-skip | 1 | 1.110 | 2.225 | 1.115 | +100.44% | 1.329 | 3.492 | 2.163 | +162.73% |
| shape-skip | 2 | 1.073 | 2.456 | 1.383 | +128.85% | 1.328 | 3.902 | 2.574 | +193.79% |
| shape-skip | 3 | 1.060 | 2.237 | 1.177 | +111.07% | 1.309 | 3.451 | 2.143 | +163.74% |
| shape-traverse-filtered | 1 | 0.860 | 0.989 | 0.129 | +15.06% | 1.114 | 1.117 | 0.003 | +0.27% |
| shape-traverse-filtered | 2 | 0.836 | 1.080 | 0.244 | +29.13% | 0.987 | 1.419 | 0.433 | +43.85% |
| shape-traverse-filtered | 3 | 0.794 | 0.906 | 0.112 | +14.10% | 0.980 | 1.105 | 0.125 | +12.76% |
| shape-two-hop | 1 | 0.596 | 0.711 | 0.114 | +19.17% | 0.769 | 0.899 | 0.130 | +16.91% |
| shape-two-hop | 2 | 0.585 | 0.693 | 0.109 | +18.56% | 0.759 | 0.888 | 0.129 | +16.93% |
| shape-two-hop | 3 | 0.566 | 0.625 | 0.059 | +10.47% | 0.747 | 0.777 | 0.030 | +4.06% |
| shape-union | 1 | 0.609 | 0.632 | 0.023 | +3.78% | 0.719 | 0.833 | 0.115 | +15.99% |
| shape-union | 2 | 0.596 | 0.609 | 0.013 | +2.15% | 0.684 | 0.740 | 0.057 | +8.29% |
| shape-union | 3 | 0.613 | 0.497 | -0.117 | -19.05% | 0.722 | 0.628 | -0.094 | -13.03% |
| shape-var-length | 1 | 0.529 | 0.639 | 0.110 | +20.74% | 0.631 | 0.800 | 0.170 | +26.92% |
| shape-var-length | 2 | 0.506 | 0.649 | 0.143 | +28.21% | 0.674 | 0.865 | 0.190 | +28.20% |
| shape-var-length | 3 | 0.509 | 0.527 | 0.018 | +3.50% | 0.780 | 0.678 | -0.102 | -13.07% |
| shape-wide-contains | 1 | 0.767 | 1.680 | 0.914 | +119.20% | 0.919 | 6.179 | 5.260 | +572.61% |
| shape-wide-contains | 2 | 0.816 | 1.613 | 0.797 | +97.72% | 2.103 | 3.534 | 1.431 | +68.01% |
| shape-wide-contains | 3 | 0.745 | 1.602 | 0.857 | +114.95% | 2.123 | 3.661 | 1.538 | +72.42% |
| shape-with-aggregate | 1 | 161.542 | 50.136 | -111.406 | -68.96% | 181.607 | 53.873 | -127.734 | -70.34% |
| shape-with-aggregate | 2 | 158.179 | 50.583 | -107.596 | -68.02% | 179.448 | 54.839 | -124.609 | -69.44% |
| shape-with-aggregate | 3 | 155.655 | 48.095 | -107.560 | -69.10% | 166.030 | 50.497 | -115.533 | -69.59% |
| shape-with-filter | 1 | 0.582 | 0.646 | 0.065 | +11.15% | 0.721 | 0.875 | 0.154 | +21.37% |
| shape-with-filter | 2 | 0.556 | 0.675 | 0.119 | +21.46% | 0.746 | 0.872 | 0.125 | +16.78% |
| shape-with-filter | 3 | 0.533 | 0.609 | 0.076 | +14.29% | 0.630 | 0.796 | 0.166 | +26.30% |
| targeted/global-wide-aliased | 1 | 0.589 | 0.671 | 0.083 | +14.09% | 0.740 | 1.019 | 0.279 | +37.74% |
| targeted/global-wide-aliased | 2 | 0.567 | 0.896 | 0.329 | +58.10% | 0.984 | 1.017 | 0.033 | +3.34% |
| targeted/global-wide-aliased | 3 | 0.585 | 0.682 | 0.096 | +16.45% | 0.648 | 0.988 | 0.341 | +52.61% |
| targeted/global-wide-callee-class | 1 | 0.588 | 0.742 | 0.154 | +26.21% | 0.897 | 1.298 | 0.400 | +44.62% |
| targeted/global-wide-callee-class | 2 | 0.545 | 0.929 | 0.383 | +70.25% | 0.672 | 0.966 | 0.294 | +43.73% |
| targeted/global-wide-callee-class | 3 | 0.548 | 0.648 | 0.101 | +18.38% | 0.795 | 0.810 | 0.015 | +1.94% |
| targeted/global-wide-caller-class | 1 | 0.577 | 0.710 | 0.133 | +23.03% | 0.696 | 0.915 | 0.219 | +31.54% |
| targeted/global-wide-caller-class | 2 | 0.545 | 0.883 | 0.338 | +62.06% | 0.780 | 2.011 | 1.230 | +157.73% |
| targeted/global-wide-caller-class | 3 | 0.536 | 0.581 | 0.045 | +8.46% | 0.751 | 0.849 | 0.098 | +13.11% |
| targeted/global-wide-class-pair | 1 | 0.593 | 0.669 | 0.075 | +12.71% | 0.760 | 0.901 | 0.141 | +18.55% |
| targeted/global-wide-class-pair | 2 | 0.587 | 0.840 | 0.253 | +43.03% | 0.800 | 1.001 | 0.201 | +25.18% |
| targeted/global-wide-class-pair | 3 | 0.581 | 0.577 | -0.004 | -0.69% | 0.975 | 1.015 | 0.039 | +4.05% |
| targeted/global-wide-four-properties | 1 | 0.815 | 0.804 | -0.011 | -1.36% | 0.970 | 1.011 | 0.041 | +4.24% |
| targeted/global-wide-four-properties | 2 | 0.813 | 0.998 | 0.186 | +22.87% | 0.961 | 1.182 | 0.222 | +23.06% |
| targeted/global-wide-four-properties | 3 | 0.795 | 0.652 | -0.142 | -17.90% | 1.009 | 1.073 | 0.064 | +6.29% |
| targeted/global-wide-name-pair | 1 | 0.554 | 0.662 | 0.108 | +19.48% | 0.875 | 0.981 | 0.105 | +12.04% |
| targeted/global-wide-name-pair | 2 | 0.548 | 0.900 | 0.352 | +64.30% | 0.727 | 1.027 | 0.300 | +41.28% |
| targeted/global-wide-name-pair | 3 | 0.575 | 0.628 | 0.052 | +9.11% | 0.961 | 0.943 | -0.018 | -1.89% |
| targeted/global-wide-parameterized-as-literal | 1 | 0.550 | 0.790 | 0.239 | +43.49% | 0.709 | 1.141 | 0.433 | +61.02% |
| targeted/global-wide-parameterized-as-literal | 2 | 0.587 | 0.846 | 0.259 | +44.21% | 0.757 | 1.220 | 0.463 | +61.22% |
| targeted/global-wide-parameterized-as-literal | 3 | 0.575 | 0.686 | 0.110 | +19.20% | 0.689 | 1.486 | 0.797 | +115.74% |
| targeted/global-wide-provenance | 1 | 0.582 | 0.762 | 0.180 | +30.92% | 0.711 | 1.042 | 0.330 | +46.45% |
| targeted/global-wide-provenance | 2 | 0.542 | 0.836 | 0.294 | +54.33% | 0.718 | 1.034 | 0.317 | +44.15% |
| targeted/global-wide-provenance | 3 | 0.553 | 0.603 | 0.050 | +9.07% | 0.721 | 0.944 | 0.223 | +30.92% |
| targeted/global-wide-wrapped-case-insensitive | 1 | 0.557 | 0.698 | 0.142 | +25.46% | 0.802 | 0.936 | 0.134 | +16.74% |
| targeted/global-wide-wrapped-case-insensitive | 2 | 0.565 | 0.969 | 0.404 | +71.45% | 0.743 | 1.182 | 0.439 | +59.15% |
| targeted/global-wide-wrapped-case-insensitive | 3 | 0.552 | 0.655 | 0.104 | +18.78% | 0.726 | 1.056 | 0.330 | +45.38% |
| targeted/global-wide-wrapped-case-insensitive-distinct | 1 | 0.539 | 0.800 | 0.261 | +48.47% | 0.675 | 1.351 | 0.676 | +100.13% |
| targeted/global-wide-wrapped-case-insensitive-distinct | 2 | 0.531 | 0.904 | 0.373 | +70.38% | 0.701 | 1.370 | 0.670 | +95.56% |
| targeted/global-wide-wrapped-case-insensitive-distinct | 3 | 0.539 | 0.728 | 0.188 | +34.92% | 0.848 | 1.061 | 0.213 | +25.07% |
| zero/global-wide-aliased | 1 | 0.355 | 0.495 | 0.141 | +39.64% | 0.513 | 0.664 | 0.151 | +29.37% |
| zero/global-wide-aliased | 2 | 0.360 | 0.561 | 0.201 | +55.92% | 0.418 | 0.770 | 0.351 | +84.02% |
| zero/global-wide-aliased | 3 | 0.362 | 0.394 | 0.032 | +8.92% | 0.701 | 0.582 | -0.119 | -16.95% |
| zero/global-wide-callee-class | 1 | 0.347 | 0.491 | 0.144 | +41.46% | 0.576 | 0.600 | 0.024 | +4.14% |
| zero/global-wide-callee-class | 2 | 0.379 | 0.545 | 0.166 | +43.79% | 0.487 | 0.743 | 0.256 | +52.57% |
| zero/global-wide-callee-class | 3 | 0.345 | 0.360 | 0.014 | +4.11% | 0.426 | 0.486 | 0.061 | +14.28% |
| zero/global-wide-caller-class | 1 | 0.385 | 0.466 | 0.080 | +20.86% | 0.630 | 0.759 | 0.128 | +20.38% |
| zero/global-wide-caller-class | 2 | 0.388 | 0.418 | 0.030 | +7.71% | 0.564 | 0.788 | 0.224 | +39.61% |
| zero/global-wide-caller-class | 3 | 0.353 | 0.352 | -0.000 | -0.02% | 0.464 | 0.548 | 0.084 | +18.16% |
| zero/global-wide-class-pair | 1 | 0.404 | 0.444 | 0.041 | +10.08% | 0.486 | 0.632 | 0.146 | +29.99% |
| zero/global-wide-class-pair | 2 | 0.403 | 0.457 | 0.054 | +13.34% | 0.587 | 0.791 | 0.204 | +34.75% |
| zero/global-wide-class-pair | 3 | 0.389 | 0.376 | -0.013 | -3.29% | 0.475 | 0.602 | 0.127 | +26.78% |
| zero/global-wide-four-properties | 1 | 0.446 | 0.414 | -0.032 | -7.08% | 0.587 | 0.640 | 0.053 | +9.00% |
| zero/global-wide-four-properties | 2 | 0.487 | 0.456 | -0.031 | -6.39% | 0.727 | 0.784 | 0.057 | +7.89% |
| zero/global-wide-four-properties | 3 | 0.432 | 0.373 | -0.059 | -13.65% | 0.486 | 0.545 | 0.059 | +12.17% |
| zero/global-wide-name-pair | 1 | 0.395 | 0.452 | 0.057 | +14.37% | 0.489 | 0.647 | 0.158 | +32.21% |
| zero/global-wide-name-pair | 2 | 0.407 | 0.492 | 0.086 | +21.14% | 0.559 | 0.780 | 0.221 | +39.53% |
| zero/global-wide-name-pair | 3 | 0.386 | 0.350 | -0.036 | -9.26% | 0.504 | 0.628 | 0.124 | +24.56% |
| zero/global-wide-parameterized-as-literal | 1 | 0.351 | 0.503 | 0.152 | +43.32% | 0.468 | 0.625 | 0.157 | +33.52% |
| zero/global-wide-parameterized-as-literal | 2 | 0.358 | 0.542 | 0.184 | +51.38% | 0.479 | 0.728 | 0.249 | +52.04% |
| zero/global-wide-parameterized-as-literal | 3 | 0.335 | 0.391 | 0.056 | +16.60% | 0.573 | 0.728 | 0.155 | +27.09% |
| zero/global-wide-provenance | 1 | 0.341 | 0.474 | 0.133 | +39.05% | 0.571 | 0.702 | 0.131 | +22.97% |
| zero/global-wide-provenance | 2 | 0.354 | 0.565 | 0.211 | +59.57% | 0.493 | 0.760 | 0.267 | +54.15% |
| zero/global-wide-provenance | 3 | 0.347 | 0.378 | 0.030 | +8.72% | 0.452 | 0.573 | 0.121 | +26.70% |
| zero/global-wide-wrapped-case-insensitive | 1 | 0.345 | 0.493 | 0.149 | +43.11% | 0.462 | 0.628 | 0.166 | +35.96% |
| zero/global-wide-wrapped-case-insensitive | 2 | 0.342 | 0.534 | 0.192 | +56.10% | 0.511 | 0.694 | 0.183 | +35.75% |
| zero/global-wide-wrapped-case-insensitive | 3 | 0.344 | 0.374 | 0.030 | +8.69% | 0.593 | 0.669 | 0.076 | +12.84% |
| zero/global-wide-wrapped-case-insensitive-distinct | 1 | 0.367 | 0.565 | 0.197 | +53.68% | 0.501 | 0.628 | 0.127 | +25.39% |
| zero/global-wide-wrapped-case-insensitive-distinct | 2 | 0.375 | 0.587 | 0.211 | +56.27% | 0.519 | 0.747 | 0.228 | +43.93% |
| zero/global-wide-wrapped-case-insensitive-distinct | 3 | 0.372 | 0.378 | 0.006 | +1.48% | 0.548 | 0.714 | 0.166 | +30.33% |

</details>

## Focused causal controls

focused-data: `main_base` → `main_head`; same native binary, different producer graphs. 690 complete requests, including 600 pressure requests. These focused distributions are a different query mix from the full 73 run and are not combined with it; the optimization increment is a parent-relative attribution, not a substitute for the main-baseline final comparison.

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shape-order-by-desc | 0.673 | 0.670 | -0.004 | -0.54% | 1.044 | 0.857 | -0.187 | -17.95% | 0.890/1.055 | 0.770/0.881 |
| shape-order-by | 0.968 | 0.967 | -0.001 | -0.10% | 1.165 | 1.204 | 0.039 | +3.37% | 1.091/1.165 | 1.057/1.204 |
| shape-collect | 1,949.073 | 1,796.310 | -152.763 | -7.84% | 2,456.933 | 2,236.047 | -220.886 | -8.99% | 1,810.121/2,563.012 | 1,842.721/2,266.135 |
| shape-with-aggregate | 166.135 | 163.681 | -2.454 | -1.48% | 200.040 | 187.194 | -12.846 | -6.42% | 172.292/213.418 | 167.557/191.197 |
| shape-group-count | 199.082 | 187.703 | -11.379 | -5.72% | 243.842 | 218.052 | -25.790 | -10.58% | 211.849/256.992 | 209.679/222.524 |

<details>
<summary>focused-data all three paired rounds</summary>

| Case | Round | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shape-order-by-desc | 1 | 0.670 | 0.661 | -0.009 | -1.34% | 1.044 | 0.881 | -0.163 | -15.62% |
| shape-order-by-desc | 2 | 0.639 | 0.628 | -0.010 | -1.59% | 0.890 | 0.770 | -0.120 | -13.45% |
| shape-order-by-desc | 3 | 0.695 | 0.714 | 0.019 | +2.70% | 1.055 | 0.838 | -0.217 | -20.56% |
| shape-order-by | 1 | 0.964 | 0.888 | -0.077 | -7.94% | 1.091 | 1.057 | -0.034 | -3.10% |
| shape-order-by | 2 | 0.952 | 0.943 | -0.009 | -0.96% | 1.128 | 1.204 | 0.076 | +6.73% |
| shape-order-by | 3 | 0.973 | 1.012 | 0.039 | +4.06% | 1.165 | 1.147 | -0.018 | -1.53% |
| shape-collect | 1 | 1,779.733 | 1,785.175 | 5.443 | +0.31% | 1,810.121 | 1,848.340 | 38.220 | +2.11% |
| shape-collect | 2 | 1,949.544 | 1,757.989 | -191.555 | -9.83% | 2,052.072 | 1,842.721 | -209.351 | -10.20% |
| shape-collect | 3 | 2,184.639 | 2,148.738 | -35.902 | -1.64% | 2,563.012 | 2,266.135 | -296.877 | -11.58% |
| shape-with-aggregate | 1 | 153.752 | 159.300 | 5.548 | +3.61% | 172.292 | 170.258 | -2.033 | -1.18% |
| shape-with-aggregate | 2 | 166.973 | 159.273 | -7.700 | -4.61% | 191.527 | 167.557 | -23.971 | -12.52% |
| shape-with-aggregate | 3 | 186.879 | 171.960 | -14.919 | -7.98% | 213.418 | 191.197 | -22.221 | -10.41% |
| shape-group-count | 1 | 180.810 | 184.887 | 4.077 | +2.25% | 211.849 | 209.679 | -2.170 | -1.02% |
| shape-group-count | 2 | 200.104 | 184.457 | -15.647 | -7.82% | 243.842 | 215.039 | -28.803 | -11.81% |
| shape-group-count | 3 | 209.785 | 200.127 | -9.658 | -4.60% | 256.992 | 222.524 | -34.468 | -13.41% |

</details>

focused-code: `main_head` → `candidate_head`; same head dataset, baseline versus pre-optimization 89f2ddd7 control. 690 complete requests, including 600 pressure requests. These focused distributions are a different query mix from the full 73 run and are not combined with it; the optimization increment is a parent-relative attribution, not a substitute for the main-baseline final comparison.

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shape-order-by-desc | 0.693 | 0.676 | -0.017 | -2.48% | 0.840 | 1.091 | 0.251 | +29.94% | 0.781/0.840 | 0.800/1.128 |
| shape-order-by | 0.946 | 0.953 | 0.006 | +0.69% | 1.069 | 1.076 | 0.007 | +0.68% | 1.024/1.069 | 1.033/1.076 |
| shape-collect | 1,741.521 | 1,753.921 | 12.401 | +0.71% | 2,030.112 | 2,006.239 | -23.873 | -1.18% | 1,780.662/2,100.232 | 1,734.444/2,016.566 |
| shape-with-aggregate | 153.268 | 154.426 | 1.157 | +0.75% | 178.584 | 176.169 | -2.415 | -1.35% | 162.478/181.721 | 158.878/185.046 |
| shape-group-count | 181.641 | 182.982 | 1.341 | +0.74% | 199.554 | 200.498 | 0.943 | +0.47% | 188.781/201.967 | 188.992/205.906 |

<details>
<summary>focused-code all three paired rounds</summary>

| Case | Round | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shape-order-by-desc | 1 | 0.721 | 0.687 | -0.034 | -4.72% | 0.840 | 0.878 | 0.038 | +4.48% |
| shape-order-by-desc | 2 | 0.636 | 0.647 | 0.011 | +1.80% | 0.781 | 1.128 | 0.346 | +44.29% |
| shape-order-by-desc | 3 | 0.693 | 0.668 | -0.025 | -3.55% | 0.818 | 0.800 | -0.018 | -2.17% |
| shape-order-by | 1 | 0.950 | 0.967 | 0.018 | +1.86% | 1.062 | 1.076 | 0.014 | +1.35% |
| shape-order-by | 2 | 0.917 | 0.931 | 0.014 | +1.52% | 1.024 | 1.046 | 0.021 | +2.07% |
| shape-order-by | 3 | 0.946 | 0.941 | -0.005 | -0.51% | 1.069 | 1.033 | -0.036 | -3.36% |
| shape-collect | 1 | 1,919.518 | 1,947.718 | 28.200 | +1.47% | 2,100.232 | 2,016.566 | -83.666 | -3.98% |
| shape-collect | 2 | 1,724.926 | 1,704.808 | -20.118 | -1.17% | 1,780.662 | 1,734.444 | -46.219 | -2.60% |
| shape-collect | 3 | 1,740.550 | 1,753.921 | 13.372 | +0.77% | 1,783.075 | 1,819.280 | 36.205 | +2.03% |
| shape-with-aggregate | 1 | 158.962 | 161.379 | 2.417 | +1.52% | 178.584 | 185.046 | 6.462 | +3.62% |
| shape-with-aggregate | 2 | 147.018 | 150.898 | 3.880 | +2.64% | 162.478 | 158.878 | -3.599 | -2.22% |
| shape-with-aggregate | 3 | 152.268 | 153.407 | 1.139 | +0.75% | 181.721 | 176.169 | -5.552 | -3.06% |
| shape-group-count | 1 | 188.303 | 190.503 | 2.199 | +1.17% | 199.554 | 205.906 | 6.351 | +3.18% |
| shape-group-count | 2 | 172.521 | 178.066 | 5.545 | +3.21% | 188.781 | 188.992 | 0.211 | +0.11% |
| shape-group-count | 3 | 180.634 | 181.566 | 0.932 | +0.52% | 201.967 | 197.597 | -4.369 | -2.16% |

</details>

optimization-effect: `parent_head` → `candidate_head`; same head dataset, pre-optimization 89f2ddd7 versus collect 152. 690 complete requests, including 600 pressure requests. These focused distributions are a different query mix from the full 73 run and are not combined with it; the optimization increment is a parent-relative attribution, not a substitute for the main-baseline final comparison.

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shape-order-by-desc | 0.651 | 0.679 | 0.028 | +4.33% | 0.811 | 1.029 | 0.218 | +26.86% | 0.691/0.811 | 0.800/1.166 |
| shape-order-by | 0.913 | 0.673 | -0.240 | -26.29% | 1.147 | 0.947 | -0.199 | -17.38% | 0.999/1.147 | 0.782/0.947 |
| shape-collect | 1,770.838 | 0.722 | -1,770.116 | -99.96% | 1,819.116 | 0.917 | -1,818.199 | -99.95% | 1,780.451/1,858.182 | 0.831/1.020 |
| shape-with-aggregate | 156.101 | 185.604 | 29.503 | +18.90% | 174.598 | 196.303 | 21.705 | +12.43% | 169.600/174.598 | 193.797/205.391 |
| shape-group-count | 183.911 | 219.868 | 35.957 | +19.55% | 201.970 | 228.040 | 26.070 | +12.91% | 200.323/203.164 | 223.912/234.836 |

<details>
<summary>optimization-effect all three paired rounds</summary>

| Case | Round | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shape-order-by-desc | 1 | 0.619 | 0.679 | 0.060 | +9.70% | 0.691 | 1.166 | 0.475 | +68.77% |
| shape-order-by-desc | 2 | 0.641 | 0.638 | -0.003 | -0.53% | 0.800 | 1.024 | 0.224 | +28.03% |
| shape-order-by-desc | 3 | 0.669 | 0.707 | 0.038 | +5.73% | 0.811 | 0.800 | -0.011 | -1.31% |
| shape-order-by | 1 | 0.913 | 0.693 | -0.220 | -24.06% | 1.029 | 0.904 | -0.125 | -12.11% |
| shape-order-by | 2 | 0.880 | 0.656 | -0.223 | -25.36% | 0.999 | 0.782 | -0.217 | -21.70% |
| shape-order-by | 3 | 0.914 | 0.659 | -0.254 | -27.82% | 1.147 | 0.947 | -0.199 | -17.38% |
| shape-collect | 1 | 1,770.273 | 0.749 | -1,769.524 | -99.96% | 1,792.591 | 1.020 | -1,791.571 | -99.94% |
| shape-collect | 2 | 1,788.580 | 0.719 | -1,787.861 | -99.96% | 1,858.182 | 0.908 | -1,857.274 | -99.95% |
| shape-collect | 3 | 1,733.433 | 0.692 | -1,732.740 | -99.96% | 1,780.451 | 0.831 | -1,779.620 | -99.95% |
| shape-with-aggregate | 1 | 155.305 | 184.801 | 29.496 | +18.99% | 169.600 | 193.797 | 24.198 | +14.27% |
| shape-with-aggregate | 2 | 156.250 | 186.000 | 29.750 | +19.04% | 174.598 | 205.391 | 30.793 | +17.64% |
| shape-with-aggregate | 3 | 155.106 | 185.604 | 30.498 | +19.66% | 171.355 | 195.158 | 23.804 | +13.89% |
| shape-group-count | 1 | 185.623 | 220.572 | 34.949 | +18.83% | 200.323 | 226.376 | 26.053 | +13.01% |
| shape-group-count | 2 | 183.666 | 220.498 | 36.832 | +20.05% | 203.164 | 234.836 | 31.672 | +15.59% |
| shape-group-count | 3 | 182.976 | 217.401 | 34.424 | +18.81% | 201.970 | 223.912 | 21.942 | +10.86% |

</details>

grouping-effect: `increment_parent_head` → `candidate_head`; same head dataset, collect 152 versus grouped fc428 increment. 690 complete requests, including 600 pressure requests. These focused distributions are a different query mix from the full 73 run and are not combined with it; the optimization increment is a parent-relative attribution, not a substitute for the main-baseline final comparison.

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shape-order-by-desc | 0.586 | 0.548 | -0.039 | -6.60% | 1.016 | 1.057 | 0.041 | +4.06% | 0.759/1.016 | 0.900/1.057 |
| shape-order-by | 0.628 | 0.600 | -0.028 | -4.44% | 1.154 | 1.159 | 0.005 | +0.40% | 0.763/1.154 | 0.961/1.159 |
| shape-collect | 0.616 | 0.460 | -0.156 | -25.30% | 1.046 | 1.048 | 0.003 | +0.27% | 0.853/1.046 | 0.904/1.125 |
| shape-with-aggregate | 175.497 | 36.091 | -139.406 | -79.44% | 187.765 | 38.219 | -149.546 | -79.65% | 180.248/237.575 | 37.315/38.286 |
| shape-group-count | 206.509 | 34.520 | -171.989 | -83.28% | 282.512 | 36.803 | -245.709 | -86.97% | 217.076/292.885 | 35.066/36.902 |

<details>
<summary>grouping-effect all three paired rounds</summary>

| Case | Round | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shape-order-by-desc | 1 | 0.589 | 0.572 | -0.017 | -2.86% | 1.016 | 1.057 | 0.041 | +4.06% |
| shape-order-by-desc | 2 | 0.589 | 0.555 | -0.035 | -5.85% | 0.991 | 0.900 | -0.091 | -9.15% |
| shape-order-by-desc | 3 | 0.573 | 0.514 | -0.059 | -10.33% | 0.759 | 0.959 | 0.201 | +26.43% |
| shape-order-by | 1 | 0.629 | 0.566 | -0.063 | -10.08% | 1.154 | 1.159 | 0.005 | +0.40% |
| shape-order-by | 2 | 0.628 | 0.589 | -0.039 | -6.18% | 0.932 | 0.961 | 0.029 | +3.12% |
| shape-order-by | 3 | 0.604 | 0.627 | 0.023 | +3.83% | 0.763 | 1.048 | 0.284 | +37.23% |
| shape-collect | 1 | 0.664 | 0.491 | -0.173 | -25.99% | 1.046 | 0.904 | -0.141 | -13.51% |
| shape-collect | 2 | 0.617 | 0.451 | -0.166 | -26.87% | 1.013 | 1.125 | 0.111 | +11.00% |
| shape-collect | 3 | 0.559 | 0.441 | -0.118 | -21.13% | 0.853 | 1.040 | 0.188 | +22.02% |
| shape-with-aggregate | 1 | 172.732 | 36.675 | -136.057 | -78.77% | 180.248 | 38.286 | -141.962 | -78.76% |
| shape-with-aggregate | 2 | 176.200 | 35.983 | -140.217 | -79.58% | 186.611 | 38.133 | -148.478 | -79.57% |
| shape-with-aggregate | 3 | 177.770 | 35.463 | -142.307 | -80.05% | 237.575 | 37.315 | -200.259 | -84.29% |
| shape-group-count | 1 | 203.169 | 34.901 | -168.268 | -82.82% | 217.076 | 36.803 | -180.273 | -83.05% |
| shape-group-count | 2 | 203.947 | 35.025 | -168.923 | -82.83% | 292.885 | 36.902 | -255.983 | -87.40% |
| shape-group-count | 3 | 209.186 | 34.111 | -175.075 | -83.69% | 282.512 | 35.066 | -247.446 | -87.59% |

</details>

## Preserved pre-optimization control

The original full-load/full-query results compared `97488be2987cbcc172714e9cd8cab5d8bf74ce60` with `89f2ddd7bfe9d562bd222955fa11bf196678a05d` before the bounded-collect optimization. They remain valid control evidence (438 load and 10,074 query requests), not final-candidate evidence. They are never pooled with either the retained152 intermediate runs or the selected cumulative primary runs. The original focused-data/code controls likewise remain tied to their snapshot configurations and frozen binaries.

<details>
<summary>Pre-optimization full73 pooled case distributions</summary>

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| broad-all-64/global-wide-four-properties | 1.112 | 1.073 | -0.039 | -3.47% | 2.035 | 1.348 | -0.687 | -33.74% | 1.343/2.062 | 1.181/1.513 |
| dense/global-wide-aliased | 1.112 | 1.077 | -0.034 | -3.08% | 1.847 | 1.766 | -0.081 | -4.39% | 1.391/1.847 | 1.545/2.254 |
| dense/global-wide-callee-class | 1.069 | 1.046 | -0.023 | -2.12% | 1.779 | 2.173 | 0.394 | +22.13% | 1.658/2.483 | 1.563/2.173 |
| dense/global-wide-caller-class | 1.057 | 1.035 | -0.022 | -2.11% | 1.445 | 1.465 | 0.020 | +1.37% | 1.302/1.445 | 1.157/1.558 |
| dense/global-wide-class-pair | 1.135 | 1.117 | -0.019 | -1.63% | 1.482 | 1.402 | -0.080 | -5.41% | 1.343/1.482 | 1.290/1.421 |
| dense/global-wide-four-properties | 1.140 | 1.105 | -0.036 | -3.12% | 1.594 | 1.506 | -0.088 | -5.52% | 1.273/2.082 | 1.239/1.617 |
| dense/global-wide-name-pair | 1.087 | 1.078 | -0.009 | -0.87% | 2.068 | 1.740 | -0.328 | -15.85% | 1.767/2.068 | 1.605/1.740 |
| dense/global-wide-parameterized-as-literal | 1.091 | 1.071 | -0.020 | -1.82% | 1.597 | 1.725 | 0.128 | +8.05% | 1.376/1.597 | 1.274/1.904 |
| dense/global-wide-provenance | 1.095 | 1.075 | -0.020 | -1.83% | 1.548 | 1.444 | -0.104 | -6.69% | 1.481/1.827 | 1.347/1.444 |
| dense/global-wide-wrapped-case-insensitive | 1.232 | 1.240 | 0.008 | +0.67% | 2.678 | 2.208 | -0.470 | -17.55% | 1.667/2.678 | 1.407/2.208 |
| dense/global-wide-wrapped-case-insensitive-distinct | 3.261 | 3.235 | -0.026 | -0.79% | 3.788 | 4.249 | 0.461 | +12.18% | 3.712/3.788 | 3.606/4.611 |
| localized-early/global-wide-four-properties | 0.648 | 0.637 | -0.011 | -1.75% | 0.885 | 0.934 | 0.049 | +5.49% | 0.710/0.995 | 0.748/1.196 |
| localized-late/global-wide-four-properties | 0.812 | 0.816 | 0.004 | +0.50% | 1.550 | 1.684 | 0.134 | +8.65% | 0.937/1.550 | 1.106/2.177 |
| localized-middle/global-wide-four-properties | 0.781 | 0.769 | -0.013 | -1.61% | 1.603 | 1.218 | -0.385 | -24.03% | 1.061/1.650 | 0.978/1.383 |
| schema-key-histogram | 0.912 | 0.926 | 0.014 | +1.55% | 1.142 | 1.926 | 0.785 | +68.72% | 1.075/2.017 | 1.207/1.926 |
| schema-label-histogram | 0.640 | 0.595 | -0.045 | -7.11% | 1.091 | 1.379 | 0.289 | +26.45% | 0.784/1.091 | 0.857/1.379 |
| schema-relationship-histogram | 14.799 | 14.804 | 0.005 | +0.03% | 17.823 | 17.066 | -0.758 | -4.25% | 15.591/18.771 | 15.419/18.099 |
| shape-all-nodes | 0.754 | 0.745 | -0.009 | -1.18% | 1.506 | 1.240 | -0.266 | -17.67% | 0.950/1.506 | 1.145/1.240 |
| shape-cartesian | 0.751 | 0.731 | -0.020 | -2.63% | 0.857 | 0.852 | -0.005 | -0.57% | 0.814/0.872 | 0.813/0.852 |
| shape-case-expr | 0.484 | 0.480 | -0.004 | -0.83% | 0.592 | 0.621 | 0.029 | +4.90% | 0.557/0.667 | 0.577/0.788 |
| shape-coalesce | 0.481 | 0.476 | -0.006 | -1.17% | 0.615 | 0.602 | -0.013 | -2.12% | 0.583/0.654 | 0.551/0.604 |
| shape-collect | 1,734.440 | 1,760.441 | 26.001 | +1.50% | 1,759.993 | 1,792.250 | 32.257 | +1.83% | 1,739.418/1,762.982 | 1,761.922/1,822.746 |
| shape-count-all | 0.290 | 0.278 | -0.012 | -4.07% | 0.934 | 1.007 | 0.073 | +7.86% | 0.740/1.278 | 0.424/1.007 |
| shape-count-label | 0.247 | 0.232 | -0.015 | -6.15% | 1.188 | 0.812 | -0.376 | -31.68% | 0.325/1.188 | 0.732/0.812 |
| shape-count-traversal | 15.127 | 14.896 | -0.231 | -1.52% | 17.959 | 17.355 | -0.604 | -3.36% | 16.699/18.651 | 16.086/17.451 |
| shape-distinct | 203.880 | 204.468 | 0.588 | +0.29% | 222.650 | 226.313 | 3.663 | +1.65% | 217.267/222.650 | 216.713/235.706 |
| shape-equality | 0.584 | 0.552 | -0.032 | -5.50% | 2.020 | 1.680 | -0.340 | -16.83% | 0.750/2.020 | 0.916/1.943 |
| shape-function-calls | 0.575 | 0.545 | -0.030 | -5.22% | 0.678 | 0.678 | -0.000 | -0.06% | 0.650/0.709 | 0.605/0.682 |
| shape-group-count | 178.968 | 180.918 | 1.950 | +1.09% | 198.788 | 199.106 | 0.318 | +0.16% | 193.232/198.788 | 195.660/199.106 |
| shape-id-lookup | 0.583 | 0.581 | -0.003 | -0.46% | 1.228 | 0.988 | -0.240 | -19.55% | 0.783/1.328 | 0.814/1.041 |
| shape-in-list | 0.351 | 0.345 | -0.005 | -1.46% | 0.706 | 1.045 | 0.339 | +47.99% | 0.583/0.981 | 0.625/1.400 |
| shape-incoming | 0.463 | 0.455 | -0.007 | -1.61% | 1.112 | 1.273 | 0.160 | +14.41% | 0.948/1.112 | 0.689/1.273 |
| shape-inequality | 0.453 | 0.421 | -0.031 | -6.93% | 1.329 | 0.973 | -0.355 | -26.73% | 0.882/1.329 | 0.623/1.604 |
| shape-label-scan-callsite | 0.464 | 0.466 | 0.003 | +0.57% | 1.291 | 1.748 | 0.456 | +35.36% | 1.146/1.291 | 1.617/1.933 |
| shape-label-scan-constant | 0.428 | 0.415 | -0.013 | -3.07% | 1.397 | 1.906 | 0.509 | +36.41% | 0.982/1.428 | 0.989/1.906 |
| shape-label-scan-field | 0.410 | 0.397 | -0.013 | -3.20% | 1.429 | 1.485 | 0.056 | +3.94% | 0.848/1.429 | 1.266/1.485 |
| shape-label-scan-method | 0.418 | 0.418 | -0.000 | -0.05% | 1.859 | 2.604 | 0.745 | +40.05% | 1.506/1.859 | 0.847/2.941 |
| shape-labels-fn | 0.490 | 0.488 | -0.001 | -0.27% | 0.629 | 0.647 | 0.018 | +2.90% | 0.612/0.629 | 0.577/0.683 |
| shape-null-check | 0.411 | 0.429 | 0.018 | +4.44% | 0.811 | 1.005 | 0.193 | +23.82% | 0.708/0.811 | 0.501/1.005 |
| shape-one-hop | 0.491 | 0.498 | 0.007 | +1.46% | 1.052 | 0.999 | -0.053 | -5.07% | 0.677/1.103 | 0.718/0.999 |
| shape-one-hop-typed | 0.460 | 0.465 | 0.005 | +1.20% | 1.497 | 1.308 | -0.189 | -12.60% | 1.226/1.497 | 0.625/1.325 |
| shape-order-by | 0.857 | 0.851 | -0.005 | -0.62% | 0.973 | 0.960 | -0.013 | -1.34% | 0.900/0.973 | 0.898/0.974 |
| shape-order-by-desc | 0.576 | 0.573 | -0.003 | -0.52% | 0.673 | 0.692 | 0.018 | +2.74% | 0.661/0.684 | 0.624/0.794 |
| shape-path | 0.561 | 0.534 | -0.027 | -4.83% | 1.245 | 1.164 | -0.080 | -6.44% | 1.065/1.351 | 0.750/1.164 |
| shape-regex | 0.445 | 0.438 | -0.008 | -1.76% | 0.901 | 0.704 | -0.197 | -21.90% | 0.678/0.901 | 0.650/0.704 |
| shape-skip | 0.978 | 0.949 | -0.029 | -2.94% | 1.245 | 1.121 | -0.124 | -9.92% | 1.073/1.366 | 1.042/1.159 |
| shape-traverse-filtered | 0.748 | 0.742 | -0.007 | -0.87% | 1.313 | 1.767 | 0.455 | +34.63% | 0.859/1.316 | 1.279/2.011 |
| shape-two-hop | 0.465 | 0.484 | 0.019 | +4.08% | 1.355 | 1.440 | 0.084 | +6.21% | 0.694/1.376 | 0.745/1.510 |
| shape-union | 0.550 | 0.530 | -0.020 | -3.62% | 0.756 | 0.629 | -0.126 | -16.70% | 0.581/0.759 | 0.618/0.629 |
| shape-var-length | 0.412 | 0.406 | -0.005 | -1.30% | 1.263 | 1.289 | 0.026 | +2.04% | 0.750/1.406 | 1.130/1.399 |
| shape-wide-contains | 0.705 | 0.713 | 0.008 | +1.15% | 3.350 | 1.896 | -1.454 | -43.41% | 1.225/3.742 | 0.951/2.158 |
| shape-with-aggregate | 152.711 | 152.189 | -0.522 | -0.34% | 165.276 | 170.970 | 5.695 | +3.45% | 162.133/168.174 | 160.482/170.970 |
| shape-with-filter | 0.491 | 0.484 | -0.007 | -1.36% | 0.561 | 0.574 | 0.013 | +2.29% | 0.546/0.584 | 0.542/0.697 |
| targeted/global-wide-aliased | 0.547 | 0.535 | -0.012 | -2.20% | 0.804 | 0.881 | 0.077 | +9.57% | 0.694/1.067 | 0.675/1.100 |
| targeted/global-wide-callee-class | 0.532 | 0.539 | 0.007 | +1.30% | 0.745 | 0.807 | 0.063 | +8.40% | 0.656/0.767 | 0.663/0.807 |
| targeted/global-wide-caller-class | 0.551 | 0.545 | -0.007 | -1.19% | 0.758 | 0.855 | 0.097 | +12.84% | 0.680/0.947 | 0.631/1.080 |
| targeted/global-wide-class-pair | 0.588 | 0.607 | 0.019 | +3.21% | 0.915 | 0.964 | 0.049 | +5.37% | 0.729/0.915 | 0.795/1.197 |
| targeted/global-wide-four-properties | 0.812 | 0.820 | 0.008 | +1.02% | 1.016 | 1.036 | 0.021 | +2.03% | 0.925/1.016 | 0.991/1.036 |
| targeted/global-wide-name-pair | 0.565 | 0.592 | 0.027 | +4.77% | 0.761 | 0.851 | 0.090 | +11.81% | 0.665/0.761 | 0.658/1.078 |
| targeted/global-wide-parameterized-as-literal | 0.539 | 0.535 | -0.004 | -0.77% | 0.829 | 0.723 | -0.106 | -12.84% | 0.630/0.904 | 0.623/0.749 |
| targeted/global-wide-provenance | 0.568 | 0.534 | -0.034 | -5.97% | 0.786 | 0.724 | -0.062 | -7.87% | 0.706/0.786 | 0.642/0.724 |
| targeted/global-wide-wrapped-case-insensitive | 0.559 | 0.537 | -0.022 | -3.93% | 0.846 | 0.832 | -0.014 | -1.65% | 0.673/0.846 | 0.706/0.832 |
| targeted/global-wide-wrapped-case-insensitive-distinct | 0.513 | 0.510 | -0.002 | -0.48% | 0.683 | 0.693 | 0.010 | +1.48% | 0.631/0.683 | 0.662/0.694 |
| zero/global-wide-aliased | 0.316 | 0.307 | -0.009 | -2.98% | 0.451 | 0.564 | 0.113 | +25.03% | 0.402/0.451 | 0.392/0.564 |
| zero/global-wide-callee-class | 0.328 | 0.317 | -0.011 | -3.34% | 0.491 | 0.529 | 0.037 | +7.64% | 0.416/0.491 | 0.398/0.529 |
| zero/global-wide-caller-class | 0.352 | 0.337 | -0.015 | -4.32% | 0.503 | 0.461 | -0.043 | -8.47% | 0.416/0.503 | 0.415/0.461 |
| zero/global-wide-class-pair | 0.343 | 0.350 | 0.007 | +2.04% | 0.557 | 0.552 | -0.004 | -0.79% | 0.423/0.557 | 0.434/0.552 |
| zero/global-wide-four-properties | 0.417 | 0.420 | 0.003 | +0.72% | 0.737 | 0.695 | -0.042 | -5.66% | 0.501/0.737 | 0.508/0.695 |
| zero/global-wide-name-pair | 0.353 | 0.350 | -0.004 | -1.01% | 0.559 | 0.577 | 0.018 | +3.23% | 0.463/0.559 | 0.421/0.577 |
| zero/global-wide-parameterized-as-literal | 0.316 | 0.310 | -0.006 | -1.96% | 0.491 | 0.460 | -0.031 | -6.23% | 0.424/0.491 | 0.410/0.460 |
| zero/global-wide-provenance | 0.312 | 0.315 | 0.002 | +0.79% | 0.485 | 0.472 | -0.014 | -2.87% | 0.326/0.485 | 0.457/0.472 |
| zero/global-wide-wrapped-case-insensitive | 0.315 | 0.315 | 0.000 | +0.01% | 0.468 | 0.424 | -0.043 | -9.29% | 0.440/0.468 | 0.377/0.472 |
| zero/global-wide-wrapped-case-insensitive-distinct | 0.339 | 0.340 | 0.001 | +0.21% | 0.539 | 0.481 | -0.058 | -10.70% | 0.424/0.539 | 0.383/0.513 |

</details>

<details>
<summary>Pre-optimization full73 all three paired rounds</summary>

| Case | Round | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| broad-all-64/global-wide-four-properties | 1 | 1.044 | 1.044 | -0.000 | -0.04% | 1.927 | 1.181 | -0.746 | -38.69% |
| broad-all-64/global-wide-four-properties | 2 | 1.170 | 1.078 | -0.091 | -7.82% | 2.062 | 1.513 | -0.549 | -26.64% |
| broad-all-64/global-wide-four-properties | 3 | 1.182 | 1.103 | -0.079 | -6.66% | 1.343 | 1.320 | -0.023 | -1.74% |
| dense/global-wide-aliased | 1 | 1.072 | 1.070 | -0.002 | -0.22% | 1.663 | 1.659 | -0.003 | -0.21% |
| dense/global-wide-aliased | 2 | 1.101 | 1.087 | -0.014 | -1.28% | 1.391 | 2.254 | 0.864 | +62.09% |
| dense/global-wide-aliased | 3 | 1.148 | 1.077 | -0.070 | -6.10% | 1.847 | 1.545 | -0.302 | -16.35% |
| dense/global-wide-callee-class | 1 | 1.082 | 1.046 | -0.036 | -3.32% | 2.483 | 2.173 | -0.310 | -12.48% |
| dense/global-wide-callee-class | 2 | 1.100 | 1.004 | -0.097 | -8.80% | 1.779 | 1.563 | -0.216 | -12.14% |
| dense/global-wide-callee-class | 3 | 1.042 | 1.058 | 0.015 | +1.47% | 1.658 | 1.688 | 0.030 | +1.83% |
| dense/global-wide-caller-class | 1 | 1.011 | 1.033 | 0.022 | +2.15% | 1.302 | 1.465 | 0.162 | +12.47% |
| dense/global-wide-caller-class | 2 | 1.095 | 1.004 | -0.091 | -8.33% | 1.325 | 1.558 | 0.233 | +17.58% |
| dense/global-wide-caller-class | 3 | 1.038 | 1.058 | 0.021 | +1.99% | 1.445 | 1.157 | -0.288 | -19.93% |
| dense/global-wide-class-pair | 1 | 1.129 | 1.084 | -0.045 | -4.02% | 1.482 | 1.421 | -0.061 | -4.11% |
| dense/global-wide-class-pair | 2 | 1.135 | 1.130 | -0.005 | -0.48% | 1.343 | 1.365 | 0.021 | +1.60% |
| dense/global-wide-class-pair | 3 | 1.135 | 1.081 | -0.054 | -4.75% | 1.397 | 1.290 | -0.107 | -7.68% |
| dense/global-wide-four-properties | 1 | 1.159 | 1.080 | -0.079 | -6.83% | 1.594 | 1.617 | 0.023 | +1.42% |
| dense/global-wide-four-properties | 2 | 1.159 | 1.179 | 0.020 | +1.74% | 2.082 | 1.379 | -0.703 | -33.77% |
| dense/global-wide-four-properties | 3 | 1.088 | 1.114 | 0.026 | +2.37% | 1.273 | 1.239 | -0.035 | -2.73% |
| dense/global-wide-name-pair | 1 | 1.051 | 1.055 | 0.004 | +0.38% | 1.767 | 1.605 | -0.162 | -9.16% |
| dense/global-wide-name-pair | 2 | 1.110 | 1.051 | -0.059 | -5.27% | 2.068 | 1.734 | -0.334 | -16.13% |
| dense/global-wide-name-pair | 3 | 1.062 | 1.081 | 0.018 | +1.73% | 1.800 | 1.740 | -0.060 | -3.35% |
| dense/global-wide-parameterized-as-literal | 1 | 1.079 | 1.169 | 0.090 | +8.39% | 1.384 | 1.725 | 0.341 | +24.63% |
| dense/global-wide-parameterized-as-literal | 2 | 1.088 | 1.055 | -0.033 | -3.02% | 1.376 | 1.904 | 0.527 | +38.32% |
| dense/global-wide-parameterized-as-literal | 3 | 1.121 | 1.046 | -0.075 | -6.66% | 1.597 | 1.274 | -0.323 | -20.23% |
| dense/global-wide-provenance | 1 | 1.095 | 1.072 | -0.023 | -2.14% | 1.481 | 1.368 | -0.112 | -7.59% |
| dense/global-wide-provenance | 2 | 1.086 | 1.048 | -0.038 | -3.46% | 1.827 | 1.444 | -0.382 | -20.93% |
| dense/global-wide-provenance | 3 | 1.089 | 1.136 | 0.047 | +4.30% | 1.519 | 1.347 | -0.172 | -11.32% |
| dense/global-wide-wrapped-case-insensitive | 1 | 1.226 | 1.209 | -0.017 | -1.35% | 1.920 | 1.676 | -0.245 | -12.73% |
| dense/global-wide-wrapped-case-insensitive | 2 | 1.282 | 1.240 | -0.042 | -3.27% | 2.678 | 2.208 | -0.470 | -17.55% |
| dense/global-wide-wrapped-case-insensitive | 3 | 1.199 | 1.244 | 0.044 | +3.68% | 1.667 | 1.407 | -0.260 | -15.62% |
| dense/global-wide-wrapped-case-insensitive-distinct | 1 | 3.191 | 3.172 | -0.019 | -0.59% | 3.788 | 4.611 | 0.823 | +21.73% |
| dense/global-wide-wrapped-case-insensitive-distinct | 2 | 3.333 | 3.253 | -0.079 | -2.38% | 3.743 | 4.249 | 0.506 | +13.53% |
| dense/global-wide-wrapped-case-insensitive-distinct | 3 | 3.261 | 3.231 | -0.030 | -0.92% | 3.712 | 3.606 | -0.105 | -2.84% |
| localized-early/global-wide-four-properties | 1 | 0.627 | 0.654 | 0.027 | +4.27% | 0.710 | 0.883 | 0.173 | +24.35% |
| localized-early/global-wide-four-properties | 2 | 0.674 | 0.590 | -0.084 | -12.47% | 0.995 | 1.196 | 0.201 | +20.26% |
| localized-early/global-wide-four-properties | 3 | 0.639 | 0.638 | -0.001 | -0.18% | 0.885 | 0.748 | -0.137 | -15.52% |
| localized-late/global-wide-four-properties | 1 | 0.816 | 0.805 | -0.010 | -1.26% | 1.232 | 2.177 | 0.945 | +76.72% |
| localized-late/global-wide-four-properties | 2 | 0.817 | 0.809 | -0.008 | -1.02% | 1.550 | 1.395 | -0.155 | -9.98% |
| localized-late/global-wide-four-properties | 3 | 0.783 | 0.819 | 0.036 | +4.60% | 0.937 | 1.106 | 0.169 | +18.04% |
| localized-middle/global-wide-four-properties | 1 | 0.715 | 0.794 | 0.079 | +11.11% | 1.061 | 1.383 | 0.322 | +30.39% |
| localized-middle/global-wide-four-properties | 2 | 0.817 | 0.744 | -0.073 | -8.89% | 1.650 | 0.978 | -0.672 | -40.75% |
| localized-middle/global-wide-four-properties | 3 | 0.773 | 0.761 | -0.012 | -1.56% | 1.484 | 1.014 | -0.470 | -31.68% |
| schema-key-histogram | 1 | 0.885 | 0.914 | 0.028 | +3.18% | 1.075 | 1.207 | 0.132 | +12.30% |
| schema-key-histogram | 2 | 0.925 | 0.907 | -0.018 | -1.92% | 2.017 | 1.926 | -0.091 | -4.50% |
| schema-key-histogram | 3 | 0.934 | 0.946 | 0.012 | +1.29% | 1.080 | 1.702 | 0.622 | +57.62% |
| schema-label-histogram | 1 | 0.606 | 0.580 | -0.026 | -4.26% | 0.839 | 1.379 | 0.540 | +64.43% |
| schema-label-histogram | 2 | 0.659 | 0.582 | -0.077 | -11.63% | 1.091 | 0.857 | -0.234 | -21.46% |
| schema-label-histogram | 3 | 0.649 | 0.642 | -0.007 | -1.01% | 0.784 | 0.906 | 0.121 | +15.48% |
| schema-relationship-histogram | 1 | 14.621 | 14.537 | -0.084 | -0.57% | 15.591 | 15.419 | -0.171 | -1.10% |
| schema-relationship-histogram | 2 | 15.097 | 15.064 | -0.033 | -0.22% | 18.771 | 18.099 | -0.672 | -3.58% |
| schema-relationship-histogram | 3 | 15.026 | 15.007 | -0.019 | -0.13% | 16.780 | 16.488 | -0.292 | -1.74% |
| shape-all-nodes | 1 | 0.751 | 0.735 | -0.016 | -2.15% | 0.950 | 1.181 | 0.230 | +24.23% |
| shape-all-nodes | 2 | 0.781 | 0.720 | -0.060 | -7.74% | 1.506 | 1.145 | -0.361 | -23.96% |
| shape-all-nodes | 3 | 0.739 | 0.790 | 0.051 | +6.90% | 0.952 | 1.240 | 0.288 | +30.22% |
| shape-cartesian | 1 | 0.723 | 0.724 | 0.001 | +0.08% | 0.822 | 0.852 | 0.030 | +3.69% |
| shape-cartesian | 2 | 0.768 | 0.721 | -0.047 | -6.09% | 0.872 | 0.832 | -0.040 | -4.53% |
| shape-cartesian | 3 | 0.755 | 0.735 | -0.020 | -2.61% | 0.814 | 0.813 | -0.001 | -0.11% |
| shape-case-expr | 1 | 0.484 | 0.483 | -0.001 | -0.22% | 0.571 | 0.788 | 0.217 | +37.97% |
| shape-case-expr | 2 | 0.494 | 0.459 | -0.035 | -7.05% | 0.557 | 0.587 | 0.030 | +5.37% |
| shape-case-expr | 3 | 0.470 | 0.492 | 0.022 | +4.73% | 0.667 | 0.577 | -0.090 | -13.48% |
| shape-coalesce | 1 | 0.469 | 0.479 | 0.010 | +2.11% | 0.583 | 0.604 | 0.020 | +3.50% |
| shape-coalesce | 2 | 0.491 | 0.461 | -0.030 | -6.05% | 0.583 | 0.551 | -0.032 | -5.50% |
| shape-coalesce | 3 | 0.495 | 0.477 | -0.017 | -3.46% | 0.654 | 0.569 | -0.086 | -13.08% |
| shape-collect | 1 | 1,730.793 | 1,782.672 | 51.879 | +3.00% | 1,739.418 | 1,822.746 | 83.328 | +4.79% |
| shape-collect | 2 | 1,730.993 | 1,760.441 | 29.448 | +1.70% | 1,751.242 | 1,783.641 | 32.399 | +1.85% |
| shape-collect | 3 | 1,747.363 | 1,746.205 | -1.158 | -0.07% | 1,762.982 | 1,761.922 | -1.060 | -0.06% |
| shape-count-all | 1 | 0.261 | 0.298 | 0.036 | +13.90% | 0.848 | 0.952 | 0.104 | +12.23% |
| shape-count-all | 2 | 0.325 | 0.258 | -0.066 | -20.38% | 0.740 | 1.007 | 0.267 | +36.10% |
| shape-count-all | 3 | 0.290 | 0.321 | 0.031 | +10.66% | 1.278 | 0.424 | -0.855 | -66.85% |
| shape-count-label | 1 | 0.236 | 0.227 | -0.009 | -3.77% | 0.325 | 0.779 | 0.454 | +139.65% |
| shape-count-label | 2 | 0.284 | 0.224 | -0.060 | -20.98% | 1.188 | 0.732 | -0.456 | -38.39% |
| shape-count-label | 3 | 0.239 | 0.239 | -0.000 | -0.02% | 0.441 | 0.812 | 0.370 | +83.90% |
| shape-count-traversal | 1 | 15.127 | 14.896 | -0.231 | -1.52% | 16.699 | 16.086 | -0.613 | -3.67% |
| shape-count-traversal | 2 | 15.854 | 14.858 | -0.996 | -6.28% | 18.651 | 17.044 | -1.607 | -8.62% |
| shape-count-traversal | 3 | 14.990 | 14.691 | -0.299 | -2.00% | 17.217 | 17.451 | 0.234 | +1.36% |
| shape-distinct | 1 | 202.671 | 202.411 | -0.260 | -0.13% | 222.650 | 216.713 | -5.937 | -2.67% |
| shape-distinct | 2 | 205.125 | 204.698 | -0.427 | -0.21% | 217.267 | 225.206 | 7.940 | +3.65% |
| shape-distinct | 3 | 198.221 | 203.305 | 5.084 | +2.56% | 218.516 | 235.706 | 17.190 | +7.87% |
| shape-equality | 1 | 0.574 | 0.569 | -0.005 | -0.88% | 2.020 | 0.916 | -1.104 | -54.64% |
| shape-equality | 2 | 0.638 | 0.509 | -0.128 | -20.14% | 1.931 | 1.943 | 0.012 | +0.64% |
| shape-equality | 3 | 0.563 | 0.574 | 0.011 | +2.01% | 0.750 | 1.233 | 0.483 | +64.45% |
| shape-function-calls | 1 | 0.571 | 0.521 | -0.050 | -8.82% | 0.650 | 0.682 | 0.031 | +4.83% |
| shape-function-calls | 2 | 0.585 | 0.527 | -0.058 | -9.87% | 0.709 | 0.605 | -0.104 | -14.71% |
| shape-function-calls | 3 | 0.537 | 0.574 | 0.037 | +6.81% | 0.676 | 0.678 | 0.001 | +0.20% |
| shape-group-count | 1 | 178.864 | 180.918 | 2.054 | +1.15% | 193.420 | 198.112 | 4.692 | +2.43% |
| shape-group-count | 2 | 182.821 | 180.060 | -2.761 | -1.51% | 193.232 | 195.660 | 2.427 | +1.26% |
| shape-group-count | 3 | 178.505 | 180.810 | 2.306 | +1.29% | 198.788 | 199.106 | 0.318 | +0.16% |
| shape-id-lookup | 1 | 0.565 | 0.581 | 0.016 | +2.80% | 0.783 | 0.814 | 0.030 | +3.88% |
| shape-id-lookup | 2 | 0.603 | 0.535 | -0.068 | -11.29% | 0.927 | 1.041 | 0.113 | +12.22% |
| shape-id-lookup | 3 | 0.599 | 0.605 | 0.006 | +1.06% | 1.328 | 0.917 | -0.411 | -30.93% |
| shape-in-list | 1 | 0.331 | 0.345 | 0.014 | +4.25% | 0.583 | 0.709 | 0.126 | +21.60% |
| shape-in-list | 2 | 0.373 | 0.299 | -0.074 | -19.84% | 0.981 | 0.625 | -0.355 | -36.24% |
| shape-in-list | 3 | 0.352 | 0.373 | 0.021 | +6.06% | 0.695 | 1.400 | 0.705 | +101.41% |
| shape-incoming | 1 | 0.444 | 0.451 | 0.008 | +1.75% | 0.948 | 0.712 | -0.236 | -24.89% |
| shape-incoming | 2 | 0.487 | 0.441 | -0.046 | -9.47% | 0.964 | 1.273 | 0.309 | +32.04% |
| shape-incoming | 3 | 0.474 | 0.492 | 0.017 | +3.65% | 1.112 | 0.689 | -0.424 | -38.09% |
| shape-inequality | 1 | 0.429 | 0.450 | 0.020 | +4.77% | 0.928 | 1.604 | 0.675 | +72.75% |
| shape-inequality | 2 | 0.502 | 0.411 | -0.091 | -18.09% | 1.329 | 0.711 | -0.617 | -46.46% |
| shape-inequality | 3 | 0.453 | 0.451 | -0.002 | -0.43% | 0.882 | 0.623 | -0.259 | -29.40% |
| shape-label-scan-callsite | 1 | 0.454 | 0.489 | 0.036 | +7.89% | 1.146 | 1.666 | 0.520 | +45.40% |
| shape-label-scan-callsite | 2 | 0.523 | 0.430 | -0.093 | -17.74% | 1.291 | 1.617 | 0.326 | +25.25% |
| shape-label-scan-callsite | 3 | 0.454 | 0.466 | 0.012 | +2.63% | 1.279 | 1.933 | 0.654 | +51.17% |
| shape-label-scan-constant | 1 | 0.409 | 0.407 | -0.002 | -0.38% | 1.428 | 0.989 | -0.440 | -30.79% |
| shape-label-scan-constant | 2 | 0.471 | 0.409 | -0.062 | -13.08% | 1.271 | 1.906 | 0.635 | +49.93% |
| shape-label-scan-constant | 3 | 0.408 | 0.438 | 0.030 | +7.40% | 0.982 | 1.081 | 0.100 | +10.15% |
| shape-label-scan-field | 1 | 0.384 | 0.397 | 0.014 | +3.53% | 1.005 | 1.266 | 0.261 | +25.97% |
| shape-label-scan-field | 2 | 0.422 | 0.382 | -0.040 | -9.52% | 1.429 | 1.379 | -0.050 | -3.47% |
| shape-label-scan-field | 3 | 0.407 | 0.412 | 0.005 | +1.32% | 0.848 | 1.485 | 0.637 | +75.17% |
| shape-label-scan-method | 1 | 0.411 | 0.423 | 0.012 | +2.85% | 1.736 | 2.941 | 1.205 | +69.43% |
| shape-label-scan-method | 2 | 0.450 | 0.387 | -0.063 | -13.96% | 1.859 | 1.720 | -0.139 | -7.50% |
| shape-label-scan-method | 3 | 0.398 | 0.438 | 0.040 | +10.11% | 1.506 | 0.847 | -0.659 | -43.74% |
| shape-labels-fn | 1 | 0.470 | 0.498 | 0.027 | +5.83% | 0.622 | 0.683 | 0.061 | +9.73% |
| shape-labels-fn | 2 | 0.494 | 0.481 | -0.014 | -2.79% | 0.629 | 0.586 | -0.042 | -6.74% |
| shape-labels-fn | 3 | 0.500 | 0.487 | -0.012 | -2.47% | 0.612 | 0.577 | -0.035 | -5.67% |
| shape-null-check | 1 | 0.395 | 0.386 | -0.009 | -2.30% | 0.811 | 1.005 | 0.193 | +23.82% |
| shape-null-check | 2 | 0.430 | 0.419 | -0.011 | -2.58% | 0.708 | 0.501 | -0.207 | -29.21% |
| shape-null-check | 3 | 0.411 | 0.447 | 0.036 | +8.89% | 0.754 | 0.632 | -0.122 | -16.21% |
| shape-one-hop | 1 | 0.490 | 0.479 | -0.011 | -2.27% | 1.103 | 0.999 | -0.104 | -9.43% |
| shape-one-hop | 2 | 0.506 | 0.497 | -0.009 | -1.76% | 0.677 | 0.987 | 0.310 | +45.77% |
| shape-one-hop | 3 | 0.483 | 0.499 | 0.015 | +3.20% | 1.030 | 0.718 | -0.312 | -30.29% |
| shape-one-hop-typed | 1 | 0.460 | 0.444 | -0.016 | -3.46% | 1.257 | 0.681 | -0.576 | -45.80% |
| shape-one-hop-typed | 2 | 0.467 | 0.465 | -0.002 | -0.45% | 1.497 | 1.325 | -0.171 | -11.43% |
| shape-one-hop-typed | 3 | 0.448 | 0.505 | 0.057 | +12.82% | 1.226 | 0.625 | -0.601 | -49.01% |
| shape-order-by | 1 | 0.830 | 0.843 | 0.013 | +1.57% | 0.900 | 0.898 | -0.003 | -0.29% |
| shape-order-by | 2 | 0.864 | 0.856 | -0.008 | -0.93% | 0.966 | 0.974 | 0.008 | +0.80% |
| shape-order-by | 3 | 0.878 | 0.851 | -0.027 | -3.08% | 0.973 | 0.954 | -0.019 | -1.94% |
| shape-order-by-desc | 1 | 0.573 | 0.562 | -0.011 | -1.99% | 0.661 | 0.624 | -0.037 | -5.58% |
| shape-order-by-desc | 2 | 0.576 | 0.567 | -0.008 | -1.46% | 0.684 | 0.692 | 0.008 | +1.16% |
| shape-order-by-desc | 3 | 0.598 | 0.592 | -0.006 | -1.06% | 0.668 | 0.794 | 0.125 | +18.79% |
| shape-path | 1 | 0.545 | 0.551 | 0.007 | +1.22% | 1.065 | 0.874 | -0.191 | -17.92% |
| shape-path | 2 | 0.568 | 0.509 | -0.059 | -10.45% | 1.085 | 1.164 | 0.080 | +7.36% |
| shape-path | 3 | 0.554 | 0.559 | 0.004 | +0.80% | 1.351 | 0.750 | -0.601 | -44.46% |
| shape-regex | 1 | 0.430 | 0.422 | -0.008 | -1.78% | 0.728 | 0.650 | -0.078 | -10.72% |
| shape-regex | 2 | 0.455 | 0.438 | -0.017 | -3.78% | 0.901 | 0.689 | -0.213 | -23.60% |
| shape-regex | 3 | 0.428 | 0.483 | 0.055 | +12.95% | 0.678 | 0.704 | 0.026 | +3.87% |
| shape-skip | 1 | 0.984 | 0.921 | -0.063 | -6.41% | 1.366 | 1.042 | -0.324 | -23.71% |
| shape-skip | 2 | 0.988 | 0.961 | -0.026 | -2.66% | 1.245 | 1.090 | -0.155 | -12.42% |
| shape-skip | 3 | 0.951 | 0.949 | -0.002 | -0.19% | 1.073 | 1.159 | 0.086 | +7.97% |
| shape-traverse-filtered | 1 | 0.748 | 0.741 | -0.007 | -0.94% | 1.177 | 1.295 | 0.118 | +10.00% |
| shape-traverse-filtered | 2 | 0.753 | 0.683 | -0.070 | -9.25% | 1.316 | 1.279 | -0.037 | -2.82% |
| shape-traverse-filtered | 3 | 0.739 | 0.767 | 0.027 | +3.70% | 0.859 | 2.011 | 1.152 | +134.10% |
| shape-two-hop | 1 | 0.455 | 0.461 | 0.006 | +1.23% | 1.040 | 0.745 | -0.295 | -28.38% |
| shape-two-hop | 2 | 0.476 | 0.455 | -0.021 | -4.36% | 0.694 | 1.414 | 0.720 | +103.79% |
| shape-two-hop | 3 | 0.460 | 0.531 | 0.071 | +15.46% | 1.376 | 1.510 | 0.134 | +9.77% |
| shape-union | 1 | 0.538 | 0.519 | -0.019 | -3.54% | 0.581 | 0.620 | 0.039 | +6.73% |
| shape-union | 2 | 0.587 | 0.530 | -0.057 | -9.67% | 0.759 | 0.629 | -0.129 | -17.05% |
| shape-union | 3 | 0.549 | 0.534 | -0.015 | -2.72% | 0.626 | 0.618 | -0.008 | -1.28% |
| shape-var-length | 1 | 0.398 | 0.413 | 0.016 | +3.96% | 1.406 | 1.130 | -0.276 | -19.64% |
| shape-var-length | 2 | 0.471 | 0.380 | -0.090 | -19.17% | 0.750 | 1.282 | 0.532 | +70.97% |
| shape-var-length | 3 | 0.408 | 0.429 | 0.020 | +4.98% | 1.263 | 1.399 | 0.135 | +10.71% |
| shape-wide-contains | 1 | 0.676 | 0.727 | 0.051 | +7.53% | 1.225 | 1.787 | 0.562 | +45.86% |
| shape-wide-contains | 2 | 0.736 | 0.688 | -0.048 | -6.48% | 3.742 | 2.158 | -1.584 | -42.33% |
| shape-wide-contains | 3 | 0.687 | 0.707 | 0.019 | +2.81% | 3.125 | 0.951 | -2.174 | -69.58% |
| shape-with-aggregate | 1 | 150.770 | 152.189 | 1.419 | +0.94% | 165.276 | 170.970 | 5.695 | +3.45% |
| shape-with-aggregate | 2 | 154.639 | 149.178 | -5.461 | -3.53% | 162.133 | 166.917 | 4.784 | +2.95% |
| shape-with-aggregate | 3 | 149.166 | 154.562 | 5.396 | +3.62% | 168.174 | 160.482 | -7.692 | -4.57% |
| shape-with-filter | 1 | 0.469 | 0.488 | 0.019 | +4.11% | 0.554 | 0.697 | 0.143 | +25.79% |
| shape-with-filter | 2 | 0.500 | 0.467 | -0.033 | -6.67% | 0.584 | 0.542 | -0.042 | -7.19% |
| shape-with-filter | 3 | 0.491 | 0.473 | -0.018 | -3.64% | 0.546 | 0.561 | 0.016 | +2.88% |
| targeted/global-wide-aliased | 1 | 0.516 | 0.527 | 0.011 | +2.10% | 1.067 | 0.851 | -0.216 | -20.22% |
| targeted/global-wide-aliased | 2 | 0.557 | 0.518 | -0.038 | -6.84% | 0.728 | 1.100 | 0.372 | +51.17% |
| targeted/global-wide-aliased | 3 | 0.553 | 0.593 | 0.041 | +7.35% | 0.694 | 0.675 | -0.020 | -2.85% |
| targeted/global-wide-callee-class | 1 | 0.530 | 0.506 | -0.024 | -4.44% | 0.656 | 0.729 | 0.072 | +11.01% |
| targeted/global-wide-callee-class | 2 | 0.560 | 0.520 | -0.041 | -7.26% | 0.767 | 0.807 | 0.040 | +5.22% |
| targeted/global-wide-callee-class | 3 | 0.519 | 0.572 | 0.053 | +10.11% | 0.745 | 0.663 | -0.082 | -11.02% |
| targeted/global-wide-caller-class | 1 | 0.530 | 0.518 | -0.012 | -2.31% | 0.947 | 0.631 | -0.316 | -33.39% |
| targeted/global-wide-caller-class | 2 | 0.551 | 0.539 | -0.013 | -2.29% | 0.749 | 0.834 | 0.085 | +11.33% |
| targeted/global-wide-caller-class | 3 | 0.570 | 0.588 | 0.018 | +3.16% | 0.680 | 1.080 | 0.400 | +58.91% |
| targeted/global-wide-class-pair | 1 | 0.588 | 0.564 | -0.024 | -4.02% | 0.775 | 0.891 | 0.116 | +14.97% |
| targeted/global-wide-class-pair | 2 | 0.590 | 0.564 | -0.026 | -4.37% | 0.915 | 1.197 | 0.283 | +30.90% |
| targeted/global-wide-class-pair | 3 | 0.586 | 0.626 | 0.041 | +6.93% | 0.729 | 0.795 | 0.066 | +9.07% |
| targeted/global-wide-four-properties | 1 | 0.762 | 0.817 | 0.056 | +7.31% | 1.016 | 1.036 | 0.021 | +2.03% |
| targeted/global-wide-four-properties | 2 | 0.810 | 0.789 | -0.021 | -2.63% | 0.925 | 0.991 | 0.066 | +7.17% |
| targeted/global-wide-four-properties | 3 | 0.833 | 0.854 | 0.021 | +2.50% | 0.959 | 1.025 | 0.066 | +6.90% |
| targeted/global-wide-name-pair | 1 | 0.547 | 0.525 | -0.022 | -4.00% | 0.665 | 0.658 | -0.007 | -1.08% |
| targeted/global-wide-name-pair | 2 | 0.587 | 0.580 | -0.008 | -1.31% | 0.761 | 0.813 | 0.052 | +6.88% |
| targeted/global-wide-name-pair | 3 | 0.557 | 0.624 | 0.068 | +12.12% | 0.758 | 1.078 | 0.320 | +42.24% |
| targeted/global-wide-parameterized-as-literal | 1 | 0.575 | 0.531 | -0.044 | -7.66% | 0.904 | 0.623 | -0.281 | -31.10% |
| targeted/global-wide-parameterized-as-literal | 2 | 0.545 | 0.530 | -0.015 | -2.72% | 0.630 | 0.670 | 0.040 | +6.40% |
| targeted/global-wide-parameterized-as-literal | 3 | 0.530 | 0.542 | 0.012 | +2.24% | 0.701 | 0.749 | 0.048 | +6.79% |
| targeted/global-wide-provenance | 1 | 0.553 | 0.548 | -0.004 | -0.78% | 0.738 | 0.642 | -0.097 | -13.07% |
| targeted/global-wide-provenance | 2 | 0.569 | 0.507 | -0.062 | -10.87% | 0.786 | 0.724 | -0.062 | -7.87% |
| targeted/global-wide-provenance | 3 | 0.568 | 0.534 | -0.034 | -5.97% | 0.706 | 0.716 | 0.010 | +1.42% |
| targeted/global-wide-wrapped-case-insensitive | 1 | 0.541 | 0.521 | -0.020 | -3.68% | 0.673 | 0.832 | 0.160 | +23.76% |
| targeted/global-wide-wrapped-case-insensitive | 2 | 0.568 | 0.537 | -0.031 | -5.46% | 0.846 | 0.706 | -0.140 | -16.55% |
| targeted/global-wide-wrapped-case-insensitive | 3 | 0.559 | 0.565 | 0.006 | +1.13% | 0.727 | 0.729 | 0.003 | +0.40% |
| targeted/global-wide-wrapped-case-insensitive-distinct | 1 | 0.499 | 0.488 | -0.011 | -2.26% | 0.631 | 0.662 | 0.032 | +5.04% |
| targeted/global-wide-wrapped-case-insensitive-distinct | 2 | 0.545 | 0.536 | -0.010 | -1.75% | 0.683 | 0.690 | 0.008 | +1.13% |
| targeted/global-wide-wrapped-case-insensitive-distinct | 3 | 0.511 | 0.516 | 0.005 | +0.92% | 0.648 | 0.694 | 0.046 | +7.12% |
| zero/global-wide-aliased | 1 | 0.305 | 0.293 | -0.012 | -3.80% | 0.402 | 0.564 | 0.162 | +40.39% |
| zero/global-wide-aliased | 2 | 0.329 | 0.311 | -0.018 | -5.36% | 0.421 | 0.406 | -0.015 | -3.59% |
| zero/global-wide-aliased | 3 | 0.308 | 0.308 | -0.000 | -0.05% | 0.451 | 0.392 | -0.059 | -13.11% |
| zero/global-wide-callee-class | 1 | 0.304 | 0.294 | -0.010 | -3.32% | 0.416 | 0.529 | 0.112 | +26.96% |
| zero/global-wide-callee-class | 2 | 0.334 | 0.318 | -0.016 | -4.84% | 0.491 | 0.444 | -0.047 | -9.57% |
| zero/global-wide-callee-class | 3 | 0.323 | 0.319 | -0.003 | -1.06% | 0.474 | 0.398 | -0.075 | -15.91% |
| zero/global-wide-caller-class | 1 | 0.321 | 0.338 | 0.017 | +5.32% | 0.416 | 0.461 | 0.045 | +10.87% |
| zero/global-wide-caller-class | 2 | 0.364 | 0.342 | -0.022 | -6.02% | 0.503 | 0.415 | -0.088 | -17.49% |
| zero/global-wide-caller-class | 3 | 0.346 | 0.319 | -0.027 | -7.75% | 0.447 | 0.430 | -0.018 | -3.94% |
| zero/global-wide-class-pair | 1 | 0.317 | 0.335 | 0.018 | +5.81% | 0.423 | 0.505 | 0.082 | +19.49% |
| zero/global-wide-class-pair | 2 | 0.374 | 0.352 | -0.022 | -5.80% | 0.557 | 0.434 | -0.123 | -22.11% |
| zero/global-wide-class-pair | 3 | 0.323 | 0.361 | 0.038 | +11.79% | 0.518 | 0.552 | 0.034 | +6.61% |
| zero/global-wide-four-properties | 1 | 0.387 | 0.393 | 0.006 | +1.57% | 0.501 | 0.695 | 0.194 | +38.75% |
| zero/global-wide-four-properties | 2 | 0.445 | 0.423 | -0.022 | -4.90% | 0.581 | 0.515 | -0.066 | -11.41% |
| zero/global-wide-four-properties | 3 | 0.417 | 0.423 | 0.006 | +1.32% | 0.737 | 0.508 | -0.229 | -31.13% |
| zero/global-wide-name-pair | 1 | 0.324 | 0.357 | 0.033 | +10.32% | 0.501 | 0.577 | 0.076 | +15.22% |
| zero/global-wide-name-pair | 2 | 0.376 | 0.334 | -0.042 | -11.05% | 0.559 | 0.464 | -0.095 | -16.98% |
| zero/global-wide-name-pair | 3 | 0.348 | 0.350 | 0.002 | +0.45% | 0.463 | 0.421 | -0.042 | -9.09% |
| zero/global-wide-parameterized-as-literal | 1 | 0.310 | 0.309 | -0.002 | -0.58% | 0.424 | 0.460 | 0.036 | +8.55% |
| zero/global-wide-parameterized-as-literal | 2 | 0.330 | 0.310 | -0.021 | -6.28% | 0.470 | 0.451 | -0.020 | -4.20% |
| zero/global-wide-parameterized-as-literal | 3 | 0.311 | 0.308 | -0.003 | -0.83% | 0.491 | 0.410 | -0.081 | -16.57% |
| zero/global-wide-provenance | 1 | 0.305 | 0.295 | -0.009 | -3.10% | 0.326 | 0.457 | 0.131 | +40.10% |
| zero/global-wide-provenance | 2 | 0.333 | 0.327 | -0.006 | -1.83% | 0.429 | 0.468 | 0.039 | +9.10% |
| zero/global-wide-provenance | 3 | 0.309 | 0.308 | -0.001 | -0.35% | 0.485 | 0.472 | -0.014 | -2.87% |
| zero/global-wide-wrapped-case-insensitive | 1 | 0.301 | 0.311 | 0.011 | +3.49% | 0.440 | 0.377 | -0.063 | -14.23% |
| zero/global-wide-wrapped-case-insensitive | 2 | 0.325 | 0.312 | -0.013 | -3.86% | 0.468 | 0.401 | -0.067 | -14.23% |
| zero/global-wide-wrapped-case-insensitive | 3 | 0.311 | 0.320 | 0.009 | +2.79% | 0.457 | 0.472 | 0.014 | +3.14% |
| zero/global-wide-wrapped-case-insensitive-distinct | 1 | 0.315 | 0.331 | 0.016 | +4.97% | 0.506 | 0.383 | -0.122 | -24.20% |
| zero/global-wide-wrapped-case-insensitive-distinct | 2 | 0.346 | 0.330 | -0.016 | -4.62% | 0.424 | 0.445 | 0.021 | +4.90% |
| zero/global-wide-wrapped-case-insensitive-distinct | 3 | 0.334 | 0.351 | 0.017 | +5.20% | 0.539 | 0.513 | -0.026 | -4.76% |

</details>

## Retained collect 152 positive increment

Attempt 312 built `152abe6e87ca8d8f668783b7b1cfd8dea2f481c7` and its original full-load/full-query data are preserved under the selected intermediate paths. It delivered a large collect and CPU benefit, while grouped-query p95 increases remained and could not be offset by CPU savings. This is a retained intermediate increment, not a performance-acceptance claim. All distributions and resource flags remain below. A request-overlap audit found more group/group concurrency after collect became fast; that changed overlap is evidence about this mix, not proof of the entire regression cause. A new global 64-graph profile motivated the compact grouped-count composition. The second attribution and cumulative confirmation remain separate measured sets.

<details>
<summary>Retained 152 full73 pooled distributions</summary>

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| broad-all-64/global-wide-four-properties | 1.075 | 1.253 | 0.178 | +16.54% | 1.707 | 1.622 | -0.085 | -4.98% | 1.291/1.707 | 1.452/1.857 |
| dense/global-wide-aliased | 1.107 | 1.235 | 0.128 | +11.52% | 1.664 | 2.132 | 0.469 | +28.17% | 1.586/1.845 | 1.584/2.426 |
| dense/global-wide-callee-class | 1.090 | 1.145 | 0.055 | +5.01% | 1.669 | 1.717 | 0.048 | +2.89% | 1.453/1.669 | 1.454/1.717 |
| dense/global-wide-caller-class | 1.078 | 1.152 | 0.074 | +6.85% | 1.715 | 1.647 | -0.069 | -4.00% | 1.683/1.863 | 1.368/1.691 |
| dense/global-wide-class-pair | 1.106 | 1.252 | 0.145 | +13.15% | 1.465 | 2.096 | 0.631 | +43.09% | 1.326/1.465 | 1.576/2.165 |
| dense/global-wide-four-properties | 1.129 | 1.219 | 0.090 | +8.01% | 1.409 | 1.845 | 0.437 | +31.02% | 1.279/1.644 | 1.449/1.845 |
| dense/global-wide-name-pair | 1.098 | 1.212 | 0.114 | +10.37% | 1.748 | 1.937 | 0.188 | +10.78% | 1.351/1.748 | 1.399/2.018 |
| dense/global-wide-parameterized-as-literal | 1.089 | 1.232 | 0.144 | +13.18% | 1.699 | 1.578 | -0.121 | -7.10% | 1.353/2.072 | 1.469/1.604 |
| dense/global-wide-provenance | 1.089 | 1.224 | 0.135 | +12.36% | 1.401 | 1.709 | 0.308 | +22.00% | 1.285/1.401 | 1.379/1.709 |
| dense/global-wide-wrapped-case-insensitive | 1.251 | 1.389 | 0.138 | +11.05% | 2.077 | 1.804 | -0.273 | -13.15% | 1.356/2.077 | 1.486/1.804 |
| dense/global-wide-wrapped-case-insensitive-distinct | 3.264 | 3.507 | 0.244 | +7.46% | 4.392 | 4.123 | -0.269 | -6.12% | 4.132/4.539 | 4.009/4.123 |
| localized-early/global-wide-four-properties | 0.638 | 0.625 | -0.013 | -1.97% | 1.190 | 1.321 | 0.131 | +10.97% | 1.005/1.268 | 0.824/1.321 |
| localized-late/global-wide-four-properties | 0.794 | 0.759 | -0.035 | -4.47% | 1.228 | 1.015 | -0.214 | -17.40% | 1.074/1.532 | 0.881/1.015 |
| localized-middle/global-wide-four-properties | 0.759 | 0.718 | -0.041 | -5.45% | 1.544 | 1.002 | -0.541 | -35.07% | 1.197/1.839 | 0.810/1.047 |
| schema-key-histogram | 0.896 | 0.992 | 0.096 | +10.72% | 2.559 | 1.203 | -1.356 | -52.98% | 1.466/2.824 | 1.071/1.203 |
| schema-label-histogram | 0.595 | 0.682 | 0.087 | +14.68% | 1.914 | 1.042 | -0.872 | -45.55% | 0.739/1.923 | 0.878/1.221 |
| schema-relationship-histogram | 14.899 | 15.892 | 0.993 | +6.67% | 19.237 | 18.798 | -0.438 | -2.28% | 16.100/21.135 | 16.490/20.179 |
| shape-all-nodes | 0.757 | 0.865 | 0.108 | +14.28% | 1.652 | 1.758 | 0.106 | +6.44% | 1.395/2.135 | 1.407/1.758 |
| shape-cartesian | 0.731 | 0.425 | -0.306 | -41.81% | 0.864 | 0.831 | -0.033 | -3.85% | 0.794/0.868 | 0.631/0.861 |
| shape-case-expr | 0.469 | 0.594 | 0.126 | +26.83% | 0.643 | 1.081 | 0.438 | +68.06% | 0.574/0.662 | 0.826/1.082 |
| shape-coalesce | 0.473 | 0.558 | 0.085 | +18.01% | 0.635 | 1.050 | 0.415 | +65.40% | 0.521/0.640 | 0.876/1.065 |
| shape-collect | 1,734.034 | 0.788 | -1,733.246 | -99.95% | 1,824.280 | 0.967 | -1,823.313 | -99.95% | 1,764.240/1,849.050 | 0.821/0.992 |
| shape-count-all | 0.272 | 0.387 | 0.115 | +42.34% | 1.286 | 0.817 | -0.469 | -36.46% | 0.615/1.466 | 0.609/0.817 |
| shape-count-label | 0.233 | 0.314 | 0.081 | +35.00% | 0.956 | 0.682 | -0.274 | -28.62% | 0.792/0.973 | 0.413/0.795 |
| shape-count-traversal | 15.315 | 15.578 | 0.263 | +1.72% | 18.209 | 18.019 | -0.190 | -1.04% | 16.291/20.967 | 15.548/18.035 |
| shape-distinct | 201.396 | 235.364 | 33.969 | +16.87% | 232.514 | 255.208 | 22.695 | +9.76% | 217.028/244.833 | 246.769/255.208 |
| shape-equality | 0.572 | 0.683 | 0.110 | +19.30% | 2.830 | 1.614 | -1.216 | -42.97% | 0.868/2.830 | 0.927/1.614 |
| shape-function-calls | 0.550 | 0.691 | 0.142 | +25.81% | 0.723 | 1.582 | 0.859 | +118.88% | 0.633/0.728 | 1.011/1.941 |
| shape-group-count | 181.427 | 215.476 | 34.049 | +18.77% | 207.114 | 227.338 | 20.224 | +9.76% | 194.256/210.081 | 222.751/231.387 |
| shape-id-lookup | 0.552 | 0.708 | 0.156 | +28.24% | 1.306 | 1.305 | -0.000 | -0.02% | 0.792/1.306 | 0.936/1.456 |
| shape-in-list | 0.330 | 0.437 | 0.107 | +32.40% | 0.867 | 0.662 | -0.205 | -23.64% | 0.753/0.867 | 0.607/0.662 |
| shape-incoming | 0.476 | 0.567 | 0.091 | +19.04% | 1.355 | 0.994 | -0.360 | -26.60% | 0.601/1.355 | 0.643/1.128 |
| shape-inequality | 0.434 | 0.543 | 0.109 | +25.17% | 1.961 | 1.314 | -0.648 | -33.03% | 1.345/1.980 | 0.939/1.314 |
| shape-label-scan-callsite | 0.484 | 0.534 | 0.050 | +10.31% | 1.896 | 1.298 | -0.598 | -31.53% | 1.310/2.559 | 0.700/1.298 |
| shape-label-scan-constant | 0.417 | 0.488 | 0.071 | +17.14% | 1.874 | 2.472 | 0.599 | +31.94% | 1.676/1.874 | 0.642/3.180 |
| shape-label-scan-field | 0.422 | 0.499 | 0.078 | +18.41% | 1.939 | 2.466 | 0.527 | +27.16% | 1.195/1.939 | 0.613/2.466 |
| shape-label-scan-method | 0.415 | 0.500 | 0.085 | +20.59% | 2.502 | 0.917 | -1.585 | -63.33% | 1.400/3.031 | 0.663/0.917 |
| shape-labels-fn | 0.485 | 0.619 | 0.133 | +27.50% | 0.729 | 1.081 | 0.352 | +48.27% | 0.567/0.729 | 0.852/1.207 |
| shape-null-check | 0.401 | 0.484 | 0.083 | +20.78% | 0.870 | 0.869 | -0.001 | -0.09% | 0.626/0.870 | 0.799/0.869 |
| shape-one-hop | 0.487 | 0.551 | 0.064 | +13.24% | 1.051 | 1.049 | -0.002 | -0.17% | 0.846/1.385 | 0.666/1.049 |
| shape-one-hop-typed | 0.460 | 0.551 | 0.091 | +19.85% | 1.123 | 1.008 | -0.115 | -10.27% | 0.863/1.127 | 0.687/1.014 |
| shape-order-by | 0.859 | 1.022 | 0.163 | +19.02% | 1.012 | 3.026 | 2.013 | +198.88% | 0.892/1.082 | 2.192/3.406 |
| shape-order-by-desc | 0.573 | 0.711 | 0.138 | +24.08% | 0.699 | 2.350 | 1.652 | +236.36% | 0.658/0.727 | 1.927/2.489 |
| shape-path | 0.566 | 0.652 | 0.085 | +15.08% | 1.023 | 1.041 | 0.018 | +1.72% | 0.982/1.023 | 0.800/1.041 |
| shape-regex | 0.442 | 0.539 | 0.097 | +21.95% | 1.117 | 0.887 | -0.230 | -20.59% | 0.661/1.405 | 0.628/1.108 |
| shape-skip | 0.964 | 1.227 | 0.263 | +27.28% | 1.146 | 2.679 | 1.533 | +133.69% | 1.077/1.157 | 2.504/3.515 |
| shape-traverse-filtered | 0.749 | 0.890 | 0.141 | +18.81% | 1.294 | 1.378 | 0.084 | +6.46% | 0.904/1.314 | 1.010/1.464 |
| shape-two-hop | 0.479 | 0.578 | 0.100 | +20.81% | 1.183 | 0.984 | -0.199 | -16.82% | 0.638/1.661 | 0.645/0.984 |
| shape-union | 0.549 | 0.489 | -0.060 | -10.92% | 0.656 | 0.598 | -0.058 | -8.85% | 0.646/0.688 | 0.553/0.598 |
| shape-var-length | 0.439 | 0.517 | 0.078 | +17.79% | 0.993 | 1.094 | 0.100 | +10.11% | 0.629/1.055 | 0.592/1.137 |
| shape-wide-contains | 0.716 | 0.848 | 0.132 | +18.44% | 1.914 | 1.792 | -0.121 | -6.34% | 0.823/2.334 | 1.010/1.792 |
| shape-with-aggregate | 152.544 | 184.441 | 31.897 | +20.91% | 175.574 | 195.108 | 19.534 | +11.13% | 167.491/176.922 | 191.943/200.907 |
| shape-with-filter | 0.493 | 0.618 | 0.125 | +25.46% | 0.658 | 1.166 | 0.508 | +77.16% | 0.571/0.669 | 0.922/1.190 |
| targeted/global-wide-aliased | 0.575 | 0.535 | -0.040 | -6.99% | 0.798 | 0.724 | -0.074 | -9.32% | 0.756/0.798 | 0.672/0.809 |
| targeted/global-wide-callee-class | 0.542 | 0.551 | 0.009 | +1.65% | 0.886 | 0.750 | -0.136 | -15.37% | 0.738/0.886 | 0.628/0.750 |
| targeted/global-wide-caller-class | 0.547 | 0.567 | 0.020 | +3.61% | 0.896 | 0.764 | -0.131 | -14.67% | 0.758/0.896 | 0.641/0.815 |
| targeted/global-wide-class-pair | 0.593 | 0.578 | -0.015 | -2.47% | 0.905 | 0.972 | 0.067 | +7.41% | 0.720/0.951 | 0.891/0.994 |
| targeted/global-wide-four-properties | 0.813 | 0.656 | -0.157 | -19.29% | 1.206 | 0.919 | -0.287 | -23.82% | 1.075/1.206 | 0.802/1.216 |
| targeted/global-wide-name-pair | 0.582 | 0.556 | -0.025 | -4.37% | 1.048 | 0.830 | -0.218 | -20.78% | 0.739/1.048 | 0.729/0.952 |
| targeted/global-wide-parameterized-as-literal | 0.540 | 0.539 | -0.001 | -0.26% | 0.754 | 0.705 | -0.049 | -6.53% | 0.687/0.913 | 0.642/0.748 |
| targeted/global-wide-provenance | 0.534 | 0.522 | -0.012 | -2.24% | 0.863 | 0.749 | -0.114 | -13.17% | 0.848/0.865 | 0.678/0.791 |
| targeted/global-wide-wrapped-case-insensitive | 0.558 | 0.524 | -0.034 | -6.09% | 1.045 | 0.701 | -0.344 | -32.93% | 0.685/1.045 | 0.625/0.701 |
| targeted/global-wide-wrapped-case-insensitive-distinct | 0.545 | 0.593 | 0.048 | +8.90% | 0.847 | 0.764 | -0.083 | -9.81% | 0.657/0.899 | 0.737/0.764 |
| zero/global-wide-aliased | 0.319 | 0.325 | 0.006 | +1.84% | 0.485 | 0.494 | 0.009 | +1.96% | 0.427/0.485 | 0.479/0.494 |
| zero/global-wide-callee-class | 0.324 | 0.334 | 0.010 | +3.02% | 0.699 | 0.502 | -0.197 | -28.19% | 0.511/0.699 | 0.401/0.502 |
| zero/global-wide-caller-class | 0.342 | 0.337 | -0.005 | -1.43% | 0.787 | 0.489 | -0.298 | -37.86% | 0.468/0.935 | 0.408/0.489 |
| zero/global-wide-class-pair | 0.350 | 0.348 | -0.002 | -0.63% | 0.500 | 0.479 | -0.020 | -4.09% | 0.414/0.500 | 0.428/0.479 |
| zero/global-wide-four-properties | 0.422 | 0.425 | 0.002 | +0.50% | 0.593 | 0.538 | -0.055 | -9.23% | 0.532/0.593 | 0.506/0.538 |
| zero/global-wide-name-pair | 0.348 | 0.347 | -0.001 | -0.14% | 0.980 | 0.452 | -0.527 | -53.82% | 0.461/1.142 | 0.420/0.452 |
| zero/global-wide-parameterized-as-literal | 0.313 | 0.315 | 0.002 | +0.75% | 0.477 | 0.459 | -0.018 | -3.81% | 0.440/0.560 | 0.446/0.562 |
| zero/global-wide-provenance | 0.304 | 0.334 | 0.030 | +9.94% | 0.617 | 0.499 | -0.118 | -19.12% | 0.399/0.617 | 0.430/0.499 |
| zero/global-wide-wrapped-case-insensitive | 0.308 | 0.328 | 0.019 | +6.30% | 0.516 | 0.453 | -0.063 | -12.24% | 0.451/0.686 | 0.394/0.492 |
| zero/global-wide-wrapped-case-insensitive-distinct | 0.334 | 0.374 | 0.039 | +11.78% | 0.692 | 0.513 | -0.179 | -25.82% | 0.658/0.779 | 0.472/0.603 |

</details>

<details>
<summary>Retained 152 full73 every paired round</summary>

| Case | Round | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| broad-all-64/global-wide-four-properties | 1 | 1.052 | 1.292 | 0.240 | +22.77% | 1.291 | 1.857 | 0.566 | +43.84% |
| broad-all-64/global-wide-four-properties | 2 | 1.059 | 1.285 | 0.225 | +21.25% | 1.707 | 1.462 | -0.246 | -14.40% |
| broad-all-64/global-wide-four-properties | 3 | 1.128 | 1.210 | 0.081 | +7.21% | 1.541 | 1.452 | -0.089 | -5.75% |
| dense/global-wide-aliased | 1 | 1.101 | 1.240 | 0.138 | +12.55% | 1.664 | 2.426 | 0.763 | +45.84% |
| dense/global-wide-aliased | 2 | 1.089 | 1.263 | 0.173 | +15.92% | 1.586 | 2.123 | 0.537 | +33.84% |
| dense/global-wide-aliased | 3 | 1.190 | 1.165 | -0.025 | -2.07% | 1.845 | 1.584 | -0.261 | -14.15% |
| dense/global-wide-callee-class | 1 | 1.119 | 1.161 | 0.042 | +3.75% | 1.669 | 1.578 | -0.092 | -5.49% |
| dense/global-wide-callee-class | 2 | 1.059 | 1.151 | 0.092 | +8.65% | 1.453 | 1.717 | 0.264 | +18.19% |
| dense/global-wide-callee-class | 3 | 1.121 | 1.126 | 0.005 | +0.45% | 1.577 | 1.454 | -0.123 | -7.78% |
| dense/global-wide-caller-class | 1 | 1.090 | 1.151 | 0.061 | +5.64% | 1.863 | 1.691 | -0.172 | -9.26% |
| dense/global-wide-caller-class | 2 | 1.039 | 1.206 | 0.167 | +16.10% | 1.709 | 1.647 | -0.062 | -3.65% |
| dense/global-wide-caller-class | 3 | 1.082 | 1.105 | 0.023 | +2.11% | 1.683 | 1.368 | -0.315 | -18.71% |
| dense/global-wide-class-pair | 1 | 1.157 | 1.250 | 0.093 | +8.05% | 1.465 | 1.576 | 0.111 | +7.61% |
| dense/global-wide-class-pair | 2 | 1.040 | 1.332 | 0.292 | +28.09% | 1.326 | 1.687 | 0.361 | +27.21% |
| dense/global-wide-class-pair | 3 | 1.137 | 1.208 | 0.071 | +6.24% | 1.424 | 2.165 | 0.741 | +52.00% |
| dense/global-wide-four-properties | 1 | 1.118 | 1.234 | 0.116 | +10.42% | 1.644 | 1.845 | 0.201 | +12.22% |
| dense/global-wide-four-properties | 2 | 1.118 | 1.230 | 0.112 | +10.05% | 1.279 | 1.519 | 0.240 | +18.75% |
| dense/global-wide-four-properties | 3 | 1.183 | 1.159 | -0.024 | -2.07% | 1.388 | 1.449 | 0.060 | +4.33% |
| dense/global-wide-name-pair | 1 | 1.097 | 1.207 | 0.110 | +9.98% | 1.351 | 2.018 | 0.667 | +49.34% |
| dense/global-wide-name-pair | 2 | 1.045 | 1.240 | 0.195 | +18.70% | 1.483 | 1.508 | 0.025 | +1.69% |
| dense/global-wide-name-pair | 3 | 1.139 | 1.132 | -0.007 | -0.58% | 1.748 | 1.399 | -0.349 | -19.95% |
| dense/global-wide-parameterized-as-literal | 1 | 1.108 | 1.259 | 0.151 | +13.60% | 1.353 | 1.469 | 0.116 | +8.55% |
| dense/global-wide-parameterized-as-literal | 2 | 1.050 | 1.270 | 0.220 | +20.91% | 2.072 | 1.562 | -0.510 | -24.62% |
| dense/global-wide-parameterized-as-literal | 3 | 1.135 | 1.152 | 0.017 | +1.50% | 1.699 | 1.604 | -0.095 | -5.58% |
| dense/global-wide-provenance | 1 | 1.089 | 1.287 | 0.198 | +18.16% | 1.401 | 1.709 | 0.308 | +22.00% |
| dense/global-wide-provenance | 2 | 1.046 | 1.264 | 0.218 | +20.80% | 1.285 | 1.596 | 0.311 | +24.24% |
| dense/global-wide-provenance | 3 | 1.126 | 1.171 | 0.045 | +4.01% | 1.395 | 1.379 | -0.016 | -1.17% |
| dense/global-wide-wrapped-case-insensitive | 1 | 1.228 | 1.429 | 0.202 | +16.43% | 1.356 | 1.804 | 0.447 | +32.98% |
| dense/global-wide-wrapped-case-insensitive | 2 | 1.221 | 1.422 | 0.201 | +16.50% | 2.077 | 1.598 | -0.479 | -23.04% |
| dense/global-wide-wrapped-case-insensitive | 3 | 1.271 | 1.275 | 0.004 | +0.33% | 1.948 | 1.486 | -0.462 | -23.71% |
| dense/global-wide-wrapped-case-insensitive-distinct | 1 | 3.288 | 3.619 | 0.331 | +10.06% | 4.539 | 4.009 | -0.530 | -11.68% |
| dense/global-wide-wrapped-case-insensitive-distinct | 2 | 3.199 | 3.507 | 0.308 | +9.63% | 4.132 | 4.079 | -0.053 | -1.29% |
| dense/global-wide-wrapped-case-insensitive-distinct | 3 | 3.323 | 3.370 | 0.047 | +1.40% | 4.134 | 4.123 | -0.011 | -0.27% |
| localized-early/global-wide-four-properties | 1 | 0.615 | 0.602 | -0.012 | -2.03% | 1.190 | 0.840 | -0.350 | -29.44% |
| localized-early/global-wide-four-properties | 2 | 0.592 | 0.613 | 0.021 | +3.58% | 1.268 | 0.824 | -0.444 | -35.01% |
| localized-early/global-wide-four-properties | 3 | 0.673 | 0.664 | -0.009 | -1.28% | 1.005 | 1.321 | 0.316 | +31.45% |
| localized-late/global-wide-four-properties | 1 | 0.800 | 0.743 | -0.057 | -7.18% | 1.532 | 1.015 | -0.518 | -33.78% |
| localized-late/global-wide-four-properties | 2 | 0.776 | 0.793 | 0.017 | +2.16% | 1.134 | 0.881 | -0.252 | -22.26% |
| localized-late/global-wide-four-properties | 3 | 0.790 | 0.769 | -0.021 | -2.67% | 1.074 | 0.898 | -0.177 | -16.46% |
| localized-middle/global-wide-four-properties | 1 | 0.758 | 0.724 | -0.034 | -4.42% | 1.197 | 1.047 | -0.150 | -12.56% |
| localized-middle/global-wide-four-properties | 2 | 0.738 | 0.773 | 0.035 | +4.76% | 1.839 | 0.965 | -0.875 | -47.56% |
| localized-middle/global-wide-four-properties | 3 | 0.831 | 0.678 | -0.153 | -18.40% | 1.460 | 0.810 | -0.650 | -44.51% |
| schema-key-histogram | 1 | 0.865 | 0.981 | 0.116 | +13.37% | 2.824 | 1.203 | -1.621 | -57.40% |
| schema-key-histogram | 2 | 0.889 | 1.009 | 0.120 | +13.51% | 2.559 | 1.203 | -1.356 | -52.99% |
| schema-key-histogram | 3 | 0.935 | 0.928 | -0.007 | -0.70% | 1.466 | 1.071 | -0.396 | -26.99% |
| schema-label-histogram | 1 | 0.586 | 0.666 | 0.079 | +13.52% | 0.815 | 1.221 | 0.406 | +49.83% |
| schema-label-histogram | 2 | 0.572 | 0.729 | 0.157 | +27.43% | 1.923 | 0.974 | -0.949 | -49.34% |
| schema-label-histogram | 3 | 0.600 | 0.660 | 0.060 | +10.03% | 0.739 | 0.878 | 0.139 | +18.80% |
| schema-relationship-histogram | 1 | 14.589 | 16.317 | 1.728 | +11.85% | 17.378 | 20.179 | 2.801 | +16.12% |
| schema-relationship-histogram | 2 | 14.804 | 16.233 | 1.429 | +9.65% | 16.100 | 18.153 | 2.054 | +12.76% |
| schema-relationship-histogram | 3 | 15.343 | 14.865 | -0.479 | -3.12% | 21.135 | 16.490 | -4.645 | -21.98% |
| shape-all-nodes | 1 | 0.744 | 0.911 | 0.167 | +22.50% | 1.395 | 1.758 | 0.363 | +26.05% |
| shape-all-nodes | 2 | 0.735 | 0.858 | 0.123 | +16.74% | 2.135 | 1.501 | -0.634 | -29.70% |
| shape-all-nodes | 3 | 0.822 | 0.822 | 0.000 | +0.01% | 1.475 | 1.407 | -0.068 | -4.60% |
| shape-cartesian | 1 | 0.693 | 0.425 | -0.268 | -38.67% | 0.794 | 0.631 | -0.163 | -20.57% |
| shape-cartesian | 2 | 0.729 | 0.420 | -0.309 | -42.34% | 0.868 | 0.861 | -0.008 | -0.88% |
| shape-cartesian | 3 | 0.767 | 0.407 | -0.361 | -47.01% | 0.815 | 0.768 | -0.047 | -5.79% |
| shape-case-expr | 1 | 0.456 | 0.648 | 0.192 | +42.08% | 0.576 | 1.082 | 0.506 | +87.72% |
| shape-case-expr | 2 | 0.472 | 0.721 | 0.250 | +52.88% | 0.574 | 1.081 | 0.507 | +88.43% |
| shape-case-expr | 3 | 0.469 | 0.555 | 0.086 | +18.42% | 0.662 | 0.826 | 0.164 | +24.72% |
| shape-coalesce | 1 | 0.469 | 0.585 | 0.115 | +24.57% | 0.549 | 0.969 | 0.420 | +76.55% |
| shape-coalesce | 2 | 0.469 | 0.745 | 0.277 | +59.02% | 0.521 | 1.065 | 0.543 | +104.20% |
| shape-coalesce | 3 | 0.489 | 0.495 | 0.006 | +1.23% | 0.640 | 0.876 | 0.236 | +36.93% |
| shape-collect | 1 | 1,734.034 | 0.797 | -1,733.238 | -99.95% | 1,785.519 | 0.851 | -1,784.668 | -99.95% |
| shape-collect | 2 | 1,730.666 | 0.850 | -1,729.816 | -99.95% | 1,764.240 | 0.992 | -1,763.248 | -99.94% |
| shape-collect | 3 | 1,740.215 | 0.742 | -1,739.473 | -99.96% | 1,849.050 | 0.821 | -1,848.229 | -99.96% |
| shape-count-all | 1 | 0.260 | 0.381 | 0.121 | +46.62% | 0.615 | 0.817 | 0.203 | +33.00% |
| shape-count-all | 2 | 0.235 | 0.390 | 0.155 | +65.83% | 1.466 | 0.609 | -0.857 | -58.47% |
| shape-count-all | 3 | 0.327 | 0.382 | 0.055 | +16.77% | 1.035 | 0.654 | -0.382 | -36.87% |
| shape-count-label | 1 | 0.228 | 0.315 | 0.088 | +38.63% | 0.792 | 0.454 | -0.338 | -42.67% |
| shape-count-label | 2 | 0.219 | 0.346 | 0.127 | +57.83% | 0.973 | 0.795 | -0.178 | -18.27% |
| shape-count-label | 3 | 0.244 | 0.269 | 0.025 | +10.42% | 0.838 | 0.413 | -0.425 | -50.75% |
| shape-count-traversal | 1 | 15.270 | 16.177 | 0.907 | +5.94% | 16.716 | 18.035 | 1.319 | +7.89% |
| shape-count-traversal | 2 | 14.855 | 16.116 | 1.261 | +8.49% | 16.291 | 18.019 | 1.728 | +10.61% |
| shape-count-traversal | 3 | 15.525 | 14.495 | -1.030 | -6.63% | 20.967 | 15.548 | -5.419 | -25.84% |
| shape-distinct | 1 | 201.396 | 232.640 | 31.244 | +15.51% | 217.028 | 255.208 | 38.180 | +17.59% |
| shape-distinct | 2 | 195.212 | 236.941 | 41.729 | +21.38% | 218.416 | 246.769 | 28.352 | +12.98% |
| shape-distinct | 3 | 211.635 | 236.871 | 25.236 | +11.92% | 244.833 | 254.854 | 10.021 | +4.09% |
| shape-equality | 1 | 0.576 | 0.683 | 0.107 | +18.57% | 0.868 | 1.614 | 0.746 | +86.02% |
| shape-equality | 2 | 0.556 | 0.702 | 0.146 | +26.26% | 2.830 | 1.323 | -1.508 | -53.26% |
| shape-equality | 3 | 0.581 | 0.658 | 0.077 | +13.24% | 0.917 | 0.927 | 0.010 | +1.05% |
| shape-function-calls | 1 | 0.549 | 0.638 | 0.089 | +16.27% | 0.641 | 1.400 | 0.759 | +118.27% |
| shape-function-calls | 2 | 0.545 | 0.816 | 0.271 | +49.76% | 0.633 | 1.941 | 1.309 | +206.92% |
| shape-function-calls | 3 | 0.552 | 0.604 | 0.052 | +9.35% | 0.728 | 1.011 | 0.283 | +38.93% |
| shape-group-count | 1 | 177.276 | 213.890 | 36.613 | +20.65% | 201.553 | 222.751 | 21.197 | +10.52% |
| shape-group-count | 2 | 181.427 | 216.511 | 35.084 | +19.34% | 194.256 | 231.387 | 37.131 | +19.11% |
| shape-group-count | 3 | 183.835 | 218.986 | 35.150 | +19.12% | 210.081 | 223.658 | 13.577 | +6.46% |
| shape-id-lookup | 1 | 0.553 | 0.699 | 0.146 | +26.37% | 0.904 | 1.106 | 0.202 | +22.38% |
| shape-id-lookup | 2 | 0.548 | 0.731 | 0.183 | +33.41% | 0.792 | 0.936 | 0.144 | +18.14% |
| shape-id-lookup | 3 | 0.607 | 0.696 | 0.089 | +14.75% | 1.306 | 1.456 | 0.151 | +11.54% |
| shape-in-list | 1 | 0.320 | 0.408 | 0.088 | +27.48% | 0.753 | 0.613 | -0.140 | -18.60% |
| shape-in-list | 2 | 0.320 | 0.467 | 0.146 | +45.76% | 0.867 | 0.662 | -0.205 | -23.64% |
| shape-in-list | 3 | 0.363 | 0.424 | 0.061 | +16.92% | 0.802 | 0.607 | -0.194 | -24.23% |
| shape-incoming | 1 | 0.456 | 0.571 | 0.115 | +25.29% | 0.601 | 1.128 | 0.527 | +87.68% |
| shape-incoming | 2 | 0.467 | 0.576 | 0.109 | +23.28% | 1.355 | 0.643 | -0.712 | -52.54% |
| shape-incoming | 3 | 0.513 | 0.506 | -0.007 | -1.30% | 0.980 | 0.947 | -0.033 | -3.41% |
| shape-inequality | 1 | 0.411 | 0.504 | 0.093 | +22.64% | 1.980 | 1.175 | -0.805 | -40.67% |
| shape-inequality | 2 | 0.434 | 0.543 | 0.109 | +25.17% | 1.345 | 0.939 | -0.406 | -30.16% |
| shape-inequality | 3 | 0.453 | 0.503 | 0.050 | +11.03% | 1.524 | 1.314 | -0.210 | -13.80% |
| shape-label-scan-callsite | 1 | 0.465 | 0.565 | 0.101 | +21.70% | 1.896 | 1.060 | -0.837 | -44.11% |
| shape-label-scan-callsite | 2 | 0.474 | 0.541 | 0.068 | +14.28% | 2.559 | 0.700 | -1.859 | -72.63% |
| shape-label-scan-callsite | 3 | 0.497 | 0.473 | -0.025 | -4.98% | 1.310 | 1.298 | -0.011 | -0.87% |
| shape-label-scan-constant | 1 | 0.405 | 0.518 | 0.113 | +27.96% | 1.736 | 3.180 | 1.443 | +83.13% |
| shape-label-scan-constant | 2 | 0.399 | 0.519 | 0.120 | +30.13% | 1.676 | 2.472 | 0.796 | +47.52% |
| shape-label-scan-constant | 3 | 0.476 | 0.445 | -0.030 | -6.41% | 1.874 | 0.642 | -1.232 | -65.73% |
| shape-label-scan-field | 1 | 0.408 | 0.515 | 0.107 | +26.11% | 1.939 | 2.466 | 0.527 | +27.16% |
| shape-label-scan-field | 2 | 0.388 | 0.509 | 0.121 | +31.12% | 1.195 | 0.613 | -0.582 | -48.71% |
| shape-label-scan-field | 3 | 0.478 | 0.453 | -0.024 | -5.10% | 1.371 | 1.858 | 0.487 | +35.52% |
| shape-label-scan-method | 1 | 0.401 | 0.515 | 0.114 | +28.54% | 1.400 | 0.786 | -0.614 | -43.86% |
| shape-label-scan-method | 2 | 0.409 | 0.517 | 0.108 | +26.51% | 3.031 | 0.663 | -2.368 | -78.12% |
| shape-label-scan-method | 3 | 0.452 | 0.447 | -0.005 | -1.15% | 1.587 | 0.917 | -0.669 | -42.19% |
| shape-labels-fn | 1 | 0.457 | 0.597 | 0.141 | +30.80% | 0.729 | 1.081 | 0.352 | +48.27% |
| shape-labels-fn | 2 | 0.484 | 0.680 | 0.196 | +40.52% | 0.567 | 1.207 | 0.640 | +112.90% |
| shape-labels-fn | 3 | 0.514 | 0.503 | -0.011 | -2.22% | 0.720 | 0.852 | 0.132 | +18.34% |
| shape-null-check | 1 | 0.392 | 0.483 | 0.091 | +23.13% | 0.759 | 0.869 | 0.110 | +14.50% |
| shape-null-check | 2 | 0.393 | 0.491 | 0.098 | +25.06% | 0.626 | 0.813 | 0.187 | +29.90% |
| shape-null-check | 3 | 0.444 | 0.458 | 0.014 | +3.26% | 0.870 | 0.799 | -0.071 | -8.11% |
| shape-one-hop | 1 | 0.497 | 0.564 | 0.067 | +13.52% | 0.846 | 0.687 | -0.159 | -18.79% |
| shape-one-hop | 2 | 0.464 | 0.573 | 0.109 | +23.56% | 1.039 | 1.049 | 0.011 | +1.02% |
| shape-one-hop | 3 | 0.487 | 0.519 | 0.032 | +6.55% | 1.385 | 0.666 | -0.719 | -51.93% |
| shape-one-hop-typed | 1 | 0.458 | 0.600 | 0.142 | +31.13% | 1.127 | 1.014 | -0.114 | -10.08% |
| shape-one-hop-typed | 2 | 0.454 | 0.593 | 0.138 | +30.47% | 0.986 | 0.687 | -0.299 | -30.35% |
| shape-one-hop-typed | 3 | 0.536 | 0.513 | -0.023 | -4.37% | 0.863 | 0.891 | 0.028 | +3.26% |
| shape-order-by | 1 | 0.840 | 0.999 | 0.158 | +18.85% | 0.892 | 3.406 | 2.514 | +281.72% |
| shape-order-by | 2 | 0.847 | 1.366 | 0.519 | +61.31% | 0.965 | 3.013 | 2.048 | +212.37% |
| shape-order-by | 3 | 0.890 | 0.924 | 0.034 | +3.79% | 1.082 | 2.192 | 1.109 | +102.47% |
| shape-order-by-desc | 1 | 0.600 | 0.647 | 0.048 | +7.97% | 0.658 | 2.300 | 1.642 | +249.36% |
| shape-order-by-desc | 2 | 0.544 | 0.885 | 0.340 | +62.52% | 0.699 | 2.489 | 1.790 | +256.18% |
| shape-order-by-desc | 3 | 0.598 | 0.591 | -0.006 | -1.05% | 0.727 | 1.927 | 1.200 | +164.98% |
| shape-path | 1 | 0.549 | 0.652 | 0.103 | +18.68% | 1.009 | 0.826 | -0.183 | -18.15% |
| shape-path | 2 | 0.552 | 0.667 | 0.115 | +20.90% | 1.023 | 0.800 | -0.223 | -21.80% |
| shape-path | 3 | 0.582 | 0.611 | 0.029 | +4.92% | 0.982 | 1.041 | 0.059 | +5.96% |
| shape-regex | 1 | 0.438 | 0.537 | 0.099 | +22.55% | 0.661 | 1.108 | 0.447 | +67.58% |
| shape-regex | 2 | 0.424 | 0.577 | 0.153 | +36.10% | 1.117 | 0.762 | -0.356 | -31.83% |
| shape-regex | 3 | 0.467 | 0.495 | 0.028 | +6.10% | 1.405 | 0.628 | -0.777 | -55.33% |
| shape-skip | 1 | 0.957 | 1.104 | 0.147 | +15.37% | 1.077 | 2.504 | 1.427 | +132.57% |
| shape-skip | 2 | 0.935 | 1.283 | 0.348 | +37.18% | 1.101 | 3.515 | 2.413 | +219.12% |
| shape-skip | 3 | 0.996 | 1.192 | 0.196 | +19.71% | 1.157 | 2.544 | 1.386 | +119.78% |
| shape-traverse-filtered | 1 | 0.742 | 0.853 | 0.110 | +14.82% | 0.904 | 1.125 | 0.221 | +24.45% |
| shape-traverse-filtered | 2 | 0.702 | 0.942 | 0.239 | +34.05% | 1.314 | 1.464 | 0.149 | +11.37% |
| shape-traverse-filtered | 3 | 0.772 | 0.871 | 0.099 | +12.84% | 1.106 | 1.010 | -0.096 | -8.66% |
| shape-two-hop | 1 | 0.449 | 0.578 | 0.129 | +28.63% | 1.173 | 0.984 | -0.189 | -16.08% |
| shape-two-hop | 2 | 0.453 | 0.598 | 0.145 | +31.89% | 0.638 | 0.954 | 0.315 | +49.39% |
| shape-two-hop | 3 | 0.562 | 0.515 | -0.047 | -8.29% | 1.661 | 0.645 | -1.015 | -61.15% |
| shape-union | 1 | 0.509 | 0.487 | -0.022 | -4.26% | 0.646 | 0.598 | -0.048 | -7.49% |
| shape-union | 2 | 0.549 | 0.506 | -0.044 | -7.95% | 0.652 | 0.591 | -0.061 | -9.40% |
| shape-union | 3 | 0.556 | 0.482 | -0.074 | -13.30% | 0.688 | 0.553 | -0.135 | -19.59% |
| shape-var-length | 1 | 0.414 | 0.516 | 0.102 | +24.74% | 0.843 | 0.609 | -0.234 | -27.80% |
| shape-var-length | 2 | 0.412 | 0.542 | 0.130 | +31.43% | 1.055 | 1.137 | 0.082 | +7.77% |
| shape-var-length | 3 | 0.440 | 0.444 | 0.005 | +1.04% | 0.629 | 0.592 | -0.036 | -5.80% |
| shape-wide-contains | 1 | 0.693 | 0.867 | 0.174 | +25.04% | 0.823 | 1.792 | 0.969 | +117.72% |
| shape-wide-contains | 2 | 0.696 | 0.896 | 0.200 | +28.71% | 1.548 | 1.010 | -0.537 | -34.71% |
| shape-wide-contains | 3 | 0.764 | 0.775 | 0.012 | +1.53% | 2.334 | 1.100 | -1.233 | -52.86% |
| shape-with-aggregate | 1 | 151.784 | 182.758 | 30.973 | +20.41% | 167.491 | 200.907 | 33.417 | +19.95% |
| shape-with-aggregate | 2 | 151.323 | 184.753 | 33.430 | +22.09% | 171.328 | 191.943 | 20.615 | +12.03% |
| shape-with-aggregate | 3 | 153.702 | 186.759 | 33.057 | +21.51% | 176.922 | 195.108 | 18.186 | +10.28% |
| shape-with-filter | 1 | 0.505 | 0.618 | 0.113 | +22.45% | 0.594 | 1.074 | 0.480 | +80.68% |
| shape-with-filter | 2 | 0.473 | 0.776 | 0.303 | +64.08% | 0.571 | 1.190 | 0.620 | +108.60% |
| shape-with-filter | 3 | 0.499 | 0.509 | 0.011 | +2.16% | 0.669 | 0.922 | 0.253 | +37.86% |
| targeted/global-wide-aliased | 1 | 0.575 | 0.563 | -0.012 | -2.04% | 0.756 | 0.672 | -0.084 | -11.13% |
| targeted/global-wide-aliased | 2 | 0.522 | 0.543 | 0.021 | +4.03% | 0.794 | 0.809 | 0.015 | +1.88% |
| targeted/global-wide-aliased | 3 | 0.599 | 0.496 | -0.103 | -17.12% | 0.798 | 0.675 | -0.123 | -15.46% |
| targeted/global-wide-callee-class | 1 | 0.571 | 0.562 | -0.010 | -1.72% | 0.886 | 0.628 | -0.258 | -29.17% |
| targeted/global-wide-callee-class | 2 | 0.533 | 0.552 | 0.019 | +3.63% | 0.738 | 0.750 | 0.012 | +1.63% |
| targeted/global-wide-callee-class | 3 | 0.542 | 0.502 | -0.040 | -7.36% | 0.846 | 0.716 | -0.129 | -15.30% |
| targeted/global-wide-caller-class | 1 | 0.561 | 0.555 | -0.006 | -1.02% | 0.896 | 0.641 | -0.255 | -28.43% |
| targeted/global-wide-caller-class | 2 | 0.540 | 0.593 | 0.053 | +9.83% | 0.758 | 0.815 | 0.057 | +7.54% |
| targeted/global-wide-caller-class | 3 | 0.546 | 0.547 | 0.001 | +0.16% | 0.895 | 0.739 | -0.156 | -17.42% |
| targeted/global-wide-class-pair | 1 | 0.582 | 0.588 | 0.007 | +1.14% | 0.854 | 0.994 | 0.139 | +16.32% |
| targeted/global-wide-class-pair | 2 | 0.574 | 0.573 | -0.001 | -0.17% | 0.720 | 0.891 | 0.171 | +23.77% |
| targeted/global-wide-class-pair | 3 | 0.623 | 0.550 | -0.073 | -11.65% | 0.951 | 0.894 | -0.057 | -5.96% |
| targeted/global-wide-four-properties | 1 | 0.807 | 0.675 | -0.132 | -16.35% | 1.075 | 1.216 | 0.142 | +13.20% |
| targeted/global-wide-four-properties | 2 | 0.784 | 0.657 | -0.127 | -16.17% | 1.184 | 0.876 | -0.308 | -25.98% |
| targeted/global-wide-four-properties | 3 | 0.857 | 0.633 | -0.224 | -26.16% | 1.206 | 0.802 | -0.404 | -33.52% |
| targeted/global-wide-name-pair | 1 | 0.582 | 0.531 | -0.051 | -8.72% | 0.886 | 0.830 | -0.056 | -6.27% |
| targeted/global-wide-name-pair | 2 | 0.556 | 0.559 | 0.002 | +0.40% | 0.739 | 0.952 | 0.213 | +28.75% |
| targeted/global-wide-name-pair | 3 | 0.569 | 0.516 | -0.054 | -9.42% | 1.048 | 0.729 | -0.319 | -30.48% |
| targeted/global-wide-parameterized-as-literal | 1 | 0.540 | 0.522 | -0.018 | -3.29% | 0.913 | 0.642 | -0.272 | -29.75% |
| targeted/global-wide-parameterized-as-literal | 2 | 0.508 | 0.568 | 0.060 | +11.82% | 0.687 | 0.748 | 0.061 | +8.82% |
| targeted/global-wide-parameterized-as-literal | 3 | 0.588 | 0.492 | -0.096 | -16.33% | 0.754 | 0.679 | -0.075 | -10.00% |
| targeted/global-wide-provenance | 1 | 0.555 | 0.513 | -0.042 | -7.54% | 0.865 | 0.749 | -0.116 | -13.42% |
| targeted/global-wide-provenance | 2 | 0.519 | 0.537 | 0.018 | +3.45% | 0.863 | 0.791 | -0.072 | -8.36% |
| targeted/global-wide-provenance | 3 | 0.538 | 0.494 | -0.045 | -8.33% | 0.848 | 0.678 | -0.170 | -20.07% |
| targeted/global-wide-wrapped-case-insensitive | 1 | 0.559 | 0.527 | -0.032 | -5.79% | 0.710 | 0.625 | -0.084 | -11.87% |
| targeted/global-wide-wrapped-case-insensitive | 2 | 0.544 | 0.533 | -0.011 | -2.10% | 1.045 | 0.701 | -0.344 | -32.93% |
| targeted/global-wide-wrapped-case-insensitive | 3 | 0.576 | 0.473 | -0.103 | -17.90% | 0.685 | 0.654 | -0.031 | -4.50% |
| targeted/global-wide-wrapped-case-insensitive-distinct | 1 | 0.545 | 0.625 | 0.080 | +14.65% | 0.657 | 0.764 | 0.108 | +16.41% |
| targeted/global-wide-wrapped-case-insensitive-distinct | 2 | 0.517 | 0.564 | 0.046 | +8.97% | 0.758 | 0.758 | 0.000 | +0.03% |
| targeted/global-wide-wrapped-case-insensitive-distinct | 3 | 0.562 | 0.541 | -0.021 | -3.77% | 0.899 | 0.737 | -0.162 | -18.05% |
| zero/global-wide-aliased | 1 | 0.308 | 0.327 | 0.019 | +6.15% | 0.449 | 0.479 | 0.030 | +6.61% |
| zero/global-wide-aliased | 2 | 0.335 | 0.343 | 0.008 | +2.38% | 0.427 | 0.482 | 0.055 | +12.93% |
| zero/global-wide-aliased | 3 | 0.317 | 0.298 | -0.019 | -6.01% | 0.485 | 0.494 | 0.009 | +1.96% |
| zero/global-wide-callee-class | 1 | 0.324 | 0.321 | -0.002 | -0.71% | 0.677 | 0.407 | -0.270 | -39.95% |
| zero/global-wide-callee-class | 2 | 0.311 | 0.356 | 0.045 | +14.55% | 0.511 | 0.502 | -0.009 | -1.70% |
| zero/global-wide-callee-class | 3 | 0.338 | 0.323 | -0.015 | -4.41% | 0.699 | 0.401 | -0.298 | -42.63% |
| zero/global-wide-caller-class | 1 | 0.336 | 0.330 | -0.006 | -1.91% | 0.468 | 0.408 | -0.059 | -12.67% |
| zero/global-wide-caller-class | 2 | 0.338 | 0.342 | 0.005 | +1.34% | 0.527 | 0.489 | -0.038 | -7.15% |
| zero/global-wide-caller-class | 3 | 0.364 | 0.316 | -0.047 | -13.02% | 0.935 | 0.413 | -0.522 | -55.80% |
| zero/global-wide-class-pair | 1 | 0.331 | 0.342 | 0.010 | +3.13% | 0.491 | 0.428 | -0.063 | -12.88% |
| zero/global-wide-class-pair | 2 | 0.324 | 0.362 | 0.038 | +11.69% | 0.414 | 0.479 | 0.066 | +15.90% |
| zero/global-wide-class-pair | 3 | 0.368 | 0.329 | -0.040 | -10.76% | 0.500 | 0.473 | -0.027 | -5.36% |
| zero/global-wide-four-properties | 1 | 0.410 | 0.426 | 0.016 | +3.83% | 0.593 | 0.538 | -0.055 | -9.23% |
| zero/global-wide-four-properties | 2 | 0.418 | 0.432 | 0.014 | +3.40% | 0.532 | 0.506 | -0.026 | -4.88% |
| zero/global-wide-four-properties | 3 | 0.432 | 0.410 | -0.022 | -4.99% | 0.560 | 0.524 | -0.037 | -6.52% |
| zero/global-wide-name-pair | 1 | 0.332 | 0.343 | 0.011 | +3.35% | 0.654 | 0.425 | -0.229 | -35.06% |
| zero/global-wide-name-pair | 2 | 0.341 | 0.366 | 0.025 | +7.26% | 0.461 | 0.452 | -0.008 | -1.77% |
| zero/global-wide-name-pair | 3 | 0.384 | 0.326 | -0.058 | -15.09% | 1.142 | 0.420 | -0.722 | -63.21% |
| zero/global-wide-parameterized-as-literal | 1 | 0.303 | 0.340 | 0.036 | +12.04% | 0.440 | 0.562 | 0.122 | +27.78% |
| zero/global-wide-parameterized-as-literal | 2 | 0.316 | 0.315 | -0.000 | -0.15% | 0.477 | 0.450 | -0.027 | -5.72% |
| zero/global-wide-parameterized-as-literal | 3 | 0.327 | 0.308 | -0.020 | -5.96% | 0.560 | 0.446 | -0.114 | -20.35% |
| zero/global-wide-provenance | 1 | 0.304 | 0.331 | 0.027 | +8.72% | 0.439 | 0.430 | -0.009 | -2.10% |
| zero/global-wide-provenance | 2 | 0.294 | 0.366 | 0.072 | +24.54% | 0.399 | 0.499 | 0.100 | +25.15% |
| zero/global-wide-provenance | 3 | 0.329 | 0.310 | -0.019 | -5.78% | 0.617 | 0.490 | -0.127 | -20.59% |
| zero/global-wide-wrapped-case-insensitive | 1 | 0.303 | 0.331 | 0.028 | +9.34% | 0.462 | 0.492 | 0.029 | +6.29% |
| zero/global-wide-wrapped-case-insensitive | 2 | 0.306 | 0.351 | 0.046 | +14.92% | 0.451 | 0.453 | 0.002 | +0.42% |
| zero/global-wide-wrapped-case-insensitive | 3 | 0.313 | 0.325 | 0.012 | +3.79% | 0.686 | 0.394 | -0.293 | -42.62% |
| zero/global-wide-wrapped-case-insensitive-distinct | 1 | 0.332 | 0.371 | 0.039 | +11.60% | 0.779 | 0.603 | -0.176 | -22.58% |
| zero/global-wide-wrapped-case-insensitive-distinct | 2 | 0.326 | 0.386 | 0.060 | +18.30% | 0.692 | 0.513 | -0.179 | -25.82% |
| zero/global-wide-wrapped-case-insensitive-distinct | 3 | 0.338 | 0.354 | 0.016 | +4.74% | 0.658 | 0.472 | -0.187 | -28.39% |

</details>

## Preserved grouped fc428 intermediate and DISTINCT attribution

The grouped increment `fc428df79ee01490e8534c5646b86cb4946a1f20` remains an intermediate result, with its original complete native and Apple evidence unchanged. Native paired RSS exceeded 5%, Apple paired RSS and CPU limits failed, and material DISTINCT and shared-wire p95 increases remained. These failures are retained explicitly: cumulative-full-query raw round 0: RSS +7.003% (6.225 → 6.660 GB); +5% limit exceeded; Apple query raw round1 RSS +5.721%; Apple query network-message-call-scan c1 raw round0 CPU +17.432% (0.666 → 0.782s); Apple query network-message-call-scan c4 raw round1 CPU +9.265% (0.694 → 0.758s); Apple query literal-call-provenance c1 raw round1 CPU +17.310% (2.084 → 2.445s); Apple query field-type-inventory c4 raw round0 CPU +29.023% (1.926 → 2.484s); DISTINCT p95 269.182 → 305.793ms (Δ +36.611ms; all three paired rounds worse: True); Apple shared-wire c4 p95 719.603 → 787.459ms (Δ +67.856ms).

<details>
<summary>Grouped fc428 full73 intermediate pooled distributions</summary>

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| broad-all-64/global-wide-four-properties | 1.273 | 1.490 | 0.216 | +17.00% | 2.334 | 2.339 | 0.005 | +0.22% | 1.551/2.476 | 2.261/2.528 |
| dense/global-wide-aliased | 1.267 | 1.437 | 0.170 | +13.44% | 1.851 | 2.331 | 0.479 | +25.90% | 1.662/2.075 | 2.227/2.331 |
| dense/global-wide-callee-class | 1.234 | 1.328 | 0.094 | +7.58% | 1.913 | 2.391 | 0.477 | +24.96% | 1.831/1.913 | 1.900/2.391 |
| dense/global-wide-caller-class | 1.226 | 1.358 | 0.132 | +10.75% | 1.675 | 1.835 | 0.160 | +9.56% | 1.363/1.874 | 1.584/2.651 |
| dense/global-wide-class-pair | 1.301 | 1.369 | 0.069 | +5.28% | 1.706 | 1.572 | -0.134 | -7.87% | 1.560/1.755 | 1.552/1.572 |
| dense/global-wide-four-properties | 1.266 | 1.359 | 0.093 | +7.36% | 1.840 | 1.642 | -0.199 | -10.80% | 1.315/1.943 | 1.510/1.642 |
| dense/global-wide-name-pair | 1.234 | 1.343 | 0.109 | +8.83% | 1.801 | 1.736 | -0.065 | -3.60% | 1.414/1.801 | 1.623/1.993 |
| dense/global-wide-parameterized-as-literal | 1.283 | 1.396 | 0.113 | +8.81% | 1.661 | 2.239 | 0.578 | +34.81% | 1.464/1.661 | 2.078/2.239 |
| dense/global-wide-provenance | 1.249 | 1.382 | 0.133 | +10.65% | 2.087 | 2.212 | 0.125 | +5.98% | 1.715/2.087 | 1.832/2.273 |
| dense/global-wide-wrapped-case-insensitive | 1.448 | 1.577 | 0.129 | +8.92% | 2.332 | 2.642 | 0.310 | +13.30% | 1.520/2.332 | 2.589/2.642 |
| dense/global-wide-wrapped-case-insensitive-distinct | 3.974 | 4.394 | 0.420 | +10.57% | 4.904 | 5.408 | 0.504 | +10.27% | 4.531/5.342 | 4.682/5.458 |
| localized-early/global-wide-four-properties | 0.649 | 0.589 | -0.060 | -9.30% | 1.230 | 1.262 | 0.032 | +2.62% | 0.862/1.323 | 0.832/1.313 |
| localized-late/global-wide-four-properties | 0.889 | 1.170 | 0.281 | +31.62% | 1.228 | 1.810 | 0.581 | +47.33% | 1.126/1.228 | 1.516/2.548 |
| localized-middle/global-wide-four-properties | 0.842 | 0.801 | -0.041 | -4.85% | 1.626 | 1.431 | -0.194 | -11.95% | 1.116/1.626 | 1.380/1.499 |
| schema-key-histogram | 1.091 | 2.948 | 1.857 | +170.22% | 1.707 | 6.674 | 4.968 | +291.11% | 1.465/2.757 | 5.822/7.307 |
| schema-label-histogram | 0.653 | 1.121 | 0.468 | +71.60% | 1.270 | 1.677 | 0.408 | +32.10% | 1.089/1.279 | 1.580/1.745 |
| schema-relationship-histogram | 20.161 | 19.733 | -0.429 | -2.13% | 25.108 | 21.761 | -3.347 | -13.33% | 20.580/25.108 | 20.528/22.034 |
| shape-all-nodes | 1.015 | 1.223 | 0.208 | +20.44% | 1.678 | 2.674 | 0.996 | +59.36% | 1.288/1.678 | 1.826/3.160 |
| shape-cartesian | 0.718 | 0.525 | -0.193 | -26.86% | 0.851 | 0.843 | -0.008 | -0.97% | 0.776/0.983 | 0.592/0.930 |
| shape-case-expr | 0.524 | 0.586 | 0.062 | +11.76% | 0.649 | 0.934 | 0.285 | +43.88% | 0.637/0.700 | 0.683/0.934 |
| shape-coalesce | 0.529 | 0.590 | 0.060 | +11.43% | 0.670 | 0.802 | 0.132 | +19.74% | 0.620/0.704 | 0.661/1.036 |
| shape-collect | 2,169.218 | 0.432 | -2,168.787 | -99.98% | 2,456.641 | 0.766 | -2,455.875 | -99.97% | 2,041.802/2,535.205 | 0.676/0.811 |
| shape-count-all | 0.369 | 0.804 | 0.435 | +117.60% | 0.910 | 1.517 | 0.607 | +66.66% | 0.702/1.307 | 1.208/1.746 |
| shape-count-label | 0.317 | 0.439 | 0.121 | +38.26% | 0.925 | 1.131 | 0.206 | +22.24% | 0.801/1.024 | 0.781/1.499 |
| shape-count-traversal | 19.643 | 20.183 | 0.540 | +2.75% | 23.691 | 22.349 | -1.342 | -5.67% | 22.632/24.815 | 21.219/22.892 |
| shape-distinct | 221.182 | 275.892 | 54.711 | +24.74% | 269.182 | 305.793 | 36.611 | +13.60% | 258.599/269.182 | 291.514/360.957 |
| shape-equality | 0.675 | 1.385 | 0.710 | +105.24% | 2.015 | 2.247 | 0.232 | +11.53% | 0.937/2.700 | 1.669/2.320 |
| shape-function-calls | 0.626 | 0.693 | 0.067 | +10.75% | 0.787 | 1.200 | 0.413 | +52.53% | 0.762/0.852 | 0.813/1.406 |
| shape-group-count | 193.089 | 51.254 | -141.835 | -73.46% | 262.852 | 57.800 | -205.052 | -78.01% | 235.652/287.530 | 53.030/58.521 |
| shape-id-lookup | 0.635 | 1.059 | 0.424 | +66.84% | 1.319 | 1.639 | 0.320 | +24.27% | 0.871/1.609 | 1.330/2.256 |
| shape-in-list | 0.432 | 0.526 | 0.094 | +21.66% | 1.237 | 1.100 | -0.136 | -11.02% | 0.570/1.275 | 1.016/1.201 |
| shape-incoming | 0.629 | 0.976 | 0.348 | +55.31% | 1.507 | 1.623 | 0.115 | +7.64% | 1.305/1.619 | 1.426/1.702 |
| shape-inequality | 0.561 | 1.057 | 0.497 | +88.58% | 1.332 | 2.246 | 0.915 | +68.68% | 0.717/1.332 | 2.192/2.246 |
| shape-label-scan-callsite | 0.569 | 1.451 | 0.882 | +155.01% | 1.932 | 3.978 | 2.046 | +105.93% | 1.207/1.932 | 3.208/5.434 |
| shape-label-scan-constant | 0.534 | 1.138 | 0.604 | +113.08% | 2.215 | 3.210 | 0.995 | +44.95% | 1.229/2.251 | 2.042/4.294 |
| shape-label-scan-field | 0.525 | 0.848 | 0.322 | +61.30% | 1.468 | 2.601 | 1.133 | +77.16% | 1.092/1.656 | 1.842/2.682 |
| shape-label-scan-method | 0.546 | 1.337 | 0.791 | +144.98% | 2.038 | 4.591 | 2.553 | +125.23% | 0.701/2.038 | 3.481/4.842 |
| shape-labels-fn | 0.558 | 0.589 | 0.030 | +5.46% | 0.707 | 0.757 | 0.049 | +6.99% | 0.626/0.776 | 0.655/0.793 |
| shape-null-check | 0.518 | 0.762 | 0.243 | +46.98% | 1.088 | 1.288 | 0.200 | +18.42% | 0.599/1.088 | 1.112/1.333 |
| shape-one-hop | 0.647 | 0.951 | 0.304 | +46.89% | 1.790 | 1.642 | -0.148 | -8.27% | 0.980/1.790 | 1.485/1.642 |
| shape-one-hop-typed | 0.606 | 0.931 | 0.325 | +53.63% | 1.145 | 1.651 | 0.505 | +44.11% | 0.879/1.531 | 1.233/2.035 |
| shape-order-by | 0.919 | 0.984 | 0.065 | +7.06% | 1.281 | 1.919 | 0.638 | +49.78% | 1.052/1.281 | 1.346/1.919 |
| shape-order-by-desc | 0.622 | 0.675 | 0.053 | +8.49% | 0.868 | 1.420 | 0.552 | +63.55% | 0.676/0.882 | 1.007/3.014 |
| shape-path | 0.709 | 1.099 | 0.390 | +55.01% | 1.180 | 2.035 | 0.855 | +72.48% | 0.957/1.313 | 1.740/2.363 |
| shape-regex | 0.560 | 0.919 | 0.359 | +64.12% | 1.232 | 1.539 | 0.307 | +24.93% | 0.910/1.402 | 1.140/1.539 |
| shape-skip | 1.162 | 1.189 | 0.027 | +2.32% | 1.451 | 1.737 | 0.286 | +19.71% | 1.251/1.568 | 1.579/2.660 |
| shape-traverse-filtered | 0.914 | 1.590 | 0.675 | +73.88% | 1.626 | 2.293 | 0.667 | +41.01% | 1.412/1.626 | 2.088/2.293 |
| shape-two-hop | 0.612 | 0.960 | 0.348 | +56.79% | 1.268 | 1.664 | 0.397 | +31.30% | 1.075/1.326 | 1.560/1.664 |
| shape-union | 0.545 | 0.523 | -0.021 | -3.86% | 0.660 | 0.676 | 0.015 | +2.29% | 0.589/0.744 | 0.578/0.702 |
| shape-var-length | 0.562 | 0.995 | 0.433 | +76.99% | 1.230 | 1.872 | 0.642 | +52.19% | 0.912/1.230 | 1.537/2.279 |
| shape-wide-contains | 0.756 | 2.864 | 2.108 | +278.72% | 3.820 | 7.624 | 3.804 | +99.57% | 2.547/5.165 | 6.193/7.624 |
| shape-with-aggregate | 168.062 | 47.051 | -121.011 | -72.00% | 226.579 | 51.550 | -175.029 | -77.25% | 188.847/226.579 | 49.225/51.775 |
| shape-with-filter | 0.547 | 0.603 | 0.057 | +10.35% | 0.664 | 1.023 | 0.359 | +54.13% | 0.580/0.665 | 0.908/1.096 |
| targeted/global-wide-aliased | 0.589 | 0.588 | -0.001 | -0.10% | 0.846 | 0.776 | -0.070 | -8.31% | 0.759/0.954 | 0.678/0.776 |
| targeted/global-wide-callee-class | 0.592 | 0.567 | -0.025 | -4.25% | 1.019 | 0.715 | -0.304 | -29.87% | 0.716/1.276 | 0.677/0.715 |
| targeted/global-wide-caller-class | 0.593 | 0.595 | 0.002 | +0.42% | 0.824 | 0.691 | -0.132 | -16.07% | 0.705/0.858 | 0.684/0.691 |
| targeted/global-wide-class-pair | 0.658 | 0.593 | -0.065 | -9.82% | 1.046 | 0.797 | -0.250 | -23.85% | 0.824/1.091 | 0.752/0.809 |
| targeted/global-wide-four-properties | 0.829 | 0.695 | -0.134 | -16.20% | 1.114 | 0.870 | -0.243 | -21.85% | 0.997/1.273 | 0.790/1.164 |
| targeted/global-wide-name-pair | 0.598 | 0.585 | -0.014 | -2.26% | 0.949 | 0.739 | -0.210 | -22.16% | 0.803/1.065 | 0.648/0.750 |
| targeted/global-wide-parameterized-as-literal | 0.593 | 0.576 | -0.016 | -2.78% | 0.791 | 0.735 | -0.056 | -7.11% | 0.736/0.808 | 0.674/0.735 |
| targeted/global-wide-provenance | 0.581 | 0.582 | 0.001 | +0.19% | 0.878 | 0.746 | -0.132 | -15.01% | 0.683/0.878 | 0.691/0.746 |
| targeted/global-wide-wrapped-case-insensitive | 0.570 | 0.574 | 0.004 | +0.72% | 0.843 | 0.782 | -0.061 | -7.20% | 0.652/0.911 | 0.671/0.782 |
| targeted/global-wide-wrapped-case-insensitive-distinct | 0.544 | 0.633 | 0.088 | +16.23% | 0.769 | 0.870 | 0.101 | +13.18% | 0.570/0.769 | 0.761/0.879 |
| zero/global-wide-aliased | 0.322 | 0.341 | 0.019 | +5.83% | 0.592 | 0.472 | -0.119 | -20.16% | 0.396/0.592 | 0.364/0.472 |
| zero/global-wide-callee-class | 0.331 | 0.348 | 0.017 | +5.15% | 0.607 | 0.497 | -0.110 | -18.05% | 0.475/0.607 | 0.489/0.497 |
| zero/global-wide-caller-class | 0.349 | 0.347 | -0.001 | -0.42% | 0.592 | 0.517 | -0.075 | -12.72% | 0.478/0.592 | 0.386/0.517 |
| zero/global-wide-class-pair | 0.364 | 0.355 | -0.008 | -2.30% | 0.820 | 0.565 | -0.255 | -31.12% | 0.446/0.820 | 0.417/0.565 |
| zero/global-wide-four-properties | 0.427 | 0.416 | -0.011 | -2.51% | 0.713 | 1.235 | 0.523 | +73.33% | 0.497/0.713 | 0.423/1.289 |
| zero/global-wide-name-pair | 0.360 | 0.352 | -0.008 | -2.14% | 0.561 | 0.589 | 0.028 | +4.97% | 0.530/0.561 | 0.388/0.589 |
| zero/global-wide-parameterized-as-literal | 0.329 | 0.340 | 0.011 | +3.33% | 0.594 | 0.454 | -0.141 | -23.67% | 0.353/0.595 | 0.426/0.454 |
| zero/global-wide-provenance | 0.327 | 0.338 | 0.011 | +3.29% | 0.562 | 0.545 | -0.017 | -3.11% | 0.409/0.562 | 0.386/0.545 |
| zero/global-wide-wrapped-case-insensitive | 0.327 | 0.348 | 0.021 | +6.39% | 0.572 | 0.477 | -0.095 | -16.57% | 0.478/0.629 | 0.457/0.477 |
| zero/global-wide-wrapped-case-insensitive-distinct | 0.364 | 0.384 | 0.020 | +5.44% | 0.665 | 0.523 | -0.142 | -21.31% | 0.484/0.747 | 0.510/0.554 |

</details>

<details>
<summary>Grouped fc428 full73 intermediate every paired round</summary>

| Case | Round | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| broad-all-64/global-wide-four-properties | 1 | 1.176 | 1.419 | 0.243 | +20.65% | 1.551 | 2.261 | 0.710 | +45.81% |
| broad-all-64/global-wide-four-properties | 2 | 1.420 | 1.741 | 0.322 | +22.65% | 2.334 | 2.528 | 0.194 | +8.29% |
| broad-all-64/global-wide-four-properties | 3 | 1.262 | 1.357 | 0.094 | +7.46% | 2.476 | 2.339 | -0.137 | -5.54% |
| dense/global-wide-aliased | 1 | 1.185 | 1.485 | 0.301 | +25.39% | 2.075 | 2.331 | 0.256 | +12.35% |
| dense/global-wide-aliased | 2 | 1.301 | 1.448 | 0.147 | +11.31% | 1.751 | 2.248 | 0.497 | +28.38% |
| dense/global-wide-aliased | 3 | 1.296 | 1.371 | 0.075 | +5.81% | 1.662 | 2.227 | 0.565 | +33.99% |
| dense/global-wide-callee-class | 1 | 1.079 | 1.311 | 0.231 | +21.43% | 1.831 | 2.391 | 0.560 | +30.58% |
| dense/global-wide-callee-class | 2 | 1.285 | 1.358 | 0.073 | +5.69% | 1.868 | 1.900 | 0.032 | +1.72% |
| dense/global-wide-callee-class | 3 | 1.241 | 1.317 | 0.077 | +6.18% | 1.913 | 2.263 | 0.350 | +18.30% |
| dense/global-wide-caller-class | 1 | 1.070 | 1.375 | 0.306 | +28.56% | 1.363 | 2.651 | 1.288 | +94.53% |
| dense/global-wide-caller-class | 2 | 1.351 | 1.342 | -0.010 | -0.70% | 1.874 | 1.826 | -0.049 | -2.61% |
| dense/global-wide-caller-class | 3 | 1.226 | 1.316 | 0.089 | +7.28% | 1.611 | 1.584 | -0.027 | -1.66% |
| dense/global-wide-class-pair | 1 | 1.149 | 1.342 | 0.193 | +16.82% | 1.705 | 1.552 | -0.153 | -8.97% |
| dense/global-wide-class-pair | 2 | 1.386 | 1.392 | 0.006 | +0.41% | 1.755 | 1.552 | -0.203 | -11.54% |
| dense/global-wide-class-pair | 3 | 1.342 | 1.357 | 0.015 | +1.10% | 1.560 | 1.572 | 0.011 | +0.72% |
| dense/global-wide-four-properties | 1 | 1.156 | 1.359 | 0.203 | +17.52% | 1.315 | 1.642 | 0.326 | +24.81% |
| dense/global-wide-four-properties | 2 | 1.338 | 1.364 | 0.026 | +1.93% | 1.593 | 1.510 | -0.084 | -5.25% |
| dense/global-wide-four-properties | 3 | 1.334 | 1.329 | -0.006 | -0.42% | 1.943 | 1.583 | -0.361 | -18.57% |
| dense/global-wide-name-pair | 1 | 1.124 | 1.314 | 0.190 | +16.93% | 1.414 | 1.623 | 0.210 | +14.83% |
| dense/global-wide-name-pair | 2 | 1.355 | 1.403 | 0.048 | +3.54% | 1.801 | 1.993 | 0.192 | +10.65% |
| dense/global-wide-name-pair | 3 | 1.226 | 1.302 | 0.077 | +6.26% | 1.732 | 1.674 | -0.057 | -3.32% |
| dense/global-wide-parameterized-as-literal | 1 | 1.185 | 1.346 | 0.161 | +13.57% | 1.464 | 2.078 | 0.614 | +41.90% |
| dense/global-wide-parameterized-as-literal | 2 | 1.349 | 1.435 | 0.086 | +6.36% | 1.661 | 2.239 | 0.578 | +34.81% |
| dense/global-wide-parameterized-as-literal | 3 | 1.306 | 1.330 | 0.024 | +1.85% | 1.502 | 2.208 | 0.706 | +47.02% |
| dense/global-wide-provenance | 1 | 1.137 | 1.360 | 0.223 | +19.59% | 2.087 | 2.273 | 0.186 | +8.91% |
| dense/global-wide-provenance | 2 | 1.312 | 1.416 | 0.104 | +7.95% | 1.925 | 1.832 | -0.093 | -4.82% |
| dense/global-wide-provenance | 3 | 1.249 | 1.387 | 0.138 | +11.06% | 1.715 | 2.187 | 0.472 | +27.51% |
| dense/global-wide-wrapped-case-insensitive | 1 | 1.309 | 1.525 | 0.216 | +16.53% | 1.520 | 2.642 | 1.122 | +73.86% |
| dense/global-wide-wrapped-case-insensitive | 2 | 1.540 | 1.673 | 0.133 | +8.63% | 2.332 | 2.589 | 0.257 | +11.03% |
| dense/global-wide-wrapped-case-insensitive | 3 | 1.477 | 1.545 | 0.068 | +4.62% | 2.042 | 2.599 | 0.557 | +27.26% |
| dense/global-wide-wrapped-case-insensitive-distinct | 1 | 3.544 | 4.265 | 0.721 | +20.35% | 4.531 | 4.682 | 0.151 | +3.33% |
| dense/global-wide-wrapped-case-insensitive-distinct | 2 | 4.101 | 4.468 | 0.368 | +8.97% | 5.342 | 5.458 | 0.116 | +2.17% |
| dense/global-wide-wrapped-case-insensitive-distinct | 3 | 4.019 | 4.412 | 0.394 | +9.79% | 4.581 | 5.310 | 0.730 | +15.93% |
| localized-early/global-wide-four-properties | 1 | 0.589 | 0.550 | -0.040 | -6.72% | 0.862 | 0.832 | -0.030 | -3.50% |
| localized-early/global-wide-four-properties | 2 | 0.683 | 0.639 | -0.045 | -6.54% | 1.020 | 1.313 | 0.292 | +28.67% |
| localized-early/global-wide-four-properties | 3 | 0.647 | 0.571 | -0.076 | -11.77% | 1.323 | 1.262 | -0.061 | -4.58% |
| localized-late/global-wide-four-properties | 1 | 0.840 | 1.128 | 0.288 | +34.28% | 1.126 | 1.716 | 0.590 | +52.37% |
| localized-late/global-wide-four-properties | 2 | 0.930 | 1.292 | 0.362 | +38.89% | 1.228 | 1.516 | 0.287 | +23.38% |
| localized-late/global-wide-four-properties | 3 | 0.870 | 1.093 | 0.223 | +25.67% | 1.208 | 2.548 | 1.340 | +110.96% |
| localized-middle/global-wide-four-properties | 1 | 0.805 | 0.812 | 0.006 | +0.80% | 1.116 | 1.380 | 0.264 | +23.64% |
| localized-middle/global-wide-four-properties | 2 | 0.885 | 0.796 | -0.089 | -10.08% | 1.626 | 1.427 | -0.198 | -12.21% |
| localized-middle/global-wide-four-properties | 3 | 0.846 | 0.777 | -0.069 | -8.13% | 1.319 | 1.499 | 0.180 | +13.61% |
| schema-key-histogram | 1 | 0.967 | 2.754 | 1.786 | +184.64% | 1.465 | 7.307 | 5.842 | +398.63% |
| schema-key-histogram | 2 | 1.122 | 2.948 | 1.827 | +162.86% | 2.757 | 6.647 | 3.891 | +141.14% |
| schema-key-histogram | 3 | 1.096 | 2.774 | 1.677 | +153.05% | 1.707 | 5.822 | 4.116 | +241.17% |
| schema-label-histogram | 1 | 0.595 | 1.119 | 0.524 | +87.97% | 1.204 | 1.677 | 0.473 | +39.30% |
| schema-label-histogram | 2 | 0.726 | 1.143 | 0.417 | +57.46% | 1.089 | 1.745 | 0.656 | +60.21% |
| schema-label-histogram | 3 | 0.653 | 1.079 | 0.426 | +65.31% | 1.279 | 1.580 | 0.301 | +23.51% |
| schema-relationship-histogram | 1 | 17.988 | 19.750 | 1.762 | +9.80% | 20.580 | 22.034 | 1.454 | +7.06% |
| schema-relationship-histogram | 2 | 20.899 | 19.215 | -1.683 | -8.05% | 25.108 | 20.528 | -4.581 | -18.24% |
| schema-relationship-histogram | 3 | 20.183 | 20.117 | -0.066 | -0.33% | 22.861 | 21.761 | -1.100 | -4.81% |
| shape-all-nodes | 1 | 0.866 | 1.215 | 0.349 | +40.29% | 1.423 | 3.160 | 1.737 | +122.10% |
| shape-all-nodes | 2 | 1.041 | 1.279 | 0.238 | +22.89% | 1.288 | 2.617 | 1.329 | +103.20% |
| shape-all-nodes | 3 | 1.046 | 1.215 | 0.168 | +16.10% | 1.678 | 1.826 | 0.148 | +8.81% |
| shape-cartesian | 1 | 0.696 | 0.480 | -0.216 | -31.00% | 0.787 | 0.592 | -0.195 | -24.77% |
| shape-cartesian | 2 | 0.764 | 0.590 | -0.173 | -22.71% | 0.983 | 0.817 | -0.166 | -16.91% |
| shape-cartesian | 3 | 0.710 | 0.519 | -0.191 | -26.92% | 0.776 | 0.930 | 0.153 | +19.75% |
| shape-case-expr | 1 | 0.489 | 0.527 | 0.038 | +7.72% | 0.700 | 0.934 | 0.234 | +33.44% |
| shape-case-expr | 2 | 0.530 | 0.657 | 0.126 | +23.84% | 0.649 | 0.763 | 0.114 | +17.59% |
| shape-case-expr | 3 | 0.525 | 0.579 | 0.053 | +10.11% | 0.637 | 0.683 | 0.046 | +7.25% |
| shape-coalesce | 1 | 0.471 | 0.547 | 0.077 | +16.26% | 0.620 | 0.802 | 0.182 | +29.30% |
| shape-coalesce | 2 | 0.548 | 0.617 | 0.068 | +12.49% | 0.704 | 1.036 | 0.332 | +47.14% |
| shape-coalesce | 3 | 0.551 | 0.597 | 0.046 | +8.36% | 0.652 | 0.661 | 0.008 | +1.25% |
| shape-collect | 1 | 1,960.683 | 0.460 | -1,960.223 | -99.98% | 2,041.802 | 0.757 | -2,041.045 | -99.96% |
| shape-collect | 2 | 2,255.687 | 0.465 | -2,255.222 | -99.98% | 2,535.205 | 0.811 | -2,534.395 | -99.97% |
| shape-collect | 3 | 2,216.039 | 0.400 | -2,215.639 | -99.98% | 2,456.635 | 0.676 | -2,455.959 | -99.97% |
| shape-count-all | 1 | 0.312 | 0.797 | 0.485 | +155.19% | 0.702 | 1.208 | 0.507 | +72.24% |
| shape-count-all | 2 | 0.405 | 0.511 | 0.106 | +26.26% | 1.307 | 1.746 | 0.439 | +33.62% |
| shape-count-all | 3 | 0.369 | 0.854 | 0.484 | +131.10% | 0.880 | 1.507 | 0.627 | +71.19% |
| shape-count-label | 1 | 0.283 | 0.438 | 0.155 | +54.64% | 0.801 | 0.781 | -0.020 | -2.55% |
| shape-count-label | 2 | 0.337 | 0.871 | 0.534 | +158.40% | 1.024 | 1.499 | 0.476 | +46.48% |
| shape-count-label | 3 | 0.328 | 0.407 | 0.079 | +23.99% | 0.925 | 1.022 | 0.096 | +10.40% |
| shape-count-traversal | 1 | 18.073 | 20.516 | 2.444 | +13.52% | 22.996 | 22.892 | -0.104 | -0.45% |
| shape-count-traversal | 2 | 19.984 | 19.344 | -0.640 | -3.20% | 24.815 | 21.219 | -3.596 | -14.49% |
| shape-count-traversal | 3 | 20.419 | 20.319 | -0.100 | -0.49% | 22.632 | 21.702 | -0.931 | -4.11% |
| shape-distinct | 1 | 212.507 | 279.030 | 66.523 | +31.30% | 258.599 | 301.197 | 42.598 | +16.47% |
| shape-distinct | 2 | 227.866 | 270.579 | 42.712 | +18.74% | 269.182 | 291.514 | 22.332 | +8.30% |
| shape-distinct | 3 | 221.182 | 279.619 | 58.437 | +26.42% | 261.768 | 360.957 | 99.189 | +37.89% |
| shape-equality | 1 | 0.608 | 1.402 | 0.795 | +130.83% | 2.015 | 2.320 | 0.305 | +15.14% |
| shape-equality | 2 | 0.754 | 1.385 | 0.631 | +83.69% | 2.700 | 1.896 | -0.803 | -29.76% |
| shape-equality | 3 | 0.685 | 1.162 | 0.477 | +69.69% | 0.937 | 1.669 | 0.731 | +78.03% |
| shape-function-calls | 1 | 0.548 | 0.638 | 0.090 | +16.37% | 0.784 | 0.813 | 0.029 | +3.74% |
| shape-function-calls | 2 | 0.649 | 0.777 | 0.128 | +19.69% | 0.852 | 1.406 | 0.554 | +65.05% |
| shape-function-calls | 3 | 0.631 | 0.675 | 0.044 | +6.96% | 0.762 | 0.822 | 0.060 | +7.83% |
| shape-group-count | 1 | 179.095 | 49.434 | -129.660 | -72.40% | 261.440 | 53.030 | -208.410 | -79.72% |
| shape-group-count | 2 | 194.319 | 50.460 | -143.859 | -74.03% | 287.530 | 54.086 | -233.444 | -81.19% |
| shape-group-count | 3 | 194.808 | 52.918 | -141.890 | -72.84% | 235.652 | 58.521 | -177.131 | -75.17% |
| shape-id-lookup | 1 | 0.572 | 0.917 | 0.345 | +60.21% | 0.871 | 1.537 | 0.666 | +76.45% |
| shape-id-lookup | 2 | 0.685 | 1.191 | 0.506 | +73.88% | 1.609 | 2.256 | 0.647 | +40.22% |
| shape-id-lookup | 3 | 0.641 | 0.908 | 0.267 | +41.61% | 1.319 | 1.330 | 0.011 | +0.82% |
| shape-in-list | 1 | 0.393 | 0.526 | 0.133 | +33.93% | 0.570 | 1.100 | 0.530 | +92.99% |
| shape-in-list | 2 | 0.473 | 0.552 | 0.078 | +16.52% | 0.801 | 1.201 | 0.400 | +49.97% |
| shape-in-list | 3 | 0.433 | 0.448 | 0.015 | +3.55% | 1.275 | 1.016 | -0.259 | -20.30% |
| shape-incoming | 1 | 0.528 | 0.821 | 0.293 | +55.43% | 1.333 | 1.702 | 0.369 | +27.66% |
| shape-incoming | 2 | 0.644 | 1.090 | 0.446 | +69.22% | 1.305 | 1.426 | 0.121 | +9.26% |
| shape-incoming | 3 | 0.651 | 1.001 | 0.350 | +53.69% | 1.619 | 1.623 | 0.004 | +0.22% |
| shape-inequality | 1 | 0.476 | 0.881 | 0.405 | +85.13% | 1.332 | 2.246 | 0.915 | +68.68% |
| shape-inequality | 2 | 0.600 | 1.129 | 0.529 | +88.18% | 0.754 | 2.206 | 1.452 | +192.46% |
| shape-inequality | 3 | 0.561 | 0.944 | 0.384 | +68.44% | 0.717 | 2.192 | 1.475 | +205.58% |
| shape-label-scan-callsite | 1 | 0.525 | 1.553 | 1.028 | +195.66% | 1.932 | 3.797 | 1.866 | +96.59% |
| shape-label-scan-callsite | 2 | 0.602 | 1.334 | 0.732 | +121.60% | 1.661 | 3.208 | 1.548 | +93.20% |
| shape-label-scan-callsite | 3 | 0.554 | 1.436 | 0.882 | +159.40% | 1.207 | 5.434 | 4.227 | +350.20% |
| shape-label-scan-constant | 1 | 0.467 | 1.140 | 0.673 | +144.03% | 2.215 | 3.012 | 0.797 | +36.01% |
| shape-label-scan-constant | 2 | 0.560 | 1.066 | 0.505 | +90.11% | 2.251 | 4.294 | 2.043 | +90.77% |
| shape-label-scan-constant | 3 | 0.521 | 1.124 | 0.603 | +115.64% | 1.229 | 2.042 | 0.813 | +66.15% |
| shape-label-scan-field | 1 | 0.500 | 0.804 | 0.304 | +60.85% | 1.656 | 2.682 | 1.025 | +61.90% |
| shape-label-scan-field | 2 | 0.561 | 0.942 | 0.381 | +68.00% | 1.210 | 2.447 | 1.237 | +102.30% |
| shape-label-scan-field | 3 | 0.549 | 0.709 | 0.159 | +29.01% | 1.092 | 1.842 | 0.750 | +68.67% |
| shape-label-scan-method | 1 | 0.479 | 1.170 | 0.692 | +144.60% | 0.701 | 3.481 | 2.780 | +396.32% |
| shape-label-scan-method | 2 | 0.582 | 1.400 | 0.818 | +140.47% | 1.223 | 4.006 | 2.783 | +227.58% |
| shape-label-scan-method | 3 | 0.547 | 1.411 | 0.864 | +158.16% | 2.038 | 4.842 | 2.804 | +137.57% |
| shape-labels-fn | 1 | 0.500 | 0.552 | 0.052 | +10.44% | 0.626 | 0.793 | 0.167 | +26.69% |
| shape-labels-fn | 2 | 0.582 | 0.608 | 0.026 | +4.40% | 0.776 | 0.757 | -0.019 | -2.50% |
| shape-labels-fn | 3 | 0.581 | 0.578 | -0.003 | -0.59% | 0.645 | 0.655 | 0.010 | +1.56% |
| shape-null-check | 1 | 0.462 | 0.813 | 0.351 | +75.92% | 0.599 | 1.112 | 0.513 | +85.65% |
| shape-null-check | 2 | 0.556 | 0.692 | 0.136 | +24.49% | 0.740 | 1.333 | 0.593 | +80.08% |
| shape-null-check | 3 | 0.541 | 0.673 | 0.133 | +24.53% | 1.088 | 1.128 | 0.040 | +3.67% |
| shape-one-hop | 1 | 0.538 | 0.892 | 0.355 | +66.03% | 0.980 | 1.485 | 0.505 | +51.47% |
| shape-one-hop | 2 | 0.691 | 1.106 | 0.415 | +60.10% | 1.790 | 1.642 | -0.148 | -8.27% |
| shape-one-hop | 3 | 0.648 | 0.902 | 0.253 | +39.09% | 1.406 | 1.624 | 0.217 | +15.46% |
| shape-one-hop-typed | 1 | 0.515 | 0.883 | 0.368 | +71.41% | 1.037 | 2.035 | 0.998 | +96.33% |
| shape-one-hop-typed | 2 | 0.641 | 1.036 | 0.395 | +61.57% | 1.531 | 1.594 | 0.063 | +4.14% |
| shape-one-hop-typed | 3 | 0.606 | 0.910 | 0.304 | +50.10% | 0.879 | 1.233 | 0.354 | +40.27% |
| shape-order-by | 1 | 0.863 | 0.932 | 0.069 | +7.98% | 1.281 | 1.839 | 0.558 | +43.56% |
| shape-order-by | 2 | 0.952 | 1.033 | 0.081 | +8.56% | 1.198 | 1.346 | 0.149 | +12.41% |
| shape-order-by | 3 | 0.921 | 0.970 | 0.049 | +5.34% | 1.052 | 1.919 | 0.868 | +82.51% |
| shape-order-by-desc | 1 | 0.564 | 0.627 | 0.063 | +11.18% | 0.676 | 3.014 | 2.338 | +345.98% |
| shape-order-by-desc | 2 | 0.641 | 0.720 | 0.079 | +12.34% | 0.882 | 1.282 | 0.400 | +45.37% |
| shape-order-by-desc | 3 | 0.640 | 0.662 | 0.022 | +3.40% | 0.775 | 1.007 | 0.232 | +29.95% |
| shape-path | 1 | 0.583 | 0.943 | 0.360 | +61.76% | 1.313 | 1.740 | 0.427 | +32.53% |
| shape-path | 2 | 0.740 | 1.201 | 0.462 | +62.44% | 1.180 | 2.035 | 0.855 | +72.48% |
| shape-path | 3 | 0.713 | 0.986 | 0.273 | +38.27% | 0.957 | 2.363 | 1.406 | +146.87% |
| shape-regex | 1 | 0.483 | 0.796 | 0.313 | +64.81% | 0.910 | 1.470 | 0.560 | +61.55% |
| shape-regex | 2 | 0.648 | 0.898 | 0.250 | +38.64% | 1.402 | 1.539 | 0.137 | +9.77% |
| shape-regex | 3 | 0.560 | 0.929 | 0.369 | +65.89% | 1.039 | 1.140 | 0.101 | +9.73% |
| shape-skip | 1 | 1.003 | 1.122 | 0.120 | +11.94% | 1.251 | 1.579 | 0.328 | +26.24% |
| shape-skip | 2 | 1.170 | 1.286 | 0.116 | +9.89% | 1.568 | 1.737 | 0.169 | +10.78% |
| shape-skip | 3 | 1.172 | 1.186 | 0.014 | +1.19% | 1.426 | 2.660 | 1.234 | +86.54% |
| shape-traverse-filtered | 1 | 0.764 | 1.191 | 0.428 | +56.00% | 1.412 | 2.293 | 0.881 | +62.38% |
| shape-traverse-filtered | 2 | 0.992 | 1.643 | 0.651 | +65.68% | 1.626 | 2.192 | 0.566 | +34.81% |
| shape-traverse-filtered | 3 | 0.909 | 1.227 | 0.318 | +34.96% | 1.524 | 2.088 | 0.564 | +36.99% |
| shape-two-hop | 1 | 0.521 | 0.959 | 0.438 | +83.94% | 1.075 | 1.560 | 0.485 | +45.06% |
| shape-two-hop | 2 | 0.654 | 0.691 | 0.038 | +5.79% | 1.146 | 1.664 | 0.519 | +45.30% |
| shape-two-hop | 3 | 0.627 | 0.995 | 0.367 | +58.60% | 1.326 | 1.600 | 0.274 | +20.67% |
| shape-union | 1 | 0.511 | 0.501 | -0.010 | -2.04% | 0.589 | 0.578 | -0.010 | -1.73% |
| shape-union | 2 | 0.545 | 0.543 | -0.002 | -0.31% | 0.744 | 0.654 | -0.090 | -12.13% |
| shape-union | 3 | 0.554 | 0.520 | -0.034 | -6.10% | 0.623 | 0.702 | 0.078 | +12.60% |
| shape-var-length | 1 | 0.453 | 0.942 | 0.489 | +107.92% | 1.230 | 1.537 | 0.307 | +24.93% |
| shape-var-length | 2 | 0.585 | 0.810 | 0.225 | +38.41% | 1.207 | 1.851 | 0.644 | +53.39% |
| shape-var-length | 3 | 0.562 | 0.996 | 0.433 | +77.14% | 0.912 | 2.279 | 1.368 | +150.00% |
| shape-wide-contains | 1 | 0.719 | 3.470 | 2.752 | +382.91% | 5.165 | 7.624 | 2.458 | +47.59% |
| shape-wide-contains | 2 | 0.846 | 2.535 | 1.689 | +199.78% | 2.547 | 6.400 | 3.852 | +151.23% |
| shape-wide-contains | 3 | 0.756 | 2.546 | 1.790 | +236.67% | 3.338 | 6.193 | 2.855 | +85.55% |
| shape-with-aggregate | 1 | 155.125 | 45.825 | -109.300 | -70.46% | 216.406 | 49.225 | -167.181 | -77.25% |
| shape-with-aggregate | 2 | 171.137 | 46.653 | -124.485 | -72.74% | 188.847 | 49.451 | -139.396 | -73.81% |
| shape-with-aggregate | 3 | 169.114 | 48.743 | -120.371 | -71.18% | 226.579 | 51.775 | -174.804 | -77.15% |
| shape-with-filter | 1 | 0.496 | 0.564 | 0.068 | +13.72% | 0.580 | 1.023 | 0.443 | +76.43% |
| shape-with-filter | 2 | 0.580 | 0.642 | 0.062 | +10.75% | 0.665 | 1.096 | 0.431 | +64.84% |
| shape-with-filter | 3 | 0.559 | 0.599 | 0.041 | +7.26% | 0.664 | 0.908 | 0.244 | +36.78% |
| targeted/global-wide-aliased | 1 | 0.555 | 0.582 | 0.027 | +4.85% | 0.846 | 0.678 | -0.168 | -19.86% |
| targeted/global-wide-aliased | 2 | 0.602 | 0.611 | 0.008 | +1.38% | 0.759 | 0.766 | 0.007 | +0.90% |
| targeted/global-wide-aliased | 3 | 0.593 | 0.561 | -0.031 | -5.29% | 0.954 | 0.776 | -0.179 | -18.71% |
| targeted/global-wide-callee-class | 1 | 0.534 | 0.559 | 0.026 | +4.82% | 0.716 | 0.677 | -0.039 | -5.46% |
| targeted/global-wide-callee-class | 2 | 0.638 | 0.635 | -0.003 | -0.52% | 0.939 | 0.715 | -0.225 | -23.91% |
| targeted/global-wide-callee-class | 3 | 0.654 | 0.538 | -0.116 | -17.72% | 1.276 | 0.686 | -0.590 | -46.25% |
| targeted/global-wide-caller-class | 1 | 0.565 | 0.556 | -0.010 | -1.69% | 0.705 | 0.684 | -0.021 | -2.96% |
| targeted/global-wide-caller-class | 2 | 0.623 | 0.610 | -0.012 | -1.99% | 0.858 | 0.685 | -0.173 | -20.14% |
| targeted/global-wide-caller-class | 3 | 0.590 | 0.557 | -0.032 | -5.49% | 0.779 | 0.691 | -0.087 | -11.22% |
| targeted/global-wide-class-pair | 1 | 0.574 | 0.580 | 0.007 | +1.13% | 0.824 | 0.809 | -0.016 | -1.89% |
| targeted/global-wide-class-pair | 2 | 0.691 | 0.598 | -0.093 | -13.49% | 1.046 | 0.797 | -0.250 | -23.85% |
| targeted/global-wide-class-pair | 3 | 0.654 | 0.580 | -0.073 | -11.22% | 1.091 | 0.752 | -0.339 | -31.09% |
| targeted/global-wide-four-properties | 1 | 0.819 | 0.685 | -0.134 | -16.36% | 0.997 | 1.164 | 0.166 | +16.67% |
| targeted/global-wide-four-properties | 2 | 0.833 | 0.695 | -0.138 | -16.57% | 1.273 | 0.817 | -0.455 | -35.79% |
| targeted/global-wide-four-properties | 3 | 0.856 | 0.691 | -0.165 | -19.32% | 1.114 | 0.790 | -0.323 | -29.02% |
| targeted/global-wide-name-pair | 1 | 0.548 | 0.545 | -0.003 | -0.58% | 0.803 | 0.648 | -0.155 | -19.26% |
| targeted/global-wide-name-pair | 2 | 0.618 | 0.610 | -0.008 | -1.30% | 1.065 | 0.750 | -0.314 | -29.51% |
| targeted/global-wide-name-pair | 3 | 0.605 | 0.556 | -0.049 | -8.09% | 0.806 | 0.727 | -0.080 | -9.87% |
| targeted/global-wide-parameterized-as-literal | 1 | 0.543 | 0.570 | 0.026 | +4.85% | 0.736 | 0.735 | -0.002 | -0.23% |
| targeted/global-wide-parameterized-as-literal | 2 | 0.599 | 0.598 | -0.001 | -0.13% | 0.769 | 0.724 | -0.045 | -5.86% |
| targeted/global-wide-parameterized-as-literal | 3 | 0.593 | 0.558 | -0.035 | -5.85% | 0.808 | 0.674 | -0.133 | -16.51% |
| targeted/global-wide-provenance | 1 | 0.553 | 0.559 | 0.006 | +1.15% | 0.683 | 0.691 | 0.008 | +1.23% |
| targeted/global-wide-provenance | 2 | 0.587 | 0.620 | 0.032 | +5.50% | 0.878 | 0.746 | -0.132 | -15.01% |
| targeted/global-wide-provenance | 3 | 0.605 | 0.557 | -0.048 | -7.87% | 0.823 | 0.745 | -0.078 | -9.49% |
| targeted/global-wide-wrapped-case-insensitive | 1 | 0.532 | 0.574 | 0.043 | +8.01% | 0.652 | 0.721 | 0.069 | +10.65% |
| targeted/global-wide-wrapped-case-insensitive | 2 | 0.629 | 0.592 | -0.037 | -5.95% | 0.810 | 0.671 | -0.139 | -17.19% |
| targeted/global-wide-wrapped-case-insensitive | 3 | 0.582 | 0.528 | -0.054 | -9.24% | 0.911 | 0.782 | -0.129 | -14.17% |
| targeted/global-wide-wrapped-case-insensitive-distinct | 1 | 0.496 | 0.625 | 0.130 | +26.18% | 0.570 | 0.879 | 0.308 | +54.02% |
| targeted/global-wide-wrapped-case-insensitive-distinct | 2 | 0.560 | 0.668 | 0.108 | +19.30% | 0.769 | 0.785 | 0.017 | +2.18% |
| targeted/global-wide-wrapped-case-insensitive-distinct | 3 | 0.561 | 0.594 | 0.033 | +5.94% | 0.742 | 0.761 | 0.019 | +2.55% |
| zero/global-wide-aliased | 1 | 0.311 | 0.323 | 0.012 | +3.97% | 0.396 | 0.364 | -0.032 | -7.97% |
| zero/global-wide-aliased | 2 | 0.350 | 0.371 | 0.021 | +5.96% | 0.525 | 0.472 | -0.053 | -10.04% |
| zero/global-wide-aliased | 3 | 0.320 | 0.324 | 0.004 | +1.24% | 0.592 | 0.453 | -0.139 | -23.51% |
| zero/global-wide-callee-class | 1 | 0.312 | 0.329 | 0.017 | +5.34% | 0.490 | 0.497 | 0.007 | +1.39% |
| zero/global-wide-callee-class | 2 | 0.331 | 0.369 | 0.039 | +11.76% | 0.475 | 0.497 | 0.023 | +4.74% |
| zero/global-wide-callee-class | 3 | 0.337 | 0.328 | -0.009 | -2.67% | 0.607 | 0.489 | -0.117 | -19.34% |
| zero/global-wide-caller-class | 1 | 0.338 | 0.328 | -0.009 | -2.78% | 0.478 | 0.386 | -0.092 | -19.31% |
| zero/global-wide-caller-class | 2 | 0.384 | 0.391 | 0.007 | +1.86% | 0.592 | 0.513 | -0.080 | -13.43% |
| zero/global-wide-caller-class | 3 | 0.353 | 0.332 | -0.022 | -6.09% | 0.561 | 0.517 | -0.045 | -7.94% |
| zero/global-wide-class-pair | 1 | 0.361 | 0.336 | -0.025 | -6.83% | 0.576 | 0.417 | -0.159 | -27.59% |
| zero/global-wide-class-pair | 2 | 0.364 | 0.386 | 0.022 | +6.05% | 0.820 | 0.508 | -0.313 | -38.12% |
| zero/global-wide-class-pair | 3 | 0.367 | 0.353 | -0.015 | -3.95% | 0.446 | 0.565 | 0.119 | +26.67% |
| zero/global-wide-four-properties | 1 | 0.400 | 0.406 | 0.006 | +1.39% | 0.713 | 0.423 | -0.289 | -40.58% |
| zero/global-wide-four-properties | 2 | 0.417 | 0.464 | 0.047 | +11.26% | 0.593 | 1.289 | 0.695 | +117.16% |
| zero/global-wide-four-properties | 3 | 0.437 | 0.407 | -0.030 | -6.79% | 0.497 | 0.646 | 0.150 | +30.13% |
| zero/global-wide-name-pair | 1 | 0.325 | 0.342 | 0.017 | +5.19% | 0.535 | 0.388 | -0.147 | -27.44% |
| zero/global-wide-name-pair | 2 | 0.366 | 0.399 | 0.034 | +9.20% | 0.530 | 0.533 | 0.003 | +0.49% |
| zero/global-wide-name-pair | 3 | 0.372 | 0.334 | -0.038 | -10.33% | 0.561 | 0.589 | 0.028 | +4.97% |
| zero/global-wide-parameterized-as-literal | 1 | 0.312 | 0.326 | 0.014 | +4.38% | 0.353 | 0.454 | 0.101 | +28.58% |
| zero/global-wide-parameterized-as-literal | 2 | 0.343 | 0.368 | 0.025 | +7.34% | 0.579 | 0.445 | -0.135 | -23.23% |
| zero/global-wide-parameterized-as-literal | 3 | 0.330 | 0.338 | 0.009 | +2.65% | 0.595 | 0.426 | -0.169 | -28.37% |
| zero/global-wide-provenance | 1 | 0.317 | 0.322 | 0.005 | +1.59% | 0.409 | 0.386 | -0.023 | -5.70% |
| zero/global-wide-provenance | 2 | 0.343 | 0.361 | 0.019 | +5.44% | 0.562 | 0.545 | -0.017 | -3.11% |
| zero/global-wide-provenance | 3 | 0.327 | 0.338 | 0.012 | +3.61% | 0.479 | 0.495 | 0.017 | +3.47% |
| zero/global-wide-wrapped-case-insensitive | 1 | 0.309 | 0.338 | 0.029 | +9.29% | 0.478 | 0.457 | -0.022 | -4.50% |
| zero/global-wide-wrapped-case-insensitive | 2 | 0.331 | 0.355 | 0.024 | +7.12% | 0.629 | 0.468 | -0.161 | -25.64% |
| zero/global-wide-wrapped-case-insensitive | 3 | 0.328 | 0.347 | 0.019 | +5.81% | 0.511 | 0.477 | -0.034 | -6.62% |
| zero/global-wide-wrapped-case-insensitive-distinct | 1 | 0.342 | 0.373 | 0.031 | +8.93% | 0.484 | 0.516 | 0.032 | +6.69% |
| zero/global-wide-wrapped-case-insensitive-distinct | 2 | 0.368 | 0.400 | 0.032 | +8.70% | 0.629 | 0.510 | -0.119 | -18.87% |
| zero/global-wide-wrapped-case-insensitive-distinct | 3 | 0.375 | 0.391 | 0.016 | +4.29% | 0.747 | 0.554 | -0.192 | -25.74% |

</details>

DISTINCT attribution: `distinct_parent_head` → `candidate_head` on the same head graphs. One all 73 pair only: 3,358 full requests including 2,920 pressure; 20 pressure samples per case/arm. This is a bounded incremental diagnostic, not the three-pair primary confirmation or a saturation test. Its samples are not pooled with any other run.

<details>
<summary>One-pair all 73 DISTINCT diagnostic: all distributions (N20/arm)</summary>

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| broad-all-64/global-wide-four-properties | 1.204 | 1.568 | 0.364 | +30.21% | 2.101 | 2.208 | 0.106 | +5.05% | 2.101/2.101 | 2.208/2.208 |
| dense/global-wide-aliased | 1.211 | 1.684 | 0.473 | +39.01% | 1.913 | 2.452 | 0.539 | +28.17% | 1.913/1.913 | 2.452/2.452 |
| dense/global-wide-callee-class | 1.088 | 1.624 | 0.536 | +49.23% | 2.023 | 2.078 | 0.054 | +2.69% | 2.023/2.023 | 2.078/2.078 |
| dense/global-wide-caller-class | 1.150 | 1.070 | -0.079 | -6.90% | 1.355 | 1.698 | 0.343 | +25.32% | 1.355/1.355 | 1.698/1.698 |
| dense/global-wide-class-pair | 1.157 | 1.183 | 0.027 | +2.31% | 1.324 | 1.693 | 0.370 | +27.92% | 1.324/1.324 | 1.693/1.693 |
| dense/global-wide-four-properties | 1.120 | 1.413 | 0.294 | +26.21% | 1.332 | 1.911 | 0.578 | +43.42% | 1.332/1.332 | 1.911/1.911 |
| dense/global-wide-name-pair | 1.138 | 1.806 | 0.668 | +58.64% | 1.303 | 2.303 | 0.999 | +76.67% | 1.303/1.303 | 2.303/2.303 |
| dense/global-wide-parameterized-as-literal | 1.354 | 1.211 | -0.143 | -10.60% | 1.920 | 1.668 | -0.252 | -13.12% | 1.920/1.920 | 1.668/1.668 |
| dense/global-wide-provenance | 1.177 | 1.180 | 0.003 | +0.25% | 1.576 | 1.659 | 0.083 | +5.29% | 1.576/1.576 | 1.659/1.659 |
| dense/global-wide-wrapped-case-insensitive | 1.326 | 1.594 | 0.269 | +20.26% | 2.681 | 2.288 | -0.393 | -14.67% | 2.681/2.681 | 2.288/2.288 |
| dense/global-wide-wrapped-case-insensitive-distinct | 3.397 | 3.837 | 0.440 | +12.95% | 4.109 | 3.987 | -0.122 | -2.98% | 4.109/4.109 | 3.987/3.987 |
| localized-early/global-wide-four-properties | 0.530 | 0.588 | 0.059 | +11.04% | 1.086 | 1.186 | 0.099 | +9.13% | 1.086/1.086 | 1.186/1.186 |
| localized-late/global-wide-four-properties | 0.909 | 0.915 | 0.006 | +0.63% | 1.901 | 1.514 | -0.387 | -20.35% | 1.901/1.901 | 1.514/1.514 |
| localized-middle/global-wide-four-properties | 0.668 | 1.260 | 0.592 | +88.67% | 1.162 | 1.987 | 0.825 | +71.04% | 1.162/1.162 | 1.987/1.987 |
| schema-key-histogram | 1.133 | 1.742 | 0.609 | +53.71% | 4.070 | 3.531 | -0.538 | -13.23% | 4.070/4.070 | 3.531/3.531 |
| schema-label-histogram | 0.879 | 1.091 | 0.213 | +24.20% | 1.461 | 3.305 | 1.844 | +126.14% | 1.461/1.461 | 3.305/3.305 |
| schema-relationship-histogram | 16.166 | 15.993 | -0.173 | -1.07% | 17.033 | 17.556 | 0.523 | +3.07% | 17.033/17.033 | 17.556/17.556 |
| shape-all-nodes | 0.907 | 1.918 | 1.011 | +111.49% | 1.945 | 3.101 | 1.156 | +59.46% | 1.945/1.945 | 3.101/3.101 |
| shape-cartesian | 0.478 | 0.470 | -0.008 | -1.62% | 0.690 | 0.811 | 0.121 | +17.56% | 0.690/0.690 | 0.811/0.811 |
| shape-case-expr | 0.465 | 1.090 | 0.625 | +134.54% | 0.631 | 3.031 | 2.400 | +380.10% | 0.631/0.631 | 3.031/3.031 |
| shape-coalesce | 0.500 | 0.891 | 0.391 | +78.09% | 0.986 | 1.913 | 0.927 | +93.95% | 0.986/0.986 | 1.913/1.913 |
| shape-collect | 0.389 | 0.420 | 0.031 | +8.05% | 0.620 | 0.849 | 0.228 | +36.83% | 0.620/0.620 | 0.849/0.849 |
| shape-count-all | 0.648 | 0.813 | 0.165 | +25.50% | 0.954 | 2.029 | 1.075 | +112.68% | 0.954/0.954 | 2.029/2.029 |
| shape-count-label | 0.351 | 1.232 | 0.881 | +250.81% | 0.958 | 2.026 | 1.067 | +111.38% | 0.958/0.958 | 2.026/2.026 |
| shape-count-traversal | 16.173 | 15.685 | -0.488 | -3.02% | 17.292 | 16.445 | -0.847 | -4.90% | 17.292/17.292 | 16.445/16.445 |
| shape-distinct | 233.664 | 6.407 | -227.257 | -97.26% | 259.358 | 9.016 | -250.342 | -96.52% | 259.358/259.358 | 9.016/9.016 |
| shape-equality | 1.101 | 2.163 | 1.063 | +96.55% | 1.508 | 3.424 | 1.916 | +127.06% | 1.508/1.508 | 3.424/3.424 |
| shape-function-calls | 0.552 | 1.588 | 1.035 | +187.49% | 0.877 | 2.675 | 1.799 | +205.20% | 0.877/0.877 | 2.675/2.675 |
| shape-group-count | 43.987 | 41.719 | -2.268 | -5.16% | 47.773 | 44.534 | -3.239 | -6.78% | 47.773/47.773 | 44.534/44.534 |
| shape-id-lookup | 0.920 | 1.390 | 0.470 | +51.04% | 1.329 | 1.958 | 0.629 | +47.28% | 1.329/1.329 | 1.958/1.958 |
| shape-in-list | 0.356 | 1.023 | 0.667 | +187.55% | 0.625 | 1.521 | 0.896 | +143.30% | 0.625/0.625 | 1.521/1.521 |
| shape-incoming | 0.572 | 1.388 | 0.816 | +142.74% | 1.000 | 2.018 | 1.018 | +101.79% | 1.000/1.000 | 2.018/2.018 |
| shape-inequality | 0.604 | 1.091 | 0.487 | +80.60% | 1.226 | 1.859 | 0.633 | +51.59% | 1.226/1.226 | 1.859/1.859 |
| shape-label-scan-callsite | 1.458 | 2.246 | 0.788 | +54.02% | 3.527 | 3.904 | 0.377 | +10.69% | 3.527/3.527 | 3.904/3.904 |
| shape-label-scan-constant | 1.056 | 2.166 | 1.110 | +105.06% | 2.517 | 3.706 | 1.188 | +47.22% | 2.517/2.517 | 3.706/3.706 |
| shape-label-scan-field | 0.751 | 1.780 | 1.028 | +136.88% | 4.076 | 3.288 | -0.789 | -19.35% | 4.076/4.076 | 3.288/3.288 |
| shape-label-scan-method | 1.432 | 1.965 | 0.533 | +37.18% | 3.733 | 4.243 | 0.509 | +13.64% | 3.733/3.733 | 4.243/4.243 |
| shape-labels-fn | 0.471 | 0.910 | 0.439 | +93.26% | 0.593 | 1.519 | 0.927 | +156.27% | 0.593/0.593 | 1.519/1.519 |
| shape-null-check | 0.506 | 1.049 | 0.543 | +107.20% | 0.867 | 1.411 | 0.544 | +62.75% | 0.867/0.867 | 1.411/1.411 |
| shape-one-hop | 0.893 | 1.320 | 0.427 | +47.81% | 1.271 | 1.856 | 0.585 | +46.06% | 1.271/1.271 | 1.856/1.856 |
| shape-one-hop-typed | 0.627 | 1.519 | 0.892 | +142.18% | 1.307 | 1.908 | 0.601 | +46.03% | 1.307/1.307 | 1.908/1.908 |
| shape-order-by | 0.821 | 3.060 | 2.239 | +272.51% | 0.867 | 7.914 | 7.047 | +812.42% | 0.867/0.867 | 7.914/7.914 |
| shape-order-by-desc | 0.553 | 1.522 | 0.969 | +175.06% | 1.730 | 4.038 | 2.308 | +133.44% | 1.730/1.730 | 4.038/4.038 |
| shape-path | 0.977 | 1.341 | 0.364 | +37.29% | 2.021 | 1.903 | -0.118 | -5.84% | 2.021/2.021 | 1.903/1.903 |
| shape-regex | 0.679 | 1.272 | 0.593 | +87.40% | 1.050 | 1.829 | 0.779 | +74.21% | 1.050/1.050 | 1.829/1.829 |
| shape-skip | 0.915 | 2.358 | 1.444 | +157.86% | 1.234 | 3.847 | 2.613 | +211.74% | 1.234/1.234 | 3.847/3.847 |
| shape-traverse-filtered | 1.245 | 1.343 | 0.098 | +7.87% | 2.316 | 2.396 | 0.079 | +3.43% | 2.316/2.316 | 2.396/2.396 |
| shape-two-hop | 0.845 | 1.260 | 0.415 | +49.09% | 1.080 | 1.750 | 0.670 | +62.03% | 1.080/1.080 | 1.750/1.750 |
| shape-union | 0.438 | 0.521 | 0.083 | +18.89% | 0.499 | 0.724 | 0.225 | +45.10% | 0.499/0.499 | 0.724/0.724 |
| shape-var-length | 0.847 | 1.228 | 0.382 | +45.08% | 1.879 | 1.812 | -0.067 | -3.55% | 1.879/1.879 | 1.812/1.812 |
| shape-wide-contains | 1.888 | 2.553 | 0.665 | +35.24% | 5.305 | 4.210 | -1.095 | -20.64% | 5.305/5.305 | 4.210/4.210 |
| shape-with-aggregate | 40.357 | 42.989 | 2.632 | +6.52% | 44.794 | 46.327 | 1.534 | +3.42% | 44.794/44.794 | 46.327/46.327 |
| shape-with-filter | 0.499 | 0.904 | 0.405 | +81.10% | 0.897 | 1.128 | 0.230 | +25.67% | 0.897/0.897 | 1.128/1.128 |
| targeted/global-wide-aliased | 0.455 | 0.644 | 0.189 | +41.49% | 0.852 | 0.793 | -0.060 | -6.99% | 0.852/0.852 | 0.793/0.793 |
| targeted/global-wide-callee-class | 0.471 | 0.617 | 0.147 | +31.16% | 0.721 | 0.789 | 0.068 | +9.49% | 0.721/0.721 | 0.789/0.789 |
| targeted/global-wide-caller-class | 0.483 | 0.531 | 0.047 | +9.79% | 0.668 | 0.671 | 0.003 | +0.52% | 0.668/0.668 | 0.671/0.671 |
| targeted/global-wide-class-pair | 0.484 | 0.573 | 0.090 | +18.57% | 1.230 | 0.761 | -0.470 | -38.16% | 1.230/1.230 | 0.761/0.761 |
| targeted/global-wide-four-properties | 0.584 | 0.648 | 0.064 | +10.95% | 1.320 | 0.953 | -0.367 | -27.83% | 1.320/1.320 | 0.953/0.953 |
| targeted/global-wide-name-pair | 0.503 | 0.620 | 0.117 | +23.28% | 0.711 | 0.829 | 0.119 | +16.69% | 0.711/0.711 | 0.829/0.829 |
| targeted/global-wide-parameterized-as-literal | 0.482 | 0.566 | 0.084 | +17.47% | 0.698 | 0.654 | -0.044 | -6.32% | 0.698/0.698 | 0.654/0.654 |
| targeted/global-wide-provenance | 0.460 | 0.536 | 0.075 | +16.32% | 0.706 | 0.691 | -0.016 | -2.21% | 0.706/0.706 | 0.691/0.691 |
| targeted/global-wide-wrapped-case-insensitive | 0.467 | 0.615 | 0.148 | +31.59% | 0.572 | 0.822 | 0.250 | +43.67% | 0.572/0.572 | 0.822/0.822 |
| targeted/global-wide-wrapped-case-insensitive-distinct | 0.513 | 0.559 | 0.046 | +8.93% | 0.608 | 0.727 | 0.118 | +19.44% | 0.608/0.608 | 0.727/0.727 |
| zero/global-wide-aliased | 0.309 | 0.341 | 0.031 | +10.15% | 0.377 | 0.665 | 0.287 | +76.10% | 0.377/0.377 | 0.665/0.665 |
| zero/global-wide-callee-class | 0.314 | 0.297 | -0.017 | -5.43% | 0.473 | 0.565 | 0.092 | +19.45% | 0.473/0.473 | 0.565/0.565 |
| zero/global-wide-caller-class | 0.320 | 0.299 | -0.020 | -6.40% | 0.368 | 0.510 | 0.142 | +38.61% | 0.368/0.368 | 0.510/0.510 |
| zero/global-wide-class-pair | 0.318 | 0.339 | 0.021 | +6.70% | 0.352 | 0.771 | 0.420 | +119.31% | 0.352/0.352 | 0.771/0.771 |
| zero/global-wide-four-properties | 0.369 | 0.489 | 0.121 | +32.69% | 0.520 | 0.723 | 0.203 | +39.02% | 0.520/0.520 | 0.723/0.723 |
| zero/global-wide-name-pair | 0.315 | 0.315 | -0.001 | -0.17% | 0.392 | 0.489 | 0.097 | +24.82% | 0.392/0.392 | 0.489/0.489 |
| zero/global-wide-parameterized-as-literal | 0.291 | 0.293 | 0.002 | +0.85% | 0.437 | 0.614 | 0.177 | +40.37% | 0.437/0.437 | 0.614/0.614 |
| zero/global-wide-provenance | 0.299 | 0.306 | 0.007 | +2.26% | 0.376 | 0.555 | 0.179 | +47.44% | 0.376/0.376 | 0.555/0.555 |
| zero/global-wide-wrapped-case-insensitive | 0.307 | 0.334 | 0.028 | +9.00% | 0.415 | 0.593 | 0.179 | +43.04% | 0.415/0.415 | 0.593/0.593 |
| zero/global-wide-wrapped-case-insensitive-distinct | 0.337 | 0.390 | 0.053 | +15.70% | 0.479 | 0.576 | 0.096 | +20.06% | 0.479/0.479 | 0.576/0.576 |

</details>

Frozen grouped intermediate report: [fc428 full report and machine analysis in the archive](DOWNLOAD.md). Its Apple raw oracle/load/query directories remain preserved; the selected final Apple output paths are distinct and must bind the new candidate identity.

## Independent resource constraints

| Pair/run | Base CPU s | Head CPU s | Δ CPU s | Δ CPU % | Total CPU ≤+5% | Base peak MiB | Head peak MiB | Δ RSS % | Max RSS ≤+5% | All rounds CPU ≤+5% | All rounds RSS ≤+5% |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| focused-data | 140.817 | 135.525 | -5.293 | -3.76% | True | 5,433.438 | 5,513.969 | +1.48% | True | True | False |
| focused-code | 128.573 | 128.138 | -0.434 | -0.34% | True | 5,425.312 | 5,391.562 | -0.62% | True | True | True |
| full-load | 67.412 | 67.338 | -0.074 | -0.11% | True | 5,260.094 | 5,262.250 | +0.04% | True | True | True |
| full-query | 160.943 | 162.807 | 1.864 | +1.16% | True | 6,292.141 | 6,243.359 | -0.78% | True | True | True |
| optimization-effect | 126.518 | 24.214 | -102.304 | -80.86% | True | 5,467.438 | 5,479.328 | +0.22% | True | True | True |
| final-full-load | 66.949 | 61.801 | -5.148 | -7.69% | True | 5,254.000 | 5,305.266 | +0.98% | True | True | True |
| final-full-query | 161.583 | 63.547 | -98.036 | -60.67% | True | 6,219.219 | 6,476.812 | +4.14% | True | True | True |
| grouping-effect | 22.912 | 4.239 | -18.672 | -81.50% | True | 5,650.938 | 4,788.719 | -15.26% | True | True | True |
| cumulative-full-load | 68.749 | 62.912 | -5.838 | -8.49% | True | 5,255.391 | 5,161.656 | -1.78% | True | True | True |
| cumulative-full-query | 190.919 | 49.984 | -140.936 | -73.82% | True | 6,431.516 | 6,550.125 | +1.84% | True | True | False |
| distinct-effect | 14.601 | 9.505 | -5.096 | -34.90% | True | 6,278.797 | 5,032.953 | -19.84% | True | True | True |
| process-full-load | 68.458 | 61.338 | -7.120 | -10.40% | True | 5,250.609 | 4,947.047 | -5.78% | True | True | True |
| process-full-query | 173.828 | 33.034 | -140.794 | -81.00% | True | 6,306.812 | 5,033.406 | -20.19% | True | True | True |
| distinct-full-load | 67.978 | 61.417 | -6.560 | -9.65% | True | 5,249.297 | 4,946.531 | -5.77% | True | True | True |
| distinct-full-query | 161.409 | 28.483 | -132.925 | -82.35% | True | 6,216.422 | 5,049.234 | -18.78% | True | True | True |
| process-validation-aa-v1 | 12.657 | 13.192 | 0.535 | +4.23% | True | 5,043.828 | 5,041.094 | -0.05% | True | True | True |

CPU is summed user+system over the three matched declared boundaries; RSS compares the maximum of three kernel wait4 process high-water marks. CPU and RSS each have an independent 5% bound. Per-round flags below expose overruns even when pooled totals pass. A passing resource flag cannot offset a latency regression, another resource overrun or a failure. No automatic recovery verdict is made.

<details>
<summary>Every paired round: independent CPU/RSS flags and complete boundary wall times</summary>

| Run | Round | Base CPU s | Head CPU s | Δ CPU % | CPU ≤+5% | Base RSS MiB | Head RSS MiB | Δ RSS % | RSS ≤+5% | Base wall ms | Head wall ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| focused-data | 1 | 42.423 | 42.767 | +0.81% | True | 4,012.109 | 5,513.969 | +37.43% | False | 11,179.084 | 11,283.881 |
| focused-data | 2 | 45.846 | 42.326 | -7.68% | True | 5,304.406 | 5,438.031 | +2.52% | True | 12,030.907 | 11,200.897 |
| focused-data | 3 | 52.549 | 50.431 | -4.03% | True | 5,433.438 | 5,411.750 | -0.40% | True | 13,883.493 | 13,255.811 |
| focused-code | 1 | 45.905 | 45.577 | -0.71% | True | 5,425.312 | 5,391.562 | -0.62% | True | 12,045.300 | 11,924.291 |
| focused-code | 2 | 41.036 | 40.719 | -0.77% | True | 5,235.547 | 5,319.766 | +1.61% | True | 10,798.881 | 10,743.388 |
| focused-code | 3 | 41.631 | 41.842 | +0.51% | True | 5,355.203 | 5,248.109 | -2.00% | True | 10,957.408 | 11,040.594 |
| full-load | 1 | 22.353 | 22.324 | -0.13% | True | 5,250.031 | 5,255.516 | +0.10% | True | 21,071.565 | 20,972.787 |
| full-load | 2 | 22.625 | 22.252 | -1.65% | True | 5,260.094 | 5,218.578 | -0.79% | True | 21,340.867 | 20,172.724 |
| full-load | 3 | 22.434 | 22.762 | +1.46% | True | 5,217.641 | 5,262.250 | +0.85% | True | 20,395.895 | 21,433.990 |
| full-query | 1 | 53.511 | 54.648 | +2.12% | True | 6,292.141 | 5,988.703 | -4.82% | True | 12,764.563 | 13,003.021 |
| full-query | 2 | 53.594 | 54.238 | +1.20% | True | 6,132.625 | 6,117.484 | -0.25% | True | 12,756.046 | 12,887.487 |
| full-query | 3 | 53.838 | 53.921 | +0.15% | True | 6,132.562 | 6,243.359 | +1.81% | True | 12,780.362 | 12,823.949 |
| optimization-effect | 1 | 42.256 | 8.065 | -80.91% | True | 5,366.156 | 5,466.688 | +1.87% | True | 11,145.922 | 2,080.918 |
| optimization-effect | 2 | 42.692 | 8.109 | -81.01% | True | 5,457.344 | 5,465.984 | +0.16% | True | 11,303.694 | 2,095.280 |
| optimization-effect | 3 | 41.569 | 8.040 | -80.66% | True | 5,467.438 | 5,479.328 | +0.22% | True | 10,970.201 | 2,071.234 |
| final-full-load | 1 | 22.121 | 20.799 | -5.98% | True | 5,249.562 | 5,305.266 | +1.06% | True | 21,218.876 | 19,801.783 |
| final-full-load | 2 | 22.603 | 20.427 | -9.63% | True | 5,248.172 | 5,303.156 | +1.05% | True | 21,283.951 | 18,592.631 |
| final-full-load | 3 | 22.224 | 20.576 | -7.42% | True | 5,254.000 | 5,301.953 | +0.91% | True | 20,437.936 | 19,472.547 |
| final-full-query | 1 | 53.651 | 21.006 | -60.85% | True | 6,219.219 | 6,350.141 | +2.11% | True | 12,827.683 | 3,931.703 |
| final-full-query | 2 | 53.478 | 21.105 | -60.53% | True | 6,183.000 | 6,476.812 | +4.75% | True | 12,703.410 | 3,938.548 |
| final-full-query | 3 | 54.454 | 21.436 | -60.63% | True | 6,198.469 | 6,464.344 | +4.29% | True | 13,127.385 | 3,892.444 |
| grouping-effect | 1 | 7.483 | 1.431 | -80.88% | True | 5,472.438 | 4,763.188 | -12.96% | True | 1,935.845 | 406.896 |
| grouping-effect | 2 | 7.663 | 1.417 | -81.51% | True | 5,650.938 | 4,788.719 | -15.26% | True | 2,037.450 | 403.714 |
| grouping-effect | 3 | 7.767 | 1.392 | -82.08% | True | 5,451.281 | 4,768.844 | -12.52% | True | 2,069.389 | 395.681 |
| cumulative-full-load | 1 | 22.946 | 20.841 | -9.17% | True | 5,245.219 | 5,161.656 | -1.59% | True | 21,834.007 | 20,254.848 |
| cumulative-full-load | 2 | 23.142 | 20.634 | -10.84% | True | 5,255.391 | 5,156.297 | -1.89% | True | 22,038.194 | 19,267.056 |
| cumulative-full-load | 3 | 22.662 | 21.436 | -5.41% | True | 5,251.062 | 5,148.719 | -1.95% | True | 20,551.822 | 21,088.765 |
| cumulative-full-query | 1 | 58.371 | 16.518 | -71.70% | True | 5,936.188 | 6,351.875 | +7.00% | False | 14,300.269 | 2,842.387 |
| cumulative-full-query | 2 | 66.556 | 16.417 | -75.33% | True | 6,431.516 | 6,550.125 | +1.84% | True | 16,381.628 | 2,805.073 |
| cumulative-full-query | 3 | 65.993 | 17.048 | -74.17% | True | 6,285.266 | 6,324.172 | +0.62% | True | 15,844.707 | 2,920.153 |
| distinct-effect | 1 | 14.601 | 9.505 | -34.90% | True | 6,278.797 | 5,032.953 | -19.84% | True | 2,412.919 | 1,397.732 |
| process-full-load | 1 | 23.221 | 20.555 | -11.48% | True | 5,249.922 | 4,942.188 | -5.86% | True | 21,672.481 | 20,031.160 |
| process-full-load | 2 | 22.816 | 20.241 | -11.28% | True | 5,250.609 | 4,946.250 | -5.80% | True | 21,432.723 | 18,938.525 |
| process-full-load | 3 | 22.420 | 20.542 | -8.38% | True | 5,249.219 | 4,947.047 | -5.76% | True | 20,094.010 | 19,927.897 |
| process-full-query | 1 | 59.845 | 11.139 | -81.39% | True | 6,039.156 | 5,032.109 | -16.68% | True | 14,440.492 | 1,621.974 |
| process-full-query | 2 | 57.488 | 11.158 | -80.59% | True | 6,306.812 | 5,024.125 | -20.34% | True | 13,804.476 | 1,663.108 |
| process-full-query | 3 | 56.495 | 10.736 | -81.00% | True | 6,186.125 | 5,033.406 | -18.63% | True | 13,578.676 | 1,491.110 |
| distinct-full-load | 1 | 22.769 | 20.559 | -9.71% | True | 5,245.953 | 4,946.531 | -5.71% | True | 21,380.317 | 19,925.106 |
| distinct-full-load | 2 | 22.771 | 20.321 | -10.76% | True | 5,246.516 | 4,944.609 | -5.75% | True | 21,465.236 | 19,069.281 |
| distinct-full-load | 3 | 22.437 | 20.537 | -8.47% | True | 5,249.297 | 4,937.734 | -5.94% | True | 20,185.989 | 19,991.797 |
| distinct-full-query | 1 | 54.260 | 9.511 | -82.47% | True | 6,216.422 | 5,036.328 | -18.98% | True | 12,969.711 | 1,375.874 |
| distinct-full-query | 2 | 52.738 | 9.412 | -82.15% | True | 6,123.656 | 5,033.266 | -17.81% | True | 12,628.397 | 1,406.751 |
| distinct-full-query | 3 | 54.411 | 9.561 | -82.43% | True | 6,211.828 | 5,049.234 | -18.72% | True | 13,030.800 | 1,387.328 |
| process-validation-aa-v1 | 1 | 12.657 | 13.192 | +4.23% | True | 5,043.828 | 5,041.094 | -0.05% | True | 1,977.340 | 2,003.438 |

</details>

Load CPU/wall runs from fresh process startup through registry and all 73 first-use responses; 438 requests across six separate load-only processes. Query CPU belongs to the complete interleaved pressure stage, not individual cases. Query process RSS includes startup, first-use, warmup, pressure and shutdown. Periodic RSS samples are only lower bounds; they do not substitute for kernel peak RSS. Mach CPU ticks were independently recomputed using each sample’s timebase; lifetime wait4 CPU is recorded separately.

Load first-use versus query first-use are separate process runs. No construction timings were collected in this repair; pinned producer dependency closure permits reuse of existing saved graphs, not a construction performance claim.

## Reproducibility, failures and limits

Readiness polling retries retained separately: focused-data=1122, focused-code=1088, full-load=1092, full-query=1081, optimization-effect=1099, final-full-load=1087, final-full-query=1094, grouping-effect=1047, cumulative-full-load=1086, cumulative-full-query=1120, distinct-effect=374, process-full-load=1098, process-full-query=1097, distinct-full-load=1099, distinct-full-query=1092, process-validation-aa-v1=376. All declared request slots, stages, full responses, unchanged-input snapshots and shutdowns passed; startup connection polls are not hidden or counted as measured query failures.

The initial rebased compile failure, old cached-artifact report and all historical nonaccepted outcomes remain retained. No private background application names or unproven thermal/cache causes are reported. OS cache is uncontrolled and workstation interference is not assumed absent. No Linux cgroup OOM counters are available on macOS. These native runs launch no JVM and change no heap ceiling; reused producer evidence used 4 GiB JVM heaps under the 8 GiB limit.

Native identities: base `100e3333ec51b169bfd6ae55c1fef92fe49f0e72fbe006e99fe536e7c4967ac3`, head `8f0052e2979e0b08885eed3c1d497e8bf136aa3d48159ec1a6ba486857ca7fa7`, parent `2017c1da01fd85332b8d369f39213039bd57ae98846057e0d6858b8fa76acb3b`, collect `69f9fd90a1ed15dbddabc3017755792624c121ad2f64c991be9afe87e01349de`, preOptimization `4257f35fc3462beefffdf454d511f808d3410aef60bb5a87500c676ac09ff2dc`.

Pinned sources/producers, 64 graph mappings and all exact queries are in config.json, the selected cumulative config, report-inputs.json, oracle-index.json, PROTOCOL.md and producer-closure-proof.json. Runner and resource helper hashes are retained; tiny JSON/HTTP/CPU-unit tests are correctness tests only. All selected native output folders contain every request response and journal; controller comparisons are separate diagnostics, not code increments. Snapshot protocols and frozen-preopt-identities.json preserve the historical binary identity even if its original build path later contains a different executable; current path bytes are never substituted for recorded historical identity. Replay requires the declared real graph inputs; large graph/binary payloads are not embedded in the report archive.

```bash
python3 -m unittest -v test_multi64
python3 multi64.py run config.json --pair main-data-effect --cases focused --operation query --out NEW_FOCUSED_DATA
python3 multi64.py run config.json --pair code-on-head-data --cases focused --operation query --out NEW_FOCUSED_CODE
python3 multi64.py run config.final.json --pair optimization-effect --cases focused --operation query --out NEW_OPTIMIZATION_EFFECT
python3 multi64.py run config.final.json --pair product-pair --cases all --operation load --out NEW_INTERMEDIATE_LOAD
python3 multi64.py run config.final.json --pair product-pair --cases all --operation query --out NEW_INTERMEDIATE_QUERY
python3 multi64.py run config.cumulative.json --pair grouping-effect --cases focused --operation query --out NEW_GROUPING_EFFECT
python3 multi64.py run config.distinct-increment.json --pair distinct-effect --cases all --operation query --out NEW_DISTINCT_DIAGNOSTIC
python3 multi64_process_validation_v1.py run config.process-final.json --pair product-pair --cases all --operation load --out NEW_CUMULATIVE_LOAD
python3 multi64_process_validation_v1.py run config.process-final.json --pair product-pair --cases all --operation query --out NEW_CUMULATIVE_QUERY
# Preserve the named evidence directories before rendering the completed run.
python3 render-report.py
```

[Download/integrity](DOWNLOAD.md) · [Machine-readable analysis](analysis.json) · [Sanitized environment](environment.json) · [Cumulative build identities](build-identities-distinct.json) · [Retained collect identities](build-identities-final.json) · [Frozen pre-optimization identities](frozen-preopt-identities.json) · [Focused data](focused-data-summary.json) · [Focused code](focused-code-summary.json) · [Cumulative load](process-full-load-summary.json) · [Cumulative query](process-full-query-summary.json) · [Grouping increment](grouping-effect-summary.json) · [Grouped intermediate load](cumulative-full-load-summary.json) · [Grouped intermediate query](cumulative-full-query-summary.json) · [One-pair DISTINCT diagnostic](distinct-effect-summary.json) · [Retained collect load](final-full-load-summary.json) · [Retained collect query](final-full-query-summary.json) · [Collect increment](optimization-effect-summary.json) · [Preserved pre-optimization load](full-load-summary.json) · [Preserved pre-optimization query](full-query-summary.json). Complete raw evidence: DOWNLOAD.md.

## Observer protocol and retained same-source measurements

Final primary arms use the same four HTTP threads and four prewarmed spawn validation processes. Each worker consumes its full body, awaits unchanged strict typed/provenance validation and raw writes, journals it, then takes its next FIFO slot. Native CPU/RSS boundaries are unchanged. The controller diagnostic found observer saturation; the same-binary/same-data A/A reduced parent CPU but increased total observer CPU and changed latency in both directions. It does not explain every remaining regression. Old threaded and new process-validator distributions are never pooled or used as a direct code speedup ratio. See [method and predeclared boundaries](PROTOCOL.process-validation.md) and [complete diagnostics](DOWNLOAD.md).

<details>
<summary>Retained877 threaded-validator full73 results: original five plus DISTINCT</summary>

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shape-order-by-desc | 0.569 | 2.262 | 1.693 | +297.80% | 0.666 | 4.264 | 3.598 | +539.85% | 0.624/0.666 | 2.888/4.906 |
| shape-order-by | 0.852 | 3.152 | 2.300 | +269.88% | 1.056 | 8.191 | 7.135 | +675.75% | 0.905/1.100 | 4.256/9.283 |
| shape-collect | 1,766.528 | 0.363 | -1,766.165 | -99.98% | 1,823.386 | 0.652 | -1,822.733 | -99.96% | 1,747.880/1,838.143 | 0.625/0.652 |
| shape-with-aggregate | 150.000 | 43.584 | -106.416 | -70.94% | 165.837 | 47.010 | -118.828 | -71.65% | 158.921/172.685 | 44.183/48.085 |
| shape-group-count | 175.071 | 41.339 | -133.733 | -76.39% | 188.837 | 45.625 | -143.213 | -75.84% | 179.577/188.844 | 42.893/48.893 |
| shape-distinct | 199.767 | 5.952 | -193.814 | -97.02% | 228.144 | 9.170 | -218.974 | -95.98% | 208.666/228.144 | 7.494/9.827 |

</details>

<details>
<summary>One-pair same877/headgraphs controller A/A diagnostic; no performance acceptance</summary>

| Case | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shape-order-by-desc | 2.671 | 1.572 | -1.099 | -41.15% | 6.966 | 2.746 | -4.221 | -60.59% | 6.966/6.966 | 2.746/2.746 |
| shape-order-by | 3.876 | 1.971 | -1.904 | -49.13% | 8.297 | 6.635 | -1.662 | -20.03% | 8.297/8.297 | 6.635/6.635 |
| shape-collect | 0.503 | 0.572 | 0.069 | +13.81% | 1.279 | 0.720 | -0.558 | -43.67% | 1.279/1.279 | 0.720/0.720 |
| shape-with-aggregate | 57.940 | 62.209 | 4.269 | +7.37% | 63.382 | 75.451 | 12.068 | +19.04% | 63.382/63.382 | 75.451/75.451 |
| shape-group-count | 56.450 | 60.259 | 3.809 | +6.75% | 62.309 | 70.241 | 7.932 | +12.73% | 62.309/62.309 | 70.241/70.241 |
| shape-distinct | 7.930 | 8.767 | 0.837 | +10.56% | 10.337 | 12.013 | 1.676 | +16.21% | 10.337/10.337 | 12.013/12.013 |

</details>

Source validation:310 complete Cypher tests passed with the required native and declared-typev1–v4/inherited fixtures; strict workspace lint/format checks passed. Current Apple/Rust/JVM CI checks passed; benchmark CI status is recorded separately at publication. Quantile uncertainty:60 repeated samples per native case/arm over three paired rounds,300 per Apple case/concurrency/arm; round ranges are shown. These limited fixed batches do not establish a confidence interval or saturation capacity.

## Fresh Apple/JVM three-graph closure

The cached-candidate artifact bug affected the old Apple supplement as well as real64. This fresh rerun uses the same three sealed real graphs and the exact final native identities in this report, replacing the invalid old code A/B. Every request explicitly spans Signal iOS, SwiftPM and Signal Server; the wire-literal join requires different graph IDs. Independent full-response oracle, row provenance, exact digest/count parity, all 4,800 measured + 120 warmup requests and three load rounds per arm passed. Four cases ×concurrency 1/4,100 measured requests per round, three AB/BA/AB pairs yield 300 samples per table arm. Five warmups precede measurements; this remains a different workload/protocol from real64.

| Case | C | Base p50 ms | Head p50 ms | Δ p50 ms | Δ p50 % | Base p95 ms | Head p95 ms | Δ p95 ms | Δ p95 % | Base round p95 min/max | Head round p95 min/max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| network-message-call-scan | 1 | 6.575 | 6.715 | 0.140 | +2.12% | 7.303 | 7.565 | 0.262 | +3.58% | 6.852–7.991 | 7.025–8.034 |
| network-message-call-scan | 4 | 6.951 | 7.262 | 0.311 | +4.47% | 8.451 | 8.000 | -0.451 | -5.33% | 8.169–8.498 | 7.931–8.000 |
| literal-call-provenance | 1 | 21.068 | 21.338 | 0.270 | +1.28% | 23.976 | 23.451 | -0.525 | -2.19% | 22.716–24.861 | 22.809–23.935 |
| literal-call-provenance | 4 | 21.634 | 21.978 | 0.344 | +1.59% | 22.963 | 23.444 | 0.480 | +2.09% | 22.884–23.025 | 23.288–23.468 |
| field-type-inventory | 1 | 18.148 | 18.541 | 0.393 | +2.17% | 19.620 | 19.872 | 0.251 | +1.28% | 19.383–19.711 | 18.913–20.279 |
| field-type-inventory | 4 | 19.000 | 19.520 | 0.521 | +2.74% | 20.037 | 20.517 | 0.479 | +2.39% | 19.733–20.110 | 20.271–20.564 |
| shared-wire-literals-prefiltered | 1 | 599.270 | 605.176 | 5.906 | +0.99% | 617.221 | 630.659 | 13.438 | +2.18% | 612.096–617.970 | 623.923–640.282 |
| shared-wire-literals-prefiltered | 4 | 619.952 | 624.470 | 4.518 | +0.73% | 657.624 | 661.467 | 3.843 | +0.58% | 646.935–662.097 | 653.709–662.006 |

Apple per-case CPU is available because its timed batches contain one case at a time (unlike the interleaved real64 workload):

| Case | C | Base CPU ms/request | Head CPU ms/request | Δ CPU ms/request | Δ CPU % | Total CPU ≤+5% |
| --- | --- | --- | --- | --- | --- | --- |
| network-message-call-scan | 1 | 6.469 | 6.659 | 0.190 | +2.94% | True |
| network-message-call-scan | 4 | 6.979 | 7.099 | 0.119 | +1.71% | True |
| literal-call-provenance | 1 | 20.784 | 21.015 | 0.231 | +1.11% | True |
| literal-call-provenance | 4 | 20.929 | 21.300 | 0.372 | +1.78% | True |
| field-type-inventory | 1 | 17.776 | 18.167 | 0.391 | +2.20% | True |
| field-type-inventory | 4 | 18.434 | 18.846 | 0.411 | +2.23% | True |
| shared-wire-literals-prefiltered | 1 | 599.156 | 606.942 | 7.787 | +1.30% | True |
| shared-wire-literals-prefiltered | 4 | 622.106 | 626.684 | 4.577 | +0.74% | True |

| Arm | Load rounds | Complete load wall range ms | Median ms | Boundary CPU range s |
| --- | --- | --- | --- | --- |
| baseline | 3 | 844.104–848.091 | 847.226 | 0.815–0.833 |
| candidate | 3 | 851.844–880.402 | 876.734 | 0.831–0.868 |

Apple load summed CPU: 2.473 → 2.551 s (+3.16%); ≤+5% flag: True.

| Operation | Arm | Processes | Lifetime peak RSS range MiB | Lifetime CPU range s / bound |
| --- | --- | --- | --- | --- |
| load | baseline | 3 | 139.938–139.984 | 0.816–0.833 |
| load | candidate | 3 | 139.984–140.062 | 0.831–0.868 |
| load | head/base max peak | — | +0.06% | RSS ≤+5%: True |
| query | baseline | 3 | 246.438–257.953 | 133.602–135.478 |
| query | candidate | 3 | 230.094–258.328 | 135.784–136.499 |
| query | head/base max peak | — | +0.15% | RSS ≤+5%: True |

Apple kernel peak RSS includes startup, first-use/warmup, requests and shutdown; it is not query-only. Load consumes readiness plus all four cases. Full success bodies are saved by the independent oracle; the latency runner retains every sample, canonical digest/count/bytes and error bodies. Signal is the corrected 31788b41 graph from fc88002a enum-preserving importer, with five declared dictionary-valued plist omissions. SwiftPM and Signal Server are legacy pinned real-source graphs; the SwiftPM historical importer SHA was not recorded. Same persisted inputs isolate the fresh native consumers, not current Apple construction performance. Exact producer/config provenance and raw Apple evidence remain in the archive.

<details>
<summary>Apple every paired round: kernel lifetime RSS and independent5% flags (raw rounds0–2)</summary>

| Operation | Raw round | Base RSS MiB | Head RSS MiB | Δ RSS MiB | Δ RSS % | RSS ≤+5% |
| --- | --- | --- | --- | --- | --- | --- |
| load | 0 | 139.938 | 140.031 | 0.094 | +0.07% | True |
| load | 1 | 139.984 | 139.984 | 0.000 | +0.00% | True |
| load | 2 | 139.953 | 140.062 | 0.109 | +0.08% | True |
| query | 0 | 246.438 | 249.312 | 2.875 | +1.17% | True |
| query | 1 | 257.953 | 230.094 | -27.859 | -10.80% | True |
| query | 2 | 254.422 | 258.328 | 3.906 | +1.54% | True |

</details>

<details>
<summary>Apple every case/concurrency/round: CPU for100 completed requests; pooled savings do not offset individual overruns</summary>

| Case | C | Raw round | Base CPU s | Head CPU s | Δ CPU s | Δ CPU % | CPU ≤+5% |
| --- | --- | --- | --- | --- | --- | --- | --- |
| network-message-call-scan | 1 | 0 | 0.659 | 0.653 | -0.006 | -0.88% | True |
| network-message-call-scan | 1 | 1 | 0.647 | 0.696 | 0.049 | +7.65% | False |
| network-message-call-scan | 1 | 2 | 0.635 | 0.649 | 0.013 | +2.12% | True |
| network-message-call-scan | 4 | 0 | 0.697 | 0.724 | 0.027 | +3.91% | True |
| network-message-call-scan | 4 | 1 | 0.690 | 0.707 | 0.017 | +2.43% | True |
| network-message-call-scan | 4 | 2 | 0.707 | 0.699 | -0.008 | -1.16% | True |
| literal-call-provenance | 1 | 0 | 2.087 | 2.095 | 0.008 | +0.38% | True |
| literal-call-provenance | 1 | 1 | 2.079 | 2.117 | 0.038 | +1.80% | True |
| literal-call-provenance | 1 | 2 | 2.069 | 2.093 | 0.024 | +1.15% | True |
| literal-call-provenance | 4 | 0 | 2.085 | 2.145 | 0.060 | +2.86% | True |
| literal-call-provenance | 4 | 1 | 2.099 | 2.124 | 0.024 | +1.16% | True |
| literal-call-provenance | 4 | 2 | 2.094 | 2.122 | 0.027 | +1.31% | True |
| field-type-inventory | 1 | 0 | 1.777 | 1.803 | 0.026 | +1.47% | True |
| field-type-inventory | 1 | 1 | 1.782 | 1.854 | 0.073 | +4.09% | True |
| field-type-inventory | 1 | 2 | 1.774 | 1.793 | 0.018 | +1.03% | True |
| field-type-inventory | 4 | 0 | 1.832 | 1.885 | 0.053 | +2.88% | True |
| field-type-inventory | 4 | 1 | 1.856 | 1.877 | 0.021 | +1.13% | True |
| field-type-inventory | 4 | 2 | 1.842 | 1.891 | 0.050 | +2.70% | True |
| shared-wire-literals-prefiltered | 1 | 0 | 59.999 | 60.759 | 0.760 | +1.27% | True |
| shared-wire-literals-prefiltered | 1 | 1 | 59.527 | 60.477 | 0.950 | +1.60% | True |
| shared-wire-literals-prefiltered | 1 | 2 | 60.221 | 60.847 | 0.626 | +1.04% | True |
| shared-wire-literals-prefiltered | 4 | 0 | 62.338 | 62.519 | 0.181 | +0.29% | True |
| shared-wire-literals-prefiltered | 4 | 1 | 61.547 | 62.512 | 0.965 | +1.57% | True |
| shared-wire-literals-prefiltered | 4 | 2 | 62.747 | 62.975 | 0.228 | +0.36% | True |

</details>

Current CI/PR tips are source-equivalent aliases only: `97488be2987cbcc172714e9cd8cab5d8bf74ce60` built → `c86fe745fc93b3847cf72b520f3e4643d67cd8cf` tip. Actual measured binary commits above are never relabeled.

[Chronological experiment history and retention decisions](experiment-history.md) · [Separate operation/workload conclusions](experiment-outcomes.json)
