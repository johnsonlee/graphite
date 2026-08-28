package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.ValueNode
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private const val FUNCTION_COUNT = "count"
private const val FUNCTION_SUM = "sum"
private const val FUNCTION_AVG = "avg"
private const val FUNCTION_MIN = "min"
private const val FUNCTION_MAX = "max"
private const val FUNCTION_COLLECT = "collect"
private const val FUNCTION_PERCENTILE_CONT = "percentilecont"
private const val FUNCTION_PERCENTILE_DISC = "percentiledisc"
private const val FUNCTION_STDEV = "stdev"
private const val FUNCTION_STDEVP = "stdevp"
private const val DEFAULT_PERCENTILE = 0.5
private const val CONSTANT_LABEL = "Constant"

internal data class NodeLabelDescriptor(
    val type: Class<out Node>,
    val labels: List<String>
)

internal val nodeLabelDescriptors = listOf(
    NodeLabelDescriptor(CallSiteNode::class.java, listOf("CallSiteNode")),
    NodeLabelDescriptor(IntConstant::class.java, listOf("IntConstant", CONSTANT_LABEL)),
    NodeLabelDescriptor(StringConstant::class.java, listOf("StringConstant", CONSTANT_LABEL)),
    NodeLabelDescriptor(LongConstant::class.java, listOf("LongConstant", CONSTANT_LABEL)),
    NodeLabelDescriptor(FloatConstant::class.java, listOf("FloatConstant", CONSTANT_LABEL)),
    NodeLabelDescriptor(DoubleConstant::class.java, listOf("DoubleConstant", CONSTANT_LABEL)),
    NodeLabelDescriptor(BooleanConstant::class.java, listOf("BooleanConstant", CONSTANT_LABEL)),
    NodeLabelDescriptor(NullConstant::class.java, listOf("NullConstant", CONSTANT_LABEL)),
    NodeLabelDescriptor(EnumConstant::class.java, listOf("EnumConstant", CONSTANT_LABEL)),
    NodeLabelDescriptor(LocalVariable::class.java, listOf("LocalVariable")),
    NodeLabelDescriptor(FieldNode::class.java, listOf("FieldNode")),
    NodeLabelDescriptor(ParameterNode::class.java, listOf("ParameterNode")),
    NodeLabelDescriptor(ReturnNode::class.java, listOf("ReturnNode")),
    NodeLabelDescriptor(ResourceFileNode::class.java, listOf("ResourceFileNode", "ResourceFile")),
    NodeLabelDescriptor(ResourceValueNode::class.java, listOf("ResourceValueNode", "ResourceValue", "Resource")),
    NodeLabelDescriptor(AnnotationNode::class.java, listOf("AnnotationNode", "Annotation"))
)

/**
 * Complete openCypher function library.
 *
 * All scalar functions accept [Any?] arguments and return [Any?].
 * Aggregation functions are dispatched separately via [aggregate].
 */
object CypherFunctions {

    fun call(name: String, args: List<Any?>): Any? = dispatch(name, args, null)

    internal fun call(
        name: String,
        args: List<Any?>,
        checkCancelled: () -> Unit
    ): Any? = dispatch(name, args, checkCancelled)

    @Suppress("CyclomaticComplexMethod")
    private fun dispatch(
        name: String,
        args: List<Any?>,
        checkCancelled: (() -> Unit)?
    ): Any? = when (name.lowercase()) {
        // Aggregation (must be handled in aggregation pipeline, not inline)
        FUNCTION_COUNT -> throw CypherAggregationException(name)
        FUNCTION_SUM -> throw CypherAggregationException(name)
        FUNCTION_AVG -> throw CypherAggregationException(name)
        FUNCTION_MIN -> throw CypherAggregationException(name)
        FUNCTION_MAX -> throw CypherAggregationException(name)
        FUNCTION_COLLECT -> throw CypherAggregationException(name)
        FUNCTION_PERCENTILE_CONT -> throw CypherAggregationException(name)
        FUNCTION_PERCENTILE_DISC -> throw CypherAggregationException(name)
        FUNCTION_STDEV -> throw CypherAggregationException(name)
        FUNCTION_STDEVP -> throw CypherAggregationException(name)

        // Scalar
        "id" -> id(args[0])
        "elementid", "qualifiedid" -> elementId(args[0])
        "graphid" -> graphId(args[0])
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
        "reverse" -> reverse(args[0], checkCancelled)

        // List
        "head" -> (args[0] as? List<*>)?.firstOrNull()
        "tail" -> tail(args[0], checkCancelled)
        "last" -> (args[0] as? List<*>)?.lastOrNull()
        "range" -> range(args, checkCancelled)
        "nodes" -> nodes(args[0], checkCancelled)
        "relationships" -> relationships(args[0], checkCancelled)

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
        FUNCTION_COUNT -> values.size.toLong()
        FUNCTION_SUM -> values.filterNotNull().sumOf { toDouble(it) }
        FUNCTION_AVG -> values.filterNotNull().let { if (it.isEmpty()) null else it.sumOf { v -> toDouble(v) } / it.size }
        FUNCTION_MIN -> values.filterNotNull().minByOrNull { toDouble(it) }
        FUNCTION_MAX -> values.filterNotNull().maxByOrNull { toDouble(it) }
        FUNCTION_COLLECT -> values.toList()
        FUNCTION_PERCENTILE_CONT -> percentileCont(values, DEFAULT_PERCENTILE)
        FUNCTION_PERCENTILE_DISC -> percentileDisc(values, DEFAULT_PERCENTILE)
        FUNCTION_STDEV -> stdev(values, sample = true)
        FUNCTION_STDEVP -> stdev(values, sample = false)
        else -> throw CypherException("Unknown aggregation: $name")
    }

    internal fun aggregate(
        name: String,
        values: List<Any?>,
        checkCancelled: () -> Unit
    ): Any? = when (name.lowercase()) {
        FUNCTION_COUNT -> values.size.toLong()
        FUNCTION_SUM -> sum(values, checkCancelled)
        FUNCTION_AVG -> average(values, checkCancelled)
        FUNCTION_MIN -> extremum(values, checkCancelled, minimum = true)
        FUNCTION_MAX -> extremum(values, checkCancelled, minimum = false)
        FUNCTION_COLLECT -> collect(values, checkCancelled)
        FUNCTION_PERCENTILE_CONT -> percentileCont(values, DEFAULT_PERCENTILE, checkCancelled)
        FUNCTION_PERCENTILE_DISC -> percentileDisc(values, DEFAULT_PERCENTILE, checkCancelled)
        FUNCTION_STDEV -> stdev(values, sample = true, checkCancelled)
        FUNCTION_STDEVP -> stdev(values, sample = false, checkCancelled)
        else -> throw CypherException("Unknown aggregation: $name")
    }

    /**
     * Aggregation with an explicit percentile parameter.
     */
    fun aggregate(name: String, values: List<Any?>, percentile: Double): Any? = when (name.lowercase()) {
        FUNCTION_PERCENTILE_CONT -> percentileCont(values, percentile)
        FUNCTION_PERCENTILE_DISC -> percentileDisc(values, percentile)
        else -> aggregate(name, values)
    }

    fun isAggregation(name: String): Boolean = name.lowercase() in AGGREGATION_NAMES

    // ========================================================================
    // Implementation details
    // ========================================================================

    private val AGGREGATION_NAMES = setOf(
        FUNCTION_COUNT, FUNCTION_SUM, FUNCTION_AVG, FUNCTION_MIN, FUNCTION_MAX, FUNCTION_COLLECT,
        FUNCTION_PERCENTILE_CONT, FUNCTION_PERCENTILE_DISC, FUNCTION_STDEV, FUNCTION_STDEVP
    )

    private fun id(value: Any?): Any? = when (value) {
        is Node -> value.id.value
        is QualifiedNode -> value.node.id.value
        else -> null
    }

    private fun elementId(value: Any?): String? = when (value) {
        is QualifiedNode -> value.elementId
        is Node -> value.id.value.toString()
        else -> null
    }

    private fun graphId(value: Any?): String? = when (value) {
        is QualifiedNode -> value.graphId
        is QualifiedEdge -> value.graphId
        is QualifiedPath -> value.graphId
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
        is QualifiedNode -> NodePropertyAccessor.getAllProperties(value.node) + mapOf(
            "graphId" to value.graphId,
            "elementId" to value.elementId,
            "qualifiedId" to value.elementId
        )
        else -> null
    }

    private fun keys(value: Any?): List<String>? = when (value) {
        is Node -> NodePropertyAccessor.getAllProperties(value).keys.toList()
        is QualifiedNode -> properties(value)?.keys?.toList()
        else -> null
    }

    internal fun labels(value: Any?): List<String> = when (value) {
        is QualifiedNode -> labels(value.node)
        is CallSiteNode -> listOf("CallSiteNode")
        is IntConstant -> listOf("IntConstant", CONSTANT_LABEL)
        is StringConstant -> listOf("StringConstant", CONSTANT_LABEL)
        is LongConstant -> listOf("LongConstant", CONSTANT_LABEL)
        is FloatConstant -> listOf("FloatConstant", CONSTANT_LABEL)
        is DoubleConstant -> listOf("DoubleConstant", CONSTANT_LABEL)
        is BooleanConstant -> listOf("BooleanConstant", CONSTANT_LABEL)
        is NullConstant -> listOf("NullConstant", CONSTANT_LABEL)
        is EnumConstant -> listOf("EnumConstant", CONSTANT_LABEL)
        is LocalVariable -> listOf("LocalVariable")
        is FieldNode -> listOf("FieldNode")
        is ParameterNode -> listOf("ParameterNode")
        is ReturnNode -> listOf("ReturnNode")
        is ResourceFileNode -> listOf("ResourceFileNode", "ResourceFile")
        is ResourceValueNode -> listOf("ResourceValueNode", "ResourceValue", "Resource")
        is AnnotationNode -> listOf("AnnotationNode", "Annotation")
        else -> emptyList()
    }

    internal fun type(value: Any?): String? = when (value) {
        is QualifiedEdge -> type(value.edge)
        is DataFlowEdge -> "DATAFLOW"
        is CallEdge -> "CALL"
        is TypeEdge -> "TYPE"
        is ControlFlowEdge -> "CONTROL_FLOW"
        is ResourceEdge -> when (value.kind) {
            ResourceRelation.OPENS -> "RESOURCE_OPEN"
            ResourceRelation.LOADS -> "RESOURCE_LOAD"
            ResourceRelation.BUNDLE_CANDIDATE -> "RESOURCE_BUNDLE_CANDIDATE"
            ResourceRelation.LOOKUP -> "RESOURCE_LOOKUP"
            ResourceRelation.ENUMERATES -> "RESOURCE_KEYS"
        }
        else -> null
    }

    private fun size(value: Any?): Int? = when (value) {
        is String -> value.length
        is List<*> -> value.size
        is QualifiedPath -> value.edges.size
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

    private fun reverse(value: Any?, checkCancelled: (() -> Unit)?): Any? = when (value) {
        is String -> reverseString(value, checkCancelled)
        is List<*> -> reverseList(value, checkCancelled)
        else -> null
    }

    private fun reverseString(value: String, checkCancelled: (() -> Unit)?): String {
        if (checkCancelled == null) return value.reversed()
        val result = StringBuilder(value.length)
        for (sourceIndex in value.lastIndex downTo 0) {
            val copied = value.lastIndex - sourceIndex
            if ((copied and CANCELLATION_POLL_MASK) == 0) checkCancelled()
            result.append(value[sourceIndex])
        }
        checkCancelled()
        return result.toString()
    }

    private fun reverseList(value: List<*>, checkCancelled: (() -> Unit)?): List<*> {
        if (checkCancelled == null) return value.reversed()
        val result = ArrayList<Any?>(value.size)
        for (sourceIndex in value.lastIndex downTo 0) {
            val copied = value.lastIndex - sourceIndex
            if ((copied and CANCELLATION_POLL_MASK) == 0) checkCancelled()
            result.add(value[sourceIndex])
        }
        checkCancelled()
        return result
    }

    private fun tail(value: Any?, checkCancelled: (() -> Unit)?): List<*>? = when {
        value !is List<*> -> null
        checkCancelled == null -> value.drop(1)
        else -> copyTail(value, checkCancelled)
    }

    private fun copyTail(list: List<*>, checkCancelled: () -> Unit): List<*> {
        val result = ArrayList<Any?>(maxOf(list.size - 1, 0))
        for (sourceIndex in 1 until list.size) {
            if (((sourceIndex - 1) and CANCELLATION_POLL_MASK) == 0) checkCancelled()
            result.add(list[sourceIndex])
        }
        checkCancelled()
        return result
    }

    private fun range(args: List<Any?>, checkCancelled: (() -> Unit)?): List<Long> {
        val start = (args[0] as Number).toLong()
        val end = (args[1] as Number).toLong()
        val step = if (args.size > 2) (args[2] as Number).toLong() else 1L
        if (step == 0L) throw CypherException("Step cannot be zero in range()")
        if (checkCancelled == null) {
            return if (step > 0) (start..end step step).toList()
            else (start downTo end step -step).toList()
        }
        val values = ArrayList<Long>()
        var index = 0
        for (value in LongProgression.fromClosedRange(start, end, step)) {
            if ((index and CANCELLATION_POLL_MASK) == 0) checkCancelled()
            values.add(value)
            index++
        }
        checkCancelled()
        return values
    }

    private fun nodes(value: Any?, checkCancelled: (() -> Unit)?): List<*>? = when (value) {
        is QualifiedPath -> value.nodes
        is PathFinder.Path -> value.nodes
        is List<*> -> filterList(value, checkCancelled) { it is Node || it is QualifiedNode }
        else -> null
    }

    private fun relationships(value: Any?, checkCancelled: (() -> Unit)?): List<*>? = when (value) {
        is QualifiedPath -> value.edges
        is PathFinder.Path -> value.edges
        is List<*> -> filterList(value, checkCancelled) { it is Edge || it is QualifiedEdge }
        else -> null
    }

    private fun filterList(
        values: List<*>,
        checkCancelled: (() -> Unit)?,
        predicate: (Any?) -> Boolean
    ): List<*> {
        if (checkCancelled == null) return values.filter(predicate)
        val result = ArrayList<Any?>()
        for ((index, value) in values.withIndex()) {
            if ((index and CANCELLATION_POLL_MASK) == 0) checkCancelled()
            if (predicate(value)) result.add(value)
        }
        checkCancelled()
        return result
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
    private fun roundHalfUp(value: Double): Double = floor(value + DEFAULT_PERCENTILE)

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

    private fun sum(values: List<Any?>, checkCancelled: () -> Unit): Double {
        var sum = 0.0
        for ((index, value) in values.withIndex()) {
            pollCancellation(index, checkCancelled)
            if (value != null) sum += toDouble(value)
        }
        checkCancelled()
        return sum
    }

    private fun average(values: List<Any?>, checkCancelled: () -> Unit): Double? {
        var sum = 0.0
        var count = 0
        for ((index, value) in values.withIndex()) {
            pollCancellation(index, checkCancelled)
            if (value != null) {
                sum += toDouble(value)
                count++
            }
        }
        checkCancelled()
        return if (count == 0) null else sum / count
    }

    private fun extremum(
        values: List<Any?>,
        checkCancelled: () -> Unit,
        minimum: Boolean
    ): Any? {
        var selected: Any? = null
        var selectedNumber = 0.0
        for ((index, value) in values.withIndex()) {
            pollCancellation(index, checkCancelled)
            if (value == null) continue
            val number = toDouble(value)
            val comparison = number.compareTo(selectedNumber)
            if (selected == null || if (minimum) comparison < 0 else comparison > 0) {
                selected = value
                selectedNumber = number
            }
        }
        checkCancelled()
        return selected
    }

    private fun collect(values: List<Any?>, checkCancelled: () -> Unit): List<Any?> {
        val result = ArrayList<Any?>(values.size)
        for ((index, value) in values.withIndex()) {
            pollCancellation(index, checkCancelled)
            result.add(value)
        }
        checkCancelled()
        return result
    }

    private fun percentileCont(values: List<Any?>, p: Double, checkCancelled: () -> Unit): Any? {
        val nums = numericValues(values, checkCancelled)
        sort(nums, checkCancelled)
        if (nums.isEmpty()) return null
        val idx = p * (nums.size - 1)
        val lower = nums[idx.toInt()]
        val upper = nums[minOf(idx.toInt() + 1, nums.size - 1)]
        val frac = idx - idx.toInt()
        return lower + frac * (upper - lower)
    }

    private fun percentileDisc(values: List<Any?>, p: Double, checkCancelled: () -> Unit): Any? {
        val nums = numericValues(values, checkCancelled)
        sort(nums, checkCancelled)
        if (nums.isEmpty()) return null
        val idx = kotlin.math.ceil(p * nums.size).toInt() - 1
        return nums[maxOf(0, idx)]
    }

    private fun stdev(values: List<Any?>, sample: Boolean, checkCancelled: () -> Unit): Any? {
        val nums = numericValues(values, checkCancelled)
        if (nums.size < 2) return null
        var sum = 0.0
        for ((index, number) in nums.withIndex()) {
            pollCancellation(index, checkCancelled)
            sum += number
        }
        val mean = sum / nums.size
        var squaredDifferences = 0.0
        for ((index, number) in nums.withIndex()) {
            pollCancellation(index, checkCancelled)
            squaredDifferences += (number - mean).pow(2)
        }
        checkCancelled()
        val divisor = if (sample) nums.size - 1 else nums.size
        return sqrt(squaredDifferences / divisor)
    }

    private fun numericValues(values: List<Any?>, checkCancelled: () -> Unit): MutableList<Double> {
        val numbers = ArrayList<Double>(values.size)
        for ((index, value) in values.withIndex()) {
            pollCancellation(index, checkCancelled)
            if (value != null) numbers.add(toDouble(value))
        }
        checkCancelled()
        return numbers
    }

    private fun sort(values: MutableList<Double>, checkCancelled: () -> Unit) {
        var comparisons = 0
        values.sortWith { left, right ->
            pollCancellation(comparisons++, checkCancelled)
            left.compareTo(right)
        }
        checkCancelled()
    }

    private fun pollCancellation(index: Int, checkCancelled: () -> Unit) {
        if ((index and CANCELLATION_POLL_MASK) == 0) checkCancelled()
    }
}

class CypherException(message: String) : RuntimeException(message)

class CypherAggregationException(val functionName: String) :
    RuntimeException("Aggregation function '$functionName' must be used in RETURN or WITH clause")
