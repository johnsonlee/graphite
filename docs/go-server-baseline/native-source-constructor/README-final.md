# Public source construction and empty graph namespaces

Go previously rejected empty graph IDs, combined invalid-store and duplicate-ID
errors, and checked cancellation before parsing. Main accepts one empty ID and
separates source construction, namespace validation, parsing and cancellation.
This functional change restores that public order and removes the six duplicate
source-ID exceptions from the existing filtered COUNT oracle test.

The native base is `f5b05027dfcb0883fcfd1f8545123bbaa91af071`; pinned main remains
`4e328b0109e13c896b74004823fb049fcb19251a`. This is a correctness follow-up, not a
new performance optimization attempt. It makes no latency, CPU, memory or P95
claim from small fixtures.

## Behavior

All nil stores are rejected as main's non-null `CypherGraph` constructor rejects
a null graph, before pipeline-wide duplicate-ID validation. Duplicate IDs return
`IllegalArgumentException: Graph ids must be unique`, including two empty IDs.
One empty ID is valid, and different IDs can refer to the same Store.

Qualified nodes, methods, relationships and paths now identify their namespace
by value type or an explicit qualification flag, preserving `graphId: ""` and
metadata `graphIds: [""]`. Single-graph unqualified values retain null graphId
semantics. Empty-ID Method properties/keys include graphId. Existing materialized
node, edge and path identity representations remain in use. Actual main controls
cover empty/nonempty namespaces sharing local IDs, bound relationships, DISTINCT
relationship counts, zero-hop paths and an `elementId(n) = ':0'` lookup.

The public executor completes DSL parsing before checking query cancellation,
as main does. `cypher.ParseContext` itself is unchanged and remains available to
parser API callers. Ordinary cancellation becomes
`CypherQueryCancelledException: Cypher query cancelled` at the public query
boundary; internal cancellation checks still use the Go context sentinel.
`errors.Is(err, context.Canceled)` remains true. An explicitly supplied typed
Cypher cancellation/timeout cause preserves its class/message and error identity
without mutating the caller's cause. An ordinary `DeadlineExceeded` remains a Go
deadline error: the context alone cannot supply main's timeout-millisecond value.
No timeout duration is inferred or invented.

## Evidence

The actual main oracle has 298 scenarios and a fresh repeated run (596 calls).
Both runs agree on complete public values, numeric types, errors, their phase,
and physical-store state. Each preserves all 5,836 original fixture files; 152
generated sidecars per run are explicitly audited. Source/JAR/helper/class hashes
and exact commands are in `oracle-inputs.json`, `commands.json` and
`oracle-receipt.json`; `oracle-manifest.json` covers 40 oracle artifacts.

The independent old-source overlay has 248 differing cases out of 298. The
candidate has zero differences in full rows/columns, errors/messages, scalar
types, container kinds and before/after store state. JVM Map/List implementation
class names do not have Go equivalents; both raw type observations remain in
the records, with full container contents checked and numeric classes kept
distinct. No cancellation output is silently renamed by the test harness.

The initial 264-case capture/control and the original 12 fixture-preparation
failures are retained. Two native pre-test failures from a leftover unused import
are also retained. The final baseline overlay alone uses `-vet=off`, because it
combines an old production snapshot with a new oracle test; this is a failing
control, not candidate lint evidence.

Final candidate `go test -race -count=1 ./...` and `go vet ./...` both pass. All
2,521 recorded module/oracle inputs remain unchanged across these commands.
The existing filtered COUNT corpus now matches 386 of 388 public observations,
up from 380. Its only two remaining differences are repeated observations of
the separate generic malformed unknown-label case, explicitly retained in
`tests/filtered-count-differences.json`.

`run-real64.py` uses the existing full-case capture/comparator with an immutable
module copy, separate copies of the original 64 real persisted graphs, and all
1,267 original ordered cases. Results and all seven observed index/cache fields
must agree before this change is retained. These observer captures contain no
performance measurements. The original main error remains in the workload;
matching it does not make the all-success gate pass or unblock formal warm timing.

Complete server fidelity, generic unknown-label routing, full work/metrics policy,
the original reference failure, repeated per-case P95/10x and required PR benchmark
checks remain outstanding. Raw typed Edge/Path parameter qualification is another
known independent boundary; changing graph-ID admission does not establish it.

## Final real64 result

| State/phase | Cases | Matching successes | Original errors | Matching graph-state observations |
| --- | ---: | ---: | ---: | ---: |
| Cold replay | 1,267 | 1,266 | 1 | 162,304 |
| Warm prewarm only | 1,267 | 1,266 | 1 | 162,240 |
| Startup-prepared replay | 1,267 | 1,266 | 1 | 162,304 |

All three runtime processes are terminal with exit 1 at the original all-success
gate; each independent comparator exits 0. The seven state fields match across
486,848 observations, and all 1,152 original graph files remain unchanged per
runtime. Warm never enters formal replay because the original main failure occurs
in prewarm. The observer's fully qualified `errorClass` remains an explicit
main/native difference; simple exception class and message match.

Keep this functional alignment. `audit-final.py` verifies the immutable 2,519-file
module copy, final module/oracle test inputs, original Go control overlay, actual
main artifacts and final complete real64 comparisons. `capture-archives.json`
records byte-exact compressed streams; ignored raw copies remain locally.
`evidence-manifest.json` identifies the committed source and evidence together.
This verification establishes this public-entry correction and the tested corpus,
not 100% engine/server equivalence or any P95 improvement.
