# PR #229 handoff

State at `db80380` (2026-11-28). Read with `docs/pr-229-acceptance.md` (the evidence matrix) and
the reviewer's matrix comment on #229 (issue comment 7220978284), which is the acceptance bar.

## Where things stand

- **#229** (`claude/busy-ramanujan-0pnilb`): 22 review threads resolved after independent
  verification. CI on `db80380`: unit tests (100% line coverage, 7 modules), Rust, Cypher TCK,
  JavaScript and Apple pass. The benchmark gate fails only on `base-head-reviewed-semantics`
  for `shape-id-lookup` and `shape-traverse-filtered` (per-build node numbering), which is what
  #230 approves.
- **#230** (`claude/busy-ramanujan-0pnilb-approve-transitions`, head `48f67ac`): policy-only
  approval of those two transitions plus the `pr229_approval` regression. Green and
  reviewer-verified, but **bound to #229 `2d1a62b` (identity `eba350a9…`) and stale**: #229 has
  moved (`db80380` is `5f4a0a23…`). Do not merge it as is.
- **Decision by the owner:** the five open checklist items are done inside #229, not as
  follow-up issues. Every #229 push moves its product identity, so #230 is rebound once, last.

## Rebinding #230 (last step)

1. `python3 identity.py HEAD` on #229's final head. The identity is SHA-256 over
   `"graphite-product-identity-v2\0"` then each `git ls-tree -rz --full-tree` record plus `\0`,
   sorted, excluding `benchmark/src/transitions.rs` and
   `benchmark/jvm/src/main/kotlin/io/johnsonlee/graphite/webgraph/LargeCorpusValidation.kt`
   (`benchmark/src/product_identity.rs`).
2. Wait for that head's benchmark run; download `benchmark-evidence-229-*`; take the 73
   observations from `results/native/semantic-transitions.json`.
3. In `transitions.rs` on the approval branch: update `head_product_identity` of both rules, the
   `pr229_approval` `HEAD` constant, the fixture rows and the run reference; keep every earlier
   head identity in the rejection test (`05205f46…`, `eba350a9…`, `5f4a0a23…`).
4. `cargo fmt --check`, `cargo test -p graphite-benchmark`, clippy; push; reply on thread
   4725197501; the owner merges #230; rerun #229's benchmark.
5. The repository workflow squashes #229 to one commit before merge. A squash keeps the tree, so
   the identity and the approval stay valid; the acceptance doc's per-commit SHAs stop resolving
   and need a note.

## Open acceptance items (reviewer's matrix), in plan order

### A0. Construction-fork peak RSS (reviewer: +27%, one macOS pair, `2d1a62b`)

Reviewer: `Fixture64BuildBenchmark.buildAndSaveDistinctRealGraphs`, `-f 1 -wi 0 -i 1 -bm ss`,
G1 4 GiB, `wait4` on the fork: CPU 388→329 s, peak RSS 2.87→3.66 GiB.

Measured here (Linux 6.18, 4 cores, 15 GiB, JDK 21.0.13, same four pinned JARs, same JMH flags,
peak from `/proc/<fork>/status` sampled every 0.5 s), base `70f908c` then head `db80380`:

| Arm | Wall | Fork CPU | Peak RSS (VmHWM) | Anonymous at peak | File-backed at peak |
| --- | ---: | ---: | ---: | ---: | ---: |
| base | 472.5 s | 663.5 s | 2.630 GiB | 2.384 GiB | 0.147 GiB |
| head | 405.5 s | 592.4 s | 2.176 GiB | 2.059 GiB | 0.117 GiB |

One pair, one order: not a conclusion. The reverse order (head then base) was started and not
yet read when this was written. What the pair does show: the peak is anonymous memory (the G1
heap's committed size), not mapped graph files, so it follows GC timing and ergonomics under
`-Xmx4g`, which differ by platform and by how fast the arm allocates. Next steps: read the
reverse-order pair; if the sign flips with order or platform, measure committed heap
(`-Xlog:gc+heap`, NMT) and compare live-set peaks rather than RSS alone; report both to the
reviewer with every sample. Do not claim acceptance from one platform.

### A. Legacy-layout pressure tail (reviewer: repeats in both orders)

`shape-group-count` p95 56→79 ms and `shape-count-traversal` p95 +12–19 ms on graphs saved
before #229, 64-graph requests, concurrency 4; CPU and RSS within +5%; no repeat on graphs
written by #229. Queries (`benchmark/src/native_queries.json`):
`MATCH (n:CallSite) RETURN n.callee_class, count(*) AS c ORDER BY c DESC LIMIT 20` and
`MATCH (a)-[r]->(b) RETURN count(*)`.

Read so far (no measurement yet):
- the multi-graph HTTP route is unchanged from base apart from the `total` field;
- `backend/storage/src/graph.rs` adds nothing per request;
- `pipeline.rs` now clones each segment's clauses once per query and calls
  `fold_constant_limit`: microseconds, too small for a +3–4 ms p50;
- **candidate cause:** `backend/cypher/src/engine/partition.rs` replaced two direct indexed
  writes with checked ones (`rank.get_mut(id)`, and `checked_sub(start)` + `tags.get_mut` in
  `TargetTags::new`). Both loops run per query over every node id of every graph (about 19 M
  ids for the corpus). Unproven.

Reproduce: `graphite-benchmark local-query --base <70f908c release binary> --candidate <head
release binary> --manifest <graphs.tsv of base-built graphs> --workload regression --mode
measure --order ab`, then `--order ba` in another output. Then `perf record` one arm under the
pressure loop. If the checked writes are the cause, validate the index once at load (reject or
clamp ids past the offset index there) and restore the unchecked loop; keep the damaged-index
tests passing. Judge by CLAUDE.md's interval rules; record the attempt per CONVENTIONS.md.

### B. Completeness for JavaScript and Apple graphs

Neither path can carry it today. Plan:
- `backend/storage/src/interchange.rs`: optional `completeness` section (`serde(default)`, as
  `supertypes`); the importer computes method identities with `MethodDesc::identity`
  (`node.rs`), writes `graph.completeness` in the JVM byte format (the test helpers in
  `completeness.rs` are a byte-level template; strings are Java modified UTF-8), adds
  `graphite.nodeLayout.sha256` (SHA-256 of the nodedata bytes) and
  `graphite.completeness.sha256` to `forward.properties`, all before
  `GraphManifest::write_dir` seals the directory.
- `frontend/web/src/analyzer.ts`: return `skipped` from `project()`; analysed owners →
  `ANALYZED`, unparsable files → `RESOLUTION_FAILED`, bodiless declarations → `NO_BODY`,
  `<dynamic>` callees → `DISPATCH_UNRESOLVED` gaps, everything unrecorded reads `EXTERNAL`.
- Apple: a completeness chunk in `ir/graphite_ir.proto`, emitted by `IRWriter.swift`, mapped in
  `IrReader.readChunk` to `builder.setAnalysisCompleteness` (the JVM writer already persists
  it; node ids shift by one on import). No Swift toolchain in the cloud container: only
  `apple.yml` on macOS can verify it.

### C. Complete verification path

No per-stage record exists; the only number is "16–21 minutes" in `verify-affected.py`.
Likely slow stages: `:webgraph:largeCorpusTest` (three whole JARs, serial, never cached),
`forkEvery=1` suites in `:webgraph` and `:cypher`, a second Gradle invocation for Kover in
`build.yml`, and `org.gradle.parallel`/build cache both off. Plan: commit a before/after timing
record, merge `check` and the Kover reports into one invocation, enable parallel and the build
cache, keep every check.

### D. Framework semantics

`GraphiteContext` exposes no write method although the README documents
`context.addMemberAnnotation`; no extension is registered anywhere. Endpoints are derived at
query time from persisted member annotations (`backend/explore/src/endpoints.rs`). Plan: add the
documented write API with a `ServiceLoader` test extension, and derive dependency-injection
wiring (`@Autowired`/`@Inject` points → `@Component`/`@Bean` candidates via supertypes) the way
endpoints are derived, with a route, an MCP tool and a capability flag.

### E. One node schema

The node record is defined about ten times: `NodeRecordCodec.kt` + `GraphValueCodec.kt`
(persisted), `MmapGraphBuilder.kt` (spill), Rust `node.rs`, the Rust interchange encoder, and
readers at fixed byte offsets (`columns.rs` `RAW_STRING_FIELDS`, `fastpath.rs`, `scan.rs`,
`MappedGraphNodeAccess.kt`). Nothing checks them against each other below whole-graph round
trips. Plan: one schema file (tags, field order, encodings), generated tag and offset tables for
both languages, golden bytes per kind that every codec is tested against.

## Local state that does not survive the container

`/tmp/claude-0/base-wt` (`70f908c`) and `/tmp/claude-0/head-wt` (`db80380`) with built JMH jars;
`/tmp/claude-0/fx64` (the four pinned JARs); `/tmp/claude-0/runs/*/result.json` and
`samples.json` (construction samples); `/tmp/claude-0/scripts/{construct,identity,rules}.py`.
`construct.py <label> <jmh.jar> <fixtures> <out>` reruns one construction fork with sampling.
