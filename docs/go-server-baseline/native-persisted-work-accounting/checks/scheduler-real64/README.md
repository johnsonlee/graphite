# Independent scheduler real64 audit

The new scheduler source reproduces the **declared public results and graph-state fields** of all original captured phases. The previous 15162 `mappedRangeCount` mismatches are gone in this capture. This does not establish all-success or P95 acceptance.

| Configuration | Actual phase covered | Cases | Graph-state observations | Public/state differences |
| --- | --- | ---: | ---: | --- |
| cold | replay | 1267 | 162304 | 0 / 0 |
| warm | warmup only | 1267 | 162240 | 0 / 0 |
| startup-prepared | replay | 1267 | 162304 | 0 / 0 |

The audit independently compares 3801 case executions and 486848 graph-state observations against the frozen original-main records. The state fields are source ID, retained index, mapped view, trigrams, loaded-from-persistence, mapped-range cache count, raw-match cache count and raw-projection cache count. Nonzero raw-projection observations total 2515 per configuration, with maximum count 4; raw-match observations remain zero. Startup begins with all 64 retained/trigram/persisted capabilities present. The other configurations begin with those capabilities absent. Cold and startup also independently match all 1266 successful original manifest digests, row counts and response sizes.

Each configuration contains 1266 successes and the original case-821 `IllegalStateException`, message `Unsafe expression reached parallel string projection`. Both original main and Go exit 1; all three comparison verifiers exit 0. The original all-success gate remains failed. **Warm never completes invocation preparation or enters formal replay**: its complete 1267-case warmup finishes with that same failure. Calling this a successful formal warm benchmark would be incorrect.

The separately observed qualified error-class field still differs once in each configuration: main exposes `java.lang.IllegalStateException`, while Go omits that field. Consequently the audit does not claim equality of every raw JSON field or JVM stack/FQCN parity.

The complete 2568-file source maps agree across scheduler-focused-v1, probe-v5, scheduler-full-checks, frozen real64-source-v3 and the original matrix201 source. The already completed `scheduler-source-verification.json` binds all file bytes. This review independently compares those complete maps and rehashes all 337 Go/module/sum sources in both current and frozen workspaces, plus each runtime's 337 compiled inputs and binary. Focused tests passed with race enabled; probe-v5 passes 111 public cases / 482 operations; full context/race/vet checks pass with 3388 recorded inputs. Runtime receipts for main and Go report all 1152 original graph files unchanged in every configuration. Their raw receipts and archives were verified; graph bytes were not reread again by this audit.

The new 201-case diagnostic capture still matches main on 166/181 public cases and 19/20 provider-wrapper controls, leaving the same 16 known comparison differences. Compilation and capture both exit 0; the comparison is not an all-pass result. Its original 2568 source files remain unchanged; diagnostic compilation adds exactly one explicitly captured test harness, `generic_disjunction_diagnostic_test.go`. The new and previous Go captures differ only in six `goStack` values. All other fields of the 201 cases are equal; these preserved stack differences explain why `completeGoOutputEqualPrevious` is false in the audit JSON. Provider-wrapper and unavailable diagnostic surfaces retain their existing scope limits.

No performance measurements or P95 samples were collected. The evidence supports reporting restored compatibility for these declared real64 phases and successful source-bound checks. It does not support overall acceptance, a formal warm replay, eliminating the 201-case gaps, implementing unrelated private counters, configured-worker overrides, or global memory-budget parity.

`evidence/` retains 26 original artifacts (about 10.4 MB), compressing raw JSONL and logs without changing their decompressed bytes. `archive-manifest.json` records external origins, sizes and hashes. `archive-and-audit.py` reads those originals, refuses to replace existing differing archive bytes, starts no Go/JVM process, and generates `independent-audit.json`. Earlier failed runs and their audits are untouched.
