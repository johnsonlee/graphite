### Global-query iteration verification

Iteration acceptance: **failed**.
No-regression checks: **passed**.
P95 progress against the last accepted iteration in every paired fork: **not achieved**.
Final 10x target against frozen main `4e328b0109e13c896b74004823fb049fcb19251a`: **not achieved**.
This run reports separately the final 10x target.

Candidate: `b94b8caa8dea10d1d2ddb74a0a0c39a3ab5351f0`. Current PR base: `4e328b0109e13c896b74004823fb049fcb19251a`. Last accepted iteration: `4e328b0109e13c896b74004823fb049fcb19251a`.

A passing iteration does not establish completion of the 10x objective.

Blocking failures:

- last-accepted pair-1: candidate P95 297628775 must improve on reference P95 119915932
- last-accepted pair-3: candidate P95 117182906 must improve on reference P95 110626223

Final target evidence:

- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1: P95 speedup 0.40x; required 10.00x in every independent fork
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1: global-wide-wrapped-case-insensitive P95 speedup 0.96x; required 10.00x
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.40x; required 10.00x
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-2: P95 speedup 1.08x; required 10.00x in every independent fork
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-2: global-wide-wrapped-case-insensitive P95 speedup 1.16x; required 10.00x
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.08x; required 10.00x
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-3: P95 speedup 0.94x; required 10.00x in every independent fork
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-3: global-wide-wrapped-case-insensitive P95 speedup 1.03x; required 10.00x
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.94x; required 10.00x

#### Reference 4e328b0109e13c896b74004823fb049fcb19251a

[Individual report](reference-4e328b0109e13c896b74004823fb049fcb19251a/global-wide-report.md)

### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **PASS**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 0.40x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 0.96x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.40x; required 10.00x
pair-2: P95 speedup 1.08x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 1.16x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 1.08x; required 10.00x
pair-3: P95 speedup 0.94x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 1.03x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.94x; required 10.00x

- Worst paired base P50 / P95: **7.041 ms / 119.916 ms**
- Worst paired candidate P50 / P95: **6.095 ms / 297.629 ms**
- Worst individual P95 speedup (retained for audit): **0.40x**
- Worst wrapped case-insensitive P95 speedup: **0.40x**
- Worst order-median P95 speedup: **0.67x**
- candidate-base: **2 pair(s), 1.06x P50 / 0.67x P95**
- base-candidate: **1 pair(s), 0.99x P50 / 1.08x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 7.041 ms | 119.916 ms | 6.095 ms | 297.629 ms | 1.16x | 0.40x | 4130.000 ms → 4260.000 ms | 2.91 | 3.98 GiB → 4.14 GiB | 5.21 GiB → 4.92 GiB |
| 2 | base-candidate | 7.011 ms | 127.840 ms | 7.114 ms | 118.118 ms | 0.99x | 1.08x | 3940.000 ms → 3680.000 ms | 3.27 | 4.10 GiB → 4.08 GiB | 4.84 GiB → 4.80 GiB |
| 3 | candidate-base | 7.551 ms | 110.626 ms | 7.754 ms | 117.183 ms | 0.97x | 0.94x | 3680.000 ms → 3810.000 ms | 3.18 | 4.01 GiB → 3.98 GiB | 4.74 GiB → 4.74 GiB |

**Result: PASS**

