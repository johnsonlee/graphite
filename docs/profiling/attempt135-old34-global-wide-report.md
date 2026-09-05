### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **FAIL**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 1.30x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 1.03x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.30x; required 10.00x
pair-2: P95 speedup 1.50x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 1.01x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.50x; required 10.00x
pair-3: P95 speedup 2.09x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 1.11x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 2.09x; required 10.00x

- Worst paired base P50 / P95: **1.830 ms / 43.854 ms**
- Worst paired candidate P50 / P95: **1.843 ms / 33.787 ms**
- Worst individual P95 speedup (retained for audit): **1.30x**
- Worst wrapped case-insensitive P95 speedup: **1.01x**
- Worst order-median P95 speedup: **1.50x**
- candidate-base: **2 pair(s), 0.95x P50 / 1.69x P95**
- base-candidate: **1 pair(s), 0.95x P50 / 1.50x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 1.830 ms | 43.854 ms | 1.843 ms | 33.787 ms | 0.99x | 1.30x | 1485.746 ms → 1412.893 ms | 3.13 | 4.38 GiB → 4.10 GiB | 4.90 GiB → 4.60 GiB |
| 2 | base-candidate | 1.806 ms | 47.670 ms | 1.907 ms | 31.796 ms | 0.95x | 1.50x | 1480.937 ms → 1443.842 ms | 3.06 | 4.39 GiB → 4.38 GiB | 4.91 GiB → 4.92 GiB |
| 3 | candidate-base | 1.695 ms | 60.723 ms | 1.867 ms | 29.107 ms | 0.91x | 2.09x | 1659.312 ms → 1395.558 ms | 3.07 | 4.12 GiB → 4.02 GiB | 4.76 GiB → 4.51 GiB |

**Result: FAIL**

pair-1/global-wide-wrapped-case-insensitive-distinct/targeted: aligned latency 33787208 exceeds base 21969542 by >15% and >1 ms; repeated in 2 independent pairs
