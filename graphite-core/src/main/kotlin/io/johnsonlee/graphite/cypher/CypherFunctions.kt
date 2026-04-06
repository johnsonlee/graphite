package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*
import kotlin.math.*

/**
 * Complete openCypher function library.
 *
 * All scalar functions accept [Any?] arguments and return [Any?].
 * Aggregation functions are dispatched separately via [aggregate].
 */
object CypherFunctions {

    fun call(name: String, args: List<Any?>): Any? = when (name.lowercase()) {
        // Aggregation (must be handled in aggregation pipeline, not inline)
        "count" -> throw CypherAggregationException(name)
        "sum" -> throw CypherAggregationException(name)
        "avg" -> throw CypherAggregationException(name)
        "min" -> throw CypherAggregationException(name)
        "max" -> throw CypherAggregationException(name)
        "collect" -> throw CypherAggregationException(name)
        "percentilecont" -> throw CypherAggregationException(name)
        "percentiledisc" -> throw CypherAggregationException(name)
        "stdev" -> throw CypherAggregationException(name)
        "stdevp" -> throw CypherAggregationException(name)

        // Scalar
        "id" -> id(args[0])
        "coalesce" -> coalesce(args)
        "timestamp" -> System.currentTimeMillis()
        "tointeger", "toint" -> toInteger(args[0])
        "tofloat" -> toFloat(args[0])
        "toboolean" -> toBoolean(args[0])
        "tostring" -> args[0]?.toString()
        "properties" -> properties(args[0])
        "keys" -> keys(args[0])
        "labels" -> labels(args[0])
        "type" -> type(args[0])

        // String
        "tolower", "tolowercase" -> (args[0] as? String)?.lowercase()
        "toupper", "touppercase" -> (args[0] as? String)?.uppercase()
        "trim" -> (args[0] as? String)?.trim()
        "ltrim" -> (args[0] as? String)?.trimStart()
        "rtrim" -> (args[0] as? String)?.trimEnd()
        "replace" -> (args[0] as? String)?.replace(args[1] as String, args[2] as String)
        "substring" -> substring(args)
        "split" -> (args[0] as? String)?.split(args[1] as String)
        "size" -> size(args[0])
        "length" -> size(args[0])
        "left" -> (args[0] as? String)?.take((args[1] as Number).toInt())
        "right" -> (args[0] as? String)?.takeLast((args[1] as Number).toInt())
        "reverse" -> reverse(args[0])

        // List
        "head" -> (args[0] as? List<*>)?.firstOrNull()
        "tail" -> (args[0] as? List<*>)?.drop(1)
        "last" -> (args[0] as? List<*>)?.lastOrNull()
        "range" -> range(args)
        "nodes" -> nodes(args[0])
        "relationships" -> relationships(args[0])

        // Math - basic
        "abs" -> abs(args[0])
        "ceil" -> ceil(toDouble(args[0]))
        "floor" -> floor(toDouble(args[0]))
        "round" -> roundHalfUp(toDouble(args[0]))
        "sign" -> sign(args[0])
        "rand" -> Math.random()

        // Math - logarithmic
        "sqrt" -> sqrt(toDouble(args[0]))
        "exp" -> exp(toDouble(args[0]))
        "log" -> ln(toDouble(args[0]))
        "log10" -> log10(toDouble(args[0]))
        "e" -> Math.E

        // Math - trigonometric
        "sin" -> sin(toDouble(args[0]))
        "cos" -> cos(toDouble(args[0]))
        "tan" -> tan(toDouble(args[0]))
        "asin" -> asin(toDouble(args[0]))
        "acos" -> acos(toDouble(args[0]))
        "atan" -> atan(toDouble(args[0]))
        "atan2" -> atan2(toDouble(args[0]), toDouble(args[1]))
        "cot" -> 1.0 / tan(toDouble(args[0]))
        "pi" -> Math.PI
        "degrees" -> Math.toDegrees(toDouble(args[0]))
        "radians" -> Math.toRadians(toDouble(args[0]))

        // Predicate
        "exists" -> args[0] != null

        else -> throw CypherException("Unknown function: $name")
    }

    // ========================================================================
    // Aggregation functions (called during result aggregation, not inline)
    // ========================================================================

    fun aggregate(name: String, values: List<Any?>): Any? = when (name.lowercase()) {
        "count" -> values.size.toLong()
        "sum" -> values.filterNotNull().sumOf { toDouble(it) }
        "avg" -> values.filterNotNull().let { if (it.isEmpty()) null else it.sumOf { v -> toDouble(v) } / it.size }
        "min" -> values.filterNotNull().minByOrNull { toDouble(it) }
        "max" -> values.filterNotNull().maxByOrNull { toDouble(it) }
        "collect" -> values.toList()
        "percentilecont" -> percentileCont(values, 0.5)
        "percentiledisc" -> percentileDisc(values, 0.5)
        "stdev" -> stdev(values, sample = true)
        "stdevp" -> stdev(values, sample = false)
        else -> throw CypherException("Unknown aggregation: $name")
    }

    /**
     * Aggregation with an explicit percentile parameter.
     */
    fun aggregate(name: String, values: List<Any?>, percentile: Double): Any? = when (name.lowercase()) {
        "percentilecont" -> percentileCont(values, percentile)
        "percentiledisc" -> percentileDisc(values, percentile)
        else -> aggregate(name, values)
    }

    fun isAggregation(name: String): Boolean = name.lowercase() in AGGREGATION_NAMES

    // ========================================================================
    // Implementation details
    // ========================================================================

    private val AGGREGATION_NAMES = setOf(
        "count", "sum", "avg", "min", "max", "collect",
        "percentilecont", "percentiledisc", "stdev", "stdevp"
    )

    private fun id(value: Any?): Any? = when (value) {
        is Node -> value.id.value
        else -> null
    }

    private fun coalesce(args: List<Any?>): Any? = args.firstOrNull { it != null }

    internal fun toInteger(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        is Boolean -> if (value) 1L else 0L
        else -> null
    }

    internal fun toFloat(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    internal fun toBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    private fun properties(value: Any?): Map<String, Any?>? = when (value) {
        is Node -> NodePropertyAccessor.getAllProperties(value)
        else -> null
    }

    private fun keys(value: Any?): List<String>? = when (value) {
        is Node -> NodePropertyAccessor.getAllProperties(value).keys.toList()
        else -> null
    }

    internal fun labels(value: Any?): List<String> = when (value) {
        is CallSiteNode -> listOf("CallSiteNode")
        is IntConstant -> listOf("IntConstant", "Constant")
        is StringConstant -> listOf("StringConstant", "Constant")
        is LongConstant -> listOf("LongConstant", "Constant")
        is FloatConstant -> listOf("FloatConstant", "Constant")
        is DoubleConstant -> listOf("DoubleConstant", "Constant")
        is BooleanConstant -> listOf("BooleanConstant", "Constant")
        is NullConstant -> listOf("NullConstant", "Constant")
        is EnumConstant -> listOf("EnumConstant", "Constant")
        is LocalVariable -> listOf("LocalVariable")
        is FieldNode -> listOf("FieldNode")
        is ParameterNode -> listOf("ParameterNode")
        is ReturnNode -> listOf("ReturnNode")
        else -> emptyList()
    }

    internal fun type(value: Any?): String? = when (value) {
        is DataFlowEdge -> "DATAFLOW"
        is CallEdge -> "CALL"
        is TypeEdge -> "TYPE"
        is ControlFlowEdge -> "CONTROL_FLOW"
        else -> null
    }

    private fun size(value: Any?): Int? = when (value) {
        is String -> value.length
        is List<*> -> value.size
        else -> null
    }

    private fun substring(args: List<Any?>): String? {
        val str = args[0] as? String ?: return null
        val start = (args[1] as Number).toInt()
        return if (args.size > 2) {
            val length = (args[2] as Number).toInt()
            str.substring(start, minOf(start + length, str.length))
        } else {
            str.substring(start)
        }
    }

    private fun reverse(value: Any?): Any? = when (value) {
        is String -> value.reversed()
        is List<*> -> value.reversed()
        else -> null
    }

    private fun range(args: List<Any?>): List<Long> {
        val start = (args[0] as Number).toLong()
        val end = (args[1] as Number).toLong()
        val step = if (args.size > 2) (args[2] as Number).toLong() else 1L
        if (step == 0L) throw CypherException("Step cannot be zero in range()")
        return if (step > 0) (start..end step step).toList()
        else (start downTo end step -step).toList()
    }

    private fun nodes(value: Any?): List<Node>? = when (value) {
        is PathFinder.Path -> value.nodes
        is List<*> -> value.filterIsInstance<Node>()
        else -> null
    }

    private fun relationships(value: Any?): List<Edge>? = when (value) {
        is PathFinder.Path -> value.edges
        is List<*> -> value.filterIsInstance<Edge>()
        else -> null
    }

    private fun abs(value: Any?): Any? = when (value) {
        is Int -> kotlin.math.abs(value)
        is Long -> kotlin.math.abs(value)
        is Float -> kotlin.math.abs(value)
        is Double -> kotlin.math.abs(value)
        else -> null
    }

    /**
     * openCypher round uses half-up rounding (2.5 -> 3), not banker's rounding.
     */
    private fun roundHalfUp(value: Double): Double = floor(value + 0.5)

    private fun sign(value: Any?): Int {
        val d = toDouble(value)
        return if (d > 0.0) 1 else if (d < 0.0) -1 else 0
    }

    internal fun toDouble(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun percentileCont(values: List<Any?>, p: Double): Any? {
        val nums = values.filterNotNull().map { toDouble(it) }.sorted()
        if (nums.isEmpty()) return null
        val idx = p * (nums.size - 1)
        val lower = nums[idx.toInt()]
        val upper = nums[minOf(idx.toInt() + 1, nums.size - 1)]
        val frac = idx - idx.toInt()
        return lower + frac * (upper - lower)
    }

    private fun percentileDisc(values: List<Any?>, p: Double): Any? {
        val nums = values.filterNotNull().map { toDouble(it) }.sorted()
        if (nums.isEmpty()) return null
        val idx = kotlin.math.ceil(p * nums.size).toInt() - 1
        return nums[maxOf(0, idx)]
    }

    private fun stdev(values: List<Any?>, sample: Boolean): Any? {
        val nums = values.filterNotNull().map { toDouble(it) }
        if (nums.size < 2) return null
        val mean = nums.sum() / nums.size
        val divisor = if (sample) (nums.size - 1) else nums.size
        val variance = nums.sumOf { (it - mean).pow(2) } / divisor
        return sqrt(variance)
    }
}

class CypherException(message: String) : RuntimeException(message)

class CypherAggregationException(val functionName: String) :
    RuntimeException("Aggregation function '$functionName' must be used in RETURN or WITH clause")
