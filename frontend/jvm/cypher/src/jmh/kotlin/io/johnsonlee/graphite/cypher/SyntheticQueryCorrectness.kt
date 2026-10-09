package io.johnsonlee.graphite.cypher

/** Executes every original synthetic query shape without a timer or resource sampler. */
// Fixed fixture expectations stay explicit and independent of implementation constants.
@Suppress("MagicNumber", "StringLiteralDuplication")
object SyntheticQueryCorrectness {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val suite = CypherBenchmark().apply { setup() }
        nodeQueries(suite)
        relationshipQueries(suite)
        projectionQueries(suite)
        BudgetedQueryCorrectness.main(emptyArray())
    }

    private fun nodeQueries(suite: CypherBenchmark) {
        val callees = (1..500).map { listOf("target${it % 50}") }
        verify("simpleNodeMatch", suite.simpleNodeMatch(), result(listOf("n.callee_name"), callees), 100)
        verify(
            "nodeMatchWithWhere", suite.nodeMatchWithWhere(),
            result(listOf("n.value"), (101..199).map { listOf(it) })
        )
        verify("regexFilter", suite.regexFilter(), result(listOf("n.callee_name"), callees), 50)
        verify(
            "aggregationCountGroupBy", suite.aggregationCountGroupBy(),
            result(listOf("n.callee_class", "cnt"), listOf(listOf("com.example.Bar", 500L)))
        )
        verify("countStar", suite.countStar(), result(listOf("count(*)"), listOf(listOf(1200L))))
    }

    private fun relationshipQueries(suite: CypherBenchmark) {
        val all = (1..500).map { listOf(it, "target${it % 50}") }
        val filtered = (251..500).map { listOf(it, "target${it % 50}") }
        verify(
            "singleHopRelationship", suite.singleHopRelationship(),
            result(listOf("c.value", "cs.callee_name"), all), 50
        )
        verify(
            "filteredSingleHopRelationship", suite.filteredSingleHopRelationship(),
            result(listOf("c.value", "cs.callee_name"), filtered), 50
        )
        ordered(
            "orderedFilteredSingleHopRelationship", suite.orderedFilteredSingleHopRelationship(),
            result(listOf("value", "callee"), filtered.reversed().take(50))
        )
        verify(
            "variableLengthPath", suite.variableLengthPath(),
            result(listOf("a.value", "b.callee_name"), all), 20
        )
        verify(
            "filteredVariableLengthPath", suite.filteredVariableLengthPath(),
            result(listOf("value", "callee"), emptyList())
        )
    }

    private fun projectionQueries(suite: CypherBenchmark) {
        verify(
            "returnDistinct", suite.returnDistinct(),
            result(listOf("n.callee_class"), listOf(listOf("com.example.Bar")))
        )
        ordered(
            "withPipeline", suite.withPipeline(),
            result(listOf("v"), (251..260).map { listOf(it) })
        )
        verify(
            "functionCalls", suite.functionCalls(),
            result(
                listOf("toLower(n.callee_name)", "size(n.callee_class)"),
                (1..500).map { listOf("target${it % 50}", "com.example.Bar".length) }
            ), 50
        )
    }

    private fun result(columns: List<String>, values: List<List<Any?>>): CypherResult =
        CypherResult(columns, values.map { row ->
            check(row.size == columns.size)
            columns.zip(row).toMap()
        })

    /** An unordered LIMIT permits any full row sub-multiset, never invented or duplicated rows. */
    private fun verify(name: String, actual: CypherResult, expected: CypherResult, limit: Int? = null) {
        check(actual.columns == expected.columns) { "$name: columns ${actual.columns}" }
        check(actual.rows.size == (limit ?: expected.rows.size)) { "$name: row count ${actual.rows.size}" }
        val remaining = expected.rows.groupingBy { it }.eachCount().toMutableMap()
        actual.rows.forEach { row ->
            val count = remaining[row] ?: 0
            check(count > 0) { "$name: unexpected full row $row" }
            remaining[row] = count - 1
        }
        check(limit != null || remaining.values.all { it == 0 }) { "$name: incomplete result" }
        println("CORRECTNESS_PASS\t$name")
    }

    private fun ordered(name: String, actual: CypherResult, expected: CypherResult) {
        check(actual == expected) { "$name: ordered result mismatch: $actual" }
        println("CORRECTNESS_PASS\t$name")
    }
}
