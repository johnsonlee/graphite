# Bounded generic-route regression test development

`generic_disjunction_test.go` reads original Java expected results and creates a
fresh copied persisted fixture for every source. Its final selection contains 83
public executor observations: original real64 query shapes on the small fixture,
positive generic hits and provenance, Enum/Local/Field raw SID hit/miss faults,
raw missing/negative/wrapped offsets, payload kind/ID behavior, and cross-source
LIMIT consumption with source scope on/off. It asserts complete ordered columns
and rows, exact simple error class and nullable Java message, and atomic failure.
The fixture is correctness-only; no numbers here are performance evidence.

The initial 75-case probe failed. The full returned output is preserved in
`query-unit-initial-probe.txt`. One test-harness bug compared `JavaMessage()`'s
nil/string value with a `*string` (including typed nil), producing false failures.
The correction dereferences a non-nil expected pointer and preserves actual nil.

Four observations also expose separate unresolved production differences:
`enum-bad-tail-hit` rejects an oversized collection count with the Go-specific
error, and the three `field-bad-tail-*` cases fail Go graph loading before query
execution. Those require decoder/loader corrections outside this raw string
filter change. They remain in the complete 201-case replay and are not asserted
to match. The bounded unit selection explicitly excludes these four; it does not
claim that all 201 scenarios or all server functionality now match main.

The corrected 71-case probe exited0, query package0.567s (session32319). Twelve
additional Enum/Local bad-SID hit/miss/global-miss controls brought the final
selection to83, so every one of the nine global-miss corrections reported by the
complete candidate comparison has a regression assertion. Unknown nested-value,
Unicode parameter and generic empty-coalesce fallback gaps stay visible in the
full replay; this test does not reclassify or normalize them.

Command from `graphite-server`:

```
/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go test ./internal/query -run '^TestGenericDisjunctionMainReadSemantics$' -count=1
```

The root agent owns complete module race tests, vet, real64 replay and timing.
