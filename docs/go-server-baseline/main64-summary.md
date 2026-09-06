# Provisional main64 HTTP baseline

These are diagnostic baseline-only measurements. They do not demonstrate a Go
speedup, uncontended performance, full functional parity or completion of the 10x
goal. Both runs used the exact remote main commit
`4e328b0109e13c896b74004823fb049fcb19251a`, Java 17.0.18 arm64, 8 GiB heap, mapped
loading, server concurrency limit 4 and 60000 ms request timeout.

All 64 graph IDs were loaded simultaneously and verified against the real fixture
provenance. Catalog totals are 19,431,891 nodes, 20,448,885 edges, 1,374,983 methods and
5,046,935 CallSites. The original fixture and served clone files were SHA256
authenticated before each run. The complete fixed workload contains 42 root
cross-graph HTTP queries: 8 AllFixtureWrapped cases and 34 global-wide cases, with
three parameterized JMH shapes explicitly adapted by binding fixed HTTP literals.

| Client concurrency | Attempts | Correct successful responses | Failures | All-attempt mix P50 | All-attempt mix P95 | All-attempt mix P99 |
| --- | --- | --- | --- | --- | --- | --- |
|1 |8400 |8400 |0 |1.616ms |287.796ms |343.749ms |
|4 |8400 |8210 |190 |18.167ms |664.031ms |836.092ms |

Every query has 200 samples. Independent verification checked the 8400 distinct
iteration/query/runtime tuples, 42 cases and exactly 200 observations per case in
each run. Errors remain in the denominator and raw records. JSON responses were
checked against complete initial baseline observations, preserving column and row
order, values, empty schemas, omitted nulls and provenance.

All 190 failures at concurrency 4 were HTTP400 `cypher_query_failed` responses with
`Cannot grow a closed mapped CallSite string-index reservation`. This makes that
run invalid as successful-response performance acceptance evidence. No failures
were filtered out or retried into successful samples. The message originates from
`MappedCallSiteStringIndex.kt:2183`; its causal mechanism has not been profiled in
this task.

The host concurrently ran development builds/tests. Additionally, another agent
sent 20 arithmetic-oracle requests and one string-conversion request to the server
during the concurrency 1 measurement. These contaminations are recorded in each
run's environment caveat. Final comparisons must be repeated as paired main/Go
runs with other workers idle, both client concurrency 1 and 4, and the declared
cold/warm protocol. The first replay is retained separately; one observation per
query is insufficient for a cold per-query P95.

Evidence:

- [c1 per-query results and CPU/RSS snapshots](main64-c1/results.json),
  [raw samples](main64-c1/samples.jsonl),
  [first replay full responses](main64-c1/initial-replay.json),
  [identity and fixture hashes](main64-c1/metadata.json),
  [environment caveat](main64-c1/environment-caveat.json).
- [c4 per-query results and CPU/RSS snapshots](main64-c4/results.json),
  [raw samples](main64-c4/samples.jsonl),
  [all190 failure responses](main64-c4/errors.json),
  [identity and fixture hashes](main64-c4/metadata.json),
  [environment caveat](main64-c4/environment-caveat.json).

The exact measured Python harness sources are archived in each run's `harness/`
directory and their SHA256 values match the previously recorded identities.
Current verification tools and full64 protocol are documented in
[the scripts README](../../graphite-server/scripts/README.md).

These initial diagnostic runs have CPU/RSS snapshots but no GC logs or allocation
profiles. Separate fresh JVM/native64 correctness processes were subsequently
launched with GC logging; those logs belong to correctness preflight and cannot
be retroactively attributed to the timed baseline runs.
