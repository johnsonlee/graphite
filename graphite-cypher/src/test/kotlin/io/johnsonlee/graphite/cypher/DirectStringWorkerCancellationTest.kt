package io.johnsonlee.graphite.cypher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectStringWorkerCancellationTest {
    @Test
    fun `cancels a worker that never started and releases its completion latch`() {
        val future = FutureTask { }
        val started = AtomicBoolean(false)
        val completion = CountDownLatch(1)

        val cancelled = cancelDirectStringWorkerBeforeStart(future, started, completion)

        assertTrue(cancelled, "a not-started worker must be cancelled before start")
        assertTrue(future.isCancelled)
        assertTrue(started.get(), "the started flag is claimed so the worker body cannot run")
        assertEquals(0L, completion.count, "the completion latch is released")
    }

    @Test
    fun `leaves an already started worker to finish on its own`() {
        val future = FutureTask { }
        val started = AtomicBoolean(true)
        val completion = CountDownLatch(1)

        val cancelled = cancelDirectStringWorkerBeforeStart(future, started, completion)

        assertFalse(cancelled, "a started worker owns its completion latch")
        assertEquals(1L, completion.count, "the latch is not touched for a started worker")
    }

    @Test
    fun `does not release the latch when the future can no longer be cancelled`() {
        val future = FutureTask { }
        future.run()
        val started = AtomicBoolean(false)
        val completion = CountDownLatch(1)

        val cancelled = cancelDirectStringWorkerBeforeStart(future, started, completion)

        assertFalse(cancelled, "a finished future cannot be cancelled")
        assertFalse(started.get(), "the started flag is untouched when cancellation fails")
        assertEquals(1L, completion.count, "the latch is not released when cancellation fails")
    }
}
