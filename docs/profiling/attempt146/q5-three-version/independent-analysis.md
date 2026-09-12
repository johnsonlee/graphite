# Original34 three-version diagnostic: independent result audit

All **306 unique original14 signatures** across **nine complete 34-query forks** pass. The nine exact JMH commands, real normal process-group exits, before/after identities, recorded resources and every field of all three original comparator reports recompute independently. All TSV non-latency fields match the original reference and every paired version; q5 work is 2674 in all nine observations.

The experiment does **not** establish 10× or CI acceptance. Official versus main fails the original regression gate. Helper versus main and versus official pass this batch's regression gate but fail the 10× target, with substantial full34 and CPU variability retained.

| Comparison | P95 speedup, groups1/2/3 | Full-replay process CPU change, groups1/2/3 | Original regression result |
|---|---|---|---|
| official / main | 0.875 / 1.039 / 0.833 | +18.34% / +7.12% / +16.64% | FAIL |
| helper / main | 1.355 / 0.899 / 0.915 | +7.95% / +10.99% / +12.59% | PASS |
| helper / official | 1.549 / 0.866 / 1.098 | −8.78% / +3.61% / −3.47% | PASS |

Official/main fails process CPU in groups1 and3, and q29 `global-wide-wrapped-case-insensitive-distinct-targeted` repeats a >15% and >1 ms latency flag in groups1 and2. This is q29, not q5. No failed condition was removed to obtain the two other comparison results.

## q5 within this batch

| Group / actual complete fork order | Main ms | Official ms | Helper ms | Official−main ms | Helper−official ms |
|---|---:|---:|---:|---:|---:|
| 1: main, official, helper | 4.465 | 5.023 | 5.081 | +0.557 | +0.058 |
| 2: official, helper, main | 4.354 | 4.716 | 5.277 | +0.362 | +0.560 |
| 3: helper, main, official | 4.290 | 5.133 | 5.343 | +0.843 | +0.210 |

Official/main q5 ratios are 1.125/1.083/1.197; none crosses both boundaries. Helper/main ratios are 1.138/1.212/1.246; only group3 crosses both, because group2's absolute increase is 0.922ms. Helper/official ratios are 1.012/1.119/1.041 and do not flag. Each observation returns the same one-row digest and 2674 work units.

The same-group pattern is compatible with an existing official-branch q5 difference plus a smaller helper increment in this batch. It does not establish that the helper has zero cost, that either increment is caused by a particular method, or that prior regression was noise. Three samples and cyclic position balance cannot eliminate host/time effects or separate JIT from execution/waiting. q5's earlier repeated starting-main failure remains valid historical evidence; it is merely not repeated by this batch's threshold. Main and official are different production baselines; matching the 12 pressure/JMH class payloads does not equate all transitive utility or runtime behavior.

## Every per-query flag retained

- official-vs-main: group1 global-wide-wrapped-case-insensitive-distinct-targeted; group2 global-wide-name-pair-targeted, global-wide-wrapped-case-insensitive-distinct-targeted; group3 global-wide-wrapped-case-insensitive-distinct-zero, global-wide-wrapped-case-insensitive-distinct-dense.
- helper-vs-main: group1 global-wide-caller-class-targeted; group2 global-wide-name-pair-targeted, global-wide-wrapped-case-insensitive-distinct-targeted; group3 global-wide-class-pair-targeted, global-wide-aliased-targeted.
- helper-vs-official: group1 (none); group2 global-wide-wrapped-case-insensitive-distinct-dense; group3 (none).

Aggregate/wrapped/resource gate conditions and all per-query rows, including non-flagged observations, remain in `post-audit.json`. The shared forks appear in two pairwise reports; those reports are not independent experiments. The 3-group order balances each version's position but not all predecessor permutations. Pair order strings match actual occurrence order even when the third version lies between a pair.

Independent verification reads the original oracle and reference schema, exact JSON JVM parameters/mode/forks/warmup, nearest-rank global P50/P95 (rank33 for P95), all raw secondary resource metrics, full34 signatures/order, three comparator command arrays/errors/targets/order summaries, actual PID/PGID and normal empty group cleanup. CPU/resource metrics describe the whole replay, including inter-query work; they are not q5 CPU or a utilization cause. Every declared final output hash was rechecked. Small source/receipt pins were independently reread; large JAR/fixture content identities are bound to terminal before/after and build/scope receipts rather than rehashed here.

`post-audit.py` completed exit0 on the first execution against this experiment. Before measurement completion its pure recomputation function had passed two previously closed original34 report checks (one PASS, one FAIL); no current live output was used. `post-audit-preparation.json` records this preparation. No JVM, Node/comparator rerun, benchmark or production mutation was performed during the independent audit. The runner/comparator outputs were left unchanged.
