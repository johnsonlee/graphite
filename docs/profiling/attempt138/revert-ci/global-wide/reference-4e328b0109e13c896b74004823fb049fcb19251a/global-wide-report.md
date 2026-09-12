### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **FAIL**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 1.09x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 1.48x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.09x; required 10.00x
pair-2: P95 speedup 0.92x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 0.81x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.92x; required 10.00x
pair-3: P95 speedup 1.56x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 1.01x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.56x; required 10.00x

- Worst paired base P50 / P95: **5.836 ms / 130.637 ms**
- Worst paired candidate P50 / P95: **6.475 ms / 142.437 ms**
- Worst individual P95 speedup (retained for audit): **0.92x**
- Worst wrapped case-insensitive P95 speedup: **0.81x**
- Worst order-median P95 speedup: **0.92x**
- candidate-base: **2 pair(s), 0.93x P50 / 1.33x P95**
- base-candidate: **1 pair(s), 0.90x P50 / 0.92x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 7.414 ms | 130.939 ms | 7.630 ms | 120.137 ms | 0.97x | 1.09x | 3920.000 ms → 4050.000 ms | 3.14 | 4.01 GiB → 4.25 GiB | 4.78 GiB → 4.99 GiB |
| 2 | base-candidate | 5.836 ms | 130.637 ms | 6.475 ms | 142.437 ms | 0.90x | 0.92x | 3280.000 ms → 3870.000 ms | 3.20 | 4.02 GiB → 4.02 GiB | 4.86 GiB → 4.76 GiB |
| 3 | candidate-base | 6.119 ms | 191.349 ms | 6.936 ms | 122.319 ms | 0.88x | 1.56x | 4420.000 ms → 4000.000 ms | 3.17 | 4.21 GiB → 4.08 GiB | 4.95 GiB → 4.78 GiB |

**Result: FAIL**

pair-2: process CPU 3870000000 exceeds paired base 3280000000 by >15%
pair-1/global-wide-class-pair/zero: aligned latency 10567829 exceeds base 7752720 by >15% and >1 ms; repeated in 2 independent pairs
pair-1/global-wide-aliased/targeted: aligned latency 12420924 exceeds base 9655432 by >15% and >1 ms; repeated in 3 independent pairs
pair-1/global-wide-parameterized/targeted: aligned latency 7839121 exceeds base 5832781 by >15% and >1 ms; repeated in 2 independent pairs
pair-1/global-wide-distribution-localized-early/dense: aligned latency 7593872 exceeds base 3601546 by >15% and >1 ms; repeated in 3 independent pairs
pair-2/global-wide-four-properties/dense: aligned latency 13866179 exceeds base 10909952 by >15% and >1 ms; repeated in 2 independent pairs
pair-2/global-wide-class-pair/targeted: aligned latency 11780115 exceeds base 10217513 by >15% and >1 ms; repeated in 2 independent pairs
