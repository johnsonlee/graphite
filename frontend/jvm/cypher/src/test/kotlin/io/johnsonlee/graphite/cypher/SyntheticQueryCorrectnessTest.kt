package io.johnsonlee.graphite.cypher

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Suppress("MagicNumber", "StringLiteralDuplication")
class SyntheticQueryCorrectnessTest {
    @Test
    fun `standalone correctness gate consumes every fixed query result`() {
        val output = captureOutput { SyntheticQueryCorrectness.main(emptyArray()) }
        assertEquals(listOf(
            "simpleNodeMatch", "nodeMatchWithWhere", "regexFilter", "aggregationCountGroupBy", "countStar",
            "singleHopRelationship", "filteredSingleHopRelationship", "orderedFilteredSingleHopRelationship",
            "variableLengthPath", "filteredVariableLengthPath", "returnDistinct", "withPipeline", "functionCalls",
            "budgetedGeneralRegex", "budgetedListConcatenation", "budgetedListMembership"
        ).map { "CORRECTNESS_PASS\t$it" }, output.lineSequence().filter(String::isNotEmpty).toList())
    }

    @Test
    fun `standalone gates reject arguments before publishing success`() {
        val output = captureOutput {
            assertFailsWith<IllegalArgumentException> { SyntheticQueryCorrectness.main(arrayOf("unexpected")) }
            assertFailsWith<IllegalArgumentException> { BudgetedQueryCorrectness.main(arrayOf("unexpected")) }
        }
        assertEquals("", output)
    }

    @Test
    fun `unordered LIMIT accepts only a full row submultiset`() {
        val expected = result(1, 1, 2)
        val output = captureOutput {
            SyntheticQueryCorrectness.verify("limited", result(2, 1), expected, 2)
            SyntheticQueryCorrectness.verify("complete", result(2, 1, 1), expected)
        }
        assertEquals("CORRECTNESS_PASS\tlimited\nCORRECTNESS_PASS\tcomplete\n", output)
        val failures = captureOutput {
            assertFailsWith<IllegalStateException> {
                SyntheticQueryCorrectness.verify("duplicated", result(2, 2), expected, 2)
            }
            assertFailsWith<IllegalStateException> {
                SyntheticQueryCorrectness.verify("invented", result(1, 3), expected, 2)
            }
            assertFailsWith<IllegalStateException> {
                SyntheticQueryCorrectness.verify("missing", result(1, 2), expected)
            }
            assertFailsWith<IllegalStateException> {
                SyntheticQueryCorrectness.verify("columns", CypherResult(listOf("wrong"), expected.rows), expected)
            }
            assertFailsWith<IllegalStateException> {
                SyntheticQueryCorrectness.verify("full-row", CypherResult(listOf("value"), listOf(
                    mapOf("value" to 1, "extra" to true), mapOf("value" to 2)
                )), expected, 2)
            }
        }
        assertEquals("", failures)
    }

    @Test
    fun `ordered gate rejects permutation even when row multiset matches`() {
        val expected = result(1, 2)
        val output = captureOutput { SyntheticQueryCorrectness.ordered("ordered", result(1, 2), expected) }
        assertEquals("CORRECTNESS_PASS\tordered\n", output)
        assertEquals("", captureOutput {
            assertFailsWith<IllegalStateException> {
                SyntheticQueryCorrectness.ordered("reordered", result(2, 1), expected)
            }
        })
    }

    private fun result(vararg values: Int) = CypherResult(listOf("value"), values.map { mapOf("value" to it) })

    private fun captureOutput(action: () -> Unit): String {
        val previous = System.out
        val bytes = ByteArrayOutputStream()
        try {
            PrintStream(bytes, true, Charsets.UTF_8).use { output ->
                System.setOut(output)
                action()
            }
        } finally {
            System.setOut(previous)
        }
        return bytes.toString(Charsets.UTF_8).replace("\r\n", "\n")
    }
}
