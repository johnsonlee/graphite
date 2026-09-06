package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.GraphTaskScheduler
import io.johnsonlee.graphite.graph.GraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MappedExactStringIdsSplitTest {
    @Test
    fun `split discovery preserves ordered ids and charges repeated predicate keys once`() {
        withView(4_096) { fixture ->
            val predicates = listOf(
                predicate("caller_class"),
                predicate("callee_name"),
                predicate("caller_name", transform = null),
                predicate("callee_class", "key000"),
                predicate("caller_name")
            )
            val serial = RecordingConsumer()
            val split = SplitConsumer(1)
            val expected = predicates.map(fixture::expected)
            assertMatches(expected, fixture.view.exactMatchingStringIds(predicates, serial))
            assertMatches(expected, fixture.view.exactMatchingStringIds(predicates, split))
            assertEquals(serial.total(), split.total())
            val unique = RecordingConsumer()
            fixture.view.exactMatchingStringIds(listOf(predicates[0], predicates[2], predicates[3]), unique)
            assertEquals(unique.total(), split.total())
            if (GraphTaskScheduler.shared.parallelism > 1) {
                assertTrue(split.workByThread.keys.any { worker ->
                    worker.name.startsWith(GraphTaskScheduler.shared.threadNamePrefix)
                })
            }
        }
    }

    @Test
    fun `anchor threshold keeps small discovery serial and splits two full segments`() {
        for (count in listOf(2_047, 2_048)) {
            withView(count) { fixture ->
                val consumer = SplitConsumer(1)
                assertMatches(
                    listOf(fixture.expected(predicate())),
                    fixture.view.exactMatchingStringIds(listOf(predicate()), consumer)
                )
                assertEquals(count + 1L, consumer.total())
                val split = count == 2_048 && GraphTaskScheduler.shared.parallelism > 1
                val expectedWork = if (split) listOf(1_024L, 1_025L) else listOf(count + 1L)
                assertEquals(expectedWork, consumer.workByThread.values.sorted())
            }
        }
    }

    @Test
    fun `zero background budget stays serial and large budget respects shared width and segment size`() {
        withView(4_096) { fixture ->
            val caller = Thread.currentThread()
            for (budget in listOf(0, Int.MAX_VALUE)) {
                val consumer = SplitConsumer(budget)
                assertMatches(
                    listOf(fixture.expected(predicate())),
                    fixture.view.exactMatchingStringIds(listOf(predicate()), consumer)
                )
                val segments = if (budget == 0) 1 else minOf(4, GraphTaskScheduler.shared.parallelism)
                assertEquals(4_097L, consumer.total())
                assertEquals((4_096L + segments - 1) / segments + 1L, consumer.workByThread[caller])
                assertTrue(consumer.workByThread.size <= segments)
                consumer.workByThread.filterKeys { it !== caller }.forEach { (worker, work) ->
                    assertTrue(worker.name.startsWith(GraphTaskScheduler.shared.threadNamePrefix))
                    assertTrue(work >= 1_024L)
                }
            }
        }
    }

    @Test
    fun `budget failure waits for an interrupted running segment to exit`() {
        if (GraphTaskScheduler.shared.parallelism == 1) return
        withView(8_192) { fixture ->
            val blocker = SegmentBlocker()
            val failure = IllegalStateException("test work budget exhausted")
            val outcome = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            val owner = thread(start = false, name = "mapped-exact-budget-owner") {
                val consumer = BudgetConsumer(blocker, failure)
                try {
                    fixture.view.exactMatchingStringIds(listOf(predicate()), consumer)
                } catch (error: Exception) {
                    outcome.set(error)
                } finally {
                    finished.countDown()
                }
            }
            try {
                owner.start()
                assertTrue(blocker.interrupted.await(5, TimeUnit.SECONDS))
                assertEquals(1L, finished.count)
                assertEquals(1, blocker.active.get())
            } finally {
                blocker.release.countDown()
                owner.join(5_000)
            }
            assertFalse(owner.isAlive)
            assertEquals(0, blocker.active.get())
            assertSame(failure, outcome.get())
        }
    }

    @Test
    fun `cancel false keeps parent incomplete until running discovery callbacks exit`() {
        if (GraphTaskScheduler.shared.parallelism == 1) return
        withView(8_192) { fixture ->
            val blockers = SegmentBlocker(expectedEntrants = 2)
            val group = GraphTaskScheduler.shared.newRootGroup<List<IntArray>?>()
            val consumer = object : SplitGraphWorkBatchConsumer {
                override val segmentWorkerCount = 1
                override fun consume() = consume(1L)
                override fun consume(workUnits: Long) {
                    if (workUnits >= 1_024L) blockers.block()
                }
            }
            val task = group.submit(Callable {
                fixture.view.exactMatchingStringIds(listOf(predicate()), consumer)
            })
            try {
                assertTrue(blockers.entered.await(5, TimeUnit.SECONDS))
                assertTrue(task.cancel(false))
                assertFalse(task.isDone)
                assertEquals(2, blockers.active.get())
                assertEquals(1L, blockers.interrupted.count)
                blockers.release.countDown()
                assertFailsWith<CancellationException> { task.get(5, TimeUnit.SECONDS) }
                assertTrue(task.isDone)
                assertEquals(0, blockers.active.get())
            } finally {
                blockers.release.countDown()
                group.cancelAndJoin()
                group.close()
            }
        }
    }

    private class BudgetConsumer(
        private val blocker: SegmentBlocker,
        private val failure: IllegalStateException
    ) : SplitGraphWorkBatchConsumer {
        private val owner = Thread.currentThread()
        override val segmentWorkerCount = 1
        override fun consume() = consume(1L)
        override fun consume(workUnits: Long) {
            if (Thread.currentThread() === owner) {
                if (workUnits >= 1_024L) {
                    assertTrue(blocker.entered.await(5, TimeUnit.SECONDS))
                    throw failure
                }
            } else {
                blocker.block()
            }
        }
    }

    private class SegmentBlocker(expectedEntrants: Int = 1) {
        val entered = CountDownLatch(expectedEntrants)
        val release = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val active = AtomicInteger()

        fun block() {
            active.incrementAndGet()
            entered.countDown()
            var restoreInterrupt = false
            try {
                while (release.count != 0L) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        restoreInterrupt = true
                        interrupted.countDown()
                    }
                }
            } finally {
                active.decrementAndGet()
                if (restoreInterrupt) Thread.currentThread().interrupt()
            }
        }
    }

    private open class RecordingConsumer : GraphWorkBatchConsumer {
        val workByThread = ConcurrentHashMap<Thread, Long>()
        override fun consume() = consume(1L)
        override fun consume(workUnits: Long) {
            workByThread.merge(Thread.currentThread(), workUnits, Long::plus)
        }
        fun total(): Long = workByThread.values.sum()
    }

    private class SplitConsumer(override val segmentWorkerCount: Int) :
        RecordingConsumer(), SplitGraphWorkBatchConsumer

    private class Fixture(
        val view: MappedCallSiteStringIndexView,
        val strings: StringTable,
        val used: List<String>
    ) {
        fun expected(predicate: StringPropertyPredicate): IntArray = used.filter { value ->
            val transformed = if (predicate.transform == StringValueTransform.LOWERCASE) {
                value.lowercase()
            } else {
                value
            }
            transformed.contains(predicate.expected)
        }.map(strings::findId).sorted().toIntArray()
    }

    private fun predicate(
        property: String = "caller_class",
        expected: String = "key",
        transform: StringValueTransform? = StringValueTransform.LOWERCASE
    ) = StringPropertyPredicate(property, transform, StringMatchMode.CONTAINS, expected)

    private fun assertMatches(expected: List<IntArray>, actual: List<IntArray>?) {
        assertNotNull(actual)
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index -> assertContentEquals(expected[index], actual[index]) }
    }

    private fun withView(count: Int, action: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("mapped-exact-split-test")
        try {
            val used = List(count) { index ->
                (if (index % 2 == 0) "Key" else "key") + index.toString().padStart(5, '0')
            }
            val strings = StringTable.build(used + "key-unused", directory)
            val input = CallSiteIndexPersistenceInput(count)
            used.forEachIndexed { index, value ->
                val method = MethodDescriptor(TypeDescriptor(value), value, emptyList(), TypeDescriptor("void"))
                input.add(CallSiteNode(NodeId(index), method, method, index, null, emptyList()), strings)
            }
            val identity = ByteArray(32) { it.toByte() }
            val path = directory.resolve("callsite.index")
            assertNotNull(input.build(strings, identity)).use { index ->
                assertTrue(index.prepareTrigramPostings())
                DataOutputStream(Files.newOutputStream(path)).use(index::writePersistent)
            }
            val loadedStrings = StringTable.load(directory)
            assertNotNull(
                MappedCallSiteStringIndexView.load(
                    path, loadedStrings.size(), count, identity, loadedStrings, count, Int::toLong, null
                )
            ).use { view -> action(Fixture(view, loadedStrings, used)) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
