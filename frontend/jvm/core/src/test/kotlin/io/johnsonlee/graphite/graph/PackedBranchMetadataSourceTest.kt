package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalDefinition
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackedBranchMetadataSourceTest {
    private val method = MethodDescriptor(TypeDescriptor("example.Branches"), "run", emptyList(), TypeDescriptor("void"))
    private val comparison = BranchComparison(ComparisonOp.EQ, NodeId(0))
    private val definitions = listOf(
        LocalDefinition(2, NodeId(3), NodeId(0)),
        LocalDefinition(2, NodeId(3), NodeId(0)),
        LocalDefinition(7, NodeId(3), null)
    )
    private val packedDefinitions = intArrayOf(2, 3, 0, 2, 3, 0, 7, 3, -1)

    @Test
    fun `snapshots preserve grouped encounter order set order and complete definition triples`() = withGraph { graph ->
        val packed = assertNotNull(graph.packedBranchScopes()).toList()
        assertEquals(listOf(1, 1, 2), packed.map { it.conditionNodeId })
        assertEquals(listOf(method, method, method), packed.map { it.method })
        assertEquals(listOf(comparison, comparison, comparison), packed.map { it.comparison })
        assertEquals(listOf(3, 6), packed[0].trueBranchNodeIds.sorted())
        assertContentEquals(intArrayOf(3), packed[0].falseBranchNodeIds)
        assertContentEquals(packedDefinitions, packed[0].trueDefinitions)
        assertContentEquals(intArrayOf(7, 3, -1), packed[0].falseDefinitions)
        assertContentEquals(intArrayOf(5, 6, 0), packed[1].trueDefinitions)
        assertContentEquals(intArrayOf(), packed[2].trueDefinitions)
        assertContentEquals(intArrayOf(), packed[2].falseDefinitions)
        val public = graph.branchScopes().toList()
        assertEquals(listOf(1, 1, 2), public.map { it.conditionNodeId.value })
        packed.zip(public).forEach { (snapshot, scope) ->
            assertContentEquals(scope.trueBranchNodeIds.toIntArray(), snapshot.trueBranchNodeIds)
            assertContentEquals(scope.falseBranchNodeIds.toIntArray(), snapshot.falseBranchNodeIds)
        }
        assertEquals(definitions, public[0].trueDefinitions)
        assertEquals(listOf(definitions.last()), public[0].falseDefinitions)
        assertEquals(listOf(LocalDefinition(5, NodeId(6), NodeId(0))), public[1].trueDefinitions)
        assertContentEquals(packedDefinitions, assertNotNull(graph.packedLocalDefinitions()).getValue(3))
        assertEquals(definitions, graph.localDefinitions().getValue(NodeId(3)))
    }

    @Test
    fun `consumer array mutations cannot alter subsequent snapshots or public values`() = withGraph { graph ->
        val snapshot = assertNotNull(graph.packedBranchScopes()).first()
        snapshot.trueBranchNodeIds.fill(99)
        snapshot.falseBranchNodeIds.fill(99)
        snapshot.trueDefinitions.fill(99)
        snapshot.falseDefinitions.fill(99)
        assertNotNull(graph.packedLocalDefinitions()).getValue(3).fill(99)
        val again = assertNotNull(graph.packedBranchScopes()).first()
        assertEquals(listOf(3, 6), again.trueBranchNodeIds.sorted())
        assertContentEquals(intArrayOf(3), again.falseBranchNodeIds)
        assertContentEquals(packedDefinitions, again.trueDefinitions)
        assertContentEquals(intArrayOf(7, 3, -1), again.falseDefinitions)
        assertContentEquals(packedDefinitions, assertNotNull(graph.packedLocalDefinitions()).getValue(3))
        assertEquals(definitions, graph.localDefinitions().getValue(NodeId(3)))
        val public = graph.branchScopes().first()
        assertEquals(listOf(3, 6), public.trueBranchNodeIds.toIntArray().sorted())
        assertEquals(listOf(3), public.falseBranchNodeIds.toIntArray().toList())
        assertEquals(definitions, public.trueDefinitions)
        assertEquals(listOf(definitions.last()), public.falseDefinitions)
    }

    @Test
    fun `materialized branch view disables its snapshots and preserves caller modifications`() = withGraph { graph ->
        val scope = graph.branchScopesFor(NodeId(1)).first()
        assertNull(graph.packedBranchScopes())
        assertContentEquals(packedDefinitions, assertNotNull(graph.packedLocalDefinitions()).getValue(3))
        scope.trueBranchNodeIds.remove(6)
        scope.falseBranchNodeIds.add(6)
        val changed = LocalDefinition(11, NodeId(3), NodeId(0))
        (scope.trueDefinitions as MutableList<LocalDefinition>)[0] = changed
        (scope.falseDefinitions as MutableList<LocalDefinition>)[0] = changed
        val actual = graph.branchScopes().first()
        assertEquals(listOf(3), actual.trueBranchNodeIds.toIntArray().toList())
        assertEquals(listOf(3, 6), actual.falseBranchNodeIds.toIntArray().sorted())
        assertEquals(listOf(changed, definitions[1], definitions[2]), actual.trueDefinitions)
        assertEquals(listOf(changed), actual.falseDefinitions)
        assertNull(graph.packedBranchScopes())
    }

    @Test
    fun `materialized local definitions disable only their snapshot and preserve caller modifications`() = withGraph { graph ->
        val locals = graph.localDefinitions()
        val changed = LocalDefinition(11, NodeId(3), null)
        (locals.getValue(NodeId(3)) as MutableList<LocalDefinition>)[1] = changed
        assertNull(graph.packedLocalDefinitions())
        assertEquals(listOf(definitions[0], changed, definitions[2]), graph.localDefinitions().getValue(NodeId(3)))
        assertContentEquals(packedDefinitions, assertNotNull(graph.packedBranchScopes()).first().trueDefinitions)
        assertEquals(definitions, graph.branchScopes().first().trueDefinitions)
        assertNull(graph.packedBranchScopes())
        assertNull(graph.packedLocalDefinitions())
    }

    @Test
    fun `empty metadata snapshots are available until their public views are requested`() = withGraph(configure = {}) { graph ->
        assertEquals(emptyList(), assertNotNull(graph.packedBranchScopes()).toList())
        assertEquals(emptyMap(), assertNotNull(graph.packedLocalDefinitions()))
        assertTrue(graph.branchScopes().none())
        assertNull(graph.packedBranchScopes())
        assertEquals(emptyMap(), assertNotNull(graph.packedLocalDefinitions()))
        assertEquals(emptyMap(), graph.localDefinitions())
        assertNull(graph.packedLocalDefinitions())
    }

    @Test
    fun `empty sides and explicit empty local entries remain present without definition records`() = withGraph(configure = {
        addBranchScope(NodeId(1), method, comparison, intArrayOf(), intArrayOf())
        addLocalDefinitions(NodeId(3), intArrayOf())
    }) { graph ->
        val scope = assertNotNull(graph.packedBranchScopes()).single()
        assertContentEquals(intArrayOf(), scope.trueBranchNodeIds)
        assertContentEquals(intArrayOf(), scope.falseBranchNodeIds)
        assertContentEquals(intArrayOf(), scope.trueDefinitions)
        assertContentEquals(intArrayOf(), scope.falseDefinitions)
        val locals = assertNotNull(graph.packedLocalDefinitions())
        assertEquals(setOf(3), locals.keys)
        assertContentEquals(intArrayOf(), locals.getValue(3))
        assertEquals(emptyList(), graph.branchScopes().single().trueDefinitions)
        assertEquals(mapOf(NodeId(3) to emptyList<LocalDefinition>()), graph.localDefinitions())
    }

    @Test
    fun `malformed definition lengths fail for either branch side and the local table`() {
        assertMalformedScope(intArrayOf(2, 3), intArrayOf())
        assertMalformedScope(intArrayOf(), intArrayOf(2, 3, 0, 7))
        withGraph(configure = { addLocalDefinitions(NodeId(3), intArrayOf(2)) }) { graph ->
            val packed = assertFailsWith<IllegalArgumentException> { graph.packedLocalDefinitions() }
            val public = assertFailsWith<IllegalArgumentException> { graph.localDefinitions() }
            assertEquals("Packed definitions length 1 is not a multiple of 3", packed.message)
            assertEquals(public.message, packed.message)
        }
    }

    @Test
    fun `a malformed later scope fails before snapshot consumption and remains invalid on retry`() = withGraph(configure = {
        addBranchScope(NodeId(2), method, comparison, intArrayOf(6), intArrayOf())
        addBranchScope(NodeId(1), method, comparison, intArrayOf(3), intArrayOf(), intArrayOf(2, 3), intArrayOf())
    }) { graph ->
        repeat(2) {
            val error = assertFailsWith<IllegalArgumentException> { graph.packedBranchScopes() }
            assertEquals("Packed definitions length 2 is not a multiple of 3", error.message)
        }
        val public = assertFailsWith<IllegalArgumentException> { graph.branchScopes() }
        assertEquals("Packed definitions length 2 is not a multiple of 3", public.message)
    }

    @Test
    fun `local snapshots preserve every key and return independent arrays for every entry`() = withGraph(configure = {
        addLocalDefinitions(NodeId(6), intArrayOf(9, 6, 0))
        addLocalDefinitions(NodeId(3), packedDefinitions)
    }) { graph ->
        val first = assertNotNull(graph.packedLocalDefinitions())
        assertEquals(setOf(3, 6), first.keys)
        assertContentEquals(intArrayOf(9, 6, 0), first.getValue(6))
        assertContentEquals(packedDefinitions, first.getValue(3))
        first.values.forEach { it.fill(99) }
        val next = assertNotNull(graph.packedLocalDefinitions())
        assertContentEquals(intArrayOf(9, 6, 0), next.getValue(6))
        assertContentEquals(packedDefinitions, next.getValue(3))
        val public = graph.localDefinitions()
        assertEquals(public.keys.map { it.value }.toSet(), next.keys)
        assertEquals(listOf(LocalDefinition(9, NodeId(6), NodeId(0))), public.getValue(NodeId(6)))
        assertEquals(definitions, public.getValue(NodeId(3)))
        assertNull(graph.packedLocalDefinitions())
    }

    private fun assertMalformedScope(trueDefinitions: IntArray, falseDefinitions: IntArray) {
        withGraph(configure = {
            addBranchScope(NodeId(1), method, comparison, intArrayOf(3), intArrayOf(), trueDefinitions, falseDefinitions)
        }) { graph ->
            val packed = assertFailsWith<IllegalArgumentException> { graph.packedBranchScopes() }
            val public = assertFailsWith<IllegalArgumentException> { graph.branchScopes().toList() }
            val length = if (trueDefinitions.isNotEmpty()) trueDefinitions.size else falseDefinitions.size
            assertEquals("Packed definitions length $length is not a multiple of 3", packed.message)
            assertEquals(public.message, packed.message)
        }
    }

    private fun fixture(builder: FullGraphBuilder) {
        builder.addBranchScope(
            NodeId(1), method, comparison, intArrayOf(6, 3, 6), intArrayOf(3, 3),
            packedDefinitions, intArrayOf(7, 3, -1)
        )
        builder.addBranchScope(NodeId(2), method, comparison, intArrayOf(6), intArrayOf())
        builder.addBranchScope(
            NodeId(1), method, comparison, intArrayOf(3), intArrayOf(), intArrayOf(5, 6, 0), intArrayOf()
        )
        builder.addLocalDefinitions(NodeId(3), packedDefinitions)
    }

    private class TestGraph(private val delegate: Graph) :
        Graph by delegate,
        PackedBranchMetadataSource by (delegate as PackedBranchMetadataSource),
        Closeable {
        override fun close() {
            (delegate as? Closeable)?.close()
        }
    }

    private fun withGraph(configure: FullGraphBuilder.() -> Unit = { fixture(this) }, action: (TestGraph) -> Unit) {
        for (mapped in listOf(false, true)) {
            val directory = Files.createTempDirectory("packed-branch-core-$mapped")
            try {
                val builder: FullGraphBuilder = if (mapped) MmapGraphBuilder(directory) else DefaultGraph.Builder()
                builder.addMethod(method)
                builder.addNode(IntConstant(NodeId(0), 0))
                listOf(1, 2, 3, 6).forEach { id ->
                    builder.addNode(LocalVariable(NodeId(id), "local$id", TypeDescriptor("int"), method))
                }
                builder.configure()
                TestGraph(builder.build()).use(action)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }
}
