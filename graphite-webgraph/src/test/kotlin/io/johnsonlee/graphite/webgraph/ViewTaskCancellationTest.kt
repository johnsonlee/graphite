package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.GraphTaskScheduler
import io.johnsonlee.graphite.graph.GraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ViewTaskCancellationTest {
    @Test
    fun `cancelled checksum loading stops work and leaves the persisted index loadable`() {
        withFixture { fixture ->
            val baseline = CountingConsumer()
            fixture.load(baseline).use { view ->
                assertEquals(fixture.expected, fixture.nodes(view).toList())
            }
            val cancelledWork = cancelWhileBlocked(Phase.CHECKSUM) { consumer ->
                fixture.load(consumer).use { }
            }
            assertTrue(cancelledWork in 1 until baseline.total.get(), "Cancelled load must stop before a full checksum")
            fixture.load(null).use { view ->
                assertEquals(fixture.expected, fixture.nodes(view).toList())
            }
        }
    }

    @Test
    fun `cancelled posting validation does not cache an incomplete range`() {
        withFixture { fixture ->
            fixture.load(null).use { view ->
                assertTrue(view.validatedPostingRangeBytes() > 0L, "Exercise an allocated validation cache")
                assertEquals(0, view.validatedPostingRangeCount())
                val cancelledWork = cancelWhileBlocked(Phase.POSTING_VALIDATION) { consumer ->
                    fixture.nodes(view, consumer)
                }
                assertTrue(cancelledWork in 1 until NODE_COUNT.toLong(), "Cancelled validation must stop scanning")
                assertEquals(0, view.validatedPostingRangeCount(), "Partial validation must not publish a cache entry")
                assertEquals(fixture.expected, fixture.nodes(view).toList())
                assertEquals(1, view.validatedPostingRangeCount())
                assertEquals(fixture.expected, fixture.nodes(view).toList())
            }
        }
    }

    @Test
    fun `cancelled lazy merge stops after a prefix without interrupting the callback`() {
        withFixture { fixture ->
            fixture.load(null).use { view ->
                assertTrue(view.validatedPostingRangeBytes() > 0L, "Exercise the already validated range path")
                assertEquals(fixture.expected, fixture.nodes(view).toList())
                assertEquals(1, view.validatedPostingRangeCount())
                val emitted = mutableListOf<Int>()
                val cancelledWork = cancelWhileBlocked(Phase.LAZY_MERGE) { consumer ->
                    fixture.nodes(view, consumer).forEach(emitted::add)
                }
                assertTrue(cancelledWork in 1 until NODE_COUNT.toLong(), "Cancelled merge must stop remaining work")
                assertTrue(emitted.size in 1 until NODE_COUNT)
                assertEquals(fixture.expected.take(emitted.size), emitted)
                assertEquals(1, view.validatedPostingRangeCount())
                assertEquals(fixture.expected, fixture.nodes(view).toList())
            }
        }
    }

    private fun cancelWhileBlocked(phase: Phase, action: (GraphWorkBatchConsumer) -> Unit): Long {
        val consumer = BlockingConsumer(phase)
        val group = GraphTaskScheduler.shared.newRootGroup<Unit>()
        val task = group.submit(Callable { action(consumer) })
        try {
            assertTrue(consumer.entered.await(5, TimeUnit.SECONDS), "Must reach the intended callback phase: $phase")
            assertTrue(task.cancel(false))
            assertFalse(task.isDone, "Held callback must keep the task incomplete")
            consumer.release.countDown()
            assertFailsWith<CancellationException> { task.get(5, TimeUnit.SECONDS) }
            assertTrue(task.isDone)
            assertEquals(false, consumer.interrupted.get(), "cancel(false) must not interrupt the callback")
            return consumer.total.get()
        } finally {
            consumer.release.countDown()
            group.cancelAndJoin()
            group.close()
        }
    }

    private enum class Phase {
        CHECKSUM,
        POSTING_VALIDATION,
        LAZY_MERGE;

        fun matches(stack: Array<StackTraceElement>): Boolean = when (this) {
            CHECKSUM -> stack.any { frame ->
                frame.className == VALIDATOR_CLASS && frame.methodName in setOf("updateInts", "updateLongs")
            }
            POSTING_VALIDATION -> stack.any { frame ->
                frame.className == VIEW_CLASS && frame.methodName == "validatedPostingCursor"
            }
            LAZY_MERGE -> stack.any { frame ->
                frame.className.startsWith(VIEW_CLASS + "\$matchingNodeIds\$") && frame.methodName == "invokeSuspend"
            }
        }
    }

    private open class CountingConsumer : GraphWorkBatchConsumer {
        val total = AtomicLong()
        override fun consume() = consume(1L)
        override fun consume(workUnits: Long) {
            total.addAndGet(workUnits)
        }
    }

    private class BlockingConsumer(private val phase: Phase) : CountingConsumer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicReference<Boolean?>()

        override fun consume(workUnits: Long) {
            super.consume(workUnits)
            if (entered.count == 0L || phase == Phase.LAZY_MERGE && total.get() < LAZY_BLOCK_AFTER) return
            if (!phase.matches(Thread.currentThread().stackTrace)) return
            entered.countDown()
            release.await()
            interrupted.set(Thread.currentThread().isInterrupted)
        }
    }

    private class Fixture(private val path: Path, private val strings: StringTable) {
        val expected = (0 until NODE_COUNT).toList()
        private val identity = ByteArray(32) { it.toByte() }
        private val predicate = StringPropertyPredicate(
            "caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "key"
        )

        fun load(consumer: GraphWorkConsumer?): MappedCallSiteStringIndexView = assertNotNull(
            MappedCallSiteStringIndexView.load(
                path, strings.size(), NODE_COUNT, identity, strings, NODE_COUNT, Int::toLong, consumer
            )
        )

        fun nodes(view: MappedCallSiteStringIndexView, consumer: GraphWorkConsumer? = null): Sequence<Int> =
            assertNotNull(view.matchingNodeIds(listOf(predicate), listOf(intArrayOf(strings.findId(VALUE))), consumer))
    }

    private fun withFixture(action: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("view-task-cancellation")
        try {
            val strings = StringTable.build(listOf(VALUE, "key-unused"), directory)
            val input = CallSiteIndexPersistenceInput(NODE_COUNT)
            val method = MethodDescriptor(TypeDescriptor(VALUE), VALUE, emptyList(), TypeDescriptor("void"))
            repeat(NODE_COUNT) { index ->
                input.add(CallSiteNode(NodeId(index), method, method, index, null, emptyList()), strings)
            }
            val path = directory.resolve("callsite.index")
            val identity = ByteArray(32) { it.toByte() }
            assertNotNull(input.build(strings, identity)).use { index ->
                assertTrue(index.prepareTrigramPostings())
                DataOutputStream(Files.newOutputStream(path)).use(index::writePersistent)
            }
            action(Fixture(path, StringTable.load(directory)))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val NODE_COUNT = 8_192
        const val LAZY_BLOCK_AFTER = 128L
        const val VALUE = "Key"
        const val VIEW_CLASS = "io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexView"
        const val VALIDATOR_CLASS = "io.johnsonlee.graphite.webgraph.PersistentIndexViewValidator"
    }
}
