### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **FAIL**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 0.35x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 1.10x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.35x; required 10.00x
pair-2: P95 speedup 0.88x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 0.88x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.88x; required 10.00x
pair-3: P95 speedup 1.34x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 1.07x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.34x; required 10.00x

- Worst paired base P50 / P95: **1.686 ms / 41.609 ms**
- Worst paired candidate P50 / P95: **1.876 ms / 118.185 ms**
- Worst individual P95 speedup (retained for audit): **0.35x**
- Worst wrapped case-insensitive P95 speedup: **0.35x**
- Worst order-median P95 speedup: **0.85x**
- candidate-base: **2 pair(s), 0.96x P50 / 0.85x P95**
- base-candidate: **1 pair(s), 0.79x P50 / 0.88x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 1.686 ms | 41.609 ms | 1.876 ms | 118.185 ms | 0.90x | 0.35x | 1476.806 ms → 1819.397 ms | 3.38 | 4.39 GiB → 3.93 GiB | 5.04 GiB → 4.45 GiB |
| 2 | base-candidate | 1.668 ms | 48.694 ms | 2.121 ms | 55.151 ms | 0.79x | 0.88x | 1492.257 ms → 1572.952 ms | 3.12 | 3.94 GiB → 4.10 GiB | 4.46 GiB → 4.60 GiB |
| 3 | candidate-base | 1.946 ms | 61.887 ms | 1.908 ms | 46.231 ms | 1.02x | 1.34x | 1644.617 ms → 1519.967 ms | 3.17 | 4.39 GiB → 4.02 GiB | 4.89 GiB → 4.53 GiB |

**Result: FAIL**

pair-1: process CPU 1819397000 exceeds paired base 1476806000 by >15%
