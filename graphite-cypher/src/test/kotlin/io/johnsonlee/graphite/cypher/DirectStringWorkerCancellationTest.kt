package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.GraphTaskRole
import io.johnsonlee.graphite.graph.GraphTaskScheduler
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Direct-string workers now use graph task groups instead of separate futures and completion latches. */
class DirectStringWorkerCancellationTest {
    @Test
    fun `cancels a worker that never started and publishes its completion once`() {
        val group = GraphTaskScheduler.shared.newGroup<Unit>(
            backgroundParallelism = 0, helpWhileWaiting = false, parentContext = null,
            role = GraphTaskRole.GRAPH_SOURCE
        )
        val entered = AtomicBoolean()
        try {
            val task = group.submit(Callable { entered.set(true) })

            assertTrue(task.cancel(true))
            assertTrue(task.isCancelled)
            assertTrue(task.isDone)
            assertFalse(entered.get(), "a cancelled queued worker must never enter its callable")
            assertSame(task, group.poll())
            assertFalse(task.cancel(true))
            assertNull(group.poll(), "cancellation must not publish completion twice")
            assertFailsWith<CancellationException> { task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            group.cancelAndJoin()
            group.close()
        }
    }

    @Test
    fun `a cancelled running worker owns completion until its finally exits`() {
        val group = GraphTaskScheduler.shared.newGroup<Unit>(
            backgroundParallelism = 1, helpWhileWaiting = false, parentContext = null,
            role = GraphTaskRole.GRAPH_SOURCE
        )
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = AtomicBoolean()
        try {
            val task = group.submit(Callable {
                try {
                    entered.countDown()
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                } finally {
                    check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    exited.set(true)
                }
            })
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(task.cancel(true))
            assertTrue(interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(task.isCancelled)
            assertFalse(task.isDone, "cancellation cannot publish completion before callable exit")
            assertNull(group.poll())

            release.countDown()
            assertFailsWith<CancellationException> { task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            assertTrue(exited.get())
            assertTrue(task.isDone)
            assertSame(task, group.poll())
            assertNull(group.poll())
        } finally {
            release.countDown()
            group.cancelAndJoin()
            group.close()
        }
    }

    @Test
    fun `a completed worker rejects cancellation and keeps its result and single completion`() {
        val group = GraphTaskScheduler.shared.newGroup<Int>(
            backgroundParallelism = 1, helpWhileWaiting = false, parentContext = null,
            role = GraphTaskRole.GRAPH_SOURCE
        )
        try {
            val task = group.submit(Callable { 42 })
            assertEquals(42, task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            assertFalse(task.cancel(true))
            assertFalse(task.isCancelled)
            assertTrue(task.isDone)
            assertEquals(42, task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertSame(task, group.poll())
            assertNull(group.poll())
        } finally {
            group.cancelAndJoin()
            group.close()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
