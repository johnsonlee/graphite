# PR #229 acceptance matrix

One chain per subproblem of the original checklist: **reproducer → expected behavior →
regression tests committed in this PR → baseline result → candidate result → revisions and
raw evidence**. Where a subproblem is an architectural limitation rather than a defect, the
chain is a use-case acceptance test. What is not solved is stated as not solved.

- **Baseline:** `70f908c` (`main` at the time of review). Base binaries were built from a
  clean worktree of that commit: `cargo build -p graphite-cli` and
  `./gradlew :query:shadowJar :explore:shadowJar`.
- **Candidate:** the evidence files were produced with binaries built from `28e761b`. The
  commit that adds this matrix adds tests, a CI step, test-only hooks and documentation; it
  does not change a production code path.
- **Raw evidence:** `docs/pr-229-evidence/*.txt`, each produced by the commands it names.
  Base and candidate always ran the same input; graphs were built by each revision's own
  frontend unless stated otherwise.
- **Hosted benchmark timings are report-only.** They are not performance acceptance (see
  row 6).

| # | Original problem | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Model fidelity: absent fact vs unrecorded fact | Addressed for JVM-built graphs. Open for the JavaScript and Apple frontends, which record no evidence | `completeness.txt` |
| 2 | Storage evolvability | Partially addressed: one file inventory, producer and format, whole-directory publication. The node codec is still hand-written in both engines | source references below |
| 3 | Data safety and corruption | Addressed, with an explicit contract: same-size damage is detected only by verification | `truncation.txt`, `bitflip-fuzz.txt`, `jvm-storage-safety.txt` |
| 4 | JVM/Rust error and JSON drift | Addressed for every case the differential covers (60 of 228 diverged at baseline, 0 now). Full engine equivalence is not proven | `cli-differential.txt` |
| 5 | Query service: paging, wide hierarchy, capabilities | Addressed, with stated limits | `query-service.txt` |
| 6 | Memory/scale: process-wide ids, max-id-sized indexes | Repeated-build drift fixed. The max-id sizing is kept and pinned as a contract. Matched multi-graph performance evidence is **unavailable** | `repeated-builds.txt` |
| 7 | Engineering: scheduling-sensitive tests, slow verification | Four tests made deterministic. The complete verification path is **not** faster; only the affected-change first pass is | source references below |
| 8 | Framework-semantic extension layer | Design boundary, not implemented | contract below |

---

## 1. Model fidelity: absent facts versus unrecorded facts

**Reproducer.** Build the acme fixture (`backend/bench/fixtures/acme`) with
`--include com.acme`, then ask the graph which calls reach bodies it holds:
`MATCH (c:CallSite) RETURN analysisStatus(c) AS status, count(*) AS n`.

**Expected.** Each call and method says whether the build read the body:
- `ANALYZED`: the body was read.
- `EXTERNAL`: the class was never loaded.
- `EXCLUDED_BY_SCOPE`, `INCOMPLETE`, … for the other cases (the table in
  `docs/analysis-completeness.md`).

A call on a function value says why it is not fully resolved (`dispatchGap`). A graph
without evidence answers `null` (never "complete"), and `GET /api/capabilities` reports
this per graph (`facts.completeness`).

**Baseline** (`70f908c`): `Error: Unknown function: analysisStatus`, exit 1. A method
without outgoing calls looks the same whether it calls nothing or was never read.

**Candidate.** Built by the real JVM frontend, saved, then queried in both engines:
- **Call sites:** 18 `ANALYZED` (every call into `com.acme.`) and 28 `EXTERNAL` (the JDK,
  plus SootUp's `sootup.dummy.InvokeDynamic` bootstrap callee).
- **Methods:** 20 `ANALYZED`.
- **Dispatch gaps:** 9 calls are `DISPATCH_UNRESOLVED`.

Both engines give the same answers (10 of 10).

**Committed tests**
- `backend/bench/completeness-e2e.py` with `backend/bench/fixtures/acme/completeness.expected.json`.
  It runs in CI (`rust.yml`, step "Completeness facts end to end") on the acme graph CI
  builds from bytecode, for both CLIs: frontend → saved graph → query.
- `AnalysisStatusCypherTest` (Kotlin engine).
- Rust `completeness.rs`: `statuses_follow_the_jvm_rules`,
  `another_saves_file_is_absent_and_damage_is_an_error`,
  `a_loaded_graph_keeps_its_own_evidence_after_its_directory_is_replaced`.
- The frontend's recording is covered by the existing `:sootup` tests
  (`DependencyCompletenessTest`, `DependencyLateDispatchGapTest`).

**Fact classes**

| Fact class | Recorded? | How a caller tells |
| --- | --- | --- |
| Body read and fully projected | yes | `analysisStatus = ANALYZED` |
| Body read, branch scopes dropped (>2,000 statements) | yes | `ANALYZED_WITHOUT_CONTROL_FLOW` |
| Abstract, native, bodiless method | yes | `ABSTRACT`, `NATIVE`, `NO_BODY` |
| Body cut short (out of memory) | yes | `INCOMPLETE` |
| Body or class unreadable | yes | `RESOLUTION_FAILED` |
| Class outside the analysis scope, loaded for types only | yes | `EXCLUDED_BY_SCOPE` |
| Class never loaded (JDK, unread jar) | yes | `EXTERNAL` |
| Class reached but past the `Reachable` budget | yes | `NOT_EXPANDED` |
| Call on a function value resolved to nothing, saturated, or only partly | yes | `dispatchGap = DISPATCH_UNRESOLVED / SATURATED / PARTIAL` |
| Anything from a graph built by the JavaScript or Apple frontend | **no** | `analysisStatus` is `null`; `facts.completeness` is `false` |

**Not addressed.**
- Facts the JVM projection never models, such as reflective targets or the effect of a
  native method, are named only as far as the statuses above name them (`NATIVE`,
  `EXTERNAL`).
- The JavaScript and Apple frontends record no completeness evidence.
- The library from #169 is not used by this PR and is not evidence for it.

## 2. Storage evolvability

**Reproducer (baseline source, `70f908c`).** A new persisted file had to be named in
several separate places:
- its writer;
- each reader (JVM and Rust);
- the JVM saver's list of stale files to delete (`GraphStoreWriter.deleteIfExists…`);
- the container's required/optional entry lists;
- the CLI's list of names that make a directory replaceable (`directory_replace.rs`).

Nothing recorded which producer or which format version wrote a graph.

**Expected.** One inventory of a saved graph, a producer and a storage format that a reader
refuses by name when newer, and no deletion list to keep in step.

**Candidate.**
- **Inventory:** every writer seals the directory with `graph.manifest` (producer, storage
  format, size and SHA-256 per file, closed by `@seal`), and a later format is refused by
  name.
- **No deletion list:** saves publish a staged directory whole, so no deletion list exists.
- **One list of names:** the graph file names live in one list in `graphite-storage`
  (`OPTIONAL_ENTRIES`), which the CLI's replace check and the container share.

**A new fact through the production path, in this PR.** Type supertypes now flow from the
JSON interchange to a query result:
1. `supertypes` input in `backend/storage/src/interchange.rs`;
2. written into the existing `graph.metadata` hierarchy section;
3. read by `Graph::type_hierarchy` (`backend/storage/src/graph.rs`);
4. queried through `/api/graphs/{id}/hierarchy`, `/api/hierarchy` and the MCP `hierarchy`
   tool (`routes.rs`, `mcp.rs`, `openapi.json`).

It needed no new file, so the inventory, the publication and the name lists did not change.

Compatibility:
- A graph saved without supertypes answers `known: false` for the type.
- Graphs from before the manifest load as before.

Tests: `GraphMetadataRoundTripTest`,
`the_hierarchy_walk_is_bounded_resumable_and_says_when_a_type_is_unknown`, and
`a_wide_hierarchy_pages_to_completion_and_stops_at_its_budget_or_deadline`.

**Not addressed.**
- The node record codec is still written by hand in both engines (JVM `NodeSerializer`, and
  `MmapGraphBuilder`'s spill format; Rust `node.rs`). A new node property still changes
  each of them and needs a format decision.
- There is no schema-driven codec.
- A newer storage format is refused, not migrated.

## 3. Data safety and corruption handling

**Reproducers and results** (`jvm-storage-safety.txt`, `truncation.txt`, `bitflip-fuzz.txt`)

| Case | Baseline `70f908c` | Candidate |
| --- | --- | --- |
| `GraphStore.save(loadMapped(dir), dir)` (acme, 204 nodes, 188 edges) | nodes 204, **edges 0** (both engines) | nodes 204, edges 188 |
| Save a graph into a directory holding another graph whose transpose was persisted | JVM engine answers **181** incoming edges from the old transpose; the oracle (Rust engine) answers 188 | 188 in both engines |
| `graph.labels` cut by 1 byte | answers `CALL 133, DATAFLOW 30297` (oracle: 134 / 30296): a call edge silently typed as dataflow | `corrupt graph: …graph.labels: 33844 bytes, but graph.manifest lists 33845` |
| `forward.graph` cut by 16 bytes | answers `CALL 129, DATAFLOW 30294` (edges silently lost) | refused, same message form |
| `graph.nodedata` cut by 64 bytes | answers | refused |
| 180 single-bit flips across six files, default mode | **62 process aborts** (exit 101), 1 error, 117 answers | **0 aborts**, 52 errors, 128 answers |
| The same 180 flips, `GRAPHITE_VERIFY_GRAPHS=1` | (no such mode) | 180 refused |
| Manifest cut at any line boundary, down to its header | (no manifest) | refused: `has no seal line` (review of #229) |
| Target directory that cannot be renamed (mount point) | n/a | refused and the earlier graph kept byte for byte (review of #229) |
| Completeness evidence or transpose read after the directory was replaced | n/a | bound to the generation the graph was loaded from (review of #229) |

**Detection contract** (README, "What is detected, and when")

| Damage | Default load | `graphite verify`, `GRAPHITE_VERIFY_GRAPHS=1`, `pack` |
| --- | --- | --- |
| Manifest cut short or edited | refused (seal) | refused |
| A listed file missing, truncated, extended or replaced by one of another size | refused (size check) | refused |
| Same-size damage (a flipped bit) | **not detected**: a record that does not decode fails its query with an error naming the graph; one that decodes can answer wrongly | refused (SHA-256 per file) |
| Directory without a manifest (pre-manifest, or the manifest deleted) | loaded as a legacy graph, unchecked | `verify`: "nothing to verify the graph against" |

**Committed tests**
- `GraphDirectorySafetyTest`, including:
  - `saving a mapped graph back into its own directory keeps the graph intact`
  - `saving another graph into a directory does not reuse the earlier graph's backward adjacency`
  - `a failed save leaves the earlier graph in place`
  - `a directory that cannot be renamed keeps its earlier graph and the save is refused`
  - `a manifest cut short or edited certifies nothing`
  - `a sealed manifest must list every file every writer produces`
  - `a graph replaced after load never publishes its transpose for the new graph` (fails at
    the review's reproduction before `c5393c0`; MAPPED and EAGER)
  - `a transpose stamped with a generation the directory no longer holds is not read`
- `AnalysisCompletenessPersistenceTest`:
  `a loaded graph keeps its own evidence after its directory is replaced`.
- Rust `manifest.rs`: `a_manifest_cut_short_or_edited_certifies_nothing` and
  `a_sealed_manifest_must_list_every_required_file`.
- Rust `csr_prefix_tests.rs`: `an_arc_past_the_last_node_is_a_corrupt_graph_not_a_transpose_panic`.
- CLI `a_panic_while_reading_a_graph_is_an_error_naming_the_graph` and `pack.rs`'s
  sealed-directory test.

## 4. JVM/Rust error and JSON drift

**Reproducer.** `backend/bench/parity-cli.py` runs both `graphite query` CLIs on the same
fixture graph. It compares stdout, stderr and the exit code byte for byte: 44 queries in 5
output formats, plus 7 CLI cases and 1 exit-code-only case. CI runs it (`rust.yml`, step
"Kotlin-vs-Rust CLI differential over the fixture").

**Baseline** (`70f908c`): **60 of 228** diverged. Each divergence is recorded in
`cli-differential.txt` with both engines' outputs:

| Divergence at baseline | Cases |
| --- | --- |
| A node with its own `type` property (local variables, fields) printed with two `type` keys by Rust | 12 |
| Integer division and `%`: Kotlin `7 / 2 = 3.5`, Rust `3` | 5 |
| `Long.MAX_VALUE + 1` and `* 2`: one engine wrapped, the other widened | 10 |
| `sqrt(-1)`, `exp(1000)` in JSON: Kotlin crashed in Gson | 1 |
| Invalid regex `'['`: Kotlin printed the JDK's three-line message (`…near index 0`, `[`, `^`), Rust one line | 5 |
| `substring` bounds: different messages; Rust panicked on a negative length; `Long.MAX` length threw in Kotlin | 15 |
| `DELETE`, `REMOVE`: Kotlin printed an uncaught `NotImplementedError` stack trace, Rust a one-line error | 10 |
| A path that does not exist (`no such graph`), and `CREATE` | 2 |

**Candidate:** **228 of 228** identical.

Committed regressions, one per class:
- the differential itself;
- `ArithmeticExpressionTest` and `scalar_dialects.rs` (arithmetic, overflow, `substring`
  incl. the `Long.MAX` boundary);
- `node_tostring.rs`;
- `QueryCommandTest` (non-finite values in JSON, and a graph path that does not exist).

**Different by design or unsupported (recorded, not counted as drift)**
- **A container file given as the graph:** the Rust CLI reads `.graphite` files and the jar
  reads directories only, so only the exit code is compared.
- **Temporal functions:** `date()` and the others exist only in the Rust engine.
- **Rust-only HTTP surface:** `/api/capabilities`, the hierarchy routes, and the
  `offset`/`nextOffset` fields. The HTTP parity suite (1,046 cases) marks them as reviewed
  Rust-only surface.
- **The `"single"` graph-id placeholder:** the Kotlin server emits it; HTTP parity notes
  and skips those 20 rows.

**Not proven.** The differential and HTTP parity cover their queries; they do not prove the
two engines equivalent.

## 5. Query service: paging, wide hierarchy, capabilities

**Reproducer** (`query-service.txt`): each revision's own `graphite serve` over the acme
graph.

| Request | Baseline `70f908c` | Candidate |
| --- | --- | --- |
| `GET /api/capabilities` | 404 | 200: dialects, paging bounds, functions, hierarchy bounds, per-graph producer, format and recorded facts |
| `GET /api/graphs/acme/hierarchy?type=java.lang.Object&limit=3` | 404 | 200, first page of subtypes |
| `…/cypher?…&limit=5&offset=5` | `offset` ignored: the first page again | the second page, `nextOffset: 10` |
| `…&offset=10000000` | the first page again | 400: a page must end within the first 50,000 rows |

**Committed tests** (`backend/explore/src/routes.rs`)
- **`cypher_pages_concatenate_to_the_whole_result_and_say_where_the_next_starts`:** pages
  concatenate to the whole result; `total` counts the rows read; a query's own LIMIT ends
  the pages (including `LIMIT 2` with page 2); the single lookahead row (review: division by
  zero in the row after the probe); a computed `LIMIT 2+6`; `UNION ALL` paged by 1; a
  deduplicating `UNION` whose branch repeats rows, including one row 17 times before the
  second (review: pages lost `2` and repeated `3`), with every page size's pages compared to
  the complete result; and the 50,000-row window.
- **`backend/cypher/tests/union_paging.rs`:** the same distinct prefix with and without the
  probe, a branch read to its end within the row cap, and a branch whose distinct prefix
  cannot be established within the cap failing instead of answering a short page.
- **`hierarchy_walks_share_the_query_guard_and_a_visit_budget`:** 429 under a saturated
  guard, plus the budget and a cancelled token.
- **`a_wide_hierarchy_pages_to_completion_and_stops_at_its_budget_or_deadline`:** through
  the HTTP service, 3,000 types (1,500 wide at depth 1) page to completion in 3 pages, in
  level/name order, each type once with its parent. A 500-visit budget stops the walk
  (400). An expired deadline returns 504.
- **`the_hierarchy_walk_is_bounded_resumable_and_says_when_a_type_is_unknown`.**
- **MCP smoke:** `backend/bench/mcp-smoke.py`, 39 checks including `capabilities`,
  `hierarchy` and `cypher` with `offset`.

**Limits (stated, not hidden)**
- **Paging window:** paging ends at row 50,000 (`maxWindow`).
- **Duplicate-heavy UNION:** a deduplicating UNION branch is read until it yields the
  distinct rows a page needs, up to 8 windows or 50,000 rows, whichever is more. A branch
  that still cannot establish them fails the query (`A UNION branch repeats its rows too
  often to page`); it never answers a page that lacks rows.
- **Fanout mode:** fanout mode refuses `offset`.
- **Hierarchy budget:** a hierarchy request examines at most 100,000 entries.

## 6. Memory and scale: node ids and index sizing

**Reproducer** (`repeated-builds.txt`): four builds of the same input (`core-1.0.0-SNAPSHOT.jar`)
in one JVM, each saved in turn.

| Build | Baseline ids / `graph.nodeoffsets` | Candidate ids / `graph.nodeoffsets` |
| --- | --- | --- |
| 1st | 1–30,957 / 247,672 B | 1–30,957 / 247,672 B |
| 2nd | 30,958–61,914 / 495,328 B | 1–30,957 / 247,672 B |
| 3rd | 61,915–92,871 / 742,984 B | 1–30,957 / 247,672 B |
| 4th | 92,872–123,828 / 990,640 B | 1–30,957 / 247,672 B |

**The remaining contract, made explicit.**
- The per-node offset index is still sized by the largest id: 8 bytes of header plus 8
  bytes per id. Numbering each build from 1 is what keeps it proportional to the node count.
- The JVM writer accepts sparse ids and pays for them. `SparseNodeIdTest`: ids 1, 7 and
  1,000,000 read back exactly in both load modes, and the index is 8,000,016 bytes.
- The Rust JSON importer refuses sparse ids (`sparse_node_ids.rs`).

Tests: `NodeIdNumberingTest`, `NodeIdAllocationTest`, `SparseNodeIdTest` and
`sparse_node_ids.rs`.

**Performance acceptance: unavailable.**
- No matched CPU/RSS/heap measurement on representative persisted multi-graph workloads
  was taken for this change.
- The hosted fixture64 gate records report-only timings, which are not acceptance evidence.
  For example, `real64-build-save` was 369,112 ms on base and 329,897 ms on head in run
  46001831978.
- The single-graph numbers above show the index-size effect only.

## 7. Engineering: scheduling-sensitive tests and verification time

| Test | Scheduling condition that failed it | Deterministic replacement |
| --- | --- | --- |
| `CypherExecutionBudgetTest` › execution context cancellation stops graph-free range and unwind work | a second thread cancelled after `Thread.sleep(20)`; on a loaded runner the query finished, or had not started, first | the query cancels itself at its own third checkpoint (`CypherCancellationSignal` callback); asserts the stop at that checkpoint |
| `ExploreGraphArchitectureTest` (serve command blocks until interrupted) | `Thread.sleep(2000)` "to let it start"; a slow runner interrupted before the server joined | spins until the thread is `WAITING` in the server's join (60 s ceiling), then interrupts |
| `tck/src/runner.rs` probe start | `ETXTBSY`: a freshly written probe binary still open in a child another test thread was forking | `spawn_retrying_busy` retries the spawn (10 ms, at most 50 times); test included |
| `benchmark/src/native.rs` stage test | bound the fixed port 18080, so it failed whenever anything else held it (a local server, a parallel test) | the stage takes its port; the test binds an ephemeral one |
| C4 warm-up route tests (`routes.rs`) | waited on real idleness time (`C4_WARM_QUIET`) | `freeze_clock` / `advance_clock` drive the idleness clock |

**Verification time.** `.github/scripts/verify-affected.py` maps the changed files to the
crates and Gradle projects that can observe them, and runs the Rust and JVM groups in
parallel. Some plans:

| Change set | Checks planned |
| --- | --- |
| docs only | none |
| `backend/explore` route | `graphite-explore`, `graphite-cli` |
| `backend/storage` reader | storage, cypher, explore, cli, tck |
| `frontend/jvm/webgraph` | `:webgraph`, `:query`, `:explore` |
| `frontend/jvm/core` | all 7 Gradle projects |
| build files, or `--all` | everything, including `npm test` |

**Not addressed.**
- This shortens the first feedback loop for a narrow change. It does **not** make the
  complete verification path faster.
- No comparable before/after records of the complete path exist, so none are claimed.
- The full path still runs in CI and with `--all`.

## 8. Framework-semantic extension boundary

This is a **design boundary**. This PR adds no framework-semantic layer, and documenting the
boundary is not counted as an implementation.

The supported extension contract:
- the `GraphiteExtension` SPI in `:sootup` (endpoint and annotation processing per class);
- fold rules (`docs/constant-folding.md`);
- Cypher queries over the persisted node schema.

Framework semantics beyond those (for example, dependency-injection wiring or
routing tables as first-class facts) remain the extension's or the query's job (README,
"Extension Mechanism").

---

## Review findings of #229

| Finding | Fixed in | Regression test |
| --- | --- | --- |
| Truncated manifest accepted as an inventory | `d8c786d`, `946f117` | `a_manifest_cut_short_or_edited_certifies_nothing` (Rust), `a manifest cut short or edited certifies nothing` (JVM), `pack.rs` |
| Fallback publication deleted the original first | `d8c786d` | `a directory that cannot be renamed keeps its earlier graph and the save is refused` |
| Offset enlarged the retained rows | `d8c786d` | route test, 50,000-row window cases |
| Hierarchy outside the guard, unbounded | `d8c786d` | `hierarchy_walks_share_the_query_guard_and_a_visit_budget`, `a_wide_hierarchy_pages_to_completion_and_stops_at_its_budget_or_deadline` |
| `nextOffset` past the query's own LIMIT | `d8c786d`, `83ff3ed` | route test (LIMIT 2 with page 2 and 5) |
| `total` from the requested offset | `d8c786d` | route test (offset 20) |
| `verify-affected --all` with no changes | `d8c786d` | `test_all_checks_everything_even_with_no_changed_paths` |
| Completeness evidence of another generation | `d8c786d` | Rust and JVM generation tests |
| `substring` length overflow in Kotlin | `02891c2` | `ArithmeticExpressionTest`, `scalar_dialects.rs`, `parity-cli.py` |
| Second lookahead row failed a valid page | `83ff3ed` | route test (division by zero past the probe) |
| Transpose stamped with another generation | `c5393c0` | `a graph replaced after load never publishes its transpose for the new graph` |
| Expression LIMIT ended the pages | `28e761b` | route test (`LIMIT 2+6`, `LIMIT 2+1`) |
| UNION page window per branch | `28e761b` | route test (UNION ALL, deduplicating UNION with repeats) |
| Deduplicating UNION past the re-run cap answered a short page | the commit after `2b5496c` | route test (one row 17 times, every page size), `union_paging.rs` (explicit failure past the cap) |

The latest-head correctness/OOM gate is in CI. Its only failing check is the two
reviewed node-numbering transitions (`shape-id-lookup`, `shape-traverse-filtered`), which
need the separate approval of `benchmark/src/transitions.rs` on `main`.
