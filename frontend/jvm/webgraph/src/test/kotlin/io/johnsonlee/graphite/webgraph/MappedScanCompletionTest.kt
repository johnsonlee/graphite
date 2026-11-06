package io.johnsonlee.graphite.webgraph

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MappedScanCompletionTest {
    @Test
    fun `out of order completion preserves structural worker order`() {
        val completion = MappedScanCompletion<Int>(Executor(Runnable::run), AtomicBoolean(), 3) { it }
        completion.executeWave(listOf(Callable { 2 }, Callable { 0 }, Callable { 1 }), inlineFirst = true, trackBackground = false)
        completion.checkFailure()
        assertEquals(listOf<Int?>(0, 1, 2), completion.results)
    }

    @Test
    fun `inline failure drains submitted workers and keeps real failure ahead of cancellation`() {
        val executor = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(2)
        val finished = CountDownLatch(2)
        val failure = IllegalStateException("query failed")
        val abort = AtomicBoolean()
        val completion = MappedScanCompletion<Int>(executor, abort, 3) { it }
        try {
            val background =
                (1..2).map { index ->
                    Callable<Int> {
                        started.countDown()
                        assertTrue(started.await(5, TimeUnit.SECONDS))
                        try {
                            while (!abort.get()) Thread.yield()
                            throw CancellationException("peer failed: $index")
                        } finally {
                            finished.countDown()
                        }
                    }
                }
            completion.executeWave(
                listOf(
                    Callable<Int> {
                        assertTrue(started.await(5, TimeUnit.SECONDS))
                        throw failure
                    }) + background,
                inlineFirst = true,
                trackBackground = false)
            assertEquals(0L, finished.count)
            assertSame(failure, assertFailsWith<IllegalStateException> { completion.checkFailure() })
            assertTrue(completion.failed)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `interrupted collection cleans reservation before restoring interrupt after workers finish`() {
        val executor = Executors.newSingleThreadExecutor()
        val finished = AtomicBoolean()
        val cleaned = AtomicBoolean()
        val completion = MappedScanCompletion<Int>(executor, AtomicBoolean(), 1) { it }
        try {
            Thread.currentThread().interrupt()
            completion.executeWave(
                listOf(
                    Callable<Int> {
                        finished.set(true)
                        0
                    }),
                inlineFirst = false,
                trackBackground = false)
            assertTrue(finished.get())
            assertFalse(Thread.currentThread().isInterrupted)
            assertFailsWith<CancellationException> {
                completion.checkFailure(
                    checkThread = false,
                    beforeFailure = {
                        assertFalse(Thread.currentThread().isInterrupted)
                        cleaned.set(true)
                    })
            }
            assertTrue(cleaned.get())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `property build primary failure wins after cancellation has been drained`() {
        val completion = MappedScanCompletion<Int>(Executor(Runnable::run), AtomicBoolean(), 1) { it }
        completion.executeWave(listOf(Callable { throw CancellationException("peer aborted") }), false, false)
        val primary = IllegalArgumentException("work budget failed")
        assertSame(
            primary, assertFailsWith<IllegalArgumentException> { completion.checkFailure(checkThread = false, primaryFailure = primary) })
    }
}
