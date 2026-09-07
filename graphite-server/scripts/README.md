# Native server verification tools

`http_parity.py` compares complete status and JSON/body semantics, plus Content-Type
and Retry-After, against an independently built pinned JVM server. Only manifest
JSON pointers explicitly identify dynamic catalog timestamps and runtime paths.
Array order, columns, null omission, scalar values and provenance stay significant.
JSON number tokens are compared as exact decimals to avoid Python float/int
precision artifacts. Raw response text and headers are retained separately.

`tika-real-cases.json` contains30 correctness cases over the real persisted Tika
shard. This small graph is strictly correctness coverage, never the acceptance
performance workload. It currently exercises catalog/node/annotation routes and9
Cypher shapes. Unknown annotation cases do not establish positive annotation
parity: the checked Tika shard and full Tika fixture contain no member annotations.

## Complete64-graph testcase acceptance

The existing HTTP42 workload is a diagnostic subset, not the complete main
benchmark. The actual pinned-main `LargeBroadQueryPressureBenchmark` generator
produces **1,267 cases** with 64 graphs and `coverageFamily=all`. Only 34 are
represented in HTTP42, three through literal substitution for parameters; the
other eight HTTP cases come from a separate benchmark. The remaining 1,233 cases
have not been covered by that HTTP manifest.

The [testcase audit](../../docs/go-server-baseline/native64-testcase-audit-20260908/README.md)
retains the actual JVM-generated case objects and every included/missing ID.
First replicate those exact queries, parameter maps, source selections and order,
plus cold/warm/startup-prepared index preparation and consumption in Go. Verify
complete responses, errors, provenance and relevant source access against main
before measuring each testcase's P95. Per-case repeated measurements and the
10x threshold apply to the full matrix; a mixed-case P95 cannot replace them.
The original engine timing and HTTP timing are separate measurement layers.

From `graphite-server`, validate the exact exported definitions with:

```bash
go run ./cmd/graphite-benchmark-cases \
  --manifest internal/benchmarkcase/testdata/main64.json \
  --sha256 378c200c5ab3053c53962f9d87c59924f732d0c012fcaff6009842a58e547023 \
  --output /path/to/new-main64-case-report.json
```

This preserves every case, binding and source-selection input and emits concrete
AST kinds. It performs no graph loading or query execution. Runtime parity,
index-state preparation and P95 acceptance remain separate, unfinished gates.

## Legacy42-case HTTP diagnostic protocol

The user explicitly requires64 simultaneously loaded graphs. Single-graph,
4-graph, graph-subset, sequential per-graph fanout, and synthetic workloads cannot
establish this performance goal.

Use `/tmp/pr113-exp037-fixture.nXn4fg`:64 distinct persisted class/resource shards
from the pinned Android, Tika, Hive and Kotlin compiler JARs. These are64 real
bytecode shards, not64 independently deployed applications. Preserve the original
`graphs.tsv` and `fixture-provenance.tsv`; original files are authenticated by
SHA256 against each server's isolated fixture copy. Startup can write optional
indexes, so do not run mutable preparations against the original fixture.

Build main revision `4e328b0109e13c896b74004823fb049fcb19251a` in a clean independent
clone (Gradle's publishing plugin fails in this environment's Git worktree
layout), then run the resulting shadow JAR. Record command, artifact SHA256, Java
version, hardware/OS, fixture hashes, process IDs, worker counts, timeout, heap,
load mode, and candidate revision plus dirty source manifest when applicable.
The current baseline uses Java17 arm64, `-Xmx8g`, `--load-mode MAPPED`, maximum4
concurrent queries,60000ms timeout, and all64 `--graph` mappings. Use matching
native limits and catalog IDs. Do not use stale prebuilt JARs from other worktrees.

`generate64_workload.py` freezes42 root `/api/cypher` queries using source from the
pinned main commit: all8 `AllFixtureWrappedDiscoveryLatencyBenchmark` cases and
all34 global-wide shapes from `LargeBroadQueryPressureBenchmark`. Three JMH
parameterized cases bind their fixed terms as escaped HTTP query literals;
current main HTTP does not pass parameter maps. This adaptation is explicit in
the manifest and does not claim to measure original JMH parameter transport.
Terms/shape placements retain original fixture order, whereas HTTP registry order
is sorted; baseline HTTP full responses establish the HTTP oracle. Do not reuse
JMH ordered-row fingerprints without this distinction.

```bash
python3 graphite-server/scripts/generate64_workload.py \
  --fixture-root /tmp/pr113-exp037-fixture.nXn4fg \
  --output graphite-server/scripts/real64-workload.json
```

`benchmark_http.py` rejects missing/subset catalogs, stats differing from fixture
provenance, different candidate/base catalog counts/modes, source bytes differing
from served copies, non-root routes, reduced workloads, and preflight response
mismatches. Every measured sample checks its full result against the baseline
oracle outside the timer. HTTP errors, cancellation, timeouts and wrong results
remain explicit failures; none are removed to improve percentiles.

Each invocation requires a new output directory. The runner preserves completed
and failed initial responses in `initial-replay.partial.json`, and writes full
HTTP or transport failures from initial replay and warmup to
`correctness-failure.json`. Warmup progress has an explicit completed/required
count. Error-containing timed runs retain all attempts and are marked invalid
for a successful latency comparison. These changes do not reclassify earlier
saved benchmark receipts.

Run `python3 -W error::ResourceWarning scripts/test_benchmark_http.py` from
`graphite-server` to check evidence preservation and overwrite protection. These
tests use fake transport solely to force failures before timing samples begin;
they run no graph query and produce no performance measurements.

Additional HTTP measurement matrix (does not replace the complete testcase gate):

| State | Client concurrency | Execution and evidence |
| --- | --- | --- |
| Warm |1 | At least3 independent paired process runs, alternating B/C and C/B; at least200 samples/query/runtime/run after identical warmups |
| Warm |4 | Same complete42 cases and sample counts, closed-loop4 workers with server admission4; report every429/timeout/error and per-query + fixed-mix P95 |
| Fresh process / first replay |1 and4 | Restart both runtimes between paired replays with identical prepared sidecars; preserve full first-replay order/results; at least200 observations/query for per-query P95 |
| OS-cache-cold, if claimed |1 and4 | Separate reproducible OS-cache reset protocol; fresh process alone is not an OS-cache reset; do not call it cold if unavailable |

Run warm strata with `--concurrency 1` and `--concurrency 4`. The script supports
`--capture-first-replay` only for a server that has received no prior queries;
one such replay is descriptive evidence with one observation/query, not cold
P95. Automated repeated process restarts and OS-cache control are not implemented
by this script yet and remain required evidence before a broad completion claim.

The command requires baseline URL/revision/artifact/PID, original and served
fixture roots, workload manifest, and output directory. A paired comparison also
requires the corresponding candidate fields. Omitting candidate fields captures
baseline-only evidence, with no speedup result. Example baseline-only command:

```bash
python3 -u graphite-server/scripts/benchmark_http.py \
  --baseline-url http://localhost:18851 \
  --baseline-revision 4e328b0109e13c896b74004823fb049fcb19251a \
  --baseline-artifact /tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar \
  --baseline-pid ACTUAL_PID \
  --fixture-root /tmp/pr113-exp037-fixture.nXn4fg \
  --baseline-fixture-root /tmp/graphite-go-main64-fixture \
  --manifest graphite-server/scripts/real64-workload.json \
  --concurrency 1 --samples 200 --warmups 3 \
  --output docs/go-server-baseline/main64-c1
```

Individual full-body timings, exact response hashes, correctness outcomes,
per-query P50/P95/P99, equal-weight pooled mix, and process CPU/RSS snapshots are
retained. Client timing includes connection, full response read and JSON decoding;
verification and `ps` sampling are outside the request timer. Process snapshots
are not allocation profiles or proven peak memory. Add equivalent CPU/allocation
and peak-RSS profiling separately. Record competing host work; repeat comparison
under controlled equivalent conditions before accepting an optimization.

For final paired runs, enable JVM `-Xlog:gc*:file=PATH` and record the exact
collector/JVM flags. Enable native `GODEBUG=gctrace=1`, retaining stderr separately
from startup diagnostics. Parse and retain GC event count, pause distributions,
heap-before/after and CPU alongside request samples. Add allocation evidence
through JVM allocation profiling/JFR and native allocation profiles or a measured
`runtime.MemStats.TotalAlloc` delta; sampled profiles and cumulative allocation
counts must be distinguished. Match observation overhead across compared runs and
also run uninstrumented controls. Current baseline-only captures lack these GC
logs and allocation profiles, so they cannot answer whether GC is the latency
bottleneck or establish the required final memory/GC evidence.

The target remains main P95 / Go P95 >=10 for every testcase in the complete64
matrix under matching declared conditions, with full functional parity and no
hidden failures. HTTP endpoint results must be reported separately from engine
timings. Per-case regressions must be reported
alongside the aggregate. Baseline-only measurements, a partial native query
engine, one successful warm stratum or one matching query set do not satisfy the
goal. Chronological optimization logs and mandatory benchmark-regression-gate
requirements in `CONVENTIONS.md` also remain in force.
