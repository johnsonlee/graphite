# 141 independent payload branch census

| Case | Candidate graphs | Mask-eligible graphs | Candidate graphs staying hash | Eligible S bytes | Replaced H key payload bytes |
|---|---:|---:|---:|---:|---:|
| global-wide-wrapped-case-insensitive-distinct-targeted | 2 | 0 | 2 | 0 | 0 |
| global-wide-wrapped-case-insensitive-distinct-dense | 64 | 64 | 0 | 2793940 | 7013376 |
| or-four-broad | 55 | 0 | 55 | 0 | 0 |
| or-four-single-early | 1 | 0 | 1 | 0 | 0 |
| or-four-single-middle | 1 | 0 | 1 | 0 | 0 |
| or-four-single-late | 1 | 0 | 1 | 0 | 0 |
| or-four-few-early-late | 2 | 0 | 2 | 0 | 0 |
| or-four-all | 64 | 64 | 0 | 2793940 | 11619328 |

All 8×64 per-graph branch values and term count/ID-hash matches are in branch-census.json. Eligible does not prove execution or allocation: existing upstream/selected-tuple early returns still apply.
