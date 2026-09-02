package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SerialGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyLookup
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookupStrategy
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionProjection
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.TransformedStringPropertyLookup
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionAggregation
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionLookup
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
class CrossGraphCypherExecutorTest {

    @Test
    fun `direct string override shares one additive NCPU budget`() {
        val balanced = resolveDirectStringParallelismPlan(16, null)
        assertEquals(8, balanced.graphWorkerCount)
        assertEquals(8, balanced.segmentWorkerCount)

        val allGraphWorkers = resolveDirectStringParallelismPlan(16, "16")
        assertEquals(16, allGraphWorkers.graphWorkerCount)
        assertEquals(0, allGraphWorkers.segmentWorkerCount)

        val bounded = resolveDirectStringParallelismPlan(7, "99")
        assertEquals(7, bounded.graphWorkerCount)
        assertEquals(0, bounded.segmentWorkerCount)

        assertEquals(4, resolveDirectStringGraphParallelism(4, 16, null))
        assertEquals(8, resolveDirectStringGraphParallelism(36, 16, null))
        assertEquals(4, resolveDirectStringGraphParallelism(36, 4, null))
        assertEquals(8, resolveDirectStringGraphParallelism(42, 16, null))
        assertEquals(2, resolveDirectStringGraphParallelism(42, 4, null))
        assertEquals(8, resolveDirectStringGraphParallelism(64, 16, null))
        assertEquals(16, resolveDirectStringGraphParallelism(64, 16, "16"))

        assertEquals(4, resolveDirectStringExecutorParallelism(4, null))
        assertEquals(8, resolveDirectStringExecutorParallelism(16, null))
        assertEquals(16, resolveDirectStringExecutorParallelism(16, "16"))

        val overriddenStorage = directStringStorageWorkConsumer(
            sourceCount = 64,
            processors = 16,
            configuredGraphWorkers = "12"
        ) as SplitGraphWorkBatchConsumer
        assertEquals(4, overriddenStorage.segmentWorkerCount)
    }

    @Test
    fun `legacy wide query executor runs the full selected graph worker wave`() {
        val graphCount = 36
        val plannedWorkers = resolveDirectStringGraphParallelism(graphCount)
        if (plannedWorkers < 2) return
        val firstWaveEntered = CountDownLatch(plannedWorkers)
        val workerThreads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val empty = graph()
        val graphs = List(graphCount) { graphIndex ->
            CypherGraph("graph-$graphIndex", object : Graph by empty, WorkAwareStringPropertyDisjunctionLookup {
                override fun nodeCount(type: Class<out Node>): Long? =
                    if (type == CallSiteNode::class.java) 10_000L else empty.nodeCount(type)

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> = emptySequence()

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int,
                    workConsumer: GraphWorkConsumer
                ): Sequence<T> {
                    if (graphIndex < plannedWorkers && workerThreads.add(Thread.currentThread().name)) {
                        firstWaveEntered.countDown()
                    }
                    if (graphIndex < plannedWorkers) {
                        check(firstWaveEntered.await(5, TimeUnit.SECONDS)) {
                            "planned $plannedWorkers graph workers but only ${workerThreads.size} entered"
                        }
                    }
                    return emptySequence()
                }
            })
        }

        val result = CrossGraphCypherExecutor(graphs).execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'absent' " +
                "RETURN n.caller_class LIMIT 1"
        )

        assertTrue(result.rows.isEmpty())
        assertEquals(plannedWorkers, workerThreads.size)
        assertTrue(workerThreads.all { thread -> thread.startsWith("graphite-cypher-scan-") })
    }

    @Test
    fun `graph id set keeps storage split within the pruned source budget`() {
        val selectedCount = 8
        val activeGraphWorkers = AtomicInteger()
        val peakGraphWorkers = AtomicInteger()
        val storageConsumers = java.util.concurrent.ConcurrentHashMap<Int, GraphWorkConsumer>()
        val empty = graph()
        val graphs = List(64) { graphIndex ->
            CypherGraph("graph-$graphIndex", object : Graph by empty, WorkAwareStringPropertyDisjunctionLookup {
                override fun nodeCount(type: Class<out Node>): Long? =
                    if (type == CallSiteNode::class.java) 10_000L else empty.nodeCount(type)

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> = emptySequence()

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int,
                    workConsumer: GraphWorkConsumer
                ): Sequence<T> {
                    storageConsumers[graphIndex] = workConsumer
                    val active = activeGraphWorkers.incrementAndGet()
                    peakGraphWorkers.accumulateAndGet(active, ::maxOf)
                    try {
                        workConsumer.consume()
                        return emptySequence()
                    } finally {
                        activeGraphWorkers.decrementAndGet()
                    }
                }
            })
        }
        val selectedGraphIds = List(selectedCount) { index -> "graph-$index" }

        val result = CrossGraphCypherExecutor(
            graphs,
            CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = 100_000))
        ).execute(
            "MATCH (n:CallSiteNode) WHERE n.graphId IN \$graphIds AND (" +
                "toLower(coalesce(n.caller_class, '')) CONTAINS 'absent' OR " +
                "toLower(coalesce(n.callee_class, '')) CONTAINS 'absent') " +
                "RETURN n.caller_class LIMIT 1",
            mapOf("graphIds" to selectedGraphIds)
        )

        assertTrue(result.rows.isEmpty())
        assertEquals(selectedGraphIds.indices.toSet(), storageConsumers.keys)
        assertTrue(storageConsumers.values.none { consumer -> consumer is ParallelGraphWorkBatchConsumer })
        // Pruning 64 sources to eight must retain the small-source compatibility path: one
        // graph lookup at a time and no nested storage split. Passing the unpruned source count
        // here would incorrectly select the 64-source balanced plan.
        assertEquals(1, peakGraphWorkers.get())
        assertEquals(0, activeGraphWorkers.get())
    }

    @Test
    fun `work tracked global wide query probes the leading graph then executes the balanced plan`() {
        val plan = resolveDirectStringParallelismPlan()
        if (plan.graphWorkerCount < 2) return
        val graphCount = 40
        val firstWaveEntered = CountDownLatch(plan.graphWorkerCount)
        val firstWaveSegments = java.util.concurrent.ConcurrentHashMap<Int, Pair<String, Int>>()
        val leadingSerial = AtomicInteger()
        val empty = graph()
        val graphs = List(graphCount) { graphIndex ->
            CypherGraph("graph-$graphIndex", object : Graph by empty, WorkAwareStringPropertyDisjunctionLookup {
                override fun nodeCount(type: Class<out Node>): Long? =
                    if (type == CallSiteNode::class.java) 10_000L else empty.nodeCount(type)

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> = emptySequence()

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int,
                    workConsumer: GraphWorkConsumer
                ): Sequence<T> {
                    if (graphIndex == 0) {
                        if (workConsumer is SerialGraphWorkBatchConsumer) leadingSerial.incrementAndGet()
                        return emptySequence()
                    }
                    val split = workConsumer as SplitGraphWorkBatchConsumer
                    if (graphIndex in 1..plan.graphWorkerCount) {
                        if (firstWaveSegments.putIfAbsent(
                                graphIndex,
                                Thread.currentThread().name to split.segmentWorkerCount
                            ) == null
                        ) {
                            firstWaveEntered.countDown()
                        }
                        check(firstWaveEntered.await(5, TimeUnit.SECONDS))
                    }
                    workConsumer.consume()
                    return emptySequence()
                }
            })
        }
        val result = CrossGraphCypherExecutor(
            graphs,
            CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = 100_000))
        ).execute(
            "MATCH (n:CallSiteNode) WHERE " +
                "toLower(coalesce(n.caller_class, '')) CONTAINS 'absent' OR " +
                "toLower(coalesce(n.callee_class, '')) CONTAINS 'absent' " +
                "RETURN n.caller_class LIMIT 1"
        )

        assertTrue(result.rows.isEmpty())
        assertEquals(1, leadingSerial.get())
        assertEquals(plan.graphWorkerCount, firstWaveSegments.size)
        assertEquals(plan.graphWorkerCount, firstWaveSegments.values.map { it.first }.toSet().size)
        assertTrue(firstWaveSegments.values.all { it.second == plan.segmentWorkerCount })
    }

    @Test
    fun `balanced row scan does not wait for a whole graph wave before scheduling later sources`() {
        val plan = resolveDirectStringParallelismPlan()
        if (plan.graphWorkerCount < 2) return
        val graphCount = 40
        val laterSourceEntered = CountDownLatch(1)
        val laterSourceIndex = plan.graphWorkerCount + 1
        val empty = graph()
        val graphs = List(graphCount) { graphIndex ->
            CypherGraph("graph-$graphIndex", object : Graph by empty, WorkAwareStringPropertyDisjunctionLookup {
                override fun nodeCount(type: Class<out Node>): Long? =
                    if (type == CallSiteNode::class.java) 10_000L else empty.nodeCount(type)

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> = emptySequence()

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int,
                    workConsumer: GraphWorkConsumer
                ): Sequence<T> {
                    when (graphIndex) {
                        plan.graphWorkerCount -> check(laterSourceEntered.await(5, TimeUnit.SECONDS)) {
                            "later source was held behind the first graph-wave barrier"
                        }
                        laterSourceIndex -> laterSourceEntered.countDown()
                    }
                    return emptySequence()
                }
            })
        }

        val result = CrossGraphCypherExecutor(graphs).execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'absent' " +
                "RETURN n.caller_class LIMIT 1"
        )

        assertTrue(result.rows.isEmpty())
        assertEquals(0, laterSourceEntered.count)
    }

    @Test
    fun `balanced row scan stops before an unneeded distant source`() {
        val plan = resolveDirectStringParallelismPlan()
        if (plan.graphWorkerCount < 2) return
        val graphCount = 40
        val lastGraphLookups = AtomicInteger()
        val returnType = TypeDescriptor("void")
        val empty = graph()
        val graphs = List(graphCount) { graphIndex ->
            val backing = if (graphIndex == 1) {
                DefaultGraph.Builder().apply {
                    repeat(2) { nodeIndex ->
                        addNode(
                            CallSiteNode(
                                NodeId(nodeIndex),
                                MethodDescriptor(
                                    TypeDescriptor("example.Target$nodeIndex"),
                                    "call",
                                    emptyList(),
                                    returnType
                                ),
                                MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                                nodeIndex,
                                null,
                                emptyList()
                            )
                        )
                    }
                }.build()
            } else {
                empty
            }
            CypherGraph("graph-$graphIndex", object : Graph by backing, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    check(graphIndex != graphCount - 1) {
                        lastGraphLookups.incrementAndGet()
                        "LIMIT should have stopped before the last graph"
                    }
                    @Suppress("UNCHECKED_CAST")
                    return backing.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>
                }
            })
        }

        val result = CrossGraphCypherExecutor(graphs).execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' " +
                "RETURN n.caller_class LIMIT 2"
        )

        assertEquals(listOf("example.Target0", "example.Target1"), result.rows.map { it["n.caller_class"] })
        assertEquals(0, lastGraphLookups.get())
    }

    @Test
    fun `global wide row limit does not initialize speculative later graph scans`() {
        val returnType = TypeDescriptor("void")
        val lookups = List(40) { AtomicInteger() }
        val graphs = List(40) { graphIndex ->
            val backing = DefaultGraph.Builder().apply {
                if (graphIndex == 0) {
                    repeat(250) { nodeIndex ->
                        addNode(
                            CallSiteNode(
                                NodeId(nodeIndex),
                                MethodDescriptor(
                                    TypeDescriptor("example.Target$nodeIndex"),
                                    "call",
                                    emptyList(),
                                    returnType
                                ),
                                MethodDescriptor(
                                    TypeDescriptor("example.Repository"),
                                    "load",
                                    emptyList(),
                                    returnType
                                ),
                                nodeIndex,
                                null,
                                emptyList()
                            )
                        )
                    }
                }
            }.build()
            CypherGraph("graph-$graphIndex", object : Graph by backing, WorkAwareStringPropertyDisjunctionLookup {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> = nodesByStringPropertyDisjunction(type, predicates, limit, GraphWorkConsumer {})

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int,
                    workConsumer: GraphWorkConsumer
                ): Sequence<T> {
                    lookups[graphIndex].incrementAndGet()
                    if (type != CallSiteNode::class.java) return emptySequence()
                    @Suppress("UNCHECKED_CAST")
                    return backing.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>
                }
            })
        }

        val result = CrossGraphCypherExecutor(graphs).execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' " +
                "RETURN n.caller_class AS caller LIMIT 200"
        )

        assertEquals(200, result.rows.size)
        assertTrue(result.rows.all { (it["caller"] as String).startsWith("example.Target") })
        assertEquals(1, lookups.first().get())
        assertTrue(lookups.drop(1).all { it.get() == 0 })
    }

    @Test
    fun `selected graph projects bounded CallSite strings without materializing nodes`() {
        val projectedRows = listOf(
            StringPropertyProjectionRow(listOf("example.FeatureCaller", "invoke"))
        )
        var projectedType: Class<out Node>? = null
        var projectedPredicates = emptyList<StringPropertyPredicate>()
        var capturedProjectedProperties = emptyList<String>()
        var projectedLimit = -1
        var projectedWorkConsumer: GraphWorkConsumer? = GraphWorkConsumer { }
        val backing = graph()
        val selected = object : Graph by backing, StringPropertyDisjunctionProjection {
            override fun projectStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>,
                limit: Int,
                workConsumer: GraphWorkConsumer?
            ): List<StringPropertyProjectionRow> {
                projectedType = type
                projectedPredicates = predicates
                capturedProjectedProperties = projectedProperties
                projectedLimit = limit
                projectedWorkConsumer = workConsumer
                return projectedRows
            }
        }
        val result = executor("selected" to selected, "other" to graph()).execute(
            "MATCH (n:CallSiteNode) WHERE n.graphId = 'selected' AND " +
                "toLower(n.caller_class) CONTAINS 'feature' " +
                "RETURN n.caller_class AS caller, n.callee_name AS callee LIMIT 200"
        )

        assertEquals(CallSiteNode::class.java, projectedType)
        assertEquals(
            listOf(
                StringPropertyPredicate(
                    "caller_class",
                    StringValueTransform.LOWERCASE,
                    StringMatchMode.CONTAINS,
                    "feature"
                )
            ),
            projectedPredicates
        )
        assertEquals(listOf("caller_class", "callee_name"), capturedProjectedProperties)
        assertEquals(200, projectedLimit)
        assertNull(projectedWorkConsumer)
        assertEquals("example.FeatureCaller", result.rows.single()["caller"])
        assertEquals("invoke", result.rows.single()["callee"])
        assertEquals(listOf("selected"), graphIds(result.rows.single()))
    }

    @Test
    fun `ordered distinct graph id limit reads the source catalog instead of scanning nodes`() {
        val nodeScans = AtomicInteger()
        fun catalogGraph(populated: Boolean): Graph {
            val delegate = if (populated) graph(IntConstant(NodeId(1), 1)) else graph()
            return object : Graph by delegate {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
                    nodeScans.incrementAndGet()
                    error("catalog graph nodes must not be scanned")
                }
            }
        }
        val populatedIds = (0 until 125).map { index -> "graph-${index.toString().padStart(3, '0')}" }
        val sources = populatedIds.map { id -> CypherGraph(id, catalogGraph(populated = true)) } +
            CypherGraph("graph-050-empty", catalogGraph(populated = false))
        val executor = CrossGraphCypherExecutor(sources.reversed())

        val result = executor.execute(
            "MATCH (n) RETURN DISTINCT n.graphId AS graphId ORDER BY graphId LIMIT 100"
        )
        val descending = executor.execute(
            "MATCH (n) RETURN DISTINCT n.graphId AS id ORDER BY id DESC LIMIT 3"
        )
        val zero = executor.execute(
            "MATCH (n) RETURN DISTINCT n.graphId AS graphId ORDER BY graphId LIMIT 0"
        )
        val noSources = CrossGraphCypherExecutor(emptyList()).execute(
            "MATCH (n) RETURN DISTINCT n.graphId AS graphId ORDER BY graphId LIMIT 100"
        )
        val externallyBounded = executor.execute(
            "MATCH (n) RETURN DISTINCT n.graphId AS graphId ORDER BY graphId LIMIT 100",
            maxRows = 1
        )
        val singleGraph = CypherExecutor(graph(IntConstant(NodeId(1), 1))).execute(
            "MATCH (n) RETURN DISTINCT n.graphId AS graphId ORDER BY graphId LIMIT 100"
        )

        assertEquals(listOf("graphId"), result.columns)
        assertEquals(populatedIds.sorted().take(100), result.rows.map { it["graphId"] })
        assertEquals(populatedIds.sortedDescending().take(3), descending.rows.map { it["id"] })
        assertTrue(zero.rows.isEmpty())
        assertEquals(listOf("graphId"), noSources.columns)
        assertTrue(noSources.rows.isEmpty())
        assertEquals(listOf(populatedIds.min()), externallyBounded.rows.map { it["graphId"] })
        assertEquals(listOf(null), singleGraph.rows.map { it["graphId"] })
        assertTrue(result.rows.all { row -> graphIds(row) == listOf(row["graphId"]) })
        assertEquals(0, nodeScans.get())
    }

    @Test
    fun `ordered distinct graph id limit probes one node when a source has no count`() {
        val inspectedNodes = AtomicInteger()
        fun uncountedGraph(populated: Boolean): Graph {
            val delegate = if (populated) {
                graph(IntConstant(NodeId(1), 1), IntConstant(NodeId(2), 2))
            } else {
                graph()
            }
            return object : Graph by delegate {
                override fun nodeCount(type: Class<out Node>): Long? = null

                override fun <T : Node> nodes(type: Class<T>): Sequence<T> = sequence {
                    val first = delegate.nodes(type).firstOrNull() ?: return@sequence
                    inspectedNodes.incrementAndGet()
                    yield(first)
                    error("catalog fallback must stop after the first node")
                }
            }
        }
        val executor = CrossGraphCypherExecutor(
            listOf(
                CypherGraph("populated", uncountedGraph(populated = true)),
                CypherGraph("empty", uncountedGraph(populated = false))
            )
        )

        val result = executor.execute(
            "MATCH (n) RETURN DISTINCT n.graphId AS graphId ORDER BY graphId LIMIT 100"
        )

        assertEquals(listOf("populated"), result.rows.map { it["graphId"] })
        assertEquals(1, inspectedNodes.get())
        assertEquals(listOf("populated"), graphIds(result.rows.single()))
    }

    @Test
    fun `unaliased graph id ordering preserves generic execution semantics`() {
        val executor = CrossGraphCypherExecutor(
            listOf("空", "Ω", "graph-2", "graph-10", "a:colon", "z").map { id ->
                CypherGraph(id, graph(IntConstant(NodeId(1), 1)))
            }
        )

        val result = executor.execute(
            "MATCH (n) RETURN DISTINCT n.graphId ORDER BY n.graphId LIMIT 1"
        )
        val generic = executor.execute(
            "MATCH (n) RETURN DISTINCT n.graphId ORDER BY n.graphId SKIP 0 LIMIT 1"
        )

        assertEquals(generic.columns, result.columns)
        assertEquals(generic.rows, result.rows)
        assertEquals(listOf("空"), result.rows.map { it["n.graphId"] })
    }

    @Test
    fun `relationship uniqueness is qualified by graph identity`() {
        fun dataFlowGraph(): Graph {
            val first = IntConstant(NodeId(1), 10)
            val second = IntConstant(NodeId(2), 20)
            return DefaultGraph.Builder()
                .addNode(first)
                .addNode(second)
                .addEdge(DataFlowEdge(first.id, second.id, DataFlowKind.ASSIGN))
                .build()
        }
        val executor = executor("orders" to dataFlowGraph(), "billing" to dataFlowGraph())

        val result = executor.execute(
            "MATCH (a)-[r1:DATAFLOW]->(b), (c)-[r2:DATAFLOW]->(d) " +
                "RETURN graphId(a) AS firstGraph, graphId(c) AS secondGraph " +
                "ORDER BY firstGraph, secondGraph"
        )

        assertEquals(
            listOf("billing" to "orders", "orders" to "billing"),
            result.rows.map { it["firstGraph"] to it["secondGraph"] }
        )
    }

    @Test
    fun `Method source preserves graph identity for equal indexed methods`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Shared"),
            "load",
            emptyList(),
            TypeDescriptor("void")
        )
        val lookupCounts = mapOf("orders" to AtomicInteger(), "billing" to AtomicInteger())
        fun methodGraph(graphId: String): Graph {
            val backing = DefaultGraph.Builder().apply { addMethod(method) }.build()
            return object : Graph by backing {
                override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> {
                    lookupCounts.getValue(graphId).incrementAndGet()
                    return backing.methods(pattern)
                }

                override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor>? {
                    lookupCounts.getValue(graphId).incrementAndGet()
                    return backing.methodSlice(pattern, limit)
                }
            }
        }
        val executor = executor("orders" to methodGraph("orders"), "billing" to methodGraph("billing"))

        val result = executor.execute(
            "MATCH (m:Method) WHERE m.signature = '${method.signature}' " +
                "RETURN graphId(m) AS graph, m.signature AS signature LIMIT 10"
        )

        assertEquals(listOf("orders", "billing"), result.rows.map { it["graph"] })
        assertTrue(result.rows.all { it["signature"] == method.signature })
        assertEquals(listOf(listOf("orders"), listOf("billing")), result.rows.map(::graphIds))

        val graphPredicates = listOf(
            "m.graphId = 'billing'" to emptyMap(),
            "graphId(m) = 'billing'" to emptyMap(),
            "m.graphId = \$graph" to mapOf("graph" to "billing")
        )
        for ((graphPredicate, parameters) in graphPredicates) {
            lookupCounts.values.forEach { count -> count.set(0) }
            val selected = executor.execute(
                "MATCH (m:Method) WHERE $graphPredicate AND m.signature = '${method.signature}' " +
                    "RETURN graphId(m) AS graph, m.signature AS signature LIMIT 10",
                parameters
            )
            assertEquals(listOf("billing"), selected.rows.map { it["graph"] })
            assertEquals(listOf(method.signature), selected.rows.map { it["signature"] })
            assertEquals(0, lookupCounts.getValue("orders").get())
            assertTrue(lookupCounts.getValue("billing").get() > 0)
        }
    }

    @Test
    fun `Method discovery stays correct across 4 17 and 36 graphs with a tiny graph budget`() {
        for (graphCount in listOf(4, 17, 36)) {
            val graphs = (0 until graphCount).map { index ->
                val graph = DefaultGraph.Builder().apply {
                    addMethod(
                        MethodDescriptor(
                            TypeDescriptor("com.example.Shared"),
                            "early",
                            emptyList(),
                            TypeDescriptor("void")
                        )
                    )
                    addMethod(
                        MethodDescriptor(
                            TypeDescriptor("com.example.Service$index"),
                            "late$index",
                            emptyList(),
                            TypeDescriptor("void")
                        )
                    )
                }.build()
                CypherGraph("graph-$index", graph)
            }
            val executor = CrossGraphCypherExecutor(graphs, CypherExecutionBudget(maxWorkUnits = 1))

            assertTrue(
                executor.execute(
                    "MATCH (m:Method) WHERE m.name = 'missing' RETURN m.signature LIMIT 1"
                ).rows.isEmpty()
            )
            assertEquals(
                "graph-0",
                executor.execute(
                    "MATCH (m:Method) WHERE m.name = 'early' RETURN graphId(m) AS graph LIMIT 1"
                ).rows.single()["graph"]
            )
            assertEquals(
                "graph-${graphCount - 1}",
                executor.execute(
                    "MATCH (m:Method) WHERE m.name = 'late${graphCount - 1}' " +
                        "RETURN graphId(m) AS graph LIMIT 1"
                ).rows.single()["graph"]
            )
            assertTrue(
                executor.execute(
                    "MATCH (m:Method) WHERE m.name = 'missing-a' OR m.name = 'missing-b' OR " +
                        "m.name = 'missing-c' RETURN m.signature LIMIT 1"
                ).rows.isEmpty()
            )
            assertEquals(
                graphCount * 2L,
                executor.execute("MATCH (m:Method) RETURN count(m) AS total").rows.single()["total"]
            )
        }
    }

    @Test
    fun `Method count provenance excludes empty graphs`() {
        val populated = DefaultGraph.Builder().apply {
            addMethod(
                MethodDescriptor(
                    TypeDescriptor("com.example.Populated"),
                    "load",
                    emptyList(),
                    TypeDescriptor("void")
                )
            )
        }.build()
        val executor = executor(
            "empty-before" to graph(),
            "populated" to populated,
            "empty-after" to graph()
        )

        for (query in listOf(
            "MATCH (m:Method) RETURN count(m) AS total",
            "MATCH (m:Method) RETURN count(*) AS total"
        )) {
            val row = executor.execute(query).rows.single()

            assertEquals(1L, row["total"])
            assertEquals(listOf("populated"), graphIds(row))
        }
    }

    @Test
    fun `Method count over all empty graphs has empty provenance`() {
        val executor = executor("empty-a" to graph(), "empty-b" to graph())

        for (query in listOf(
            "MATCH (m:Method) RETURN count(m) AS total",
            "MATCH (m:Method) RETURN count(*) AS total"
        )) {
            val row = executor.execute(query).rows.single()

            assertEquals(0L, row["total"])
            assertEquals(emptyList(), graphIds(row))
        }
    }

    @Test
    fun `shared execution context cancellation stops a cross graph scan`() {
        val cancellation = CypherCancellationSignal()
        val context = CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = 10), cancellation)
        cancellation.cancel()

        assertFailsWith<CypherQueryCancelledException> {
            CrossGraphCypherExecutor(
                listOf(CypherGraph("service", graph(IntConstant(NodeId(1), 10)))),
                context
            ).execute("MATCH (n) RETURN n.id LIMIT 1")
        }
    }

    @Test
    fun `retains JVM one argument constructor`() {
        val constructor = assertNotNull(
            CrossGraphCypherExecutor::class.java.getConstructor(List::class.java)
        )
        val executor = constructor.newInstance(
            listOf(CypherGraph("orders", graph(IntConstant(NodeId(1), 10))))
        ) as CrossGraphCypherExecutor

        assertEquals(10, executor.execute("MATCH (n:IntConstant) RETURN n.value").rows.single()["n.value"])
    }

    @Test
    fun `retains QueryPipeline JVM one argument list constructor`() {
        val constructor = assertNotNull(QueryPipeline::class.java.getConstructor(List::class.java))
        val pipeline = constructor.newInstance(
            listOf(CypherGraph("orders", graph(IntConstant(NodeId(1), 10))))
        ) as QueryPipeline

        val result = pipeline.execute(CypherDslAdapter.parse("MATCH (n:IntConstant) RETURN n.value"))
        assertEquals(10, result.rows.single()["n.value"])
    }

    @Test
    fun `execution budget charges qualified element id seeks across union segments`() {
        val executor = CrossGraphCypherExecutor(
            listOf(CypherGraph("orders", graph(IntConstant(NodeId(1), 10)))),
            CypherExecutionBudget(maxWorkUnits = 1)
        )

        assertFailsWith<CypherBudgetExceededException> {
            executor.execute(
                "MATCH (n) WHERE elementId(n) = 'orders:1' RETURN n.id AS id " +
                    "UNION ALL MATCH (n) WHERE elementId(n) = 'orders:1' RETURN n.id AS id"
            )
        }
    }

    @Test
    fun `qualifies colliding local node ids and records row provenance`() {
        val executor = executor(
            "orders" to graph(IntConstant(NodeId(1), 10)),
            "billing" to graph(IntConstant(NodeId(1), 20))
        )

        val result = executor.execute(
            "MATCH (n:IntConstant) RETURN n ORDER BY n.graphId"
        )

        assertEquals(2, result.rows.size)
        assertEquals(
            listOf("billing:1", "orders:1"),
            result.rows.map { (it["n"] as Map<*, *>)["elementId"] }
        )
        assertEquals(
            listOf(listOf("billing"), listOf("orders")),
            result.rows.map(::graphIds)
        )
        assertTrue(result.rows.all { "_graphIds" !in it })
    }

    @Test
    fun `joins independent patterns across graph boundaries`() {
        val executor = executor(
            "orders" to graph(StringConstant(NodeId(1), "shared")),
            "billing" to graph(StringConstant(NodeId(1), "shared"))
        )

        val result = executor.execute(
            """
            MATCH (a:StringConstant), (b:StringConstant)
            WHERE a.value = b.value AND a.graphId < b.graphId
            RETURN graphId(a) AS leftGraph, graphId(b) AS rightGraph, a.value AS value
            """.trimIndent()
        )

        assertEquals(
            listOf(
                mapOf(
                    "leftGraph" to "billing",
                    "rightGraph" to "orders",
                    "value" to "shared",
                    RESULT_METADATA_KEY to mapOf(
                        RESULT_GRAPH_IDS_KEY to listOf("billing", "orders")
                    )
                )
            ),
            result.rows
        )
    }

    @Test
    fun `relationship traversal stays inside the owning graph`() {
        val orders = DefaultGraph.Builder()
            .addNode(IntConstant(NodeId(1), 10))
            .addNode(IntConstant(NodeId(2), 20))
            .addEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.ASSIGN))
            .build()
        val billing = graph(
            IntConstant(NodeId(1), 30),
            IntConstant(NodeId(2), 40)
        )
        val executor = executor("orders" to orders, "billing" to billing)

        val result = executor.execute(
            "MATCH (a:IntConstant)-[:DATAFLOW]->(b:IntConstant) " +
                "RETURN graphId(a) AS graph, a.value AS source, b.value AS target"
        )

        assertEquals(1, result.rows.size)
        assertEquals("orders", result.rows.single()["graph"])
        assertEquals(10, result.rows.single()["source"])
        assertEquals(20, result.rows.single()["target"])
        assertEquals(listOf("orders"), graphIds(result.rows.single()))
    }

    @Test
    fun `filtered relationship limit scopes an exact graph id before traversal`() {
        val orders = DefaultGraph.Builder()
            .addNode(IntConstant(NodeId(1), 10))
            .addNode(IntConstant(NodeId(2), 20))
            .addEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.ASSIGN))
            .build()
        val billing = graph(IntConstant(NodeId(1), 30))
        val unreadableBilling = object : Graph by billing {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                error("Graph-id equality must prune unrelated graphs")
        }
        val executor = executor("billing" to unreadableBilling, "orders" to orders)

        val result = executor.execute(
            "MATCH (c)-[r:DATAFLOW]->(n) " +
                "WHERE c.graphId = 'orders' AND c.value = 10 " +
                "RETURN c.graphId AS graph, c.value AS source, n.value AS target, r.kind AS kind LIMIT 1"
        )

        assertEquals("orders", result.rows.single()["graph"])
        assertEquals(10, result.rows.single()["source"])
        assertEquals(20, result.rows.single()["target"])
        assertEquals("ASSIGN", result.rows.single()["kind"])
        assertEquals(listOf("orders"), graphIds(result.rows.single()))
    }

    @Test
    fun `filtered relationship graph id pruning recognizes equivalent predicates`() {
        val orders = DefaultGraph.Builder()
            .addNode(IntConstant(NodeId(1), 10))
            .addNode(IntConstant(NodeId(2), 20))
            .addEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.ASSIGN))
            .build()
        val executor = executor("billing" to graph(IntConstant(NodeId(1), 30)), "orders" to orders)
        val predicates = listOf(
            "'orders' = c.graphId",
            "graphId(c) = 'orders'",
            "c.graphId <> 'billing'",
            "true"
        )

        for (predicate in predicates) {
            val result = executor.execute(
                "MATCH (c)-[:DATAFLOW]->(n) WHERE $predicate " +
                    "RETURN c.graphId AS graph, n.value AS value LIMIT 1"
            )

            assertEquals("orders", result.rows.single()["graph"])
            assertEquals(20, result.rows.single()["value"])
        }
    }

    @Test
    fun `filtered distinct relationship limit merges graph provenance`() {
        fun dataFlowGraph(): Graph = DefaultGraph.Builder()
            .addNode(IntConstant(NodeId(1), 10))
            .addNode(IntConstant(NodeId(2), 20))
            .addEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.ASSIGN))
            .build()
        val executor = executor("billing" to dataFlowGraph(), "orders" to dataFlowGraph())

        val result = executor.execute(
            "MATCH (c)-[:DATAFLOW]->(n) WHERE n.value = 20 " +
                "RETURN DISTINCT n.value AS value LIMIT 1"
        )

        assertEquals(20, result.rows.single()["value"])
        assertEquals(listOf("billing", "orders"), graphIds(result.rows.single()).sorted())
    }

    @Test
    fun `aggregates once across all selected graphs and retains contributors`() {
        val executor = executor(
            "orders" to graph(IntConstant(NodeId(1), 10), IntConstant(NodeId(2), 20)),
            "billing" to graph(IntConstant(NodeId(1), 30)),
            "empty" to graph(StringConstant(NodeId(1), "not counted"))
        )

        val result = executor.execute("MATCH (n:IntConstant) RETURN count(n) AS count")

        assertEquals(3L, result.rows.single()["count"])
        assertEquals(listOf("billing", "orders"), graphIds(result.rows.single()))
    }

    @Test
    fun `label histogram sums type counts and retains contributors`() {
        val executor = executor(
            "orders" to graph(
                IntConstant(NodeId(1), 10),
                IntConstant(NodeId(2), 20),
                StringConstant(NodeId(3), "shared")
            ),
            "billing" to graph(IntConstant(NodeId(1), 30))
        )

        val result = executor.execute(
            """
            MATCH (n)
            UNWIND labels(n) AS label
            RETURN label, count(*) AS c
            ORDER BY c DESC
            LIMIT 50
            """.trimIndent()
        )
        val constant = result.rows.first { it["label"] == "Constant" }
        val string = result.rows.first { it["label"] == "StringConstant" }

        assertEquals(4L, constant["c"])
        assertEquals(listOf("billing", "orders"), graphIds(constant))
        assertEquals(1L, string["c"])
        assertEquals(listOf("orders"), graphIds(string))
    }

    @Test
    fun `cross graph execution shares one work budget`() {
        val executor = CrossGraphCypherExecutor(
            listOf(
                CypherGraph("orders", graph(IntConstant(NodeId(1), 10), IntConstant(NodeId(2), 20))),
                CypherGraph("billing", graph(IntConstant(NodeId(1), 30), IntConstant(NodeId(2), 40)))
            ),
            CypherExecutionBudget(maxWorkUnits = 3)
        )

        assertFailsWith<CypherBudgetExceededException> {
            executor.execute("MATCH (n) RETURN n.id")
        }
    }

    @Test
    fun `relationship and named path rows retain graph identity`() {
        val orders = DefaultGraph.Builder()
            .addNode(IntConstant(NodeId(1), 10))
            .addNode(IntConstant(NodeId(2), 20))
            .addEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.ASSIGN))
            .build()
        val executor = executor("orders" to orders, "billing" to graph(IntConstant(NodeId(1), 30)))

        val relationship = executor.execute("MATCH (a)-[r:DATAFLOW]->(b) RETURN r")
        assertEquals(listOf("orders"), graphIds(relationship.rows.single()))
        assertEquals("orders", (relationship.rows.single()["r"] as Map<*, *>)["graphId"])

        val namedPath = executor.execute(
            "MATCH p=(a:IntConstant)-[:DATAFLOW]->(b:IntConstant) " +
                "RETURN graphId(p) AS graph, p"
        )
        assertEquals("orders", namedPath.rows.single()["graph"])
        assertEquals("orders", (namedPath.rows.single()["p"] as Map<*, *>)["graphId"])
        assertEquals(listOf("orders"), graphIds(namedPath.rows.single()))

        val incomingPath = executor.execute(
            "MATCH p=(b:IntConstant)<-[:DATAFLOW]-(a:IntConstant) RETURN graphId(p) AS graph, p"
        )
        assertEquals("orders", incomingPath.rows.single()["graph"])

        val variablePath = executor.execute(
            "MATCH (a:IntConstant)-[r:DATAFLOW*1..2]->(b:IntConstant) RETURN r"
        )
        val variableRelationships = variablePath.rows.single()["r"] as List<*>
        assertEquals("orders", (variableRelationships.single() as Map<*, *>)["graphId"])

        val budgetedVariablePath = CrossGraphCypherExecutor(
            listOf(CypherGraph("orders", orders)),
            CypherExecutionBudget(maxWorkUnits = 20)
        ).execute("MATCH (a:IntConstant)-[r:DATAFLOW*1..2]->(b:IntConstant) RETURN r")
        val budgetedRelationships = budgetedVariablePath.rows.single()["r"] as List<*>
        assertEquals("orders", (budgetedRelationships.single() as Map<*, *>)["graphId"])

        val budgetedNamedPath = CrossGraphCypherExecutor(
            listOf(CypherGraph("orders", orders)),
            CypherExecutionBudget(maxWorkUnits = 100)
        ).execute("MATCH p=(a:IntConstant)-[:DATAFLOW]->(b:IntConstant) RETURN a, p")
        assertEquals("orders", (budgetedNamedPath.rows.single()["a"] as Map<*, *>)["graphId"])
        assertEquals("orders", (budgetedNamedPath.rows.single()["p"] as Map<*, *>)["graphId"])
    }

    @Test
    fun `distinct and union merge provenance from every collapsed row`() {
        val executor = executor(
            "orders" to graph(StringConstant(NodeId(1), "shared")),
            "billing" to graph(StringConstant(NodeId(1), "shared"))
        )

        val distinct = executor.execute(
            "MATCH (n:StringConstant) RETURN DISTINCT n.value AS value LIMIT 1"
        )
        assertEquals("shared", distinct.rows.single()["value"])
        assertEquals(listOf("billing", "orders"), graphIds(distinct.rows.single()))

        val union = executor.execute(
            "MATCH (n:StringConstant) WHERE graphId(n) = 'orders' RETURN n.value AS value " +
                "UNION MATCH (n:StringConstant) WHERE graphId(n) = 'billing' RETURN n.value AS value"
        )
        assertEquals(1, union.rows.size)
        assertEquals(listOf("billing", "orders"), graphIds(union.rows.single()))

        val boundedUnion = executor.execute(
            "MATCH (n:StringConstant) WHERE graphId(n) = 'orders' RETURN n.value AS value " +
                "UNION MATCH (n:StringConstant) WHERE graphId(n) = 'billing' RETURN n.value AS value",
            maxRows = 1
        )
        assertEquals(listOf("shared"), boundedUnion.rows.map { it["value"] })
        assertEquals(listOf("billing", "orders"), graphIds(boundedUnion.rows.single()))
    }

    @Test
    fun `broad discovery query streams distinct rows and merges provenance`() {
        val caller = MethodDescriptor(
            TypeDescriptor("com.example.ThankYouService"),
            "create",
            emptyList(),
            TypeDescriptor("void")
        )
        val callee = MethodDescriptor(
            TypeDescriptor("com.example.Repository"),
            "save",
            emptyList(),
            TypeDescriptor("void")
        )
        val executor = executor(
            "orders" to graph(CallSiteNode(NodeId(1), caller, callee, 10, null, emptyList())),
            "billing" to graph(CallSiteNode(NodeId(1), caller, callee, 20, null, emptyList()))
        )

        val result = executor.execute(
            """
            MATCH (n)
            WHERE (exists(n.class) AND n.class CONTAINS 'ThankYou')
               OR (exists(n.name) AND n.name CONTAINS 'ThankYou')
               OR (exists(n.caller_class) AND n.caller_class CONTAINS 'ThankYou')
               OR (exists(n.caller_name) AND n.caller_name CONTAINS 'ThankYou')
               OR (exists(n.callee_class) AND n.callee_class CONTAINS 'ThankYou')
               OR (exists(n.callee_name) AND n.callee_name CONTAINS 'ThankYou')
            RETURN DISTINCT n.class AS class, n.name AS name,
                n.caller_class AS caller, n.caller_name AS callerMethod,
                n.callee_class AS callee, n.callee_name AS calleeMethod
            LIMIT 1
            """.trimIndent()
        )

        assertEquals(1, result.rows.size)
        assertEquals("com.example.ThankYouService", result.rows.single()["caller"])
        assertEquals("create", result.rows.single()["callerMethod"])
        assertEquals("com.example.Repository", result.rows.single()["callee"])
        assertEquals("save", result.rows.single()["calleeMethod"])
        assertEquals(listOf("billing", "orders"), graphIds(result.rows.single()))
    }

    @Test
    fun `qualified broad discovery drains large matches with bounded deduplication state`() {
        val matchCount = 5_000
        val consumed = mutableMapOf("orders" to 0, "billing" to 0)
        val caller = MethodDescriptor(
            TypeDescriptor("com.example.TargetService"),
            "call",
            emptyList(),
            TypeDescriptor("void")
        )
        val callee = MethodDescriptor(
            TypeDescriptor("com.example.TargetRepository"),
            "load",
            emptyList(),
            TypeDescriptor("void")
        )

        fun indexedGraph(graphId: String): Graph {
            val backing = DefaultGraph.Builder().apply {
                repeat(matchCount) { index ->
                    addNode(CallSiteNode(NodeId(index), caller, callee, index, null, emptyList()))
                }
            }.build()
            return object : Graph by backing, StringPropertyLookup {
                override fun <T : Node> nodesByStringProperty(
                    type: Class<T>,
                    property: String,
                    mode: StringMatchMode,
                    expected: String,
                    limit: Int
                ): Sequence<T> = backing.nodes(type).onEach {
                    consumed[graphId] = consumed.getValue(graphId) + 1
                }
            }
        }

        val result = executor(
            "orders" to indexedGraph("orders"),
            "billing" to indexedGraph("billing")
        ).execute(
            "MATCH (n:CallSiteNode) WHERE " +
                "n.caller_class CONTAINS 'Target' OR n.callee_class CONTAINS 'Target' " +
                "RETURN DISTINCT n.caller_class AS caller LIMIT 1"
        )

        assertEquals(listOf("com.example.TargetService"), result.rows.map { it["caller"] })
        assertEquals(listOf("billing", "orders"), graphIds(result.rows.single()))
        assertEquals(2 * matchCount, consumed.getValue("orders"))
        assertEquals(2 * matchCount, consumed.getValue("billing"))
    }

    @Test
    fun `non distinct broad discovery scans graphs concurrently and preserves source rows`() {
        if (Runtime.getRuntime().availableProcessors() < 2) return
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val workersReady = CyclicBarrier(2)
        val returnType = TypeDescriptor("void")

        fun backingGraph(graphId: String): Graph = DefaultGraph.Builder().apply {
                repeat(2) { index ->
                    addNode(
                        CallSiteNode(
                            NodeId(index),
                            MethodDescriptor(
                                TypeDescriptor("example.$graphId.Target"),
                                "call$index",
                                emptyList(),
                                returnType
                            ),
                            MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                            index,
                            null,
                            emptyList()
                        )
                    )
                }
            }.build()

        fun parallelGraph(graphId: String): Graph {
            val backing = backingGraph(graphId)
            return object : Graph by backing, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> = sequence {
                    if (type != CallSiteNode::class.java) return@sequence
                    val now = active.incrementAndGet()
                    maximumActive.accumulateAndGet(now, ::maxOf)
                    try {
                        workersReady.await(2, TimeUnit.SECONDS)
                        @Suppress("UNCHECKED_CAST")
                        for (node in backing.nodes(CallSiteNode::class.java).take(limit)) yield(node as T)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }

        val result = executor(
            "orders" to parallelGraph("orders"),
            "billing" to parallelGraph("billing")
        ).execute(
            "MATCH (n:CallSiteNode) WHERE " +
                "n.caller_class CONTAINS 'Target' OR n.callee_class CONTAINS 'Target' " +
                "RETURN n.graphId AS graph, n.caller_name AS caller LIMIT 3"
        )

        assertEquals(listOf("orders", "orders", "billing"), result.rows.map { it["graph"] })
        assertEquals(listOf("call0", "call1", "call0"), result.rows.map { it["caller"] })
        assertEquals(listOf(listOf("orders"), listOf("orders"), listOf("billing")), result.rows.map(::graphIds))
        assertEquals(2, maximumActive.get())

        val serialResult = executor("catalog" to backingGraph("catalog")).execute(
            "MATCH (n:CallSiteNode) WHERE " +
                "n.caller_class CONTAINS 'Target' OR n.callee_class CONTAINS 'Target' " +
                "RETURN n.graphId AS graph, n.caller_name AS caller LIMIT 3"
        )
        assertEquals(listOf("catalog", "catalog"), serialResult.rows.map { it["graph"] })
        assertEquals(listOf("call0", "call1"), serialResult.rows.map { it["caller"] })
        assertEquals(listOf(listOf("catalog"), listOf("catalog")), serialResult.rows.map(::graphIds))
    }

    @Test
    fun `parallel residual string predicates keep graph local bindings`() {
        if (Runtime.getRuntime().availableProcessors() < 2) return
        val returnType = TypeDescriptor("void")
        val sources = (0 until 8).map { graphIndex ->
            val backing = DefaultGraph.Builder().apply {
                repeat(20_000) { index ->
                    val outcome = if (index and 1 == 0) "Accept" else "Reject"
                    addNode(
                        CallSiteNode(
                            NodeId(index),
                            MethodDescriptor(
                                TypeDescriptor("example.Target$graphIndex"),
                                "call$index",
                                emptyList(),
                                returnType
                            ),
                            MethodDescriptor(
                                TypeDescriptor("example.$outcome"),
                                "invoke$index",
                                emptyList(),
                                returnType
                            ),
                            index,
                            null,
                            emptyList()
                        )
                    )
                }
            }.build()
            CypherGraph("graph-$graphIndex", backing)
        }

        val result = CrossGraphCypherExecutor(sources).execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' " +
                "AND NOT n.callee_class CONTAINS 'Reject' " +
                "RETURN DISTINCT n.graphId AS graph, n.callee_class AS callee LIMIT 16"
        )

        assertEquals((0 until 8).map { "graph-$it" }, result.rows.map { it["graph"] })
        assertEquals(List(8) { "example.Accept" }, result.rows.map { it["callee"] })
    }

    @Test
    fun quotedContainsRegexPreservesJavaNewlineSemantics() {
        val returnType = TypeDescriptor("void")
        val graph = graph(
            CallSiteNode(
                NodeId(1),
                MethodDescriptor(
                    TypeDescriptor("example\nTarget"),
                    "call",
                    emptyList(),
                    returnType
                ),
                MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                1,
                null,
                emptyList()
            )
        )

        val result = executor("newline" to graph).execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class =~ '.*\\QTarget\\E.*' " +
                "RETURN n.caller_class AS caller LIMIT 10"
        )

        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun parallelNonDistinctLimitDoesNotSpendTheLimitPerGraph() {
        val returnType = TypeDescriptor("void")
        fun matchingGraph(graphId: String): Graph = DefaultGraph.Builder().apply {
            repeat(20) { index ->
                addNode(
                    CallSiteNode(
                        NodeId(index),
                        MethodDescriptor(
                            TypeDescriptor("example.$graphId.Target"),
                            "call$index",
                            emptyList(),
                            returnType
                        ),
                        MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                        index,
                        null,
                        emptyList()
                    )
                )
            }
        }.build()
        val executor = CrossGraphCypherExecutor(
            listOf(
                CypherGraph("orders", matchingGraph("orders")),
                CypherGraph("billing", matchingGraph("billing"))
            ),
            CypherExecutionBudget(maxWorkUnits = 10)
        )

        val result = executor.execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' " +
                "RETURN n.caller_class AS caller LIMIT 10"
        )

        assertEquals(10, result.rows.size)
        assertTrue(result.rows.all { it["caller"] == "example.orders.Target" })
    }

    @Test
    fun concurrentBroadQueriesDoNotSerializeOnTheSameGraph() {
        if (Runtime.getRuntime().availableProcessors() < 2) return
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val returnType = TypeDescriptor("void")
        val backing = graph(
            CallSiteNode(
                NodeId(1),
                MethodDescriptor(TypeDescriptor("example.Target"), "call", emptyList(), returnType),
                MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                1,
                null,
                emptyList()
            )
        )
        val shared = object : Graph by backing, StringPropertyDisjunctionLookup {
            override fun <T : Node> nodesByStringPropertyDisjunction(
                type: Class<T>,
                predicates: List<StringPropertyPredicate>,
                limit: Int
            ): Sequence<T> = sequence {
                if (type != CallSiteNode::class.java) return@sequence
                val now = active.incrementAndGet()
                maximumActive.accumulateAndGet(now, ::maxOf)
                entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                    @Suppress("UNCHECKED_CAST")
                    yieldAll(backing.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        fun queryExecutor(suffix: String) = CrossGraphCypherExecutor(
            listOf(CypherGraph("shared-$suffix", shared), CypherGraph("empty-$suffix", graph()))
        )
        val requests = Executors.newFixedThreadPool(2)
        val first = requests.submit<CypherResult> {
            queryExecutor("first").execute(
                "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' " +
                    "RETURN n.caller_class AS caller LIMIT 1"
            )
        }
        val second = requests.submit<CypherResult> {
            queryExecutor("second").execute(
                "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' " +
                    "RETURN n.caller_class AS caller LIMIT 1"
            )
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(2, maximumActive.get())
            release.countDown()
            assertEquals("example.Target", first.get(5, TimeUnit.SECONDS).rows.single()["caller"])
            assertEquals("example.Target", second.get(5, TimeUnit.SECONDS).rows.single()["caller"])
        } finally {
            release.countDown()
            requests.shutdownNow()
        }
    }

    @Test
    fun `direct string conjunction pushes down its required predicate and applies residual disjunction`() {
        val lookupCalls = AtomicInteger()
        val returnType = TypeDescriptor("void")

        fun indexedGraph(): Graph {
            val backing = DefaultGraph.Builder().apply {
                listOf("Keep", "Drop").forEachIndexed { index, methodName ->
                    addNode(
                        CallSiteNode(
                            NodeId(index),
                            MethodDescriptor(TypeDescriptor("example.Target"), methodName, emptyList(), returnType),
                            MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                            index,
                            null,
                            emptyList()
                        )
                    )
                }
            }.build()
            return object : Graph by backing, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    lookupCalls.incrementAndGet()
                    assertEquals(listOf("caller_class"), predicates.map { it.property })
                    @Suppress("UNCHECKED_CAST")
                    return backing.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>
                }
            }
        }

        val queryExecutor = executor(
            "orders" to indexedGraph(),
            "billing" to indexedGraph()
        )
        val result = queryExecutor.execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' AND " +
                "(n.caller_name CONTAINS 'Keep' OR n.callee_name CONTAINS 'Keep') " +
                "RETURN DISTINCT n.caller_name AS caller LIMIT 10"
        )

        assertEquals(listOf("Keep"), result.rows.map { it["caller"] })
        assertEquals(listOf("billing", "orders"), graphIds(result.rows.single()))

        val sourceRows = queryExecutor.execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' AND " +
                "(n.caller_name CONTAINS 'Keep' OR n.callee_name CONTAINS 'Keep') " +
                "RETURN n.graphId AS graph, n.caller_name AS caller LIMIT 10"
        )
        assertEquals(listOf("orders", "billing"), sourceRows.rows.map { it["graph"] })
        assertEquals(listOf("Keep", "Keep"), sourceRows.rows.map { it["caller"] })
        assertEquals(listOf(listOf("orders"), listOf("billing")), sourceRows.rows.map(::graphIds))
        assertEquals(4, lookupCalls.get())
    }

    @Test
    fun `broad discovery query preserves dynamic annotation properties`() {
        val annotation = AnnotationNode(
            NodeId(1),
            "com.example.Feature",
            "com.example.Owner",
            "create",
            mapOf("caller_class" to "com.example.ThankYouDynamicOwner")
        )
        val executor = executor("orders" to graph(annotation))

        val result = executor.execute(
            "MATCH (n) WHERE " +
                "(exists(n.class) AND n.class CONTAINS 'ThankYou') OR " +
                "(exists(n.name) AND n.name CONTAINS 'ThankYou') OR " +
                "(exists(n.caller_class) AND n.caller_class CONTAINS 'ThankYou') OR " +
                "(exists(n.caller_name) AND n.caller_name CONTAINS 'ThankYou') OR " +
                "(exists(n.callee_class) AND n.callee_class CONTAINS 'ThankYou') OR " +
                "(exists(n.callee_name) AND n.callee_name CONTAINS 'ThankYou') " +
                "RETURN DISTINCT n.caller_class AS caller LIMIT 10"
        )

        assertEquals(listOf("com.example.ThankYouDynamicOwner"), result.rows.map { it["caller"] })
        assertEquals(listOf("orders"), graphIds(result.rows.single()))

        val wrapped = executor.execute(
            "MATCH (n) WHERE " +
                "toLower(coalesce(n.class, '')) CONTAINS 'thankyou' OR " +
                "toLower(coalesce(n.name, '')) CONTAINS 'thankyou' OR " +
                "toLower(coalesce(n.caller_class, '')) CONTAINS 'thankyou' OR " +
                "toLower(coalesce(n.caller_name, '')) CONTAINS 'thankyou' OR " +
                "toLower(coalesce(n.callee_class, '')) CONTAINS 'thankyou' OR " +
                "toLower(coalesce(n.callee_name, '')) CONTAINS 'thankyou' " +
                "RETURN DISTINCT n.caller_class AS caller LIMIT 10"
        )
        assertEquals(listOf("com.example.ThankYouDynamicOwner"), wrapped.rows.map { it["caller"] })
        assertEquals(listOf("orders"), graphIds(wrapped.rows.single()))
    }

    @Test
    fun `wrapped lowercase distinct limit merges provenance across graphs`() {
        val caller = MethodDescriptor(
            TypeDescriptor("com.example.VoucherService"),
            "create",
            emptyList(),
            TypeDescriptor("void")
        )
        val callee = MethodDescriptor(
            TypeDescriptor("com.example.Repository"),
            "load",
            emptyList(),
            TypeDescriptor("void")
        )

        fun indexedGraph(): Graph {
            val backing = graph(CallSiteNode(NodeId(1), caller, callee, 1, null, emptyList()))
            return object : Graph by backing, TransformedStringPropertyLookup {
                override fun <T : Node> nodesByTransformedStringProperty(
                    type: Class<T>,
                    property: String,
                    transform: StringValueTransform,
                    mode: StringMatchMode,
                    expected: String,
                    limit: Int
                ): Sequence<T> {
                    assertEquals(StringValueTransform.LOWERCASE, transform)
                    return backing.nodes(type).filter { node ->
                        val value = NodePropertyAccessor.getProperty(node, property) as? String
                        value?.lowercase()?.contains(expected) == true
                    }.take(limit)
                }
            }
        }

        val result = executor("orders" to indexedGraph(), "billing" to indexedGraph()).execute(
            "MATCH (n) WHERE " +
                "toLower(coalesce(n.caller_class, '')) CONTAINS 'voucher' OR " +
                "toLower(coalesce(n.callee_class, '')) CONTAINS 'voucher' " +
                "RETURN DISTINCT n.caller_class AS caller LIMIT 1"
        )

        assertEquals(listOf("com.example.VoucherService"), result.rows.map { it["caller"] })
        assertEquals(listOf("billing", "orders"), graphIds(result.rows.single()))
    }

    @Test
    fun `parallel string scans preserve source order limit and complete provenance`() {
        if (Runtime.getRuntime().availableProcessors() < 2) return
        val barrier = CyclicBarrier(2)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val returnType = TypeDescriptor("void")

        fun parallelGraph(vararg callerClasses: String): Graph {
            val backing = DefaultGraph.Builder().apply {
                callerClasses.forEachIndexed { index, callerClass ->
                    addNode(
                        CallSiteNode(
                            NodeId(index),
                            MethodDescriptor(TypeDescriptor(callerClass), "call", emptyList(), returnType),
                            MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                            index,
                            null,
                            emptyList()
                        )
                    )
                }
            }.build()
            return object : Graph by backing, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    @Suppress("UNCHECKED_CAST")
                    val nodes = backing.nodes(CallSiteNode::class.java) as Sequence<T>
                    return sequence<T> {
                        val now = active.incrementAndGet()
                        maximumActive.accumulateAndGet(now, ::maxOf)
                        try {
                            barrier.await(2, TimeUnit.SECONDS)
                            for (node in nodes.take(limit)) yield(node)
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }
        }

        val result = executor(
            "orders" to parallelGraph("example.TargetA", "example.TargetB"),
            "billing" to parallelGraph("example.TargetA", "example.TargetC")
        ).execute(
            "MATCH (n:CallSiteNode) WHERE " +
                "n.caller_class CONTAINS 'Target' OR n.callee_class CONTAINS 'Target' " +
                "RETURN DISTINCT n.caller_class AS caller LIMIT 2"
        )

        assertEquals(listOf("example.TargetA", "example.TargetB"), result.rows.map { it["caller"] })
        assertEquals(listOf("billing", "orders"), graphIds(result.rows.first()))
        assertEquals(listOf("orders"), graphIds(result.rows.last()))
        assertEquals(2, maximumActive.get())
    }

    @Test
    fun `parallel row scanners forward bounded limits while residual filters keep complete candidates`() {
        if (Runtime.getRuntime().availableProcessors() < 2 ||
            System.getProperty("graphite.cypher.directStringParallelism") != null
        ) return
        val lookupLimits = List(2) { java.util.concurrent.ConcurrentLinkedQueue<Int>() }
        val returnType = TypeDescriptor("void")

        fun lookupGraph(graphIndex: Int): Graph {
            val backing = DefaultGraph.Builder().apply {
                repeat(4) { nodeIndex ->
                    addNode(
                        CallSiteNode(
                            NodeId(nodeIndex),
                            MethodDescriptor(
                                TypeDescriptor("example.Target${graphIndex}Service$nodeIndex"),
                                "call",
                                emptyList(),
                                returnType
                            ),
                            MethodDescriptor(
                                TypeDescriptor("example.Repository"),
                                if (nodeIndex == 3) "Keep" else "Discard",
                                emptyList(),
                                returnType
                            ),
                            nodeIndex,
                            null,
                            emptyList()
                        )
                    )
                }
            }.build()
            return object : Graph by backing, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    lookupLimits[graphIndex] += limit
                    @Suppress("UNCHECKED_CAST")
                    return backing.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>
                }
            }
        }

        val queryExecutor = executor(
            "graph-0" to lookupGraph(0),
            "graph-1" to lookupGraph(1)
        )
        val bounded = queryExecutor.execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' OR " +
                "n.callee_class CONTAINS 'Target' " +
                "RETURN n.graphId AS graph, n.caller_class AS caller LIMIT 2"
        )

        assertEquals(listOf("graph-0", "graph-0"), bounded.rows.map { it["graph"] })
        assertEquals(
            listOf("example.Target0Service0", "example.Target0Service1"),
            bounded.rows.map { it["caller"] }
        )
        assertTrue(lookupLimits.all { limits -> limits.toList() == listOf(2) })

        lookupLimits.forEach { it.clear() }
        val residual = queryExecutor.execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' AND " +
                "n.callee_name CONTAINS 'Keep' " +
                "RETURN n.graphId AS graph, n.caller_class AS caller LIMIT 1"
        )

        assertEquals(listOf("graph-0"), residual.rows.map { it["graph"] })
        assertEquals(listOf("example.Target0Service3"), residual.rows.map { it["caller"] })
        assertTrue(lookupLimits.all { limits -> limits.toList() == listOf(4) })
    }

    @Test
    fun `filtered string counts aggregate graph-local scans in parallel`() {
        if (Runtime.getRuntime().availableProcessors() < 2) return
        val barrier = CyclicBarrier(2)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val returnType = TypeDescriptor("void")

        fun countingGraph(vararg callerClasses: String): Graph {
            val backing = DefaultGraph.Builder().apply {
                callerClasses.forEachIndexed { index, callerClass ->
                    addNode(
                        CallSiteNode(
                            NodeId(index),
                            MethodDescriptor(TypeDescriptor(callerClass), "call", emptyList(), returnType),
                            MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                            index,
                            null,
                            emptyList()
                        )
                    )
                }
            }.build()
            return object : Graph by backing, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    @Suppress("UNCHECKED_CAST")
                    val nodes = backing.nodes(CallSiteNode::class.java) as Sequence<T>
                    return sequence {
                        val now = active.incrementAndGet()
                        maximumActive.accumulateAndGet(now, ::maxOf)
                        try {
                            barrier.await(2, TimeUnit.SECONDS)
                            yieldAll(nodes.take(limit))
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }
        }

        val executor = executor(
            "orders" to countingGraph("example.TargetA", "example.TargetB"),
            "billing" to countingGraph("example.TargetA", "example.TargetC")
        )
        val predicate = "n.caller_class CONTAINS 'Target' OR n.callee_class CONTAINS 'Target'"
        val count = executor.execute("MATCH (n:CallSiteNode) WHERE $predicate RETURN count(*) AS total")
        val distinct = executor.execute(
            "MATCH (n:CallSiteNode) WHERE $predicate RETURN count(DISTINCT n.caller_class) AS total"
        )

        assertEquals(4L, count.rows.single()["total"])
        assertEquals(3L, distinct.rows.single()["total"])
        assertEquals(listOf("billing", "orders"), graphIds(count.rows.single()))
        assertEquals(listOf("billing", "orders"), graphIds(distinct.rows.single()))
        assertEquals(2, maximumActive.get())
    }

    @Test
    fun `work tracked filtered counts use budget aware storage aggregation in parallel`() {
        if (Runtime.getRuntime().availableProcessors() < 2) return
        val barrier = CyclicBarrier(2)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val untrackedCalls = AtomicInteger()

        fun aggregatingGraph(vararg callerClasses: String): Graph {
            val backing = graph()
            return object : Graph by backing, WorkAwareStringPropertyDisjunctionAggregation {
                override fun aggregateStringPropertyDisjunction(
                    type: Class<out Node>,
                    predicates: List<StringPropertyPredicate>,
                    distinctProperty: String?
                ): StringPropertyDisjunctionAggregate? {
                    untrackedCalls.incrementAndGet()
                    return null
                }

                override fun aggregateStringPropertyDisjunction(
                    type: Class<out Node>,
                    predicates: List<StringPropertyPredicate>,
                    distinctProperty: String?,
                    workConsumer: GraphWorkConsumer
                ): StringPropertyDisjunctionAggregate? {
                    if (type != CallSiteNode::class.java) return null
                    val now = active.incrementAndGet()
                    maximumActive.accumulateAndGet(now, ::maxOf)
                    return try {
                        barrier.await(2, TimeUnit.SECONDS)
                        callerClasses.forEach { _ -> workConsumer.consume() }
                        StringPropertyDisjunctionAggregate(
                            callerClasses.size.toLong(),
                            distinctProperty?.let { callerClasses.toSet() }
                        )
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        }

        val sources = listOf(
            CypherGraph("orders", aggregatingGraph("example.TargetA", "example.TargetB")),
            CypherGraph("billing", aggregatingGraph("example.TargetA", "example.TargetC"))
        )
        val context = CypherExecutionContext(CypherExecutionBudget(100))
        val executor = CrossGraphCypherExecutor(sources, context)
        val predicate = "n.caller_class CONTAINS 'Target' OR n.callee_class CONTAINS 'Target'"
        val count = executor.execute("MATCH (n:CallSiteNode) WHERE $predicate RETURN count(*) AS total")
        val distinct = executor.execute(
            "MATCH (n:CallSiteNode) WHERE $predicate RETURN count(DISTINCT n.caller_class) AS total"
        )

        assertEquals(4L, count.rows.single()["total"])
        assertEquals(3L, distinct.rows.single()["total"])
        assertEquals(2, maximumActive.get())
        assertEquals(0, untrackedCalls.get())
    }

    @Test
    fun `small ordered graph scans stay serial`() {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val returnType = TypeDescriptor("void")

        fun orderedGraph(callerClass: String): Graph {
            val backing = DefaultGraph.Builder()
                .addNode(
                    CallSiteNode(
                        NodeId(0),
                        MethodDescriptor(TypeDescriptor(callerClass), "call", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                        0,
                        null,
                        emptyList()
                    )
                )
                .build()
            return object : Graph by backing,
                StringPropertyDisjunctionLookup,
                StringPropertyDisjunctionLookupStrategy,
                StringPropertyLookupOrder {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> = sequence {
                    val now = active.incrementAndGet()
                    maximumActive.accumulateAndGet(now, ::maxOf)
                    try {
                        @Suppress("UNCHECKED_CAST")
                        yieldAll(backing.nodes(CallSiteNode::class.java).take(limit) as Sequence<T>)
                    } finally {
                        active.decrementAndGet()
                    }
                }

                override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()

                override fun prefersSerialStringPropertyDisjunction(
                    type: Class<out Node>,
                    predicates: List<StringPropertyPredicate>
                ): Boolean = true
            }
        }

        val result = executor(
            "orders" to orderedGraph("example.TargetA"),
            "billing" to orderedGraph("example.TargetB")
        ).execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' OR " +
                "n.callee_class CONTAINS 'Target' " +
                "RETURN DISTINCT n.caller_class AS caller LIMIT 2"
        )

        assertEquals(listOf("example.TargetA", "example.TargetB"), result.rows.map { it["caller"] })
        assertEquals(1, maximumActive.get())
    }

    @Test
    fun `ordered filtered rows scan graphs in parallel and preserve global order and skip`() {
        if (Runtime.getRuntime().availableProcessors() < 2) return
        val barrier = CyclicBarrier(2)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val returnType = TypeDescriptor("void")

        fun orderedGraph(vararg callerClasses: String): Graph {
            val nodes = callerClasses.mapIndexed { index, callerClass ->
                CallSiteNode(
                    NodeId(index),
                    MethodDescriptor(TypeDescriptor(callerClass), "call", emptyList(), returnType),
                    MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                    index,
                    null,
                    emptyList()
                )
            }
            val backing = DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build()
            return object : Graph by backing, StringPropertyDisjunctionLookup, StringPropertyLookupOrder {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> = sequence {
                    val now = active.incrementAndGet()
                    maximumActive.accumulateAndGet(now, ::maxOf)
                    try {
                        barrier.await(2, TimeUnit.SECONDS)
                        @Suppress("UNCHECKED_CAST")
                        yieldAll(nodes.asSequence().take(limit) as Sequence<T>)
                    } finally {
                        active.decrementAndGet()
                    }
                }

                override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()
            }
        }

        val result = executor(
            "orders" to orderedGraph("example.TargetZ", "example.TargetA"),
            "billing" to orderedGraph("example.TargetB", "example.TargetC")
        ).execute(
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' " +
                "RETURN n.caller_class AS caller ORDER BY caller SKIP 1 LIMIT 2"
        )

        assertEquals(listOf("example.TargetB", "example.TargetC"), result.rows.map { it["caller"] })
        assertEquals(2, maximumActive.get())
    }

    @Test
    fun `provenance scans keep workers busy across more graphs than workers`() {
        if (Runtime.getRuntime().availableProcessors() < 2) return
        val releaseSlowGraph = CountDownLatch(1)
        val slowGraphStarted = CountDownLatch(1)
        val thirdGraphStarted = CountDownLatch(1)
        val returnType = TypeDescriptor("void")

        fun scheduledGraph(callerClass: String, afterFirst: (() -> Unit)? = null): Graph {
            val node = CallSiteNode(
                NodeId(1),
                MethodDescriptor(TypeDescriptor(callerClass), "call", emptyList(), returnType),
                MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                1,
                null,
                emptyList()
            )
            val backing = graph(node)
            return object : Graph by backing, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    @Suppress("UNCHECKED_CAST")
                    return sequence {
                        yield(node as T)
                        afterFirst?.invoke()
                    }
                }
            }
        }

        val queryThread = Executors.newSingleThreadExecutor()
        val future = queryThread.submit<CypherResult> {
            executor(
                "slow" to scheduledGraph("example.TargetA") {
                    slowGraphStarted.countDown()
                    check(releaseSlowGraph.await(5, TimeUnit.SECONDS))
                },
                "quick" to scheduledGraph("example.TargetB"),
                "third" to scheduledGraph("example.TargetA") { thirdGraphStarted.countDown() }
            ).execute(
                "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' OR " +
                    "n.callee_class CONTAINS 'Target' " +
                    "RETURN DISTINCT n.caller_class AS caller LIMIT 1"
            )
        }
        try {
            assertTrue(slowGraphStarted.await(5, TimeUnit.SECONDS))
            assertTrue(thirdGraphStarted.await(2, TimeUnit.SECONDS))
            releaseSlowGraph.countDown()
            val result = future.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("example.TargetA"), result.rows.map { it["caller"] })
            assertEquals(listOf("slow", "third"), graphIds(result.rows.single()))
        } finally {
            releaseSlowGraph.countDown()
            queryThread.shutdownNow()
        }
    }

    @Test
    fun `parallel scan callbacks can execute nested cross graph queries without deadlock`() {
        if (Runtime.getRuntime().availableProcessors() < 2) return
        val returnType = TypeDescriptor("void")
        val nested = executor(
            "nested-a" to graph(
                CallSiteNode(
                    NodeId(1),
                    MethodDescriptor(TypeDescriptor("example.NestedA"), "call", emptyList(), returnType),
                    MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                    1,
                    null,
                    emptyList()
                )
            ),
            "nested-b" to graph(
                CallSiteNode(
                    NodeId(2),
                    MethodDescriptor(TypeDescriptor("example.NestedB"), "call", emptyList(), returnType),
                    MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                    2,
                    null,
                    emptyList()
                )
            )
        )
        val outerWorkersReady = CyclicBarrier(2)

        fun reentrantGraph(callerClass: String): Graph {
            val node = CallSiteNode(
                NodeId(1),
                MethodDescriptor(TypeDescriptor(callerClass), "call", emptyList(), returnType),
                MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                1,
                null,
                emptyList()
            )
            val backing = graph(node)
            return object : Graph by backing, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    if (type != CallSiteNode::class.java) return emptySequence()
                    @Suppress("UNCHECKED_CAST")
                    return sequence {
                        outerWorkersReady.await(2, TimeUnit.SECONDS)
                        val nestedResult = nested.execute(
                            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Nested' OR " +
                                "n.callee_class CONTAINS 'Nested' " +
                                "RETURN DISTINCT n.caller_class AS caller LIMIT 1"
                        )
                        check(nestedResult.rows.single()["caller"].toString().startsWith("example.Nested"))
                        yield(node as T)
                    }
                }
            }
        }

        val queryThread = Executors.newSingleThreadExecutor()
        val future = queryThread.submit<CypherResult> {
            executor(
                "outer-a" to reentrantGraph("example.OuterA"),
                "outer-b" to reentrantGraph("example.OuterB")
            ).execute(
                "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Outer' OR " +
                    "n.callee_class CONTAINS 'Outer' " +
                    "RETURN DISTINCT n.caller_class AS caller LIMIT 2"
            )
        }
        try {
            assertEquals(
                listOf("example.OuterA", "example.OuterB"),
                future.get(5, TimeUnit.SECONDS).rows.map { it["caller"] }
            )
        } finally {
            queryThread.shutdownNow()
        }
    }

    @Test
    fun `parallel scan failure interrupts peer tasks`() {
        if (Runtime.getRuntime().availableProcessors() < 2) return
        val returnType = TypeDescriptor("void")
        val sharedGraph = graph(
            CallSiteNode(
                NodeId(1),
                MethodDescriptor(TypeDescriptor("example.Shared"), "call", emptyList(), returnType),
                MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                1,
                null,
                emptyList()
            )
        )
        val failingBacking = graph()
        val failingGraph = object : Graph by failingBacking, StringPropertyDisjunctionLookup {
            override fun <T : Node> nodesByStringPropertyDisjunction(
                type: Class<T>,
                predicates: List<StringPropertyPredicate>,
                limit: Int
            ): Sequence<T> = sequence {
                Thread.sleep(250)
                error("intentional parallel scan failure")
            }
        }
        val queryThread = Executors.newSingleThreadExecutor()
        val future = queryThread.submit<CypherResult> {
            executor("shared" to sharedGraph, "failing" to failingGraph).execute(
                "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'example' OR " +
                    "n.callee_class CONTAINS 'example' " +
                    "RETURN DISTINCT n.caller_class AS caller LIMIT 1"
            )
        }
        try {
            val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
                future.get(2, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is IllegalStateException)
            assertEquals("intentional parallel scan failure", failure.cause?.message)
        } finally {
            queryThread.shutdownNow()
        }
    }

    @Test
    fun `supports graph qualified properties functions and fast filtered limits`() {
        val executor = executor(
            "orders" to graph(IntConstant(NodeId(7), 10)),
            "billing" to graph(IntConstant(NodeId(7), 20))
        )

        val result = executor.execute(
            "MATCH (n:IntConstant) WHERE n.graphId = 'orders' " +
                "RETURN id(n) AS id, elementId(n) AS elementId, properties(n) AS properties LIMIT 1"
        )

        val row = result.rows.single()
        assertEquals(7, row["id"])
        assertEquals("orders:7", row["elementId"])
        assertEquals("orders", (row["properties"] as Map<*, *>)["graphId"])
        assertEquals(listOf("orders"), graphIds(row))
    }

    @Test
    fun `graph id equality prunes unselected sources from broad string limit scans`() {
        val scanCounts = List(4) { AtomicInteger() }
        val lookupLimits = List(4) { mutableListOf<Int>() }
        val parallelPermissions = List(4) { java.util.concurrent.ConcurrentLinkedQueue<Boolean>() }
        val segmentWorkerAllocations = List(4) { java.util.concurrent.ConcurrentLinkedQueue<Int>() }
        val returnType = TypeDescriptor("void")
        val graphs = scanCounts.indices.map { graphIndex ->
            val backing = DefaultGraph.Builder().apply {
                addNode(
                    CallSiteNode(
                        NodeId(graphIndex),
                        MethodDescriptor(
                            TypeDescriptor("com.example.Voucher${graphIndex}Service"),
                            "createVoucher",
                            emptyList(),
                            returnType
                        ),
                        MethodDescriptor(
                            TypeDescriptor("com.example.Repository"),
                            "saveVoucher",
                            emptyList(),
                            returnType
                        ),
                        graphIndex,
                        null,
                        emptyList()
                    )
                )
            }.build()
            val measured = object : Graph by backing, WorkAwareStringPropertyDisjunctionLookup {
                override fun nodeCount(type: Class<out Node>): Long? {
                    scanCounts[graphIndex].incrementAndGet()
                    return if (type == CallSiteNode::class.java) 10_000L else backing.nodeCount(type)
                }

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T> {
                    scanCounts[graphIndex].incrementAndGet()
                    lookupLimits[graphIndex] += limit
                    return backing.nodes(type).take(limit)
                }

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int,
                    workConsumer: GraphWorkConsumer
                ): Sequence<T> {
                    parallelPermissions[graphIndex] += workConsumer is ParallelGraphWorkBatchConsumer
                    (workConsumer as? SplitGraphWorkBatchConsumer)?.segmentWorkerCount?.let {
                        segmentWorkerAllocations[graphIndex] += it
                    }
                    workConsumer.consume()
                    return nodesByStringPropertyDisjunction(type, predicates, limit)
                }
            }
            CypherGraph("graph-$graphIndex", measured)
        }
        val executor = CrossGraphCypherExecutor(
            graphs,
            CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = 100_000))
        )
        val broadPredicate = "(" + listOf("caller_class", "caller_name", "callee_class", "callee_name")
            .joinToString(" OR ") { property ->
                "toLower(coalesce(n.$property, '')) CONTAINS 'voucher'"
            } + ")"

        val graphPredicates = listOf(
            "n.graphId = 'graph-3'" to emptyMap(),
            "graphId(n) = 'graph-3'" to emptyMap(),
            "n.graphId = \$graph" to mapOf("graph" to "graph-3")
        )
        for ((graphPredicate, parameters) in graphPredicates) {
            scanCounts.forEach { it.set(0) }
            lookupLimits.forEach(MutableList<Int>::clear)
            val result = executor.execute(
                "MATCH (n) WHERE $graphPredicate AND $broadPredicate " +
                    "RETURN n.graphId AS graph, n.caller_class AS caller LIMIT 250",
                parameters
            )

            assertEquals(listOf("graph-3"), result.rows.map { it["graph"] })
            assertEquals(listOf("com.example.Voucher3Service"), result.rows.map { it["caller"] })
            assertEquals(listOf("graph-3"), graphIds(result.rows.single()))
            assertTrue(scanCounts.take(3).all { it.get() == 0 })
            assertTrue(scanCounts.last().get() > 0)
            assertEquals(250, lookupLimits.last().first())
            assertTrue(lookupLimits.last().all { limit -> limit in 0..250 })
            assertTrue(parallelPermissions.last().all { it })
            assertTrue(segmentWorkerAllocations.last().isEmpty())
        }

        parallelPermissions.forEach { it.clear() }
        segmentWorkerAllocations.forEach { it.clear() }
        val unqualified = executor.execute(
            "MATCH (n) WHERE $broadPredicate " +
                "RETURN n.graphId AS graph, n.caller_class AS caller LIMIT 250"
        )
        assertEquals(graphs.map { it.id }.toSet(), unqualified.rows.map { it["graph"] }.toSet())
        val configuredGraphWorkers = System.getProperty("graphite.cypher.directStringParallelism")
        if (configuredGraphWorkers == null) {
            assertTrue(parallelPermissions.all { permissions -> permissions.isNotEmpty() && permissions.none { it } })
            assertTrue(segmentWorkerAllocations.all { allocations -> allocations.isEmpty() })
        } else {
            val graphWorkers = resolveDirectStringGraphParallelism(graphs.size)
            val segmentWorkers = resolveDirectStringParallelismPlan().segmentWorkerCount
            assertTrue(parallelPermissions.all { permissions -> permissions.isNotEmpty() && permissions.all { it } })
            val observed = segmentWorkerAllocations.map { allocations -> allocations.first() }
            observed.chunked(graphWorkers).forEach { wave ->
                assertTrue(wave.all { it == segmentWorkers })
            }
        }

        scanCounts.forEach { it.set(0) }
        lookupLimits.forEach(MutableList<Int>::clear)
        val rightHandGraphId = executor.execute(
            "MATCH (n) WHERE n.caller_name = 'createVoucher' AND $broadPredicate AND " +
                "n.graphId = 'graph-3' " +
                "RETURN n.graphId AS graph, n.caller_class AS caller LIMIT 1"
        )
        assertEquals(1, rightHandGraphId.rows.size)
        assertEquals("graph-3", rightHandGraphId.rows.single()["graph"])
        assertEquals("com.example.Voucher3Service", rightHandGraphId.rows.single()["caller"])
        assertTrue(scanCounts.take(3).all { it.get() == 0 })
        assertTrue(scanCounts.last().get() > 0)
        assertTrue(lookupLimits.take(3).all(MutableList<Int>::isEmpty))
        assertTrue(lookupLimits.last().isNotEmpty())

        scanCounts.forEach { it.set(0) }
        val missing = executor.execute(
            "MATCH (n) WHERE n.graphId = 'missing' AND $broadPredicate " +
                "RETURN n.graphId AS graph LIMIT 250"
        )
        assertTrue(missing.rows.isEmpty())
        assertTrue(scanCounts.all { it.get() == 0 })

        val contradictory = executor.execute(
            "MATCH (n) WHERE n.graphId = 'graph-3' AND graphId(n) = 'graph-2' AND $broadPredicate " +
                "RETURN n.graphId AS graph LIMIT 250"
        )
        assertTrue(contradictory.rows.isEmpty())

        scanCounts.forEach { it.set(0) }
        val count = executor.execute(
            "MATCH (n) WHERE graphId(n) = 'graph-3' AND $broadPredicate RETURN count(*) AS total"
        )
        assertEquals(1L, count.rows.single()["total"])
        assertTrue(scanCounts.take(3).all { it.get() == 0 })
        assertTrue(scanCounts.last().get() > 0)
    }

    @Test
    fun `execution diagnostics distinguish graph routing from an already selected source`() {
        val returnType = TypeDescriptor("void")
        fun callSite(id: Int, callerClass: String) = CallSiteNode(
            NodeId(id),
            MethodDescriptor(TypeDescriptor(callerClass), "createVoucher", emptyList(), returnType),
            MethodDescriptor(TypeDescriptor("com.example.Repository"), "save", emptyList(), returnType),
            id,
            null,
            emptyList()
        )
        val sources = listOf(
            CypherGraph("orders", graph(callSite(1, "com.example.OrderVoucherService"))),
            CypherGraph("billing", graph(callSite(2, "com.example.BillingVoucherService")))
        )
        val predicate = listOf("caller_class", "caller_name", "callee_class", "callee_name")
            .joinToString(" OR ", prefix = "(", postfix = ")") { property ->
                "toLower(coalesce(n.$property, '')) CONTAINS 'voucher'"
            }
        val routedContext = CypherExecutionContext(CypherExecutionBudget(10_000))

        val routed = CrossGraphCypherExecutor(sources, routedContext).execute(
            "MATCH (n) WHERE \$graph = graphId(n) AND $predicate " +
                "RETURN n.caller_class AS caller LIMIT 200",
            mapOf("graph" to "orders")
        )

        assertEquals(listOf("com.example.OrderVoucherService"), routed.rows.map { it["caller"] })
        assertEquals(
            CypherExecutionDiagnostics(
                graphIdSourceSelections = 1,
                graphIdSourcePruningExecutions = 1,
                graphIdSourcesPruned = 1,
                graphIdSourceConflicts = 0,
                fastPathExecutions = 1,
                filteredNodeLimitFastPathExecutions = 1,
                generalFallbackExecutions = 0,
                workUnitsConsumed = routedContext.diagnostics.workUnitsConsumed
            ),
            routedContext.diagnostics
        )
        assertTrue(routedContext.diagnostics.workUnitsConsumed > 0)

        val selectedContext = CypherExecutionContext(CypherExecutionBudget(10_000))
        val selected = CrossGraphCypherExecutor(listOf(sources.first()), selectedContext).execute(
            "MATCH (n) WHERE n.graphId = 'orders' AND $predicate " +
                "RETURN n.caller_class AS caller LIMIT 200"
        )

        assertEquals(listOf("com.example.OrderVoucherService"), selected.rows.map { it["caller"] })
        assertEquals(1, selectedContext.diagnostics.graphIdSourceSelections)
        assertEquals(0, selectedContext.diagnostics.graphIdSourcePruningExecutions)
        assertEquals(0, selectedContext.diagnostics.graphIdSourcesPruned)
        assertEquals(1, selectedContext.diagnostics.filteredNodeLimitFastPathExecutions)
    }

    @Test
    fun `graph source planner covers node properties fallback and reports conflicts`() {
        val nodeScans = List(2) { AtomicInteger() }
        val sources = listOf("orders" to 10, "billing" to 20).mapIndexed { index, (graphId, value) ->
            val backing = graph(IntConstant(NodeId(index + 1), value))
            CypherGraph(graphId, object : Graph by backing {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
                    nodeScans[index].incrementAndGet()
                    return backing.nodes(type)
                }
            })
        }
        val fallbackContext = CypherExecutionContext(CypherExecutionBudget(10_000))

        val fallback = CrossGraphCypherExecutor(sources, fallbackContext).execute(
            "MATCH (n:IntConstant {graphId: \$graph}) WITH n RETURN n.value AS value",
            mapOf("graph" to "billing")
        )

        assertEquals(listOf(20), fallback.rows.map { it["value"] })
        assertEquals(0, nodeScans.first().get())
        assertTrue(nodeScans.last().get() > 0)
        assertEquals(1, fallbackContext.diagnostics.graphIdSourceSelections)
        assertEquals(1, fallbackContext.diagnostics.graphIdSourcePruningExecutions)
        assertEquals(1, fallbackContext.diagnostics.graphIdSourcesPruned)
        assertEquals(0, fallbackContext.diagnostics.fastPathExecutions)
        assertEquals(1, fallbackContext.diagnostics.generalFallbackExecutions)
        assertEquals(0, fallbackContext.diagnostics.filteredNodeLimitFastPathExecutions)

        val conflictContext = CypherExecutionContext(CypherExecutionBudget(10_000))
        nodeScans.forEach { it.set(0) }
        val conflict = CrossGraphCypherExecutor(sources, conflictContext).execute(
            "MATCH (n:IntConstant) WHERE 'orders' = n.graphId AND graphId(n) = 'billing' " +
                "RETURN n.value AS value LIMIT 1"
        )

        assertTrue(conflict.rows.isEmpty())
        assertTrue(nodeScans.all { it.get() == 0 })
        assertEquals(1, conflictContext.diagnostics.graphIdSourceSelections)
        assertEquals(1, conflictContext.diagnostics.graphIdSourcePruningExecutions)
        assertEquals(2, conflictContext.diagnostics.graphIdSourcesPruned)
        assertEquals(1, conflictContext.diagnostics.graphIdSourceConflicts)
        assertEquals(1, conflictContext.diagnostics.filteredNodeLimitFastPathExecutions)
        assertEquals(0, conflictContext.diagnostics.workUnitsConsumed)
    }

    @Test
    fun `graph source planner prunes finite graph id sets and preserves unsafe shapes`() {
        val nodeScans = List(64) { AtomicInteger() }
        val sources = List(64) { index ->
            val backing = graph(IntConstant(NodeId(index + 1), index * 10))
            CypherGraph("graph-$index", object : Graph by backing {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
                    nodeScans[index].incrementAndGet()
                    return backing.nodes(type)
                }
            })
        }

        fun execute(
            query: String,
            parameters: Map<String, Any?> = emptyMap()
        ): Pair<CypherResult, CypherExecutionDiagnostics> {
            nodeScans.forEach { it.set(0) }
            val context = CypherExecutionContext(CypherExecutionBudget(100_000))
            val result = CrossGraphCypherExecutor(sources, context).execute(query, parameters)
            return result to context.diagnostics
        }

        val (parameterSet, parameterDiagnostics) = execute(
            "MATCH (n:IntConstant) WHERE graphId(n) IN \$graphIds " +
                "RETURN n.graphId AS graph, n.value AS value LIMIT 10",
            mapOf("graphIds" to listOf("graph-1", "missing", "graph-63", "graph-1"))
        )
        assertEquals(listOf("graph-1", "graph-63"), parameterSet.rows.map { it["graph"] })
        assertEquals(listOf(10, 630), parameterSet.rows.map { it["value"] })
        assertEquals(1, parameterDiagnostics.graphIdSourceSelections)
        assertEquals(1, parameterDiagnostics.graphIdSourcePruningExecutions)
        assertEquals(62, parameterDiagnostics.graphIdSourcesPruned)
        assertEquals(1, parameterDiagnostics.filteredNodeLimitFastPathExecutions)
        assertTrue(nodeScans.indices.filterNot { it == 1 || it == 63 }.all { nodeScans[it].get() == 0 })
        assertTrue(nodeScans[1].get() > 0)
        assertTrue(nodeScans[63].get() > 0)

        for (nonList in listOf(setOf("graph-1"), arrayOf("graph-1"))) {
            val (invalidMembership, invalidMembershipDiagnostics) = execute(
                "MATCH (n:IntConstant) WHERE n.graphId IN \$graphIds AND n.value >= 0 " +
                    "RETURN n.graphId AS graph LIMIT 10",
                mapOf("graphIds" to nonList)
            )
            assertTrue(invalidMembership.rows.isEmpty())
            assertEquals(0, invalidMembershipDiagnostics.graphIdSourceSelections)
            assertEquals(0, invalidMembershipDiagnostics.graphIdSourcesPruned)
        }

        val (literalSet, literalDiagnostics) = execute(
            "MATCH (n:IntConstant) WHERE n.graphId IN ['graph-0', 'graph-2'] " +
                "RETURN n.graphId AS graph, n.value AS value LIMIT 10"
        )
        assertEquals(listOf("graph-0", "graph-2"), literalSet.rows.map { it["graph"] })
        assertEquals(62, literalDiagnostics.graphIdSourcesPruned)
        assertEquals(0, literalDiagnostics.graphIdSourceConflicts)

        val (exactOr, exactOrDiagnostics) = execute(
            "MATCH (n:IntConstant) WHERE n.graphId = 'graph-3' OR graphId(n) = 'graph-1' " +
                "RETURN n.graphId AS graph LIMIT 10"
        )
        assertEquals(listOf("graph-1", "graph-3"), exactOr.rows.map { it["graph"] })
        assertEquals(1, exactOrDiagnostics.graphIdSourceSelections)
        assertEquals(62, exactOrDiagnostics.graphIdSourcesPruned)

        val (mixedExactOr, mixedExactOrDiagnostics) = execute(
            "MATCH (n:IntConstant) WHERE (n.graphId = 'graph-0' AND n.value = 999) " +
                "OR graphId(n) = 'graph-1' RETURN n.graphId AS graph LIMIT 10"
        )
        assertEquals(listOf("graph-1"), mixedExactOr.rows.map { it["graph"] })
        assertEquals(1, mixedExactOrDiagnostics.graphIdSourceSelections)
        assertEquals(62, mixedExactOrDiagnostics.graphIdSourcesPruned)

        val (intersection, intersectionDiagnostics) = execute(
            "MATCH (n:IntConstant) WHERE n.graphId IN ['graph-0', 'graph-2'] " +
                "AND graphId(n) IN ['graph-2', 'graph-3'] " +
                "RETURN n.graphId AS graph, n.value AS value LIMIT 10"
        )
        assertEquals(listOf("graph-2"), intersection.rows.map { it["graph"] })
        assertEquals(listOf(20), intersection.rows.map { it["value"] })
        assertEquals(63, intersectionDiagnostics.graphIdSourcesPruned)
        assertEquals(0, intersectionDiagnostics.graphIdSourceConflicts)

        val (conflict, conflictDiagnostics) = execute(
            "MATCH (n:IntConstant) WHERE n.graphId IN ['graph-0'] AND graphId(n) = 'graph-2' " +
                "RETURN n.value AS value LIMIT 10"
        )
        assertTrue(conflict.rows.isEmpty())
        assertTrue(nodeScans.all { it.get() == 0 })
        assertEquals(64, conflictDiagnostics.graphIdSourcesPruned)
        assertEquals(1, conflictDiagnostics.graphIdSourceConflicts)

        val (unknownOr, unknownOrDiagnostics) = execute(
            "MATCH (n:IntConstant) WHERE n.graphId IN ['graph-0'] OR n.value >= 20 " +
                "RETURN n.graphId AS graph LIMIT 100"
        )
        assertEquals(63, unknownOr.rows.size)
        assertEquals("graph-0", unknownOr.rows.first()["graph"])
        assertEquals("graph-63", unknownOr.rows.last()["graph"])
        assertEquals(0, unknownOrDiagnostics.graphIdSourceSelections)

        val (nonRoutingReference, nonRoutingDiagnostics) = execute(
            "MATCH (n:IntConstant) WHERE graphId(n) STARTS WITH 'graph-' " +
                "RETURN n.graphId AS graph LIMIT 100"
        )
        assertEquals(64, nonRoutingReference.rows.size)
        assertEquals("graph-0", nonRoutingReference.rows.first()["graph"])
        assertEquals("graph-63", nonRoutingReference.rows.last()["graph"])
        assertEquals(0, nonRoutingDiagnostics.graphIdSourceSelections)
        assertTrue(nodeScans.all { it.get() > 0 })

        val (independent, independentDiagnostics) = execute(
            "MATCH (a:IntConstant), (b:IntConstant) " +
                "WHERE graphId(a) IN ['graph-0'] AND b.graphId IN ['graph-3'] " +
                "RETURN a.value AS leftValue, b.value AS rightValue LIMIT 10"
        )
        assertEquals(1, independent.rows.size)
        assertEquals(0, independent.rows.single()["leftValue"])
        assertEquals(30, independent.rows.single()["rightValue"])
        assertEquals(0, independentDiagnostics.graphIdSourceSelections)
    }

    @Test
    fun `disjunctive graph id predicate is observable as an unpruned fast path`() {
        val sources = listOf(
            CypherGraph("orders", graph(IntConstant(NodeId(1), 10))),
            CypherGraph("billing", graph(IntConstant(NodeId(2), 20)))
        )
        val context = CypherExecutionContext(CypherExecutionBudget(10_000))

        val result = CrossGraphCypherExecutor(sources, context).execute(
            "MATCH (n:IntConstant) WHERE n.graphId = 'orders' OR n.value = 20 " +
                "RETURN n.graphId AS graph, n.value AS value LIMIT 10"
        )

        assertEquals(listOf("orders", "billing"), result.rows.map { it["graph"] })
        assertEquals(0, context.diagnostics.graphIdSourceSelections)
        assertEquals(0, context.diagnostics.graphIdSourcePruningExecutions)
        assertEquals(1, context.diagnostics.filteredNodeLimitFastPathExecutions)
    }

    @Test
    fun `graph id source pruning preserves disjunctions and independent cross graph patterns`() {
        val returnType = TypeDescriptor("void")
        fun callSite(id: Int, callerClass: String) = CallSiteNode(
            NodeId(id),
            MethodDescriptor(TypeDescriptor(callerClass), "call", emptyList(), returnType),
            MethodDescriptor(TypeDescriptor("com.example.Repository"), "load", emptyList(), returnType),
            id,
            null,
            emptyList()
        )
        val executor = executor(
            "orders" to graph(callSite(1, "com.example.OrderService")),
            "billing" to graph(callSite(2, "com.example.BillingOnlyService"))
        )

        val disjunction = executor.execute(
            "MATCH (n:CallSiteNode) WHERE n.graphId = 'orders' OR " +
                "n.caller_class CONTAINS 'BillingOnly' " +
                "RETURN n.graphId AS graph, n.caller_class AS caller LIMIT 10"
        )
        assertEquals(listOf("orders", "billing"), disjunction.rows.map { it["graph"] })
        assertEquals(
            listOf("com.example.OrderService", "com.example.BillingOnlyService"),
            disjunction.rows.map { it["caller"] }
        )

        val independent = executor.execute(
            "MATCH (a:CallSiteNode), (b:CallSiteNode) " +
                "WHERE a.graphId = 'orders' AND b.graphId = 'billing' " +
                "RETURN a.graphId AS leftGraph, b.graphId AS rightGraph LIMIT 10"
        )
        assertEquals(1, independent.rows.size)
        assertEquals("orders", independent.rows.single()["leftGraph"])
        assertEquals("billing", independent.rows.single()["rightGraph"])
    }

    @Test
    fun `fast string filters preserve qualified identity and provenance`() {
        val executor = executor(
            "orders" to graph(StringConstant(NodeId(1), "feature-order-handler")),
            "billing" to graph(StringConstant(NodeId(1), "feature-billing-handler"))
        )

        val contains = executor.execute(
            "MATCH (n:StringConstant) WHERE n.value CONTAINS 'billing' " +
                "RETURN elementId(n) AS id, n.value AS value LIMIT 1"
        )
        val startsWith = executor.execute(
            "MATCH (n:StringConstant) WHERE n.value STARTS WITH 'feature-order' " +
                "RETURN elementId(n) AS id LIMIT 1"
        )
        val endsWith = executor.execute(
            "MATCH (n:StringConstant) WHERE n.value ENDS WITH 'handler' " +
                "RETURN elementId(n) AS id LIMIT 2"
        )
        val missing = executor.execute(
            "MATCH (n:StringConstant) WHERE n.value CONTAINS 'missing' " +
                "RETURN elementId(n) AS id LIMIT 2"
        )

        assertEquals("billing:1", contains.rows.single()["id"])
        assertEquals("feature-billing-handler", contains.rows.single()["value"])
        assertEquals(listOf("billing"), graphIds(contains.rows.single()))
        assertEquals("orders:1", startsWith.rows.single()["id"])
        assertEquals(listOf("orders:1", "billing:1"), endsWith.rows.map { it["id"] })
        assertEquals(listOf(listOf("orders"), listOf("billing")), endsWith.rows.map(::graphIds))
        assertTrue(missing.rows.isEmpty())
    }

    @Test
    fun `string filters preserve virtual qualified properties`() {
        val executor = executor(
            "orders" to graph(StringConstant(NodeId(1), "order")),
            "billing" to graph(StringConstant(NodeId(1), "billing"))
        )

        val graphId = executor.execute(
            "MATCH (n:StringConstant) WHERE n.graphId STARTS WITH 'ord' " +
                "RETURN elementId(n) AS id LIMIT 1"
        )
        val elementId = executor.execute(
            "MATCH (n:StringConstant) WHERE n.elementId ENDS WITH ':1' " +
                "RETURN elementId(n) AS id LIMIT 2"
        )
        val qualifiedId = executor.execute(
            "MATCH (n:StringConstant) WHERE n.qualifiedId CONTAINS 'billing:' " +
                "RETURN elementId(n) AS id LIMIT 1"
        )

        assertEquals(listOf("orders:1"), graphId.rows.map { it["id"] })
        assertEquals(listOf("orders:1", "billing:1"), elementId.rows.map { it["id"] })
        assertEquals(listOf("billing:1"), qualifiedId.rows.map { it["id"] })
    }

    @Test
    fun `element id seek preserves an empty graph namespace`() {
        val executor = executor("" to graph(IntConstant(NodeId(1), 10)))

        val result = executor.execute(
            "MATCH (n:IntConstant) WHERE elementId(n) = ':1' RETURN n.value AS value"
        )

        assertEquals(listOf(10), result.rows.map { it["value"] })
        assertEquals(listOf(""), graphIds(result.rows.single()))
    }

    @Test
    fun `element id seek seeds a qualified call chain without scanning colliding ids`() {
        val orders = DefaultGraph.Builder()
            .addNode(IntConstant(NodeId(1), 10))
            .addNode(IntConstant(NodeId(2), 20))
            .addEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.ASSIGN))
            .build()
        val billing = DefaultGraph.Builder()
            .addNode(IntConstant(NodeId(1), 30))
            .addNode(IntConstant(NodeId(2), 40))
            .addEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.ASSIGN))
            .build()
        val executor = executor("orders" to orders, "billing" to billing)

        val result = executor.execute(
            "MATCH (a:IntConstant) WHERE elementId(a) = 'billing:1' " +
                "WITH a MATCH (a)-[:DATAFLOW*1..2]->(b:IntConstant) " +
                "RETURN elementId(a) AS source, elementId(b) AS target, b.value AS value LIMIT 2"
        )
        val missing = executor.execute(
            "MATCH (a:IntConstant) WHERE elementId(a) = 'missing:1' RETURN a.value AS value"
        )
        val propertySeek = executor.execute(
            "MATCH (a:IntConstant) WHERE 'orders:1' = a.qualifiedId RETURN a.value AS value"
        )
        val unlabeledSeek = executor.execute(
            "MATCH (a) WHERE elementId(a) = 'billing:1' RETURN a.value AS value"
        )

        assertEquals(
            listOf(mapOf("source" to "billing:1", "target" to "billing:2", "value" to 40)),
            result.rows.map { it.filterKeys { key -> key != RESULT_METADATA_KEY } }
        )
        assertEquals(listOf("billing"), graphIds(result.rows.single()))
        assertEquals(10, propertySeek.rows.single()["value"])
        assertEquals(listOf("orders"), graphIds(propertySeek.rows.single()))
        assertEquals(30, unlabeledSeek.rows.single()["value"])
        assertEquals(listOf("billing"), graphIds(unlabeledSeek.rows.single()))
        assertTrue(missing.rows.isEmpty())
    }

    @Test
    fun `supports an empty graph set and rejects duplicate graph namespaces`() {
        val empty = CrossGraphCypherExecutor(emptyList()).execute("MATCH (n) RETURN count(n) AS count")
        assertEquals(0L, empty.rows.single()["count"])
        assertEquals(emptyList<String>(), graphIds(empty.rows.single()))

        val constant = CrossGraphCypherExecutor(emptyList()).execute("RETURN 1 AS value")
        assertEquals(emptyList<String>(), graphIds(constant.rows.single()))

        val graph = graph(IntConstant(NodeId(1), 1))
        val error = assertFailsWith<IllegalArgumentException> {
            CrossGraphCypherExecutor(listOf(CypherGraph("service", graph), CypherGraph("service", graph)))
        }
        assertTrue(error.message.orEmpty().contains("unique"))
    }

    @Test
    fun `bounded execution and internal value helpers cover qualified edge cases`() {
        val executor = executor(
            "orders" to graph(IntConstant(NodeId(1), 10), IntConstant(NodeId(2), 20))
        )

        val result = executor.execute("MATCH (n:IntConstant) RETURN n", maxRows = 1)
        assertEquals(1, result.rows.size)
        assertEquals(listOf("orders"), graphIds(result.rows.single()))
        assertNull(nodeValue("not a node"))
        assertNull(edgeValue("not an edge"))
    }

    @Test
    fun `qualified values expose namespaced identity properties and equality`() {
        val first = IntConstant(NodeId(1), 10)
        val second = IntConstant(NodeId(2), 20)
        val edge = DataFlowEdge(first.id, second.id, DataFlowKind.ASSIGN)
        val graph = DefaultGraph.Builder()
            .addNode(first)
            .addNode(second)
            .addEdge(edge)
            .build()
        val node = QualifiedNode("orders", graph, first)
        val sameNodeId = QualifiedNode("orders", graph, IntConstant(NodeId(1), 99))
        val qualifiedEdge = QualifiedEdge("orders", graph, edge)
        val sameEdge = QualifiedEdge(
            "orders",
            graph,
            DataFlowEdge(first.id, second.id, DataFlowKind.ASSIGN)
        )
        val path = QualifiedPath("orders", listOf(node), listOf(qualifiedEdge))
        val resourceEdge = QualifiedEdge(
            "orders",
            graph,
            ResourceEdge(first.id, second.id, ResourceRelation.LOOKUP)
        )
        val evaluator = ExpressionEvaluator()

        assertEquals(node, sameNodeId)
        assertEquals(node.hashCode(), sameNodeId.hashCode())
        assertNotEquals(node, QualifiedNode("billing", graph, first))
        assertTrue(!node.equals(first))
        assertEquals(qualifiedEdge, sameEdge)
        assertEquals(qualifiedEdge.hashCode(), sameEdge.hashCode())
        assertNotEquals(qualifiedEdge, QualifiedEdge("billing", graph, edge))
        assertTrue(!qualifiedEdge.equals(edge))
        assertEquals(first, nodeValue(first))
        assertEquals(edge, edgeValue(edge))
        assertEquals("1", CypherFunctions.call("elementId", listOf(first)))
        assertNull(CypherFunctions.call("elementId", listOf("not a node")))

        fun property(value: Any, name: String): Any? = evaluator.evaluate(
            CypherExpr.Property(CypherExpr.Variable("value"), name),
            mapOf("value" to value)
        )

        assertEquals("orders", property(node, "graphId"))
        assertEquals("orders:1", property(node, "elementId"))
        assertEquals("orders:1", property(node, "qualifiedId"))
        assertEquals(10, property(node, "value"))
        assertEquals("orders", property(qualifiedEdge, "graphId"))
        assertEquals("ASSIGN", property(qualifiedEdge, "kind"))
        assertEquals("LOOKUP", property(resourceEdge, "kind"))
        assertEquals("orders", property(path, "graphId"))
        assertEquals(1, property(path, "length"))
        assertNull(property(path, "unknown"))

        fun lessThan(left: Any, right: Any): Any? = evaluator.evaluate(
            CypherExpr.Comparison("<", CypherExpr.Literal(left), CypherExpr.Literal(right)),
            emptyMap()
        )

        assertEquals(false, lessThan(node, sameNodeId))
        assertEquals(true, lessThan(node, QualifiedNode("orders", graph, second)))
        assertEquals(false, lessThan(qualifiedEdge, sameEdge))
        assertEquals(true, lessThan(qualifiedEdge, resourceEdge))
    }

    @Test
    fun `qualified relationship materialization preserves subtype properties`() {
        val first = IntConstant(NodeId(1), 10)
        val second = IntConstant(NodeId(2), 20)
        val graph = DefaultGraph.Builder()
            .addNode(first)
            .addNode(second)
            .addEdge(CallEdge(first.id, second.id, isVirtual = true, isDynamic = false))
            .addEdge(TypeEdge(first.id, second.id, TypeRelation.EXTENDS))
            .addEdge(ControlFlowEdge(first.id, second.id, ControlFlowKind.SEQUENTIAL))
            .addEdge(ResourceEdge(first.id, second.id, ResourceRelation.LOOKUP))
            .build()

        val result = executor("orders" to graph).execute("MATCH (a)-[r]->(b) RETURN r ORDER BY r")
        val relationships = result.rows.map { it.getValue("r") as Map<*, *> }

        assertEquals(4, relationships.size)
        assertTrue(relationships.all { it["graphId"] == "orders" })
        assertTrue(relationships.any { it["virtual"] == true && it["dynamic"] == false })
        assertTrue(relationships.any { it["kind"] == "EXTENDS" })
        assertTrue(relationships.any { it["kind"] == "SEQUENTIAL" })
        assertTrue(relationships.any { it["kind"] == "LOOKUP" })
    }

    private fun executor(vararg graphs: Pair<String, Graph>): CrossGraphCypherExecutor =
        CrossGraphCypherExecutor(graphs.map { (id, graph) -> CypherGraph(id, graph) })

    @Suppress("UNCHECKED_CAST")
    private fun graphIds(row: Map<String, Any?>): List<String> =
        (row.getValue(RESULT_METADATA_KEY) as Map<String, Any?>).getValue(RESULT_GRAPH_IDS_KEY) as List<String>

    private fun graph(vararg nodes: io.johnsonlee.graphite.core.Node): Graph =
        DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build()
}
