package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.GraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StorageBoundaryContractTest {
    @Test
    fun `node index reconstruction preserves eight byte numeric payloads`() {
        val root = Files.createTempDirectory("reconstruct-wide-nodes")
        val long = LongConstant(NodeId(0), Long.MAX_VALUE)
        val double = DoubleConstant(NodeId(1), Math.PI)
        try {
            GraphStore.save(DefaultGraph.Builder().addNode(long).addNode(double).build(), root)
            Files.delete(root.resolve("graph.nodeindex"))
            ensureGraphNodeIndex(root)
            (GraphStore.loadMapped(root) as MappedWebGraphBackedGraph).use { graph ->
                assertEquals(long, graph.node(long.id))
                assertEquals(double, graph.node(double.id))
            }
            assertTrue(Files.isRegularFile(root.resolve("graph.nodeindex")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `posting exhaustion charges trailing duplicates exactly once`() = withIndex { file, context ->
        assertNotNull(MappedCallSiteStringIndexView.load(file, context, null)).use { view ->
            val predicates = listOf("caller_class", "callee_class").map {
                StringPropertyPredicate(it, null, StringMatchMode.CONTAINS, "Owner")
            }
            val selected = assertNotNull(view.exactMatchingStringIds(predicates, null))
            var charged = 0L
            val consumer = object : GraphWorkBatchConsumer {
                override fun consume(workUnits: Long) { charged += workUnits }
            }
            val iterator = assertNotNull(view.matchingNodeIds(predicates, selected, consumer)).iterator()
            assertEquals(0, iterator.next())
            assertEquals(1, iterator.next())
            val beforeExhaustion = charged
            assertFalse(iterator.hasNext())
            assertEquals(beforeExhaustion + 1, charged, "The final duplicate still consumes work")
            assertFalse(iterator.hasNext())
            assertEquals(beforeExhaustion + 1, charged, "Exhaustion must not charge twice")
        }
    }

    @Test
    fun `oversized posting declarations fall back before integer byte size overflows`() = withIndex { file, context ->
        val bytes = Files.readAllBytes(file)
        val header = ByteBuffer.wrap(bytes)
        header.putInt(3 * Int.SIZE_BYTES, Int.MAX_VALUE)
        Files.write(file, bytes)
        assertNull(MappedCallSiteStringIndexView.load(file, context.copy(expectedCallSiteCount = Int.MAX_VALUE), null))
    }

    @Test
    fun `unreadable optional sidecars fall back and can be loaded after access is restored`() = withIndex { file, context ->
        org.junit.Assume.assumeTrue(Files.getFileStore(file).supportsFileAttributeView("posix"))
        val permissions = Files.getPosixFilePermissions(file)
        try {
            Files.setPosixFilePermissions(file, emptySet())
            org.junit.Assume.assumeFalse("A privileged user bypasses permission denial", Files.isReadable(file))
            assertNull(MappedCallSiteStringIndexView.load(file, context, null))
            assertSame(CallSiteOrdinals.EMPTY, CallSiteOrdinals.load(file, ByteArray(NodeSerializer.DIGEST_BYTES)))
        } finally { Files.setPosixFilePermissions(file, permissions) }
        assertNotNull(MappedCallSiteStringIndexView.load(file, context, null)).close()
    }

    private fun withIndex(action: (Path, CallSiteIndexViewContext) -> Unit) {
        val root = Files.createTempDirectory("storage-sidecar-contract")
        val method = MethodDescriptor(TypeDescriptor("sample.Owner"), "run", emptyList(), TypeDescriptor("void"))
        val builder = DefaultGraph.Builder()
        repeat(2) { builder.addNode(CallSiteNode(NodeId(it), method, method, null, null, emptyList())) }
        try {
            GraphStore.save(builder.build(), root)
            (GraphStore.loadMapped(root) as MappedWebGraphBackedGraph).use { graph ->
                val state = graph.context
                assertTrue(state.prepareCallSiteStringIndex())
                assertTrue(state.persistPreparedCallSiteStringIndex())
                val context = CallSiteIndexViewContext(
                    state.stringTable.size(), 2, state.callSiteStringIndexContentIdentity(null),
                    state.stringTable, state.nodeOffsets.size
                ) { state.nodeOffsets.offset(it) }
                action(root.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE), context)
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
