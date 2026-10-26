package io.johnsonlee.graphite.cypher

/** Preserves the former selected budgeted scenarios as values, never benchmark samples. */
// Fixed fixture expectations stay explicit and independent of implementation constants.
@Suppress("MagicNumber")
object BudgetedQueryCorrectness {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val suite = BudgetedCypherBenchmark().apply { setup() }
        verify("budgetedGeneralRegex", suite.budgetedGeneralRegex(), "n", 10_000L)
        verify("budgetedListConcatenation", suite.budgetedListConcatenation(), "n", 100_000)
        verify("budgetedListMembership", suite.budgetedListMembership(), "found", false)
    }

    private fun verify(name: String, actual: CypherResult, column: String, value: Any) {
        check(actual == CypherResult(listOf(column), listOf(mapOf(column to value)))) { "$name: $actual" }
        println("CORRECTNESS_PASS\t$name")
    }
}
