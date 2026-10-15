package io.johnsonlee.graphite.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("MagicNumber", "StringLiteralDuplication")
class SingleGraphCorrectnessTest {
    @Test
    fun `explorer plan retains every original check and correctness settings`() {
        val run = SingleGraphCorrectness.prepare(arrayOf("explorer"))
        val suite = assertIs<ExplorerMemoryBenchmark>(run.suite)
        assertTrue(suite.correctnessOnly)
        assertEquals("MAPPED", suite.loadMode)
        assertEquals(3, suite.repeats)
        assertEquals(512, suite.sampledNodeCount)
        assertEquals(32, suite.waterlineWarmupCycles)
        assertEquals(256, suite.waterlineMeasuredCycles)
        assertIs<ExplorerMemoryCounters>(run.counters)
        assertEquals(suite::setup, run.setup)
        assertEquals(suite::tearDown, run.tearDown)
        assertEquals(listOf(
            suite::android_initialExplorerSession, suite::android_browserForwardExploration,
            suite::android_longRunningExplorerWaterline, suite::android_incomingExplorerWaterline
        ), run.checks)
    }

    @Test
    fun `capacity plan retains rejection cancellation and recovery check`() {
        val run = SingleGraphCorrectness.prepare(arrayOf("capacity"))
        val suite = assertIs<CypherCapacityBenchmark>(run.suite)
        assertTrue(suite.correctnessOnly)
        assertIs<CypherCapacityBenchmarkCounters>(run.counters)
        assertEquals(suite::setup, run.setup)
        assertEquals(suite::tearDown, run.tearDown)
        assertEquals(listOf(suite::fourWorstCaseQueriesRejectCancelAndRecover), run.checks)
    }

    @Test
    fun `method plans retain supported graph counts scenario and exact check`() {
        for (count in listOf(4, 17, 36)) {
            val run = SingleGraphCorrectness.prepare(arrayOf("methods", count.toString(), "contains"))
            val suite = assertIs<MethodDiscoveryCompatibilityBenchmark>(run.suite)
            assertTrue(suite.correctnessOnly)
            assertEquals(count, suite.graphCount)
            assertEquals("contains", suite.scenario)
            assertIs<MethodCompatibilityCounters>(run.counters)
            assertEquals(suite::setup, run.setup)
            assertEquals(suite::tearDown, run.tearDown)
            assertEquals(listOf(suite::methodScenarioGate), run.checks)
        }
    }

    @Test
    fun `invalid suite and method arguments cannot execute or publish success`() {
        assertFailsWith<IllegalArgumentException> { SingleGraphCorrectness.main(emptyArray()) }
        val reports = mutableListOf<String>()
        for (args in listOf(
            arrayOf("methods"), arrayOf("methods", "4"), arrayOf("methods", "4", "count", "extra"),
            arrayOf("methods", "one", "count"), arrayOf("methods", "1", "count")
        )) {
            assertFailsWith<IllegalArgumentException> { SingleGraphCorrectness.execute(args, report = reports::add) }
        }
        assertFailsWith<IllegalStateException> {
            SingleGraphCorrectness.execute(arrayOf("unknown"), report = reports::add)
        }
        assertEquals(emptyList(), reports)
    }

    @Test
    fun `lifecycle consumes every check with same counters and reports only after cleanup`() {
        val fixture = LifecycleFixture()
        val args = arrayOf("methods", "17", "contains")
        SingleGraphCorrectness.execute(args, prepare = { received ->
            assertSame(args, received)
            fixture.run()
        }, report = fixture.events::add)
        assertEquals(listOf("setup", "first", "second", "close", "CORRECTNESS_PASS\tmethods\t17\tcontains"), fixture.events)
        assertEquals(2, fixture.counters.single())
    }

    @Test
    fun `setup check and cleanup failures never publish success`() {
        for (failure in listOf("setup", "first", "second", "close")) {
            val fixture = LifecycleFixture(failure)
            val thrown = assertFailsWith<IllegalStateException> {
                SingleGraphCorrectness.execute(arrayOf("explorer"), prepare = { fixture.run() }, report = fixture.events::add)
            }
            assertSame(fixture.failure, thrown)
            val expected = when (failure) {
                "setup" -> listOf("setup", "close")
                "first" -> listOf("setup", "first", "close")
                else -> listOf("setup", "first", "second", "close")
            }
            assertEquals(expected, fixture.events)
        }
    }

    private class LifecycleFixture(private val failAt: String? = null) {
        val events = mutableListOf<String>()
        val counters = mutableListOf(0)
        val failure = IllegalStateException("fixture failure")

        fun run() = CorrectnessRun(this, counters, ::setup, listOf(::first, ::second), ::close)

        fun setup() = event("setup")
        fun close() = event("close")

        fun first(actual: MutableList<Int>): Long = check("first", actual)
        fun second(actual: MutableList<Int>): Long = check("second", actual)

        private fun check(name: String, actual: MutableList<Int>): Long {
            assertSame(counters, actual)
            event(name)
            actual[0]++
            return actual[0].toLong()
        }

        private fun event(name: String) {
            events.add(name)
            if (name == failAt) throw failure
        }
    }
}
