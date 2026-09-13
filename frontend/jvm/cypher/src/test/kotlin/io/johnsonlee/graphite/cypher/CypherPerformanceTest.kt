package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Performance smoke tests for the Cypher query engine.
 * These verify queries complete within reasonable time bounds on a graph with 1200 nodes.
 */
class CypherPerformanceTest {

    private val graph: Graph by lazy { buildLargeGraph() }

    private fun buildLargeGraph(): Graph {
        NodeId.reset()
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val barType = TypeDescriptor("com.example.Bar")

        for (i in 1..500) {
            val method = MethodDescriptor(fooType, "method$i", emptyList(), TypeDescriptor("void"))
            val callee = MethodDescriptor(barType, "target${i % 50}", listOf(TypeDescriptor("int")), TypeDescriptor("void"))
            val csId = NodeId.next()
            val cs = CallSiteNode(csId, method, callee, i, null, emptyList())
            builder.addNode(cs)
            builder.addMethod(method)
            builder.addMethod(callee)

            val cId = NodeId.next()
            val c = IntConstant(cId, i)
            builder.addNode(c)
            builder.addEdge(DataFlowEdge(c.id, cs.id, DataFlowKind.PARAMETER_PASS))
        }

        for (i in 1..100) {
            builder.addNode(StringConstant(NodeId.next(), "value_$i"))
            builder.addNode(
                FieldNode(
                    NodeId.next(),
                    FieldDescriptor(fooType, "field$i", TypeDescriptor("java.lang.String")),
                    false
                )
            )
        }

        builder.addTypeRelation(barType, fooType, TypeRelation.EXTENDS)
        return builder.build()
    }

    private fun measure(query: String, maxMs: Long = 1000): Pair<Long, Int> {
        // Warmup
        repeat(3) { graph.query(query) }
        // Measure
        val start = System.nanoTime()
        val result = graph.query(query)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        println("$elapsed ms | ${result.rows.size} rows | $query")
        assertTrue(elapsed < maxMs, "Query took ${elapsed}ms, exceeds ${maxMs}ms: $query")
        return elapsed to result.rows.size
    }

    @Test
    fun `simple node match under 100ms`() {
        measure("MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 100", 100)
    }

    @Test
    fun `WHERE filter under 100ms`() {
        measure("MATCH (n:IntConstant) WHERE n.value > 100 AND n.value < 200 RETURN n.value", 100)
    }

    @Test
    fun `regex filter under 200ms`() {
        measure("MATCH (n:CallSiteNode) WHERE n.callee_class =~ 'com\\.example\\..*' RETURN n.callee_name LIMIT 50", 200)
    }

    @Test
    fun `single hop relationship under 200ms`() {
        measure("MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name LIMIT 50", 200)
    }

    @Test
    fun `aggregation under 200ms`() {
        measure("MATCH (n:CallSiteNode) RETURN n.callee_class, count(*) AS cnt", 200)
    }

    @Test
    fun `variable length path under 500ms`() {
        measure("MATCH (a:IntConstant)-[:DATAFLOW*..2]->(b:CallSiteNode) RETURN a.value, b.callee_name LIMIT 20", 500)
    }

    @Test
    fun `count star under 100ms`() {
        measure("MATCH (n) RETURN count(*)", 100)
    }

    @Test
    fun `WITH pipeline under 200ms`() {
        measure("MATCH (n:IntConstant) WITH n.value AS v WHERE v > 250 RETURN v ORDER BY v LIMIT 10", 200)
    }

    @Test
    fun `DISTINCT under 200ms`() {
        measure("MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class", 200)
    }

    @Test
    fun `function calls under 200ms`() {
        measure("MATCH (n:CallSiteNode) RETURN toLower(n.callee_name) LIMIT 50", 200)
    }
}
