package io.johnsonlee.graphite.webgraph

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies split call site worker. */
class SplitCallSiteWorkerTest : GraphStoreTestFixture() {

    @Test
    fun `split task exit includes worker accounting after cancellation`() {
        resetSplitCallSiteWorkerMetrics()
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val task = SplitCallSiteTask(Callable {
            started.countDown()
            try {
                releaseCleanup.await()
            } catch (_: InterruptedException) {
                interrupted.countDown()
                releaseCleanup.await()
            }
            intArrayOf(1)
        })
        try {
            task.future = executor.submit(task.callable)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(1, splitCallSiteActiveWorkers.get())
            task.cancel()
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            assertEquals(1, splitCallSiteActiveWorkers.get(), "Cancellation must not finish running cleanup")
            releaseCleanup.countDown()
            assertEquals(null, task.awaitExit())
            assertEquals(0, splitCallSiteActiveWorkers.get(), "Exit includes accounting teardown")
        } finally {
            releaseCleanup.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `split task cancelled before entry never starts worker accounting`() {
        resetSplitCallSiteWorkerMetrics()
        val ran = AtomicBoolean()
        val task = SplitCallSiteTask(Callable { ran.set(true) })
        task.future = FutureTask(task.callable)
        task.cancel()
        assertEquals(null, task.awaitExit())
        assertFailsWith<CancellationException> { task.callable.call() }
        assertFalse(ran.get())
        assertEquals(0, splitCallSiteActiveWorkers.get())
        assertEquals(0, splitCallSitePeakActiveWorkers())
    }

    @Test
    fun `background task failure preserves its cause after all workers exit`() {
        val original = IllegalArgumentException("damaged posting")
        val failed = assertFailsWith<IllegalArgumentException> {
            executeSplitCallSiteCandidateTasks(
                listOf(Callable { intArrayOf(1) }, Callable<IntArray> { throw original }), 1)
        }
        assertTrue(failed === original)
        assertEquals(0, splitCallSiteActiveWorkers.get())
    }

    @Test
    fun `interruption during failure cleanup wins without leaving a worker running`() {
        for (wrapped in listOf(false, true)) {
            val original = IllegalStateException("foreground failed")
            try {
                val failed = assertFailsWith<CancellationException> {
                    executeSplitCallSiteCandidateTasks(listOf(
                        Callable<IntArray> {
                            Thread.currentThread().interrupt()
                            if (wrapped) throw ExecutionException(original) else throw original
                        },
                        Callable { intArrayOf(2) }), 1)
                }
                assertTrue(failed.cause is InterruptedException)
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(0, splitCallSiteActiveWorkers.get())
            } finally { Thread.interrupted() }
        }
    }

    @Test
    fun `split worker metrics can reset immediately after every completed execution`() {
        repeat(100) {
            resetSplitCallSiteWorkerMetrics()
            assertContentEquals(
                intArrayOf(1, 2),
                executeSplitCallSiteCandidateTasks(
                    listOf(Callable { intArrayOf(1) }, Callable { intArrayOf(2) }), backgroundParallelism = 1))
            assertEquals(1, splitCallSitePeakActiveWorkers())
        }
        resetSplitCallSiteWorkerMetrics()
    }

    @Test
    fun `split candidate failure joins an interrupted background worker before returning`() {
        val backgroundStarted = CountDownLatch(1)
        val interruptionObserved = CountDownLatch(1)
        val releaseBackground = CountDownLatch(1)
        val callerFailure = IllegalStateException("caller failed")
        val runner = Executors.newSingleThreadExecutor()
        try {
            val execution =
                runner.submit<IntArray> {
                    executeSplitCallSiteCandidateTasks(
                        listOf(
                            Callable {
                                check(backgroundStarted.await(5, TimeUnit.SECONDS))
                                throw callerFailure
                            },
                            Callable {
                                backgroundStarted.countDown()
                                try {
                                    releaseBackground.await()
                                } catch (_: InterruptedException) {
                                    interruptionObserved.countDown()
                                    releaseBackground.await()
                                }
                                IntArray(0)
                            }),
                        backgroundParallelism = 1)
                }
            assertTrue(interruptionObserved.await(5, TimeUnit.SECONDS))
            assertFalse(execution.isDone)
            releaseBackground.countDown()
            val failure = assertFailsWith<ExecutionException> { execution.get(5, TimeUnit.SECONDS) }
            assertEquals(callerFailure, failure.cause)
        } finally {
            releaseBackground.countDown()
            runner.shutdownNow()
        }
    }

    @Test
    fun `split candidate interruption cancels and joins the background worker`() {
        val backgroundStarted = CountDownLatch(1)
        val backgroundInterrupted = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val interruptedFlag = AtomicBoolean()
        val request = Thread {
            try {
                executeSplitCallSiteCandidateTasks(
                    listOf(
                        Callable { IntArray(0) },
                        Callable {
                            backgroundStarted.countDown()
                            try {
                                Thread.sleep(TimeUnit.SECONDS.toMillis(5))
                            } catch (interrupted: InterruptedException) {
                                backgroundInterrupted.countDown()
                                throw interrupted
                            }
                            IntArray(0)
                        }),
                    backgroundParallelism = 1)
                failure.set(AssertionError("Interrupted split candidate work completed normally"))
            } catch (error: Throwable) {
                failure.set(error)
                interruptedFlag.set(Thread.currentThread().isInterrupted)
            }
        }
        request.start()
        assertTrue(backgroundStarted.await(5, TimeUnit.SECONDS))
        request.interrupt()
        request.join(5_000)

        assertFalse(request.isAlive)
        assertTrue(failure.get() is CancellationException, failure.get()?.stackTraceToString() ?: "No failure was captured")
        assertEquals("CallSite string candidate match interrupted", failure.get().message)
        assertTrue(interruptedFlag.get())
        assertTrue(backgroundInterrupted.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `split candidate executor enforces one shared background budget across graph callers`() {
        val graphCallers = Executors.newFixedThreadPool(4)
        val releaseBackground = CountDownLatch(1)
        val backgroundStarted = CountDownLatch(2)
        val activeBackground = AtomicInteger()
        val peakBackground = AtomicInteger()
        val threads = ConcurrentHashMap.newKeySet<String>()
        try {
            val executions =
                List(4) {
                    graphCallers.submit<IntArray> {
                        executeSplitCallSiteCandidateTasks(
                            listOf(
                                Callable { IntArray(0) },
                                Callable {
                                    threads += Thread.currentThread().name
                                    val active = activeBackground.incrementAndGet()
                                    peakBackground.accumulateAndGet(active, ::maxOf)
                                    backgroundStarted.countDown()
                                    try {
                                        check(releaseBackground.await(5, TimeUnit.SECONDS))
                                        IntArray(0)
                                    } finally {
                                        activeBackground.decrementAndGet()
                                    }
                                },
                                Callable {
                                    threads += Thread.currentThread().name
                                    val active = activeBackground.incrementAndGet()
                                    peakBackground.accumulateAndGet(active, ::maxOf)
                                    backgroundStarted.countDown()
                                    try {
                                        check(releaseBackground.await(5, TimeUnit.SECONDS))
                                        IntArray(0)
                                    } finally {
                                        activeBackground.decrementAndGet()
                                    }
                                }),
                            backgroundParallelism = 2)
                    }
                }
            assertTrue(backgroundStarted.await(5, TimeUnit.SECONDS))
            assertEquals(2, activeBackground.get())
            assertEquals(2, peakBackground.get())
            assertTrue(threads.all { thread -> thread.startsWith("graphite-callsite-segment-") })
            releaseBackground.countDown()
            executions.forEach { execution -> assertContentEquals(IntArray(0), execution.get(5, TimeUnit.SECONDS)) }
            assertEquals(0, activeBackground.get())
        } finally {
            releaseBackground.countDown()
            graphCallers.shutdownNow()
        }
    }
}
