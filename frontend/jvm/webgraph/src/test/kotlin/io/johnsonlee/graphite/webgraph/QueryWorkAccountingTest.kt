package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.GraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Verifies query work accounting. */
class QueryWorkAccountingTest : GraphStoreTestFixture() {

    @Test
    fun `buffered graph work does not replay a failed batch`() {
        var calls = 0
        val accounting =
            BufferedGraphWorkConsumer(
                object : GraphWorkBatchConsumer {
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
    fun `persistent index read wraps a rejected final accounting batch`() {
        val rejection = IllegalStateException("persistent read work rejected")
        val work =
            PersistentIndexReadWork(
                object : GraphWorkBatchConsumer {
                    override fun consume(workUnits: Long) {
                        throw rejection
                    }
                })
        work.consume()

        val failure = assertFailsWith<MappedCallSiteStringIndexReadAbortedException> { work.flush() }
        assertSame(rejection, failure.cause)
    }

    @Test
    fun `CallSite trigram posting budget exhaustion preserves dictionary scan correctness`() {
        val property = "graphite.webgraph.callSiteTrigramIndexBudgetBytes"
        val previous = System.getProperty(property)
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(3) { index ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(
                                    TypeDescriptor(if (index == 1) "example.Voucher" else "example.Feature$index"),
                                    "call$index",
                                    emptyList(),
                                    returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-callsite-trigram-budget")
        try {
            System.setProperty(property, "0")
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val ids =
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(
                                StringPropertyPredicate(
                                    "caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "voucher")))
                        .orEmpty()
                        .map { it.id.value }
                        .toList()

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
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(32) { index ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor("example.Caller$index"), "call$index", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-cold-aggregate-budget")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                val executor = CrossGraphCypherExecutor(listOf(CypherGraph("mapped", loaded)), CypherExecutionBudget(maxWorkUnits = 1))

                assertFailsWith<CypherBudgetExceededException> {
                    executor.execute("MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'missing' " + "RETURN count(*) AS total")
                }
                val mapped = loaded as MappedWebGraphBackedGraph
                val sparse =
                    mapped.aggregateStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.EQUALS, "example.Caller31")),
                        distinctProperty = "callee_name")
                assertEquals(1L, sparse?.count)
                assertEquals(setOf("invoke"), sparse?.distinctValues)
                val broad =
                    mapped.aggregateStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "example.Caller")),
                        distinctProperty = "caller_class")
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
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(32) { index ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor("example.Caller$index"), "call$index", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-warm-zero-aggregate-budget")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val predicates = listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Caller"))
                assertEquals(
                    32L, loaded.aggregateStringPropertyDisjunction(CallSiteNode::class.java, predicates, distinctProperty = null)?.count)
                assertTrue(loaded.isCallSiteStringIndexInitialized())

                val executor = CrossGraphCypherExecutor(listOf(CypherGraph("mapped", loaded)), CypherExecutionBudget(maxWorkUnits = 1))
                assertFailsWith<CypherBudgetExceededException> {
                    executor.execute("MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'missing' " + "RETURN count(*) AS total")
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
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(4) { index ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor("example.Target"), "call$index", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val predicate = StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")
        val dir = Files.createTempDirectory("webgraph-limited-lookup-budget")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertNotNull(loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate), limit = 1)?.single())
                var queryWork = 0L
                assertNotNull(
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java, listOf(predicate), limit = 1, workConsumer = GraphWorkConsumer { queryWork++ })
                        ?.single())
                assertTrue(queryWork > 0L)
                val context = CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = queryWork))
                val executor = CrossGraphCypherExecutor(listOf(CypherGraph("mapped", loaded)), context)
                val query = "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' " + "RETURN n.caller_class AS caller LIMIT 1"

                assertEquals("example.Target", executor.execute(query).rows.single()["caller"])
                assertFailsWith<CypherBudgetExceededException> { executor.execute(query) }
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
