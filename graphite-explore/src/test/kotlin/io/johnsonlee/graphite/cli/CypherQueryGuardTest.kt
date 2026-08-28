package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherQueryCancelledException
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CypherQueryGuardTest {

    @Test
    fun `guard validates concurrency and work limits`() {
        assertFailsWith<IllegalArgumentException> {
            CypherQueryGuard(maxConcurrent = 0, maxWorkUnits = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 0)
        }
    }

    @Test
    fun `guard releases permit when query fails`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)

        try {
            assertFailsWith<IllegalStateException> {
                guard.execute { error("query failed") }
            }

            assertEquals(10L, guard.execute { it.executionBudget.maxWorkUnits })
        } finally {
            guard.close()
        }
    }

    @Test
    fun `guard rejects a second synchronous query while its only permit is held`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)

        try {
            guard.execute {
                assertFailsWith<CypherConcurrencyLimitException> {
                    guard.execute { error("must not run") }
                }
            }
        } finally {
            guard.close()
        }
    }

    @Test
    fun `guard does not start work after cancellation arrives before execution`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)
        val cancellation = CypherCancellationSignal().apply(CypherCancellationSignal::cancel)
        val executed = AtomicBoolean()

        try {
            val task = guard.submit(cancellation) {
                executed.set(true)
            }

            assertFailsWith<CypherQueryCancelledException> { task.completion.get(5, TimeUnit.SECONDS) }
            assertFalse(executed.get())
        } finally {
            guard.close()
        }
    }

    @Test
    fun `cancelling an async query interrupts blocking work and releases its permit`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)
        val cancellation = CypherCancellationSignal()
        val started = CountDownLatch(1)
        val task = guard.submit(cancellation) {
            started.countDown()
            CountDownLatch(1).await()
        }

        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            task.cancel()
            assertFailsWith<CypherQueryCancelledException> { task.completion.get(5, TimeUnit.SECONDS) }
            assertEquals(10L, executeWhenAvailable(guard) { it.executionBudget.maxWorkUnits })
        } finally {
            guard.close()
        }
    }

    @Test
    fun `cancellation wins when work ignores interruption and returns`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)
        val cancellation = CypherCancellationSignal()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val task = guard.submit(cancellation) {
            started.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Deliberately ignore interruption to exercise the post-block cancellation check.
                }
            }
            "completed"
        }

        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            task.cancel()
            release.countDown()

            assertFailsWith<CypherQueryCancelledException> { task.completion.get(5, TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            guard.close()
        }
    }

    @Test
    fun `guard retains permit until registered continuation returns`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)
        val workStarted = CountDownLatch(1)
        val releaseWork = CountDownLatch(1)
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val task = guard.submit(
            CypherCancellationSignal(),
            continuation = {
                callbackStarted.countDown()
                releaseCallback.await()
            }
        ) {
            workStarted.countDown()
            releaseWork.await()
            "completed"
        }

        try {
            assertTrue(workStarted.await(5, TimeUnit.SECONDS))
            releaseWork.countDown()
            assertTrue(callbackStarted.await(5, TimeUnit.SECONDS))

            assertFailsWith<CypherConcurrencyLimitException> {
                guard.execute { error("must not overlap completion callback") }
            }

            releaseCallback.countDown()
            assertEquals("completed", task.completion.get(5, TimeUnit.SECONDS))
            assertEquals("next", executeWhenAvailable(guard) { "next" })
        } finally {
            releaseWork.countDown()
            releaseCallback.countDown()
            guard.close()
        }
    }

    @Test
    fun `task completion is published after guard teardown`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)

        try {
            val task = guard.submit(CypherCancellationSignal()) { "completed" }

            assertEquals("completed", task.completion.get(5, TimeUnit.SECONDS))
            assertEquals("next", guard.execute { "next" })
        } finally {
            guard.close()
        }
    }

    @Test
    fun `continuation failure is published after guard teardown`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)
        val responseFailure = IllegalStateException("response failed")

        try {
            val task = guard.submit(
                CypherCancellationSignal(),
                continuation = { throw responseFailure }
            ) { "completed" }

            val executionError = assertFailsWith<ExecutionException> {
                task.completion.get(5, TimeUnit.SECONDS)
            }
            assertTrue(executionError.cause is CypherContinuationException)
            val error = executionError.cause as CypherContinuationException
            assertEquals(responseFailure, error.cause)
            assertEquals("next", guard.execute { "next" })
        } finally {
            guard.close()
        }
    }

    @Test
    fun `worker rejection publishes failure and releases permit`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)
        val executorField = CypherQueryGuard::class.java.getDeclaredField("executor").apply {
            isAccessible = true
        }
        (executorField.get(guard) as ThreadPoolExecutor).shutdownNow()

        try {
            val task = guard.submit(CypherCancellationSignal()) { "must not run" }
            val executionError = assertFailsWith<ExecutionException> {
                task.completion.get(5, TimeUnit.SECONDS)
            }
            assertTrue(executionError.cause is RejectedExecutionException)
            assertEquals("next", guard.execute { "next" })
        } finally {
            guard.close()
        }
    }

    private fun <T> executeWhenAvailable(
        guard: CypherQueryGuard,
        block: (CypherExecutionContext) -> T
    ): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            try {
                return guard.execute(block)
            } catch (_: CypherConcurrencyLimitException) {
                Thread.sleep(10)
            }
        }
        error("Cypher guard did not release its permit")
    }
}
