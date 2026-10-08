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

## Correctness and coverage

The [final correctness audit](apple-frontend-evidence/2026-10-08/apple-final-correctness-audit.json)
records candidate `357a6206`, the frontend binary and IR hashes, the generation
environment, and 67 passing tests with no failures. The checks below concern source
facts and persisted graph contents; they do not establish performance acceptance.
The [retained evidence](apple-frontend-evidence/2026-10-08/) includes the original
source paths and binary hashes so that the measurement environment remains auditable.

Signal source discovery now includes 3,821 files, up from 2,529: 1,292 restored and
none removed. The directory traversal correction prevents a hidden regular file from
causing the next directory to be skipped, and traverses directories whose names end
in `.swift` without treating the directory itself as a source file. The
[source-file comparison](apple-frontend-evidence/2026-10-08/signal-source-files-diff.json)
and [index-fact comparison](apple-frontend-evidence/2026-10-08/signal-index-fact-preservation.json)
show that all 227,775 previous index facts remain, with 74,578 added. That comparison
keys calls by actual caller/callee USR and source path, line and column; it also checks
member/type declarations and supertype relations. The larger graph reflects restored
source coverage, not a reduction of the requested workload.

The independent lowering comparison preserves every previous node and edge within
its declared normalization boundary:

| Corpus | Nodes before → after | Edges before → after | Missing nodes / edges |
| --- | ---: | ---: | ---: |
| Signal iOS | 331,236 → 421,262 | 153,346 → 189,838 | 0 / 0 |
| SwiftPM | 60,956 → 60,956 | 29,247 → 29,247 | 0 / 0 |

The [Signal](apple-frontend-evidence/2026-10-08/signal-final-lowering-preservation.json)
and [SwiftPM](apple-frontend-evidence/2026-10-08/swiftpm-final-lowering-preservation.json)
multiset checks retain call source lines, receiver presence, literals and other node
identities. They exclude corrected signature types/owners and caller/callee display
names; they are not byte-for-byte graph equivalence checks. The separate index-fact
check anchors Signal calls before display-name normalization. For example,
[the same two calls](apple-frontend-evidence/2026-10-08/signal-call-selector-correction-example.json)
from `viewDidLoad()` at line 33 now resolve the imported Swift spelling
`autoPinEdge(toSuperviewEdge:)` to the Objective-C selector
`autoPinEdgeToSuperviewEdge:`. Their caller identities and source locations remain.

The final persisted definition inventory is:

| Corpus | Method definitions | Empty return types | Field definitions | Empty field types |
| --- | ---: | ---: | ---: | ---: |
| SwiftPM | 4,575 | 0 | 5,377 | 0 |
| Signal iOS, including discovered Pods | 42,487 | 1,389 | 34,748 | 1,179 |

SwiftPM's definition types are populated throughout this inventory, including the
previously unparsed `@Sendable` methods. This is not a claim that every external call
reference has a complete signature. Signal's original app targets have no remaining
empty method returns or field types in the audit. Exact
[source-backed assertions](apple-frontend-evidence/2026-10-08/final-exact-type-assertions.log)
cover SHA methods and their parameters, private declaring types, Swift and Objective-C
deinitializers, cross-module members, and the synthesized `badgeStore` ivar's
`BadgeStore * _Nonnull` type. The
[five Mantle protocol methods](apple-frontend-evidence/2026-10-08/signal-copied-header-signatures.json)
now obtain their types from the exact copied framework headers recorded by the index.
Only header paths belonging to already indexed members are added to the syntax pass;
this does not expand symbol discovery into unrelated headers.

The remaining Signal gaps are explicit: 1,385 C function return types and 1,179
C/third-party field types in the expanded coverage, plus four SSZipArchive methods.
For the latter, mutually exclusive `#if`/`#else` branches each open a brace and share
one closing brace, so the unpreprocessed source cannot be balanced as one method body.
The [diagnosis](apple-frontend-evidence/2026-10-08/objc-empty-return-diagnosis/classification.json)
records the affected methods and source lines. The lightweight declaration parser
is not a C/C++ preprocessor or Clang AST; these gaps remain unresolved. Nonempty types
and preserved facts do not establish complete C semantics, SIL dataflow or resource
extraction.

## Construction results

The [construction analysis](apple-frontend-evidence/2026-10-08/construction-analysis.json)
uses all 12 construction samples (three per variant per corpus), including every
frontend/importer log and independent saved-graph check in the
[raw journal](apple-frontend-evidence/2026-10-08/lifecycle-construction-final/samples.jsonl).
The measured candidate is `357a6206`; the feature baseline is `332c0d8a`.
Commands, binary hashes and graph verification commands are retained in the
[configuration](apple-frontend-evidence/2026-10-08/lifecycle-construct-config.json)
and [recorded protocol](apple-frontend-evidence/2026-10-08/lifecycle-construction-final/protocol.json).
Run with `python3 backend/bench/apple-lifecycle.py construct <config.json> --out <new-directory>`;
paths in the configuration identify the prepared source/index stores and binaries.

| Corpus / metric | Baseline | Candidate | Absolute change | Change |
| --- | ---: | ---: | ---: | ---: |
| Signal construction wall median | 15,348.065 ms | 18,900.144 ms | +3,552.079 ms | +23.14% |
| Signal construction CPU median | 19.471 s | 25.896 s | +6.424 s | +32.99% |
| Signal construction peak RSS maximum | 900.156 MiB | 1,422.469 MiB | +522.313 MiB | +58.02% |
| SwiftPM construction wall median | 4,901.508 ms | 4,723.243 ms | −178.265 ms | −3.64% |
| SwiftPM construction CPU median | 7.055 s | 6.807 s | −0.248 s | −3.51% |
| SwiftPM construction peak RSS maximum | 339.750 MiB | 343.391 MiB | +3.641 MiB | +1.07% |

Signal wall ranges were 14,939.110–15,823.739 ms and 18,852.879–19,603.549 ms;
CPU ranges were 19.462–19.657 s and 25.394–26.158 s. SwiftPM wall ranges were
4,435.214–5,541.789 ms and 4,689.224–5,290.158 ms; CPU ranges were
6.594–7.079 s and 6.807–6.968 s. These are only three observations per variant;
no statistical significance or confidence interval is claimed. Every sample is retained.

**Construction acceptance is not established.** Signal now processes 51.09% more
source files and produces 27.18% more nodes, so the two revisions perform different
work. The measured time, CPU and RSS increases are real costs of this candidate;
coverage restoration does not waive the 5% resource constraints. SwiftPM has equal
node/edge counts but different file, method and string counts and corrected signatures,
so its output is also not fully equivalent. Its observed resource changes are within
5%, but that alone does not establish matched-work acceptance or query recovery.

All 12 constructions completed and the separately queried node totals matched the
expected graphs. Every importer used `-Xmx8g`; no failure or out-of-memory event was
observed. Source-fact correctness is supported by the independent audits above,
not by graph size alone. The construction boundary excludes upstream compilation;
these numbers are not clean-build application compilation times.

## Formal server loading and query results

The original A/B run **did not pass performance acceptance**. It compares main
`4f2ccf33b969e684972e56b5e810034e6e67c1b3` with candidate
`357a6206a8a13fa2491d5ecaae16801493a89448`. All three persisted graph fingerprints
match between variants and between the loading/query runs. The native candidate binary
was built at `77ecf81b`; its Rust/Cargo sources are unchanged through `357a6206`.
Its SHA-256 is `b089844defbf59bbad8670f156199f1fed07f63dd19321966d467b933b4cefa6`.
The [build diagnosis](apple-frontend-evidence/2026-10-08/server-codegen-diagnosis.json)
records matching compiler profiles and identical pre-relocation code sections for the
query, storage, server and allocator objects; final executable layouts differ. This
narrows the diagnosis but does not explain away the measured differences. The
[independent analysis](apple-frontend-evidence/2026-10-08/server-analysis.json) retains
all samples, per-round quantiles, ranges, resource checks and CPU-unit corrections.
The [loading journal](apple-frontend-evidence/2026-10-08/lifecycle-loading-final/samples.jsonl)
and [query journal](apple-frontend-evidence/2026-10-08/lifecycle-query-final/samples.jsonl)
remain unchanged.

Sampling followed the declared three AB/BA/AB rounds with a warm OS cache and fresh
server processes. Each query case has five warmup requests per round, then 100 measured
requests at each concurrency, giving 300 observations per variant/case/concurrency
and 4,800 measured requests overall. Every request scopes all three graphs and consumes
the complete response. The table uses pooled nearest-rank p50/p95, not a distribution
across different cases. CPU values below include the independently verified timebase
correction described in the next section.

### Loading

Wall time runs from spawn through readiness and complete consumption of all four cases.
Wall and CPU entries are medians with full three-round ranges in brackets. RSS is the
maximum lifetime process peak across rounds, with its round range in brackets; shutdown
is included in lifetime CPU/RSS.

| Metric | Main [range] | Candidate [range] | Absolute change (percent) |
| --- | ---: | ---: | ---: |
| Wall, ms | 898.813 [892.330–919.908] | 954.868 [859.653–974.710] | +56.055 (+6.237%) |
| CPU through consumption, s | 0.887481 [0.875429–0.907120] | 0.900579 [0.851718–0.920772] | +0.013098 (+1.476%) |
| Lifetime CPU (`wait4`), s | 0.887729 [0.875861–0.907294] | 0.900782 [0.852055–0.921008] | +0.013053 (+1.470%) |
| Lifetime peak RSS, MiB | 108.109 [103.797–108.109] | 111.922 [108.484–111.922] | +3.812 (+3.527%) |

Loading wall time is higher in the observed median. CPU and peak RSS increases are
within 5%; this does not offset the wall-time increase. The wide candidate wall range
and only three rounds limit causal and statistical conclusions.

### Repeated-request latency

All eight case/concurrency pairs have higher pooled p50 and p95 than main. Each pair
therefore fails the observed latency requirement independently.

| Case | Concurrency | p50 main → candidate, ms | p50 change, ms (%) | p95 main → candidate, ms | p95 change, ms (%) |
| --- | ---: | ---: | ---: | ---: | ---: |
| `network-message-call-scan` | 1 | 6.628 → 8.137 | +1.508 (+22.755%) | 7.797 → 9.828 | +2.030 (+26.038%) |
| `network-message-call-scan` | 4 | 7.003 → 7.257 | +0.254 (+3.630%) | 8.520 → 8.725 | +0.205 (+2.408%) |
| `literal-call-provenance` | 1 | 23.308 → 28.368 | +5.060 (+21.708%) | 27.628 → 31.489 | +3.861 (+13.975%) |
| `literal-call-provenance` | 4 | 24.903 → 25.861 | +0.958 (+3.846%) | 27.629 → 29.072 | +1.442 (+5.220%) |
| `field-type-inventory` | 1 | 17.331 → 19.459 | +2.128 (+12.278%) | 19.657 → 21.510 | +1.853 (+9.427%) |
| `field-type-inventory` | 4 | 19.313 → 19.692 | +0.379 (+1.964%) | 20.325 → 22.063 | +1.739 (+8.556%) |
| `shared-wire-literals-prefiltered` | 1 | 637.708 → 682.851 | +45.142 (+7.079%) | 714.566 → 753.822 | +39.257 (+5.494%) |
| `shared-wire-literals-prefiltered` | 4 | 669.994 → 686.130 | +16.135 (+2.408%) | 771.714 → 834.135 | +62.422 (+8.089%) |

The slow shared-wire join has the largest practical latency impact: p95 increases by
39.257 ms at concurrency 1 and 62.422 ms at concurrency 4. Its per-round p95 ranges are
645.959–738.752 ms (main) versus 656.662–759.029 ms (candidate) at concurrency 1, and
682.115–786.774 ms versus 684.009–891.936 ms at concurrency 4. The corresponding p50
ranges are 635.251–641.633 versus 637.802–733.276 ms, and 666.935–730.721 versus
667.677–780.190 ms. Every other case's per-round p50/p95 values and ranges are retained
in the analysis. These overlapping, sometimes wide ranges require diagnosis; they do
not establish statistical significance or permit selecting favorable rounds.

### CPU, RSS, correctness and stability

Batch CPU is the median server CPU for 100 requests, with the three-round range in
brackets. These values exclude warmup; they are not CPU per individual request.

| Case | Concurrency | Main CPU, s [range] | Candidate CPU, s [range] | Absolute change, s (percent) |
| --- | ---: | ---: | ---: | ---: |
| `network-message-call-scan` | 1 | 0.653968 [0.611998–0.714116] | 0.799635 [0.619284–0.940476] | +0.145666 (+22.274%) |
| `network-message-call-scan` | 4 | 0.707670 [0.690758–0.712718] | 0.702226 [0.701148–0.790261] | -0.005444 (-0.769%) |
| `literal-call-provenance` | 1 | 2.426878 [2.241198–2.484861] | 2.855749 [2.230022–2.912279] | +0.428870 (+17.672%) |
| `literal-call-provenance` | 4 | 2.407576 [2.379155–2.425318] | 2.423732 [2.408220–2.669699] | +0.016157 (+0.671%) |
| `field-type-inventory` | 1 | 1.698442 [1.679609–1.870925] | 1.913950 [1.705467–2.008178] | +0.215509 (+12.689%) |
| `field-type-inventory` | 4 | 1.831493 [1.826639–1.845518] | 1.854525 [1.853301–1.960337] | +0.023032 (+1.258%) |
| `shared-wire-literals-prefiltered` | 1 | 64.275836 [63.558139–65.908258] | 70.258912 [63.918652–70.937783] | +5.983077 (+9.308%) |
| `shared-wire-literals-prefiltered` | 4 | 66.862768 [66.858139–72.447258] | 68.738783 [66.898095–76.275135] | +1.876015 (+2.806%) |

All four concurrency-1 CPU increases exceed 5%; all four concurrency-4 increases stay
within 5%. Each workload is assessed separately. Whole mixed-workload lifetime CPU
(`wait4`, including startup, warmup and shutdown) rises from a median 146.335300 s
[143.397764–150.239055] to 154.303175 s [143.929735–161.942728]: +7.967875 s
(+5.445%), also above 5%. Whole server-lifetime wall time rises from 92,792.226 ms
[90,036.993–93,116.119] to 100,099.436 ms [90,485.405–101,963.374], or +7,307.210 ms
(+7.875%); it does not replace the per-case latency results.

The query servers' maximum lifetime RSS rises from 265,568,256 to 270,974,976 bytes,
+5,406,720 bytes (+2.036%), within 5%. This is the peak for the entire mixed workload,
including startup and warmup. Independent per-case peak RSS was not measured, so it
cannot establish a per-case RSS pass.

Correctness checks pass: all five result digest/row-count comparisons match, and all
4,800 measured requests, 120 warmup requests and 24 load-consumption requests succeed.
Stability evidence contains no unexpected request failures or RSS-sampling errors.
The loading and query journals each have 14 initial readiness connection refusals
across six server starts; all occur before successful readiness. Each of the 12 servers
is deliberately stopped with SIGTERM after its work. The run's `summary.json` value
`ok=true` records successful execution and result parity, not performance acceptance.

### Separate same-binary diagnostic

The [A/A protocol](apple-frontend-evidence/2026-10-08/server-diagnostic-aa-protocol.md)
was declared before its independent run. The
[analysis](apple-frontend-evidence/2026-10-08/server-aa-analysis.json) and
[raw journal](apple-frontend-evidence/2026-10-08/lifecycle-query-aa-diagnostic/samples.jsonl)
remain separate from the formal A/B samples.

A separate A/A diagnostic ran six fresh instances of the same candidate native CLI
(SHA256 `b089844defbf59bbad8670f156199f1fed07f63dd19321966d467b933b4cefa6`,
built at `77ec`; native Rust/Cargo sources are unchanged through `357a6206`). It
completed all 4,800 measured requests and 120 warmups with identical result digests
and graph fingerprints; the 15 initial connection-refused readiness attempts were
retained, with no unexpected failures. Independent nearest-rank recalculation matches
the harness summary. Across its six processes, the slow join's p95 ranged from
665.642–781.404 ms at concurrency 1 and 721.693–767.152 ms at concurrency 4; the two
label pools were 750.525/762.664 ms and 742.222/746.547 ms, respectively. Corrected
100-request server CPU ranged from 64.847–72.650 s and 68.923–71.918 s. Competing
unrelated benchmark activity was observed during this A/A, so it is not a quiet-host
control. These descriptive spreads are not uncertainty bounds for the original A/B,
and the observations do not establish contention during that earlier run. The A/A
neither proves main/candidate equivalence nor replaces the original +39.257 ms and
+62.422 ms p95 regressions or failed acceptance; isolating their cause requires a
separately declared comparison with host activity accounted for.

The [contention observations](apple-frontend-evidence/2026-10-08/server-diagnostic-aa-contention.jsonl)
distinguish live snapshots from a transcription whose exact timestamp is unavailable.
The [environment snapshot](apple-frontend-evidence/2026-10-08/server-diagnostic-aa-early-environment.json)
was taken during the run, not before it; it cannot establish quiet starting conditions.

## CPU counter correction

Independent review found a measurement bug in the original macOS live CPU collector:
it divided `proc_pid_rusage` CPU fields by 1e9 as though they were nanoseconds. XNU's
[`fill_task_rusage`](https://github.com/apple-oss-distributions/xnu/blob/main/osfmk/kern/bsd_kern.c#L1187-L1197)
receives those fields from
[`task_power_info_locked`](https://github.com/apple-oss-distributions/xnu/blob/main/osfmk/kern/task.c#L6387-L6392),
which supplies Mach absolute-time ticks. The same host's `mach_timebase_info`, queried
after sampling, reports **125/3 nanoseconds per tick**.

The archived journals retain their original, mislabeled CPU values. The analysis
separately reconstructs each integer counter as `round(original_value * 1e9)`, then
converts it with `ticks * 125 / 3 / 1e9`. Batch CPU is the difference between converted
before/after counters. It records the original counters, reconstructed ticks and
conversion factor; no requests were rerun or original values overwritten. Across all
12 server lifetimes, the corrected final live CPU differs from independently recorded
`wait4` CPU by at most 0.000433 s, consistent with the additional shutdown work.
`wait4` construction/lifetime CPU, wall time and RSS are unaffected by this bug.

The constant correction changes absolute live CPU values, not baseline/candidate
percentages. The collector now queries the host timebase and retains raw ticks and the
factor in future evidence. Fourteen lifecycle harness tests pass, including exact
125/3 and 1/1 conversion checks and rejection of an invalid timebase. The correction
does not turn the observed CPU or latency overruns into a pass.

## Validation control fixes

The Xcode corpus cache now retains `derived-data/Build` alongside the index store.
Index records refer to copied framework headers in `Build/Products` and generated
headers in `Build/Intermediates.noindex`; preserving their symlink targets keeps a
cache hit consistent with a fresh build. The cache version was changed to avoid
restoring old incomplete entries. A restore simulation checks that the paths recorded
by the index remain readable after restoring the configured cache trees.

The comparator also retains the reviewed baseline revision and coverage-change reason
in reverse-order confirmation reports, including unsuccessful confirmations. This
changes reporting only, not the resource or graph-shape acceptance rules. The full
[benchmark script test run](apple-frontend-evidence/2026-10-08/benchmark-script-tests.log)
passes all 163 tests, including both confirmation outcomes and the cache restoration
check. Fourteen lifecycle tests pass after the CPU collector correction.

## Hosted real-corpus calibration

Run [37798249852](https://github.com/johnsonlee/graphite/actions/runs/37798249852)
uses the final release tarball on Linux with Swift 6.1.3/static Swift standard-library
linkage and on macOS 15 with Xcode 16.4. It prepares upstream sources once, measures the
candidate and then the fixed feature baseline `332c0d8a`, with one warmup plus five
measured frontend-only observations per variant. These sequential calibration samples
are not the alternating lifecycle comparison. Each emitted IR is wire-validated; the
last IR of each variant is imported by the production JAR, and identical IR hashes
across that variant's samples establish deterministic bytes.

The completed frontend-only observations are:

| Corpus / metric | Baseline | Candidate |
| --- | ---: | ---: |
| SwiftFormat wall median [range], ms | 1,301.424 [1,296.403–1,303.866] | 1,312.278 [1,309.760–1,322.880] |
| SwiftFormat peak RSS maximum, MiB | 78.977 | 78.777 |
| SwiftPM wall median [range], ms | 4,874.959 [4,866.865–4,938.720] | 5,176.946 [5,144.328–5,246.152] |
| SwiftPM CPU median [range], ms | 4,826.886 [4,811.433–4,894.819] | 5,079.300 [5,040.300–5,140.194] |
| SwiftPM peak RSS maximum, MiB | 173.906 | 174.656 |
| Signal 7.70 wall median [range], ms | 28,062.418 [26,661.809–30,964.288] | 45,720.037 [44,764.814–49,665.776] |
| Signal 7.70 CPU median [range], ms | 25,164.619 [24,467.679–27,469.450] | 36,758.423 [35,606.883–37,917.726] |
| Signal 7.70 peak RSS maximum, MiB | 776.656 | 933.656 |

The [SwiftPM audit](apple-frontend-evidence/2026-10-08/hosted-calibration-37798249852/swift-package-manager-audit.json)
and [raw calibration artifacts](apple-frontend-evidence/2026-10-08/hosted-calibration-37798249852/)
retain all six samples per variant, including warmup and import logs. SwiftFormat's
observed shape matches the existing pin and its limits remain unchanged. SwiftPM's
candidate has 482 files, 4,602 methods and 21,419 strings, versus 481, 4,601 and 21,459
for the baseline; its 60,407 nodes and 29,000 edges do not make the full outputs equal.
The manifest records both observed shapes and the exact baseline revision. These
Linux shapes also differ from the local macOS SwiftPM graphs; they are not interchangeable.

The [initial ceiling policy](apple-frontend-evidence/2026-10-08/hosted-bootstrap-ceiling-policy.md)
was recorded before inspecting the new samples, after SwiftPM candidate sampling had occurred.
For new real corpora only, the absolute bootstrap health limits use 1.5 times the maximum
of all five measured candidate samples, rounded upward to 250 ms and 16 MiB. SwiftPM
therefore receives 8,000 ms and 272 MiB. Warmup is retained separately; no measured
outliers are discarded. This is an explicit headroom choice, not a confidence interval,
an estimate of cross-runner uncertainty, or permission to exceed the independent 5%
resource constraints. Different coverage is reported as a ceilings-only transition,
not as a passing paired regression comparison. These calibration observations do not
establish lifecycle or query performance acceptance.

The [Signal 7.70 audit](apple-frontend-evidence/2026-10-08/hosted-calibration-37798249852/signal-ios-audit.json)
records both deterministic shapes: the baseline has 2,594 files and 357,780 nodes;
the candidate has 3,853 files and 449,760 nodes. This is different coverage, not a
paired regression pass. Applying the same declared initial rule to maximum candidate
wall time 49,665.776 ms and peak RSS 933.65625 MiB gives limits of 74,500 ms and
1,408 MiB. All three manifests now carry observed shapes and limits; both new corpora
also pin the baseline's separately observed shape to its full revision SHA.

## Remaining acceptance work

The retained candidate fixes correctness and delivery gaps; it is not a performance
recovery claim. A separately declared multi-graph comparison on an isolated host,
with background activity recorded throughout, is needed to determine whether the
native latency/CPU differences reproduce. That comparison must retain all observations
and report p50/p95 and resource constraints independently; the existing A/B failure
must remain in the history. Repeated runs selected for favorable results are not a
substitute for that diagnosis.

Construction also needs a semantically matched coverage baseline before a strict
performance comparison can be accepted. The original frontend's omitted sources
cannot be removed from the candidate to obtain a faster result. Any optimization
should first profile the complete discovered workload, preserve the source-fact and
lowering audits, and remeasure through a usable saved graph under matched heap settings.
The C/third-party and preprocessor-dependent signature gaps listed above require
additional language handling rather than a performance threshold change.

## Release and PR validation status

The final [dry-run release](https://github.com/johnsonlee/graphite/actions/runs/37798249852)
at `1ba3808f` succeeded. The
[archived audit](apple-frontend-evidence/2026-10-08/hosted-calibration-37798249852/audit.json)
contains every job outcome, artifact identity and full job log. Production frontend
code is unchanged since `357a6206`; later commits adjust validation controls and evidence.

- All four native CLI builds and all three shipped Apple frontend builds succeeded.
- Final tarball installation/build/import/query smoke tests passed on Linux x86_64 and macOS arm64/x86_64.
- The actual Homebrew-installed command built and queried the fixture successfully.
- Release archive assembly and local Maven publication passed; Docker built with `push: false`. The Homebrew tap update was intentionally skipped in dry-run mode.
- Three pinned real corpora produced 36 valid raw records (six warmups and 30 measured observations), with deterministic IR per variant and all six production import/save checks passing. These are calibration checks, not performance acceptance.

Earlier run 37794100367 passed every release job but failed SwiftPM/Signal preparation;
its [audit](apple-frontend-evidence/2026-10-08/hosted-calibration-37794100367/audit.json)
and logs remain. Linux needed SQLite development headers; the older Signal/MobileCoin
source pin did not compile with hosted Xcode 16.4. The final run installs those build
dependencies and uses Signal 7.70, whose upstream build uses the matching Xcode version.
The earlier successful rehearsal and intentionally cancelled intermediate run are also
retained; none of these observations is substituted for a failed performance sample.

Final validation-control checks pass: 72 Python benchmark tests, 10 Apple verifier
tests, 163 benchmark script tests and workflow actionlint (shellcheck disabled).
Their logs accompany the evidence. The 67-test final Swift run and earlier CLI tests/
clippy results validate the unchanged production code.

The required PR `benchmark-regression-gate` result is still pending PR creation.
Passing that gate's own thresholds would not override the stricter local performance
conclusions above.
