package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CallSiteOrdinalPersistenceTest {
    private val caller = MethodDescriptor(TypeDescriptor("example.Caller"), "run", emptyList(), TypeDescriptor("void"))
    private val callee = MethodDescriptor(TypeDescriptor("example.Flags"), "enabled", emptyList(), TypeDescriptor("boolean"))

    @Test
    fun `shuffled sparse calls preserve reference sidecar bytes and all decoded fields across blocks`() {
        val calls = List(620) { index ->
            val ordinal = when (index % 4) {
                0 -> index
                1 -> -index
                2 -> Int.MIN_VALUE
                else -> null
            }
            call(7 + index * 3, ordinal, if (index > 0 && index % 3 == 0) NodeId(7) else null)
        }
        // Add another block of ordinal-bearing calls after the mixed prefix.
        val allCalls = calls + List(100) { call(2000 + it * 3, Int.MAX_VALUE, NodeId(7)) }
        val nodes: List<Node> = allCalls + IntConstant(NodeId(0), 42)
        val graph = EncounterGraph(nodes.shuffled(Random(108)))
        withDirectory { dir ->
            GraphStore.save(graph, dir)
            assertReferenceSidecar(dir, allCalls)
            assertRoundTrip(dir, allCalls)
            assertEquals(0, graph.typedCallSiteScans, "ordinal persistence reuses the node-data pass")
            val sidecar = CallSiteOrdinals.load(
                dir.resolve(GraphStore.CALL_SITE_ORDINALS_FILE),
                NodeSerializer.readCallSiteOrdinalBinding(dir.resolve("graph.metadata"))
            )
            assertEquals(565, sidecar.size)
            // Entries 255/256 and 511/512 sit on opposite sides of sidecar blocks.
            for (id in listOf(1027, 1030, 2138, 2141)) assertEquals(allCalls.single { it.id.value == id }.ordinal, sidecar[id])
            for (id in listOf(6, 8, 1029, 2139, 2300)) assertNull(sidecar[id], "sparse gap $id")
        }
    }

    @Test
    fun `ascending calls retain extreme ordinals and origins without changing persisted format`() {
        val calls = listOf(
            call(0, 0),
            call(2, Int.MIN_VALUE, NodeId(-2)),
            call(7, Int.MAX_VALUE, NodeId(2)),
            call(9, -1, NodeId(7)),
            call(15, null, NodeId(9))
        )
        withDirectory { dir ->
            GraphStore.save(EncounterGraph(calls), dir)
            assertReferenceSidecar(dir, calls)
            assertRoundTrip(dir, calls)
        }
    }

    @Test
    fun `saving unknown ordinals removes previous sidecar and binding`() {
        withDirectory { dir ->
            GraphStore.save(EncounterGraph(listOf(call(0, 1))), dir)
            val calls = listOf(call(0, null), call(7, null, NodeId(0)))
            val graph = EncounterGraph(calls)
            GraphStore.save(graph, dir)
            assertFalse(Files.exists(dir.resolve(GraphStore.CALL_SITE_ORDINALS_FILE)))
            assertNull(NodeSerializer.readCallSiteOrdinalBinding(dir.resolve("graph.metadata")))
            assertRoundTrip(dir, calls)
            assertEquals(0, graph.typedCallSiteScans)
        }
    }

    @Test
    fun `equal ids retain encounter order in reference encoding`() {
        // The encoder has historically received a stable sort, including repeated ids.
        val input = CallSiteOrdinalPersistenceInput(4)
        listOf(call(9, 3), call(2, 7), call(9, -2, NodeId(2)), call(2, 8)).forEach(input::add)
        val reference = NodeSerializer.encodeCallSiteOrdinals(
            intArrayOf(2, 2, 9, 9), intArrayOf(7, 8, 3, -2), intArrayOf(NO_ORIGIN, NO_ORIGIN, NO_ORIGIN, 2)
        )
        val actual = checkNotNull(input.encode())
        assertContentEquals(reference.bytes, actual.bytes)
        assertContentEquals(reference.digest, actual.digest)
    }

    private fun call(id: Int, ordinal: Int?, origin: NodeId? = null): CallSiteNode =
        CallSiteNode(NodeId(id), caller, callee, 42, null, emptyList(), ordinal, origin)

    private fun assertReferenceSidecar(dir: Path, calls: List<CallSiteNode>) {
        // This is the previous writer's object-based ordering, fed to the unchanged format encoder.
        val sites = calls.filter { it.ordinal != null }.sortedBy { it.id.value }
        val reference = NodeSerializer.encodeCallSiteOrdinals(
            IntArray(sites.size) { sites[it].id.value },
            IntArray(sites.size) { checkNotNull(sites[it].ordinal) },
            IntArray(sites.size) { sites[it].origin?.value ?: NO_ORIGIN }
        )
        assertContentEquals(reference.bytes, Files.readAllBytes(dir.resolve(GraphStore.CALL_SITE_ORDINALS_FILE)))
        val binding = NodeSerializer.readCallSiteOrdinalBinding(dir.resolve("graph.metadata"))
        assertContentEquals(reference.digest, binding)
        val sidecar = CallSiteOrdinals.load(dir.resolve(GraphStore.CALL_SITE_ORDINALS_FILE), binding)
        for (site in sites) {
            assertEquals(site.ordinal, sidecar[site.id.value])
            assertEquals(site.origin, sidecar.origin(site.id.value))
        }
    }

    private fun assertRoundTrip(dir: Path, calls: List<CallSiteNode>) {
        // The reader exposes an origin only for derived (negative-ordinal) calls, although
        // the existing encoder also stores origins attached to nonnegative ordinals.
        val expected = calls.map {
            val ordinal = it.ordinal
            if (ordinal == null || ordinal >= 0) it.copy(origin = null) else it
        }.sortedBy { it.id.value }
        assertEquals(expected, GraphStore.load(dir, GraphStore.LoadMode.EAGER).callsById())
        (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
            assertEquals(expected, mapped.callsById())
        }
    }

    private fun Graph.callsById(): List<CallSiteNode> = nodes(CallSiteNode::class.java).sortedBy { it.id.value }.toList()

    private fun withDirectory(action: (Path) -> Unit) {
        val dir = Files.createTempDirectory("call-site-ordinal-persistence")
        try {
            action(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private class EncounterGraph(private val encounterNodes: List<Node>) : Graph by (DefaultGraph.Builder().apply {
        encounterNodes.forEach { addNode(it) }
    }.build()) {
        var typedCallSiteScans = 0
            private set

        override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
            if (type == CallSiteNode::class.java) typedCallSiteScans++
            return encounterNodes.asSequence().filter(type::isInstance).map(type::cast)
        }
    }
}
