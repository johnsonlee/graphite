### Global-query iteration verification

Iteration acceptance: **failed**.
No-regression checks: **failed**.
P95 progress against the last accepted iteration in every paired fork: **not achieved**.
Final 10x target against frozen main `4e328b0109e13c896b74004823fb049fcb19251a`: **not achieved**.
This run reports separately the final 10x target.

Candidate: `e6c932c5e1d0fb7b583ceb9e14c8ef88ec9d9694`. Current PR base: `4e328b0109e13c896b74004823fb049fcb19251a`. Last accepted iteration: `4e328b0109e13c896b74004823fb049fcb19251a`.

A passing iteration does not establish completion of the 10x objective.

Blocking failures:

- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1/aggregate P95: latency 144742573 exceeds base 125214119 by >15% and >1 ms; repeated in 3 independent pairs
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1/global-wide-wrapped-case-insensitive-distinct P95: latency 144742573 exceeds base 125214119 by >15% and >1 ms; repeated in 3 independent pairs
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1/global-wide-name-pair/zero: aligned latency 10127343 exceeds base 5278509 by >15% and >1 ms; repeated in 2 independent pairs
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1/global-wide-caller-class/targeted: aligned latency 8878257 exceeds base 6559061 by >15% and >1 ms; repeated in 2 independent pairs
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1/global-wide-aliased/zero: aligned latency 4191593 exceeds base 3085990 by >15% and >1 ms; repeated in 2 independent pairs
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1/global-wide-wrapped-case-insensitive-distinct/zero: aligned latency 15693627 exceeds base 13642057 by >15% and >1 ms; repeated in 2 independent pairs
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1/global-wide-wrapped-case-insensitive-distinct/dense: aligned latency 144742573 exceeds base 125214119 by >15% and >1 ms; repeated in 3 independent pairs
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-2/global-wide-class-pair/targeted: aligned latency 13433220 exceeds base 10851995 by >15% and >1 ms; repeated in 2 independent pairs
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-2/global-wide-provenance/zero: aligned latency 8148351 exceeds base 2951391 by >15% and >1 ms; repeated in 2 independent pairs
- Valid passing paired evidence against the last accepted iteration is required for progress

Final target evidence:

- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1: P95 speedup 0.87x; required 10.00x in every independent fork
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1: global-wide-wrapped-case-insensitive P95 speedup 1.14x; required 10.00x
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.87x; required 10.00x
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-2: P95 speedup 0.82x; required 10.00x in every independent fork
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-2: global-wide-wrapped-case-insensitive P95 speedup 0.74x; required 10.00x
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.82x; required 10.00x
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-3: P95 speedup 0.74x; required 10.00x in every independent fork
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-3: global-wide-wrapped-case-insensitive P95 speedup 1.03x; required 10.00x
- 4e328b0109e13c896b74004823fb049fcb19251a: pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.74x; required 10.00x

#### Reference 4e328b0109e13c896b74004823fb049fcb19251a

[Individual report](reference-4e328b0109e13c896b74004823fb049fcb19251a/global-wide-report.md)

### 64 fixture-derived global wide-query pressure gate

Evaluation: **non-regression**
P95 target in every independent paired fork: **10.0x**
Regression checks: **FAIL**; target achieved: **NO**
Target remains unmet; a passing regression evaluation does not establish the speedup target.
pair-1: P95 speedup 0.87x; required 10.00x in every independent fork
pair-1: global-wide-wrapped-case-insensitive P95 speedup 1.14x; required 10.00x
pair-1: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.87x; required 10.00x
pair-2: P95 speedup 0.82x; required 10.00x in every independent fork
pair-2: global-wide-wrapped-case-insensitive P95 speedup 0.74x; required 10.00x
pair-2: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.82x; required 10.00x
pair-3: P95 speedup 0.74x; required 10.00x in every independent fork
pair-3: global-wide-wrapped-case-insensitive P95 speedup 1.03x; required 10.00x
pair-3: global-wide-wrapped-case-insensitive-distinct P95 speedup 0.74x; required 10.00x

- Worst paired base P50 / P95: **5.681 ms / 112.354 ms**
- Worst paired candidate P50 / P95: **6.969 ms / 150.906 ms**
- Worst individual P95 speedup (retained for audit): **0.74x**
- Worst wrapped case-insensitive P95 speedup: **0.74x**
- Worst order-median P95 speedup: **0.80x**
- candidate-base: **2 pair(s), 0.96x P50 / 0.80x P95**
- base-candidate: **1 pair(s), 0.97x P50 / 0.82x P95**

| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | candidate-base | 5.819 ms | 125.214 ms | 5.255 ms | 144.743 ms | 1.11x | 0.87x | 4010.000 ms → 3830.000 ms | 3.13 | 4.21 GiB → 4.05 GiB | 4.95 GiB → 4.85 GiB |
| 2 | base-candidate | 6.318 ms | 121.342 ms | 6.485 ms | 148.690 ms | 0.97x | 0.82x | 3810.000 ms → 3750.000 ms | 3.11 | 4.01 GiB → 4.02 GiB | 4.77 GiB → 4.77 GiB |
| 3 | candidate-base | 5.681 ms | 112.354 ms | 6.969 ms | 150.906 ms | 0.82x | 0.74x | 3760.000 ms → 3860.000 ms | 3.10 | 4.21 GiB → 4.02 GiB | 4.96 GiB → 4.83 GiB |

**Result: FAIL**

pair-1/aggregate P95: latency 144742573 exceeds base 125214119 by >15% and >1 ms; repeated in 3 independent pairs
pair-1/global-wide-wrapped-case-insensitive-distinct P95: latency 144742573 exceeds base 125214119 by >15% and >1 ms; repeated in 3 independent pairs
pair-1/global-wide-name-pair/zero: aligned latency 10127343 exceeds base 5278509 by >15% and >1 ms; repeated in 2 independent pairs
pair-1/global-wide-caller-class/targeted: aligned latency 8878257 exceeds base 6559061 by >15% and >1 ms; repeated in 2 independent pairs
pair-1/global-wide-aliased/zero: aligned latency 4191593 exceeds base 3085990 by >15% and >1 ms; repeated in 2 independent pairs
pair-1/global-wide-wrapped-case-insensitive-distinct/zero: aligned latency 15693627 exceeds base 13642057 by >15% and >1 ms; repeated in 2 independent pairs
pair-1/global-wide-wrapped-case-insensitive-distinct/dense: aligned latency 144742573 exceeds base 125214119 by >15% and >1 ms; repeated in 3 independent pairs
pair-2/global-wide-class-pair/targeted: aligned latency 13433220 exceeds base 10851995 by >15% and >1 ms; repeated in 2 independent pairs
pair-2/global-wide-provenance/zero: aligned latency 8148351 exceeds base 2951391 by >15% and >1 ms; repeated in 2 independent pairs
