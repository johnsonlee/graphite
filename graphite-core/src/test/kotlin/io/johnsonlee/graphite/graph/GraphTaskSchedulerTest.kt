package io.johnsonlee.graphite.graph

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphTaskSchedulerTest {
    @Test
    fun `saturated owners finish nested own groups with one and four fixed workers`() {
        listOf(1, 4, Runtime.getRuntime().availableProcessors()).distinct().forEach { width ->
            GraphTaskScheduler(width).use { scheduler ->
                val ownersStarted = CyclicBarrier(width)
                val owners = scheduler.newGroup<List<Int>>(helpWhileWaiting = false)
                val activePeak = AtomicInteger()
                val futures = (0 until width).map { owner ->
                    owners.submit(Callable {
                        ownersStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        val leaves = scheduler.newGroup<Int>()
                        val tasks = (0 until width + 2).map { leaf ->
                            leaves.submit(Callable {
                                activePeak.accumulateAndGet(aliveWorkers(scheduler), ::maxOf)
                                owner * 100 + leaf
                            })
                        }
                        leaves.awaitAll()
                        tasks.map { it.get() }
                    })
                }
                futures.forEachIndexed { owner, future ->
                    assertEquals((0 until width + 2).map { owner * 100 + it }, future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }
                assertEquals(width, aliveWorkers(scheduler)) // Includes idle threads after completion.
                assertEquals(width, activePeak.get())
            }
        }
    }

    @Test
    fun `owner helps only awaited group and restores context`() {
        GraphTaskScheduler(1).use { scheduler ->
            val owners = scheduler.newGroup<Boolean>(helpWhileWaiting = false)
            val siblingRan = AtomicBoolean()
            val result = owners.submit(Callable {
                val parent = GraphTaskContext.current
                val sibling = scheduler.newGroup<Int>()
                sibling.submit(Callable { siblingRan.set(true); 7 })
                val own = scheduler.newGroup<Int>()
                val value = own.submit(Callable {
                    assertTrue(GraphTaskContext.current!!.isHelper)
                    assertFalse(siblingRan.get())
                    11
                }).get()
                assertEquals(11, value)
                assertTrue(GraphTaskContext.current === parent)
                assertFalse(siblingRan.get())
                sibling.cancelAndJoin()
                true
            })
            assertTrue(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(siblingRan.get())
        }
    }

    @Test
    fun `queued cancellation never enters callable or publishes twice`() {
        GraphTaskScheduler(1).use { scheduler ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val group = scheduler.newGroup<Int>()
            try {
                val first = group.submit(Callable { started.countDown(); release.await(); 1 })
                assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val entered = AtomicBoolean()
                val queued = group.submit(Callable { entered.set(true); 2 })
                assertTrue(queued.cancel(true))
                assertFalse(queued.cancel(true))
                assertTrue(queued.isDone)
                assertTrue(group.poll() === queued)
                assertNull(group.poll())
                assertFailsWith<CancellationException> { queued.get() }
                release.countDown()
                assertEquals(1, first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(entered.get())
            } finally {
                release.countDown()
                group.cancelAndJoin()
            }
        }
    }

    @Test
    fun `running cancellation waits for finally and cannot interrupt a reused worker`() {
        GraphTaskScheduler(1).use { scheduler ->
            val started = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val releaseFinally = CountDownLatch(1)
            val group = scheduler.newGroup<Int>()
            try {
                val task = group.submit(Callable {
                    try {
                        started.countDown()
                        CountDownLatch(1).await()
                    } catch (_: InterruptedException) {
                        interrupted.countDown()
                    } finally {
                        releaseFinally.await()
                    }
                    1
                })
                assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertTrue(task.cancel(true))
                assertTrue(interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(task.isDone)
                assertNull(group.poll())
                releaseFinally.countDown()
                assertFailsWith<CancellationException> { task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                val reusedStarted = CountDownLatch(1)
                val releaseReused = CountDownLatch(1)
                val reused = scheduler.newGroup<Boolean>().submit(Callable {
                    reusedStarted.countDown()
                    releaseReused.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    Thread.currentThread().isInterrupted
                })
                assertTrue(reusedStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(task.cancel(true))
                releaseReused.countDown()
                assertFalse(reused.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            } finally {
                releaseFinally.countDown()
                group.cancelAndJoin()
            }
        }
    }

    @Test
    fun `local helper cancellation preserves parent interruption and inline result is not queued`() {
        GraphTaskScheduler(1).use { scheduler ->
            val owner = scheduler.newGroup<Boolean>(helpWhileWaiting = false)
            val result = owner.submit(Callable {
                val parent = GraphTaskContext.current
                val local = scheduler.newGroup<Int>()
                val entered = CountDownLatch(1)
                val cancelDone = CountDownLatch(1)
                lateinit var task: GraphTask<Int>
                task = local.submit(Callable {
                    entered.countDown()
                    assertTrue(cancelDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    assertTrue(GraphTaskContext.current!!.isCancelled)
                    assertFalse(Thread.currentThread().isInterrupted)
                    8
                })
                val canceller = Thread {
                    check(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    task.cancel(true)
                    cancelDone.countDown()
                }
                canceller.start()
                try {
                    assertFailsWith<CancellationException> { task.get() }
                } finally {
                    canceller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                }
                assertFalse(parent!!.isCancelled)
                assertTrue(GraphTaskContext.current === parent)
                assertEquals(9, local.runInline(Callable { 9 }))
                assertTrue(local.poll() === task)
                assertNull(local.poll())
                !Thread.currentThread().isInterrupted
            })
            assertTrue(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `ancestor cancellation remains visible in nested inline context`() {
        GraphTaskScheduler(1).use { scheduler ->
            val entered = CountDownLatch(1)
            val inspect = CountDownLatch(1)
            val observed = AtomicBoolean()
            val owner = scheduler.newGroup<Int>()
            try {
                val task = owner.submit(Callable {
                    val child = scheduler.newGroup<Int>()
                    child.runInline(Callable {
                        entered.countDown()
                        try {
                            inspect.await()
                        } catch (_: InterruptedException) {
                            // Deliberately clear interruption to prove ancestry, not just the flag.
                        }
                        observed.set(GraphTaskContext.current!!.isCancelled)
                        3
                    })
                })
                assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                task.cancel(true)
                inspect.countDown()
                assertFailsWith<CancellationException> { task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                assertTrue(observed.get())
            } finally {
                inspect.countDown()
                owner.cancelAndJoin()
            }
        }
    }

    @Test
    fun `groups in one lane share the background limit while all idle threads count`() {
        GraphTaskScheduler(4).use { scheduler ->
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            val entered = AtomicInteger()
            val groups = List(4) { scheduler.newGroup<Int>(2, "segments-2") }
            try {
                val tasks = groups.map { group ->
                    group.submit(Callable {
                        val value = entered.incrementAndGet()
                        started.countDown()
                        release.await()
                        value
                    })
                }
                assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertEquals(2, entered.get())
                assertEquals(4, aliveWorkers(scheduler))
                release.countDown()
                assertEquals(setOf(1, 2, 3, 4), tasks.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.toSet())
                assertEquals(4, aliveWorkers(scheduler))
            } finally {
                release.countDown()
                groups.forEach { it.cancelAndJoin() }
            }
        }
    }

    @Test
    fun `ancestor cancellation interrupts registered background child and drains its finally`() {
        GraphTaskScheduler(2).use { scheduler ->
            val childStarted = CountDownLatch(1)
            val childInterrupted = CountDownLatch(1)
            val releaseFinally = CountDownLatch(1)
            val owners = scheduler.newGroup<Int>(helpWhileWaiting = false)
            try {
                val owner = owners.submit(Callable {
                    val children = scheduler.newGroup<Int>()
                    children.submit(Callable {
                        try {
                            childStarted.countDown()
                            CountDownLatch(1).await()
                        } catch (_: InterruptedException) {
                            childInterrupted.countDown()
                        } finally {
                            releaseFinally.await()
                        }
                        2
                    })
                    try {
                        // Wait for the child to obtain its own background lease, not an owner helper.
                        childStarted.await()
                        children.awaitAll()
                    } finally {
                        children.cancelAndJoin()
                    }
                    1
                })
                assertTrue(childStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                owner.cancel(true)
                assertTrue(childInterrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(owner.isDone)
                releaseFinally.countDown()
                assertFailsWith<CancellationException> { owner.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                releaseFinally.countDown()
                owners.cancelAndJoin()
            }
        }
    }

    @Test
    fun `already completed result survives interruption between notification and get`() {
        GraphTaskScheduler(1).use { scheduler ->
            val group = scheduler.newGroup<Int>()
            val task = group.submit(Callable { 17 })
            assertEquals(17, task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(group.awaitNext() === task)
            Thread.currentThread().interrupt()
            try {
                assertEquals(17, task.get())
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(17, task.get(0, TimeUnit.NANOSECONDS))
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
            assertNull(group.poll())
        }
    }

    @Test
    fun `parent completion closes unjoined children and rejects its expired context`() {
        GraphTaskScheduler(2).use { scheduler ->
            val started = CountDownLatch(1)
            val returned = CountDownLatch(1)
            val release = CountDownLatch(1)
            val childExited = AtomicBoolean()
            val context = java.util.concurrent.atomic.AtomicReference<GraphTaskContext>()
            val group = scheduler.newRootGroup<Int>()
            try {
                val parent = group.submit(Callable {
                    context.set(GraphTaskContext.current)
                    scheduler.newGroup<Int>().submit(Callable {
                        try {
                            started.countDown()
                            release.await()
                        } finally {
                            childExited.set(true)
                        }
                        2
                    })
                    check(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    returned.countDown()
                    1
                })
                assertTrue(returned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(parent.isDone)
                assertFalse(childExited.get())
                release.countDown()
                assertEquals(1, parent.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertTrue(childExited.get())
                assertFailsWith<IllegalStateException> {
                    scheduler.newGroup<Int>(parentContext = context.get())
                }
            } finally {
                release.countDown()
                group.cancelAndJoin()
            }
        }
    }

    @Test
    fun `parent original failure survives unjoined child cancellation and final cleanup`() {
        GraphTaskScheduler(2).use { scheduler ->
            val started = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val releaseFinally = CountDownLatch(1)
            val original = IllegalArgumentException("parent original failure")
            val group = scheduler.newRootGroup<Int>()
            try {
                val parent = group.submit(Callable<Int> {
                    scheduler.newGroup<Int>().submit(Callable {
                        try {
                            started.countDown()
                            CountDownLatch(1).await()
                        } catch (_: InterruptedException) {
                            interrupted.countDown()
                        } finally {
                            releaseFinally.await()
                        }
                        2
                    })
                    check(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    throw original
                })
                assertTrue(interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(parent.isDone)
                releaseFinally.countDown()
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
                    parent.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause === original)
            } finally {
                releaseFinally.countDown()
                group.cancelAndJoin()
            }
        }
    }

    @Test
    fun `queued contexts reject children and closed phases release parent references`() {
        GraphTaskScheduler(1).use { scheduler ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val group = scheduler.newRootGroup<Int>()
            try {
                val blocker = group.submit(Callable { started.countDown(); release.await(); 1 })
                assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val queued = group.submit(Callable { 2 })
                assertFailsWith<IllegalStateException> {
                    scheduler.newGroup<Int>(parentContext = queued.context)
                }
                assertTrue(queued.children.isEmpty())
                queued.cancel(true)
                release.countDown()
                assertEquals(1, blocker.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val parentReference = java.util.concurrent.atomic.AtomicReference<GraphTask<Int>>()
                val begin = CountDownLatch(1)
                val parent = group.submit(Callable {
                    begin.await()
                    var sum = 0
                    repeat(3) { index ->
                        val phase = scheduler.newGroup<Int>()
                        assertEquals(1, parentReference.get().children.size)
                        sum += phase.runInline(Callable { index })
                        phase.close()
                        assertTrue(parentReference.get().children.isEmpty())
                    }
                    val cancelled = scheduler.newGroup<Int>()
                    cancelled.submit(Callable { error("queued phase must not execute") })
                    cancelled.cancelAndJoin()
                    assertTrue(parentReference.get().children.isEmpty())
                    sum
                })
                parentReference.set(parent)
                begin.countDown()
                assertEquals(3, parent.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertTrue(parent.children.isEmpty())
            } finally {
                release.countDown()
                group.cancelAndJoin()
            }
        }
    }

    @Test
    fun `full request admission progresses through owned graph and storage roles within live cap`() {
        listOf(1, 4, Runtime.getRuntime().availableProcessors()).distinct().forEach { width ->
            GraphTaskScheduler(width).use { scheduler ->
                val requestWidth = (width - 1).coerceAtLeast(1)
                val requestsStarted = CyclicBarrier(requestWidth)
                val sourcesStarted = CyclicBarrier(requestWidth)
                val requests = scheduler.newRequestGroup<List<GraphTaskRole>>()
                val results = (0 until requestWidth).map { requestIndex ->
                    requests.submit(Callable {
                        val roles = mutableListOf(GraphTaskContext.current!!.role)
                        requestsStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        val sources = scheduler.newRootGroup<Int>()
                        val source = sources.submit(Callable {
                            roles += GraphTaskContext.current!!.role
                            assertTrue(GraphTaskContext.current!!.isHelper)
                            sourcesStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            val storage = scheduler.newGroup<Int>()
                            val leaf = storage.submit(Callable {
                                roles += GraphTaskContext.current!!.role
                                assertEquals(width, aliveWorkers(scheduler))
                                requestIndex
                            })
                            val value = leaf.get()
                            storage.close()
                            assertEquals(GraphTaskRole.GRAPH_SOURCE, GraphTaskContext.current!!.role)
                            value
                        })
                        assertEquals(requestIndex, source.get())
                        sources.close()
                        assertEquals(GraphTaskRole.REQUEST, GraphTaskContext.current!!.role)
                        roles
                    })
                }
                results.forEach { result ->
                    assertEquals(
                        listOf(GraphTaskRole.REQUEST, GraphTaskRole.GRAPH_SOURCE, GraphTaskRole.STORAGE),
                        result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    )
                }
                assertEquals(width, aliveWorkers(scheduler))
            }
        }
    }

    @Test
    fun `request cancellation reaches nested helper without releasing before final exit`() {
        GraphTaskScheduler(1).use { scheduler ->
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val releaseFinally = CountDownLatch(1)
            val cancelledStorage = AtomicBoolean()
            val requests = scheduler.newRequestGroup<Int>()
            try {
                val request = requests.submit(Callable {
                    val graph = scheduler.newRootGroup<Int>()
                    graph.submit(Callable {
                        val storage = scheduler.newGroup<Int>()
                        storage.submit(Callable {
                            try {
                                assertEquals(GraphTaskRole.STORAGE, GraphTaskContext.current!!.role)
                                entered.countDown()
                                CountDownLatch(1).await()
                            } catch (_: InterruptedException) {
                                cancelledStorage.set(GraphTaskContext.current!!.isCancelled)
                                interrupted.countDown()
                            } finally {
                                releaseFinally.await()
                            }
                            1
                        }).get()
                    }).get()
                })
                assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                request.cancel(true)
                assertTrue(interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(request.isDone)
                releaseFinally.countDown()
                assertFailsWith<CancellationException> { request.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                assertTrue(cancelledStorage.get())
                assertEquals(1, aliveWorkers(scheduler))
            } finally {
                releaseFinally.countDown()
                requests.cancelAndJoin()
            }
        }
    }

    @Test
    fun `request explicitly drains captured failure descendants before publishing outcome`() {
        GraphTaskScheduler(2).use { scheduler ->
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val releaseFinally = CountDownLatch(1)
            val published = AtomicBoolean()
            val original = IllegalStateException("captured continuation failure")
            val requests = scheduler.newRequestGroup<Throwable?>()
            try {
                val request = requests.submit(Callable {
                    scheduler.newGroup<Int>().submit(Callable {
                        try {
                            entered.countDown()
                            CountDownLatch(1).await()
                        } catch (_: InterruptedException) {
                            interrupted.countDown()
                        } finally {
                            releaseFinally.await()
                        }
                        1
                    })
                    check(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    val context = GraphTaskContext.current!!
                    val outcome = context.finishChildren(original)
                    assertFailsWith<IllegalStateException> { scheduler.newGroup<Int>() }
                    assertTrue(context.finishChildren(outcome) === original)
                    published.set(true)
                    outcome
                })
                assertTrue(interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(published.get())
                assertFalse(request.isDone)
                releaseFinally.countDown()
                assertTrue(request.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) === original)
                assertTrue(published.get())
            } finally {
                releaseFinally.countDown()
                requests.cancelAndJoin()
            }
        }
    }

    @Test
    fun `helpOne obeys request role own group and one task bound without external participation`() {
        GraphTaskScheduler(1).use { scheduler ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val externalRuns = AtomicInteger()
            val requests = scheduler.newRequestGroup<Int>()
            try {
                val request = requests.submit(Callable {
                    entered.countDown()
                    check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    val own = scheduler.newRootGroup<Int>()
                    val other = scheduler.newRootGroup<Int>()
                    val siblingRuns = AtomicInteger()
                    val first = own.submit(Callable { 11 })
                    val second = own.submit(Callable { 22 })
                    other.submit(Callable { siblingRuns.incrementAndGet() })
                    assertTrue(own.helpOne())
                    assertTrue(first.isDone)
                    assertFalse(second.isDone)
                    assertEquals(11, first.get())
                    assertEquals(0, siblingRuns.get())
                    assertEquals(GraphTaskRole.REQUEST, GraphTaskContext.current!!.role)
                    assertTrue(own.helpOne())
                    assertEquals(22, second.get())
                    assertFalse(own.helpOne())
                    own.close()
                    other.cancelAndJoin()
                    33
                })
                assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val external = scheduler.newGroup<Int>()
                val queued = external.submit(Callable { externalRuns.incrementAndGet() })
                assertFalse(external.helpOne())
                assertFalse(queued.isDone)
                assertEquals(0, externalRuns.get())
                external.cancelAndJoin()
                release.countDown()
                assertEquals(33, request.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            } finally {
                release.countDown()
                requests.cancelAndJoin()
            }
        }
    }

    private fun aliveWorkers(scheduler: GraphTaskScheduler): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith(scheduler.threadNamePrefix) }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
