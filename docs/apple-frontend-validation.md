# Apple frontend follow-up validation

This follow-up addresses dispatch, output-directory, SwiftPM indexing/header discovery,
real-source signature, cross-language query, and release-artifact verification gaps
in PR #154. SIL/dataflow expansion, resource extraction and a Rust indexer migration
remain separate architecture work.

## Measurement protocol declared before sampling

The feature baseline is PR #154 commit
`332c0d8a6fec650a6e7979e3dce37aa1a891c03a`. Main at
`4f2ccf33b969e684972e56b5e810034e6e67c1b3` has no Apple frontend, so an
Apple frontend construction timing comparison against main is unavailable. Construction
therefore compares PR #154 with this follow-up. Loading and queries compare
the main native server with the candidate on the same candidate-produced persisted graphs;
main can consume that existing graph format. These comparisons do not establish recovery
against an older accepted performance baseline. Candidate revisions and binary hashes
accompany each run. No synthetic corpus contributes performance evidence.

Pinned upstream sources:

| Workload | Repository | Commit |
| --- | --- | --- |
| Signal iOS 7.19.1.208 | signalapp/Signal-iOS | `dc04157b3adef68af2136a12478f455e41e5d2e0` |
| Swift Package Manager, Swift 6.1.3 | swiftlang/swift-package-manager | `587a4fdaebc5d7f977ac6b0a01f0e0b644b50f54` |
| Signal Server 6.99.1 | signalapp/Signal-Server | `9c62622733de2d4f067d9f8094e7542e8bf0c700` |

Signal iOS includes its pinned Pods submodule
`77c21193e6b5e4f8cbd86abaf46631fd242322f4`. SwiftPM dependency revisions are
frozen in `backend/bench/fixtures/swiftpm/Package.resolved`. Signal Server uses the
default Maven application JAR, without the optional shading profile or a Graphite
class filter. Dependency JARs are not part of that input.

Local measurements use macOS 14.3, Apple M3 Max, 64 GiB RAM, Xcode 15.4,
Swift 6.1.3 frontend binaries and Java 17. Hosted frontend calibration has a separate
environment: Linux with Swift 6.1.3 for SwiftPM; macOS 15 with Xcode 16.4 for Signal.
Hosted observations must not be replaced with local graph-shape or resource values.

`backend/bench/apple-lifecycle.py` records three paired rounds, alternating baseline /
candidate order AB, BA, AB. All attempts, including warmup and failed requests, remain
in the append-only sample journal. The local OS page cache is warm after preparation
and graph hashing. Each load/query round starts a new process. These measurements do
not establish cold OS-cache behavior or sustained saturation capacity.

* Construction: existing compiler index store through frontend IR emission and JVM
  import to a usable saved graph. Compilation is preparation outside this boundary.
  Each sample has an independent saved-graph query check. Wall time covers the whole
  sequential pipeline; CPU sums the stages; RSS is the maximum stage process peak.
  All importer JVMs have the same explicit `-Xmx8g` ceiling.
* Loading: process spawn through readiness and complete consumption of all declared
  query cases, including deferred indexing work. CPU is recorded at readiness for the
  declared use; process-lifetime CPU and peak RSS also include shutdown.
* Queries: each HTTP request explicitly scopes all three graphs (Signal iOS, SwiftPM,
  Signal Server). After five warmup requests per case, each case runs exactly 100
  measured requests at concurrency 1 and 4 in each round. Response bytes are fully
  consumed before the latency clock stops. Queries must not hit response caps.
  Result digests preserve values, duplicate rows and graph provenance, and must match
  across variants. Query CPU is the server-process counter delta for each batch;
  conservative peak RSS includes server startup and warmup across the entire mixed
  workload round, not the independent peak of each case. A sampled RSS value is
  supplementary and cannot substitute for process peak RSS.

The exact Cypher, scope, result order and response caps are pinned in
[`apple-lifecycle-cases.json`](../backend/bench/apple-lifecycle-cases.json).
The four cases cover a wrapped network/message call scan; StringConstant-to-CallSite
DATAFLOW aggregation; field type distribution; and a cross-graph equality join of HTTP
wire literals. Every request scopes all three graphs. The two ranked inventories request
the first 100 groups with a complete tie-breaking order; the transport cap is 10,000 rows.
Shared literal matches establish shared vocabulary, not proven client/server connectivity.
An initial unfiltered join hit the server's 60-second timeout during diagnosis; that failure
is retained separately. The measured join explicitly prefilters its first input with WITH.
Its success does not establish recovery for the original timed-out query.

Main/candidate query parity on shared graphs verifies the native server, not frontend
semantic equivalence. Source-backed signature correctness and intentional construction
shape changes must be checked and reported separately.

The query statistic is nearest-rank p50/p95 per case, concurrency and round, with
pooled values and per-round ranges retained. Report baseline and candidate milliseconds
and absolute changes before percentages. Construction/loading wall time and CPU use every
paired sample, median and full range; query batch CPU uses the per-case/concurrency median
and full range. Peak RSS comparisons use the maximum observed process peak across all
rounds, with the per-round values retained. Correctness, stability, wall time, query p50/p95, CPU and RSS
receive separate conclusions. A faster mean or construction stage cannot compensate
for slower query p50/p95, and CPU/RSS increases above 5% cannot be silently accepted.
Small differences within run variability remain inconclusive. No post-hoc selection
of favorable runs or statistics is allowed.

Hosted calibration collects observations only. It does not declare acceptance or
invent bootstrap ceilings. The PR benchmark comparator remains closed until observed
graph shape and evidence-backed ceilings are committed, then compares base and candidate
on the same runner.

## Results

Formal lifecycle measurements and final release rehearsal are pending. Diagnostic
builds establish that these upstream applications compile and import; they are not
performance samples. The completed report must link raw evidence and the required PR
benchmark-regression-gate result before claiming acceptance.
