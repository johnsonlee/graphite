package io.johnsonlee.graphite.cypher

import kotlin.math.abs
import kotlin.math.floor

internal object CypherScalarValues {
    fun coalesce(args: List<Any?>): Any? = args.firstOrNull { it != null }

    fun toInteger(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        is Boolean -> if (value) 1L else 0L
        else -> null
    }

    fun toFloat(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    fun toBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    fun abs(value: Any?): Any? = when (value) {
        is Int -> kotlin.math.abs(value)
        is Long -> kotlin.math.abs(value)
        is Float -> kotlin.math.abs(value)
        is Double -> kotlin.math.abs(value)
        else -> null
    }

    /**
     * openCypher round uses half-up rounding (2.5 -> 3), not banker's rounding.
     */
    fun roundHalfUp(value: Double): Double = floor(value + DEFAULT_PERCENTILE)

    fun sign(value: Any?): Int {
        val d = toDouble(value)
        return if (d > 0.0) 1 else if (d < 0.0) -1 else 0
    }

    fun toDouble(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
}
