# Linux38 paired-latency draft — not run

This draft closes the missing paired-latency comparison for multi-keyword wide queries. It does **not** complete multi-keyword profiling, accept attempt146, change an existing performance gate, or modify production code. No build, Java process, capture, push or workflow dispatch has been performed for this draft.

The frozen external `MultiKeywordProfileRunner.java` is copied byte-for-byte from the validated38 adapter. The same compiled default-package adapter classes run against both original JARs; no class is inserted into either JAR. The upstream verifier includes the separately reviewed V4 exact-integer fixes (SHA256 `99439d4a001cf214c8e2f44e67b8898064c3e0c4c754043cd99f950c712d910f`). Its draft copy adds only a whitelisted explicit expected reset mode, retaining `per-query-cold` as the existing default. `verifier-adaptation.json` records both hashes. Existing controls and original verifier files are unchanged.

## Fixed experiment

Production remains base `4e328b0109e13c896b74004823fb049fcb19251a` versus candidate `23dafb3dc82b31ea78a5d399d3ed68f70de5340f`. Original CI canonical content SHA256 must match for both rebuilt JMH JARs; a mismatch stops setup, with no exemption. Canonical JAR content includes all entry names and bytes. Raw JAR SHA256 is separately bound for every replay. The original reviewed candidate benchmark/correctness harness is used on both sides exactly as in the original CI build script; no diagnostic marker overlay is applied.

| State, in this order | Pair1 | Pair2 | Pair3 | Unchanged `setupInvocation` call |
|---|---|---|---|---|
| `per-query-cold` | B then C | C then B | B then C | Before each of38 queries |
| `replay-cold` | B then C | C then B | B then C | Before the first query only |

Each cell side is a new JVM:12 JVMs total, each all38 queries in frozen order, with `-Xmx8g -XX:ActiveProcessorCount=4`, Java17, unchanged per-query timeout300000ms, unchanged execution budget and `LIMIT 200`. The fixed38 catalog preserves the original36-query prefix exactly, then appends the two verified four-keyword OR zero-result queries. There is no selection, warmup insertion, adaptive fork count or automatic retry. Index reset is not a cold OS page cache: fixture authentication/export/hash scans precede capture, and caches persist across processes. JIT state starts afresh in each JVM; it evolves during the38-query sequence. These are explicit cold-state experiments, not steady-state measurements.

The adapter calls the original `setupInvocation()` before every query in `per-query-cold`, and only before the first query in `replay-cold`. That method does more than clear engine string indexes: it resets CallSite scan metrics, performs three iterations of `System.gc()`, `System.runFinalization()` and a100ms sleep, then calls `sampler.start()` to reset its recorded peaks and enable background sampling. These are GC requests, not evidence that three collections completed. Cross-state comparisons therefore change GC/finalization/sleep frequency, metrics and sampler resets together with index retention; their differences cannot be attributed solely to index caching. Within each state, B and C use the same unchanged adapter protocol.

All those invocation-setup operations occur before the corresponding query timer. Whole-JVM CPU/RSS/elapsed measurements include them, and setup can affect subsequent query execution. The timer remains the adapter's submission-through-result wait interval. Result serialization is outside each query timer. GNU time records user/system CPU, elapsed time and maximum RSS for the **whole JVM**, including setup,38 queries, serialization and teardown. CPU seconds retain GNU time's output precision; max RSS is KiB on Linux. These are not per-query resources. `/proc` checks reject any visible Java/javac before and after each replay; the scripts do not kill unrelated processes. Their own timed process group is killed/reaped on timeout. A leftover Gradle/Kotlin daemon causes a failure, not a silent concurrent measurement.

## Authenticate the Linux oracle before timing

`fetch-inputs.py` retains the original run34015504366 artifact API metadata, pinned ZIP digest/size and extraction checks for shared fixture64 artifact9984020128 and global evidence9984147800. It omits the profiler download because this protocol is unprofiled. The original fetch source hash and precise adaptation are recorded. Fixture semantic verification still runs against the original four source JARs before export. If expired or mismatched, input acquisition fails instead of replacing the corpus.

`prepare.py` builds both exact original JARs, verifies the shared fixture, compiles the external adapter/exporters once against base, and runs new **Linux** CallSite export and non-CallSite property census. Successful exporter receipts must bind the actual Linux manifest, provenance, raw base JAR and output hashes. `derive.py` independently derives all expected values by reading these real exported nodes, without executing Cypher as its oracle. The derivation preserves the frozen36-query prefix and independently proves each appended keyword is absent across all64 graphs.

The Linux catalog must exactly equal frozen local38 for query strings/order, complete result values/order/source graph provenance, all logical predicates, every64-graph hit census, counts and semantic descriptions. All38 `workloads.tsv` bytes must also match. Paths, raw archive hash metadata and export-compression metadata may differ by platform; each is bound to its own authenticated source and every such difference is retained in `linux-local-oracle-comparison.json`. This is stronger than using local expected counts alone, and it does not mislabel local exports as Linux evidence.

Persisted graph files are hashed before/after preparation and measurement. JARs, compiled classes, catalogs, original provenance and exporter outputs are bound in `prepared.json`; full endpoint checks run again after measurement, while runtime inputs are checked at every fork boundary. Endpoint hashing does not prove files were never temporarily altered between checks.

## Entry points and deployment delta

Deploy this directory to `.github/scripts/multi-linux/` on the existing `codex/attempt146-linux-profile` branch; copy `workflow.yml` over the existing `.github/workflows/attempt146-linux-profile.yml`. The workflow is **push-only** for exactly that branch, with an additional `johnsonlee/graphite` repository and exact-ref job guard. It does not depend on default-branch registration of a manual workflow. No push or run has been performed for this revision of the draft. Relative paths, frozen sources/catalogs and `pins.json` must travel together. Compared with the prior global34 workflow: retain exact production checkouts and artifact inputs, replace marker/profiler setup with `prepare.py`, then run `paired-run.py`; remove perf sysctl changes and profiler recording/export steps.

```bash
python3 .github/scripts/multi-linux/prepare.py \
  --java "$JAVA_HOME/bin/java" \
  --base-tree /absolute/base --candidate-tree /absolute/candidate \
  --fixture-dir /absolute/shared-fixture64 \
  --evidence-dir /absolute/global-evidence/benchmark-global-wide-116-1/reference-4e328b0109e13c896b74004823fb049fcb19251a \
  --output /absolute/diagnostic-output/multi38-setup

python3 .github/scripts/multi-linux/paired-run.py \
  --prepared /absolute/diagnostic-output/multi38-setup/prepared.json \
  --output /absolute/diagnostic-output/multi38-pairs
```

Both builds use `--no-daemon -Pkotlin.compiler.execution.strategy=in-process`, matching the already successful Linux run34018650891 that reproduced both original CI canonical JAR hashes. Canonical equality remains a hard check on the next run, not an assumed exemption. Builds, exporter JVMs and oracle derivation complete before the first timed JVM. No profiler or JFR parser runs between pairs. All output directories must be fresh; failures preserve their logs/partial rows and are never resumed as successful data. Workflow finalization/upload uses `always()` and includes authenticated raw Linux export/census, full results, commands, resource receipts and source hashes; large original JARs/classes/fixture ZIPs are excluded from upload, with identities retained.

## Results and limits

Every replay must hard-pass all38 query IDs/order, exact query hashes, all full values/order/provenance, success, source count and the selected reset mode. All raw observation fields are retained. `all38-paired.json` and CSV include each B/C sample and pair ratio separately for each reset state; JSON retains every non-latency observation difference, including work counters and fast-path/fallback counters. Work differences are diagnostic observations, not silently promoted to correctness failures or erased.

Three observations per query/side/state do not support a useful per-query P95: it is explicitly null. Descriptive medians/pair ratios are not statistical stability,10x or regression acceptance. No aggregate percentile mixes unrelated queries or reset states. CPU/RSS are whole-process observations and need corresponding pair interpretation. Actual sampler-based multi-keyword profiling and query-event alignment remain **separate pending work**.

Offline validation only: eight Python tests cover fixed ordering, complete existing38 controls against the repaired verifier, reset-mode rejection/dispatch, oracle mutation rejection, omitted P95 and retained work-counter differences. A temporary reset-label fixture tests validator mechanics only and is never represented as a replay. No Linux performance data has been generated by this draft.

## Working directory and API audit

Workflow shell steps explicitly run from `${{ github.workspace }}`. The three checkouts and downloaded fixture/evidence directories are siblings there, and `diagnostic-output/` is uploaded from that same root. `prepare.py` resolves every CLI path immediately; its Gradle wrapper and `-p` path are absolute, preserving the same build command/cwd arrangement as the successful original-JAR rebuild. Exporter classpaths, output/receipt paths and oracle paths are absolute. `paired-run.py` resolves its prepared receipt/output at entry and consumes absolute paths recorded by preparation. All bundled source/catalog references resolve from `__file__`, independent of shell cwd. Fetch and finalizer defaults are intentionally workspace-relative and are fixed by the workflow default. The downloaded manifest/provenance contain absolute graph paths: neither fetch nor fixture verification automatically relocates them. This deployment requires those paths to resolve under the same runner workspace layout as the original artifact. A differing layout fails verification and must be investigated, not silently rewritten. A Python test runs bundled-source authentication from a different cwd.

Both exporters accept exactly five named options: manifest, provenance, output, receipt and expected-jar-sha256. Preparation supplies all five, uses base-first exported engine identity, checks successful receipt kind/revision plus actual manifest/provenance/JAR/output SHA256, and records per-graph data. The frozen-main API is `GraphStore.loadMapped(Path)`, `Graph.nodes(CallSiteNode.class)` and `Graph.nodes(AnnotationNode.class)`; the exporters use the public Kotlin-generated getters and close each graph. These sources remain byte-identical to the validated exporters. `path-build-api-audit.json` records the source/build evidence; no Java was run for this audit.
