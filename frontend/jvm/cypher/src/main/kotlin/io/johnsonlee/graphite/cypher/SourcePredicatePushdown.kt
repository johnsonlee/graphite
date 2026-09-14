package io.johnsonlee.graphite.cypher

/** Conservative, total predicates only: moving a volatile or throwing expression changes semantics. */
internal object SourcePredicatePushdown {
    fun compile(condition: CypherExpr, source: String): CypherExpr? {
        dependencies(condition) ?: return null
        return conjuncts(condition)
            .filter { dependencies(it) == setOf(source) }
            .reduceOrNull { left, right -> CypherExpr.And(left, right) }
    }

    private fun conjuncts(expression: CypherExpr): List<CypherExpr> = when (expression) {
        is CypherExpr.And -> conjuncts(expression.left) + conjuncts(expression.right)
        else -> listOf(expression)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun dependencies(expression: CypherExpr): Set<String>? = when (expression) {
        is CypherExpr.Literal, is CypherExpr.Parameter -> emptySet()
        is CypherExpr.Variable -> setOf(expression.name)
        is CypherExpr.Property -> (expression.expression as? CypherExpr.Variable)?.let { setOf(it.name) }
        is CypherExpr.Comparison -> if (expression.op in setOf("=", "<>", "<", ">", "<=", ">=")) {
            combine(expression.left, expression.right)
        } else null
        is CypherExpr.StringOp -> if (expression.op in setOf("CONTAINS", "STARTS WITH", "ENDS WITH")) {
            combine(expression.left, expression.right)
        } else null
        is CypherExpr.And -> combine(expression.left, expression.right)
        is CypherExpr.Or -> combine(expression.left, expression.right)
        is CypherExpr.Not -> if (hasBooleanResult(expression.expression)) {
            dependencies(expression.expression)
        } else null
        is CypherExpr.IsNull -> dependencies(expression.expression)
        is CypherExpr.IsNotNull -> dependencies(expression.expression)
        is CypherExpr.FunctionCall -> if (expression.args.size == 1 && expression.name.lowercase() in
            setOf("tostring", "tolower", "tolowercase", "toupper", "touppercase")
        ) {
            dependencies(expression.args.single())
        } else null
        else -> null
    }

    // NOT uses a strict Boolean cast; a property, parameter, or string conversion can throw.
    private fun hasBooleanResult(expression: CypherExpr): Boolean = when (expression) {
        is CypherExpr.Comparison, is CypherExpr.StringOp,
        is CypherExpr.And, is CypherExpr.Or, is CypherExpr.Not,
        is CypherExpr.IsNull, is CypherExpr.IsNotNull -> true
        is CypherExpr.Literal -> expression.value == null || expression.value is Boolean
        else -> false
    }

    private fun combine(left: CypherExpr, right: CypherExpr): Set<String>? =
        dependencies(left)?.let { leftDependencies ->
            dependencies(right)?.let { rightDependencies -> leftDependencies + rightDependencies }
        }
}
