package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyLookup
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValueStringLookupTest {
    private val nodes = listOf(
        ResourceValueNode(NodeId(0), "app.properties", "key", "needle-resource", "properties"),
        StringConstant(NodeId(1), "needle-string"),
        EnumConstant(NodeId(2), TypeDescriptor("Example"), "ENTRY", listOf("needle-enum")),
        IntConstant(NodeId(3), 123),
        EnumConstant(NodeId(4), TypeDescriptor("Example"), "NUMBER", listOf(123)),
        StringConstant(NodeId(5), "other")
    )
    private val backing = DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build()

    @Test
    fun `polymorphic value search uses typed lookup without omitting resource and enum strings`() {
        val scanned = mutableListOf<Class<out Node>>()
        val lookups = mutableListOf<Class<out Node>>()
        val graph = object : Graph by backing, StringPropertyLookup, StringPropertyLookupOrder {
            override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()

            override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
                scanned += type
                check(type != Node::class.java) { "untyped value search scanned the entire graph" }
                return backing.nodes(type)
            }

            override fun <T : Node> nodesByStringProperty(
                type: Class<T>, property: String, mode: StringMatchMode, expected: String, limit: Int
            ): Sequence<T>? {
                lookups += type
                assertEquals("value", property)
                assertEquals(StringMatchMode.CONTAINS, mode)
                if (type != StringConstant::class.java) return null
                @Suppress("UNCHECKED_CAST")
                return (backing.nodes(type) as Sequence<T>).filter {
                    (NodePropertyAccessor.getProperty(it, property) as? String)?.contains(expected) == true
                }.take(limit)
            }
        }
        val result = CypherExecutor(graph).execute(
            "MATCH (n) WHERE n.value CONTAINS 'needle' RETURN n.value AS value LIMIT 3"
        )
        assertEquals(listOf("needle-resource", "needle-string", "needle-enum"), result.rows.map { it["value"] })
        assertTrue(StringConstant::class.java in lookups)
        assertTrue(ResourceValueNode::class.java in scanned)
        assertTrue(EnumConstant::class.java in scanned)
    }

    @Test
    fun `fallback value search preserves encounter order and numeric conversions`() {
        val executor = CypherExecutor(backing)
        val query = "MATCH (n) WHERE n.value CONTAINS 'needle' RETURN n.value AS value LIMIT 2"
        val expected = backing.nodes(Node::class.java)
            .mapNotNull { NodePropertyAccessor.getProperty(it, "value") as? String }
            .filter { it.contains("needle") }.take(2).toList()
        assertEquals(expected, executor.execute(query).rows.map { it["value"] })
        assertEquals(
            listOf(3, 4),
            executor.execute(
                "MATCH (n) WHERE toString(n.value) CONTAINS '123' RETURN n.id AS id ORDER BY id LIMIT 10"
            ).rows.map { it["id"] }
        )
        assertTrue(executor.execute("MATCH (n) WHERE n.value CONTAINS '123' RETURN n LIMIT 10").rows.isEmpty())
    }

    @Test
    fun `value search retains aggregation null and enum numeric equality semantics`() {
        val executor = CypherExecutor(backing)
        assertEquals(
            listOf(mapOf("count" to 3L)),
            executor.execute("MATCH (n) WHERE n.value CONTAINS 'needle' RETURN count(n) AS count").rows
        )
        assertEquals(
            listOf(3, 4),
            executor.execute("MATCH (n) WHERE n.value = 123 RETURN n.id AS id ORDER BY id LIMIT 10")
                .rows.map { it["id"] }
        )
        assertTrue(executor.execute("MATCH (n) WHERE n.value CONTAINS null RETURN n LIMIT 10").rows.isEmpty())
    }
}
