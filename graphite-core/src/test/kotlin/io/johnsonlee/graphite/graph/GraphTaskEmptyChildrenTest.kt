package io.johnsonlee.graphite.graph

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphTaskEmptyChildrenTest {
    @Test
    fun `finishing an owner without children closes registration but retains its execution slot`() {
        for (cancel in listOf(false, true)) {
            GraphTaskScheduler(2).use { scheduler -> verifyEmptyOwner(scheduler, cancel) }
        }
    }

    private fun verifyEmptyOwner(scheduler: GraphTaskScheduler, cancel: Boolean) {
        val cleaned = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbackExited = AtomicBoolean()
        val callbackInterrupted = AtomicBoolean()
        val callbackCancelled = AtomicBoolean()
        val events = LinkedBlockingQueue<String>()
        val original = IllegalArgumentException("empty owner failure")
        val owners = scheduler.newGroup<Int>(maxConcurrentTasks = 1)
        val canaries = scheduler.newGroup<Unit>()
        val owner = owners.submit(Callable {
            val context = GraphTaskContext.current!!
            assertTrue(context.isAcceptingChildren)
            assertSame(original, context.finishChildren(original))
            assertFalse(context.isAcceptingChildren)
            assertFailsWith<IllegalStateException> { scheduler.newGroup<Int>() }
            assertSame(original, context.finishChildren(original))
            assertNull(context.finishChildren())
            cleaned.countDown()
            try {
                release.await()
                callbackInterrupted.set(Thread.currentThread().isInterrupted)
                callbackCancelled.set(context.isCancelled)
                17
            } catch (interrupted: InterruptedException) {
                callbackInterrupted.set(true)
                throw interrupted
            } finally {
                callbackExited.set(true)
            }
        })
        try {
            assertTrue(cleaned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            if (cancel) assertTrue(owner.cancel(false))
            assertFalse(owner.isDone)
            assertFalse(callbackExited.get())
            assertNull(owners.poll())
            assertFailsWith<TimeoutException> { owner.get(0, TimeUnit.NANOSECONDS) }
            // Both entries are queued before the spare worker can claim either. The closed
            // child context must not release the owner's slot ahead of the callback return.
            val replacement = synchronized(scheduler.monitor) {
                val task = owners.submit(Callable {
                    assertTrue(callbackExited.get())
                    events.add("replacement")
                    23
                })
                canaries.submit(Callable { events.add("canary"); Unit })
                task
            }
            assertEquals("canary", events.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(replacement.isDone)
            assertFalse(owner.isDone)
            release.countDown()
            if (cancel) {
                assertFailsWith<CancellationException> { owner.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            } else {
                assertEquals(17, owner.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
            assertTrue(callbackExited.get())
            assertFalse(callbackInterrupted.get())
            assertEquals(cancel, callbackCancelled.get())
            assertEquals(23, replacement.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("replacement", events.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            owners.cancelAndJoin()
            canaries.cancelAndJoin()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
