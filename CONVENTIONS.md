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
- Every PR must pass the required `benchmark-regression-gate` check. The check runs the base and PR revisions on the same GitHub runner and updates a benchmark result comment on the PR.
- The benchmark comment is the standard multi-graph load-test evidence. A PR body may link to it instead of copying the same tables. Historical single-graph timings do not establish performance acceptance.
- Additional benchmark evidence in a PR body must include the benchmark command, environment summary, exact benchmark names, result table, and a comparison against `main`.
- Select benchmark implementations by their actual multi-graph workload and declared load, not their class names. Do not run a single-graph benchmark to satisfy a former method-level or end-to-end requirement. Retain its correctness checks as correctness gates.
- For construction, loading and query changes, report their respective multi-graph workload boundaries and results. Query acceptance must include representative concurrent requests, p50/p95 latency, CPU, RSS and complete result consumption. Distinguish diagnostic batches from the declared load-test acceptance workload.
- The PR body must explicitly state whether the multi-graph load-test comparison indicates a performance regression, with separate conclusions for each affected operation. Existing single-graph CI timing requirements must be reconciled with this rule rather than treated as performance acceptance evidence.
- If benchmark results cannot be produced, state the blocker in the PR description rather than leaving performance unaddressed.

### Verification feedback and reuse

- Before starting an expensive acceptance run, exercise the producer-to-consumer handoff with a fast correctness smoke check, including checkpoint sealing, the workflow's download layout, restoration, and consumption. Small or synthetic fixtures are permitted for this correctness check only; they provide no performance evidence.
- Declare the required acceptance checks, baseline, workload, and approved tradeoffs before measurement. Add checks only when a concrete defect, changed dependency, or new evidence justifies them; do not expand the acceptance scope merely because a run is still in progress.
- Run independent measurement and complete correctness verification concurrently when their actual data dependencies permit it. A measurement may finish while comparability is pending, but neither its job nor its report may claim final acceptance until the matching complete correctness proof succeeds. Preserve all failures and original samples.
- Reuse reviewed evidence for unchanged revisions, inputs, controls, environments, and measurement boundaries. Revalidate the affected dependency closure when one changes; do not repeatedly download, hash, or rerun the same evidence without a new reason. Required integrity checks when transporting or consuming artifacts still apply.
- If a long-running stage produces no new diagnostic or completed-stage result for 30 minutes, inspect the live process and critical path. Record whether it is advancing, waiting on a real dependency, or failed, and choose the next action from that evidence. This is a feedback deadline, not a timeout or permission to cancel a healthy run. Do not restart work because an observation timed out.
- Preserve an active run while preparing workflow repairs locally. Report separately what has been implemented, locally verified, and exercised by hosted CI; a proposed workflow change is not a completed improvement.

### Performance experiment history

- Append every performance optimization attempt to the relevant chronological
  `docs/*-optimization-attempts.md` log, including attempts that are reverted. Continue that
  log's date, attempt numbering, headings, and table style instead of creating a parallel record
  hierarchy.
- Use one commit per optimization attempt. Do not combine independent hypotheses in one experiment commit.
- Each record must identify the hypothesis, exact real-data fixture, base and candidate revisions, correctness result, latency, CPU/memory evidence, and the keep/revert decision.
- A failed experiment commit keeps the record but must not leave the rejected production-code change in the tree.
