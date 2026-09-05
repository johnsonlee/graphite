### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **FAIL**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 0.51x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 1.13x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.51x; required 10.00x
pair-2: P95 speedup 1.62x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 0.99x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.62x; required 10.00x
pair-3: P95 speedup 0.67x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 0.95x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.67x; required 10.00x

- Worst paired base P50 / P95: **1.917 ms / 52.132 ms**
- Worst paired candidate P50 / P95: **1.870 ms / 101.659 ms**
- Worst individual P95 speedup (retained for audit): **0.51x**
- Worst wrapped case-insensitive P95 speedup: **0.51x**
- Worst order-median P95 speedup: **0.59x**
- candidate-base: **2 pair(s), 0.97x P50 / 0.59x P95**
- base-candidate: **1 pair(s), 0.89x P50 / 1.62x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 1.917 ms | 52.132 ms | 1.870 ms | 101.659 ms | 1.03x | 0.51x | 1642.950 ms → 1817.217 ms | 3.27 | 4.39 GiB → 4.39 GiB | 4.90 GiB → 4.93 GiB |
| 2 | base-candidate | 1.663 ms | 73.699 ms | 1.875 ms | 45.519 ms | 0.89x | 1.62x | 1654.015 ms → 1501.512 ms | 3.01 | 3.93 GiB → 4.38 GiB | 4.44 GiB → 4.90 GiB |
| 3 | candidate-base | 1.736 ms | 47.265 ms | 1.913 ms | 70.090 ms | 0.91x | 0.67x | 1462.809 ms → 1720.888 ms | 3.19 | 4.01 GiB → 4.39 GiB | 4.61 GiB → 4.90 GiB |

**Result: FAIL**

pair-3: process CPU 1720888000 exceeds paired base 1462809000 by >15%
pair-1/aggregate P95: latency 101659125 exceeds base 52132041 by >15% and >1 ms; repeated in 2 independent pairs
pair-1/global-wide-wrapped-case-insensitive-distinct P95: latency 101659125 exceeds base 52132041 by >15% and >1 ms; repeated in 2 independent pairs
pair-1/global-wide-wrapped-case-insensitive-distinct/dense: aligned latency 101659125 exceeds base 52132041 by >15% and >1 ms; repeated in 2 independent pairs
