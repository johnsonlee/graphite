# General Cypher candidate routing and read order

Functional follow-up to Go `4ac9dd8b5f5fcfaf9ec920d7f7d381faedb5fe88`, compared
with original main `4e328b0109e13c896b74004823fb049fcb19251a`. This is correctness
evidence, with no latency or P95 measurement.

Unknown node labels now dispatch to the general pipeline before all fast paths.
Any Method label chooses metadata candidates; otherwise the first label chooses
the node class before existing bindings are checked. Fresh mapped typed candidates
preserve main's erased generic cast, while multiple labels, bindings and seeks
retain their explicit runtime class checks. General reads use the node-offset
capacity and missing-value sentinel, without imposing the validation API's extra
index ID/tag consistency check.

General MATCH now materializes patterns across all input rows before WHERE.
OPTIONAL MATCH materializes its candidates per input row before filtering and
adding null bindings. This removes the former general scanner's candidate pruning
and interleaved predicate evaluation, which could hide or reorder read errors.
Positive early limits retain their existing lazy traversal. Literal elementId seeks
run before that limit handling and before resolving the sought node's label;
parameters, nested AND and optional predicates do not broaden seek admission.

## Independent original-main controls

`oracle-readme.md` describes 140 cases, run twice in the actual pinned JVM.
`general-where-readme.md` describes eight additional actual-main read-order cases,
also repeated. The 296 main calls agree on complete public results, value types,
error class/message, phase and source state. Original 120 and six-case controls
remain archived; cloned fixtures and all mutations have explicit source-derived
positions and hash manifests. These small fixtures establish semantics only.

`native-final148-receipt.json` records 148 primary native cases plus 81 independent
single-source calls with the main context's work-tracking option. The old complete
Go production overlay has 60 routing and two WHERE case differences (plus 36 and
two auxiliary differences); the final candidate has none. Actual single-source
primary calls use Execute. Type comparisons retain raw JVM/Go types and explicitly
normalize Java/Go map/list representations; no budget or metrics equivalence is
inferred from these observations.

Initial before/after120 observations are retained. The intermediate 140 candidate
is explicitly marked non-final because source edits could have overlapped its
build/run. The final 148 test run records unchanged hashes for every Go source file.

## Validation

Full-module `go test -race -count=1 ./...` and `go vet ./...` pass. All 2,595
recorded module/oracle inputs are unchanged. Source-constructor coverage remains
298 strict cases; filtered COUNT now compares all 388 public observations without
the previous unknown-label exception. The independently observed canceled sibling
retained-state scheduling allowance remains explicitly bounded in that test.

The real64 replay ran from a separate immutable 2,523-file module copy using
`run-real64.py`. Cold and startup-prepared replay plus warm prewarm each match all
1,267 original case definitions, 1,266 successful results and the same original
error. Seven state fields match across 486,848 graph observations; all 1,152
original files are unchanged per runtime. The original all-success gate exits 1;
all three full comparators exit 0. Warm formal replay remains unavailable. Final
results are recorded in `real64/` and `final-audit.json`.
Original driver filename prefixes are inherited from the Attempt21 capture tool;
the unique run suffix and frozen module identify this candidate.

## Remaining scope

This does not establish full engine/server compatibility or 10x P95. Mapped
supertype iteration still has the documented JVM Class identity order issue;
Go's typed ID lists derive from nodeindex, rather than honoring an intentionally
different persisted typeindex. Non-CallSite malformed collection/truncation
semantics, full work accounting/configured workers, the remaining JVM-backed CLI
paths and required PR benchmark gates also remain open. The original real64
failed query still prevents formal warm replay. Performance acceptance requires
repeated per-case measurements on the same real64 data and separately specified
runtime states; individual old pilot observations are not attributed to this fix.
