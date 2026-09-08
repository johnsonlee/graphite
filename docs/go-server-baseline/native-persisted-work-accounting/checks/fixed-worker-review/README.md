# Independent fixed-worker source review

Reviewed `internal/query/main_fixed_workers.go` SHA-256 `fc5b4f5afa7bb41ec85a94e748ba0966fe327ef3818393a2b48a7a675a7711ef` and its tests SHA-256 `97117c5077f7d2a222dbf924c934e0a3f15240b9dd3e2dea476fdf398809f08c`. This is a read-only source review, with no Go/JVM execution or performance measurement. It does not review a later correction implicitly.

## Finding: canceled queued futures retain request closures

`mainFixedFuture.interrupt` closes `done` for a future that has not started, but leaves both `run` and `worker` reachable through the process-wide executor queue. If another invocation occupies the available executor workers, a canceled invocation can finish its cleanup and return while its queued futures continue retaining the task closure, captured source stores/outcome buffer and worker context values. Repeated canceled invocations accumulate these references until workers reach the queue entries. This is a new resource-lifetime difference caused by the persistent queue, even though the canceled tasks do not execute.

The reference is actual JDK 17.0.18 `FutureTask.java:164–180`: successful `cancel` always calls `finishCompletion`. Lines 361–383 clear `callable` after waking waiters. A canceled FutureTask may remain in the executor queue, but that queue entry no longer retains the request callable.

A deterministic correctness control does not need GC or memory measurements: occupy a private capacity-one executor; enqueue a second future with a closure/context payload; cancel it; wait for its completion while the first task remains held; then inspect the queued future under its lock. Its payload references should already be released. A second control must cover cancellation before initialization finishes, so an initially cleared closure cannot subsequently be assigned again.

The safest small correction is to establish all worker closures before installing the parent `AfterFunc` bridge and submitting futures. `execute` should capture `run` into a local variable while setting `started` under the future mutex, then invoke that local value. Queued cancellation can clear `run` and `worker` under the same mutex before publishing completion; running-task completion can release them after the local callable returns. The existing `complete`/`canceled` checks must precede any worker dereference. Clearing only `run` inside the current `interrupt` is insufficient: the bridge can cancel before the coordinator assigns `future.run`, which would restore the retained closure, and the worker context itself can retain request values.

## Other reviewed paths

For the ordinary admitted path, the mutex and queue ownership provide consistent start/cancel/finish transitions. A canceled queued future completes without waiting for executor capacity and is skipped when dequeued. Started futures publish completion only after task exit. Queue submission publishes an initialized callable before normal execution. No definite normal-path deadlock was found in this review.

Only consumption of a contiguous result prefix admits replacement indexes, matching pinned main `QueryPipeline.kt:2572–2661`. The outcome channel has capacity equal to the total task count, and each index executes at most once, so publishing a failure cannot block merely because the coordinator is canceling. A suffix error can remain unconsumed after an earlier LIMIT, while an error in the required source prefix is rethrown. On successful completion every admitted source index has already been consumed, leaving room for one sentinel per worker. Normal shutdown joins futures without canceling observed task contexts.

The single parent bridge is stopped after joining. If the callback already started, cleanup joins `bridgeDone`; it does not mistake `AfterFunc`'s stop function for a callback join. Canceling a completed future returns before interrupting its old worker context. Private executor `close` is only valid here after its futures have joined; the global executor is not closed.

Existing tests check context reuse and normal cleanup, contiguous admission, suffix-error discard, producer/consumer failure joining, parent cancellation, queued cancellation without free capacity, active LIMIT cancellation, and FIFO queued execution with distinct contexts. They do not yet check the closure-release finding above. This source review does not replace the focused tests or race checks being run separately.

## Scope

The production call site is the prepared balanced suffix path in `ordinary_projection.go`; the old `runDistinctTasks` branches remain separate. Main uses one shared `directStringExecutor` for multiple direct-string runners. The Go change does not yet establish shared capacity with the legacy runner, configured-worker override parity, nested-worker marker/accounting parity, or arbitrary mixed-runner scheduling parity. Those are explicit integration limits, not grounds to expand this patch during the review.

No real64 regression or P95 acceptance conclusion follows from this review.
