package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.GraphTask
import io.johnsonlee.graphite.graph.GraphTaskScheduler
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MethodPartialSubmissionTest {
    @Test
    fun `partial Method submission joins its callback before helper returns after parent cancellation`() {
        // REQUEST and the first source need two background root lanes (P-1).
        // With fewer processors the coordinator cannot wait for that source during submission.
        if (GraphTaskScheduler.shared.parallelism < 3) return
        val state = SubmissionState()
        val requests = GraphTaskScheduler.shared.newRequestGroup<Unit>()
        val start = CountDownLatch(1)
        val owner = AtomicReference<GraphTask<Unit>>()
        try {
            val request = requests.submit(Callable {
                check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                invokeHelper(CancellingTasks(state, owner), state)
            })
            owner.set(request)
            start.countDown()
            assertTrue(state.cancelRequested.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, state.activeCallbacks.get())
            assertFalse(
                state.helperReturned.await(100, TimeUnit.MILLISECONDS),
                "Method helper escaped partial submission before its running callback exited"
            )
            assertFalse(state.callbackInterrupted.get())
            assertFalse(state.secondRan.get())

            state.release.countDown()
            assertTrue(state.helperReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val failure = assertIs<CancellationException>(state.failure.get())
            assertEquals("Graph task group cancelled", failure.message)
            assertTrue(state.exitedAtHelperReturn.get())
            assertFalse(state.helperInterrupted.get())
            assertFalse(state.callbackInterrupted.get())
            assertFalse(state.secondRan.get())
            assertEquals(1, state.secondReads.get())
            assertEquals(0, state.activeCallbacks.get())
            assertFailsWith<CancellationException> { request.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            assertTrue(request.isDone)
        } finally {
            start.countDown()
            state.release.countDown()
            requests.cancelAndJoin(mayInterruptIfRunning = false)
            requests.close()
        }
    }

    private fun invokeHelper(tasks: List<(() -> Unit) -> Int>, state: SubmissionState) {
        val helper = Class.forName("io.johnsonlee.graphite.cypher.MethodQueryExecutorKt")
            .getDeclaredMethod("runMethodGraphTasks", List::class.java)
            .apply { isAccessible = true }
        try {
            helper.invoke(null, tasks)
        } catch (failure: InvocationTargetException) {
            state.failure.set(failure.targetException)
        } finally {
            // This is deliberately inside the REQUEST callable, before scheduler ancestor
            // cleanup. Observing only request.get() would let that cleanup hide the defect.
            state.exitedAtHelperReturn.set(state.callbackExited.get())
            state.helperInterrupted.set(Thread.currentThread().isInterrupted)
            state.helperReturned.countDown()
        }
    }

    private class CancellingTasks(
        private val state: SubmissionState,
        private val owner: AtomicReference<GraphTask<Unit>>
    ) : AbstractList<(() -> Unit) -> Int>() {
        override val size = 2

        override fun get(index: Int): (() -> Unit) -> Int = when (index) {
            0 -> { _ -> state.blockFirstCallback() }
            1 -> {
                val next: (() -> Unit) -> Int = { state.secondRan.set(true); 17 }
                state.secondReads.incrementAndGet()
                check(state.firstStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                check(owner.get().cancel(false))
                state.cancelRequested.countDown()
                next
            }
            else -> throw IndexOutOfBoundsException(index.toString())
        }
    }

    private class SubmissionState {
        val firstStarted = CountDownLatch(1)
        val cancelRequested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val helperReturned = CountDownLatch(1)
        val activeCallbacks = AtomicInteger()
        val callbackExited = AtomicBoolean()
        val callbackInterrupted = AtomicBoolean()
        val helperInterrupted = AtomicBoolean()
        val exitedAtHelperReturn = AtomicBoolean()
        val secondRan = AtomicBoolean()
        val secondReads = AtomicInteger()
        val failure = AtomicReference<Throwable?>()

        fun blockFirstCallback(): Int {
            activeCallbacks.incrementAndGet()
            firstStarted.countDown()
            try {
                release.await()
                return 11
            } catch (error: InterruptedException) {
                callbackInterrupted.set(true)
                throw error
            } finally {
                activeCallbacks.decrementAndGet()
                callbackExited.set(true)
            }
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
