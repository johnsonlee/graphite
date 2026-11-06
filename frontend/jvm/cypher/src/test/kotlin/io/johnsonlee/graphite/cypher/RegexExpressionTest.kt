package io.johnsonlee.graphite.cypher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class RegexExpressionTest : ExpressionEvaluatorTestFixture() {

    @Test
    fun `regex match`() {
        assertEquals(true, eval(CypherExpr.RegexMatch(lit("hello"), lit("hel.*"))))
        assertEquals(false, eval(CypherExpr.RegexMatch(lit("hello"), lit("xyz.*"))))
    }

    @Test
    fun `regex preserves Java pattern language`() {
        val matchingCases =
            listOf(
                "aa" to "(a)\\1",
                "foobar" to "foo(?=bar)bar",
                "aaa" to "a++",
                "a" to "[a-z&&[^b]]",
                "abcXYZ_123" to "[a-z]+[A-Z_]+[0-9]+",
                "azm" to "[a-z]+[a-m]+")

        for ((value, pattern) in matchingCases) {
            assertEquals(true, eval(CypherExpr.RegexMatch(lit(value), lit(pattern))), pattern)
        }
        assertEquals(false, eval(CypherExpr.RegexMatch(lit("b"), lit("[a-z&&[^b]]"))))
        assertEquals(false, eval(CypherExpr.RegexMatch(lit("abc123def"), lit("[a-z]+[0-9]+"))))
    }

    @Test
    fun `ASCII range fast path matches Java regex semantics`() {
        val patterns = listOf("[a-z]+[0-9]+", "[a-a]+[b-d]+", "[A-Z]+[_]+[0-9]+", "[0]+")
        val values = listOf("", "a", "abc123", "aaaa", "aaab", "aad", "XYZ_12", "XYZ__12", "0", "00", "é1")

        for (pattern in patterns) {
            for (value in values) {
                val expected = pattern.toRegex().matches(value)
                assertEquals(expected, eval(CypherExpr.RegexMatch(lit(value), lit(pattern))), "$pattern / $value")
            }
        }
    }

    @Test
    fun `dot rejects every default Java line terminator`() {
        val lineTerminators = listOf('\n', '\r', '\u0085', '\u2028', '\u2029')

        for (lineTerminator in lineTerminators) {
            val value = "foo${lineTerminator}bar"
            assertEquals(false, eval(CypherExpr.RegexMatch(lit(value), lit("foo.*"))))
        }
    }

    @Test
    fun `tracked regex fast paths preserve matches and poll cancellation`() {
        var cancellationChecks = 0
        val trackedEvaluator = ExpressionEvaluator { cancellationChecks++ }
        fun trackedMatch(value: String, pattern: String): Any? =
            trackedEvaluator.evaluate(CypherExpr.RegexMatch(lit(value), lit(pattern)), emptyMap())

        assertEquals(true, trackedMatch("literal", "literal"))
        assertEquals(false, trackedMatch("short", "shorter"))
        assertEquals(false, trackedMatch("mismatch", "different"))
        assertEquals(true, trackedMatch("prefix-suffix", "prefix.*"))
        assertEquals(false, trackedMatch("prefix\rvalue", "prefix.*"))
        assertEquals(true, trackedMatch("foobar", "foo(?=bar)bar"))
        assertTrue(cancellationChecks > 0)
    }

    @Test
    fun `tracked short ASCII range sequence avoids per-character cancellation polling`() {
        var cancellationChecks = 0
        val trackedEvaluator = ExpressionEvaluator { cancellationChecks++ }
        val value = "a".repeat(100) + "12345678901234"

        assertEquals(true, trackedEvaluator.evaluate(CypherExpr.RegexMatch(lit(value), lit("[a-z]+[0-9]+")), emptyMap()))
        assertEquals(1, cancellationChecks)
    }

    @Test
    fun `tracked long ASCII range sequence polls cancellation`() {
        var cancellationChecks = 0
        val trackedEvaluator = ExpressionEvaluator { cancellationChecks++ }
        val value = "a".repeat(2_048) + "1234"

        assertEquals(true, trackedEvaluator.evaluate(CypherExpr.RegexMatch(lit(value), lit("[a-z]+[0-9]+")), emptyMap()))
        assertTrue(cancellationChecks >= 3)
    }

    @Test
    fun `tracked regex without risky syntax still cancels inside the matcher`() {
        val cancellation = CypherCancellationSignal()
        val matcherEntered = CountDownLatch(1)
        val resumeMatcher = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val cancellableEvaluator = ExpressionEvaluator {
            matcherEntered.countDown()
            resumeMatcher.await()
            cancellation.throwIfCancelled()
        }
        val worker = Thread {
            try {
                val optionalPrefix = "a?".repeat(30)
                cancellableEvaluator.evaluate(CypherExpr.RegexMatch(lit("a".repeat(29)), lit(optionalPrefix + "a".repeat(30))), emptyMap())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        worker.start()
        assertTrue(matcherEntered.await(5, TimeUnit.SECONDS), "Regex matcher did not reach an internal checkpoint")
        cancellation.cancel()
        resumeMatcher.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(2))

        assertFalse(worker.isAlive, "Regex matcher ignored cancellation")
        assertTrue(failure.get() is CypherQueryCancelledException, "Unexpected failure: ${failure.get()}")
    }

    @Test
    fun `regex with null returns null`() {
        assertNull(eval(CypherExpr.RegexMatch(lit(null), lit("hel.*"))))
        assertNull(eval(CypherExpr.RegexMatch(lit("hello"), lit(null))))
    }

    @Test
    fun `regex cache is bounded`() {
        repeat(300) { index -> assertEquals(true, eval(CypherExpr.RegexMatch(lit("value-$index"), lit("value-$index")))) }

        val field = ExpressionEvaluator::class.java.getDeclaredField("regexCache")
        field.isAccessible = true
        val cache = field.get(evaluator) as Map<*, *>

        assertTrue(cache.size <= 256)
    }

    @Test
    fun `regex matching observes cancellation from inside the matcher`() {
        val cancellation = CypherCancellationSignal()
        val matcherEntered = CountDownLatch(1)
        val resumeMatcher = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val cancellableEvaluator = ExpressionEvaluator {
            matcherEntered.countDown()
            resumeMatcher.await()
            cancellation.throwIfCancelled()
        }
        val worker = Thread {
            try {
                cancellableEvaluator.evaluate(CypherExpr.RegexMatch(lit("a".repeat(100_000) + "!"), lit("(a+)+$")), emptyMap())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        worker.start()
        assertTrue(matcherEntered.await(5, TimeUnit.SECONDS), "Regex matcher did not start reading input")
        cancellation.cancel()
        resumeMatcher.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(2))

        assertFalse(worker.isAlive, "Regex matcher ignored cancellation")
        assertTrue(failure.get() is CypherQueryCancelledException, "Unexpected failure: ${failure.get()}")
    }
}
