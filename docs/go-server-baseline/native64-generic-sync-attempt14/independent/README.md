# Independent synchronous generic DISTINCT review

Verdict: **no blocking defect found in the reviewed A11→synchronous transport increment**. This is a bounded source/ownership/counterexample review, not a claim that eighteen matching output files establish all semantics or that this candidate improves performance. The complete candidate inherits A11 functionality, root UTF16 equality correction and existing parity gaps.

Reviewed provider `/tmp/graphite-go-generic-sync-87aaf0ad/generic-sync-review`; manifest SHA256 `b0646ba6df1df3bfacb866ee159e596aacaccf94406bdead00ece9282b2440cf`; incremental patch SHA256 `50b779dc89b87796ec178d726300a7525204ad49a1c16c887073c21630032991`; complete-from87 patch SHA256 `5a2e7eed0f304ec038932f4cb7cf51139b9f976fa6368d77142daaf2b8d7ae65`. All166 provider manifest entries were independently verified twice. The independent detached worktree `/tmp/graphite-generic-sync-independent-87aaf0ad` starts at `87aaf0ad39c89189d52c859672e5420b26c3f5e9`, applies the complete frozen patch, and matches all93 source/fixture identity entries exactly. Only the independent test file copied here was added locally. No provider/root production edit, commit,64 runtime or performance run occurred.

## Incremental production scope

The two changed production regions are cursor construction/transport in `generic_distinct.go` and preparation extraction/position iteration in `string_candidates.go`. Direct comparison with `/tmp/graphite-go-generic-root-87aaf0ad` confirms:

- The original preparation body is exactly equal after converting unavailable `return nil` to `return nil,false`. Certificates, candidate grammar, trigram/dictionary/Postings reads, graph selection, offset sorting and fallback have not been relaxed.
- `compileGenericDistinct` is byte-identical.
- The entire suffix from `type genericDistinctRows` through scanner/merge/scheduling/result construction is byte-identical.
- `generic_distinct_values.go`, including root UTF16 equality, and engine.go are byte-identical.

The inherited generic-DISTINCT correctness differences and the independent remaining82 audit are not repaired by this change. In particular full-node certification of an unrelated corrupt node still declines to the same fallback; do not describe new synchronous positions as the proposed main-compatible selective candidate capability.

## Lifecycle findings

**Lazy initialization and prepare once.** `newGenericDistinctCursor` allocates state/slot but the closure prepares only on its first advance. Closing before a demand cancels an unused cursor without touching source/index/IDs. Successful preparation retains source-group positions; unavailable preparation takes one QueryNodeIDs snapshot. `initialized` is set only after one of those completes. A preparation panic makes the cursor exhausted and propagates, so that failing query cannot silently retry preparation and duplicate rows. A pre-canceled request is rejected before read/initialization and leaves state untouched.

**Legacy walker replay.** `indexedNodeWalker` still prepares at its original callsite. Its returned function creates a fresh `candidateNodePositions` on every invocation. This is essential: moving those indices into the enclosing closure would turn a formerly reusable walker into a one-shot iterator. The new implementation does not make that mistake. Independent actual indexed tests also interrupt the first consumer with a panic and verify the second invocation restarts at IDs2,41. The position helper skips empty source groups, retains source order and never sorts IDs numerically.

**Batch→remaining.** The generic cursor retains indices across scanner.batch and scanner.remaining. Reading one result advances the matching position exactly once; a batch reaching its limit does not demand another node or probe EOF. A later remaining call resumes after the last consumed position and performs its required exhaustion. Qualified single-source scans must still consume late failures; this inherited behavior and its exact source-count distinction remain in the unchanged suffix. No batch reinitializes the candidate lists.

**Borrowed slot and projection storage.** Each advance completely decodes CandidateNode before returning a borrowed candidateSlot. The consumer finishes predicate evaluation and projections before another advance can overwrite that slot. The generic plan still admits only literals/direct properties, not a bare node value escaping the slot. The unchanged scanner scratch is reused, but retained values are copied by genericDistinctRows.add and the row stores projected values rather than the slot. Full projection evaluates every original item, including overwritten aliases; selected-row mode evaluates the final assignment only, exactly as A11. Those modes are tested separately; the transport increment does not broaden their eligibility.

## Errors, cancellation and ownership

The old producer recovery boundary covered preparation, full decode and node-pattern matching. A11's receiver checked its request context before exposing producer error/EOF/value. The new read() covers precisely that former producer segment; next() checks the current request both before demand and after read, then rethrows the captured failure. WHERE/direct projection/equality still run in scanner.next outside read, with their original checks. No new wrapper overwrites a consumer exception after delivery. Provider's failure-boundary test is an artificial boundary probe, not by itself proof about every possible property failure; the reviewed catch boundary plus unchanged consumer implementation substantiate its scope.

The source lifetime context is separately checked before advance. Synchronous advance uses the **current request context**, not the source lifetime's earlier task context. This is required for joins: a sibling failure cancels the runDistinctTasks child, and an active synchronous Store/regex/checkpoint operation must unwind before that task can be drained. All production requests inherit the query parent. A successful wave's child is canceled when that wave returns; the next batch or remaining phase supplies a fresh parent/child. `e.ctx=ctx` at each advance ensures that the old successful wave cannot poison later work. Prepared positions contain no context. Independent tests cancel the first successful child, resume the actual indexed source with another child, and observe IDs2 then41 with preparation exactly once.

A request canceled during failed preparation/decode exhausts that cursor and aborts the query; this is not a promise that an arbitrary caller can resume a mid-operation canceled private cursor. Pre-canceled demand is different and leaves position unchanged, as explicitly tested. Unrelated custom request contexts are outside actual callsite ownership.

The outer genericDistinct defer is the only production cursor.close owner. All parallel source access is enclosed by unchanged runDistinctTasks, whose defer cancels and drains every active task before returning or propagating failure. Initial batch waves finish before synchronous extra batches; remaining uses another joined task group. Therefore cursor.close occurs after all scanner tasks stop, including panic paths. It can safely discard advance without a cursor mutex because there is no concurrent close/next production owner. Store.Close is a separate lifetime concern: each CandidateNode retains its existing Store lock; preparation snapshots do not bypass subsequent Store close checks. The independent test closes a real already-prepared Store between demands and requires ErrStoreClosed on the next read.

I found no active scanner concurrently used by two source tasks and no task context permanently captured by a successful synchronous advance. The new helper owns no producer goroutine; the join obligation is now the source task's unwind. The old producer-join test was migrated to assert this concrete obligation, while its original source remains in provider evidence. An additional independent test starts three advances, externally cancels their actual common query parent, checks all three finished before runDistinctTasks returns, and only then invokes owner close.

## Deliberate limits

Using the task context can prevent a cache/certificate preparation from finishing after a sibling already failed, where the old separate producer could finish before outer close canceled it. Exact transient publication timing and the winner among simultaneous cancellation/errors cannot be identical to an independently scheduled producer. No source evidence here guarantees identical racing cache state. Existing Store atomic publication/poisoning tests plus provider cold/warm cancellation controls remain necessary; completed publication is not rolled back by the cursor. A13/mapped cursor and A12/evaluator Done changes are not included in this independent base; the parent must validate their integration separately.

Private concurrent cursor.close/next is not tested as a supported API: old synchronization was an implementation detail of owning a producer, and current production always joins before close. Adding an external concurrent caller later would require a new ownership design. No tests or claims here turn that unsupported operation into safe concurrent behavior.

I did not recapture main or rerun the provider's18 large output files; all their frozen hashes were checked. The provider reports inherited741/1048 and360/432 rather than100%; this review does not alter those denominators or fill those gaps. The separate ordinary provider is not part of this candidate.

## Independent verification

Four new actual-code tests are copied in `generic_sync_independent_review_test.go`:

1. No initialization before demand/close, exactly one actual indexed preparation across successful child cancellation, correct resumed values and EOF.
2. Prepared indexed Store closed between actual demands yields ErrStoreClosed.
3. External query-parent cancellation unwinds and joins all three started synchronous advances before owner close; no partial rows merge.
4. Actual legacy indexed walker restarts after a first-consumer panic.

The first three were included with provider/legacy cancellation tests in the targeted run; the fourth was then added, and the final independent-only run tested all four20 times under race. No production or provider test source changed during either run. Both runs and query vet passed. No additional full-module suite was started; parent/provider full verification is separate evidence.

Commands in the independent worktree:

```
git apply /tmp/graphite-go-generic-sync-87aaf0ad/generic-sync-review/complete-from-87aaf0ad.patch
# Copy generic_sync_independent_review_test.go from this evidence directory
# into graphite-server/internal/query before executing tests.
go -C graphite-server test -race ./internal/query -run 'Test(GenericSync|GenericDistinctCursor|IndependentGenericSync|DistinctIntegration|CandidateCancellation|StringCandidateCancellationAtEvery|TrigramPreparationCancellation)' -count=20 -timeout=120s
go -C graphite-server test -race ./internal/query -run '^TestIndependentGenericSync' -count=20 -timeout=120s
go -C graphite-server vet ./internal/query
```

Logs, source snapshots and identity/check receipts are frozen alongside this report. Test elapsed output is routine bookkeeping and has no performance interpretation. No corrective production patch is proposed by this bounded review.
