# Lazy-core cold independent audit

The cold regression remains failed. All 1,267 declared public results/error outcomes match main and v4, but 2,527 of 162,304 graph-state observations differ from main. Every state difference is `fixture-android-08.mappedRangeCount`: main = 4, lazy-core = 0, first at `replay/3/after` (`single-contains-unlabeled-dense`). It persists for that after snapshot and both snapshots of the remaining 1,263 cases. Every other graph and compared state field matches main.

Compared with v4, lazy-core differs only in `mappedRangeCount` for android03–07, each across 2,527 observations (12,635 total). Their case 3 after values change from zero to main’s 6, 7, 1, 5, 5 respectively. android08 remains zero in both native captures. This is progress in the observed cache state, not a passed regression or evidence establishing a particular worker schedule.

The audit independently traversed all raw JSON fields across all 1,269 records. v4 versus lazy-core has no differences outside those before/after range counts. Main versus lazy-core also differs in the `workloadSHA256` and `unavailableStateCounters` metadata representations and case 821’s qualified `errorClass` observer; full raw-JVM equality is not claimed. The existing comparator records exact case-definition equality. Full case 3 before/after states for all 64 graphs and each comparison are retained in the accompanying JSON.

All 12 cold archive artifacts were checked against recorded archive/decompressed hashes and external original bytes. Complete-fixture preflight and postrun verification receipts each report all 1,152 original files matched, with zero changed/missing/added files. This audit verifies those receipts and their provenance; it does not reread all graph files.

The full 2,575-entry source manifest equals the root source-audit map, broader focused manifest, and module portion of the 3,395-input full-check manifest. Compared with frozen v4, exactly `internal/query/main_fixed_workers.go` and `internal/query/main_fixed_workers_test.go` differ. Both current files were independently hashed. All captured compiled-input hashes match the frozen module manifest. Full-check context, module race, and vet receipts each record exit 0 and unchanged inputs; archived logs and receipts match external originals. This establishes manifest binding without independently rehashing the whole current/frozen filesystem again.

Cold runtime and verifier both exit 1. Besides the remaining state mismatch, the original main case 821 error is still present, so the original all-success gate remains false. Warm and startup-prepared were not executed. No P95 or other performance measurements were made.

## 201-case supplement

The separately completed `lazy-core-matrix201` capture has compile/capture exit 0 and comparison/controller exit 1. Its original 2,575-file source manifest equals the same full module. Source-after and compiled-source manifests each have 2,576 entries: every original entry is unchanged and the only addition is `internal/query/generic_disjunction_diagnostic_test.go` (SHA-256 `8e788981cbcd8a3ef96edd5bf3257cfa9eb4ebdc7dc0c2d6c6bb8fca031d4594`).

The comparison still matches 166/181 public cases and 19/20 provider-wrapper controls; all 16 known difference names/scopes/fields match the preceding scheduler matrix against both main references. Independently comparing every parsed native output field finds only six `goStack` differences from the previous matrix; every other field in all 201 cases is equal. Thirteen small archived artifacts were checked byte-for-byte against external originals. The binary/source tar and compressed fixture archives were not re-audited here. The legacy adapter does not pass the new ExecutionContext or compare its diagnostics, so this supplemental matrix is not full-route work-accounting acceptance.

Only this new audit JSON/Markdown were written. No Go/JVM runtime was launched and no source, tests, comparator, or previous evidence was modified.
