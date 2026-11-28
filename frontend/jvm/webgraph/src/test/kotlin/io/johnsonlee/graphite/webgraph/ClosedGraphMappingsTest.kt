package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import java.io.Closeable
import java.lang.ref.Reference
import java.nio.MappedByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The contract of [ClosedGraphMappings]: closed graphs are counted until the collector releases them, the collector is asked once the
 * count passes the budget and at most once per budget closed, and asking is what unmaps the files of graphs nothing holds any more.
 */
class ClosedGraphMappingsTest {

    private class Requests : () -> Unit {
        var count = 0

        override fun invoke() {
            count++
        }
    }

    @Test
    fun `closed graphs within the budget ask for no collection`() {
        val requests = Requests()
        val mappings = ClosedGraphMappings(budgetBytes = 100, collect = requests)
        val owners = List(4) { Any() }
        owners.forEach { mappings.closed(it, 25) }
        assertEquals(100, mappings.unreleasedBytes())
        assertEquals(0, requests.count, "exactly the budget is within it")
        Reference.reachabilityFence(owners)
    }

    @Test
    fun `the collector is asked once the unreleased bytes pass the budget`() {
        val requests = Requests()
        val mappings = ClosedGraphMappings(budgetBytes = 100, collect = requests)
        val owners = List(5) { Any() }
        owners.take(4).forEach { mappings.closed(it, 25) }
        assertEquals(0, requests.count)
        mappings.closed(owners[4], 1)
        assertEquals(1, requests.count)
        assertEquals(101, mappings.unreleasedBytes(), "asking uncounts nothing: only a release does")
        Reference.reachabilityFence(owners)
    }

    @Test
    fun `graphs that stay reachable cost one request per budget closed, never one per close`() {
        val requests = Requests()
        val mappings = ClosedGraphMappings(budgetBytes = 100, collect = requests)
        // Every owner stays reachable, as when a caller keeps the graphs it closed: nothing is ever released.
        val owners = List(40) { Any() }
        val askingCloses = mutableListOf<Int>()
        owners.forEachIndexed { index, owner ->
            val before = requests.count
            mappings.closed(owner, 10)
            if (requests.count > before) askingCloses += index + 1
        }
        assertEquals(400, mappings.unreleasedBytes())
        // Closes 1..10 reach the budget, the 11th passes it; after that every 11th close has closed more than a budget again.
        assertEquals(listOf(11, 22, 33), askingCloses)
        Reference.reachabilityFence(owners)
    }

    @Test
    fun `a released graph leaves the count and its bytes are free to be closed again`() {
        val requests = Requests()
        val mappings = ClosedGraphMappings(budgetBytes = 100, collect = requests)
        val owners = List(3) { Any() }
        mappings.closed(owners[0], 60)
        ClosedGraphMappings.Released(mappings, 60).run()
        assertEquals(0, mappings.unreleasedBytes())
        mappings.closed(owners[1], 60)
        mappings.closed(owners[2], 40)
        assertEquals(100, mappings.unreleasedBytes())
        assertEquals(0, requests.count, "160 bytes were closed, but never more than the budget at once")
        Reference.reachabilityFence(owners)
    }

    @Test
    fun `a graph that maps nothing is not counted`() {
        val requests = Requests()
        val mappings = ClosedGraphMappings(budgetBytes = 0, collect = requests)
        mappings.closed(Any(), 0)
        mappings.closed(Any(), -1)
        assertEquals(0, mappings.unreleasedBytes())
        assertEquals(0, requests.count)
    }

    @Test
    fun `the collector releasing a closed owner uncounts it`() {
        val mappings = ClosedGraphMappings(budgetBytes = Long.MAX_VALUE, collect = {})
        val kept = Any()
        mappings.closed(kept, 7)
        mappings.closed(Any(), 1_000)
        awaitCollected("the unreachable owner's bytes to leave the count") { mappings.unreleasedBytes() == 7L }
        assertEquals(7, mappings.unreleasedBytes(), "the owner still held stays counted")
        Reference.reachabilityFence(kept)
    }

    @Test
    fun `the bound of what a graph maps is the size of its files`() = inTemp { root ->
        Files.write(root.resolve("graph.a"), ByteArray(3))
        Files.write(root.resolve("graph.b"), ByteArray(5))
        Files.write(Files.createDirectory(root.resolve("META-INF")).resolve("ignored"), ByteArray(11))
        assertEquals(8, ClosedGraphMappings.mappableBytes(root))
    }

    @Test
    fun `a mapped load counts against the process budget unless told otherwise`() = inTemp { root ->
        assertEquals(256L * 1024 * 1024, ClosedGraphMappings.PROCESS_BUDGET_BYTES)
        val dir = saved(root.resolve("g"), 3)
        val graph = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
        try {
            assertSame(ClosedGraphMappings.process, graph.context.closedMappings)
            assertEquals(ClosedGraphMappings.mappableBytes(dir), graph.context.mappedBytes)
        } finally {
            graph.close()
        }
    }

    @Test
    fun `a mapped graph is counted by its files when it closes, and once however often it closes`() = inTemp { root ->
        val dir = saved(root.resolve("g"), 3)
        val requests = Requests()
        val mappings = ClosedGraphMappings(budgetBytes = Long.MAX_VALUE, collect = requests)
        val graph = loadMappedGraph(dir, false, persistentCallSiteStringIndex = false, closedMappings = mappings)
        assertEquals(4, graph.nodes(Node::class.java).count())
        assertEquals(0, mappings.unreleasedBytes(), "an open graph is not counted")
        (graph as Closeable).close()
        val bytes = ClosedGraphMappings.mappableBytes(dir)
        assertTrue(bytes > 0)
        assertEquals(bytes, mappings.unreleasedBytes())
        graph.close()
        assertEquals(bytes, mappings.unreleasedBytes(), "the second close counts nothing")
        assertEquals(0, requests.count)
        // What is counted is the graph itself: while it is held, collections release a closed owner beside it and not its bytes.
        mappings.closed(Any(), 1)
        awaitCollected("the unreachable owner to be released") { mappings.unreleasedBytes() <= bytes }
        System.gc()
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(bytes, mappings.unreleasedBytes(), "a closed graph that is still held stays counted")
        Reference.reachabilityFence(graph)
    }

    @Test
    fun `a closed graph stays counted for as long as its node data is mapped, whoever holds it`() = inTemp { root ->
        val dir = saved(root.resolve("g"), 3)
        val mappings = ClosedGraphMappings(budgetBytes = Long.MAX_VALUE, collect = {})
        // Only the buffer outlives the call, as when a collection promotes it and frees the graph around it.
        val nodeData = nodeDataOfClosedGraph(dir, mappings)
        val bytes = ClosedGraphMappings.mappableBytes(dir)
        mappings.closed(Any(), 1)
        awaitCollected("the unreachable owner to be released") { mappings.unreleasedBytes() <= bytes }
        System.gc()
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(bytes, mappings.unreleasedBytes(), "the file is still mapped, so the graph is still counted")
        Reference.reachabilityFence(nodeData)
    }

    @Test
    fun `closing mapped graphs past the budget unmaps the files of those nothing holds`() = inTemp { root ->
        assumeTrue("needs /proc/self/maps", Files.isReadable(MAPS))
        val dirs = (0 until GRAPHS).map { index -> saved(root.resolve("g$index"), 200 + index) }
        val requests = Requests()
        // A budget below any graph: every close asks, and what it asks for is a real collection.
        val mappings =
            ClosedGraphMappings(budgetBytes = 1) {
                requests()
                System.gc()
            }
        for (dir in dirs) {
            val graph = loadMappedGraph(dir, false, persistentCallSiteStringIndex = false, closedMappings = mappings)
            assertTrue(graph.nodes(Node::class.java).count() > 200)
            assertTrue(mappedFilesOf(dir).isNotEmpty(), "an open graph maps its files")
            (graph as Closeable).close()
        }
        assertEquals(GRAPHS, requests.count)
        // Each graph but the last was unreachable when a later close asked. This test asks for no collection of its own: the
        // requests made by the closes are what unmaps the files, once the collector's cleaner thread has run.
        awaitWithoutCollecting("the closed graphs' files to be unmapped") { dirs.dropLast(1).all { mappedFilesOf(it).isEmpty() } }
    }

    /** A function of its own: once it returns, no frame holds the graph it closed. */
    private fun nodeDataOfClosedGraph(dir: Path, mappings: ClosedGraphMappings): MappedByteBuffer {
        val graph = loadMappedGraph(dir, false, persistentCallSiteStringIndex = false, closedMappings = mappings)
        (graph as MappedWebGraphBackedGraph).close()
        return graph.context.mappedNodeData
    }

    private fun saved(dir: Path, constants: Int): Path {
        GraphStore.save(chain(constants), dir)
        return dir
    }

    private fun chain(constants: Int): Graph {
        val builder = DefaultGraph.Builder()
        val method = MethodDescriptor(TypeDescriptor("com.example.Chain"), "run", emptyList(), TypeDescriptor("void"))
        builder.addMethod(method)
        val local = LocalVariable(NodeId.next(), "x", TypeDescriptor("int"), method)
        builder.addNode(local)
        repeat(constants) { value ->
            val constant = IntConstant(NodeId.next(), value)
            builder.addNode(constant)
            builder.addEdge(DataFlowEdge(constant.id, local.id, DataFlowKind.ASSIGN))
        }
        return builder.build()
    }

    private fun mappedFilesOf(dir: Path): List<String> {
        val prefix = dir.toRealPath().toString() + "/"
        return Files.readAllLines(MAPS).filter { line -> prefix in line }
    }

    /** The collector's cleaner runs on its own thread: wait for its effect, with a deadline. */
    private fun awaitWithoutCollecting(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + DEADLINE_NANOS
        while (!done()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun awaitCollected(what: String, done: () -> Boolean) =
        awaitWithoutCollecting(what) {
            System.gc()
            done()
        }

    private inline fun inTemp(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("closed-graph-mappings")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val GRAPHS = 6
        const val DEADLINE_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 20L
        const val SETTLE_MILLIS = 200L
        val MAPS: Path = Path.of("/proc/self/maps")
    }
}
