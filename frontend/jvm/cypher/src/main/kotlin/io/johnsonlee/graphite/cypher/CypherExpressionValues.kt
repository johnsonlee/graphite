package io.johnsonlee.graphite.cypher

internal fun addValues(left: Any, right: Any, checkCancelled: (() -> Unit)?): Any =
    when {
        left is String || right is String -> "$left$right"
        left is List<*> && right is List<*> -> concatenateLists(left, right, checkCancelled)
        left is List<*> -> concatenateLists(left, listOf(right), checkCancelled)
        right is List<*> -> concatenateLists(listOf(left), right, checkCancelled)
        else -> integerOrFloat(left, right, Math::addExact) { a, b -> a + b }
    }

private fun isInteger(value: Any): Boolean = value is Long || value is Int || value is Short || value is Byte

/**
 * openCypher arithmetic, as the Rust engine evaluates it: two integers give an integer (`7 / 2` is `3`, truncated), an overflow is an
 * error rather than a wrapped or widened value, and anything else is computed in floating point.
 */
internal fun integerOrFloat(left: Any, right: Any, integer: (Long, Long) -> Long, float: (Double, Double) -> Double): Any =
    if (isInteger(left) && isInteger(right)) {
        try {
            integer((left as Number).toLong(), (right as Number).toLong())
        } catch (overflow: ArithmeticException) {
            throw CypherException(overflow.message ?: "long overflow").apply { initCause(overflow) }
        }
    } else {
        float(expressionNumber(left), expressionNumber(right))
    }

private const val DIVISION_BY_ZERO = "Division by zero"

internal fun divideExact(a: Long, b: Long): Long =
    when {
        b == 0L -> throw CypherException(DIVISION_BY_ZERO)
        a == Long.MIN_VALUE && b == -1L -> throw ArithmeticException("long overflow")
        else -> a / b
    }

internal fun remainderExact(a: Long, b: Long): Long = if (b == 0L) throw CypherException(DIVISION_BY_ZERO) else a % b

internal fun divideFloat(a: Double, b: Double): Double = if (b == 0.0) throw CypherException(DIVISION_BY_ZERO) else a / b

internal fun remainderFloat(a: Double, b: Double): Double = if (b == 0.0) throw CypherException(DIVISION_BY_ZERO) else a % b

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
