# Independent context checkpoint review

Verdict: no blocking defect found within this bounded review. Candidate remains eligible for a separate real-data experiment; this document makes no performance claim or complete scheduling-equivalence claim.

Reviewed frozen provider `/tmp/graphite-go-context-check-87aaf0ad/context-check-review`, patch SHA256 `734d415a8b13f638f7941b6e7bb1d09e33d9bdc4a85c6e864c5bdbd26963951c`, manifest SHA256 `db1ccda3ca16fd159af8f89b61d54b161bde2f8a22eea517939e351351407895`. All 56 manifest entries verified twice. An independent detached worktree at `/tmp/graphite-context-check-independent-87aaf0ad` starts from `87aaf0ad39c89189d52c859672e5420b26c3f5e9`, applies only that patch plus the two tests copied here. Its ten candidate source hashes match the provider exactly. No root/provider mutation, commit, 64-graph process or benchmark.

## Production reasoning

The sole production hunk is `graphite-server/internal/query/eval.go:44`: a nonblocking select on the **current** `e.ctx.Done()`, then the original Err check and panic when closed. It changes no checkpoint locations, fields, channel caches, error/cause selection, Store or regex implementation, context assignment, task launch/consumption policy, close behavior, or joins.

Go 1.22 Context's documented invariant is explicit: Done not yet closed means Err nil; Done closed means a nonnil stable Err. Its successive Done calls return the same channel. The exact locally installed standard-library source is preserved in `go1.22-context.go.txt`, lines 68–112. The cancelCtx implementation assigns its error and closes its channel under its mutex (536–574), while Err reads under that mutex (453–458). The new select observes cancellation through that published channel and then retains the actual Err. WithCancelCause remains context.Canceled, rather than the custom Cause; WithoutCancel has nil Done and nil Err. Expired deadlines remain DeadlineExceeded.

The old tests' Err-only injectors deliberately inherited Background's nil Done, violating that invariant once they returned Canceled. Preserving their invalid behavior is not an API requirement. The new helper still injects deterministically but cancels a real WithCancel and reports its actual stable Done/Err; both Done and Err observations count so unchanged downstream checkpoints remain exercised. No production wrapper was introduced.

Cancellation overlapping a check can be observed at a different instant than the old mutex read. Neither version fixes the scheduler's winner for such a race; this patch must not be described as identical cancellation timing. Nil evaluator contexts were already invalid and panicked in both versions. Custom contexts violating the documented Done/Err invariant are outside this approval.

## Eight migrated test files

| File | Independent assessment |
| --- | --- |
| candidate_slot_test.go | Only constructor changes; threshold 9, cancellation panic, candidate scratch cleanup and row-order assertions stay. |
| distinct_string_id_integration_test.go | The third in-search check still must cancel at exactly 3; now also asserts underlying Done closed. Empty-table/no-check behavior is retained. |
| early_match_trace_test.go | Scalar observer moves from Err to Done at the same actual scalar entry; main's two stages early/projected, row ID and count assertions stay. New mutex protects appended observations; synchronous Execute return precedes their read. Traversal still cancels at 30. |
| functions_test.go | Only helper constructor changes; each collection function still must panic Canceled inside its threshold-12 execution. |
| string_candidates_test.go | Preparation counter/each-threshold enumeration retained, including minimum 100 exercised cancellations; existing tolerance for a threshold not reached because of map sort encounter order predates the patch. |
| string_predicate_test.go | All six operators still cancel at exact fast-path boundary 3/4 and UTF16 fallback boundary 6. |
| traversal_test.go | Helper now closes a real context; variable traversal still must cancel at 30. Its observation mutex makes methods concurrent-safe; test counters are inspected only after synchronous completion in these callers. |
| trigram_candidates_test.go | Full reached-check enumeration retained; original variable-count allowance unchanged and at least one actual cancellation required. |

No old test was removed, skipped, or changed to accept successful execution in place of cancellation. A purely mechanical checkpoint count is not the only evidence: added component-specific tests distinguish query Done calls from real Store and regex Err calls, and independently exercise task completion.

## Context rebinding, mixed components and lifecycle

The four worker-local context assignments in indexed_distinct.go remain unchanged (118, 160, 191, 823); each evaluator check reads its local field again. The provider test explicitly copies an evaluator, binds a canceled child, and proves its live parent remains usable. The independent overlay additionally alternates 32 fresh live/canceled children on the same evaluator, restores the live parent each time, then checks a real elapsed deadline and its WithoutCancel wrapper.

Provider mixed tests call real range, CandidateNode and cached backtracking regex code. They assert respectively Done/Err counts 3/0, 1/2 and 1/79; fresh uncanceled Store and regex work succeeds after interruption. This establishes that moving the evaluator access did not remove either component's existing Err checkpoints. The Close test distinguishes a live request's ErrStoreClosed from cancellation already observed before a closed Store access. Existing Store concurrent-close/publication and HTTP guard shutdown/connection tests remain intact in the full race run.

Ordered and unordered provider tests call the real runDistinctTasks, block a worker on the actual child Done, and require evaluator.check to panic after the child closes. Started worker completion counters must reach two before returning. The older integration tests retain three-worker prefix/speculative-failure and required-failure join coverage. Channel synchronization and final counters substantiate joins, rather than elapsed-time guesses. No assertion equates arbitrary concurrent error arrival order.

The independent external-cancellation test uses 16 goroutines and standard WithCancelCause/WithValue only: all finish their live checks before the test cancels externally; every subsequent check must panic Canceled, and the Cause remains unchanged. No Err/Done override can make this pass by advancing an injection counter.

## Verification and reproduction

From the independent worktree:

```
git apply /tmp/graphite-go-context-check-87aaf0ad/context-check-review/source.patch
# Copy context_check_independent_review_test.go from this receipt directory
# to graphite-server/internal/query before running the commands below.
go -C graphite-server test -race ./... -count=1
go -C graphite-server test -race ./internal/query -run 'Test(IndependentContext|Evaluator|CandidateCancellation|VariableTraversalCancellation|EarlyMatchTraversalCancellation|EarlyMatchScalarEvaluationCount|StringPredicateCancellation|CollectionFunctionsCancel|StringCandidateCancellationAtEvery|TrigramPreparationCancellation|DistinctStringIDCancellation|DistinctIntegration)' -count=20 -timeout=120s
go -C graphite-server vet ./internal/query
git diff --check
```

All completed with exit 0. Logs and source hashes are preserved here. The full race run was already started before the parent reported its duplicate full verification; after that report no further full-suite run was started. The bounded repeated test run adds the external-cancel/rebind cases and stresses migrated assertion stability. Ordinary test duration lines in logs are not performance evidence.

Provider's 17 complete base/candidate oracle JSON artifacts were hash-verified; I did not rerun or relabel them as independent JVM captures. Existing baseline parity gaps remain unchanged and are not resolved by this checkpoint change. The parent independently regenerated those 17 outputs separately. No corrective production patch is proposed.
