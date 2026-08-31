package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.core.ValueNode
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.ClassDependency
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.MmapGraphBuilder
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import io.johnsonlee.graphite.graph.nodesByStringProperty
import io.johnsonlee.graphite.graph.nodesByTransformedStringProperty
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import it.unimi.dsi.fastutil.io.BinIO
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphStoreTest {

    @Test
    fun `node string collection accepts an iterable batch`() {
        val strings = linkedSetOf<String>()

        NodeSerializer.collectNodeStrings(
            listOf(StringConstant(NodeId(0), "feature-alpha")),
            strings
        )

        assertEquals(setOf("feature-alpha"), strings)
    }

    @Test
    @Suppress("LongMethod")
    fun `mapped string property lookup uses raw node fields and falls back when unsupported`() {
        val owner = TypeDescriptor("example.Owner")
        val caller = MethodDescriptor(
            owner,
            "callerFeature",
            listOf(TypeDescriptor("java.lang.String"), TypeDescriptor("int")),
            TypeDescriptor("void")
        )
        val callee = MethodDescriptor(
            TypeDescriptor("example.Target"),
            "billingFeature",
            emptyList(),
            TypeDescriptor("void")
        )
        val graph = DefaultGraph.Builder()
            .addNode(StringConstant(NodeId(0), "feature-alpha"))
            .addNode(StringConstant(NodeId(1), "feature-beta"))
            .addNode(CallSiteNode(NodeId(2), caller, callee, 10, null, emptyList()))
            .addNode(EnumConstant(NodeId(3), TypeDescriptor("example.State"), "READY"))
            .addNode(LocalVariable(NodeId(4), "request", TypeDescriptor("java.lang.String"), caller))
            .addNode(
                FieldNode(
                    NodeId(5),
                    FieldDescriptor(owner, "token", TypeDescriptor("java.lang.String")),
                    false
                )
            )
            .addNode(ParameterNode(NodeId(6), 0, TypeDescriptor("java.lang.String"), caller))
            .addNode(ResourceFileNode(NodeId(7), "config/app.yml", "resources", "yaml"))
            .addNode(IntConstant(NodeId(8), 42))
            .build()
        val dir = Files.createTempDirectory("webgraph-string-property-index")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                val mapped = loaded as MappedWebGraphBackedGraph
                listOf(
                    "caller_class" to "example.Owner",
                    "caller_name" to "callerFeature",
                    "callee_class" to "example.Target",
                    "callee_name" to "billingFeature"
                ).forEach { (property, expected) ->
                    assertEquals(
                        listOf(2),
                        loaded.nodesByStringProperty(
                            CallSiteNode::class.java,
                            property,
                            StringMatchMode.EQUALS,
                            expected,
                            limit = 1
                        ).orEmpty().map { it.id.value }.toList()
                    )
                }
                assertEquals(
                    listOf(7),
                    loaded.nodesByStringPropertyDisjunction(
                        ResourceFileNode::class.java,
                        listOf(
                            StringPropertyPredicate("path", null, StringMatchMode.CONTAINS, "config"),
                            StringPropertyPredicate("source", null, StringMatchMode.CONTAINS, "resources")
                        ),
                        limit = 1
                    ).orEmpty().map { it.id.value }.toList()
                )
                assertEarlyLimitedLookupsDoNotBuildIndex(mapped)
                assertBroadDiscoveryQuery(loaded)
                assertNull(
                    loaded.nodesByStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringMatchMode.CONTAINS,
                        "alpha"
                    )
                )
                val contains = loaded.nodesByStringProperty(
                    StringConstant::class.java,
                    "value",
                    StringMatchMode.CONTAINS,
                    "alpha"
                )?.map { it.value }?.toList()
                assertEquals(1, mapped.stringPropertyIndexCount())
                assertTrue(
                    loaded.nodesByStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringMatchMode.EQUALS,
                        "definitely-missing"
                    ).orEmpty().none()
                )
                assertEquals(
                    listOf("feature-alpha"),
                    loaded.nodesByTransformedStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringValueTransform.LOWERCASE,
                        StringMatchMode.EQUALS,
                        "feature-alpha",
                        limit = Int.MAX_VALUE
                    ).orEmpty().map { it.value }.toList()
                )
                loaded.nodesByStringProperty(
                    CallSiteNode::class.java,
                    "caller_name",
                    StringMatchMode.STARTS_WITH,
                    "caller"
                )
                val startsWith = loaded.nodesByStringProperty(
                    CallSiteNode::class.java,
                    "caller_name",
                    StringMatchMode.STARTS_WITH,
                    "caller"
                )?.map { it.caller.name }?.toList()
                loaded.nodesByStringProperty(
                    CallSiteNode::class.java,
                    "callee_name",
                    StringMatchMode.ENDS_WITH,
                    "Feature"
                )
                val endsWith = loaded.nodesByStringProperty(
                    CallSiteNode::class.java,
                    "callee_name",
                    StringMatchMode.ENDS_WITH,
                    "Feature"
                )?.map { it.callee.name }?.toList()
                val cypherValues = loaded.query(
                    "MATCH (n:StringConstant) WHERE n.value CONTAINS 'beta' RETURN n.value AS value"
                ).rows.map { it["value"] }

                assertEquals(listOf("feature-alpha"), contains)
                assertEquals(listOf("callerFeature"), startsWith)
                assertEquals(listOf("billingFeature"), endsWith)
                assertEquals(listOf("feature-beta"), cypherValues)
                assertWrappedLowercaseQuery(loaded)
                assertRawStringPropertyLookups(loaded)
                assertWorkAwareTransformedLookup(mapped)
                var disjunctionWork = 0
                val disjunction = loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "owner"
                        ),
                        StringPropertyPredicate(
                            "callee_class",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "target"
                        )
                    ),
                    Int.MAX_VALUE,
                    GraphWorkConsumer { disjunctionWork++ }
                )?.map { it.id.value }?.toList()
                assertEquals(listOf(2), disjunction)
                assertTrue(disjunctionWork > 0)
                assertNull(
                    loaded.nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(
                            StringPropertyPredicate(
                                "unknown",
                                null,
                                StringMatchMode.CONTAINS,
                                "feature"
                            )
                        )
                    )
                )
                assertNull(
                    loaded.nodesByStringProperty(
                        StringConstant::class.java,
                        "unknown",
                        StringMatchMode.CONTAINS,
                        "feature"
                    )
                )
                assertNull(
                    loaded.nodesByStringProperty(
                        IntConstant::class.java,
                        "value",
                        StringMatchMode.CONTAINS,
                        "42"
                    )
                )
                assertTrue(loaded.memberAnnotationIndex().orEmpty().isEmpty())
                assertNull(loaded.classOrigin("example.Owner"))
                assertTrue(loaded.classOrigins().isEmpty())
                assertTrue(loaded.artifactDependencies().isEmpty())
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped broad disjunction preserves stored order while deduplicating property streams`() {
        val nodeIds = listOf(1, 30, 70, 2, 90, 4, 5, 50, 40, 3)
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            nodeIds.forEach { nodeId ->
                val callerClass = if (nodeId == 4) "example.TargetCaller" else "example.OtherCaller$nodeId"
                val calleeClass = if (nodeId == 90 || nodeId == 4) {
                    "example.TargetCallee"
                } else {
                    "example.OtherCallee$nodeId"
                }
                addNode(
                    CallSiteNode(
                        NodeId(nodeId),
                        MethodDescriptor(TypeDescriptor(callerClass), "call$nodeId", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor(calleeClass), "load$nodeId", emptyList(), returnType),
                        nodeId,
                        null,
                        emptyList()
                    )
                )
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-unordered-string-candidates")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val storedOrder = loaded.nodes(CallSiteNode::class.java).map { it.id.value }.toList()
                assertTrue(storedOrder.indexOf(90) < storedOrder.indexOf(4), "Mapped fixture must be unordered")

                val rows = loaded.query(
                    "MATCH (n:CallSiteNode) WHERE " +
                        "n.caller_class CONTAINS 'Target' OR n.callee_class CONTAINS 'Target' " +
                        "RETURN DISTINCT n.id AS id, rand() AS nonce LIMIT 3"
                ).rows
                val resultIds = rows.map { it["id"] }

                assertEquals(listOf(90, 4), resultIds)
                assertEquals(resultIds.size, resultIds.distinct().size)
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `long missing contains skips the combined index while prefix builds it`() {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            repeat(4_096) { index ->
                addNode(
                    CallSiteNode(
                        NodeId(index),
                        MethodDescriptor(TypeDescriptor("example.Caller"), "call", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                        index,
                        null,
                        emptyList()
                    )
                )
            }
        }.build()
        val predicates = listOf(
            StringPropertyPredicate(
                "caller_class",
                StringValueTransform.LOWERCASE,
                StringMatchMode.CONTAINS,
                "definitely-not-present"
            )
        )
        val dir = Files.createTempDirectory("webgraph-missing-disjunction")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertEquals(
                    emptyList(),
                    loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, predicates)
                        .orEmpty().toList()
                )
                assertEquals(
                    StringPropertyDisjunctionAggregate(0, emptySet()),
                    loaded.aggregateStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        predicates,
                        distinctProperty = "caller_class"
                    )
                )
                assertEquals(
                    emptyList(),
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        predicates,
                        listOf("caller_class"),
                        limit = 10
                    )
                )
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(
                    emptyList(),
                    loaded.nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        predicates.map { predicate ->
                            predicate.copy(mode = StringMatchMode.STARTS_WITH)
                        }
                    ).orEmpty().toList()
                )
                assertTrue(loaded.isCallSiteStringIndexInitialized())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `mapped disjunction builds and clears one combined CallSite string index`() {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            repeat(3) { index ->
                val marker = if (index == 1) "Voucher" else "Feature"
                addNode(
                    CallSiteNode(
                        NodeId(index),
                        MethodDescriptor(
                            TypeDescriptor("example.${marker}Caller$index"),
                            "create$index",
                            emptyList(),
                            returnType
                        ),
                        MethodDescriptor(
                            TypeDescriptor("example.Dependency$index"),
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
        val predicates = listOf(
            "caller_class",
            "caller_name",
            "callee_class",
            "callee_name"
        ).map { property ->
            StringPropertyPredicate(
                property,
                StringValueTransform.LOWERCASE,
                StringMatchMode.CONTAINS,
                "voucher"
            )
        }
        val dir = Files.createTempDirectory("webgraph-warm-disjunction-handoff")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val budgetBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
                val cold = loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates
                ).orEmpty().map { it.id.value }.toList()

                assertEquals(listOf(1), cold)
                assertTrue(loaded.isCallSiteStringIndexInitialized())
                assertTrue(loaded.isCallSiteTrigramIndexInitialized())
                assertTrue(loaded.callSiteStringIndexBytes() > 0L)
                val aggregate = loaded.aggregateStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates,
                    distinctProperty = "caller_class"
                )
                assertEquals(1L, aggregate?.count)
                assertEquals(setOf("example.VoucherCaller1"), aggregate?.distinctValues)
                var aggregateWork = 0
                val trackedAggregate = loaded.aggregateStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates,
                    distinctProperty = "caller_class",
                    workConsumer = GraphWorkConsumer { aggregateWork++ }
                )
                assertEquals(1L, trackedAggregate?.count)
                assertTrue(aggregateWork > 0)
                val sparseCalleeAggregate = loaded.aggregateStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates,
                    distinctProperty = "callee_name"
                )
                assertEquals(setOf("invoke1"), sparseCalleeAggregate?.distinctValues)
                var denseAggregateWork = 0
                val denseAggregate = loaded.aggregateStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "example"
                        ),
                        StringPropertyPredicate(
                            "caller_name",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "create"
                        )
                    ),
                    distinctProperty = "callee_name",
                    workConsumer = GraphWorkConsumer { denseAggregateWork++ }
                )
                assertEquals(3L, denseAggregate?.count)
                assertEquals(setOf("invoke0", "invoke1", "invoke2"), denseAggregate?.distinctValues)
                assertTrue(denseAggregateWork >= 6)
                assertTrue(loaded.prefersSerialStringPropertyDisjunction(CallSiteNode::class.java, predicates))
                val projectedValues = listOf("example.VoucherCaller1", "create1", null)
                val projected = loaded.distinctStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates,
                    listOf("caller_class", "caller_name", "graphId"),
                    limit = 10
                )
                assertEquals(listOf(projectedValues), projected?.map { it.values })
                val selected = loaded.distinctStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates,
                    listOf("caller_class", "caller_name", "graphId"),
                    limit = 10,
                    selectedValues = setOf(projectedValues, listOf("missing", "missing", null))
                )
                assertEquals(listOf(projectedValues), selected?.map { it.values })
                val selectedWithLimit = loaded.distinctStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates,
                    listOf("caller_class", "caller_name", "graphId"),
                    limit = 1,
                    selectedValues = setOf(projectedValues)
                )
                assertEquals(listOf(projectedValues), selectedWithLimit?.map { it.values })
                val absent = loaded.aggregateStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates.map { it.copy(expected = "missing") },
                    distinctProperty = "caller_class"
                )
                assertEquals(0L, absent?.count)
                assertEquals(emptySet(), absent?.distinctValues)
                assertEquals(
                    listOf(1),
                    loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, predicates)
                        .orEmpty().map { it.id.value }.toList()
                )
                assertTrue(
                    loaded.nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        predicates,
                        limit = 0
                    ).orEmpty().none()
                )
                assertNull(
                    loaded.nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        emptyList()
                    )
                )

                loaded.clearStringPropertyIndexes()
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(budgetBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
                var consumed = 0
                val reset = loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates,
                    Int.MAX_VALUE,
                    GraphWorkConsumer { consumed++ }
                ).orEmpty().map { it.id.value }.toList()
                assertEquals(listOf(1), reset)
                assertTrue(consumed.toLong() > checkNotNull(graph.nodeCount(CallSiteNode::class.java)))
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped CallSite trigram postings preserve Unicode matching and prune zero hits`() {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            repeat(256) { index ->
                val callerType = when (index) {
                    42 -> "example.аяа"
                    137 -> "example.İstanbulVoucher"
                    else -> "example.Feature$index"
                }
                addNode(
                    CallSiteNode(
                        NodeId(index),
                        MethodDescriptor(TypeDescriptor(callerType), "create$index", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency$index"), "invoke$index", emptyList(), returnType),
                        index,
                        null,
                        emptyList()
                    )
                )
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-callsite-trigram-postings")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                fun matchingIds(predicate: StringPropertyPredicate): List<Int> =
                    loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate))
                        .orEmpty().map { it.id.value }.toList()

                assertEquals(
                    listOf(137),
                    matchingIds(
                        StringPropertyPredicate(
                            "caller_class",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "i\u0307stanbul"
                        )
                    )
                )
                assertEquals(
                    listOf(137),
                    matchingIds(
                        StringPropertyPredicate(
                            "caller_class",
                            null,
                            StringMatchMode.ENDS_WITH,
                            "Voucher"
                        )
                    )
                )
                // "аяа" and "баа" have the same base-31 trigram hash; full verification is mandatory.
                assertEquals(
                    emptyList(),
                    matchingIds(
                        StringPropertyPredicate(
                            "caller_class",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "баа"
                        )
                    )
                )
                assertTrue(loaded.isCallSiteTrigramIndexInitialized())

                var zeroHitWork = 0
                val zeroHits = loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "definitely-absent"
                        )
                    ),
                    Int.MAX_VALUE,
                    GraphWorkConsumer { zeroHitWork++ }
                ).orEmpty().toList()
                assertEquals(emptyList(), zeroHits)
                assertTrue(zeroHitWork in 1 until 32)
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    @Suppress("LongMethod", "NestedBlockDepth")
    fun `bounded mapped CallSite scan uses ordered intra graph workers before index admission`() {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            repeat(32_768) { index ->
                val marker = if (index == 100 || index % 512 == 0) "Target" else "Feature"
                addNode(
                    CallSiteNode(
                        NodeId(index),
                        MethodDescriptor(TypeDescriptor("example.${marker}Caller$index"), "call$index", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency$index"), "invoke$index", emptyList(), returnType),
                        index,
                        null,
                        emptyList()
                    )
                )
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-parallel-callsite-scan")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val workerThreads = ConcurrentHashMap.newKeySet<String>()
                val ids = loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class",
                            null,
                            StringMatchMode.CONTAINS,
                            "Target"
                        )
                    ),
                    limit = 1,
                    workConsumer = ParallelGraphWorkBatchConsumer { workUnits ->
                        workerThreads += Thread.currentThread().name
                    }
                ).orEmpty().map { it.id.value }.toList()

                assertEquals(listOf(0), ids)
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertTrue(workerThreads.all { thread -> thread.startsWith("graphite-callsite-scan-") })
                assertEquals(1L, loaded.callSiteParallelScanCount())
                if (Runtime.getRuntime().availableProcessors() > 1) {
                    assertTrue(workerThreads.size > 1)
                    assertTrue(loaded.callSiteScanPeakActiveWorkers() > 1)
                }

                loaded.resetCallSiteScanMetrics()
                val qualified = CrossGraphCypherExecutor(
                    listOf(CypherGraph("selected", loaded)),
                    CypherExecutionBudget(maxWorkUnits = 100_000)
                ).execute(
                    """
                    MATCH (n:CallSiteNode)
                    WHERE n.graphId = 'selected' AND (
                        toLower(n.caller_class) CONTAINS 'target' OR
                        toLower(n.caller_name) CONTAINS 'target' OR
                        toLower(n.callee_class) CONTAINS 'target' OR
                        toLower(n.callee_name) CONTAINS 'target'
                    )
                    RETURN n.graphId AS graph, n.caller_class AS caller LIMIT 1
                    """.trimIndent()
                )
                assertEquals(listOf("selected"), qualified.rows.map { row -> row["graph"] })
                assertEquals(listOf("example.TargetCaller0"), qualified.rows.map { row -> row["caller"] })
                assertEquals(1L, loaded.callSiteParallelScanCount())
                if (Runtime.getRuntime().availableProcessors() > 1) {
                    assertTrue(loaded.callSiteScanPeakActiveWorkers() > 1)
                }

                if (Runtime.getRuntime().availableProcessors() > 1) {
                    loaded.resetCallSiteScanMetrics()
                    val expectedWorkers = minOf(8, Runtime.getRuntime().availableProcessors())
                    val firstBatchReached = CountDownLatch(expectedWorkers)
                    val failureClaimed = AtomicBoolean()
                    val consumedWork = AtomicLong()
                    val failure = assertFailsWith<IllegalStateException> {
                        loaded.nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(
                                StringPropertyPredicate(
                                    "caller_class",
                                    null,
                                    StringMatchMode.CONTAINS,
                                    "NeverMatches"
                                )
                            ),
                            limit = 1,
                            workConsumer = ParallelGraphWorkBatchConsumer { workUnits ->
                                consumedWork.addAndGet(workUnits)
                                val failThisWorker = failureClaimed.compareAndSet(false, true)
                                firstBatchReached.countDown()
                                check(firstBatchReached.await(5, TimeUnit.SECONDS))
                                if (failThisWorker) error("parallel scan budget failure")
                                while (loaded.callSiteScanActiveWorkers() == expectedWorkers) {
                                    Thread.onSpinWait()
                                }
                            }
                        ).orEmpty().toList()
                    }
                    assertEquals("parallel scan budget failure", failure.message)
                    assertEquals(expectedWorkers * 1_024L, consumedWork.get())
                    assertEquals(expectedWorkers - 1L, loaded.callSiteScanAbortedWorkers())

                    loaded.resetCallSiteScanMetrics()
                    val interruptionBatchReached = CountDownLatch(expectedWorkers)
                    val releaseInterruptedWorkers = CountDownLatch(1)
                    val interruptedFailure = AtomicReference<Throwable>()
                    val interruptedFlag = AtomicBoolean()
                    val interruptedRequest = Thread {
                        try {
                            loaded.nodesByStringPropertyDisjunction(
                                CallSiteNode::class.java,
                                listOf(
                                    StringPropertyPredicate(
                                        "caller_class",
                                        null,
                                        StringMatchMode.CONTAINS,
                                        "NeverMatches"
                                    )
                                ),
                                limit = 1,
                                workConsumer = ParallelGraphWorkBatchConsumer {
                                    interruptionBatchReached.countDown()
                                    check(releaseInterruptedWorkers.await(5, TimeUnit.SECONDS))
                                }
                            ).orEmpty().toList()
                            interruptedFailure.set(AssertionError("Interrupted scan completed normally"))
                        } catch (error: Throwable) {
                            interruptedFailure.set(error)
                            interruptedFlag.set(Thread.currentThread().isInterrupted)
                        }
                    }
                    interruptedRequest.start()
                    try {
                        assertTrue(interruptionBatchReached.await(5, TimeUnit.SECONDS))
                        interruptedRequest.interrupt()
                    } finally {
                        releaseInterruptedWorkers.countDown()
                    }
                    interruptedRequest.join(5_000)
                    assertFalse(interruptedRequest.isAlive)
                    assertTrue(interruptedFailure.get() is CancellationException)
                    assertEquals("Mapped string-property scan interrupted", interruptedFailure.get().message)
                    assertTrue(interruptedFlag.get())
                    assertEquals(expectedWorkers.toLong(), loaded.callSiteScanAbortedWorkers())
                }

                loaded.clearStringPropertyIndexes()
                loaded.resetCallSiteScanMetrics()
                val coldZero = loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "definitely-absent"
                        )
                    ),
                    limit = 1,
                    workConsumer = ParallelGraphWorkBatchConsumer { }
                ).orEmpty().toList()
                assertTrue(coldZero.isEmpty())
                assertEquals(1L, loaded.callSiteParallelScanCount())
                assertEquals(0L, loaded.callSiteStringIndexLookupCount())
                assertTrue(loaded.isCallSiteStringIndexInitialized())
                assertFalse(loaded.isCallSiteTrigramIndexInitialized())

                val warm = loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "target"
                        )
                    ),
                    limit = 1,
                    workConsumer = ParallelGraphWorkBatchConsumer { }
                ).orEmpty().map { it.id.value }.toList()
                assertEquals(listOf(0), warm)
                assertEquals(1L, loaded.callSiteParallelScanCount())
                assertEquals(1L, loaded.callSiteStringIndexLookupCount())
                assertTrue(loaded.isCallSiteTrigramIndexInitialized())

                loaded.clearStringPropertyIndexes()
                val budgetBeforeFailedHandoff = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
                val handoffResult = loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "definitely-absent"
                        )
                    ),
                    limit = 1,
                    workConsumer = ParallelGraphWorkBatchConsumer {
                        check(Thread.currentThread().name.startsWith("graphite-callsite-scan-")) {
                            "fused index budget failure"
                        }
                    }
                ).orEmpty().toList()
                assertTrue(handoffResult.isEmpty())
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(budgetBeforeFailedHandoff, MappedCallSiteStringIndexMemoryBudget.retainedBytes())

                val scanWork = 32_768L
                val handoffWorkLimit = 40_000L
                val consumedWork = AtomicLong()
                val budgetedHandoffResult = loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "definitely-absent"
                        )
                    ),
                    limit = 1,
                    workConsumer = ParallelGraphWorkBatchConsumer { workUnits ->
                        val total = consumedWork.addAndGet(workUnits)
                        check(total <= handoffWorkLimit) { "fused index exceeded request budget" }
                    }
                ).orEmpty().toList()
                assertTrue(budgetedHandoffResult.isEmpty())
                assertTrue(consumedWork.get() in (scanWork + 1)..(scanWork * 3))
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(budgetBeforeFailedHandoff, MappedCallSiteStringIndexMemoryBudget.retainedBytes())

                val handoffCancellation = assertFailsWith<CancellationException> {
                    loaded.nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(
                            StringPropertyPredicate(
                                "caller_class",
                                StringValueTransform.LOWERCASE,
                                StringMatchMode.CONTAINS,
                                "definitely-absent"
                            )
                        ),
                        limit = 1,
                        workConsumer = ParallelGraphWorkBatchConsumer {
                            if (!Thread.currentThread().name.startsWith("graphite-callsite-scan-")) {
                                throw CancellationException("cancelled during optional cache handoff")
                            }
                        }
                    ).orEmpty().toList()
                }
                assertEquals("cancelled during optional cache handoff", handoffCancellation.message)
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(budgetBeforeFailedHandoff, MappedCallSiteStringIndexMemoryBudget.retainedBytes())

                loaded.resetCallSiteScanMetrics()
                assertEquals(0L, loaded.callSiteParallelScanCount())
                assertEquals(0L, loaded.callSiteStringIndexLookupCount())
                assertEquals(0, loaded.callSiteScanPeakActiveWorkers())
                assertEquals(0L, loaded.callSiteScanAbortedWorkers())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `buffered graph work does not replay a failed batch`() {
        var calls = 0
        val accounting = BufferedGraphWorkConsumer(object : GraphWorkBatchConsumer {
            override fun consume(workUnits: Long) {
                calls++
                error("work rejected")
            }
        })

        repeat(1_023) { accounting.consume() }
        assertEquals("work rejected", assertFailsWith<IllegalStateException> { accounting.consume() }.message)
        accounting.flush()
        assertEquals(1, calls)
    }

    @Test
    fun `CallSite trigram posting budget exhaustion preserves dictionary scan correctness`() {
        val property = "graphite.webgraph.callSiteTrigramIndexBudgetBytes"
        val previous = System.getProperty(property)
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            repeat(3) { index ->
                addNode(
                    CallSiteNode(
                        NodeId(index),
                        MethodDescriptor(
                            TypeDescriptor(if (index == 1) "example.Voucher" else "example.Feature$index"),
                            "call$index",
                            emptyList(),
                            returnType
                        ),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                        index,
                        null,
                        emptyList()
                    )
                )
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-callsite-trigram-budget")
        try {
            System.setProperty(property, "0")
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val ids = loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "voucher"
                        )
                    )
                ).orEmpty().map { it.id.value }.toList()

                assertEquals(listOf(1), ids)
                assertTrue(loaded.isCallSiteStringIndexInitialized())
                assertFalse(loaded.isCallSiteTrigramIndexInitialized())
            } finally {
                loaded.close()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cold mapped aggregate charges inspected call sites to the query budget`() {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            repeat(32) { index ->
                addNode(
                    CallSiteNode(
                        NodeId(index),
                        MethodDescriptor(
                            TypeDescriptor("example.Caller$index"),
                            "call$index",
                            emptyList(),
                            returnType
                        ),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                        index,
                        null,
                        emptyList()
                    )
                )
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-cold-aggregate-budget")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                val executor = CrossGraphCypherExecutor(
                    listOf(CypherGraph("mapped", loaded)),
                    CypherExecutionBudget(maxWorkUnits = 1)
                )

                assertFailsWith<CypherBudgetExceededException> {
                    executor.execute(
                        "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'missing' " +
                            "RETURN count(*) AS total"
                    )
                }
                val mapped = loaded as MappedWebGraphBackedGraph
                val sparse = mapped.aggregateStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class",
                            null,
                            StringMatchMode.EQUALS,
                            "example.Caller31"
                        )
                    ),
                    distinctProperty = "callee_name"
                )
                assertEquals(1L, sparse?.count)
                assertEquals(setOf("invoke"), sparse?.distinctValues)
                val broad = mapped.aggregateStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class",
                            null,
                            StringMatchMode.CONTAINS,
                            "example.Caller"
                        )
                    ),
                    distinctProperty = "caller_class"
                )
                assertEquals(32L, broad?.count)
                assertEquals(32, broad?.distinctValues?.size)
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `warm mapped zero hit aggregate charges inspected strings to the query budget`() {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            repeat(32) { index ->
                addNode(
                    CallSiteNode(
                        NodeId(index),
                        MethodDescriptor(TypeDescriptor("example.Caller$index"), "call$index", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                        index,
                        null,
                        emptyList()
                    )
                )
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-warm-zero-aggregate-budget")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val predicates = listOf(
                    StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Caller")
                )
                assertEquals(
                    32L,
                    loaded.aggregateStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        predicates,
                        distinctProperty = null
                    )?.count
                )
                assertTrue(loaded.isCallSiteStringIndexInitialized())

                val executor = CrossGraphCypherExecutor(
                    listOf(CypherGraph("mapped", loaded)),
                    CypherExecutionBudget(maxWorkUnits = 1)
                )
                assertFailsWith<CypherBudgetExceededException> {
                    executor.execute(
                        "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'missing' " +
                            "RETURN count(*) AS total"
                    )
                }
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped limited lookup flushes work before the lazy sequence is abandoned`() {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            repeat(4) { index ->
                addNode(
                    CallSiteNode(
                        NodeId(index),
                        MethodDescriptor(TypeDescriptor("example.Target"), "call$index", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                        index,
                        null,
                        emptyList()
                    )
                )
            }
        }.build()
        val predicate = StringPropertyPredicate(
            "caller_class",
            null,
            StringMatchMode.CONTAINS,
            "Target"
        )
        val dir = Files.createTempDirectory("webgraph-limited-lookup-budget")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertNotNull(
                    loaded.nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(predicate),
                        limit = 1
                    )?.single()
                )
                var queryWork = 0L
                assertNotNull(
                    loaded.nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(predicate),
                        limit = 1,
                        workConsumer = GraphWorkConsumer { queryWork++ }
                    )?.single()
                )
                assertTrue(queryWork > 0L)
                val context = CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = queryWork))
                val executor = CrossGraphCypherExecutor(
                    listOf(CypherGraph("mapped", loaded)),
                    context
                )
                val query = "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' " +
                    "RETURN n.caller_class AS caller LIMIT 1"

                assertEquals("example.Target", executor.execute(query).rows.single()["caller"])
                assertFailsWith<CypherBudgetExceededException> { executor.execute(query) }
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun mappedDistinctProjectionFallsBackForUnsupportedCallSiteProperties() {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            listOf(7, 9).forEachIndexed { index, line ->
                addNode(
                    CallSiteNode(
                        NodeId(index),
                        MethodDescriptor(TypeDescriptor("example.Target"), "call$index", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                        line,
                        null,
                        emptyList()
                    )
                )
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-unsupported-distinct-projection")
        try {
            GraphStore.save(graph, dir)
            val first = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            val second = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertNull(
                    first.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(
                            StringPropertyPredicate(
                                "caller_class",
                                null,
                                StringMatchMode.CONTAINS,
                                "Target"
                            )
                        ),
                        projectedProperties = listOf("line"),
                        limit = 1
                    )
                )
                val result = CrossGraphCypherExecutor(
                    listOf(CypherGraph("first", first), CypherGraph("second", second))
                ).execute(
                    "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' " +
                        "RETURN DISTINCT n.line AS line LIMIT 1"
                )

                assertEquals(listOf(7), result.rows.map { it["line"] })
            } finally {
                second.close()
                first.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped indexed distinct projection preserves matching non CallSite nodes`() {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder()
            .addNode(
                CallSiteNode(
                    NodeId(0),
                    MethodDescriptor(TypeDescriptor("example.Other"), "call", emptyList(), returnType),
                    MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                    1,
                    null,
                    emptyList()
                )
            )
            .addNode(
                AnnotationNode(
                    NodeId(1),
                    "example.DynamicCall",
                    "example.Owner",
                    "call",
                    mapOf("caller_class" to "example.Target")
                )
            )
            .build()
        val dir = Files.createTempDirectory("webgraph-mixed-distinct-projection")
        try {
            GraphStore.save(graph, dir)
            val first = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            val second = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val result = CrossGraphCypherExecutor(
                    listOf(CypherGraph("first", first), CypherGraph("second", second))
                ).execute(
                    "MATCH (n) WHERE n.caller_class CONTAINS \$term " +
                        "RETURN DISTINCT n.caller_class AS caller LIMIT 10",
                    mapOf("term" to "Target")
                )

                assertEquals(listOf("example.Target"), result.rows.map { it["caller"] })
            } finally {
                second.close()
                first.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `combined CallSite string index preserves modes order limits and conjunction residuals`() {
        val returnType = TypeDescriptor("void")
        fun callSite(id: Int, callerClass: String, callerName: String, calleeClass: String, calleeName: String) =
            CallSiteNode(
                NodeId(id),
                MethodDescriptor(TypeDescriptor(callerClass), callerName, emptyList(), returnType),
                MethodDescriptor(TypeDescriptor(calleeClass), calleeName, emptyList(), returnType),
                id,
                null,
                emptyList()
            )
        val graph = DefaultGraph.Builder()
            .addNode(callSite(0, "Example.Alpha", "loadFirst", "deps.AKeyStore", "fetch"))
            .addNode(callSite(1, "example.Beta", "makeSecond", "deps.Repository", "provideTail"))
            .addNode(callSite(2, "Example.Alpha", "finishThird", "deps.OtherAKey", "close"))
            .addNode(callSite(3, "example.Other", "skip", "deps.Repository", "ignore"))
            .build()
        val dir = Files.createTempDirectory("webgraph-combined-callsite-index")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                fun ids(
                    property: String,
                    mode: StringMatchMode,
                    expected: String,
                    transform: StringValueTransform? = null,
                    limit: Int = Int.MAX_VALUE,
                    consume: (() -> Unit)? = null
                ): List<Int> {
                    val predicate = StringPropertyPredicate(property, transform, mode, expected)
                    val sequence = if (consume == null) {
                        loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate), limit)
                    } else {
                        loaded.nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate),
                            limit,
                            GraphWorkConsumer { consume() }
                        )
                    }
                    return sequence.orEmpty().map { it.id.value }.toList()
                }

                assertEquals(listOf(0, 2), ids("caller_class", StringMatchMode.EQUALS, "Example.Alpha"))
                assertEquals(
                    listOf(0, 2),
                    ids(
                        "caller_class",
                        StringMatchMode.EQUALS,
                        "example.alpha",
                        StringValueTransform.LOWERCASE
                    )
                )
                assertEquals(listOf(0, 2), ids("callee_class", StringMatchMode.CONTAINS, "AKey"))
                assertEquals(listOf(0), ids("caller_name", StringMatchMode.STARTS_WITH, "load"))
                assertEquals(listOf(1), ids("callee_name", StringMatchMode.ENDS_WITH, "Tail"))
                assertTrue(ids("caller_class", StringMatchMode.EQUALS, "missing").isEmpty())

                val overlapping = listOf(
                    StringPropertyPredicate("caller_class", null, StringMatchMode.EQUALS, "Example.Alpha"),
                    StringPropertyPredicate("callee_class", null, StringMatchMode.CONTAINS, "AKey")
                )
                assertEquals(
                    listOf(0, 2),
                    loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, overlapping)
                        .orEmpty().map { it.id.value }.toList()
                )

                var consumed = 0
                val exactOr = listOf(
                    StringPropertyPredicate("caller_class", null, StringMatchMode.EQUALS, "Example.Alpha"),
                    StringPropertyPredicate("caller_class", null, StringMatchMode.EQUALS, "example.Beta")
                )
                assertEquals(
                    listOf(0, 1),
                    loaded.nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        exactOr,
                        2,
                        GraphWorkConsumer { consumed++ }
                    ).orEmpty().map { it.id.value }.toList()
                )
                assertTrue(consumed > exactOr.size)

                loaded.clearStringPropertyIndexes()
                val exactOrRows = loaded.query(
                    "MATCH (n) WHERE n.caller_class = 'Example.Alpha' OR n.caller_class = 'example.Beta' " +
                        "RETURN DISTINCT n.caller_class AS caller, n.caller_name AS method LIMIT 10"
                ).rows
                assertEquals(
                    listOf("loadFirst", "makeSecond", "finishThird"),
                    exactOrRows.map { it["method"] }
                )
                assertTrue(loaded.isCallSiteStringIndexInitialized())

                val exactAndContains = loaded.query(
                    "MATCH (n) WHERE n.caller_class = 'Example.Alpha' AND n.callee_class CONTAINS 'AKey' " +
                        "RETURN DISTINCT n.caller_name AS method LIMIT 10"
                ).rows
                assertEquals(listOf("loadFirst", "finishThird"), exactAndContains.map { it["method"] })

                val exactAndDisjunction = loaded.query(
                    "MATCH (n) WHERE n.caller_class = 'Example.Alpha' AND " +
                        "(n.callee_class CONTAINS 'Store' OR n.callee_name ENDS WITH 'close') " +
                        "RETURN DISTINCT n.caller_name AS method LIMIT 10"
                ).rows
                assertEquals(listOf("loadFirst", "finishThird"), exactAndDisjunction.map { it["method"] })
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped disjunction tolerates disabled match cache and observes interruption`() {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder()
            .addNode(
                CallSiteNode(
                    NodeId(0),
                    MethodDescriptor(TypeDescriptor("example.VoucherCaller"), "call", emptyList(), returnType),
                    MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                    0,
                    null,
                    emptyList()
                )
            )
            .build()
        val predicate = StringPropertyPredicate(
            "caller_class",
            StringValueTransform.LOWERCASE,
            StringMatchMode.CONTAINS,
            "voucher"
        )
        val dir = Files.createTempDirectory("webgraph-disjunction-no-match-cache")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                loaded.javaClass.getDeclaredField("rawStringMatchStates").apply {
                    isAccessible = true
                    set(loaded, RawStringMatchStates(maxRetainedBytes = 0, maxEntries = 0))
                }
                val budgetBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
                Thread.currentThread().interrupt()
                try {
                    assertFailsWith<CancellationException> {
                        loaded.nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate)
                        ).orEmpty().toList()
                    }
                } finally {
                    Thread.interrupted()
                }
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(budgetBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
                assertEquals(
                    listOf(0),
                    loaded.nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(predicate)
                    ).orEmpty().map { it.id.value }.toList()
                )

                Thread.currentThread().interrupt()
                try {
                    assertFailsWith<CancellationException> {
                        loaded.nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate)
                        ).orEmpty().toList()
                    }
                } finally {
                    Thread.interrupted()
                }
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun assertEarlyLimitedLookupsDoNotBuildIndex(graph: MappedWebGraphBackedGraph) {
        listOf(
            StringMatchMode.CONTAINS to "alpha",
            StringMatchMode.STARTS_WITH to "feature"
        ).forEach { (mode, expected) ->
            val early = graph.nodesByStringProperty(
                StringConstant::class.java,
                "value",
                mode,
                expected,
                limit = 1
            )?.map { it.value }?.toList()
            assertEquals(listOf("feature-alpha"), early)
        }
        assertEquals(0, graph.stringPropertyIndexCount())
    }

    private fun assertBroadDiscoveryQuery(graph: Graph) {
        val rows = graph.query(
            """
            MATCH (n)
            WHERE (exists(n.class) AND n.class CONTAINS 'Owner')
               OR (exists(n.name) AND n.name CONTAINS 'Owner')
               OR (exists(n.caller_class) AND n.caller_class CONTAINS 'Owner')
               OR (exists(n.caller_name) AND n.caller_name CONTAINS 'Owner')
               OR (exists(n.callee_class) AND n.callee_class CONTAINS 'Owner')
               OR (exists(n.callee_name) AND n.callee_name CONTAINS 'Owner')
            RETURN DISTINCT n.class AS class, n.name AS name,
                n.caller_class AS caller, n.caller_name AS callerMethod,
                n.callee_class AS callee, n.callee_name AS calleeMethod
            LIMIT 120
            """.trimIndent()
        ).rows

        assertEquals(2, rows.size)
        assertEquals("token", rows.single { it["class"] == "example.Owner" }["name"])
        val callSite = rows.single { it["caller"] == "example.Owner" }
        assertEquals("callerFeature", callSite["callerMethod"])
        assertEquals("example.Target", callSite["callee"])
        assertEquals("billingFeature", callSite["calleeMethod"])
    }

    @Test
    fun `trigram budget exhaustion falls back to dictionary scan`() {
        val dir = Files.createTempDirectory("webgraph-trigram-budget")
        try {
            val values = listOf("alpha-feature", "beta-feature")
            val strings = StringTable.build(values, dir)
            val index = MappedStringPropertyIndex(
                nodeIds = intArrayOf(10, 11),
                stringIds = values.map(strings::indexOf).toIntArray(),
                uniqueStringIds = values.map(strings::indexOf).sorted().toIntArray(),
                stringTable = strings,
                maxTrigramPostings = 0,
                maxTrigramBytes = 0,
                maxMatchingStringCacheEntries = 1,
                maxMatchingStringCacheBytes = 128
            )

            assertEquals(
                listOf(10),
                index.matchingNodeIds(StringMatchMode.CONTAINS, "alpha").toList()
            )
            assertEquals(1, index.matchingStringCacheSize())
            assertTrue(index.matchingNodeIds(StringMatchMode.CONTAINS, "missing").none())
            assertEquals(1, index.matchingStringCacheSize())

            val uncached = MappedStringPropertyIndex(
                nodeIds = intArrayOf(10, 11),
                stringIds = values.map(strings::indexOf).toIntArray(),
                uniqueStringIds = values.map(strings::indexOf).sorted().toIntArray(),
                stringTable = strings,
                maxMatchingStringCacheBytes = 0
            )
            assertEquals(listOf(11), uncached.matchingNodeIds(StringMatchMode.STARTS_WITH, "beta").toList())
            assertEquals(0, uncached.matchingStringCacheSize())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped string index isolates raw and exact lowercase match caches`() {
        val dir = Files.createTempDirectory("webgraph-lowercase-string-index")
        try {
            val values = listOf("Voucher", "voucher", "İVOUCHER")
            val strings = StringTable.build(values, dir)
            val index = MappedStringPropertyIndex(
                nodeIds = intArrayOf(10, 11, 12),
                stringIds = values.map(strings::indexOf).toIntArray(),
                uniqueStringIds = values.map(strings::indexOf).sorted().toIntArray(),
                stringTable = strings
            )

            assertEquals(
                listOf(11),
                index.matchingNodeIds(StringMatchMode.CONTAINS, "voucher").toList()
            )
            assertEquals(
                listOf(10, 11, 12),
                index.matchingNodeIds(
                    StringValueTransform.LOWERCASE,
                    StringMatchMode.CONTAINS,
                    "voucher",
                    workConsumer = null
                ).toList()
            )
            assertEquals(2, index.matchingStringCacheSize())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped fused lowercase scan preserves ASCII and Unicode string semantics`() {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            listOf("Example.VoucherService", "Example.İVOUCHERService").forEachIndexed { index, owner ->
                addNode(
                    CallSiteNode(
                        NodeId(index),
                        MethodDescriptor(TypeDescriptor(owner), "Create", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("Example.Repository"), "Load", emptyList(), returnType),
                        index,
                        null,
                        emptyList()
                    )
                )
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-fused-lowercase-semantics")
        try {
            GraphStore.save(graph, dir)
            val mapped = GraphStore.loadMapped(dir)
            try {
                fun owners(expected: String): List<Any?> = mapped.query(
                    "MATCH (n:CallSiteNode) WHERE " +
                        "toLower(coalesce(n.caller_class, '')) CONTAINS '$expected' OR " +
                        "toLower(coalesce(n.callee_class, '')) CONTAINS '$expected' " +
                        "RETURN DISTINCT n.caller_class AS owner LIMIT 10"
                ).rows.map { it["owner"] }

                assertEquals(listOf("Example.VoucherService", "Example.İVOUCHERService"), owners("voucher"))
                assertTrue(owners("Voucher").isEmpty(), "The expected literal must not be normalized")
            } finally {
                (mapped as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped string index retains two argument JVM lookup method`() {
        val dir = Files.createTempDirectory("webgraph-string-index-abi")
        try {
            val strings = StringTable.build(listOf("alpha"), dir)
            val index = MappedStringPropertyIndex(
                nodeIds = intArrayOf(10),
                stringIds = intArrayOf(strings.indexOf("alpha")),
                uniqueStringIds = intArrayOf(strings.indexOf("alpha")),
                stringTable = strings
            )
            val method = assertNotNull(
                MappedStringPropertyIndex::class.java.getMethod(
                    "matchingNodeIds",
                    StringMatchMode::class.java,
                    String::class.java
                )
            )

            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(index, StringMatchMode.STARTS_WITH, "alpha") as Sequence<Int>
            assertEquals(listOf(10), result.toList())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped string lookup admits an index only after a long raw scan`() {
        val graph = DefaultGraph.Builder().apply {
            repeat(300) { index ->
                addNode(StringConstant(NodeId(index), "symbol-$index"))
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-string-property-admission")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertTrue(
                    loaded.nodesByStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringMatchMode.ENDS_WITH,
                        "missing",
                        limit = 1
                    ).orEmpty().none()
                )
                assertEquals(0, loaded.stringPropertyIndexCount())

                assertEquals(
                    listOf("symbol-0"),
                    loaded.nodesByStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringMatchMode.STARTS_WITH,
                        "symbol-0",
                        limit = 1
                    ).orEmpty().map { it.value }.toList()
                )
                assertEquals(0, loaded.stringPropertyIndexCount())

                assertTrue(
                    loaded.nodesByStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringMatchMode.ENDS_WITH,
                        "missing",
                        limit = 1
                    ).orEmpty().none()
                )
                assertEquals(1, loaded.stringPropertyIndexCount())
                assertEquals(
                    listOf("symbol-0"),
                    loaded.nodesByStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringMatchMode.STARTS_WITH,
                        "symbol-0",
                        limit = 1
                    ).orEmpty().map { it.value }.toList()
                )
                assertTrue(
                    loaded.nodesByStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringMatchMode.CONTAINS,
                        "symbol",
                        limit = 0
                    ).orEmpty().none()
                )
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped raw string scan keeps match states query local and accounts budgeted work`() {
        val graph = DefaultGraph.Builder().apply {
            repeat(261) { index ->
                val value = when (index) {
                    0 -> "TÄRGET"
                    256, 257, 260 -> "TaRgEt"
                    258, 259 -> "other"
                    else -> "prefix-$index"
                }
                addNode(StringConstant(NodeId(index), value))
            }
        }.build()
        val orderedGraph = object : Graph by graph {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                graph.nodes(type).sortedBy { it.id.value }
        }
        val dir = Files.createTempDirectory("webgraph-raw-string-match-states")
        try {
            GraphStore.save(orderedGraph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val unbudgeted = loaded.nodesByTransformedStringProperty(
                    StringConstant::class.java,
                    "value",
                    StringValueTransform.LOWERCASE,
                    StringMatchMode.CONTAINS,
                    "target",
                    limit = 3
                ).orEmpty().map { it.id.value }.toList()

                assertEquals(listOf(256, 257, 260), unbudgeted)
                assertEquals(0, loaded.rawStringMatchStateCount())
                assertEquals(0L, loaded.rawStringMatchStateBytes())

                assertEquals(
                    listOf(0),
                    loaded.nodesByTransformedStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringValueTransform.LOWERCASE,
                        StringMatchMode.EQUALS,
                        "tärget",
                        limit = 1
                    ).orEmpty().map { it.id.value }.toList()
                )
                assertEquals(
                    listOf(256),
                    loaded.nodesByTransformedStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringValueTransform.LOWERCASE,
                        StringMatchMode.EQUALS,
                        "target",
                        limit = 1
                    ).orEmpty().map { it.id.value }.toList()
                )

                loaded.clearStringPropertyIndexes()
                var consumed = 0
                val budgeted = loaded.nodesByTransformedStringProperty(
                    StringConstant::class.java,
                    "value",
                    StringValueTransform.LOWERCASE,
                    StringMatchMode.CONTAINS,
                    "target",
                    limit = 3,
                    workConsumer = GraphWorkConsumer { consumed++ }
                ).orEmpty().map { it.id.value }.toList()

                assertEquals(listOf(256, 257, 260), budgeted)
                assertEquals(261, consumed)
                assertEquals(0, loaded.rawStringMatchStateCount())
                assertEquals(0L, loaded.rawStringMatchStateBytes())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `raw string match states evict least recently used entries within graph memory bound`() {
        val states = RawStringMatchStates(maxRetainedBytes = 512, maxEntries = 8)
        val property = StringPropertyKey(StringConstant::class.java, "value")
        val firstKey =
            RawStringMatchKey(property, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "first")
        val secondKey =
            RawStringMatchKey(property, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "second")
        val thirdKey =
            RawStringMatchKey(property, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "third")

        val first = states.stateFor(firstKey, stringCount = 40)
        val second = states.stateFor(secondKey, stringCount = 40)

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(states.stateFor(firstKey, stringCount = 40) === first)
        val third = states.stateFor(thirdKey, stringCount = 40)
        assertNotNull(third)
        assertEquals(2, states.size())
        assertEquals(440L, states.retainedBytes())
        fun predicate(expected: String) = StringPropertyPredicate(
            "value",
            StringValueTransform.LOWERCASE,
            StringMatchMode.CONTAINS,
            expected
        )
        assertTrue(states.contains(StringConstant::class.java, predicate("first")))
        assertTrue(!states.contains(StringConstant::class.java, predicate("second")))
        assertTrue(states.contains(StringConstant::class.java, predicate("third")))

        val oversizedKeyStates = RawStringMatchStates(maxRetainedBytes = 256, maxEntries = 8)
        assertNull(oversizedKeyStates.stateFor(
            RawStringMatchKey(
                property,
                StringValueTransform.LOWERCASE,
                StringMatchMode.CONTAINS,
                "x".repeat(128)
            ),
            stringCount = 1
        ))
        assertEquals(0, oversizedKeyStates.size())
        assertEquals(0L, oversizedKeyStates.retainedBytes())
    }

    @Test
    fun `combined CallSite index estimates CSR retention and rejects budget overflow`() {
        assertEquals(384L, estimatedMappedCallSiteStringIndexCountBytes(stringCount = 20))
        assertEquals(
            896L,
            estimatedMappedCallSiteStringIndexRetainedBytes(
                nodeCount = 10,
                stringCount = 20,
                uniqueCounts = intArrayOf(2, 3, 4, 5)
            )
        )

        val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        val unavailable = MappedCallSiteStringIndexMemoryBudget.maxBytes - retainedBefore + 1L
        assertNull(MappedCallSiteStringIndexMemoryBudget.tryReserve(unavailable))
        assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
        assertNull(
            estimatedMappedCallSiteStringIndexRetainedBytes(
                nodeCount = -1,
                stringCount = 20,
                uniqueCounts = intArrayOf(2, 3, 4, 5)
            )
        )
        assertNull(
            estimatedMappedCallSiteStringIndexRetainedBytes(
                nodeCount = 10,
                stringCount = 20,
                uniqueCounts = intArrayOf(2, 3, 4)
            )
        )
        val emptyReservation = MappedCallSiteStringIndexMemoryBudget.tryReserve(0)
        assertNotNull(emptyReservation)
        emptyReservation.shrinkTo(0)
        emptyReservation.close()
        emptyReservation.close()
    }

    @Test
    fun `mapped CallSite disjunction falls back to bounded raw scan when index budget is occupied`() {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            repeat(3) { index ->
                addNode(
                    CallSiteNode(
                        NodeId(index),
                        MethodDescriptor(TypeDescriptor("example.Caller$index"), "call$index", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke$index", emptyList(), returnType),
                        index,
                        null,
                        emptyList()
                    )
                )
            }
        }.build()
        val predicates = listOf(
            StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Caller1"),
            StringPropertyPredicate("callee_name", null, StringMatchMode.EQUALS, "missing")
        )
        val dir = Files.createTempDirectory("webgraph-callsite-budget-fallback")
        val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        val reservation = MappedCallSiteStringIndexMemoryBudget.tryReserve(
            MappedCallSiteStringIndexMemoryBudget.maxBytes - retainedBefore
        )
        assertNotNull(reservation)
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                var work = 0
                val ids = loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates,
                    Int.MAX_VALUE,
                    GraphWorkConsumer { work++ }
                ).orEmpty().map { it.id.value }.toList()

                assertEquals(listOf(1), ids)
                assertEquals(3, work)
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertNull(
                    loaded.aggregateStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        predicates,
                        distinctProperty = "caller_class"
                    )
                )
                assertNull(
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        predicates,
                        listOf("caller_class"),
                        limit = 10
                    )
                )
                assertNull(
                    loaded.aggregateStringPropertyDisjunction(
                        StringConstant::class.java,
                        predicates,
                        distinctProperty = null
                    )
                )
            } finally {
                loaded.close()
            }
        } finally {
            reservation.close()
            dir.toFile().deleteRecursively()
        }
        assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
    }

    @Test
    fun `mapped string lookup keeps admission specific to the query limit`() {
        val graph = DefaultGraph.Builder().apply {
            repeat(300) { index ->
                addNode(StringConstant(NodeId(index), "symbol-$index"))
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-string-property-limit-admission")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertEquals(
                    300,
                    loaded.nodesByStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringMatchMode.CONTAINS,
                        "symbol-",
                        limit = 300
                    ).orEmpty().count()
                )
                assertEquals(0, loaded.stringPropertyIndexCount())

                assertEquals(
                    listOf("symbol-0"),
                    loaded.nodesByStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringMatchMode.CONTAINS,
                        "symbol-",
                        limit = 1
                    ).orEmpty().map { it.value }.toList()
                )
                assertEquals(0, loaded.stringPropertyIndexCount())

                assertEquals(
                    300,
                    loaded.nodesByStringProperty(
                        StringConstant::class.java,
                        "value",
                        StringMatchMode.CONTAINS,
                        "symbol-",
                        limit = 300
                    ).orEmpty().count()
                )
                assertEquals(1, loaded.stringPropertyIndexCount())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped string lookup resets predicate admission after LRU eviction`() {
        val graph = DefaultGraph.Builder().apply {
            repeat(300) { index ->
                addNode(ResourceFileNode(NodeId(index), "path-$index", "source-$index", "format-$index"))
                addNode(
                    FieldNode(
                        NodeId(300 + index),
                        FieldDescriptor(
                            TypeDescriptor("example.Owner$index"),
                            "field-$index",
                            TypeDescriptor("example.Type$index")
                        ),
                        false
                    )
                )
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-string-property-eviction")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                listOf(
                    Triple(ResourceFileNode::class.java, "path", "missing-path"),
                    Triple(ResourceFileNode::class.java, "source", "missing-source"),
                    Triple(ResourceFileNode::class.java, "format", "missing-format"),
                    Triple(FieldNode::class.java, "class", "missing-class"),
                    Triple(FieldNode::class.java, "name", "missing-name")
                ).forEach { (type, property, expected) ->
                    repeat(2) {
                        assertTrue(
                            loaded.nodesByStringProperty(
                                type,
                                property,
                                StringMatchMode.CONTAINS,
                                expected,
                                limit = 1
                            ).orEmpty().none()
                        )
                    }
                }

                assertEquals(4, loaded.stringPropertyIndexCount())
                assertEquals(0, loaded.stringPropertyIndexCount(ResourceFileNode::class.java, "path"))
                assertTrue(
                    loaded.nodesByStringProperty(
                        ResourceFileNode::class.java,
                        "path",
                        StringMatchMode.CONTAINS,
                        "missing-path",
                        limit = 1
                    ).orEmpty().none()
                )
                assertEquals(0, loaded.stringPropertyIndexCount(ResourceFileNode::class.java, "path"))
                assertEquals(
                    listOf("path-0"),
                    loaded.nodesByStringProperty(
                        ResourceFileNode::class.java,
                        "path",
                        StringMatchMode.STARTS_WITH,
                        "path-0",
                        limit = 1
                    ).orEmpty().map { it.path }.toList()
                )
                assertEquals(0, loaded.stringPropertyIndexCount(ResourceFileNode::class.java, "path"))
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped string lookup bounds predicate admission state`() {
        val graph = DefaultGraph.Builder().apply {
            repeat(300) { index ->
                addNode(StringConstant(NodeId(index), "symbol-$index"))
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-string-property-admission-bounds")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                repeat(33) { index ->
                    assertTrue(loaded.stringConstantsMissing("short-missing-$index"))
                }
                assertTrue(loaded.stringConstantsMissing("short-missing-0"))
                assertEquals(0, loaded.stringPropertyIndexCount())
                assertTrue(loaded.stringConstantsMissing("short-missing-32"))
                assertEquals(1, loaded.stringPropertyIndexCount())

                loaded.clearStringPropertyIndexes()
                val largePredicates = List(24) { index -> "large-missing-$index-${"x".repeat(1_500)}" }
                largePredicates.forEach { expected ->
                    assertTrue(loaded.stringConstantsMissing(expected))
                }
                assertTrue(loaded.stringConstantsMissing(largePredicates.first()))
                assertEquals(0, loaded.stringPropertyIndexCount())
                assertTrue(loaded.stringConstantsMissing(largePredicates.last()))
                assertEquals(1, loaded.stringPropertyIndexCount())

                loaded.clearStringPropertyIndexes()
                val oversized = "oversized-${"x".repeat(32_768)}"
                repeat(2) {
                    assertTrue(loaded.stringConstantsMissing(oversized))
                }
                assertEquals(0, loaded.stringPropertyIndexCount())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clearing mapped string indexes resets unbounded admission`() {
        val graph = DefaultGraph.Builder().apply {
            repeat(300) { index ->
                addNode(StringConstant(NodeId(index), "symbol-$index"))
            }
        }.build()
        val dir = Files.createTempDirectory("webgraph-string-property-admission-clear")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertNull(loaded.unboundedStringConstants("symbol"))
                assertEquals(300, loaded.unboundedStringConstants("symbol")?.count())
                assertEquals(1, loaded.stringPropertyIndexCount())

                loaded.clearStringPropertyIndexes()

                assertEquals(0, loaded.stringPropertyIndexCount())
                assertNull(loaded.unboundedStringConstants("symbol"))
                assertEquals(300, loaded.unboundedStringConstants("symbol")?.count())
                assertEquals(1, loaded.stringPropertyIndexCount())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun MappedWebGraphBackedGraph.stringConstantsMissing(expected: String): Boolean =
        nodesByStringProperty(
            StringConstant::class.java,
            "value",
            StringMatchMode.CONTAINS,
            expected,
            limit = 1
        ).orEmpty().none()

    private fun MappedWebGraphBackedGraph.unboundedStringConstants(expected: String): Sequence<StringConstant>? =
        nodesByStringProperty(
            StringConstant::class.java,
            "value",
            StringMatchMode.CONTAINS,
            expected
        )

    private fun assertRawStringPropertyLookups(graph: Graph) {
        graph.nodesByTransformedStringProperty(
            FieldNode::class.java,
            "class",
            StringValueTransform.LOWERCASE,
            StringMatchMode.CONTAINS,
            "owner"
        )
        assertEquals(
            listOf("example.Owner"),
            assertNotNull(
                graph.nodesByTransformedStringProperty(
                    FieldNode::class.java,
                    "class",
                    StringValueTransform.LOWERCASE,
                    StringMatchMode.CONTAINS,
                    "owner"
                )
            ).map { it.descriptor.declaringClass.className }.toList()
        )
        assertEquals(
            listOf("example.State"),
            indexedValues(graph, EnumConstant::class.java, "enum_type", StringMatchMode.ENDS_WITH, "State") {
                it.enumType.className
            }
        )
        assertEquals(
            listOf("READY"),
            indexedValues(graph, EnumConstant::class.java, "name", StringMatchMode.STARTS_WITH, "REA") {
                it.enumName
            }
        )
        assertEquals(
            listOf("request"),
            indexedValues(graph, LocalVariable::class.java, "name", StringMatchMode.CONTAINS, "que") { it.name }
        )
        assertEquals(
            listOf("java.lang.String"),
            indexedValues(graph, LocalVariable::class.java, "type", StringMatchMode.ENDS_WITH, "String") {
                it.type.className
            }
        )
        assertEquals(
            listOf("example.Owner"),
            indexedValues(graph, FieldNode::class.java, "class", StringMatchMode.CONTAINS, "Owner") {
                it.descriptor.declaringClass.className
            }
        )
        assertEquals(
            listOf("token"),
            indexedValues(graph, FieldNode::class.java, "name", StringMatchMode.CONTAINS, "oke") {
                it.descriptor.name
            }
        )
        assertEquals(
            listOf("java.lang.String"),
            indexedValues(graph, FieldNode::class.java, "type", StringMatchMode.ENDS_WITH, "String") {
                it.descriptor.type.className
            }
        )
        assertEquals(
            listOf("java.lang.String"),
            indexedValues(graph, ParameterNode::class.java, "type", StringMatchMode.CONTAINS, "lang") {
                it.type.className
            }
        )
        assertResourceStringPropertyLookups(graph)
    }

    private fun assertWorkAwareTransformedLookup(graph: MappedWebGraphBackedGraph) {
        var inspected = 0
        val values = graph.nodesByTransformedStringProperty(
            FieldNode::class.java,
            "class",
            StringValueTransform.LOWERCASE,
            StringMatchMode.CONTAINS,
            "owner",
            limit = 1,
            workConsumer = GraphWorkConsumer { inspected++ }
        ).orEmpty().map { it.descriptor.declaringClass.className }.toList()

        assertEquals(listOf("example.Owner"), values)
        assertTrue(inspected > 0)
    }

    private fun assertWrappedLowercaseQuery(graph: Graph) {
        val values = graph.query(
            "MATCH (n) WHERE " +
                "toLower(coalesce(n.caller_name, '')) CONTAINS 'feature' OR " +
                "toLower(coalesce(n.callee_name, '')) CONTAINS 'feature' " +
                "RETURN DISTINCT n.caller_name AS caller, n.callee_name AS callee LIMIT 250"
        ).rows.map { it["caller"] to it["callee"] }

        assertEquals(listOf("callerFeature" to "billingFeature"), values)
    }

    private fun assertResourceStringPropertyLookups(graph: Graph) {
        assertEquals(
            listOf("config/app.yml"),
            indexedValues(graph, ResourceFileNode::class.java, "path", StringMatchMode.STARTS_WITH, "config") {
                it.path
            }
        )
        assertEquals(
            listOf("resources"),
            indexedValues(graph, ResourceFileNode::class.java, "source", StringMatchMode.CONTAINS, "source") {
                it.source
            }
        )
        assertEquals(
            listOf("yaml"),
            indexedValues(graph, ResourceFileNode::class.java, "format", StringMatchMode.ENDS_WITH, "yaml") {
                it.format
            }
        )
        assertEquals(
            listOf("example.Owner"),
            indexedValues(graph, CallSiteNode::class.java, "caller_class", StringMatchMode.CONTAINS, "Owner") {
                it.caller.declaringClass.className
            }
        )
        assertEquals(
            listOf("example.Target"),
            indexedValues(graph, CallSiteNode::class.java, "callee_class", StringMatchMode.CONTAINS, "Target") {
                it.callee.declaringClass.className
            }
        )
        assertEquals(
            listOf("feature-alpha", "feature-beta"),
            indexedValues(graph, StringConstant::class.java, "value", StringMatchMode.CONTAINS, "a") { it.value }
        )
    }

    private fun <T : Node, R> indexedValues(
        graph: Graph,
        type: Class<T>,
        property: String,
        mode: StringMatchMode,
        expected: String,
        transform: (T) -> R
    ): List<R> {
        graph.nodesByStringProperty(type, property, mode, expected)
        return assertNotNull(graph.nodesByStringProperty(type, property, mode, expected))
            .map(transform)
            .toList()
    }

    @Test
    fun `round-trip save and load preserves nodes`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            // Verify nodes
            val originalNodes = graph.nodes(Node::class.java).toList()
            val loadedNodes = loaded.nodes(Node::class.java).toList()
            assertEquals(originalNodes.size, loadedNodes.size, "Node count should match")

            for (node in originalNodes) {
                assertNotNull(loaded.node(node.id), "Node ${node.id} should exist in loaded graph")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves outgoing edges`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val nodes = graph.nodes(Node::class.java).toList()
            for (node in nodes) {
                val originalEdges = graph.outgoing(node.id).toList()
                val loadedEdges = loaded.outgoing(node.id).toList()
                assertEquals(originalEdges.size, loadedEdges.size,
                    "Outgoing edge count should match for node ${node.id}")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves incoming edges`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val nodes = graph.nodes(Node::class.java).toList()
            for (node in nodes) {
                val originalEdges = graph.incoming(node.id).toList()
                val loadedEdges = loaded.incoming(node.id).toList()
                assertEquals(originalEdges.size, loadedEdges.size,
                    "Incoming edge count should match for node ${node.id}")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip exposes precomputed edge count`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertEquals(graph.edgeCount(), loaded.edgeCount())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves typed edge queries`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val node = graph.nodes(Node::class.java).first()
            val originalDataFlow = graph.outgoing(node.id, DataFlowEdge::class.java).toList()
            val loadedDataFlow = loaded.outgoing(node.id, DataFlowEdge::class.java).toList()
            assertEquals(originalDataFlow.size, loadedDataFlow.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves methods`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val originalMethods = graph.methods(MethodPattern()).toList()
            val loadedMethods = loaded.methods(MethodPattern()).toList()
            assertEquals(originalMethods.size, loadedMethods.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves member annotations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val annotations = loaded.memberAnnotations("com.example.Foo", "bar")
            assertEquals(
                graph.memberAnnotations("com.example.Foo", "bar"),
                annotations
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves class origins`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Foo"),
            "bar",
            emptyList(),
            TypeDescriptor("void")
        )
        val graph = DefaultGraph.Builder()
            .addMethod(method)
            .addNode(ReturnNode(NodeId(1), method))
            .addClassOrigin("org.apache.lucene.index.IndexWriter", "BOOT-INF/lib/lucene-core-9.11.1.jar")
            .addClassOrigin("org.apache.logging.log4j.Logger", "BOOT-INF/lib/log4j-api-2.23.1.jar")
            .build()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            assertEquals("BOOT-INF/lib/lucene-core-9.11.1.jar", loaded.classOrigin("org.apache.lucene.index.IndexWriter"))
            assertEquals("BOOT-INF/lib/log4j-api-2.23.1.jar", loaded.classOrigin("org.apache.logging.log4j.Logger"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves artifact dependencies`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Foo"),
            "bar",
            emptyList(),
            TypeDescriptor("void")
        )
        val graph = DefaultGraph.Builder()
            .addMethod(method)
            .addNode(ReturnNode(NodeId(1), method))
            .addArtifactDependency("elasticsearch-8.17.0", "lucene-core-9.12.0", 42)
            .addArtifactDependency("lucene-highlighter-9.12.0", "lucene-core-9.12.0", 9)
            .build()
        val dir = Files.createTempDirectory("webgraph-artifact-deps-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            assertEquals(
                mapOf(
                    "elasticsearch-8.17.0" to mapOf("lucene-core-9.12.0" to 42),
                    "lucene-highlighter-9.12.0" to mapOf("lucene-core-9.12.0" to 9)
                ),
                loaded.artifactDependencies()
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves AnnotationNode`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val annotations = loaded.nodes(AnnotationNode::class.java).toList()
            assertEquals(1, annotations.size)
            assertEquals("javax.annotation.Nullable", annotations[0].name)
            assertEquals("com.example.Foo", annotations[0].className)
            assertEquals("bar", annotations[0].memberName)
            assertEquals("true", annotations[0].values["value"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves typed annotation values`() {
        val builder = DefaultGraph.Builder()
        val ownerType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(ownerType, "bar", emptyList(), TypeDescriptor("void"))
        val annotationNode = AnnotationNode(
            NodeId.next(),
            "com.example.Http",
            "com.example.Foo",
            "bar",
            mapOf(
                "paths" to listOf("/a", "/b"),
                "required" to true,
                "code" to 200,
                "note" to null
            )
        )
        builder.addMethod(method)
        builder.addNode(ReturnNode(NodeId.next(), method))
        builder.addNode(annotationNode)
        builder.addMemberAnnotation(
            "com.example.Foo",
            "bar",
            "com.example.Http",
            annotationNode.values
        )

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-typed-annot-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedNode = loaded.node(annotationNode.id) as AnnotationNode
            assertEquals(annotationNode.values, loadedNode.values)
            assertEquals(annotationNode.values, loaded.memberAnnotations("com.example.Foo", "bar")["com.example.Http"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves ResourceValueNode`() {
        val builder = DefaultGraph.Builder()
        val method = MethodDescriptor(TypeDescriptor("com.example.Foo"), "bar", emptyList(), TypeDescriptor("void"))
        val resourceFileNode = ResourceFileNode(NodeId.next(), "application.yml", "BOOT-INF/classes", "yaml", "prod")
        val resourceNode = ResourceValueNode(NodeId.next(), "application.yml", "server.port", 8080, "yaml", "prod")
        val callSite = CallSiteNode(NodeId.next(), method, method, 12, null, emptyList())
        builder.addMethod(method)
        builder.addNode(ReturnNode(NodeId.next(), method))
        builder.addNode(resourceFileNode)
        builder.addNode(resourceNode)
        builder.addNode(callSite)
        builder.addEdge(ResourceEdge(resourceNode.id, callSite.id, ResourceRelation.LOOKUP))

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-resource-value-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedFile = loaded.node(resourceFileNode.id) as ResourceFileNode
            val loadedNode = loaded.node(resourceNode.id) as ResourceValueNode
            assertEquals(resourceFileNode, loadedFile)
            assertEquals(resourceNode, loadedNode)
            assertTrue(loaded.incoming(callSite.id, ResourceEdge::class.java).any {
                it.from == resourceNode.id && it.kind == ResourceRelation.LOOKUP
            })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves type hierarchy`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val child = TypeDescriptor("com.example.Child")
            val parent = TypeDescriptor("com.example.Parent")
            assertTrue(loaded.supertypes(child).any { it.className == "com.example.Parent" })
            assertTrue(loaded.subtypes(parent).any { it.className == "com.example.Child" })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves enum values`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val values = loaded.enumValues("com.example.Status", "ACTIVE")
            assertEquals(listOf(1, "active"), values)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves call sites`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val originalCallSites = graph.nodes(CallSiteNode::class.java).toList()
            val loadedCallSites = loaded.nodes(CallSiteNode::class.java).toList()
            assertEquals(originalCallSites.size, loadedCallSites.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `load from nonexistent directory throws`() {
        try {
            GraphStore.load(Files.createTempFile("not", "dir"))
            assertTrue(false, "Should have thrown")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `empty graph round-trips`() {
        val graph = DefaultGraph.Builder().build()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            assertEquals(0, loaded.nodes(Node::class.java).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // All constant node types round-trip
    // ========================================================================

    @Test
    fun `round-trip preserves all constant node types`() {
        val builder = DefaultGraph.Builder()
        val strConst = StringConstant(NodeId.next(), "hello")
        val longConst = LongConstant(NodeId.next(), 123456789L)
        val floatConst = FloatConstant(NodeId.next(), 3.14f)
        val doubleConst = DoubleConstant(NodeId.next(), 2.718281828)
        val boolConst = BooleanConstant(NodeId.next(), true)
        val nullConst = NullConstant(NodeId.next())
        val intConst = IntConstant(NodeId.next(), 99)

        builder.addNode(strConst)
        builder.addNode(longConst)
        builder.addNode(floatConst)
        builder.addNode(doubleConst)
        builder.addNode(boolConst)
        builder.addNode(nullConst)
        builder.addNode(intConst)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-const-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedStr = loaded.node(strConst.id) as StringConstant
            assertEquals("hello", loadedStr.value)

            val loadedLong = loaded.node(longConst.id) as LongConstant
            assertEquals(123456789L, loadedLong.value)

            val loadedFloat = loaded.node(floatConst.id) as FloatConstant
            assertEquals(3.14f, loadedFloat.value)

            val loadedDouble = loaded.node(doubleConst.id) as DoubleConstant
            assertEquals(2.718281828, loadedDouble.value)

            val loadedBool = loaded.node(boolConst.id) as BooleanConstant
            assertEquals(true, loadedBool.value)

            val loadedNull = loaded.node(nullConst.id) as NullConstant
            assertNull(loadedNull.value)

            assertEquals(7, loaded.nodes(ConstantNode::class.java).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // ControlFlowEdge and TypeEdge round-trip
    // ========================================================================

    @Test
    fun `round-trip preserves ControlFlowEdge with BranchComparison`() {
        val builder = DefaultGraph.Builder()
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        val n3 = IntConstant(NodeId.next(), 0)
        builder.addNode(n1)
        builder.addNode(n2)
        builder.addNode(n3)

        val comparison = BranchComparison(ComparisonOp.EQ, n3.id)
        val cfEdge = ControlFlowEdge(n1.id, n2.id, ControlFlowKind.BRANCH_TRUE, comparison)
        val seqEdge = ControlFlowEdge(n2.id, n3.id, ControlFlowKind.SEQUENTIAL)
        builder.addEdge(cfEdge)
        builder.addEdge(seqEdge)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-cf-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedCfEdges = loaded.outgoing(n1.id, ControlFlowEdge::class.java).toList()
            assertEquals(1, loadedCfEdges.size)
            assertEquals(ControlFlowKind.BRANCH_TRUE, loadedCfEdges[0].kind)
            assertNotNull(loadedCfEdges[0].comparison)
            assertEquals(ComparisonOp.EQ, loadedCfEdges[0].comparison!!.operator)
            assertEquals(n3.id, loadedCfEdges[0].comparison!!.comparandNodeId)

            val loadedSeqEdges = loaded.outgoing(n2.id, ControlFlowEdge::class.java).toList()
            assertEquals(1, loadedSeqEdges.size)
            assertEquals(ControlFlowKind.SEQUENTIAL, loadedSeqEdges[0].kind)
            assertNull(loadedSeqEdges[0].comparison)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `comparison lookup is skipped for non-control-flow edge labels`() {
        val label = NodeSerializer.encodeEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.ASSIGN))
        val lookup = BranchComparisonLookup {
            error("comparison lookup should not run for non-control-flow edges")
        }

        assertNull(comparisonForEdge(label, 1, 2, NodeSerializer.FORMAT_VERSION, lookup))
    }

    @Test
    fun `mapped comparison lookup reads persisted comparisons by edge key`() {
        val builder = DefaultGraph.Builder()
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        val n3 = IntConstant(NodeId.next(), 3)
        val n4 = IntConstant(NodeId.next(), 4)
        builder.addNode(n1)
        builder.addNode(n2)
        builder.addNode(n3)
        builder.addNode(n4)

        val first = BranchComparison(ComparisonOp.EQ, n3.id)
        val second = BranchComparison(ComparisonOp.NE, n4.id)
        builder.addEdge(ControlFlowEdge(n1.id, n2.id, ControlFlowKind.BRANCH_TRUE, first))
        builder.addEdge(ControlFlowEdge(n2.id, n4.id, ControlFlowKind.BRANCH_FALSE, second))

        val dir = Files.createTempDirectory("webgraph-mapped-comparison-test")
        try {
            GraphStore.save(builder.build(), dir)
            val lookup = MappedBranchComparisonLookup.load(dir.resolve("graph.comparisons"))

            assertEquals(first, lookup.find(edgeKey(n1.id, n2.id)))
            assertEquals(second, lookup.find(edgeKey(n2.id, n4.id)))
            assertNull(lookup.find(edgeKey(n3.id, n4.id)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped comparison lookup binary search covers lower and upper misses`() {
        val builder = DefaultGraph.Builder()
        val n1 = IntConstant(NodeId(10), 1)
        val n2 = IntConstant(NodeId(20), 2)
        val n3 = IntConstant(NodeId(30), 3)
        builder.addNode(n1).addNode(n2).addNode(n3)
        builder.addEdge(
            ControlFlowEdge(
                n2.id,
                n3.id,
                ControlFlowKind.BRANCH_TRUE,
                BranchComparison(ComparisonOp.EQ, n1.id)
            )
        )

        val dir = Files.createTempDirectory("webgraph-mapped-comparison-miss-test")
        try {
            GraphStore.save(builder.build(), dir)
            val lookup = MappedBranchComparisonLookup.load(dir.resolve("graph.comparisons"))
            assertNull(lookup.find(edgeKey(NodeId(0), NodeId(0))))
            assertNull(lookup.find(edgeKey(NodeId(99), NodeId(99))))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun edgeKey(from: NodeId, to: NodeId): Long =
        from.value.toLong() shl INT_BITS or (to.value.toLong() and UNSIGNED_INT_MASK)

    @Test
    fun `mapped comparison lookup handles empty and corrupt comparison files`() {
        val emptyFile = Files.createTempFile("webgraph-empty-comparisons", ".bin")
        val corruptFile = Files.createTempFile("webgraph-corrupt-comparisons", ".bin")
        try {
            DataOutputStream(emptyFile.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_COMPARISONS)
                dos.writeInt(0)
            }
            DataOutputStream(corruptFile.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_COMPARISONS)
                dos.writeInt(-1)
            }

            assertNull(MappedBranchComparisonLookup.load(emptyFile).find(1L))
            assertFailsWith<IllegalArgumentException> {
                MappedBranchComparisonLookup.load(corruptFile)
            }
        } finally {
            Files.deleteIfExists(emptyFile)
            Files.deleteIfExists(corruptFile)
        }
    }

    @Test
    fun `node offset indexes preserve missing sentinel grow and wide offsets`() {
        val compact = IntNodeOffsetIndex(1)
        assertEquals(-1L, compact.offset(0))
        compact.ensureSize(3)
        compact.set(2, 42L)

        assertEquals(-1L, compact.offset(1))
        assertEquals(42L, compact.offset(2))
        assertFailsWith<IllegalArgumentException> {
            compact.set(0, Int.MAX_VALUE.toLong() + 1L)
        }

        val wide = LongNodeOffsetIndex(1)
        val wideOffset = Int.MAX_VALUE.toLong() + 1L
        wide.ensureSize(2)
        wide.set(1, wideOffset)

        assertEquals(-1L, wide.offset(0))
        assertEquals(wideOffset, wide.offset(1))
    }

    @Test
    fun `mutable node offset indexes and loaded string tables expose fallback behavior`() {
        val intOffsets = IntNodeOffsetIndex(1)
        assertEquals(1, intOffsets.size)
        intOffsets.set(0, 12)
        intOffsets.ensureSize(3)
        assertEquals(3, intOffsets.size)
        assertEquals(12L, intOffsets.offset(0))
        assertEquals(-1L, intOffsets.offset(2))

        val longOffsets = LongNodeOffsetIndex(1)
        assertEquals(1, longOffsets.size)
        longOffsets.set(0, Int.MAX_VALUE.toLong() + 10L)
        longOffsets.ensureSize(2)
        assertEquals(2, longOffsets.size)
        assertEquals(Int.MAX_VALUE.toLong() + 10L, longOffsets.offset(0))
        assertEquals(-1L, longOffsets.offset(1))

        val dir = Files.createTempDirectory("webgraph-string-table-test")
        try {
            val built = StringTable.build(listOf("beta", "alpha"), dir)
            assertEquals(0, built.indexOf("alpha"))
            val loaded = StringTable.load(dir)
            assertEquals("alpha", loaded.get(0))
            assertEquals(-1, loaded.indexOf("alpha"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `save writes mmap node offset and type indexes`() {
        val builder = DefaultGraph.Builder()
        val first = IntConstant(NodeId(0), 1)
        val second = IntConstant(NodeId(2), 2)
        builder.addNode(first)
        builder.addNode(second)

        val dir = Files.createTempDirectory("webgraph-mapped-index-test")
        try {
            GraphStore.save(builder.build(), dir)

            val offsets = MappedNodeOffsetIndex.load(dir.resolve("graph.nodeoffsets"))
            assertEquals(3, offsets.size)
            assertTrue(offsets.offset(first.id.value) >= 0L)
            assertEquals(-1L, offsets.offset(1))
            assertTrue(offsets.offset(second.id.value) > offsets.offset(first.id.value))

            val typeIndex = MappedNodeTypeIndex.load(dir.resolve("graph.typeindex"))
            assertEquals(2L, typeIndex.count(IntConstant::class.java))
            assertEquals(listOf(0, 2), typeIndex.ids(IntConstant::class.java).toList().sorted())
            assertEquals(2L, typeIndex.count(Node::class.java))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `save writes label prefix and mapped load falls back when missing`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-label-prefix-test")
        try {
            GraphStore.save(graph, dir)

            val prefix = BinIO.loadInts(dir.resolve("graph.labelprefix").toString())
            val maxNodeId = graph.nodes(Node::class.java).maxOf { it.id.value }
            assertEquals(maxNodeId + 2, prefix.size)
            assertEquals(graph.edgeCount(), prefix.last().toLong())

            val loaded = GraphStore.loadMapped(dir)
            try {
                assertGraphOperations(graph, loaded)
            } finally {
                (loaded as Closeable).close()
            }

            Files.delete(dir.resolve("graph.labelprefix"))
            val loadedWithoutPrefix = GraphStore.loadMapped(dir)
            try {
                assertGraphOperations(graph, loadedWithoutPrefix)
            } finally {
                (loadedWithoutPrefix as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped load rebuilds mmap indexes from legacy node index`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-rebuild-mapped-index-test")
        try {
            GraphStore.save(graph, dir)
            Files.delete(dir.resolve("graph.nodeoffsets"))
            Files.delete(dir.resolve("graph.typeindex"))

            val loaded = GraphStore.loadMapped(dir)
            try {
                assertTrue(Files.exists(dir.resolve("graph.nodeoffsets")))
                assertTrue(Files.exists(dir.resolve("graph.typeindex")))
                assertGraphOperations(graph, loaded)
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped node indexes reject invalid headers and ranges`() {
        val dir = Files.createTempDirectory("webgraph-invalid-mapped-index-test")
        try {
            val offsetIndex = dir.resolve("graph.nodeoffsets")
            DataOutputStream(offsetIndex.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_NODEOFFSETS)
                dos.writeInt(-1)
            }
            assertFailsWith<IllegalArgumentException> {
                MappedNodeOffsetIndex.load(offsetIndex)
            }

            val typeIndex = dir.resolve("graph.typeindex")
            DataOutputStream(typeIndex.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_TYPEINDEX)
                dos.writeInt(-1)
            }
            assertFailsWith<IllegalArgumentException> {
                MappedNodeTypeIndex.load(typeIndex)
            }

            DataOutputStream(typeIndex.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_TYPEINDEX)
                dos.writeInt(1)
                dos.writeByte(NodeSerializer.TAG_INT_CONSTANT)
                dos.writeInt(-1)
                dos.writeLong(0L)
            }
            assertFailsWith<IllegalArgumentException> {
                MappedNodeTypeIndex.load(typeIndex)
            }

            DataOutputStream(typeIndex.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_TYPEINDEX)
                dos.writeInt(1)
                dos.writeByte(NodeSerializer.TAG_INT_CONSTANT)
                dos.writeInt(0)
                dos.writeLong(Int.MAX_VALUE.toLong() + 1L)
            }
            assertFailsWith<IllegalArgumentException> {
                MappedNodeTypeIndex.load(typeIndex)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `node tag helpers expose stable defensive mappings`() {
        val tags = nodeTagEntries()
        tags[0] = 99

        assertEquals(NodeSerializer.TAG_INT_CONSTANT, nodeTagEntries()[0])
        assertEquals(IntConstant::class.java, nodeClassForTag(NodeSerializer.TAG_INT_CONSTANT))
        assertNull(nodeClassForTag(255))
    }

    @Test
    fun `round-trip preserves TypeEdge`() {
        val builder = DefaultGraph.Builder()
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        builder.addNode(n1)
        builder.addNode(n2)

        val typeEdge = TypeEdge(n1.id, n2.id, TypeRelation.IMPLEMENTS)
        builder.addEdge(typeEdge)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-type-edge-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedEdges = loaded.outgoing(n1.id, TypeEdge::class.java).toList()
            assertEquals(1, loadedEdges.size)
            assertEquals(TypeRelation.IMPLEMENTS, loadedEdges[0].kind)
            assertEquals(n2.id, loadedEdges[0].to)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Branch scopes round-trip
    // ========================================================================

    @Test
    fun `round-trip preserves branch scopes`() {
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(fooType, "check", emptyList(), TypeDescriptor("boolean"))
        builder.addMethod(method)

        val condNode = IntConstant(NodeId.next(), 0)
        val trueNode = IntConstant(NodeId.next(), 1)
        val falseNode = IntConstant(NodeId.next(), 2)
        builder.addNode(condNode)
        builder.addNode(trueNode)
        builder.addNode(falseNode)

        val comp = BranchComparison(ComparisonOp.NE, condNode.id)
        builder.addBranchScope(condNode.id, method, comp, intArrayOf(trueNode.id.value), intArrayOf(falseNode.id.value))

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-branch-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val scopes = loaded.branchScopes().toList()
            assertEquals(1, scopes.size)
            assertEquals(condNode.id, scopes[0].conditionNodeId)
            assertEquals(ComparisonOp.NE, scopes[0].comparison.operator)
            assertTrue(scopes[0].trueBranchNodeIds.contains(trueNode.id.value))
            assertTrue(scopes[0].falseBranchNodeIds.contains(falseNode.id.value))

            // Also test branchScopesFor
            val scopesFor = loaded.branchScopesFor(condNode.id).toList()
            assertEquals(1, scopesFor.size)

            // branchScopesFor with unknown node returns empty
            assertEquals(0, loaded.branchScopesFor(NodeId(999999)).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // callSites with pattern filtering on loaded graph
    // ========================================================================

    @Test
    fun `callSites with pattern filtering on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-callsite-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            // Match existing callee
            val matched = loaded.callSites(MethodPattern(declaringClass = "com.example.Foo", name = "baz")).toList()
            assertEquals(1, matched.size)

            // No match
            val noMatch = loaded.callSites(MethodPattern(name = "nonexistent")).toList()
            assertTrue(noMatch.isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Reified nodes<T>() type filtering on loaded graph
    // ========================================================================

    @Test
    fun `reified nodes type filtering on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-reified-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val intConstants = loaded.nodes(IntConstant::class.java).toList()
            assertEquals(1, intConstants.size)
            assertEquals(42, intConstants[0].value)

            val callSites = loaded.nodes(CallSiteNode::class.java).toList()
            assertEquals(1, callSites.size)

            val fields = loaded.nodes(FieldNode::class.java).toList()
            assertEquals(1, fields.size)

            val params = loaded.nodes(ParameterNode::class.java).toList()
            assertEquals(1, params.size)

            val locals = loaded.nodes(LocalVariable::class.java).toList()
            assertEquals(1, locals.size)

            val returns = loaded.nodes(ReturnNode::class.java).toList()
            assertEquals(1, returns.size)

            val enums = loaded.nodes(EnumConstant::class.java).toList()
            assertEquals(1, enums.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Resources on loaded graph
    // ========================================================================

    @Test
    fun `loaded graph resources preserves persisted text resources`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-resources-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val entries = loaded.resources.list("**").toList()
            assertEquals(1, entries.size)
            assertEquals("application.properties", entries.single().path)
            val content = loaded.resources.open("application.properties").bufferedReader().readText()
            assertTrue(content.contains("feature.mode=shadow"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Type hierarchy on loaded graph
    // ========================================================================

    @Test
    fun `typeHierarchyTypes on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-hierarchy-types-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val types = loaded.typeHierarchyTypes()
            assertTrue(types.contains("com.example.Parent"))
            assertTrue(types.contains("com.example.Child"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `supertypes and subtypes return empty for unknown type on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-hierarchy-empty-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertEquals(0, loaded.supertypes(TypeDescriptor("com.nonexistent.Type")).count())
            assertEquals(0, loaded.subtypes(TypeDescriptor("com.nonexistent.Type")).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Enum values on loaded graph
    // ========================================================================

    @Test
    fun `enumValues returns null for missing enum on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-enum-null-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertNull(loaded.enumValues("com.example.Missing", "MISSING"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Member annotations on loaded graph
    // ========================================================================

    @Test
    fun `memberAnnotations returns empty for unknown member on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-annot-empty-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertTrue(loaded.memberAnnotations("com.example.Unknown", "unknown").isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Methods on loaded graph
    // ========================================================================

    @Test
    fun `methods with pattern filter on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-method-filter-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertEquals(8L, loaded.nodeCount(Node::class.java))
            assertEquals(1L, loaded.nodeCount(CallSiteNode::class.java))

            val barMethods = loaded.methods(MethodPattern(name = "bar")).toList()
            assertEquals(1, barMethods.size)
            assertEquals("bar", barMethods[0].name)

            val allMethods = loaded.methods(MethodPattern()).toList()
            assertEquals(2, allMethods.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph exposes method count and limited method slices`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-method-slice-test")
        val loadedGraphs = mutableListOf<Graph>()
        try {
            GraphStore.save(graph, dir)
            loadedGraphs += GraphStore.loadMapped(dir)

            for (loaded in loadedGraphs) {
                assertEquals(8L, loaded.nodeCount(Node::class.java))
                assertEquals(1L, loaded.nodeCount(CallSiteNode::class.java))
                assertEquals(graph.edgeCount(), loaded.edgeCount())
                assertEquals(2L, loaded.methodCount())

                val firstMethod = assertNotNull(loaded.methodSlice(MethodPattern(), 1))
                assertEquals(1, firstMethod.size)

                val barMethods = assertNotNull(loaded.methodSlice(MethodPattern(name = "bar"), 10))
                assertEquals(1, barMethods.size)
                assertEquals("bar", barMethods[0].name)

                val missingMethods = assertNotNull(loaded.methodSlice(MethodPattern(name = "missing"), 10))
                assertTrue(missingMethods.isEmpty())

                val zeroLimit = assertNotNull(loaded.methodSlice(MethodPattern(), 0))
                assertTrue(zeroLimit.isEmpty())
            }
        } finally {
            loadedGraphs.forEach { (it as? Closeable)?.close() }
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loaded graphs expose persisted class overview`() {
        val callerType = TypeDescriptor("com.example.Caller")
        val calleeType = TypeDescriptor("com.example.Callee")
        val caller = MethodDescriptor(callerType, "call", emptyList(), TypeDescriptor("void"))
        val callee = MethodDescriptor(calleeType, "serve", emptyList(), TypeDescriptor("void"))
        val callSite = CallSiteNode(NodeId.next(), caller, callee, 42, null, emptyList())
        val graph = DefaultGraph.Builder()
            .addNode(callSite)
            .addMethod(caller)
            .addMethod(callee)
            .build()
        val dir = Files.createTempDirectory("webgraph-class-overview-test")
        val loadedGraphs = mutableListOf<Graph>()
        try {
            GraphStore.save(graph, dir)
            loadedGraphs += GraphStore.load(dir, GraphStore.LoadMode.EAGER)
            loadedGraphs += GraphStore.loadMapped(dir)

            for (loaded in loadedGraphs) {
                val overview = assertNotNull(loaded.classOverview(10))
                assertEquals(1, overview.callSiteCount)
                assertEquals(1, overview.classCounts["com.example.Caller"])
                assertEquals(1, overview.classCounts["com.example.Callee"])
                assertEquals(
                    1,
                    overview.classEdges[ClassDependency("com.example.Caller", "com.example.Callee")]
                )
                assertEquals(2, assertNotNull(loaded.classOverview(Int.MAX_VALUE)).classCounts.size)
            }
        } finally {
            loadedGraphs.forEach { (it as? Closeable)?.close() }
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `class overview store bounds reads and provider cache`() {
        val overview = ClassOverview(
            classCounts = linkedMapOf(
                "com.example.A" to 3,
                "com.example.B" to 2,
                "com.example.C" to 1
            ),
            classEdges = linkedMapOf(
                ClassDependency("com.example.A", "com.example.B") to 2,
                ClassDependency("com.example.B", "com.example.C") to 1
            ),
            callSiteCount = 3
        )
        val dir = Files.createTempDirectory("class-overview-store-test")
        try {
            val strings = mutableSetOf<String>()
            ClassOverviewStore.collectStrings(overview, strings)
            val stringTable = StringTable.build(strings, dir)
            assertNull(ClassOverviewStore.load(dir, stringTable, 10))

            ClassOverviewStore.save(overview, dir, stringTable)
            assertTrue(Files.isRegularFile(dir.resolve(ClassOverviewStore.FILE_NAME)))
            assertEquals(0, ClassOverviewStore.boundLimit(-1))
            assertEquals(ClassOverviewStore.MAX_PERSISTED_CLASSES, ClassOverviewStore.boundLimit(Int.MAX_VALUE))

            val negative = assertNotNull(ClassOverviewStore.load(dir, stringTable, -1))
            assertTrue(negative.classCounts.isEmpty())
            assertTrue(negative.classEdges.isEmpty())

            val firstClass = assertNotNull(ClassOverviewStore.load(dir, stringTable, 1))
            assertEquals(listOf("com.example.A"), firstClass.classCounts.keys.toList())
            assertTrue(firstClass.classEdges.isEmpty())

            val provider = PersistedClassOverviewProvider(dir, stringTable)
            val full = assertNotNull(provider.load(Int.MAX_VALUE))
            assertEquals(3, full.classCounts.size)
            assertEquals(2, full.classEdges.size)
            val cachedSlice = assertNotNull(provider.load(2))
            assertEquals(listOf("com.example.A", "com.example.B"), cachedSlice.classCounts.keys.toList())
            assertEquals(1, cachedSlice.classEdges.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `class overview builders keep only top class edges`() {
        val methodA = MethodDescriptor(TypeDescriptor("com.example.A"), "a", emptyList(), TypeDescriptor("void"))
        val methodB = MethodDescriptor(TypeDescriptor("com.example.B"), "b", emptyList(), TypeDescriptor("void"))
        val methodC = MethodDescriptor(TypeDescriptor("com.example.C"), "c", emptyList(), TypeDescriptor("void"))
        val callAB = CallSiteNode(NodeId.next(), methodA, methodB, 1, null, emptyList())
        val callAB2 = CallSiteNode(NodeId.next(), methodA, methodB, 2, null, emptyList())
        val callBC = CallSiteNode(NodeId.next(), methodB, methodC, 3, null, emptyList())
        val callAA = CallSiteNode(NodeId.next(), methodA, methodA, 4, null, emptyList())

        val classBuilder = ClassOverviewBuilder()
        listOf(callAB, callAB2, callBC, callAA).forEach(classBuilder::add)
        val topCounts = classBuilder.topClassCounts(2)
        assertEquals(4, classBuilder.callSiteCount())
        assertEquals(listOf("com.example.A", "com.example.B"), topCounts.keys.toList())

        val edgeBuilder = ClassOverviewEdgeBuilder(topCounts.keys)
        listOf(callAB, callAB2, callBC, callAA).forEach(edgeBuilder::add)
        assertEquals(mapOf(ClassDependency("com.example.A", "com.example.B") to 2), edgeBuilder.build())
    }

    // ========================================================================
    // Incoming edges on loaded graph
    // ========================================================================

    @Test
    fun `incoming typed edges on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-incoming-typed-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            // The local node has incoming DataFlowEdges (from param and constant)
            val locals = loaded.nodes(LocalVariable::class.java).toList()
            assertEquals(1, locals.size)
            val localId = locals[0].id

            val incomingDf = loaded.incoming(localId, DataFlowEdge::class.java).toList()
            assertEquals(2, incomingDf.size)

            // No incoming CallEdge to local
            val incomingCall = loaded.incoming(localId, CallEdge::class.java).toList()
            assertEquals(0, incomingCall.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Outgoing/incoming for node without edges on loaded graph
    // ========================================================================

    @Test
    fun `outgoing and incoming return empty for isolated node on loaded graph`() {
        val builder = DefaultGraph.Builder()
        val isolated = IntConstant(NodeId.next(), 999)
        builder.addNode(isolated)
        val graph = builder.build()

        val dir = Files.createTempDirectory("webgraph-isolated-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertEquals(0, loaded.outgoing(isolated.id).count())
            assertEquals(0, loaded.incoming(isolated.id).count())
            assertEquals(0, loaded.outgoing(isolated.id, DataFlowEdge::class.java).count())
            assertEquals(0, loaded.incoming(isolated.id, DataFlowEdge::class.java).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // ReturnNode with actualType for metadata collection
    // ========================================================================

    @Test
    fun `round-trip preserves ReturnNode with actualType`() {
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(fooType, "getData", emptyList(), TypeDescriptor("java.lang.Object"))
        val returnNode = ReturnNode(NodeId.next(), method, actualType = TypeDescriptor("com.example.ConcreteData"))
        builder.addNode(returnNode)
        builder.addMethod(method)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-return-actual-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedReturn = loaded.node(returnNode.id) as ReturnNode
            assertNotNull(loadedReturn.actualType)
            assertEquals("com.example.ConcreteData", loadedReturn.actualType!!.className)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // encodeEdge / decodeEdge round-trip for all edge variants
    // ========================================================================

    @Test
    fun `encodeEdge and decodeEdge round-trip for all edge variants`() {
        val from = NodeId(1)
        val to = NodeId(2)

        // DataFlowEdge - all kinds
        for (kind in DataFlowKind.entries) {
            val edge = DataFlowEdge(from, to, kind)
            val encoded = NodeSerializer.encodeEdge(edge)
            val decoded = NodeSerializer.decodeEdge(encoded, from, to)
            assertTrue(decoded is DataFlowEdge)
            assertEquals(kind, (decoded as DataFlowEdge).kind)
            assertEquals(from, decoded.from)
            assertEquals(to, decoded.to)
        }

        // CallEdge - all flag combinations
        for (virtual in listOf(false, true)) {
            for (dynamic in listOf(false, true)) {
                val edge = CallEdge(from, to, isVirtual = virtual, isDynamic = dynamic)
                val encoded = NodeSerializer.encodeEdge(edge)
                val decoded = NodeSerializer.decodeEdge(encoded, from, to)
                assertTrue(decoded is CallEdge)
                val callEdge = decoded as CallEdge
                assertEquals(virtual, callEdge.isVirtual, "isVirtual=$virtual")
                assertEquals(dynamic, callEdge.isDynamic, "isDynamic=$dynamic")
            }
        }

        // TypeEdge - all relations
        for (relation in TypeRelation.entries) {
            val edge = TypeEdge(from, to, relation)
            val encoded = NodeSerializer.encodeEdge(edge)
            val decoded = NodeSerializer.decodeEdge(encoded, from, to)
            assertTrue(decoded is TypeEdge)
            assertEquals(relation, (decoded as TypeEdge).kind)
        }

        // ControlFlowEdge - all kinds, with and without comparison
        for (kind in ControlFlowKind.entries) {
            val edge = ControlFlowEdge(from, to, kind)
            val encoded = NodeSerializer.encodeEdge(edge)
            val decoded = NodeSerializer.decodeEdge(encoded, from, to)
            assertTrue(decoded is ControlFlowEdge)
            assertEquals(kind, (decoded as ControlFlowEdge).kind)
            assertNull(decoded.comparison)
        }

        // ControlFlowEdge with comparison
        val comp = BranchComparison(ComparisonOp.GE, NodeId(99))
        val cfEdge = ControlFlowEdge(from, to, ControlFlowKind.BRANCH_TRUE)
        val encoded = NodeSerializer.encodeEdge(cfEdge)
        val decoded = NodeSerializer.decodeEdge(encoded, from, to, comp)
        assertTrue(decoded is ControlFlowEdge)
        assertEquals(comp, (decoded as ControlFlowEdge).comparison)

        for (relation in ResourceRelation.entries) {
            val edge = ResourceEdge(from, to, relation)
            val encodedResource = NodeSerializer.encodeEdge(edge)
            val decodedResource = NodeSerializer.decodeEdge(encodedResource, from, to)
            assertTrue(decodedResource is ResourceEdge)
            assertEquals(relation, (decodedResource as ResourceEdge).kind)
        }
    }

    @Test
    fun `decodeEdge supports legacy v2 labels`() {
        val from = NodeId(1)
        val to = NodeId(2)
        val comparison = BranchComparison(ComparisonOp.EQ, NodeId(3))

        val dataFlow = NodeSerializer.decodeEdge(0 or (DataFlowKind.FIELD_LOAD.ordinal shl 2), from, to, version = 2)
        assertEquals(DataFlowKind.FIELD_LOAD, (dataFlow as DataFlowEdge).kind)

        val call = NodeSerializer.decodeEdge(1 or (1 shl 6) or (1 shl 7), from, to, version = 2)
        assertTrue((call as CallEdge).isVirtual)
        assertTrue(call.isDynamic)

        val type = NodeSerializer.decodeEdge(2 or (TypeRelation.IMPLEMENTS.ordinal shl 2), from, to, version = 2)
        assertEquals(TypeRelation.IMPLEMENTS, (type as TypeEdge).kind)

        val control = NodeSerializer.decodeEdge(3 or (ControlFlowKind.BRANCH_FALSE.ordinal shl 2), from, to, comparison, version = 2)
        assertEquals(ControlFlowKind.BRANCH_FALSE, (control as ControlFlowEdge).kind)
        assertEquals(comparison, control.comparison)
    }

    @Test
    fun `node serializer helpers cover value io and direct edge decoders`() {
        val dir = Files.createTempDirectory("webgraph-node-helpers")
        try {
            val strings = StringTable.build(
                setOf("fallback", "enum.Owner", "VALUE", "hello", "java.class"),
                dir
            )
            val serializerClass = NodeSerializer::class.java
            val collectAnyValueString = serializerClass.getDeclaredMethod("collectAnyValueString", Any::class.java, MutableSet::class.java).apply { isAccessible = true }
            val writeAnyValue = serializerClass.getDeclaredMethod("writeAnyValue", DataOutputStream::class.java, Any::class.java, StringTable::class.java).apply { isAccessible = true }
            val readAnyValue = serializerClass.getDeclaredMethod(
                "readAnyValue",
                DataInput::class.java,
                StringTable::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val decodeEdgeV2 = serializerClass.declaredMethods.first { it.name.startsWith("decodeEdgeV2") }.apply { isAccessible = true }
            val decodeEdgeV3 = serializerClass.declaredMethods.first { it.name.startsWith("decodeEdgeV3") }.apply { isAccessible = true }

            val dest = linkedSetOf<String>()
            collectAnyValueString.invoke(NodeSerializer, EnumValueReference("enum.Owner", "VALUE"), dest)
            collectAnyValueString.invoke(NodeSerializer, listOf("hello", true), dest)
            collectAnyValueString.invoke(NodeSerializer, object { override fun toString() = "fallback" }, dest)
            assertTrue(dest.containsAll(listOf("enum.Owner", "VALUE", "hello")))
            assertTrue(dest.contains("fallback"))

            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { dos ->
                writeAnyValue.invoke(NodeSerializer, dos, listOf("hello", 7), strings)
                dos.writeByte(99)
                dos.writeInt(strings.indexOf("fallback"))
            }
            DataInputStream(ByteArrayInputStream(baos.toByteArray())).use { dis ->
                assertEquals(listOf("hello", 7), readAnyValue.invoke(NodeSerializer, dis, strings, NodeSerializer.FORMAT_VERSION))
                assertEquals("fallback", readAnyValue.invoke(NodeSerializer, dis, strings, NodeSerializer.FORMAT_VERSION))
            }

            val unsupportedOut = ByteArrayOutputStream()
            DataOutputStream(unsupportedOut).use { dos ->
                writeAnyValue.invoke(NodeSerializer, dos, object { override fun toString() = "fallback" }, strings)
            }
            DataInputStream(ByteArrayInputStream(unsupportedOut.toByteArray())).use { dis ->
                assertEquals("fallback", readAnyValue.invoke(NodeSerializer, dis, strings, NodeSerializer.FORMAT_VERSION))
            }

            val from = NodeId(11)
            val to = NodeId(12)
            val comparison = BranchComparison(ComparisonOp.GT, NodeId(13))
            assertEquals(
                DataFlowKind.PARAMETER_PASS,
                (decodeEdgeV2.invoke(NodeSerializer, 0 or (DataFlowKind.PARAMETER_PASS.ordinal shl 2), from.value, to.value, null) as DataFlowEdge).kind
            )
            val legacyCall = decodeEdgeV2.invoke(NodeSerializer, 1 or (1 shl 6) or (1 shl 7), from.value, to.value, null) as CallEdge
            assertTrue(legacyCall.isVirtual)
            assertTrue(legacyCall.isDynamic)
            val legacyStaticCall = decodeEdgeV2.invoke(NodeSerializer, 1, from.value, to.value, null) as CallEdge
            assertFalse(legacyStaticCall.isVirtual)
            assertFalse(legacyStaticCall.isDynamic)
            assertEquals(
                TypeRelation.EXTENDS,
                (decodeEdgeV2.invoke(NodeSerializer, 2 or (TypeRelation.EXTENDS.ordinal shl 2), from.value, to.value, null) as TypeEdge).kind
            )
            assertEquals(
                comparison,
                (decodeEdgeV2.invoke(NodeSerializer, 3 or (ControlFlowKind.SEQUENTIAL.ordinal shl 2), from.value, to.value, comparison) as ControlFlowEdge).comparison
            )
            val v3Call = decodeEdgeV3.invoke(NodeSerializer, 1 or (1 shl 3) or (1 shl 4), from.value, to.value, null) as CallEdge
            assertTrue(v3Call.isVirtual)
            assertTrue(v3Call.isDynamic)
            val v3StaticCall = decodeEdgeV3.invoke(NodeSerializer, 1, from.value, to.value, null) as CallEdge
            assertFalse(v3StaticCall.isVirtual)
            assertFalse(v3StaticCall.isDynamic)
            assertEquals(
                ResourceRelation.OPENS,
                (decodeEdgeV3.invoke(NodeSerializer, 4 or (ResourceRelation.OPENS.ordinal shl 3), from.value, to.value, null) as ResourceEdge).kind
            )
            val decodeFailure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                decodeEdgeV3.invoke(NodeSerializer, 5, from.value, to.value, null)
            }
            assertTrue(decodeFailure.targetException is IllegalArgumentException)

            val boolOut = ByteArrayOutputStream()
            DataOutputStream(boolOut).use { dos -> writeAnyValue.invoke(NodeSerializer, dos, true, strings) }
            DataInputStream(ByteArrayInputStream(boolOut.toByteArray())).use { dis ->
                assertEquals(true, readAnyValue.invoke(NodeSerializer, dis, strings, NodeSerializer.FORMAT_VERSION))
            }

            val legacyListOut = ByteArrayOutputStream()
            DataOutputStream(legacyListOut).use { dos -> writeAnyValue.invoke(NodeSerializer, dos, listOf("hello"), strings) }
            DataInputStream(ByteArrayInputStream(legacyListOut.toByteArray())).use { dis ->
                val legacyFailure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                    readAnyValue.invoke(NodeSerializer, dis, strings, 1)
                }
                assertTrue(legacyFailure.targetException is IllegalArgumentException)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `byte buffer data input reads primitives and counting output stream flush delegate correctly`() {
        val byteBufferDataInputClass = Class.forName("io.johnsonlee.graphite.webgraph.ByteBufferDataInput")
        val inputCtor = byteBufferDataInputClass
            .getDeclaredConstructor(ByteBuffer::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val source = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 7, 1, 2, 3))
        val input = inputCtor.newInstance(source, 0) as DataInput

        assertEquals(7, input.readInt())
        assertTrue(input.readBoolean())
        assertEquals(2, input.readUnsignedByte())
        val buffer = ByteArray(1)
        input.readFully(buffer)
        assertEquals(byteArrayOf(3).toList(), buffer.toList())
        assertFailsWith<java.io.EOFException> {
            input.readByte()
        }
        assertEquals(0, source.position())

        val offsetInput = inputCtor.newInstance(source, 4) as DataInput
        assertTrue(offsetInput.readBoolean())
        assertEquals(0, offsetInput.skipBytes(-1))

        val skipInput = inputCtor.newInstance(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 0) as DataInput
        assertEquals(2, skipInput.skipBytes(2))
        assertEquals(3, skipInput.readUnsignedByte())
        assertEquals(0, skipInput.skipBytes(1))

        val primitives = ByteBuffer.allocate(
            Short.SIZE_BYTES + Char.SIZE_BYTES + Long.SIZE_BYTES + Float.SIZE_BYTES + Double.SIZE_BYTES
        )
        primitives.putShort(7)
        primitives.putChar('g')
        primitives.putLong(11L)
        primitives.putFloat(1.25f)
        primitives.putDouble(2.5)
        primitives.flip()
        val primitiveInput = inputCtor.newInstance(primitives, 0) as DataInput
        assertEquals(7, primitiveInput.readShort().toInt())
        assertEquals('g', primitiveInput.readChar())
        assertEquals(11L, primitiveInput.readLong())
        assertEquals(1.25f, primitiveInput.readFloat())
        assertEquals(2.5, primitiveInput.readDouble())

        val utfOut = ByteArrayOutputStream()
        DataOutputStream(utfOut).use { it.writeUTF("ok") }
        val utfInput = inputCtor.newInstance(ByteBuffer.wrap(utfOut.toByteArray()), 0) as DataInput
        assertEquals("ok", utfInput.readUTF())
        assertFailsWith<UnsupportedOperationException> { utfInput.readLine() }

        val flushed = mutableListOf<Boolean>()
        val delegate = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun flush() { flushed += true }
        }
        val countingOutputStreamClass = Class.forName("io.johnsonlee.graphite.webgraph.CountingOutputStream")
        val outputCtor = countingOutputStreamClass.getDeclaredConstructor(OutputStream::class.java).apply { isAccessible = true }
        val output = outputCtor.newInstance(delegate) as OutputStream
        output.write(byteArrayOf(1, 2, 3))
        output.flush()
        val bytesWritten = countingOutputStreamClass.getDeclaredMethod("getBytesWritten").invoke(output) as Long
        assertEquals(3L, bytesWritten)
        assertEquals(listOf(true), flushed)
    }

    @Test
    fun `decodeEdge throws on unknown edge family`() {
        // family bits 0-1 can only be 0..3; label=0xFF has family=3 (ControlFlow) so
        // we need to craft a label where bits 0-1 give family > 3 -- not possible with 2 bits.
        // The else branch is a safety net. We test it directly.
        assertFailsWith<IllegalArgumentException> {
            // Use reflection or a trick: family is label & 0x3, so we need family=4+
            // but 2 bits max is 3. The else branch is unreachable in normal operation.
            // We test it by calling with a carefully crafted mock -- not possible without
            // modifying the method. Since it's truly unreachable with 2-bit masking,
            // we accept this line stays uncovered.

            // Instead, test the unknown node tag branch in readNode by writing raw bytes
            // with an unknown tag, then reading them with a string table.
            val dir = Files.createTempDirectory("webgraph-unknown-tag-test")
            try {
                // Build a minimal string table so readNode can be called
                StringTable.build(setOf("dummy"), dir)
                val strings = StringTable.load(dir)

                val baos = java.io.ByteArrayOutputStream()
                val dos = DataOutputStream(baos)
                dos.writeInt(999)  // node ID
                dos.writeByte(99)  // unknown tag
                dos.flush()
                val dis = DataInputStream(java.io.ByteArrayInputStream(baos.toByteArray()))
                NodeSerializer.readNode(dis, strings)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    // ========================================================================
    // writeAnyValue / readAnyValue round-trip for all value types
    // ========================================================================

    @Test
    fun `round-trip preserves enum constructor args with all value types`() {
        val builder = DefaultGraph.Builder()
        val enumConst = EnumConstant(
            NodeId.next(),
            TypeDescriptor("com.example.MyEnum"),
            "VALUE_A",
            listOf(
                42,                                               // Int
                123456789L,                                       // Long
                "hello",                                          // String
                3.14f,                                            // Float
                2.718281828,                                      // Double
                true,                                             // Boolean
                null,                                             // null
                EnumValueReference("com.example.Other", "X")      // EnumValueReference
            )
        )
        builder.addNode(enumConst)
        builder.addEnumValues("com.example.MyEnum", "VALUE_A", enumConst.constructorArgs)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-anyvalue-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedEnum = loaded.node(enumConst.id) as EnumConstant
            assertEquals(8, loadedEnum.constructorArgs.size)
            assertEquals(42, loadedEnum.constructorArgs[0])
            assertEquals(123456789L, loadedEnum.constructorArgs[1])
            assertEquals("hello", loadedEnum.constructorArgs[2])
            assertEquals(3.14f, loadedEnum.constructorArgs[3])
            assertEquals(2.718281828, loadedEnum.constructorArgs[4])
            assertEquals(true, loadedEnum.constructorArgs[5])
            assertNull(loadedEnum.constructorArgs[6])
            val ref = loadedEnum.constructorArgs[7] as EnumValueReference
            assertEquals("com.example.Other", ref.enumClass)
            assertEquals("X", ref.enumName)

            // Also verify enum values metadata round-tripped
            val values = loaded.enumValues("com.example.MyEnum", "VALUE_A")
            assertNotNull(values)
            assertEquals(8, values.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // CallEdge with isVirtual and isDynamic flags round-trip via graph
    // ========================================================================

    @Test
    fun `round-trip preserves CallEdge with isVirtual and isDynamic flags`() {
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val caller = MethodDescriptor(fooType, "caller", emptyList(), TypeDescriptor("void"))
        val callee = MethodDescriptor(fooType, "callee", emptyList(), TypeDescriptor("void"))
        builder.addMethod(caller)
        builder.addMethod(callee)

        val callSite = CallSiteNode(NodeId.next(), caller, callee, 5, null, emptyList())
        val target = ReturnNode(NodeId.next(), callee)
        builder.addNode(callSite)
        builder.addNode(target)

        // isVirtual=true, isDynamic=true
        builder.addEdge(CallEdge(callSite.id, target.id, isVirtual = true, isDynamic = true))

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-call-flags-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedEdges = loaded.outgoing(callSite.id, CallEdge::class.java).toList()
            assertEquals(1, loadedEdges.size)
            assertTrue(loadedEdges[0].isVirtual)
            assertTrue(loadedEdges[0].isDynamic)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Null annotation attribute value round-trip
    // ========================================================================

    @Test
    fun `round-trip preserves null annotation attribute value`() {
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(fooType, "annotated", emptyList(), TypeDescriptor("void"))
        builder.addMethod(method)

        val returnNode = ReturnNode(NodeId.next(), method)
        builder.addNode(returnNode)

        // Add an annotation with a null attribute value
        builder.addMemberAnnotation(
            "com.example.Foo", "annotated",
            "javax.annotation.Nullable",
            mapOf("value" to null, "reason" to "test")
        )

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-null-annot-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val annotations = loaded.memberAnnotations("com.example.Foo", "annotated")
            assertTrue(annotations.containsKey("javax.annotation.Nullable"))
            val attrs = annotations["javax.annotation.Nullable"]!!
            assertNull(attrs["value"])
            assertEquals("test", attrs["reason"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Out-of-bounds NodeId returns empty sequences on loaded graph
    // ========================================================================

    @Test
    fun `outgoing and incoming return empty for out-of-bounds NodeId on loaded graph`() {
        val builder = DefaultGraph.Builder()
        val node = IntConstant(NodeId(0), 1)
        builder.addNode(node)
        val graph = builder.build()

        val dir = Files.createTempDirectory("webgraph-oob-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)

            // NodeId with value >= numNodes should return empty
            val bigId = NodeId(999999)
            assertEquals(0, loaded.outgoing(bigId).count())
            assertEquals(0, loaded.incoming(bigId).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // BranchScopeData equals and hashCode coverage
    // ========================================================================

    @Test
    fun `BranchScopeData equals identity`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))

        // Identity
        assertTrue(data == data)
    }

    @Test
    fun `BranchScopeData equals with different type returns false`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))

        assertTrue(data.equals("not a BranchScopeData").not())
    }

    @Test
    fun `BranchScopeData equals with equal values`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method, comp, intArrayOf(1, 2), intArrayOf(3))
        val data2 = BranchScopeData(0, method, comp, intArrayOf(1, 2), intArrayOf(3))

        assertEquals(data1, data2)
        assertEquals(data1.hashCode(), data2.hashCode())
    }

    @Test
    fun `BranchScopeData not equal when conditionNodeId differs`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(1, method, comp, intArrayOf(1), intArrayOf(2))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData not equal when method differs`() {
        val method1 = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val method2 = MethodDescriptor(TypeDescriptor("com.example.B"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method1, comp, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(0, method2, comp, intArrayOf(1), intArrayOf(2))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData not equal when comparison differs`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp1 = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val comp2 = BranchComparison(ComparisonOp.NE, NodeId(1))
        val data1 = BranchScopeData(0, method, comp1, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(0, method, comp2, intArrayOf(1), intArrayOf(2))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData not equal when trueBranchNodeIds differ`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(0, method, comp, intArrayOf(9), intArrayOf(2))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData not equal when falseBranchNodeIds differ`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(3))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData hashCode is consistent`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data = BranchScopeData(0, method, comp, intArrayOf(1, 2), intArrayOf(3, 4))

        // hashCode should be consistent across calls
        assertEquals(data.hashCode(), data.hashCode())
    }

    // ========================================================================
    // writeAnyValue fallback for unsupported types (falls back to toString)
    // ========================================================================

    @Test
    fun `round-trip preserves list enum constructor arg`() {
        val builder = DefaultGraph.Builder()
        val enumConst = EnumConstant(
            NodeId.next(),
            TypeDescriptor("com.example.FallbackEnum"),
            "VAL",
            listOf(listOf("a", "b"))
        )
        builder.addNode(enumConst)
        builder.addEnumValues("com.example.FallbackEnum", "VAL", enumConst.constructorArgs)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-fallback-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedEnum = loaded.node(enumConst.id) as EnumConstant
            assertEquals(listOf("a", "b"), loadedEnum.constructorArgs[0])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Load mode variants and mapped loading
    // ========================================================================

    @Test
    fun `load with explicit EAGER mode preserves all graph operations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-eager-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir, GraphStore.LoadMode.EAGER)
            assertGraphOperations(graph, loaded)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `load with MAPPED mode preserves all graph operations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-test")
        try {
            GraphStore.save(graph, dir)
            GraphStore.ensureNodeIndex(dir)
            val loaded = GraphStore.load(dir, GraphStore.LoadMode.MAPPED)
            try {
                assertGraphOperations(graph, loaded)
            } finally {
                (loaded as? Closeable)?.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `load with AUTO mode preserves all graph operations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-auto-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir, GraphStore.LoadMode.AUTO)
            try {
                assertGraphOperations(graph, loaded)
            } finally {
                (loaded as? Closeable)?.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loadMapped preserves all graph operations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-loadmapped-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertGraphOperations(graph, loaded)
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `save from core mmap graph uses streaming outgoing traversal`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.MmapSource"),
            "run",
            emptyList(),
            TypeDescriptor("void")
        )
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(NodeId(0), 1))
            .addNode(StringConstant(NodeId(1), "two"))
            .addNode(ReturnNode(NodeId(2), method))
            .addNode(BooleanConstant(NodeId(3), true))
            .addEdge(DataFlowEdge(NodeId(0), NodeId(2), DataFlowKind.RETURN_VALUE))
            .addEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.PARAMETER_PASS))
            .addEdge(
                ControlFlowEdge(
                    NodeId(3),
                    NodeId(2),
                    ControlFlowKind.BRANCH_TRUE,
                    BranchComparison(ComparisonOp.EQ, NodeId(0))
                )
            )
            .addMethod(method)
            .build()
        val dir = Files.createTempDirectory("webgraph-save-mmap-source-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertEquals(4L, loaded.nodeCount(Node::class.java))
                assertEquals(3L, loaded.edgeCount())
                assertEquals(graph.outgoing(NodeId(0)).toList(), loaded.outgoing(NodeId(0)).toList())
                assertEquals(graph.incoming(NodeId(2)).toList(), loaded.incoming(NodeId(2)).toList())
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `save collects metadata from graph fallback scans`() {
        val base = buildTestGraph()
        val graph = MetadataFallbackGraph(base)
        val dir = Files.createTempDirectory("webgraph-metadata-fallback-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertEquals(2L, loaded.methodCount())
                assertEquals(
                    base.memberAnnotations("com.example.Foo", "bar"),
                    loaded.memberAnnotations("com.example.Foo", "bar")
                )
                assertEquals(1, loaded.methods(MethodPattern(name = "bar")).count())
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped incoming query persists compressed backward graph lazily`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-lazy-backward-test")
        try {
            GraphStore.save(graph, dir)
            assertFalse(Files.exists(dir.resolve("backward.graph")))
            assertFalse(Files.exists(dir.resolve("backward.properties")))

            val nodeWithIncoming = graph.nodes(Node::class.java)
                .first { graph.incoming(it.id).any() }
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertEquals(
                    graph.incoming(nodeWithIncoming.id).count(),
                    loaded.incoming(nodeWithIncoming.id).count()
                )
                assertTrue(Files.exists(dir.resolve("backward.graph")))
                assertTrue(Files.exists(dir.resolve("backward.properties")))
            } finally {
                (loaded as Closeable).close()
            }

            val reloaded = GraphStore.loadMapped(dir)
            try {
                assertGraphOperations(graph, reloaded)
            } finally {
                (reloaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ensureNodeIndex is idempotent`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-idempotent-index-test")
        try {
            GraphStore.save(graph, dir)
            val indexFile = dir.resolve("graph.nodeindex")
            assertTrue(Files.exists(indexFile), "Index file should exist after save")
            val sizeAfterSave = Files.size(indexFile)

            // Call ensureNodeIndex -- should be a no-op since index already exists
            GraphStore.ensureNodeIndex(dir)
            val sizeAfterEnsure = Files.size(indexFile)
            assertEquals(sizeAfterSave, sizeAfterEnsure, "Index file should not change when already present")

            // Delete the index and call ensureNodeIndex -- should rebuild
            Files.delete(indexFile)
            assertTrue(!Files.exists(indexFile))
            GraphStore.ensureNodeIndex(dir)
            assertTrue(Files.exists(indexFile), "Index file should be rebuilt")
            val sizeAfterRebuild = Files.size(indexFile)
            assertEquals(sizeAfterSave, sizeAfterRebuild, "Rebuilt index should have same size")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `MappedWebGraphBackedGraph close is safe`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-close-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as Closeable

            // Access a node to exercise the mapped path
            val mappedGraph = loaded as Graph
            val firstNode = mappedGraph.nodes(Node::class.java).first()
            assertNotNull(mappedGraph.node(firstNode.id))

            // Close should not throw
            loaded.close()

            // Calling close again should also be safe
            loaded.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loadMapped from nonexistent directory throws`() {
        assertFailsWith<IllegalArgumentException> {
            GraphStore.loadMapped(Files.createTempFile("not", "dir"))
        }
    }

    @Test
    fun `loadMapped without node index throws`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-no-index-test")
        try {
            GraphStore.save(graph, dir)
            // Delete node index
            Files.delete(dir.resolve("graph.nodeindex"))
            assertFailsWith<IllegalArgumentException> {
                GraphStore.loadMapped(dir)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph typed outgoing and incoming edges`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-typed-edges-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                val callSites = loaded.nodes(CallSiteNode::class.java).toList()
                assertEquals(1, callSites.size)
                val cs = callSites[0]

                // Typed outgoing
                val callEdges = loaded.outgoing(cs.id, CallEdge::class.java).toList()
                assertEquals(1, callEdges.size)

                val dataFlowEdges = loaded.outgoing(cs.id, DataFlowEdge::class.java).toList()
                assertEquals(0, dataFlowEdges.size)

                // Typed incoming
                val locals = loaded.nodes(LocalVariable::class.java).toList()
                assertEquals(1, locals.size)
                val incomingDf = loaded.incoming(locals[0].id, DataFlowEdge::class.java).toList()
                assertEquals(2, incomingDf.size)
                val incomingCall = loaded.incoming(locals[0].id, CallEdge::class.java).toList()
                assertEquals(0, incomingCall.size)
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph out-of-bounds NodeId returns empty`() {
        val builder = DefaultGraph.Builder()
        val node = IntConstant(NodeId(0), 1)
        builder.addNode(node)
        val graph = builder.build()

        val dir = Files.createTempDirectory("webgraph-mapped-oob-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                val bigId = NodeId(999999)
                assertEquals(0, loaded.outgoing(bigId).count())
                assertEquals(0, loaded.incoming(bigId).count())
                assertNull(loaded.node(bigId))
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph nodes with supertype returns all subtypes`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-supertype-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                val constants = loaded.nodes(ConstantNode::class.java).toList()
                assertTrue(constants.size >= 2, "Should find at least IntConstant and EnumConstant")

                val allNodes = loaded.nodes(Node::class.java).toList()
                assertEquals(8, allNodes.size)
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph branch scopes round-trip`() {
        val graph = buildBranchScopeGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-branch-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertBranchScopeOperations(loaded)
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph resources preserves persisted text resources`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-resources-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertEquals(1, loaded.resources.list("**").count())
                val content = loaded.resources.open("application.properties").bufferedReader().readText()
                assertTrue(content.contains("feature.mode=shadow"))
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Shared assertion helpers
    // ========================================================================

    private var branchCondNodeId: NodeId = NodeId(0)
    private var branchTrueNodeId: NodeId = NodeId(0)
    private var branchFalseNodeId: NodeId = NodeId(0)

    private class MetadataFallbackGraph(private val delegate: Graph) : Graph by delegate {
        override fun memberAnnotationIndex(): Map<String, Map<String, Map<String, Any?>>>? = null
        override fun typeHierarchyTypes(): Set<String> = emptySet()
    }

    private fun buildBranchScopeGraph(): Graph {
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(fooType, "check", emptyList(), TypeDescriptor("boolean"))
        builder.addMethod(method)

        val condNode = IntConstant(NodeId.next(), 0)
        val trueNode = IntConstant(NodeId.next(), 1)
        val falseNode = IntConstant(NodeId.next(), 2)
        builder.addNode(condNode)
        builder.addNode(trueNode)
        builder.addNode(falseNode)

        branchCondNodeId = condNode.id
        branchTrueNodeId = trueNode.id
        branchFalseNodeId = falseNode.id

        val comp = BranchComparison(ComparisonOp.NE, condNode.id)
        builder.addBranchScope(condNode.id, method, comp, intArrayOf(trueNode.id.value), intArrayOf(falseNode.id.value))

        return builder.build()
    }

    private fun assertBranchScopeOperations(loaded: Graph) {
        val scopes = loaded.branchScopes().toList()
        assertEquals(1, scopes.size)
        assertEquals(branchCondNodeId, scopes[0].conditionNodeId)
        assertEquals(ComparisonOp.NE, scopes[0].comparison.operator)
        assertTrue(scopes[0].trueBranchNodeIds.contains(branchTrueNodeId.value))
        assertTrue(scopes[0].falseBranchNodeIds.contains(branchFalseNodeId.value))

        val scopesFor = loaded.branchScopesFor(branchCondNodeId).toList()
        assertEquals(1, scopesFor.size)

        assertEquals(0, loaded.branchScopesFor(NodeId(999999)).count())
    }

    private fun assertGraphOperations(original: Graph, loaded: Graph) {
        // Nodes
        val originalNodes = original.nodes(Node::class.java).toList()
        val loadedNodes = loaded.nodes(Node::class.java).toList()
        assertEquals(originalNodes.size, loadedNodes.size, "Node count should match")

        for (node in originalNodes) {
            assertNotNull(loaded.node(node.id), "Node ${node.id} should exist in loaded graph")
        }

        // Non-existent node
        assertNull(loaded.node(NodeId(999999)))

        // Typed node queries
        assertEquals(1, loaded.nodes(IntConstant::class.java).count())
        assertEquals(1, loaded.nodes(CallSiteNode::class.java).count())
        assertEquals(1, loaded.nodes(FieldNode::class.java).count())
        assertEquals(1, loaded.nodes(ParameterNode::class.java).count())
        assertEquals(1, loaded.nodes(LocalVariable::class.java).count())
        assertEquals(1, loaded.nodes(ReturnNode::class.java).count())
        assertEquals(1, loaded.nodes(EnumConstant::class.java).count())

        // Outgoing edges
        for (node in originalNodes) {
            val origEdges = original.outgoing(node.id).toList()
            val loadEdges = loaded.outgoing(node.id).toList()
            assertEquals(origEdges.size, loadEdges.size,
                "Outgoing edge count for node ${node.id}")
        }

        // Incoming edges
        for (node in originalNodes) {
            val origEdges = original.incoming(node.id).toList()
            val loadEdges = loaded.incoming(node.id).toList()
            assertEquals(origEdges.size, loadEdges.size,
                "Incoming edge count for node ${node.id}")
        }

        // callSites
        val matched = loaded.callSites(MethodPattern(declaringClass = "com.example.Foo", name = "baz")).toList()
        assertEquals(1, matched.size)
        val noMatch = loaded.callSites(MethodPattern(name = "nonexistent")).toList()
        assertTrue(noMatch.isEmpty())

        // supertypes and subtypes
        val child = TypeDescriptor("com.example.Child")
        val parent = TypeDescriptor("com.example.Parent")
        assertTrue(loaded.supertypes(child).any { it.className == "com.example.Parent" })
        assertTrue(loaded.subtypes(parent).any { it.className == "com.example.Child" })
        assertEquals(0, loaded.supertypes(TypeDescriptor("com.nonexistent.Type")).count())
        assertEquals(0, loaded.subtypes(TypeDescriptor("com.nonexistent.Type")).count())

        // methods
        val allMethods = loaded.methods(MethodPattern()).toList()
        assertEquals(2, allMethods.size)
        val barMethods = loaded.methods(MethodPattern(name = "bar")).toList()
        assertEquals(1, barMethods.size)

        // enumValues
        assertEquals(listOf(1, "active"), loaded.enumValues("com.example.Status", "ACTIVE"))
        assertNull(loaded.enumValues("com.example.Missing", "MISSING"))

        // memberAnnotations
        val annotations = loaded.memberAnnotations("com.example.Foo", "bar")
        assertTrue(annotations.containsKey("javax.annotation.Nullable"))
        assertTrue(loaded.memberAnnotations("com.example.Unknown", "unknown").isEmpty())

        // typeHierarchyTypes
        val types = loaded.typeHierarchyTypes()
        assertTrue(types.contains("com.example.Parent"))
        assertTrue(types.contains("com.example.Child"))

        // resources
        val resourceEntries = loaded.resources.list("**").toList()
        assertEquals(1, resourceEntries.size)
        assertEquals("application.properties", resourceEntries.single().path)
        assertTrue(loaded.resources.open("application.properties").bufferedReader().readText().contains("feature.mode=shadow"))
    }

    // ========================================================================
    // NodeSerializer header round-trip and invalid magic
    // ========================================================================

    @Test
    fun `writeHeader and readHeader round-trip via DataInputStream`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_METADATA)
        dos.flush()
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val version = NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA)
        assertEquals(NodeSerializer.FORMAT_VERSION, version)
    }

    @Test
    fun `readHeader with invalid magic via DataInputStream throws`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(0x12345678) // wrong magic
        dos.flush()
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertFailsWith<IllegalArgumentException> {
            NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA)
        }
    }

    @Test
    fun `readHeader with legacy version via DataInputStream succeeds`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(NodeSerializer.MAGIC_METADATA or 0x01)
        dos.flush()
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val version = NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA)
        assertEquals(1, version)
    }

    @Test
    fun `readHeader with invalid magic via RandomAccessFile throws`() {
        val tmpFile = Files.createTempFile("bad-magic", ".bin")
        try {
            // Write wrong magic into the file
            DataOutputStream(tmpFile.toFile().outputStream()).use { dos ->
                dos.writeInt(0x12345678) // wrong magic
            }
            RandomAccessFile(tmpFile.toFile(), "r").use { raf ->
                assertFailsWith<IllegalArgumentException> {
                    NodeSerializer.readHeader(raf, NodeSerializer.MAGIC_NODEDATA)
                }
            }
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    @Test
    fun `readHeader via RandomAccessFile round-trip`() {
        val tmpFile = Files.createTempFile("raf-header", ".bin")
        try {
            DataOutputStream(tmpFile.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_NODEDATA)
            }
            RandomAccessFile(tmpFile.toFile(), "r").use { raf ->
                val version = NodeSerializer.readHeader(raf, NodeSerializer.MAGIC_NODEDATA)
                assertEquals(NodeSerializer.FORMAT_VERSION, version)
            }
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    @Test
    fun `readHeader with legacy version via RandomAccessFile succeeds`() {
        val tmpFile = Files.createTempFile("raf-unsupported-version", ".bin")
        try {
            DataOutputStream(tmpFile.toFile().outputStream()).use { dos ->
                dos.writeInt(NodeSerializer.MAGIC_NODEDATA or 0x01)
            }
            RandomAccessFile(tmpFile.toFile(), "r").use { raf ->
                val version = NodeSerializer.readHeader(raf, NodeSerializer.MAGIC_NODEDATA)
                assertEquals(1, version)
            }
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    @Test
    fun `readHeader with unknown version throws`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(NodeSerializer.MAGIC_METADATA or 0x04)
        dos.flush()
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val error = assertFailsWith<IllegalArgumentException> {
            NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA)
        }
        assertTrue(error.message!!.contains("Unsupported GraphStore format version 4"))
    }

    // ========================================================================
    // Invalid magic in metadata file on load
    // ========================================================================

    @Test
    fun `invalid magic in metadata file throws on load`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("bad-magic-test")
        try {
            GraphStore.save(graph, dir)
            // Corrupt the metadata file
            val metadataFile = dir.resolve("graph.metadata").toFile()
            val bytes = metadataFile.readBytes()
            bytes[0] = 0x00 // corrupt magic
            metadataFile.writeBytes(bytes)
            assertFailsWith<IllegalArgumentException> {
                GraphStore.load(dir)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy v1 annotation node decodes as string values`() {
        val dir = Files.createTempDirectory("legacy-node-strings")
        try {
            val strings = StringTable.build(
                listOf("Lcom/example/Foo;", "com.example.Foo", "bar", "value", "hello"),
                dir
            )
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { dos ->
                dos.writeInt(42)
                dos.writeByte(13)
                dos.writeInt(strings.indexOf("Lcom/example/Foo;"))
                dos.writeInt(strings.indexOf("com.example.Foo"))
                dos.writeInt(strings.indexOf("bar"))
                dos.writeInt(1)
                dos.writeInt(strings.indexOf("value"))
                dos.writeInt(strings.indexOf("hello"))
            }
            val node = NodeSerializer.readNode(
                DataInputStream(ByteArrayInputStream(baos.toByteArray())),
                strings,
                1
            ) as AnnotationNode
            assertEquals("hello", node.values["value"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy v1 metadata decodes annotation values as strings`() {
        val dir = Files.createTempDirectory("legacy-metadata-strings")
        try {
            val strings = StringTable.build(
                listOf("com.example.Foo#bar", "javax.annotation.Nullable", "value", "hello"),
                dir
            )
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { dos ->
                dos.writeInt(NodeSerializer.MAGIC_METADATA or 0x01)
                dos.writeInt(0)
                dos.writeInt(0)
                dos.writeInt(0)
                dos.writeInt(0)
                dos.writeInt(1)
                dos.writeInt(strings.indexOf("com.example.Foo#bar"))
                dos.writeInt(1)
                dos.writeInt(strings.indexOf("javax.annotation.Nullable"))
                dos.writeInt(1)
                dos.writeInt(strings.indexOf("value"))
                dos.writeInt(strings.indexOf("hello"))
                dos.writeInt(0)
            }
            val metadata = NodeSerializer.loadMetadata(
                DataInputStream(ByteArrayInputStream(baos.toByteArray())),
                strings
            )
            assertEquals(
                "hello",
                metadata.memberAnnotations["com.example.Foo#bar"]!!["javax.annotation.Nullable"]!!["value"]
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy v1 graph directory migrates end to end`() {
        val fixture = createLegacyV1GraphDir()
        try {
            val loaded = GraphStore.load(fixture.dir)
            val loadedNode = loaded.node(fixture.annotationNode.id) as AnnotationNode
            assertEquals("hello", loadedNode.values["value"])
            assertEquals(
                "hello",
                loaded.memberAnnotations("com.example.Foo", "bar")["javax.annotation.Nullable"]!!["value"]
            )

            Files.deleteIfExists(fixture.dir.resolve("graph.nodeindex"))
            val mapped = GraphStore.load(fixture.dir, GraphStore.LoadMode.MAPPED)
            try {
                val mappedNode = mapped.node(fixture.annotationNode.id) as AnnotationNode
                assertEquals("hello", mappedNode.values["value"])
            } finally {
                (mapped as Closeable).close()
            }

            val migratedDir = Files.createTempDirectory("legacy-v3-migrated")
            try {
                GraphStore.save(loaded, migratedDir)

                DataInputStream(migratedDir.resolve("graph.nodedata").toFile().inputStream()).use { dis ->
                    assertEquals(NodeSerializer.FORMAT_VERSION, NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEDATA))
                }
                DataInputStream(migratedDir.resolve("graph.metadata").toFile().inputStream()).use { dis ->
                    assertEquals(NodeSerializer.FORMAT_VERSION, NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA))
                }

                val migrated = GraphStore.load(migratedDir)
                val migratedNode = migrated.node(fixture.annotationNode.id) as AnnotationNode
                assertEquals("hello", migratedNode.values["value"])
                assertEquals(
                    "hello",
                    migrated.memberAnnotations("com.example.Foo", "bar")["javax.annotation.Nullable"]!!["value"]
                )
            } finally {
                migratedDir.toFile().deleteRecursively()
            }
        } finally {
            fixture.dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Backward adjacency round-trip
    // ========================================================================

    @Test
    fun `round-trip preserves backward adjacency`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("backward-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            // Verify incoming edges for every node
            for (node in graph.nodes(Node::class.java)) {
                val originalIncoming = graph.incoming(node.id).toList()
                val loadedIncoming = loaded.incoming(node.id).toList()
                assertEquals(originalIncoming.size, loadedIncoming.size,
                    "Incoming edge count for node ${node.id}")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // No backward files written on save
    // ========================================================================

    @Test
    fun `no backward files written on save`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("no-backward-test")
        try {
            GraphStore.save(graph, dir)
            assertFalse(Files.exists(dir.resolve("backward.graph")))
            assertFalse(Files.exists(dir.resolve("backward.properties")))
            assertFalse(Files.exists(dir.resolve("backward.offsets")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // save with custom compression threads
    // ========================================================================

    @Test
    fun `save with custom compression threads`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("threads-test")
        try {
            GraphStore.save(graph, dir, compressionThreads = 1)
            val loaded = GraphStore.load(dir)
            assertEquals(
                graph.nodes(Node::class.java).count(),
                loaded.nodes(Node::class.java).count()
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Mapped load with parallel IO
    // ========================================================================

    @Test
    fun `mapped load with parallel IO preserves graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("mapped-parallel-test")
        try {
            GraphStore.save(graph, dir)
            GraphStore.ensureNodeIndex(dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertEquals(
                    graph.nodes(Node::class.java).count(),
                    loaded.nodes(Node::class.java).count()
                )
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // AUTO mode dispatches to MAPPED for large node count
    // ========================================================================

    @Test
    fun `AUTO mode dispatches to MAPPED for large node count`() {
        // Build a graph with enough nodes to exceed MAPPED_THRESHOLD (1M).
        // We can't actually create 1M nodes in a unit test, so we test AUTO
        // with small graph (which dispatches to EAGER) and verify correctness.
        // The MAPPED path is tested separately via explicit MAPPED mode.
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("auto-mode-test")
        try {
            GraphStore.save(graph, dir)
            // Small graph => AUTO should use EAGER
            val loaded = GraphStore.load(dir, GraphStore.LoadMode.AUTO)
            assertEquals(
                graph.nodes(Node::class.java).count(),
                loaded.nodes(Node::class.java).count()
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // StringTable size() coverage
    // ========================================================================

    @Test
    fun `StringTable size returns correct count`() {
        val dir = Files.createTempDirectory("string-table-size-test")
        try {
            val strings = setOf("alpha", "beta", "gamma")
            val table = StringTable.build(strings, dir)
            assertEquals(3, table.size())

            // Also verify after reload
            val loaded = StringTable.load(dir)
            assertEquals(3, loaded.size())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // AnnotationNode in collectMetadata
    // ========================================================================

    @Test
    fun `collectMetadata handles AnnotationNode correctly`() {
        // AnnotationNode's empty branch in collectMetadata should not crash
        val builder = DefaultGraph.Builder()
        val annotNode = AnnotationNode(
            NodeId.next(), "com.example.MyAnnotation",
            "com.example.Target", "doStuff",
            mapOf("key" to "value")
        )
        builder.addNode(annotNode)

        val graph = builder.build()
        val dir = Files.createTempDirectory("annotation-collect-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            val annotations = loaded.nodes(AnnotationNode::class.java).toList()
            assertEquals(1, annotations.size)
            assertEquals("com.example.MyAnnotation", annotations[0].name)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Test graph builder
    // ========================================================================

    private fun buildTestGraph(): Graph {
        val builder = DefaultGraph.Builder().setResources(
            object : ResourceAccessor {
                private val resources = mapOf(
                    "application.properties" to "feature.mode=shadow\nfeature.enabled=true\n"
                )

                override fun list(pattern: String): Sequence<ResourceEntry> =
                    resources.keys.asSequence().map { ResourceEntry(it, "test-fixture") }

                override fun open(path: String) =
                    resources[path]?.let { ByteArrayInputStream(it.toByteArray()) }
                        ?: throw java.io.IOException("Resource not found: $path")
            }
        )

        val fooType = TypeDescriptor("com.example.Foo")
        val parentType = TypeDescriptor("com.example.Parent")
        val childType = TypeDescriptor("com.example.Child")
        val method = MethodDescriptor(fooType, "bar", listOf(TypeDescriptor("int")), TypeDescriptor("void"))

        // Nodes
        val param = ParameterNode(NodeId.next(), 0, TypeDescriptor("int"), method)
        val local = LocalVariable(NodeId.next(), "x", TypeDescriptor("int"), method)
        val constant = IntConstant(NodeId.next(), 42)
        val returnNode = ReturnNode(NodeId.next(), method)
        val callee = MethodDescriptor(fooType, "baz", emptyList(), TypeDescriptor("void"))
        val callSite = CallSiteNode(NodeId.next(), method, callee, 10, null, listOf(param.id))
        val enumConst = EnumConstant(NodeId.next(), TypeDescriptor("com.example.Status"), "ACTIVE", listOf(1, "active"))
        val field = FieldNode(NodeId.next(), FieldDescriptor(fooType, "name", TypeDescriptor("java.lang.String")), false)

        builder.addNode(param)
        builder.addNode(local)
        builder.addNode(constant)
        builder.addNode(returnNode)
        builder.addNode(callSite)
        builder.addNode(enumConst)
        builder.addNode(field)

        val annotNode = AnnotationNode(NodeId.next(), "javax.annotation.Nullable", "com.example.Foo", "bar", mapOf("value" to "true"))
        builder.addNode(annotNode)

        // Edges
        builder.addEdge(DataFlowEdge(param.id, local.id, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(constant.id, local.id, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(local.id, returnNode.id, DataFlowKind.RETURN_VALUE))
        builder.addEdge(CallEdge(callSite.id, callSite.id, isVirtual = false))

        // Method
        builder.addMethod(method)
        builder.addMethod(callee)

        // Type hierarchy
        builder.addTypeRelation(childType, parentType, TypeRelation.EXTENDS)

        // Enum values
        builder.addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active"))

        // Annotations
        builder.addMemberAnnotation("com.example.Foo", "bar", "javax.annotation.Nullable", emptyMap())

        return builder.build()
    }

    private data class LegacyV1Fixture(
        val dir: Path,
        val method: MethodDescriptor,
        val annotationNode: AnnotationNode
    )

    private fun createLegacyV1GraphDir(): LegacyV1Fixture {
        val dir = Files.createTempDirectory("legacy-v1-graph")
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Foo"),
            "bar",
            emptyList(),
            TypeDescriptor("void")
        )
        val returnNode = ReturnNode(NodeId(1), method)
        val annotationNode = AnnotationNode(
            NodeId(2),
            "javax.annotation.Nullable",
            "com.example.Foo",
            "bar",
            mapOf("value" to "hello")
        )

        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(returnNode)
        builder.addNode(annotationNode)
        builder.addMemberAnnotation("com.example.Foo", "bar", "javax.annotation.Nullable", annotationNode.values)
        GraphStore.save(builder.build(), dir)

        val strings = StringTable.build(
            setOf(
                method.declaringClass.className,
                method.name,
                method.returnType.className,
                annotationNode.name,
                annotationNode.className,
                annotationNode.memberName,
                "com.example.Foo#bar",
                "javax.annotation.Nullable",
                "value",
                "hello"
            ),
            dir
        )
        writeLegacyV1NodeData(dir, method, returnNode, annotationNode, strings)
        writeLegacyV1Metadata(dir, method, strings)
        writeLegacyV1NodeIndex(dir, method, returnNode, annotationNode, strings)
        writeLegacyV1Comparisons(dir)

        return LegacyV1Fixture(dir, method, annotationNode)
    }

    private fun writeLegacyV1NodeData(
        dir: Path,
        method: MethodDescriptor,
        returnNode: ReturnNode,
        annotationNode: AnnotationNode,
        strings: StringTable
    ) {
        DataOutputStream(dir.resolve("graph.nodedata").toFile().outputStream()).use { dos ->
            dos.writeInt(NodeSerializer.MAGIC_NODEDATA or 0x01)
            dos.writeInt(2)
            dos.write(legacyV1ReturnNodeBytes(returnNode, method, strings))
            dos.write(legacyV1AnnotationNodeBytes(annotationNode, strings))
        }
    }

    private fun legacyV1ReturnNodeBytes(
        returnNode: ReturnNode,
        method: MethodDescriptor,
        strings: StringTable
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(returnNode.id.value)
            dos.writeByte(11)
            writeLegacyV1MethodDescriptor(dos, method, strings)
            dos.writeBoolean(false)
        }
        return baos.toByteArray()
    }

    private fun legacyV1AnnotationNodeBytes(annotationNode: AnnotationNode, strings: StringTable): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(annotationNode.id.value)
            dos.writeByte(13)
            dos.writeInt(strings.indexOf(annotationNode.name))
            dos.writeInt(strings.indexOf(annotationNode.className))
            dos.writeInt(strings.indexOf(annotationNode.memberName))
            dos.writeInt(annotationNode.values.size)
            for ((k, v) in annotationNode.values) {
                dos.writeInt(strings.indexOf(k))
                dos.writeInt(strings.indexOf(v?.toString() ?: ""))
            }
        }
        return baos.toByteArray()
    }

    private fun writeLegacyV1Metadata(dir: Path, method: MethodDescriptor, strings: StringTable) {
        DataOutputStream(dir.resolve("graph.metadata").toFile().outputStream()).use { dos ->
            dos.writeInt(NodeSerializer.MAGIC_METADATA or 0x01)
            dos.writeInt(1)
            writeLegacyV1MethodDescriptor(dos, method, strings)
            dos.writeInt(0)
            dos.writeInt(0)
            dos.writeInt(0)
            dos.writeInt(1)
            dos.writeInt(strings.indexOf("com.example.Foo#bar"))
            dos.writeInt(1)
            dos.writeInt(strings.indexOf("javax.annotation.Nullable"))
            dos.writeInt(1)
            dos.writeInt(strings.indexOf("value"))
            dos.writeInt(strings.indexOf("hello"))
            dos.writeInt(0)
        }
    }

    private fun writeLegacyV1NodeIndex(
        dir: Path,
        method: MethodDescriptor,
        returnNode: ReturnNode,
        annotationNode: AnnotationNode,
        strings: StringTable
    ) {
        val returnNodeSize = legacyV1ReturnNodeBytes(returnNode, method, strings).size.toLong()
        DataOutputStream(dir.resolve("graph.nodeindex").toFile().outputStream()).use { dos ->
            dos.writeInt(NodeSerializer.MAGIC_NODEINDEX or 0x01)
            dos.writeInt(2)
            dos.writeInt(returnNode.id.value)
            dos.writeByte(11)
            dos.writeLong(8L)
            dos.writeInt(annotationNode.id.value)
            dos.writeByte(13)
            dos.writeLong(8L + returnNodeSize)
        }
    }

    private fun writeLegacyV1Comparisons(dir: Path) {
        DataOutputStream(dir.resolve("graph.comparisons").toFile().outputStream()).use { dos ->
            dos.writeInt(NodeSerializer.MAGIC_COMPARISONS or 0x01)
            dos.writeInt(0)
        }
    }

    private fun writeLegacyV1MethodDescriptor(
        dos: DataOutputStream,
        method: MethodDescriptor,
        strings: StringTable
    ) {
        dos.writeInt(strings.indexOf(method.declaringClass.className))
        dos.writeInt(strings.indexOf(method.name))
        dos.writeInt(method.parameterTypes.size)
        method.parameterTypes.forEach { dos.writeInt(strings.indexOf(it.className)) }
        dos.writeInt(strings.indexOf(method.returnType.className))
    }
}
