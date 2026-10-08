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

## Observed saved sizes

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
