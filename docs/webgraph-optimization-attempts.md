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
