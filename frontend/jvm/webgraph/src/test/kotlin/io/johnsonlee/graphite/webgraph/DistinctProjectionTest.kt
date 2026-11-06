package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Verifies distinct projection. */
class DistinctProjectionTest : GraphStoreTestFixture() {

    @Test
    fun mappedDistinctProjectionFallsBackForUnsupportedCallSiteProperties() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    listOf(7, 9).forEachIndexed { index, line ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor("example.Target"), "call$index", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                line,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-unsupported-distinct-projection")
        try {
            GraphStore.save(graph, dir)
            val first = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            val second = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertNull(
                    first.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")),
                        projectedProperties = listOf("line"),
                        limit = 1))
                val result =
                    CrossGraphCypherExecutor(listOf(CypherGraph("first", first), CypherGraph("second", second)))
                        .execute(
                            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'Target' " + "RETURN DISTINCT n.line AS line LIMIT 1")

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
        val graph =
            DefaultGraph.Builder()
                .addNode(
                    CallSiteNode(
                        NodeId(0),
                        MethodDescriptor(TypeDescriptor("example.Other"), "call", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                        1,
                        null,
                        emptyList()))
                .addNode(
                    AnnotationNode(NodeId(1), "example.DynamicCall", "example.Owner", "call", mapOf("caller_class" to "example.Target")))
                .build()
        val dir = Files.createTempDirectory("webgraph-mixed-distinct-projection")
        try {
            GraphStore.save(graph, dir)
            val first = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            val second = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val result =
                    CrossGraphCypherExecutor(listOf(CypherGraph("first", first), CypherGraph("second", second)))
                        .execute(
                            "MATCH (n) WHERE n.caller_class CONTAINS \$term " + "RETURN DISTINCT n.caller_class AS caller LIMIT 10",
                            mapOf("term" to "Target"))

                assertEquals(listOf("example.Target"), result.rows.map { it["caller"] })
                val nonDistinct =
                    CrossGraphCypherExecutor(listOf(CypherGraph("first", first)))
                        .execute(
                            "MATCH (n) WHERE n.caller_class CONTAINS ${'$'}term " +
                                "RETURN n.caller_class AS caller, n.caller_name AS callerName LIMIT 10",
                            mapOf("term" to "Target"))
                assertEquals(listOf("example.Target"), nonDistinct.rows.map { it["caller"] })
                assertEquals(listOf(null), nonDistinct.rows.map { it["callerName"] })
            } finally {
                second.close()
                first.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
