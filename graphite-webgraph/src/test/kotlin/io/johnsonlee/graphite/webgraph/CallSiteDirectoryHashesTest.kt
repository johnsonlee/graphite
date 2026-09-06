package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringPropertyTupleSet
import it.unimi.dsi.lang.MutableString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Load-time exact tables over the sidecar's property directories, checked against the sorted directories. */
class CallSiteDirectoryHashesTest {
    private fun callSite(id: Int, callerClass: String, callerName: String, calleeClass: String, calleeName: String) =
        CallSiteNode(
            NodeId(id),
            MethodDescriptor(TypeDescriptor(callerClass), callerName, emptyList(), TypeDescriptor("void")),
            MethodDescriptor(TypeDescriptor(calleeClass), calleeName, emptyList(), TypeDescriptor("void")),
            id,
            null,
            emptyList()
        )

    /** Caller names `Aa` and `BB` share a hash code, so one table slot chain carries both. */
    private fun referenceGraph(): Graph = DefaultGraph.Builder().apply {
        repeat(300) { id ->
            val callerName = when (id % 3) {
                0 -> "Aa"
                1 -> "BB"
                else -> "run${id % 7}"
            }
            addNode(callSite(id, "app.pkg${id % 11}.Caller${id % 23}", callerName, "lib.Dependency${id % 5}", "getValue$id"))
        }
    }.build()

    private fun <T> withPersistedGraph(block: (Path, StringTable, CallSiteDirectoryHashes) -> T): T {
        val dir = Files.createTempDirectory("callsite-directory-hashes")
        try {
            GraphStore.save(referenceGraph(), dir, prepareCallSiteStringIndex = true)
            val stringTable = StringTable.load(dir)
            val hashes = assertNotNull(
                CallSiteDirectoryHashes.load(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE), stringTable)
            )
            return block(dir, stringTable, hashes)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun header(dir: Path): Triple<ByteArray, Int, IntArray> {
        val bytes = ByteBuffer.wrap(Files.readAllBytes(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))
            .order(ByteOrder.BIG_ENDIAN)
        assertEquals(CALL_SITE_STRING_INDEX_MAGIC, bytes.int)
        assertEquals(CALL_SITE_STRING_INDEX_VERSION, bytes.int)
        val stringCount = bytes.int
        bytes.int
        val identity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES).also(bytes::get)
        val uniqueCounts = IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { bytes.int }
        return Triple(identity, stringCount, uniqueCounts)
    }

    @Test
    fun `every directory value resolves to its sorted row and foreign values to none`() = withPersistedGraph { _, stringTable, hashes ->
        val reference = referenceGraph()
        val columns = listOf<(CallSiteNode) -> String>(
            { node -> node.caller.declaringClass.className },
            { node -> node.caller.name },
            { node -> node.callee.declaringClass.className },
            { node -> node.callee.name }
        )
        val decoded = MutableString()
        columns.forEachIndexed { propertyIndex, column ->
            val values = reference.nodes(CallSiteNode::class.java).map(column).toSortedSet().toList()
            values.forEachIndexed { row, value ->
                assertEquals(row, hashes.row(propertyIndex, value, stringTable, decoded), "$propertyIndex/$value")
                assertEquals(value, decoded.toString())
            }
            assertEquals(-1, hashes.row(propertyIndex, "missing.Value", stringTable, decoded))
            // A hash-equal string that is not in the directory walks past its twin's slot.
            assertEquals(-1, hashes.row(propertyIndex, "AaAa", stringTable, decoded))
            assertEquals(-1, hashes.row(propertyIndex, "BBBB", stringTable, decoded))
        }
        assertTrue(hashes.retainedBytes > 0L)
    }

    @Test
    fun `tables are adopted only by the sidecar they were read from`() = withPersistedGraph { dir, _, hashes ->
        val (identity, stringCount, uniqueCounts) = header(dir)
        assertTrue(hashes.matches(identity, stringCount, uniqueCounts))
        assertFalse(hashes.matches(identity, stringCount + 1, uniqueCounts))
        assertFalse(hashes.matches(identity, stringCount, uniqueCounts.copyOf().also { counts -> counts[0]++ }))
        assertFalse(hashes.matches(identity.copyOf().also { bytes -> bytes[0] = (bytes[0] + 1).toByte() }, stringCount, uniqueCounts))
    }

    @Test
    fun `tables reserve their exact bytes from the shared index budget and release them on close`() {
        val dir = Files.createTempDirectory("callsite-directory-hashes-budget")
        try {
            GraphStore.save(referenceGraph(), dir, prepareCallSiteStringIndex = true)
            val stringTable = StringTable.load(dir)
            val sidecar = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
            val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
            val hashes = assertNotNull(CallSiteDirectoryHashes.load(sidecar, stringTable))
            val (identity, stringCount, uniqueCounts) = header(dir)
            assertEquals(CallSiteDirectoryHashes.tableBytes(uniqueCounts), hashes.retainedBytes)
            assertEquals(retainedBefore + hashes.retainedBytes, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
            assertTrue(hashes.matches(identity, stringCount, uniqueCounts))
            hashes.close()
            hashes.close()
            assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
            assertFalse(hashes.matches(identity, stringCount, uniqueCounts))

            // A saturated budget leaves the graph without tables, and the search path serves it.
            val blocker = assertNotNull(
                MappedCallSiteStringIndexMemoryBudget.tryReserve(
                    MappedCallSiteStringIndexMemoryBudget.maxBytes - MappedCallSiteStringIndexMemoryBudget.retainedBytes()
                )
            )
            try {
                assertNull(CallSiteDirectoryHashes.load(sidecar, stringTable))
                assertEquals(MappedCallSiteStringIndexMemoryBudget.maxBytes, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
            } finally {
                blocker.close()
            }
            assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())

            // The mapped graph owns the tables: open reserves, close releases.
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { graph ->
                assertTrue(MappedCallSiteStringIndexMemoryBudget.retainedBytes() >= retainedBefore + hashes.retainedBytes)
                assertEquals(
                    listOf(listOf("app.pkg2.Caller2", "run2")),
                    graph.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("callee_name", null, StringMatchMode.STARTS_WITH, "getValue")),
                        listOf("caller_class", "caller_name"),
                        limit = 1,
                        selectedValues = StringPropertyTupleSet(listOf(listOf("app.pkg2.Caller2", "run2"))),
                        workConsumer = null
                    )?.map { row -> row.values }
                )
            }
            assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a missing, truncated or foreign sidecar builds no tables`() = withPersistedGraph { dir, stringTable, _ ->
        val sidecar = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
        assertNull(CallSiteDirectoryHashes.load(dir.resolve("absent"), stringTable))
        val truncated = dir.resolve("truncated")
        Files.write(truncated, Files.readAllBytes(sidecar).copyOf(CALL_SITE_STRING_INDEX_HEADER_BYTES + Int.SIZE_BYTES))
        assertNull(CallSiteDirectoryHashes.load(truncated, stringTable))
        val garbage = dir.resolve("garbage")
        Files.write(garbage, ByteArray(CALL_SITE_STRING_INDEX_HEADER_BYTES + Long.SIZE_BYTES))
        assertNull(CallSiteDirectoryHashes.load(garbage, stringTable))
        val other = Files.createTempDirectory("callsite-directory-hashes-other")
        try {
            assertNull(CallSiteDirectoryHashes.load(sidecar, StringTable.build(listOf("only"), other)))
        } finally {
            other.toFile().deleteRecursively()
        }
    }
}
