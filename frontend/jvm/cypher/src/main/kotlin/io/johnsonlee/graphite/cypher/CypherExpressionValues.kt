package io.johnsonlee.graphite.cypher

internal fun addValues(left: Any, right: Any, checkCancelled: (() -> Unit)?): Any =
    when {
        left is String || right is String -> "$left$right"
        left is List<*> && right is List<*> -> concatenateLists(left, right, checkCancelled)
        left is List<*> -> concatenateLists(left, listOf(right), checkCancelled)
        right is List<*> -> concatenateLists(listOf(left), right, checkCancelled)
        else -> arithmeticValues(left, right) { a, b -> a + b }
    }

internal fun arithmeticValues(left: Any, right: Any, op: (Double, Double) -> Double): Any {
    val l = expressionNumber(left)
    val r = expressionNumber(right)
    val result = op(l, r)
    // Preserve integer type when both operands are integers
    val integerPair = left is Int && right is Int
    val longPair = left is Long && right is Long
    return if (integerPair || longPair) {
        if (result == result.toLong().toDouble()) result.toLong() else result
    } else {
        result
    }
}

internal fun compareExpressionValues(a: Any, b: Any): Int =
    when {
        a is Number && b is Number -> compareCypherNumbers(a, b)
        a is String && b is String -> a.compareTo(b)
        a is Boolean && b is Boolean -> a.compareTo(b)
        a is QualifiedNode && b is QualifiedNode -> if (a == b) 0 else a.elementId.compareTo(b.elementId)
        a is QualifiedEdge && b is QualifiedEdge ->
            if (a == b) {
                0
            } else {
                "${a.graphId}:${a.edge}".compareTo("${b.graphId}:${b.edge}")
            }
        else -> a.toString().compareTo(b.toString())
    }

internal fun threeValuedAnd(a: Boolean?, b: Boolean?): Boolean? =
    when {
        a == false || b == false -> false
        a == null || b == null -> null
        else -> a && b
    }

internal fun threeValuedOr(a: Boolean?, b: Boolean?): Boolean? =
    when {
        a == true || b == true -> true
        a == null || b == null -> null
        else -> a || b
    }

internal fun expressionNumber(value: Any?): Double =
    when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
