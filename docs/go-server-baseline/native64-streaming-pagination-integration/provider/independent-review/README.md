# Independent review: B streaming pagination / ORDER

Verdict: the reviewed final three production files have no remaining blocker found in this bounded review. An initial cancellation defect was reproduced independently and repaired by the author before this verdict. This is a finite review, not a proof of complete Cypher compatibility. No root or author worktree was changed, no commit was created, and no Java/64-graph/performance workload was run. Existing pinned-main source and full tiny oracle captures were used.

Author workspace: `/tmp/graphite-go-streaming-pagination-87aaf0ad`. Effective staged base tree: `faf2bee8fe4fc845442cf775f212cd70b0a96bbd` (frozen main-source plus CDE). Final author production manifest SHA256: `6ceffad6c8bf19f3d93f6db878d20a37c71e727d8c10eaa40435abd3342a2389`. `final-production.json` verifies all three hashes. `final-source/` copies the exact engine hook and two new consumers. The author retains the initial manifest separately; this review also retains the initial source bytes and initial hashes. Store, tuple and source-provider changes are not part of B. Root's later integration must preserve its independently integrated prerequisites.

## Concrete defect and fix

Initial `streamingRowHeap.compare` and `result` lacked context checks. `streamingOrderedRows` checked immediately before final sorting, but not during or after it. In the independent copy, a test-only observer at the comparator entry notified the test goroutine after the source was exhausted and final sorting began. The test goroutine called standard `context.WithCancel`'s cancel function, and released the real sort. With just three owned CallSite values, the initial consumer returned successful rows `[1,2,3]` after cancellation; `cancel-before.log` preserves this failure.

The observer does not supply a false context, implement cancellation, alter comparisons or replace values. It only permits deterministic scheduling at the actual comparator boundary. The source and test overlay are preserved. Main QueryPipeline's ranked comparator polls tracked cancellation at comparisons 0, 1024, etc.; main materialization additionally checks at the first output row and after materializing. Author repair adds heap-owner-local check/counter fields, first/every-1024 comparison checks, and a post-sort owner check. A final check is necessary for the three-row case, which may finish before another cadence boundary. The same independent counterexample passes under race ten times after repair (`cancel-after.log`). This establishes actual cancellation/ownership behavior, not exact equality of Java GraphWork polling counts across APIs.

A separate initial suspicion about signed encounter ordering was rejected after reading main. Java QueryPipeline uses the same `(sourceIndex.toLong() shl 56) + localOrder++` expression. Go must preserve its signed overflow behavior; it must not replace it with a supposedly cleaner ordering. No 128-source workload was needed to establish that source equivalence.

## Source and lifecycle review

| Boundary | Evidence and result |
| --- | --- |
| SKIP before ordinary projection | Q3311–3411 increments matching rows before projection; Go skips matched bindings before streamingProject. Throwing skipped projection and ignored nonliteral SKIP controls match. |
| DISTINCT before dropping SKIP | Main retains skip+limit distinct rows; Go retains the same bound and drops only at completion. Scoped stop and qualified full provenance consumption stay separate. |
| DISTINCT ORDER duplicate | Q3574–3587 projects each match before checking retained identity; an existing retained row merges provenance and skips its ORDER evaluation. Go does the same. |
| Eviction | Q3593–3606 removes the evicted visible identity. Go removes the same key, so a later reappearance reevaluates ORDER and can throw. No permanent all-seen set hides that error. |
| Projection/order scope | Both project all aliases, then overlay projected values on original bindings for ORDER. Overridden aliases and original node property ordering have complete oracle cases. |
| Heap capacity/error | Serial main uses default small initial heap capacity; parallel direct ORDER constructs retainedCount capacity before source tasks. Native hard VM-array limit is confined to that branch. Environment-dependent OOM/global budget remains outside the model. |
| Lazy node consumption | Node source is advanced on demand; full decode, runtime acceptance and WHERE remain before projection. SKIP does not consume projected rows. Generic node-only main ignores relationship-only finalResultPredicate, so WHERE is not spuriously doubled. |
| Scope/source count | Scoped catalog selection precedes factories. Parallel workers own one graph but pass the full selected catalog count into the provider, preserving its source-count-dependent choice. |
| EAGER strategy | Main nonempty supported EAGER types have no retained serial preference capability, so B permits workers. This is separate from generic DISTINCT's admission. |
| Tasks | Per-worker evaluator owns rowOrders=nil and a private regex cache; heap and comparison counters are worker-local. Existing runDistinctTasks joins the full wave before result merge, later waves or Close. Source/request/projection failure tests check exact causes and no late worker. |
| Ownership/cache | Full nodes and frozen nested result values remain owned after Close. Partial or canceled source iteration does not publish an exhaustion-only cached ID list; fresh execution can complete and publish. |
| Unknown labels | General empty-source suffix evaluation is preserved rather than unconditionally returning before invalid SKIP expressions. Main/Go 24-case results match. |

## Independent verification

After the repaired cancellation counterexample passed, the observer was removed. The exact final production bytes then passed `go test -race ./internal/query -run '^TestStreaming' -count=1` and `go vet ./internal/query`. The observer version's same suite also passed, and both raw output sets compare identically. `verify.py` checks the final production hashes, output inventory, full main responses and ordered source traces, rather than relying only on test exit status.

There are 595 complete response observations: 53 design/source-trace records (including four deliberately declined control executions), 480 actual-main corpus records, 24 unknown-label records, and 38 responses in 28 source histories. Arrays, order, error class/message/null, metadata and response fields all match at JSON-value semantics. `wire-layer-audit.json` finds no semantic-versus-wire changes in these B oracle pairs. The test source/oracle copies are retained to show the complete comparisons.

Raw JSON is not byte-equal: the extra strict Python int/float audit found 166 spelling distinctions (8 result-row values, 98 parameter-metadata values, 60 source-spec values), such as main `0.0` versus native `0`, or metadata `1.0` versus `1`. These share the JSON number type/value; strings, booleans and nulls are never coerced. `numeric-spelling-differences.json` preserves every such path and value. This matches the existing Go corpus helper's JSON-decoded value comparison and is not claimed as lexical fidelity. B does not edit numeric evaluation or serialization. The original strict Python-type comparator is also retained; its first failure was a representation comparison, not a production test failure.

The author's original1048 result is reported as 1044 with only four separately documented MAPPED Class-identity-order differences; this bounded independent review did not replace that full corpus evidence or rerun the whole module. It independently verified B's 595 response/trace observations, cancellation counterexample, final hashes and lifecycle suite. Root will perform full combined integration checks. No sorting normalization or discarded error case is used to claim the remaining four are fixed.

## Reproduction and evidence

The isolated copied module is `module/`; its final three production files match the author manifest exactly. `review_b_cancel_test.go.txt` and `comparator-overlay.go.txt` retain the deterministic observer test. To replay the counterexample, apply only the observer insertion to the chosen initial/final streaming_order source and restore the test file, then run `go test -race ./internal/query -run '^TestReviewBFinalSortCancellation$' -count=10 -v`. Do not include that hook in production. `commands.json`, all before/after logs, exact-source logs, main source, main oracle, and native output files are sealed by `manifest.json`. The expanded module is excluded from the manifest; exact changed source, tests and oracles are included separately.
