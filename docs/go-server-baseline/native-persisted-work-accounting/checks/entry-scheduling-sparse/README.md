# Sparse scheduling diagnostic

Prepared only; no Go/JVM/build/replay was launched. Ready command:

```sh
python3 docs/go-server-baseline/native-persisted-work-accounting/checks/entry-scheduling-sparse/run.py --source /Users/johnsonlee/.codex/benchmarks/graphite/persisted-work-f0838dda-lazy-core-real64-source-v1 --output /Users/johnsonlee/.codex/benchmarks/graphite/persisted-entry-scheduling-sparse-lazy-v1
```

Optional old-entry control accepts the explicit frozen source `persisted-work-f0838dda-real64-source-v4` with a separate new output directory. Run only serially in the parent's exclusive runtime window. Touched source files must match the recorded source set; full module inventory/copy/archive and before/after hashes are dynamic. No current workspace file is changed.

This inherits the previous runner's original workload SHA check, unchanged 1267-case input, prefix indices 0–3, all 64 sources, independent 1152-file real64 clone, pre/post fixture audits, original CLI setup calls, raw results/errors, source archives and reversible instrumentation receipt. The diagnostic response count is explicitly 4. Failed attempts are retained. No comparator/reference is modified and no desired cache count is enforced.

Sparse events retain executor creation, firstTask/queue submission, core/future start, actual source dequeue/outcome, source-to-graph association, prefix consumption/LIMIT, actual future/worker cancellation, worker exit and joins. Legacy entry remains observable. A worker may own several sources. Outside-query events remain recorded with index -1.

There are **no successful range lookup, cache observation, validation, anchor-selection, per-posting, publication, or poll events**. Only actual rejection at an existing mapped anchor/sequence/range cancellation check is logged. The existing `ctx.Err()` expression is evaluated once and its original returned error is passed back unchanged; the logger adds no check, callback, consume, cache action, wait, delay, or forced work. Cache results remain visible through original before/after state records. Absence of a rejection event does not prove that a task visited any particular successful checkpoint or finished matching.

The bounded in-memory logger is unchanged from the full trace and still adds overhead, including hooks in existing locks and atomic reservations. These runs can perturb scheduling and cannot establish performance or an uninstrumented distribution. Their purpose is to distinguish actual source admission/start from actual cancellation rejection when investigating android08. No causal conclusion or successful reproduction is claimed before execution.

In-memory exact-anchor application and inverse restoration passed for both supported sources: 41 edits for lazy-core-v1, 40 for old v4; seven existing files changed and one logger added. Python syntax was checked; Go compilation is deliberately deferred to the parent's runner launch. Existing full-trace runner files and evidence remain immutable.
