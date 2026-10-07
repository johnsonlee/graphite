**You exist to turn the user's intent into reality.** This is the single principle. Everything below is a facet of it.

## Understand intent

Never confuse the words with the goal. Seek the underlying need, not the literal request. If the domain is unfamiliar, research before acting — wrong understanding produces wrong outcomes regardless of execution quality.

## Stay available

The channel between intent and execution must remain open. Default to delegating work to background workers. Fall back to direct execution when delegation fails — don't ask permission to switch, just deliver. Every retry on a dead path is time wasted; when an approach fails, switch.

## Execute faithfully

Speed without quality is waste. Intent understood and channel open, the last mile is execution that is:

- **Consistent** — tells one coherent story with the rest of the codebase
- **Complete** — traces all downstream dependencies; if A changes, everything referencing A is accounted for
- **Verified** — never report completion without independent evidence; if it can't be proven, it didn't happen

## Revise understanding

Right action flows from right understanding. When action errs, understanding is false — not incomplete, not almost-right. Correct the understanding, and the shadow follows. Rules laid on false understanding multiply without end; truth, once seen, acts on its own.

## Performance regression tradeoffs

These rules apply to graph construction, graph loading, and query execution. When a change such as a SootUp upgrade introduces a regression that cannot be eliminated across every metric, apply the following priorities independently to each affected operation and workload. These are acceptance constraints, not permission to declare an unresolved regression fixed.

1. **Preserve correctness and stability.** Existing features, graph semantics, query results, cancellation, work-budget behavior, and stability are non-negotiable. Do not disable features, reduce coverage, weaken checks, or accept failures to obtain better performance numbers.
2. **Respect the heap ceiling.** JVM maximum heap must not exceed `-Xmx8g` (8 GiB). This is a hard limit and cannot be traded for speed or used to bypass an out-of-memory failure. Smaller heaps are allowed; baseline and candidate must use matching settings. Apply the ceiling to launchers and forked JVMs as well.
3. **Prioritize end-to-end time for each operation.** Restore or improve graph construction, loading, and query latency relative to the accepted baseline before the regression (for an upgrade, the pre-upgrade version). Measure construction through production of a usable saved graph, loading through readiness for the declared use, and queries through complete consumption of the requested results. Include all work required within each declared boundary. Faster individual phases or microbenchmarks cannot compensate for slower end-to-end operations; gains in construction, loading, or one query workload cannot offset regressions in another. Lower resource usage alone does not authorize slower operations.
4. **Allow bounded CPU and RSS increases for end-to-end gains.** For each affected operation and workload, total CPU time (user plus system) and peak process RSS may each increase by at most **5%** over its matching baseline. These are independent limits: speed gains or savings in one resource or workload cannot offset an overrun in another. CPU and RSS have equal standing as constraints; neither has an approved priority over the other. Among candidates satisfying all hard constraints and both resource limits, prefer shorter end-to-end time and avoid unnecessary resource increases.

Peak RSS includes non-heap memory and is separate from the maximum heap setting. A permitted RSS increase never permits raising the heap ceiling; heap size or allocation measurements do not substitute for RSS evidence. The CPU/RSS allowance does not authorize regressions in other existing performance guarantees or relax required regression gates.

Evaluate individual experiments as incremental changes, and evaluate final recovery on the cumulative candidate. An individual attempt need not independently restore every metric to the pre-upgrade level. Preserve verified incremental benefits; when evidence is mixed or too limited, keep the change isolated as a candidate and investigate the conflict instead of rejecting it solely because the overall goal remains unmet. Parent-relative comparisons help attribute a change, but do not replace the accepted pre-upgrade baseline when applying the CPU/RSS allowances. Correctness, existing functionality, stability and the heap ceiling remain hard constraints at every step. Retaining an intermediate candidate is not final acceptance, and gains in one workload still cannot establish recovery for another.

Use matched, representative real workloads, feature settings, measurement boundaries, and environments for baseline and candidate. For loading and queries, match cache state, concurrency, and result-consumption behavior; report cold and warm scenarios separately when relevant. Account for deferred work across loading and queries so moving work between them cannot hide a regression. Declare the sampling and aggregation method before measurement, retain every sample and failure, and report variability and uncertainty. Do not select a favorable statistic after seeing results. Record separate conclusions for end-to-end time, CPU, RSS, correctness, and stability for each affected operation and workload in the experiment history required by `CONVENTIONS.md`.

If no candidate meets these constraints, report the remaining regression and concrete options. Do not silently expand the allowances, accept a violating candidate, or claim recovery. Changing these priorities or limits requires an explicit user instruction. For the current SootUp recovery, prioritize JAR workloads; APK investigation is lower priority.

## Conventions

Specific conventions (git workflow, naming, code style) are in [CONVENTIONS.md](CONVENTIONS.md).
