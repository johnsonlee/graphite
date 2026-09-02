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
- Every PR must pass the required `benchmark-regression-gate` check. The check runs the base and PR revisions on the same GitHub runner and updates a benchmark result comment on the PR.
- The benchmark comment is the standard method-level and end-to-end evidence. A PR body may link to it instead of copying the same tables.
- Additional benchmark evidence in a PR body must include the benchmark command, environment summary, exact benchmark names, result table, and a comparison against `main`.
- Method-level benchmark data must come from the most relevant JMH class for the touched code. For Cypher query changes, include `CypherBenchmark`.
- Automated end-to-end data comes from `LargeCorpusPerformanceGateTest`, which covers `JAR -> build -> save -> mapped load -> Cypher query`; targeted manual investigations may add `GraphEndToEndBenchmark` data.
- If a change touches persisted graph loading or large-corpus query behavior, also include the relevant load/query benchmark class, such as `AndroidQueryBenchmark`, `AndroidLoadBenchmark`, `LargeCorpusQueryBenchmark`, or `LargeCorpusLoadBenchmark`.
- The PR body must explicitly state whether the benchmark comparison indicates a performance regression, including separate conclusions for method-level and end-to-end results.
- If benchmark results cannot be produced, state the blocker in the PR description rather than leaving performance unaddressed.

### Performance experiment history

- Append every performance optimization attempt to the relevant chronological
  `docs/*-optimization-attempts.md` log, including attempts that are reverted. Continue that
  log's date, attempt numbering, headings, and table style instead of creating a parallel record
  hierarchy.
- Use one commit per optimization attempt. Do not combine independent hypotheses in one experiment commit.
- Each record must identify the hypothesis, exact real-data fixture, base and candidate revisions, correctness result, latency, CPU/memory evidence, and the keep/revert decision.
- A failed experiment commit keeps the record but must not leave the rejected production-code change in the tree.
