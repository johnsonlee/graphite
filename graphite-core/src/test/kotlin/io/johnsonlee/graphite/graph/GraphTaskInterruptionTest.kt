package io.johnsonlee.graphite.graph

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphTaskInterruptionTest {
    @Test
    fun `scheduler close interrupted during join waits for actual worker exit`() {
        val scheduler = GraphTaskScheduler(1)
        val group = scheduler.newGroup<Int>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = AtomicBoolean()
        val worker = AtomicReference<Thread>()
        var closer: AsyncAction? = null
        val task = group.submit(Callable {
            worker.set(Thread.currentThread())
            try {
                started.countDown()
                release.await()
                19
            } finally {
                exited.set(true)
            }
        })
        try {
            await(started)
            val closing = AsyncAction { scheduler.close() }.also { closer = it }
            awaitWaiting(closing.thread, "join")
            closing.thread.interrupt()
            awaitWaiting(closing.thread, "join")
            assertFalse(closing.finished.get())
            assertFalse(exited.get())
            assertFalse(task.isDone)
            assertFalse(task.isCancelled)
            release.countDown()
            closing.joinAndCheck()
            assertTrue(closing.interruptedOnReturn.get())
            assertTrue(exited.get())
            assertFalse(worker.get().isAlive)
            assertEquals(19, task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { scheduler.newGroup<Int>() }
        } finally {
            release.countDown()
            closer?.joinAndCheck()
            scheduler.close()
        }
    }

    @Test
    fun `cancel and join interrupted while waiting retains false interruption policy`() {
        GraphTaskScheduler(1).use { scheduler ->
            val group = scheduler.newGroup<Int>()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val exited = AtomicBoolean()
            val callbackInterrupted = AtomicBoolean()
            val callbackCancelled = AtomicBoolean()
            var closer: AsyncAction? = null
            val task = group.submit(Callable {
                try {
                    started.countDown()
                    release.await()
                    callbackInterrupted.set(Thread.currentThread().isInterrupted)
                    callbackCancelled.set(GraphTaskContext.current!!.isCancelled)
                    23
                } finally {
                    exited.set(true)
                }
            })
            try {
                await(started)
                val closing = AsyncAction { group.cancelAndJoin(false) }.also { closer = it }
                awaitWaiting(closing.thread, "awaitAll")
                closing.thread.interrupt()
                awaitWaiting(closing.thread, "awaitAll")
                assertFalse(closing.finished.get())
                assertFalse(exited.get())
                assertFalse(task.isDone)
                assertTrue(task.isCancelled)
                assertNull(group.poll())
                release.countDown()
                closing.joinAndCheck()
                assertTrue(closing.interruptedOnReturn.get())
                assertTrue(exited.get())
                assertTrue(callbackCancelled.get())
                assertFalse(callbackInterrupted.get())
                assertFailsWith<CancellationException> { task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                assertWorkerReusable(scheduler)
            } finally {
                release.countDown()
                closer?.joinAndCheck()
                group.cancelAndJoin()
            }
        }
    }

    @Test
    fun `group close interrupted during wait cancels and joins callback finally`() {
        GraphTaskScheduler(1).use { scheduler ->
            val group = scheduler.newGroup<Int>()
            val callback = InterruptibleCallback()
            val task = group.submit(Callable { callback.run() })
            var closer: AsyncAction? = null
            try {
                await(callback.started)
                val closing = AsyncAction { group.close() }.also { closer = it }
                awaitWaiting(closing.thread, "awaitAll")
                assertFalse(task.isCancelled)
                closing.thread.interrupt()
                await(callback.inFinally)
                awaitWaiting(closing.thread, "cancelAndJoin")
                assertTrue(task.isCancelled)
                assertTrue(callback.interrupted.get())
                assertFalse(callback.exited.get())
                assertFalse(task.isDone)
                assertFalse(closing.finished.get())
                assertNull(group.poll())
                callback.releaseFinally.countDown()
                closing.joinAndCheck()
                assertTrue(closing.interruptedOnReturn.get())
                assertTrue(callback.exited.get())
                assertTrue(callback.cancelled.get())
                assertFailsWith<CancellationException> { task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                assertWorkerReusable(scheduler)
            } finally {
                callback.releaseFinally.countDown()
                task.cancel(true)
                closer?.joinAndCheck()
                group.cancelAndJoin()
            }
        }
    }

    @Test
    fun `interrupted owner cleanup cancels its child without publishing early completion`() {
        GraphTaskScheduler(2).use { scheduler ->
            val owners = scheduler.newGroup<Int>()
            val callback = InterruptibleCallback()
            val worker = AtomicReference<Thread>()
            val child = AtomicReference<GraphTask<Int>>()
            val ownerInterrupted = AtomicBoolean()
            val cleanupReturned = AtomicBoolean()
            val owner = owners.submit(Callable {
                worker.set(Thread.currentThread())
                val children = scheduler.newGroup<Int>(helpWhileWaiting = false)
                child.set(children.submit(Callable { callback.run() }))
                await(callback.started)
                val context = GraphTaskContext.current!!
                assertNull(context.finishChildren())
                ownerInterrupted.set(Thread.currentThread().isInterrupted)
                assertFalse(context.isCancelled)
                assertFalse(context.isAcceptingChildren)
                assertTrue(callback.exited.get())
                cleanupReturned.set(true)
                41
            })
            try {
                await(callback.started)
                awaitWaiting(worker.get(), "closeFromOwner")
                worker.get().interrupt()
                await(callback.inFinally)
                awaitWaiting(worker.get(), "closeFromOwner")
                assertTrue(child.get().isCancelled)
                assertFalse(child.get().isDone)
                assertFalse(owner.isCancelled)
                assertFalse(owner.isDone)
                assertFalse(cleanupReturned.get())
                assertFalse(callback.exited.get())
                assertNull(owners.poll())
                callback.releaseFinally.countDown()
                assertEquals(41, owner.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertTrue(ownerInterrupted.get())
                assertTrue(cleanupReturned.get())
                assertTrue(callback.interrupted.get())
                assertTrue(callback.cancelled.get())
                assertTrue(callback.exited.get())
                assertFailsWith<CancellationException> { child.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                assertWorkerReusable(scheduler)
            } finally {
                callback.releaseFinally.countDown()
                owners.cancelAndJoin()
            }
        }
    }

    @Test
    fun `submission to closed scheduler preserves rejection and cancels the queued completion`() {
        val scheduler = GraphTaskScheduler(1)
        val group = scheduler.newGroup<Int>()
        val ran = AtomicBoolean()
        scheduler.close()
        try {
            val rejected = assertFailsWith<IllegalStateException> {
                group.submit(Callable { ran.set(true); 7 })
            }
            assertEquals("Graph scheduler is closed", rejected.message)
            val cancelled = assertNotNull(group.poll())
            assertTrue(cancelled.isCancelled)
            assertTrue(cancelled.isDone)
            assertFailsWith<CancellationException> { cancelled.get() }
            assertNull(group.poll())
            group.awaitAll()
            assertFalse(ran.get())
        } finally {
            group.close()
            scheduler.close()
        }
    }

    @Test
    fun `idle worker interruption does not poison or replace its next execution lease`() {
        GraphTaskScheduler(1).use { scheduler ->
            val group = scheduler.newGroup<Thread>()
            val worker = group.submit(Callable { Thread.currentThread() }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            awaitWaiting(worker, "work")
            worker.interrupt()
            awaitWaiting(worker, "work")
            val reused = group.submit(Callable {
                assertFalse(Thread.currentThread().isInterrupted)
                assertFalse(GraphTaskContext.current!!.isCancelled)
                Thread.currentThread()
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertSame(worker, reused)
            group.close()
        }
    }

    private fun assertWorkerReusable(scheduler: GraphTaskScheduler) {
        val group = scheduler.newGroup<Int>()
        try {
            val next = group.submit(Callable {
                assertFalse(Thread.currentThread().isInterrupted)
                assertFalse(GraphTaskContext.current!!.isCancelled)
                31
            })
            assertEquals(31, next.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            group.close()
        }
    }

    private class InterruptibleCallback {
        val started = CountDownLatch(1)
        val inFinally = CountDownLatch(1)
        val releaseFinally = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val exited = AtomicBoolean()

        fun run(): Int {
            try {
                started.countDown()
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                interrupted.set(true)
                cancelled.set(GraphTaskContext.current!!.isCancelled)
            } finally {
                inFinally.countDown()
                releaseFinally.await()
                exited.set(true)
            }
            return 29
        }
    }

    @Suppress("TooGenericExceptionCaught") // Report assertions and cleanup failures back to the test thread.
    private class AsyncAction(action: () -> Unit) {
        val finished = AtomicBoolean()
        val interruptedOnReturn = AtomicBoolean()
        private val failure = AtomicReference<Throwable>()
        val thread = Thread {
            try {
                action()
                interruptedOnReturn.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.set(true)
            }
        }.apply {
            isDaemon = true
            start()
        }

        fun joinAndCheck() {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            assertFalse(thread.isAlive, "Asynchronous cleanup did not return after its callback was released")
            assertTrue(finished.get())
            assertNull(failure.get(), "Asynchronous cleanup failed")
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L

        fun await(latch: CountDownLatch) {
            assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }

        /** Observe the actual blocking site before interrupting, not just a pre-call latch. */
        fun awaitWaiting(thread: Thread, method: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (thread.state != Thread.State.WAITING || thread.isInterrupted ||
                thread.stackTrace.none { it.methodName.substringBefore('$') == method }
            ) {
                check(thread.isAlive && System.nanoTime() < deadline) { "Thread did not wait inside $method" }
                Thread.sleep(1)
            }
        }
    }
}
