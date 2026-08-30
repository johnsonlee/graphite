package io.johnsonlee.graphite.cypher

import java.math.BigDecimal

internal fun cypherEquals(left: Any?, right: Any?): Boolean? = when {
    left == null || right == null -> null
    left is Number && right is Number -> cypherNumbersEqual(left, right)
    left is List<*> && right is List<*> -> cypherListsEqual(left, right)
    left is Map<*, *> && right is Map<*, *> -> cypherMapsEqual(left, right)
    else -> left == right
}

private fun cypherNumbersEqual(left: Number, right: Number): Boolean {
    val bothIntegral = left.isIntegralNumber() && right.isIntegralNumber()
    val sameFloatingType = left.javaClass === right.javaClass && (left is Float || left is Double)
    val eitherNonFinite = !left.toDouble().isFinite() || !right.toDouble().isFinite()
    return when {
        bothIntegral -> left.toLong() == right.toLong()
        sameFloatingType -> left.toDouble() == right.toDouble()
        eitherNonFinite -> left.toDouble() == right.toDouble()
        else -> left.toCypherBigDecimal().compareTo(right.toCypherBigDecimal()) == 0
    }
}

private fun Number.toCypherBigDecimal(): BigDecimal = if (isIntegralNumber()) {
    BigDecimal.valueOf(toLong())
} else {
    BigDecimal(toString())
}

private fun Number.isIntegralNumber(): Boolean =
    this is Byte || this is Short || this is Int || this is Long

internal fun Number.toExactLongOrNull(): Long? = when {
    isIntegralNumber() -> toLong()
    !toDouble().isFinite() -> null
    else -> try {
        BigDecimal(toString()).longValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}

internal fun cypherValueKey(value: Any?): Any = when (value) {
    null -> CypherNullKey
    is Number -> {
        val doubleValue = value.toDouble()
        if (doubleValue.isFinite()) CypherNumberKey(value.toCypherBigDecimal().stripTrailingZeros())
        else CypherNonFiniteNumberKey(doubleValue)
    }
    is List<*> -> if (value.any(::requiresCypherNormalization)) {
        CypherListKey(value.map(::cypherValueKey))
    } else {
        value
    }
    is Map<*, *> -> if (value.entries.any { (key, item) ->
        requiresCypherNormalization(key) || requiresCypherNormalization(item)
    }) {
        CypherMapKey(value.entries.associate { (key, item) ->
            cypherValueKey(key) to cypherValueKey(item)
        })
    } else {
        value
    }
    else -> value
}

internal fun requiresCypherNormalization(value: Any?): Boolean = when (value) {
    is Number -> true
    is List<*> -> value.any(::requiresCypherNormalization)
    is Map<*, *> -> value.entries.any { (key, item) ->
        requiresCypherNormalization(key) || requiresCypherNormalization(item)
    }
    else -> false
}

private data object CypherNullKey

private data class CypherNumberKey(val value: BigDecimal)

private data class CypherNonFiniteNumberKey(val value: Double)

private data class CypherListKey(val values: List<Any>)

private data class CypherMapKey(val values: Map<Any, Any>)

@Suppress("ReturnCount")
private fun cypherListsEqual(left: List<*>, right: List<*>): Boolean? {
    if (left.size != right.size) return false
    var containsNullComparison = false
    for (index in left.indices) {
        when (cypherEquals(left[index], right[index])) {
            false -> return false
            null -> containsNullComparison = true
            true -> Unit
        }
    }
    return if (containsNullComparison) null else true
}

@Suppress("ReturnCount")
private fun cypherMapsEqual(left: Map<*, *>, right: Map<*, *>): Boolean? {
    if (left.keys != right.keys) return false
    var containsNullComparison = false
    for (key in left.keys) {
        when (cypherEquals(left[key], right[key])) {
            false -> return false
            null -> containsNullComparison = true
            true -> Unit
        }
    }
    return if (containsNullComparison) null else true
}
