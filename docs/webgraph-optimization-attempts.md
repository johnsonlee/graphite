# WebGraph Optimization Attempts

This file contains the chronological optimization attempt log split out from [webgraph-storage.md](webgraph-storage.md), so the storage-format document can stay focused on the current design.

## Ongoing Load/Query Optimization Log

### 2026-07-18 — Attempt 000: Android-scale JMH harness repair

**Goal:** establish a reliable Android-scale baseline before changing load/query code. The objective requires JMH results on a graph at Android jar scale; demo jars or sub-100K-node graphs are not representative.

**Initial failure:** `./gradlew :webgraph:jmh -Pjmh.filter='AndroidLoadBenchmark.mapped_load'` compiled and the Gradle task reported success, but JMH produced no score because the forked JVM could not locate `android-all`.

```
java.lang.IllegalStateException: Unable to locate android fixture JAR.
Set -Dandroid.jar.path=<path> or resolve integration fixtures first.
```

**Rejected fix:** adding `libs.android.all` and `libs.elasticsearch` to the `jmh` dependency configuration made the fixture visible, but it also placed the Android jar in the JMH fat jar. `:webgraph:jmhJar` then failed with:

```
Archive contains more than 65535 entries.
```

**Root cause:** large integration fixture jars must not be packaged into the benchmark jar. They should be resolved by Gradle and passed to the forked JMH JVM as `-Dandroid.jar.path` / `-Delasticsearch.jar.path`.

**Accepted fix:** keep fixtures in `integrationFixtures`; lazily resolve that configuration only for the `jmh` task and append exact fixture paths to JMH fork JVM args. `BenchmarkCorpus` also checks `java.class.path` before falling back to the Gradle cache, which keeps explicit `-D...path` overrides, JMH classpath execution, and cache scanning all valid.

**Validation command:**

```
./gradlew :webgraph:jmh -Pjmh.filter='AndroidLoadBenchmark.mapped_load'
```

**Result:**

| Benchmark | Mode | Count | Score | Units |
|-----------|------|-------|-------|-------|
| `AndroidLoadBenchmark.mapped_load` | avgt | 2 | `2292.892` | ms/op |

**Conclusion:** effective as a benchmark harness repair, not a load/query optimization. The Android-scale baseline path is now usable for subsequent attempts.

### 2026-07-18 — Attempt 001: Skip reverse StringTable index on load

**Hypothesis:** `StringTable.load` reconstructs a `HashMap<String, Int>` for every persisted graph even though eager/lazy/mapped graph loading only needs `StringTable.get(index)` while deserializing nodes and metadata. Avoiding this reverse index should reduce load time and heap without affecting build/save.

**Change:** keep the reverse index for `StringTable.build` (save path) but make `StringTable.load` return a read-only table with no `indexMap`.

**Validation commands:**

```
./gradlew :webgraph:test
./gradlew :webgraph:jmh -Pjmh.filter='AndroidLoadBenchmark.mapped_load'
```

**Result:**

| Benchmark | Baseline | Attempt 001 | Change |
|-----------|----------|-------------|--------|
| `AndroidLoadBenchmark.mapped_load` | `2292.892 ms/op` | `2341.167 ms/op` | `+48.275 ms` / `+2.1%` |

**Conclusion:** not effective for load-time improvement. The result is within short JMH-run noise but does not prove a win.

**Root cause:** on Android-scale mapped load, reverse string-index construction is not the dominant cost. Remaining costs such as BVGraph load, backward graph construction, node index parsing, labels, and metadata dominate the measured path. This may still reduce heap modestly, but it does not move the required load/query performance target by a meaningful amount.

### 2026-07-18 — Attempt 002: Lazy backward adjacency construction

**Hypothesis:** `loadEager`, `loadLazy`, and `loadMapped` all rebuild the full backward adjacency from `forward.*` during load, even when the query path only needs forward traversal or node scans. Android-scale `mapped_load` should improve if transpose construction is deferred until the first `incoming()` call.

**Change:** replace eagerly constructed `ImmutableGraph backward` constructor parameters with `Lazy<ImmutableGraph>`. `GraphStore.load*` now creates a lazy handle, and each graph implementation calls `backward.value` only inside `incoming()`.

**Build/save impact:** none. The persisted format and `GraphStore.save` path are unchanged.

**Validation commands:**

```
./gradlew :webgraph:test
./gradlew :webgraph:jmh -Pjmh.filter='AndroidLoadBenchmark.mapped_load'
```

**Result:**

| Benchmark | Baseline | Attempt 002 | Change |
|-----------|----------|-------------|--------|
| `AndroidLoadBenchmark.mapped_load` | `2292.892 ms/op` | `1425.196 ms/op` | `-867.696 ms` / `-37.8%` |

Compared with Attempt 001's immediate pre-change result (`2341.167 ms/op`), this is `-915.971 ms` / `-39.1%`.

**Conclusion:** effective for load time. This does not yet provide a full order-of-magnitude improvement, but it removes a large eager-load cost without increasing build/save time or memory.

**Root cause:** backward transpose construction is a major Android-scale mapped-load cost. Most common load and forward-query paths do not need incoming edges, so doing this work unconditionally was wasted. Queries that call `incoming()` still pay the same transpose cost once, but they pay it at first use rather than at graph open.

### 2026-07-18 — Attempt 003: Fast path for simple node `count`

**Hypothesis:** `MATCH (n:Label) RETURN count(*)` should not materialize every node. The graph already has type indexes for `nodes(Label)`, so Cypher can answer simple unfiltered count queries from a precomputed node count.

**Change:** add optional `Graph.nodeCount(type)` with implementations in `DefaultGraph` and WebGraph-backed graphs. `QueryPipeline` now short-circuits only simple single-node count queries:

```
MATCH (n:Label) RETURN count(*)
MATCH (n:Label) RETURN count(n)
```

Queries with relationships, `WHERE`, properties, `DISTINCT`, grouping, `WITH`, `ORDER BY`, or multiple clauses still use the normal execution path.
The fast path is limited to 0/1-label node patterns; multi-label node patterns still use the normal matcher.

**Build/save impact:** none. This uses existing in-memory type indexes and persisted node indexes; no new build-time or save-time structure is written.

**Validation commands:**

```
./gradlew :cypher:test
./gradlew :webgraph:test
./gradlew :webgraph:jmh -Pjmh.filter='AndroidQueryBenchmark.mapped_countStar'
```

**Result:**

| Benchmark | Baseline | Attempt 003 | Change |
|-----------|----------|-------------|--------|
| `AndroidQueryBenchmark.mapped_countStar` | `1816.130 ms/op` | `0.003 ms/op` | effectively eliminates the scan |

**Conclusion:** effective for the targeted count query. This is a narrow query optimization, not a general Cypher accelerator.

**Root cause:** the previous pipeline executed `MATCH` before aggregation, so `count(*)` forced a full scan and deserialization of every matching node. For unfiltered single-node counts, the result is exactly the size of the node type index, so scanning was unnecessary work.

### 2026-07-18 — Attempt 004: Early-stop simple `DISTINCT property LIMIT`

**Hypothesis:** `MATCH (n:Label) RETURN DISTINCT n.property LIMIT k` should not scan all matching nodes when there is no `WHERE`, relationship, grouping, or `ORDER BY`. The existing implementation preserves first-seen order with `List.distinct()`, so it is equivalent to stop after the first `k` distinct property values.

**Change:** add a narrow `QueryPipeline` fast path for:

```
MATCH (n:Label) RETURN DISTINCT n.property LIMIT k
```

The fast path scans matching nodes only until `k` distinct values have been seen. Queries with `WHERE`, relationships, inline node properties, missing `LIMIT`, multiple return items, grouping, `WITH`, or `ORDER BY` still use the normal pipeline.
Like the count fast path, it is limited to 0/1-label node patterns so multi-label matching semantics remain in the existing matcher.

**Build/save impact:** none. This is purely query execution control flow and adds no persisted index or build-time work.

**Validation commands:**

```
./gradlew :cypher:test
./gradlew :webgraph:test
./gradlew :webgraph:jmh -Pjmh.filter='AndroidQueryBenchmark.mapped_returnDistinct'
```

**Result:**

| Benchmark | Baseline | Attempt 004 | Change |
|-----------|----------|-------------|--------|
| `AndroidQueryBenchmark.mapped_returnDistinct` | `2738.060 ms/op` | `0.214 ms/op` | effectively eliminates the full scan for this shape |

**Conclusion:** effective for the targeted distinct-property query. It is not a general replacement for property indexes, but it removes a common unnecessary full scan when `LIMIT` is present and no ordering constraints exist.

**Root cause:** `DISTINCT` disabled early limit pushdown, so the old pipeline materialized every `CallSiteNode`, projected `callee_class`, deduplicated the full list, and then applied `LIMIT 20`. For unordered distinct queries, scanning after the first 20 distinct values is unnecessary.

### 2026-07-18 — Attempt 005: Stream simple single-hop relationship `LIMIT`

**Hypothesis:** `MATCH (a)-[:TYPE]->(b) RETURN ... LIMIT k` should not expand and materialize a complete relationship result list for each source node before applying `LIMIT`. On Android-scale graphs, high-fanout source nodes can make a small `LIMIT` query pay for far more edge decoding than necessary.

**Change:** add a narrow `QueryPipeline` fast path for one non-optional, non-variable-length relationship pattern followed by a non-aggregate, non-`DISTINCT` `RETURN` and `LIMIT`:

```
MATCH (a:Source)-[:TYPE]->(b:Target) RETURN a.property, b.property LIMIT k
```

The fast path streams source nodes, edges, target checks, and return projection in query order, then stops as soon as `k` complete rows are produced. Queries with `WHERE`, `ORDER BY`, `SKIP`, `WITH`, variable-length relationships, path variables, aggregation, or `DISTINCT` still use the normal pipeline.

**Build/save impact:** none. This is query execution control flow only; it does not add indexes, persisted fields, or build-time work.

**Validation commands:**

```
./gradlew :cypher:test
./gradlew :webgraph:test
./gradlew :webgraph:jmh -Pjmh.filter='AndroidQueryBenchmark.mapped_singleHopRelationship'
```

**Result:**

| Benchmark | Baseline | Attempt 005 | Change |
|-----------|----------|-------------|--------|
| `AndroidQueryBenchmark.mapped_singleHopRelationship` | `75.802 ms/op` | `0.658 ms/op` | `-75.144 ms` / `~115.2x faster` |

**Conclusion:** effective for the targeted single-hop relationship query and crosses the required order-of-magnitude threshold for this benchmark shape.

**Root cause:** the generic early-limit path only checked the limit after `matchRelationship` had expanded a source node into a full intermediate list. If one source has many outgoing `DATAFLOW` edges, the engine still decodes and materializes all of those relationship matches before keeping the first 20 rows. It also risked treating `LIMIT` as a cap on source nodes rather than complete relationship matches. Streaming complete rows and stopping inside the edge loop removes both costs.

### 2026-07-18 — Attempt 006: Stream filtered single-node `LIMIT`

**Hypothesis:** `MATCH (n:Label) WHERE ... RETURN ... LIMIT k` should not materialize every matching node before evaluating `WHERE`. For bounded filtered scans, `LIMIT` can be applied after filtering and projection while still streaming the node scan.

**Change:** add a narrow `QueryPipeline` fast path for one non-optional single-node pattern followed by `WHERE`, non-aggregate/non-`DISTINCT` `RETURN`, and `LIMIT`:

```
MATCH (n:Label) WHERE n.property = value RETURN n.id LIMIT k
```

The fast path scans candidate nodes, evaluates node constraints and `WHERE`, projects the return row, and stops when `k` filtered rows have been produced. Queries with relationships, `ORDER BY`, `SKIP`, `WITH`, aggregation, `DISTINCT`, path variables, non-literal `LIMIT`, or `RETURN *` still use the normal pipeline.

**Build/save impact:** none. This is query execution control flow only; it does not add indexes, persisted fields, or build-time work.

**Validation commands:**

```
./gradlew :cypher:test
./gradlew :webgraph:test
./gradlew :webgraph:jmh -Pjmh.filter='AndroidQueryBenchmark.mapped_intConstantFilter'
```

**Result:**

| Benchmark | Baseline | Attempt 006 | Change |
|-----------|----------|-------------|--------|
| `AndroidQueryBenchmark.mapped_intConstantFilter` | `2.545 ms/op` | `0.057 ms/op` | `-2.488 ms` / `~44.6x faster` |

**Conclusion:** effective for the targeted filtered node query and crosses the order-of-magnitude threshold for this benchmark shape.

**Root cause:** the generic pipeline executed `MATCH` first, producing bindings for all `IntConstant` nodes, then applied `WHERE`, then projected rows and finally applied `LIMIT 100`. Even when only the first 100 filtered rows are needed, the old path still deserialized and materialized every candidate node. Streaming the filter and stopping after the limit removes that intermediate list.

### 2026-07-18 — Attempt 007: Single-pass primitive node-index loading

**Hypothesis:** `readNodeIndex` does two full passes over `graph.nodeindex` and stores type buckets as boxed `MutableList<Int>`. On Android-scale graphs this creates unnecessary IO and heap churn during `loadMapped`. Reading the file once and storing type buckets as primitive `IntArray` should reduce mapped-load time and per-graph heap without changing build/save.

**Change:** parse `graph.nodeindex` in one pass. Fill `nodeOffsets` and per-tag `IntArrayList` buckets together, then expose the loaded type index as `Map<Class<out Node>, IntArray>` to lazy/mapped graph implementations.

**Build/save impact:** none. The persisted `graph.nodeindex` format and save path are unchanged; this only changes how the index is loaded.

**Validation commands:**

```
./gradlew :webgraph:test
./gradlew :webgraph:jmh -Pjmh.filter='AndroidLoadBenchmark.mapped_load'
```

**Result:**

| Benchmark | Baseline | Attempt 007 | Change |
|-----------|----------|-------------|--------|
| `AndroidLoadBenchmark.mapped_load` | `1425.196 ms/op` | `1284.409 ms/op` | `-140.787 ms` / `-9.9%` |

**Conclusion:** effective but not enough for the load-side order-of-magnitude goal. It removes measurable node-index parsing overhead and reduces boxed integer allocation, but the remaining mapped-load path is still dominated by other eager structures.

**Root cause:** node-index parsing is a real cost, but not the dominant remaining load cost after lazy backward adjacency. `loadMapped` still eagerly loads forward BVGraph adjacency, edge labels, cumulative outdegree, comparison metadata, graph metadata, resources, string table, and node offsets before a graph is considered open. For node-only touch/query paths, much of that work is still paid upfront.

### 2026-07-18 — Attempt 008: Lazy edge, metadata, and resource loading

**Hypothesis:** `loadMapped` should not eagerly load edge traversal structures or metadata when a graph is opened for node-only queries. The Android load benchmark only touches one node, so eager forward BVGraph load, edge labels, cumulative outdegree, comparison metadata, graph metadata, and resources are all upfront work that can be deferred without changing build/save.

**Change:** make `loadLazy` and `loadMapped` pass lazy handles for:

```
forward BVGraph
backward transpose
edge labels
cumulative outdegree
comparison map
graph metadata
persisted resources
```

Node offset/type indexes and the string table still load at graph open because node lookup and node deserialization need them. Edge traversal and metadata APIs pay their load cost on first use.

**Build/save impact:** none. Persisted files and the save path are unchanged.

**Validation commands:**

```
./gradlew :webgraph:test
./gradlew :webgraph:jmh -Pjmh.filter='AndroidLoadBenchmark.mapped_load'
./gradlew :webgraph:jmh -Pjmh.filter='AndroidQueryBenchmark.mapped_singleHopRelationship'
```

**Result:**

| Benchmark | Baseline | Attempt 008 | Change |
|-----------|----------|-------------|--------|
| `AndroidLoadBenchmark.mapped_load` | `1284.409 ms/op` | `176.097 ms/op` | `-1108.312 ms` / `~7.3x faster` |
| `AndroidQueryBenchmark.mapped_singleHopRelationship` | `0.658 ms/op` | `0.667 ms/op` | roughly unchanged after JMH warmup |

Compared with the original Android mapped-load baseline (`2292.892 ms/op`), `176.097 ms/op` is `~13.0x faster`.

**Conclusion:** effective for the load-side order-of-magnitude goal when measured against the original Android-scale mapped-load baseline. It also materially reduces open-time heap for multi-graph serving because edge labels and BVGraph structures are no longer resident until an edge query needs them.

**Root cause:** after lazy backward adjacency and primitive node-index loading, the dominant remaining mapped-load costs were unrelated to opening a node-readable graph: forward BVGraph, edge labels, cumulative outdegree, comparisons, metadata, and resources. Deferring those structures moves their cost to the APIs that actually need them, while node-only load/query paths avoid the work entirely.

### 2026-07-18 — Current Android mapped benchmark sweep

This is a verification summary of the current mapped Android-scale state after Attempts 001-008. It is not a separate optimization attempt.

**Validation commands:**

```
./gradlew :webgraph:test
./gradlew :webgraph:jmh -Pjmh.filter='AndroidLoadBenchmark.mapped_load'
./gradlew :webgraph:jmh -Pjmh.filter='AndroidQueryBenchmark.mapped_.*'
```

**Current results:**

| Benchmark | Current score |
|-----------|---------------|
| `AndroidLoadBenchmark.mapped_load` | `176.097 ms/op` |
| `AndroidQueryBenchmark.mapped_countStar` | `0.003 ms/op` |
| `AndroidQueryBenchmark.mapped_intConstantFilter` | `0.056 ms/op` |
| `AndroidQueryBenchmark.mapped_returnDistinct` | `0.197 ms/op` |
| `AndroidQueryBenchmark.mapped_simpleNodeMatch` | `0.085 ms/op` |
| `AndroidQueryBenchmark.mapped_singleHopRelationship` | `0.701 ms/op` |

**Baseline comparison:**

| Benchmark | Recorded baseline | Current score | Change |
|-----------|-------------------|---------------|--------|
| `AndroidLoadBenchmark.mapped_load` | `2292.892 ms/op` | `176.097 ms/op` | `~13.0x faster` |
| `AndroidQueryBenchmark.mapped_countStar` | `1816.130 ms/op` | `0.003 ms/op` | scan eliminated |
| `AndroidQueryBenchmark.mapped_intConstantFilter` | `2.545 ms/op` | `0.056 ms/op` | `~45.4x faster` |
| `AndroidQueryBenchmark.mapped_returnDistinct` | `2738.060 ms/op` | `0.197 ms/op` | full scan eliminated for this shape |
| `AndroidQueryBenchmark.mapped_singleHopRelationship` | `75.802 ms/op` | `0.701 ms/op` | `~108.1x faster` |

`mapped_simpleNodeMatch` was already low after existing early `LIMIT` pushdown; the current sweep records it at `0.085 ms/op`.

**Build/save impact:** no optimization in Attempts 001-008 changes the graph save format or adds build-time indexes. The load-side wins come from deferring runtime structures; the query-side wins come from execution control flow and existing type indexes.

**Operational note for multi-graph serving:** opening many mapped graphs now keeps edge structures, metadata, and resources unloaded until first use. The first edge or metadata query for a graph pays that lazy initialization cost once; services that need predictable first-edge latency can explicitly prewarm edge APIs for selected graphs, while node-only query workloads avoid the cost entirely.

### 2026-07-18 — Attempt 009: SootUp Android build JMH harness repair

**Goal:** verify that the load/query work did not compromise the build side. The relevant Android-scale build benchmark is `GraphBuildBenchmark.buildAndroidSdkGraph`, but the `:sootup:jmh` harness had to produce a real JMH score before it could be used as evidence.

**Initial failure:**

```
./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraph' --rerun-tasks
```

The Gradle task reported `BUILD SUCCESSFUL`, but JMH produced no benchmark score. The fork failed immediately with:

```
java.lang.NoSuchMethodError: 'int org.objectweb.asm.Type.getArgumentCount(java.lang.String)'
    at org.objectweb.asm.tree.MethodNode.visitParameterAnnotation(MethodNode.java:304)
```

**Control check:** running the generated JMH fat jar directly with an explicit Android fixture path completed successfully:

```
java -Dandroid.jar.path=<android-all.jar> \
  -jar frontend/jvm/sootup/build/libs/sootup-1.0.0-SNAPSHOT-jmh.jar \
  '.*GraphBuildBenchmark.buildAndroidSdkGraph.*' -wi 0 -i 1 -f 1 -r 1s -w 1s
```

Result: `100524.865 ms/op`.

**Root cause:** the `sootup` JMH Gradle harness was weaker than the already-repaired `webgraph` harness. It relied on fallback fixture discovery, did not pass exact `android-all` / Elasticsearch fixture paths into the forked JVM, did not explicitly force a consistent ASM family on all JMH configurations, and did not fail the Gradle task when JMH failed internally.

**Accepted fix:** align `frontend/jvm/sootup/build.gradle.kts` with `frontend/jvm/webgraph/build.gradle.kts`:

- keep large fixture jars out of the JMH fat jar
- pass fixture paths as `-Dandroid.jar.path` / `-Delasticsearch.jar.path`
- force `asm`, `asm-tree`, `asm-util`, `asm-commons`, and `asm-analysis` to the configured ASM version
- set `failOnError = true` so failed JMH forks fail the Gradle task instead of creating false-success builds

**Validation command:**

```
./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraph' --rerun-tasks
```

**Result:**

| Benchmark | Mode | Count | Score | Units |
|-----------|------|-------|-------|-------|
| `GraphBuildBenchmark.buildAndroidSdkGraph` | ss | 1 | `101647.075` | ms/op |

**End-to-end guardrail:**

```
./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query' --rerun-tasks
```

| Benchmark | Mode | Count | Score | Units |
|-----------|------|-------|-------|-------|
| `GraphEndToEndBenchmark.android_build_save_load_query` | ss | 1 | `115782.725` | ms/op |

This covers `build -> save -> loadMapped -> query` with `-Xmx4g`.

**Conclusion:** effective as a benchmark harness repair. It proves the current Android build benchmark remains runnable after the load/query changes, and the Gradle-run result is in the same range as the direct JMH control run. The end-to-end guardrail also passes under the 4 GB heap constraint. This attempt does not change product build, save, load, or query behavior.

### 2026-07-25 — Attempt 010: Explorer Android memory JMH baseline

**Goal:** establish a reproducible JMH baseline for long-running `graphite-explore` memory retention before changing explorer behavior. The benchmark uses the Android-scale mapped graph and keeps a Javalin explorer process alive while issuing HTTP requests against the real route handlers.

**Benchmark design:** add `ExplorerMemoryBenchmark` in the `explore` module with retained-heap aux counters. Each benchmark forces GC before and after the request sequence and reports:

- `usedHeapBeforeBytes`
- `usedHeapAfterBytes`
- `retainedHeapBytes`

The benchmark uses `-Xmx4g` and the same Android fixture resolution pattern as the existing large-corpus JMH harnesses. Persisted graphs can be supplied via `-Dandroid.graph.path`; otherwise the Android fixture is built and saved once per JMH fork.

**Baseline scenarios:**

- `android_initialExplorerSession`: `/api/graphs`, `/api/overview?limit=200`, `/api/methods?limit=200`
- `android_browserForwardExploration`: repeated node detail/outgoing/subgraph requests, passing `direction=outgoing` to the subgraph endpoint. Current main ignores that parameter, so this captures the pre-optimization behavior where subgraph expansion still traverses incoming edges.

**Validation command:**

```
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_.*' --no-daemon
```

**Result:**

| Benchmark | Mode | Count | Score | Retained heap | Used heap before | Used heap after |
|-----------|------|-------|-------|---------------|------------------|-----------------|
| `ExplorerMemoryBenchmark.android_browserForwardExploration` | ss | 1 | `2190.773 ms/op` | `215089672 B` | `122481864 B` | `337571536 B` |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` | ss | 1 | `14451.838 ms/op` | `713390624 B` | `122877128 B` | `836267752 B` |

**Conclusion:** baseline established. The initial explorer session retains ~713 MB after forced GC because the graph statistics request scans every node's outgoing edges and forces edge traversal structures resident. Forward browser exploration retains ~215 MB because current subgraph expansion ignores `direction=outgoing` and still traverses incoming edges, which initializes the backward adjacency path. This commit is a benchmark harness and documentation baseline only; it does not change explorer runtime behavior.

### 2026-07-25 — Attempt 011: Explorer route memory guardrails

**Hypothesis:** long-running explorer memory growth is amplified by route handlers that force expensive lazy graph structures resident or materialize unbounded responses. The first optimization should avoid accidental heavy initialization in default browser workflows and cap request fan-out before objects are allocated.

**Change:**

- add optional `Graph.edgeCount()` so `/api/graphs` can report edge totals from precomputed graph state instead of scanning every node's outgoing edges
- clamp list-style request limits for nodes, edges, resources, endpoints, overview, and Cypher rows
- reject resource content responses larger than 1 MiB before converting them to UTF-8 strings
- cap subgraph traversal by depth, node count, and edge count
- add `direction=outgoing|incoming|both` for `/api/subgraph`; the Web UI uses outgoing-only exploration by default so clicking nodes does not initialize backward adjacency
- make incoming edge details explicit in the Web UI behind a "Load incoming" action
- apply a Cypher endpoint row limit before execution by inserting/capping a `LIMIT` clause in the parsed query

**Build/save impact:** no new persisted graph files are written and no build-time analysis index is added. `DefaultGraph` computes an edge total from its already-built outgoing edge lists; WebGraph-backed graphs answer from existing adjacency metadata.

**Validation commands:**

```
./gradlew :core:check :webgraph:check :cypher:check :explore:check --no-daemon
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_.*' --no-daemon
```

**Result:**

| Benchmark | Baseline | Attempt 011 | Change |
|-----------|----------|-------------|--------|
| `ExplorerMemoryBenchmark.android_browserForwardExploration` time | `2190.773 ms/op` | `1082.164 ms/op` | `-1108.609 ms` / `-50.6%` |
| `ExplorerMemoryBenchmark.android_browserForwardExploration` retained heap | `215089672 B` | `72174632 B` | `-142915040 B` / `-66.4%` |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` time | `14451.838 ms/op` | `2075.391 ms/op` | `-12376.447 ms` / `-85.6%` |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` retained heap | `713390624 B` | `587591208 B` | `-125799416 B` / `-17.6%` |

**Conclusion:** effective for default explorer interaction latency and materially effective for browser-style forward exploration retained heap. The initial session still retains ~588 MB after forced GC, so this does not fully solve long-running multi-service memory pressure. The remaining retained heap is dominated by routes that still deserialize or summarize broad graph slices, especially overview/API-style inspection paths; follow-up attempts should target streaming or cached bounded summaries rather than merely clamping response sizes.

### 2026-07-25 — Attempt 012: Lazy edge count without forward graph load

**Hypothesis:** `LazyWebGraphBackedGraph.edgeCount()` and `MappedWebGraphBackedGraph.edgeCount()` still call `forward.value.numArcs()`, which can load the forward BVGraph during `/api/graphs`. Since `graph.labels` is one byte per stored edge label, existing persisted files can answer edge count by file size without initializing the forward graph.

**Change:** load `Files.size(graph.labels)` once in `GraphStore.loadLazy` and `GraphStore.loadMapped`, pass that value into the lazy/mapped graph implementations, and return it from `edgeCount()`. The eager WebGraph-backed graph returns the already-loaded label byte-array size.

**Build/save impact:** none. This reuses the existing `graph.labels` persisted file and does not alter build-time analysis or save format.

**Validation commands:**

```
./gradlew :webgraph:check :explore:check --no-daemon
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_.*' --no-daemon
```

**Result:**

| Benchmark | Attempt 011 | Attempt 012 | Change |
|-----------|-------------|-------------|--------|
| `ExplorerMemoryBenchmark.android_browserForwardExploration` time | `1082.164 ms/op` | `1069.718 ms/op` | `-12.446 ms` / `-1.2%` |
| `ExplorerMemoryBenchmark.android_browserForwardExploration` retained heap | `72174632 B` | `72176192 B` | `+1560 B` / unchanged |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` time | `2075.391 ms/op` | `1961.092 ms/op` | `-114.299 ms` / `-5.5%` |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` retained heap | `587591208 B` | `572768584 B` | `-14822624 B` / `-2.5%` |

Compared with the Attempt 010 baseline, the current initial session is `~86.4%` faster and retains `~19.7%` less heap; browser forward exploration is `~51.2%` faster and retains `~66.4%` less heap.

**Conclusion:** correct but not sufficient. Avoiding forward graph initialization from `edgeCount()` removes a real lazy-loading leak and gives a small initial-session improvement, but the remaining retained heap is still ~573 MB. The dominant memory source is no longer graph statistics; it is the broad initial explorer routes that load metadata and/or deserialize large graph slices, especially `/api/overview` and `/api/methods`.

### 2026-07-25 — Attempt 013: Bounded method metadata reads for explorer

**Hypothesis:** the remaining initial-session retained heap is caused by method metadata materialization. Graph statistics call `graph.methods(MethodPattern()).count()`, and `/api/methods?limit=200` calls `graph.methods(pattern).take(limit)`, but WebGraph-backed lazy/mapped implementations load the entire `graph.metadata` object before returning a method sequence.

**Change:**

- add optional `Graph.methodCount()` and `Graph.methodSlice(pattern, limit)` APIs
- answer `methodCount()` for lazy/mapped WebGraph loads by reading only the method count at the start of `graph.metadata`
- answer `methodSlice()` for lazy/mapped WebGraph loads by opening `graph.metadata`, reading method descriptors until `limit` matches are found, then closing the stream
- update explorer `/api/graphs` statistics and `/api/methods` to use these optional bounded APIs before falling back to the legacy full sequence
- keep full metadata lazy loading for hierarchy, annotation, enum, artifact, and branch-scope APIs

**Build/save impact:** none. The persisted metadata format is unchanged; methods were already the first section in `graph.metadata`, so the new loader reads existing bytes more selectively.

**Validation commands:**

```
./gradlew :core:check :webgraph:check :explore:check --no-daemon
./gradlew :cypher:check --no-daemon
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_.*' --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='AndroidLoadBenchmark.mapped_load' --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query' --no-daemon
```

**Explorer result:**

| Benchmark | Attempt 012 | Attempt 013 | Change |
|-----------|-------------|-------------|--------|
| `ExplorerMemoryBenchmark.android_browserForwardExploration` time | `1069.718 ms/op` | `1099.436 ms/op` | `+29.718 ms` / `+2.8%` |
| `ExplorerMemoryBenchmark.android_browserForwardExploration` retained heap | `72176192 B` | `72193728 B` | `+17536 B` / unchanged |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` time | `1961.092 ms/op` | `887.545 ms/op` | `-1073.547 ms` / `-54.7%` |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` retained heap | `572768584 B` | `628232 B` | `-572140352 B` / `-99.9%` |

Compared with the Attempt 010 baseline, the current initial session is `~93.9%` faster and retains `~99.9%` less heap. Browser forward exploration remains `~49.8%` faster and retains `~66.4%` less heap.

**Build/load guardrail result:**

| Benchmark | Recorded guardrail | Attempt 013 | Change |
|-----------|--------------------|-------------|--------|
| `AndroidLoadBenchmark.mapped_load` | `176.097 ms/op` | `153.681 ms/op` | no regression |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `115782.725 ms/op` | `107538.184 ms/op` | no regression |

**Conclusion:** effective. The long-running explorer memory growth was not a JVM leak in this scenario; it was full method metadata being pinned by seemingly bounded explorer routes. Bounded method count/slice APIs keep initial explorer startup effectively flat on heap while preserving full metadata behavior for routes that explicitly need it.

### 2026-07-26 — Attempt 014: Stable explorer resident memory defaults

**Root cause:** after the bounded route fixes, the remaining long-run symptom is not only heap pressure. `graphite-explore` opens graphs through `GraphStore.load()`, whose `AUTO` mode picks `MAPPED` for large graphs. A memory-mapped `graph.nodedata` file does not allocate JVM heap, but every node page touched over time can become resident and show up in process RSS. For a 500 MB graph this looks like a service that only grows even after GC, especially when multiple microservice graphs are queried over a long session.

Two smaller long-lived retention paths were also present:

- `LazyWebGraphBackedGraph` kept every per-thread `RandomAccessFile` in a strong list, so retired Jetty worker threads could leave handles reachable until graph close.
- `ExpressionEvaluator` kept an unbounded regex cache inside long-lived query execution objects.

**Change:**

- add `GraphStore.LoadMode.LAZY` as an explicit load mode
- make `graphite-explore` default to `--load-mode LAZY` so long-running explorer processes use on-demand disk reads instead of mmap residency
- keep `--load-mode MAPPED` available for short-lived local exploration where faster node reads matter more than stable RSS
- close the Javalin app and graph in `ExploreCommand.call()` when the service is interrupted or startup fails
- store lazy graph `RandomAccessFile` handles in a weak set so dead worker threads are not strongly retained by the graph
- bound the Cypher regex cache to a synchronized 256-entry LRU
- update `ExplorerMemoryBenchmark` to measure the explorer default load mode, with a `loadMode` JMH parameter for explicit comparisons

**Build/save impact:** none. The persisted format is unchanged. `GraphStore.load(dir)` keeps its existing `AUTO` behavior for library callers; the stable-memory default is scoped to the long-running explorer CLI.

**Validation commands:**

```
./gradlew :webgraph:test :cypher:test :explore:test --no-daemon
./gradlew :explore:compileJmhKotlin --no-daemon
./gradlew koverLog --no-daemon
./gradlew check -S --no-daemon
```

**Result:** all validations passed. Coverage remains above the project gate:

| Module | Line coverage |
|--------|---------------|
| `core` | `98.1016%` |
| `cypher` | `98.0652%` |
| `explore` | `98.5823%` |
| `query` | `100%` |
| `sootup` | `98.2783%` |
| `webgraph` | `98.9037%` |

**Conclusion:** this addresses the long-running service waterline directly. The explorer no longer defaults to a load mode whose RSS naturally increases as more mapped pages are touched, and the remaining process-level caches introduced by queries/worker threads are bounded or weakly held.

### 2026-07-26 — Attempt 015: Long-running explorer RSS waterline benchmark

**Hypothesis:** the previous explorer memory benchmarks measured retained JVM heap after a small request sequence, but they did not prove the product requirement: a long-running explorer process should keep total process memory below 4 GiB and settle at a stable RSS waterline. The benchmark should fail when this contract is violated, not just report heap counters.

**Change:**

- add `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline`
- sample process RSS through `/proc/self/status` on Linux and `ps` as a local fallback
- keep existing heap counters and add committed heap, max heap, RSS before/after, max RSS, post-warmup RSS growth, and explicit limit counters
- run a warmup phase followed by 256 measured cycles over 512 sampled graph nodes
- include representative explorer traffic: `/api/graphs`, `/api/overview`, `/api/methods`, node detail, outgoing edges, outgoing subgraph expansion, and bounded Cypher
- fail the benchmark when max RSS exceeds `4 GiB` or post-warmup RSS growth exceeds `512 MiB`

**Build/save impact:** none. This is a JMH guardrail only; graph build, save, load, query, and HTTP behavior are unchanged.

**Validation command:**

```
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_longRunningExplorerWaterline' --no-daemon
```

**Result:**

| Metric | Result |
|--------|--------|
| Score | `5929.583 ms/op` |
| Max RSS | `845824000 B` |
| RSS before | `801996800 B` |
| RSS after | `845824000 B` |
| Steady RSS before measured phase | `810778624 B` |
| Post-warmup RSS growth | `35045376 B` |
| RSS limit | `4294967296 B` |
| Stable growth limit | `536870912 B` |
| Max committed heap | `465567744 B` |
| Max used heap | `366639840 B` |
| RSS measurement available | `1` |

**Conclusion:** effective as a verification guardrail. The long-running explorer benchmark now proves the current default `LAZY` explorer session stays well below 4 GiB total process RSS on the Android-scale graph and does not continue climbing after warmup. The measured max RSS is ~0.79 GiB, and post-warmup RSS growth is ~33.4 MiB.

### 2026-07-26 — Attempt 016: Retire lazy explorer default

**Root cause:** `LAZY` kept the explorer process heap small, but it did not reduce the real system cost of repeatedly touching `graph.nodedata`; it moved the pressure from mmap-backed RSS accounting to on-demand file reads and OS page cache behavior. That is not a real optimization for multi-service graph exploration.

**Change:**

- remove `GraphStore.LoadMode.LAZY` from the public load-mode enum
- restore `graphite-explore` default load mode to `AUTO`, which uses eager loading for small graphs and mmap for large graphs
- keep the pre-existing `GraphStore.loadLazy()` method and its tests for compatibility, but stop using it as the explorer solution
- change `ExplorerMemoryBenchmark` default load mode from `LAZY` to `MAPPED`
- change the long-running waterline guardrail to fail on max used heap and post-warmup used-heap growth, while keeping RSS as an observation counter for mmap/page-cache behavior
- update README examples so explorer documents `AUTO`/`MAPPED`, not `LAZY`

**Build/save impact:** none. This removes the rejected explorer load-mode path and benchmark default without changing persisted graph files or build-time graph generation.

**Validation commands:**

```
./gradlew :webgraph:test :explore:test :explore:compileJmhKotlin --no-daemon
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_longRunningExplorerWaterline' --no-daemon
```

**Result:**

| Metric | Result |
|--------|--------|
| Load mode | `MAPPED` |
| Score | `3441.344 ms/op` |
| Max used heap | `368439008 B` |
| Max committed heap | `465567744 B` |
| Used heap before | `122491504 B` |
| Used heap after | `262582088 B` |
| Steady used heap before measured phase | `262569448 B` |
| Post-warmup heap growth | `12640 B` |
| Heap limit | `4294967296 B` |
| Stable heap growth limit | `536870912 B` |
| Max RSS observation | `2089435136 B` |
| Post-warmup RSS observation | `25296896 B` |

**Conclusion:** direction corrected. The explorer is back on the eager/mmap path, and the long-running JMH guardrail now validates the stated heap target directly: max used heap remains ~351 MiB under `-Xmx4g`, and post-warmup heap growth is effectively flat. Follow-up optimization should reduce work done by mmap/eager query paths, starting with explorer routes such as `/api/overview` that still deserialize large numbers of call-site nodes only to compute class-level summaries.

### 2026-07-26 — Attempt 017: Persisted bounded class overview

**Hypothesis:** `/api/overview` is still doing expensive broad graph work on the eager/mmap path: it deserializes up to 100k `CallSiteNode`s only to aggregate class-level call counts and class-to-class edge weights. Persisting that aggregate during save should reduce repeated explorer query work without changing graph loading mode or hiding memory in lazy file reads.

**Change:**

- add optional `Graph.classOverview(limit)` for implementations that can answer class-level summaries without scanning call-site nodes
- write `graph.classoverview` during `GraphStore.save()` from existing node passes, so there is no additional graph traversal
- keep save-time edge aggregation bounded to the top 1000 classes instead of materializing the full class-to-class edge map
- load only the top `limit` class counts and retain only edges whose caller/callee are inside that bounded top-class set
- filter persisted edge records by string-table integer id before resolving strings
- add a single-slot `PersistedClassOverviewProvider` cache per loaded graph: repeated requests reuse the same bounded overview; a larger later limit replaces the cached value instead of accumulating one entry per client-supplied limit
- keep the old call-site scan as a fallback for in-memory graphs and older persisted graphs that do not have `graph.classoverview`

**Build/save impact:** one new persisted file, `graph.classoverview`. Save work is piggybacked on the existing node scans used for string collection, node-count discovery, and node-data writing. The additional save-time state is bounded to top-class counts plus top-class edge weights; existing graphs without the file remain readable and explorer falls back to the old scan.

**Validation commands:**

```
./gradlew :webgraph:test --tests io.johnsonlee.graphite.webgraph.GraphStoreTest :explore:test :explore:compileJmhKotlin --no-daemon
./gradlew koverLog --no-daemon
./gradlew check -S --no-daemon
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_(initialExplorerSession|longRunningExplorerWaterline)' --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='(AndroidLoadBenchmark.mapped_load|GraphEndToEndBenchmark.android_build_save_load_query)' --no-daemon
```

**Explorer result:**

| Benchmark / metric | Previous mapped baseline | Attempt 017 | Change |
|--------------------|--------------------------|-------------|--------|
| `ExplorerMemoryBenchmark.android_initialExplorerSession` time | `887.545 ms/op` | `788.768 ms/op` | `-98.777 ms` / `-11.1%` |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` max used heap | not recorded in the previous table | `124023064 B` | below 4 GiB |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` retained heap | `628232 B` | `1517592 B` | `+889360 B`, single cached summary |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` time | `3441.344 ms/op` | `3349.122 ms/op` | `-92.222 ms` / `-2.7%` |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` max used heap | `368439008 B` | `333782032 B` | `-34656976 B` / `-9.4%` |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` max committed heap | `465567744 B` | `721420288 B` | `+255852544 B` / observation below 4 GiB |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` post-warmup heap growth | `12640 B` | `12864 B` | effectively flat |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` max RSS observation | `2089435136 B` | `990806016 B` | observation only; mmap/page-cache dependent |

**Build/load guardrail result:**

| Benchmark | Recorded guardrail | Attempt 017 | Change |
|-----------|--------------------|-------------|--------|
| `AndroidLoadBenchmark.mapped_load` | `153.681 ms/op` | `150.959 ms/op` | no regression |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `107538.184 ms/op` | `106805.988 ms/op` | no regression |

`koverLog` and `check -S` also passed after the save-time aggregation was bounded. Relevant line coverage recovered above the CI gate: `core` was `98.0592%`, and `webgraph` was `98.8053%`.

Intermediate full-materialization versions were rejected before commit. A full query-time summary regressed long-running explorer time to `3604.045 ms/op` and raised max used heap to `403637776 B`; a full save-time class edge map also added unnecessary large-graph save pressure. The committed shape bounds both save-time aggregation and query-time caching.

**Conclusion:** effective for the targeted eager/mmap query path. The explorer remains on `MAPPED` under the benchmark, max used heap stays around `318 MiB`, warmup-after heap growth stays flat, and `/api/overview` no longer repeatedly deserializes broad call-site slices for common bounded overview requests.

### 2026-07-27 — Attempt 018: Compact mapped edge metadata residency

**Hypothesis:** after removing lazy explorer default and adding persisted overviews, the long-running `MAPPED` explorer waterline is dominated by loaded-graph resident metadata rather than retained route responses. Two structures are unnecessarily expensive for ordinary forward exploration:

- edge decoding looks up `graph.comparisons` for every edge, which initializes a heap `HashMap<Long, BranchComparison>` even when the edge label is not `ControlFlowEdge`
- loaded graphs keep `nodeId -> nodedata offset` and cumulative outdegree offsets as `LongArray`s, even though Android-scale `graph.nodedata` and `graph.labels` are byte-addressed with `Int` indexes

**Change:**

- introduce `BranchComparisonLookup`
- keep eager graphs on a map-backed lookup, but switch lazy/mapped graphs to a lazy memory-mapped binary lookup over `graph.comparisons`
- short-circuit comparison lookup unless the encoded edge family is `ControlFlowEdge`
- replace loaded-graph cumulative outdegree offsets with `IntArray`, with an explicit overflow guard matching the existing label `ByteArray` address limit
- add a compact `NodeOffsetIndex`: use `IntArray` offsets when `graph.nodedata <= Int.MAX_VALUE`, otherwise retain the `LongArray` fallback

**Build/save impact:** no persisted format change and no extra save pass. The compact offset choices happen only while loading a persisted graph. The comparison file is not deserialized into heap for mapped/lazy graphs; it is mapped only if a ControlFlow edge actually asks for branch comparison data.

**Validation commands:**

```
./gradlew :webgraph:test --tests io.johnsonlee.graphite.webgraph.GraphStoreTest --no-daemon
./gradlew koverLog --no-daemon
./gradlew check -S --no-daemon
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_initialExplorerSession' --no-daemon
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_(browserForwardExploration|longRunningExplorerWaterline)' --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='(AndroidLoadBenchmark.mapped_load|AndroidQueryBenchmark.mapped_(simpleNodeMatch|singleHopRelationship|returnDistinct)|GraphEndToEndBenchmark.android_build_save_load_query)' --no-daemon
```

**Explorer result:**

| Benchmark / metric | Attempt 017 / prior mapped baseline | Attempt 018 | Change |
|--------------------|--------------------------------------|-------------|--------|
| `ExplorerMemoryBenchmark.android_initialExplorerSession` time | `788.768 ms/op` | `800.622 ms/op` | `+11.854 ms` / `+1.5%`, small-run variance |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` retained heap | `1517592 B` | `1502984 B` | effectively unchanged |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` max used heap | `124023064 B` | `100948440 B` | `-23074624 B` / `-18.6%` |
| `ExplorerMemoryBenchmark.android_browserForwardExploration` time | `1099.436 ms/op` | `1107.741 ms/op` | `+8.305 ms` / `+0.8%` |
| `ExplorerMemoryBenchmark.android_browserForwardExploration` retained heap | `72193728 B` | `49119328 B` | `-23074400 B` / `-32.0%` |
| `ExplorerMemoryBenchmark.android_browserForwardExploration` max used heap | not recorded in the prior table | `149020856 B` | below 4 GiB |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` time | `3349.122 ms/op` | `3000.117 ms/op` | `-349.005 ms` / `-10.4%` |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` max used heap | `333782032 B` | `219431008 B` | `-114351024 B` / `-34.3%` |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` max committed heap | `721420288 B` | `364904448 B` | `-356515840 B` / `-49.4%` |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` post-warmup heap growth | `12864 B` | `14392 B` | effectively flat |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` steady used heap before/after | not recorded in the prior table | `150224992 B` -> `150239384 B` | stable waterline |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` max RSS observation | `990806016 B` | `785154048 B` | observation only; mmap/page-cache dependent |

**Build/load/query guardrail result:**

| Benchmark | Attempt 017 / recorded baseline | Attempt 018 | Change |
|-----------|----------------------------------|-------------|--------|
| `AndroidLoadBenchmark.mapped_load` | `150.959 ms/op` | `155.265 ms/op` | `+4.306 ms` / `+2.9%`, within small-run variance |
| `AndroidQueryBenchmark.mapped_returnDistinct` | `0.197 ms/op` | `0.190 ms/op` | no regression |
| `AndroidQueryBenchmark.mapped_simpleNodeMatch` | `0.085 ms/op` | `0.085 ms/op` | unchanged |
| `AndroidQueryBenchmark.mapped_singleHopRelationship` | `0.701 ms/op` | `0.635 ms/op` | no regression |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `106805.988 ms/op` | `107200.646 ms/op` | `+394.658 ms` / `+0.37%` |

`git diff --check`, `koverLog`, `:webgraph:koverLog`, `:webgraph:check -S`, and `check -S` passed. Coverage remained above the CI gate: `core` `98.0592%`, `cypher` `98.0652%`, `explore` `98.0691%`, `sootup` `98.2783%`, `webgraph` `98.6422%`, and `query` `100%`.

**Conclusion:** this is a real eager/mmap residency reduction, not a lazy-mode relocation. Under the long-running explorer workload, warmed-up heap stays flat around `150 MiB`, max used heap drops by roughly one third from Attempt 017, and query/build-save-load guardrails stay effectively unchanged. The remaining large residents are the forward BVGraph, label bytes, string table, node type index, and the compact node offset index; those are inherent to serving forward graph queries without eager node materialization.

### 2026-07-27 — Attempt 019: Remove lazy load mode and make mmap query-ready

**Hypothesis:** keeping `GraphStore.loadLazy()` and the seek-based `LazyWebGraphBackedGraph` leaves a second load strategy that can hide memory and latency in the first query instead of improving the eager/mmap path. `MAPPED` should open the forward graph structures it needs for normal forward traversal during load, while still keeping node records off heap with mmap.

**Change:**

- remove `GraphStore.loadLazy()`, `LazyWebGraphBackedGraph`, lazy JMH cases, lazy-specific tests, and stale detekt baseline entries
- remove `LazyMappedBranchComparisonLookup`; mapped graphs now establish the mmap comparison lookup during load
- change `MappedWebGraphBackedGraph` to hold direct `ImmutableGraph`, label bytes, and cumulative outdegree values instead of `Lazy<T>` wrappers
- parallelize mapped load across forward BVGraph, string table, node index, labels, method count, and comparison mmap setup
- change node deserialization from `DataInputStream`-only to `DataInput`, and use a thread-local `ByteBufferDataInput` for mapped node reads to avoid per-node `ByteBufferInputStream` and `DataInputStream` allocations

**Build/save impact:** no persisted format change and no extra save pass. This changes loaded-graph behavior only: mapped graphs are ready for forward edge traversal after load, and node data remains mmap-backed rather than heap-resident.

**Validation commands:**

```
git diff --check
./gradlew :webgraph:test --tests io.johnsonlee.graphite.webgraph.GraphStoreTest --no-daemon
./gradlew :webgraph:jmhClasses --no-daemon
./gradlew :webgraph:koverLog --no-daemon
./gradlew :webgraph:check -S --no-daemon
./gradlew check -S --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='(AndroidLoadBenchmark.mapped_load|AndroidQueryBenchmark.mapped_(simpleNodeMatch|singleHopRelationship|returnDistinct)|GraphEndToEndBenchmark.android_build_save_load_query)' --no-daemon
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_(initialExplorerSession|browserForwardExploration|longRunningExplorerWaterline)' --no-daemon
```

**Explorer result:**

| Benchmark / metric | Attempt 018 | Attempt 019 | Change |
|--------------------|-------------|-------------|--------|
| `ExplorerMemoryBenchmark.android_initialExplorerSession` time | `800.622 ms/op` | `793.486 ms/op` | `-7.136 ms` / `-0.9%` |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` retained heap | `1502984 B` | `1512656 B` | effectively unchanged |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` max used heap | `100948440 B` | `149452392 B` | edge structures are now accounted for before the first request; still far below 4 GiB |
| `ExplorerMemoryBenchmark.android_browserForwardExploration` time | `1107.741 ms/op` | `795.411 ms/op` | `-312.330 ms` / `-28.2%` |
| `ExplorerMemoryBenchmark.android_browserForwardExploration` retained heap | `49119328 B` | `584600 B` | `-48534728 B` / `-98.8%` |
| `ExplorerMemoryBenchmark.android_browserForwardExploration` max used heap | `149020856 B` | `148523312 B` | effectively unchanged |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` time | `3000.117 ms/op` | `2809.244 ms/op` | `-190.873 ms` / `-6.4%` |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` max used heap | `219431008 B` | `220201920 B` | effectively unchanged and far below 4 GiB |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` max committed heap | `364904448 B` | `532676608 B` | forward structures now initialized during load; still far below 4 GiB |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` steady used heap before/after | `150224992 B` -> `150239384 B` | `150089976 B` -> `149957784 B` | stable waterline |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` retained heap | `50380376 B` | `1918784 B` | `-48461592 B` / `-96.2%` |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` RSS growth | `10534912 B` | `8241152 B` | observation only; mmap/page-cache dependent |

**Build/load/query guardrail result:**

| Benchmark | Attempt 018 | Attempt 019 | Change |
|-----------|-------------|-------------|--------|
| `AndroidLoadBenchmark.mapped_load` | `155.265 ms/op` | `269.822 ms/op` | not directly comparable: Attempt 019 includes forward graph and labels in load instead of deferring them to first query |
| `AndroidQueryBenchmark.mapped_returnDistinct` | `0.190 ms/op` | `0.176 ms/op` | no regression |
| `AndroidQueryBenchmark.mapped_simpleNodeMatch` | `0.085 ms/op` | `0.074 ms/op` | no regression |
| `AndroidQueryBenchmark.mapped_singleHopRelationship` | `0.635 ms/op` | `0.632 ms/op` | no regression |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `107200.646 ms/op` | `105662.095 ms/op` | `-1538.551 ms` / `-1.4%`, no regression |

`GraphStoreTest`, `jmhClasses`, `git diff --check`, `:webgraph:koverLog`, `:webgraph:check -S`, and `check -S` passed. `webgraph` line coverage remained above the CI gate at `98.3038%`.

**Conclusion:** the explicit lazy mode is gone. The mapped path is now an eager+mmap path for forward graph serving: node records stay off heap, comparison metadata is mmap-backed, and forward graph/labels are initialized during load instead of being moved to the first query. This is progress toward the target, but it does not complete the broader goal: mapped load still needs another round of real optimization because the old `mapped_load` number was partly achieved by deferred initialization.

### 2026-07-27 — Attempt 020: Memory-map mapped node indexes

**Hypothesis:** after lazy load mode is removed, the mapped graph's remaining avoidable resident heap includes two node indexes: `nodeId -> offset` and `type -> nodeIds`. Keeping those as primitive JVM arrays still costs tens of megabytes per loaded microservice. Persisting them as mmap-backed auxiliary files should lower the long-running explorer heap waterline without moving work to first query.

**Change:**

- add `graph.nodeoffsets`, a dense mmap-backed `nodeId -> offset + 1` long table with `0` as the missing-node sentinel
- add `graph.typeindex`, a mmap-backed table of node-type ranges followed by packed node-id arrays
- write both files during the existing nodedata/nodeindex save pass using the node type counts collected in the first node scan; no extra full graph pass is added to save
- keep `graph.nodeindex` as the compatibility source, and rebuild the mmap auxiliary files from it when loading an older persisted graph
- replace mapped graph heap type buckets with `NodeTypeIndex`
- remove the thread-local mapped-node reader so long-lived explorer worker threads do not retain graph-specific mapped buffers after graph replacement

**Build/save impact:** no extra graph traversal. Save writes two additional sequential/mmap files while it already streams nodes and writes `graph.nodeindex`. Older persisted graphs remain readable because `loadMapped` can rebuild `graph.nodeoffsets` and `graph.typeindex` from `graph.nodeindex`.

**Validation commands:**

```
git diff --check
./gradlew :webgraph:test --tests io.johnsonlee.graphite.webgraph.GraphStoreTest --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='(AndroidLoadBenchmark.mapped_load|AndroidQueryBenchmark.mapped_(simpleNodeMatch|singleHopRelationship|returnDistinct)|GraphEndToEndBenchmark.android_build_save_load_query)' --no-daemon
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_(initialExplorerSession|browserForwardExploration|longRunningExplorerWaterline)' --no-daemon
./gradlew :webgraph:check -S --no-daemon
./gradlew :webgraph:koverLog --no-daemon
./gradlew check -S --no-daemon
```

**Explorer result:**

| Benchmark / metric | Attempt 019 | Attempt 020 | Change |
|--------------------|-------------|-------------|--------|
| `ExplorerMemoryBenchmark.android_initialExplorerSession` time | `793.486 ms/op` | `788.340 ms/op` | `-5.146 ms` / `-0.6%` |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` retained heap | `1512656 B` | `1515104 B` | effectively unchanged |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` max used heap | `149452392 B` | `96311104 B` | `-53141288 B` / `-35.6%` |
| `ExplorerMemoryBenchmark.android_initialExplorerSession` max committed heap | `532676608 B` | `343932928 B` | `-188743680 B` / `-35.4%` |
| `ExplorerMemoryBenchmark.android_browserForwardExploration` time | `795.411 ms/op` | `836.186 ms/op` | `+40.775 ms` / `+5.1%` |
| `ExplorerMemoryBenchmark.android_browserForwardExploration` retained heap | `584600 B` | `578720 B` | effectively unchanged |
| `ExplorerMemoryBenchmark.android_browserForwardExploration` max used heap | `148523312 B` | `95769464 B` | `-52753848 B` / `-35.5%` |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` time | `2809.244 ms/op` | `2790.566 ms/op` | `-18.678 ms` / `-0.7%` |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` max used heap | `220201920 B` | `165903768 B` | `-54298152 B` / `-24.7%` |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` max committed heap | `532676608 B` | `350224384 B` | `-182452224 B` / `-34.3%` |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` steady used heap before/after | `150089976 B` -> `149957784 B` | `96797384 B` -> `96708488 B` | stable waterline, about `53 MB` lower |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` retained heap | `1918784 B` | `1913408 B` | effectively unchanged |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` RSS growth | `8241152 B` | `7258112 B` | observation only; mmap/page-cache dependent |

**Build/load/query guardrail result:**

| Benchmark | Attempt 019 | Attempt 020 | Change |
|-----------|-------------|-------------|--------|
| `AndroidLoadBenchmark.mapped_load` | `269.822 ms/op` | `256.958 ms/op` | `-12.864 ms` / `-4.8%` |
| `AndroidQueryBenchmark.mapped_returnDistinct` | `0.176 ms/op` | `0.167 ms/op` | no regression |
| `AndroidQueryBenchmark.mapped_simpleNodeMatch` | `0.074 ms/op` | `0.067 ms/op` | no regression |
| `AndroidQueryBenchmark.mapped_singleHopRelationship` | `0.632 ms/op` | `0.642 ms/op` | `+0.010 ms` / `+1.6%`, within noise |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `105662.095 ms/op` | `108408.647 ms/op` | `+2746.552 ms` / `+2.6%` |

**Conclusion:** this is a real resident-heap reduction on the eager+mmap path, not a lazy relocation. The long-running explorer benchmark stabilizes around `97 MB` used heap after warmup, max used heap stays around `166 MB`, and max committed heap stays around `350 MB` under `-Xmx4g`. Loading improves modestly because mapped load no longer reconstructs node offset/type arrays on heap. Query performance is materially unchanged, but the Android end-to-end single-shot run is `2.6%` slower and does not satisfy the larger "order-of-magnitude loading improvement" target yet.

`GraphStoreTest`, `:webgraph:check -S`, `:webgraph:koverLog`, `check -S`, and `git diff --check` passed after the coverage backfill. `webgraph` line coverage is `98.8243%`.

### 2026-07-28 — Attempt 021: Compress lazy backward graph residency

**Hypothesis:** lazy backward construction only defers memory until the first
incoming traversal. It does not fix a long-running explorer process, because
the uncompressed transpose then remains resident for the lifetime of the loaded
graph. To keep memory at a stable waterline across many microservices, the
incoming path must replace that uncompressed resident structure with a compact
form after the first use, without adding build/save cost.

**Change:**

- keep `GraphStore.save()` forward-only; `backward.*` is not written during build/save
- on the first `incoming()` query, load existing `backward.*` when present
- when `backward.*` is missing, build the transpose, store it as compressed BVGraph files when possible, reload the compressed graph, and allow the temporary flat arrays to be collected
- when the graph directory is read-only, fall back to a transient compressed backward graph for the current process instead of retaining the uncompressed transpose
- change precomputed adjacency offsets from `LongArray` to `IntArray`, matching the existing `ByteArray` edge-label address limit
- add `ExplorerMemoryBenchmark.android_incomingExplorerWaterline`
- tighten the long-running waterline guardrail to fail when post-warmup heap growth exceeds `16 MiB`, and add a post-warmup RSS growth guardrail of `64 MiB`

**Build/save impact:** no extra graph traversal and no backward compression in
the save path. The first incoming query pays a one-time transpose compression
cost; later queries and later explorer processes reuse the compressed
`backward.*` files when the graph directory is writable.

**Rejected before commit:**

| Approach | Result | Reason |
|----------|--------|--------|
| mmap `graph.labeloffsets` | `mapped_singleHopRelationship` regressed to `1.551 ms/op`; Android end-to-end regressed to `278193.662 ms/op` | moved offset residency but made hot edge-label access slower |
| file-channel node reads | long-running RSS growth was not better than mmap | moved page-cache behavior to syscalls without improving the waterline |

**Validation commands:**

```
./gradlew :webgraph:test --tests io.johnsonlee.graphite.webgraph.GraphStoreTest :explore:test --tests io.johnsonlee.graphite.cli.ExploreCommandTest :webgraph:detekt :explore:detekt --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='(AndroidLoadBenchmark.mapped_load|AndroidQueryBenchmark.mapped_singleHopRelationship|GraphEndToEndBenchmark.android_build_save_load_query)' --no-daemon
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_(incomingExplorerWaterline|longRunningExplorerWaterline)' --no-daemon
./gradlew check -S --no-daemon
./gradlew koverLog --no-daemon
```

**Explorer waterline result:**

| Benchmark / metric | Attempt 020 / prior | Attempt 021 | Change |
|--------------------|---------------------|-------------|--------|
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` time | `2790.566 ms/op` | `2848.062 ms/op` | `+57.496 ms` / `+2.1%`, within single-shot variance |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` max used heap | `165903768 B` | `166313592 B` | effectively unchanged |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` steady used heap before/after | `96797384 B` -> `96708488 B` | `97210072 B` -> `97123592 B` | stable waterline |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` post-warmup heap growth | effectively flat | `0 B` | stable |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` post-warmup RSS growth | `7258112 B` | `15302656 B` | below `64 MiB` guardrail |
| `ExplorerMemoryBenchmark.android_incomingExplorerWaterline` time | not previously measured | `4746.793 ms/op` | includes first incoming compression |
| `ExplorerMemoryBenchmark.android_incomingExplorerWaterline` steady used heap before/after | not previously measured | `113776400 B` -> `113791136 B` | `14736 B` growth |
| `ExplorerMemoryBenchmark.android_incomingExplorerWaterline` post-warmup RSS growth | not previously measured | `8077312 B` | below `64 MiB` guardrail |

**Build/load/query guardrail result:**

| Benchmark | Attempt 020 | Attempt 021 | Change |
|-----------|-------------|-------------|--------|
| `AndroidLoadBenchmark.mapped_load` | `256.958 ms/op` | `256.929 ms/op` | no regression |
| `AndroidQueryBenchmark.mapped_singleHopRelationship` | `0.642 ms/op` | `0.667 ms/op` | `+0.025 ms` / `+3.9%`, within CI/noise |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `108408.647 ms/op` | `106287.182 ms/op` | no regression |

`git diff --check`, targeted tests/detekt, `check -S`, and `koverLog`
passed. Line coverage stayed above the gate: `core` `98.0592%`, `cypher`
`98.0652%`, `explore` `98.0691%`, `query` `100%`, `sootup` `98.2783%`,
and `webgraph` `98.3439%`.

**Conclusion:** lazy mode by itself was only relocation. This change keeps the
lazy trigger for forward-only load performance, but it changes the post-trigger
resident form: the uncompressed incoming transpose is no longer kept as the
steady-state graph. Under both forward and incoming explorer workloads, used
heap remains flat after warmup and RSS growth stays below the explicit guardrail.

### 2026-07-28 — Attempt 022: Persist heap label prefix offsets

**Hypothesis:** after lazy load mode is removed, `mapped_load` is query-ready but
still spends time rebuilding `cumulativeOutdeg` by calling `forward.outdegree()`
for every node. That work exists only to find the byte offset into
`graph.labels` during edge decoding. The array is already available as
`forwardAdj.offsets` while saving and is already kept as a heap `IntArray` while
serving queries, so persisting the same `IntArray` should improve load without
changing the query hot path or increasing steady heap.

This is intentionally different from the rejected mmap `graph.labeloffsets`
experiment: the accepted shape loads the prefix into the same heap `IntArray`
used before, so edge-label lookup remains an array access.

**Change:**

- add `graph.labelprefix`, a persisted `int[]` cumulative outdegree table
- write it during `GraphStore.save()` from the existing `forwardAdj.offsets`; no
  extra graph traversal is added
- load `graph.labelprefix` in eager and mapped graph loads when it is present
- fall back to rebuilding the prefix from `forward.*` when older graphs lack the
  file or when the auxiliary file is corrupt

**Rejected before commit:**

| Approach | Result | Reason |
|----------|--------|--------|
| `BVGraph.loadMapped(forward)` for mapped graphs | `mapped_load` regressed to `268.716 ms/op`; `mapped_singleHopRelationship` regressed to `0.800 ms/op` | moved forward graph bytes out of heap but made both load and hot query slower |

**Validation commands:**

```
./gradlew :webgraph:test --tests io.johnsonlee.graphite.webgraph.GraphStoreTest :webgraph:detekt --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='(AndroidLoadBenchmark.mapped_load|AndroidQueryBenchmark.mapped_singleHopRelationship|GraphEndToEndBenchmark.android_build_save_load_query)' --no-daemon
./gradlew :explore:jmh -Pjmh.filter='ExplorerMemoryBenchmark.android_(incomingExplorerWaterline|longRunningExplorerWaterline)' --no-daemon
./gradlew check -S --no-daemon
./gradlew koverLog --no-daemon
```

**Build/load/query guardrail result:**

| Benchmark | Attempt 021 | Attempt 022 | Change |
|-----------|-------------|-------------|--------|
| `AndroidLoadBenchmark.mapped_load` | `256.929 ms/op` | `138.768 ms/op` | `-118.161 ms` / `-46.0%`; `~16.5x` faster than the original `2292.892 ms/op` baseline |
| `AndroidQueryBenchmark.mapped_singleHopRelationship` | `0.667 ms/op` | `0.633 ms/op` | no regression |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `106287.182 ms/op` | `105041.169 ms/op` | no regression |

**Explorer waterline result:**

| Benchmark / metric | Attempt 021 | Attempt 022 | Change |
|--------------------|-------------|-------------|--------|
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` time | `2848.062 ms/op` | `3142.191 ms/op` | single-shot route variance; guardrails pass |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` max used heap | `166313592 B` | `167434568 B` | effectively unchanged |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` steady used heap before/after | `97210072 B` -> `97123592 B` | `97179960 B` -> `97334336 B` | `154376 B` growth, below `16 MiB` |
| `ExplorerMemoryBenchmark.android_longRunningExplorerWaterline` post-warmup RSS growth | `15302656 B` | `9699328 B` | below `64 MiB` |
| `ExplorerMemoryBenchmark.android_incomingExplorerWaterline` max used heap | `192419600 B` | `193384752 B` | effectively unchanged |
| `ExplorerMemoryBenchmark.android_incomingExplorerWaterline` steady used heap before/after | `113776400 B` -> `113791136 B` | `113692976 B` -> `113706200 B` | `13224 B` growth, below `16 MiB` |
| `ExplorerMemoryBenchmark.android_incomingExplorerWaterline` post-warmup RSS growth | `8077312 B` | `5767168 B` | below `64 MiB` |

**Conclusion:** accepted. `mapped_load` now clears the strict order-of-magnitude
target even after making mapped graphs query-ready: `138.768 ms/op` vs the
original `2292.892 ms/op` baseline. The load improvement is not achieved by
deferring work to first query; the hot query path still uses the same heap
prefix array, and the long-running explorer guardrails remain stable.

`git diff --check`, targeted tests/detekt, `check -S`, and `koverLog`
passed. Line coverage stayed above the gate: `core` `98.0592%`, `cypher`
`98.0652%`, `explore` `98.0691%`, `query` `100%`, `sootup` `98.2783%`,
and `webgraph` `98.3513%`.

### 2026-07-30 — Attempt 023: Remove redundant build and save scans

**Question:** why did previous load/query work not noticeably improve
`GraphEndToEndBenchmark`?

Because the end-to-end benchmark is dominated by `JAR -> build -> save`, not by
mapped load or Cypher query. Attempts 018-022 made serving a persisted graph much
cheaper and stabilized long-running explorer heap, but they mostly left
`JavaProjectLoader` and the `GraphStore.save()` metadata collection path
unchanged.

**Hypothesis:** remove repeated work in the current build/save path without
adding permanent heap:

- build the temporary mmap node type index from compact node record headers,
  instead of deserializing every node after writing it
- build temporary edge indexes through buffered sequential scans, instead of
  per-record `RandomAccessFile` reads
- scan all mmap nodes in node-id order for full-graph save passes, avoiding
  type-grouped random mmap reads
- reuse graph-owned member annotation and type hierarchy indexes during metadata
  collection, instead of rediscovering them from all nodes
- reduce SootUp adapter hashing work by using identity keys for per-method
  statement maps and caching branch reachability within each method

**Environment:**

- machine: macOS arm64, Darwin 23.3.0
- JVM: OpenJDK 17.0.18 Homebrew
- fixture: Android SDK jar discovered from the local Gradle cache
- JMH mode: SingleShotTime, one fork

**Validation commands:**

```
./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraph$' --no-daemon
./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig' --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query' --no-daemon
java -Xmx4g -jar frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar 'GraphEndToEndBenchmark.android_build_save_load_query' -wi 0 -i 1 -f 1 -bm ss -tu ms -prof gc
./gradlew :core:check :webgraph:check --no-daemon
./gradlew :sootup:check --no-daemon
```

**Main comparison:**

| Benchmark | `main` (`74e937a`) | Attempt 023 | Change |
|-----------|--------------------|-------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraph` | `95195.133 ms/op` | `27647.563 ms/op` | `-67547.570 ms` / `-70.96%`, `3.44x` faster |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `111921.044 ms/op` | `35765.348 ms/op` | `-76155.696 ms` / `-68.04%`, `3.13x` faster |

**Stage attribution:**

| Benchmark / metric | Result |
|--------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `26825.472 ms/op` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `35765.348 ms/op` |
| Approximate save + mapped load + query remainder | `~8939.876 ms/op` |
| E2E with GC profiler | `37315.659 ms/op`, `71960564456 B/op`, `122` GCs, `1719 ms` GC time |

**Conclusion:** accepted as a meaningful end-to-end improvement, but not the
requested order-of-magnitude improvement. The optimized path is now roughly
`3.1x` faster than `main` and still runs under `-Xmx4g`; the remaining lower
bound is the SootUp/Jimple method-body walk itself. Reaching `10x` from here
requires a larger architectural change, most likely an ASM-first builder for the
parts of the graph that can be emitted directly, or an explicit reduction in the
graph semantics built from bytecode.

### 2026-07-31 — Attempt 024: Reduce mmap build/save allocation overhead

**Question:** why did Attempt 023 still miss the order-of-magnitude target?

The build-only benchmark had already fallen from `~95s` to `~27s`, but the
end-to-end path was still paying avoidable allocation and decoding cost in two
places: temporary mmap graph construction and `GraphStore.save()` forward
adjacency generation.

**Changes retained:**

- write temporary mmap node type references as builder-local type ids instead of
  repeatedly UTF-8 encoding the same type class names
- collect the temporary mmap node type index with primitive int buffers instead
  of boxed `MutableList<Int>` values
- use an identity map for the builder-local method id table; the SootUp adapter
  already canonicalizes `MethodDescriptor` instances
- let `MmapGraph` expose target-only outgoing scans so `GraphStore.save()` can
  count unique outdegree without decoding full edge objects
- replace per-node `MutableSet`/`MutableMap`/`sorted()` allocation in forward
  data construction with reusable primitive scratch arrays while preserving
  sorted targets and "last edge for duplicate target wins" semantics

**Rejected during this attempt:** lazy `ControlFlowIndex` successors, local
identity-key caching in `SootUpAdapter`, invoke argument loop rewrites, and a raw
mmap edge-record save path. Each either regressed the Android single-shot score
or failed to show a stable improvement, so those changes were removed.

**Environment:**

- machine: macOS arm64, Darwin 23.3.0
- JVM: OpenJDK 17.0.18 Homebrew
- fixture: Android SDK jar discovered from the local Gradle cache
- JMH mode: SingleShotTime, one fork

**Validation commands:**

```
./gradlew :core:check :webgraph:check :sootup:check --no-daemon
./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraph$' --no-daemon
./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig' --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query' --no-daemon
java -Xmx4g -Delasticsearch.jar.path=... -Dandroid.jar.path=... -jar frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar 'GraphEndToEndBenchmark.android_build_save_load_query$' -wi 0 -i 1 -f 1 -bm ss -tu ms -prof gc
```

**Main comparison:**

| Benchmark | `main` (`74e937a`) | Attempt 024 | Change |
|-----------|--------------------|-------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraph` | `95195.133 ms/op` | `19971.867 ms/op` | `-75223.266 ms` / `-79.02%`, `4.77x` faster |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `111921.044 ms/op` | `25594.160 ms/op` | `-86326.884 ms` / `-77.13%`, `4.37x` faster |

**Attempt 023 comparison:**

| Benchmark / metric | Attempt 023 | Attempt 024 | Change |
|--------------------|-------------|-------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraph` | `27647.563 ms/op` | `19971.867 ms/op` | `-7675.696 ms` / `-27.76%` |
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `26825.472 ms/op` | `19768.045 ms/op` | `-7057.427 ms` / `-26.31%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `35765.348 ms/op` | `25594.160 ms/op` | `-10171.188 ms` / `-28.44%` |
| Approximate save + mapped load + query remainder | `~8939.876 ms/op` | `~5826.115 ms/op` | `-3113.761 ms` |
| E2E with GC profiler | `37315.659 ms/op`, `71960564456 B/op`, `122` GCs, `1719 ms` GC time | `28605.346 ms/op`, `39170535112 B/op`, `71` GCs, `946 ms` GC time | allocation `-45.57%`, GC count `-41.80%` |

**Conclusion:** accepted as another concrete build/save improvement under the
same `-Xmx4g` end-to-end heap setting. It still does not prove the requested
`10x` improvement: the current Android end-to-end path is `4.37x` faster than
`main`, not `10x`. The remaining lower bound is still dominated by SootUp/Jimple
body materialization plus the adapter's full statement walk.

### 2026-07-31 — Attempt 025: Reject explicit ASM fast-build mode

**Question:** can the Android `JAR -> build -> save -> mapped load -> query`
path cross the requested order-of-magnitude target without raising the heap cap?

Attempt 024 showed that the standard path was still bounded by SootUp/Jimple body
materialization. Attempt 025 tested an explicit `LoaderConfig.fastBuild` mode,
exposed in the CLI as `graphite build --fast-build`, that bypassed SootUp when
call graph metadata, annotation nodes, and cross-method functional dispatch were
disabled.

**Semantic boundary:**

- enabled only when `fastBuild=true`, `buildCallGraph=false`,
  `extractAnnotations=false`, and `trackCrossMethodFunctionalDispatch=false`
- kept bytecode-derived type hierarchy, methods, fields, parameters, return
  nodes, call sites, basic operand dataflow, class origins, artifact dependency
  metadata, and resource file nodes
- dropped full SootUp/Jimple statement semantics, call graph metadata,
  annotation nodes, and cross-method functional dispatch

**Validation commands:**

```
./gradlew :core:check :sootup:check :query:check :webgraph:check --no-daemon
./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraph(EndToEndConfig|FastEndToEndConfig)?$' --no-daemon
./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android(_fast)?_build_save_load_query$' --no-daemon
java -Xmx4g -Delasticsearch.jar.path=... -Dandroid.jar.path=... -jar frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar 'GraphEndToEndBenchmark.android_fast_build_save_load_query$' -wi 0 -i 1 -f 1 -bm ss -tu ms -prof gc
```

**Main comparison:**

| Benchmark | `main` (`74e937a`) | Latest standard path | Fast candidate | Change vs main |
|-----------|--------------------|----------------------|----------------|----------------|
| `GraphBuildBenchmark.buildAndroidSdkGraph` | `95195.133 ms/op` | `20230.262 ms/op` | N/A | standard: `4.71x` faster |
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | N/A | `19350.774 ms/op` | N/A | standard stage attribution |
| `GraphBuildBenchmark.buildAndroidSdkGraphFastEndToEndConfig` | `95195.133 ms/op` | N/A | `4550.565 ms/op` | fast: `20.92x` faster |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `111921.044 ms/op` | `25197.778 ms/op` | N/A | standard: `4.44x` faster |
| `GraphEndToEndBenchmark.android_fast_build_save_load_query` | `111921.044 ms/op` | N/A | `10171.204 ms/op` | fast: `11.00x` faster |

**Heap/GC comparison under the same `-Xmx4g` cap:**

| Benchmark / metric | Attempt 024 standard path | Fast candidate | Change |
|--------------------|---------------------------|----------------|--------|
| E2E with GC profiler | `28605.346 ms/op`, `39170535112 B/op`, `71` GCs, `946 ms` GC time | `8684.932 ms/op`, `6677828640 B/op`, `23` GCs, `153 ms` GC time | allocation `-82.95%`, GC count `-67.61%` |

**Conclusion:** rejected for the product objective. The bytecode-only path crossed
`10x` under the same `-Xmx4g` cap, but it achieved that by making graph semantics
optional. A user-visible `--fast-build` mode is therefore only useful as an
upper-bound experiment and is not retained in the product code.

### 2026-08-01 — Attempt 026: Reject SootUp descriptor and invoke micro fast paths

**Question:** can the remaining standard SootUp/Jimple end-to-end gap be reduced
with allocation-oriented micro-optimizations, while keeping the same graph
semantics and max heap settings?

Attempt 026 tested small optimizations inside the standard adapter only:

- cache `TypeDescriptor` instances by normalized class name in addition to Soot
  `Type` identity
- skip method-defining-class resolution and full argument-list construction for
  invokedynamic and boxing/unboxing invokes that are modeled without ordinary
  call sites
- skip method hierarchy lookup for constructors, whose declaring class is the
  defining class

**Environment:**

- machine: macOS arm64, Darwin 26.5.2
- JVM: OpenJDK 17.0.20 Homebrew
- fixture: Android SDK jar from Gradle cache
- build-only JMH heap: unchanged `-Xmx8g`
- end-to-end JMH heap: unchanged `-Xmx4g`
- JMH mode: SingleShotTime, one fork

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:check --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Micro comparison:**

| Benchmark / candidate | Score |
|-----------------------|-------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig`, descriptor cache only | `26396.880 ms/op` |
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig`, descriptor cache + invoke early returns | `25432.894 ms/op` |
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig`, invoke early returns only | `26229.148 ms/op` |

**End-to-end negative control:**

| Benchmark | Candidate | Same-environment control with candidate code removed | Change |
|-----------|-----------|------------------------------------------------------|--------|
| `GraphEndToEndBenchmark.android_build_save_load_query` | `33282.163 ms/op` | `33164.647 ms/op` | `+117.516 ms` / `+0.35%` slower |

**Conclusion:** rejected. The candidate kept SootUp/Jimple semantics and did not
raise the max heap, and `:sootup:check` passed while the code was present, but
the real end-to-end benchmark did not improve. No product code from this attempt
is retained.

### 2026-08-01 — Attempt 027: Reject enum completion work on ASM fast path

**Question:** can the fast builder move closer to the standard SootUp/Jimple
graph semantics without raising the end-to-end max heap or losing the large
build-time win?

Attempt 027 tested closing one concrete semantic gap in the rejected fast path:
enum constructor value metadata. The candidate recovered primitive, string,
boxed, and enum-reference constructor arguments from enum `<clinit>` bytecode.

**Candidate changes:**

- wrap enum `<clinit>` method visits with a lightweight symbolic stack visitor
  that runs alongside the existing graph visitor
- recover constructor arguments from `new enum`, `dup`, enum `<init>`, and
  `putstatic` bytecode patterns
- support primitive/string constants, boxed `valueOf(...)` calls, and enum
  constant references via `EnumValueReference`
- add fast-build coverage for `ComplexEnum`, `BoxedArgEnum`, and
  `EnumWithEnumRef`

**Main comparison:**

| Benchmark | Baseline `main` (`74e937a`) | Attempt 025 fast path | Attempt 027 fast path | Change vs baseline |
|-----------|-----------------------------|------------------------|------------------------|--------------------|
| `GraphEndToEndBenchmark.android_fast_build_save_load_query` | `111921.044 ms/op` | `10171.204 ms/op` | `12385.541 ms/op` | `9.04x` faster |

**Conclusion:** rejected. The candidate improved one semantic gap but continued
to depend on a separate `--fast-build` delivery shape, and the added semantic
work reduced the fast result below the `10x` target. No product code from this
attempt is retained.

### 2026-08-02 — Attempt 028: Remove fast-build delivery path

**Question:** after rejecting semantic trade-offs, what is the current
end-to-end position when the product code keeps only the default
semantic-complete SootUp/Jimple path?

The explicit ASM fast-build mode from Attempt 025 was removed from the working
tree, including the CLI flag, loader config field, fast adapter, fast JMH
variants, README documentation, and fast-path tests. The previous fast-path
candidate remains recorded as an upper-bound experiment only.

**Changes retained:**

- keep the default-path graph build, mmap, and `GraphStore.save()` optimizations
  from Attempts 023 and 024
- remove user-visible `--fast-build` behavior from the product diff
- keep rejected Attempt 025-027 notes in this document so the performance
  evidence and trade-off decision remain auditable

**Environment:**

- machine: macOS arm64, Darwin 26.5.2
- JVM: OpenJDK 17.0.20 Homebrew
- fixture: Android SDK jar from Gradle cache
- build-only JMH heap: unchanged `-Xmx8g`
- end-to-end JMH heap: unchanged `-Xmx4g`
- JMH mode: SingleShotTime, one fork

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew check --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Main comparison:**

| Benchmark | Baseline `main` (`74e937a`) | Attempt 028 default path | Change vs baseline |
|-----------|-----------------------------|---------------------------|--------------------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `95195.133 ms/op` | `26484.479 ms/op` | `3.59x` faster |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `111921.044 ms/op` | `33782.165 ms/op` | `3.31x` faster |

**Conclusion:** accepted as a course correction, not as performance success.
The product diff no longer exposes a semantic-reducing fast-build mode, and the
full check passes. The `10x` target is still not met: default semantic-complete
end-to-end performance is `3.31x` faster than baseline, with most remaining time
still in the SootUp/Jimple build phase.

### 2026-08-02 — Attempt 029: Reject automatic whole-jar bytecode backend

**Question:** can the rejected fast builder be made invisible to users by
selecting it automatically for the existing end-to-end benchmark config, instead
of exposing a separate `--fast-build` flag?

Attempt 029 wired a whole-jar ASM/bytecode graph builder into
`JavaProjectLoader` only when `buildCallGraph=false`,
`extractAnnotations=false`, `trackCrossMethodFunctionalDispatch=false`, and the
input was a non-Spring-Boot JAR. This removed the user-visible flag but still
replaced the SootUp/Jimple backend for that configuration.

**Candidate variants:**

- initial bytecode backend without branch scopes
- branch-aware bytecode backend that emitted `ControlFlowEdge` and
  `BranchScope` metadata using the same reachable-minus-other-branch model as
  the SootUp path
- small branch metadata save optimization that avoided materializing
  `BranchScope` sets before writing metadata
- negative test: avoid `BitSet.clone()` during branch extraction

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:compileKotlin :webgraph:compileKotlin --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test :webgraph:test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Results:**

| Candidate | `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `GraphEndToEndBenchmark.android_build_save_load_query` | Decision |
|-----------|----------------------------------------------------------|--------------------------------------------------------|----------|
| Whole-jar bytecode backend, no branch scopes | `5712.378 ms/op` | `10335.337 ms/op` | rejected semantic gap |
| Whole-jar bytecode backend with branch scopes | `6586.652 ms/op` | `12042.363 ms/op` | rejected; below `10x` and still not semantically complete |
| Branch backend after successor/node recording optimizations | N/A | `11547.691 ms/op` | rejected; still above the `11192.104 ms/op` `10x` target |
| Branch metadata save snapshot | N/A | `11726.914 ms/op` | rejected; not a stable win |
| Avoid `BitSet.clone()` in branch extraction | N/A | `14851.686 ms/op` | rejected; expanded traversal work |

**Semantic gaps found:**

- bytecode stack simulation remained linear and was not equivalent for all
  control-flow joins and loop back-edges
- enum constructor argument metadata was still incomplete compared with the
  SootUp/Jimple path
- resource relationship extraction still lacked SootUp's resource-call
  reasoning
- method resolution and bytecode-only dataflow were approximations rather than
  proven replacements for the SootUp body model

**Conclusion:** rejected. Hiding the fast path behind automatic selection does
not remove the trade-off; it only makes the trade-off implicit. The no-branch
variant crossed `10x`, but by dropping semantics. The branch-aware variant got
closer semantically but missed the `10x` target and still had known correctness
gaps. No whole-jar bytecode backend is retained in product code.

### 2026-08-02 — Attempt 030: Reject conservative method-level bytecode shortcut

**Question:** can a much narrower bytecode shortcut keep default semantics by
using bytecode only for methods with no jumps, no switches, no try/catch blocks,
no invokedynamic, no monitor operations, and no resource-relevant calls, while
falling back to SootUp for everything else?

Attempt 030 removed the whole-jar backend and tested a method-level shortcut
inside `SootUpAdapter`. The candidate only handled simple linear method bodies
and preserved source line numbers on bytecode-created call sites. Any method
with control flow or resource-sensitive calls used the existing SootUp path.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:compileKotlin :webgraph:compileKotlin --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Results:**

| Benchmark | Attempt 028 default path | Conservative shortcut candidate | Change |
|-----------|--------------------------|----------------------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `26484.479 ms/op` | `26463.322 ms/op` | `-21.157 ms` / `-0.08%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `33782.165 ms/op` | `34772.328 ms/op` | `+990.163 ms` / `+2.93%` slower |

**Conclusion:** rejected. Once the shortcut was constrained tightly enough to
avoid the known semantic trade-offs, it no longer moved the build-time or
end-to-end result. The implementation complexity was removed; the product diff
continues to retain only the explicit removal of the `--fast-build` delivery
path and the previous accepted default-path optimizations.

### 2026-08-02 — Attempt 031: Keep default-path micro cleanups

**Question:** after removing the semantic-reducing fast-build direction, can the
standard SootUp/Jimple path still gain measurable time from allocation-oriented
cleanups that do not change graph semantics or max heap settings?

Attempt 031 kept the product path on the default adapter and tested three small
changes in `SootUpAdapter`:

- make verbose-only hot log messages lazy so string interpolation is skipped
  when `LoaderConfig.verbose` is unset
- reuse `stmtGraph.stmts` while processing method bodies instead of iterating
  the statement graph object directly
- skip the functional-dispatch local lookup only when cross-method functional
  dispatch is disabled and the current method has no same-method dynamic
  targets to resolve

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter=GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter=GraphEndToEndBenchmark.android_build_save_load_query -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew check --no-daemon
```

**Results:**

| Benchmark | Attempt 028 default path | Attempt 031 default path | Change |
|-----------|--------------------------|---------------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `26484.479 ms/op` | `24814.024 ms/op` | `-1670.455 ms` / `-6.31%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `33782.165 ms/op` | `33664.201 ms/op` | `-117.964 ms` / `-0.35%` |

**Conclusion:** accepted as a small default-path cleanup, not as performance
success. The changes keep the standard SootUp/Jimple graph semantics and
unchanged heap settings, but the end-to-end result remains only `3.32x` faster
than the `111921.044 ms/op` baseline and still misses the `11192.104 ms/op`
`10x` target by a wide margin.

### 2026-08-02 — Attempt 032: Reuse statement list for control-flow indexing

**Question:** can the accepted `stmtGraph.stmts` list reuse from Attempt 031
also reduce control-flow extraction cost without changing branch semantics?

Attempt 032 kept the existing control-flow algorithm and branch-scope semantics,
but changed `ControlFlowIndex` to seed its statement ids from the already
materialized `stmtGraph.stmts` list. Successor lookup still uses the original
`StmtGraph`, so control-flow edges and branch scopes are computed from the same
CFG as before.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter=GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter=GraphEndToEndBenchmark.android_build_save_load_query -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew check --no-daemon
```

**Results:**

| Benchmark | Attempt 031 default path | Attempt 032 default path | Change |
|-----------|--------------------------|---------------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `24814.024 ms/op` | `24255.432 ms/op` | `-558.592 ms` / `-2.25%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `33664.201 ms/op` | `32113.098 ms/op` | `-1551.103 ms` / `-4.61%` |

**Conclusion:** accepted as another default-path cleanup, not as performance
success. The optimization keeps SootUp/Jimple semantics and the same heap
settings, improving the end-to-end result to `3.49x` faster than the
`111921.044 ms/op` baseline. The `10x` target is still not met.

### 2026-08-02 — Attempt 033: Reject extra allocation micro-optimizations

**Question:** after Attempt 032, do additional small allocation cleanups in the
same hot path compound into a measurable build-time win?

Three candidate variants were tested after the accepted control-flow list reuse:

- pre-scan each method's statement list and record statement-node mappings only
  for methods that can produce branch scopes
- pre-size `ControlFlowIndex`, skip method annotation usage creation when
  `extractAnnotations=false`, reuse a method sub-signature string during
  declaring-class resolution, and use an empty-parameter fast path in
  `toMethodDescriptor`
- isolate the method sub-signature string reuse from the larger allocation
  cleanup pack

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter=GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

| Candidate | Attempt 032 build-only | Candidate build-only | Change |
|-----------|------------------------|----------------------|--------|
| Statement-node recording pre-scan | `24255.432 ms/op` | `25418.012 ms/op` | `+1162.580 ms` / `+4.79%` slower |
| Allocation cleanup pack | `24255.432 ms/op` | `24596.783 ms/op` | `+341.351 ms` / `+1.41%` slower |
| Isolated sub-signature string reuse | `24255.432 ms/op` | `25095.547 ms/op` | `+840.115 ms` / `+3.46%` slower |

**Conclusion:** rejected. Both variants preserved the intended default-path
semantics in tests, but they regressed the build-only benchmark, so no
end-to-end benchmark was run for them and no product code from this attempt is
retained.

### 2026-08-02 — Attempt 034: Keep invoke argument fast path

**Question:** can ordinary invoke processing avoid avoidable list allocation for
no-argument call sites while keeping call-site and dataflow semantics unchanged?

Attempt 034 replaced the direct `invokeExpr.args.mapIndexed` calls in
`processInvokeExpr` and `processDynamicInvoke` with a small helper that returns
`emptyList()` for no-argument invokes and otherwise builds the same `NodeId`
list with exact capacity. The helper preserves the existing fallback behavior
for unsupported argument values by allocating an unknown `NodeId`.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter=GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter=GraphEndToEndBenchmark.android_build_save_load_query -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

| Benchmark | Attempt 032 default path | Attempt 034 default path | Change |
|-----------|--------------------------|---------------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `24255.432 ms/op` | `23994.740 ms/op` | `-260.692 ms` / `-1.07%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `32113.098 ms/op` | `31717.323 ms/op` | `-395.775 ms` / `-1.23%` |

**Conclusion:** accepted as a small default-path cleanup, not as performance
success. The end-to-end result is now `3.53x` faster than the `111921.044 ms/op`
baseline, still well short of the `11192.104 ms/op` `10x` target.

### 2026-08-02 — Attempt 035: Reject field-only signature preload

**Question:** can `JavaProjectLoader` reduce bytecode signature preload time by
parsing only the field generic signatures that `SootUpAdapter` currently
consumes during graph construction?

Attempt 035 added a field-only mode to `BytecodeSignatureReader` and used it
only from `JavaProjectLoader.loadSignatures(...)`. The public reader default
continued to parse class, field, method return, and method parameter signatures,
so the reader's standalone behavior and tests remained intact.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter=GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

| Benchmark | Attempt 034 default path | Field-only signature preload | Change |
|-----------|--------------------------|------------------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `23994.740 ms/op` | `24038.146 ms/op` | `+43.406 ms` / `+0.18%` slower |

**Conclusion:** rejected. The candidate preserved tests but did not produce a
measurable build-time win, and it added API surface. No product code from this
attempt is retained.

### 2026-08-02 — Attempt 036: Reject current-method node state cleanup

**Question:** can the default SootUp/Jimple path remove small per-method and
per-call overhead by keeping parameter and return nodes in current-method state
instead of adapter-level maps, and by replacing a few hot invoke argument
`forEach` calls with explicit loops?

Attempt 036 tested two semantic-equivalent micro cleanups in `SootUpAdapter`:

- replace the adapter-level `parameterNodes` and `methodReturnNodes` maps with
  current-method state for active parameter and return lookup
- replace several argument-edge `forEach`/`forEachIndexed` loops with explicit
  `for` loops while emitting the same call-site, dynamic-call, and dataflow
  edges in the same order

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter=GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter=GraphEndToEndBenchmark.android_build_save_load_query -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

| Benchmark | Attempt 034 default path | Attempt 036 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `23994.740 ms/op` | `23974.573 ms/op` | `-20.167 ms` / `-0.08%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `31717.323 ms/op` | `32334.374 ms/op` | `+617.051 ms` / `+1.95%` slower |

**Conclusion:** rejected. The build-only result was noise-level positive, but
the end-to-end benchmark regressed under the same `-Xmx4g` heap cap. The
candidate was reverted, and no product code from this attempt is retained.

### 2026-08-02 — Attempt 037: Reject invokedynamic early split

**Question:** can `processInvokeExpr` avoid wasted work by routing
`JDynamicInvokeExpr` before resolving the method-defining class and creating the
ordinary invoke argument list that `processDynamicInvoke()` rebuilds anyway?

Attempt 037 tested a semantic-equivalent early split in `SootUpAdapter`:

- handle `JDynamicInvokeExpr` before `resolveMethodDefiningClass(...)`
- delay ordinary callee descriptor creation until after boxing/unboxing
  early returns
- reorder boxing/unboxing boolean checks so `resultNode == null` can skip the
  wrapper-method classification

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter=GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

| Benchmark | Attempt 034 default path | Attempt 037 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `23994.740 ms/op` | `24512.464 ms/op` | `+517.724 ms` / `+2.16%` slower |

**Conclusion:** rejected. The candidate preserved `:sootup:test`, but the
build-only benchmark regressed enough that no end-to-end benchmark was run. No
product code from this attempt is retained.

### 2026-08-02 — Attempt 038: Reject constructor resolution fast path

**Question:** can method-resolution overhead be reduced by returning constructor
signatures directly from `resolveMethodDefiningClass(...)`, since JVM
constructors are not inherited and the declaring class in a `<init>` signature
is already the defining class?

Attempt 038 added a narrow early return for `sig.name == "<init>"` before the
declared-method cache and type-hierarchy walk. This preserves constructor
callee semantics while avoiding unnecessary declaring-class resolution for
constructor invokes.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter=GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

| Benchmark | Attempt 034 default path | Attempt 038 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `23994.740 ms/op` | `24354.974 ms/op` | `+360.234 ms` / `+1.50%` slower |

**Conclusion:** rejected. Although the constructor rule is semantically safe
and `:sootup:test` passed, the measured build-only path regressed. No
end-to-end benchmark was run, and no product code from this attempt is retained.

### 2026-08-02 — Attempt 039: Reject slice-based WebGraph successor iterator

**Question:** can the end-to-end save phase improve by avoiding
`copyOfRange(...)` allocation in `PrecomputedImmutableGraph.successors()` while
BVGraph stores the already sorted forward adjacency?

Attempt 039 replaced `LazyIntIterators.wrap(successorArray(x))` with a custom
`LazyIntIterator` over the backing `targets[offsets[x]..offsets[x + 1])` slice.
`successorArray(x)` kept its existing copy behavior for callers that require a
standalone array, so graph semantics and WebGraph-visible successor order were
unchanged.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter=GraphEndToEndBenchmark.android_build_save_load_query -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

| Benchmark | Attempt 034 default path | Attempt 039 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphEndToEndBenchmark.android_build_save_load_query` | `31717.323 ms/op` | `32651.374 ms/op` | `+934.051 ms` / `+2.95%` slower |

**Conclusion:** rejected. `:webgraph:test` passed, but the end-to-end benchmark
regressed under the same `-Xmx4g` heap cap. The custom iterator was reverted,
and no product code from this attempt is retained.

### 2026-08-02 — Attempt 040: Reject gated method annotation materialization

**Question:** when `extractAnnotations=false`, can the default SootUp/Jimple
path avoid materializing ASM method annotations while preserving the configured
graph semantics?

Attempt 040 changed `createStreamingMethod(...)` so method annotation usages
were created only when `extractAnnotations` was enabled. The end-to-end Android
benchmark config disables annotation extraction, and `processMethod(...)` does
not use method annotations in that configuration, so the candidate was intended
as a semantics-preserving cleanup for the measured default path.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
```

**Results:**

| Benchmark | Attempt 034 default path | Attempt 040 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `23994.740 ms/op` | `24709.900 ms/op` | `+715.160 ms` / `+2.98%` slower |

**Conclusion:** rejected. `:sootup:test` passed, but the build-only benchmark
regressed, so no end-to-end benchmark was run. The candidate was reverted, and
no product code from this attempt is retained.

### 2026-08-02 — Attempt 041: Keep lazy field generic signature lookup

**Question:** can the default SootUp/Jimple path keep field generic type
semantics without paying an upfront full-archive generic signature scan?

Attempt 041 removed the `JavaProjectLoader.load()` call to
`loadSignatures(...)` and moved field generic signature recovery into
`SootUpAdapter.getFieldTypeWithGenerics(...)`. The adapter now lazily builds a
per-class field-signature cache from the ASM `ClassNode` already attached to
SootUp bytecode class sources, with a resource-stream fallback when needed.
The public `BytecodeSignatureReader` remains intact for direct API callers and
tests; the loader simply stops preloading class and method signatures that the
graph builder does not currently consume.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.GenericSignatureTest" --tests "io.johnsonlee.graphite.sootup.JavaProjectLoaderTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Results:**

| Benchmark | Attempt 034 default path | Attempt 041 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `23994.740 ms/op` | `23194.731 ms/op` | `-800.009 ms` / `-3.33%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `31717.323 ms/op` | `30998.551 ms/op` | `-718.772 ms` / `-2.27%` |

**Conclusion:** accepted as a semantic-preserving default-path cleanup. The
loader no longer spends time parsing generic class and method signatures that
are not used by the graph builder, while field generic types are still recovered
on demand from the same bytecode. This improves Android end-to-end performance
under the same `-Xmx4g` heap cap, but it still does not meet the `10x` target:
`30998.551 ms/op` is `3.61x` faster than the `111921.044 ms/op` baseline.

### 2026-08-02 — Attempt 042: Reject cached resource profile regex

**Question:** can resource indexing avoid repeated regex compilation in
`resourceProfile(...)` without changing resource node semantics?

Attempt 042 moved the `application-<profile>.<ext>` regex from a per-call
`Regex(...)` construction to a companion-level cached `Regex`. This preserves
the same profile matching behavior for `properties`, `json`, `xml`, `yml`, and
`yaml` resource filenames.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Results:**

| Benchmark | Attempt 041 default path | Attempt 042 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `23194.731 ms/op` | `22751.982 ms/op` | `-442.749 ms` / `-1.91%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30998.551 ms/op` | `31185.186 ms/op` | `+186.635 ms` / `+0.60%` slower |

**Conclusion:** rejected. Although `:sootup:test` passed and the build-only
single-shot score improved, the end-to-end benchmark regressed under the same
`-Xmx4g` heap cap. The regex cache was reverted, and no product code from this
attempt is retained.

### 2026-08-02 — Attempt 043: Reject eager mmap build indexes

**Question:** can `MmapGraphBuilder.build()` get faster by maintaining node
offsets, node type indexes, and edge degree counts while nodes and edges are
written, avoiding the node header scan and the first edge count scan at build
time?

Attempt 043 changed the default mmap builder path to update these indexes in
`addNode(...)` and `addEdge(...)`. The graph payload format and lookup
semantics were unchanged; the candidate only moved index bookkeeping from
`build()` into the write path.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :core:test --tests "io.johnsonlee.graphite.graph.MmapGraphBuilderTest" --tests "io.johnsonlee.graphite.graph.MmapGraphTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Results:**

| Benchmark | Attempt 041 default path | Attempt 043 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `23194.731 ms/op` | `23318.710 ms/op` | `+123.979 ms` / `+0.53%` slower |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30998.551 ms/op` | `37724.091 ms/op` | `+6725.540 ms` / `+21.70%` slower |

**Conclusion:** rejected. The targeted mmap graph tests passed, but shifting
index maintenance into the write path regressed both build-only and end-to-end
performance, with a large end-to-end slowdown under the same `-Xmx4g` heap cap.
The candidate was reverted, and no product code from this attempt is retained.

### 2026-08-02 — Attempt 044: Keep single-artifact origin scan skip

**Question:** for ordinary single-JAR inputs, can the default SootUp/Jimple path
avoid building class-origin bookkeeping that is later discarded because there is
only one artifact?

Attempt 044 teaches `JavaProjectLoader` to pass a single-artifact source hint
only for plain archive inputs, not directories, WARs, or Spring Boot jars. When
that hint is present, `SootUpAdapter.buildGraph()` skips
`indexSootClassOrigins(...)` and dependency extraction setup, while still
indexing resources from the archive and still skipping class resource entries
from the loaded source. Multi-source layouts keep the existing class-origin and
artifact-dependency path. A regression assertion also locks in the existing
single-JAR semantics: redundant class origins and artifact dependencies remain
empty.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.JavaProjectLoaderTest" --tests "io.johnsonlee.graphite.sootup.ResourceConfigLinkingTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Results:**

| Benchmark | Attempt 041 default path | Attempt 044 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `23194.731 ms/op` | `22970.595 ms/op` | `-224.136 ms` / `-0.97%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30998.551 ms/op` | `30450.433 ms/op` | `-548.118 ms` / `-1.77%` |

**Conclusion:** accepted as a narrow semantic-preserving default-path cleanup.
The Android benchmark fixture is a plain single JAR, so the adapter can avoid a
class-origin pass whose result would be intentionally dropped for the same
single-artifact semantics. This improves end-to-end performance under the same
`-Xmx4g` heap cap, but it still does not meet the `10x` target:
`30450.433 ms/op` is `3.68x` faster than the `111921.044 ms/op` baseline.

### 2026-08-02 — Attempt 045: Reject ListResourceBundle superclass cache

**Question:** can `indexClassBundles(...)` get cheaper by caching superclass
walk results while identifying `java.util.ListResourceBundle` subclasses?

Attempt 045 added a class-name cache to `isListResourceBundleClass(...)`.
The candidate preserved bundle-linking behavior in targeted tests and avoided
re-walking shared superclass chains, but it also added map lookups on the
all-classes pass.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.ResourceConfigLinkingTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Results:**

| Benchmark | Attempt 044 default path | Attempt 045 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22970.595 ms/op` | `22928.347 ms/op` | `-42.248 ms` / `-0.18%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30450.433 ms/op` | `30811.242 ms/op` | `+360.809 ms` / `+1.18%` slower |

**Conclusion:** rejected. The build-only score moved by only noise-level
amounts, and the real end-to-end benchmark regressed under the same `-Xmx4g`
heap cap. The cache was reverted, and no product code from this attempt is
retained.

### 2026-08-02 — Attempt 046: Reject ASM enum `<clinit>` extractor

**Question:** can enum constructor value extraction stay on the default
semantic path while avoiding SootUp/Jimple body materialization for simple enum
`<clinit>` methods?

Attempt 046 added a conservative ASM visitor for enum `<clinit>` bytecode. The
candidate only accepted the ASM result after it observed constructor values for
all enum constants; unknown stack values or unsupported control flow before
that point forced a fallback to the existing SootUp/Jimple extractor. This kept
enum-value semantics covered by the existing fallback path, including boxed
values, enum references, static initializer blocks, and mixed primitive
constructor arguments.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest.should extract complex enum values with multiple constructor args" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest.should extract boxed argument enum values" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest.should extract enum values with Short Byte Float Double Boolean Character boxing" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest.should extract enum values with enum reference arguments" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest.should extract enum values from enum with static initializer block" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest.should extract enum values from DirectFieldRefEnum" --tests "io.johnsonlee.graphite.sootup.EnumValueReferenceTest" --tests "io.johnsonlee.graphite.sootup.StaticFieldIndirectReferenceTest.should extract values from boxed Integer enum constructor parameters" --tests "io.johnsonlee.graphite.sootup.StaticFieldIndirectReferenceTest.should extract float, double, boolean, and long enum constructor values" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Results:**

| Benchmark | Attempt 044 default path | Attempt 046 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22970.595 ms/op` | `22895.476 ms/op` | `-75.119 ms` / `-0.33%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30450.433 ms/op` | `30693.084 ms/op` | `+242.651 ms` / `+0.80%` slower |

**Conclusion:** rejected. The targeted enum semantics tests passed and the
build-only score improved slightly, but the actual end-to-end benchmark
regressed under the same `-Xmx4g` heap cap. The ASM enum extractor was reverted,
and no product code from this attempt is retained.

### 2026-08-02 — Attempt 047: Reject full-resource glob fast path

**Question:** can `ArchiveResourceAccessor.list("**")` skip glob-to-regex
conversion and per-entry regex matching during full resource scans?

Attempt 047 special-cased the full-resource glob in
`ArchiveResourceAccessor.list(...)`, returning all source entries directly while
leaving every other glob pattern on the existing `globToRegex(...)` path. This
preserved the meaning of `"**"` and did not alter specific glob filters such as
`*.txt`, `resources/*.json`, or `**/*.json`.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.ArchiveResourceAccessorTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
```

**Results:**

| Benchmark | Attempt 044 default path | Attempt 047 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22970.595 ms/op` | `23155.341 ms/op` | `+184.746 ms` / `+0.80%` slower |

**Conclusion:** rejected. `ArchiveResourceAccessorTest` passed, but the
build-only Android benchmark regressed, so no end-to-end benchmark was run. The
special case was reverted, and no product code from this attempt is retained.

### 2026-08-02 — Attempt 048: Reject in-memory builder for Android build

**Question:** is the pass 2 bottleneck mostly `MmapGraphBuilder` write cost,
and can Android-scale builds stay under the same heap cap while using the
in-memory `DefaultGraph.Builder`?

Attempt 048 temporarily changed the Android end-to-end-config benchmark to
construct `JavaProjectLoader(..., useMmapBuilder = false)`. This preserved graph
semantics but replaced the disk-spilling builder with the in-memory graph
builder, testing whether node/edge write cost inside pass 2 was the dominant
remaining overhead.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
```

**Results:**

| Benchmark | Attempt 044 default path | Attempt 048 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22970.595 ms/op` | `25102.729 ms/op` | `+2132.134 ms` / `+9.28%` slower |

**Conclusion:** rejected. The in-memory builder made build-only performance
substantially worse, so no end-to-end benchmark was run. The temporary benchmark
change was reverted, and no product code from this attempt is retained.

### 2026-08-02 — Attempt 049: Reject abstract/native body-check skip

**Question:** can pass 2 avoid unnecessary SootUp body checks for methods that
are known not to have bodies, such as abstract and native methods?

Attempt 049 changed `processMethod(...)` to call `method.hasBody()` only when
`!method.isAbstract && !method.isNative`. This preserves body-processing
semantics because abstract and native methods have no bytecode body to walk.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Results:**

| Benchmark | Attempt 044 default path | Attempt 049 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22970.595 ms/op` | `22700.379 ms/op` | `-270.216 ms` / `-1.18%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30450.433 ms/op` | `30665.039 ms/op` | `+214.606 ms` / `+0.70%` slower |

**Conclusion:** rejected. The build-only benchmark improved modestly, but the
end-to-end benchmark regressed under the same `-Xmx4g` heap cap. The body-check
skip was reverted, and no product code from this attempt is retained.

### 2026-08-02 — Attempt 050: Reject statement-node index array

**Question:** can branch-scope statement-node recording avoid hot-path
`IdentityHashMap<Stmt, IntArrayBuilder>` writes by using the already available
`stmtGraph.stmts` list order?

Attempt 050 replaced the per-method identity map for statement-created node ids
with a statement-indexed nullable array. `ControlFlowIndex` registers
`stmtGraph.stmts` in the same order before adding successor-only statements, so
branch-scope lookup can read node ids by statement index for all processed
statements while preserving the existing control-flow semantics.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
```

**Results:**

| Benchmark | Attempt 044 default path | Attempt 050 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22970.595 ms/op` | `23041.391 ms/op` | `+70.796 ms` / `+0.31%` slower |

**Conclusion:** rejected. The targeted SootUp adapter tests passed, but the
build-only benchmark regressed slightly, so no end-to-end benchmark was run. The
array-based statement-node index was reverted, and no product code from this
attempt is retained.

### 2026-08-02 — Attempt 051: Keep lazy control-flow successor indexing

**Question:** can branch-scope extraction avoid eager SootUp successor lookups
for every statement in each branched method, while preserving the existing
control-flow graph semantics?

Attempt 051 changes `ControlFlowIndex` to keep the same statement identity ids
but compute and cache successor id arrays lazily. The eager implementation
called `stmtGraph.successors(...)` for every statement as soon as a method had
at least one `JIfStmt`. The lazy implementation still reads successors from the
same SootUp `StmtGraph`, but only when branch reachability needs a given
statement. This keeps branch target ordering and successor-only statement
registration unchanged.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Results:**

| Benchmark | Attempt 044 default path | Attempt 051 candidate | Change |
|-----------|--------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22970.595 ms/op` | `22911.585 ms/op` | `-59.010 ms` / `-0.26%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30450.433 ms/op` | `30182.415 ms/op` | `-268.018 ms` / `-0.88%` |

**Conclusion:** accepted as a narrow semantic-preserving default-path cleanup.
The optimization only changes when successor arrays are computed, not which
successors are used. It improves both build-only and end-to-end Android scores
under the same heap caps, but it still does not meet the `10x` target:
`30182.415 ms/op` is `3.71x` faster than the `111921.044 ms/op` baseline.

### 2026-08-02 — Attempt 052: Reject selective callee resolution

**Question:** can `processInvokeExpr(...)` avoid hierarchy-based callee
resolution for calls where the result is unused or already fixed, such as
`invokedynamic`, static invokes, constructors, boxing, and unboxing?

Attempt 052 reordered invocation processing so boxing, unboxing, and dynamic
invoke handling ran before `resolveMethodDefiningClass(...)`, and added a helper
that only resolved ordinary instance calls. This preserved the intended
method-resolution surface in targeted tests: inherited instance calls still
resolved through the hierarchy, while fixed-target paths avoided unnecessary
callee lookup.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.MethodResolutionTest" --tests "io.johnsonlee.graphite.sootup.LambdaAnalysisTest" --tests "io.johnsonlee.graphite.sootup.ResourceConfigLinkingTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Results:**

| Benchmark | Attempt 051 retained path | Attempt 052 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `22849.276 ms/op` | `-62.309 ms` / `-0.27%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `30521.278 ms/op` | `+338.863 ms` / `+1.12%` slower |

**Conclusion:** rejected. The targeted tests passed and build-only performance
improved slightly, but the real end-to-end pipeline regressed under the same
`-Xmx4g` heap cap. The invocation-order change was reverted, and no product
code from this attempt is retained.

### 2026-08-02 — Attempt 053: Reject lazy `stmtGraph.stmts` materialization

**Question:** can methods without branches avoid allocating the
`stmtGraph.stmts` list by iterating the SootUp `StmtGraph` directly and only
materializing the statement list when branch-scope extraction is needed?

Attempt 053 changed `processMethodBody(...)` to process statements with
`for (stmt in stmtGraph)`, then call `stmtGraph.stmts` only for methods that
actually contained a `JIfStmt` and stayed under the existing control-flow size
limit. The intended semantics were unchanged: non-branch methods do not emit
branch scopes, and branched methods still used SootUp's statement order for
control-flow indexing.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
```

**Results:**

| Benchmark | Attempt 051 retained path | Attempt 053 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23990.075 ms/op` | `+1078.490 ms` / `+4.71%` slower |

**Conclusion:** rejected. The targeted control-flow tests passed, but build-only
performance regressed substantially, so no end-to-end benchmark was run. The
direct-iterator change was reverted, and no product code from this attempt is
retained.

### 2026-08-02 — Attempt 054: Reject one-pass class list and index build

**Question:** can `buildGraph()` avoid an extra pass over all resolved classes
by collecting the class list and `classesByNameCache` in one stream traversal?

Attempt 054 replaced `view.classes.toList()` followed by `associateBy(...)`
with a single `view.classes.forEach` that populated both an `ArrayList` and a
`HashMap`. The class order and last-wins indexing semantics were intended to
match the original list-plus-associate implementation.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.JavaProjectLoaderTest" --tests "io.johnsonlee.graphite.sootup.ResourceConfigLinkingTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
```

**Results:**

| Benchmark | Attempt 051 retained path | Attempt 054 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23219.478 ms/op` | `+307.893 ms` / `+1.34%` slower |

**Conclusion:** rejected. The targeted tests passed, but build-only performance
regressed, so no end-to-end benchmark was run. The one-pass class collection
change was reverted, and no product code from this attempt is retained.

### 2026-08-02 — Attempt 055: Reject gated statement-node recording

**Question:** can `recordStmtNode(...)` avoid per-statement `IdentityHashMap`
writes for methods whose statement-node map will never be read because they do
not emit branch scopes?

Attempt 055 pre-scanned `stmtGraph.stmts` for `JIfStmt` and enabled
statement-node recording only when branch-scope extraction would actually run
under the existing `MAX_CONTROL_FLOW_STATEMENTS` limit. Methods with no
branches, or with too many statements for control-flow extraction, would still
emit their normal dataflow/call/resource graph but skip the otherwise-unused
statement-to-node map.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
```

**Results:**

| Benchmark | Attempt 051 retained path | Attempt 055 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23084.143 ms/op` | `+172.558 ms` / `+0.75%` slower |

**Conclusion:** rejected. The targeted branch-scope tests passed, but the extra
pre-scan cost outweighed the avoided map writes in the Android benchmark. No
end-to-end benchmark was run. The gated recording change was reverted, and no
product code from this attempt is retained.

### 2026-08-02 — Attempt 056: Reject SootUp and ASM version upgrade

**Question:** can the default semantic path get a larger gain by moving to the
latest upstream bytecode stack instead of adding a user-visible fast mode?

Attempt 056 temporarily changed SootUp from `2.0.0` to `3.0.0` and ASM from
`9.7` to `9.10.1`, based on Maven metadata showing those as the latest
released versions. This would have kept a single default loader path if it had
compiled cleanly and improved the benchmarks.

**Validation command:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:compileKotlin --no-daemon
```

**Results:**

Compilation failed before benchmark validation. SootUp 3 changes core API
surface used throughout `SootUpAdapter`, including `StmtGraph`, `Local`,
`Value`, `Body.stmtGraph`, and `AsmUtil.asmIdToSignature`.

**Conclusion:** rejected. This is not a narrow default-path optimization; it is
a larger SootUp migration with its own compatibility and semantic risk. The
version changes were reverted, and no product code from this attempt is
retained.

### 2026-08-02 — Attempt 057: Reject manual SootUp 3 API migration

**Question:** if the SootUp 3 upgrade is adapted manually instead of only
bumping versions, can the default semantic path get a meaningful upstream
performance gain without adding a fast mode or dropping bytecode semantics?

Attempt 057 temporarily migrated the adapter to SootUp 3's renamed APIs:
`StmtGraph` to `ControlFlowGraph`, `Local`/`Value` to
`sootup.core.jimple.common`, `Body.stmtGraph` to `Body.controlFlowGraph`, and
`AsmUtil.asmIdToSignature` to `AsmUtil.asmIdToSignatures`. It also adapted the
ASM method-node fallback for SootUp 3's `OverridingJavaClassSource`, where
method metadata is already resolved but `BodySource` still points at
`AsmMethodSource`.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.JavaProjectLoaderTest" --tests "io.johnsonlee.graphite.sootup.ResourceConfigLinkingTest" --tests "io.johnsonlee.graphite.sootup.GenericSignatureTest" --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
```

**Results:**

| Benchmark | Attempt 051 retained path | Attempt 057 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `26672.278 ms/op` | `+3760.693 ms` / `+16.41%` slower |

The targeted SootUp tests passed after the compatibility fixes, but the
Android build-only benchmark regressed sharply. No end-to-end benchmark was run
because the candidate already failed the build-stage gate.

**Conclusion:** rejected. A full SootUp 3 migration is semantically possible,
but it is slower for the default Android graph build under the same heap cap.
The version and API migration changes were reverted, and no product code from
this attempt is retained.

### 2026-08-02 — Attempt 058: Reject method sub-signature string cache

**Question:** can inherited-method resolution avoid repeated
`MethodSubSignature.toString()` work without changing which methods are
resolved through the hierarchy?

Attempt 058 added a small `MethodSubSignature -> String` cache used by
`declaresMethod(...)`. The lookup surface was unchanged: the adapter still
checks the declaring class, then superclasses, then implemented interfaces, and
still compares against the same declared sub-signature strings.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.MethodResolutionTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' --no-daemon
```

**Results:**

| Benchmark | Attempt 051 retained path | Attempt 058 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `22877.440 ms/op` | `-34.145 ms` / `-0.15%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `37492.698 ms/op` | `+7310.283 ms` / `+24.22%` slower |

**Conclusion:** rejected. The targeted tests passed and build-only performance
showed a tiny improvement, but the real end-to-end path regressed sharply under
the same `-Xmx4g` heap cap. The cache was reverted, and no product code from
this attempt is retained.

### 2026-08-02 — Attempt 059: Reject plain-JAR directory expansion

**Question:** can the default loader avoid ZipFS overhead by expanding a plain
single JAR's `.class` entries to a temporary directory and pointing SootUp at
that directory, while keeping resource reads on the original archive?

Attempt 059 changed only loader input materialization. For non-directory,
non-WAR, non-Spring-Boot archives, it copied class entries to a temp directory,
used `PathBasedAnalysisInputLocation` on that directory, kept
`ArchiveResourceAccessor` on the original path, and cleaned temporary
directories after `buildGraph()`. This preserves the same class/resource
surface but trades ZipFS reads for an up-front extraction pass.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.JavaProjectLoaderTest" --tests "io.johnsonlee.graphite.sootup.ResourceConfigLinkingTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' --no-daemon
```

**Results:**

The targeted loader/resource tests passed. The first JMH run exposed duplicate
class entries in the Android archive (`FileAlreadyExistsException` while
copying `android/media/Audioattributes.class`), so the candidate was adjusted
to overwrite duplicate extracted paths and rerun.

| Benchmark | Attempt 051 retained path | Attempt 059 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `33601.272 ms/op` | `+10689.687 ms` / `+46.66%` slower |

**Conclusion:** rejected. Directory scanning did not offset the cost of
expanding the Android archive; build-only performance regressed too much to
justify an end-to-end run. The loader materialization changes were reverted,
and no product code from this attempt is retained.

### 2026-08-02 — Attempt 060: Reject SootUp body-validation bypass

**Question:** can the default semantic path avoid repeated SootUp statement
graph validation during method body construction without copying or forking
SootUp internals?

Attempt 060 inspected the SootUp 2 bytecode implementation for
`AsmMethodSource.resolveBody(...)` and `Body.BodyBuilder.build()`. The goal was
to find a supported external hook or configuration switch that would preserve
the same Jimple body construction but skip redundant validation work.

**Validation commands:**

```
javap -classpath /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.java.bytecode.frontend/2.0.0/c72cad03b1ca9ad0fa8f8a835b10075da751bfa3/sootup.java.bytecode.frontend-2.0.0.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.core/2.0.0/3ccfc6d55ca06ee3170f45b2ac5a204edabcb10a/sootup.core-2.0.0.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.java.core/2.0.0/d7e10779a0b3758a5adbf2eb6ea51b9b1845bdd7/sootup.java.core-2.0.0.jar -c -private sootup.core.model.Body\$BodyBuilder
javap -classpath /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.java.bytecode.frontend/2.0.0/c72cad03b1ca9ad0fa8f8a835b10075da751bfa3/sootup.java.bytecode.frontend-2.0.0.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.core/2.0.0/3ccfc6d55ca06ee3170f45b2ac5a204edabcb10a/sootup.core-2.0.0.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.java.core/2.0.0/d7e10779a0b3758a5adbf2eb6ea51b9b1845bdd7/sootup.java.core-2.0.0.jar -c -private sootup.java.bytecode.frontend.conversion.AsmMethodSource
```

**Results:**

`AsmMethodSource.resolveBody(...)` constructs a private
`MutableBlockStmtGraph`, arranges statements, runs SootUp body interceptors,
then calls `BodyBuilder.getStmtGraph().validateStmtConnectionsInGraph()` after
each interceptor. `BodyBuilder.build()` then calls
`MutableStmtGraph.validateStmtConnectionsInGraph()` again before constructing
the final `Body`. No public `LoaderConfig`, `JavaView`, `BodySource`, or
interceptor hook disables those validations while keeping the same body
construction flow.

**Conclusion:** rejected as a product change. Skipping this work would require
copying or bytecode-patching SootUp internals, which adds a large maintenance
and semantic-compatibility risk for a default path PR. No benchmark was run and
no product code was changed.

### 2026-08-02 — Attempt 061: Reject local hierarchy index for method resolution

**Question:** can inherited-method resolution avoid repeated SootUp
`typeHierarchy` superclass/interface lookups by using a lightweight hierarchy
index built during the existing type-hierarchy pass, without changing callee
resolution semantics?

Attempt 061 temporarily recorded each class's direct superclass and interfaces
while pass 1 was already visiting classes. `resolveMethodDefiningClass(...)`
then searched locally cached transitive superclasses and interfaces before
falling back to SootUp for classes not present in the local index. The
`declaresMethod(...)` check and the resulting `CallSiteNode.callee` descriptors
were unchanged.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.MethodResolutionTest" --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted method-resolution, bytecode, control-flow, and internal coverage
tests passed.

| Benchmark | Attempt 051 retained path | Attempt 061 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23458.243 ms/op` | `+546.658 ms` / `+2.39%` slower |

**Conclusion:** rejected. The candidate preserved the tested method-resolution
semantics, but the local hierarchy bookkeeping and traversal were slower than
SootUp's cached hierarchy lookup on the Android end-to-end config. No
end-to-end benchmark was run, the candidate was reverted, and no product code
from this attempt is retained.

### 2026-08-02 — Attempt 062: Reject mmap scans for `MmapGraphBuilder.build()`

**Question:** can `MmapGraphBuilder.build()` reduce its index-construction cost
by memory-mapping `nodes.dat` and `edges.dat` during the final scan, jumping
between compact record headers instead of using `DataInputStream.skipBytes(...)`
for node payloads and stream reads for edge payloads?

Attempt 062 changed only the temporary mmap builder's index scan. The node and
edge record formats, emitted graph nodes, edge labels, and persistent
`GraphStore` format were unchanged. The candidate kept payloads off JVM heap by
using read-only mapped buffers and still built the same outgoing/incoming offset
indexes.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :core:test --tests "io.johnsonlee.graphite.graph.MmapGraphBuilderTest" --tests "io.johnsonlee.graphite.graph.MmapGraphTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :core:test --tests "io.johnsonlee.graphite.graph.MmapGraphBuilderTest" --tests "io.johnsonlee.graphite.graph.MmapGraphTest" :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted mmap builder/graph tests passed.

| Benchmark | Attempt 051 retained path | Attempt 062 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `22747.903 ms/op` | `-163.682 ms` / `-0.71%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `37015.609 ms/op` | `+6833.194 ms` / `+22.64%` slower |

**Conclusion:** rejected. The mapped scan shaved a small amount from the
build-only benchmark, but the real end-to-end path regressed sharply under the
same `-Xmx4g` heap cap, likely because the extra mapped buffers and file-cache
behavior interfered with the subsequent save/load pipeline. The candidate was
reverted, and no product code from this attempt is retained.

### 2026-08-02 — Attempt 063: Reject enum `<clinit>` method reuse

**Question:** can enum value extraction avoid constructing the same enum
`<clinit>` body twice by reusing the `SootMethod` materialized during pass 1
when pass 2 processes the same enum class as ordinary graph nodes?

Attempt 063 temporarily cached each enum class's `<clinit>` `SootMethod` after
pass 1 materialized its body for enum constructor argument extraction. During
pass 2, `streamMethodsOrNull(...)` yielded that cached method for the same
static `<clinit>` method node instead of creating a fresh wrapper. The cache was
removed immediately after processing that class to avoid retaining enum bodies
beyond the class's pass-2 window.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.EnumValueReferenceTest" --tests "io.johnsonlee.graphite.sootup.StaticFieldIndirectReferenceTest" --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted enum extraction, static-field enum reference, and control-flow
tests passed.

| Benchmark | Attempt 051 retained path | Attempt 063 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `22887.545 ms/op` | `-24.040 ms` / `-0.10%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `31030.851 ms/op` | `+848.436 ms` / `+2.81%` slower |

**Conclusion:** rejected. Reusing enum `<clinit>` preserved the targeted enum
semantics but only produced noise-level build-only movement and regressed the
real end-to-end benchmark. The candidate was reverted, and no product code from
this attempt is retained.

### 2026-08-02 — Attempt 064: Reject adaptive forward compression threads

**Question:** can `GraphStore.save()` reduce Android end-to-end wall time by
letting forward `BVGraph.store(...)` use more compression workers on machines
with available cores, while preserving the exact same graph nodes, edges,
labels, and persisted format?

An E2E JFR profile of the retained default path showed a meaningful save-stage
share after build:

| Segment | Execution samples |
|---------|-------------------|
| build / source graph load | `436` |
| `GraphStore.save` | `327` |
| mapped load | `17` |
| query | `1` |

Attempt 064 temporarily changed `GraphStore.save(...)` from a fixed default of
`compressionThreads = 2` to an adaptive default capped at four workers:
`Runtime.getRuntime().availableProcessors().coerceIn(1, 4)`. Explicit
`compressionThreads` arguments were still honored, and a unit test covered the
clamp behavior. The graph construction path and serialized graph content were
unchanged; only the number of BVGraph compression workers changed.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:test --tests "io.johnsonlee.graphite.webgraph.GraphStoreTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home java -Xmx4g -jar frontend/jvm/webgraph/build/libs/webgraph-1.0.0-SNAPSHOT-jmh.jar 'GraphEndToEndBenchmark.android_build_save_load_query$' -wi 0 -i 1 -f 1 -bm ss -tu ms -prof gc -jvmArgsAppend '-Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar'
```

**Results:**

The targeted `GraphStoreTest` suite passed.

| Benchmark | Attempt 051 retained path | Attempt 064 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `30726.566 ms/op` | `+544.151 ms` / `+1.80%` slower |
| `GraphEndToEndBenchmark.android_build_save_load_query` with GC profiler | N/A | `30565.605 ms/op`, `36151213504 B/op`, `116` GCs, `1534 ms` GC time | attribution run |

**Conclusion:** rejected. The candidate preserved graph semantics and stayed
under the same `-Xmx4g` cap, but it did not beat the retained end-to-end
baseline. The extra compression workers traded a little more CPU parallelism
for enough scheduling and GC noise that the real single-shot E2E score moved
backward. The candidate was reverted, and no product code from this attempt is
retained.

### 2026-08-02 — Attempt 065: Reject raw mmap node string scan for save

**Question:** can `GraphStore.save(MmapGraph)` avoid constructing full `Node`
objects during its first save pass, where it only needs node strings, node
counts, type counts, and call-site caller/callee classes for the class overview?

Attempt 065 temporarily added a `MmapGraph` raw node-record scanner over the
temporary `nodes.dat` mmap. The scanner read the existing mmap-builder node
format directly, collected the same strings as `NodeSerializer.collectNodeStrings(...)`,
and reported call-site caller/callee `MethodDescriptor` pairs to the class
overview builder. The later persisted `graph.nodedata` write still used the
normal `NodeSerializer.writeNode(...)` path, so the persisted graph format and
loaded graph semantics were unchanged. The candidate only tried to reduce
allocation in the pre-string-table save pass.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :core:test --tests "io.johnsonlee.graphite.graph.MmapGraphBuilderTest" --tests "io.johnsonlee.graphite.graph.MmapGraphTest" :webgraph:test --tests "io.johnsonlee.graphite.webgraph.GraphStoreTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted mmap builder/graph and `GraphStoreTest` suites passed.

| Benchmark | Attempt 051 retained path | Attempt 065 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `30693.829 ms/op` | `+511.414 ms` / `+1.69%` slower |

**Conclusion:** rejected. The candidate removed one source of temporary node
object construction during save, but the extra raw-format branch and duplicate
string-scanning logic did not improve the real end-to-end path. Since it added
cross-module API surface and format-coupled code while moving the benchmark
backward, the candidate was reverted and no product code from this attempt is
retained.

### 2026-08-02 — Attempt 066: Reject direct method-node loop

**Question:** can the SootUp adapter avoid Kotlin coroutine-sequence overhead
in method streaming by replacing `streamMethodsOrNull(...).forEach(...)` with a
plain loop over ASM `MethodNode`s, while preserving the same method order,
`createStreamingMethod(...)` construction, and per-method exception handling?

Attempt 066 temporarily changed the hot `forEachMethod(...)` and
`firstMethod(...)` paths to loop directly over `getAsmMethodNodes(...)`.
`streamMethodsOrNull(...)` was kept as a private compatibility wrapper for
internal coverage tests, but the production method traversal no longer used the
`sequence { yield(...) }` coroutine path. No method filtering, body
materialization, or graph emission behavior changed.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.EnumValueReferenceTest" --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted adapter, enum, and control-flow tests passed.

| Benchmark | Attempt 051 retained path | Attempt 066 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `22523.978 ms/op` | `-387.607 ms` / `-1.69%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `31038.295 ms/op` | `+855.880 ms` / `+2.84%` slower |

**Conclusion:** rejected. The direct loop did remove enough wrapper overhead to
help the build-only benchmark, but the production end-to-end score regressed.
Because this PR is being gated on the full build-save-load-query path, the
candidate was reverted and no product code from this attempt is retained.

### 2026-08-02 — Attempt 067: Reject invokedynamic argument reuse

**Question:** can `SootUpAdapter.processInvokeExpr(...)` avoid duplicate
argument-node lookup for `invokedynamic` call sites by computing
`argumentNodeIds(...)` once and passing the result into
`processDynamicInvoke(...)`, while preserving the same bootstrap target
resolution, call-site nodes, and argument edges?

Attempt 067 temporarily threaded the already-computed `argNodeIds` from
`processInvokeExpr(...)` into the dynamic-invoke path. A compatibility overload
kept the existing private reflection coverage intact, and no semantic behavior
was intended to change: the same dynamic targets were extracted from bootstrap
method handles, and the same call/dataflow edges were emitted.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.LambdaAnalysisTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted lambda and adapter tests passed.

| Benchmark | Attempt 051 retained path | Attempt 067 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `22897.163 ms/op` | `-14.422 ms` / `-0.06%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `34020.932 ms/op` | `+3838.517 ms` / `+12.72%` slower |

**Conclusion:** rejected. The build-only score was effectively neutral, and the
full Android build-save-load-query path regressed sharply. Since the goal is
end-to-end performance on the default semantic-complete path, the candidate was
reverted and no product code from this attempt is retained.

### 2026-08-02 — Attempt 068: Reject branch-only statement-node recording

**Question:** can `SootUpAdapter` avoid per-statement `stmtNodeIds` recording
for methods whose temporary statement-node index is never consumed, while
preserving branch-scope extraction for methods that do emit control-flow
metadata?

Attempt 068 temporarily pre-scanned each method's materialized `stmtGraph.stmts`
list for `JIfStmt`s. It enabled `recordStmtNode(...)` only when the method had
branches and stayed under the existing `MAX_CONTROL_FLOW_STATEMENTS` cap. The
graph nodes and edges created by statement processing were unchanged; only the
temporary per-method branch-scope lookup map was skipped for methods that would
not call `processControlFlow(...)`.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted control-flow and adapter tests passed.

| Benchmark | Attempt 051 retained path | Attempt 068 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23409.619 ms/op` | `+498.034 ms` / `+2.17%` slower |

**Conclusion:** rejected. The candidate avoided temporary statement-node
recording for non-control-flow methods, but the extra pre-scan and conditional
bookkeeping moved the Android build-only score backward. Since build-only
already regressed, no end-to-end benchmark was run. The candidate was reverted
and no product code from this attempt is retained.

### 2026-08-02 — Attempt 069: Reject active parameter-node map

**Question:** can parameter identity processing avoid `ParameterBinding` and
global `parameterNodes` map overhead by keeping the current method's
`ParameterNode`s in an `index -> node` map, while still creating
`ParameterBinding` only when cross-method functional dispatch is enabled?

Attempt 069 temporarily replaced the `ParameterBinding -> ParameterNode` map
used by `processParameters(...)` and `processIdentity(...)` with a per-method
`activeParameterNodesByIndex` map. Under the Android end-to-end config,
`trackCrossMethodFunctionalDispatch = false`, so this removed all
`ParameterBinding` creation from the hot parameter-node lookup path. When
cross-method dispatch was enabled, `processIdentity(...)` still recorded
`localToParamIndex` bindings for later functional-interface resolution.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --tests "io.johnsonlee.graphite.sootup.LambdaAnalysisTest" --tests "io.johnsonlee.graphite.sootup.MethodResolutionTest" --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted parameter, lambda, method-resolution, control-flow, and adapter
tests passed.

| Benchmark | Attempt 051 retained path | Attempt 069 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23553.799 ms/op` | `+642.214 ms` / `+2.80%` slower |

**Conclusion:** rejected. The candidate reduced one class of small temporary
objects, but the changed hot-path data structure shape regressed Android
build-only performance. Since build-only already lost, no end-to-end benchmark
was run. The candidate was reverted and no product code from this attempt is
retained.

### 2026-08-02 — Attempt 070: Reject cached method descriptor signature

**Question:** can repeated `MethodDescriptor.signature` string construction be
avoided with a lightweight nullable cache on `MethodDescriptor`, while leaving
the constructor, data-class equality, copy behavior, and signature text
unchanged?

Attempt 070 temporarily changed the computed `signature` getter from rebuilding
`"${declaringClass.className}.$name(...)"` on every access to caching the first
computed string in a private nullable field. This targeted repeated signature
lookups in `MmapGraphBuilder.build()`, graph metadata collection, and persisted
method indexing without changing graph semantics or public constructor shape.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :core:test --tests "io.johnsonlee.graphite.graph.DefaultGraphTest" --tests "io.johnsonlee.graphite.graph.MmapGraphBuilderTest" --tests "io.johnsonlee.graphite.graph.MmapGraphTest" --tests "io.johnsonlee.graphite.graph.MethodPatternTest" --tests "io.johnsonlee.graphite.query.QueryDslTest" :webgraph:test --tests "io.johnsonlee.graphite.webgraph.GraphStoreTest" :sootup:test --tests "io.johnsonlee.graphite.sootup.MethodResolutionTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted core graph, query, WebGraph, and method-resolution tests passed.

| Benchmark | Attempt 051 retained path | Attempt 070 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `22839.198 ms/op` | `-72.387 ms` / `-0.32%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `30876.462 ms/op` | `+694.047 ms` / `+2.30%` slower |

**Conclusion:** rejected. Caching the signature produced a small build-only
improvement, but the full Android build-save-load-query path regressed under
the same `-Xmx4g` heap cap, likely because the retained cached strings changed
object lifetime and heap pressure during save/load. The candidate was reverted
and no product code from this attempt is retained.

### 2026-08-02 — Attempt 071: Reject lightweight field-signature keys

**Question:** can field-node and dynamic-field tracking avoid SootUp
`FieldSignature.toString()` cost by using an adapter-local key made from
declaring class, field name, and field type, without changing the emitted
`FieldNode` descriptors or dataflow semantics?

Attempt 071 temporarily replaced the internal `fieldNodes`,
`fieldDynamicTargets`, and `fieldLoadLocals` map keys that were based on
`fieldSignature.toString()` with a lightweight
`declaringClass#fieldName:type` helper. The helper was used consistently for
field declarations and field references, so the graph surface was intended to
stay identical while avoiding SootUp's expensive signature string formatting in
the hot field access path.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.StaticFieldIndirectReferenceTest" --tests "io.johnsonlee.graphite.sootup.LambdaAnalysisTest" --tests "io.johnsonlee.graphite.sootup.ResourceConfigLinkingTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The first test run exposed an internal coverage test that was coupled to the
old private map key. After updating that assertion to call the new private key
helper, the targeted static-field, lambda, resource, and adapter tests passed.

| Benchmark | Attempt 051 retained path | Attempt 071 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23055.164 ms/op` | `+143.579 ms` / `+0.63%` slower |

**Conclusion:** rejected. The candidate avoided one expensive SootUp string
formatter path, but the custom key construction still moved Android build-only
performance backward. Since build-only regressed, no end-to-end benchmark was
run. The candidate was reverted and no product code from this attempt is
retained.

### 2026-08-02 — Attempt 072: Reject skipping method annotation conversion when disabled

**Question:** can `createStreamingMethod(...)` avoid converting ASM method
annotations into SootUp annotation usages when `LoaderConfig.extractAnnotations`
is `false`, while preserving the existing annotation behavior when the flag is
enabled?

Attempt 072 temporarily changed `createStreamingMethod(...)` so
`extractAnnotationsEnabled = false` passed `emptyList()` to `JavaSootMethod`
instead of building annotation usages from `visibleAnnotations` and
`invisibleAnnotations`. When annotation extraction was enabled, the previous
conversion path was unchanged. This targeted the Android end-to-end config,
which disables annotation extraction.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.EndpointExtractionTest" --tests "io.johnsonlee.graphite.sootup.MemberAnnotationTest" --tests "io.johnsonlee.graphite.sootup.JacksonAnnotationTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted annotation and adapter tests passed.

| Benchmark | Attempt 051 retained path | Attempt 072 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23463.265 ms/op` | `+551.680 ms` / `+2.41%` slower |

**Conclusion:** rejected. Although the candidate respected the annotation
configuration flag, the Android build-only path regressed. Since build-only
already lost, no end-to-end benchmark was run. The candidate was reverted and
no product code from this attempt is retained.

### 2026-08-02 — Attempt 073: Reject empty exception-signature fast path

**Question:** can `createStreamingMethod(...)` avoid calling
`AsmUtil.asmIdToSignature(...)` for methods that declare no checked exceptions,
while preserving the same exception metadata for methods that do declare
exceptions?

Attempt 073 temporarily changed `JavaSootMethod` construction to pass
`emptyList()` directly when `methodNode.exceptions` was null or empty, and to
call `AsmUtil.asmIdToSignature(...)` only for non-empty exception lists. The
candidate targeted JFR samples attributed to `AsmUtil.asmIdToSignature(...)`
inside `createStreamingMethod(...)`; method bodies, annotations, signatures,
nodes, and edges were otherwise unchanged.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted adapter, control-flow, and advanced bytecode tests passed.

| Benchmark | Attempt 051 retained path | Attempt 073 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `24304.283 ms/op` | `+1392.698 ms` / `+6.08%` slower |

**Conclusion:** rejected. The candidate was semantically narrow, but the
Android build-only path regressed significantly, likely due to the changed
collection shape passed into SootUp method construction. Since build-only
already lost, no end-to-end benchmark was run. The candidate was reverted and
no product code from this attempt is retained.

### 2026-08-02 — Attempt 074: Reject descriptor-key declared-method lookup

**Question:** can inherited-method resolution avoid converting every declared
ASM method node back into a SootUp `MethodSignature` just to compare
sub-signatures, while preserving the same resolved callee class?

Attempt 074 temporarily changed the private `declaresMethod(...)` membership
test from SootUp sub-signature strings to JVM descriptor keys. The lookup key
for a declared ASM method was `methodNode.name + methodNode.desc`; the lookup
key for the invoked `MethodSubSignature` was built from the same method name,
parameter types, and return type. The resulting `MethodSignature` and
`MethodDescriptor` surfaces were otherwise unchanged. This targeted JFR
allocation samples attributed to `AsmMethodSource.getSignature()`,
`AsmUtil.toJimpleSignatureDesc(...)`, and
`SootClassMemberSignature.toString()` under
`collectDeclaredMethodSubSignatures(...)`.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.MethodResolutionTest" --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted method-resolution, bytecode, adapter, and internal coverage tests
passed.

| Benchmark | Attempt 051 retained path | Attempt 074 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `22756.948 ms/op` | `-154.637 ms` / `-0.67%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `31097.174 ms/op` | `+914.759 ms` / `+3.03%` slower |

**Conclusion:** rejected. Descriptor keys avoided one SootUp signature
conversion path and produced a small build-only improvement, but the full
Android build-save-load-query path regressed under the same `-Xmx4g` heap cap.
Because the goal is default semantic-complete end-to-end performance, the
candidate was reverted and no product code from this attempt is retained.

### 2026-08-02 — Attempt 075: Reject cached archive layout classification

**Question:** can `JavaProjectLoader.load(...)` avoid repeatedly checking a
plain JAR for Spring Boot/WAR layout by classifying the input once and reusing
that classification for both input-location creation and single-artifact source
selection?

Attempt 075 temporarily introduced a small private `InputLayout` enum inside
`JavaProjectLoader`. The loader computed the layout once per `load(...)` call
and passed it into `createInputLocations(...)` and `singleArtifactSource(...)`.
The directory, Spring Boot JAR, WAR, and plain archive branches kept the same
behavior; the candidate only tried to remove duplicate ZipFile layout probing
for large plain archives.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.JavaProjectLoaderTest" --tests "io.johnsonlee.graphite.sootup.ResourceConfigLinkingTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted loader, resource, and adapter tests passed.

| Benchmark | Attempt 051 retained path | Attempt 075 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23787.544 ms/op` | `+875.959 ms` / `+3.82%` slower |

**Conclusion:** rejected. The candidate preserved loader behavior, but Android
build-only performance regressed, likely because the extra enum/classification
shape outweighed the removed duplicate archive-layout probe in this single-shot
pipeline. Since build-only already lost, no end-to-end benchmark was run. The
candidate was reverted and no product code from this attempt is retained.

### 2026-08-02 — Attempt 076: Reject block-reserved NodeIds

**Question:** can graph construction reduce per-node `AtomicInteger` overhead
by reserving `NodeId`s in large contiguous blocks inside `SootUpAdapter`, while
keeping globally unique node IDs?

Attempt 076 temporarily added `NodeId.reserve(count)` and changed
`SootUpAdapter.nextNodeId(...)` to reserve IDs in blocks of 8192. This reduced
global atomic counter updates from once per node to once per block. The
candidate preserved uniqueness, but could leave small unused gaps at the tail
of each graph build if a block was not fully consumed.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :core:test --tests "io.johnsonlee.graphite.core.NodeTest" :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted core, adapter, advanced bytecode, and control-flow tests passed.

| Benchmark | Attempt 051 retained path | Attempt 076 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23582.029 ms/op` | `+670.444 ms` / `+2.93%` slower |

**Conclusion:** rejected. The atomic counter was not a meaningful bottleneck
on the Android build path. Any saved atomic operations were outweighed by the
changed node-id/index shape, because mmap indexes size themselves from the
highest observed node id. Since build-only regressed, no end-to-end benchmark
was run. The candidate was reverted and no product code from this attempt is
retained.

### 2026-08-02 — Attempt 077: Reject stripping line-number nodes before body resolution

**Question:** can default SootUp/Jimple graph construction avoid unused source
line position allocation by removing ASM `LineNumberNode`s before streaming
method body resolution, while preserving Graphite's current node and edge
semantics?

Attempt 077 temporarily changed `createStreamingMethod(...)` to remove
`LineNumberNode` entries from each ASM `MethodNode.instructions` list before
constructing the streaming `JavaSootMethod`. Graphite already creates
`JavaSootMethod` instances with `NoPositionInformation`, and generated
`CallSiteNode`s keep `lineNumber = null`, so the candidate targeted SootUp
statement-position allocation without changing Graphite's exposed graph
schema, call resolution, control-flow labels, method signatures, annotations,
or bytecode instruction conversion.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.MethodResolutionTest" --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted method-resolution, advanced bytecode, adapter, and internal
coverage tests passed.

| Benchmark | Attempt 051 retained path | Attempt 077 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `22761.499 ms/op` | `-150.086 ms` / `-0.66%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `31350.339 ms/op` | `+1167.924 ms` / `+3.87%` slower |

**Conclusion:** rejected. Removing line-number nodes produced a small
build-only improvement, but the full Android build-save-load-query pipeline
regressed under the same heap cap. Since the goal is default
semantic-complete end-to-end performance, the candidate was reverted and no
product code from this attempt is retained.

### 2026-08-02 — Attempt 078: Reject branch-heavy reachability closure

**Question:** can branch-scope extraction reduce repeated successor traversal
for branch-heavy methods by computing a method-local reachability closure, while
preserving the existing `reachable(branch) - reachable(otherBranch)` semantics?

Attempt 078 temporarily added a private `ReachabilityClosure` scratch structure
used only when a method had at least eight `JIfStmt` branch statements. The
candidate still used SootUp's `StmtGraph` successors and the same
`ControlFlowIndex` statement identities. For each branch it emitted the same
control-flow edges and `BranchScope` node-id sets as the existing lazy BFS
formula, but precomputed transitive reachability with compact `LongArray`
bitsets to avoid repeated breadth-first walks in branch-heavy methods.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted control-flow, adapter, internal coverage, and advanced bytecode
tests passed.

| Benchmark | Attempt 051 retained path | Attempt 078 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23464.133 ms/op` | `+552.548 ms` / `+2.41%` slower |

**Conclusion:** rejected. The closure preserved the branch-scope formula in
tests, but Android build-only performance regressed. The extra closure
allocation and propagation work outweighed the avoided repeated BFS traversals,
so no end-to-end benchmark was run. The candidate was reverted and no product
code from this attempt is retained.

### 2026-08-02 — Attempt 079: Reject custom SootUp class provider for `SKIP_DEBUG`

**Question:** can the default SootUp/Jimple path avoid parsing debug line
metadata up front by supplying a custom ASM class provider that uses
`ClassReader.SKIP_DEBUG`, while keeping Graphite's current graph semantics?

Attempt 079 inspected SootUp 2's bytecode frontend API boundary. `AsmUtil`
currently calls `ClassReader.accept(visitor, ClassReader.SKIP_FRAMES)`; it does
not expose a flag for `SKIP_DEBUG`. The public `PathBasedAnalysisInputLocation`
factory accepts body interceptors, but the default list is already empty, and
it does not expose the ASM reader flags or class provider. `AsmJavaClassProvider`
constructs a package-private `SootClassNode`, whose `visitMethod(...)` creates
package-private `AsmMethodSource` instances required by Graphite's streaming
method path. `AsmClassSource` itself is also package-private.

**Validation commands:**

```
javap -classpath /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.java.bytecode.frontend/2.0.0/c72cad03b1ca9ad0fa8f8a835b10075da751bfa3/sootup.java.bytecode.frontend-2.0.0.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.core/2.0.0/3ccfc6d55ca06ee3170f45b2ac5a204edabcb10a/sootup.core-2.0.0.jar -c -p sootup.java.bytecode.frontend.conversion.AsmJavaClassProvider
javap -classpath /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.java.bytecode.frontend/2.0.0/c72cad03b1ca9ad0fa8f8a835b10075da751bfa3/sootup.java.bytecode.frontend-2.0.0.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.core/2.0.0/3ccfc6d55ca06ee3170f45b2ac5a204edabcb10a/sootup.core-2.0.0.jar -p sootup.java.bytecode.frontend.conversion.AsmJavaClassProvider\$SootClassNode
javap -classpath /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.java.bytecode.frontend/2.0.0/c72cad03b1ca9ad0fa8f8a835b10075da751bfa3/sootup.java.bytecode.frontend-2.0.0.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.core/2.0.0/3ccfc6d55ca06ee3170f45b2ac5a204edabcb10a/sootup.core-2.0.0.jar -p sootup.java.bytecode.frontend.conversion.AsmClassSource
```

**Results:**

No product code was changed. The inspection confirmed that implementing this
inside Graphite would require a split-package class in
`sootup.java.bytecode.frontend.conversion` or reflection against package-private
SootUp internals, effectively copying part of SootUp's class-provider logic just
to change the `ClassReader` flags.

**Conclusion:** rejected as a product change. Dropping source line debug
metadata is likely compatible with Graphite's current graph surface, but the
only available implementation path is too coupled to SootUp internals for a
default, PR-ready semantic-complete loader. No benchmark was run and no product
code from this attempt is retained.

### 2026-08-02 — Attempt 080: Reject graphless no-arg void body skip

**Question:** can the default SootUp/Jimple path skip body resolution for
concrete no-argument `void` methods whose bytecode body contains no graph-visible
work beyond `return`, while preserving the existing method and return-node
surface?

Before editing product code, the Android jar was scanned with ASM. Out of
`379430` concrete methods, `48077` were no-argument `void` methods and only
`2483` were graphless return-only bodies under the conservative predicate
tested here. The candidate then temporarily changed `processMethod(...)` to
skip `processMethodBody(...)` only when the streaming `MethodNode` had:

- descriptor `()V`
- no try/catch blocks
- only `RETURN`, `NOP`, label, frame, or line nodes

For such methods the current adapter would add the method descriptor and
`ReturnNode` before body processing, then produce no additional Graphite nodes
or edges from the body itself.

**Validation commands:**

```
/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/jshell --class-path /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7/73d7b3086e14beb604ced229c302feff6449723/asm-9.7.jar
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted adapter, internal coverage, advanced bytecode, and control-flow
tests passed.

| Benchmark | Attempt 051 retained path | Attempt 080 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23554.013 ms/op` | `+642.428 ms` / `+2.80%` slower |

**Conclusion:** rejected. The skip predicate was semantically conservative, but
the Android corpus had too few matching bodies for the saved SootUp body
resolutions to offset the extra per-method bytecode inspection. Since build-only
regressed, no end-to-end benchmark was run. The candidate was reverted and no
product code from this attempt is retained.

### 2026-08-02 — Attempt 081: Reject empty statement-node branch BFS skip

**Question:** can branch-scope extraction avoid reachability BFS work for
branched methods whose main statement pass created no branch-scope candidate
nodes, while preserving condition operand side effects?

Attempt 081 temporarily added a narrow early-continue inside
`processControlFlow(...)`: after resolving the branch condition operands,
comparison operator, and SootUp true/false successors, it skipped
`branchNodeIds(...)` when the per-method `stmtNodeIds` map was empty. This
preserved the existing side effects of creating condition/comparand local or
constant nodes, but avoided a BFS whose `nodeIdsFor(...)` result would be empty
for both branches because there were no statement-created node ids to collect.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted control-flow, adapter, internal coverage, and advanced bytecode
tests passed.

| Benchmark | Attempt 051 retained path | Attempt 081 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `22799.896 ms/op` | `-111.689 ms` / `-0.49%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `30455.897 ms/op` | `+273.482 ms` / `+0.91%` slower |

**Conclusion:** rejected. The early-continue preserved the branch-scope result
shape and produced a small build-only improvement, but the full Android
build-save-load-query pipeline regressed under the same heap cap. Because the
goal is default semantic-complete end-to-end performance, the candidate was
reverted and no product code from this attempt is retained.

### 2026-08-02 — Attempt 082: Reject tag-indexed mmap node type index collection

**Question:** can `MmapGraphBuilder.build()` reduce temporary node type index
construction overhead by collecting node ids in a tag-indexed array instead of a
`HashMap<Class<out Node>, IntArrayBuilder>`, while preserving the same
class-keyed node type index exposed by `MmapGraph`?

Attempt 082 temporarily changed the node-record scan in
`MmapGraphBuilder.build()`. Since node type tags are compact fixed integers, the
candidate collected `IntArrayBuilder`s in an `Array<IntArrayBuilder?>` indexed by
the serialized tag, then created the same final `Map<Class<out Node>, IntArray>`
after the scan. The persisted node format, node offsets, node order, type index
contents, edge indexes, and public graph APIs were unchanged.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :core:test --tests "io.johnsonlee.graphite.graph.MmapGraphBuilderTest" --tests "io.johnsonlee.graphite.graph.MmapGraphTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted mmap builder and mmap graph tests passed.

| Benchmark | Attempt 051 retained path | Attempt 082 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23566.824 ms/op` | `+655.239 ms` / `+2.86%` slower |

**Conclusion:** rejected. The tag-indexed array preserved the final index shape,
but Android build-only performance regressed. The existing `HashMap` path is
not the relevant remaining bottleneck, and the changed allocation/control-flow
shape outweighed any saved class-key lookups. Since build-only already lost, no
end-to-end benchmark was run. The candidate was reverted and no product code
from this attempt is retained.

### 2026-08-02 — Attempt 083: Reject reusable WebGraph successor iterator buffer

**Question:** can `GraphStore.save()` reduce save-phase allocation by giving
`PrecomputedImmutableGraph` a custom `NodeIterator` that reuses its own
successor array while preserving the public random-access
`successorArray(node)` copy semantics?

JFR showed meaningful time in WebGraph compression and in array/IO-heavy save
paths. `BVGraph$CompressionThread` calls `NodeIterator.successorArray()` and
then immediately copies the returned successors into its own compression window.
Attempt 083 temporarily added an internal `PrecomputedNodeIterator` for
`PrecomputedImmutableGraph.nodeIterator(from)`. The iterator reused a growable
`IntArray` per iterator and copied the node's sorted successor slice into that
buffer. The graph's random-access `successorArray(node)` method still returned
a defensive copy, and the forward adjacency, labels, comparison map, node data,
and persisted graph format were unchanged.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:test --tests "io.johnsonlee.graphite.webgraph.GraphStoreTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted `GraphStoreTest` suite passed.

| Benchmark | Attempt 051 retained path | Attempt 083 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `32137.064 ms/op` | `+1954.649 ms` / `+6.47%` slower |

**Conclusion:** rejected. Although the iterator buffer preserved graph contents
and public random-access copy semantics, it made the full Android
build-save-load-query pipeline slower. The extra custom iterator dispatch and
per-node copy shape did not improve the save phase enough to offset its cost.
The candidate was reverted and no product code from this attempt is retained.

### 2026-08-02 — Attempt 084: Reject empty local-state map guards

**Question:** can `SootUpAdapter.processAssignment(...)` reduce per-assignment
hash lookups by checking whether local propagation maps are empty before probing
them, while preserving all existing local state propagation when any state is
present?

Attempt 084 temporarily added `isNotEmpty()` guards around the local-to-local
propagation maps used for string constants, locale specs, locale builders,
resource handles, properties paths, resource bundle paths, and bundle control
specs. It also guarded array dynamic-target propagation when the relevant
dynamic-target map was empty. These checks were intended to skip work only in
states where the original lookup could not find a value, so they did not remove
any existing graph nodes, edges, or resource/dynamic-dispatch propagation.

**Validation commands:**

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.ResourceConfigLinkingTest" --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted adapter, internal coverage, resource linking, and advanced
bytecode tests passed.

| Benchmark | Attempt 051 retained path | Attempt 084 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23713.260 ms/op` | `+801.675 ms` / `+3.50%` slower |

**Conclusion:** rejected. The extra branches were more expensive than the empty
map probes they avoided on the Android build workload, and build-only
performance regressed enough that no end-to-end benchmark was run. The
candidate was reverted and no product code from this attempt is retained.

### 2026-08-02 — Attempt 085: Reject disabling default SootUp body interceptors

**Question:** can the default SootUp/Jimple path reduce body materialization
cost by passing a smaller body-interceptor list to
`PathBasedAnalysisInputLocation.create(...)`, while preserving the Jimple graph
shape consumed by Graphite?

JFR allocation samples continued to show the largest remaining cost under
`SootMethod.getBody() -> AsmMethodSource.resolveBody(...)`. Attempt 085
inspected SootUp's public input-location API before editing product code to see
whether Graphite was implicitly paying for default body interceptors that could
be disabled for this loader.

**Validation commands:**

```
javap -classpath /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.java.bytecode.frontend/2.0.0/c72cad03b1ca9ad0fa8f8a835b10075da751bfa3/sootup.java.bytecode.frontend-2.0.0.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.core/2.0.0/3ccfc6d55ca06ee3170f45b2ac5a204edabcb10a/sootup.core-2.0.0.jar -p sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
javap -classpath /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.java.bytecode.frontend/2.0.0/c72cad03b1ca9ad0fa8f8a835b10075da751bfa3/sootup.java.bytecode.frontend-2.0.0.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.core/2.0.0/3ccfc6d55ca06ee3170f45b2ac5a204edabcb10a/sootup.core-2.0.0.jar -c -p sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
```

**Results:**

No product code was changed. The public `create(path, sourceType)` overload
already delegates to `create(path, sourceType, Collections.emptyList())`, and
the constructors store that list directly as the input location's body
interceptors. There is therefore no default interceptor list for Graphite to
remove on the current SootUp 2.0.0 path.

**Conclusion:** rejected as a product change. This confirms that the dominant
`AsmMethodSource.resolveBody(...)` cost is not coming from optional public
body-interceptor configuration in Graphite's current loader. Avoiding more of
that cost would require changing SootUp bytecode frontend internals or replacing
the body-resolution path with a semantic-complete bytecode graph builder, not a
small default-path configuration change. No benchmark was run and no product
code from this attempt is retained.

### 2026-08-02 — Attempt 086: Reject equality-keyed TypeDescriptor cache

**Question:** can `SootUpAdapter.toTypeDescriptor(...)` avoid repeated
`JavaClassType.getFullyQualifiedName()` string construction by using SootUp
type equality instead of object identity for the adapter-local
`TypeDescriptor` cache, while preserving the same emitted type names?

SootUp 2.0.0's `JavaClassType.getFullyQualifiedName()` allocates a new
`StringBuilder` and string for each call, while `JavaClassType.equals(...)` and
`hashCode()` compare cached class/package fields. Attempt 086 temporarily
changed `typeDescriptorCache` from `IdentityHashMap<Type, TypeDescriptor>` to a
regular `HashMap<Type, TypeDescriptor>`. The candidate kept the same
`TypeDescriptor` construction logic and graph surface, but allowed equivalent
SootUp type instances to share one descriptor.

**Validation commands:**

```
javap -classpath /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.java.core/2.0.0/d7e10779a0b3758a5adbf2eb6ea51b9b1845bdd7/sootup.java.core-2.0.0.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.core/2.0.0/3ccfc6d55ca06ee3170f45b2ac5a204edabcb10a/sootup.core-2.0.0.jar -c -p sootup.java.core.types.JavaClassType
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --tests "io.johnsonlee.graphite.sootup.MethodResolutionTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted adapter, internal coverage, advanced bytecode, and method
resolution tests passed.

| Benchmark | Attempt 051 retained path | Attempt 086 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23023.469 ms/op` | `+111.884 ms` / `+0.49%` slower |

**Conclusion:** rejected. Although the equality-keyed cache avoided some
potential duplicate `TypeDescriptor` construction, the regular `HashMap` and
SootUp type equality costs outweighed the saved fully-qualified-name work on
the Android build workload. Since build-only regressed, no end-to-end benchmark
was run. The candidate was reverted and no product code from this attempt is
retained.

### 2026-08-02 — Attempt 087: Reject single-artifact non-class resource scan

**Question:** can the default plain-JAR path avoid allocating and filtering
`ResourceEntry` objects for loaded `.class` entries during resource indexing,
while preserving non-class resource nodes and keeping multi-artifact class
origin/dependency scans unchanged?

On the Android corpus, the jar contains about `48226` class entries and `15535`
non-class entries. For a plain single artifact, Attempt 044 already skips
artifact dependency extraction, so loaded class entries do not contribute to
class-origin persistence or dependency weights. Attempt 087 temporarily added a
`ResourceAccessor.listNonClass(...)` API with an optimized
`ArchiveResourceAccessor` implementation that filtered class files before
creating `ResourceEntry` values. `SootUpAdapter.indexResourceValues(...)` used
that path only when `singleArtifactSource != null`; directory, Spring Boot,
WAR, and multi-source paths kept the existing full class-entry scan.

**Validation commands:**

```
jar tf /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar | awk 'BEGIN{c=0;r=0} /\/$/{next} {if ($0 ~ /\.class$/) c++; else r++} END{print "class", c; print "non_class", r}'
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.ArchiveResourceAccessorTest" --tests "io.johnsonlee.graphite.sootup.JavaProjectLoaderTest" --tests "io.johnsonlee.graphite.sootup.ResourceConfigLinkingTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The targeted resource accessor, loader, resource linking, and adapter tests
passed.

| Benchmark | Attempt 051 retained path | Attempt 087 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23433.907 ms/op` | `+522.322 ms` / `+2.28%` slower |

**Conclusion:** rejected. The candidate preserved single-artifact resource
semantics, but the extra public API, source-level filter branch, and changed
enumeration shape made Android build-only performance worse than the retained
path. Since build-only regressed, no end-to-end benchmark was run. The
candidate was reverted and no product code from this attempt is retained.

### 2026-08-02 — Attempt 088: Quantify conservative bytecode shortcut coverage

**Question:** after Attempt 030 rejected a conservative method-level bytecode
shortcut, was the failure mainly because too few Android methods were eligible,
or because the hybrid shortcut shape itself did not translate into end-to-end
speed?

Attempt 088 did not change product code. It scanned the Android benchmark jar
with ASM and classified concrete methods using the same kind of conservative
eligibility boundary as Attempt 030: no try/catch blocks, no jumps, no switch
instructions, no invokedynamic, no monitor operations, and no resource-relevant
calls. Unsupported methods would require SootUp fallback to preserve the
current graph semantics.

**Validation command:**

```
/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/jshell --class-path /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7/73d7b3086e14beb604ced229c302feff6449723/asm-9.7.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.ow2.asm/asm-tree/9.7/e446a17b175bfb733b87c5c2560ccb4e57d69f1a/asm-tree-9.7.jar
```

**Results:**

| Metric | Count |
|--------|-------|
| Classes | `48226` |
| Methods | `416802` |
| Concrete methods | `379430` |
| Abstract/native methods | `37372` |
| Conservative linear-safe concrete methods | `220983` / `58.24%` |
| Concrete methods with try/catch | `54207` |
| Concrete methods with jumps | `144960` |
| Concrete methods with switches | `9386` |
| Concrete methods with invokedynamic | `8430` |
| Concrete methods with monitor operations | `15260` |
| Concrete methods with resource-relevant calls | `268` |

Attempt 030 had already measured this conservative method-level shortcut shape:

| Benchmark | Attempt 028 default path | Conservative shortcut candidate | Change |
|-----------|--------------------------|----------------------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `26484.479 ms/op` | `26463.322 ms/op` | `-21.157 ms` / `-0.08%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `33782.165 ms/op` | `34772.328 ms/op` | `+990.163 ms` / `+2.93%` slower |

**Conclusion:** no product change. The conservative method-level shortcut was
not rejected simply because it covered too few methods; it covered more than
half of concrete Android methods and still failed to improve the end-to-end
pipeline. A future default bytecode/SootUp hybrid would need a substantially
more complete bytecode CFG and graph-equivalence story, plus lower integration
overhead, rather than just a narrow linear-method shortcut. No benchmark was
run in this attempt and no product code is retained.

### 2026-08-02 — Attempt 089: Reject frame-node stripping before body resolution

**Question:** can Graphite remove ASM verifier frame metadata before SootUp
body resolution to reduce method-body conversion work, while preserving the
statement graph and all Graphite-visible semantics?

Attempt 077 showed that stripping line-number nodes before body resolution did
not help end-to-end performance. Attempt 089 tried the narrower variant:
temporarily remove only ASM `FrameNode` entries from each `MethodNode` inside
`createStreamingMethod(...)` before invoking SootUp's private
`setDeclaringClass(...)`. Graphite does not consume verifier frame metadata
directly, and the candidate left line-number metadata intact.

**Validation commands:**

```
/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/jshell --class-path /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7/73d7b3086e14beb604ced229c302feff6449723/asm-9.7.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.ow2.asm/asm-tree/9.7/e446a17b175bfb733b87c5c2560ccb4e57d69f1a/asm-tree-9.7.jar
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --tests "io.johnsonlee.graphite.sootup.ControlFlowTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The ASM scan showed that frame metadata is common enough to be worth testing,
but much smaller than line-number metadata:

| Metric | Count |
|--------|-------|
| Classes | `48226` |
| Methods | `416802` |
| Concrete methods | `379430` |
| Methods containing `FrameNode` | `154360` |
| `FrameNode` entries | `626493` |
| Methods containing line-number nodes | `378434` |
| Line-number nodes | `2305955` |
| Total instruction-list nodes | `15327975` |

The targeted adapter, internal coverage, advanced bytecode, and control-flow
tests passed.

| Benchmark | Attempt 051 retained path | Attempt 089 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23241.944 ms/op` | `+330.359 ms` / `+1.44%` slower |

**Conclusion:** rejected. The candidate preserved targeted semantics, but
mutating every streamed ASM method body to remove frames cost more than any
conversion work it saved on the Android build workload. Since build-only
regressed, no end-to-end benchmark was run. The candidate was reverted and no
product code from this attempt is retained.

### 2026-08-02 — Attempt 090: Reject singleton argument-node list

**Question:** can single-argument call sites avoid `ArrayList` allocation in
`argumentNodeIds(...)` by returning a singleton list, while preserving the same
argument node ids and call-site argument order?

The Android corpus has many one-argument calls, so Attempt 090 temporarily
added a narrow `args.size == 1` branch to `argumentNodeIds(...)`. The branch
still called `getOrCreateValueNode(...)` exactly once and used the existing
`nextNodeId("unknown")` fallback for unsupported argument values; only the
temporary list representation changed.

**Validation commands:**

```
/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/jshell --class-path /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7/73d7b3086e14beb604ced229c302feff6449723/asm-9.7.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.ow2.asm/asm-tree/9.7/e446a17b175bfb733b87c5c2560ccb4e57d69f1a/asm-tree-9.7.jar
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.LambdaAnalysisTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.AdvancedBytecodeTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The ASM invocation scan showed the single-argument case is common:

| Metric | Count |
|--------|-------|
| Invoke instructions | `1742403` |
| Method invokes | `1731957` |
| Dynamic invokes | `10446` |
| 0 arguments | `686230` |
| 1 argument | `719956` |
| 2 arguments | `211921` |
| 3 arguments | `60909` |
| 4 arguments | `41608` |
| 5 arguments | `10909` |
| 6 arguments | `4185` |
| 7 arguments | `2489` |
| 8+ arguments | `4196` |

The targeted adapter, lambda, internal coverage, and advanced bytecode tests
passed.

| Benchmark | Attempt 051 retained path | Attempt 090 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `22742.010 ms/op` | `-169.575 ms` / `-0.74%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `31116.428 ms/op` | `+934.013 ms` / `+3.09%` slower |

**Conclusion:** rejected. The singleton-list branch preserved the targeted graph
shape and improved build-only time, but the full Android build-save-load-query
pipeline regressed. Since this PR is gated on end-to-end performance under the
same heap cap, the candidate was reverted and no product code from this attempt
is retained.

### 2026-08-02 — Attempt 091: Reject cached enum constructor args

**Question:** can enum `<clinit>` value extraction avoid repeatedly scanning the
same statement graph for each enum constant by caching constructor argument
values by local, while preserving the existing Jimple/SootUp enum semantics?

The existing enum extractor scans the `<clinit>` statement graph once, but when
it sees an enum field assignment it calls `findEnumInitValues(...)`, which scans
the same graph again to find the matching local's constructor call. Attempt 091
temporarily cached the constructor argument `Value` list for each local during
the main `<clinit>` pass, then converted those values with the existing
`extractValueFromArg(...)` logic at the same field-assignment point as before.
If a local was not in the cache, the previous full-scan fallback still ran.

**Validation commands:**

```
/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/jshell --class-path /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7/73d7b3086e14beb604ced229c302feff6449723/asm-9.7.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.ow2.asm/asm-tree/9.7/e446a17b175bfb733b87c5c2560ccb4e57d69f1a/asm-tree-9.7.jar
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest.should extract complex enum values with multiple constructor args" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest.should extract boxed argument enum values" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest.should extract enum values with Short Byte Float Double Boolean Character boxing" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest.should extract enum values with enum reference arguments" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest.should extract enum values from enum with static initializer block" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest.should extract enum values from DirectFieldRefEnum" --tests "io.johnsonlee.graphite.sootup.EnumValueReferenceTest" --tests "io.johnsonlee.graphite.sootup.StaticFieldIndirectReferenceTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :webgraph:jmh -Pjmh.filter='GraphEndToEndBenchmark.android_build_save_load_query$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

The ASM scan showed enum extraction has repeated-scan potential, but the total
surface is relatively small compared with full method-body conversion:

| Metric | Count |
|--------|-------|
| Enum classes | `661` |
| Enum constants | `3799` |
| Enum `<clinit>` instruction-list nodes | `37250` |
| Max enum constants in one class | `96` / `com/android/internal/telephony/CommandException$Error` |
| Max `<clinit>` instruction-list nodes in one enum | `1253` / `com/android/okhttp/CipherSuite` |

The targeted enum extraction, enum reference, and static-field indirect
reference tests passed.

| Benchmark | Attempt 051 retained path | Attempt 091 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `22816.928 ms/op` | `-94.657 ms` / `-0.41%` |
| `GraphEndToEndBenchmark.android_build_save_load_query` | `30182.415 ms/op` | `30681.917 ms/op` | `+499.502 ms` / `+1.65%` slower |

**Conclusion:** rejected. Caching enum constructor args preserved targeted enum
semantics and shaved a small amount from build-only time, but the complete
build-save-load-query path regressed under the same heap cap. The candidate was
reverted and no product code from this attempt is retained.

### 2026-08-02 — Attempt 092: Reject cached `AsmClassSource.classNode` field

**Question:** can Graphite avoid repeated reflective field lookup in
`getAsmClassNode(...)` by resolving the package-private
`AsmClassSource.classNode` field once, while preserving the existing SootUp
streaming method path?

The retained adapter already uses reflection to read SootUp's
`AsmClassSource.classNode` so it can stream `MethodNode`s without calling
`resolveMethods()` for the whole class. Attempt 092 kept the same reflective
field access and class-name guard, but temporarily cached the `Field` object in
a top-level value instead of calling `classSource.javaClass.getDeclaredField(...)`
for each class.

**Validation commands:**

```
javap -classpath /private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.java.bytecode.frontend/2.0.0/c72cad03b1ca9ad0fa8f8a835b10075da751bfa3/sootup.java.bytecode.frontend-2.0.0.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.java.core/2.0.0/d7e10779a0b3758a5adbf2eb6ea51b9b1845bdd7/sootup.java.core-2.0.0.jar:/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.soot-oss/sootup.core/2.0.0/3ccfc6d55ca06ee3170f45b2ac5a204edabcb10a/sootup.core-2.0.0.jar -p sootup.java.bytecode.frontend.conversion.AsmClassSource
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:test --tests "io.johnsonlee.graphite.sootup.SootUpAdapterTest" --tests "io.johnsonlee.graphite.sootup.SootUpAdapterInternalCoverageTest" --tests "io.johnsonlee.graphite.sootup.JavaProjectLoaderTest" --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/graphite-gradle-home ./gradlew :sootup:jmh -Pjmh.filter='GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' -Dandroid.jar.path=/private/tmp/graphite-gradle-home/caches/modules-2/files-2.1/org.robolectric/android-all/14-robolectric-10818077/94b1490a891e9be559aa35c87cd8a0c163f32d83/android-all-14-robolectric-10818077.jar --no-daemon
```

**Results:**

`javap` confirmed that SootUp 2.0.0's `AsmClassSource` has a private final
`ClassNode classNode` field. The targeted adapter, internal coverage, and
loader tests passed.

| Benchmark | Attempt 051 retained path | Attempt 092 candidate | Change |
|-----------|---------------------------|-----------------------|--------|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | `22911.585 ms/op` | `23545.991 ms/op` | `+634.406 ms` / `+2.77%` slower |

**Conclusion:** rejected. Although the candidate removed repeated reflective
field lookup, the top-level cached-field path made the Android build-only score
worse. Since build-only regressed, no end-to-end benchmark was run. The
candidate was reverted and no product code from this attempt is retained.

### 2026-10-03 — Attempt 093: Keep lambda dispatch bounded on dex bodies

**Question:** why does 2.10.0 run out of a 16 GB heap building a 185 MB Android
APK (132,500 classes in pass 2) that 2.8.0 built in 85 s and 8.9 GB, and what
bounds the cross-method lambda dispatch of #162 on inputs it cannot be precise
about?

**Fixture:** `coupang-9-3-9.apk` (185,686,651 bytes), Android platforms from
`~/Library/Android/sdk/platforms`. Base revision `v2.10.0` (`019f0024`);
candidates are the three steps of this change on top of it. Apple M3 Max,
64 GiB, OpenJDK 17.0.20.1, `-Xmx16g -XX:+UseParallelGC`, `graphite.jar build
<apk> -o <dir> -v`; max live heap read from `-Xlog:gc`, phases from `jcmd
Thread.print` samples, hot frames from JFR (`settings=profile`).

**Diagnosis:** every 2.10.0 run died at the same class, on
`ProductDetailFragment.V()`: 6,250 statements, 325 function objects, 1,624
copies between the 22 untyped registers a dex body keeps as locals (no
`LocalSplitter`). `arrayDynamicTargets` was a `MutableList` appended on every
copy, so `$u1 = $stack; $stack = $u1` doubled it each round trip (live heap
5.5 GB → 16 GB inside one method). With that fixed, the fixpoint copied a
27k-target set into millions of slots (304 M `LinkedHashMap$Entry`, 32 GB at
200 s, not converged at 40 GB). With sets shared and capped, the fixpoint
still took ~200 s: JFR put half the samples under `HashMap.getNode` /
`String.hashCode` / `TreeNode.getTreeNode` from `slotTargets[slot]`, a data
class hash of a `MethodDescriptor` per step.

**Results:**

| Revision | Outcome | Wall | Max live heap | Nodes |
|----------|---------|-----:|--------------:|------:|
| `v2.8.0` | built | 85 s | 8,878 MB | 8,155,058 |
| `v2.10.0` | `OutOfMemoryError` at class 52,000 of pass 2 | ~90 s | >16 GB (40 GB not converged) | — |
| + immutable, de-duplicated per-local sets | OOM in the fixpoint | — | >16 GB | — |
| + sets shared between slots, `MAX_TARGETS` = 64 | built | 288 s | 11,539 MB | 9,081,858 |
| + slots numbered, CSR flow graph, reverse-postorder sweeps | built | 101 s | 10,817 MB | 9,091,765 |

`./gradlew check` passes; the Tika, Hive and Kotlin compiler gate counts are
unchanged (jar bodies are split by `LocalSplitter`, so no holder there reaches
the cap). The node count differs from the FIFO variant by 9,907 because which
64 targets a holder keeps before saturating depends on arrival order; both are
deterministic.

**Conclusion:** kept. The cap changes results only where a call would have
resolved to more than 64 implementations, which the previous output expressed
as thousands of call sites per call. The remaining 16 s over 2.8.0 is the
resolution itself (930k resolved dispatch call sites that 2.8.0 did not have,
since D8 lambda classes were not function values before #162).


The next two entries were recorded independently in PR #173; their original attempt numbers are retained alongside the SootUp recovery series.
### 2026-10-07 — Attempt 094: Split dex registers before functional dispatch

**Question:** can APK calls resolve each function value independently instead of
combining every value ever held in a reused dex register, without requiring the
Android platform hierarchy in the application view? Follow-up to #167 and #168.

**Change:** append `LocalSplitter` to the dex defaults, before any configured
constant-folding passes. Leave `TypeAssigner` out: it requires the platform
hierarchy that an APK-only view does not contain. Run each method's complete dex
interceptor chain on a copied body, validate each pass, and install the result
only after the chain succeeds. An interceptor exception retains the original
body, clears that method's fold accounting and call ordinals, and increments
`JavaProjectLoader.bodyInterceptionFallbackCount`. Verbose output identifies
each fallback and prints a build summary. Errors such as `OutOfMemoryError`
retain their existing behavior. Recovery belongs at this boundary because
SootUp 3 materializes dex bodies while enumerating a class's methods, before
`SootUpAdapter.processMethod` can catch an exception.

**Fixture and revisions:** `coupang-9-3-9.apk` (185,686,651 bytes, SHA-256
`892cf8b57498196dfb453557ff92337f4e5fc82838821c5509f304015878a139`), Android
platforms from `~/Library/Android/sdk/platforms`. Measured base is `main` at
`02b853b7e5588034274d292163b79495a9ef8743`; measured candidate is
`ecccf867a42be333610885fad2fa688a87c74064`, before rebasing this change onto
`f710681749349e43cd6221be53c65932cc692165` (#172). That intervening change affects
optional constant folding, which neither local benchmark enables; CI compares
the rebased PR against the updated base. Both local runs use Apple M3 Max /
64 GiB / macOS 14.3 / OpenJDK 17.0.20.1.
The APK measurement is one fresh JVM per revision, `-Xmx16g
-XX:+UseParallelGC`, including graph build and save. Maximum live heap is the
largest post-GC value in `-Xlog:gc`; CPU and RSS come from `/usr/bin/time -l`.
Node-type counts are read independently from the persisted `graph.typeindex`;
persisted bytes sum the graph's regular files. The old #167 figures are historical
context, not the comparator: the current-main graph already differs from that
older revision.

**Validation commands:** run in ordinary clones if the publication plugin cannot
open a linked worktree's Git metadata.

```bash
./gradlew :query:shadowJar :sootup:jmhJar
/usr/bin/time -l java -Xmx16g -XX:+UseParallelGC -Xlog:gc \
  -jar frontend/jvm/query/build/libs/graphite.jar \
  build ~/Downloads/coupang-9-3-9.apk \
  --android-sdk ~/Library/Android/sdk -o /tmp/graphite-168-REVISION-graph -v
java -jar frontend/jvm/sootup/build/libs/sootup-1.0.0-SNAPSHOT-jmh.jar \
  'io.johnsonlee.graphite.sootup.GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig$' \
  -prof gc -rf json -rff /tmp/graphite-168-REVISION-jmh.json
./gradlew check koverLog
```

The method-level JMH comparison uses `android-all-14-robolectric-10818077.jar`
and the benchmark's existing defaults: single shot, no warmup, one measurement,
one fork, `-Xmx8g`. All four fixture properties (`android.jar.path`,
`tika.jar.path`, `hive.jar.path`, `kotlin.compiler.jar.path`) point to the same
cached Android, Tika 2.9.2, Hive 4.0.0 and Kotlin compiler 2.0.21 artifacts in both
runs. These local single-shot measurements describe this run, not a statistical
performance guarantee. The PR's `benchmark-regression-gate` comment supplies
the standard CI method-level and build/save/load/query comparison.

**Correctness:** the D8 fixture proves that the old dex chain reuses one register
for two distinct lambdas, and for 66 distinct lambdas beyond `MAX_TARGETS`.
Each call must resolve to the exact lambda allocated for it, in call order.
Removing only `LocalSplitter` fails both controls: a two-lambda call resolves to
two targets, while the saturated call resolves to zero. Transaction tests cover
rollback after mutation, successful processing of the next method, switch
successor order and duplicate targets, trap edges, reverse block insertion,
invalid graphs, error propagation, and failed-fold metadata cleanup. A loader
test injects a failing dex pass and checks retained calls, the fallback count
and summary, and count reset on the next load.

**Results:**

| Full APK build and save | Measured `main` | Candidate | Change |
|-------------------------|---------------:|----------:|-------:|
| Wall | 104.28 s | 142.31 s | +36.47% |
| CPU (user + system) | 303.13 s | 359.28 s | +18.52% |
| Maximum post-GC heap | 10,351 MiB | 9,433 MiB | -8.87% |
| Maximum RSS | 16,935,501,824 bytes | 17,112,383,488 bytes | +1.04% |
| Nodes | 8,541,084 | 12,933,106 | +4,392,022 |
| `LocalVariable` | 2,571,471 | 6,184,079 | +3,612,608 |
| `CallSiteNode` | 2,601,008 | 3,380,422 | +779,414 |
| Direct calls (no origin) | 2,244,024 | 2,244,024 | unchanged |
| Derived calls (with origin) | 356,984 | 1,136,398 | +779,414 |
| Persisted graph | 964,514,140 bytes | 1,310,359,525 bytes | +35.86% |
| Interceptor fallbacks | not guarded | 0 | all bodies intercepted |

The call-site sidecar independently confirms that the increase is entirely in
derived dispatch calls; the direct call count is unchanged. Unlike the earlier
#167 experiment, splitting produces a net increase in resolved calls on current
main, so the historical claim of 220k fewer calls does not apply to this pair.

| Method-level benchmark | Measured `main` | Candidate | Change |
|------------------------|---------------:|----------:|-------:|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | 20,349.162 ms/op | 20,385.158 ms/op | +0.18% |
| Allocated bytes | 37,464,485,944 B/op | 37,488,884,560 B/op | +0.07% |
| GC count / time | 37 / 882 ms | 38 / 861 ms | — |
| Process CPU | 76.36 s | 74.16 s | -2.88% |
| Process maximum RSS | 9,165,602,816 bytes | 9,132,261,376 bytes | -0.36% |

**Conclusion:** kept for correctness. The APK build/save path has an explicit
36.47% latency regression and a 35.86% persisted-size increase in exchange for
separate function values and restored dispatch beyond register saturation.
It still fits the 16 GiB heap, with lower measured live heap and no fallback.
The JAR method-level measurement is essentially unchanged (+0.18% in this
single-shot comparison); the dex-only chain does not modify that input path.
The PR benchmark comment is the authoritative separate CI method-level and
end-to-end `LargeCorpusPerformanceGateTest` comparison.

### 2026-10-07 — Attempt 095: Recover dex interception with one backup

**Question:** can the recovery boundary retain its exception isolation without
copying every successful body back or validating between passes that may need
to repair each other's intermediate graphs?

**Change:** keep one independent raw-body backup and run the primary chain in
place. Validate once at the end, matching the dex frontend's chain contract;
SootUp's own final build still validates the returned body. Only a failure
restores blocks, successor indices, traps, locals, modifiers and position from
the backup. Every retry receives fresh mutable sets. The successful path keeps
its existing graph blocks instead of reconstructing them.

`LocalSplitter` now precedes the dex defaults. A failed folding chain retries
split defaults without folding; a failed split chain retries defaults alone.
There is no production fallback to raw dex constants: exhausted defaults, or
an unexpected failure while restoring the graph, throws `BodyRecoveryException`.
This deliberately is not `IllegalStateException`, which the adapter catches
while resolving class methods and would otherwise turn into a silently empty
class. VM errors retain their existing propagation behavior.

Each successful recovery is counted and warned about even without `--verbose`.
Failed folds are reported as unsupported with the failure reason against the
body actually retained. Selected calls present in that body are not reported as
missing; truly absent selected keys still are. Failed applied-fold accounting
and call ordinals are cleared. Loader tests inject failures through per-loader
hooks, without changing the JVM-global `DexBodyInterceptors.Default` enum.

The review's numeric/null example is not evidence of a new regression in this
PR: the actual SootUp 3.0.1 default transformers discard the immutable statements
returned by several `withRValue`/`withOp` methods. A direct probe against the
bundled dependency, with an unknown local assigned the bits of `1.5f` and used
as a float argument, still yields `IntConstant(1069547520)` after the defaults;
the analogous object argument still yields `IntConstant(0)`. Preserving the
default chain is the recovery contract, not a claim that this upstream constant
decoding defect has been fixed here.

**Fixture and revisions:** the same Coupang APK, SDK, host, JDK, JVM flags,
commands and measurements as Attempt 094. The fresh base is the pre-review PR
revision `aa2d4949985a0918bc9698cab03b77711b0932c5`; the candidate is the commit
containing this attempt. Both are based on `main` at
`f710681749349e43cd6221be53c65932cc692165`. APK builds and then the real Android
JAR JMH measurements run sequentially, base before candidate, in fresh JVMs.

**Correctness:** twenty focused recovery, DEX, fold-report and frontend-contract
tests pass with the module lint gate. They cover a temporarily invalid graph
repaired by a later pass, precise final-validation diagnostics, staged retry
order, snapshot isolation across failed retries, retained switch/trap structure,
an unchanged successful graph, visible warnings without verbose logging, and
exhaustion escaping the adapter as an explicit build failure. Fold-report tests
cover failures before folding and after accounting, selected-present versus
selected-missing calls, later successful resolution, shared selections, and a
second exception during diagnostic eligibility checks. The original two-lambda
and 66-lambda dispatch regressions remain covered.

**Results:**

| Full APK build and save | Pre-review PR `aa2d4949` | Candidate | Change |
|-------------------------|------------------------:|----------:|-------:|
| Wall | 135.18 s | 130.83 s | -3.22% |
| CPU (user + system) | 365.32 s | 354.70 s | -2.91% |
| Maximum post-GC heap | 10,347 MiB | 9,524 MiB | -7.95% |
| Maximum RSS | 17,863,000,064 bytes | 17,150,459,904 bytes | -3.99% |
| Nodes | 12,833,807 | 12,932,727 | +98,920 |
| `LocalVariable` | 6,184,079 | 6,184,079 | unchanged |
| `CallSiteNode` | 3,281,123 | 3,380,043 | +98,920 |
| Direct calls (no origin) | 2,244,024 | 2,244,024 | unchanged |
| Derived calls (with origin) | 1,037,099 | 1,136,019 | +98,920 |
| Persisted graph | 1,298,067,807 bytes | 1,310,312,680 bytes | +0.94% |
| Interceptor fallbacks | 0 | 0 | unchanged |

All fifteen non-call node-type counts match, and the call-site sidecar confirms
that the count change is entirely in derived calls. The pre-review measurement
also differs from Attempt 094's candidate by 99,299 derived calls, despite the
same adapter/loader/recovery code and folding being disabled. This is consistent
with the existing arrival-order sensitivity of capped dispatch documented in
Attempt 093: the APK method set is not ordered, and saturation stops later
propagation without retracting earlier derived calls. These counts do not prove
complete graph equivalence or establish a unique cause for the variation.

| Method-level benchmark | Pre-review PR `aa2d4949` | Candidate | Change |
|------------------------|------------------------:|----------:|-------:|
| `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig` | 20,350.388 ms/op | 20,258.958 ms/op | -0.45% |
| Allocated bytes | 37,601,930,936 B/op | 37,630,952,656 B/op | +0.08% |
| GC count / time | 39 / 982 ms | 38 / 1,008 ms | — |

**Conclusion:** kept. The recovery contract and diagnostics are corrected, and
the common path avoids the extra graph reconstruction and intermediate
validations. The paired APK run is 3.22% faster and the method-level JAR result is
essentially unchanged; these single-shot results are not a stable-speedup claim.
The APK still pays the correctness cost of splitting locals relative to unsplit
`main` in Attempt 094. CI's current-base method-level and end-to-end conclusions
remain separate and are reported in the PR benchmark comment.

The SootUp recovery series continues below with its original attempt numbering.

### 2026-10-07 — Attempt 094: Reject replacing SootUp's instruction-index map

**Question:** can SootUp 3.0.1's per-method `HashMap<AbstractInsnNode, Integer>`
be replaced with ASM's existing instruction-index array without losing local
scope names, type annotations, or increasing retained memory?

**Revisions and fixture:** pre-upgrade `6f498705009689551c92c6d1ca92f67252ef77c4`
(SootUp 2.0.0), current main `c84d7811b52bd11832f97686c15876bbd7f0c244`
(SootUp 3.0.1), and that main plus the trial patch SHA-256
`8e70ff603ce429562d2778d185793e24fe5777db7e60b88198605d9673880fc7`.
The candidate SootUp JAR SHA-256 was
`b5e98aced04d1f85317012aee06641434698374c741d4e93ef9a6b48db77dbe0`.
Inputs were the real Tika app 2.9.2, Hive exec 4.0.0 and Kotlin compiler
embeddable 2.0.21 JARs pinned by `LargeCorpusPerformanceGateTest`, with its
fixture checksums and full persisted-graph correctness checks enabled.

The trial subclassed `AsmMethodSource`, installed a read-only map backed by
`InsnList.indexOf`, and checked node identity to reject a foreign node with the
same cached index. It restored the previous ASM array after conversion and
cleared SootUp's scope caches on both successful and exceptional exits. This
avoided retaining an extra instruction array per method. No interceptors,
type inference, annotations, or control-flow work were disabled.

**Validation and environment:** Apple M3 Max, 16 cores, 64 GiB, macOS arm64,
Homebrew OpenJDK 17.0.20.1. `:sootup:test :sootup:detekt` passed: 458 tests,
including exact stock-SootUp statement/local/annotation/CFG comparisons, JSR
inlining, repeated body resolution, foreign-node membership, and cache cleanup
on success/failure. All 27 large-corpus gate runs passed. Local publishing-plugin
application was omitted on both revisions because the plugin cannot resolve
this Git worktree; production sources and runtime artifacts were sealed before
measurement. This was not a dedicated host: Spotlight consumed approximately
one CPU core. Results are directional, not narrow-confidence claims.

Each gate ran in a fresh `java -Xmx4g` JVM with
`-Dlarge.corpus.record=true` and the three fixture path properties, invoking
`org.junit.runner.JUnitCore io.johnsonlee.graphite.webgraph.{Tika,Hive,KotlinCompiler}CorpusPerformanceGateTest`.
Three rounds rotated old/main/candidate, main/candidate/old, and candidate/old/main.
The record property removes timing ceilings only; shape/query/branch-definition
checks remain enabled. `/usr/bin/time -l` measured complete-process CPU/RSS;
CPU includes correctness checks and cleanup, not just the timed pipeline.
The gate configuration disables call-graph construction, annotation extraction,
and cross-method functional dispatch on **all** revisions; it does not represent
the full CLI default configuration or APK performance.

**Results (three-run medians):**

| Corpus / metric | Pre-upgrade | Main 3.0.1 | Trial |
|-----------------|------------:|-----------:|------:|
| Tika build, ms | 12,848 | 13,046 | 13,121 |
| Tika pipeline, ms | 19,091 | 19,174 | 19,144 |
| Tika total CPU, s | 69.77 | 79.99 | 82.19 |
| Tika max RSS, bytes | 5,128,634,368 | 5,018,271,744 | 5,029,199,872 |
| Kotlin build, ms | 12,377 | 12,511 | 12,694 |
| Kotlin pipeline, ms | 18,414 | 18,322 | 18,324 |
| Kotlin total CPU, s | 70.61 | 76.19 | 77.16 |
| Kotlin max RSS, bytes | 4,944,674,816 | 5,104,599,040 | 4,983,767,040 |
| Hive build, ms | 23,800 | 23,690 | 23,772 |
| Hive pipeline, ms | 32,168 | 31,692 | 31,726 |
| Hive total CPU, s | 105.18 | 125.69 | 126.14 |
| Hive max RSS, bytes | 5,179,801,600 | 5,265,408,000 | 5,236,834,304 |

Method-level command, with the same fixture properties supplied to every fork:

```sh
java -jar <revision-sootup-jmh.jar> \
  'io.johnsonlee.graphite.sootup.GraphBuildBenchmark.buildKotlinCompilerGraphEndToEndConfig$' \
  -bm ss -tu ms -wi 0 -i 1 -f 2 -t 1 -foe true \
  -jvmArgs '-Xmx8g' -jvmArgsAppend '<fixture-path-properties>' \
  -prof gc -rf json -rff <revision-jmh.json>
```

| Kotlin JMH metric (two cold forks) | Pre-upgrade | Main 3.0.1 | Trial |
|-----------------------------------|------------:|-----------:|------:|
| Build, ms/op | 12,544.580 | 12,160.958 | 11,990.444 |
| Allocation, bytes/op | 23,033,373,160 | 23,853,566,512 | 23,655,538,712 |

**Conclusion:** rejected. The method-level trial reduced allocation by 0.83%
versus main and its point-estimate build latency by 1.40%, but allocation was
still 2.70% above the pre-upgrade baseline. End-to-end build medians did not
improve over main on any of the three corpora, and whole-process CPU medians
remained above both main and the pre-upgrade baseline. This does not establish
broad performance recovery and does not justify the additional reflective cache
lifecycle. The trial production code and its tests are removed; no benchmark
threshold or semantic baseline is relaxed. Android JMH was not run for this
rejected candidate. Continue with method-wrapper lifetime and full-default
JAR/APK measurements.

Raw commands, fixture/runtime hashes and logs remain in
`/tmp/graphite-sootup-recovery.6wpE4s`; the immutable trial source patch and JMH
artifact are in `/tmp/sootup-recovery-sources/candidate-final-snapshot`.

### 2026-10-07 — Attempt 095: Stream method wrappers from the existing ASM sources

**Question:** can SootUp 3.0.1 avoid eagerly retaining every `JavaSootMethod`
wrapper and repeatedly parsing its descriptor while preserving method bodies,
annotations, dispatch, and the normal view used by call-graph construction?

**Revisions and fixtures:** pre-upgrade `6f498705009689551c92c6d1ca92f67252ef77c4`
(SootUp 2.0.0), main `c84d7811b52bd11832f97686c15876bbd7f0c244`
(SootUp 3.0.1), and the `streaming` working-tree snapshot on
`7c56de32bbe3e69ea000960637af4b440861fcbb` (main plus the rejected-attempt
record, then this uncommitted production patch). The immutable source state,
including untracked sources, is recorded in
`/tmp/sootup-recovery-sources/streaming-snapshot/source-state.json`.

| Snapshot | JMH JAR SHA-256 |
|----------|----------------|
| Pre-upgrade | `70cb0af6a19f7e0966832a2fd82b936d82da4d572487ae1715d168ca515a3543` |
| Main | `3bfc2c3ec8f269959f42b1559ee9ed173a99a937e66683931d3100de666c5039` |
| Streaming | `ec055333aabfc6645bf004ecd8b0b64d3871ce621cce2e165cd21d55c290d0fe` |

The streaming runtime SootUp adapter JAR SHA-256 is
`d803d814234411472ecd311106cdbb3bfa7479bb7d3718e576d693237c755523`.
The real Tika app 2.9.2, Hive exec 4.0.0 and Kotlin compiler embeddable 2.0.21
fixtures, their exact paths/checksums and all runtime artifact hashes are in
`/tmp/graphite-sootup-recovery.6wpE4s/{preupgrade,main,streaming}.json`.

The candidate exposes `AsmMethodSource`s only from Graphite's own lazy class
source. It sorts by ASM method name/descriptor, creates fresh wrappers when a
method is visited, and reuses the source's signature cache. Declaration and
return-type annotations, exceptions, modifiers and position follow stock
SootUp. Annotation classes, overriding sources and DEX keep their existing
paths. The source scratch maps are still released after conversion and after
call-graph construction. No feature, interceptor, cap, query check or semantic baseline
was disabled to obtain the measurements.

**Validation and environment:** Apple M3 Max, 16 CPUs, 64 GiB, macOS 14.3
arm64, Homebrew OpenJDK 17.0.20.1. This is the same shared development host as
Attempt 094; measurements were serialized, not obtained on a dedicated runner.
The local publishing-plugin workaround remains identical across snapshots.
`:sootup:test :sootup:detekt` passed: 459 tests, zero failures/errors/skips.
Tests compare stock versus streamed signatures, metadata, statement/local/CFG
shape, wrapper body-cache independence, repeated rich annotation conversion,
and avoidance of the persistent method set. Test XML is under
`frontend/jvm/sootup/build/test-results/test`; build logs are
`/tmp/sootup-recovery-sources/streaming-test.log` and
`/tmp/sootup-recovery-sources/streaming-scoped-test.log`.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ./gradlew :sootup:test :sootup:detekt --console=plain
bash /tmp/graphite-sootup-recovery.6wpE4s/run-streaming-stage1.sh
for label in preupgrade main streaming; do
  python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py jmh "$label" \
    --run coldstreaming1 --forks 2 --corpus kotlin
done
```

The stage-one script runs one rotated three-revision round per corpus
(Tika old/main/streaming; Kotlin main/streaming/old; Hive streaming/old/main).
All nine `LargeCorpusPerformanceGateTest` runs passed. Each uses a fresh
`java -Xmx4g`, `-Dlarge.corpus.record=true`, the pinned fixture properties and
`JUnitCore` with `{Tika,Hive,KotlinCompiler}CorpusPerformanceGateTest`.
The record flag removes timing ceilings, not graph/query/branch-definition
correctness assertions. These gates and the JMH `EndToEndConfig` method disable
call-graph construction, annotation extraction and cross-method functional
dispatch. They are reduced-configuration evidence, not CLI-default results.
Whole-process CPU/RSS below includes gate verification and cleanup.

**Results (one gate run per revision/corpus):**

| Corpus / metric | Pre-upgrade | Main 3.0.1 | Streaming |
|-----------------|------------:|-----------:|----------:|
| Tika build, ms | 12,555 | 12,728 | 12,474 |
| Tika pipeline, ms | 18,734 | 18,936 | 18,292 |
| Tika total CPU, s | 68.80 | 75.78 | 79.30 |
| Tika max RSS, bytes | 5,077,041,152 | 5,030,445,056 | 5,188,091,904 |
| Hive build, ms | 23,099 | 23,246 | 22,515 |
| Hive pipeline, ms | 31,903 | 31,561 | 30,748 |
| Hive total CPU, s | 108.48 | 122.08 | 117.40 |
| Hive max RSS, bytes | 5,413,896,192 | 5,287,100,416 | 5,256,544,256 |
| Kotlin build, ms | 11,997 | 12,435 | 11,921 |
| Kotlin pipeline, ms | 17,457 | 17,780 | 17,361 |
| Kotlin total CPU, s | 66.07 | 76.60 | 76.46 |
| Kotlin max RSS, bytes | 5,124,407,296 | 5,022,580,736 | 5,187,813,376 |

JMH ran
`GraphBuildBenchmark.buildKotlinCompilerGraphEndToEndConfig` with
`-bm ss -tu ms -wi 0 -i 1 -f 2 -t 1 -foe true -jvmArgs '-Xmx8g' -prof gc`,
the same pinned fixture properties and the Java 17 executable above. These are
two cold forks, not a stable-throughput estimate; Android JMH was not completed.

| Kotlin JMH metric | Pre-upgrade | Main 3.0.1 | Streaming |
|-------------------|------------:|-----------:|----------:|
| Build, ms/op | 12,431.475 | 12,161.500 | 11,653.318 |
| Allocation, bytes/op | 23,127,506,544 | 23,885,424,448 | 23,217,131,304 |

**Full-default JAR check:** Kotlin was also run separately with CHA call-graph
construction, annotations and cross-method functional dispatch enabled;
`GraphStore.save(..., compressionThreads=2, prepareCallSiteStringIndex=true)`;
the CLI node scan; mapped load; and node/callsite count queries. Every revision
used `-Xmx8g`. The full structural verification ran in separate JVMs so its
CPU/RSS did not enter this pipeline measurement.

```sh
for label in main preupgrade streaming; do
  python3 /tmp/graphite-apk-recovery/run.py run "$label" \
    /Users/johnsonlee/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21/79346ed53db48b18312a472602eb5c057070c54d/kotlin-compiler-embeddable-2.0.21.jar \
    "$label-kotlin-default-streaming1" --heap 8g --sdk -
done
# Main/streaming use the same run command with --verify for separate verification.
python3 /tmp/graphite-apk-recovery/run.py compare \
  main-kotlin-default-streaming1 streaming-kotlin-default-streaming1
python3 /tmp/graphite-apk-recovery/verification/run-both.py
```

| Kotlin full-default metric (one run) | Pre-upgrade | Main 3.0.1 | Streaming |
|-------------------------------------|------------:|-----------:|----------:|
| Pipeline, ms | 113,003.132 | 38,216.017 | 37,931.140 |
| Pipeline process CPU, ms | 188,452.537 | 119,933.034 | 114,541.638 |
| Whole-process max RSS, bytes | 7,682,211,840 | 9,623,306,240 | 9,601,695,744 |
| Nodes | 4,657,648 | 4,744,132 | 4,744,132 |
| Callsites | 2,173,010 | 2,251,811 | 2,251,811 |

Main and streaming have identical complete mapped node/edge/connected-callsite
shape signatures. Supplementary metadata signatures and API checks also match:
249,669 method descriptors and annotation lookups, 102,495 annotated members,
122,983 annotation nodes, synthetic identities, origins, artifact dependencies,
64 exact method queries, and 353,541 control-flow comparisons with their
comparand-node connectivity. These signatures use normalized node IDs and do
not constitute proof for untested APIs such as all resource contents or all
query paths. The full-default pre-upgrade graph has different node/callsite
counts; its timing is not a like-for-like claim that all old/new semantics are
equivalent. The reduced configuration and full defaults must not be combined
into one recovery percentage.

**Within-trial refinements:** the final streamed-method tests were strengthened, including concrete body/metadata and repeat-conversion checks, and GraphiteClassNode KDoc was corrected to describe deferred member resolution rather than an inaccurate double-read claim. These belong to this trial's final implementation, not a new performance hypothesis. The stronger tests also passed complete module/lint runs in later frozen builds (464 tests in 099; 471 in 100); this supplements the original 459-test evidence without pretending those later builds remeasure the original 095 artifact.

**APK boundary:** TVBox showed run-to-run variation in the existing DEX method
set order and capped functional-dispatch output. A correctness-only Java agent
sorted the exact same fallback method objects by signature on both main and
streaming, leaving the cap and features intact; their complete mapped shape
signatures then matched. That agent's timings are excluded from performance
evidence. The diagnostic and comparison are retained under
`/tmp/graphite-apk-recovery/diagnostic` and
`/tmp/graphite-sootup-recovery.6wpE4s/tvbox-deterministic-differences.json`.
The user subsequently deprioritized APK. Only the pre-upgrade Coupang run
completed; the corresponding main/streaming runs and verification were paused.
There is no complete Coupang comparison and no performance conclusion for it.

**Conclusion:** RETAINED as a component of the aggregate recovery chain, not standalone proof that the recovery goal is fully met. The method-level point estimate and all three reduced
pipeline times improve over main in this round. Allocation approaches, but
remains above, pre-upgrade. CPU/RSS have not uniformly recovered: Tika CPU/RSS
are above main and old; reduced Kotlin CPU remains above old; full-default
Kotlin RSS remains above old. One round does not establish absence of regression
or complete achievement of the recovery objective.

Separate diagnostic JFR runs found substantially more sampled ForkJoin worker
CPU in streaming than pre-upgrade. This is a next independent hypothesis about
parallel class parsing/worker contention; the CPU difference is not attributed
to wrapper streaming without further controls and does not justify changing
global concurrency in this attempt. Profiles use bounded sampled
thread loads, not exact per-thread CPU counters; their timings are excluded
from the tables. Commands and JFR/GC summaries are in
`/tmp/graphite-sootup-recovery.6wpE4s/profile-kotlin.sh` and `profiles/`.

Exact launched JVM commands and fixture/runtime hashes are retained in each
`command.json`; raw gate/JMH evidence is under
`/tmp/graphite-sootup-recovery.6wpE4s/results/*-streaming1` and
`*-coldstreaming1`. Full-default phase/resource logs and both fingerprint files
are under `/tmp/graphite-apk-recovery/results/*-kotlin-default-streaming1`;
supplementary comparison is
`/tmp/graphite-apk-recovery/verification/differences.json` (empty).

Protocol clarification (recorded when Attempt 099 introduced a separate quiet harness): every ProductionPipeline full-default table in this attempt used the original verbose callback. Here "full-default" means full graph features (CHA, annotations and cross-method dispatch), not literally every LoaderConfig field or silent execution. Quiet full-feature results are recorded separately in Attempt 099 and cannot be mixed with these historical timings. The publishing-plugin workaround was identical in frozen snapshots; the five publishing-plugin declarations were later restored in the working tree.

### 2026-10-07 — Attempt 096: Parse class sources on the calling thread

**Status:** historically REJECTED; retained as an experiment record only. The later causal correction below remains part of this record. Retaining the distinct exact-input serial combination in 098 does not retroactively select this 096 artifact.

**Question:** does the CPU still above the pre-upgrade baseline in Attempt 095
come from parallel class parsing, and can it be recovered without changing
SootUp features, dispatch, or process-wide worker settings?

**Revisions and fixture:** the pre-upgrade and main snapshots are the same
`6f498705009689551c92c6d1ca92f67252ef77c4` and
`c84d7811b52bd11832f97686c15876bbd7f0c244` artifacts as Attempt 095.
`streaming` is that attempt's immutable parallel-parsing candidate;
`streaming-serial` is its one-line follow-up, on recorded checkout revision
`7c56de32bbe3e69ea000960637af4b440861fcbb`. Full source states, fixture paths,
checksums and runtime artifact hashes are in
`/tmp/graphite-sootup-recovery.6wpE4s/{preupgrade,main,streaming,streaming-serial}.json`.
The candidate's runtime SootUp adapter JAR SHA-256 is
`3814dd402aa88e76644eaa3971b9da3d4be6590782f3d36a27d236272d70a76d`;
its frozen JMH JAR SHA-256 is
`6bec54199b585fafabd36de5d4cd04fa6b5bdcd93d699e447bf6d2e0a8768154`.

The only production behavior change from Attempt 095 is
`files.parallelStream()` → `files.stream()` in
`ParsedClassLocation.getClassSources`. Parsing now runs on the calling thread;
class encounter order, parser, lazy sources, method-wrapper streaming,
interceptors, annotations, dispatch cap and graph functionality are unchanged.
There were no feature or JVM-worker flag changes in this trial. The JAR
class comparison found no added or removed classes and differences only in
`ParsedClassLocation` and its companion; the comparison is saved at
`/tmp/sootup-recovery-sources/streaming-serial-snapshot/artifact-diff.json`.

The old KDoc's assertion that SootUp's provider reads the class file twice was
checked and found incorrect. The working-tree comments now describe deferred
member resolution rather than a double-read explanation. Measurements use the
frozen serial snapshot from before this comment correction; this subsequent
difference is documentation only, not another performance implementation.

**Validation and environment:** `:sootup:test :sootup:detekt` passed, with
459 tests and no failures/errors/skips. The validation log is
`/tmp/sootup-recovery-sources/streaming-serial-test.log`. Environment remains
Apple M3 Max, 16 CPUs, 64 GiB, macOS 14.3 arm64, Homebrew OpenJDK 17.0.20.1;
this is a shared development host, with serialized measurement JVMs. The
publishing-plugin workaround is identical across snapshots. All inputs below
are the pinned real Tika app 2.9.2, Hive exec 4.0.0 and Kotlin compiler
embeddable 2.0.21 JARs.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :sootup:test :sootup:detekt --no-daemon
bash /tmp/graphite-sootup-recovery.6wpE4s/run-pool-diagnostics.sh
for label in preupgrade streaming-serial streaming; do
  python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py gate "$label" \
    --run serial1 --corpus kotlin
done
bash /tmp/graphite-sootup-recovery.6wpE4s/run-serial-expansion.sh
```

Every gate is a fresh `java -Xmx4g` with the fixture path properties,
`-Dlarge.corpus.record=true`, and `JUnitCore` running the relevant
`LargeCorpusPerformanceGateTest` subclass. Recording removes timing ceilings,
not semantic checks. Call-graph construction, annotation extraction and
cross-method functional dispatch are disabled for these reduced-configuration
gates on all revisions. The complete-process CPU/RSS includes correctness
checks and cleanup. These results must not be presented as full-default JAR
results. All three pool diagnostics, three isolation runs and eleven expansion
runs passed their graph/query/branch-definition correctness checks.

**Global-pool diagnostics (Kotlin, all using Attempt 095):**

| Common-pool setting | Build, ms | Save, ms | Pipeline, ms | Total CPU, s | Max RSS, bytes |
|---------------------|----------:|---------:|-------------:|-------------:|---------------:|
| Default | 12,104 | 4,164 | 17,507 | 75.87 | 5,158,273,024 |
| `parallelism=3` | 12,292 | 4,294 | 17,760 | 75.48 | 4,976,672,768 |
| `parallelism=1` | 12,489 | 7,989 | 21,729 | 78.11 | 4,996,972,544 |

The diagnostic flags were
`-Djava.util.concurrent.ForkJoinPool.common.parallelism=3` and `=1`.
They did not recover pre-upgrade CPU in these observations, and limiting the
global pool also changes consumers outside class parsing. Neither flag is part
of this candidate; these runs are not evidence for globally limiting workers.

**Isolation round (Kotlin, execution order old → serial → streaming):**

| Snapshot | Build, ms | Save, ms | Pipeline, ms | Total CPU, s | Max RSS, bytes |
|----------|----------:|---------:|-------------:|-------------:|---------------:|
| Pre-upgrade | 12,585 | 7,887 | 21,667 | 71.28 | 5,065,113,600 |
| Streaming serial | 12,429 | 4,180 | 17,796 | 71.06 | 5,154,324,480 |
| Streaming parallel | 12,182 | 4,787 | 18,190 | 79.51 | 5,157,994,496 |

The local serial change brought total CPU near the old observation, while
build time was 247 ms slower than parallel streaming. The old 7,887 ms save is
a long-tail observation in an unchanged stage; its large contribution to the
pipeline difference must not be credited to serial class parsing.

**Cross-corpus expansion (all observed runs, no best-run selection):**

The execution order was Tika main/streaming/serial/old; Hive
serial/old/streaming/main; Kotlin serial/main/old. There was no additional
parallel-streaming Kotlin run in this round.

| Corpus / snapshot | Build, ms | Save, ms | Pipeline, ms | Total CPU, s | Max RSS, bytes |
|-------------------|----------:|---------:|-------------:|-------------:|---------------:|
| Tika main | 12,774 | 4,629 | 18,800 | 77.91 | 5,029,396,480 |
| Tika streaming | 12,648 | 4,604 | 18,621 | 82.04 | 5,192,761,344 |
| Tika serial | 12,984 | 4,425 | 18,661 | 73.48 | 5,180,424,192 |
| Tika pre-upgrade | 12,861 | 4,551 | 18,888 | 73.14 | 4,826,169,344 |
| Hive serial | 23,516 | 6,136 | 31,399 | 110.65 | 5,440,962,560 |
| Hive pre-upgrade | 23,059 | 6,243 | 30,926 | 109.56 | 5,229,166,592 |
| Hive streaming | 23,006 | 6,088 | 30,840 | 126.48 | 5,166,137,344 |
| Hive main | 23,430 | 6,801 | 31,929 | 128.25 | 5,272,223,744 |
| Kotlin serial | 12,494 | 4,217 | 17,887 | 67.52 | 5,161,582,592 |
| Kotlin main | 12,176 | 4,102 | 17,436 | 74.99 | 5,127,995,392 |
| Kotlin pre-upgrade | 12,430 | 7,898 | 21,518 | 70.17 | 4,986,929,152 |

The CPU recovery from local serial parsing repeats across these corpora.
It is not a free improvement: against parallel streaming, serial build is
336 ms slower on Tika and 510 ms slower on Hive (approximately 2–3%, consistent
with the Kotlin isolation round). Serial pipeline is also slower than streaming
on both Tika and Hive here, and slower than main on Kotlin. RSS is above the
old observations for all three corpora; Hive serial RSS is also above main and
streaming. None of these costs is hidden by averaging metrics or selecting a
favorable round. Kotlin old again spent 7,898 ms saving; do not interpret its
resulting 21.5 s pipeline as a parsing speedup attributable to this change.

**Full-default Kotlin follow-up:** the serial trial was then tested with all
three default features enabled and prepared-index saving, using the independent
`ProductionPipeline` harness and `-Xmx8g --sdk -`. Unlike the reduced gates,
the original unprofiled serial runs both took over 11 s to save. All observed
unprofiled comparison rows are retained below; the earlier main row is the
Attempt 095 observation, not an additional paired repetition.

| Full-default run | Build, ms | Save, ms | Pipeline, ms | Total CPU, s | Max RSS, bytes |
|------------------|----------:|---------:|-------------:|-------------:|---------------:|
| Main `default-streaming1` | 31,165.716 | 6,475.568 | 38,216.017 | 120.11 | 9,623,306,240 |
| Serial `default-serial1` | 31,012.424 | 11,209.702 | 42,752.248 | 116.02 | 9,621,831,680 |
| Main `default-serial2` | 30,127.944 | 6,235.762 | 36,937.625 | 114.40 | 9,604,808,704 |
| Serial `default-serial2` | 30,833.724 | 11,333.211 | 42,690.832 | 116.47 | 9,651,912,704 |

Existing graph-shape verification between main and serial passed; see
`/tmp/graphite-sootup-recovery.6wpE4s/kotlin-main-serial-differences.json`.
The slower save/pipeline result cannot be dismissed on correctness grounds.

Further paired diagnostics gave faster saves under some conditions, but did
not establish why the two original serial observations were slow:

| Diagnostic protocol | Main save, ms | Serial save, ms | Interpretation |
|---------------------|--------------:|----------------:|----------------|
| JFR + GC/safepoint log | 6,589.544 | 6,287.958 | Profiled runs; excluded from performance claims |
| Repeated `jcmd Thread.print -l` | 26,945.480 | 25,778.092 | Heavy attachment interference; invalid performance comparison |
| `Thread.print` without `-l` | 6,575.646 | 6,490.892 | Lighter attachment, still diagnostic and excluded |
| Late-only `Thread.print`, after save exceeds 8 s | 6,495.320 | 6,619.720 | Both finished before threshold; zero attachments recorded |

The heavy `-l` sampler substantially perturbed both runs. Removing `-l` made
sampling lighter and both saves were fast, but attachment remains a confounder.
The final late-only protocol independently records `attachmentCount=0` for both
runs, so it did not cause their fast result by attaching. That does not erase
the prior 11.2/11.3 s observations or prove the serial change is safe against
save regressions. All protocols and all original samples are preserved; no
fast diagnostic observation is substituted for an earlier slow unprofiled run.

The full-default JVM commands are in
`/tmp/graphite-apk-recovery/results/{main,streaming-serial}-kotlin-default-*/command.json`.
Paired diagnostics are in the same results directory under
`*-kotlin-save-diagnostic1`, `*-kotlin-save-thread-diagnostic1`,
`*-kotlin-save-thread-diagnostic2` and
`*-kotlin-save-thread-late-diagnostic1`. JFR, GC logs and summaries are under
`/tmp/graphite-sootup-recovery.6wpE4s/profiles/`; each thread-sampling directory
contains `samples.json`. The late-only protocol is preserved as
`profiles/sample-save-threads-late-v1.py`.

**Conclusion:** REJECTED. Local serial parsing reduced CPU in the reduced
configuration, but slowed builds relative to Attempt 095, did not restore RSS
uniformly, and the full-default observations included substantial unresolved
long save/pipeline times. Later fast diagnostic runs do not establish the
required recovery without regressions. At this decision, the serial production change was
reverted and Attempt 095's parallel parsing remained; later Attempt 098 tests a distinct combination. Kotlin and Android JMH for
this serial trial were not completed, and no scores are inferred. APK remains
deprioritized. Subsequent input-stream parsing work is a separate Attempt 097,
not part of this experiment's results.

**Subsequent evidence (Attempt 097):** the unchanged main snapshot later also
saved full-default Kotlin slowly: 11,467.024 ms wall and 23,220.831 ms process
CPU, while exact-input saved in 6,390.335 ms. Thus the roughly 11 s save is not
unique to serial parsing. The original two slow serial / fast main pairs did
not establish that serial parsing caused the long save or must always regress
it. This does not retroactively change the recorded observations or the
rejection decision made with unresolved evidence; it corrects the causal
interpretation. Both main and serial have now shown fast and slow states.
See `trial097-exact-paired-results.json` under the harness root. A new serial
experiment on the exact-input base is Attempt 098 and must be evaluated
independently rather than overwriting this history.

Raw commands, environment, fixture/runtime hashes and results are preserved in
`/tmp/graphite-sootup-recovery.6wpE4s/results/*-pool*`, `*-serial1` and
`*-serial2` (`command.json`, `stdout.log`, `stderr-time.log`). The unchanged
JFR diagnosis motivating this attempt is in `profiles/` under the same root;
profiled timing is not included in the tables. The source snapshot and build
log are in `/tmp/sootup-recovery-sources/streaming-serial-snapshot`.

Protocol clarification (recorded when Attempt 099 introduced a separate quiet harness): every ProductionPipeline full-default table in this attempt used the original verbose callback. Here "full-default" means full graph features (CHA, annotations and cross-method dispatch), not literally every LoaderConfig field or silent execution. Quiet full-feature results are recorded separately in Attempt 099 and cannot be mixed with these historical timings. The publishing-plugin workaround was identical in frozen snapshots; the five publishing-plugin declarations were later restored in the working tree.

### 2026-10-07 — Attempt 097: Read exact class bytes through an input stream

**Question:** can class input avoid the generic `Files.readAllBytes(Path)`
channel-reading path while retaining parallel parsing, the streamed wrappers
from Attempt 095, and exactly the same acceptance of valid and malformed
class files?

**Revisions and hypothesis:** the retained starting implementation is Attempt
095 (`streaming`), not the rejected serial parser from Attempt 096. Both trial
snapshots below were frozen on checkout revision
`7c56de32bbe3e69ea000960637af4b440861fcbb`, with their distinct working-tree
patches recorded in their manifests/source states. Pre-upgrade and main remain
`6f498705009689551c92c6d1ca92f67252ef77c4` and
`c84d7811b52bd11832f97686c15876bbd7f0c244`.

The hypothesis is that opening the class input directly may avoid channel-path
allocation/work without changing parsing concurrency or functionality. It is
not a hypothesis that SootUp reads each class twice: the earlier double-read
comment was found inaccurate, and deferred member resolution is the relevant
frontend distinction.

| Frozen trial | Runtime adapter JAR SHA-256 | Status |
|--------------|---------------------------|--------|
| `streaming-input` | `51498cba1656bb770841fa919e98f21c46f5e83ce86d8637d5e847506b46bfc6` | Defective; ineligible for selection |
| `streaming-input-exact` | `20aded41b6de3c6d250358efb66347efbe60c3d7c997c3e94ae9a66bdf9e36fe` | Corrected trial; retained in aggregate chain |

The corresponding JMH JAR SHA-256 values are
`3c061cd669b82dddbfd66d4a6a1a5f59e6ab6818833b7b03b60f326501daa5bf`
(defective trial) and
`0cbe3dd14f1fb41d4b1554e59597de7de76922f7b1bec6421f01925f7cf616f1`
(exact-input trial). Full immutable runtime/fixture hashes are in
`/tmp/graphite-sootup-recovery.6wpE4s/{streaming-input,streaming-input-exact}.json`;
source states/build logs are under the matching
`/tmp/sootup-recovery-sources/*-snapshot` directories.

**Correctness finding:** the first trial passed the stream directly to
`ClassReader(InputStream)`. ASM's stream-reading path can retain a minimum
256-byte buffer with zero padding for a smaller input. A short class whose
trailing `attributes_count` bytes are missing can therefore appear to have a
zero count instead of being rejected, unlike exact-length byte-array parsing.
This changes malformed-input behavior; performance measurements of that trial
cannot establish a safe optimization.

The frozen repaired trial applied this replacement to both directory and archive inputs, keeping explicit stream closure and exact input length:

```kotlin
Files.newInputStream(file).use { input ->
    ClassReader(input.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES)
}
```

It retains the existing parallel class stream, parsing flags, class-name
validation, lazy sources, annotations, interceptors, functional dispatch and
call-graph configuration. Exact-input performance must be measured afresh:
using `readAllBytes()` can change allocation and work relative to ASM's padded
stream reader. The old trial's apparent allocation savings are not attributed
to this corrected code.

**Validation:** the new test creates a valid class smaller than 256 bytes,
asserts that its final two bytes are `attributes_count`, removes those bytes,
and provides a complete control class returning integer `7`. For both a JAR
and a directory, the corrected frontend must skip the truncated class in
enumeration and direct lookup while preserving the complete class and its
concrete return statement/value.

The exact same test source was run against the frozen defective runtime. It
failed for the intended reason on the JAR input: enumeration returned
`[p.q.Truncated, p.q.Complete]` instead of `[p.q.Complete]`. The other four tests
in that class passed; this was an assertion failure, not an incidental build
or classpath error. With the corrected runtime, all five tests in that class
passed, and the complete suite plus detekt passed: 460 tests, zero
failures/errors/skips.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :sootup:test :sootup:detekt --no-daemon
```

The red/green evidence is preserved in
`/tmp/graphite-sootup-recovery.6wpE4s/trial097-exact-red-green/`:
`red-command.json` contains the exact old-runtime JUnit command; `red.log`
contains the single expected failure; `green.xml` contains the passing five-test
suite; `evidence.json` records the complete 460-test result and test-source
SHA-256 `1a4fa058207f7255f87c65a84e5cee0692beb350e49e0f72e9e781bcace5bfba`.
The successful full build log is
`/tmp/sootup-recovery-sources/streaming-input-exact-test.log`.

**Performance evidence boundary:** the real Kotlin/Tika gates and Kotlin JMH
already run under `streaming-input` remain preserved as measurements of a
known-defective implementation, not selection evidence for `streaming-input-exact`.
Their commands and results are under
`/tmp/graphite-sootup-recovery.6wpE4s/results/streaming-input-*`; eligibility is
explicitly revoked in `trial097-inputstream-correctness-status.json`. No old
number is copied into an exact-input result table or used to claim its benefit.

**Corrected-input method-level result:** the frozen exact-input artifact has
now completed two cold forks of
`GraphBuildBenchmark.buildKotlinCompilerGraphEndToEndConfig`, using
`-bm ss -tu ms -wi 0 -i 1 -f 2 -t 1 -foe true -jvmArgs '-Xmx8g' -prof gc`
and the same fixture properties as Attempt 095.

```sh
python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py jmh streaming-input-exact \
  --run coldexact1 --forks 2 --corpus kotlin
```

| Kotlin JMH metric | Pre-upgrade | Main | Attempt 095 | Exact input |
|-------------------|------------:|-----:|------------:|------------:|
| Build, ms/op | 12,431.475 | 12,161.500 | 11,653.318 | 11,598.210 |
| Allocation, bytes/op | 23,127,506,544 | 23,885,424,448 | 23,217,131,304 | 23,067,210,448 |

Exact input's allocation point estimate is 149,920,856 bytes/op below Attempt
095 (0.646%), 3.426% below main and 0.261% below pre-upgrade. The small
old/exact difference is not treated as a significant win on two cold forks.
The previous baseline rows are the saved Attempt 095 JMH observations, not
fresh contemporaneous reruns. The corrected and defective input variants are
not combined. Exact commands, per-fork values and the successful result are in
`results/streaming-input-exact-jmh-kotlin-coldexact1/` and
`trial097-exact-kotlin-jmh-comparison.json` under the harness root.

**Corrected-input paired follow-up:** exact-input and unchanged main each ran
one Tika reduced gate and one full-default Kotlin pipeline. The full-default
execution order was exact-input then main. Both Tika gates passed with matching
node/edge/method/callsite and branch-definition counts; all measured phase and
resource outcomes are retained rather than selecting the faster stage.

| Tika reduced gate metric | Main | Exact input |
|--------------------------|-----:|------------:|
| Build, ms | 12,939 | 12,410 |
| Save, ms | 4,477 | 4,842 |
| Mapped load median, ms | 87 | 89 |
| Query, ms | 1,177 | 1,196 |
| Pipeline, ms | 18,680 | 18,537 |
| Whole-process wall, s | 22.09 | 21.92 |
| Whole-process CPU, s | 83.55 | 77.29 |
| Max RSS, bytes | 5,214,617,600 | 5,038,473,216 |
| Sampled peak heap, bytes | 3,802,953,216 | 3,879,632,896 |

The Tika build/CPU/RSS observations improve, while its save, load and query
observations are slightly slower and sampled peak heap is higher. This is one
paired observation, not proof of uniform recovery.

| Kotlin full-default phase | Main wall, ms | Exact wall, ms | Main CPU, ms | Exact CPU, ms |
|---------------------------|--------------:|---------------:|-------------:|--------------:|
| Build | 30,043.259 | 30,656.223 | 93,970.659 | 98,205.022 |
| CLI node count | 327.117 | 341.491 | 631.430 | 650.073 |
| Prepared save | 11,467.024 | 6,390.335 | 23,220.831 | 17,974.915 |
| Close source | 0.046 | 0.035 | 0.165 | 0.068 |
| Mapped load | 157.507 | 165.635 | 274.897 | 304.428 |
| All-node count query | 36.817 | 38.486 | 119.387 | 170.306 |
| Callsite count query | 0.941 | 0.892 | 4.859 | 6.138 |
| Complete timed pipeline | 42,041.290 | 37,602.559 | 118,260.443 | 117,365.438 |

| Kotlin whole-process metric | Main | Exact input |
|-----------------------------|-----:|------------:|
| Wall, s | 42.19 | 37.75 |
| CPU, s | 118.42 | 117.50 |
| Max RSS, bytes | 9,653,256,192 | 9,629,810,688 |
| Nodes | 4,744,132 | 4,744,132 |
| Callsites | 2,251,811 | 2,251,811 |

The unchanged main artifact itself entered the approximately 11 s save state
previously observed on Attempt 096's serial candidate. Long save is therefore
not serial-specific, and the prior two slow-serial/fast-main observations did
not demonstrate a causal serial regression. Conversely, main's long save
cannot be counted as an exact-input speedup: in this paired run exact-input
build was slower and consumed more CPU, and main has already demonstrated
approximately 6 s saves in earlier runs. Pipeline totals mix these unresolved
save states. Equal node/callsite counts alone do not establish complete
semantic equivalence; the standalone exact-input artifact was not given complete fingerprint verification. The later combined 099 chain passed complete mapped shape/metadata and typed enum comparisons; that is aggregate evidence, not retrospective standalone verification or a no-regression conclusion for this paired sample.

The exact launched commands and phase/resource logs are in
`results/{main,streaming-input-exact}-gate-tika-exact1` under
`/tmp/graphite-sootup-recovery.6wpE4s`, and
`/tmp/graphite-apk-recovery/results/{main,streaming-input-exact}-kotlin-default-exact1`.
The combined observed evidence is
`/tmp/graphite-sootup-recovery.6wpE4s/trial097-exact-paired-results.json`.
Gate/JMH configurations still disable call graph, annotations and cross-method
dispatch; the Kotlin pipeline above enables all defaults and prepared saving.
The shared-host environment is unchanged: Apple M3 Max, 16 CPUs, 64 GiB,
macOS 14.3 arm64, Homebrew OpenJDK 17.0.20.1. APK remains paused/deprioritized.

**Late integration scope correction:** static review of the installed JDK 17 source found that directory files and archive entries use different allocation paths. The frozen `streaming-input-exact` implementation also changed the ordinary directory path, where a stream read introduces extra chunk allocation/copying. On the default macOS provider, `newInputStream` wraps a file channel in `ChannelInputStream`, whose inherited `InputStream.readAllBytes` allocates 8 KiB chunks and copies a partial result (a stable 1,000-byte file allocates an 8,192-byte buffer and then a 1,000-byte result). `Files.readAllBytes` instead uses the known file size to fill the result array directly. ZipFS differs: its byte-channel path first materializes the entry, while direct entry input avoids that intermediate materialization. This is source-backed allocation-path analysis, not a measured directory latency/CPU/RSS improvement.

The final production source therefore keeps the existing directory reader and limits the exact stream read to the archive filesystem owned by `ParsedClassLocation`:

```kotlin
val bytes = if (fileSystem == null) {
    Files.readAllBytes(file)
} else {
    Files.newInputStream(file).use { it.readAllBytes() }
}
ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES)
```

Both branches supply exact-length bytes and retain malformed-input rejection. Existing real directory/JAR parity tests and the truncated-class/control-class test exercise both branches; their existence alone was not proof for the scoped artifact; the actual rerun evidence is recorded below. Final full-suite/lint validation is now complete as recorded below; additional real-corpus/Android checks and CI remain pending. All defective/repaired trial hashes, commands and numerical observations above remain unchanged: they measured their own frozen sources, including repaired stream reads for both directory and archive inputs, and must not be presented as measurements of this late scoped source. The real JAR reading branch remains the same; no directory performance result is inferred. Static evidence and exact JDK source references: `/tmp/sootup-static-review/attempt097-directory-read-note.txt` and `/tmp/sootup-static-review/jdk17-read-path/`.

**Late scope validation:** The directory-scoped integration artifact has now passed the complete 471-test suite (zero failures/errors/skips), detekt, JMH artifact build and recovery-classpath preparation. Frozen label `recovery-final` has runtime adapter JAR SHA-256 `666f2ff520b69fd96335e2a968651104555d898220a3c4a04f9fced25009f410`. `/tmp/sootup-recovery-sources/recovery-final-snapshot/test-proof.json` pins the aggregate result and individual test XML hashes; `test-results/` preserves the XML, including the existing directory/JAR parity and truncated-class tests, and `/tmp/sootup-recovery-sources/recovery-final-test.log` records the successful full build. `artifact-diff.json` confirms only ParsedClassLocation and its companion differ from frozen 100, with no added/removed classes. These are new-artifact validation results; no historical timing or hash is relabeled. Final three real-corpus gates and a separate Android correctness helper checking for OOM/skipped analysis remain pending, as does CI.

**Conclusion:** RETAIN the exact-input optimization with the archive-only integration scope described above as a component of the aggregate recovery chain, not standalone proof that the goal is fully met. The direct-to-ASM
`streaming-input` variant is rejected on correctness. The exact-input fix
restores the regression test's required behavior but is not, by itself,
evidence of performance recovery without other regressions. The corrected artifact has method-level and paired pipeline measurements,
but CPU/RSS recovery and absence of other regressions are not established.
Attempt 098 independently tests local serial parsing on this exact-input
base; its later results must not be attributed to this implementation alone.

Protocol clarification (recorded when Attempt 099 introduced a separate quiet harness): every ProductionPipeline full-default table in this attempt used the original verbose callback. Here "full-default" means full graph features (CHA, annotations and cross-method dispatch), not literally every LoaderConfig field or silent execution. Quiet full-feature results are recorded separately in Attempt 099 and cannot be mixed with these historical timings. The publishing-plugin workaround was identical in frozen snapshots; the five publishing-plugin declarations were later restored in the working tree.

### 2026-10-07 — Attempt 098: Exact-length stream input plus serial class parsing

**Status:** RETAINED as a component of the aggregate recovery chain with 095, corrected 097 and 099. This is not standalone proof that the recovery goal is fully met.

Frozen main produced a slow save (11.467s in exact1), disproving attribution of the earlier Attempt 096 slow-save observations solely to serial parsing. This is a new combination experiment; all prior samples remain retained.

**Change:** relative to streaming-input-exact (097 repaired), only files.parallelStream() -> files.stream() changes runtime behavior. Comments also updated. Exact-length input.readAllBytes() is retained. Snapshot artifact diff contains only ParsedClassLocation.class and ParsedClassLocation$Companion.class; no added/removed classes.
**Validation:** 460 tests, zero failures/errors/skips; detekt, jmhJar and recoveryClasspath all passed. No source edits by the benchmark agent. The immutable snapshot excludes later Attempt 099 edits.

Full-feature configuration WITH VERBOSE LOGGING: CHA call graph, annotations and cross-method dispatch enabled, prepared callsite index, Xmx8g. This is not every LoaderConfig field literally at default and not silent production. Existing ProductionPipeline.java and logs were unchanged. Any future silent experiment must use the same verbosity on both sides and remain separately identified.

Kotlin full-feature run order: 098 -> exact -> main. Times seconds, RSS decimal GB.

| Variant | Build wall | Build CPU | Save wall | Save CPU | Pipeline wall | Process CPU | RSS |
|---|---:|---:|---:|---:|---:|---:|---:|
| 098 | 30.927 | 88.976 | 11.194 | 17.736 | 42.662 | 107.92 | 8.377 |
| exact | 31.061 | 101.584 | 10.906 | 20.049 | 42.545 | 124.04 | 9.652 |
| main | 29.936 | 92.181 | 6.963 | 19.151 | 37.573 | 113.63 | 9.669 |

All three produced 4,744,132 nodes / 2,251,811 calls. Heavy semantic graph verification was not repeated. Preupgrade full-feature graph has different counts (4,657,648 / 2,173,010); its historical timing/RSS is NOT a like-for-like full-feature comparison.

Reduced 4g real-corpus gates: all nine passed. Orders: Tika old -> 098 -> main; Hive 098 -> main -> old; Kotlin main -> old -> 098. Times seconds, RSS decimal GB.

| Corpus | Variant | Build | Save | Pipeline | Process CPU | RSS |
|---|---|---:|---:|---:|---:|---:|
| tika | preupgrade | 12.659 | 4.474 | 18.585 | 69.68 | 5.182 |
| tika | 098 | 12.794 | 4.387 | 18.541 | 71.84 | 5.182 |
| tika | main | 12.840 | 4.651 | 18.859 | 77.15 | 5.026 |
| hive | preupgrade | 23.475 | 7.008 | 32.092 | 104.67 | 5.229 |
| hive | 098 | 22.978 | 6.280 | 30.941 | 108.27 | 5.233 |
| hive | main | 23.392 | 7.063 | 32.126 | 123.75 | 5.340 |
| kotlin | preupgrade | 12.434 | 8.024 | 21.652 | 71.62 | 5.180 |
| kotlin | 098 | 12.754 | 4.338 | 18.326 | 66.68 | 5.151 |
| kotlin | main | 12.389 | 4.126 | 17.695 | 78.05 | 5.162 |

**Interpretation:** 098 lowers whole-process CPU versus main in all three reduced gates. Tika/Hive CPU remains about 3% above old; Kotlin build is about 3% slower than main. Full-feature 098 CPU/RSS is lower than paired main, but build is 3.31% slower and total wall includes a retained slow-save tail. No single tail is attributed to code. Retained with the aggregate chain despite these explicit costs; not evidence of uniform standalone improvement.

Slow-save evidence across variants, unprofiled Kotlin full-feature runs: main exact1 11.467s; repaired exact exactserial1 10.906s; 098 exactserial1 11.194s; rejected serial096 serial1/serial2 11.210/11.333s. Every other unprofiled full-feature Kotlin sample is 6.219–6.963s. Main thus has both slow and fast samples. Diagnostic startup-JFR and plain Thread.print runs were fast; Thread.print -l induced 25.778–26.945s save and is excluded from performance conclusions. Late-only diagnostic runs were fast with zero attach. All samples are retained in all-kotlin-default-save-samples.json.
Reduced Kotlin gates also have slow save: old comparison2 7.922s, serial1 7.887s, serial2 7.898s, exactserial1 8.024s; streaming global-pool diagnostic pool1-1 7.989s. These are separate from full-feature runs; no sample was removed.

At the initial 098 record, method-level JMH allocation/latency had not been measured. Subsequent evidence is recorded without overwriting the verbose table: Attempt 099 includes a separate identical-quiet-protocol run for 098 and two cold GraphBuildBenchmark Kotlin JMH forks (12,223.136209 / 12,397.823583 ms; allocation 23,021,083,832 / 23,045,759,424 B/op). These are actual later measurements, not inferred from the JMH jar build. Android JAR JMH, APK and full graph semantic verification remain unmeasured for this individual 098 snapshot. The final combined chain is evaluated separately.

**Evidence:**

- Snapshot: /tmp/sootup-recovery-sources/streaming-input-exact-serial-snapshot (source-state.json, artifact-diff.json, classpath.txt, build.log). Sootup jar SHA256 c6faf95218c89d6fe352d2b82bcf72084b0cb39999664217c08a9fd49879770c.
- Build: /tmp/sootup-recovery-sources/streaming-input-exact-serial-test.log.
- Manifest: /tmp/graphite-sootup-recovery.6wpE4s/streaming-input-exact-serial.json.
- All phase CPU/heap/GC metrics and RSS: /tmp/graphite-sootup-recovery.6wpE4s/trial098-results.json.
- Raw gates: /tmp/graphite-sootup-recovery.6wpE4s/results/*-gate-*-exactserial1/.
- Raw full-feature runs and graphs: /tmp/graphite-apk-recovery/results/*-kotlin-default-exactserial1/.
- Slow-save indexes: /tmp/graphite-sootup-recovery.6wpE4s/all-kotlin-default-save-samples.json and all-kotlin-gate-slow-save-samples.json.

Local Apple M3 Max machine is not dedicated; background activity and cold-fork/JIT variance remain. Single rounds establish boundaries, not a statistical no-regression guarantee.

**Final-source provenance:** the final integration source includes Attempt 097's later directory-path preservation: `fileSystem == null` keeps `Files.readAllBytes`, while archive entries use the exact input-stream read. This scope refinement occurred after this trial's frozen artifact and measurements. The hash-pinned results above still describe that original frozen source, not a validated or measured new artifact. No directory performance improvement is claimed; new-artifact full-suite/lint passed with 471 tests under recovery-final; the remaining real-corpus/Android checks and CI are pending (see Attempt 097 validation proof). See Attempt 097 and `/tmp/sootup-static-review/attempt097-directory-read-note.txt`.

**Conclusion:** RETAINED in the aggregate recovery chain. The later 099 correctness checks and broader real-data results support continuing with that chain; all original 098 build/latency/CPU/RSS costs and slow-save samples above remain part of the decision. Overall recovery is not yet fully achieved.

### 2026-10-07 — Attempt 099: Index enum constructor expressions once per class

**Status:** RETAINED as a component of the aggregate recovery chain and as the parent candidate for independent Attempt 100. The overall recovery objective is not yet fully achieved. Final retry2 passed all 464 tests (460 existing + 4 new), detekt, JMH artifact build and recovery classpath preparation. The first two build rounds failed only lint; equivalent extraction of trackEnumLocalValue and a combined field short-circuit condition resolved those findings without changing baseline thresholds or enum matching/value semantics. Candidate whole-enum in-memory parity, the first quiet full-feature Kotlin round and two-fork Kotlin JMH have completed as detailed below. Final nine real-corpus gates, Android JAR JMH and complete Kotlin mapped shape/metadata comparisons have now completed; results and remaining performance costs are retained below.

**Hypothesis:** extractEnumValues repeatedly scans the same complete <clinit> CFG for each field. With N enum fields and O(N) initialization statements, this causes O(N²) statement visits. Build a temporary base.name -> first eligible constructor-expression index in one pass, then retain the original assignment/value pass. Lookup avoids rescanning; argument conversion still happens at each field assignment. This is an independent enum extraction change layered on the frozen input/parsing chain, with no changes to 095 wrappers or loader feature flags.

**Compatibility:** retain only JInvokeStmt containing AbstractInstanceInvokeExpr named <init> with more than two arguments. Keep the first match in full CFG iteration order, even when it occurs after a field assignment. Key by base.name, with no new owner, invocation-kind, field-name or enum-constant filters. Resolve raw argument expressions using each field's then-current localValues and existing flattened aliases. Preserve unknown/null values, all-null nonempty lists, short-constructor absence, boxing representations and enum references. Verbose configuration is unchanged; scan diagnostics naturally occur once per indexing pass instead of per field rescan.

**Tests passed in the final retry2:** four controlled-IR correctness tests cover first eligible/duplicate/short constructors, ignored statement forms versus accepted virtual/foreign-owner constructors, same-name different-type locals, later CFG matches, per-field argument snapshots, copied aliases and rebinding, unknown/all-null values, unfiltered $VALUES, boxing and enum references. A two-iterator assertion is a structural source-access guard only, not performance evidence. Seven adjacent compiled-fixture tests now assert complete lists for every enum constant, including JVM Int representations of boolean/char and explicit numeric wrapper types.

**Real-input evidence:** pinned Kotlin compiler embeddable 2.0.21 contains InfoCmp$Capability with 464 enum fields and only (String,int) constructor parameters. The retained main verbose Kotlin log contains 215,760 scans for this class (464 invokes x 465 lookups, including $VALUES), among 340,362 Checking invoke lines / 704,690 total lines. Tika app 2.9.2 contains ShapeType with 246 enum fields and (String,int,int,int,String) constructor parameters. These are actual class-byte/log observations, not a measured speedup. Evidence: /tmp/graphite-apk-recovery/results/main-kotlin-default-streaming1/stderr-time.log and verification query sidecars below.

**Supplementary correctness scope:** EnumValuesVerifier independently enumerates every root ACC_ENUM field in each pinned jar: Kotlin 582 enum classes / 4,557 keys; Tika 1,240 / 9,686. It compares complete typed rows, preserving absent/null versus empty, null elements, parameter order, wrapper types, raw floating bits, UTF-16 strings and enum reference identity; unknown types fail. Concrete bytecode-derived oracles require all 464 Capability results null and all 246 in-memory ShapeType results present, with exact sample values (NOT_PRIMITIVE, LINE, LINE_INV, RECT). Both full-feature in-memory and persisted APIs must be compared across revisions. GraphStore persists enumValues only for EnumConstant node keys, so matching persisted reports cannot prove equality for values omitted from both graphs. The inventory covers real enum field keys, not arbitrary static fields or synthetic $VALUES entries; unit tests cover those extraction boundaries. This is separate correctness work, excluded from timing.

**Completed in-memory enum parity:** main full-feature in-memory Kotlin queried 4,557 keys, with 2,386 present values; Tika queried 9,686 keys, with 6,577 present values. Both passed their concrete class-byte-derived assertions, including all 246 ShapeType values. The baseline persisted Kotlin graph returned absent for all 4,557 keys; this demonstrates the persistence coverage limitation and cannot establish candidate correctness. Candidate 099 then matched main for all 14,243 queried keys across Kotlin and Tika: every typed payload and every report property is identical, with ENUM_VALUES_PARITY_OK for both comparisons. Concrete oracles passed on both sides. This proves the stated in-memory enum API coverage, not whole-graph equivalence; persisted absence is not substituted for this evidence.

**Snapshot scope:** compared with frozen 098, enum implementation and SootUpAdapter-generated classes changed. Three additional inline-containing classes (ControlFlowIndex, SlotPropagation, SlotPropagation$DepthFirstSearch) had byte differences from line/debug metadata changes; independent javap -p -c comparisons report identical executable code for all three. This evidence is stored in additional-class-code-verification.json and adjacent code diffs, not inferred from source alone.

**New quiet full-feature protocol:** /tmp/graphite-jar-quiet-recovery/ProductionPipeline.java changes only the verbose callback to null. CHA call graph, annotations, cross-method dispatch, phase instrumentation, save options and existing verification remain unchanged. /tmp/graphite-jar-quiet-recovery/protocol.json pins parent SHA-256 6552cb9c4afbb2d28c1d47f64e090eb1044764486816a727fab744df52cb18dc and quiet SHA-256 1317041bdc5875e0d8cee1c72386837752a96af69a72f5093cd0184a50dfe799. Main, preupgrade, frozen 098 and candidate 099 completed one round using this identical quiet protocol. Historical verbose samples stay separately labeled and must not be mixed into quiet timing comparisons. All phase wall/CPU, heap/GC and process RSS results are retained, including slow-save tails; no best-sample selection. Candidate frozen as streaming-input-exact-serial-enum; runtime sootup jar SHA-256 ff0bffaf3342f963c2373bf4add378e21495d39479dfb6562b3821c2c6b03264. Exact launched commands are retained in each results directory command.json; complete quiet and JMH observations follow. Local Apple M3 Max / JDK 17 environment is shared, not a dedicated performance runner.

Quiet full-feature Kotlin observations (one run each, Xmx8g; order pre-upgrade -> 099 -> main -> 098). These are separate from every historical verbose table. The protocol file's prepared-state compiled/measured booleans remain false; successful command exit codes, logged source hashes and result artifacts establish actual execution.

| Phase | Pre-upgrade wall / CPU ms | Main wall / CPU ms | 098 wall / CPU ms | 099 wall / CPU ms |
|---|---:|---:|---:|---:|
| build | 106,909.469 / 181,726.719 | 28,814.205 / 93,715.345 | 30,064.691 / 82,445.437 | 29,130.671 / 84,063.038 |
| cliNodeCount | 335.503 / 1,088.376 | 381.806 / 1,827.214 | 341.397 / 1,242.253 | 350.445 / 1,382.082 |
| savePrepared | 6,736.092 / 17,534.410 | 6,497.599 / 15,750.560 | 11,265.188 / 22,882.178 | 6,464.720 / 18,215.415 |
| closeSource | 0.046 / 0.050 | 0.040 / 0.046 | 0.041 / 0.044 | 0.074 / 0.077 |
| loadMapped | 155.898 / 281.851 | 162.007 / 269.211 | 160.267 / 276.747 | 158.307 / 280.095 |
| queryAllNodeCount | 39.909 / 131.254 | 36.366 / 115.956 | 35.206 / 76.567 | 35.250 / 77.054 |
| queryCallSiteCount | 1.016 / 2.655 | 0.813 / 3.822 | 0.877 / 1.540 | 0.829 / 1.811 |
| Timed pipeline | 114,186.684 / 200,800.421 | 35,902.414 / 111,740.130 | 41,876.315 / 106,956.622 | 36,149.129 / 104,043.426 |

| Metric | Pre-upgrade | Main | 098 | 099 |
|---|---:|---:|---:|---:|
| /usr/bin/time wall seconds | 114.32 | 36.05 | 42.03 | 36.30 |
| /usr/bin/time CPU seconds | 200.90 | 111.87 | 107.09 | 104.20 |
| Max RSS bytes | 8,622,342,144 | 9,398,026,240 | 9,159,147,520 | 9,090,891,776 |
| Build sum of heap-pool peaks bytes | 7,408,448,000 | 7,572,717,568 | 7,721,615,360 | 7,704,838,144 |
| Build GC count / milliseconds | 98 / 2089 | 42 / 1061 | 44 / 1063 | 44 / 1033 |
| Nodes / callsites | 4657648 / 2173010 | 4744132 / 2251811 | 4744132 / 2251811 | 4744132 / 2251811 |

**Interpretation:** relative to main, 099 build wall is slightly slower (29.131 vs 28.814 s), while build CPU and whole-process CPU/RSS are lower in this observation. Relative to 098, 099 build wall is lower but build CPU is higher (84.063 vs 82.445 s); this is not uniform enum-index improvement. The 098 11.265 s save tail is retained and cannot be credited as an enum-index speedup, because main and other variants have also exhibited slow saves. Pre-upgrade has different graph counts, so its much longer full-feature build is not a like-for-like semantics/performance comparison; 099 RSS is still above its observation. Full phase heap/GC metrics remain in trial099-quiet-results.json.

Two-cold-fork method-level JMH, GraphBuildBenchmark.buildKotlinCompilerGraphEndToEndConfig: -bm ss -tu ms -wi 0 -i 1 -f 2 -t 1 -foe true -jvmArgs '-Xmx8g' -prof gc. This EndToEndConfig disables call graph, annotations and cross-method dispatch; it is not the quiet full-feature pipeline. Old/main rows are retained coldstreaming1 observations; 097 is coldexact1; 098/099 are coldenum1. They were not all run contemporaneously.

| Snapshot | Fork 1 ms/op | Fork 2 ms/op | Mean ms/op | Fork 1 allocation B/op | Fork 2 allocation B/op | Mean allocation B/op |
|---|---:|---:|---:|---:|---:|---:|
| Pre-upgrade | 12,370.715 | 12,492.235 | 12,431.475 | 23,127,877,248 | 23,127,135,840 | 23,127,506,544 |
| Main | 12,207.976 | 12,115.024 | 12,161.500 | 23,884,889,712 | 23,885,959,184 | 23,885,424,448 |
| 097 exact | 11,585.382 | 11,611.037 | 11,598.210 | 23,063,362,968 | 23,071,057,928 | 23,067,210,448 |
| 098 | 12,223.136 | 12,397.824 | 12,310.480 | 23,021,083,832 | 23,045,759,424 | 23,033,421,628 |
| 099 | 12,609.881 | 12,302.463 | 12,456.172 | 22,979,474,736 | 22,968,526,640 | 22,974,000,688 |

099 is +0.20% time and -0.664% allocation versus pre-upgrade, near that old reduced-config baseline; versus main it is +2.42% time and -3.82% allocation. The observed 099 fork interval overlaps old and 098, but does NOT overlap the saved main fork interval. The main latency point estimate is worse; two cold forks collected at different times are insufficient to establish a stable regression or its absence. Relative to 098, allocation falls by 59,420,940 B/op (0.258%) while time rises 1.18%; both outcomes are retained. Real end-to-end gates and CI comparisons must complement this narrow sample.

**Reproduction/evidence:**

- Quiet wrapper: python3 /tmp/graphite-jar-quiet-recovery/run.py run LABEL KOTLIN_JAR LABEL-kotlin-quiet-enum1 --heap 8g --sdk - (labels in recorded order above, pinned Kotlin fixture from each manifest).
- New JMH: python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py jmh LABEL --run coldenum1 --forks 2 --corpus kotlin (098 and 099 labels).
- /tmp/graphite-sootup-recovery.6wpE4s/trial099-quiet-results.json and trial099-kotlin-jmh-comparison.json contain all observations and per-fork sources.
- /tmp/graphite-jar-quiet-recovery/results/*-kotlin-quiet-enum1/command.json pins the full JVM command, fixture hash, runtime manifest, environment and source hash; no extra JVM flags. stderr-time.log and phases.csv retain raw measurements.
- Publishing-plugin workaround remains recorded in immutable historical snapshot source states; the five build-script plugin declarations have since been restored in the working tree. They are not part of the proposed production optimization.

**Evidence and verifier commands:**

- /tmp/sootup-recovery-sources/streaming-input-exact-serial-enum-test{,-retry1,-retry2}.log (initial lint failures retained; final retry2 success).
- /tmp/sootup-recovery-sources/streaming-input-exact-serial-enum-snapshot/{artifact-diff.json,additional-class-code-verification.json,source-state.json,build.log} and /tmp/graphite-sootup-recovery.6wpE4s/streaming-input-exact-serial-enum.json.
- /tmp/graphite-apk-recovery/verification/reports/099-{kotlin,tika}-memory-enum1-compare.log (empty typed-value/property differences; both parity markers present).
- /tmp/graphite-apk-recovery/verification/reports/main-{kotlin-memory,tika-memory,kotlin-persisted}-baseline1.{command.json,log,properties,values.tsv}.
- Source/test: frontend/jvm/sootup/src/{main,test}/kotlin/io/johnsonlee/graphite/sootup/{SootUpAdapter.kt,SootUpAdapterTest.kt,EnumConstructorIndexTest.kt} (respective source sets).
- /tmp/graphite-apk-recovery/verification/ENUM-VALUES-README.md, EnumValuesVerifier.java, run-enum-values.py, enum_values_queries.py and {kotlin,tika}-enum-queries.tsv{,.json}.
- /tmp/sootup-recovery-sources/attempt099-enum-parity-plan.txt (initial focused plan; whole-inventory verifier supersedes its narrower query scope).
- python3 /tmp/graphite-apk-recovery/verification/run-enum-values.py run "$MANIFEST" kotlin memory "$REPORT_PREFIX" --execute (repeat tika, base/candidate; coordinator only).
- python3 /tmp/graphite-apk-recovery/verification/run-enum-values.py run "$MANIFEST" kotlin persisted "$REPORT_PREFIX" --graph "$GRAPH" --execute (repeat tika/base/candidate; compare matching modes).
- python3 /tmp/graphite-apk-recovery/verification/run-enum-values.py compare "$BASE_REPORT_PREFIX" "$CANDIDATE_REPORT_PREFIX".

Final real-corpus reduced gates: all nine passed, retaining graph/query/branch-definition assertions with large.corpus.record=true (timing ceilings disabled). Call graph, annotations and cross-method functional dispatch are disabled; these are separate from the quiet full-feature results. Each is a fresh Xmx4g JVM. Whole-process wall/CPU/RSS includes verification and cleanup. All runs are listed, not selected best samples; execution commands and timestamps are in each result directory.

| Corpus / variant | Build ms | Save ms | Mapped load median ms | Query ms | Pipeline ms | Process wall s | Process CPU s | Peak heap bytes | Max RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| tika / Pre-upgrade | 12,560 | 4,493 | 90 | 1,236 | 18,379 | 21.60 | 65.99 | 3,555,663,424 | 5,182,259,200 |
| tika / Main | 12,867 | 4,388 | 91 | 1,283 | 18,629 | 22.09 | 78.87 | 3,827,204,096 | 5,038,784,512 |
| tika / 099 | 13,012 | 4,421 | 88 | 1,129 | 18,650 | 22.02 | 72.82 | 3,764,289,592 | 5,024,890,880 |
| hive / Pre-upgrade | 21,729 | 6,023 | 133 | 1,453 | 29,338 | 33.91 | 97.87 | 3,916,306,432 | 5,248,352,256 |
| hive / Main | 23,614 | 7,051 | 135 | 1,549 | 32,349 | 37.15 | 127.45 | 4,072,237,080 | 5,220,466,688 |
| hive / 099 | 23,643 | 6,066 | 136 | 1,697 | 31,542 | 36.49 | 111.83 | 3,889,565,184 | 5,412,978,688 |
| kotlin / Pre-upgrade | 12,492 | 7,734 | 83 | 1,228 | 21,537 | 24.75 | 70.83 | 3,671,084,608 | 5,009,014,784 |
| kotlin / Main | 12,519 | 4,251 | 76 | 1,123 | 17,969 | 21.18 | 75.98 | 3,861,575,568 | 4,962,549,760 |
| kotlin / 099 | 12,721 | 4,242 | 76 | 1,097 | 18,136 | 21.36 | 69.16 | 3,965,430,272 | 5,144,313,856 |

Against main, 099 build is slower on all three (Tika +145 ms, Hive +29 ms, Kotlin +202 ms), total CPU is lower on all three, and RSS is higher on Hive/Kotlin but slightly lower on Tika. Against pre-upgrade, build is slower on all three; Tika/Hive CPU is still higher and Hive/Kotlin RSS is higher. The old Kotlin 7,734 ms save is another retained tail, not an enum-index speedup. The 099 Hive query is 1,697 ms versus main 1,549 and old 1,453; neither it nor the 099 Tika/Kotlin pipeline costs versus main are concealed by CPU improvements.

Android JAR method-level JMH (not APK): pinned Robolectric android-all 14-robolectric-10818077, Xmx8g, -bm ss -tu ms -wi 0 -i 1 -f 2 -t 1 -foe true -prof gc, same fixture properties as the other JMH runs. buildAndroidSdkGraph disables call graph but retains annotations and cross-method dispatch; buildAndroidSdkGraphEndToEndConfig disables all three. Neither method measures save/mapped-load/query. Keep these configurations separate.

| Benchmark / variant | Fork 1 ms/op | Fork 2 ms/op | Mean ms/op | Fork 1 allocation B/op | Fork 2 allocation B/op | Mean allocation B/op | GC count / ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| android / Pre-upgrade | 28,629.642 | 28,640.517 | 28,635.079 | 44,475,183,456 | 44,491,852,464 | 44,483,517,960 | 92 / 2035 |
| android / Main | 23,535.993 | 23,903.208 | 23,719.600 | 41,016,551,000 | 41,099,011,784 | 41,057,781,392 | 84 / 1971 |
| android / 099 | 24,193.905 | 24,128.117 | 24,161.011 | 40,361,858,960 | 40,327,963,176 | 40,344,911,068 | 86 / 1930 |
| android-e2e / Pre-upgrade | 19,204.356 | 18,840.371 | 19,022.364 | 35,020,249,072 | 35,098,401,576 | 35,059,325,324 | 80 / 1574 |
| android-e2e / Main | 19,446.630 | 19,253.428 | 19,350.029 | 36,895,777,744 | 36,842,948,272 | 36,869,363,008 | 78 / 1764 |
| android-e2e / 099 | 19,501.166 | 19,460.138 | 19,480.652 | 35,947,038,872 | 35,944,017,848 | 35,945,528,360 | 80 / 1711 |

Android comparisons (099 point estimates; two cold forks):

- android versus Pre-upgrade: time -15.62%, allocation -9.30%.
- android versus Main: time +1.86%, allocation -1.74%.
- android-e2e versus Pre-upgrade: time +2.41%, allocation +2.53%.
- android-e2e versus Main: time +0.68%, allocation -2.51%.

Both Android methods are slower than main in this sample despite lower allocation. In reduced Android EndToEndConfig, 099 remains slower and allocates more than pre-upgrade; in annotations/dispatch-enabled Android it beats pre-upgrade on both. These different baselines/configurations must not be collapsed into one recovery percentage.

Complete Kotlin mapped verification: main and 099 quiet full-feature graphs have identical values across all 22 structural fingerprint properties and all 13 supplementary metadata properties (methods/annotations, synthetic identity, origins/dependencies and comparand-node connectivity). The shape comparison differences and metadata-differences.json are both empty. Evidence: /tmp/graphite-jar-quiet-recovery/verification-enum1/{shape-compare.log,metadata-differences.json} plus the four successful per-variant verifier logs and command.json records. This complements the separate 14,243-key in-memory enum check; it does not prove every untested resource/query API or old/new full-feature equivalence.

**Final commands/evidence:**

- python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py gate LABEL --run enum1 --corpus CORPUS (labels old/main/099 and all Tika/Hive/Kotlin corpora; actual preupgrade and frozen candidate labels in each command.json).
- python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py jmh LABEL --run coldenum1 --forks 2 --corpus android (repeat android-e2e; each of preupgrade/main/streaming-input-exact-serial-enum).
- trial099-nine-gates.json and trial099-android-jmh-comparison.json under /tmp/graphite-sootup-recovery.6wpE4s preserve every result and per-fork source path; results/*-gate-*-enum1 and *-jmh-android*-coldenum1 contain exact launched commands, environment and raw output.

**Final-source provenance:** the final integration source includes Attempt 097's later directory-path preservation: `fileSystem == null` keeps `Files.readAllBytes`, while archive entries use the exact input-stream read. This scope refinement occurred after this trial's frozen artifact and measurements. The hash-pinned results above still describe that original frozen source, not a validated or measured new artifact. No directory performance improvement is claimed; new-artifact full-suite/lint passed with 471 tests under recovery-final; the remaining real-corpus/Android checks and CI are pending (see Attempt 097 validation proof). See Attempt 097 and `/tmp/sootup-static-review/attempt097-directory-read-note.txt`.

**Decision:** RETAINED with 095, corrected 097 and 098 as the aggregate recovery chain and Attempt 100's parent candidate; this is not standalone proof that the overall recovery goal is achieved. The chain lowers allocation and some CPU observations but does not uniformly restore latency, CPU or RSS relative to main or pre-upgrade. All final local checks above completed; shared-host samples and this API coverage do not establish universal no-regression. APK remains paused/deprioritized.

### 2026-10-07 — Attempt 100: Cache raw class-name owner lookups per Java view

**Status:** RETAINED as a component of the aggregate recovery chain; the overall user goal is NOT yet fully met. Final retry2 build passed tests, detekt and JMH artifact preparation; test XML totals are 471 tests, zero failures/errors/skips. First paired JMH and all eleven gate observations below have completed. Quiet full-feature measurements below have also completed; the independent semantic comparisons below also passed. Frozen Android JMH and the separate Tika diagnostic pair are complete; the final directory-scoped artifact passed its full build as recorded below; real-corpus/Android follow-ups and CI remain pending. Parent is the frozen Attempt 099 chain (streaming-input-exact-serial-enum, adapter JAR SHA-256 ff0bffaf3342f963c2373bf4add378e21495d39479dfb6562b3821c2c6b03264). Candidate frozen label: streaming-input-exact-serial-enum-typecache; runtime adapter JAR SHA-256 cc0e2531b257fefa6fa74d3362e325973149975068290e31312c86890cf3fbcc. Frozen source and artifact scope are retained in its snapshot directory.

**Hypothesis:** repeated one-argument JavaIdentifierFactory.getClassType(rawName) requests from one parsed-class Java view repeatedly enter SootUp's singleton lookup path. A view-owned ConcurrentHashMap can reuse the exact canonical JavaClassType returned by that singleton for warm raw-name requests, avoiding some repeated owner-name parsing/cache work. This is independent of enum constructor indexing and the earlier input/streaming changes.

**Implementation boundary:** create GraphiteJavaView only when every input location is ParsedClassLocation; otherwise create the original JavaView. APK/DEX and mixed/nonparsed-location inputs retain that fallback. Each optimized view gets its own GraphiteIdentifierFactory. The cache keys the original unnormalized class-name string and uses get followed by delegate lookup plus putIfAbsent on misses. Misses still use the original JavaIdentifierFactory singleton. Racing misses may call the delegate more than once but publish the existing canonical value. No exception is cached; retries delegate again. Null bypasses ConcurrentHashMap and follows the singleton's original behavior. The two-argument getClassType(className, packageName) and getPackageName calls delegate without caching or replacing their corresponding original objects. Default primitive/array/type parsing remains inherited; fresh array instances and invalid descriptor behavior must remain unchanged.

**Coverage limit:** this does not replace static AsmUtil descriptor conversion, singleton getType calls inside that path, or independent annotation parsing. Allocation samples attributed to that broader path cannot all be credited to this owner-name cache; in particular, no claim that it eliminates the whole sampled 3.16 GB is justified. It stores O(U) strong entries for U distinct raw names requested through that view, retained until the view/factory becomes unreachable. Allocation saved on repeated hits must be assessed against added map/key/value retention and synchronization overhead. There is no global cache, eviction policy or normalization change.

Seven new test methods passed, with concrete contracts across these cases:

- Singleton owner/package/nested-name identity, corresponding two-argument identity, and method-signature owner/return/parameter shape.
- Unusual raw names and null delegate unchanged; successful warm calls avoid redelegation; a failed request retries successfully without cached failure.
- Malformed descriptor failures retain exception messages; repeated arrays are equal but distinct instances with canonical base type and exact dimension; primitive/null/void behavior remains.
- Concurrent first lookups all publish the canonical object; warm concurrent reads stop delegating, without assuming only one delegate call during a race.
- Independent factories keep independent strong lookup maps while still returning canonical singleton values.
- Separate parsed-class views receive separate factory instances and preserve intended Java view/loading behavior.
- Nonparsed/mixed inputs use the original JavaView/factory path (APK fallback is preserved by this input-location gate, not claimed as a new APK run).

**Source scope:** frontend/jvm/sootup/src/main/kotlin/sootup/java/core/GraphiteIdentifierFactory.kt, io/johnsonlee/graphite/sootup/GraphiteJavaView.kt and JavaProjectLoader's view construction; test counterparts GraphiteIdentifierFactoryTest.kt and GraphiteJavaViewTest.kt. No feature flags are disabled. APK remains paused/deprioritized.

**Evidence scope:** immutable snapshot under /tmp/sootup-recovery-sources/streaming-input-exact-serial-enum-typecache-snapshot; artifact-diff.json records the three new view/factory classes and JavaProjectLoader changes. Four additional inline-containing classes differ in line/debug metadata; additional-class-code-verification.json records identical javap executable code. Final build log is streaming-input-exact-serial-enum-typecache-test-retry2.log, alongside the preserved earlier failed logs. All measured inputs remain pinned real jars; source-access test assertions are not performance evidence.

**First paired Kotlin JMH:** GraphBuildBenchmark.buildKotlinCompilerGraphEndToEndConfig, two cold forks, -bm ss -tu ms -wi 0 -i 1 -f 2 -t 1 -foe true -jvmArgs '-Xmx8g' -prof gc. Candidate 100 ran before the refreshed 099 comparison. Call graph, annotations and cross-method dispatch are disabled by this benchmark configuration. Compare this fresh 099 sample separately from its historical coldenum1 score.

| Variant | Fork 1 ms/op | Fork 2 ms/op | Mean ms/op | Fork 1 allocation B/op | Fork 2 allocation B/op | Mean allocation B/op |
|---|---:|---:|---:|---:|---:|---:|
| 099 | 12,378.709 | 12,397.491 | 12,388.100 | 22,962,744,432 | 22,973,792,768 | 22,968,268,600 |
| 100 | 11,924.991 | 12,232.245 | 12,078.618 | 21,919,356,600 | 21,921,724,376 | 21,920,540,488 |

100 is -2.50% time and -4.56% allocation versus this paired 099 estimate, a reduction of 1,047,728,112 B/op. This observed allocation reduction is about 1.05 GB/op, not the complete 3.16 GB sampled allocation path. Two cold forks remain a limited sample; this result does not establish full-feature or whole-process CPU/RSS recovery.

**Real reduced gates:** all eleven completed runs passed their graph/query/branch-definition assertions. Each uses a fresh Xmx4g JVM and large.corpus.record=true (timing ceilings disabled), with call graph, annotations and cross-method dispatch disabled. CPU/RSS include verification/cleanup. Initial Hive order was 099 -> 100 -> old; reverse Hive was 100 -> 099. Expanded Tika/Kotlin compare main, old and 100; there is no contemporaneous 099 row for those two corpora. Do not substitute historical 099 observations as paired measurements. All samples are retained:

| Round / corpus / variant | Build ms | Save ms | Mapped load median ms | Query ms | Pipeline ms | Process wall s | Process CPU s | Peak heap bytes | Max RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| typecache1 / hive / 099 | 23,441 | 7,007 | 135 | 1,710 | 32,293 | 36.95 | 109.30 | 3,897,905,184 | 5,206,638,592 |
| typecache1 / hive / 100 | 22,568 | 5,957 | 137 | 1,577 | 30,239 | 35.22 | 111.64 | 3,884,437,520 | 5,428,576,256 |
| typecache1 / hive / old | 23,358 | 6,141 | 134 | 1,585 | 31,218 | 35.81 | 105.26 | 3,866,714,456 | 5,254,709,248 |
| typecache2 / hive / 100 | 22,630 | 6,301 | 136 | 1,539 | 30,606 | 35.65 | 113.28 | 4,091,143,680 | 5,169,790,976 |
| typecache2 / hive / 099 | 23,388 | 7,120 | 135 | 1,591 | 32,234 | 37.00 | 109.97 | 3,893,718,560 | 5,202,395,136 |
| typecache2 / tika / main | 13,006 | 4,611 | 88 | 1,298 | 19,003 | 22.73 | 85.61 | 3,850,892,808 | 5,183,946,752 |
| typecache2 / tika / old | 12,770 | 4,521 | 87 | 1,223 | 18,601 | 22.02 | 68.05 | 3,654,500,848 | 5,179,801,600 |
| typecache2 / tika / 100 | 12,737 | 4,620 | 90 | 1,321 | 18,768 | 22.31 | 72.20 | 3,816,718,336 | 5,016,666,112 |
| typecache2 / kotlin / main | 12,153 | 4,227 | 76 | 1,126 | 17,582 | 20.95 | 73.10 | 3,863,692,312 | 5,119,787,008 |
| typecache2 / kotlin / old | 12,472 | 4,180 | 74 | 1,136 | 17,862 | 21.17 | 66.77 | 3,641,970,864 | 5,175,984,128 |
| typecache2 / kotlin / 100 | 12,281 | 4,629 | 76 | 1,099 | 18,085 | 21.42 | 68.20 | 3,822,279,760 | 5,150,539,776 |

Hive tradeoff repeats in both orders: 100 builds/saves faster, but CPU rises from 109.30 to 111.64 s (+2.14%) initially and 109.97 to 113.28 s (+3.01%) in reverse order. Initial RSS rises 5,206,638,592 -> 5,428,576,256 bytes; reverse RSS falls 5,202,395,136 -> 5,169,790,976 bytes. This reversal prevents a stable cache-RSS attribution from either single round. Reverse 100 sampled peak heap is also higher than the initial 100 sample; none is discarded. Initial 100 CPU remains above old 105.26 s despite faster wall/build.

Tika 100 build is slightly faster than both main and old, but total CPU is 72.20 s versus old 68.05 (main 85.61), and save/load/query are slower than old. Kotlin expansion completed while this draft was being updated: 100 build is 12.281 s versus main 12.153 and old 12.472; CPU is 68.20 s versus main 73.10 and old 66.77; its 4.629 s save produces a slower pipeline than both baselines. RSS and sampled heap differ across variants, and no uniform recovery is asserted. Completed Kotlin data is therefore included rather than left as stale PENDING.

**Commands/evidence:**

- python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py jmh LABEL --run coldtypecache1 --forks 2 --corpus kotlin (100 then 099).
- python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py gate LABEL --run typecache1 --corpus hive (099 -> 100 -> preupgrade).
- python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py gate LABEL --run typecache2 --corpus CORPUS (reverse Hive and expanded Tika/Kotlin; exact commands/timestamps in results/*-gate-*-typecache2/command.json).
- /tmp/graphite-sootup-recovery.6wpE4s/trial100-kotlin-jmh-comparison.json, trial100-hive-gates.json and trial100-expanded-gates.json preserve all point/fork/phase results and original run identifiers. Matching results directories retain command.json, stdout.log and stderr-time.log.
- Shared Apple M3 Max / Homebrew JDK17 host and pinned fixture manifests match the prior trials; no synthetic performance inputs.

**Quiet full-feature Kotlin follow-up:** one fresh Xmx8g run per variant in order 099 -> 100 -> main, using the identical verbose=null ProductionPipeline source. CHA call graph, annotations, cross-method dispatch, prepared-index saving and phase instrumentation remain enabled. This is separate from reduced gates/JMH and historical verbose runs.

| Phase | 099 wall / CPU ms | 100 wall / CPU ms | Main wall / CPU ms |
|---|---:|---:|---:|
| build | 30,133.976 / 86,472.302 | 29,128.472 / 89,339.874 | 28,815.264 / 94,436.477 |
| cliNodeCount | 385.444 / 656.391 | 312.021 / 574.432 | 399.300 / 1,888.180 |
| savePrepared | 6,840.431 / 19,542.368 | 11,278.463 / 18,367.651 | 6,540.091 / 16,051.281 |
| closeSource | 0.042 / 0.044 | 0.043 / 0.107 | 0.059 / 0.104 |
| loadMapped | 158.895 / 297.024 | 161.070 / 283.998 | 160.354 / 296.983 |
| queryAllNodeCount | 39.179 / 137.488 | 35.985 / 82.232 | 36.186 / 95.652 |
| queryCallSiteCount | 0.976 / 3.190 | 0.804 / 2.095 | 0.824 / 1.994 |
| Timed pipeline | 37,567.918 / 107,126.935 | 40,925.061 / 108,672.679 | 35,960.881 / 112,810.068 |

| Metric | 099 | 100 | Main |
|---|---:|---:|---:|
| /usr/bin/time wall seconds | 37.74 | 41.08 | 36.10 |
| /usr/bin/time CPU seconds | 107.23 | 108.79 | 112.92 |
| Max RSS bytes | 9,597,321,216 | 9,639,919,616 | 9,653,567,488 |
| Build sum of heap-pool peaks bytes | 7,741,780,480 | 7,863,991,808 | 7,458,150,368 |
| Build GC count / milliseconds | 44 / 1122 | 45 / 1109 | 42 / 1044 |
| Prepared-save GC count / milliseconds | 4 / 213 | 2 / 41 | 4 / 121 |
| Nodes / callsites | 4744132 / 2251811 | 4744132 / 2251811 | 4744132 / 2251811 |

**Interpretation:** versus 099, 100 build wall falls 30.134 -> 29.128 s but build CPU rises 86.472 -> 89.340 s; whole CPU rises 107.23 -> 108.79 s and RSS rises 9,597,321,216 -> 9,639,919,616 bytes. Versus main, 100 build wall remains slower (29.128 vs 28.815 s), while build/whole CPU and RSS are lower. Build heap-pool peaks are highest for 100. Its 11.278 s save tail makes the 40.925 s pipeline slower than both 099 37.568 and main 35.961 s. The tail is retained, not attributed causally to this cache: main and other frozen variants have previously shown the same slow-save state. Conversely, faster build cannot erase the unfavorable total/CPU/heap observations. Equal node/callsite counts are not complete semantic proof.

**Protocol provenance:** /tmp/graphite-jar-quiet-recovery/protocol.json is original preparation metadata; compiled:false and measured:false were never updated. Those stale preparation flags are not evidence of execution failure. Actual per-run command.json, successful exits/pipeline markers and sourceSha256 pin execution to quiet source 1317041bdc5875e0d8cee1c72386837752a96af69a72f5093cd0184a50dfe799 (parent verbose source 6552cb9c4afbb2d28c1d47f64e090eb1044764486816a727fab744df52cb18dc). Raw command/phase/CPU/heap/GC/RSS records are in /tmp/graphite-jar-quiet-recovery/results/{streaming-input-exact-serial-enum,streaming-input-exact-serial-enum-typecache,main}-kotlin-quiet-typecache2/; summary is /tmp/graphite-sootup-recovery.6wpE4s/trial100-quiet-results.json. Command form: python3 /tmp/graphite-jar-quiet-recovery/run.py run LABEL PINNED_KOTLIN_JAR LABEL-kotlin-quiet-typecache2 --heap 8g --sdk -.

**Independent correctness:** candidate 100 now matches main across all 22 Kotlin mapped shape properties and all 13 supplementary metadata properties. /tmp/graphite-jar-quiet-recovery/verification-typecache1/shape-compare.log and metadata-differences.json report empty differences; the per-variant shape/metadata logs and command records preserve successful runs. Separately, all 14,243 in-memory Kotlin/Tika enum API keys match main with identical typed payloads and every report property: /tmp/graphite-apk-recovery/verification/reports/100-{kotlin,tika}-memory-typecache1-compare.log both end ENUM_VALUES_PARITY_OK. These are direct candidate checks, not inherited parent-099 evidence. Existing scope limits still apply: mapped enum omission is not proof of absent in-memory values, and the compared APIs do not cover every resource or possible query.

**Frozen Android JAR JMH:** pinned Robolectric android-all 14-robolectric-10818077, two cold forks per method/variant, -bm ss -tu ms -wi 0 -i 1 -f 2 -t 1 -foe true -jvmArgs '-Xmx8g' -prof gc. These are Java classfiles, not APK/DEX. GraphBuildBenchmark.buildAndroidSdkGraph disables call graph but retains annotations and cross-method dispatch; buildAndroidSdkGraphEndToEndConfig disables all three. Save/load/query are outside both build methods. This is fresh coldtypecache1 evidence, separate from 099's earlier coldenum1 rows.

| Benchmark / variant | Fork 1 ms/op | Fork 2 ms/op | Mean ms/op | Fork 1 allocation B/op | Fork 2 allocation B/op | Mean allocation B/op | GC count / ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| android / Pre-upgrade | 27,998.724 | 28,424.764 | 28,211.744 | 44,448,338,520 | 44,713,030,488 | 44,580,684,504 | 84 / 1989 |
| android / Main | 23,791.813 | 23,950.647 | 23,871.230 | 41,043,161,792 | 41,093,465,040 | 41,068,313,416 | 84 / 1993 |
| android / 100 | 23,552.684 | 23,463.788 | 23,508.236 | 38,410,559,680 | 38,281,178,456 | 38,345,869,068 | 80 / 1853 |
| android-e2e / Pre-upgrade | 18,713.156 | 19,002.664 | 18,857.910 | 35,002,487,192 | 35,008,823,360 | 35,005,655,276 | 76 / 1539 |
| android-e2e / Main | 19,149.263 | 19,196.256 | 19,172.760 | 36,878,921,136 | 36,864,366,128 | 36,871,643,632 | 74 / 1744 |
| android-e2e / 100 | 19,092.382 | 18,998.947 | 19,045.665 | 33,972,678,368 | 33,957,818,960 | 33,965,248,664 | 79 / 1623 |

Outer resource measurements cover the entire JMH command, including its harness and both forks. These are NOT method CPU/op, allocation/op, or per-operation RSS, and must not be divided by a guessed operation count:

| Benchmark / variant | Whole-command wall s | Whole-command CPU s | Whole-command max RSS bytes |
|---|---:|---:|---:|
| android / Pre-upgrade | 57.10 | 171.75 | 9,286,418,432 |
| android / Main | 50.09 | 179.44 | 9,211,379,712 |
| android / 100 | 48.66 | 153.71 | 9,196,077,056 |
| android-e2e / Pre-upgrade | 38.32 | 116.98 | 8,255,340,544 |
| android-e2e / Main | 38.98 | 147.84 | 9,156,820,992 |
| android-e2e / 100 | 39.29 | 129.95 | 8,920,662,016 |

Method-level comparison (100 point estimates, with both baselines explicit):

- android versus Pre-upgrade: time -16.67%, allocation -13.99%.
- android versus Main: time -1.52%, allocation -6.63%.
- android-e2e versus Pre-upgrade: time +1.00%, allocation -2.97%.
- android-e2e versus Main: time -0.66%, allocation -7.88%.

The chain reduces allocation and whole-command CPU versus upgraded main in both Android configurations. Ordinary Android improves time relative to both baselines. Reduced Android EndToEndConfig is still 1.00% slower than old; its outer CPU is 129.95 vs old 116.98 s (+11.09%) and RSS 8,920,662,016 vs 8,255,340,544 bytes (+8.06%). Its outer wall is also 39.29 vs main 38.98 s despite the slightly better method point estimate. The old CPU/RSS gaps are material and remain part of the conclusion; allocation reductions cannot be equated with full memory/CPU recovery.

Exact results, all forks, method identities and outer resource scope: /tmp/graphite-sootup-recovery.6wpE4s/trial100-android-jmh-comparison.json and results/{preupgrade,main,streaming-input-exact-serial-enum-typecache}-jmh-{android,android-e2e}-coldtypecache1/{command.json,jmh.json,stderr-time.log}. Wrapper command: python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py jmh LABEL --run coldtypecache1 --forks 2 --corpus android (repeat android-e2e). All hashes remain those of their immutable frozen snapshots, not the later directory-scoped source.

**Tika JFR diagnostic boundary:** a separate pre-upgrade -> frozen100 pair used the identical reduced 4g gate with startup recovery.jfc and GC logging, without attach; both correctness gates passed. Both commands are marked profiled=true and excluded from performance summaries/tables. The directory-scope fix was not compiled into either snapshot. Whole diagnostic CPU was 73.38 -> 74.45 s (+1.07 s), with GC-log pause User+Sys sum 9.15 -> 10.03 s (+0.88 s). This is a smaller CPU gap than the unprofiled samples, and pause accounting excludes some concurrent-GC cost; it does not establish the cause of earlier unprofiled Tika CPU increases.

Bounded JFR ThreadCPULoad integrals are approximations, not exact per-thread CPU counters: the sampled JIT and ForkJoin work did not show a surviving large parser-parallel/JIT excess. Execution samples were dispersed across strings/maps, persistence/compression and queries; weighted allocation groups report the first SootUp frame, combining callers, and do not measure this cache's hit/miss behavior. They must neither replace exact JMH B/op nor justify another cache from one profile. No clear, bounded, safe additional optimization is established. Preserve the unfavorable unprofiled CPU/RSS/tail observations. Details and protocol/source pins: /tmp/graphite-sootup-recovery.6wpE4s/profiles/trial100-tika/{interpretation.txt,protocol.json,time-gc-comparison.json,preupgrade-summary.txt,typecache-summary.txt}; profiled timings remain outside all performance tables.

**Final integration validation:** The directory-scoped integration artifact has now passed the complete 471-test suite (zero failures/errors/skips), detekt, JMH artifact build and recovery-classpath preparation. Frozen label `recovery-final` has runtime adapter JAR SHA-256 `666f2ff520b69fd96335e2a968651104555d898220a3c4a04f9fced25009f410`. `/tmp/sootup-recovery-sources/recovery-final-snapshot/test-proof.json` pins the aggregate result and individual test XML hashes; `test-results/` preserves the XML, including the existing directory/JAR parity and truncated-class tests, and `/tmp/sootup-recovery-sources/recovery-final-test.log` records the successful full build. `artifact-diff.json` confirms only ParsedClassLocation and its companion differ from frozen 100, with no added/removed classes. These are new-artifact validation results; no historical timing or hash is relabeled. Final three real-corpus gates and a separate Android correctness helper checking for OOM/skipped analysis remain pending, as does CI. The five publishing-plugin Gradle files were restored to their recorded original SHA values; temporary build preparation changes are not part of the production patch.

**Remaining:** final three real-corpus gates, Android no-OOM/no-skipped-analysis correctness and required CI/benchmark-regression-gate evidence are pending. Completed correctness checks do not settle the observed CPU/RSS and total-latency tradeoffs.

**Final-source provenance:** the final integration source includes Attempt 097's later directory-path preservation: `fileSystem == null` keeps `Files.readAllBytes`, while archive entries use the exact input-stream read. This scope refinement occurred after this trial's frozen artifact and measurements. The hash-pinned results above still describe that original frozen source, not a validated or measured new artifact. No directory performance improvement is claimed; new-artifact full-suite/lint passed with 471 tests under recovery-final; the remaining real-corpus/Android checks and CI are pending (see Attempt 097 validation proof). See Attempt 097 and `/tmp/sootup-static-review/attempt097-directory-read-note.txt`.

**Decision:** RETAINED as an aggregate-chain component for substantial measured allocation reductions and CPU reductions versus upgraded main, with the stated correctness checks on its frozen artifact. This does not make it a universal standalone improvement: both Hive orders cost about 2–3% CPU versus 099, RSS reverses direction, Tika/Kotlin CPU still exceeds old observations, quiet build CPU/RSS exceed 099, and the 11.278 s save tail remains. Reduced Android outer CPU/RSS also remain about 11%/8% above old. The overall user recovery goal is NOT yet fully met. The directory-scoped artifact passed 471 tests/lint/build, while final real-corpus/Android follow-ups and CI are pending; no blanket no-regression claim.

**Final scoped-artifact gates:** after the directory-read correction, `recovery-final`
(runtime adapter SHA-256 `666f2ff520b69fd96335e2a968651104555d898220a3c4a04f9fced25009f410`)
passed all three existing 4 GiB real-corpus gates. These additional runs prove the
integrated artifact still satisfies the gate assertions; they do not replace the
paired frozen-100 samples or establish a new matched performance comparison.

| Corpus | Build s | Save s | Pipeline s | Whole-process CPU s | Max RSS GB | Peak heap GB |
|--------|--------:|-------:|-----------:|--------------------:|-----------:|-------------:|
| Tika | 12.570 | 4.582 | 18.449 | 74.16 | 5.202 | 3.623 |
| Hive | 22.972 | 7.315 | 31.950 | 112.23 | 5.266 | 4.079 |
| Kotlin | 12.270 | 4.476 | 17.916 | 70.54 | 5.127 | 3.878 |

Full raw metrics are in `/tmp/graphite-sootup-recovery.6wpE4s/recovery-final-proof-gates.json`.
The final snapshot preserves the source state, build log, artifact diff, test XML
and checksums in `/tmp/sootup-recovery-sources/recovery-final-snapshot`; all 471 tests
passed without failures, errors or skips, and detekt passed. Additional Android
coverage/no-OOM-skip assertions and the required PR benchmark gate remain pending.

**Late Android JAR correctness proof for final directory-scoped integration:** four independent Xmx8g builds completed successfully: main and `recovery-final` in each of the standard and EndToEndConfig benchmark configurations. All used the same pinned Android all 14-robolectric-10818077 real JAR, MmapGraphBuilder and verifier source SHA-256 `3e704ce69c5a4f0cd163160b6705c724cb41fcc9804d2a9fe323fbaa29624eac`. These are correctness-only runs, not performance replacements. The verified final adapter remains SHA-256 `666f2ff520b69fd96335e2a968651104555d898220a3c4a04f9fced25009f410`; these results do not cover the subsequent 101 `empty-lvt` artifact.

| Configuration / variant | Heap | Exit | Recognized OOM skip messages | Nodes | Methods | Callsites |
|---|---|---:|---:|---:|---:|---:|
| Standard / main | 8g | 0 | 0 | 6,849,998 | 408,510 | 2,543,259 |
| Standard / recovery-final | 8g | 0 | 0 | 6,849,998 | 408,510 | 2,543,259 |
| End-to-end / main | 8g | 0 | 0 | 5,953,640 | 408,510 | 1,721,578 |
| End-to-end / recovery-final | 8g | 0 | 0 | 5,953,640 | 408,510 | 1,721,578 |

Both configuration comparisons report empty report differences and `ANDROID_BUILD_COVERAGE_PARITY_OK`. Standard enables annotations and cross-method dispatch; EndToEndConfig disables them; both disable call-graph construction, matching their corresponding JMH methods. No old/new baseline equivalence or APK/DEX result is inferred.

The verifier uses typed, length-delimited encodings, raw floating bits and UTF-16 strings, failing on unknown metadata types. Full method descriptors have count/hash `408510:bae3a09dff97a0a9c588ac65dcc131683076ffae8dcb5a99132744ea305d3040` on both sides. Call payload count/hash is `2543259:9ac3250a1e90206d8d14f9bb83d9ac354642d4583a3b7054dc22f27ad7b0f0b7` in standard and `1721578:1c6e574428cb64f94ca3aa9a9cc68db4a9ec153318af9c5105bb76b5b0d95e34` in end-to-end. Call rows include caller/callee descriptors, line number and argument arity; a separate signature adds virtual/dynamic call-edge flags. Reports also compare node-kind/edge counts, annotation-node values, per-method annotation lookups, member annotations, synthetic identities, class origins and artifact dependencies.

Coverage limits: these are order-independent typed aggregate fingerprints (sum of SHA-256 row hashes modulo 2^256 plus counts), not stored per-row payload comparisons. They do not verify argument/receiver node connectivity, all branch/data-flow structure, every resource/query API or every possible skipped-analysis path. The zero-OOM assertion specifically detects the adapter's known OOM-skip warning forms; it is not proof that arbitrary non-OOM skips cannot occur. Broader normalized Kotlin shape/metadata and typed enum evidence is recorded separately; full final-chain/CI evidence is still required.

The first main standard verifier run failed because an overbroad classifier treated two legitimate enum debug messages containing `OOM_IMPROVEMENT` as OOM skips. That failed attempt remains at `reports/main-android-standard-finalproof1.tsv.{log,command.json,driver.log}`. The corrected classifier matches the actual warning prefixes/suffixes, with positive tests for all three OOM warning forms (including both streamed-method naming forms) and negative tests for the retained enum messages, exception-class names and ordinary non-OOM warnings. No failed run was relabeled a success; all four finalproof2 runs used the corrected pinned helper.

Evidence: `/tmp/graphite-apk-recovery/verification/reports/android-finalproof2-summary.json`, `android-{standard,end-to-end}-finalproof2-compare.log`, and each `{main,recovery-final}-android-{standard,end-to-end}-finalproof2.tsv` plus command/log/driver records. Helper and classifier validation are `/tmp/graphite-apk-recovery/verification/{AndroidBuildVerifier.java,run-android-build.py,check-android-oom-classification.py}`. This late correctness evidence does not alter earlier frozen 100 timing/allocation/CPU/RSS results or erase their unfavorable samples.

### 2026-10-07 — Attempt 101: Skip unused instruction indexing for empty local-variable tables

**Question:** can an empty ASM local-variable list avoid SootUp's instruction-position map without changing any body names, debug information, annotations or control flow?

**Revisions and scope:** parent commit `3c815753`, frozen `recovery-final` runtime adapter JAR SHA-256 `666f2ff520b69fd96335e2a968651104555d898220a3c4a04f9fced25009f410`. Frozen candidate `empty-lvt` runtime adapter JAR SHA-256 `c5aa3362611a96cd6890f4c30a2282a39339b23aa10908d20a77038f82df9923`. The current production change is nine added lines in `GraphiteClassNode.visitEnd`: after all class visits, set only a non-null, empty method `localVariables` list to null. Tests are in `StreamingClassMethodsTest`. ParsedClassLocation's directory/archive scope, nonempty LVT, instruction lists, type-annotation lists, line numbers, loading flags and the existing index implementation remain unchanged. This is independent of rejected Attempt 094: it skips unused empty-LVT work, not a replacement index/map.

**Static mechanism:** ASM 9.10.1 `MethodNode` initializes `localVariables` to an empty ArrayList for every nonabstract method. SootUp 3.0.1 `determineLocalName` checks only for non-null, calls `insnIndex(atInsn)` before iterating that list, and builds a full instruction-position HashMap even when no entry can match. Empty and null both yield `l` + slot index. Parameter naming uses a null instruction and does not create the map. A method must create a new nonparameter local during conversion for this avoidable work to occur.

Local TYPE_USE annotations are independent: their visible/invisible lists and scope lookup still request the same original instruction map when needed. LineNumberNode instructions remain intact. ASM replay emits zero local-variable events for either an empty or null list, while emitting annotations and line information independently. Normalization occurs at parse completion before publication, not during body conversion. Repeated body conversion and the existing scratch-release lifecycle must remain equivalent.

**Source evidence:** exact Maven source archives, extracted files and SHA pins are under `/tmp/sootup-static-review/attempt101/`:

| Source | SHA-256 |
|---|---|
| `asm-tree-9.10.1-sources.jar` | `93a9406bb68abff43f491891625e0d3c7edce87b70d2370ec1ea4627c9ead090` |
| `sootup.java.bytecode.frontend-3.0.1-sources.jar` | `29900b731104a4d4c2d42ea497962b63a5d21df3e7b18d056fa6994869117ab6` |

`MethodNode.java:226–227,473–509,732–770` and `AsmMethodSource.java:320–346,409–446` support the mechanism and metadata separation. `source-sha256.json` also pins the extracted source and inventory script; the inspected SootUp source matches the previously used copy byte for byte. `feasibility.txt` records references, lifecycle reasoning and limits.

**Read-only real-input inventory:** Python parsed the pinned original JAR classfiles, checking fixture SHA-256 against `/tmp/graphite-sootup-recovery.6wpE4s/main.json`. Only root entries whose path matches the class name are included; META-INF variants are excluded consistently with the production input policy. Unknown/truncated structures fail. Encoded Code bytes are a classfile size proxy, not ASM node counts, saved allocation or performance measurements.

| Real fixture | Code methods | No LVT entries | Nonempty LVT | No-LVT Code bytes | No-LVT with maxLocals > 0 |
|---|---:|---:|---:|---:|---:|
| Tika app 2.9.2 | 261,274 | 56,699 | 204,575 | 4,581,546 | 44,862 |
| Kotlin compiler embeddable 2.0.21 | 248,855 | 16,297 | 232,558 | 1,605,677 | 6,253 |
| Hive exec 4.0.0 | 429,941 | 27,973 | 401,968 | 1,279,821 | 6,614 |
| Android all 14-robolectric-10818077 | 379,430 | 11,814 | 367,616 | 765,835 | 686 |

Even positive maxLocals counts are upper bounds: this/parameter locals can already exist, unused slots need no name, and not every method is converted. No actual index-construction count or saved bytes/time is inferred. All four fixtures contain zero no-LVT methods with local TYPE_USE annotations; that critical combination therefore requires the new controlled correctness guard. Tika/Kotlin contain 50/86 methods with such annotations and nonempty LVT, which must remain unchanged. Full inventory, paths/checksums and explicit-empty versus missing-table counts are in `inventory.json`.

```sh
python3 /tmp/sootup-static-review/attempt101/inventory.py \
  /tmp/graphite-sootup-recovery.6wpE4s/main.json \
  /tmp/sootup-static-review/attempt101/inventory.json
```

**Validation prepared:** two new stock-versus-Graphite tests compare exact statements, local names/types/annotation values, normal/exceptional CFG topology and source positions. A no-LVT/no-local-annotation fixture must leave Graphite's instruction index absent while stock creates it; this is a structural assertion, not a synthetic benchmark. A no-LVT TYPE_USE fixture must preserve annotation scopes and index availability. Both repeat fresh body resolution and real adapter scratch cleanup. The adjacent nonempty-LVT test now checks complete names/slots, list identity and existing scoped behavior; line assertions use concrete fixture lines. The first full 473-test run failed three new position comparisons, including the existing nonempty-LVT reuse case; no freeze or benchmark followed. Frozen pre101 recovery-final reproduced that identical nonempty-LVT failure. Static source confirms the oracle compared stock's first cached body with candidate repeated resolutions while SootUp retains currentLineNumber. Tests now use SootUp's own `expected.withSource(expected.bodySource)` per iteration, with independent stock metadata/source and matched conversion counts; all exact position, semantic and cache assertions remain. The corrected oracle then passed the full 473-test suite (zero failures/errors/skips), detekt, JMH build and classpath preparation; `git diff --check` passed. `/tmp/sootup-recovery-sources/empty-lvt-test-retry1.log` and `empty-lvt-snapshot/test-proof.json` preserve the successful rerun. Artifact comparison changes only GraphiteClassNode and its file class; independent javap comparison confirms the latter's executable code is identical. Earlier failure/isolation records remain unchanged. Evidence: `/tmp/sootup-recovery-sources/attempt101-failed-test-proof/`, `attempt101-baseline-isolation/`, and `/tmp/sootup-static-review/attempt101/position-oracle-note.txt`.

**Bounded real Tika evaluation:** the frozen parent and candidate each ran two cold JMH forks of `GraphBuildBenchmark.buildTikaGraphEndToEndConfig`, parent -> 101, using `-bm ss -tu ms -wi 0 -i 1 -f 2 -t 1 -foe true -jvmArgs '-Xmx8g' -prof gc`. The same pinned Tika app 2.9.2 was then used for three fresh Xmx4g gates in order 101 -> parent -> pre-upgrade. All configurations disable call graph, annotations and cross-method dispatch. The gates retain graph/query/branch-definition checks with timing ceilings disabled by record mode. Shared Apple M3 Max / JDK 17 host, not a dedicated runner.

| Tika JMH variant | Fork1 ms/op | Fork2 ms/op | Mean ms/op | Fork1 allocation B/op | Fork2 allocation B/op | Mean allocation B/op | Outer CPU s | Outer max RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| recovery-final | 12,394.283 | 12,653.049 | 12,523.666 | 21,499,888,256 | 21,570,098,728 | 21,534,993,492 | 92.98 | 5,374,836,736 |
| empty-lvt | 12,554.027 | 12,365.532 | 12,459.780 | 21,399,790,952 | 21,423,486,216 | 21,411,638,584 | 94.01 | 5,180,080,128 |

Outer CPU/RSS cover the entire JMH command including its harness and both forks, not method CPU/op or per-operation RSS. Allocation decreases by 123,354,908 B/op (123.35 decimal MB, 0.573%) versus the parent. Time mean is 0.51% lower, but fork ranges overlap; this does not establish a latency gain. Outer CPU rises 92.98 -> 94.01 s while RSS falls; neither is hidden by the allocation result.

| Tika gate variant | Build ms | Save ms | Mapped-load median ms | Query ms | Pipeline ms | Process wall s | Process CPU s | Peak heap bytes | Max RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| empty-lvt | 12,428 | 4,468 | 90 | 1,327 | 18,313 | 21.73 | 72.56 | 3,573,771,216 | 5,194,170,368 |
| recovery-final | 12,460 | 4,529 | 90 | 1,214 | 18,293 | 21.65 | 72.57 | 3,564,011,544 | 5,192,679,424 |
| preupgrade | 12,811 | 4,365 | 90 | 1,199 | 18,465 | 21.76 | 72.55 | 3,633,746,456 | 5,034,868,736 |

All three gates pass; CPU is essentially flat at 72.56/72.57/72.55 s. Candidate saves/builds slightly faster than parent but query and total pipeline are slower; RSS is slightly above parent and about 159 MB above old. This newest old/parent CPU equality is retained alongside, not selected over, the earlier real samples showing old CPU/RSS gaps in 100. No full-scope CPU recovery or broad no-regression claim follows from a single Tika round.

```sh
python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py jmh recovery-final --run coldempty1 --forks 2 --corpus tika
python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py jmh empty-lvt --run coldempty1 --forks 2 --corpus tika
for label in empty-lvt recovery-final preupgrade; do
  python3 /tmp/graphite-sootup-recovery.6wpE4s/harness.py gate "$label" --run empty1 --corpus tika
done
```

**Evidence:** `trial101-tika-jmh-comparison.json`, `trial101-tika-gates.json` and matching `results/*-tika-{coldempty1,empty1}/` under `/tmp/graphite-sootup-recovery.6wpE4s` retain all rows/forks and exact command/fixture/runtime pins. `/tmp/sootup-recovery-sources/empty-lvt-snapshot/{source-state.json,artifact-diff.json,additional-class-code-verification.json,test-proof.json}` seals the candidate and passing tests. No investigator JVM runs were used; the coordinator ran the tests and measurements. APK remains paused.

**Conclusion:** RETAIN the small nine-line removal of unused work: source semantics are bounded, 473 tests/lint pass, allocation has a 123 MB/op signal and gate CPU is flat versus the parent. This is not an established latency gain, full-scope CPU/RSS recovery or proof that the overall user goal is achieved. Broader final-artifact semantic/performance proof and required CI/benchmark-regression-gate remain pending; all previous unfavorable measurements remain valid evidence.

**Additional sealed-candidate gates:** Hive and Kotlin also passed the unchanged
4 GiB persisted-graph gate. Hive recorded build 22.603 s, pipeline 31.400 s,
whole-process CPU 111.56 s, max RSS 5.408 GB and peak heap 4.104 GB. Kotlin
recorded 12.089 s, 17.555 s, 72.16 s, 4.986 GB and 3.724 GB respectively.
These candidate-only artifact checks do not replace paired comparisons. All
fields remain in `/tmp/graphite-sootup-recovery.6wpE4s/trial101-proof-gates.json`.
Final Android JMH/coverage, quiet full-feature shape/metadata, typed enum
rechecks and the required PR benchmark gate remain in progress.

### 2026-10-07 — Attempt 102: Reuse eligible typed sub-signatures within a parsed view

**Status:** RETAIN as the next recovery candidate; required CI passed at `58fc8983`, but full pre-upgrade recovery remains unproven. Parent is `ca8df9a589863d0b8f8c90b5ec4b550d8c1d20a3` (Attempt 101). Its 473 local tests, three corpus gates, main-versus-final semantic checks and required PR #171 benchmark gate passed; the final gate comment is https://github.com/johnsonlee/graphite/pull/171#issuecomment-6025051872. This does not erase the remaining pre-upgrade resource gap.

**Hypothesis:** sharing repeated immutable method/field sub-signatures through the existing per-view identifier factory may avoid SootUp 3's new per-sub-signature hash memoizers while preserving fresh outer signatures and each caller-supplied type object. No global factory replacement, upstream patch, feature suppression, heap-limit change or forced GC belongs in production.

**Evidence motivating this separate attempt:** fresh unprofiled reduced Android JAR comparison at the parent had method wall -0.17%, allocation -3.41%, whole-command CPU +5.47% and RSS +9.32% versus pre-upgrade `6f498705`. CPU/RSS include the harness and both cold forks. A separate JFR/NMT diagnostic reversed RSS ordering and therefore did not attribute that peak. A subsequent intrusive full-GC histogram at the identical pre-finalization callback found 3,451,711,664 live shallow bytes at the parent versus 3,211,121,040 before upgrade: +240,590,624 bytes (+7.49%). Both correctness-only builds completed with zero OOM skips and 408,510 methods. These histograms are not performance measurements or an exact explanation of peak RSS.

SootUp 3 adds a memoized hash supplier to SootClassMemberSubSignature and another to MethodSubSignature. Both resolved Guava versions already allocate a lock per nonserializable memoizing supplier. Source fields and histogram populations reconcile the entire supplier-count delta: 2 * 1,443,943 method subs + 843,818 field subs - 3 * 45,186 fewer preexisting textual suppliers + 48,226 new per-class method indexes + 298 body suppliers = 3,644,670 additional suppliers. Supplier, Object and boxed Integer shallow-byte changes total 205,255,456 bytes; the Object count differs from the supplier count by only five. This is a concrete retention mechanism, not evidence that a local cache will save the same amount.

**Proposed boundary:** override only typed outer method/field signature construction in GraphiteIdentifierFactory. Keep string, parser, explicit-sub-signature and direct sub-signature overloads unchanged. Fresh stock outer objects retain their given declaring owner; shared subs use name value equality and ordered type reference identity. Permit exact ordinary JavaClassType with exact PackageName, primitive singletons and Void; bypass arrays, unknown/null/custom types and invalid inputs through the existing superclass path. Snapshot stored method-key parameters using the stock sub-signature's immutable list only on a miss. Instance-local concurrent maps add strong O(unique eligible sub-signatures) retention until the view dies; this tradeoff must be measured.

**Coverage limits:** AsmMethodSource uses the view factory for own signatures, invokes and field references. Stock field declarations and fallback method wrappers use the global factory and will not benefit. Static pinned Android bytecode contains 92.3% nonarray method declarations and about 94.3%/94.4% nonarray method/field constant-pool references, with substantial repeated name/descriptor pairs. These are opportunity counts, not dynamic hit rates, type-identity duplication counts or measured savings.

**Pinned workload and protocol:** Robolectric Android 14 JAR SHA-256 `6be2218c6a53fe3c57bc22ebdc723edcb7270a8a6f187545708aa5c0ed813977`, Apple M3 Max 16 CPU/64 GiB, macOS 14.3 arm64, OpenJDK 17.0.20.1, shared host, serial heavy JVM execution. Exact baseline and proposed pilot commands are in `/tmp/sootup-recovery-sources/attempt102-pilot-plan.json`. The primary pilot uses the unchanged harness, `GraphBuildBenchmark.buildAndroidSdkGraphEndToEndConfig`, 8g, zero warmup, one single-shot measurement and two cold forks with the GC profiler, parent then candidate. Any follow-up forced-GC histogram is separately labeled intrusive correctness/retention evidence.

**Evidence paths:** `/tmp/graphite-android-retained-heap/interpretation.md`, `results/pre-build-comparison.json`; `/tmp/graphite-sootup-recovery.6wpE4s/trial101-android-reverse-primary-comparison.json`; `/tmp/sootup-static-review/android-subsignature-static-inventory.json`; `/tmp/sootup-recovery-sources/attempt102-proposal.txt`.

**Validation so far:** independent static review found no blocking semantic issue. Ten focused tests cover stock values/hashes/ordering, supplied type identity, separate overloads, null/custom fallback, mutable ArrayList/LinkedList ownership and concurrent publication. The first two build attempts stopped at detekt before tests: ComplexCondition/EqualsWithHashCode, then ReturnCount. Equivalent control-flow factoring and identity equals on the mutable test helper addressed those findings without suppressions or threshold changes. Failure logs remain `attempt102-test.log` and `attempt102-retry1.log`; any prior XML copied during these early failures is explicitly stale and not Attempt102 evidence. Retry2 passed the complete module build: 483 tests, zero failures/errors/skips, detekt, JMH JAR and runtime classpath. Root independently counted the 43 fresh XML reports. Frozen production JAR SHA-256 is `7bdd4c17d68a6956cd37a4f56a46917ba163afa7fd68bb15ca51e43f2b2f0f35`; JMH JAR is `6a49bddc48499737d8ae9b7a44097f2e64faabf6ab78da6c1b6aa3098927ce0c`. Production bytecode differs only in GraphiteIdentifierFactory and its three new nested classes. Readiness/source/classpath pins are in `attempt102-subsignature-intern-snapshot/readiness.json`. All five temporarily altered publishing-plugin build files were restored byte-for-byte after each completed attempt.

**Initial Android reduced pilot:** fresh parent101 then candidate102, two cold forks each. Method wall 18,737.283 -> 18,737.468 ms/op (+0.001%, overlapping fork ranges); allocation 33,998,327,000 -> 32,467,187,612 B/op (-4.50%, both candidate forks lower); whole-command CPU 131.81 -> 129.62 s (-1.66%); max RSS 8,757,428,224 -> 7,542,800,384 bytes (-13.87%). Outer wall 38.76 -> 38.82 s. This small ordered pilot establishes neither a stable RSS percentage nor universal performance recovery. All raw forks and host snapshots remain in `/tmp/graphite-sootup-recovery.6wpE4s/attempt102-android-reduced-pilot.json` and referenced directories.

**Candidate intrusive retention check:** same helper/source, heap and pre-finalization phase as the parent/old histogram, with one confirmed full GC and zero OOM skips. Candidate live shallow bytes 2,982,851,768: -468,859,896 (-13.58%) versus parent101 and -228,269,272 (-7.11%) versus pre-upgrade. MethodSubSignature objects fall 1,443,943 -> 269,156, FieldSubSignature 843,818 -> 367,455. Supplier reduction is exactly `4 * 1,174,787 + 3 * 476,363 = 6,128,237`; boxed Integer reduction exactly `2 * 1,174,787 + 476,363 = 2,825,937`. Added method/field keys and net CHM nodes/tables cost about 17.264 MB and are already included in the net live-heap result. This supports the proposed retention mechanism but is not a replacement for unprofiled peak RSS. `/tmp/graphite-android-retained-heap/attempt102-interpretation.md` and `results/attempt102-pre-build-comparison.json` contain every class row and limits.

**Initial semantic proof:** corrected AndroidBuildVerifier, unchanged pinned helper and 8g Mmap builder, passes standard and reduced configurations against both main and parent101: all 29/28 respective report keys match, including duplicate-sensitive payload fingerprints; OOM skips zero. Helper coverage limits from Attempt100/101 remain. Evidence index `/tmp/graphite-sootup-recovery.6wpE4s/attempt102-bounded-validation.json`.

**Expanded unprofiled corpus gates:** all nine gates passed, which verifies their configured assertions, not recovery of every timing/resource metric. The reduced gate configuration disables call graph, annotations and cross-method functional dispatch, then builds, saves with the production call-site index, loads mapped storage and runs its query checks. Each JVM uses 4g. Run order was Tika old -> candidate102 -> main; Hive main -> old -> candidate102; Kotlin candidate102 -> main -> old. Full aggregation: `/tmp/graphite-sootup-recovery.6wpE4s/attempt102-expanded-gates.json`. Phase times below are milliseconds; outer wall/CPU are whole-process seconds, and memory/storage columns are exact bytes. Mapped-load median is from five samples, with min/max retained.

| Corpus / revision | Build ms | Save ms | Load min/median/max ms | Query ms | Pipeline ms | Outer wall s | Outer CPU s | Max RSS B | Peak heap B |
|---|---|---|---|---|---|---|---|---|---|
| tika / old 6f498705 | 12886 | 4816 | 89/93/136 | 1247 | 19042 | 22.42 | 77.44 | 5,204,180,992 | 3,848,689,688 |
| tika / candidate102 | 13569 | 5699 | 103/138/169 | 1517 | 20923 | 25.24 | 78.46 | 4,876,926,976 | 3,406,381,128 |
| tika / main c84d7811 | 16210 | 4432 | 91/93/166 | 1195 | 21930 | 25.60 | 81.77 | 5,012,832,256 | 3,850,272,768 |
| hive / main c84d7811 | 23733 | 6205 | 130/135/188 | 1561 | 31634 | 36.37 | 126.36 | 5,274,927,104 | 4,141,335,672 |
| hive / old 6f498705 | 22848 | 6291 | 129/136/211 | 1491 | 30766 | 35.45 | 103.22 | 5,168,267,264 | 3,868,285,952 |
| hive / candidate102 | 21090 | 6429 | 131/135/184 | 1538 | 29192 | 34.28 | 101.47 | 5,294,227,456 | 3,855,534,592 |
| kotlin / candidate102 | 12241 | 4496 | 76/77/130 | 1131 | 17945 | 21.23 | 66.22 | 4,920,705,024 | 3,676,421,120 |
| kotlin / main c84d7811 | 12490 | 4229 | 75/76/118 | 1057 | 17852 | 21.21 | 78.23 | 5,101,240,320 | 3,881,242,136 |
| kotlin / old 6f498705 | 12282 | 4243 | 71/75/120 | 1190 | 17790 | 21.03 | 65.63 | 5,193,121,792 | 3,652,663,200 |

The three revisions have these equal reduced-gate counts within each corpus; equality of these totals is not complete payload equality. `productionIndexPrepared=1` and `mappedLoadSamples=5` for all nine.

| Corpus | Nodes | Source edges | Persisted edges | Methods | Call sites | Synthetic identities | Branch-definition B |
|---|---|---|---|---|---|---|---|
| tika | 3,901,103 | 4,510,016 | 4,353,588 | 312,788 | 1,006,172 | 16,936 | 6,083,824 |
| hive | 5,992,914 | 6,597,267 | 6,376,682 | 404,016 | 1,443,886 | 77,832 | 10,512,048 |
| kotlin | 3,292,214 | 3,906,617 | 3,785,858 | 249,669 | 922,876 | 55,047 | 9,363,036 |

Storage sizes differ slightly; do not label the persisted files byte-identical. `branchDefinitionsMs` measures the first mapped branch-definition access after the timed queries; it is reported separately and excluded from the pipeline sum.

| Corpus / revision | Persisted B | Call-site index B | Mapped branch definitions ms |
|---|---|---|---|
| tika / old 6f498705 | 336,794,906 | 38,738,100 | 646 |
| tika / candidate102 | 336,801,071 | 38,740,564 | 746 |
| tika / main c84d7811 | 336,801,072 | 38,740,564 | 614 |
| hive / main c84d7811 | 528,533,024 | 52,387,660 | 944 |
| hive / old 6f498705 | 528,529,159 | 52,386,900 | 957 |
| hive / candidate102 | 528,533,027 | 52,387,660 | 939 |
| kotlin / candidate102 | 311,075,230 | 38,354,892 | 695 |
| kotlin / main c84d7811 | 311,075,227 | 38,354,892 | 775 |
| kotlin / old 6f498705 | 311,064,525 | 38,353,252 | 630 |

Candidate percentage changes below retain both baselines; positive means more/slower.

| Comparison | Build | Save | Load median | Query | Pipeline | Outer CPU | RSS |
|---|---|---|---|---|---|---|---|
| tika vs old 6f498705 | +5.30% | +18.33% | +48.39% | +21.65% | +9.88% | +1.32% | -6.29% |
| tika vs main c84d7811 | -16.29% | +28.59% | +48.39% | +26.95% | -4.59% | -4.05% | -2.71% |
| hive vs old 6f498705 | -7.69% | +2.19% | -0.74% | +3.15% | -5.12% | -1.70% | +2.44% |
| hive vs main c84d7811 | -11.14% | +3.61% | +0.00% | -1.47% | -7.72% | -19.70% | +0.37% |
| kotlin vs old 6f498705 | -0.33% | +5.96% | +2.67% | -4.96% | +0.87% | +0.90% | -5.25% |
| kotlin vs main c84d7811 | -1.99% | +6.31% | +1.32% | +7.00% | +0.52% | -15.35% | -3.54% |

In particular, Tika pipeline is **+9.88% versus old** (save/load/query all worse in this run), and Hive RSS is **+2.44% versus old** despite lower build/pipeline/CPU. Kotlin pipeline is slightly higher than both baselines; its CPU remains slightly higher than old. One ordered run per corpus/revision cannot establish stable attribution, and earlier favorable/unfavorable results remain evidence.

**Expanded Android JMH:** same fixture, unchanged benchmark methods, 8g, `-bm ss -tu ms -wi 0 -i 1 -f 2 -t 1 -foe true -prof gc`. Standard `buildAndroidSdkGraph` disables only the call graph; annotations and functional dispatch remain enabled. `buildAndroidSdkGraphEndToEndConfig` disables all three, but is still build-only JMH (it does not save/load/query). Standard order main -> candidate102 -> old, followed by reduced old -> candidate102. There is no fresh main reduced-config run in this expansion; do not invent a three-way reduced comparison or substitute a historical main result as fresh. Aggregate: `/tmp/graphite-sootup-recovery.6wpE4s/attempt102-expanded-jmh.json`.

| Config / revision | Mean ms/op | Fork1 ms | Fork2 ms | Mean B/op | Fork1 B/op | Fork2 B/op |
|---|---|---|---|---|---|---|
| android / main c84d7811 | 23408.298771 | 23660.974417 | 23155.623125 | 41,069,658,812 | 41,078,325,608 | 41,060,992,016 |
| android / candidate102 | 23547.679417 | 23346.520208 | 23748.838625 | 36,605,269,156 | 36,561,921,128 | 36,648,617,184 |
| android / old 6f498705 | 28466.312667 | 28917.697334 | 28014.928000 | 44,456,840,084 | 44,504,026,792 | 44,409,653,376 |
| android-e2e / old 6f498705 | 18925.928354 | 19010.502833 | 18841.353875 | 34,923,579,768 | 34,946,147,416 | 34,901,012,120 |
| android-e2e / candidate102 | 18542.042292 | 18649.203250 | 18434.881333 | 32,517,716,212 | 32,492,306,544 | 32,543,125,880 |

Outer measurements include the JMH harness and both cold forks, not per-operation CPU or RSS.

| Config / revision | Outer wall s | Outer user s | Outer system s | Outer CPU s | Max RSS B |
|---|---|---|---|---|---|
| android / main c84d7811 | 47.43 | 167.58 | 5.92 | 173.50 | 9,271,279,616 |
| android / candidate102 | 47.70 | 137.28 | 4.73 | 142.01 | 7,866,499,072 |
| android / old 6f498705 | 58.32 | 168.04 | 5.07 | 173.11 | 9,217,081,344 |
| android-e2e / old 6f498705 | 39.00 | 120.43 | 4.32 | 124.75 | 7,550,648,320 |
| android-e2e / candidate102 | 37.69 | 114.83 | 4.23 | 119.06 | 7,673,315,328 |

| Comparison | Method wall | Allocation | Outer CPU | RSS |
|---|---|---|---|---|
| android vs main c84d7811 | +0.60% | -10.87% | -18.15% | -15.15% |
| android vs old 6f498705 | -17.28% | -17.66% | -17.97% | -14.65% |
| android-e2e vs old 6f498705 | -2.03% | -6.89% | -4.56% | +1.62% |

The fresh reduced pair retains **RSS +1.62% versus old** (7,673,315,328 versus 7,550,648,320 bytes), despite lower wall/allocation/CPU. Standard candidate method wall is slightly higher than main. The initial parent101 -> candidate102 pilot and its -13.87% RSS point remain above; that parent comparison cannot stand in for this fresh old comparison. Neither PASS nor lower diagnostic retained heap proves all resource metrics recovered.

**Raw expanded run directories:** each gate contains `command.json`, `stdout.log` and `stderr-time.log`; each JMH directory additionally contains `jmh.json`. These files carry exact command/classpath/fixture pins, timestamps, fork records and exit status. Listed in execution order within each group:

- `/tmp/graphite-sootup-recovery.6wpE4s/results/preupgrade-gate-tika-subsignature1`
- `/tmp/graphite-sootup-recovery.6wpE4s/results/attempt102-subsignature-intern-gate-tika-subsignature1`
- `/tmp/graphite-sootup-recovery.6wpE4s/results/main-gate-tika-subsignature1`
- `/tmp/graphite-sootup-recovery.6wpE4s/results/main-gate-hive-subsignature1`
- `/tmp/graphite-sootup-recovery.6wpE4s/results/preupgrade-gate-hive-subsignature1`
- `/tmp/graphite-sootup-recovery.6wpE4s/results/attempt102-subsignature-intern-gate-hive-subsignature1`
- `/tmp/graphite-sootup-recovery.6wpE4s/results/attempt102-subsignature-intern-gate-kotlin-subsignature1`
- `/tmp/graphite-sootup-recovery.6wpE4s/results/main-gate-kotlin-subsignature1`
- `/tmp/graphite-sootup-recovery.6wpE4s/results/preupgrade-gate-kotlin-subsignature1`

- `/tmp/graphite-sootup-recovery.6wpE4s/results/main-jmh-android-coldsubsignature-expanded1`
- `/tmp/graphite-sootup-recovery.6wpE4s/results/attempt102-subsignature-intern-jmh-android-coldsubsignature-expanded1`
- `/tmp/graphite-sootup-recovery.6wpE4s/results/preupgrade-jmh-android-coldsubsignature-expanded1`
- `/tmp/graphite-sootup-recovery.6wpE4s/results/preupgrade-jmh-android-e2e-coldsubsignature-expanded1`
- `/tmp/graphite-sootup-recovery.6wpE4s/results/attempt102-subsignature-intern-jmh-android-e2e-coldsubsignature-expanded1`

**Quiet full-feature Kotlin status at this update:** `/tmp/graphite-sootup-recovery.6wpE4s/attempt102-expanded-quiet.json` currently contains 2 completed record(s). This independent unchanged quiet helper enables default call graph, annotations and functional dispatch, prepares the production save index and scans the mapped graph; it is not the reduced gate configuration. Its source SHA-256 is `1317041bdc5875e0d8cee1c72386837752a96af69a72f5093cd0184a50dfe799`. Do not mix its phase results with verbose callback runs.

Completed main c84d7811: `/tmp/graphite-jar-quiet-recovery/results/main-kotlin-quiet-subsignature1`; nodes 4744132, calls 2251811, pipeline wall 35633.395125 ms / process CPU 111025.988 ms; outer wall 35.78 s / CPU 111.15 s / max RSS 9,654,419,456 B.

| Phase | Wall ms | CPU ms | Heap start B | Heap end B | Sum pool peaks B | GC count | GC ms |
|---|---|---|---|---|---|---|---|
| build | 28466.757 | 92676.401 | 0 | 4734056960 | 7326136832 | 42 | 1055 |
| cliNodeCount | 374.852 | 1760.980 | 4734056960 | 5530974720 | 5556140544 | 0 | 0 |
| savePrepared | 6587.055 | 16145.446 | 5530974720 | 3938812928 | 7501853184 | 4 | 137 |
| closeSource | 0.048 | 0.051 | 3938812928 | 3938812928 | 3938812928 | 0 | 0 |
| loadMapped | 160.303 | 276.618 | 3938812928 | 4052059136 | 4052059136 | 0 | 0 |
| queryAllNodeCount | 35.035 | 117.180 | 4052059136 | 4064642048 | 4064642048 | 0 | 0 |
| queryCallSiteCount | 0.785 | 1.340 | 4064642048 | 4064642048 | 4064642048 | 0 | 0 |

Completed candidate102: `/tmp/graphite-jar-quiet-recovery/results/attempt102-kotlin-quiet-subsignature1`; nodes 4744132, calls 2251811, pipeline wall 35875.007542 ms / process CPU 97734.494 ms; outer wall 36.03 s / CPU 97.84 s / max RSS 9,604,923,392 B.

| Phase | Wall ms | CPU ms | Heap start B | Heap end B | Sum pool peaks B | GC count | GC ms |
|---|---|---|---|---|---|---|---|
| build | 28494.965 | 79234.840 | 0 | 4102519808 | 6954646528 | 42 | 1010 |
| cliNodeCount | 395.321 | 1916.627 | 4102519808 | 4924603392 | 4924603392 | 0 | 0 |
| savePrepared | 6791.195 | 16158.145 | 4924603392 | 6740148224 | 7159578624 | 2 | 65 |
| closeSource | 0.040 | 0.043 | 6740148224 | 6740148224 | 6740148224 | 0 | 0 |
| loadMapped | 148.943 | 270.238 | 6740148224 | 6853394432 | 6853394432 | 0 | 0 |
| queryAllNodeCount | 34.414 | 83.621 | 6853394432 | 6865977344 | 6865977344 | 0 | 0 |
| queryCallSiteCount | 0.777 | 1.450 | 6865977344 | 6865977344 | 6865977344 | 0 | 0 |

Quiet run order was main -> candidate102. Candidate versus main: pipeline **+0.68%** (35.6334 -> 35.8750 s), save **+3.10%**, build +0.10%, whole-process CPU **-11.98%** (111.15 -> 97.84 s) and RSS **-0.51%** (9,654,419,456 -> 9,604,923,392 B). Preserve the slower wall/save observations alongside the CPU reduction. Phase `sumPoolPeaksBytes` is the sum of pool peaks, not a simultaneous live-heap measurement; end-of-phase heap depends on collection timing and cannot replace RSS. Equal node/call totals alone are not semantic proof; the separate completed bounded shape/metadata/enum checks are described below. No fresh old full-feature quiet run belongs to this pair; earlier old/full-feature semantic and performance limitations remain.

**Completed expanded correctness (separate from timing):** `/tmp/graphite-jar-quiet-recovery/verification-subsignature1/complete.json` reports all eight commands passed. Candidate `attempt102-kotlin-quiet-subsignature1` matches all 22 shape properties and 13 supplementary metadata properties against pinned, previously verified `main-kotlin-quiet-typecache2`. The freshly timed main graph lacks these fingerprints; this is not a fresh-main payload comparison. All 14,243 in-memory typed enum queries (Kotlin 4,557 + Tika 9,686) have no differing values or report properties against main; the two `enum-*-compare.log` files retain the exact results. These are bounded graph/API fingerprints and fixed enum-query inventories, not exhaustive graph equivalence. Persisted enum metadata limitations remain, hence the separate in-memory checks. The enum helper has no OOM-skip counter: do not transfer the Android helper's zero-OOM assertion to enum runs. Verification timings are excluded from performance evidence.

**Bounded Tika follow-up (diagnostic only):** the slower unprofiled Tika save/query result triggered a fixed parent101 -> candidate102 -> candidate102 -> parent101 sequence, using the unchanged 4g gate with identical startup JFR and GC logging. All four correctness gates passed. The original unprofiled results are retained and these profiled samples are excluded from primary performance summaries. No extra run was added after seeing the results.

| Order / variant | Build ms | Save ms | Query ms | Pipeline ms | Outer CPU s | Max RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| 0 / parent101 | 12745 | 4533 | 1397 | 18765 | 71.62 | 5,069,029,376 |
| 1 / candidate102 | 12930 | 4706 | 1311 | 19036 | 74.77 | 4,893,605,888 |
| 2 / candidate102 | 12906 | 4323 | 1348 | 18665 | 71.55 | 4,899,782,656 |
| 3 / parent101 | 12748 | 4759 | 1328 | 18924 | 71.53 | 5,227,266,048 |

The candidate and parent save ranges overlap and the original 5,699 ms save / 1,517 ms query did not recur. This does not explain or invalidate that unprofiled sample. Candidate build is about 1.35% slower than the parent mean in these diagnostics; overall CPU point estimates are also slightly higher. No diagnostic latency or CPU gain is claimed.

The first JSON exports displayed only five stack frames, preventing phase attribution. All four existing recordings were re-exported with `jfr print --stack-depth 256`; original exports and their SHA records remain. No profile was rerun. The offline parser identifies actual JUnit timeout-thread stacks and the frozen gate source call sites, excludes source prequeries and later branch checks, and bounds phases using first/last execution samples plus the reported duration. Interior events and uncertain boundary events remain separate; missing stacks are not zero work. Compiler elapsed duration is not CPU time.

In the definite save interiors, parent GC CPU was 1.44/1.32 s and pause wall 116.12/110.97 ms, versus candidate 0.78/0.78 s and 63.93/65.18 ms. Query GC CPU was 0.13/0.13 s versus 0.14/0.12 s. Candidate save boundary-uncertain GC CPU is at most another 0.01 s and does not change the direction; query has no such unassigned boundary CPU. Compiler elapsed values do not show a large candidate-only save/query burden. The independent interpretation and aggregate are `interpretation.txt` and `phase-comparison-depth256.json` in that diagnostic directory. Thus this fixed diagnostic set does not support the hypothesis that the cache moved additional GC work into save/query. It neither establishes the cause of the original slowdown nor proves identical persisted content: the gate deletes its temporary graph, and equal counts/near-equal sizes are not byte-level parity. Raw source pins, recordings, exports and phase intervals remain in `/tmp/graphite-sootup-recovery.6wpE4s/profiles/attempt102-tika-abba/`; parser and coverage rules are in `/tmp/sootup-static-review/attempt102/PHASE-ANALYZER-README.txt`.

**Results and decision:** RETAIN the bounded per-view sub-signature reuse as the next recovery candidate. Ten direct behavioral tests and the broader real-input comparisons pass; the Android pilot reduces allocation by 1.531 GB/op versus parent101, the independently measured live-heap reduction is 468.86 MB after including added cache costs, and expanded Android/default-feature Kotlin CPU improves versus upgraded main. The fixed Tika follow-up does not demonstrate a repeatable save/query penalty or additional GC in those phases. This decision does not erase slower wall samples, higher RSS samples, diagnostic CPU/build costs, or remaining uncertainty. It is not a claim that the user's complete pre-upgrade performance objective has been achieved.

**Remaining verification within the JAR scope:** obtain and inspect the required same-runner benchmark-regression gate for the new commit, and continue resolving the remaining pre-upgrade latency/RSS evidence. Parent101 CI is historical and cannot certify102. APK remains deferred. The complete local evidence index is `/tmp/graphite-sootup-recovery.6wpE4s/attempt102-total-evidence-index.json`.


**Fixed pre-upgrade follow-up (24 fresh JVMs):** after the exploratory measurements above, eight predeclared rounds `A B B A B A A B` compared old `6f498705` (A) with frozen102 (B), rotating Tika/Hive/Kotlin within each round. All24 original4g unprofiled gates passed without retries or discarded samples. Each corpus/revision has four samples. Exact expanded commands, fixture/runtime pins, all raw rows and the independent72-file hash audit are retained in `/tmp/graphite-sootup-recovery.6wpE4s/attempt102-final-primary/`.

| Corpus | Pipeline ms old ->102 | Whole-process CPU s old ->102 | Peak RSS decimal GB old ->102 | Pipeline change | CPU change | RSS change |
|---|---|---|---|---|---|---|
| Tika | 18356.50 ->18444.25 | 68.978 ->72.443 | 5.165 ->4.912 | +0.48% | +5.02% | -4.90% |
| Hive | 31375.50 ->30495.00 | 107.778 ->101.360 | 5.230 ->5.241 | -2.81% | -5.95% | +0.22% |
| Kotlin | 17868.75 ->20330.25 | 64.860 ->70.855 | 5.028 ->4.907 | +13.78% | +9.24% | -2.39% |

Kotlin save was old4132/4123/4244/4166ms versus1028029/7941/7736/4151ms (+67.16% mean). All three slow candidate samples and the final fast sample remain included. Tika/Hive query means rose6.42%/5.22%. These controlled results contradict complete recovery; intermittent slow saves observed elsewhere in old/main do not erase this series.

**Exact-head CI:** unit, Rust and required `benchmark-regression-gate` passed at `58fc89833da43c58208a0758f24ac5f02d3e0237`; [benchmark comment](https://github.com/johnsonlee/graphite/pull/171#issuecomment-6025051872). CI compares upgraded main, not old. Initial Kotlin save9388 ->15733ms (+67.6%) became8459 ->8517ms (+0.7%) in reverse confirmation and was classified `NOISE`. Confirmation also measured Tika save9746 ->16187ms (+66.1%), pipeline49261 ->52413ms (+6.4%); Tika had not been initially flagged and that later row did not re-trigger the policy. Both rounds remain in `/tmp/graphite-ci-head58fc/large-corpus/`. Green CI is not universal performance parity.

**Slow-save localization and compiler-state diagnosis (not primary timing evidence):** a fixed old/102/102/old coarse-stage diagnostic passed with save7585/4160/4186/7861ms and BVGraph4012.526/655.075/639.250/4222.245ms; the other stages together were3561/3494/3537/3628ms. Only13 monotonic timestamps and one final report were added around existing stages; removing those probes restores the source and404 business instructions/control flow. Evidence: `/tmp/sootup-recovery-sources/attempt102-kotlin-coarse-save/`.

A subsequent fixed old ->102 diagnostic with symmetric startup JFR/LogCompilation captured both slow states: save7696/8051ms, BVGraph4049/4493ms. Runtime XML shows each slow worker exiting the exact C2 caller retired by the other worker3.925/4.337s earlier (compile IDs9823/10122). Both workers had already deoptimized the previous C1 caller. JFR repeatedly samples interpreted `diffComp` calls despite replacement C2 callee code being installed. This supports a retired-caller callsite-repair mechanism; actual native callsite destinations/fixup returns were not recorded, so that last causal step remains inferred. Candidate also logged15746 failed stale OSR tasks, not successful compilations or CPU time; old was slow without them. BVGraph GC pauses were0/13.21ms. Exact commands, full recording coverage, original XML/JFR and independent caller/command audits are in `/tmp/sootup-static-review/bvgraph-historical/paired-logcomp-plan/`. Diagnostics do not establish that102 increases the probability of this state or replace the adverse24-run primary series.

**Updated decision:** retain102 as an intermediate candidate with verified allocation/retention benefits; complete pre-upgrade performance recovery remains unproven. No production JVM flags, warmup calls, compression settings or correctness gates were changed in response to the diagnosis. APK remains deferred.

### 2026-10-07 — Attempt 103: Reuse sequential successor buffers during graph persistence

**Status:** REJECTED; production and test changes reverted. Parent is frozen Attempt102 (`attempt102-subsignature-intern`, source commit `58fc89833da43c58208a0758f24ac5f02d3e0237`); candidate is `attempt103-sequential-successors`. Candidate correctness/build checks and the fixed real-input measurements completed. The measured resource tradeoff does not justify retaining the implementation.

**Hypothesis:** eliminate two temporary successor arrays per sequential node access at the GraphStore/WebGraph boundary. The inherited NodeIterator.successorArray allocates an output array and unwraps successors; the inherited node iterator delegates successors to GraphStore's random-access path, which allocates another slice copy. BVGraph then copies the result into its own compression buffer. An iterator-owned reusable array can remove the intermediate arrays and unwrapping without changing graph data or compression settings. This is an independent allocation hypothesis, not a claim to fix the investigated retired-OSR-caller slow mode.

**Change and contracts:** override only `PrecomputedImmutableGraph.nodeIterator(from)` with a sequential iterator. Its scratch grows to the required outdegree without copying stale contents; only the current outdegree prefix is valid. Each copied iterator owns separate scratch. Lazy successors read a captured immutable CSR interval, remaining valid when node traversal advances or scratch is overwritten. Random-access `successorArray(x)` continues returning a distinct copy. Keep stock positioning, exhausted-next exceptions, unsupported remove, inherited node skip, lazy negative-skip bounds/postincrement behavior and empty-iterator behavior. `copy(upperBound)` starts at current+1, clamps against graph size, and can extend a prior copy's bound. Default contiguous splitting, worker count, compression parameters, features and heap limits are unchanged. Correct the earlier inaccurate zero-allocation comments.

**Resource tradeoff:** each live sequential iterator retains an int buffer sized to its largest encountered outdegree until that iterator becomes unreachable. Fewer temporary allocations do not by themselves prove lower live heap, RSS, CPU, save time or end-to-end latency. The upstream compression buffers and their capacity branches remain unchanged.

**Correctness and build history:** build1 failed compilation because the Kotlin subclass lacked explicit `remove()`; the original failure remains in `/tmp/sootup-recovery-sources/attempt103-build1.log`. Add stock-compatible UnsupportedOperationException behavior and an oracle assertion before, during and after traversal; no test or lint threshold was weakened. Build2 completed successfully: **247 webgraph tests, 0 failures, 0 errors, 0 skipped**, detekt, webgraph JMH jar construction and `verifyJmhJarExcludesTests`. Eleven new `PrecomputedGraphIteratorTest` cases compare concrete ordered contents and boundaries against an independent anonymous ImmutableGraph using stock iterators, including copy expansion, splitting, scratch isolation and negative-skip recovery. The existing lazy persisted-backward test now compares every source node's complete incoming Edge multiset on both first mapped access and reopened storage, preserving duplicates, edge payloads and existing persistence checks. These small fixtures provide correctness evidence only.

Build2 command:

```text
./gradlew --no-daemon --console=plain --max-workers=2 -I /tmp/sootup-recovery-sources/compile-attempt103.init.gradle :webgraph:test :webgraph:detekt :webgraph:jmhJar :webgraph:verifyJmhJarExcludesTests :webgraph:recoveryClasspath :webgraph:recoveryJmhClasspath
```

Both attempts used the existing temporary worktree publishing-plugin workaround; all five build files were restored byte-for-byte. Fresh XML and task execution were independently checked, rather than treating stale XML as success. `/tmp/sootup-recovery-sources/attempt103-root-build-audit.json` verifies totals, 11 suite hashes, all five restorations and source/manifest/JMH pins. Full logs, build proof and `/tmp/sootup-recovery-sources/attempt103-test-proof.json` retain the evidence. “JMH verified” here means artifact construction and test-class exclusion, not a completed benchmark or allocation result.

**Frozen provenance:** `/tmp/sootup-recovery-sources/attempt103-freeze-proof.json` records the source hashes and full production-class delta. Candidate snapshot: `/tmp/sootup-recovery-sources/attempt103-sequential-successors-snapshot`; manifest: `/tmp/graphite-sootup-recovery.6wpE4s/attempt103-sequential-successors.json`, SHA-256 `0d2120fa94bc4be5a77473be71906be001c11bf8daa4fdafa569e943260186cf`. Frozen webgraph JMH SHA-256: `44de47ef5ee4e15126e463204f6b2252e1dc409497b54a841c6cfd6a174ac2af`. GraphStore source SHA-256: `03afca71efdb25d82a1b2b4e0dfe53fde2be25613e3f97bad77bd5ab7914cdc6`; iterator-test SHA-256: `4eb8c20014031df3cb49abc14f1cd449a321323ae39c6e78db3bc9f690850948`; GraphStoreTest SHA-256: `714d35c5f2b5d2b3e8d0f12941680497ed2a808964b0be4db2e0f0e9fef74db0`. Parent snapshots remain unchanged. Additional compiled classes changed alongside the two added iterator classes; the complete delta is retained rather than asserting that every unrelated class byte is identical.

**Fixed real-Kotlin pilot:** all four prescribed original4g gates passed in order102 ->103 ->103 ->102, without diagnostic overlays, profiling, additional flags, retries or discarded samples. Fixture Kotlin compiler embeddable2.0.21 has SHA-256 `9fa8cdd1de0dccffe154c997d423ec6b5f53cd6d9177e3a77a9b0de03fb1bc81`. Environment matches102: Apple M3 Max16cores/64GiB, macOS14.3 arm64, OpenJDK17.0.20.1, shared host with team JVMs serialized. Full commands/raw assertions and pins: `/tmp/sootup-recovery-sources/attempt103-gate-pilot/{plan.json,seal.json,results.json}`. Independent raw stdout/time/command audit: `root-independent-pilot-audit.json` and `root-independent-pilot-report.txt` in the same directory.

| Run / revision | Build ms | Save ms | Mapped load ms | Query ms | Pipeline ms | Whole-process CPU s | Peak RSS B |
|---|---|---|---|---|---|---|---|
| 0 / 102 | 12152 | 4283 | 75 | 1055 | 17565 | 66.42 | 5141168128 |
| 1 / 103 | 12175 | 4103 | 77 | 1090 | 17445 | 68.43 | 4793417728 |
| 2 / 103 | 12272 | 7685 | 81 | 1157 | 21195 | 71.98 | 4638310400 |
| 3 / 102 | 12235 | 7822 | 78 | 1129 | 21264 | 72.65 | 4499423232 |

Both variants had one fast and one slow save. Candidate means versus102: save **-2.62%**, pipeline **-0.49%**, CPU **+0.96%**, RSS **-2.17%**, mapped load **+3.27%** and query **+2.88%**. The two paired save reductions were180/137ms, but CPU (+2.01/-0.67s) and RSS (-347.75/+138.89MB) had opposite directions. Only two samples per variant: no stable resource or OSR-fix conclusion. Whole-process CPU/RSS include JUnit correctness checks; pipeline is build+save+median load+query. Branch-definition first access is outside pipeline. All counts, production index and gate assertions passed; counts alone are not full edge identity.

**Additional real JMH and persisted-graph measurements:** all10 authorized jobs passed: two matched E2E commands (two cold forks each), one real Kotlin graph preparation per variant, then three load/query methods per graph. No extra repetitions or selected-out samples. Full expanded commands and raw files are in `/tmp/sootup-recovery-sources/attempt103-jmh-persisted-results/{sealed-plan.json,results.json,completion-report.md}`; the independent audit `root-independent-jmh-audit.json` rechecked48 raw file hashes and independently parsed outer CPU/RSS. The first audit reader assumed hash strings rather than byte-count/hash objects; schema inspection corrected the reader without changing files or rerunning workloads.

The relevant JMH method was `GraphEndToEndBenchmark.kotlinCompiler_build_save_load_query`, single-shot milliseconds, `-wi 0 -i 1 -f 2 -t 1 -foe true -prof gc -jvmArgs -Xmx4g`. Its save does not prepare the call-site string index, unlike the gate. Both commands use the same newly built webgraph JMH harness last in the classpath, behind each variant's frozen runtime; `attempt103-jmh-resolution-proof.json` verifies effective class/metadata provenance. Parent102's historical JMH jar contained sootup build benchmarks, not webgraph benchmarks, so it was not silently substituted. No synthetic performance data was used.

| E2E revision | Fork1 ms/op | Fork2 ms/op | Mean ms/op | Fork1 B/op | Fork2 B/op | Mean B/op | Outer CPU s | Peak RSS B |
|---|---|---|---|---|---|---|---|---|
| 102 | 16189.805 | 15865.545 | 16027.675 | 23191239320.0 | 23039548128.0 | 23115393724.0 | 107.17 | 4346560512 |
| 103 | 15953.549 | 15879.318 | 15916.434 | 22908815696.0 | 23097590416.0 | 23003203056.0 | 115.26 | 4801642496 |

Candidate E2E wall is **-0.694%**, allocation **-0.485%** (-112,190,668B/op), but whole-command CPU **+7.549%** and RSS **+10.470%**. CPU/RSS include the JMH launcher and both forks; they are not CPU/op or isolated save measurements. The fixed small samples do not prove the code intrinsically causes these adverse values, but they do not establish a worthwhile all-resource improvement.

Each variant's existing `AllFixtureBenchmarkGraphPreparation` helper prepared one real Kotlin graph. `LargeCorpusLoadBenchmark.mapped_load` used8g,1fork,1x1s warmup,2x1s measurement. `LargeCorpusQueryBenchmark.mapped_singleHopRelationship` and `eager_singleHopRelationship` used8g,1fork,2x1s warmup,3x1s measurement. All set `corpus=KOTLIN_COMPILER`; query methods traverse real DATAFLOW relationships. These methods had no GC profiler, so no allocation result is claimed for them. Preparation was separate from benchmark samples, and the same per-variant graph was reused unchanged across all three methods.

| Method / revision | Mean ms/op | Outer CPU s | Peak RSS B |
|---|---|---|---|
| mapped-load / 102 | 73.494042 | 4.93 | 650166272 |
| mapped-edge-query / 102 | 0.283669 | 7.56 | 856539136 |
| eager-edge-query / 102 | 0.23858 | 24.32 | 6525222912 |
| mapped-load / 103 | 74.601991 | 4.93 | 643072000 |
| mapped-edge-query / 103 | 0.268203 | 7.36 | 848642048 |
| eager-edge-query / 103 | 0.233099 | 23.18 | 6830211072 |

Mapped load was **+1.508%**; mapped/eager single-hop queries were -5.452%/-2.297%, but eager whole-command RSS was **+4.674%**. Each graph's file pins remained unchanged before/after all load/query measurements. Small method samples, query success and equal gate counts do not prove complete graph equivalence. The proposed extra full semantic-fingerprint phase was not executed after rejection; no main/old expansion, further profiles or favorable-sample search was run. Both real graphs and every raw result remain available locally.

**Results and decision:** REJECT and revert the entire candidate production/test patch. Reusing the buffer reduced allocation only modestly, did not remove the observed slow-save mode, and did not establish CPU/RSS parity; the query gains do not cancel the adverse resource/load observations. No measured benefit is added to the retained implementation's claims. Exact before-revert hashes and restored HEAD bytes are recorded in `/tmp/sootup-recovery-sources/attempt103-revert-proof.json`; rejected sources remain in `attempt103-rejected-source` and the frozen candidate snapshot. The repository returns to102 runtime/test source bytes; only this chronological record and102's later diagnostic/CI evidence are committed for103. Candidate-specific tests passed before rejection; no new runtime change remains that would justify repeating those checks after byte-for-byte restoration. The existing102 required CI remains historical evidence, and the next PR head must satisfy its required checks. Complete pre-upgrade performance recovery remains open; APK remains deferred.

### 2026-10-07 — Attempt 104: Stream mapped CallSite properties into ordered top-k queries

**Status:** PROVISIONAL — measured query improvement; final resource-tradeoff acceptance pending. This record documents the measured candidate; final retention is provisional. Parent runtime is frozen Attempt102 (`attempt102-subsignature-intern`, source commit `58fc89833da43c58208a0758f24ac5f02d3e0237`); repository parent `7f0ecb748ede049f3cfb74975b61fff98cc51a5f` also contains the rejected Attempt103 record, with its implementation reverted. Build2, freezing, real-query correctness, the fixed four-run parent102 pilot and eight parent102/candidate104 JMH jobs completed. The main/pre-upgrade expansion also completed and passed its independent audit. The user prioritizes end-to-end graph-build latency and permits capped small CPU/RSS increases; numeric caps remain undecided, so final acceptance is provisional.

**Hypothesis:** avoid decoding complete CallSite nodes and both method descriptors when the existing ordered LIMIT query needs only caller/callee class/name strings. Four existing Tika diagnostic recordings contain 80/84/79/81 query ExecutionSamples; 46/44/44/47 include `tryFastOrderedPropertyLimit`, and 45/37/45/47 include `NodeSerializer.readMethodDescriptor`. These overlapping counts are not CPU shares or projected savings. The path appears in both101 and102; it does not establish102 causation. The real Tika2.9.2 gate scans1,006,172 CallSites for a two-property ordered LIMIT20. Evidence and limitations: `/tmp/sootup-static-review/attempt102/next-query-opportunity.txt`. This is a query allocation hypothesis, with no claim to repair BVGraph slow saves or complete pre-upgrade recovery.

**Change and contracts:** add independent optional `StreamingStringPropertyProjection` in core Graph.kt, implemented by MappedWebGraphBackedGraph using its existing format-aware raw CallSite string-ID scanner. Admit only single-source, unqualified, untracked requests for exact CallSiteNode and the four supported string properties. Unsupported requests return false before scanning or invoking callbacks. Qualified/multisource, tracked-budget, unsupported-property/type/expression and other unsupported query shapes retain the ordinary evaluator.

Feed rows into the existing QueryPipeline comparator, encounter counter and bounded priority queue. Preserve projected column order, duplicate properties/rows, Unicode string comparisons, ascending/descending ordering and stable ties; retain O(k) results, while scanning O(N). Decode requested strings rather than sorting dictionary IDs. No string cache, global index, all-row list, persisted-format change, query-literal matching, heap change or additional worker is introduced. Per-row temporary values still allocate; savings require measurement.

Cancellation remains explicit even when work accounting is disabled: poll entry, each raw row, scan completion and ranking/return boundaries without consuming budget. Callback exceptions propagate without partial fallback. Thread interruption remains set; the underlying raw scanner may throw base CancellationException, so no universal exception-subclass claim is made. Eligible zero-LIMIT requests check cancellation without scanning; unrelated zero-LIMIT behavior stays unchanged. Tracked requests preserve existing accounting through fallback. A necessary admission correction rejects inline `match.where` in `OrderedPropertyLimitQuery.compile`: rejecting only the raw capability would otherwise re-enter the older ordered helper and bypass that predicate. General WHERE evaluation supplies the result; comparator, provenance and default-budget semantics remain unchanged.

**Correctness design and build history:** fifteen new tests comprise ten Cypher cases and five mapped-storage cases. They assert complete ordered values, ties, Unicode, aliases, duplicated properties, limits, fallback/provenance, exact budget diagnostics, successful/cancelled empty input, zero LIMIT, interruption and exception propagation. Mapped fixtures use noncontiguous IDs, three distinct tuples plus a duplicate, varying caller parameter lengths, index-prepared/unprepared saves and reopenings. A deliberately invalid unrelated descriptor field proves source-access isolation; these small fixtures establish correctness only.

Build1 failed at `MappedStringPropertyProjectionTest.kt:39` because the expected map inferred nonnullable lists while the projection API exposes nullable strings. The minimal correction explicitly uses `mapOf<List<String?>, Int>`; values and duplicate counts are unchanged. Original log: `/tmp/sootup-recovery-sources/attempt104-build1.log`. The executor reported all five temporary publishing-plugin changes restored byte-for-byte. Build2 subsequently completed with exit0. The freeze proof and `/tmp/sootup-recovery-sources/attempt104-independent-freeze-audit.json` record core452 and Cypher1332 tests reused from their successful build1 execution (build2 UP-TO-DATE), plus7 filtered-relationship memory tests and241 webgraph tests executed in build2, all with0 failures/errors/skips. The independent freeze audit PASS establishes2032 unique executed checks across the four tasks, including the15 new cases; do not describe all2032 as freshly rerun in build2. Detekt and JMH construction/isolation checks are recorded by the executor; the export-only invocation is not another test run. Corrected test SHA-256: `39a0945cae4dedd0541ab7bc7a0079fc806652e3b0b3686d0979c36fa9d6f0ca`. The retry source pins include this correction. Frozen label `attempt104-streaming-ordered-properties`, manifest SHA-256 `afa079e66e80245267c4213d2fb6777b80e542c1b314644592d9658b34e30d81`, and test-task execution/reuse distinctions are recorded in `/tmp/sootup-recovery-sources/attempt104-freeze-proof.json`; all five publishing files were restored. Other production/test pins are unchanged.

**Completed parent102 validation protocol:** `/tmp/sootup-recovery-sources/attempt104-validation-plan.md` specifies the full core/Cypher/webgraph tests and detekt, explicit `filteredRelationshipMemoryTest`, JMH builds, test-class exclusion and frozen classpath checks. The fixed original4g Tika gate pilot is102→104→104→102, with original assertions/index preparation and no selected-out samples. Relevant real query JMH compares exact `LargeCorpusQueryBenchmark.mapped_orderedCallSitePropertyLimit` and the IntConstant fallback control,102 then104, two forks,8g and GC allocation profiler. Preserve source warmup/measurement settings, outer CPU/RSS separately, and exact ordered20-row/column correctness against generic/eager evaluation before timing. Existing mapped-load and single-hop query methods provide adjacent-path checks. Both variants use the same pinned real persisted Tika bytes; that fixture's default unprepared index is a distinct protocol from the gate's prepared index.

The repository CypherBenchmark setup is synthetic and cannot establish performance evidence under CONVENTIONS. An external-only adaptation loads the real mapped fixture and closes it at trial teardown, preserving all13 benchmark bodies and class measurement annotations. The compiled external harness passed executable-body comparison for all13 methods and all13 generated drivers invoke teardown; class-origin/readiness checks passed. The initial audit stop remains in `attempt104-real-cypher-harness/compile-proof-initial-audit-stop.json`, and its corrected evidence is in `compile-proof.json`. Source proof: `/tmp/sootup-recovery-sources/attempt104-real-cypher-harness/source-proof.json`. The measured real-input subset is simpleNodeMatch, nodeMatchWithWhere and singleHopRelationship, two forks,8g, GC profiler. This does not migrate CI, alter repository CypherBenchmark or relabel historical synthetic results as real-data evidence.

**Decision:** PROVISIONAL. The target-query improvement is established on the measured real fixture, but final acceptance awaits numeric CPU/RSS tradeoff bounds and an assessment of the limited samples. Positive resource deltas are reported, not automatically disqualifying under the user’s latency-first preference. Separate graph build+save from the query-inclusive pipeline; neither a stable whole-process CPU gain nor complete recovery is claimed. APK is deferred/deprioritized.


**Bounded source review after freeze:** no additional blocking defect was found; this is source review, not additional runtime evidence. Supported node-data versions1/2/3 share the same CallSite node header and method-descriptor prefix: class ID, name ID, parameter count, parameter IDs, return-type ID. The raw helper uses those existing offsets and the same type-index ID sequence as mapped `nodes(CallSiteNode)`, with absolute buffer reads. Version-dependent annotation/artifact/edge changes do not alter this prefix. Dedicated historical-format projection fixtures were not added; legacy compatibility here rests on source-layout review plus existing format handling, not a new three-version runtime matrix. Future layout changes must update this shared raw helper alongside deserialization.

The scan assumes a valid persisted graph, as the existing raw helper does. It intentionally does not validate or decode unrelated descriptor fields, so corrupt unrequested data can produce different failure behavior from full-node decoding; the corruption test demonstrates access isolation rather than a general corruption validator. Existing mapped-buffer size/addressing limits are unchanged. Unsupported capability requests return before callbacks, while the pipeline's admitted optional-capability path performs its own cancellation check even if a backend later declines. Exceptions are not swallowed and a partial scan cannot fall back. Cancellation is cooperative: callbacks precede string decoding and are polled during ranking, but cannot preempt the middle of one front-coded string decode or string comparison.

Each visited row still allocates a values list, row wrapper, LinkedHashMap and ranking wrapper, and requested strings decode per occurrence. Duplicate requested properties therefore decode more than once; only bounded top-k rows remain retained. No cross-query retained cache is introduced. For tiny descriptors or many projected properties, callback/list overhead may offset saved decoding work; measured allocation and CPU are required. Tracked-budget and other unsupported shapes cannot benefit from this raw path. These limits do not establish an optimization failure or success.

Correctness helper source and its review notes are in `/tmp/sootup-recovery-sources/attempt104-query-verifier/`. It loads the same mapped graph and delegates ordinary Graph operations through a wrapper hiding optional capabilities; it never loads an eager graph. Its deterministic report contains complete row sequences and types, with no performance measurements. The executor compiled this unchanged source once against frozen102, then reused the same compiled classes in separate parent102 and candidate104 JVMs with `-Xmx8g`; all three commands exited0. Query counts were20/20/100/99/50, totaling289 rows across the five query shapes. Both mapped/fallback comparisons passed on each runtime, and the two complete reports are byte-identical, SHA-256 `ec873635b9170ab3915be0e2a2b824e785222d0821e171480f2624cdffabf758`. The graph contains3,901,103 nodes and its file pins remained unchanged before/after. `execution-proof.json` retains exact commands, source/compiled hashes and exits; `root-parity-audit.json` independently confirms the report/count equality. These checks validate these query results, not complete graph equivalence, and are excluded from all performance comparisons.


**Fixed real-Tika gate pilot — completed:** exactly102→104→104→102, four original unprofiled4g gate JVMs, all assertions passed, no retries/exclusions. `attempt104-gate-pilot/{plan.json,seal.json,results.json}` retain literal commands and all input/runtime pins; `root-independent-pilot-audit.json` and `root-independent-pilot-report.txt` independently re-read the raw phase/time files. Matching gate class bytes confirm unchanged parent/candidate assertions. The input is Tika2.9.2 SHA-256 `87e06f88c801fcb2beae5f15e707241edb14da468a154ad78be4e31ff982c3da`. Same shared Apple M3 Max/macOS host and OpenJDK17 executable path; heavy team JVMs serialized and host snapshots retained. The runner did not seal the historical JDK executable/release hash, and snapshots cannot rule out every transient external process.

| Run / revision | Build ms | Save ms | Mapped load ms | Query ms | Pipeline ms | Whole-process CPU s | Peak RSS B |
|---|---|---|---|---|---|---|---|---|
| 0 / 102 | 12484 | 4338 | 90 | 1155 | 18067 | 72.9 | 5094686720 |
| 1 / 104 | 12509 | 4727 | 90 | 904 | 18230 | 70.77 | 5019926528 |
| 2 / 104 | 12619 | 4369 | 88 | 922 | 17998 | 70.92 | 4979245056 |
| 3 / 102 | 12587 | 4314 | 89 | 1132 | 18122 | 69.95 | 5007458304 |

Candidate means versus102: query -20.157% (1143.5→913ms), build +0.227%, save +5.132%, mapped load -0.559%, pipeline +0.108%, whole-process wall +0.187%, CPU -0.812%, RSS -1.019%, sampled peak heap -2.859%. Additional branch-definition first-access work rose +10.465%; it is outside the pipeline sum but inside whole-process wall/CPU. Pipeline is build+save+median of five mapped-load samples+query, not total JVM wall. CPU/RSS include JUnit correctness/postvalidation and are not isolated query measurements.

Both query pairs improved (-251/-210ms), but both save pairs were adverse (+389/+55ms), and adjacent pipeline deltas disagree (+0.902%/-0.684%). CPU pairs also disagree (-2.13/+0.97s); two samples per variant do not establish a stable CPU gain. RSS was lower in both pairs, but that limited observation is not a general memory guarantee. Save precedes mapped query execution; this pilot does not establish that the new query path caused the save increase. Equal counts, persisted byte counts and gate assertions do not prove full edge identity or solve the independently observed Kotlin slow-save mode.

**Real persisted query JMH — completed:** all eight fixed jobs passed, seven distinct methods per variant, parent102 then104 for each command group. No extra repetitions or synthetic measurements. Reuse the same17 pinned files of the reduced-config real Tika graph; preparation is outside timing and its default unprepared string index differs from the gate's prepared-index protocol. `attempt104-measurement-plan.json` seals literal commands, common artifacts, source-derived settings and fixture properties; `attempt104-jmh-results/{results.json,comparison.json}` retain every fork/iteration, command and raw artifact hash. Independent audit PASS: `independent-audit.json` and `independent-report.txt` in that directory.

All fork heaps are8g. Ordered queries use two forks,2×1s warmup and3×1s measurement with `-prof gc`; mapped-load uses one fork,1×1s warmup/2×1s measurement without allocation profiling; mapped-singleHop uses one fork,2×1s warmup/3×1s measurement without allocation profiling. The external real Cypher subset uses two forks,3×1s warmup/5×1s measurement and GC profiling. These are average-time jobs. Six or ten iteration observations are not six or ten independent JVMs. The parent-first order is not counterbalanced.

| Method |102 time/op |104 time/op | Time change |102 B/op |104 B/op |
|---|---|---|---|---|---|
| mapped_orderedCallSitePropertyLimit | 517.835234 ms/op | 197.323553 ms/op | -61.895% | 2111660104.000 | 580947278.711 |
| mapped_orderedIntConstantPropertyLimit | 2.042843 ms/op | 1.975680 ms/op | -3.288% | 10621651.269 | 10621625.853 |
| mapped_load | 86.251991 ms/op | 86.300696 ms/op | +0.056% | not collected | not collected |
| mapped_singleHopRelationship | 0.022972 ms/op | 0.023883 ms/op | +3.965% | not collected | not collected |
| nodeMatchWithWhere | 2675.107000 us/op | 2681.187814 us/op | +0.227% | 3498153.330 | 3498155.409 |
| simpleNodeMatch | 64.926307 us/op | 64.691550 us/op | -0.362% | 253880.868 | 253880.862 |
| singleHopRelationship | 49.857070 us/op | 51.032821 us/op | +2.358% | 161400.606 | 161400.628 |

The target's fork means were503.540/532.131ms for102 and204.048/190.600ms for104. Its mean latency decreased61.895% and normalized allocation72.489% (2,111,660,104→580,947,279B/op). This is a measured improvement for that query/fixture/protocol, not for all Cypher queries or the build/save pipeline. IntConstant allocation was effectively unchanged. Retain adverse mapped-load +0.056%, mapped-singleHop +3.965%, Cypher WHERE +0.227% and Cypher singleHop +2.358%; these small controls are not automatically dismissed as noise, and the one-fork controls do not establish stable regressions either.

| Whole-command group | Revision | Wall s | CPU s | Peak RSS B |
|---|---|---|---|---|
| ordered | 102 | 24.54 | 29.53 | 1032110080 |
| ordered | 104 | 23.11 | 28.29 | 1032421376 |
| load | 102 | 3.67 | 4.75 | 615088128 |
| load | 104 | 3.75 | 4.83 | 604127232 |
| relationship | 102 | 5.74 | 8.23 | 876085248 |
| relationship | 104 | 5.76 | 8.31 | 883441664 |
| cypher-sanity | 102 | 50.70 | 59.82 | 863846400 |
| cypher-sanity | 104 | 50.69 | 59.80 | 862994432 |

Normalized allocation B/op is per-operation allocated bytes, not retained heap or RSS. Despite the target allocation reduction, the combined ordered command's RSS rose0.030%; load CPU rose1.684%, relationship CPU/RSS rose0.972%/0.840%. These outer values include startup/setup/warmup, all forked work and multiple benchmark methods per group. Fixed-duration measurements execute different operation counts, so their CPU deltas cannot establish CPU/op or equal-work CPU savings. No allocation measurement exists for the unprofiled load/relationship methods; absence is not zero allocation.

**Completed main/pre-upgrade expansion:** all four appended main JMH commands and all12 fixed original4g corpus gates passed, plus one separate main five-query parity check. No104 JMH repetition was added. `attempt104-next-phase-results/independent-audit.json` independently verified all16 timed jobs, the parity report and raw pins; `independent-report.txt` retains its interpretation. Main's complete289-row/column/type report matches102/104 byte-for-byte. Literal commands, authorization, manifests and common harness/fixture pins are in `attempt104-next-phase-proposal.json`, `attempt104-next-phase-root-release.json` and the results directory's `seal.json`/`results.json`.

Main is frozen c84d7811b52bd11832f97686c15876bbd7f0c244. Pre-upgrade is6f498705009689551c92c6d1ca92f67252ef77c4, using the genuinely frozen `preupgrade-android-proof` copy of its full runtime despite that historical label name. Its artifact SHA multiset equals original preupgrade; it is not a relabeled current runtime. Each variant kept its own original gate. Independent compatibility review found old/current gate source differs only by a KDoc line shift; compiled class bytes differ, so no claim of identical gate bytecode is made across old/current. Shared input/coverage counts do not establish complete graph equivalence.

**Main method comparison:** append-only chronological102→104→main on the same host/session and real persisted fixture; these are not fresh interleaved main/candidate samples. Existing104 forks remain unchanged. Same seven methods,8g heaps, original warmup/measurement settings and profiler/fork distinctions as above. The real-fixture external Cypher setup/teardown and all13 unchanged executable method bodies remain pinned; repository synthetic benchmarks were not used as evidence.

| Method | Main time/op |104 time/op | Time change | Main B/op |104 B/op |
|---|---|---|---|---|---|
| mapped_orderedCallSitePropertyLimit | 539.237702 ms/op | 197.323553 ms/op | -63.407% | 2111660829.333 | 580947278.711 |
| mapped_orderedIntConstantPropertyLimit | 2.018322 ms/op | 1.975680 ms/op | -2.113% | 10621628.880 | 10621625.853 |
| mapped_load | 85.149164 ms/op | 86.300696 ms/op | +1.352% | not collected | not collected |
| mapped_singleHopRelationship | 0.024077 ms/op | 0.023883 ms/op | -0.805% | not collected | not collected |
| nodeMatchWithWhere | 2655.145709 us/op | 2681.187814 us/op | +0.981% | 3498150.606 | 3498155.409 |
| simpleNodeMatch | 63.476817 us/op | 64.691550 us/op | +1.914% | 253880.377 | 253880.862 |
| singleHopRelationship | 49.425090 us/op | 51.032821 us/op | +3.253% | 161400.603 | 161400.628 |

The target is63.407% faster and allocates72.489% fewer bytes/op than appended main. Preserve slower main-relative controls: mapped-load +1.352%, WHERE +0.981%, simpleNodeMatch +1.914%, Cypher singleHop +3.253%. Not every method improved. All raw fork/iteration values, including historical102, remain in `main-jmh-comparison.json`.

| Appended main command | Outer wall s | Outer CPU s | Peak RSS B |
|---|---|---|---|
| ordered | 23.32 | 28.29 | 1029210112 |
| load | 3.76 | 4.82 | 614105088 |
| relationship | 5.73 | 8.22 | 893108224 |
| cypher-sanity | 50.68 | 59.68 | 862420992 |

As above, average-time JMH outer CPU includes unequal operation counts, setup/warmup and all forks. It is not CPU/op or equal-work CPU, and allocation B/op is not retained memory or RSS. Main's later measurement time is a limitation, not grounds to discard unfavorable controls.

**All12 fresh pre-upgrade/104 gates:** exactly the sealed rotated sequence, two samples per variant per corpus; Tika/Kotlin are old→104→104→old and Hive is104→old→old→104. Original4g heaps, input properties, features and correctness assertions remained unchanged; no profiles, extra samples, retries or excluded slow runs. Graph-build latency below means **build+save**, excluding mapped load and Cypher queries. The original pipeline metric includes build+save+median mapped load+query. Whole JVM wall and CPU also include correctness and post-pipeline first-access work.

| Ordinal | Corpus | Variant | Build ms | Save ms | Load ms | Query ms | Pipeline ms | Whole CPU s | Peak RSS B |
|---|---|---|---|---|---|---|---|---|---|
| 0 | Tika | old | 12829 | 4430 | 91 | 1169 | 18519 | 68.84 | 5123784704 |
| 1 | Hive | 104 | 22343 | 6011 | 135 | 1065 | 29554 | 101.29 | 5401477120 |
| 2 | KotlinCompiler | old | 12674 | 7857 | 75 | 1108 | 21714 | 68.91 | 5130240000 |
| 3 | Hive | old | 23440 | 7022 | 134 | 1470 | 32066 | 105.97 | 5176918016 |
| 4 | KotlinCompiler | 104 | 12162 | 4190 | 76 | 810 | 17238 | 69.72 | 4551770112 |
| 5 | Tika | 104 | 12302 | 4313 | 91 | 894 | 17600 | 68.86 | 4981555200 |
| 6 | KotlinCompiler | 104 | 12221 | 4341 | 76 | 815 | 17453 | 69.2 | 5043798016 |
| 7 | Tika | 104 | 12709 | 4500 | 100 | 954 | 18263 | 70.41 | 5032132608 |
| 8 | Hive | old | 23392 | 6388 | 135 | 1470 | 31385 | 94.61 | 5145214976 |
| 9 | Tika | old | 12566 | 4538 | 89 | 1206 | 18399 | 72.73 | 5190991872 |
| 10 | Hive | 104 | 22347 | 6230 | 134 | 1098 | 29809 | 103.78 | 5406195712 |
| 11 | KotlinCompiler | old | 12305 | 4191 | 78 | 1138 | 17712 | 66.96 | 5114855424 |

| Corpus | Mean build+save s, old→104 | Change | Mean pipeline s, old→104 | Change | Mean whole CPU s, old→104 | Change | Mean RSS B, old→104 | Change |
|---|---|---|---|---|---|---|---|---|
| Tika | 17.1815→16.9120 | -1.569% | 18.4590→17.9315 | -2.858% | 70.785→69.635 | -1.625% | 5157388288→5006843904 | -2.919% |
| Hive | 30.1210→28.4655 | -5.496% | 31.7255→29.6815 | -6.443% | 100.290→102.535 | +2.239% | 5161066496→5403836416 | +4.704% |
| KotlinCompiler | 18.5135→16.4570 | -11.108% | 19.7130→17.3455 | -12.010% | 67.935→69.460 | +2.245% | 5122547712→4797784064 | -6.340% |

All six adjacent query comparisons and pipeline comparisons improved, but these old→104 values measure the entire retained chain since pre-upgrade, not104 alone. The parent102 pilot isolates104 more closely: build+save rose16861.5→17112ms (+1.486%), pipeline +0.108%, while aggregate query time fell20.157%. That pilot's save variation and opposing CPU-pair directions remain part of the record; no isolated104 build/save improvement is claimed.

Specific drawbacks and uncertainty remain. Tika mean of per-run mapped-load medians rose6.111% and branch-definition first-access2.394%. Hive CPU mean rose2.239% with opposite pair directions (-4.416%/+9.692%); RSS rose in both pairs (+4.338%/+5.072%), mean4.704%. Kotlin CPU rose in both pairs (+1.175%/+3.345%), mean2.245%, and branch-definition first-access rose7.610%. Branch-definition access is excluded from pipeline but included in whole-process CPU/wall. Sampled peak-heap means changed -5.014%/+1.207%/-4.916% for Tika/Hive/Kotlin. `gate-comparison.json` and the independent report retain all means, ranges and pairs.

Kotlin's old saves were7.857/4.191s versus104's4.190/4.341s; the retained old slow save materially drives the mean build+save/pipeline advantage. In the fast-save adjacent pair,104 pipeline still improved1.462% while CPU rose3.345%. Two samples per side cannot establish a stable slow-save frequency or show that104 repairs that mode. Hive's mean RSS increase is close to the discussed—but not accepted—5% example cap, and one pair exceeds it; any acceptance rule must specify the aggregation/confidence basis rather than retroactively selecting a favorable statistic.

**Provisional outcome under clarified user priorities:** the measured CallSite ordered-query gain is established versus both102 and upgraded main, with2,032 unique executed module checks and full289-row real-query parity across102/104/main. The user prioritizes end-to-end graph-build latency and allows bounded small CPU/RSS tradeoffs. Numeric caps remain pending; therefore the evidence supports provisional retention for review, not finalized acceptance, zero-regression compliance or completion of the overall recovery goal. Whole-chain old→104 build+save results are encouraging within this small sample, with the Kotlin tail caveat and Hive/Kotlin resource costs stated above. Further optimization will focus on graph construction and saving; numeric resource acceptance remains pending. APK remains deferred/deprioritized.

**Construction-first evaluation boundary:** the user explicitly allows bounded small CPU and peak-memory increases in exchange for lower end-to-end construction latency. Numeric limits remain pending; no5% limit is approved. CPU means total user+system seconds per construction command, and memory means process peak RSS, not allocation/op or the sum of nonsimultaneous heap-pool peaks. Existing gate resource numbers include post-save queries/assertions and cannot directly certify construction-only limits. A separate default-feature helper protocol is being prepared: load, the CLI’s actual node scan, prepared save with two compression threads, and explicit source close, followed by persisted-graph verification in another process. It mirrors CLI construction but is not the literal CLI invocation: status messages are suppressed and the helper explicitly closes the source, as the prior quiet harness did. Preserve all features and graph semantics, cancellation/budget behavior and stability. Query gains remain separate from construction gains; no unrelated performance regression is implicitly accepted. The pending numeric preference does not block correctness checks or measurement preparation.

**Later default-feature Kotlin construction comparison — completed:** this new protocol measures full-feature construction separately from mapped reload, queries and correctness checks. A is frozen pre-upgrade6f498705; B is frozen104, now committed as `87da90bd4d6c80363f12d4b7f54626529a907372`. It measures the cumulative retained frontend chain, not104 query causation. Same real Kotlin compiler2.0.21 JAR (SHA-256 `9fa8cdd1de0dccffe154c997d423ec6b5f53cd6d9177e3a77a9b0de03fb1bc81`), Apple M3 Max/macOS/OpenJDK17 host and default features: call graph, annotations and cross-method functional dispatch enabled, CHA, Mmap builder, quiet callback, save with two compression threads and prepared CallSite index. Both use8g and no extra JVM/profiling/GC flags. This is distinct from the earlier reduced-feature4g gates.

The common Java helper follows load → CLI node scan → prepared save → explicit source close. It suppresses CLI status messages and calls close explicitly, so it is not a literal CLI invocation; calling close does not establish memory reclamation. Path/config/report setup precedes the continuous timer; scalar timing output follows it. Whole-command CPU and peak RSS include JVM startup/setup/reporting, but exclude later verification. CPU is user+system seconds, and each RSS value is that process’s high-water mark. No sum of heap-pool peaks is treated as RSS. Literal commands, source/runtime/JDK/fixture pins and raw files are in `/tmp/sootup-recovery-sources/cli-construction-protocol/`; helper source SHA-256 `d728b511505147f468586980996f1425733766929e1dc38b970a6fe144adf74e`.

Command form: `/usr/bin/time -l "$JAVA" -Xmx8g -cp "$COMMON_HELPER:$FROZEN_CP" CliConstruction "$KOTLIN_JAR" "$NEW_OUTPUT"`. The helper was compiled once against the old public API and used unchanged for both variants. Exactly eight fresh JVMs ran in ABBA BAAB order; all samples, including the slower last candidate save, remain below. No repetitions or exclusions were added.

| Ordinal / revision | Build s | Node scan s | Save s | Continuous construction s | Whole CPU s | Peak RSS GB |
|---|---:|---:|---:|---:|---:|---:|
| 0 / old | 112.580 | 0.308 | 6.630 | 119.518 | 191.62 | 7.461 |
| 1 / 104 | 29.614 | 0.467 | 6.770 | 36.852 | 101.53 | 9.580 |
| 2 / 104 | 30.345 | 0.345 | 6.786 | 37.476 | 103.65 | 9.586 |
| 3 / old | 111.742 | 0.307 | 6.477 | 118.526 | 191.88 | 7.702 |
| 4 / 104 | 29.601 | 0.428 | 6.743 | 36.772 | 102.17 | 9.227 |
| 5 / old | 111.379 | 0.399 | 6.539 | 118.316 | 198.91 | 7.721 |
| 6 / old | 110.874 | 0.349 | 6.628 | 117.851 | 186.60 | 7.247 |
| 7 / 104 | 29.842 | 0.342 | 11.186 | 41.370 | 106.30 | 6.437 |

Mean continuous construction is118.553→38.118s (**−67.848%**), whole-command CPU192.253→103.413s (**−46.210%**), and mean process-peak RSS7.533→8.708GB (**+1.175GB / +15.596%**). RSS medians are adverse by24.033%, and the maximum observed per-variant RSS rises24.163%. These are descriptive four-sample summaries, not tail-frequency or population guarantees. Three candidate RSS samples are9.227–9.586GB, while the last is6.437GB and its save rises to11.186s versus6.743–6.786s for the other candidate saves. Neither sample is discarded, and their co-occurrence does not prove causation. The primary construction speedup does not establish that saving improved or that the existing slow-save mode was fixed.

**Correctness and scope:** all eight builds passed source counts and pin checks. Thirty separate untimed JVMs validated two stored references and every new output using per-variant structure/connected-node fingerprints, metadata/API fingerprints, and five full ordered query row/column/type comparisons against generic fallback. All passed, with no retry or omitted failure. Current-reference fingerprints also match the originally stored upgraded-main oracle; recomputation did not redefine expected coverage. Old output has4,657,648 nodes /2,173,010 CallSites; current output has4,744,132 /2,251,811. Each new graph matches its own appropriate reference; no old/current full-graph identity or equal-work claim is made. All18 files of each new graph and both reference graphs remained unchanged through verification. Quiet callback suppression means exit0 alone cannot establish that no adapter-caught warning occurred; the output checks are the bounded semantic evidence.

Independent root audit re-read all raw timing files, all30 verification reports, immutable reference fingerprints and144 new graph-file hashes. Evidence: `execution/root-independent-audit.json`, `root-output-pin-audit.json`, `completion.json`; raw `results.json` SHA-256 `c97c7d1440bb1f616e3d651bbf05e9fe5417534ab5a234f8104e2acf43b550e7`. All figures refer to this new protocol only; historical quiet/profile/gate results are not pooled.

**Tradeoff conclusion:** default-feature Kotlin construction is substantially faster and uses less total CPU in this fixed series, while its observed RSS increase is material. It is not accepted as a small bounded increase merely because latency improved. Numeric caps remain pending, and overall recovery remains open. A source review identifies the pre-save node scan as avoidable work that increased used heap by about0.8GB in historical main/102 samples; that motivates a separate count-metadata experiment, not a claim that it will eliminate the RSS increase.


**Required CI at104 head — failed:** benchmark workflow37550509552 compares upgraded main `c84d7811` with `87da90bd`. JVM unit workflow37550509570 and Rust correctness workflow37550509412 passed. The three JAR gate comparisons contain21 rows, all passing without confirmation/noise clearance: build+save Tika34205→33876ms (−0.962%), Hive56962→54495ms (−4.331%), Kotlin33171→31818ms (−4.079%). Adverse save/load values remain in the benchmark comment. The aggregate required check nonetheless fails because `rust.fixture64.schema-relationship-histogram[selectivity=schema]` rises126.445→176.117ms (+39.2835%) and reverse confirmation rises145.382→193.949ms (+33.4065%), exceeding15% and1ms both times. All39 Rust rows retain matching result counts/digests; only this row blocks. Backend/CLI Git trees, Cargo files, workflow and comparator are identical between base and candidate, but compiled binary identity is unproven. Source equality does not erase observed timing or justify clearing the gate. Diagnosis remains open. Archived original logs, artifacts, source identities and independent audit: `/tmp/graphite-ci-head87da90bd/failure/failure-audit.json`; [benchmark comment](https://github.com/johnsonlee/graphite/pull/171#issuecomment-6025051872).


### 2026-10-07 — Attempt 105: Diagnose the BVGraph compressor's initial successor ring

**Hypothesis and scope:** the previously observed slow-save mode may depend on the compressor's initial successor-array capacity. This diagnostic changes only the initial compressor-local IntArrayList capacity from1024 to0, allowing ordinary growth. Reader buffering, compression flags, thread count and persisted format remain unchanged. Slow saves were observed with pre-upgrade and retained candidates; no claim attributes the mode specifically to SootUp3.

**Implementation:** external experimental overlay only, never repository production or a shipping dependency. WebGraph3.6.12 original JAR SHA-256 `8daddd3881aecc7095643936c9e16d825cbe0e431df507519e36df68d06444ef`. The12,523-byte CompressionThread class differs at exactly one byte (offset9725,04→00), changing SIPUSH1024 to0 at bytecode offset187 in call(). Original class SHA-256 `e06967ab9f6f7f353499b736b995b6c9dfd401b17bbd7d585e81e5a1cc2f0e2d`; overlay `4922818822be7c19b0dfc6af24a3058ac358443691dc3ed04bda0b2534494484`. Original artifacts are untouched. A production change would require an upstream release or reproducible source fork; this inner-class overlay is not a release approach.

**Correctness:** stock and overlay each pass40 synthetic correctness configurations (empty graphs, isolates, null splits,1023/1024/1025 ring growth, references, edge labels, window0/7, threads1/2, max-reference0/3). All193 compressed/decoded/semantic-property files match byte-for-byte. Separately, the same241 existing webgraph test identities pass on stock and overlay in11 fresh4g processes per arm:482 executions,0 failures/errors/skips. These directJUnit runs omit Gradle bootstrap/Kover and establish no performance result. Each execution records actual compressor origin/class hash; the outerBVGraph remains stock. Root independently checked all27 commands,22 XML reports and output hashes; evidence `/tmp/sootup-recovery-sources/attempt105-capacity-zero-preparation/execution/root-complete-correctness-audit.json`.

**Real-data save-only protocol:** existing persisted Kotlin and Tika CSR inputs are extracted untimed with the stock decoder. Kotlin dimension3,292,215/arcs3,785,858; Tika3,901,104/arcs4,353,588. Dimensions include theID0 gap and are not nonnull node counts. Both use the actual frozen104 GraphStore PrecomputedAdjacency/PrecomputedImmutableGraph classes, not Attempt103's rejected iterator. Default BVGraph compression flags and two workers; one store call per fresh4g JVM. Eight fixed processes rotate KotlinA,TikaB,TikaA,KotlinB,KotlinB,TikaA,TikaB,KotlinA; A is stock and B adds only the overlay. Two separate untimed group verifiers inspect every adjacency, graph/offset bytes and semantic properties. No frontend construction, mapped-query timing, profiler, forcedGC, warmup or optional repetition occurs. Whole-command CPU/RSS includes startup and loading the sealed CSR and must not be labeled pure compressor CPU/RSS. Literal commands and pins: `/tmp/sootup-recovery-sources/attempt105-persisted-save-diagnostic/plan.json` (SHA-256 `2d7ec886a4621416bc700c9154136cc6152595f35e6766f0627c1697b7dee399`); helper SHA-256 `9cbf34854442d9c4c93920d83fcf80483a90b02ee487e56e1b233fea10c9b204`.

**Measurements — completed:** all eight measured JVMs and both untimed verification groups passed, with no retries or exclusions. Every output passed full adjacency verification; graph/offset bytes and semantic properties matched its stock anchor. Actual origins and hashes of outerBVGraph, innerCompressionThread and both GraphStore adjacency classes matched the sealed commands. Both input and existing output pins remained unchanged. Environment is the same M3 Max16core/64GiB/macOS14.3/OpenJDK17.0.20.1 shared host; heavy JVMs serialized. Command form: `/usr/bin/time -l "$JAVA" -Xmx4g -cp "$FROZEN_104_CP_WITH_OPTIONAL_OVERLAY_AND_COMMON_HELPER" PersistedCompression measure "$PREPARED_CSR" "$NEW_OUTPUT" "$EXPECTED_COMPRESSOR_ORIGIN" "$EXPECTED_CLASS_SHA256"`. Exact arrays and classpath order are retained in the plan and execution results.

| Ordinal / corpus / arm | Store s | Store process CPU s | Whole CPU s | Peak RSS MB |
|---|---:|---:|---:|---:|
| 00-Kotlin-A | 0.618090 | 1.421106 | 1.80 | 328.892 |
| 01-Tika-B | 0.655688 | 1.526020 | 1.92 | 372.244 |
| 02-Tika-A | 1.058801 | 2.094668 | 2.51 | 371.966 |
| 03-Kotlin-B | 0.666971 | 1.531043 | 1.91 | 341.049 |
| 04-Kotlin-B | 0.632585 | 1.441817 | 1.83 | 329.171 |
| 05-Tika-A | 0.728994 | 1.689858 | 2.09 | 373.866 |
| 06-Tika-B | 0.705152 | 1.717912 | 2.10 | 377.389 |
| 07-Kotlin-A | 4.080867 | 5.157582 | 5.54 | 338.084 |

| Corpus | Mean store s stock→overlay | Change | Mean whole CPU s | Change | Mean peak RSS MB | Change |
|---|---:|---:|---:|---:|---:|---:|
| Kotlin | 2.349478→0.649778 | -72.344% | 3.670→1.870 | -49.046% | 333.488→335.110 | +0.486% |
| Tika | 0.893898→0.680420 | -23.882% | 2.300→2.010 | -12.609% | 372.916→374.817 | +0.510% |

The stock Kotlin samples span0.618–4.081s, whereas the two overlay samples are0.633–0.667s. This small diagnostic reproduced one slow stock store and no slow overlay store. It does not estimate tail frequency or establish that all slow modes are fixed. Compared with the fast stock Kotlin sample, both overlay stores are slightly slower; the mean advantage is driven by the retained slow sample. Tika also favors the overlay within this two-sample series. Mean process peak RSS rises about0.5% in both corpora; these small save-only processes do not resolve the default-feature Kotlin construction RSS increase.

**Decision:** retain as an external diagnostic lead; no production dependency, source, heap/feature change or binary overlay is shipped. An upstream/source-build integration and full construction comparison are prerequisites for any production proposal. The authoritative required CI at104 remains failed as documented above. This isolated parent104 comparison is not a new main/pre-upgrade end-to-end comparison and does not establish whole-product recovery. Raw ten-job evidence and independently parsed statistics: `/tmp/sootup-recovery-sources/attempt105-persisted-save-diagnostic/measurement-execution/{results.json,root-metrics-audit.json}`. Original source/plan/preparation/correctness failures and pins remain preserved.


### 2026-10-07 — Attempt 106: Count Mmap records without decoding every node before saving

**Hypothesis:** the CLI scans and deserializes every node merely to print its count before saving. Historical full-feature Kotlin scans added about0.8GB used heap, but do not prove this scan caused the observed RSS increase. Use existing Mmap node-type index lengths to obtain the exact all-record count and avoid this scan. The count deliberately includes repeated IDs, matching allNodes; the ID lookup index resolves the last record and cannot supply the count.

**Changes:** MmapGraph.nodeCount(Node.class) returns the sum of record-index lengths; subtype counts remain unavailable and preserve existing lookup behavior. BuildCommand uses count metadata when available, including authoritative zero, and otherwise performs its original scan. Exposing this capability also enables existing Cypher metadata paths, so tryFastNodeCount rejects inline MATCH predicates before they could bypass filtering. No persisted format, source frontend, feature switch, heap, thread or compression setting changes. Metadata counts consume zero actual node-visit work; this follows existing capability accounting and is not a claim of identical old scan-budget counters. Entry cancellation remains checked before metadata access.

**Correctness:** five new core tests cover empty/sparse IDs, repeated same/different-type IDs and a deterministic no-payload-read proof. Three new CLI-helper tests cover metadata, zero and fallback; the existing build CLI test now compares its reported count to the saved graph. Four Cypher tests cover count(*)/count(n), inline/explicit filtering, cancellation/timeout reasons, qualified provenance and graph discovery. Build1 passed core457, Cypher1336, filtered-memory7 and webgraph241 checks, then failed query-test compilation on two generic assertEquals calls. Explicit Class<*>/List<Node> assertion types preserve all expected values; build2 runs all26 query tests successfully. Thus2067 unique checks passed with0 failures/errors/skips; the2041 successful build1 checks are reused, not claimed as rerun. Detekt and both JMH artifact-isolation checks passed. Original error and packaging-path freeze stop remain archived; the latter occurred after successful compilation and was corrected to the actual slim query JAR without another build.

**Frozen candidate:** `attempt106-mmap-node-count`, manifest SHA-256 `c2046b95009a19f5f294e3d528b2d3ec5762fe6346b47199c0f9da92b1bfa8de`; source parent production is104/87da90bd. Binary deltas are limited to MmapGraph and its line/debug-affected nested classes plus QueryPipeline; frontend/webgraph production classes match104. Query CLI artifact is separately pinned. Evidence `/tmp/sootup-recovery-sources/attempt106-freeze-proof.json` and `/tmp/sootup-static-review/attempt106/`.

**Fixed default-feature construction protocol:** eight fresh8g JVMs in104→106→106→104 /106→104→104→106 order use the same full Kotlin compiler2.0.21 input/default features/save parameters and quiet helper boundaries as104's later construction series. The one common helper is compiled once against104; it changes only the count phase to metadata with identical scan fallback, and records the chosen mode after timing. Expected node count4,744,132 in both arms; A must scan and B must use metadata. This helper is not the literal CLI command. No original old/104 samples are pooled as fresh controls. Twenty-four separate correctness processes compare every graph's structure,13 metadata/API fingerprints and five full query results to the already-proven current reference. Output pins cover every file before and after checks.

Literal commands: `/tmp/sootup-recovery-sources/attempt106-construction-proposal/commands.resolved.json`, SHA-256 `c90e1351f36b172a7fad03303b9066e7445ff7ccaa55a9f183e1292925a531cc`; preparation seal `48e047708764c1fb6314e7b92881121f39efee4a91f818145eaf78fdb1b682be`. Independent readiness audit verifies657 pins, six complete directory inventories and candidate-only substitutions in8+24+2 commands. The initial executor release-file race stopped at preflight with jobs=[] before any JVM; its empty failed directory is preserved and no performance sample was replaced.

**Construction measurements — completed:** all eight fixed measurements passed the expected count/mode and input pins. These are fresh104/106 controls only. Environment and default8g feature/save configuration are identical to the prior full-construction protocol.

| Run | Mode | Continuous s | Build+save s | Count s | Save s | Whole CPU s | RSS MB |
|---|---|---:|---:|---:|---:|---:|---:|
| kotlin-count106-0-A | scan | 41.436124 | 41.107156 | 0.328965 | 11.291740 | 113.82 | 9578.086 |
| kotlin-count106-1-B | metadata | 36.285041 | 36.285010 | 0.000026 | 6.905401 | 101.16 | 8511.259 |
| kotlin-count106-2-B | metadata | 41.024079 | 41.024054 | 0.000021 | 11.166183 | 103.66 | 8526.692 |
| kotlin-count106-3-A | scan | 36.745863 | 36.415795 | 0.330064 | 6.461291 | 97.44 | 7360.152 |
| kotlin-count106-4-B | metadata | 41.424459 | 41.424429 | 0.000025 | 11.442687 | 107.72 | 9595.978 |
| kotlin-count106-5-A | scan | 36.455874 | 36.112903 | 0.342968 | 6.511711 | 104.06 | 8586.805 |
| kotlin-count106-6-A | scan | 41.440858 | 41.077563 | 0.363287 | 11.104448 | 107.96 | 9588.556 |
| kotlin-count106-7-B | metadata | 41.021711 | 41.021690 | 0.000018 | 11.007227 | 106.89 | 9581.724 |

| Metric | A mean / median [range] | B mean / median [range] | Mean B−A absolute | Mean B−A % |
|---|---:|---:|---:|---:|
| continuous.wallSeconds | 39.019680 / 39.090994 [36.455874, 41.440858] | 39.938822 / 41.022895 [36.285041, 41.424459] | +0.919143 | +2.356% |
| buildPlusSave.wallSeconds | 38.678354 / 38.746679 [36.112903, 41.107156] | 39.938796 / 41.022872 [36.285010, 41.424429] | +1.260442 | +3.259% |
| wholeCpuSeconds | 105.820000 / 106.010000 [97.440000, 113.820000] | 104.857500 / 105.275000 [101.160000, 107.720000] | -0.962500 | -0.910% |
| peakRssMB | 8778.399744 / 9082.445824 [7360.151552, 9588.555776] | 9053.913088 / 9054.208000 [8511.258624, 9595.977728] | +275.513344 | +3.139% |
| build.wallSeconds | 29.836056 / 29.884960 [29.601192, 29.973115] | 29.808421 / 29.919807 [29.379609, 30.014462] | -0.027635 | -0.093% |
| cliNodeCount.wallSeconds | 0.341321 / 0.336516 [0.328965, 0.363287] | 0.000023 / 0.000023 [0.000018, 0.000026] | -0.341298 | -99.993% |
| savePrepared.wallSeconds | 8.842298 / 8.808080 [6.461291, 11.291740] | 10.130375 / 11.086705 [6.905401, 11.442687] | +1.288077 | +14.567% |
| savePrepared.processCpuSeconds | 16.958428 / 15.842973 [14.179359, 21.968405] | 19.407703 / 19.230024 [18.173097, 20.997666] | +2.449275 | +14.443% |

| Adjacent pair | Continuous B−A s (%) | Whole CPU B−A s (%) | RSS B−A MB (%) |
|---|---:|---:|---:|
| [0, 1] | -5.151084 (-12.431%) | -12.660000 (-11.123%) | -1066.827776 (-11.138%) |
| [2, 3] | +4.278216 (+11.643%) | +6.220000 (+6.383%) | +1166.540800 (+15.849%) |
| [4, 5] | +4.968585 (+13.629%) | +3.660000 (+3.517%) | +1009.172480 (+11.753%) |
| [6, 7] | -0.419146 (-1.011%) | -1.070000 (-0.991%) | -6.832128 (-0.071%) |


**Decision:** reject the count-scan optimization for this construction recovery goal. The0.3413s counting phase disappears, but continuous construction is slower by2.356% on mean and4.942% on median; RSS mean is higher by3.139% while median is essentially unchanged (−0.311%). CPU mean falls only0.910%. Opposing pair directions and slow saves on both arms prevent a claim that skipping the scan caused a different tail frequency. No end-to-end or RSS benefit is demonstrated, so this local micro-optimization does not satisfy the user’s construction-first priority. All samples are retained. Numeric resource caps remain unapproved and are not needed to claim rejection here.

**Final correctness and method controls — completed:** all24 untimed output verifiers passed structure,13 metadata/API fingerprints and all five ordered query reports against unchanged reference-B; all144 graph files and construction/report artifacts remained identical before/after verification. Both real-Tika Cypher JMH groups passed. Common adapted harness, same pinned3,901,103-node graph,104→106 order,2forks/method,3×1s warmup,5×1s measurement,8g and GC profiler; synthetic fixtures are not performance evidence. These persisted-backend controls already support count metadata and do not benchmark the new in-memory count capability.

| Exact CypherBenchmark method | 104 us/op | 106 us/op | Change | Allocation B/op104→106 |
|---|---:|---:|---:|---:|
| `countStar` | 0.675231 | 0.645561 | -4.394% | 1826.8→1824.0 |
| `nodeMatchWithWhere` | 2841.040476 | 2808.670761 | -1.139% | 3498184.1→3498181.1 |
| `simpleNodeMatch` | 66.774058 | 66.083126 | -1.035% | 255480.9→253880.6 |

Command form: `/usr/bin/time -l "$JAVA" -cp "$FROZEN_PRODUCTION_CP:$COMMON_REAL_CYPHER_HARNESS" org.openjdk.jmh.Main '^io\.johnsonlee\.graphite\.cypher\.CypherBenchmark\.(countStar|nodeMatchWithWhere|simpleNodeMatch)$' -f 2 -prof gc -foe true -rf json -rff "$OUT" -jvm "$JAVA" -jvmArgsAppend "-Xmx8g -Dcypher.benchmark.graph.path=$TIKA_GRAPH"`. Exact command arrays and all per-fork samples are preserved. Fixed-duration command CPU/RSS is not equal-work CPU/op. These small query gains do not offset the worse primary construction/RSS means and do not change the rejection decision.

**Reverted:** all three production files, the modified CLI test and three added test files are restored/removed; current retained production returns to104 before the next independent frontend-lifetime experiment. Exact rejected source is preserved at `/tmp/sootup-static-review/attempt106/rejected-source/`, with verified restore proof. This is a performance-based rejection, not a correctness failure. The source/API capability and inline predicate guard are not retained as unrelated changes. Readiness, failed empty preflight, all8+24+2 jobs and original raw evidence remain available; terminal results SHA-256 `e53934a9b4282c7c1fae0944acc0a23e7853118ddf41a2a553762d58d5d1e904`. Independent raw construction/report audits are in `/tmp/sootup-static-review/attempt106/`. No additional repetitions or excluded samples were used.


**Subsequent user acceptance decision (2026-10-07):** the user explicitly confirmed the proposed CPU and process-peak RSS limits of **+5% each relative to pre-upgrade**, with end-to-end graph construction latency as the priority and functional correctness/stability nontradeable. This supersedes earlier “numeric caps pending” notes, which record the state at those measurements. CPU is total construction-command user+system seconds; memory is process peak RSS. All preplanned raw/mean/median/range results remain reportable; the confirmation does not approve selecting a favorable statistic after seeing the data. Existing104 full-feature Kotlin meanRSS+15.596% and observed-maxRSS+24.163% do not meet the confirmed cap. A104→107 comparison can isolate the next change but cannot alone establish acceptance against pre-upgrade. APK remains deferred.

The user additionally confirmed **maximum heap8GB as a hard, nontradeable ceiling**, retaining the existing `-Xmx8g` limit. No experiment may increase the heap limit for latency, CPU or correctness. Smaller4g controls remain within the ceiling. Process peakRSS includes memory outside the Java heap and is evaluated separately against the confirmed+5% limit. Attempt107’s proposed8g construction and4g JMH settings already respect this ceiling.

The user subsequently extended the same priorities and limits to **graph loading and query execution**, independently of construction. For each affected operation and workload, end-to-end latency takes priority, CPU and peakRSS each have a +5% allowance against the matching pre-regression baseline, and correctness, stability and the8GiB heap ceiling remain nontradeable. Improvements in one operation do not offset regressions in another; deferred loading work must remain visible in query measurements. This policy is recorded in repository-root `AGENTS.md`. Construction-only or fused pipeline measurements do not by themselves establish separate loading/query acceptance.

### 2026-10-07 — Attempt 107: End loader-owned analysis before graph finalization

**Status:** REJECTED; both production files and the baseline-ID change are restored, and the candidate-specific test is removed. All eight construction runs,24 separate output verifiers and three JMH groups completed successfully. Correctness passed, but the candidate did not establish the confirmed RSS limit or an independent end-to-end benefit.

**Hypothesis:** returning a prepared builder from a separate loader helper may allow loader-owned SootUp analysis objects to become collectible before final graph indexes are allocated. Source lifetime is not proof of JVM liveness or lower RSS. Public `SootUpAdapter.buildGraph()` remains a compatibility wrapper; the loader finalizes the original builder after preparation. Analysis, starting callback, resource handoff, build and finished callback retain their order and failure behavior. No explicit GC, cache clearing, resource closure, feature suppression, heap change or compression change is introduced. Escaped extension contexts may still retain the view.

**Correctness before measurement:** 489 fresh SootUp tests and 241 fresh webgraph tests passed (730 total, zero failures/errors/skips), with Detekt and JMH artifact isolation. Six focused tests cover original-builder identity even when `setResources` returns another builder, graph/resource content, escaped extension context/body access, deferred finalization and callback/resource/build failure ordering. The first build stopped on the existing complexity-baseline method name after its body moved; only that baseline ID was renamed, with the same218 entries and unchanged thresholds. Fresh build2 results establish the test pass; stale build1 XML was not credited.

Frozen candidate: `attempt107-loader-preparation-boundary`, manifest SHA-256 `fd8d4b832a3bf9a48bf192a529e87b1d06c4503e86ed156b4a6c63cf19104fb5`. Core, Cypher and webgraph runtime bytes equal104; SootUp changes are confined to the two edited classes and their generated nested classes. Independent freeze/readiness evidence is in `/tmp/sootup-static-review/attempt107/`.

**Fixed real-Kotlin construction comparison:** A is frozen pre-upgrade6f498705; B is107. Exactly eight fresh JVMs in `ABBABAAB` order, default features, CHA, Mmap builder, quiet callback, prepared save with two threads and matching `-Xmx8g`. The original compiled always-scan `CliConstruction` helper measures load → CLI node scan → save → explicit source close continuously. This mirrors CLI construction but is not a literal CLI invocation; Mmap close is a no-op. No query, profiler, forced GC or output hashing runs inside construction. Whole-command CPU is user+system; peak RSS is process high-water RSS. Same real Kotlin compiler2.0.21 JAR and M3 Max/macOS14.3/JDK17.0.20.1 environment as104. Old and current coverage differs (4,657,648 versus4,744,132 nodes); per-version reference checks preserve that distinction. This is cumulative old→107 evidence, not an isolated104→107 causal comparison.

| Order / variant | Continuous construction s | Whole CPU s | Peak RSS GB | Save s |
|---|---:|---:|---:|---:|
| 0 / A | 114.242073 | 192.28 | 7.728398 | 6.201069 |
| 1 / B | 39.863304 | 100.77 | 7.037960 | 11.039840 |
| 2 / B | 35.616662 | 102.11 | 8.092762 | 6.663168 |
| 3 / A | 112.137742 | 182.54 | 7.574700 | 6.595068 |
| 4 / B | 35.492571 | 99.09 | 9.562243 | 6.251403 |
| 5 / A | 114.228318 | 192.79 | 8.428765 | 7.082634 |
| 6 / A | 115.573175 | 195.85 | 7.867548 | 6.805026 |
| 7 / B | 41.199598 | 102.29 | 8.584970 | 11.346007 |

Mean continuous construction is114.045327→38.043034s (**−66.642%**) and whole-command CPU190.865→101.065s (**−47.049%**). Mean peak RSS is7.899853→8.319484GB (**+419.631MB / +5.312%**); the RSS median rises6.936%, and the maximum observed peak rises13.448%. The four adjacent RSS pair changes are−8.934%, +6.839%, +13.448% and+9.119%. All samples are retained; selecting the favorable first pair would not establish acceptance. Mean save rises6.670949→8.825104s (+32.292%), with two slow candidate saves retained; this small series does not establish slow-mode frequency or causation.

Literal commands and raw outputs: `/tmp/sootup-recovery-sources/attempt107-construction-proposal/`; command manifest SHA-256 `d2027084ae2cc0f41658d0875a663c9c42540abd58d64a48af0df529cc7d8e56`, preparation seal `52b0a2881c82b5f1a661cc8f822eabcf3362f559b19d73cfcf9e27c7478e06d7`, runner `794d8d9626fa765ccc70c0cc5a7584e4b1b394597d10882b7e063b8b638812a5`. The original unexecuted104/107 proposal is archived separately; no measurements from it exist. The command form remains `/usr/bin/time -l "$JAVA" -Xmx8g -cp "$HELPER_CLASSES:$FROZEN_CP" CliConstruction "$KOTLIN_COMPILER_JAR" "$OUTPUT_DIRECTORY"`. Independent raw construction audit: `/tmp/sootup-static-review/attempt107/construction-raw-audit.json`.

**Integration context:** while these frozen measurements ran, remote main advanced fromc84d7811 to02b853b7 (constant folding, CallSite ordinals and persistence changes). A merge is prepared separately; no107 result or its730 checks certify that merged artifact. Current PR CI cannot start until the real loader merge conflict is resolved. Construction results also do not certify independent loading/query CPU or RSS compliance under the clarified policy.

**Completed semantic verification:** all24 commands compare each output with its pinned version-specific reference: shape/connected nodes,13 metadata/API fingerprints and five fully consumed query results with generic-evaluation controls. All eight output graph inventories/bytes remained unchanged. Old and current reference graphs retain their different legitimate coverage; matching a per-version reference is not a claim of identical old/current graphs. The final executor result is PASS, SHA-256 `3c23555b24e879db2e245138fe878c155d019199cb06496058fd50a0a45dcd9e`. Original preflight preparation failures, unexecuted proposal and all raw samples remain archived.

**Separate real-input JMH controls:** exact method `io.johnsonlee.graphite.webgraph.GraphEndToEndBenchmark.kotlinCompiler_build_save_load_query`, common pinned104 webgraph harness after each frozen production classpath, order104→107→historical upgraded mainc84d7811. Each command uses two fresh forks, zero warmup and one single-shot measurement per fork, no GC profiler, and explicit4g for launcher and forks. The source benchmark disables call graph, annotations and cross-method dispatch, saves with default two threads and index preparation disabled, then mapped-loads, counts CallSites and deletes its output. It is distinct from the full-feature8g construction comparison and cannot certify separate loading/query resource caps.

| Revision | Fork1 ms/op | Fork2 ms/op | Mean ms/op | Whole command CPU s | Peak RSS GB |
|---|---:|---:|---:|---:|---:|
| 104 | 15983.609084 | 16428.938708 | 16206.273896 | 111.83 | 4.301963 |
| 107 | 16053.160334 | 19849.312166 | 17951.236250 | 115.90 | 4.666769 |
| mainc84d7811 | 16088.358875 | 16108.996542 | 16098.677709 | 130.15 | 4.992500 |

107 versus104: method mean **+10.767%**, whole-command CPU **+3.639%**, RSS **+8.480%**. Versus historical upgraded main: method **+11.508%**, CPU−10.949%, RSS−6.524%. All forks remain included, particularly the slow candidate fork; two single shots do not establish its frequency or intrinsic cause. Whole-command resources include orchestration and both forks, not isolated method CPU/op. No allocation result is claimed without a profiler.

Command form: `/usr/bin/time -l "$JAVA" -Xmx4g -cp "$FROZEN_PRODUCTION_CP:$COMMON_WEBGRAPH_JMH" org.openjdk.jmh.Main '^io\.johnsonlee\.graphite\.webgraph\.GraphEndToEndBenchmark\.kotlinCompiler_build_save_load_query$' -wi 0 -i 1 -f 2 -bm ss -foe true -rf json -rff "$OUT" -jvm "$JAVA" -jvmArgsAppend "-Dkotlin.compiler.jar.path=$KOTLIN_COMPILER_JAR"`. The source annotation supplies fork4g; exact argument arrays, effective class origins and JMH metadata are preserved in the sealed protocol.

**Decision:** reject the lifetime-boundary change. The full-feature cumulative candidate remains above the RSS allowance on mean and median, while the independent parent104 JMH comparison shows no end-to-end benefit and higher RSS. The historical104 meanRSS+15.596% cannot be used as an interleaved control to attribute the newer+5.312% result to107. Do not call the excess close enough, discard a high run or add favorable repetitions. Exact rejected source and verified restoration are preserved at `/tmp/sootup-static-review/attempt107/rejected-source/revert-proof.json`; the frozen107 runtime and all evidence remain. The next work is validating the separately prepared main integration and diagnosing memory by phase, with correctness/features and the8GiB ceiling preserved. Overall construction/loading/query recovery remains open.

Independent terminal audit `/tmp/sootup-static-review/attempt107/terminal-protocol-audit.json` (SHA-256 `440c484818ca63a07ef9bd35fdb85f989e694bdbc16c03a86dbfad2f2bd4ca96`) rechecked all35 jobs,24 reference reports,144 output files,1188 pins and13 inventories. Its full pin check and executor final pins precede the authorized source revert; the current root source difference is intentional and does not invalidate the frozen measurements.


**Post107 integration validation:** merge the retained recovery chain with main `02b853b7e5588034274d292163b79495a9ef8743` separately from the rejected experiment. The loader resolution retains main's fold interceptor chains, pre-fold ordinal callback and report-after-build behavior, together with the recovery view factory. A focused Java-fixture test verifies that selecting the first gate removes only that call, surviving gate/work calls keep ordinal1 and work argument2, and two loads report exactly once each after finalization without accumulating counts. The main ordinal sidecar and new feature paths remain intact;107 is absent.

One isolated Gradle invocation passed **2633 fresh test executions**, zero failures/errors/skips: core466, Cypher1332, SootUp539, webgraph246, query43 and the separate constrained-memory test7. All five modules' Detekt checks passed, both webgraph/Cypher JMH jars excluded test output, and runtime classpaths were frozen. The temporary build-only removal of the publishing plugin was restored byte-for-byte in all five build files; all597 source pins matched afterward. The Gradle512MiB metaspace warning is retained in the successful build log; no retry or limit increase was used. Launcher/Gradle/test heaps were explicitly bounded within8GiB. These are build/correctness checks, not new performance measurements.

Exact command and fresh XML/task provenance: `/tmp/sootup-recovery-sources/main-integration-build/merged-build1/`; build proof SHA-256 `ffc1317c97ace8d32077144aa7a30baa4c13d8a645924fccd43736c670e03139`, frozen runtime manifest `7540cf3bb8fa1ca176f60fc46bbedbf8c33d193b209226cb0ca005b4304709d6`. Performance comparisons against the pre-upgrade version and the newly integrated main still require their matching artifacts and semantic references; no earlier104/107 result is relabeled as a merged-artifact result. The required CI gate must run on the newly pushed head; prior87 failures remain recorded.


### 2026-10-07 — Merged baseline validation and bounded memory diagnosis

**Runtime identity and correctness:** the retained recovery plus main `02b853b7` integration is commit `6fa79652`; production sources are unchanged at `ca659c65`. A fresh, separately compiled exact main `02b853b7` baseline and the frozen merged production runtime each built the real Kotlin compiler 2.0.21 JAR with default features and `-Xmx8g`. Both contain 4,744,132 nodes and 2,251,811 CallSites. All 22 existing semantic-shape properties, 13 metadata properties and five complete query reports agree. An additional streamed, disk-backed exact comparison covers every CallSite's complete caller/callee descriptors, nullable ordinal, line and resolved origin identity: zero differing records, 893,309 nonnegative and 1,358,502 negative ordinals, 1,329,249 origin links, no absent ordinal. Numeric graph IDs are normalized; duplicate IDs/stable identities and dangling origins fail validation. Both saved graphs remain byte-identical to their own pre-verification inventories. This establishes the checked main/merged parity, not universal feature coverage or performance acceptance. Evidence: `/tmp/sootup-recovery-sources/merged-construction-reference-protocol/execution/results.json` and `/tmp/sootup-static-review/merged-reference/audit.json`.

**Fixed diagnostic protocol:** pre-upgrade `6f498705009689551c92c6d1ca92f67252ef77c4` then merged, exactly one fresh JVM each, on the same M3 Max / macOS 14.3 / JDK 17.0.20.1 host. Both use maximum heap 8 GiB, default features, Mmap construction, full source-node scan, prepared save with two compression threads and source close. The common helper compiles once against the old API; the merged node count comes from the semantic reference above. Symmetric buffered phase callbacks, 250 ms MXBean sampling, 100 ms external RSS sampling and natural GC/heap/safepoint logs are diagnostic observers. They can change allocation/JIT/GC timing; they do not reproduce the quiet primary protocol exactly. No forced GC, retries or additional samples. The historical `preupgrade-android-proof` runtime label is used on the Kotlin JAR, not an APK.

Command: `python3 /tmp/sootup-recovery-sources/merged-construction-memory-plan/execution-packet/execute.py --execute --merged-node-count 4744132`. Both workloads completed; old/new counts remain 4,657,648 / 4,744,132 and differ legitimately. All raw graphs, markers, heap/GC/RSS observations and command exits remain under that packet's `execution/` directory.

| Runtime | Construction through close (s) | Whole-command user + system CPU (s) | Process peak RSS (decimal GB) | Largest sampled save RSS (GB) |
|---|---:|---:|---:|---:|
| Pre-upgrade 6f498705 | 114.555853 | 186.49 | 7.554400 | 7.554269 |
| Merged 6fa79652 / production at `ca659c65` | 36.874394 | 100.72 | 7.147717 | 7.147651 |

**Interpretation:** both largest RSS observations occur late in save and fall within 131,072 / 65,536 bytes of the independent whole-process peaks. The actual maximum may fall between samples. Old heap commitment reached 6.635 GB; merged reached 6.174 GB, both before save. Merged natural remark/mixed collections during save reduced reported heap occupancy to about 1.3 GB without reducing commitment; its last sampled occupancy was 2.916 GB while RSS approached 7.148 GB. The old final sampled occupancy was 5.365 GB. Occupancy is not live-object size, commitment is not residency, and RSS minus either does not estimate native memory. These traces do not prove frontend retention or attribute the RSS peak to ordinal encoding. The merged RSS is lower in this pair; it does not reproduce or overturn 104/107's adverse quiet samples, establish repeatability, or satisfy the user 5% allowance by itself. Persisted loading and query resource recovery remain unmeasured by this construction-only pair.

**Next hypothesis:** ordinal persistence currently materializes/sorts all decoded CallSites before emitting primitive arrays. Capturing compact ordinal/origin records during the existing node-data write pass may remove that retention and redundant decode work. This is source-supported but causality/performance remains unproven; it requires its own correctness and real-data comparison. No optimization was accepted from this diagnostic pair. Full analysis: `/tmp/sootup-static-review/merged-memory-analysis/report.md` and `results.json`.

**CI at `ca659c65`:** JVM, Rust and required benchmark regression workflows all passed, including run 37560173922 and its exact-head [benchmark comment](https://github.com/johnsonlee/graphite/pull/171#issuecomment-6025051872). Actual base `02b853b7` and candidate Rust executables are byte-identical (SHA-256 29524a0205ff1a2b1a0df82f550257e36a631dab59f093e43a37dbe4660add94); the earlier `87da90bd` failure remains in history. Preserve adverse results despite the gate's PASS: Kotlin save 9,541→15,886ms initially and 9,648→10,841ms on reverse confirmation; global-wide pressure pair 2 P95 +144.0% and replay CPU +6.44%, with name-pair zero/targeted rows adverse in two pairs. These gates have different boundaries/thresholds and do not prove the user's per-operation latency/CPU/RSS constraints. CI heap arguments were not explicitly capped for every direct launcher; no historical >8 GiB violation is inferred from missing arguments.


### 2026-10-07 — Attempt 108: Collect primitive call ordinals during node persistence

**Status:** RETAINED AS AN INCREMENTAL CHANGE, not accepted as complete recovery. All primary and follow-up runs completed. The initial rejection was reconsidered after the user clarified that verified incremental gains may accumulate; an individual attempt need not independently reach the final pre-upgrade targets. The measured implementation is integrated with the separately tested cancellation fix; combined validation passed.

**Hypothesis:** the old save path decodes, retains and sorts every ordinal-bearing CallSite before copying its ID, ordinal and origin into primitive arrays. Count the ordinal-bearing calls in the existing counting pass, allocate the arrays just before node-data writing, and populate them during that write pass. Preserve the encoder, sidecar format, metadata binding and readers. Already ordered streams require no sort; other streams sort aligned primitive fields with encounter-order tie breaking. This removes the dedicated CallSite scan and object retention without reducing graph coverage, changing heap size or forcing GC.

**Correctness:** isolated parent `ca659c65` plus this candidate passed 250 fresh webgraph tests and 43 fresh query tests, with zero failures/errors/skips, webgraph Detekt and both JMH artifact-isolation checks. The four added tests compare the unchanged reference encoder byte-for-byte and cover shuffled sparse IDs across sidecar blocks, extreme/nullable ordinals, origins, stable equal-ID ordering, eager/mapped decoding and stale-sidecar removal. The first build failed Detekt's constructor parameter limit; related CallSite writer fields were grouped without changing execution order. The second failed compilation of the new test; a nullable property was cached locally and the delegation expression parenthesized. Both failures remain archived; only build3 supplies the test pass.

Candidate runtime manifest SHA-256 `0ca5a6c12cf227262a2f8c12b18ebe89bd5e04f21ee439b9265ca37654937ec9`; build evidence `/tmp/sootup-recovery-sources/attempt108-build/attempt108-build3/`. Publishing configuration was restored after each build. Root independently checked the fresh XML counts and content hashes.

**Predeclared real-data comparison:** exactly four full-feature Kotlin compiler 2.0.21 constructions, parent A then candidate B in ABBA order, on the same M3 Max / macOS 14.3 / JDK 17.0.20.1 host. Both use the same compiled CLI-like helper, default features, Mmap builder, full node-count scan, prepared two-thread save, explicit source close and `-Xmx8g`. Whole-process user+system CPU and peak RSS are collected separately from internal phase clocks. All samples, failures and mean/median/range/pair deltas are retained. This n=2-per-arm parent comparison cannot establish recovery against pre-upgrade or standalone loading/query acceptance.

All four output graphs must pass the existing shape, metadata and five-query reports; sidecar bytes and metadata binding must match the validated merged reference. The first candidate additionally compares every normalized CallSite ordinal/origin record against that reference. Separate reduced-feature controls use the unchanged common `GraphEndToEndBenchmark.kotlinCompiler_build_save_load_query`, parent then candidate, two fresh forks each, zero warmup, one single shot, matching 4 GiB launchers/forks. Their fused boundaries cannot replace full-feature construction or independent loading/query evidence.

Exact command arrays and raw results: `/tmp/sootup-recovery-sources/attempt108-performance-pilot/`; resolved command SHA-256 `3c32d093b3c8a9ebbf6159ee14684efcb6b1c82006bb78f40f294c28ab5a3503`. No synthetic performance benchmark is used.

**Completed primary construction samples:** all four runs exited successfully with 4,744,132 nodes. All subsequent output verification and separate JMH controls also completed successfully; successful execution is distinct from a favorable performance result.

| Order / variant | Continuous construction s | Whole CPU s | Peak RSS GB | Save s |
|---|---:|---:|---:|---:|
| run0-A | 37.310826 | 103.40 | 9.629909 | 6.920126 |
| run1-B | 36.867443 | 104.47 | 9.072099 | 6.676781 |
| run2-B | 36.990654 | 103.13 | 9.667215 | 6.631487 |
| run3-A | 37.434450 | 103.51 | 9.642033 | 6.921217 |

Mean continuous construction is 37.372638→36.929048s (**−1.187%**), whole-command CPU 103.455→103.800s (**+0.333%**), and peak RSS 9.635971→9.369657GB (**−2.764% / −266.314MB**). Save falls 6.920671→6.654134s (−3.851%). The candidate RSS samples differ by 595.116MB; one candidate peak (9.667215GB) exceeds both parent samples. These are modest local point estimates, not proof of repeatable memory improvement or compliance with the pre-upgrade +5% cap. No sample has been excluded or replaced.

**Completed correctness:** all 12 separate shape/metadata/query verifier JVMs passed. All four ordinal sidecars and metadata bindings match the reference byte-for-byte; all 2,251,811 normalized CallSite records and 1,329,249 origins match the reference in the exact disk-backed comparison. Every output graph retains its original file hashes after verification. All 20 planned jobs completed, with no retry or excluded sample. Terminal results SHA-256 `28d08fde29b0eeaa208b78e73c5f0fbecd8d6be954dbb2f3594e1b1e1eea3d77`.

**Independent reduced-feature JMH control:** same exact Kotlin pipeline method and common harness, parent A then candidate B, two fresh forks each, 4 GiB launcher/forks, zero warmup, one single shot per fork. These runs disable call graph, annotations and cross-method tracking; CPU/RSS below cover the whole command including orchestration and both forks, not isolated method CPU/op.

| Runtime | Fork 1 ms/op | Fork 2 ms/op | Mean ms/op | Whole command CPU s | Peak RSS GB |
|---|---:|---:|---:|---:|---:|
| A | 18920.519709 | 16956.807250 | 17938.663479 | 112.61 | 5.006344 |
| B | 20230.965792 | 16757.364708 | 18494.165250 | 120.63 | 4.998545 |

Candidate method mean is **+3.097%**, command CPU **+7.122%**, and RSS **−0.156%** relative to the parent. The second candidate fork is faster while the first is slower; both are retained. Two forks do not establish a stable intrinsic regression or its cause. This parent-only comparison also cannot establish a violation of the user's resource cap relative to pre-upgrade.

**Initial disposition, superseded:** the change was rejected because its small full-feature gain did not resolve the memory regression and the independent 4 GiB control had adverse point estimates. Sources were archived and the isolated tree restored; the original archive and restoration proof remain at `/tmp/sootup-static-review/attempt108/rejected-source/`.

**Revised decision:** preserve the measured implementation as an isolated candidate and investigate the conflicting 4 GiB result. Requiring every individual attempt to recover all final metrics would discard potentially useful cumulative improvements. Parent-relative CPU +7.122% is not the same comparison as the user's pre-upgrade +5% cap; two forks also do not establish a stable intrinsic regression. Neither the favorable 8 GiB point estimates nor the adverse 4 GiB estimates are discarded. A follow-up must distinguish repeatable tradeoffs from variation and evaluate the cumulative candidate against the accepted pre-upgrade baseline. Correctness and maximum heap remain hard constraints at every step. Restored sources match the measured hashes exactly (`/tmp/sootup-static-review/attempt108/reopened-proof.json`); no rerun, replacement sample or final acceptance is implied. Construction/loading/query recovery remains incomplete.

**Predeclared follow-up after reopening:** run exactly four new outer JMH groups in BAAB order, one fresh fork per group, with the same frozen A/B runtimes, real Kotlin method, 4 GiB launcher/fork, zero warmup and one single shot. This changes the process grouping to balance ordering; original two-fork-group CPU/RSS remain a separate dataset. Retain every original and new result, including failures; no reruns or selected replacement samples. The purpose is to investigate the conflicting control, not require the incremental attempt to complete the entire recovery. Commands: `/tmp/sootup-recovery-sources/attempt108-jmh-baab/commands.json`, SHA-256 `a177857d7c1a0030f33ccc6bb1b371abf9d240e2324cb5981650b069e0d4639f`.

**Completed fixed follow-up:** all four one-fork groups passed. These are separate outer-process resource measurements from the earlier two-fork groups.

| Order / runtime | JMH ms/op | Whole command CPU s | Peak RSS GB |
|---|---:|---:|---:|
| run0-B | 16128.318541 | 57.48 | 4.998922 |
| run1-A | 16647.118125 | 59.45 | 4.952556 |
| run2-A | 20156.443291 | 59.59 | 4.548559 |
| run3-B | 16299.401500 | 56.89 | 4.325261 |

Within this fixed follow-up, the candidate method mean is 11.890% lower, CPU 3.923% lower and RSS 1.862% lower. The slow parent shot is retained, as are the earlier slow candidate shot and adverse two-fork CPU result. This reversal leaves the reduced-feature 4 GiB effect uncertain; it does not justify selecting either batch as the stable effect or pooling their different outer-command CPU boundaries. Full raw results: `/tmp/sootup-recovery-sources/attempt108-jmh-baab/results.json`, SHA-256 `a263804ccca060242d1ffb2100625c624409a7e4f4f8d76eb5342fbb608893c8`.

**Keep decision:** retain the compact ordinal collection as incremental progress based on the full-feature construction/save improvement, preserved format/semantics and the absence of a repeatable adverse direction in the separate control. The modest and variable RSS reduction is not a solution to the outstanding memory regression. The reduced-feature control remains uncertain, rather than claimed as a proven gain. Evaluate the cumulative implementation against pre-upgrade, including independent loading and query resources; all hard correctness/heap constraints remain in force. Integration does not constitute final recovery acceptance.

**Combined validation:** the retained 108 implementation plus the final-accounting cancellation fix passed 252 fresh webgraph tests, 43 fresh query tests, webgraph Detekt and both JMH artifact-isolation checks, with no failures/errors/skips. Root independently checked all test XML counts and hashes. Publishing files were restored byte-for-byte. The frozen combined runtime is `/tmp/sootup-recovery-sources/combined108-build/combined108-build1/snapshot/runtime.json`, SHA-256 `39fd360457890bfea77a7cf0755a7aa1390bc3540f14564c704e992823b89d32`; it is the candidate for subsequent independent loading/query measurements. No earlier parent-only measurement is relabeled as this combined artifact.


### 2026-10-07 — Cumulative validation at 9cf1b5ff: separate loading and ordered-query resources

**Status:** correctness PASS; loading acceptance NOT MET. This validates the cumulative retained implementation at `9cf1b5ff` (including the combined cancellation fix), not a new optimization attempt or an isolated effect of108. A is frozen pre-upgrade `6f498705`; B is the combined runtime manifest `39fd360457890bfea77a7cf0755a7aa1390bc3540f14564c704e992823b89d32`. No parent-only result is substituted for B.

**Fixed protocol:** exactly24 fresh measured JVMs, three operations in the order below, each `ABBABAAB` (four samples per runtime). A separate combined-B five-query oracle passed first; there were no retries, replacement samples, compilation or excluded measurements. Same M3 Max / macOS14.3 / JDK17.0.20.1 host, `-Xmx8g` for every JVM, no profiler, forced GC or heap/feature changes. Recorded JVM arguments are exactly `[-Xmx8g]`; JVM/classpath override variables are absent, and the recorded inherited `MallocNanoZone=0` is unchanged. The common `LoadingQueryOperation.class` is reused unchanged (SHA-256 `7bf8281f68311bb353ae858a60d5e747162ba857a4ada611d1f4514da01e2313`).

Both runtimes read private complete copies of the same validated merged Kotlin compiler2.0.21 graph:19 files,578,075,575 bytes,4,744,132 nodes and2,251,811 CallSites, including ordinal/origin and prepared-string-index sidecars. Each copy is sequentially read and hashed immediately before its JVM, outside timing. This is a specified cache-preparation policy, not a claim of cold disk or guaranteed full page-cache residency. The copies and original retain their file inventories after all checks. This compares readers on identical current-format bytes; it does not compare separately generated old/current graph sizes or claim that old readers expose the new ordinal API.

Command form: `/usr/bin/time -l "$JAVA17" -Xmx8g -cp "$EXISTING_HELPER_CLASSES:$FROZEN_RUNTIME_CP" LoadingQueryOperation "$MODE" "$PRIVATE_GRAPH" "$NEW_REPORT_DIR"`. Literal arrays, copy commands and all pins are in `/tmp/sootup-static-review/loading-query-operation-protocol/primary24-draft/commands.resolved.json` (SHA-256 `ff98002ac7f2a02b3066212813825b801722b84f5140968a8bd590af96bc6861`). A uses the pinned old runtime classpath; B uses combined `MAIN_query`. The historical old manifest's “android-proof” label does not make this an Android workload.

**Measurement boundaries:** `mapped-open` loads the mapped graph, decodes the first real node and closes it. `first-ordered` opens, fully consumes one ordered query and closes. `warm-ordered` opens, fully consumes one warmup request, measures ten further requests, then closes. The exact query is `MATCH (n:CallSiteNode) RETURN n.callee_class AS className, n.callee_name AS methodName ORDER BY className, methodName LIMIT 20`. Request clocks include complete typed-result traversal, canonicalization and hashing; batch clocks also include request bookkeeping. Continuous session clocks include mapped loading, any deferred query work, warmup where specified and close. Phase/session CPU uses the JVM process CPU clock, including concurrent JVM threads. Whole-command CPU is `/usr/bin/time` user+system, including startup and report/teardown; its peak RSS is whole-process high-water memory, **not query-only RSS**. Copying and post-run verification are outside both measured boundaries.

All24 raw samples follow, in execution order. CPU columns are seconds; RSS is decimal MB. Unrounded values, phase clocks and individual warm requests remain in the raw reports.

| Operation / order / runtime | Session wall s | Session CPU s | Whole CPU s | Peak RSS MB |
|---|---:|---:|---:|---:|
| mapped-open / 0 / A | 0.224805 | 0.490272 | 0.53 | 179.208192 |
| mapped-open / 1 / B | 0.243988 | 0.578710 | 0.63 | 191.381504 |
| mapped-open / 2 / B | 0.245245 | 0.560622 | 0.61 | 185.286656 |
| mapped-open / 3 / A | 0.219694 | 0.474052 | 0.52 | 180.584448 |
| mapped-open / 4 / B | 0.251563 | 0.587685 | 0.64 | 192.036864 |
| mapped-open / 5 / A | 0.228350 | 0.489542 | 0.54 | 180.191232 |
| mapped-open / 6 / A | 0.227817 | 0.486635 | 0.53 | 178.683904 |
| mapped-open / 7 / B | 0.243330 | 0.486284 | 0.53 | 182.206464 |
| first-ordered / 0 / A | 1.674499 | 2.402917 | 2.46 | 1130.954752 |
| first-ordered / 1 / B | 0.827252 | 1.517915 | 1.57 | 1128.284160 |
| first-ordered / 2 / B | 0.800922 | 1.494959 | 1.54 | 1125.761024 |
| first-ordered / 3 / A | 1.518871 | 2.156970 | 2.21 | 1105.133568 |
| first-ordered / 4 / B | 0.822443 | 1.530628 | 1.58 | 1125.728256 |
| first-ordered / 5 / A | 1.677429 | 2.370612 | 2.41 | 1120.305152 |
| first-ordered / 6 / A | 1.702448 | 2.471253 | 2.52 | 1131.708416 |
| first-ordered / 7 / B | 0.836655 | 1.512093 | 1.57 | 1121.239040 |
| warm-ordered / 0 / A | 14.235863 | 15.187744 | 15.25 | 1114.816512 |
| warm-ordered / 1 / B | 4.685130 | 5.443668 | 5.49 | 1104.740352 |
| warm-ordered / 2 / B | 4.465889 | 5.477072 | 5.54 | 1134.133248 |
| warm-ordered / 3 / A | 13.738220 | 14.766630 | 14.82 | 1117.863936 |
| warm-ordered / 4 / B | 4.562302 | 5.510469 | 5.57 | 1135.968256 |
| warm-ordered / 5 / A | 14.231863 | 15.237549 | 15.29 | 1130.430464 |
| warm-ordered / 6 / A | 13.891721 | 14.910117 | 14.97 | 1124.777984 |
| warm-ordered / 7 / B | 4.770507 | 5.725276 | 5.79 | 1126.383616 |

**Predeclared arithmetic-mean comparisons, A→B:**

| Operation | Session wall s (change) | Session CPU s (change) | Whole CPU s (change) | Mean peak RSS MB (change) |
|---|---:|---:|---:|---:|
| Mapped open + first node + close | 0.225167→0.246031 (+9.266%) | 0.485125→0.553325 (+14.058%) | 0.5300→0.6025 (+13.679%) | 179.666944→187.727872 (+4.487%) |
| First ordered query session | 1.643312→0.821818 (−49.990%) | 2.350438→1.513899 (−35.591%) | 2.4000→1.5650 (−34.792%) | 1122.025472→1125.253120 (+0.288%) |
| Warm ordered session, one warmup + ten requests | 14.024417→4.620957 (−67.051%) | 15.025510→5.539121 (−63.135%) | 15.0825→5.5975 (−62.888%) | 1121.972224→1125.306368 (+0.297%) |

First-request batch wall is1.417032→0.592051s (−58.219%) and CPU1.863339→1.002306s (−46.209%). The ten measured warm requests together take12.372170→3.795294s (−69.324%) and12.706680→4.071871 CPU seconds (−67.955%); these are batch totals, not individual request latency. Query-session RSS includes opening the graph and JVM state. These bounded query improvements do not compensate for loading regressions.

**Adverse loading result:** all four adjacent session-wall comparisons worsen (+8.533%, +11.630%, +10.165%, +6.809%). Whole-command CPU pairs are+18.868%, +17.308%, +18.519% and0.000%; the mean exceeds the user's separate+5% limit, and session CPU independently shows the same adverse direction. Session-wall median rises8.089%; whole-CPU median rises16.981%. Mean RSS is within+5% but median rises4.805%, maximum observed peak rises6.342%, and adjacent RSS changes are+6.793%, +2.604%, +6.574% and+1.971%. Four samples do not establish a population bound or justify choosing only favorable pairs. The `loadMapped` phase alone rises2.776% in mean wall time; first-node touch rises3.273→17.984ms and11.224→63.500ms process CPU. That localizes much of the observed extra work to first-use readiness without identifying its cause. Reporting only the open call would hide deferred loading work.

**Independent validation:** all25 recorded command arrays match the sealed plan; stdout/stderr/report hashes,24 raw time/RSS parses, every phase/session metric and every summary mean/median/min/max/range/pair/block calculation were rechecked. All1465 input pins and five classpath-directory inventories still match. The combined oracle equals the complete five-query reference (row counts20/20/100/99/50, with full values/types/order retained). All96 primary query responses, including warmups, match the typed20-row reference; mapped-open runs decode the expected `IntConstant`. All25 saved post-run graph inventories match the same19-file reference. The runner stops on failure and executes serially; all jobs passed without an extra JVM.

Evidence: `primary24-draft/execution/results.json`, SHA-256 `b54b2139c2fefa4ec84eb374926b719fafe97171d8b45b89e33c3f5895e8e2f2`; `summary.json`, SHA-256 `a7a2a6098d90592ac79a4a16caae278489985a91ba82f371b8bb2a6e065e2c52`. Raw per-run outputs are in the sibling `results/` directories. The summary retains every metric and both four-run block comparisons; no operations are pooled.

**Host-observation limitation found afterward:** the macOS `ps` command placed `comm` before numeric columns, allowing executable paths to be truncated. Its Java-name filter therefore cannot independently prove the absence of other JVMs. The executor ran the prescribed jobs serially and was the only agent authorized to run JVMs; that coordination is distinct from a complete process inventory. Subsequent runs use wide output with `comm` last and match Java at end of line. Original observations and measurements are retained without claiming stronger host isolation than they establish.

**Classpath comparability correction:** subsequent inspection found that A's 54-entry frozen classpath comes from `TEST_webgraph`, whereas B's 88-entry `MAIN_query` also includes the explore application, Javalin/Jetty and other application dependencies absent from A. The measured totals above remain valid for those exact commands, but cold class loading and whole-command CPU may be affected by this mismatch; the table does not independently prove an intrinsic loading regression or establish the requested matched-baseline acceptance result. The effect size of the mismatch is not yet measured. A fresh, fixed comparison will use the same Gradle configuration on both sides (`TEST_webgraph`; the candidate has 57 entries, including dependency changes required by SootUp 3). Original results are retained rather than replaced or pooled. This correction also applies to the old-versus-current comparisons in attempt 109's initial 36-run batch; its parent-versus-candidate comparison uses matching `MAIN_query` configurations and remains an incremental comparison.

**Remaining work:** diagnose the measured loading/first-touch overhead; preserve the query gains while evaluating each operation separately. This ordered-query case does not cover eager loading, first access to all deferred metadata/backward indexes, own-version outputs, other corpora or the broader filter/count/DISTINCT/relationship/name-pair workloads. Prior dense wrapped-DISTINCT and name-pair pressure results remain adverse evidence, not replaced by this favorable ordered projection. Construction RSS and exact-head CI remain separate acceptance requirements. Overall recovery is incomplete; no CPU/RSS allowance is inferred from startup dilution, one small query result or a near-cap RSS mean.


### 2026-10-07 — Attempt 109: defer ordinal sidecar loading until a CallSite is decoded

**Hypothesis and change:** `MappedWebGraphBackedGraph.readNodeAt` evaluated `callSiteOrdinals.value` for every node, although `NodeSerializer` reads ordinal/origin metadata only for the CallSite tag. Inspect the existing five-byte node header and pass `CallSiteOrdinals.EMPTY` for other tags; initialize the existing lazy sidecar only for a CallSite. This removes avoidable first-use work; it does not assert that this was the sole cause of the earlier loading difference. The isolated candidate is based on `9cf1b5ff8ef52f824bdc71467cabf6ba8411cdbd`, with no other production change.

The guard preserves node-format and bounds/error handling: malformed headers still reach the serializer, unknown tags retain its error, and invalid/missing IDs retain their existing behavior. Real CallSites still use the same ordinal decoder, binding validation and derived-origin lookup. Corrupt sidecar validation/warnings are deferred until a CallSite needs the sidecar; they are not removed. Non-CallSite access still references `EMPTY`, so this is not a claim of eliminating all ordinal-class initialization.

**Correctness:** 299 fresh tests passed (256 webgraph, 43 query; zero failures/errors/skips), both module Detekt gates passed, and both JMH artifacts passed test-class isolation checks. Four new tests cover non-CallSite direct/typed/query access without initializing the lazy value, first ordinary and first derived CallSites with exact ordinal/origin fields, complete node/query enumeration, and missing-sidecar behavior. Publishing build files were restored byte-for-byte. Build/source proof is `/tmp/sootup-recovery-sources/attempt109-build/attempt109-build1/build-proof.json`; frozen runtime `snapshot/runtime.json` SHA-256 is `9dc3514f1125bbdfeb634104737f8e9d19445db71a444ab5e9e11d9682d5c5fb`. Source seal SHA-256 is `3294b734b19eb3ec413ae367eed0bc0eed3ca9a79f928c08c3e9437cfb676bc7`.

**Initial 36 direct-JVM measurements:** A is frozen pre-upgrade, B the combined 9cf parent, C109. Each of mapped-open, first-ordered and warm-ordered uses the fixed order `ABCCBABACCAB` (four samples each), with the common helper, exact ordered query and phase boundaries of the preceding cumulative record. All use `-Xmx8g`, private complete copies of the same real merged Kotlin compiler2.0.21 graph, and a sequential hash/read before each JVM outside timing. The graph has 4,744,132 nodes and 2,251,811 CallSites, including current ordinal/origin sidecars. All36 measurements and the separate five-query oracle passed; complete query values/order and post-run graph inventories were retained. No retries or excluded samples.

Arithmetic means below retain all three variants. Wall/CPU are seconds; RSS is decimal MB. CPU is whole-command user+system; session CPU remains separately recorded in the raw summary.

| Operation | Session wall A / B / C | Whole CPU A / B / C | Peak RSS A / B / C |
|---|---:|---:|---:|
| mapped-open | 0.231030 / 0.249216 / 0.240251 | 0.522500 / 0.615000 / 0.595000 | 179.560448 / 185.589760 / 185.196544 |
| first-ordered | 1.803801 / 0.820870 / 0.834482 | 2.420000 / 1.465000 / 1.477500 | 1097.482240 / 1112.510464 / 1108.500480 |
| warm-ordered | 15.981227 / 4.769203 / 4.756247 | 16.855000 / 5.625000 / 5.587500 | 1109.057536 / 1110.306816 / 1110.065152 |

The matching-role B→C comparison shows mapped-session wall −3.597% (249.216→240.251ms), session CPU −4.511%, whole CPU −3.252% and RSS −0.212%. All four B/C loading-wall pairs improve. First-query session wall instead rises 1.658%, session CPU 1.054% and whole CPU 0.853%; warm-session wall falls only 0.272%, whole CPU 0.667%. These small query differences are retained, not claimed as stable gains.

**Classpath qualification:** A uses 54-entry `TEST_webgraph`; B and C use matching 88-entry `MAIN_query`, including application/server dependencies absent from A. Both A/B and A/C cold-process comparisons are therefore confounded by classpath role. This also qualifies the preceding initial24 cumulative table; neither batch independently establishes an intrinsic old/current loading CPU regression or final matched-baseline acceptance. B/C remains an incremental matching-role comparison. Changing role in a later batch is not an isolated experiment proving the size or cause of the classpath effect.

**Relevant JMH controls:** four complete seven-method groups ran B→C→C→B, one fresh fork per method per group, `-prof gc`, average time, fail-on-error, launcher and every fork `-Xmx8g`. Load methods use one 1s warmup and two 1s measurement iterations; query methods use two 1s warmups and three 1s measurements. This separate real persisted Kotlin fixture is the reduced-feature graph from frozen102: 3,292,214 nodes, 922,876 CallSites, 17 files, without the newer ordinal sidecars. B/C full shape bags match and graph inventories remain pinned. Consequently these controls exercise missing-sidecar compatibility and relevant load/query paths, but do not replace the full-feature direct-JVM measurements or prove ordinal-rich eager-load behavior.

All four group scores are shown in execution order, in ms/op; each score averages that fork's measurement iterations.

| Method | B0 | C1 | C2 | B3 | Mean B→C | Change |
|---|---:|---:|---:|---:|---:|---:|
| eager_load | 3276.485416 | 3219.638126 | 2786.273000 | 3324.293688 | 3300.389552→3002.955563 | -9.012% |
| mapped_load | 73.726952 | 74.747698 | 74.472878 | 73.650266 | 73.688609→74.610288 | +1.251% |
| mapped_simpleNodeMatch | 0.052328 | 0.052418 | 0.050179 | 0.050516 | 0.051422→0.051299 | -0.240% |
| mapped_intConstantFilter | 0.043090 | 0.041132 | 0.043426 | 0.043551 | 0.043320→0.042279 | -2.404% |
| mapped_singleHopRelationship | 0.279808 | 0.281287 | 0.280758 | 0.273462 | 0.276635→0.281022 | +1.586% |
| mapped_orderedCallSitePropertyLimit | 182.946410 | 178.254600 | 188.421900 | 185.758055 | 184.352233→183.338250 | -0.550% |
| mapped_orderedIntConstantPropertyLimit | 0.384769 | 0.362730 | 0.388109 | 0.390329 | 0.387549→0.375419 | -3.130% |

GC-profiler allocation is bytes/op, not live heap or RSS. All four fork scores are retained:

| Method | B0 | C1 | C2 | B3 | Mean B→C | Change |
|---|---:|---:|---:|---:|---:|---:|
| eager_load | 6049207656.00 | 6043215616.00 | 5953120372.00 | 6043215600.00 | 6046211628.00→5998167994.00 | -0.794607% |
| mapped_load | 41233931.71 | 41232707.71 | 41232667.71 | 41234027.71 | 41233979.71→41232687.71 | -0.003133% |
| mapped_simpleNodeMatch | 232737.12 | 232768.48 | 232768.46 | 232736.46 | 232736.79→232768.47 | +0.013611% |
| mapped_intConstantFilter | 103824.40 | 103872.87 | 103840.40 | 103824.81 | 103824.60→103856.64 | +0.030853% |
| mapped_singleHopRelationship | 353962.56 | 354442.59 | 352525.72 | 353962.52 | 353962.54→353484.15 | -0.135152% |
| mapped_orderedCallSitePropertyLimit | 567744100.44 | 567744099.11 | 567744099.11 | 567744132.44 | 567744116.44→567744099.11 | -0.000003% |
| mapped_orderedIntConstantPropertyLimit | 1545726.96 | 1545730.21 | 1545704.33 | 1545703.20 | 1545715.08→1545717.27 | +0.000142% |

Mapped-load method latency worsens 1.251%, and the single-hop control worsens 1.586%; small positive allocation deltas for simple match, Int filtering and ordered Int remain visible. Eager-load mean improves 9.012%, but the two candidate fork scores differ substantially. These are two forks per variant, not evidence of stable tail frequency or a blanket query improvement.

| Whole seven-method group | Wall s | CPU s | Peak RSS GB (decimal) |
|---|---:|---:|---:|
| run0-B | 43.11 | 86.91 | 6.858228 |
| run1-C | 42.73 | 82.84 | 9.008218 |
| run2-C | 41.65 | 76.83 | 6.602670 |
| run3-B | 43.15 | 86.82 | 8.756969 |

Whole-group RSS ranges overlap broadly, including the candidate's 9.008GB high sample. These figures include launcher/fork startup, warmup, setup and multiple methods; fixed-duration methods execute unequal operation counts. They are not method CPU/op or isolated query RSS and do not certify the user's operation resource caps. An 8GiB heap ceiling does not imply an 8GiB process-RSS ceiling.

**Exact commands and pins:** direct measurements use `/usr/bin/time -l "$JAVA17" -Xmx8g -cp "$COMMON_HELPER:$FROZEN_CP" LoadingQueryOperation "$MODE" "$PRIVATE_GRAPH" "$REPORT"`. JMH uses `/usr/bin/time -l "$JAVA17" -Xmx8g -cp "$FROZEN_RUNTIME_AND_COMMON_JMH_CP" org.openjdk.jmh.Main "$ANCHORED_SEVEN_METHOD_REGEX" -p corpus=KOTLIN_COMPILER -f 1 -prof gc -foe true -rf json -rff "$RESULT" -jvm "$JAVA17" -jvmArgsAppend "-Dkotlin.compiler.graph.path=$PRIVATE_GRAPH"`; the common benchmark metadata supplies the 8g fork heap and iteration settings. Literal arrays, exact regex, classpaths, fixture/file hashes and JVM arguments are preserved in the files below. Host is the same M3 Max/macOS14.3/JDK17.0.20.1; no profiler other than JMH GC metrics, forced GC or changed heap/features were introduced. These command boundaries are distinct from construction/save timing.

- `/tmp/sootup-static-review/loading-query-operation-protocol/attempt109-primary36/commands.resolved.json` — SHA-256 `e310e0ce2d93f327d2795994ea287f1e7d05b4e6346b0d30fc037cb6184be885`.
- `/tmp/sootup-static-review/loading-query-operation-protocol/attempt109-primary36/execution/results.json` — SHA-256 `58f54c6cdf728e2f3a92dae4ed801a5af665026215479509d25c452fb327b9bc`.
- `/tmp/sootup-static-review/loading-query-operation-protocol/attempt109-primary36/execution/summary.json` — SHA-256 `27bb787e8aa5dd0e775eb46f05faed2977d11691defff1e1e2c504d98e48b693`.
- `/tmp/sootup-recovery-sources/attempt109-jmh/commands.json` — SHA-256 `6815fc19b12c8b2df3bf5a8812e6bd47ba57071d6d64ecf4ec511817162edeb1`.
- `/tmp/sootup-recovery-sources/attempt109-jmh/seal.json` — SHA-256 `04c155c6f01382437bb50741d8ea7addae95b3624696f307d91977755f1bb69c`.
- `/tmp/sootup-recovery-sources/attempt109-jmh/execution/results.json` — SHA-256 `319404bfc88b01944c427ce9c74a5dea7d68dd22eb29571face3aaa0db33fa90`.
- `/tmp/sootup-recovery-sources/attempt109-jmh/execution/summary.json` — SHA-256 `a3cb13340dc853626ad4ebfc3f95809c760005de9525c14d855d0a1af3300685`.

**Corrected matching-role cumulative24, completed:** the fresh fixed `ABBABAAB` sequence for each operation now uses `TEST_webgraph` on both sides (54 old entries, 57 current entries with required dependency-version changes). A is pre-upgrade and B is109 in this separate batch. The same helper, full-feature graph copies, query, phase clocks, 8g limit and pre-JVM sequential-read policy are unchanged. All24 measurements plus the separate oracle passed. They are not pooled with either earlier batch. Independent review checked report hashes and means.

| Operation | Mean session wall s (old→109) | Mean session CPU s | Mean whole CPU s | Mean peak RSS MB |
|---|---:|---:|---:|---:|
| mapped-open | 0.227913→0.234285 (+2.796%) | 0.494473→0.499343 (+0.985%) | 0.535000→0.540000 (+0.935%) | 179.257344→179.900416 (+0.359%) |
| first-ordered | 1.613169→0.800644 (-50.368%) | 2.284169→1.417583 (-37.939%) | 2.330000→1.465000 (-37.124%) | 1113.931776→1116.180480 (+0.202%) |
| warm-ordered | 15.115090→4.968518 (-67.129%) | 16.086278→5.848461 (-63.643%) | 16.137500→5.897500 (-63.455%) | 1122.131968→1123.684352 (+0.138%) |

Mapped-session latency still rises 6.372ms (+2.796%; median +3.534%, maximum observed +0.509%). Its four adjacent wall changes are +6.266%, −0.672%, +1.582% and +4.210%. Whole CPU mean rises 0.935% (median +2.830%, maximum unchanged); RSS mean rises 0.359% (+0.643MB), median 0.696%, maximum falls 0.462%. The earlier CPU overrun is absent in this matching-role batch. This does not retroactively erase earlier measurements or prove classpath mismatch alone caused them. All raw rows, ranges, pairs and blocks remain in the summary; four samples do not establish a population bound. First/warm ordered sessions retain large cumulative latency/CPU gains, with small RSS increases; those gains are not attributed solely to109.

- `/tmp/sootup-static-review/loading-query-operation-protocol/primary24-role-matched109/commands.resolved.json` — SHA-256 `70d6bf2742d15aa14b8c6b0b56bd24503fff9d36190201c958c42501d6f516e5`.
- `/tmp/sootup-static-review/loading-query-operation-protocol/primary24-role-matched109/execution/results.json` — SHA-256 `e4149693e12c61355749871fd2aa7906202911a2d32f997fe869570a4c530db5`.
- `/tmp/sootup-static-review/loading-query-operation-protocol/primary24-role-matched109/execution/summary.json` — SHA-256 `142a4b42d56eb6676a958912ee0931dffad01ce39cbc3fad8af23e2a63840490`.

**Decision:** retain109 as a bounded incremental improvement: it avoids unnecessary sidecar work, preserves tested node/ordinal semantics, and improves matching-parent loading readiness while retaining the adverse controls. It does not establish full recovery. Matching pre-upgrade loading latency is still slower, construction memory remains a separate unresolved requirement, and eager/full-metadata/other-query operations require their own relevant evidence. The user's separate +5% CPU and peak-RSS limits and 8GiB heap ceiling remain unchanged; query gains cannot compensate for another operation's regression. APK work remains deferred.


### 2026-10-07 — Cumulative full-feature Tika/Hive construction at retained109

**Scope:** one fresh construction per corpus/runtime, in the fixed order Tika pre-upgrade→109, Hive109→pre-upgrade, followed by twelve separate correctness JVMs. This is cumulative implementation/reference evidence, not an isolated effect of109 or a repeated-sample acceptance gate. A is frozen pre-upgrade `6f498705009689551c92c6d1ca92f67252ef77c4`; B is frozen109 based on9cf, subsequently integrated as `cdc85935`. The old/current output coverage differs, and exact shared-field cross-version parity remains unproved.

**Protocol:** same M3 Max/macOS14.3/JDK17.0.20.1 host, explicit `-Xmx8g` for every measured and verification JVM. Both runtime classpaths use the matching `TEST_webgraph` role (54 old/57 current entries), retaining required dependency-version differences. The unchanged compiled `CliConstruction` helper asserts default full features (CHA call graph, annotations and cross-method functional dispatch enabled; folding and interprocedural dataflow at their defaults), creates a Mmap graph, scans every node as the CLI does, saves with two compression threads and prepared CallSite string index, then explicitly closes the source. Verbosity is null. This reproduces the construction operations with suppressed status output and explicit close; it is not a literal CLI invocation.

The continuous interval includes build, complete CLI node scan, save and close; `buildPlusSave` is their build/save subtotal and excludes the scan/close. It contains no mapped reload or query. Process CPU is sampled at each phase boundary without forced GC or peak resets. Whole-command wall, user+system CPU and peak RSS include JVM startup, reporting and teardown; RSS is process high-water memory, not heap or save-only memory. Fresh JVMs do not imply cold disk: fixture page-cache state is uncontrolled, and no input prewarming was added. There were no replacement samples or altered settings. Verification timings are excluded.

Real inputs are Tika app2.9.2 (SHA-256 `87e06f88c801fcb2beae5f15e707241edb14da468a154ad78be4e31ff982c3da`) and Hive exec4.0.0 (`232d67c5d2ff54806944bb5b7402eaf1ebb81f11dbe4fd51bc5604a8e0c0bdad`). Command form: `/usr/bin/time -l "$JAVA17" -Xmx8g -cp "$PINNED_HELPER:$FROZEN_TEST_WEBGRAPH_CP" CliConstruction "$INPUT_JAR" "$NEW_OUTPUT"`. Literal arrays, helper/JDK/runtime/input pins, fixed order and all twelve verifier commands are in `/tmp/sootup-recovery-sources/fullfeature-tika-hive-reference109/commands.json`. Common helper class SHA-256 is `406db55c1efa12c64826c9c088200544efcbdaa65e0d63e511aa69cff52cc546`; old/current manifests are `dac9a2659ae54c94d5714605cdc3237dfe04b71649583259a4991083aa6b37fc` / `9dc3514f1125bbdfeb634104737f8e9d19445db71a444ab5e9e11d9682d5c5fb`.

All measured rows follow in execution order. Seconds and decimal GB are used; no samples are pooled across corpora.

| Run | Nodes | CallSites | Continuous wall s | Continuous CPU s | Build+save wall s | Whole wall s | Whole CPU s | Peak RSS GB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 00-tika-old | 4,673,289 | 1,758,353 | 111.545014 | 171.662863 | 111.257881 | 111.68 | 171.79 | 7.989527 |
| 01-tika-109 | 4,620,490 | 1,705,428 | 28.147332 | 82.913644 | 27.773977 | 28.28 | 83.05 | 8.496726 |
| 02-hive-109 | 6,950,016 | 2,370,341 | 50.925078 | 160.244522 | 50.459235 | 51.07 | 160.34 | 9.738519 |
| 03-hive-old | 7,056,494 | 2,476,825 | 92.862211 | 281.458417 | 92.410807 | 93.00 | 281.59 | 9.740468 |

| Run | Build wall / CPU s | Node scan wall / CPU s | Save wall / CPU s | Close wall / CPU µs |
|---|---:|---:|---:|---:|
| 00-tika-old | 105.985598 / 160.529179 | 0.287129 / 0.476298 | 5.272283 / 10.657374 | 4.417000 / 12.000000 |
| 01-tika-109 | 22.083322 / 70.775362 | 0.373352 / 0.620052 | 5.690655 / 11.518217 | 3.875000 / 13.000000 |
| 02-hive-109 | 42.575801 / 136.222340 | 0.465840 / 1.376286 | 7.883433 / 22.645885 | 3.708000 / 11.000000 |
| 03-hive-old | 84.571799 / 262.027675 | 0.451399 / 2.263593 | 7.839008 / 17.167136 | 4.333000 / 13.000000 |

**Observed differences, not population estimates:** Tika continuous wall falls74.766% and whole CPU51.656%, while peak RSS rises507.199MB (+6.348%), exceeding the user's +5% allowance in this single pair. Its save wall rises7.935% (+0.418s), save CPU8.077% (+0.861s), and node-scan wall/CPU about30%. Hive continuous wall falls45.161% and whole CPU43.059%; peak RSS changes−1.950MB (−0.020%). Hive save wall rises0.567% (+0.044s), but save CPU rises31.914% (+5.479s). Whole construction improvements do not make those phase costs disappear. The differing graph coverage and single sample per runtime prevent a stable speedup/cap claim or attribution to any one retained optimization. No significance, tail frequency or favorable statistic is inferred from n=1.

**Terminal correctness and independent audit:** all four constructions and twelve separate verification commands passed. Each of the four outputs has a22-field shape report,13-field metadata/API report and five-query full-value comparison against its own capability-hidden `Graph` fallback. All20 mapped/fallback query pairs match within their respective runtime. Full scans independently agree with each output's source/type-index node and CallSite counts; missing-edge-target count is zero for all four. Persisted edges are Tika5,911,785→5,786,368 and Hive8,559,444→8,317,618. Both corpora's old/current shape, metadata and query reports differ; the complete differing keys and values are preserved, not interpreted as cross-version parity. Changes include node/CallSite/edge coverage, annotation and method fingerprints, synthetic identities and bounded query outputs.

An independent read-only audit re-parsed all raw construction properties and time-l records, checked phase sums/subtotals, exact command arrays with only authorized observed-count substitutions,8g flags, stdout/stderr/report hashes, complete mapped/fallback query rows, and recorded pre/post graph inventories. All four recorded inventories remained unchanged across verification. This audit verifies recorded inventory equality rather than claiming a second independent reread of every large graph file. Evidence and runtime pins remain in the executor packet; no additional JVM was run for this audit.

**Semantic limits:** each output is checked with its own runtime and discovered counts, not forced to match reduced-feature counts or a different SootUp version. Common shape counts, metadata APIs and concrete query outputs are retained, including differences. Existing CallSite `toString`-based shape/connectivity hashes include the added ordinal/origin fields; some metadata comparand hashes inherit that rendering. They cannot establish exact cross-version equality by stripping a few fields after hashing. A missing oracle must explicitly preserve complete caller/callee descriptors, nullable line/receiver, ordered argument references and normalized connectivity while excluding only genuinely new fields. That comparison is still pending. The new ordinal/origin API has no old equivalent, and this packet does not independently validate every new ordinal record. Five bounded queries are controls, not exhaustive loading/query coverage. Null verbosity means internally caught frontend OOM-skip warnings are not counted; successful exits and coverage checks are not proof that no internal skip occurred.

**Conclusion:** preserve these cumulative observations and reference graphs. Latency/CPU gains coexist with an adverse Tika RSS sample and phase costs; this is not full recovery or final CPU/RSS acceptance. User limits remain +5% whole CPU and peak RSS versus matching pre-upgrade, with an8GiB heap ceiling. Construction, loading and query acceptance remain separate; APK work is deferred.

Evidence pins:

- `/tmp/sootup-recovery-sources/fullfeature-tika-hive-reference109/commands.json` — SHA-256 `5cfb12fd8bd24eb1631075ba26d86246d0fc0cd0df6eebf0e406389446cc0159`.
- `/tmp/sootup-recovery-sources/fullfeature-tika-hive-reference109/execution/results.json` — SHA-256 `65cce774ba3418ddb1a91c555f5d9bcb071ed49e3248974ab15d559d45874b6e`.
- `/tmp/sootup-recovery-sources/fullfeature-tika-hive-reference109/execution/cross-version-report-differences.json` — SHA-256 `7432f1725188cfb97d94a366168b63103b309d7f13fd1999f980a199914c6d53`.
- `/tmp/sootup-recovery-sources/fullfeature-tika-hive-reference109/execution/graph-pins-after-verification.json` — SHA-256 `8a9baf2f59196d7b86235f3b729ea4d005ac6fbd40bcd1393ccc99afb5321008`.
- `/tmp/sootup-static-review/fullfeature-tika-hive-reference109-audit/audit.json` — SHA-256 `a1443b55989231c03854edcfa4b9ef8cd92df70c73fbf1ebaa442ef725edde97`.

**Completed upgraded-main parity follow-up:** a separate eight-command packet built both full-feature corpora with frozen main `02b853b7` and then compared all three reports per corpus against109. Both constructions and all six strict comparisons passed. Tika main/current each have4,620,490 nodes and1,705,428 CallSites; Hive each have6,950,016 nodes and2,370,341 CallSites. Every22-field structural report,13-field metadata report and complete five-query JSON report agrees, including values/types/order; each graph's file inventory remains unchanged after verification. Independent review re-read all six report pairs and checked all eight exits/stdout/stderr hashes. This extends the existing Kotlin comparison to both additional real corpora. It proves equality within those checks, not universal graph isomorphism.

The old/current count differences therefore also exist between the pre-upgrade version and upgraded main; they are not count losses introduced by the retained optimization chain. This does not explain every semantic difference between SootUp versions or turn differing old/current outputs into identical graphs. The separate references are n=1 observations, not additional acceptance samples or replacements for the preceding four measurements.

The common quiet helper, default features, `TEST_webgraph` runtime role, two-thread prepared save and8GiB heap setting were retained. Frozen main runtime manifest SHA-256 is `185bdb48e8da1d47528c23e6439a11b24a9b5fd4fd0932f22732cee83e03d770`. Exact commands: `/tmp/sootup-recovery-sources/main02-tika-hive-reference109/commands.json`, SHA-256 `8e503a05a19a5c331cbaee8b52f21bfc53a90a15e01f494b6bf3103fd833001f`. Terminal results: `execution/results.json`, SHA-256 `6a96aaf8d166b9a6f6c5a91000055b41690bfe8fed3a793483a0af10e108bf6e`. All failures would stop the packet; no mismatch, retry or oracle relaxation occurred.


### 2026-10-07 — Attempt 110: compact branch metadata persistence (isolated candidate retained)

**Hypothesis and change:** saving a fresh `MmapGraph` expands packed branch/local definitions into query-view objects and immediately repacks them. Add an optional compact persistence capability to avoid that overlap, preserving grouping, set deduplication/order, nulls, duplicate triples and consumer-owned arrays. Already-materialized mutable public views retain the original fallback. This targets save-phase allocation/retention, not a proven cause or complete solution for the earlier construction RSS peak. The isolated candidate is based on retained109;111 is not included.

**Correctness and provenance:** the first build stopped at Detekt because adding the interface changed the identifier of an existing `TooManyFunctions` baseline entry. Only that existing identifier migrated from `GraphCloseable` to `GraphCloseablePackedBranchMetadataSource`; baseline cardinality, thresholds and production source pins were unchanged. The first failure is retained. Build2 passed769 fresh tests (466core,260webgraph,43query; zero failures/errors/skips), all three module Detekt checks and both JMH artifact-isolation checks. Four new focused tests compare exact metadata/branch-definition bytes and decoded values against capability-hidden generic fallback, interleaved scope grouping, defensive ownership, materialized-view mutation, duplicate triples and malformed lengths. Five temporary publishing changes were restored byte-for-byte. Frozen runtime SHA-256 `45314fd8b7bc078dfa2f89e8afe0177ff635bbe6758e39d9e954588f3786070e`; build proof `/tmp/sootup-recovery-sources/attempt110-build/attempt110-build2/build-proof.json`. Source/base proofs remain under `/tmp/sootup-static-review/attempt110/`, including the preserved four-file patch and rebasing proof.

**Fixed construction pilot:** Kotlin ABBA then Tika ABBA, A=frozen109 and B=frozen110, two fresh8GiB JVMs per arm/corpus. Same quiet full-feature helper, `TEST_webgraph` roles, Mmap frontend, original CLI node scan, prepared two-thread save and source close. Continuous construction includes build, scan, save and close; no query is timed inside that boundary. Whole-command CPU/RSS include startup/reporting. No retries, heap changes, substituted samples or profiling. This is an incremental parent comparison, not a matching pre-upgrade cap check.

All eight observations are retained (seconds and decimal MB):

| Corpus / order | Continuous wall | Build wall | Save wall | Save process CPU | Whole CPU | Peak RSS MB |
|---|---:|---:|---:|---:|---:|---:|
| kotlin-0-A | 37.481997 | 30.185066 | 6.882027 | 16.880478 | 105.31 | 9388.507 |
| kotlin-1-B | 41.374273 | 29.578279 | 11.403375 | 18.073971 | 105.06 | 8439.349 |
| kotlin-2-B | 41.769568 | 30.136358 | 11.277598 | 19.136551 | 102.77 | 7043.645 |
| kotlin-3-A | 37.259400 | 30.044400 | 6.834165 | 16.537842 | 105.28 | 8776.925 |
| tika-0-A | 27.746436 | 21.970260 | 5.430123 | 12.005409 | 83.65 | 8508.113 |
| tika-1-B | 27.649737 | 21.944795 | 5.372757 | 12.065457 | 83.41 | 8660.959 |
| tika-2-B | 27.427122 | 21.884422 | 5.195802 | 11.815583 | 85.05 | 6737.904 |
| tika-3-A | 28.164487 | 22.214062 | 5.614095 | 11.774665 | 84.36 | 8545.370 |

Arithmetic means, candidate minus parent:

| Corpus | Continuous wall A → B | Whole CPU A → B | Peak RSS A → B |
|---|---:|---:|---:|
| Kotlin | 37.371 → 41.572s (+11.242%) | 105.295 → 103.915s (-1.311%) | 9082.716 → 7741.497MB (-14.767%) |
| Tika | 27.955 → 27.538s (-1.492%) | 84.005 → 84.230s (+0.268%) | 8526.742 → 7699.431MB (-9.703%) |

Kotlin's candidate save is slow in both samples: mean6.858→11.340s (+65.36%), while save process CPU16.709→18.605s (+11.35%). Continuous wall rises4.201s (+11.24%) even though whole CPU decreases1.380s (-1.31%) and mean RSS decreases1,341.219MB (-14.77%). This is not an end-to-end latency win. Tika mean continuous wall decreases0.417s (-1.49%) and RSS827.310MB (-9.70%), but whole CPU rises0.225s (+0.27%). Tika B1 RSS8,660.959MB exceeds both parent samples (8,508.113/8,545.370MB); the low B2 sample6,737.904MB contributes strongly to the mean improvement. Kotlin B1 remains8,439.349MB while B2 is7,043.645MB. These high samples and the between-run spread are retained, not dismissed. The two-sample median equals the mean; all minima/maxima/ranges and paired differences remain in the raw summary. No tail-frequency or stable RSS effect is established. Aggregate page faults/context switches/instructions cannot assign the Kotlin save delay to paging, GC or a compiler cause.

**Output checks, outside performance timing:** all24 verification JVMs passed. Each of the eight graphs exactly matches its fixed upgraded-main reference on22 shape properties,13 metadata properties and all five complete query reports, including mapped/fallback parity. Kotlin has4,744,132 nodes/2,251,811 CallSites; Tika4,620,490/1,705,428. Every graph's ordinal sidecar bytes and metadata binding match its fixed reference. Full graph inventories recorded before and after verification are identical. The independent audit rechecked raw log/report hashes, all values/time-l arithmetic, all24 complete report comparisons, actual ordinal SHA256s/binding tails and recorded inventory equality; it did not rehash every other graph data file while queued measurements were pending. These are the stated checks, not universal graph equivalence or old/current semantic identity.

Evidence: `/tmp/sootup-recovery-sources/attempt110-construction-pilot/commands.json`, `resolved-seal.json`, `execution/results.json`, `execution/summary.json` and every retained raw output. Independent audit: `/tmp/sootup-static-review/attempt110/terminal-audit/audit.json`. JMH jars were built and isolated, but this pilot contains no method-level JMH performance measurement.

**Decision: retain110 and integrate its tested changes into the cumulative candidate.** The complete correctness checks pass and both corpora show a positive RSS mean. Preserve this increment for continued optimization rather than discarding it because the overall goal remains unmet. Integration uses the exact five source/test/baseline files from the passing frozen build; other changes since109 are documentation only. The Kotlin latency cost remains an explicit tradeoff, not an omitted result. Matching pre-upgrade resource-cap evidence and investigation of the repeated Kotlin save delay remain necessary for final acceptance, not prerequisites for retaining useful incremental work. The user now prioritizes server-query request-level p50/p95, then end-to-end latency, with construction/loading/query accepted independently; this construction pilot provides no server-query latency evidence. Neither a favorable RSS mean nor an unfavorable parent latency point estimate alone establishes the overall recovery outcome. The user-approved5% whole-CPU/RSS limits and8GiB heap ceiling remain unchanged. No feature reduction or automatic rejection is introduced.

### 2026-10-07 — Attempt 111: initialize the ordinal warning logger only when needed

**Hypothesis and change:**109 avoids sidecar loading on a non-CallSite read, but obtaining `CallSiteOrdinals.EMPTY` still initializes the class and eagerly creates its JUL logger. Change that logger to Kotlin `by lazy`, leaving warning text, level, logger name and all ordinal validation unchanged. The parent is committed109 `cdc85935ada041ebf995ada534f636c4822112e1`; the isolated candidate changes only this production declaration. This is a first-use initialization hypothesis, not a general query or construction optimization.

**Correctness and provenance:**300 fresh tests passed (257 webgraph,43 query; zero failures/errors/skips), both module Detekt gates and both JMH isolation checks passed. The added test asserts exact valid ordinal lookup, quiet absent/valid/unbound sidecars, and exactly one warning with the expected level/name/message for a malformed bound sidecar. Before compilation, review corrected the test to use a separate `validFile`: it must not truncate an already mapped valid input. The original source proof with stale test hash is archived; reviewed pins and actual passing source are authoritative. No failed-test claim or oracle relaxation is inferred from that source correction. Production SHA-256 `a442b5ae38fd3e361f54ddff13f22e70a9edc1afd4a72187d6228aa7000450e6`, test SHA-256 `dffc93adbf1cf341add93f777e1426739411e948163e51c6201c0d2da126de44`. Build proof is `/tmp/sootup-recovery-sources/attempt111-build/attempt111-build1/build-proof.json`; frozen runtime SHA-256 `a55c3e5d18e8784359dd00bfee311d995b3644fd4d076b02d9e95314feb4722f`. Publishing files were restored byte-for-byte.

**Fixed primary36:** three operation groups, each `ABCCBABACCAB`, four fresh JVMs per variant. A is frozen pre-upgrade, B frozen109, C111. All use matching `TEST_webgraph` classpath roles, the same compiled `LoadingQueryOperation` helper,8g heap, and private complete copies of the same merged Kotlin compiler2.0.21 full-feature graph (4,744,132 nodes,2,251,811 CallSites,19 files including ordinal/origin and prepared string-index sidecars). Each copy is sequentially read/hashed before its JVM outside timing. All36 measurements and the candidate five-query oracle passed; query values/order and graph inventories remain preserved. No retries or substituted samples.

Boundaries remain those defined in the cumulative loading record: mapped-open includes first-node decode and close; first-ordered includes a fully consumed ordered request; warm-ordered includes one warmup and ten measured requests. Session wall/process CPU and whole-command time-l CPU/RSS are distinct. RSS includes JVM startup/loading and session work; it is not incremental query RSS. This is a specified page-cache preparation policy, not cold disk. Same M3 Max/macOS14.3/JDK17.0.20.1 host and unchanged8GiB limit.

All arithmetic means are shown (seconds; decimal MB):

| Operation | Session wall A / B / C | Session CPU A / B / C | Whole CPU A / B / C | Peak RSS A / B / C |
|---|---:|---:|---:|---:|
| mapped-open | 0.230351 / 0.232798 / 0.229768 | 0.488802 / 0.502410 / 0.499206 | 0.532500 / 0.542500 / 0.540000 | 180.043776 / 181.137408 / 181.161984 |
| first-ordered | 1.681860 / 0.818979 / 0.813265 | 2.317871 / 1.417866 / 1.479836 | 2.370000 / 1.470000 / 1.532500 | 1107.656704 / 1112.170496 / 1123.176448 |
| warm-ordered | 16.141259 / 4.806791 / 4.784650 | 16.980231 / 5.581594 / 5.588122 | 17.030000 / 5.637500 / 5.642500 | 1110.376448 / 1106.489344 / 1111.863296 |

Incremental B→C mapped-session wall falls1.302% (−3.030ms), whole CPU0.461%, while RSS rises0.014%. First-node wall is5.924→3.891ms (old3.213ms). First-query session wall falls0.698%, but process CPU rises4.371%, whole CPU4.252% (+0.0625s) and RSS0.990% (+11.006MB). Warm-session wall falls0.461%, whole CPU rises0.089% and RSS0.486%. These adverse first-query resources remain part of the decision.

Against pre-upgrade, mapped-session mean wall is only0.253% lower (−0.584ms); median instead rises1.012% and maximum observed rises1.147%. Its whole CPU mean is+1.408%, RSS+0.621% (+1.118MB). First/warm session whole CPU means are−35.338%/−66.867%, RSS+1.401%/+0.134%; the large query gains are cumulative, not111-only. This small loading mean is not proof of general recovery or a stable population cap. All raw rows, ranges, pair/block results and individual request metrics remain in the primary summary, and other workloads/operations remain separate.

**Four-method JMH control:** fixed B→C→C→B groups, one cold fork per method per group, `-prof gc`, average time, fail-on-error, launcher and forks8g. Load methods use1×1s warmup/2×1s measurement; query methods2×1s warmup/3×1s measurement. All four groups completed successfully; this is execution status, not numerical acceptance. As in109, the separate real persisted reduced-feature Kotlin graph has3,292,214 nodes/922,876 CallSites/17 files and no newer ordinal sidecar. These warm method controls are not pooled with full-feature primary sessions and do not directly measure first-use logger initialization.

All four scores in execution order, ms/op:

| Method | B0 | C1 | C2 | B3 | Mean B→C | Change |
|---|---:|---:|---:|---:|---:|---:|
| eager_load | 3251.887958 | 3179.937667 | 3344.745500 | 2819.125812 | 3035.506885→3262.341584 | +7.473% |
| mapped_load | 73.094591 | 74.068754 | 74.798115 | 74.885040 | 73.989815→74.433435 | +0.600% |
| mapped_simpleNodeMatch | 0.051855 | 0.050837 | 0.052054 | 0.051507 | 0.051681→0.051446 | -0.455% |
| mapped_orderedCallSitePropertyLimit | 192.072646 | 186.616127 | 189.276896 | 186.869368 | 189.471007→187.946512 | -0.805% |

GC-profiler bytes/op, all four scores (allocation is neither retained heap nor RSS):

| Method | B0 | C1 | C2 | B3 | Mean B→C | Change |
|---|---:|---:|---:|---:|---:|---:|
| eager_load | 6049207656.00 | 6043215600.00 | 6043215600.00 | 6568574256.00 | 6308890956.00→6043215600.00 | -4.211126% |
| mapped_load | 41232702.29 | 41232817.71 | 41232753.71 | 41232705.14 | 41232703.71→41232785.71 | +0.000199% |
| mapped_simpleNodeMatch | 232737.14 | 232737.12 | 235937.13 | 232737.13 | 232737.14→234337.13 | +0.687467% |
| mapped_orderedCallSitePropertyLimit | 567744247.11 | 567744215.11 | 567744247.11 | 567744247.11 | 567744247.11→567744231.11 | -0.000003% |

| Whole four-method group | Wall s | CPU s | Peak RSS GB (decimal) |
|---|---:|---:|---:|
| run0-B | 26.29 | 61.18 | 6.686163 |
| run1-C | 26.14 | 57.51 | 7.378387 |
| run2-C | 26.40 | 64.02 | 8.854061 |
| run3-B | 24.65 | 57.16 | 7.073759 |

The eager-load mean worsens7.473%, including the parent's faster2.819s fork; no sample is dropped. Mapped-load worsens0.600%. Simple-match latency is nearly flat but its allocation rises0.687%; ordered-query latency falls0.805%. Candidate whole-group RSS samples7.378/8.854GB exceed both parent6.686/7.074GB samples; whole-group CPU is mixed. Those high-water figures span four methods, setup, warmup, teardown and unequal fixed-duration operation counts. They neither quantify each operation's CPU/RSS nor justify ignoring the adverse direction. Two forks per variant do not establish causality or stable tail behavior. No automatic recovery conclusion follows from successful exits.

**Exact reproduction and evidence:** primary command form is `/usr/bin/time -l "$JAVA17" -Xmx8g -cp "$COMMON_HELPER:$FROZEN_TEST_WEBGRAPH_CP" LoadingQueryOperation "$MODE" "$PRIVATE_GRAPH" "$REPORT"`. JMH uses `/usr/bin/time -l "$JAVA17" -Xmx8g -cp "$FROZEN_RUNTIME_AND_COMMON_JMH_CP" org.openjdk.jmh.Main "$ANCHORED_FOUR_METHOD_REGEX" -p corpus=KOTLIN_COMPILER -f 1 -prof gc -foe true -rf json -rff "$RESULT" -jvm "$JAVA17" -jvmArgsAppend "-Dkotlin.compiler.graph.path=$PRIVATE_GRAPH"`, with unchanged8g fork/iteration metadata. The four methods are exactly the table's `LargeCorpusLoadBenchmark` load methods and `LargeCorpusQueryBenchmark` query methods. Literal arrays/regex/classpaths, source/runtime/JDK/graph pins and every raw sample follow:

- `/tmp/sootup-recovery-sources/attempt111-build/reviewed-source-pins.json` — SHA-256 `673153e59fd6b0527dd2a51e182e31f7449db5b0e1dedd3e094529440a74b91f`.
- `/tmp/sootup-recovery-sources/attempt111-build/attempt111-build1/build-proof.json` — SHA-256 `e0a4786ab89361b8c55b28b8810b1dd209645a57f079093999794292ff1263ef`.
- `/tmp/sootup-static-review/loading-query-operation-protocol/attempt111-primary36/commands.resolved.json` — SHA-256 `37da17c39a2bdbde3ecdbf6ddc42b3bfe501cf062c16c1fbe6f81ff1430f730f`.
- `/tmp/sootup-static-review/loading-query-operation-protocol/attempt111-primary36/execution/results.json` — SHA-256 `bd9e983fa8c7e82372e6447ae2d43b51eafe346ad3753dc2fbed9966f64944b2`.
- `/tmp/sootup-static-review/loading-query-operation-protocol/attempt111-primary36/execution/summary.json` — SHA-256 `9cb5014d5d9f83b4461d7d674c16c017022fca9da29b232e10711c6cf54043ad`.
- `/tmp/sootup-recovery-sources/attempt111-jmh/commands.json` — SHA-256 `d254157258c6128c29db178ae9e1c1e82bbed3eecc14bd6bbbd6b37e3fe926f0`.
- `/tmp/sootup-recovery-sources/attempt111-jmh/execution/results.json` — SHA-256 `09f2345f05aa32665b2bd8ef6efd8b32f4cc0adbf62063dafc27f7dcdc3dca38`.
- `/tmp/sootup-recovery-sources/attempt111-jmh/execution/summary.json` — SHA-256 `4f3692ce7c3fa52fb21a1550413b8443f6ff297406c9ef82034bc9371a43bc78`.

**Initial decision before the eager12 follow-up:** retain111 as an isolated candidate, not yet integrated. Direct-operation loading shows a small incremental benefit, while eager-load JMH and group resource evidence remain unresolved; this is not a claim that all performance controls passed. The candidate is not rejected merely because overall recovery is incomplete, and no general recovery or final acceptance is asserted. Attempt110 continues from retained109 so that111’s unresolved change does not enter its attribution. The user’s independent +5% whole CPU/peak-RSS limits versus pre-upgrade and8GiB heap ceiling remain; all earlier construction and pressure evidence is retained. APK work remains deferred.

**Eager12 follow-up: missing independent operation evidence.** The preceding mapped-only direct protocol and parent-comparison JMH did not establish eager loading against pre-upgrade. Run exactly12 fresh JVMs on the same full real Kotlin graph: A=pre-upgrade, B=109, C=111, fixed `ABC / CBA / BAC / CAB`, four samples each. All use matching `TEST_webgraph` runtime roles, one common helper compiled once against A, explicit8GiB heap, and new private copies of all19 identical files (578,075,575 bytes), including ordinal/index sidecars. Every copy is sequentially read and hashed immediately before its JVM. No profiling, forced GC, warmup, retries, dropped samples or graph substitutions were used. This is a fixed pre-read policy, not disk-cold loading.

The primary loading boundary is `GraphStore.load(EAGER)` start through return, when all nodes are decoded by the loader. A separate validation phase reads metadata node/CallSite counts and two existing values; it performs no query or full enumeration. Backward/branch query views retain their API's lazy behavior. Eager `WebGraphBackedGraph` has no close API in all three variants: the helper records a conditional no-op and normal JVM process exit releases state. No nonexistent close-cleanup cost is claimed. The complete internal session includes validation and the conditional lifecycle check; outer time-l CPU/RSS additionally include startup, reporting and exit. Operation CPU, session CPU and whole CPU remain distinct, with no subtraction of peak RSS.

All12 actual samples (seconds; decimal MB), in their fixed order:

| Order / variant | Eager load wall | Eager load process CPU | Internal session wall | Whole CPU | Peak RSS MB |
|---|---:|---:|---:|---:|---:|
| eager-open-00-A | 4.825314 | 17.384225 | 4.827476 | 18.80 | 7370.408 |
| eager-open-01-B | 5.289560 | 23.915921 | 5.291656 | 24.87 | 6407.176 |
| eager-open-02-C | 5.414338 | 23.966313 | 5.416350 | 24.15 | 6855.606 |
| eager-open-03-C | 4.781810 | 17.122054 | 4.783717 | 18.77 | 7381.762 |
| eager-open-04-B | 5.010433 | 23.132663 | 5.012428 | 23.98 | 6410.813 |
| eager-open-05-A | 5.054480 | 21.566486 | 5.056401 | 23.05 | 6657.393 |
| eager-open-06-B | 5.290974 | 22.824363 | 5.292958 | 24.01 | 6536.479 |
| eager-open-07-A | 5.106215 | 22.745792 | 5.108174 | 23.86 | 6346.867 |
| eager-open-08-C | 4.984606 | 17.642388 | 4.986436 | 17.70 | 7024.525 |
| eager-open-09-C | 5.384180 | 22.766359 | 5.386211 | 24.55 | 6362.612 |
| eager-open-10-A | 5.019932 | 22.075425 | 5.021942 | 23.59 | 6662.701 |
| eager-open-11-B | 5.328436 | 22.843095 | 5.330335 | 23.17 | 6597.935 |

Predeclared arithmetic means:

| Metric | A pre-upgrade | B109 | C111 | C vs A | C vs B |
|---|---:|---:|---:|---:|---:|
| Load-return wall s | 5.001485 | 5.229851 | 5.141233 | +2.794% | -1.694% |
| Load-return process CPU s | 20.942982 | 23.179011 | 20.374278 | -2.715% | -12.100% |
| Internal session wall s | 5.003498 | 5.231844 | 5.143178 | +2.792% | -1.695% |
| Internal session process CPU s | 20.961396 | 23.189210 | 20.377809 | -2.784% | -12.124% |
| Whole command wall s | 5.525000 | 5.567500 | 5.512500 | -0.226% | -0.988% |
| Whole command CPU s | 22.325000 | 24.007500 | 21.292500 | -4.625% | -11.309% |
| Peak RSS MB | 6759.342080 | 6488.100864 | 6906.126336 | +2.172% | +6.443% |

Distribution summaries are retained rather than replacing the primary mean:

| Metric | A median [min, max] | B median [min, max] | C median [min, max] |
|---|---:|---:|---:|
| Load-return wall s | 5.037206 [4.825314, 5.106215] | 5.290267 [5.010433, 5.328436] | 5.184393 [4.781810, 5.414338] |
| Whole CPU s | 23.320000 [18.800000, 23.860000] | 23.995000 [23.170000, 24.870000] | 21.460000 [17.700000, 24.550000] |
| Peak RSS MB | 6660.046848 [6346.866688, 7370.407936] | 6473.646080 [6407.176192, 6597.935104] | 6940.065792 [6362.611712, 7381.762048] |

C's mean eager latency improves88.618ms (-1.694%) versus109, but remains139.748ms (+2.794%) above pre-upgrade. Mean whole CPU decreases2.715s (-11.309%) versus109 and1.0325s (-4.625%) versus old. RSS increases418.025MB (+6.443%) versus109 and146.784MB (+2.172%) versus old. The parent-relative RSS increase is adverse and retained; it is not itself the user-defined old-relative cap comparison. The old-relative resource means are within5% in this packet, but wide CPU/RSS ranges and mixed individual triplets remain: no stable cap guarantee or tail-frequency conclusion follows from four samples. Every triplet and both fixed six-JVM block comparisons remain in `summary.json`; no mean/median/max was selected after seeing results. The original eager JMH +7.473%, mapped JMH +0.600% and high four-method-group RSS samples above remain unchanged. These protocols have different warmup/repetition/session boundaries; the follow-up does not erase or causally explain the earlier results.

All12 bounded output checks passed:4,744,132 nodes,2,251,811 CallSites, first IntConstant value1, first callee name`<init>`, expected eager implementation and exact runtime origins. Actual max heap is8,589,934,592 bytes with only `-Xmx8g`; no extra query or warmup occurred. The independent audit rehashed every input-seal artifact, all19 actual files in each of the12 private copies and the original graph, verified report/stdout/time-log hashes, recomputed every phase/whole metric and all summary statistics, and checked origins against pinned classpaths. File contents were unchanged. These bounded eager checks supplement existing full reference shape/metadata/query/ordinal proofs; they do not claim new exhaustive eager graph equivalence.

Evidence is `/tmp/sootup-static-review/attempt111-eager12/`: `LoadingQueryOperation.java`, `commands.draft.json`, `execution-seal.json`, compile-once receipts and every raw output. The measured command is `/usr/bin/time -l "$JAVA17" -Xmx8g -cp "$COMMON_EAGER_HELPER:$FROZEN_TEST_WEBGRAPH_CP" LoadingQueryOperation eager-open "$PRIVATE_GRAPH" "$REPORT"`. Literal commands, runner, source/class/JDK/fixture pins and all12 order entries are sealed. Authoritative hashes:

- `execution/results.json` — SHA-256 `5f5812fe7f6da3bb2ed3f13ed4b7e8927614637c9b95642c6aa22e77bc9df659`.
- `execution/summary.json` — SHA-256 `815d16171d68614c11703d144c98c32f54e5829bd48b3a0f534cfd3f0a7bf941`.
- `execution-seal.json` — SHA-256 `938631d65191c2cee65d1ac2c96b05d9fa6c7508ff48004bc2fdf4cb88196e71`.
- `independent-audit/audit.json` — SHA-256 `536c2396c266fae09ab954217ee0839f15c0cccf7fdf7bf681a3fc3979a4568d`.

**Updated decision: retain111 and include its exact tested production/test change in the cumulative recovery plan.** Both direct mapped and eager loading provide an incremental parent-relative latency gain; the user requires retaining verified positive work rather than rejecting it because overall recovery is unfinished. Root owns source integration and commit; this record does not claim that integration has already happened. Attempt110's preceding experiment remains based on109, preserving its attribution. Eager latency versus old is still2.794% higher, and adverse JMH/resource samples remain unresolved work. Final acceptance still requires independent construction/loading/query evidence under the5% CPU/RSS and8GiB constraints. Server-query request-level p50/p95 is the user's first priority: neither this eager protocol nor the CI percentile across different query cases measures that repeated-request distribution. Any later server acceptance must state request mix, concurrency and cold/warm boundaries and retain request-level latency samples. APK remains deferred; no general recovery is claimed.


### 2026-10-07 — Retained109 exact-head CI: passing gates with recurring pressure regressions

Head `cdc85935ada041ebf995ada534f636c4822112e1`, base `02b853b7e5588034274d292163b79495a9ef8743`: Benchmark37569675281, JVM37569675293 and Rust37569675274 completed successfully. All41 enabled benchmark jobs passed, including required gate112629135345. The [standard benchmark comment](https://github.com/johnsonlee/graphite/pull/171#issuecomment-6025051872) records this comparison; its mutable contents are archived under `/tmp/sootup-static-review/ci-cdc85935/`. Neither isolated110 nor111 is included in this head.

All14 method rows passed; the largest adverse result was `nodeMatchWithWhere` +3.1%. Reduced-feature4GiB real-corpus pipeline changes versus upgraded main were Tika−10.1%, Hive+1.3%, Kotlin−10.3%. Hive build+2.7%, save+4.5%, mapped load+2.7% and branch definitions+5.4% remain recorded. These pipeline/heap observations do not establish separate operation CPU/RSS acceptance against pre-upgrade.

The64-fixture global-wide JVM replay again showed unfavorable dense wrapped case-insensitive DISTINCT results. All three pairs return the same200 rows and complete-result digest, with matching graph-work counts:

| Pair / execution order | Base latency ms | Candidate latency ms | Change |
|---|---:|---:|---:|
| 1 / candidate-base | 176.028528 | 309.427591 | +75.783% |
| 2 / base-candidate | 148.189034 | 235.964693 | +59.232% |
| 3 / candidate-base | 190.455357 | 308.485323 | +61.973% |

These rows also supply the report's paired P95 values. The base-first pair now worsens too: the earlier9cf order pattern cannot explain these results by itself. Whole-replay CPU is4.22→5.01s in pair1 (+18.72%),4.15→4.20s in pair2 and4.03→4.16s in pair3; pair3 peak RSS is5,138,542,592→5,919,449,088 bytes (+15.20%). Four-properties/zero and localized-late/dense are also repeatedly adverse. Correctness/integrity are required here, while numerical performance is advisory; job success does not resolve these regressions. Recurrence does not establish that109 caused them. No new causal profiling evidence is available.

Routing request-selected P95 also worsens23.42% (P50+4.83%), despite no reported advisory errors; startup-prepared graphId P95 improves to1.04x. The separate Rust artifact has identical actual base/candidate binary hashes and cannot explain away the JVM results.

Independent review re-read the three dense-DISTINCT raw TSV pairs and verified latency arithmetic, result digests, row counts and work counts. The terminal report and five artifacts are retained at `/tmp/sootup-static-review/ci-cdc85935/`; `terminal-report.md` SHA-256 is `cb87a2a11f969bcdbc56ec755bcc8d66163580dfa036c857994678393758a410`. PR171 remains draft and its updated body explicitly records these limits; its exact readback and hashes are archived in `pr-update-proof.json`. No CI rerun or sample replacement was performed. Full recovery remains unproved.


### 2026-10-07 — Attempt 112: skip repeated decoding of accepted DISTINCT projection tuples

**Hypothesis and scope:** the raw serial and split-worker CallSite DISTINCT loops decode projected strings and allocate a list for each matched node before deduplicating it. Add a worker-local content-keyed `IntArray` set for tuples that already produced an accepted row. Reuse a scratch probe; copy it only when a row is accepted, so retained keys are bounded by the requested result limit. Keep the existing string-value `seenValues` set authoritative: different loaded string IDs may contain equal text. Selected-value filtering, first encounter offsets, scan work accounting, cancellation, worker joining and cross-graph provenance remain intact. Low-repeat inputs pay extra key hashing, accepted-key copies and table storage; benefit was not assumed. This existing decoding cost is present in upgraded main too, so it is a recovery opportunity, not a demonstrated cause of the recurring CI regression.

Parent A is frozen109, committed `cdc85935ada041ebf995ada534f636c4822112e1`; B is isolated112 from that exact parent. Neither110 nor111 is included in either measured arm. Production changes only `MappedWebGraphBackedGraph.kt`, SHA-256 `02aae75fad36d1513ddb372aca260061bd034b4615c96cba401305ee8a227ef7`. The source remains isolated at `/tmp/graphite-attempt112` for further evaluation.

**Correctness and first failure:** build1 compiled and passed lint, then one of the five new tests failed: expected first offsets `[8,63512,106093]`, actual `[8,106,106093]`. The fixture incorrectly equated increasing NodeID with physical encounter order. `GraphStore` preserves original NodeIDs but writes nodes in `DefaultGraph`'s hash-map iteration order; another beta node was physically encountered before ID3000. Only the fixture was corrected to explicitly enumerate nodes by ID, preserving the intended long consecutive duplicate runs and the concrete first-ID/offset expectations. Production was not sorted or changed, and the failed XML/source are retained. Corrected test SHA-256 `91cb490752b5b32dfeeb51d992cc4bffd76edf85b9168ea9a7a72880b79fa13a`.

Build2 passed304 fresh tests (261 webgraph,43 query; zero failures/errors/skips), both module Detekt checks and both JMH isolation checks. The five focused tests assert first offsets and full serial/split rows, multiple/null/repeated projected columns, selected values, distinct IDs with equal loaded text, and duplicate-run budget/cancellation behavior with released workers. Existing provenance, budget and cancellation coverage remains enabled. Publishing files were restored. Build proof: `/tmp/sootup-recovery-sources/attempt112-build/attempt112-build2/build-proof.json`.

**Real64 diagnostic protocol:** one existing `Fixture64GraphPreparation --verify` preflight, then exactly four fresh ABBA replay forks, no retries or sample replacement. The common frozen109 `LargeBroadQueryPressureBenchmark.replayBroadQueries` runs all34 `global-wide` cases with `graphCount=64`, `indexState=cold`, `timeoutMillis=300000`, zero warmup, one single-shot iteration, one thread/fork, fail-on-error and `-prof gc`. Both production classpaths use matching `TEST_webgraph`57-entry roles ahead of the same common JMH jar. Launcher and fork explicitly use8g, on the same local macOS/JDK17.0.20.1+0 host,16 available processors. Cold denotes the harness index state, not cold OS pages: every command has the same sequential graph read/hash conditioning outside timing. No feature, cancellation or resource-budget reduction was introduced.

The fixture is the exact cdc CI shared artifact11460362339:64 distinct real class shards from pinned Android14, Tika2.9.2, Hive4.0.0 and Kotlin compiler2.0.21 JARs. Archive713,650,469 bytes, extracted1,220 files/10,428,802,366 bytes. Safe extraction rejected absolute/traversing/symlink paths; archive/API SHA, all file pins,64 CallSite-index hashes, four source-JAR identities, framed workload identities and manifest/provenance/reproducibility evidence matched. Physical paths alone were relocated. The runtime preflight subsequently passed; this is not a synthetic scalability fixture. Every measured replay validated all34 complete result records, ordered row digests and provenance against the existing oracle (136 measured records total); all work/path counters remain in raw TSVs. All five recorded post-run inventories equal the sealed graph hashes.

**All query observations:** ms, execution order A0/B1/B2/A3. Row labels omit the common `global-wide-` prefix only. Each entry is one execution of that case per fresh replay, not repeated samples of one server endpoint.

| Case | A0 | B1 | B2 | A3 | Mean B vs A |
|---|---:|---:|---:|---:|---:|
| four-properties-zero | 153.609250 | 165.452083 | 170.042459 | 164.871833 | +5.342% |
| four-properties-targeted | 11.370792 | 13.006709 | 11.855666 | 12.448625 | +4.379% |
| four-properties-dense | 3.871750 | 3.665333 | 3.984459 | 3.742500 | +0.467% |
| class-pair-zero | 3.250833 | 1.838416 | 2.134417 | 2.544541 | -31.448% |
| class-pair-targeted | 4.615709 | 3.188500 | 5.405125 | 4.226250 | -2.809% |
| class-pair-dense | 0.877958 | 0.990458 | 1.045125 | 0.870208 | +16.441% |
| name-pair-zero | 1.836459 | 2.366500 | 1.751417 | 1.294875 | +31.507% |
| name-pair-targeted | 5.031375 | 3.656333 | 3.182000 | 3.429084 | -19.173% |
| name-pair-dense | 0.608417 | 0.571458 | 0.625792 | 0.666875 | -6.120% |
| caller-class-zero | 1.799500 | 1.070291 | 1.038292 | 1.078958 | -26.746% |
| caller-class-targeted | 4.581875 | 2.072375 | 2.419583 | 2.366500 | -35.352% |
| caller-class-dense | 0.688750 | 0.530250 | 0.546000 | 0.549250 | -13.065% |
| callee-class-zero | 1.008167 | 1.038792 | 0.995209 | 1.043834 | -0.877% |
| callee-class-targeted | 2.224167 | 2.083000 | 2.105000 | 1.929917 | +0.816% |
| callee-class-dense | 0.574000 | 0.539917 | 0.435750 | 0.458250 | -5.482% |
| provenance-zero | 0.868000 | 0.864541 | 0.955917 | 1.002500 | -2.675% |
| provenance-targeted | 2.721625 | 2.429959 | 2.604292 | 2.501666 | -3.619% |
| provenance-dense | 0.876500 | 0.936334 | 0.961416 | 0.867958 | +8.787% |
| aliased-zero | 0.994959 | 1.069166 | 0.943084 | 1.014458 | +0.141% |
| aliased-targeted | 3.169500 | 2.942125 | 3.157500 | 3.090917 | -2.568% |
| aliased-dense | 0.816416 | 0.877458 | 0.801750 | 0.822333 | +2.469% |
| parameterized-zero | 0.958000 | 1.121209 | 0.955292 | 1.085625 | +1.609% |
| parameterized-targeted | 1.953791 | 2.092375 | 2.185042 | 1.801041 | +13.918% |
| parameterized-dense | 0.793584 | 0.672583 | 0.759041 | 0.778708 | -8.947% |
| wrapped-case-insensitive-zero | 1.029458 | 0.990833 | 1.025750 | 1.000625 | -0.665% |
| wrapped-case-insensitive-targeted | 1.971042 | 1.879459 | 2.249375 | 1.877167 | +7.292% |
| wrapped-case-insensitive-dense | 1.501959 | 1.396042 | 2.676666 | 1.450334 | +37.951% |
| wrapped-case-insensitive-distinct-zero | 3.099083 | 3.774084 | 3.256042 | 3.505250 | +6.447% |
| wrapped-case-insensitive-distinct-targeted | 33.557167 | 32.043833 | 44.795125 | 30.898667 | +19.212% |
| wrapped-case-insensitive-distinct-dense | 45.619917 | 46.105750 | 39.943666 | 43.749708 | -3.715% |
| distribution-broad-all-64 | 0.764542 | 0.903209 | 0.786125 | 0.787250 | +8.863% |
| distribution-localized-early | 1.364208 | 1.302042 | 1.277625 | 1.284291 | -2.599% |
| distribution-localized-late | 3.635833 | 3.605250 | 3.733958 | 3.687209 | +0.221% |
| distribution-localized-middle | 2.522833 | 2.560542 | 2.217667 | 2.260542 | -0.108% |

The target dense wrapped DISTINCT mean is44.684813→43.024708ms (**−3.715%**), with mixed adjacent pairs **+1.065% / −8.700%**. Its targeted control instead rises32.227917→38.419479ms (**+19.212%**), pairs−4.510% / +44.974%. Zero-hit and non-DISTINCT controls above are retained. There is no matched-tuple/duplicate-count profile proving how much decoding was avoided or assigning any time difference to that mechanism.

**Resource and timing boundaries:** internal `wallNanos`/`processCpuNanos` cover the instrumented complete replay; per-case wall is separate. Whole-command `/usr/bin/time -l` includes launcher/fork startup, fixture setup and correctness/reporting. Its CPU is user+sys and RSS is whole-process peak, not per-query CPU/RSS. JMH's primary single-shot duration also includes work outside the inner replay timer; none of these boundaries is silently substituted for another.

| Order | Internal replay wall s | Internal process CPU s | JMH single-shot s/op | Whole wall s | Whole CPU s | Peak RSS GB (decimal) |
|---|---:|---:|---:|---:|---:|---:|
| run0-A | 0.399359 | 2.541376 | 0.418170 | 8.61 | 12.23 | 5.041160 |
| run1-B | 0.381076 | 2.918254 | 0.398698 | 8.66 | 12.75 | 5.154832 |
| run2-B | 0.394535 | 3.081147 | 0.415048 | 8.86 | 13.00 | 5.278958 |
| run3-A | 0.375599 | 2.701649 | 0.391753 | 8.78 | 12.64 | 5.001937 |

Arithmetic-mean internal replay wall is0.387479→0.387805s (**+0.084%**), with pairs−4.578%/+5.042%. Internal process CPU is2.621512→2.999701s (**+14.426%**), and both CPU pairs worsen (+14.830%/+14.047%). Whole CPU12.435→12.875s (**+3.538%**), whole wall8.695→8.760s (+0.748%) and peak RSS5.021549→5.216895GB (**+3.890%**). The second RSS pair worsens5.538%; it is not hidden by the mean. Lower diluted whole-command CPU growth does not erase the internal replay increase. With two observations per arm, the median equals the mean; raw minima/maxima/ranges and both pair differences remain in the summary. None establishes old-relative resource acceptance.

GC-profiler allocation is4,860,937,308→4,861,895,156 B/op (+0.019705%); all four values are4,860,976,064 /4,860,788,528 /4,863,001,784 /4,860,898,552. Reported GC counts are17/18/18/17, GC elapsed70/65/66/72ms. These profiler statistics neither measure retained memory nor isolate the projection cache. No causal GC/JIT explanation is asserted. The report's cross-case P50 points are1.836459/1.838416/2.105000/1.450334ms (candidate mean+19.978%), P95 points45.619917/46.105750/44.795125/43.749708ms (+1.713%). Those percentiles describe different query cases in one replay; **they are not repeated HTTP server-request p50/p95** and cannot establish the user's first-priority server goal.

**Evidence and independent audit:** literal commands and artifact/source/fixture/JDK pins are in `/tmp/sootup-static-review/attempt112/replay-packet/commands.json` and `seal.json`. The measured form is `/usr/bin/time -l "$JAVA17" -Xmx8g -cp "$FROZEN_TEST_WEBGRAPH_CP:$COMMON109_JMH" org.openjdk.jmh.Main '^io\.johnsonlee\.graphite\.webgraph\.LargeBroadQueryPressureBenchmark\.replayBroadQueries$' -p graphCount=64 -p coverageFamily=global-wide -p indexState=cold -p timeoutMillis=300000 -wi 0 -i 1 -f 1 -to 30m -foe true -prof gc -rf json -rff "$RESULT" -jvmArgs "-Xmx8g -Dgraphite.broad.pressure.graphs=$MANIFEST -Dgraphite.broad.pressure.correctness.mode=verify -Dgraphite.broad.pressure.correctness.oracle=$ORACLE -Dgraphite.broad.pressure.observations.output=$TSV"`. No CI dispatch or publication wrapper was run.

Authoritative SHA-256 pins:

- Parent109 runtime: `9dc3514f1125bbdfeb634104737f8e9d19445db71a444ab5e9e11d9682d5c5fb`.
- Candidate112 runtime: `d6a05ff5b92b7f1efffaa33f66e8604a2067e0d15f0b5995c9be79ea1b04d6f4`.
- Common109 `webgraph-jmh.jar`: `3a65e7ddedc053f49988fd1a72929be649144e24663f737e8b05cc7cb247ce1b`.
- Fixture archive: `b7e233cb1c5bb27306ccb0ee518a7b5b37fb84bc3a6a00e31115b2550684c01a`; extracted inventory: `a5ad960c424ce9d04ca02ad50c88e648be13bcaf99f39af19712e3bc4c65ad80`.
- Local manifest: `19c6b2bda4644583bdc11cc493ea4e6167871b38b7adca7870df4e58f693359b`; full34-query oracle: `0b762ca78cd9be246ad32710eafdd1ddaebac25f56102410564c194f4c4de487`.
- Commands: `04e972b9d68c18b7a3786b69fde889fb02ce19f458ea74f13bcec779fbccd752`; seal: `2686df31885734b1d519cbd535a1559b8dad349039853a4a6b0844f0f67dfb4c`.
- Raw `execution/results.json`: `80d97c3baed9c343cdb4cb0a64db3f50c704c35c1dbf956e72d6896a2a22a3a1`; summary: `3f83e8310bd3daa1562f327f02a92d2e00426103765018138da5a5e83f30cc5f`.
- Independent `/tmp/sootup-static-review/attempt112/independent-audit/audit.json`: `1b877cebb8af5035445523d5087eb8f6aebe5fe12a23e5d5e51969902bc0ab5a`.

The independent audit rehashed raw outputs and command/manifest pins, re-read all136 TSV rows against the oracle, recomputed every reported query/resource statistic from raw JSON/time logs, and checked archived XML hashes/timestamps/totals for all304 fresh tests. It compared all five recorded graph inventories to sealed extracted hashes; it did not repeat a10.43GB graph read while other work was queued. This is bounded evidence, not a new universal equivalence claim.

**Decision: retain112 as an active isolated candidate for targeted request-level verification.** The local dense-DISTINCT mean benefit is positive and correctness passes; preserve that increment for evaluation rather than rejecting it because overall recovery remains incomplete. The mixed target pairs, targeted regression, internal CPU increase, allocation and adverse controls remain explicit. Do not integrate112 as an established fix or claim server/overall recovery from this pilot. Next evaluation must measure repeated real server requests with a fixed mix/concurrency and stated first-use/warm boundaries, full result/provenance checks, and matched old/current server runtimes. The user prioritizes server-request p50/p95, then end-to-end latency; independent operation CPU/RSS≤5% versus pre-upgrade, maximum8GiB Java heap, correctness and stability remain unchanged. APK remains deferred.


### 2026-10-07 — Cumulative 341: repeated HTTP requests against pre-upgrade

**Question:** test the user's primary server-request p50/p95 target directly, before further construction experiments. A is pre-upgrade `6f498705009689551c92c6d1ca92f67252ef77c4`; B is cumulative `34122563c3bd3cba3b529bdc9e5f20d454aaf779`, including retained110/111 and excluding isolated112. Test-only `f8e4105ea50d6546d0f456c07a4381252b59049a` has identical production sources. Both use frozen `MAIN_query` classpaths (84/88 entries), the same JDK17.0.20.1, M3 Max16-core/macOS14.3 host, real64 JAR-shard graphs and complete34-case HTTP workload described above. APK remains deferred. Every server explicitly uses `-Xmx8g`, mapped loading, max-concurrent-cypher4 and timeout60000ms; default work limits/features remain unchanged.

**Fixed protocol and interruption:** concurrency1 ABBA, then concurrency4 ABBA, eight fresh servers. Each process receives one first-use cycle, two warm cycles and30 measured cycles of34 cases, rotating case order by cycle; bounded waves issue at most the declared concurrency. No Cypher readiness warmup. Every process receives1122 requests,1020 measured,30 measured samples per case. Request latency starts before HTTP send and ends after complete body read; JSON validation and disk reporting occur outside individual latency, between waves. Connections are new per request. These are empirical nearest-rank percentiles (n30 gives coarse tails), reported as arithmetic means of two process-level percentiles with both AB/BA pairs and raw ranges retained. They are not pooled production-population estimates.

The first old process completed normally. The second process's bare socket preflight returned EADDRINUSE before any JVM or query started. Original failure and firstA were preserved. Later read-only checks found no listener or TCP entry; TIME_WAIT was plausible but not observed. A separately reviewed continuation ran exactly the remaining seven servers on predetermined ports18083–18089. No completed sample was reissued/replaced. The first AB pair therefore contains a pause; the combined series is explicitly interrupted. All eight actual servers completed and shut down using owned SIGTERM, with no forced kill.

**Correctness:** all8976 complete HTTP responses match the independently reviewed109 oracle, including types, column and row order and nested `$metadata` graph provenance. The oracle itself was independently decoded and matched to all34 existing engine framed-value digests and byte counts, including metadata. Three parameterized engine cases use literal-equivalent HTTP text because the endpoint has no bound-parameter interface; this does not replace their separate engine parameter tests. All64 graph identities/readiness counts and all19 files per graph match frozen inventories before/after each session. Runtime artifacts and JDK/source pins were checked. Full sorted graph reads before each process condition OS pages equally; this is not cold-page evidence.

**Resources:** measured-window CPU is server process user+system around the30 cycles, including gaps between requests; client CPU is separate. Whole-lifetime wall/CPU/peak RSS includes startup, all64 mapped loads, first-use, warmup, measured requests and teardown. These scopes cannot establish per-case query resource caps or separate loading recovery. RSS is decimal GB below, separate from heap maximum.

| Run | Lifetime wall s | Lifetime CPU s | Peak RSS GB | Measured-window server CPU s |
|---|---:|---:|---:|---:|
| c1-0-A | 19.13 | 19.29 | 5.138874 | 6.19 |
| c1-1-B | 19.51 | 19.30 | 5.884772 | 6.01 |
| c1-2-B | 19.49 | 19.18 | 5.038146 | 6.07 |
| c1-3-A | 20.03 | 20.53 | 5.859787 | 6.43 |
| c4-0-A | 13.64 | 20.30 | 5.840437 | 6.75 |
| c4-1-B | 13.42 | 20.11 | 5.039030 | 6.26 |
| c4-2-B | 13.69 | 20.42 | 5.799723 | 6.35 |
| c4-3-A | 13.65 | 20.46 | 5.080383 | 6.91 |

Mean lifetime CPU/RSS changes are−3.365%/−0.689% at concurrency1 and−0.564%/−0.751% at concurrency4; measured-window server CPU changes are−4.279%/−7.687%. RSS paired changes are **+14.515%/−14.022%** and **−13.722%/+14.159%**, respectively. Means below the caps do not erase this variability or establish each operation's resource acceptance. No causal GC, JIT or scheduling diagnosis follows from these counters.

**Request latency:** means of run-level fixed-mix request p50/p95 are1.071521/2.119688→1.064000/2.068063ms at concurrency1 (−0.702%/−2.435%) and1.879979/7.274083→1.876438/7.106584ms at concurrency4 (−0.188%/−2.303%). These distributions comprise1020 actual requests per process and are separately labeled; favorable mixed-workload percentiles cannot compensate for worse individual cases. All case means follow; raw process-level percentiles, sample arrays and both pair deltas remain in the audit report.

Concurrency 1

| Case | Old p50 ms |341 p50 ms | Δp50 | Old p95 ms |341 p95 ms | Δp95 |
|---|---:|---:|---:|---:|---:|---:|
|global-wide-four-properties-zero|0.933|0.925|-0.85%|1.354|1.400|+3.37%|
|global-wide-four-properties-targeted|1.328|1.315|-1.01%|1.782|1.810|+1.58%|
|global-wide-four-properties-dense|0.930|0.914|-1.70%|1.330|1.367|+2.84%|
|global-wide-class-pair-zero|0.903|0.902|-0.15%|1.337|1.459|+9.11%|
|global-wide-class-pair-targeted|1.303|1.288|-1.14%|1.738|1.691|-2.70%|
|global-wide-class-pair-dense|0.827|0.795|-3.94%|1.322|1.226|-7.27%|
|global-wide-name-pair-zero|0.902|0.889|-1.37%|1.351|1.429|+5.79%|
|global-wide-name-pair-targeted|1.455|1.439|-1.07%|2.162|2.040|-5.62%|
|global-wide-name-pair-dense|0.797|0.823|+3.20%|1.281|1.524|+18.97%|
|global-wide-caller-class-zero|0.903|0.862|-4.48%|1.391|1.460|+4.99%|
|global-wide-caller-class-targeted|1.181|1.171|-0.80%|1.709|1.747|+2.21%|
|global-wide-caller-class-dense|0.779|0.764|-1.93%|1.320|1.230|-6.80%|
|global-wide-callee-class-zero|0.900|0.847|-5.97%|1.338|1.451|+8.40%|
|global-wide-callee-class-targeted|1.137|1.135|-0.23%|1.759|1.810|+2.90%|
|global-wide-callee-class-dense|0.737|0.725|-1.62%|1.178|1.198|+1.70%|
|global-wide-provenance-zero|0.867|0.842|-2.85%|1.462|1.386|-5.21%|
|global-wide-provenance-targeted|1.400|1.352|-3.40%|2.034|1.979|-2.68%|
|global-wide-provenance-dense|0.966|0.961|-0.46%|1.603|1.512|-5.70%|
|global-wide-aliased-zero|0.924|0.878|-5.02%|1.340|1.302|-2.84%|
|global-wide-aliased-targeted|1.546|1.503|-2.79%|2.178|2.119|-2.71%|
|global-wide-aliased-dense|0.930|0.927|-0.23%|1.507|1.481|-1.67%|
|global-wide-parameterized-zero|0.889|0.873|-1.77%|1.391|1.363|-2.01%|
|global-wide-parameterized-targeted|1.247|1.237|-0.76%|1.653|1.608|-2.75%|
|global-wide-parameterized-dense|0.907|0.926|+2.05%|1.537|1.489|-3.10%|
|global-wide-wrapped-case-insensitive-zero|0.906|0.872|-3.77%|1.354|1.427|+5.41%|
|global-wide-wrapped-case-insensitive-targeted|1.256|1.228|-2.20%|1.798|1.843|+2.50%|
|global-wide-wrapped-case-insensitive-dense|0.922|0.919|-0.37%|1.641|1.661|+1.25%|
|global-wide-wrapped-case-insensitive-distinct-zero|0.984|0.931|-5.37%|3.057|2.890|-5.49%|
|global-wide-wrapped-case-insensitive-distinct-targeted|1.596|1.588|-0.50%|2.399|2.307|-3.86%|
|global-wide-wrapped-case-insensitive-distinct-dense|6.893|6.925|+0.47%|8.175|8.306|+1.60%|
|global-wide-distribution-broad-all-64|1.046|1.022|-2.36%|1.577|1.542|-2.25%|
|global-wide-distribution-localized-early|0.989|0.952|-3.73%|1.424|1.390|-2.38%|
|global-wide-distribution-localized-late|1.859|1.867|+0.45%|2.409|2.414|+0.24%|
|global-wide-distribution-localized-middle|1.736|1.747|+0.61%|2.399|2.275|-5.20%|

Concurrency 4

| Case | Old p50 ms |341 p50 ms | Δp50 | Old p95 ms |341 p95 ms | Δp95 |
|---|---:|---:|---:|---:|---:|---:|
|global-wide-four-properties-zero|2.133|2.194|+2.87%|7.682|6.591|-14.20%|
|global-wide-four-properties-targeted|2.302|2.255|-2.03%|7.278|6.349|-12.76%|
|global-wide-four-properties-dense|1.456|1.388|-4.69%|2.058|2.205|+7.14%|
|global-wide-class-pair-zero|1.770|1.698|-4.04%|3.708|5.088|+37.21%|
|global-wide-class-pair-targeted|2.218|2.263|+2.01%|7.816|6.648|-14.95%|
|global-wide-class-pair-dense|1.266|1.297|+2.40%|1.809|2.154|+19.06%|
|global-wide-name-pair-zero|1.830|1.776|-2.96%|4.852|4.506|-7.14%|
|global-wide-name-pair-targeted|2.412|2.510|+4.05%|5.737|5.627|-1.92%|
|global-wide-name-pair-dense|1.283|1.220|-4.84%|2.087|2.045|-1.99%|
|global-wide-caller-class-zero|1.658|1.839|+10.93%|4.763|6.782|+42.39%|
|global-wide-caller-class-targeted|1.962|2.027|+3.31%|6.137|7.179|+16.99%|
|global-wide-caller-class-dense|1.182|1.169|-1.05%|2.087|2.045|-2.01%|
|global-wide-callee-class-zero|1.553|1.745|+12.33%|3.752|4.859|+29.50%|
|global-wide-callee-class-targeted|2.113|1.986|-5.99%|5.669|4.963|-12.45%|
|global-wide-callee-class-dense|1.137|1.110|-2.41%|2.133|2.012|-5.68%|
|global-wide-provenance-zero|1.771|1.809|+2.14%|4.511|4.634|+2.74%|
|global-wide-provenance-targeted|2.101|2.543|+21.02%|5.908|5.425|-8.17%|
|global-wide-provenance-dense|1.456|1.569|+7.70%|3.146|3.181|+1.11%|
|global-wide-aliased-zero|1.641|1.687|+2.80%|6.362|5.301|-16.68%|
|global-wide-aliased-targeted|2.411|2.577|+6.90%|8.291|8.378|+1.05%|
|global-wide-aliased-dense|1.435|1.444|+0.64%|2.321|2.446|+5.37%|
|global-wide-parameterized-zero|1.604|1.781|+11.02%|5.503|6.760|+22.85%|
|global-wide-parameterized-targeted|2.184|2.077|-4.89%|7.516|5.556|-26.08%|
|global-wide-parameterized-dense|1.405|1.412|+0.50%|2.207|2.427|+10.00%|
|global-wide-wrapped-case-insensitive-zero|1.909|1.769|-7.30%|5.251|4.987|-5.05%|
|global-wide-wrapped-case-insensitive-targeted|2.070|2.191|+5.82%|7.290|9.088|+24.67%|
|global-wide-wrapped-case-insensitive-dense|1.396|1.397|+0.09%|4.761|2.371|-50.19%|
|global-wide-wrapped-case-insensitive-distinct-zero|1.743|1.857|+6.52%|6.433|6.039|-6.12%|
|global-wide-wrapped-case-insensitive-distinct-targeted|3.314|3.466|+4.60%|7.725|8.525|+10.36%|
|global-wide-wrapped-case-insensitive-distinct-dense|7.619|7.662|+0.56%|9.498|9.988|+5.16%|
|global-wide-distribution-broad-all-64|1.502|1.434|-4.52%|2.660|2.773|+4.27%|
|global-wide-distribution-localized-early|1.540|1.526|-0.93%|2.533|2.571|+1.50%|
|global-wide-distribution-localized-late|3.612|3.592|-0.55%|9.721|9.727|+0.06%|
|global-wide-distribution-localized-middle|4.095|4.360|+6.47%|9.663|10.079|+4.31%|


Concurrency1 has16/34 adverse mean p95 cases; eight are adverse in both pairs. Dense name-pair p95 worsens12.201%/25.837% (mean18.968%). Concurrency4 has21/34 adverse mean p50 and19/34 adverse mean p95 cases, with11/7 adverse in both pairs respectively. Targeted provenance p50 worsens16.10%/26.32% (mean21.02%); dense class-pair p95 worsens20.57%/17.57% (mean19.06%). The largest mean p95 increase, zero-hit caller-class42.387%, has highly unequal pair changes1.695%/90.642%; zero-hit class-pair is−8.718%/+145.667%. Preserve both recurring and variable adverse results; neither proves a code-level cause. Dense wrapped DISTINCT p95 is+1.601%/+5.161% at concurrency1/4, with mixed paired directions.

**Decision:** retain the cumulative improvements and keep112 active for targeted evaluation. Full HTTP correctness passes and mixed-request/resource means improve, but **per-query p50/p95 recovery is not established**, so the cumulative solution does not pass final acceptance. Do not discard positive increments because the overall goal remains unmet, and do not use the favorable mixed distribution to hide adverse cases. Next investigation prioritizes the recurring query tails and variability before another construction optimization. Separate construction/loading acceptance and the hard correctness/stability/8GiB constraints remain open as previously recorded.

**Reproduction and evidence:** `/tmp/sootup-static-review/server-request-old-vs341/plan.sealed.json` fixes literal server/client argv, runtime/source/fixture/JDK hashes, all34 queries and sampling rules. It was invoked by `python3 run.py --plan /tmp/sootup-static-review/server-request-old-vs341/plan.sealed.json --execute-root-released`. After the pre-JVM failure, `python3 /tmp/sootup-static-review/server-request-old-vs341/resume-preparation/run_resume.py --execute-root-released-resume` used the separately sealed resume plan. Original and continuation outputs remain separate; `combined/` references each original sample exactly once. No CI dispatch, retry-until-good, benchmark replacement or additional server was used.

- `plan.sealed.json` SHA-256 `bd52443faca5b8ed2f056f36417dec351be1886fbe1f75adbe36eb59148e6bb7`.
- `execution/results.json` SHA-256 `f6513e968f6b1695679c536b638a361cf590d92c272300d8e6be6e5bb74c3836`.
- `resume-preparation/plan.resume.json` SHA-256 `c9d3c0a464c4d4a5475ff5e06cb28b3ecbafdc1443bfa811ba96cd1069973bda`.
- `resume-preparation/execution/results.json` SHA-256 `0e30ef66c5db2aa58f35d50822207c11e00a126a14ec736d1bc3ce71e89b2555`.
- `resume-preparation/execution/combined-eight-summary.json` SHA-256 `1778f5894deeb4d1c2329168ead2b27abee90a7f24558935e8a146b72533f4bf`.
- `independent-audit/audit.json` SHA-256 `22aaf84ef4caa77f912fe6fcc088e60c8fb0d8ac11341c63e9b0a564aa265638`.
- `independent-audit/report.md` SHA-256 `fb315bd0f5af4e4a9a107b5c3df49377f44f2f444020539fe5ce0ed89cf04a6e`.

The independent audit re-read all8976 raw bodies and request records, recomputed each empirical percentile and every resource/pair statistic, and checked protocol origins, heap argv, receipts and retained fixture inventories. It did not launch JVMs or repeat graph reads.

### 2026-10-07 — Cumulative 341: full-feature construction against pre-upgrade

**Question:** measure the complete retained solution, including110/111, against actual pre-upgrade construction after the HTTP request baseline. A is frozen `6f498705009689551c92c6d1ca92f67252ef77c4`, `TEST_webgraph`54; B is frozen `34122563c3bd3cba3b529bdc9e5f20d454aaf779`, `TEST_webgraph`57. Root c7 has the same production sources as341. Both use JDK17.0.20.1 on M3 Max16-core/macOS14.3, the same real Kotlin compiler2.0.21 and Tika2.9.2 JARs, default full features and explicit `-Xmx8g`. APK remains deferred.

**Protocol:** fixed Kotlin ABBA followed by Tika ABBA, eight fresh construction processes, then24 strict verification processes. The unchanged compiled `CliConstruction` helper builds the graph, completely enumerates the CLI node count, saves a usable graph with prepared indexes and two compression threads, and explicitly closes the source. Continuous end-to-end wall includes all four phases; whole-command CPU is user+system and peak RSS includes JVM startup. No forced GC, profiling, feature reduction, retries, replacement samples or extra JVM options. Arithmetic means were declared primary, with all raw phases, median/min/max/range and both adjacent AB/BA changes retained. There are only two samples per arm/corpus; this is bounded evidence, not a population or stability guarantee.

**Raw measurements:** wall/CPU seconds; RSS is decimal GB, separate from the8GiB maximum heap.

|Run|Build s|Count s|Save s|E2E s|Outer CPU s|Peak RSS GB|
|---|---:|---:|---:|---:|---:|---:|
|kotlin-0-A|105.431552|0.349308|6.395754|112.176618|183.480|7.734903|
|kotlin-1-B|29.877531|0.341010|11.718515|41.937060|106.840|8.096023|
|kotlin-2-B|29.633110|0.413412|6.429801|36.476328|103.540|9.653109|
|kotlin-3-A|106.014774|0.319169|6.751546|113.085493|204.940|9.381167|
|tika-0-A|103.920091|0.334426|10.333406|114.587926|174.780|6.453215|
|tika-1-B|22.114879|0.352135|5.478223|27.945241|82.950|8.572797|
|tika-2-B|22.136642|0.356233|5.345504|27.838384|85.370|8.631763|
|tika-3-A|105.346426|0.333338|5.420125|111.099893|171.750|7.294321|

| Corpus / metric | Pre-upgrade mean | Cumulative341 mean | Change | AB / BA changes |
|---|---:|---:|---:|---:|
| Kotlin end-to-end s |112.631055|39.206694|−65.190%|−62.615% / −67.744%|
| Kotlin whole CPU s |194.210|105.190|−45.837%|−41.770% / −49.478%|
| Kotlin peak RSS GB |8.558035|8.874566|+3.699%|+4.669% / +2.899%|
| Tika end-to-end s |112.843909|27.891812|−75.283%|−75.612% / −74.943%|
| Tika whole CPU s |173.265|84.160|−51.427%|−52.540% / −50.294%|
| Tika peak RSS GB |6.873768|8.602280|+25.146%|+32.845% / +18.335%|

Kotlin build wall improves71.855%, but prepared-save wall increases38.038% (6.573650→9.074158s); the two save pairs are+83.223%/−4.765%, with both candidate samples11.718515/6.429801s preserved. Tika build improves78.854% and save31.293%. Complete-count wall rises12.857% for Kotlin and6.081% for Tika; these small phases remain included in end-to-end. No causal GC/JIT/allocation inference follows from these counters.

**Correctness:** All eight builds and24 independent verification JVMs completed successfully. Root independently re-read every output/reference report and log, checked the declared8g commands and exact run order, and confirmed the recorded graph inventories are identical before/after verification. Old/current graph counts differ as already recorded for the SootUp upgrade: Kotlin4,657,648/2,173,010 versus4,744,132/2,251,811 nodes/CallSites; Tika4,673,289/1,758,353 versus4,620,490/1,705,428. Verification compares every output to its own runtime's established full-feature22-field shape,13-field metadata and five-query reference. This does not establish old/current graph isomorphism. Old graphs have18files and no ordinal sidecar; current graphs additionally require exact pinned main02 ordinal-sidecar bytes and the36-byte metadata binding. Quiet construction logs cannot prove absence of internally caught adapter OOM or GC; no such instrumentation was enabled.

**Decision:** retain the substantial cumulative construction latency/CPU gains. Kotlin's observed RSS mean and both pairs are within the5% allowance, but n2 does not prove a stable bound. Tika RSS exceeds5% in the mean and both pairs, so construction does **not** pass final resource acceptance. The earlier110 parent-relative RSS reduction remains a useful retained increment; it does not establish old-relative compliance. Query p50/p95 remains the first priority, then end-to-end time; neither these construction gains nor lower CPU can offset the Tika RSS overrun or unresolved loading/query results. Keep the8GiB heap ceiling and continue reducing memory without discarding the speed gains.

**Reproduction and evidence:** `/tmp/sootup-recovery-sources/cumulative341-old-construction-pilot/execute.py --execute-root-released` (run with Python3). `commands.json` SHA256 `6eeeb28063bd6ec20097102495d657276a550a503905d6658e8f6d502b266430`; runner `6278bce58c97a623ce7057592bfe16b7623e4064b5e441c01effcf5ca3df23c3`. All raw properties, time-l logs, inventories, reports and phase/resource statistics are preserved in that directory. Independent root recomputation checked all eight raw time/property files and every summary mean/pair against `execution/summary.json`, SHA256 `b1bb8d14e78010b181e0abb4c9ccef435b6b9166a11ef0d5ec5037d92e1e3818`; proof `/tmp/sootup-static-review/cumulative341-construction-review/phase-audit.json`.

Final executor result SHA256 `6bff72a5f85f2ad7c3c69f0337866f45cc83c8f23236648a343f73d1de8735f6`; independent report audit `/tmp/sootup-static-review/cumulative341-construction-review/final-audit.json`, SHA256 `f96c5b7007ac4de6bf3524298fd486eaa26ec39c95e26204067215b63b10c7f9`. Both graph-inventory files have SHA256 `dc1311bca9ecbe492002352fcb422d34d5e463ee39f8d8d9f61db4bfac2c4008`. Root rechecked report bytes and recorded inventories; graph bytes were rehashed by the sole executor, avoiding duplicate scans during the next JVM workload.

### 2026-10-07 — Attempt 113: keep overlapping prepared queries off the graph-worker queue

**Hypothesis:** the preceding real64 HTTP measurements show that some zero-hit request tails coincide with targeted requests in the same concurrency4 wave. The same association also occurs in pre-upgrade samples, so it is not proof of a regression introduced by cumulative341. In the current prepared global path, each query holds a fixed graph-worker cohort on the shared executor while its source-ordered queues advance. Let overlapping eligible requests execute their graph tasks on their own request thread to test whether this queue interaction contributes to tails, while preserving standalone graph parallelism.

**Base and scope:** isolated `/tmp/graphite-attempt113` is based on `c7f68173526dcd4c248feb4dd8fe306a2c274464`, whose production is cumulative341, including110/111 and excluding isolated112. Only `QueryPipeline.kt` changes production. A single existing eligibility traversal distinguishes strict prepared capability from legacy batching. The new dispatch applies only to the default-configuration, global non-DISTINCT path with known node counts, no serial-preference-only shortcut, and prepared capability for relevant nonempty types. Unknown counts, explicit configuration, serial-preference-only, unprepared/no-sidecar fallbacks and DISTINCT keep their existing dispatch. Serial preference still short-circuits the sidecar readiness call. Leading-source/LIMIT behavior is retained.

The first active eligible query retains the fixed balanced graph workers. Other eligible queries execute the same scanners and source-ordered merge inline, preserving the captured work consumer and segment-worker budget. Cancellation is checked around tasks and after the final merge; interruption remains set. Runtime exceptions and errors preserve identity, checked failures preserve their wrapped cause, and `finally` restores the worker ThreadLocal and decrements the active-query counter. No feature, work limit, timeout, heap or cancellation check is removed.

**Scope limitations:** prepared capability means a persisted sidecar is available, not that the mapped index is already initialized. A cold first lookup with a sidecar can take the inline path when it overlaps another eligible request; first-use observations must remain visible. The active counter includes inline queries. Under continuous arrivals, the cohort may never reach zero after its original fixed-worker query finishes, so later eligible requests may all remain inline. This is intentional for this candidate, but a wave-based pilot with gaps cannot establish sustained throughput or resource behavior. If retained, the next validation must include a fixed continuous closed-loop concurrency4 run on the same real graphs/workload, with complete response/provenance validation and CPU/RSS boundaries retained. A fixed-owner-only CAS policy would be a different hypothesis and is not implemented here.

**Correctness:** six focused tests verify complete ordered projected rows, graph provenance and LIMIT; execution of every second-query storage call on its requesting thread while the first query remains blocked; exactly one readiness probe per graph; shared-budget failure and exact consumed work; cancellation of an empty lookup before another graph; preserved interruption; runtime/error identity and checked failure cause; worker/counter reuse after success and failures; and serial-preference readiness short-circuit plus mixed unprepared fallback. Caller-thread assertions prevent low-core CI machines from passing merely because the first query did not occupy the full shared pool. Existing standalone balanced-worker, explicit-policy and cancellation-join tests remain unchanged.

The first build stopped at the external task-set guard because Kover also scheduled the existing `:cypher:filteredRelationshipMemoryTest`. Gradle's JVM started, but compilation/tests did not execute. The failure is preserved in `attempt113-build1/build.log`; it is not a candidate test failure. Build2 corrected only that guard and retained the special test's256MiB heap. Candidate source/test hashes stayed unchanged. Build2 passed **1649 fresh tests**:1338 Cypher,7 filtered-relationship memory,261 webgraph and43 query; zero failures/errors/skips. All three module Detekt tasks and both JMH isolation checks passed. Actual Cypher Kover coverage is **98.0392%**,6550 covered/131 missed lines, with the98% threshold unchanged. The successful build used JDK17.0.20.1, serialized two-worker Gradle,4GiB ordinary test heaps and the256MiB specialized task; temporary publishing edits were restored and source pins rechecked. No performance claim follows from these tests.

**Fixed real-data HTTP pilot:** A is frozen cumulative341; B is113. Both use matching `MAIN_query` runtime roles on the same M3 Max16-core/macOS14.3 host and JDK17.0.20.1, the same64 persisted real JAR-shard graphs and34-query full-response oracle. Eight fresh servers run concurrency1 ABBA, then concurrency4 ABBA. Every process uses `-Xmx8g`, mapped loading, max-concurrent-cypher4 and timeout60000ms, with unchanged default work limits. One first-use cycle, two warmup cycles and30 measured cycles give1122 requests per process,8976 total and30 measured observations per case. Source order rotates by cycle, with bounded request waves and a new connection per request. No sample was replaced or added. All8976 full typed, ordered responses and provenance match the oracle; readiness and before/after64-graph inventories pass. Independent audit re-read all raw response bodies and arithmetic; it relied on retained lifecycle hashes for the large graph/runtime payloads rather than rereading them.

Request latency spans HTTP send through full response-body read; validation/reporting occur between waves. Tables use arithmetic means of two process-level nearest-rank percentiles, not pooled population estimates. With30 observations per process, p95 is the second-largest sample and tail precision is limited. All raw samples, ranges, paired differences and descriptive request means remain in the complete artifacts.

CPU seconds; peakRSS decimalMB. Measured-window serverCPU spans30cycles including validation/logging gaps. Lifetime serverCPU/RSS includes startup/load/first-use/warm/measured/shutdown. ClientCPU is separate.

|Run|Window serverCPU s|Lifetime serverCPU s|Lifetime wall s|PeakRSS MB|Window clientCPU s|
|---|---:|---:|---:|---:|---:|
|c1-0-A|6.170|19.440|19.990|5807.144960|8.869437|
|c1-1-B|6.100|19.520|19.940|5096.046592|8.871275|
|c1-2-B|6.060|19.380|19.920|5258.805248|8.859575|
|c1-3-A|6.120|19.120|19.510|5500.715008|8.879677|
|c4-0-A|6.370|19.640|13.370|5060.280320|8.597493|
|c4-1-B|6.130|19.500|13.610|5012.242432|8.629540|
|c4-2-B|6.070|19.760|13.420|5398.331392|8.546475|
|c4-3-A|6.390|19.650|13.650|5772.279808|8.646283|

Concurrency1 mean resource differences: measuredWindowServerCpuSeconds: 6.145000→6.080000 (-1.058%), serverLifetimeCpuSeconds: 19.280000→19.450000 (+0.882%), serverLifetimeWallSeconds: 19.750000→19.930000 (+0.911%), serverLifetimePeakRssBytes: 5653929984.000000→5177425920.000000 (-8.428%), measuredWindowClientCpuSeconds: 8.874557→8.865425 (-0.103%).
Pair c1-0-A→c1-1-B: measuredWindowServerCpuSeconds -1.135%, serverLifetimeCpuSeconds +0.412%, serverLifetimeWallSeconds -0.250%, serverLifetimePeakRssBytes -12.245%, measuredWindowClientCpuSeconds +0.021%.
Pair c1-3-A→c1-2-B: measuredWindowServerCpuSeconds -0.980%, serverLifetimeCpuSeconds +1.360%, serverLifetimeWallSeconds +2.101%, serverLifetimePeakRssBytes -4.398%, measuredWindowClientCpuSeconds -0.226%.

Concurrency4 mean resource differences: measuredWindowServerCpuSeconds: 6.380000→6.100000 (-4.389%), serverLifetimeCpuSeconds: 19.645000→19.630000 (-0.076%), serverLifetimeWallSeconds: 13.510000→13.515000 (+0.037%), serverLifetimePeakRssBytes: 5416280064.000000→5205286912.000000 (-3.896%), measuredWindowClientCpuSeconds: 8.621888→8.588007 (-0.393%).
Pair c4-0-A→c4-1-B: measuredWindowServerCpuSeconds -3.768%, serverLifetimeCpuSeconds -0.713%, serverLifetimeWallSeconds +1.795%, serverLifetimePeakRssBytes -0.949%, measuredWindowClientCpuSeconds +0.373%.
Pair c4-3-A→c4-2-B: measuredWindowServerCpuSeconds -5.008%, serverLifetimeCpuSeconds +0.560%, serverLifetimeWallSeconds -1.685%, serverLifetimePeakRssBytes -6.478%, measuredWindowClientCpuSeconds -1.154%.

**All68 measured case comparisons:** A is parent341 and B is113. These tables retain the four raw run-level p50/p95 values, mean changes and both p95 pairs; no adverse control is omitted. Units are milliseconds.

Concurrency1

|Case|Parent0 p50/p95|113-1 p50/p95|113-2 p50/p95|Parent3 p50/p95|Mean p50Δ|Mean p95Δ|AB p95Δ|BA p95Δ|
|---|---:|---:|---:|---:|---:|---:|---:|---:|
|global-wide-four-properties-zero|0.956666/1.312042|0.955750/2.157167|1.017417/1.449625|0.965500/1.467500|+2.653%|+29.762%|+64.413%|-1.218%|
|global-wide-four-properties-targeted|1.331042/1.718500|1.327250/1.855958|1.336875/1.783375|1.366417/2.055250|-1.236%|-3.562%|+7.999%|-13.228%|
|global-wide-four-properties-dense|0.978041/1.315083|0.924416/1.303083|0.971958/1.396334|0.991625/1.335875|-3.721%|+1.828%|-0.912%|+4.526%|
|global-wide-class-pair-zero|0.919791/1.340667|0.937167/1.405417|0.928792/1.295500|0.930083/1.800000|+0.870%|-14.002%|+4.830%|-28.028%|
|global-wide-class-pair-targeted|1.351334/1.691291|1.288208/1.709042|1.367958/1.648584|1.355250/1.933000|-1.863%|-7.358%|+1.050%|-14.714%|
|global-wide-class-pair-dense|0.830834/1.261583|0.825333/1.201750|0.868875/1.216084|0.913417/1.286833|-2.869%|-5.124%|-4.743%|-5.498%|
|global-wide-name-pair-zero|0.909375/1.297459|0.864916/1.363916|0.897250/1.323209|0.963667/1.420208|-5.920%|-1.124%|+5.122%|-6.830%|
|global-wide-name-pair-targeted|1.497292/1.983792|1.434917/1.898125|1.484625/1.973208|1.530208/2.056208|-3.566%|-4.175%|-4.318%|-4.037%|
|global-wide-name-pair-dense|0.828250/1.238167|0.815958/1.290917|0.858500/1.218250|0.869333/1.293333|-1.362%|-0.882%|+4.260%|-5.805%|
|global-wide-caller-class-zero|0.936750/1.253167|0.874667/1.398916|0.919625/1.390042|0.975750/1.419916|-6.181%|+4.335%|+11.630%|-2.104%|
|global-wide-caller-class-targeted|1.223334/2.112458|1.148708/1.541625|1.192667/1.735833|1.210083/2.065958|-3.782%|-21.562%|-27.022%|-15.979%|
|global-wide-caller-class-dense|0.774833/1.266083|0.739791/1.276125|0.787709/1.242709|0.814125/1.282000|-3.868%|-1.148%|+0.793%|-3.065%|
|global-wide-callee-class-zero|0.904500/1.359417|0.866375/1.333000|0.931084/1.382250|0.939334/1.367042|-2.515%|-0.411%|-1.943%|+1.112%|
|global-wide-callee-class-targeted|1.126375/1.726875|1.149166/1.622625|1.131042/1.722625|1.185792/1.702084|-1.382%|-2.441%|-6.037%|+1.207%|
|global-wide-callee-class-dense|0.743750/1.168084|0.736291/1.142875|0.777375/1.204250|0.761833/1.156583|+0.537%|+0.966%|-2.158%|+4.121%|
|global-wide-provenance-zero|0.878917/1.333667|0.868042/1.296708|0.878958/1.419167|0.937500/1.633833|-3.822%|-8.479%|-2.771%|-13.139%|
|global-wide-provenance-targeted|1.409458/1.936042|1.407333/2.131125|1.447625/2.104708|1.435875/2.381792|+0.338%|-1.899%|+10.076%|-11.633%|
|global-wide-provenance-dense|0.981875/1.530541|0.985334/1.428125|1.032042/1.490292|1.067959/1.518667|-1.583%|-4.289%|-6.691%|-1.868%|
|global-wide-aliased-zero|0.902708/1.340334|0.886042/1.232375|0.983500/1.371209|0.942166/1.302042|+1.337%|-1.468%|-8.055%|+5.312%|
|global-wide-aliased-targeted|1.584959/1.954750|1.532041/1.872750|1.568292/2.248583|1.661459/2.095291|-4.500%|+1.760%|-4.195%|+7.316%|
|global-wide-aliased-dense|0.947250/1.353000|0.918209/1.326334|1.010750/1.436000|0.996000/1.453333|-0.735%|-1.568%|-1.971%|-1.193%|
|global-wide-parameterized-zero|0.933500/1.203708|0.903666/1.513791|0.924583/1.629750|0.976125/1.297792|-4.261%|+25.666%|+25.761%|+25.579%|
|global-wide-parameterized-targeted|1.274416/1.597333|1.260375/1.612666|1.284042/1.655625|1.294375/1.717625|-0.949%|-1.408%|+0.960%|-3.610%|
|global-wide-parameterized-dense|0.917417/1.515000|0.931791/1.424375|0.980125/1.527833|0.967542/1.590333|+1.430%|-4.931%|-5.982%|-3.930%|
|global-wide-wrapped-case-insensitive-zero|0.890250/1.407125|0.887458/1.694041|0.956792/1.397500|0.941125/1.508083|+0.703%|+6.049%|+20.390%|-7.333%|
|global-wide-wrapped-case-insensitive-targeted|1.229292/1.820750|1.220875/1.994083|1.289084/1.896625|1.288042/1.810583|-0.293%|+7.143%|+9.520%|+4.752%|
|global-wide-wrapped-case-insensitive-dense|0.934250/1.657041|0.920209/1.705708|0.999750/1.774709|0.983125/1.671583|+0.135%|+4.560%|+2.937%|+6.169%|
|global-wide-wrapped-case-insensitive-distinct-zero|0.923250/2.610833|0.964334/3.914291|0.980583/2.760875|1.017375/2.995917|+0.221%|+19.056%|+49.925%|-7.845%|
|global-wide-wrapped-case-insensitive-distinct-targeted|1.635584/2.250291|1.619292/2.162208|1.662208/2.372459|1.654833/2.289833|-0.271%|-0.120%|-3.914%|+3.608%|
|global-wide-wrapped-case-insensitive-distinct-dense|7.093667/8.283167|6.976667/8.258958|7.061833/8.066709|7.195208/8.412000|-1.752%|-2.213%|-0.292%|-4.105%|
|global-wide-distribution-broad-all-64|1.124333/1.527958|1.059500/1.879125|1.112917/1.622209|1.161583/1.523125|-4.965%|+14.757%|+22.983%|+6.505%|
|global-wide-distribution-localized-early|1.032708/1.372792|0.997125/1.445417|1.008625/1.563291|1.051875/1.380292|-3.782%|+9.285%|+5.290%|+13.258%|
|global-wide-distribution-localized-late|1.971167/2.543333|1.869709/2.377792|1.923333/6.478166|2.005666/2.314750|-4.622%|+82.293%|-6.509%|+179.865%|
|global-wide-distribution-localized-middle|1.762084/2.195125|1.734500/2.189333|1.755584/2.415625|1.853833/2.318791|-3.480%|+2.017%|-0.264%|+4.176%|

Concurrency4

|Case|Parent0 p50/p95|113-1 p50/p95|113-2 p50/p95|Parent3 p50/p95|Mean p50Δ|Mean p95Δ|AB p95Δ|BA p95Δ|
|---|---:|---:|---:|---:|---:|---:|---:|---:|
|global-wide-four-properties-zero|2.057209/4.431042|1.550167/2.203708|1.536375/2.394334|1.997417/5.711333|-23.876%|-54.665%|-50.267%|-58.077%|
|global-wide-four-properties-targeted|2.281750/6.248334|2.924625/6.777208|3.035542/6.265416|2.352708/5.575000|+28.605%|+10.313%|+8.464%|+12.384%|
|global-wide-four-properties-dense|1.388750/2.087708|1.466917/2.099500|1.511000/1.989750|1.516875/2.144667|+2.488%|-3.382%|+0.565%|-7.223%|
|global-wide-class-pair-zero|1.659334/4.231416|1.354458/2.335667|1.410083/2.058375|1.715333/4.736166|-18.080%|-51.001%|-44.802%|-56.539%|
|global-wide-class-pair-targeted|2.216583/7.491375|3.031042/7.815167|3.182917/9.458583|2.273500/7.262709|+38.393%|+17.078%|+4.322%|+30.235%|
|global-wide-class-pair-dense|1.275042/2.031167|1.353000/2.279667|1.278959/2.228958|1.318000/1.995917|+1.501%|+11.958%|+12.234%|+11.676%|
|global-wide-name-pair-zero|1.773500/4.525541|1.430708/2.453792|1.390292/2.188125|1.756750/4.447875|-20.091%|-48.270%|-45.779%|-50.805%|
|global-wide-name-pair-targeted|2.506250/5.897084|4.779042/7.092000|4.167250/6.897583|2.448041/5.841500|+80.577%|+19.176%|+20.263%|+18.079%|
|global-wide-name-pair-dense|1.241541/2.448958|1.251708/1.982208|1.194750/2.311917|1.250625/2.040916|-1.834%|-4.360%|-19.059%|+13.278%|
|global-wide-caller-class-zero|1.783250/4.085458|1.387209/2.353041|1.378333/2.698959|1.811209/4.720834|-23.061%|-42.632%|-42.404%|-42.829%|
|global-wide-caller-class-targeted|1.928125/6.083458|2.701458/8.040125|2.554875/5.813334|1.983167/8.004416|+34.389%|-1.664%|+32.164%|-27.373%|
|global-wide-caller-class-dense|1.213667/1.838084|1.106708/1.968292|1.127917/2.180708|1.193375/1.939584|-7.163%|+9.830%|+7.084%|+12.432%|
|global-wide-callee-class-zero|1.738583/3.738041|1.328291/2.434833|1.248667/2.734500|1.705750/4.636125|-25.183%|-38.270%|-34.863%|-41.018%|
|global-wide-callee-class-targeted|1.925250/4.088708|2.441917/6.140125|2.281291/4.793458|1.871458/4.993333|+24.403%|+20.387%|+50.173%|-4.003%|
|global-wide-callee-class-dense|1.158750/1.804625|1.163792/2.138167|1.093709/2.166750|1.200541/1.548041|-4.314%|+28.403%|+18.483%|+39.967%|
|global-wide-provenance-zero|1.604166/6.873458|1.482125/2.904958|1.388667/2.942917|1.882292/3.382375|-17.659%|-42.980%|-57.737%|-12.993%|
|global-wide-provenance-targeted|2.566042/6.877083|3.719208/6.051875|3.630917/6.023500|2.507291/5.167750|+44.878%|+0.254%|-11.999%|+16.559%|
|global-wide-provenance-dense|1.539500/2.507209|1.534291/3.114250|1.503375/3.020375|1.497459/3.443500|+0.023%|+3.091%|+24.212%|-12.288%|
|global-wide-aliased-zero|1.606875/5.541958|1.492708/1.896708|1.366541/2.047458|1.820083/4.891500|-16.566%|-62.197%|-65.775%|-58.143%|
|global-wide-aliased-targeted|2.509167/6.859209|3.912583/6.703250|4.091542/7.320166|2.404750/10.075958|+62.887%|-17.194%|-2.274%|-27.350%|
|global-wide-aliased-dense|1.415791/2.254792|1.483375/2.066208|1.386500/2.106041|1.408625/2.218917|+1.610%|-6.738%|-8.364%|-5.087%|
|global-wide-parameterized-zero|1.734542/4.906416|1.507791/2.058500|1.444792/2.091917|1.734458/7.188292|-14.887%|-65.684%|-58.045%|-70.898%|
|global-wide-parameterized-targeted|1.986375/5.470542|2.880750/7.186167|3.068417/6.339833|2.051042/7.864791|+47.351%|+1.430%|+31.361%|-19.390%|
|global-wide-parameterized-dense|1.407625/2.628792|1.414791/2.280250|1.402542/2.338291|1.480375/2.443542|-2.447%|-8.946%|-13.259%|-4.307%|
|global-wide-wrapped-case-insensitive-zero|1.736500/4.976625|1.415334/2.036334|1.309000/1.980041|2.050625/7.451291|-28.063%|-67.683%|-59.082%|-73.427%|
|global-wide-wrapped-case-insensitive-targeted|1.922000/9.318333|2.485917/8.410833|2.356583/5.989667|1.953042/6.908917|+24.966%|-11.257%|-9.739%|-13.305%|
|global-wide-wrapped-case-insensitive-dense|1.317500/2.420417|1.451792/2.189000|1.355542/2.156000|1.455583/2.851541|+1.235%|-17.583%|-9.561%|-24.392%|
|global-wide-wrapped-case-insensitive-distinct-zero|1.790709/6.395875|1.681500/4.925083|1.686000/3.050584|1.875583/7.086500|-8.150%|-40.844%|-22.996%|-56.952%|
|global-wide-wrapped-case-insensitive-distinct-targeted|3.289750/10.408875|3.265042/8.926083|3.094959/8.673084|3.376458/10.193833|-4.593%|-14.578%|-14.245%|-14.918%|
|global-wide-wrapped-case-insensitive-distinct-dense|7.555375/9.503916|7.518416/9.276834|7.604417/9.084542|7.858500/9.947833|-1.888%|-5.606%|-2.389%|-8.678%|
|global-wide-distribution-broad-all-64|1.455458/2.430083|1.457834/2.561583|1.512292/2.493125|1.466958/2.596583|+1.633%|+0.558%|+5.411%|-3.984%|
|global-wide-distribution-localized-early|1.598250/2.310125|1.542917/2.394500|1.532000/2.186375|1.595417/2.229667|-3.718%|+0.905%|+3.652%|-1.942%|
|global-wide-distribution-localized-late|3.813250/11.082833|3.936459/8.126542|4.218958/9.938292|3.559541/10.079041|+10.615%|-14.635%|-26.675%|-1.396%|
|global-wide-distribution-localized-middle|3.771750/9.024208|4.047750/8.226500|4.450417/10.056625|4.303500/9.823542|+5.237%|-2.996%|-8.840%|+2.373%|

**First-use and warmup limits:** these were predeclared supplements. Each case has only two first-use observations per arm/concurrency, so the following are means of individual latencies, not first-use p95 estimates. Sidecar files exist at startup and earlier cases may initialize overlapping state; “first-use” is not a guarantee of an uncached index for every case. Both warm cycles remain separate and are not pooled into measured results. The largest first-use adverse cases include:

|Concurrency|Case|A0 ms|B1 ms|B2 ms|A3 ms|Mean change|
|---|---|---:|---:|---:|---:|---:|
|1|global-wide-class-pair-targeted|7.784000|7.744958|7.521875|5.953500|+11.133%|
|1|global-wide-callee-class-dense|2.305250|2.428834|2.587375|2.312625|+8.626%|
|1|global-wide-name-pair-targeted|5.089208|5.398625|5.396417|5.118250|+5.756%|
|1|global-wide-wrapped-case-insensitive-dense|3.311334|3.665041|3.152083|3.149750|+5.511%|
|4|global-wide-parameterized-targeted|5.395166|16.654417|15.865833|5.879250|+188.443%|
|4|global-wide-provenance-targeted|5.826500|5.896958|8.918333|6.536208|+19.839%|
|4|global-wide-caller-class-zero|4.488916|4.374333|7.031666|5.247791|+17.144%|
|4|global-wide-caller-class-targeted|7.832084|8.026042|7.351666|5.339292|+16.751%|

Concurrency4 parameterized-targeted first-use therefore rises5.637208→16.260125ms (**+188.443%**), pairs+208.691%/+169.862%. That is two observed requests per arm, not a stable tail estimate. The complete first-use and two warm-cycle tables, all measured request means and per-process maxima remain in [the independent full report](/tmp/sootup-static-review/attempt113/server-request-packet/independent-audit/report.md), [summary.json](/tmp/sootup-static-review/attempt113/server-request-packet/execution/summary.json), [comparison-complete.json](/tmp/sootup-static-review/attempt113/server-request-packet/execution/comparison-complete.json) and [all-case-percentiles.md](/tmp/sootup-static-review/attempt113/server-request-packet/execution/all-case-percentiles.md). No per-case CPU or GC/JIT cause is inferred.

**Interpretation and decision: keep113 as an ACTIVE isolated positive candidate; do not integrate it yet.** Concurrency4 wrapped case-insensitive zero-hit p95 improves6.213958→2.008188ms (**−67.683%**), with both pairs−59.082%/−73.427%; several other zero-hit cases improve in both pairs. Preserve that positive evidence. However, targeted name-pair p50 worsens2.477145→4.473146ms (**+80.577%**), pairs+90.685%/+70.228%, and p95 worsens19.176% in both pairs. Parameterized-targeted p50 also worsens47.351%, both pairs adverse. Concurrency1 localized-late p95 increases82.293%, with unequal pairs−6.509%/+179.865%. These costs are not compensated away by lower session CPU/RSS or favorable cases. DISTINCT remains a control path; its observed changes cannot automatically be attributed to this dispatch code. Sustained-cohort behavior remains unvalidated. This parent-relative pilot neither certifies old-relative CPU/RSS caps nor establishes whole-server throughput or overall recovery.112 remains active and isolated separately.

**Reproduction:** `python3 /tmp/sootup-static-review/attempt113/server-request-packet/run.py --plan /tmp/sootup-static-review/attempt113/server-request-packet/plan.sealed.json --execute-root-released` executes the sealed eight-server sequence. Literal per-server/client argv are in that plan and raw command receipts. The server form is `/usr/bin/time -l "$JAVA17" -Xmx8g -cp "$FROZEN_MAIN_QUERY_CP" io.johnsonlee.graphite.cli.MainKt serve --data "$RUN/data" --port "$PORT" --load-mode MAPPED --max-concurrent-cypher 4 --cypher-max-timeout-ms 60000` followed by the fixed64 `--graph id:path` arguments. No CI dispatch or performance profiler was added.

**Evidence and pins:** the successful build command, environment, fresh XML hashes, coverage and frozen runtime are recorded in `/tmp/sootup-recovery-sources/attempt113-build/attempt113-build2/build-proof.json`. Its command runs `:cypher:test :cypher:detekt :webgraph:test :webgraph:detekt :query:test :query:detekt :cypher:koverLog :cypher:koverXmlReport`, JMH packaging/isolation and runtime exports through the sealed external init script. Failed build1 remains alongside it.

- Production `QueryPipeline.kt`: `62660bd8d1bd1ad6b8200f93bd91bd79c6241710f3b0b2f16f5ace14191409f7`.
- `PreparedQueryOverlapTest.kt`: `2ce0bae751b9a66114f17def60eb848dfaa59cc526c85638bbf6686d15980751`.
- Original source review proof `/tmp/sootup-static-review/attempt113/source-proof.json`: `8ea1fa76cfe360bcd1cf608144e39b3050db82ac2187106d70eb058eb73d8518`.
- Build2 source seal: `1f7aa374a390503eeed725f2dc6dd2954eab1b325b908d51c9ea20c823e85998`.
- Build2 proof: `b8cbcb74f8df7ac29c048eb526bfe870262da24554ca2d1e3634e5d1989a7a41`.
- Frozen113 runtime `/tmp/sootup-recovery-sources/attempt113-build/attempt113-build2/snapshot/runtime.json`: `6ca4f283cb4ea1465de3eb3eb7ce7ffc701da1c371a841a75a0f3776216af7b8`.
- Coverage XML: `0cc73a8aaa06224e7fa1b6e98376b93ea4745841d5913c082fca94b1c7fae10e`.


Additional exact measurement/audit SHA-256 pins:

- `plan.sealed.json`: `15cf66ce2f307bb37b34d09a326d1cae924f6c1403c9d02c12236a82c828cb01`.
- `execution/results.json`: `3afa8ce6f1b66256f3534671af3fef9913a24c6ed45174b77e4af48e3377dd45`.
- `execution/summary.json`: `ccc6a60709b6979a20eefc37ce46c589aa511edd896db190f17a2cbf5e572568`.
- `execution/comparison-complete.json`: `2151a3de1957e266268a38423b9265eb5f4eb75fc2853261f32d1c444174546d`.
- `execution/all-case-percentiles.md`: `2a1d6ceb63777957344b19d65e6c2a28bda21209337b3b936d187792b6abc18f`.
- `independent-audit/audit.json`: `39e5a48f1b9e1ad8814c5a9e3acbe855aef11e439d92806885a7a9567c8e8b48`.
- `independent-audit/report.md`: `d4f551b48558bb62d951ca44a1c5a7dc29ce8a5f11d5f8981b0b1a00ac532ee0`.
- Parent341 runtime: `5a64dcf9415e5745e6877e9c028b4ba2a0e972f674a9f12cb2713267aedd5fcc`; requests34: `484660b6edd3b0da6a1bac9fed14c4d8317da369c19683554ce07fb536175410`; HTTP oracle: `fb0b50c1bd61dd47dbb7a02189c4e8b3b260374a27e7ae677f706a5e8875e83e`.

The request p50/p95 priority, end-to-end priority next, independent CPU/RSS≤5% versus pre-upgrade, maximum8GiB Java heap, correctness and stability remain unchanged. This local candidate commit retains code and evidence for further evaluation; it is not a root integration or a final acceptance claim.

### 2026-10-07 — Attempt 114: preserve work tracking in scoped ordered CallSite projection

**Hypothesis:** let single-graph HTTP ORDER BY top-k use the retained104 direct-string projection without weakening its work budget or cancellation. The scoped route creates an unqualified executor but always supplies a tracked context; the previous `!workTrackingEnabled` guard therefore excluded the HTTP request even though the equivalent untracked CLI query used104. This is a new isolated candidate from `c7f68173526dcd4c248feb4dd8fe306a2c274464` (production cumulative341), not a stack on113. No global prepared-query scheduling, DISTINCT, feature, heap, GC or cache changes are included.

**Change:** admit the same supported tracked projection shapes and consume exactly one work unit at the beginning of each projected-row callback, before row creation/ranking. Reuse the active request tracker so earlier executions in the same context remain charged. Do not pass the tracker into the raw scanner: its batching could defer exhaustion and alter exception ordering. Unsupported shapes, qualified/global sources and other projection types keep the ordinary path; zero LIMIT, sorting/ties and final cancellation checks remain intact.

A projection-only preflight is also necessary for supported graphs created through the public MmapGraphBuilder: reusing one NodeID across types can leave an older typed-index entry pointing to the current different node. The generic ordinary sequence does not guarantee a runtime CallSite cast, so silently skipping that entry would change fallback properties. Before any projection callback or charge, validate every typed ID's current offset/header/type. A missing/invalid ID or non-CallSite header returns unsupported with zero callbacks and the original path handles the entire query. Valid duplicate CallSite IDs still project and charge repeatedly. All other raw-scan consumers remain unchanged. The prepass adds one full ID/header pass; it checks thread interruption every1024 entries. Signal-only cancellation may wait for the prepass, because the callback cannot run before a possible unsupported return; request cancellation is checked on entry and after successful preflight. HTTP guards interrupt their worker, but this does not erase the signal-only API latency limitation. As with104, unprojected payloads remain undecoded; equivalent exceptions for arbitrary corruption of those payloads are not claimed.

**Correctness and build:** build1 compiled production but failed compilation of the new persistence test because `builder.build()` has static type Graph, which is not a Closeable `use` receiver. The only correction was an explicit MmapGraph cast before `use`, preserving close and every assertion. Original source/proof/patch and build1 logs remain archived; this was not a successful test run.

Build2 independently passed1649 fresh checks:1336 Cypher tests,7 filtered-relationship memory tests,263 webgraph tests and43 query tests, with zero failures/errors/skips. Detekt, required packaging/isolation and runtime exports passed. Cypher Kover reports6515 covered/130 missed lines, **98.0436418359669%**, above the unchanged98% gate. Nine frozen classpath roles include MAIN/TEST/JMH for webgraph and Cypher, MAIN/TEST_query and MAIN_explore. Test heaps are4g, with the existing filtered-memory task256m; no heap exceeds8g.

Six new behavior tests plus adjacent expanded assertions cover exact tracked diagnostics at budget1/N−1/N/N+1, a preconsumed shared context, zero/empty and unsupported fallback, storage errors/cancellation/interruption, and persisted sparse mixed data with same-type duplicate IDs. A late cross-type duplicate created through the public builder proves preflight emits no partial callbacks/charges before full fallback. Ordinary node decoding is forbidden in the supported projection fixture. The tests do not substitute for performance evidence.

**Real HTTP protocol:** A is frozen pre-upgrade `6f498705009689551c92c6d1ca92f67252ef77c4`, MAIN_query84; B is frozen cumulative341, MAIN_query88; C is this114 runtime, MAIN_query88. A/C is old-relative evidence for these four operations; B/C isolates114. All arms read the same complete19-file validated full-feature Kotlin2.0.21 graph,4,744,132 nodes/2,251,811 CallSites, at `/tmp/sootup-recovery-sources/merged-construction-reference-protocol/results/merged/graph`. Its ordinal and string sidecars are retained. Frozen inventories are checked, files sequentially pre-read before each process and checked afterward, outside measurement. This is a repeatable pre-read policy, not cold OS-cache evidence.

The host is an Apple M3 Max (16 cores, 64 GiB), macOS 14.3 arm64. Each fresh server uses pinned JDK17.0.20.1, `-Xmx8g`, MAPPED load, its matching MAIN_query runtime, a fresh data directory, maximum concurrent Cypher4 and timeout60000ms. Actual old/current CLI serve code configures **Long.MAX_VALUE work units**, while still tracking work. The deprecated ignored work-budget flag is absent. HTTP results therefore do not certify finite-budget exhaustion; the dedicated tests above do. No profiler, forced GC, injected JVM flags, retries or replacement samples are permitted.

POST `/api/graphs/kotlin-full/cypher?limit=20` exercises four fully ordered requests:

|Case|Projection and ordering|
|---|---|
|callee-asc20|callee class/name ascending|
|callee-desc20|callee class/name descending|
|caller-asc20|caller class/name ascending|
|four-fields-duplicate20|all four direct strings plus repeated callee class under another alias; order by callee class/name then caller class/name|

One separate old-server correctness preparation exported four complete typed HTTP bodies. Root reviewed columns, row order, values/types, UTF16 ordering, duplicate projections and raw digests. The first ASC request also matches all20 rows of the established mapped/fallback engine oracle. The other three use the frozen old HTTP response as reference; no separate engine execution is claimed. All measured responses must match their full reviewed oracle, with no metadata/value normalization.

The fixed schedule is **ABC then CBA at concurrency1, followed by ABC then CBA at concurrency4**,12 fresh servers. Every server performs one first-use cycle, two warmup cycles and30 measured cycles of four rotating cases:132 requests/process,1584 total,1440 measured. At concurrency4 the four different cases share a drained wave. The independent four-request oracle preparation is separate, so total preparation plus measurement is13 server JVMs/1588 requests. No additional sample is authorized here.

Latency covers request submission through complete response-body reading; validation and body-file output follow it. Each case/process has30 measured values for nearest-rank p50/p95, arithmetic request mean and maximum. Report both process summaries per arm/concurrency, their mean/median/range and both ABC/CBA block comparisons. Individual first-use values have only n2 per arm/concurrency and are not a tail estimate; retain both warm cycles separately. Earlier requests may initialize shared state.

Measured-window server process CPU includes validation/logging gaps between the30 cycles; client CPU is separate. Whole `/usr/bin/time -l` CPU and peak RSS include server startup, mapped load, first-use, warmup, measured requests, drain and exit. They cannot certify per-case CPU or query-only RSS, and peak RSS values must not be subtracted to infer allocation. Request p50/p95 is primary; separate old-relative CPUtotal/RSS+5% and hard8GiB heap constraints remain. A favorable mixture cannot hide adverse cases.

**Performance and independent verification — complete:** the fixed12 series terminated successfully with all1584 raw responses,20 rows each, matching the reviewed oracle byte-hash and complete typed columns/ordered rows/provenance. Independent audit re-read every response, recomputed per-case30-sample nearest-rank p50/p95, actual request means, all ABC/CBA comparisons and three-arm aggregates; published statistics matched. All twelve origin/MAIN_query/8g/readiness receipts and nineteen-file pre/post inventories matched. The audit did not independently reread graph/JAR binaries; their executor receipts remain the pinned evidence. No replacement, retry or extra performance request occurred.

All eight case/concurrency groups improve measured request p50/p95 versus both old and341, in both ABC/CBA blocks. Mean-of-run p95 versusold improves45.03%–72.08%, versus34151.61%–76.71%. Every declared individual first-use/warmup paired latency also improves; no adverse latency case is omitted. These results include the preflight cost. They do not show that all per-row allocation is gone: the existing projection list/row/map construction remains.

C versusold lifetimeCPU improves64.44%(c1)/63.51%(c4), measured-windowCPU65.26%/64.57%; mean processpeakRSS improves0.69%/1.21%, with all four old/candidate RSS pair deltas negative. This shows no resource cap overrun for the defined combined process/session samples; it cannot establish separate per-case resource caps. **Retained adverse:** C versus341 c4peakRSS increases3.36% inmean, including one+11.23% pair and one−3.37% pair. Old-relative improvement does not erase that parent-relative variation. OriginalB versusold CPU overruns are retained in the tables. All raw mean/median/range/max and absolute deltas remain in the independent JSON.

#### Full8case groups

|Concurrency / case|A p50/p95 ms|B p50/p95 ms|C p50/p95 ms|C/A p50 / p95|C/B p50 / p95|
|---|---:|---:|---:|---:|---:|
|1 / callee-asc20|1355.144 / 1369.305|1532.515 / 1547.832|378.958 / 387.448|-72.04% / -71.70%|-75.27% / -74.97%|
|1 / callee-desc20|1355.340 / 1369.633|1537.665 / 1547.971|375.139 / 391.202|-72.32% / -71.44%|-75.60% / -74.73%|
|1 / caller-asc20|1356.128 / 1376.787|1529.586 / 1545.774|375.547 / 384.391|-72.31% / -72.08%|-75.45% / -75.13%|
|1 / four-fields-duplicate20|1459.910 / 1478.309|1634.219 / 1649.440|782.872 / 798.206|-46.38% / -46.01%|-52.10% / -51.61%|
|4 / callee-asc20|1426.219 / 1450.008|1733.483 / 1757.348|406.966 / 419.205|-71.47% / -71.09%|-76.52% / -76.15%|
|4 / callee-desc20|1429.024 / 1457.548|1734.813 / 1755.635|410.892 / 423.412|-71.25% / -70.95%|-76.31% / -75.88%|
|4 / caller-asc20|1427.875 / 1449.305|1718.591 / 1741.333|399.959 / 405.565|-71.99% / -72.02%|-76.73% / -76.71%|
|4 / four-fields-duplicate20|1494.979 / 1521.530|1788.741 / 1809.484|820.828 / 836.322|-45.09% / -45.03%|-54.11% / -53.78%|

#### Actual requestmeans and both block p95 changes

|Concurrency / case|A mean ms|B mean ms|C mean ms|C/A p95 block1,2|C/B p95 block1,2|
|---|---:|---:|---:|---:|---:|
|1 / callee-asc20|1355.642|1534.727|378.815|-73.32%, -69.85%|-76.41%, -73.31%|
|1 / callee-desc20|1358.030|1537.527|377.127|-72.94%, -69.72%|-76.10%, -73.16%|
|1 / caller-asc20|1358.516|1531.227|376.346|-73.90%, -70.02%|-76.85%, -73.17%|
|1 / four-fields-duplicate20|1461.947|1635.610|784.297|-49.85%, -41.56%|-54.93%, -47.78%|
|4 / callee-asc20|1430.865|1737.048|409.746|-65.61%, -75.37%|-75.20%, -77.10%|
|4 / callee-desc20|1433.146|1737.761|412.306|-66.05%, -74.84%|-75.03%, -76.73%|
|4 / caller-asc20|1431.501|1721.514|400.474|-67.12%, -75.84%|-76.23%, -77.20%|
|4 / four-fields-duplicate20|1497.904|1792.641|821.642|-36.90%, -51.55%|-53.30%, -54.28%|

#### All12 resource rows

WindowserverCPU surrounds30cycles including gaps; lifetimeCPU/RSS include startup/load/first/warm/measured/drain/exit. ClientCPU separate. No percaseCPU/RSS claim or subtraction of peaks.

|Run|WindowserverCPU s|LifetimeCPU s|PeakRSS MB decimal|Lifetimewall s|WindowclientCPU s|
|---|---:|---:|---:|---:|---:|
|c1-0-A|179.62|199.51|1178.780|197.12|0.9637|
|c1-1-B|202.58|225.43|1230.979|222.65|0.9726|
|c1-2-C|58.57|66.62|1175.962|65.79|0.9859|
|c1-3-C|58.52|66.71|1175.175|65.64|0.9701|
|c1-4-B|177.00|197.42|1233.224|194.30|0.9778|
|c1-5-A|157.40|175.42|1188.758|172.78|0.9757|
|c4-0-A|154.17|172.44|1422.164|45.82|0.9640|
|c4-1-B|211.24|236.11|1251.394|61.60|0.9610|
|c4-2-C|64.14|73.35|1391.919|29.58|0.9565|
|c4-3-C|59.54|68.76|1413.726|28.23|0.9555|
|c4-4-B|209.60|234.42|1462.960|60.41|0.9535|
|c4-5-A|194.89|217.00|1417.986|56.54|0.9674|

#### Resource comparisons (means; paired variations retained)

|Concurrency / comparator|WindowCPU Δ|LifetimeCPU Δ|PeakRSS Δ|Both peakRSS pairs|
|---|---:|---:|---:|---:|
|1 / AB|+12.63%|+12.78%|+4.08%|+4.43%, +3.74%|
|1 / BC|-69.15%|-68.47%|-4.59%|-4.47%, -4.71%|
|1 / AC|-65.26%|-64.44%|-0.69%|-0.24%, -1.14%|
|4 / AB|+20.56%|+20.82%|-4.43%|-12.01%, +3.17%|
|4 / BC|-70.61%|-69.80%|+3.36%|+11.23%, -3.37%|
|4 / AC|-64.57%|-63.51%|-1.21%|-2.13%, -0.30%|

Full run-level mean/median/min/max and absolute byte/second deltas are in audit.json; the chosen display does not replace them or define a new acceptance statistic.

#### First-use and both warmup cycles, every case

Each cell lists the two process observations in ms. First-use is cycle0, not a guarantee of independent cache state per case. No p95 from two observations.

|Concurrency / cycle / case|A individual ms|B individual ms|C individual ms|C/A mean Δ|C/B mean Δ|
|---|---:|---:|---:|---:|---:|
|1 / 0 / callee-asc20|1639.102, 1446.147|1997.034, 1740.348|574.924, 568.046|-62.95%|-69.42%|
|1 / 0 / callee-desc20|1436.119, 1294.472|1643.249, 1475.841|387.661, 400.424|-71.14%|-74.73%|
|1 / 0 / caller-asc20|1436.204, 1277.989|1650.188, 1430.637|383.863, 391.711|-71.43%|-74.83%|
|1 / 0 / four-fields-duplicate20|1553.067, 1372.438|1752.076, 1531.470|798.040, 797.065|-45.48%|-51.42%|
|1 / 1 / callee-asc20|1434.241, 1264.010|1647.877, 1428.946|391.543, 385.287|-71.21%|-74.75%|
|1 / 1 / callee-desc20|1432.340, 1265.022|1645.806, 1429.016|375.349, 376.426|-72.13%|-75.55%|
|1 / 1 / caller-asc20|1428.891, 1273.911|1637.246, 1416.554|374.725, 369.933|-72.45%|-75.62%|
|1 / 1 / four-fields-duplicate20|1570.941, 1365.607|1752.068, 1518.201|791.654, 791.800|-46.08%|-51.58%|
|1 / 2 / callee-asc20|1432.685, 1262.954|1630.336, 1445.143|381.420, 380.477|-71.74%|-75.23%|
|1 / 2 / callee-desc20|1443.859, 1263.505|1638.520, 1428.149|377.494, 373.317|-72.27%|-75.52%|
|1 / 2 / caller-asc20|1447.687, 1280.170|1628.917, 1430.210|379.462, 378.701|-72.21%|-75.22%|
|1 / 2 / four-fields-duplicate20|1558.134, 1358.069|1759.452, 1527.956|777.627, 784.431|-46.44%|-52.48%|
|4 / 0 / callee-asc20|1453.987, 1768.477|2076.209, 2074.447|620.495, 643.891|-60.76%|-69.54%|
|4 / 0 / callee-desc20|1454.507, 1768.158|2075.633, 2074.184|620.716, 643.571|-60.77%|-69.53%|
|4 / 0 / caller-asc20|1454.208, 1768.449|2075.963, 2074.295|620.970, 652.833|-60.47%|-69.31%|
|4 / 0 / four-fields-duplicate20|1547.147, 1846.480|2076.233, 2074.431|1031.055, 1062.231|-38.32%|-49.57%|
|4 / 1 / callee-asc20|1275.674, 1590.025|1758.933, 1756.305|468.879, 426.622|-68.75%|-74.53%|
|4 / 1 / callee-desc20|1276.199, 1598.890|1758.811, 1756.457|477.517, 428.083|-68.50%|-74.24%|
|4 / 1 / caller-asc20|1275.902, 1590.054|1759.001, 1756.085|458.609, 434.595|-68.83%|-74.59%|
|4 / 1 / four-fields-duplicate20|1339.991, 1701.983|1896.274, 1868.409|888.913, 838.740|-43.21%|-54.11%|
|4 / 2 / callee-asc20|1295.681, 1597.499|1731.493, 1745.088|446.555, 383.811|-71.30%|-76.12%|
|4 / 2 / callee-desc20|1295.790, 1597.282|1739.118, 1763.289|446.917, 383.845|-71.28%|-76.28%|
|4 / 2 / caller-asc20|1295.692, 1597.545|1730.345, 1703.329|442.072, 384.888|-71.42%|-75.92%|
|4 / 2 / four-fields-duplicate20|1368.109, 1657.877|1815.048, 1775.124|873.479, 804.662|-44.54%|-53.26%|


#### Resource run-level aggregates

Each arm has two process replicates, so its median equals the displayed arithmetic mean. Ranges retain both samples; no mean/max choice is used to hide a cap overrun. CPU seconds; RSS decimal MB.

|Concurrency / arm|WindowCPU mean [min–max] s|LifetimeCPU mean [min–max] s|PeakRSS mean [min–max] MB|
|---|---:|---:|---:|
|1 / A|168.510 [157.400–179.620]|187.465 [175.420–199.510]|1183.769 [1178.780–1188.758]|
|1 / B|189.790 [177.000–202.580]|211.425 [197.420–225.430]|1232.101 [1230.979–1233.224]|
|1 / C|58.545 [58.520–58.570]|66.665 [66.620–66.710]|1175.568 [1175.175–1175.962]|
|4 / A|174.530 [154.170–194.890]|194.720 [172.440–217.000]|1420.075 [1417.986–1422.164]|
|4 / B|210.420 [209.600–211.240]|235.265 [234.420–236.110]|1357.177 [1251.394–1462.960]|
|4 / C|61.840 [59.540–64.140]|71.055 [68.760–73.350]|1402.823 [1391.919–1413.726]|

**Decision: KEEP and integrate 114 as a verified positive increment in the cumulative implementation.** Scoped ordered-query latency, actual first-use/warmup, CPU and old-relative sessionRSS evidence support retaining this change. The integrated four source files exactly match the tested source hashes below. Integration does not establish that the entire user goal is complete; the required CI gate must also pass on the new PR head. It covers four selected direct-string ORDER BY requests on one real Kotlin graph; arbitrary queries, global workloads, productionpopulation tail precision and independent per-case CPU/RSS remain outside these measurements. Two server replicates and30 requests percase provide coarse empirical p95 only. Budget/cancellation correctness is supported separately by tests, not by the CLI's Long.MAX_VALUE work setting. Attempts112/113/115 remain separate and are not credited with these results.

**Reproduction and evidence:** the exact literal server/client argv and input/runtime/source pins are in `/tmp/sootup-static-review/attempt114/scoped-http-packet/plan.sealed.json`. Invocation: `python3 /tmp/sootup-static-review/attempt114/scoped-http-packet/run.py --plan /tmp/sootup-static-review/attempt114/scoped-http-packet/plan.sealed.json --execute-root-released`. The separate correctness command was `python3 /tmp/sootup-static-review/attempt114/scoped-http-packet/run_oracle.py --execute-root-released`. The record reports the completed frozen execution, not additional measurements.

- `/tmp/sootup-static-review/attempt114/source-proof.json` SHA256 `30d2958fa643510ba51f38945e518db58aff4129c834f7eefe82b46930ce45ad`.
- `/tmp/sootup-static-review/attempt114/candidate.patch` SHA256 `2c9bd78b3b2e5b4d5666d980e5a8dd646fc3d4098fff779ba6153e80c4e6457a`.
- `/tmp/sootup-static-review/attempt114/build2-independent-audit.json` SHA256 `2cf549b2226023a4b56fb813e377777b26ed1afde2de3c2039d54f2eed24c12f`.
- `/tmp/sootup-static-review/attempt114/scoped-http-packet/plan.sealed.json` SHA256 `f5defc71c6ba737e7b691bb1ac58bb231a614f5a2c45172f57bc86ced12e73f2`.
- `/tmp/sootup-static-review/attempt114/scoped-http-packet/oracle/root-review.json` SHA256 `8cae91be137826a3a388f884b2354ae3b811933793ff641ce502c17751a5ffa4`.
- `/tmp/sootup-static-review/attempt114/scoped-http-packet/oracle/old-four/client/oracle.reviewed.json` SHA256 `e4d811189cfd4aacf18093f7e4b00f4846a6b9458305729f3788b50c67749acd`.
- `/tmp/sootup-static-review/attempt114/scoped-http-packet/execution/results.json` SHA256 `1253808b5db7d04f34d70924baefd35adf856c3fa58b84a849ee9e19f08ef8cf`.
- `/tmp/sootup-recovery-sources/attempt114-build/attempt114-build2/snapshot/runtime.json` SHA256 `f9b38dced732d0078b662ec51e63e3de001eb7fbd8ed65518e1b957ff071483e`.

The frozen source proof pins QueryPipeline `8a9374242500ac5bd1646ca9596e7face647ea93f964dcce3a4edb2c8db582f2`, StreamingOrderedPropertyProjectionTest `2a5b82e12b9214cd154610db4b745f49b811aa0fbe062796cd3bab0f445c26d0`, MappedWebGraphBackedGraph `9a44cfc0af2c3ba1cfb80c17319b9a41e4d25e36892315835183d1c7e16f7ceb`, and MappedTrackedOrderedProjectionTest `1fcabad86779ec72804f436e2c321962ab01ed82b7ad41967ffc2cf1d7609d02`. The historical source-only proof's pending-test text is preparation provenance, not the final build status. The final performance and audit pins below supersede preparation-only pending status; failed build1 evidence remains unchanged.

Additional completed measurement/audit SHA-256 pins:

- `/tmp/sootup-static-review/attempt114/scoped-http-packet/execution/summary.json`: `ded38daf18e61064b1a408049d721e8f18efc615719d4c584ac51bbafc964885`.
- `/tmp/sootup-static-review/attempt114/scoped-http-packet/independent-audit/audit.json`: `951e6ffb35dbb6d03600e91bf9ed018e450740a60e565cef90e893cec2020ca7`.
- `/tmp/sootup-static-review/attempt114/scoped-http-packet/independent-audit/report.md`: `ada884c5d3423bde02e4da4cf6363cf885f4018b190e8120874317bd004d016c`.
- `/tmp/sootup-static-review/attempt114/scoped-http-packet/independent-audit/audit-proof.json`: `92d4f560a4d9d2a6e0494ef2a3982bf7772ecfcf4b10770a8e9e78b73b303e66`.

### 2026-10-07 — Cumulative 341: Tika memory phases

**Question declared before execution:** does frontend/finalization work establish a larger resident heap high-water mark that persists into count/save, even after natural GC reduces occupancy, overlapping mapped graph data?
This is a diagnostic attribution hypothesis, not an assertion that a particular cache retains the excess or that earlier GC is a fix.
The quiet primary full-feature Tika series already showed old peak RSS 6.4532/7.2943 GB versus cumulative341 8.5728/8.6318 GB: mean **+25.1465%**, paired +32.8454/+18.3354%.
Those primary adverse samples remain unchanged; diagnostic measurements cannot replace them or certify the +5% resource caps.

**Protocol:** two fixed fresh construction JVMs, old6f498 then cumulative341, followed by six strict verification JVMs and two JFR-export JVMs; no added sample or replacement.
Use matching TEST_webgraph roles (54/57 entries), the same real Tika fixture/JDK, default full features, Mmap builder, complete source-node scan, prepared save with two compression threads, and source close.
Construction and verification use `-Xmx8g`; both `jfr print` tools explicitly use `JAVA_TOOL_OPTIONS=-Xmx1g`.
Reuse the pinned ConstructionMemoryDiagnostic helper, buffered phase callback, 250 ms heap MXBean sampler and approximately 100 ms external RSS sampling.
Both construction JVMs use identical startup JFR profile and GC logging; no forced GC, NMT, feature/cache changes or heap adjustment.
All timings below are instrumented diagnostic observations, excluded from performance acceptance.

**Preserved first failure:** execution stopped before any JVM because tooling inherited `MallocNanoZone=0`, violating the sealed no-injected-environment check.
The receipt records exit1, `jvmStarted=false` and zero measured samples; it remains in `execution-packet/preflight-failure1.json`.
After review, launch used `env -u MallocNanoZone python3 .../execute.py --execute-root-released` for all children, with packet/runner bytes unchanged.
`clean-environment-launch.json` records that correction and binds the original failure receipt; it is not a replacement of a measured sample.

**Terminal evidence:** `PASS_DIAGNOSTIC2_STRICT6_EXPORT2`; all ten JVMs and the offline Python phase parser exited zero.
The two completed constructions reported the expected old/current source counts 4,673,289/4,620,490 and CallSites 1,758,353/1,705,428.
Old shape22, metadata13 and five complete typed query reports exactly match the old references; current reports exactly match current main02 semantic references.
These are per-version correctness checks, not cross-version graph equality.
Executor evidence preserves old ordinal-sidecar absence, current exact ordinal sidecar/binding, and identical complete graph inventories before/after verification.
The independent bounded audit rechecked all six full reports, 56 small output hashes and raw heap/RSS/GC aggregation; it compared the inventory receipts without rereading large graph/JAR files.

| Diagnostic process | Whole wall s | Whole CPU s | Peak RSS GB |
|---|---:|---:|---:|
| old6f498 | 109.77 | 174.99 | 6.651003 |
| cumulative341 | 28.21 | 87.18 | 8.630206 |

Peak RSS rises **1.979204 GB (+29.758%)** in this one pair. GB/MB in this record are decimal; GC-log M values below are MiB.
Current has 52,799 fewer source nodes, so node count alone does not explain the direction.

| Phase | Old sampled RSS max GB | Current sampled RSS max GB | Old committed heap GB | Current committed heap GB |
|---|---:|---:|---:|---:|
| Frontend enumeration/indexing | 1.762034 | 1.973322 | 1.082130–2.583691 | 1.082130–2.688549 |
| Frontend pass1 | 2.313699 | 2.259845 | 3.787457 | 3.816817 |
| Frontend pass2/linking | 6.312018 | 6.590710 | 3.787457–5.783945 | 3.816817–6.085935 |
| Graph finalization | 6.367019 | 6.939492 | 5.783945 | 7.675576 |
| CLI node count | 6.437945 | 7.321600 | 5.783945 | 7.675576 |
| Prepared save | 6.649577 | 8.628797 | 5.783945 | 7.675576 |

**Finding:** the phase-level high-water/overlap hypothesis is supported, with a boundary qualification: the large commitment expansion occurs around finalization, not proven to originate in enumeration or a specific frontend cache.
Current GC(39) remark completes at uptime21665 ms, only26 ms after the finalization marker21639 ms, reporting7320 MiB commitment.
Buffered callbacks and sequential samples cannot establish the exact allocation/GC cause inside that boundary.
The excess is already visible before save, and both versions retain their respective commitment through save; save adds further resident pressure.
Thus the result is neither a save-only increase with equal pre-save commitment nor proof that every excess resident byte was resident before save.

During current save, GC(42) reports6221→3888 MiB and GC(43) remark3910→2778 MiB; commitment remains7320 MiB.
At uptime25576 ms, sampled used heap is2.919849 GB and commitment7.675576 GB; the nearest RSS observation about2.6 ms earlier is8.548729 GB.
This supports continued high residency/commitment after occupancy falls, not a retained-live object census.
Old late-save young-GC occupancy is about4309 MiB; different collection types/times prevent a comparable full-GC retained-live conclusion.
Save mapped capacity maxima are313.430/321.703 MB, only8.274 MB apart, but mapped capacity is not resident mapped-file pages.
Heap commitment is not residency; RSS minus heap used/committed is not native-memory accounting.

RSS samples miss the independent time-l peak by1.425 MB(old)/1.409 MB(current); observations and phase labels are not atomic.
Both time-l reports record zero swaps, which does not establish a paging/GC cause or eliminate scheduling effects.
The pair is n=1 per version, old first, with callback/JFR/sampler perturbation; no repeatability, exact causal ownership or cap acceptance follows.

**JFR boundary:** both selected-event exports succeeded, but their2.905 GB/375 MB JSON bodies were not parsed in this bounded small-file audit.
No missing-event-as-zero inference or allocation-stack attribution is made. JFR allocation samples/weights would describe sampled allocation activity, not retained bytes.
A separately authorized streaming analysis of existing events could localize activity around finalization without another JVM; it would still not identify retained roots by itself.
**Next action116:** inspect/test the bounded cache-lifecycle change as an independent hypothesis. This diagnostic does not prove that cache is the RSS cause or that116 will reduce the primary peak.

Evidence: `/tmp/sootup-static-review/tika341-memory-next/{review.md,execution-packet,independent-audit}`.
Terminal results SHA256 `995cd5485822c9153b5555b27b5251c4a917447f9f2b45ec0377e6fe45c02cfb`; phase-analysis SHA256 `e737b380b721a5e8e499eafd96f058a1ee6f9ebefb8c9bac0ab7b95847bd3c8d`.
Independent audit SHA256 `a4f7aaebb1b0ed39afbd5b6c3007996970eb3946275fbcdd0eaa2379c42b1533`; report SHA256 `31c54e7543b84643f3c791094c9de97af67d90cadf87c716423f930a1beb90a8`.

### 2026-10-07 — Attempt 117: reject ordinal-count growth before buffer mutation

**Problem and scope:** PR review found that `CallSiteOrdinalPersistenceInput.add` writes fixed arrays before `encode` checks the first-pass count. A custom or lazy graph with more ordinal-bearing CallSites at write time therefore threw an incidental array-index exception. This correctness repair was prepared independently from `1ab8c912` while attempts115/116 were still being validated; it is not a new performance hypothesis.

**Change:** check capacity immediately after excluding unknown ordinals, before changing sort state, arrays or size. Both extra and missing ordinals now report `Call-site ordinal count changed while saving`. The existing stable graph format, ordinal/origin fields and unknown-ordinal behavior are unchanged. This does not make graph-directory writes transactional: earlier writes can still exist when a consistency failure aborts saving.

**Verification:** all264 fresh webgraph tests passed, including three new tests: zero/full capacity preserves captured bytes after a failed add; missing ordinals still fail at encode; and full GraphStore saving detects increased ordinals between count and write. Source review caught an intervening metadata node scan in the custom fixture before execution; the final test changes ordinals only on the third scan and asserts all three visits. The original source review artifact remains preserved. Existing shuffled/sparse/duplicate ordinal and persisted round-trip checks also pass. Detekt passed; actual Kover0.9.1 reports6401 covered/116 missed lines, **98.2200399%**, against the unchanged98% requirement. No new performance measurements or speedup claims are made for this guard.

Command: `env -u MallocNanoZone python3 /tmp/sootup-recovery-sources/attempt117-build/execute.py`. The wrapper runs `:webgraph:test :webgraph:detekt :webgraph:koverLog :webgraph:koverXmlReport :webgraph:koverHtmlReport` using JDK17.0.20.1 on M3 Max/macOS14.3, four-GiB Gradle/test heaps, in-process Kotlin compilation and no build cache. Full argv and environment are retained in the result. Source/tool hashes, fresh XML times and all three new testcase names were independently checked; temporary publishing-plugin changes were restored. Coverage thresholds and test filters were not weakened.

**Decision: KEEP and integrate the explicit consistency guard.** The two integrated source files exactly match the independently tested candidate. This fixes the ordinal review defect, not the outstanding Tika RSS or query latency regressions; the required CI gate remains necessary on the combined PR head.

Evidence: `/tmp/sootup-static-review/attempt117/{source-proof.json,root-build-audit.json,before-source-review}` and `/tmp/sootup-recovery-sources/attempt117-build/attempt117-build1`.
Result SHA256 `4da133ef8a02e3c0a042556bd9024acfa62e3bfde3590ddbb5e9a8d3b82a195a`; production SHA256 `d61934e5aaf6c84677a4d01d04bd62d76eb17a8cdd7a7f26f7109d6c8ba645d3`; test SHA256 `f051690889e9c0ba24da25c7acfff853e52357df61852f888220979c510410a4`.

### 2026-10-07 — Attempt 116: bound method-source caches to the active class

**Problem:** the per-class removal in pass2 did not bound cache lifetime. Entry-point discovery and call-graph cleanup subsequently traversed the complete view and repopulated both caches; ancestor, enum and control lookups could also insert classes outside the processing loop. Source references already owned by the view are not necessarily new retained ASM bodies, but the extra cache tables and sorted method lists outlived their intended scope. The Tika phase diagnostic does not establish this as the cause of its RSS overrun.

**Change:** only the current pass2 class identity may populate `streamingSourcesCache` and `bytecodeMethodsCache`. All other existing callers resolve without insertion. A per-class `try/finally` clears both maps and resets ownership on normal and exceptional exit. Processing order, method wrappers, sorting, original exception handling and nullable `getOrPut` behavior remain unchanged. No entry-point raw filtering or wrapper reuse is bundled into this correction.

**Validation history:** isolated parent `1ab8c912`; attempt numbers were reserved before validation, and117 completed first. Build1 failed the unchanged NestedBlockDepth lint rule before tests. Extracting the unchanged per-class owner/try/finally body into a private helper reduced nesting; independent review reconstructed the original source exactly by inlining it. Build2 passed production compilation/lint but failed compilation of the new test: the anonymous JavaView shadowed Fixture.classes, and two constructors required explicit JavaSootClassSource types. The test-only fix qualifies the outer property and casts the known Java sources. All original assertions and both failure records remain intact; thresholds and baselines were not relaxed.

Build3 passed **542 fresh SootUp tests**, zero failures/errors/skips, including three new lifecycle tests built from real ASM class structures. They verify active-class result reuse, no insertion for other classes or discovery/cleanup/enum/control paths, unchanged graph method contents, and original extension exception identity with cache release. These small generated classes are correctness fixtures, not performance evidence. Detekt passed; real Kover0.9.1 reports3724 covered/68 missed lines (**98.2067511%**) against the unchanged98% requirement. Source hashes and publishing-plugin restoration were independently verified.

Command: `env -u MallocNanoZone python3 /tmp/sootup-recovery-sources/attempt116-build/execute.py`, final output `attempt116-build3`. The archived wrapper runs `:sootup:test :sootup:detekt :sootup:koverLog :sootup:koverXmlReport :sootup:koverHtmlReport` with JDK17.0.20.1, four-GiB Gradle/test heaps and in-process compilation on M3 Max/macOS14.3. Full argv, fresh XML and all three required testcase names are retained.

**Decision: KEEP the verified cache-lifecycle correction in the draft cumulative implementation, with real-data performance validation pending.** The two integrated files match the tested hashes. No measured latency, CPU, allocation or RSS improvement is claimed. Resolving outside the owner can repeat sorting, so the planned fixed Kotlin ABBA and Tika ABBA comparison against frozen341 must measure complete construction/save, CPU and RSS and verify every output. Parent-relative attribution does not replace pre-upgrade acceptance. The Tika +25.15% quiet RSS regression remains unresolved;115/118 query candidates remain separate. Required exact-head CI is still necessary for the combined PR.

Evidence: `/tmp/sootup-static-review/attempt116/{source-proof.json,root-build3-audit.json,build1-source,build2-source,construction-packet}` and `/tmp/sootup-recovery-sources/attempt116-build/attempt116-build{1,2,3}`.
Build3 result SHA256 `e63cea756c3e4d21af31e7d72dca750d0986523c5853120dda862eb8f27965e4`; production SHA256 `6385800d5f13f06d0f346af4d24c9ca479486eb29e20cac0d357eec87a3bb84f`; test SHA256 `218451004af8be96694a7c47e6db8d5d27451c1289218398bce9e4f8e084fedb`.

### 2026-10-07 — Attempt 115: reacquire prepared-query workers after overlapping inline work

**Hypothesis and isolated scope:** attempt113 counts inline requests as active prepared queries, so an
overlapping request can keep later graph suffixes inline even after the worker owner has finished.
Candidate115 uses an owner-only lease and retries acquisition before each unexecuted suffix,
retaining graph encounter order, existing scanners and result merging. Its parent is the retained
113 commit `77b85a7bd9d9d21b830b0f88f78c30aae7dd1b85`, not cumulative341 or the current PR head.
It does not contain114/116/117. Lease acquisition is not fair and bounded worker speculation can
exceed serial113's actual work; no claim of identical total speculative work is made.

**Validation history:** build1 failed the unchanged NestedBlockDepth lint rule in a new test.
Extracting the same test coordination into a helper preserved its assertions. Build2 exposed a
real coordinator-interruption defect: `outcomes.take()` cleared the interrupt flag. The final
candidate restores the coordinator flag before propagating that exception; it does not interpret
a worker's interruption as a coordinator interruption. The original failing assertion remains.
Build3 passed **1,656 fresh tests** (1,345 Cypher, seven filtered memory checks, 261 webgraph and
43 query), zero failures/errors/skips, including the original113 and new115 concurrency cases.
Detekt passed and real Kover reported6,558 covered/129 missed lines (**98.0708838%**), above the
unchanged98% requirement. Runtime-role separation, source pins and publishing-file restoration
were independently audited. Both failed builds and their exact source snapshots remain retained.

**Fixed real HTTP experiment:** A is actual pre-upgrade `6f498` (`MAIN_query`,84 entries),
B is113 (`MAIN_query`,88), and C is115 build3 (`MAIN_query`,88). The unchanged real64 fixture
contains class shards from Android14, Tika2.9.2, Hive4 and Kotlin2.0.21 JARs; all64 graphs are
loaded in manifest order. The same34 requests and complete typed-response oracle used for113
are retained. Previously parameterized engine cases use the same reviewed literal-equivalent
HTTP requests. The fixed order is c1 ABCCBA, then c4 ABCCBA, on ports18301–18312. Each process
runs one first-use cycle, two warmup cycles and30 measured cycles: **13,464 total responses,
12,240 measured**. All first-use, warmup, measured samples and adverse results remain present.

Command: `env -u MallocNanoZone python3 /tmp/sootup-static-review/attempt115/server-request-packet/run.py --plan /tmp/sootup-static-review/attempt115/server-request-packet/plan.sealed.json --execute-root-released`.
The sealed packet contains the full server/client argv. Environment is M3 Max16-core/64GiB,
macOS14.3, JDK17.0.20.1, mapped loading, `-Xmx8g`, default tracked `Long.MAX_VALUE` work limit,
60-second timeout and maximum concurrency4. Percentiles use nearest rank on30 samples per
case/process; the table below summarizes arithmetic means of two process-level observations,
not pooled p95 or a precise production-tail estimate. Whole CPU includes user plus system for
the server lifetime; peak RSS includes loading, first/warm requests and shutdown. Neither is
per-case resource evidence. Complete per-case, paired, first-use/warmup and resource tables are
retained in the independent report.

**Correctness PASS; performance isolation FAIL.** All13,464 full response bodies, columns,
typed ordered rows, graphCount64 and provenance match the oracle. Independent recomputation
also agrees with every per-case statistic, pair and resource calculation. However, foreign
Gradle and Kotlin JVMs started after the initial idle check. Logged builds in another worktree
and subsequently `graphite-fold-verify` overlap six estimated server lifetimes:
c1-0-A, c1-3-C, c1-4-B, c4-0-A, c4-3-C and c4-4-B. The other runs have no matching logged
build interval, but cannot be certified clean: the daemons remained resident and their historical
CPU activity is unavailable. Whole-second process start timestamps bound the lifetime estimates;
individual requests have monotonic timings without exact UTC boundaries. Terminal0% CPU does
not establish earlier idleness. No subset was selected as clean and no sample was replaced.

| Concurrency / comparison | Cases with lower / higher p50 | Cases with lower / higher p95 | Whole CPU | Peak RSS |
|---|---:|---:|---:|---:|
| c1,115 versus113 |31 /3|22 /12|−0.25%|−9.34%|
| c1,115 versus old |28 /6|23 /11|−0.56%|−4.72%|
| c4,115 versus113 |30 /4|26 /8|+3.14%|−5.01%|
| c4,115 versus old |25 /9|23 /11|+2.18%|−8.94%|

These are **confounded descriptive observations**, not verified causal gains or proof of the
5% resource caps. For example, c4 name-pair-targeted p50 is38.65% lower than113, while
c4 caller-class-targeted p95 is3.77% higher; c1 wrapped DISTINCT-zero p95 is15.14% higher.
Against old, c4 wrapped DISTINCT-zero p95 is24.56% higher and localized-middle p50 is12.13%
higher. The complete report retains all68 case groups and every process pair rather than using
the favorable counts to declare recovery.

**Decision: RETAIN ISOLATED, performance validation incomplete.** Preserve the candidate and
verified concurrency/error behavior; do not integrate it on the strength of contaminated timing
data, discard it for missing global acceptance, or replace only unfavorable samples. A subsequent
performance replication requires a separately declared complete fixed series and a confirmed
measurement window. Existing Tika RSS, eager-loading and per-case query regressions remain open.

Evidence: `/tmp/sootup-static-review/attempt115/{source-proof.json,build1-source,build2-source,build3-independent-audit,server-request-packet}`
and `/tmp/sootup-recovery-sources/attempt115-build/attempt115-build3`.
Candidate patch SHA256 `66e335ed307c69d129ff489bd81e60e3c0c79c537fc9677ab185aefb45b68db2`;
source proof `fd2c98348df55f149d8efc41dad1961534e11817c2adbbcbbfa0fab035193289`;
build audit `f65797356b6140f96c0d2c979748408128dd034cd403cb6e1ec9b387aa069863`.
HTTP results SHA256 `ace8750b9a3868839a6f72ecde69c411be35931a85e471b552713f4e0e36a133`;
summary `2548f736f6f347c648c681deb3418d5d042079aa05cadb0329c7b58e3b43e4ad`;
independent audit `f08aa8104eb0fa1c4ee01bb6c0ed13df037efa637ba95c7ec6d876a5f3ff4908`;
full report `ce084e7f087e5044410c43fc84fd0328ce717f0574e75d6a0f867c2b222b72ca`.

### 2026-10-07 — Offline allocation analysis of the retained Tika phase diagnostic

This completes analysis of the already recorded old6f498/cumulative341 diagnostic pair above;
it is not another measurement, optimization attempt or repetition selected after seeing results.
The two retained selected-event JSON exports were parsed serially with Python/SQLite, without
starting a JVM, changing inputs or rehashing the multi-GB exports. Commands:

```text
python3 /tmp/sootup-static-review/tika341-memory-next/offline-jfr/analyze.py --execute-reviewed --input /tmp/sootup-static-review/tika341-memory-next/execution-packet/execution/run0 --output /tmp/sootup-static-review/tika341-memory-next/offline-jfr/run0-analysis
python3 /tmp/sootup-static-review/tika341-memory-next/offline-jfr/analyze.py --execute-reviewed --input /tmp/sootup-static-review/tika341-memory-next/execution-packet/execution/run1 --output /tmp/sootup-static-review/tika341-memory-next/offline-jfr/run1-analysis
```

Both commands exited zero and input size/mtime remained unchanged. The streaming parser had
previously failed a chunk-boundary whitespace fixture; the original failure and source were
retained, and the corrected parser passed boundary/truncation tests before either real export
was processed. Observed pending buffers stayed below197KB; this is not a process-memory cap.
Root independently reconciled every phase/event count and allocation-weight sum across SQLite
dimensions with the JSON report, phase rates with monotonic marker intervals, all selected-event
totals, and the GC interval crossing finalization. This output audit did not reparse the raw exports.

| Phase | Old /341 duration (s) | Old /341 allocation weight (decimal GB) | Old /341 weight rate (GB/s) |
|---|---:|---:|---:|
| Enumeration and indexing |1.421592 /1.542269|1.770420 /1.875276|1.245378 /1.215920|
| Frontend pass1 |0.687209 /0.341107|0.474192 /0.354643|0.690025 /1.039684|
| Frontend pass2 and linking |100.966572 /19.565299|116.616217 /32.496644|1.154998 /1.660933|
| Graph finalization |0.638546 /0.701825|0.448536 /0.445355|0.702434 /0.634567|
| CLI node count |0.354348 /0.406240|0.461144 /0.637214|1.301389 /1.568564|
| Prepared save |5.410756 /5.356845|3.511008 /3.368368|0.648894 /0.628797|

Weights estimate sample-represented allocation volume, **not retained bytes, exact allocation,
peak heap or RSS**. Including startup and post-construction events, old/current totals are
123.306622/39.205729GB, from30,891/7,533 allocation samples; execution samples are5,485/1,136.
The lower pass2 total accompanies a higher represented allocation rate. Finalization weights
are similar, so this pair does not support a larger current finalization allocation-volume peak.
The increased CLI count weight remains visible; it cannot be removed from the E2E boundary.

Current gcId39 spans pass2 and finalization:07:19:23.220192667Z–07:19:24.546319584Z.
Its1.326-second G1Old collection interval is not pause time and cannot all be assigned to
finalization. Concurrent allocation also prevents treating its after-GC occupancy as a retained
live-set measurement. Combined with the earlier heap/RSS timeline, this directs investigation
toward allocation rate and heap residency across the boundary, rather than attributing the peak
to finalization solely from its timestamp. It does not prove cache retention or a GC-tuning fix.

Missing allocation stacks old/current are21/16; truncated allocation stacks3,682/34 and
truncated execution stacks1,496/0. All six selected event types are present, without missing
timestamps or weights. Sampling weights can represent allocation before their event timestamps,
and short phases without samples are not proof of zero work. The quiet-primary Tika RSS
regression remains **+25.15%**; none of these diagnostic quantities replace that acceptance result.

Evidence: `/tmp/sootup-static-review/tika341-memory-next/offline-jfr/`, including both
`analysis.json`/`aggregates.sqlite` outputs, `comparison.json`, `report.md`, `execution-proof.json`
and `root-output-audit.json`. Parser SHA256
`d52ec64a26e08154b30152251aee98ecb1832ebd4879f6e682bef700aef193ba`;
report `0dadb85bc51e132597f5984817d4a3e4d9348ce2c9c294305974da0f80daec88`;
old analysis `71c62edfbee8fc4e1c98e0a3e4787ff2e24672022d53517436ef34b7795b6769`;
current analysis `56ec868e0ead11d2f6600ca3741425ad12e071ce57db0c86c889f5ab3896557c`.

### 2026-10-07 — Clarify JVM and native-server acceptance boundaries

The local HTTP experiments recorded above launch the **JVM compatibility server**, using the
frozen `MAIN_query` runtime. Their response checks and incremental results remain evidence for
that implementation. They do not establish performance recovery for native `graphite serve`,
which dispatches to Rust in both pre-upgrade `6f498705009689551c92c6d1ca92f67252ef77c4` and
current source. The JVM API and `java -jar graphite.jar serve` remain supported and must retain
their behavior; neither runtime's results can silently substitute for the other's.

The required `db713c98` native latency CI did send real HTTP requests and read complete bodies.
Its comparison is upgraded main `02b853b7`, however, and each case has five sequential samples.
Case scores use their median; the reported aggregate p50/p95 summarizes different query cases,
not a repeated-request p95 for each case. It has no concurrency4 or separate server-readiness,
CPU and peak-RSS acceptance evidence. Both arms use the same candidate-generated fixture64.
The latency gate's digest excludes row `$metadata`, checks repeatability within each arm, and
is not itself a full cross-version typed-response/provenance oracle. Other gates retain their
own contracts; a green latency report does not acquire their coverage automatically.

Rust backend/CLI source matches upgraded main but differs from the pre-upgrade revision,
including ordinal-sidecar loading and related query properties. Cargo.lock and workspace
Cargo.toml files are unchanged. Source equality with main cannot prove that graphs produced by
different frontend revisions have identical loading or query costs. Native acceptance still
requires an explicit old-relative response oracle, per-case request p50/p95, declared concurrency,
and independently bounded loading/CPU/RSS evidence. Existing JVM gains are retained and labeled,
while native coverage is prepared separately. No new latency samples or acceptance claim arise
from this source/CI audit.

Evidence: `/tmp/sootup-static-review/native-runtime-acceptance-scope/{report.md,source-identities.json}`;
report SHA256 `faadbe29478d4b652130b16f00f3e7246f04393e0cbd455d9524f166056ec727`.

### 2026-10-07 — G1 region evidence narrows the retained Tika RSS hypothesis

The existing diagnostic `gc.log`, phase markers and heap samples were analyzed without a new
JVM or measurement. Both arms used4MiB regions, a1GiB initial heap and an8GiB maximum. Old
committed capacity reaches5,516MiB; cumulative341 reaches7,320MiB at GC39's remark, about26ms
after finalization begins, and keeps that capacity through later reclamation.

At the first save GC, old GC100 has263 populated Eden regions; current GC42 has629, a difference
of1,464MiB of region capacity. After those collections, survivor+old+humongous regions total
981 versus972: current occupied-region capacity is36MiB smaller, despite1,804MiB more committed
heap. Root independently checked those counts against the original log lines. These are
phase-comparable collection points, not simultaneous measurements or retained/live-object sizes.
Current save remark later lowers used heap from3,910 to2,778MiB while committed remains7,320MiB.
The observed larger capacity therefore cannot be described simply as more occupied old regions.

**Next hypothesis:** adaptive young-generation sizing and persistent committed/resident headroom
may explain a substantial part of the extra late-save RSS. The logs lack the complete ergonomic
decision trace and an OS residency breakdown, so this is not proven RSS attribution. It does
not establish a cache leak, justify forced GC, or permit a larger heap. Any discriminating tuning
experiment must predeclare a bounded, symmetric old/current comparison and retain all original
features and boundaries; adopting defaults would still require query p50/p95, E2E, CPU and RSS
acceptance. No GC policy or production setting was changed here.

Evidence: `/tmp/sootup-static-review/tika341-memory-next/gc-region-analysis/{report.md,analysis.json,root-region-audit.json}`;
report SHA256 `734bbc06c963294307716797c972baae2ddedd87884e24ea58ddab7ae2bfb749`.

### 2026-10-07 — Review follow-up: preserve inline WHERE before DISTINCT LIMIT

**Scope:** review comment4204060675 suggested removing the ordered projection's inline-WHERE guard because the sibling DISTINCT shortcut lacked it. Public `QueryPipeline.execute(clauses)` accepts a non-optional `Match` with `where`; the ordered guard is necessary. Inspection instead found that the sibling shortcut could return before evaluating this predicate. The same omission exists in preupgrade6f498; this is a pre-existing JVM AST correctness repair discovered during recovery, not a new SootUp regression or performance gain. Ordinary DSL WHERE clauses and the native Rust server are separate paths.

**Change:** reject inline `Match.where` from `tryFastDistinctPropertyLimit`, preserving filtering through the existing general executor before DISTINCT and LIMIT. Keep the other eligibility checks and all cancellation/work-budget behavior. No new fast path or benchmark setting is introduced.

**Verification:** with the production guard absent, five focused tests produced exactly three expected assertion failures and two passing controls. The failures returned `rejected` instead of `shared`, leaked a value from a zero-match query, and returned incorrect cross-graph provenance. With the one-condition fix, all1,341 Cypher tests plus the original seven256-MiB relationship-memory checks passed, including all five new cases. Tests assert ordered concrete values, columns, LIMIT0/1/2/10, filtering before deduplication, contributor provenance, and unchanged unfiltered/OPTIONAL behavior. Detekt and unchanged98% Kover verification passed; real-agent line coverage6515/6645 =98.0436418%. Root independently parsed33 XML reports, focused failures/successes and the coverage report, and rechecked607 source pins. Existing foreign JVMs were observed and left alone; these are correctness runs and provide no performance evidence.

Exact commands are retained in `/tmp/sootup-recovery-sources/distinct-inline-where-correctness/plan.json`: `env -u MallocNanoZone python3 .../execute.py --stage red --execute-root-released`, then the same command with `--stage green`. The red stage runs only the new test class against the pinned original production source. The green stage runs `:cypher:test :cypher:detekt :cypher:koverLog :cypher:koverXmlReport :cypher:koverHtmlReport :cypher:koverVerify`, including the existing memory-test dependency, on JDK17.0.20.1/M3 Max/macOS with four-GiB Gradle/test heaps and the original256-MiB memory-test heap. Both stages preserve full logs/XML, disable build cache, run Kotlin compilation in-process, and restore all five temporary publishing-plugin edits. No tests, thresholds or expected values were weakened after the red run.

**Decision:** retain and integrate the correctness fix. It does not establish latency, CPU, RSS, loading, construction or native-server recovery. Evidence: `/tmp/sootup-static-review/distinct-inline-where-correctness/{source-proof.json,root-red-audit.json,root-green-audit.json}`. Red result SHA256 `eaf49b1aa78d6577fa83c811dcefcbcdee00c057c278687cc6221e2f8c3ad509`; green result SHA256 `27a921bf397a6a16f7ddc7410e7c12075c8c13bc308689ad32549da3033cdc5e`.

### 2026-10-07 — Native preupgrade/current full-response correctness baseline

**Boundary:** the primary native `graphite serve` runs Rust in both preupgrade6f498 and currentdb713. Rootcbb4b9eb changes only JVM correctness/docs relative to the previously inspected native trees. Independently built macOS arm64 release binaries use the same pinned Rust1.93 toolchain, locked Cargo dependencies, explicit host target and jobs2. Each native server loads the same unchanged64 real persisted graph shards and receives the same34 existing query requests once. This is a correctness baseline, with no latency/CPU/RSS/loading-recovery claim and no inference about separate old-generated/current-generated full graphs or new ordinal/descriptor/fold behavior.

**Initial harness failure retained:** the first old-native request returned HTTP200, but the validator incorrectly allowed only the four JVM response fields and rejected native `total`. It stopped after one request, reaped its owned server and launched no current server. Both native revisions have identical HTTP source: `run_cypher` appends `total`, whose `eq` value equals returned rows and whose `gte` value is returned rows plus one when the engine observes another row. The failure came from an incomplete harness schema assumption. The original plan, raw body and failure remain intact; no expected query value was changed.

**Corrected fixed pass:** a separately sealed packet requires all five native fields, verifies integer total values and relation semantics, and retains `total` in the full native comparison. Two serial servers completed all68 HTTP200 responses. All34 old/current native response pairs match completely, including scalar types, ordered columns/rows, nested provenance and total metadata. All68 responses also match the unchanged JVM oracle on its complete shared envelope (columns, rows, rowCount, graphCount). Every exact cross-runtime comparison remains different solely because native includes `total`; the strict runner's `FAIL_SEMANTIC_COMPARISON_COMPLETED_68` exit is preserved rather than rewritten to PASS. The separate native-equality, shared-envelope and HTTP-integrity verdicts all pass. Each arm has20 `eq` and14 `gte` totals. Startup graph metadata agrees after excluding volatile loadedAt and aligning graph entries by ID; raw listing order remains archived. Both servers were reaped, and no input pin or fixture stat identity changed.

Root independently reread all68 request/response bodies, checked their hashes and concrete request contents, rebuilt type-sensitive structural identities without sorting result arrays, verified all totals, compared native pairs and the independent JVM reference, and checked both fixture stat inventories. Existing1216-file content-hash receipts establish fixture provenance; this run verified exact inventories, sizes and stat continuity, not a repeated10GB content-hash sweep. A full native preupgrade oracle is now frozen for subsequent prespecified request-latency measurements. One correctness pass does not establish performance or global semantic coverage.

Commands: `python3 /tmp/sootup-static-review/native-http-correctness/run.py --execute-root-reviewed` (preserved schema failure), then `python3 /tmp/sootup-static-review/native-http-correctness-v2/run.py --execute-root-reviewed` (fixed68 pass). The plans contain literal `serve --port ... --load-mode MAPPED --graph id:path ...` argv, input/binary/source pins, fixed ports, bounded readiness, complete raw outputs and owned-process cleanup. No JVM or heap-setting change occurred. Native listens on its existing0.0.0.0 binding; clients used127.0.0.1 with proxies disabled.

Evidence: `/tmp/sootup-static-review/native-http-correctness-v2/{execution/results.json,execution/raw-consistency-audit.json,independent-audit/audit.json,native-oracle.reviewed.json}`. V2 result SHA256 `d91c216e5adbbed174ee9423b7719caf7a0873f0489da0961d82f91bcd07e602`.
Independent root audit SHA256 `493aaf8742568634b621074cb02f894a4b5b99bdc20a2da00e1701cfbc958609`; native oracle SHA256 `1edcb2815cebb8b43f435c0f5d7c9de92a9f641df890fe121dfe2697efbf7387`.

### 2026-10-07 — Attempt 116 fixed construction diagnostic: retained mixed results

**Protocol and environment:** the previously tested current-class-only cache fix is compared with cumulative341, using two fresh full Kotlin builds and two fresh full Tika builds per arm, fixed Kotlin ABBA then Tika ABBA. A=341, B=116. Both use their pinned TEST_webgraph57 runtime, identical complete input/features, eight-GiB maximum heap, the unchanged quiet construction helper, complete CLI node count, prepared save with two workers, and source close. These are parent-relative attribution diagnostics, not a replacement for the preupgrade baseline or native server p50/p95. The exact32 construction/verifier command arrays remain SHA256 `274a3204673bc5c070218623c9fe016ab96a2a6fbc83d6a53f0d28bda3e19014`.

**Preflight failure and explicit policy correction:** an initial fixed10-second observation stopped before any workload because two pre-existing external JVMs used approximately0.03 CPU seconds; no new build-log event was observed. That zero-sample failure and its strict-zero-activity rule remain archived. The rule was unnecessarily strict for a diagnostic. Before taking any construction sample, a separately reviewed amendment replaced it with one fixed observational startup and labelled the entire new series `DIAGNOSTIC_WITH_OBSERVED_BACKGROUND_ACTIVITY` from the outset. Observation errors/log loss still reject startup; all background activity, raw samples and failures remain recorded. No favorable subseries was selected, no measured sample was replaced, and the user was not assumed to have reserved an exclusive window. Neither heaps nor workload/correctness/resource thresholds changed. The foreign processes were left alone.

**Completed correctness:** all eight builds and24 separate validators passed. Each graph matches its own current-runtime22 shape,13 metadata and five complete ordered query references; exact ordinal sidecar/binding checks passed. All152 graph-file content hashes agree before/after verification. Root independently checked all24 actual/reference reports,40 complete query comparisons, raw construction/time-l outputs, all summary statistics and paired changes, and the before/after inventory receipts. The owner rechecked1825 input pins at termination; root did not redundantly reread the large graph payloads. These checks do not establish preupgrade/current graph isomorphism.

| Fixed sample | Full construction E2E s | Build s | Save s | Whole CPU s | Peak RSS GB |
|---|---:|---:|---:|---:|---:|
| kotlin-0-A | 37.795504 | 30.553678 | 6.698031 | 105.560 | 9.642689 |
| kotlin-1-B | 41.729956 | 29.773106 | 11.579243 | 106.570 | 7.805452 |
| kotlin-2-B | 37.831290 | 30.461616 | 6.948632 | 106.900 | 9.554493 |
| kotlin-3-A | 37.455762 | 30.230567 | 6.858243 | 104.720 | 8.074707 |
| tika-0-A | 27.836227 | 22.057730 | 5.390642 | 86.800 | 8.589967 |
| tika-1-B | 28.090095 | 22.252301 | 5.493534 | 83.550 | 8.482292 |
| tika-2-B | 27.962628 | 22.253362 | 5.367223 | 82.590 | 8.478409 |
| tika-3-A | 28.095006 | 22.481574 | 5.262847 | 82.410 | 8.534524 |

All means below use both predetermined samples per arm; GB is decimal. CPU is complete process user+system. E2E includes build/count/save/close.

| Corpus | E2E change | Build change | Save change | CPU change | RSS change |
|---|---:|---:|---:|---:|---:|
| kotlin | +5.727% | -0.904% | +36.674% | +1.517% | -2.018% |
| tika | +0.217% | -0.076% | +1.946% | -1.814% | -0.956% |

**Adverse samples and uncertainty:** Kotlin first-B save11.579243 seconds is retained. Its RSS paired changes are -19.053% / +18.326%; the small negative mean does not establish a repeatable memory gain. Tika RSS paired changes are -1.254% / -0.658%; CPU paired changes are -3.744% / +0.218%. All other pairs/ranges and raw values are preserved in the summary.

The monitor recorded944 snapshots. Across the construction block, the two resident external JVMs had21 accumulated-CPU increment events (approximately0.33 seconds combined) and eight positive instantaneous-CPU observations; verification had43 increments (approximately0.71 seconds) and19 positive observations. No new external JVM, logged build, missing-log or incomplete-observation event was detected. This bounds only what the one-second metadata/log observer saw; it does not prove zero interference, detect every native/I/O task, or quantify a causal slowdown. Observer/controller overhead remains outside the target CPU/RSS counters. The predeclared diagnostic label is unchanged despite the low observed background activity.

**Decision and next action:** retain116 as the verified cache-lifetime correctness fix and preserve all mixed diagnostic evidence. Its roughly1% Tika RSS mean difference does not demonstrate recovery from the earlier quiet preupgrade-relative25.146% increase. Kotlin's save/E2E and opposite-sign RSS pairs remain unresolved. These data do not justify reverting a correctness repair or declaring a CPU/RSS cap passed. The next discriminating memory hypothesis concerns G1 young-generation sizing and committed capacity, supported by the separate retained GC evidence; no VM default has been changed. Native request latency has its own oracle and fixed-series preparation.

Command: `env -u MallocNanoZone python3 /tmp/sootup-static-review/attempt116/construction-packet/execute.py --execute-root-released`. Environment remains JDK17.0.20.1/M3 Max/macOS; exact source, runtime, flags, input, reference and environment identities are in the sealed packet. Evidence: `execution/{results.json,summary.json,graph-pins-before-verification.json,graph-pins-after-verification.json}`, `host-observation/` (initial failure), `host-observation-diagnostic/`, and `independent-audit/{audit.py,audit.json}`. Result SHA256 `1e1021627140f80a25443c43d4ed26d377b6a2c7f81580a6c4d815a7eef6368f`; summary SHA256 `fcb6557ed402fb5419156c815cc0b1ff1978e36d3e8c66e8a6c19bbcb87a59ef`.
Root audit SHA256 `a7757261e381cc9e0b2cb098a7c1b4566d009d31ed95395592b2b090e0c57a82`.

### 2026-10-07 — Native server fixed request diagnostic: mixed per-case latency

**Protocol:** preupgrade Rust6f498 versus current Rustdb713 on the same unchanged64 real persisted graph shards and34 requests. These are the native binaries inspected above; later root commits change JVM correctness/docs only. On macOS/M3 Max, eight fresh servers ran c1 ABBA then c4 ABBA. Each process performed one first-use cycle, two warmup cycles and30 measured cycles, with case order rotated by cycle:8,976 total requests,8,160 measured. Each request opens a connection, consumes its complete HTTP body and validates the frozen native oracle. Per-case p50/p95 use nearest ranks15/29 of30; aggregate workload distributions remain separate. Two run-level quantiles per arm do not establish a precise population tail. No sample was replaced.

**Correctness:** all8,976 complete typed bodies, native total metadata, ordered rows/columns and provenance match the preupgrade oracle. All eight native/time processes terminated by owned SIGTERM with reviewed-15 wrapper status, and clients/lifecycle processes exited0. All1216 fixture stat identities remained unchanged. Root independently reread all8,976 bodies and request hashes, recomputed per-case quantiles and paired/run-level statistics, checked lifecycle cleanup and raw time-l CPU/RSS. Two path/order assumptions in the offline root audit were corrected against the archived client source (bodies subdirectory and prespecified cycle rotation); no measured data or oracle was changed.

**All raw process resources:** wall is the entire server lifetime, including load, client validation gaps and shutdown; it is not graph-loading E2E or per-case latency. Whole CPU includes user+system; RSS is decimal GB. The measured-window CPU counter is a separate native-process counter with0.01s resolution, including client gaps, not CPU attributed to individual queries.

| Session | Lifetime wall s | Whole CPU s | Peak RSS GB | Measured-window CPU s |
|---|---:|---:|---:|---:|
| c1-0-A | 45.530 | 38.120 | 6.564413 | 2.970 |
| c1-1-B | 45.750 | 38.680 | 6.611173 | 2.960 |
| c1-2-B | 46.440 | 39.400 | 6.608962 | 2.940 |
| c1-3-A | 46.700 | 39.640 | 6.569263 | 2.950 |
| c4-0-A | 41.040 | 38.870 | 6.581649 | 2.140 |
| c4-1-B | 41.140 | 39.060 | 6.631571 | 2.140 |
| c4-2-B | 41.040 | 38.900 | 6.629229 | 2.050 |
| c4-3-A | 41.070 | 38.980 | 6.589350 | 2.150 |

**Per-case changes:** percentages compare the arithmetic mean of the two current run-level quantiles with the two old run-level quantiles. Positive means slower. Every case is shown; individual AB/BA pairs and all30 raw observations per process remain in the sealed results.

| Query case (global-wide prefix omitted) | c1 p50 | c1 p95 | c4 p50 | c4 p95 |
|---|---:|---:|---:|---:|
| four-properties-zero | +1.114% | +7.797% | +7.035% | +2.929% |
| four-properties-targeted | +1.483% | +4.205% | -0.007% | -55.954% |
| four-properties-dense | +1.840% | +8.991% | -2.363% | -35.967% |
| class-pair-zero | +1.781% | +0.043% | +0.157% | +4.650% |
| class-pair-targeted | -0.034% | -2.236% | +2.678% | +0.285% |
| class-pair-dense | +2.968% | +0.144% | -2.085% | +6.258% |
| name-pair-zero | +0.293% | +9.074% | +1.992% | -5.426% |
| name-pair-targeted | +0.146% | +4.034% | -0.152% | -4.613% |
| name-pair-dense | +0.492% | -0.212% | -2.181% | -6.148% |
| caller-class-zero | +1.636% | +5.397% | -4.791% | -4.538% |
| caller-class-targeted | +1.057% | -0.603% | -5.558% | -5.066% |
| caller-class-dense | -0.609% | +0.277% | -0.849% | -3.093% |
| callee-class-zero | +3.339% | -1.343% | -4.959% | -4.548% |
| callee-class-targeted | +3.809% | -0.641% | -0.077% | +7.338% |
| callee-class-dense | -0.189% | -1.531% | -2.350% | -0.688% |
| provenance-zero | +0.414% | +3.795% | -2.975% | +5.516% |
| provenance-targeted | -0.674% | -0.223% | -1.941% | +3.946% |
| provenance-dense | +1.290% | -4.409% | +2.620% | -5.693% |
| aliased-zero | +1.005% | +0.269% | -2.413% | -4.868% |
| aliased-targeted | -0.670% | -4.769% | -1.339% | -8.248% |
| aliased-dense | +0.873% | -2.456% | -3.420% | -14.227% |
| parameterized-zero | +2.330% | -0.367% | -1.909% | +6.119% |
| parameterized-targeted | -1.770% | -2.981% | +4.305% | +5.315% |
| parameterized-dense | -0.996% | -3.724% | -7.586% | -2.072% |
| wrapped-case-insensitive-zero | +0.902% | -8.901% | +1.104% | -10.393% |
| wrapped-case-insensitive-targeted | -0.826% | -3.790% | -1.549% | -2.056% |
| wrapped-case-insensitive-dense | +0.722% | +8.911% | -0.288% | +101.896% |
| wrapped-case-insensitive-distinct-zero | +0.694% | -5.342% | -1.098% | -3.851% |
| wrapped-case-insensitive-distinct-targeted | -3.238% | -10.283% | -5.865% | -0.178% |
| wrapped-case-insensitive-distinct-dense | -1.985% | -2.409% | -3.461% | -0.205% |
| distribution-broad-all-64 | -1.472% | -2.585% | -0.756% | -6.675% |
| distribution-localized-early | +1.330% | -0.718% | -0.449% | +6.774% |
| distribution-localized-late | -3.418% | -3.854% | -0.648% | +7.286% |
| distribution-localized-middle | -3.400% | +5.728% | +5.228% | -10.408% |

c1 has13/34 lower p50 means and21/34 lower p95 means; c4 has26/34 and22/34. Cases slower in both pairs remain: c1 p50 has12, p95 has6; c4 p50 has5, p95 has8. For example, c4 localized-late p95 is +7.286% (pairs +7.200/+7.381%), localized-early +6.774% (+7.182/+6.357%), and class-pair-dense +6.258% (+9.861/+2.640%). The largest adverse mean, c4 wrapped-case-insensitive-dense p95, rises1.686646→3.405271ms (+101.896%); its two pair changes are +218.961% and -4.064%. That disagreement is retained and does not prove a repeatable doubling or justify dropping the spike.

Whole-process CPU means change +0.412% at c1 and +0.141% at c4; peak RSS +0.658% and +0.682%. Each resource pair stays within +5% in this series. Those observed values are not final cap acceptance: the protocol was diagnostic from outset, loading/startup dominates lifetime CPU, and no per-case resource attribution is available.

**Background observations and limits:** the monitor retained346 snapshots,23 external accumulated-CPU increment events and seven positive-CPU observations. One observation was incomplete when the existing external Kotlin daemon disappeared during a native session; this is retained, not converted to zero activity. No foreign process was signalled. The series remains `DIAGNOSTIC_WITH_OBSERVED_BACKGROUND_ACTIVITY`, with observer overhead and unobserved native/I/O/subsecond activity limitations. It cannot prove isolated causal differences or final resource compliance.

**Decision:** preserve the complete native baseline, investigate the concrete adverse cases and native old/current source differences, and keep JVM optimization gains separate. Shared-fixture native response correctness now has repeated-request evidence; native latency recovery is still incomplete. This does not validate loading of separate old/current full generated graphs or new semantic features.

Command: `python3 /tmp/sootup-static-review/native-server-request-baseline/run.py --plan /tmp/sootup-static-review/native-server-request-baseline/plan.sealed.json --execute-root-released`. Exact eight argv, binaries, fixture, oracle, methods, ports and all metrics are frozen in plan SHA256 `5d0a204e1af3deed52d2594bfb5ce16a26006c0d9b843e581ca24321dc928e64`. Results `execution/results.json` SHA256 `2abea57f8a74a95c3b4c2ff6fb200574805b54ac0903256c0cf11a407f0f3a33`; summary `99520d361455b731aa45f99f1251c351bbd54ca8cb58169edfb3b11881636bd4`; independent audit `ccea243cecbd23f0679cc69b9563a46e4f40cebb8e4bb3c2cbed152d1110d5be`.

### 2026-10-07 — G1 young-generation cap diagnostic: retained mixed candidate

**Hypothesis and fixed protocol:** test whether adaptive young sizing/committed capacity contributes to the Tika RSS regression. The same pinned HotSpot17.0.20.1 binary runs the preupgrade6f498 TEST54 and current116 TEST57 runtimes on full Tika2.9.2. The sole parameter is experimental `G1MaxNewSizePercent=60` versus30; both arms explicitly select G1, unlock experimental options and retain `-Xmx8g`. Two finite flag-only JVMs verified all effective flags: only that percentage and its derived MaxNewSize differ. Both use initial1GiB/min8MiB/max8GiB,4MiB regions,13 parallel/3 concurrent/13 refinement workers and minimum young5. No Xms, forcedGC, feature reduction or pause/worker change was introduced.

The eight prespecified cells are old60/current60/current30/old30/old30/current30/current60/old60. The existing full build/count/prepared-save/close helper records250ms heap/buffer samples; an unchanged external sampler records100ms RSS and independent process time-l. Every JVM records debug GC logs; none records JFR. These instrumentation differences prohibit treating the earlier quiet or JFR pairs as matched controls. All eight builds precede24 own-version strict validators. All samples, including adverse/high-variance values, are retained.

**Correctness:** eight builds,24 strict validators and the offline phase parser all passed. Every generated graph matches its own version's complete shape/metadata/five query references; current ordinal sidecar/binding checks and old-format absence checks passed. The eight graph inventories match before/after verification. Static classfile review matched all16 direct helper member references in116; actual completion verifies the invoked ABI/default-feature assertions. Root independently reconstructed all eight time-l/phase/heap/GC metrics and compared all24 complete actual/reference reports. No cross-version graph isomorphism is claimed.

| Fixed run | Version/cap | Build→saved graph E2E s | Whole CPU s | Peak RSS GB | Sampled committed max GB | Completed GC pause total ms |
|---|---|---:|---:|---:|---:|---:|
| 0 | old/60 | 109.616094 | 207.370 | 8.892563 | 7.927235 | 1473.581 |
| 1 | current116/60 | 27.924471 | 86.560 | 8.564883 | 7.675576 | 914.876 |
| 2 | current116/30 | 28.065773 | 85.010 | 6.975029 | 6.123684 | 984.908 |
| 3 | old/30 | 110.158691 | 176.730 | 7.465091 | 6.639583 | 1811.951 |
| 4 | old/30 | 107.198059 | 175.160 | 7.203357 | 7.230980 | 1795.027 |
| 5 | current116/30 | 28.281880 | 86.040 | 6.966297 | 6.132072 | 1008.920 |
| 6 | current116/60 | 28.111146 | 86.370 | 6.799737 | 5.922357 | 969.934 |
| 7 | old/60 | 109.978637 | 179.330 | 7.326990 | 7.012876 | 1710.320 |

GB is decimal. CPU is complete process user+system. Sampled committed/used heap is neither retained live memory nor RSS; completed pause totals exclude concurrent GC work and include distinct remark/cleanup pauses sharing a GC identifier. All raw helper phases, GC events, process wall times, peaks and paired changes remain archived.

| Prespecified comparison | E2E mean change | CPU mean change | RSS mean change | RSS pair changes |
|---|---:|---:|---:|---|
| oldVsCurrent60 | -74.482% | -55.281% | -5.271% | -3.685% / -7.196% |
| oldVsCurrent30 | -74.076% | -51.391% | -4.957% | -6.565% / -3.291% |
| currentPolicyEffect | +0.557% | -1.087% | -9.263% | -18.562% / +2.450% |
| oldPolicyEffect | -1.019% | -9.002% | -9.563% | -16.052% / -1.687% |

**Interpretation:** current116 cap30 has E2E28.173827s, CPU85.525s and RSS6.970663GB means. Against old under the same30 setting, E2E is -74.076%, CPU -51.391%, RSS -4.957%; both resource pairs decrease. Against current60, E2E is +0.557%, CPU -1.087%, RSS -9.263% on means, but RSS pairs disagree (-18.562%/+2.450%). Current60 peaks are8.564883/6.799737GB, while current30 peaks are6.975029/6.966297GB. Current60's sampled committed maxima also vary strongly (7.675576/5.922357GB), so the mean alone does not prove a repeatable GC-policy benefit or causal explanation. Old60's first207.37 CPU-second/8.892563GB sample is retained. The current60-versus-old60 comparison in this instrumented series itself differs from the earlier quiet regression; it does not supersede that baseline.

**Decision:** retain cap30 as a mixed configuration candidate for matched full-workload validation, not as an adopted production default or completed recovery. Its observed Tika values justify continuing; one adverse pair does not erase the positive evidence, and a favorable mean does not establish the +5% cap. Kotlin construction and any affected launch configuration still need validation. Native Rust server behavior is independent of these JVM flags. Existing quiet preupgrade evidence and all global query/loading gaps remain authoritative.

The host observer retained1117 snapshots: construction29 external CPU-increment/14 positive-CPU events; verification19/5. No new external JVM, logged build or incomplete observation was detected. The entire protocol remains `DIAGNOSTIC_WITH_OBSERVED_BACKGROUND_ACTIVITY`, not exclusive or causal evidence. Subsecond/native/I/O activity remains unobserved; the final subsecond construction tail can be classified under verification at the phase switch. All owned JVMs and observers ended; foreign processes were untouched.

Command: `env -u MallocNanoZone python3 /tmp/sootup-static-review/tika-g1-young-cap/execute.py --execute-root-released`. Sealed packet SHA256 `d318aa9ffc1078ab9604d60b75e02ce82e7e969ee6bc4f1be06c312c550c89bd`; result `654414829fc7fab5d342182f4cb6e102e7f79ad451c73021f690896eca1c66a8`; independent audit `a784fd853ebd1d3af6039b70db7a67e10e640b4baf886913c0b36824887434f9`. Exact34 total JVM argv (two flag preflights plus8+24), source/runtime/input/reference identities, all bodies/reports, heap/RSS/GC traces and failure handling are preserved under that directory.

### 2026-10-07 — Native own-version full-graph compatibility: four artifacts, twelve complete responses

**Scope and identity:** four fresh native sessions loaded complete Kotlin and Tika graphs produced by their own construction versions: preupgrade6f498 and current116. The reader binaries remain frozen native6f498/db713, not a fresh root HEAD or JVM server. Artifacts are the first successful outputs in the original fixed construction order, not selected by performance: old `kotlin-0-A`/`tika-0-A` from cumulative341-old construction, current `kotlin-1-B`/`tika-1-B` from116 construction. Each session loads one graph as `own`, using MAPPED mode. This establishes bounded own-format compatibility; old/current counts differ and graph isomorphism is not claimed.

| Artifact | Nodes | Edges | Methods | CallSites | Complete query responses |
|---|---:|---:|---:|---:|---:|
| kotlin-old | 4,657,648 | 6,406,031 | 249,669 | 2,173,010 | 3 |
| kotlin-current | 4,744,132 | 6,557,841 | 249,669 | 2,251,811 | 3 |
| tika-old | 4,673,289 | 5,911,785 | 312,788 | 1,758,353 | 3 |
| tika-current | 4,620,490 | 5,786,368 | 312,788 | 1,705,428 | 3 |

**Verification:** all four complete `/api/graphs` envelopes and twelve HTTP200 query bodies passed independent root comparison. Each graph reused its own existing JVM full-content references: ordered CallSite two-property LIMIT20, ordered IntConstant LIMIT20, and the complete99-row `nodeMatchWithWhere` result. Ordered arrays retain order and duplicate multiplicity; the unordered, unlimited99-row result is compared as a typed multiset. Every row carries exactly `$metadata:{graphIds:["own"]}`; columns, values, integer/string types, graphCount and native total metadata remain checked. Native totals are source-derived21/gte for both ordered LIMIT20 queries and99/eq for the complete WHERE result, not copied from a JVM envelope that lacks total.

Two other available references—unordered `simpleNodeMatch LIMIT100` and `singleHopRelationship LIMIT50`—were explicitly not issued: their JVM prefixes cannot determine the native implementation's permitted limited subset. They were not replaced by row-count-only checks. The successful three-query subset does not establish all query semantics, C4 background completion, loading time, CPU/RSS recovery or native p50/p95. Resource receipts include correctness queries and shutdown and are not loading-performance samples.

**Environment and completion:** macOS/M3 Max, existing Rust1.93 arm64 release binaries and locked dependencies; Python3.14.7 controller. Ports18880–18883 were used serially. GRAPHITE_NO_FASTPATH, RAYON_NUM_THREADS and RAYON_RS_NUM_CPUS were absent, including empty-value overrides, and injected DYLD overrides were rejected. Native C4/default features were retained. Readiness includes complete own-version node/edge/method/CallSite totals but does not wait for all background/lazy query work. All owned servers were stopped/reaped with reviewed SIGTERM/time-wrapper−15; no natural exit, retry or replacement was accepted. Existing graph-content receipts plus exact inventory/stat continuity were verified without repeating bulk graph hashing. The diagnostic monitor saw one external CPU increment and one positive-CPU observation during sessions; that is not proof of an exclusive host.

Command: `python3 /tmp/sootup-static-review/native-own-graph-loading/compatibility/run.py --plan /tmp/sootup-static-review/native-own-graph-loading/compatibility/plan.sealed.json --execute-root-released`. Literal per-session argv, source/binary/graph/report identities, expected envelopes and all raw bodies remain in that packet. Old binary SHA256 `2bf3cd50096fe3d39ad9227cc1aade64bb4ac8014486c10b0d4b57f48a57700b`; current binary `d5f3a9c1b2744391fef90963ac4f27ccc848421c1378f7c2526ba056f128e770`.

Sealed plan SHA256 `f7b582d65d3e005d7db7f32130c6d1e34be103d63ed452b967cf2a675aa6c8ac`; result `67285110b4f15bf7076ad5c86a20ead25beb26505bbb6a9456546ca0c57283aa`; independent audit `6181aaa5e890f96ad4cd802c48b4cd840b1f06a99b09008829afa0b62215bb49`. Evidence: `/tmp/sootup-static-review/native-own-graph-loading/compatibility/{execution/results.json,independent-audit/audit.json}`. **Decision:** preserve this compatibility pass and its explicit two-query gap; proceed separately with bounded loading measurement, without treating these correctness lifetimes as a performance baseline.
