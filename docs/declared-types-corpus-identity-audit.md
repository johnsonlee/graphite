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

## GTY05 CI storage transition (2026-10-10)

Candidate `b033a4a2d05f8bb485966db7bb6769c8e9e75163` completed both untimed
large-corpus persistence pipelines against accepted
`4f2ccf33b969e684972e56b5e810034e6e67c1b3` in
[CI run 37983621250, job 114000873666](https://github.com/johnsonlee/graphite/actions/runs/37983621250/job/114000873666).
The comparison failed because it still selected the historical GTY03 size
transition. Artifact `11641609467` retains the original logs, per-file inventories
and failure under `/tmp/graphite-b033-large-corpus/`.

Independent accounting of every emitted file gives the following exact byte
totals. `persistedBytes` excludes `graph.callsite-string-index` on both arms:

| Corpus | Baseline bytes | GTY05 bytes | Persisted increase |
|---|---:|---:|---:|
| Tika | 344,993,419 | 358,550,052 | 13,556,633 |
| Hive | 540,289,069 | 559,679,479 | 19,390,410 |
| Kotlin compiler | 318,590,673 | 330,684,523 | 12,093,850 |

| Corpus | `graph.types` added | `graph.strings` increase | Metadata increase | Forward-file net change |
|---|---:|---:|---:|---:|
| Tika | 13,552,964 | 2,082 | 1,492 | 95 |
| Hive | 19,384,352 | 5,312 | 652 | 94 |
| Kotlin compiler | 12,088,532 | 5,224 | 0 | 94 |

Hive's forward-file total includes `forward.properties` +95 and
`forward.offsets` −1 byte; the latter was initially omitted from the allowed
filename set and remains explicitly recorded in the corrected audit. The
separately checked CallSite index increases are 712, 544 and 768 bytes.
Every other emitted file size matches, which is not a claim of content equality.
The independent accounting is
`/tmp/graphite-b033-large-corpus/independent-file-delta-audit.json`, SHA-256
`74502fe39a1977ad5a0dd6be242807092577e50feb8aeae0be85aa56091c5ac7`.
The original base/candidate log hashes are respectively
`84a04740149cf4eedbd77af49dca15b2678d9e91477e9f2cbf2407bf31d226f7` and
`4232161d3b890d7398b6487b155b200d266a46899c606c16ba9691472071a8ce`.

The comparator adds a separate `--structural-types-transition` option for these
GTY05 deltas. It requires `--correctness-only` and rejects combinations with the
GTY01 `--shape-transition` or GTY03 `--shared-strings-transition` options. Both
historical transition constants remain unchanged. Only the workflow's exact
accepted4f branch selects the new option; ordinary same-format comparisons still
require equal shapes and sizes within 4,096 bytes. The transition keeps the same
exact base/candidate shape assertions, including the independently established
64/27 recovered method identities, and the same 4,096-byte tolerance.

Separately, the production writer from
`acd11a6989c9d501ffac3803c41e629466c8883e` produced a fresh 64-graph GTY05
corpus. Its production sources are unchanged at b033: the intervening diff
contains only the benchmark workflow, protocol tests and this audit document.
An independent decoded comparison against the prior writer
`42377d8c1967a59ec3b4a71835f4538e803b3555` establishes that all 520,009 prior
type rows remain the exact prefix, with 1,607 additional raw types used only by
member keys; every field, method and class binding remains equal across all 64
graphs. The retained record is
`/tmp/graphite-gty05-wire-validation-1/record.json`, SHA-256
`c63ac1d4d81bd9392b0a49f97279f76bb75dac512578f1b671e81f1a2bef9378`;
its independent review is
`/tmp/graphite-gty05-wire-validation-1/independent-review.json`, SHA-256
`ff809c4cf9e2a2fef1c4fa00be9ee8c88b023d39a7d73fffb6bfc669bf17926c`.
This proves the stated declaration comparison and wire/dictionary validity,
not complete accepted4f core/topology/index equivalence, source-to-declaration
completeness, or the bytecode origin of every appended row.

The original CI failure remains a failure; a replay under the new explicit
storage mode is a separate correctness result. No timing, CPU, RSS, heap,
coverage or performance threshold is waived. Single-graph corpus checks have
no performance-acceptance standing; representative multi-graph construction,
loading and query acceptance remains separate.

Validation of this comparator change passes all 179 JavaScript protocol tests.
Replaying the exact archived base/candidate logs with the GTY05 option passes
with `scope: correctness-only` and `performanceAcceptance: false`; replaying
those same logs with the unchanged GTY03 option retains all three original
size failures. Commands, source/log hashes and both results are retained at
`/tmp/graphite-gty05-storage-transition-validation-1/record.json`.

## Untimed routing workload verification (2026-10-10)

Candidate `6c9f9fee71592afdae0167e0ccff288bdf4c4493` passes JVM and Rust CI,
including the unchanged coverage checks, and the correctness-only large-corpus
storage comparison. Its
[routing job 114011754588](https://github.com/johnsonlee/graphite/actions/runs/37986345288/job/114011754588)
fails after the cold, warm and startup-prepared correctness comparisons all pass.
Artifact `11643069455` preserves those results and the original failure.

The trailing workload verifier still required a `latencyNanos` column, while the
untimed driver emits `measurementScope=correctness-only`. The verifier now accepts
that explicit untimed schema or the historical timing schema, rejecting headers
that contain both or neither. Every untimed row must declare `correctness-only`.
Canonical query IDs, shard identities, complete result records and graph-access
checks remain mandatory. No timing value is manufactured, and this verifier does
not establish performance acceptance.

Behavioral tests cover both 13-argument and 17-argument invocation forms, including
multi-graph routing and startup-prepared records. Invalid scopes, ambiguous or
missing measurement columns, changed results, unbound query IDs and non-target
graph accesses remain failures. All 179 JavaScript protocol tests, Bash syntax
validation and `git diff --check` pass.

A replay uses the unchanged archived CI observations and correctness files. All
64 workload identities match the independently produced local GTY05 corpus;
the replay supplies its retained manifests because CI manifest paths are absent
from the failed artifact. The original verifier exits 1; the corrected verifier
exits 0, checking all three index states. This is a scoped format/correctness
replay, not a rerun of CI, graph construction, or a performance measurement.
Commands, source/input hashes and both outcomes are retained in
`/tmp/graphite-untimed-workload-replay-1/record.json`. The complete test output is
`/tmp/graphite-untimed-workload-all-js-tests.log`.

## Construction resource capture (2026-10-10)

The Native CI artifact producer now captures the resources of each revision's
own real 64-graph writer using GNU time on Linux. The measured boundary starts
with a fresh JVM and ends after all saved graphs, indexes and manifests,
embedded readback validation, and clean writer exit. Compilation, the separate
verification process and subsequent artifact hashing remain outside this
boundary. This is graph preparation including validation, not pure serialization.

The receipt retains raw wall, user and system time, their total CPU time, and
maximum process RSS in bytes, together with the exact wrapped command and GNU
time identity. Each writer retains the same 4 GiB heap and four active
processors. Peak process RSS is not a sum of simultaneous processes. The
independent auditor parses the raw metrics again, verifies the command and
cleanup, and rejects missing metrics when CI requests capture. Historical
unmeasured packets retain their original shape.

All 100 producer, auditor and downstream handoff protocol tests pass, including
failed writers, timeouts, invalid metrics, changed commands and missing captures.
Three focused JavaScript contract tests and all control-manifest checks pass;
an independent rerun of eight resource tests also passes. Evidence is retained
at `/tmp/graphite-construction-capture-integration-1/record.json`. No real
construction measurement has run with this change yet. Construction, loading
and query regression acceptance remain separate and unproven by these tests.

## Portable oracle handoff (2026-10-10)

The fresh 39-query audit and pressure preparation used different evidence
shapes. `normalize_native_pressure_oracles.py` now adapts the existing audit
into per-case plan, audit, expected-payload and actual-body references. It
recomputes the raw response audit, preserves the historical persisted-graph
oracle derivation, and binds the current request, runtime, graph scope and
complete response. It does not claim to derive historical expected values from
newly written graphs.

Pressure preparation independently recomputes this normalized evidence once per
arm within an assembly call, then retains every per-case check. The separate
complete fixture-equivalence requirement is unchanged. Normalization does not
emit that proof, promote a partial producer bundle, or establish performance
acceptance. Fresh whole-core, topology and index verification remains required
before continuous-pressure eligibility can be established.

All 115 adjacent protocol tests pass; an independent root rerun of the 11 new
adapter tests also passes. Positive cases cover accepted C, distinct parent A,
candidate B and the existing exact A=C alias, checking all 39 responses each.
Changed queries, revisions, graph scope, payload/body pins and forged semantic
claims are rejected. CI runs the new tests; control hashes remain verified.
Evidence: `/tmp/graphite-native-fresh39-adapter-prep-1/validation-2.json` and
`/tmp/graphite-fresh39-root-tests.log`. Actual fresh CI normalization and the
complete producer adapter have not run yet.

## Array conversion production-path correctness (2026-10-10)

Five additional tests cover the production array formatter, descriptor cache,
local identity/cache behavior and persisted local values. They check every JVM
array rank from 1 through 255 for eight primitive bases and a reference base,
nested ArrayType layers, caller overloads and first-encounter local retention.
Hand-authored bytecode independently supplies byte/String allocations of ranks
1, 2 and 3; complete Local values and caller/callee identities survive both
EAGER and MAPPED save/load paths. The existing array-overload folding test also
passes.

The isolated unchanged production sources pass all 613 SootUp tests, all 375
Webgraph tests and both modules' detekt checks, with no skipped tests. The first
validation run incorrectly overrode Webgraph's configured 4 GiB test heap with
512 MiB and failed three Android integration tests with OutOfMemoryError; its
new array tests and SootUp suite passed. That failed run is retained. A separate
run restores the repository's existing 4 GiB heap and one-class-per-fork policy,
reruns the full Webgraph suite and executes both static checks successfully.
The already-passed SootUp tests are not relabeled as a second fresh execution.

Evidence: `/tmp/graphite-array-formatter-validation-1/independent-xml-review.json`
and `/tmp/graphite-array-formatter-validation-2/independent-xml-review.json`
(SHA-256 `a8b63a7931ed28edc0a9dc147ddbb00c990d3a8e48d8ea8750c7a9b1d3c92cf0`).
An independent root review confirms complete XML counts, clean owned process
exit, unchanged source pins and both successful static checks.

This supports conversion correctness relative to the supplied SootUp type
model. It does not independently prove SootUp's inferred type for each historical
local correction or replace a complete comparison of fresh CI graph artifacts.
In particular, nested ArrayType values must not be normalized by indiscriminately
collapsing every array to rank one. No performance claim follows from these
correctness tests.


## Portable independent core comparison package (2026-10-10)

The retained source16 comparison implementation and its172 tests now live in
`.github/scripts/native_core_proof`. Fourteen additional tests bind the exact
per-pair source rules to the actual writer revisions, fixture manifests and
source-manifest hashes. The source-backed array and synthetic-identity policies
retain their existing limits: this does not independently prove SootUp local
inference or newly recovered fingerprints. Strict comparison remains the default;
explicit correction switches and pinned source rules are required when used.
The topology wrapper is the actual execution-prep14 implementation with an explicit
portable helper directory; the complete topology algorithm is unchanged.

All186 tiny Python tests passed in the integration audit and again in the root's
independent check. The root verified the36 frozen package-file hashes and every
preparation-control hash, preserving the preceding72 entries. CI now runs the
package tests. These checks perform no Java compilation or real graph comparison.
The integration receipt is
`/tmp/graphite-native-core-package-integration-1/record.json`, SHA-256
`d72166fd3788202b058baf170488aab4e8cdf6c8c83374e3ef8efb8da22a9d2d`.

Fresh dictionary exports, GTY01–05 validation, bootstrap-marker and formatter-test
authorities, the owned all64 execution runner and completed producer-contract
adapter remain required. Packaging the comparator does not make a partial fixture
eligible for pressure or establish complete semantic/performance acceptance.


## Fresh producer dictionary and bootstrap authority (2026-10-10)

Commit `c7d9438d` integrates the fresh writer dictionary export stage. Each
unique actual C/A/B producer compiles the dependency-only `ExportStrings` helper
and exports all64 dictionaries in65 owned phases, using matching4g/APC4 limits.
An A=C alias reuses the actual C artifact. Export work is outside construction
measurement and outside source/runtime/graph roots. The independent auditor
replays the exact commands, logs, cleanup, compiled helper closure, raw values,
semantic digests and producer pins. GTY01–05 declaration loading now requires
its active `forward.properties` binding; orphan, missing, duplicate, malformed
and mismatched bindings are rejected. The fresh parser accepts plain ASCII
properties and rejects unsupported escapes rather than guessing.

Root verification passed213 package tests and15 mocked export protocol tests.
The frozen source packet is `/tmp/graphite-native-core-export-prep-2/source-ready.json`,
SHA-256 `e5ac61d351f4212ff6d4e6646fb0f0a7f5973bdf3da554ca2877849224c8dfa2`.
Workflow YAML was parsed with Ruby Psych, the extracted producer shell passed
`bash -n`, and source/control hashes matched. Actual fresh JVM exports have not
yet run, so these are protocol/correctness results only.

The next stage binds the exact producer JDK bootstrap `java.io.Serializable`
resource to each actual C/A or C/B comparison. It replays both raw producer
artifact audits, requires matching JDK path and module image, and runs two owned
bounded compile/export phases against the actual writer JAR. The retained Java
helper is byte-identical to the previously reviewed helper. An independent raw
classfile parser requires the exact empty Serializable interface with Object as
superclass. The portable Marker adapter replays the complete export audit;
the historical adapter remains unchanged. This narrowly supplies a known
bootstrap resource and never authorizes a fallback for arbitrary missing classes.

Root independently verified all8 changed source files against the frozen packet,
all preparation-control hashes, workflow binding and extracted shell syntax.
All213 retained package tests and13 new marker protocol tests pass. The initial
root package invocation omitted unittest's package top-level argument and failed
19 imports; it is retained in `/tmp/graphite-core-marker-root-package-tests.log`.
With the repository's package-aware invocation (`-t .github/scripts`) the suite
passes in `/tmp/graphite-core-marker-root-package-tests-2.log`. No production
source was changed to obtain that result. The marker protocol log is
`/tmp/graphite-core-marker-root-protocol-tests.log`.

Frozen marker source is `/tmp/graphite-native-core-marker-prep-1/marker-source-ready.json`,
SHA-256 `f2581e01292457b77c84edc284c01dad96c832041151c96cd85192f2dd1ee187`.
No real JVM, corpus or performance execution is represented by these tiny tests.
The whole64 comparison runner must reuse a verified pair authority, avoiding
repeated complete artifact audits per graph. Fresh source/formatter authority,
whole core/topology/index comparison and the completed producer adapter still
remain. Complete semantic equivalence, inference and performance acceptance
flags remain false.


## Fresh formatter source-rule binding (2026-10-10)

The portable source-rule binder now consumes the actual C/A or C/B producer pair
through the independently replayed bootstrap/artifact audits. It binds exact
source and fixture manifests and checks the reviewed old/new array formatter,
seven adapter boundaries (including both method-descriptor overloads), identity
cache declarations and unchanged dependency/build definitions. The historical
contract provenance path is not read. A C=C alias requires strict comparison
without a formatter correction rule. Outputs use the existing Local authority
schema; comparator policy is unchanged.

Root reviewed the implementation and all new behavioral tests, verified every
frozen/base/control hash, parsed workflow YAML, and checked the extracted producer
shell. All224 package tests and5 composition tests pass. Raw logs are
`/tmp/graphite-core-formatter-root-package-tests.log` and
`/tmp/graphite-core-formatter-root-adapter-tests.log`. Frozen source is
`/tmp/graphite-native-core-formatter-prep-1/formatter-source-ready.json`, SHA-256
`6e4887dae570b179c397bb144c30c8da69de7125ff87aa4c2ee1217915bab316`.

This establishes source-model binding only. Fresh owned production formatter
tests, actual whole64 semantic execution and completed producer evidence remain
required. `productionFormatterTestsVerified`, complete semantic equivalence,
synthetic-local inference and performance acceptance remain false. Historical
unsupported inference cases are not promoted by checking source fragments.


## Real two-graph large projection expectations (2026-10-10)

The independent large-projection selector has now executed on accepted4f and
GTY05 own-writer artifacts for `fixture-tika-10` and
`fixture-kotlin-compiler-15`. It decoded the four real graph inputs with their
actual shared dictionary exports. The frozen selector uses raw property cohorts,
checks complete C/B identity multisets and retains every selection rejection;
it does not select from server responses, timing or generic-presence filters.
Return method candidates use owner/package prefixes, with exact-signature
exclusions counted and retained, avoiding a20,000-candidate overflow without
raising the cap. Thirty tiny selector tests pass, including20,771 distinct
methods and exact complete matching. The earlier32-row packet remains unchanged.

All eight requested full-node / properties projections have complete admitted
cohorts. Counts below are identical for the two projection forms:

| Node kind | Tika rows / bound / true generic | Kotlin rows / bound / true generic | Complete response rows |
|---|---:|---:|---:|
| Field | 993 / 969 / 54 | 966 / 650 / 70 | 1959 |
| Parameter | 994 / 994 / 150 | 927 / 927 / 689 | 1921 |
| Return | 1007 / 1007 / 40 | 1021 / 1021 / 5 | 2028 |
| Method | 995 / 995 / 135 | 979 / 979 / 33 | 1974 |

Every request actually targets both named graphs. The eventual server retains64
loaded graphs, but these requests' scope is explicitly two, not64. There is no
Cypher LIMIT; the exact URL `/api/cypher?limit=5000` admits all rows. Sixteen
arm-specific expected JSON bodies preserve full order, nested values, provenance,
Return null/label distinctions and `total.relation=eq`. C payloads range from
715,128 to968,909 bytes; B payloads range from976,065 to1,844,352 bytes.

Command:
`python3 -B /tmp/graphite-large-projection-oracle-prep-2/large_oracle.py --plan /tmp/graphite-large-projection-oracle-prep-2/plan.json --output /tmp/graphite-large-projection-selection-1 --execute-assigned-slot`.
The sole assigned process exited0 with no selection failure and unchanged
before/after input pins. Root verified all output hashes, all16 canonical body
digests, complete rows/graph coverage and exact request endpoints. An initial
root output-check call passed a parsed dict to a byte-payload validator; using
the original bytes corrected that review invocation without changing oracle
source, selected rows or expected output.

Status is `EXPECTED_LARGE_COHORTS_DERIVED_NOT_SERVER_COMPARED`. This establishes
real expected responses, not HTTP correctness or latency/CPU/RSS. Actual
C/A192/B193 full-projection requests and pressure remain to execute.

Generator record SHA-256: `f26186a79ab78831db472cb814d3430e0876596a253eab1667c5ae8fb25cac66`.
Root review SHA-256: `301cb3511cfc4450834d4358911eee08a202b4d0484b235e17751017d53f2c75`.


## Executable actual-writer formatter correctness stage (2026-10-10)

The fresh producer workflow now has an owned correctness command for the actual
non-baseline writer checkout: the four existing ArrayTypeFormatterMigrationTest
cases,16 FoldSelectionTest cases and ArrayLocalPersistenceTest's six-allocation
case. No candidate test is copied into accepted4f. A=C skips the stage; an actual
parent lacking the exact reviewed test sources fails explicitly. The command
reuses the producer's Gradle/home caches, uses its exact JDK with4g/APC4 and one
Test fork, and keeps raw XML and binary test results outside measured graph roots.
This work is after usable graph construction and contributes no performance time.

The init script captures ordered classpath bytes before and after each Test task.
The auditor checks exact test identities, no failures/skips or unrelated results,
first-resolution production classes against the actual writer JAR, source/runtime
identity and owned cleanup. A successful generic CI status or historical XML is
not substituted. The independent audit sets productionFormatterTestsVerified
only after actual command, classpath, writer and raw result checks succeed.
Inference, complete graph semantics and performance flags remain false.

Root inspected the Python/Groovy execution and audit paths and the11 behavioral
protocol tests, then independently ran all11 successfully. Source/test/control
hashes, YAML and extracted Bash validation pass. Evidence is
`/tmp/graphite-formatter-tests-root-protocol.log`; frozen source is
`/tmp/graphite-native-formatter-tests-prep-1/production-test-source-ready.json`,
SHA-256 `2bc859336802853c3d1a68db019e4bfb21a2889a9320de9dad2aa0a2c209af49`.
These tests use tiny classpath/JAR/XML fixtures with mocked execution. The new
Groovy init script and complete actual-writer stage have not yet run in Gradle;
they are not represented as production test success by this source integration.
The separate615-test Attempt187 result remains separate evidence.

### Fresh64 core, topology and index execution stage (2026-10-10)

The workflow now runs the retained complete comparison on each actual accepted
writer / feature writer pair after dictionary export, bootstrap marker, source
binding and actual formatter tests. `run_native_core_equivalence.py` compiles the
existing topology helper once and executes64 Python core checks followed by64
Java topology checks, in129 sequential owned phases. Every Java child uses4g
heap and APC4. A=C skips only the duplicate pair. Failed commands stop the run;
raw phase logs, cleanup records and completed per-graph results remain archived.

The per-graph marker consumer checks its exact bootstrap authority without
rehashing unrelated graph payloads. Full producer/fixture authority is still
verified at pair binding, final verification and independent audit. Core matching,
explicit migrations, topology, node/index coverage and declaration wire checks
retain their existing predicates. No pressure threshold or oracle is relaxed.

This stage cannot close the historical10,410 unsupported synthetic-local type
inference cases. A rule projecting candidate array rank2..255 to legacy rank1
does not independently distinguish a correct rank2 from an incorrect rank3, or
find a missed rank1 correction. The source checks and21 formatter tests support
the conversion model, not every local's actual input type. The new audit retains
`completeSemanticEquivalence=false`, explicit missing authority and per-graph
correction counts. The original partial producer packet continues to block
complete performance acceptance. No completion adapter promoting this result to
full semantic equivalence is integrated.

Root independently ran20 runner,15 marker and224 retained package protocol tests;
all passed. Control hashes, YAML and109 Bash blocks passed validation. A trailing
space in the prepared runner was removed, control hashes refreshed and all20
runner tests rerun. These are tiny protocol tests; the actual129-phase comparison
has not yet executed. Frozen preparation is
`/tmp/graphite-native-core-runner-prep-2/source-ready.json`, SHA-256
`97e11473e8fc4f7d2a4b226858501a20c83f13bcd98d645ab3d4619515ef2170`;
root logs are `/tmp/graphite-whole64-root-*-tests.log`.


### Actual raw Local.type authority integration (2026-10-10)

The existing all64 runner now supports `--raw-local-types`, and the CI comparison
passes that option. Each actual writer compiles the same Java helper and exports
the four original corpora through its own ParsedClassLocation and GraphiteJavaView.
The original splitter reconstructs inputs only; shard bytecode hashes and class
counts must match producer provenance. No saved graph type string, adapter type
formatter or graph builder supplies the expected array rank.

Every candidate Local is checked before legacy normalization, including unchanged
rank-one values. The join includes graph/shard, owner, method name, full JVM
descriptor including return type, and local name. Both writers' raw types must
agree. Same-name conflicts, missing identities, duplicate persisted identities
and array-related typed allocations remain failures rather than selecting a
matching occurrence. Non-array notes are explicitly outside this array proof.
Partial traversal cannot report complete coverage. Scope remains fixture64 with
no folding; it does not independently prove SootUp's inference algorithm.

Source review caught and fixed ambiguous Java Type wildcard imports and the
missing workflow option. The root independently ran251 package tests (including
27 new tests) and20 existing runner tests; all passed. Logs are
`/tmp/graphite-raw-local-root-package-tests.log` and
`/tmp/graphite-raw-local-root-runner-tests.log`. Actual Java compilation and raw
corpus execution remain pending behind the existing exclusive construction run.
The exporter may retain more method bodies than the production streaming adapter;
its actual4g execution must succeed without raising the heap. All global semantic
and performance acceptance flags remain false pending actual evidence and review.


Actual compilation follow-up: the unchanged helper compiled successfully against
both the accepted4f writer and candidate286 writer using OpenJDK17 javac,
`-J-Xmx4g -J-XX:ActiveProcessorCount=4 -proc:none`. Both commands exited0,
stdout/stderr were empty, source and dependency identities remained unchanged,
and both owned process groups were empty afterwards. Root independently checked
the receipts, raw output hashes and generated class hashes. The identical class
SHA-256 is `b26f51073ccb0b9b7224a04ff10ffdf630b23dd806eafcd1e1ccfe67fc09bfcd`.
Evidence: `/private/tmp/graphite-attempt187-raw-local-helper-compile-1`, record
SHA-256 `94ec66b028e24ea8544a2bca4a5de4853d77b6b24dc5067ff5066e428b32d89b`.
This resolves compilation compatibility only; raw corpus execution and complete
semantic acceptance remain pending.
