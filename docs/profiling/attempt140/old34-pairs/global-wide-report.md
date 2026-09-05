### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **PASS**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 1.02x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 1.16x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.02x; required 10.00x
pair-2: P95 speedup 1.04x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 1.25x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.04x; required 10.00x
pair-3: P95 speedup 1.23x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 1.37x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.23x; required 10.00x

- Worst paired base P50 / P95: **1.751 ms / 42.972 ms**
- Worst paired candidate P50 / P95: **1.757 ms / 42.150 ms**
- Worst individual P95 speedup (retained for audit): **1.02x**
- Worst wrapped case-insensitive P95 speedup: **1.02x**
- Worst order-median P95 speedup: **1.04x**
- candidate-base: **2 pair(s), 0.93x P50 / 1.13x P95**
- base-candidate: **1 pair(s), 1.14x P50 / 1.04x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 1.751 ms | 42.972 ms | 1.757 ms | 42.150 ms | 1.00x | 1.02x | 1507.567 ms → 1434.618 ms | 3.10 | 4.39 GiB → 4.00 GiB | 4.91 GiB → 4.49 GiB |
| 2 | base-candidate | 2.047 ms | 51.234 ms | 1.790 ms | 49.122 ms | 1.14x | 1.04x | 1590.003 ms → 1502.798 ms | 3.19 | 4.38 GiB → 4.00 GiB | 4.89 GiB → 4.50 GiB |
| 3 | candidate-base | 1.595 ms | 49.197 ms | 1.848 ms | 39.950 ms | 0.86x | 1.23x | 1535.388 ms → 1418.618 ms | 3.07 | 4.38 GiB → 3.99 GiB | 4.91 GiB → 4.47 GiB |

**Result: PASS**
