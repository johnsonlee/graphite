package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.RESULT_GRAPH_IDS_KEY
import io.johnsonlee.graphite.cypher.RESULT_METADATA_KEY
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Verifies call site trigram. */
class CallSiteTrigramTest : GraphStoreTestFixture() {

    @Test
    fun `large trigram posting sort preserves every key in ascending order`() {
        val size = (1 shl 20) + 1
        val postings =
            LongArray(size) { index ->
                if (index and 1 == 0) {
                    Long.MIN_VALUE + index
                } else {
                    Long.MAX_VALUE - index
                }
            }

        assertTrue(sortCallSiteTrigramPostings(postings))

        assertEquals(Long.MIN_VALUE, postings.first())
        assertEquals(Long.MAX_VALUE - 1, postings.last())
        assertTrue((1 until size).all { index -> postings[index - 1] <= postings[index] })
    }

    @Test
    fun `large trigram posting sort stays on bounded workers and stops when request is interrupted`() {
        val expectedWorkers = callSiteScanParallelism
        val postings = LongArray((1 shl 20) + 1) { index -> index.toLong() * -7_046_029_254_386_353_131L xor (index.toLong() shl 21) }
        val workersStarted = CountDownLatch(expectedWorkers)
        val releaseWorkers = CountDownLatch(1)
        val workerThreads = ConcurrentHashMap.newKeySet<String>()
        val failure = AtomicReference<Throwable>()
        val interruptedFlag = AtomicBoolean()
        val request =
            Thread {
                    try {
                        sortCallSiteTrigramPostings(postings) {
                            workerThreads += Thread.currentThread().name
                            workersStarted.countDown()
                            check(releaseWorkers.await(5, TimeUnit.SECONDS))
                        }
                    } catch (error: Throwable) {
                        failure.set(error)
                        interruptedFlag.set(Thread.currentThread().isInterrupted)
                    }
                }
                .apply {
                    isDaemon = true
                    name = "trigram-sort-interruption-test"
                }

        request.start()
        assertTrue(workersStarted.await(5, TimeUnit.SECONDS))
        assertEquals(expectedWorkers, workerThreads.size)
        assertTrue(workerThreads.all { name -> name.startsWith("graphite-callsite-scan-") })
        request.interrupt()
        releaseWorkers.countDown()
        request.join(500)
        val stoppedPromptly = !request.isAlive
        if (!stoppedPromptly) {
            request.interrupt()
            request.join(5_000)
        }

        assertTrue(stoppedPromptly)
        assertTrue(failure.get() is CancellationException, failure.get()?.stackTraceToString() ?: "No failure was captured")
        assertTrue(interruptedFlag.get())
    }

    @Test
    fun `trigram posting sort accounts temporary memory and declines when the index budget is full`() {
        val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        val accepted = checkNotNull(MappedCallSiteStringIndexMemoryBudget.tryReserve(0L))
        try {
            val postings = LongArray((1 shl 20) + 1) { index -> -index.toLong() }
            assertTrue(sortCallSiteTrigramPostings(postings, accepted))
            assertEquals(0L, accepted.bytes)
            assertTrue((1 until postings.size).all { index -> postings[index - 1] <= postings[index] })
        } finally {
            accepted.close()
        }
        assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())

        val available = MappedCallSiteStringIndexMemoryBudget.maxBytes - retainedBefore
        val saturated = checkNotNull(MappedCallSiteStringIndexMemoryBudget.tryReserve(available))
        try {
            val postings = LongArray((1 shl 20) + 1) { index -> index.toLong() }
            assertFalse(sortCallSiteTrigramPostings(postings, saturated))
            assertEquals(available, saturated.bytes)
        } finally {
            saturated.close()
        }
        assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())

        val small = longArrayOf(4L, Long.MIN_VALUE, 0L, Long.MAX_VALUE, -1L)
        assertTrue(sortCallSiteTrigramPostings(small))
        assertEquals(listOf(Long.MIN_VALUE, -1L, 0L, 4L, Long.MAX_VALUE), small.toList())
    }

    @Test
    fun `mapped CallSite trigram postings preserve Unicode matching and prune zero hits`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(256) { index ->
                        val callerType =
                            when (index) {
                                42 -> "example.аяа"
                                137 -> "example.İstanbulVoucher"
                                else -> "example.Feature$index"
                            }
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor(callerType), "create$index", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency$index"), "invoke$index", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-callsite-trigram-postings")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                fun matchingIds(predicate: StringPropertyPredicate): List<Int> =
                    loaded
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate))
                        .orEmpty()
                        .map { it.id.value }
                        .toList()

                assertEquals(
                    listOf(137),
                    matchingIds(
                        StringPropertyPredicate(
                            "caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "i\u0307stanbul")))
                assertEquals(listOf(137), matchingIds(StringPropertyPredicate("caller_class", null, StringMatchMode.ENDS_WITH, "Voucher")))
                assertEquals(emptyList(), matchingIds(StringPropertyPredicate("caller_class", null, StringMatchMode.ENDS_WITH, "voucher")))
                // "аяа" and "баа" have the same base-31 trigram hash; full verification is mandatory.
                assertEquals(
                    emptyList(),
                    matchingIds(StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "баа")))
                assertTrue(loaded.isCallSiteTrigramIndexInitialized())

                var zeroHitWork = 0
                val zeroHits =
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(
                                StringPropertyPredicate(
                                    "caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "definitely-absent")),
                            Int.MAX_VALUE,
                            GraphWorkConsumer { zeroHitWork++ })
                        .orEmpty()
                        .toList()
                assertEquals(emptyList(), zeroHits)
                assertTrue(zeroHitWork in 1 until 32)
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `mapped CallSite index reuses verified trigram matches within its memory reservation`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(256) { index ->
                        val marker = if (index == 137 || index == 138) "Voucher" else "Feature"
                        val propertySuffix = if (index == 138) 137 else index
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(
                                    TypeDescriptor("example.${marker}Caller$propertySuffix"),
                                    "create$propertySuffix",
                                    emptyList(),
                                    returnType),
                                MethodDescriptor(
                                    TypeDescriptor("example.Dependency$propertySuffix"), "invoke$propertySuffix", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val predicates =
            listOf("caller_class", "caller_name", "callee_class", "callee_name").map { property ->
                StringPropertyPredicate(property, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "voucher")
            }
        val dir = Files.createTempDirectory("webgraph-callsite-match-cache")
        val budgetBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val featurePredicates = predicates.map { predicate -> predicate.copy(expected = "feature") }
                loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, featurePredicates, 201).orEmpty().take(1).toList()
                val retainedBeforePartial = loaded.callSiteStringIndexBytes()

                var partialWork = 0L
                val partial =
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java, featurePredicates, 200, GraphWorkConsumer { partialWork++ })
                        .orEmpty()
                        .take(1)
                        .map { it.id.value }
                        .toList()
                assertEquals(retainedBeforePartial, loaded.callSiteStringIndexBytes())

                var firstWork = 0L
                val first =
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java, featurePredicates, 200, GraphWorkConsumer { firstWork++ })
                        .orEmpty()
                        .map { it.id.value }
                        .toList()
                val retainedAfterFirst = loaded.callSiteStringIndexBytes()

                var repeatedWork = 0L
                val repeated =
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java, featurePredicates, 200, GraphWorkConsumer { repeatedWork++ })
                        .orEmpty()
                        .map { it.id.value }
                        .toList()

                assertEquals(200, first.size)
                assertEquals(first.size, first.toSet().size)
                assertTrue(first.none { it == 137 || it == 138 })
                assertTrue(first.all { it in 0 until 256 })
                assertEquals(first.take(1), partial)
                assertEquals(first, repeated)
                assertTrue(partialWork < firstWork)
                assertTrue(firstWork > repeatedWork)
                assertEquals(first.size.toLong(), repeatedWork)
                assertEquals(retainedAfterFirst, loaded.callSiteStringIndexBytes())

                repeat(40) { cacheIndex ->
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java, listOf(predicates.first().copy(expected = "cache-miss-$cacheIndex")))
                        .orEmpty()
                        .toList()
                }

                val projectedProperties = listOf("caller_class", "caller_name", "callee_class", "callee_name")
                val storedProjection =
                    assertNotNull(loaded.projectStringPropertyDisjunction(CallSiteNode::class.java, predicates, projectedProperties, 200))
                assertFailsWith<UnsupportedOperationException> {
                    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") (storedProjection as java.util.List<*>).clear()
                }
                assertFailsWith<UnsupportedOperationException> {
                    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    (storedProjection.first().values as java.util.List<String?>).set(0, "poisoned")
                }
                val repeatedProjection =
                    assertNotNull(loaded.projectStringPropertyDisjunction(CallSiteNode::class.java, predicates, projectedProperties, 200))
                assertSame(storedProjection, repeatedProjection)
                assertEquals("example.VoucherCaller137", repeatedProjection.first().values.first())

                val executor = CrossGraphCypherExecutor(listOf(CypherGraph("mapped", loaded)))
                val projectionQuery =
                    "MATCH (n) WHERE " +
                        "toLower(n.caller_class) CONTAINS 'voucher' OR " +
                        "toLower(n.caller_name) CONTAINS 'voucher' OR " +
                        "toLower(n.callee_class) CONTAINS 'voucher' OR " +
                        "toLower(n.callee_name) CONTAINS 'voucher' " +
                        "RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name LIMIT 200"
                val projected = executor.execute(projectionQuery)
                assertEquals(2, projected.rows.size)
                assertEquals(projected.rows[0], projected.rows[1])
                assertEquals("example.VoucherCaller137", projected.rows[0]["n.caller_class"])
                assertEquals("create137", projected.rows[0]["n.caller_name"])
                assertFailsWith<UnsupportedOperationException> {
                    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    (projected.rows[0] as java.util.Map<String, Any?>).put("n.caller_name", "mutated")
                }
                assertEquals(1, executor.execute("$projectionQuery UNION $projectionQuery").rows.size)
                assertEquals(4, executor.execute("$projectionQuery UNION ALL $projectionQuery").rows.size)
                val crossGraphExecutor = CrossGraphCypherExecutor(listOf(CypherGraph("left", loaded), CypherGraph("right", loaded)))
                val leftQuery = projectionQuery.replace("WHERE ", "WHERE graphId(n) = 'left' AND ")
                val rightQuery = projectionQuery.replace("WHERE ", "WHERE graphId(n) = 'right' AND ")
                val crossGraphUnion = crossGraphExecutor.execute("$leftQuery UNION $rightQuery")
                assertEquals(1, crossGraphUnion.rows.size)
                @Suppress("UNCHECKED_CAST") val metadata = crossGraphUnion.rows.single().getValue(RESULT_METADATA_KEY) as Map<String, Any?>
                assertEquals(listOf("left", "right"), metadata[RESULT_GRAPH_IDS_KEY])

                loaded.clearStringPropertyIndexes()
                assertEquals(budgetBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
        assertEquals(budgetBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
    }
}
