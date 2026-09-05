package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.SerialGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MappedCallSiteStringIndexViewTest {

    @Test
    fun `serial persisted view returns exact tuples and reopens after clear`() = withPreparedGraph(
        DefaultGraph.Builder()
            .addNode(callSite(0, "example.TargetCaller", "alpha", "example.Dependency", "invoke"))
            .addNode(callSite(1, "example.OtherCaller", "beta", "example.Dependency", "dispatch"))
            .addNode(callSite(2, "example.FinalCaller", "gamma", "example.Dependency", "targetInvoke"))
            .build()
    ) { dir ->
        (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
            val selectedValues = linkedSetOf(
                listOf("example.TargetCaller", "alpha", "example.Dependency", "invoke"),
                listOf("example.OtherCaller", "beta", "example.Dependency", "dispatch"),
                listOf("example.FinalCaller", "gamma", "example.Dependency", "targetInvoke"),
                listOf("missing.Caller", "missing", "missing.Dependency", "missing")
            )
            val expected = listOf(selectedValues.elementAt(0), selectedValues.elementAt(2))
            val work = AtomicLong()

            val selected = loaded.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                targetPredicates(),
                CALL_SITE_PROPERTIES,
                limit = 10,
                selectedValues = selectedValues,
                workConsumer = SerialGraphWorkBatchConsumer(work::addAndGet)
            )

            assertEquals(expected, selected?.map { row -> row.values })
            assertTrue(work.get() > 0L)
            assertTrue(loaded.isMappedCallSiteStringIndexViewInitialized())
            assertFalse(loaded.isCallSiteStringIndexInitialized())
            assertEquals(
                listOf(0, 2),
                loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    targetPredicates(),
                    limit = Int.MAX_VALUE,
                    workConsumer = SerialGraphWorkBatchConsumer { }
                ).orEmpty().map { node -> node.id.value }.toList()
            )

            loaded.clearStringPropertyIndexes()
            assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
            val reopened = loaded.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                targetPredicates(),
                CALL_SITE_PROPERTIES,
                limit = 10,
                selectedValues = selectedValues,
                workConsumer = SerialGraphWorkBatchConsumer { }
            )
            assertEquals(expected, reopened?.map { row -> row.values })
            assertTrue(loaded.isMappedCallSiteStringIndexViewInitialized())
            assertFalse(loaded.isCallSiteStringIndexInitialized())
        }
    }

    @Test
    fun `corrupt persisted view falls back to exact bounded raw results without rewriting`() = withPreparedGraph(
        DefaultGraph.Builder()
            .addNode(callSite(0, "example.TargetCaller", "alpha", "example.Dependency", "invoke"))
            .addNode(callSite(1, "example.OtherCaller", "beta", "example.Dependency", "dispatch"))
            .build()
    ) { dir ->
        val indexFile = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
        val corrupted = Files.readAllBytes(indexFile).apply {
            this[size / 2] = (this[size / 2].toInt() xor 1).toByte()
        }
        Files.write(indexFile, corrupted)

        (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
            val ids = loaded.nodesByStringPropertyDisjunction(
                CallSiteNode::class.java,
                targetPredicates(),
                limit = Int.MAX_VALUE,
                workConsumer = SerialGraphWorkBatchConsumer { }
            ).orEmpty().map { node -> node.id.value }.toList()

            assertEquals(listOf(0), ids)
            assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
            assertTrue(loaded.isMappedCallSiteStringIndexViewUnavailable())
            assertFalse(loaded.isCallSiteStringIndexInitialized())
            assertEquals(
                listOf(listOf("example.TargetCaller", "alpha", "example.Dependency", "invoke")),
                loaded.distinctStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    targetPredicates(),
                    CALL_SITE_PROPERTIES,
                    limit = 10,
                    selectedValues = setOf(
                        listOf("example.TargetCaller", "alpha", "example.Dependency", "invoke"),
                        listOf("example.OtherCaller", "beta", "example.Dependency", "dispatch")
                    ),
                    workConsumer = SerialGraphWorkBatchConsumer { }
                )?.map { row -> row.values }
            )
            assertContentEquals(corrupted, Files.readAllBytes(indexFile))
        }
    }

    @Test
    fun `missing and foreign persisted views keep the authoritative raw graph`() {
        fun graphWithTargetAt(targetNodeId: Int): Graph = DefaultGraph.Builder().apply {
            repeat(2) { nodeId ->
                addNode(
                    callSite(
                        nodeId,
                        if (nodeId == targetNodeId) "example.TargetCaller" else "example.OtherCaller",
                        "call",
                        "example.Dependency",
                        "invoke"
                    )
                )
            }
        }.build()

        val graphADir = Files.createTempDirectory("mapped-callsite-index-view-owner-a")
        val graphBDir = Files.createTempDirectory("mapped-callsite-index-view-owner-b")
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        try {
            GraphStore.save(graphWithTargetAt(0), graphADir, prepareCallSiteStringIndex = true)
            GraphStore.save(graphWithTargetAt(1), graphBDir, prepareCallSiteStringIndex = true)
            System.clearProperty(property)
            val graphAIndex = graphADir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
            val graphBIndex = graphBDir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
            val foreignIndex = Files.readAllBytes(graphAIndex)

            Files.delete(graphAIndex)
            (GraphStore.loadMapped(graphADir) as MappedWebGraphBackedGraph).use { loaded ->
                assertEquals(listOf(0), loaded.serialTargetNodeIds())
                assertFalse(Files.exists(graphAIndex))
                assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
                assertFalse(loaded.isMappedCallSiteStringIndexViewUnavailable())
                assertFalse(loaded.isCallSiteStringIndexInitialized())
            }

            Files.write(graphBIndex, foreignIndex)
            (GraphStore.loadMapped(graphBDir) as MappedWebGraphBackedGraph).use { loaded ->
                assertEquals(listOf(1), loaded.serialTargetNodeIds())
                assertContentEquals(foreignIndex, Files.readAllBytes(graphBIndex))
                assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
                assertTrue(loaded.isMappedCallSiteStringIndexViewUnavailable())
                assertFalse(loaded.isCallSiteStringIndexInitialized())
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            graphADir.toFile().deleteRecursively()
            graphBDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid posting order is rejected before mapped rows are exposed`() = withPreparedGraph(
        DefaultGraph.Builder()
            .addNode(callSite(0, "example.TargetCaller", "call", "example.Dependency", "invoke"))
            .addNode(callSite(1, "example.TargetCaller", "call", "example.Dependency", "invoke"))
            .build()
    ) { dir ->
        val indexFile = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
        val header = ByteBuffer.wrap(Files.readAllBytes(indexFile)).order(ByteOrder.BIG_ENDIAN)
        assertEquals(CALL_SITE_STRING_INDEX_MAGIC, header.int)
        assertEquals(CALL_SITE_STRING_INDEX_VERSION, header.int)
        val stringCount = header.int
        val callSiteCount = header.int
        val contentIdentity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
        header.get(contentIdentity)
        val strings = StringTable.load(dir)
        val view = assertNotNull(
            MappedCallSiteStringIndexView.load(
                indexFile,
                stringCount,
                callSiteCount,
                contentIdentity,
                strings,
                nodeIdCapacity = 2,
                nodeAccessor = object : MappedCallSiteNodeAccessor {
                    override fun encounterOrder(nodeId: Int): Long = -nodeId.toLong()

                    override fun tupleMatches(
                        nodeId: Int,
                        propertyIndexes: IntArray,
                        stringIds: IntArray
                    ): Boolean = true
                },
                workConsumer = null
            )
        )
        view.use {
            val predicates = listOf(
                StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")
            )
            val exactMatches = assertNotNull(view.exactMatchingStringIds(predicates, null))
            assertNull(view.matchingNodeIds(predicates, exactMatches, null))
            assertNull(
                view.firstNodesMatchingTuples(
                    intArrayOf(0, 1, 2, 3),
                    arrayOf(
                        intArrayOf(
                            strings.findId("example.TargetCaller"),
                            strings.findId("call"),
                            strings.findId("example.Dependency"),
                            strings.findId("invoke")
                        )
                    ),
                    null
                )
            )
        }
    }

    @Test
    fun `interrupted view validation is unpublished and can be retried`() = withPreparedGraph(
        DefaultGraph.Builder().apply {
            repeat(4_096) { index ->
                addNode(
                    callSite(
                        index,
                        "example.Caller$index",
                        "call$index",
                        "example.Dependency$index",
                        "invoke$index"
                    )
                )
            }
        }.build()
    ) { dir ->
        (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
            val validationReached = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val interrupted = AtomicBoolean()
            val request = Thread {
                try {
                    loaded.nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(
                            StringPropertyPredicate(
                                "caller_class",
                                StringValueTransform.LOWERCASE,
                                StringMatchMode.CONTAINS,
                                "never-present"
                            )
                        ),
                        limit = Int.MAX_VALUE,
                        workConsumer = SerialGraphWorkBatchConsumer { workUnits ->
                            if (workUnits >= 4_096L && validationReached.count > 0L) {
                                validationReached.countDown()
                                while (!Thread.currentThread().isInterrupted) Thread.onSpinWait()
                            }
                        }
                    ).orEmpty().toList()
                    failure.set(AssertionError("Interrupted validation completed normally"))
                } catch (error: Throwable) {
                    failure.set(error)
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }

            request.start()
            assertTrue(validationReached.await(5, TimeUnit.SECONDS))
            request.interrupt()
            request.join(5_000)

            assertFalse(request.isAlive)
            assertTrue(failure.get() is CancellationException)
            assertTrue(interrupted.get())
            assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
            assertFalse(loaded.isMappedCallSiteStringIndexViewUnavailable())
            assertEquals(
                emptyList(),
                loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "never-present"
                            )
                        ),
                    limit = Int.MAX_VALUE,
                    workConsumer = SerialGraphWorkBatchConsumer { }
                ).orEmpty().map { node -> node.id.value }.toList()
            )
            assertTrue(loaded.isMappedCallSiteStringIndexViewInitialized())
        }
    }

    @Test
    fun `view validation propagates graph work consumer failures`() = withPreparedGraph(
        DefaultGraph.Builder()
            .addNode(callSite(0, "example.TargetCaller", "alpha", "example.Dependency", "invoke"))
            .build()
    ) { dir ->
        val indexFile = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
        val header = ByteBuffer.wrap(Files.readAllBytes(indexFile)).order(ByteOrder.BIG_ENDIAN)
        assertEquals(CALL_SITE_STRING_INDEX_MAGIC, header.int)
        assertEquals(CALL_SITE_STRING_INDEX_VERSION, header.int)
        val stringCount = header.int
        val callSiteCount = header.int
        val contentIdentity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
        header.get(contentIdentity)
        val expected = IllegalArgumentException("consumer stopped validation")

        val thrown = assertFailsWith<IllegalArgumentException> {
            MappedCallSiteStringIndexView.load(
                indexFile,
                stringCount,
                callSiteCount,
                contentIdentity,
                StringTable.load(dir),
                nodeIdCapacity = 1,
                nodeAccessor = object : MappedCallSiteNodeAccessor {
                    override fun encounterOrder(nodeId: Int): Long = nodeId.toLong()

                    override fun tupleMatches(
                        nodeId: Int,
                        propertyIndexes: IntArray,
                        stringIds: IntArray
                    ): Boolean = true
                },
                workConsumer = SerialGraphWorkBatchConsumer { throw expected }
            )
        }

        assertSame(expected, thrown)
    }

    @Test
    fun `prepared heap index remains authoritative for serial distinct projection`() = withPreparedGraph(
        DefaultGraph.Builder()
            .addNode(callSite(0, "example.TargetCaller", "alpha", "example.Dependency", "invoke"))
            .addNode(callSite(1, "example.OtherCaller", "beta", "example.Dependency", "dispatch"))
            .build()
    ) { dir ->
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        System.setProperty(property, "true")
        try {
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
                assertTrue(loaded.isCallSiteStringIndexInitialized())
                assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())

                val rows = loaded.distinctStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    targetPredicates(),
                    CALL_SITE_PROPERTIES,
                    limit = 10,
                    selectedValues = null,
                    workConsumer = SerialGraphWorkBatchConsumer { }
                )

                assertEquals(
                    listOf(listOf("example.TargetCaller", "alpha", "example.Dependency", "invoke")),
                    rows?.map { row -> row.values }
                )
                assertTrue(loaded.isCallSiteStringIndexInitialized())
                assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
            }
        } finally {
            System.clearProperty(property)
        }
    }

    @Test
    fun `sparse mapped distinct projection preserves graph id null and dense limit uses raw path`() =
        withPreparedGraph(
            DefaultGraph.Builder()
                .addNode(callSite(0, "example.TargetCaller", "alpha", "example.Dependency", "invoke"))
                .addNode(callSite(1, "example.OtherCaller", "beta", "example.Dependency", "dispatch"))
                .addNode(callSite(2, "example.TargetCallerTwo", "gamma", "example.Dependency", "invoke"))
                .build()
        ) { dir ->
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
                val sparse = loaded.distinctStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    targetPredicates(),
                    listOf("caller_class", "caller_name", "callee_name", "graphId"),
                    limit = 10,
                    selectedValues = null,
                    workConsumer = SerialGraphWorkBatchConsumer { }
                )

                assertEquals(
                    listOf(
                        listOf("example.TargetCaller", "alpha", "invoke", null),
                        listOf("example.TargetCallerTwo", "gamma", "invoke", null)
                    ),
                    sparse?.map { row -> row.values }
                )
                assertSame(sparse?.first()?.values?.get(2), sparse?.last()?.values?.get(2))
                assertTrue(loaded.isMappedCallSiteStringIndexViewInitialized())
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(1L, loaded.callSiteStringIndexLookupCount())
                assertEquals(0L, loaded.callSiteSerialScanCount())

                loaded.resetCallSiteScanMetrics()
                val denseLimit = loaded.distinctStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    targetPredicates(),
                    listOf("caller_class", "caller_name", "graphId"),
                    limit = 1,
                    selectedValues = null,
                    workConsumer = SerialGraphWorkBatchConsumer { }
                )

                assertEquals(
                    listOf(listOf("example.TargetCaller", "alpha", null)),
                    denseLimit?.map { row -> row.values }
                )
                assertEquals(0L, loaded.callSiteStringIndexLookupCount())
                assertEquals(1L, loaded.callSiteSerialScanCount())
            }
        }

    @Test
    fun `three character bounded predicate keeps the v247 raw path`() = withPreparedGraph(
        DefaultGraph.Builder()
            .addNode(callSite(0, "example.TargetCaller", "alpha", "example.Dependency", "invoke"))
            .addNode(callSite(1, "example.OtherCaller", "beta", "example.Dependency", "dispatch"))
            .build()
    ) { dir ->
        (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
            val result = loaded.nodesByStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(
                    StringPropertyPredicate(
                        "caller_class",
                        StringValueTransform.LOWERCASE,
                        StringMatchMode.CONTAINS,
                        "tar"
                    )
                ),
                limit = 1,
                workConsumer = SerialGraphWorkBatchConsumer { }
            ).orEmpty().map { node -> node.id.value }.toList()

            assertEquals(listOf(0), result)
            assertEquals(1L, loaded.callSiteSerialScanCount())
            assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
            assertFalse(loaded.isCallSiteStringIndexInitialized())
        }
    }

    @Test
    fun `empty distinct requests do no persisted index work`() = withPreparedGraph(
        DefaultGraph.Builder()
            .addNode(callSite(0, "example.TargetCaller", "alpha", "example.Dependency", "invoke"))
            .build()
    ) { dir ->
        (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
            val rejectingConsumer = SerialGraphWorkBatchConsumer {
                error("empty request must not consume graph work")
            }
            assertEquals(
                emptyList(),
                loaded.distinctStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    targetPredicates(),
                    CALL_SITE_PROPERTIES,
                    limit = 0,
                    selectedValues = null,
                    workConsumer = rejectingConsumer
                )
            )
            assertEquals(
                emptyList(),
                loaded.distinctStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    targetPredicates(),
                    CALL_SITE_PROPERTIES,
                    limit = 10,
                    selectedValues = emptySet(),
                    workConsumer = rejectingConsumer
                )
            )
            assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
            assertFalse(loaded.isCallSiteStringIndexInitialized())
        }
    }

    @Test
    fun `mapped matching sequence supports repeated and concurrent iteration`() = withPreparedGraph(
        DefaultGraph.Builder()
            .addNode(callSite(0, "example.TargetCaller", "alpha", "example.Dependency", "invoke"))
            .addNode(callSite(1, "example.TargetCaller", "beta", "example.Dependency", "dispatch"))
            .build()
    ) { dir ->
        val indexFile = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
        val header = ByteBuffer.wrap(Files.readAllBytes(indexFile)).order(ByteOrder.BIG_ENDIAN)
        assertEquals(CALL_SITE_STRING_INDEX_MAGIC, header.int)
        assertEquals(CALL_SITE_STRING_INDEX_VERSION, header.int)
        val stringCount = header.int
        val callSiteCount = header.int
        val contentIdentity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
        header.get(contentIdentity)
        val view = assertNotNull(
            MappedCallSiteStringIndexView.load(
                indexFile,
                stringCount,
                callSiteCount,
                contentIdentity,
                StringTable.load(dir),
                nodeIdCapacity = 2,
                nodeAccessor = object : MappedCallSiteNodeAccessor {
                    override fun encounterOrder(nodeId: Int): Long = nodeId.toLong()

                    override fun tupleMatches(
                        nodeId: Int,
                        propertyIndexes: IntArray,
                        stringIds: IntArray
                    ): Boolean = true
                },
                workConsumer = null
            )
        )
        view.use {
            val predicates = listOf(
                StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")
            )
            val exactMatches = assertNotNull(view.exactMatchingStringIds(predicates, null))
            val matches = assertNotNull(view.matchingNodeIds(predicates, exactMatches, null))
            assertEquals(listOf(0, 1), matches.toList())
            assertEquals(listOf(0, 1), matches.toList())

            val first = AtomicReference<List<Int>>()
            val second = AtomicReference<List<Int>>()
            val firstThread = Thread { first.set(matches.toList()) }
            val secondThread = Thread { second.set(matches.toList()) }
            firstThread.start()
            secondThread.start()
            firstThread.join(5_000)
            secondThread.join(5_000)
            assertFalse(firstThread.isAlive)
            assertFalse(secondThread.isAlive)
            assertEquals(listOf(0, 1), first.get())
            assertEquals(listOf(0, 1), second.get())
        }
    }

    @Test
    fun `unsupported predicates bypass persisted view validation`() = withPreparedGraph(
        DefaultGraph.Builder()
            .addNode(callSite(0, "example.TargetCaller", "alpha", "example.Dependency", "invoke"))
            .addNode(callSite(1, "example.OtherCaller", "beta", "example.Dependency", "dispatch"))
            .addNode(callSite(2, "пример.TargetCaller", "gamma", "example.Dependency", "finish"))
            .build()
    ) { dir ->
        (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
            fun assertRawResult(predicate: StringPropertyPredicate, expectedId: Int) {
                val work = AtomicLong()
                val result = loaded.nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(predicate),
                    limit = 1,
                    workConsumer = SerialGraphWorkBatchConsumer { units ->
                        check(work.addAndGet(units) <= 10L) { "unsupported predicate validated the sidecar" }
                    }
                ).orEmpty().map { node -> node.id.value }.toList()
                assertEquals(listOf(expectedId), result)
                assertTrue(work.get() in 1L..3L)
                assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
                assertFalse(loaded.isCallSiteStringIndexInitialized())
            }

            assertRawResult(
                StringPropertyPredicate(
                    "caller_class",
                    null,
                    StringMatchMode.EQUALS,
                    "example.TargetCaller"
                ),
                0
            )
            assertRawResult(
                StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Ta"),
                0
            )
            assertRawResult(
                StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "пример"),
                2
            )
        }
    }

    private fun withPreparedGraph(graph: Graph, action: (Path) -> Unit) {
        val dir = Files.createTempDirectory("mapped-callsite-index-view")
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        try {
            GraphStore.save(graph, dir, prepareCallSiteStringIndex = true)
            System.clearProperty(property)
            action(dir)
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.toFile().deleteRecursively()
        }
    }

    private fun targetPredicates(): List<StringPropertyPredicate> = CALL_SITE_PROPERTIES.map { property ->
        StringPropertyPredicate(
            property,
            StringValueTransform.LOWERCASE,
            StringMatchMode.CONTAINS,
            "target"
        )
    }

    private fun MappedWebGraphBackedGraph.serialTargetNodeIds(): List<Int> =
        nodesByStringPropertyDisjunction(
            CallSiteNode::class.java,
            listOf(
                StringPropertyPredicate(
                    "caller_class",
                    StringValueTransform.LOWERCASE,
                    StringMatchMode.CONTAINS,
                    "target"
                )
            ),
            limit = 1,
            workConsumer = SerialGraphWorkBatchConsumer { }
        ).orEmpty().map { node -> node.id.value }.toList()

    private fun callSite(
        id: Int,
        callerClass: String,
        callerName: String,
        calleeClass: String,
        calleeName: String
    ): CallSiteNode = CallSiteNode(
        NodeId(id),
        MethodDescriptor(TypeDescriptor(callerClass), callerName, emptyList(), RETURN_TYPE),
        MethodDescriptor(TypeDescriptor(calleeClass), calleeName, emptyList(), RETURN_TYPE),
        id,
        null,
        emptyList()
    )

    companion object {
        private val RETURN_TYPE = TypeDescriptor("void")
        private val CALL_SITE_PROPERTIES = listOf(
            "caller_class",
            "caller_name",
            "callee_class",
            "callee_name"
        )
    }
}
