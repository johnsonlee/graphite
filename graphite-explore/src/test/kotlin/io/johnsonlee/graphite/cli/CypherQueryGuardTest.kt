package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherQueryCancelledException
import io.johnsonlee.graphite.cypher.CypherQueryTimeoutException
import io.johnsonlee.graphite.graph.GraphTaskContext
import io.johnsonlee.graphite.graph.GraphTaskRole
import io.johnsonlee.graphite.graph.GraphTaskScheduler
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CypherQueryGuardTest {

    @Test
    fun `synchronous and asynchronous queries execute on the shared request workers`() {
        val scheduler = GraphTaskScheduler.shared
        val guard = CypherQueryGuard()
        try {
            val sync = guard.execute {
                assertEquals(GraphTaskRole.REQUEST, GraphTaskContext.current?.role)
                Thread.currentThread().name
            }
            val async = guard.submit(CypherCancellationSignal(), continuation = {
                assertEquals(GraphTaskRole.REQUEST, GraphTaskContext.current?.role)
            }) {
                assertEquals(GraphTaskRole.REQUEST, GraphTaskContext.current?.role)
                Thread.currentThread().name
            }
            assertTrue(sync.startsWith(scheduler.threadNamePrefix))
            assertTrue(async.completion.get(5, TimeUnit.SECONDS).startsWith(scheduler.threadNamePrefix))
            async.cancel()
            assertTrue(guard.execute { !Thread.currentThread().isInterrupted })
        } finally {
            guard.close()
        }
    }

    @Test
    fun `guard drains request children before publishing completion and releasing admission`() {
        val guard = CypherQueryGuard(maxConcurrent = 1)
        val childStarted = CountDownLatch(1)
        val childExited = CountDownLatch(1)
        val releaseChild = CountDownLatch(1)
        val continued = CountDownLatch(1)
        try {
            val task = guard.submit(CypherCancellationSignal(), continuation = { continued.countDown() }) {
                GraphTaskScheduler.shared.newGroup<Unit>().submit(Callable {
                    childStarted.countDown()
                    try {
                        check(releaseChild.await(5, TimeUnit.SECONDS))
                    } finally {
                        childExited.countDown()
                    }
                })
                "completed"
            }
            assertTrue(continued.await(5, TimeUnit.SECONDS))
            assertTrue(childStarted.await(5, TimeUnit.SECONDS))
            assertFalse(task.completion.isDone)
            assertFailsWith<CypherConcurrencyLimitException> { guard.execute { "overlap" } }
            releaseChild.countDown()
            assertEquals("completed", task.completion.get(5, TimeUnit.SECONDS))
            assertEquals(0L, childExited.count)
            assertEquals("next", guard.execute { "next" })
        } finally {
            releaseChild.countDown()
            guard.close()
        }
    }

    @Test
    fun `queued cancellation completes once and guard close rejects queued work without closing shared workers`() {
        val scheduler = GraphTaskScheduler.shared
        val blockers = scheduler.newRootGroup<Unit>()
        val rootCount = (scheduler.parallelism - 1).coerceAtLeast(1)
        val started = CountDownLatch(rootCount)
        val release = CountDownLatch(1)
        repeat(rootCount) {
            blockers.submit(Callable {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            })
        }
        val cancelledGuard = CypherQueryGuard(maxConcurrent = 1)
        val closedGuard = CypherQueryGuard(maxConcurrent = 1)
        val called = AtomicInteger()
        val continuations = AtomicInteger()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val cancelled = cancelledGuard.submit(CypherCancellationSignal(), continuation = {
                continuations.incrementAndGet()
            }) { called.incrementAndGet() }
            cancelled.cancel()
            val closed = closedGuard.submit(CypherCancellationSignal(), continuation = {
                continuations.incrementAndGet()
            }) { called.incrementAndGet() }
            closedGuard.close()
            val rejection = assertFailsWith<ExecutionException> { closed.completion.get(5, TimeUnit.SECONDS) }
            assertTrue(rejection.cause is RejectedExecutionException)
            assertEquals(0, called.get())
            release.countDown()
            blockers.awaitAll()
            assertFailsWith<CypherQueryCancelledException> { cancelled.completion.get(5, TimeUnit.SECONDS) }
            assertEquals(2, continuations.get())
            assertEquals(0, called.get())
            assertEquals("next", cancelledGuard.execute { "next" })
        } finally {
            release.countDown()
            blockers.cancelAndJoin()
            cancelledGuard.close()
            closedGuard.close()
        }
    }

    @Test
    fun `unexpected submission failure preserves its cause and releases admission`() {
        val failure = IllegalStateException("timeout scheduler failed")
        val guard = CypherQueryGuard(maxConcurrent = 1)
        val field = CypherQueryGuard::class.java.getDeclaredField("timeoutExecutor").apply { isAccessible = true }
        (field.get(guard) as ScheduledThreadPoolExecutor).shutdownNow()
        field.set(guard, object : ScheduledThreadPoolExecutor(1) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> = throw failure
        })
        val continued = AtomicInteger()
        try {
            val task = guard.submit<String>(CypherCancellationSignal(), continuation = {
                assertSame(failure, it.exceptionOrNull())
                continued.incrementAndGet()
            }) { error("query must not start") }
            val error = assertFailsWith<ExecutionException> { task.completion.get(5, TimeUnit.SECONDS) }
            assertSame(failure, error.cause)
            assertEquals(1, continued.get())
            assertEquals("next", guard.execute { "next" })
        } finally {
            guard.close()
        }
    }

    @Test
    fun `recorder failures release admission and preserve the query failure`() {
        val startFailure = IllegalStateException("start failed")
        val stopFailure = IllegalStateException("stop failed")
        val failStart = AtomicBoolean(true)
        val failStop = AtomicBoolean(true)
        val recorder = object : CypherPerformanceRecorder {
            override fun start(): Long {
                if (failStart.getAndSet(false)) throw startFailure
                return 0L
            }
            override fun stop(startedAtNanos: Long, outcome: CypherQueryOutcome) {
                if (failStop.getAndSet(false)) throw stopFailure
            }
            override fun reject() = Unit
        }
        val guard = CypherQueryGuard(maxConcurrent = 1, performance = recorder)
        try {
            assertSame(startFailure, assertFailsWith<IllegalStateException> {
                guard.submit(CypherCancellationSignal()) { "never started" }
            })
            val queryFailure = IllegalArgumentException("query failed")
            val task = guard.submit<String>(CypherCancellationSignal()) { throw queryFailure }
            val error = assertFailsWith<ExecutionException> { task.completion.get(5, TimeUnit.SECONDS) }
            assertSame(queryFailure, error.cause)
            assertEquals(listOf(stopFailure), queryFailure.suppressed.toList())
            assertEquals("next", guard.execute { "next" })
        } finally {
            guard.close()
        }
    }

    @Test
    fun `completion dependent can execute a new query with its own request scope`() {
        val guard = CypherQueryGuard(maxConcurrent = 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val outer = guard.submit(CypherCancellationSignal()) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "outer"
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val dependent = outer.completion.thenApply { value ->
                assertEquals("outer", value)
                val publishingContext = GraphTaskContext.current
                assertEquals(GraphTaskRole.REQUEST, publishingContext?.role)
                assertFalse(checkNotNull(publishingContext).isAcceptingChildren)
                guard.execute {
                    assertEquals(GraphTaskRole.REQUEST, GraphTaskContext.current?.role)
                    assertNotSame(publishingContext, GraphTaskContext.current)
                    GraphTaskScheduler.shared.newRootGroup<Int>().use { sources ->
                        sources.submit(Callable { 37 }).get()
                    }
                }
            }
            release.countDown()
            assertEquals(37, dependent.get(5, TimeUnit.SECONDS))
            assertEquals("next", guard.execute { "next" })
        } finally {
            release.countDown()
            guard.close()
        }
    }

    @Test
    @Suppress("NestedBlockDepth")
    fun `new queries in closed source and storage scopes preserve the execution role`() {
        val guard = CypherQueryGuard(maxConcurrent = 1)
        try {
            listOf(GraphTaskRole.GRAPH_SOURCE, GraphTaskRole.STORAGE).forEach { role ->
                GraphTaskScheduler.shared.newGroup<Int>(role = role).use { owners ->
                    val result = owners.submit(Callable {
                        val ownerContext = checkNotNull(GraphTaskContext.current)
                        assertEquals(role, ownerContext.role)
                        assertEquals(null, ownerContext.finishChildren())
                        assertFalse(ownerContext.isAcceptingChildren)
                        guard.execute {
                            assertEquals(role, GraphTaskContext.current?.role)
                            assertNotSame(ownerContext, GraphTaskContext.current)
                            GraphTaskScheduler.shared.newGroup<Int>(0).use { children ->
                                children.submit(Callable { 41 }).get()
                            }
                        }
                    }).get(5, TimeUnit.SECONDS)
                    assertEquals(41, result)
                }
            }
        } finally {
            guard.close()
        }
    }

    @Test
    fun `guard validates concurrency and work limits`() {
        assertFailsWith<IllegalArgumentException> {
            CypherQueryGuard(maxConcurrent = 0, maxWorkUnits = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CypherQueryGuard(maxConcurrent = 1, maxTimeoutMillis = 0)
        }
        assertEquals(60_000L, DEFAULT_CYPHER_MAX_TIMEOUT_MILLIS)
    }

    @Test
    fun `guard applies shorter client timeout and caps longer client timeout`() {
        fun timedOutAfter(guard: CypherQueryGuard, clientTimeoutMillis: Long): Long {
            val started = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val task = guard.submit(
                CypherCancellationSignal(),
                clientTimeoutMillis = clientTimeoutMillis
            ) {
                started.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (error: InterruptedException) {
                    interrupted.countDown()
                    throw error
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val timeout = assertFailsWith<CypherQueryTimeoutException> {
                task.completion.get(5, TimeUnit.SECONDS)
            }
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            return timeout.timeoutMillis
        }

        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10, maxTimeoutMillis = 100)
        try {
            assertEquals(25L, timedOutAfter(guard, 25))
            assertEquals(100L, timedOutAfter(guard, 1_000))
            assertEquals("next", executeWhenAvailable(guard) { "next" })
        } finally {
            guard.close()
        }
    }

    @Test
    fun `invalid client timeout does not consume a permit`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)
        try {
            assertFailsWith<IllegalArgumentException> {
                guard.submit(CypherCancellationSignal(), clientTimeoutMillis = 0) { "invalid" }
            }
            assertEquals("next", guard.execute { "next" })
        } finally {
            guard.close()
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
    fun `default guard admits four concurrent queries and rejects a fifth`() {
        val guard = CypherQueryGuard()
        val started = CountDownLatch(minOf(DEFAULT_MAX_CONCURRENT_CYPHER,
            (io.johnsonlee.graphite.graph.GraphTaskScheduler.shared.parallelism - 1).coerceAtLeast(1)))
        val release = CountDownLatch(1)
        val tasks = List(DEFAULT_MAX_CONCURRENT_CYPHER) {
            guard.submit(CypherCancellationSignal()) {
                started.countDown()
                release.await()
            }
        }

        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFailsWith<CypherConcurrencyLimitException> {
                guard.execute { error("fifth query must not run") }
            }
            release.countDown()
            tasks.forEach { it.completion.get(5, TimeUnit.SECONDS) }
            assertEquals(DEFAULT_CYPHER_WORK_BUDGET, guard.execute { it.executionBudget.maxWorkUnits })
        } finally {
            release.countDown()
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
    fun `cancelling during continuation interrupts it and publishes cancellation`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)
        val callbackStarted = CountDownLatch(1)
        val callbackInterrupted = CountDownLatch(1)
        val task = guard.submit(
            CypherCancellationSignal(),
            continuation = {
                callbackStarted.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (error: InterruptedException) {
                    callbackInterrupted.countDown()
                    throw error
                }
            }
        ) { "completed" }

        try {
            assertTrue(callbackStarted.await(5, TimeUnit.SECONDS))
            task.cancel()

            assertTrue(callbackInterrupted.await(5, TimeUnit.SECONDS))
            assertFailsWith<CypherQueryCancelledException> { task.completion.get(5, TimeUnit.SECONDS) }
            assertEquals("next", executeWhenAvailable(guard) { "next" })
        } finally {
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
    fun `cancelling a completed task preserves its result`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)

        try {
            val task = guard.submit(CypherCancellationSignal()) { "completed" }
            assertEquals("completed", task.completion.get(5, TimeUnit.SECONDS))

            task.cancel()

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
    fun `timeout scheduling rejection publishes failure and releases permit`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)
        val executorField = CypherQueryGuard::class.java.getDeclaredField("timeoutExecutor").apply {
            isAccessible = true
        }
        (executorField.get(guard) as ScheduledThreadPoolExecutor).shutdownNow()

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
