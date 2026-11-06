package io.johnsonlee.graphite.cypher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherAggregationFunctionsTest : CypherFunctionsTestFixture() {

    @Test
    fun `count returns number of values`() {
        assertEquals(3L, CypherFunctions.aggregate("count", listOf(1, 2, 3)))
    }

    @Test
    fun `count ignores nulls`() {
        assertEquals(2L, CypherFunctions.aggregate("count", listOf(1, null, 3)))
        assertEquals(0L, CypherFunctions.aggregate("count", listOf(null, null)))
    }

    @Test
    fun `sum of numbers`() {
        assertEquals(6.0, CypherFunctions.aggregate("sum", listOf(1, 2, 3)))
    }

    @Test
    fun `sum ignores nulls`() {
        assertEquals(4.0, CypherFunctions.aggregate("sum", listOf(1, null, 3)))
    }

    @Test
    fun `avg of numbers`() {
        assertEquals(2.0, CypherFunctions.aggregate("avg", listOf(1, 2, 3)))
    }

    @Test
    fun `avg ignores nulls`() {
        assertEquals(2.0, CypherFunctions.aggregate("avg", listOf(1, null, 3)))
    }

    @Test
    fun `avg of empty filtered list returns null`() {
        assertNull(CypherFunctions.aggregate("avg", listOf(null, null)))
    }

    @Test
    fun `min returns smallest value`() {
        assertEquals(1, CypherFunctions.aggregate("min", listOf(3, 1, 2)))
    }

    @Test
    fun `min ignores nulls`() {
        assertEquals(1, CypherFunctions.aggregate("min", listOf(null, 3, 1, null, 2)))
    }

    @Test
    fun `min of empty non-null list returns null`() {
        assertNull(CypherFunctions.aggregate("min", listOf(null)))
    }

    @Test
    fun `max returns largest value`() {
        assertEquals(3, CypherFunctions.aggregate("max", listOf(1, 3, 2)))
    }

    @Test
    fun `collect returns all values as list`() {
        assertEquals(listOf(1, 2, 3), CypherFunctions.aggregate("collect", listOf(1, 2, 3)))
    }

    @Test
    fun `collect ignores nulls`() {
        assertEquals(listOf(1, 3), CypherFunctions.aggregate("collect", listOf(1, null, 3)))
        assertEquals(emptyList<Any?>(), CypherFunctions.aggregate("collect", listOf(null, null)))
    }

    @Test
    fun `tracked aggregates preserve untracked results`() {
        val values = listOf<Any?>(null, 1.0, 2.0, 3.0)
        val aggregateNames = listOf("count", "sum", "avg", "min", "max", "collect", "percentileCont", "percentileDisc", "stdev", "stdevp")
        var cancellationChecks = 0

        for (name in aggregateNames) {
            assertEquals(CypherFunctions.aggregate(name, values), CypherFunctions.aggregate(name, values) { cancellationChecks++ }, name)
        }
        assertTrue(cancellationChecks > 0)
        assertNull(CypherFunctions.aggregate("avg", listOf(null)) {})
        assertNull(CypherFunctions.aggregate("min", listOf(null)) {})
        assertNull(CypherFunctions.aggregate("max", listOf(null)) {})
        assertNull(CypherFunctions.aggregate("percentileCont", listOf(null)) {})
        assertNull(CypherFunctions.aggregate("percentileDisc", listOf(null)) {})
        assertNull(CypherFunctions.aggregate("stdev", listOf(1)) {})
        assertNull(CypherFunctions.aggregate("stdevp", listOf(1)) {})
        assertFailsWith<CypherException> { CypherFunctions.aggregate("unknown", values) {} }
    }

    @Test
    fun `tracked extrema preserve NaN and signed zero ordering`() {
        for (values in listOf(listOf(Double.NaN, 1.0), listOf(1.0, Double.NaN))) {
            assertEquals(1.0, CypherFunctions.aggregate("min", values) {})
            assertTrue((CypherFunctions.aggregate("max", values) {} as Double).isNaN())
        }

        for (values in listOf(listOf(-0.0, 0.0), listOf(0.0, -0.0))) {
            val minimum = CypherFunctions.aggregate("min", values) {} as Double
            val maximum = CypherFunctions.aggregate("max", values) {} as Double
            assertEquals((-0.0).toRawBits(), minimum.toRawBits())
            assertEquals(0.0.toRawBits(), maximum.toRawBits())
        }
    }

    @Test
    fun `stdev computes sample standard deviation`() {
        val values = listOf(2, 4, 4, 4, 5, 5, 7, 9)
        val result = CypherFunctions.aggregate("stdev", values) as Double
        // Sample stdev of [2,4,4,4,5,5,7,9]: mean=5, variance=32/7, stdev=sqrt(32/7) ~ 2.138
        val expected = 2.138089935299395
        assertTrue(kotlin.math.abs(result - expected) < 0.001, "Expected ~$expected but got $result")
    }

    @Test
    fun `stdev returns null for fewer than 2 values`() {
        assertNull(CypherFunctions.aggregate("stdev", listOf(1)))
    }

    @Test
    fun `stdevp computes population standard deviation`() {
        val values = listOf(2, 4, 4, 4, 5, 5, 7, 9)
        val result = CypherFunctions.aggregate("stdevp", values) as Double
        // Population stdev of [2,4,4,4,5,5,7,9]
        val mean = 5.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        assertEquals(sqrt(variance), result, 0.001)
    }

    @Test
    fun `stdevp returns null for fewer than 2 values`() {
        assertNull(CypherFunctions.aggregate("stdevp", listOf(5)))
    }

    @Test
    fun `percentileCont returns median by default`() {
        val result = CypherFunctions.aggregate("percentileCont", listOf(1, 2, 3, 4, 5)) as Double
        assertEquals(3.0, result, 0.001)
    }

    @Test
    fun `percentileCont returns null for empty filtered list`() {
        assertNull(CypherFunctions.aggregate("percentileCont", listOf(null)))
    }

    @Test
    fun `percentileDisc returns median by default`() {
        val result = CypherFunctions.aggregate("percentileDisc", listOf(1, 2, 3, 4, 5)) as Double
        assertEquals(3.0, result, 0.001)
    }

    @Test
    fun `percentileDisc returns null for empty filtered list`() {
        assertNull(CypherFunctions.aggregate("percentileDisc", listOf(null)))
    }

    @Test
    fun `percentile sort observes cancellation after aggregation starts`() {
        val values = (10_000 downTo 1).map { it as Any? }
        val cancellation = CypherCancellationSignal()
        val sortEntered = CountDownLatch(1)
        val resumeSort = CountDownLatch(1)
        val checkpoints = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                CypherFunctions.aggregate("percentileCont", values) {
                    if (checkpoints.incrementAndGet() == SORT_PHASE_CHECKPOINT) {
                        sortEntered.countDown()
                        resumeSort.await()
                    }
                    cancellation.throwIfCancelled()
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        worker.start()
        assertTrue(sortEntered.await(5, TimeUnit.SECONDS), "Percentile aggregation did not enter sorting")
        cancellation.cancel()
        resumeSort.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(2))

        assertFalse(worker.isAlive, "Percentile sorting ignored cancellation")
        assertTrue(failure.get() is CypherQueryCancelledException, "Unexpected failure: ${failure.get()}")
    }

    @Test
    fun `aggregate with explicit percentile for percentileCont`() {
        val result = CypherFunctions.aggregate("percentileCont", listOf(10, 20, 30, 40, 50), 0.9) as Double
        // 0.9 * 4 = 3.6 -> interpolate between index 3 (40) and index 4 (50)
        // 40 + 0.6 * (50 - 40) = 46.0
        assertEquals(46.0, result, 0.001)
    }

    @Test
    fun `aggregate with explicit percentile for percentileDisc`() {
        val result = CypherFunctions.aggregate("percentileDisc", listOf(10, 20, 30, 40, 50), 0.9) as Double
        // ceil(0.9 * 5) - 1 = 4, nums[4] = 50.0
        assertEquals(50.0, result, 0.001)
    }

    @Test
    fun `aggregate with explicit percentile falls through for non-percentile function`() {
        // The 3-arg aggregate delegates to 2-arg for non-percentile functions
        assertEquals(6.0, CypherFunctions.aggregate("sum", listOf(1, 2, 3), 0.5))
    }

    @Test
    fun `toDouble with String input`() {
        assertEquals(3.14, CypherFunctions.toDouble("3.14"), 0.001)
    }

    @Test
    fun `toDouble with non-numeric String returns 0`() {
        assertEquals(0.0, CypherFunctions.toDouble("abc"), 0.001)
    }

    @Test
    fun `toDouble with boolean returns 0`() {
        assertEquals(0.0, CypherFunctions.toDouble(true), 0.001)
    }

    @Test
    fun `isAggregation returns true for aggregation functions`() {
        assertTrue(CypherFunctions.isAggregation("count"))
        assertTrue(CypherFunctions.isAggregation("COUNT"))
        assertTrue(CypherFunctions.isAggregation("sum"))
        assertTrue(CypherFunctions.isAggregation("avg"))
        assertTrue(CypherFunctions.isAggregation("min"))
        assertTrue(CypherFunctions.isAggregation("max"))
        assertTrue(CypherFunctions.isAggregation("collect"))
        assertTrue(CypherFunctions.isAggregation("percentileCont"))
        assertTrue(CypherFunctions.isAggregation("percentileDisc"))
        assertTrue(CypherFunctions.isAggregation("stdev"))
        assertTrue(CypherFunctions.isAggregation("stdevp"))
    }

    @Test
    fun `isAggregation returns false for scalar functions`() {
        assertTrue(!CypherFunctions.isAggregation("toLower"))
        assertTrue(!CypherFunctions.isAggregation("abs"))
        assertTrue(!CypherFunctions.isAggregation("id"))
    }

    @Test
    fun `unknown function throws CypherException`() {
        val ex = assertFailsWith<CypherException> { CypherFunctions.call("nonexistent", emptyList()) }
        assertTrue(ex.message!!.contains("nonexistent"))
    }

    @Test
    fun `aggregation function in call context throws CypherAggregationException`() {
        for (name in listOf("count", "sum", "avg", "min", "max", "collect", "percentileCont", "percentileDisc", "stdev", "stdevp")) {
            val ex = assertFailsWith<CypherAggregationException> { CypherFunctions.call(name, listOf(1)) }
            assertEquals(name.lowercase(), ex.functionName.lowercase())
        }
    }

    @Test
    fun `unknown aggregation throws CypherException`() {
        assertFailsWith<CypherException> { CypherFunctions.aggregate("nonexistent", listOf(1)) }
    }
}
