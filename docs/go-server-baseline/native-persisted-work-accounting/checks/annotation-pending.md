# Annotation top-level value-count correction — validation pending

The original and repeated 201-case Java captures report
`IndexOutOfBoundsException: Index (28) is greater than or equal to list size (21)`
for `annotation-bad-tail-hit`, `annotation-bad-tail-miss`, and
`annotation-bad-tail-global-miss`. The checked Enum candidate instead reports
`invalid collection count 2147483647 (104 bytes remain)` for all three. Those
failed captures remain unchanged; the last verified matrix still has 15 strict
differences.

Pinned main `NodeSerializer.kt:554–565` reads the three identifying strings and
the signed top-level count, then reads each key and annotation value before
inserting it into a mutable map. It does not reject the count against remaining
bytes or allocate count-sized storage. Annotation's public query path decodes
the node before filtering, so a predicate miss must not hide this error.

The pending workspace correction is restricted to the projection candidate's
Annotation decoding branch. The strict `decoder.node` / `Store.Node` contract
and nested value decoding remain separate. Store tests have been prepared in
`projection_annotation_test.go`. The existing public query regression test now
selects these three original observations (84 → 87 cases), checks their exact
error and empty failed result, and compares their original before/after
`retained` and `mappedView` states. These test changes have not been executed.
Independent source review found no blocking issue in the query test. Its
assertions do not cover qualified JVM class names, stack traces, or work-unit
diagnostics, and do not establish complete raw-oracle parity.

Independent Store review likewise found no blocking production issue. One
proposed test of a partial Node's map on failed decoding was removed because
Java returns no partial node; that assertion would constrain internal
construction rather than the observable contract. Three top-level Store tests
remain, with both candidate APIs covered. Formatting and scoped whitespace
checks pass, but compilation and runtime behavior remain unverified.

The partial three-file candidate snapshot and original measured versions are
preserved at
`/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-annotation-pending-v1`.
Its receipt records hashes and measurement isolation, not a full module freeze
or successful tests. The initial proposed test source remains separately
preserved under `/tmp/graphite-annotation-count-f0838dda-v1`.

## Measurement isolation

The live campaign is
`/Users/johnsonlee/.codex/benchmarks/graphite/p95-enum-f0838dda-200-v1`,
using build `p95-enum-f0838dda-build-v1`. Inspection of its `inputs.json` confirms
that no path under the workspace `graphite-server/` is a measurement input.
Its native source module is that build's separate frozen `source/` directory;
the binary and recorded input files remain untouched. The controller checks
those recorded inputs before and after each runtime.

Consequently, the current sampling results apply to the previously checked
Enum candidate, not the pending Annotation correction. No new compiler, test,
fixture replay, or benchmark workload may be run concurrently with this serial
campaign. Small source edits, formatting, and read-only review supply no runtime
correctness or performance evidence.

## Required follow-through

After the live campaign becomes terminal, retain the original failing controls
and run the new Store controls against both the old and corrected production
source. Run the three public cases and neighboring projection/cancellation/
Close tests, then the full module race and vet checks. Replay the complete
201-case matrix and the real 64-graph workload from a newly frozen, checked
candidate; preserve every comparison difference and original case 821 failure.
Do not report three gaps resolved, full functional parity, or a latency gain
until the appropriate new runtime evidence exists. The current 200-pair
campaign's observations must never be attributed to the changed workspace.

## Measurement termination and validation handoff

The Enum-candidate campaign was subsequently stopped deliberately after early
samples and source inspection identified a missing exact-match prefilter in the
Go parallel raw DISTINCT path. This was not a timeout or missing observation.
Session 68471 returned exit 130; controller PID 42719 and all 25 owned runtime
processes were verified terminal. Twelve complete pairs and the unpaired fifth
startup-prepared Go run remain preserved. The stop decision and terminal proof
are in `p95-enum-f0838dda-stop-decision-v1` under the external benchmark root.
The minimum 200 samples per case/state remains the final acceptance criterion.

The focused Annotation negative/candidate and original public-case checks can
now run. The three source files remain frozen at this document's pending
snapshot; no additional optimization has been mixed into them. Test outcomes
must be recorded separately when their processes finish.

## Focused runtime results

The focused runner is now terminal with the expected baseline failure and
successful candidate/public steps. Original production supplied by Go overlay
still rejects the actual bad-tail count for both APIs and rejects the negative
count controls. With the candidate, all ten selected Store top-level tests
pass three race-enabled repetitions (30 passes), including the three new
Annotation tests and neighboring Enum/context/Close tests. The three original
public Annotation cases pass exact error and before/after state checks. The
source hashes are unchanged before/after; no production fix was made in
response to these tests.

The 15 original evidence files and manifest are archived under
`checks/annotation-focused-evidence`, with the old production bytes retained.
Original output is
`/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-annotation-focused-v1`.
Manifest SHA256:
`cb962f266c8eb2a665f962319c3e17a3926b811d50389000a6798d0bcccb53f4`.
This is focused runtime evidence only. Full-module checks are running separately
in `persisted-work-f0838dda-annotation-fullchecks-v1` (session 28633); the complete
201-case and real64 replays are not yet claimed.

## Full module and matrix results

Full-check session 28633 is terminal with context, race and vet exit 0. All
3,397 recorded inputs remain unchanged. The complete 2,577-file module is frozen
at `persisted-work-f0838dda-annotation-source-v1/module`, with its manifest bound
to full-check receipt SHA256
`7bcb300e16fdaed6a58a5fcd04894a826909e2606e6fa21e6a698208f3ff5277`.
Relative to the checked Enum source, only the two changed production/query-test
files and new Annotation test file differ. The six full-check artifacts are
archived under `checks/annotation-fullchecks`.

The complete 201-case runner (session 35080) compiles and captures successfully,
then exits 1 for 12 remaining strict differences against both original and
repeated main. It now matches 170/181 public cases and 19/20 provider-wrapper
controls. Compared with the previous Enum capture, only the three
`annotation-bad-tail-*` projected records change; all three now report the
original SID 28/list 21 error and state. Original fixture bytes are unchanged;
the same 22 generated index sidecars are recorded. The complete evidence and
changed-case comparison are archived under `checks/annotation-matrix201`.
The original comparator, provider boundary caveat and unavailable diagnostics
comparison remain unchanged.

The real64 three-state correctness runner is now active as session 56260, using
the checked freeze and a new copy at `annotation-real64-source-v1`. It has not
yet supplied terminal three-state evidence. This is not a performance run.

## Real64 cold comparison failure retained

Session 56260 is terminal with comparison exit 1. Cold executes all 1,267
original cases and preserves the original case 821 failure; all declared public
fields match. Its 162,304 state observations contain 2,527 `mappedRangeCount`
differences, beginning at `replay/3/after`, `fixture-android-08`: main 4, Go 0.
All 1,152 original fixture files and the frozen source remain unchanged. The
qualified JVM error-class observer is still unavailable in Go, separately
recorded. The driver stops after this failure; warm and startup-prepared are
not executed and must not be reported as passing.

All 12 files from the terminal cold runtime, including failed verification,
are archived under `checks/annotation-real64-cold`. This is the same first
graph/case and counter discrepancy as the earlier lazy-core capture. That
observation does not prove the Annotation change caused it, nor remove the
failure. No comparator change, retry-until-pass, synthetic cache publication or
scheduler barrier has been introduced. Full real64 state parity remains
unproven despite the independently verified Annotation matrix correction.
