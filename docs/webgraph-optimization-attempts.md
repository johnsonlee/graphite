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
