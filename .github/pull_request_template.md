## Change

<!-- Describe the problem and resulting behavior. -->

## Verification

<!-- Link correctness CI here. Hosted timing is supplementary; it never replaces
the mandatory local report below. See CONVENTIONS.md. -->

- CI / evidence links:
- Correctness:
- Query performance:
- Graph loading:
- Graph construction:
- Baseline / workload (where performance is affected):
- Acceptance status / pending proof or blockers:

<!-- Give separate conclusions for each affected operation, including remaining
regressions. Measurement completed with correctness proof pending is not final
acceptance. Distinguish local verification from hosted CI execution.

When relevant, note the handoff correctness smoke before expensive runs, evidence
reused with unchanged inputs, and the changed dependency or concrete finding that
requires invalidation or a new check. Follow the existing acceptance requirements;
do not expand them merely because a run remains in progress. -->

## Local performance benchmark

<!-- Required for every PR and rerun for every new candidate commit. Do not use N/A,
TBD, pending, a hosted runner, a single-graph timing or an earlier candidate. Use the
real persisted representative multi-graph workload and retain every sample/failure. -->

- Machine: <!-- local hostname/model, CPU, RAM and OS -->
- Baseline: <!-- full merge-base commit -->
- Candidate: <!-- full PR-head commit -->
- Workload: <!-- real dataset, actual multi-graph query scope, concurrency, cache state and complete-consumption boundary -->
- Sampling: <!-- ordering, warmups, measured requests/repetitions and uncertainty method -->
- Query p50/p95: <!-- base, candidate, absolute delta, percentage and failures; include the largest absolute regressions -->
- Graph loading: <!-- base, candidate, absolute delta, percentage, end-to-end time, CPU and RSS -->
- Graph construction: <!-- base, candidate, absolute delta, percentage, end-to-end time, CPU and RSS -->
- CPU / peak RSS: <!-- separate conclusions and 5% limit checks; name any explicitly approved exception -->
- Correctness / stability: <!-- outcomes and successful/failed sample counts -->
- Evidence: <!-- durable report/raw-sample reference plus SHA-256 digest -->
- Conclusion: <!-- separate acceptance for query latency, loading, construction, CPU, RSS, correctness and stability -->
