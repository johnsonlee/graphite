# Resolution of queued-future payload retention

The P2 finding in the original review is resolved in `main_fixed_workers.go` SHA-256 `dfc418d602fbe17261c4565155cf1061fb2f84bc2172d29e7a497ce48fd31e87`. The original review files remain unchanged and continue describing their earlier source snapshot.

All worker closures are now installed before the parent `AfterFunc` bridge is registered. A never-started canceled future clears both `run` and `worker` while holding its mutex, then publishes completion. `setRun` uses the same mutex and rejects canceled or completed futures, so later assignment cannot restore the request closure. `execute` captures the callable locally while atomically marking the future started; it invokes that local callable after unlocking. `finish` releases the future's callable and worker references before publishing completion. Completed/canceled checks precede the cancellation path's worker dereference, so clearing the worker does not create a later nil dereference on that path.

This preserves an active task's locally owned closure until the task returns, while releasing canceled queued request references immediately. Normal completion still leaves independently retained task contexts uncanceled. The queued future shell may remain in the FIFO queue, but it no longer owns the request payload identified in the finding.

Independent artifact verification of `scheduler-focused-v1` confirms exit 0 with `-race`, unchanged inputs, and all 2568 source-archive members matching the recorded module manifest. The current production file and test file match that snapshot. All archived artifacts were compared to their external originals, including the decompressed test log SHA-256 `6476265d60ebbc39ebc7017757142f6764d44c57a5646751ee432c61e5db297f`.

The raw log records PASS for the deterministic occupied-executor queued-reference check, assignment after prior cancellation, completed-callable release, and normal context lifetime. These controls inspect actual references and completion state; they do not rely on a garbage collection heuristic or performance measurement.

This resolves the specific resource-lifetime finding only. The broader full checks were still separate work at the time of this review, and no new real64 result was available for this scheduler source. Legacy runner integration, configured overrides and the other original scope limits remain unchanged. No overall regression or P95 acceptance is claimed. The reviewer ran no Go/JVM process and modified no production or test file.
