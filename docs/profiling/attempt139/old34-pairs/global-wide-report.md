### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **PASS**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 1.05x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 1.02x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.05x; required 10.00x
pair-2: P95 speedup 0.43x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 0.91x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.43x; required 10.00x
pair-3: P95 speedup 1.09x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 0.87x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.09x; required 10.00x

- Worst paired base P50 / P95: **1.707 ms / 58.594 ms**
- Worst paired candidate P50 / P95: **1.920 ms / 137.031 ms**
- Worst individual P95 speedup (retained for audit): **0.43x**
- Worst wrapped case-insensitive P95 speedup: **0.43x**
- Worst order-median P95 speedup: **0.43x**
- candidate-base: **2 pair(s), 0.96x P50 / 1.07x P95**
- base-candidate: **1 pair(s), 0.89x P50 / 0.43x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 1.745 ms | 118.032 ms | 1.855 ms | 112.874 ms | 0.94x | 1.05x | 1748.474 ms → 1545.742 ms | 3.11 | 4.38 GiB → 3.58 GiB | 4.88 GiB → 4.05 GiB |
| 2 | base-candidate | 1.707 ms | 58.594 ms | 1.920 ms | 137.031 ms | 0.89x | 0.43x | 1424.759 ms → 1463.312 ms | 3.02 | 4.39 GiB → 3.58 GiB | 4.89 GiB → 4.05 GiB |
| 3 | candidate-base | 1.749 ms | 54.235 ms | 1.797 ms | 49.777 ms | 0.97x | 1.09x | 1446.098 ms → 1226.040 ms | 2.89 | 4.01 GiB → 3.46 GiB | 4.53 GiB → 4.09 GiB |

**Result: PASS**
