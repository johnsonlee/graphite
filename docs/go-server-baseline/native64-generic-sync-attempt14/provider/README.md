# Explicit synchronous generic DISTINCT cursor candidate

This is a separate candidate based on clean `87aaf0ad39c89189d52c859672e5420b26c3f5e9` plus the exact rejected Attempt11 source and root UTF16 equality correction from `/tmp/graphite-go-generic-root-87aaf0ad`. Worktree: `/tmp/graphite-go-generic-sync-87aaf0ad`. No Attempt12 context-check, mapped-node cursor, diagnostic harness, profile export, new cache, or concurrency-policy change is included. No commit/root/provider-source edit or performance/64-graph run was made.

## One hypothesis and exact implementation scope

The hypothesis is to replace per-node request/value rendezvous with synchronous explicit position advancement while keeping the same candidate selection, full node decode, projection/Java equality, source batches, required exhaustion and task joins. Performance benefit is unmeasured. Rejection of Attempt11 is not causal proof about its channels.

Incremental production changes affect two files:

- `string_candidates.go`: extract the existing preparation into `prepareIndexedNodePositions(...) ([]candidateGraphNodes, bool)`. The body is byte-identical except unavailable returns becoming `(nil,false)`. Preparation, certificates, trigram/dictionary selection, Postings, offset ordering and fallback are unchanged. `candidateNodePositions` holds only source-group and node-position indices over the existing prepared slices. The legacy indexedNodeWalker prepares once then creates fresh position state on each callback invocation, retaining its restart/repeated-call behavior and complete decode before callback.
- `generic_distinct.go`: replace the node producer goroutine/channels with a scanner-owned synchronous advance closure. Initialization is deferred until first demand. It prepares once or obtains QueryNodeIDs once, advances one pattern-matching candidate at a time, and retains its position across batches. Every candidate still goes through CandidateNode and matches. EOF is not probed after a batch has already reached its budget. A separate capture boundary handles former producer failures before returning to the unchanged consumer.

The entire file suffix from `type genericDistinctRows` onward is **byte-identical to Attempt11**. That includes scanner.next, scratch projection, batch, remaining, selected/source merge, serial vs parallel decisions, runDistinctTasks invocations, output construction and the top-level cursor-close defer. compileGenericDistinct is also byte-identical. generic_distinct_values.go (including root UTF16 equality fix) and engine.go are unchanged relative to Attempt11. `scope-verification.json` records these exact checks.

The cursor constructor still has a source lifetime Context/CancelFunc. Each advance receives the current request Context. No context is cached in prepared node positions, and no per-node goroutine, lock, pool or cache is added. Normal Context operations retain their Go1.22 synchronization; this is not the independently developed evaluator.check optimization.

## Failure and cancellation boundaries

The old producer covered candidate preparation, full decode and pattern matching. Its recovered failure crossed to next(), which checked the request Context before rethrowing. The new read() captures **only that same segment**, then next() checks the request Context before delivering value/EOF/failure. WHERE, projection and DISTINCT errors remain in scanner.next outside this boundary and receive no new cancellation-overrides-error wrapper. Required source failure choice and cancellation/drain remain in unchanged runDistinctTasks.

Synchronous advancement necessarily adjusts cancellation progress: the old producer used its source context and could keep preparing after the batch task had been canceled, until the outer query defer canceled and joined it. Synchronous work must run against the current request context so canceled source-wave work can unwind before runDistinctTasks joins it. The source lifetime context is checked separately; prepared positions retain no request context. A successful batch's task context is canceled when its wave finishes, but a later batch/remaining call supplies a fresh context and resumes correctly.

Actual production request inheritance is visible in the unchanged suffix:

- Serial scanner.next receives e.ctx, exactly the scanner's query parent.
- Initial batches receive runDistinctTasks(e.ctx)'s local WithCancel child (or e.ctx directly for a one-task call).
- Additional source-order batches receive e.ctx.
- Remaining scans receive a newly created runDistinctTasks(e.ctx) child.

Thus the current production requests all inherit the query parent's cancellation/values. The private cursor is not a general API for unrelated request contexts. No claim is made that arbitrary concurrently canceled custom contexts or exact racing cache-publication instants match the old goroutine schedule. Earlier request cancellation may prevent an index/proof publication that the old producer would have completed before outer cleanup. What must remain true is no partial/poisoned publication, no rollback of a completed publication, a fresh successful request, unchanged required errors, and completed task ownership before returning.

## Ownership, close and joins

All production `genericDistinctCursor.close` calls are in genericDistinct's single top-level defer. Every source operation is either synchronous in that function, or inside runDistinctTasks; that function's deferred cancel and drain join all started tasks before returning or propagating a panic. The outer defer therefore closes cursors only after all scanner tasks have stopped. There is no concurrent owner close/next callsite.

The cursor no longer owns a producer goroutine to join. Its private close cancels the source context, marks it exhausted and drops its advance closure. It is an owner-only operation, not newly promised concurrent close/next synchronization. No lock is added for a nonexistent caller. Store.Close remains separate: each full CandidateNode call retains its existing store lifetime lock and the consumer holds no mapping lock. The existing actual Store Close-between-demands test passes.

The old cancellation-joins-producer test was not discarded as irrelevant. Its exact Attempt11 source and successful baseline run are retained. It is migrated to assert that a canceled synchronous advance has fully unwound before next returns; the new task test additionally proves a required sibling failure cancels a blocked current advance, joins it, and performs no more accesses when the owner closes. The existing no-read-past-demand and required-late-failure tests retain their concrete consumption assertions with the new advance signature.

## Correctness counterexample coverage

Nine new top-level `TestGenericSync...` tests supplement the unchanged main corpus and migrated cursor assertions:

1. Shared prepared positions and legacy replay: two complete callback invocations both yield persisted source order `[17,2,41,90]`, not ID-sorted order. Explicit position iteration also handles empty groups and EOF.
2. Actual scanner batch across task waves: first batch runs inside runDistinctTasks; after its child context is canceled on successful wave completion, another batch resumes at the next persisted position and remaining drains to a selected suffix value. Retained row values remain unchanged.
3. Exact batch end: reaching its single-row budget performs one read and does not probe EOF. A subsequent batch performs the EOF request and marks exhausted.
4. Projection modes and borrowed scratch: full mode performs both original duplicate-alias projections, selected mode only the final assignment; both retain value 99. Retaining values/rows copies scratch ownership so mutating the reusable vector cannot alter selected data. The observer watches either Err or Done access only as test instrumentation; production check remains the base implementation.
5. Failure boundary: canceled producer failure becomes Canceled at delivery, while a consumer failure after delivery is not overwritten by an added cancellation poll. The latter is an explicit boundary probe; actual property/core errors remain covered by the committed main fault corpus.
6. Current request cancellation during active advance: a required sibling task failure cancels the source-wave context; the blocked synchronous advance unwinds, all task work is joined, and close causes no further access.
7. Real indexed preparation cancellation: cold and already-published-index cases cancel with a standard Context observer. Fresh index access and full candidate certification succeed afterward; the warm index pointer is retained, not rolled back. The warm case also establishes a successful certificate before cancellation. This is correctness evidence, not a claim of identical racing cache state or proof that no internal partial publication is possible beyond the existing Store tests.
8. Actual bad-last fixture: scoped LIMIT returns its first row, while qualified execution with a **single** source still consumes the bad tail and errors.
9. A canceled request before demand leaves initialization/position untouched; a fresh request starts or resumes at the next expected value.

The design audit's remaining planned assertions are supplied by retained tests rather than duplicated synthetic graph construction: the 440 actual main mixed/edge cases cover numeric boxing, signed zero, lists, enum values, aliases, SKIP 0 and qualified provenance/source counts; the root UTF16-equivalent string assertion remains unchanged; existing Close/demand/late-error tests remain; all 432 generic fault cases and the original DISTINCT corpus are replayed. No direct-property whitelist or omitted candidate decode was introduced to make cases pass.

One initial new test incorrectly assumed the second persisted Annotation node's number was 1; the actual persisted order has number 2 there. `initial-assumed-id-order.log` retains that failure. The test now compares the resumed value with the actual QueryNodeIDs second position, rather than imposing ID order. No production change was made to repair that test assumption.

## Verification and limits

The unmodified Attempt11 query suite ran before any changes and wrote `a11-oracle/` with all 18 output JSON files. Its sources/fixtures are hashed in `a11-identity.json`; the reviewed source worktree itself was never changed. Initial synchronous output is preserved in `candidate-oracle/`; final output is in `final-oracle/`.

Final full-module race and vet completed with exit 0. `oracle-verification.json` compares all 18 complete final output structures with unmodified Attempt11, including eligibility and the additional generic fault audit. Every file is equal. Ordinary test wall-time output is retained as diagnostic output, never performance evidence.

Commands from the candidate root:

```
INDEXED_DISTINCT_OUTPUT=/tmp/graphite-go-generic-sync-87aaf0ad/generic-sync-review/a11-oracle \
  go -C graphite-server test ./internal/query

go -C graphite-server test -race -run '^TestGenericSync' -v ./internal/query

INDEXED_DISTINCT_OUTPUT=/tmp/graphite-go-generic-sync-87aaf0ad/generic-sync-review/final-oracle \
  go -C graphite-server test -race ./...
go -C graphite-server vet ./...

git diff --check
git apply --reverse --check generic-sync-review/incremental-from-a11.patch
git apply --reverse --check generic-sync-review/complete-from-87aaf0ad.patch
```

The second final module run includes a test-observer Done method added after the first complete run; both runs' logs are retained. No tests were skipped or removed to accommodate the candidate; existing opt-in tests remain opt-in. The old cursor test source/logs and main fixtures preserve the pre-change assertions.

“No new differences” is not 100% compatibility: inherited Attempt11's original DISTINCT corpus is 741 main matches of 1,048, with 307 prior differences; its generic fault corpus is 360/432 main matches with 72 prior gaps. Candidate certification still falls back to full native decoding on an unrelated corrupt node, exactly as before. Contract agent's separate generic correctness audit is not implemented here. No new JVM oracle execution or main-relative performance result is claimed.

Unproven behavior is explicitly bounded: exact timing/winner among concurrent cancellation/source failures, arbitrary external concurrent private cursor close/next, and equality between old and new transient cache-publication schedules are not contracts established by these tests. The actual owner, source ordering, full consumption, error boundaries, live request cancellation and required joins are verified by code structure and the listed cases.

## Frozen delivery

- `incremental-from-a11.patch`: four-file delta (two production, one migrated test, one new test) relative to the exact restored Attempt11 candidate. SHA256 `50b779dc89b87796ec178d726300a7525204ad49a1c16c887073c21630032991`.
- `complete-from-87aaf0ad.patch`: complete source and tiny persisted-test fixture integration from clean 87aaf0ad, including inherited A11 and the root UTF16 fix. No diagnostics/evidence are embedded. SHA256 `5a2e7eed0f304ec038932f4cb7cf51139b9f976fa6368d77142daaf2b8d7ae65`.
- `candidate-identity.json`: complete 93-file integrated source/fixture hashes and both patch identities.
- `scope-verification.json`: exact preserved production regions.
- `manifest.json`: all review files and integrated source hashes, excluding the manifest itself.

The evidence directory is not a runtime/test dependency. The inherited/new tests use committed relative testdata. Both patches pass reverse apply-check against the exact final worktree. Source, tests and evidence are frozen after the manifest is produced; no performance process is running from this task.
