package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.MethodPattern

private const val METHOD_LABEL = "Method"
private const val INITIAL_METHOD_RESULT_CAPACITY = 1_024

/** Executes bounded Cypher reads over the graph's method metadata index. */
internal object MethodQueryExecutor {

    fun referencesMethod(clauses: List<CypherClause>): Boolean = clauses.any { clause ->
        clause is CypherClause.Match && clause.patterns.any { pattern ->
            pattern.elements.any { element ->
                element is PatternElement.NodePattern && element.labels.any(::isMethodLabel)
            }
        }
    }

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun execute(
        clauses: List<CypherClause>,
        sources: List<CypherGraph>,
        qualified: Boolean,
        checkCancelled: () -> Unit
    ): CypherResult {
        val match = clauses.firstOrNull() as? CypherClause.Match
            ?: unsupported()
        if (match.optional || match.patterns.size != 1) unsupported()
        val pattern = match.patterns.single()
        if (pattern.pathVariable != null || pattern.elements.size != 1) unsupported()
        val nodePattern = pattern.elements.single() as? PatternElement.NodePattern
            ?: unsupported()
        if (nodePattern.labels.size != 1 || !isMethodLabel(nodePattern.labels.single())) unsupported()
        val variable = nodePattern.variable ?: unsupported()

        var clauseIndex = 1
        val where = (clauses.getOrNull(clauseIndex) as? CypherClause.Where)?.also { clauseIndex++ }
        val ret = clauses.getOrNull(clauseIndex) as? CypherClause.Return ?: unsupported()
        clauseIndex++
        if (ret.distinct || ret.items.any { containsAggregation(it.expression) }) unsupported()
        val skip = (clauses.getOrNull(clauseIndex) as? CypherClause.Skip)?.also { clauseIndex++ }
        val limit = (clauses.getOrNull(clauseIndex) as? CypherClause.Limit)?.also { clauseIndex++ }
        if (clauseIndex != clauses.size) unsupported()

        val skipCount = literalCount(skip?.count, default = 0)
        val limitCount = literalCount(limit?.count, default = Int.MAX_VALUE)
        val requested = saturatedAdd(skipCount, limitCount)
        if (requested == 0) return CypherResult(columns(ret), emptyList())

        val predicate = MethodPredicate(variable)
        nodePattern.properties.forEach { (property, value) ->
            if (!predicate.addEquality(property, value)) unsupported()
        }
        if (where != null && !predicate.add(where.condition)) unsupported()

        val bindings = ArrayList<Map<String, Any?>>(minOf(requested, INITIAL_METHOD_RESULT_CAPACITY))
        for (source in sources) {
            checkCancelled()
            val remaining = requested - bindings.size
            if (remaining <= 0) break
            val methods = source.graph.methodSlice(predicate.pattern(), remaining)
                ?: source.graph.methods(predicate.pattern()).take(remaining).toList()
            methods.forEach { method ->
                checkCancelled()
                bindings += mutableMapOf<String, Any?>(variable to methodValue(method, source, qualified)).apply {
                    if (qualified) put(INTERNAL_PROVENANCE_KEY, setOf(source.id))
                }
            }
        }

        val evaluator = ExpressionEvaluator(checkCancelled)
        val columns = columns(ret)
        val rows = bindings.drop(skipCount).take(limitCount).map { binding ->
            mutableMapOf<String, Any?>().apply {
                ret.items.forEachIndexed { index, item ->
                    put(columns[index], evaluator.evaluate(item.expression, binding))
                }
                binding[INTERNAL_PROVENANCE_KEY]?.let { put(INTERNAL_PROVENANCE_KEY, it) }
            }
        }
        checkCancelled()
        return CypherResult(columns, rows)
    }

    private fun methodValue(method: MethodDescriptor, source: CypherGraph, qualified: Boolean): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "signature" to method.signature,
            "class" to method.declaringClass.className,
            "name" to method.name,
            "parameter_types" to method.parameterTypes.map { it.className },
            "return_type" to method.returnType.className
        ).apply {
            if (qualified) put(GRAPH_ID_PROPERTY, source.id)
        }

    private fun columns(ret: CypherClause.Return): List<String> =
        ret.items.map { it.alias ?: it.expression.toCypherString() }

    private fun literalCount(expression: CypherExpr?, default: Int): Int {
        if (expression == null) return default
        val count = (expression as? CypherExpr.Literal)?.value as? Number ?: unsupported()
        return count.toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun saturatedAdd(left: Int, right: Int): Int =
        (left.toLong() + right.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun containsAggregation(expression: CypherExpr): Boolean = when (expression) {
        is CypherExpr.CountStar -> true
        is CypherExpr.FunctionCall -> CypherFunctions.isAggregation(expression.name) ||
            expression.args.any(::containsAggregation)
        is CypherExpr.Property -> containsAggregation(expression.expression)
        is CypherExpr.BinaryOp -> containsAggregation(expression.left) || containsAggregation(expression.right)
        is CypherExpr.Comparison -> containsAggregation(expression.left) || containsAggregation(expression.right)
        is CypherExpr.Distinct -> containsAggregation(expression.expression)
        else -> false
    }

    private fun isMethodLabel(label: String): Boolean = label.equals(METHOD_LABEL, ignoreCase = true)

    private fun unsupported(): Nothing = throw CypherException(
        "Method metadata queries support MATCH (m:Method), optional indexed WHERE, RETURN, SKIP, and LIMIT"
    )
}

@Suppress("ReturnCount")
private class MethodPredicate(private val variable: String) {
    private var declaringClass: String? = null
    private var name: String? = null
    private var parameterTypes: List<String>? = null
    private var returnType: String? = null

    fun pattern(): MethodPattern = MethodPattern(
        declaringClass = declaringClass,
        name = name,
        parameterTypes = parameterTypes,
        returnType = returnType,
        useRegex = true
    )

    fun add(expression: CypherExpr): Boolean = when (expression) {
        is CypherExpr.And -> add(expression.left) && add(expression.right)
        is CypherExpr.Comparison -> expression.op == "=" && addComparison(expression.left, expression.right)
        is CypherExpr.StringOp -> addStringOperation(expression)
        is CypherExpr.RegexMatch -> addRegex(expression)
        else -> false
    }

    fun addEquality(property: String, value: CypherExpr): Boolean {
        val raw = literal(value) ?: return false
        return when (property) {
            "signature" -> addSignature(raw as? String)
            "parameter_types" -> {
                val parameters = (raw as? List<*>)?.map { it as? String ?: return false } ?: return false
                setOnce(parameterTypes, parameters.map(Regex::escape)) { parameterTypes = it }
            }
            else -> addProperty(property, (raw as? String)?.let(Regex::escape))
        }
    }

    private fun addComparison(left: CypherExpr, right: CypherExpr): Boolean {
        val leftProperty = property(left)
        val rightProperty = property(right)
        return when {
            leftProperty != null -> addEquality(leftProperty, right)
            rightProperty != null -> addEquality(rightProperty, left)
            else -> false
        }
    }

    private fun addStringOperation(expression: CypherExpr.StringOp): Boolean {
        val property = property(expression.left) ?: return false
        val value = (literal(expression.right) as? String) ?: return false
        val escaped = Regex.escape(value)
        val regex = when (expression.op) {
            "STARTS WITH" -> "$escaped.*"
            "ENDS WITH" -> ".*$escaped"
            "CONTAINS" -> ".*$escaped.*"
            else -> return false
        }
        return addProperty(property, regex)
    }

    private fun addRegex(expression: CypherExpr.RegexMatch): Boolean {
        val property = property(expression.left) ?: return false
        val regex = literal(expression.right) as? String ?: return false
        return addProperty(property, regex)
    }

    private fun property(expression: CypherExpr): String? {
        val property = expression as? CypherExpr.Property ?: return null
        val owner = property.expression as? CypherExpr.Variable ?: return null
        return property.propertyName.takeIf { owner.name == variable }
    }

    private fun addProperty(property: String, regex: String?): Boolean = when (property) {
        "class" -> setOnce(declaringClass, regex) { declaringClass = it }
        "name" -> setOnce(name, regex) { name = it }
        "return_type" -> setOnce(returnType, regex) { returnType = it }
        else -> false
    }

    private fun addSignature(signature: String?): Boolean {
        signature ?: return false
        val open = signature.lastIndexOf('(')
        val close = signature.lastIndexOf(')')
        val separator = signature.lastIndexOf('.', startIndex = open - 1)
        if (open <= 0 || close != signature.lastIndex || separator <= 0) return false
        val parameters = signature.substring(open + 1, close)
            .takeIf(String::isNotEmpty)
            ?.split(',')
            .orEmpty()
            .map(Regex::escape)
        return setOnce(declaringClass, Regex.escape(signature.substring(0, separator))) { declaringClass = it } &&
            setOnce(name, Regex.escape(signature.substring(separator + 1, open))) { name = it } &&
            setOnce(parameterTypes, parameters) { parameterTypes = it }
    }

    private fun literal(expression: CypherExpr): Any? = when (expression) {
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
