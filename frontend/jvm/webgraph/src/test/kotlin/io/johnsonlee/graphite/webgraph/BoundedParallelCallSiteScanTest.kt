package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import java.nio.file.Files
import kotlin.test.Test

/** Verifies bounded parallel call site scan. */
class BoundedParallelCallSiteScanTest : GraphStoreTestFixture() {

    @Test
    fun `bounded mapped CallSite scan uses ordered intra graph workers before index admission`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(32_768) { index ->
                        val marker = if (index == 100 || index % 512 == 0) "Target" else "Feature"
                        val callerName = if (index == 32_767) "ÜnicodeCall" else "call$index"
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor("example.${marker}Caller$index"), callerName, emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency$index"), "invoke$index", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-parallel-callsite-scan")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                RawCallSiteScanAssertions.verify(loaded)
                CallSiteMetadataAssertions.verify(loaded)
                CallSiteAdmissionAssertions.verify(loaded)
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
