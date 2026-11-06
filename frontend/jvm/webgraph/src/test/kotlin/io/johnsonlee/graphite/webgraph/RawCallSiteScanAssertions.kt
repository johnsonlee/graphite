package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SerialGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun assertRawCallSiteScans(loaded: MappedWebGraphBackedGraph) {
    assertSerialScans(loaded)
    assertSerialProjection(loaded)
    assertParallelWorkers(loaded)
    assertSplitWorkers(loaded)
    assertSplitProjection(loaded)
    assertProjectionFailures(loaded)
    assertQualifiedQuery(loaded)
    assertParallelFailures(loaded)
}

private fun assertSerialScans(loaded: MappedWebGraphBackedGraph) {
    val serialWork = AtomicLong()
    val serialNodes =
        loaded
            .nodesByStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")),
                limit = 1,
                workConsumer = SerialGraphWorkBatchConsumer(serialWork::addAndGet))
            .orEmpty()
    assertEquals(0L, serialWork.get())
    val serialIds = serialNodes.map { it.id.value }.toList()
    assertEquals(listOf(0), serialIds)
    assertEquals(1L, serialWork.get())
    assertFalse(loaded.isCallSiteStringIndexInitialized())

    val serialLateWork = AtomicLong()
    val serialLateIds =
        loaded
            .nodesByStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(
                    StringPropertyPredicate(
                        "caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "definitely-absent"),
                    StringPropertyPredicate("caller_name", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "ünicodecall")),
                limit = 1,
                workConsumer = SerialGraphWorkBatchConsumer(serialLateWork::addAndGet))
            .orEmpty()
            .map { it.id.value }
            .toList()
    assertEquals(listOf(32_767), serialLateIds)
    assertTrue(serialLateWork.get() in 2L..32_768L)
    assertFalse(loaded.isCallSiteStringIndexInitialized())

    assertSerialCalleeAndCancellation(loaded)
}

private fun assertSerialCalleeAndCancellation(loaded: MappedWebGraphBackedGraph) {
    val serialCalleeIds =
        loaded
            .nodesByStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(
                    StringPropertyPredicate("callee_class", null, StringMatchMode.CONTAINS, "definitely-missing-callee"),
                    StringPropertyPredicate("callee_name", null, StringMatchMode.ENDS_WITH, "invoke32767")),
                limit = 1,
                workConsumer = SerialGraphWorkBatchConsumer {})
            .orEmpty()
            .map { it.id.value }
            .toList()
    assertEquals(listOf(32_767), serialCalleeIds)
    assertFalse(loaded.isCallSiteStringIndexInitialized())

    Thread.currentThread().interrupt()
    try {
        assertFailsWith<CancellationException> {
            loaded
                .nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")),
                    limit = 1,
                    workConsumer = SerialGraphWorkBatchConsumer {})
                .orEmpty()
                .toList()
        }
    } finally {
        Thread.interrupted()
    }

    Thread.currentThread().interrupt()
    try {
        assertFailsWith<CancellationException> {
            loaded.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")),
                projectedProperties = listOf("caller_class"),
                limit = 1,
                workConsumer = SerialGraphWorkBatchConsumer {})
        }
    } finally {
        Thread.interrupted()
    }

    val serialMissWork = AtomicLong()
    val serialMissIds =
        loaded
            .nodesByStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(
                    StringPropertyPredicate(
                        "caller_name", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "definitely-missing-serial-value")),
                limit = 1,
                workConsumer = SerialGraphWorkBatchConsumer(serialMissWork::addAndGet))
            .orEmpty()
            .map { it.id.value }
            .toList()
    assertTrue(serialMissIds.isEmpty())
    assertEquals(32_768L, serialMissWork.get())
    assertFalse(loaded.isCallSiteStringIndexInitialized())
}

private fun assertSerialProjection(loaded: MappedWebGraphBackedGraph) {
    val rawProjectionWork = AtomicLong()
    val rawProjection =
        loaded.distinctStringPropertyDisjunction(
            CallSiteNode::class.java,
            listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")),
            projectedProperties = listOf("caller_class", "graphId"),
            limit = 2,
            workConsumer = SerialGraphWorkBatchConsumer(rawProjectionWork::addAndGet))
    val expectedRawProjection =
        loaded
            .nodes(CallSiteNode::class.java)
            .filter { node -> "Target" in node.caller.declaringClass.className }
            .take(2)
            .map { node -> listOf(node.caller.declaringClass.className, null) }
            .toList()
    assertEquals(expectedRawProjection, rawProjection?.map { row -> row.values })
    assertTrue(rawProjectionWork.get() in 1L..32_768L)
    assertFalse(loaded.isCallSiteStringIndexInitialized())
    assertEquals(0L, loaded.callSiteStringPreflightCount())
    assertTrue(
        loaded
            .distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "definitely-missing-preflight-value")),
                projectedProperties = listOf("caller_class"),
                limit = 2,
                workConsumer = SerialGraphWorkBatchConsumer {})
            .orEmpty()
            .isEmpty())
    assertEquals(1L, loaded.callSiteStringPreflightCount())
}

private fun assertParallelWorkers(loaded: MappedWebGraphBackedGraph) {
    val workerThreads = ConcurrentHashMap.newKeySet<String>()
    val expectedParallelWorkers = minOf(callSiteScanParallelism, 32_768)
    val parallelWorkersStarted = CountDownLatch(expectedParallelWorkers)
    val ids =
        loaded
            .nodesByStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")),
                limit = 1,
                workConsumer =
                    ParallelGraphWorkBatchConsumer { workUnits ->
                        if (workerThreads.add(Thread.currentThread().name)) {
                            parallelWorkersStarted.countDown()
                        }
                        check(parallelWorkersStarted.await(5, TimeUnit.SECONDS))
                    })
            .orEmpty()
            .map { it.id.value }
            .toList()

    assertEquals(listOf(0), ids)
    assertFalse(loaded.isCallSiteStringIndexInitialized())
    assertTrue(workerThreads.all { thread -> thread.startsWith("graphite-callsite-scan-") })
    assertEquals(1L, loaded.callSiteParallelScanCount())
    if (expectedParallelWorkers > 1) {
        assertEquals(expectedParallelWorkers, workerThreads.size)
        assertTrue(loaded.callSiteScanPeakActiveWorkers() > 1)
    }
}

private fun assertSplitWorkers(loaded: MappedWebGraphBackedGraph) {
    loaded.resetCallSiteScanMetrics()
    assertEquals(0L, loaded.callSiteStringPreflightCount())
    val splitWorkerThreads = ConcurrentHashMap.newKeySet<String>()
    val splitWorkersStarted = CountDownLatch(2)
    val splitIds =
        loaded
            .nodesByStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")),
                limit = 1,
                workConsumer =
                    object : SplitGraphWorkBatchConsumer {
                        override val segmentWorkerCount: Int = 1

                        override fun consume(workUnits: Long) {
                            splitWorkerThreads += Thread.currentThread().name
                            splitWorkersStarted.countDown()
                            check(splitWorkersStarted.await(5, TimeUnit.SECONDS))
                        }
                    })
            .orEmpty()
            .map { it.id.value }
            .toList()

    assertEquals(listOf(0), splitIds)
    assertTrue(splitWorkerThreads.any { thread -> thread.startsWith("graphite-callsite-segment-") })
    assertTrue(splitWorkerThreads.any { thread -> !thread.startsWith("graphite-callsite-segment-") })
    assertEquals(2, loaded.callSiteScanPeakActiveWorkers())
    assertEquals(0, loaded.callSiteScanActiveWorkers())
    assertFalse(loaded.isCallSiteStringIndexInitialized())
}

private fun assertSplitProjection(loaded: MappedWebGraphBackedGraph) {
    val expectedRawProjection =
        loaded
            .nodes(CallSiteNode::class.java)
            .filter { node -> "Target" in node.caller.declaringClass.className }
            .take(2)
            .map { node -> listOf(node.caller.declaringClass.className, null) }
            .toList()
    loaded.resetCallSiteScanMetrics()
    val splitProjectionThreads = ConcurrentHashMap.newKeySet<String>()
    val splitProjectionWorkersStarted = CountDownLatch(2)
    val splitProjection =
        loaded.distinctStringPropertyDisjunction(
            CallSiteNode::class.java,
            listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")),
            projectedProperties = listOf("caller_class", "graphId"),
            limit = 2,
            workConsumer =
                object : SplitGraphWorkBatchConsumer {
                    override val segmentWorkerCount: Int = 1

                    override fun consume(workUnits: Long) {
                        splitProjectionThreads += Thread.currentThread().name
                        splitProjectionWorkersStarted.countDown()
                        check(splitProjectionWorkersStarted.await(5, TimeUnit.SECONDS))
                    }
                })
    assertEquals(expectedRawProjection, splitProjection?.map { row -> row.values })
    assertTrue(splitProjectionThreads.any { thread -> thread.startsWith("graphite-callsite-segment-") })
    assertTrue(splitProjectionThreads.any { thread -> !thread.startsWith("graphite-callsite-segment-") })
    assertEquals(1L, loaded.callSiteParallelScanCount())
    assertEquals(2, loaded.callSiteScanPeakActiveWorkers())
    assertEquals(0, loaded.callSiteScanActiveWorkers())
    assertFalse(loaded.isCallSiteStringIndexInitialized())

    val incompleteSelectedProjection =
        loaded.distinctStringPropertyDisjunction(
            CallSiteNode::class.java,
            listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")),
            projectedProperties = listOf("caller_class"),
            limit = 2,
            selectedValues = setOf(listOf("example.TargetCaller0"), listOf("example.TargetCallerMissing")),
            workConsumer =
                object : SplitGraphWorkBatchConsumer {
                    override val segmentWorkerCount: Int = 1

                    override fun consume(workUnits: Long) = Unit
                })
    assertEquals(listOf(listOf("example.TargetCaller0")), incompleteSelectedProjection?.map { row -> row.values })

    val inlineOnlyProjection =
        loaded.distinctStringPropertyDisjunction(
            CallSiteNode::class.java,
            listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")),
            projectedProperties = listOf("caller_class"),
            limit = 2,
            workConsumer =
                object : SplitGraphWorkBatchConsumer {
                    override val segmentWorkerCount: Int = 0

                    override fun consume(workUnits: Long) = Unit
                })
    assertEquals(expectedRawProjection.map { values -> values.take(1) }, inlineOnlyProjection?.map { row -> row.values })
}

private fun assertProjectionFailures(loaded: MappedWebGraphBackedGraph) {
    loaded.resetCallSiteScanMetrics()
    val projectionFailureClaimed = AtomicBoolean()
    val projectionFailure =
        assertFailsWith<IllegalStateException> {
            loaded.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")),
                projectedProperties = listOf("caller_class"),
                limit = 2,
                workConsumer =
                    object : SplitGraphWorkBatchConsumer {
                        override val segmentWorkerCount: Int = 1

                        override fun consume(workUnits: Long) {
                            if (projectionFailureClaimed.compareAndSet(false, true)) {
                                error("parallel projection budget failure")
                            }
                        }
                    })
        }
    assertEquals("parallel projection budget failure", projectionFailure.message)
    assertEquals(0, loaded.callSiteScanActiveWorkers())
    assertTrue(loaded.callSiteScanAbortedWorkers() >= 1L)

    loaded.resetCallSiteScanMetrics()
    val projectionBackgroundStarted = CountDownLatch(1)
    val releaseProjectionBackground = CountDownLatch(1)
    val interruptedProjectionFailure = AtomicReference<Throwable>()
    val interruptedProjectionFlag = AtomicBoolean()
    val interruptedProjection =
        Thread(
            {
                try {
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target")),
                        projectedProperties = listOf("caller_class"),
                        limit = 2,
                        workConsumer =
                            object : SplitGraphWorkBatchConsumer {
                                override val segmentWorkerCount: Int = 1

                                override fun consume(workUnits: Long) {
                                    if (Thread.currentThread().name.startsWith("graphite-callsite-segment-")) {
                                        projectionBackgroundStarted.countDown()
                                        check(releaseProjectionBackground.await(5, TimeUnit.SECONDS))
                                    }
                                }
                            })
                    interruptedProjectionFailure.set(AssertionError("Interrupted projection completed normally"))
                } catch (error: Throwable) {
                    interruptedProjectionFailure.set(error)
                    interruptedProjectionFlag.set(Thread.currentThread().isInterrupted)
                }
            },
            "projection-interruption-test")
    interruptedProjection.start()
    try {
        assertTrue(projectionBackgroundStarted.await(5, TimeUnit.SECONDS))
        interruptedProjection.interrupt()
    } finally {
        releaseProjectionBackground.countDown()
    }
    interruptedProjection.join(5_000)
    assertFalse(interruptedProjection.isAlive)
    assertTrue(
        interruptedProjectionFailure.get() is CancellationException,
        interruptedProjectionFailure.get()?.stackTraceToString() ?: "No failure was captured")
    assertTrue(interruptedProjectionFlag.get())
    assertEquals(0, loaded.callSiteScanActiveWorkers())
}

private fun assertQualifiedQuery(loaded: MappedWebGraphBackedGraph) {
    loaded.resetCallSiteScanMetrics()
    val qualified =
        CrossGraphCypherExecutor(listOf(CypherGraph("selected", loaded)), CypherExecutionBudget(maxWorkUnits = 100_000))
            .execute(
                """
        MATCH (n:CallSiteNode)
        WHERE n.graphId = 'selected' AND (
            toLower(n.caller_class) CONTAINS 'target' OR
            toLower(n.caller_name) CONTAINS 'target' OR
            toLower(n.callee_class) CONTAINS 'target' OR
            toLower(n.callee_name) CONTAINS 'target'
        )
        RETURN n.graphId AS graph, n.caller_class AS caller LIMIT 1
        """
                    .trimIndent())
    assertEquals(listOf("selected"), qualified.rows.map { row -> row["graph"] })
    assertEquals(listOf("example.TargetCaller0"), qualified.rows.map { row -> row["caller"] })
    assertEquals(1L, loaded.callSiteParallelScanCount())
}

private fun assertParallelFailures(loaded: MappedWebGraphBackedGraph) {
    if (Runtime.getRuntime().availableProcessors() > 1) {
        loaded.resetCallSiteScanMetrics()
        val expectedWorkers = callSiteScanParallelism
        val firstBatchReached = CountDownLatch(expectedWorkers)
        val failureClaimed = AtomicBoolean()
        val consumedWork = AtomicLong()
        val failure =
            assertFailsWith<IllegalStateException> {
                loaded
                    .nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "NeverMatches")),
                        limit = 1,
                        workConsumer =
                            ParallelGraphWorkBatchConsumer { workUnits ->
                                consumedWork.addAndGet(workUnits)
                                val failThisWorker = failureClaimed.compareAndSet(false, true)
                                firstBatchReached.countDown()
                                check(firstBatchReached.await(5, TimeUnit.SECONDS))
                                if (failThisWorker) error("parallel scan budget failure")
                                while (loaded.callSiteScanActiveWorkers() == expectedWorkers) {
                                    Thread.onSpinWait()
                                }
                            })
                    .orEmpty()
                    .toList()
            }
        assertEquals("parallel scan budget failure", failure.message)
        assertEquals(expectedWorkers * 1_024L, consumedWork.get())
        assertEquals(expectedWorkers - 1L, loaded.callSiteScanAbortedWorkers())

        loaded.resetCallSiteScanMetrics()
        val interruptionBatchReached = CountDownLatch(expectedWorkers)
        val releaseInterruptedWorkers = CountDownLatch(1)
        val interruptedFailure = AtomicReference<Throwable>()
        val interruptedFlag = AtomicBoolean()
        val interruptedRequest = Thread {
            try {
                loaded
                    .nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "NeverMatches")),
                        limit = 1,
                        workConsumer =
                            ParallelGraphWorkBatchConsumer {
                                interruptionBatchReached.countDown()
                                check(releaseInterruptedWorkers.await(5, TimeUnit.SECONDS))
                            })
                    .orEmpty()
                    .toList()
                interruptedFailure.set(AssertionError("Interrupted scan completed normally"))
            } catch (error: Throwable) {
                interruptedFailure.set(error)
                interruptedFlag.set(Thread.currentThread().isInterrupted)
            }
        }
        interruptedRequest.start()
        try {
            assertTrue(interruptionBatchReached.await(5, TimeUnit.SECONDS))
            interruptedRequest.interrupt()
        } finally {
            releaseInterruptedWorkers.countDown()
        }
        interruptedRequest.join(5_000)
        assertFalse(interruptedRequest.isAlive)
        assertTrue(
            interruptedFailure.get() is CancellationException,
            interruptedFailure.get()?.stackTraceToString() ?: "No failure was captured")
        assertEquals("Mapped string-property scan interrupted", interruptedFailure.get().message)
        assertTrue(interruptedFlag.get())
        assertTrue(loaded.callSiteScanAbortedWorkers() in 1L..expectedWorkers.toLong())
    }
}
