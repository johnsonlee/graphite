package io.johnsonlee.graphite.cypher

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RunnableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaskCancellationRaceTest {
    @Test(timeout = 15_000)
    fun `method cancellation wins after executor entry but before scan admission`() {
        val executor = InvocationBoundaryExecutor()
        val context = CypherExecutionContext(CypherExecutionBudget(20))
        val failure = IllegalArgumentException("first source failed")
        try {
            val thrown = assertFailsWith<IllegalArgumentException> {
                runMethodGraphTasks(listOf(
                    { _: () -> Unit -> executor.awaitPausedInvocation(); throw failure },
                    { _: () -> Unit -> context.workTracker.consume(7); 42 }), executor)
            }
            assertSame(failure, thrown, "the originating source failure is preserved")
            assertTrue(executor.pausedFuture.get().isCancelled)
            assertEquals(0L, context.diagnostics.workUnitsConsumed, "a pending source is never admitted or charged")
            executor.releaseAndDrain()
            assertNotNull(executor.refusedInvocation.get(), "a cancelled callable that resumes must reject admission")
            assertEquals(0L, context.diagnostics.workUnitsConsumed, "late executor progress cannot resurrect cancelled work")
        } finally {
            executor.closeWorkers()
        }
    }

    @Test(timeout = 15_000)
    fun `unordered direct scan cancellation rejects a callable already entered by its executor`() {
        assertDirectCancellation(ordered = false)
    }

    @Test(timeout = 15_000)
    fun `ordered direct scan cancellation rejects a callable already entered by its executor`() {
        assertDirectCancellation(ordered = true)
    }

    private fun assertDirectCancellation(ordered: Boolean) {
        val executor = InvocationBoundaryExecutor()
        val context = CypherExecutionContext(CypherExecutionBudget(20))
        val failure = IllegalArgumentException("first source failed")
        val merged = mutableListOf<Int>()
        try {
            val tasks = listOf<() -> Int>(
                { executor.awaitPausedInvocation(); throw failure },
                { context.workTracker.consume(7); 42 })
            val thrown = assertFailsWith<IllegalArgumentException> {
                if (ordered) {
                    runDirectStringTasksInOrderUntil(tasks, 2, { merged += it; false }, executor)
                } else {
                    merged += runDirectStringTasks(tasks, 2, executor)
                }
            }
            assertSame(failure, thrown)
            assertTrue(executor.pausedFuture.get().isCancelled)
            assertTrue(merged.isEmpty(), "no partial or cancelled source result escapes a failed query")
            assertEquals(0L, context.diagnostics.workUnitsConsumed)
            executor.releaseAndDrain()
            assertTrue(executor.refusedInvocation.get() is CancellationException)
            assertEquals(false, executor.workerContextAfterInvocation.get(), "refused admission cannot leak worker context")
            assertEquals(0L, context.diagnostics.workUnitsConsumed, "the cancelled source remains uncharged after executor drain")
        } finally {
            executor.closeWorkers()
        }
    }

    @Test(timeout = 15_000)
    fun `nested caller-runs scans restore the enclosing worker context`() {
        val executor = InlineExecutor()
        assertFalse(isDirectStringWorker)
        val values = runDirectStringTasks(listOf(
            {
                assertTrue(isDirectStringWorker)
                val nested = runDirectStringTasks(listOf({ assertTrue(isDirectStringWorker); 3 }, { 5 }), 2, executor)
                assertTrue(isDirectStringWorker, "finishing nested work must preserve the enclosing scan context")
                nested.sum()
            },
            { 13 }), 2, executor)
        assertEquals(listOf(8, 13), values)
        assertFalse(isDirectStringWorker, "outer worker completion restores the caller context")
    }

    @Test(timeout = 15_000)
    fun `method failure unwraps an executor-style duplicate cause without losing its identity`() {
        val original = IllegalStateException("storage read failed")
        val wrapper = IllegalStateException(original)
        val failure = assertFailsWith<IllegalStateException> {
            runMethodGraphTasks(listOf({ _: () -> Unit -> throw wrapper }, { _: () -> Unit -> 1 }), InlineExecutor())
        }
        assertSame(original, failure)
    }

    /**
     * A standard FutureTask has entered RUNNING, but an executor decorator has not delegated to
     * Graphite's Callable yet. Cancellation is allowed to win in this interval; the decorator
     * deliberately finishes its non-interruptible admission phase before invoking the Callable.
     */
    private class InvocationBoundaryExecutor : ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        { runnable -> Thread(runnable, "test-scan-admission").apply { isDaemon = true } }
    ) {
        private val submitted = AtomicInteger()
        private val paused = CountDownLatch(1)
        private val release = CountDownLatch(1)
        val pausedFuture = AtomicReference<FutureTask<*>>()
        val refusedInvocation = AtomicReference<Throwable?>()
        val workerContextAfterInvocation = AtomicReference<Boolean?>()

        override fun <T : Any?> newTaskFor(callable: Callable<T>): RunnableFuture<T> {
            if (submitted.getAndIncrement() != 1) return super.newTaskFor(callable)
            val future = FutureTask(Callable {
                paused.countDown()
                awaitAdmissionRelease()
                try {
                    callable.call()
                } catch (error: Throwable) {
                    refusedInvocation.set(error)
                    throw error
                } finally {
                    workerContextAfterInvocation.set(isDirectStringWorker)
                }
            })
            pausedFuture.set(future)
            return future
        }

        fun awaitPausedInvocation() {
            assertTrue(paused.await(5, TimeUnit.SECONDS), "second Future must reach the executor admission boundary")
        }

        private fun awaitAdmissionRelease() {
            var interrupted = false
            while (release.count != 0L) {
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "query must cancel or finish the paused source")
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        fun releaseAndDrain() {
            release.countDown()
            shutdown()
            assertTrue(awaitTermination(5, TimeUnit.SECONDS), "all executor decorators must finish after cancellation")
        }

        fun closeWorkers() {
            release.countDown()
            shutdownNow()
            assertTrue(awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    /** The same inline execution policy as ThreadPoolExecutor.CallerRunsPolicy under saturation. */
    private class InlineExecutor : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): List<Runnable> { shutdown(); return emptyList() }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped
    }
}
