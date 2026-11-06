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

/** Checks optional index admission preserves budgets and joins failed or interrupted preparation workers. */
internal object CallSiteAdmissionAssertions {
    fun verify(loaded: MappedWebGraphBackedGraph) {
        assertOptionalHandoffBudgets(loaded)
        assertPreparationFailures(loaded)
        loaded.resetCallSiteScanMetrics()
        assertEquals(0L, loaded.callSiteParallelScanCount())
        assertEquals(0L, loaded.callSiteStringIndexLookupCount())
        assertEquals(0, loaded.callSiteScanPeakActiveWorkers())
        assertEquals(0L, loaded.callSiteScanAbortedWorkers())
    }

    private fun assertOptionalHandoffBudgets(loaded: MappedWebGraphBackedGraph) {
        loaded.clearStringPropertyIndexes()
        val budgetBeforeFailedHandoff = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        val scanWork = 32_768L
        val failedHandoffWork = AtomicLong()
        val handoffResult =
            loaded
                .nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "definitely-absent")),
                    limit = 1,
                    workConsumer =
                        ParallelGraphWorkBatchConsumer { workUnits ->
                            check(failedHandoffWork.addAndGet(workUnits) <= scanWork) { "fused index budget failure" }
                        })
                .orEmpty()
                .toList()
        assertTrue(handoffResult.isEmpty())
        assertFalse(loaded.isCallSiteStringIndexInitialized())
        assertEquals(budgetBeforeFailedHandoff, MappedCallSiteStringIndexMemoryBudget.retainedBytes())

        val handoffWorkLimit = 40_000L
        val consumedWork = AtomicLong()
        val budgetedHandoffResult =
            loaded
                .nodesByStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(
                        StringPropertyPredicate(
                            "caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "definitely-absent")),
                    limit = 1,
                    workConsumer =
                        ParallelGraphWorkBatchConsumer { workUnits ->
                            val total = consumedWork.addAndGet(workUnits)
                            check(total <= handoffWorkLimit) { "fused index exceeded request budget" }
                        })
                .orEmpty()
                .toList()
        assertTrue(budgetedHandoffResult.isEmpty())
        assertTrue(consumedWork.get() in (scanWork + 1)..(scanWork * 3))
        assertFalse(loaded.isCallSiteStringIndexInitialized())
        assertEquals(budgetBeforeFailedHandoff, MappedCallSiteStringIndexMemoryBudget.retainedBytes())

        val cancellationWork = AtomicLong()
        val handoffCancellation =
            assertFailsWith<CancellationException> {
                loaded
                    .nodesByStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(
                            StringPropertyPredicate(
                                "caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "definitely-absent")),
                        limit = 1,
                        workConsumer =
                            ParallelGraphWorkBatchConsumer { workUnits ->
                                if (cancellationWork.addAndGet(workUnits) > scanWork) {
                                    throw CancellationException("cancelled during optional cache handoff")
                                }
                            })
                    .orEmpty()
                    .toList()
            }
        assertEquals("cancelled during optional cache handoff", handoffCancellation.message)
        assertFalse(loaded.isCallSiteStringIndexInitialized())
        assertEquals(budgetBeforeFailedHandoff, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
    }

    private fun assertPreparationFailures(loaded: MappedWebGraphBackedGraph) {
        val budgetBeforeFailedHandoff = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        if (Runtime.getRuntime().availableProcessors() > 1) {
            loaded.resetCallSiteScanMetrics()
            val expectedWorkers = callSiteScanParallelism
            val firstPreparationBatchReached = CountDownLatch(expectedWorkers)
            val preparationFailureClaimed = AtomicBoolean()
            val preparationFailure =
                assertFailsWith<IllegalStateException> {
                    loaded.prepareCallSiteStringIndex(
                        ParallelGraphWorkBatchConsumer {
                            val failThisWorker = preparationFailureClaimed.compareAndSet(false, true)
                            firstPreparationBatchReached.countDown()
                            check(firstPreparationBatchReached.await(5, TimeUnit.SECONDS))
                            if (failThisWorker) error("parallel preparation budget failure")
                            while (loaded.callSiteScanActiveWorkers() == expectedWorkers) {
                                Thread.onSpinWait()
                            }
                        })
                }
            assertEquals("parallel preparation budget failure", preparationFailure.message)
            assertEquals(0, loaded.callSiteScanActiveWorkers())
            assertEquals(0L, loaded.callSiteScanAbortedWorkers())
            assertFalse(loaded.isCallSiteStringIndexInitialized())
            assertEquals(budgetBeforeFailedHandoff, MappedCallSiteStringIndexMemoryBudget.retainedBytes())

            loaded.resetCallSiteScanMetrics()
            val interruptedPreparationBatchReached = CountDownLatch(expectedWorkers)
            val releaseInterruptedPreparation = CountDownLatch(1)
            val interruptedPreparationFailure = AtomicReference<Throwable>()
            val interruptedPreparationFlag = AtomicBoolean()
            val interruptedPreparation = Thread {
                try {
                    loaded.prepareCallSiteStringIndex(
                        ParallelGraphWorkBatchConsumer {
                            interruptedPreparationBatchReached.countDown()
                            check(releaseInterruptedPreparation.await(5, TimeUnit.SECONDS))
                        })
                    interruptedPreparationFailure.set(AssertionError("Interrupted CallSite preparation completed normally"))
                } catch (error: Throwable) {
                    interruptedPreparationFailure.set(error)
                    interruptedPreparationFlag.set(Thread.currentThread().isInterrupted)
                }
            }
            interruptedPreparation.start()
            try {
                assertTrue(interruptedPreparationBatchReached.await(5, TimeUnit.SECONDS))
                interruptedPreparation.interrupt()
            } finally {
                releaseInterruptedPreparation.countDown()
            }
            interruptedPreparation.join(5_000)
            assertFalse(interruptedPreparation.isAlive)
            assertTrue(
                interruptedPreparationFailure.get() is CancellationException,
                interruptedPreparationFailure.get()?.stackTraceToString() ?: "No failure was captured")
            assertTrue(interruptedPreparationFlag.get())
            assertEquals(0, loaded.callSiteScanActiveWorkers())
            assertEquals(0L, loaded.callSiteScanAbortedWorkers())
            assertFalse(loaded.isCallSiteStringIndexInitialized())
            assertEquals(budgetBeforeFailedHandoff, MappedCallSiteStringIndexMemoryBudget.retainedBytes())

            assertTrue(loaded.prepareCallSiteStringIndex())
            assertTrue(loaded.isCallSiteStringIndexInitialized())
            assertTrue(loaded.isCallSiteTrigramIndexInitialized())
            loaded.clearStringPropertyIndexes()
        }
    }
}
