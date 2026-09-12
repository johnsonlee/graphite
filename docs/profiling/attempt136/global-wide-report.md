### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **PASS**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 0.93x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 1.08x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.93x; required 10.00x
pair-2: P95 speedup 1.18x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 1.06x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.18x; required 10.00x
pair-3: P95 speedup 1.14x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 0.96x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.14x; required 10.00x

- Worst paired base P50 / P95: **2.000 ms / 47.075 ms**
- Worst paired candidate P50 / P95: **1.818 ms / 50.419 ms**
- Worst individual P95 speedup (retained for audit): **0.93x**
- Worst wrapped case-insensitive P95 speedup: **0.93x**
- Worst order-median P95 speedup: **1.04x**
- candidate-base: **2 pair(s), 1.08x P50 / 1.04x P95**
- base-candidate: **1 pair(s), 1.11x P50 / 1.18x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 2.000 ms | 47.075 ms | 1.818 ms | 50.419 ms | 1.10x | 0.93x | 1499.329 ms → 1552.886 ms | 3.13 | 4.38 GiB → 4.03 GiB | 4.89 GiB → 4.53 GiB |
| 2 | base-candidate | 1.802 ms | 65.733 ms | 1.617 ms | 55.484 ms | 1.11x | 1.18x | 1659.594 ms → 1511.979 ms | 3.18 | 4.06 GiB → 3.95 GiB | 4.68 GiB → 4.44 GiB |
| 3 | candidate-base | 1.832 ms | 47.527 ms | 1.725 ms | 41.616 ms | 1.06x | 1.14x | 1590.953 ms → 1491.688 ms | 3.04 | 4.38 GiB → 4.37 GiB | 4.91 GiB → 4.90 GiB |

**Result: PASS**
