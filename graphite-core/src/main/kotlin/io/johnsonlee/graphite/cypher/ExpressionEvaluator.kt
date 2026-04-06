package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*

/**
 * Evaluates Cypher expressions against a variable binding context.
 * Supports all openCypher expression types: arithmetic, boolean, comparison,
 * string operators, list operators, CASE, property access, function calls.
 */
class ExpressionEvaluator {

    private val regexCache = mutableMapOf<String, Regex>()

    /**
     * Evaluate a parsed expression node.
     * The expression is represented as a sealed class hierarchy.
     */
    fun evaluate(expr: CypherExpr, bindings: Map<String, Any?>): Any? = when (expr) {
        is CypherExpr.Literal -> expr.value
        is CypherExpr.Variable -> bindings[expr.name]
        is CypherExpr.Property -> {
            val obj = evaluate(expr.expression, bindings)
            resolveProperty(obj, expr.propertyName)
        }
        is CypherExpr.Parameter -> bindings[expr.name]
        is CypherExpr.FunctionCall -> {
            val args = expr.args.map { evaluate(it, bindings) }
            CypherFunctions.call(expr.name, args)
        }
        is CypherExpr.BinaryOp -> evaluateBinaryOp(expr, bindings)
        is CypherExpr.UnaryOp -> evaluateUnaryOp(expr, bindings)
        is CypherExpr.Comparison -> evaluateComparison(expr, bindings)
        is CypherExpr.StringOp -> evaluateStringOp(expr, bindings)
        is CypherExpr.ListOp -> evaluateListOp(expr, bindings)
        is CypherExpr.RegexMatch -> evaluateRegex(expr, bindings)
        is CypherExpr.IsNull -> evaluate(expr.expression, bindings) == null
        is CypherExpr.IsNotNull -> evaluate(expr.expression, bindings) != null
        is CypherExpr.CaseExpr -> evaluateCase(expr, bindings)
        is CypherExpr.ListLiteral -> expr.elements.map { evaluate(it, bindings) }
        is CypherExpr.MapLiteral -> expr.entries.mapValues { evaluate(it.value, bindings) }
        is CypherExpr.ListComprehension -> evaluateListComprehension(expr, bindings)
        is CypherExpr.Subscript -> evaluateSubscript(expr, bindings)
        is CypherExpr.Slice -> evaluateSlice(expr, bindings)
        is CypherExpr.Not -> {
            val value = evaluate(expr.expression, bindings)
            if (value == null) null else !(value as Boolean)
        }
        is CypherExpr.And -> {
            val left = evaluate(expr.left, bindings)
            val right = evaluate(expr.right, bindings)
            threeValuedAnd(left as? Boolean, right as? Boolean)
        }
        is CypherExpr.Or -> {
            val left = evaluate(expr.left, bindings)
            val right = evaluate(expr.right, bindings)
            threeValuedOr(left as? Boolean, right as? Boolean)
        }
        is CypherExpr.Xor -> {
            val left = evaluate(expr.left, bindings) as? Boolean
            val right = evaluate(expr.right, bindings) as? Boolean
            if (left == null || right == null) null else left xor right
        }
        is CypherExpr.Distinct -> evaluate(expr.expression, bindings)
        is CypherExpr.CountStar -> throw CypherAggregationException("count")
    }

    // ========================================================================
    // Binary operations (arithmetic)
    // ========================================================================

    private fun evaluateBinaryOp(expr: CypherExpr.BinaryOp, bindings: Map<String, Any?>): Any? {
        val left = evaluate(expr.left, bindings)
        val right = evaluate(expr.right, bindings)
        if (left == null || right == null) return null

        return when (expr.op) {
            "+" -> add(left, right)
            "-" -> arithmetic(left, right) { a, b -> a - b }
            "*" -> arithmetic(left, right) { a, b -> a * b }
            "/" -> arithmetic(left, right) { a, b ->
                if (b == 0.0) throw CypherException("Division by zero") else a / b
            }
            "%" -> arithmetic(left, right) { a, b -> a % b }
            "^" -> arithmetic(left, right) { a, b -> Math.pow(a, b) }
            else -> throw CypherException("Unknown operator: ${expr.op}")
        }
    }

    private fun evaluateUnaryOp(expr: CypherExpr.UnaryOp, bindings: Map<String, Any?>): Any? {
        val value = evaluate(expr.expression, bindings) ?: return null
        return when (expr.op) {
            "-" -> when (value) {
                is Int -> -value
                is Long -> -value
                is Float -> -value
                is Double -> -value
                else -> -toDouble(value)
            }
            "+" -> value
            else -> throw CypherException("Unknown unary operator: ${expr.op}")
        }
    }

    private fun add(left: Any, right: Any): Any = when {
        left is String || right is String -> "$left$right"
        left is List<*> && right is List<*> -> left + right
        left is List<*> -> left + right
        right is List<*> -> listOf(left) + right
        else -> arithmetic(left, right) { a, b -> a + b }
    }

    private fun arithmetic(left: Any, right: Any, op: (Double, Double) -> Double): Any {
        val l = toDouble(left)
        val r = toDouble(right)
        val result = op(l, r)
        // Preserve integer type when both operands are integers
        return if (left is Int && right is Int || left is Long && right is Long) {
            if (result == result.toLong().toDouble()) result.toLong() else result
        } else {
            result
        }
    }

    // ========================================================================
    // Comparison
    // ========================================================================

    private fun evaluateComparison(expr: CypherExpr.Comparison, bindings: Map<String, Any?>): Any? {
        val left = evaluate(expr.left, bindings)
        val right = evaluate(expr.right, bindings)

        if (left == null || right == null) {
            return when (expr.op) {
                "=" -> if (left == null && right == null) true else null
                "<>" -> if (left == null && right == null) false else null
                else -> null
            }
        }

        return when (expr.op) {
            "=" -> compareValues(left, right) == 0
            "<>" -> compareValues(left, right) != 0
            "<" -> compareValues(left, right) < 0
            ">" -> compareValues(left, right) > 0
            "<=" -> compareValues(left, right) <= 0
            ">=" -> compareValues(left, right) >= 0
            else -> throw CypherException("Unknown comparison: ${expr.op}")
        }
    }

    private fun compareValues(a: Any, b: Any): Int = when {
        a is Number && b is Number -> toDouble(a).compareTo(toDouble(b))
        a is String && b is String -> a.compareTo(b)
        a is Boolean && b is Boolean -> a.compareTo(b)
        else -> a.toString().compareTo(b.toString())
    }

    // ========================================================================
    // String operations
    // ========================================================================

    private fun evaluateStringOp(expr: CypherExpr.StringOp, bindings: Map<String, Any?>): Any? {
        val left = evaluate(expr.left, bindings) as? String ?: return null
        val right = evaluate(expr.right, bindings) as? String ?: return null

        return when (expr.op) {
            "STARTS WITH" -> left.startsWith(right)
            "ENDS WITH" -> left.endsWith(right)
            "CONTAINS" -> left.contains(right)
            else -> throw CypherException("Unknown string op: ${expr.op}")
        }
    }

    // ========================================================================
    // List operations
    // ========================================================================

    private fun evaluateListOp(expr: CypherExpr.ListOp, bindings: Map<String, Any?>): Any? {
        return when (expr.op) {
            "IN" -> {
                val element = evaluate(expr.left, bindings)
                val list = evaluate(expr.right, bindings) as? List<*> ?: return null
                element in list
            }
            else -> throw CypherException("Unknown list op: ${expr.op}")
        }
    }

    // ========================================================================
    // Regex
    // ========================================================================

    private fun evaluateRegex(expr: CypherExpr.RegexMatch, bindings: Map<String, Any?>): Any? {
        val value = evaluate(expr.left, bindings) as? String ?: return null
        val pattern = evaluate(expr.right, bindings) as? String ?: return null
        val regex = regexCache.getOrPut(pattern) { Regex(pattern) }
        return regex.matches(value)
    }

    // ========================================================================
    // CASE expression
    // ========================================================================

    private fun evaluateCase(expr: CypherExpr.CaseExpr, bindings: Map<String, Any?>): Any? {
        val testValue = expr.test?.let { evaluate(it, bindings) }

        for ((condition, result) in expr.whenClauses) {
            if (testValue != null) {
                // Simple CASE: compare test value with each when
                val whenValue = evaluate(condition, bindings)
                if (testValue == whenValue) return evaluate(result, bindings)
            } else {
                // Generic CASE: evaluate each when as boolean
                val condResult = evaluate(condition, bindings)
                if (condResult == true) return evaluate(result, bindings)
            }
        }

        return expr.elseExpr?.let { evaluate(it, bindings) }
    }

    // ========================================================================
    // List comprehension: [x IN list WHERE predicate | expression]
    // ========================================================================

    private fun evaluateListComprehension(
        expr: CypherExpr.ListComprehension,
        bindings: Map<String, Any?>
    ): Any? {
        val list = evaluate(expr.listExpr, bindings) as? List<*> ?: return null
        val results = mutableListOf<Any?>()

        for (element in list) {
            val innerBindings = bindings.toMutableMap()
            innerBindings[expr.variable] = element

            // Apply filter if present
            if (expr.predicate != null) {
                val keep = evaluate(expr.predicate, innerBindings)
                if (keep != true) continue
            }

            // Apply mapping if present, otherwise use element
            val value = if (expr.mapExpr != null) {
                evaluate(expr.mapExpr, innerBindings)
            } else {
                element
            }
            results.add(value)
        }

        return results
    }

    // ========================================================================
    // Subscript and slice
    // ========================================================================

    private fun evaluateSubscript(expr: CypherExpr.Subscript, bindings: Map<String, Any?>): Any? {
        val collection = evaluate(expr.expression, bindings)
        val index = (evaluate(expr.index, bindings) as? Number)?.toInt() ?: return null

        return when (collection) {
            is List<*> -> collection.getOrNull(if (index < 0) collection.size + index else index)
            is String -> collection.getOrNull(
                if (index < 0) collection.length + index else index
            )?.toString()
            else -> null
        }
    }

    private fun evaluateSlice(expr: CypherExpr.Slice, bindings: Map<String, Any?>): Any? {
        val collection = evaluate(expr.expression, bindings)
        val from = expr.from?.let { (evaluate(it, bindings) as? Number)?.toInt() }
        val to = expr.to?.let { (evaluate(it, bindings) as? Number)?.toInt() }

        return when (collection) {
            is List<*> -> {
                val start = from ?: 0
                val end = to ?: collection.size
                collection.subList(maxOf(0, start), minOf(collection.size, end))
            }
            is String -> {
                val start = from ?: 0
                val end = to ?: collection.length
                collection.substring(maxOf(0, start), minOf(collection.length, end))
            }
            else -> null
        }
    }

    // ========================================================================
    // Property resolution
    // ========================================================================

    private fun resolveProperty(obj: Any?, propertyName: String): Any? = when (obj) {
        is Node -> NodePropertyAccessor.getProperty(obj, propertyName)
        is Edge -> getEdgeProperty(obj, propertyName)
        is Map<*, *> -> obj[propertyName]
        is PathFinder.Path -> when (propertyName) {
            "length" -> obj.edges.size
            else -> null
        }
        else -> null
    }

    private fun getEdgeProperty(edge: Edge, prop: String): Any? = when (prop) {
        "type" -> CypherFunctions.call("type", listOf(edge))
        else -> when (edge) {
            is DataFlowEdge -> if (prop == "kind") edge.kind.name else null
            is CallEdge -> when (prop) {
                "virtual" -> edge.isVirtual
                "dynamic" -> edge.isDynamic
                else -> null
            }
            is TypeEdge -> if (prop == "kind") edge.kind.name else null
            is ControlFlowEdge -> when (prop) {
                "kind" -> edge.kind.name
                else -> null
            }
        }
    }

    // ========================================================================
    // Three-valued logic (Cypher null semantics)
    // ========================================================================

    private fun threeValuedAnd(a: Boolean?, b: Boolean?): Boolean? = when {
        a == false || b == false -> false
        a == null || b == null -> null
        else -> a && b
    }

    private fun threeValuedOr(a: Boolean?, b: Boolean?): Boolean? = when {
        a == true || b == true -> true
        a == null || b == null -> null
        else -> a || b
    }

    private fun toDouble(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
}

/**
 * AST for Cypher expressions.
 * Produced by the Cypher parser adapter from neo4j-cypher-dsl AST.
 */
sealed class CypherExpr {
    data class Literal(val value: Any?) : CypherExpr()
    data class Variable(val name: String) : CypherExpr()
    data class Property(val expression: CypherExpr, val propertyName: String) : CypherExpr()
    data class Parameter(val name: String) : CypherExpr()
    data class FunctionCall(
        val name: String,
        val args: List<CypherExpr>,
        val distinct: Boolean = false
    ) : CypherExpr()
    data class BinaryOp(val op: String, val left: CypherExpr, val right: CypherExpr) : CypherExpr()
    data class UnaryOp(val op: String, val expression: CypherExpr) : CypherExpr()
    data class Comparison(val op: String, val left: CypherExpr, val right: CypherExpr) : CypherExpr()
    data class StringOp(val op: String, val left: CypherExpr, val right: CypherExpr) : CypherExpr()
    data class ListOp(val op: String, val left: CypherExpr, val right: CypherExpr) : CypherExpr()
    data class RegexMatch(val left: CypherExpr, val right: CypherExpr) : CypherExpr()
    data class IsNull(val expression: CypherExpr) : CypherExpr()
    data class IsNotNull(val expression: CypherExpr) : CypherExpr()
    data class Not(val expression: CypherExpr) : CypherExpr()
    data class And(val left: CypherExpr, val right: CypherExpr) : CypherExpr()
    data class Or(val left: CypherExpr, val right: CypherExpr) : CypherExpr()
    data class Xor(val left: CypherExpr, val right: CypherExpr) : CypherExpr()
    data class CaseExpr(
        val test: CypherExpr?,
        val whenClauses: List<Pair<CypherExpr, CypherExpr>>,
        val elseExpr: CypherExpr?
    ) : CypherExpr()
    data class ListLiteral(val elements: List<CypherExpr>) : CypherExpr()
    data class MapLiteral(val entries: Map<String, CypherExpr>) : CypherExpr()
    data class ListComprehension(
        val variable: String,
        val listExpr: CypherExpr,
        val predicate: CypherExpr?,
        val mapExpr: CypherExpr?
    ) : CypherExpr()
    data class Subscript(val expression: CypherExpr, val index: CypherExpr) : CypherExpr()
    data class Slice(val expression: CypherExpr, val from: CypherExpr?, val to: CypherExpr?) : CypherExpr()
    data class Distinct(val expression: CypherExpr) : CypherExpr()
    object CountStar : CypherExpr()
}

/**
 * Render a [CypherExpr] back to a human-readable Cypher string.
 *
 * Used primarily for generating column names in query results when
 * no explicit alias is provided.
 */
fun CypherExpr.toCypherString(): String = when (this) {
    is CypherExpr.Literal -> when (value) {
        null -> "null"
        is String -> "'${value.replace("'", "\\'")}'"
        is Boolean -> value.toString()
        else -> value.toString()
    }
    is CypherExpr.Variable -> name
    is CypherExpr.Property -> "${expression.toCypherString()}.$propertyName"
    is CypherExpr.Parameter -> "\$$name"
    is CypherExpr.FunctionCall -> {
        val distinctStr = if (distinct) "DISTINCT " else ""
        "$name($distinctStr${args.joinToString(", ") { it.toCypherString() }})"
    }
    is CypherExpr.BinaryOp -> "${left.toCypherString()} $op ${right.toCypherString()}"
    is CypherExpr.UnaryOp -> "$op${expression.toCypherString()}"
    is CypherExpr.Comparison -> "${left.toCypherString()} $op ${right.toCypherString()}"
    is CypherExpr.StringOp -> "${left.toCypherString()} $op ${right.toCypherString()}"
    is CypherExpr.ListOp -> "${left.toCypherString()} $op ${right.toCypherString()}"
    is CypherExpr.RegexMatch -> "${left.toCypherString()} =~ ${right.toCypherString()}"
    is CypherExpr.IsNull -> "${expression.toCypherString()} IS NULL"
    is CypherExpr.IsNotNull -> "${expression.toCypherString()} IS NOT NULL"
    is CypherExpr.Not -> "NOT ${expression.toCypherString()}"
    is CypherExpr.And -> "${left.toCypherString()} AND ${right.toCypherString()}"
    is CypherExpr.Or -> "${left.toCypherString()} OR ${right.toCypherString()}"
    is CypherExpr.Xor -> "${left.toCypherString()} XOR ${right.toCypherString()}"
    is CypherExpr.CaseExpr -> buildString {
        append("CASE")
        if (test != null) append(" ${test.toCypherString()}")
        for ((cond, result) in whenClauses) {
            append(" WHEN ${cond.toCypherString()} THEN ${result.toCypherString()}")
        }
        if (elseExpr != null) append(" ELSE ${elseExpr.toCypherString()}")
        append(" END")
    }
    is CypherExpr.ListLiteral -> "[${elements.joinToString(", ") { it.toCypherString() }}]"
    is CypherExpr.MapLiteral -> "{${entries.entries.joinToString(", ") { "${it.key}: ${it.value.toCypherString()}" }}}"
    is CypherExpr.ListComprehension -> buildString {
        append("[$variable IN ${listExpr.toCypherString()}")
        if (predicate != null) append(" WHERE ${predicate.toCypherString()}")
        if (mapExpr != null) append(" | ${mapExpr.toCypherString()}")
        append("]")
    }
    is CypherExpr.Subscript -> "${expression.toCypherString()}[${index.toCypherString()}]"
    is CypherExpr.Slice -> buildString {
        append("${expression.toCypherString()}[")
        append(from?.toCypherString() ?: "")
        append("..")
        append(to?.toCypherString() ?: "")
        append("]")
    }
    is CypherExpr.Distinct -> "DISTINCT ${expression.toCypherString()}"
    is CypherExpr.CountStar -> "count(*)"
}
