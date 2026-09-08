# Entry revision: independent scheduling review

Read-only, 2026-09-08. No Go/JVM program, build, test, replay, or performance workload was run. This review does not modify production or previous evidence.

## Result and source binding

The frozen v3 and v4 source modules contain **byte-identical** `internal/query/main_fixed_workers.go` (`dfc418d602fbe17261c4565155cf1061fb2f84bc2172d29e7a497ce48fd31e87`) and `ordinary_projection.go` (`972d36b5844e9f6debeeea0719cc497ce2f9826050eaec7c57f620d8614cb559`). Their module manifests identify base `f0838dda4f0d67628d028826d0577a8151765288`. Thus no new worker-count, submission, or replenish code was introduced between these two captures. Their changed production files are the seven query entry/matching files and seven Store entry/decoder/cache files, not the scheduler or its callsite.

The existing v3 cold comparison has zero state differences. The v4 cold comparison has 15,162 `mappedRangeCount` differences, first `replay/3/after`, `fixture-android-03`, main6/native0. Both have zero public-response differences over 1,267 cases, comprising 1,266 successful results and the same existing error at821. Both also retain the separate observer `errorClass` representation difference. The independent untouched-v3 cold repeat has now also completed: its `cold-comparison.json` has zero public/state differences over 1,267 cases and 162,304 graph-state observations. This strengthens the observed version difference; two successful v3 executions still do not establish a universal scheduling distribution guarantee.

Read alongside `main-entry-query-review.md`: E01–E10 covers its specific storage entry phases; it does not cover every scheduler interleaving, noncached retained heap, canonical merge, source-scanner poll, or cross-path executor interaction. The older `cursor-scheduler-audit.md` describes the pre-fixed-worker implementation and must not be read as a claim that the current balanced prepared branch still uses a per-source goroutine.

## Current protocol that matches the main source

Pinned `QueryPipeline.kt:2572–2672` and current `main_fixed_workers.go:163–286` agree on the following visible coordination rules:

- Worker count is `min(taskCount,max(1,parallelism))`. Native ordinary passes half the default available processors, minimum1. Main's default balanced graph-worker count uses the same half-budget plan. The executor capacity is `max(min(processors,8),max(1,processors/2))` in the current Go default, corresponding to main's default resolver at135–162. This comparison assumes the same reported processor count and no main configuration override; it does not prove those environment values from a formula.
- A worker instance owns one cancellation context across successive source tasks. A new future has a new context even when an executor worker is reused. Main similarly retains its thread interrupt state while its fixed-worker loop processes several task indices; the JDK executor clears residual thread interruption before a later future. Go does not reset context cancellation between source tasks of the same future.
- Main submits all worker futures, then enqueues the first workerCount source indices. Go constructs all closures, registers parent cancellation, submits all futures, then enqueues the same indices. Neither waits for all futures to start. Neither waits for all sources to finish before merging. Some workers may be runnable while others have not started. The submission loop is a sequencing requirement, not a startup barrier.
- Only successfully merged **contiguous source results** replenish the task-index queue. A later completed source does not independently admit another source. A stored later error cannot supersede an earlier result that already satisfies LIMIT.
- Workers publish the complete outcome before exiting on an error. Native publication has no cancellation-select. Main uses unbounded `outcomes.add`; native capacity is taskCount, which is sufficient because each admitted source produces at most one outcome. Neither loses the outcome merely because the worker is interrupted.
- Early LIMIT/error interrupts futures in worker-index order, then joins them. Normal exhaustion enqueues one sentinel per worker and joins without canceling successful task contexts. Parent callback teardown is joined independently.
- A future canceled before it starts is marked complete and its closure/worker references are cleared; a later executor dequeue skips its body. Started futures retain their local closure until the worker loop exits, then clear references. This preserves worker completion separately from source-outcome arrival.

The task-index channel's workerCount capacity does not add a normal-path barrier: the initial enqueue count is exactly capacity; each later enqueue follows a consumed outcome and hence an earlier index dequeue; at most workerCount indices/tasks are ahead of the merged prefix. On normal completion all source tasks have produced their outcomes before workerCount sentinels are added. There is no source-backed reason to add a startup latch, wait for speculative cache initialization, or force completion before LIMIT cancellation.

## Remaining actual implementation boundaries

### Process-pool initialization and sharing

Main calls `Executors.newFixedThreadPool` lazily. JDK17 `ThreadPoolExecutor.execute` creates a core worker with the submitted command as its **firstTask** while the worker count is below core capacity; later submissions go to its work queue. Native `newMainFixedExecutor` starts the full executor capacity when first initialized, and every future, including the first, enters one FIFO queue (`:103–136`). This is a concrete cold-start dispatch difference. It is not a missing barrier and it provides no specified guarantee about which source completes before LIMIT. It was present in both v3 and v4.

Main's executor is shared by prepared fixed workers, legacy waves, and the rolling per-source runner (`QueryPipeline.kt:2414,2487,2587`). Native `mainPreparedExecutor` is used only by the prepared ordinary path; `runDistinctTasks` remains separate. Consequently an earlier non-prepared query can warm main's pool but not native's prepared pool, and concurrent calls to different paths compete for one pool in main but not native. Whether either occurs before/at this case3 has not been established by this source review. Main's explicit `configuredGraphWorkers` property is also outside the current native default implementation.

### Queue interruption race, not a justified unconditional extra poll

Main's `LinkedBlockingQueue.take` obtains an interruptible lock and waits on a condition; Go performs a Done precheck and then a select between Done and an index/outcome. Both reject an already canceled worker at the explicit precheck. Their races when cancellation and a queue item become ready together are not mechanically identical: a Go select can choose either ready arm, whereas Java's result depends on lock/condition and interrupt ordering. Adding a poll after every successful dequeue would change Java's permitted case where the take already returned before the interrupt. A narrow queue-race oracle/instrumented schedule is needed before changing this boundary.

Main also sets/restores the `directStringWorkerActive` thread-local for the entire worker future. Native uses explicit worker evaluators and `parallelProjection`; it does not implement that JVM thread-local globally. Nested dispatcher/poll behavior outside this particular task closure remains a separate contract, not evidence that current source tasks accidentally share the parent context.

## Source-faithful cold-pool change, if pursued separately

The minimal mechanical correction is to construct the executor with capacity and zero started workers. Under submit's lock, if started workers are below capacity, increment the count and start one persistent worker holding this submitted future as `firstTask`; do not enqueue that first task. Otherwise append to the existing FIFO queue. After running its first future, the worker enters the ordinary queue loop. A canceled first future is skipped, but the newly created executor worker remains available for later futures. No startup latch, prestart-all, submit acknowledgement, or waiting for source work is part of that algorithm. Main's fixed-worker helper still submits every worker future before adding initial source indices.

This is a supported implementation correction, not a prediction that the range counts will match. It matters at executor initialization/core growth, not every fresh graph load. Queries0/1 may already have initialized or fully populated a process executor; the fact that all graphs have warm mapped views before query3 does not determine the executor state. Measure actual core starts and task submission paths in a future bounded diagnostic rather than equating cold fixture state with a cold pool.

The parent's separate case-state audit reports all versions have mappedView=true before case3; only android00 has ranges1024 and the other graphs have zero. After case3, android01..08 are main/v3 `[4,7,6,7,1,5,5,4]`, v4 `[4,7,0,0,0,0,0,0]`, and earlier v2 `[4,0,0,0,0,0,0,4]`. These are additional observations supplied during review, not newly executed measurements. They isolate publication of speculative suffix ranges, not a failure to load those views.

Main's protocol itself permits a worker future to remain unscheduled while an earlier source finishes: the coordinator submits futures and initial indices without waiting for every future to start, then cancels on the first source-ordered LIMIT result. Such a never-started future is explicitly canceled and joined without executing its body. Therefore complete speculative cache publication is not a source-level invariant. That logical allowance neither proves this is the actual v4 execution nor establishes equivalent natural scheduling distributions. Keep the comparator failure and determine actual task/poll causality; do not reclassify all state differences as allowed merely from this argument.

## Falsifiable next diagnostics

1. **Untouched-v3 repeat completed:** its full public/state comparison is again zero-difference. This strengthens a version-dependent observation but does not identify the scheduler mechanism or justify repeated attempts until a favored result appears.
2. If the version observation persists, capture one bounded instrumented case3 trace on the exact v3/v4 sources: global executor creation/submission/start; future index and source index at dequeue; entry mode; first mapped anchor/range poll; range publication; source outcome; contiguous merge/LIMIT; per-worker cancel; actual worker exit. Hooks should record events without waiting, canceling, skipping, or forcing work. Preserve separate uninstrumented captures because logging perturbs schedules. A trace is evidence of that run's causality, not an unchanged natural distribution.
3. If missing-cache sources never start or start only after cancellation, inspect executor admission/pool history. If they enter storage before cancellation but terminate at a particular genuine poll, inspect that poll's matching main boundary. If the first source reaches LIMIT sooner relative to speculative sources after entry changes, that supports a relative-progress hypothesis; it does not authorize slowing it down or making every source finish.
4. Test shared-pool/cold-firstTask differences independently with small correctness tasks and private executors. Distinguish cold creation from a warmed pool and prepared-only contention from mixed legacy/prepared contention. Assert admitted task identities, cancellation and join, not latency or a desired cache count.
5. Preserve E01–E10's complete success as one completed gate while separately testing any newly implicated noncached retained or source-scanner boundary. Do not undo a source/oracle-proven entry correction solely because a prior incorrect implementation happened to produce the desired speculative cache count.

No runtime trace was captured here. The current evidence establishes the listed source protocol differences and unchanged scheduler identities; it does not establish the cause of the 15,162-state regression or acceptance of an allowed-schedule explanation.

## Sources read

Main Kotlin paths refer to `/tmp/graphite-go-main-baseline-clone-4e328b0`, pinned revision `4e328b0109e13c896b74004823fb049fcb19251a`. JDK mechanics were read from the local Homebrew17.0.18 `lib/src.zip`, not by launching Java. This supplemental source version is identified separately; this audit does not claim it proves the exact JVM binary identity of every earlier capture.

- `graphite-server/internal/query/main_fixed_workers.go` SHA256 `dfc418d602fbe17261c4565155cf1061fb2f84bc2172d29e7a497ce48fd31e87`.

- `graphite-server/internal/query/ordinary_projection.go` SHA256 `972d36b5844e9f6debeeea0719cc497ce2f9826050eaec7c57f620d8614cb559`.

- `/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/QueryPipeline.kt` SHA256 `9522dff099e2843f32115ae52f01adfb29c14d147dd3b9919daf907181c4f4ae`.

- `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-real64-v3/module-source.json` SHA256 `cf5dc74523be99595992eb0b815a69f8b37961d38cb11ca826a8c1cdd50da7b3`.

- `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-real64-v3/cold-comparison.json` SHA256 `f28f1d4a301cf5022be4dfbc41ddc1a7eeda5c3573a482ca9370be4d3cc725c3`.

- `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-real64-v4/module-source.json` SHA256 `02335a8449068ef10bcd55a65fd1cb4ec3160d8ef9fd2ae2b5b79bef71c25165`.

- `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-real64-v4/cold-comparison.json` SHA256 `00dfed6c145e2dfb2321f77c365e019067eb1ae5c705582d62e9231d276abe93`.

- `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/lib/src.zip!/java.base/java/util/concurrent/ThreadPoolExecutor.java`, lines 1115–1150,1328–1365, entry SHA256 `f2324097c15108638d1e5d76589000013dd0e5e2383ac0322194836ab7a27912`.

- `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/lib/src.zip!/java.base/java/util/concurrent/LinkedBlockingQueue.java`, lines 427–446, entry SHA256 `4a9324b661fce49ea4e39dc6c5f4ce2071aa5e434dedff14bca5fae19a12de4b`.

- `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/lib/src.zip!/java.base/java/util/concurrent/FutureTask.java`, lines 164–181,254–283,361–384, entry SHA256 `c7452b6dc16e838408ad38dd9461a123edab6e1f17936d0c22765c4956879593`.

## Closing evidence boundary

The current entry implementation's E01–E10 correction and full checks remain valid completed gates; its real64 state gate remains failed. The old frozen fixed-worker implementation has now produced two complete zero-state-difference cold captures. No counter or expected mask is changed here. The next independent lazy-firstTask experiment must preserve both records and establish whether case3's executor is still growing at all. There is no current per-query core-worker/start-history capture. A cold graph fixture is not evidence of a cold or partially populated executor. No cold-pool production change was made during this review.

- Independent v3 repeat `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-scheduler-cold-repeat-v1/cold-comparison.json` SHA256 `f28f1d4a301cf5022be4dfbc41ddc1a7eeda5c3573a482ca9370be4d3cc725c3`.

- Independent v3 repeat `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-scheduler-cold-repeat-v1/source-verification.json` SHA256 `78120c9d4f9f87b0f05d09ef0dd7d1975cea892c2bdc6e5103d801706d004e7a`.

- Independent v3 repeat `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-scheduler-cold-repeat-v1/native-cold-process.json` SHA256 `52a1c989c6b28c0550c43a832fc39e400da1f53e75ff342c61a70da1c2771bc3`.

- Independent v3 repeat `/Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-scheduler-cold-repeat-v1/controller.json` SHA256 `5ffbcb6d8f30f58e989bb013f04eda27c4194b83576a00727ca2862d4b55d293`.
