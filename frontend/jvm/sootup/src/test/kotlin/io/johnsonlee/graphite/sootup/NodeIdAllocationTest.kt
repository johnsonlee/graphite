package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Node ids of a build are numbered for that build. A persisted graph's node offset index, forward graph and per-node sidecars are sized
 * by the largest id, so ids carried over from earlier builds in the same process made every later graph pay for every earlier one.
 */
class NodeIdAllocationTest {

    private fun fixture(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else projectDir.resolve("frontend/jvm/sootup/build/classes/java/test")
    }

    private fun load(): List<Int> =
        JavaProjectLoader(LoaderConfig(includePackages = listOf("sample.ab"), buildCallGraph = false))
            .load(fixture())
            .nodes(Node::class.java)
            .map { it.id.value }
            .sorted()
            .toList()

    @Test
    fun `every build in a process numbers its nodes from one`() {
        val first = load()
        // Unrelated allocations between builds (other graphs, synthetic analysis results) must not shift the next build's ids.
        repeat(1_000) { NodeId.next() }
        val second = load()
        assertTrue(first.isNotEmpty())
        assertEquals(first, second, "the same input must build the same node ids")
        assertEquals(1, first.first())
        assertTrue(first.last() <= first.size * 2, "ids are dense: largest ${first.last()} for ${first.size} nodes")
    }

    @Test
    fun `ids allocated outside a build never collide with a build's ids`() {
        val built = load()
        val synthetic = NodeId.next().value
        assertTrue(synthetic > built.last(), "synthetic id $synthetic must not reuse a built id (largest ${built.last()})")
    }
}
