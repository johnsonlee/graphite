package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalDefinition
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.FullGraphBuilder
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MmapGraphBuilder
import io.johnsonlee.graphite.graph.PackedBranchMetadataSource
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackedBranchPersistenceTest {
    private val method = MethodDescriptor(TypeDescriptor("example.Branches"), "run", emptyList(), TypeDescriptor("void"))
    private val definitions = listOf(
        LocalDefinition(1, NodeId(3), NodeId(4)),
        LocalDefinition(3, NodeId(3), NodeId(4)),
        LocalDefinition(5, NodeId(3), null)
    )

    @Test
    fun `compact save preserves exact bytes and grouped scope order without initializing query views`() = withDirectory { dir, mapped ->
        fixture(dir.resolve("compact-source"), mapped).use { compact ->
            fixture(dir.resolve("fallback-source"), mapped).use { fallback ->
                val compactDir = dir.resolve("compact")
                val fallbackDir = dir.resolve("fallback")
                GraphStore.save(compact, compactDir)
                assertNotNull(compact.packedBranchScopes())
                assertNotNull(compact.packedLocalDefinitions())
                GraphStore.save(object : Graph by fallback {}, fallbackDir)
                assertNull(fallback.packedBranchScopes())
                assertNull(fallback.packedLocalDefinitions())
                assertSamePersistence(compactDir, fallbackDir, fallback)
                val scopes = GraphStore.load(compactDir, GraphStore.LoadMode.EAGER).branchScopes().toList()
                assertEquals(listOf(1, 1, 2), scopes.map { it.conditionNodeId.value })
                assertEquals(listOf(3, 6), scopes[0].trueBranchNodeIds.toIntArray().sorted())
                assertEquals(listOf(definitions[1]), scopes[0].trueDefinitions)
                assertEquals(listOf(definitions[2]), scopes[0].falseDefinitions)
                assertEquals(listOf(definitions[0]), scopes[1].trueDefinitions)
                assertEquals(listOf(LocalDefinition(9, NodeId(6), NodeId(4))), scopes[2].trueDefinitions)
                val locals = GraphStore.load(compactDir, GraphStore.LoadMode.EAGER).localDefinitions()
                assertEquals(listOf(LocalDefinition(9, NodeId(6), NodeId(4))), locals.getValue(NodeId(6)))
            }
        }
    }

    @Test
    fun `snapshot arrays are owned and cannot modify later persistence or public query views`() = withDirectory { dir, mapped ->
        fixture(dir.resolve("source"), mapped).use { graph ->
            val first = assertNotNull(graph.packedBranchScopes()).first()
            first.trueBranchNodeIds.fill(99)
            first.falseBranchNodeIds.fill(99)
            first.trueDefinitions.fill(99)
            first.falseDefinitions.fill(99)
            assertNotNull(graph.packedLocalDefinitions()).getValue(3).fill(99)
            val next = assertNotNull(graph.packedBranchScopes()).first()
            assertEquals(listOf(3, 6), next.trueBranchNodeIds.sorted())
            assertContentEquals(BranchScope.packDefinitions(listOf(definitions[1])), next.trueDefinitions)
            assertContentEquals(BranchScope.packDefinitions(definitions), assertNotNull(graph.packedLocalDefinitions()).getValue(3))
            GraphStore.save(graph, dir.resolve("saved"))
            val loaded = GraphStore.load(dir.resolve("saved"), GraphStore.LoadMode.EAGER)
            assertEquals(definitions, loaded.localDefinitions().getValue(NodeId(3)))
            assertEquals(graph.branchScopes().toList(), loaded.branchScopes().toList())
        }
    }

    @Test
    fun `materialized mutations use public views independently for scopes and local definitions`() = withDirectory { dir, mapped ->
        fixture(dir.resolve("source"), mapped).use { graph ->
            val scope = graph.branchScopesFor(NodeId(1)).first()
            assertNull(graph.packedBranchScopes())
            assertNotNull(graph.packedLocalDefinitions())
            scope.trueBranchNodeIds.remove(6)
            val replacement = definitions[1].copy(constantNodeId = NodeId(5))
            (scope.trueDefinitions as MutableList<LocalDefinition>)[0] = replacement
            (graph.localDefinitions().getValue(NodeId(3)) as MutableList<LocalDefinition>)[1] = replacement
            assertNull(graph.packedLocalDefinitions())
            GraphStore.save(graph, dir.resolve("compact"))
            GraphStore.save(object : Graph by graph {}, dir.resolve("fallback"))
            assertSamePersistence(dir.resolve("compact"), dir.resolve("fallback"), graph)
            val loaded = GraphStore.load(dir.resolve("compact"), GraphStore.LoadMode.EAGER)
            val actual = loaded.branchScopesFor(NodeId(1)).first()
            assertEquals(listOf(3), actual.trueBranchNodeIds.toIntArray().toList())
            assertEquals(listOf(replacement), actual.trueDefinitions)
            assertEquals(listOf(definitions[0], replacement, definitions[2]), loaded.localDefinitions().getValue(NodeId(3)))
        }
        fixture(dir.resolve("locals-first"), mapped).use { graph ->
            assertEquals(definitions, graph.localDefinitions().getValue(NodeId(3)))
            assertNull(graph.packedLocalDefinitions())
            assertNotNull(graph.packedBranchScopes())
        }
    }

    @Test
    fun `compact definitions preserve duplicate triples and reject malformed packed lengths like public views`() =
        withDirectory { dir, mapped ->
            fixture(dir.resolve("duplicates"), mapped, duplicateDefinitions = true).use { graph ->
                val packed = BranchScope.packDefinitions(listOf(definitions[1], definitions[1]))
                assertContentEquals(packed, assertNotNull(graph.packedBranchScopes()).first().trueDefinitions)
                assertEquals(listOf(definitions[1], definitions[1]), graph.branchScopes().first().trueDefinitions)
            }
            fixture(dir.resolve("malformed"), mapped, malformedDefinitions = true).use { graph ->
                val compact = assertFailsWith<IllegalArgumentException> { assertNotNull(graph.packedBranchScopes()).toList() }
                val fallback = assertFailsWith<IllegalArgumentException> { graph.branchScopes().toList() }
                assertEquals(fallback.message, compact.message)
                assertTrue(compact.message.orEmpty().contains("not a multiple of 3"))
            }
        }

    private fun fixture(
        dir: Path,
        mapped: Boolean,
        duplicateDefinitions: Boolean = false,
        malformedDefinitions: Boolean = false
    ): TestGraph {
        Files.createDirectories(dir)
        val builder: FullGraphBuilder = if (mapped) MmapGraphBuilder(dir) else DefaultGraph.Builder()
        builder.addMethod(method)
        listOf(1, 2, 3, 6).forEach { id ->
            builder.addNode(LocalVariable(NodeId(id), "local$id", TypeDescriptor("int"), method))
        }
        builder.addNode(IntConstant(NodeId(4), 1))
        builder.addNode(IntConstant(NodeId(5), 0))
        val comparison = BranchComparison(ComparisonOp.EQ, NodeId(5))
        val trueDefinitions = when {
            malformedDefinitions -> intArrayOf(3, 3)
            duplicateDefinitions -> BranchScope.packDefinitions(listOf(definitions[1], definitions[1]))
            else -> BranchScope.packDefinitions(listOf(definitions[1]))
        }
        builder.addBranchScope(
            NodeId(1), method, comparison, intArrayOf(6, 3, 6), intArrayOf(3),
            trueDefinitions, BranchScope.packDefinitions(listOf(definitions[2]))
        )
        builder.addBranchScope(
            NodeId(2), method, comparison, intArrayOf(5), intArrayOf(), intArrayOf(9, 6, 4)
        )
        builder.addBranchScope(
            NodeId(1), method, comparison, intArrayOf(3), intArrayOf(),
            BranchScope.packDefinitions(listOf(definitions[0])), BranchScope.EMPTY_DEFINITIONS
        )
        builder.addLocalDefinitions(NodeId(3), BranchScope.packDefinitions(definitions))
        builder.addLocalDefinitions(NodeId(6), intArrayOf(9, 6, 4))
        return TestGraph(builder.build())
    }

    private fun assertSamePersistence(actual: Path, expected: Path, source: Graph) {
        for (name in listOf("graph.metadata", GraphStore.BRANCH_DEFINITIONS_FILE)) {
            assertContentEquals(Files.readAllBytes(expected.resolve(name)), Files.readAllBytes(actual.resolve(name)), name)
        }
        (GraphStore.loadMapped(actual) as MappedWebGraphBackedGraph).use { loaded ->
            assertEquals(source.branchScopes().toList(), loaded.branchScopes().toList())
            assertEquals(source.localDefinitions(), loaded.localDefinitions())
        }
    }

    private class TestGraph(private val delegate: Graph) :
        Graph by delegate,
        PackedBranchMetadataSource by (delegate as PackedBranchMetadataSource),
        Closeable {
        override fun close() {
            (delegate as? Closeable)?.close()
        }
    }

    private fun withDirectory(action: (Path, Boolean) -> Unit) {
        for (mapped in listOf(false, true)) {
            val dir = Files.createTempDirectory("packed-branch-persistence-$mapped")
            try {
                action(dir, mapped)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }
}
