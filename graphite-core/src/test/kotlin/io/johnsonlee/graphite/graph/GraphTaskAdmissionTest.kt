package io.johnsonlee.graphite.graph

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphTaskAdmissionTest {
    @Test
    fun `returned callable retains its slot through child drain and cancellation`() {
        for (cancel in listOf(false, true)) {
            GraphTaskScheduler(3).use { scheduler -> verifyChildDrain(scheduler, cancel) }
        }
    }

    private fun verifyChildDrain(scheduler: GraphTaskScheduler, cancel: Boolean) {
        val childStarted = CountDownLatch(1)
        val callableReturned = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = LinkedBlockingQueue<String>()
        val roots = scheduler.newRootGroup<Int>(maxConcurrentTasks = 1)
        val canaries = scheduler.newGroup<Unit>()
        val parent = roots.submit(Callable {
            scheduler.newGroup<Int>().submit(Callable {
                childStarted.countDown()
                release.await()
                2
            })
            check(childStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            callableReturned.countDown()
            1
        })
        try {
            assertTrue(callableReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            if (cancel) assertTrue(parent.cancel(false))
            assertFalse(parent.isDone)
            // The owner and its started child occupy two workers. Queue both entries before
            // the only free worker can claim either: a full root must be skipped for storage.
            val replacement = synchronized(scheduler.monitor) {
                val task = roots.submit(Callable {
                    events.add("replacement")
                    release.await()
                    3
                })
                canaries.submit(Callable { events.add("canary"); Unit })
                task
            }
            assertEquals("canary", events.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(replacement.isDone)
            assertEquals(1, synchronized(scheduler.monitor) { roots.runningTasks })
            release.countDown()
            if (cancel) {
                assertFailsWith<CancellationException> { parent.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            } else {
                assertEquals(1, parent.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
            assertEquals(3, replacement.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("replacement", events.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(0, synchronized(scheduler.monitor) { roots.runningTasks })
        } finally {
            release.countDown()
            roots.cancelAndJoin()
            canaries.cancelAndJoin()
        }
    }

    @Test
    fun `request helper shares graph admission with background owners and resumes after exit`() {
        GraphTaskScheduler(3).use { scheduler ->
            val requests = scheduler.newRequestGroup<Int>()
            val firstStarted = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val request = requests.submit(Callable {
                    val graphs = scheduler.newRootGroup<Int>(maxConcurrentTasks = 1)
                    try {
                        graphs.submit(Callable {
                            firstStarted.countDown()
                            release.await()
                            1
                        })
                        check(firstStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        graphs.submit(Callable { nestedStorage(scheduler) })
                        // REQUEST + the first GRAPH_SOURCE fill the shared P-1 root lane.
                        // The queued source can only run here if help wrongly ignores G=1.
                        assertFalse(graphs.helpOne())
                        assertEquals(GraphTaskRole.REQUEST, GraphTaskContext.current?.role)
                        release.countDown()
                        graphs.awaitNext().get() + graphs.awaitNext().get()
                    } finally {
                        release.countDown()
                        graphs.cancelAndJoin()
                    }
                })
                assertEquals(3, request.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            } finally {
                release.countDown()
                requests.cancelAndJoin()
            }
        }
    }

    private fun nestedStorage(scheduler: GraphTaskScheduler): Int {
        assertEquals(GraphTaskRole.GRAPH_SOURCE, GraphTaskContext.current?.role)
        val storage = scheduler.newGroup<Int>()
        return try {
            storage.runInline(Callable {
                assertEquals(GraphTaskRole.STORAGE, GraphTaskContext.current?.role)
                2
            })
        } finally {
            storage.close()
        }
    }

    @Test
    fun `full inline group fails before registering a task and can be used after release`() {
        GraphTaskScheduler(1).use { scheduler ->
            val group = scheduler.newGroup<Int>(maxConcurrentTasks = 1)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val inlineRan = AtomicBoolean()
            val background = group.submit(Callable {
                started.countDown()
                release.await()
                7
            })
            try {
                assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFailsWith<IllegalStateException> {
                    group.runInline(Callable { inlineRan.set(true); 9 })
                }
                assertFalse(inlineRan.get())
                assertNull(GraphTaskContext.current)
                synchronized(scheduler.monitor) {
                    assertEquals(1, group.tasks.size)
                    assertEquals(1, group.runningTasks)
                }
                release.countDown()
                assertEquals(7, background.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertEquals(9, group.runInline(Callable { 9 }))
                assertSame(background, group.poll())
                assertNull(group.poll())
                assertEquals(0, synchronized(scheduler.monitor) { group.runningTasks })
            } finally {
                release.countDown()
                group.cancelAndJoin()
            }
        }
    }

    @Test
    fun `uncapped storage still accepts caller participation beside its background worker`() {
        GraphTaskScheduler(1).use { scheduler ->
            val group = scheduler.newGroup<Int>()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val caller = Thread.currentThread()
            val background = group.submit(Callable {
                started.countDown()
                release.await()
                13
            })
            try {
                assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertEquals(21, group.runInline(Callable {
                    assertSame(caller, Thread.currentThread())
                    assertEquals(GraphTaskRole.STORAGE, GraphTaskContext.current?.role)
                    assertEquals(2, synchronized(scheduler.monitor) { group.runningTasks })
                    21
                }))
                assertFalse(background.isDone)
                release.countDown()
                assertEquals(13, background.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            } finally {
                release.countDown()
                group.cancelAndJoin()
            }
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
