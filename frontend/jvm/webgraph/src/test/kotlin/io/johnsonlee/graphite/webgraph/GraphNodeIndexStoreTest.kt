package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import it.unimi.dsi.fastutil.io.BinIO
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies graph node index store. */
class GraphNodeIndexStoreTest : GraphStoreTestFixture() {

    @Test
    fun `node offset indexes preserve missing sentinel grow and wide offsets`() {
        val compact = IntNodeOffsetIndex(1)
        assertEquals(-1L, compact.offset(0))
        compact.ensureSize(3)
        compact.set(2, 42L)

        assertEquals(-1L, compact.offset(1))
        assertEquals(42L, compact.offset(2))
        assertFailsWith<IllegalArgumentException> { compact.set(0, Int.MAX_VALUE.toLong() + 1L) }

        val wide = LongNodeOffsetIndex(1)
        val wideOffset = Int.MAX_VALUE.toLong() + 1L
        wide.ensureSize(2)
        wide.set(1, wideOffset)

        assertEquals(-1L, wide.offset(0))
        assertEquals(wideOffset, wide.offset(1))
    }

    @Test
    fun `mutable node offset indexes and loaded string tables expose fallback behavior`() {
        val intOffsets = IntNodeOffsetIndex(1)
        assertEquals(1, intOffsets.size)
        intOffsets.set(0, 12)
        intOffsets.ensureSize(3)
        assertEquals(3, intOffsets.size)
        assertEquals(12L, intOffsets.offset(0))
        assertEquals(-1L, intOffsets.offset(2))

        val longOffsets = LongNodeOffsetIndex(1)
        assertEquals(1, longOffsets.size)
        longOffsets.set(0, Int.MAX_VALUE.toLong() + 10L)
        longOffsets.ensureSize(2)
        assertEquals(2, longOffsets.size)
        assertEquals(Int.MAX_VALUE.toLong() + 10L, longOffsets.offset(0))
        assertEquals(-1L, longOffsets.offset(1))

        val dir = Files.createTempDirectory("webgraph-string-table-test")
        try {
            val built = StringTable.build(listOf("beta", "alpha"), dir)
            assertEquals(0, built.indexOf("alpha"))
            val loaded = StringTable.load(dir)
            assertEquals("alpha", loaded.get(0))
            assertEquals(-1, loaded.indexOf("alpha"))
            assertContentEquals(built.contentIdentity(), loaded.contentIdentity())

            Files.delete(dir.resolve(StringTable.CONTENT_IDENTITY_FILE_NAME))
            val legacyLoaded = StringTable.load(dir)
            assertContentEquals(built.contentIdentity(), legacyLoaded.contentIdentity())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `production CallSite persistence declines missing identity and unavailable index memory`() {
        val dir = Files.createTempDirectory("webgraph-callsite-persistence-failures")
        val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        var blocker: Closeable? = null
        try {
            val stringTable = StringTable.build(listOf("example.Caller", "call", "example.Dependency", "invoke"), dir)
            assertFalse(persistCallSiteStringIndex(CallSiteIndexPersistenceInput(0), stringTable, dir))

            val available = MappedCallSiteStringIndexMemoryBudget.maxBytes - retainedBefore
            blocker = MappedCallSiteStringIndexMemoryBudget.tryReserve(available)
            assertNotNull(blocker)
            assertNull(CallSiteIndexPersistenceInput(0).build(stringTable, ByteArray(32)))
            blocker.close()
            blocker = null

            val input = CallSiteIndexPersistenceInput(1)
            input.add(
                CallSiteNode(
                    NodeId(0),
                    MethodDescriptor(TypeDescriptor("example.Caller"), "call", emptyList(), TypeDescriptor("void")),
                    MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), TypeDescriptor("void")),
                    0,
                    null,
                    emptyList()),
                stringTable)
            val countBytes = checkNotNull(estimatedMappedCallSiteStringIndexCountBytes(stringTable.size()))
            blocker = MappedCallSiteStringIndexMemoryBudget.tryReserve(available - countBytes)
            assertNotNull(blocker)
            assertNull(input.build(stringTable, ByteArray(32)))
            blocker.close()
            blocker = null

            val identity = ByteArray(32) { index -> index.toByte() }
            checkNotNull(input.build(stringTable, identity)).use { persistenceOnly ->
                assertContentEquals(identity, persistenceOnly.contentIdentity())
                val unprepared =
                    assertFailsWith<IllegalStateException> { persistenceOnly.writePersistent(DataOutputStream(ByteArrayOutputStream())) }
                assertEquals("CallSite trigram postings must be prepared before persistence", unprepared.message)

                val queryFailure =
                    assertFailsWith<IllegalStateException> {
                        persistenceOnly
                            .matchingNodeIds(
                                listOf(
                                    StringPropertyPredicate("caller_class", null, StringMatchMode.EQUALS, "example.Caller"),
                                    StringPropertyPredicate("caller_name", null, StringMatchMode.EQUALS, "call")),
                                workConsumer = null)
                            .toList()
                    }
                assertEquals("Persistence-only CallSite index has no query node order", queryFailure.message)

                val rawPropertyFailure =
                    assertFailsWith<IllegalStateException> {
                        persistenceOnly.distinctProjection(
                            predicates = listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.EQUALS, "example.Caller")),
                            projectedProperties = listOf("caller_class"),
                            limit = 1,
                            selectedValues = setOf(listOf("example.Caller")),
                            workConsumer = null)
                    }
                assertEquals("Persistence-only CallSite index has no raw property accessor", rawPropertyFailure.message)
            }
        } finally {
            blocker?.close()
            assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `save writes mmap node offset and type indexes`() {
        val builder = DefaultGraph.Builder()
        val first = IntConstant(NodeId(0), 1)
        val second = IntConstant(NodeId(2), 2)
        val third = StringConstant(NodeId(3), "three")
        builder.addNode(first)
        builder.addNode(second)
        builder.addNode(third)

        val dir = Files.createTempDirectory("webgraph-mapped-index-test")
        try {
            GraphStore.save(builder.build(), dir)

            val offsets = MappedNodeOffsetIndex.load(dir.resolve("graph.nodeoffsets"))
            assertEquals(4, offsets.size)
            assertTrue(offsets.offset(first.id.value) >= 0L)
            assertEquals(-1L, offsets.offset(1))
            assertTrue(offsets.offset(second.id.value) > offsets.offset(first.id.value))

            val typeIndex = MappedNodeTypeIndex.load(dir.resolve("graph.typeindex"))
            assertEquals(2L, typeIndex.count(IntConstant::class.java))
            val intIds = typeIndex.ids(IntConstant::class.java)
            assertEquals(listOf(0, 2), intIds.toList().sorted())
            assertEquals(listOf(0, 2), intIds.toList().sorted())
            assertEquals(listOf(0, 2, 3), typeIndex.ids(Node::class.java).toList().sorted())
            val exhausted = intIds.iterator()
            assertEquals(0, exhausted.next())
            assertEquals(2, exhausted.next())
            assertFalse(exhausted.hasNext())
            assertFailsWith<NoSuchElementException> { exhausted.next() }
            val rangedIds = mutableListOf<Int>()
            typeIndex.forEachIdWhile(IntConstant::class.java, 1, 2) { nodeId ->
                rangedIds += nodeId
                true
            }
            assertEquals(listOf(2), rangedIds)

            val stoppedIds = mutableListOf<Int>()
            typeIndex.forEachIdWhile(IntConstant::class.java, 0, 2) { nodeId ->
                stoppedIds += nodeId
                false
            }
            assertEquals(listOf(0), stoppedIds)
            assertFailsWith<IllegalArgumentException> { typeIndex.forEachIdWhile(IntConstant::class.java, -1, 1) { true } }
            assertFailsWith<IllegalArgumentException> { typeIndex.forEachIdWhile(IntConstant::class.java, 2, 1) { true } }
            assertEquals(3L, typeIndex.count(Node::class.java))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `node type index default ranged traversal stops early`() {
        val typeIndex =
            object : NodeTypeIndex {
                override fun ids(type: Class<out Node>): Sequence<Int> = sequenceOf(3, 5, 8, 13)

                override fun count(type: Class<out Node>): Long = 4
            }
        val visited = mutableListOf<Int>()
        val iterator = typeIndex.idIterator(IntConstant::class.java)
        val iterated = mutableListOf<Int>()
        while (iterator.hasNext()) iterated += iterator.nextInt()

        typeIndex.forEachIdWhile(IntConstant::class.java, 1, 4) { nodeId ->
            visited += nodeId
            nodeId != 8
        }

        assertEquals(listOf(3, 5, 8, 13), iterated)
        assertEquals(listOf(5, 8), visited)
    }

    @Test
    fun `save writes label prefix and mapped load falls back when missing`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-label-prefix-test")
        try {
            GraphStore.save(graph, dir)

            val prefix = BinIO.loadInts(dir.resolve("graph.labelprefix").toString())
            val maxNodeId = graph.nodes(Node::class.java).maxOf { it.id.value }
            assertEquals(maxNodeId + 2, prefix.size)
            assertEquals(graph.edgeCount(), prefix.last().toLong())

            val loaded = GraphStore.loadMapped(dir)
            try {
                assertGraphOperations(graph, loaded)
            } finally {
                (loaded as Closeable).close()
            }

            Files.delete(dir.resolve("graph.labelprefix"))
            val loadedWithoutPrefix = GraphStore.loadMapped(dir)
            try {
                assertGraphOperations(graph, loadedWithoutPrefix)
            } finally {
                (loadedWithoutPrefix as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped load rebuilds mmap indexes from legacy node index`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-rebuild-mapped-index-test")
        try {
            GraphStore.save(graph, dir)
            Files.delete(dir.resolve("graph.nodeoffsets"))
            Files.delete(dir.resolve("graph.typeindex"))

            val loaded = GraphStore.loadMapped(dir)
            try {
                assertTrue(Files.exists(dir.resolve("graph.nodeoffsets")))
                assertTrue(Files.exists(dir.resolve("graph.typeindex")))
                assertGraphOperations(graph, loaded)
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped node indexes reject invalid headers and ranges`() {
        val dir = Files.createTempDirectory("webgraph-invalid-mapped-index-test")
        try {
            val offsetIndex = dir.resolve("graph.nodeoffsets")
            DataOutputStream(offsetIndex.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_NODEOFFSETS)
                dos.writeInt(-1)
            }
            assertFailsWith<IllegalArgumentException> { MappedNodeOffsetIndex.load(offsetIndex) }

            val typeIndex = dir.resolve("graph.typeindex")
            DataOutputStream(typeIndex.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_TYPEINDEX)
                dos.writeInt(-1)
            }
            assertFailsWith<IllegalArgumentException> { MappedNodeTypeIndex.load(typeIndex) }

            DataOutputStream(typeIndex.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_TYPEINDEX)
                dos.writeInt(1)
                dos.writeByte(NodeSerializer.TAG_INT_CONSTANT)
                dos.writeInt(-1)
                dos.writeLong(0L)
            }
            assertFailsWith<IllegalArgumentException> { MappedNodeTypeIndex.load(typeIndex) }

            DataOutputStream(typeIndex.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_TYPEINDEX)
                dos.writeInt(1)
                dos.writeByte(NodeSerializer.TAG_INT_CONSTANT)
                dos.writeInt(0)
                dos.writeLong(Int.MAX_VALUE.toLong() + 1L)
            }
            assertFailsWith<IllegalArgumentException> { MappedNodeTypeIndex.load(typeIndex) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `node tag helpers expose stable defensive mappings`() {
        val tags = nodeTagEntries()
        tags[0] = 99

        assertEquals(NodeSerializer.TAG_INT_CONSTANT, nodeTagEntries()[0])
        assertEquals(IntConstant::class.java, nodeClassForTag(NodeSerializer.TAG_INT_CONSTANT))
        assertNull(nodeClassForTag(255))
    }
}
