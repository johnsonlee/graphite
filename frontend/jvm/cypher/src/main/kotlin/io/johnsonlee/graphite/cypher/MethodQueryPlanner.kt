package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.MethodPattern

private data class Suffix(
    val where: CypherClause.Where?,
    val returned: CypherClause.Return,
    val orderBy: CypherClause.OrderBy?,
    val skip: CypherClause.Skip?,
    val limit: CypherClause.Limit?
)

internal fun compileMethodQuery(clauses: List<CypherClause>): CompiledMethodQuery? {
    val match = clauses.firstOrNull() as? CypherClause.Match ?: return null
    return if (match.optional) null
    else
        singleNodePattern(match)
            ?.takeIf { node -> node.labels.size == 1 && MethodQueryExecutor.isMethodLabel(node.labels.single()) }
            ?.let { compileNode(it, clauses) }
}

private fun compileNode(node: PatternElement.NodePattern, clauses: List<CypherClause>): CompiledMethodQuery? {
    val variable = node.variable ?: return null
    return suffix(clauses)?.let { compileMethodQuery(node, variable, it) }
}

private fun suffix(clauses: List<CypherClause>): Suffix? {
    var index = 1
    val where = (clauses.getOrNull(index) as? CypherClause.Where)?.also { index++ }
    val returned = clauses.getOrNull(index) as? CypherClause.Return
    index++
    val orderBy = (clauses.getOrNull(index) as? CypherClause.OrderBy)?.also { index++ }
    val skip = (clauses.getOrNull(index) as? CypherClause.Skip)?.also { index++ }
    val limit = (clauses.getOrNull(index) as? CypherClause.Limit)?.also { index++ }
    return if (returned != null && index == clauses.size) Suffix(where, returned, orderBy, skip, limit) else null
}

private fun compileMethodQuery(node: PatternElement.NodePattern, variable: String, suffix: Suffix): CompiledMethodQuery? {
    val skip = literalCount(suffix.skip?.count, default = 0) ?: return null
    return literalCount(suffix.limit?.count, default = Int.MAX_VALUE)?.let { limit ->
        methodScanPlan(node, variable, suffix.where)?.let { scan ->
            CompiledMethodQuery(suffix.returned, variable, suffix.where, scan, skip, limit, suffix.orderBy)
        }
    }
}

private fun literalCount(expression: CypherExpr?, default: Int): Int? =
    if (expression == null) default
    else {
        ((expression as? CypherExpr.Literal)?.value as? Number)?.toLong()?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt()
    }

internal fun methodScanPlan(nodePattern: PatternElement.NodePattern, variable: String, where: CypherClause.Where?): MethodScanPlan? {
    val predicate = MethodPredicate(variable)
    nodePattern.properties.forEach { (property, value) -> if (!predicate.addEquality(property, value)) return null }
    val fullyPushed = where == null || predicate.addConjuncts(where.condition)
    return MethodScanPlan(predicate.pattern(), fullyPushed, predicate.sourceGraphId())
}

private class MethodPredicate(private val variable: String) {
    private var declaringClass: String? = null
    private var name: String? = null
    private var parameterTypes: List<String>? = null
    private var returnType: String? = null
    private var graphId: String? = null

    fun pattern(): MethodPattern =
        MethodPattern(
            declaringClass = declaringClass, name = name, parameterTypes = parameterTypes, returnType = returnType, useRegex = true)

    fun sourceGraphId(): String? = graphId

    fun addConjuncts(expression: CypherExpr): Boolean =
        if (expression is CypherExpr.And) {
            val left = addConjuncts(expression.left)
            val right = addConjuncts(expression.right)
            left && right
        } else {
            add(expression)
        }

    fun add(expression: CypherExpr): Boolean =
        when (expression) {
            is CypherExpr.And -> add(expression.left) && add(expression.right)
            is CypherExpr.Comparison -> expression.op == "=" && addComparison(expression.left, expression.right)
            is CypherExpr.StringOp -> addStringOperation(expression)
            is CypherExpr.RegexMatch -> addRegex(expression)
            else -> false
        }

    fun addEquality(property: String, value: CypherExpr): Boolean {
        val raw = literal(value) ?: return false
        return when (property) {
            GRAPH_ID_PROPERTY -> setOnce(graphId, raw as? String) { graphId = it }
            METHOD_SIGNATURE_PROPERTY -> addSignature(raw as? String)
            METHOD_PARAMETER_TYPES_PROPERTY -> setOnce(parameterTypes, parameterPatterns(raw)) { parameterTypes = it }
            else -> addProperty(property, (raw as? String)?.let(Regex::escape))
        }
    }

    private fun addComparison(left: CypherExpr, right: CypherExpr): Boolean {
        val leftProperty = property(left)
        val rightProperty = property(right)
        return when {
            isGraphIdReference(left) -> addGraphId(right)
            isGraphIdReference(right) -> addGraphId(left)
            leftProperty != null -> addEquality(leftProperty, right)
            rightProperty != null -> addEquality(rightProperty, left)
            else -> false
        }
    }

    private fun addGraphId(expression: CypherExpr): Boolean = setOnce(graphId, literal(expression) as? String) { graphId = it }

    private fun isGraphIdReference(expression: CypherExpr): Boolean =
        when (expression) {
            is CypherExpr.Property -> expression.expression == CypherExpr.Variable(variable) && expression.propertyName == GRAPH_ID_PROPERTY
            is CypherExpr.FunctionCall ->
                expression.name.equals("graphId", ignoreCase = true) &&
                    !expression.distinct &&
                    expression.args.singleOrNull() == CypherExpr.Variable(variable)
            else -> false
        }

    private fun parameterPatterns(raw: Any): List<String>? = (raw as? List<*>)?.map { Regex.escape(it as? String ?: return null) }

    private fun addStringOperation(expression: CypherExpr.StringOp): Boolean {
        val property = property(expression.left) ?: return false
        val regex = (literal(expression.right) as? String)?.let { stringPattern(expression.op, Regex.escape(it)) }
        return regex != null && addProperty(property, regex)
    }

    private fun stringPattern(operation: String, escaped: String): String? =
        when (operation) {
            "STARTS WITH" -> "$escaped.*"
            "ENDS WITH" -> ".*$escaped"
            "CONTAINS" -> ".*$escaped.*"
            else -> null
        }

    private fun addRegex(expression: CypherExpr.RegexMatch): Boolean {
        val property = property(expression.left) ?: return false
        return (literal(expression.right) as? String)?.let { addProperty(property, it) } ?: false
    }

    private fun property(expression: CypherExpr): String? =
        (expression as? CypherExpr.Property)?.takeIf { it.expression == CypherExpr.Variable(variable) }?.propertyName

    private fun addProperty(property: String, regex: String?): Boolean =
        when (property) {
            METHOD_CLASS_PROPERTY -> setOnce(declaringClass, regex) { declaringClass = it }
            METHOD_NAME_PROPERTY -> setOnce(name, regex) { name = it }
            METHOD_RETURN_TYPE_PROPERTY -> setOnce(returnType, regex) { returnType = it }
            else -> false
        }

    private data class Signature(val declaringClass: String, val name: String, val parameters: List<String>)

    private fun addSignature(signature: String?): Boolean =
        signature?.let(::parseSignature)?.let { parsed ->
            setOnce(declaringClass, parsed.declaringClass) { declaringClass = it } &&
                setOnce(name, parsed.name) { name = it } &&
                setOnce(parameterTypes, parsed.parameters) { parameterTypes = it }
        } ?: false

    private fun parseSignature(signature: String): Signature? {
        val open = signature.lastIndexOf('(')
        val close = signature.lastIndexOf(')')
        val separator = signature.lastIndexOf('.', startIndex = open - 1)
        if (open <= 0 || close != signature.lastIndex || separator <= 0) return null
        val parameters = signature.substring(open + 1, close).takeIf(String::isNotEmpty)?.split(',').orEmpty().map(Regex::escape)
        return Signature(
            Regex.escape(signature.substring(0, separator)), Regex.escape(signature.substring(separator + 1, open)), parameters)
    }

    private fun literal(expression: CypherExpr): Any? =
        when (expression) {
            is CypherExpr.Literal -> expression.value
            is CypherExpr.ListLiteral -> expression.elements.map { literal(it) ?: return null }
            else -> null
        }

    private fun <T> setOnce(current: T?, candidate: T?, assign: (T) -> Unit): Boolean {
        if (candidate == null || current != null && current != candidate) return false
        if (current == null) assign(candidate)
        return true
    }
}
