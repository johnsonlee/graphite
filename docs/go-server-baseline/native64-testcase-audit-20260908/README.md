# Historical audit: the original HTTP42 subset did not replicate real64

This page preserves the initial HTTP42 audit below. Its incomplete-workload
finding has since been superseded: the native
[`main64.json`](../../../graphite-server/internal/benchmarkcase/testdata/main64.json)
is byte-identical to both actual main exports in this directory (SHA256
`378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023`).
All 1,267 original definitions, parameters, source selections and order are now
present. The [full replay at Go revision 2547ef3](../native-distinct-capability-boundary/native-readme.md)
matches the compared public outcomes and graph-state fields in cold, warm
prewarm, and startup-prepared captures. This does not establish complete
execution-condition fidelity or P95 acceptance: the original failing testcase
still blocks formal warm setup, and equivalent work/resource accounting remains
unfinished. The following findings describe the earlier HTTP42 harness, not
the current full native workload.

The pinned main benchmark constructs **1,267 cases** for 64 graphs with
`coverageFamily=all`. The existing native HTTP manifest contains 42 cases:
34 map to main's `global-wide` family, while eight come from a separate wrapped
benchmark. Therefore 1,233 main cases are absent from that HTTP manifest.
Even within the mapped 34, three replace parameter bindings with query literals.
Passing the 42 HTTP response checks is useful correctness evidence, but is not
proof that the original 64-graph benchmark testcases were replicated.

## Evidence, independent of graph execution

`ExportBenchmarkCases.java` invokes the actual private main functions
`broadQueryGraphSources`, `broadQueryFixtureDistributions`, and
`broadQueryCoverageWorkload` by reflection. It exports their complete resulting
case objects, including original query text, parameter maps, source selections,
expected row ranges, workload identities and order. It does not call setupTrial,
GraphStore.loadMapped, an executor, or any query/timing operation.

The JMH classes were built in the clean pinned-main clone at
`4e328b0109e13c896b74004823fb049fcb19251a`. The source snapshots, Gradle build log,
classpath, Java exporter and commands are retained here. `main-cases.json` is the
actual 1,267-case export. `http-coverage.json` lists every included and omitted ID.
The preserved graph manifest changes only path column 2 to the authenticated
persistent fixture snapshot; graph IDs, terms, workload identities, distribution
records and original row order are unchanged. The snapshot is byte-identical to
all 1,152 frozen graph files and both original TSV hashes.

| Main family | Cases | Mapped by existing HTTP42 |
|---|---:|---:|
| graph-id | 576 | 0 |
| graph-parameter | 192 | 0 |
| graph-id-set | 246 | 0 |
| graph-set-reference | 123 | 0 |
| contains | 24 | 0 |
| wrapped | 9 | 0 |
| boolean | 12 | 0 |
| exact | 12 | 0 |
| projection | 21 | 0 |
| aggregation | 6 | 0 |
| global-wide | 34 | 34, including 3 literal adaptations |
| global | 9 | 0 |
| regex | 3 | 0 |
| **Total** | **1,267** | **34** |

The extra eight wrapped HTTP queries come from
`AllFixtureWrappedDiscoveryLatencyBenchmark`, whose original setup uses the four
complete corpus graphs. Moving those query strings to the 64-shard fixture does
not reproduce that benchmark's original setup or expected distributions.

## Unreplicated execution conditions

- Main preserves graph manifest order. HTTP sorts graph IDs; these orders differ.
  Source order affects early/middle/late placement, row order and LIMIT results.
- Main has cold, warm and startup-prepared index states. Cold clears string
  indexes before the complete replay; warm clears and performs a validated replay;
  startup-prepared configures preparation on load. Generic HTTP warmups and a
  process restart do not establish those same states.
- Main executes all cases in its constructed order. It explicitly moves the K64
  zero-hit request-selected graph-set case first. HTTP42 starts with a wrapped
  case and its timed iterations shuffle the subset.
- Main passes original parameter maps and explicit request-selected source lists.
  Three HTTP literals do not test parameter binding. Predicate routing must not
  be confused with selecting sources before execution.
- Main times context/source setup, worker submission and executor completion;
  result canonicalization follows the timer. HTTP timings include transport,
  server serialization, full body reading and client JSON decoding.
- Main's single replay produces one sample per case and mixed distribution
  counters. Neither a JMH replay score nor the mixed-case P95 establishes an
  individual testcase's P95 over repeated observations.
- Main uses a fresh execution context with Long.MAX_VALUE budget per query and
  a 60-second timeout by default. These, cancellation/join behavior, source-access
  observations and index lifecycle need an explicit corresponding Go driver.

## Acceptance order

1. Use the complete exported main case definitions, preserving all 1,267 IDs,
   query strings, parameter values/types, source selections and order in Go.
2. Prove the same real64 fixture and cold/warm/startup-prepared preparation and
   consumption rules. Replay main and Go with complete result/error, ordering,
   provenance and relevant source-access comparisons for every case/state.
3. Only then measure per-case P50/P95/P99, samples and failures under matching
   conditions. Each case needs repeated observations (at least 200 per declared
   stratum), and the requested threshold is main P95 / Go P95 >= 10.
4. Keep HTTP end-to-end measurements as a separately declared layer. Do not mix
   their latency boundary with the original engine benchmark, substitute HTTP42
   for the full matrix, or hide errors/regressions in a pooled number.

**Status at the original HTTP42 audit:** testcase replication is incomplete; full functional replay and
per-case P95 acceptance have not passed. No performance measurement was launched
by this audit. Earlier successful HTTP42/63 checks and diagnostic optimizations
remain valid only within their explicitly recorded scopes.
