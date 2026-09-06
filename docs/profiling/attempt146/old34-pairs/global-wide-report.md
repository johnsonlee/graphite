### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **PASS**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 1.34x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 1.06x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.34x; required 10.00x
pair-2: P95 speedup 1.32x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 0.83x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.32x; required 10.00x
pair-3: P95 speedup 0.99x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 0.98x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.99x; required 10.00x

- Worst paired base P50 / P95: **2.177 ms / 51.742 ms**
- Worst paired candidate P50 / P95: **2.228 ms / 52.154 ms**
- Worst individual P95 speedup (retained for audit): **0.99x**
- Worst wrapped case-insensitive P95 speedup: **0.83x**
- Worst order-median P95 speedup: **1.17x**
- candidate-base: **2 pair(s), 0.90x P50 / 1.17x P95**
- base-candidate: **1 pair(s), 0.76x P50 / 1.32x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 2.059 ms | 57.550 ms | 2.481 ms | 42.818 ms | 0.83x | 1.34x | 1623.256 ms → 1559.704 ms | 3.19 | 4.38 GiB → 3.97 GiB | 4.90 GiB → 4.47 GiB |
| 2 | base-candidate | 1.793 ms | 62.781 ms | 2.367 ms | 47.492 ms | 0.76x | 1.32x | 1714.644 ms → 1684.759 ms | 3.18 | 3.93 GiB → 4.20 GiB | 4.44 GiB → 4.72 GiB |
| 3 | candidate-base | 2.177 ms | 51.742 ms | 2.228 ms | 52.154 ms | 0.98x | 0.99x | 1726.941 ms → 1842.224 ms | 3.32 | 4.38 GiB → 4.39 GiB | 4.91 GiB → 4.91 GiB |

**Result: PASS**
