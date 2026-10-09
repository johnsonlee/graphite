# Declared-type corpus identity audit

The array-dimension correction recovers **27 Hive and 64 Tika method keys** that
previously collided. Kotlin compiler retains the same method count. These exact
changes were independently derived from the pinned classfiles, then checked against
actual baseline and candidate graph builds. They are correctness changes, not a
relaxation of the corpus gate or evidence of a performance improvement.

## Inputs and method identities

| Corpus | Artifact | SHA-256 |
|---|---|---|
| Hive | `org.apache.hive:hive-exec:4.0.0` | `232d67c5d2ff54806944bb5b7402eaf1ebb81f11dbe4fd51bc5604a8e0c0bdad` |
| Tika | `org.apache.tika:tika-app:2.9.2` | `87e06f88c801fcb2beae5f15e707241edb14da468a154ad78be4e31ff982c3da` |
| Kotlin compiler | `org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21` | `9fa8cdd1de0dccffe154c997d423ec6b5f53cd6d9177e3a77a9b0de03fb1bc81` |

| Corpus | Legacy unique methods | Accurate unique methods | Recovered keys | Colliding groups | Affected classes |
|---|---:|---:|---:|---:|---:|
| Hive | 404,016 | 404,043 | 27 | 25 | 16 |
| Tika | 312,788 | 312,852 | 64 | 56 | 36 |
| Kotlin compiler | 249,669 | 249,669 | 0 | 0 | 0 |

[The complete collision inventory](declared-types-corpus-collisions.json) lists all
81 affected owner/method groups and every accurate key in each group. For example,
Hive's shaded `ByteArrays.radixSort(byte[])` and `radixSort(byte[][])` previously
shared one key. Some groups contain more than two declarations, explaining why the
number of recovered keys exceeds the number of groups.

The independent [classfile reader](declared-type-identity-audit.py) uses only Python's
standard library. It reads the constant pool and every declared method descriptor,
without importing Graphite, SootUp or ASM. It applies the frontend's class-entry
rule: skip `module-info.class` and entries whose path does not match their binary
class name. There were zero classfile parse failures; path mismatches were 8 for
Hive, 1,121 for Tika and 0 for Kotlin compiler.

For each declaration it computes `owner.name(parameterTypes)` twice: with every
array rank collapsed to one, and with the complete rank retained. This matches the
existing graph method-index key, which does not include the return type. The
independent legacy counts exactly reproduce all three pre-change gate baselines.
This audit does not change that existing return-type behavior; the new declaration
table separately keys methods by their complete JVM descriptor.

The script's optional `javap` pass verified **172 distinct method descriptors in all
52 affected classes**, including constructors. All 172 accurate overload keys
(Hive 52, Tika 120) were also found in the sorted method metadata exported directly
from the built candidate graphs. Replaying the committed script reproduced the
complete committed collision inventory exactly. Reproduce the inventory with local
copies of the artifacts above:

```sh
python3 docs/declared-type-identity-audit.py \
  --jar hive=/path/to/hive-exec-4.0.0.jar \
  --jar tika=/path/to/tika-app-2.9.2.jar \
  --jar kotlin-compiler=/path/to/kotlin-compiler-embeddable-2.0.21.jar \
  --output /tmp/declared-type-identity-audit.json --javap
```

For an individual affected group:

```sh
javap -J-Xmx512m -p -s -classpath /path/to/hive-exec-4.0.0.jar \
  shaded.parquet.it.unimi.dsi.fastutil.bytes.ByteArrays
```

## Saved-graph verification

The retained baseline was built from `4f2ccf33`; the candidate used the compiled
PR #174 implementation at `cfcfb191` (the subsequent `d8a5dd49` change is lint only).
Both used Homebrew OpenJDK 17.0.20.1, `-Xms512m -Xmx4g`, the default mmap builder,
`buildCallGraph=false`, `extractAnnotations=false`,
`trackCrossMethodFunctionalDispatch=false`, all other loader defaults, and
`GraphStore.save(graph, directory, 2, true)`. Builds used identical artifact hashes.
The investigation ran concurrently with other development work and makes **no
latency, CPU, RSS or regression-recovery claim**.

Actual graph counts matched the independent method inventories. Every other count
below was identical between baseline and candidate:

| Corpus | Nodes | Source edges | Persisted edges | Call sites |
|---|---:|---:|---:|---:|
| Hive | 5,992,914 | 6,597,267 | 6,376,682 | 1,443,886 |
| Tika | 3,901,103 | 4,510,016 | 4,353,588 | 1,006,172 |
| Kotlin compiler | 3,292,214 | 3,906,617 | 3,785,858 | 922,876 |

Counts alone were insufficient to establish topology preservation. Decoding every
BVGraph successor and corresponding edge-label byte initially found different
adjacency at 111 Hive, 369 Tika and 56 Kotlin compiler source-node IDs. The full raw
differences were retained; they were not suppressed or used to rewrite a baseline.
Node-index and node-type-index files were byte-identical in all three pairs.

Inspection showed reordered field-node allocation within existing declaring
classes. A separate comparison constructed an explicit bijection from each unique
`(declaringClass, fieldName)` declaration, requiring identical staticness and
rejecting duplicate or missing declarations. It inspected all fields in the
affected owners, including fields without incident edges, to close the permutation:

| Corpus | Affected owners | Field declarations inspected | Field IDs permuted | Remaining decoded labeled-edge differences |
|---|---:|---:|---:|---:|
| Hive | 8 | 152 | 58 | 0 |
| Tika | 21 | 217 | 145 | 0 |
| Kotlin compiler | 5 | 107 | 39 | 0 |

After applying only those recorded field-ID bijections in the comparison harness,
all 6,376,682 / 4,353,588 / 3,785,858 decoded labeled edges respectively matched
exactly. The stored graphs and corpus gates were not normalized. BVGraph has one
additional reserved vertex beyond the node counts above.

Three field-type changes occurred in those inspected owners. `javap -p -s`
independently confirms their corrected erasures:

| Declaration | Legacy graph type | Candidate graph type | Classfile descriptor |
|---|---|---|---|
| Hive `org.apache.hive.org.apache.datasketches.sampling.VarOptItemsSketch$Result.items` | `T[]` | `java.lang.Object[]` | `[Ljava/lang/Object;` |
| Kotlin `org.jetbrains.kotlin.com.intellij.util.containers.TreeTraversal$GuidedIt.curChild` | `T` | `java.lang.Object` | `Ljava/lang/Object;` |
| Kotlin `org.jetbrains.kotlin.com.intellij.util.containers.TreeTraversal$GuidedIt.curParent` | `T` | `java.lang.Object` | `Ljava/lang/Object;` |

For example:

```sh
javap -J-Xmx512m -p -s -classpath /path/to/hive-exec-4.0.0.jar \
  'org.apache.hive.org.apache.datasketches.sampling.VarOptItemsSketch$Result'
javap -J-Xmx512m -p -s -classpath /path/to/kotlin-compiler-embeddable-2.0.21.jar \
  'org.jetbrains.kotlin.com.intellij.util.containers.TreeTraversal$GuidedIt'
```

The separate, smaller sample-corpus proof covers its complete fact multiset: 84
explicit type-text substitutions, 12,725 unique facts / 12,894 counted facts and no
remaining differences. See [the declaration verification notes](declared-types.md#verification).
It is not substituted for the real-corpus checks above.

## Observed saved sizes (historical GTY01)

These are paired file-size inventories, not revised storage budgets. The gate's
`persistedBytes` excludes `graph.callsite-string-index`; total directory bytes
include it. The authoritative digest binding adds a property, while compression
statistics can cause small textual size differences between saves. Accordingly,
these observations should not become exact byte-count assertions.

The one-time CI migration pins the verified method counts and these paired byte
deltas, retaining the existing 4 KiB tolerance for textual metadata variability.
It selects the migration only for the exact old/new harness and comparator hashes.
All timing and resource constraints remain unchanged; later comparisons use the
normal baseline rather than this migration.

| Corpus | Baseline gate bytes | Candidate gate bytes | New `graph.types` bytes | Baseline total bytes | Candidate total bytes |
|---|---:|---:|---:|---:|---:|
| Hive | 540,289,070 | 642,128,195 | 101,837,620 | 592,676,730 | 694,515,919 |
| Tika | 344,993,419 | 406,380,064 | 61,379,711 | 383,733,983 | 445,121,020 |
| Kotlin compiler | 318,590,673 | 385,975,359 | 67,378,924 | 356,945,565 | 424,330,099 |

Local full inventories, raw adjacency differences, explicit field permutations,
`javap` outputs and retained graphs are under
`/tmp/graphite-types-corpus-audit/`. Those temporary artifacts are not required to
reproduce the method-key inventory from the committed script and pinned JARs.
Only the compact collision inventory is committed; million-line method listings
and multi-gigabyte graph files are deliberately excluded.

## GTY03 fresh-corpus correctness follow-up (2026-10-09)

Commit `ae316747a573739b6a55c98458b2f3fa9f98130f` stores declared-type text as IDs
in the existing `graph.strings`, with a binding to its actual serialized SHA-256.
Fresh builds of the same three pinned JARs above pass complete declaration and
core-graph equivalence checks against the retained GTY02 references. This adds to,
and does not replace, the earlier method-identity audit. **It establishes
correctness within the scope below, not performance recovery.**

The builds use the retained corpus configuration: Homebrew OpenJDK17.0.20.1,
`-Xms512m -Xmx4g -XX:ActiveProcessorCount=4`, mmap builder,
`buildCallGraph=false`, CHA, and `GraphStore.save(graph, directory, 2, true)`.
They are fresh JAR ingestion and usable saves, not type-file substitutions. All
three build processes exit0. The supplementary audit confirms the frozen
classpath order and exact runtime inventory; relevant JVM/compiler overrides are
absent. Existing fixtures and historical measurements remain untouched.

An independent decoder checks each type file's authoritative property binding,
metadata digest and actual serialized-string digest. A dependency-only Java
serialization exporter supplies the global strings. Canonicalization preserves
all four section counts, complete member keys, text, type IDs, references and list
order. All declaration-row digests equal the GTY02 references; each file's separate
metadata-bound digest is retained. The candidate production reader additionally
loads both formats and compares the complete `DeclaredTypeTable` and each corpus's
selected full typed generic-query result against independently retained values.

| Corpus | Type expressions | Declared fields | Declared methods | Declared classes |
|---|---:|---:|---:|---:|
| Tika | 52,465 | 108,500 | 317,985 | 32,006 |
| Hive | 74,661 | 127,957 | 452,507 | 38,991 |
| Kotlin compiler | 66,978 | 59,130 | 264,628 | 24,941 |

Global string IDs change when declaration text joins the sorted table. The new
core proof therefore resolves every persisted string reference and retains every
other node/metadata payload value, canonicalizing branch-member sets by value. It checks every metadata method row, including
complete return descriptors, rather than comparing a potentially collapsing
method map. Fresh field allocation order differs: the proof constructs a closed
bijection using each complete `(owner, name, erased type, staticness)` field key.
Only this proven mapping applies to node references, including CallSite receiver
and arguments, branches, comparisons and edges. Non-field identities remain exact;
unknown or unexplained differences fail. The stored graphs are not normalized.

| Corpus | Materialized nodes | Complete field keys | Field IDs permuted | Decoded labeled edges | Unmatched edges |
|---|---:|---:|---:|---:|---:|
| Tika | 3,901,103 | 114,244 | 20,764 | 4,353,588 | 0 |
| Hive | 5,992,914 | 142,266 | 20,015 | 6,376,682 | 0 |
| Kotlin compiler | 3,292,214 | 61,095 | 2,899 | 3,785,858 | 0 |

The topology pass checks every BVGraph vertex, including each graph's additional
reserved vertex, sequential and random-access successors, every cumulative label
prefix and every labeled edge. Branch-definition, call-site-ordinal and resource
sidecars remain byte-identical in these pairs. The Java topology result binds the
upstream full-field-key proof, mapping, parser hashes and graph inputs before and
after execution. All seven core/topology phases and all ten declaration/query
inspection phases exit0, with empty owned process groups and final pins `PASS`.
The inspection receipt's original `CORE_PROOF_PENDING` label is preserved; the
subsequent core receipt closes that obligation with `PASS_FULL_CORE_AND_TOPOLOGY`.

A separate server correctness run covers all three graphs in every request. It
uses C=accepted4f, A=the retained163 JVM with GTY02 graphs, and B=`ae316747` with the
fresh GTY03 graphs, matching8GiB/APC4 and client/server concurrency1. Six cases per
arm comprise exhaustive node/method binding-presence groups and12 independently
selected full-tree sentinels across FieldNode, ParameterNode, ReturnNode and Method.
The independent raw audit passes all **18 complete typed responses**, including
exact envelopes, row multisets, ordered nested arrays and graph provenance. Each
of the six A/B body hashes also matches. C's explicit absence of declaration
properties remains correct.

The oracle joins actual materialized members by full owner/name/JVM descriptor;
it does not equate declaration rows with live Method rows. A/B expose312,852,
404,043 and249,669 Method rows respectively. Unbound FieldNodes remain5,744,
14,309 and1,965; their rendered and structured declaration properties stay absent.
All three servers terminate normally by SIGTERM with empty owned groups and no
cleanup errors.

Evidence is retained under:

- `/tmp/graphite-shared-performance/fresh-build-1/`: build logs, file inventories
  and `supplemental-input-audit.json`.
- `/tmp/graphite-shared-performance/typed-inspection-1/`: both complete canonical
  declaration receipts per corpus, typed-query logs and final pins.
- `/tmp/graphite-shared-performance/core-proof-1/`: every field permutation,
  complete node/metadata proof, topology result, input bindings and final pins.
- `/tmp/graphite-member-oracle-v3-preparation/real3-1/`: independent shared-string
  exports, full member joins and expected query bodies.
- `/tmp/graphite-shared-member-server/results/run-1/`: all raw server responses,
  cleanup records and `root-independent-response-audit.json`.

These are correctness preparations, not a matched construction benchmark,
loading benchmark, repeated-request latency distribution or saturation test.
They provide no p50/p95, CPU or RSS recovery claim. The prior cumulative resource
and latency regressions, native/multi-shard validation and required CI/PR gates
remain separate obligations; no threshold or corpus gate is relaxed here.

### GTY03 CI persisted-size transition

The exact `067f0cab4a01cdaea95d92d7e09048fd96ee6f9b` candidate and accepted
`4f2ccf33b969e684972e56b5e810034e6e67c1b3` corpus tests completed in
[CI run 37882909532, job 113666472741](https://github.com/johnsonlee/graphite/actions/runs/37882909532/job/113666472741).
The initial correctness outputs establish these `persistedBytes` values for
the same pinned JARs (the separately reported CallSite index is excluded):

| Corpus | Baseline bytes | GTY03 bytes | Declared migration delta |
|---|---:|---:|---:|
| Tika | 344,993,420 | 363,142,143 | 18,148,723 |
| Hive | 540,289,068 | 568,781,406 | 28,492,338 |
| Kotlin compiler | 318,590,672 | 339,774,950 | 21,184,278 |

Reverse-order deltas are 18,148,725 / 28,492,337 / 21,184,279 bytes, within
two bytes of the initial values and the unchanged 4,096-byte tolerance.

These replace the active **storage transition** values for GTY03. The original
GTY01 values remain in the historical transition object. Exact graph-shape
counts and the 4,096-byte storage tolerance remain unchanged. A separate option
selects the GTY03 correctness transition; it cannot silently authorize another
format or turn off a size check.

The complete fresh-corpus core/declaration/member proofs above provide the
semantic basis. A source audit additionally confirms that `GraphStore.kt`,
`StringTable.kt`, `NodeSerializer.kt`, and the declaration collection, writer,
binding and digest functions have identical bytes from the proven `ae316747`
implementation through `067f0cab` and `0cdb5dd9`. Loader/query changes do not
alter these writer paths. The retained audit is
`/tmp/graphite-multigraph-ci-storage-transition-source-audit.json`; the original
CI failure, logs and size errors remain under
`/tmp/graphite-pr174-067-large-corpus-failure/`.

The original CI comparison still records a failure; it is not rewritten as a
pass. Its per-corpus timing observations are historical single-graph evidence
and have no performance-acceptance standing under the multi-graph-only rule.
The storage correction establishes no latency, CPU or RSS recovery. Required
multi-graph construction, loading and query evidence remains separate.

### Multi-graph CI acceptance wiring

Single-graph entry points now run correctness assertions without performance
sampling. Their success does not satisfy a performance gate. The native query
catalog contains 39 requests: 15 original full-response cases and 24 independently
derived cases for slow shapes, wrapped discovery, graph routing and type presence.
Global requests target all 64 graphs; the two routing requests target the declared
pair while the complete registry remains loaded.

Four DATAFLOW queries have no `ORDER BY`. They use complete independently derived
legal result multisets, including typed values, provenance, multiplicity, LIMIT
counts and probe totals. Their expected digest identifies that multiset, not an
observed response. The verifier compiles the multiset before serving requests and
checks every completed response during the measured boundary.

The pressure runner retains each request and failure, executes fixed C/A/B/B/A/C
cells with four independently replenished workers, and reports per-case request
p50/p95 in milliseconds before relative changes. Each case has two warmup and
20 measured requests per cell. CPU and RSS limits remain separate constraints.
Source/runtime identities, independently established answers and matching real
persisted fixtures are prerequisites; a parent can reuse C only when all identity
fields are present and exactly match the accepted artifacts.

This wiring is not a completed CI performance result. The workflow still requires
an actual `native-pressure-producers/packet.json`; its absence yields UNAVAILABLE,
not PASS. Complete fixture semantic-equivalence claims remain mandatory. The local
all-64 comparison records explicit source-backed corrections and limitations, so
it cannot be relabelled as that stronger claim. Construction, loading, JVM query
pressure and the native CI producer remain outstanding acceptance work. The
historical CI failure and the local correctness evidence remain separate records.

### Exact-revision native artifact production

The CI job now invokes `produce_native_pressure_artifacts.py` sequentially for C,
A when distinct from C, and B. Each uses a clean ordinary Git checkout and the
same JDK, Rust toolchain and four pinned input JARs. The accepted C revision is
fixed to `4f2ccf33b969e684972e56b5e810034e6e67c1b3`; the parent aliases C only when
the full base revision matches it. Every distinct arm builds its own writer and
native executable, generates its own 64 graphs, and runs its writer's complete
provenance verification. Candidate graphs are never substituted for C's graphs.

Gradle, writer and build subprocess JVMs are capped at 4 GiB; the version probe
uses 512 MiB. Rust compilation uses two jobs and the recorded host target. Raw
source, JAR, runtime and fixture hashes, toolchain identity, commands, logs and
owned-process cleanup records are retained. Final input/configuration checks
reject changes even after earlier phases succeeded. The source checkout is not
modified to make an old writer support a new format.

A successful producer reports `ARTIFACTS_COMPLETE_INDEPENDENT_PROOFS_PENDING`,
with `acceptanceEligible: false`. This records artifact production only. It does
not generate the downstream pressure producer packet, certify complete semantic
equivalence, establish independent query oracles, or report performance recovery.
Those independent audits and packet assembly are still required before CI can
execute and accept the pressure comparison. No actual run of this new CI stage
is claimed by the source change; existing local C/B graph receipts were used only
to verify the portable metadata parser without rebuilding their corpora.

### Portable native query expectations (2026-10-10)

The repository now contains all 39 query expectations under
`.github/scripts/fixtures/native-pressure-oracles/`. The index binds each exact
request and target graph list to accepted/candidate payloads, the four source
JAR hashes, original source revisions, and archived independent audit records.
There are 38 unique expected payloads and eight archived audit records. Original
absolute paths remain historical provenance only; the loader never reads them.

Import verified all 78 actual full response bodies against the established
expectations. The historical 15 retain the original catalog digests, except the
candidate schema histogram's explicitly separate completed independent authority.
The 24 additional cases retain persisted-graph-derived expectations. Four DATAFLOW
cases retain complete legal multisets rather than one observed LIMIT response;
their policy does not claim encounter order. The original schema catalog is not
repinned to match the candidate's first response.

CI checks this bundle against the resolved source-JAR manifest before building
the independent runtime/graph arms. The loader emits
`PASS_VERSIONED_EXPECTATIONS_FRESH_EXECUTION_PENDING`, not a current runtime or
performance PASS. All payloads, audit records, the loader and its tests are in the
preparation control hash closure. Parent-to-accepted aliasing still requires
actual artifact identity outside this loader.

Validation: 12 portable-loader tests, six legal-policy tests, 90 existing pressure
tests and 174 JavaScript gate tests pass. Replay against the newly loaded bundle
checks 78 retained actual bodies and 6,522 rows. Workflow YAML parsing and all 11
native job shell blocks pass syntax checks. This is portable expectation and
control validation only; no new server run, fresh fixture semantic-equivalence
proof, final producer packet, performance acceptance, or successful CI run is
claimed. Fresh independent artifact audits and HTTP execution remain required.

### Independent native artifact audit (2026-10-10)

CI now runs `audit_native_pressure_artifacts.py` after each distinct C/A/B
artifact producer. The auditor does not import the producer. It checks the
requested clean source revision, raw source/runtime/writer hash chain, original
build output locations, exact bounded build commands, selected JDK/Rust tools,
configuration closure, seven owned successful phases and complete logs. It also
rehashes the closed graph directory, checks all 64 own-writer paths and source JAR
identities, and repeats identity checks at the end. Missing files, extra files,
symlinks, mismatched artifacts and unsuccessful cleanup fail the audit.

Success is `PASS_ARTIFACTS_READINESS_AND_QUERY_PROOFS_PENDING`. Readiness, query
correctness, complete cross-arm semantic equivalence and performance acceptance
remain explicitly false. The producer's original status is preserved. No old
runtime is relabeled as the new source revision.

Fourteen new audit tests pass, including altered binary/JAR/log contents, a
different writer or graph directory, a missing verify phase, an increased heap,
remaining children and metadata changes during the audit. All 104 pressure
Python tests and 174 JavaScript gate tests pass. Separately, the new fixture and
phase validators read the existing real C/B outputs: 128 graphs, 2,500 closed
graph files and 14 raw build/writer phase records pass. That actual replay covers
these validator functions on historical artifacts; it is not a new execution of
the portable producer or the entire new auditor on a fresh CI build. No server
or performance workload ran during this validation.

### Fresh native query execution and raw response audit (2026-10-10)

After each distinct C/A/B artifact audit, CI now starts that arm's actual native
executable with its own 64 graph directories, then executes the 39 versioned
queries. Each request has a complete-body deadline and size cap. The original
request, response bytes, failures, readiness attempts, process owner and cleanup
are retained, including partial output after a failure. Queries execute once
each, sequentially; this stage does not measure performance. The separate
continuous-pressure workload remains the performance acceptance path.

Readiness expectations come from persisted data before launch: actual node
records from `graph.nodedata`, arcs from `forward.properties`, methods from
`graph.metadata`, and verified fixture call-site counts. BVGraph's `nodes` is an
ID capacity and includes shard gaps; an initial real-data replay exposed that
distinction. The initial mismatch remains recorded, the reader now uses the
actual record count, and a sparse-ID correctness test protects that behavior.
The corrected reader matches all readiness statistics across 128 historical
C/B graphs, including their different method counts. This checks the new reader
against real saved data and captured readiness, not a fresh server execution.

`audit_native_query_correctness.py` separately reconstructs persisted readiness,
reloads versioned expectations, and checks all 39 raw response bodies, request
digests, scope, journal entries, runtime identity and owned cleanup. It does not
import the execution controller. Neither this audit nor the execution record
claims full fixture semantic equivalence or performance acceptance. Historical
expectation revisions remain distinct from the newly executed runtime revision.

Eleven execution/audit tests use tiny artifact fixtures, mocked transport and the
unaltered versioned response payloads; they start no child processes. They cover
complete catalog execution, wrong/missing responses, query replacement, input
mutation, failed cleanup and sparse node IDs. Corpus matching is bypassed only
in these tiny protocol fixtures; the portable-oracle tests separately enforce
the real JAR hashes. The final producer packet and source-correction equivalence
policy remain pending, so this wiring alone cannot pass the performance gate.


## CI producer evidence bundle (2026-10-10)

The CI workflow now writes `native-pressure-producers/packet.json` through
`assemble_native_pressure_producers.py` after each distinct C/A/B writer and
runtime has passed its artifact audit and fresh39-query correctness audit.
A aliases C only when the parent revision is exactly accepted4f; the artifact's
original accepted-baseline role remains recorded separately from the parent role.
All distinct arms must use the same direct toolchain and source-input manifest.

The assembler recomputes each raw query audit, checks complete source/runtime/
writer links, preserves all39 request scopes and response/oracle bindings, and
rejects conflicting input pins. It launches no process and cannot create a
performance pass. The new `graphite.native-pressure.producer-bundle.v1` explicitly
retains `completeSemanticEquivalence=false`, `performanceAcceptance=false` and
`acceptanceEligible=false`. Original artifact audits keep their original partial
status; successful queries do not retroactively rewrite them.

Preparation revalidates the bundle and reports the completed per-arm inputs, but
still returns UNAVAILABLE without a pressure plan while full cross-arm core,
topology and index equivalence, or the remaining independently source-proven
corrections, are missing. The existing strict legacy plan proof gate is unchanged.
This closes the missing handoff-file implementation; it does not complete the
semantic-equivalence proof, execute real CI or establish performance acceptance.

Validation:11 new protocol tests pass; the native-related Python group passes100
and the pressure-related group115 (the groups overlap). All174 JavaScript tests
pass. The workflow parses as YAML, all101 shell run blocks pass `bash -n`, and the
updated preparation control manifest matches every reviewed source file. The new
protocol tests exercise real versioned response payloads with tiny mocked graph/
process fixtures for correctness only, including forged audit, wrong input,
wrong parent alias, toolchain drift and false-equivalence rejection.


## Exact-head CI failures retained (2026-10-10, a21e8e3e)

Benchmark run `37973928467`, large-corpus job `113967638359`, completed both
accepted-baseline and candidate construction/save/load correctness pipelines.
All declared node, source-edge, persisted-edge, method and call-site counts
matched their reviewed expectations. The subsequent comparison failed its
unchanged 4,096-byte persisted-size tolerance:

| Corpus | Baseline bytes | Candidate bytes | Observed delta | Previously reviewed delta | Difference |
|---|---:|---:|---:|---:|---:|
| Tika | 344,993,420 | 363,218,271 | 18,224,851 | 18,148,723 | +76,128 |
| Hive | 540,289,069 | 568,960,397 | 28,671,328 | 28,492,338 | +178,990 |
| Kotlin compiler | 318,590,674 | 339,795,030 | 21,204,356 | 21,184,278 | +20,078 |

These are correctness-only artifact sizes, not performance samples. The old
reviewed deltas came from `067f0cab`; subsequent source changes include inherited
field bindings. The total alone does not prove the cause of each difference.
The test harness now emits a sorted `LARGE_CORPUS_FILE` inventory before deleting
the temporary output, including the separately accounted CallSite index. The
existing total, index accounting, comparison constants and tolerance are unchanged.
No size transition is accepted on the strength of this diagnostic change.

Raw CI evidence: artifact `11638550422` (`benchmark-large-corpus-174-1`), retained
locally at `/tmp/graphite-ci-a21-large-corpus/`; job log at
`/tmp/graphite-ci-113967638359.log`. Unit run `37973928466` also completed its
build/check step but failed the final 98% coverage threshold: Cypher 97.0648%,
Explore 97.3919%, Webgraph 97.4775%. GitHub exposed no artifacts for that run,
so the exact missed CI lines are unavailable. These failures remain open.

The retained attempt181 Cypher coverage report provides a diagnostic lead, not
an explanation proven for CI: its main-source files are byte-identical to a21
and it reports 6,647/6,780 covered lines (98.0383%). The CI percentage also
rounds to 6,647/6,848, a possible increase of 68 uncovered lines. CI compiles the
JMH source set, including newly named correctness entrypoints absent from that
old report. Fresh CI XML is required to establish the counted source set and
missed lines. The build workflow now generates XML/HTML alongside the log and
uploads them after the threshold check even on failure. No coverage threshold,
source exclusion or failure behavior has been relaxed.

## Correctness-driver control bindings and per-file CI accounting (2026-10-10)

At `d23`, benchmark run `37982075270` rejected the reviewed-control manifest
before executing several correctness jobs. The coverage-test seams changed
`SingleGraphCorrectness.kt` and `SyntheticQueryCorrectness.kt`, but their old
source hashes were still pinned. Candidate gate job `113995249102` and synthetic
correctness job `113995249059` both retained this failure. The correction updates
only those two source hashes and the workflow's hash of the eight-entry manifest;
the other six entries, verification commands, thresholds and result checks stay
unchanged. The new manifest SHA-256 is
`93c872af537ae477451db9d7a1397892cbd919a5596ac16ef3077fe8f6d7cffe`.

Validation: all 174 JavaScript protocol tests and 115 pressure-related Python
tests pass. The workflow parses, its 101 Bash blocks pass syntax checks, and all
eight file hashes plus the manifest hash match. This is a control-binding fix,
not a rerun or acceptance of the failed CI jobs. The exact changed-entry review
is retained at `/tmp/graphite-d23-correctness-controls-review.json`.

The completed large-corpus artifact from the subsequently canceled `8f` run
`37978314668` remains available as artifact `11640132261`. Its new per-file
inventory accounts for the complete observed persisted-size transition:

| Corpus | `graph.types` added | `graph.strings` increase | Metadata increase | Forward-file net change | Total persisted increase |
|---|---:|---:|---:|---:|---:|
| Tika | 12,700,028 | 4,551,866 | 1,492 | 93 | 17,253,479 |
| Hive | 17,850,784 | 9,178,720 | 652 | 93 | 27,030,249 |
| Kotlin compiler | 11,049,504 | 8,018,322 | 0 | 96 | 19,067,922 |

All values are bytes. Separately accounted CallSite indexes increased by 529,984,
917,496 and 565,936 bytes respectively and are excluded from the persisted totals
on both arms. Summing every emitted file and subtracting this index reproduces
each recorded total exactly. Other emitted file sizes match between the arms;
size equality does not establish content or semantic equality. The corresponding
old `067` expectation has no per-file inventory in this artifact, so this evidence
does not establish which code change caused the difference from that expectation.
The existing tolerance and expected deltas remain unchanged. The independent
accounting is retained at
`/tmp/graphite-8f-large-corpus/independent-file-delta-audit.json`.

A subsequent `acd11a69` run (`37982619222`) passed the candidate control gate but
synthetic correctness job `113996963656` still stopped at a duplicate inline
installer pin. The manifest already referenced the reviewed driver; the installer
retained its pre-coverage-test hash. That single inline hash now matches the same
reviewed source. A new protocol assertion enumerates all four standalone
correctness installer pins and checks each against its actual file and, where
present, its manifest entry. All 175 JavaScript tests pass. Executing the exact
installer shell block in a temporary candidate/base layout reproduces rejection
with the old pin, succeeds with the corrected pin, and installs byte-identical
Budgeted and Synthetic drivers. No JVM was launched. All 101 workflow Bash blocks
remain syntactically valid. The shell replay is retained at
`/tmp/graphite-acd11-installer-pin-shell-review.json`; the original CI failure log
remains `/tmp/graphite-acd11-synthetic-correctness.log`.
