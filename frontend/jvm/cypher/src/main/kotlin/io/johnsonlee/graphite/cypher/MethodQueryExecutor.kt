package io.johnsonlee.graphite.cypher

/** Executes bounded Cypher reads over the graph's method metadata index. */
internal object MethodQueryExecutor {

    fun referencesMethod(clauses: List<CypherClause>): Boolean =
        clauses.any { clause ->
            clause is CypherClause.Match &&
                clause.patterns.any { pattern ->
                    pattern.elements.any { element -> element is PatternElement.NodePattern && element.labels.any(::isMethodLabel) }
                }
        }

    fun tryExecute(
        clauses: List<CypherClause>,
        sources: List<CypherGraph>,
        qualified: Boolean,
        checkCancelled: () -> Unit,
        workTracker: CypherWorkTracker?
    ): CypherResult? {
        val query = compileMethodQuery(clauses) ?: return null
        val selectedSources = query.scan.sourceGraphId?.let { id -> sources.filter { it.id == id } } ?: sources
        val execution =
            MethodExecution(
                query.returned, query.variable, query.where, query.scan, selectedSources, qualified, checkCancelled, workTracker)
        // Count handling precedes requested-zero and ORDER BY validation, as in the metadata query contract.
        return if (query.returned.items.any { containsMethodAggregation(it.expression) }) {
            if (query.returned.distinct || query.orderBy != null) null else executeMethodCount(execution, query.skip, query.limit)
        } else executeMethodRows(execution, query.skip, query.limit, query.orderBy)
    }

    fun isMethodLabel(label: String): Boolean = label.equals(METHOD_LABEL, ignoreCase = true)
}
