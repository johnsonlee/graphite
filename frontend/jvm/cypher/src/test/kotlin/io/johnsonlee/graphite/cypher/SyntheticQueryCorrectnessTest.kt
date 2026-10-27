package io.johnsonlee.graphite.cypher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Keeps exact result checks for the synthetic JMH query shapes in the ordinary unit-test gate. */
@Suppress("MagicNumber", "StringLiteralDuplication")
class SyntheticQueryCorrectnessTest {
    @Test
    fun `every synthetic benchmark query returns its exact expected result`() {
        val suite = CypherBenchmark().apply { setup() }
        val callees = (1..500).map { listOf("target${it % 50}") }
        verify(suite.simpleNodeMatch(), result(listOf("n.callee_name"), callees), limit = 100)
        verify(
            suite.nodeMatchWithWhere(),
            result(listOf("n.value"), (101..199).map { listOf(it) })
        )
        verify(suite.regexFilter(), result(listOf("n.callee_name"), callees), limit = 50)
        verify(
            suite.aggregationCountGroupBy(),
            result(listOf("n.callee_class", "cnt"), listOf(listOf("com.example.Bar", 500L)))
        )
        verify(suite.countStar(), result(listOf("count(*)"), listOf(listOf(1200L))))

        val all = (1..500).map { listOf(it, "target${it % 50}") }
        val filtered = (251..500).map { listOf(it, "target${it % 50}") }
        verify(
            suite.singleHopRelationship(),
            result(listOf("c.value", "cs.callee_name"), all),
            limit = 50
        )
        verify(
            suite.filteredSingleHopRelationship(),
            result(listOf("c.value", "cs.callee_name"), filtered),
            limit = 50
        )
        assertEquals(
            result(listOf("value", "callee"), filtered.reversed().take(50)),
            suite.orderedFilteredSingleHopRelationship()
        )
        verify(
            suite.variableLengthPath(),
            result(listOf("a.value", "b.callee_name"), all),
            limit = 20
        )
        verify(
            suite.filteredVariableLengthPath(),
            result(listOf("value", "callee"), emptyList())
        )
        verify(
            suite.returnDistinct(),
            result(listOf("n.callee_class"), listOf(listOf("com.example.Bar")))
        )
        assertEquals(
            result(listOf("v"), (251..260).map { listOf(it) }),
            suite.withPipeline()
        )
        verify(
            suite.functionCalls(),
            result(
                listOf("toLower(n.callee_name)", "size(n.callee_class)"),
                (1..500).map { listOf("target${it % 50}", "com.example.Bar".length) }
            ),
            limit = 50
        )
    }

    @Test
    fun `budgeted synthetic queries preserve exact large expression results`() {
        val suite = BudgetedCypherBenchmark().apply { setup() }
        assertEquals(
            result(listOf("n"), listOf(listOf(10_000L))),
            suite.budgetedGeneralRegex()
        )
        assertEquals(
            result(listOf("n"), listOf(listOf(100_000))),
            suite.budgetedListConcatenation()
        )
        assertEquals(
            result(listOf("found"), listOf(listOf(false))),
            suite.budgetedListMembership()
        )
    }

    @Test
    fun `unordered LIMIT accepts only a full row submultiset`() {
        val expected = result(1, 1, 2)
        verify(result(2, 1), expected, limit = 2)
        verify(result(2, 1, 1), expected)
        assertFailsWith<IllegalStateException> { verify(result(2, 2), expected, limit = 2) }
        assertFailsWith<IllegalStateException> { verify(result(1, 3), expected, limit = 2) }
        assertFailsWith<AssertionError> { verify(result(1, 2), expected) }
        assertFailsWith<AssertionError> {
            verify(CypherResult(listOf("wrong"), expected.rows), expected)
        }
        assertFailsWith<IllegalStateException> {
            verify(
                CypherResult(
                    listOf("value"),
                    listOf(mapOf("value" to 1, "extra" to true), mapOf("value" to 2))
                ),
                expected,
                limit = 2
            )
        }
    }

    private fun verify(actual: CypherResult, expected: CypherResult, limit: Int? = null) {
        assertEquals(expected.columns, actual.columns)
        assertEquals(limit ?: expected.rows.size, actual.rows.size)
        val remaining = expected.rows.groupingBy { it }.eachCount().toMutableMap()
        actual.rows.forEach { row ->
            val count = remaining[row] ?: 0
            check(count > 0) { "unexpected full row $row" }
            remaining[row] = count - 1
        }
        check(limit != null || remaining.values.all { it == 0 }) { "incomplete result" }
    }

    private fun result(columns: List<String>, values: List<List<Any?>>): CypherResult =
        CypherResult(columns, values.map { row ->
            check(row.size == columns.size)
            columns.zip(row).toMap()
        })

    private fun result(vararg values: Int): CypherResult =
        result(listOf("value"), values.map { listOf(it) })
}
