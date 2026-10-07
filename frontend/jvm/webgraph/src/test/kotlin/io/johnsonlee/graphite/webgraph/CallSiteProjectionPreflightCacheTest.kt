package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherQueryCancelledException
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.MmapGraph
import io.johnsonlee.graphite.graph.MmapGraphBuilder
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import it.unimi.dsi.webgraph.BVGraph
import java.io.DataInputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CallSiteProjectionPreflightCacheTest {
    @Test
    fun `successful preflight is reused while complete ordered rows and work remain per request`() {
        withStored(repeatedCalls) { dir ->
            val index = ObservedTypeIndex(MappedNodeTypeIndex.load(dir.resolve("graph.typeindex")))
            loadObserved(dir, index).use { mapped ->
                repeat(2) {
                    val expectedWork = if (it == 0) 8L else 4L
                    val context = CypherExecutionContext(CypherExecutionBudget(expectedWork))
                    val result = CypherExecutor(mapped, context).execute(QUERY)
                    assertEquals(listOf("c", "m", "again"), result.columns)
                    assertEquals(expectedRows, result.rows)
                    assertEquals(expectedWork, context.diagnostics.workUnitsConsumed)
                    // One complete preflight, plus one actual projection scan per request.
                    assertEquals(it + 2, index.scans.get())
                }
                assertFalse(mapped.isCallSiteStringIndexInitialized())
            }
        }
    }

    @Test
    fun `false preflight is reused without callbacks and cached refusal still honors interruption`() {
        withStored(listOf(call(0, "A", "first"), call(3, "old", "stale"),
            StringConstant(NodeId(3), "replacement"))) { dir ->
            val index = ObservedTypeIndex(MappedNodeTypeIndex.load(dir.resolve("graph.typeindex")))
            loadObserved(dir, index).use { mapped ->
                repeat(2) {
                    assertFalse(mapped.forEachStringPropertyProjection(CallSiteNode::class.java, PROPERTIES,
                        { error("Refusal must not invoke cancellation callback") }, { error("Refusal must not emit") }))
                }
                assertEquals(1, index.scans.get())
                try {
                    Thread.currentThread().interrupt()
                    assertFailsWith<CancellationException> { project(mapped) }
                    assertTrue(Thread.currentThread().isInterrupted)
                } finally {
                    Thread.interrupted()
                }
                val context = CypherExecutionContext(CypherExecutionBudget(2))
                assertEquals(listOf(
                    mapOf("c" to "A", "m" to "first", "again" to "A"),
                    mapOf("c" to null, "m" to null, "again" to null)
                ), CypherExecutor(mapped, context).execute(QUERY).rows)
                assertEquals(2L, context.diagnostics.workUnitsConsumed)
            }
        }
    }

    @Test
    fun `unsupported requests never initialize cache and empty supported scans retain cancellation callbacks`() {
        withStored(emptyList()) { dir ->
            val index = ObservedTypeIndex(MappedNodeTypeIndex.load(dir.resolve("graph.typeindex")))
            loadObserved(dir, index).use { mapped ->
                for (properties in listOf(emptyList(), listOf("line"))) {
                    assertFalse(mapped.forEachStringPropertyProjection(CallSiteNode::class.java, properties,
                        { error("Unsupported request must not poll") }, { error("Unsupported request must not emit") }))
                    assertFalse(mapped.forEachStringPropertyProjection(CallSiteNode::class.java, properties,
                        GraphWorkConsumer { error("Unsupported request must not consume preflight work") },
                        { error("Unsupported request must not poll") }, { error("Unsupported request must not emit") }))
                }
                assertEquals(0, index.scans.get())
                repeat(2) {
                    var checks = 0
                    assertTrue(mapped.forEachStringPropertyProjection(CallSiteNode::class.java, PROPERTIES,
                        { checks++ }, { error("Empty graph must not emit") }))
                    assertEquals(2, checks)
                }
                assertEquals(3, index.scans.get())
                assertEmptyCancellationPrecedence(mapped)
            }
        }
    }

    @Test
    fun `cold budget pays each preflight ID and failed validation retries without cached partial state`() {
        withStored(repeatedCalls) { dir ->
            val index = ObservedTypeIndex(MappedNodeTypeIndex.load(dir.resolve("graph.typeindex")))
            loadObserved(dir, index).use { mapped ->
                val tooSmall = CypherExecutionContext(CypherExecutionBudget(3))
                val failure = assertFailsWith<CypherBudgetExceededException> {
                    CypherExecutor(mapped, tooSmall).execute(QUERY)
                }
                assertEquals(3L, failure.maxWorkUnits)
                assertEquals(3L, tooSmall.diagnostics.workUnitsConsumed)
                assertEquals(1, index.scans.get(), "Budget failure must precede the row scan")

                val almostEnough = CypherExecutionContext(CypherExecutionBudget(7))
                assertFailsWith<CypherBudgetExceededException> { CypherExecutor(mapped, almostEnough).execute(QUERY) }
                assertEquals(7L, almostEnough.diagnostics.workUnitsConsumed)
                assertEquals(3, index.scans.get(), "Fresh request repeats preflight then scans rows")

                val warm = CypherExecutionContext(CypherExecutionBudget(4))
                assertEquals(expectedRows, CypherExecutor(mapped, warm).execute(QUERY).rows)
                assertEquals(4L, warm.diagnostics.workUnitsConsumed)
                assertEquals(4, index.scans.get(), "Completed validation survives later row budget failure")

                val warmTooSmall = CypherExecutionContext(CypherExecutionBudget(3))
                val warmFailure = assertFailsWith<CypherBudgetExceededException> {
                    CypherExecutor(mapped, warmTooSmall).execute(QUERY)
                }
                assertEquals(3L, warmFailure.maxWorkUnits)
                assertEquals(3L, warmTooSmall.diagnostics.workUnitsConsumed)
                assertEquals(5, index.scans.get(), "Warm failure scans rows without repeating preflight")
            }
        }
    }

    @Test
    fun `cold structural refusal charges inspected IDs before ordinary fallback and warm refusal does not`() {
        withStored(listOf(call(0, "A", "first"), call(3, "old", "stale"),
            StringConstant(NodeId(3), "replacement"))) { dir ->
            val index = ObservedTypeIndex(MappedNodeTypeIndex.load(dir.resolve("graph.typeindex")))
            loadObserved(dir, index).use { mapped ->
                for (work in listOf(4L, 2L)) {
                    val context = CypherExecutionContext(CypherExecutionBudget(work))
                    assertEquals(listOf(
                        mapOf("c" to "A", "m" to "first", "again" to "A"),
                        mapOf("c" to null, "m" to null, "again" to null)
                    ), CypherExecutor(mapped, context).execute(QUERY).rows)
                    assertEquals(work, context.diagnostics.workUnitsConsumed)
                }
                assertEquals(3, index.scans.get(), "One refusal preflight and two ordinary scans")
            }
        }
    }

    @Test
    fun `preflight work cancellation keeps exception identity and retries before invoking row callbacks`() {
        withStored(repeatedCalls) { dir ->
            val index = ObservedTypeIndex(MappedNodeTypeIndex.load(dir.resolve("graph.typeindex")))
            loadObserved(dir, index).use { mapped ->
                val failure = CancellationException("cancel during preflight accounting")
                var work = 0
                assertSame(failure, assertFailsWith<CancellationException> {
                    mapped.forEachStringPropertyProjection(CallSiteNode::class.java, PROPERTIES,
                        GraphWorkConsumer { if (++work == 2) throw failure },
                        { error("Incomplete preflight must not reach projection cancellation") },
                        { error("Incomplete preflight must not emit") })
                })
                assertEquals(2, work)
                assertEquals(1, index.scans.get())
                val rows = mutableListOf<StringPropertyProjectionRow>()
                work = 0
                assertTrue(mapped.forEachStringPropertyProjection(CallSiteNode::class.java, PROPERTIES,
                    GraphWorkConsumer { work++ }, {}, rows::add))
                assertEquals(4, work)
                assertEquals(3, index.scans.get())
                assertEquals(listOf(listOf("A", "first", "A"), listOf("Å", "duplicate", "Å"),
                    listOf("Z", "last", "Z"), listOf("Å", "duplicate", "Å")), rows.map { it.values })
            }
        }
    }

    @Test
    fun `request cancellation during cold preflight stops before the next header and retries on a new request`() {
        withStored(repeatedCalls) { dir ->
            val signal = CypherCancellationSignal()
            val reason = CypherQueryCancelledException("request stopped during preflight")
            val delegate = MappedNodeTypeIndex.load(dir.resolve("graph.typeindex"))
            var scanned = 0
            val index = object : NodeTypeIndex by delegate {
                override fun ids(type: Class<out Node>): Sequence<Int> = sequence {
                    val currentScan = ++scanned
                    delegate.ids(type).forEachIndexed { offset, id ->
                        if (currentScan == 1 && offset == 1) signal.cancel(reason)
                        yield(id)
                    }
                }
            }
            loadObserved(dir, index).use { mapped ->
                val cancelled = CypherExecutionContext(CypherExecutionBudget(8), signal)
                assertSame(reason, assertFailsWith<CypherQueryCancelledException> {
                    CypherExecutor(mapped, cancelled).execute(QUERY)
                })
                assertEquals(1L, cancelled.diagnostics.workUnitsConsumed)
                assertEquals(1, scanned)
                val restored = CypherExecutionContext(CypherExecutionBudget(8))
                assertEquals(expectedRows, CypherExecutor(mapped, restored).execute(QUERY).rows)
                assertEquals(8L, restored.diagnostics.workUnitsConsumed)
                assertEquals(3, scanned)
            }
        }
    }

    private fun assertEmptyCancellationPrecedence(mapped: MappedWebGraphBackedGraph) {
        val cancelled = CancellationException("empty caller cancellation")
        try {
            Thread.currentThread().interrupt()
            assertSame(cancelled, assertFailsWith<CancellationException> {
                mapped.forEachStringPropertyProjection(CallSiteNode::class.java, PROPERTIES, { throw cancelled }, {})
            })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `preflight exception is not cached but later consumer cancellation does not discard completed validation`() {
        withStored(listOf(call(0, "A", "first"))) { dir ->
            val failure = IllegalStateException("index failed before a complete result")
            val index = ObservedTypeIndex(MappedNodeTypeIndex.load(dir.resolve("graph.typeindex"))) { scan ->
                if (scan == 1) throw failure
            }
            loadObserved(dir, index).use { mapped ->
                assertSame(failure, assertFailsWith<IllegalStateException> { project(mapped) })
                val cancelled = CancellationException("caller cancelled after validation")
                assertSame(cancelled, assertFailsWith<CancellationException> {
                    mapped.forEachStringPropertyProjection(CallSiteNode::class.java, PROPERTIES, { throw cancelled }, {})
                })
                assertEquals(2, index.scans.get())
                assertEquals(listOf(listOf("A", "first", "A")), project(mapped))
                assertEquals(3, index.scans.get())
            }
        }
    }

    @Test
    fun `interrupted validation retries but completed validation survives later caller interruption`() {
        withStored(listOf(call(0, "A", "first"))) { dir ->
            for (interruptAfterScan in listOf(false, true)) {
                assertInterruptedScanPublication(dir, interruptAfterScan)
            }
        }
    }

    private fun assertInterruptedScanPublication(dir: Path, interruptAfterScan: Boolean) {
        val delegate = MappedNodeTypeIndex.load(dir.resolve("graph.typeindex"))
        val scans = AtomicInteger()
        val index = object : NodeTypeIndex by delegate {
            override fun ids(type: Class<out Node>): Sequence<Int> {
                val scan = scans.incrementAndGet()
                return sequence {
                    if (scan == 1 && !interruptAfterScan) Thread.currentThread().interrupt()
                    yieldAll(delegate.ids(type))
                    if (scan == 1 && interruptAfterScan) Thread.currentThread().interrupt()
                }
            }
        }
        loadObserved(dir, index).use { mapped ->
            try {
                assertFailsWith<CancellationException> { project(mapped) }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
            val scansAfterRetry = if (interruptAfterScan) 2 else 3
            assertEquals(listOf(listOf("A", "first", "A")), project(mapped))
            assertEquals(scansAfterRetry, scans.get())
            assertEquals(listOf(listOf("A", "first", "A")), project(mapped))
            assertEquals(scansAfterRetry + 1, scans.get())
        }
    }

    @Test
    fun `cold cancelled caller does not wait for another initializer and does not poison its publication`() {
        withStored(listOf(call(0, "A", "first"))) { dir ->
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val cancelledThread = AtomicReference<Thread>()
            val index = ObservedTypeIndex(MappedNodeTypeIndex.load(dir.resolve("graph.typeindex"))) { scan ->
                when (scan) {
                    1 -> { firstEntered.countDown(); check(releaseFirst.await(5, TimeUnit.SECONDS)) }
                    2 -> awaitCancellation(secondEntered)
                }
            }
            loadObserved(dir, index).use { mapped ->
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val first = executor.submit<List<List<String?>>> { project(mapped) }
                    assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
                    val second = executor.submit<Boolean> { cancelledProjection(mapped, cancelledThread) }
                    assertTrue(secondEntered.await(5, TimeUnit.SECONDS), "Cold caller waited on another initializer")
                    cancelledThread.get().interrupt()
                    assertTrue(second.get(5, TimeUnit.SECONDS), "Cancellation must preserve the caller interrupt")
                    assertEquals(1L, releaseFirst.count, "Cancellation must complete before releasing the first scan")
                    releaseFirst.countDown()
                    assertEquals(listOf(listOf("A", "first", "A")), first.get(5, TimeUnit.SECONDS))
                    assertEquals(listOf(listOf("A", "first", "A")), project(mapped))
                    assertEquals(4, index.scans.get())
                } finally {
                    releaseFirst.countDown()
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    private fun cancelledProjection(mapped: MappedWebGraphBackedGraph, thread: AtomicReference<Thread>): Boolean {
        thread.set(Thread.currentThread())
        try {
            assertFailsWith<CancellationException> { project(mapped) }
            return Thread.currentThread().isInterrupted
        } finally {
            Thread.interrupted()
        }
    }

    private fun awaitCancellation(entered: CountDownLatch) {
        entered.countDown()
        try {
            check(CountDownLatch(1).await(5, TimeUnit.SECONDS)) { "Expected a caller interrupt" }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun project(mapped: MappedWebGraphBackedGraph): List<List<String?>> {
        val rows = mutableListOf<StringPropertyProjectionRow>()
        assertTrue(mapped.forEachStringPropertyProjection(CallSiteNode::class.java, PROPERTIES, {}, rows::add))
        return rows.map { it.values }
    }

    private class ObservedTypeIndex(
        private val delegate: NodeTypeIndex,
        private val beforeScan: (Int) -> Unit = {}
    ) : NodeTypeIndex by delegate {
        val scans = AtomicInteger()

        override fun ids(type: Class<out Node>): Sequence<Int> {
            beforeScan(scans.incrementAndGet())
            return delegate.ids(type)
        }
    }

    private fun withStored(nodes: List<Node>, block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("projection-preflight-cache")
        try {
            val builder = MmapGraphBuilder(Files.createDirectory(directory.resolve("builder")))
            nodes.forEach(builder::addNode)
            val stored = directory.resolve("stored")
            (builder.build() as MmapGraph).use { GraphStore.save(it, stored) }
            block(stored)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    // Constructor injection observes the actual persisted index without reflection or production hooks.
    // This fixture has no edges, metadata, resources or ordinal-bearing calls.
    private fun loadObserved(dir: Path, index: NodeTypeIndex): MappedWebGraphBackedGraph {
        val data = dir.resolve("graph.nodedata")
        val version = DataInputStream(Files.newInputStream(data)).use {
            NodeSerializer.readHeader(it, NodeSerializer.MAGIC_NODEDATA)
        }
        val buffer = FileChannel.open(data, StandardOpenOption.READ).use {
            it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
        }
        val forward = BVGraph.load(dir.resolve("forward").toString())
        return MappedWebGraphBackedGraph(
            forward = forward, backward = lazy { error("No incoming edges in this fixture") },
            mappedNodeData = buffer, nodeDataVersion = version, stringTable = StringTable.load(dir),
            nodeOffsets = MappedNodeOffsetIndex.load(dir.resolve("graph.nodeoffsets")), nodeTypeIndex = index,
            forwardLabels = byteArrayOf(), cumulativeOutdeg = IntArray(forward.numNodes() + 1), edgeCount = 0,
            metadataFile = dir.resolve("graph.metadata").toFile(),
            callSiteStringIndexFile = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE),
            persistentCallSiteStringIndexEnabled = false, methodCount = 0,
            comparisonLookup = EmptyBranchComparisonLookup,
            metadata = lazy { error("Projection must not read graph metadata") },
            classOverviewProvider = { error("Projection must not read class overview") },
            resourceAccessor = lazy { error("Projection must not read resources") }
        )
    }

    private fun call(id: Int, target: String, name: String): CallSiteNode = CallSiteNode(
        NodeId(id), MethodDescriptor(TypeDescriptor("Caller"), "run", emptyList(), TypeDescriptor("void")),
        MethodDescriptor(TypeDescriptor(target), name, emptyList(), TypeDescriptor("void")), id, null, emptyList()
    )

    private val repeatedCalls = listOf(
        call(0, "A", "first"), call(3, "old", "replaced"), StringConstant(NodeId(8), "unrelated"),
        call(13, "Z", "last"), call(3, "Å", "duplicate")
    )
    private val expectedRows = listOf(
        mapOf("c" to "A", "m" to "first", "again" to "A"),
        mapOf("c" to "Z", "m" to "last", "again" to "Z"),
        mapOf("c" to "Å", "m" to "duplicate", "again" to "Å"),
        mapOf("c" to "Å", "m" to "duplicate", "again" to "Å")
    )

    companion object {
        private val PROPERTIES = listOf("callee_class", "callee_name", "callee_class")
        private const val QUERY = "MATCH (n:CallSiteNode) RETURN n.callee_class AS c, n.callee_name AS m, " +
            "n.callee_class AS again ORDER BY c, m LIMIT 20"
    }
}
