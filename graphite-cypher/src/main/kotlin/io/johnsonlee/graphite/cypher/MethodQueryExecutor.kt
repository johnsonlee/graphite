package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.methodSlice
import io.johnsonlee.graphite.graph.methods

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
    fun tryExecute(
        clauses: List<CypherClause>,
        sources: List<CypherGraph>,
        qualified: Boolean,
        checkCancelled: () -> Unit,
        workTracker: CypherWorkTracker?
    ): CypherResult? {
        val match = clauses.firstOrNull() as? CypherClause.Match
            ?: return null
        if (match.optional || match.patterns.size != 1) return null
        val pattern = match.patterns.single()
        if (pattern.pathVariable != null || pattern.elements.size != 1) return null
        val nodePattern = pattern.elements.single() as? PatternElement.NodePattern
            ?: return null
        if (nodePattern.labels.size != 1 || !isMethodLabel(nodePattern.labels.single())) return null
        val variable = nodePattern.variable ?: return null

        var clauseIndex = 1
        val where = (clauses.getOrNull(clauseIndex) as? CypherClause.Where)?.also { clauseIndex++ }
        val ret = clauses.getOrNull(clauseIndex) as? CypherClause.Return ?: return null
        clauseIndex++
        if (ret.distinct || ret.items.any { containsAggregation(it.expression) }) {
            return tryExecuteCount(
                ret,
                nodePattern,
                variable,
                where,
                clausesComplete = clauseIndex == clauses.size,
                sources,
                qualified,
                checkCancelled
            )
        }
        val skip = (clauses.getOrNull(clauseIndex) as? CypherClause.Skip)?.also { clauseIndex++ }
        val limit = (clauses.getOrNull(clauseIndex) as? CypherClause.Limit)?.also { clauseIndex++ }
        if (clauseIndex != clauses.size) return null

        val skipCount = literalCount(skip?.count, default = 0) ?: return null
        val limitCount = literalCount(limit?.count, default = Int.MAX_VALUE) ?: return null
        val requested = saturatedAdd(skipCount, limitCount)
        if (requested == 0) return CypherResult(columns(ret), emptyList())

        val predicate = MethodPredicate(variable).also { candidate ->
            nodePattern.properties.forEach { (property, value) ->
                if (!candidate.addEquality(property, value)) return null
            }
            if (where != null && !candidate.add(where.condition)) return null
        }

        val bindings = ArrayList<Map<String, Any?>>(minOf(requested, INITIAL_METHOD_RESULT_CAPACITY))
        val cancellationConsumer = workTracker?.let { tracker ->
            MethodMetadataScanConsumer { tracker.checkCancelled() }
        }
        for (source in sources) {
            checkCancelled()
            if (bindings.size >= requested) break
            val methods = if (cancellationConsumer == null) {
                source.graph.methodSlice(predicate.pattern(), requested)
                    ?: source.graph.methods(predicate.pattern()).take(requested).toList()
            } else {
                source.graph.methodSlice(predicate.pattern(), requested, cancellationConsumer)
                    ?: source.graph.methods(predicate.pattern(), cancellationConsumer).take(requested).toList()
            }
            methods.forEach { method ->
                checkCancelled()
                if (bindings.size >= requested) return@forEach
                bindings += mutableMapOf<String, Any?>(
                    variable to MethodValue(source.id.takeIf { qualified }, method)
                ).apply {
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

    private fun columns(ret: CypherClause.Return): List<String> =
        ret.items.map { it.alias ?: it.expression.toCypherString() }

    private fun literalCount(expression: CypherExpr?, default: Int): Int? = if (expression == null) {
        default
    } else {
        ((expression as? CypherExpr.Literal)?.value as? Number)
            ?.toLong()
            ?.coerceIn(0, Int.MAX_VALUE.toLong())
            ?.toInt()
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

    @Suppress("ComplexCondition", "LongParameterList", "ReturnCount")
    private fun tryExecuteCount(
        ret: CypherClause.Return,
        nodePattern: PatternElement.NodePattern,
        variable: String,
        where: CypherClause.Where?,
        clausesComplete: Boolean,
        sources: List<CypherGraph>,
        qualified: Boolean,
        checkCancelled: () -> Unit
    ): CypherResult? {
        if (ret.distinct || where != null || nodePattern.properties.isNotEmpty() || !clausesComplete) return null
        val item = ret.items.singleOrNull() ?: return null
        val countExpression = item.expression
        val supported = countExpression is CypherExpr.CountStar ||
            countExpression is CypherExpr.FunctionCall &&
            countExpression.name.equals("count", ignoreCase = true) &&
            !countExpression.distinct &&
            countExpression.args.singleOrNull() == CypherExpr.Variable(variable)
        if (!supported) return null

        var total = 0L
        val contributingSources = linkedSetOf<String>()
        for (source in sources) {
            checkCancelled()
            val count = source.graph.methodCount() ?: return null
            if (count > 0L) contributingSources += source.id
            total = try {
                Math.addExact(total, count)
            } catch (_: ArithmeticException) {
                return null
            }
        }
        val row = mutableMapOf<String, Any?>(columns(ret).single() to total)
        if (qualified && contributingSources.isNotEmpty()) {
            row[INTERNAL_PROVENANCE_KEY] = contributingSources
        }
        return CypherResult(columns(ret), listOf(row))
    }

    fun isMethodLabel(label: String): Boolean = label.equals(METHOD_LABEL, ignoreCase = true)

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
            METHOD_SIGNATURE_PROPERTY -> addSignature(raw as? String)
            METHOD_PARAMETER_TYPES_PROPERTY -> {
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
        METHOD_CLASS_PROPERTY -> setOnce(declaringClass, regex) { declaringClass = it }
        METHOD_NAME_PROPERTY -> setOnce(name, regex) { name = it }
        METHOD_RETURN_TYPE_PROPERTY -> setOnce(returnType, regex) { returnType = it }
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
