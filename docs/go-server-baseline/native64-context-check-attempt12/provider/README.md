# Independent evaluator Context checkpoint candidate

Base: `87aaf0ad` (`identity.json` has the complete commit ID). Worktree: `/tmp/graphite-go-context-check-87aaf0ad`. No commit, root-worktree edit, graph64 runtime, benchmark, or performance claim was made. This is one correctness-reviewed candidate; optimization numbering and any real-data measurement remain the parent's responsibility.

## Production scope

The only production file changed is `graphite-server/internal/query/eval.go`. The original evaluator.check now selects the current `e.ctx.Done()` nonblocking, and on closure retains `if err := e.ctx.Err(); err != nil { panic(err) }`. Checkpoint locations, evaluator fields, context assignments, store methods, worker/task policy, source ordering, cleanup/Close and error handling are unchanged. No channel caching, Cause substitution, wrapper, pool or concurrency adjustment.

This follows the frozen read-only audit at `/tmp/graphite-context-check-audit` (manifest SHA256 `fcced2f731acaf2e599a1098e0fd0796c137e38aee26788806993281b729abea`). Go1.22's cancelCtx.Err mutex and stable Done atomic-load mechanisms motivate a separate hypothesis; this candidate has no measurement establishing benefit. Store projection methods retain their own Err calls and lifetime locks.

Cancellation already observed at an existing checkpoint still wins over the following operation. Existing errors thrown before the next checkpoint keep their original path. Concurrent cancellation overlapping a checkpoint may be observed at a different instant than a mutex-based Err call; neither the old nor new implementation promises an identical scheduling winner. No changes to worker error precedence or the HTTP guard's Cause-based result selection are bundled here.

## Test migration and retained evidence

The old `traversalCancelContext` and `findIDCancelPoll` returned Canceled after N Err calls while inheriting nil Done from Background. They were deterministic test injectors, but those cancellation results violate Context's documented Done/Err invariant. Their original source is retained in `base-traversal-context.go.txt` and `base-findid-context.go.txt`; `base-eval.go.txt` records the original production method.

The common query test observer now owns a real WithCancel context and returns its stable Done and real Err state. Observations in both Done and Err can trigger cancellation, and do not advance the count again after cancellation. Its mutex makes the observer methods safe for simultaneous invocation; mutation exists only in tests. This preserves coverage of new evaluator Done checkpoints **and unchanged Store/regex Err checkpoints**, rather than deleting or skipping the mixed cancellation tests. Constructor cleanup releases the real cancel context.

All existing traversal/early traversal, string predicate entry/exit and UTF16 fallback, function collection, candidate scratch cleanup, candidate/trigram preparation enumeration and findId third-check assertions remain. The findId test additionally verifies the underlying Done actually closed. The existing standard already-canceled empty-table lookup still performs no check and returns -1.

The scalar-call observer now inspects the Done access at the same evaluator checkpoint. Its old Err method's call-stack side effects were observations, not a Context guarantee that callers must invoke Err. The actual main oracle's two scalar evaluation stages (early and projected) remain asserted; no scalar execution was replaced.

Changed tests: candidate_slot_test.go, distinct_string_id_integration_test.go, early_match_trace_test.go, functions_test.go, string_candidates_test.go, string_predicate_test.go, traversal_test.go, trigram_candidates_test.go. New file: context_check_test.go. These tests use already-committed testdata and do not depend on this review directory to execute.

## New actual-code assertions

Seven new top-level tests call the actual evaluator and, where relevant, real Store/regex/task implementations:

- Standard Background/TODO, canceled, expired deadline, custom cancellation cause, Value wrapper, and WithoutCancel results. Err remains Canceled rather than the custom Cause; deadlines remain DeadlineExceeded.
- Rebinding a copied evaluator to a canceled child while its parent stays live. This prevents a future stale-parent Done cache from losing worker-local cancellation.
- Explicit mixed component cancellation: query-only range reaches 3 Done / 0 Err observations; query then actual CandidateNode reaches 1 Done / 2 Err; query then cached real regex backtracking reaches 1 Done / 79 Err. These are correctness checkpoint counts, not timings. A subsequent uncanceled Store read and regex match still succeed.
- Closed actual Store with a live context gives ErrStoreClosed, while cancellation at the preceding evaluator check still gives Canceled.
- An explicitly invalid Err-only/nil-Done counterexample is retained to explain why the old injection is not production cancellation evidence. This is not used to make another execution test pass.
- Actual ordered runDistinctTasks stops at its prefix, cancels a waiting worker, invokes actual evaluator.check, and joins both started tasks before returning.
- Actual unordered runDistinctTasks preserves the required source failure, cancels its sibling, invokes actual evaluator.check and joins both started tasks before returning.

The earlier committed task join, Store Close/publication, parser, regex, and HTTP guard cancellation tests are retained and ran in the complete module suite. No tests were skipped or removed to accommodate this change; pre-existing opt-in external-fixture tests remain opt-in.

## Verification

All commands were run in this independent worktree. The baseline query run completed before edits. The initial query race run passed before adding stronger mixed-component assertions; the final whole-module run includes all final sources.

```
INDEXED_DISTINCT_OUTPUT=/tmp/graphite-go-context-check-87aaf0ad/context-check-review/base-oracle \
  go -C graphite-server test ./internal/query

go -C graphite-server test -race -run '^TestEvaluator' -v ./internal/query

INDEXED_DISTINCT_OUTPUT=/tmp/graphite-go-context-check-87aaf0ad/context-check-review/candidate-oracle \
  go -C graphite-server test -race ./...

go -C graphite-server vet ./...
git diff --check
git apply --reverse --check context-check-review/source.patch
```

All completed with exit 0. Raw logs are retained; ordinary Go test duration output is diagnostic and has no performance interpretation.

`oracle-comparison.json` records complete structural comparison and hashes for all 17 baseline/candidate native output JSON files, including eligibility records. All files are equal, not merely row counts or selected fields. The committed main-method and complete graph-query fixture tests (including 45 StringTable method lookups and the two corrected duplicate/unsorted queries) also run in the module suite.

This preserves the existing oracle scope and gaps: the original 1,048-case DISTINCT corpus remains 739 main matches and 309 previously recorded declined-path differences. It is not a claim that all 1,048 match main. This change adds no new main mismatch and does not conceal or repair those existing gaps. No new JVM oracle execution was necessary; original committed outputs are the baseline sources.

## Delivery

`source.patch` contains exactly the production change and test migration/additions, based on 87aaf0ad. SHA256: `734d415a8b13f638f7941b6e7bb1d09e33d9bdc4a85c6e864c5bdbd26963951c`.

`identity.json` lists all ten changed source paths and final hashes. `manifest.json` hashes these sources plus review evidence, excluding itself. The review directory is evidence only and is not referenced by test code. Production/tests/evidence are frozen after the manifest is written.
