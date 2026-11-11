package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.ClassDependency
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies graph overview store. */
class GraphOverviewStoreTest : GraphStoreTestFixture() {

    @Test
    fun `methods with pattern filter on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-method-filter-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertEquals(8L, loaded.nodeCount(Node::class.java))
            assertEquals(1L, loaded.nodeCount(CallSiteNode::class.java))

            val barMethods = loaded.methods(MethodPattern(name = "bar")).toList()
            assertEquals(1, barMethods.size)
            assertEquals("bar", barMethods[0].name)

            val allMethods = loaded.methods(MethodPattern()).toList()
            assertEquals(2, allMethods.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph exposes method count and limited method slices`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-method-slice-test")
        val loadedGraphs = mutableListOf<Graph>()
        try {
            GraphStore.save(graph, dir)
            loadedGraphs += GraphStore.loadMapped(dir)

            for (loaded in loadedGraphs) {
                assertEquals(8L, loaded.nodeCount(Node::class.java))
                assertEquals(1L, loaded.nodeCount(CallSiteNode::class.java))
                assertEquals(graph.edgeCount(), loaded.edgeCount())
                assertEquals(2L, loaded.methodCount())
                assertEquals(listOf("bar", "baz"), loaded.methods(MethodPattern()).map(MethodDescriptor::name).sorted().toList())

                val firstMethod = assertNotNull(loaded.methodSlice(MethodPattern(), 1))
                assertEquals(1, firstMethod.size)

                val barMethods = assertNotNull(loaded.methodSlice(MethodPattern(name = "bar"), 10))
                assertEquals(1, barMethods.size)
                assertEquals("bar", barMethods[0].name)

                val missingMethods = assertNotNull(loaded.methodSlice(MethodPattern(name = "missing"), 10))
                assertTrue(missingMethods.isEmpty())

                val zeroLimit = assertNotNull(loaded.methodSlice(MethodPattern(), 0))
                assertTrue(zeroLimit.isEmpty())
            }
        } finally {
            loadedGraphs.forEach { (it as? Closeable)?.close() }
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loaded graphs expose persisted class overview`() {
        val callerType = TypeDescriptor("com.example.Caller")
        val calleeType = TypeDescriptor("com.example.Callee")
        val caller = MethodDescriptor(callerType, "call", emptyList(), TypeDescriptor("void"))
        val callee = MethodDescriptor(calleeType, "serve", emptyList(), TypeDescriptor("void"))
        val callSite = CallSiteNode(NodeId.next(), caller, callee, 42, null, emptyList())
        val graph = DefaultGraph.Builder().addNode(callSite).addMethod(caller).addMethod(callee).build()
        val dir = Files.createTempDirectory("webgraph-class-overview-test")
        val loadedGraphs = mutableListOf<Graph>()
        try {
            GraphStore.save(graph, dir)
            loadedGraphs += GraphStore.load(dir, GraphStore.LoadMode.EAGER)
            loadedGraphs += GraphStore.loadMapped(dir)

            for (loaded in loadedGraphs) {
                val overview = assertNotNull(loaded.classOverview(10))
                assertEquals(1, overview.callSiteCount)
                assertEquals(1, overview.classCounts["com.example.Caller"])
                assertEquals(1, overview.classCounts["com.example.Callee"])
                assertEquals(1, overview.classEdges[ClassDependency("com.example.Caller", "com.example.Callee")])
                assertEquals(2, assertNotNull(loaded.classOverview(Int.MAX_VALUE)).classCounts.size)
            }
        } finally {
            loadedGraphs.forEach { (it as? Closeable)?.close() }
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `class overview store bounds reads and provider cache`() {
        val overview =
            ClassOverview(
                classCounts = linkedMapOf("com.example.A" to 3, "com.example.B" to 2, "com.example.C" to 1),
                classEdges =
                    linkedMapOf(
                        ClassDependency("com.example.A", "com.example.B") to 2, ClassDependency("com.example.B", "com.example.C") to 1),
                callSiteCount = 3)
        val dir = Files.createTempDirectory("class-overview-store-test")
        try {
            val strings = mutableSetOf<String>()
            ClassOverviewStore.collectStrings(overview, strings)
            val stringTable = StringTable.build(strings, dir)
            assertNull(ClassOverviewStore.load(dir, stringTable, 10))

            ClassOverviewStore.save(overview, dir, stringTable)
            assertTrue(Files.isRegularFile(dir.resolve(ClassOverviewStore.FILE_NAME)))
            assertEquals(0, ClassOverviewStore.boundLimit(-1))
            assertEquals(ClassOverviewStore.MAX_PERSISTED_CLASSES, ClassOverviewStore.boundLimit(Int.MAX_VALUE))

            val negative = assertNotNull(ClassOverviewStore.load(dir, stringTable, -1))
            assertTrue(negative.classCounts.isEmpty())
            assertTrue(negative.classEdges.isEmpty())

            val firstClass = assertNotNull(ClassOverviewStore.load(dir, stringTable, 1))
            assertEquals(listOf("com.example.A"), firstClass.classCounts.keys.toList())
            assertTrue(firstClass.classEdges.isEmpty())

            val competing = PersistedClassOverviewProvider(dir, stringTable)
            val task = java.util.concurrent.FutureTask { competing.load(1) }
            val reader = Thread(task, "overview-concurrent-reader")
            synchronized(competing) {
                reader.start()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (reader.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, reader.state, "reader must reach the cache publication monitor")
                assertEquals(3, assertNotNull(competing.load(3)).classCounts.size)
            }
            assertEquals(listOf("com.example.A"),
                assertNotNull(task.get(5, java.util.concurrent.TimeUnit.SECONDS)).classCounts.keys.toList())
            reader.join(5_000)
            assertFalse(reader.isAlive)

            val provider = PersistedClassOverviewProvider(dir, stringTable)
            val full = assertNotNull(provider.load(Int.MAX_VALUE))
            assertEquals(3, full.classCounts.size)
            assertEquals(2, full.classEdges.size)
            val cachedSlice = assertNotNull(provider.load(2))
            assertEquals(listOf("com.example.A", "com.example.B"), cachedSlice.classCounts.keys.toList())
            assertEquals(1, cachedSlice.classEdges.size)
            assertEquals(full, provider.load(3))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `class overview builders keep only top class edges`() {
        val methodA = MethodDescriptor(TypeDescriptor("com.example.A"), "a", emptyList(), TypeDescriptor("void"))
        val methodB = MethodDescriptor(TypeDescriptor("com.example.B"), "b", emptyList(), TypeDescriptor("void"))
        val methodC = MethodDescriptor(TypeDescriptor("com.example.C"), "c", emptyList(), TypeDescriptor("void"))
        val callAB = CallSiteNode(NodeId.next(), methodA, methodB, 1, null, emptyList())
        val callAB2 = CallSiteNode(NodeId.next(), methodA, methodB, 2, null, emptyList())
        val callBC = CallSiteNode(NodeId.next(), methodB, methodC, 3, null, emptyList())
        val callAA = CallSiteNode(NodeId.next(), methodA, methodA, 4, null, emptyList())

        val classBuilder = ClassOverviewBuilder()
        listOf(callAB, callAB2, callBC, callAA).forEach(classBuilder::add)
        val topCounts = classBuilder.topClassCounts(2)
        assertEquals(4, classBuilder.callSiteCount())
        assertEquals(listOf("com.example.A", "com.example.B"), topCounts.keys.toList())

        val edgeBuilder = ClassOverviewEdgeBuilder(topCounts.keys)
        listOf(callAB, callAB2, callBC, callAA).forEach(edgeBuilder::add)
        assertEquals(mapOf(ClassDependency("com.example.A", "com.example.B") to 2), edgeBuilder.build())
    }

    @Test
    fun `incoming typed edges on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-incoming-typed-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            // The local node has incoming DataFlowEdges (from param and constant)
            val locals = loaded.nodes(LocalVariable::class.java).toList()
            assertEquals(1, locals.size)
            val localId = locals[0].id

            val incomingDf = loaded.incoming(localId, DataFlowEdge::class.java).toList()
            assertEquals(2, incomingDf.size)

            // No incoming CallEdge to local
            val incomingCall = loaded.incoming(localId, CallEdge::class.java).toList()
            assertEquals(0, incomingCall.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `outgoing and incoming return empty for isolated node on loaded graph`() {
        val builder = DefaultGraph.Builder()
        val isolated = IntConstant(NodeId.next(), 999)
        builder.addNode(isolated)
        val graph = builder.build()

        val dir = Files.createTempDirectory("webgraph-isolated-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertEquals(0, loaded.outgoing(isolated.id).count())
            assertEquals(0, loaded.incoming(isolated.id).count())
            assertEquals(0, loaded.outgoing(isolated.id, DataFlowEdge::class.java).count())
            assertEquals(0, loaded.incoming(isolated.id, DataFlowEdge::class.java).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
