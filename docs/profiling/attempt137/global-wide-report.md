### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **PASS**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 2.61x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 0.90x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 2.61x; required 10.00x
pair-2: P95 speedup 1.02x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 1.00x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.02x; required 10.00x
pair-3: P95 speedup 1.22x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 0.97x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.22x; required 10.00x

- Worst paired base P50 / P95: **1.979 ms / 49.278 ms**
- Worst paired candidate P50 / P95: **1.847 ms / 48.396 ms**
- Worst individual P95 speedup (retained for audit): **1.02x**
- Worst wrapped case-insensitive P95 speedup: **0.90x**
- Worst order-median P95 speedup: **1.02x**
- candidate-base: **2 pair(s), 1.04x P50 / 1.91x P95**
- base-candidate: **1 pair(s), 1.07x P50 / 1.02x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 1.714 ms | 137.409 ms | 1.829 ms | 52.732 ms | 0.94x | 2.61x | 1687.700 ms → 1430.862 ms | 3.50 | 4.39 GiB → 3.57 GiB | 4.89 GiB → 4.07 GiB |
| 2 | base-candidate | 1.979 ms | 49.278 ms | 1.847 ms | 48.396 ms | 1.07x | 1.02x | 1544.348 ms → 1415.953 ms | 3.48 | 4.02 GiB → 3.58 GiB | 4.52 GiB → 4.09 GiB |
| 3 | candidate-base | 2.246 ms | 49.659 ms | 1.956 ms | 40.778 ms | 1.15x | 1.22x | 1702.896 ms → 1282.096 ms | 3.37 | 4.39 GiB → 3.58 GiB | 4.91 GiB → 4.06 GiB |

**Result: PASS**
