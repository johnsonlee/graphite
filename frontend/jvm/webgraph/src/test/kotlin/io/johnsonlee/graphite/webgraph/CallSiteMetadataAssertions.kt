package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun assertCallSiteMetadataRecovery(loaded: MappedWebGraphBackedGraph) {
    assertMetadataFailureRecovery(loaded)
    assertInterruptedMetadataAndPostingRecovery(loaded)
}

private fun assertMetadataFailureRecovery(loaded: MappedWebGraphBackedGraph) {
    loaded.clearStringPropertyIndexes()
    loaded.resetCallSiteScanMetrics()
    val coldZero =
        loaded
            .nodesByStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(
                    StringPropertyPredicate(
                        "caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "definitely-absent")),
                limit = 1,
                workConsumer = ParallelGraphWorkBatchConsumer {})
            .orEmpty()
            .toList()
    assertTrue(coldZero.isEmpty())
    assertEquals(1L, loaded.callSiteParallelScanCount())
    assertEquals(0L, loaded.callSiteStringIndexLookupCount())
    assertTrue(loaded.isCallSiteStringIndexInitialized())
    assertFalse(loaded.isCallSiteTrigramIndexInitialized())

    val metadataWork = AtomicLong()
    val metadataFailureClaimed = AtomicBoolean()
    val metadataFailure =
        assertFailsWith<IllegalStateException> {
            loaded
                .nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "target")),
                    limit = 1,
                    workConsumer =
                        ParallelGraphWorkBatchConsumer { workUnits ->
                            metadataWork.addAndGet(workUnits)
                            if (metadataFailureClaimed.compareAndSet(false, true)) {
                                error("trigram metadata budget failure")
                            }
                        })
                .orEmpty()
                .toList()
        }
    assertEquals("trigram metadata budget failure", metadataFailure.message)
    assertTrue(metadataWork.get() >= 1_024L)
    assertTrue(loaded.isCallSiteStringIndexInitialized())
    assertFalse(loaded.isCallSiteTrigramIndexInitialized())

    val warm =
        loaded
            .nodesByStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "target")),
                limit = 1,
                workConsumer = ParallelGraphWorkBatchConsumer {})
            .orEmpty()
            .map { it.id.value }
            .toList()
    assertEquals(listOf(0), warm)
    assertEquals(1L, loaded.callSiteParallelScanCount())
    assertEquals(2L, loaded.callSiteStringIndexLookupCount())
    assertTrue(loaded.isCallSiteTrigramIndexInitialized())
}

private fun assertInterruptedMetadataAndPostingRecovery(loaded: MappedWebGraphBackedGraph) {
    loaded.clearStringPropertyIndexes()
    val secondColdZero =
        loaded
            .nodesByStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(
                    StringPropertyPredicate(
                        "caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "definitely-absent")),
                limit = 1,
                workConsumer = ParallelGraphWorkBatchConsumer {})
            .orEmpty()
            .toList()
    assertTrue(secondColdZero.isEmpty())
    assertTrue(loaded.isCallSiteStringIndexInitialized())
    assertFalse(loaded.isCallSiteTrigramIndexInitialized())

    val metadataBatchReached = CountDownLatch(1)
    val releaseMetadataWorkers = CountDownLatch(1)
    val metadataInterruption = AtomicReference<Throwable>()
    val metadataInterruptedFlag = AtomicBoolean()
    val metadataInterruptedRequest = Thread {
        try {
            loaded
                .nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "target")),
                    limit = 1,
                    workConsumer =
                        ParallelGraphWorkBatchConsumer {
                            metadataBatchReached.countDown()
                            check(releaseMetadataWorkers.await(5, TimeUnit.SECONDS))
                        })
                .orEmpty()
                .toList()
            metadataInterruption.set(AssertionError("Interrupted trigram metadata build completed normally"))
        } catch (error: Throwable) {
            metadataInterruption.set(error)
            metadataInterruptedFlag.set(Thread.currentThread().isInterrupted)
        }
    }
    metadataInterruptedRequest.start()
    try {
        assertTrue(metadataBatchReached.await(5, TimeUnit.SECONDS))
        metadataInterruptedRequest.interrupt()
    } finally {
        releaseMetadataWorkers.countDown()
    }
    metadataInterruptedRequest.join(5_000)
    assertFalse(metadataInterruptedRequest.isAlive)
    assertTrue(
        metadataInterruption.get() is CancellationException,
        metadataInterruption.get()?.stackTraceToString() ?: "No failure was captured")
    assertEquals("CallSite trigram metadata build interrupted", metadataInterruption.get().message)
    assertTrue(metadataInterruptedFlag.get())
    assertTrue(loaded.isCallSiteStringIndexInitialized())
    assertFalse(loaded.isCallSiteTrigramIndexInitialized())

    val metadataStringCount = 32_768L * CALL_SITE_STRING_PROPERTY_COUNT
    val postingWork = AtomicLong()
    val postingFailureClaimed = AtomicBoolean()
    val postingFailure =
        assertFailsWith<IllegalStateException> {
            loaded
                .nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "target")),
                    limit = 1,
                    workConsumer =
                        ParallelGraphWorkBatchConsumer { workUnits ->
                            val total = postingWork.addAndGet(workUnits)
                            if (total > metadataStringCount && postingFailureClaimed.compareAndSet(false, true)) {
                                error("trigram posting budget failure")
                            }
                        })
                .orEmpty()
                .toList()
        }
    assertEquals("trigram posting budget failure", postingFailure.message)
    assertTrue(postingWork.get() > metadataStringCount)
    assertTrue(loaded.isCallSiteStringIndexInitialized())
    assertFalse(loaded.isCallSiteTrigramIndexInitialized())

    val postingRetry =
        loaded
            .nodesByStringPropertyDisjunction(
                CallSiteNode::class.java,
                listOf(StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "target")),
                limit = 1,
                workConsumer = ParallelGraphWorkBatchConsumer {})
            .orEmpty()
            .map { it.id.value }
            .toList()
    assertEquals(listOf(0), postingRetry)
    assertTrue(loaded.isCallSiteTrigramIndexInitialized())
}
