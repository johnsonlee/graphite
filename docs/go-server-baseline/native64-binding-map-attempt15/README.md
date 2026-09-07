# Attempt 15: reuse each DISTINCT scanner's projection bindings

Keep the allocation reduction; there is no meaningful latency improvement in
these observations. The repeated prefix query allocates 3,759,934,424 ->
444,259,376 bytes (88.18% less), but execute+marshal remains 9.892465 -> 9.800067
seconds and CPU remains 46.503897 -> 45.741571 seconds. This does not establish
P95, peak RSS, a GC pause reduction, or speedup relative to main.

## Change and source identity

Native base is `2ab90cdc11477166fcd84c63c1af03041bd7f940`. The provider patch
`a2c0457f6c732f136711b58b2f5a73622d072de7882cd1258e236cd8fde30ef1`
was authored on `9ada2bf1` and applies unchanged with the CDE functional fixes.
The only production change is `internal/query/generic_distinct.go`: a scanner
owns one projection binding map, updates its candidate, and clears the value on
every next exit, including cancellation and panic. Expression order, selected
alias evaluation, candidate freezing, fallback, source consumption and worker
joins remain. There is no global pool or shared map between scanners.

All 2402 root source/testdata inputs match the fully tested combined candidate.
The pre-existing ignored root executable is excluded. Provider206, independent
allocation diagnosis62 and integration79 files were rehashed before this freeze.
Their manifests and exact patch/source identities remain in the corresponding
subdirectories and `freeze-input-verification.json`.

## Correctness

Whole-module `go test -race ./... -count=1` and `go vet ./...` pass. New tests
exercise binding ownership, shadowing, aliases and cancellation. An independent
actual two-task cancellation/barrier test ran under race20 times, required both
tasks to enter, required cancellation and joining/clearing, then verified a fresh
wave. Its exact temporary file was removed after testing and retained as evidence.

All67 existing complete-response artifacts are unchanged. Three source-history
files retain45 speculative mappedView boolean differences; response data is not
masked. The original1048 corpus remains1016 matches/32 differences. These32 are
not the full product compatibility gap inventory. The author had1016 only after
combining CDE; its earlier9ada-based observation994/54 is retained in provider.

## Real persisted64 observations

The fixed five-query history uses all64 actual persisted graphs from
`/tmp/pr113-exp037-fixture.nXn4fg`:1152 files,10,338,207,518 bytes. Native base and
candidate use a separately verified COW clone. All five full responses equal the
pinned main responses, and source/binary/graph hashes are unchanged after Close.
`run-profile.py`, configs, commands, raw output, heap profiles and counter receipts
are preserved. Go1.22.0 darwin/arm64;16 logical CPUs;64 GB host. Forced GC is outside
request counters. Asynchronous CPU sampling is disabled; CPU uses getrusage.

| Query | Base seconds | Candidate seconds | Base CPU seconds | Candidate CPU seconds | Base allocated bytes | Candidate allocated bytes |
|---|---:|---:|---:|---:|---:|---:|
| wrapped-firstLastGraphBimodalClassPrefix | 13.965664 | 13.844310 | 69.276597 | 67.477911 | 9281813272 | 5966132840 |
| wrapped-firstLastGraphBimodalClassPrefix-repeat | 9.892465 | 9.800067 | 46.503897 | 45.741571 | 3759934424 | 444259376 |
| global-wide-wrapped-case-insensitive-distinct-dense | 0.313969 | 0.312444 | 2.107906 | 2.077883 | 268474248 | 268469520 |
| global-wide-wrapped-case-insensitive-distinct-dense-repeat | 0.309357 | 0.303839 | 2.031272 | 2.012126 | 268435840 | 268440568 |
| global-wide-distribution-broad-all-64 | 0.001809 | 0.001802 | 0.001842 | 0.001836 | 1636064 | 1635392 |

These are single sequential instrumented observations on a shared host, with
identical query history. Dense requests follow prefix index construction and are
not independent cold measurements. The prior generic CDE DISTINCT regression is
not resolved by this patch. Allocation removal alone did not remove prefix CPU
cost; further diagnosis is separate from this optimization.

## Shipping command verification

A clean helper-free module with150 compiler/embed inputs and2402 total module
inputs built binary SHA256
`b80b2cf4acac78adefd81dab786640ecaabc49323ba5c2928e3b0508e191816b`.
Two fresh actual HTTP command processes returned55/55 complete typed main bodies,
headers and64-graph catalogs (13 lazy queries and42 regressions). Default timeout60s
and admission capacity4 were retained. Both exited143 after SIGTERM, without
forced kill, and ports were released. Original and HTTP-clone1152 graph files
match after both exits with no extra graph files.

## Retained failures and acceptance limits

Provider setup initially omitted the explore OpenAPI source; the pinned source
was added. The initial cancellation test observed Err only and missed the A12
Done checkpoint; the corrected observer delegates a real cancelable context and
requires the trigger. The initial artifact collector missed nested histories;
all complete recursive histories were subsequently compared. Root initially
resolved the provider manifest relative to the wrong directory; the failure and
correct206-file parent-relative verification remain. No production semantics
were changed to hide those setup/test failures.

Keep this one allocation hypothesis in its own commit. Full compatibility,
paired repeated main-relative P95 acceptance and the final
benchmark-regression-gate remain outstanding.
