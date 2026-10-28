# Conventions

## Unit Tests

- New or changed unit tests must verify the behavior that matters, not only that code executes.
- Parser and adapter tests must assert the concrete AST shape: operator names, variable bindings, arguments, literals, and nested expressions.
- Execution tests must assert meaningful result values, not only row counts, when the result content is part of the behavior.
- After tightening or fixing one unit test, review adjacent tests added in the same change for the same assertion quality issue.

## Verification and Benchmarks

- Synthetic graphs may be used only for correctness checks, planner/path coverage, deterministic
  source-access assertions, and other non-performance verification. They must not be used to
  establish or gate latency, throughput, CPU, memory, allocation, scalability, or speedup claims.
- Performance benchmarks and performance regression gates must use real persisted graph datasets
  representative of the production workload. If the required real graphs are unavailable, report
  the performance evidence as unavailable; never substitute synthetic measurements.
- **Multi-graph load tests are the only performance regression acceptance standard. Single-graph
  runs are correctness gates only.** Do not run or require single-graph performance measurements,
  including JMH, for diagnosis, candidate selection or regression acceptance. Every measured query
  must actually target multiple graphs; loading multiple graphs alone does not qualify.
- After fixing or tightening unit tests for a performance-sensitive path, rerun the relevant module tests and lint gate.
- Every PR must pass `benchmark-regression-gate` for correctness, complete execution and OOM checks.
- GitHub-hosted runners do not enforce performance thresholds. Their timing observations are report-only.
- Use the Rust controller in `benchmark/`: frontend build/save runs on its native technology stack (JMH for JVM), and production Rust servers run the real64 load/query workload.
- Run product merge-base/head revisions on the same runner with matching source fixtures and fixed settings. JAR workloads use 4 GiB maximum heap; APK workloads use 8 GiB. Never retry an OOM with a larger heap.
- Build the benchmark controller and shared frontend validation harness from the trusted target base tip. Intentional semantic changes need exact reviewed expectations landed there before the feature run.
- Whole Tika, Hive and Kotlin compiler JARs must complete JMH build/save/readback under 4 GiB in addition to the 64-shard workload; single-graph durations are correctness diagnostics only.
- Keep one benchmark report comment per PR and update it for new commits. Include exact source revisions, correctness/OOM outcomes, base/head values, absolute changes before percentages, sampling methods, measurement boundaries, and links to raw evidence.
- Missing, failed or incomparable measurements must remain explicit; do not turn them into zero values or performance PASS results.
- Existing historical reports and experiment records remain historical evidence with their original limitations. A report-only hosted timing improvement is not formal performance acceptance.
- See `benchmark/README.md` for the fixed workload, measurement protocol and execution commands.

### Required local PR benchmark report

- Every pull request body must include a `## Local performance benchmark` section. There are
  no documentation-only, test-only or claimed-unaffected exceptions. A hosted runner report,
  benchmark comment or link to CI is supplementary and does not satisfy this requirement.
- Run the benchmark locally on the exact candidate commit against its merge base, on the same
  machine with matching settings and the representative real persisted multi-graph workload.
  Any new candidate commit invalidates the report and requires a new matched run.
- Record the local machine and operating system, full base and candidate revisions, workload
  and query scope, concurrency, warmup and measured sample counts, result-consumption boundary,
  heap settings, and cold/warm cache state. Identify any reused evidence and prove that its
  revisions, inputs, controls, environment and measurement boundaries are unchanged.
- Report query p50 and p95 per case, prioritizing the largest absolute regressions; report graph
  loading and construction separately. Include base, candidate, absolute delta and percentage
  in that order. Report end-to-end time, total CPU time and peak process RSS separately for each
  affected operation and workload. Never collapse unavailable evidence to zero or omit failures.
- State the number of successful and failed samples, variability/uncertainty, correctness and
  stability outcomes, every approved exception, and a separate acceptance conclusion for query
  latency, loading, construction, CPU, RSS, correctness and stability.
- Retain the complete local report and raw samples. Put a durable evidence reference and SHA-256
  digest in the PR body so a reviewer can match the summarized report to the retained evidence.
- The `local-benchmark-report` workflow validates the required structure and exact revisions.
  It cannot prove that claimed measurements were honestly produced; reviewers must inspect the
  report and evidence. Keep this check required in branch protection.

### Verification feedback and reuse

- **First make the feedback loop fast; then begin validation and optimization.** Before the first expensive iteration, estimate the end-to-end critical path, including preparation, transfers, verification and final consumers. Establish fast actionable checks and remove avoidable serial dependencies or repeated work first. Do not treat hours per iteration as an acceptable default or postpone workflow repair until after repeated long runs; retain complete correctness coverage and real multi-graph performance acceptance.
- Before starting an expensive acceptance run, exercise the producer-to-consumer handoff with a fast correctness smoke check, including checkpoint sealing, the workflow's download layout, restoration, and consumption. Small or synthetic fixtures are permitted for this correctness check only; they provide no performance evidence.
- Declare the required acceptance checks, baseline, workload, and approved tradeoffs before measurement. Add checks only when a concrete defect, changed dependency, or new evidence justifies them; do not expand the acceptance scope merely because a run is still in progress.
- Run independent measurement and complete correctness verification concurrently when their actual data dependencies permit it. A measurement may finish while comparability is pending, but neither its job nor its report may claim final acceptance until the matching complete correctness proof succeeds. Preserve all failures and original samples.
- Reuse reviewed evidence for unchanged revisions, inputs, controls, environments, and measurement boundaries. Revalidate the affected dependency closure when one changes; do not repeatedly download, hash, or rerun the same evidence without a new reason. Required integrity checks when transporting or consuming artifacts still apply.
- If a long-running stage produces no new diagnostic or completed-stage result for 30 minutes, inspect the live process and critical path. Record whether it is advancing, waiting on a real dependency, or failed, and choose the next action from that evidence. This is a feedback deadline, not a timeout or permission to cancel a healthy run. Do not restart work because an observation timed out.
- Preserve an active run while preparing workflow repairs locally. Report separately what has been implemented, locally verified, and exercised by hosted CI; a proposed workflow change is not a completed improvement.
- Once a replacement is ready, compare the active run's remaining critical path with the replacement's full critical path before deciding to continue or supersede it. Use matched historical stage timings where available, include rebuilding, artifact transfer, final audits, downstream dependencies, queueing and uncertainty, and state which evidence can actually be reused. Elapsed time is a sunk cost; an active status alone does not justify continuing. Revisit the decision when new evidence changes the estimates. Do not estimate a parallel workflow by dividing its total serial runtime by the worker count.

### Performance experiment history

- Append every performance optimization attempt to the relevant chronological
  `docs/*-optimization-attempts.md` log, including attempts that are reverted. Continue that
  log's date, attempt numbering, headings, and table style instead of creating a parallel record
  hierarchy.
- Use one commit per optimization attempt. Do not combine independent hypotheses in one experiment commit.
- Each record must identify the hypothesis, exact real-data fixture, base and candidate revisions, correctness result, latency, CPU/memory evidence, and the keep/revert decision.
- A failed experiment commit keeps the record but must not leave the rejected production-code change in the tree.
