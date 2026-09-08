# Mapped cursor scheduler audit

Read-only source audit, 2026-09-08. No Go/JVM/build/benchmark was run and no production file was changed. Main is pinned to `4e328b0109e13c896b74004823fb049fcb19251a`; native HEAD is `f0838dda4f0d67628d028826d0577a8151765288` plus the current uncommitted persisted/cursor work. Source hashes below identify the audited files.

The six-graph private iterator diagnostic can obtain range counts `7,6,7,1,5,5` from the recorded case-3 starting states. This establishes that the low-level iterator can construct those caches. It does not establish the public dispatch schedule, pre-interrupted graph-entry behavior, or the cause of the remaining 15,162 public state differences. The complete public denominator and strict failures remain unchanged.

## Exact dispatch gate

Pinned `QueryPipeline.kt:1650–1817` first tries indexed ordinary projection, computes balanced/raw-leading flags, executes the leading projection probe, and returns immediately if it supplies LIMIT. It then selects serial versus parallel execution. Only after the balanced leading source is consumed and proves more rows necessary does it evaluate:

```
mayBatchPreparedStorage = balanced && nodePredicateFactory == null &&
    (!preferPersistedStorage || parallelStringScan)
batchedPreparedStorage = mayBatchPreparedStorage &&
    hasPreparedWideStringDisjunction(nodeClass, filter, candidateSources)
```

A true value selects `runDirectStringTasksWithFixedWorkersInOrderUntil`; false selects `runDirectStringTasksInOrderUntil`. Neither condition starts another leading probe. The prepared check includes all candidate sources, including the already consumed first one, at this late observation point.

`hasPreparedWideStringDisjunction` (`:2026–2050`) preserves the ordered candidate-type/property table at `:256`. It returns false when there are no relevant type/predicate pairs. For each graph and each relevant type with matching predicates, it accepts zero nodes, a true serial-strategy result, or a prepared capability. On mapped graphs (`MappedWebGraphBackedGraph.kt:1696–1712`), serial strategy requires an existing retained CallSite index with supported properties and its actual size preference. Prepared capability requires exactly CallSite type, supported fields, persistence enabled, and a regular sidecar file. A malformed regular sidecar still qualifies; a successfully mapped view is not required. Nonempty Annotation nodes with these dynamic fields do not qualify as prepared CallSites. Therefore an all-sidecars shortcut that forgets Annotation/type counts is not equivalent.

Current native `ordinary_projection.go:189–213` always calls `runDistinctTasks(..., orderedStop=true)` for the balanced suffix. It neither computes the late prepared-wide gate nor chooses a fixed-worker implementation. Earlier `allRetained` and `prefersSerial` checks do not implement this gate. Existing `PreparedProjectionFile` checks actual regular-file presence, but its request cancellation and filesystem-error behavior must not silently be treated as identical to Java `Files.isRegularFile`.

## Protocol comparison

| Dimension | Main prepared fixed workers, `:2572–2672` | Current native `runDistinctTasks`, `indexed_distinct.go:217–295` |
|---|---|---|
| Execution ownership | Submit exactly `min(taskCount,max(1,parallelism))` long-lived worker futures; each repeatedly takes an index from a shared FIFO queue | Launch a fresh goroutine for every admitted source task |
| Initial work | Worker futures submitted, then exactly workerCount source indices enqueued; no start/completion barrier | Exactly parallelism source goroutines launched |
| Replenishment | Enqueue one next source for each contiguous source result successfully merged | Same contiguous-prefix rule for `orderedStop=true`; **not a difference** |
| Out-of-order completion | Save outcome until its source turn; later failure cannot bypass an earlier LIMIT success | Same ordered pending-outcome rule |
| Worker error | Catch Throwable, unconditionally publish outcome using non-interruptible `outcomes.add`, then that worker exits | Recover panic, unconditionally publish one outcome to a count-sized channel, goroutine exits |
| Success reuse | Same worker can take the next enqueued source; worker interrupt/thread-local state belongs to that worker | New goroutine receives the same group context for each new source |
| Early LIMIT / error | Cancel each worker future in worker-index order with interrupt=true, accounting for never-started workers; join every completion | One shared `cancel()` broadcast; drain all launched task outcomes |
| Normal exhaustion | Enqueue workerCount `-1` sentinels, then join; no cancellation | Deferred shared cancel also runs on normal exhaustion, then drains active tasks |
| One source task | Still uses the worker/future protocol | Special case runs task synchronously on parent context, no child lifetime |
| Executor scope | Shared process-wide fixed thread pool (`:153–162`); worker-active flag set once per worker lifetime | Per-call goroutines; no corresponding process-wide direct-string pool |
| Parent interruption / join | Queue take can be interrupted; cleanup joins despite further interrupts and restores interrupt status (`:2674–2689`) | Coordinator waits for task outcomes without its own ctx-select; progress relies on tasks reaching checks |

Main's non-prepared rolling runner (`:2480–2562`) also uses one future per source, but cancellation is per future, not a single task-group broadcast. Do not replace every existing helper while adding the prepared branch. Both current native publication and main fixed-worker publication are non-interruptible in the relevant sense; replacing outcome send with a cancel-select that drops an outcome would introduce a deadlock/ordering defect.

Global pool capacity, submission queue competition across queries, and Java worker-local interruption are observable constraints. Per-invocation fixed Go workers alone repair worker reuse but do not establish all process-wide scheduling equivalence. Conversely no source evidence guarantees identical cache counts under all permitted schedules. The repeated natural-run discrepancy remains evidence to explain, not proof that a particular start barrier is part of main.

## Separate graph-entry cancellation gap

`MappedWebGraphBackedGraph.kt:916–931` reads retained state and chooses mapped/exact paths without an unconditional interruption check. Its mapped-view helper (`:1659–1672`) first checks consumer capability, persistence/file presence, then returns an already initialized view; that warm return has no interruption poll. Native `mainCandidateIterator` calls `RetainedProjectionIndex(e.ctx)`, whose getter always calls `ctx.Err()`. `PrepareDistinctStringIndex` calls `prepareProjectionOffsets` before inspecting the existing index/view, then polls again before returning it (`distinct_projection.go:273–322`). Even an already loaded offsets array encounters the initial cancellation check.

Thus a worker that starts but is canceled before graph lookup may stop before any native mapped span/range operation, while main can continue to its actual absolute-position poll. The low-level cursor controls do not test that public graph-entry situation. This is a second concrete source difference, independent of the missing scheduler gate; neither one's contribution to the real64 state mismatch has been measured separately. Do not remove all context checks, substitute Background, or force cache publication to make either hypothesis pass.

## Minimal coherent implementation proposal

Add a main-ordinary-only selector and runner, leaving `runDistinctTasks` and its unrelated consumers unchanged:

```
mainPreparedWide(plan, candidateSources) (bool, error)
runMainPreparedTasksInOrderUntil[T](
    parent context.Context, count, parallelism int,
    task func(workerCtx context.Context, sourceIndex int) T,
    stopAfter func(sourceIndex int, value T) bool)
```

The selector must observe the actual AST-derived type/predicate eligibility and late all-source capability check. The runner needs worker-owned cancellation contexts reused for successive source tasks, FIFO task indices, non-dropping outcome publication, source-ordered merging, prefix-based admission, and a worker join separate from source-result consumption. Stop/error cancels workers in stable worker order; natural exhaustion sends sentinels without cancellation. Cleanup must execute on consumer panic as well as producer failure. No completion/start barrier or eager suffix validation is justified. Explicitly track whether process-wide pool capacity and parent-to-worker cancellation bridging are implemented; do not claim the small runner alone duplicates them.

Treat graph-entry pre-interruption as its own main-only capability/read API change after oracle confirmation. It must preserve lifetime/Close safety independently of local request polling and preserve cold loader polling/work/error order.

## Most informative correctness controls (not executed here)

1. Public prepared and missing-sidecar controls with the same AST/history; include nonempty Annotation and zero-count relevant types, scoped/global selection, and a leading source that already reaches LIMIT. Observe actual branch and all states rather than forcing the helper.
2. Fixed workers handle successive tasks with the same owner; admit new work only after contiguous merge. Controlled out-of-order success/error and early LIMIT must retain full response and join every started worker.
3. An interrupted worker still publishes its error once; a later failure is ignored when an earlier ordered result satisfies LIMIT. Normal exhaustion leaves successful worker contexts uncanceled while joining them.
4. Real parent cancellation plus blocked queue wait and callback error: no lost outcome, no borrowed values after join, no Store Close while workers can read. Instrumented scheduling demonstrates reachability only, not natural timing distribution.
5. Public graph-entry already-interrupted controls: cold view, warm view, retained index, unavailable view; anchors/ranges at absolute positions on either side of 1024; work consumer that only interrupts versus one that throws. Capture exact error, work, range and retained state, and retry. Keep these separate from fixed-worker tests.
6. Re-run the complete natural public sequence only after each independently verified change. A passing iterator or selected six-graph state does not replace the complete public state denominator.

## Read-only evidence identities

- `/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/QueryPipeline.kt`: `9522dff099e2843f32115ae52f01adfb29c14d147dd3b9919daf907181c4f4ae`

- `/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt`: `9133289c4382b59f4bc880f9de3d8aee0930d449a32f9a487893dabb56255cb7`

- `graphite-server/internal/query/ordinary_projection.go`: `ee976b8b70288948995d8b1f97c099186d2a883eb928eb12c39fb94213db39a1`

- `graphite-server/internal/query/indexed_distinct.go`: `e890e0d64cb6334af0e4c60cc37a89dd37765679842118a86a7a9d251a9a8405`

- `graphite-server/internal/query/main_string_candidates.go`: `980f4f3a83ca66d0d1f25aa1599d593f5bc9b217ecc4f92dc4d27f86538487aa`

- `graphite-server/internal/store/ordinary_projection.go`: `a337a47ad844f18974bdc56a133ec3c27e7fb7a739c00393fefdb48e3830963f`

- `graphite-server/internal/store/distinct_projection.go`: `e18d68536ce2f076ccc2b958c7ecb6326542cbe2a359efb1140dc9d271ad38d5`
