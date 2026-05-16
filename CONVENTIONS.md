# Conventions

## Unit Tests

- New or changed unit tests must verify the behavior that matters, not only that code executes.
- Parser and adapter tests must assert the concrete AST shape: operator names, variable bindings, arguments, literals, and nested expressions.
- Execution tests must assert meaningful result values, not only row counts, when the result content is part of the behavior.
- After tightening or fixing one unit test, review adjacent tests added in the same change for the same assertion quality issue.

## Verification and Benchmarks

- After fixing or tightening unit tests for a performance-sensitive path, rerun the relevant module tests and lint gate.
- Every PR body must include benchmark testing results.
- Benchmark evidence in the PR body must include the benchmark command, environment summary, exact benchmark names, result table, and a comparison against `main`.
- The PR body must include both method-level and end-to-end benchmark data.
- Method-level benchmark data must come from the most relevant JMH class for the touched code. For Cypher query changes, include `CypherBenchmark`.
- End-to-end benchmark data must include `GraphEndToEndBenchmark`, which covers `JAR -> build -> save -> mapped load -> Cypher query`.
- If a change touches persisted graph loading or large-corpus query behavior, also include the relevant `GraphBenchmark` load/query benchmark class, such as `EsQueryBenchmark`, `AndroidQueryBenchmark`, `EsLoadBenchmark`, or `AndroidLoadBenchmark`.
- The PR body must explicitly state whether the benchmark comparison indicates a performance regression, including separate conclusions for method-level and end-to-end results.
- If benchmark results cannot be produced, state the blocker in the PR description rather than leaving performance unaddressed.
