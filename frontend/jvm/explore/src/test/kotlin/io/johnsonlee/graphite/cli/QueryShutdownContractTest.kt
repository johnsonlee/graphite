package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherQueryCancelledException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QueryShutdownContractTest {
    @Test
    fun `cancellation before completion prevents a success continuation`() {
        CypherQueryGuard(maxConcurrent = 1).use { guard ->
            val signal = CypherCancellationSignal()
            val reason = CypherQueryCancelledException("cancelled while computing")
            val delivered = AtomicReference<Result<String>>()
            val task = guard.submit(signal, continuation = delivered::set) {
                signal.cancel(reason)
                "must not escape"
            }

            assertSame(reason, assertFailsWith<CypherQueryCancelledException> {
                task.completion.get(5, TimeUnit.SECONDS)
            })
            assertSame(reason, delivered.get().exceptionOrNull())
            assertEquals("next", guard.execute { "next" })
        }
    }

    @Test
    fun `nested continuation cancellation retains the cancellation reason and releases admission`() {
        CypherQueryGuard(maxConcurrent = 1).use { guard ->
            val signal = CypherCancellationSignal()
            val reason = CypherQueryCancelledException("response disconnected")
            val workFailure = IllegalArgumentException("query failed")
            val callbacks = AtomicInteger()
            val task = guard.submit<String>(signal, continuation = { outcome ->
                assertSame(workFailure, outcome.exceptionOrNull())
                callbacks.incrementAndGet()
                signal.cancel(reason)
                throw IllegalStateException("response writer failed", reason)
            }) { throw workFailure }

            assertSame(reason, assertFailsWith<CypherQueryCancelledException> {
                task.completion.get(5, TimeUnit.SECONDS)
            })
            assertEquals(1, callbacks.get())
            assertEquals("next", guard.execute { "next" })
        }
    }

    @Test
    fun `shutdown rejects admitted work queued behind a completion listener`() {
        val guard = CypherQueryGuard(maxConcurrent = 1)
        val workStarted = CountDownLatch(1)
        val releaseWork = CountDownLatch(1)
        val listenerStarted = CountDownLatch(1)
        val releaseListener = CountDownLatch(1)
        val queuedExecuted = AtomicBoolean()
        val rejectedCallbacks = AtomicInteger()
        try {
            val first = guard.submit(CypherCancellationSignal()) {
                workStarted.countDown()
                releaseWork.await()
                "first"
            }
            assertTrue(workStarted.await(5, TimeUnit.SECONDS))
            val listener = first.completion.thenRun {
                listenerStarted.countDown()
                try {
                    releaseListener.await()
                } catch (_: InterruptedException) {
                    // shutdown interrupts the listener; the completed query stays successful.
                }
            }
            releaseWork.countDown()
            assertTrue(listenerStarted.await(5, TimeUnit.SECONDS))
            // Completion is published after permit release, but this listener
            // still owns the worker. The next admitted query must be queued.
            val queued = guard.submit(CypherCancellationSignal(), continuation = { result ->
                assertIs<RejectedExecutionException>(result.exceptionOrNull())
                rejectedCallbacks.incrementAndGet()
            }) {
                queuedExecuted.set(true)
                "must not run"
            }
            guard.close()
            val failure = assertFailsWith<ExecutionException> {
                queued.completion.get(5, TimeUnit.SECONDS)
            }
            assertIs<RejectedExecutionException>(failure.cause)
            assertEquals("Cypher query guard is closed", failure.cause?.message)
            assertEquals(1, rejectedCallbacks.get())
            assertFalse(queuedExecuted.get())
            assertEquals("first", first.completion.get(5, TimeUnit.SECONDS))
            listener.get(5, TimeUnit.SECONDS)
            assertFailsWith<IllegalStateException> { guard.execute { "closed" } }
        } finally {
            releaseWork.countDown()
            releaseListener.countDown()
            guard.close()
        }
    }
}
