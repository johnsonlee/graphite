package io.johnsonlee.graphite.cypher

import kotlin.math.abs
import kotlin.math.floor

internal fun coalesce(args: List<Any?>): Any? = args.firstOrNull { it != null }

internal fun integerValue(value: Any?): Long? =
    when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        is Boolean -> if (value) 1L else 0L
        else -> null
    }

internal fun floatValue(value: Any?): Double? =
    when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

internal fun booleanValue(value: Any?): Boolean? =
    when (value) {
        is Boolean -> value
        is String ->
            when (value.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        else -> null
    }

internal fun absoluteValue(value: Any?): Any? =
    when (value) {
        is Int -> kotlin.math.abs(value)
        is Long -> kotlin.math.abs(value)
        is Float -> kotlin.math.abs(value)
        is Double -> kotlin.math.abs(value)
        else -> null
    }

/** openCypher round uses half-up rounding (2.5 -> 3), not banker's rounding. */
internal fun roundHalfUp(value: Double): Double = floor(value + DEFAULT_PERCENTILE)

internal fun signValue(value: Any?): Int {
    val d = numericValue(value)
    return if (d > 0.0) 1 else if (d < 0.0) -1 else 0
}

internal fun numericValue(value: Any?): Double =
    when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
