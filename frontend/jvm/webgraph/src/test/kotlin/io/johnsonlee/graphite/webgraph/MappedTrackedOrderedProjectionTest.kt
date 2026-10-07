package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MmapGraph
import io.johnsonlee.graphite.graph.MmapGraphBuilder
import io.johnsonlee.graphite.graph.StreamingStringPropertyProjection
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MappedTrackedOrderedProjectionTest {
    @Test
    fun `tracked mapped projection counts sparse mixed and repeated current CallSites exactly`() {
        val nodes = listOf(
            call(0, "A", "first"), call(3, "old", "replaced"), StringConstant(NodeId(8), "unrelated"),
            call(13, "Z", "last"), call(3, "Å", "duplicate")
        )
        withStored(nodes) { mapped ->
            val values = mutableListOf<StringPropertyProjectionRow>()
            assertTrue(mapped.forEachStringPropertyProjection(
                CallSiteNode::class.java, listOf("callee_class", "callee_name", "callee_class"), {}, values::add
            ))
            assertEquals(listOf(
                listOf("A", "first", "A"), listOf("Å", "duplicate", "Å"),
                listOf("Z", "last", "Z"), listOf("Å", "duplicate", "Å")
            ), values.map { it.values })
            val streaming = object : Graph by mapped, StreamingStringPropertyProjection by mapped {
                override fun node(id: NodeId): Node? = error("Full node decoding is forbidden")
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> = error("Full node enumeration is forbidden")
            }
            val fallback = object : Graph by mapped {}
            for (budget in listOf(3L, 4L, 5L)) {
                val expectedContext = CypherExecutionContext(CypherExecutionBudget(budget))
                val actualContext = CypherExecutionContext(CypherExecutionBudget(budget))
                val expected = runCatching { CypherExecutor(fallback, expectedContext).execute(QUERY) }
                val actual = runCatching { CypherExecutor(streaming, actualContext).execute(QUERY) }
                assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
                assertEquals(expected.getOrNull()?.rows, actual.getOrNull()?.rows)
                assertEquals(expectedContext.diagnostics, actualContext.diagnostics)
                assertEquals(minOf(4L, budget), actualContext.diagnostics.workUnitsConsumed)
                if (budget >= 4) {
                    assertEquals(listOf(mapOf("c" to "A", "m" to "first", "again" to "A")), actual.getOrThrow().rows)
                } else {
                    assertEquals(budget, (actual.exceptionOrNull() as CypherBudgetExceededException).maxWorkUnits)
                }
            }
        }
    }

    @Test
    fun `late cross-type reused ID refuses before callbacks and retains tracked ordinary semantics`() {
        withStored(listOf(call(0, "A", "first"), call(3, "Z", "last"), call(13, "old", "stale"),
            StringConstant(NodeId(13), "replacement"))) { mapped ->
            assertFalse(mapped.forEachStringPropertyProjection(
                CallSiteNode::class.java, listOf("callee_class", "callee_name"),
                { error("Refusal must precede cancellation callback") }, { error("Refusal must precede every emitted row") }
            ))
            val fallback = object : Graph by mapped {}
            val query = QUERY.replace("LIMIT 1", "LIMIT 20")
            for (budget in listOf(2L, 3L)) {
                val expectedContext = CypherExecutionContext(CypherExecutionBudget(budget))
                val actualContext = CypherExecutionContext(CypherExecutionBudget(budget))
                val expected = runCatching { CypherExecutor(fallback, expectedContext).execute(query) }
                val actual = runCatching { CypherExecutor(mapped, actualContext).execute(query) }
                assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
                assertEquals(expected.getOrNull()?.rows, actual.getOrNull()?.rows)
                assertEquals(expectedContext.diagnostics, actualContext.diagnostics)
                assertEquals(budget, actualContext.diagnostics.workUnitsConsumed)
                if (budget == 3L) {
                    assertEquals(listOf(
                        mapOf("c" to "A", "m" to "first", "again" to "A"),
                        mapOf("c" to "Z", "m" to "last", "again" to "Z"),
                        mapOf("c" to null, "m" to null, "again" to null)
                    ), actual.getOrThrow().rows)
                }
            }
        }
    }

    private fun withStored(nodes: List<Node>, block: (MappedWebGraphBackedGraph) -> Unit) {
        val directory = Files.createTempDirectory("mapped-tracked-projection")
        try {
            val builder = MmapGraphBuilder(Files.createDirectory(directory.resolve("builder")))
            nodes.forEach(builder::addNode)
            (builder.build() as MmapGraph).use { graph -> GraphStore.save(graph, directory.resolve("stored")) }
            (GraphStore.loadMapped(directory.resolve("stored")) as MappedWebGraphBackedGraph).use(block)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun call(id: Int, target: String, name: String): CallSiteNode = CallSiteNode(
        NodeId(id), MethodDescriptor(TypeDescriptor("Caller"), "run", emptyList(), TypeDescriptor("void")),
        MethodDescriptor(TypeDescriptor(target), name, emptyList(), TypeDescriptor("void")), id, null, emptyList()
    )

    companion object {
        private const val QUERY = "MATCH (n:CallSiteNode) RETURN n.callee_class AS c, n.callee_name AS m, " +
            "n.callee_class AS again ORDER BY c, m LIMIT 1"
    }
}
