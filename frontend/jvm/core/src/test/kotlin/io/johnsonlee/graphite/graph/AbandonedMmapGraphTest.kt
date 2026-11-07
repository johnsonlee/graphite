package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbandonedMmapGraphTest {
    @Test
    fun `abort closes outputs and removes incomplete graph files`() {
        val directory = Files.createTempDirectory("abandoned-mmap-test")
        val builder = MmapGraphBuilder(directory)
        val method = MethodDescriptor(TypeDescriptor("example.App"), "run", emptyList(), TypeDescriptor("void"))
        builder.addMethod(method)
        builder.addNode(ReturnNode(NodeId(0), method))
        assertTrue(Files.list(directory).use { it.findAny().isPresent })
        builder.abort()
        assertFalse(Files.exists(directory), "a failed load must not retain the partial graph")
        builder.abort()
        assertFalse(Files.exists(directory), "cleanup is idempotent")
    }
}
