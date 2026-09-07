# Request-selected sources and execution-context policy

This functional follow-up fixes a prerequisite for running the complete 1,267
main64 cases. It does not execute that real64 benchmark or measure performance.

Main is pinned to `4e328b0109e13c896b74004823fb049fcb19251a`. The oracle uses
the actual main executor from its built Explore JAR, SHA256
`91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.
The exact JAR identity is also checked in `source-verification.json`; use that
receipt's authenticated digest when reproducing commands.

## Change and scope

`ExecuteCrossWithOptions` preserves whether the caller already selected graph
sources, including an explicit selection containing every graph. It separately
preserves the planner policies selected by main's execution-context constructor.
The existing plain `ExecuteCross` API keeps its original constructor semantics.
The HTTP selected-source path supplies the marker; root/allGraphs paths do not.
HTTP cross-graph queries enable the context policies. This does not claim that
main's work counters or finite execution-budget accounting are implemented.

The marker controls persisted-source preference for ordinary/residual string
queries and suppresses a second root source-pruning pass in streaming queries.
WHERE still evaluates its original graph predicate. Context-aware ordinary
scans with fewer than 40 sources remain serial, matching main. Streaming full
candidate scans now use main's retained-index policy rather than initializing
a mapped index view for split sources.

## Actual-main evidence

`SourceScopeOracle.java` is retained with the query testdata. It captures five
source counts (1, 2, 8, 40, 64), selected/unselected scope, clean/bad-last fixtures,
and eight query shapes, each executed twice with the actual main context:
160 scenarios and 320 complete responses plus before/after source states.
The tiny persisted fixtures are correctness data only.

The original Go source snapshot and baseline have **24 public-response
differences**. The candidate has **zero public-response differences**. All
deterministic source states remain exact. `verification.json` lists every raw
state difference separately; the complete records are never rewritten.

Main's ordered parallel consumer merges complete waves and, on a source error,
cancels and joins unfinished sibling tasks. Consequently a last-wave sibling
may or may not have initialized its retained index at cancellation. The actual
main `SourceScopeScheduleMatrixOracle` records 12 controls: counts 40/64, three
scope/query shapes, and inactive/active scheduling hooks. Active hooks delay the
seven final-wave clean sources before lookup until main itself interrupts them;
the bad source waits for those starts, then executes its original failing read.
Hooks never mutate caches, substitute results/errors for the bad source, or
initiate cancellation. All 12 responses retain the original decode failure;
active runs leave exactly those seven indexes uninitialized, join all seven
siblings, and leave the parent un-interrupted. No watchdog fires.

The Go scheduling test uses the same source boundary and the actual production
parallel consumer/storage functions. Its six forced schedules match main's
complete state exactly and prove sibling joining. Natural controls and the
320-response matrix assert: exact public response, exact initial state, no state
change between joined queries, no loss of retained indexes, no mapped views,
all prior waves retained, and the failing source retained. Only the final-wave
clean siblings can remain uninitialized. Negative controls reject changes to
errors, messages, rows, source IDs, prefix/failing-source state, mapped views,
initial state and repeat lifecycle. This is a scoped scheduling invariant,
not a blanket cache-field exclusion or a claim of distribution equivalence.

Six HTTP checks cover root, allGraphs and selected requests with an ordinary
query and a routed ORDER query. They assert the full body and source states,
including the selected-source later error. Full-module race tests and vet pass;
raw outputs are `full-race.log` and `vet.log`.

## Retained failures and reproduction

`initial-wrong-cwd-no-tests.log` did not execute a test and is not a pass.
`baseline-native.log` is the valid original-source failure. The initial and
later candidate strict comparisons preserve storage-policy and scheduling
differences. `candidate-schedule-tests.log` preserves the failed attempt to
treat inactive scheduling controls as deterministic. `candidate-verified.log`
records a relative output-path failure after the assertions ran; the corrected
absolute-path capture is `candidate-verified.json`. `query-http-tests.log`
retains an incomplete test expectation for the error body's `code` field; the
final full-module run uses the corrected complete error body.

Run `python3 docs/go-server-baseline/native-request-source-scope/verify.py`
from the repository root for independent comparison of the preserved captures.
Java command arrays and exit codes are in `commands.json` and
`schedule-matrix-commands.json`. Run `go test -race ./...` and `go vet ./...`
from `graphite-server` for the production tests.

Cold/warm/startup-prepared lifecycle operations and the complete real64 replay
remain unfinished. Per-case P95 and the 10x acceptance gate remain unproven.
