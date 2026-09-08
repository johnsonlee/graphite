# Native serial raw work batches

Baseline Go: `cf920cd4e84eafe7d3c641fe747bb0b1d14da16a`. Actual main:
`4e328b0109e13c896b74004823fb049fcb19251a`. This is a functional correction;
no latency, CPU or allocation measurements are attributed to it.

The native query buffer submits 1,024 inspected nodes at a time. Pending work
is cleared before submission, so a throwing submission is not repeated by
the deferred flush. Empty flushes neither submit zero work nor poll cancellation.
The shared request context receives pending work even if an iterator's local
worker context was cancelled after early stopping.

Mapped non-Annotation direct-string candidates charge before reading raw fields,
and flush before yielding a decoded node. Serial raw CallSite candidates flush
before their outer canonical-order read. Deferred flushing covers exhaustion
and decoding failures, preserving main's budget-error override when finally
submits pending work. Match-state initialization and unsuccessful next-ID reads
do not incur a node charge. Annotation and eager fallback candidates instead
charge once after successful decoding and before predicate evaluation.

Successful ordinary bounded string projections and lazy filtered dispatches
now produce their corresponding planner counters after evaluation completes.
Failed dispatches do not report successful fast paths.

The actual JVM oracle and original repeat variability are documented in
`README.md`. Two original-config CallSite errors have variable nullable messages
and omitted stacks. Diagnostic-flag captures explain this behavior but do not
replace the original-config observations. Synthetic persisted graphs are used
only for correctness and error-order verification.

## Verification

The new 43 serial-raw scenarios (57 operations), 16 Method admission scenarios,
8 applicable actual-buffer controls, and the existing 119 JVM scenarios pass.
Two native budget/cancellation controls and an empty/single/parallel source-loop
control supplement the captured observations. Full module race tests and vet
pass, with all 2,775 recorded inputs unchanged during the final checks.

Independent review found that Method precedes the generic filtered-empty guard.
The dedicated helper now preserves numeric-only count admission, inline property
validation, and no-scan Method successes before recording the ordinary fast path.
Declined Method requests retain general evaluation rather than the generic empty
shortcut. Genuine ordered, DISTINCT or parallel Method scanning remains outside
this helper. The 16 original-JVM cases repeat completely and use a fixture with
no methods; they prove admission and counters, not nonempty Method execution.
A second source review corrected the empty-source wave boundary before final
checks. Graph-source selection counters remain a separate unfinished integration.

The initial real64 replay was interrupted after cold completed because review
found the Method classification issue. Its partial warm capture and the two
intermediate module-check runs are retained under checks/ and external paths.
The final frozen module completes all three real64 captures with no new compared
public outcome or graph-state differences:

| State | Original cases | Matching graph-state observations |
| --- | ---: | ---: |
| cold | 1,267 | 162,304 |
| warm prewarm | 1,267 | 162,240 |
| startup-prepared | 1,267 | 162,304 |

The total is 486,848 graph-state observations. Every runtime retains all 1,152
original files and the original case order. Case 821 still raises the original
IllegalStateException, so all original all-success gates still exit 1. Formal
warm never reaches its prepared measured invocation. The fully qualified JVM
exception-class observer remains unavailable in Go; public simple class/message
comparison does not establish complete observer parity or schedule invariance.

The complete existing 201-case adversarial replay still matches 166/181 public
cases and 19/20 provider controls. All 16 original differences remain, with no
new compared result/error/state differences from cf920cd4. The historical
adapter does not pass the new ExecutionContext or compare its diagnostics;
its raw wording about a missing diagnostics API remains historical, not a claim
about the presence of the current primitives. Complete route accounting remains
unfinished.

`checks/source-verification.json` binds all 2,545 module files to final checks,
the frozen real64 module and the matrix source before its diagnostic helper.
`checks/` and `real64/` retain byte-verified archives, original failures and the
intermediate/interrupted attempts. No earlier n=1 timings describe this code.

## Remaining acceptance scope

This does not complete raw leading-projection probes, parallel ranges, indexed
lookup/build/cache accounting, relationship/path work, all planner diagnostics,
or request-wide server integration. The existing adversarial semantic gaps,
original case 821 error, formal warm setup failure, required benchmark gates
and per-case 10x P95 acceptance remain open.
