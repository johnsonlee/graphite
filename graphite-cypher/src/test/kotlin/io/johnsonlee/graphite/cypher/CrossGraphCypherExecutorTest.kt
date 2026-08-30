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
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyLookup
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookupStrategy
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.TransformedStringPropertyLookup
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
    fun `parallel scan failure interrupts a task waiting for the same graph`() {
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
        val graphLock = directStringGraphLock(sharedGraph)
        val holderStarted = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holderThread = Executors.newSingleThreadExecutor()
        holderThread.submit {
            synchronized(sharedGraph) {
                graphLock.lock()
                try {
                    holderStarted.countDown()
                    check(releaseHolder.await(5, TimeUnit.SECONDS))
                } finally {
                    graphLock.unlock()
                }
            }
        }
        assertTrue(holderStarted.await(2, TimeUnit.SECONDS))

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
            releaseHolder.countDown()
            queryThread.shutdownNow()
            holderThread.shutdownNow()
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
