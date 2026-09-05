### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **FAIL**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 1.32x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 1.20x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.32x; required 10.00x
pair-2: P95 speedup 1.75x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 0.84x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.75x; required 10.00x
pair-3: P95 speedup 1.13x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 1.03x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.13x; required 10.00x

- Worst paired base P50 / P95: **6.984 ms / 125.710 ms**
- Worst paired candidate P50 / P95: **5.602 ms / 111.394 ms**
- Worst individual P95 speedup (retained for audit): **1.13x**
- Worst wrapped case-insensitive P95 speedup: **0.84x**
- Worst order-median P95 speedup: **1.22x**
- candidate-base: **2 pair(s), 1.26x P50 / 1.22x P95**
- base-candidate: **1 pair(s), 0.79x P50 / 1.75x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 6.939 ms | 149.665 ms | 5.463 ms | 113.355 ms | 1.27x | 1.32x | 4050.000 ms → 3370.000 ms | 2.85 | 4.04 GiB → 4.03 GiB | 4.75 GiB → 4.75 GiB |
| 2 | base-candidate | 5.356 ms | 251.114 ms | 6.752 ms | 143.174 ms | 0.79x | 1.75x | 3840.000 ms → 3930.000 ms | 3.20 | 4.05 GiB → 4.04 GiB | 4.77 GiB → 4.78 GiB |
| 3 | candidate-base | 6.984 ms | 125.710 ms | 5.602 ms | 111.394 ms | 1.25x | 1.13x | 3820.000 ms → 3400.000 ms | 2.93 | 4.00 GiB → 4.04 GiB | 4.75 GiB → 4.76 GiB |

**Result: FAIL**

pair-2/global-wide-callee-class/zero: aligned latency 6008926 exceeds base 4235666 by >15% and >1 ms; repeated in 2 independent pairs
