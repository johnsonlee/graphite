package io.johnsonlee.graphite.cypher

/** The final predicate, projection and bounded pagination shared by streaming match executors. */
internal data class FilteredRowPlan(
    val where: CypherClause.Where,
    val returned: CypherClause.Return,
    val columns: List<String>,
    val skip: Int,
    val retained: Int,
    val orderBy: CypherClause.OrderBy? = null
)
