package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.DefaultGraph
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MappedCallSiteOrdinalLoadingTest {
    private val caller = MethodDescriptor(TypeDescriptor("example.Caller"), "run", emptyList(), TypeDescriptor("void"))
    private val callee = MethodDescriptor(TypeDescriptor("example.Flags"), "enabled", emptyList(), TypeDescriptor("boolean"))
    private val integer = IntConstant(NodeId(0), 42)
    private val text = StringConstant(NodeId(2), "ordinal-independent")
    private val ordinary = call(7, 3)
    private val derived = call(9, -1, ordinary.id)
    private val unknown = call(13, null)
    private val nodes: List<Node> = listOf(integer, text, ordinary, derived, unknown)

    @Test
    fun `non CallSite reads typed scans and queries do not initialize ordinal sidecar`() = withStored(nodes) { dir ->
        assertTrue(Files.exists(dir.resolve(GraphStore.CALL_SITE_ORDINALS_FILE)))
        (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
            assertFalse(mapped.isCallSiteOrdinalsInitialized())
            assertEquals(integer, mapped.node(integer.id))
            assertEquals(text, mapped.node(text.id))
            assertEquals(listOf(integer), mapped.nodes(IntConstant::class.java).toList())
            assertEquals(listOf(text), mapped.nodes(StringConstant::class.java).toList())
            assertNull(mapped.node(NodeId(-1)))
            assertNull(mapped.node(NodeId(1)))
            assertNull(mapped.node(NodeId(Int.MAX_VALUE)))
            val result = mapped.query("MATCH (n:IntConstant) RETURN n.value AS value")
            assertEquals(listOf("value"), result.columns)
            assertEquals(listOf(42), result.rows.map { (it["value"] as Number).toInt() }.toList())
            assertFalse(mapped.isCallSiteOrdinalsInitialized())
            assertEquals(ordinary, mapped.node(ordinary.id))
            assertTrue(mapped.isCallSiteOrdinalsInitialized())
            assertEquals(derived, mapped.node(derived.id))
        }
    }

    @Test
    fun `first derived CallSite retains ordinal and origin without a preceding ordinary read`() = withStored(nodes) { dir ->
        (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
            assertFalse(mapped.isCallSiteOrdinalsInitialized())
            assertEquals(derived, mapped.node(derived.id))
            assertTrue(mapped.isCallSiteOrdinalsInitialized())
            assertEquals(unknown, mapped.node(unknown.id))
            assertEquals(ordinary, mapped.node(ordinary.id))
            assertEquals(nodes, mapped.nodes(Node::class.java).sortedBy { it.id.value }.toList())
        }
    }

    @Test
    fun `complete scans and ordinal queries retain all values after reopening`() = withStored(nodes) { dir ->
        (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
            assertFalse(mapped.isCallSiteOrdinalsInitialized())
            assertEquals(nodes, mapped.nodes(Node::class.java).sortedBy { it.id.value }.toList())
            assertTrue(mapped.isCallSiteOrdinalsInitialized())
        }
        (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
            assertFalse(mapped.isCallSiteOrdinalsInitialized())
            val result = mapped.query("MATCH (c:CallSite) RETURN id(c) AS nodeId, c.ordinal AS ordinal ORDER BY nodeId")
            assertEquals(listOf("nodeId", "ordinal"), result.columns)
            assertEquals(
                listOf(7 to 3, 9 to -1, 13 to null),
                result.rows.map { row ->
                    (row["nodeId"] as Number).toInt() to (row["ordinal"] as Number?)?.toInt()
                }.toList()
            )
            assertTrue(mapped.isCallSiteOrdinalsInitialized())
            assertEquals(derived, mapped.node(derived.id))
        }
    }

    @Test
    fun `graph without ordinal sidecar keeps non CallSite reads lazy and unknown call intact`() {
        withStored(listOf(integer, unknown)) { dir ->
            assertFalse(Files.exists(dir.resolve(GraphStore.CALL_SITE_ORDINALS_FILE)))
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
                assertEquals(integer, mapped.node(integer.id))
                assertFalse(mapped.isCallSiteOrdinalsInitialized())
                assertEquals(unknown, mapped.node(unknown.id))
                assertTrue(mapped.isCallSiteOrdinalsInitialized())
            }
        }
    }

    private fun call(id: Int, ordinal: Int?, origin: NodeId? = null): CallSiteNode =
        CallSiteNode(NodeId(id), caller, callee, 42, null, listOf(integer.id), ordinal, origin)

    private fun withStored(values: List<Node>, action: (Path) -> Unit) {
        val graph = DefaultGraph.Builder().apply { values.forEach { addNode(it) } }.build()
        val dir = Files.createTempDirectory("mapped-call-site-ordinal-loading")
        try {
            GraphStore.save(graph, dir)
            action(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
