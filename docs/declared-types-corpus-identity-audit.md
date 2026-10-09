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
