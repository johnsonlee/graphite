package io.johnsonlee.graphite.cypher

/** Proves graph scopes from connected patterns and conjunctive graph-id predicates. */
internal class GraphSourcePlanner(private val sources: List<CypherGraph>, private val context: PipelineExecutionContext) {
    /**
     * Restricts a single connected MATCH to graph ids proven by conjunctive predicates.
     * Multiple/disconnected MATCH patterns remain unscoped because their variables may
     * legally bind values from different graphs.
     */
    data class GraphSourceScope(
        val selectedSources: List<CypherGraph>,
        val conflicting: Boolean
    )

    fun graphSourceScope(clauses: List<CypherClause>): GraphSourceScope? {
        val matchIndex = soleMatchIndex(clauses)
        if (matchIndex < 0) return null
        val match = clauses[matchIndex] as CypherClause.Match
        val condition = match.where
            ?: (clauses.getOrNull(matchIndex + 1) as? CypherClause.Where)?.condition
        return match.patterns.singleOrNull()?.let { pattern -> scopeForPattern(pattern, condition) }
    }

    private fun soleMatchIndex(clauses: List<CypherClause>): Int {
        var matchIndex = -1
        for (index in clauses.indices) {
            if (clauses[index] is CypherClause.Match) {
                if (matchIndex >= 0) return -1
                matchIndex = index
            }
        }
        return matchIndex
    }

    private fun scopeForPattern(pattern: CypherPattern, condition: CypherExpr?): GraphSourceScope? {
        val patternReferencesGraphId = pattern.elements.any { element ->
            element is PatternElement.NodePattern && GRAPH_ID_PROPERTY in element.properties
        }
        val whereReferencesGraphId = condition?.let(::containsGraphIdConstraintCandidate) == true
        if (!patternReferencesGraphId && !whereReferencesGraphId) return null
        return scopeForVariables(pattern, condition)
    }

    private fun scopeForVariables(pattern: CypherPattern, condition: CypherExpr?): GraphSourceScope? {
        val variables = pattern.elements.filterIsInstance<PatternElement.NodePattern>()
            .mapNotNullTo(linkedSetOf(), PatternElement.NodePattern::variable)
        if (variables.isEmpty()) return null
        val patternGraphIds = pattern.elements.filterIsInstance<PatternElement.NodePattern>()
            .mapNotNull { node ->
                node.variable?.takeIf { it in variables }
                    ?.let { node.properties[GRAPH_ID_PROPERTY] }
                    ?.let(::graphIdValue)
            }
            .map(::setOf)
            .reduceOrNull(Set<String>::intersect)
        val whereGraphIds = condition?.let { graphIdConstraint(it, variables) }
        return intersectGraphIdConstraints(patternGraphIds, whereGraphIds)?.let { graphIds ->
            GraphSourceScope(sources.filter { source -> source.id in graphIds }, conflicting = graphIds.isEmpty())
        }
    }

    /**
     * Cheaply rejects the overwhelmingly common source-unqualified query before set planning.
     * Only the expression shapes accepted by [graphIdConstraint] are candidates: walking through
     * arbitrary arithmetic, CASE, string, or collection expressions cannot produce a safe route.
     */
    private fun containsGraphIdConstraintCandidate(expression: CypherExpr): Boolean = when (expression) {
        is CypherExpr.And -> containsGraphIdConstraintCandidate(expression.left) ||
            containsGraphIdConstraintCandidate(expression.right)
        is CypherExpr.Or -> containsGraphIdConstraintCandidate(expression.left) ||
            containsGraphIdConstraintCandidate(expression.right)
        is CypherExpr.Comparison -> isPotentialGraphIdReference(expression.left) ||
            isPotentialGraphIdReference(expression.right)
        is CypherExpr.ListOp -> expression.op.equals("IN", ignoreCase = true) &&
            isPotentialGraphIdReference(expression.left)
        else -> false
    }

    private fun isPotentialGraphIdReference(expression: CypherExpr): Boolean = when (expression) {
        is CypherExpr.Property -> expression.propertyName == GRAPH_ID_PROPERTY
        is CypherExpr.FunctionCall -> expression.name.equals("graphId", ignoreCase = true)
        else -> false
    }

    fun recordGraphSourceScope(scope: GraphSourceScope) {
        context.workTracker?.recordGraphIdSourceSelection(
            initialSourceCount = sources.size,
            selectedSourceCount = scope.selectedSources.size,
            conflicting = scope.conflicting
        )
    }

    fun hasUnknownNodeLabel(clauses: List<CypherClause>): Boolean = clauses.any { clause ->
        clause is CypherClause.Match && clause.patterns.any { pattern ->
            pattern.elements.filterIsInstance<PatternElement.NodePattern>().any { node ->
                node.labels.any { label ->
                    !MethodQueryExecutor.isMethodLabel(label) &&
                        NodePropertyAccessor.resolveNodeLabelOrNull(label) == null
                }
            }
        }
    }

    data class ConjunctiveGraphIdRoute(
        val graphIds: Set<String>?,
        val conflicting: Boolean,
        val residual: CypherExpr?
    )

    fun conjunctiveGraphIdRoute(
        expression: CypherExpr,
        variable: String
    ): ConjunctiveGraphIdRoute {
        var graphIds: Set<String>? = null

        fun visit(candidate: CypherExpr): CypherExpr? = when (candidate) {
            is CypherExpr.And -> {
                val left = visit(candidate.left)
                val right = visit(candidate.right)
                when {
                    left == null -> right
                    right == null -> left
                    left === candidate.left && right === candidate.right -> candidate
                    else -> CypherExpr.And(left, right)
                }
            }
            else -> {
                val values = pureGraphIdConstraint(candidate, setOf(variable))
                if (values == null) {
                    candidate
                } else {
                    graphIds = intersectGraphIdConstraints(graphIds, values)
                    null
                }
            }
        }

        val residual = visit(expression)
        return ConjunctiveGraphIdRoute(graphIds, graphIds?.isEmpty() == true, residual)
    }

    fun graphIdEquality(expression: CypherExpr, variable: String): String? = when (expression) {
        is CypherExpr.And -> graphIdEquality(expression.left, variable)
            ?: graphIdEquality(expression.right, variable)
        is CypherExpr.Comparison -> graphIdEqualityValue(expression, variable)
        else -> null
    }

    private fun graphIdEqualityValue(expression: CypherExpr.Comparison, variable: String): String? =
        if (expression.op == "=") {
            when {
                isGraphIdReference(expression.left, variable) -> graphIdValue(expression.right)
                isGraphIdReference(expression.right, variable) -> graphIdValue(expression.left)
                else -> null
            }
        } else {
            null
        }

    /**
     * Returns the finite graph-id set proven by [expression]. Unknown conjuncts
     * do not weaken an exact constraint, while every OR branch must be exact.
     */
    fun graphIdConstraint(expression: CypherExpr, variables: Set<String>): Set<String>? = when (expression) {
        is CypherExpr.And -> intersectGraphIdConstraints(
            graphIdConstraint(expression.left, variables),
            graphIdConstraint(expression.right, variables)
        )
        is CypherExpr.Or -> {
            val left = graphIdConstraint(expression.left, variables)
            val right = graphIdConstraint(expression.right, variables)
            if (left == null || right == null) null else left + right
        }
        else -> graphIdAtomicConstraint(expression, variables)
    }

    /** Unlike [graphIdConstraint], this requires every sub-expression to constrain graph id. */
    private fun pureGraphIdConstraint(expression: CypherExpr, variables: Set<String>): Set<String>? = when (expression) {
        is CypherExpr.And -> {
            val left = pureGraphIdConstraint(expression.left, variables)
            val right = pureGraphIdConstraint(expression.right, variables)
            if (left == null || right == null) null else left intersect right
        }
        is CypherExpr.Or -> {
            val left = pureGraphIdConstraint(expression.left, variables)
            val right = pureGraphIdConstraint(expression.right, variables)
            if (left == null || right == null) null else left + right
        }
        else -> graphIdAtomicConstraint(expression, variables)
    }

    private fun graphIdAtomicConstraint(expression: CypherExpr, variables: Set<String>): Set<String>? = when (expression) {
        is CypherExpr.Comparison -> variables.firstNotNullOfOrNull { variable ->
            graphIdEqualityValue(expression, variable)
        }?.let(::setOf)
        is CypherExpr.ListOp -> if (expression.op.equals("IN", ignoreCase = true) &&
            variables.any { variable -> isGraphIdReference(expression.left, variable) }
        ) {
            graphIdValues(expression.right)
        } else {
            null
        }
        else -> null
    }

    private fun intersectGraphIdConstraints(left: Set<String>?, right: Set<String>?): Set<String>? = when {
        left == null -> right
        right == null -> left
        else -> left intersect right
    }

    private fun graphIdValues(expression: CypherExpr): Set<String>? = when (expression) {
        is CypherExpr.ListLiteral -> expression.elements.map { graphIdValue(it) }
            .takeIf { values -> values.all { it != null } }
            ?.filterNotNull()
            ?.toSet()
        is CypherExpr.Parameter -> when (val value = context.parameters.orEmpty()[expression.name]) {
            is List<*> -> value.takeIf { values -> values.all { it is String } }
                ?.filterIsInstance<String>()
                ?.toSet()
            else -> null
        }
        else -> null
    }

    fun graphIdValue(expression: CypherExpr): String? = when (expression) {
        is CypherExpr.Literal -> expression.value as? String
        is CypherExpr.Parameter -> context.parameters.orEmpty()[expression.name] as? String
        else -> null
    }

    private fun isGraphIdReference(expression: CypherExpr, variable: String): Boolean = when (expression) {
        is CypherExpr.Property -> expression.expression == CypherExpr.Variable(variable) &&
            expression.propertyName == GRAPH_ID_PROPERTY
        is CypherExpr.FunctionCall -> expression.name.equals("graphId", ignoreCase = true) &&
            !expression.distinct && expression.args.singleOrNull() == CypherExpr.Variable(variable)
        else -> false
    }
}
