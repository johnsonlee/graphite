package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MappedStringPropertyProjectionTest {
    private val properties = listOf("callee_name", "caller_class", "callee_class", "caller_name", "callee_name")
    private val calls = listOf(
        call(0, listOf("int", "java.lang.String[]")), call(3, emptyList()),
        call(8, listOf("long")), call(13, listOf("boolean", "float", "double"))
    )

    @Test
    fun `raw streaming retains encounter order duplicated properties and values across reopening without index admission`() {
        for (prepared in listOf(false, true)) {
            withStored(calls, prepared) { dir ->
                repeat(2) {
                    (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
                        val expected = mapped.nodes(CallSiteNode::class.java).map(::projectedValues).toList()
                        val rows = mutableListOf<StringPropertyProjectionRow>()
                        var checks = 0
                        assertTrue(mapped.forEachStringPropertyProjection(CallSiteNode::class.java, properties, { checks++ }, rows::add))
                        assertEquals(expected, rows.map { it.values })
                        assertEquals(mapOf<List<String?>, Int>(
                            listOf("调用", "CallerΩ", "Target😀", "caller", "调用") to 2,
                            listOf("z", "Second", "TargetÅ", "second", "z") to 1,
                            listOf("a", "Callerβ", "A", "last", "a") to 1
                        ), rows.map { it.values }.groupingBy { it }.eachCount())
                        assertEquals(calls.size + 2, checks)
                        assertFalse(mapped.isCallSiteStringIndexInitialized())
                        assertFalse(mapped.isMappedCallSiteStringIndexViewInitialized())
                        assertEquals(0L, mapped.callSiteStringIndexLookupCount())
                        assertEquals(0, mapped.rawProjectionMatchCount())
                        assertEquals(prepared, Files.exists(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))
                    }
                }
            }
        }
    }

    @Test
    fun `unsupported requests return false before either callback`() {
        withStored(calls) { dir ->
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
                for (requested in listOf(emptyList(), listOf("callee_name", "line"), listOf("signature"))) {
                    assertFalse(mapped.forEachStringPropertyProjection(CallSiteNode::class.java, requested,
                        { error("Unsupported request must not poll") }, { error("Unsupported request must not emit") }))
                }
                assertFalse(mapped.forEachStringPropertyProjection(StringConstant::class.java, listOf("value"),
                    { error("Unsupported type must not poll") }, { error("Unsupported type must not emit") }))
                assertFalse(mapped.isCallSiteStringIndexInitialized())
            }
        }
    }

    @Test
    fun `raw projection does not decode an unrequested descriptor return type`() {
        withStored(calls) { dir ->
            val tuplesById = calls.associate { it.id to projectedValues(it) }
            val expected = (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
                mapped.nodes(CallSiteNode::class.java).map { tuplesById.getValue(it.id) }.toList()
            }
            // Deliberately invalidate only an irrelevant field, after saving all valid indexes.
            // Full node decoding must fail; raw class/name projection must never consult this id.
            val offset = MappedNodeOffsetIndex.load(dir.resolve("graph.nodeoffsets")).offset(0)
            RandomAccessFile(dir.resolve("graph.nodedata").toFile(), "rw").use { data ->
                val nodeHeader = Int.SIZE_BYTES + Byte.SIZE_BYTES
                val callerReturnIndex = 3 + calls.first().caller.parameterTypes.size
                data.seek(offset + nodeHeader + callerReturnIndex * Int.SIZE_BYTES)
                data.writeInt(Int.MAX_VALUE)
            }
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
                assertFailsWith<IndexOutOfBoundsException> { mapped.node(NodeId(0)) }
                val rows = mutableListOf<StringPropertyProjectionRow>()
                assertTrue(mapped.forEachStringPropertyProjection(CallSiteNode::class.java, properties, {}, rows::add))
                assertEquals(expected, rows.map { it.values })
            }
        }
    }

    @Test
    fun `cancellation and consumer errors stop streaming without losing their original exception`() {
        withStored(calls) { dir ->
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
                val cancellation = CancellationException("stop raw projection")
                var emitted = 0
                assertSame(cancellation, assertFailsWith<CancellationException> {
                    mapped.forEachStringPropertyProjection(CallSiteNode::class.java, properties,
                        { if (emitted == 1) throw cancellation }, { emitted++ })
                })
                assertEquals(1, emitted)
                val failure = IllegalStateException("consumer failed")
                assertSame(failure, assertFailsWith<IllegalStateException> {
                    mapped.forEachStringPropertyProjection(CallSiteNode::class.java, properties, {}, { throw failure })
                })
                assertFalse(mapped.isCallSiteStringIndexInitialized())
            }
        }
    }

    @Test
    fun `empty scans poll cancellation and interruption without requiring work accounting`() {
        withStored(emptyList()) { dir ->
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
                var checks = 0
                assertTrue(mapped.forEachStringPropertyProjection(CallSiteNode::class.java, properties,
                    { checks++ }, { error("Empty graph must not emit") }))
                assertEquals(2, checks)
                val cancelled = CancellationException("already cancelled")
                assertSame(cancelled, assertFailsWith<CancellationException> {
                    mapped.forEachStringPropertyProjection(CallSiteNode::class.java, properties,
                        { throw cancelled }, { error("Cancelled graph must not emit") })
                })
                try {
                    Thread.currentThread().interrupt()
                    val failure = assertFailsWith<CancellationException> {
                        mapped.forEachStringPropertyProjection(CallSiteNode::class.java, properties, {}, {})
                    }
                    assertEquals(CancellationException::class.java, failure.javaClass)
                    assertEquals("Mapped CallSite projection interrupted", failure.message)
                    assertTrue(Thread.currentThread().isInterrupted)
                } finally {
                    Thread.interrupted()
                }
            }
        }
    }

    @Test
    fun `projection checks interruption after callback and preserves callback failure precedence`() {
        withStored(emptyList()) { dir ->
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
                var checks = 0
                try {
                    val failure = assertFailsWith<CancellationException> {
                        mapped.forEachStringPropertyProjection(CallSiteNode::class.java, properties, {
                            checks++
                            Thread.currentThread().interrupt()
                        }, { error("Interrupted empty scan must not emit") })
                    }
                    assertEquals(1, checks)
                    assertEquals(CancellationException::class.java, failure.javaClass)
                    assertEquals("Mapped CallSite projection interrupted", failure.message)
                    assertTrue(Thread.currentThread().isInterrupted)
                } finally {
                    Thread.interrupted()
                }
                val original = IllegalStateException("callback failed after interruption")
                try {
                    assertSame(original, assertFailsWith<IllegalStateException> {
                        mapped.forEachStringPropertyProjection(CallSiteNode::class.java, properties, {
                            Thread.currentThread().interrupt()
                            throw original
                        }, { error("Failed callback must not emit") })
                    })
                    assertTrue(Thread.currentThread().isInterrupted)
                } finally {
                    Thread.interrupted()
                }
            }
        }
    }

    private fun call(id: Int, parameters: List<String>): CallSiteNode {
        val names = when (id) {
            3 -> listOf("Second", "second", "TargetÅ", "z")
            8 -> listOf("Callerβ", "last", "A", "a")
            else -> listOf("CallerΩ", "caller", "Target😀", "调用")
        }
        return CallSiteNode(
            NodeId(id),
            MethodDescriptor(TypeDescriptor(names[0]), names[1], parameters.map { TypeDescriptor(it) }, TypeDescriptor("void")),
            MethodDescriptor(TypeDescriptor(names[2]), names[3], listOf(TypeDescriptor("double")), TypeDescriptor("long")),
            id, null, emptyList()
        )
    }

    private fun projectedValues(node: CallSiteNode): List<String> = listOf(
        node.callee.name, node.caller.declaringClass.className, node.callee.declaringClass.className,
        node.caller.name, node.callee.name
    )

    private fun withStored(calls: List<CallSiteNode>, prepared: Boolean = false, block: (Path) -> Unit) {
        val graph = DefaultGraph.Builder().apply { calls.forEach { addNode(it) } }.build()
        val dir = Files.createTempDirectory("mapped-streaming-projection-test")
        try {
            GraphStore.save(graph, dir, prepareCallSiteStringIndex = prepared)
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
