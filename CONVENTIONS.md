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
- After fixing or tightening unit tests for a performance-sensitive path, rerun the relevant module tests and lint gate.
- Every PR must pass `benchmark-regression-gate` for correctness, complete execution and OOM checks.
- GitHub-hosted runners do not enforce performance thresholds. Their timing observations are report-only.
- Use the Rust controller in `benchmark/`: frontend build/save runs on its native technology stack (JMH for JVM), and production Rust servers run the real64 load/query workload.
- Run exact base/head revisions on the same runner with matching source fixtures and fixed settings. JAR workloads use 4 GiB maximum heap; APK workloads use 8 GiB. Never retry an OOM with a larger heap.
- Keep one benchmark report comment per PR and update it for new commits. Include exact source revisions, correctness/OOM outcomes, base/head values, absolute changes before percentages, sampling methods, measurement boundaries, and links to raw evidence.
- Missing, failed or incomparable measurements must remain explicit; do not turn them into zero values or performance PASS results.
- Existing historical reports and experiment records remain historical evidence with their original limitations. A report-only hosted timing improvement is not formal performance acceptance.
- See `benchmark/README.md` for the fixed workload, measurement protocol and execution commands.

### Performance experiment history

- Append every performance optimization attempt to the relevant chronological
  `docs/*-optimization-attempts.md` log, including attempts that are reverted. Continue that
  log's date, attempt numbering, headings, and table style instead of creating a parallel record
  hierarchy.
- Use one commit per optimization attempt. Do not combine independent hypotheses in one experiment commit.
- Each record must identify the hypothesis, exact real-data fixture, base and candidate revisions, correctness result, latency, CPU/memory evidence, and the keep/revert decision.
- A failed experiment commit keeps the record but must not leave the rejected production-code change in the tree.
