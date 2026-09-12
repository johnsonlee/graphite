### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **PASS**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 2.92x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 0.94x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 2.92x; required 10.00x
pair-2: P95 speedup 2.39x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 1.05x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 2.39x; required 10.00x
pair-3: P95 speedup 1.78x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 1.05x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.78x; required 10.00x

- Worst paired base P50 / P95: **1.651 ms / 37.082 ms**
- Worst paired candidate P50 / P95: **1.908 ms / 20.844 ms**
- Worst individual P95 speedup (retained for audit): **1.78x**
- Worst wrapped case-insensitive P95 speedup: **0.94x**
- Worst order-median P95 speedup: **2.35x**
- candidate-base: **2 pair(s), 0.94x P50 / 2.35x P95**
- base-candidate: **1 pair(s), 1.27x P50 / 2.39x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 1.907 ms | 56.735 ms | 1.868 ms | 19.399 ms | 1.02x | 2.92x | 1573.797 ms → 1275.374 ms | 2.84 | 4.38 GiB → 4.38 GiB | 4.92 GiB → 4.86 GiB |
| 2 | base-candidate | 2.007 ms | 53.315 ms | 1.576 ms | 22.333 ms | 1.27x | 2.39x | 1576.525 ms → 1305.471 ms | 2.99 | 4.39 GiB → 4.11 GiB | 4.91 GiB → 4.57 GiB |
| 3 | candidate-base | 1.651 ms | 37.082 ms | 1.908 ms | 20.844 ms | 0.87x | 1.78x | 1445.943 ms → 1256.500 ms | 2.85 | 4.39 GiB → 4.27 GiB | 4.91 GiB → 4.75 GiB |

**Result: PASS**
